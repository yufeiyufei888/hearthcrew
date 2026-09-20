package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionRecoveryTest {
    @Test void loadedIdentityCannotAcquireLeaseOrReplayEvenInNewEpoch() {
        var original = ActionRequest.withoutDeadline(ActionId.of("delivery-1"), ActionPriority.MISSION,
                "transfer:oak:4", new WorldEpoch(1, 1, 1), 10);
        var loaded = new ActionArbiter<String, String>(new WorldEpoch(1, 2, 7));
        loaded.restoreForReconciliation(List.of(original));
        assertTrue(loaded.activeActionId().isEmpty());
        var retry = loaded.submit(original);
        assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY, retry.decision());
        assertEquals(ActionState.RECONCILE_REQUIRED, retry.state());
        assertEquals(ActionState.RECONCILE_REQUIRED, loaded.start(original.id()).state());
        assertTrue(loaded.activeActionId().isEmpty());
        var changed = ActionRequest.withoutDeadline(original.id(), ActionPriority.MISSION, "transfer:oak:8", loaded.epoch(), 0);
        assertEquals(ReceiptDecision.REJECTED_ID_CONFLICT, loaded.submit(changed).decision());
    }
    @Test void invalidRecoveryIsAtomicAndCannotReplaceLiveLease() {
        var request = ActionRequest.withoutDeadline(ActionId.of("a"), ActionPriority.MISSION, "mine", new WorldEpoch(1, 1, 1), 0);
        var arbiter = new ActionArbiter<String, String>(request.epoch(), 40, 2);
        assertThrows(IllegalArgumentException.class, () -> arbiter.restoreForReconciliation(List.of(request, request)));
        assertTrue(arbiter.journal().isEmpty());
        assertEquals(ReceiptDecision.ACCEPTED, arbiter.submit(request).decision());
        assertThrows(IllegalStateException.class, () -> arbiter.restoreForReconciliation(List.of(request)));
        assertEquals(request.id(), arbiter.activeActionId().orElseThrow());
    }
}
