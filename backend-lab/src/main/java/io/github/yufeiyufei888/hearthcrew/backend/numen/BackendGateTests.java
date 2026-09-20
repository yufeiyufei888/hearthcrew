package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_lab")
@PrefixGameTestTemplate(false)
public class BackendGateTests {
    static NumenBackend spawn(GameTestHelper h) {
        h.onEachTick(()->BackendTestClock.pace(h.getLevel().getGameTime()));
        for (int x=0;x<24;x++) for(int z=0;z<16;z++) for(int y=0;y<6;y++)
            h.setBlock(new BlockPos(x,y,z), y==0 ? Blocks.STONE : Blocks.AIR);
        BackendProtection.configure(h.getLevel().getServer(), level -> Set.copyOf(BackendLab.PROTECTED));
        var position = h.absolutePos(new BlockPos(3,1,5));
        var id = UUID.randomUUID();
        var backend = NumenBackend.spawn(h.getLevel(),id,"Lab"+id.toString().substring(0,8),
                UUID.randomUUID(),Vec3.atBottomCenterOf(position),false);
        // No network owner exists in this headless fixture. Reproduce the exact
        // owner-online branch of CompanionTickDispatcher: native bounded/expiring
        // pad refresh from server ticks, not an extra route-wide forced ticket.
        h.onEachTick(()->{if(h.getLevel().getServer().getPlayerList().getPlayer(id)==backend.body())
            com.dwinovo.numen.entity.CompanionChunkLoader.refresh(backend.body());});
        com.dwinovo.numen.entity.CompanionChunkLoader.refresh(backend.body());
        h.assertTrue(backend.body().getInventory().isEmpty(),"fresh native player must start empty without clearing saved data");
        return backend;
    }
    static void move(GameTestHelper h,NumenBackend b,String id,BlockPos relative) {
        var p=h.absolutePos(relative);
        b.submit(new MoveToTaskRecord(id,h.getLevel().getGameTime()+1000,
                p.getX()+.5,(double)p.getY(),p.getZ()+.5,null,false));
    }
    @GameTest(template="empty",timeoutTicks=200,batch="gate-registration")
    public static void serverPlayerRegistrationAndSave(GameTestHelper h) {
        var b=spawn(h); var p=b.body();
        h.runAfterDelay(10,()->{
            h.assertTrue(p.server.getPlayerList().getPlayerByName(p.getGameProfile().getName())==p,"native name lookup");
            h.assertTrue(p.server.getPlayerList().getPlayer(p.getUUID())==p,"native UUID lookup");
            h.assertTrue(p.getInventory().isEmpty(),"fresh body must be empty");
            p.getInventory().setItem(9,new ItemStack(Items.COBBLESTONE,11));
            var identity=p.getUUID();var name=p.getGameProfile().getName();var owner=p.getOwnerUuid();
            b.close();
            var restored=CompanionFactory.spawn(h.getLevel().getServer(),identity,name,owner,h.getLevel(),null);
            var restoredBackend=new NumenBackend(restored);
            h.assertTrue(restored.getInventory().countItem(Items.COBBLESTONE)==11,"native save/load preserves inventory exactly");
            h.assertTrue(restoredBackend.snapshot().state().equals("IDLE"),"no work replay on new body");
            restoredBackend.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1300,batch="gate-move")
    public static void knownFlatRouteMustComplete(GameTestHelper h) {
        var b=spawn(h);var target=new BlockPos(19,1,5);
        move(h,b,"flat-route",target);
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"known path must succeed: "+b.snapshot());
            h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(target)))<1.2,"actual arrival");
            h.assertTrue(b.body().onGround(),"grounded at completion");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1500,batch="gate-pause")
    public static void pauseResumeAndLateCancellation(GameTestHelper h) {
        var b=spawn(h);move(h,b,"pause-route",new BlockPos(21,1,5));
        h.runAfterDelay(12,()->{
            b.pause();
            h.runAfterDelay(8,()->{
                var pausedAt=b.body().position();var ticks=b.snapshot().activeTicks();
                h.runAfterDelay(20,()->{
                    h.assertTrue(b.body().position().distanceTo(pausedAt)<.1,"paused authority prevents locomotion");
                    h.assertTrue(b.snapshot().activeTicks()==ticks,"paused task not ticked");
                    b.resume();
                });
            });
        });
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"resume must reach known destination: "+b.snapshot());
            h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(21,1,5))))<1.2,"actual arrival after resume");
            b.cancel();h.assertTrue(b.snapshot().state().equals("SUCCESS"),"late cancel cannot change terminal");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=2400,batch="gate-mine")
    public static void continuousSixteenCoalMustEnterInventory(GameTestHelper h) {
        var b=spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,5),Blocks.COAL_ORE);
        b.submit(new MineBlockTaskRecord("sixteen-coal",h.getLevel().getGameTime()+2200,Set.of(Blocks.COAL_ORE),16,"coal"));
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"upstream mining result: "+b.snapshot());
            h.assertTrue(b.body().getInventory().countItem(Items.COAL)==16,"success requires sixteen real coal, not partial progress");
            h.assertTrue(b.body().getInventory().getItem(0).getDamageValue()>0,"native tool durability consumed");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=500,batch="gate-protection")
    public static void protectedOreMustRemainIntact(GameTestHelper h) {
        var b=spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        var relative=new BlockPos(5,1,5);var absolute=h.absolutePos(relative);
        h.setBlock(relative,Blocks.COAL_ORE);BackendLab.PROTECTED.add(absolute);BackendRuntime.blockedBreaks=0;
        b.submit(new MineBlockTaskRecord("protected-coal",h.getLevel().getGameTime()+300,Set.of(Blocks.COAL_ORE),1,"coal"));
        h.runAfterDelay(350,()->{
            h.assertTrue(h.getBlockState(relative).is(Blocks.COAL_ORE),"protection must prevent real block modification");
            h.assertTrue(b.body().getInventory().countItem(Items.COAL)==0,"protected ore grants no inventory");
            h.assertTrue(BackendRuntime.blockedBreaks>0,"fixture must exercise a denied native break");
            b.cancel();BackendLab.PROTECTED.remove(absolute);b.close();h.succeed();
        });
    }
}
