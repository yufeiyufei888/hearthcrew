package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("hearthcrew_backend_lifecycle")
@PrefixGameTestTemplate(false)
public class BackendLifecycleTests {
    @GameTest(template="empty",timeoutTicks=350,batch="lifecycle-relocation-exception")
    public static void failedNativeRelocationUnwindsAndNextWorkCanRun(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);int[] phase={0};
        h.runAfterDelay(6,()->{
            BackendGateTests.move(h,b,"before-relocation-exception",new BlockPos(19,1,5));
            long original=b.snapshot().identity().generation();boolean threw=false;
            // Explicit invalid native argument causes an exception after the wrapper
            // begins, without a fake success or a test-only production bypass.
            try {b.body().changeDimension(null);}catch(NullPointerException expected){threw=true;}
            h.assertTrue(threw&&b.snapshot().state().equals("CANCELLED")&&b.snapshot().identity().generation()==original+1,"invalid relocation still invalidates old work");
            var p=b.body().position();b.body().teleportTo(p.x,p.y,p.z);
            h.assertTrue(b.snapshot().identity().generation()==original+2,"exception did not leave nested relocation depth held");
            BackendGateTests.move(h,b,"after-relocation-exception",new BlockPos(12,1,5));phase[0]=1;
        });
        h.onEachTick(()->{if(phase[0]==0||!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(12,1,5))))<1.2,"ordinary movement after failed native relocation must physically finish: "+b.snapshot());
            b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=100,batch="lifecycle-native-commands")
    public static void registeredPlayerCommandsKeepNativePermissions(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var body=b.body();var server=body.server;var name=body.getGameProfile().getName();
        h.runAfterDelay(5,()->{
            h.assertTrue(!body.createCommandSourceStack().hasPermission(2)&&!server.getPlayerList().isOp(body.getGameProfile()),"body never gains operator permission");
            var console=server.createCommandSourceStack();
            server.getCommands().performPrefixedCommand(console,"give "+name+" minecraft:cobblestone 3");
            h.assertTrue(body.getInventory().countItem(Items.COBBLESTONE)==3,"native give resolves player name");
            server.getCommands().performPrefixedCommand(console,"clear "+name+" minecraft:cobblestone 2");
            h.assertTrue(body.getInventory().countItem(Items.COBBLESTONE)==1,"native clear quantity");
            server.getCommands().performPrefixedCommand(console,"experience add "+name+" 7 levels");
            h.assertTrue(body.experienceLevel==7,"native player experience");
            server.getCommands().performPrefixedCommand(console,"effect give "+name+" minecraft:haste 10 0");
            h.assertTrue(body.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED),"native effect");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="lifecycle-mod-payload")
    public static void clientlessPlayerDiscardsUnnegotiatedModPayloads(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);
        h.runAfterDelay(5,()->{
            var payload=new net.minecraft.network.protocol.common.custom.DiscardedPayload(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hearthcrew_backend_lab","unnegotiated_test"));
            var packet=new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload);
            b.body().connection.send(packet);
            b.body().connection.send(packet,null);
            h.assertTrue(b.body().server.getPlayerList().getPlayer(b.body().getUUID())==b.body(),"mod payload must not reject or remove a clientless player");
            b.close();h.succeed();
        });
    }
    private static void death(GameTestHelper h,boolean keep) {
        var b=BackendGateTests.spawn(h);var original=b.body();var server=original.server;
        boolean prior=h.getLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        h.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keep,server);
        var foot=new BlockPos(19,1,5);var head=foot.east();
        h.setBlock(foot,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST).setValue(BedBlock.PART,BedPart.FOOT));
        h.setBlock(head,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST).setValue(BedBlock.PART,BedPart.HEAD));
        original.setRespawnPosition(h.getLevel().dimension(),h.absolutePos(head),0,false,false);
        original.getInventory().setItem(9,new ItemStack(Items.COBBLESTONE,11));
        BackendGateTests.move(h,b,"must-not-survive-death",new BlockPos(15,1,5));
        final net.minecraft.world.phys.AABB[] deathBox={original.getBoundingBox()};
        h.runAfterDelay(4,()->{
            deathBox[0]=original.getBoundingBox().inflate(6);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"kill "+original.getGameProfile().getName());
            System.out.println("BACKEND_LAB_DEATH keep="+keep+" deathBox="+deathBox[0]+" items="+h.getLevel().getEntitiesOfClass(ItemEntity.class,deathBox[0]).stream().map(e->e.getItem()+"@"+e.position()).toList());
        });
        final boolean[] moved={false};
        h.onEachTick(()->{
            if(b.snapshot().identity().generation()<2)return;
            if(!moved[0]) {
                h.assertTrue(b.body()!=original&&b.body().getUUID().equals(original.getUUID()),"new native body, stable identity");
                h.assertTrue(server.getPlayerList().getPlayers().stream().filter(p->p.getUUID().equals(original.getUUID())).count()==1,"single registered player");
                h.assertTrue(b.snapshot().state().equals("CANCELLED"),"old task terminal after native kill: "+b.snapshot());
                h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(head)))<3,"native bed respawn, not owner relocation");
                h.assertTrue(b.body().getInventory().countItem(Items.COBBLESTONE)==(keep?11:0),"vanilla keepInventory state");
                int drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,deathBox[0]).stream().filter(e->e.getItem().is(Items.COBBLESTONE)).mapToInt(e->e.getItem().getCount()).sum();
                h.assertTrue(drops==(keep?0:11),"native death drops exactly once: "+drops);
                moved[0]=true;BackendGateTests.move(h,b,"new-body-work",new BlockPos(15,1,8));return;
            }
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"new generation executes new work");
            h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(15,1,8))))<1.2,"actual new-body arrival");
            h.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(prior,server);
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1200,batch="lifecycle-drops")
    public static void nativeKillDropsAndBedRespawn(GameTestHelper h){death(h,false);}
    @GameTest(template="empty",timeoutTicks=1200,batch="lifecycle-keep")
    public static void nativeKeepInventoryAndBedRespawn(GameTestHelper h){death(h,true);}
    @GameTest(template="empty",timeoutTicks=1200,batch="lifecycle-teleport")
    public static void nativeTeleportInvalidatesOldAuthority(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var server=b.body().server;var name=b.body().getGameProfile().getName();
        var start=h.absolutePos(new BlockPos(3,1,5));
        var nether=server.getLevel(net.minecraft.world.level.Level.NETHER);
        // Explicit isolated landing fixture. Native commands perform the tested relocation.
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)for(int y=69;y<74;y++)nether.setBlock(new BlockPos(x,y,z),y==69?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        BackendGateTests.move(h,b,"pre-teleport-work",new BlockPos(19,1,5));
        var oldIdentity=b.snapshot().identity();
        h.runAfterDelay(4,()->{
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"execute in minecraft:the_nether run tp "+name+" 3.5 70 3.5");
            h.assertTrue(b.body().serverLevel()==nether,"native command changes dimension");
            h.assertTrue(b.snapshot().identity().generation()==2,"nested teleport changes generation once");
            h.assertTrue(b.snapshot().state().equals("CANCELLED"),"pre-teleport work is cancelled");
            var destination=String.format(java.util.Locale.ROOT,"%.1f %d %.1f",start.getX()+.5,start.getY(),start.getZ()+.5);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"execute in minecraft:overworld run tp "+name+" "+destination);
            h.assertTrue(b.snapshot().identity().generation()==3,"returning is a new transaction");
            boolean refused=false;
            try {b.submit(oldIdentity,new CraftRequest("late-old-body-craft",h.getLevel().getGameTime()+100,Items.STICK,4),0);}
            catch(IllegalArgumentException expected) {refused=expected.getMessage().contains("STALE_EXECUTION_CONTEXT");}
            h.assertTrue(refused,"late old-generation request refused before side effects");
            BackendGateTests.move(h,b,"post-teleport-work",new BlockPos(18,1,5));
        });
        h.onEachTick(()->{
            if(b.snapshot().identity().generation()<3||!b.terminal())return;
            var snapshot=b.snapshot();var pos=b.body().position();b.close();
            h.assertTrue(snapshot.state().equals("SUCCESS"),"new work after native relocation succeeds: "+snapshot);
            h.assertTrue(pos.distanceTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(18,1,5))))<1.2,"actual arrival after dimension round trip");h.succeed();
        });
    }
}
