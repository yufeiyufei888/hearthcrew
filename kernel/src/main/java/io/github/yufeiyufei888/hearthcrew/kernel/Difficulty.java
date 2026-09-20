package io.github.yufeiyufei888.hearthcrew.kernel;

/** The four vanilla difficulty policies relevant to food and starvation. */
public enum Difficulty {
    PEACEFUL(true, Integer.MAX_VALUE),
    EASY(false, 10),
    NORMAL(false, 1),
    HARD(false, 0);

    private final boolean peaceful;
    private final int starvationMinimumHealth;

    Difficulty(boolean peaceful, int starvationMinimumHealth) {
        this.peaceful = peaceful;
        this.starvationMinimumHealth = starvationMinimumHealth;
    }

    public boolean peaceful() { return peaceful; }
    public int starvationMinimumHealth() { return starvationMinimumHealth; }
}
