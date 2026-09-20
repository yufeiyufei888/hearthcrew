package io.github.yufeiyufei888.hearthcrew.backend;

import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.ReceiptDecision;
import java.util.*;

/** Development comparison adapter. Does not add a second executor or change legacy ticking. */
public final class LegacyBackend implements CompanionBackend {
    private final CompanionEntity body;
    private final String world;
    private boolean closed;
    private String latest="";
    public LegacyBackend(CompanionEntity body,String world){this.body=Objects.requireNonNull(body);this.world=Objects.requireNonNull(world);requireThread();}
    private void requireThread(){if(!body.server.isSameThread())throw new IllegalStateException("Server thread required");}
    private Identity identity(){return new Identity(world,body.getUUID(),body.bodyGeneration());}
    public String backendId(){return "legacy";}
    public Map<String,Object> diagnostics(){requireThread();return body.executor().localSafetyStatus();}
    public Map<String,Object> execution(){requireThread();return body.executor().arbiter().activeActionId().map(id->body.executor().executionReport(id.value())).orElse(Map.of());}
    public Set<BodyOrder.Kind> capabilities(){return Set.copyOf(EnumSet.allOf(BodyOrder.Kind.class));}
    public CompanionEntity body(){return body;}
    public Acceptance dispatch(Request request){
        requireThread();if(closed)throw new IllegalStateException("Backend closed");
        if(!identity().equals(request.identity()))throw new IllegalArgumentException("STALE_EXECUTION_CONTEXT");
        var receipt=body.executor().submit(request.actionId(),request.order(),request.priority());
        if(receipt.decision().name().startsWith("REJECTED_"))throw new IllegalStateException(receipt.decision()+":"+receipt.message());
        latest=request.actionId();
        return new Acceptance(receipt.decision()==ReceiptDecision.IDEMPOTENT_REPLAY,request.actionId(),receipt.epoch().bodyGeneration(),receipt.state().name(),receipt.message());
    }
    public Snapshot snapshot(){
        requireThread();var arbiter=body.executor().arbiter();var active=arbiter.activeSnapshot();
        var row=active.isPresent()?active:latest.isEmpty()?Optional.<io.github.yufeiyufei888.hearthcrew.kernel.ActionSnapshot<BodyOrder,BodyExecutor.Checkpoint>>empty():arbiter.snapshot(io.github.yufeiyufei888.hearthcrew.kernel.ActionId.of(latest));
        // Legacy clocks are world/action clocks, not measured working time. Unknown is -1.
        return row.map(s->new Snapshot(identity(),s.id().value(),s.epoch().bodyGeneration(),s.state().name(),-1,-1,s.message()))
            .orElseGet(()->new Snapshot(identity(),"",body.bodyGeneration(),"IDLE",-1,-1,""));
    }
    public Optional<Snapshot> lookup(String id){requireThread();return body.executor().arbiter().snapshot(io.github.yufeiyufei888.hearthcrew.kernel.ActionId.of(id)).map(s->new Snapshot(identity(),id,s.epoch().bodyGeneration(),s.state().name(),-1,-1,s.message()));}
    public void pause(){requireThread();body.executor().pause();body.haltInputs();}
    public void resume(){requireThread();if(closed)throw new IllegalStateException("Backend closed");body.executor().resume();}
    public void cancel(){requireThread();boolean stopped=body.executor().stopped(),paused=body.executor().paused();body.executor().stop("backend action cancelled");if(!stopped){body.executor().resume();if(paused)body.executor().pause();}body.haltInputs();}
    public void close(){requireThread();if(closed)return;cancel();closed=true;body.setRespawnEnabled(false);body.server.getPlayerList().saveAll();body.server.getPlayerList().remove(body);body.connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("backend closed"));}
}
