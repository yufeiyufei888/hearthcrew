package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.backend.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Host boundary fixtures: no client, controller process or model. Inputs are explicitly injected. */
@GameTestHolder("hearthcrew_backend_host")
@PrefixGameTestTemplate(false)
public class BackendHostTests {
    @GameTest(template="empty",timeoutTicks=700,batch="host-wire")
    public static void loopbackConnectionDeliversTerminalAndContinuesNextTask(GameTestHelper h)throws Exception{
        h.onEachTick(()->io.github.yufeiyufei888.hearthcrew.backend.numen.BackendTestClock.pace(h.getLevel().getGameTime()));
        var server=h.getLevel().getServer();var roster=BackendWorld.roster(server);
        for(int x=0;x<24;x++)for(int z=0;z<16;z++)for(int y=0;y<5;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);
        var ownerId=roster.members().isEmpty()?UUID.randomUUID():roster.members().getFirst().owner();
        var owner=new net.minecraft.server.level.ServerPlayer(server,h.getLevel(),new com.mojang.authlib.GameProfile(ownerId,"WireFixtureOwner"),net.minecraft.server.level.ClientInformation.createDefault());
        var origin=h.absolutePos(new BlockPos(12,1,8));owner.moveTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);BackendWorld.join(owner);
        var b=roster.live().stream().filter(v->v.body().getGameProfile().getName().equals("Moss")).findFirst().orElseThrow();
        BackendWorld.control(server,b.body().getUUID(),"resume");
        // Explicit fixture input, never a production preparation or a model achievement.
        b.body().getInventory().clearContent();b.body().getInventory().setItem(9,new ItemStack(Items.BIRCH_LOG,2));
        var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));listener.setSoTimeout(10000);
        String token=UUID.randomUUID().toString()+UUID.randomUUID();var pairing=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("fixture-pairing-"+UUID.randomUUID()+".json");
        java.nio.file.Files.writeString(pairing,new Gson().toJson(Map.of("port",listener.getLocalPort(),"token",token)));
        var link=new ModLink(server,pairing);var done=new java.util.concurrent.atomic.AtomicBoolean();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var botId=b.body().getUUID().toString();long generation=b.snapshot().identity().generation();
        var peer=new Thread(()->{
            try(var socket=listener.accept()){
                socket.setSoTimeout(10000);var in=new java.io.DataInputStream(socket.getInputStream());var out=new java.io.DataOutputStream(socket.getOutputStream());var hello=read(in);
                if(hello.getAsJsonObject("capabilities").get("executionProtocol").getAsInt()!=6||!hello.getAsJsonObject("backend").get("id").getAsString().equals("numen"))throw new AssertionError("actual handshake missing protocol/backend");
                if(!hello.get("token").getAsString().equals(token))throw new AssertionError("pairing token mismatch");
                var ack=base(hello,"handshake_ack");ack.addProperty("requestId","mod-hello");ack.addProperty("accepted",true);write(out,ack);
                var body=new JsonObject();body.addProperty("botId",botId);body.addProperty("origin","autonomous");body.addProperty("intentGeneration",2);
                var bind=base(hello,"request");bind.addProperty("requestId","wire-bind");bind.addProperty("op","intent.bind");bind.addProperty("intentId","wire-preparation");bind.addProperty("bodyGeneration",generation);bind.add("body",body);
                write(out,bind);response(in,out,hello,"wire-bind");
                for(int step=0;step<2;step++){
                    var action=bind.deepCopy();action.addProperty("op","action.submit");action.addProperty("requestId","wire-submit-"+step);String actionId="wire-preparation:"+step;action.addProperty("actionId",actionId);
                    var args=body.deepCopy();args.addProperty("kind","CRAFT");args.addProperty("resource",step==0?"minecraft:birch_planks":"minecraft:stick");args.addProperty("count",step==0?2:1);action.add("body",args);write(out,action);
                    boolean terminal=false,accepted=false;
                    while(!terminal||!accepted){var message=read(in);String kind=message.get("kind").getAsString();
                        if(kind.equals("event")){acknowledge(out,hello,message);if(message.get("event").getAsString().equals("action.terminal")&&message.getAsJsonObject("body").getAsJsonObject("id").get("value").getAsString().equals(actionId)){
                            if(!message.getAsJsonObject("body").get("state").getAsString().equals("COMPLETED"))throw new AssertionError("wire task failed: "+message.getAsJsonObject("body").get("message"));terminal=true;}}
                        else if(kind.equals("response")&&message.get("requestId").getAsString().equals("wire-submit-"+step)){if(!message.get("ok").getAsBoolean())throw new AssertionError("wire submission rejected: "+message.get("error"));accepted=true;}
                    }
                }
                done.set(true);
            }catch(Throwable e){failure.set(e);}finally{try{listener.close();}catch(Exception ignored){}}
        },"headless-fixture-peer-NO-MODEL");peer.setDaemon(true);peer.start();link.start();var finished=new java.util.concurrent.atomic.AtomicBoolean();
        h.onEachTick(()->{
            if(finished.get())return;link.tick();
            if(failure.get()!=null){finished.set(true);link.close();roster.close(server);h.fail("Loopback protocol failed: "+failure.get());return;}
            if(done.get()){
                finished.set(true);link.close();h.assertTrue(b.body().getInventory().countItem(Items.BIRCH_PLANKS)==6&&b.body().getInventory().countItem(Items.STICK)==4,"two actual connected tasks continue from prior real output");
                roster.close(server);System.out.println("BACKEND_HOST_WIRE_GATE protocol=6 nativeTasks=2 terminalEvents=2 finalPlanks=6 finalSticks=4 realModels=0");h.succeed();
            }
        });
    }
    private static JsonObject base(JsonObject hello,String kind){var o=new JsonObject();o.addProperty("kind",kind);o.add("protocol",hello.get("protocol"));o.add("worldId",hello.get("worldId"));o.add("sessionEpoch",hello.get("sessionEpoch"));return o;}
    private static JsonObject read(java.io.DataInputStream in)throws java.io.IOException{int size=in.readInt();if(size<1||size>1048576)throw new java.io.IOException("invalid fixture frame");return JsonParser.parseString(new String(in.readNBytes(size),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void write(java.io.DataOutputStream out,JsonObject body)throws java.io.IOException{byte[] data=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);out.flush();}
    private static void acknowledge(java.io.DataOutputStream out,JsonObject hello,JsonObject message)throws java.io.IOException{var ack=base(hello,"ack");ack.add("eventId",message.get("eventId"));ack.addProperty("accepted",true);write(out,ack);}
    private static JsonObject response(java.io.DataInputStream in,java.io.DataOutputStream out,JsonObject hello,String id)throws java.io.IOException{while(true){var message=read(in);if(message.get("kind").getAsString().equals("event")){acknowledge(out,hello,message);continue;}if(message.get("kind").getAsString().equals("response")&&message.get("requestId").getAsString().equals(id)){if(!message.get("ok").getAsBoolean())throw new AssertionError("request rejected: "+message.get("error"));return message;}}}
    @GameTest(template="empty",timeoutTicks=500,batch="host-protocol")
    public static void realHostBindingsResultsAndHistoryDoNotReplay(GameTestHelper h){
        h.onEachTick(()->io.github.yufeiyufei888.hearthcrew.backend.numen.BackendTestClock.pace(h.getLevel().getGameTime()));
        var server=h.getLevel().getServer();h.assertTrue(BackendWorld.enabled()&&BackendWorld.fault(server).isEmpty(),"host provider and new world must initialize: "+BackendWorld.fault(server));
        for(int x=0;x<24;x++)for(int z=0;z<16;z++)for(int y=0;y<5;y++)h.setBlock(new BlockPos(x,y,z),y==0?Blocks.STONE:Blocks.AIR);
        // This boundary fixture needs an owner identity, not an unnegotiated fake network client.
        var owner=new net.minecraft.server.level.ServerPlayer(server,h.getLevel(),new com.mojang.authlib.GameProfile(BackendWorld.roster(server).members().isEmpty()?UUID.randomUUID():BackendWorld.roster(server).members().getFirst().owner(),"FixtureOwner"),net.minecraft.server.level.ClientInformation.createDefault());var origin=h.absolutePos(new BlockPos(12,1,8));owner.moveTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);
        h.assertTrue(BackendWorld.join(owner)==3,"three actual native bodies joined by host entry");
        var roster=BackendWorld.roster(server);var ember=roster.live().stream().filter(b->b.body().getGameProfile().getName().equals("Ember")).findFirst().orElseThrow();
        h.assertTrue(server.getPlayerList().getPlayerByName("Ember")==ember.body(),"original player-name lookup");
        var stream=new BackendEventStream(server,"fixture-events");var emitted=new ArrayList<String>();
        BackendEventStream.Sink sink=(id,type,sequence,body)->{emitted.add(type+":"+id);return true;};
        h.assertTrue(stream.tick(sink)&&emitted.stream().filter(e->e.startsWith("body.available")).count()==3,"one availability event per current incarnation");
        var link=new BackendLinkOperations(server,()->7L);var payload=new JsonObject();payload.addProperty("botId",ember.body().getUUID().toString());payload.addProperty("origin","autonomous");payload.addProperty("intentGeneration",1);
        var request=new JsonObject();request.addProperty("intentId","fixture-craft");request.addProperty("bodyGeneration",ember.snapshot().identity().generation());
        var facility=ember.body().blockPosition().east(2);h.getLevel().setBlockAndUpdate(facility,Blocks.CHEST.defaultBlockState());
        var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(facility);chest.setItem(0,new ItemStack(Items.COAL,3));
        CrewWorldData.get(server).markPlacement(h.getLevel(),facility,owner.getUUID(),false);
        var query=payload.deepCopy();query.add("containers",new Gson().toJsonTree(List.of(Map.of("x",facility.getX(),"y",facility.getY(),"z",facility.getZ()))));
        var hidden=link.handle("observe",request,query).getAsJsonObject().getAsJsonArray("containers").get(0).getAsJsonObject();
        h.assertTrue(hidden.get("state").getAsString().equals("FACILITY_PRIVATE")&&!hidden.has("slots"),"host observation preserves private inventory");
        BackendWorld.provider().publishFacility(h.getLevel(),facility,true);
        var visible=link.handle("observe",request,query).getAsJsonObject().getAsJsonArray("containers").get(0).getAsJsonObject();
        h.assertTrue(visible.get("state").getAsString().equals("observed")&&visible.getAsJsonArray("slots").get(0).getAsJsonObject().get("count").getAsInt()==3,"public inventory reaches protocol response: "+visible);
        h.assertTrue(CrewWorldData.get(server).playerBlock(h.getLevel(),facility)&&chest.getItem(0).getCount()==3,"query preserves inventory and building protection");
        link.handle("intent.bind",request,payload);
        ember.body().getInventory().setItem(9,new ItemStack(Items.BIRCH_LOG,2));
        payload.addProperty("kind","CRAFT");payload.addProperty("resource","minecraft:birch_planks");payload.addProperty("count",2);request.addProperty("actionId","fixture-craft:one");
        var accepted=link.handle("action.submit",request,payload).getAsJsonObject();
        h.assertTrue(Set.of("ACCEPTED","RUNNING").contains(accepted.get("state").getAsString()),"host submits real task: "+accepted);
        // Backpressure cannot consume an event. It must be offered again with the same identity.
        var rejected=new ArrayList<String>();stream.tick((id,type,sequence,body)->{if(type.startsWith("action.")){rejected.add(id);return false;}return true;});
        stream.tick((id,type,sequence,body)->{if(type.startsWith("action."))h.assertTrue(rejected.contains(id),"retained registration retry preserves identity");return sink.accept(id,type,sequence,body);});
        h.onEachTick(()->{
            var ledger=BackendActions.get(server);ledger.reconcile(server,ember);stream.tick(sink);
            var current=ledger.find(ember.body().getUUID(),"fixture-craft:one").orElseThrow();if(!Set.of("COMPLETED","FAILED").contains(current.state()))return;
            h.assertTrue(current.state().equals("COMPLETED"),"real host crafting must succeed: "+current);
            h.assertTrue(ember.body().getInventory().countItem(Items.BIRCH_PLANKS)==8&&ember.body().getInventory().countItem(Items.BIRCH_LOG)==0,"real inputs and eight outputs");
            var retry=link.handle("action.submit",request,payload).getAsJsonObject();h.assertTrue(retry.get("state").getAsString().equals("COMPLETED"),"late retry cannot revive terminal");
            h.assertTrue(ember.body().getInventory().countItem(Items.BIRCH_PLANKS)==8,"duplicate does not craft again");
            var snapshot=link.snapshot();h.assertTrue(snapshot.getAsJsonArray("companions").size()==3&&snapshot.get("executionProtocol").getAsInt()==6,"UI/controller share three-body protocol6 snapshot");
            h.assertTrue(snapshot.getAsJsonArray("companions").asList().stream().allMatch(b->b.getAsJsonObject().get("action").isJsonNull()),"historical terminal is not active execution");
            var history=link.handle("action.history",request,payload).getAsJsonObject();h.assertTrue(history.getAsJsonArray("entries").size()==1&&!history.get("truncated").getAsBoolean(),"read-only history contains one task identity");
            var reconnected=new BackendEventStream(server,"fixture-reconnect");var replayed=new ArrayList<String>();reconnected.tick((id,type,seq,body)->{replayed.add(type);return true;});h.assertTrue(replayed.stream().noneMatch(t->t.startsWith("action.")),"reconnect does not replay saved terminals as new activity");
            BackendWorld.control(server,ember.body().getUUID(),"pause");boolean refused=false;try{link.handle("action.submit",request,payload);}catch(RuntimeException expected){refused=true;}h.assertTrue(refused,"old binding cannot write after owner pause");
            var stable=ember.body().getUUID();roster.close(server);BackendWorld.join(owner);h.assertTrue(roster.control(stable).paused()&&roster.body(stable).body().getInventory().countItem(Items.BIRCH_PLANKS)==8,"pause and native inventory survive rejoin");
            System.out.println("BACKEND_HOST_GATE nativePlayers=3 craftedPlanks=8 terminalReplay=0 oldHistoryEvents=0 pauseRestored=true");
            roster.close(server);h.succeed();
        });
    }
}
