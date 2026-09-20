import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { assertNoExtraScenarioActions } from "./scenario-receipts.js";

type RecordValue = Record<string, any>;
const COMPOUND_BUILD = process.argv.includes("--compound-build");
const EVIDENCE = COMPOUND_BUILD ? "CONTROLLED_REAL_CLIENT_TEAM_BUILD_FIXTURE" : "CONTROLLED_REAL_CLIENT_TEAM_FIXTURE";
const SAFE_ID = /^[A-Za-z0-9-]{1,48}$/;
function flag(name: string, fallback = ""): string { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1] ?? fallback; }
function record(value: unknown, label: string): RecordValue {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as RecordValue;
}
function owned(value: string, root: string): string {
  const path = resolve(value); const part = relative(resolve(root), path);
  if (isAbsolute(part) || part === ".." || part.startsWith(`..${sep}`)) throw new Error("probe output is outside the owned test root");
  return path;
}
async function json(path: string): Promise<RecordValue | undefined> {
  try { return record(JSON.parse(await readFile(path, "utf8")), "JSON file"); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined; throw error; }
}
const delay = (ms: number): Promise<void> => new Promise(done => setTimeout(done, ms));
function itemCount(body: RecordValue, item: string): number {
  if (!Array.isArray(body.inventory)) throw new Error("actual body inventory missing");
  return body.inventory.filter((stack: RecordValue) => stack.item === item).reduce((count: number, stack: RecordValue) => {
    if (!Number.isSafeInteger(stack.count) || stack.count < 0) throw new Error("invalid inventory quantity");
    return count + stack.count;
  }, 0);
}
function actionId(value: any): string | undefined { return typeof value === "string" ? value : typeof value?.value === "string" ? value.value : undefined; }
function completed(body: RecordValue, kind: string): RecordValue[] {
  if (!Array.isArray(body.actionJournal)) throw new Error("actual action journal missing");
  return body.actionJournal.filter((row: RecordValue) => row.state === "COMPLETED" && row.payload?.kind === kind);
}
function assertUniqueActions(body: RecordValue, kind: string, count: number): RecordValue[] {
  const rows = completed(body, kind); const ids = rows.map(row => actionId(row.id));
  if (rows.length !== count || ids.some(id => !id) || new Set(ids).size !== count) throw new Error(`${body.name}: ${kind} terminal count/identity mismatch`);
  return rows;
}
function samePosition(left: any, right: any): boolean { return left && right && ["x", "y", "z"].every(axis => left[axis] === right[axis]); }
function client(child: ChildProcessWithoutNullStreams) {
  let nextId = 0;
  const pending = new Map<number, { resolve(value: any): void; reject(error: Error): void; timer: NodeJS.Timeout }>();
  const lines = createInterface({ input: child.stdout });
  const fail = (error: Error): void => { for (const value of pending.values()) { clearTimeout(value.timer); value.reject(error); } pending.clear(); };
  lines.on("line", line => {
    try {
      const response = JSON.parse(line); const entry = pending.get(response.id);
      if (!entry) return;
      clearTimeout(entry.timer); pending.delete(response.id);
      if (response.error) entry.reject(new Error(response.error.message ?? "MCP error")); else entry.resolve(response.result);
    } catch { fail(new Error("controller emitted invalid MCP JSON")); }
  });
  child.on("error", fail);
  child.on("exit", (code, signal) => fail(new Error(`controller exited ${code ?? signal}`)));
  return {
    call(method: string, params: unknown = {}, timeoutMs = 30_000): Promise<any> {
      return new Promise((resolveCall, rejectCall) => {
        const id = ++nextId;
        const timer = setTimeout(() => { pending.delete(id); rejectCall(new Error(`${method} timed out`)); }, timeoutMs);
        pending.set(id, { resolve: resolveCall, reject: rejectCall, timer });
        child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
      });
    },
    async close(): Promise<boolean> {
      child.stdin.end();
      if (child.exitCode === null && child.signalCode === null) await new Promise<void>(done => {
        const timer = setTimeout(done, 10_000); child.once("exit", () => { clearTimeout(timer); done(); });
      });
      lines.close();
      return child.exitCode === 0 && child.signalCode === null;
    },
  };
}

async function main(): Promise<void> {
  const project = resolve(flag("--project-root", process.cwd()));
  const runId = flag("--run-id");
  if (!SAFE_ID.test(runId)) throw new Error("a unique safe run ID is required");
  const runRoot = owned(flag("--run-root", resolve(project, ".runtime", `team-client-${runId}`)), resolve(project, ".runtime"));
  const state = owned(flag("--controller-state", resolve(runRoot, "controller")), runRoot);
  const reportPath = owned(flag("--report", resolve(runRoot, "report.json")), runRoot);
  const expectedSave = resolve(project, "mod", "run", "client", "saves", `P2Team-${runId}`);
  const fixtureSave = resolve(flag("--fixture-save", expectedSave));
  if (fixtureSave.toLowerCase() !== expectedSave.toLowerCase()) throw new Error("fixture save must be the new P2Team save for this run");
  try { await stat(fixtureSave); throw new Error("fixture save already exists; choose a fresh run ID"); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
  await mkdir(state, { recursive: true });
  if ((await readdir(state)).length) throw new Error("controller state must be empty for this test");
  const timeout = Number(flag("--timeout-seconds", "900")) * 1000;
  if (!Number.isSafeInteger(timeout) || timeout < 60_000 || timeout > 3_600_000) throw new Error("timeout must be 60..3600 seconds");
  const runtime = flag("--codex-runtime", resolve(process.env.LOCALAPPDATA ?? project, "HearthCrew", "codex-runtime"));
  const workspace = flag("--workspace", resolve(process.env.LOCALAPPDATA ?? project, "HearthCrew", "workspace"));
  const report: RecordValue = { protocol: "hearthcrew.v1", evidence: EVIDENCE, constructionMode: COMPOUND_BUILD ? "BUILD" : "PLACE", runId, status: "FAIL", startedAt: new Date().toISOString() };
  let child: ChildProcessWithoutNullStreams | undefined;
  let mcp: ReturnType<typeof client> | undefined;
  let last: RecordValue | undefined;
  let tool: ((name: string, args?: unknown, timeoutMs?: number) => Promise<any>) | undefined;
  try {
    child = spawn(process.execPath, [resolve(project, "bridge", "dist", "bridge", "src", "play-runtime.js"),
      "--state", state, "--runtime", runtime, "--workspace", workspace, "--codex-command", flag("--codex-command", "codex"), "--trace-model"],
      { cwd: project, stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    child.stderr.on("data", chunk => process.stderr.write(chunk));
    mcp = client(child);
    await mcp.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "hearthcrew-team-client-probe", version: "0.1.0-dev" } });
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} }) + "\n");
    const listed = await mcp.call("tools/list");
    report.desktopTools = listed.tools?.map((value: RecordValue) => value.name);
    if (!Array.isArray(report.desktopTools) || ["status", "command", "control", "events"].some(name => !report.desktopTools.includes(name))) throw new Error("desktop MCP surface incomplete");
    tool = async (name, args = {}, timeoutMs = 30_000) => {
      const reply = await mcp!.call("tools/call", { name, arguments: args }, timeoutMs);
      if (reply.isError) throw new Error(reply.content?.[0]?.text ?? "desktop MCP call failed");
      return reply.structuredContent;
    };
    const fixturePath = resolve(fixtureSave, `hearthcrew-p2-team-fixture-${runId}.json`);
    const fixtureReportPath = resolve(fixtureSave, `hearthcrew-p2-team-report-${runId}.json`);
    let metadata: RecordValue | undefined;
    const attachDeadline = Date.now() + 180_000;
    while (Date.now() < attachDeadline) {
      last = await tool("status"); metadata = await json(fixturePath);
      if (metadata && last && last.game?.worldId === metadata.worldId && last.game.companions?.length === 3) break;
      await delay(1000);
    }
    if (!metadata || metadata.runId !== runId || metadata.saveName !== `P2Team-${runId}` || metadata.evidence !== EVIDENCE
      || metadata.initialInventoryEmpty !== true || metadata.actionsSubmittedByFixture !== false || !last?.game) throw new Error("new team fixture did not attach with matching identity");
    if (metadata.constructionMode !== (COMPOUND_BUILD ? "BUILD" : "PLACE")) throw new Error("fixture construction mode differs from requested scenario");
    const initialBodies: RecordValue[] = last.game.companions;
    const definedBodies: RecordValue[] = metadata.bodies;
    if (!Array.isArray(definedBodies) || definedBodies.length !== 3 || new Set(definedBodies.map(body => body.logicalUuid)).size !== 3) throw new Error("fixture must define three distinct bodies");
    for (const body of initialBodies) {
      const expected = definedBodies.find(value => value.logicalUuid === body.botId);
      if (!expected || expected.entityUuid !== body.entityId || !Number.isSafeInteger(body.bodyGeneration)
        || expected.bodyGeneration !== body.bodyGeneration || !Array.isArray(body.inventory) || body.inventory.length !== 0) throw new Error("initial body identity/generation/empty inventory mismatch");
      if (!expected.oakPosition || !["x", "y", "z"].every(axis => Number.isSafeInteger(expected.oakPosition[axis]))) throw new Error("per-body log position missing");
    }
    const tools = last.appServer?.toolNames;
    if (!Array.isArray(tools) || tools.length !== 5 || ["observe", "act", "propose_work", "share", "remember"].some(name => !tools.includes(name))) throw new Error("dedicated game tool surface not confirmed");
    const builder = definedBodies.find(body => body.role === "builder");
    if (!builder || !Array.isArray(metadata.buildingPositions) || metadata.buildingPositions.length !== 3) throw new Error("fixture builder or build positions missing");
    report.initial = { game: last.game, metadata, appServer: last.appServer };
    const targets = definedBodies.map(body => ({ botId: body.logicalUuid, name: body.name, oakPosition: body.oakPosition }));
    const buildInstruction = COMPOUND_BUILD
      ? `建造者必须只提交一次 BUILD，parameters.steps=${JSON.stringify(metadata.buildingPositions.map((position: unknown) => ({ position, block: "minecraft:oak_planks" })))}；由本地执行器连续放好三块，不得拆成三次 PLACE。`
      : "建造者使用三次 PLACE 完成三个指定位置。";
    const command = await tool("command", { message: `这是受控 P2 三人协作测试，不是普通生存通关。三名伙伴各采集且仅采集自己指定的一块橡木，并各制作一次 minecraft:oak_planks（得到4木板）。目标对应关系：${JSON.stringify(targets)}。伙伴 ${builder.name}（botId=${builder.logicalUuid}，entityId=${builder.entityUuid}）是材料接收和建造者，其余两人把自己的4木板交给它。接收者最终使用真实木板在 ${JSON.stringify(metadata.buildingPositions)} 这三个位置各放置一块橡木木板，最后留9木板；其余两人留0。${buildInstruction}请使用 propose_work 将实际任务分配给伙伴（包含你自己），再由接收任务的伙伴 act(taskId) 执行，每次接受动作后结束回合等待结果。要协商并尊重团队资源，队长也必须亲自完成自己的采木、制作和交付。不要动石平台、其他树或玩家，不要制造多余动作；完成个人工作后记住贡献并等待团队完成。` }, timeout);
    if (!command?.accepted || command.count !== 3 || !command.groupIntentId) throw new Error("three-role command did not bind the full squad");
    const assignments: RecordValue[] = command.assignments;
    if (!Array.isArray(assignments) || assignments.length !== 3
      || new Set(assignments.map(value => value.role)).size !== 3 || new Set(assignments.map(value => value.intentId)).size !== 3
      || !initialBodies.every(body => assignments.filter(value => value.botId === body.botId).length === 1)
      || assignments.some(value => value.intentId !== `${command.groupIntentId}:${value.role}`)) throw new Error("squad role/intent bindings differ from the three initial bodies");
    report.command = command;
    const end = Date.now() + timeout;
    let lastProgress = 0;
    while (Date.now() < end) {
      // Observe the server's fixture verdict before requesting the world snapshot.
      // A PASS must not be paired with a snapshot from before its final placement.
      const fixture = await json(fixtureReportPath);
      if (fixture?.status === "PASS") {
        report.finalControl = await tool("control", { operation: "pause" });
        if (report.finalControl?.count !== 3) throw new Error("could not quiesce all three roles before final verification");
      }
      last = record(await tool("status", {}, 20_000), "status");
      await writeFile(resolve(runRoot, "live-status.json"), JSON.stringify(last, null, 2), "utf8");
      if (last.game?.worldId !== metadata.worldId || last.game.companions?.length !== 3) throw new Error("world or squad changed during test");
      const bodies: RecordValue[] = last.game.companions;
      for (const initial of initialBodies) {
        const body = bodies.find(value => value.botId === initial.botId);
        if (!body || body.entityId !== initial.entityId || body.bodyGeneration !== initial.bodyGeneration || body.health <= 0) throw new Error("body incarnation changed or died during controlled team test");
      }
      if (fixture && (fixture.evidence !== EVIDENCE || fixture.worldId !== metadata.worldId || fixture.runId !== runId)) throw new Error("fixture report identity mismatch");
      if (fixture?.status === "FAIL") throw new Error(`fixture rejected actual state: ${fixture.failure ?? "unknown"}`);
      const roles: RecordValue[] = last.roles ?? [];
      if (roles.length !== 3 || new Set(roles.map(role => role.threadId)).size !== 3 || roles.some(role => !role.threadId || role.model !== "gpt-5.6-luna" || role.reasoningEffort !== "high")) throw new Error("three independent Luna/high sessions were not confirmed");
      const healthyAction = bodies.some(body => body.action && ["ACCEPTED", "RUNNING", "SUSPENDED"].includes(body.action.state));
      if (fixture?.status !== "PASS" && roles.some(role => role.status === "failed") && !healthyAction)
        throw new Error("one or more model roles failed; healthy body actions were allowed to finish first");
      if (Date.now() - lastProgress >= 10_000) {
        process.stdout.write(JSON.stringify({ progress: true, runId, gameTick: last.game.gameTick, roles, bodies: bodies.map(body => ({ name: body.name, inventory: body.inventory, action: body.action })) }) + "\n"); lastProgress = Date.now();
      }
      if (fixture?.status === "PASS") {
        if (fixture.oakLogsAir !== true || fixture.buildingPositionsOakPlanks !== true
          || fixture.builderNineOakPlanks !== true || fixture.unexpectedItems !== false) throw new Error("fixture PASS lacks required actual block and inventory postconditions");
        const contributions: RecordValue[] = fixture.contributions;
        if (!Array.isArray(contributions) || contributions.length !== 3) throw new Error("fixture PASS lacks three body contribution records");
        for (const initial of initialBodies) {
          const contribution = contributions.find(value => value.logicalUuid === initial.botId);
          if (!contribution || contribution.entityUuid !== initial.entityId
            || contribution.observedEntityUuid !== initial.entityId
            || contribution.bodyGeneration !== initial.bodyGeneration || contribution.alive !== true
            || contribution.ownerUuid !== metadata.ownerUuid || contribution.dimension !== initial.dimension)
            throw new Error("fixture contribution identity/generation differs from current controlled squad");
        }
        const receipts: RecordValue[] = [];
        for (const expected of definedBodies) {
          const body = bodies.find(value => value.botId === expected.logicalUuid)!;
          const mines = assertUniqueActions(body, "MINE", 1);
          const crafts = assertUniqueActions(body, "CRAFT", 1);
          if (!samePosition(mines[0]!.payload.position, expected.oakPosition) || crafts[0]!.payload.resource !== "minecraft:oak_planks" || crafts[0]!.payload.count !== 1) throw new Error("actual mining/crafting semantics differ from fixture");
          if (expected.logicalUuid === builder.logicalUuid) {
            if (COMPOUND_BUILD) {
              const builds = assertUniqueActions(body, "BUILD", 1);
              assertUniqueActions(body, "PLACE", 0);
              const steps = builds[0]!.payload.steps;
              if (!Array.isArray(steps) || steps.length !== 3 || steps.some((step: RecordValue) => step.block !== "minecraft:oak_planks")
                || !metadata.buildingPositions.every((pos: unknown) => steps.some((step: RecordValue) => samePosition(step.position, pos)))) throw new Error("compound BUILD blueprint differs from fixture");
              receipts.push(...builds);
            } else {
              assertUniqueActions(body, "BUILD", 0);
              const placements = assertUniqueActions(body, "PLACE", 3);
              if (!metadata.buildingPositions.every((pos: unknown) => placements.some(row => samePosition(row.payload.position, pos)))) throw new Error("builder positions not confirmed");
              receipts.push(...placements);
            }
            if (itemCount(body, "minecraft:oak_planks") !== 9) throw new Error("builder output/consumption not confirmed");
          } else {
            const transfers = assertUniqueActions(body, "TRANSFER", 1);
            if (transfers[0]!.payload.target !== builder.entityUuid || transfers[0]!.payload.resource !== "minecraft:oak_planks" || transfers[0]!.payload.count !== 4 || itemCount(body, "minecraft:oak_planks") !== 0) throw new Error("actual contribution transfer not confirmed");
            receipts.push(...transfers);
          }
          if (itemCount(body, "minecraft:oak_log") !== 0) throw new Error("unprocessed log remains");
          receipts.push(...mines, ...crafts);
        }
        if (new Set(receipts.map(row => actionId(row.id))).size !== receipts.length) throw new Error("duplicate semantic action identities");
        const allRows: RecordValue[] = bodies.flatMap(body => body.actionJournal);
        assertNoExtraScenarioActions(allRows, receipts);
        const team = last.game.team;
        if (!team || team.recoveryInvalid || !Array.isArray(team.bindings) || !Array.isArray(team.ledger?.tasks)) throw new Error("actual team ledger unavailable");
        if (team.bindings.length !== receipts.length || team.ledger.tasks.length !== receipts.length) throw new Error("unexpected extra team work or action bindings in the controlled scenario");
        for (const receipt of receipts) {
          const id = actionId(receipt.id);
          const bindings = team.bindings.filter((binding: RecordValue) => binding.actionId === id);
          if (bindings.length !== 1) throw new Error("completed scenario action must have exactly one authoritative team binding");
          const tasks = team.ledger.tasks.filter((task: RecordValue) => task.task?.id?.value === bindings[0].taskId);
          if (tasks.length !== 1 || tasks[0].state !== "COMPLETED" || tasks[0].reservedResources?.length !== 0) throw new Error("actual team task completion or released reservations missing");
        }
        report.status = "PASS"; report.final = { game: last.game, roles, fixture, matchedReceipts: receipts }; break;
      }
      await delay(1000);
    }
    if (report.status !== "PASS") throw new Error("three-role real client scenario timed out");
  } catch (error) {
    report.failure = error instanceof Error ? error.message : String(error); report.lastStatus = last; process.exitCode = 1;
  } finally {
    try { if (tool) report.events = await tool("events", { after: 0, limit: 100 }, 10_000); } catch { /* Keep original outcome. */ }
    let closed = false;
    try { if (mcp) closed = await mcp.close(); } catch { /* Runner retains ownership for fallback cleanup. */ }
    if (!closed) {
      report.status = "FAIL"; report.failure ??= "controller did not exit cleanly"; process.exitCode = 1;
      if (child?.pid && child.exitCode === null && child.signalCode === null) {
        if (process.platform === "win32") spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
        else child.kill("SIGTERM");
      }
    }
    report.controllerClosedGracefully = closed; report.finishedAt = new Date().toISOString();
    await writeFile(reportPath, JSON.stringify(report, null, 2), { encoding: "utf8", flag: "wx" });
    process.stdout.write(JSON.stringify({ status: report.status, runId, report: reportPath, failure: report.failure }) + "\n");
  }
}
main().catch(error => { process.stderr.write(`team client probe failed: ${error instanceof Error ? error.message : String(error)}\n`); process.exitCode = 1; });
