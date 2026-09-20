package io.github.yufeiyufei888.hearthcrew.backend;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Stable wire requests/results separate from the native task implementation. No implicit replay. */
public final class BackendActions extends SavedData {
    private static final Gson JSON=new GsonBuilder().registerTypeAdapter(ResourceLocation.class,
        (JsonSerializer<ResourceLocation>)(v,t,c)->new JsonPrimitive(v.toString())).create();
    public record Entry(long sequence,UUID companion,String actionId,long generation,String world,String order,
                        String priority,String state,String evidence,long gameTick) {}
    private final Map<UUID,LinkedHashMap<String,Entry>> rows=new LinkedHashMap<>();
    private long sequence;
    private final NavigableMap<Long,Entry> changes=new TreeMap<>();
    private BackendActions(){}
    private BackendActions(CompoundTag tag){
        if(tag.getInt("schema")!=1)throw new IllegalArgumentException("Invalid backend action ledger; preserve file");
        sequence=tag.getLong("sequence");
        for(var raw:tag.getList("rows",Tag.TAG_COMPOUND)){
            var t=(CompoundTag)raw;String state=t.getString("state");if(!terminal(state))state="RECONCILE_REQUIRED";
            var e=new Entry(t.getLong("sequence"),t.getUUID("companion"),t.getString("id"),t.getLong("generation"),t.getString("world"),t.getString("order"),t.getString("priority"),state,t.getString("evidence"),t.getLong("tick"));
            if(e.actionId().isBlank()||e.sequence()<1||e.sequence()>sequence||!JsonParser.parseString(e.order()).isJsonObject()||rows.computeIfAbsent(e.companion(),k->new LinkedHashMap<>()).putIfAbsent(e.actionId(),e)!=null)
                throw new IllegalArgumentException("Invalid/duplicate action evidence; preserve file");
        }
        for(var own:rows.values())for(var e:own.values())changes.put(e.sequence(),e);
        setDirty();
    }
    public static BackendActions get(MinecraftServer server){requireThread(server);return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(BackendActions::new,(t,p)->new BackendActions(t)),"hearthcrew_backend_actions");}
    public Entry dispatch(MinecraftServer server,CompanionBackend backend,CompanionBackend.Request request){
        requireThread(server);if(!backend.snapshot().identity().equals(request.identity()))throw new IllegalArgumentException("STALE_EXECUTION_CONTEXT");
        String order=JSON.toJson(request.order());var own=rows.computeIfAbsent(request.identity().companion(),k->new LinkedHashMap<>());var previous=own.get(request.actionId());
        if(previous!=null){
            if(!previous.world().equals(request.identity().world())||!previous.order().equals(order)||!previous.priority().equals(request.priority().name()))throw new IllegalArgumentException("ACTION_ID_CONFLICT");
            reconcile(server,backend,previous);return own.get(request.actionId());
        }
        var pending=new Entry(++sequence,request.identity().companion(),request.actionId(),request.identity().generation(),request.identity().world(),order,request.priority().name(),"RECONCILE_REQUIRED","submission has not been confirmed",server.overworld().getGameTime());
        own.put(request.actionId(),pending);changes.put(pending.sequence(),pending);persist(server); // identity durable before any backend operation
        try {backend.dispatch(request);reconcile(server,backend,pending);}
        catch(RuntimeException failure){
            // A throw after submission may have world effects. Query native evidence; never retry.
            if(backend.lookup(request.actionId()).isPresent())reconcile(server,backend,pending);
            else put(pending,"FAILED","request rejected before native registration: "+failure.getClass().getSimpleName()+":"+Objects.toString(failure.getMessage(),""),server.overworld().getGameTime());
        }
        persist(server);return own.get(request.actionId());
    }
    public void reconcile(MinecraftServer server,CompanionBackend backend){
        requireThread(server);var own=rows.get(backend.body().getUUID());if(own==null)return;
        String active=backend.snapshot().actionId();var entry=own.get(active);if(entry!=null&&!terminal(entry.state()))reconcile(server,backend,entry);
    }
    /** Explicit reconnect/recovery pass; never repeated by the ordinary per-tick path. */
    public void reconcileHistory(MinecraftServer server,CompanionBackend backend){
        requireThread(server);var own=rows.get(backend.body().getUUID());if(own==null)return;
        for(var e:List.copyOf(own.values()))if(!terminal(e.state()))reconcile(server,backend,e);
    }
    private void reconcile(MinecraftServer server,CompanionBackend backend,Entry e){
        if(terminal(e.state()))return;
        backend.lookup(e.actionId()).ifPresent(observed->{
            if(!observed.identity().world().equals(e.world())||observed.actionGeneration()!=e.generation())return;
            String state=normalize(observed.state());
            // Running old-generation work cannot acquire the new body's execution authority.
            if(observed.identity().generation()!=e.generation()&&!terminal(state))state="RECONCILE_REQUIRED";
            if(!state.equals(e.state())||terminal(state)&&!observed.result().equals(e.evidence()))put(e,state,observed.result(),server.overworld().getGameTime());
        });
    }
    private void put(Entry e,String state,String evidence,long tick){
        if(terminal(e.state()))return;
        var update=new Entry(++sequence,e.companion(),e.actionId(),e.generation(),e.world(),e.order(),e.priority(),state,evidence,tick);
        rows.get(e.companion()).put(e.actionId(),update);changes.remove(e.sequence());changes.put(update.sequence(),update);setDirty();
    }
    public long revision(){return sequence;}
    public List<Entry> changesAfter(long cursor,int limit){if(limit<1||limit>64)throw new IllegalArgumentException("event limit 1..64");return changes.tailMap(cursor,false).values().stream().limit(limit).toList();}
    public List<Entry> page(UUID companion,long before,int limit){if(limit<1||limit>64)throw new IllegalArgumentException("history limit 1..64");return rows.getOrDefault(companion,new LinkedHashMap<>()).values().stream().filter(e->e.sequence()<before).sorted(Comparator.comparingLong(Entry::sequence).reversed()).limit(limit).toList();}
    public Optional<Entry> find(UUID companion,String id){return Optional.ofNullable(rows.getOrDefault(companion,new LinkedHashMap<>()).get(id));}
    public long latestSequence(UUID companion){return rows.getOrDefault(companion,new LinkedHashMap<>()).values().stream().mapToLong(Entry::sequence).max().orElse(0);}
    public static JsonObject wire(Entry e){
        var row=new JsonObject();row.addProperty("sequence",e.sequence());row.add("id",JSON.toJsonTree(Map.of("value",e.actionId())));row.addProperty("priority",e.priority());row.add("payload",JsonParser.parseString(e.order()));
        row.add("epoch",JSON.toJsonTree(Map.of("worldGeneration",1,"sessionGeneration",0,"bodyGeneration",e.generation())));row.addProperty("state",e.state());row.addProperty("decision",e.state().equals("RUNNING")?"STARTED":e.state());row.addProperty("gameTick",e.gameTick());row.addProperty("worldTick",e.gameTick());
        String message=e.evidence();try{var proof=JsonParser.parseString(message).getAsJsonObject();if(proof.has("message"))message=proof.get("message").getAsString();}catch(RuntimeException ignored){}
        row.addProperty("message",message);row.addProperty("checkpointAvailable",!terminal(e.state()));return row;
    }
    private static boolean terminal(String s){return Set.of("COMPLETED","FAILED","CANCELLED","EXPIRED","PARTIAL").contains(s);}
    private static String normalize(String s){return switch(s){case "SUCCESS"->"COMPLETED";case "TIMEOUT"->"EXPIRED";case "RUNNING","ACCEPTED","COMPLETED","FAILED","CANCELLED","EXPIRED","PARTIAL"->s;default->"RECONCILE_REQUIRED";};}
    private static void requireThread(MinecraftServer s){if(!s.isSameThread())throw new IllegalStateException("Server thread required");}
    private void persist(MinecraftServer s){setDirty();s.overworld().getDataStorage().save();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("schema",1);tag.putLong("sequence",sequence);var list=new ListTag();for(var own:rows.values())for(var e:own.values()){
        var t=new CompoundTag();t.putLong("sequence",e.sequence());t.putUUID("companion",e.companion());t.putString("id",e.actionId());t.putLong("generation",e.generation());t.putString("world",e.world());t.putString("order",e.order());t.putString("priority",e.priority());t.putString("state",e.state());t.putString("evidence",e.evidence());t.putLong("tick",e.gameTick());list.add(t);
    }tag.put("rows",list);return tag;}
}
