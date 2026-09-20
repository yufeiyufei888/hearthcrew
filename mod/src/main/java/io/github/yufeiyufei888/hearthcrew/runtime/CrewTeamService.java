package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import io.github.yufeiyufei888.hearthcrew.kernel.team.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Shared task ownership. Only BodyExecutor may turn an accepted work definition into game effects. */
public final class CrewTeamService {
    public static final int CAPACITY = 4096;
    public record Position(int x, int y, int z) {
        public Position {
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2048 || y > 2048)
                throw new IllegalArgumentException("team position outside bounds");
        }
        BlockPos block() { return new BlockPos(x, y, z); }
    }
    public record Work(String taskId, String intentId, UUID botId, long bodyGeneration, String dimension,
                       BodyOrder.Kind kind, Position position, UUID target, int count, String resource, String description,
                       List<BodyOrder.BuildStep> steps) {
        public Work(String taskId, String intentId, UUID botId, long bodyGeneration, String dimension,
                    BodyOrder.Kind kind, Position position, UUID target, int count, String resource, String description) {
            this(taskId, intentId, botId, bodyGeneration, dimension, kind, position, target, count, resource, description, List.of());
        }
        public Work {
            required(taskId, 128); required(intentId, 128); Objects.requireNonNull(botId); Objects.requireNonNull(kind);
            ResourceLocation.parse(dimension);
            if (bodyGeneration < 0 || count < 0 || count > 100_000) throw new IllegalArgumentException("invalid work generation/count");
            if (resource != null) ResourceLocation.parse(resource);
            description = description == null ? "" : description;
            if (description.length() > 2000) throw new IllegalArgumentException("task description too long");
            steps = steps == null ? List.of() : List.copyOf(steps);
            // Apply the same immutable blueprint contract to wire requests and saved definitions.
            new BodyOrder(kind, position == null ? null : position.block(), target, count,
                    resource == null ? null : ResourceLocation.parse(resource), steps);
        }
        BodyOrder order() { return new BodyOrder(kind, position == null ? null : position.block(), target, count,
                resource == null ? null : ResourceLocation.parse(resource), steps); }
    }
    public record Binding(String taskId, String actionId, long bodyGeneration, String entityId) {
        public Binding { required(taskId, 128); required(actionId, 128); UUID.fromString(entityId); if (bodyGeneration < 0) throw new IllegalArgumentException("negative body generation"); }
    }
    /** Durable ownership of a finalized local GATHER candidate set. */
    public record GatherClaim(String actionId, UUID bodyId, String entityId, long bodyGeneration, String dimension, List<Position> positions) {
        public GatherClaim {
            required(actionId, 128); Objects.requireNonNull(bodyId); UUID.fromString(Objects.requireNonNull(entityId)); ResourceLocation.parse(dimension);
            if (bodyGeneration < 0 || positions == null || positions.isEmpty() || positions.size() > 64)
                throw new IllegalArgumentException("invalid gather claim");
            positions = List.copyOf(positions);
            if (new HashSet<>(positions).size() != positions.size()) throw new IllegalArgumentException("duplicate gather claim position");
        }
    }
    public record Saved(String worldId, TeamLedgerSnapshot ledger, List<Work> works, List<Binding> bindings,
                        List<GatherClaim> gatherClaims) {
        public Saved(String worldId, TeamLedgerSnapshot ledger, List<Work> works, List<Binding> bindings) {
            this(worldId, ledger, works, bindings, List.of());
        }
        public Saved {
            UUID.fromString(worldId); Objects.requireNonNull(ledger); works = List.copyOf(works); bindings = List.copyOf(bindings);
            gatherClaims = List.copyOf(gatherClaims == null ? List.of() : gatherClaims);
        }
    }
    private final MinecraftServer server;
    private final CrewWorldData data;
    private final String worldId;
    private final Map<String, Work> works = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, GatherClaim> gatherClaims = new LinkedHashMap<>();
    private TeamLedger ledger;
    private String invalidReason;
    private long revision;

    public CrewTeamService(MinecraftServer server, CrewWorldData data) {
        this.server = Objects.requireNonNull(server); this.data = Objects.requireNonNull(data); worldId = data.worldId().toString();
        ledger = new TeamLedger(new WorldEpoch(1, 1, 0), CAPACITY);
        CompoundTag archive = data.teamArchive();
        if (archive != null) {
            try {
                Saved saved = TeamStateCodec.decode(archive);
                if (!worldId.equals(saved.worldId()) || saved.works().size() != saved.ledger().tasks().size()) throw new IllegalArgumentException("team archive world or task count mismatch");
                ledger = TeamLedger.restore(saved.ledger(), CAPACITY);
                for (Work work : saved.works()) {
                    TeamTaskSnapshot task = ledger.taskSnapshot(TeamTaskId.of(work.taskId()));
                    if (works.putIfAbsent(work.taskId(), work) != null || task == null
                            || !task.task().fingerprint().equals(fingerprint(work)) || !worldId.equals(task.task().worldId()))
                        throw new IllegalArgumentException("team archive definition mismatch");
                }
                for (Binding binding : saved.bindings()) {
                    Work work = works.get(binding.taskId());
                    if (work == null || binding.bodyGeneration() != work.bodyGeneration()
                            || !binding.actionId().equals(actionId(work)) || bindings.putIfAbsent(binding.taskId(), binding) != null)
                        throw new IllegalArgumentException("team archive binding mismatch");
                }
                for (GatherClaim claim : saved.gatherClaims()) {
                    String key = gatherClaimKey(claim.bodyId(), claim.actionId());
                    if (gatherClaims.putIfAbsent(key, claim) != null) throw new IllegalArgumentException("duplicate gather claim");
                }
                if (gatherClaimsConflictEachOther() || gatherClaimsConflictLedger())
                    throw new IllegalArgumentException("saved gather claim conflicts with an existing reservation");
                for (Work work : works.values()) {
                    TeamTaskSnapshot task = ledger.taskSnapshot(TeamTaskId.of(work.taskId()));
                    if (task.started() != bindings.containsKey(work.taskId()))
                        throw new IllegalArgumentException("team archive started task and binding do not correspond");
                }
                WorldEpoch old = ledger.epoch();
                ledger.advanceEpoch(new WorldEpoch(old.worldGeneration(), Math.addExact(old.sessionGeneration(), 1), 0), "world reopened; reconcile saved tasks before new work");
            } catch (RuntimeException invalid) {
                invalidReason = "saved team state requires repair: " + invalid.getClass().getSimpleName();
                works.clear(); bindings.clear(); gatherClaims.clear(); // The original archive remains untouched in SavedData.
            }
        }
        if (invalidReason == null) persist();
    }
    public Map<String, Object> snapshot() {
        requireServerThread();
        return Map.of("worldId", worldId, "revision", revision, "recoveryInvalid", invalidReason != null,
                "reason", invalidReason == null ? "" : invalidReason, "ledger", ledger.snapshot(),
                "works", List.copyOf(works.values()), "bindings", List.copyOf(bindings.values()),
                "gatherClaims", List.copyOf(gatherClaims.values()), "inventoryReservations", inventoryReservations());
    }
    private List<Map<String, Object>> inventoryReservations() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (invalidReason != null) return result;
        for (var task : ledger.snapshot().tasks()) for (var held : task.reservedQuantities().entrySet()) {
            if (held.getKey().address() instanceof ResourceKey.ItemQuantityAddress item)
                result.add(Map.of("owner", item.owner(), "item", item.itemId(), "count", held.getValue(),
                        "dimension", held.getKey().dimension(), "taskId", task.task().id().value()));
        }
        return List.copyOf(result);
    }
    public TeamResult<TeamTaskSnapshot> propose(Work work) {
        return propose(work, ActionPriority.MISSION);
    }
    public TeamResult<TeamTaskSnapshot> propose(Work work, ActionPriority priority) {
        if (priority != ActionPriority.MISSION && priority != ActionPriority.OWNER) throw new IllegalArgumentException("invalid team origin priority");
        requireUsable(); syncClock(); requireUsable();
        Work existing = works.get(work.taskId());
        if (existing != null) {
            if (!existing.equals(work) || ledger.taskSnapshot(TeamTaskId.of(work.taskId())).task().priority() != priority) throw new IllegalArgumentException("team task identity conflicts with its definition");
            return new TeamResult<>(TeamDecision.IDEMPOTENT_REPLAY, ledger.taskSnapshot(TeamTaskId.of(work.taskId())), "task already recorded; inspect actual state");
        }
        CompanionEntity body = body(work.botId()); validateBody(work, body);
        if (body.executor().stopped() || body.executor().paused() || body.executor().recoveryInvalid()) throw new IllegalStateException("body is unavailable for team work");
        for (Work prior : works.values()) {
            if (!prior.botId().equals(work.botId())) continue;
            TeamTaskState state = ledger.taskSnapshot(TeamTaskId.of(prior.taskId())).state();
            if (!state.terminal() || state == TeamTaskState.PARTIAL || state == TeamTaskState.RECONCILE_REQUIRED)
                throw new IllegalStateException("body already owns team task " + prior.taskId() + "; observe its outcome before proposing another");
        }
        if (work.kind() == BodyOrder.Kind.MINE && work.position() != null) {
            if (!body.level().hasChunkAt(work.position().block())) throw new IllegalStateException("mining target is not loaded; observe before proposing");
            if (body.level().getBlockState(work.position().block()).isAir()) throw new IllegalStateException("mining target is already air; observe the completed work before proposing again");
        }
        Set<io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey> resources = resources(work, body);
        if (resources.stream().anyMatch(this::gatherClaimConflicts))
            throw new IllegalStateException("team proposal conflicts with a reserved GATHER candidate");
        TeamTask task = new TeamTask(TeamTaskId.of(work.taskId()), fingerprint(work), worldId, work.dimension(),
                priority, ledger.epoch(), resources, 2400, work.description());
        if (!archiveFits(task, work)) return new TeamResult<>(TeamDecision.REJECTED_CAPACITY, null, "team archive capacity would be exceeded");
        TeamResult<TeamTaskSnapshot> result = ledger.submit(task, ledger.gameTick());
        if (result.decision() == TeamDecision.ACCEPTED) { works.put(work.taskId(), work); persist(); requireUsable(); }
        return result;
    }
    /**
     * Admission gate for autonomous actions that are not represented by a
     * team task. It observes only durable reservations; it never submits or
     * mutates an action, so BodyExecutor remains the sole world writer.
     */
    public void assertIndependentActionAllowed(CompanionEntity body, BodyOrder order) {
        requireUsable(); syncClock(); requireUsable(); Objects.requireNonNull(body); Objects.requireNonNull(order);
        if (hasActiveTeamTask(body)) throw new IllegalStateException("body already owns an unsettled team task; execute or reconcile taskId");
        if (order.kind() == BodyOrder.Kind.GATHER) {
            if (hasActiveTeamTask(body)) throw new IllegalStateException("GATHER body already owns an active team task");
            return;
        }
        Set<ResourceKey> requested = independentResources(body, order);
        for (TeamTaskSnapshot task : ledger.snapshot().tasks()) {
            for (ResourceKey held : task.reservedResources()) {
                if (requested.stream().anyMatch(held::conflicts)) {
                    throw new IllegalStateException("independent action conflicts with team task " + task.task().id().value());
                }
            }
        }
        if (requested.stream().anyMatch(this::gatherClaimConflicts))
            throw new IllegalStateException("independent action conflicts with a reserved GATHER candidate");
    }

    /** Pure admission query for a finalized candidate set; it never writes the archive. */
    public boolean canClaimGather(CompanionEntity body, String actionId, List<BlockPos> positions) {
        requireServerThread();
        if (invalidReason != null || body == null || !body.isAlive() || findBody(body.companionId()) != body
                || actionId == null || actionId.isBlank() || actionId.length() > 128 || positions == null) return false;
        try {
            GatherClaim candidate = gatherClaim(body, actionId, positions);
            GatherClaim existing = gatherClaims.get(gatherClaimKey(candidate.bodyId(), candidate.actionId()));
            if (existing != null) return existing.equals(candidate) && sameBody(existing, body);
            return !gatherClaimsConflict(candidate) && !gatherClaimsConflictLedger(candidate)
                    && !hasActiveTeamTask(body) && archiveFitsGather(candidate);
        } catch (RuntimeException invalid) { return false; }
    }

    /** Atomically reserves a finalized, bounded local-GATHER candidate set. */
    public boolean tryClaimGather(CompanionEntity body, String actionId, List<BlockPos> positions) {
        requireUsable(); syncClock(); requireUsable(); Objects.requireNonNull(body);
        required(actionId, 128); Objects.requireNonNull(positions);
        if (findBody(body.companionId()) != body || !body.isAlive()) throw new IllegalStateException("GATHER body incarnation is unavailable");
        GatherClaim claim = gatherClaim(body, actionId, positions);
        String key = gatherClaimKey(claim.bodyId(), claim.actionId());
        GatherClaim existing = gatherClaims.get(key);
        if (existing != null) {
            if (!existing.equals(claim) || !sameBody(existing, body)) throw new IllegalStateException("GATHER action id already owns a different candidate set");
            return true;
        }
        if (!canClaimGather(body, actionId, positions)) return false;
        gatherClaims.put(key, claim);
        persist(); requireUsable();
        return true;
    }

    /** Admission gate used immediately before the local executor accepts the claimed GATHER. */
    public void assertIndependentGatherAllowed(CompanionEntity body, String actionId) {
        requireUsable(); syncClock(); requireUsable(); Objects.requireNonNull(body); required(actionId, 128);
        GatherClaim claim = gatherClaims.get(gatherClaimKey(body.companionId(), actionId));
        if (claim == null || !sameBody(claim, body) || hasActiveTeamTask(body))
            throw new IllegalStateException("GATHER candidate claim is missing, stale, or belongs to another body generation");
        if (gatherClaimsConflictLedger(claim)) throw new IllegalStateException("GATHER claim conflicts with a team reservation");
    }

    public boolean ownsGatherBlock(CompanionEntity body, String actionId, BlockPos position) {
        requireServerThread();
        if (invalidReason != null || body == null || actionId == null || position == null) return false;
        GatherClaim claim = gatherClaims.get(gatherClaimKey(body.companionId(), actionId));
        return claim != null && sameBody(claim, body)
                && claim.positions().contains(new Position(position.getX(), position.getY(), position.getZ()));
    }
    public List<GatherClaim> gatherClaims() { requireServerThread(); return List.copyOf(gatherClaims.values()); }

    /** Wire entrypoint: the current owner intent is part of the idempotency guard. */
    public Object execute(String taskId, UUID botId, long generation, String intentId) {
        requireUsable(); syncClock(); requireUsable();
        Work work = works.get(taskId);
        if (work == null || !work.botId().equals(botId) || work.bodyGeneration() != generation) throw new IllegalArgumentException("task owner/generation does not match");
        if (intentId == null || !work.intentId().equals(intentId)) throw new IllegalArgumentException("task owner intent does not match");
        CompanionEntity body = body(botId); validateBody(work, body);
        if (resources(work, body).stream().anyMatch(this::gatherClaimConflicts))
            throw new IllegalStateException("team execution conflicts with a reserved GATHER candidate");
        if (bindings.containsKey(taskId)) return Map.of("state", "RECONCILE_REQUIRED", "task", ledger.taskSnapshot(TeamTaskId.of(taskId)), "message", "bound action already recorded; never replay");
        TeamResult<TeamTaskSnapshot> claim = ledger.claim(TeamTaskId.of(taskId), TeamBodyId.of(botId.toString()), generation, ledger.gameTick(), Map.of());
        if (claim.decision() != TeamDecision.ACCEPTED && claim.decision() != TeamDecision.IDEMPOTENT_REPLAY) return claim;
        Binding binding = new Binding(taskId, actionId(work), generation, body.getUUID().toString());
        bindings.put(taskId, binding);
        ledger.start(TeamTaskId.of(taskId), ledger.gameTick());
        ledger.checkpoint(TeamTaskId.of(taskId), checkpoint(body, work));
        persist(); // Save the binding before handing execution to the sole writer.
        requireUsable();
        try {
            ActionReceipt<BodyOrder> receipt = body.executor().submit(binding.actionId(), work.order(), ledger.taskSnapshot(TeamTaskId.of(taskId)).task().priority());
            if (receipt.decision() != ReceiptDecision.ACCEPTED && receipt.decision() != ReceiptDecision.IDEMPOTENT_REPLAY) {
                ledger.finish(TeamTaskId.of(taskId), TeamTaskState.FAILED, "executor rejected task: " + receipt.decision()); persist();
            }
            return receipt;
        } catch (RuntimeException rejected) {
            // Even a throw may occur after submission; retain the binding for the tick observer to reconcile.
            ledger.bodyUnavailable(TeamBodyId.of(botId.toString()), "executor submission uncertain: " + rejected.getClass().getSimpleName()); persist(); throw rejected;
        }
    }
    public Work bindingForAction(UUID botId, String actionId) {
        requireServerThread();
        if (botId == null || actionId == null) return null;
        for (Binding binding : bindings.values()) {
            Work work = works.get(binding.taskId());
            if (work != null && work.botId().equals(botId) && binding.actionId().equals(actionId)) return work;
        }
        return null;
    }
    /** Explicit owner cancellation may retire uncertain work only after this body has been stopped or retasked. */
    public void ownerRetiredWork(CompanionEntity body) {
        retireBelow(body, ActionPriority.STOP);
    }
    public void retireOrdinaryWork(CompanionEntity body) { retireBelow(body, ActionPriority.GUARD); }
    public void retireAutonomousWork(CompanionEntity body) { retireBelow(body, ActionPriority.OWNER); }
    public boolean intentMayBind(CompanionEntity body, String intentId) {
        requireUsable();
        for (Work work : works.values()) {
            if (!work.botId().equals(body.companionId())) continue;
            TeamTaskState state = ledger.taskSnapshot(TeamTaskId.of(work.taskId())).state();
            if ((!state.terminal() || state == TeamTaskState.PARTIAL || state == TeamTaskState.RECONCILE_REQUIRED)
                    && !work.intentId().equals(intentId)) return false;
        }
        return true;
    }
    private void retireBelow(CompanionEntity body, ActionPriority threshold) {
        requireUsable(); syncClock(); requireUsable();
        if (body.executor().arbiter().activeSnapshot().filter(a -> threshold.outranks(a.priority())).isPresent()) throw new IllegalStateException("owner cancellation must quiesce ordinary work first");
        // stop/retask also retires suspended checkpoints. Check every known binding rather than treating idle as proof.
        for (Work work : works.values()) {
            if (!work.botId().equals(body.companionId())) continue;
            if (!threshold.outranks(ledger.taskSnapshot(TeamTaskId.of(work.taskId())).task().priority())) continue;
            Binding binding = bindings.get(work.taskId());
            if (binding != null && body.executor().arbiter().snapshot(ActionId.of(binding.actionId()))
                    .map(action -> !action.state().terminal()).orElse(false)) throw new IllegalStateException("old task still has a live body lease");
        }
        tick(); boolean changed = false;
        for (Work work : works.values()) {
            if (!work.botId().equals(body.companionId())) continue;
            if (!threshold.outranks(ledger.taskSnapshot(TeamTaskId.of(work.taskId())).task().priority())) continue;
            TeamTaskId id = TeamTaskId.of(work.taskId()); TeamTaskSnapshot task = ledger.taskSnapshot(id);
            if (task.state() == TeamTaskState.PLANNED || task.state() == TeamTaskState.CLAIMED) {
                ledger.cancel(id, "owner retired unstarted team work"); changed = true;
            } else if (task.state() == TeamTaskState.PARTIAL || task.state() == TeamTaskState.RECONCILE_REQUIRED) {
                Binding binding = bindings.get(work.taskId());
                if (binding == null || binding.bodyGeneration() != body.bodyGeneration()
                        || !binding.entityId().equals(body.getUUID().toString())) continue;
                var actual = body.executor().arbiter().snapshot(ActionId.of(binding.actionId()));
                // An idle replacement body is not evidence about its predecessor.
                // Keep unknown requests and reservations until their exact action can be observed.
                if (actual.isEmpty() || !actual.get().payload().equals(work.order())
                        || !Set.of(ActionState.COMPLETED, ActionState.PARTIAL, ActionState.CANCELLED, ActionState.FAILED).contains(actual.get().state())) continue;
                WorldEpoch evidenceEpoch = new WorldEpoch(ledger.epoch().worldGeneration(), ledger.epoch().sessionGeneration(), task.bodyGeneration());
                String evidence = "observed-retired-action:" + binding.actionId() + ":" + actual.get().state()
                        + ":" + checkpoint(body, work).facts();
                boolean completed = actual.get().state() == ActionState.COMPLETED;
                ledger.reconcile(id, new TeamReconciliation(completed ? TeamTaskState.COMPLETED : TeamTaskState.CANCELLED,
                        evidence, completed, evidenceEpoch, ledger.gameTick(),
                        "owner retired exact observed action; partial effects are retained and inventory was not altered"));
                changed = true;
            }
        }
        if (changed) { persist(); requireUsable(); }
    }
    public void tick() {
        requireServerThread(); if (invalidReason != null) return;
        syncClock(); if (invalidReason != null) return; boolean changed = false;
        for (GatherClaim claim : List.copyOf(gatherClaims.values())) {
            CompanionEntity body = findBody(claim.bodyId());
            if (body == null || !sameBody(claim, body)) continue;
            var action = body.executor().arbiter().snapshot(ActionId.of(claim.actionId()));
            if (action.isEmpty() || action.get().payload().kind() != BodyOrder.Kind.GATHER) continue;
            if (Set.of(ActionState.COMPLETED, ActionState.CANCELLED, ActionState.FAILED, ActionState.PARTIAL).contains(action.get().state())) {
                gatherClaims.remove(gatherClaimKey(claim.bodyId(), claim.actionId())); changed = true;
            }
        }
        for (Binding binding : bindings.values()) {
            TeamTaskSnapshot task = ledger.taskSnapshot(TeamTaskId.of(binding.taskId()));
            if (task.state() != TeamTaskState.RUNNING && task.state() != TeamTaskState.CLAIMED) continue;
            Work work = works.get(binding.taskId());
            CompanionEntity body = findBody(work.botId());
            if (body == null || body.bodyGeneration() != binding.bodyGeneration() || !body.getUUID().toString().equals(binding.entityId())) {
                ledger.bodyUnavailable(TeamBodyId.of(work.botId().toString()), "body incarnation unavailable; inspect world before resolving task"); changed = true; continue;
            }
            var action = body.executor().arbiter().snapshot(ActionId.of(binding.actionId()));
            if (action.isEmpty()) { ledger.bodyUnavailable(TeamBodyId.of(work.botId().toString()), "bound action missing; reconciliation required"); changed = true; continue; }
            if (!action.get().payload().equals(work.order())) { ledger.bodyUnavailable(TeamBodyId.of(work.botId().toString()), "bound action semantics changed"); changed = true; continue; }
            if (action.get().state().terminal()) {
                TeamTaskState state = switch (action.get().state()) {
                    case COMPLETED -> TeamTaskState.COMPLETED;
                    case PARTIAL -> TeamTaskState.PARTIAL;
                    case CANCELLED -> TeamTaskState.CANCELLED;
                    case FAILED, EXPIRED -> TeamTaskState.FAILED;
                    default -> TeamTaskState.RECONCILE_REQUIRED;
                };
                if (state == TeamTaskState.RECONCILE_REQUIRED) ledger.bodyUnavailable(TeamBodyId.of(work.botId().toString()), "body result requires reconciliation");
                else ledger.finish(TeamTaskId.of(binding.taskId()), state, action.get().message());
                changed = true;
            }
        }
        if (changed) persist();
    }
    private Set<io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey> resources(Work work, CompanionEntity body) {
        Set<io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey> keys = new LinkedHashSet<>();
        keys.add(io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey.entity(worldId, work.dimension(), body.getUUID().toString()));
        switch (work.kind()) {
            case BUILD -> {
                for (BodyOrder.BuildStep step : work.steps()) {
                    BlockPos p = step.position();
                    if (!body.level().hasChunkAt(p) || body.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p)) > 32 * 32)
                        throw new IllegalArgumentException("blueprint target must be loaded and within 32 blocks");
                    keys.add(ResourceKey.block(worldId, work.dimension(), p.getX(), p.getY(), p.getZ()));
                }
            }
            case MINE, PLACE -> {
                if (work.position() == null) throw new IllegalArgumentException("work requires block position");
                Position p = work.position(); keys.add(io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey.block(worldId, work.dimension(), p.x(), p.y(), p.z()));
            }
            case TRANSFER -> {
                if (work.target() == null || work.resource() == null || work.count() < 1) throw new IllegalArgumentException("transfer requires recipient, item and count");
                var recipient = ((ServerLevel)body.level()).getEntity(work.target());
                if (!(recipient instanceof CompanionEntity other) || !other.isAlive() || other == body) throw new IllegalArgumentException("team recipient must be another live companion");
                keys.add(io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey.entity(worldId, work.dimension(), other.getUUID().toString()));
            }
            case CRAFT -> { if (work.resource() == null || work.count() < 1) throw new IllegalArgumentException("craft requires recipe and count"); }
            case GATHER -> throw new IllegalArgumentException("team GATHER is not supported; finalize a local candidate claim first");
            case MOVE -> { if (work.position() == null) throw new IllegalArgumentException("move requires position"); }
            case WAIT, SELECT, EAT -> { }
            default -> throw new IllegalArgumentException("team work kind not supported yet: " + work.kind());
        }
        return Set.copyOf(keys);
    }
    private Set<ResourceKey> independentResources(CompanionEntity body, BodyOrder order) {
        Set<ResourceKey> keys = new LinkedHashSet<>();
        String dimension = body.level().dimension().location().toString();
        keys.add(ResourceKey.entity(worldId, dimension, body.getUUID().toString()));
        if (order.kind() == BodyOrder.Kind.BUILD) for (BodyOrder.BuildStep step : order.steps()) {
            BlockPos pos = step.position(); keys.add(ResourceKey.block(worldId, dimension, pos.getX(), pos.getY(), pos.getZ()));
        }
        if (order.kind() == BodyOrder.Kind.MINE || order.kind() == BodyOrder.Kind.PLACE) {
            if (order.position() == null) throw new IllegalArgumentException("independent action requires block position");
            BlockPos pos = order.position(); keys.add(ResourceKey.block(worldId, dimension, pos.getX(), pos.getY(), pos.getZ()));
        }
        if (order.kind() == BodyOrder.Kind.TRANSFER) {
            if (order.target() == null) throw new IllegalArgumentException("independent transfer requires target");
            var recipient = ((ServerLevel)body.level()).getEntity(order.target());
            if (recipient == null || !recipient.isAlive()) throw new IllegalArgumentException("independent transfer target unavailable");
            keys.add(ResourceKey.entity(worldId, dimension, recipient.getUUID().toString()));
        }
        return Set.copyOf(keys);
    }
    private TeamCheckpoint checkpoint(CompanionEntity body, Work work) {
        Map<String, String> facts = new LinkedHashMap<>(); facts.put("entityId", body.getUUID().toString());
        facts.put("dimension", body.level().dimension().location().toString()); facts.put("position", body.blockPosition().toShortString());
        facts.put("inventory", body.inventory().createTag(body.registryAccess()).toString());
        if (work.kind() == BodyOrder.Kind.BUILD) for (int index = 0; index < work.steps().size(); index++) {
            BlockPos pos = work.steps().get(index).position();
            facts.put("buildStep" + index, body.level().hasChunkAt(pos) ? body.level().getBlockState(pos).toString() : "UNLOADED");
        }
        if (work.position() != null) facts.put("targetBlock", body.level().hasChunkAt(work.position().block())
                ? body.level().getBlockState(work.position().block()).toString() : "UNLOADED");
        if (work.kind() == BodyOrder.Kind.TRANSFER && work.target() != null) {
            var recipient = ((ServerLevel) body.level()).getEntity(work.target());
            if (recipient instanceof CompanionEntity other && other.isAlive()) {
                facts.put("recipientEntity", other.getUUID().toString());
                facts.put("recipientGeneration", Long.toString(other.bodyGeneration()));
                facts.put("recipientInventory", other.inventory().createTag(other.registryAccess()).toString());
            } else facts.put("recipientEntity", "UNAVAILABLE");
        }
        return new TeamCheckpoint(fingerprint(work), facts, new WorldEpoch(ledger.epoch().worldGeneration(), ledger.epoch().sessionGeneration(), work.bodyGeneration()), ledger.gameTick());
    }
    private static String gatherClaimKey(UUID bodyId, String actionId) { return bodyId + "|" + actionId; }
    private GatherClaim gatherClaim(CompanionEntity body, String actionId, List<BlockPos> positions) {
        if (positions.isEmpty() || positions.size() > 64) throw new IllegalArgumentException("GATHER claim must contain 1..64 positions");
        List<Position> normalized = positions.stream().map(Objects::requireNonNull)
                .map(position -> new Position(position.getX(), position.getY(), position.getZ())).toList();
        if (new HashSet<>(normalized).size() != normalized.size()) throw new IllegalArgumentException("GATHER claim contains duplicate positions");
        for (Position position : normalized) {
            BlockPos block = position.block();
            if (!body.level().hasChunkAt(block)
                    || body.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(block)) > 32 * 32)
                throw new IllegalArgumentException("GATHER claim position is unloaded or outside 32 block origin bound");
        }
        return new GatherClaim(actionId, body.companionId(), body.getUUID().toString(), body.bodyGeneration(),
                body.level().dimension().location().toString(), normalized);
    }
    private static boolean sameBody(GatherClaim claim, CompanionEntity body) {
        return claim.bodyId().equals(body.companionId())
                && claim.entityId().equals(body.getUUID().toString())
                && claim.bodyGeneration() == body.bodyGeneration()
                && claim.dimension().equals(body.level().dimension().location().toString());
    }
    private boolean archiveFitsGather(GatherClaim candidate) {
        List<GatherClaim> claims = new ArrayList<>(gatherClaims.values()); claims.add(candidate);
        return TeamStateCodec.fits(new Saved(worldId, ledger.snapshot(), List.copyOf(works.values()),
                List.copyOf(bindings.values()), claims));
    }
    private boolean hasActiveTeamTask(CompanionEntity body) {
        for (Work work : works.values()) {
            if (!work.botId().equals(body.companionId())) continue;
            TeamTaskSnapshot task = ledger.taskSnapshot(TeamTaskId.of(work.taskId()));
            if (task == null) continue;
            TeamTaskState state = task.state();
            if (!state.terminal() || state == TeamTaskState.PARTIAL || state == TeamTaskState.RECONCILE_REQUIRED) return true;
        }
        return false;
    }
    private Set<ResourceKey> gatherClaimResources(GatherClaim claim) {
        Set<ResourceKey> result = new LinkedHashSet<>();
        for (Position position : claim.positions()) result.add(ResourceKey.block(worldId, claim.dimension(), position.x(), position.y(), position.z()));
        return Set.copyOf(result);
    }
    private boolean gatherClaimConflicts(ResourceKey requested) {
        for (GatherClaim claim : gatherClaims.values())
            if (gatherClaimResources(claim).stream().anyMatch(requested::conflicts)) return true;
        return false;
    }
    private boolean gatherClaimsConflict(GatherClaim candidate) {
        Set<ResourceKey> requested = gatherClaimResources(candidate);
        for (GatherClaim existing : gatherClaims.values()) {
            if (existing.bodyId().equals(candidate.bodyId()) && existing.actionId().equals(candidate.actionId())) continue;
            if (requested.stream().anyMatch(key -> gatherClaimResources(existing).stream().anyMatch(key::conflicts))) return true;
        }
        return false;
    }
    private boolean gatherClaimsConflictEachOther() {
        List<GatherClaim> claims = List.copyOf(gatherClaims.values());
        for (int i = 0; i < claims.size(); i++) for (int j = i + 1; j < claims.size(); j++) {
            Set<ResourceKey> left = gatherClaimResources(claims.get(i));
            Set<ResourceKey> right = gatherClaimResources(claims.get(j));
            if (left.stream().anyMatch(key -> right.stream().anyMatch(key::conflicts))) return true;
        }
        return false;
    }
    private boolean gatherClaimsConflictLedger() {
        return gatherClaims.values().stream().anyMatch(this::gatherClaimsConflictLedger);
    }
    private boolean gatherClaimsConflictLedger(GatherClaim claim) {
        Set<ResourceKey> requested = gatherClaimResources(claim);
        return ledger.snapshot().tasks().stream()
                .anyMatch(task -> task.reservedResources().stream().anyMatch(held -> requested.stream().anyMatch(held::conflicts)));
    }
    private void syncClock() {
        TeamLedgerSnapshot before = ledger.snapshot();
        TeamLedgerSnapshot after = ledger.advanceTick(server.overworld().getGameTime(), false);
        if (!before.tasks().equals(after.tasks()) || !before.settlements().equals(after.settlements())) persist();
    }
    private boolean archiveFits(TeamTask task, Work work) {
        TeamLedgerSnapshot current = ledger.snapshot();
        List<TeamTaskSnapshot> tasks = new ArrayList<>(current.tasks());
        tasks.add(new TeamTaskSnapshot(task, TeamTaskState.PLANNED, null, work.bodyGeneration(), false, 0L,
                null, Set.of(), Map.of(), List.of(), current.epoch(), current.gameTick(), "planned"));
        List<Work> definitions = new ArrayList<>(works.values()); definitions.add(work);
        return TeamStateCodec.fits(new Saved(worldId,
                new TeamLedgerSnapshot(current.epoch(), current.gameTick(), tasks, current.settlements()),
                definitions, List.copyOf(bindings.values()), List.copyOf(gatherClaims.values())));
    }
    private void persist() {
        try { data.storeTeamArchive(TeamStateCodec.encode(new Saved(worldId, ledger.snapshot(), List.copyOf(works.values()), List.copyOf(bindings.values()), List.copyOf(gatherClaims.values())))); }
        catch (RuntimeException failure) {
            // Retain the previous valid archive. No additional task may claim resources or submit world effects.
            invalidReason = "team archive could not be recorded: " + failure.getClass().getSimpleName();
        }
        revision++;
    }
    private CompanionEntity findBody(UUID id) { return CrewWorldData.liveCompanions(server).stream().filter(body -> body.companionId().equals(id)).findFirst().orElse(null); }
    private CompanionEntity body(UUID id) { CompanionEntity body = findBody(id); if (body == null) throw new IllegalStateException("team body unavailable"); return body; }
    private static void validateBody(Work work, CompanionEntity body) {
        if (body.bodyGeneration() != work.bodyGeneration() || !work.dimension().equals(body.level().dimension().location().toString())) throw new IllegalArgumentException("team body generation or dimension changed");
    }
    private void requireUsable() { requireServerThread(); if (invalidReason != null) throw new IllegalStateException(invalidReason); }
    private void requireServerThread() { if (!server.isSameThread()) throw new IllegalStateException("team state belongs to the game thread"); }
    private static String actionId(Work work) { return "team-" + fingerprint(work); }
    private static String fingerprint(Work work) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TeamStateCodec.definitionJson(work).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void required(String value, int max) { if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid team identity"); }
}
