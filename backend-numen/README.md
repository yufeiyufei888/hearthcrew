# Numen execution adapter (experimental)

This source module holds the actual single-authority runtime used by the isolated backend lab. It is not a released gameplay mod. No GameTest fixture, model credential, private log or upstream artwork is included.

The host must initialize BackendRuntime and configure a world-specific BackendProtection.Policy before taking ownership of a real NumenPlayer. Searches freeze this policy for worker threads; execution rechecks the live server state.

Pinned dependency: Numen 947f0064f3374adc0341e61687215ae32ea9765a. LGPL source/notice distribution and asset replacement remain release gates. The host supplies mod metadata and includes hearthcrew-backend.mixins.json. Neither this experimental jar nor the laboratory jar may be installed into PCL.

Preparation, pickup, native crafting and container adapters are shared with tests. Production body routing, protocol/UI integration, persistent recovery and full autonomy gates remain incomplete; see docs/V03_BACKEND_GATE_REPORT.md.
