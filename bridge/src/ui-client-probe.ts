import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdir, readFile, readdir, rename, stat, unlink, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";

type JsonObject = Record<string, any>;
type McpResponse = { id?: unknown; result?: any; error?: { message?: string } };
type ProbeStatus = JsonObject;

const SAFE_RUN_ID = /^[A-Za-z0-9-]{1,48}$/;
const SAFE_WORLD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_RUNTIME_SECONDS = 1_800;
const POLL_MS = 1_000;

function flag(name: string, fallback = ""): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function object(value: unknown, label: string): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as JsonObject;
}

function ownedPath(value: string, root: string, label: string): string {
  const path = resolve(value);
  const child = relative(resolve(root), path).toLowerCase();
  if (isAbsolute(child) || child === ".." || child.startsWith(`..${sep}`) || child.startsWith("../")) {
    throw new Error(`${label} must stay under ${root}`);
  }
  return path;
}

async function exists(path: string): Promise<boolean> {
  try { await stat(path); return true; }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return false; throw error; }
}

async function readJson(path: string): Promise<JsonObject | undefined> {
  try { return object(JSON.parse(await readFile(path, "utf8")), path); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    if (error instanceof SyntaxError) return undefined;
    throw error;
  }
}

async function hasContent(path: string): Promise<boolean> {
  try {
    const details = await stat(path);
    return details.isFile() && details.size > 0;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
}

async function activeControllerLock(path: string): Promise<boolean> {
  let lock: JsonObject;
  try {
    lock = object(JSON.parse(await readFile(path, "utf8")), path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
  if (!Number.isSafeInteger(lock.pid) || lock.pid < 1) throw new Error(`invalid controller lock: ${path}`);
  try {
    process.kill(lock.pid, 0);
    return true;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === "ESRCH") return false;
    // EPERM means the process exists but this probe cannot inspect it. Fail
    // closed rather than risking two controllers using one journal.
    if (code === "EPERM") return true;
    throw error;
  }
}

function nonEmptyWorldId(status: ProbeStatus): string | undefined {
  const value = status.game?.worldId;
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string") throw new Error("game.worldId is not a string");
  return value;
}

function assertExpectedWorld(status: ProbeStatus, expectedWorld: string | undefined, phase: string): void {
  if (!expectedWorld) return;
  const actual = nonEmptyWorldId(status);
  if (actual !== undefined && actual !== expectedWorld) {
    throw new Error(`${phase} observed world ${actual}, expected ${expectedWorld}`);
  }
}

async function atomicWrite(path: string, value: unknown): Promise<void> {
  const temporary = `${path}.tmp-${process.pid}-${Date.now()}`;
  const serialized = JSON.stringify(value, null, 2);
  await writeFile(temporary, serialized, { encoding: "utf8", flag: "wx" });
  try { await rename(temporary, path); }
  catch (error) {
    // Windows can reject replacing an existing destination. Preserve the
    // bounded snapshot contract with a normal overwrite, then remove the
    // temporary file; a genuine write failure still propagates.
    await writeFile(path, serialized, { encoding: "utf8", flag: "w" });
    await unlink(temporary).catch(() => {});
    void error;
  }
}

function identity(status: ProbeStatus): JsonObject | undefined {
  const game = status.game;
  if (!game || typeof game.worldId !== "string" || !Array.isArray(game.companions) || game.companions.length !== 3) return undefined;
  const companions: JsonObject[] = game.companions.map((body: unknown): JsonObject => {
    const value = object(body, "companion");
    return {
      botId: typeof value.botId === "string" ? value.botId : undefined,
      entityId: typeof value.entityId === "string" ? value.entityId : undefined,
      bodyGeneration: Number.isSafeInteger(value.bodyGeneration) ? value.bodyGeneration : undefined,
      name: typeof value.name === "string" ? value.name : undefined,
      dimension: typeof value.dimension === "string" ? value.dimension : undefined,
    };
  });
  if (companions.some(value => !value.botId || !value.entityId || value.bodyGeneration === undefined || !value.name || !value.dimension)) return undefined;
  if (new Set(companions.map(value => value.botId)).size !== 3 || new Set(companions.map(value => value.entityId)).size !== 3) return undefined;
  return { worldId: game.worldId, companions };
}

function mcpClient(child: ChildProcessWithoutNullStreams): {
  call(method: string, params?: unknown, timeoutMs?: number): Promise<any>;
  close(): Promise<boolean>;
  forceClose(): void;
} {
  let nextId = 1;
  const pending = new Map<number, { resolve(value: unknown): void; reject(error: Error): void; timer: NodeJS.Timeout }>();
  const lines = createInterface({ input: child.stdout });
  const fail = (error: Error): void => {
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
    pending.clear();
  };
  lines.on("line", line => {
    try {
      const response = JSON.parse(line) as McpResponse;
      if (!Number.isSafeInteger(response.id)) return;
      const entry = pending.get(response.id as number);
      if (!entry) return;
      pending.delete(response.id as number); clearTimeout(entry.timer);
      if (response.error) entry.reject(new Error(response.error.message ?? "MCP request failed"));
      else entry.resolve(response.result);
    } catch { fail(new Error("controller emitted invalid MCP JSON")); }
  });
  child.on("error", fail);
  child.on("exit", (code, signal) => fail(new Error(`controller exited (${code ?? `signal ${signal}`})`)));
  const call = (method: string, params: unknown = {}, timeoutMs = 20_000): Promise<any> => new Promise((resolveCall, rejectCall) => {
    if (child.exitCode !== null || child.signalCode !== null || child.stdin.destroyed) { rejectCall(new Error("controller is not running")); return; }
    const id = nextId++;
    const timer = setTimeout(() => { pending.delete(id); rejectCall(new Error(`MCP ${method} timed out`)); }, timeoutMs);
    pending.set(id, { resolve: resolveCall, reject: rejectCall, timer });
    try { child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`); }
    catch (error) { clearTimeout(timer); pending.delete(id); rejectCall(error instanceof Error ? error : new Error(String(error))); }
  });
  const close = async (): Promise<boolean> => {
    lines.close();
    if (!child.stdin.destroyed) child.stdin.end();
    if (child.exitCode === null && child.signalCode === null) await new Promise<void>(done => {
      const timer = setTimeout(done, 10_000);
      child.once("exit", () => { clearTimeout(timer); done(); });
    });
    return child.exitCode === 0 && child.signalCode === null;
  };
  const forceClose = (): void => {
    if (child.exitCode !== null || child.signalCode !== null || !child.pid) return;
    if (process.platform === "win32") spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
    else child.kill("SIGTERM");
  };
  return { call, close, forceClose };
}

async function readStop(path: string): Promise<boolean> {
  const value = await readJson(path);
  return value?.stop === true;
}

async function recentEvents(tool: (name: string, args?: unknown, timeoutMs?: number) => Promise<any>): Promise<unknown[]> {
  let after = 0;
  const result: unknown[] = [];
  for (let page = 0; page < 100; page += 1) {
    const response = object(await tool("events", { after, limit: 100 }, 20_000), "events response");
    const rows = Array.isArray(response.events) ? response.events : [];
    result.push(...rows);
    const next = response.nextCursor;
    if (!Number.isSafeInteger(next) || rows.length === 0 || next <= after || rows.length < 100) break;
    after = next;
  }
  return result.slice(-100);
}

async function main(): Promise<void> {
  const projectRoot = resolve(flag("--project-root", process.cwd()));
  const runId = flag("--run-id");
  if (!SAFE_RUN_ID.test(runId)) throw new Error("provide a unique --run-id (1..48 letters, digits or hyphens)");
  const runtimeRoot = resolve(projectRoot, ".runtime");
  const runRoot = ownedPath(flag("--run-root", resolve(runtimeRoot, `ui-client-${runId}`)), runtimeRoot, "run root");
  const resumeStateArgument = flag("--resume-state");
  const expectedWorldArgument = flag("--expected-world");
  if ((resumeStateArgument && !expectedWorldArgument) || (!resumeStateArgument && expectedWorldArgument)) {
    throw new Error("--resume-state and --expected-world must be provided together");
  }
  if (expectedWorldArgument && !SAFE_WORLD_ID.test(expectedWorldArgument)) {
    throw new Error("--expected-world must be a canonical world UUID");
  }
  const expectedWorld = expectedWorldArgument || undefined;
  const resumeState = resumeStateArgument
    ? ownedPath(resumeStateArgument, runtimeRoot, "resume state")
    : undefined;
  const state = resumeState ?? resolve(runRoot, "controller");
  const stopPath = resolve(runRoot, "stop.json");
  const summaryPath = resolve(runRoot, "probe-summary.json");
  const readyPath = resolve(runRoot, "ready.json");
  const livePath = resolve(runRoot, "live-status.json");
  const expectedSave = resolve(projectRoot, "mod", "run", "client", "saves", `P2Team-${runId}`);
  const codexRuntime = resolve(flag("--codex-runtime", resolve(process.env.LOCALAPPDATA ?? projectRoot, "HearthCrew", "codex-runtime")));
  const workspace = resolve(flag("--workspace", resolve(process.env.LOCALAPPDATA ?? projectRoot, "HearthCrew", "workspace")));
  const codexCommand = flag("--codex-command", "codex");
  const timeoutSeconds = Number(flag("--timeout-seconds", String(MAX_RUNTIME_SECONDS)));
  if (!Number.isSafeInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > MAX_RUNTIME_SECONDS) throw new Error("timeout must be 1..1800 seconds");
  if (!resumeState && await exists(expectedSave)) throw new Error(`fixture save already exists; choose a fresh run ID: ${expectedSave}`);
  await mkdir(runRoot, { recursive: true });
  const files = await readdir(runRoot);
  if (files.length !== 0) throw new Error(`run root is not empty; choose a fresh run ID: ${runRoot}`);
  if (resumeState) {
    const pairing = await readJson(resolve(resumeState, "pairing.json"));
    if (!pairing || !Number.isSafeInteger(pairing.port) || pairing.port < 1 || pairing.port > 65_535
      || typeof pairing.token !== "string" || pairing.token.length < 16) throw new Error("resume state is missing a valid pairing.json");
    if (!(await hasContent(resolve(resumeState, "crew-events.jsonl")))) throw new Error("resume state is missing crew-events.jsonl");
    if (await activeControllerLock(resolve(resumeState, "controller.lock"))) {
      throw new Error("resume state has an active controller.lock; choose an inactive runtime");
    }
  } else {
    await mkdir(state, { recursive: true });
  }

  const startedAt = new Date().toISOString();
  const summary: JsonObject = {
    protocol: "hearthcrew.v1", probe: "UI_CLIENT", mode: resumeState ? "RESUME_STATE" : "FRESH_STATE",
    runId, ...(resumeState ? { expectedWorld } : {}), status: "FAIL", startedAt, stopRequested: false,
  };
  let child: ChildProcessWithoutNullStreams | undefined;
  let mcp: ReturnType<typeof mcpClient> | undefined;
  let lastStatus: ProbeStatus | undefined;
  let ready: JsonObject | undefined;
  let closed = false;
  let failure: string | undefined;
  try {
    child = spawn(process.execPath, [resolve(projectRoot, "bridge", "dist", "bridge", "src", "play-runtime.js"), "--state", state, "--runtime", codexRuntime, "--workspace", workspace, "--codex-command", codexCommand], {
      cwd: projectRoot, stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
    });
    child.stderr.on("data", chunk => process.stderr.write(chunk));
    mcp = mcpClient(child);
    await mcp.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "hearthcrew-ui-client-probe", version: "0.1.0-dev" } });
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} })}\n`);
    const listed = object(await mcp.call("tools/list"), "tools/list response");
    const names = Array.isArray(listed.tools) ? listed.tools.map((value: unknown) => object(value, "tool").name) : [];
    if (!["status", "events"].every(name => names.includes(name))) throw new Error("read-only MCP surface is incomplete");
    const tool = async (name: string, args: unknown = {}, timeoutMs = 20_000): Promise<any> => {
      const response = await mcp!.call("tools/call", { name, arguments: args }, timeoutMs);
      if (response?.isError) throw new Error(response.content?.[0]?.text ?? `${name} failed`);
      return response?.structuredContent;
    };
    const deadline = Date.now() + timeoutSeconds * 1_000;
    let statusCount = 0;
    while (Date.now() < deadline) {
      if (await readStop(stopPath)) {
        summary.stopRequested = true;
        lastStatus = object(await tool("status"), "final status");
        assertExpectedWorld(lastStatus, expectedWorld, "final status");
        summary.finalStatus = lastStatus;
        summary.events = await recentEvents(tool);
        break;
      }
      lastStatus = object(await tool("status"), "status");
      statusCount += 1;
      assertExpectedWorld(lastStatus, expectedWorld, statusCount === 1 ? "first status" : "status");
      if (statusCount === 1) {
        summary.firstStatus = {
          worldId: nonEmptyWorldId(lastStatus) ?? null,
          gameStage: typeof lastStatus.game?.stage === "string" ? lastStatus.game.stage : "unknown",
        };
      }
      await atomicWrite(livePath, lastStatus);
      if (!ready) {
        const currentIdentity = identity(lastStatus);
        if (currentIdentity) {
          ready = { runId, startedAt, readyAt: new Date().toISOString(), ...currentIdentity };
          summary.firstIdentity = currentIdentity.companions;
          await atomicWrite(readyPath, ready);
        }
      }
      await new Promise(done => setTimeout(done, POLL_MS));
    }
    if (!summary.stopRequested) throw new Error(`probe timeout after ${timeoutSeconds} seconds`);
    if (!lastStatus || !ready) throw new Error("stop was requested before a three-body world connection was observed");
    summary.status = "CLOSED";
    summary.world = ready.worldId;
    summary.identity = ready.companions;
    if (summary.finalStatus) {
      const finalIdentity = identity(summary.finalStatus);
      if (finalIdentity) {
        summary.finalIdentity = finalIdentity.companions;
        summary.world = finalIdentity.worldId;
      }
    }
  } catch (error) {
    failure = error instanceof Error ? error.message : String(error);
    summary.failure = failure;
    if (ready) { summary.world = ready.worldId; summary.identity = ready.companions; }
    else if (lastStatus) summary.world = lastStatus.game?.worldId;
  } finally {
    try { if (mcp) closed = await mcp.close(); }
    catch (error) { failure ??= error instanceof Error ? error.message : String(error); }
    if (!closed) {
      failure ??= "controller did not exit cleanly";
      mcp?.forceClose();
    }
    summary.controllerClosedGracefully = closed;
    summary.status = summary.status === "CLOSED" && !failure && closed ? "CLOSED" : "FAIL";
    summary.finishedAt = new Date().toISOString();
    if (failure) summary.failure = failure;
    await writeFile(summaryPath, JSON.stringify(summary, null, 2), { encoding: "utf8", flag: "wx" });
  }
  process.stdout.write(JSON.stringify({ status: summary.status, runId, summary: summaryPath, stopRequested: summary.stopRequested }) + "\n");
  if (summary.status !== "CLOSED") process.exitCode = 1;
}

main().catch(error => { process.stderr.write(`UI client probe failed: ${error instanceof Error ? error.message : String(error)}\n`); process.exitCode = 1; });
