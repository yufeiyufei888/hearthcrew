package io.github.yufeiyufei888.hearthcrew.backend.numen;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable facility orders. World ticks produce items; a submission is never an output receipt. */
final class FurnaceWork extends SavedData {
    private final Map<String,CompoundTag> orders=new LinkedHashMap<>();
    private final Map<String,String> active=new HashMap<>();
    private record Lease(ServerLevel level,ChunkPos chunk) {}
    private final Map<String,Lease> leases=new HashMap<>();
    private static final TicketType<String> TICKET=TicketType.create("hearthcrew_processing",Comparator.<String>naturalOrder(),40);
    private static final Set<String> STATES=Set.of("RESERVED","INPUT_DEPOSITED","PROCESSING","OUTPUT_READY","COLLECTING","COMPLETED","FAILED","RECONCILE_REQUIRED");
    FurnaceWork(){}
    private FurnaceWork(CompoundTag tag){
        if(tag.getInt("schema")!=1)throw new IllegalArgumentException("Invalid processing journal");
        for(var raw:tag.getList("orders",Tag.TAG_COMPOUND)){
            var row=((CompoundTag)raw).copy();String id=row.getString("id");
            if(id.isBlank()||!row.hasUUID("owner")||!id.equals(row.getUUID("owner")+"/"+row.getString("action"))||row.getString("action").isBlank()||!STATES.contains(row.getString("state")))throw new IllegalArgumentException("Invalid processing identity/state");
            if(ResourceLocation.tryParse(row.getString("dimension"))==null||!row.contains("position",Tag.TAG_LONG)||row.getInt("inputCount")<1||row.getInt("inputCount")>64||row.getInt("fuelCount")<1||row.getInt("fuelCount")>64||row.getInt("yield")<1||row.getInt("expected")!=row.getInt("inputCount")*row.getInt("yield")||row.getInt("expected")>64||row.getInt("collected")<0||row.getInt("collected")>row.getInt("expected"))throw new IllegalArgumentException("Invalid processing quantities/site");
            for(var field:List.of("input","fuel","output")){var key=ResourceLocation.tryParse(row.getString(field));if(key==null||!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)||item(row,field)==Items.AIR)throw new IllegalArgumentException("Invalid processing item");}
            if(row.getString("state").equals("COMPLETED")&&row.getInt("collected")!=row.getInt("expected"))throw new IllegalArgumentException("Completed order lacks output evidence");
            if(orders.putIfAbsent(id,row)!=null)throw new IllegalArgumentException("Duplicate processing identity");
            if(Set.of("RESERVED","INPUT_DEPOSITED","COLLECTING").contains(row.getString("state")))row.putString("state","RECONCILE_REQUIRED");
            if(!terminal(row)&&active.putIfAbsent(site(row),id)!=null)throw new IllegalArgumentException("Conflicting processing reservations");
        }
    }
    static FurnaceWork get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(new Factory<>(FurnaceWork::new,(t,p)->new FurnaceWork(t)),"hearthcrew_processing");}
    static FurnaceWork load(CompoundTag tag){return new FurnaceWork(tag);}
    private static boolean terminal(CompoundTag r){return Set.of("COMPLETED","FAILED").contains(r.getString("state"));}
    private static String site(CompoundTag r){return r.getString("dimension")+"/"+r.getLong("position");}
    CompoundTag at(ServerLevel level,BlockPos p){var id=active.get(level.dimension().location()+"/"+p.asLong());return id==null?null:orders.get(id);}
    boolean permits(ServerPlayer body,BlockPos p){var r=at(body.serverLevel(),p);return r==null||r.getUUID("owner").equals(body.getUUID());}
    CompoundTag reserve(ServerPlayer body,String action,BlockPos p,Item input,int count,Item fuel,int fuelCount,Item output,int yield){
        if(at(body.serverLevel(),p)!=null)throw new IllegalStateException("WORKSTATION_ORDER_BUSY");
        if(active.size()>=6||active.values().stream().map(orders::get).filter(r->r.getUUID("owner").equals(body.getUUID())).count()>=2)throw new IllegalStateException("PROCESSING_ORDER_LIMIT");
        String id=body.getUUID()+"/"+action;if(orders.containsKey(id))throw new IllegalStateException("PROCESSING_ID_ALREADY_RECORDED");
        var r=new CompoundTag();r.putString("id",id);r.putUUID("owner",body.getUUID());r.putString("action",action);r.putString("dimension",body.level().dimension().location().toString());r.putLong("position",p.asLong());
        r.putString("input",key(input));r.putInt("inputCount",count);r.putString("fuel",key(fuel));r.putInt("fuelCount",fuelCount);r.putString("output",key(output));r.putInt("yield",yield);r.putInt("expected",count*yield);r.putInt("collected",0);r.putString("state","RESERVED");
        orders.put(id,r);active.put(site(r),id);persist(body.server);return r;
    }
    static String key(Item item){return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();}
    static Item item(CompoundTag r,String field){return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(r.getString(field)));}
    void phase(MinecraftServer server,CompoundTag r,String state){
        if(!STATES.contains(state))throw new IllegalArgumentException("Invalid processing phase");
        if(terminal(r))return;r.putString("state",state);
        if(terminal(r)){active.remove(site(r));release(r.getString("id"));}persist(server);
    }
    void persist(MinecraftServer server){setDirty();server.overworld().getDataStorage().save();}
    void reconcile(ServerLevel level,BlockPos p){
        var r=at(level,p);if(r==null||!Set.of("PROCESSING","OUTPUT_READY").contains(r.getString("state")))return;
        if(!level.hasChunkAt(p))return; // unloaded is unknown, not consumed/lost/finished
        if(!(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity furnace)){phase(level.getServer(),r,"RECONCILE_REQUIRED");return;}
        if(!FacilityAccess.denial(level,p).isEmpty()){phase(level.getServer(),r,"RECONCILE_REQUIRED");return;}
        var input=furnace.getItem(0);var output=furnace.getItem(2);
        if(!input.isEmpty()&&!input.is(item(r,"input"))||!output.isEmpty()&&!output.is(item(r,"output"))){phase(level.getServer(),r,"RECONCILE_REQUIRED");return;}
        int conserved=input.getCount()*r.getInt("yield")+output.getCount()+r.getInt("collected");
        if(conserved!=r.getInt("expected")){phase(level.getServer(),r,"RECONCILE_REQUIRED");return;}
        String state=input.isEmpty()&&output.getCount()==r.getInt("expected")-r.getInt("collected")?"OUTPUT_READY":"PROCESSING";
        if(!state.equals(r.getString("state")))phase(level.getServer(),r,state);
    }
    List<Map<String,Object>> summary(UUID owner){return active.values().stream().map(orders::get).filter(r->r.getUUID("owner").equals(owner)).map(FurnaceWork::facts).toList();}
    static Map<String,Object> facts(CompoundTag r){var p=BlockPos.of(r.getLong("position"));return Map.of("orderId",r.getString("action"),"owner",r.getUUID("owner").toString(),"dimension",r.getString("dimension"),"position",Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ()),"state",r.getString("state"),"output",r.getString("output"),"expected",r.getInt("expected"),"collected",r.getInt("collected"));}
    private void release(String id){var lease=leases.remove(id);if(lease!=null)lease.level().getChunkSource().removeRegionTicket(TICKET,lease.chunk(),1,id);}
    void releaseOwner(UUID owner){for(var r:orders.values())if(r.getUUID("owner").equals(owner))release(r.getString("id"));}
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();if(!BackendProtection.configured(server)||server.overworld().getGameTime()%20!=0)return;var book=get(server);
        for(var id:List.copyOf(book.active.values())){
            var r=book.orders.get(id);var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(r.getString("dimension"))));if(level==null)continue;
            var p=BlockPos.of(r.getLong("position"));var body=server.getPlayerList().getPlayer(r.getUUID("owner"));
            boolean online=body instanceof com.dwinovo.numen.entity.NumenPlayer nativeBody&&NumenBackend.owns(body.getUUID())&&nativeBody.getOwnerUuid()!=null&&server.getPlayerList().getPlayer(nativeBody.getOwnerUuid())!=null;
            if(online&&Set.of("PROCESSING","OUTPUT_READY").contains(r.getString("state"))){var chunk=new ChunkPos(p);level.getChunkSource().addRegionTicket(TICKET,chunk,1,id);book.leases.put(id,new Lease(level,chunk));}
            else book.release(id);
            book.reconcile(level,p);
        }
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppingEvent event){if(BackendProtection.configured(event.getServer())){var book=get(event.getServer());for(var id:List.copyOf(book.leases.keySet()))book.release(id);}}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider p){t.putInt("schema",1);var list=new ListTag();orders.values().forEach(r->list.add(r.copy()));t.put("orders",list);return t;}
}
