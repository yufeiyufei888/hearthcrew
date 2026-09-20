package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.Map;
import java.util.Objects;

/** Evidence checkpoint that the Minecraft adapter can revalidate on resume. */
public record TeamCheckpoint(String fingerprint, Map<String, String> facts, WorldEpoch epoch, long gameTick) {
    public TeamCheckpoint {
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("checkpoint fingerprint must not be blank");
        facts = Map.copyOf(Objects.requireNonNull(facts, "facts"));
        Objects.requireNonNull(epoch, "epoch");
        if (gameTick < 0) throw new IllegalArgumentException("checkpoint tick must be non-negative");
    }
}
