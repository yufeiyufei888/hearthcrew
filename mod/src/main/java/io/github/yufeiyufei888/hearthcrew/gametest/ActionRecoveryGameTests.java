package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class ActionRecoveryGameTests {
    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_recovery")
    public static void ownerRetaskCancelsBothActiveAndSuspendedWork(GameTestHelper helper) {
        var body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1)); body.setRespawnEnabled(false);
        body.executor().submit("old-mission", new BodyOrder(BodyOrder.Kind.WAIT, null, null, 100), ActionPriority.MISSION);
        body.executor().submit("old-safety", new BodyOrder(BodyOrder.Kind.WAIT, null, null, 100), ActionPriority.SAFETY);
        body.executor().retask();
        helper.assertTrue(body.executor().arbiter().snapshot(ActionId.of("old-mission")).orElseThrow().state() == ActionState.CANCELLED, "retask left suspended work alive");
        helper.assertTrue(body.executor().arbiter().snapshot(ActionId.of("old-safety")).orElseThrow().state() == ActionState.CANCELLED, "retask left active work alive");
        var receipt = body.executor().submit("new-mission", new BodyOrder(BodyOrder.Kind.WAIT, null, null, 1), ActionPriority.MISSION);
        helper.assertTrue(receipt.decision() == ReceiptDecision.ACCEPTED, "retasked body cannot accept new work");
        body.discard(); helper.succeed();
    }
    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_recovery")
    public static void loadedAcceptedPlacementNeverReplays(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var original = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        original.setRespawnEnabled(false);
        original.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        var order = new BodyOrder(BodyOrder.Kind.PLACE, helper.absolutePos(new BlockPos(2, 1, 1)), null, 1);
        original.executor().submit("persisted-placement", order, ActionPriority.MISSION);
        var state = new CompoundTag(); original.addAdditionalSaveData(state);
        var identity = original.companionId(); original.discard();
        var loaded = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        loaded.readAdditionalSaveData(state);
        var receipt = loaded.executor().submit("persisted-placement", order, ActionPriority.MISSION);
        helper.assertTrue(identity.equals(loaded.companionId()), "saved logical identity changed");
        helper.assertTrue(receipt.decision() == ReceiptDecision.IDEMPOTENT_REPLAY && receipt.state() == ActionState.RECONCILE_REQUIRED,
                "saved action was accepted again instead of requiring reconciliation");
        helper.startSequence().thenExecuteAfter(30, () -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 1)).isAir(), "loaded placement mutated world");
            helper.assertTrue(loaded.inventory().getItem(0).getCount() == 4, "loaded placement consumed inventory");
            helper.assertTrue(loaded.executor().arbiter().activeActionId().isEmpty(), "saved action acquired execution lease");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_recovery")
    public static void corruptLedgerFailsClosedAndPreservesDiagnosticData(GameTestHelper helper) {
        var body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        var saved = new CompoundTag(); body.addAdditionalSaveData(saved);
        var malformed = new CompoundTag(); malformed.putInt("version", 99); malformed.putString("unknown", "retained");
        saved.put("CrewActionLedger", malformed); body.readAdditionalSaveData(saved);
        helper.assertTrue(body.executor().recoveryInvalid() && body.executor().stopped(), "unknown ledger was silently ignored");
        boolean rejected = false;
        try { body.executor().resume(); } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "ordinary resume bypassed invalid recovery ledger");
        var resaved = new CompoundTag(); body.addAdditionalSaveData(resaved);
        helper.assertTrue(resaved.getCompound("CrewActionLedger").getString("unknown").equals("retained"), "unreadable evidence was discarded");
        body.discard(); helper.succeed();
    }
}
