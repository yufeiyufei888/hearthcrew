import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";

type JsonObject = Record<string, unknown>;
type Position = { readonly x: number; readonly y: number; readonly z: number };
type Body = {
  readonly botId?: string;
  readonly entityId?: string;
  readonly bodyGeneration?: number;
  readonly name?: string;
  readonly inventory?: readonly { readonly item?: string; readonly count?: number }[];
  readonly actionJournal?: readonly JsonObject[];
};
type WorldView = { readonly worldId?: string; readonly gameTick?: number; readonly companions?: readonly Body[] };
type DesktopStatus = { readonly game?: WorldView; readonly controller?: unknown; readonly appServer?: unknown; readonly roles?: readonly JsonObject[]; readonly restoration?: unknown };
type FixtureMetadata = {
  readonly evidence?: string;
  readonly saveName?: string;
  readonly runId?: string;
  readonly worldId?: string;
  readonly oakPosition?: Position;
  readonly worker?: { readonly logicalUuid?: string; readonly entityUuid?: string; readonly name?: string; readonly initialInventoryEmpty?: boolean };
  readonly recipient?: { readonly logicalUuid?: string; readonly entityUuid?: string; readonly name?: string; readonly initialInventoryEmpty?: boolean };
  readonly initialInventoryEmpty?: boolean;
};
type FixtureReport = { readonly evidence?: string; readonly status?: string; readonly runId?: string; readonly saveName?: string; readonly worldId?: string; readonly oakBlockAir?: boolean; readonly recipientOakPlanks?: number; readonly workerWoodOrPlanks?: number; readonly mineCompleted?: boolean; readonly craftCompleted?: boolean; readonly transferCompleted?: boolean; readonly failure?: string };

const SAFE_RUN_ID = /^[A-Za-z0-9-]{1,48}$/;
const SAVE_PREFIX = "P1Model-";
const OAK_PLANKS = "minecraft:oak_planks";
const REQUIRED_DYNAMIC_TOOLS = ["observe", "propose_work", "act", "share", "remember"] as const;

function flag(name: string, fallback: string): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function integerFlag(name: string, fallback: number): number {
  const parsed = Number(flag(name, String(fallback)));
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

function ownedPath(value: string, root: string, label: string): string {
  const path = resolve(value);
  const relativePath = relative(resolve(root), path).toLowerCase();
  if (isAbsolute(relativePath) || relativePath === ".." || relativePath.startsWith(`..${sep}`) || relativePath.startsWith("../")) throw new Error(`${label} must stay under ${root}`);
  return path;
}

function object(value: unknown, label: string): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as JsonObject;
}

function string(value: unknown, label: string): string {
  if (typeof value !== "string" || !value) throw new Error(`${label} must be a non-empty string`);
  return value;
}

function position(value: unknown, label: string): Position {
  const candidate = object(value, label);
  for (const axis of ["x", "y", "z"] as const) if (typeof candidate[axis] !== "number" || !Number.isFinite(candidate[axis])) throw new Error(`${label}.${axis} is invalid`);
  return { x: candidate.x as number, y: candidate.y as number, z: candidate.z as number };
}

function samePosition(left: unknown, right: Position): boolean {
  try {
    const candidate = position(left, "receipt.position");
    return candidate.x === right.x && candidate.y === right.y && candidate.z === right.z;
  } catch { return false; }
}

function receiptPayload(receipt: JsonObject): JsonObject | undefined {
  try { return receipt.payload === undefined ? undefined : object(receipt.payload, "receipt.payload"); } catch { return undefined; }
}

function completedReceipt(body: Body, kind: string, matches: (payload: JsonObject) => boolean): JsonObject | undefined {
  return (body.actionJournal ?? []).find(receipt => receipt.state === "COMPLETED" && receiptPayload(receipt)?.kind === kind && matches(receiptPayload(receipt)!));
}

function resourceId(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const id = value as JsonObject;
    if (typeof id.namespace === "string" && typeof id.path === "string") return `${id.namespace}:${id.path}`;
  }
  return undefined;
}

function itemCount(body: Body | undefined, item: string): number {
  return (body?.inventory ?? []).filter(stack => stack.item === item).reduce((total, stack) => total + (typeof stack.count === "number" ? stack.count : 0), 0);
}

function fixtureBody(view: WorldView | undefined, name: string): Body | undefined {
  const matches = (view?.companions ?? []).filter(body => body.name === name);
  return matches.length === 1 ? matches[0] : undefined;
}

function diagnosticTools(status: DesktopStatus): string[] {
  const appServer = status.appServer;
  if (!appServer || typeof appServer !== "object") throw new Error(`App Server diagnostic unavailable: ${String(appServer)}`);
  const tools = (appServer as { toolNames?: unknown }).toolNames;
  if (!Array.isArray(tools) || tools.some(tool => typeof tool !== "string")) throw new Error("dynamic game tool diagnostic is missing toolNames");
  const missing = REQUIRED_DYNAMIC_TOOLS.filter(tool => !tools.includes(tool));
  if (missing.length) throw new Error(`configured dynamic game tool surface missing: ${missing.join(",")}`);
  return tools as string[];
}

async function readJson<T>(path: string): Promise<T | undefined> {
  try { return JSON.parse(await readFile(path, "utf8")) as T; }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    if (error instanceof SyntaxError) return undefined;
    throw error;
  }
}

async function waitFor<T>(read: () => Promise<T>, ready: (value: T) => boolean, timeoutMs: number, label: string): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | undefined;
  while (Date.now() < deadline) {
    last = await read();
    if (ready(last)) return last;
    await new Promise(resolveDelay => setTimeout(resolveDelay, 1000));
  }
  throw new Error(`${label} timed out${last === undefined ? "" : `; last=${JSON.stringify(last).slice(0, 1200)}`}`);
}

function mcpClient(child: ChildProcessWithoutNullStreams): { call: (method: string, params?: unknown, timeoutMs?: number) => Promise<any>; close: () => Promise<boolean>; forceClose: () => void } {
  let nextId = 1;
  let output = "";
  const pending = new Map<number, { resolve: (value: unknown) => void; reject: (reason: unknown) => void; timer: NodeJS.Timeout }>();
  const lines = createInterface({ input: child.stdout });
  lines.on("line", line => {
    output = (output + line + "\n").slice(-1024 * 1024);
    try {
      const response = JSON.parse(line) as { id?: unknown; result?: unknown; error?: { message?: string } };
      if (typeof response.id !== "number") return;
      const entry = pending.get(response.id);
      if (!entry) return;
      pending.delete(response.id); clearTimeout(entry.timer);
      if (response.error) entry.reject(new Error(response.error.message ?? "MCP request failed")); else entry.resolve(response.result);
    } catch { /* Controller diagnostics are on stderr; malformed stdout is reported by the pending timeout. */ }
  });
  child.on("exit", (code, signal) => {
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error(`controller exited (${code ?? `signal ${signal}`})`)); }
    pending.clear();
  });
  const call = (method: string, params: unknown = {}, timeoutMs = 30_000): Promise<any> => new Promise((resolveCall, rejectCall) => {
    const id = nextId++;
    const timer = setTimeout(() => { pending.delete(id); rejectCall(new Error(`MCP ${method} timed out`)); }, timeoutMs);
    pending.set(id, { resolve: resolveCall, reject: rejectCall, timer });
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
  });
  const close = async (): Promise<boolean> => {
    lines.close();
    if (!child.stdin.destroyed) child.stdin.end();
    await new Promise<void>(resolveClose => {
      if (child.exitCode !== null || child.signalCode !== null) { resolveClose(); return; }
      const timer = setTimeout(resolveClose, 10_000);
      child.once("exit", () => { clearTimeout(timer); resolveClose(); });
    });
    return child.exitCode === 0 && child.signalCode === null;
  };
  const forceClose = (): void => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    if (process.platform === "win32") spawn("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    else child.kill("SIGTERM");
  };
  return { call, close, forceClose };
}

async function main(): Promise<void> {
  const projectRoot = resolve(flag("--project-root", process.cwd()));
  const runId = flag("--run-id", "");
  if (!SAFE_RUN_ID.test(runId)) throw new Error("provide a unique --run-id (1..48 letters, digits or hyphens)");
  const runtimeRoot = resolve(projectRoot, ".runtime");
  const runRoot = ownedPath(flag("--run-root", flag("--state", resolve(runtimeRoot, `model-client-${runId}`))), runtimeRoot, "run root");
  const controllerState = ownedPath(flag("--controller-state", resolve(runRoot, "controller")), runRoot, "controller state");
  const reportPath = ownedPath(flag("--report", resolve(runRoot, "report.json")), runRoot, "report");
  const fixtureSave = ownedPath(flag("--fixture-save", resolve(projectRoot, "mod", "run", "client", "saves", `${SAVE_PREFIX}${runId}`)), resolve(projectRoot, "mod", "run", "client", "saves"), "fixture save");
  const expectedFixtureSave = resolve(projectRoot, "mod", "run", "client", "saves", `${SAVE_PREFIX}${runId}`);
  if (fixtureSave.toLowerCase() !== expectedFixtureSave.toLowerCase()) throw new Error(`fixture save must be exactly ${expectedFixtureSave}`);
  const fixturePath = resolve(fixtureSave, `hearthcrew-p1-model-fixture-${runId}.json`);
  const fixtureReportPath = resolve(fixtureSave, `hearthcrew-p1-model-report-${runId}.json`);
  const codexRuntime = resolve(flag("--codex-runtime", resolve(process.env.LOCALAPPDATA ?? resolve(process.env.HOME ?? projectRoot, "AppData", "Local"), "HearthCrew", "codex-runtime")));
  const workspace = resolve(flag("--workspace", resolve(process.env.LOCALAPPDATA ?? resolve(process.env.HOME ?? projectRoot, "AppData", "Local"), "HearthCrew", "workspace")));
  const codexCommand = flag("--codex-command", "codex");
  const modelCatalog = flag("--model-catalog", "");
  const playRuntime = resolve(flag("--play-runtime", resolve(projectRoot, "bridge", "dist", "bridge", "src", "play-runtime.js")));
  const timeoutMs = integerFlag("--timeout-seconds", 600) * 1000;
  try { await stat(fixtureSave); throw new Error(`fixture save already exists; choose a new unique run ID: ${fixtureSave}`); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
  await mkdir(runRoot, { recursive: true });
  await mkdir(controllerState, { recursive: true });
  if ((await readdir(controllerState)).length !== 0) throw new Error(`controller state is not empty; choose a new unique run ID: ${controllerState}`);
  const startedAt = new Date().toISOString();
  const report: JsonObject = { protocol: "hearthcrew.v1", test: "REAL_CODEX_MODEL_CLIENT", evidence: "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE", status: "FAIL", runId, startedAt, reportPath };
  let controller: ChildProcessWithoutNullStreams | undefined;
  let mcp: ReturnType<typeof mcpClient> | undefined;
  let lastStatus: DesktopStatus | undefined;
  let desktopTool: ((name: string, argumentsValue?: JsonObject, requestTimeoutMs?: number) => Promise<any>) | undefined;
  let controllerClosedGracefully = false;
  try {
    controller = spawn(process.execPath, [playRuntime, "--state", controllerState, "--runtime", codexRuntime, "--workspace", workspace, "--codex-command", codexCommand, "--trace-model", ...(modelCatalog ? ["--model-catalog", resolve(modelCatalog)] : [])], { cwd: projectRoot, stdio: ["pipe", "pipe", "pipe"] });
    let stderr = "";
    controller.stderr.on("data", chunk => { stderr = (stderr + chunk.toString("utf8")).slice(-1024 * 1024); process.stderr.write(chunk); });
    mcp = mcpClient(controller);
    const tool = async (name: string, argumentsValue: JsonObject = {}, requestTimeoutMs = 30_000): Promise<any> => {
      const result = await mcp!.call("tools/call", { name, arguments: argumentsValue }, requestTimeoutMs);
      if (result?.isError) throw new Error(result.content?.[0]?.text ?? `${name} failed`);
      return result?.structuredContent;
    };
    desktopTool = tool;
    await mcp.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "hearthcrew-model-client-probe", version: "0.1.0-dev" } });
    controller.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} }) + "\n");
    const listed = await mcp.call("tools/list");
    const desktopTools = Array.isArray(listed?.tools) ? listed.tools.map((tool: any) => tool.name).filter((name: unknown): name is string => typeof name === "string") : [];
    report.desktopTools = desktopTools;
    const initial = await waitFor(
      async () => tool("status"),
      (value: any) => value?.game?.worldId && Array.isArray(value.game.companions)
        && value.game.companions.some((body: Body) => body.name === `P1_WORKER_${runId}`)
        && value.game.companions.some((body: Body) => body.name === `P1_RECIPIENT_${runId}`),
      180_000,
      "P1 fixture",
    );
    lastStatus = initial as DesktopStatus;
    const dynamicTools = diagnosticTools(lastStatus);
    report.configuredDynamicTools = dynamicTools;
    report.appServerDiagnostics = lastStatus.appServer;
    const metadata = await waitFor(
      async () => readJson<FixtureMetadata>(fixturePath),
      value => value?.runId === runId && value?.saveName === `${SAVE_PREFIX}${runId}`,
      30_000,
      "fixture metadata",
    );
    if (!metadata) throw new Error("fixture metadata was not available after the wait");
    const initialWorld = lastStatus.game;
    const worldId = string(initialWorld?.worldId, "worldId");
    if (metadata.worldId !== worldId || metadata.evidence !== "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE" || metadata.initialInventoryEmpty !== true) throw new Error("fixture metadata identity or empty-inventory assertion failed");
    const workerName = `P1_WORKER_${runId}`; const recipientName = `P1_RECIPIENT_${runId}`;
    const worker = fixtureBody(initialWorld, workerName); const recipient = fixtureBody(initialWorld, recipientName);
    if (!worker || !recipient || !Array.isArray(worker.inventory) || !Array.isArray(recipient.inventory) || worker.inventory.length !== 0 || recipient.inventory.length !== 0) throw new Error("fixture did not begin with exactly two empty inventories");
    if (worker.botId !== metadata.worker?.logicalUuid || worker.entityId !== metadata.worker?.entityUuid || recipient.botId !== metadata.recipient?.logicalUuid || recipient.entityId !== metadata.recipient?.entityUuid) throw new Error("status body identity does not match fixture metadata");
    if (typeof worker.bodyGeneration !== "number" || !Number.isSafeInteger(worker.bodyGeneration) || typeof recipient.bodyGeneration !== "number" || !Number.isSafeInteger(recipient.bodyGeneration)) throw new Error("fixture body generation is invalid");
    const oakPosition = position(metadata.oakPosition, "oakPosition");
    report.initial = { worldId, worker, recipient, oakPosition, saveName: `${SAVE_PREFIX}${runId}`, fixtureSave };
    const command = await tool("command", { botId: worker.botId, message: `这是一次受控 P1 真实模型任务。请观察并完成：采集场地中坐标 ${oakPosition.x},${oakPosition.y},${oakPosition.z} 的唯一橡木，制作一次 minecraft:oak_planks，然后把恰好 4 个橡木木板交给名为 ${recipientName} 的伙伴。不要采石平台、不要生成物品；每一步都用游戏工具完成并根据实际背包和动作回执确认。` }, timeoutMs);
    if (!command?.accepted) throw new Error(`owner command was not accepted: ${JSON.stringify(command).slice(0, 1000)}`);
    report.command = command;
    const deadline = Date.now() + timeoutMs;
    let lastProgress = 0;
    let idleSinceTick: number | undefined;
    while (Date.now() < deadline) {
      lastStatus = await tool("status", {}, 20_000) as DesktopStatus;
      await writeFile(resolve(runRoot, "live-status.json"), JSON.stringify(lastStatus, null, 2), "utf8");
      const currentWorld = lastStatus.game;
      const currentWorker = fixtureBody(currentWorld, workerName); const currentRecipient = fixtureBody(currentWorld, recipientName);
      if (!currentWorker || !currentRecipient) throw new Error("fixture body disappeared during model task");
      if (currentWorld?.worldId !== worldId || currentWorker.botId !== worker.botId || currentWorker.entityId !== worker.entityId || currentRecipient.botId !== recipient.botId || currentRecipient.entityId !== recipient.entityId) throw new Error("fixture world or body identity changed during model task");
      if (currentWorker.bodyGeneration !== worker.bodyGeneration || currentRecipient.bodyGeneration !== recipient.bodyGeneration) throw new Error("fixture body generation changed during model task");
      const recipientPlanks = itemCount(currentRecipient, OAK_PLANKS); const workerWood = itemCount(currentWorker, "minecraft:oak_log") + itemCount(currentWorker, OAK_PLANKS);
      if (recipientPlanks > 4) throw new Error(`recipient received duplicate oak planks: ${recipientPlanks}`);
      // MINE operates on one position; its generic quantity field is unused.
      const mine = completedReceipt(currentWorker, "MINE", payload => samePosition(payload.position, oakPosition));
      const craft = completedReceipt(currentWorker, "CRAFT", payload => payload.count === 1 && resourceId(payload.resource) === OAK_PLANKS);
      const transfer = completedReceipt(currentWorker, "TRANSFER", payload => payload.count === 4 && resourceId(payload.resource) === OAK_PLANKS && payload.target === recipient.entityId);
      const fixtureReport = await readJson<FixtureReport>(fixtureReportPath);
      if (fixtureReport?.status === "PASS" || fixtureReport?.status === "FAIL") {
        if (fixtureReport.runId !== runId || fixtureReport.worldId !== worldId || fixtureReport.saveName !== `${SAVE_PREFIX}${runId}` || fixtureReport.evidence !== "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE") throw new Error("fixture report identity or evidence boundary failed");
      }
      if (fixtureReport?.status === "FAIL") throw new Error(`model fixture failed: ${fixtureReport.failure ?? "unspecified"}`);
      const roles = lastStatus.roles ?? [];
      if (roles.some(role => role.status === "failed")) throw new Error("real model role failed; inspect report and durable events");
      const workerRole = roles.find(role => role.botId === worker.botId);
      if (workerRole?.status === "idle" && fixtureReport?.status !== "PASS" && typeof currentWorld?.gameTick === "number") {
        idleSinceTick ??= currentWorld.gameTick;
        if (currentWorld.gameTick - idleSinceTick > 300) throw new Error("model remained idle for over 15 game seconds with the controlled mission unfinished");
      } else idleSinceTick = undefined;
      if (Date.now() - lastProgress >= 10_000) {
        process.stdout.write(JSON.stringify({ progress: true, runId, gameTick: currentWorld?.gameTick, recipientPlanks, workerWood, mine: !!mine, craft: !!craft, transfer: !!transfer, roles }) + "\n");
        lastProgress = Date.now();
      }
      if (fixtureReport?.status === "PASS" && fixtureReport.oakBlockAir === true && fixtureReport.recipientOakPlanks === 4 && fixtureReport.workerWoodOrPlanks === 0 && mine && craft && transfer) {
        const actualRole = roles.find(role => role.botId === worker.botId);
        if (actualRole?.model !== "gpt-5.6-luna" || actualRole?.reasoningEffort !== "high") throw new Error("actual role did not confirm Luna/high");
        report.status = "PASS";
        report.modelActionEvidence = { act: true, evidence: "controller-submitted real MINE/CRAFT/TRANSFER receipts; controlled scene has no action driver" };
        report.final = { world: currentWorld, roles, appServer: lastStatus.appServer, fixture: fixtureReport, matchedReceipts: { mine, craft, transfer } }; break;
      }
      await new Promise(resolveDelay => setTimeout(resolveDelay, 1000));
    }
    if (report.status !== "PASS") throw new Error("real model gameplay loop timed out; no PASS evidence was observed");
  } catch (error) {
    report.failure = error instanceof Error ? error.message : String(error);
    if (lastStatus) report.lastStatus = lastStatus;
    if (!report.modelActionEvidence) report.modelActionEvidence = { act: "unconfirmed", evidence: "no matched terminal action receipts" };
    try { if (desktopTool) report.finalStatus = await desktopTool("status", {}, 10_000); } catch { /* preserve the original failure */ }
    try { if (desktopTool) report.events = await desktopTool("events", { after: 0, limit: 100 }, 10_000); } catch { /* preserve the original failure */ }
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    try { if (desktopTool && !report.events) report.events = await desktopTool("events", { after: 0, limit: 100 }, 10_000); } catch { /* preserve the terminal report */ }
    try { if (mcp) controllerClosedGracefully = await mcp.close(); } catch { controllerClosedGracefully = false; }
    if (!controllerClosedGracefully && controller && controller.exitCode === null && controller.signalCode === null) mcp?.forceClose();
    if (!controllerClosedGracefully) {
      report.controllerCleanup = "did_not_exit_cleanly";
      report.status = "FAIL";
      report.failure ??= "controller did not exit cleanly after stdin EOF";
      process.exitCode = 1;
    }
    await writeFile(reportPath, JSON.stringify(report, null, 2), { encoding: "utf8" });
    process.stdout.write(JSON.stringify({ status: report.status, runId, report: reportPath, failure: report.failure }) + "\n");
  }
}

main().catch(async error => {
  const runId = flag("--run-id", "unknown");
  const projectRoot = resolve(flag("--project-root", process.cwd()));
  const runtimeRoot = resolve(projectRoot, ".runtime");
  if (SAFE_RUN_ID.test(runId)) {
    try {
      const runRoot = ownedPath(flag("--run-root", flag("--state", resolve(runtimeRoot, `model-client-${runId}`))), runtimeRoot, "run root");
      const reportPath = ownedPath(flag("--report", resolve(runRoot, "report.json")), runRoot, "report");
      await mkdir(runRoot, { recursive: true });
      try { await readFile(reportPath, "utf8"); }
      catch (readError) {
        if ((readError as NodeJS.ErrnoException).code === "ENOENT") await writeFile(reportPath, JSON.stringify({ protocol: "hearthcrew.v1", test: "REAL_CODEX_MODEL_CLIENT", evidence: "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE", status: "FAIL", runId, failure: error instanceof Error ? error.message : String(error), finishedAt: new Date().toISOString() }, null, 2), { encoding: "utf8" });
      }
    } catch { /* invalid paths never receive fallback output */ }
  }
  process.stderr.write(`model client probe failed: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
