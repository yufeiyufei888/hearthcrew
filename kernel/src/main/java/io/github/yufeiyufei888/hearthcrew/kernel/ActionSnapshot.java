package io.github.yufeiyufei888.hearthcrew.kernel;

/** Current action state, including the last checkpoint available for resume. */
public record ActionSnapshot<T, C>(
        ActionId id,
        ActionPriority priority,
        T payload,
        WorldEpoch epoch,
        ActionState state,
        long worldTick,
        long gameTick,
        C checkpoint,
        String message) {
    public ActionSnapshot {
        if (message == null) {
            message = "";
        }
    }
}
