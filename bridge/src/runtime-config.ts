import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { homedir } from "node:os";
import { mkdir, readFile, writeFile, readdir } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { makeGameOnlyCatalog } from "./game-model-catalog.js";

export interface BridgeRuntimeConfig {
  /** Dedicated state directory. It must not be the user's global Codex home. */
  readonly codexHome: string;
  readonly workspace: string;
  readonly codexCommand?: string;
  /** Complete model metadata catalog used to remove non-game built-in capability metadata. */
  readonly modelCatalogJson?: string;
  readonly appServerArgs?: readonly string[];
}

/** The App Server wire contract was verified against this CLI build. */
export const PINNED_CODEX_CLI_VERSION = "0.153.3" as const;

export const ISOLATION_DISABLED_FEATURES = [
  "apps",
  "artifact",
  "auth_elicitation",
  "browser_use",
  "browser_use_external",
  "browser_use_full_cdp_access",
  "computer_use",
  "hooks",
  "image_generation",
  "in_app_browser",
  "in_app_chat",
  "in_app_dictation",
  "in_app_local_automation",
  "in_app_updates",
  "memories",
  "multi_agent",
  "network_proxy",
  "plugin_sharing",
  "plugins",
  "recommended_plugins",
  "remote_plugin",
  "skill_mcp_dependency_install",
  "skill_search",
  "shell_snapshot",
  "shell_tool",
  "sleep_tool",
  "tool_call_mcp_elicitation",
  "tool_suggest",
  "unified_exec",
  "view_image",
  "workspace_dependencies",
  "js_repl",
] as const;

/**
 * The game process uses the official HTTPS Responses transport.  Keep this
 * provider definition on the App Server command line so it cannot inherit a
 * user's global provider or transport preference.
 */
export const HEARTHCREW_HTTP_PROVIDER_ARGS = [
  "-c", "model_provider=\"hearthcrew_http\"",
  "-c", "model_providers.hearthcrew_http={name=\"OpenAI\",base_url=\"https://chatgpt.com/backend-api/codex\",wire_api=\"responses\",requires_openai_auth=true,supports_websockets=false}",
] as const;

export function detectCodexCliVersion(command = "codex", config?: BridgeRuntimeConfig): string {
  try {
    const output = execFileSync(command, ["--version"], { encoding: "utf8", timeout: 10_000, env: config ? buildIsolatedEnvironment(config) : undefined });
    const match = output.trim().match(/codex-cli\s+([^\s]+)/i);
    return match?.[1] ?? "unknown";
  } catch { return "unknown"; }
}

/** Exact feature set disabled for a HearthCrew game-only App Server. */
export function isolatedAppServerArgs(config: BridgeRuntimeConfig): readonly string[] {
  validateRuntimeConfig(config);
  const extra = config.appServerArgs ?? [];
  const forbidden = [
    "--enable", "--config", "-c", "--profile", "--code-mode-host",
    "--dangerously-bypass-approvals-and-sandbox", "--approve-for-me", "--search",
    "--remote", "--remote-auth-token-env", "--listen", "--ws-auth", "--ws-token-file", "--ws-token-sha256",
    "--ws-shared-secret-file", "--ws-issuer", "--ws-audience", "--ws-max-clock-skew-seconds", "--analytics-default-enabled",
  ];
  for (let index = 0; index < extra.length; index += 1) {
    const arg = extra[index];
    const isShortConfigAssignment = arg.startsWith("-c") && arg.length > 2 && !arg.startsWith("--");
    const isForbidden = forbidden.some((flag) => arg === flag || arg.startsWith(`${flag}=`) || (flag === "-c" && isShortConfigAssignment));
    if (isForbidden) throw new Error(`appServerArgs cannot override HearthCrew isolation: ${arg}`);
  }
  const catalogArgs = config.modelCatalogJson === undefined ? [] : ["-c", `model_catalog_json=${JSON.stringify(resolve(config.modelCatalogJson).replaceAll("\\", "/"))}`];
  return ["app-server", "--stdio", "--strict-config", ...ISOLATION_DISABLED_FEATURES.flatMap((feature) => ["--disable", feature]), ...HEARTHCREW_HTTP_PROVIDER_ARGS, ...catalogArgs, ...extra];
}

export const DEDICATED_CONFIG = `# HearthCrew-owned Codex runtime. Do not point this file at the global Codex home.
approval_policy = "never"
sandbox_mode = "read-only"
include_apps_instructions = false
include_collaboration_mode_instructions = false
include_permissions_instructions = false
include_environment_context = false
project_doc_max_bytes = 0
project_root_markers = []
web_search = "disabled"

[features]
apps = false
browser_use = false
browser_use_external = false
browser_use_full_cdp_access = false
computer_use = false
code_mode = true
code_mode_host = true
memories = false
multi_agent = false
plugins = false
shell_tool = false
unified_exec = false

[history]
persistence = "none"
`;

/** The only older config that may be migrated automatically. Unknown files remain untouched and fail closed. */
export const LEGACY_DEDICATED_CONFIG = DEDICATED_CONFIG.replace('web_search = "disabled"\n', "");

/** Create or verify only the bridge-owned config file under the dedicated runtime home. */
export async function ensureDedicatedCodexConfig(config: BridgeRuntimeConfig): Promise<string> {
  const validated = validateRuntimeConfig(config);
  const normalizeForCompare = (value: string) => process.platform === "win32" ? value.toLowerCase() : value;
  if (normalizeForCompare(validated.codexHome) === normalizeForCompare(validated.workspace)) throw new Error("codexHome and workspace must be separate directories");
  const sourceRoot = resolve(process.cwd());
  const workspaceFromSource = relative(sourceRoot, validated.workspace);
  if (workspaceFromSource === "" || (!workspaceFromSource.startsWith(`..${sep}`) && workspaceFromSource !== "..")) throw new Error("dedicated Codex workspace must be outside the HearthCrew source tree");
  await mkdir(validated.codexHome, { recursive: true });
  await mkdir(validated.workspace, { recursive: true });
  const sourceMarkers = new Set([".git", "package.json", "build.gradle", "settings.gradle", "src", "mod", "kernel", "agents.md", "agents.override.md"]);
  let directory = validated.workspace;
  for (;;) {
    const entries = await readdir(directory);
    if (entries.some((entry) => sourceMarkers.has(entry.toLowerCase()))) throw new Error("dedicated Codex workspace must not inherit project or AGENTS markers");
    const parent = dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  const path = resolve(validated.codexHome, "config.toml");
  try {
    const existing = await readFile(path, "utf8");
    const normalize = (value: string) => value.replace(/\r\n/g, "\n");
    if (normalize(existing) === normalize(LEGACY_DEDICATED_CONFIG)) await writeFile(path, DEDICATED_CONFIG, { encoding: "utf8", flag: "r+" });
    else if (normalize(existing) !== normalize(DEDICATED_CONFIG)) throw new Error("dedicated Codex config does not exactly match the HearthCrew isolation profile");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    await writeFile(path, DEDICATED_CONFIG, { encoding: "utf8", flag: "wx" });
  }
  return path;
}

export function validateRuntimeConfig(config: BridgeRuntimeConfig): BridgeRuntimeConfig {
  if (!isAbsolute(config.codexHome) || !isAbsolute(config.workspace)) throw new Error("codexHome and workspace must be absolute paths");
  const codexHome = resolve(config.codexHome);
  const workspace = resolve(config.workspace);
  if (config.modelCatalogJson !== undefined && !isAbsolute(config.modelCatalogJson)) throw new Error("modelCatalogJson must be an absolute path");
  const modelCatalogJson = config.modelCatalogJson === undefined ? undefined : resolve(config.modelCatalogJson);
  const normalizeForCompare = (value: string) => process.platform === "win32" ? value.toLowerCase() : value;
  const forbiddenHomes = [resolve(homedir(), ".codex")];
  if (process.env.CODEX_HOME) forbiddenHomes.push(resolve(process.env.CODEX_HOME));
  if (forbiddenHomes.some((forbiddenHome) => normalizeForCompare(codexHome) === normalizeForCompare(forbiddenHome))) throw new Error("hearthcrew runtime must not use the global CODEX_HOME");
  return { ...config, codexHome, workspace, ...(modelCatalogJson === undefined ? {} : { modelCatalogJson }) };
}

/** Validate the public model metadata used by the game-only process before spawning it. */
export async function assertGameModelCatalog(path: string, codexHome?: string): Promise<string> {
  const catalogPath = resolve(path);
  if (codexHome !== undefined) {
    const catalogRoot = resolve(codexHome, "model-catalog");
    const relativePath = relative(catalogRoot, catalogPath).toLowerCase();
    if (isAbsolute(relativePath) || relativePath === ".." || relativePath.startsWith(`..${sep}`) || relativePath.startsWith("../")) throw new Error("HearthCrew model catalog must stay under the dedicated codexHome/model-catalog directory");
  }
  const raw = await readFile(catalogPath, "utf8");
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { throw new Error("HearthCrew model catalog is not valid JSON"); }
  if (!parsed || typeof parsed !== "object" || !Array.isArray((parsed as { models?: unknown }).models) || (parsed as { models: unknown[] }).models.length !== 1) throw new Error("HearthCrew model catalog must contain exactly one model");
  const model = (parsed as { models: unknown[] }).models[0];
  if (!model || typeof model !== "object" || Array.isArray(model)) throw new Error("HearthCrew model catalog entry is invalid");
  // makeGameOnlyCatalog performs the complete-schema and duplicate-Luna checks.
  makeGameOnlyCatalog(parsed);
  const candidate = model as Record<string, unknown>;
  if (candidate.slug !== "gpt-5.6-luna") throw new Error("HearthCrew model catalog must contain the pinned Luna model");
  if (candidate.tool_mode !== "code_mode_only") throw new Error("HearthCrew model catalog must already use code_mode_only");
  if (candidate.shell_type !== "disabled") throw new Error("HearthCrew model catalog must already disable shell_type");
  if (!Object.prototype.hasOwnProperty.call(candidate, "apply_patch_tool_type") || candidate.apply_patch_tool_type !== null) throw new Error("HearthCrew model catalog must already disable apply_patch");
  if (candidate.supports_search_tool !== false) throw new Error("HearthCrew model catalog must already disable search");
  if (candidate.node_repl_disabled !== true) throw new Error("HearthCrew model catalog must already disable node repl");
  if (candidate.multi_agent_version !== null) throw new Error("HearthCrew model catalog must already disable multi-agent");
  return createHash("sha256").update(raw, "utf8").digest("hex");
}

/** Build a minimal Windows process environment without copying API keys or bearer tokens. */
export function buildIsolatedEnvironment(config: BridgeRuntimeConfig): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  const allow = ["Path", "PATH", "PATHEXT", "SystemRoot", "ComSpec", "TEMP", "TMP", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA"];
  for (const key of allow) if (process.env[key] !== undefined) env[key] = process.env[key];
  env.CODEX_HOME = config.codexHome;
  return env;
}

export interface IsolationDiagnostic {
  readonly isolated: boolean;
  readonly codexHome: string;
  readonly copiedCredentialVariables: readonly [];
  readonly blockedCredentialVariableCount: number;
  readonly globalConfigTouched: false;
}

export function isolationDiagnostic(config: BridgeRuntimeConfig): IsolationDiagnostic {
  const blockedCredentialVariableCount = Object.keys(process.env).filter((key) => /(TOKEN|API_KEY|SECRET|PASSWORD|AUTH)/i.test(key) && key !== "CODEX_HOME").length;
  return { isolated: true, codexHome: resolve(config.codexHome), copiedCredentialVariables: [], blockedCredentialVariableCount, globalConfigTouched: false };
}
