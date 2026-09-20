package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.backend.CompanionBackend;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_orders")
@PrefixGameTestTemplate(false)
public class BackendOrderTests {
    @GameTest(template="empty",timeoutTicks=300,batch="order-world-roster")
    public static void freshRosterRejectsLegacyMarkersAndRestoresThreeNativeIdentities(GameTestHelper h){
        var seed=BackendGateTests.spawn(h);seed.close();var server=h.getLevel().getServer();
        var provider=new NumenBackendProvider();var data=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        var marker=data.resolve("hearthcrew_players.dat");boolean refused=false;
        try {
            h.assertTrue(!java.nio.file.Files.exists(marker),"isolated fixture must not overwrite existing save evidence");
            java.nio.file.Files.writeString(marker,"INJECTED_LEGACY_MARKER_FOR_NEGATIVE_GATE");
            try{io.github.yufeiyufei888.hearthcrew.backend.BackendRoster.open(server,provider);}catch(IllegalStateException expected){refused=expected.getMessage().contains("LEGACY_WORLD");}
            h.assertTrue(refused&&java.nio.file.Files.readString(marker).equals("INJECTED_LEGACY_MARKER_FOR_NEGATIVE_GATE"),"legacy marker refused and preserved");
            java.nio.file.Files.delete(marker); // only this test's verified marker in its isolated world
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        var roster=io.github.yufeiyufei888.hearthcrew.backend.BackendRoster.open(server,provider);var owner=UUID.randomUUID();var ids=new HashMap<String,UUID>();
        for(String name:io.github.yufeiyufei888.hearthcrew.backend.BackendRoster.NAMES){
            var b=roster.join(server,provider,name,owner,h.getLevel(),h.absoluteVec(new net.minecraft.world.phys.Vec3(4.5+ids.size()*2,1,5.5)));
            ids.put(name,b.body().getUUID());h.assertTrue(b.body().getInventory().isEmpty(),"new world empty inventory");
        }
        roster.body(ids.get("Ember")).body().getInventory().setItem(9,new ItemStack(Items.COBBLESTONE,7));
        roster.close(server);
        for(String name:io.github.yufeiyufei888.hearthcrew.backend.BackendRoster.NAMES){
            var b=roster.join(server,provider,name,owner,h.getLevel(),null);
            h.assertTrue(b.body().getUUID().equals(ids.get(name))&&server.getPlayerList().getPlayerByName(name)==b.body(),"stable identity and native lookup after rejoin");
            h.assertTrue(b.body().getInventory().countItem(Items.COBBLESTONE)==(name.equals("Ember")?7:0),"native inventory restored once, never copied between partners");
        }
        h.assertTrue(roster.members().size()==3&&roster.live().size()==3,"one body per persistent member");roster.close(server);h.succeed();
    }
    private static CompanionBackend.Request request(NumenBackend b,String id,BodyOrder order){return new CompanionBackend.Request(b.snapshot().identity(),id,order,ActionPriority.PERSONAL);}
    private static BodyOrder craft(String recipe,int runs,boolean prepare,BlockPos table){return new BodyOrder(BodyOrder.Kind.CRAFT,table,null,runs,ResourceLocation.parse(recipe),List.of(),List.of(),List.of(),32,16,prepare?BodyOrder.Preparation.standard():BodyOrder.Preparation.disabled());}
    @GameTest(template="empty",timeoutTicks=250,batch="order-craft-count")
    public static void recipeExecutionsRequireNewOutputAndReplayIsReadOnly(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();inv.setItem(9,new ItemStack(Items.BIRCH_LOG,2));inv.setItem(10,new ItemStack(Items.BIRCH_PLANKS,12));
        var r=request(b,"craft-two-times",craft("minecraft:birch_planks",2,false,null));b.dispatch(r);
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"recipe task must complete: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.BIRCH_PLANKS)==20&&inv.countItem(Items.BIRCH_LOG)==0,"two recipe executions produce eight NEW planks, not two items or old inventory");
            h.assertTrue(b.dispatch(r).reused(),"same request replay is read-only");
            boolean conflict=false;try{b.dispatch(request(b,"craft-two-times",craft("minecraft:birch_planks",1,false,null)));}catch(IllegalArgumentException expected){conflict=true;}
            h.assertTrue(conflict&&inv.countItem(Items.BIRCH_PLANKS)==20,"changed count cannot reuse identity");b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=100,batch="order-strict-fields")
    public static void unsupportedBlockCountCannotSilentlyChangeMeaning(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var pos=h.absolutePos(new BlockPos(5,0,5));
        int rejected=0;
        try{b.dispatch(request(b,"unadapted-block-count",BodyOrder.mine(pos)));}catch(IllegalArgumentException expected){rejected++;}
        h.assertTrue(rejected==1&&b.snapshot().actionId().isEmpty()&&h.getLevel().getBlockState(pos).is(Blocks.STONE),"unsupported semantics rejected before authority/world change");
        h.assertTrue(!b.capabilities().contains(BodyOrder.Kind.MINE),"unadapted block-count action not advertised");b.close();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=250,batch="order-limited-craft")
    public static void singleStepZeroBreakPreparationUsesAvailableMaterials(GameTestHelper h){
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(9,new ItemStack(Items.BIRCH_LOG,2));
        var order=new BodyOrder(BodyOrder.Kind.CRAFT,null,null,2,ResourceLocation.parse("minecraft:birch_planks"),List.of(),List.of(),List.of(),1,0,new BodyOrder.Preparation(true,1,1,0));
        b.dispatch(request(b,"single-step-existing-material",order));
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&b.body().getInventory().countItem(Items.BIRCH_PLANKS)==8,"bounded existing-material preparation must actually craft");
            h.assertTrue(((Number)b.execution().get("preparationBudget")).intValue()==0&&((Number)b.execution().get("preparationBroken")).intValue()==0,"caller budget preserved in execution evidence");
            b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=300,batch="order-limited-scope")
    public static void narrowerScopeDoesNotMineAResourceOutsideAuthorization(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var ore=new BlockPos(7,1,5);h.setBlock(ore,Blocks.COAL_ORE);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        var order=new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,null,null,1,ResourceLocation.parse("minecraft:coal"),List.of(),List.of(),List.of(),1,0,new BodyOrder.Preparation(true,2,3,0));
        b.dispatch(request(b,"restricted-resource-scope",order));
        h.assertTrue(!NumenBackend.pathPolicy(b.body()).permitsBreak(h.absolutePos(ore),Blocks.COAL_ORE.defaultBlockState()),"worker search obeys the same smaller scope before execution");
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&h.getBlockState(ore).is(Blocks.COAL_ORE)&&b.body().getInventory().countItem(Items.COAL)==0,"scope failure cannot expand to nearby unauthorized coal: "+b.snapshot());
            h.assertTrue(((Number)b.execution().get("preparationBroken")).intValue()==0&&((Number)b.execution().get("accessSpent")).intValue()==0,"zero shared preparation and access budgets respected");b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=1200,batch="order-prepare-craft")
    public static void exactRecipeOrderPreparesOwnTableAndPreservesWornPicks(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        var worn=new ItemStack(Items.STONE_PICKAXE);worn.setDamageValue(worn.getMaxDamage()-2);inv.setItem(0,worn);
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));inv.setItem(11,new ItemStack(Items.CRAFTING_TABLE));
        b.dispatch(request(b,"exact-pick-recipe",craft("minecraft:stone_pickaxe",1,true,null)));
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"public recipe order must finish: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==2&&inv.countItem(Items.COBBLESTONE)==8&&inv.countItem(Items.STICK)==3&&inv.countItem(Items.CRAFTING_TABLE)==0,"old pick retained and actual table/recipe consumed");
            h.assertTrue(b.body().getMainHandItem().is(Items.STONE_PICKAXE)&&b.body().getMainHandItem().getDamageValue()==0,"fresh tool selected");b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=250,batch="order-explicit-station")
    public static void invalidExplicitStationDoesNotFallBackToAnotherTable(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));inv.setItem(10,new ItemStack(Items.STICK,2));inv.setItem(11,new ItemStack(Items.CRAFTING_TABLE));
        b.dispatch(request(b,"invalid-specific-table",craft("minecraft:stone_pickaxe",1,true,h.absolutePos(new BlockPos(6,1,6)))));
        h.onEachTick(()->{if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&b.snapshot().result().contains("EXPLICIT_FACILITY"),"specific invalid facility must be reported");
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==0&&inv.countItem(Items.CRAFTING_TABLE)==1&&inv.countItem(Items.COBBLESTONE)==3,"no fallback crafting or placement");b.close();h.succeed();});
    }
}
