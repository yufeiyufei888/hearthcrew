package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.kernel.ReceiptDecision;
import io.github.yufeiyufei888.hearthcrew.kernel.team.*;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewTeamService;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Deterministic evidence for the bounded BUILD composite action. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2BuildGameTests {
    private static final ResourceLocation OAK_PLANKS = ResourceLocation.parse("minecraft:oak_planks");
    private static final ResourceLocation UNSUPPORTED_FLUID = ResourceLocation.parse("minecraft:water");
    private static final ResourceLocation OAK_DOOR = ResourceLocation.parse("minecraft:oak_door");

    private P2BuildGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 260, batch = "hearthcrew_p2_build")
    public static void buildPlacesExactBlocksOnceAndIsIdempotent(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        List<BlockPos> targets = List.of(helper.absolutePos(new BlockPos(2, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 1)), helper.absolutePos(new BlockPos(4, 1, 1)));
        BodyOrder build = build(targets);
        String id = "p2-build-idempotent-" + body.getUUID();
        var accepted = body.executor().submit(id, build, ActionPriority.MISSION);
        if (accepted.decision() != ReceiptDecision.ACCEPTED) helper.fail("BUILD was not accepted: " + accepted);
        var replay = body.executor().submit(id, build, ActionPriority.MISSION);
        if (replay.decision() != ReceiptDecision.IDEMPOTENT_REPLAY) helper.fail("same BUILD retry was not idempotent: " + replay);
        var conflict = body.executor().submit(id, build(List.of(targets.get(0), targets.get(1), helper.absolutePos(new BlockPos(2, 1, 2)))), ActionPriority.MISSION);
        if (conflict.decision() != ReceiptDecision.REJECTED_ID_CONFLICT) helper.fail("same BUILD id accepted a changed blueprint: " + conflict);
        helper.startSequence().thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.COMPLETED) throw new GameTestAssertException("BUILD did not complete: " + action);
            for (BlockPos target : targets) if (!helper.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS))
                throw new GameTestAssertException("BUILD did not place exact oak plank at " + target);
            if (count(body, Items.OAK_PLANKS) != 0) throw new GameTestAssertException("BUILD consumed an incorrect quantity");
            if (body.executor().arbiter().journal().stream().map(receipt -> receipt.id()).distinct().count() != 1)
                throw new GameTestAssertException("BUILD retry created another action identity");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 300, batch = "hearthcrew_p2_build")
    public static void buildPreemptionResumesWithoutRedoingCompletedSteps(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        List<BlockPos> targets = List.of(helper.absolutePos(new BlockPos(2, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 1)), helper.absolutePos(new BlockPos(4, 1, 1)));
        String id = "p2-build-preempt-" + body.getUUID();
        body.executor().submit(id, build(targets), ActionPriority.MISSION);
        boolean[] preempted = {false};
        helper.startSequence().thenWaitUntil(() -> {
            if (helper.getLevel().getBlockState(targets.get(0)).is(Blocks.OAK_PLANKS)
                    && helper.getLevel().getBlockState(targets.get(1)).isAir()) {
                body.executor().submit("p2-build-safety-" + body.getUUID(),
                        new BodyOrder(BodyOrder.Kind.WAIT, null, null, 1), ActionPriority.SAFETY);
                preempted[0] = true;
            }
            if (!preempted[0]) throw new GameTestAssertException("BUILD did not expose a resumable completed step");
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() == ActionState.FAILED || action.state() == ActionState.RECONCILE_REQUIRED)
                throw new GameTestAssertException("BUILD failed during safety preemption: " + action);
        }).thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.COMPLETED) throw new GameTestAssertException("preempted BUILD did not resume: " + action);
            for (BlockPos target : targets) if (!helper.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS))
                throw new GameTestAssertException("resumed BUILD lost target " + target);
            if (count(body, Items.OAK_PLANKS) != 0) throw new GameTestAssertException("resumed BUILD consumed the wrong total");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 220, batch = "hearthcrew_p2_build")
    public static void buildMissingMaterialReportsPartialWithoutInventingBlocks(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        List<BlockPos> targets = List.of(helper.absolutePos(new BlockPos(2, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 1)), helper.absolutePos(new BlockPos(4, 1, 1)));
        String id = "p2-build-partial-" + body.getUUID();
        body.executor().submit(id, build(targets), ActionPriority.MISSION);
        helper.startSequence().thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.PARTIAL) throw new GameTestAssertException("missing material was not reported PARTIAL: " + action);
            // Preflight is atomic: a missing third plank must not place the
            // first two steps and leave a misleading partial world mutation.
            for (BlockPos target : targets) {
                if (!helper.getLevel().getBlockState(target).isAir())
                    throw new GameTestAssertException("partial BUILD changed a target before material preflight completed");
            }
            if (count(body, Items.OAK_PLANKS) != 2) throw new GameTestAssertException("partial BUILD consumed material during failed preflight");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 220, batch = "hearthcrew_p2_build")
    public static void buildReconcilesExternalChangeAndRejectsProtection(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        List<BlockPos> targets = List.of(helper.absolutePos(new BlockPos(2, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 1)));
        String id = "p2-build-reconcile-" + body.getUUID();
        body.executor().submit(id, build(targets), ActionPriority.MISSION);
        boolean[] changed = {false};
        helper.startSequence().thenWaitUntil(() -> {
            if (!changed[0] && helper.getLevel().getBlockState(targets.get(0)).is(Blocks.OAK_PLANKS)
                    && helper.getLevel().getBlockState(targets.get(1)).isAir()) {
                body.executor().submit("p2-build-reconcile-safety-" + body.getUUID(),
                        new BodyOrder(BodyOrder.Kind.WAIT, null, null, 1), ActionPriority.SAFETY);
                helper.getLevel().setBlockAndUpdate(targets.get(0), Blocks.STONE.defaultBlockState());
                changed[0] = true;
            }
            if (!changed[0]) throw new GameTestAssertException("BUILD did not reach a checkpoint before external change");
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() == ActionState.COMPLETED) throw new GameTestAssertException("BUILD completed despite changed confirmed block");
            if (action.state() == ActionState.RECONCILE_REQUIRED) {
                if (!helper.getLevel().getBlockState(targets.get(1)).isAir()) throw new GameTestAssertException("reconcile BUILD wrote an unconfirmed target");
                return;
            }
            throw new GameTestAssertException("BUILD did not enter RECONCILE_REQUIRED after external change: " + action);
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 160, batch = "hearthcrew_p2_build")
    public static void protectedOrUnsupportedBuildTargetFailsClosed(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        CrewWorldData.get(helper.getLevel().getServer()).markPlayerBlock(helper.getLevel(), target);
        String id = "p2-build-protected-" + body.getUUID();
        body.executor().submit(id, build(List.of(target)), ActionPriority.MISSION);
        CompanionEntity unsupported = body(helper, new BlockPos(1, 1, 3));
        unsupported.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        BlockPos unsupportedTarget = helper.absolutePos(new BlockPos(2, 1, 3));
        helper.getLevel().setBlockAndUpdate(unsupportedTarget.below(), Blocks.AIR.defaultBlockState());
        String unsupportedId = "p2-build-no-support-" + unsupported.getUUID();
        unsupported.executor().submit(unsupportedId, build(List.of(unsupportedTarget)), ActionPriority.MISSION);
        CompanionEntity blockEntity = body(helper, new BlockPos(1, 1, 4));
        blockEntity.inventory().clearContent();
        BlockPos blockEntityTarget = helper.absolutePos(new BlockPos(2, 1, 4));
        String blockEntityId = "p2-build-block-entity-" + blockEntity.getUUID();
        blockEntity.executor().submit(blockEntityId,
                new BodyOrder(BodyOrder.Kind.BUILD, null, null, 0, null,
                        List.of(new BodyOrder.BuildStep(blockEntityTarget, UNSUPPORTED_FLUID))),
                ActionPriority.MISSION);
        CompanionEntity doorBody = body(helper, new BlockPos(3, 1, 3));
        doorBody.inventory().setItem(0, new ItemStack(Items.OAK_DOOR, 1));
        BlockPos doorTarget = helper.absolutePos(new BlockPos(4, 1, 3));
        String doorId = "p2-build-door-" + doorBody.getUUID();
        doorBody.executor().submit(doorId,
                new BodyOrder(BodyOrder.Kind.BUILD, null, null, 0, null,
                        List.of(new BodyOrder.BuildStep(doorTarget, OAK_DOOR))),
                ActionPriority.MISSION);
        helper.startSequence().thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.FAILED && action.state() != ActionState.PARTIAL)
                throw new GameTestAssertException("protected BUILD target was not rejected: " + action);
            if (!helper.getLevel().getBlockState(target).isAir() || count(body, Items.OAK_PLANKS) != 1)
                throw new GameTestAssertException("protected BUILD mutated the target or inventory");
            var unsupportedAction = unsupported.executor().arbiter().snapshot(ActionId.of(unsupportedId)).orElseThrow();
            if (unsupportedAction.state() != ActionState.FAILED && unsupportedAction.state() != ActionState.PARTIAL)
                throw new GameTestAssertException("unsupported BUILD target was not rejected: " + unsupportedAction);
            if (!helper.getLevel().getBlockState(unsupportedTarget).isAir() || count(unsupported, Items.OAK_PLANKS) != 1)
                throw new GameTestAssertException("unsupported BUILD mutated the target or inventory");
            var blockEntityAction = blockEntity.executor().arbiter().snapshot(ActionId.of(blockEntityId)).orElseThrow();
            if (blockEntityAction.state() != ActionState.FAILED && blockEntityAction.state() != ActionState.PARTIAL)
                throw new GameTestAssertException("block-entity BUILD blueprint was not rejected: " + blockEntityAction);
            if (!helper.getLevel().getBlockState(blockEntityTarget).isAir())
                throw new GameTestAssertException("block-entity BUILD mutated the target or inventory");
            var doorAction = doorBody.executor().arbiter().snapshot(ActionId.of(doorId)).orElseThrow();
            if (doorAction.state() != ActionState.COMPLETED)
                throw new GameTestAssertException("supported door blueprint did not complete: " + doorAction);
            if (!helper.getLevel().getBlockState(doorTarget).is(Blocks.OAK_DOOR) || count(doorBody, Items.OAK_DOOR) != 0)
                throw new GameTestAssertException("door BUILD did not produce the real door block and consumption");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 420, batch = "hearthcrew_p2_build")
    public static void teamBuildReservesEveryStepRejectsConflictAndReleasesAfterCompletion(GameTestHelper helper) {
        floor(helper);
        List<BlockPos> firstTargets = List.of(
                helper.absolutePos(new BlockPos(2, 1, 1)),
                helper.absolutePos(new BlockPos(3, 1, 1)),
                helper.absolutePos(new BlockPos(4, 1, 1)));
        List<BlockPos> secondTargets = List.of(
                firstTargets.get(2),
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(3, 1, 2)));
        CompanionEntity first = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity second = body(helper, new BlockPos(1, 1, 3));
        first.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        second.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService service = new CrewTeamService(helper.getLevel().getServer(), data);
        CrewTeamService.Work firstWork = teamBuildWork("p2-team-build-first", first, firstTargets);
        CrewTeamService.Work secondWork = teamBuildWork("p2-team-build-overlap", second, secondTargets);
        if (service.propose(firstWork).decision() != TeamDecision.ACCEPTED
                || service.propose(secondWork).decision() != TeamDecision.ACCEPTED) {
            helper.fail("BUILD proposals were not both accepted before lease arbitration");
        }
        if (!first.executor().arbiter().journal().isEmpty() || !second.executor().arbiter().journal().isEmpty()) {
            helper.fail("BUILD proposal created a body action before execute");
        }

        Object firstResult = service.execute(firstWork.taskId(), first.companionId(), firstWork.bodyGeneration(), firstWork.intentId());
        if (!(firstResult instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?> firstReceipt)
                || (firstReceipt.decision() != ReceiptDecision.ACCEPTED && firstReceipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY)) {
            helper.fail("team BUILD was not accepted by the sole body executor: " + firstResult);
            return;
        }
        ActionId firstActionId = firstReceipt.id();
        Object duplicate = service.execute(firstWork.taskId(), first.companionId(), firstWork.bodyGeneration(), firstWork.intentId());
        if (!(duplicate instanceof Map<?, ?> map) || !"RECONCILE_REQUIRED".equals(map.get("state"))) {
            helper.fail("duplicate team BUILD did not fail closed without replaying the action: " + duplicate);
        }
        if (first.executor().arbiter().journal().stream().map(receipt -> receipt.id()).distinct().count() != 1) {
            helper.fail("duplicate team BUILD created a second body action");
        }

        TeamTaskSnapshot claimed = teamTask(service, firstWork.taskId());
        if (claimed.state() != TeamTaskState.RUNNING || claimed.reservedResources().size() != 4
                || claimed.reservedQuantities().size() != 4) {
            helper.fail("BUILD did not reserve body plus every blueprint step: " + claimed);
        }
        String worldId = data.worldId().toString();
        String dimension = first.level().dimension().location().toString();
        if (!claimed.reservedResources().contains(ResourceKey.entity(worldId, dimension, first.getUUID().toString()))) {
            helper.fail("BUILD reservation omitted the executing body");
        }
        for (BlockPos target : firstTargets) {
            if (!claimed.reservedResources().contains(ResourceKey.block(worldId, dimension, target.getX(), target.getY(), target.getZ()))) {
                helper.fail("BUILD reservation omitted blueprint step " + target);
            }
        }

        Object conflict = service.execute(secondWork.taskId(), second.companionId(), secondWork.bodyGeneration(), secondWork.intentId());
        if (!(conflict instanceof TeamResult<?> result) || result.decision() != TeamDecision.REJECTED_RESOURCE_CONFLICT) {
            helper.fail("overlapping BUILD was not rejected atomically: " + conflict);
        }
        if (!second.executor().arbiter().journal().isEmpty()) helper.fail("conflicting BUILD reached the second body executor");

        helper.startSequence().thenWaitUntil(() -> {
            service.tick();
            var action = first.executor().arbiter().snapshot(firstActionId).orElseThrow();
            if (action.state() == ActionState.FAILED || action.state() == ActionState.PARTIAL
                    || action.state() == ActionState.RECONCILE_REQUIRED) {
                throw new GameTestAssertException("team BUILD ended without a complete real effect: " + action);
            }
            if (action.state() != ActionState.COMPLETED) {
                throw new GameTestAssertException("team BUILD is still running: " + action.state());
            }
            TeamTaskSnapshot completed = teamTask(service, firstWork.taskId());
            if (completed.state() != TeamTaskState.COMPLETED || !completed.reservedResources().isEmpty()
                    || !completed.reservedQuantities().isEmpty()) {
                throw new GameTestAssertException("completed BUILD did not release every reservation: " + completed);
            }
            for (BlockPos target : firstTargets) {
                if (!helper.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS)) {
                    throw new GameTestAssertException("completed team BUILD missed " + target);
                }
            }
        }).thenExecute(() -> {
            Object afterRelease = service.execute(secondWork.taskId(), second.companionId(), secondWork.bodyGeneration(), secondWork.intentId());
            if (!(afterRelease instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?> receipt)
                    || (receipt.decision() != ReceiptDecision.ACCEPTED && receipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY)) {
                helper.fail("released BUILD resources could not be claimed by the next task: " + afterRelease);
            }
            if (second.executor().arbiter().journal().stream().map(row -> row.id()).distinct().count() != 1) {
                helper.fail("released BUILD task did not create exactly one body action");
            }
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_build")
    public static void directBuildKeepsAcceptedStartRadiusAfterBodyMoves(GameTestHelper helper) {
        floor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        double farX = target.getX() + 40.5D;
        body.moveTo(farX, target.getY(), target.getZ() + 0.5D, 0, 0);
        String id = "p2-build-start-radius-" + body.getUUID();
        BodyOrder order = build(List.of(target));
        var accepted = body.executor().submit(id, order, ActionPriority.MISSION);
        if (accepted.decision() != ReceiptDecision.ACCEPTED) helper.fail("radius fixture BUILD was not accepted: " + accepted);
        body.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 1.5D, 0, 0);
        helper.startSequence().thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() != ActionState.FAILED && action.state() != ActionState.PARTIAL) {
                throw new GameTestAssertException("radius BUILD has not reached a terminal failure: " + action);
            }
            if (!helper.getLevel().getBlockState(target).isAir() || count(body, Items.OAK_PLANKS) != 1) {
                throw new GameTestAssertException("out-of-radius BUILD mutated the world or consumed material: " + action);
            }
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_build")
    public static void buildLedgerRestoreKeepsBlueprintAndNeverReplaysBeforeFirstTick(GameTestHelper helper) {
        floor(helper);
        List<BlockPos> targets = List.of(
                helper.absolutePos(new BlockPos(2, 1, 1)),
                helper.absolutePos(new BlockPos(3, 1, 1)),
                helper.absolutePos(new BlockPos(4, 1, 1)));
        CompanionEntity original = body(helper, new BlockPos(1, 1, 1));
        original.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        BodyOrder order = build(targets);
        String actionId = "p2-build-restore-" + original.getUUID();
        var accepted = original.executor().submit(actionId, order, ActionPriority.MISSION);
        if (accepted.decision() != ReceiptDecision.ACCEPTED) {
            helper.fail("three-step BUILD was not accepted before the save point: " + accepted);
            return;
        }
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        UUID logicalId = original.companionId();
        UUID oldEntityId = original.getUUID();
        original.discard();
        CompanionEntity restored = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        restored.readAdditionalSaveData(saved);
        restored.setRespawnEnabled(false);
        if (!logicalId.equals(restored.companionId()) || oldEntityId.equals(restored.getUUID())) {
            helper.fail("BUILD restore did not create a new entity with the saved logical identity");
            return;
        }
        var restoredAction = restored.executor().arbiter().snapshot(ActionId.of(actionId)).orElseThrow();
        if (restoredAction.state() != ActionState.RECONCILE_REQUIRED) {
            helper.fail("saved BUILD became executable instead of RECONCILE_REQUIRED: " + restoredAction);
        }
        BodyOrder restoredOrder = restoredAction.payload();
        if (restoredOrder.kind() != BodyOrder.Kind.BUILD
                || !restoredOrder.steps().equals(order.steps())) {
            helper.fail("saved BUILD blueprint steps were not preserved exactly: " + restoredAction.payload());
        }
        if (restored.executor().arbiter().activeActionId().isPresent()) {
            helper.fail("restored BUILD acquired an active execution lease");
        }
        if (count(restored, Items.OAK_PLANKS) != 3) helper.fail("BUILD restore changed the saved inventory");
        for (BlockPos target : targets) {
            if (!helper.getLevel().getBlockState(target).isAir()) {
                helper.fail("BUILD restore replayed a block mutation at " + target);
            }
        }
        restored.executor().tick();
        if (restored.executor().arbiter().activeActionId().isPresent()
                || count(restored, Items.OAK_PLANKS) != 3
                || targets.stream().anyMatch(target -> !helper.getLevel().getBlockState(target).isAir())) {
            helper.fail("restored BUILD tick replayed the old action");
        }
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 180, batch = "hearthcrew_p2_build")
    public static void existingCorrectBuildStepIsObservedWithoutMaterialConsumption(GameTestHelper helper) {
        floor(helper);
        BlockPos existing = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.getLevel().setBlockAndUpdate(existing, Blocks.OAK_PLANKS.defaultBlockState());
        List<BlockPos> targets = List.of(
                existing,
                helper.absolutePos(new BlockPos(3, 1, 1)),
                helper.absolutePos(new BlockPos(4, 1, 1)));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        String id = "p2-build-existing-step-" + body.getUUID();
        body.executor().submit(id, build(targets), ActionPriority.MISSION);
        helper.startSequence().thenWaitUntil(() -> {
            if (helper.getLevel().getBlockState(targets.get(1)).is(Blocks.OAK_PLANKS)
                    && helper.getLevel().getBlockState(targets.get(2)).isAir()) {
                body.executor().submit("p2-build-existing-safety-" + body.getUUID(),
                        new BodyOrder(BodyOrder.Kind.WAIT, null, null, 1), ActionPriority.SAFETY);
                var checkpoint = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().checkpoint();
                if (checkpoint == null || checkpoint.buildSteps().stream()
                        .noneMatch(step -> existing.equals(step.position()) && step.existing() && !step.placed())) {
                    throw new GameTestAssertException("existing BUILD step was not recorded as observed-only: " + checkpoint);
                }
                return;
            }
            throw new GameTestAssertException("BUILD did not reach the observed-only checkpoint");
        }).thenWaitUntil(() -> {
            var action = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow();
            if (action.state() == ActionState.FAILED || action.state() == ActionState.RECONCILE_REQUIRED)
                throw new GameTestAssertException("BUILD failed while checking an existing correct block: " + action);
            if (action.state() != ActionState.COMPLETED)
                throw new GameTestAssertException("BUILD is still resuming after the safety checkpoint: " + action.state());
            if (count(body, Items.OAK_PLANKS) != 0) throw new GameTestAssertException("existing block caused an extra material deduction");
            for (BlockPos target : targets) if (!helper.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS))
                throw new GameTestAssertException("existing-step BUILD did not finish target " + target);
        }).thenSucceed();
    }

    private static BodyOrder build(List<BlockPos> positions) {
        return new BodyOrder(BodyOrder.Kind.BUILD, null, null, 0, null,
                positions.stream().map(position -> new BodyOrder.BuildStep(position, OAK_PLANKS)).toList());
    }

    private static CrewTeamService.Work teamBuildWork(String taskId, CompanionEntity body, List<BlockPos> positions) {
        List<BodyOrder.BuildStep> steps = positions.stream()
                .map(position -> new BodyOrder.BuildStep(position, OAK_PLANKS)).toList();
        return new CrewTeamService.Work(taskId, taskId + "-intent", body.companionId(),
                body.bodyGeneration(), body.level().dimension().location().toString(),
                BodyOrder.Kind.BUILD, null, null, 0, null, "P2 team BUILD resource reservation test", steps);
    }

    private static TeamTaskSnapshot teamTask(CrewTeamService service, String taskId) {
        TeamLedgerSnapshot ledger = (TeamLedgerSnapshot) service.snapshot().get("ledger");
        return ledger.tasks().stream()
                .filter(task -> task.task().id().value().equals(taskId))
                .findFirst().orElseThrow();
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static int count(CompanionEntity body, net.minecraft.world.item.Item item) {
        return body.inventory().countItem(item);
    }
}
