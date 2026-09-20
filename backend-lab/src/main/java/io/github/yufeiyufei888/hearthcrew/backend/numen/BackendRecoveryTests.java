package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_recovery")
@PrefixGameTestTemplate(false)
public final class BackendRecoveryTests {
 @GameTest(template="empty",timeoutTicks=1500,batch="recovery-table")
 public static void realHighWorkstation(GameTestHelper h){BackendPreparationTests.flintAutomaticallyApproachesPublicTableAndCrafts(h);}
 @GameTest(template="empty",timeoutTicks=1500,batch="recovery-replace")
 public static void replaceWornTools(GameTestHelper h){BackendPreparationTests.mossBuildsOwnTableAndReplacesWornTools(h);}
 @GameTest(template="empty",timeoutTicks=2800,batch="recovery-coal")
 public static void prepareAndCollectSixteen(GameTestHelper h){BackendPreparationTests.oneResourceRequestPreparesToolThenActuallyCollectsSixteen(h);}
 @GameTest(template="empty",timeoutTicks=1900,batch="recovery-descent")
 public static void descendFourAndPickUp(GameTestHelper h){BackendExcavationTests.authorizedOpeningDownFourMustCollect(h);}
 @GameTest(template="empty",timeoutTicks=1000,batch="recovery-water")
 public static void surfacePauseAndResume(GameTestHelper h){BackendSafetyTests.lowAirPreemptsAndResumesSameRequest(h);}
 @GameTest(template="empty",timeoutTicks=800,batch="recovery-fault")
 public static void sealedCandidatesCannotHoldBodyIndefinitely(GameTestHelper h){
  var b=BackendGateTests.spawn(h);var origin=b.body().blockPosition();
  b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++)if(x!=0||z!=0||y==2)h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),Blocks.BEDROCK.defaultBlockState());
  h.getLevel().setBlockAndUpdate(origin.east(5),Blocks.COAL_ORE.defaultBlockState());
  b.submit(new com.dwinovo.numen.core.task.mine.MineBlockTaskRecord("sealed-no-progress",h.getLevel().getGameTime()+2400,Set.of(Blocks.COAL_ORE),1,"coal"),16);
  h.onEachTick(()->{
   if(!b.terminal())return;
   h.assertTrue(!b.snapshot().state().equals("SUCCESS")&&b.snapshot().activeTicks()<=600,"bounded stalled execution, never success");
   h.assertTrue(b.body().getInventory().countItem(Items.COAL)==0,"no fabricated output");
   b.close();h.succeed();
  });
 }
}
