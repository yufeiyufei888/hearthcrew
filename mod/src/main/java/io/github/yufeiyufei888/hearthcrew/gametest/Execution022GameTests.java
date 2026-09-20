package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation;

@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Execution022GameTests {
 @GameTest(template="p0_empty",timeoutTicks=20,batch="ledger022")
 public static void mergedDropAccountingDoesNotDuplicate(GameTestHelper h){
  var ledger=new HarvestLedger();var first=java.util.UUID.randomUUID();var second=java.util.UUID.randomUUID();
  ledger.broken("a","minecraft:overworld",BlockPos.ZERO,java.util.Map.of(first,2));
  ledger.broken("b","minecraft:overworld",new BlockPos(1,0,0),java.util.Map.of(second,3));
  ledger.merged(first,second,2);
  h.assertTrue(ledger.acquired(second,2,"minecraft:cobblestone",true)==2,"partial merged pickup consumes only observed amount");
  var saved=new net.minecraft.nbt.CompoundTag();ledger.save(saved);var restored=new HarvestLedger();restored.restore(saved);
  h.assertTrue(restored.acquired(second,1,"minecraft:cobblestone",false)==1,"human pickup tracked separately");
  h.assertTrue(restored.acquired(second,8,"minecraft:cobblestone",true)==2,"remaining claim bounds accounting");
  h.assertTrue(restored.acquired(second,8,"minecraft:cobblestone",true)==0,"repeat cannot credit again");h.succeed();
 }
 private static void floor(GameTestHelper h){for(int x=0;x<12;x++)for(int z=0;z<12;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<7;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}}
 @GameTest(template="p0_empty",timeoutTicks=650,batch="swim022")
 public static void swimmingReachesSupportedShore(GameTestHelper h){
  floor(h);for(int x=3;x<=8;x++)for(int z=2;z<=7;z++)h.setBlock(new BlockPos(x,1,z),Blocks.WATER);
  for(int x=0;x<=2;x++)for(int z=0;z<12;z++)h.setBlock(new BlockPos(x,1,z),Blocks.STONE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(5,1,4));var goal=h.absolutePos(new BlockPos(1,2,4));
  p.executor().submit("swim-shore",new BodyOrder(BodyOrder.Kind.SWIM,goal,null,0),ActionPriority.OWNER);
  h.succeedWhen(()->{var s=p.executor().arbiter().snapshot(ActionId.of("swim-shore"));
   h.assertTrue(s.isPresent()&&s.get().state()==ActionState.COMPLETED,"explicit swim reaches land: "+s.map(a->a.state()+" "+a.message()).orElse("missing")+" position="+p.position()+" travel="+p.executor().travelStatus());
   h.assertTrue(!p.isInWater()&&p.onGround(),"supported shore: position="+p.position()+" ground="+p.onGround()+" water="+p.isInWater()+" goal="+goal+" state="+h.getLevel().getBlockState(goal)+" receipt="+s.get().message());
  });
 }
 @GameTest(template="p0_empty",timeoutTicks=2400,batch="trees022")
 public static void gatherAcrossTwoSmallTrees(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,2));
  for(int x:new int[]{3,8}){
   h.setBlock(new BlockPos(x,0,2),Blocks.DIRT);h.setBlock(new BlockPos(x,1,2),Blocks.BIRCH_LOG);h.setBlock(new BlockPos(x,2,2),Blocks.BIRCH_LOG);
   for(int dx:new int[]{-1,1})h.setBlock(new BlockPos(x+dx,3,2),Blocks.BIRCH_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE,1));
  }
  p.executor().submit("two-trees",BodyOrder.gather(ResourceLocation.withDefaultNamespace("birch_log"),4),ActionPriority.OWNER);
  h.onEachTick(()->{var s=p.executor().arbiter().snapshot(ActionId.of("two-trees"));if(s.isPresent()&&s.get().state().terminal()){
   h.assertTrue(s.get().state()==ActionState.COMPLETED,"multi-tree "+s.get().message()+" "+p.executor().gatherDiagnostics("two-trees"));
   h.assertTrue(p.inventory().countItem(Items.BIRCH_LOG)==4,"four actual logs from separate short trees");h.succeed();
  }});
 }
 @GameTest(template="p0_empty",timeoutTicks=350,batch="stairs022")
 public static void stableStairArrival(GameTestHelper h){
  floor(h);for(int x=3;x<=6;x++)for(int z=1;z<=3;z++)h.setBlock(new BlockPos(x,1,z),Blocks.STONE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,2));var to=h.absolutePos(new BlockPos(5,2,2));
  p.executor().submit("stairs",BodyOrder.move(to),ActionPriority.OWNER);
  h.succeedWhen(()->{var s=p.executor().arbiter().snapshot(ActionId.of("stairs"));h.assertTrue(s.isPresent()&&s.get().state()==ActionState.COMPLETED,"stairs completes");h.assertTrue(io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.landedAt(p,to,.6),"actual supported target height");});
 }
 @GameTest(template="p0_empty",timeoutTicks=1600,batch="execution022")
 public static void batchMiningAndPersistedEvidence(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,2));p.inventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  for(int x=3;x<=6;x++)for(int z=2;z<=3;z++)h.setBlock(new BlockPos(x,1,z),Blocks.STONE);
  p.executor().submit("batch-eight",new BodyOrder(BodyOrder.Kind.MINE,h.absolutePos(new BlockPos(3,1,2)),null,8,ResourceLocation.withDefaultNamespace("stone")),ActionPriority.OWNER);
  h.onEachTick(()->{
   var state=p.executor().arbiter().snapshot(ActionId.of("batch-eight"));
   if(p.tickCount%100==0)System.out.println("BATCH022 pos="+p.position()+" action="+state+" evidence="+p.executor().harvestEvidence());
   if(state.isPresent()&&state.get().state().terminal()){
    h.assertTrue(state.get().state()==ActionState.COMPLETED,"batch terminal "+state);
    h.assertTrue(p.inventory().countItem(Items.COBBLESTONE)==8,"eight actual drops, not accepted count");
    var saved=p.executor().saveLedger();var restored=new HarvestLedger();restored.restore(saved);
    h.assertTrue(restored.reports().size()==1&&restored.report("batch-eight").get("recovery").equals("accounted"),"persisted accounted harvest");
    h.succeed();
   }
  });
 }
 @GameTest(template="p0_empty",timeoutTicks=200,batch="execution022")
 public static void unsupportedLandingNeverCompletes(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));var target=h.absolutePos(new BlockPos(2,2,2));
  p.executor().submit("unsupported",BodyOrder.move(target),ActionPriority.OWNER);
  h.succeedWhen(()->{var s=p.executor().arbiter().snapshot(ActionId.of("unsupported"));h.assertTrue(s.isPresent()&&s.get().state()==ActionState.FAILED,"floating goal rejected");});
 }
 @GameTest(template="p0_empty",timeoutTicks=700,batch="execution022")
 public static void stonePerceptionAndPendingHarvest(GameTestHelper h){
  floor(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));h.setBlock(new BlockPos(3,1,2),Blocks.DEEPSLATE);
  var ledger=new HarvestLedger();var drop=java.util.UUID.randomUUID();ledger.broken("old","minecraft:overworld",new BlockPos(1,2,3),java.util.Map.of(drop,2));
  var tag=new net.minecraft.nbt.CompoundTag();ledger.save(tag);var restored=new HarvestLedger();restored.restore(tag);
  h.assertTrue(restored.pendingAt("minecraft:overworld",new BlockPos(1,2,3)),"pending recovery survives reload");
  var merged=java.util.UUID.randomUUID();restored.merged(drop,merged,2);restored.acquired(merged,2,"minecraft:cobbled_deepslate",false);
  h.assertTrue(restored.report("old").get("recovery").equals("accounted"),"teammate acquisition settles, not self contribution");
  h.succeedWhen(()->{var scan=NearbyObservation.scan(p,4);h.assertTrue(scan.get("state").getAsString().equals("ready"),"incremental scan completes");h.assertTrue(scan.getAsJsonObject("categories").has("stone"),"basic stone category present");});
 }
}
