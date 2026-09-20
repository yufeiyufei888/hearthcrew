import { mkdir, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { AppServerClient, AGENT_PROFILES } from "./app-server-client.js";
import { PINNED_CODEX_CLI_VERSION, type BridgeRuntimeConfig } from "./runtime-config.js";

const RUN_ID_PATTERN = /^[A-Za-z0-9-]{1,48}$/;
const EXPECTED_TOOLS = ["act", "observe", "propose_work", "remember", "share"] as const;
const MISSING_TOOL_NAMES = ["exec_command", "write_stdin", "apply_patch", "web__run", "view_image", "read_mcp_resource", "list_mcp_resources", "node_repl", "shell"] as const;
const MISSING_GLOBAL_NAMES = ["process", "require", "fetch"] as const;
const SURFACE_MARKER = "HEARTHCREW_ACTUAL_TOOL_SURFACE";
const OBSERVE_MARKER = "HEARTHCREW_CAPABILITY_OBSERVE_ACK";
const EVIDENCE = "ACTUAL_CODE_MODE_CAPABILITY_CONTRACT" as const;

type JsonObject = Record<string, unknown>;
type SafeFailure = "invalid_arguments" | "unexpected_game_tool" | "model_turn_failed" | "timeout" | "contract_mismatch" | "startup_failed";

export interface CapabilityProbeOptions {
  readonly runId: string;
  readonly runRoot: string;
  readonly codexCommand?: string;
  readonly codexRuntime?: string;
  readonly workspace?: string;
  readonly timeoutSeconds?: number;
}

export interface CapabilityProbeReport {
  readonly evidence: typeof EVIDENCE;
  readonly status: "PASS" | "FAIL";
  readonly runId: string;
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly cliVersion?: string;
  readonly catalogSha256?: string;
  readonly model?: { readonly model: string; readonly reasoningEffort: string | null | undefined; readonly serviceTier: string | null | undefined };
  readonly customToolCall?: { readonly count: number; readonly name: string; readonly sourceMatched: boolean; readonly outputMatched: boolean };
  readonly toolSurface?: { readonly names: readonly string[]; readonly missing: Readonly<Record<string, "undefined" | "present">>; readonly globals: Readonly<Record<string, "undefined" | "present">> };
  readonly actualObserve?: { readonly calls: number; readonly completed: boolean };
  readonly failure?: SafeFailure;
}

interface RawEventCapture {
  readonly sourceByCallId: Map<string, { readonly name: string; readonly sourceMatched: boolean }>;
  readonly outputByCallId: Map<string, { readonly markerMatched: boolean; readonly surface?: JsonObject }>;
}

function argument(name: string, fallback = ""): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function object(value: unknown): JsonObject | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : undefined;
}

function safeRunRoot(value: string): string {
  const root = resolve(value);
  const runtimeRoot = resolve(process.cwd(), ".runtime");
  const child = relative(runtimeRoot, root).toLowerCase();
  if (isAbsolute(child) || child === ".." || child.startsWith(`..${sep}`) || child.startsWith("../")) throw new Error("runRoot must stay under the project .runtime directory");
  return root;
}

function parseSurface(text: string): JsonObject | undefined {
  const starts = jsonCandidateStarts(text);
  for (const start of starts) {
    let candidate: unknown = text.slice(start).trim();
    for (let depth = 0; depth < 3; depth += 1) {
      if (typeof candidate !== "string") {
        const value = object(candidate);
        if (value?.marker === SURFACE_MARKER) return value;
        break;
      }
      try { candidate = JSON.parse(candidate) as unknown; } catch { break; }
    }
  }
  return undefined;
}

function hasMarker(text: string, marker: string): boolean {
  const starts = jsonCandidateStarts(text);
  for (const start of starts) {
    let candidate: unknown = text.slice(start).trim();
    for (let depth = 0; depth < 3; depth += 1) {
      if (typeof candidate !== "string") return object(candidate)?.marker === marker;
      try { candidate = JSON.parse(candidate) as unknown; } catch { break; }
    }
  }
  return false;
}

function jsonCandidateStarts(text: string): readonly number[] {
  const bounded = text.slice(0, 1024 * 1024);
  const starts = new Set<number>([0]);
  for (const token of ["{", "[", '"']) {
    const index = bounded.indexOf(token);
    if (index >= 0) starts.add(index);
  }
  // A code-mode host may prefix the serialized tool result with a short
  // runner label. Try quoted JSON strings in a bounded prefix as well.
  for (let index = 0; index < Math.min(bounded.length, 4096); index += 1) if (bounded[index] === '"') starts.add(index);
  return [...starts].sort((left, right) => left - right);
}

function outputTexts(output: unknown): string[] {
  if (!Array.isArray(output)) return [];
  return output.flatMap(entry => {
    const item = object(entry);
    return typeof item?.text === "string" ? [item.text] : [];
  });
}

function captureRawItem(capture: RawEventCapture, item: unknown): void {
  const value = object(item);
  if (value?.type === "custom_tool_call" && typeof value.call_id === "string" && typeof value.name === "string") {
    const input = typeof value.input === "string" ? value.input : "";
    capture.sourceByCallId.set(value.call_id, { name: value.name, sourceMatched: input.includes("ALL_TOOLS.map") && input.includes("tools.observe") });
  }
  if (value?.type === "custom_tool_call_output" && typeof value.call_id === "string") {
    const texts = outputTexts(value.output);
    const surface = texts.map(parseSurface).find((candidate): candidate is JsonObject => candidate !== undefined);
    capture.outputByCallId.set(value.call_id, { markerMatched: texts.some(text => hasMarker(text, OBSERVE_MARKER)), ...(surface === undefined ? {} : { surface }) });
  }
}

function restrictedSurface(value: JsonObject | undefined): CapabilityProbeReport["toolSurface"] {
  if (!value) return undefined;
  const names = Array.isArray(value.names) ? value.names.filter((name): name is string => typeof name === "string").sort() : [];
  const checked = object(value.checked);
  const globals = object(value.globals);
  const missing = Object.fromEntries(MISSING_TOOL_NAMES.map(name => [name, checked?.[name] === "undefined" ? "undefined" : "present"])) as Readonly<Record<string, "undefined" | "present">>;
  const safeGlobals = Object.fromEntries(MISSING_GLOBAL_NAMES.map(name => [name, globals?.[name] === "undefined" ? "undefined" : "present"])) as Readonly<Record<string, "undefined" | "present">>;
  return { names, missing, globals: safeGlobals };
}

function contractPass(report: CapabilityProbeReport): boolean {
  const surface = report.toolSurface;
  const namesPass = surface !== undefined && JSON.stringify(surface.names) === JSON.stringify([...EXPECTED_TOOLS].sort());
  const missingPass = surface !== undefined && Object.keys(surface.missing).length === MISSING_TOOL_NAMES.length && Object.values(surface.missing).every(value => value === "undefined");
  const globalsPass = surface !== undefined && Object.keys(surface.globals).length === MISSING_GLOBAL_NAMES.length && Object.values(surface.globals).every(value => value === "undefined");
  return namesPass === true && missingPass === true && globalsPass === true
    && report.customToolCall?.count === 1 && report.customToolCall.name === "exec" && report.customToolCall.sourceMatched && report.customToolCall.outputMatched
    && report.actualObserve?.calls === 1 && report.actualObserve.completed
    && report.model?.model === "gpt-5.6-luna" && report.model.reasoningEffort === "high" && report.model.serviceTier === "priority";
}

function runtimeConfig(options: CapabilityProbeOptions): BridgeRuntimeConfig {
  const localAppData = process.env.LOCALAPPDATA ?? resolve(homedir(), "AppData", "Local");
  return {
    codexHome: resolve(options.codexRuntime ?? resolve(localAppData, "HearthCrew", "codex-runtime")),
    workspace: resolve(options.workspace ?? resolve(localAppData, "HearthCrew", "workspace")),
    ...(options.codexCommand ? { codexCommand: options.codexCommand } : {}),
  };
}

/**
 * Opt-in real-model capability contract. Raw response events are enabled only
 * by the diagnostic spawn wrapper below; production AppServerClient options
 * never enable or expose experimentalRawEvents.
 */
export async function runCodexCapabilityProbe(options: CapabilityProbeOptions): Promise<CapabilityProbeReport> {
  if (!RUN_ID_PATTERN.test(options.runId)) throw new Error("runId must match [A-Za-z0-9-]{1,48}");
  if (!Number.isSafeInteger(options.timeoutSeconds ?? 180) || (options.timeoutSeconds ?? 180) < 30) throw new Error("timeoutSeconds must be at least 30");
  const runRoot = safeRunRoot(options.runRoot);
  const resultRoot = resolve(runRoot, "result");
  await mkdir(resultRoot, { recursive: false });
  const reportPath = resolve(resultRoot, "report.json");
  const startedAt = new Date().toISOString();
  const capture: RawEventCapture = { sourceByCallId: new Map(), outputByCallId: new Map() };
  const observedCalls: string[] = [];
  const config = runtimeConfig(options);
  const profile = { ...AGENT_PROFILES[0], developerInstructions: "受控只读能力契约测试。必须使用 code-mode wrapper 执行给定 JavaScript，打印实际工具面和全局检查结果，然后只调用一次 observe(radius=1)。不要调用其他工具，不要执行命令、文件、浏览器、联网、MCP、桌面或游戏改变操作。不要编造结果。" };
  const reportBase = { evidence: EVIDENCE, status: "FAIL" as const, runId: options.runId, startedAt, finishedAt: startedAt };
  let report: CapabilityProbeReport = reportBase;
  const app = new AppServerClient({
    runtime: config,
    profiles: [profile],
    diagnosticRawEvents: true,
    dynamicToolHandler: (params: unknown) => {
      const request = object(params);
      const tool = typeof request?.tool === "string" ? request.tool : "";
      observedCalls.push(tool);
      return { success: tool === "observe", contentItems: [{ type: "inputText", text: JSON.stringify({ marker: OBSERVE_MARKER, tool, observed: tool === "observe" }) }] };
    },
  });
  app.on("notification", event => {
    const value = object(event);
    if (value?.method === "rawResponseItem/completed") captureRawItem(capture, object(value.params)?.item);
  });
  try {
    await app.start();
    const fast = await app.verifyFastServiceTier("coordinator");
    if (!fast.verified) throw new Error("fast_service_tier_unverified");
    const thread = await app.createThread(profile.id);
    const handle = await app.startTurn(thread, `Run this exact read-only JavaScript in the code-mode wrapper and print its actual output with text():\nconst names = ALL_TOOLS.map(t => t.name).sort();\nconst checked = Object.fromEntries(${JSON.stringify([...MISSING_TOOL_NAMES])}.map(k => [k, typeof tools[k]]));\ntext(JSON.stringify({marker:${JSON.stringify(SURFACE_MARKER)}, names, checked, globals:{process:typeof process,require:typeof require,fetch:typeof fetch}}));\nconst observed = await tools.observe({radius:1});\ntext(JSON.stringify(observed));\nFinish after this one observe call.`, profile);
    let turnTimer: NodeJS.Timeout | undefined;
    try {
      await Promise.race([handle.completion, new Promise<never>((_, reject) => { turnTimer = setTimeout(() => reject(new Error("timeout")), (options.timeoutSeconds ?? 180) * 1000); })]);
    } finally {
      if (turnTimer !== undefined) clearTimeout(turnTimer);
    }
    const matched = [...capture.sourceByCallId.entries()].map(([callId, source]) => ({ callId, source, output: capture.outputByCallId.get(callId) })).find(entry => entry.output !== undefined);
    const surface = restrictedSurface(matched?.output?.surface);
    report = {
      ...reportBase,
      finishedAt: new Date().toISOString(),
      cliVersion: app.cliVersion,
      catalogSha256: app.diagnostics().modelCatalogSha256,
      model: { model: thread.model, reasoningEffort: thread.reasoningEffort, serviceTier: thread.effectiveServiceTier },
      ...(matched === undefined ? {} : { customToolCall: { count: capture.sourceByCallId.size, name: matched.source.name, sourceMatched: matched.source.sourceMatched, outputMatched: matched.output?.markerMatched === true } }),
      ...(surface === undefined ? {} : { toolSurface: surface }),
      actualObserve: { calls: observedCalls.filter(tool => tool === "observe").length, completed: observedCalls.length === 1 && observedCalls[0] === "observe" },
    };
    if (!contractPass(report)) report = { ...report, status: "FAIL", failure: "contract_mismatch" };
    else report = { ...report, status: "PASS" };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const failure: SafeFailure = message === "timeout" ? "timeout" : message === "fast_service_tier_unverified" ? "startup_failed" : message.startsWith("App Server turn failed") ? "model_turn_failed" : "startup_failed";
    report = { ...reportBase, finishedAt: new Date().toISOString(), cliVersion: app.cliVersion, catalogSha256: app.diagnostics().modelCatalogSha256, actualObserve: { calls: observedCalls.filter(tool => tool === "observe").length, completed: false }, failure };
  } finally {
    await app.stop();
    const finalReport = { ...report, finishedAt: report.finishedAt ?? new Date().toISOString() };
    await writeFile(reportPath, `${JSON.stringify(finalReport, null, 2)}\n`, { encoding: "utf8", flag: "wx" });
    report = finalReport;
  }
  if (report.status !== "PASS") throw new Error(`capability contract failed; inspect ${reportPath}`);
  return report;
}

async function main(): Promise<void> {
  const runId = argument("--run-id");
  const runRoot = argument("--run-root", resolve(process.cwd(), ".runtime", `codex-contract-${runId}`));
  const report = await runCodexCapabilityProbe({ runId, runRoot, codexCommand: argument("--codex-command") || undefined, codexRuntime: argument("--codex-runtime") || undefined, workspace: argument("--workspace") || undefined, timeoutSeconds: Number(argument("--timeout-seconds", "180")) });
  console.log(JSON.stringify({ status: report.status, runId: report.runId, report: resolve(runRoot, "result", "report.json") }));
}

if (process.argv[1]?.endsWith("codex-capability-probe.js")) main().catch(error => { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; });
