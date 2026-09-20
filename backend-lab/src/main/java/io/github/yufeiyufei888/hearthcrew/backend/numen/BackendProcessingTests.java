package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.backend.CompanionBackend;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("hearthcrew_backend_processing")
@PrefixGameTestTemplate(false)
public class BackendProcessingTests {
    private static CompanionBackend.Request request(NumenBackend b,String id,BodyOrder order){return new CompanionBackend.Request(b.snapshot().identity(),id,order,ActionPriority.PERSONAL);}
    private static BlockPos station(GameTestHelper h){var p=h.absolutePos(new BlockPos(5,1,5));h.getLevel().setBlockAndUpdate(p,Blocks.FURNACE.defaultBlockState());BackendJournal.get(h.getLevel().getServer()).publish(h.getLevel().dimension(),p);return p;}
    @GameTest(template="empty",timeoutTicks=1000,batch="processing-native-output")
    public static void submittedOrderFreesBodyAndCollectsOnlyRealOutput(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();inv.setItem(9,new ItemStack(Items.RAW_IRON,3));inv.setItem(10,new ItemStack(Items.COAL));
        var p=station(h);var book=FurnaceWork.get(b.body().server);var submit=request(b,"cook-three-iron",new BodyOrder(BodyOrder.Kind.PROCESS,p,null,3,ResourceLocation.parse("minecraft:raw_iron")));
        int[] phase={0};boolean[] moved={false};CompoundTag[] order={null};
        h.runAfterDelay(6,()->{b.dispatch(submit);phase[0]=1;});
        h.onEachTick(()->{
            if(phase[0]==0||!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"processing chain requires real success: "+b.snapshot());
            if(phase[0]==1){
                h.assertTrue(inv.countItem(Items.IRON_INGOT)==0&&inv.countItem(Items.RAW_IRON)==0&&inv.countItem(Items.COAL)==0,"submission consumed inputs, acquired no output");
                order[0]=book.at(h.getLevel(),p);h.assertTrue(order[0]!=null&&order[0].getString("state").equals("PROCESSING"),"durable processing state");
                b.dispatch(submit);h.assertTrue(b.snapshot().state().equals("SUCCESS"),"same accepted order is not re-fed");
                BackendGateTests.move(h,b,"work-during-processing",new BlockPos(3,1,3));phase[0]=2;
            }else if(phase[0]==2){
                moved[0]=true;book.reconcile(h.getLevel(),p);if(!order[0].getString("state").equals("OUTPUT_READY"))return;
                h.assertTrue(inv.countItem(Items.IRON_INGOT)==0,"furnace output is not inventory output");
                b.dispatch(request(b,"collect-three-iron",new BodyOrder(BodyOrder.Kind.COLLECT_PROCESS,p,null,0)));phase[0]=3;
            }else{
                h.assertTrue(moved[0]&&inv.countItem(Items.IRON_INGOT)==3&&order[0].getString("state").equals("COMPLETED")&&order[0].getInt("collected")==3,"actual inventory and durable credit");
                h.assertTrue(((AbstractFurnaceBlockEntity)h.getLevel().getBlockEntity(p)).getItem(2).isEmpty()&&book.at(h.getLevel(),p)==null,"output transferred once, station released");
                var saved=book.save(new CompoundTag(),h.getLevel().registryAccess());var restored=FurnaceWork.load(saved);
                h.assertTrue(restored.at(h.getLevel(),p)==null&&restored.save(new CompoundTag(),h.getLevel().registryAccess()).equals(saved),"completed journal reload unchanged");b.close();h.succeed();
            }
        });
    }
    @GameTest(template="empty",timeoutTicks=150,batch="processing-cancel-reservation")
    public static void cancelAfterInputRetainsUnknownEffectsAndBlocksOtherBody(GameTestHelper h){
        var b=BackendGateTests.spawn(h);b.body().getInventory().setItem(9,new ItemStack(Items.RAW_IRON,3));b.body().getInventory().setItem(10,new ItemStack(Items.COAL));
        var p=station(h);var book=FurnaceWork.get(b.body().server);int[] phase={0};NumenBackend[] other={null};
        h.runAfterDelay(6,()->{b.submit(new ProcessRequest("interrupt-feed",h.getLevel().getGameTime()+100,p,Items.RAW_IRON,3,false));phase[0]=1;});
        h.onEachTick(()->{
            if(phase[0]==2){var peer=other[0];if(!peer.terminal())return;
                h.assertTrue(peer.snapshot().state().equals("FAILED")&&peer.snapshot().result().contains("WORKSTATION_RESERVED_BY_TEAMMATE"),"another body cannot take reserved inputs");
                var furnace=(AbstractFurnaceBlockEntity)h.getLevel().getBlockEntity(p);
                h.assertTrue(furnace.getItem(0).getCount()==3&&peer.body().getInventory().countItem(Items.RAW_IRON)==0,"reservation enforces world facts");b.close();peer.close();h.succeed();return;}
            if(phase[0]!=1)return;var row=book.at(h.getLevel(),p);if(row==null||!row.getString("state").equals("INPUT_DEPOSITED"))return;phase[0]=2;
            b.cancel();h.assertTrue(row.getString("state").equals("RECONCILE_REQUIRED"),"cancel retains uncertain partial effects");
            var furnace=(AbstractFurnaceBlockEntity)h.getLevel().getBlockEntity(p);
            h.assertTrue(furnace.getItem(0).getCount()==3&&furnace.getItem(1).isEmpty()&&b.body().getInventory().countItem(Items.COAL)==1,"exact partial inputs, no replay");
            var loaded=FurnaceWork.load(book.save(new CompoundTag(),h.getLevel().registryAccess()));
            h.assertTrue(loaded.at(h.getLevel(),p).getString("state").equals("RECONCILE_REQUIRED"),"reload does not restart partial transaction");
            var peerId=java.util.UUID.randomUUID();var peer=NumenBackend.spawn(h.getLevel(),peerId,"Peer"+peerId.toString().substring(0,8),java.util.UUID.randomUUID(),h.absoluteVec(new net.minecraft.world.phys.Vec3(3.5,1,8.5)),false);
            other[0]=peer;
            peer.submit(new ContainerRequest("peer-cannot-steal-input",h.getLevel().getGameTime()+60,p,ContainerRequest.Mode.WITHDRAW,Items.RAW_IRON,3,0));
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="processing-journal-corruption")
    public static void reloadCannotInventResultsAndRemovedStationIsReconciled(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var p=station(h);var book=FurnaceWork.get(b.body().server);
        // Explicit injected journal states exercise persistence only, never count as real processing success.
        var r=book.reserve(b.body(),"injected-interrupted-reserve",p,Items.RAW_IRON,1,Items.COAL,1,Items.IRON_INGOT,1);
        var saved=book.save(new CompoundTag(),h.getLevel().registryAccess());
        h.assertTrue(FurnaceWork.load(saved).at(h.getLevel(),p).getString("state").equals("RECONCILE_REQUIRED"),"reserved without world evidence is unknown after load");
        var corrupt=saved.copy();for(var raw:corrupt.getList("orders",10)){var row=(CompoundTag)raw;if(row.getString("id").equals(r.getString("id")))row.putString("state","COMPLETED");}boolean rejected=false;
        try{FurnaceWork.load(corrupt);}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"completed without credited output rejected");
        book.phase(b.body().server,r,"PROCESSING");h.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());book.reconcile(h.getLevel(),p);
        h.assertTrue(r.getString("state").equals("RECONCILE_REQUIRED")&&r.getInt("collected")==0&&b.body().getInventory().isEmpty(),"removed facility unknown, never produced output");b.close();h.succeed();
    }
}
