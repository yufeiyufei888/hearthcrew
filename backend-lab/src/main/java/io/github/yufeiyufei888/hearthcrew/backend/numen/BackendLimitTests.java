package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_limits")
@PrefixGameTestTemplate(false)
public class BackendLimitTests {
    @GameTest(template="empty",templateNamespace="hearthcrew_backend_limits_candidate",timeoutTicks=1900,batch="limits-nearest-protected")
    public static void deniedNearestMustNotStopReachableBatch(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        var protectedPos=h.absolutePos(new BlockPos(5,1,5));BackendLab.PROTECTED.add(protectedPos);
        h.setBlock(new BlockPos(5,1,5),Blocks.COAL_ORE);
        for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,7),Blocks.COAL_ORE);
        b.submit(new MineBlockTaskRecord("protected-nearest-batch",h.getLevel().getGameTime()+1700,Set.of(Blocks.COAL_ORE),16,"coal"));
        h.onEachTick(()->{
            if(!b.terminal())return;
            var snapshot=b.snapshot();int actual=b.body().getInventory().countItem(Items.COAL);
            boolean preserved=h.getLevel().getBlockState(protectedPos).is(Blocks.COAL_ORE);
            b.close();BackendLab.PROTECTED.remove(protectedPos);
            h.assertTrue(preserved,"must not break protected nearest ore");
            h.assertTrue(snapshot.state().equals("SUCCESS")&&actual==16,"must skip denied candidate and get sixteen permitted coal; actual="+actual+" "+snapshot);
            h.succeed();
        });
    }
    @GameTest(template="empty",templateNamespace="hearthcrew_backend_limits_door",timeoutTicks=900,batch="limits-zero-access")
    public static void deniedShortcutMustUseExistingDoor(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
        // Two-block thick wall, reachable ore on the far side, three-wide doorway
        // twelve blocks sideways. No auxiliary breaking has been authorized.
        for(int x=7;x<=8;x++)for(int y=1;y<=5;y++)for(int z=0;z<15;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        for(int x=7;x<=8;x++)for(int y=1;y<=3;y++)for(int z=12;z<=14;z++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        h.setBlock(new BlockPos(11,1,5),Blocks.COAL_ORE);
        b.submit(new MineBlockTaskRecord("door-not-unauthorized-shortcut",h.getLevel().getGameTime()+750,Set.of(Blocks.COAL_ORE),1,"coal"),0);
        h.onEachTick(()->{
            if(!b.terminal())return;
            var snap=b.snapshot();int actual=b.body().getInventory().countItem(Items.COAL);b.close();
            for(int x=7;x<=8;x++)for(int y=1;y<=5;y++)for(int z=0;z<12;z++)h.assertTrue(h.getBlockState(new BlockPos(x,y,z)).is(Blocks.STONE),"wall must remain intact");
            h.assertTrue(snap.state().equals("SUCCESS")&&actual==1,"known door route must collect without repeated denied shortcut: "+snap);
            h.succeed();
        });
    }
    @GameTest(template="empty",templateNamespace="hearthcrew_backend_limits_output",timeoutTicks=250,batch="limits-false-output")
    public static void InventoryTransferMustNotCountAsMinedOutput(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        var ore=new BlockPos(21,1,5);h.setBlock(ore,Blocks.COAL_ORE);
        b.submit(new MineBlockTaskRecord("unrelated-inventory-increase",h.getLevel().getGameTime()+150,Set.of(Blocks.COAL_ORE),16,"coal"));
        // Explicit synthetic external inventory transaction, not a mined/pickup event.
        // It challenges the success contract; it is not used in a positive outcome test.
        b.body().getInventory().setItem(9,new ItemStack(Items.COAL,16));
        h.onEachTick(()->{
            if(!b.terminal())return;
            var snap=b.snapshot();boolean oreIntact=h.getBlockState(ore).is(Blocks.COAL_ORE);b.close();
            h.assertTrue(oreIntact,"test must finish before actual mining");
            h.assertTrue(!snap.state().equals("SUCCESS"),"external inventory increase cannot prove mining output: "+snap);
            h.succeed();
        });
    }
}
