package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class NativePlayerGameTests {
 @GameTest(template="p0_empty",timeoutTicks=100)
 public static void nativeCommandsAndMovement(GameTestHelper h){
  for(int x=0;x<8;x++)for(int z=0;z<8;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<4;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,1));
  h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayerByName(p.getGameProfile().getName())==p,"native name registration");
  h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack(),"give "+p.getGameProfile().getName()+" minecraft:iron_pickaxe 1");
  h.assertTrue(p.inventory().countItem(Items.IRON_PICKAXE)==1,"native give reaches inventory");
  // A client-only mod can send an unnegotiated payload during login. There is
  // no remote client for this player, so it must never reach channel checks.
  p.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
      new net.minecraft.network.protocol.common.custom.DiscardedPayload(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hearthcrew_fixture","unnegotiated_client_ping"))));
  h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(p.getUUID())==p,"mod client-only packet must not evict native companion");
  var from=p.position();var to=h.absolutePos(new BlockPos(5,1,1));
  // Scripted input fixture, not an autonomous model acceptance test.
  p.executor().submit("native-move",io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.move(to),io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);
  h.onEachTick(()->{if(p.tickCount%20==0)System.out.println("NATIVE_TRACE tick="+p.tickCount+" pos="+p.position()+" terrain="+io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.standable(p,to)+" here="+io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.standable(p,p.blockPosition())+" ground="+p.onGround()+" input="+p.zza+" path="+p.getNavigation().getPath()+" action="+p.executor().arbiter().activeSnapshot());});
  h.succeedWhen(()->h.assertTrue(p.position().distanceToSqr(from)>4,"native physical movement"));
 }

 @GameTest(template="p0_empty",timeoutTicks=100)
 public static void nativeDeathRespawnIdentity(GameTestHelper h){
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,1));p.setRespawnEnabled(true);
  var server=h.getLevel().getServer();var id=p.getUUID();long generation=p.bodyGeneration();
  var pos=h.absolutePos(new BlockPos(1,1,1));p.setRespawnPosition(h.getLevel().dimension(),pos,0,true,false);
  p.inventory().setItem(0,new net.minecraft.world.item.ItemStack(Items.DIRT,3));
  h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,server);
  server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"kill "+p.getGameProfile().getName());
  h.succeedWhen(()->{var next=server.getPlayerList().getPlayer(id);
   h.assertTrue(next instanceof io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity && next!=p && next.isAlive(),"native companion respawn");
   var crew=(io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity)next;
   h.assertTrue(crew.bodyGeneration()>generation,"new incarnation invalidates old calls");
   h.assertTrue(crew.inventory().countItem(Items.DIRT)==0,"death inventory not replayed");
  });
 }

 @GameTest(template="p0_empty",timeoutTicks=2400,batch="native_survival_chain")
 public static void emptyInventorySurvivalChain(GameTestHelper h){
  for(int x=0;x<12;x++)for(int z=0;z<12;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<7;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,2));p.setRespawnEnabled(false);
  for(int y=1;y<=4;y++)h.setBlock(new BlockPos(3,y,2),Blocks.OAK_LOG);
  h.setBlock(new BlockPos(3,1,4),Blocks.POTATOES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7,7));
  h.setBlock(new BlockPos(3,0,4),Blocks.FARMLAND);
  var table=h.absolutePos(new BlockPos(2,1,1));var furnace=h.absolutePos(new BlockPos(2,1,3));
  var work=new java.util.ArrayList<io.github.yufeiyufei888.hearthcrew.entity.BodyOrder>();
  for(int y=1;y<=4;y++)work.add(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.mine(h.absolutePos(new BlockPos(3,y,2))));
  work.add(order("CRAFT",null,4,"oak_planks"));work.add(order("CRAFT",null,1,"crafting_table"));work.add(order("PLACE",table,0,"crafting_table"));
  work.add(order("CRAFT",null,1,"stick"));work.add(order("CRAFT",table,1,"wooden_pickaxe"));
  for(int i=0;i<8;i++){var block=new BlockPos(4+i%4,1,5+i/4);h.setBlock(block,Blocks.STONE);work.add(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.mine(h.absolutePos(block)));}
  work.add(order("CRAFT",table,1,"furnace"));work.add(order("PLACE",furnace,0,"furnace"));
  work.add(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.mine(h.absolutePos(new BlockPos(3,1,4))));
  work.add(order("PROCESS",furnace,1,"potato"));work.add(order("WAIT",null,240,null));work.add(order("COLLECT_PROCESS",furnace,0,null));
  int[] index={0};String[] active={null};
  h.onEachTick(()->{
   if(p.tickCount%100==0)System.out.println("CHAIN_TRACE step="+index[0]+" pos="+p.position()+" action="+p.executor().arbiter().activeSnapshot());
   if(active[0]!=null){var state=p.executor().arbiter().snapshot(io.github.yufeiyufei888.hearthcrew.kernel.ActionId.of(active[0]));if(state.isEmpty()||!state.get().state().terminal())return;
    if(state.get().state()!=io.github.yufeiyufei888.hearthcrew.kernel.ActionState.COMPLETED){h.fail("chain step="+(index[0]-1)+" "+work.get(index[0]-1)+" result="+state.get()+" pos="+p.position()+" "+p.executor().pickupDiagnostics());return;}active[0]=null;
   }
   if(index[0]==work.size()){h.assertTrue(p.inventory().countItem(Items.BAKED_POTATO)>=1,"actual native furnace output");h.assertTrue(h.getLevel().getBlockState(table).is(Blocks.CRAFTING_TABLE),"actual table remains");h.succeed();return;}
   active[0]="chain-"+index[0];p.executor().submit(active[0],work.get(index[0]++),io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);
  });
 }
 private static io.github.yufeiyufei888.hearthcrew.entity.BodyOrder order(String kind,BlockPos pos,int count,String item){return new io.github.yufeiyufei888.hearthcrew.entity.BodyOrder(io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.valueOf(kind),pos,null,count,item==null?null:net.minecraft.resources.ResourceLocation.withDefaultNamespace(item));}

 @GameTest(template="p0_empty",timeoutTicks=600,batch="native_boat")
 public static void realBoatPlacementBoardAndPropulsion(GameTestHelper h){
  for(int x=0;x<14;x++)for(int z=0;z<10;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<5;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);if(x>=3&&x<=12&&z>=2&&z<=8)h.setBlock(new BlockPos(x,1,z),Blocks.WATER);}
  var p=PlayerTestBodies.spawn(h,new BlockPos(1,1,4));p.inventory().setItem(0,new net.minecraft.world.item.ItemStack(Items.OAK_BOAT));
  var launch=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.launch(p,h.absolutePos(new BlockPos(3,1,4)),net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_boat"));h.assertTrue(launch.completed(),"real launch "+launch);
  var boat=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class,p.getBoundingBox().inflate(6)).getFirst();
  h.assertTrue(p.inventory().countItem(Items.OAK_BOAT)==0,"boat item consumed exactly once");
  var boarded=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.board(p,boat.getUUID());h.assertTrue(boarded.completed(),"real board "+boarded);
  var from=boat.position();p.executor().submit("sail",order("SAIL",h.absolutePos(new BlockPos(10,2,4)),0,null),io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER);
  final int[] checks={0};h.onEachTick(()->{if(checks[0]++%100==0)System.out.println("BOAT_TRACE pos="+boat.position()+" player="+p.position()+" ticks="+p.tickCount+" boatTicks="+boat.tickCount+" ticking="+p.serverLevel().isPositionEntityTicking(boat.blockPosition())+" removed="+boat.isRemoved()+" paused="+p.executor().paused()+" action="+p.executor().arbiter().snapshot(io.github.yufeiyufei888.hearthcrew.kernel.ActionId.of("sail")));});
  h.succeedWhen(()->h.assertTrue(boat.position().distanceToSqr(from)>9&&p.getVehicle()==boat,"native boat moves with rider without teleport"));
 }
}
