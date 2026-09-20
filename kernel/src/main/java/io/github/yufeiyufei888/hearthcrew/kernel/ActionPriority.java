package io.github.yufeiyufei888.hearthcrew.kernel;

/** Higher priority work may preempt lower priority work. */
public enum ActionPriority {
    PERSONAL(10),
    SUPPLY(20),
    MISSION(30),
    OWNER(40),
    GUARD(50),
    SAFETY(60),
    STOP(70);

    private final int rank;

    ActionPriority(int rank) {
        this.rank = rank;
    }

    public boolean outranks(ActionPriority other) {
        return rank > other.rank;
    }
}
