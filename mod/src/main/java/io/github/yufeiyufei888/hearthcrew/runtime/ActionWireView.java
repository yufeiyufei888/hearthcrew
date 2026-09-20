package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;

/** Only protocol values cross the wire; checkpoints keep native objects server-side. */
public final class ActionWireView {
    private ActionWireView() {}
    public static JsonObject read(ActionSnapshot<BodyOrder, BodyExecutor.Checkpoint> action, Gson json) {
        var result = new JsonObject();
        result.add("id", json.toJsonTree(action.id()));
        result.addProperty("priority", action.priority().name());
        result.add("payload", json.toJsonTree(action.payload()));
        result.add("epoch", json.toJsonTree(action.epoch()));
        result.addProperty("state", action.state().name());
        result.addProperty("worldTick", action.worldTick());
        result.addProperty("gameTick", action.gameTick());
        result.addProperty("message", action.message());
        result.addProperty("checkpointAvailable", action.checkpoint() != null);
        // ItemStack/BlockState include registries and Optional fields. Neither
        // reflecting them nor dropping the actual resume checkpoint is valid.
        return result;
    }
}
