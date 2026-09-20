package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.Objects;

/** Immutable vanilla-style food state independent of Minecraft classes. */
public final class FoodState {
    public static final int MAX_FOOD = 20;
    public static final float EXHAUSTION_THRESHOLD = 4.0F;
    public static final float MAX_EXHAUSTION = 40.0F;

    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int foodTickTimer;
    private final int peacefulClock;

    public FoodState(int foodLevel, float saturation, float exhaustion) {
        this(foodLevel, saturation, exhaustion, 0, 0);
    }

    private FoodState(int foodLevel, float saturation, float exhaustion,
                      int foodTickTimer, int peacefulClock) {
        if (foodLevel < 0 || foodLevel > MAX_FOOD) {
            throw new IllegalArgumentException("food level must be between 0 and 20");
        }
        if (!Float.isFinite(saturation) || saturation < 0 || saturation > 20.0F) {
            throw new IllegalArgumentException("saturation must be finite and between 0 and 20");
        }
        if (!Float.isFinite(exhaustion) || exhaustion < 0 || exhaustion > MAX_EXHAUSTION) {
            throw new IllegalArgumentException("exhaustion must be finite and between 0 and 40");
        }
        if (foodTickTimer < 0 || peacefulClock < 0) {
            throw new IllegalArgumentException("food timers must be non-negative");
        }
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.foodTickTimer = foodTickTimer;
        this.peacefulClock = peacefulClock;
    }

    public record TimerState(int foodTickTimer, int peacefulClock) {
        public TimerState {
            if (foodTickTimer < 0 || peacefulClock < 0) throw new IllegalArgumentException("timers must be non-negative");
        }
    }

    public static FoodState full() { return new FoodState(20, 5.0F, 0.0F); }
    public static FoodState empty() { return new FoodState(0, 0.0F, 0.0F); }
    public int foodLevel() { return foodLevel; }
    public float saturation() { return saturation; }
    public float exhaustion() { return exhaustion; }
    public int foodTickTimer() { return foodTickTimer; }
    public int peacefulClock() { return peacefulClock; }
    public TimerState timerState() { return new TimerState(foodTickTimer, peacefulClock); }

    /** Restore timers loaded from the companion's persistent data. */
    public FoodState restoreTimers(int restoredFoodTickTimer, int restoredPeacefulClock) {
        return new FoodState(foodLevel, saturation, exhaustion,
                restoredFoodTickTimer, restoredPeacefulClock);
    }

    /** Add nutrition and actual saturation points from 1.21 food properties. */
    public FoodState eat(int nutrition, float saturationPoints) {
        if (nutrition < 0 || !Float.isFinite(saturationPoints) || saturationPoints < 0) {
            throw new IllegalArgumentException("food values must be non-negative and finite");
        }
        int food = Math.min(MAX_FOOD, foodLevel + nutrition);
        float sat = Math.min(food, saturation + saturationPoints);
        return new FoodState(food, sat, exhaustion, foodTickTimer, peacefulClock);
    }

    /** Accumulate exhaustion, capped at 40; the tick method clears one unit later. */
    public FoodState addExhaustion(float amount) {
        if (!Float.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("exhaustion amount must be non-negative and finite");
        }
        return new FoodState(foodLevel, saturation,
                Math.min(MAX_EXHAUSTION, exhaustion + amount), foodTickTimer, peacefulClock);
    }

    /** Compatibility name for adapters that call the operation consumeExhaustion. */
    public FoodState consumeExhaustion(float amount) { return addExhaustion(amount); }

    /**
     * Apply one server tick. The supplied world tick is accepted for adapter
     * tracing only; timers intentionally accrue from state, not tick modulo.
     */
    public FoodTickResult tick(long ignoredWorldTick, Difficulty difficulty, boolean naturalRegeneration,
                               float health, float maxHealth) {
        Objects.requireNonNull(difficulty, "difficulty");
        if (ignoredWorldTick < 0 || !Float.isFinite(health) || !Float.isFinite(maxHealth)
                || health < 0 || maxHealth <= 0 || health > maxHealth) {
            throw new IllegalArgumentException("invalid tick or health values");
        }

        int food = foodLevel;
        float sat = saturation;
        float exhaustionAfter = exhaustion;
        // FoodData processes only one exhaustion unit per tick and uses a
        // strict >4 check. Peaceful may drain saturation but never food here.
        if (exhaustionAfter > EXHAUSTION_THRESHOLD) {
            exhaustionAfter -= EXHAUSTION_THRESHOLD;
            if (sat > 0.0F) sat = Math.max(0.0F, sat - 1.0F);
            else if (!difficulty.peaceful()) food = Math.max(0, food - 1);
        }

        float nextHealth = health;
        boolean regenerated = false;
        boolean starved = false;
        boolean peacefulRefill = false;
        int nextFoodTimer;
        int nextPeacefulClock;

        // Player.aiStep's peaceful supplements are separate from FoodData's
        // shared timer: food +1 every 10 ticks and saturation/health +1 every
        // 20 ticks while natural regeneration is enabled.
        if (difficulty.peaceful() && naturalRegeneration) {
            nextPeacefulClock = peacefulClock + 1;
            if (nextPeacefulClock % 10 == 0 && food < MAX_FOOD) {
                food++;
                peacefulRefill = true;
            }
            if (nextPeacefulClock % 20 == 0) {
                if (sat < 20.0F) {
                    sat = Math.min(20.0F, sat + 1.0F);
                    peacefulRefill = true;
                }
                if (health < maxHealth) {
                    nextHealth = Math.min(maxHealth, health + 1.0F);
                    regenerated = nextHealth > health;
                }
            }
        } else {
            nextPeacefulClock = 0;
        }

        // Peaceful aiStep runs before FoodData.tick in the player lifecycle;
        // use its post-refill health and food values for this same tick.
        boolean fast = naturalRegeneration && sat > 0.0F && nextHealth < maxHealth && food >= MAX_FOOD;
        boolean slow = naturalRegeneration && food >= 18 && nextHealth < maxHealth && !fast;
        if (fast || slow) {
            nextFoodTimer = foodTickTimer + 1;
            int interval = fast ? 10 : 80;
            if (nextFoodTimer >= interval) {
                float heal = fast ? Math.min(sat, 6.0F) / 6.0F : 1.0F;
                nextHealth = Math.min(maxHealth, nextHealth + heal);
                regenerated = regenerated || nextHealth > health;
                // Fast regeneration adds f exhaustion; slow regeneration adds 6.
                exhaustionAfter = Math.min(MAX_EXHAUSTION,
                        exhaustionAfter + (fast ? Math.min(sat, 6.0F) : 6.0F));
                nextFoodTimer = 0;
            }
        } else if (food <= 0) {
            nextFoodTimer = foodTickTimer + 1;
            if (nextFoodTimer >= 80) {
                if (health > 10.0F || difficulty == Difficulty.HARD
                        || (health > 1.0F && difficulty == Difficulty.NORMAL)) {
                    // A starvation hit is one point of damage. EASY/NORMAL
                    // decide whether the hit is permitted; they do not round
                    // fractional health up to their minimum threshold.
                    nextHealth = Math.max(0.0F, health - 1.0F);
                    starved = nextHealth < health;
                }
                nextFoodTimer = 0;
            }
        } else {
            nextFoodTimer = 0;
        }

        FoodState next = new FoodState(food, Math.min(20.0F, sat), exhaustionAfter,
                nextFoodTimer, nextPeacefulClock);
        return new FoodTickResult(next, nextHealth, nextHealth - health,
                regenerated, starved, peacefulRefill);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FoodState state)) return false;
        return foodLevel == state.foodLevel
                && Float.compare(saturation, state.saturation) == 0
                && Float.compare(exhaustion, state.exhaustion) == 0
                && foodTickTimer == state.foodTickTimer
                && peacefulClock == state.peacefulClock;
    }

    @Override
    public int hashCode() {
        return Objects.hash(foodLevel, saturation, exhaustion, foodTickTimer, peacefulClock);
    }

    @Override
    public String toString() {
        return "FoodState[foodLevel=" + foodLevel + ", saturation=" + saturation
                + ", exhaustion=" + exhaustion + "]";
    }
}
