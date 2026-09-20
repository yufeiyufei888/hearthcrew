package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;

@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Water024GameTests {
 private static void pool(GameTestHelper h){
  for(int x=0;x<7;x++)for(int z=0;z<7;z++)for(int y=0;y<7;y++){
   boolean bank=x==0||x>=5||z==0||z==6;
   h.setBlock(new BlockPos(x,y,z),y==0||bank&&y<=3?Blocks.STONE:y<=3?Blocks.WATER:Blocks.AIR);
  }
 }
 @GameTest(template="p0_empty",timeoutTicks=1100,batch="water024")
 public static void threeIdlePlayersExitDeepWaterAndStayDry(GameTestHelper h){
  pool(h);
  var crew=java.util.List.of(PlayerTestBodies.spawn(h,new BlockPos(2,1,2)),PlayerTestBodies.spawn(h,new BlockPos(2,1,3)),PlayerTestBodies.spawn(h,new BlockPos(2,1,4)));
  int[] dry={0};
  h.onEachTick(()->{
   boolean landed=crew.stream().allMatch(p->!p.isInWater()&&p.onGround()&&p.getY()>=h.absolutePos(new BlockPos(0,4,0)).getY()-.1);
   dry[0]=landed?dry[0]+1:0;
   if(h.getTick()%200==0)for(var p:crew)System.out.println("WATER024 tick="+h.getTick()+" pos="+p.position()+" air="+p.getAirSupply()+" travel="+p.executor().travelStatus());
   if(dry[0]>=100){h.assertTrue(crew.stream().allMatch(p->p.getHealth()==20),"no drowning damage; recovered without a model");h.succeed();}
  });
 }
 @GameTest(template="p0_empty",timeoutTicks=1000,batch="water024checkpoint")
 public static void recoveryRetiresUnderwaterWorkWithoutReplaying(GameTestHelper h){
  pool(h);var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,3));
  p.executor().submit("old-water-work",new BodyOrder(BodyOrder.Kind.WAIT,null,null,1000),ActionPriority.OWNER);
  h.onEachTick(()->{
   var s=p.executor().arbiter().snapshot(ActionId.of("old-water-work"));
   if(s.isPresent()&&s.get().state().terminal()){
    h.assertTrue(s.get().state()==ActionState.CANCELLED&&s.get().message().contains("WATER_INTERRUPTED"),"old unsafe leg not replayed or claimed complete");
    h.assertTrue(!p.isInWater()&&p.onGround(),"recovery must actually land first");h.succeed();
   }
  });
 }
}
