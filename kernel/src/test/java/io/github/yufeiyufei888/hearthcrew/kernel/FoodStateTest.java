package io.github.yufeiyufei888.hearthcrew.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FoodStateTest {
    @Test
    void eatingClampsFoodAndUsesAbsoluteSaturationPoints() {
        FoodState state = new FoodState(15, 2.0F, 0.0F).eat(8, 6.0F);
        assertEquals(20, state.foodLevel());
        assertEquals(8.0F, state.saturation(), 0.0001F);
        assertEquals(20, state.eat(10, 100).foodLevel());
    }

    @Test
    void exhaustionAccumulatesToFortyAndOneTickClearsOnlyOneUnit() {
        FoodState accumulated = new FoodState(20, 2.0F, 3.0F).consumeExhaustion(5.0F);
        assertEquals(20, accumulated.foodLevel());
        assertEquals(2.0F, accumulated.saturation(), 0.0001F);
        assertEquals(8.0F, accumulated.exhaustion(), 0.0001F);
        FoodTickResult tick = accumulated.tick(0, Difficulty.NORMAL, false, 20.0F, 20.0F);
        assertEquals(1.0F, tick.state().saturation(), 0.0001F);
        assertEquals(4.0F, tick.state().exhaustion(), 0.0001F);
        FoodState capped = FoodState.empty().addExhaustion(100);
        assertEquals(40.0F, capped.exhaustion(), 0.0001F);
    }

    @Test
    void saturationRegeneratesAfterTenEligibleTicksWithFractionalHealAndExhaustion() {
        FoodState state = new FoodState(20, 4.0F, 0.0F);
        FoodTickResult result = null;
        for (int tick = 0; tick < 10; tick++) {
            result = state.tick(tick, Difficulty.NORMAL, true, 10.0F, 20.0F);
            state = result.state();
        }
        assertTrue(result.regenerated());
        assertEquals(10.666666F, result.health(), 0.0001F);
        assertEquals(4.0F, result.state().saturation(), 0.0001F);
        assertEquals(4.0F, result.state().exhaustion(), 0.0001F);
    }

    @Test
    void regenerationUsesContinuousSharedTimerAndFoodNineteenIsSlow() {
        FoodState state = new FoodState(19, 4.0F, 0.0F);
        FoodTickResult result = null;
        for (int tick = 0; tick < 79; tick++) {
            result = state.tick(79 + tick, Difficulty.NORMAL, true, 10.0F, 20.0F);
            state = result.state();
        }
        assertFalse(result.regenerated());
        result = state.tick(1000, Difficulty.NORMAL, true, result.health(), 20.0F);
        assertTrue(result.regenerated());
        assertEquals(11.0F, result.health(), 0.0001F);
    }

    @Test
    void starvationUsesContinuousTimerAndDifficultyMinimumHealth() {
        FoodState state = FoodState.empty();
        FoodTickResult result = null;
        for (int tick = 0; tick < 79; tick++) {
            result = state.tick(tick, Difficulty.EASY, true, 11.0F, 20.0F);
            state = result.state();
        }
        assertFalse(result.starved());
        result = state.tick(900, Difficulty.EASY, true, 11.0F, 20.0F);
        assertTrue(result.starved());
        assertEquals(10.0F, result.health(), 0.0001F);
        FoodState easyFractionState = FoodState.empty();
        FoodTickResult easyFraction = null;
        for (int tick = 0; tick < 80; tick++) {
            easyFraction = easyFractionState.tick(tick, Difficulty.EASY, true, 10.5F, 20.0F);
            easyFractionState = easyFraction.state();
        }
        assertEquals(9.5F, easyFraction.health(), 0.0001F);
        FoodState normalState = FoodState.empty();
        FoodTickResult normal = null;
        for (int tick = 0; tick < 80; tick++) {
            normal = normalState.tick(tick, Difficulty.NORMAL, true, 1.0F, 20.0F);
            normalState = normal.state();
        }
        assertFalse(normal.starved());
        FoodState normalFractionState = FoodState.empty();
        FoodTickResult normalFraction = null;
        for (int tick = 0; tick < 80; tick++) {
            normalFraction = normalFractionState.tick(tick, Difficulty.NORMAL, true, 1.5F, 20.0F);
            normalFractionState = normalFraction.state();
        }
        assertEquals(0.5F, normalFraction.health(), 0.0001F);
        FoodState hardState = FoodState.empty();
        FoodTickResult hard = null;
        for (int tick = 0; tick < 80; tick++) {
            hard = hardState.tick(tick, Difficulty.HARD, true, 1.0F, 20.0F);
            hardState = hard.state();
        }
        assertTrue(hard.starved());
        assertEquals(0.0F, hard.health(), 0.0001F);
    }

    @Test
    void peacefulRefillsFoodAndSaturationOnSeparateCadencesAndRegenerates() {
        FoodState state = new FoodState(3, 1.0F, 2.0F);
        FoodTickResult result = null;
        for (int tick = 0; tick < 20; tick++) {
            result = state.tick(tick, Difficulty.PEACEFUL, true, 19.0F, 20.0F);
            state = result.state();
        }
        assertTrue(result.regenerated());
        assertEquals(20.0F, result.health(), 0.0001F);
        assertEquals(5, result.state().foodLevel());
        assertEquals(2.0F, result.state().saturation(), 0.0001F);
        assertTrue(result.peacefulRefill());
    }

    @Test
    void timersRoundTripForSaveAndRestore() {
        FoodState state = FoodState.empty();
        for (int tick = 0; tick < 7; tick++) state = state.tick(tick, Difficulty.NORMAL, true, 20, 20).state();
        FoodState restored = state.restoreTimers(state.foodTickTimer(), state.peacefulClock());
        assertEquals(state.foodTickTimer(), restored.foodTickTimer());
        assertEquals(state.peacefulClock(), restored.peacefulClock());
        assertFalse(state.equals(new FoodState(state.foodLevel(), state.saturation(), state.exhaustion())));
    }
}
