package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;

/** Optional local transport. IO never waits on the Minecraft tick thread. */
public final class ModLink implements AutoCloseable {
    public static final String PROTOCOL = "hearthcrew.v1";
    private static final Gson JSON = new GsonBuilder().registerTypeAdapter(ResourceLocation.class,
            (JsonSerializer<ResourceLocation>) (value, type, context) -> new JsonPrimitive(value.toString())).create();
    private static final int MAX_FRAME = 1_048_576;
    private static final int EVENT_QUEUE_CAPACITY = 128;
    private static final int EVENT_IN_FLIGHT_WINDOW = 64;
    private final MinecraftServer server;
    private final String worldId;
    private final BackendLinkOperations backendOps;
    private final BackendEventStream backendEvents;
    private final String eventNamespace = UUID.randomUUID().toString();
    private final Path pairingFile;
    private final ConcurrentMap<Long, ArrayBlockingQueue<JsonObject>> outgoingBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Socket> socketsBySession = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean ready;
    private volatile Socket socket;
    private volatile long epoch;
    private volatile String state = "disconnected";
    private final Map<String, JsonObject> replies = new LinkedHashMap<>();
    private final Map<String, String> requestBodies = new LinkedHashMap<>();
    private final Map<String, Long> lastSequences = new HashMap<>();
    /** Last body incarnation whose availability has entered stable retention. */
    private final Map<String, String> announcedBodies = new HashMap<>();
    private record IntentBinding(String intentId, String origin, long intentGeneration, long bodyGeneration, long sessionEpoch, long controlRevision) {}
    private final Map<UUID, IntentBinding> intents = new HashMap<>();
    private final Map<UUID,List<BlockPos>> watchedTargets=new HashMap<>();
    private record WakeFacts(String inventory, String environment, String danger, long controlRevision, boolean active, long idleSince, boolean idleAnnounced) {}
    private final Map<UUID, WakeFacts> wakeFacts = new HashMap<>();
    private long lastWakeTick = Long.MIN_VALUE;
    private long announcedTeamRevision = -1;
    private final Semaphore inboundSlots = new Semaphore(128);
    /** Stable event retention is separate from each session's bounded queue. */
    private final EventWindow<JsonObject> unacknowledged = new EventWindow<>(4096, EVENT_IN_FLIGHT_WINDOW);
    private GameUiService ui;
    private final Set<String> displayedChats = new LinkedHashSet<>();

    public ModLink(MinecraftServer server, Path pairingFile) {
        this.server = server; this.pairingFile = pairingFile;
        boolean backend=io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.enabled();
        this.worldId = backend?io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.roster(server).world().toString():CrewWorldData.get(server).worldId().toString();
        this.backendOps=backend?new BackendLinkOperations(server,()->epoch):null;
        this.backendEvents=backend?new BackendEventStream(server,eventNamespace):null;
    }
    public void start() {
        Thread worker = new Thread(this::connectLoop, "hearthcrew-link"); worker.setDaemon(true); worker.start();
    }
    public boolean ready() { return ready; }
    public String state() { return state; }
    public long sessionEpoch() { return epoch; }
    public void setUiService(GameUiService service) { ui = service; }
    /** Register a UI request without waiting for the controller or socket writer. */
    public boolean publishUiRequest(Map<String, Object> details) {
        if (!server.isSameThread()) throw new IllegalStateException("UI requests require the server thread");
        if (!ready || closed) return false;
        String eventId = eventNamespace + ":ui:" + details.get("uiSession") + ":" + details.get("requestId");
        JsonObject event = envelope("event");
        event.addProperty("eventId", eventId); event.addProperty("event", "ui.request");
        event.add("body", JSON.toJsonTree(details));
        if (!unacknowledged.register(eventId, 0, event)) return false;
        pumpEvents(epoch);
        return true;
    }
    public boolean publishPlayerChat(Map<String, Object> details) {
        if (!server.isSameThread()) throw new IllegalStateException("chat requires server thread");
        if (closed || !ready) return false;
        String id = eventNamespace + ":chat:" + details.get("messageId");
        JsonObject event = envelope("event"); event.addProperty("eventId", id); event.addProperty("event", "player.chat");
        event.add("body", JSON.toJsonTree(details));
        if (!unacknowledged.register(id, 0, event)) return false;
        if (ready) pumpEvents(epoch);
        return true;
    }
    private JsonElement publishChat(JsonObject request, JsonObject payload) {
        CompanionEntity sender = companion(payload);
        requireGeneration(requiredBodyGeneration(payload, request), sender);
        requireIntent(request, payload, sender);
        if (sender.executor().stopped() || sender.executor().paused()) throw new IllegalArgumentException("speaker is stopped or paused");
        if (!worldId.equals(requiredString(payload, "worldId", 128))
                || !sender.companionId().toString().equals(requiredString(payload, "senderId", 128))) throw new IllegalArgumentException("chat identity mismatch");
        String id = requiredString(payload, "messageId", 128);
        String message = requiredString(payload, "message", 400).replaceAll("[\\p{Cntrl}§]", " ");
        if (message.codePointCount(0, message.length()) > 200) throw new IllegalArgumentException("chat too long");
        var player = server.getPlayerList().getPlayer(sender.ownerId());
        if (player == null) throw new IllegalArgumentException("owner unavailable");
        var names = new ArrayList<String>();
        JsonArray recipients = payload.getAsJsonArray("recipientIds");
        if (recipients == null || recipients.size() > 2) throw new IllegalArgumentException("invalid chat recipients");
        for (JsonElement raw : recipients) {
            UUID recipientId = parseUuid(raw, "recipientIds");
            var recipient = CrewWorldData.liveCompanions(server).stream().filter(b -> b.companionId().equals(recipientId)).findFirst().orElseThrow();
            if (!Objects.equals(sender.ownerId(), recipient.ownerId())) throw new IllegalArgumentException("chat recipient belongs to another owner");
            names.add(recipient.getDisplayName().getString());
        }
        if (!displayedChats.contains(id)) {
            JsonObject packet = new JsonObject(); packet.addProperty("worldId", worldId); packet.addProperty("messageId", id);
            packet.addProperty("senderName", sender.getDisplayName().getString()); packet.addProperty("message", message);
            packet.addProperty("targetName", names.isEmpty() ? "你" : names.size() == 2 ? "小队" : names.getFirst());
            if (payload.has("replyTo")) packet.addProperty("replyTo", requiredString(payload, "replyTo", 128));
            if (payload.has("replyName")) packet.addProperty("replyName", requiredString(payload, "replyName", 128));
            io.github.yufeiyufei888.hearthcrew.network.UiNetwork.sendChat(player, packet.toString());
            displayedChats.add(id);
            if (displayedChats.size() > 4096) displayedChats.remove(displayedChats.iterator().next());
        }
        return JSON.toJsonTree(Map.of("messageId", id, "delivered", true));
    }
    private void connectLoop() {
        while (!closed) {
            try {
                if (!Files.isRegularFile(pairingFile)) { state = "not_paired"; Thread.sleep(2000); continue; }
                JsonObject pairing = JsonParser.parseString(Files.readString(pairingFile)).getAsJsonObject();
                int port = pairing.get("port").getAsInt(); String token = pairing.get("token").getAsString();
                if (port < 1 || port > 65535 || token.length() < 32) throw new IOException("invalid pairing configuration");
                epoch = Math.max(epoch + 1, System.currentTimeMillis());
                Socket connection = new Socket(); socket = connection;
                connection.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
                connection.setTcpNoDelay(true); connection.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(connection.getInputStream());
                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                JsonObject hello = envelope("handshake");
                hello.addProperty("requestId", "mod-hello"); hello.addProperty("client", "mod");hello.addProperty("modVersion",backendOps==null?"0.3.2":"0.3.2"); hello.addProperty("token", token);
                JsonObject caps = new JsonObject(); caps.addProperty("events", true); caps.addProperty("acknowledgements", true);
                caps.addProperty("executionProtocol",5);caps.addProperty("resourcePreparation",true);caps.addProperty("unifiedPreparation",true);caps.addProperty("historySeparate",true);caps.addProperty("safeAccessBudget",true);caps.addProperty("nativePickupPost",true);caps.addProperty("sharedSequenceBudget",true);caps.addProperty("continuousCollection",true);caps.addProperty("sequence",true);caps.addProperty("reconciliation", true); caps.addProperty("autonomy", true); caps.addProperty("publicChat", true); caps.addProperty("maxFrameBytes", MAX_FRAME); if(backendOps!=null){caps.addProperty("executionProtocol",6);caps.addProperty("sequence",false);hello.add("backend",BackendLinkOperations.descriptor());}
                hello.add("capabilities", caps);
                write(output, hello);
                JsonObject ack = read(input);
                if(backendOps!=null){var capabilities=ack.has("serverCapabilities")?ack.getAsJsonObject("serverCapabilities"):new JsonObject();
                    if(!capabilities.has("exactMoveCompletion")||!capabilities.get("exactMoveCompletion").getAsBoolean()||!capabilities.has("navigationProgressVersion")||capabilities.get("navigationProgressVersion").getAsInt()!=1||!capabilities.has("boundedNoProgress")||!capabilities.get("boundedNoProgress").getAsBoolean()||!capabilities.has("facilityStanceVerification")||!capabilities.get("facilityStanceVerification").getAsBoolean())throw new IOException("Controller requires 0.3.2 navigation/recovery contract");}
                if (!sameSession(ack) || !"handshake_ack".equals(ack.get("kind").getAsString()) || !ack.get("accepted").getAsBoolean())
                    throw new IOException("handshake not accepted");
                connection.setSoTimeout(0); ready = true; state = "ready";
                long thisEpoch = epoch;
                socketsBySession.put(thisEpoch, connection);
                server.execute(() -> { if (ready && epoch == thisEpoch) pumpEvents(thisEpoch); });
                Thread writer = new Thread(() -> writeLoop(connection, output, thisEpoch), "hearthcrew-link-writer"); writer.setDaemon(true); writer.start();
                while (!closed && !connection.isClosed()) {
                    JsonObject message = read(input);
                    if (!sameSession(message)) throw new IOException("stale session message");
                    if (!inboundSlots.tryAcquire()) throw new IOException("request backpressure");
                    server.execute(() -> { try { if (ready && epoch == thisEpoch) handle(message, thisEpoch); } finally { inboundSlots.release(); } });
                }
            } catch (Exception error) {
                boolean incompatible = "Controller requires 0.3.2 navigation/recovery contract".equals(error.getMessage());
                state = closed ? "closed" : incompatible ? "version_mismatch: update Mod and controller to 0.3.2" : "disconnected";
                org.slf4j.LoggerFactory.getLogger("HearthCrewLink").warn("HearthCrew transport read closed: {}", incompatible ? "BACKEND_0_3_2_REQUIRED" : error.getClass().getSimpleName());
            } finally { ready = false; closeSocket(); }
            if (!closed) try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void writeLoop(Socket connection, DataOutputStream output, long sessionEpoch) {
        ArrayBlockingQueue<JsonObject> outgoing = outgoingBySession.computeIfAbsent(sessionEpoch, ignored -> new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY));
        try {
            while (!closed && !connection.isClosed()) {
                JsonObject message = outgoing.poll(1, TimeUnit.SECONDS);
                if (message != null) {
                    write(output, message);
                    // A slot is now available for the next retained event;
                    // ACKs are still the in-flight bound for event delivery.
                    server.execute(() -> { if (ready && epoch == sessionEpoch) pumpEvents(sessionEpoch); });
                }
            }
        } catch (Exception error) { org.slf4j.LoggerFactory.getLogger("HearthCrewLink").warn("HearthCrew transport write closed: {}", error instanceof IOException && "frame too large".equals(error.getMessage()) ? "FRAME_TOO_LARGE" : error.getClass().getSimpleName()); try { connection.close(); } catch (IOException ignored) {} }
        finally {
            outgoingBySession.remove(sessionEpoch, outgoing);
            socketsBySession.remove(sessionEpoch, connection);
            unacknowledged.resetSession(sessionEpoch);
        }
    }

    private void handle(JsonObject request, long sessionEpoch) {
        if (request == null) return;
        String kind = stringField(request, "kind", 32);
        if ("ack".equals(kind)) {
            if (request.has("accepted") && request.get("accepted").isJsonPrimitive()
                    && request.get("accepted").getAsJsonPrimitive().isBoolean()
                    && request.get("accepted").getAsBoolean() && request.has("eventId")) {
                String eventId = stringField(request, "eventId", 256);
                if (eventId != null && unacknowledged.acknowledge(eventId)) pumpEvents(sessionEpoch);
            }
            return;
        }
        if (!"request".equals(kind)) return;
        String id = stringField(request, "requestId", 128);
        if (id == null) return;
        String fingerprint = fingerprint(request);
        if (replies.containsKey(id)) {
            if (fingerprint.equals(requestBodies.get(id))) enqueue(withSession(replies.get(id), sessionEpoch), sessionEpoch);
            else enqueue(error(id, "ID_CONFLICT", "request identity already bound", sessionEpoch), sessionEpoch);
            return;
        }
        String op = stringField(request, "op", 32);
        boolean retainReply = op != null && !(backendOps!=null && Set.of("intent.bind","action.submit","action.cancel","action.reconcile_history").contains(op)) && !Set.of("status", "observe", "reconcile", "team.status", "action.history", "ui.claim", "ui.update", "chat.publish").contains(op);
        // Observations must neither exhaust mutation identity retention nor
        // become stale cached snapshots. Recovery reads remain available even
        // when the bounded mutation ledger is full.
        // UI claim/update have a separate owner/session ledger. Frequent screen
        // refreshes must not exhaust the body's world-mutation identities.
        if (retainReply && replies.size() >= 4096) { enqueue(error(id, "CAPACITY", "mutation identity retention full; recovery reads remain available", sessionEpoch), sessionEpoch); return; }
        JsonObject response;
        try {
            if (op == null || !Set.of("status", "observe", "intent.bind", "action.submit", "recovery.standby", "action.reconcile_history", "action.history", "action.cancel", "control", "reconcile", "team.status", "team.propose", "team.execute", "ui.claim", "ui.update", "chat.publish").contains(op)) {
                throw new IllegalArgumentException("unsupported operation");
            }
            if (!request.has("body") || !request.get("body").isJsonObject()) throw new IllegalArgumentException("body must be an object");
            JsonObject body = request.getAsJsonObject("body");
            JsonElement result = backendOps!=null&&!Set.of("ui.claim","ui.update").contains(op)?backendOps.handle(op,request,body):switch (op) {
                case "status", "reconcile" -> snapshot();
                case "action.history" -> { var selected=companion(body);requireGeneration(requiredGeneration(request,"bodyGeneration"),selected);yield ActionHistoryPage.read(selected,body.has("beforeSequence")?body.get("beforeSequence").getAsLong():Long.MAX_VALUE,body.has("limit")?body.get("limit").getAsInt():64); }
                case "observe" -> observe(body);
                case "recovery.standby" -> {
                    var selected=companion(body);requireGeneration(requiredGeneration(request,"bodyGeneration"),selected);
                    var data=CrewWorldData.get(server);
                    if(!worldId.equals(requiredString(body,"expectedWorld",128)))throw new IllegalArgumentException("recovery world mismatch");
                    String state=data.recoverLegacyStandby(requiredString(body,"recoveryId",80),selected,requiredGeneration(body,"controlRevision"),requiredString(body,"expectedName",64));
                    var reply=new JsonObject();reply.addProperty("state",state);yield reply;
                }
                case "intent.bind" -> bindIntent(request, body);
                case "action.submit" -> submit(request, body);
                case "action.reconcile_history" -> reconcileHistory(request,body);
                case "action.cancel" -> cancel(body, request);
                case "control" -> control(body, request);
                case "team.status" -> JSON.toJsonTree(WorldEvents.team().snapshot());
                case "team.propose" -> proposeWork(request, body);
                case "team.execute" -> executeWork(request, body);
                case "ui.claim", "ui.update" -> uiRequest(op, body, sessionEpoch);
                case "chat.publish" -> publishChat(request, body);
                default -> throw new IllegalArgumentException("unsupported operation: " + op);
            };
            response = envelope("response", sessionEpoch); response.addProperty("requestId", id); response.addProperty("ok", true); response.add("body", result);
        } catch (RuntimeException error) { response = error(id, "REQUEST_REJECTED", safeMessage(error), sessionEpoch); }
        if (retainReply) { replies.put(id, response); requestBodies.put(id, fingerprint); }
        enqueue(response, sessionEpoch);
    }

    private JsonElement uiRequest(String operation, JsonObject body, long sessionEpoch) {
        if (ui == null) throw new IllegalStateException("game UI service unavailable");
        UUID requestId = UUID.fromString(requiredString(body, "requestId", 36));
        UUID ownerId = UUID.fromString(requiredString(body, "ownerId", 36));
        UUID uiSession = UUID.fromString(requiredString(body, "uiSession", 36));
        if (operation.equals("ui.claim")) return JSON.toJsonTree(ui.claim(requestId, ownerId, uiSession, sessionEpoch));
        String json = requiredString(body, "json", 60 * 1024);
        return JSON.toJsonTree(ui.complete(requestId, ownerId, uiSession, sessionEpoch, json));
    }

    private JsonElement reconcileHistory(JsonObject request,JsonObject body) {
        CompanionEntity companion=companion(body); requireGeneration(requiredGeneration(request,"bodyGeneration"),companion);
        String id=requiredString(body,"actionId",128);
        if (!body.has("receipt")||!body.get("receipt").isJsonObject()) throw new IllegalArgumentException("missing historical receipt");
        JsonObject evidence=body.getAsJsonObject("receipt");
        var current=companion.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow(()->new IllegalArgumentException("unknown historical action"));
        JsonObject stored=JSON.toJsonTree(current).getAsJsonObject();
        for(String field:List.of("id","payload","epoch","priority"))
            if(!stored.has(field)||!evidence.has(field)||!stored.get(field).equals(evidence.get(field))) throw new IllegalArgumentException("historical request mismatch: "+field);
        ActionState state=ActionState.valueOf(requiredString(evidence,"state",32));
        boolean restored=companion.executor().restoreHistoricalOutcome(id,state,requiredString(evidence,"message",4096));
        return JSON.toJsonTree(java.util.Map.of("restored",restored));
    }

    private static BodyOrder parseCompositeOrder(JsonObject raw,BodyOrder.Kind kind,BlockPos position,UUID target,int count,ResourceLocation resource){
        List<BodyOrder> actions=new ArrayList<>();List<ResourceLocation> candidates=new ArrayList<>();
        if(raw.has("actions")){
            var list=raw.getAsJsonArray("actions");if(kind!=BodyOrder.Kind.SEQUENCE||list.isEmpty()||list.size()>8)throw new IllegalArgumentException("SEQUENCE requires 1..8 steps");
            for(var element:list){var step=element.getAsJsonObject();var args=step.getAsJsonObject("parameters").deepCopy();args.add("kind",step.get("kind"));var k=parseKind(args);
                if(k==BodyOrder.Kind.SEQUENCE)throw new IllegalArgumentException("nested SEQUENCE forbidden");
                var pos=args.has("position")?parsePosition(args.get("position")):null;var id=args.has("target")?parseUuid(args.get("target"),"target"):null;
                int amount=args.has("count")?boundedInt(args.get("count"),"count",0,100000):k==BodyOrder.Kind.EXCAVATE?32:0;
                var item=args.has("resource")?parseResource(args.get("resource")):null;validateKindParameters(k,pos,id,amount,item,args);
                var nested=parseCompositeOrder(args,k,pos,id,amount,item);var parent=parsePreparation(raw,kind);var own=nested.preparation();
                nested=new BodyOrder(nested.kind(),nested.position(),nested.target(),nested.count(),nested.resource(),nested.steps(),nested.actions(),nested.candidates(),nested.radius(),nested.accessBudget(),new BodyOrder.Preparation(parent.enabled()&&own.enabled(),Math.min(parent.maxDepth(),own.maxDepth()),Math.min(parent.maxSteps(),own.maxSteps()),Math.min(parent.maxBreaks(),own.maxBreaks())));
                actions.add(nested);
            }
        }
        if(raw.has("candidates")){var list=raw.getAsJsonArray("candidates");if(list.isEmpty()||list.size()>16)throw new IllegalArgumentException("candidates requires 1..16 blocks");for(var e:list)candidates.add(parseResource(e));}
        int radius=raw.has("radius")?boundedInt(raw.get("radius"),"radius",1,32):32;
        return new BodyOrder(kind,position,target,count,resource,parseBuildSteps(raw,kind),actions,candidates,radius,raw.has("accessBudget")?boundedInt(raw.get("accessBudget"),"accessBudget",0,64):16,parsePreparation(raw,kind));
    }
    private static BodyOrder.Preparation parsePreparation(JsonObject raw,BodyOrder.Kind kind){
        if(!raw.has("preparation"))return java.util.Set.of(BodyOrder.Kind.COLLECT_RESOURCE,BodyOrder.Kind.MINE,BodyOrder.Kind.EXCAVATE,BodyOrder.Kind.CRAFT,BodyOrder.Kind.SEQUENCE).contains(kind)?BodyOrder.Preparation.standard():BodyOrder.Preparation.disabled();
        var p=raw.getAsJsonObject("preparation");
        for(var key:p.keySet())if(!java.util.Set.of("enabled","maxDepth","maxSteps","maxBreaks").contains(key))throw new IllegalArgumentException("unknown preparation field");
        return new BodyOrder.Preparation(!p.has("enabled")||p.get("enabled").getAsBoolean(),p.has("maxDepth")?boundedInt(p.get("maxDepth"),"maxDepth",1,6):6,p.has("maxSteps")?boundedInt(p.get("maxSteps"),"maxSteps",1,16):16,p.has("maxBreaks")?boundedInt(p.get("maxBreaks"),"maxBreaks",0,64):64);
    }
    private JsonElement submit(JsonObject request, JsonObject body) {
        requiredString(request, "intentId", 128);
        String actionId = requiredString(request, "actionId", 128);
        long generation = requiredGeneration(request, "bodyGeneration");
        CompanionEntity companion = companion(body);
        requireGeneration(generation, companion);
        IntentBinding intent = requireIntent(request, body, companion);
        BodyOrder.Kind kind = parseKind(body);
        if (kind == BodyOrder.Kind.SELECT && !body.has("count")) throw new IllegalArgumentException("SELECT requires explicit slot normalized as count");
        BlockPos position = null;
        if (body.has("position")) position = parsePosition(body.get("position"));
        UUID target = body.has("target") ? parseUuid(body.get("target"), "target") : null;
        int count = body.has("count") ? boundedInt(body.get("count"), "count", 0, 100_000) : kind == BodyOrder.Kind.EXCAVATE ? 32 : 0;
        ResourceLocation resource = body.has("resource") ? parseResource(body.get("resource")) : null;
        validateKindParameters(kind, position, target, count, resource, body);
        BodyOrder order = parseCompositeOrder(body,kind,position,target,count,resource);
        WorldEvents.team().assertIndependentActionAllowed(companion, order);
        ActionPriority priority = intent.origin().equals("owner") ? ActionPriority.OWNER
                : kind == BodyOrder.Kind.EAT ? ActionPriority.SUPPLY : ActionPriority.PERSONAL;
        var receipt = companion.executor().submit(actionId, order, priority);
        return JSON.toJsonTree(receipt);
    }
    static BodyOrder parseBackendOrder(JsonObject body){var kind=parseKind(body);var position=body.has("position")?parsePosition(body.get("position")):null;var target=body.has("target")?parseUuid(body.get("target"),"target"):null;int count=body.has("count")?boundedInt(body.get("count"),"count",0,100000):0;var resource=body.has("resource")?parseResource(body.get("resource")):null;validateKindParameters(kind,position,target,count,resource,body);return parseCompositeOrder(body,kind,position,target,count,resource);}
    private JsonElement proposeWork(JsonObject request, JsonObject body) {
        String intentId = requiredString(request, "intentId", 128);
        String taskId = requiredString(body, "taskId", 128);
        long generation = requiredGeneration(request, "bodyGeneration");
        CompanionEntity selected = companion(body); requireGeneration(generation, selected);
        IntentBinding intent = requireIntent(request, body, selected);
        BodyOrder.Kind kind = parseKind(body);
        if (kind == BodyOrder.Kind.SELECT && !body.has("count")) throw new IllegalArgumentException("SELECT requires explicit slot normalized as count");
        BlockPos position = body.has("position") ? parsePosition(body.get("position")) : null;
        UUID target = body.has("target") ? parseUuid(body.get("target"), "target") : null;
        int count = body.has("count") ? boundedInt(body.get("count"), "count", 0, 100_000) : kind == BodyOrder.Kind.EXCAVATE ? 32 : 0;
        ResourceLocation resource = body.has("resource") ? parseResource(body.get("resource")) : null;
        validateKindParameters(kind, position, target, count, resource, body);
        String description = requiredString(body, "description", 2000);
        CrewTeamService.Work work = new CrewTeamService.Work(taskId, intentId, selected.companionId(), generation,
                selected.level().dimension().location().toString(), kind,
                position == null ? null : new CrewTeamService.Position(position.getX(), position.getY(), position.getZ()),
                target, count, resource == null ? null : resource.toString(), description, parseBuildSteps(body, kind));
        return JSON.toJsonTree(WorldEvents.team().propose(work, intent.origin().equals("owner") ? ActionPriority.OWNER : ActionPriority.MISSION));
    }
    private JsonElement executeWork(JsonObject request, JsonObject body) {
        String intentId = requiredString(request, "intentId", 128);
        long generation = requiredGeneration(request, "bodyGeneration");
        CompanionEntity selected = companion(body); requireGeneration(generation, selected);
        requireIntent(request, body, selected);
        return JSON.toJsonTree(WorldEvents.team().execute(requiredString(body, "taskId", 128), selected.companionId(), generation, intentId));
    }
    private JsonElement cancel(JsonObject body, JsonObject request) {
        String actionId = requiredString(body, "actionId", 128);
        requiredString(body, "intentId", 128);
        long generation = requiredBodyGeneration(body, request);
        CompanionEntity companion = companion(body);
        requireGeneration(generation, companion);
        // Cancellation is a controller operation; ordinary model tools cannot issue it.
        return JSON.toJsonTree(companion.executor().arbiter().cancel(new ActionId(actionId), "owner cancellation"));
    }
    private JsonElement control(JsonObject body, JsonObject request) {
        String operation = requiredString(body, "operation", 16).toLowerCase(Locale.ROOT);
        long generation = requiredBodyGeneration(body, request);
        if (!body.has("botId")) throw new IllegalArgumentException("control requires botId and bodyGeneration");
        CompanionEntity selected = companion(body);
        requireGeneration(generation, selected);
        if (request.has("intentId")) requireIntent(request, body, selected);
        List<CompanionEntity> targets = List.of(selected);
        for (CompanionEntity companion : targets) {
            WorldEvents.controlBody(companion, operation);
        }
        WorldEvents.team().tick();
        JsonObject result = new JsonObject(); result.addProperty("count", targets.size()); result.addProperty("operation", operation); return result;
    }
    private JsonElement bindIntent(JsonObject request, JsonObject payload) {
        CompanionEntity body = companion(payload);
        long bodyGeneration = requiredGeneration(request, "bodyGeneration"); requireGeneration(bodyGeneration, body);
        String id = requiredString(request, "intentId", 128), origin = requiredString(payload, "origin", 16);
        if (!Set.of("owner", "autonomous").contains(origin)) throw new IllegalArgumentException("invalid intent origin");
        long generation = requiredGeneration(payload, "intentGeneration");
        CrewWorldData data = CrewWorldData.get(server);
        if (body.executor().stopped() || body.executor().paused() || body.executor().recoveryInvalid() || data.standby(body.companionId()))
            throw new IllegalStateException("伙伴已暂停、待命或需要核对");
        if (origin.equals("autonomous") && !data.autonomyEnabled(body.companionId())) throw new IllegalStateException("自主模式已关闭");
        IntentBinding prior = intents.get(body.companionId());
        if (prior != null && (generation < prior.intentGeneration() || (generation == prior.intentGeneration()
                && (!id.equals(prior.intentId()) || !origin.equals(prior.origin()))))) throw new IllegalArgumentException("stale or conflicting intent generation");
        if (!WorldEvents.team().intentMayBind(body, id)) throw new IllegalStateException("已有未结算团队任务，需先核对");
        var active = body.executor().arbiter().activeSnapshot();
        if (active.isPresent() && ActionPriority.GUARD.outranks(active.get().priority())
                && !active.get().id().value().startsWith(id + ":")
                && WorldEvents.team().bindingForAction(body.companionId(), active.get().id().value()) == null)
            throw new IllegalStateException("身体正在执行其他意图，需先核对或改派");
        IntentBinding next = new IntentBinding(id, origin, generation, bodyGeneration, epoch, data.controlRevision(body.companionId()));
        intents.put(body.companionId(), next); return JSON.toJsonTree(next);
    }
    private IntentBinding requireIntent(JsonObject request, JsonObject payload, CompanionEntity body) {
        IntentBinding binding = intents.get(body.companionId()); CrewWorldData data = CrewWorldData.get(server);
        if (binding == null || !binding.intentId().equals(requiredString(request, "intentId", 128))
                || binding.intentGeneration() != requiredGeneration(payload, "intentGeneration")
                || binding.bodyGeneration() != body.bodyGeneration() || binding.sessionEpoch() != epoch
                || binding.controlRevision() != data.controlRevision(body.companionId())) throw new IllegalStateException("意图已失效，需重新核对并绑定");
        if (data.standby(body.companionId()) || (binding.origin().equals("autonomous") && !data.autonomyEnabled(body.companionId())))
            throw new IllegalStateException("伙伴待命或自主模式已关闭");
        return binding;
    }
    private CompanionEntity companion(JsonObject body) {
        UUID id = parseUuid(body.get("botId"), "botId");
        return CrewWorldData.liveCompanions(server).stream().filter(candidate -> candidate.companionId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("companion unavailable"));
    }

    private static String requiredString(JsonObject object, String name, int maxLength) {
        String value = stringField(object, name, maxLength);
        if (value == null) throw new IllegalArgumentException("missing or invalid " + name);
        return value;
    }
    private static String stringField(JsonObject object, String name, int maxLength) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isString()) return null;
        String value = object.get(name).getAsString();
        return value.isBlank() || value.length() > maxLength ? null : value;
    }
    private static long requiredGeneration(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing or invalid " + name);
        }
        double number = object.get(name).getAsDouble();
        // Gson stores JSON numbers as doubles here.  Refuse values which are
        // outside JavaScript's safe integer range instead of silently
        // aliasing two distinct wire values to the same long.
        if (!Double.isFinite(number) || number < 0 || number != Math.rint(number) || number > 9_007_199_254_740_991d) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return (long) number;
    }
    private static int boundedInt(JsonElement value, String name, int minimum, int maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < minimum || number > maximum) {
            throw new IllegalArgumentException(name + " is out of bounds");
        }
        return (int) number;
    }
    private static UUID parseUuid(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a UUID string");
        }
        try { return UUID.fromString(value.getAsString()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(name + " must be a UUID string"); }
    }
    private static void requireGeneration(long requested, CompanionEntity companion) {
        if (requested > 0xFFFF_FFFFL) throw new IllegalArgumentException("body generation out of bounds");
        if (requested != companion.bodyGeneration()) throw new IllegalArgumentException("body generation changed");
    }
    private static long requiredBodyGeneration(JsonObject body, JsonObject request) {
        boolean bodyHas = body != null && body.has("bodyGeneration");
        boolean requestHas = request != null && request.has("bodyGeneration");
        if (!bodyHas && !requestHas) throw new IllegalArgumentException("missing bodyGeneration");
        long bodyGeneration = bodyHas ? requiredGeneration(body, "bodyGeneration") : -1;
        long requestGeneration = requestHas ? requiredGeneration(request, "bodyGeneration") : -1;
        if (bodyHas && requestHas && bodyGeneration != requestGeneration) throw new IllegalArgumentException("bodyGeneration mismatch");
        return bodyHas ? bodyGeneration : requestGeneration;
    }
    private static BlockPos parsePosition(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("position must be an object");
        JsonObject position = value.getAsJsonObject();
        int x = boundedInt(position.get("x"), "position.x", -30_000_000, 30_000_000);
        int y = boundedInt(position.get("y"), "position.y", -2_048, 2_048);
        int z = boundedInt(position.get("z"), "position.z", -30_000_000, 30_000_000);
        return new BlockPos(x, y, z);
    }
    private static BodyOrder.Kind parseKind(JsonObject body) {
        String value = requiredString(body, "kind", 32).toUpperCase(Locale.ROOT);
        try {
            var kind = BodyOrder.Kind.valueOf(value);
            if ((kind == BodyOrder.Kind.SELF_DEFENCE || kind == BodyOrder.Kind.BREATHE)) throw new IllegalArgumentException("local reflex only");
            return kind;
        }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("unsupported action kind"); }
    }
    private static List<BodyOrder.BuildStep> parseBuildSteps(JsonObject body, BodyOrder.Kind kind) {
        if (kind != BodyOrder.Kind.BUILD) {
            if (body.has("steps")) throw new IllegalArgumentException("steps are only accepted for BUILD");
            return List.of();
        }
        JsonElement value = body.get("steps");
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 1 || value.getAsJsonArray().size() > 16)
            throw new IllegalArgumentException("BUILD requires 1..16 steps");
        List<BodyOrder.BuildStep> steps = new ArrayList<>();
        Set<BlockPos> positions = new HashSet<>();
        for (JsonElement raw : value.getAsJsonArray()) {
            if (!raw.isJsonObject() || !raw.getAsJsonObject().keySet().equals(Set.of("position", "block")))
                throw new IllegalArgumentException("BUILD step requires only position and block");
            JsonObject step = raw.getAsJsonObject();
            if (!step.get("position").isJsonObject() || !step.getAsJsonObject("position").keySet().equals(Set.of("x", "y", "z")))
                throw new IllegalArgumentException("BUILD step position requires only x, y, z");
            BlockPos position = parsePosition(step.get("position"));
            if (!positions.add(position)) throw new IllegalArgumentException("BUILD step positions must be unique");
            String block = requiredString(step, "block", 256);
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("BUILD block requires a namespaced identifier");
            steps.add(new BodyOrder.BuildStep(position, ResourceLocation.parse(block)));
        }
        return List.copyOf(steps);
    }
    private static ResourceLocation parseResource(JsonElement value) {
        String text = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
        if (text == null || text.isBlank() || text.length() > 256) throw new IllegalArgumentException("resource must be a ResourceLocation");
        try { return ResourceLocation.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("resource must be a ResourceLocation"); }
    }
    private static String fingerprint(JsonObject request) {
        JsonObject semantic = request.deepCopy();
        // sessionEpoch identifies the transport connection; it must not turn
        // a lost-response retry after reconnect into a new world mutation.
        semantic.remove("sessionEpoch");
        return JSON.toJson(canonical(semantic));
    }
    private static JsonElement canonical(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value;
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) array.add(canonical(item));
            return array;
        }
        JsonObject object = new JsonObject();
        value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.add(entry.getKey(), canonical(entry.getValue())));
        return object;
    }
    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "request rejected" : message.substring(0, Math.min(message.length(), 256));
    }
    private static void validateKindParameters(BodyOrder.Kind kind, BlockPos position,
                                               UUID target, int count, ResourceLocation resource, JsonObject body) {
        ActionCapabilities.validate(kind.name(),position,target,count,resource,body);
        if(kind==BodyOrder.Kind.GATHER&&!BodyOrder.supportedGatherResource(resource))throw new IllegalArgumentException("unsupported overworld log");
    }

    private JsonElement observe(JsonObject body) {
        JsonObject result = snapshot();
        if (!body.has("botId")) return result;
        CompanionEntity selected = companion(body);
        // Observation is read-only, so callers may omit a generation when
        // opening a UI view.  If one is supplied, still reject a stale body
        // rather than describing a different entity after respawn.
        if (body.has("bodyGeneration")) {
            requireGeneration(requiredGeneration(body, "bodyGeneration"), selected);
        }
        int radius = body.has("radius") ? boundedInt(body.get("radius"), "radius", 1, 32) : 32;
        result.add("observation", NearbyObservation.scan(selected, radius));
        if(body.has("targets")) {
            if(!body.get("targets").isJsonArray()||body.getAsJsonArray("targets").size()>32)throw new IllegalArgumentException("targets must contain at most 32 positions");
            var inspections=new JsonArray();var watched=new ArrayList<BlockPos>();
            for(var raw:body.getAsJsonArray("targets")) {var target=parsePosition(raw);if(target.distSqr(selected.blockPosition())<=1024&&selected.level().hasChunkAt(target))watched.add(target);inspections.add(JSON.toJsonTree(io.github.yufeiyufei888.hearthcrew.gameplay.TargetInspection.inspect(selected,target)));}
            watchedTargets.put(selected.companionId(),List.copyOf(watched));
            result.add("targetInspections",inspections);
        }
        if(body.has("recipes")){var raw=body.getAsJsonArray("recipes");if(raw.size()>16)throw new IllegalArgumentException("at most 16 recipes");var outputs=new ArrayList<ResourceLocation>();for(var item:raw)outputs.add(parseResource(item));result.add("recipes",JSON.toJsonTree(io.github.yufeiyufei888.hearthcrew.gameplay.RecipeDiscovery.query(selected,outputs,body.has("recipeCount")?boundedInt(body.get("recipeCount"),"recipeCount",1,64):1)));}
        if(body.has("containers")){var raw=body.getAsJsonArray("containers");if(raw.size()>16)throw new IllegalArgumentException("at most 16 containers");var entries=new JsonArray();for(var item:raw){var pos=parsePosition(item);if(pos.distSqr(selected.blockPosition())>1024||!selected.level().hasChunkAt(pos))continue;var data=JSON.toJsonTree(io.github.yufeiyufei888.hearthcrew.gameplay.FurnaceOrders.observe(selected,pos)).getAsJsonObject();data.add("position",JSON.toJsonTree(io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.position(pos)));entries.add(data);}result.add("containers",entries);}
        return result;
    }
    private JsonObject snapshot() {
        if(backendOps!=null)return backendOps.snapshot();
        JsonObject result = new JsonObject(); result.addProperty("worldId", worldId); result.addProperty("gameTick", server.overworld().getGameTime());
        result.add("chunkLoading", JSON.toJsonTree(WorldEvents.chunkDiagnostics()));
        result.add("team", JSON.toJsonTree(WorldEvents.team().snapshot()));
        result.addProperty("stage", "0.3.2_DEVELOPMENT_UNACCEPTED");result.addProperty("modVersion","0.3.2");result.addProperty("executionProtocol",5);result.addProperty("snapshotGeneration",server.overworld().getGameTime()); result.add("capabilities",ActionCapabilities.definitions()); JsonArray bots = new JsonArray();
        for (CompanionEntity body : CrewWorldData.liveCompanions(server)) {
            JsonObject bot = new JsonObject(); bot.addProperty("botId", body.companionId().toString()); bot.addProperty("entityId", body.getUUID().toString());
            bot.addProperty("bodyType", "ServerPlayer"); bot.addProperty("bodyGeneration", body.bodyGeneration()); bot.addProperty("name", body.getDisplayName().getString());
            bot.addProperty("dimension", body.level().dimension().location().toString()); bot.addProperty("health", body.getHealth()); bot.addProperty("food", body.foodLevel());
            bot.addProperty("stopped", body.executor().stopped()); bot.addProperty("paused", body.executor().paused());
            bot.addProperty("recoveryInvalid", body.executor().recoveryInvalid());
            CrewWorldData data = CrewWorldData.get(server);
            bot.addProperty("autonomyEnabled", data.autonomyEnabled(body.companionId()));
            bot.addProperty("standby", data.standby(body.companionId()));
            bot.addProperty("controlRevision", data.controlRevision(body.companionId()));
            bot.add("intentBinding", intents.containsKey(body.companionId()) ? JSON.toJsonTree(intents.get(body.companionId())) : JsonNull.INSTANCE);
            bot.addProperty("selectedSlot", body.selectedSlot());
            JsonObject equipment = new JsonObject();
            equipment.add("head", itemView(body.getItemBySlot(EquipmentSlot.HEAD)));
            equipment.add("chest", itemView(body.getItemBySlot(EquipmentSlot.CHEST)));
            equipment.add("legs", itemView(body.getItemBySlot(EquipmentSlot.LEGS)));
            equipment.add("feet", itemView(body.getItemBySlot(EquipmentSlot.FEET)));
            equipment.add("offhand", itemView(body.getOffhandItem())); bot.add("equipment", equipment);
            JsonObject pos = new JsonObject(); pos.addProperty("x", body.getX()); pos.addProperty("y", body.getY()); pos.addProperty("z", body.getZ()); bot.add("position", pos);
            JsonArray inventory = new JsonArray();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = body.inventory().getItem(slot); if (stack.isEmpty()) continue;
                JsonObject entry = new JsonObject(); entry.addProperty("slot", slot); entry.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); entry.addProperty("count", stack.getCount()); inventory.add(entry);
            }
            bot.add("tools",JSON.toJsonTree(io.github.yufeiyufei888.hearthcrew.gameplay.TargetInspection.tools(body)));
            bot.addProperty("air",body.getAirSupply());bot.add("mainHand",itemView(body.getMainHandItem()));
            bot.add("inventory", inventory); bot.add("action", body.executor().arbiter().activeSnapshot().<JsonElement>map(a -> ActionWireView.read(a, JSON)).orElse(JsonNull.INSTANCE));
            var executionJournal=body.executor().arbiter().journal();bot.addProperty("actionSequence",executionJournal.isEmpty()?0:executionJournal.getLast().sequence());bot.addProperty("snapshotGeneration",server.overworld().getGameTime());bot.addProperty("executionProtocol",5);bot.add("suspendedActionIds", JSON.toJsonTree(body.executor().arbiter().suspendedActionIds().stream().map(ActionId::value).toList()));
            body.executor().arbiter().activeSnapshot().ifPresent(a->bot.add("execution",JSON.toJsonTree(body.executor().executionReport(a.id().value()))));
            bot.add("harvestEvidence",JSON.toJsonTree(body.executor().harvestEvidence()));bot.addProperty("onGround",body.onGround());bot.add("travel",JSON.toJsonTree(body.executor().travelStatus()));
            bot.addProperty("ledgerVersion",2);
            bot.add("localSafety", JSON.toJsonTree(body.executor().localSafetyStatus()));
            body.executor().arbiter().activeSnapshot().ifPresent(action -> bot.add("gatherDiagnostics", JSON.toJsonTree(body.executor().gatherDiagnostics(action.id().value()))));
            var history=ActionHistoryPage.read(body,Long.MAX_VALUE,64);
            bot.add("actionJournal",history.getAsJsonArray("entries"));history.remove("entries");bot.add("actionHistory",history); bots.add(bot);
        }
        result.add("companions", bots); return result;
    }
    private static JsonElement itemView(ItemStack stack) {
        if (stack.isEmpty()) return JsonNull.INSTANCE;
        JsonObject item = new JsonObject(); item.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount()); return item;
    }
    private long lastDiagnosticTick=Long.MIN_VALUE;
    public void tick() {
        if (!ready) return;
        long gameTick = server.overworld().getGameTime();
        boolean sampleWake = lastWakeTick == Long.MIN_VALUE || gameTick - lastWakeTick >= 20;
        if (sampleWake) lastWakeTick = gameTick;
        pumpEvents(epoch);
        if ("event_backpressure".equals(state) && !unacknowledged.atCapacity()) state = "ready";
        Map<String, Object> team = WorldEvents.team().snapshot();
        long teamRevision = ((Number)team.get("revision")).longValue();
        if (teamRevision != announcedTeamRevision) {
            String eventId = eventNamespace + ":team:" + teamRevision;
            JsonObject changed = envelope("event"); changed.addProperty("eventId", eventId); changed.addProperty("event", "team.changed");
            JsonObject details = new JsonObject(); details.addProperty("revision", teamRevision); changed.add("body", details);
            if (!unacknowledged.register(eventId, teamRevision, changed)) { state = "event_backpressure"; pumpEvents(epoch); return; }
            announcedTeamRevision = teamRevision;
        }
        if(backendEvents!=null){
            if(!backendEvents.tick((id,type,sequence,details)->{
                var event=envelope("event");event.addProperty("eventId",id);event.addProperty("event",type);event.add("body",details);
                return unacknowledged.register(id,sequence,event);
            })){state="event_backpressure";pumpEvents(epoch);return;}
        }
        for (CompanionEntity body : backendOps!=null?List.<CompanionEntity>of():CrewWorldData.liveCompanions(server)) {
            String key = body.companionId().toString(); String journalKey = key + ":" + body.getUUID() + ":" + body.bodyGeneration();
            if (!journalKey.equals(announcedBodies.get(key))) {
                String eventId = eventNamespace + ":body:" + journalKey;
                JsonObject available = envelope("event");
                available.addProperty("eventId", eventId);
                available.addProperty("event", "body.available");
                JsonObject details = new JsonObject();
                details.addProperty("botId", key);
                details.addProperty("entityId", body.getUUID().toString());
                details.addProperty("bodyGeneration", body.bodyGeneration());
                details.addProperty("name", body.getDisplayName().getString());
                details.addProperty("dimension", body.level().dimension().location().toString());
                available.add("body", details);
                // A reconnect may happen before respawn creates the new body.
                // Announce the actual incarnation only once, and retry on
                // backpressure without relying on model/status polling.
                if (!unacknowledged.register(eventId, 0, available)) {
                    state = "event_backpressure";
                    pumpEvents(epoch);
                    return;
                }
                announcedBodies.put(key, journalKey);
            }
            long last = lastSequences.getOrDefault(journalKey, -1L);
            for (var receipt : body.executor().arbiter().journal()) {
                if (receipt.sequence() <= last) continue;
                // Restored terminal evidence is available through action.history. It is not new activity.
                if(!io.github.yufeiyufei888.hearthcrew.kernel.ReceiptChannel.realtime(receipt.state(),receipt.message())){lastSequences.put(journalKey,receipt.sequence());continue;}
                if (unacknowledged.atCapacity()) { state = "event_backpressure"; pumpEvents(epoch); return; }
                String eventId = io.github.yufeiyufei888.hearthcrew.kernel.ReceiptChannel.identity(CrewWorldData.get(server).worldId().toString(),key,receipt.epoch().bodyGeneration(),receipt.id().value(),receipt.sequence());
                JsonObject event = envelope("event"); event.addProperty("eventId", eventId); event.addProperty("event", receipt.state().terminal() ? "action.terminal" : "action.progress");
                JsonObject details = JSON.toJsonTree(receipt).getAsJsonObject(); details.addProperty("botId", key);
                details.add("execution",JSON.toJsonTree(body.executor().executionReport(receipt.id().value())));
                details.addProperty("historical",(receipt.message().startsWith("historical outcome restored:") || receipt.message().startsWith("saved outcome restored:")));
                var gatherDiagnostics = body.executor().gatherDiagnostics(receipt.id().value());
                if (!gatherDiagnostics.isEmpty()) details.add("gatherDiagnostics", JSON.toJsonTree(gatherDiagnostics));
                CrewTeamService.Work work = WorldEvents.team().bindingForAction(body.companionId(), receipt.id().value());
                if (work != null) { details.addProperty("intentId", work.intentId()); details.addProperty("taskId", work.taskId()); }
                event.add("body", details);
                if (!unacknowledged.register(eventId, receipt.sequence(), event)) {
                    state = "event_backpressure";
                    pumpEvents(epoch);
                    return;
                }
                // lastSequences records registration in the stable retained
                // window, not successful socket delivery. The event remains
                // replayable until an explicit ACK removes it.
                lastSequences.put(journalKey, receipt.sequence());
            }
            if (sampleWake) publishWake(body, gameTick);
        }
        if(ready && (lastDiagnosticTick==Long.MIN_VALUE || gameTick-lastDiagnosticTick>=100) && !unacknowledged.atCapacity()) {
            String eventId=eventNamespace+":health:"+gameTick;var event=envelope("event");event.addProperty("eventId",eventId);event.addProperty("event","diagnostic.snapshot");
            var health=snapshot();for(var raw:health.getAsJsonArray("companions")){var b=raw.getAsJsonObject();b.remove("actionJournal");b.remove("inventory");b.remove("tools");b.remove("equipment");}
            health.remove("team");event.add("body",health);if(unacknowledged.register(eventId,gameTick,event))lastDiagnosticTick=gameTick;
        }
        if (ready) pumpEvents(epoch);
    }
    /** Cheap fact changes and one idle edge, never periodic model polling. */
    private final Map<UUID,Long> scanWakeVersions=new HashMap<>();
    private void publishWake(CompanionEntity body, long gameTick) {
        UUID id = body.companionId(); CrewWorldData data = CrewWorldData.get(server);
        WakeFacts prior = wakeFacts.get(id);
        var amounts=new java.util.TreeMap<String,Integer>();
        for(int slot=0;slot<36;slot++){var item=body.inventory().getItem(slot);if(!item.isEmpty())amounts.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),item.getCount(),Integer::sum);}
        String inventory = amounts.toString();
        int terrainHash=1;
        // Watch fixed action/query targets; moving the observation origin is not world progress.
        for(var pos:watchedTargets.getOrDefault(id,List.of()))if(pos.distSqr(body.blockPosition())<=1024&&body.level().hasChunkAt(pos))terrainHash=31*terrainHash+Objects.hash(body.level().getBlockState(pos),data.protectionReason(body.level(),pos));
        String environment = body.level().dimension().location() + ":" + terrainHash;
        String danger = ((int)body.getHealth()) + ":" + body.foodLevel() + ":" + body.isOnFire() + ":" + body.isUnderWater() + ":" + body.executor().localSafetyStatus().get("mode") + ":" + body.executor().localSafetyStatus().get("reason");
        boolean active = body.executor().arbiter().activeSnapshot().isPresent();
        long idleSince = active || prior == null || prior.active() ? gameTick : prior.idleSince();
        boolean announced = !active && prior != null && !prior.active() && prior.idleAnnounced();
        List<String> reasons = new ArrayList<>();
        long scanRevision=NearbyObservation.revision(body);if(scanRevision>0&&!Objects.equals(scanWakeVersions.put(id,scanRevision),scanRevision))reasons.add("scan_ready");
        if (prior != null) {
            if (!inventory.equals(prior.inventory())) reasons.add("inventory_changed");
            if (!environment.equals(prior.environment())) reasons.add("environment_changed");
            if (!danger.equals(prior.danger())) reasons.add("danger_changed");
            if (data.controlRevision(id) != prior.controlRevision()) reasons.add("mode_changed");
        }
        if (!active && !announced && gameTick - idleSince >= 300) { reasons.add("body_idle"); announced = true; }
        if (!reasons.isEmpty()) {
            String eventId = eventNamespace + ":wake:" + id + ":" + body.bodyGeneration() + ":" + gameTick;
            JsonObject event = envelope("event"); event.addProperty("eventId", eventId); event.addProperty("event", "body.wakeup");
            JsonObject facts = new JsonObject(); facts.addProperty("botId", id.toString()); facts.addProperty("bodyGeneration", body.bodyGeneration());
            facts.add("localSafety", JSON.toJsonTree(body.executor().localSafetyStatus()));
            facts.addProperty("gameTick", gameTick); facts.add("reasons", JSON.toJsonTree(reasons)); event.add("body", facts);
            if (!unacknowledged.register(eventId, gameTick, event)) return;
        }
        wakeFacts.put(id, new WakeFacts(inventory, environment, danger, data.controlRevision(id), active, idleSince, announced));
    }
    private JsonObject error(String id, String code, String message) {
        return error(id, code, message, epoch);
    }
    private JsonObject error(String id, String code, String message, long sessionEpoch) {
        JsonObject result = envelope("response", sessionEpoch); result.addProperty("requestId", id); result.addProperty("ok", false);
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message); result.add("error", error); return result;
    }
    private JsonObject envelope(String kind) { return envelope(kind, epoch); }
    private JsonObject envelope(String kind, long sessionEpoch) {
        JsonObject result = new JsonObject(); result.addProperty("kind", kind); result.addProperty("protocol", PROTOCOL);
        result.addProperty("worldId", worldId); result.addProperty("sessionEpoch", sessionEpoch); return result;
    }
    private JsonObject withSession(JsonObject message, long sessionEpoch) {
        JsonObject copy = message.deepCopy();
        copy.addProperty("sessionEpoch", sessionEpoch);
        return copy;
    }
    private boolean sameSession(JsonObject message) {
        if (message == null) return false;
        String protocol = stringField(message, "protocol", 64);
        String incomingWorld = stringField(message, "worldId", 256);
        if (protocol == null || incomingWorld == null || !message.has("sessionEpoch")) return false;
        try { return PROTOCOL.equals(protocol) && worldId.equals(incomingWorld)
                && requiredGeneration(message, "sessionEpoch") == epoch; }
        catch (RuntimeException invalid) { return false; }
    }
    private void enqueue(JsonObject message) { enqueue(message, epoch); }
    private void enqueue(JsonObject message, long sessionEpoch) {
        if (message == null) return;
        ArrayBlockingQueue<JsonObject> outgoing = outgoingBySession.computeIfAbsent(sessionEpoch, ignored -> new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY));
        if (!outgoing.offer(message.deepCopy())) {
            state = "transport_backpressure";
            Socket sessionSocket = socketsBySession.get(sessionEpoch);
            if (sessionSocket != null) try { sessionSocket.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Fill only the bounded event window. Queue saturation defers replay; it
     * must not tear down a healthy reconnect or remove retained events.
     * Ordinary responses still use enqueue(), whose existing backpressure
     * policy closes the session.
     */
    private void pumpEvents(long sessionEpoch) {
        if (!ready || epoch != sessionEpoch || closed) return;
        ArrayBlockingQueue<JsonObject> outgoing = outgoingBySession.computeIfAbsent(sessionEpoch, ignored -> new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY));
        for (;;) {
            List<EventWindow.Pending<JsonObject>> batch = unacknowledged.reserve(sessionEpoch, 1);
            if (batch.isEmpty()) break;
            EventWindow.Pending<JsonObject> pending = batch.get(0);
            JsonObject event = pending.payload().deepCopy();
            event.addProperty("sessionEpoch", sessionEpoch);
            if (!outgoing.offer(event)) {
                unacknowledged.release(sessionEpoch, pending.eventId());
                break;
            }
        }
    }
    private static JsonObject read(DataInputStream input) throws IOException {
        int size = input.readInt(); if (size <= 0 || size > MAX_FRAME) throw new IOException("invalid frame size");
        byte[] bytes = input.readNBytes(size); if (bytes.length != size) throw new EOFException();
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("frame must contain a JSON object");
            return parsed.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException | IllegalStateException malformed) {
            throw new IOException("malformed UTF-8 JSON frame", malformed);
        }
    }
    private static void write(DataOutputStream output, JsonObject message) throws IOException {
        byte[] bytes = JSON.toJson(message).getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_FRAME) throw new IOException("frame too large");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private void closeSocket() { try { if (socket != null) socket.close(); } catch (IOException ignored) {} }
    @Override public void close() {
        closed = true; ready = false; state = "closed";
        for (Socket connection : socketsBySession.values()) try { connection.close(); } catch (IOException ignored) { }
        socketsBySession.clear(); closeSocket();
    }
}
