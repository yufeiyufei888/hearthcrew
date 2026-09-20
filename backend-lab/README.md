# 0.3.0 execution backend laboratory

This opt-in module is **not a gameplay release**. It does not change the installed
0.2.14 mod, production capabilities, protocol or existing companion saves.

The user authorized pinned open-source dependency evaluation, isolated headless
tests, and (only after execution gates pass) real Luna validation limited to 15
minutes for one agent plus 15 minutes for three agents. No Minecraft client,
personal saves or automatic migration may be used.

## Reproduction

1. Run `scripts/prepare-backend-lab.ps1 -Build`. This exports only the pinned Git
   object into an ignored directory. Existing Numen modifications are not used.
2. Run `scripts/test-backend-lab.ps1 -RunId <fresh-id> -Suite <suite>`.
   Suites: `base`, `contracts`, `limits-candidate`, `limits-door`, `limits-output`,
   `lifecycle`, `excavation`, `journal`, `safety`, `preparation`, `preparation-empty`,
   `containers`. The focused `preparation-empty` case remains included in the full
   `preparation` suite. Each run uses a fresh isolated world and
   rejects zero-test success. Java 21 and the existing Gradle runtime are selected
   locally without changing global configuration.
3. Preserve the fresh run directory and exit code. Compare raw inventory and
   world changes, not merely upstream SUCCESS. Tests declaring a reachable path
   must complete it; a failure message is not an alternate pass condition.

## Adapter boundary

The experiment depends on Numen classes rather than copying their implementation.
An independently implemented authority receives managed bodies' ticks through a
pin-specific `CompanionBrain` hook and owns a direct `TaskFactory` task. Numen supplies
the actual ServerPlayer and physics. The old highest-priority-chain approach did
not suppress upstream empty-slot housekeeping; the direct hook does. Managed
bodies reject upstream TaskDispatch/network/replay entry points. Breath and defense
are delegated within the same authority and pause with it. This is experimental:
full survival coverage, crash-safe identity/result persistence, preparation,
world bounds, comprehensive permission enforcement, and all production actions
remain **unverified** until their dedicated gates are implemented and run.

The complete-plan preparation adapter now uses a bounded kernel AND/OR graph,
server recipe snapshots and an explicit source registry. It shares root mutation
budgets and preserves successful step receipts when worn tools need replacement.
Native drops are recovered by evidence identity; entity disappearance is not
credited as acquisition. Search threads share 64 expanded nodes per body/tick,
with a 4096-node search cap; budget exhaustion is not proof of inaccessible terrain.
Public-container transfers use native menus and independently verify both sides.
The furnace fixture uses native cooking ticks and releases the body between feed
and collection. It does not yet prove persistent processing-order recovery.
See `docs/V03_BACKEND_GATE_REPORT.md` for exact passes, retained failures and limits.

No Numen model calls are configured or requested. The gate launch is headless.
The lab hard-fails if its explicit system property is absent. Never put its JAR
in the user's mods folder.

## Licensing and provenance

See `upstream-lock.json` for commit and archive hash. The upstream LGPL core and
MIT public API exception are different scopes. Numen-original art is separately
restricted; this lab does not grant redistribution rights to it. No third-party
art or source is copied into the tracked HearthCrew tree. A production dependency
and its notices/source availability need a separate distribution check before
packaging; a privately compiled laboratory JAR is not an official Numen release.

## Gates

- Native server-player registration and exact inventory save/load.
- Known flat route must finish grounded at the destination.
- Pause freezes task ticks/inputs; resume continues; late cancellation preserves
  terminal results.
- One task must obtain sixteen actual coal; partial upstream success fails.
- A protected ore exercises a denied vanilla break and remains intact.

Additional probes cover reachable high crafting tables, worn tools, candidate
denial, external inventory changes, native item merges, authorized stair excavation,
native bed respawn and world-local request idempotency. Failed runs are preserved;
see `docs/V03_BACKEND_GATE_REPORT.md` for both failures and subsequent fixes.

These are integration probes, not the full approved 0.3.0 acceptance suite.
