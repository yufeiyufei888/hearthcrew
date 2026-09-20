# Kernel API contract for mod and bridge adapters

The Minecraft server thread owns world mutation. The adapter translates a
bridge command into an immutable request and submits it to one
`ActionArbiter<Payload, Checkpoint>` per companion.

```java
var request = ActionRequest.withoutDeadline(
    ActionId.of(command.id()), command.priority(), command.payload(),
    new WorldEpoch(worldGen, sessionGen, bodyGen), arbiter.gameTick());
ActionReceipt<Payload> receipt = arbiter.submit(request);
arbiter.start(request.id());
```

The body executor calls `checkpoint(id, checkpoint)` after each meaningful
observation. A higher-priority request automatically suspends the current
lease; the executor can then resume it only after checking the checkpoint:

```java
arbiter.resume(id, checkpoint -> checkpoint.stillMatches(world));
```

Use `finish(id, COMPLETED|PARTIAL|FAILED, message)` only after observing the
world or inventory postcondition. `cancel(id, reason)` is terminal. A model
timeout is recorded with `modelTimeout(id, detail)` and leaves accepted or
running body work untouched. Call `advanceTick(worldTick, paused)` once per
server tick; the game clock and deadlines stop during pause. On world/session/
body replacement call `advanceEpoch(new WorldEpoch(...), reason)`, which stales
all old work and rejects late submissions.

`activeSnapshot()`/`snapshot(id)` expose the single execution lease, priority,
payload, state, and checkpoint. `journal()` is a bounded receipt stream; active
records are never evicted and terminal IDs remain as compact tombstones. Once
the bounded action table is full, new IDs receive `REJECTED_CAPACITY` until
this session is archived or a new arbiter is created.

`FoodState` is independent of Minecraft types. Store its value in the custom
mob and call `consumeExhaustion`, `eat`, and `tick` from the entity tick on the
server thread. `FoodTickResult` reports hunger/saturation changes, fractional
health regeneration, and starvation damage without applying health mutations
itself.

## TeamLedger P2 contract

`TeamLedger` is the server-owned shared lease table. A task uses a stable
`TeamTaskId`, semantic fingerprint, `ActionPriority`, world/dimension, a
`WorldEpoch` (only world and session generations gate team ownership), and a
map of positive `ResourceKey` quantities. `ResourceKey` includes world and
dimension and can address a block, an entire container, one container slot, an
entity, one entity slot, or a body/team item quantity pool. Whole-container
keys conflict with every slot in that container; distinct slots do not.

```java
ledger.submit(task, tick);
ledger.claim(task.id(), body, bodyGeneration, tick, observedAvailable);
ledger.start(task.id(), tick);
ledger.checkpoint(task.id(), checkpoint);
ledger.prepareSettlement(request, tick);
ledger.settle(observedBeforeAfter);
```

Claims reserve all resources atomically. Item quantity claims require an
observed available count and subtract quantities held by other active or
reconcile-required tasks. Repeated IDs with the same fingerprint are
idempotent; a changed fingerprint is rejected. A body generation is attached
to the lease, so a respawn cannot silently resume it. `rebind` requires a
fresh checkpoint for the new body generation. Personal work cannot occupy a
body or resource already held by mission/owner work; explicit `retask` ends the
old lease first.

`advanceTick(tick, true)` freezes lease clocks. Started work that expires,
crosses a world/session epoch, dies, or is restored from a snapshot becomes
`RECONCILE_REQUIRED` and keeps its resource reservation. Unknown work is never
replayed. `TeamSettlementRequest` carries a settlement ID, semantic
fingerprint, and expected quantity delta; `TeamSettlementObservation` must
match actual before/after keys. Exact observations complete and release the
reservation; bounded partial observations become `PARTIAL` and keep it;
contradictory observations require reconciliation. Task and settlement
tombstones remain in the bounded ledger; capacity fails closed.
