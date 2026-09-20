package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P1 GATHER fixture only. It proves one bounded, whitelisted natural-log
 * collection loop; it does not prove arbitrary tree recognition, exploration,
 * campaign completion, or remote chunk loading.
 */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2GatherGameTests {
    private P2GatherGameTests() {}

    @GameTest(template = "gather_empty", timeoutTicks = 700, batch = "hearthcrew_p2_gather")
    public static void gatherOakUsesRealMiningAndPickup(GameTestHelper helper) {
        floor(helper);
        makeSmallOakFixture(helper, new BlockPos(3, 1, 2));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        String id = "p2-gather-real-" + body.getUUID();
        body.executor().submit(id, BodyOrder.gather(ResourceLocation.parse("minecraft:oak_log"), 1), ActionPriority.MISSION);
        helper.succeedWhen(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.COMPLETED) {
                throw new GameTestAssertException("GATHER did not complete: " + action.state() + ":" + action.message());
            }
            BlockPos base = helper.absolutePos(new BlockPos(3, 1, 2));
            if (!helper.getLevel().getBlockState(base).isAir()) throw new GameTestAssertException("real log was not broken");
            if (body.inventory().countItem(Items.OAK_LOG) < 1) throw new GameTestAssertException("real log drop did not enter body inventory");
            if (action.message() == null || !action.message().contains("collected=1")) throw new GameTestAssertException("missing collected result");
        });
    }

    @GameTest(template = "gather_empty", timeoutTicks = 1000, batch = "hearthcrew_p2_gather")
    public static void gatherTwoLogsResetsMiningProgressBetweenBlocks(GameTestHelper helper) {
        floor(helper);
        BlockPos relativeBase = new BlockPos(3, 1, 2);
        makeSmallOakFixture(helper, relativeBase);
        BlockPos first = helper.absolutePos(relativeBase);
        BlockPos second = helper.absolutePos(relativeBase.above());
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        String id = "p2-gather-two-timing-" + body.getUUID();
        long[] brokenAt = {-1L, -1L};
        helper.onEachTick(() -> {
            long tick = helper.getLevel().getGameTime();
            if (brokenAt[0] < 0 && helper.getLevel().getBlockState(first).isAir()) brokenAt[0] = tick;
            if (brokenAt[0] >= 0 && brokenAt[1] < 0 && helper.getLevel().getBlockState(second).isAir()) brokenAt[1] = tick;
        });
        body.executor().submit(id, BodyOrder.gather(ResourceLocation.parse("minecraft:oak_log"), 2), ActionPriority.MISSION);
        helper.startSequence().thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.COMPLETED) {
                if (action.state().terminal()) throw new GameTestAssertException("two-log GATHER result: " + action.state() + ":" + action.message());
                throw new GameTestAssertException("waiting for two real log breaks");
            }
            if (brokenAt[0] < 0 || brokenAt[1] < 0) throw new GameTestAssertException("did not observe both real block breaks");
            if (brokenAt[1] - brokenAt[0] < 60) {
                throw new GameTestAssertException("second log broke without a fresh mining interval: first="
                        + brokenAt[0] + ", second=" + brokenAt[1]);
            }
            if (body.inventory().countItem(Items.OAK_LOG) != 2) throw new GameTestAssertException("two-log gather inventory mismatch");
        }).thenSucceed();
    }

    @GameTest(template = "gather_empty", timeoutTicks = 180, batch = "hearthcrew_p2_gather")
    public static void gatherNeverBreaksMarkedPlayerLog(GameTestHelper helper) {
        floor(helper);
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.DIRT);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_LEAVES);
        CrewWorldData.get(helper.getLevel().getServer()).markPlayerBlock(helper.getLevel(), base);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        String id = "p2-gather-protected-" + body.getUUID();
        body.executor().submit(id, BodyOrder.gather(ResourceLocation.parse("minecraft:oak_log"), 1), ActionPriority.MISSION);
        helper.succeedWhen(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (!action.state().terminal()) throw new GameTestAssertException("waiting for protected gather to fail closed");
            if (action.state() != ActionState.FAILED) throw new GameTestAssertException("protected gather result: " + action.state());
            if (!helper.getLevel().getBlockState(base).is(Blocks.OAK_LOG)) throw new GameTestAssertException("protected log was modified");
            if (body.inventory().countItem(Items.OAK_LOG) != 0) throw new GameTestAssertException("protected gather fabricated material");
        });
    }

    @GameTest(template = "gather_empty", timeoutTicks = 220, batch = "hearthcrew_p2_gather")
    public static void gatherRejectsSideBranchAndPersistentCanopy(GameTestHelper helper) {
        floor(helper);
        BlockPos root = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.DIRT);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.OAK_LOG); // side branch/building evidence
        var persistent = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        helper.setBlock(new BlockPos(3, 3, 2), persistent);
        helper.setBlock(new BlockPos(3, 3, 3), persistent);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        String id = "p2-gather-shape-reject-" + body.getUUID();
        body.executor().submit(id, BodyOrder.gather(ResourceLocation.parse("minecraft:oak_log"), 2), ActionPriority.MISSION);
        helper.succeedWhen(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (!action.state().terminal()) throw new GameTestAssertException("waiting for strict tree-shape rejection");
            if (action.state() != ActionState.FAILED) throw new GameTestAssertException("unsafe tree shape result: " + action.state());
            if (!helper.getLevel().getBlockState(root).is(Blocks.OAK_LOG)
                    || body.inventory().countItem(Items.OAK_LOG) != 0) {
                throw new GameTestAssertException("strict tree rejection changed world or fabricated drops");
            }
        });
    }

    @GameTest(template = "gather_empty", timeoutTicks = 760, batch = "hearthcrew_p2_gather")
    public static void gatherResumesFromObservedCheckpoint(GameTestHelper helper) {
        floor(helper);
        makeSmallOakFixture(helper, new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(3, 3, 2), Blocks.OAK_LOG);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        String gatherId = "p2-gather-resume-" + body.getUUID();
        String waitId = "p2-gather-interrupt-" + body.getUUID();
        BlockPos first = helper.absolutePos(new BlockPos(3, 1, 2));
        boolean[] interrupted = {false};
        boolean[] observedUnpickedDrop = {false};
        body.executor().submit(gatherId, BodyOrder.gather(ResourceLocation.parse("minecraft:oak_log"), 2), ActionPriority.MISSION);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (interrupted[0]) return;
                    if (!helper.getLevel().getBlockState(first).isAir()) {
                        throw new GameTestAssertException("waiting for first GATHER block to be really broken");
                    }
                    ItemEntity drop = findDrop(helper, first);
                    if (drop == null) throw new GameTestAssertException("first GATHER drop not observable");
                    // Keep the real drop in the world while the safety action takes over.
                    drop.setPickUpDelay(40);
                    var receipt = body.executor().submit(waitId,
                            new BodyOrder(BodyOrder.Kind.WAIT, null, null, 2), ActionPriority.SAFETY);
                    if (receipt.decision().name().startsWith("REJECTED")) {
                        throw new GameTestAssertException("safety preemption rejected: " + receipt.decision());
                    }
                    interrupted[0] = true;
                    observedUnpickedDrop[0] = body.inventory().countItem(Items.OAK_LOG) == 0 && drop.isAlive();
                    if (!observedUnpickedDrop[0]) throw new GameTestAssertException("first drop was picked before checkpoint");
                })
                .thenWaitUntil(() -> {
                    var gather = body.executor().arbiter().snapshot(ActionId.of(gatherId)).orElseThrow();
                    if (gather.state() != ActionState.COMPLETED) {
                        if (gather.state().terminal()) {
                            ItemEntity pendingDrop = findDrop(helper, first);
                            String details = pendingDrop == null ? "no nearby drop" : pendingDrop.position() + " pickupDelay="
                                    + pendingDrop.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getShort("PickupDelay");
                            throw new GameTestAssertException("resumed GATHER result: " + gather.state() + ":" + gather.message()
                                    + "; body=" + body.position() + "; drop=" + details + "; checkpoint=" + gather.checkpoint());
                        }
                        throw new GameTestAssertException("waiting for GATHER resume");
                    }
                    if (!interrupted[0] || !observedUnpickedDrop[0]) throw new GameTestAssertException("checkpoint was not taken with an unpicked first drop");
                    if (body.inventory().countItem(Items.OAK_LOG) < 2) throw new GameTestAssertException("resumed GATHER did not acquire both logs");
                    boolean suspended = body.executor().arbiter().journal().stream().anyMatch(r -> r.id().equals(ActionId.of(gatherId)) && r.decision().name().equals("SUSPENDED"));
                    boolean resumed = body.executor().arbiter().journal().stream().anyMatch(r -> r.id().equals(ActionId.of(gatherId)) && r.decision().name().equals("RESUMED"));
                    if (!suspended || !resumed) throw new GameTestAssertException("GATHER checkpoint lacked SUSPENDED/RESUMED evidence");
                }).thenSucceed();
    }

    @GameTest(template = "gather_empty", timeoutTicks = 40, batch = "hearthcrew_p2_gather")
    public static void gatherRejectsNonNaturalResourceAtOrderBoundary(GameTestHelper helper) {
        boolean rejected = false;
        try {
            BodyOrder.gather(ResourceLocation.parse("minecraft:diamond_ore"), 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) helper.fail("unsupported GATHER resource was accepted");
        helper.succeed();
    }

    private static void makeSmallOakFixture(GameTestHelper helper, BlockPos relativeBase) {
        helper.setBlock(relativeBase.below(), Blocks.DIRT);
        helper.setBlock(relativeBase, Blocks.OAK_LOG);
        helper.setBlock(relativeBase.above(), Blocks.OAK_LOG);
        helper.setBlock(relativeBase.above(2), Blocks.OAK_LEAVES);
        helper.setBlock(relativeBase.above(2).east(), Blocks.OAK_LEAVES);
        helper.setBlock(relativeBase.above(2).west(), Blocks.OAK_LEAVES);
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos relative) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, relative);
        body.setRespawnEnabled(false);
        return body;
    }

    private static ItemEntity findDrop(GameTestHelper helper, BlockPos center) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(2.0))
                .stream().filter(entity -> entity.isAlive() && entity.getItem().is(Items.OAK_LOG)).findFirst().orElse(null);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        for (int x = 0; x < 5; x++) for (int y = 1; y < 5; y++) for (int z = 0; z < 5; z++) {
            if (helper.getBlockState(new BlockPos(x, y, z)).isAir()) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
}



