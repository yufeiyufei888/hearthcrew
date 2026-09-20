package io.github.yufeiyufei888.hearthcrew.kernel.team;

/** Stable logical identity for one companion body. */
public record TeamBodyId(String value) {
    public TeamBodyId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("body id must not be blank");
        value = value.trim();
    }

    public static TeamBodyId of(String value) { return new TeamBodyId(value); }
}
