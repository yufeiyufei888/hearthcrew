package io.github.yufeiyufei888.hearthcrew.kernel.team;

import java.util.Map;
import java.util.Objects;

/** Actual before/after quantities observed by the Minecraft server thread. */
public record TeamSettlementObservation(
        TeamSettlementId id,
        String fingerprint,
        Map<ResourceKey, Long> before,
        Map<ResourceKey, Long> after,
        long gameTick) {
    public TeamSettlementObservation {
        Objects.requireNonNull(id, "id");
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("observation fingerprint must not be blank");
        fingerprint = fingerprint.trim();
        before = TeamSettlementRequest.immutableQuantities(before, false);
        after = TeamSettlementRequest.immutableQuantities(after, false);
        if (gameTick < 0) throw new IllegalArgumentException("observation tick must be non-negative");
    }
}
