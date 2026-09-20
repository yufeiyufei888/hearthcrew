package io.github.yufeiyufei888.hearthcrew.gametest;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain;
import io.github.yufeiyufei888.hearthcrew.kernel.*;

/** Explicit scripted fixtures; no model, personal save or post-start teleport. */
@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Continuous029GameTests {
 private static void floor(GameTestHelper h){for(int x=0;x<14;x++)for(int z=0;z<12;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<8;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}}
 private static ResourceLocation id(String name){return ResourceLocation.withDefaultNamespace(name);}
 @GameTest(template="p0_empty",timeoutTicks=600,batch="continuous029")
 public static void mixedCollisionSurfacesUseActualFeetHeight(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,3));
  for(int z=0;z<12;z++){
   h.setBlock(new BlockPos(3,0,z),Blocks.STONE_SLAB.defaultBlockState());
   h.setBlock(new BlockPos(4,0,z),Blocks.STONE_STAIRS.defaultBlockState());
   h.setBlock(new BlockPos(5,0,z),Blocks.OAK_LEAVES.defaultBlockState());
   h.setBlock(new BlockPos(6,0,z),Blocks.FARMLAND.defaultBlockState());
  }
  h.assertTrue(Math.abs(TravelTerrain.feetPoint(p,h.absolutePos(new BlockPos(3,1,3))).y-h.absolutePos(new BlockPos(3,0,3)).getY()-.5)<.001,"slab half height");
  for(int x:new int[]{3,4,5,6})h.assertTrue(TravelTerrain.standable(p,h.absolutePos(new BlockPos(x,1,3))),"collision support at x="+x);
  var destination=h.absolutePos(new BlockPos(9,1,3));p.executor().submit("mixed-029",BodyOrder.move(destination),ActionPriority.OWNER);
  h.onEachTick(()->{var a=p.executor().arbiter().snapshot(ActionId.of("mixed-029")).orElseThrow();if(a.state().terminal()){h.assertTrue(a.state()==ActionState.COMPLETED,"mixed terrain real movement: "+a.message());h.assertTrue(TravelTerrain.landedAt(p,destination,.6),"actual supported arrival");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=1400,batch="collect029")
 public static void collectSixteenItemsInOneExecutorLease(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,4));p.inventory().setItem(12,new ItemStack(Items.IRON_PICKAXE));
  for(int x=4;x<8;x++)for(int y=1;y<=4;y++)h.setBlock(new BlockPos(x,y,4),Blocks.COAL_ORE);
  var order=new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,null,null,16,id("coal"),List.of(),List.of(),List.of(id("coal_ore")),12);
  p.executor().submit("collect-029",order,ActionPriority.OWNER);
  h.onEachTick(()->{var a=p.executor().arbiter().snapshot(ActionId.of("collect-029")).orElseThrow();if(a.state().terminal()){
   h.assertTrue(a.state()==ActionState.COMPLETED,"single collect result: "+a.message()+" "+p.executor().executionReport("collect-029"));
   h.assertTrue(p.inventory().countItem(Items.COAL)>=16,"sixteen actual coal in inventory");
   var restored=PlayerTestBodies.spawn(h,new BlockPos(11,1,8));restored.executor().restoreLedger(p.executor().saveLedger());
   h.assertTrue(!restored.executor().recoveryInvalid()&&restored.executor().arbiter().snapshot(ActionId.of("collect-029")).orElseThrow().state()==ActionState.COMPLETED,"collection identity and terminal survive codec");
   h.assertTrue(restored.inventory().countItem(Items.COAL)==0,"restoring receipts never imports inventory");
   h.assertTrue(p.executor().arbiter().journal().stream().filter(e->e.state()==ActionState.ACCEPTED).count()==1,"one body request, no per-block model turn");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=160,batch="sequence029")
 public static void preparationSequenceStopsAtFailureWithoutReplayingCraft(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));p.inventory().setItem(0,new ItemStack(Items.OAK_LOG));
  var actions=List.of(new BodyOrder(BodyOrder.Kind.CRAFT,null,null,1,id("oak_planks")),new BodyOrder(BodyOrder.Kind.CRAFT,null,null,1,id("stick")),new BodyOrder(BodyOrder.Kind.CRAFT,null,null,1,id("iron_pickaxe")));
  var order=new BodyOrder(BodyOrder.Kind.SEQUENCE,null,null,0,null,List.of(),actions,List.of(),32);
  p.executor().submit("sequence-029",order,ActionPriority.OWNER);
  h.onEachTick(()->{var a=p.executor().arbiter().snapshot(ActionId.of("sequence-029")).orElseThrow();if(a.state().terminal()){
   h.assertTrue(a.state()==ActionState.PARTIAL,"missing iron stops prepared chain as partial");h.assertTrue(p.inventory().countItem(Items.STICK)==4&&p.inventory().countItem(Items.OAK_PLANKS)==2,"earlier recipe results exactly once");
   p.executor().submit("sequence-029",order,ActionPriority.OWNER);h.assertTrue(p.inventory().countItem(Items.STICK)==4,"duplicate cannot replay first steps");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=400,batch="yield029")
 public static void acceptedYieldLeavesBlockerPosition(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(4,1,4));var other=PlayerTestBodies.spawn(h,new BlockPos(5,1,4));var start=p.position();
  p.executor().submit("yield-029",new BodyOrder(BodyOrder.Kind.YIELD,null,null,0),ActionPriority.OWNER);
  h.onEachTick(()->{var a=p.executor().arbiter().snapshot(ActionId.of("yield-029")).orElseThrow();if(a.state().terminal()){
   h.assertTrue(a.state()==ActionState.COMPLETED,"actual yield: "+a.message());h.assertTrue(p.position().distanceToSqr(start)>=3.5&&p.onGround(),"left original space on safe ground");h.assertTrue(p.distanceToSqr(other)>1,"not moved into teammate");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=180,batch="collectmissing029")
 public static void collectionRejectsMissingToolWithoutBreakingOre(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));var ore=new BlockPos(3,1,2);h.setBlock(ore,Blocks.IRON_ORE);
  p.executor().submit("no-tool-029",new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,null,null,1,id("raw_iron"),List.of(),List.of(),List.of(id("iron_ore")),4),ActionPriority.OWNER);
  h.succeedWhen(()->{var a=p.executor().arbiter().snapshot(ActionId.of("no-tool-029")).orElseThrow();h.assertTrue(a.state()==ActionState.FAILED&&a.message().contains("MISSING_HARVEST_TOOL"),"specific full inventory tool condition");h.assertTrue(h.getBlockState(ore).is(Blocks.IRON_ORE),"no unqualified destruction");});
 }
}
