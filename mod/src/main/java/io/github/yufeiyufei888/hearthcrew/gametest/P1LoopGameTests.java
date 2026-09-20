package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

/** Real body actions in a controlled fixture; no model/survival-campaign claim. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P1LoopGameTests {
    @GameTest(template = "p0_empty", timeoutTicks = 600, batch = "hearthcrew_p1_loop")
    public static void mineCraftDeliverAndDuplicateRequestsSettleOnce(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1)); body.setRespawnEnabled(false);
        var recipient = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(4, 1, 1)); recipient.setRespawnEnabled(false);
        var log = new BlockPos(2, 1, 1); helper.setBlock(log, Blocks.OAK_LOG);
        var mining = BodyOrder.mine(helper.absolutePos(log));
        var crafting = new BodyOrder(BodyOrder.Kind.CRAFT, null, null, 1, ResourceLocation.parse("minecraft:oak_planks"));
        var delivery = new BodyOrder(BodyOrder.Kind.TRANSFER, null, recipient.getUUID(), 4, ResourceLocation.parse("minecraft:oak_planks"));
        helper.assertTrue(body.inventory().isEmpty(), "fixture must begin without supplied inventory");
        body.executor().submit("loop-mine", mining, ActionPriority.OWNER);
        helper.startSequence()
                .thenWaitUntil(() -> completed(body, "loop-mine"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockState(log).isAir() && body.inventory().countItem(Items.OAK_LOG) == 1, "real log acquisition absent");
                    body.executor().submit("loop-craft", crafting, ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> completed(body, "loop-craft"))
                .thenExecute(() -> {
                    helper.assertTrue(body.inventory().countItem(Items.OAK_LOG) == 0 && body.inventory().countItem(Items.OAK_PLANKS) == 4, "craft did not consume actual log");
                    var retry = body.executor().submit("loop-craft", crafting, ActionPriority.OWNER);
                    helper.assertTrue(retry.decision() == ReceiptDecision.IDEMPOTENT_REPLAY, "craft retry was accepted twice");
                    body.executor().submit("loop-deliver", delivery, ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> completed(body, "loop-deliver"))
                .thenExecute(() -> {
                    helper.assertTrue(body.inventory().countItem(Items.OAK_PLANKS) == 0 && recipient.inventory().countItem(Items.OAK_PLANKS) == 4, "delivery not observed in recipient inventory");
                    var retry = body.executor().submit("loop-deliver", delivery, ActionPriority.OWNER);
                    helper.assertTrue(retry.decision() == ReceiptDecision.IDEMPOTENT_REPLAY, "transfer retry acquired a second lease");
                })
                .thenExecuteAfter(20, () -> helper.assertTrue(recipient.inventory().countItem(Items.OAK_PLANKS) == 4, "duplicate request duplicated output"))
                .thenSucceed();
    }
    private static void completed(CompanionEntity body, String id) {
        var state = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().state();
        if (state != ActionState.COMPLETED) throw new GameTestAssertException(id + " state=" + state);
    }
}
