package io.github.yufeiyufei888.hearthcrew.kernel.team;

/** Result code for a TeamLedger operation. */
public enum TeamDecision {
    ACCEPTED,
    IDEMPOTENT_REPLAY,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    STALE,
    RECONCILE_REQUIRED,
    REJECTED_BUSY,
    REJECTED_RESOURCE_CONFLICT,
    REJECTED_STALE,
    REJECTED_ID_CONFLICT,
    REJECTED_INVALID,
    REJECTED_CAPACITY,
    REJECTED_REVALIDATION;
}
