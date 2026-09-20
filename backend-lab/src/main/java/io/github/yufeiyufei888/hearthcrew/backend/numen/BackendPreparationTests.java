package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("hearthcrew_backend_preparation")
@PrefixGameTestTemplate(false)
public class BackendPreparationTests {
    private static void request(GameTestHelper h,NumenBackend b,String id) {
        b.submit(new PrepareRequest(id,h.getLevel().getGameTime()+1400,Items.STONE_PICKAXE,1,18),16);
    }
    @GameTest(template="empty",timeoutTicks=1500,batch="prepare-high-table")
    public static void flintAutomaticallyApproachesPublicTableAndCrafts(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        for(int x=7;x<=12;x++)for(int y=1;y<=x-6;y++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        for(int x=13;x<=17;x++)for(int z=4;z<=7;z++)h.setBlock(new BlockPos(x,6,z),Blocks.STONE);
        var table=h.absolutePos(new BlockPos(16,7,5));h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),table);
        request(h,b,"flint-auto-prepare");
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"goal-only preparation must reach the known high table and craft: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.COBBLESTONE)==8&&inv.countItem(Items.STICK)==3&&inv.countItem(Items.STONE_PICKAXE)==1,"actual recipe inventory");
            h.assertTrue(b.body().getMainHandItem().is(Items.STONE_PICKAXE)&&b.body().getY()>=table.getY()-.5,"actually at station, usable pick equipped");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1500,batch="prepare-worn-table")
    public static void mossBuildsOwnTableAndReplacesWornTools(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        for(int slot=0;slot<2;slot++){var pick=new ItemStack(Items.STONE_PICKAXE);pick.setDamageValue(pick.getMaxDamage()-2);inv.setItem(slot,pick);}
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));inv.setItem(11,new ItemStack(Items.CRAFTING_TABLE));
        request(h,b,"moss-auto-replace");
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"self placement and replacement must complete: "+b.snapshot());
            int worn=0,fresh=0;for(var stack:inv.items)if(stack.is(Items.STONE_PICKAXE)){if(stack.getDamageValue()==0)fresh++;else if(stack.getMaxDamage()-stack.getDamageValue()==2)worn++;}
            h.assertTrue(worn==2&&fresh==1&&inv.countItem(Items.CRAFTING_TABLE)==0,"old picks preserved, real table consumed, new pick made");
            h.assertTrue(b.body().getMainHandItem().is(Items.STONE_PICKAXE)&&b.body().getMainHandItem().getDamageValue()==0,"new usable tool selected");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1500,batch="prepare-raw-log")
    public static void recipeDependenciesIncludeMaterialProcessingAndWorkstation(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));inv.setItem(10,new ItemStack(Items.BIRCH_LOG,2));
        request(h,b,"real-recipe-dependencies");
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"whole dependency graph must allocate table, sticks and pick: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.COBBLESTONE)==0&&inv.countItem(Items.BIRCH_LOG)==0&&inv.countItem(Items.STONE_PICKAXE)==1,"actual consumed source materials and final product");
            h.assertTrue(BackendJournal.get(b.body().server).publicPositions(h.getLevel().dimension()).stream().anyMatch(p->h.getLevel().getBlockState(p).is(Blocks.CRAFTING_TABLE)),"physical table remains usable");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=800,batch="prepare-no-table-path")
    public static void isolatedHighTableDoesNotClaimCraftable(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        h.setBlock(new BlockPos(15,7,5),Blocks.STONE);h.setBlock(new BlockPos(15,8,5),Blocks.CRAFTING_TABLE);
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),h.absolutePos(new BlockPos(15,8,5)));
        request(h,b,"unreachable-public-table");
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&b.snapshot().result().contains("PREPARATION_UNKNOWN"),"inconclusive path cannot become success/no-path proof: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.COBBLESTONE)==11&&inv.countItem(Items.STICK)==5&&inv.countItem(Items.STONE_PICKAXE)==0,"no side effects before complete plan");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=2400,batch="prepare-acquire-stone")
    public static void missingCobblestoneIsActuallyMinedDuringPreparation(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(0,new ItemStack(Items.WOODEN_PICKAXE));inv.setItem(9,new ItemStack(Items.STICK,2));inv.setItem(10,new ItemStack(Items.CRAFTING_TABLE));
        for(int x=6;x<=10;x++)h.setBlock(new BlockPos(x,1,5),Blocks.STONE);
        b.submit(new PrepareRequest("mine-ingredients-before-craft",h.getLevel().getGameTime()+2200,Items.STONE_PICKAXE,1,18),16);
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"missing stone must be mined and used without scripted children: "+b.snapshot().state());
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==1&&inv.countItem(Items.WOODEN_PICKAXE)==1,"physical new pick and retained old tool");
            h.assertTrue(b.snapshot().result().contains("\"preparationBroken\":3"),"three preparation blocks recorded");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",templateNamespace="hearthcrew_backend_preparation_empty",timeoutTicks=3000,batch="prepare-empty-inventory")
    public static void emptyInventoryBuildsToolFromObservedNaturalSources(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        h.setBlock(new BlockPos(7,0,5),Blocks.DIRT);
        for(int y=1;y<=4;y++)h.setBlock(new BlockPos(7,y,5),Blocks.BIRCH_LOG);
        for(int x=6;x<=8;x++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,5,z),Blocks.BIRCH_LEAVES);
        for(int x=10;x<=14;x++)h.setBlock(new BlockPos(x,1,5),Blocks.STONE);
        b.submit(new PrepareRequest("empty-inventory-tool-goal",h.getLevel().getGameTime()+2800,Items.STONE_PICKAXE,1,18),16);
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"empty inventory preparation must form the actual tool chain: "+b.snapshot().state());
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==1&&inv.countItem(Items.WOODEN_PICKAXE)==1,"real basic tool chain from empty inventory");
            h.assertTrue(b.body().getMainHandItem().is(Items.STONE_PICKAXE),"usable final tool selected");
            h.assertTrue((int)b.diagnostics().get("maxSearchNodesPerBodyTick")<=64,"all body searches share the per-tick limit");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=2800,batch="prepare-then-collect-sixteen")
    public static void oneResourceRequestPreparesToolThenActuallyCollectsSixteen(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));inv.setItem(10,new ItemStack(Items.STICK,2));inv.setItem(11,new ItemStack(Items.CRAFTING_TABLE));
        inv.setItem(12,new ItemStack(Items.COAL,5)); // existing stock is not new task production
        for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,5),Blocks.COAL_ORE);
        b.submit(new CollectRequest("one-prepared-coal-task",h.getLevel().getGameTime()+2600,Items.COAL,16,java.util.Set.of(Blocks.COAL_ORE),true),16);
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"one resource goal must prepare and continue: "+b.snapshot().state());
            h.assertTrue(inv.countItem(Items.COAL)==21&&inv.countItem(Items.STONE_PICKAXE)==1,"sixteen new actual coal, initial five not counted");
            h.assertTrue(b.snapshot().result().contains("\"ownNew\":16"),"native goal output evidence");
            h.assertTrue((int)b.diagnostics().get("maxSearchNodesPerBodyTick")<=64,"combined search budget");
            b.close();h.succeed();
        });
    }
    private static void wornCollection(GameTestHelper h,boolean prepare) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        var pick=new ItemStack(Items.WOODEN_PICKAXE);pick.setDamageValue(pick.getMaxDamage()-18);inv.setItem(0,pick);
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));inv.setItem(10,new ItemStack(Items.STICK,2));inv.setItem(11,new ItemStack(Items.CRAFTING_TABLE));
        for(int x=6;x<16;x++)for(int z=5;z<=6;z++)h.setBlock(new BlockPos(x,1,z),Blocks.COAL_ORE);
        b.submit(new CollectRequest("naturally-worn-tool-"+prepare,h.getLevel().getGameTime()+3000,Items.COAL,20,java.util.Set.of(Blocks.COAL_ORE),prepare),16);
        h.onEachTick(()->{
            if(!b.terminal())return;
            if(prepare) {
                h.assertTrue(b.snapshot().state().equals("SUCCESS"),"natural tool wear must replan within the same request: "+b.snapshot().state());
                h.assertTrue(inv.countItem(Items.COAL)==20&&inv.countItem(Items.STONE_PICKAXE)==1,"actual target output after replacement");
                h.assertTrue(b.snapshot().result().contains("\"planRevision\":1"),"one bounded recovery, original identity kept");
            } else {
                h.assertTrue(b.snapshot().state().equals("FAILED"),"explicit preparation refusal respected");
                h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==0&&inv.countItem(Items.COBBLESTONE)==3&&inv.countItem(Items.CRAFTING_TABLE)==1,"no unapproved preparation effects");
                h.assertTrue(inv.countItem(Items.COAL)>0&&inv.countItem(Items.COAL)<20,"partial output retained without success claim");
            }
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=3200,batch="prepare-natural-tool-break")
    public static void naturallyBrokenToolIsPreparedAndCollectionContinues(GameTestHelper h){wornCollection(h,true);}
    @GameTest(template="empty",timeoutTicks=3200,batch="prepare-explicitly-disabled")
    public static void preparationCannotOverrideExplicitDisable(GameTestHelper h){wornCollection(h,false);}
}
