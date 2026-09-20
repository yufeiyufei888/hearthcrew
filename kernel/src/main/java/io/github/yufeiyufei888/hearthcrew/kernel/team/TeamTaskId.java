package io.github.yufeiyufei888.hearthcrew.kernel.team;

/** Idempotent identity for one shared team task. */
public record TeamTaskId(String value) {
    public TeamTaskId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("task id must not be blank");
        value = value.trim();
    }

    public static TeamTaskId of(String value) { return new TeamTaskId(value); }
}
