package io.github.yufeiyufei888.hearthcrew.backend.numen;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
@GameTestHolder("hearthcrew_backend_recoveryedge")
@PrefixGameTestTemplate(false)
public final class BackendRecoveryEdgeTests {
 @GameTest(template="empty",timeoutTicks=1200,batch="edge-water-shore")
 public static void actuallyLeaveWaterWithoutDrainingOrTeleporting(GameTestHelper h){
  var b=BackendGateTests.spawn(h);
  for(int x=0;x<=9;x++)for(int z=0;z<=10;z++)for(int y=1;y<=3;y++)h.setBlock(new BlockPos(x,y,z),x==0||x==9||z==0||z==10?Blocks.STONE:Blocks.WATER);
  b.body().setAirSupply(90);
  BackendGateTests.move(h,b,"shore-before-old-work",new BlockPos(19,1,5));
  boolean[] reachedShore={false};
  h.onEachTick(()->{
   if(!b.body().isInWater()&&b.body().onGround()&&((Number)b.diagnostics().get("reactionTicks")).longValue()>100)reachedShore[0]=true;
   if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&reachedShore[0],"must actually surface, get ashore and resume original move: "+b.snapshot()+b.diagnostics());
   b.close();h.succeed();
  });
 }
 @GameTest(template="empty",timeoutTicks=1800,batch="edge-alternative-coal")
 public static void sealedNearOreDoesNotBlockAvailableCoal(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  for(int x=5;x<=7;x++)for(int z=3;z<=5;z++)for(int y=1;y<=3;y++)h.setBlock(new BlockPos(x,y,z),Blocks.BEDROCK);
  h.setBlock(new BlockPos(6,2,4),Blocks.COAL_ORE);
  for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,9),Blocks.COAL_ORE);
  b.submit(new CollectRequest("candidate-rotation",h.getLevel().getGameTime()+1700,Items.COAL,16,Set.of(Blocks.COAL_ORE),true),16);
  h.onEachTick(()->{if(!b.terminal())return;h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.COAL)==16,"sixteen real coal despite sealed first clue: "+b.snapshot());b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=180,batch="edge-delayed-wal")
 public static void injectedSlowDurabilityCannotModifyInventoryBeforeAck(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,2));
  b.submit(new CraftRequest("injected-wal-delay",h.getLevel().getGameTime()+150,Items.STICK,4));
  var gate=new java.util.concurrent.CompletableFuture<Void>();
  try{
   var field=NumenBackend.class.getDeclaredField("pendingStart");field.setAccessible(true);
   var actual=(java.util.concurrent.CompletableFuture<?>)field.get(b);
   field.set(b,actual.thenCompose(ignored->gate));
  }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
  h.runAfterDelay(20,()->{h.assertTrue(b.body().getInventory().countItem(Items.OAK_PLANKS)==2&&b.body().getInventory().countItem(Items.STICK)==0,"INJECTED write delay: game advanced twenty ticks without unauthorized craft");gate.complete(null);});
  h.onEachTick(()->{if(!b.terminal())return;h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.STICK)==4,"durable ack releases exactly one native craft");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=400,batch="edge-pickup")
 public static void pickupMergeAndReload(GameTestHelper h){BackendJournalTests.realMergedPickupSurvivesLedgerReload(h);}
 @GameTest(template="empty",timeoutTicks=250,batch="edge-rejoin")
 public static void rejoinNeverReplaysCraft(GameTestHelper h){BackendJournalTests.repeatedCraftAfterBodyRejoinDoesNotSpendTwice(h);}
}
