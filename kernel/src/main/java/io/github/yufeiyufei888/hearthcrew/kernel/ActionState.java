package io.github.yufeiyufei888.hearthcrew.kernel;

public enum ActionState {
    ACCEPTED,
    RUNNING,
    SUSPENDED,
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
