package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Thread-safe single-body action state machine.
 *
 * <p>It deliberately does not execute game code. The Minecraft server-thread
 * executor owns execution and calls the transition methods after observing
 * game postconditions. All methods are synchronized so a bridge thread cannot
 * race a server tick or a safety callback.</p>
 */
public final class ActionArbiter<T, C> {
    public static final long DEFAULT_MAX_REQUEST_AGE = 40;
    public static final int DEFAULT_MAX_JOURNAL_ENTRIES = 512;

    private final long maxRequestAge;
    private final int maxJournalEntries;
    private final int maxActionEntries;
    private final Map<ActionId, MutableAction<T, C>> actions = new HashMap<>();
    private final List<ActionReceipt<T>> journal = new ArrayList<>();
    private long sequence;
    private long worldTick;
    private long gameTick;
    private long lastWorldTick;
    private long activeWorldBaseline;
    private boolean paused;
    private WorldEpoch epoch;
    private ActionId activeId;

    public ActionArbiter(WorldEpoch initialEpoch) {
        this(initialEpoch, DEFAULT_MAX_REQUEST_AGE, DEFAULT_MAX_JOURNAL_ENTRIES);
    }

    public ActionArbiter(WorldEpoch initialEpoch, long maxRequestAge, int maxJournalEntries) {
        this(initialEpoch, maxRequestAge, maxJournalEntries, maxJournalEntries);
    }

    /** Receipt transport history and persistent identity retention have different budgets. */
    public ActionArbiter(WorldEpoch initialEpoch, long maxRequestAge, int maxJournalEntries, int maxActionEntries) {
        this.epoch = Objects.requireNonNull(initialEpoch, "initialEpoch");
        if (maxRequestAge < 0 || maxJournalEntries < 1 || maxActionEntries < maxJournalEntries) {
            throw new IllegalArgumentException("retention and request age must be positive");
        }
        this.maxRequestAge = maxRequestAge;
        this.maxJournalEntries = maxJournalEntries;
        this.maxActionEntries = maxActionEntries;
    }

    /** Submit an immutable request. Accepted work is not started until start(). */
    public synchronized ActionReceipt<T> submit(ActionRequest<T> request) {
        Objects.requireNonNull(request, "request");
        MutableAction<T, C> existing = actions.get(request.id());
        if (existing != null) {
            if (existing.sameRequest(request)) {
                return receipt(existing, ReceiptDecision.IDEMPOTENT_REPLAY, "same action already known", null);
            }
            return rejection(request, ReceiptDecision.REJECTED_ID_CONFLICT,
                    "action id is already bound to a different request", null);
        }
        if (!request.epoch().matches(epoch)) {
            return rejection(request, ReceiptDecision.REJECTED_STALE, "request epoch is stale", null);
        }
        if (request.submittedGameTick() > gameTick) {
            return rejection(request, ReceiptDecision.REJECTED_INVALID,
                    "request was submitted from a future game tick", null);
        }
        if (gameTick - request.submittedGameTick() > maxRequestAge) {
            return rejection(request, ReceiptDecision.REJECTED_STALE, "request observation is too old", null);
        }
        if (request.deadlineGameTick() >= 0 && gameTick > request.deadlineGameTick()) {
            return rejection(request, ReceiptDecision.REJECTED_DEADLINE, "request deadline has passed", null);
        }
        if (actions.size() >= maxActionEntries) {
            return rejection(request, ReceiptDecision.REJECTED_CAPACITY,
                    "action-id retention is full; archive this session before accepting more work", null);
        }
        MutableAction<T, C> current = activeId == null ? null : actions.get(activeId);
        if (current != null && !request.priority().outranks(current.priority)) {
            return rejection(request, ReceiptDecision.REJECTED_BUSY,
                    "another action has the single execution lease", current.id);
        }
        if (current != null) {
            transition(current, ActionState.SUSPENDED, ReceiptDecision.SUSPENDED,
                    "preempted by higher priority action", request.id());
            activeId = null;
        }
        MutableAction<T, C> accepted = new MutableAction<>(request);
        actions.put(request.id(), accepted);
        activeId = request.id();
        return receipt(accepted, ReceiptDecision.ACCEPTED, "accepted", null);
    }

    /** Start accepted work. Game mutation still belongs to the caller. */
    public synchronized ActionReceipt<T> start(ActionId id) {
        MutableAction<T, C> action = known(id);
        if (action == null) return unknown(id, ReceiptDecision.REJECTED_INVALID, "unknown action");
        if (action.state != ActionState.ACCEPTED) {
            return receipt(action, ReceiptDecision.IDEMPOTENT_REPLAY,
                    "action is not in accepted state", null);
        }
        if (!id.equals(activeId)) {
            return receipt(action, ReceiptDecision.REJECTED_BUSY,
                    "action does not hold the execution lease", activeId);
        }
        action.state = ActionState.RUNNING;
        return receipt(action, ReceiptDecision.STARTED, "running", null);
    }

    /** Store a server-observed checkpoint before a possible preemption. */
    public synchronized ActionReceipt<T> checkpoint(ActionId id, C checkpoint) {
        MutableAction<T, C> action = known(id);
        if (action == null) return unknown(id, ReceiptDecision.REJECTED_INVALID, "unknown action");
        if (action.state != ActionState.RUNNING || !id.equals(activeId)) {
            return receipt(action, ReceiptDecision.REJECTED_INVALID,
                    "only the running action may update its checkpoint", null);
        }
        action.checkpoint = checkpoint;
        return receipt(action, ReceiptDecision.ACCEPTED, "checkpoint recorded", null);
    }

    /** Revalidate a suspended checkpoint before allowing it to run again. */
    public synchronized ActionReceipt<T> resume(ActionId id, Predicate<? super C> revalidator) {
        Objects.requireNonNull(revalidator, "revalidator");
        MutableAction<T, C> action = known(id);
        if (action == null) return unknown(id, ReceiptDecision.REJECTED_INVALID, "unknown action");
        if (action.state != ActionState.SUSPENDED) {
            return receipt(action, ReceiptDecision.IDEMPOTENT_REPLAY,
                    "action is not suspended", null);
        }
        if (!action.epoch.matches(epoch)) {
            transition(action, ActionState.STALE, ReceiptDecision.STALE,
                    "suspended action epoch is stale", null);
            return lastReceipt(action.id);
        }
        if (activeId != null) {
            return receipt(action, ReceiptDecision.REJECTED_BUSY,
                    "another action holds the execution lease", activeId);
        }
        final boolean valid;
        try {
            valid = revalidator.test(action.checkpoint);
        } catch (RuntimeException failure) {
            transition(action, ActionState.STALE, ReceiptDecision.STALE,
                    "checkpoint revalidation failed: " + failure.getMessage(), null);
            return lastReceipt(action.id);
        }
        if (!valid) {
            transition(action, ActionState.STALE, ReceiptDecision.STALE,
                    "checkpoint no longer matches observed world state", null);
            return lastReceipt(action.id);
        }
        action.state = ActionState.RUNNING;
        activeId = id;
        return receipt(action, ReceiptDecision.RESUMED, "resumed after checkpoint validation", null);
    }

    /** Commit a result only after the executor has observed its postcondition. */
    public synchronized ActionReceipt<T> finish(ActionId id, ActionState terminalState, String message) {
        MutableAction<T, C> action = known(id);
        if (action == null) return unknown(id, ReceiptDecision.REJECTED_INVALID, "unknown action");
        if (terminalState == null || !terminalState.terminal()) {
            return receipt(action, ReceiptDecision.REJECTED_INVALID,
                    "finish requires a terminal state", null);
        }
        if (action.state.terminal()) {
            return receipt(action, ReceiptDecision.IDEMPOTENT_REPLAY,
                    "terminal state already committed", null);
        }
        transition(action, terminalState, decisionFor(terminalState), message, null);
        if (id.equals(activeId)) activeId = null;
        pruneJournal();
        return lastReceipt(id);
    }

    public synchronized ActionReceipt<T> cancel(ActionId id, String reason) {
        return finish(id, ActionState.CANCELLED, reason == null ? "cancelled" : reason);
    }

    /** Record model failure without touching healthy accepted/running body work. */
    public synchronized ActionReceipt<T> modelTimeout(ActionId id, String detail) {
        MutableAction<T, C> action = known(id);
        if (action == null) return unknown(id, ReceiptDecision.REJECTED_INVALID, "unknown action");
        return receipt(action, ReceiptDecision.MODEL_TIMEOUT,
                detail == null ? "model turn timed out; body work continues" : detail, null);
    }

    /** Advance both clocks. The game clock does not advance while paused. */
    public synchronized List<ActionReceipt<T>> advanceTick(long newWorldTick, boolean nowPaused) {
        if (newWorldTick < worldTick) {
            throw new IllegalArgumentException("world tick must be monotonic");
        }
        worldTick = newWorldTick;
        if (!nowPaused) {
            if (paused) {
                activeWorldBaseline = newWorldTick;
            } else {
                gameTick += newWorldTick - activeWorldBaseline;
                activeWorldBaseline = newWorldTick;
            }
        }
        paused = nowPaused;
        if (paused) return List.of();
        List<ActionReceipt<T>> expired = new ArrayList<>();
        for (MutableAction<T, C> action : List.copyOf(actions.values())) {
            if (!action.state.terminal() && action.deadlineGameTick >= 0
                    && gameTick > action.deadlineGameTick) {
                transition(action, ActionState.EXPIRED, ReceiptDecision.EXPIRED,
                        "game-time deadline elapsed", null);
                if (action.id.equals(activeId)) activeId = null;
                expired.add(lastReceipt(action.id));
            }
        }
        pruneJournal();
        return List.copyOf(expired);
    }

    /** Replace any one of the independent generations and stale all old work. */
    public synchronized List<ActionReceipt<T>> advanceEpoch(WorldEpoch newEpoch, String reason) {
        Objects.requireNonNull(newEpoch, "newEpoch");
        if (newEpoch.equals(epoch)) return List.of();
        epoch = newEpoch;
        List<ActionReceipt<T>> stale = new ArrayList<>();
        for (MutableAction<T, C> action : List.copyOf(actions.values())) {
            if (!action.state.terminal()) {
                transition(action, ActionState.STALE, ReceiptDecision.STALE,
                        reason == null ? "epoch advanced" : reason, null);
                stale.add(lastReceipt(action.id));
            }
        }
        activeId = null;
        pruneJournal();
        return List.copyOf(stale);
    }

    public synchronized Optional<ActionId> activeActionId() {
        return Optional.ofNullable(activeId);
    }

    /** Complete execution ownership, independent of the bounded receipt history. */
    public synchronized List<ActionId> suspendedActionIds() {
        return actions.values().stream().filter(action -> action.state == ActionState.SUSPENDED)
                .map(action -> action.id).toList();
    }

    /**
     * Restore identity tombstones from a body save without granting a lease.
     * Recorded terminal receipts remain historical facts. Incomplete/legacy
     * requests need reconciliation and cannot receive a new execution lease.
     * Validate the entire ledger before changing this empty arbiter.
     */
    public record SavedOutcome(ActionState state, String message) {
        public SavedOutcome { Objects.requireNonNull(state); Objects.requireNonNull(message); }
    }
    public synchronized void restoreForReconciliation(List<ActionRequest<T>> requests) { restoreSaved(requests, Map.of()); }
    public synchronized void restoreSaved(List<ActionRequest<T>> requests, Map<ActionId, SavedOutcome> outcomes) {
        Objects.requireNonNull(requests); Objects.requireNonNull(outcomes);
        if (!actions.isEmpty() || activeId != null) throw new IllegalStateException("restore requires an empty arbiter");
        if (requests.size() > maxActionEntries) throw new IllegalArgumentException("saved action ledger exceeds capacity");
        var ids = new java.util.HashSet<ActionId>();
        for (var request : requests) if (!ids.add(request.id())) throw new IllegalArgumentException("duplicate saved action identity");
        if (!ids.containsAll(outcomes.keySet())) throw new IllegalArgumentException("outcome has no request identity");
        for (var request : requests) {
            var action = new MutableAction<T, C>(request); actions.put(request.id(), action);
            var saved = outcomes.get(request.id());
            var state = saved != null && saved.state().terminal() ? saved.state() : ActionState.RECONCILE_REQUIRED;
            transition(action, state, decisionFor(state), saved != null && saved.state().terminal() ? (saved.message().startsWith("saved outcome restored:") || saved.message().startsWith("historical outcome restored:") ? saved.message() : "saved outcome restored: " + saved.message())
                    : "body loaded; unfinished or legacy effects require reconciliation; never replay", null);
        }
    }
    /** Metadata-only repair from a matching, previously recorded authoritative receipt. */
    public synchronized boolean restoreHistoricalOutcome(ActionRequest<T> expected, SavedOutcome outcome) {
        var action = actions.get(expected.id());
        if (action == null || !action.sameRequest(expected) || action.state != ActionState.RECONCILE_REQUIRED
                || !outcome.state().terminal() || outcome.state() == ActionState.RECONCILE_REQUIRED) return false;
        transition(action, outcome.state(), decisionFor(outcome.state()), "historical outcome restored: " + outcome.message(), null);
        return true;
    }

    public synchronized Optional<ActionSnapshot<T, C>> snapshot(ActionId id) {
        MutableAction<T, C> action = actions.get(id);
        if (action == null) return Optional.empty();
        return Optional.of(action.snapshot(worldTick, gameTick));
    }

    public synchronized Optional<ActionSnapshot<T, C>> activeSnapshot() {
        return activeId == null ? Optional.empty() : snapshot(activeId);
    }

    public synchronized List<ActionReceipt<T>> journal() {
        return List.copyOf(journal);
    }

    public synchronized WorldEpoch epoch() { return epoch; }
    public synchronized long worldTick() { return worldTick; }
    public synchronized long gameTick() { return gameTick; }
    public synchronized boolean paused() { return paused; }

    private MutableAction<T, C> known(ActionId id) {
        return id == null ? null : actions.get(id);
    }

    private ActionReceipt<T> unknown(ActionId id, ReceiptDecision decision, String message) {
        return new ActionReceipt<>(nextSequence(), id, ActionState.STALE, decision,
                ActionPriority.PERSONAL, null, epoch, worldTick, gameTick, message, null);
    }

    private ActionReceipt<T> rejection(ActionRequest<T> request, ReceiptDecision decision,
                                       String message, ActionId related) {
        return new ActionReceipt<>(nextSequence(), request.id(), ActionState.STALE, decision,
                request.priority(), request.payload(), request.epoch(), worldTick, gameTick, message, related);
    }

    private ActionReceipt<T> receipt(MutableAction<T, C> action, ReceiptDecision decision,
                                     String message, ActionId related) {
        ActionReceipt<T> receipt = new ActionReceipt<>(nextSequence(), action.id, action.state,
                decision, action.priority, action.payload, action.epoch, worldTick, gameTick, message, related);
        journal.add(receipt);
        pruneJournal();
        return receipt;
    }

    private void transition(MutableAction<T, C> action, ActionState state,
                            ReceiptDecision decision, String message, ActionId related) {
        action.state = state;
        action.message = message == null ? "" : message;
        receipt(action, decision, action.message, related);
    }

    private ActionReceipt<T> lastReceipt(ActionId id) {
        for (int i = journal.size() - 1; i >= 0; i--) {
            ActionReceipt<T> receipt = journal.get(i);
            if (Objects.equals(receipt.id(), id)) return receipt;
        }
        throw new IllegalStateException("transition receipt was evicted");
    }

    private ReceiptDecision decisionFor(ActionState state) {
        return switch (state) {
            case COMPLETED -> ReceiptDecision.COMPLETED;
            case PARTIAL -> ReceiptDecision.PARTIAL;
            case FAILED -> ReceiptDecision.FAILED;
            case CANCELLED -> ReceiptDecision.CANCELLED;
            case EXPIRED -> ReceiptDecision.EXPIRED;
            case STALE -> ReceiptDecision.STALE;
            case RECONCILE_REQUIRED -> ReceiptDecision.RECONCILE_REQUIRED;
            default -> ReceiptDecision.REJECTED_INVALID;
        };
    }

    private long nextSequence() { return sequence++; }

    private void pruneJournal() {
        // Journal receipts are observations, not the idempotence store. They
        // may be dropped for any state, including an in-flight action; the
        // action record and its checkpoint remain in the bounded action table.
        // Never drop the receipt emitted by the current call.
        while (journal.size() > maxJournalEntries && journal.size() > 1) {
            journal.remove(0);
        }
    }

    private static final class MutableAction<T, C> {
        private final ActionId id;
        private final ActionPriority priority;
        private final T payload;
        private final WorldEpoch epoch;
        private final long submittedGameTick;
        private final long deadlineGameTick;
        private ActionState state = ActionState.ACCEPTED;
        private C checkpoint;
        private String message = "accepted";

        private MutableAction(ActionRequest<T> request) {
            id = request.id();
            priority = request.priority();
            payload = request.payload();
            epoch = request.epoch();
            submittedGameTick = request.submittedGameTick();
            deadlineGameTick = request.deadlineGameTick();
        }

        private boolean sameRequest(ActionRequest<T> request) {
            return priority == request.priority() && Objects.equals(payload, request.payload())
                    && epoch.equals(request.epoch()) && submittedGameTick == request.submittedGameTick()
                    && deadlineGameTick == request.deadlineGameTick();
        }

        private ActionSnapshot<T, C> snapshot(long worldTick, long gameTick) {
            return new ActionSnapshot<>(id, priority, payload, epoch, state,
                    worldTick, gameTick, checkpoint, message);
        }
    }
}
