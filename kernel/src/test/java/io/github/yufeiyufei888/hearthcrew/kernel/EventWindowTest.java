package io.github.yufeiyufei888.hearthcrew.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventWindowTest {
    @Test
    void backlogIsReservedInStableBatchesAndAckReleasesTheWindow() {
        EventWindow<String> window = new EventWindow<>(4096, 64);
        for (int i = 0; i < 200; i++) assertTrue(window.register("event-" + i, i, "payload-" + i));

        List<EventWindow.Pending<String>> first = window.reserve(10);
        assertEquals(64, first.size());
        assertEquals("event-0", first.get(0).eventId());
        assertEquals("event-63", first.get(63).eventId());
        assertEquals(64, window.inFlightCount(10));
        assertTrue(window.reserve(10).isEmpty(), "a session cannot exceed its in-flight window");

        assertTrue(window.acknowledge("event-0"));
        List<EventWindow.Pending<String>> next = window.reserve(10, 1);
        assertEquals(List.of("event-64"), next.stream().map(EventWindow.Pending::eventId).toList());
        assertEquals(64, window.inFlightCount(10));
        assertEquals(199, window.pendingCount());
    }

    @Test
    void reconnectResetsReservationsButRetainsStableEventIdsAndOrder() {
        EventWindow<String> window = new EventWindow<>(4096, 32);
        for (int i = 0; i < 130; i++) assertTrue(window.register("e" + i, i, "v" + i));
        List<String> first = window.reserve(1).stream().map(EventWindow.Pending::eventId).toList();
        assertEquals(32, first.size());
        window.resetSession(1);
        List<String> replay = window.reserve(2).stream().map(EventWindow.Pending::eventId).toList();
        assertEquals(first, replay);
        assertEquals(130, window.pendingCount());
        assertFalse(window.acknowledge("missing"));
    }

    @Test
    void moreThanOneTransportBatchEventuallyDrainsByAcknowledgement() {
        EventWindow<String> window = new EventWindow<>(4096, 64);
        for (int i = 0; i < 130; i++) assertTrue(window.register("event-" + i, i, "payload"));
        int acknowledged = 0;
        while (window.pendingCount() > 0) {
            List<EventWindow.Pending<String>> batch = window.reserve(22);
            assertFalse(batch.isEmpty());
            for (EventWindow.Pending<String> event : batch) {
                assertTrue(window.acknowledge(event.eventId()));
                acknowledged++;
            }
        }
        assertEquals(130, acknowledged);
        assertEquals(0, window.pendingCount());
        assertEquals(0, window.inFlightCount(22));
    }

    @Test
    void fullRetentionFailsClosedWithoutChangingExistingIdentity() {
        EventWindow<String> window = new EventWindow<>(2, 1);
        assertTrue(window.register("a", 7, "A"));
        assertTrue(window.register("b", 8, "B"));
        assertFalse(window.register("c", 9, "C"));
        assertTrue(window.register("a", 7, "A-retry"));
        assertThrows(IllegalArgumentException.class, () -> window.register("a", 8, "conflict"));
        assertEquals(List.of("a", "b"), window.pendingSnapshot().stream().map(EventWindow.Pending::eventId).toList());
    }

    @Test
    void fourThousandNinetySixRetentionBoundaryDoesNotAdvanceByItself() {
        EventWindow<Integer> window = new EventWindow<>(4096, 64);
        for (int i = 0; i < 4096; i++) assertTrue(window.register("e" + i, i, i));
        assertTrue(window.atCapacity());
        assertFalse(window.register("overflow", 4096, 4096));
        assertTrue(window.acknowledge("e0"));
        assertTrue(window.register("overflow", 4096, 4096));
    }
}
