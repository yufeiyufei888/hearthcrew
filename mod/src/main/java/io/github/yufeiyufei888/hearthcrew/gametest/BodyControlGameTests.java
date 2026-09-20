package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.kernel.FoodState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused control-loop gates kept separate from the broad P0 body suite. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class BodyControlGameTests {
    private BodyControlGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 240, batch = "hearthcrew_body_control")
    public static void acceptedActionPreemptionRetainsCheckpoint(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 1));
        ActionId mission = ActionId.of("control-accepted-mission-" + body.getUUID());
        body.executor().submit(mission.value(), BodyOrder.move(target), ActionPriority.MISSION);
        body.executor().submit("control-safety-" + body.getUUID(),
                new BodyOrder(BodyOrder.Kind.WAIT, null, null, 1), ActionPriority.SAFETY);
        var checkpoint = body.executor().arbiter().snapshot(mission).orElseThrow().checkpoint();
        if (checkpoint == null) helper.fail("accepted action was suspended without a checkpoint");
        helper.startSequence()
                .thenWaitUntil(() -> {
                    var state = body.executor().arbiter().snapshot(mission).orElseThrow().state();
                    if (state != ActionState.RUNNING) {
                        throw new GameTestAssertException("accepted mission did not resume after safety action: " + state);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 180, batch = "hearthcrew_body_control")
    public static void manualPauseFreezesAndResumeContinuesAction(GameTestHelper helper) {
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        String id = "control-pause-" + body.getUUID();
        body.executor().submit(id, new BodyOrder(BodyOrder.Kind.WAIT, null, null, 20), ActionPriority.OWNER);
        helper.startSequence()
                .thenExecuteAfter(2, body.executor()::pause)
                .thenExecuteAfter(40, () -> {
                    var state = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().state();
                    if (state != ActionState.RUNNING) throw new GameTestAssertException("paused action changed state: " + state);
                })
                .thenExecute(body.executor()::resume)
                .thenWaitUntil(() -> {
                    var state = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().state();
                    if (state != ActionState.COMPLETED) throw new GameTestAssertException("resumed WAIT did not complete: " + state);
                })
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 700, batch = "hearthcrew_body_control")
    public static void miningDropAcquiredDuringFoodPreemptionResumesWithoutRemining(GameTestHelper helper) {
        floor(helper);
        BlockPos target = new BlockPos(2, 1, 1);
        helper.setBlock(target, Blocks.STONE);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        String id = "control-mining-preempt-" + body.getUUID();
        body.executor().submit(id, BodyOrder.mine(helper.absolutePos(target)), ActionPriority.OWNER);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!helper.getBlockState(target).isAir()) throw new GameTestAssertException("mine has not produced an air target yet");
                    ItemEntity drop = findDrop(helper, helper.absolutePos(target), Items.COBBLESTONE);
                    // A fast native player may already have picked up the
                    // real drop before this observation tick.  Either the
                    // live entity at the target or the confirmed inventory
                    // increment is valid evidence; never require an artificial
                    // pause to make the drop visible.
                    if (drop == null && count(body, Items.COBBLESTONE) < 1)
                        throw new GameTestAssertException("actual mining drop is not observable or accounted yet");
                    if (drop != null && body.distanceToSqr(drop) > 1.0)
                        throw new GameTestAssertException("body has not reached the real mining drop yet");
                })
                .thenExecute(() -> {
                    body.setFoodState(new FoodState(10, 0.0F, 0.0F));
                    body.inventory().setItem(9, new ItemStack(Items.BREAD));
                })
                .thenWaitUntil(() -> {
                    if (count(body, Items.COBBLESTONE) < 1) throw new GameTestAssertException("real mining drop was not acquired during preemption");
                    var state = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().state();
                    if (state != ActionState.COMPLETED) throw new GameTestAssertException("mining did not complete from acquired drop: " + state);
                })
                .thenSucceed();
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 4; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static ItemEntity findDrop(GameTestHelper helper, BlockPos center, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(2.0))
                .stream().filter(entity -> entity.isAlive() && entity.getItem().is(item)).findFirst().orElse(null);
    }

    private static int count(CompanionEntity body, Item item) {
        int total = 0;
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            ItemStack stack = body.inventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
