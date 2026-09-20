package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.chain.BreathChain;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.entity.*;
import com.dwinovo.numen.task.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A single safety episode survives a momentary breath above the water surface. */
final class WaterRecovery implements Task {
    private final BreathChain breath=new BreathChain();
    private boolean latched;
    private BlockPos shore,origin;
    private PlayerNav nav;
    private final Set<BlockPos> rejected=new HashSet<>();
    private int stable,cursor,idle;
    private double best=Double.MAX_VALUE;
    private String phase="SURFACING";
    public boolean canRun(NumenPlayer body){return latched||breath.canRun(body);}
    public String name(){return phase;}
    public TaskState tick(NumenPlayer body){
        latched=true;
        if(!body.isInWater()&&body.onGround()){
            phase="VERIFYING_SAFE_SHORE";InputDriver.halt(body);
            if(++stable>=100){latched=false;shore=null;origin=null;cursor=0;rejected.clear();stop(body,StopReason.REPLACED);return TaskState.SUCCESS;}
            return TaskState.RUNNING;
        }
        stable=0;
        if(body.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)||body.getAirSupply()<200){phase="SURFACING";breath.tick(body);return TaskState.RUNNING;}
        if(origin==null)origin=body.blockPosition();
        if(shore==null){
            phase="FINDING_SHORE";
            // Incremental loaded-only scan. No world access on path workers.
            for(int n=0;n<64&&cursor<25*25*9;n++,cursor++){
                int x=cursor%25-12,z=cursor/25%25-12,y=cursor/(25*25)-4;
                var p=origin.offset(x,y,z);
                if(rejected.contains(p)||!body.level().hasChunkAt(p)||!body.level().hasChunkAt(p.above()))continue;
                var support=body.level().getBlockState(p.below()).getCollisionShape(body.level(),p.below());
                if(support.isEmpty()||!body.level().getFluidState(p).isEmpty()||!body.level().getFluidState(p.below()).isEmpty())continue;
                double foot=p.getY()-1+support.max(net.minecraft.core.Direction.Axis.Y);
                var feet=new Vec3(p.getX()+.5,foot,p.getZ()+.5);
                if(!body.level().noCollision(body,body.getDimensions(body.getPose()).makeBoundingBox(feet)))continue;
                if(shore==null||feet.distanceToSqr(body.position())<Vec3.atBottomCenterOf(shore).distanceToSqr(body.position()))shore=p;
            }
            if(shore==null){InputDriver.halt(body);InputDriver.jump(body);if(cursor>=25*25*9)phase="WAITING_NO_SAFE_SHORE";return TaskState.RUNNING;}
            best=body.position().distanceToSqr(Vec3.atBottomCenterOf(shore));idle=0;
        }
        phase="APPROACHING_SHORE";
        if(nav==null)nav=PlayerNav.toGoal(body,()->NavGoal.exact(shore),1.0,()->!body.isInWater()&&body.onGround(),PlayerNav.ContextProvider.DEFAULT);
        var status=nav.tick();
        if(body.isInWater())InputDriver.jump(body);
        double distance=body.position().distanceToSqr(Vec3.atBottomCenterOf(shore));
        if(distance<best-.5){best=distance;idle=0;}else idle++;
        if(status==PlayerNav.Status.FAILED||idle>=100&&!nav.planningInFlight()){
            rejected.add(shore);stop(body,StopReason.REPLACED);shore=null;cursor=0;
        }
        return TaskState.RUNNING;
    }
    public void stop(NumenPlayer body,StopReason reason){if(nav!=null)nav.stop();nav=null;InputDriver.halt(body);}
    public TaskResult result(TaskState state){return new TaskResult(state==TaskState.SUCCESS,phase,false,false,Map.of("rejectedShoreCount",rejected.size(),"stableTicks",stable));}
}
