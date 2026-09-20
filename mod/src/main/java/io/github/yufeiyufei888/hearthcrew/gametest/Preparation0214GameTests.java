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
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
@GameTestHolder("hearthcrewpreparation") @PrefixGameTestTemplate(false)
public class Preparation0214GameTests {
 private static void flat(GameTestHelper h){for(int x=0;x<20;x++)for(int z=0;z<14;z++)for(int y=0;y<11;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);}
 private static BodyOrder order(BodyOrder.Kind kind,BlockPos pos,int count,String resource,int steps){return new BodyOrder(kind,pos,null,count,resource==null?null:ResourceLocation.parse(resource),List.of(),List.of(),List.of(),16,16,new BodyOrder.Preparation(true,6,steps,64));}
 private static void materials(CompanionEntity p){p.inventory().setItem(9,new ItemStack(Items.COBBLESTONE,11));p.inventory().setItem(10,new ItemStack(Items.STICK,5));}
 @GameTest(template="p0_empty",timeoutTicks=1500,batch="prep-moss") public static void mossReplacesWornToolsAndResumesMining(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));
  for(int slot=0;slot<2;slot++){var tool=new ItemStack(Items.STONE_PICKAXE);tool.setDamageValue(tool.getMaxDamage()-2);p.inventory().setItem(slot,tool);}
  var ore=new BlockPos(10,1,5);h.setBlock(ore,Blocks.IRON_ORE);p.executor().submit("moss-0214",order(BodyOrder.Kind.MINE,h.absolutePos(ore),1,null,16),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("moss-0214")).orElseThrow();if(r.state().terminal()){
   h.assertTrue(r.state()==ActionState.COMPLETED,"Moss preparation: "+r.message()+" "+p.executor().executionReport("moss-0214"));
   h.assertTrue(p.inventory().countItem(Items.RAW_IRON)==1&&p.inventory().countItem(Items.STONE_PICKAXE)==3,"new tool made and actual ore picked up; two worn tools preserved");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=1800,batch="prep-flint") public static void flintReachesElevatedWorkbenchOrReportsPath(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(3,1,5));materials(p);
  for(int i=0;i<7;i++)for(int y=0;y<=i;y++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(5+i,y,z),Blocks.STONE);
  var table=new BlockPos(12,7,5);h.setBlock(table.below(),Blocks.STONE);h.setBlock(table,Blocks.CRAFTING_TABLE);CrewWorldData.get(p.server).markPlacement(p.level(),h.absolutePos(table),p.getUUID(),true);
  p.executor().submit("flint-0214",order(BodyOrder.Kind.CRAFT,null,1,"minecraft:stone_pickaxe",16),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("flint-0214")).orElseThrow();if(r.state().terminal()){
   System.out.println("PREPARATION_0214_FLINT: "+r.state()+" "+r.message());
   if(r.state()==ActionState.COMPLETED)h.assertTrue(p.inventory().countItem(Items.STONE_PICKAXE)==1&&p.inventory().countItem(Items.COBBLESTONE)==8,"actual recipe consumption");
   else h.assertTrue(r.message().contains("STATION")||r.message().contains("PATH")||r.message().contains("PROGRESS"),"specific path/station condition: "+r.message());h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=1000,batch="prep-craft") public static void craftWithoutStationPlacesOwnedWorkbench(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));
  p.executor().submit("craft-0214",order(BodyOrder.Kind.CRAFT,null,1,"minecraft:stone_pickaxe",16),ActionPriority.OWNER);
  h.succeedWhen(()->{var r=p.executor().arbiter().snapshot(ActionId.of("craft-0214")).orElseThrow();h.assertTrue(r.state()==ActionState.COMPLETED,"craft: "+r.message());h.assertTrue(p.inventory().countItem(Items.STONE_PICKAXE)==1&&p.inventory().countItem(Items.CRAFTING_TABLE)==0,"placed table and created pickaxe");var saved=p.executor().saveLedger();var restored=PlayerTestBodies.spawn(h,new BlockPos(18,1,12));restored.executor().restoreLedger(saved);h.assertTrue(restored.inventory().isEmpty()&&restored.executor().arbiter().snapshot(ActionId.of("craft-0214")).orElseThrow().state()==ActionState.COMPLETED,"reload does not replay crafting");});
 }
 @GameTest(template="p0_empty",timeoutTicks=1000,batch="prep-dig") public static void excavationPreparesMissingTool(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));
  p.executor().submit("dig-0214",order(BodyOrder.Kind.EXCAVATE,h.absolutePos(new BlockPos(10,1,5)),8,null,16),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("dig-0214")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"dig: "+r.message());h.assertTrue(p.inventory().countItem(Items.STONE_PICKAXE)==1,"prepared actual pickaxe");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=900,batch="prep-budget") public static void sequenceSharesPreparationStepBudget(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));
  var child=order(BodyOrder.Kind.CRAFT,null,1,"minecraft:stone_pickaxe",16);
  var root=new BodyOrder(BodyOrder.Kind.SEQUENCE,null,null,0,null,List.of(),List.of(child,child),List.of(),16,0,new BodyOrder.Preparation(true,6,2,0));
  p.executor().submit("budget-0214",root,ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("budget-0214")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()!=ActionState.COMPLETED&&r.message().contains("BUDGET"),"shared step limit: "+r.message());h.assertTrue(p.inventory().countItem(Items.STONE_PICKAXE)==1,"first confirmed output retained, second not fabricated");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=700,batch="prep-query") public static void queryIsReadOnlyAndAggregatesMaterials(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);var id=ResourceLocation.parse("minecraft:stone_pickaxe");
  var result=io.github.yufeiyufei888.hearthcrew.gameplay.RecipeDiscovery.query(p,List.of(id),4).getFirst();
  h.assertTrue(Boolean.FALSE.equals(result.get("materialsReady"))&&p.inventory().countItem(Items.COBBLESTONE)==11&&p.inventory().countItem(Items.STICK)==5,"four recipes share ingredients; query makes no changes");
  h.setBlock(new BlockPos(9,1,5),Blocks.IRON_ORE);
  p.executor().submit("disabled-0214",BodyOrder.mine(h.absolutePos(new BlockPos(9,1,5))),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("disabled-0214")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.FAILED&&p.inventory().countItem(Items.STONE_PICKAXE)==0,"legacy disabled preparation does not gain permission");h.succeed();}});
 }

 @GameTest(template="p0_empty",timeoutTicks=1400,batch="prep-replace") public static void wornToolDuringExcavationPreservesCheckpoint(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(4,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));p.inventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
  for(int x=7;x<=9;x++)for(int z=0;z<14;z++)for(int y=1;y<8;y++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
  p.executor().submit("wear-0214",order(BodyOrder.Kind.EXCAVATE,h.absolutePos(new BlockPos(12,1,5)),16,null,16),ActionPriority.OWNER);
  boolean[] injected={false};h.onEachTick(()->{if(!injected[0]&&h.getBlockState(new BlockPos(7,1,5)).isAir()){for(int i=0;i<36;i++){var tool=p.inventory().getItem(i);if(tool.is(Items.STONE_PICKAXE))tool.setDamageValue(tool.getMaxDamage()-1);}injected[0]=true;}
   var r=p.executor().arbiter().snapshot(ActionId.of("wear-0214")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.COMPLETED,"excavation renewal: "+r.message());h.assertTrue(injected[0]&&p.inventory().countItem(Items.STONE_PICKAXE)==2,"wear injection triggered and replacement crafted during action");h.succeed();}});
 }
 @GameTest(template="p0_empty",timeoutTicks=500,batch="prep-protected") public static void preparationCannotPlaceInProtectedSite(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);p.inventory().setItem(11,new ItemStack(Items.CRAFTING_TABLE));
  final var fixtureOwner=UUID.randomUUID();
  for(var pos:BlockPos.betweenClosed(h.absolutePos(new BlockPos(0,0,0)),h.absolutePos(new BlockPos(19,10,13))))CrewWorldData.get(p.server).markPlacement(p.level(),pos,fixtureOwner,false);
  p.executor().submit("protected-0214",order(BodyOrder.Kind.CRAFT,null,1,"minecraft:stone_pickaxe",16),ActionPriority.OWNER);
  h.onEachTick(()->{var r=p.executor().arbiter().snapshot(ActionId.of("protected-0214")).orElseThrow();if(r.state().terminal()){h.assertTrue(r.state()==ActionState.FAILED&&r.message().contains("NO_LEGAL_SITE"),"protected station: "+r.message());h.assertTrue(p.inventory().countItem(Items.CRAFTING_TABLE)==1&&p.inventory().countItem(Items.STONE_PICKAXE)==0,"no placement or invented output");for(var pos:BlockPos.betweenClosed(h.absolutePos(new BlockPos(0,0,0)),h.absolutePos(new BlockPos(19,10,13))))CrewWorldData.get(p.server).confirmPlayerRemoval(p.level(),pos,"player:"+fixtureOwner);h.succeed();}});
 }

 @GameTest(template="p0_empty",timeoutTicks=100,batch="prep-capacity") public static void missingMaterialsAndFullInventoryAreSeparate(GameTestHelper h){
  flat(h);var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,5));materials(p);
  for(int i=0;i<36;i++)p.inventory().setItem(i,new ItemStack(Items.DIRT,64));p.inventory().setItem(9,new ItemStack(Items.COBBLESTONE,64));p.inventory().setItem(10,new ItemStack(Items.STICK,64));
  var recipe=(net.minecraft.world.item.crafting.CraftingRecipe)p.level().getRecipeManager().byKey(ResourceLocation.parse("minecraft:stone_pickaxe")).orElseThrow().value();
  var full=io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.assess(p,recipe,1);h.assertTrue(Boolean.FALSE.equals(full.get("outputCapacity")),"full inventory cannot fabricate capacity");
  p.inventory().clearContent();var missing=io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.assess(p,recipe,1);h.assertTrue(Boolean.FALSE.equals(missing.get("materialsReady"))&&!((List<?>)missing.get("materialGaps")).isEmpty(),"missing ingredients reported separately");h.succeed();
 }
}
