package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.CompanionFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("hearthcrew_backend_journal")
@PrefixGameTestTemplate(false)
public class BackendJournalTests {
    @GameTest(template="empty",timeoutTicks=400,batch="journal-native-pickup-merge")
    public static void realMergedPickupSurvivesLedgerReload(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.pause();var body=b.body();var level=h.getLevel();
        var p=h.absoluteVec(new net.minecraft.world.phys.Vec3(9.5,1,5.5));
        var first=new net.minecraft.world.entity.item.ItemEntity(level,p.x,p.y,p.z,new ItemStack(Items.COAL,2));
        var second=new net.minecraft.world.entity.item.ItemEntity(level,p.x+.08,p.y,p.z,new ItemStack(Items.COAL,3));
        for(var item:java.util.List.of(first,second)){item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);item.setPickUpDelay(25);level.addFreshEntity(item);}
        // Explicit fixture drops; acquisition is still the real vanilla entity pickup path.
        var one=DropProof.forAction(level,body.getUUID(),"fixture-drop-one",1);one.spawned(first.getUUID(),Items.COAL,2);
        var two=DropProof.forAction(level,body.getUUID(),"fixture-drop-two",1);two.spawned(second.getUUID(),Items.COAL,3);
        h.runAfterDelay(30,()->{b.resume();BackendGateTests.move(h,b,"walk-to-fixture-drops",new BlockPos(9,1,5));});
        h.onEachTick(()->{
            if(body.getInventory().countItem(Items.COAL)!=5)return;
            h.assertTrue(one.ownAcquired(Items.COAL)==2&&two.ownAcquired(Items.COAL)==3,"merged native pickup retains separate source contributions");
            var stored=PickupLedger.get(body.server);var restored=PickupLedger.load(stored.save(new CompoundTag(),level.registryAccess()));
            h.assertTrue(restored.proofs.get(one.key()).ownAcquired(Items.COAL)==2&&restored.proofs.get(two.key()).ownAcquired(Items.COAL)==3,"reload preserves acquisition, not a fresh counter");
            h.assertTrue(restored.drops.values().stream().flatMap(java.util.Collection::stream).noneMatch(c->c.proof.key().equals(one.key())||c.proof.key().equals(two.key())),"already picked identities are not pending again");
            h.assertTrue(restored.proofs.get(one.key()).collectors.containsKey(body.getUUID()+"/minecraft:coal"),"actual collector identity retained");
            b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="journal-unknown-versus-loss")
    public static void removalClosesLossWhileUnobservedEvidenceRemainsUnknown(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);b.pause();var level=h.getLevel();var p=h.absoluteVec(new net.minecraft.world.phys.Vec3(12.5,1,5.5));
        var item=new net.minecraft.world.entity.item.ItemEntity(level,p.x,p.y,p.z,new ItemStack(Items.COAL,4));item.setNeverPickUp();level.addFreshEntity(item);
        var proof=DropProof.forAction(level,b.body().getUUID(),"fixture-removed-drop",1);proof.spawned(item.getUUID(),Items.COAL,4);
        UUID unknown=UUID.randomUUID();proof.spawned(unknown,Items.COAL,2); // explicitly injected unknown identity, no physical production claim
        h.runAfterDelay(2,item::discard);
        h.runAfterDelay(5,()->{
            var ledger=PickupLedger.get(b.body().server);var reloaded=PickupLedger.load(ledger.save(new CompoundTag(),level.registryAccess()));
            var restored=reloaded.proofs.get(proof.key());
            h.assertTrue(restored.lost.getOrDefault("minecraft:coal",0)==4&&restored.acquired()==0,"confirmed disappearance is loss, never acquisition");
            h.assertTrue(reloaded.drops.containsKey(unknown)&&!reloaded.drops.containsKey(item.getUUID()),"unobserved remains pending; confirmed removed closes once");
            h.assertTrue(b.body().getInventory().isEmpty(),"loading evidence does not restore inventory");b.close();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=100,batch="journal-roundtrip")
    public static void terminalCannotRegressAndActiveDoesNotReplay(GameTestHelper h) {
        var journal=new BackendJournal();var id=UUID.randomUUID();long generation=journal.nextGeneration(id);
        journal.begin(id,"finished","fingerprint-one",generation);journal.update(id,"finished","SUCCESS","verified outcome",4,16);
        journal.update(id,"finished","RUNNING","late acceptance",0,0);
        journal.begin(id,"in-flight","fingerprint-two",generation);journal.update(id,"in-flight","RUNNING","partial evidence",9,1);
        var loaded=BackendJournal.load(journal.save(new CompoundTag(),h.getLevel().registryAccess()));
        h.assertTrue(loaded.worldId().equals(journal.worldId()),"world identity persists");
        h.assertTrue(loaded.find(id,"finished").state().equals("SUCCESS")&&loaded.find(id,"finished").result().equals("verified outcome"),"terminal proof does not regress");
        h.assertTrue(loaded.find(id,"in-flight").state().equals("RECONCILE_REQUIRED")&&loaded.find(id,"in-flight").accessSpent()==9,"unknown action remains inert, spent budget persists");
        h.assertTrue(loaded.nextGeneration(id)==2,"body generation monotonic across load");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=250,batch="journal-native-rejoin")
    public static void repeatedCraftAfterBodyRejoinDoesNotSpendTwice(GameTestHelper h) {
        var b=BackendGateTests.spawn(h);var inv=b.body().getInventory();
        inv.setItem(9,new ItemStack(Items.COBBLESTONE,11));inv.setItem(10,new ItemStack(Items.STICK,5));
        h.setBlock(new BlockPos(5,1,5),Blocks.CRAFTING_TABLE);
        BackendJournal.get(b.body().server).publish(h.getLevel().dimension(),h.absolutePos(new BlockPos(5,1,5)));
        b.submit(new CraftRequest("persistent-craft",h.getLevel().getGameTime()+100,Items.STONE_PICKAXE,1));
        h.onEachTick(()->{
            if(!b.terminal())return;
            var player=b.body();var uuid=player.getUUID();var name=player.getGameProfile().getName();var owner=player.getOwnerUuid();
            h.assertTrue(b.snapshot().state().equals("SUCCESS"),"first real craft succeeds");b.close();
            var restored=new NumenBackend(CompanionFactory.spawn(h.getLevel().getServer(),uuid,name,owner,h.getLevel(),null));
            var reply=restored.submit(new CraftRequest("persistent-craft",h.getLevel().getGameTime()+100,Items.STONE_PICKAXE,1));
            h.assertTrue(reply.reused()&&reply.state().equals("SUCCESS")&&reply.generation()==1,"same identity returns original body outcome");
            h.assertTrue(restored.snapshot().state().equals("IDLE"),"historical reply never occupies new body");
            h.assertTrue(restored.body().getInventory().countItem(Items.STONE_PICKAXE)==1&&restored.body().getInventory().countItem(Items.COBBLESTONE)==8,"no second craft or inventory import");
            boolean conflict=false;try{restored.submit(new CraftRequest("persistent-craft",h.getLevel().getGameTime()+100,Items.STONE_AXE,1));}catch(IllegalArgumentException e){conflict=e.getMessage().equals("ACTION_ID_CONFLICT");}
            h.assertTrue(conflict,"same ID different request must reject");restored.close();h.succeed();
        });
    }
}
