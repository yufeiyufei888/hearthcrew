package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.core.BlockPos;
import java.util.*;

public final class BackendRuntime {
    static int blockedBreaks;
    static int blockedPlaces;
    private record Removal(net.minecraft.server.level.ServerLevel level,BlockPos position) {}
    private static final Set<Removal> removals=new HashSet<>();
    private record RemovedDrop(net.minecraft.server.level.ServerLevel level,UUID id) {}
    private static final Set<RemovedDrop> removedDrops=new HashSet<>();
    private static boolean initialized;
    private BackendRuntime() {}
    public static synchronized void initialize() {
        if(initialized)return;initialized=true;
        CraftRequest.register();
        ContainerRequest.register();
        ProcessRequest.register();
        NeoForge.EVENT_BUS.addListener(BackendRuntime::beforeBreak);
        NeoForge.EVENT_BUS.addListener(BackendRuntime::beforePlace);
        NeoForge.EVENT_BUS.addListener(NumenBackend::drops);
        NeoForge.EVENT_BUS.addListener(BackendRuntime::picked);
        NeoForge.EVENT_BUS.addListener(FurnaceWork::tick);
        NeoForge.EVENT_BUS.addListener(FurnaceWork::stopped);
        NeoForge.EVENT_BUS.addListener(NumenBackend::shutdown);
        NeoForge.EVENT_BUS.addListener(BackendRuntime::stopped);
        NeoForge.EVENT_BUS.addListener(FacilityAccess::use);
        NeoForge.EVENT_BUS.addListener(BackendRuntime::confirmRemovals);
        NeoForge.EVENT_BUS.addListener(BackendRuntime::dropLeft);
    }
    private static void beforeBreak(BlockEvent.BreakEvent event) {
        if(!BackendProtection.configured(event.getPlayer().getServer()))return;
        if(!NumenBackend.owns(event.getPlayer().getUUID())) {
            if(event.getPlayer().level() instanceof net.minecraft.server.level.ServerLevel level)removals.add(new Removal(level,event.getPos().immutable()));
            return;
        }
        if (NumenBackend.owns(event.getPlayer().getUUID()) && !NumenBackend.beforeBreak(event.getPlayer(),event.getPos())) {
            blockedBreaks++;
            event.setCanceled(true);
        }
    }
    private static void confirmRemovals(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // Native pickup and merge can discard their source before the post-transaction
        // evidence hook. Close only the remaining claims after those hooks have run.
        for(var it=removedDrops.iterator();it.hasNext();) {
            var entry=it.next();if(entry.level().getServer()!=event.getServer())continue;
            if(entry.level().getEntity(entry.id())==null)DropProof.confirmedRemoved(event.getServer(),entry.id());
            it.remove();
        }
        for(var it=removals.iterator();it.hasNext();) {
            var entry=it.next();if(entry.level().getServer()!=event.getServer())continue;
            // Canceled breaks and replacement blocks keep protection. Never infer removal from intent.
            if(entry.level().hasChunkAt(entry.position())&&entry.level().getBlockState(entry.position()).isAir())
                BackendJournal.get(event.getServer()).removedStructure(entry.level().dimension(),entry.position());
            it.remove();
        }
    }
    private static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){
        NumenBackend.stopped(event);removals.removeIf(r->r.level().getServer()==event.getServer());removedDrops.removeIf(r->r.level().getServer()==event.getServer());
    }
    private static void dropLeft(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if(!(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item)
                ||!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;
        if(!BackendProtection.configured(level.getServer()))return;
        var reason=item.getRemovalReason();
        if(reason==net.minecraft.world.entity.Entity.RemovalReason.DISCARDED||reason==net.minecraft.world.entity.Entity.RemovalReason.KILLED)
            removedDrops.add(new RemovedDrop(level,item.getUUID()));
        // Chunk unload and world exit remain unknown, never recorded as loss or acquisition.
    }
    private static void beforePlace(BlockEvent.EntityPlaceEvent event) {
        if(event.getEntity()==null)return;
        if(!BackendProtection.configured(event.getEntity().getServer()))return;
        if(!NumenBackend.owns(event.getEntity().getUUID())) {
            if(event.getEntity().getServer()!=null) {
                var journal=BackendJournal.get(event.getEntity().getServer());var dimension=event.getEntity().level().dimension();
                var positions=event instanceof BlockEvent.EntityMultiPlaceEvent multi?multi.getReplacedBlockSnapshots().stream().map(s->s.getPos()).toList():List.of(event.getPos());
                for(var pos:positions){journal.unpublish(dimension,pos);journal.recordStructure(dimension,pos);}
            }
            return;
        }
        boolean permitted=event instanceof BlockEvent.EntityMultiPlaceEvent multi
            ? multi.getReplacedBlockSnapshots().stream().allMatch(s->NumenBackend.beforePlace(event.getEntity(),s.getPos()))
            : NumenBackend.beforePlace(event.getEntity(),event.getPos());
        if (!permitted) {
            blockedPlaces++;
            event.setCanceled(true);
        }
    }
    private static void picked(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post e) {
        var original=e.getOriginalStack();int n=original.getCount()-e.getCurrentStack().getCount();
        if(n>0)DropProof.picked(e.getPlayer().getServer(),e.getItemEntity().getUUID(),e.getPlayer().getUUID(),original.getItem(),n,original.getCount());
    }
}
