package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable server-owned task definition. */
public record TeamTask(
        TeamTaskId id,
        String fingerprint,
        String worldId,
        String dimension,
        ActionPriority priority,
        WorldEpoch epoch,
        Set<ResourceKey> resources,
        Map<ResourceKey, Long> reservedQuantities,
        long leaseTicks,
        String description) {
    public TeamTask {
        Objects.requireNonNull(id, "id");
        fingerprint = required(fingerprint, "fingerprint");
        worldId = required(worldId, "worldId");
        dimension = required(dimension, "dimension");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(epoch, "epoch");
        resources = Set.copyOf(Objects.requireNonNull(resources, "resources"));
        reservedQuantities = immutableQuantities(reservedQuantities, resources);
        for (ResourceKey resource : resources) {
            if (!worldId.equals(resource.worldId()) || !dimension.equals(resource.dimension())) {
                throw new IllegalArgumentException("task resource belongs to another world or dimension");
            }
        }
        ResourceKey[] resourceArray = resources.toArray(ResourceKey[]::new);
        for (int i = 0; i < resourceArray.length; i++) {
            for (int j = i + 1; j < resourceArray.length; j++) {
                if (resourceArray[i].conflicts(resourceArray[j])) throw new IllegalArgumentException("task contains overlapping resource keys");
            }
        }
        if (leaseTicks < 1) throw new IllegalArgumentException("leaseTicks must be positive");
        description = description == null ? "" : description;
    }

    /** Compatibility constructor: every non-quantity resource reserves one unit. */
    public TeamTask(TeamTaskId id, String fingerprint, String worldId, String dimension,
                    ActionPriority priority, WorldEpoch epoch, Set<ResourceKey> resources,
                    long leaseTicks, String description) {
        this(id, fingerprint, worldId, dimension, priority, epoch, resources,
                defaultQuantities(resources), leaseTicks, description);
    }

    private static Map<ResourceKey, Long> defaultQuantities(Set<ResourceKey> resources) {
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (ResourceKey resource : resources) result.put(resource, 1L);
        return result;
    }

    private static Map<ResourceKey, Long> immutableQuantities(Map<ResourceKey, Long> values, Set<ResourceKey> resources) {
        Objects.requireNonNull(values, "reservedQuantities");
        if (!values.keySet().equals(resources)) throw new IllegalArgumentException("reserved quantities must cover exactly task resources");
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey, Long> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1) throw new IllegalArgumentException("reserved quantity must be positive");
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value.trim();
    }
}
