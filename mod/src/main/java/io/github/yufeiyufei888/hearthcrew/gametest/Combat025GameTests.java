package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class Combat025GameTests {
 @GameTest(template="p0_empty",timeoutTicks=500,batch="combat025")
 public static void defendApproachesBesideOccupiedEnemy(GameTestHelper h){
  for(int x=0;x<12;x++)for(int z=0;z<10;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<5;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
  h.getLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
  var p=PlayerTestBodies.spawn(h,new BlockPos(2,1,4));
  p.inventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
  var enemy=h.spawn(net.minecraft.world.entity.EntityType.HUSK,new BlockPos(8,1,4));enemy.setNoAi(true);enemy.setPersistenceRequired();enemy.setTarget(p);float health=enemy.getHealth();
  p.executor().submit("defence-occupied",new BodyOrder(BodyOrder.Kind.SELF_DEFENCE,null,null,0),ActionPriority.SAFETY);
  h.onEachTick(()->{
   if(p.tickCount%100==0)System.out.println("COMBAT_029_TRACE pos="+p.position()+" enemy="+enemy.position()+" safety="+p.executor().localSafetyStatus()+" travel="+p.executor().travelStatus()+" path="+p.getNavigation().movementType());
   var s=p.executor().arbiter().snapshot(ActionId.of("defence-occupied"));
   h.assertTrue(s.isPresent()&&!s.get().state().terminal(),"enemy="+enemy.isAlive()+" removed="+enemy.getRemovalReason()+" pos="+enemy.position()+" body="+p.position()+" threat="+io.github.yufeiyufei888.hearthcrew.gameplay.LocalThreats.nearby(p).size()+" same defence lease: "+s.map(a->a.state()+" "+a.message()).orElse("missing"));
   if(enemy.getHealth()<health){h.assertTrue(p.distanceToSqr(enemy)<16,"actually approached and dealt native melee damage");enemy.discard();p.discard();h.succeed();}
  });
 }
}
