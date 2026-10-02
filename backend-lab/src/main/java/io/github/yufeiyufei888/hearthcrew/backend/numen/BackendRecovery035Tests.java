package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_recovery035")
@PrefixGameTestTemplate(false)
public final class BackendRecovery035Tests {
 @GameTest(template="empty",timeoutTicks=2400,batch="035-index-shadow")
 public static void verifiedCoalSurvivesNearestIndexShadow(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  var protectedCells=new HashSet<BlockPos>();
  for(int x=1;x<=8;x++)for(int z=1;z<=8;z++)for(int y=3;y<=4;y++){
   var rel=new BlockPos(x,y,z);h.setBlock(rel,Blocks.COAL_ORE);protectedCells.add(h.absolutePos(rel));
  }
  BackendLab.PROTECTED.addAll(protectedCells);
  for(int x=12;x<20;x++)for(int z:new int[]{5,9})h.setBlock(new BlockPos(x,1,z),Blocks.COAL_ORE);
  b.submit(new CollectRequest("verified-index-shadow",h.getLevel().getGameTime()+2200,Items.COAL,16,Set.of(Blocks.COAL_ORE),true),0);
  h.onEachTick(()->{if(!b.terminal())return;
   for(var cell:protectedCells)h.assertTrue(h.getLevel().getBlockState(cell).is(Blocks.COAL_ORE),"protected index decoys must remain intact");
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.COAL)==16,"one root must collect sixteen granted coal despite 128 nearer protected index hits: "+b.snapshot());
   h.assertTrue(b.snapshot().result().contains("\"ownNew\":16"),"actual pickup evidence required");
   BackendLab.PROTECTED.removeAll(protectedCells);b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1300,batch="035-small-tree-scope")
 public static void canopyOutsideHarvestRadiusStillValidatesTrunk(GameTestHelper h){
  var b=BackendGateTests.spawn(h);h.setBlock(new BlockPos(6,0,5),Blocks.DIRT);
  for(int y=1;y<=6;y++)h.setBlock(new BlockPos(6,y,5),Blocks.OAK_LOG);
  for(int x=5;x<=7;x++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,7,z),Blocks.OAK_LEAVES);
  var request=new CollectRequest("narrow-tree-scope",h.getLevel().getGameTime()+1100,Items.OAK_LOG,4,Set.of(Blocks.OAK_LOG),true);
  request.radius=4;b.submit(request,0);
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.OAK_LOG)==4,"loaded canopy halo must validate, not broaden harvest: "+b.snapshot());
   h.assertTrue(h.getBlockState(new BlockPos(6,6,5)).is(Blocks.OAK_LOG),"out-of-scope upper trunk remains");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1200,batch="035-exact-height")
 public static void reachableUpperFeetUseRealStairRoute(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var origin=b.body().blockPosition();var target=origin.east(8).above(3);
  for(int x=3;x<=14;x++)for(int z=4;z<=6;z++){
   int height=Math.min(3,Math.max(0,x-4));for(int y=0;y<=height;y++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
  }
  b.submit(new MoveToTaskRecord("exact-upper-route",h.getLevel().getGameTime()+1000,target.getX()+.5,(double)target.getY(),target.getZ()+.5,null,false));
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("SUCCESS")&&Math.abs(b.body().getY()-target.getY())<.15&&b.body().onGround(),"known staircase must reach actual upper feet, never column fallback: "+b.snapshot());b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1000,batch="035-impossible-height")
 public static void wrongHeightDoesNotBecomeArrival(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var origin=b.body().blockPosition();
  b.submit(new MoveToTaskRecord("unsupported-height",h.getLevel().getGameTime()+800,origin.getX()+5.5,(double)origin.getY()+5,origin.getZ()+.5,null,false));
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("FAILED")&&Math.abs(b.body().getY()-origin.getY())<.15,"no terrain permission means no invented upper landing or false success: "+b.snapshot());b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1200,batch="035-rejected-batch")
 public static void unsafeSourceIsNotResubmittedSixteenTimes(GameTestHelper h){
  var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
  h.setBlock(new BlockPos(6,1,5),Blocks.IRON_ORE);h.setBlock(new BlockPos(6,2,5),Blocks.WATER);
  for(var rel:List.of(new BlockPos(5,2,5),new BlockPos(7,2,5),new BlockPos(6,2,4),new BlockPos(6,2,6),new BlockPos(6,3,5)))h.setBlock(rel,Blocks.GLASS);
  var request=new CollectRequest("unsafe-source-once",h.getLevel().getGameTime()+1000,Items.RAW_IRON,1,Set.of(Blocks.IRON_ORE),true);request.radius=4;b.submit(request,0);
  h.onEachTick(()->{if(!b.terminal())return;
   h.assertTrue(b.snapshot().state().equals("FAILED")&&b.body().getInventory().countItem(Items.RAW_IRON)==0&&h.getBlockState(new BlockPos(6,1,5)).is(Blocks.IRON_ORE),"liquid safety remains enforced");
   h.assertTrue(!b.snapshot().result().contains("\"executionSteps\":16"),"identical unsafe source may not consume every preparation step");b.close();h.succeed();});
 }
 @GameTest(template="empty",timeoutTicks=1500,batch="035-workstation")
 public static void highWorkstationRemainsUsable(GameTestHelper h){BackendPreparationTests.flintAutomaticallyApproachesPublicTableAndCrafts(h);}
}
