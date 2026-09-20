package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.backend.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Real-time edges only. A retained event, not a socket write, advances the cursor. */
final class BackendEventStream {
    interface Sink { boolean accept(String id,String type,long sequence,JsonObject body); }
    private static final Gson JSON=new Gson();
    private final MinecraftServer server;
    private final String namespace;
    private long cursor;
    private final Map<UUID,Long> incarnations=new HashMap<>();
    private final Map<UUID,Facts> facts=new HashMap<>();
    private record Facts(long generation,String inventory,String danger,String processing,long control,long scan,boolean active,long idleSince,boolean idleAnnounced,long sampled) {}
    BackendEventStream(MinecraftServer server,String namespace){
        this.server=server;this.namespace=namespace;
        // Saved results remain queryable. They are not new work on reconnect.
        cursor=BackendActions.get(server).revision();
    }
    boolean tick(Sink sink){
        long tick=server.overworld().getGameTime();var ledger=BackendActions.get(server);
        for(var b:BackendWorld.live(server)){
            var p=b.body();var state=b.snapshot();long gen=state.identity().generation();var id=p.getUUID();
            if(!Objects.equals(incarnations.get(id),gen)){
                var body=JSON.toJsonTree(Map.of("botId",id.toString(),"entityId",id.toString(),"bodyGeneration",gen,"name",p.getGameProfile().getName(),"dimension",p.level().dimension().location().toString())).getAsJsonObject();
                if(!sink.accept(namespace+":body:"+id+":"+gen,"body.available",0,body))return false;
                incarnations.put(id,gen);
            }
        }
        for(var entry:ledger.changesAfter(cursor,64)){
            var live=BackendWorld.live(server).stream().filter(b->b.body().getUUID().equals(entry.companion())).findFirst();
            if(live.isPresent()&&entry.generation()==live.get().snapshot().identity().generation()){
                var body=BackendActions.wire(entry);body.addProperty("botId",entry.companion().toString());body.addProperty("historical",false);
                // The proof belongs to this result, never the next task's execution report.
                try{var proof=JsonParser.parseString(entry.evidence());if(proof.isJsonObject())body.add("execution",proof);}catch(RuntimeException ignored){}
                boolean terminal=Set.of("COMPLETED","FAILED","CANCELLED","PARTIAL","EXPIRED").contains(entry.state());
                String identity=entry.world()+":"+entry.companion()+":"+entry.generation()+":"+entry.actionId()+":"+entry.sequence();
                if(!sink.accept(identity,terminal?"action.terminal":"action.progress",entry.sequence(),body))return false;
            }
            cursor=entry.sequence();
        }
        for(var b:BackendWorld.live(server))if(!wake(b,tick,sink))return false;
        return true;
    }
    private boolean wake(CompanionBackend b,long tick,Sink sink){
        var p=b.body();var id=p.getUUID();var state=b.snapshot();var previous=facts.get(id);
        if(previous!=null&&tick-previous.sampled()<20)return true;
        var counts=new TreeMap<String,Integer>();for(var stack:p.getInventory().items)if(!stack.isEmpty())counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        String inventory=counts.toString();String danger=(int)p.getHealth()+":"+p.isOnFire()+":"+p.isUnderWater()+":"+b.diagnostics().get("reaction");
        String processing=JSON.toJson(b.execution().getOrDefault("processingOrders",List.of()));
        long revision=CrewWorldData.get(server).controlRevision(id),scan=NearbyObservation.revision(p),gen=state.identity().generation();
        boolean active=state.state().equals("RUNNING");long idleSince=active||previous==null||previous.active()||previous.generation()!=gen?tick:previous.idleSince();
        boolean announced=!active&&previous!=null&&!previous.active()&&previous.generation()==gen&&previous.idleAnnounced();var reasons=new ArrayList<String>();
        if(previous!=null&&previous.generation()==gen){
            if(!previous.inventory().equals(inventory))reasons.add("inventory_changed");
            if(!previous.danger().equals(danger))reasons.add("danger_changed");
            if(!previous.processing().equals(processing))reasons.add("processing_changed");
            if(previous.control()!=revision)reasons.add("mode_changed");
            if(scan>previous.scan())reasons.add("scan_ready");
        }else if(scan>0)reasons.add("scan_ready");
        var control=BackendWorld.roster(server).control(id);
        if(!active&&!announced&&!control.paused()&&!control.stopped()&&!CrewWorldData.get(server).standby(id)&&tick-idleSince>=100){reasons.add("body_idle");announced=true;}
        if(!reasons.isEmpty()){
            var body=JSON.toJsonTree(Map.of("botId",id.toString(),"bodyGeneration",gen,"gameTick",tick,"reasons",reasons,"localSafety",b.diagnostics())).getAsJsonObject();
            if(!sink.accept(namespace+":wake:"+id+":"+gen+":"+tick,"body.wakeup",tick,body))return false;
        }
        facts.put(id,new Facts(gen,inventory,danger,processing,revision,scan,active,idleSince,announced,tick));return true;
    }
}
