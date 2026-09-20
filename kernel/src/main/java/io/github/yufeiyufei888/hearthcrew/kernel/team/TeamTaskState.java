package io.github.yufeiyufei888.hearthcrew.kernel.team;

public enum TeamTaskState {
    PLANNED,
    CLAIMED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    STALE,
    RECONCILE_REQUIRED;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, STALE, RECONCILE_REQUIRED -> true;
            default -> false;
        };
    }
}
