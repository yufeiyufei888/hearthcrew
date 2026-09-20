package io.github.yufeiyufei888.hearthcrew.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ActionArbiterTest {
    private static final WorldEpoch EPOCH = new WorldEpoch(1, 1, 1);

    private static ActionRequest<String> request(String id, ActionPriority priority, String payload, long tick) {
        return ActionRequest.withoutDeadline(ActionId.of(id), priority, payload, EPOCH, tick);
    }

    @Test
    void repeatedIdenticalSubmitIsIdempotentAndDifferentPayloadIsRejected() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        ActionRequest<String> first = request("a", ActionPriority.MISSION, "mine:oak", 0);
        assertEquals(ReceiptDecision.ACCEPTED, arbiter.submit(first).decision());
        assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY, arbiter.submit(first).decision());
        assertEquals(ReceiptDecision.REJECTED_ID_CONFLICT,
                arbiter.submit(request("a", ActionPriority.MISSION, "mine:stone", 0)).decision());
        assertEquals(1, arbiter.activeSnapshot().orElseThrow().id().value().length());
    }

    @Test
    void concurrentSubmitsKeepOneExecutionLease() throws Exception {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ActionReceipt<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                calls.add(() -> arbiter.submit(request("race-" + n, ActionPriority.MISSION, "same", 0)));
            }
            List<Future<ActionReceipt<String>>> results = executor.invokeAll(calls);
            long accepted = results.stream().map(this::get).filter(r -> r.decision() == ReceiptDecision.ACCEPTED).count();
            assertEquals(1, accepted);
            assertTrue(arbiter.activeActionId().isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleEpochAgeAndDeadlineAreRejected() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH, 2, 10);
        assertEquals(ReceiptDecision.REJECTED_STALE,
                arbiter.submit(new ActionRequest<>(ActionId.of("epoch"), ActionPriority.MISSION, "x",
                        new WorldEpoch(2, 1, 1), 0, -1)).decision());
        arbiter.advanceTick(5, false);
        assertEquals(ReceiptDecision.REJECTED_STALE,
                arbiter.submit(request("old", ActionPriority.MISSION, "x", 0)).decision());
        assertEquals(ReceiptDecision.REJECTED_DEADLINE,
                arbiter.submit(new ActionRequest<>(ActionId.of("deadline"), ActionPriority.MISSION, "x",
                        EPOCH, 3, 4)).decision());
    }

    @Test
    void higherPrioritySuspendsAndResumeRequiresCheckpointValidation() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        ActionId mission = ActionId.of("mission");
        arbiter.submit(request("mission", ActionPriority.MISSION, "mine", 0));
        arbiter.start(mission);
        arbiter.checkpoint(mission, "at-tree");
        ActionReceipt<String> safety = arbiter.submit(request("safety", ActionPriority.SAFETY, "guard", 0));
        assertEquals(ReceiptDecision.ACCEPTED, safety.decision());
        assertEquals(ActionState.SUSPENDED, arbiter.snapshot(mission).orElseThrow().state());
        assertEquals(List.of(mission), arbiter.suspendedActionIds());
        arbiter.start(ActionId.of("safety"));
        arbiter.finish(ActionId.of("safety"), ActionState.COMPLETED, "threat gone");
        assertEquals(ReceiptDecision.STALE, arbiter.resume(mission, checkpoint -> false).decision());
        assertEquals(ActionState.STALE, arbiter.snapshot(mission).orElseThrow().state());
        assertTrue(arbiter.suspendedActionIds().isEmpty());
    }

    @Test
    void terminalStatesAndModelTimeoutAreExplicit() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        arbiter.submit(request("healthy", ActionPriority.MISSION, "work", 0));
        arbiter.start(ActionId.of("healthy"));
        assertEquals(ReceiptDecision.MODEL_TIMEOUT, arbiter.modelTimeout(ActionId.of("healthy"), "deadline").decision());
        assertEquals(ActionState.RUNNING, arbiter.activeSnapshot().orElseThrow().state());
        assertEquals(ActionState.PARTIAL,
                arbiter.finish(ActionId.of("healthy"), ActionState.PARTIAL, "some blocks delivered").state());
        assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY,
                arbiter.finish(ActionId.of("healthy"), ActionState.COMPLETED, "late result").decision());
    }

    @Test
    void pauseFreezesGameClockAndDeadlineThenExpires() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        ActionRequest<String> request = new ActionRequest<>(ActionId.of("timed"), ActionPriority.MISSION, "x",
                EPOCH, 0, 3);
        arbiter.submit(request);
        arbiter.advanceTick(10, true);
        assertEquals(0, arbiter.gameTick());
        assertEquals(ActionState.ACCEPTED, arbiter.snapshot(request.id()).orElseThrow().state());
        arbiter.advanceTick(11, false);
        arbiter.advanceTick(15, false);
        assertEquals(ActionState.EXPIRED, arbiter.snapshot(request.id()).orElseThrow().state());
    }

    @Test
    void epochAdvanceStalesEachInFlightActionAndClearsLease() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH);
        arbiter.submit(request("one", ActionPriority.MISSION, "x", 0));
        arbiter.start(ActionId.of("one"));
        List<ActionReceipt<String>> stale = arbiter.advanceEpoch(new WorldEpoch(1, 2, 1), "reconnected");
        assertEquals(1, stale.size());
        assertEquals(ActionState.STALE, stale.get(0).state());
        assertTrue(arbiter.activeActionId().isEmpty());
    }

    @Test
    void boundedJournalRetainsInFlightRecords() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH, 40, 20);
        for (int i = 0; i < 10; i++) {
            ActionId id = ActionId.of("done-" + i);
            arbiter.submit(request(id.value(), ActionPriority.SAFETY, "s", 0));
            arbiter.start(id);
            arbiter.finish(id, ActionState.COMPLETED, "done");
        }
        arbiter.submit(request("active", ActionPriority.MISSION, "x", 0));
        arbiter.start(ActionId.of("active"));
        assertNotNull(arbiter.snapshot(ActionId.of("active")).orElse(null));
        assertTrue(arbiter.journal().size() >= 2, "in-flight receipts are never evicted");
        assertEquals(ActionState.RUNNING, arbiter.snapshot(ActionId.of("active")).orElseThrow().state());
    }

    @Test
    void boundedCapacityRetainsTerminalIdsAndFailsClosed() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH, 40, 2);
        for (String id : List.of("a", "b")) {
            arbiter.submit(request(id, ActionPriority.MISSION, "x", 0));
            arbiter.start(ActionId.of(id));
            arbiter.finish(ActionId.of(id), ActionState.COMPLETED, "done");
        }
        assertEquals(ReceiptDecision.REJECTED_CAPACITY,
                arbiter.submit(request("c", ActionPriority.MISSION, "x", 0)).decision());
        assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY,
                arbiter.submit(request("a", ActionPriority.MISSION, "x", 0)).decision());
    }

    @Test
    void transportJournalBudgetDoesNotExhaustPersistentActionIdentities() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH, 40, 2, 4);
        for (String id : List.of("a", "b", "c")) {
            assertEquals(ReceiptDecision.ACCEPTED, arbiter.submit(request(id, ActionPriority.MISSION, "x", 0)).decision());
            arbiter.start(ActionId.of(id));
            arbiter.finish(ActionId.of(id), ActionState.COMPLETED, "done");
        }
        assertEquals(2, arbiter.journal().size());
        assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY, arbiter.submit(request("a", ActionPriority.MISSION, "x", 0)).decision());
        assertEquals(ActionState.COMPLETED, arbiter.snapshot(ActionId.of("a")).orElseThrow().state());
    }

    @Test
    void runningEventFloodDoesNotGrowJournalWithoutBound() {
        ActionArbiter<String, String> arbiter = new ActionArbiter<>(EPOCH, 40, 3);
        ActionId id = ActionId.of("flood");
        arbiter.submit(request("flood", ActionPriority.MISSION, "x", 0));
        arbiter.start(id);
        for (int i = 0; i < 100_000; i++) {
            arbiter.checkpoint(id, "checkpoint-" + i);
            arbiter.modelTimeout(id, "model event");
        }
        assertEquals(3, arbiter.journal().size());
        assertEquals(ActionState.RUNNING, arbiter.snapshot(id).orElseThrow().state());
    }

    private ActionReceipt<String> get(Future<ActionReceipt<String>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
