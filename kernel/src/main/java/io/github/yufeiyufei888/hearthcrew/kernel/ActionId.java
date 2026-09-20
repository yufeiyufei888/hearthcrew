package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.Objects;

/** A caller-owned immutable identity for one logical action. */
public record ActionId(String value) {
    public ActionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("action id must not be blank");
        }
        value = value.trim();
    }

    public static ActionId of(String value) {
        return new ActionId(value);
    }
}
