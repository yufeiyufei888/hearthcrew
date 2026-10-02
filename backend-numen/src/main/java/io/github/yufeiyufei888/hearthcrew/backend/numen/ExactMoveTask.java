package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import net.minecraft.core.BlockPos;
import java.util.Map;

/** Search and completion share the same feet target, with no native column fallback. */
final class ExactMoveTask implements Task {
    private final MoveToTaskRecord request;
    private PlayerNav nav;
    private TaskResult outcome;
    private int stable, settleTicks;
    ExactMoveTask(NumenPlayer body, MoveToTaskRecord request){this.request=request;}
    public String name(){return request.mayAlterTerrain?"EXCAVATING_ACCESS":"APPROACHING_TARGET";}
    static boolean atTarget(NumenPlayer body,MoveToTaskRecord r){
        return r.x!=null&&r.y!=null&&r.z!=null
            &&body.blockPosition().getX()==(int)Math.floor(r.x)&&body.blockPosition().getZ()==(int)Math.floor(r.z)
            &&Math.hypot(body.getX()-r.x,body.getZ()-r.z)<=.75
            &&Math.abs(body.getY()-r.y)<=.55;
    }
    public TaskState tick(NumenPlayer body){
        if(request.x==null||request.y==null||request.z==null){outcome=TaskResult.fail("MOVE_REQUIRES_THREE_AXIS_FEET_TARGET",Map.of());return TaskState.FAILED;}
        if(atTarget(body,request)) {
            if(nav!=null)nav.pause();InputDriver.haltVehicle(body);
            stable=body.onGround()&&!body.isInWater()?stable+1:0;
            if(stable>=5){outcome=new TaskResult(true,"Target feet verified on stable ground",false,false,Map.of("actual",body.position().toString(),"stableTicks",stable));return TaskState.SUCCESS;}
            if(++settleTicks>=40){outcome=TaskResult.fail("TARGET_NOT_STABLE: arrival requires five grounded ticks",Map.of("actual",body.position().toString()));return TaskState.FAILED;}
            return TaskState.RUNNING;
        }
        stable=settleTicks=0;
        if(nav==null){
            var target=BlockPos.containing(request.x,request.y,request.z);
            nav=PlayerNav.toGoal(body,()->NavGoal.exact(target),1.0,()->atTarget(body,request),
                request.mayAlterTerrain?PlayerNav.ContextProvider.TERRAFORM:PlayerNav.ContextProvider.DEFAULT);
        }
        var state=nav.tick();
        if(state==PlayerNav.Status.FAILED){outcome=TaskResult.fail("MOVE_BLOCKED:"+nav.failReason(),Map.of("target",request.x+","+request.y+","+request.z,"actual",body.position().toString()));return TaskState.FAILED;}
        if(state==PlayerNav.Status.ARRIVED&&!atTarget(body,request)){
            outcome=TaskResult.fail("TARGET_FEET_NOT_REACHED: exact route ended outside requested feet position",Map.of("actual",body.position().toString()));return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }
    public void stop(NumenPlayer body,StopReason reason){if(nav!=null)nav.stop();nav=null;InputDriver.haltVehicle(body);stable=settleTicks=0;}
    public TaskResult result(TaskState state){return outcome!=null?outcome:new TaskResult(state==TaskState.SUCCESS,"Exact feet navigation stopped",state==TaskState.TIMEOUT,state==TaskState.CANCELLED,Map.of());}
}
