package io.github.yufeiyufei888.hearthcrew.runtime;

import com.mojang.authlib.GameProfile;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/** World-owned identity index. Native playerdata owns inventory; archived legacy NBT is never replayed. */
public final class CrewPlayers extends SavedData {
    private final Map<UUID,CompoundTag> records=new LinkedHashMap<>();
    private final Map<UUID,CompoundTag> migrationBackups=new LinkedHashMap<>();
    private final Set<UUID> respawning=new HashSet<>();
    private boolean closing;
    public CrewPlayers(){}
    private CrewPlayers(CompoundTag tag){for(var row:tag.getList("players",Tag.TAG_COMPOUND)){var r=(CompoundTag)row;if(r.hasUUID("id"))records.put(r.getUUID("id"),r.copy());}
        for(var row:tag.getList("legacy",Tag.TAG_COMPOUND)){var r=(CompoundTag)row;if(r.hasUUID("CrewIdentity"))migrationBackups.put(r.getUUID("CrewIdentity"),r.copy());}}
    public static CrewPlayers get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CrewPlayers::new,(tag,provider)->new CrewPlayers(tag)),"hearthcrew_players");}
    public static CompanionEntity create(ServerLevel level,String name,UUID owner,Vec3 pos){
        var store=get(level.getServer());if(store.closing)throw new IllegalStateException("world closing");
        if(level.getServer().getPlayerList().getPlayerByName(name)!=null)throw new IllegalStateException("player name conflict: "+name);
        if(owner!=null&&store.records.size()>=3)throw new IllegalStateException("three companion limit");
        var id=UUID.randomUUID();var player=join(level,id,name,owner,pos);
        var record=new CompoundTag();record.putUUID("id",id);record.putString("name",name);if(owner!=null)record.putUUID("owner",owner);store.records.put(id,record);store.setDirty();return player;
    }
    public static CompanionEntity create(ServerLevel level){return create(level,"HC"+UUID.randomUUID().toString().substring(0,8),null,Vec3.atBottomCenterOf(level.getSharedSpawnPos()));}
    private static CompanionEntity join(ServerLevel level,UUID id,String name,UUID owner,Vec3 pos){
        var server=level.getServer();if(server.getPlayerList().getPlayer(id)!=null||server.getPlayerList().getPlayerByName(name)!=null)throw new IllegalStateException("player identity already present");
        var profile=new GameProfile(id,name);var player=new CompanionEntity(server,level,profile,ClientInformation.createDefault());
        var connection=new LocalPlayerConnection();
        try {
            server.getPlayerList().placeNewPlayer(connection,player,CommonListenerCookie.createInitial(profile,false));
            player.setOwner(owner);player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            if(pos!=null)player.teleportTo(level,pos.x,pos.y,pos.z,0,0);
            return player;
        } catch(RuntimeException failure) {
            if(server.getPlayerList().getPlayer(id)==player)server.getPlayerList().remove(player);
            connection.disconnect(net.minecraft.network.chat.Component.literal("join failed"));throw failure;
        }
    }
    public void restore(MinecraftServer server){closing=false;for(var r:List.copyOf(records.values())){
        var id=r.getUUID("id");if(server.getPlayerList().getPlayer(id)!=null)continue;
        if("prepared".equals(r.getString("migrationState"))){org.slf4j.LoggerFactory.getLogger(CrewPlayers.class).error("Migration awaiting reconciliation; both body activation and inventory replay refused: {}",id);continue;}
        try{join(server.overworld(),id,r.getString("name"),r.hasUUID("owner")?r.getUUID("owner"):null,null);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(CrewPlayers.class).error("Companion restore refused {}: {}",id,e.toString());}
    }}
    public void initializeFor(ServerPlayer owner){addMissingFor(owner);}
    /** Server-thread, owner-only, fixed roster; repeated requests never duplicate a record. */
    public int addMissingFor(ServerPlayer owner){
        if(closing||owner instanceof CompanionEntity)throw new IllegalStateException("world unavailable");
        if(CrewWorldData.get(owner.server).chunkRecoveryInvalid())throw new IllegalStateException("伙伴登记待核对");
        if(records.isEmpty()&&!CrewWorldData.get(owner.server).chunkAnchors().isEmpty())throw new IllegalStateException("旧伙伴迁移待核对，请勿重复创建");
        String[] names={"Ember","Moss","Flint"};var missing=new ArrayList<Integer>();
        for(int i=0;i<names.length;i++){
            String name=names[i];var live=owner.server.getPlayerList().getPlayerByName(name);
            if(live!=null){if(!(live instanceof CompanionEntity body)||!owner.getUUID().equals(body.ownerId()))throw new IllegalStateException("名字已被占用："+name);continue;}
            if(records.values().stream().anyMatch(r->name.equals(r.getString("name"))))throw new IllegalStateException("伙伴已登记但不在线，需核对后恢复："+name);
            missing.add(i);
        }
        if(records.size()+missing.size()>3)throw new IllegalStateException("伙伴登记数量已满");
        var places=new ArrayList<Vec3>();
        for(int i:missing){
            Vec3 place=null;
            for(int radius=1;radius<=6&&place==null;radius++)for(int dx=-radius;dx<=radius&&place==null;dx++)for(int dz=-radius;dz<=radius&&place==null;dz++)for(int dy:new int[]{0,1,-1,2,-2}){
                var p=owner.blockPosition().offset(dx,dy,dz);var point=Vec3.atBottomCenterOf(p);
                if(places.stream().anyMatch(other->other.distanceToSqr(point)<2)||point.distanceToSqr(owner.position())<2)continue;
                if(owner.level().hasChunkAt(p)&&owner.level().getFluidState(p).isEmpty()&&owner.level().getBlockState(p.below()).isFaceSturdy(owner.level(),p.below(),net.minecraft.core.Direction.UP)&&owner.level().noCollision(owner,owner.getBoundingBox().move(point.subtract(owner.position())))){place=point;break;}
            }
            if(place==null)throw new IllegalStateException("附近没有足够安全站位，请走到空旷陆地后重试");places.add(place);
        }
        for(int n=0;n<missing.size();n++){int i=missing.get(n);var player=create(owner.serverLevel(),names[i],owner.getUUID(),places.get(n));player.setSkinIndex(i);WorldEvents.trackCompanion(player);}
        return missing.size();
    }
    public void migrate(LegacyCompanionEntity legacy){
        if(closing)return;var tag=legacy.archive();if(!tag.hasUUID("CrewIdentity"))return;
        UUID id=tag.getUUID("CrewIdentity");
        if(records.containsKey(id)){
            var row=records.get(id);
            if(row.getBoolean("migrated")&&!"prepared".equals(row.getString("migrationState")))legacy.discard();
            return;
        }
        if(records.size()>=3)return;
        migrationBackups.putIfAbsent(id,tag.copy());setDirty();
        var name=legacy.getName().getString();var level=(ServerLevel)legacy.level();
        if(level.getServer().getPlayerList().getPlayerByName(name)!=null)return;
        // Backup is durable before creating a player or retiring a legacy body.
        level.getDataStorage().save();level.getServer().overworld().getDataStorage().save();
        // A durable prepared marker forbids replay after a crash at any import step.
        var prepared=new CompoundTag();prepared.putUUID("id",id);prepared.putString("name",name);prepared.putString("migrationState","prepared");
        if(tag.hasUUID("CrewOwner"))prepared.putUUID("owner",tag.getUUID("CrewOwner"));records.put(id,prepared);setDirty();level.getServer().overworld().getDataStorage().save();
        CompanionEntity player=null;
        try {
        player=join(level,id,name,tag.hasUUID("CrewOwner")?tag.getUUID("CrewOwner"):null,legacy.position());
        if(!player.getInventory().isEmpty())throw new IllegalStateException("migration found existing native inventory; legacy preserved");
        for(var value:tag.getList("CrewInventory",Tag.TAG_COMPOUND)){var row=(CompoundTag)value;int slot=row.getInt("slot");if(slot<0||slot>=36)throw new IllegalStateException("invalid legacy slot");player.getInventory().setItem(slot,net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(),row.getCompound("item")));}
        int index=0;for(var value:tag.getList("ArmorItems",Tag.TAG_COMPOUND)){
            var slots=List.of(net.minecraft.world.entity.EquipmentSlot.FEET,net.minecraft.world.entity.EquipmentSlot.LEGS,net.minecraft.world.entity.EquipmentSlot.CHEST,net.minecraft.world.entity.EquipmentSlot.HEAD);
            if(index<4)player.setItemSlot(slots.get(index++),net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(),(CompoundTag)value));}
        var hands=tag.getList("HandItems",Tag.TAG_COMPOUND);if(hands.size()>1)player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(),hands.getCompound(1)));
        player.selectSlot(Math.clamp(tag.getInt("CrewSelected"),0,8));player.getFoodData().setFoodLevel(tag.contains("CrewFood")?tag.getInt("CrewFood"):20);player.setHealth(legacy.getHealth());player.setSkinIndex(tag.getInt("CrewSkin"));
        if(tag.contains("CrewSaturation"))player.getFoodData().setSaturation(tag.getFloat("CrewSaturation"));
        if(tag.contains("CrewActionLedger"))player.executor().restoreLedger(tag.getCompound("CrewActionLedger"));
        level.getServer().getPlayerList().saveAll();
        var row=new CompoundTag();row.putUUID("id",id);row.putString("name",name);if(player.ownerId()!=null)row.putUUID("owner",player.ownerId());row.putBoolean("migrated",true);row.putString("migrationState","committed");records.put(id,row);setDirty();
        level.getServer().overworld().getDataStorage().save();legacy.discard();WorldEvents.trackCompanion(player);
        } catch(RuntimeException failure){
            if(player!=null&&level.getServer().getPlayerList().getPlayer(id)==player){level.getServer().getPlayerList().remove(player);player.connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("migration requires reconciliation"));}
            throw failure;
        }
    }
    public static void queueRespawn(CompanionEntity player){var store=get(player.server);if(store.closing||!store.respawning.add(player.getUUID()))return;
        player.server.execute(()->{try{if(!store.closing&&player.server.getPlayerList().getPlayer(player.getUUID())==player){var next=player.server.getPlayerList().respawn(player,false,Entity.RemovalReason.KILLED);next.connection.player=next;if(!(next instanceof CompanionEntity))throw new IllegalStateException("companion respawn adapter missing");}}finally{store.respawning.remove(player.getUUID());}});}
    public void close(MinecraftServer server){closing=true;server.getPlayerList().saveAll();for(var p:List.copyOf(server.getPlayerList().getPlayers()))if(p instanceof CompanionEntity){((CompanionEntity)p).haltInputs();server.getPlayerList().remove(p);p.connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("world closed"));}}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider){var players=new ListTag();records.values().forEach(r->players.add(r.copy()));tag.put("players",players);var backups=new ListTag();migrationBackups.values().forEach(r->backups.add(r.copy()));tag.put("legacy",backups);return tag;}
}
