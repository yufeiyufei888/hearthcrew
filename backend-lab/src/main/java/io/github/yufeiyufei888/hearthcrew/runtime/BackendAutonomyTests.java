package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.backend.*;
import io.github.yufeiyufei888.hearthcrew.backend.numen.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Explicit controlled real-Luna gate. The fixture sets terrain, never a companion plan or inventory. */
@GameTestHolder("hearthcrew_backend_autonomy")
@PrefixGameTestTemplate(false)
public class BackendAutonomyTests {
    @GameTest(template="empty",timeoutTicks=18400,batch="controlled-autonomy")
    public static void emptyInventoryMustProduceAndContinue(GameTestHelper h)throws Exception {
        var control=Path.of(System.getProperty("hearthcrew.lunaControlDirectory",""));
        if(!Boolean.getBoolean("hearthcrew.realLunaApproved")||!Files.isRegularFile(control.resolve("authorization.json")))throw new IllegalStateException("Explicit bounded Luna gate runner required");
        var authorization=JsonParser.parseString(Files.readString(control.resolve("authorization.json"))).getAsJsonObject();
        int count=authorization.get("companions").getAsInt();
        if((count!=1&&count!=3)||authorization.get("maximumSeconds").getAsInt()>900)throw new IllegalArgumentException("Invalid Luna gate limits");
        var level=h.getLevel();var server=level.getServer();server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,true);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(6000);
        // Known, disclosed test landscape: 64x64 grass, eight stone layers, rooted birches,
        // exposed coal/iron and stone. No chest, workbench, tool, food or inventory injection.
        for(int x=0;x<64;x++)for(int z=0;z<64;z++)for(int y=0;y<17;y++)
            h.setBlock(new BlockPos(x,y,z),y<8?Blocks.STONE:y==8?Blocks.GRASS_BLOCK:Blocks.AIR);
        for(int x:new int[]{14,23,41,50})for(int z:new int[]{14,24,42,50}){
            for(int y=9;y<=12;y++)h.setBlock(new BlockPos(x,y,z),Blocks.BIRCH_LOG);
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)h.setBlock(new BlockPos(x+dx,13,z+dz),Blocks.BIRCH_LEAVES);
        }
        for(int x=28;x<36;x++)for(int z=18;z<21;z++)h.setBlock(new BlockPos(x,9,z),Blocks.COAL_ORE);
        for(int x=28;x<36;x++)for(int z=45;z<48;z++)h.setBlock(new BlockPos(x,9,z),Blocks.IRON_ORE);
        for(int x=16;x<20;x++)for(int z=30;z<36;z++)h.setBlock(new BlockPos(x,9,z),Blocks.STONE);
        var origin=h.absolutePos(new BlockPos(32,9,32));
        var owner=BackendFixtureOwner.join(level,net.minecraft.world.phys.Vec3.atBottomCenterOf(origin));
        BackendWorld.join(owner,count==1?"Ember":null);
        var bodies=BackendWorld.roster(server).live();h.assertTrue(bodies.size()==count,"exact real companion count");
        for(var b:bodies){h.assertTrue(b.body().getInventory().isEmpty(),"fresh empty inventory, no old state");BackendWorld.control(server,b.body().getUUID(),"resume");BackendWorld.control(server,b.body().getUUID(),"auto_on");}
        write(control.resolve("fixture-ready.json"),Map.of("fixture","controlled-peaceful-resource-landscape","companions",count,"emptyInventories",true,"gameTick",level.getGameTime()));
        var start=level.getGameTime();var stopped=new boolean[1];
        h.onEachTick(()->{
            BackendTestClock.pace(level.getGameTime());if(stopped[0]||level.getGameTime()%100!=0)return;
            var rows=new ArrayList<Map<String,Object>>();boolean all=true;
            for(var b:bodies){
                var inv=b.body().getInventory();var history=BackendActions.get(server).page(b.body().getUUID(),Long.MAX_VALUE,64);
                var completed=history.stream().filter(e->e.state().equals("COMPLETED")).toList();
                boolean collected=completed.stream().anyMatch(e->e.order().contains("COLLECT_RESOURCE")&&e.evidence().contains("\"ownNew\":")&&!e.evidence().contains("\"ownNew\":0"));
                boolean crafted=completed.stream().anyMatch(e->e.order().contains("CRAFT")||verifiedPreparationCraft(e.evidence()));
                boolean usableTool=inv.items.stream().anyMatch(s->s.getItem() instanceof PickaxeItem&&(!s.isDamageableItem()||s.getMaxDamage()-s.getDamageValue()>2));
                long facilities=BackendTestFacts.usablePublicFacilities(level);
                // Single-person gate checks a full preparation chain. The approved crew
                // gate checks each person's contribution and continued execution, not a
                // prescribed identical profession/tool for all three independent planners.
                boolean contribution=collected||completed.stream().anyMatch(e->e.order().contains("BUILD")||e.order().contains("CRAFT")||e.order().contains("COLLECT_PROCESS"));
                boolean pass=count==1?collected&&crafted&&usableTool&&facilities>0&&completed.size()>=3
                    :contribution&&facilities>0&&completed.size()>=2;
                all&=pass;
                var row=new LinkedHashMap<String,Object>();row.put("name",b.body().getGameProfile().getName());row.put("passed",pass);row.put("collected",collected);row.put("crafted",crafted);row.put("usableTool",usableTool);row.put("facilities",facilities);row.put("completedActions",completed.size());
                row.put("position",List.of(b.body().getX(),b.body().getY(),b.body().getZ()));row.put("state",b.snapshot().state());row.put("inventory",inv.items.stream().filter(s->!s.isEmpty()).map(s->Map.of("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount(),"damage",s.getDamageValue())).toList());
                row.put("actions",history);rows.add(row);
            }
            write(control.resolve("physical-evidence.json"),Map.of("tick",level.getGameTime(),"elapsedTicks",level.getGameTime()-start,"companions",rows));
            if(all&&level.getGameTime()-start>=1200){
                stopped[0]=true;write(control.resolve("physical-result.json"),Map.of("passed",true,"companions",rows));
                for(var b:bodies)BackendWorld.control(server,b.body().getUUID(),"pause");
                BackendFixtureOwner.close(owner);h.succeed();
            }else if(Files.exists(control.resolve("abort.request"))){
                stopped[0]=true;write(control.resolve("physical-result.json"),Map.of("passed",false,"reason","runner ended bounded trial; original evidence retained","companions",rows));
                for(var b:bodies)BackendWorld.control(server,b.body().getUUID(),"pause");BackendFixtureOwner.close(owner);h.fail("Bounded Luna gate did not meet production conditions");
            }
        });
    }
    private static void write(Path target,Object value){try{Files.writeString(target,new Gson().toJson(value));}catch(java.io.IOException e){throw new IllegalStateException("Luna gate evidence write failed",e);}}
    private static boolean verifiedPreparationCraft(String evidence){
        try{var root=JsonParser.parseString(evidence).getAsJsonObject();var data=root.getAsJsonObject("data");if(data==null||!data.has("receipts"))return false;
            for(var raw:data.getAsJsonArray("receipts")){var r=raw.getAsJsonObject();if(r.has("method")&&r.get("method").getAsString().startsWith("craft:")&&r.has("verified")&&r.get("verified").getAsBoolean())return true;}
        }catch(RuntimeException ignored){}return false;
    }
}
