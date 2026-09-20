package io.github.yufeiyufei888.hearthcrew.backend;

import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;
import java.util.*;

/** Host integration is explicitly opt-in during development; never silently replaces a legacy world. */
public final class BackendWorld {
    private static BackendProvider provider;
    private static final Map<MinecraftServer,BackendRoster> WORLDS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,String> FAULTS=new IdentityHashMap<>();
    private BackendWorld(){}
    public static boolean enabled(){return "numen".equals(BackendSelection.mode());}
    public static void initialize(){
        if(!enabled())return;
        var matches=ServiceLoader.load(BackendProvider.class,BackendProvider.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).filter(p->p.id().equals("numen")).toList();
        if(matches.size()!=1)throw new IllegalStateException("Expected exactly one Numen backend provider");provider=matches.getFirst();provider.initialize();
    }
    public static void start(MinecraftServer server){
        if(!enabled())return;
        try {
            if(provider==null)throw new IllegalStateException("Backend provider not initialized");
            var roster=BackendRoster.open(server,provider); // must precede loading/creating legacy saved data
            provider.configure(server,level->CrewWorldData.get(server).protectedPositions(level));WORLDS.put(server,roster);FAULTS.remove(server);
        }catch(RuntimeException failure){FAULTS.put(server,Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));}
    }
    public static String fault(MinecraftServer server){return FAULTS.getOrDefault(server,"");}
    public static BackendRoster roster(MinecraftServer server){var roster=WORLDS.get(server);if(roster==null)throw new IllegalStateException("Backend unavailable: "+fault(server));return roster;}
    public static boolean owns(UUID id){return provider!=null&&provider.owns(id);}
    public static long generation(ServerPlayer p){if(p instanceof io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity old)return old.bodyGeneration();return roster(p.server).body(p.getUUID()).snapshot().identity().generation();}
    public static List<CompanionBackend> live(MinecraftServer server){var r=WORLDS.get(server);return r==null?List.of():r.live();}
    public static BackendProvider provider(){if(provider==null)throw new IllegalStateException("Backend unavailable");return provider;}
    public static int join(ServerPlayer owner){return join(owner,null);}
    public static int join(ServerPlayer owner,String requestedName){
        if(requestedName!=null&&!BackendRoster.NAMES.contains(requestedName))throw new IllegalArgumentException("Valid companions: Ember, Moss, Flint");
        var r=roster(owner.server);int added=0;var errors=new ArrayList<String>();
        for(String name:requestedName==null?BackendRoster.NAMES:List.of(requestedName)){
            var existing=r.members().stream().filter(m->m.name().equals(name)).findFirst().orElse(null);
            if(existing!=null&&r.live().stream().anyMatch(b->b.body().getUUID().equals(existing.id())))continue;
            try {Vec3 position=existing!=null&&existing.state().equals("COMMITTED")?null:spawnPosition(owner);var b=r.join(owner.server,provider,name,owner.getUUID(),owner.serverLevel(),position);BackendActions.get(owner.server).reconcileHistory(owner.server,b);io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation.request(b.body(),32);added++;}
            catch(RuntimeException failure){errors.add(name+": "+failure.getMessage());}
        }
        if(!errors.isEmpty())throw new IllegalStateException("joined="+added+"; "+String.join("; ",errors));return added;
    }
    private static Vec3 spawnPosition(ServerPlayer owner){
        var level=owner.serverLevel();var origin=owner.blockPosition();
        for(int radius=2;radius<=6;radius++)for(var p:BlockPos.betweenClosed(origin.offset(-radius,-2,-radius),origin.offset(radius,2,radius))){
            if(Math.max(Math.abs(p.getX()-origin.getX()),Math.abs(p.getZ()-origin.getZ()))!=radius||!level.hasChunkAt(p))continue;
            var feet=Vec3.atBottomCenterOf(p);var box=new AABB(feet.x-.3,feet.y,feet.z-.3,feet.x+.3,feet.y+1.8,feet.z+.3);
            if(level.getFluidState(p).isEmpty()&&level.getFluidState(p.above()).isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)&&level.noCollision(box)&&level.getEntities(owner,box).isEmpty())return feet;
        }
        throw new IllegalStateException("No safe initial spawn site near owner");
    }
    public static void control(MinecraftServer server,UUID id,String operation){
        var r=roster(server);var b=r.body(id);var data=CrewWorldData.get(server);var before=r.control(id);
        switch(operation){
            case "stop"->{b.cancel();b.pause();r.control(id,true,true);}
            case "pause"->{b.pause();r.control(id,true,before.stopped());}
            case "resume"->{r.control(id,false,false);data.setStandby(id,false);b.resume();}
            case "retask"->{b.cancel();r.control(id,false,false);data.setStandby(id,false);b.resume();}
            case "standby"->{b.cancel();data.setStandby(id,true);}
            case "auto_on"->{data.setAutonomy(id,true);data.setStandby(id,false);}
            case "auto_off"->{data.setAutonomy(id,false);if(BackendActions.get(server).find(id,b.snapshot().actionId()).map(e->e.priority().equals("PERSONAL")).orElse(false))b.cancel();}
            default->throw new IllegalArgumentException("Unsupported backend control");
        }
        data.touchControl(id);server.overworld().getDataStorage().save();
    }
    public static void tick(MinecraftServer server){var r=WORLDS.get(server);if(r==null)return;r.checkpoint(server);var actions=BackendActions.get(server);for(var b:r.live())try{actions.reconcile(server,b);}catch(RuntimeException failure){
            r.fault(b.body().getUUID(),"ACTION_LEDGER_RECONCILIATION_FAILED:"+failure.getClass().getSimpleName());
            org.slf4j.LoggerFactory.getLogger(BackendWorld.class).error("Backend ledger reconciliation failed for {}",b.body().getUUID(),failure);
        }}
    public static void close(MinecraftServer server){var r=WORLDS.remove(server);if(r!=null)r.close(server);FAULTS.remove(server);}
}
