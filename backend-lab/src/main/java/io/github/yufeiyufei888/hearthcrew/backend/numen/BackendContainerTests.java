package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_containers")
@PrefixGameTestTemplate(false)
public class BackendContainerTests {
    @GameTest(template="empty",timeoutTicks=150,batch="container-public-protected")
    public static void publicUseDoesNotRemoveBuildingProtection(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(5,1,5));
        h.getLevel().setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());var chest=(ChestBlockEntity)h.getLevel().getBlockEntity(p);
        chest.setItem(0,new ItemStack(Items.COBBLESTONE,3));BackendLab.PROTECTED.add(p);
        var provider=new NumenBackendProvider();provider.publishFacility(h.getLevel(),p,true);
        h.runAfterDelay(6,()->{
            var before=chest.saveWithoutMetadata(h.getLevel().registryAccess());var facts=provider.inspectFacility(b.body(),p);
            h.assertTrue(facts.get("state").equals("observed")&&facts.containsKey("slots"),"public protected container can be inspected: "+facts);
            h.assertTrue(before.equals(chest.saveWithoutMetadata(h.getLevel().registryAccess()))&&b.body().containerMenu==b.body().inventoryMenu,"query is read-only, no menu activation");
            b.submit(new ContainerRequest("protected-public-take",h.getLevel().getGameTime()+100,p,ContainerRequest.Mode.WITHDRAW,Items.COBBLESTONE,3,null));
        });
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.COBBLESTONE)==3,"authorized use through real menu: "+b.snapshot());
            h.assertTrue(BackendProtection.isProtected(h.getLevel(),p),"publication must not remove building protection");
            provider.publishFacility(h.getLevel(),p,false);h.assertTrue(provider.inspectFacility(b.body(),p).get("state").equals("FACILITY_PRIVATE"),"revoke applies immediately");
            BackendLab.PROTECTED.remove(p);b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=200,batch="container-double-authority")
    public static void bothHalvesRequiredBeforeReadOrTransfer(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(5,1,5));var q=p.south();
        var facing=net.minecraft.core.Direction.WEST;
        var left=Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,facing).setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        // Derive the second coordinate from the actual native connection rule.
        q=p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(left));final var other=q;
        h.getLevel().setBlock(p,left,2);h.getLevel().setBlock(other,left.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
        var first=(ChestBlockEntity)h.getLevel().getBlockEntity(p);var second=(ChestBlockEntity)h.getLevel().getBlockEntity(other);
        first.setItem(0,new ItemStack(Items.DIAMOND,2));second.setItem(0,new ItemStack(Items.DIAMOND,4));
        var provider=new NumenBackendProvider();provider.publishFacility(h.getLevel(),p,true);int[] phase={0};
        h.runAfterDelay(6,()->{
            var facts=provider.inspectFacility(b.body(),p);h.assertTrue(facts.get("state").equals("FACILITY_PAIR_PRIVATE")&&!facts.containsKey("slots"),"private half not disclosed");
            b.submit(new ContainerRequest("one-half-denied",h.getLevel().getGameTime()+100,p,ContainerRequest.Mode.WITHDRAW,Items.DIAMOND,6,null));phase[0]=1;});
        h.onEachTick(()->{if(phase[0]==0||!b.terminal())return;
            if(phase[0]==1){h.assertTrue(b.snapshot().state().equals("FAILED")&&first.getItem(0).getCount()==2&&second.getItem(0).getCount()==4,"no private transfer");
                provider.publishFacility(h.getLevel(),other,true);var facts=provider.inspectFacility(b.body(),p);
                h.assertTrue(facts.get("state").equals("observed")&&((List<?>)facts.get("slots")).size()==54,"published double chest has native 54 slots: "+facts);
                b.submit(new ContainerRequest("both-halves-authorized",h.getLevel().getGameTime()+100,p,ContainerRequest.Mode.WITHDRAW,Items.DIAMOND,6,null));phase[0]=2;
            }else {h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.DIAMOND)==6,"both authorized halves transfer through native menu: "+b.snapshot());b.close();h.succeed();}
        });
    }
    @GameTest(template="empty",timeoutTicks=60,batch="container-read-only-loot")
    public static void inspectionDoesNotGenerateUnopenedLoot(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(5,1,5));h.getLevel().setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());
        var chest=(ChestBlockEntity)h.getLevel().getBlockEntity(p);chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON,42L);
        var provider=new NumenBackendProvider();provider.publishFacility(h.getLevel(),p,true);
        h.runAfterDelay(6,()->{var before=chest.saveWithoutMetadata(h.getLevel().registryAccess());var facts=provider.inspectFacility(b.body(),p);
            h.assertTrue(facts.get("state").equals("LOOT_NOT_GENERATED")&&!facts.containsKey("slots"),"unopened loot stays unknown");
            h.assertTrue(before.equals(chest.saveWithoutMetadata(h.getLevel().registryAccess()))&&b.body().containerMenu==b.body().inventoryMenu,"query cannot generate loot or activate menu");b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=1200,batch="container-real-smelting")
    public static void craftPlaceFeedAndCollectActualFurnaceOutput(GameTestHelper h) {
        // Explicit test intent, not a gameplay route: each submitted operation uses real inputs.
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,8));inv.setItem(10,new ItemStack(Items.RAW_IRON,3));inv.setItem(11,new ItemStack(Items.COAL));
        var table=h.absolutePos(new BlockPos(3,1,7));h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),table);
        var site=h.absolutePos(new BlockPos(5,1,5));int[] phase={0};boolean[] workedDuringHeating={false};
        h.runAfterDelay(6,()->{b.submit(new CraftRequest("craft-real-furnace",h.getLevel().getGameTime()+200,Items.FURNACE,1,table));phase[0]=1;});
        h.onEachTick(()->{
            if(phase[0]==0||!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"native chain step must succeed: "+b.snapshot());
            long deadline=h.getLevel().getGameTime()+200;
            switch(phase[0]) {
                case 1 -> {var target=new BuildTaskRecord.Target(Blocks.FURNACE,Items.FURNACE,site,"test furnace",null,null,null).asItemPlace();
                    b.submit(new BuildTaskRecord("place-real-furnace",deadline,List.of(target),false,true,false));phase[0]++;}
                case 2 -> {h.assertTrue(BackendJournal.get(b.body().server).isPublic(h.getLevel().dimension(),site),"own furnace is usable public facility");
                    b.submit(new ContainerRequest("feed-raw-iron",deadline,site,ContainerRequest.Mode.DEPOSIT,Items.RAW_IRON,3,0));phase[0]++;}
                case 3 -> {b.submit(new ContainerRequest("feed-real-fuel",deadline,site,ContainerRequest.Mode.DEPOSIT,Items.COAL,1,1));phase[0]++;}
                case 4 -> {h.assertTrue(inv.countItem(Items.IRON_INGOT)==0,"feeding is not completed smelting");
                    BackendGateTests.move(h,b,"independent-work-while-heating",new BlockPos(3,1,3));phase[0]++;}
                case 5 -> {
                    workedDuringHeating[0]=true;
                    var furnace=(AbstractFurnaceBlockEntity)h.getLevel().getBlockEntity(site);
                    if(!furnace.getItem(2).is(Items.IRON_INGOT)||furnace.getItem(2).getCount()!=3)return;
                    b.submit(new ContainerRequest("take-real-ingots",deadline,site,ContainerRequest.Mode.WITHDRAW,Items.IRON_INGOT,3,2));phase[0]++;
                }
                case 6 -> {
                    h.assertTrue(workedDuringHeating[0]&&inv.countItem(Items.IRON_INGOT)==3,"actual output and independent body work");
                    h.assertTrue(inv.countItem(Items.COBBLESTONE)==0&&inv.countItem(Items.RAW_IRON)==0&&inv.countItem(Items.COAL)==0,"all real inputs consumed");
                    h.assertTrue(((AbstractFurnaceBlockEntity)h.getLevel().getBlockEntity(site)).getItem(2).isEmpty(),"output removed through native menu");
                    b.close();h.succeed();
                }
            }
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="container-private")
    public static void privateContainerIsNotReadOrModified(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(5,1,5));
        h.getLevel().setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());var chest=(ChestBlockEntity)h.getLevel().getBlockEntity(p);
        chest.setItem(0,new ItemStack(Items.DIAMOND,3));
        h.runAfterDelay(6,()->b.submit(new ContainerRequest("private-chest",h.getLevel().getGameTime()+50,p,ContainerRequest.Mode.WITHDRAW,Items.DIAMOND,3,null)));
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&b.snapshot().result().contains("FACILITY_PRIVATE"),"private ownership denied before opening");
            h.assertTrue(chest.getItem(0).getCount()==3&&b.body().getInventory().countItem(Items.DIAMOND)==0&&b.body().containerMenu==b.body().inventoryMenu,"no private transfer or menu retained");
            b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=150,batch="container-capacity")
    public static void fullPublicContainerDoesNotClaimDeposit(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));
        var p=h.absolutePos(new BlockPos(5,1,5));h.getLevel().setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),p);
        var chest=(ChestBlockEntity)h.getLevel().getBlockEntity(p);for(int slot=0;slot<chest.getContainerSize();slot++)chest.setItem(slot,new ItemStack(Items.DIRT,64));
        h.runAfterDelay(6,()->b.submit(new ContainerRequest("full-chest-deposit",h.getLevel().getGameTime()+100,p,ContainerRequest.Mode.DEPOSIT,Items.COBBLESTONE,3,null)));
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&inv.countItem(Items.COBBLESTONE)==3,"full container cannot swallow or credit items");
            for(int slot=0;slot<chest.getContainerSize();slot++)h.assertTrue(chest.getItem(slot).is(Items.DIRT)&&chest.getItem(slot).getCount()==64,"no implicit swapping");
            b.close();h.succeed();});
    }
}
