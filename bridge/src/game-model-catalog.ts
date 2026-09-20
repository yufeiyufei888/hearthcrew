import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, relative, resolve, sep } from "node:path";

const LUNA_SLUG = "gpt-5.6-luna" as const;
const DEBUG_ARGS = ["debug", "models", "--bundled"] as const;
const DEBUG_TIMEOUT_MS = 30_000;
const MAX_STDOUT_BYTES = 16 * 1024 * 1024;
const OUTPUT_PREFIX = "hearthcrew-game-only-luna-";

/** Fields required to prove that the selected entry is a complete debug-models entry. */
const REQUIRED_LUNA_FIELDS = [
  "slug", "display_name", "description", "default_reasoning_level", "supported_reasoning_levels",
  "shell_type", "visibility", "supported_in_api", "priority", "model_messages",
  "apply_patch_tool_type", "web_search_tool_type", "truncation_policy", "context_window",
  "max_context_window", "comp_hash", "effective_context_window_percent", "input_modalities",
  "supports_search_tool", "use_responses_lite", "node_repl_disabled", "tool_mode",
  "multi_agent_version", "base_instructions",
] as const;

type JsonObject = Record<string, unknown>;

export interface GameModelCatalog {
  readonly models: readonly [JsonObject];
}

export interface PrepareGameModelCatalogOptions {
  /** Absolute executable path or command name for the pinned Codex CLI. */
  readonly command: string;
  /** Dedicated child-process environment. Credential variables are deliberately filtered. */
  readonly env: NodeJS.ProcessEnv;
  readonly workspace: string;
  readonly outputDirectory: string;
}

export interface PreparedGameModelCatalog {
  readonly catalogPath: string;
  readonly sha256: string;
  readonly byteLength: number;
  readonly modelSlug: typeof LUNA_SLUG;
  readonly modelCount: 1;
  readonly command: string;
  readonly args: readonly string[];
}

function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parseRawCatalog(raw: string | unknown): JsonObject {
  let parsed: unknown;
  try {
    parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch {
    throw new Error("Codex debug models output is not valid JSON");
  }
  if (!isObject(parsed) || !Array.isArray(parsed.models)) {
    throw new Error("Codex model catalog must be an object with a models array");
  }
  return parsed;
}

function cloneObject(value: JsonObject): JsonObject {
  try {
    return JSON.parse(JSON.stringify(value)) as JsonObject;
  } catch {
    throw new Error("Codex Luna model entry is not JSON serializable");
  }
}

function validateCompleteLuna(entry: JsonObject): void {
  for (const field of REQUIRED_LUNA_FIELDS) {
    if (!Object.prototype.hasOwnProperty.call(entry, field)) {
      throw new Error(`Luna model entry is missing required field: ${field}`);
    }
  }
  if (entry.slug !== LUNA_SLUG) throw new Error("Luna model entry has an unexpected slug");
  if (typeof entry.display_name !== "string" || entry.display_name.length === 0) throw new Error("Luna display_name is invalid");
  if (typeof entry.description !== "string" || entry.description.length === 0) throw new Error("Luna description is invalid");
  if (typeof entry.default_reasoning_level !== "string") throw new Error("Luna default_reasoning_level is invalid");
  if (!Array.isArray(entry.supported_reasoning_levels) || entry.supported_reasoning_levels.length === 0) throw new Error("Luna supported_reasoning_levels is invalid");
  if (!entry.supported_reasoning_levels.some(level => isObject(level) && level.effort === "high")) throw new Error("Luna supported_reasoning_levels must include high");
  if (!isObject(entry.model_messages)) throw new Error("Luna model_messages is invalid");
  if (!isObject(entry.truncation_policy)) throw new Error("Luna truncation_policy is invalid");
  if (!Array.isArray(entry.input_modalities)) throw new Error("Luna input_modalities is invalid");
  if (!Number.isFinite(entry.context_window) || !Number.isFinite(entry.max_context_window)) throw new Error("Luna context window is invalid");
  if (entry.supported_in_api !== true) throw new Error("Luna model is not supported in API");
  if (entry.tool_mode !== "code_mode_only") throw new Error("Luna model must use code_mode_only");
  if (typeof entry.base_instructions !== "string" || entry.base_instructions.length === 0) throw new Error("Luna base_instructions is invalid");
}

/**
 * Select exactly one complete Luna entry and force the HearthCrew capability boundary.
 * The input object is never mutated and all unrelated model fields are preserved.
 */
export function makeGameOnlyCatalog(raw: string | unknown): GameModelCatalog {
  const parsed = parseRawCatalog(raw);
  const models: unknown = parsed.models;
  if (!Array.isArray(models)) throw new Error("Codex model catalog must contain a models array");
  const matches = models.filter((entry: unknown): entry is JsonObject => isObject(entry) && entry.slug === LUNA_SLUG);
  if (matches.length === 0) throw new Error(`Codex model catalog does not contain ${LUNA_SLUG}`);
  if (matches.length !== 1) throw new Error(`Codex model catalog contains ${matches.length} ${LUNA_SLUG} entries; refusing ambiguity`);
  validateCompleteLuna(matches[0]);

  const luna = cloneObject(matches[0]);
  luna.tool_mode = "code_mode_only";
  luna.shell_type = "disabled";
  luna.apply_patch_tool_type = null;
  luna.supports_search_tool = false;
  luna.node_repl_disabled = true;
  // The game process has no delegation surface.  This must be explicit in
  // the model metadata because the code-mode host can otherwise expose the
  // built-in multi-agent functions even when the feature flag is disabled.
  luna.multi_agent_version = null;
  return { models: [luna] };
}

function childEnvironment(input: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const codexHome = input.CODEX_HOME;
  if (!codexHome || !isAbsolute(codexHome)) throw new Error("env.CODEX_HOME must be an absolute dedicated runtime directory");
  const normalize = (value: string) => process.platform === "win32" ? value.toLowerCase() : value;
  if (normalize(resolve(codexHome)) === normalize(resolve(homedir(), ".codex"))) throw new Error("env.CODEX_HOME must not be the global Codex home");
  const output: NodeJS.ProcessEnv = {};
  const allowed = [
    "Path", "PATH", "PATHEXT", "SystemRoot", "ComSpec", "TEMP", "TMP",
    "LOCALAPPDATA", "APPDATA", "PROGRAMDATA", "CODEX_HOME",
  ];
  for (const key of allowed) if (input[key] !== undefined) output[key] = input[key];
  output.CODEX_HOME = resolve(codexHome);
  return output;
}

function runBundledDebugModels(command: string, workspace: string, env: NodeJS.ProcessEnv): Promise<string> {
  return new Promise((resolveOutput, reject) => {
    const child = spawn(command, [...DEBUG_ARGS], {
      cwd: workspace,
      env: childEnvironment(env),
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    let timer: NodeJS.Timeout | undefined;
    const finishError = (error: Error): void => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      child.kill();
      reject(error);
    };
    child.stdout.on("data", (chunk: Buffer | string) => {
      stdout += chunk.toString();
      if (Buffer.byteLength(stdout, "utf8") > MAX_STDOUT_BYTES) finishError(new Error("Codex debug models output exceeded 16 MiB"));
    });
    child.stderr.on("data", (chunk: Buffer | string) => { stderr += chunk.toString(); });
    child.once("error", error => finishError(new Error(`Codex debug models could not start: ${error.message}`)));
    child.once("close", code => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (code !== 0) {
        const detail = stderr.trim().slice(-2_000);
        reject(new Error(`Codex debug models exited with code ${code}${detail ? `: ${detail}` : ""}`));
        return;
      }
      resolveOutput(stdout);
    });
    timer = setTimeout(() => finishError(new Error(`Codex debug models timed out after ${DEBUG_TIMEOUT_MS}ms`)), DEBUG_TIMEOUT_MS);
  });
}

function validateAbsoluteDirectory(value: string, label: string): string {
  if (!isAbsolute(value)) throw new Error(`${label} must be an absolute path`);
  return resolve(value);
}

/** Run the bundled debug catalog in a sanitized child process and write one new owned file. */
export async function prepareGameModelCatalog(options: PrepareGameModelCatalogOptions): Promise<PreparedGameModelCatalog> {
  if (!options.command || options.command.trim().length === 0) throw new Error("command must be non-empty");
  const workspace = validateAbsoluteDirectory(options.workspace, "workspace");
  const outputDirectory = validateAbsoluteDirectory(options.outputDirectory, "outputDirectory");
  await mkdir(outputDirectory, { recursive: true });

  const raw = await runBundledDebugModels(options.command, workspace, options.env);
  const catalog = makeGameOnlyCatalog(raw);
  const serialized = `${JSON.stringify(catalog, null, 2)}\n`;
  const bytes = Buffer.from(serialized, "utf8");
  const filename = `${OUTPUT_PREFIX}${Date.now()}-${randomUUID()}.catalog.json`;
  const catalogPath = resolve(outputDirectory, filename);
  const childPath = relative(outputDirectory, catalogPath);
  if (isAbsolute(childPath) || childPath === ".." || childPath.startsWith(`..${sep}`)) throw new Error("generated catalog escaped outputDirectory");
  await writeFile(catalogPath, bytes, { flag: "wx" });
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  return { catalogPath, sha256, byteLength: bytes.byteLength, modelSlug: LUNA_SLUG, modelCount: 1, command: options.command, args: DEBUG_ARGS };
}

export const GAME_MODEL_CATALOG_CONSTANTS = {
  lunaSlug: LUNA_SLUG,
  debugArgs: DEBUG_ARGS,
  timeoutMs: DEBUG_TIMEOUT_MS,
  outputPrefix: OUTPUT_PREFIX,
} as const;
