# HearthCrew kernel

The kernel is deliberately independent of NeoForge and Minecraft classes. It
owns the single-body action arbitration contract and the vanilla-style food
state used by a custom companion entity.

`ActionArbiter` is a single-thread-safe state machine. Callers submit immutable
`ActionRequest` values, update a checkpoint while an action runs, and commit
observed terminal results. A model timeout is an event only: it never cancels
accepted body work. `WorldEpoch` carries independent world, session, and body
generations so late requests can be rejected after reconnects, dimension/world
replacement, or body replacement.

The public API is intentionally small and contains no Minecraft or Codex types;
the mod and bridge layers adapt their own wire objects to these values.

Journal retention is bounded. In-flight records (accepted, running, suspended)
are never evicted, and terminal action IDs remain as compact in-session
tombstones. When the bounded action table is full, new IDs are rejected until
the session is archived/recreated; this fails closed instead of risking a
duplicate world mutation.
