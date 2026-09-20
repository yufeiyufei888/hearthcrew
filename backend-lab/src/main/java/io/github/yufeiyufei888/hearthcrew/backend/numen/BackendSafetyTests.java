package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder("hearthcrew_backend_safety")
@PrefixGameTestTemplate(false)
public final class BackendSafetyTests {
    @GameTest(template="empty",timeoutTicks=160,batch="safety-spawn-authority")
    public static void nativeSpawnCallbackCannotStartASecondTask(GameTestHelper h) {
        var base=BackendGateTests.spawn(h);var owner=base.body().getOwnerUuid();var position=base.body().position();base.close();
        UUID id=UUID.randomUUID();AtomicReference<String> reply=new AtomicReference<>();
        com.dwinovo.numen.entity.CompanionEvents.subscribe(com.dwinovo.numen.api.CompanionEvent.SPAWN,p->{
            if(!p.getUUID().equals(id))return;
            TaskDispatch.setTask(p,new MoveToTaskRecord("foreign-at-spawn",p.level().getGameTime()+100,position.x+3,position.y,position.z,null,false),
                new com.google.gson.JsonObject(),reply::set);
        });
        var b=NumenBackend.spawn(h.getLevel(),id,"Lab"+id.toString().substring(0,8),owner,position,false);
        h.assertTrue(reply.get()!=null&&reply.get().contains("HEARTHCREW_AUTHORITY_REQUIRED"),"foreign dispatch rejected during native spawn callback");
        h.runAfterDelay(15,()->{
            h.assertTrue(b.snapshot().state().equals("IDLE")&&b.body().position().distanceTo(position)<.1,"no foreign work or movement escaped creation");
            h.assertTrue(b.body().getInventory().isEmpty(),"fresh body inventory was not restored or fabricated");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1000,batch="safety-breath")
    public static void lowAirPreemptsAndResumesSameRequest(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);
        // Explicit fixture: sealed sides, open top. After surfacing we drain the tank
        // and open its exit to independently prove retained work resumes without replay.
        for(int x=0;x<=9;x++)for(int z=0;z<=10;z++)for(int y=1;y<=4;y++)
            h.setBlock(new BlockPos(x,y,z),x==0||x==9||z==0||z==10?Blocks.STONE:Blocks.WATER);
        b.body().setAirSupply(100);
        var target=new BlockPos(19,1,5);
        BackendGateTests.move(h,b,"rescue-preserves-work",target);
        boolean[] drained={false},resumed={false};long[] rescueTicks={0};
        h.runAfterDelay(5,()->{
            b.pause();long before=((Number)b.diagnostics().get("reactionTicks")).longValue();
            h.runAfterDelay(10,()->{
                h.assertTrue(((Number)b.diagnostics().get("reactionTicks")).longValue()==before,"pause freezes rescue as well as work");
                h.assertTrue(b.body().zza==0&&b.body().xxa==0,"no held movement input while paused");
                b.resume();resumed[0]=true;
            });
        });
        h.onEachTick(()->{
            rescueTicks[0]=((Number)b.diagnostics().get("reactionTicks")).longValue();
            if(!drained[0]&&resumed[0]&&rescueTicks[0]>5&&!b.body().isEyeInFluid(FluidTags.WATER)) {
                h.assertTrue(b.body().isAlive(),"native surfacing before drowning");
                h.assertTrue(b.snapshot().state().equals("RUNNING"),"original request retained during rescue");
                for(int x=0;x<=9;x++)for(int z=0;z<=10;z++)for(int y=1;y<=4;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
                drained[0]=true;
            }
            if(!b.terminal())return;
            var snapshot=b.snapshot();var diagnostics=b.diagnostics();var pos=b.body().position();b.close();
            h.assertTrue(drained[0]&&snapshot.state().equals("SUCCESS"),"rescue then original move must succeed: "+snapshot+diagnostics);
            h.assertTrue(pos.distanceTo(Vec3.atBottomCenterOf(h.absolutePos(target)))<1.2,"real arrival after retained work resumes");
            h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120,batch="safety-ingress")
    public static void upstreamDispatchCannotTakeManagedBody(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,2));
        b.body().pauseReflex("lab-retained-intent");
        var reply=new AtomicReference<String>();
        TaskDispatch.runSync(b.body(),new CraftRequest("foreign-sync",h.getLevel().getGameTime()+100,Items.STICK,4),reply::set);
        h.assertTrue(reply.get()!=null&&reply.get().contains("HEARTHCREW_AUTHORITY_REQUIRED"),"foreign sync must return explicit rejection");
        reply.set(null);
        TaskDispatch.setTask(b.body(),new MoveToTaskRecord("foreign-move",h.getLevel().getGameTime()+100,8d,1d,5d,null,false),new com.google.gson.JsonObject(),reply::set);
        h.assertTrue(reply.get()!=null&&reply.get().contains("HEARTHCREW_AUTHORITY_REQUIRED"),"foreign async rejected");
        h.runAfterDelay(20,()->{
            h.assertTrue(b.body().getInventory().countItem(Items.OAK_PLANKS)==2&&b.body().getInventory().countItem(Items.STICK)==0,"no foreign start side effects");
            h.assertTrue(b.snapshot().state().equals("IDLE"),"foreign tasks do not occupy managed body");
            h.assertTrue(b.body().reflexPaused("lab-retained-intent"),"upstream empty slots cannot clear backend intent");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="safety-proof")
    public static void outsiderPickupIsNotTeamProduction(GameTestHelper h) {
        // Injected evidence contract, not a claim that a physical pickup occurred in this test.
        var miner=UUID.randomUUID();var proof=DropProof.forAction(h.getLevel(),miner,"injected-external-proof",1);var drop=UUID.randomUUID();
        proof.spawned(drop,Items.COAL,3);DropProof.picked(h.getLevel().getServer(),drop,UUID.randomUUID(),Items.COAL,3,3);
        h.assertTrue(proof.acquired()==0,"unmanaged picker cannot satisfy own or team goal");
        h.assertTrue(proof.external.getOrDefault("minecraft:coal",0)==3,"external acquisition retained separately");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1600,batch="safety-combat")
    public static void nativeDefenseReturnsToOriginalWork(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.IRON_SWORD));
        h.getLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        var enemy=net.minecraft.world.entity.EntityType.ZOMBIE.create(h.getLevel());
        // Deliberately stationary attacker isolates native weapon/cooldown execution and arbitration.
        var pos=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5,1,5)));
        enemy.moveTo(pos.x,pos.y,pos.z,0,0);enemy.setNoAi(true);enemy.setTarget(b.body());enemy.setHealth(8);
        enemy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
        h.getLevel().addFreshEntity(enemy);BackendGateTests.move(h,b,"work-after-defense",new BlockPos(19,1,5));
        h.onEachTick(()->{
            if(!b.terminal())return;
            var state=b.snapshot();var diagnostics=b.diagnostics();var actual=b.body().position();boolean killed=!enemy.isAlive();
            enemy.discard();b.close();
            h.assertTrue(((Number)diagnostics.get("reactionTicks")).longValue()>0,"defense actually preempted ordinary work");
            h.assertTrue(killed,"native player attack defeats stationary target");
            h.assertTrue(state.state().equals("SUCCESS"),"original work resumes: "+state+diagnostics);
            h.assertTrue(actual.distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(19,1,5))))<1.2,"actual work arrival after defense");h.succeed();
        });
    }
}
