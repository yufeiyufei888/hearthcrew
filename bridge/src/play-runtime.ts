import { randomBytes, randomUUID } from "node:crypto";
import { appendFile, mkdir, open, readFile, unlink, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { AppServerClient } from "./app-server-client.js";
import { CrewController } from "./crew-controller.js";
import { CrewJournal } from "./crew-journal.js";
import { DesktopMcp, serveStdioMcp } from "./desktop-mcp.js";
import { ModBridgeServer } from "./mod-bridge-server.js";
import { executionRuntime } from "./execution-runtime.js";

function argument(name: string, fallback: string): string {
  const index = process.argv.indexOf(name); return index < 0 ? fallback : process.argv[index + 1] ?? fallback;
}
async function claimLock(path: string): Promise<() => Promise<void>> {
  try {
    const old = JSON.parse(await readFile(path, "utf8")) as { pid?: number };
    if (!Number.isSafeInteger(old.pid) || !old.pid || old.pid < 1) throw new Error("invalid controller lock; recovery required");
    let running = true;
    try { process.kill(old.pid, 0); } catch (error) { if ((error as NodeJS.ErrnoException).code === "ESRCH") running = false; }
    if (running) throw new Error("another HearthCrew controller holds this runtime directory");
    await unlink(path); // Only our exact stale lock, never another process or a directory.
  } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
  const file = await open(path, "wx");
  try { await file.writeFile(JSON.stringify({ pid: process.pid })); await file.sync(); } finally { await file.close(); }
  return async () => {
    const current = JSON.parse(await readFile(path, "utf8")) as { pid?: number };
    if (current.pid === process.pid) await unlink(path);
  };
}

interface RuntimeMetadata {
  readonly schema: 1;
  readonly mode: "standalone" | "stdio";
  readonly pid: number;
  readonly state: string;
  readonly codexRuntime: string;
  readonly workspace: string;
  readonly codexCommand: string;
  readonly startedAt: string;
  readonly executionBackend: string;
  readonly executionProtocol: number;
  readonly bridgePort?: number;
  readonly status: "starting" | "ready" | "stopping" | "stopped";
  readonly finishedAt?: string;
}

async function writeRuntimeMetadata(path: string, metadata: RuntimeMetadata): Promise<void> {
  // This file is deliberately descriptive only: pairing tokens, credentials,
  // model traces and private journals never appear in launcher metadata.
  await writeFile(path, JSON.stringify(metadata, null, 2) + "\n", { encoding: "utf8" });
}

async function waitForStandaloneStop(stopFile: string): Promise<void> {
  await new Promise<void>(resolveStop => {
    let stopping = false;
    const finish = () => {
      if (stopping) return;
      stopping = true;
      clearInterval(poll);
      process.off("SIGINT", finish);
      process.off("SIGTERM", finish);
      resolveStop();
    };
    const poll = setInterval(() => {
      void readFile(stopFile, "utf8").then(() => finish()).catch(error => {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") finish();
      });
    }, 500);
    process.once("SIGINT", finish);
    process.once("SIGTERM", finish);
    // Do not keep the process alive solely because of this timer after the
    // signal handlers have been removed during shutdown.
    poll.unref();
  });
}

async function main(): Promise<void> {
  const executionMode=argument("--execution-backend","legacy"),executionOptions=executionRuntime(executionMode);
  const base = join(process.env.LOCALAPPDATA ?? join(homedir(), "AppData", "Local"), "HearthCrew");
  const state = resolve(argument("--state", join(base, "runtime")));
  const standalone = process.argv.includes("--standalone");
  const stopFile = resolve(argument("--stop-file", join(state, "stop.request")));
  await mkdir(state, { recursive: true });
  const release = await claimLock(join(state, "controller.lock"));
  const metadataPath = join(state, "runtime-metadata.json");
  const startedAt = new Date().toISOString();
  const metadataBase = { schema: 1 as const, mode: standalone ? "standalone" as const : "stdio" as const,
    pid: process.pid, state, codexRuntime: resolve(argument("--runtime", join(base, "codex-runtime"))),
    workspace: resolve(argument("--workspace", join(base, "workspace"))), codexCommand: argument("--codex-command", "codex"), startedAt,
    executionBackend:executionMode,executionProtocol:executionMode==="numen"?6:5 };
  await writeRuntimeMetadata(metadataPath, { ...metadataBase, status: "starting" });
  // A previous stop request belongs to a previous process.  It is removed only
  // after this process has acquired the exact runtime lock.
  try { await unlink(stopFile); } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
  const controller = new CrewController(new CrewJournal(join(state, "crew-events.jsonl")));
  let app: AppServerClient | undefined;
  let mod: ModBridgeServer | undefined;
  let diagnosticTimer: NodeJS.Timeout | undefined;
  let diagnosticTail: Promise<void> = Promise.resolve();
  let closeMcp: (() => void) | undefined;
  let traceTail: Promise<void> = Promise.resolve();
  try {
    await controller.initialize();
    try {controller.configureRecovery(JSON.parse(await readFile(join(state,"recovery-requests.json"),"utf8")));}catch(error){if((error as NodeJS.ErrnoException).code!=="ENOENT")throw error;}
    diagnosticTimer=setInterval(()=>{diagnosticTail=diagnosticTail.then(()=>writeFile(join(state,"live-diagnostics.json"),JSON.stringify(controller.diagnosticSnapshot(),null,2),"utf8")).catch(error=>{process.stderr.write(`HearthCrew diagnostic write failed: ${String(error)}\n`);});},5000);diagnosticTimer.unref();
    const tracePath = process.argv.includes("--trace-model") ? join(state, `model-tool-trace-${randomUUID()}.jsonl`) : undefined;
    if (tracePath) await writeFile(tracePath, JSON.stringify({ evidence: "CONTROLLED_MODEL_TOOL_TRACE", startedAt: new Date().toISOString() }) + "\n", { flag: "wx" });
    const modelCatalog = argument("--model-catalog", "");
    app = new AppServerClient({ runtime: { codexHome: metadataBase.codexRuntime,
      workspace: metadataBase.workspace, codexCommand: metadataBase.codexCommand, ...(modelCatalog ? { modelCatalogJson: resolve(modelCatalog) } : {}) }, diagnosticRawEvents: tracePath !== undefined, dynamicToolHandler: params => controller.toolCall(params) });
    app.on("notification", notification => {
      const item = notification.params?.item;
      const toolItem = notification.method === "rawResponseItem/completed" && ["custom_tool_call", "custom_tool_call_output", "function_call", "function_call_output"].includes(item?.type);
      const terminalText = notification.method === "item/completed" && item?.type === "agentMessage";
      if (tracePath && (toolItem || terminalText || ["turn/started", "turn/completed"].includes(notification.method))) {
        const line = JSON.stringify({ receivedAt: new Date().toISOString(), ...notification }) + "\n";
        // Deliberately exclude reasoning, account details, and authentication events.
        traceTail = traceTail.then(() => appendFile(tracePath, line, "utf8")).catch(() => { process.exitCode = 1; process.stderr.write("HearthCrew: test model trace write failed.\n"); });
      }
      if (notification.method === "error") {
        const error = notification.params?.error;
        process.stderr.write(`HearthCrew App Server: ${error?.message ?? "model error"}; ${error?.additionalDetails ?? ""}; retry=${notification.params?.willRetry === true}\n`);
      } else if (notification.method === "warning") {
        process.stderr.write(`HearthCrew App Server: ${notification.params?.message ?? "warning"}\n`);
      }
    });
    try {
      await app.start();
      const readOnly = await app.readOnlyDiagnostics() as { account?: { account?: unknown }; mcpServers?: { data?: unknown[] } };
      if (readOnly.mcpServers?.data?.length !== 0) throw new Error("dedicated Codex runtime inherited unexpected MCP servers");
      const connectedApp = app;
      const authenticated = readOnly.account?.account != null;
      let fastVerification: Awaited<ReturnType<AppServerClient["verifyFastServiceTier"]>> | undefined;
      if (authenticated) {
        fastVerification = await connectedApp.verifyFastServiceTier();
        if (!fastVerification.verified) process.stderr.write(`HearthCrew: fast service tier pending; using default (${fastVerification.reason ?? "not verified"}).\n`);
        else process.stderr.write(`HearthCrew: fast service tier verified; Codex echoed ${fastVerification.echoed ?? "unknown"}.\n`);
      }
      await controller.setBrain({
        createThread: (id,backend) => {
          if (!authenticated) return Promise.reject(new Error("专用 Codex 尚未登录；完成官方登录后重新启动控制器。"));
          return connectedApp.createThread(id,backend);
        },
        startTurn: (thread, input, profile, generation) => connectedApp.startTurn(thread, input, profile, generation),
        diagnostics: () => ({ ...connectedApp.diagnostics(), authenticated, fastServiceTier: fastVerification ?? { verified: false, requested: "fast", reason: authenticated ? "verification unavailable" : "Codex login required" } }),
      });
      if (!authenticated) process.stderr.write("HearthCrew: 专用 Codex 运行环境尚未登录；游戏状态与控制可用，模型任务待登录。\n");
    } catch (error) {
      process.stderr.write(`HearthCrew: Codex unavailable: ${error instanceof Error ? error.message : String(error)}\n`);
      await app.stop(); app = undefined;
    }
    const token = randomBytes(32).toString("hex");
    mod = new ModBridgeServer({ token, ...executionOptions });
    mod.onConnection(connection => { void controller.attach(connection).catch(error => {
      process.stderr.write(`HearthCrew: reconciliation failed: ${error instanceof Error ? error.message : String(error)}\n`); connection.close();
    }); });
    const port = await mod.start();
    await writeFile(join(state, "pairing.json"), JSON.stringify({ port, token }), { encoding: "utf8", mode: 0o600 });
    await writeRuntimeMetadata(metadataPath, { ...metadataBase, bridgePort: port, status: "ready" });
    if (standalone) {
      process.stderr.write("HearthCrew standalone controller ready. PCL can connect through the in-game panel.\n");
      await waitForStandaloneStop(stopFile);
      await writeRuntimeMetadata(metadataPath, { ...metadataBase, bridgePort: port, status: "stopping" });
    } else {
      process.stderr.write("HearthCrew development controller ready. Mod pairing file prepared; MCP uses stdio.\n");
      closeMcp = serveStdioMcp(new DesktopMcp(controller), process.stdin, process.stdout);
      await new Promise<void>(resolveStop => {
        const stop = () => { process.off("SIGINT", stop); process.off("SIGTERM", stop); process.stdin.off("end", stop); resolveStop(); };
        process.once("SIGINT", stop); process.once("SIGTERM", stop); process.stdin.once("end", stop); process.stdin.once("error", stop);
      });
    }
  } finally {
    if(diagnosticTimer)clearInterval(diagnosticTimer);await diagnosticTail;
    const cleanup = [
      ["MCP", async () => { closeMcp?.(); }],
      ["Mod bridge", async () => { await mod?.stop(); }],
      ["Codex App Server", async () => { await app?.stop(); }],
      ["test model trace", async () => { await traceTail; }],
      ["controller lock", release],
    ] as const;
    for (const [name, action] of cleanup) {
      try { await action(); }
      catch (error) {
        process.exitCode = 1;
        process.stderr.write(`HearthCrew: ${name} cleanup failed: ${error instanceof Error ? error.message : String(error)}\n`);
      }
    }
    await writeRuntimeMetadata(metadataPath, { ...metadataBase, ...(mod?.port === undefined ? {} : { bridgePort: mod.port }), status: "stopped", finishedAt: new Date().toISOString() }).catch(error => {
      process.exitCode = 1;
      process.stderr.write(`HearthCrew: runtime metadata cleanup failed: ${error instanceof Error ? error.message : String(error)}\n`);
    });
  }
}
main().catch(error => { process.stderr.write(`HearthCrew runtime: ${error instanceof Error ? error.message : String(error)}\n`); process.exitCode = 1; });
