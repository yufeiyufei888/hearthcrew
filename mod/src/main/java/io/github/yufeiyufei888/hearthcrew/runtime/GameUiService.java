package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.backend.BackendWorld;
import io.github.yufeiyufei888.hearthcrew.network.UiNetwork;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread bookkeeping for the in-game UI/controller boundary.
 *
 * <p>This class never retains a player object or payload context. A request is
 * bound to the player's current entity id and a random per-connection session,
 * then resolved back through the server player list when a controller result
 * arrives.</p>
 */
public final class GameUiService implements AutoCloseable {
    public static final long PENDING_TIMEOUT_TICKS = 200L;
    public static final int MUTATION_TOMBSTONE_LIMIT = 512;

    private final MinecraftServer server;
    private final LongSupplier sessionEpoch;
    private final BooleanSupplier connected;
    private final Predicate<Map<String, Object>> publishEvent;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long gameTick;
    private int retainedMutations;
    private boolean closed;

    public GameUiService(MinecraftServer server, LongSupplier sessionEpoch,
                         BooleanSupplier connected, Predicate<Map<String, Object>> publishEvent) {
        this.server = Objects.requireNonNull(server, "server");
        this.sessionEpoch = Objects.requireNonNull(sessionEpoch, "sessionEpoch");
        this.connected = Objects.requireNonNull(connected, "connected");
        this.publishEvent = Objects.requireNonNull(publishEvent, "publishEvent");
    }

    /** Handles a payload already dispatched on the Minecraft server thread. */
    public Map<String, Object> request(ServerPlayer player, UiNetwork.Request request) {
        requireServerThread();
        if (closed) return result("CLOSED", "UI service is closed");
        if (player == null || request == null) return result("REJECTED", "player and request are required");
        if (!server.isSingleplayer() || !server.isSingleplayerOwner(player.getGameProfile())) {
            return result("REJECTED", "UI is available only to the singleplayer owner");
        }

        UUID ownerId = player.getUUID();
        Session session = sessionFor(player);
        Entry existing = entries.get(request.requestId());
        if (existing != null) {
            if (!existing.samePayload(request)) return result("ID_CONFLICT", "request id is bound to another payload");
            if (!existing.ownerId.equals(ownerId) || existing.entityId != player.getId()
                    || !existing.uiSession.equals(session.uiSession)) {
                return result("STALE", "request belongs to an earlier player session");
            }
            return resultFor(existing, "DUPLICATE", "request was already recorded");
        }

        List<CompanionEntity> bodies = CrewWorldData.liveCompanions(server);
        var backendBodies=BackendWorld.live(server);
        if (bodies.stream().anyMatch(body -> !ownerId.equals(body.ownerId()))||backendBodies.stream().anyMatch(b->!ownerId.equals(BackendWorld.roster(server).member(b.body().getUUID()).owner()))) {
            return result("REJECTED", "all live companions must belong to the requesting owner");
        }
        if (bodies.isEmpty() && backendBodies.isEmpty() && request.operation() != UiNetwork.Operation.STATUS && request.operation() != UiNetwork.Operation.JOIN) {
            return result("REJECTED", "no companion is available for this UI operation");
        }

        boolean mutation = request.operation() != UiNetwork.Operation.STATUS && request.operation() != UiNetwork.Operation.DETAIL && request.operation() != UiNetwork.Operation.HISTORY;
        if (mutation && retainedMutations >= MUTATION_TOMBSTONE_LIMIT) {
            return result("REJECTED", "UI mutation identity retention is full");
        }

        long epoch = currentEpoch();
        Entry entry = new Entry(request.requestId(), ownerId, player.getId(), session.uiSession,
                epoch, request.operation(), request.message(), gameTick, mutation);
        entries.put(entry.requestId, entry);
        if (mutation) retainedMutations++;

        if(request.operation()==UiNetwork.Operation.JOIN){
            try{int added=BackendWorld.enabled()?BackendWorld.join(player):CrewPlayers.get(server).addMissingFor(player);entry.state=State.COMPLETED;entry.completedTick=gameTick;return resultFor(entry,"JOINED",added==0?"三名伙伴已在游戏中，无需重复加入":"已加入 "+added+" 名伙伴，请刷新小队页");}
            catch(RuntimeException error){entry.state=State.REJECTED;return resultFor(entry,"REJECTED","加入伙伴未完成："+error.getMessage());}
        }
        boolean localControl = request.operation() == UiNetwork.Operation.AUTO_ON || request.operation() == UiNetwork.Operation.AUTO_OFF
                || request.operation() == UiNetwork.Operation.STANDBY;
        if (localControl) {
            try { for (CompanionEntity body : bodies) WorldEvents.controlBody(body, request.operation().wireName());for(var b:backendBodies)BackendWorld.control(server,b.body().getUUID(),request.operation().wireName()); }
            catch (RuntimeException failure) { entry.state = State.REJECTED; return resultFor(entry, "REJECTED", "本地控制未完成：" + failure.getMessage()); }
            if (!connected.getAsBoolean()) { entry.state = State.COMPLETED; entry.completedTick = gameTick; return resultFor(entry, "ACCEPTED_LOCAL_CONTROL", "本地设置已保存，控制器离线"); }
        }

        if (request.operation() == UiNetwork.Operation.STOP) {
            entry.localStop = stopOwnedBodies(bodies);
            for(var b:backendBodies){BackendWorld.control(server,b.body().getUUID(),"stop");entry.localStop++;}
            if (!connected.getAsBoolean()) {
                return resultFor(entry, "ACCEPTED_LOCAL_STOP", "controller is offline; local stop applied");
            }
        } else if (!connected.getAsBoolean()) {
            entry.state = State.REJECTED;
            return resultFor(entry, "REJECTED", "controller is offline");
        }

        if (!publish(entry)) {
            if (localControl) { entry.state = State.COMPLETED; entry.completedTick = gameTick; return resultFor(entry, "ACCEPTED_LOCAL_CONTROL", "本地设置已保存，控制器未接收通知"); }
            if (request.operation() != UiNetwork.Operation.STOP) entry.state = State.REJECTED;
            return resultFor(entry, request.operation() == UiNetwork.Operation.STOP
                    ? "ACCEPTED_LOCAL_STOP" : "REJECTED", "controller event was not accepted");
        }
        return resultFor(entry, "ACCEPTED", "UI request published");
    }

    /**
     * Payload entrypoint used by UiNetwork. Accepted requests wait for the
     * controller completion; every other result receives an immediate bounded
     * snapshot so a client cannot hang on a rejected or offline request.
     */
    public void handleRequest(ServerPlayer player, UiNetwork.Request request) {
        Map<String, Object> outcome = request(player, request);
        String decision = String.valueOf(outcome.get("decision"));
        if ("ACCEPTED".equals(decision)) return;
        if("JOINED".equals(decision)){
            var response=new com.google.gson.JsonObject();response.addProperty("ok",true);response.addProperty("message",String.valueOf(outcome.get("reason")));sendImmediate(player,request.requestId(),response.toString());return;
        }
        if ("ACCEPTED_LOCAL_CONTROL".equals(decision)) {
            sendImmediate(player, request.requestId(), "{\"ok\":true,\"operationResult\":{\"operation\":\""
                    + request.operation().wireName() + "\",\"accepted\":true},\"message\":\"本地设置已保存，控制器连接后生效\"}");
            return;
        }
        if ("ACCEPTED_LOCAL_STOP".equals(decision)) {
            int stopped = outcome.get("localStopped") instanceof Number number ? number.intValue() : 0;
            sendImmediate(player, request.requestId(),
                    "{\"ok\":true,\"operationResult\":{\"operation\":\"stop\",\"count\":"
                            + stopped + ",\"accepted\":true},\"message\":\"已本地急停，控制器离线\"}");
            return;
        }
        String message = "DUPLICATE".equals(decision) && "PENDING".equals(outcome.get("state"))
                ? "UI请求正在处理中"
                : "UI请求未被接受：" + String.valueOf(outcome.getOrDefault("reason", decision));
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        sendImmediate(player, request.requestId(),
                "{\"ok\":false,\"error\":\"" + escaped + "\"}");
    }

    /** Claims a recorded request before the bridge performs any controller command. */
    public Map<String, Object> claim(UUID requestId, UUID ownerId, UUID uiSession, long epoch) {
        requireServerThread();
        if (closed) return result("CLOSED", "UI service is closed");
        Entry entry = entries.get(requestId);
        if (entry == null) return result("STALE", "unknown UI request");
        if (!matchesCurrentSession(entry, ownerId, uiSession, epoch)) {
            return result("STALE", "UI request session or epoch is stale");
        }
        if (entry.state == State.CLAIMED || entry.state == State.COMPLETED) {
            return resultFor(entry, "DUPLICATE", "UI request was already claimed");
        }
        if (entry.state != State.PENDING || !entry.published) {
            return resultFor(entry, "STALE", "UI request is not claimable");
        }
        entry.state = State.CLAIMED;
        return resultFor(entry, "CLAIMED", "UI request claimed");
    }

    /** Completes only the exact claimed request and sends one client snapshot. */
    public Map<String, Object> complete(UUID requestId, UUID ownerId, UUID uiSession,
                                        long epoch, String json) {
        requireServerThread();
        if (closed) return result("CLOSED", "UI service is closed");
        final UiNetwork.Snapshot snapshot;
        try {
            snapshot = new UiNetwork.Snapshot(requestId, json);
        } catch (RuntimeException invalid) {
            return result("REJECTED", "snapshot JSON exceeds its UTF-8 bound");
        }
        Entry entry = entries.get(requestId);
        if (entry == null) return result("STALE", "unknown UI request");
        if (entry.state == State.COMPLETED) return resultFor(entry, "DUPLICATE", "completion was already delivered");
        if (entry.state != State.CLAIMED || !matchesCurrentSession(entry, ownerId, uiSession, epoch)) {
            return result("STALE", "completion does not match the current claimed session");
        }
        ServerPlayer player = currentPlayer(entry);
        if (player == null || !connected.getAsBoolean()) {
            return result("STALE", "original player session is no longer available");
        }
        try {
            UiNetwork.sendSnapshot(player, snapshot);
        } catch (RuntimeException sendFailure) {
            return result("REJECTED", "snapshot could not be sent");
        }
        entry.state = State.COMPLETED;
        entry.completedTick = gameTick;
        return resultFor(entry, "COMPLETED", "snapshot delivered");
    }

    /** Advances only tick-based expiry and deferred offline STOP publication. */
    public void tick() {
        requireServerThread();
        if (closed) return;
        gameTick++;
        long epoch = currentEpoch();
        boolean isConnected = connected.getAsBoolean();
        for (Entry entry : entries.values()) {
            if (entry.operation == UiNetwork.Operation.STOP && entry.localStop >= 0
                    && entry.state == State.PENDING && !entry.published && entry.epoch != epoch) {
                // A local emergency stop remains safe to forward once the next
                // controller session is ready; it never re-applies the stop.
                entry.epoch = epoch;
            } else if ((entry.state == State.PENDING || entry.state == State.CLAIMED)
                    && entry.epoch != epoch) {
                entry.state = State.STALE;
            } else if (entry.state == State.PENDING
                    && gameTick - entry.createdTick > PENDING_TIMEOUT_TICKS) {
                entry.state = State.STALE;
            }
            if (entry.operation == UiNetwork.Operation.STOP && !entry.published
                    && entry.state == State.PENDING && isConnected && entry.epoch == epoch) {
                publish(entry);
            }
        }
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.mutation && (entry.state == State.COMPLETED || entry.state == State.STALE
                    || entry.state == State.REJECTED)) iterator.remove();
        }
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
        sessions.clear();
        retainedMutations = 0;
    }

    private boolean publish(Entry entry) {
        if (entry.published || !connected.getAsBoolean()) return false;
        Map<String, Object> details = Map.of(
                "requestId", entry.requestId.toString(),
                "ownerId", entry.ownerId.toString(),
                "uiSession", entry.uiSession.toString(),
                "operation", entry.operation.wireName(),
                "message", entry.message);
        try {
            if (!publishEvent.test(details)) return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        entry.published = true;
        return true;
    }

    private void sendImmediate(ServerPlayer player, UUID requestId, String json) {
        if (player == null || closed) return;
        try {
            UiNetwork.sendSnapshot(player, new UiNetwork.Snapshot(requestId, json));
        } catch (RuntimeException ignored) {
            // The request has already been durably classified; do not retry a
            // client response as a second world/controller operation.
        }
    }

    private int stopOwnedBodies(List<CompanionEntity> bodies) {
        int stopped = 0;
        for (CompanionEntity body : bodies) {
            body.executor().stop("UI stop");
            CrewWorldData.get(server).touchControl(body.companionId());
            try {
                WorldEvents.team().ownerRetiredWork(body);
            } catch (RuntimeException ignored) {
                // The body is already stopped; preserve the event for controller reconciliation.
            }
            stopped++;
        }
        return stopped;
    }

    private Session sessionFor(ServerPlayer player) {
        UUID ownerId = player.getUUID();
        Session current = sessions.get(ownerId);
        if (current == null || current.entityId != player.getId()) {
            current = new Session(player.getId());
            sessions.put(ownerId, current);
        }
        return current;
    }

    private boolean matchesCurrentSession(Entry entry, UUID ownerId, UUID uiSession, long epoch) {
        Session session = sessions.get(ownerId);
        if (!entry.ownerId.equals(ownerId) || !entry.uiSession.equals(uiSession)
                || entry.epoch != epoch || epoch != currentEpoch()) return false;
        if (session == null || session.entityId != entry.entityId || !session.uiSession.equals(uiSession)) return false;
        ServerPlayer player = currentPlayer(entry);
        return player != null && player.getId() == entry.entityId;
    }

    private ServerPlayer currentPlayer(Entry entry) {
        ServerPlayer player = server.getPlayerList().getPlayer(entry.ownerId);
        return player != null && player.getId() == entry.entityId ? player : null;
    }

    private long currentEpoch() {
        try {
            return sessionEpoch.getAsLong();
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("UI service requires the server thread");
    }

    private static Map<String, Object> result(String decision, String reason) {
        return Map.of("decision", decision, "reason", reason);
    }

    private static Map<String, Object> resultFor(Entry entry, String decision, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decision);
        result.put("reason", reason);
        result.put("requestId", entry.requestId.toString());
        result.put("uiSession", entry.uiSession.toString());
        result.put("operation", entry.operation.wireName());
        result.put("message", entry.message);
        result.put("state", entry.state.name());
        result.put("published", entry.published);
        if (entry.localStop >= 0) result.put("localStopped", entry.localStop);
        return Map.copyOf(result);
    }

    private enum State { PENDING, CLAIMED, COMPLETED, STALE, REJECTED }

    private static final class Session {
        private final int entityId;
        private final UUID uiSession = UUID.randomUUID();

        private Session(int entityId) {
            this.entityId = entityId;
        }
    }

    private static final class Entry {
        private final UUID requestId;
        private final UUID ownerId;
        private final int entityId;
        private final UUID uiSession;
        private long epoch;
        private final UiNetwork.Operation operation;
        private final String message;
        private final long createdTick;
        private final boolean mutation;
        private State state = State.PENDING;
        private boolean published;
        private int localStop = -1;
        private long completedTick = -1;

        private Entry(UUID requestId, UUID ownerId, int entityId, UUID uiSession, long epoch,
                      UiNetwork.Operation operation, String message, long createdTick, boolean mutation) {
            this.requestId = requestId;
            this.ownerId = ownerId;
            this.entityId = entityId;
            this.uiSession = uiSession;
            this.epoch = epoch;
            this.operation = operation;
            this.message = message;
            this.createdTick = createdTick;
            this.mutation = mutation;
        }

        private boolean samePayload(UiNetwork.Request request) {
            return operation == request.operation() && message.equals(request.message());
        }
    }
}
