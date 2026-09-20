package io.github.yufeiyufei888.hearthcrew.kernel.team;

/** Idempotent identity for one observed resource settlement. */
public record TeamSettlementId(String value) {
    public TeamSettlementId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("settlement id must not be blank");
        value = value.trim();
    }

    public static TeamSettlementId of(String value) { return new TeamSettlementId(value); }
}
