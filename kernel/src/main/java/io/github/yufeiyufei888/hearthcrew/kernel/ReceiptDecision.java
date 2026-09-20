package io.github.yufeiyufei888.hearthcrew.kernel;

/** Explains the arbiter decision represented by a receipt. */
public enum ReceiptDecision {
    ACCEPTED,
    IDEMPOTENT_REPLAY,
    STARTED,
    SUSPENDED,
    RESUMED,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    STALE,
    RECONCILE_REQUIRED,
    MODEL_TIMEOUT,
    REJECTED_BUSY,
    REJECTED_STALE,
    REJECTED_DEADLINE,
    REJECTED_ID_CONFLICT,
    REJECTED_INVALID,
    REJECTED_REVALIDATION,
    REJECTED_CAPACITY;
}
