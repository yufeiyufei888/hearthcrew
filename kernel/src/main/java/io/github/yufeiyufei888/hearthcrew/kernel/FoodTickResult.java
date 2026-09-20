package io.github.yufeiyufei888.hearthcrew.kernel;

/**
 * Result of one server food tick. The kernel reports health effects; the mob
 * applies the returned health value through its normal damage/heal hooks.
 */
public record FoodTickResult(
        FoodState state,
        float health,
        float healthChange,
        boolean regenerated,
        boolean starved,
        boolean peacefulRefill) {
    public FoodTickResult {
        if (state == null) throw new NullPointerException("state");
    }
}
