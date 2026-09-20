package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.backend.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Same physical scenarios/orders for separate legacy/new worlds, never simultaneous executors. */
@GameTestHolder("hearthcrew_backend_comparison")
@PrefixGameTestTemplate(false)
public class BackendComparisonTests {
    private record Fixture(CompanionBackend backend,ServerPlayer owner) {void close(){backend.close();BackendFixtureOwner.close(owner);}}
    private static Fixture setup(GameTestHelper h){
        h.onEachTick(()->io.github.yufeiyufei888.hearthcrew.backend.numen.BackendTestClock.pace(h.getLevel().getGameTime()));
        h.getLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,true);
        for(int x=0;x<24;x++)for(int z=0;z<16;z++)for(int y=0;y<7;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);
        var start=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3,1,5)));
        var owner=BackendFixtureOwner.join(h.getLevel(),start.add(0,5,0));CompanionBackend backend;
        if(BackendWorld.enabled()){
            var provider=BackendWorld.provider();backend=provider.create(h.getLevel(),UUID.randomUUID(),"CompareBot",owner.getUUID(),start,false);
        }else{
            var body=CrewPlayers.create(h.getLevel(),"CompareBot",null,start);body.setRespawnEnabled(false);
            backend=new LegacyBackend(body,CrewWorldData.get(h.getLevel().getServer()).worldId().toString());
        }
        h.assertTrue(backend.body().getInventory().isEmpty(),"separate world starts empty, no old inventory imported");return new Fixture(backend,owner);
    }
    private static boolean terminal(CompanionBackend b){return Set.of("SUCCESS","COMPLETED","FAILED","CANCELLED","TIMEOUT","EXPIRED","PARTIAL","RECONCILE_REQUIRED").contains(b.snapshot().state());}
    private static void run(GameTestHelper h,Fixture f,String scenario,BodyOrder order,java.util.function.BooleanSupplier actual,int maxTicks){
        var b=f.backend();long[] started={-1},wallStart={0};boolean[] finished={false};String[] submission={null};var origin=b.body().position();
        h.runAfterDelay(8,()->{started[0]=h.getLevel().getGameTime();wallStart[0]=System.nanoTime();try{b.dispatch(new CompanionBackend.Request(b.snapshot().identity(),"comparison-"+scenario,order,ActionPriority.OWNER));}catch(RuntimeException e){submission[0]=e.getClass().getSimpleName()+":"+e.getMessage();}});
        h.onEachTick(()->{if(finished[0]||started[0]<0)return;long elapsed=h.getLevel().getGameTime()-started[0];if(submission[0]==null&&!terminal(b)&&elapsed<maxTicks)return;finished[0]=true;
            boolean passed=submission[0]==null&&Set.of("SUCCESS","COMPLETED").contains(b.snapshot().state())&&actual.getAsBoolean();
            var result=new LinkedHashMap<String,Object>();result.put("scenario",scenario);result.put("backend",b.backendId());result.put("passed",passed);result.put("ticks",elapsed);result.put("wallMillis",(System.nanoTime()-wallStart[0])/1_000_000);result.put("state",b.snapshot().state());result.put("submissionError",submission[0]);result.put("result",b.snapshot().result());result.put("displacement",b.body().position().distanceTo(origin));
            result.put("inventory",b.body().getInventory().items.stream().filter(s->!s.isEmpty()).map(s->Map.of("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount(),"damage",s.getDamageValue())).toList());result.put("execution",b.execution());result.put("diagnostics",b.diagnostics());
            System.out.println("BACKEND_COMPARISON "+new com.google.gson.Gson().toJson(result));f.close();
            if(BackendWorld.enabled())h.assertTrue(passed,"new backend did not meet known-success comparison: "+scenario);
            // A legacy failure is a retained baseline result, not a claimed gameplay success.
            h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1300,batch="comparison-100m")
    public static void hundredBlockRoute(GameTestHelper h){
        var f=setup(h);var b=f.backend();var origin=b.body().blockPosition();var target=origin.east(100);
        for(int x=-1;x<=102;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++)h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        run(h,f,"hundred-block-route",BodyOrder.move(target),()->b.body().position().distanceTo(Vec3.atBottomCenterOf(target))<1.2&&b.body().onGround(),1100);
    }
    @GameTest(template="empty",timeoutTicks=2000,batch="comparison-coal")
    public static void closedNearestCoalAndSixteenAccessible(GameTestHelper h){
        var f=setup(h);var b=f.backend();b.body().getInventory().setItem(0,new ItemStack(Items.STONE_PICKAXE));
        var closed=new BlockPos(5,1,8);h.setBlock(closed,Blocks.COAL_ORE);for(var d:Direction.values())h.setBlock(closed.relative(d),Blocks.BEDROCK);
        for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,5),Blocks.COAL_ORE);
        var order=new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,null,null,16,ResourceLocation.parse("minecraft:coal"),List.of(),List.of(),List.of(),32,0,BodyOrder.Preparation.disabled());
        run(h,f,"closed-nearest-sixteen-coal",order,()->b.body().getInventory().countItem(Items.COAL)==16&&h.getBlockState(closed).is(Blocks.COAL_ORE),1800);
    }
    @GameTest(template="empty",timeoutTicks=1600,batch="comparison-residual-tool")
    public static void residualPickaxesDoNotBlockRealPreparation(GameTestHelper h){
        var f=setup(h);var b=f.backend();var inv=b.body().getInventory();
        for(int slot=0;slot<2;slot++){var pick=new ItemStack(Items.STONE_PICKAXE);pick.setDamageValue(pick.getMaxDamage()-2);inv.setItem(slot,pick);}
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        var table=h.absolutePos(new BlockPos(3,1,7));h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        if(BackendWorld.enabled())BackendWorld.provider().publishFacility(h.getLevel(),table,true);
        for(int x=6;x<22;x++)h.setBlock(new BlockPos(x,1,5),Blocks.COAL_ORE);
        var order=new BodyOrder(BodyOrder.Kind.COLLECT_RESOURCE,null,null,16,ResourceLocation.parse("minecraft:coal"),List.of(),List.of(),List.of(),32,0,BodyOrder.Preparation.standard());
        run(h,f,"residual-tool-and-materials",order,()->inv.countItem(Items.COAL)==16&&inv.items.stream().filter(s->s.is(Items.STONE_PICKAXE)&&s.getMaxDamage()-s.getDamageValue()==2).count()==2&&inv.countItem(Items.COBBLESTONE)==8&&inv.countItem(Items.STICK)==3&&inv.items.stream().anyMatch(s->s.is(Items.STONE_PICKAXE)&&s.getMaxDamage()-s.getDamageValue()>18),1400);
    }
}
