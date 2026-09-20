import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { once } from "node:events";
import test from "node:test";

test("standalone runtime ignores stdin EOF and stops through its owned stop file", async () => {
  const root = await mkdtemp(join(tmpdir(), "hearthcrew-standalone-"));
  const state = join(root, "state");
  const codexRuntime = join(root, "codex-runtime");
  const workspace = join(root, "workspace");
  const runtime = resolve(process.cwd(), "dist", "bridge", "src", "play-runtime.js");
  const child = spawn(process.execPath, [runtime, "--standalone", "--state", state, "--runtime", codexRuntime, "--workspace", workspace, "--codex-command", "hearthcrew-test-missing-codex"], {
    cwd: resolve(process.cwd(), ".."), stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
  });
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk.toString("utf8"); });
  try {
    child.stdin.end();
    const deadline = Date.now() + 15_000;
    let pairing: { port?: number; token?: string } | undefined;
    while (Date.now() < deadline) {
      try { pairing = JSON.parse(await readFile(join(state, "pairing.json"), "utf8")) as { port?: number; token?: string }; } catch { }
      if (pairing?.port && pairing.token) break;
      await new Promise(resolveDelay => setTimeout(resolveDelay, 50));
    }
    assert.ok(pairing?.port && pairing.token, `standalone pairing was not published: ${stderr}`);
    assert.equal(child.exitCode, null, "stdin EOF must not stop standalone runtime");
    const metadata = JSON.parse(await readFile(join(state, "runtime-metadata.json"), "utf8")) as { mode?: string; status?: string; pid?: number };
    assert.equal(metadata.mode, "standalone");
    assert.equal(metadata.status, "ready");
    assert.equal(metadata.pid, child.pid);

    await writeFile(join(state, "stop.request"), JSON.stringify({ schema: 1, requestedBy: "test" }) + "\n", { encoding: "utf8", flag: "wx" });
    const [code, signal] = await once(child, "exit") as [number | null, NodeJS.Signals | null];
    assert.equal(code, 0, `standalone runtime exited with ${code ?? signal}: ${stderr}`);
    const stopped = JSON.parse(await readFile(join(state, "runtime-metadata.json"), "utf8")) as { status?: string };
    assert.equal(stopped.status, "stopped");
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill();
    await rm(root, { recursive: true, force: true });
  }
});
