package io.github.yufeiyufei888.hearthcrew.backend;

import java.util.*;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** New-backend membership only. Inventory remains in native player files; old saves are never imported. */
public final class BackendRoster extends SavedData {
    private static final String FILE="hearthcrew_backend_roster";
    public static final List<String> NAMES=List.of("Ember","Moss","Flint");
    public record Member(UUID id,String name,UUID owner,String dimension,String state) {
        public Member {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);
            if(!NAMES.contains(name)||!Set.of("RESERVED","COMMITTED").contains(state)||ResourceLocation.tryParse(dimension)==null)
                throw new IllegalArgumentException("Invalid backend roster; preserve original data");
        }
    }
    private final String backend;
    private final UUID world;
    private final Map<UUID,Member> members=new LinkedHashMap<>();
    private final Map<UUID,CompanionBackend> live=new LinkedHashMap<>();
    private final Map<UUID,String> faults=new LinkedHashMap<>();
    public record Control(boolean paused,boolean stopped) {}
    private final Map<UUID,Control> controls=new LinkedHashMap<>();
    private BackendRoster(String backend,UUID world){this.backend=backend;this.world=world;setDirty();}
    private BackendRoster(CompoundTag tag){
        if(tag.getInt("schema")!=1||!tag.hasUUID("world")||!tag.contains("members",Tag.TAG_LIST)||tag.getString("backend").isBlank())throw new IllegalArgumentException("Invalid backend roster schema; preserve file");
        backend=tag.getString("backend");world=tag.getUUID("world");var names=new HashSet<String>();
        for(var raw:tag.getList("members",Tag.TAG_COMPOUND)){
            var r=(CompoundTag)raw;var m=new Member(r.getUUID("id"),r.getString("name"),r.getUUID("owner"),r.getString("dimension"),r.getString("state"));
            if(members.putIfAbsent(m.id(),m)!=null||!names.add(m.name()))throw new IllegalArgumentException("Duplicate backend identity; preserve file");
            controls.put(m.id(),new Control(r.getBoolean("paused"),r.getBoolean("stopped")));
        }
        if(members.size()>3)throw new IllegalArgumentException("Too many backend companions");
    }
    public static BackendRoster open(MinecraftServer server,BackendProvider provider){
        requireThread(server);var data=server.getWorldPath(LevelResource.ROOT).resolve("data");
        if(!Files.exists(data.resolve(FILE+".dat"))&&Files.exists(data.resolve(FILE+".dat_old")))throw new IllegalStateException("ROSTER_BACKUP_REQUIRES_RECOVERY");
        if(!Files.exists(data.resolve(FILE+".dat"))) {
            for(String legacy:List.of("hearthcrew_players","hearthcrew_world"))
                if(Files.exists(data.resolve(legacy+".dat"))||Files.exists(data.resolve(legacy+".dat_old")))
                    throw new IllegalStateException("LEGACY_WORLD_NOT_MIGRATED: use the existing version for this save");
        }
        var roster=server.overworld().getDataStorage().computeIfAbsent(new Factory<>(()->new BackendRoster(provider.id(),provider.worldId(server)),(tag,lookup)->new BackendRoster(tag)),FILE);
        if(!roster.backend.equals(provider.id())||!roster.world.equals(provider.worldId(server)))throw new IllegalStateException("BACKEND_WORLD_IDENTITY_CONFLICT");
        return roster;
    }
    public UUID world(){return world;}
    public List<Member> members(){return List.copyOf(members.values());}
    public List<CompanionBackend> live(){return List.copyOf(live.values());}
    public void fault(UUID id,String reason){member(id);faults.put(id,reason);}
    public Map<UUID,String> faults(){return Map.copyOf(faults);}
    public Control control(UUID id){member(id);return controls.getOrDefault(id,new Control(false,false));}
    public void control(UUID id,boolean paused,boolean stopped){member(id);controls.put(id,new Control(paused,stopped));setDirty();}
    public Member member(UUID id){var m=members.get(id);if(m==null)throw new IllegalArgumentException("Unknown companion");return m;}
    public CompanionBackend body(UUID id){var b=live.get(id);if(b==null)throw new IllegalStateException("Companion unavailable: "+faults.getOrDefault(id,"not joined"));return b;}
    public CompanionBackend join(MinecraftServer server,BackendProvider provider,String name,UUID owner,ServerLevel initialLevel,Vec3 initialPosition){
        requireThread(server);
        if(!NAMES.contains(name))throw new IllegalArgumentException("Valid companions: Ember, Moss, Flint");
        if(members.values().stream().anyMatch(m->!m.owner().equals(owner)))throw new IllegalStateException("WORLD_OWNER_CONFLICT");
        var member=members.values().stream().filter(m->m.name().equals(name)).findFirst().orElse(null);
        if(member!=null&&live.containsKey(member.id()))return live.get(member.id());
        if(member==null){
            if(server.getPlayerList().getPlayerByName(name)!=null)throw new IllegalStateException("COMPANION_NAME_CONFLICT:"+name);
            member=new Member(UUID.randomUUID(),name,owner,initialLevel.dimension().location().toString(),"RESERVED");
            members.put(member.id(),member);persist(server);
        }
        boolean nativeData=Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(member.id()+".dat"));
        boolean restore=member.state().equals("COMMITTED");
        if(restore&&!nativeData)return refused(member,"NATIVE_PLAYER_DATA_MISSING");
        if(!restore&&nativeData)return refused(member,"JOIN_EFFECT_REQUIRES_RECONCILIATION");
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(member.dimension())));
        if(level==null)return refused(member,"SAVED_DIMENSION_UNAVAILABLE");
        if(!restore&&(initialPosition==null||level!=initialLevel))return refused(member,"INITIAL_SPAWN_POSITION_REQUIRED");
        try {
            var b=provider.create(level,member.id(),member.name(),member.owner(),restore?null:initialPosition,restore);
            if(!b.body().getUUID().equals(member.id())||!b.snapshot().identity().world().equals(world.toString())){b.close();return refused(member,"CREATED_BODY_IDENTITY_MISMATCH");}
            live.put(member.id(),b);faults.remove(member.id());
            var control=control(member.id());if(control.paused()||control.stopped())b.pause();
            members.put(member.id(),new Member(member.id(),member.name(),member.owner(),b.body().level().dimension().location().toString(),"COMMITTED"));
            // The native file must exist before the committed marker promises it can be restored.
            server.getPlayerList().saveAll();persist(server);return b;
        } catch(RuntimeException e){faults.put(member.id(),e.getClass().getSimpleName()+":"+Objects.toString(e.getMessage(),""));throw e;}
    }
    private CompanionBackend refused(Member m,String reason){faults.put(m.id(),reason);throw new IllegalStateException(reason);}
    public void checkpoint(MinecraftServer server){
        requireThread(server);boolean changed=false;
        for(var e:live.entrySet()){
            var m=members.get(e.getKey());String dimension=e.getValue().body().level().dimension().location().toString();
            if(!m.dimension().equals(dimension)){members.put(m.id(),new Member(m.id(),m.name(),m.owner(),dimension,m.state()));changed=true;}
        }
        if(changed)setDirty();
    }
    public void close(MinecraftServer server){
        requireThread(server);checkpoint(server);persist(server);
        for(var entry:List.copyOf(live.entrySet()))try{entry.getValue().close();live.remove(entry.getKey());}catch(RuntimeException e){faults.put(entry.getKey(),"CLOSE_REQUIRES_RECONCILIATION:"+e.getClass().getSimpleName());}
    }
    private void persist(MinecraftServer server){setDirty();server.overworld().getDataStorage().save();}
    private static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Server thread required");}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){
        tag.putInt("schema",1);tag.putString("backend",backend);tag.putUUID("world",world);var rows=new ListTag();
        for(var m:members.values()){var r=new CompoundTag();r.putUUID("id",m.id());r.putString("name",m.name());r.putUUID("owner",m.owner());r.putString("dimension",m.dimension());r.putString("state",m.state());var control=control(m.id());r.putBoolean("paused",control.paused());r.putBoolean("stopped",control.stopped());rows.add(r);}tag.put("members",rows);return tag;
    }
}
