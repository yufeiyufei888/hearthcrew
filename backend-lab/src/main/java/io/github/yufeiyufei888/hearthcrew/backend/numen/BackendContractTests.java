package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("hearthcrew_backend_contracts")
@PrefixGameTestTemplate(false)
public class BackendContractTests {
    @GameTest(template="empty",timeoutTicks=150,batch="contract-selected-materials")
    public static void selectedRecipeDoesNotConsumeAnotherStepsMaterials(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.OAK_PLANKS,64));inv.setItem(10,new ItemStack(Items.BIRCH_PLANKS,2));
        b.submit(new CraftRequest("selected-stick-materials",h.getLevel().getGameTime()+100,Items.STICK,4,null,
                "minecraft:stick",java.util.Map.of("minecraft:birch_planks",2)));
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&inv.countItem(Items.STICK)==4,"real selected recipe completes");
            h.assertTrue(inv.countItem(Items.BIRCH_PLANKS)==0&&inv.countItem(Items.OAK_PLANKS)==64,"allocated birch used; other-step oak untouched");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=150,batch="contract-wrong-recipe")
    public static void selectedRecipeCannotSilentlySwitchOutputs(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();inv.setItem(9,new ItemStack(Items.BIRCH_LOG,1));
        b.submit(new CraftRequest("wrong-selected-recipe",h.getLevel().getGameTime()+100,Items.BIRCH_PLANKS,4,null,
                "minecraft:oak_planks",java.util.Map.of("minecraft:birch_log",1)));
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("FAILED")&&inv.countItem(Items.BIRCH_LOG)==1&&inv.countItem(Items.BIRCH_PLANKS)==0,"wrong selection rejected without consuming or substituting");
            b.close();h.succeed();
        });
    }
    private static void craft(GameTestHelper h,NumenBackend b) {
        b.submit(new CraftRequest("native-stone-pickaxe",h.getLevel().getGameTime()+100,Items.STONE_PICKAXE,1));
    }
    @GameTest(template="empty",timeoutTicks=1600,batch="contract-elevated-craft")
    public static void knownStairsToHighTableMustCraft(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        // Six full-block stairs, with explicit headroom and a supported top platform.
        for(int x=7;x<=12;x++)for(int y=1;y<=x-6;y++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        for(int x=13;x<=17;x++)for(int z=4;z<=7;z++)h.setBlock(new BlockPos(x,6,z),Blocks.STONE);
        h.setBlock(new BlockPos(16,7,5),Blocks.CRAFTING_TABLE);
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),h.absolutePos(new BlockPos(16,7,5))); // explicit public fixture
        var destination=new BlockPos(14,7,5);
        BackendGateTests.move(h,b,"known-high-table",destination);
        final boolean[] crafting={false};
        h.onEachTick(()->{
            if(!b.terminal())return;
            if(!crafting[0]) {
                h.assertTrue(b.snapshot().state().equals("SUCCESS"),"known stairs must arrive: "+b.snapshot());
                h.assertTrue(b.body().position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(destination)))<1.2,"must actually reach high platform");
                crafting[0]=true;craft(h,b);return;
            }
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"craft must succeed: "+b.snapshot());
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==1&&inv.countItem(Items.COBBLESTONE)==8&&inv.countItem(Items.STICK)==3,"actual recipe consumes three cobblestone and two sticks");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=200,batch="contract-worn-tools")
    public static void wornToolsDoNotPreventRealReplacement(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        for(int slot=0;slot<2;slot++) {var pick=new ItemStack(Items.STONE_PICKAXE);pick.setDamageValue(pick.getMaxDamage()-2);inv.setItem(slot,pick);}
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        h.setBlock(new BlockPos(5,1,5),Blocks.CRAFTING_TABLE);
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),h.absolutePos(new BlockPos(5,1,5)));craft(h,b);
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"replacement craft must succeed: "+b.snapshot());
            int old=0,fresh=0;for(var item:inv.items)if(item.is(Items.STONE_PICKAXE)){if(item.getDamageValue()==0)fresh++;else if(item.getMaxDamage()-item.getDamageValue()==2)old++;}
            h.assertTrue(fresh==1&&old==2,"one real new pick, both worn picks preserved");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1500,batch="contract-down-four")
    public static void fourBlockDescentMustRecoverCoal(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        // Fixture placement only: spawn the body on a high platform before submitting work.
        for(int x=1;x<=5;x++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,4,z),Blocks.STONE);
        for(int x=6;x<=8;x++)for(int y=1;y<=9-x;y++)for(int z=4;z<=6;z++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        b.body().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3,5,5))));
        h.setBlock(new BlockPos(12,1,5),Blocks.COAL_ORE);
        b.submit(new MineBlockTaskRecord("four-block-descent",h.getLevel().getGameTime()+1300,Set.of(Blocks.COAL_ORE),1,"coal"));
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"known descent and recovery must succeed: "+b.snapshot());
            h.assertTrue(b.body().getInventory().countItem(Items.COAL)==1,"coal must actually enter inventory after descent");
            b.close();h.succeed();
        });
    }
    private static void protectedFurnace(GameTestHelper h,boolean nativePlacement) {
        var b=BackendGateTests.spawn(h);var relative=new BlockPos(5,1,5);var absolute=h.absolutePos(relative);
        b.body().getInventory().setItem(0,new ItemStack(Items.FURNACE));
        BackendLab.PROTECTED.add(absolute);BackendRuntime.blockedPlaces=0;
        var target=new BuildTaskRecord.Target(Blocks.FURNACE,Items.FURNACE,absolute,"furnace",null,null,null);
        if(nativePlacement)target=target.asItemPlace();
        b.submit(new BuildTaskRecord("protected-furnace",h.getLevel().getGameTime()+350,List.of(target),false,true,false));
        h.runAfterDelay(360,()->{
            boolean unchanged=h.getBlockState(relative).isAir();int remaining=b.body().getInventory().countItem(Items.FURNACE);int denied=BackendRuntime.blockedPlaces;
            System.out.println("BACKEND_CONTRACT_PROTECTION native="+nativePlacement+" unchanged="+unchanged+" remaining="+remaining+" deniedEvents="+denied+" "+b.snapshot());
            b.close();BackendLab.PROTECTED.remove(absolute);
            h.assertTrue(unchanged&&remaining==1,"protected construction must not change world or consume furnace");
            h.assertTrue(denied>0,"must exercise real place-event denial");h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=420,batch="contract-direct-placement")
    public static void exactStateConstructionMustRespectProtection(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var p=h.absolutePos(new BlockPos(5,1,5));
        b.body().getInventory().setItem(0,new ItemStack(Items.FURNACE));
        var target=new BuildTaskRecord.Target(Blocks.FURNACE,Items.FURNACE,p,"furnace",null,null,null);
        boolean rejected=false;
        try {b.submit(new BuildTaskRecord("unsupported-exact-build",h.getLevel().getGameTime()+100,List.of(target),false,true,false));}
        catch(IllegalArgumentException e) {rejected=e.getMessage().startsWith("UNSUPPORTED_EXACT_BUILD");}
        h.assertTrue(rejected,"unsafe exact-state building must be explicitly unavailable");
        h.assertTrue(h.getLevel().getBlockState(p).isAir()&&b.body().getInventory().countItem(Items.FURNACE)==1,"rejected task cannot modify inventory or world");
        b.close();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=420,batch="contract-native-placement")
    public static void itemConstructionMustRespectProtection(GameTestHelper h) {protectedFurnace(h,true);}
    @GameTest(template="empty",timeoutTicks=600,batch="contract-owned-facility")
    public static void nativePlacedTableBecomesUsableAndCrafts(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();var pos=h.absolutePos(new BlockPos(5,1,5));
        inv.setItem(0,new ItemStack(Items.CRAFTING_TABLE));inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        var target=new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE,Items.CRAFTING_TABLE,pos,"table",null,null,null).asItemPlace();
        b.submit(new BuildTaskRecord("place-own-table",h.getLevel().getGameTime()+400,List.of(target),false,true,false));
        boolean[] crafting={false};
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"native facility chain succeeds: "+b.snapshot());
            if(!crafting[0]) {
                h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE)&&inv.countItem(Items.CRAFTING_TABLE)==0,"real table placed and item consumed");
                h.assertTrue(BackendJournal.get(b.body().server).isPublic(h.getLevel().dimension(),pos),"verified own facility shared");
                crafting[0]=true;craft(h,b);return;
            }
            h.assertTrue(inv.countItem(Items.STONE_PICKAXE)==1&&inv.countItem(Items.COBBLESTONE)==8&&inv.countItem(Items.STICK)==3,"native crafting after actual table placement");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120,batch="contract-private-facility")
    public static void unpublishedTableCannotConsumeMaterials(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        h.setBlock(new BlockPos(5,1,5),Blocks.CRAFTING_TABLE);craft(h,b);
        h.onEachTick(()->{
            if(!b.terminal())return;
            var state=b.snapshot();int picks=inv.countItem(Items.STONE_PICKAXE),cobble=inv.countItem(Items.COBBLESTONE),sticks=inv.countItem(Items.STICK);b.close();
            h.assertTrue(state.state().equals("FAILED"),"unknown/private station must not be used");
            h.assertTrue(picks==0&&cobble==11&&sticks==5,"no private station craft or lost materials");h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=150,batch="contract-explicit-public-facility")
    public static void explicitPublicTableIsUsedEvenWithNearerPrivateTable(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,3));inv.setItem(10,new ItemStack(Items.STICK,2));
        h.setBlock(new BlockPos(4,1,4),Blocks.CRAFTING_TABLE);var table=h.absolutePos(new BlockPos(6,1,5));
        h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),table);
        h.runAfterDelay(6,()->b.submit(new CraftRequest("explicit-public-craft",h.getLevel().getGameTime()+80,Items.STONE_PICKAXE,1,table)));
        h.onEachTick(()->{
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&inv.countItem(Items.STONE_PICKAXE)==1,"selected public facility must be used");
            h.assertTrue(!BackendJournal.get(b.body().server).isPublic(h.getLevel().dimension(),h.absolutePos(new BlockPos(4,1,4))),"nearer private table remains private");
            b.close();h.succeed();
        });
    }
}
