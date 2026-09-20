import test from "node:test";
import assert from "node:assert/strict";
import { PassThrough } from "node:stream";
import { EventEmitter } from "node:events";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AppServerClient, AGENT_PROFILES } from "../src/app-server-client.js";
import { PINNED_CODEX_CLI_VERSION, assertGameModelCatalog, DEDICATED_CONFIG, ensureDedicatedCodexConfig, HEARTHCREW_HTTP_PROVIDER_ARGS, ISOLATION_DISABLED_FEATURES, isolatedAppServerArgs, LEGACY_DEDICATED_CONFIG, validateRuntimeConfig } from "../src/runtime-config.js";

async function testRuntime(prefix: string) {
  const root = await mkdtemp(join(tmpdir(), `hearthcrew-${prefix}-`));
  const codexHome = join(root, "codex");
  const modelCatalogDirectory = join(codexHome, "model-catalog");
  const modelCatalogJson = join(modelCatalogDirectory, "game-only.json");
  await mkdir(modelCatalogDirectory, { recursive: true });
  await writeFile(modelCatalogJson, JSON.stringify({ models: [{ slug: "gpt-5.6-luna", display_name: "GPT-5.6-Luna", description: "test", default_reasoning_level: "medium", supported_reasoning_levels: [{ effort: "high", description: "high" }], shell_type: "disabled", visibility: "list", supported_in_api: true, priority: 8, model_messages: { instructions_template: "test" }, apply_patch_tool_type: null, web_search_tool_type: "text_and_image", truncation_policy: { mode: "tokens" }, context_window: 272000, max_context_window: 872000, comp_hash: "test", effective_context_window_percent: 95, input_modalities: ["text"], supports_search_tool: false, use_responses_lite: true, node_repl_disabled: true, tool_mode: "code_mode_only", multi_agent_version: null, base_instructions: "test" }] }), "utf8");
  return { codexHome, workspace: join(root, "workspace"), modelCatalogJson };
}

function fakeChild(completionBeforeResponse = false, lateToolCall = false, earlyToolCall = false, autoComplete = true, completionStatus: "completed" | "failed" = "completed", threadServiceTier: string | null = null, fastCatalog = false) {
  const child = new EventEmitter() as any;
  child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => { child.emit("exit", 0, null); return true; };
  let emitServer: (message: unknown) => void = () => undefined;
  child.stdin.on("data", (chunk: Buffer) => {
    const request = JSON.parse(chunk.toString("utf8"));
    const emit = (message: unknown) => child.stdout.write(`${JSON.stringify(message)}\n`);
    emitServer = emit;
    if (request.method === "initialize") emit({ jsonrpc: "2.0", id: request.id, result: { codexHome: "C:\\isolated", platformFamily: "windows", platformOs: "windows", userAgent: "fake" } });
    if (request.method === "model/list") {
      const result = fastCatalog ? { data: [{ id: "gpt-5.6-luna", additionalSpeedTiers: ["fast"], serviceTiers: [{ id: "priority", name: "Fast" }] }] } : { data: [] };
      emit({ jsonrpc: "2.0", id: request.id, result });
    }
    if (request.method === "thread/start") emit({ jsonrpc: "2.0", id: request.id, result: { thread: { id: "thread-1" }, model: "gpt-5.6-luna", reasoningEffort: "high", serviceTier: threadServiceTier } });
    if (request.method === "turn/start") { const completed = { jsonrpc: "2.0", method: "turn/completed", params: { threadId: "thread-1", turn: { id: "turn-1", status: completionStatus, ...(completionStatus === "failed" ? { error: { message: "controlled failure" } } : {}) } } }; if (completionBeforeResponse) emit(completed); if (earlyToolCall) emit({ jsonrpc: "2.0", id: 77, method: "item/tool/call", params: { threadId: "thread-1", turnId: "turn-1", callId: "early", tool: "act", arguments: { bodyGeneration: 17 } } }); emit({ jsonrpc: "2.0", id: request.id, result: { turn: { id: "turn-1" } } }); if (!completionBeforeResponse && autoComplete) setImmediate(() => emit(completed)); if (lateToolCall) setTimeout(() => emit({ jsonrpc: "2.0", id: 99, method: "item/tool/call", params: { threadId: "thread-1", turnId: "turn-1", callId: "late", tool: "act", arguments: { bodyGeneration: 1 } } }), 20); }
    if (request.method === "turn/interrupt") emit({ jsonrpc: "2.0", id: request.id, result: {} });
  });
  child.emitServer = (message: unknown) => emitServer(message);
  return child;
}

function silentChild() {
  const child = new EventEmitter() as any;
  child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.killed = false;
  child.kill = () => { child.killed = true; child.emit("exit", 1, null); return true; };
  return child;
}

test("App Server client initializes, isolates tools, creates three-profile threads and completes a turn", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("client"), detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(), requestTimeoutMs: 1000 });
  const initialized = await client.start();
  assert.equal((initialized as any).platformOs, "windows");
  const thread = await client.createThread("coordinator");
  assert.equal(thread.model, "gpt-5.6-luna");
  assert.equal(thread.effectiveServiceTier, null);
  const handle = await client.startTurn(thread, "观察附近", thread.profile);
  assert.equal(handle.turnId, "turn-1");
  assert.equal((await handle.completion as any).status, "completed");
  assert.equal(client.diagnostics().gameOnlyTools, "pending");
  assert.equal(client.diagnostics().productionGameplay, "unsupported");
  await client.stop();
});

test("fast service tier stays disabled unless the App Server echoes fast", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("fast-unverified"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(), requestTimeoutMs: 1000 });
  await client.start();
  assert.deepEqual(await client.verifyFastServiceTier(), { verified: false, requested: "fast", reason: "Luna catalog did not advertise a fast service tier" });
  assert.equal((await client.createThread("coordinator")).effectiveServiceTier, null);
  await client.stop();
});

test("fast service tier accepts Codex wire alias priority and preserves the echoed tier", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("fast-priority-alias"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(false, false, false, true, "completed", "priority", true), requestTimeoutMs: 1000 });
  await client.start();
  assert.deepEqual(await client.verifyFastServiceTier(), { verified: true, requested: "fast", echoed: "priority" });
  const thread = await client.createThread("coordinator");
  assert.equal(thread.effectiveServiceTier, "priority");
  await client.stop();
});

test("fast verification uses the same App Server and an ephemeral thread-start probe", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("fast-runtime-verification"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(false, false, false, true, "completed", "priority", true), requestTimeoutMs: 1000 });
  await client.start();
  const verification = await client.verifyFastServiceTier();
  assert.deepEqual(verification, { verified: true, requested: "fast", echoed: "priority" });
  assert.match(client.diagnostics().modelProfiles[0], /:fast$/);
  await client.stop();
});

test("all non-game approval requests are denied without an approval callback", async () => {
  const child = fakeChild();
  const responses: any[] = [];
  child.stdin.on("data", (chunk: Buffer) => { try { const message = JSON.parse(chunk.toString("utf8")); if (message.id === 99) responses.push(message); } catch { /* fake server consumes its own request stream */ } });
  const client = new AppServerClient({ runtime: await testRuntime("approval-deny"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => child, requestTimeoutMs: 1000 });
  await client.start();
  child.emitServer({ jsonrpc: "2.0", id: 99, method: "execCommandApproval", params: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(responses.length, 1);
  assert.equal(responses[0].error.code, -32601);
  assert.match(responses[0].error.message, /denies non-game/);
  await client.stop();
});

test("game-only tool validation rejects arbitrary host tools", () => {
  assert.throws(() => new AppServerClient({ runtime: { codexHome: "C:\\hc", workspace: "C:\\w" }, gameTools: [{ type: "function", name: "exec" as any, description: "shell", inputSchema: {} }], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild() }), /outside/);
});

test("turn start reserves a thread before the RPC response arrives", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("race"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(), requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const first = client.startTurn(thread, "first", thread.profile);
  await assert.rejects(client.startTurn(thread, "second", thread.profile), /active or starting/);
  const handle = await first;
  await handle.completion;
  await client.stop();
});

test("turn start requires the configured profile when given only a thread id", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("profile-required"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(), requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  await assert.rejects(client.startTurn({ threadId: thread.threadId }, "missing profile"), /requires a configured agent profile/);
  await client.stop();
});

test("completion racing the turn/start response does not leave a phantom busy turn", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("early"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(true), requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "early", thread.profile);
  await handle.completion;
  const second = await client.startTurn(thread, "after", thread.profile);
  await second.completion;
  await client.stop();
});

test("failed turn completion rejects instead of looking like a successful turn", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("failed-turn"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(false, false, false, true, "failed"), requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "fail", thread.profile);
  await assert.rejects(handle.completion, /App Server turn failed: controlled failure/);
  await client.stop();
});

test("tool call arriving before turn/start response is buffered until the matching turn is active", async () => {
  let calls = 0;
  const client = new AppServerClient({ runtime: await testRuntime("early-tool"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(false, false, true), dynamicToolHandler: (params) => { calls += 1; assert.equal((params as any).turnId, "turn-1"); return { contentItems: [], success: true }; }, requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "early tool", thread.profile, 17);
  await handle.completion;
  assert.equal(calls, 1);
  await client.stop();
});

test("tool call buffered for a turn that completed before registration is discarded", async () => {
  let calls = 0;
  const client = new AppServerClient({ runtime: await testRuntime("early-tool-complete"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(true, false, true), dynamicToolHandler: () => { calls += 1; return { contentItems: [], success: true }; }, requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "already complete", thread.profile, 17);
  await handle.completion;
  assert.equal(calls, 0);
  await client.stop();
});

test("late dynamic tool calls after turn completion are rejected before the game handler", async () => {
  let calls = 0;
  const client = new AppServerClient({ runtime: await testRuntime("late"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => fakeChild(false, true), dynamicToolHandler: () => { calls += 1; return { contentItems: [], success: true }; }, requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "late", thread.profile);
  await handle.completion;
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(calls, 0);
  await client.stop();
});

test("dynamic requests for tools outside the injected game surface never reach the handler", async () => {
  let calls = 0;
  const child = fakeChild(false, false, false, false);
  const client = new AppServerClient({ runtime: await testRuntime("unauthorized-tool"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => child, dynamicToolHandler: () => { calls += 1; return { contentItems: [], success: true }; }, requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "reject external tool", thread.profile, 17);
  child.emitServer({ jsonrpc: "2.0", id: 97, method: "item/tool/call", params: { threadId: handle.threadId, turnId: handle.turnId, callId: "external", tool: "exec", arguments: {} } });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(calls, 0);
  child.emitServer({ jsonrpc: "2.0", method: "turn/completed", params: { threadId: handle.threadId, turn: { id: handle.turnId, status: "completed" } } });
  await handle.completion;
  await client.stop();
});

test("interrupt marks the active model turn stale before late tool calls can reach the handler", async () => {
  let calls = 0;
  const child = fakeChild(false, false, false, false);
  const client = new AppServerClient({ runtime: await testRuntime("interrupt"), profiles: [AGENT_PROFILES[0]], detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => child, dynamicToolHandler: () => { calls += 1; return { contentItems: [], success: true }; }, requestTimeoutMs: 1000 });
  await client.start();
  const thread = await client.createThread("coordinator");
  const handle = await client.startTurn(thread, "interrupt me", thread.profile, 17);
  await handle.interrupt();
  child.emitServer({ jsonrpc: "2.0", id: 98, method: "item/tool/call", params: { threadId: "thread-1", turnId: "turn-1", callId: "after-interrupt", tool: "act", arguments: { bodyGeneration: 17 } } });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(calls, 0);
  child.emitServer({ jsonrpc: "2.0", method: "turn/completed", params: { threadId: "thread-1", turn: { id: "turn-1", status: "interrupted" } } });
  await handle.completion;
  await client.stop();
});

test("failed startup kills the App Server child process", async () => {
  const child = silentChild();
  const client = new AppServerClient({ runtime: await testRuntime("startup-failure"), detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => child, requestTimeoutMs: 20 });
  await assert.rejects(client.start(), /timed out|exited|stopped/);
  assert.equal(child.killed, true);
});

test("startup rejects an unverified Codex CLI version before opening the App Server", async () => {
  const client = new AppServerClient({ runtime: await testRuntime("version"), expectedCliVersion: "0.0.0", detectVersion: () => PINNED_CODEX_CLI_VERSION, spawnProcess: () => { throw new Error("spawn must not run for an unsupported CLI"); } });
  await assert.rejects(client.start(), /unsupported Codex CLI version/);
});

test("dedicated config rejects comment or setting tampering and global homes", async () => {
  const root = await mkdtemp(join(tmpdir(), "hearthcrew-config-"));
  const config = { codexHome: join(root, "codex"), workspace: root };
  const path = await ensureDedicatedCodexConfig(config);
  await writeFile(path, `${await readFile(path, "utf8")}# tampered\n`, "utf8");
  await assert.rejects(ensureDedicatedCodexConfig(config), /exactly match/);
  const defaultHome = join((await import("node:os")).homedir(), ".codex");
  assert.throws(() => validateRuntimeConfig({ codexHome: defaultHome, workspace: root }), /global CODEX_HOME/);
  const previousCodexHome = process.env.CODEX_HOME;
  const configuredGlobal = join(root, "configured-global");
  process.env.CODEX_HOME = configuredGlobal;
  try { assert.throws(() => validateRuntimeConfig({ codexHome: configuredGlobal, workspace: root }), /global CODEX_HOME/); }
  finally { if (previousCodexHome === undefined) delete process.env.CODEX_HOME; else process.env.CODEX_HOME = previousCodexHome; }
  const args = isolatedAppServerArgs(config);
  assert.deepEqual(args.slice(0, 3), ["app-server", "--stdio", "--strict-config"]);
  const providerStart = 3 + 2 * ISOLATION_DISABLED_FEATURES.length;
  assert.deepEqual(args.slice(providerStart, providerStart + HEARTHCREW_HTTP_PROVIDER_ARGS.length), HEARTHCREW_HTTP_PROVIDER_ARGS);
  assert.equal(args.includes('model_provider="hearthcrew_http"'), true);
  assert.equal(args.includes('model_providers.hearthcrew_http={name="OpenAI",base_url="https://chatgpt.com/backend-api/codex",wire_api="responses",requires_openai_auth=true,supports_websockets=false}'), true);
  assert.equal(args.includes("view_image"), true);
  assert.equal(args.includes("workspace_dependencies"), true);
  assert.equal(args.includes("code_mode"), false);
  assert.equal(args.includes("code_mode_host"), false);
  assert.match(DEDICATED_CONFIG, /code_mode = true/);
  assert.match(DEDICATED_CONFIG, /code_mode_host = true/);
  assert.match(DEDICATED_CONFIG, /web_search = "disabled"/);
  const catalogArgs = isolatedAppServerArgs({ ...config, modelCatalogJson: join(root, "game-only.json") });
  assert.equal(catalogArgs.includes("model_catalog_json=\"" + join(root, "game-only.json").replaceAll("\\", "/") + "\""), true);
  assert.throws(() => validateRuntimeConfig({ ...config, modelCatalogJson: "relative.json" }), /modelCatalogJson must be an absolute path/);
  assert.throws(() => isolatedAppServerArgs({ ...config, appServerArgs: ["--enable", "shell_tool"] }), /override HearthCrew isolation/);
  for (const override of ["--enable=shell_tool", "-cshell_tool=true", "--config=/tmp/untrusted.toml", "--profile=default", "--code-mode-host=127.0.0.1:9999", "--listen=127.0.0.1:9999", "--ws-token-sha256=deadbeef", "--ws-issuer=external", "--ws-audience=external", "--ws-max-clock-skew-seconds=60"]) {
    assert.throws(() => isolatedAppServerArgs({ ...config, appServerArgs: [override] }), /override HearthCrew isolation/, override);
  }
});

test("dedicated config migrates only the known previous profile", async () => {
  const root = await mkdtemp(join(tmpdir(), "hearthcrew-config-migrate-"));
  const config = { codexHome: join(root, "codex"), workspace: join(root, "workspace") };
  const path = join(config.codexHome, "config.toml");
  await mkdir(config.codexHome, { recursive: true });
  await writeFile(path, LEGACY_DEDICATED_CONFIG, "utf8");
  assert.equal(await ensureDedicatedCodexConfig(config), path);
  assert.equal(await readFile(path, "utf8"), DEDICATED_CONFIG);
});

test("dedicated workspace rejects inherited project or AGENTS markers", async () => {
  const parent = await mkdtemp(join(tmpdir(), "hearthcrew-workspace-parent-"));
  await writeFile(join(parent, "AGENTS.md"), "external instructions", "utf8");
  await assert.rejects(
    ensureDedicatedCodexConfig({ codexHome: join(parent, "codex"), workspace: join(parent, "workspace") }),
    /inherit project or AGENTS markers/,
  );
});

test("game-only model catalog validation fails closed on unsafe metadata", async () => {
  const root = await mkdtemp(join(tmpdir(), "hearthcrew-catalog-"));
  const path = join(root, "catalog.json");
  const safe = { models: [{ slug: "gpt-5.6-luna", display_name: "GPT-5.6-Luna", description: "test", default_reasoning_level: "medium", supported_reasoning_levels: [{ effort: "high", description: "high" }], shell_type: "disabled", visibility: "list", supported_in_api: true, priority: 8, model_messages: { instructions_template: "test" }, apply_patch_tool_type: null, web_search_tool_type: "text_and_image", truncation_policy: { mode: "tokens" }, context_window: 272000, max_context_window: 872000, comp_hash: "test", effective_context_window_percent: 95, input_modalities: ["text"], supports_search_tool: false, use_responses_lite: true, node_repl_disabled: true, tool_mode: "code_mode_only", multi_agent_version: null, base_instructions: "test" }] };
  await writeFile(path, JSON.stringify(safe), "utf8");
  assert.equal((await assertGameModelCatalog(path)).length, 64);
  await assert.rejects(assertGameModelCatalog(path, join(root, "dedicated")), /under the dedicated codexHome\/model-catalog/);
  await mkdir(join(root, "dedicated", "model-catalog"), { recursive: true });
  const copiedPath = join(root, "dedicated", "model-catalog", "catalog.json");
  await writeFile(copiedPath, JSON.stringify(safe), "utf8");
  await assert.doesNotReject(assertGameModelCatalog(copiedPath, join(root, "dedicated")));
  await writeFile(copiedPath, JSON.stringify({ models: [{ ...safe.models[0], multi_agent_version: "v1" }] }), "utf8");
  await assert.rejects(assertGameModelCatalog(copiedPath, join(root, "dedicated")), /must already disable multi-agent/);
  await writeFile(copiedPath, JSON.stringify({ models: [{ ...safe.models[0], shell_type: "unified_exec" }] }), "utf8");
  await assert.rejects(assertGameModelCatalog(copiedPath, join(root, "dedicated")), /must already disable shell_type/);
});
