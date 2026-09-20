import test from "node:test";
import assert from "node:assert/strict";
import { makeUiView } from "../src/ui-view.js";

test("historical recovery cannot masquerade as current model wait or idle action",()=>{
 const status={game:{companions:[{botId:"b",name:"Ember",harvestEvidence:[{recovery:"pickup_or_inspect_required",pendingDrops:[{availability:"visible"},{availability:"not_observable"}]},{recovery:"unavailable",pendingDrops:[{availability:"removed"}]}]}]},roles:[{botId:"b",activity:"thinking",waitReason:"等待模型回复"}]};
 const b=makeUiView(status,[]).companions[0]!;
 assert.equal(b.waitReason,"等待模型回复");assert.equal(b.activity,"thinking");assert.equal(b.action,"当前无身体动作");
 assert.match(b.recoverySummary,/可见 1 组，已消失 1 组，未观测 1 组/);
 const idle=makeUiView({...status,roles:[{botId:"b",activity:"idle"}]},[]).companions[0]!;
 assert.equal(idle.waitReason,"unknown");
});

test("makeUiView produces bounded six-page data without retaining raw status or events", () => {
  const status = {
    stage: "P2_DEVELOPMENT",
    controller: "running",
    game: {
      worldId: "world-a", gameTick: 42,
      companions: Array.from({ length: 4 }, (_, index) => ({
        botId: `bot-${index}`, name: `伙伴${index}`, dimension: "minecraft:overworld",
        position: { x: index, y: 64, z: 2 }, health: 20, food: 18,
        inventory: Array.from({ length: 40 }, (_, slot) => ({ slot, item: "minecraft:oak_log", count: 1 })),
        action: { payload: { kind: "MINE" }, state: "RUNNING" },
      })),
      team: {
        recoveryInvalid: false,
        works: [{ taskId: "intent:wood", botId: "bot-0", intentId: "intent", bodyGeneration: 7, kind: "MINE", position: { x: 3, y: 64, z: 4 } }],
        ledger: { tasks: [{ task: { id: { value: "intent:wood" } }, state: "RUNNING" }] },
        bindings: [{ taskId: "intent:wood", actionId: "team-action", bodyGeneration: 7 }],
      },
    },
    roles: [{ role: "coordinator", botId: "bot-0", status: "working", taskId: "intent:wood", taskState: "RUNNING", model: "gpt-5.6-luna", reasoningEffort: "high", serviceTier: "priority" }],
    appServer: { appServer: "connected", cliVersion: "0.153.3", gameOnlyTools: "pending", modelCatalogPath: "C:\\private\\token.json", toolNames: ["observe"] },
    privateRawStatus: "should not appear",
  };
  const events = [
    { sequence: 1, type: "owner.command", worldId: "world-a", data: { role: "coordinator", botId: "bot-0", message: "去 C:\\Users\\private\\save" } },
    { sequence: 2, type: "role.shared", worldId: "world-a", data: { role: "gatherer", botId: "bot-1", recipients: ["builder"], message: "我发现了铁矿" } },
    { sequence: 3, type: "role.failure", worldId: "world-a", data: { role: "builder", reason: "secret=abc /tmp/private" } },
    { sequence: 4, type: "model.trace", worldId: "world-a", data: { thought: "must never be exposed" } },
  ];
  const view = makeUiView(status, events);
  assert.equal(view.schemaVersion, 2);
  assert.equal(view.companions.length, 3);
  assert.equal(view.companions[0].inventory.length, 36);
  assert.equal(view.companions[0].action, "MINE");
  assert.equal(view.tasks.length, 0);
  assert.equal(view.work.length, 3);
  assert.equal(view.messages.length, 2);
  assert.equal(view.messages.some(message => message.type === "model.trace"), false);
  assert.match(view.messages[0].message, /\[path\]/);
  assert.doesNotMatch(JSON.stringify(view), /private|abc|thought|modelCatalogPath/);
  assert.equal((view as unknown as Record<string, unknown>).privateRawStatus, undefined);
});

test("malformed or missing inputs fail closed to unknown and never throw", () => {
  const view = makeUiView({ game: { companions: [{ botId: "bot-1", inventory: [{ slot: 0, item: "x", count: 1 }] }] }, roles: [{}] }, [{ type: "owner.command", data: null }, null]);
  assert.equal(view.companions.length, 1);
  assert.equal(view.companions[0].name, "unknown");
  assert.equal(view.companions[0].health, "unknown");
  assert.equal(view.companions[0].position, "unknown");
  assert.equal(view.diagnostics.game.status, "unknown");
  assert.equal(view.diagnostics.controller.status, "unknown");
  assert.equal(view.diagnostics.appServer.status, "unknown");
  assert.equal(view.diagnostics.roles[0].model, "unknown");
  assert.equal(view.messages.length, 0);
});

test("live bodies without an action are shown as idle and fixture role names remain visible before binding", () => {
  const view = makeUiView({ game: { companions: [
    { botId: "captain", name: "P2_CAPTAIN_ui", dimension: "minecraft:overworld", health: 20, food: 20, inventory: [], position: { x: 0, y: 64, z: 0 } },
    { botId: "gatherer", name: "P2_GATHERER_ui", dimension: "minecraft:overworld", health: 20, food: 20, inventory: [], position: { x: 1, y: 64, z: 0 } },
    { botId: "builder", name: "P2_BUILDER_ui", dimension: "minecraft:overworld", health: 20, food: 20, inventory: [], position: { x: 2, y: 64, z: 0 } },
  ] } }, []);
  assert.deepEqual(view.companions.map(companion => companion.role), ["coordinator", "gatherer", "builder"]);
  assert.deepEqual(view.companions.map(companion => companion.action), ["当前无身体动作", "当前无身体动作", "当前无身体动作"]);
});

test("GATHER body actions use the Chinese continuous-woodcutting label", () => {
  const view = makeUiView({ game: { companions: [{
    botId: "gatherer", name: "采集者", dimension: "minecraft:overworld", health: 20, food: 20,
    inventory: [], position: { x: 0, y: 64, z: 0 }, action: { payload: { kind: "GATHER" }, state: "RUNNING" },
  }] } }, []);
  assert.equal(view.companions[0].action, "连续采木");
});

test("game diagnostic status reflects real pause and stop state while keeping stage as detail", () => {
  const base = { stage: "P2_DEVELOPMENT", worldId: "world-a", companions: [
    { botId: "bot-1", name: "伙伴", dimension: "minecraft:overworld", health: 20, food: 20, inventory: [], position: { x: 0, y: 64, z: 0 } },
  ] };
  assert.equal(makeUiView({ game: base }, []).diagnostics.game.status, "RUNNING");
  assert.equal(makeUiView({ game: { ...base, companions: [{ ...base.companions[0], paused: true }] } }, []).diagnostics.game.status, "PAUSED");
  assert.equal(makeUiView({ game: { ...base, companions: [{ ...base.companions[0], paused: true, stopped: true }] } }, []).diagnostics.game.status, "STOPPED");
  const unknown = makeUiView({ game: { stage: "P2_DEVELOPMENT", companions: [] } }, []).diagnostics.game;
  assert.equal(unknown.status, "unknown");
  assert.equal(unknown.details.stage, "P2_DEVELOPMENT");
});

test("body action journal never substitutes for the current task", () => {
  const status = { game: { companions: [{ botId: "bot-1", name: "伙伴", bodyGeneration: 4, inventory: [],
    actionJournal: [{ id: { value: "intent:gather-oak" }, state: "RUNNING", gameTick: 12,
      epoch: { bodyGeneration: 4 }, payload: { kind: "MINE", position: { x: 4, y: 101, z: 8 }, count: 1, steps: [] } }],
  }] } };
  const view = makeUiView(status, []);
  assert.equal(view.tasks.length, 0);
  assert.equal(view.work[0].actionId, "");
});

test("model final and internal message events never become public speech", () => {
  const view = makeUiView({}, [
    { sequence: 1, type: "role.final", data: { role: "gatherer", message: "已完成采集" } },
    { sequence: 2, type: "role.message", data: { role: "builder", text: "准备建造" } },
    { sequence: 3, type: "model.trace", data: { thought: "private" } },
  ]);
  assert.deepEqual(view.messages, []);
});

test("squad owner commands linked to one UI request appear once in chat", () => {
  const view = makeUiView({}, [
    { sequence: 1, type: "ui.claim", data: { role: "系统", requestId: "ui-1", operation: "command", decision: "CLAIMED" } },
    { sequence: 2, type: "owner.command", data: { role: "coordinator", botId: "bot-1", uiRequestId: "ui-1", message: "一起采木" } },
    { sequence: 3, type: "owner.command", data: { role: "gatherer", botId: "bot-2", uiRequestId: "ui-1", message: "一起采木" } },
    { sequence: 4, type: "owner.command", data: { role: "builder", botId: "bot-3", uiRequestId: "ui-1", message: "一起采木" } },
    { sequence: 5, type: "ui.failure", data: { role: "系统", requestId: "ui-2", operation: "command", stage: "command", reason: "no available companion" } },
  ]);
  assert.deepEqual(view.messages.map(message => message.message), ["一起采木"]);
  assert.equal(view.messages[0].requestId, "ui-1");
  assert.match(String(view.diagnostics.controller.details.lastError), /no available companion/);
});

test("task projection joins ledger and binding data while capping tasks", () => {
  const works = Array.from({ length: 30 }, (_, index) => ({ taskId: `task-${index}`, botId: "bot-1", kind: "WAIT" }));
  const ledgerTasks = Array.from({ length: 30 }, (_, index) => ({ task: { id: { value: `task-${index}` } }, state: index === 0 ? "PARTIAL" : index === 29 ? "RUNNING" : "COMPLETED", updatedGameTick: index }));
  const status = { game: { companions: [], team: { works, ledger: { tasks: ledgerTasks }, bindings: [{ taskId: "task-29", actionId: "action-29" }] } } };
  const view = makeUiView(status, []);
  assert.equal(view.tasks.length, 0);
  assert.equal(view.work.length, 0);
});

test("worst-case Chinese payload is hard-capped while retaining bodies and diagnostic roles", () => {
  const long = "伙伴状态消息".repeat(100);
  const companions = Array.from({ length: 3 }, (_, index) => ({
    botId: `bot-${index}`, name: long, dimension: long,
    position: { x: index, y: 64, z: 0 }, health: 20, food: 20,
    inventory: Array.from({ length: 36 }, (_, slot) => ({ slot, item: long, count: 64 })),
    action: { payload: { kind: "BUILD" }, state: "RUNNING" },
  }));
  const works = Array.from({ length: 24 }, (_, index) => ({ taskId: `task-${index}`, botId: "bot-0", intentId: "intent", bodyGeneration: 1, kind: "BUILD", steps: Array.from({ length: 16 }, () => ({ position: { x: 1, y: 2, z: 3 }, block: long })) }));
  const ledger = { tasks: works.map((work, index) => ({ task: { id: { value: work.taskId } }, state: index === 0 ? "RUNNING" : "COMPLETED", updatedGameTick: index })) };
  const roles = Array.from({ length: 3 }, (_, index) => ({ role: `role-${index}`, botId: `bot-${index}`, status: "working", model: "gpt-5.6-luna", reasoningEffort: "high", serviceTier: "priority" }));
  const events = Array.from({ length: 30 }, (_, index) => ({ sequence: index, type: "role.shared", data: { role: "coordinator", botId: "bot-0", recipients: ["builder"], message: long } }));
  const view = makeUiView({ controller: "running", game: { worldId: "world-a", gameTick: 1, companions, team: { works, ledger, bindings: [] } }, roles, appServer: { appServer: "connected" } }, events);
  assert.equal(view.truncated, true);
  assert.equal(view.companions.length, 3);
  assert.equal(view.diagnostics.roles.length, 3);
  assert.ok(new TextEncoder().encode(JSON.stringify(view)).byteLength <= 60 * 1024);
});

test("local water phase and air remain visible while Luna is waiting",()=>{
 const view=makeUiView({game:{worldId:"w",companions:[{botId:"b",name:"Ember",travel:{active:true,phase:"surfacing",air:45}}]},roles:[{botId:"b",activity:"thinking"}]},[]);
 assert.equal(view.companions[0].activity,"surfacing");assert.match(String(view.companions[0].waitReason),/45/);
});
