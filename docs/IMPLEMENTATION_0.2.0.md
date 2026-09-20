# HearthCrew 0.2.0 implementation ledger

Status: development build complete; final checks passed within the user-reduced scope. Packaging/install evidence is in the release manifest. Real play acceptance pending.

Authorized: ServerPlayer bodies; native commands/inventory/death/respawn; single input executor; native interaction/menu/crafting/equipment; furnace jobs; safe water travel/boats; 32-block detail and 128-block loaded surface exploration; new-world isolation. Offline and isolated headless GameTest authorized; no client or real-model tests. Preserve personal worlds, credentials, and other mods.

Implementation order: player identity/lifecycle/input -> interaction and capability contracts -> survival/exploration -> regressions/headless integration -> versioned installation and hashes. Latest user steering: expedite delivery; retain compilation and four core native GameTests, do not expand testing. See RELEASE_0.2.0.md for exact verification and remaining limits.

Reference policy: user explicitly permits studying Numen; independently implement against 1.21.1 APIs, never copy implementation/assets. Numen local modified branches are behavior references, not acceptance evidence. Voyager motivates high-level goals + extended execution + real feedback, not arbitrary runtime code execution.
