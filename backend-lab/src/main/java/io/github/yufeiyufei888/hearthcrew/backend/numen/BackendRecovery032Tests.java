package io.github.yufeiyufei888.hearthcrew.backend.numen;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.backend.CompanionBackend;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import java.util.*;

@GameTestHolder("hearthcrew_backend_recovery032")
@PrefixGameTestTemplate(false)
public final class BackendRecovery032Tests {
 @GameTest(template="empty",timeoutTicks=1300,batch="032-flat")
 public static void validMoveStillReachesActualTarget(GameTestHelper h){BackendGateTests.knownFlatRouteMustComplete(h);}
 @GameTest(template="empty",timeoutTicks=1300,batch="032-height")
 public static void nativeColumnFallbackCannotReportHeightSuccess(GameTestHelper h){
  var b=BackendGateTests.spawn(h);BackendGateTests.move(h,b,"wrong-height",new BlockPos(8,5,5));
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("FAILED"),"unreachable elevated feet must not succeed: "+b.snapshot());
   h.assertTrue(b.snapshot().result().contains("TARGET_FEET_NOT_REACHED")||b.snapshot().result().contains("MOVE_BLOCKED")||b.snapshot().result().contains("NO_PROGRESS"),"specific failure retained");
   h.assertTrue(b.body().getY()<h.absolutePos(new BlockPos(8,5,5)).getY()-2,"actual lower position proves the mismatch");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=100,batch="032-injected-fallback")
 public static void injectedNativeSuccessAtWrongHeightIsRejected(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var target=b.body().blockPosition().above(4);
  var task=new ExactMoveTask(b.body(),new com.dwinovo.numen.core.task.move.MoveToTaskRecord("injected-column-success",h.getLevel().getGameTime()+50,b.body().getX(),(double)target.getY(),b.body().getZ(),null,false));
  try{var f=ExactMoveTask.class.getDeclaredField("delegate");f.setAccessible(true);f.set(task,new com.dwinovo.numen.task.Task(){
   public String name(){return "INJECTED_COLUMN_FALLBACK";}
   public com.dwinovo.numen.task.TaskState tick(com.dwinovo.numen.entity.NumenPlayer p){return com.dwinovo.numen.task.TaskState.SUCCESS;}
   public void stop(com.dwinovo.numen.entity.NumenPlayer p,StopReason r){}
  });}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
  h.assertTrue(task.tick(b.body())==com.dwinovo.numen.task.TaskState.FAILED,"INJECTED exact upstream success pattern is rejected");
  h.assertTrue(task.result(com.dwinovo.numen.task.TaskState.FAILED).toJson().contains("TARGET_FEET_NOT_REACHED"),"specific wrong height reason");b.close();h.succeed();
 }
 private static void tunnel(GameTestHelper h){
  // A two-high tunnel. Bedrock walls and roof make walking around impossible.
  for(int x=1;x<=11;x++)for(int y=1;y<=3;y++)for(int z=4;z<=6;z++)
   if(z!=5||y==3||x==1||x==11)h.setBlock(new BlockPos(x,y,z),Blocks.BEDROCK);
  h.setBlock(new BlockPos(6,1,5),Blocks.STONE);h.setBlock(new BlockPos(6,2,5),Blocks.STONE);
 }
 private static CompanionBackend.Request excavate(GameTestHelper h,NumenBackend b,int budget){
  var order=new BodyOrder(BodyOrder.Kind.EXCAVATE,h.absolutePos(new BlockPos(9,1,5)),null,2,null,List.of(),List.of(),List.of(),32,budget,BodyOrder.Preparation.disabled());
  return new CompanionBackend.Request(b.snapshot().identity(),"open-exit-"+budget,order,ActionPriority.PERSONAL);
 }
 @GameTest(template="empty",timeoutTicks=1800,batch="032-excavate")
 public static void approvedExitActuallyOpensAndArrives(GameTestHelper h){
  var b=BackendGateTests.spawn(h);tunnel(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
  var request=excavate(h,b,2);b.dispatch(request);
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("SUCCESS"),"known legal two-block exit must succeed: "+b.snapshot());
   h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(9,1,5))))<.9,"actual exit arrival");
   h.assertTrue(h.getBlockState(new BlockPos(6,1,5)).isAir()&&h.getBlockState(new BlockPos(6,2,5)).isAir(),"real permitted terrain removed");
   h.assertTrue(((Number)b.execution().get("accessSpent")).intValue()==2,"shared budget counts exactly two");
   h.assertTrue(b.dispatch(request).reused(),"terminal replay is read-only");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1300,batch="032-zero-budget")
 public static void zeroBudgetCannotOpenSameExit(GameTestHelper h){
  var b=BackendGateTests.spawn(h);tunnel(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));b.dispatch(excavate(h,b,0));
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(!b.snapshot().state().equals("SUCCESS"),"no permission to open this tunnel");
   h.assertTrue(h.getBlockState(new BlockPos(6,1,5)).is(Blocks.STONE)&&h.getBlockState(new BlockPos(6,2,5)).is(Blocks.STONE),"zero budget preserves both blocks");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1500,batch="032-pause")
 public static void pausedMovementResumesWithoutLateStateRegression(GameTestHelper h){BackendGateTests.pauseResumeAndLateCancellation(h);}
}
