package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable view consumed by the mod adapter and diagnostics. */
public record TeamTaskSnapshot(
        TeamTask task,
        TeamTaskState state,
        TeamBodyId assignedBody,
        long bodyGeneration,
        boolean started,
        long leaseExpiresAt,
        TeamCheckpoint checkpoint,
        Set<ResourceKey> reservedResources,
        Map<ResourceKey, Long> reservedQuantities,
        List<TeamSettlementId> settlements,
        WorldEpoch observedEpoch,
        long updatedGameTick,
        String message) {
    public TeamTaskSnapshot {
        if (task == null || state == null || observedEpoch == null) throw new NullPointerException("task/state/epoch");
        if (bodyGeneration < 0 || leaseExpiresAt < 0 || updatedGameTick < 0) throw new IllegalArgumentException("snapshot generations/ticks must be non-negative");
        reservedResources = Set.copyOf(reservedResources == null ? Set.of() : reservedResources);
        reservedQuantities = Map.copyOf(reservedQuantities == null ? Map.of() : reservedQuantities);
        settlements = List.copyOf(settlements == null ? List.of() : settlements);
        message = message == null ? "" : message;
    }
}
