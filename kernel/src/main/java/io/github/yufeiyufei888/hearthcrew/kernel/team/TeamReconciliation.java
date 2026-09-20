package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;

/** Explicit post-interruption evidence. Unknown effects are never replayed automatically. */
public record TeamReconciliation(
        TeamTaskState resolvedState,
        String evidenceFingerprint,
        boolean effectConfirmed,
        WorldEpoch epoch,
        long gameTick,
        String message) {
    public TeamReconciliation {
        if (resolvedState == null || !resolvedState.terminal() || resolvedState == TeamTaskState.RECONCILE_REQUIRED) {
            throw new IllegalArgumentException("reconciliation needs a terminal non-reconcile state");
        }
        if (evidenceFingerprint == null || evidenceFingerprint.isBlank()) throw new IllegalArgumentException("evidence fingerprint must not be blank");
        if (epoch == null) throw new NullPointerException("epoch");
        if (gameTick < 0) throw new IllegalArgumentException("reconciliation tick must be non-negative");
        message = message == null ? "" : message;
    }
}
