import { spawn } from "node:child_process";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { AppServerClient } from "./app-server-client.js";
import { buildIsolatedEnvironment, detectCodexCliVersion, ensureDedicatedCodexConfig, isolatedAppServerArgs, isolationDiagnostic, ISOLATION_DISABLED_FEATURES, PINNED_CODEX_CLI_VERSION, type BridgeRuntimeConfig } from "./runtime-config.js";

function argument(name: string, fallback: string): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function runtimeConfig(): BridgeRuntimeConfig {
  const localAppData = process.env.LOCALAPPDATA ?? join(homedir(), "AppData", "Local");
  const modelCatalog = argument("--model-catalog", "");
  return { codexHome: resolve(argument("--runtime", join(localAppData, "HearthCrew", "codex-runtime"))), workspace: resolve(argument("--workspace", join(localAppData, "HearthCrew", "workspace"))), codexCommand: argument("--codex-command", "codex"), ...(modelCatalog ? { modelCatalogJson: resolve(modelCatalog) } : {}) };
}

async function doctor(config: BridgeRuntimeConfig): Promise<void> {
  const configPath = await ensureDedicatedCodexConfig(config);
  const client = new AppServerClient({ runtime: config });
  try {
    const initialize = await client.start() as { platformOs?: string; platformFamily?: string; userAgent?: string };
    const readOnly = await client.readOnlyDiagnostics() as { account?: { account?: unknown; requiresOpenaiAuth?: boolean }; models?: { data?: unknown[] }; mcpServers?: { data?: unknown[] }; config?: { config?: { project_doc_max_bytes?: unknown; include_environment_context?: unknown; project_root_markers?: unknown; skills?: unknown; web_search?: unknown }; origins?: unknown } };
    // Deliberately summarize account data: never print email, token, or account payloads.
    const models = readOnly.models?.data ?? [];
    const modelIds = models.map((model: any) => model.id ?? model.slug ?? model.model ?? "unknown");
    const modelCapabilities = models.map((model: any) => ({
      id: model.id ?? model.slug ?? model.model ?? "unknown",
      toolMode: model.toolMode ?? model.tool_mode ?? null,
      shellType: model.shellType ?? model.shell_type ?? null,
      applyPatchToolType: model.applyPatchToolType ?? model.apply_patch_tool_type ?? null,
      webSearchToolType: model.webSearchToolType ?? model.web_search_tool_type ?? null,
      supportsSearchTool: model.supportsSearchTool ?? model.supports_search_tool ?? null,
      nodeReplDisabled: model.nodeReplDisabled ?? model.node_repl_disabled ?? null,
      serviceTiers: Array.isArray(model.serviceTiers) ? model.serviceTiers.map((tier: any) => tier.id ?? tier.name ?? "unknown") : Array.isArray(model.service_tiers) ? model.service_tiers.map((tier: any) => tier.id ?? tier.name ?? "unknown") : [],
    }));
    const isolatedConfig = readOnly.config?.config;
    const diagnostics = client.diagnostics();
    const effectiveConfig = diagnostics.modelCatalogPath === undefined ? config : { ...config, modelCatalogJson: diagnostics.modelCatalogPath };
    console.log(JSON.stringify({ cliVersion: client.cliVersion, cliVersionDetectedBeforeStart: detectCodexCliVersion(config.codexCommand ?? "codex", config), configPath, isolation: isolationDiagnostic(config), isolationFlags: { disabledFeatures: ISOLATION_DISABLED_FEATURES, modelCatalogConfigured: diagnostics.modelCatalogConfigured, modelCatalogPath: diagnostics.modelCatalogPath ?? null, modelCatalogSha256: diagnostics.modelCatalogSha256 ?? null, appServerArgCount: isolatedAppServerArgs(effectiveConfig).length }, initialize: { platformOs: initialize.platformOs, platformFamily: initialize.platformFamily, userAgent: initialize.userAgent }, account: { present: readOnly.account?.account != null, requiresOpenaiAuth: readOnly.account?.requiresOpenaiAuth }, modelIds, modelCapabilities, mcpServerCount: readOnly.mcpServers?.data?.length ?? null, configOriginsPresent: readOnly.config?.origins != null, configIsolation: { projectDocMaxBytes: isolatedConfig?.project_doc_max_bytes ?? null, includeEnvironmentContext: isolatedConfig?.include_environment_context ?? null, projectRootMarkers: isolatedConfig?.project_root_markers ?? null, skillsConfigured: isolatedConfig?.skills != null, webSearch: isolatedConfig?.web_search ?? null }, gameOnlyTools: diagnostics.gameOnlyTools, productionGameplay: diagnostics.productionGameplay }, null, 2));
  } finally { await client.stop(); }
}

async function login(config: BridgeRuntimeConfig): Promise<void> {
  const configPath = await ensureDedicatedCodexConfig(config);
  const cliVersion = detectCodexCliVersion(config.codexCommand ?? "codex", config);
  if (cliVersion !== PINNED_CODEX_CLI_VERSION) throw new Error(`unsupported Codex CLI version ${cliVersion}; HearthCrew requires ${PINNED_CODEX_CLI_VERSION}`);
  console.error(`HearthCrew dedicated login home: ${config.codexHome}`);
  console.error(`Config: ${configPath}`);
  const child = spawn(config.codexCommand ?? "codex", ["login", "--device-auth"], { cwd: config.workspace, env: buildIsolatedEnvironment(config), stdio: "inherit" });
  const code = await new Promise<number>((resolveExit, reject) => { child.once("error", reject); child.once("exit", (exitCode) => resolveExit(exitCode ?? 1)); });
  process.exitCode = code;
}

async function server(config: BridgeRuntimeConfig): Promise<void> {
  const client = new AppServerClient({ runtime: config });
  await client.start();
  console.log(JSON.stringify({ status: "ready", cliVersion: client.cliVersion, isolation: isolationDiagnostic(config), gameOnlyTools: client.diagnostics().gameOnlyTools, productionGameplay: "unsupported" }));
  await new Promise<void>((resolveStop) => { const stop = () => { process.off("SIGINT", stop); process.off("SIGTERM", stop); resolveStop(); }; process.once("SIGINT", stop); process.once("SIGTERM", stop); });
  await client.stop();
}

async function main(): Promise<void> {
  const config = runtimeConfig();
  const command = process.argv[2] ?? "doctor";
  if (command === "doctor") await doctor(config);
  else if (command === "login") await login(config);
  else if (command === "server") await server(config);
  else throw new Error(`usage: node cli.js <doctor|login|server> [--runtime ABSOLUTE_PATH] [--workspace ABSOLUTE_PATH] [--codex-command CODEX_PATH] [--model-catalog ABSOLUTE_PATH]`);
}

main().catch((error) => { console.error(`hearthcrew bridge: ${error instanceof Error ? error.message : String(error)}`); process.exitCode = 1; });
