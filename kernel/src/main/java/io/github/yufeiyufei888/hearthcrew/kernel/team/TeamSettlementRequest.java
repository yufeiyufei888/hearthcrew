package io.github.yufeiyufei888.hearthcrew.kernel.team;

import java.util.Map;
import java.util.Objects;

/** Prepared settlement intent; quantities are checked against server observations. */
public record TeamSettlementRequest(
        TeamSettlementId id,
        TeamTaskId taskId,
        String fingerprint,
        Map<ResourceKey, Long> expectedDelta) {
    public TeamSettlementRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("settlement fingerprint must not be blank");
        fingerprint = fingerprint.trim();
        expectedDelta = immutableQuantities(expectedDelta, true);
        if (expectedDelta.isEmpty()) throw new IllegalArgumentException("settlement expected delta must not be empty");
    }

    static Map<ResourceKey, Long> immutableQuantities(Map<ResourceKey, Long> source, boolean allowNegative) {
        Objects.requireNonNull(source, "quantities");
        java.util.LinkedHashMap<ResourceKey, Long> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<ResourceKey, Long> entry : source.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "quantity key");
            Long value = Objects.requireNonNull(entry.getValue(), "quantity value");
            if (!allowNegative && value < 0) throw new IllegalArgumentException("quantity cannot be negative");
            copy.put(entry.getKey(), value);
        }
        return Map.copyOf(copy);
    }
}
