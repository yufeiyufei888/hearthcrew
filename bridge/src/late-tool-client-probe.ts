/**
 * P1 controlled real-client probe for an App Server tool call delivered late.
 * The model must produce the held request. This probe never synthesizes a
 * model call or a Minecraft action.
 */
import { randomBytes } from "node:crypto";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { Transform, type TransformCallback } from "node:stream";
import { StringDecoder } from "node:string_decoder";
import { mkdir, open, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { AppServerClient, type AgentProfile } from "./app-server-client.js";
import { CrewController, type BodyView, type BrainPort, type WorldView } from "./crew-controller.js";
import { CrewJournal } from "./crew-journal.js";
import { ModBridgeServer } from "./mod-bridge-server.js";
import type { ModConnection } from "./mod-connection.js";
import { PINNED_CODEX_CLI_VERSION, type BridgeRuntimeConfig } from "./runtime-config.js";

type JsonObject = Record<string, unknown>;
type Position = { readonly x: number; readonly y: number; readonly z: number };
type FixtureMetadata = {
  readonly evidence?: string;
  readonly saveName?: string;
  readonly runId?: string;
  readonly worldId?: string;
  readonly oakPosition?: Position;
  readonly worker?: { readonly logicalUuid?: string; readonly entityUuid?: string };
  readonly recipient?: { readonly logicalUuid?: string; readonly entityUuid?: string };
};
type FixtureReport = {
  readonly evidence?: string;
  readonly status?: string;
  readonly runId?: string;
  readonly saveName?: string;
  readonly worldId?: string;
  readonly oakBlockAir?: boolean;
  readonly failure?: string;
  readonly workerLogicalUuid?: string;
  readonly workerEntityUuid?: string;
  readonly recipientLogicalUuid?: string;
  readonly recipientEntityUuid?: string;
};
type ProbeStatus = {
  readonly game?: WorldView;
  readonly controller?: unknown;
  readonly appServer?: unknown;
  readonly roles?: readonly JsonObject[];
  readonly restoration?: unknown;
};
type RpcRequest = { readonly id?: string | number; readonly method?: string; readonly params?: JsonObject };
type RpcResponse = { readonly id?: string | number; readonly result?: JsonObject; readonly error?: JsonObject };
type HeldCall = {
  readonly id: string | number;
  readonly line: string;
  readonly threadId?: string;
  readonly turnId?: string;
  readonly actionId?: string;
  readonly kind: string;
  readonly arguments?: JsonObject;
};

const SAFE_RUN_ID = /^[A-Za-z0-9-]{1,48}$/;
const SAVE_PREFIX = "P1Model-";
const FIXTURE_EVIDENCE = "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE";
const MAX_APP_LINE_BYTES = 8 * 1024 * 1024;

function flag(name: string, fallback: string): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function positiveFlag(name: string, fallback: number): number {
  const value = Number(flag(name, String(fallback)));
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name} must be a positive integer`);
  return value;
}

function object(value: unknown, label: string): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as JsonObject;
}

function text(value: unknown, label: string): string {
  if (typeof value !== "string" || !value) throw new Error(`${label} must be a non-empty string`);
  return value;
}

function safePath(value: string, root: string, label: string): string {
  const path = resolve(value);
  const relativePath = relative(resolve(root), path).toLowerCase();
  if (isAbsolute(relativePath) || relativePath === ".." || relativePath.startsWith(`..${sep}`) || relativePath.startsWith("../")) {
    throw new Error(`${label} must stay under ${root}`);
  }
  return path;
}

function position(value: unknown, label: string): Position {
  const candidate = object(value, label);
  for (const axis of ["x", "y", "z"] as const) {
    if (typeof candidate[axis] !== "number" || !Number.isFinite(candidate[axis])) throw new Error(`${label}.${axis} is invalid`);
  }
  return { x: candidate.x as number, y: candidate.y as number, z: candidate.z as number };
}

function samePosition(left: unknown, right: Position): boolean {
  try {
    const candidate = position(left, "position");
    return candidate.x === right.x && candidate.y === right.y && candidate.z === right.z;
  } catch {
    return false;
  }
}

function exists(path: string): Promise<boolean> {
  return stat(path).then(() => true, error => (error as NodeJS.ErrnoException).code === "ENOENT" ? false : Promise.reject(error));
}

async function readJson<T>(path: string): Promise<T | undefined> {
  try {
    return JSON.parse(await readFile(path, "utf8")) as T;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT" || error instanceof SyntaxError) return undefined;
    throw error;
  }
}

function redacted(value: unknown, depth = 0): unknown {
  if (depth > 5) return "<truncated>";
  if (typeof value === "string") return value.length > 500 ? `${value.slice(0, 500)}…` : value;
  if (Array.isArray(value)) return value.slice(0, 100).map(entry => redacted(entry, depth + 1));
  if (!value || typeof value !== "object") return value;
  const result: JsonObject = {};
  for (const [key, entry] of Object.entries(value as JsonObject)) {
    result[key] = /token|auth|password|secret|credential/i.test(key) ? "<redacted>" : redacted(entry, depth + 1);
  }
  return result;
}

async function writeReport(path: string, report: unknown): Promise<void> {
  await mkdir(resolve(path, ".."), { recursive: true });
  await writeFile(path, JSON.stringify(redacted(report), null, 2), { encoding: "utf8", flag: "wx" });
}

async function appendEvidence(path: string, value: unknown): Promise<void> {
  const file = await open(path, "a");
  try {
    await file.writeFile(`${JSON.stringify(redacted(value))}\n`, "utf8");
    await file.sync();
  } finally {
    await file.close();
  }
}

async function waitFor<T>(read: () => Promise<T>, ready: (value: T) => boolean, timeoutMs: number, label: string): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | undefined;
  while (Date.now() < deadline) {
    last = await read();
    if (ready(last)) return last;
    await new Promise(resolveDelay => setTimeout(resolveDelay, 500));
  }
  throw new Error(`${label} timed out${last === undefined ? "" : `; last=${JSON.stringify(redacted(last)).slice(0, 1200)}`}`);
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, label: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timed out`)), timeoutMs); }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function fixtureBody(view: WorldView | undefined, name: string): BodyView | undefined {
  const matches = (view?.companions ?? []).filter(candidate => candidate.name === name);
  return matches.length === 1 ? matches[0] : undefined;
}

function actionRows(body: BodyView | undefined): JsonObject[] {
  return (body?.actionJournal ?? []).filter((entry): entry is JsonObject => !!entry && typeof entry === "object" && !Array.isArray(entry));
}

function assertFixtureReportIdentity(report: FixtureReport | undefined, runId: string, worldId: string): void {
  if (!report) return;
  if (report.runId !== undefined && report.runId !== runId) throw new Error("fixture report runId mismatch");
  if (report.worldId !== undefined && report.worldId !== worldId) throw new Error("fixture report worldId mismatch");
  if (report.saveName !== undefined && report.saveName !== `${SAVE_PREFIX}${runId}`) throw new Error("fixture report saveName mismatch");
  if (report.evidence !== undefined && report.evidence !== FIXTURE_EVIDENCE) throw new Error("fixture report evidence mismatch");
}

function assertCompleteFixtureReport(report: FixtureReport | undefined, runId: string, worldId: string, worker: BodyView, recipient: BodyView): asserts report is FixtureReport {
  if (!report) throw new Error("fixture report is unavailable");
  if (report.evidence !== FIXTURE_EVIDENCE || report.runId !== runId || report.saveName !== `${SAVE_PREFIX}${runId}` || report.worldId !== worldId) {
    throw new Error("fixture report identity fields are incomplete or mismatched");
  }
  if (report.oakBlockAir !== false) throw new Error("fixture report did not confirm oakBlockAir=false");
  if (report.workerLogicalUuid !== worker.botId || report.workerEntityUuid !== worker.entityId
    || report.recipientLogicalUuid !== recipient.botId || report.recipientEntityUuid !== recipient.entityId) {
    throw new Error("fixture report body identity fields are incomplete or mismatched");
  }
}

function completeFixtureReport(report: FixtureReport | undefined, runId: string, worldId: string, worker: BodyView, recipient: BodyView): boolean {
  if (report?.status === "FAIL") return true;
  try { assertCompleteFixtureReport(report, runId, worldId, worker, recipient); return true; } catch { return false; }
}

function actionPayload(entry: JsonObject): JsonObject | undefined {
  const value = entry.payload;
  return value && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : undefined;
}

function actionId(entry: JsonObject): string | undefined {
  if (typeof entry.id === "string") return entry.id;
  const id = entry.id;
  if (id && typeof id === "object" && !Array.isArray(id) && typeof (id as JsonObject).value === "string") return (id as JsonObject).value as string;
  return undefined;
}

function observedOak(value: unknown, oak: Position): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const observation = (value as JsonObject).observation;
  if (!observation || typeof observation !== "object" || Array.isArray(observation)) return false;
  const blocks = (observation as JsonObject).blocks;
  if (!Array.isArray(blocks)) return false;
  return blocks.some(entry => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) return false;
    const block = entry as JsonObject;
    return block.block === "minecraft:oak_log" && samePosition(block.position, oak);
  });
}

function assertObservedBodies(value: unknown, worldId: string, worker: BodyView, recipient: BodyView): void {
  const view = object(value, "actual Mod observation") as unknown as WorldView;
  if (view.worldId !== worldId) throw new Error("observation world changed");
  for (const original of [worker, recipient]) {
    const found = view.companions?.find(body => body.botId === original.botId);
    if (!found || found.entityId !== original.entityId || found.bodyGeneration !== original.bodyGeneration
      || !Array.isArray(found.inventory) || found.inventory.length !== 0)
      throw new Error("observation body identity, generation or empty inventory changed");
  }
}

function isStaleToolRejection(response: RpcResponse): boolean {
  const result = response.result;
  if (!result || result.success !== false || !Array.isArray(result.contentItems)) return false;
  return result.contentItems.some(item => {
    if (!item || typeof item !== "object" || Array.isArray(item)) return false;
    const itemText = (item as JsonObject).text;
    return typeof itemText === "string" && /stale\s+or\s+inactive/i.test(itemText);
  });
}

class DelayedAppOutput extends Transform {
  private readonly decoder = new StringDecoder("utf8");
  private buffer = "";
  private held?: HeldCall;
  private released = false;

  constructor(private readonly onHeld: (call: HeldCall) => void) { super(); }

  release(): HeldCall {
    if (!this.held) throw new Error("no delayed App Server call is held");
    const call = this.held;
    this.held = undefined;
    this.released = true;
    this.push(`${call.line}\n`);
    return call;
  }

  private inspect(line: string): HeldCall | undefined {
    try {
      const request = JSON.parse(line) as RpcRequest;
      if (request.method !== "item/tool/call" || request.id === undefined || request.params?.tool !== "act") return undefined;
      const args = request.params.arguments && typeof request.params.arguments === "object" && !Array.isArray(request.params.arguments)
        ? request.params.arguments as JsonObject : undefined;
      const kind = typeof args?.kind === "string" ? args.kind.toUpperCase() : "";
      if (kind !== "MINE") return undefined;
      return {
        id: request.id,
        line,
        threadId: typeof request.params.threadId === "string" ? request.params.threadId : undefined,
        turnId: typeof request.params.turnId === "string" ? request.params.turnId : undefined,
        actionId: typeof args?.actionId === "string" ? args.actionId : undefined,
        kind,
        arguments: args,
      };
    } catch {
      return undefined;
    }
  }

  private processText(text: string): void {
    this.buffer += text;
    if (Buffer.byteLength(this.buffer, "utf8") > MAX_APP_LINE_BYTES) throw new Error("App Server JSONL buffering exceeded 8 MiB");
    const lines = this.buffer.split("\n");
    this.buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line) { this.push("\n"); continue; }
      const call = !this.held && !this.released ? this.inspect(line) : undefined;
      if (call) { this.held = call; this.onHeld(call); } else this.push(`${line}\n`);
    }
  }

  _transform(chunk: Buffer, _encoding: BufferEncoding, callback: TransformCallback): void {
    try { this.processText(this.decoder.write(chunk)); callback(); }
    catch (error) { callback(error as Error); }
  }

  _flush(callback: TransformCallback): void {
    try {
      this.processText(this.decoder.end());
      if (this.buffer) {
        const call = !this.held && !this.released ? this.inspect(this.buffer) : undefined;
        if (call) { this.held = call; this.onHeld(call); } else this.push(this.buffer);
      }
      callback();
    } catch (error) { callback(error as Error); }
  }
}

function installDelay(
  child: ChildProcessWithoutNullStreams,
  onHeld: (call: HeldCall) => void,
  onResponse: (response: RpcResponse) => void,
): DelayedAppOutput {
  const output = new DelayedAppOutput(onHeld);
  child.stdout.pipe(output);
  Object.defineProperty(child, "stdout", { configurable: true, value: output });
  const input = child.stdin as unknown as { write: (...args: any[]) => boolean };
  const originalWrite = input.write.bind(input);
  const responseDecoder = new StringDecoder("utf8");
  let responseBuffer = "";
  input.write = (...args: any[]): boolean => {
    const chunk = args[0];
    if (typeof chunk === "string" || Buffer.isBuffer(chunk)) {
      responseBuffer += responseDecoder.write(typeof chunk === "string" ? Buffer.from(chunk, "utf8") : chunk);
      if (Buffer.byteLength(responseBuffer, "utf8") > MAX_APP_LINE_BYTES) throw new Error("App Server response buffering exceeded 8 MiB");
      const responseLines = responseBuffer.split("\n");
      responseBuffer = responseLines.pop() ?? "";
      for (const line of responseLines) {
        if (!line) continue;
        try {
          const response = JSON.parse(line) as RpcResponse;
          if (response.id !== undefined && (response.result !== undefined || response.error !== undefined)) onResponse(response);
        } catch { /* only JSONL responses are inspected */ }
      }
    }
    return originalWrite(...args);
  };
  return output;
}

function brain(app: AppServerClient): BrainPort {
  return {
    createThread: (profile: AgentProfile["id"]) => app.createThread(profile),
    startTurn: (thread, input, profile, generation) => app.startTurn(thread, input, profile, generation),
    diagnostics: () => app.diagnostics(),
  };
}

async function main(): Promise<void> {
  const projectRoot = resolve(flag("--project-root", process.cwd()));
  const runId = flag("--run-id", "");
  if (!SAFE_RUN_ID.test(runId)) throw new Error("provide a unique --run-id");
  const runtimeRoot = resolve(projectRoot, ".runtime");
  const runRoot = safePath(flag("--run-root", flag("--state", resolve(runtimeRoot, `model-client-${runId}`))), runtimeRoot, "run root");
  const state = safePath(flag("--controller-state", resolve(runRoot, "controller")), runRoot, "controller state");
  const reportPath = safePath(flag("--report", resolve(runRoot, "report.json")), runRoot, "report");
  const evidencePath = safePath(resolve(runRoot, "late-tool-evidence.jsonl"), runRoot, "evidence log");
  const saveRoot = resolve(projectRoot, "mod", "run", "client", "saves");
  const fixtureSave = safePath(flag("--fixture-save", flag("--fixture", resolve(saveRoot, `${SAVE_PREFIX}${runId}`))), saveRoot, "fixture save");
  const expectedSave = resolve(saveRoot, `${SAVE_PREFIX}${runId}`);
  if (fixtureSave.toLowerCase() !== expectedSave.toLowerCase()) throw new Error("fixture save must be exactly P1Model-<runId>");
  const fixturePath = resolve(fixtureSave, `hearthcrew-p1-model-fixture-${runId}.json`);
  const fixtureReportPath = safePath(flag("--fixture-report", resolve(fixtureSave, `hearthcrew-p1-model-report-${runId}.json`)), fixtureSave, "fixture report");
  const localApp = process.env.LOCALAPPDATA ?? resolve(projectRoot, "AppData", "Local");
  const codexHome = resolve(flag("--codex-runtime", resolve(localApp, "HearthCrew", "codex-runtime")));
  const workspace = resolve(flag("--workspace", resolve(localApp, "HearthCrew", "workspace")));
  const codexCommand = flag("--codex-command", "codex");
  const modelCatalog = flag("--model-catalog", "");
  const timeoutMs = positiveFlag("--timeout-seconds", 900) * 1000;

  if (await exists(reportPath)) throw new Error(`report already exists; unique run required: ${reportPath}`);
  await mkdir(runRoot, { recursive: true });
  await mkdir(state, { recursive: true });
  if ((await readdir(state)).length !== 0) throw new Error(`controller state must be fresh for late-tool probe: ${state}`);

  const report: JsonObject = {
    protocol: "hearthcrew.v1",
    test: "P1_LATE_TOOL_CLIENT",
    evidence: "CONTROLLED_REAL_CLIENT_LATE_TOOL",
    injected: "INJECTED_DELIVERY_DELAY",
    status: "FAIL",
    runId,
    startedAt: new Date().toISOString(),
    reportPath,
    evidencePath,
  };
  const token = randomBytes(32).toString("hex");
  const journal = new CrewJournal(resolve(state, "crew-events.jsonl"));
  const controller = new CrewController(journal);
  let modServer: ModBridgeServer | undefined;
  let active: ModConnection | undefined;
  let app: AppServerClient | undefined;
  let delayed: DelayedAppOutput | undefined;
  let heldResolve!: (call: HeldCall) => void;
  const heldPromise = new Promise<HeldCall>(resolveHeld => { heldResolve = resolveHeld; });
  let heldId: string | number | undefined;
  let responseResolve: ((response: RpcResponse) => void) | undefined;
  const onAppResponse = (response: RpcResponse): void => {
    if (heldId !== undefined && response.id === heldId) responseResolve?.(response);
  };

  try {
    await controller.initialize();
    modServer = new ModBridgeServer({ token });
    let attachedResolve!: () => void;
    let attachedReject!: (error: Error) => void;
    const attached = new Promise<void>((resolveAttached, rejectAttached) => {
      attachedResolve = resolveAttached;
      attachedReject = rejectAttached;
    });
    modServer.onConnection(connection => {
      active = connection;
      void controller.attach(connection).then(attachedResolve, attachedReject);
    });
    const port = await modServer.start();
    const pairingPath = resolve(state, "pairing.json");
    await writeFile(pairingPath, JSON.stringify({ port, token }), { encoding: "utf8", flag: "wx" });

    const runtime: BridgeRuntimeConfig = {
      codexHome,
      workspace,
      codexCommand,
      ...(modelCatalog ? { modelCatalogJson: resolve(modelCatalog) } : {}),
    };
    const spawnProcess = (
      command: string,
      args: readonly string[],
      options: { cwd: string; env: NodeJS.ProcessEnv; stdio: ["pipe", "pipe", "pipe"]; windowsHide?: boolean },
    ): ChildProcessWithoutNullStreams => {
      const child = spawn(command, [...args], { ...options, windowsHide: true });
      delayed = installDelay(child, heldResolve, onAppResponse);
      return child;
    };
    app = new AppServerClient({
      runtime,
      spawnProcess,
      expectedCliVersion: PINNED_CODEX_CLI_VERSION,
      dynamicToolHandler: params => controller.toolCall(params),
    });
    await app.start();
    const diagnostics = await app.readOnlyDiagnostics();
    const account = object(diagnostics.account, "account diagnostics");
    if (!account.account) throw new Error("dedicated Codex login is unavailable");
    const mcpServers = diagnostics.mcpServers && typeof diagnostics.mcpServers === "object" && Array.isArray((diagnostics.mcpServers as JsonObject).data)
      ? (diagnostics.mcpServers as JsonObject).data as unknown[] : [];
    if (mcpServers.length !== 0) throw new Error("dedicated Codex runtime inherited unexpected MCP servers");
    const fast = await app.verifyFastServiceTier();
    report.appServer = { diagnostics: app.diagnostics(), fast: { verified: fast.verified, echoed: fast.echoed, reason: fast.reason } };
    await controller.setBrain(brain(app));
    await withTimeout(attached, 180_000, "Mod body attachment");

    const status = async (): Promise<ProbeStatus> => await controller.status() as ProbeStatus;
    const workerName = `P1_WORKER_${runId}`;
    const recipientName = `P1_RECIPIENT_${runId}`;
    const initial = await waitFor(
      status,
      value => !!value.game?.worldId && !!fixtureBody(value.game, workerName) && !!fixtureBody(value.game, recipientName),
      180_000,
      "P1 fixture",
    );
    const metadata = await waitFor(
      () => readJson<FixtureMetadata>(fixturePath),
      value => value?.runId === runId && value.saveName === `${SAVE_PREFIX}${runId}`,
      60_000,
      "fixture metadata",
    );
    if (!metadata) throw new Error("fixture metadata unavailable");
    const worldId = text(initial.game?.worldId, "worldId");
    const worker = fixtureBody(initial.game, workerName);
    const recipient = fixtureBody(initial.game, recipientName);
    if (!worker || !recipient || !Array.isArray(worker.inventory) || !Array.isArray(recipient.inventory)
      || worker.inventory.length !== 0 || recipient.inventory.length !== 0) throw new Error("fixture did not start with two empty inventories");
    if (metadata.worldId !== worldId || metadata.evidence !== FIXTURE_EVIDENCE
      || metadata.worker?.logicalUuid !== worker.botId || metadata.worker.entityUuid !== worker.entityId
      || metadata.recipient?.logicalUuid !== recipient.botId || metadata.recipient.entityUuid !== recipient.entityId) throw new Error("fixture identity mismatch");
    const oak = position(metadata.oakPosition, "oakPosition");
    const workerId = text(worker.botId, "worker botId");
    const recipientId = text(recipient.botId, "recipient botId");
    if (!active) throw new Error("Mod connection was not attached");
    const fixtureAtReady = await waitFor(
      () => readJson<FixtureReport>(fixtureReportPath),
      value => completeFixtureReport(value, runId, worldId, worker, recipient),
      60_000,
      "fixture report",
    );
    assertFixtureReportIdentity(fixtureAtReady, runId, worldId);
    if (fixtureAtReady?.status === "FAIL") throw new Error(`fixture failed before late-tool injection: ${fixtureAtReady.failure ?? "unspecified"}`);
    assertCompleteFixtureReport(fixtureAtReady, runId, worldId, worker, recipient);
    const oakBefore = await active.request("observe", { botId: workerId, radius: 32 }, { bodyGeneration: worker.bodyGeneration, timeoutMs: 10_000 });
    assertObservedBodies(oakBefore, worldId, worker, recipient);
    const oakBeforePresent = observedOak(oakBefore, oak);
    if (!oakBeforePresent) throw new Error("fixture oak was not observed at the expected position before injection");
    report.initial = { worldId, worker, recipient, oak, oakObserved: oakBeforePresent, cliVersion: app.cliVersion };
    await appendEvidence(evidencePath, { phase: "ready", worldId, worker, recipient, oak, observe: { radius: 32, oakPresent: oakBeforePresent } });

    const command = await controller.command(
      `这是一次受控迟到工具测试。请先 observe，然后采集坐标 ${oak.x},${oak.y},${oak.z} 的唯一橡木。只提交一个 MINE 动作并等待真实结果，不要制作、交付、放置或生成任何物品。`,
      workerId,
    );
    if (!(command as JsonObject)?.accepted) throw new Error(`owner command was not accepted: ${JSON.stringify(redacted(command))}`);
    report.ownerCommand = { accepted: true };
    const held = await withTimeout(heldPromise, Math.min(timeoutMs, 120_000), "real model MINE tool call");
    if (!held.threadId || !held.turnId || !held.actionId || held.kind !== "MINE") throw new Error("held MINE call lacked thread, turn, or action identity");
    const heldParameters = held.arguments?.parameters;
    const heldPosition = heldParameters && typeof heldParameters === "object" && !Array.isArray(heldParameters)
      ? (heldParameters as JsonObject).position : undefined;
    if (!samePosition(heldPosition, oak)) throw new Error("held MINE call did not target the fixture oak position");
    heldId = held.id;
    report.delayedCall = { rpcId: held.id, threadId: held.threadId, turnId: held.turnId, actionId: held.actionId, kind: held.kind, arguments: held.arguments };
    await appendEvidence(evidencePath, { phase: "actual-call-held", rpcId: held.id, threadId: held.threadId, turnId: held.turnId, actionId: held.actionId, tool: "act", kind: held.kind, arguments: held.arguments });

    const beforeStop = await status();
    await controller.control("stop", workerId);
    const stopped = await waitFor(status, value => fixtureBody(value.game, workerName)?.stopped === true, 15_000, "body stop");
    const stoppedWorker = fixtureBody(stopped.game, workerName);
    const stoppedRecipient = fixtureBody(stopped.game, recipientName);
    if (stopped.game?.worldId !== worldId || !stoppedWorker || !stoppedRecipient || stoppedWorker.stopped !== true || stoppedWorker.action != null
      || stoppedWorker.botId !== workerId || stoppedWorker.entityId !== worker.entityId || stoppedWorker.bodyGeneration !== worker.bodyGeneration
      || stoppedRecipient.botId !== recipientId || stoppedRecipient.entityId !== recipient.entityId
      || (stoppedWorker.inventory ?? []).length !== 0 || (stoppedRecipient.inventory ?? []).length !== 0) throw new Error("stop did not leave a stopped, idle body with unchanged identity/inventory");
    await appendEvidence(evidencePath, { phase: "owner-stop", worldId, gameTick: beforeStop.game?.gameTick, worker: stoppedWorker, recipient: stoppedRecipient, command: "stop" });
    const lateResponsePromise = new Promise<RpcResponse>(resolveResponse => { responseResolve = resolveResponse; });
    delayed?.release();
    let lateResponse: RpcResponse;
    try {
      lateResponse = await withTimeout(lateResponsePromise, 30_000, "late stale-tool response");
    } finally {
      responseResolve = undefined;
    }
    if (lateResponse.id !== held.id || !isStaleToolRejection(lateResponse)) throw new Error(`late MINE call was not rejected as stale or inactive: ${JSON.stringify(redacted(lateResponse))}`);
    report.lateResponse = { rpcId: lateResponse.id, response: lateResponse, success: lateResponse.result?.success, contentItems: lateResponse.result?.contentItems };
    await appendEvidence(evidencePath, { phase: "actual-response", rpcId: lateResponse.id, tool: "act", response: lateResponse });

    const preparedBefore = journal.rows(worldId).filter(row => row.type === "action.prepared" && row.data.botId === workerId);
    const modReceiptsBefore = actionRows(stoppedWorker);
    if (preparedBefore.length !== 0 || modReceiptsBefore.length !== 0) throw new Error(`late MINE produced a prepared or Mod receipt: prepared=${preparedBefore.length}, receipts=${modReceiptsBefore.length}`);
    const afterLate = await waitFor(
      status,
      value => {
        const candidateWorker = fixtureBody(value.game, workerName);
        const candidateRecipient = fixtureBody(value.game, recipientName);
        return value.game?.worldId === worldId && candidateWorker?.stopped === true && candidateWorker.action == null
          && candidateWorker.botId === workerId && candidateWorker.entityId === worker.entityId
          && candidateWorker.bodyGeneration === worker.bodyGeneration && candidateRecipient?.botId === recipientId
          && candidateRecipient.entityId === recipient.entityId && candidateRecipient.bodyGeneration === recipient.bodyGeneration;
      },
      15_000,
      "fresh post-late status",
    );
    const afterLateWorker = fixtureBody(afterLate.game, workerName);
    const afterLateRecipient = fixtureBody(afterLate.game, recipientName);
    if (!afterLateWorker || !afterLateRecipient) throw new Error("fresh post-late status omitted fixture bodies");
    const oakAfterStop = await active.request("observe", { botId: workerId, radius: 32 }, { bodyGeneration: afterLateWorker.bodyGeneration, timeoutMs: 10_000 });
    assertObservedBodies(oakAfterStop, worldId, worker, recipient);
    const oakAfterStopPresent = observedOak(oakAfterStop, oak);
    if (!oakAfterStopPresent) throw new Error("actual Mod observation did not confirm the oak remained after late delivery");
    const afterLateRows = actionRows(afterLateWorker);
    if (afterLateRows.length !== 0) throw new Error("fresh post-late status contains an unexpected action receipt");
    report.afterLateCall = { worldId, worker: afterLateWorker, recipient: afterLateRecipient, prepared: 0, modReceipts: afterLateRows.length, oakObserved: oakAfterStopPresent };
    await appendEvidence(evidencePath, { phase: "post-late-world-observation", worker: afterLateWorker, recipient: afterLateRecipient, observe: { radius: 32, oakPresent: oakAfterStopPresent }, prepared: 0, modReceipts: afterLateRows.length });

    const fixtureBeforeResume = await readJson<FixtureReport>(fixtureReportPath);
    assertFixtureReportIdentity(fixtureBeforeResume, runId, worldId);
    if (fixtureBeforeResume?.status === "FAIL") throw new Error(`fixture failed during late-tool test: ${fixtureBeforeResume.failure ?? "unspecified"}`);
    assertCompleteFixtureReport(fixtureBeforeResume, runId, worldId, stoppedWorker, stoppedRecipient);
    await controller.control("resume", workerId);
    const resumeCommand = await controller.command("迟到调用已被拒绝。现在只执行一个安全的 WAIT 1 动作，不要采集、制作、交付、放置或攻击。", workerId);
    const newIntentId = text((resumeCommand as JsonObject)?.intentId, "new owner intentId");
    if (!(resumeCommand as JsonObject)?.accepted) throw new Error("resume owner command was not accepted");
    const finalDeadline = Date.now() + Math.min(timeoutMs, 180_000);
    let finalStatus: ProbeStatus | undefined;
    let finalWorker: BodyView | undefined;
    let finalRecipient: BodyView | undefined;
    let waitReceipt: JsonObject | undefined;
    let finalVerified = false;
    while (Date.now() < finalDeadline) {
      finalStatus = await status();
      finalWorker = fixtureBody(finalStatus.game, workerName);
      finalRecipient = fixtureBody(finalStatus.game, recipientName);
      const fixture = await readJson<FixtureReport>(fixtureReportPath);
      if (fixture?.status === "FAIL") throw new Error(`fixture failed after resume: ${fixture.failure ?? "unspecified"}`);
      const rows = actionRows(finalWorker);
      if (finalStatus.roles?.some(role => role.botId === workerId && role.status === "failed") && !finalWorker?.action)
        throw new Error("new WAIT model turn failed without active body work");
      const forbidden = rows.find(entry => ["MINE", "CRAFT", "TRANSFER"].includes(String(actionPayload(entry)?.kind)));
      if (forbidden) throw new Error("resumed command produced a forbidden MINE/CRAFT/TRANSFER action");
      const newIntentUnexpected = rows.find(entry => {
        const id = actionId(entry);
        return id?.startsWith(`${newIntentId}:`) && actionPayload(entry)?.kind !== "WAIT";
      });
      if (newIntentUnexpected) throw new Error("resumed command produced an action other than the verified WAIT");
      waitReceipt = rows.find(entry => entry.state === "COMPLETED" && actionPayload(entry)?.kind === "WAIT"
        && actionPayload(entry)?.count === 1 && actionId(entry)?.startsWith(`${newIntentId}:`));
      const sameBodies = finalStatus.game?.worldId === worldId && finalWorker?.botId === workerId && finalWorker.entityId === worker.entityId
        && finalWorker.bodyGeneration === worker.bodyGeneration && finalRecipient?.botId === recipientId
        && finalRecipient.entityId === recipient.entityId && finalRecipient.bodyGeneration === recipient.bodyGeneration;
      const empty = (finalWorker?.inventory ?? []).length === 0 && (finalRecipient?.inventory ?? []).length === 0;
      if (waitReceipt && sameBodies && empty) { finalVerified = true; break; }
      await new Promise(resolveDelay => setTimeout(resolveDelay, 500));
    }
    if (!finalVerified || !finalStatus || !finalWorker || !finalRecipient || !waitReceipt) throw new Error("fresh owner command did not execute the verified safe WAIT 1 action");
    const verifiedStatus = finalStatus;
    const verifiedWorker = finalWorker;
    const verifiedRecipient = finalRecipient;
    const verifiedRows = actionRows(verifiedWorker);
    const verifiedIntentRows = verifiedRows.filter(entry => actionId(entry)?.startsWith(`${newIntentId}:`));
    const verifiedIds = new Set(verifiedRows.map(actionId));
    // The real Mod journal retains ACCEPTED, RUNNING and COMPLETED records
    // for one action. Count identities and terminal states, not journal rows.
    if (verifiedIds.size !== 1 || !verifiedIds.has(actionId(waitReceipt)) || verifiedIds.has(undefined)
      || verifiedRows.length !== verifiedIntentRows.length
      || verifiedRows.filter(entry => entry.state === "COMPLETED").length !== 1
      || verifiedRows.some(entry => actionPayload(entry)?.kind !== "WAIT" || actionPayload(entry)?.count !== 1
        || !["ACCEPTED", "RUNNING", "COMPLETED"].includes(String(entry.state))
        || (entry.epoch as JsonObject | undefined)?.bodyGeneration !== worker.bodyGeneration)) {
      throw new Error("final action journal was not exactly one completed WAIT 1 for the new intent");
    }
    const oakFinal = await active.request("observe", { botId: workerId, radius: 32 }, { bodyGeneration: verifiedWorker.bodyGeneration, timeoutMs: 10_000 });
    assertObservedBodies(oakFinal, worldId, worker, recipient);
    const oakFinalPresent = observedOak(oakFinal, oak);
    if (!oakFinalPresent) throw new Error("final actual Mod observation did not confirm the oak remained");
    const fixtureAfter = await waitFor(
      () => readJson<FixtureReport>(fixtureReportPath),
      value => completeFixtureReport(value, runId, worldId, verifiedWorker, verifiedRecipient),
      30_000,
      "final fixture report",
    );
    assertFixtureReportIdentity(fixtureAfter, runId, worldId);
    if (fixtureAfter?.status === "FAIL") throw new Error(`fixture final report failed: ${fixtureAfter.failure ?? "unspecified"}`);
    assertCompleteFixtureReport(fixtureAfter, runId, worldId, verifiedWorker, verifiedRecipient);
    const finalJournalRows = journal.rows(worldId).filter(row => row.data.botId === workerId);
    const stalePrepared = finalJournalRows.find(row => row.type === "action.prepared" && row.data.intentId !== newIntentId);
    if (stalePrepared) throw new Error("final journal retained a prepared action from the invalidated intent");
    const finalPrepared = finalJournalRows.filter(row => row.type === "action.prepared");
    if (finalPrepared.length !== 1 || finalPrepared[0]?.data.actionId !== actionId(waitReceipt)) throw new Error("final journal did not contain exactly the new WAIT preparation");
    const finalReceipts = finalJournalRows.filter(row => row.type === "action.receipt");
    if (finalReceipts.length !== 1 || finalReceipts[0]?.data.actionId !== actionId(waitReceipt)) throw new Error("final journal did not contain exactly the new WAIT receipt");
    report.final = {
      status: verifiedStatus,
      waitReceipt,
      fixture: fixtureAfter,
      observedPostconditions: {
        verified: finalVerified,
        sameWorld: verifiedStatus.game?.worldId === worldId,
        sameWorker: verifiedWorker.botId === workerId && verifiedWorker.entityId === worker.entityId && verifiedWorker.bodyGeneration === worker.bodyGeneration,
        sameRecipient: verifiedRecipient.botId === recipientId && verifiedRecipient.entityId === recipient.entityId && verifiedRecipient.bodyGeneration === recipient.bodyGeneration,
        inventoriesEmpty: verifiedWorker.inventory.length === 0 && verifiedRecipient.inventory.length === 0,
        oakObservedAtExpectedPosition: oakFinalPresent,
        fixtureOakBlockAir: fixtureAfter?.oakBlockAir,
        waitCount: actionPayload(waitReceipt)?.count,
        waitIntentId: newIntentId,
      },
    };
    report.status = "PASS";
  } catch (error) {
    report.failure = error instanceof Error ? error.message : String(error);
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    try { delayed?.destroy(); } catch { /* cleanup continues */ }
    try { await app?.stop(); } catch (error) { report.appCleanupError = error instanceof Error ? error.message : String(error); report.status = "FAIL"; process.exitCode = 1; }
    try { await modServer?.stop(); } catch (error) { report.modCleanupError = error instanceof Error ? error.message : String(error); report.status = "FAIL"; process.exitCode = 1; }
    try { await unlink(resolve(state, "pairing.json")); } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") { report.pairingCleanupError = error instanceof Error ? error.message : String(error); report.status = "FAIL"; process.exitCode = 1; } }
    try { await writeReport(reportPath, report); } catch (error) { process.stderr.write(`late-tool report write failed: ${error instanceof Error ? error.message : String(error)}\n`); process.exitCode = 1; }
    process.stdout.write(`${JSON.stringify({ status: report.status, runId, report: reportPath, failure: report.failure })}\n`);
  }
}

main().catch(async error => {
  const runId = flag("--run-id", "unknown");
  if (SAFE_RUN_ID.test(runId)) {
    try {
      const projectRoot = resolve(flag("--project-root", process.cwd()));
      const runtimeRoot = resolve(projectRoot, ".runtime");
      const runRoot = safePath(flag("--run-root", flag("--state", resolve(runtimeRoot, `model-client-${runId}`))), runtimeRoot, "run root");
      const reportPath = safePath(flag("--report", resolve(runRoot, "report.json")), runRoot, "report");
      if (!(await exists(reportPath))) await writeReport(reportPath, { protocol: "hearthcrew.v1", test: "P1_LATE_TOOL_CLIENT", evidence: "CONTROLLED_REAL_CLIENT_LATE_TOOL", status: "FAIL", runId, failure: error instanceof Error ? error.message : String(error), finishedAt: new Date().toISOString() });
    } catch { /* never write outside the validated runtime root */ }
  }
  process.stderr.write(`late-tool client probe failed: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
