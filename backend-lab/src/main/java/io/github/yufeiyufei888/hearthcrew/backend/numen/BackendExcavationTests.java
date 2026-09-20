package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.Set;

@GameTestHolder("hearthcrew_backend_excavation")
@PrefixGameTestTemplate(false)
public class BackendExcavationTests {
    @GameTest(template="empty",timeoutTicks=1900,batch="excavate-down-four")
    public static void authorizedOpeningDownFourMustCollect(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);
        // A four-high stone shelf with an open lower floor at its side. An eight-block
        // staircase cut is feasible; no pre-cut passage, no liquid or protected blocks.
        for(int x=0;x<=12;x++)for(int y=1;y<=4;y++)for(int z=0;z<16;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        h.setBlock(new BlockPos(8,1,5),Blocks.COAL_ORE);
        b.body().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3,5,5))));
        b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
        b.submit(new MineBlockTaskRecord("authorized-opening-down-four",h.getLevel().getGameTime()+1750,Set.of(Blocks.COAL_ORE),1,"coal"),16);
        var ctx=com.dwinovo.numen.core.pathing.bridge.ContextFactory.forExecution(b.body(),com.dwinovo.numen.core.pathing.moves.TerrainPermit.TERRAFORM);
        h.assertTrue(!ctx.allowDownward,"owned execution context must reject digging directly below feet");
        h.onEachTick(()->{
            if(!b.terminal())return;
            String state=b.snapshot().state(),outcome=b.snapshot().toString();
            int got=b.body().getInventory().countItem(Items.COAL);
            int broken=0;for(int x=0;x<=12;x++)for(int y=1;y<=4;y++)for(int z=0;z<16;z++)
                if(!(x==8&&y==1&&z==5)&&h.getBlockState(new BlockPos(x,y,z)).isAir())broken++;
            b.close();
            h.assertTrue(broken<=16,"shared auxiliary budget cannot exceed sixteen: "+broken);
            h.assertTrue(state.equals("SUCCESS")&&got==1,"known feasible descent must actually acquire coal; auxiliary="+broken+" "+outcome);
            h.succeed();
        });
    }
}
