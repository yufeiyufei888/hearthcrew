package io.github.yufeiyufei888.hearthcrew.backend.numen;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import net.minecraft.core.BlockPos;
import java.util.*;
final class FacilityApproach implements Task {
    public String name(){return "APPROACHING_FACILITY";}
 private final BlockPos target;private final Set<BlockPos> failed=new HashSet<>();
 private PlayerNav nav;private BlockPos selected;private String reason="APPROACHING_FACILITY";private int attempts,settling;
 FacilityApproach(BlockPos target){this.target=target;}
 public TaskState tick(NumenPlayer body){
  if(FacilityAccess.usableNow(body,target)){stop(body,StopReason.REPLACED);return TaskState.SUCCESS;}
  var denial=FacilityAccess.denial(body.serverLevel(),target);if(!denial.isEmpty()){reason=denial;return TaskState.FAILED;}
  if(nav==null){
   if(attempts>=3){reason="FACILITY_STANCES_BLOCKED";return TaskState.FAILED;}
   var stances=InteractionStances.around(body,target).stream().filter(s->!failed.contains(s.cell())).toList();
   if(stances.isEmpty()){reason="FACILITY_NO_VISIBLE_SUPPORTED_STANCE";return TaskState.FAILED;}
   selected=stances.getFirst().cell();attempts++;settling=0;
   nav=PlayerNav.toGoal(body,()->NavGoal.exact(selected),1.0,()->FacilityAccess.usableNow(body,target),PlayerNav.ContextProvider.DEFAULT);
  }
  var state=nav.tick();
  if(state==PlayerNav.Status.ARRIVED&&!FacilityAccess.usableNow(body,target)&&settling++<20)return TaskState.RUNNING;
  if(state==PlayerNav.Status.FAILED||state==PlayerNav.Status.ARRIVED){reason=state==PlayerNav.Status.FAILED?nav.failReason():"FACILITY_ARRIVAL_NOT_USABLE";failed.add(selected);stop(body,StopReason.REPLACED);}
  return TaskState.RUNNING;
 }
 public void stop(NumenPlayer body,StopReason why){if(nav!=null)nav.stop();nav=null;}
 public TaskResult result(TaskState state){return new TaskResult(state==TaskState.SUCCESS,reason,false,state==TaskState.CANCELLED,Map.of("station",target.toShortString(),"attempts",attempts));}
}
