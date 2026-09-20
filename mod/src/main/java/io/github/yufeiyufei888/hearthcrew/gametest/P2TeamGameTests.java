package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.kernel.ReceiptDecision;
import io.github.yufeiyufei888.hearthcrew.kernel.team.*;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewTeamService;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server-thread P2 team ownership tests; no WorldEvents singleton injection. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2TeamGameTests {
    private P2TeamGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_team")
    public static void primitiveTaskFingerprintAndArchiveRemainCompatibleBeforeBuild(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService service = new CrewTeamService(helper.getLevel().getServer(), data);
        var work = work("p2-legacy-primitive", body, BodyOrder.Kind.WAIT, null, null, 1, null);
        service.propose(work);
        CompoundTag archive = data.teamArchive();
        var json = JsonParser.parseString(new String(archive.getByteArray("payload"), StandardCharsets.UTF_8)).getAsJsonObject();
        var oldDefinition = json.getAsJsonArray("works").get(0).getAsJsonObject();
        oldDefinition.remove("steps"); // Exact pre-BUILD definition shape; no optional blueprint field.
        try {
            String legacyFingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(new com.google.gson.Gson().toJson(oldDefinition).getBytes(StandardCharsets.UTF_8)));
            String recorded = json.getAsJsonObject("ledger").getAsJsonArray("tasks").get(0).getAsJsonObject()
                    .getAsJsonObject("task").get("fingerprint").getAsString();
            if (!legacyFingerprint.equals(recorded)) helper.fail("adding BUILD changed a primitive task fingerprint");
        } catch (java.security.NoSuchAlgorithmException unavailable) { helper.fail("SHA-256 unavailable"); }
        archive.putByteArray("payload", json.toString().getBytes(StandardCharsets.UTF_8));
        data.storeTeamArchive(archive);
        CrewTeamService restored = new CrewTeamService(helper.getLevel().getServer(), data);
        if (!Boolean.FALSE.equals(restored.snapshot().get("recoveryInvalid"))) helper.fail("pre-BUILD task archive failed to restore");
        if (!body.executor().arbiter().journal().isEmpty()) helper.fail("restoring a primitive archive submitted an action");
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_team")
    public static void bodyAcceptsOnePlannedTaskAndRejectsStaleAirTarget(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewTeamService service = isolatedService(helper);
        var first = work("p2-one-plan", body, BodyOrder.Kind.WAIT, null, null, 1, null);
        if (service.propose(first).decision() != TeamDecision.ACCEPTED) helper.fail("first proposal rejected");
        expectRejected(() -> service.propose(work("p2-competing-plan", body, BodyOrder.Kind.WAIT, null, null, 2, null)), "two planned tasks assigned to one body");
        if (service.propose(first).decision() != TeamDecision.IDEMPOTENT_REPLAY) helper.fail("exact proposal retry lost idempotence");
        service.ownerRetiredWork(body);
        BlockPos air = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
        expectRejected(() -> service.propose(work("p2-stale-air", body, BodyOrder.Kind.MINE, air, null, 1, null)), "stale mining proposal accepted after target disappeared");
        if (!body.executor().arbiter().journal().isEmpty()) helper.fail("rejected proposals submitted world actions");
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_team")
    public static void missingStartedBindingPreservesArchiveAndFailsClosed(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService service = new CrewTeamService(helper.getLevel().getServer(), data);
        var work = work("p2-missing-binding", body, BodyOrder.Kind.WAIT, null, null, 100, null);
        service.propose(work);
        service.execute(work.taskId(), body.companionId(), work.bodyGeneration(), work.intentId());
        CompoundTag corrupt = data.teamArchive();
        var json = JsonParser.parseString(new String(corrupt.getByteArray("payload"), StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonArray("bindings").remove(0);
        corrupt.putByteArray("payload", json.toString().getBytes(StandardCharsets.UTF_8));
        data.storeTeamArchive(corrupt);
        CrewTeamService restored = new CrewTeamService(helper.getLevel().getServer(), data);
        if (!Boolean.TRUE.equals(restored.snapshot().get("recoveryInvalid"))) helper.fail("missing started binding was not rejected");
        if (!corrupt.equals(data.teamArchive())) helper.fail("corrupt binding archive overwritten");
        body.executor().stop("missing-binding test cleanup");
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 600, batch = "hearthcrew_p2_team")
    public static void mineReservationBlocksSecondBodyAndProposalHasNoAction(GameTestHelper helper) {
        floor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlockAndUpdate(target, Blocks.OAK_LOG.defaultBlockState());
        CompanionEntity first = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity second = body(helper, new BlockPos(5, 1, 1));
        CrewTeamService service = isolatedService(helper);
        CrewTeamService.Work firstWork = work("p2-mine-first", first, BodyOrder.Kind.MINE, target, null, 1, null);
        CrewTeamService.Work secondWork = work("p2-mine-second", second, BodyOrder.Kind.MINE, target, null, 1, null);
        if (service.propose(firstWork).decision() != TeamDecision.ACCEPTED || service.propose(secondWork).decision() != TeamDecision.ACCEPTED) {
            helper.fail("both distinct task definitions should be proposal-accepted before lease arbitration");
        }
        if (!first.executor().arbiter().journal().isEmpty() || !second.executor().arbiter().journal().isEmpty()) {
            helper.fail("propose unexpectedly created a body action");
        }
        expectRejected(() -> service.execute(firstWork.taskId(), first.companionId(), firstWork.bodyGeneration() + 1L, firstWork.intentId()), "wrong body generation created a body action");
        expectRejected(() -> service.execute(firstWork.taskId(), first.companionId(), firstWork.bodyGeneration(), firstWork.intentId() + "-late"), "wrong owner intent created a body action");
        if (!first.executor().arbiter().journal().isEmpty()) helper.fail("rejected generation/intent reached BodyExecutor");
        Object firstReceipt = service.execute(firstWork.taskId(), first.companionId(), firstWork.bodyGeneration(), firstWork.intentId());
        if (!(firstReceipt instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?> receipt)
                || (receipt.decision() != ReceiptDecision.ACCEPTED && receipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY)) {
            helper.fail("first real MINE was not accepted: " + firstReceipt);
        }
        ActionId firstActionId = first.executor().arbiter().journal().stream().findFirst().orElseThrow().id();
        Object secondResult = service.execute(secondWork.taskId(), second.companionId(), secondWork.bodyGeneration(), secondWork.intentId());
        if (!(secondResult instanceof io.github.yufeiyufei888.hearthcrew.kernel.team.TeamResult<?> result)
                || result.decision() != TeamDecision.REJECTED_RESOURCE_CONFLICT) {
            helper.fail("second MINE did not fail on the shared block reservation: " + secondResult);
        }
        if (!second.executor().arbiter().journal().isEmpty()) helper.fail("rejected second MINE reached BodyExecutor");
        helper.startSequence().thenWaitUntil(() -> {
            service.tick();
            if (!helper.getLevel().getBlockState(target).isAir()) throw new GameTestAssertException("first MINE did not change the real target block");
            var action = first.executor().arbiter().snapshot(firstActionId);
            if (action.isEmpty() || action.get().state() != ActionState.COMPLETED) {
                throw new GameTestAssertException("first MINE did not complete: " + action);
            }
            if (first.inventory().countItem(Items.OAK_LOG) != 1 || second.inventory().countItem(Items.OAK_LOG) != 0) {
                throw new GameTestAssertException("MINE inventory evidence was not conserved: first=" + first.inventory().countItem(Items.OAK_LOG) + ", second=" + second.inventory().countItem(Items.OAK_LOG));
            }
            TeamTaskSnapshot task = ((TeamLedgerSnapshot) service.snapshot().get("ledger")).tasks().stream()
                    .filter(candidate -> candidate.task().id().value().equals(firstWork.taskId())).findFirst().orElseThrow();
            if (task.state() != TeamTaskState.COMPLETED) throw new GameTestAssertException("team MINE task did not become COMPLETED: " + task.state());
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 500, batch = "hearthcrew_p2_team")
    public static void transferConservesItemsAndSharedRecipientIsExclusive(GameTestHelper helper) {
        floor(helper);
        CompanionEntity sender = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity competingSender = body(helper, new BlockPos(1, 1, 3));
        CompanionEntity recipient = body(helper, new BlockPos(4, 1, 1));
        sender.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        competingSender.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        CrewTeamService service = isolatedService(helper);
        CrewTeamService.Work first = work("p2-transfer-first", sender, BodyOrder.Kind.TRANSFER, null, recipient.getUUID(), 4, "minecraft:oak_planks");
        CrewTeamService.Work second = work("p2-transfer-second", competingSender, BodyOrder.Kind.TRANSFER, null, recipient.getUUID(), 4, "minecraft:oak_planks");
        if (service.propose(first).decision() != TeamDecision.ACCEPTED || service.propose(second).decision() != TeamDecision.ACCEPTED) helper.fail("transfer proposals were not accepted");
        if (!recipient.isAlive() || recipient.isRemoved() || helper.getLevel().getEntity(recipient.getUUID()) != recipient) {
            helper.fail("transfer fixture recipient was not live before execution: alive=" + recipient.isAlive() + ", removed=" + recipient.isRemoved() + ", lookup=" + helper.getLevel().getEntity(recipient.getUUID()));
        }
        int before = count(sender) + count(competingSender) + count(recipient);
        service.execute(first.taskId(), sender.companionId(), first.bodyGeneration(), first.intentId());
        ActionId firstActionId = sender.executor().arbiter().journal().stream().findFirst().orElseThrow().id();
        Object rejected = service.execute(second.taskId(), competingSender.companionId(), second.bodyGeneration(), second.intentId());
        if (!(rejected instanceof io.github.yufeiyufei888.hearthcrew.kernel.team.TeamResult<?> result)
                || result.decision() != TeamDecision.REJECTED_RESOURCE_CONFLICT) helper.fail("shared recipient was not reserved exclusively: " + rejected);
        if (!competingSender.executor().arbiter().journal().isEmpty()) helper.fail("rejected transfer reached the second BodyExecutor");
        ActionId[] secondActionId = new ActionId[1];
        helper.startSequence().thenWaitUntil(() -> {
            service.tick();
            var firstState = sender.executor().arbiter().snapshot(firstActionId).orElseThrow();
            if (firstState.state() != ActionState.COMPLETED) {
                Object target = helper.getLevel().getEntity(recipient.getUUID());
                throw new GameTestAssertException("first TRANSFER did not complete: state=" + firstState.state() + ", message=" + firstState.message()
                        + ", payload=" + firstState.payload() + ", senderPos=" + sender.blockPosition() + ", recipientPos=" + recipient.blockPosition()
                        + ", senderDim=" + sender.level().dimension().location() + ", recipientDim=" + recipient.level().dimension().location()
                        + ", recipientAlive=" + recipient.isAlive() + ", recipientRemoved=" + recipient.isRemoved() + ", recipientHealth=" + recipient.getHealth()
                        + ", lookup=" + target + ", lookupAlive=" + (target instanceof net.minecraft.world.entity.Entity entity && entity.isAlive())
                        + ", senderPlanks=" + count(sender) + ", competingPlanks=" + count(competingSender) + ", recipientPlanks=" + count(recipient));
            }
            if (count(sender) != 0 || count(recipient) != 4 || count(competingSender) != 4) throw new GameTestAssertException("first transfer inventories were not observed: sender=" + count(sender) + ", recipient=" + count(recipient));
        }).thenExecute(() -> {
            Object retry = service.execute(second.taskId(), competingSender.companionId(), second.bodyGeneration(), second.intentId());
            if (!(retry instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?> receipt)
                    || (receipt.decision() != ReceiptDecision.ACCEPTED && receipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY)) helper.fail("second transfer retry was not accepted after recipient release: " + retry);
            secondActionId[0] = competingSender.executor().arbiter().journal().stream().findFirst().orElseThrow().id();
        }).thenWaitUntil(() -> {
            service.tick();
            if (secondActionId[0] == null || competingSender.executor().arbiter().snapshot(secondActionId[0]).orElseThrow().state() != ActionState.COMPLETED) throw new GameTestAssertException("second TRANSFER did not complete");
            if (count(sender) != 0 || count(competingSender) != 0 || count(recipient) != 8) throw new GameTestAssertException("final transfer inventories were not observed: sender=" + count(sender) + ", competing=" + count(competingSender) + ", recipient=" + count(recipient));
            if (count(sender) + count(competingSender) + count(recipient) != before) throw new GameTestAssertException("item conservation failed");
            if (distinctActionIds(sender) != 1 || distinctActionIds(competingSender) != 1) throw new GameTestAssertException("a transfer executed more than once");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 160, batch = "hearthcrew_p2_team")
    public static void restoredBindingIsReconcileRequiredAndNeverReplayed(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService first = new CrewTeamService(helper.getLevel().getServer(), data);
        CrewTeamService.Work work = work("p2-restore-wait", body, BodyOrder.Kind.WAIT, null, null, 100, null);
        if (first.propose(work).decision() != TeamDecision.ACCEPTED) helper.fail("restore fixture proposal rejected");
        Object accepted = first.execute(work.taskId(), body.companionId(), work.bodyGeneration(), work.intentId());
        if (!(accepted instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?>)) helper.fail("restore fixture did not submit real WAIT");
        String actualActionId = body.executor().arbiter().journal().stream().findFirst().orElseThrow().id().value();
        CrewTeamService restored = new CrewTeamService(helper.getLevel().getServer(), data);
        Object replay = restored.execute(work.taskId(), body.companionId(), work.bodyGeneration(), work.intentId());
        if (!(replay instanceof Map<?, ?> map) || !"RECONCILE_REQUIRED".equals(map.get("state"))) helper.fail("restored binding was replayed instead of reconciled: " + replay);
        long actionCount = body.executor().arbiter().journal().stream().filter(receipt -> receipt.id().value().equals(actualActionId)).count();
        if (distinctActionIds(body) != 1 || actionCount < 1) helper.fail("restore created duplicate body action: ids=" + distinctActionIds(body) + ", rows=" + actionCount);
        if (body.executor().arbiter().journal().stream().anyMatch(receipt -> !(receipt.payload() instanceof BodyOrder order) || order.kind() != BodyOrder.Kind.WAIT || order.count() != 100)) {
            helper.fail("restore changed the recorded WAIT action payload");
        }
        helper.startSequence().thenExecuteAfter(20, () -> {
            restored.tick();
            if (body.executor().arbiter().journal().stream().filter(receipt -> receipt.id().value().equals(actualActionId)).count() < 1) helper.fail("restore tick lost the binding");
            if (body.executor().arbiter().snapshot(ActionId.of(actualActionId)).orElseThrow().state() != ActionState.RUNNING) helper.fail("WAIT100 was not still RUNNING at the 20 tick checkpoint");
            if (((TeamLedgerSnapshot) restored.snapshot().get("ledger")).tasks().stream().filter(task -> task.task().id().value().equals(work.taskId())).findFirst().orElseThrow().state() != TeamTaskState.RECONCILE_REQUIRED) helper.fail("restored task was not reconcile-required");
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 160, batch = "hearthcrew_p2_team")
    public static void restoredTeamWorkKeepsUnknownLeaseAcrossReplacementBody(GameTestHelper helper) {
        floor(helper);
        CompanionEntity original = body(helper, new BlockPos(1, 1, 1));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService first = new CrewTeamService(helper.getLevel().getServer(), data);
        CrewTeamService.Work work = work("p2-replacement-wait", original, BodyOrder.Kind.WAIT, null, null, 100, null);
        if (first.propose(work).decision() != TeamDecision.ACCEPTED) helper.fail("replacement fixture proposal rejected");
        Object submitted = first.execute(work.taskId(), original.companionId(), work.bodyGeneration(), work.intentId());
        if (!(submitted instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?>)) {
            helper.fail("replacement fixture did not submit WAIT: " + submitted);
            return;
        }
        var submittedReceipt = (io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?>) submitted;
        if (submittedReceipt.decision() != ReceiptDecision.ACCEPTED)
            helper.fail("replacement fixture WAIT was not accepted: " + submitted);
        ActionId oldActionId = submittedReceipt.id();
        long oldGeneration = Integer.toUnsignedLong(original.getId());
        UUID logicalId = original.companionId();
        UUID oldEntityId = original.getUUID();
        CompoundTag savedBody = new CompoundTag();
        original.addAdditionalSaveData(savedBody);
        CompoundTag savedTeamArchive = data.teamArchive();
        if (savedTeamArchive == null || !savedTeamArchive.contains("payload")) helper.fail("team archive was not persisted before body replacement");

        original.discard();
        if (!original.isRemoved()) helper.fail("old body was not removed before replacement");
        CompanionEntity replacement = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(3, 1, 1));
        replacement.readAdditionalSaveData(savedBody);
        replacement.setRespawnEnabled(false);
        if (!logicalId.equals(replacement.companionId()) || oldEntityId.equals(replacement.getUUID())) helper.fail("replacement did not preserve logical identity with a new entity identity");
        long newGeneration = Integer.toUnsignedLong(replacement.getId());
        if (newGeneration == oldGeneration) helper.fail("replacement body generation was not changed");

        CrewTeamService restored = new CrewTeamService(helper.getLevel().getServer(), data);
        TeamTaskSnapshot restoredTask = ((TeamLedgerSnapshot) restored.snapshot().get("ledger")).tasks().stream()
                .filter(task -> task.task().id().value().equals(work.taskId())).findFirst().orElseThrow();
        if (restoredTask.state() != TeamTaskState.RECONCILE_REQUIRED || restoredTask.reservedResources().isEmpty()) {
            helper.fail("replacement restore released or completed the unknown WAIT lease: " + restoredTask);
        }
        var replacementAction = replacement.executor().arbiter().snapshot(oldActionId).orElseThrow();
        if (replacementAction.state() != ActionState.RECONCILE_REQUIRED
                || replacementAction.epoch().bodyGeneration() != oldGeneration
                || replacement.executor().arbiter().epoch().bodyGeneration() != newGeneration) {
            helper.fail("replacement action ledger did not preserve old evidence/new generation: action=" + replacementAction
                    + ", epoch=" + replacement.executor().arbiter().epoch());
        }
        expectRejected(() -> restored.execute(work.taskId(), replacement.companionId(), newGeneration, work.intentId()),
                "replacement body executed a task owned by the old generation");
        replacement.executor().retask();
        restored.ownerRetiredWork(replacement);
        TeamTaskSnapshot afterRetask = ((TeamLedgerSnapshot) restored.snapshot().get("ledger")).tasks().stream()
                .filter(task -> task.task().id().value().equals(work.taskId())).findFirst().orElseThrow();
        if (afterRetask.state() != TeamTaskState.RECONCILE_REQUIRED
                || !afterRetask.reservedResources().equals(restoredTask.reservedResources())) {
            helper.fail("retask treated an idle replacement as evidence for old work: " + afterRetask);
        }
        if (replacement.executor().arbiter().journal().stream().noneMatch(receipt -> receipt.id().equals(oldActionId)
                && receipt.state() == ActionState.RECONCILE_REQUIRED)) {
            helper.fail("replacement journal lost the old action tombstone");
        }
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 240, batch = "hearthcrew_p2_team")
    public static void ownerRetiredWorkReleasesLeaseWithoutInventoryRollback(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        int initialInventory = count(body);
        CrewWorldData data = new CrewWorldData();
        CrewTeamService service = new CrewTeamService(helper.getLevel().getServer(), data);
        CrewTeamService.Work oldWork = work("p2-retire-old", body, BodyOrder.Kind.WAIT, null, null, 100, null);
        CrewTeamService.Work nextWork = work("p2-retire-next", body, BodyOrder.Kind.WAIT, null, null, 1, null);
        if (service.propose(oldWork).decision() != TeamDecision.ACCEPTED) helper.fail("old fixture proposal was not accepted");
        expectRejected(() -> service.execute(oldWork.taskId(), body.companionId(), oldWork.bodyGeneration(), oldWork.intentId() + "-late"), "wrong intent was accepted before retirement");
        if (!body.executor().arbiter().journal().isEmpty()) helper.fail("wrong intent reached BodyExecutor");
        service.execute(oldWork.taskId(), body.companionId(), oldWork.bodyGeneration(), oldWork.intentId());
        try { service.ownerRetiredWork(body); helper.fail("ownerRetiredWork accepted a still-active body"); }
        catch (IllegalStateException expected) { }
        body.executor().retask();
        service.ownerRetiredWork(body);
        TeamTaskSnapshot retired = ((TeamLedgerSnapshot) service.snapshot().get("ledger")).tasks().stream()
                .filter(task -> task.task().id().value().equals(oldWork.taskId())).findFirst().orElseThrow();
        if (retired.state() != TeamTaskState.CANCELLED || !retired.reservedResources().isEmpty()) helper.fail("retired task did not release its lease: " + retired);
        if (count(body) != initialInventory) helper.fail("owner retirement changed inventory: before=" + initialInventory + ", after=" + count(body));

        if (service.propose(nextWork).decision() != TeamDecision.ACCEPTED) helper.fail("new proposal after owner retirement was not accepted");
        Object nextReceipt = service.execute(nextWork.taskId(), body.companionId(), nextWork.bodyGeneration(), nextWork.intentId());
        if (!(nextReceipt instanceof io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?> receipt)
                || (receipt.decision() != ReceiptDecision.ACCEPTED && receipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY)) helper.fail("new task could not claim released lease: " + nextReceipt);
        var nextActionId = ((io.github.yufeiyufei888.hearthcrew.kernel.ActionReceipt<?>) nextReceipt).id();
        helper.startSequence().thenWaitUntil(() -> {
            service.tick();
            var action = body.executor().arbiter().snapshot(nextActionId);
            if (action.isEmpty() || action.get().state() != ActionState.COMPLETED) throw new GameTestAssertException("new WAIT did not execute after retirement: " + action);
            if (count(body) != initialInventory) throw new GameTestAssertException("new task wrote back inventory unexpectedly");
            TeamTaskSnapshot next = ((TeamLedgerSnapshot) service.snapshot().get("ledger")).tasks().stream()
                    .filter(task -> task.task().id().value().equals(nextWork.taskId())).findFirst().orElseThrow();
            if (next.state() != TeamTaskState.COMPLETED) throw new GameTestAssertException("new task did not reach COMPLETED: " + next.state());
        }).thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_team")
    public static void corruptTeamArchiveIsPreservedAndFailsClosed(GameTestHelper helper) {
        CrewWorldData data = new CrewWorldData();
        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("version", 1);
        corrupt.putByteArray("payload", "{not-json".getBytes(StandardCharsets.UTF_8));
        data.storeTeamArchive(corrupt);
        CrewTeamService service = new CrewTeamService(helper.getLevel().getServer(), data);
        Object state = service.snapshot().get("recoveryInvalid");
        if (!Boolean.TRUE.equals(state)) helper.fail("corrupt archive did not enter recoveryInvalid state");
        if (!corrupt.equals(data.teamArchive())) helper.fail("corrupt archive was overwritten");
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewTeamService.Work work = work("p2-corrupt-propose", body, BodyOrder.Kind.WAIT, null, null, 1, null);
        try { service.propose(work); helper.fail("corrupt archive accepted a new proposal"); }
        catch (IllegalStateException expected) { }
        helper.succeed();
    }

    private static CrewTeamService isolatedService(GameTestHelper helper) {
        return new CrewTeamService(helper.getLevel().getServer(), new CrewWorldData());
    }

    private static CrewTeamService.Work work(String taskId, CompanionEntity body, BodyOrder.Kind kind, BlockPos position,
                                             UUID target, int count, String resource) {
        return new CrewTeamService.Work(taskId, taskId + "-action", body.companionId(), Integer.toUnsignedLong(body.getId()),
                body.level().dimension().location().toString(), kind,
                position == null ? null : new CrewTeamService.Position(position.getX(), position.getY(), position.getZ()),
                target, count, resource, "P2 isolated team fixture");
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static int count(CompanionEntity body) {
        return body.inventory().countItem(Items.OAK_PLANKS);
    }

    private static long distinctActionIds(CompanionEntity body) {
        return body.executor().arbiter().journal().stream().map(receipt -> receipt.id().value()).distinct().count();
    }

    private static void expectRejected(Runnable action, String message) {
        try { action.run(); throw new GameTestAssertException(message); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
