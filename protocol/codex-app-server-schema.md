## 0.1.3-dev 增量诊断

五个动态工具名字保持不变。observe/每轮上下文新增 capabilities（self/team/gather），工具拒绝文本为 JSON：accepted=false、code、reason、allowedNext、capabilities。回合输入新增 actionResults，每项携带原始 eventId 与真实终态；action.results_consumed 标记消费，不改变动作成功与否。GATHER 的状态快照与事件可附加 gatherDiagnostics：phase、scanned、scanTotal、candidates、eligibleRoots、rejections、examples、requested、collected。字段缺失仍是未知，不能推断为零。

新增 turn.started/attached/ended/recovery、tool.rejected 诊断，包含角色/会话/回合关联，不记录内部推理。此增量仅通过模拟控制器回归和编译，真实 CLI 回合契约与游戏行为待验证。

# Codex App Server schema pin

## Public chat development extension (2026-09-06)

CLI remains pinned to 0.153.3. The five tool names are unchanged. `share` adds optional `messageId` and `replyTo`, recipients accept observed names/IDs or `team`/`player`, and public text is limited to 200 characters. `propose_work` adds `discuss_work` (discussion only) and `owner_task` (reference an addressed, actual player message). Communication turns cannot submit body actions or mutate team tasks. Internal protocol adds `chat.publish` with world/body/intent validation, and the Mod emits `player.chat` from authenticated server-side chat events. Durable records use `crew.chat`, `chat.read`, `chat.owner_claim`, `chat.owner_result`, and `role.bound`.

These dynamic tool schema changes have only been compiled. The earlier real-contract PASS records below do not validate this extension; no model probe was run in this delivery.

The bridge was inspected against the schema generated locally by Codex CLI `0.153.3`:

```powershell
codex app-server generate-json-schema --experimental --out .artifacts/codex-schema
```

The generated directory is intentionally ignored because it is machine-generated and may contain a large number of files. The reproducibility pin is in `codex-app-server-schema-manifest.json`.

The bridge relies on these current protocol facts:

* `initialize` accepts `clientInfo` and optional `capabilities`; the client sends `initialized` after the response.
* `thread/start` accepts `model`, `baseInstructions`, `developerInstructions`, `cwd`, `ephemeral`, `dynamicTools`, and `serviceTier`; the returned `thread.id`, `model`, `reasoningEffort`, and `serviceTier` are treated as authoritative.
* `turn/start` requires `threadId` and `input`; this bridge passes the profile `model`, `effort`, and requested `serviceTierForTurn`. Completion is observed from `turn/completed`, not inferred from text items.
* `turn/interrupt` requires `threadId` and `turnId`.
* Dynamic game tools are `DynamicToolSpec` function entries and are delivered through server request `item/tool/call` with `threadId`, `turnId`, `callId`, `tool`, and `arguments`.
* The client rejects non-game approval, user-input, and elicitation requests. A caller cannot install an approval callback to authorize host capabilities.
* Optional read-only diagnostics use `account/read`, `model/list`, `mcpServerStatus/list`, and `config/read`; they do not create a thread or start a turn.

`fast` is a requested service tier only. A profile is allowed to display it as effective only after the same App Server advertises `fast` for Luna and a probe `thread/start` echoes either `fast` or the CLI 0.153.3 wire alias `priority`; `null` or another value remains unconfirmed.

## Feature isolation audit

On 2026-09-05, `codex features list` from CLI 0.153.3 reported the currently available external or host capabilities. The bridge starts App Server with `--strict-config`, enables the required `code_mode` and `code_mode_host`, and disables the external features enumerated in `bridge/src/runtime-config.ts`. Before spawn it generates or validates a complete, unique Luna catalog with shell disabled, apply_patch null, search false, Node REPL disabled, and `multi_agent_version: null`. Feature flags alone had left five sub-agent tools visible; the metadata field was necessary to remove them.

The real contract probe now checks the code-mode wrapper's actual output against its matching call ID. It verifies exactly the five game tools, nine absent host functions, three absent host globals, one actual observe callback, and the Luna/high/priority echo. `scripts/test-codex-contract.ps1 -AllowRealModel` is an explicit test entry point; it does not run as part of ordinary unit tests. The maintained runner passed at `.runtime/codex-contract-maintained-20260905-1451/result/report.json`. Its diagnostic `experimentalRawEvents` flag is disabled for normal product sessions. Code mode remains an under-development interface requiring this pinned-version contract test.

The dedicated App Server selects `hearthcrew_http`, a Responses provider pointing to the official ChatGPT Codex backend with OpenAI login required and `supports_websockets=false`. This avoids the measured WebSocket retry delay while keeping default HTTP retries. It does not change desktop/global configuration. Real authenticated HTTPS calls have passed.

The model's `act` schema requires a short `actionId`, `kind`, and any action parameters. The controller supplies the active mission identity from its validated thread/turn/body binding; the model need not transcribe an opaque mission UUID. Internal Mod requests still carry the complete intent/action/session identity and reject stale or conflicting calls.

`act.kind` enumerates the supported body primitives. Its `parameters` schema distinguishes integer block `position`, canonical entity UUID `target`, numeric `count`, and qualified resource ID `resource`. The controller also validates the per-kind parameter combinations before preparing an action identity or consuming the turn's submission allowance. A malformed request can therefore be corrected in the same turn if it has not reached the Mod. Once dispatch may have happened, an uncertain outcome still requires reconciliation rather than an automatic retry. This is structural validation; recipes, inventory, protection, and world postconditions remain authoritative server checks.

The typed-tool contract passed against the actual pinned App Server at `.runtime/codex-contract-typed-tools-20260905-1528/result/report.json` in 11,850 ms, with the same exact five-tool and absent-host-capability assertions. The real Minecraft App Server fault run `fault-appserver-20260905-1536` subsequently used this schema for MINE, CRAFT, and TRANSFER; see `evidence/P1_PROCESS_RECOVERY_2026-09-05.md` for its scope and outstanding audit checks.

The same isolated `config/read` check confirmed `project_doc_max_bytes: 0`, `include_environment_context: false`, `project_root_markers: []`, and `skills: null`. These settings prevent project instruction and environment-context injection in the dedicated runtime; the skill and plugin feature flags are also disabled. The doctor prints these four values so a changed CLI or config cannot silently turn this into a prompt-only claim.

## 0.1.4-dev additive local safety and deferred owner requests

World bodies expose localSafety {active, mode, reason, threatId, threatCount}. SELF_DEFENCE is an internal server-only action; the dynamic tools and action.submit parser reject requesting it. UI displays the body safety status ahead of model planning status. Normal busy-body owner requests are journalled as owner.request.queued; claimed/closed records prevent uncertain replay. Explicit urgent reassignment remains immediate; stop/standby cancels pending requests. Team action settlement requires its matching task ID or submitted action identity, never a local safety terminal alone.
