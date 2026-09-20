package io.github.yufeiyufei888.hearthcrew.gametest;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import io.github.yufeiyufei888.hearthcrew.runtime.*;

@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Recovery027GameTests {
 @GameTest(template="p0_empty",timeoutTicks=100,batch="recovery027")
 public static void snapshotWithNativeCheckpointDoesNotReflectGameInternals(GameTestHelper h) throws Exception {
  h.setBlock(new BlockPos(2,0,2),Blocks.STONE);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,2));
  var id=ActionId.of("checkpoint-027");
  p.executor().submit(id.value(),new BodyOrder(BodyOrder.Kind.WAIT,null,null,200),ActionPriority.OWNER);
  p.executor().arbiter().start(id);
  var checkpoint=new BodyExecutor.Checkpoint(p.level().dimension().location().toString(),p.position(),0,
      new ItemStack(Items.IRON_PICKAXE),p.blockPosition().below(),Blocks.STONE.defaultBlockState(),.5f,false,0,1,
      Map.of(),null,List.of(),null,null,null,0,List.of(),0,false);
  p.executor().arbiter().checkpoint(id,checkpoint);
  var link=new ModLink(h.getLevel().getServer(),java.nio.file.Path.of("unused-checkpoint-fixture"));
  var method=ModLink.class.getDeclaredMethod("snapshot");method.setAccessible(true);
  var view=(JsonObject)method.invoke(link);
  var body=java.util.stream.StreamSupport.stream(view.getAsJsonArray("companions").spliterator(),false)
      .map(JsonElement::getAsJsonObject).filter(b->b.get("botId").getAsString().equals(p.companionId().toString())).findFirst().orElseThrow();
  var action=body.getAsJsonObject("action");
  h.assertTrue(action.get("checkpointAvailable").getAsBoolean()&&!action.has("checkpoint"),"wire contains metadata, not native checkpoint object graph");
  h.assertTrue(p.executor().arbiter().activeSnapshot().orElseThrow().checkpoint()==checkpoint,"actual resume checkpoint untouched");
  h.succeed();
 }
 @GameTest(template="p0_empty",timeoutTicks=100,batch="recovery027")
 public static void occludedAggroDoesNotTriggerReflexButVisibleThreatDoes(GameTestHelper h){
  for(int x=0;x<10;x++)for(int z=0;z<7;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<5;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,3));
  var enemy=h.spawn(net.minecraft.world.entity.EntityType.HUSK,new BlockPos(7,1,3));enemy.setNoAi(true);enemy.setPersistenceRequired();enemy.setTarget(p);
  for(int y=1;y<5;y++)for(int z=0;z<7;z++)h.setBlock(new BlockPos(4,y,z),Blocks.STONE);
  h.assertTrue(!io.github.yufeiyufei888.hearthcrew.gameplay.LocalThreats.nearby(p).contains(enemy),"hidden retained aggro is not immediate threat");
  for(int y=1;y<5;y++)for(int z=0;z<7;z++)h.setBlock(new BlockPos(4,y,z),Blocks.AIR);
  h.assertTrue(io.github.yufeiyufei888.hearthcrew.gameplay.LocalThreats.nearby(p).contains(enemy),"visible enemy targeting body still activates safety");
  enemy.discard();h.succeed();
 }
}
