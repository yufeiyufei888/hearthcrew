package io.github.yufeiyufei888.hearthcrew.gametest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
@GameTestHolder("hearthcrewcontinuous") @PrefixGameTestTemplate(false)
public class Continuous0213GameTests {
 private static void flat(GameTestHelper h){for(int x=0;x<20;x++)for(int z=0;z<14;z++)for(int y=0;y<8;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);}
 private static BodyOrder collect(BlockPos seed,String item,int count,int access,boolean prepare){return new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,seed,null,count,ResourceLocation.parse(item),List.of(),List.of(),List.of(),16,access,prepare?BodyOrder.Preparation.standard():BodyOrder.Preparation.disabled());}
 @GameTest(template="p0_empty",timeoutTicks=1500,batch="continuous-coal")
 public static void blockedNearestCandidateDoesNotEndBatch(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,5));p.inventory().setItem(9,new ItemStack(Items.IRON_PICKAXE));
  // Nearby enclosed ore requires unauthorized excavation; exposed row remains reachable.
  var closed=new BlockPos(4,1,5);for(int x=3;x<=5;x++)for(int z=4;z<=6;z++)for(int y=1;y<=3;y++)h.setBlock(new BlockPos(x,y,z),Blocks.BEDROCK);h.setBlock(closed,Blocks.COAL_ORE);
  for(int x=2;x<18;x++)h.setBlock(new BlockPos(x,1,10),Blocks.COAL_ORE);
  p.executor().submit("coal-0213",collect(h.absolutePos(closed),"minecraft:coal",16,0,false),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("coal-0213")).orElseThrow();
   if(p.tickCount%100==0)System.out.println("CONTINUOUS coal "+p.position()+" inventory="+p.inventory().countItem(Items.COAL)+" "+p.executor().travelStatus());
   if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"batch: "+r.message());h.assertTrue(p.inventory().countItem(Items.COAL)>=16,"16 native coal pickups");h.assertTrue(h.getBlockState(closed).is(Blocks.COAL_ORE),"sealed ore preserved");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=1800,batch="continuous-prepare")
 public static void realRecipePreparationMakesMissingStonePickaxe(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,5));p.inventory().setItem(12,new ItemStack(Items.BIRCH_LOG,4));p.inventory().setItem(13,new ItemStack(Items.WOODEN_PICKAXE));
  var ore=new BlockPos(8,1,5);h.setBlock(ore,Blocks.IRON_ORE);
  p.executor().submit("prepare-0213",collect(h.absolutePos(ore),"minecraft:raw_iron",1,16,true),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("prepare-0213")).orElseThrow();
   if(h.getTick()%100==0)System.out.println("CONTINUOUS preparation "+p.position()+" bodyTicks="+p.tickCount+" ticking="+p.serverLevel().isPositionEntityTicking(p.blockPosition())+" state="+r.state()+" report="+p.executor().executionReport("prepare-0213"));
   if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"preparation: "+r.message()+" checkpoints="+p.executor().executionReport("prepare-0213"));h.assertTrue(p.inventory().countItem(Items.RAW_IRON)==1&&p.inventory().countItem(Items.STONE_PICKAXE)==1,"crafted real pickaxe then mined actual iron");
    var report=p.executor().executionReport("prepare-0213");h.assertTrue(((Number)report.get("preparationSteps")).intValue()<=16&&((Number)report.get("preparationBroken")).intValue()<=64,"bounded preparation");
    var saved=p.executor().saveLedger();var restored=PlayerTestBodies.spawn(h,new BlockPos(18,1,12));restored.executor().restoreLedger(saved);h.assertTrue(restored.inventory().isEmpty()&&restored.executor().arbiter().snapshot(ActionId.of("prepare-0213")).orElseThrow().state()==ActionState.COMPLETED,"terminal checkpoint reload without inventory replay");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=1200,batch="continuous-descent") public static void descent(GameTestHelper h){Recovery0210GameTests.fourBlockDescentMinesAndNaturallyPicksUp(h);}
 @GameTest(template="p0_empty",timeoutTicks=300,batch="continuous-pickup") public static void sequencePickup(GameTestHelper h){Recovery0210GameTests.sequencePickupUsesChildTargetAndVanillaDelay(h);}
 @GameTest(template="p0_empty",timeoutTicks=100,batch="continuous-boat") public static void inventoryBoat(GameTestHelper h){Recovery0210GameTests.boatInMainInventorySelectedWithoutResourceArgument(h);}
 @GameTest(template="p0_empty",timeoutTicks=100,batch="continuous-torch") public static void wallTorchCountsAsActualVariant(GameTestHelper h){
  flat(h);var pos=new BlockPos(4,2,5);h.setBlock(pos.east(),Blocks.STONE);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,5));p.inventory().setItem(12,new ItemStack(Items.TORCH));
  p.executor().submit("torch-0213",new BodyOrder(BodyOrder.Kind.PLACE,h.absolutePos(pos),null,0,ResourceLocation.parse("minecraft:torch")),ActionPriority.OWNER);
  h.succeedWhen(()->{var r=p.executor().arbiter().snapshot(ActionId.of("torch-0213")).orElseThrow();h.assertTrue(r.state()==ActionState.COMPLETED,"torch result: "+r.message());h.assertTrue(h.getBlockState(pos).is(Blocks.WALL_TORCH)&&p.inventory().countItem(Items.TORCH)==0,"wall variant and one consumed item");});
 }
 @GameTest(template="p0_empty",timeoutTicks=1300,batch="continuous-far") public static void hundredBlockTransferUsesMultipleSegments(GameTestHelper h){
  for(int x=0;x<112;x++)for(int z=0;z<7;z++)for(int y=0;y<5;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,3));var peer=PlayerTestBodies.spawn(h,new BlockPos(102,1,3));p.inventory().setItem(12,new ItemStack(Items.COBBLESTONE));
  p.executor().submit("far-0213",new BodyOrder(BodyOrder.Kind.TRANSFER,null,peer.getUUID(),1,ResourceLocation.parse("minecraft:cobblestone")),ActionPriority.OWNER);
  h.succeedWhen(()->{var r=p.executor().arbiter().snapshot(ActionId.of("far-0213")).orElseThrow();h.assertTrue(r.state()==ActionState.COMPLETED,"segmented transfer: "+r.message()+" pos="+p.position());h.assertTrue(p.distanceToSqr(peer)<10&&peer.inventory().countItem(Items.COBBLESTONE)==1&&p.inventory().countItem(Items.COBBLESTONE)==0,"physically traversed ~100 blocks and transferred one actual item");});
 }
 @GameTest(template="p0_empty",timeoutTicks=1100,batch="continuous-cooking") public static void cookingUsesRealFurnaceTicksAndItems(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));p.inventory().setItem(12,new ItemStack(Items.PORKCHOP,3));p.inventory().setItem(13,new ItemStack(Items.COAL));p.inventory().setItem(14,new ItemStack(Items.FURNACE));
  p.executor().submit("cook-0213",collect(h.absolutePos(new BlockPos(5,1,5)),"minecraft:cooked_porkchop",3,0,true),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("cook-0213")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"cooking: "+r.message());h.assertTrue(p.inventory().countItem(Items.COOKED_PORKCHOP)==3&&p.inventory().countItem(Items.PORKCHOP)==0&&p.inventory().countItem(Items.COAL)==0,"actual cooking output and fuel consumption");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=900,batch="continuous-food") public static void huntThenCollectNativeFoodDrops(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,5));var chicken=h.spawn(net.minecraft.world.entity.EntityType.CHICKEN,new BlockPos(7,1,5));chicken.setNoAi(true);
  p.executor().submit("food-0213",collect(h.absolutePos(new BlockPos(7,1,5)),"minecraft:chicken",1,0,true),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("food-0213")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"hunting: "+r.message());h.assertTrue(!chicken.isAlive()&&p.inventory().countItem(Items.CHICKEN)>=1,"native damage/death/drop/pickup");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=100,batch="continuous-frontier") public static void forwardCandidatesAreNotLostToNearestQuota(GameTestHelper h){
  for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++)for(int y=0;y<4;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);
  var p=PlayerTestBodies.spawn(h,new BlockPos(0,1,0));var forward=h.absolutePos(new BlockPos(70,1,0));
  var choices=io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.frontier(p,net.minecraft.world.phys.Vec3.atBottomCenterOf(forward),24);
  h.assertTrue(!choices.isEmpty()&&choices.stream().anyMatch(c->c.getX()>p.getX()+16),"direction-ranked frontiers survive nearby candidates");h.succeed();
 }

}
