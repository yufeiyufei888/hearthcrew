package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.Objects;

/** Immutable action submission. Payloads should themselves be immutable. */
public record ActionRequest<T>(
        ActionId id,
        ActionPriority priority,
        T payload,
        WorldEpoch epoch,
        long submittedGameTick,
        long deadlineGameTick) {
    public ActionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(epoch, "epoch");
        if (submittedGameTick < 0) {
            throw new IllegalArgumentException("submitted game tick must be non-negative");
        }
        if (deadlineGameTick < -1 || (deadlineGameTick >= 0 && deadlineGameTick < submittedGameTick)) {
            throw new IllegalArgumentException("deadline must be -1 or no earlier than submission");
        }
    }

    public static <T> ActionRequest<T> withoutDeadline(
            ActionId id, ActionPriority priority, T payload, WorldEpoch epoch, long submittedGameTick) {
        return new ActionRequest<>(id, priority, payload, epoch, submittedGameTick, -1);
    }
}
