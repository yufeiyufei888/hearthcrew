package io.github.yufeiyufei888.hearthcrew.kernel.team;

import java.util.Map;

public record TeamSettlementSnapshot(
        TeamSettlementRequest request,
        TeamSettlementState state,
        Map<ResourceKey, Long> before,
        Map<ResourceKey, Long> after,
        long updatedGameTick,
        String message) {
    public TeamSettlementSnapshot {
        if (request == null || state == null) throw new NullPointerException("request/state");
        before = Map.copyOf(before == null ? Map.of() : before);
        after = Map.copyOf(after == null ? Map.of() : after);
        if (updatedGameTick < 0) throw new IllegalArgumentException("settlement tick must be non-negative");
        message = message == null ? "" : message;
    }
}
