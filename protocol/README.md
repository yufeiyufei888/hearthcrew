# HearthCrew wire protocol v1

This directory contains the small, implementation independent contract shared by the NeoForge mod and the local bridge. It is an original protocol and does not reuse the previous companion project wire format.

## Transport

The controller listens on IPv4 `127.0.0.1` and the Mod connects to that exact address. Each message is strict UTF-8 JSON preceded by a four byte unsigned big-endian payload length. A payload of zero or greater than 1 MiB is invalid. A receiver may receive a frame in any number of chunks and may receive multiple frames in one chunk.

Only the first message is a `handshake`. It carries the shared local token, the world identity, and a monotonically increasing `sessionEpoch`. The token is never written to logs. Every later message must carry the exact negotiated `worldId` and `sessionEpoch`; a stale epoch is rejected.

## Envelope kinds

* `handshake` / `handshake_ack`: connection authentication and capability negotiation.
* `request` / `response`: bounded request/response calls. During one loaded-world server lifetime the Mod retains bounded mutation receipts across socket reconnects. Reuse of a retained identity with a different semantic payload is rejected. On capacity exhaustion, new mutations fail closed rather than evicting identities. Read-only requests return fresh observations and do not consume that ledger; recovery reads remain available.
* `event` / `ack`: asynchronous event delivery. Events are acknowledged by `eventId`; an ACK confirms receipt only and is not a world-state completion proof.

World mutation requests must additionally carry immutable `intentId`, `actionId`, and, when applicable, `bodyGeneration` fields on the request envelope. The mod is the only world writer; the bridge can only submit intent-bearing requests.

The v1 operation names are `status`, `observe`, `action.submit`, `action.cancel`, `control`, and `reconcile`.

| Operation | Current body contract |
| --- | --- |
| `status`, `reconcile` | `{}` returns actual world identity, live companions, body generations, inventory, active actions and action receipts. Optional selection hints are not filters. The caller compares the returned world and body identities. |
| `observe` | Optional `botId`, `bodyGeneration` and `radius` (1–32, default 32). With a bot, adds a bounded scan of loaded nearby blocks and entities. |
| `action.submit` | Logical companion UUID `botId`, case-insensitive `kind`, and kind-specific arguments below. Envelope requires `intentId`, `actionId`, and current `bodyGeneration`. Remote actions use MISSION priority; clients cannot assign arbitrary priority. |
| `action.cancel` | `botId`, `intentId`, `actionId`; generation in body or envelope, matching if both provided. Returns the actual cancellation receipt. |
| `control` | `botId`, `operation`: `pause`, `resume`, `stop`, or `retask`; generation in body or envelope. `retask` atomically cancels active and suspended work and clears stop for a new owner command. |

Action arguments: MOVE/MINE/PLACE/SLEEP/PORTAL require block `position`; FOLLOW/GUARD/ATTACK/PICKUP/TRANSFER require actual observed entity UUID `target`. `botId` is a stable logical companion identity and is not an entity UUID. CRAFT takes registered recipe `resource`, repetition `count` (1–64), and optional nearby crafting-table `position`. TRANSFER takes item `resource` and positive item `count`. SELECT uses `count` as inventory slot 0–35. EAT uses the selected held food; PLACE uses the selected held block. These are P1 primitives, not a complete survival skill library.

Action receipts have uppercase `state` values: ACCEPTED, RUNNING, SUSPENDED, COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, STALE, or RECONCILE_REQUIRED. The `decision` additionally records acceptance/rejection semantics. The transport `ok` field means the endpoint handled a request; it does not mean the action succeeded.

## Disconnect and reconciliation

Disconnect rejects new work and marks in-flight requests as `uncertain`. The bridge never retries a world-writing request automatically. After reconnect, the caller submits `reconcile` with the new epoch and compares actual world, body generation, inventory and action receipts before deciding whether an action completed, failed, or remains unknown. There is no invented inventory version counter.

Across entity save/load, known action identities become RECONCILE_REQUIRED tombstones and cannot execute again. Entity inventory, other chunks and action receipts are not an atomic storage transaction. Therefore persisted completion is not blindly treated as proof that another entity received an item. A corrupt saved ledger stops that body and preserves the raw diagnostic data. The controller durably writes prepared action identities before dispatch and events before acknowledging them.

The protocol is deliberately usable without a model. An App Server turn may time out while an already accepted body action continues; those states are reported independently.

## Contract examples

```json
{"kind":"handshake","protocol":"hearthcrew.v1","requestId":"h-1","worldId":"world-a","sessionEpoch":7,"token":"<redacted>","client":"mod","capabilities":{"events":true,"acknowledgements":true,"reconciliation":true,"maxFrameBytes":1048576}}
```

```json
{"kind":"request","protocol":"hearthcrew.v1","requestId":"r-42","worldId":"world-a","sessionEpoch":7,"op":"action.submit","intentId":"i-1","actionId":"a-1","bodyGeneration":3,"body":{"botId":"47022ea9-3fdc-43e7-b890-f8be3693e253","kind":"MOVE","position":{"x":1,"y":64,"z":2}}}
```

```json
{"kind":"request","protocol":"hearthcrew.v1","requestId":"r-43","worldId":"world-a","sessionEpoch":7,"op":"control","bodyGeneration":3,"body":{"botId":"47022ea9-3fdc-43e7-b890-f8be3693e253","operation":"pause"}}
```

Each `action.progress` or `action.terminal` event body is the Mod's `ActionReceipt` (including `id.value`, `state`, `sequence`, payload, epoch and tick counters) plus logical `botId`. Event IDs include server lifetime, body identity/generation and receipt sequence. ACKs confirm durable receipt only. Read `status` for current position and inventory postconditions.

`body.available` announces a server-observed incarnation using `botId`, `entityId`, `bodyGeneration`, `name`, and `dimension`. It shares the bounded retained event window and acknowledgement protocol. Reconnecting the same body does not create a new incarnation event. A recipient first records the event durably, then reconciles and compares its world, logical identity, physical identity, generation, and dimension against current server state. It does not infer inventory or complete an action from this notification. Controller state `awaiting_body` can resume after this check; explicit pause or stop remains in effect. A failed reconciliation is not acknowledged as handled, and replay must not duplicate the durable event row.

## 0.1.5-dev compatibility additions

- Role SELECT requires `parameters.slot` (0–35); legacy `count` is accepted only when explicit and consistent. The bridge normalizes to wire `count`. Replies report actual selectedSlot, heldItem, heldCount and swaps from inventory.
- PLACE accepts optional item `resource`; absence uses the current held block. Completion requires the requested block and one item consumed. Ambiguous effects require reconciliation. BUILD cannot replace water.
- `observe` separates resources from `terrain`: inWater, eyesUnderWater, air, standable and breathable candidates, 32-block loaded-area bounds and sample spacing. Candidate reachability is unverified.
- Body `travel` reports phase, destination, start/actual position, start/current air and replan flag. BREATHE is server-only and cannot be requested by a model.
- Ledger version 2 saves full request identities and outcomes. Restored terminal events carry `historical:true`, remain terminal and never trigger execution or refresh chat budget.
- Internal authenticated `action.reconcile_history` takes botId, actionId, an authoritative historical receipt and current envelope bodyGeneration. The bridge requires the same world/bot and identical receipt id/payload/epoch/priority, refusing conflicting terminal states. Mod verifies these fields against the stored request. It changes receipt metadata only. No role/MCP tool exposes it.
- Chat budgets reset once per novel player message or actual completed/partial work result; SELECT/WAIT/local reflexes, failures, historical receipts and repeated wake notifications do not reset them. Communication sees pending results but only work turns acknowledge them.
- New journal rows include UTC timestamp and 0.1.5-dev version. Historical model-triggered standby without sufficient evidence about subsequent player controls stays pending confirmation.


## 0.1.6-dev additions

- `observe.targets`: up to32 integer block positions, each checked within32 blocks in loaded terrain. `targetInspections` separates exact block, lineOfSight, stanceCandidates, bounded path result, protection, support and tool facts. No candidate is a reachability guarantee. Snapshot adds tools/remainingDurability, mainHand and air.
- `EXCAVATE` is self-only (not team-delegated): position required, count defaults32 and must be1..64. Safe passage only; MINE harvest is a separate action. Existing origin bounds, protection and server arbitration apply.
- `propose_work(operation=stage)`: stageId, summary, scope, completion and state. Completion is inventory(resource,count), position(position,dimension), or blocks(steps up to256). BUILD stays limited to16 per action; target verification queries stay limited to32 each. Stages cannot promote failed/partial/unknown receipts into success.
- Optional role `act.actionId` is generated from actual tool-call identity when absent; action.prepared is durable before submission. The wire still always carries actionId, intentId and generations.
- `share.purpose`: coordination, stage_result, help, discovery, danger, answer, progress. progress is private diagnostic data, not mailbox broadcast. stage_result requires stageId, deduplicates by verified stage state. Final model text is not automatically public.
- Internal `recovery.standby` is not exposed by role tools/MCP. Configured by explicit local user repair requests, checked against expectedWorld, botId, expectedName, controlRevision and current bodyGeneration. Saved world operation marker prevents repeated application; a fresh status read prevents an old repair receipt overriding newer controls.
- `diagnostic.snapshot` is an acknowledged passive game event every100 game ticks. It never wakes a model. Controller live-diagnostics.json is refreshed every5 seconds; absence of clock evidence is unknown, not pause.
