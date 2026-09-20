package io.github.yufeiyufei888.hearthcrew.backend;

import java.util.UUID;
import java.util.Set;
import java.util.Objects;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread boundary shared by native bodies. Capabilities describe wired adapters only. */
public interface CompanionBackend {
    record Identity(String world, UUID companion, long generation) {}
    record Snapshot(Identity identity, String actionId, long actionGeneration, String state, long activeTicks,
                    long pausedTicks, String result) {}
    record Request(Identity identity, String actionId, BodyOrder order, ActionPriority priority) {
        public Request {
            Objects.requireNonNull(identity); Objects.requireNonNull(order); Objects.requireNonNull(priority);
            if(actionId==null||!actionId.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid action identity");
        }
    }
    record Acceptance(boolean reused,String actionId,long generation,String state,String result) {}
    String backendId();
    Set<BodyOrder.Kind> capabilities();
    Acceptance dispatch(Request request);
    ServerPlayer body();
    Snapshot snapshot();
    java.util.Optional<Snapshot> lookup(String actionId);
    java.util.Map<String,Object> diagnostics();
    java.util.Map<String,Object> execution();
    void pause();
    void resume();
    void cancel();
    void close();
}
