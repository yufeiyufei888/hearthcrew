package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned coordination ledger for shared tasks and reservations.
 *
 * All methods are synchronized because the intended adapter calls them on the
 * game thread while reconnect/recovery tests may submit from another thread.
 * This class never edits an inventory or world; it only records leases,
 * reservations and observed settlement evidence.
 */
public final class TeamLedger {
    private static final int DEFAULT_CAPACITY = 4096;
    private final int capacity;
    private final Map<TeamTaskId, TaskEntry> tasks = new LinkedHashMap<>();
    private final Map<TeamSettlementId, SettlementEntry> settlements = new LinkedHashMap<>();
    private WorldEpoch epoch;
    private long gameTick;

    public TeamLedger(WorldEpoch epoch) { this(epoch, DEFAULT_CAPACITY); }

    public TeamLedger(WorldEpoch epoch, int capacity) {
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized WorldEpoch epoch() { return epoch; }
    public synchronized long gameTick() { return gameTick; }

    public synchronized TeamResult<TeamTaskSnapshot> submit(TeamTask task, long tick) {
        Objects.requireNonNull(task, "task");
        if (tick < gameTick) return result(null, TeamDecision.REJECTED_STALE, "submission tick is behind ledger clock");
        TaskEntry existing = tasks.get(task.id());
        if (existing != null) {
            if (!existing.task.equals(task)) return result(snapshot(existing), TeamDecision.REJECTED_ID_CONFLICT, "task id has different immutable semantics");
            return result(snapshot(existing), TeamDecision.IDEMPOTENT_REPLAY, "task already registered");
        }
        if (!sameTeamEpoch(task.epoch(), epoch)) return result(null, TeamDecision.REJECTED_STALE, "task world/session epoch does not match current ledger epoch");
        if (tasks.size() + settlements.size() >= capacity) return result(null, TeamDecision.REJECTED_CAPACITY, "team ledger capacity reached; archive or start a new session");
        TaskEntry entry = new TaskEntry(task, tick);
        tasks.put(task.id(), entry);
        return result(snapshot(entry), TeamDecision.ACCEPTED, "task registered");
    }

    /** Claims all task resources and one body atomically, or claims none. */
    public synchronized TeamResult<TeamTaskSnapshot> claim(TeamTaskId id, TeamBodyId body, long tick) {
        return claim(id, body, 0L, tick, Map.of());
    }

    /** Claims using the body's current generation and observed available quantities. */
    public synchronized TeamResult<TeamTaskSnapshot> claim(TeamTaskId id, TeamBodyId body, long bodyGeneration,
                                                            long tick, Map<ResourceKey, Long> observedAvailable) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        if (!validTick(tick)) return result(snapshot(entry), TeamDecision.REJECTED_STALE, "claim tick is behind ledger clock");
        if (entry.state.terminal()) return result(snapshot(entry), decisionFor(entry.state), "task is terminal");
        if (entry.assignedBody != null) {
            return entry.assignedBody.equals(body) && entry.bodyGeneration == bodyGeneration
                    ? result(snapshot(entry), TeamDecision.IDEMPOTENT_REPLAY, "task already claimed by body")
                    : entry.assignedBody.equals(body)
                    ? result(snapshot(entry), TeamDecision.REJECTED_REVALIDATION, "body generation changed; reconcile before rebind")
                    : result(snapshot(entry), TeamDecision.REJECTED_BUSY, "task is claimed by another body");
        }
        if (!sameTeamEpoch(entry.task.epoch(), epoch)) return result(snapshot(entry), TeamDecision.REJECTED_STALE, "task world/session epoch is stale");
        TaskEntry bodyLease = activeLease(body);
        if (bodyLease != null) return result(snapshot(entry), TeamDecision.REJECTED_BUSY, "body already owns an active team lease");
        String reservationConflict = reservationConflict(entry, observedAvailable);
        if (reservationConflict != null) {
            return result(snapshot(entry), TeamDecision.REJECTED_RESOURCE_CONFLICT, reservationConflict);
        }
        entry.assignedBody = Objects.requireNonNull(body, "body");
        entry.bodyGeneration = bodyGeneration;
        entry.reservedResources = Set.copyOf(entry.task.resources());
        entry.reservedQuantities = entry.task.reservedQuantities();
        entry.state = TeamTaskState.CLAIMED;
        entry.leaseExpiresAt = safeAdd(gameTick, entry.task.leaseTicks());
        entry.updatedGameTick = gameTick;
        entry.message = "claimed by " + body.value();
        return result(snapshot(entry), TeamDecision.ACCEPTED, entry.message);
    }

    /** Explicitly ends the previous lease before attempting a higher-level retask. */
    public synchronized TeamResult<TeamTaskSnapshot> retask(TeamBodyId body, TeamTaskId oldId, TeamTaskId newId, long tick, String reason) {
        return retask(body, oldId, newId, 0L, tick, Map.of(), reason);
    }

    public synchronized TeamResult<TeamTaskSnapshot> retask(TeamBodyId body, TeamTaskId oldId, TeamTaskId newId,
                                                              long bodyGeneration, long tick,
                                                              Map<ResourceKey, Long> observedAvailable, String reason) {
        TaskEntry oldEntry = task(oldId);
        TaskEntry newEntry = task(newId);
        if (oldEntry == null || newEntry == null) return result(null, TeamDecision.REJECTED_INVALID, "retask task is unknown");
        if (!validTick(tick)) return result(snapshot(newEntry), TeamDecision.REJECTED_STALE, "retask tick is behind ledger clock");
        if (!body.equals(oldEntry.assignedBody)) return result(snapshot(newEntry), TeamDecision.REJECTED_BUSY, "old task is not owned by body");
        if (newEntry.assignedBody != null || newEntry.state.terminal()) return result(snapshot(newEntry), TeamDecision.REJECTED_BUSY, "new task is already claimed or terminal");
        if (oldEntry.started || oldEntry.state == TeamTaskState.RUNNING) {
            markReconcile(oldEntry, "retask: " + (reason == null ? "" : reason));
            return result(snapshot(oldEntry), TeamDecision.RECONCILE_REQUIRED, "started task must be reconciled before retask");
        } else {
            finishEntry(oldEntry, TeamTaskState.CANCELLED, reason == null ? "retasked before start" : reason, true);
        }
        TeamResult<TeamTaskSnapshot> claimed = claim(newId, body, bodyGeneration, tick, observedAvailable);
        if (claimed.decision() != TeamDecision.ACCEPTED && claimed.decision() != TeamDecision.IDEMPOTENT_REPLAY) return claimed;
        return claimed;
    }

    /**
     * Safe retask path for work that already started. The executor must first
     * stop at a known checkpoint and provide explicit reconciliation evidence;
     * only then are old reservations released and the new lease attempted.
     */
    public synchronized TeamResult<TeamTaskSnapshot> retaskAfterReconciliation(
            TeamBodyId body, TeamTaskId oldId, TeamTaskId newId, long bodyGeneration,
            TeamReconciliation evidence, long tick, Map<ResourceKey, Long> observedAvailable, String reason) {
        TaskEntry oldEntry = task(oldId);
        if (oldEntry == null || !body.equals(oldEntry.assignedBody)) return result(null, TeamDecision.REJECTED_INVALID, "old task is not owned by body");
        if (oldEntry.state.terminal() && oldEntry.state != TeamTaskState.PARTIAL && oldEntry.state != TeamTaskState.RECONCILE_REQUIRED) {
            return result(snapshot(oldEntry), decisionFor(oldEntry.state), "old task is already terminal");
        }
        if (!oldEntry.started && oldEntry.state == TeamTaskState.CLAIMED) {
            finishEntry(oldEntry, TeamTaskState.CANCELLED, reason == null ? "retasked before start" : reason, true);
        } else {
            if (oldEntry.state != TeamTaskState.RECONCILE_REQUIRED) markReconcile(oldEntry, reason == null ? "retask reconciliation" : reason);
            TeamResult<TeamTaskSnapshot> reconciled = reconcile(oldId, evidence);
            if (reconciled.decision() != TeamDecision.COMPLETED && reconciled.decision() != TeamDecision.FAILED
                    && reconciled.decision() != TeamDecision.CANCELLED && reconciled.decision() != TeamDecision.STALE) return reconciled;
        }
        return claim(newId, body, bodyGeneration, tick, observedAvailable);
    }

    /**
     * Rebinds an uncertain lease only after a fresh body-generation checkpoint.
     * The adapter must independently verify the entity represented by the typed
     * body/generation pair. The free-form checkpoint facts are evidence for that
     * adapter contract; they are deliberately not treated as an entity identity.
     */
    public synchronized TeamResult<TeamTaskSnapshot> rebind(TeamTaskId id, TeamBodyId body, long bodyGeneration,
                                                              TeamCheckpoint checkpoint, long tick) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (entry.state != TeamTaskState.RECONCILE_REQUIRED) return result(snapshot(entry), TeamDecision.REJECTED_REVALIDATION, "task is not awaiting reconciliation");
        if (!entry.task.fingerprint().equals(checkpoint.fingerprint())) {
            return result(snapshot(entry), TeamDecision.REJECTED_ID_CONFLICT, "rebind checkpoint fingerprint does not match task");
        }
        if (!validTick(tick) || !sameTeamEpoch(checkpoint.epoch(), epoch) || checkpoint.epoch().bodyGeneration() != bodyGeneration) {
            return result(snapshot(entry), TeamDecision.REJECTED_STALE, "rebind checkpoint is stale");
        }
        TaskEntry occupied = activeLease(body);
        if (occupied != null && occupied != entry) return result(snapshot(entry), TeamDecision.REJECTED_BUSY, "body already owns another active lease");
        entry.assignedBody = body;
        entry.bodyGeneration = bodyGeneration;
        entry.checkpoint = checkpoint;
        entry.state = TeamTaskState.CLAIMED;
        entry.leaseExpiresAt = safeAdd(gameTick, entry.task.leaseTicks());
        entry.updatedGameTick = gameTick;
        entry.message = "rebound after reconciliation checkpoint";
        return result(snapshot(entry), TeamDecision.ACCEPTED, entry.message);
    }

    public synchronized TeamResult<TeamTaskSnapshot> start(TeamTaskId id, long tick) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        if (!validTick(tick)) return result(snapshot(entry), TeamDecision.REJECTED_STALE, "start tick is behind ledger clock");
        if (entry.state == TeamTaskState.RUNNING) return result(snapshot(entry), TeamDecision.IDEMPOTENT_REPLAY, "task already running");
        if (entry.state != TeamTaskState.CLAIMED) return result(snapshot(entry), decisionFor(entry.state), "task is not claimable");
        entry.state = TeamTaskState.RUNNING;
        entry.started = true;
        entry.updatedGameTick = gameTick;
        entry.message = "running";
        return result(snapshot(entry), TeamDecision.ACCEPTED, "task started");
    }

    public synchronized TeamResult<TeamTaskSnapshot> checkpoint(TeamTaskId id, TeamCheckpoint checkpoint) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (entry.state.terminal()) return result(snapshot(entry), decisionFor(entry.state), "task is terminal");
        if (!sameTeamEpoch(checkpoint.epoch(), epoch) || checkpoint.epoch().bodyGeneration() != entry.bodyGeneration) {
            return result(snapshot(entry), TeamDecision.REJECTED_STALE, "checkpoint world/session/body generation is stale");
        }
        entry.checkpoint = checkpoint;
        entry.updatedGameTick = gameTick;
        entry.message = "checkpoint " + checkpoint.fingerprint();
        return result(snapshot(entry), TeamDecision.ACCEPTED, "checkpoint saved");
    }

    public synchronized TeamResult<TeamTaskSnapshot> finish(TeamTaskId id, TeamTaskState state, String message) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        if (entry.state == state) return result(snapshot(entry), decisionFor(state), "idempotent terminal finish");
        if (!state.terminal() || state == TeamTaskState.RECONCILE_REQUIRED) return result(snapshot(entry), TeamDecision.REJECTED_INVALID, "finish needs a terminal non-reconcile state");
        if (entry.state.terminal() && entry.state != TeamTaskState.PARTIAL) return result(snapshot(entry), decisionFor(entry.state), "task is already terminal");
        finishEntry(entry, state, message == null ? "" : message, state != TeamTaskState.PARTIAL);
        return result(snapshot(entry), decisionFor(state), entry.message);
    }

    public synchronized TeamResult<TeamTaskSnapshot> cancel(TeamTaskId id, String message) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        if (entry.state.terminal()) return result(snapshot(entry), decisionFor(entry.state), "task is already terminal");
        if (entry.started || entry.state == TeamTaskState.RUNNING) {
            markReconcile(entry, message == null ? "cancelled while running" : message);
            return result(snapshot(entry), TeamDecision.RECONCILE_REQUIRED, entry.message);
        }
        finishEntry(entry, TeamTaskState.CANCELLED, message == null ? "cancelled" : message, true);
        return result(snapshot(entry), TeamDecision.CANCELLED, entry.message);
    }

    public synchronized TeamResult<TeamSettlementSnapshot> prepareSettlement(TeamSettlementRequest request, long tick) {
        Objects.requireNonNull(request, "request");
        SettlementEntry existing = settlements.get(request.id());
        if (existing != null) {
            if (!existing.request.equals(request)) return settlementResult(snapshot(existing), TeamDecision.REJECTED_ID_CONFLICT, "settlement id has different immutable semantics");
            return settlementResult(snapshot(existing), TeamDecision.IDEMPOTENT_REPLAY, "settlement already prepared");
        }
        if (!validTick(tick)) return settlementResult(null, TeamDecision.REJECTED_STALE, "settlement tick is behind ledger clock");
        if (tasks.size() + settlements.size() >= capacity) return settlementResult(null, TeamDecision.REJECTED_CAPACITY, "team ledger capacity reached");
        TaskEntry task = task(request.taskId());
        if (task == null) return settlementResult(null, TeamDecision.REJECTED_INVALID, "unknown settlement task");
        if (task.state != TeamTaskState.CLAIMED && task.state != TeamTaskState.RUNNING) return settlementResult(null, decisionFor(task.state), "settlement task is not active");
        for (TeamSettlementId existingId : task.settlementIds) {
            SettlementEntry existingEntry = settlements.get(existingId);
            if (existingEntry != null && existingEntry.state != TeamSettlementState.COMPLETED) {
                return settlementResult(null, TeamDecision.REJECTED_RESOURCE_CONFLICT, "an unfinished settlement already exists for this task");
            }
        }
        for (Map.Entry<ResourceKey, Long> expected : request.expectedDelta().entrySet()) {
            long reserved = quantityReservedFor(task, expected.getKey());
            if (reserved < 0 || exceedsMagnitude(expected.getValue(), reserved)) {
                return settlementResult(null, TeamDecision.REJECTED_RESOURCE_CONFLICT, "settlement quantity is outside the task reservation");
            }
        }
        for (SettlementEntry other : settlements.values()) {
            if (!other.request.taskId().equals(request.taskId()) || other.state == TeamSettlementState.COMPLETED) continue;
            if (resourcesConflict(request.expectedDelta().keySet(), other.request.expectedDelta().keySet())) {
                return settlementResult(null, TeamDecision.REJECTED_RESOURCE_CONFLICT, "overlapping settlement is already prepared for this task");
            }
        }
        SettlementEntry entry = new SettlementEntry(request, gameTick);
        settlements.put(request.id(), entry);
        task.started = true;
        task.state = TeamTaskState.RUNNING;
        task.settlementIds.add(request.id());
        task.updatedGameTick = gameTick;
        task.message = "settlement prepared";
        return settlementResult(snapshot(entry), TeamDecision.ACCEPTED, "settlement prepared");
    }

    public synchronized TeamResult<TeamTaskSnapshot> settle(TeamSettlementObservation observation) {
        Objects.requireNonNull(observation, "observation");
        SettlementEntry entry = settlements.get(observation.id());
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown settlement");
        if (!entry.request.fingerprint().equals(observation.fingerprint())) return result(taskSnapshot(entry.request.taskId()), TeamDecision.REJECTED_ID_CONFLICT, "settlement fingerprint mismatch");
        if (entry.state == TeamSettlementState.COMPLETED || entry.state == TeamSettlementState.PARTIAL) {
            return result(taskSnapshot(entry.request.taskId()), TeamDecision.IDEMPOTENT_REPLAY, "settlement already observed");
        }
        if (entry.state == TeamSettlementState.RECONCILE_REQUIRED) {
            return result(taskSnapshot(entry.request.taskId()), TeamDecision.RECONCILE_REQUIRED, "settlement requires reconciliation");
        }
        if (!observation.before().keySet().equals(observation.after().keySet()) || !observation.before().keySet().equals(entry.request.expectedDelta().keySet())) {
            markSettlementReconcile(entry, "before/after quantity keys do not match prepared delta");
            return result(taskSnapshot(entry.request.taskId()), TeamDecision.RECONCILE_REQUIRED, entry.message);
        }
        gameTick = Math.max(gameTick, observation.gameTick());
        boolean exact = true;
        boolean validPartial = true;
        for (ResourceKey key : entry.request.expectedDelta().keySet()) {
            long expected = entry.request.expectedDelta().get(key);
            long actual = observation.after().get(key) - observation.before().get(key);
            if (actual != expected) exact = false;
            if (!towards(expected, actual)) validPartial = false;
        }
        TaskEntry task = task(entry.request.taskId());
        if (exact) {
            entry.state = TeamSettlementState.COMPLETED;
            entry.before = observation.before(); entry.after = observation.after(); entry.updatedGameTick = observation.gameTick(); entry.message = "settled";
            finishEntry(task, TeamTaskState.COMPLETED, "settlement completed", true);
            return result(snapshot(task), TeamDecision.COMPLETED, "settlement completed");
        }
        if (validPartial) {
            entry.state = TeamSettlementState.PARTIAL;
            entry.before = observation.before(); entry.after = observation.after(); entry.updatedGameTick = observation.gameTick(); entry.message = "partial observed settlement";
            task.state = TeamTaskState.PARTIAL; task.updatedGameTick = observation.gameTick(); task.message = entry.message;
            return result(snapshot(task), TeamDecision.PARTIAL, entry.message);
        }
        markSettlementReconcile(entry, "observed quantity delta did not match prepared semantics");
        task.state = TeamTaskState.RECONCILE_REQUIRED; task.updatedGameTick = observation.gameTick(); task.message = entry.message;
        return result(snapshot(task), TeamDecision.RECONCILE_REQUIRED, entry.message);
    }

    public synchronized TeamResult<TeamTaskSnapshot> reconcile(TeamTaskId id, TeamReconciliation reconciliation) {
        TaskEntry entry = task(id);
        if (entry == null) return result(null, TeamDecision.REJECTED_INVALID, "unknown task");
        if (entry.state != TeamTaskState.RECONCILE_REQUIRED && entry.state != TeamTaskState.PARTIAL) return result(snapshot(entry), TeamDecision.REJECTED_REVALIDATION, "task does not need reconciliation");
        if (reconciliation.resolvedState() == TeamTaskState.COMPLETED && !reconciliation.effectConfirmed()) {
            return result(snapshot(entry), TeamDecision.REJECTED_REVALIDATION, "completed reconciliation requires confirmed world effect");
        }
        if (!sameTeamEpoch(reconciliation.epoch(), epoch)) return result(snapshot(entry), TeamDecision.REJECTED_STALE, "reconciliation world/session epoch is stale");
        if (entry.assignedBody != null && reconciliation.epoch().bodyGeneration() != entry.bodyGeneration) {
            return result(snapshot(entry), TeamDecision.REJECTED_STALE, "reconciliation body generation is stale");
        }
        if (reconciliation.gameTick() < entry.updatedGameTick) {
            return result(snapshot(entry), TeamDecision.REJECTED_STALE, "reconciliation observation tick predates task evidence");
        }
        gameTick = Math.max(gameTick, reconciliation.gameTick());
        entry.state = reconciliation.resolvedState();
        entry.updatedGameTick = reconciliation.gameTick();
        entry.message = reconciliation.message();
        if (entry.state != TeamTaskState.PARTIAL) {
            entry.reservedResources = Set.of();
            entry.reservedQuantities = Map.of();
        }
        return result(snapshot(entry), decisionFor(entry.state), entry.message);
    }

    /** Paused game time does not move leases or expiry. */
    public synchronized TeamLedgerSnapshot advanceTick(long tick, boolean paused) {
        if (paused) return snapshot();
        if (tick < gameTick) throw new IllegalArgumentException("game tick cannot move backwards");
        gameTick = tick;
        for (TaskEntry entry : tasks.values()) {
            if ((entry.state == TeamTaskState.CLAIMED || entry.state == TeamTaskState.RUNNING) && entry.leaseExpiresAt <= gameTick) {
                if (entry.started || entry.state == TeamTaskState.RUNNING) markReconcile(entry, "lease expired after world effects may have started");
                else finishEntry(entry, TeamTaskState.EXPIRED, "lease expired before start", true);
            }
        }
        return snapshot();
    }

    /** World/session/body replacement invalidates active work without releasing uncertain reservations. */
    public synchronized TeamLedgerSnapshot advanceEpoch(WorldEpoch next, String reason) {
        Objects.requireNonNull(next, "next");
        if (sameTeamEpoch(next, epoch)) { epoch = next; return snapshot(); }
        epoch = next;
        for (TaskEntry entry : tasks.values()) {
            if (entry.state == TeamTaskState.PLANNED) {
                finishEntry(entry, TeamTaskState.STALE, reason == null ? "epoch changed" : reason, true);
            } else if (!entry.state.terminal() || entry.state == TeamTaskState.PARTIAL) {
                markReconcile(entry, reason == null ? "epoch changed" : reason);
            }
        }
        return snapshot();
    }

    public synchronized TeamLedgerSnapshot bodyUnavailable(TeamBodyId body, String reason) {
        Objects.requireNonNull(body, "body");
        for (TaskEntry entry : tasks.values()) {
            if (!body.equals(entry.assignedBody) || entry.state.terminal() && entry.state != TeamTaskState.PARTIAL) continue;
            if (entry.started || entry.state == TeamTaskState.RUNNING) markReconcile(entry, reason == null ? "body unavailable" : reason);
            else finishEntry(entry, TeamTaskState.FAILED, reason == null ? "body unavailable before start" : reason, true);
        }
        return snapshot();
    }

    public synchronized TeamTaskSnapshot taskSnapshot(TeamTaskId id) {
        TaskEntry entry = task(id);
        return entry == null ? null : snapshot(entry);
    }

    public synchronized TeamSettlementSnapshot settlementSnapshot(TeamSettlementId id) {
        SettlementEntry entry = settlements.get(id);
        return entry == null ? null : snapshot(entry);
    }

    public synchronized TeamLedgerSnapshot snapshot() {
        List<TeamTaskSnapshot> taskSnapshots = tasks.values().stream().map(this::snapshot).toList();
        List<TeamSettlementSnapshot> settlementSnapshots = settlements.values().stream().map(this::snapshot).toList();
        return new TeamLedgerSnapshot(epoch, gameTick, taskSnapshots, settlementSnapshots);
    }

    /** Restored active leases become reconcile-required; no world action is replayed. */
    public static TeamLedger restore(TeamLedgerSnapshot saved, int capacity) {
        Objects.requireNonNull(saved, "saved");
        validateSavedSnapshot(saved);
        TeamLedger ledger = new TeamLedger(saved.epoch(), capacity);
        ledger.gameTick = saved.gameTick();
        for (TeamTaskSnapshot value : saved.tasks()) {
            if (ledger.tasks.containsKey(value.task().id())) throw new IllegalArgumentException("duplicate task in snapshot");
            TaskEntry entry = new TaskEntry(value.task(), value.updatedGameTick());
            entry.state = value.state();
            entry.assignedBody = value.assignedBody();
            entry.bodyGeneration = value.bodyGeneration();
            entry.started = value.started();
            entry.leaseExpiresAt = value.leaseExpiresAt();
            entry.checkpoint = value.checkpoint();
            entry.reservedResources = Set.copyOf(value.reservedResources());
            entry.reservedQuantities = Map.copyOf(value.reservedQuantities());
            entry.settlementIds.addAll(value.settlements());
            entry.updatedGameTick = value.updatedGameTick();
            entry.message = value.message();
            if (entry.state == TeamTaskState.CLAIMED || entry.state == TeamTaskState.RUNNING) {
                entry.state = TeamTaskState.RECONCILE_REQUIRED;
                entry.started = true;
                entry.message = "restored active lease requires reconciliation";
            }
            ledger.tasks.put(entry.task.id(), entry);
        }
        for (TeamSettlementSnapshot value : saved.settlements()) {
            if (ledger.settlements.containsKey(value.request().id())) throw new IllegalArgumentException("duplicate settlement in snapshot");
            SettlementEntry entry = new SettlementEntry(value.request(), value.updatedGameTick());
            entry.state = value.state(); entry.before = value.before(); entry.after = value.after(); entry.updatedGameTick = value.updatedGameTick(); entry.message = value.message();
            ledger.settlements.put(entry.request.id(), entry);
        }
        if (ledger.tasks.size() + ledger.settlements.size() > capacity) throw new IllegalArgumentException("snapshot exceeds ledger capacity");
        return ledger;
    }

    private static void validateSavedSnapshot(TeamLedgerSnapshot saved) {
        Map<TeamTaskId, TeamTaskSnapshot> taskMap = new LinkedHashMap<>();
        for (TeamTaskSnapshot value : saved.tasks()) {
            if (taskMap.put(value.task().id(), value) != null) throw new IllegalArgumentException("duplicate task in snapshot");
            if (!definitionEpochCompatible(value, saved.epoch())) throw new IllegalArgumentException("task definition epoch is future or from another world");
            if (!sameTeamEpoch(value.observedEpoch(), saved.epoch())) throw new IllegalArgumentException("task observed epoch is not from saved world/session");
            if (!value.reservedQuantities().keySet().equals(value.reservedResources())) throw new IllegalArgumentException("snapshot reservation keys disagree");
            if (value.state() == TeamTaskState.PLANNED && (value.assignedBody() != null || !value.reservedResources().isEmpty())) {
                throw new IllegalArgumentException("planned task cannot own a lease or reservation");
            }
            if (Set.of(TeamTaskState.COMPLETED, TeamTaskState.FAILED, TeamTaskState.CANCELLED,
                    TeamTaskState.EXPIRED, TeamTaskState.STALE).contains(value.state()) && !value.reservedResources().isEmpty()) {
                throw new IllegalArgumentException("released terminal task still owns resources");
            }
            if ((value.state() == TeamTaskState.CLAIMED || value.state() == TeamTaskState.RUNNING
                    || value.state() == TeamTaskState.PARTIAL || value.state() == TeamTaskState.RECONCILE_REQUIRED)
                    && (value.assignedBody() == null || value.reservedResources().isEmpty())) {
                throw new IllegalArgumentException("active or uncertain task is missing body or reservation");
            }
            if ((value.state() == TeamTaskState.CLAIMED || value.state() == TeamTaskState.RUNNING
                    || value.state() == TeamTaskState.PARTIAL || value.state() == TeamTaskState.RECONCILE_REQUIRED)
                    && !value.reservedQuantities().equals(value.task().reservedQuantities())) {
                throw new IllegalArgumentException("active or uncertain task reservation quantity changed");
            }
            if (value.checkpoint() != null && (!sameTeamEpoch(value.checkpoint().epoch(), saved.epoch())
                    || value.assignedBody() != null && value.checkpoint().epoch().bodyGeneration() != value.bodyGeneration())) {
                throw new IllegalArgumentException("snapshot checkpoint has an unrelated epoch");
            }
            if (new LinkedHashSet<>(value.settlements()).size() != value.settlements().size()) throw new IllegalArgumentException("duplicate task settlement link");
            for (Map.Entry<ResourceKey, Long> quantity : value.reservedQuantities().entrySet()) {
                if (!value.task().reservedQuantities().containsKey(quantity.getKey()) || quantity.getValue() == null || quantity.getValue() < 1) {
                    throw new IllegalArgumentException("snapshot reservation quantity is not task-owned");
                }
            }
        }
        Map<TeamSettlementId, TeamSettlementSnapshot> settlementMap = new LinkedHashMap<>();
        for (TeamSettlementSnapshot value : saved.settlements()) {
            if (settlementMap.put(value.request().id(), value) != null) throw new IllegalArgumentException("duplicate settlement in snapshot");
            TeamTaskSnapshot task = taskMap.get(value.request().taskId());
            if (task == null || !task.settlements().contains(value.request().id())) throw new IllegalArgumentException("settlement is not linked from its task");
            if (!sameTeamEpoch(task.task().epoch(), saved.epoch())) throw new IllegalArgumentException("settlement task epoch is stale");
            if (value.state() != TeamSettlementState.COMPLETED) {
                for (Map.Entry<ResourceKey, Long> expected : value.request().expectedDelta().entrySet()) {
                    long reserved = quantityReservedForSnapshot(task, expected.getKey());
                    if (reserved < 0 || exceedsMagnitude(expected.getValue(), reserved)) throw new IllegalArgumentException("settlement exceeds snapshot reservation");
                }
            }
            if (value.state() != TeamSettlementState.PREPARED && !value.before().keySet().equals(value.after().keySet())) {
                throw new IllegalArgumentException("settlement before/after keys disagree");
            }
        }
        for (TeamTaskSnapshot task : saved.tasks()) {
            long unfinished = task.settlements().stream()
                    .map(settlementMap::get)
                    .filter(Objects::nonNull)
                    .filter(settlement -> settlement.state() != TeamSettlementState.COMPLETED)
                    .count();
            if (unfinished > 1) throw new IllegalArgumentException("task has multiple unfinished settlements");
        }
        for (TeamTaskSnapshot task : saved.tasks()) {
            for (TeamSettlementId id : task.settlements()) {
                TeamSettlementSnapshot settlement = settlementMap.get(id);
                if (settlement == null || !settlement.request().taskId().equals(task.task().id())) throw new IllegalArgumentException("cross-linked settlement snapshot");
            }
        }
        List<TeamSettlementSnapshot> settlementValues = new ArrayList<>(settlementMap.values());
        for (int i = 0; i < settlementValues.size(); i++) {
            TeamSettlementSnapshot left = settlementValues.get(i);
            if (left.state() == TeamSettlementState.COMPLETED) continue;
            for (int j = i + 1; j < settlementValues.size(); j++) {
                TeamSettlementSnapshot right = settlementValues.get(j);
                if (left.request().taskId().equals(right.request().taskId())
                        && right.state() != TeamSettlementState.COMPLETED
                        && resourcesConflict(left.request().expectedDelta().keySet(), right.request().expectedDelta().keySet())) {
                    throw new IllegalArgumentException("overlapping prepared settlement snapshot");
                }
            }
        }
    }

    private static long quantityReservedForSnapshot(TeamTaskSnapshot task, ResourceKey requested) {
        long total = 0;
        for (Map.Entry<ResourceKey, Long> held : task.reservedQuantities().entrySet()) {
            if (requested.conflicts(held.getKey())) total = safeAdd(total, held.getValue());
        }
        return total;
    }

    private static boolean definitionEpochCompatible(TeamTaskSnapshot snapshot, WorldEpoch savedEpoch) {
        WorldEpoch definition = snapshot.task().epoch();
        if (definition.worldGeneration() != savedEpoch.worldGeneration() || definition.sessionGeneration() > savedEpoch.sessionGeneration()) return false;
        if (definition.sessionGeneration() == savedEpoch.sessionGeneration()) return true;
        if (snapshot.state() == TeamTaskState.RECONCILE_REQUIRED) return true;
        if (!snapshot.state().terminal()) return snapshot.checkpoint() != null
                && sameTeamEpoch(snapshot.checkpoint().epoch(), savedEpoch);
        return true;
    }

    private TaskEntry task(TeamTaskId id) { return tasks.get(Objects.requireNonNull(id, "task id")); }

    private TaskEntry activeLease(TeamBodyId body) {
        for (TaskEntry entry : tasks.values()) {
            if (body.equals(entry.assignedBody) && (!entry.state.terminal() || entry.state == TeamTaskState.RECONCILE_REQUIRED
                    || entry.state == TeamTaskState.PARTIAL || !entry.reservedResources.isEmpty())) return entry;
        }
        return null;
    }

    private boolean validTick(long tick) { return tick >= gameTick; }

    private static boolean resourcesConflict(Set<ResourceKey> left, Set<ResourceKey> right) {
        for (ResourceKey a : left) for (ResourceKey b : right) if (a.conflicts(b)) return true;
        return false;
    }

    private String reservationConflict(TaskEntry candidate, Map<ResourceKey, Long> observedAvailable) {
        Map<ResourceKey, Long> available = observedAvailable == null ? Map.of() : observedAvailable;
        for (Map.Entry<ResourceKey, Long> requested : candidate.task.reservedQuantities().entrySet()) {
            ResourceKey requestedKey = requested.getKey();
            long quantity = requested.getValue();
            if (requestedKey.address() instanceof ResourceKey.ItemQuantityAddress) {
                Long observed = available.get(requestedKey);
                if (observed == null || observed < 0) return "item quantity availability is required for " + requestedKey;
                long alreadyReserved = 0;
                for (TaskEntry other : tasks.values()) {
                    if (other == candidate || other.reservedQuantities.isEmpty()) continue;
                    for (Map.Entry<ResourceKey, Long> held : other.reservedQuantities.entrySet()) {
                        if (requestedKey.conflicts(held.getKey())) alreadyReserved = safeAdd(alreadyReserved, held.getValue());
                    }
                }
                if (observed - alreadyReserved < quantity) return "insufficient observed item quantity for " + requestedKey;
            } else {
                for (TaskEntry other : tasks.values()) {
                    if (other == candidate || other.reservedResources.isEmpty()) continue;
                    if (resourcesConflict(Set.of(requestedKey), other.reservedResources)) {
                        return "a reserved resource conflicts with task " + other.task.id().value();
                    }
                }
            }
        }
        return null;
    }

    private static long quantityReservedFor(TaskEntry task, ResourceKey requested) {
        long total = 0;
        for (Map.Entry<ResourceKey, Long> held : task.reservedQuantities.entrySet()) {
            if (requested.conflicts(held.getKey())) total = safeAdd(total, held.getValue());
        }
        return total;
    }

    private static boolean exceedsMagnitude(long value, long limit) {
        if (value == Long.MIN_VALUE) return true;
        return Math.abs(value) > limit;
    }

    private static boolean sameTeamEpoch(WorldEpoch left, WorldEpoch right) {
        return left.worldGeneration() == right.worldGeneration() && left.sessionGeneration() == right.sessionGeneration();
    }

    private static boolean towards(long expected, long actual) {
        if (expected == 0) return actual == 0;
        return expected > 0 ? actual >= 0 && actual <= expected : actual <= 0 && actual >= expected;
    }

    private static long safeAdd(long left, long right) {
        if (right < 0 || left > Long.MAX_VALUE - right) throw new IllegalArgumentException("lease tick overflow");
        return left + right;
    }

    private static TeamDecision decisionFor(TeamTaskState state) {
        return switch (state) {
            case COMPLETED -> TeamDecision.COMPLETED;
            case PARTIAL -> TeamDecision.PARTIAL;
            case FAILED -> TeamDecision.FAILED;
            case CANCELLED -> TeamDecision.CANCELLED;
            case EXPIRED -> TeamDecision.EXPIRED;
            case STALE -> TeamDecision.STALE;
            case RECONCILE_REQUIRED -> TeamDecision.RECONCILE_REQUIRED;
            default -> TeamDecision.ACCEPTED;
        };
    }

    private static <T> TeamResult<T> result(T value, TeamDecision decision, String message) { return new TeamResult<>(decision, value, message); }
    private static TeamResult<TeamSettlementSnapshot> settlementResult(TeamSettlementSnapshot value, TeamDecision decision, String message) { return result(value, decision, message); }

    private void finishEntry(TaskEntry entry, TeamTaskState state, String message, boolean release) {
        entry.state = state; entry.message = message == null ? "" : message; entry.updatedGameTick = gameTick;
        if (release) {
            entry.reservedResources = Set.of();
            entry.reservedQuantities = Map.of();
        }
    }

    private void markReconcile(TaskEntry entry, String reason) {
        entry.state = TeamTaskState.RECONCILE_REQUIRED; entry.started = true; entry.updatedGameTick = gameTick; entry.message = reason == null ? "reconciliation required" : reason;
    }

    private void markSettlementReconcile(SettlementEntry entry, String reason) {
        entry.state = TeamSettlementState.RECONCILE_REQUIRED; entry.message = reason == null ? "settlement reconciliation required" : reason; entry.updatedGameTick = gameTick;
        TaskEntry task = task(entry.request.taskId());
        if (task != null) markReconcile(task, entry.message);
    }

    private TeamTaskSnapshot snapshot(TaskEntry entry) {
        return new TeamTaskSnapshot(entry.task, entry.state, entry.assignedBody, entry.bodyGeneration, entry.started, entry.leaseExpiresAt,
                entry.checkpoint, entry.reservedResources, entry.reservedQuantities, List.copyOf(entry.settlementIds), epoch, entry.updatedGameTick, entry.message);
    }

    private TeamSettlementSnapshot snapshot(SettlementEntry entry) {
        return new TeamSettlementSnapshot(entry.request, entry.state, entry.before, entry.after, entry.updatedGameTick, entry.message);
    }

    private static final class TaskEntry {
        final TeamTask task;
        TeamTaskState state = TeamTaskState.PLANNED;
        TeamBodyId assignedBody;
        long bodyGeneration;
        boolean started;
        long leaseExpiresAt;
        TeamCheckpoint checkpoint;
        Set<ResourceKey> reservedResources = Set.of();
        Map<ResourceKey, Long> reservedQuantities = Map.of();
        final List<TeamSettlementId> settlementIds = new ArrayList<>();
        long updatedGameTick;
        String message = "";
        TaskEntry(TeamTask task, long updatedGameTick) { this.task = task; this.updatedGameTick = updatedGameTick; }
    }

    private static final class SettlementEntry {
        final TeamSettlementRequest request;
        TeamSettlementState state = TeamSettlementState.PREPARED;
        Map<ResourceKey, Long> before = Map.of();
        Map<ResourceKey, Long> after = Map.of();
        long updatedGameTick;
        String message = "";
        SettlementEntry(TeamSettlementRequest request, long updatedGameTick) { this.request = request; this.updatedGameTick = updatedGameTick; }
    }
}
