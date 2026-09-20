import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AGENT_PROFILES, type StartedThread, type TurnHandle } from "../src/app-server-client.js";
import { CrewController, type BrainPort, type WorldView } from "../src/crew-controller.js";
import { CrewJournal } from "../src/crew-journal.js";
import type { ModConnection } from "../src/mod-connection.js";

const context = { protocol: "hearthcrew.v1" as const, worldId: "world-a", sessionEpoch: 1 };
const baseView = (action: unknown = null, worldId = context.worldId): WorldView => ({ worldId, gameTick: 1, companions: [{
  botId: "bot-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld", position: { x: 0, y: 64, z: 0 }, inventory: [], autonomyEnabled: false, action,
}] });
const squadView = (worldId = context.worldId): WorldView => ({
  worldId, gameTick: 1, companions: ["bot-1", "bot-2", "bot-3"].map((botId, index) => ({
    botId, bodyGeneration: 7 + index, name: `伙伴${index + 1}`, dimension: "minecraft:overworld",
    position: { x: index, y: 64, z: 0 }, inventory: [], autonomyEnabled: false, action: null,
  })),
});

function deferred<T>(): { promise: Promise<T>; resolve: (value?: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value?: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = value => res(value as T); reject = rej; });
  return { promise, resolve, reject };
}

class FakeConnection extends EventEmitter {
  connectionState = "ready" as const;
  sessionContext;
  readonly calls: Array<{ op: string; body: any; options: any }> = [];
  private eventHandler?: (event: any) => Promise<boolean> | boolean;
  observeResult?: Promise<WorldView>;
  statusResult?: Promise<WorldView>;
  reconcileError?: Error;
  cancelResult?: Promise<unknown>;
  teamExecuteResult?: unknown;
  uiClaimResult?: unknown;
  readonly uiUpdates: unknown[] = [];
  readonly teamTasks = new Set<string>();
  readonly teamIntents = new Map<string, string>();
  constructor(public view: WorldView) { super(); this.sessionContext = { ...context, worldId: view.worldId }; }
  onEvent(handler: (event: any) => Promise<boolean> | boolean): this { this.eventHandler = handler; return this; }
  close(): void { this.connectionState = "ready"; }
  async request<T>(op: string, body: unknown, options: unknown = {}): Promise<T> {
    this.calls.push({ op, body, options });
    if (op === "reconcile") { if (this.reconcileError) throw this.reconcileError; return structuredClone(this.view) as T; }
    if (op === "status") return (this.statusResult ?? Promise.resolve(structuredClone(this.view))) as Promise<T>;
    if (op === "observe") return (this.observeResult ?? Promise.resolve(structuredClone(this.view))) as Promise<T>;
    if (op === "intent.bind" || op === "chat.publish") return { accepted: true } as T;
    if (op === "action.cancel") return (this.cancelResult ?? Promise.resolve({ state: "CANCELLED" })) as Promise<T>;
    if (op === "action.submit") {
      const b=this.view.companions.find(b=>b.botId===(body as any).botId);
      if(b)b.action={id:{value:(options as any).actionId},state:"RUNNING",payload:body};
      return { state: "ACCEPTED", actionId: (options as any).actionId } as T;
    }
    if (op === "team.propose") {
      const taskId = (body as any).taskId as string;
      const intentId = (options as any).intentId as string;
      const previousIntent = this.teamIntents.get(taskId);
      if (previousIntent !== undefined && previousIntent !== intentId) throw new Error("task owner intent does not match");
      const decision = this.teamTasks.has(taskId) ? "IDEMPOTENT_REPLAY" : "ACCEPTED";
      this.teamTasks.add(taskId);
      this.teamIntents.set(taskId, intentId);
      return { decision, task: { id: { value: taskId } } } as T;
    }
    if (op === "team.execute") {
      const taskId = (body as any).taskId as string;
      if (this.teamIntents.get(taskId) !== (options as any).intentId) throw new Error("task owner intent does not match");
      if (this.teamExecuteResult !== undefined) return this.teamExecuteResult as T;
      return { state: "ACCEPTED", id: { value: `team-${taskId}` }, actionId: `team-${taskId}` } as T;
    }
    if (op === "ui.claim") return (this.uiClaimResult ?? { decision: "CLAIMED", operation: "status", message: "" }) as T;
    if (op === "ui.update") { this.uiUpdates.push(structuredClone(body)); return { accepted: true } as T; }
    if (op === "control") {
      for(const b of this.view.companions.filter(b=>!(body as any).botId || b.botId===(body as any).botId)) {
        if((body as any).operation==="pause")b.paused=true;
        if((body as any).operation==="resume" || (body as any).operation==="retask") { b.paused=false;b.stopped=false; }
        if((body as any).operation==="retask")b.action=null;
      }
      return {count:1} as T;
    }
    throw new Error(`unexpected op ${op}`);
  }
  fireEvent(event: any): Promise<boolean> {
    if(event.event==="action.terminal") {
      const b=this.view.companions.find(b=>b.botId===event.body.botId);
      if(b && (b.action as any)?.id?.value===event.body.id?.value)b.action=null;
    }
    return this.eventHandler ? Promise.resolve(this.eventHandler(event)) : Promise.resolve(false); }
}

class FakeBrain implements BrainPort {
  readonly created: StartedThread[] = [];
  readonly turns: Array<{ handle: TurnHandle; resolve: (value?: unknown) => void }> = [];
  failCreate = false;
  createThreadWait?: Promise<void>;
  createThreadStarted = false;
  startTurnWait?: Promise<void>;
  readonly inputs: string[] = [];
  readonly interrupts: string[] = [];
  interruptResolves = true;
  beforeIdleCompletion?: (handle: TurnHandle) => void;
  async createThread(id: StartedThread["profile"]["id"]): Promise<StartedThread> {
    if (this.failCreate) throw new Error("login required");
    this.createThreadStarted = true;
    if (this.createThreadWait) await this.createThreadWait;
    const profile = AGENT_PROFILES.find(candidate => candidate.id === id)!;
    const thread = { profile, threadId: `thread-${id}-${this.created.length}`, model: profile.model, reasoningEffort: profile.reasoningEffort, effectiveServiceTier: "default" };
    this.created.push(thread); return thread;
  }
  async startTurn(thread: StartedThread, _input: string, _profile?: StartedThread["profile"], _generation?: number): Promise<TurnHandle> {
    this.inputs.push(_input);
    const completion = deferred<unknown>();
    const handle: TurnHandle = { threadId: thread.threadId, turnId: `turn-${this.turns.length}`, completion: completion.promise, interrupt: async () => { this.interrupts.push(handle.turnId); if (this.interruptResolves) completion.resolve({ status: "interrupted" }); } };
    this.turns.push({ handle, resolve: value => { if(value === undefined)this.beforeIdleCompletion?.(handle); completion.resolve(value ?? { status: "completed" }); } });
    if (this.startTurnWait) await this.startTurnWait;
    return handle;
  }
  diagnostics(): unknown { return { fake: true }; }
}

async function setup(view = baseView(), turnTimeoutMs = 5000): Promise<{ controller: CrewController; journal: CrewJournal; connection: FakeConnection; brain: FakeBrain; cleanup: () => Promise<void> }> {
  const directory = await mkdtemp(join(tmpdir(), "hearthcrew-controller-"));
  const journal = new CrewJournal(join(directory, "crew.jsonl"));
  await journal.load();
  const controller = new CrewController(journal, turnTimeoutMs, [10, 20]);
  const connection = new FakeConnection(view);
  const brain = new FakeBrain();
  brain.beforeIdleCompletion = handle => {
    const role = [...(controller as any).roles.values()].find((r:any)=>r.thread.threadId===handle.threadId) as any;
    if(role?.run && !role.run.submitted && !role.run.goalState) { role.run.goalState="blocked"; role.run.goalReason="fixture waiting for explicit event"; }
  };
  await controller.initialize(); await controller.setBrain(brain); await controller.attach(connection as unknown as ModConnection);
  return { controller, journal, connection, brain, cleanup: async () => { connection.emit("disconnect"); for(const turn of brain.turns)turn.resolve({status:"interrupted"}); await new Promise(resolve=>setTimeout(resolve,20)); await rm(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 }); } };
}

async function waitFor(read: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt++) { if (read()) return; await new Promise(resolve => setTimeout(resolve, 2)); }
  throw new Error("test condition timed out");
}

const uiRequest = (operation: "status" | "command" | "pause" | "resume" | "stop", requestId = "11111111-1111-4111-8111-111111111111") => ({
  ...context, kind: "event" as const, eventId: `ui-${requestId}-${operation}`, event: "ui.request",
  body: { requestId, ownerId: "22222222-2222-4222-8222-222222222222", uiSession: "33333333-3333-4333-8333-333333333333", operation, message: operation === "command" ? "查看小队状态" : "" },
});

// 0.2.12: deterministic fake transport/brain; no live game or model.
test("quiet preparation shares leave action/query budgets and teammate execution untouched",async()=>{
 const s=await setup(squadView());try{
  await s.controller.command("准备工具","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const h=s.brain.turns[0].handle;
  for(let i=0;i<5;i++){
   const response=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"share",arguments:{message:"制作了4根木棍"}}) as any;
   assert.equal(response.success,true);assert.equal(JSON.parse(response.contentItems[0].text).public,false);
  }
  const role=(s.controller as any).roles.get("coordinator");assert.equal(role.run.publicCount??0,0);assert.equal(role.run.queries??0,0);
  assert.equal(s.brain.turns.length,1);assert.equal(s.connection.calls.some(c=>c.op==="chat.publish"),false);
  assert.equal((await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"act",callId:"continue-after-silence",arguments:{kind:"WAIT",parameters:{count:1}}}) as any).success,true);
 }finally{await s.cleanup();}
});

test("team inventory reads cache by incarnation/version, use directed budget and never wake teammate",async()=>{
 const v=squadView();v.companions[1].inventory=[{slot:12,item:"minecraft:stick",count:4}];const s=await setup(v);try{
  await s.controller.command("准备工具","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const h=s.brain.turns[0].handle;
  const query=async(companion="bot-2")=>await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"observe",arguments:{teamInventory:{companion}}}) as any;
  assert.equal((await query("player")).success,false);
  assert.equal(JSON.parse((await query()).contentItems[0].text).slots[0].count,4);
  await query();const role=(s.controller as any).roles.get("coordinator");assert.equal(role.run.queries,1);
  s.connection.view.companions[1].dimension="minecraft:the_nether";
  assert.equal(JSON.parse((await query()).contentItems[0].text).sameDimension,false);
  s.connection.view.companions[1].bodyGeneration++;s.connection.view.companions[1].inventory=[];
  assert.equal(JSON.parse((await query()).contentItems[0].text).inventoryState,"empty");
  (s.connection.view.companions[1] as any).inventory=undefined;
  assert.equal(JSON.parse((await query()).contentItems[0].text).inventoryState,"unknown");
  s.connection.view.companions[1].inventory=[{slot:1,item:"minecraft:coal",count:1}];
  assert.equal(JSON.parse((await query()).contentItems[0].text).budgetExhausted,true);
  assert.equal(role.run.queries,4);assert.equal(s.brain.turns.length,1);
  assert.equal(s.connection.calls.some(c=>["action.submit","action.cancel","chat.publish"].includes(c.op)),false);
  assert.equal(JSON.parse(s.brain.inputs[0]).world?.companions?.find((b:any)=>b.botId==="bot-2")?.inventory,undefined);
 }finally{await s.cleanup();}
});

test("material subscriptions wake only the waiting role on threshold change, retain pause and dedup evidence",async()=>{
 const v=squadView();v.companions[1].inventory=[{slot:0,item:"minecraft:stick",count:1}];const s=await setup(v);try{
  await s.controller.command("等队友的4根木棍","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const h=s.brain.turns[0].handle;
  assert.equal((await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"propose_work",arguments:{operation:"goal_state",state:"blocked",reason:"等待木棍",condition:{kind:"materials",resource:"minecraft:stick",count:4,peerId:"bot-2"}}}) as any).success,true);
  s.brain.turns[0].resolve();const role=(s.controller as any).roles.get("coordinator");await waitFor(()=>!role.run);
  const wake=async(id:string)=>await s.connection.fireEvent({...context,kind:"event",eventId:id,event:"body.wakeup",body:{botId:"bot-2",bodyGeneration:8,reasons:["inventory_changed"]}});
  s.connection.view.companions[1].inventory=[{slot:7,item:"minecraft:stick",count:3}];await wake("below");assert.equal(s.brain.turns.length,1);
  role.status="paused";s.connection.view.companions[1].inventory=[{slot:7,item:"minecraft:stick",count:4}];await wake("paused");assert.equal(s.brain.turns.length,1);
  role.status="idle";await wake("available");await waitFor(()=>s.brain.turns.length===2);
  assert.match(s.brain.inputs[1],/resource_condition_changed/);await wake("duplicate");assert.equal(s.brain.turns.length,2);
  assert.equal(s.journal.rows().filter(r=>r.type==="resource.condition_changed").length,1);
  assert.equal(s.journal.rows().some(r=>r.type==="crew.chat"),false);
  assert.ok(s.journal.rows().some(r=>r.type==="resource.condition_baseline"));
  const restored=new CrewController(s.journal);await restored.initialize();
  assert.equal((restored as any).goalWaits.has(`${context.worldId}:bot-1`),false);
  assert.ok((restored as any).materialSignals.size>0);
 }finally{await s.cleanup();}
});

test("ui status request claims once, refreshes without starting a model turn, and sends a bounded view", async () => {
  const setupState = await setup();
  try {
    setupState.connection.uiClaimResult = { decision: "CLAIMED", operation: "status", message: "" };
    assert.equal(await setupState.connection.fireEvent(uiRequest("status")), true);
    assert.equal(setupState.brain.turns.length, 0);
    assert.equal(setupState.connection.calls.filter(call => call.op === "ui.claim").length, 1);
    assert.equal(setupState.connection.calls.filter(call => call.op === "ui.update").length, 1);
    const update = JSON.parse((setupState.connection.uiUpdates[0] as any).json);
    assert.equal(update.ok, true);
    assert.equal(update.view.schemaVersion, 2);
    assert.equal(typeof update.view.truncated, "boolean");
  } finally { await setupState.cleanup(); }
});

test("ui view keeps a public owner command after refresh-only journal noise", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { role: "coordinator", botId: "bot-1", message: "去采集木头" });
    for (let index = 0; index < 50; index += 1) {
      await setupState.journal.append("game.event", context.worldId, { eventId: `refresh-${index}`, event: "ui.request", body: {} });
    }
    setupState.connection.uiClaimResult = { decision: "CLAIMED", operation: "status", message: "" };
    assert.equal(await setupState.connection.fireEvent(uiRequest("status", "55555555-5555-4555-8555-555555555555")), true);
    const update = JSON.parse((setupState.connection.uiUpdates[0] as any).json);
    assert.equal(update.ok, true);
    assert.equal(update.view.messages.some((message: any) => message.type === "owner.command" && message.message === "去采集木头"), true);
  } finally { await setupState.cleanup(); }
});

test("normal final text and reasoning are never implicitly published", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("观察并报告附近情况", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve({ status: "completed", items: [
      { type: "reasoning", text: "private reasoning must not be published" },
      { type: "agentMessage", id: "msg-1", phase: "final_answer", text: "已观察完成，附近暂时安全。" },
    ] });
    await waitFor(() => setupState.journal.rows(context.worldId).some(row => row.type === "turn.ended"));
    const rows = setupState.journal.rows(context.worldId).filter(row => row.type === "crew.chat");
    assert.equal(rows.length, 0);
    assert.equal(JSON.stringify(rows).includes("private reasoning"), false);
  } finally { await setupState.cleanup(); }
});

test("duplicate ui command events share one claim/mutation and stale claims have no effect", async () => {
  const setupState = await setup();
  try {
    setupState.connection.uiClaimResult = { decision: "CLAIMED", operation: "command", message: "查看小队状态" };
    const request = uiRequest("command");
    const [first, second] = await Promise.all([
      setupState.connection.fireEvent(request),
      setupState.connection.fireEvent({ ...request, eventId: "ui-command-duplicate" }),
    ]);
    assert.equal(first, true); assert.equal(second, true);
    assert.equal(setupState.connection.calls.filter(call => call.op === "ui.claim").length, 1);
    assert.equal(setupState.connection.calls.filter(call => call.op === "control").length, 1);
    assert.equal(setupState.connection.uiUpdates.length, 1);
    for (const turn of setupState.brain.turns) turn.resolve();

    setupState.connection.uiClaimResult = { decision: "STALE", operation: "command", message: "查看小队状态" };
    const beforeControl = setupState.connection.calls.filter(call => call.op === "control").length;
    await setupState.connection.fireEvent(uiRequest("command", "44444444-4444-4444-8444-444444444444"));
    assert.equal(setupState.connection.calls.filter(call => call.op === "control").length, beforeControl);
    const staleUpdate = JSON.parse((setupState.connection.uiUpdates.at(-1) as any).json);
    assert.equal(staleUpdate.ok, false);
    assert.match(staleUpdate.error, /过期|重复/);
    const diagnostics = setupState.journal.rows(context.worldId).filter(row => row.type === "ui.claim" || row.type === "ui.failure");
    assert.equal(diagnostics.filter(row => row.type === "ui.claim").length, 2);
    assert.equal(diagnostics.filter(row => row.type === "ui.failure").length, 1);
    assert.equal((diagnostics.at(-1) as any).data.reason, "界面请求已过期或重复");
  } finally { await setupState.cleanup(); }
});

test("fresh connected UI command binds all available bodies without an MCP status call", async () => {
  const setupState = await setup(squadView());
  try {
    const request = uiRequest("command", "66666666-6666-4666-8666-666666666666");
    setupState.connection.uiClaimResult = { decision: "CLAIMED", operation: "command", message: request.body.message };
    assert.equal(await setupState.connection.fireEvent(request), true);
    const ownerRows = setupState.journal.rows(context.worldId).filter(row => row.type === "owner.command");
    assert.equal(ownerRows.length, 3);
    assert.equal(ownerRows.every(row => row.data.uiRequestId === request.body.requestId), true);
    assert.equal(setupState.journal.rows(context.worldId).filter(row => row.type === "ui.claim").length, 1);
    await waitFor(() => setupState.brain.turns.length === 3);
    assert.equal(setupState.connection.calls.filter(call => call.op === "control").length, 3);
  } finally { await setupState.cleanup(); }
});

test("UI command exposes no-body and unavailable-body rejection reasons", async () => {
  for (const view of [
    { ...baseView(), companions: [] },
    { ...baseView(), companions: [{ ...baseView().companions[0], recoveryInvalid: true, stopped: true }] },
  ]) {
    const setupState = await setup(view);
    try {
      const requestId = view.companions.length === 0 ? "77777777-7777-4777-8777-777777777777" : "88888888-8888-4888-8888-888888888888";
      const request = uiRequest("command", requestId);
      setupState.connection.uiClaimResult = { decision: "CLAIMED", operation: "command", message: request.body.message };
      assert.equal(await setupState.connection.fireEvent(request), true);
      const update = JSON.parse((setupState.connection.uiUpdates.at(-1) as any).json);
      assert.equal(update.ok, false);
      assert.equal(update.view.messages.some((message: any) => message.type === "ui.failure"), false);
      assert.match(String(update.view.diagnostics.controller.details.lastError), /伙伴/);
      const failure = setupState.journal.rows(context.worldId).find(row => row.type === "ui.failure");
      assert.equal(typeof failure?.data.reason, "string");
      assert.equal(String(failure?.data.reason).includes("伙伴"), true);
    } finally { await setupState.cleanup(); }
  }
});

test("resume controls stopped unbound bodies, then a new squad command can bind them", async () => {
  const stopped = squadView();
  stopped.companions = stopped.companions.map(body => ({ ...body, stopped: true }));
  const setupState = await setup(stopped);
  try {
    const resumed = await setupState.controller.control("resume") as any;
    assert.deepEqual(resumed, { operation: "resume", count: 3 });
    assert.deepEqual(setupState.connection.calls.filter(call => call.op === "control").map(call => call.body), [
      { operation: "resume", botId: "bot-1" },
      { operation: "resume", botId: "bot-2" },
      { operation: "resume", botId: "bot-3" },
    ]);
    assert.equal(setupState.brain.created.length, 3);

    setupState.connection.view = squadView();
    const command = await setupState.controller.command("恢复后一起工作") as any;
    assert.equal(command.accepted, true);
    assert.equal(command.count, 3);
    assert.equal(setupState.brain.created.length, 3);
  } finally { await setupState.cleanup(); }
});

test("ui event with an unknown session epoch is rejected before claim or mutation", async () => {
  const setupState = await setup();
  try {
    const event = { ...uiRequest("stop"), sessionEpoch: 99 };
    assert.equal(await setupState.connection.fireEvent(event), false);
    assert.equal(setupState.connection.calls.some(call => call.op === "ui.claim" || call.op === "control"), false);
  } finally { await setupState.cleanup(); }
});

test("owner retask clears active and suspended work, then command/control mutations serialize", async () => {
  const active = { id: { value: "old-action" }, state: "RUNNING" };
  const setupState = await setup(baseView(active));
  try {
    await setupState.journal.append("action.prepared", context.worldId, { botId: "bot-1", actionId: "old-action", intentId: "old-intent", fingerprint: "old" });
    // The controller was initialized before this fixture row: rebuild it to
    // exercise the same persisted recovery path used after a restart.
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain); await controller.attach(setupState.connection as unknown as ModConnection);
    const stop = deferred<unknown>();
    let stopCalls = 0;
    const originalRequest = setupState.connection.request.bind(setupState.connection);
    setupState.connection.request = async (op: string, body: unknown, options: unknown = {}) => {
      if (op === "control" && (body as any).operation === "retask" && stopCalls++ === 0) {
        setupState.connection.calls.push({ op, body, options }); return stop.promise as any;
      }
      return originalRequest(op, body, options);
    };
    const first = controller.command("立即改去建基地", "bot-1");
    await waitFor(() => stopCalls === 1);
    const second = controller.control("pause", "bot-1");
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(setupState.connection.calls.filter(call => call.op === "status").length, 1);
    stop.resolve({ count: 1, operation: "stop" });
    await first; await second;
    assert.deepEqual(setupState.connection.calls.filter(call => call.op === "control").map(call => call.body.operation), ["retask", "pause"]);
  } finally { await setupState.cleanup(); }
});

test("canonical action identity is stable across parameter key order", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("执行动作", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const response = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: {
      intentId: (command as any).intentId, actionId: "canonical", kind: "CRAFT", parameters: { resource: "minecraft:oak_planks", position: { z: 3, x: 1, y: 64 }, count: 1 },
    } });
    assert.equal((response as any).success, true);
    const row = setupState.journal.rows().find(candidate => candidate.type === "action.prepared")!;
    assert.equal(row.data.fingerprint, JSON.stringify({ botId: "bot-1", count: 1, kind: "CRAFT", position: { x: 1, y: 64, z: 3 }, resource: "minecraft:oak_planks" }));
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("rejected capacity receipt cannot leave a phantom working action", async () => {
  const s=await setup();
  try {
    await s.controller.command("执行动作","bot-1");
    await waitFor(()=>s.brain.turns.length===1);
    const original=s.connection.request.bind(s.connection);
    s.connection.request=async (op:string,body:unknown,options:unknown={}) => op==="action.submit" ? {state:"STALE",decision:"REJECTED_CAPACITY",message:"retention full"} as any : original(op,body,options);
    const t=s.brain.turns[0].handle;
    const result=await s.controller.toolCall({threadId:t.threadId,turnId:t.turnId,tool:"act",arguments:{kind:"WAIT",parameters:{}}});
    assert.equal((result as any).success,false);
    assert.equal((s.controller.diagnosticSnapshot() as any).roles[0].action,undefined);
    assert.equal((s.controller.diagnosticSnapshot() as any).roles[0].status,"thinking");
    s.brain.turns[0].resolve();
  } finally {await s.cleanup();}
});

test("act without intentId binds the current intent and sends a stable action envelope", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("执行当前任务", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const mismatched = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      intentId: "legacy-intent", actionId: "stable-local-id", kind: "WAIT", parameters: {},
    } });
    assert.equal((mismatched as any).success, false);
    const result = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "stable-local-id", kind: "WAIT", parameters: {},
    } });
    assert.equal((result as any).success, true);
    const submitted = setupState.connection.calls.find(call => call.op === "action.submit")!;
    assert.equal(submitted.options.intentId, command.intentId);
    assert.equal(submitted.options.actionId, `${command.intentId}:stable-local-id`);
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("BUILD action identity rejects the same id with a different blueprint", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("搭建小墙", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const firstSteps = { steps: [{ position: { x: 1, y: 64, z: 1 }, block: "minecraft:oak_planks" }] };
    const accepted = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "wall", kind: "BUILD", parameters: firstSteps,
    } }) as any;
    assert.equal(accepted.success, true);
    first && setupState.brain.turns[0].resolve();
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "build-completed", event: "action.terminal", body: {
      botId: "bot-1", id: { value: `${command.intentId}:wall` }, state: "COMPLETED",
    } });
    await waitFor(() => setupState.brain.turns.length === 2);
    const second = setupState.brain.turns[1].handle;
    const conflicting = await setupState.controller.toolCall({ threadId: second.threadId, turnId: second.turnId, tool: "act", arguments: {
      actionId: "wall", kind: "BUILD", parameters: { steps: [{ position: { x: 2, y: 64, z: 1 }, block: "minecraft:oak_planks" }] },
    } }) as any;
    assert.equal(conflicting.success, false);
    assert.match(String(conflicting.contentItems?.[0]?.text), /action identity conflicts/);
    assert.equal(setupState.connection.calls.filter(call => call.op === "action.submit").length, 1);
    second && setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("invalid action parameters do not consume the turn and allow a corrected act", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("修正动作参数", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const invalid = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: {
      actionId: "bad-move", kind: "MOVE", parameters: { target: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((invalid as any).success, false);
    assert.equal(setupState.connection.calls.some(call => call.op === "action.submit"), false);
    assert.equal(setupState.journal.rows().some(row => row.type === "action.prepared"), false);
    const corrected = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: {
      actionId: "good-move", kind: "MOVE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((corrected as any).success, true);
    assert.equal(setupState.connection.calls.filter(call => call.op === "action.submit").length, 1);
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("GATHER sends only the server-defined tree request and preserves real collected receipt fields", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("采集一棵橡树", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const originalRequest = setupState.connection.request.bind(setupState.connection);
    setupState.connection.request = async (op: string, body: unknown, options: unknown = {}) => {
      if (op === "action.submit") {
        setupState.connection.calls.push({ op, body, options });
        return { state: "ACCEPTED", actionId: (options as any).actionId, requested: 4, collected: 3 } as any;
      }
      return originalRequest(op, body, options);
    };
    const result = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: {
      actionId: "gather-oak", kind: "GATHER", parameters: { resource: "minecraft:oak_log", count: 4 },
    } }) as any;
    assert.equal(result.success, true);
    const receipt = JSON.parse(result.contentItems[0].text);
    assert.deepEqual({ state: receipt.state, requested: receipt.requested, collected: receipt.collected }, { state: "ACCEPTED", requested: 4, collected: 3 });
    const submitted = setupState.connection.calls.find(call => call.op === "action.submit")!;
    assert.equal(receipt.actionId, submitted.options.actionId);
    assert.match(receipt.actionId, /^[0-9a-f-]{36}:gather-oak$/);
    assert.deepEqual(submitted.body, { botId: "bot-1", kind: "GATHER", resource: "minecraft:oak_log", count: 4, intentGeneration: 1 });
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("matching action terminal yields a delayed model turn before starting action_result planning", async () => {
  const setupState = await setup(undefined, 5000);
  try {
    const command = await setupState.controller.command("采集并继续", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:mine-oak`;
    const result = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      intentId: command.intentId, actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((result as any).success, true);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-current", event: "action.terminal", body: {
      botId: "bot-1", id: { value: actionId }, state: "COMPLETED", decision: "COMPLETED",
    } });
    setupState.brain.turns[0].resolve({status:"interrupted"});
    await waitFor(() => setupState.brain.turns.length === 2);
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("a late terminal from the previous action cannot interrupt a newer model turn", async () => {
  const setupState = await setup(undefined, 5000);
  try {
    const command = await setupState.controller.command("先完成动作", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:mine-oak`;
    await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      intentId: command.intentId, actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-first", event: "action.terminal", body: { botId: "bot-1", id: { value: actionId }, state: "COMPLETED" } });
    await waitFor(() => setupState.brain.turns.length === 2);
    const second = setupState.brain.turns[1].handle;
    await setupState.controller.toolCall({ threadId: second.threadId, turnId: second.turnId, tool: "act", arguments: {
      actionId: "newer-action", kind: "WAIT", parameters: {},
    } });
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-late", event: "action.terminal", body: { botId: "bot-1", id: { value: actionId }, state: "COMPLETED" } });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    assert.equal(setupState.brain.turns.length, 2);
    second && setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("retained current-intent terminal queues result behind a reconnected run with no action", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("断线后继续", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const reconnected = new FakeConnection(baseView(null, context.worldId));
    await setupState.controller.attach(reconnected as unknown as ModConnection);
    await waitFor(() => setupState.brain.turns.length === 2);
    const second = setupState.brain.turns[1].handle;
    await reconnected.fireEvent({ ...context, kind: "event", eventId: "terminal-replayed", event: "action.terminal", body: {
      botId: "bot-1", id: { value: `${command.intentId}:old-action` }, state: "COMPLETED",
    } });
    assert.deepEqual(setupState.brain.interrupts, []);
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").pending.has("action_result"));
    second && setupState.brain.turns[1].resolve();
    await waitFor(() => setupState.brain.turns.length === 3);
    setupState.brain.turns[2].resolve();
  } finally { await setupState.cleanup(); }
});

test("terminal from a different body generation queues reobserve without interrupting the current turn", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("核对身体代次", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:generation-check`;
    await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "generation-check", kind: "WAIT", parameters: {},
    } });
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-wrong-body", event: "action.terminal", body: {
      botId: "bot-1", id: { value: actionId }, state: "COMPLETED", epoch: { bodyGeneration: 8 },
    } });
    assert.deepEqual(setupState.brain.interrupts, []);
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").pending.has("reobserve_body_generation"));
    first && setupState.brain.turns[0].resolve();
    await waitFor(() => setupState.brain.turns.length === 2);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("terminal from an older intent or another body does not wake the current run", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("忽略旧终态", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-old-intent", event: "action.terminal", body: {
      botId: "bot-1", id: { value: "old-intent:old-action" }, state: "COMPLETED",
    } });
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-other-body", event: "action.terminal", body: {
      botId: "bot-2", id: { value: "other-intent:other-action" }, state: "COMPLETED",
    } });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(setupState.brain.turns.length, 1);
    assert.deepEqual(setupState.brain.interrupts, []);
    first && setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("body.available for a changed physical body invalidates the old run before waking once", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("身体代次变化", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    setupState.connection.view = { ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-2", bodyGeneration: 8 }] };
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "body-available-new-generation", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-2", bodyGeneration: 8, name: "伙伴", dimension: "minecraft:overworld",
    } });
    await waitFor(() => setupState.brain.turns.length === 2);
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("action terminal before turn/start response sets a yield barrier and waits for old completion", async () => {
  const setupState = await setup(undefined, 5000);
  try {
    const start = deferred<void>();
    setupState.brain.startTurnWait = start.promise;
    const command = await setupState.controller.command("处理早到回执", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:mine-oak`;
    const action = setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      intentId: command.intentId, actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((await action as any).success, true);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-early", event: "action.terminal", body: { botId: "bot-1", id: { value: actionId }, state: "COMPLETED" } });
    assert.deepEqual(setupState.brain.interrupts, []);
    start.resolve();
    await waitFor(() => setupState.brain.turns.length === 2);
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("early yield interruption is bounded and never starts a replacement while the old turn is active", async () => {
  const setupState = await setup(undefined, 25);
  try {
    const start = deferred<void>();
    setupState.brain.startTurnWait = start.promise;
    setupState.brain.interruptResolves = false;
    const command = await setupState.controller.command("处理无法结束的早到回执", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:mine-oak`;
    const action = setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((await action as any).success, true);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-timeout", event: "action.terminal", body: { botId: "bot-1", id: { value: actionId }, state: "COMPLETED" } });
    start.resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").awaitingTurn !== undefined);
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    assert.equal(setupState.brain.turns.length, 1);
  } finally { await setupState.cleanup(); }
});

test("late observe response cannot overwrite the current view after the turn is invalidated", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("先观察", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const result = deferred<WorldView>(); setupState.connection.observeResult = result.promise;
    const turn = setupState.brain.turns[0].handle;
    const observing = setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "observe", arguments: {} });
    await waitFor(() => setupState.connection.calls.some(call => call.op === "observe"));
    const role = (setupState.controller as any).roles.get("coordinator");
    (setupState.controller as any).invalidate(role, "disconnected");
    result.resolve({ ...baseView(), gameTick: 99 });
    const response = await observing;
    assert.equal((response as any).success, false);
    assert.equal((setupState.controller as any).view.gameTick, 1);
    assert.equal((command as any).intentId !== undefined, true);
  } finally { await setupState.cleanup(); }
});

test("duplicate event delivery writes one durable event and acknowledges both calls", async () => {
  const setupState = await setup();
  try {
    const event = { ...context, kind: "event" as const, eventId: "event-1", event: "action.terminal", body: { botId: "bot-1", state: "COMPLETED" } };
    const [first, second] = await Promise.all([setupState.connection.fireEvent(event), setupState.connection.fireEvent(event)]);
    assert.equal(first, true); assert.equal(second, true);
    assert.equal(setupState.journal.rows().filter(row => row.type === "game.event").length, 1);
  } finally { await setupState.cleanup(); }
});

test("body.available wakes a goal that was awaiting a respawned body", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "coordinator", intentId: "awaiting-intent", message: "重生后继续" });
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain);
    const missing = new FakeConnection({ ...baseView(), companions: [] });
    await controller.attach(missing as unknown as ModConnection);
    assert.equal((controller as any).roles.get("coordinator").status, "awaiting_body");
    const available = { ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-1" }] };
    missing.view = available;
    await missing.fireEvent({ ...context, kind: "event", eventId: "body-available-1", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "改名后的伙伴", dimension: "minecraft:overworld",
    } });
    await waitFor(() => setupState.brain.turns.length === 1);
    assert.equal((controller as any).roles.get("coordinator").status, "thinking");
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("body.available with stale world or identity is acknowledged without waking a goal", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "coordinator", intentId: "awaiting-intent", message: "等待身体" });
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain);
    const missing = new FakeConnection({ ...baseView(), companions: [] });
    await controller.attach(missing as unknown as ModConnection);
    await missing.fireEvent({ ...context, worldId: "other-world", kind: "event", eventId: "body-available-wrong-world", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld",
    } });
    missing.view = { ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-1" }] };
    await missing.fireEvent({ ...context, kind: "event", eventId: "body-available-wrong-generation", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 8, name: "伙伴", dimension: "minecraft:overworld",
    } });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(setupState.brain.turns.length, 0);
    assert.equal((controller as any).roles.get("coordinator").status, "awaiting_body");
  } finally { await setupState.cleanup(); }
});

test("body.available reconcile failure is not remembered as handled and a new attach can recover", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "coordinator", intentId: "awaiting-intent", message: "重连恢复" });
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain);
    const missing = new FakeConnection({ ...baseView(), companions: [] });
    await controller.attach(missing as unknown as ModConnection);
    missing.view = { ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-1" }] };
    missing.reconcileError = new Error("reconcile unavailable");
    const event = { ...context, kind: "event" as const, eventId: "body-available-retry", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld",
    } };
    assert.equal(await missing.fireEvent(event), false);
    assert.equal((controller as any).roles.get("coordinator").status, "awaiting_body");
    const recovered = new FakeConnection(missing.view);
    await controller.attach(recovered as unknown as ModConnection);
    await waitFor(() => setupState.brain.turns.length === 1);
    assert.equal(await recovered.fireEvent(event), true);
    assert.equal(setupState.journal.rows().filter(row => row.type === "game.event" && row.data.eventId === event.eventId).length, 1);
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("body.available preserves an explicit pause", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("先暂停", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    (setupState.controller as any).invalidate(role, "paused");
    const next = new FakeConnection({ ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-1", paused: true }] });
    await setupState.controller.attach(next as unknown as ModConnection);
    await next.fireEvent({ ...context, kind: "event", eventId: "body-available-paused", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld",
    } });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(role.status, "paused");
    assert.equal(setupState.brain.turns.length, 1);
  } finally { await setupState.cleanup(); }
});

test("duplicate body.available delivery wakes one recovery turn", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "coordinator", intentId: "awaiting-intent", message: "只唤醒一次" });
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain);
    const available = { ...baseView(), companions: [{ ...baseView().companions[0], entityId: "entity-1" }] };
    const connection = new FakeConnection({ ...available, companions: [] });
    await controller.attach(connection as unknown as ModConnection);
    connection.view = available;
    const event = { ...context, kind: "event" as const, eventId: "body-available-duplicate", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld",
    } };
    await Promise.all([connection.fireEvent(event), connection.fireEvent(event)]);
    await waitFor(() => setupState.brain.turns.length === 1);
    assert.equal(setupState.journal.rows().filter(row => row.type === "game.event" && row.data.eventId === event.eventId).length, 1);
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("latest owner goal restores the same role, body and intent after attach", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "gatherer", intentId: "persisted-intent", message: "继续寻找铁矿" });
    const controller = new CrewController(setupState.journal, 5000);
    const brain = new FakeBrain(); await controller.initialize(); await controller.setBrain(brain); await controller.attach(setupState.connection as unknown as ModConnection);
    const status = await controller.status() as any;
    assert.deepEqual(status.roles.map((role: any) => [role.role, role.botId, role.intentId]), [["gatherer", "bot-1", "persisted-intent"]]);
    assert.equal(brain.created.length, 1);
  } finally { await setupState.cleanup(); }
});

test("a reassignment updates the in-memory restore goal before a same-world reconnect", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, {
      botId: "bot-1", role: "coordinator", intentId: "old-intent", message: "旧目标",
    });
    const controller = new CrewController(setupState.journal, 5000);
    await controller.initialize(); await controller.setBrain(setupState.brain); await controller.attach(setupState.connection as unknown as ModConnection);
    const reassigned = await controller.command("立即改派新目标", "bot-1") as any;
    const reconnected = new FakeConnection(baseView(null, context.worldId));
    await controller.attach(reconnected as unknown as ModConnection);
    const goal = (controller as any).ownerGoals.get(`${context.worldId}:bot-1`);
    assert.equal(goal.intentId, reassigned.intentId);
    assert.equal(goal.command, "立即改派新目标");
    assert.equal((await controller.status() as any).roles[0].intentId, reassigned.intentId);
  } finally { await setupState.cleanup(); }
});

test("unavailable App Server leaves the reconciled game usable and retries pending goals later", async () => {
  const setupState = await setup();
  try {
    await setupState.journal.append("owner.command", context.worldId, { botId: "bot-1", role: "builder", intentId: "waiting-intent", message: "继续建造基地" });
    const controller = new CrewController(setupState.journal, 5000);
    const unavailable = new FakeBrain(); unavailable.failCreate = true;
    await controller.initialize(); await controller.setBrain(unavailable);
    await controller.attach(setupState.connection as unknown as ModConnection);
    assert.equal(setupState.connection.connectionState, "ready");
    assert.deepEqual((await controller.status() as any).restoration, [{ role: "builder", botId: "bot-1", worldId: "world-a", status: "awaiting_reconciliation", reason: "login required" }]);
    const authenticated = new FakeBrain();
    await controller.setBrain(authenticated);
    const status = await controller.status() as any;
    assert.deepEqual(status.roles.map((role: any) => [role.role, role.intentId]), [["builder", "waiting-intent"]]);
    assert.deepEqual(status.restoration, []);
  } finally { await setupState.cleanup(); }
});

test("status response from an old connection cannot replace a newer attach view", async () => {
  const setupState = await setup();
  try {
    const oldStatus = deferred<WorldView>(); setupState.connection.statusResult = oldStatus.promise;
    const pendingStatus = setupState.controller.status();
    const newer = new FakeConnection(baseView(null, "world-b"));
    await setupState.controller.attach(newer as unknown as ModConnection);
    oldStatus.resolve({ ...baseView(), gameTick: 999 });
    const result = await pendingStatus as any;
    assert.equal(result.game.worldId, "world-b");
    assert.equal(result.game.gameTick, 1);
  } finally { await setupState.cleanup(); }
});

test("attaching another world retires old roles and frees a profile for the new body", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("旧世界任务", "bot-1");
    const newer = new FakeConnection(baseView(null, "world-b"));
    await setupState.controller.attach(newer as unknown as ModConnection);
    const command = await setupState.controller.command("新世界任务", "bot-1") as any;
    const status = await setupState.controller.status() as any;
    assert.equal((status.roles as any[]).length, 1);
    assert.equal(status.roles[0].botId, "bot-1");
    assert.equal(status.roles[0].intentId, command.intentId);
    assert.equal(setupState.brain.created.length, 2);
  } finally { await setupState.cleanup(); }
});

test("binding that completes after an attach switch cannot install a role in the new world", async () => {
  const setupState = await setup();
  try {
    (setupState.controller as any).roles.clear(); setupState.brain.createThreadStarted = false;
    const creation = deferred<void>(); setupState.brain.createThreadWait = creation.promise;
    const pending = setupState.controller.command("绑定期间切换世界", "bot-1");
    const rejection = assert.rejects(pending, /world changed|connection|disconnected/);
    await waitFor(() => setupState.brain.createThreadStarted);
    const newer = new FakeConnection({...baseView(null, "world-b"), companions:[]});
    await setupState.controller.attach(newer as unknown as ModConnection);
    creation.resolve();
    await rejection;
    const status = await setupState.controller.status() as any;
    assert.deepEqual(status.roles, []);
  } finally { await setupState.cleanup(); }
});

test("rejected RPC from an invalidated turn cannot mark the replacement role failed", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("建立初始回合", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await new Promise(resolve => setImmediate(resolve));
    const role = (setupState.controller as any).roles.get("coordinator");
    role.pending.set("forced", {}); role.dispatching = true;
    const response = deferred<WorldView>(); setupState.connection.statusResult = response.promise;
    const thinking = (setupState.controller as any).think(role) as Promise<void>;
    await waitFor(() => setupState.connection.calls.filter(call => call.op === "status").length >= 2);
    (setupState.controller as any).invalidate(role, "idle"); role.pending.clear();
    response.reject(new Error("old connection response failed"));
    await thinking;
    assert.equal(role.status, "idle");
  } finally { await setupState.cleanup(); }
});

test("connection switch while owner command is being journaled prevents retask on the old connection", async () => {
  const setupState = await setup();
  try {
    const gate = deferred<unknown>();
    const originalAppend = setupState.journal.append.bind(setupState.journal);
    let delayed = false;
    (setupState.journal as any).append = (type: string, worldId: string, data: Record<string, unknown>) => {
      if (type === "owner.command" && !delayed) {
        delayed = true;
        return gate.promise.then(() => originalAppend(type, worldId, data));
      }
      return originalAppend(type, worldId, data);
    };
    const pending = setupState.controller.command("在旧世界继续工作", "bot-1");
    await waitFor(() => delayed);
    const newer = new FakeConnection(baseView(null, "world-b"));
    await setupState.controller.attach(newer as unknown as ModConnection);
    gate.resolve();
    await assert.rejects(pending, /connection, world, or role changed before retask/);
    assert.equal(setupState.connection.calls.some(call => call.op === "control"), false);
    assert.equal(newer.calls.some(call => call.op === "control"), false);
  } finally { await setupState.cleanup(); }
});

test("connection switch while control status is pending prevents a stale control mutation", async () => {
  const setupState = await setup();
  try {
    const gate = deferred<WorldView>();
    setupState.connection.statusResult = gate.promise;
    const pending = setupState.controller.control("stop", "bot-1");
    await waitFor(() => setupState.connection.calls.some(call => call.op === "status"));
    const newer = new FakeConnection(baseView(null, "world-b"));
    await setupState.controller.attach(newer as unknown as ModConnection);
    gate.resolve(baseView(null, context.worldId));
    await assert.rejects(pending, /connection or world changed before control/);
    assert.equal(setupState.connection.calls.some(call => call.op === "control"), false);
    assert.equal(newer.calls.some(call => call.op === "control"), false);
  } finally { await setupState.cleanup(); }
});

test("share opens a restricted communication turn while a teammate has a real action", async () => {
  const dual = baseView(null);
  dual.companions = [
    dual.companions[0],
    { ...dual.companions[0], botId: "bot-2", entityId: "entity-2", name: "队友" },
  ];
  const setupState = await setup(dual);
  try {
    const owner = await setupState.controller.command("持续采集", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const heldAction = `${owner.intentId}:mine-oak`;
    const accepted = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((accepted as any).success, true);
    first && setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);

    const teammate = await setupState.controller.command("分享进展", "bot-2") as any;
    await waitFor(() => setupState.brain.turns.length === 2);
    const second = setupState.brain.turns[1].handle;
    await (setupState.controller as any).cooperation().save({id:"sharing-proposal",worldId:context.worldId,requester:"bot-2",recipient:"bot-1",category:"yield",summary:"请让出通道",action:{kind:"YIELD",parameters:{}},state:"proposed"});
    const shared = { threadId: second.threadId, turnId: second.turnId, tool: "share", arguments: {purpose:"coordination",proposalId:"sharing-proposal",recipient: "coordinator", message: "请在方便时让出通道" } };
    assert.equal((await setupState.controller.toolCall(shared) as any).success, true);
    assert.equal((await setupState.controller.toolCall(shared) as any).success, true);
    const role = (setupState.controller as any).roles.get("coordinator");
    assert.equal(role.activeAction.actionId, heldAction);
    await waitFor(() => setupState.brain.turns.length === 3);
    assert.equal(role.run.communicationOnly, true);
    assert.equal(setupState.journal.rows().filter(row => row.type === "crew.chat").length, 1);
    assert.equal(setupState.brain.turns.length, 3);

    // A body refresh describing the same accepted action is ordinary noise;
    // it must not open a competing model turn.
    setupState.connection.view = { ...dual, companions: [
      { ...dual.companions[0], action: { id: { value: heldAction }, state: "RUNNING" } },
      dual.companions[1],
    ] };
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "body-refresh-with-action", event: "body.available", body: {
      botId: "bot-1", entityId: "entity-1", bodyGeneration: 7, name: "伙伴", dimension: "minecraft:overworld",
    } });
    assert.equal(setupState.brain.turns.length, 3);
    second && setupState.brain.turns[1].resolve();
    setupState.brain.turns[2].resolve();
    await waitFor(() => role.run === undefined);

    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-after-share", event: "action.terminal", body: {
      botId: "bot-1", id: { value: heldAction }, state: "COMPLETED",
    } });
    await waitFor(() => setupState.brain.turns.length === 4);
    assert.equal(setupState.brain.turns.length, 4);
    setupState.brain.turns[3].resolve();
    void teammate;
  } finally { await setupState.cleanup(); }
});

test("a model timeout leaves a healthy body action running and its terminal resumes planning", async () => {
  // Keep this bounded timeout above ordinary Windows CI scheduling jitter;
  // the assertion still exercises the production timeout path without making
  // the full multi-file suite depend on a 25 ms wall-clock slice.
  const setupState = await setup(undefined, 100);
  setupState.brain.interruptResolves = false;
  try {
    const command = await setupState.controller.command("超时后继续", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:wait-one`;
    const result = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "wait-one", kind: "WAIT", parameters: {},
    } });
    assert.equal((result as any).success, true);
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").awaitingTurn !== undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    assert.equal(role.activeAction.actionId, actionId);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "terminal-after-timeout", event: "action.terminal", body: {
      botId: "bot-1", id: { value: actionId }, state: "COMPLETED",
    } });
    setupState.brain.turns[0].resolve({status:"interrupted"});
    await waitFor(() => setupState.brain.turns.length === 2);
    assert.deepEqual(setupState.brain.interrupts, [first.turnId]);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("a suspended owner action remains the tracked lease through a guard terminal", async () => {
  const setupState = await setup(undefined, 5000);
  try {
    const command = await setupState.controller.command("护卫期间保持采集", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const first = setupState.brain.turns[0].handle;
    const actionId = `${command.intentId}:mine-oak`;
    const accepted = await setupState.controller.toolCall({ threadId: first.threadId, turnId: first.turnId, tool: "act", arguments: {
      actionId: "mine-oak", kind: "MINE", parameters: { position: { x: 3, y: 64, z: 0 } },
    } });
    assert.equal((accepted as any).success, true);
    first && setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "mine-suspended", event: "action.progress", body: {
      botId: "bot-1", id: { value: actionId }, state: "SUSPENDED",
    } });
    const role = (setupState.controller as any).roles.get("coordinator");
    assert.equal(role.suspendedActionIds.has(actionId), true);
    (setupState.controller as any).trigger(role, "idle", {});
    assert.equal(setupState.brain.turns.length, 1);
    const guardId = "guard-owner-threat";
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "guard-started", event: "action.progress", body: {
      botId: "bot-1", id: { value: guardId }, state: "RUNNING",
    } });
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "guard-terminal", event: "action.terminal", body: {
      botId: "bot-1", id: { value: guardId }, state: "COMPLETED",
    } });
    assert.equal(role.suspendedActionIds.has(actionId), true);
    assert.equal(setupState.brain.turns.length, 1);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "mine-resumed", event: "action.progress", body: {
      botId: "bot-1", id: { value: actionId }, state: "RESUMED",
    } });
    assert.equal(role.suspendedActionIds.has(actionId), false);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "mine-terminal-after-guard", event: "action.terminal", body: {
      botId: "bot-1", id: { value: actionId }, state: "COMPLETED",
    } });
    await waitFor(() => setupState.brain.turns.length === 2);
    setupState.brain.turns[1].resolve();
  } finally { await setupState.cleanup(); }
});

test("an owner command without botId binds the available squad under one group intent", async () => {
  const view = baseView(null);
  view.companions = [
    view.companions[0],
    { ...view.companions[0], botId: "bot-2", entityId: "entity-2", name: "伙伴二" },
    { ...view.companions[0], botId: "bot-3", entityId: "entity-3", name: "伙伴三" },
  ];
  const setupState = await setup(view);
  try {
    const result = await setupState.controller.command("全队建立安全基地") as any;
    assert.equal(result.accepted, true);
    assert.equal(result.count, 3);
    assert.equal(typeof result.groupIntentId, "string");
    await waitFor(() => setupState.brain.turns.length === 3);
    const roles = [...(setupState.controller as any).roles.values()];
    assert.equal(roles.length, 3);
    assert.equal(new Set(roles.map(role => role.groupIntentId)).size, 1);
    assert.equal(roles.every(role => role.groupIntentId === result.groupIntentId), true);
    assert.equal(roles.every(role => String(role.intentId).startsWith(`${result.groupIntentId}:`)), true);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("a retask invalidates the previous squad turn and rejects its late action call", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("旧任务", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const old = setupState.brain.turns[0].handle;
    await setupState.controller.command("立即改派新任务", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 2);
    const late = await setupState.controller.toolCall({ threadId: old.threadId, turnId: old.turnId, tool: "act", arguments: {
      actionId: "late", kind: "WAIT", parameters: {},
    } }) as any;
    assert.equal(late.success, false);
    assert.match(String(late.contentItems?.[0]?.text), /stale role turn/);
    assert.equal(setupState.connection.calls.some(call => call.op === "action.submit"), false);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("team proposal is server-idempotent and team execute maps a non-intent action id", async () => {
  const view = baseView(null);
  view.companions = [view.companions[0], { ...view.companions[0], botId: "bot-2", entityId: "entity-2", name: "队友" }];
  const setupState = await setup(view);
  try {
    const squad = await setupState.controller.command("分工采集") as any;
    await waitFor(() => setupState.brain.turns.length === 2);
    const sender = setupState.brain.turns.find(turn => turn.handle.threadId.includes("coordinator"))!.handle;
    const recipientTurn = setupState.brain.turns.find(turn => turn.handle.threadId.includes("gatherer"))!.handle;
    recipientTurn && setupState.brain.turns.find(turn => turn.handle === recipientTurn)?.resolve();
    await waitFor(() => (setupState.controller as any).roles.get("gatherer").run === undefined);
    const proposal = { threadId: sender.threadId, turnId: sender.turnId, tool: "propose_work", arguments: {
      taskId: "team-wait-1", recipient: "bot-2", intentId: `${squad.groupIntentId}:coordinator`, kind: "WAIT", parameters: {}, summary: "等待一秒并报告实际终态",
    } };
    assert.equal((await setupState.controller.toolCall(proposal) as any).success, true);
    assert.equal((await setupState.controller.toolCall(proposal) as any).success, true);
    assert.equal(setupState.connection.teamTasks.size, 1);
    const fullTaskId = `${(setupState.controller as any).roles.get("gatherer").intentId}:team-wait-1`;
    assert.equal((setupState.controller as any).roles.get("gatherer").assignedTaskId, fullTaskId);
    const proposalCall = setupState.connection.calls.find(call => call.op === "team.propose");
    assert.equal((proposalCall?.options as any).intentId, (setupState.controller as any).roles.get("gatherer").intentId);
    assert.notEqual((proposalCall?.options as any).intentId, (setupState.controller as any).roles.get("coordinator").intentId);
    await waitFor(() => (setupState.controller as any).roles.get("gatherer").run?.handle && (setupState.controller as any).roles.get("gatherer").run.valid && (setupState.controller as any).roles.get("gatherer").run.revision === (setupState.controller as any).roles.get("gatherer").revision);
    const taskTurn = (setupState.controller as any).roles.get("gatherer").run.handle;
    const personalAct = await setupState.controller.toolCall({ threadId: taskTurn.threadId, turnId: taskTurn.turnId, tool: "act", arguments: {
      actionId: "personal-while-assigned", kind: "WAIT", parameters: {},
    } }) as any;
    assert.equal(personalAct.success, false);
    assert.match(String(personalAct.contentItems?.[0]?.text), /team task owns current body/);
    assert.equal(setupState.connection.calls.some(call => call.op === "action.submit"), false);
    const execution = await setupState.controller.toolCall({ threadId: taskTurn.threadId, turnId: taskTurn.turnId, tool: "act", arguments: {
      taskId: fullTaskId,
    } }) as any;
    assert.equal(execution.success, true,JSON.stringify(execution));
    const executeCall = setupState.connection.calls.find(call => call.op === "team.execute");
    assert.equal(executeCall?.body.taskId, fullTaskId);
    assert.equal((executeCall?.options as any).intentId, (setupState.controller as any).roles.get("gatherer").intentId);
    const actionId = `team-${fullTaskId}`;
    assert.equal(actionId.startsWith(`${(setupState.controller as any).roles.get("gatherer").intentId}:`), false);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "team-task-terminal", event: "action.terminal", body: {
      botId: "bot-2", id: { value: actionId }, state: "COMPLETED",
    } });
    assert.equal((setupState.controller as any).roles.get("gatherer").assignedTaskId, undefined);
    await waitFor(() => setupState.brain.turns.length === 4);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("stale planner must reobserve after another body completes mining before proposing the next task", async () => {
  const initial = baseView(null);
  initial.companions = [
    initial.companions[0],
    { ...initial.companions[0], botId: "bot-2", entityId: "entity-2", name: "采集伙伴" },
  ];
  const setupState = await setup(initial);
  try {
    const squad = await setupState.controller.command("先采木再制作") as any;
    await waitFor(() => setupState.brain.turns.length === 2);
    const coordinator = setupState.brain.turns.find(turn => turn.handle.threadId.includes("coordinator"))!.handle;
    const gathererRole = (setupState.controller as any).roles.get("gatherer");
    const oldTask = await setupState.controller.toolCall({ threadId: coordinator.threadId, turnId: coordinator.turnId, tool: "propose_work", arguments: {
      taskId: "gatherer-mine-old", recipient: "bot-2", intentId: `${squad.groupIntentId}:coordinator`, kind: "MINE",
      parameters: { position: { x: 4, y: 101, z: 8 } }, summary: "采集一根原木",
    } }) as any;
    assert.equal(oldTask.success, true);
    const oldTaskId = gathererRole.assignedTaskId;
    assert.equal(typeof oldTaskId, "string");
    // The assignment supersedes the recipient's pre-submit planning turn;
    // execute it from the replacement turn, not from the stale handle.
    await waitFor(() => setupState.brain.turns.filter(turn => turn.handle.threadId.includes("gatherer")).length === 2);
    const gathererTaskTurn = setupState.brain.turns.filter(turn => turn.handle.threadId.includes("gatherer")).at(-1)!.handle;

    const executed = await setupState.controller.toolCall({ threadId: gathererTaskTurn.threadId, turnId: gathererTaskTurn.turnId, tool: "act", arguments: { taskId: oldTaskId } }) as any;
    assert.equal(executed.success, true);
    const oldActionId = `team-${oldTaskId}`;
    setupState.connection.view = {
      ...setupState.connection.view,
      companions: setupState.connection.view.companions.map(body => body.botId === "bot-2" ? {
        ...body,
        inventory: [{ item: "minecraft:oak_log", count: 1 }],
        actionJournal: [{ sequence: 1, id: { value: oldActionId }, state: "COMPLETED", payload: { kind: "MINE" } }],
      } : body),
    };
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "stale-planner-mine-complete", event: "action.terminal", body: {
      botId: "bot-2", id: { value: oldActionId }, actionId: oldActionId, taskId: oldTaskId, intentId: gathererRole.intentId,
      state: "COMPLETED", epoch: { bodyGeneration: 7 },
    } });
    assert.equal(gathererRole.assignedTaskId, undefined);

    const proposalCountBefore = setupState.connection.calls.filter(call => call.op === "team.propose").length;
    const stale = await setupState.controller.toolCall({ threadId: coordinator.threadId, turnId: coordinator.turnId, tool: "propose_work", arguments: {
      taskId: "gatherer-mine-again", recipient: "bot-2", intentId: `${squad.groupIntentId}:coordinator`, kind: "MINE",
      parameters: { position: { x: 4, y: 101, z: 8 } }, summary: "再采一次原木",
    } }) as any;
    assert.equal(stale.success, false);
    assert.match(String(stale.contentItems?.[0]?.text), /REOBSERVE_REQUIRED:/);
    assert.equal(setupState.connection.calls.filter(call => call.op === "team.propose").length, proposalCountBefore);

    const observed = await setupState.controller.toolCall({ threadId: coordinator.threadId, turnId: coordinator.turnId, tool: "observe", arguments: {} }) as any;
    assert.equal(observed.success, true);
    const next = await setupState.controller.toolCall({ threadId: coordinator.threadId, turnId: coordinator.turnId, tool: "propose_work", arguments: {
      taskId: "gatherer-craft-next", recipient: "bot-2", intentId: `${squad.groupIntentId}:coordinator`, kind: "CRAFT",
      parameters: { resource: "minecraft:oak_planks", count: 1 }, summary: "用已采集的原木制作木板",
    } }) as any;
    assert.equal(next.success, true);
    const proposals = setupState.connection.calls.filter(call => call.op === "team.propose");
    assert.equal(proposals.length, proposalCountBefore + 1);
    assert.equal(proposals.at(-1)?.body.kind, "CRAFT");
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("GATHER is act-only and never creates a team proposal", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("连续采木", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const result = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "propose_work", arguments: {
      taskId: "gather-oak", recipient: "bot-1", kind: "GATHER", parameters: { resource: "minecraft:oak_log", count: 2 }, summary: "连续采木",
    } }) as any;
    assert.equal(result.success, false);
    assert.match(String(result.contentItems?.[0]?.text), /TEAM_ACTION_UNSUPPORTED/);
    assert.equal(setupState.connection.calls.some(call => call.op === "team.propose"), false);
    setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("a self-proposed team task keeps the current turn usable for its execute call", async () => {
  const setupState = await setup();
  try {
    const command = await setupState.controller.command("自己先等待", "bot-1") as any;
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const proposal = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "propose_work", arguments: {
      taskId: "self-wait", recipient: "bot-1", intentId: command.intentId, kind: "WAIT", parameters: {}, summary: "等待一秒",
    } }) as any;
    assert.equal(proposal.success, true);
    const role = (setupState.controller as any).roles.get("coordinator");
    assert.equal(role.run !== undefined, true);
    const execution = await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: {
      taskId: role.assignedTaskId,
    } }) as any;
    assert.equal(execution.success, true,JSON.stringify(execution));
    assert.equal(setupState.connection.calls.filter(call => call.op === "team.execute").length, 1);
    for (const brainTurn of setupState.brain.turns) brainTurn.resolve();
  } finally { await setupState.cleanup(); }
});

test("authoritative team status restores an unbound task and team.changed wakes once", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("恢复团队任务", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    const taskId = `${role.intentId}:recover-wood`;
    setupState.connection.view = {
      ...setupState.connection.view,
      team: {
        worldId: context.worldId, revision: 12, recoveryInvalid: false,
        works: [{ taskId, botId: "bot-1", intentId: role.intentId, bodyGeneration: 7, kind: "MINE" }],
        bindings: [],
        ledger: { tasks: [{ task: { id: { value: taskId } }, state: "PLANNED" }] },
      },
    };
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    const before = setupState.brain.turns.length;
    (setupState.connection.view.team as any).revision = 13;
    // A polling status read may observe the new snapshot before the event;
    // that must not mark the event revision as already consumed.
    await setupState.controller.status();
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "team-change-13", event: "team.changed", body: { revision: 13 } });
    await waitFor(() => setupState.brain.turns.length === before + 1);
    assert.equal(setupState.brain.turns.length, before + 1);
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "team-change-13-duplicate", event: "team.changed", body: { revision: 13 } });
    await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(setupState.brain.turns.length, before + 1);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("partial or reconcile-required team work stays owned and does not wake a replacement turn", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("先执行团队采集", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    const taskId = `${role.intentId}:partial-wood`;
    setupState.connection.view = {
      ...setupState.connection.view,
      team: {
        worldId: context.worldId, revision: 4, recoveryInvalid: false,
        works: [{ taskId, botId: "bot-1", intentId: role.intentId, bodyGeneration: 7, kind: "MINE" }],
        bindings: [],
        ledger: { tasks: [{ task: { id: { value: taskId } }, state: "PARTIAL" }] },
      },
    };
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    assert.equal(role.teamTaskState, "PARTIAL");
    assert.equal((await setupState.controller.status() as any).roles[0].taskState, "PARTIAL");
    const before = setupState.brain.turns.length;
    (setupState.controller as any).trigger(role, "shared:coordinator", { message: "资源结果变化" });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(setupState.brain.turns.length, before);
    assert.equal(role.assignedTaskId, taskId);

    (setupState.connection.view.team as any).ledger.tasks[0].state = "RECONCILE_REQUIRED";
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    assert.equal(role.teamTaskState, "RECONCILE_REQUIRED");
    assert.equal((await setupState.controller.status() as any).roles[0].taskState, "RECONCILE_REQUIRED");
  } finally { await setupState.cleanup(); }
});

test("unknown team ledger state is retained as reconcile-required and cannot execute blindly", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("核对未知团队结果", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    const turn = setupState.brain.turns[0].handle;
    const role = (setupState.controller as any).roles.get("coordinator");
    const taskId = `${role.intentId}:unknown-state`;
    setupState.connection.view = {
      ...setupState.connection.view,
      team: {
        worldId: context.worldId, revision: 5, recoveryInvalid: false,
        works: [{ taskId, botId: "bot-1", intentId: role.intentId, bodyGeneration: 7, kind: "WAIT" }],
        bindings: [],
        ledger: { tasks: [{ task: { id: { value: taskId } }, state: "FUTURE_STATE" }] },
      },
    };
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    assert.equal(role.teamTaskState, "RECONCILE_REQUIRED");
    assert.equal((await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: { taskId } }) as any).success, false);
    assert.match(String((await setupState.controller.toolCall({ threadId: turn.threadId, turnId: turn.turnId, tool: "act", arguments: { taskId } }) as any).contentItems?.[0]?.text), /reconcile/i);
    assert.equal(setupState.connection.calls.some(call => call.op === "team.execute"), false);
    turn && setupState.brain.turns[0].resolve();
  } finally { await setupState.cleanup(); }
});

test("team event revision resets when a new connection starts at a lower value", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("准备低版本重连", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    const taskId = `${role.intentId}:reconnect-task`;
    setupState.connection.view = { ...setupState.connection.view, team: {
      worldId: context.worldId, revision: 30, recoveryInvalid: false,
      works: [{ taskId, botId: "bot-1", intentId: role.intentId, bodyGeneration: 7 }], bindings: [],
      ledger: { tasks: [{ task: { id: { value: taskId } }, state: "PLANNED" }] },
    } };
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    (setupState.controller as any).teamEventRevision = 30;
    const replacement = new FakeConnection(structuredClone(setupState.connection.view));
    (replacement.view.team as any).revision = 1;
    await setupState.controller.attach(replacement as unknown as ModConnection);
    await waitFor(() => { for(const turn of setupState.brain.turns) turn.resolve(); return role.run === undefined && !role.dispatching && role.pending.size === 0; });
    role.status="idle";
    const before = setupState.brain.turns.length;
    await replacement.fireEvent({ ...context, kind: "event", eventId: "low-team-revision", event: "team.changed", body: { revision: 1 } });
    await waitFor(() => setupState.brain.turns.length === before + 1);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("team.changed queues behind an active role turn and drains after completion", async () => {
  const setupState = await setup();
  try {
    await setupState.controller.command("等待资源变化", "bot-1");
    await waitFor(() => setupState.brain.turns.length === 1);
    setupState.brain.turns[0].resolve();
    await waitFor(() => (setupState.controller as any).roles.get("coordinator").run === undefined);
    const role = (setupState.controller as any).roles.get("coordinator");
    const taskId = `${role.intentId}:resource-wait`;
    setupState.connection.view = { ...setupState.connection.view, team: {
      worldId: context.worldId, revision: 1, recoveryInvalid: false,
      works: [{ taskId, botId: "bot-1", intentId: role.intentId, bodyGeneration: 7 }], bindings: [],
      ledger: { tasks: [{ task: { id: { value: taskId } }, state: "PLANNED" }] },
    } };
    await setupState.controller.status();
    assert.equal(role.assignedTaskId, taskId);
    (setupState.controller as any).trigger(role, "manual", {});
    await waitFor(() => setupState.brain.turns.length === 2);
    (setupState.connection.view.team as any).revision = 2;
    await setupState.connection.fireEvent({ ...context, kind: "event", eventId: "queued-team-change", event: "team.changed", body: { revision: 2 } });
    await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(setupState.brain.turns.length, 2);
    assert.equal(role.pending.has("team_changed"), true);
    setupState.brain.turns[1].resolve();
    await waitFor(() => setupState.brain.turns.length === 3);
    for (const turn of setupState.brain.turns) turn.resolve();
  } finally { await setupState.cleanup(); }
});

test("uncertain team execute waits for reconciliation without replaying or dropping ownership", async () => {
  const view = baseView(null);
  view.companions = [view.companions[0], { ...view.companions[0], botId: "bot-2", entityId: "entity-2", name: "队友" }];
  const setupState = await setup(view);
  try {
    const squad = await setupState.controller.command("等待团队回执") as any;
    await waitFor(() => setupState.brain.turns.length === 2);
    const sender = setupState.brain.turns.find(turn => turn.handle.threadId.includes("coordinator"))!.handle;
    const recipientTurn = setupState.brain.turns.find(turn => turn.handle.threadId.includes("gatherer"))!.handle;
    recipientTurn && setupState.brain.turns.find(turn => turn.handle === recipientTurn)?.resolve();
    await waitFor(() => (setupState.controller as any).roles.get("gatherer").run === undefined);
    const proposal = await setupState.controller.toolCall({ threadId: sender.threadId, turnId: sender.turnId, tool: "propose_work", arguments: {
      taskId: "uncertain", recipient: "bot-2", intentId: `${squad.groupIntentId}:coordinator`, kind: "WAIT", parameters: {}, summary: "等待服务端回执",
    } }) as any;
    assert.equal(proposal.success, true);
    const role = (setupState.controller as any).roles.get("gatherer");
    const taskId = role.assignedTaskId;
    setupState.connection.teamExecuteResult = { state: "RECONCILE_REQUIRED", message: "existing binding" };
    await waitFor(() => role.run?.handle && role.run.valid && role.run.revision === role.revision && role.run.handle.turnId !== recipientTurn.turnId);
    const taskTurn = (setupState.controller as any).roles.get("gatherer").run.handle;
    assert.equal((await setupState.controller.toolCall({ threadId: taskTurn.threadId, turnId: taskTurn.turnId, tool: "act", arguments: { taskId } }) as any).success, true);
    const turnsBeforeResult=setupState.brain.turns.length;
    setupState.brain.turns.find(t=>t.handle.turnId===taskTurn.turnId)?.resolve();
    await waitFor(() => role.run === undefined);
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(setupState.brain.turns.length, turnsBeforeResult);
    assert.equal(role.status, "idle");
    assert.equal(role.assignedTaskId, taskId);
  } finally { await setupState.cleanup(); }
});

test("model standby returns blocked without issuing control, while player standby still issues control", async()=>{
 const view=baseView();view.companions[0].autonomyEnabled=true;
 const s=await setup(view);
 try {
  await waitFor(()=>s.brain.turns.length===1);const h=s.brain.turns[0].handle;
  const result=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"propose_work",arguments:{operation:"goal_state",state:"standby",reason:"no shore reachable"}}) as any;
  assert.equal(result.success,true);assert.match(JSON.stringify(result),/blocked/);
  s.brain.turns[0].resolve();await waitFor(()=>s.journal.rows().some(r=>r.type==="goal.state"));
  assert.equal(s.connection.calls.some(c=>c.op==="control"&&c.body.operation==="standby"),false);
  assert.equal((await s.controller.status() as any).roles[0].autonomyEnabled,true);
  await s.controller.control("standby","bot-1");
  assert.equal(s.connection.calls.some(c=>c.op==="control"&&c.body.operation==="standby"),true);
 }finally{await s.cleanup();}
});

test("working chat may reference old results but never resets its budget, consumes results or cancels work",async()=>{
 const view=baseView();view.companions.push({...view.companions[0],botId:"bot-2",name:"Moss",bodyGeneration:8});
 const s=await setup(view);
 try {
  await s.controller.command("采集","bot-1");await waitFor(()=>s.brain.turns.length===1);const first=s.brain.turns[0].handle;
  await s.controller.toolCall({threadId:first.threadId,turnId:first.turnId,tool:"act",arguments:{actionId:"mine",kind:"MINE",parameters:{position:{x:3,y:64,z:0}}}});
  s.brain.turns[0].resolve();await waitFor(()=>!(s.controller as any).roles.get("coordinator").run);
  await s.connection.fireEvent({...context,kind:"event",eventId:"old-result",event:"action.terminal",body:{botId:"bot-1",intentId:"old-other",id:{value:"old"},epoch:{world:1,body:1},payload:{kind:"MOVE"},state:"COMPLETED"}});
  await s.controller.command("报告","bot-2");await waitFor(()=>s.brain.turns.length===2);const sender=s.brain.turns[1].handle;
  const proposal:any={id:"chat-chain",worldId:context.worldId,requester:"bot-2",recipient:"bot-1",category:"yield",summary:"请在安全边界让路",action:{kind:"YIELD",parameters:{}},state:"proposed"};
  await (s.controller as any).cooperation().save(proposal);
  const share=(messageId:string,replyTo?:string)=>s.controller.toolCall({threadId:sender.threadId,turnId:sender.turnId,tool:"share",arguments:{purpose:"coordination",proposalId:proposal.id,recipient:"bot-1",message:"协调通道安排",messageId,replyTo}});
  assert.equal((await share("first-message") as any).success,true);
  const firstMessageId=s.journal.rows().filter(r=>r.type==="crew.chat").at(-1)!.data.messageId as string;
  await waitFor(()=>s.brain.turns.length===3);
  const role=(s.controller as any).roles.get("coordinator");assert.equal(role.run.communicationOnly,true);assert.equal(role.run.chatReset,false);
  assert.deepEqual(role.run.resultIds,[]);assert.match(s.brain.inputs[2],/old-result|old-other/);
  const reply=s.brain.turns[2].handle;
  await (s.controller as any).cooperation().save({...proposal,state:"accepted"});
  await s.controller.toolCall({threadId:reply.threadId,turnId:reply.turnId,tool:"share",arguments:{purpose:"coordination",proposalId:proposal.id,recipient:"bot-2",message:"将在安全边界让路",messageId:"reply",replyTo:firstMessageId}});
  s.brain.turns[2].resolve();await waitFor(()=>!role.run);
  const replyId=s.journal.rows().filter(r=>r.type==="crew.chat").at(-1)!.data.messageId as string;
  await (s.controller as any).cooperation().save({...proposal,state:"blocked",reason:"fixture route blocked"});
  assert.equal((await share("second-message",replyId) as any).success,true);await waitFor(()=>s.brain.turns.length===4);
  assert.equal(role.run.chatReset,false);assert.equal(role.run.chatDepth,3);assert.deepEqual(role.run.resultIds,[]);
  assert.ok((s.controller as any).resultQueue(context.worldId,"bot-1").has("old-result"));
  assert.equal(s.connection.calls.some(c=>c.op==="action.cancel"),false);
 }finally{await s.cleanup();}
});

test("legacy outcome reconciliation rejects mismatched proof and sends only a metadata repair for a matching receipt",async()=>{
 const s=await setup();
 try{
  const receipt={id:{value:"old"},priority:"PERSONAL",epoch:{world:1,body:3},payload:{kind:"SELECT",count:3},message:"slot verified",state:"COMPLETED",botId:"bot-1"};
  await s.journal.append("game.event",context.worldId,{eventId:"old-receipt",event:"action.terminal",body:receipt});
  const view=baseView();view.companions[0].ledgerVersion=2;view.companions[0].actionJournal=[{...receipt,state:"RECONCILE_REQUIRED",payload:{kind:"SELECT",count:0}}];
  const connection=new FakeConnection(view);const original=connection.request.bind(connection);
  connection.request=async(op,body,options)=>op==="action.reconcile_history"?(connection.calls.push({op,body,options}),{restored:true} as any):original(op,body,options);
  await s.controller.attach(connection as unknown as ModConnection);assert.equal(connection.calls.some(c=>c.op==="action.reconcile_history"),false);
  connection.view.companions[0].actionJournal=[{...receipt,state:"RECONCILE_REQUIRED"}];
  await s.controller.attach(connection as unknown as ModConnection);
  assert.equal(connection.calls.filter(c=>c.op==="action.reconcile_history").length,1);
  assert.equal(connection.calls.some(c=>c.op==="action.submit"),false);
  connection.emit("disconnect");
 }finally{await s.cleanup();}
});

// 0.1.6 offline regressions: fake transport and fake brain, no game/model process.
test("a complete internal stage stays quiet; verified owner goal completion publishes once",async()=>{
 const s=await setup(baseView(null));
 try {
  await s.controller.command("建一段围墙","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const call=async(index:number,tool:string,args:unknown,callId?:string)=>{const h=s.brain.turns[index].handle;return await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,callId,tool,arguments:args}) as any;};
  assert.equal((await call(0,"propose_work",{operation:"stage",stageId:"wall",summary:"围墙一段",scope:"营地南边",completion:{kind:"inventory",resource:"minecraft:dirt",count:2},state:"continue"})).success,true);
  assert.equal((await call(0,"share",{purpose:"progress",message:"第一块进展",recipient:"player"})).success,true);
  assert.equal(s.journal.rows().some(r=>r.type==="crew.chat"),false);
  assert.equal((await call(0,"act",{kind:"WAIT",parameters:{count:1}},"wire-call-1")).success,true);
  assert.equal((await call(0,"act",{kind:"WAIT",parameters:{count:1}},"wire-call-1")).success,false);
  assert.equal(s.connection.calls.filter(c=>c.op==="action.submit").length,1);
  const first=s.connection.calls.find(c=>c.op==="action.submit")!.options.actionId;
  assert.match(first,/:call-[0-9a-f]{40}$/);
  s.brain.turns[0].resolve();await waitFor(()=>!(s.controller as any).roles.get("coordinator").run);
  await s.connection.fireEvent({...context,kind:"event",eventId:"stage-first",event:"action.terminal",body:{botId:"bot-1",id:{value:first},state:"COMPLETED"}});
  await waitFor(()=>s.brain.turns.length===2);
  assert.equal(JSON.parse(s.brain.inputs[1]).taskStage.stageId,"wall");
  assert.equal((await call(1,"propose_work",{operation:"stage",stageId:"wall",state:"complete"})).success,false);
  assert.equal((await call(1,"act",{kind:"WAIT",parameters:{count:1}},"wire-call-2")).success,true);
  const second=s.connection.calls.filter(c=>c.op==="action.submit").at(-1)!.options.actionId;
  s.connection.view.companions[0].inventory=[{slot:0,item:"minecraft:dirt",count:2}];
  s.brain.turns[1].resolve();await waitFor(()=>!(s.controller as any).roles.get("coordinator").run);
  await s.connection.fireEvent({...context,kind:"event",eventId:"stage-second",event:"action.terminal",body:{botId:"bot-1",id:{value:second},state:"COMPLETED"}});
  await waitFor(()=>s.brain.turns.length===3);
  assert.equal((await call(2,"propose_work",{operation:"stage",stageId:"wall",state:"complete"})).success,true);
  assert.equal((await call(2,"share",{purpose:"stage_result",stageId:"wall",recipient:"player",message:"这一段已完成。"})).success,true);
  assert.equal((await call(2,"share",{purpose:"stage_result",stageId:"wall",recipient:"player",message:"这一段确认完成。"})).success,true);
  assert.equal(s.journal.rows().filter(r=>r.type==="crew.chat").length,0);
  assert.equal((await call(2,"propose_work",{operation:"goal_state",state:"complete"})).success,true);
  assert.equal((await call(2,"share",{purpose:"stage_result",stageId:"wall",message:"你的围墙任务已完成。"})).success,true);
  assert.equal((await call(2,"share",{purpose:"stage_result",stageId:"wall",message:"确认完成。"})).success,true);
  assert.equal(s.journal.rows().filter(r=>r.type==="crew.chat").length,1);
  assert.deepEqual(s.journal.rows().filter(r=>r.type==="crew.chat").at(-1)!.data.recipientNames,["玩家"]);
  assert.equal(s.journal.rows().filter(r=>r.type==="work.stage").at(-1)!.data.state,"complete");
 }finally{await s.cleanup();}
});
test("passive diagnostic events do not trigger any brain turns or body actions",async()=>{
 const s=await setup();try{
  const before=s.brain.turns.length;
  await s.connection.fireEvent({...context,kind:"event",eventId:"health",event:"diagnostic.snapshot",body:{gameTick:100,companions:[]}});
  assert.equal(s.brain.turns.length,before);assert.equal(s.connection.calls.some(c=>c.op==="action.submit"),false);
  assert.equal(s.controller.diagnosticSnapshot().gameEvidence,"recent_game_tick");
 }finally{await s.cleanup();}
});
test("autonomous blocked work gets one alternative assessment then waits until a real change",async()=>{
 const view=baseView(null);view.companions[0].autonomyEnabled=true;const s=await setup(view);
 try{
  await waitFor(()=>s.brain.turns.length===1);s.brain.turns[0].resolve();await waitFor(()=>s.brain.turns.length===2);
  assert.match(s.brain.inputs[1],/alternative_work/);s.brain.turns[1].resolve();await waitFor(()=>!(s.controller as any).roles.get("coordinator").run);
  await s.connection.fireEvent({...context,kind:"event",eventId:"idle-noise",event:"body.wakeup",body:{botId:"bot-1",reasons:["body_idle"],bodyGeneration:view.companions[0].bodyGeneration}});
  await new Promise(r=>setTimeout(r,15));assert.equal(s.brain.turns.length,2);
  assert.equal(s.journal.rows().filter(r=>r.type==="goal.alternative").length,1);
 }finally{await s.cleanup();}
});
test("one-time standby repair is checked by the server and never blindly reapplied on reconnect",async()=>{
 const s=await setup(baseView(null));
 try{
  s.controller.configureRecovery([{worldId:context.worldId,botId:"bot-1",expectedName:"Ember",controlRevision:5,recoveryId:"repair-fixture"}]);
  const c=new FakeConnection({...baseView(null),companions:[{...baseView(null).companions[0],name:"Ember",standby:true,controlRevision:5}]});
  const original=c.request.bind(c);let calls=0;
  c.request=async(op,body,options)=>{if(op==="recovery.standby"){calls++;c.view.companions[0].standby=false;c.view.companions[0].controlRevision=6;return {state:"RECOVERY_ASSESSMENT_REQUIRED"} as any;}return original(op,body,options);};
  await s.controller.attach(c as unknown as ModConnection);assert.equal(calls,1);
  c.view.companions[0].standby=true;c.view.companions[0].controlRevision=7;
  await s.controller.attach(c as unknown as ModConnection);assert.equal(calls,1);assert.equal(c.view.companions[0].standby,true);
  c.emit("disconnect");
 }finally{await s.cleanup();}
});


test("028 authoritative empty suspension list releases stale lock but keeps unresolved results", async () => {
  const s = await setup();
  try {
    await s.controller.command("work", "bot-1"); await waitFor(()=>s.brain.turns.length===1);
    s.brain.turns[0].resolve(); await waitFor(()=>!(s.controller as any).roles.get("coordinator").run);
    const role=(s.controller as any).roles.get("coordinator");
    role.suspendedActionIds.add("old");role.activeAction={actionId:"old",state:"SUSPENDED"};role.pending.set("body_wakeup",{});
    (s.controller as any).resultQueue(context.worldId,"bot-1").set("unknown",{id:{value:"old"},state:"RECONCILE_REQUIRED"});
    const body={...s.connection.view.companions[0],suspendedActionIds:[]};
    await s.connection.fireEvent({...context,eventId:"complete-ownership",event:"diagnostic.snapshot",body:{gameTick:2,companions:[body]}});
    await waitFor(()=>s.brain.turns.length===2);
    assert.equal(role.suspendedActionIds.size,0);
    assert.equal((s.controller as any).resultQueue(context.worldId,"bot-1").size,1);
    assert.equal(s.connection.calls.filter(c=>c.op==="action.submit").length,0);
  } finally {await s.cleanup();}
});

test("028 incomplete or old-body ownership cannot clear suspended work; live safety lease is kept", async () => {
  const s=await setup();
  try {
    await s.controller.command("work","bot-1");await waitFor(()=>s.brain.turns.length===1);
    const role=(s.controller as any).roles.get("coordinator");
    role.suspendedActionIds.add("mine");role.activeAction={actionId:"mine",state:"SUSPENDED"};
    const sync=(body:any)=>(s.controller as any).syncActiveAction(role,body);
    assert.equal(sync({...s.connection.view.companions[0],bodyGeneration:6,suspendedActionIds:[]}),true);
    assert.equal(sync(s.connection.view.companions[0]),true);
    assert.equal(role.suspendedActionIds.has("mine"),true);
    assert.equal(sync({...s.connection.view.companions[0],suspendedActionIds:["mine"],action:{id:{value:"safety"},state:"RUNNING"}}),true);
    assert.equal(role.suspendedActionIds.has("mine"),true);
  } finally {await s.cleanup();}
});

test("028 historical terminal releases only its orphan lock without interrupting a live turn or consuming results", async () => {
  const s=await setup();
  try {
    await s.controller.command("work","bot-1");await waitFor(()=>s.brain.turns.length===1);
    const role=(s.controller as any).roles.get("coordinator");role.suspendedActionIds.add("old");role.suspendedActionIds.add("other");
    await s.connection.fireEvent({...context,eventId:"historic-old",event:"action.terminal",body:{botId:"bot-1",id:{value:"old"},state:"STALE",historical:true}});
    assert.deepEqual([...role.suspendedActionIds],["other"]);
    assert.equal(s.brain.interrupts.length,0);assert.equal(s.brain.turns.length,1);
    assert.equal((s.controller as any).resultQueue(context.worldId,"bot-1").size,0);
  } finally {await s.cleanup();}
});

test("028 resumed journal work is not restored as suspended; stale and expired receipts release ownership", async () => {
  const s=await setup();
  try {
    for(const [event,state] of [["action.progress","SUSPENDED"],["action.progress","RESUMED"]])
      await s.journal.append("game.event",context.worldId,{eventId:state,event,body:{botId:"bot-1",id:{value:"resumed"},state}});
    const restored=new CrewController(s.journal);await restored.initialize();
    assert.equal((restored as any).suspendedFor(context.worldId,"bot-1").size,0);
    await s.controller.command("work","bot-1");await waitFor(()=>s.brain.turns.length===1);
    const role=(s.controller as any).roles.get("coordinator");
    for(const state of ["STALE","EXPIRED"]){
      role.suspendedActionIds.add("done");role.activeAction={actionId:"done",state:"SUSPENDED"};
      assert.equal((s.controller as any).syncActiveAction(role,{...s.connection.view.companions[0],actionJournal:[{id:{value:"done"},state}]}),false);
    }
  } finally {await s.cleanup();}
});

test("028 planning context bounds old receipts without mutating current facts or ledger", async () => {
  const s=await setup();
  try {
    const world=squadView();for(const body of world.companions)body.actionJournal=Array.from({length:64},(_,i)=>({id:{value:`old-${i}`},state:"FAILED"}));
    const plan=(s.controller as any).planningWorld(world,"bot-1");
    assert.deepEqual(plan.companions.map((b:any)=>b.actionJournal.length),[8,2,2]);
    assert.deepEqual(world.companions.map(b=>b.actionJournal?.length),[64,64,64]);
    assert.deepEqual(plan.companions[0].inventory,world.companions[0].inventory);
  } finally {await s.cleanup();}
});
test("029 stale idle snapshot cannot clear newer execution ownership",async()=>{
 const s=await setup();try{
  await s.controller.command("continue","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const c=s.controller as any,role=c.roles.get("coordinator"),body=s.connection.view.companions[0];
  c.acceptEvidence(role,"new-work","RUNNING",body.bodyGeneration,"test_receipt",6);
  assert.equal(c.syncActiveAction(role,{...body,action:null,suspendedActionIds:[],actionSequence:5}),true);
  assert.equal(role.activeAction.actionId,"new-work");
  assert.equal(c.syncActiveAction(role,{...body,action:null,suspendedActionIds:[],actionSequence:7}),false);
  assert.equal(c.evidence.settled(role.worldId,role.botId,"new-work"),false);
 }finally{await s.cleanup();}
});
test("029 terminal before submit response cannot reacquire the body",async()=>{
 const s=await setup();try{
  const command=await s.controller.command("continue","bot-1") as any;await waitFor(()=>s.brain.turns.length===1);
  const request=s.connection.request.bind(s.connection);
  (s.connection as any).request=async(operation:string,body:any,options:any)=>{
   const reply=await (request as any)(operation,body,options);
   if(operation==="action.submit")await s.connection.fireEvent({...context,eventId:"terminal-before-response-029",event:"action.terminal",body:{botId:"bot-1",id:{value:options.actionId},state:"COMPLETED",epoch:{bodyGeneration:s.connection.view.companions[0].bodyGeneration}}});
   return reply;
  };
  const h=s.brain.turns[0].handle;
  await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"act",arguments:{actionId:"race-029",kind:"WAIT",parameters:{count:1}}});
  const role=(s.controller as any).roles.get("coordinator");
  assert.notEqual(role.activeAction?.actionId,`${command.intentId}:race-029`);
  await waitFor(()=>s.brain.turns.length>=2);
  await waitFor(()=>s.journal.rows().some(r=>r.type==="action.late_ignored"));
 }finally{await s.cleanup();}
});
test("0210 context pages are scoped to the role and do not consume environment scans",async()=>{
 const s=await setup();try{
  await s.journal.append("context.archive",context.worldId,{botId:"bot-1",reference:"a".repeat(64),path:"world",json:JSON.stringify({known:"真实背包"})});
  await s.journal.append("context.archive",context.worldId,{botId:"bot-2",reference:"b".repeat(64),path:"world",json:"private-other-role"});
  await s.controller.command("continue","bot-1");await waitFor(()=>s.brain.turns.length===1);const handle=s.brain.turns[0].handle;
  const query=(reference:string)=>s.controller.toolCall({threadId:handle.threadId,turnId:handle.turnId,tool:"observe",arguments:{contextRef:reference,cursor:0}}) as Promise<any>;
  const own=await query("a".repeat(64));assert.equal(own.success,true);assert.match(own.contentItems[0].text,/真实背包/);
  const other=await query("b".repeat(64));assert.equal(other.success,true);assert.match(other.contentItems[0].text,/REFERENCE_NOT_AVAILABLE/);assert.doesNotMatch(other.contentItems[0].text,/private-other-role/);
  assert.equal((s.controller as any).roles.get("coordinator").run.observations,0);
 }finally{await s.cleanup();}
});
test("0210 failure-region identity ignores stage numbering and small coordinate changes",async()=>{
 const s=await setup();try{
  await s.controller.command("collect coal","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const controller=s.controller as any,role=controller.roles.get("coordinator"),key=controller.regionFailureKey(role,"MINE",{x:1,y:64,z:1});
  role.stageId="new-stage";assert.equal(controller.regionFailureKey(role,"MINE",{x:3,y:66,z:3}),key);
  assert.notEqual(controller.regionFailureKey(role,"EXCAVATE",{x:3,y:66,z:3}),key,"genuinely different access preparation remains available");
  await s.journal.append("task.region_failure",context.worldId,{botId:"bot-1",key,count:3});const restored=new CrewController(s.journal);await restored.initialize();
  assert.equal((restored as any).taskRegionFailures.get(key),3,"restart preserves failure evidence");
 }finally{await s.cleanup();}
});


test("UI history pages select actual speech before limits and reject another world without planning",async()=>{
 const env=await setup();try{
  for(let n=0;n<25;n++)await env.journal.append("crew.chat",context.worldId,{messageId:`m${n}`,origin:"companion",senderName:"Ember",message:`发现${n}`,recipientNames:["Moss"]});
  for(let n=0;n<50;n++)await env.journal.append("tool.rejected",context.worldId,{reason:"not speech"});
  const read=async(query:unknown,requestId:string)=>{const message=JSON.stringify(query);env.connection.uiClaimResult={decision:"CLAIMED",operation:"history",message};const req=uiRequest("status",requestId);await env.connection.fireEvent({...req,body:{...req.body,operation:"history",message}});return JSON.parse((env.connection.uiUpdates.at(-1) as any).json);};
  const first=await read({worldId:context.worldId,cursor:0},"10000000-1111-4111-8111-111111111111");assert.equal(first.ok,true);assert.equal(first.page.rows.length,20);assert.ok(first.page.nextCursor>0);assert.equal(env.brain.turns.length,0);
  const older=await read({worldId:context.worldId,cursor:first.page.nextCursor},"20000000-1111-4111-8111-111111111111");assert.equal(older.page.rows.length,5);assert.equal(older.page.nextCursor,null);assert.equal(new Set([...first.page.rows,...older.page.rows].map((r:any)=>r.messageId)).size,25);
  const invalid=await read({worldId:"other",cursor:0},"30000000-1111-4111-8111-111111111111");assert.equal(invalid.ok,false);assert.match(invalid.error,/世界/);assert.equal(env.connection.calls.some(c=>["action.submit","control"].includes(c.op)),false);
 }finally{await env.cleanup();}
});
test("UI stage pages retain more than eight stages and scope the requested body",async()=>{
 const env=await setup();try{
  const bot=baseView().companions[0].botId;
  for(let n=0;n<10;n++)await (env.controller as any).stages.save({worldId:context.worldId,botId:bot,intentId:"i",stageId:`s${n}`,summary:"同名阶段",state:"complete",scope:"test",completion:{kind:"inventory",resource:"minecraft:coal",count:1},actions:[]});
  const read=async(botId:string,cursor:number,id:string)=>{const message=JSON.stringify({worldId:context.worldId,botId,cursor});env.connection.uiClaimResult={decision:"CLAIMED",operation:"detail",message};const req=uiRequest("status",id);await env.connection.fireEvent({...req,body:{...req.body,operation:"detail",message}});return JSON.parse((env.connection.uiUpdates.at(-1) as any).json);};
  const page=await read(bot,6,"40000000-1111-4111-8111-111111111111");assert.equal(page.ok,true);assert.equal(page.page.rows.length,3);assert.equal(page.page.nextCursor,9);assert.notEqual(page.page.rows[0].key,page.page.rows[1].key);
  const bad=await read("foreign-body",0,"50000000-1111-4111-8111-111111111111");assert.equal(bad.ok,false);assert.equal(env.brain.turns.length,0);
 }finally{await env.cleanup();}
});

// 0.2.14: deterministic inventory/recipe evidence, never a live model.
test("0214 own preparation suppresses automatic tool request without suppressing the next action",async()=>{
 const v=squadView();v.companions[0].botId="11111111-1111-4111-8111-111111111111";v.companions[1].botId="22222222-2222-4222-8222-222222222222";const s=await setup(v);try{
  await s.controller.command("采铁并准备工具","11111111-1111-4111-8111-111111111111");await waitFor(()=>s.brain.turns.length===1);const h=s.brain.turns[0].handle;
  s.connection.observeResult=Promise.resolve({...s.connection.view,recipes:[{output:"minecraft:stone_pickaxe",count:1,outputCapacity:true,selfPreparation:{state:"ready_step",nextKind:"CRAFT"}}]} as any);
  const r=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"propose_work",callId:"own-tool",arguments:{operation:"cooperate",category:"materials",recipient:"22222222-2222-4222-8222-222222222222",summary:"请送石镐",kind:"TRANSFER",parameters:{target:"11111111-1111-4111-8111-111111111111",resource:"minecraft:stone_pickaxe",count:1}}}) as any;
  assert.equal(r.success,true,JSON.stringify(r));assert.equal(JSON.parse(r.contentItems[0].text).reason,"SELF_PREPARATION_AVAILABLE");
  assert.equal(s.journal.rows().some(r=>r.type==="cooperation.state"),false);assert.equal(s.brain.turns.length,1);
  const act=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"act",callId:"craft-local",arguments:{kind:"CRAFT",parameters:{resource:"minecraft:stone_pickaxe",count:1}}}) as any;assert.equal(act.success,true);
 }finally{await s.cleanup();}
});
test("0214 local path failures do not prohibit verified station placement",async()=>{
 const s=await setup();try{
  await s.controller.command("放置工作台准备工具","bot-1");await waitFor(()=>s.brain.turns.length===1);const h=s.brain.turns[0].handle,role=(s.controller as any).roles.get("coordinator");
  (s.controller as any).localPathFailures.set(`${role.worldId}:${role.botId}:${role.intentId}`,{count:30,position:{x:0,y:64,z:0}});
  const r=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"act",callId:"station",arguments:{kind:"PLACE",parameters:{position:{x:1,y:64,z:0},resource:"minecraft:crafting_table"}}}) as any;
  assert.equal(r.success,true);assert.equal(s.connection.calls.filter(c=>c.op==="action.submit").length,1);
 }finally{await s.cleanup();}
});
test("0214 empty query placeholders are normalized but nonempty mixed queries still reject",async()=>{
 const s=await setup(squadView());try{
  await s.controller.command("查询材料","bot-1");await waitFor(()=>s.brain.turns.length===1);const h=s.brain.turns[0].handle;
  const call=(recipes:any[])=>s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"observe",arguments:{teamInventory:{companion:"bot-2"},recipes,targets:[]}}) as Promise<any>;
  assert.equal((await call([])).success,true);assert.equal((await call(["minecraft:stone_pickaxe"])).success,false);
 }finally{await s.cleanup();}
});
test("0214 rejected parameters get one recovery turn then a typed wait, never role no-plan failure",async()=>{
 const s=await setup();try{
  s.brain.beforeIdleCompletion=undefined;await s.controller.command("准备工具","bot-1");await waitFor(()=>s.brain.turns.length===1);
  for(let i=0;i<2;i++){const h=s.brain.turns[i].handle;const r=await s.controller.toolCall({threadId:h.threadId,turnId:h.turnId,tool:"act",callId:`bad-${i}`,arguments:{kind:"PICKUP",parameters:{count:3}}}) as any;assert.equal(r.success,false);s.brain.turns[i].resolve({status:"completed"});if(i===0)await waitFor(()=>s.brain.turns.length===2);}
  await waitFor(()=>s.journal.rows().some(r=>r.type==="wait.tool_rejection"));
  assert.equal(s.journal.rows().some(r=>r.type==="role.no_plan"),false);assert.equal(s.brain.turns.length,2);
  assert.ok(s.journal.rows().some(r=>r.type==="goal.state"&&r.data.condition));
 }finally{await s.cleanup();}
});


test("032 blocked stage is a typed wait with one alternative, not a no-plan fault",async()=>{
 const s=await setup();try{
  s.brain.beforeIdleCompletion=undefined;await s.controller.command("采煤并寻找安全出口","bot-1");await waitFor(()=>s.brain.turns.length===1);
  const call=(i:number,args:any)=>s.controller.toolCall({threadId:s.brain.turns[i].handle.threadId,turnId:s.brain.turns[i].handle.turnId,tool:"propose_work",arguments:args}) as Promise<any>;
  assert.equal((await call(0,{operation:"stage",stageId:"coal",state:"continue",summary:"开路采煤",scope:"32格内",completion:{kind:"inventory",resource:"minecraft:coal",count:16}})).success,true);
  assert.equal((await call(0,{operation:"stage",stageId:"coal",state:"blocked",reason:"出口通路需要开挖",condition:{kind:"path",position:{x:4,y:64,z:4}}})).success,true);
  s.brain.turns[0].resolve({status:"completed"});await waitFor(()=>s.brain.turns.length===2);
  assert.equal((await call(1,{operation:"stage",stageId:"coal",state:"blocked",reason:"其他出口仍受阻",condition:{kind:"path"}})).success,true);
  s.brain.turns[1].resolve({status:"completed"});await waitFor(()=>(s.controller as any).roles.get("coordinator").run===undefined);
  assert.equal(s.brain.turns.length,2);assert.equal(s.journal.rows().filter(r=>r.type==="goal.alternative").length,1);assert.equal(s.journal.rows().some(r=>r.type==="role.no_plan"),false);
  const role=(s.controller as any).roles.get("coordinator");assert.equal(role.status,"idle");assert.equal((s.controller as any).goalWaits.get("world-a:bot-1").condition.kind,"path");assert.equal(role.command,"采煤并寻找安全出口");
  await s.connection.fireEvent({...context,kind:"event",eventId:"scan-repeat",event:"body.wakeup",body:{botId:"bot-1",bodyGeneration:7,reasons:["scan_ready"],gameTick:25}});
  await new Promise(r=>setTimeout(r,20));assert.equal(s.brain.turns.length,2,"scan does not reset wait or resume same no-plan loop");
 }finally{await s.cleanup();}
});

test("032 directed UI task and retries preserve recipients and reject duplicate submissions",async()=>{
 const s=await setup(squadView());try{
  const message=JSON.stringify({botId:"bot-2",message:"采集煤炭"});s.connection.uiClaimResult={decision:"CLAIMED",operation:"task",message};
  const base=uiRequest("status");const req={...base,body:{...base.body,operation:"task",message}};
  await s.connection.fireEvent(req);await waitFor(()=>s.brain.turns.length===1);await s.connection.fireEvent(req);
  assert.equal(s.brain.turns.filter(t=>t.handle.threadId!==s.brain.turns[0].handle.threadId).length,0);assert.equal((await s.controller.status() as any).roles.find((r:any)=>r.intentId).botId,"bot-2");assert.equal(s.journal.rows().filter(r=>r.type==="owner.command").length,1);
  const role=[...(s.controller as any).roles.values()].find((r:any)=>r.botId==="bot-2") as any;role.run.goalState="blocked";role.run.goalReason="等待出口";role.run.goalCondition={kind:"path",reason:"等待出口",sinceTick:1,recoveryAttempts:0};(s.controller as any).alternativeUsed.add(`world-a:bot-2:${role.intentId}`);
  s.brain.turns[0].resolve({status:"completed"});await waitFor(()=>!role.run);
  const retryMsg=JSON.stringify({botId:"bot-2"}),retry=uiRequest("status","44444444-1111-4111-8111-111111111111");s.connection.uiClaimResult={decision:"CLAIMED",operation:"retry",message:retryMsg};
  await s.connection.fireEvent({...retry,body:{...retry.body,operation:"retry",message:retryMsg}});await waitFor(()=>s.brain.turns.length===2);
  assert.equal(role.command,"采集煤炭");assert.equal(s.brain.turns.filter(t=>t.handle.threadId!==s.brain.turns[0].handle.threadId).length,0);assert.equal(s.connection.calls.some(c=>c.op==="action.submit"||c.op==="control"&&(c.body as any).operation!=="retask"),false,"retry asks existing Luna; it never replays actions or resumes player controls");
 }finally{await s.cleanup();}
});

test("032 retry cannot override stopped bodies or unknown effects",async()=>{
 const s=await setup();try{
  await s.controller.command("保留目标","bot-1");await waitFor(()=>s.brain.turns.length===1);const role=(s.controller as any).roles.get("coordinator");
  role.run.goalState="blocked";role.run.goalReason="待核对";role.run.goalCondition={kind:"unknown_effect",reason:"待核对",sinceTick:1,recoveryAttempts:0};(s.controller as any).alternativeUsed.add(`world-a:bot-1:${role.intentId}`);
  s.brain.turns[0].resolve({status:"completed"});await waitFor(()=>!role.run);
  for(let i=0;i<2;i++){
   s.connection.view.companions[0].stopped=i===1;const message=JSON.stringify({botId:"bot-1"});s.connection.uiClaimResult={decision:"CLAIMED",operation:"retry",message};const r=uiRequest("status",`${i+5}1111111-1111-4111-8111-111111111111`);
   await s.connection.fireEvent({...r,body:{...r.body,operation:"retry",message}});assert.equal(JSON.parse((s.connection.uiUpdates.at(-1) as any).json).ok,false);
  }
  assert.equal(s.brain.turns.length,1);assert.equal(s.connection.calls.some(c=>c.op==="action.submit"||c.op==="control"&&(c.body as any).operation!=="retask"),false);
 }finally{await s.cleanup();}
});


test("032 legacy no-plan reassessment is once-only and respects player pause",async()=>{
 const s=await setup();try{
  await s.controller.command("保留旧采矿目标","bot-1");await waitFor(()=>s.brain.turns.length===1);const c=s.controller as any,r=c.roles.get("coordinator");
  r.run.goalState="blocked";r.run.goalReason="出口不可达";r.run.goalCondition={kind:"path",reason:"出口不可达",sinceTick:1,recoveryAttempts:0};c.alternativeUsed.add(`world-a:bot-1:${r.intentId}`);
  s.brain.turns[0].resolve({status:"completed"});await waitFor(()=>!r.run);
  const evidence=await s.journal.append("role.no_plan",context.worldId,{botId:"bot-1"});// INJECTED prior-release journal metadata in this temporary fixture only.
  (s.journal as any).records.find((x:any)=>x.sequence===evidence.sequence).version="0.3.1";
  (s.connection as any).executionBackend={id:"numen",navigationProgressVersion:1,exactMoveCompletion:true};
  s.connection.view.companions[0].paused=true;
  await c.wakeReconciledRoles(s.connection,s.connection.view,"reconnected");assert.equal(s.journal.rows().filter(x=>x.type==="recovery.execution_032").length,0);assert.equal(r.command,"保留旧采矿目标");
  s.connection.view.companions[0].paused=false;
  await c.wakeReconciledRoles(s.connection,s.connection.view,"reconnected");await waitFor(()=>s.brain.turns.length===2);
  await c.wakeReconciledRoles(s.connection,s.connection.view,"reconnected");
  assert.equal(s.journal.rows().filter(x=>x.type==="recovery.execution_032").length,1);assert.equal(r.command,"保留旧采矿目标");assert.equal(s.connection.calls.some(x=>x.op==="action.submit"),false);
 }finally{await s.cleanup();}
});
