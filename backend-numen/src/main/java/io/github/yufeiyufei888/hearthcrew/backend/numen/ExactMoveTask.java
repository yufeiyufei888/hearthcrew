package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import java.util.Map;

/** Native goto can fall back to a column. HearthCrew promises a three-axis feet target. */
final class ExactMoveTask implements Task {
    private final Task delegate;
    private final MoveToTaskRecord request;
    private TaskResult outcome;
    private boolean settling;
    private int stable, settleTicks;
    ExactMoveTask(NumenPlayer body, MoveToTaskRecord request){this.request=request;delegate=TaskFactory.create(body,request);}
    public String name(){return request.mayAlterTerrain?"EXCAVATING_ACCESS":"APPROACHING_TARGET";}
    public void start(NumenPlayer body){delegate.start(body);}
    static boolean atTarget(NumenPlayer body,MoveToTaskRecord r){
        return r.x!=null&&r.y!=null&&r.z!=null
            &&body.blockPosition().getX()==(int)Math.floor(r.x)&&body.blockPosition().getZ()==(int)Math.floor(r.z)
            &&Math.hypot(body.getX()-r.x,body.getZ()-r.z)<=.75
            &&Math.abs(body.getY()-r.y)<=.55;
    }
    public TaskState tick(NumenPlayer body){
        if(!settling){
            TaskState state=delegate.tick(body);
            if(state!=TaskState.SUCCESS){
                if(state.isTerminal())outcome=TaskResult.fail("MOVE_BLOCKED: inspect verified feet target; if safe excavation is required, use EXCAVATE with count/accessBudget. Native may_alter_terrain is not an act parameter",Map.of("native",String.valueOf(delegate.result(state)),"actual",body.position().toString()));
                return state;
            }
            settling=true;delegate.stop(body,StopReason.REPLACED);
        }
        InputDriver.haltVehicle(body);
        if(!atTarget(body,request)){
            outcome=TaskResult.fail("TARGET_FEET_NOT_REACHED: native column fallback is not a completed three-dimensional move",Map.of("target",request.x+","+request.y+","+request.z,"actual",body.position().toString()));
            return TaskState.FAILED;
        }
        stable=body.onGround()&&!body.isInWater()?stable+1:0;
        if(stable>=5){outcome=new TaskResult(true,"Target feet verified on stable ground",false,false,Map.of("actual",body.position().toString(),"stableTicks",stable));return TaskState.SUCCESS;}
        if(++settleTicks>=40){outcome=TaskResult.fail("TARGET_NOT_STABLE: arrival requires five grounded ticks",Map.of("actual",body.position().toString()));return TaskState.FAILED;}
        return TaskState.RUNNING;
    }
    public void stop(NumenPlayer body,StopReason reason){delegate.stop(body,reason);InputDriver.haltVehicle(body);if(reason==StopReason.PREEMPTED)stable=0;}
    public TaskResult result(TaskState state){return outcome!=null?outcome:delegate.result(state);}
}
