import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { makeGameOnlyCatalog, prepareGameModelCatalog } from "../src/game-model-catalog.js";

function completeLuna(): Record<string, unknown> {
  return {
    slug: "gpt-5.6-luna",
    display_name: "GPT-5.6-Luna",
    description: "Fast and affordable agentic coding model.",
    default_reasoning_level: "medium",
    supported_reasoning_levels: [{ effort: "low", description: "low" }, { effort: "high", description: "high" }],
    shell_type: "unified_exec",
    visibility: "list",
    supported_in_api: true,
    priority: 8,
    model_messages: { instructions_template: "test" },
    apply_patch_tool_type: "freeform",
    web_search_tool_type: "text_and_image",
    truncation_policy: { mode: "tokens" },
    context_window: 272000,
    max_context_window: 872000,
    comp_hash: "test",
    effective_context_window_percent: 95,
    input_modalities: ["text"],
    supports_search_tool: true,
    use_responses_lite: true,
    node_repl_disabled: false,
    tool_mode: "code_mode_only",
    multi_agent_version: "v1",
    base_instructions: "test instructions",
    future_parameter: { preserved: true },
  };
}

test("makeGameOnlyCatalog selects complete Luna and preserves unrelated metadata", () => {
  const raw = { models: [{ slug: "other", ignored: true }, completeLuna()] };
  const result = makeGameOnlyCatalog(raw);
  assert.equal(result.models.length, 1);
  assert.deepEqual(result.models[0].future_parameter, { preserved: true });
  assert.equal(result.models[0].slug, "gpt-5.6-luna");
  assert.equal(result.models[0].tool_mode, "code_mode_only");
  assert.equal(result.models[0].shell_type, "disabled");
  assert.equal(result.models[0].apply_patch_tool_type, null);
  assert.equal(result.models[0].supports_search_tool, false);
  assert.equal(result.models[0].node_repl_disabled, true);
  assert.equal(result.models[0].multi_agent_version, null);
  assert.equal((raw.models[1] as Record<string, unknown>).shell_type, "unified_exec");
});

test("makeGameOnlyCatalog rejects missing, incomplete, or ambiguous Luna entries", () => {
  assert.throws(() => makeGameOnlyCatalog({ models: [] }), /does not contain gpt-5\.6-luna/);
  const incomplete = completeLuna();
  delete incomplete.base_instructions;
  assert.throws(() => makeGameOnlyCatalog({ models: [incomplete] }), /base_instructions/);
  assert.throws(() => makeGameOnlyCatalog({ models: [completeLuna(), completeLuna()] }), /ambiguity/);
  assert.throws(() => makeGameOnlyCatalog("not-json"), /not valid JSON/);
});

test("prepareGameModelCatalog runs only the bounded debug command and writes an owned catalog", { skip: process.env.HEARTHCREW_CODEX_COMMAND === undefined }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "hearthcrew-game-catalog-"));
  const outputDirectory = join(directory, "output");
  try {
    const command = process.env.HEARTHCREW_CODEX_COMMAND as string;
    const result = await prepareGameModelCatalog({
      command,
      env: { Path: process.env.Path, PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, TEMP: process.env.TEMP, TMP: process.env.TMP, CODEX_HOME: join(directory, "codex") },
      workspace: directory,
      outputDirectory,
    });
    assert.equal(result.modelSlug, "gpt-5.6-luna");
    assert.equal(result.modelCount, 1);
    assert.match(result.catalogPath, /hearthcrew-game-only-luna-.*\.catalog\.json$/);
    const output = await readFile(result.catalogPath);
    assert.equal(createHash("sha256").update(output).digest("hex"), result.sha256);
    const outputRelative = relative(resolve(outputDirectory), result.catalogPath);
    assert.ok(!outputRelative.startsWith(".."));
    const parsed = JSON.parse(output.toString("utf8")) as { models: Record<string, unknown>[] };
    assert.equal(parsed.models[0].apply_patch_tool_type, null);
    assert.equal(parsed.models[0].multi_agent_version, null);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
