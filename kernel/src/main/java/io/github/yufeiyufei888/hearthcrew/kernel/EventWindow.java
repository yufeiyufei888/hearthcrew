package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, replayable event window for one logical event stream.
 *
 * <p>Registration is independent from transport queueing.  A registered
 * event remains retained until an explicit ACK removes it, while each
 * connection may reserve only a bounded number of events.  This lets a new
 * connection drain a large unacknowledged backlog in batches instead of
 * overflowing its socket queue during reconnect.</p>
 *
 * <p>The payload is retained by reference.  Callers that use mutable payloads
 * must pass an immutable snapshot or clone it before registration.</p>
 */
public final class EventWindow<T> {
    public record Pending<T>(String eventId, long sequence, T payload) {
        public Pending {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(payload, "payload");
            if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
            if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    private final int maxPending;
    private final int maxInFlightPerSession;
    private final LinkedHashMap<String, Pending<T>> pending = new LinkedHashMap<>();
    private final Map<Long, Set<String>> inFlight = new LinkedHashMap<>();

    public EventWindow(int maxPending, int maxInFlightPerSession) {
        if (maxPending < 1) throw new IllegalArgumentException("maxPending must be positive");
        if (maxInFlightPerSession < 1) throw new IllegalArgumentException("maxInFlightPerSession must be positive");
        if (maxInFlightPerSession > maxPending) throw new IllegalArgumentException("in-flight window cannot exceed pending capacity");
        this.maxPending = maxPending;
        this.maxInFlightPerSession = maxInFlightPerSession;
    }

    public EventWindow() { this(4096, 64); }

    /** Register a stable event identity. Returns false only when at capacity. */
    public synchronized boolean register(String eventId, long sequence, T payload) {
        Objects.requireNonNull(payload, "payload");
        Pending<T> existing = pending.get(eventId);
        if (existing != null) {
            if (existing.sequence() != sequence) throw new IllegalArgumentException("event identity has conflicting sequence");
            return true;
        }
        if (pending.size() >= maxPending) return false;
        pending.put(eventId, new Pending<>(eventId, sequence, payload));
        return true;
    }

    public synchronized boolean contains(String eventId) { return pending.containsKey(eventId); }

    public synchronized boolean atCapacity() { return pending.size() >= maxPending; }

    /** Reserve the next stable events for a session without removing them. */
    public synchronized List<Pending<T>> reserve(long sessionEpoch) {
        return reserve(sessionEpoch, maxInFlightPerSession);
    }

    public synchronized List<Pending<T>> reserve(long sessionEpoch, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Set<String> reserved = inFlight.computeIfAbsent(sessionEpoch, ignored -> new LinkedHashSet<>());
        int remaining = Math.min(limit, maxInFlightPerSession - reserved.size());
        if (remaining <= 0) return List.of();
        List<Pending<T>> result = new ArrayList<>(remaining);
        for (Pending<T> event : pending.values()) {
            if (reserved.contains(event.eventId())) continue;
            reserved.add(event.eventId());
            result.add(event);
            if (result.size() >= remaining) break;
        }
        return List.copyOf(result);
    }

    /** Release a reservation when it could not enter the transport queue. */
    public synchronized boolean release(long sessionEpoch, String eventId) {
        Set<String> reserved = inFlight.get(sessionEpoch);
        if (reserved == null) return false;
        boolean removed = reserved.remove(eventId);
        if (reserved.isEmpty()) inFlight.remove(sessionEpoch);
        return removed;
    }

    /** ACK removes an event from every session's in-flight set and retention. */
    public synchronized boolean acknowledge(String eventId) {
        boolean removed = pending.remove(eventId) != null;
        for (Set<String> reserved : inFlight.values()) reserved.remove(eventId);
        inFlight.values().removeIf(Set::isEmpty);
        return removed;
    }

    /** Drop reservations for a dead transport; retained events remain replayable. */
    public synchronized void resetSession(long sessionEpoch) { inFlight.remove(sessionEpoch); }

    public synchronized int pendingCount() { return pending.size(); }

    public synchronized int inFlightCount(long sessionEpoch) { return inFlight.getOrDefault(sessionEpoch, Set.of()).size(); }

    public synchronized List<Pending<T>> pendingSnapshot() { return List.copyOf(pending.values()); }
}
