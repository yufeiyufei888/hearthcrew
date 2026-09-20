package io.github.yufeiyufei888.hearthcrew.kernel;

/** Immutable journal receipt. It is a snapshot, never a command. */
public record ActionReceipt<T>(
        long sequence,
        ActionId id,
        ActionState state,
        ReceiptDecision decision,
        ActionPriority priority,
        T payload,
        WorldEpoch epoch,
        long worldTick,
        long gameTick,
        String message,
        ActionId relatedAction) {
    public ActionReceipt {
        if (sequence < 0 || worldTick < 0 || gameTick < 0) {
            throw new IllegalArgumentException("receipt counters must be non-negative");
        }
        if (message == null) {
            message = "";
        }
    }
}
