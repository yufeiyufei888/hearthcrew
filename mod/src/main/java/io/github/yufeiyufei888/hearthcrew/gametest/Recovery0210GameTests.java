package io.github.yufeiyufei888.hearthcrew.gametest;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions;
import io.github.yufeiyufei888.hearthcrew.kernel.*;

/** Scripted, isolated regression of the four-block height gap reported in peaceful play. */
@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Recovery0210GameTests {
 private static void terrain(GameTestHelper h,int top){
  for(int x=0;x<14;x++)for(int z=0;z<12;z++)for(int y=-4;y<9;y++)h.setBlock(new BlockPos(x,y,z),y<=top?Blocks.STONE:Blocks.AIR);
 }
 private static BodyOrder mine(BlockPos pos,int budget){return new BodyOrder(BodyOrder.Kind.MINE,pos,null,1,null,List.of(),List.of(),List.of(),32,budget);}
 @GameTest(template="p0_empty",timeoutTicks=1200,batch="access0210")
 public static void fourBlockDescentMinesAndNaturallyPicksUp(GameTestHelper h){
  terrain(h,4);var ore=new BlockPos(7,1,4);h.setBlock(ore,Blocks.COAL_ORE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,5,4));p.inventory().setItem(12,new ItemStack(Items.IRON_PICKAXE));
  p.executor().submit("down-0210",mine(h.absolutePos(ore),16),ActionPriority.OWNER);
  h.onEachTick(()->{var result=p.executor().arbiter().snapshot(ActionId.of("down-0210")).orElseThrow();
   if(p.tickCount%100==0)System.out.println("ACCESS0210 pos="+p.position()+" phase="+p.executor().travelStatus()+" result="+result.state());
   if(result.state().terminal()){
    h.assertTrue(result.state()==ActionState.COMPLETED,"four-block descent: "+result.message()+" pos="+p.position());
    h.assertTrue(p.inventory().countItem(Items.COAL)==1,"real native coal pickup");
    var report=p.executor().executionReport("down-0210");int broken=((Number)report.get("accessBroken")).intValue();
    h.assertTrue(broken>0&&broken<=16,"shared safe-access budget "+report);
    h.assertTrue(p.getY()<h.absolutePos(new BlockPos(2,5,4)).getY()-2,"physically descended, no remote suction");h.succeed();
   }});
 }
 @GameTest(template="p0_empty",timeoutTicks=300,batch="accesszero0210")
 public static void noAccessBudgetLeavesUndergroundOreIntact(GameTestHelper h){
  terrain(h,4);var ore=new BlockPos(7,1,4);h.setBlock(ore,Blocks.COAL_ORE);var p=PlayerTestBodies.spawn(h,new BlockPos(2,5,4));p.inventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  p.executor().submit("zero-0210",mine(h.absolutePos(ore),0),ActionPriority.OWNER);
  h.succeedWhen(()->{var result=p.executor().arbiter().snapshot(ActionId.of("zero-0210")).orElseThrow();h.assertTrue(result.state().terminal()&&result.state()!=ActionState.COMPLETED,"zero budget explicit failure");h.assertTrue(h.getBlockState(ore).is(Blocks.COAL_ORE),"no uncollectable ore destruction");});
 }
 @GameTest(template="p0_empty",timeoutTicks=250,batch="pickupsequence0210")
 public static void sequencePickupUsesChildTargetAndVanillaDelay(GameTestHelper h){
  terrain(h,0);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,4));var at=h.absolutePos(new BlockPos(4,1,4));var item=new ItemEntity(h.getLevel(),at.getX()+.5,at.getY(),at.getZ()+.5,new ItemStack(Items.COAL,3));item.setPickUpDelay(25);h.getLevel().addFreshEntity(item);
  var children=List.of(new BodyOrder(BodyOrder.Kind.PICKUP,null,item.getUUID(),0),new BodyOrder(BodyOrder.Kind.SELECT,null,null,0));
  p.executor().submit("pickup-seq-0210",new BodyOrder(BodyOrder.Kind.SEQUENCE,null,null,0,null,List.of(),children,List.of(),32),ActionPriority.OWNER);
  h.succeedWhen(()->{var result=p.executor().arbiter().snapshot(ActionId.of("pickup-seq-0210")).orElseThrow();h.assertTrue(result.state()==ActionState.COMPLETED,"child pickup completes without root target");h.assertTrue(p.inventory().countItem(Items.COAL)==3,"actual three items, no double pickup");});
 }
 @GameTest(template="p0_empty",timeoutTicks=100,batch="boat0210")
 public static void boatInMainInventorySelectedWithoutResourceArgument(GameTestHelper h){
  terrain(h,0);for(int x=3;x<12;x++)for(int z=2;z<9;z++)h.setBlock(new BlockPos(x,1,z),Blocks.WATER);
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,4));p.inventory().setItem(0,new ItemStack(Items.STICK));p.inventory().setItem(18,new ItemStack(Items.BIRCH_BOAT));
  var result=BoatActions.launch(p,h.absolutePos(new BlockPos(3,1,4)),null);
  h.assertTrue(result.completed(),"select real inventory boat: "+result);h.assertTrue(p.inventory().countItem(Items.BIRCH_BOAT)==0&&p.inventory().countItem(Items.STICK)==1,"one boat consumed, hand item preserved");h.succeed();
 }
 @GameTest(template="p0_empty",timeoutTicks=1200,batch="sharedbudget0210")
 public static void sequenceCannotRenewAccessBudgetBetweenMines(GameTestHelper h){
  terrain(h,4);var first=new BlockPos(7,1,4);var second=new BlockPos(10,-1,4);h.setBlock(first,Blocks.COAL_ORE);h.setBlock(second,Blocks.COAL_ORE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,5,4));p.inventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  var order=new BodyOrder(BodyOrder.Kind.SEQUENCE,null,null,0,null,List.of(),List.of(mine(h.absolutePos(first),16),mine(h.absolutePos(second),16)),List.of(),32,11);
  p.executor().submit("shared-0210",order,ActionPriority.OWNER);
  h.onEachTick(()->{var result=p.executor().arbiter().snapshot(ActionId.of("shared-0210")).orElseThrow();if(result.state().terminal()){
   h.assertTrue(result.state()==ActionState.PARTIAL,"shared budget must stop second step: "+result.message());
   h.assertTrue(p.inventory().countItem(Items.COAL)==1&&h.getBlockState(second).is(Blocks.COAL_ORE),"first real item retained; second inaccessible ore not destroyed");
   h.assertTrue(((Number)p.executor().executionReport("shared-0210").get("accessBroken")).intValue()<=11,"root allowance not renewed");
   var ledger=p.executor().saveLedger();var restored=PlayerTestBodies.spawn(h,new BlockPos(11,5,9));restored.executor().restoreLedger(ledger);
   h.assertTrue(!restored.executor().recoveryInvalid()&&((Number)restored.executor().executionReport("shared-0210").get("accessBroken")).intValue()<=11,"budget evidence survives load, no replay");
   h.assertTrue(restored.inventory().countItem(Items.COAL)==0,"ledger load never restores inventory effects");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=300,batch="teammatepickup0210")
 public static void teammateMayCollectMinerDropWithoutDoubleCredit(GameTestHelper h){
  terrain(h,0);var ore=new BlockPos(4,1,4);h.setBlock(ore,Blocks.COAL_ORE);
  var miner=PlayerTestBodies.spawn(h,new BlockPos(2,1,4));var collector=PlayerTestBodies.spawn(h,new BlockPos(4,1,5));miner.inventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  miner.executor().submit("team-pickup-0210",mine(h.absolutePos(ore),0),ActionPriority.OWNER);
  final boolean[] reclaim={false};
  h.onEachTick(()->{
   // Scripted pause after destruction gives the second real body the recovery job,
   // avoiding an arbitrary race against randomized vanilla drop velocity.
   if(!reclaim[0]&&h.getBlockState(ore).isAir()){reclaim[0]=true;miner.executor().pause();collector.executor().submit("recover-team-0210",BodyOrder.move(h.absolutePos(ore)),ActionPriority.OWNER);}
   if(collector.inventory().countItem(Items.COAL)==1)miner.executor().resume();
   var result=miner.executor().arbiter().snapshot(ActionId.of("team-pickup-0210")).orElseThrow();if(result.state().terminal()){
   h.assertTrue(result.state()==ActionState.COMPLETED,"mining recovery closed by actual teammate pickup: "+result.message());
   h.assertTrue(collector.inventory().countItem(Items.COAL)==1&&miner.inventory().countItem(Items.COAL)==0,"physical holder only receives the item");
   var report=miner.executor().executionReport("team-pickup-0210");h.assertTrue(((Map<?,?>)report.get("acquired")).isEmpty()&&((Number)((Map<?,?>)report.get("acquiredByOthers")).get("minecraft:coal")).intValue()==1,"attribution does not fabricate miner inventory");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=100,batch="lossevidence0210")
 public static void removalClosesDebtWhileUnobservedDebtRemainsUnknown(GameTestHelper h){
  var ledger=new HarvestLedger();var lost=UUID.randomUUID();var unseen=UUID.randomUUID();ledger.broken("loss","minecraft:overworld",BlockPos.ZERO,Map.of(lost,2,unseen,1));
  ledger.removed(lost,"entity_removal:DISCARDED");var report=ledger.report("loss");h.assertTrue(((List<?>)report.get("pendingDrops")).size()==1&&((List<?>)report.get("closedDrops")).size()==1,"only proven removal closes an obligation");
  var saved=new net.minecraft.nbt.CompoundTag();ledger.save(saved);var loaded=new HarvestLedger();loaded.restore(saved);
  var result=loaded.report("loss");h.assertTrue(((List<?>)result.get("pendingDrops")).size()==1&&((List<?>)result.get("closedDrops")).size()==1&&loaded.claims(unseen)&&!loaded.claims(lost),"closed and unknown evidence survive save/load");
  h.assertTrue(loaded.acquired(lost,2,"minecraft:coal",true)==0&&loaded.acquired(unseen,1,"minecraft:coal",true)==1,"loss is not reacquired; unknown can later reconcile once");h.succeed();
 }
 @GameTest(template="p0_empty",timeoutTicks=100,batch="earlypickup0210")
 public static void nativePickupBeforeFirstSequenceTickDoesNotFinishWholeSequence(GameTestHelper h){
  terrain(h,0);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,4));var item=new ItemEntity(h.getLevel(),p.getX(),p.getY(),p.getZ(),new ItemStack(Items.COAL));item.setNoPickUpDelay();h.getLevel().addFreshEntity(item);
  var children=List.of(new BodyOrder(BodyOrder.Kind.PICKUP,null,item.getUUID(),0),new BodyOrder(BodyOrder.Kind.SELECT,null,null,5));
  p.executor().submit("early-pickup-0210",new BodyOrder(BodyOrder.Kind.SEQUENCE,null,null,0,null,List.of(),children,List.of(),32),ActionPriority.OWNER);
  h.assertTrue(p.getBoundingBox().inflate(1,.5,1).intersects(item.getBoundingBox()),"fixture is in native contact range");item.playerTouch(p);
  h.assertTrue(p.executor().arbiter().snapshot(ActionId.of("early-pickup-0210")).orElseThrow().state()==ActionState.ACCEPTED,"event cannot skip the rest of SEQUENCE");
  h.succeedWhen(()->{h.assertTrue(p.executor().arbiter().snapshot(ActionId.of("early-pickup-0210")).orElseThrow().state()==ActionState.COMPLETED,"sequence consumes recorded native result");h.assertTrue(p.selectedSlot()==5&&p.inventory().countItem(Items.COAL)==1,"later SELECT ran, coal counted once");});
 }
}
