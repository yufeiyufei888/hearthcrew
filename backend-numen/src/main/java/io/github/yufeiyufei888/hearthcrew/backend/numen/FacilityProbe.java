package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.astar.*;
import com.dwinovo.numen.core.pathing.bridge.*;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.TerrainPermit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;

/** One multi-station read-only search per preparation; never mutates or loads unknown chunks. */
final class FacilityProbe {
    private final NumenPlayer body;
    private final BlockPos scopeOrigin;
    private final BlockPos requested;
    private final int radius;
    private SearchHandle search;
    private List<BlockPos> stations=List.of();
    private List<InteractionStances.Stance> stances=List.of();
    BlockPos table,placement;
    boolean done,unknown,atTable;
    FacilityProbe(NumenPlayer body,BlockPos scopeOrigin){this(body,scopeOrigin,null);}
    FacilityProbe(NumenPlayer body,BlockPos scopeOrigin,BlockPos requested){this(body,scopeOrigin,requested,32);}
    FacilityProbe(NumenPlayer body,BlockPos scopeOrigin,BlockPos requested,int radius){this.radius=radius;this.body=body;this.scopeOrigin=scopeOrigin.immutable();this.requested=requested==null?null:requested.immutable();}
    private boolean inside(BlockPos p){return Math.max(Math.max(Math.abs(p.getX()-scopeOrigin.getX()),Math.abs(p.getY()-scopeOrigin.getY())),Math.abs(p.getZ()-scopeOrigin.getZ()))<=radius;}
    static boolean usableNow(NumenPlayer body,BlockPos p) {
        return body.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)&&FacilityAccess.usableNow(body,p);
    }
    void tick(){long began=System.nanoTime();try{tickMeasured();}finally{SlowOperations.record("facility_query",began);}}
    private void tickMeasured() {
        if(done||!body.onGround())return;
        if(search==null) {
            var origin=body.blockPosition();
            stations=BackendJournal.get(body.server).publicPositions(body.level().dimension()).stream()
                .filter(p->requested==null||requested.equals(p))
                .filter(p->inside(p)&&body.level().hasChunkAt(p)&&body.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)&&FacilityAccess.denial(body.serverLevel(),p).isEmpty())
                .sorted(Comparator.comparingDouble(p->p.distSqr(origin))).limit(32).toList();
            placement=requested==null?placement(body):null;
            if(placement!=null&&!inside(placement))placement=null;
            table=stations.stream().filter(p->usableNow(body,p)).findFirst().orElse(null);
            if(table!=null){atTable=true;done=true;return;}
            if(stations.isEmpty()){done=true;return;}
            stances=stations.stream().flatMap(p->InteractionStances.around(body,p).stream()).toList();
            if(stances.isEmpty()){unknown=true;done=true;return;}
            var goal=new GoalCompiler.Compiled(com.dwinovo.numen.core.pathing.calc.NavGoal.composite(stances.stream().map(s->com.dwinovo.numen.core.pathing.calc.NavGoal.exact(s.cell())).toList()),new it.unimi.dsi.fastutil.longs.LongOpenHashSet(stations.stream().mapToLong(BlockPos::asLong).toArray()));
            var context=ContextFactory.forSearch(body,goal.sacred(),it.unimi.dsi.fastutil.longs.LongSets.emptySet(),TerrainPermit.PRESERVE);
            var start=PathExecutor.playerFeet(body);
            search=PoolSearchDispatcher.INSTANCE.submit(start,start,goal.engineGoal(),context,Favoring.empty(),100,300);
            return;
        }
        var result=search.poll();if(result==null)return;
        done=true;
        if(result.getType()==PathCalcResult.Type.SUCCESS_TO_GOAL) {
            var end=result.getPath().orElseThrow().getDest();
            table=stances.stream().filter(s->s.cell().equals(end)).map(InteractionStances.Stance::target).findFirst().orElse(null);
            if(table==null)unknown=true;
        } else unknown=true; // budget or exception is not evidence that a station is unreachable
    }
    void cancel(){if(search!=null)search.cancel();}
    static BlockPos placement(NumenPlayer body) {
        var journal=BackendJournal.get(body.server);var origin=body.blockPosition();
        for(int radius=2;radius<=3;radius++)for(var direction:Direction.Plane.HORIZONTAL) {
            var p=origin.relative(direction,radius);
            if(!body.level().hasChunkAt(p)||!body.level().getBlockState(p).isAir()||!body.level().getFluidState(p).isEmpty()
                ||journal.structureAt(body.level().dimension(),p)||BackendProtection.isProtected(body.serverLevel(),p)
                ||!body.level().getBlockState(p.below()).isFaceSturdy(body.level(),p.below(),Direction.UP)
                ||!body.level().getEntities(body,new AABB(p)).isEmpty())continue;
            var hit=body.level().clip(new ClipContext(body.getEyePosition(),Vec3.atCenterOf(p.below()).add(0,.499,0),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,body));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(p.below())&&body.getEyePosition().distanceToSqr(hit.getLocation())<=4.5*4.5)return p.immutable();
        }
        return null;
    }
}
