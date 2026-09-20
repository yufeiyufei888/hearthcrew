package io.github.yufeiyufei888.hearthcrew.kernel.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamLedgerTest {
    private static final WorldEpoch EPOCH = new WorldEpoch(7, 3, 0);
    private static final String WORLD = "world-7";
    private static final String DIMENSION = "minecraft:overworld";

    private static TeamTask task(String id, ActionPriority priority, ResourceKey key, long quantity) {
        return new TeamTask(TeamTaskId.of(id), "fp:" + id, WORLD, DIMENSION, priority, EPOCH,
                Set.of(key), Map.of(key, quantity), 3, id);
    }

    @Test
    void containerWholeReservationConflictsWithSlotsButDifferentSlotsCanProceed() {
        ResourceKey whole = ResourceKey.container(WORLD, DIMENSION, 1, 64, 1);
        ResourceKey slot0 = ResourceKey.containerSlot(WORLD, DIMENSION, 1, 64, 1, 0);
        ResourceKey slot1 = ResourceKey.containerSlot(WORLD, DIMENSION, 1, 64, 1, 1);
        assertTrue(whole.conflicts(slot0));
        assertTrue(slot0.conflicts(whole));
        assertTrue(slot0.conflicts(slot0));
        assertTrue(!slot0.conflicts(slot1));

        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask first = task("slot0", ActionPriority.MISSION, slot0, 1);
        TeamTask second = task("slot1", ActionPriority.MISSION, slot1, 1);
        TeamTask wholeTask = task("whole", ActionPriority.MISSION, whole, 1);
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(first, 0).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(second, 0).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(first.id(), TeamBodyId.of("a"), 0, 0, Map.of()).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(second.id(), TeamBodyId.of("b"), 0, 0, Map.of()).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(wholeTask, 0).decision());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.claim(wholeTask.id(), TeamBodyId.of("c"), 0, 0, Map.of()).decision());
    }

    @Test
    void threeBodiesCompeteAtomicallyAndPersonalCannotOverwriteMissionLease() {
        ResourceKey tree = ResourceKey.block(WORLD, DIMENSION, 2, 65, 2);
        TeamTask mission = task("mission", ActionPriority.MISSION, tree, 1);
        TeamLedger ledger = new TeamLedger(EPOCH);
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(mission, 0).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(mission.id(), TeamBodyId.of("guard"), 0, 0, Map.of()).decision());

        TeamTask personal = task("personal", ActionPriority.PERSONAL, tree, 1);
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(personal, 0).decision());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.claim(personal.id(), TeamBodyId.of("gatherer"), 0, 0, Map.of()).decision());
        assertEquals(TeamDecision.IDEMPOTENT_REPLAY, ledger.claim(mission.id(), TeamBodyId.of("guard"), 0, 0, Map.of()).decision());
    }

    @Test
    void itemReservationsSubtractAlreadyReservedQuantityAndRequireObservedAvailability() {
        ResourceKey logs = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_log");
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask six = task("six", ActionPriority.SUPPLY, logs, 6);
        TeamTask five = task("five", ActionPriority.SUPPLY, logs, 5);
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(six, 0).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(five, 0).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(six.id(), TeamBodyId.of("a"), 0, 0, Map.of(logs, 10L)).decision());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.claim(five.id(), TeamBodyId.of("b"), 0, 0, Map.of(logs, 10L)).decision());

        TeamLedger another = new TeamLedger(EPOCH);
        TeamTask sixAgain = task("six-again", ActionPriority.SUPPLY, logs, 6);
        TeamTask four = task("four", ActionPriority.SUPPLY, logs, 4);
        another.submit(sixAgain, 0); another.submit(four, 0);
        assertEquals(TeamDecision.ACCEPTED, another.claim(sixAgain.id(), TeamBodyId.of("a"), 0, 0, Map.of(logs, 10L)).decision());
        assertEquals(TeamDecision.ACCEPTED, another.claim(four.id(), TeamBodyId.of("b"), 0, 0, Map.of(logs, 10L)).decision());
    }

    @Test
    void pauseFreezesLeasesAndStartedExpiryRetainsReservationForReconciliation() {
        ResourceKey chest = ResourceKey.container(WORLD, DIMENSION, 4, 64, 4);
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask task = task("paused", ActionPriority.MISSION, chest, 1);
        ledger.submit(task, 0); ledger.claim(task.id(), TeamBodyId.of("a"), 0, 0, Map.of());
        ledger.advanceTick(100, true);
        assertEquals(TeamTaskState.CLAIMED, ledger.taskSnapshot(task.id()).state());
        ledger.advanceTick(2, false);
        assertEquals(TeamTaskState.CLAIMED, ledger.taskSnapshot(task.id()).state());
        ledger.advanceTick(3, false);
        assertEquals(TeamTaskState.EXPIRED, ledger.taskSnapshot(task.id()).state());

        TeamTask running = task("running", ActionPriority.MISSION, chest, 1);
        TeamTask other = task("other", ActionPriority.MISSION, chest, 1);
        ledger.submit(running, 3); ledger.claim(running.id(), TeamBodyId.of("a"), 0, 3, Map.of()); ledger.start(running.id(), 3);
        ledger.submit(other, 3);
        ledger.advanceTick(6, false);
        assertEquals(TeamTaskState.RECONCILE_REQUIRED, ledger.taskSnapshot(running.id()).state());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.claim(other.id(), TeamBodyId.of("b"), 0, 6, Map.of()).decision());
    }

    @Test
    void partialSettlementIsObservedOnceAndKeepsResourceExclusiveUntilReconciled() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:ender_pearl");
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask transfer = task("transfer", ActionPriority.MISSION, stock, 4);
        TeamTask competing = task("competing", ActionPriority.SUPPLY, stock, 1);
        ledger.submit(transfer, 0); ledger.submit(competing, 0);
        ledger.claim(transfer.id(), TeamBodyId.of("a"), 0, 0, Map.of(stock, 10L));
        TeamSettlementRequest request = new TeamSettlementRequest(TeamSettlementId.of("settle-1"), transfer.id(), "transfer:4", Map.of(stock, -4L));
        assertEquals(TeamDecision.ACCEPTED, ledger.prepareSettlement(request, 0).decision());
        TeamSettlementObservation partial = new TeamSettlementObservation(request.id(), request.fingerprint(), Map.of(stock, 10L), Map.of(stock, 7L), 1);
        assertEquals(TeamDecision.PARTIAL, ledger.settle(partial).decision());
        assertEquals(TeamDecision.REJECTED_BUSY, ledger.claim(competing.id(), TeamBodyId.of("a"), 0, 1, Map.of(stock, 10L)).decision());
        assertEquals(TeamDecision.REJECTED_STALE,
                ledger.reconcile(transfer.id(), new TeamReconciliation(TeamTaskState.COMPLETED, "wrong-generation", true, new WorldEpoch(7, 3, 99), 2, "bad evidence")).decision());
        assertEquals(TeamDecision.REJECTED_REVALIDATION,
                ledger.reconcile(transfer.id(), new TeamReconciliation(TeamTaskState.COMPLETED, "unconfirmed", false, EPOCH, 2, "no world confirmation")).decision());
        TeamReconciliation reconciliation = new TeamReconciliation(TeamTaskState.COMPLETED, "transfer:3-observed", true, EPOCH, 2, "partial transfer confirmed");
        assertEquals(TeamDecision.COMPLETED, ledger.reconcile(transfer.id(), reconciliation).decision());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(competing.id(), TeamBodyId.of("b"), 0, 2, Map.of(stock, 10L)).decision());
        assertEquals(TeamDecision.IDEMPOTENT_REPLAY, ledger.settle(partial).decision());
    }

    @Test
    void settlementRequiresANonEmptyObservedDelta() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_log");
        assertThrows(IllegalArgumentException.class, () -> new TeamSettlementRequest(
                TeamSettlementId.of("empty-delta"), TeamTaskId.of("task"), "empty-fp", Map.of()));
    }

    @Test
    void oneTaskCannotPrepareTwoUnfinishedSettlementsEvenWhenKeysDoNotOverlap() {
        ResourceKey logs = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_log");
        ResourceKey planks = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_planks");
        TeamTask task = new TeamTask(TeamTaskId.of("single-settlement"), "fp:single-settlement", WORLD, DIMENSION,
                ActionPriority.SUPPLY, EPOCH, Set.of(logs, planks), Map.of(logs, 2L, planks, 2L), 3, "single");
        TeamLedger ledger = new TeamLedger(EPOCH);
        ledger.submit(task, 0);
        ledger.claim(task.id(), TeamBodyId.of("a"), 0, 0, Map.of(logs, 2L, planks, 2L));
        TeamSettlementRequest first = new TeamSettlementRequest(TeamSettlementId.of("single-a"), task.id(), "single-a-fp", Map.of(logs, -1L));
        TeamSettlementRequest second = new TeamSettlementRequest(TeamSettlementId.of("single-b"), task.id(), "single-b-fp", Map.of(planks, -1L));
        assertEquals(TeamDecision.ACCEPTED, ledger.prepareSettlement(first, 0).decision());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.prepareSettlement(second, 0).decision());
    }

    @Test
    void completedSettlementTombstoneRestoresAfterReservationRelease() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:diamond");
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask task = task("completed-settlement", ActionPriority.MISSION, stock, 2);
        ledger.submit(task, 0); ledger.claim(task.id(), TeamBodyId.of("a"), 0, 0, Map.of(stock, 2L));
        TeamSettlementRequest request = new TeamSettlementRequest(TeamSettlementId.of("completed-settlement-id"), task.id(), "diamond-transfer", Map.of(stock, -2L));
        ledger.prepareSettlement(request, 0);
        ledger.settle(new TeamSettlementObservation(request.id(), request.fingerprint(), Map.of(stock, 2L), Map.of(stock, 0L), 1));
        TeamLedger restored = TeamLedger.restore(ledger.snapshot(), 8);
        assertEquals(TeamTaskState.COMPLETED, restored.taskSnapshot(task.id()).state());
        assertEquals(TeamSettlementState.COMPLETED, restored.settlementSnapshot(request.id()).state());
        assertTrue(restored.taskSnapshot(task.id()).reservedQuantities().isEmpty());
    }

    @Test
    void restoreTurnsUnknownActiveWorkIntoReconcileRequiredAndKeepsTombstones() {
        ResourceKey tree = ResourceKey.block(WORLD, DIMENSION, 8, 64, 8);
        TeamLedger original = new TeamLedger(EPOCH, 8);
        TeamTask active = task("active", ActionPriority.MISSION, tree, 1);
        TeamTask done = task("done", ActionPriority.MISSION, ResourceKey.block(WORLD, DIMENSION, 9, 64, 9), 1);
        original.submit(active, 0); original.claim(active.id(), TeamBodyId.of("a"), 0, 0, Map.of()); original.start(active.id(), 0);
        original.submit(done, 0); original.claim(done.id(), TeamBodyId.of("b"), 0, 0, Map.of()); original.finish(done.id(), TeamTaskState.COMPLETED, "observed");
        TeamLedger restored = TeamLedger.restore(original.snapshot(), 8);
        assertEquals(TeamTaskState.RECONCILE_REQUIRED, restored.taskSnapshot(active.id()).state());
        assertEquals(TeamTaskState.COMPLETED, restored.taskSnapshot(done.id()).state());
        assertNotNull(restored.taskSnapshot(active.id()).reservedResources());
        assertEquals(TeamDecision.IDEMPOTENT_REPLAY, restored.submit(active, 0).decision());
        TeamTask newTask = task("new", ActionPriority.MISSION, tree, 1);
        restored.submit(newTask, 0);
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT,
                restored.claim(newTask.id(), TeamBodyId.of("c"), 0, 0, Map.of()).decision());
    }

    @Test
    void bodyGenerationCanChangeWithoutStalingTeamTaskButRebindNeedsCheckpoint() {
        ResourceKey tree = ResourceKey.block(WORLD, DIMENSION, 10, 64, 10);
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask task = task("handoff", ActionPriority.MISSION, tree, 1);
        ledger.submit(task, 0); ledger.claim(task.id(), TeamBodyId.of("a"), 11, 0, Map.of()); ledger.start(task.id(), 0);
        ledger.advanceEpoch(new WorldEpoch(7, 3, 99), "body respawn");
        assertEquals(TeamTaskState.RUNNING, ledger.taskSnapshot(task.id()).state());
        ledger.bodyUnavailable(TeamBodyId.of("a"), "death");
        assertEquals(TeamTaskState.RECONCILE_REQUIRED, ledger.taskSnapshot(task.id()).state());
        TeamCheckpoint wrongFingerprint = new TeamCheckpoint("tree-still-there", Map.of("block", "oak_log"), new WorldEpoch(7, 3, 99), 2);
        assertEquals(TeamDecision.REJECTED_ID_CONFLICT, ledger.rebind(task.id(), TeamBodyId.of("a"), 99, wrongFingerprint, 2).decision());
        TeamCheckpoint checkpoint = new TeamCheckpoint(task.fingerprint(), Map.of("block", "oak_log"), new WorldEpoch(7, 3, 99), 2);
        assertEquals(TeamDecision.ACCEPTED, ledger.rebind(task.id(), TeamBodyId.of("a"), 99, checkpoint, 2).decision());
        assertEquals(TeamDecision.IDEMPOTENT_REPLAY, ledger.claim(task.id(), TeamBodyId.of("a"), 99, 2, Map.of()).decision());
    }

    @Test
    void olderDefinitionEpochSurvivesTwoRestoreRoundsButFutureEpochFailsClosed() {
        ResourceKey activeKey = ResourceKey.block(WORLD, DIMENSION, 11, 64, 11);
        ResourceKey doneKey = ResourceKey.block(WORLD, DIMENSION, 12, 64, 12);
        TeamLedger ledger = new TeamLedger(EPOCH, 16);
        TeamTask active = task("old-active", ActionPriority.MISSION, activeKey, 1);
        TeamTask done = task("old-done", ActionPriority.MISSION, doneKey, 1);
        ledger.submit(active, 0); ledger.claim(active.id(), TeamBodyId.of("a"), 7, 0, Map.of()); ledger.start(active.id(), 0);
        ledger.submit(done, 0); ledger.claim(done.id(), TeamBodyId.of("b"), 8, 0, Map.of()); ledger.finish(done.id(), TeamTaskState.COMPLETED, "observed");
        TeamLedgerSnapshot afterSessionChange = ledger.advanceEpoch(new WorldEpoch(7, 4, 0), "reopen");
        TeamLedger firstRestore = TeamLedger.restore(afterSessionChange, 16);
        TeamLedger secondRestore = TeamLedger.restore(firstRestore.snapshot(), 16);
        assertEquals(TeamTaskState.RECONCILE_REQUIRED, secondRestore.taskSnapshot(active.id()).state());
        assertEquals(TeamTaskState.COMPLETED, secondRestore.taskSnapshot(done.id()).state());
        assertEquals(3, secondRestore.taskSnapshot(active.id()).task().epoch().sessionGeneration());
        assertEquals(4, secondRestore.taskSnapshot(active.id()).observedEpoch().sessionGeneration());

        TeamTask future = new TeamTask(TeamTaskId.of("future"), "future-fp", WORLD, DIMENSION, ActionPriority.MISSION,
                new WorldEpoch(7, 5, 0), Set.of(ResourceKey.block(WORLD, DIMENSION, 13, 64, 13)), 3, "future");
        TeamTaskSnapshot futureSnapshot = new TeamTaskSnapshot(future, TeamTaskState.PLANNED, null, 0, false, 0,
                null, Set.of(), Map.of(), java.util.List.of(), new WorldEpoch(7, 4, 0), 0, "");
        assertThrows(IllegalArgumentException.class, () -> TeamLedger.restore(new TeamLedgerSnapshot(new WorldEpoch(7, 4, 0), 0,
                java.util.List.of(futureSnapshot), java.util.List.of()), 16));
    }

    @Test
    void terminalReleaseClearsQuantityAndPartialKeepsBodyLease() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:iron_ingot");
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask first = task("release", ActionPriority.SUPPLY, stock, 6);
        TeamTask second = task("reuse", ActionPriority.SUPPLY, stock, 6);
        ledger.submit(first, 0); ledger.submit(second, 0);
        ledger.claim(first.id(), TeamBodyId.of("a"), 0, 0, Map.of(stock, 6L));
        ledger.finish(first.id(), TeamTaskState.COMPLETED, "observed");
        assertTrue(ledger.taskSnapshot(first.id()).reservedQuantities().isEmpty());
        assertEquals(TeamDecision.ACCEPTED, ledger.claim(second.id(), TeamBodyId.of("b"), 0, 0, Map.of(stock, 6L)).decision());

        TeamLedger partialLedger = new TeamLedger(EPOCH);
        TeamTask partial = task("partial-body", ActionPriority.MISSION, stock, 2);
        TeamTask other = task("other-body", ActionPriority.MISSION, ResourceKey.block(WORLD, DIMENSION, 30, 64, 30), 1);
        partialLedger.submit(partial, 0); partialLedger.submit(other, 0);
        partialLedger.claim(partial.id(), TeamBodyId.of("a"), 0, 0, Map.of(stock, 2L));
        TeamSettlementRequest settlement = new TeamSettlementRequest(TeamSettlementId.of("partial-body-settlement"), partial.id(), "partial-body-fp", Map.of(stock, -2L));
        partialLedger.prepareSettlement(settlement, 0);
        partialLedger.settle(new TeamSettlementObservation(settlement.id(), settlement.fingerprint(), Map.of(stock, 2L), Map.of(stock, 1L), 1));
        assertEquals(TeamDecision.REJECTED_BUSY, partialLedger.claim(other.id(), TeamBodyId.of("a"), 1, 1, Map.of()).decision());
    }

    @Test
    void fullSemanticsAndOverlappingPreparedSettlementsAreNotIdempotent() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:gold_ingot");
        TeamLedger ledger = new TeamLedger(EPOCH);
        TeamTask original = task("semantic", ActionPriority.SUPPLY, stock, 4);
        TeamTask changedDescription = new TeamTask(original.id(), original.fingerprint(), original.worldId(), original.dimension(),
                original.priority(), original.epoch(), original.resources(), original.reservedQuantities(), original.leaseTicks(), "changed");
        assertEquals(TeamDecision.ACCEPTED, ledger.submit(original, 0).decision());
        assertEquals(TeamDecision.REJECTED_ID_CONFLICT, ledger.submit(changedDescription, 0).decision());
        ledger.claim(original.id(), TeamBodyId.of("a"), 0, 0, Map.of(stock, 4L));
        TeamSettlementRequest first = new TeamSettlementRequest(TeamSettlementId.of("settle-a"), original.id(), "same", Map.of(stock, -2L));
        TeamSettlementRequest overlapping = new TeamSettlementRequest(TeamSettlementId.of("settle-b"), original.id(), "other", Map.of(stock, -1L));
        assertEquals(TeamDecision.ACCEPTED, ledger.prepareSettlement(first, 0).decision());
        assertEquals(TeamDecision.REJECTED_RESOURCE_CONFLICT, ledger.prepareSettlement(overlapping, 0).decision());
        TeamSettlementRequest altered = new TeamSettlementRequest(first.id(), original.id(), first.fingerprint(), Map.of(stock, -1L));
        assertEquals(TeamDecision.REJECTED_ID_CONFLICT, ledger.prepareSettlement(altered, 0).decision());
    }

    @Test
    void restoreRejectsChangedReservationsAndCrossLinkedSettlement() {
        ResourceKey stock = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:coal");
        TeamTask task = task("malformed", ActionPriority.SUPPLY, stock, 2);
        TeamTaskSnapshot changedQuantity = new TeamTaskSnapshot(task, TeamTaskState.CLAIMED, TeamBodyId.of("a"), 0, false, 3,
                null, Set.of(stock), Map.of(stock, 1L), java.util.List.of(), EPOCH, 0, "");
        assertThrows(IllegalArgumentException.class, () -> TeamLedger.restore(new TeamLedgerSnapshot(EPOCH, 0, java.util.List.of(changedQuantity), java.util.List.of()), 8));

        TeamTaskSnapshot noSettlementLink = new TeamTaskSnapshot(task, TeamTaskState.CLAIMED, TeamBodyId.of("a"), 0, false, 3,
                null, Set.of(stock), Map.of(stock, 2L), java.util.List.of(), EPOCH, 0, "");
        TeamSettlementRequest request = new TeamSettlementRequest(TeamSettlementId.of("cross-link"), task.id(), "fp", Map.of(stock, -1L));
        TeamSettlementSnapshot settlement = new TeamSettlementSnapshot(request, TeamSettlementState.PREPARED, Map.of(), Map.of(), 0, "");
        assertThrows(IllegalArgumentException.class, () -> TeamLedger.restore(new TeamLedgerSnapshot(EPOCH, 0,
                java.util.List.of(noSettlementLink), java.util.List.of(settlement)), 8));
    }

    @Test
    void restoreRejectsMultipleUnfinishedSettlementsForOneTask() {
        ResourceKey logs = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_log");
        ResourceKey planks = ResourceKey.itemQuantity(WORLD, DIMENSION, "team", "minecraft:oak_planks");
        TeamTask task = new TeamTask(TeamTaskId.of("malformed-settlements"), "fp:malformed-settlements", WORLD, DIMENSION,
                ActionPriority.SUPPLY, EPOCH, Set.of(logs, planks), Map.of(logs, 2L, planks, 2L), 3, "malformed");
        TeamSettlementRequest first = new TeamSettlementRequest(TeamSettlementId.of("malformed-a"), task.id(), "a", Map.of(logs, -1L));
        TeamSettlementRequest second = new TeamSettlementRequest(TeamSettlementId.of("malformed-b"), task.id(), "b", Map.of(planks, -1L));
        TeamTaskSnapshot snapshot = new TeamTaskSnapshot(task, TeamTaskState.RUNNING, TeamBodyId.of("a"), 0, true, 3,
                null, Set.of(logs, planks), Map.of(logs, 2L, planks, 2L), java.util.List.of(first.id(), second.id()), EPOCH, 0, "");
        TeamSettlementSnapshot firstSnapshot = new TeamSettlementSnapshot(first, TeamSettlementState.PREPARED, Map.of(), Map.of(), 0, "");
        TeamSettlementSnapshot secondSnapshot = new TeamSettlementSnapshot(second, TeamSettlementState.PREPARED, Map.of(), Map.of(), 0, "");
        assertThrows(IllegalArgumentException.class, () -> TeamLedger.restore(new TeamLedgerSnapshot(EPOCH, 0,
                java.util.List.of(snapshot), java.util.List.of(firstSnapshot, secondSnapshot)), 8));
    }
}
