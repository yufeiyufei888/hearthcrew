/**
 * Controlled, real-client P1 recovery probe.
 *
 * This file deliberately owns only the controller process it starts.  It never
 * starts Minecraft, reads pairing tokens, or asks the model for a new goal
 * after a fault.  The goal is to prove that the durable owner command is
 * resumed by a fresh controller session.
 */
import { spawn, type ChildProcessWithoutNullStreams, execFile as execFileCallback } from "node:child_process";
import { createInterface } from "node:readline";
import { open, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { promisify } from "node:util";
type JsonObject = Record<string, unknown>;
type Position = {
    readonly x: number;
    readonly y: number;
    readonly z: number;
};
type Body = {
    readonly botId?: string;
    readonly entityId?: string;
    readonly bodyGeneration?: number;
    readonly name?: string;
    readonly inventory?: readonly {
        readonly item?: string;
        readonly count?: number;
    }[];
    readonly actionJournal?: readonly JsonObject[];
    readonly action?: JsonObject;
};
type WorldView = {
    readonly worldId?: string;
    readonly gameTick?: number;
    readonly companions?: readonly Body[];
};
type DesktopStatus = {
    readonly game?: WorldView;
    readonly controller?: unknown;
    readonly appServer?: unknown;
    readonly roles?: readonly JsonObject[];
    readonly restoration?: unknown;
};
type FixtureMetadata = {
    readonly evidence?: string;
    readonly saveName?: string;
    readonly runId?: string;
    readonly worldId?: string;
    readonly oakPosition?: Position;
    readonly worker?: {
        readonly logicalUuid?: string;
        readonly entityUuid?: string;
    };
    readonly recipient?: {
        readonly logicalUuid?: string;
        readonly entityUuid?: string;
    };
};
type FixtureReport = {
    readonly evidence?: string;
    readonly status?: string;
    readonly runId?: string;
    readonly saveName?: string;
    readonly worldId?: string;
    readonly oakBlockAir?: boolean;
    readonly recipientOakPlanks?: number;
    readonly workerWoodOrPlanks?: number;
    readonly mineCompleted?: boolean;
    readonly craftCompleted?: boolean;
    readonly transferCompleted?: boolean;
    readonly failure?: string;
};
type ProcessInfo = {
    readonly pid: number;
    readonly parentPid: number;
    readonly name: string;
    readonly commandLine: string;
    readonly creationDate?: string;
};
type FaultKind = "controller" | "app-server";
const execFile = promisify(execFileCallback);
const SAFE_RUN_ID = /^[A-Za-z0-9-]{1,48}$/;
const SAVE_PREFIX = "P1Model-";
const OAK = "minecraft:oak_planks";
const REQUIRED_TOOLS = ["observe", "propose_work", "act", "share", "remember"] as const;
function flag(name: string, fallback: string): string { const i = process.argv.indexOf(name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function positiveFlag(name: string, fallback: number): number { const n = Number(flag(name, String(fallback))); if (!Number.isSafeInteger(n) || n < 1)
    throw new Error(`${name} must be a positive integer`); return n; }
function obj(value: unknown, label: string): JsonObject { if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error(`${label} must be an object`); return value as JsonObject; }
function text(value: unknown, label: string): string { if (typeof value !== "string" || !value)
    throw new Error(`${label} must be a non-empty string`); return value; }
function safePath(value: string, root: string, label: string): string {
    const p = resolve(value);
    const r = relative(resolve(root), p).toLowerCase();
    if (isAbsolute(r) || r === ".." || r.startsWith(`..${sep}`) || r.startsWith("../"))
        throw new Error(`${label} must stay under ${root}`);
    return p;
}
function pos(value: unknown, label: string): Position { const p = obj(value, label); for (const a of ["x", "y", "z"] as const)
    if (typeof p[a] !== "number" || !Number.isFinite(p[a]))
        throw new Error(`${label}.${a} is invalid`); return { x: p.x as number, y: p.y as number, z: p.z as number }; }
function samePos(value: unknown, expected: Position): boolean { try {
    const p = pos(value, "position");
    return p.x === expected.x && p.y === expected.y && p.z === expected.z;
}
catch {
    return false;
} }
function resource(value: unknown): string | undefined { if (typeof value === "string")
    return value; if (value && typeof value === "object" && !Array.isArray(value)) {
    const p = value as JsonObject;
    if (typeof p.namespace === "string" && typeof p.path === "string")
        return `${p.namespace}:${p.path}`;
} return undefined; }
function payload(receipt: JsonObject): JsonObject | undefined { try {
    return receipt.payload === undefined ? undefined : obj(receipt.payload, "payload");
}
catch {
    return undefined;
} }
function receipt(body: Body | undefined, kind: string, test: (p: JsonObject) => boolean): JsonObject | undefined { return (body?.actionJournal ?? []).find(r => r.state === "COMPLETED" && payload(r)?.kind === kind && test(payload(r)!)); }
function receipts(bodyValue: Body | undefined, kind: string, test: (p: JsonObject) => boolean): JsonObject[] { return (bodyValue?.actionJournal ?? []).filter(r => r.state === "COMPLETED" && payload(r)?.kind === kind && test(payload(r)!)); }
function receiptId(value: JsonObject | undefined): string | undefined { const id = value?.id; if (typeof id === "string")
    return id; if (id && typeof id === "object" && !Array.isArray(id) && typeof (id as JsonObject).value === "string")
    return (id as JsonObject).value as string; return undefined; }
function rowData(row: JsonObject): JsonObject { return row.data && typeof row.data === "object" && !Array.isArray(row.data) ? row.data as JsonObject : row; }
function preparedKind(row: JsonObject): string | undefined {
    const fingerprint = rowData(row).fingerprint;
    if (typeof fingerprint !== "string") return undefined;
    try { const parsed = JSON.parse(fingerprint) as unknown; return parsed && typeof parsed === "object" && !Array.isArray(parsed) && typeof (parsed as JsonObject).kind === "string" ? (parsed as JsonObject).kind as string : undefined; }
    catch { return undefined; }
}
function terminalEventBody(row: JsonObject): JsonObject | undefined {
    const data = rowData(row);
    return row.type === "game.event" && data.event === "action.terminal" && data.body && typeof data.body === "object" && !Array.isArray(data.body) ? data.body as JsonObject : undefined;
}
function runningMine(bodyValue: Body | undefined, oak?: Position): JsonObject | undefined { const action = bodyValue?.action; if (!action || action.state !== "RUNNING")
    return undefined; const p = payload(action); const kind = typeof p?.kind === "string" ? p.kind : typeof action.kind === "string" ? action.kind : ""; return kind === "MINE" && (!oak || samePos(p?.position, oak)) ? action : undefined; }
function count(body: Body | undefined, item: string): number { return (body?.inventory ?? []).filter(s => s.item === item).reduce((n, s) => n + (typeof s.count === "number" && Number.isFinite(s.count) ? s.count : 0), 0); }
function body(view: WorldView | undefined, name: string): Body | undefined { const found = (view?.companions ?? []).filter(b => b.name === name); return found.length === 1 ? found[0] : undefined; }
function exists(path: string): Promise<boolean> { return stat(path).then(() => true, error => (error as NodeJS.ErrnoException).code === "ENOENT" ? false : Promise.reject(error)); }
async function json<T>(path: string): Promise<T | undefined> { try {
    return JSON.parse(await readFile(path, "utf8")) as T;
}
catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT" || e instanceof SyntaxError)
        return undefined;
    throw e;
} }
function redacted(value: unknown, depth = 0): unknown {
    if (depth > 5)
        return "<truncated>";
    if (typeof value === "string")
        return value.length > 600 ? `${value.slice(0, 600)}…` : value;
    if (Array.isArray(value))
        return value.slice(0, 200).map(v => redacted(v, depth + 1));
    if (!value || typeof value !== "object")
        return value;
    const result: JsonObject = {};
    for (const [key, val] of Object.entries(value as JsonObject))
        result[key] = /token|auth|password|secret|credential/i.test(key) ? "<redacted>" : redacted(val, depth + 1);
    return result;
}
async function writeJson(path: string, value: unknown): Promise<void> { await mkdir(resolve(path, ".."), { recursive: true }); await writeFile(path, JSON.stringify(redacted(value), null, 2), "utf8"); }
async function appendSnapshot(path: string, value: unknown): Promise<void> {
    const handle = await open(path, "a");
    try {
        await handle.writeFile(`${JSON.stringify(redacted(value))}\n`, "utf8");
        await handle.sync();
    }
    finally {
        await handle.close();
    }
}
async function waitFor<T>(read: () => Promise<T>, ready: (value: T) => boolean, timeoutMs: number, label: string): Promise<T> {
    const until = Date.now() + timeoutMs;
    let last: T | undefined;
    while (Date.now() < until) {
        last = await read();
        if (ready(last))
            return last;
        await new Promise(r => setTimeout(r, 1000));
    }
    throw new Error(`${label} timed out${last === undefined ? "" : `; last=${JSON.stringify(redacted(last)).slice(0, 1600)}`}`);
}
type Mcp = {
    call(method: string, params?: unknown, timeoutMs?: number): Promise<any>;
    close(): Promise<boolean>;
    forceClose(): void;
};
function mcp(child: ChildProcessWithoutNullStreams): Mcp {
    let next = 1;
    const pending = new Map<number, {
        resolve: (v: unknown) => void;
        reject: (e: unknown) => void;
        timer: NodeJS.Timeout;
    }>();
    const lines = createInterface({ input: child.stdout });
    lines.on("line", line => { try {
        const r = JSON.parse(line) as {
            id?: unknown;
            result?: unknown;
            error?: {
                message?: string;
            };
        };
        if (typeof r.id !== "number")
            return;
        const p = pending.get(r.id);
        if (!p)
            return;
        pending.delete(r.id);
        clearTimeout(p.timer);
        r.error ? p.reject(new Error(r.error.message ?? "MCP request failed")) : p.resolve(r.result);
    }
    catch { /* pending request timeout is the diagnostic */ } });
    child.on("exit", (code, signal) => { for (const p of pending.values()) {
        clearTimeout(p.timer);
        p.reject(new Error(`controller exited (${code ?? `signal ${signal}`})`));
    } pending.clear(); });
    const call = (method: string, params: unknown = {}, timeoutMs = 30000): Promise<any> => new Promise((resolveCall, reject) => { const id = next++; const timer = setTimeout(() => { pending.delete(id); reject(new Error(`MCP ${method} timed out`)); }, timeoutMs); pending.set(id, { resolve: resolveCall, reject, timer }); try {
        child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    }
    catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        reject(e);
    } });
    const close = async (): Promise<boolean> => { lines.close(); if (!child.stdin.destroyed)
        child.stdin.end(); await new Promise<void>(resolveClose => { if (child.exitCode !== null || child.signalCode !== null)
        return resolveClose(); const timer = setTimeout(resolveClose, 10000); child.once("exit", () => { clearTimeout(timer); resolveClose(); }); }); return child.exitCode === 0 && child.signalCode === null; };
    const forceClose = (): void => { if (child.exitCode !== null || child.signalCode !== null)
        return; if (process.platform === "win32")
        spawn("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
    else
        child.kill("SIGTERM"); };
    return { call, close, forceClose };
}
async function processTree(rootPid: number): Promise<ProcessInfo[]> {
    if (process.platform !== "win32")
        return [{ pid: rootPid, parentPid: 0, name: "controller", commandLine: "<owned controller>" }];
    const script = "$ErrorActionPreference='Stop'; Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CommandLine,CreationDate | ConvertTo-Json -Compress";
    const result = await execFile("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], { maxBuffer: 8 * 1024 * 1024 });
    const parsed: unknown = result.stdout.trim() ? JSON.parse(result.stdout) : [];
    const rows = Array.isArray(parsed) ? parsed : [parsed];
    const all: ProcessInfo[] = rows.flatMap(row => { if (!row || typeof row !== "object")
        return []; const r = row as JsonObject; const pid = Number(r.ProcessId); const parentPid = Number(r.ParentProcessId); if (!Number.isSafeInteger(pid) || pid < 1)
        return []; return [{ pid, parentPid: Number.isSafeInteger(parentPid) ? parentPid : 0, name: typeof r.Name === "string" ? r.Name : "", commandLine: typeof r.CommandLine === "string" ? r.CommandLine : "", creationDate: typeof r.CreationDate === "string" ? r.CreationDate : undefined }]; });
    const root = all.find(p => p.pid === rootPid);
    if (!root)
        return [];
    const selected = new Map<number, ProcessInfo>();
    selected.set(rootPid, root);
    let changed = true;
    while (changed) {
        changed = false;
        for (const p of all)
            if (!selected.has(p.pid) && selected.has(p.parentPid)) {
                selected.set(p.pid, p);
                changed = true;
            }
    }
    return [...selected.values()].sort((a, b) => a.pid - b.pid);
}
function processEvidence(tree: readonly ProcessInfo[]): unknown[] { return tree.map(p => ({ pid: p.pid, parentPid: p.parentPid, name: p.name, creationDate: p.creationDate, commandLine: p.commandLine.replace(/(token|auth|password|secret|credential)[^\s=]*[=\s]+[^\s]+/gi, "$1=<redacted>").slice(0, 500) })); }
function normalizeCommandLine(value: string): string { return value.trim().replace(/\s+/g, " ").toLowerCase(); }
function sameProcessIdentity(expected: ProcessInfo | undefined, actual: ProcessInfo | undefined): boolean {
    return !!expected && !!actual && expected.pid === actual.pid && expected.parentPid === actual.parentPid
        && expected.name.toLowerCase() === actual.name.toLowerCase()
        && normalizeCommandLine(expected.commandLine) === normalizeCommandLine(actual.commandLine)
        && (!!expected.creationDate ? expected.creationDate === actual.creationDate : true);
}
function isDescendant(tree: readonly ProcessInfo[], rootPid: number, childPid: number): boolean {
    const byPid = new Map(tree.map(process => [process.pid, process]));
    let current = byPid.get(childPid);
    const visited = new Set<number>();
    while (current && !visited.has(current.pid)) {
        if (current.parentPid === rootPid) return true;
        visited.add(current.pid); current = byPid.get(current.parentPid);
    }
    return false;
}
async function killTree(pid: number): Promise<void> { if (process.platform === "win32") {
    await execFile("taskkill", ["/PID", String(pid), "/T", "/F"], { maxBuffer: 1024 * 1024 });
}
else {
    process.kill(pid, "SIGKILL");
} }
async function pidAlive(pid: number): Promise<boolean> {
    if (process.platform !== "win32") {
        try {
            process.kill(pid, 0);
            return true;
        }
        catch {
            return false;
        }
    }
    try {
        const result = await execFile("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", `(Get-Process -Id ${pid} -ErrorAction SilentlyContinue) -ne $null`], { maxBuffer: 64 * 1024 });
        return result.stdout.trim().toLowerCase() === "true";
    }
    catch {
        return false;
    }
}
async function waitGone(pids: readonly number[], timeoutMs = 15000): Promise<void> { const until = Date.now() + timeoutMs; while (Date.now() < until) {
    const alive = await Promise.all(pids.map(pid => pidAlive(pid)));
    if (!alive.some(Boolean))
        return;
    await new Promise(r => setTimeout(r, 500));
} throw new Error(`owned process tree did not exit: ${pids.join(",")}`); }
async function lockPid(state: string): Promise<number | undefined> { const l = await json<{
    pid?: unknown;
}>(resolve(state, "controller.lock")); return typeof l?.pid === "number" && Number.isSafeInteger(l.pid) ? l.pid : undefined; }
async function main(): Promise<void> {
    const projectRoot = resolve(flag("--project-root", process.cwd()));
    const runId = flag("--run-id", "");
    if (!SAFE_RUN_ID.test(runId))
        throw new Error("provide a unique --run-id (1..48 letters, digits or hyphens)");
    const runtime = resolve(projectRoot, ".runtime");
    const runRoot = safePath(flag("--run-root", flag("--state", resolve(runtime, `p1-recovery-${runId}`))), runtime, "run root");
    const state = safePath(flag("--controller-state", resolve(runRoot, "controller")), runRoot, "controller state");
    const reportPath = safePath(flag("--report", resolve(runRoot, "report.json")), runRoot, "report");
    const snapshotsPath = safePath(resolve(runRoot, "fault-snapshots.jsonl"), runRoot, "snapshot log");
    const saveRoot = resolve(projectRoot, "mod", "run", "client", "saves");
    const save = safePath(flag("--fixture-save", resolve(saveRoot, `${SAVE_PREFIX}${runId}`)), saveRoot, "fixture save");
    if (save.toLowerCase() !== resolve(saveRoot, `${SAVE_PREFIX}${runId}`).toLowerCase())
        throw new Error("fixture save must be exactly the P1Model run directory");
    const fixturePath = resolve(save, `hearthcrew-p1-model-fixture-${runId}.json`);
    const fixtureReportPath = resolve(save, `hearthcrew-p1-model-report-${runId}.json`);
    const codexRoot = resolve(flag("--codex-runtime", resolve(process.env.LOCALAPPDATA ?? resolve(projectRoot, "AppData", "Local"), "HearthCrew", "codex-runtime")));
    const workspace = resolve(flag("--workspace", resolve(process.env.LOCALAPPDATA ?? resolve(projectRoot, "AppData", "Local"), "HearthCrew", "workspace")));
    const codexCommand = flag("--codex-command", "codex");
    const modelCatalog = flag("--model-catalog", "");
    const play = resolve(flag("--play-runtime", resolve(projectRoot, "bridge", "dist", "bridge", "src", "play-runtime.js")));
    const fault = flag("--fault", "controller") as FaultKind;
    if (fault !== "controller" && fault !== "app-server")
        throw new Error("--fault must be controller or app-server");
    const timeoutMs = positiveFlag("--timeout-seconds", 900) * 1000;
    if (await exists(reportPath))
        throw new Error(`report already exists; unique-run guard refused overwrite: ${reportPath}`);
    await mkdir(runRoot, { recursive: true });
    await mkdir(state, { recursive: true });
    const report: JsonObject = { protocol: "hearthcrew.v1", test: "P1_RECOVERY_PROBE", evidence: "CONTROLLED_REAL_CLIENT_FAULT", status: "FAIL", runId, fault, startedAt: new Date().toISOString(), reportPath, snapshotsPath };
    let child: ChildProcessWithoutNullStreams | undefined;
    let client: Mcp | undefined;
    let oldControllerPid: number | undefined;
    let snapshotCount = 0;
    let lastStatus: DesktopStatus | undefined;
    let worldId = "";
    let workerId = "";
    let recipientId = "";
    let workerEntity = "";
    let recipientEntity = "";
    let workerGeneration = 0;
    let recipientGeneration = 0;
    let oak: Position | undefined;
    let injected = false;
    const tool = async (name: string, args: JsonObject = {}, timeout = 30000): Promise<any> => { if (!client)
        throw new Error("MCP client unavailable"); const result = await client.call("tools/call", { name, arguments: args }, timeout); if (result?.isError)
        throw new Error(result.content?.[0]?.text ?? `${name} failed`); return result?.structuredContent; };
    const status = async (): Promise<DesktopStatus> => tool("status", {}, 20000) as Promise<DesktopStatus>;
    const events = async (): Promise<JsonObject[]> => { const rows: JsonObject[] = []; let after = 0; for (let page = 0; page < 100; page++) {
        const result = await tool("events", { after, limit: 100 }, 20000);
        const list = Array.isArray(result?.events) ? result.events.filter((v: unknown): v is JsonObject => !!v && typeof v === "object" && !Array.isArray(v)) : [];
        rows.push(...list);
        const previous = after;
        const next = typeof result?.nextCursor === "number" ? result.nextCursor : undefined;
        if (!list.length || next === undefined || next <= previous || list.length < 100)
            break;
        after = next;
    } return rows; };
    const snap = async (phase: string, current?: DesktopStatus, note?: unknown, tree?: ProcessInfo[]): Promise<void> => { snapshotCount++; await appendSnapshot(snapshotsPath, { phase, at: new Date().toISOString(), gameTick: current?.game?.gameTick, status: current, note, processTree: tree ? processEvidence(tree) : undefined }); };
    const start = async (previousPid?: number): Promise<void> => {
        child = spawn(process.execPath, [play, "--state", state, "--runtime", codexRoot, "--workspace", workspace, "--codex-command", codexCommand, "--trace-model", ...(modelCatalog ? ["--model-catalog", resolve(modelCatalog)] : [])], { cwd: projectRoot, stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
        oldControllerPid = child.pid;
        child.stderr.on("data", chunk => process.stderr.write(chunk));
        client = mcp(child);
        await client.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "hearthcrew-p1-recovery-probe", version: "0.1.0-dev" } });
        child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} })}\n`);
        await client.call("tools/list", {}, 20000);
        await waitFor(async () => lockPid(state), pid => pid !== undefined && pid === oldControllerPid && (previousPid === undefined || pid !== previousPid), 30000, "controller lock");
        await waitFor<DesktopStatus>(async () => client ? status() : {}, value => !!value.game?.worldId && Array.isArray(value.game.companions), 180000, "world connection");
    };
    const closeOwned = async (): Promise<boolean> => { if (!client)
        return true; const c = client; client = undefined;
        const owned = child && child.pid && child.exitCode === null && child.signalCode === null ? await processTree(child.pid).catch(() => []) : [];
        const ok = await c.close().catch(() => false);
        if (!ok && child && child.exitCode === null && child.signalCode === null) {
            c.forceClose();
        }
        if (owned.length) {
            try { await waitGone(owned.map(pinfo => pinfo.pid)); }
            catch { return false; }
        }
        return ok;
    };
    try {
        await start();
        lastStatus = await waitFor(status, value => !!value.game?.worldId && !!body(value.game, `P1_WORKER_${runId}`) && !!body(value.game, `P1_RECIPIENT_${runId}`), 180000, "P1 fixture");
        const metadata = await waitFor(() => json<FixtureMetadata>(fixturePath), value => value?.runId === runId && value.saveName === `${SAVE_PREFIX}${runId}`, 30000, "fixture metadata");
        if (!metadata)
            throw new Error("fixture metadata unavailable");
        worldId = text(lastStatus.game?.worldId, "worldId");
        if (metadata.worldId !== worldId || metadata.evidence !== "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE")
            throw new Error("fixture metadata identity/evidence mismatch");
        oak = pos(metadata.oakPosition, "oakPosition");
        const w = body(lastStatus.game, `P1_WORKER_${runId}`);
        const r = body(lastStatus.game, `P1_RECIPIENT_${runId}`);
        if (!w || !r)
            throw new Error("fixture bodies unavailable");
        if ((w.inventory ?? []).length !== 0 || (r.inventory ?? []).length !== 0)
            throw new Error("fixture did not begin with empty worker and recipient inventories");
        workerId = text(w.botId, "worker logical UUID");
        recipientId = text(r.botId, "recipient logical UUID");
        workerEntity = text(w.entityId, "worker entity UUID");
        recipientEntity = text(r.entityId, "recipient entity UUID");
        if (!Number.isSafeInteger(w.bodyGeneration) || !Number.isSafeInteger(r.bodyGeneration))
            throw new Error("fixture body generation is invalid");
        workerGeneration = w.bodyGeneration as number;
        recipientGeneration = r.bodyGeneration as number;
        if (metadata.worker?.logicalUuid !== workerId || metadata.worker.entityUuid !== workerEntity || metadata.recipient?.logicalUuid !== recipientId || metadata.recipient.entityUuid !== recipientEntity)
            throw new Error("fixture/body identity mismatch");
        report.initial = { worldId, worker: w, recipient: r, metadata, configuredDynamicTools: ((lastStatus.appServer as JsonObject | undefined)?.toolNames ?? undefined) };
        await snap("ready", lastStatus, { worldId, workerId, recipientId, oak });
        const rawConfigured: unknown = lastStatus.appServer && typeof lastStatus.appServer === "object" ? (lastStatus.appServer as JsonObject).toolNames : undefined;
        const configured: string[] = Array.isArray(rawConfigured) ? rawConfigured.filter((name: unknown): name is string => typeof name === "string") : [];
        if (REQUIRED_TOOLS.some(name => !configured.includes(name)))
            throw new Error("configured dynamic game tool surface is incomplete");
        // Enumerate before submitting gameplay so WMI latency does not consume
        // the short RUNNING interval. This exact ChildProcess remains alive
        // and owns the same App Server throughout the injection checkpoint.
        const ownedTree = await processTree(oldControllerPid!);
        if (!ownedTree.length) throw new Error("owned controller process tree unavailable before command");
        const command = await tool("command", { botId: workerId, message: `这是一次受控 P1 真实模型恢复任务。请完成：采集坐标 ${oak.x},${oak.y},${oak.z} 的唯一橡木，制作一次 minecraft:oak_planks，然后把恰好 4 个橡木木板交给名为 P1_RECIPIENT_${runId} 的伙伴。只使用游戏工具并依据实际回执确认。` }, timeoutMs);
        if (!command?.accepted)
            throw new Error(`owner command was not accepted: ${JSON.stringify(redacted(command))}`);
        report.command = command;
        const deadline = Date.now() + timeoutMs;
        let idleSinceTick: number | undefined;
        while (!injected && Date.now() < deadline) {
            lastStatus = await status();
            const current = body(lastStatus.game, `P1_WORKER_${runId}`);
            const fixtureBeforeFault = await json<FixtureReport>(fixtureReportPath);
            if (fixtureBeforeFault?.status === "FAIL")
                throw new Error(`fixture failed before fault injection: ${fixtureBeforeFault.failure}`);
            if (!current || lastStatus.game?.worldId !== worldId || current.botId !== workerId || current.entityId !== workerEntity || current.bodyGeneration !== workerGeneration)
                throw new Error("fixture identity or body generation changed before fault");
            if ((current.actionJournal ?? []).some(r => ["COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "RECONCILE_REQUIRED"].includes(String(r.state)) && payload(r)?.kind === "MINE"))
                throw new Error("fault window missed: MINE completed before process termination; rerun a fresh fixture");
            if (lastStatus.roles?.some(role => role.botId === workerId && role.status === "failed"))
                throw new Error("model role failed before fault injection");
            const workerRole = lastStatus.roles?.find(role => role.botId === workerId);
            if (workerRole?.status === "idle" && !current.action && typeof lastStatus.game?.gameTick === "number") {
                idleSinceTick ??= lastStatus.game.gameTick;
                if (lastStatus.game.gameTick - idleSinceTick > 300)
                    throw new Error("worker idle without an action for over 300 game ticks before injection");
            } else idleSinceTick = undefined;
            const action = runningMine(current, oak);
            const p = action ? payload(action) : undefined;
            const kind = typeof p?.kind === "string" ? p.kind : typeof action?.kind === "string" ? action.kind : "";
            const stateName = typeof action?.state === "string" ? action.state : "";
            if (action && kind === "MINE" && stateName === "RUNNING") {
                const tree = ownedTree;
                if (!child || child.pid !== oldControllerPid || child.exitCode !== null || child.signalCode !== null)
                    throw new Error("owned controller exited before fault checkpoint");
                const confirmedStatus = await status();
                const confirmedWorker = body(confirmedStatus.game, `P1_WORKER_${runId}`);
                const confirmedAction = runningMine(confirmedWorker, oak);
                if (!confirmedAction) {
                    await new Promise(rdelay => setTimeout(rdelay, 250));
                    continue;
                }
                if (receiptId(confirmedAction) !== receiptId(action))
                    throw new Error("active MINE identity changed before fault injection");
                await snap("mine-running-checkpoint", confirmedStatus, { action: redacted(confirmedAction), checkpoint: { actionId: confirmedAction.id, kind, state: stateName }, fault }, tree);
                // WMI was deliberately kept out of the polling loop. Take a
                // fresh ownership snapshot only after the second status CAS,
                // immediately before kill, so a stale PID cannot be reused.
                const freshTree = await processTree(oldControllerPid!);
                const freshRoot = freshTree.find(pinfo => pinfo.pid === oldControllerPid);
                const oldRoot = tree.find(pinfo => pinfo.pid === oldControllerPid);
                if (!freshRoot || !oldRoot || !sameProcessIdentity(oldRoot, freshRoot) || !(await pidAlive(oldControllerPid!)))
                    throw new Error("controller process identity changed before fault injection");
                const rootTree = freshTree;
                const captureKillCheckpoint = async (): Promise<void> => {
                    // Process discovery may take long enough for MINE to end.
                    // Only the observation after those checks is the kill checkpoint.
                    const latest = await status();
                    const latestWorker = body(latest.game, `P1_WORKER_${runId}`);
                    const latestMine = runningMine(latestWorker, oak!);
                    if (latest.game?.worldId !== worldId || latestWorker?.botId !== workerId
                        || latestWorker.entityId !== workerEntity || latestWorker.bodyGeneration !== workerGeneration
                        || !latestMine || receiptId(latestMine) !== receiptId(confirmedAction))
                        throw new Error("fault window missed during process ownership checks; no process was killed");
                    report.faultTrigger = { gameTick: latest.game.gameTick, action: latestMine, controllerPid: oldControllerPid, processTree: processEvidence(freshTree) };
                    await snap("immediate-kill-checkpoint", latest, { action: latestMine, fault }, freshTree);
                };
                if (fault === "app-server") {
                    const candidate = rootTree.filter(pinfo => pinfo.pid !== oldControllerPid && /app-server/i.test(pinfo.commandLine));
                    if (candidate.length !== 1)
                        throw new Error(`could not identify exactly one App Server descendant (${candidate.length})`);
                    const app = candidate[0];
                    if (!isDescendant(rootTree, oldControllerPid!, app.pid) || !(await pidAlive(app.pid)))
                        throw new Error("App Server is not a live descendant of the owned controller");
                    const appIds = new Set<number>([app.pid]);
                    let changed = true;
                    while (changed) {
                        changed = false;
                        for (const pinfo of rootTree)
                            if (!appIds.has(pinfo.pid) && appIds.has(pinfo.parentPid)) {
                                appIds.add(pinfo.pid);
                                changed = true;
                            }
                    }
                    const appTree = rootTree.filter(pinfo => appIds.has(pinfo.pid));
                    report.appServerProcess = { pid: app.pid, tree: processEvidence(appTree) };
                    await captureKillCheckpoint();
                    await killTree(app.pid);
                    await waitGone(appTree.map(pinfo => pinfo.pid));
                    const afterApp = await waitFor(status, value => (value.appServer as JsonObject | undefined)?.appServer === "stopped", 15000, "App Server stopped diagnostic");
                    const afterWorker = body(afterApp.game, `P1_WORKER_${runId}`);
                    const afterRecipient = body(afterApp.game, `P1_RECIPIENT_${runId}`);
                    if (afterApp.game?.worldId !== worldId || afterWorker?.botId !== workerId || afterWorker?.entityId !== workerEntity || afterRecipient?.botId !== recipientId || afterRecipient?.entityId !== recipientEntity)
                        throw new Error("App Server fault changed the live world/body identity");
                    report.appServerStopped = { observed: true, worldId, worker: afterWorker, recipient: afterRecipient, action: afterWorker?.action };
                    await snap("app-server-stopped-controller-alive", afterApp, { appServer: afterApp.appServer, sameBodies: true, action: afterWorker?.action }, rootTree);
                }
                else {
                    await captureKillCheckpoint();
                    await killTree(oldControllerPid!);
                    await waitGone(rootTree.map(pinfo => pinfo.pid));
                }
                injected = true;
                await snap("fault-injected", undefined, { fault, killedControllerPid: fault === "controller" ? oldControllerPid : undefined, appServer: report.appServerProcess, processTreeBefore: processEvidence(rootTree) });
                const beforePid = oldControllerPid;
                const closed = await closeOwned();
                report.controllerBeforeRestartExit = { graceful: closed, pid: beforePid };
                if (beforePid !== undefined) await waitGone(rootTree.map(pinfo => pinfo.pid));
                await start(beforePid);
                const restartedControllerPid = oldControllerPid;
                const restartedTree = restartedControllerPid === undefined ? [] : await processTree(restartedControllerPid);
                const restartedRoot = restartedTree.find(pinfo => pinfo.pid === restartedControllerPid);
                const restartedLock = restartedControllerPid === undefined ? undefined : await lockPid(state);
                if (restartedControllerPid === undefined || !restartedRoot || restartedLock !== restartedControllerPid)
                    throw new Error("restarted controller identity or lock does not match");
                const restartedApps = restartedTree.filter(pinfo => pinfo.pid !== restartedControllerPid && /app-server/i.test(pinfo.commandLine));
                if (restartedApps.length !== 1 || !isDescendant(restartedTree, restartedControllerPid, restartedApps[0].pid) || !(await pidAlive(restartedApps[0].pid)))
                    throw new Error(`restarted controller must own exactly one live App Server descendant (${restartedApps.length})`);
                const oldPids = new Set(rootTree.map(pinfo => pinfo.pid));
                if (fault === "app-server" && report.appServerProcess && typeof (report.appServerProcess as JsonObject).pid === "number"
                    && restartedApps[0].pid === (report.appServerProcess as JsonObject).pid)
                    throw new Error("recovery reused the killed App Server PID");
                report.restartedController = { pid: restartedControllerPid, lockPid: restartedLock, appServer: processEvidence(restartedApps), oldTreePids: [...oldPids] };
                break;
            }
            await snap("waiting-mine", lastStatus, { kind, state: stateName, gameTick: lastStatus.game?.gameTick });
            await new Promise(rdelay => setTimeout(rdelay, 200));
        }
        if (!injected)
            throw new Error("MINE never reached RUNNING before timeout");
        const recoveryDeadline = Date.now() + timeoutMs;
        let final: DesktopStatus | undefined;
        let finalFixture: FixtureReport | undefined;
        let terminalEvents: JsonObject[] = [];
        let recoveredDone = false;
        let recoveryIdleSinceTick: number | undefined;
        while (Date.now() < recoveryDeadline) {
            final = await status();
            lastStatus = final;
            const currentWorker = body(final.game, `P1_WORKER_${runId}`);
            const currentRecipient = body(final.game, `P1_RECIPIENT_${runId}`);
            if (!currentWorker || !currentRecipient || final.game?.worldId !== worldId || currentWorker.botId !== workerId || currentWorker.entityId !== workerEntity || currentRecipient.botId !== recipientId || currentRecipient.entityId !== recipientEntity || currentWorker.bodyGeneration !== workerGeneration || currentRecipient.bodyGeneration !== recipientGeneration)
                throw new Error("fixture world/body identity or generation changed after recovery");
            finalFixture = await json<FixtureReport>(fixtureReportPath);
            if (finalFixture?.status === "FAIL")
                throw new Error(`fixture failed: ${finalFixture.failure ?? "unspecified"}`);
            const recoveredRole = final.roles?.find(role => role.botId === workerId);
            if (recoveredRole?.status === "idle" && !currentWorker.action && typeof final.game?.gameTick === "number") {
                recoveryIdleSinceTick ??= final.game.gameTick;
                if (final.game.gameTick - recoveryIdleSinceTick > 300)
                    throw new Error("worker idle without an action for over 300 game ticks during recovery");
            }
            else recoveryIdleSinceTick = undefined;
            terminalEvents = await events();
            const mine = receipt(currentWorker, "MINE", p => samePos(p.position, oak!));
            const craft = receipt(currentWorker, "CRAFT", p => p.count === 1 && resource(p.resource) === OAK);
            const transfer = receipt(currentWorker, "TRANSFER", p => p.count === 4 && resource(p.resource) === OAK && p.target === recipientEntity);
            const done = finalFixture?.status === "PASS" && finalFixture.oakBlockAir === true
                && finalFixture.recipientOakPlanks === 4 && finalFixture.workerWoodOrPlanks === 0
                && count(currentRecipient, OAK) === 4
                && count(currentWorker, OAK) + count(currentWorker, "minecraft:oak_log") === 0
                && mine && craft && transfer;
            await snap("recovery-progress", final, { fixture: finalFixture, matched: { mine: !!mine, craft: !!craft, transfer: !!transfer }, eventCount: terminalEvents.length });
            if (done) {
                recoveredDone = true;
                report.final = { status: final, fixture: finalFixture, matchedReceipts: { mine, craft, transfer } };
                break;
            }
            if (recoveredRole?.status === "failed" && !currentWorker.action)
                throw new Error("model role failed during recovery after healthy body work ended");
            await new Promise(rdelay => setTimeout(rdelay, 1000));
        }
        if (!recoveredDone || !final || !finalFixture || finalFixture.status !== "PASS")
            throw new Error("recovered P1 task did not reach all fixture and terminal action postconditions");
        terminalEvents = await events();
        const prepared = new Map<string, number>();
        const eventIds = new Set<string>();
        const duplicatePrepared: string[] = [];
        const duplicateEvents: string[] = [];
        for (const row of terminalEvents) {
            const d = row.data && typeof row.data === "object" ? row.data as JsonObject : row;
            const eventId = typeof d.eventId === "string" ? d.eventId : typeof row.eventId === "string" ? row.eventId as string : undefined;
            if (eventId && eventIds.has(eventId))
                duplicateEvents.push(eventId);
            if (eventId)
                eventIds.add(eventId);
            if (row.type === "action.prepared") {
                const key = `${d.worldId ?? worldId}:${d.botId ?? workerId}:${d.actionId ?? d.id ?? ""}`;
                const n = (prepared.get(key) ?? 0) + 1;
                prepared.set(key, n);
                if (n > 1)
                    duplicatePrepared.push(key);
            }
        }
        const reconciled = terminalEvents.filter(row => row.type === "world.reconciled");
        const epochValues = reconciled.map(row => { const d = row.data && typeof row.data === "object" ? row.data as JsonObject : row; return d.sessionEpoch; }).filter((v): v is number => typeof v === "number" && Number.isSafeInteger(v));
        const epochs = new Set(epochValues);
        const finalWorker = body(final?.game, `P1_WORKER_${runId}`);
        const mineReceipts = receipts(finalWorker, "MINE", p => samePos(p.position, oak!));
        const craftReceipts = receipts(finalWorker, "CRAFT", p => p.count === 1 && resource(p.resource) === OAK);
        const transferReceipts = receipts(finalWorker, "TRANSFER", p => p.count === 4 && resource(p.resource) === OAK && p.target === recipientEntity);
        const terminal = { mine: mineReceipts[0], craft: craftReceipts[0], transfer: transferReceipts[0] };
        const terminalIds = Object.values(terminal).map(value => receiptId(value)).filter((id): id is string => !!id);
        const terminalIdSet = new Set(terminalIds);
        const unresolved = (finalWorker?.actionJournal ?? []).filter(row => row.state === "RECONCILE_REQUIRED").map(row => receiptId(row) ?? "unknown");
        const expectedKinds = ["MINE", "CRAFT", "TRANSFER"] as const;
        const semanticPrepared = terminalEvents.filter(row => row.type === "action.prepared" && rowData(row).botId === workerId && expectedKinds.includes(preparedKind(row) as typeof expectedKinds[number]));
        const preparedByKind = new Map<string, JsonObject[]>();
        for (const row of semanticPrepared) { const kind = preparedKind(row)!; preparedByKind.set(kind, [...(preparedByKind.get(kind) ?? []), row]); }
        const semanticJournalIds = new Map<string, Set<string>>();
        for (const row of finalWorker?.actionJournal ?? []) {
            const kind = payload(row)?.kind;
            const id = receiptId(row);
            if (typeof kind === "string" && expectedKinds.includes(kind as typeof expectedKinds[number]) && id)
                semanticJournalIds.set(kind, new Set([...(semanticJournalIds.get(kind) ?? []), id]));
        }
        const expectedActionIds = new Map<string, string>();
        for (const kind of expectedKinds) {
            const rows = preparedByKind.get(kind) ?? [];
            const ids = semanticJournalIds.get(kind) ?? new Set<string>();
            if (rows.length !== 1 || ids.size !== 1) throw new Error(`expected exactly one ${kind} submission and action identity; prepared=${rows.length}, journalIds=${ids.size}`);
            const preparedData = rowData(rows[0]);
            const preparedId = typeof preparedData.actionId === "string" ? preparedData.actionId : receiptId(preparedData);
            const journalId = [...ids][0];
            if (!preparedId || preparedId !== journalId) throw new Error(`${kind} prepared/action journal identity mismatch`);
            if (preparedData.intentId !== command.intentId || !journalId.startsWith(`${command.intentId}:`))
                throw new Error(`${kind} no longer belongs to the original owner intent`);
            expectedActionIds.set(kind, journalId);
        }
        const terminalBodies = terminalEvents.map(terminalEventBody).filter((value): value is JsonObject => !!value);
        const terminalEventEvidence: Record<string, string> = {};
        for (const kind of expectedKinds) {
            const actionId = expectedActionIds.get(kind)!;
            const matches = terminalBodies.filter(value => value.botId === workerId && receiptId(value) === actionId);
            if (matches.length !== 1) throw new Error(`${kind} requires exactly one matching action.terminal event; count=${matches.length}`);
            if (matches[0].state !== "COMPLETED" || payload(matches[0])?.kind !== kind)
                throw new Error(`${kind} terminal event is not the matching COMPLETED action`);
            const epoch = matches[0].epoch;
            const eventGeneration = epoch && typeof epoch === "object" && !Array.isArray(epoch) ? (epoch as JsonObject).bodyGeneration : undefined;
            if (eventGeneration !== workerGeneration) throw new Error(`${kind} action.terminal body generation mismatch`);
            terminalEventEvidence[kind] = actionId;
        }
        const otherPreparedKinds = terminalEvents
            .filter(row => row.type === "action.prepared" && rowData(row).botId === workerId)
            .map(preparedKind).filter((kind): kind is string => !!kind && !expectedKinds.includes(kind as typeof expectedKinds[number]));
        if (reconciled.length < 2 || epochs.size < 2 || epochValues.some((value, index) => index > 0 && value <= epochValues[index - 1]))
            throw new Error(`recovery evidence requires two world.reconciled events with distinct sessionEpoch values; count=${reconciled.length}, epochs=${epochs.size}`);
        if (duplicatePrepared.length || duplicateEvents.length)
            throw new Error(`duplicate durable evidence: action.prepared=${duplicatePrepared.join(",")}, eventId=${duplicateEvents.join(",")}`);
        if (mineReceipts.length !== 1 || craftReceipts.length !== 1 || transferReceipts.length !== 1 || terminalIds.length !== 3 || terminalIdSet.size !== 3 || terminalIds.some(id => !prepared.has(`${worldId}:${workerId}:${id}`)) || unresolved.length)
            throw new Error(`terminal action ledger is incomplete or unresolved; mine=${mineReceipts.length}, craft=${craftReceipts.length}, transfer=${transferReceipts.length}, terminalIds=${terminalIds.join(",")}, unresolved=${unresolved.join(",")}`);
        const finalRoles = final?.roles ?? [];
        const role = finalRoles.find(candidate => candidate.botId === workerId);
        if (role?.model !== "gpt-5.6-luna" || role?.reasoningEffort !== "high")
            throw new Error("recovered role did not confirm gpt-5.6-luna/high");
        if (role.intentId !== command.intentId || terminalEvents.filter(row => row.type === "owner.command").length !== 1)
            throw new Error("recovery did not retain the original single owner goal");
        if (Array.isArray(final.restoration) && final.restoration.length)
            throw new Error("recovery still has unresolved goal restoration");
        report.recovery = { worldId, sameBodies: true, sessionEpochs: [...epochs], worldReconciledCount: reconciled.length, eventCount: terminalEvents.length, terminalActionIds: terminalIds, expectedActionIds: Object.fromEntries(expectedActionIds), terminalEventEvidence, otherPreparedKinds, unresolvedReconciliation: unresolved, duplicatePrepared, duplicateEventIds: duplicateEvents, events: terminalEvents };
        report.status = "PASS";
    }
    catch (error) {
        report.failure = error instanceof Error ? error.message : String(error);
        report.lastStatus = lastStatus;
        process.exitCode = 1;
    }
    finally {
        report.snapshotCount = snapshotCount;
        report.finishedAt = new Date().toISOString();
        try {
            if (client) {
                const clean = await closeOwned();
                if (!clean) {
                    report.controllerCleanup = "did_not_exit_cleanly";
                    report.status = "FAIL";
                    report.failure ??= "controller did not exit cleanly";
                    process.exitCode = 1;
                }
            }
        }
        catch (e) {
            report.controllerCleanup = "error";
            report.cleanupError = e instanceof Error ? e.message : String(e);
            report.status = "FAIL";
            process.exitCode = 1;
        }
        await writeJson(reportPath, report);
        process.stdout.write(`${JSON.stringify({ status: report.status, runId, report: reportPath, failure: report.failure })}\n`);
    }
}
main().catch(async (error) => { const runId = flag("--run-id", "unknown"); if (SAFE_RUN_ID.test(runId)) {
    try {
        const root = resolve(flag("--project-root", process.cwd()), ".runtime");
        const run = safePath(flag("--run-root", flag("--state", resolve(root, `p1-recovery-${runId}`))), root, "run root");
        const report = safePath(flag("--report", resolve(run, "report.json")), run, "report");
        if (!(await exists(report)))
            await writeJson(report, { protocol: "hearthcrew.v1", test: "P1_RECOVERY_PROBE", evidence: "CONTROLLED_REAL_CLIENT_FAULT", status: "FAIL", runId, failure: error instanceof Error ? error.message : String(error), finishedAt: new Date().toISOString() });
    }
    catch { /* fail closed on invalid fallback paths */ }
} process.stderr.write(`p1 recovery probe failed: ${error instanceof Error ? error.message : String(error)}\n`); process.exitCode = 1; });
