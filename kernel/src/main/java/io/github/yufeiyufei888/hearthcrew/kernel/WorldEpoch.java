package io.github.yufeiyufei888.hearthcrew.kernel;

/**
 * Independent generations for world, controller session, and companion body.
 * Any mismatch makes an action request unsafe to apply.
 */
public record WorldEpoch(long worldGeneration, long sessionGeneration, long bodyGeneration) {
    public WorldEpoch {
        if (worldGeneration < 0 || sessionGeneration < 0 || bodyGeneration < 0) {
            throw new IllegalArgumentException("generations must be non-negative");
        }
    }

    public boolean matches(WorldEpoch other) {
        return equals(other);
    }
}
