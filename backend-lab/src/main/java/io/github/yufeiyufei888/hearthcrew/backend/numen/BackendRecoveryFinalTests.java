package io.github.yufeiyufei888.hearthcrew.backend.numen;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.*;
@GameTestHolder("hearthcrew_backend_recoveryfinal")
@PrefixGameTestTemplate(false)
public final class BackendRecoveryFinalTests {
 @GameTest(template="empty",timeoutTicks=850,batch="final-watchdog")
 public static void injectedNativeStallExpiresAtThirtyEffectiveSecondsAndReleasesBody(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,2));
  BackendGateTests.move(h,b,"injected-native-stall",new BlockPos(19,1,5));
  try{var f=NumenBackend.class.getDeclaredField("task");f.setAccessible(true);f.set(b,new Task(){
   public String name(){return "INJECTED_NATIVE_STALL";}
   public TaskState tick(NumenPlayer p){return TaskState.RUNNING;}
   public void stop(NumenPlayer p,StopReason r){}
   public TaskResult result(TaskState s){return TaskResult.fail("INJECTED: upstream never terminates",Map.of());}
  });}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
  h.runAfterDelay(10,b::pause);h.runAfterDelay(60,b::resume);
  boolean[] following={false};
  h.onEachTick(()->{
   if(!b.terminal())return;
   if(!following[0]){
    h.assertTrue(b.snapshot().activeTicks()==600&&b.snapshot().pausedTicks()==50&&b.snapshot().result().contains("NO_PROGRESS_30_SECONDS"),"INJECTED stall: exactly 600 work ticks, pause excluded, concrete outcome: "+b.snapshot());
    following[0]=true;b.submit(new CraftRequest("queued-work-after-stall",h.getLevel().getGameTime()+100,Items.STICK,4));return;
   }
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.STICK)==4,"released body accepts and completes next work");b.close();h.succeed();
  });
 }
 @GameTest(template="empty",timeoutTicks=200,batch="final-wal-replay")
 public static void durableLogRecoversNewResultsOverOlderWorldSnapshot(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var journal=BackendJournal.get(b.body().server);
  var earlier=journal.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());
  b.body().getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,2));
  b.submit(new CraftRequest("durable-after-old-nbt",h.getLevel().getGameTime()+100,Items.STICK,4));
  h.onEachTick(()->{
   if(!b.terminal()||!journal.barrier().isDone())return;journal.barrier().join();
   var recovered=BackendJournal.load(earlier);
   try{var attach=BackendJournal.class.getDeclaredMethod("attach",net.minecraft.server.MinecraftServer.class);attach.setAccessible(true);attach.invoke(recovered,b.body().server);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
   h.assertTrue(recovered.find(b.body().getUUID(),"durable-after-old-nbt").state().equals("SUCCESS"),"WAL recovers actual terminal over prior saved data");
   h.assertTrue(b.body().getInventory().countItem(Items.STICK)==4,"replay updates evidence, never runs another craft");b.close();h.succeed();
  });
 }
 @GameTest(template="empty",timeoutTicks=1800,batch="final-search")
 public static void boundedCandidateSearchStillProducesSixteen(GameTestHelper h){BackendRecoveryEdgeTests.sealedNearOreDoesNotBlockAvailableCoal(h);}
 @GameTest(template="empty",timeoutTicks=1500,batch="final-station")
 public static void workstationAfterSearchInstrumentation(GameTestHelper h){BackendPreparationTests.flintAutomaticallyApproachesPublicTableAndCrafts(h);}
}
