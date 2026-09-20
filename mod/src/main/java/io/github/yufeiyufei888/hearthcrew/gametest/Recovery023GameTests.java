package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation;
import io.github.yufeiyufei888.hearthcrew.entity.HarvestLedger;

@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Recovery023GameTests {
 @GameTest(template="p0_empty",timeoutTicks=1000,batch="preparation023")
 public static void prepareOwnStonePickaxeThenMineIron(GameTestHelper h){
  for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.setBlock(new BlockPos(x,0,z),Blocks.STONE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));var ore=h.absolutePos(new BlockPos(4,1,2));var table=h.absolutePos(new BlockPos(2,1,4));
  h.setBlock(new BlockPos(4,1,2),Blocks.IRON_ORE);h.setBlock(new BlockPos(4,2,2),Blocks.IRON_ORE);h.setBlock(new BlockPos(2,1,4),Blocks.CRAFTING_TABLE);
  p.inventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE));
  p.inventory().setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE,3));p.inventory().setItem(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK,2));
  var iron=new io.github.yufeiyufei888.hearthcrew.entity.BodyOrder(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.MINE,ore,null,2);
  p.executor().submit("precheck",iron,io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);int[] phase={0};
  h.onEachTick(()->{
   String id=phase[0]==0?"precheck":phase[0]==1?"make-pick":"iron-batch";var s=p.executor().arbiter().snapshot(io.github.yufeiyufei888.hearthcrew.kernel.ActionId.of(id));if(s.isEmpty()||!s.get().state().terminal())return;
   if(phase[0]==0){h.assertTrue(s.get().message().contains("MISSING_HARVEST_TOOL")&&s.get().message().contains("minecraft:stone_pickaxe"),"missing tier is actionable, not path failure");h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE),"no destructive probe");phase[0]++;p.executor().submit("make-pick",new io.github.yufeiyufei888.hearthcrew.entity.BodyOrder(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.CRAFT,table,null,1,net.minecraft.resources.ResourceLocation.withDefaultNamespace("stone_pickaxe")),io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);return;}
   h.assertTrue(s.get().state()==io.github.yufeiyufei888.hearthcrew.kernel.ActionState.COMPLETED,"preparation/batch must complete: "+s.get().message());
   if(phase[0]==1){phase[0]++;p.executor().submit("iron-batch",iron,io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);return;}
   h.assertTrue(p.inventory().countItem(net.minecraft.world.item.Items.RAW_IRON)==2,"two real iron drops using self-crafted stone pickaxe");h.succeed();
  });
 }
 @GameTest(template="p0_empty",timeoutTicks=1100,batch="scan023")
 public static void completedScanSurvivesRefreshAndSmallerReads(GameTestHelper h){
  for(int x=0;x<6;x++)for(int z=0;z<6;z++)h.setBlock(new BlockPos(x,0,z),Blocks.STONE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));NearbyObservation.scan(p,4);long[] completed={-1};
  h.onEachTick(()->{
   var value=NearbyObservation.scan(p,4);
   if(completed[0]<0){if(value.get("state").getAsString().equals("ready"))completed[0]=value.get("completedGameTick").getAsLong();return;}
   NearbyObservation.scan(p,2);
   if(p.serverLevel().getGameTime()-completed[0]>605){
    h.assertTrue(value.get("visitedBlocks").getAsInt()>0,"expiry must retain completed scan, not return visited=0");
    h.assertTrue(value.getAsJsonObject("categories").has("stone"),"prior resource clues remain while refreshing");
    h.assertTrue(value.get("completedGameTick").getAsLong()>=completed[0],"publication does not regress");h.succeed();
   }
  });
 }
 @GameTest(template="p0_empty",timeoutTicks=50,batch="ledger023")
 public static void unseenDropIsUnknownAndObservedLossIsNotPickup(GameTestHelper h){
  h.setBlock(new BlockPos(2,0,2),Blocks.STONE);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));
  var ledger=new HarvestLedger();var missing=java.util.UUID.randomUUID();ledger.broken("missing","minecraft:overworld",BlockPos.ZERO,java.util.Map.of(missing,1));
  ledger.observe(p);h.assertTrue(ledger.report("missing").get("recovery").equals("pickup_or_inspect_required"),"missing UUID cannot imply loss or success");
  ledger.removed(missing,"entity_removal:DISCARDED");h.assertTrue(ledger.report("missing").get("recovery").equals("unavailable"),"explicit entity removal settles availability only");
  var saved=new net.minecraft.nbt.CompoundTag();ledger.save(saved);var loaded=new HarvestLedger();loaded.restore(saved);
  h.assertTrue(loaded.report("missing").get("recovery").equals("unavailable"),"loss evidence survives save");
  h.assertTrue(((java.util.Map<?,?>)loaded.report("missing").get("acquired")).isEmpty(),"no invented pickup");h.succeed();
 }
}
