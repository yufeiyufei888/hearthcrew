package io.github.yufeiyufei888.hearthcrew.kernel.team;

/** Immutable operation result; adapters inspect decision before applying world changes. */
public record TeamResult<T>(TeamDecision decision, T value, String message) {
    public TeamResult {
        if (decision == null) throw new NullPointerException("decision");
        if (message == null) message = "";
    }
}
