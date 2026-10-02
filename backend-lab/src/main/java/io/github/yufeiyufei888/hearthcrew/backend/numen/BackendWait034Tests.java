package io.github.yufeiyufei888.hearthcrew.backend.numen;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
@GameTestHolder("hearthcrew_backend_wait034")
@PrefixGameTestTemplate(false)
public final class BackendWait034Tests {
 @GameTest(template="empty",timeoutTicks=2500,batch="034-finite-sources")
 public static void sixteenCoalAcrossTwoFiniteSourceTypes(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  for(int x=6;x<14;x++)h.setBlock(new BlockPos(x,1,5),Blocks.COAL_ORE);
  for(int x=6;x<14;x++)h.setBlock(new BlockPos(x,1,9),Blocks.DEEPSLATE_COAL_ORE);
  b.submit(new CollectRequest("finite-source-batches",h.getLevel().getGameTime()+2300,Items.COAL,16,Set.of(Blocks.COAL_ORE,Blocks.DEEPSLATE_COAL_ORE),true),16);
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.COAL)==16,"both finite sources must produce sixteen real coal: "+b.snapshot());
   h.assertTrue(b.snapshot().result().contains("\"ownNew\":16"),"native pickup proof, not final-stock inference");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=2000,batch="034-partial-tree")
 public static void twoLogsAreCollectedWithoutClaimingFour(GameTestHelper h){
  var b=BackendGateTests.spawn(h);h.setBlock(new BlockPos(6,0,5),Blocks.DIRT);
  h.setBlock(new BlockPos(6,1,5),Blocks.OAK_LOG);h.setBlock(new BlockPos(6,2,5),Blocks.OAK_LOG);
  for(int x=5;x<=7;x++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,3,z),Blocks.OAK_LEAVES);
  b.submit(new CollectRequest("partial-natural-tree",h.getLevel().getGameTime()+1800,Items.OAK_LOG,4,Set.of(Blocks.OAK_LOG),true),0);
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("FAILED")&&b.body().getInventory().countItem(Items.OAK_LOG)==2,"collect available two but do not invent remaining two: "+b.snapshot());
   h.assertTrue(b.snapshot().result().contains("\"ownNew\":2")&&b.snapshot().result().contains("\"requestedNew\":4"),"original quantity and partial pickup retained");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1800,batch="034-blocked-candidate")
 public static void closedCandidateCannotStopOtherCoal(GameTestHelper h){BackendRecoveryEdgeTests.sealedNearOreDoesNotBlockAvailableCoal(h);}
 @GameTest(template="empty",timeoutTicks=1500,batch="034-station")
 public static void legalHighWorkstationMustActuallyCraft(GameTestHelper h){BackendPreparationTests.flintAutomaticallyApproachesPublicTableAndCrafts(h);}
 @GameTest(template="empty",timeoutTicks=1000,batch="034-processing")
 public static void furnaceUsesRealOutputAndReleasesBody(GameTestHelper h){BackendProcessingTests.submittedOrderFreesBodyAndCollectsOnlyRealOutput(h);}
 @GameTest(template="empty",timeoutTicks=1400,batch="034-container-stance")
 public static void publicChestUsesVisibleStanceOnOtherSideOfWall(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(12,1,5));
  h.setBlock(new BlockPos(12,1,5),Blocks.CHEST);BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),p);
  for(int z=4;z<=6;z++)for(int y=1;y<=3;y++)h.setBlock(new BlockPos(10,y,z),Blocks.STONE);
  b.body().getInventory().setItem(9,new ItemStack(Items.COAL,3));
  b.submit(new ContainerRequest("store-around-wall",h.getLevel().getGameTime()+1200,p,ContainerRequest.Mode.DEPOSIT,Items.COAL,3,null));
  h.onEachTick(()->{if(!b.terminal())return;
   var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(p);
   int stored=0;for(int i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).is(Items.COAL))stored+=chest.getItem(i).getCount();
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&stored==3&&b.body().getInventory().countItem(Items.COAL)==0,"actual public chest transfer through legal visible stance: "+b.snapshot());b.close();h.succeed();});
 }
}
