import type {ExecutionBackendDescriptor} from "../../protocol/execution-backend.js";
import {projectBackendCapabilities} from "./backend-capabilities.js";
import {ReportingPolicy, reportDecision, publicFacilityKey, REPORT_RULES, STAGE_PURPOSES} from "./reporting-policy.js";
import {inventoryQuery, inventoryVersion, teamInventory, resourceSummary, relevantItems, materialConditionKey} from "./team-resources.js";
import { planningContext, contextPage } from "./planning-context.js";
import { parseWait, waitingEventRelevant, type WaitCondition } from "./wait-condition.js";
import { assessSupply, CooperationQueue, type Cooperation } from "./cooperation.js";
import { ActionEvidence } from "./action-evidence.js";
import { WorkStages, completionSpec, stageSatisfied } from "./work-stages.js";
import { randomUUID, createHash } from "node:crypto";
import { CrewMailbox, type CrewChat } from "./crew-mailbox.js";
import { AGENT_PROFILES, type AgentProfile, type StartedThread, type TurnHandle } from "./app-server-client.js";
import type { ModConnection } from "./mod-connection.js";
import type { EventEnvelope, UiRequestBody } from "../../protocol/types.js";
import { CrewJournal } from "./crew-journal.js";
import { consumeNovelFacts, goalStateForSource, resolveRecipients, sameHistoricalRequest } from "./behavior-policy.js";
import { ACTION_KINDS, ACTION_GUIDE, validateActionParameters, normalizeActArguments } from "./action-parameters.js";
import { stageRows } from "./work-board.js";
import { makeUiView, publicText, messages } from "./ui-view.js";

export interface BodyView { actionSequence?:number; onGround?:boolean;harvestEvidence?:unknown[]; botId: string; bodyGeneration: number; name: string; dimension: string; position: { x: number; y: number; z: number }; inventory: unknown[]; entityId?: string; action?: unknown; stopped?: boolean; paused?: boolean; recoveryInvalid?: boolean; waitReason?: string; ledgerVersion?: number; actionJournal?: unknown[]; suspendedActionIds?: string[]; gatherDiagnostics?: unknown; localSafety?: unknown; autonomyEnabled?: boolean; standby?: boolean; controlRevision?: number; intentBinding?: { intentId?: string; origin?: IntentOrigin; intentGeneration?: number } | null; }
export interface TeamView { worldId?: string; revision: number; recoveryInvalid: boolean; reason?: string; ledger?: unknown; works?: unknown[]; bindings?: unknown[]; }
export interface WorldView {stage?:string; modVersion?:string;executionProtocol?:number;snapshotGeneration?:number;worldId: string; gameTick: number; companions: BodyView[]; observation?: unknown; targetInspections?: unknown[]; team?: TeamView; }
export interface BrainPort {
  createThread(id: AgentProfile["id"], backend?:ExecutionBackendDescriptor): Promise<StartedThread>;
  startTurn(thread: StartedThread, input: string, profile?: AgentProfile, generation?: number): Promise<TurnHandle>;
  diagnostics(): unknown;
}
export const ACTION_CAPABILITIES = {
  self: ACTION_KINDS,
  actions: ACTION_GUIDE,
  team: ["BUILD", "MINE", "PLACE", "TRANSFER", "CRAFT", "MOVE", "WAIT", "SELECT", "EAT"],
  gather: { delegation: false, resources: ["oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry"].map(name=>`minecraft:${name}_log`), validation: "unverified_until_local_scan", radius: 32 },
  guidance: "GATHER只能本人act；队友可share请求帮助，由接收者独立判断。附近矿石包含隐藏资源，不能据此判断身处地下。失败后参考真实原因选择其他动作或报告条件，不重复无变化的失败请求。",
} as const;
type IntentOrigin = "owner" | "autonomous";
type AutonomousMode = "on" | "off" | "standby";
type GoalState = "continue" | "complete" | "blocked" | "standby";
interface Role {
  id: AgentProfile["id"]; botId: string; worldId: string; thread: StartedThread; intentId?: string; groupIntentId?: string; command?: string; assignedTaskId?: string; assignedTaskIntentId?: string;
  teamTaskState?: string;
  status: "idle" | "thinking" | "working" | "paused" | "awaiting_body" | "failed" | "disconnected" | "recovering";
  entityId?: string; bodyGeneration?: number; actionSequence?:number; sequenceGeneration?:number;
  origin: IntentOrigin; intentGeneration: number; autonomyEnabled: boolean; standby: boolean; controlRevision?: number; boundControlRevision?: number; waitReason?: string; wakeConditions?: string[]; noWorkCorrections: number;
  pending: Map<string, unknown>; dispatching: boolean; revision: number;
  activeAction?: { actionId: string; state: string }; lastTerminalActionId?: string;
  suspendedActionIds: Set<string>;
  chatDepth?: number;
  recoveryAttempts?: number; recoveryExhausted?: boolean; resumeAfterEnd?: boolean; recoveryTimer?: NodeJS.Timeout; awaitingTurn?: TurnHandle;
  failureSignature?: string;
  preparationGrace?: boolean;

  rejectionRecoveryUsed?: boolean;
  run?: { toolRejections?:string[]; preparationRegistered?: boolean; startedAt?: number; promptBytes?: number; observeCache?:Map<string,unknown>; resultIds?: string[]; communicationOnly?: boolean; chatReset?: boolean; inbox?: CrewChat[]; publicCount?: number; chatDepth?: number; ownerChat?: string; token: string; connection: ModConnection; generation: number; intentId: string; origin: IntentOrigin; intentGeneration: number; observations: number; queries?: number; submitted: boolean; submittedActionId?: string; submittedTaskId?: string; goalState?: GoalState; goalReason?: string; goalCondition?:WaitCondition; goalWakeConditions?: string[]; yieldForBodyResult: boolean; interruptRequested: boolean; handle?: TurnHandle; valid: boolean; revision: number; lastObserved: WorldView; reobserveRequired?: string };
}
interface ActionAttempt { readonly fingerprint: string; readonly intentId: string; }
interface OwnerGoal { readonly worldId: string; readonly botId: string; readonly role: AgentProfile["id"]; readonly intentId: string; readonly groupIntentId?: string; readonly command: string; }
interface AutonomousGoal { readonly worldId: string; readonly botId: string; readonly role: AgentProfile["id"]; readonly intentId: string; readonly groupIntentId: string; readonly command: string; readonly intentGeneration: number; readonly active: boolean; readonly standbyAfter?: boolean; }
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("object required");
  return value as Record<string, unknown>;
};
const text = (value: unknown, field: string, max = 2000): string => {
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new Error(`${field} must contain 1..${max} characters`);
  return value;
};
const canonical = (value: unknown): unknown => {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    const source = value as Record<string, unknown>;
    return Object.fromEntries(Object.keys(source).sort().map(key => [key, canonical(source[key])]));
  }
  return value;
};
const terminalActionId = (details: Record<string, unknown>): string | undefined => {
  if (typeof details.actionId === "string" && details.actionId.length > 0) return details.actionId;
  const id = details.id;
  if (id && typeof id === "object" && !Array.isArray(id) && typeof (id as Record<string, unknown>).value === "string") return (id as Record<string, unknown>).value as string;
  return undefined;
};
const terminalBodyGeneration = (details: Record<string, unknown>): { present: boolean; value?: number } => {
  const epoch = details.epoch;
  if (epoch === undefined) return { present: false };
  if (!epoch || typeof epoch !== "object" || Array.isArray(epoch)) return { present: true };
  const value = (epoch as Record<string, unknown>).bodyGeneration;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? { present: true, value }
    : { present: true };
};
const availableBody = (details: Record<string, unknown>): { botId: string; entityId: string; bodyGeneration: number; name: string; dimension: string } | undefined => {
  const botId = details.botId;
  const entityId = details.entityId;
  const bodyGeneration = details.bodyGeneration;
  const name = details.name;
  const dimension = details.dimension;
  if (typeof botId !== "string" || !botId || typeof entityId !== "string" || !entityId
    || typeof bodyGeneration !== "number" || !Number.isSafeInteger(bodyGeneration) || bodyGeneration < 0
    || typeof name !== "string" || !name || typeof dimension !== "string" || !dimension) return undefined;
  return { botId, entityId, bodyGeneration, name, dimension };
};

const actionIdFromValue = (value: unknown): string | undefined => {
  if (typeof value === "string" && value) return value;
  if (value && typeof value === "object" && !Array.isArray(value) && typeof (value as Record<string, unknown>).value === "string") {
    const result = (value as Record<string, unknown>).value;
    return result as string;
  }
  return undefined;
};

const taskIdFromValue = (value: unknown): string | undefined => {
  if (typeof value === "string" && value) return value;
  if (value && typeof value === "object" && !Array.isArray(value) && typeof (value as Record<string, unknown>).value === "string") return (value as Record<string, unknown>).value as string;
  return undefined;
};
const recordValue = (value: unknown): Record<string, unknown> | undefined => value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
// PARTIAL and RECONCILE_REQUIRED are terminal from the executor's point of
// view, but they still retain the body's team lease and its resource
// reservations.  Dropping those states here would let a status refresh turn
// the same body into an apparently free personal worker.
const TEAM_ACTIVE_STATES = new Set(["PLANNED", "CLAIMED", "RUNNING", "PARTIAL", "RECONCILE_REQUIRED"]);
const TEAM_RECONCILE_STATES = new Set(["PARTIAL", "RECONCILE_REQUIRED"]);
const TEAM_RELEASED_STATES = new Set(["COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "STALE"]);
const TEAM_KNOWN_STATES = new Set([...TEAM_ACTIVE_STATES, ...TEAM_RELEASED_STATES]);

/**
 * This is deliberately a small, task-relevant view of the last world state
 * given to a model.  It must not include gameTick or position: walking does
 * not invalidate a planning turn, while an inventory/action-ledger change
 * does.  The Mod remains authoritative; this fingerprint only prevents an
 * old model snapshot from creating another world task.
 */
const planningBodyFingerprint = (view: WorldView, botId: string): string | undefined => {
  const body = view.companions.find(candidate => candidate.botId === botId);
  if (!body) return undefined;
  const journal = Array.isArray(body.actionJournal) ? body.actionJournal.map(raw => {
    const row = recordValue(raw);
    if (!row) return raw;
    return {
      sequence: row.sequence,
      actionId: actionIdFromValue(row.actionId) ?? actionIdFromValue(row.id),
      state: row.state,
      kind: recordValue(row.payload)?.kind,
    };
  }) : undefined;
  const teamWorks = Array.isArray(view.team?.works) ? view.team!.works.map(recordValue).filter((work): work is Record<string, unknown> =>
    Boolean(work && taskIdFromValue(work.botId) === botId)).map(work => ({
      taskId: taskIdFromValue(work.taskId), intentId: work.intentId, bodyGeneration: work.bodyGeneration, kind: work.kind,
      state: work.state,
    })) : undefined;
  const teamLedger = recordValue(view.team?.ledger);
  const ledgerTasks = Array.isArray(teamLedger?.tasks) ? teamLedger.tasks.map(raw => {
    const snapshot = recordValue(raw); const task = recordValue(snapshot?.task);
    return { taskId: taskIdFromValue(task?.id), state: snapshot?.state };
  }).filter(entry => entry.taskId && teamWorks?.some(work => work.taskId === entry.taskId)) : undefined;
  return JSON.stringify(canonical({
    bodyGeneration: body.bodyGeneration,
    entityId: body.entityId,
    dimension: body.dimension,
    inventory: body.inventory,
    actionJournal: journal,
    teamWorks,
    teamLedger: ledgerTasks,
  }));
};

const planningFingerprint = (view: WorldView, botId: string, transferTarget?: string): string | undefined => {
  const body = planningBodyFingerprint(view, botId);
  if (body === undefined) return undefined;
  if (!transferTarget) return JSON.stringify({ body });
  const target = view.companions.find(candidate => candidate.entityId === transferTarget);
  const targetFingerprint = target ? planningBodyFingerprint(view, target.botId) : undefined;
  return JSON.stringify({ body, transferTarget, targetFingerprint });
};

const stalePlanningReason = (run: NonNullable<Role["run"]>, current: WorldView, recipientBotId: string, transferTarget?: string): string | undefined => {
  const previous = planningFingerprint(run.lastObserved, recipientBotId, transferTarget);
  const latest = planningFingerprint(current, recipientBotId, transferTarget);
  if (previous === undefined) return "recipient was absent from the model's last observation";
  if (latest === undefined) return "recipient is absent from the current world state";
  if (previous !== latest) return "recipient inventory, action journal, body generation, or team assignment changed";
  if (transferTarget && !current.companions.some(body => body.entityId === transferTarget)) return "transfer recipient is no longer observed";
  return undefined;
};

const activeAction = (value: unknown): { actionId: string; state: string } | null | undefined => {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const details = value as Record<string, unknown>;
  const actionId = actionIdFromValue(details.actionId) ?? actionIdFromValue(details.id);
  const state = typeof details.state === "string" ? details.state.toUpperCase() : "";
  if (!actionId || !state) return null;
  if (["COMPLETED", "FAILED", "CANCELLED", "REJECTED"].includes(state)) return null;
  return { actionId, state };
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const UI_OPERATIONS = new Set(["status", "detail", "history", "command", "task", "retry", "pause", "resume", "stop", "auto_on", "auto_off", "standby"]);
const uiUuid = (value: unknown): string | undefined => typeof value === "string" && UUID.test(value) ? value : undefined;
const uiRequestBody = (value: unknown): UiRequestBody | undefined => {
  const body = recordValue(value);
  const requestId = uiUuid(body?.requestId); const ownerId = uiUuid(body?.ownerId); const uiSession = uiUuid(body?.uiSession);
  const operation = body?.operation;
  const message = body?.message === undefined ? "" : body.message;
  if (!requestId || !ownerId || !uiSession || typeof operation !== "string" || !UI_OPERATIONS.has(operation) || typeof message !== "string" || message.length > (["task","retry"].includes(operation)?4096:512)) return undefined;
  if (["command","task","retry","detail","history"].includes(operation) ? message.trim().length === 0 : message.length !== 0) return undefined;
  return { requestId, ownerId, uiSession, operation: operation as UiRequestBody["operation"], message };
};
const uiOperationResult = (value: unknown): Record<string, string | number | boolean> => {
  const source = recordValue(value); if (!source) return {};
  const result: Record<string, string | number | boolean> = {};
  for (const key of ["accepted", "completed", "count", "operation", "botId", "role", "intentId", "groupIntentId"]) {
    const item = source[key];
    if (typeof item === "boolean" || (typeof item === "number" && Number.isSafeInteger(item)) || (typeof item === "string" && item.length <= 128)) result[key] = item;
  }
  return result;
};
const uiError = (error: unknown): string => {
  let message = error instanceof Error ? error.message : String(error);
  message = message.replace(/[A-Za-z]:[\\/][^\s"'<>]+/g, "[路径]");
  message = message.replace(/(?:^|\s)\/[^\s"'<>]+/g, " [路径]");
  message = message.replace(/\b(token|password|secret|api[_-]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, "$1=[已隐藏]");
  return `操作未完成：${message.slice(0, 180) || "未知错误"}`;
};

/** Event-driven P1 controller. World writes are exclusively Mod requests. */
export class CrewController {
  private executionCapabilities(){return projectBackendCapabilities(ACTION_CAPABILITIES,this.connection?.executionBackend);}
  private connection?: ModConnection;
  private view?: WorldView;
  private brain?: BrainPort;
  private readonly roles = new Map<string, Role>();
  private readonly seenChatFacts = new Map<string, Set<string>>();
  private chatFacts(world: string, bot: string): Set<string> { const key = `${world}:${bot}`; let seen = this.seenChatFacts.get(key); if (!seen) {seen = new Set(); this.seenChatFacts.set(key,seen);} return seen; }
  private readonly bindings = new Set<string>();
  private readonly seenEvents = new Set<string>();
  private cooperationQueue?:CooperationQueue;
  private cooperation():CooperationQueue{if(!this.cooperationQueue){this.cooperationQueue=new CooperationQueue(this.journal);this.cooperationQueue.restore();}return this.cooperationQueue;}
  private readonly evidence = new ActionEvidence();
  private readonly actionAttempts = new Map<string, ActionAttempt>();
  private readonly sharedDeliveries = new Set<string>();
  private readonly suspendedByBody = new Map<string, Set<string>>();
  private mutationTail: Promise<unknown> = Promise.resolve();
  private readonly eventWrites = new Map<string, Promise<boolean>>();
  private readonly handledBodyAvailable = new Set<string>();
  private readonly ownerGoals = new Map<string, OwnerGoal>();
  private readonly deferredRequests = new Map<string, { id: string; worldId: string; botId: string; message: string }[]>();
  private urgentRequest(message: string): boolean {
    if (/不(?:要|用|必|需要)(?:现在|马上|立即|紧急)/.test(message)) return false;
    return /(?:立即|马上|现在就|先停下|停止当前|取消当前|改做|改派|优先执行|紧急|急救|救命|\b(?:immediately|urgent|retask|stop now)\b)/i.test(message);
  }
  private async cancelDeferred(worldId: string, botId: string, reason: string): Promise<void> {
    const key = `${worldId}:${botId}`, queue = this.deferredRequests.get(key) ?? [];
    this.deferredRequests.delete(key);
    for (const request of queue) await this.journal.append("owner.request.closed", worldId, { id: request.id, botId, reason });
  }
  private readonly autonomousGoals = new Map<string, AutonomousGoal>();
  private readonly roleAssignments = new Map<string, AgentProfile["id"]>();
  private lastGameHealth?: {atUtc:string;gameTick:unknown;bodies:unknown};
  private reportingPolicy?:ReportingPolicy;
  private recipeFacts=new Map<string,unknown>();
  private materialSignals=new Map<string,string>();
  private reporting(){if(!this.reportingPolicy){this.reportingPolicy=new ReportingPolicy(this.journal);this.reportingPolicy.restore();}return this.reportingPolicy;}
  private recoveryRequests: Array<{worldId:string;botId:string;expectedName:string;controlRevision:number;recoveryId:string}>=[];
  configureRecovery(requests: typeof this.recoveryRequests):void {
    if(!Array.isArray(requests)||requests.length>3||requests.some(r=>!r.worldId||!r.botId||!r.recoveryId||!r.expectedName||!Number.isSafeInteger(r.controlRevision)))throw new Error("invalid one-time recovery requests");
    this.recoveryRequests=structuredClone(requests);
  }
  diagnosticSnapshot():Record<string,unknown> {
    const age=this.lastGameHealth ? Date.now()-Date.parse(this.lastGameHealth.atUtc):undefined;
    return {atUtc:new Date().toISOString(),version:"0.3.2",worldId:this.view?.worldId,connection:this.connection?.connectionState??"disconnected",gameEvidence:age!==undefined&&age<15000?"recent_game_tick":"状态待确认：无近期游戏时钟；不能据此判为暂停",lastGameHealth:this.lastGameHealth,roles:[...this.roles.values()].map(r=>({botId:r.botId,threadId:r.thread.threadId,turnId:r.run?.handle?.turnId,status:r.status,action:r.activeAction,suspendedActionIds:[...r.suspendedActionIds],dispatching:r.dispatching,teamTaskState:r.teamTaskState,modelPhase:r.run ? (r.run.handle ? "running" : "starting") : r.awaitingTurn ? "awaiting_interrupt" : r.recoveryTimer ? "retry_delay" : "not_started",turnAgeMs:r.run?.startedAt===undefined?undefined:Date.now()-r.run.startedAt,promptBytes:r.run?.promptBytes,recoveryAttempts:r.recoveryAttempts??0,recoveryExhausted:r.recoveryExhausted??false,pending:[...r.pending.keys()],waiting:r.waitReason,controlRevision:r.controlRevision,standby:r.standby,origin:r.origin}))};
  }
  private async applyRecoveryRequests(connection:ModConnection,observed:WorldView):Promise<void> {
    for(const request of this.recoveryRequests) {
      if(request.worldId!==observed.worldId || this.journal.rows(observed.worldId).some(r=>r.type==="recovery.applied"&&r.data.recoveryId===request.recoveryId))continue;
      const body=observed.companions.find(b=>b.botId===request.botId);if(!body)continue;
      const receipt=await connection.request<{state:string}>("recovery.standby",{...request,expectedWorld:request.worldId},{bodyGeneration:body.bodyGeneration,timeoutMs:5000});
      await this.journal.append("recovery.applied",observed.worldId,{...request,receipt});
      const refreshed=await connection.request<WorldView>("status",{},{timeoutMs:5000});
      if(connection!==this.connection||refreshed.worldId!==observed.worldId)throw new Error("world changed while verifying recovery");
      const actual=refreshed.companions.find(b=>b.botId===request.botId);if(actual)Object.assign(body,actual);
      if(receipt.state==="RECOVERY_ASSESSMENT_REQUIRED" && actual?.standby===false && actual.controlRevision===request.controlRevision+1 && !actual.stopped && !actual.paused){
        this.recoveryEvaluations.set(`${observed.worldId}:${body.botId}`,body.bodyGeneration);
        this.autonomyModes.set(`${observed.worldId}:${body.botId}`,"on");this.goalWaits.delete(`${observed.worldId}:${body.botId}`);
      }
    }
  }
  private readonly stages: WorkStages;
  private readonly alternativeUsed = new Set<string>();
  private readonly recoveryEvaluations = new Map<string, number>();
  private readonly goalWaits = new Map<string, { intentId: string; reason: string; condition?:WaitCondition; wakeConditions?: string[] }>();
  private readonly autonomyModes = new Map<string, AutonomousMode>();
  private readonly autonomyGroups = new Map<string, string>();
  private readonly restoreFailures = new Map<string, string>();
  private restoreTail: Promise<void> = Promise.resolve();
  private autoBootstrapTail: Promise<void> = Promise.resolve();
  private autonomyBootstrapDepth = 0;
  private teamSnapshotRevision?: number;
  private teamEventRevision?: number;
  private readonly uiRequestInFlight = new Map<string, Promise<boolean>>();
  private readonly uiRequestHandled = new Set<string>();
  private readonly mailbox: CrewMailbox;
  private readonly failedAttempts = new Map<string, Set<string>>();
  private recordFailedContext(key:string, fingerprint:string):void {const values=this.failedAttempts.get(key)??new Set<string>();values.add(fingerprint);if(values.size>128)values.delete(values.values().next().value!);this.failedAttempts.set(key,values);}
  private readonly taskRegionFailures=new Map<string,number>();
  private readonly localPathFailures=new Map<string,{position:{x:number;y:number;z:number};count:number}>();
  private readonly actionContexts = new Map<string, string>();
  private readonly results = new Map<string, Map<string, Record<string, unknown>>>();
  private resultQueue(world: string, bot: string): Map<string, Record<string, unknown>> {
    const key = `${world}:${bot}`;
    let queue = this.results.get(key); if (!queue) { queue = new Map(); this.results.set(key, queue); }
    return queue;
  }
  private async consumeResults(role: Role, run: NonNullable<Role["run"]>): Promise<void> {
    if (run.communicationOnly || !run.valid || role.run !== run || run.connection !== this.connection || !run.resultIds?.length) return;
    const ids = run.resultIds.filter(id => this.resultQueue(role.worldId, role.botId).has(id));
    if (!ids.length) return;
    await this.journal.append("action.results_consumed", role.worldId, { botId: role.botId, threadId: role.thread.threadId, turnId: run.handle?.turnId, eventIds: ids });
    ids.forEach(id => this.resultQueue(role.worldId, role.botId).delete(id));
  }
  private actionContext(role: Role, kind: string, parameters: unknown, observed: WorldView): string {
    const body = observed.companions.find(b => b.botId === role.botId);
    return JSON.stringify(canonical({ kind, parameters, position: body ? {x:Math.floor(body.position.x),y:Math.floor(body.position.y),z:Math.floor(body.position.z)} : undefined, inventory: body?.inventory.map(raw=>{const v=recordValue(raw);return {item:v?.item,count:v?.count};}).sort((a,b)=>String(a.item).localeCompare(String(b.item))), targets: observed.targetInspections?.map(raw=>{const t=recordValue(raw);return {position:t?.position,block:t?.block,protection:t?.protection,fluid:t?.fluid,support:t?.supportBelow,path:t?.path};}) }));
  }
  private clearRecovery(role: Role): void {
    if (role.recoveryTimer) clearTimeout(role.recoveryTimer);
    role.recoveryTimer = undefined;
  }
  private recoverAfterEnd(role: Role, run: NonNullable<Role["run"]>, revision: number, expected: boolean): void {
    const handle = run.handle;
    if (!handle) return;
    role.awaitingTurn = handle;
    const ended = () => {
      if (role.awaitingTurn !== handle) return;
      role.awaitingTurn = undefined;
      if (run.connection !== this.connection || !role.intentId || role.standby || role.origin === "autonomous" && !role.autonomyEnabled || role.status === "paused" || role.status === "disconnected") return;
      if (role.revision !== revision) { if (role.pending.size) { role.resumeAfterEnd = true; this.trigger(role, "queued_event", {}); } return; }
      if (expected) { role.resumeAfterEnd = true; role.status = role.activeAction ? "working" : "idle"; this.trigger(role, "action_result", { retained: true }); return; }
      const attempt = role.recoveryAttempts ?? 0;
      if (attempt >= 2) { role.recoveryExhausted = true; role.waitReason = "模型连续恢复两次仍失败，等待新指令或实际动作进展"; role.status = role.activeAction ? "working" : "failed"; return; }
      role.recoveryAttempts = attempt + 1;
      role.status = role.activeAction ? "working" : "recovering";
      const delay = this.recoveryDelays[attempt]!;
      role.waitReason = `模型回合超时，旧回合已结束，${delay / 1000}秒后第${attempt + 1}次恢复`;
      void this.journal.append("turn.recovery", role.worldId, { botId: role.botId, threadId: handle.threadId, turnId: handle.turnId, attempt: attempt + 1, delayMs: delay }).catch(() => {});
      role.recoveryTimer = setTimeout(() => {
        role.recoveryTimer = undefined;
        if (role.revision !== revision || run.connection !== this.connection || role.standby || role.origin === "autonomous" && !role.autonomyEnabled || role.status === "paused" || role.status === "disconnected") return;
        role.status = role.activeAction ? "working" : "idle";
        this.trigger(role, "model_recovery", { attempt: attempt + 1 });
      }, delay);
      role.recoveryTimer.unref();
    };
    void handle.completion.then(ended, ended);
  }

  constructor(readonly journal: CrewJournal, private readonly turnTimeoutMs = 90_000, private readonly recoveryDelays: readonly number[] = [5000, 15000]) { this.mailbox = new CrewMailbox(journal); this.stages = new WorkStages(journal); }
  private autonomyMode(worldId: string, botId?: string): AutonomousMode {
    return (botId ? this.autonomyModes.get(`${worldId}:${botId}`) : undefined) ?? this.autonomyModes.get(worldId) ?? "on";
  }
  async initialize(): Promise<void> {
    await this.journal.load();
    this.mailbox.restore(); this.stages.restore();
    const startupRows = this.journal.rows();
    const ownerResults = new Set(startupRows.filter(row => row.type === "chat.owner_result").map(row => `${row.worldId}:${row.data.messageId}`));
    for (const row of startupRows.filter(row => row.type === "chat.owner_claim")) {
      if (!ownerResults.has(`${row.worldId}:${row.data.messageId}`)) {
        await this.journal.append("chat.owner_result", row.worldId, { messageId: row.data.messageId, state: "RECONCILE_REQUIRED", reason: "控制器在点名任务登记后中断，派发结果待核对；不会自动重放。" });
      }
    }
    for (const row of this.journal.rows()) {
      if(["resource.condition_changed","resource.condition_baseline","cooperation.notified"].includes(row.type)&&typeof row.data.key==="string")this.materialSignals.set(row.data.key,String(row.data.signal));
      if(row.type==="resource.condition_changed"&&this.goalWaits.get(`${row.worldId}:${row.data.botId}`)?.intentId===row.data.intentId)this.goalWaits.delete(`${row.worldId}:${row.data.botId}`);
      if(row.type==="task.region_failure"&&typeof row.data.key==="string"&&typeof row.data.count==="number")this.taskRegionFailures.set(row.data.key,row.data.count);
      if(row.type==="path.local_state" && typeof row.data.botId==="string" && typeof row.data.intentId==="string"){
        const key=`${row.worldId}:${row.data.botId}:${row.data.intentId}`,p=recordValue(row.data.position);
        if(row.data.cleared===true)this.localPathFailures.delete(key);
        else if(p&&[p.x,p.y,p.z,row.data.count].every(n=>typeof n==="number"&&Number.isFinite(n)))this.localPathFailures.set(key,{position:{x:Number(p.x),y:Number(p.y),z:Number(p.z)},count:Number(row.data.count)});
      }
      if (row.type === "goal.alternative_reset") this.alternativeUsed.delete(`${row.worldId}:${row.data.botId}:${row.data.intentId}`);
      if (row.type === "goal.alternative" && typeof row.data.botId === "string") this.alternativeUsed.add(`${row.worldId}:${row.data.botId}:${row.data.intentId}`);
      if (row.type === "turn.started" && typeof row.data.botId === "string" && Array.isArray(row.data.chatFacts)) consumeNovelFacts(this.chatFacts(row.worldId,row.data.botId),row.data.chatFacts.filter((v): v is string => typeof v === "string"));
      if (row.type === "owner.request.queued" && typeof row.data.id === "string" && typeof row.data.botId === "string" && typeof row.data.message === "string") {
        const key = `${row.worldId}:${row.data.botId}`, queue = this.deferredRequests.get(key) ?? [];
        queue.push({ id: row.data.id, worldId: row.worldId, botId: row.data.botId, message: row.data.message }); this.deferredRequests.set(key, queue);
      }
      if (row.type === "owner.request.closed" || row.type === "owner.request.claimed") {
        const key = `${row.worldId}:${row.data.botId}`;
        this.deferredRequests.set(key, (this.deferredRequests.get(key) ?? []).filter(r => r.id !== row.data.id));
      }
      if (row.type === "game.event" && row.data.event === "action.terminal") {
        const d = recordValue(row.data.body);
        if (typeof d?.botId === "string" && d.historical !== true) {
          this.resultQueue(row.worldId, d.botId).set(String(row.data.eventId), d);
          const key = this.actionContexts.get(`${row.worldId}:${d.botId}:${terminalActionId(d)}`);
          if (d.state === "FAILED" && key) this.recordFailedContext(`${row.worldId}:${d.botId}`, key);
        }
      }
      if (row.type === "action.history_reconciled" && typeof row.data.botId === "string") {
        const queue=this.resultQueue(row.worldId,row.data.botId);
        for (const [id,r] of queue) if(terminalActionId(r)===row.data.actionId)queue.delete(id);
      }
      if (row.type === "action.results_consumed" && typeof row.data.botId === "string" && Array.isArray(row.data.eventIds))
        row.data.eventIds.forEach(id => this.resultQueue(row.worldId, String(row.data.botId)).delete(String(id)));
      if (row.type === "role.bound" && typeof row.data.botId === "string" && AGENT_PROFILES.some(p => p.id === row.data.role))
        this.roleAssignments.set(`${row.worldId}:${row.data.botId}`, row.data.role as AgentProfile["id"]);
      if (row.type === "game.event" && row.data.event === "player.chat") {
        const d = object(row.data.body);
        if (typeof d.messageId === "string" && !this.mailbox.get(row.worldId, d.messageId)) {
          await this.mailbox.append({ messageId: d.messageId, worldId: row.worldId, origin: "player", senderId: String(d.ownerId), senderName: String(d.ownerName),
            recipientIds: d.recipientIds as string[], recipientNames: (d.recipientNames ?? d.recipientIds) as string[], message: String(d.message), depth: 0, gameTick: 0 });
        }
      }
      if (row.type === "goal.state" && typeof row.data.botId === "string" && typeof row.data.intentId === "string") {
        const key = `${row.worldId}:${row.data.botId}`;
        if (row.data.state === "blocked") this.goalWaits.set(key, { intentId: row.data.intentId, condition:row.data.condition as WaitCondition|undefined,reason: typeof row.data.reason === "string" ? row.data.reason : "等待条件", wakeConditions: Array.isArray(row.data.wakeConditions) ? row.data.wakeConditions.filter((value): value is string => typeof value === "string") : undefined });
        else this.goalWaits.delete(key);
      }
      if(row.type==="action.receipt" && typeof row.data.botId==="string" && typeof row.data.actionId==="string") {
        const receipt=recordValue(row.data.receipt);
        if(typeof receipt?.state==="string")this.evidence.apply(row.worldId,row.data.botId,row.data.actionId,receipt.state,terminalBodyGeneration(receipt).value);
      }
      if (row.type === "game.event") {
        this.seenEvents.add(`${row.worldId}:${row.data.eventId}`);
        const body = row.data.body && typeof row.data.body === "object" && !Array.isArray(row.data.body) ? row.data.body as Record<string, unknown> : undefined;
        const botId = typeof body?.botId === "string" ? body.botId : undefined;
        const actionId = body ? terminalActionId(body) : undefined;
        if(botId && actionId && body && ["action.terminal","action.progress"].includes(String(row.data.event)))this.evidence.apply(row.worldId,botId,actionId,String(body.state),terminalBodyGeneration(body).value);
        if (botId && actionId && row.data.event === "action.progress" && String(body?.state).toUpperCase() === "SUSPENDED") this.suspendedFor(row.worldId, botId).add(actionId);
        if (botId && actionId && (row.data.event === "action.terminal" || row.data.event === "action.progress" && ["RUNNING", "STARTED", "RESUMED"].includes(String(body?.state).toUpperCase()))) this.suspendedFor(row.worldId, botId).delete(actionId);
      }
      if (row.type === "action.prepared") {
        if (typeof row.data.contextFingerprint === "string") this.actionContexts.set(`${row.worldId}:${row.data.botId}:${row.data.actionId}`, row.data.contextFingerprint);
        this.goalWaits.delete(`${row.worldId}:${row.data.botId}`);
        const key = `${row.worldId}:${row.data.botId}:${row.data.actionId}`;
        const fingerprint = String(row.data.fingerprint);
        const intentId = row.data.intentId;
        if (typeof intentId !== "string" || !intentId) throw new Error("journal action.prepared is missing intentId");
        const previous = this.actionAttempts.get(key);
        if (previous && (previous.fingerprint !== fingerprint || previous.intentId !== intentId)) throw new Error("journal contains conflicting action identity");
        this.actionAttempts.set(key, { fingerprint, intentId });
      }
      if (row.type === "owner.command") {
        const botId = row.data.botId; const role = row.data.role; const intentId = row.data.intentId; const command = row.data.message;
        if (typeof botId !== "string" || !botId || typeof role !== "string" || !AGENT_PROFILES.some(profile => profile.id === role)
          || typeof intentId !== "string" || !intentId || typeof command !== "string" || !command) throw new Error("journal owner.command is invalid");
        const groupIntentId = row.data.groupIntentId;
        this.ownerGoals.set(`${row.worldId}:${botId}`, { worldId: row.worldId, botId, role: role as AgentProfile["id"], intentId, ...(typeof groupIntentId === "string" && groupIntentId ? { groupIntentId } : {}), command });
        this.roleAssignments.set(`${row.worldId}:${botId}`, role as AgentProfile["id"]);
      }
      if (row.type === "owner.goal_state") {
        const botId = row.data.botId; const intentId = row.data.intentId;
        if (typeof botId === "string" && typeof intentId === "string" && ["complete", "standby"].includes(String(row.data.state))) {
          const current = this.ownerGoals.get(`${row.worldId}:${botId}`);
          if (current?.intentId === intentId) this.ownerGoals.delete(`${row.worldId}:${botId}`);
        }
      }
      if (row.type === "autonomy.mode" && ["on", "off", "standby"].includes(String(row.data.mode))) {
        this.autonomyModes.set(typeof row.data.botId === "string" && row.data.botId ? `${row.worldId}:${row.data.botId}` : row.worldId, row.data.mode as AutonomousMode);
      }
      if (row.type === "autonomy.goal") {
        const botId = row.data.botId; const intentId = row.data.intentId; const role = row.data.role;
        const generation = row.data.intentGeneration;
        if (typeof botId === "string" && typeof intentId === "string" && typeof role === "string" && AGENT_PROFILES.some(profile => profile.id === role)
          && typeof generation === "number" && Number.isSafeInteger(generation) && generation >= 0 && typeof row.data.command === "string" && typeof row.data.groupIntentId === "string") {
          const key = `${row.worldId}:${botId}`;
          this.roleAssignments.set(key, role as AgentProfile["id"]);
          if (row.data.active === false) this.autonomousGoals.delete(key);
          else {
            const goal: AutonomousGoal = { worldId: row.worldId, botId, role: role as AgentProfile["id"], intentId, groupIntentId: row.data.groupIntentId, command: row.data.command, intentGeneration: generation, active: true, ...(row.data.standbyAfter === true ? { standbyAfter: true } : {}) };
            this.autonomousGoals.set(key, goal); this.autonomyGroups.set(row.worldId, goal.groupIntentId);
          }
        }
      }
      if (row.type === "role.shared") {
        const sender = row.data.role;
        const intentId = row.data.intentId;
        const message = row.data.message;
        const recipients = row.data.recipients;
        if (typeof sender === "string" && typeof intentId === "string" && typeof message === "string" && Array.isArray(recipients)) {
          for (const recipient of recipients) if (typeof recipient === "string") this.sharedDeliveries.add(this.shareKey(row.worldId, intentId, sender, recipient, message));
        }
      }
    }
  }
  async setBrain(brain: BrainPort): Promise<void> {
    this.brain = brain;
    if (this.view) {
      await this.restoreGoals(this.view.worldId);
      await this.ensureAutonomousBodies(this.view);
      this.syncTeamAssignments(this.view);
      if (this.connection?.connectionState === "ready") await this.wakeReconciledRoles(this.connection, this.view, "reconnected");
    }
  }
  async attach(connection: ModConnection): Promise<void> {
    if (this.connection && this.connection !== connection) this.connection.close();
    this.connection = connection; this.view = undefined;
    // Team revisions are scoped to the Mod session/world archive.  A fresh
    // connection may legitimately restart its service revision at a lower
    // value, so never let the previous session's consumed-event watermark
    // suppress its first team.changed event.
    this.teamSnapshotRevision = undefined;
    this.teamEventRevision = undefined;
    for (const role of this.roles.values()) this.invalidate(role, role.status === "paused" ? "paused" : "disconnected");
    connection.onEvent(event => this.gameEvent(connection, event));
    connection.once("disconnect", () => {
      if (this.connection !== connection) return;
      this.view = undefined;
      for (const role of this.roles.values()) this.invalidate(role, role.status === "paused" ? "paused" : "disconnected");
    });
    const observed = await connection.request<WorldView>("reconcile", {}, { timeoutMs: 10_000 });
    if (this.connection !== connection) return;
    await this.journal.append("world.reconciled", observed.worldId, { gameTick: observed.gameTick, sessionEpoch: connection.sessionContext?.sessionEpoch });
    if (this.connection !== connection) return;
    await this.applyRecoveryRequests(connection,observed);
    await this.reconcileHistoricalResults(connection,observed);
    for(const body of observed.companions)this.recoveryEvaluations.set(`${observed.worldId}:${body.botId}`,body.bodyGeneration);
    this.view = observed;
    // A body/role belongs to one world session.  Retire old-world threads so
    // their profiles are available for the newly attached world; their owner
    // goals remain in ownerGoals and can be restored if that world returns.
    for (const [id, role] of this.roles) {
      if (role.worldId !== observed.worldId) {
        this.invalidate(role, "disconnected");
        this.roles.delete(id);
      }
    }
    if (this.brain) {
      await this.restoreGoals(observed.worldId);
      await this.ensureAutonomousBodies(observed);
    }
    if (this.connection !== connection) return;
    this.syncTeamAssignments(observed);
    await this.wakeReconciledRoles(connection, observed, "reconnected");
  }
  private async reconcileHistoricalResults(connection: ModConnection, observed: WorldView): Promise<void> {
    for (const body of observed.companions) {
      if (body.standby && this.journal.rows(observed.worldId).some(r=>r.type==="goal.state" && r.data.botId===body.botId && r.data.origin==="autonomous" && r.data.state==="standby")) {
        body.waitReason="旧版待命来源待确认，无法排除后续玩家控制；如需自主游玩，请在设置重新开启自主模式";
        this.restoreFailures.set(`${observed.worldId}:${body.botId}`,body.waitReason);
      }
      if (body.ledgerVersion !== 2 || !Array.isArray(body.actionJournal)) continue;
      const latest = new Map<string,Record<string,unknown>>();
      for (const raw of body.actionJournal) { const receipt=recordValue(raw); const id=receipt && terminalActionId(receipt); if(id && receipt)latest.set(id,receipt); }
      for (const [id, receipt] of latest) {
        if (receipt.state !== "RECONCILE_REQUIRED") continue;
        const candidates=this.journal.action(observed.worldId,body.botId,id).filter(r=>r.type==="game.event"&&r.data.event==="action.terminal").map(r=>recordValue(r.data.body)).filter((r):r is Record<string,unknown> => !!r && r.botId === body.botId && ["COMPLETED","PARTIAL","FAILED","CANCELLED","EXPIRED","STALE"].includes(String(r.state)) && sameHistoricalRequest(receipt,r));
        if (!candidates.length || new Set(candidates.map(r=>r.state)).size !== 1) continue;
        if (connection !== this.connection) return;
        const proof=candidates.at(-1)!;
        try {
          const reply=await connection.request<{restored:boolean}>("action.reconcile_history",{botId:body.botId,actionId:id,receipt:proof},{bodyGeneration:body.bodyGeneration,timeoutMs:5000});
          if(reply.restored) {
            Object.assign(receipt,{state:proof.state,message:proof.message,historical:true});
            for(const [eventId,result] of this.resultQueue(observed.worldId,body.botId)) if(terminalActionId(result)===id)this.resultQueue(observed.worldId,body.botId).delete(eventId);
            await this.journal.append("action.history_reconciled",observed.worldId,{botId:body.botId,actionId:id,state:proof.state,bodyGeneration:body.bodyGeneration});
          }
        } catch(error) {
          await this.journal.append("action.history_unconfirmed",observed.worldId,{botId:body.botId,actionId:id,reason:uiError(error)});
        }
      }
    }
  }
  private async wakeReconciledRoles(connection: ModConnection, observed: WorldView, reason: string, botId?: string): Promise<void> {
    for (const role of this.roles.values()) {
      if (role.worldId !== observed.worldId || !role.intentId || (botId && role.botId !== botId)) continue;
      const body = observed.companions.find(candidate => candidate.botId === role.botId);
      if (!body) { role.status = "awaiting_body"; continue; }
      this.syncRoleAuthoritative(body); role.entityId = body.entityId; role.bodyGeneration = body.bodyGeneration;
      const waiting = this.goalWaits.get(`${role.worldId}:${role.botId}`);
      if (waiting?.intentId === role.intentId) { role.waitReason = waiting.reason; role.wakeConditions = waiting.wakeConditions; }
      if (body.stopped || body.paused || body.recoveryInvalid) { role.status = "paused"; continue; }
      if (role.standby || (role.origin === "autonomous" && !role.autonomyEnabled)) { role.status = "idle"; continue; }
      const revision = role.revision;
      try {
        if(connection.executionBackend?.navigationProgressVersion===1&&!activeAction(body.action)&&waiting?.condition?.kind!=="unknown_effect"){
          const done=this.journal.role(role.worldId,role.botId,r=>r.type==="recovery.execution_031",1);
          const affected031=this.journal.role(role.worldId,role.botId,r=>r.version==="0.3.0"&&(r.type==="role.no_plan"||r.type==="tool.rejected"||r.type==="goal.state"&&r.data.state==="blocked"||r.type==="game.event"&&r.data.event==="action.terminal"),1);
          if(!done.length&&affected031.length&&(!waiting||/path|stance|station|NAV|矿|工位|工作台|无进展|通路|计划|水中/i.test(waiting.reason))){
            await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:role.intentId,state:"continue",reason:"0.3.1 bounded execution reassessment; no old action replay"});
            await this.journal.append("recovery.execution_031",role.worldId,{botId:role.botId,intentId:role.intentId,evidenceSequence:affected031[0]!.sequence,controlRevision:body.controlRevision});
            this.goalWaits.delete(`${role.worldId}:${role.botId}`);role.waitReason=undefined;role.wakeConditions=undefined;
            role.pending.set("execution_reassessment",{message:"0.3.1已更新通路、工位和无进展监督。核对当前身体、原目标及实际结果，再选择下一步；未知效果不重放。"});
          }
        }
        if(connection.executionBackend?.exactMoveCompletion===true&&!activeAction(body.action)&&waiting?.condition?.kind!=="unknown_effect") {
          const done=this.journal.role(role.worldId,role.botId,r=>r.type==="recovery.execution_032",1);
          const affected=this.journal.role(role.worldId,role.botId,r=>r.version==="0.3.1"&&(r.type==="role.no_plan"||r.type==="goal.state"&&r.data.state==="blocked"),1);
          const newerOwner=this.journal.role(role.worldId,role.botId,r=>r.type==="owner.command",1);
          if(!done.length&&affected.length&&(!newerOwner.length||newerOwner[0]!.sequence<affected[0]!.sequence)){
            await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:role.intentId,state:"continue",reason:"0.3.2 reassess blocked stage; never replay historical actions"});
            await this.journal.append("recovery.execution_032",role.worldId,{botId:role.botId,intentId:role.intentId,evidenceSequence:affected[0]!.sequence,controlRevision:body.controlRevision});
            this.goalWaits.delete(`${role.worldId}:${role.botId}`);role.waitReason=undefined;role.wakeConditions=undefined;role.noWorkCorrections=0;role.rejectionRecoveryUsed=false;role.preparationGrace=false;
            role.pending.set("execution_reassessment",{message:"0.3.2已修复移动高度核验和阶段受阻调度，并提供受限EXCAVATE。保留原目标，核对真实身体与库存，选择下一步；具体受阻用stage blocked附condition登记。不要重放旧动作。"});
          }
        }
        const migration=this.journal.role(role.worldId,role.botId,r=>r.type==="recovery.preparation_0214",1);
        const affected=this.journal.role(role.worldId,role.botId,r=>r.version==="0.2.13"&&(r.type==="role.no_plan"||r.type==="tool.rejected"&&/LOCAL_ESCAPE_STALLED|recipe requires a real crafting table/.test(String(r.data.reason))),1);
        if(!migration.length&&affected.length&&(!waiting||/镐|工位|工作台|recipe|tool|station|craft|协作|无计划/i.test(waiting.reason))){
          await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:role.intentId,state:"continue",reason:"0.2.14 bounded preparation reassessment"});
          await this.journal.append("recovery.preparation_0214",role.worldId,{botId:role.botId,intentId:role.intentId,evidenceSequence:affected[0]!.sequence,previousWait:waiting?.reason,controlRevision:body.controlRevision});
          this.goalWaits.delete(`${role.worldId}:${role.botId}`);role.waitReason=undefined;role.wakeConditions=undefined;
          role.pending.set("preparation_reassessment",{message:"0.2.14已修复工位误拦和自动补工具。重新核对自身材料、残镐和工位，在原任务内恢复；旧回执保留，不重放旧动作。"});
        }
        await this.bindIntent(connection, role, body);
        if (connection !== this.connection || this.view?.worldId !== observed.worldId || role.revision !== revision) continue;
        this.restoreFailures.delete(`${role.worldId}:${role.botId}`);
        const executing = this.syncActiveAction(role, body);
        role.status = role.run?.valid ? "thinking" : executing ? "working" : "idle";
        this.trigger(role, reason, { message: "先核对真实动作账本与背包；不要重放先前动作。" });
      } catch (error) {
        if (role.revision !== revision) continue;
        role.status = "failed"; role.waitReason = error instanceof Error ? error.message : String(error);
        this.restoreFailures.set(`${role.worldId}:${role.botId}`, role.waitReason);
      }
    }
  }
  async status(): Promise<unknown> {
    const connection = this.connection;
    if (connection?.connectionState === "ready") {
      const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
      if (connection === this.connection && connection.connectionState === "ready" && observed.worldId === connection.sessionContext?.worldId) { this.view = observed; for (const body of observed.companions) this.syncRoleAuthoritative(body); this.syncTeamAssignments(observed); }
    }
    for(const body of this.view?.companions ?? []) {
      const hint=this.restoreFailures.get(`${this.view!.worldId}:${body.botId}`);
      if(body.standby && hint?.startsWith("旧版待命来源待确认"))body.waitReason=hint;
    }
    return { stage: "P2_DEVELOPMENT", game: this.view ?? null, controller: "running", appServer: this.brain?.diagnostics() ?? "not_started",
      runtimeDiagnostics: this.diagnosticSnapshot(),
      roles: [...this.roles.values()].map(role => ({ role: role.id, botId: role.botId, status: role.status, intentId: role.intentId, groupIntentId: role.groupIntentId, taskId: role.assignedTaskId, taskState: role.teamTaskState, autonomyEnabled: role.autonomyEnabled, activity: role.standby ? "standby" : this.recoveryEvaluations.has(`${role.worldId}:${role.botId}`) ? "recovery_assessment" : role.status === "recovering" || role.awaitingTurn || role.recoveryTimer ? "recovering" : role.status === "failed" ? "failed" : role.status === "thinking" ? "thinking" : role.waitReason && !role.activeAction ? "waiting" : role.activeAction ? (recordValue(this.view?.companions.find(b => b.botId === role.botId)?.gatherDiagnostics)?.phase === "scanning" ? "scanning" : "working") : "idle", waitCondition:this.goalWaits.get(`${role.worldId}:${role.botId}`)?.condition,pendingRequests: (this.deferredRequests.get(`${role.worldId}:${role.botId}`) ?? []).map(r => ({ id: r.id, message: r.message })), recoveryAttempts: role.recoveryAttempts ?? 0, pendingResults: this.resultQueue(role.worldId, role.botId).size, goal: this.stageGoal(role), rootGoal:role.command, taskStage: this.stages.current(role.worldId,role.botId,role.intentId ?? ""), recoveryAssessment: this.recoveryEvaluations.has(`${role.worldId}:${role.botId}`), waitReason: this.modelWaitDetail(role), intentOrigin: role.origin, intentGeneration: role.intentGeneration, threadId: role.thread.threadId, model: role.thread.model,
        reasoningEffort: role.thread.reasoningEffort, serviceTier: role.thread.effectiveServiceTier ?? "unconfirmed" })),
      restoration: [...this.restoreFailures].map(([key, reason]) => { const goal = this.ownerGoals.get(key) ?? this.autonomousGoals.get(key); return { role: goal?.role ?? "unknown", botId: goal?.botId ?? key.split(":").at(-1), worldId: goal?.worldId ?? key.split(":")[0], status: "awaiting_reconciliation", reason }; }) };
  }
  private regionFailureKey(role:Role,kind:string,p:{x:number;y:number;z:number}):string{return `${role.worldId}:${role.botId}:${role.intentId}:${kind}:${Math.floor(p.x/8)},${Math.floor(p.y/8)},${Math.floor(p.z/8)}`;}
  private planningWorld(world: WorldView, botId: string): Record<string,unknown> {
    // Preserve current facts; outstanding results are supplied separately.
    // Full historical receipts remain queryable through observe.
    const view = structuredClone(world) as WorldView & {capabilities?:unknown};
    delete view.capabilities;
    const companions = view.companions.map(body => ({...(body.botId===botId?body:{botId:body.botId,bodyGeneration:body.bodyGeneration,name:body.name,dimension:body.dimension,position:body.position,inventoryState:"not_expanded",action:body.action,paused:body.paused,stopped:body.stopped,localSafety:body.localSafety}),
      actionJournal: body.actionJournal?.slice(body.botId === botId ? -8 : -2),
      actionHistoryOmitted: Math.max(0,(body.actionJournal?.length??0)-(body.botId===botId?8:2)),
    }));
    return {...view,companions};
  }
  private modelWaitDetail(role: Role): string | undefined {
    if (role.run && !role.activeAction) return `${role.run.handle ? "Luna 正在思考" : "正在启动 Luna 回合"} · ${Math.max(0,Math.floor((Date.now()-(role.run.startedAt??Date.now()))/1000))} 秒`;
    if (!role.activeAction && role.suspendedActionIds.size) return `等待核对 ${role.suspendedActionIds.size} 项暂停动作的执行权`;
    return role.waitReason;
  }
  private stageGoal(role:Role):string|undefined {
    const stage=this.stages.current(role.worldId,role.botId,role.intentId ?? "");return stage ? `阶段：${stage.summary}${stage.state === "blocked" ? "（受阻，评估替代工作）" : ""}` : role.command;
  }
  async command(message: string, botId?: string, uiRequestId?: string): Promise<unknown> {
    message = text(message, "message");
    return this.serializeMutation(() => this.commandSerial(message, botId, uiRequestId));
  }
  private async commandSerial(message: string, botId?: string, uiRequestId?: string, expectedWorld?: string, expectedBodies?: BodyView[], skipDeferral = false): Promise<unknown> {
    await this.autoBootstrapTail;
    const connection = this.ready();
    const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
    if (connection !== this.connection || expectedWorld && observed.worldId !== expectedWorld) throw new Error("connection or addressed world changed");
    if (expectedBodies?.some(prior => { const current = observed.companions.find(b => b.botId === prior.botId);
      return !current || current.bodyGeneration !== prior.bodyGeneration || current.controlRevision !== prior.controlRevision || current.paused || current.stopped || current.standby;
    })) throw new Error("addressed body or owner controls changed; old chat instruction was not applied");
      this.view = observed;
      for (const candidate of observed.companions) this.syncRoleAuthoritative(candidate);
    const requestedBodies = observed.companions.filter(b => !botId || b.botId === botId);
    const busy = (body: BodyView): boolean => {
      const role = [...this.roles.values()].find(r => r.botId === body.botId && r.worldId === observed.worldId);
      return Boolean(activeAction(body.action) || role?.run?.valid || role?.suspendedActionIds.size || role?.intentId && this.stages.current(role.worldId,role.botId,role.intentId)?.state === "continue");
    };
    if (!skipDeferral && !this.urgentRequest(message) && requestedBodies.some(busy)) {
      const assignments: unknown[] = [];
      for (const target of requestedBodies) {
        if (!busy(target)) {
          assignments.push(await this.commandSerial(message, target.botId, uiRequestId, observed.worldId, [target], true));
          continue;
        }
        const key = `${observed.worldId}:${target.botId}`, queue = this.deferredRequests.get(key) ?? [];
        if (queue.length >= 8) throw new Error("伙伴待办请求已满（8条），请等待或明确改派");
        const request = { id: uiRequestId ? `${uiRequestId}:${target.botId}` : randomUUID(), worldId: observed.worldId, botId: target.botId, message };
        if (!queue.some(r => r.id === request.id)) {
          await this.journal.append("owner.request.queued", observed.worldId, request);
          queue.push(request); this.deferredRequests.set(key, queue);
        }
        assignments.push({ botId: target.botId, queued: true, requestId: request.id });
      }
      return { accepted: true, completed: false, queued: true, message: "已记入待办；先完成自己的当前工作。需要中断时请明确说立即执行或改派。", assignments };
    }
    if (!skipDeferral && this.urgentRequest(message)) for (const target of requestedBodies) await this.cancelDeferred(observed.worldId, target.botId, "explicit urgent reassignment");
    if (!botId) return this.commandGroupSerial(message, observed, connection, uiRequestId);
    const body = observed.companions.find(body => !botId || body.botId === botId);
    if (!body) throw new Error("当前没有可用伙伴，请先让该伙伴加入游戏");
    let role = [...this.roles.values()].find(role => role.botId === body.botId && role.worldId === observed.worldId);
    if (!role) role = await this.bind(body, observed.worldId);
    if (connection !== this.connection || this.view?.worldId !== observed.worldId) throw new Error("world changed while binding companion");
    const roleRevision = role.revision;
    const intentId = randomUUID();
    if (role.origin === "autonomous") await this.retireAutonomyGoal(role, "owner_command");
    const ownerGoal: OwnerGoal = { worldId: observed.worldId, botId: body.botId, role: role.id, intentId, command: message };
    await this.journal.append("owner.command", observed.worldId, { botId: body.botId, role: role.id, intentId, message, recipientNames:[body.name], ...(uiRequestId ? { uiRequestId } : {}) });
    // Journal durability is an await boundary.  A reconnect or another
    // invalidation may have retired this role while the owner command was
    // being flushed; never send the retask lease to the old connection then.
    if (connection !== this.connection || connection.connectionState !== "ready"
      || this.view?.worldId !== observed.worldId || !this.roles.has(role.id) || role.revision !== roleRevision) {
      throw new Error("connection, world, or role changed before retask");
    }
    // Keep the in-memory recovery index in the same mutation as the durable
    // owner command.  Otherwise a reconnect before the next process restart
    // can restore the previous goal even though the journal has the new one.
    this.ownerGoals.set(`${observed.worldId}:${body.botId}`, ownerGoal);
    this.invalidate(role, "idle");
    role.recoveryAttempts = 0; role.recoveryExhausted = false; role.failureSignature = undefined; this.failedAttempts.delete(`${role.worldId}:${role.botId}`);
    // Owner reassignment cancels the previous body lease before any new plan.
    // Retasking is a lease boundary: the Mod atomically clears both the
    // active action and every suspended checkpoint (for example, mining
    // hidden below an EAT preemption), then makes the body available for the
    // new intent.  This avoids guessing ownership from model text or leaving
    // a window in which an old checkpoint can resume.
    await connection.request("control", { operation: "retask", botId: body.botId }, { bodyGeneration: body.bodyGeneration, timeoutMs: 5000 });
    role.activeAction = undefined; role.lastTerminalActionId = undefined; role.suspendedActionIds.clear();
    role.assignedTaskId = undefined; role.assignedTaskIntentId = undefined; role.teamTaskState = undefined;
    role.origin = "owner"; role.intentId = intentId; role.groupIntentId = undefined; role.command = message; role.intentGeneration = Math.max(0, role.intentGeneration) + 1; role.autonomyEnabled = this.autonomyMode(observed.worldId, body.botId) !== "off"; role.standby = false; role.waitReason = undefined; role.noWorkCorrections = 0; role.pending.clear();this.goalWaits.delete(`${role.worldId}:${role.botId}`);role.rejectionRecoveryUsed=false;role.preparationGrace=false;
    await this.bindIntent(connection, role, observed.companions.find(body => body.botId === role.botId));
    this.trigger(role, "owner_command", { message, intentId });
    return { accepted: true, completed: false, intentId, botId: body.botId, role: role.id };
  }
  private async commandGroupSerial(message: string, observed: WorldView, connection: ModConnection, uiRequestId?: string): Promise<unknown> {
    const bodies = observed.companions.filter(body => !body.stopped && !body.paused && !body.recoveryInvalid).slice(0, AGENT_PROFILES.length);
    if (!bodies.length) throw new Error("当前没有可执行的伙伴，请先点击恢复或检查待核对状态");
    const groupIntentId = randomUUID();
    const assignments: Array<{ botId: string; role: AgentProfile["id"]; intentId: string }> = [];
    const assignedRoles: Role[] = [];
    for (const body of bodies) {
      let role = [...this.roles.values()].find(candidate => candidate.botId === body.botId && candidate.worldId === observed.worldId);
      if (!role) role = await this.bind(body, observed.worldId);
      if (connection !== this.connection || this.view?.worldId !== observed.worldId) throw new Error("world changed while binding squad");
      const roleRevision = role.revision;
      const intentId = `${groupIntentId}:${role.id}`;
      const ownerGoal: OwnerGoal = { worldId: observed.worldId, botId: body.botId, role: role.id, intentId, groupIntentId, command: message };
      if (role.origin === "autonomous") await this.retireAutonomyGoal(role, "owner_command");
      await this.journal.append("owner.command", observed.worldId, { botId: body.botId, role: role.id, intentId, groupIntentId, message, ...(uiRequestId ? { uiRequestId } : {}) });
      if (connection !== this.connection || connection.connectionState !== "ready" || this.view?.worldId !== observed.worldId
        || !this.roles.has(role.id) || role.revision !== roleRevision) throw new Error("connection, world, or role changed before squad retask");
      this.ownerGoals.set(`${observed.worldId}:${body.botId}`, ownerGoal);
      this.invalidate(role, "idle"); role.recoveryAttempts = 0; role.recoveryExhausted = false; this.failedAttempts.delete(`${role.worldId}:${role.botId}`);
      await connection.request("control", { operation: "retask", botId: body.botId }, { bodyGeneration: body.bodyGeneration, timeoutMs: 5000 });
      role.activeAction = undefined; role.lastTerminalActionId = undefined; role.suspendedActionIds.clear();
      role.assignedTaskId = undefined; role.assignedTaskIntentId = undefined; role.teamTaskState = undefined;
      role.origin = "owner"; role.intentId = intentId; role.groupIntentId = groupIntentId; role.command = message; role.intentGeneration = Math.max(0, role.intentGeneration) + 1; role.autonomyEnabled = this.autonomyMode(observed.worldId, body.botId) !== "off"; role.standby = false; role.waitReason = undefined; role.noWorkCorrections = 0; role.pending.clear();this.goalWaits.delete(`${role.worldId}:${role.botId}`);role.rejectionRecoveryUsed=false;role.preparationGrace=false;
      await this.bindIntent(connection, role, observed.companions.find(body => body.botId === role.botId));
      assignedRoles.push(role);
      assignments.push({ botId: body.botId, role: role.id, intentId });
    }
    // Do not let the first role begin planning while the rest of the squad
    // is still being retasked.  Every role must observe the same completed
    // group assignment before any model turn is started.
    for (const role of assignedRoles) this.trigger(role, "owner_command", { message, intentId: role.intentId, groupIntentId });
    return { accepted: true, completed: false, groupIntentId, count: assignments.length, assignments };
  }
  async control(operation: "pause" | "resume" | "stop" | "auto_on" | "auto_off" | "standby", botId?: string): Promise<unknown> {
    if (!["pause", "resume", "stop", "auto_on", "auto_off", "standby"].includes(operation)) throw new Error("invalid control operation");
    return this.serializeMutation(() => this.controlSerial(operation, botId));
  }
  private async controlSerial(operation: "pause" | "resume" | "stop" | "auto_on" | "auto_off" | "standby", botId?: string): Promise<unknown> {
    await this.autoBootstrapTail;
    const connection = this.ready();
    const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
    // Status is read asynchronously.  Treat the captured connection/world as
    // a compare-and-swap token before issuing any control mutation.
    if (connection !== this.connection || connection.connectionState !== "ready"
      || this.view?.worldId !== observed.worldId || connection.sessionContext?.worldId !== observed.worldId) {
      throw new Error("connection or world changed before control");
    }
    // Resume/stop are body lifecycle controls. They must target the
    // authoritative companion list even when no Codex role has been bound
    // yet (for example after a local emergency stop on first startup).
    const targets = observed.companions.filter(body => !botId || body.botId === botId);
    if (botId && !targets.length) throw new Error("companion unavailable");
    if (operation === "stop" || operation === "standby") for (const target of targets) await this.cancelDeferred(observed.worldId, target.botId, operation);
    if (["auto_on", "auto_off", "standby"].includes(operation)) {
      const mode: AutonomousMode = operation === "auto_on" ? "on" : operation === "auto_off" ? "off" : "standby";
      // The Mod is authoritative for stopping ordinary work.  Do this first;
      // a rejected/uncertain control must not change the durable controller
      // mode or discard the in-memory action checkpoint.
      for (const body of targets) await connection.request("control", { operation, botId: body.botId }, { bodyGeneration: body.bodyGeneration, timeoutMs: 5_000 });
      const refreshed = await connection.request<WorldView>("status", {}, { timeoutMs: 5_000 });
      if (connection !== this.connection || connection.connectionState !== "ready" || refreshed.worldId !== observed.worldId || connection.sessionContext?.worldId !== refreshed.worldId) throw new Error("connection or world changed before autonomy mode commit");
      const freshTargets = refreshed.companions.filter(body => !botId || body.botId === botId);
      if (freshTargets.length !== targets.length || freshTargets.some(body => targets.find(previous => previous.botId === body.botId)?.bodyGeneration !== body.bodyGeneration)) throw new Error("body changed before autonomy mode commit; reconcile required");
      this.view = refreshed;
      for (const body of refreshed.companions) this.syncRoleAuthoritative(body);
      this.autonomyModes.set(botId ? `${observed.worldId}:${botId}` : observed.worldId, mode);
      await this.journal.append("autonomy.mode", observed.worldId, { mode, ...(botId ? { botId } : {}), changedAtGameTick: refreshed.gameTick });
      for (const body of freshTargets) {
        const role = [...this.roles.values()].find(candidate => candidate.botId === body.botId && candidate.worldId === observed.worldId);
        if (mode === "on") {
          if (role) { role.autonomyEnabled = true; role.standby = false; role.waitReason = undefined; if (role.status === "idle" && role.intentId) this.trigger(role, "autonomy_enabled", {}); }
        } else if (role && (mode === "standby" || role.origin === "autonomous")) {
          role.autonomyEnabled = mode !== "off"; role.standby = mode === "standby"; role.waitReason = mode === "standby" ? "已进入待命" : "自主模式已关闭";
          await this.suspendPlanning(role, mode === "standby" ? "standby" : "autonomy_off");
        }
      }
      if (mode === "on") await this.ensureAutonomousBodies(refreshed, botId);
      return { operation, mode, count: targets.length, gameTick: refreshed.gameTick };
    }
    for (const body of targets) {
      if (connection !== this.connection || connection.connectionState !== "ready"
        || this.view?.worldId !== observed.worldId || connection.sessionContext?.worldId !== observed.worldId) {
        throw new Error("connection or world changed before control");
      }
      for (const role of this.roles.values()) if (role.botId === body.botId) this.invalidate(role, operation === "resume" ? "idle" : "paused");
      await connection.request("control", { operation, botId: body.botId }, { bodyGeneration: body.bodyGeneration, timeoutMs: 5000 });
      if (operation === "stop") for (const role of this.roles.values()) if (role.botId === body.botId) { role.activeAction = undefined; role.lastTerminalActionId = undefined; role.suspendedActionIds.clear(); }
      for (const role of this.roles.values()) if (role.botId === body.botId && operation === "resume") this.trigger(role, "resumed", {});
    }
    if (operation === "resume") {
      const latest = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
      if (connection !== this.connection || latest.worldId !== observed.worldId) throw new Error("world changed after resume");
      this.view = latest;
      await this.ensureAutonomousBodies(latest, botId);
      await this.wakeReconciledRoles(connection, latest, "resumed", botId);
    }
    return { operation, count: targets.length };
  }
  events(after = 0, limit = 50): unknown {
    if (!Number.isSafeInteger(after) || after < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new Error("invalid event cursor/limit");
    const rows = this.journal.select(this.view?.worldId,()=>true,limit,after,false);
    return { events: rows, nextCursor: rows.at(-1)?.sequence ?? after };
  }
  private sameUiSession(connection: ModConnection, worldId: string, sessionEpoch: number): boolean {
    const context = connection.sessionContext;
    return connection === this.connection && connection.connectionState === "ready" && context?.worldId === worldId && context.sessionEpoch === sessionEpoch;
  }
  private recentUiEvents(limit = 50): readonly unknown[] {
    const publicTypes = new Set(["work.progress", "crew.chat", "cooperation.state", "chat.owner_result", "owner.request.queued", "owner.request.closed", "tool.rejected", "turn.recovery", "owner.command", "role.final", "role.message", "role.shared", "role.failure", "team.proposed", "team.execute.receipt", "ui.claim", "ui.failure"]);
    const visible = this.journal.select(this.view?.worldId,row => {
      if (row.type === "ui.claim" && row.data.operation === "status") return false;
      if (publicTypes.has(row.type)) return true;
      if (row.type !== "game.event") return false;
      const data = recordValue(row.data);
      const event = typeof data?.event === "string" ? data.event : undefined;
      return event === "action.terminal";
    },limit);
    const spoken=this.journal.select(this.view?.worldId,row=>["crew.chat","owner.command","role.shared","owner.request.queued"].includes(row.type),30);
    const merged=new Map([...visible,...spoken].map(row=>[row.sequence,row]));return [...merged.values()].sort((a,b)=>a.sequence-b.sequence);
  }
  private async appendUiDiagnostic(type: "ui.claim" | "ui.failure", worldId: string,
    request: UiRequestBody, data: Record<string, unknown>): Promise<void> {
    // UI events may be replayed by ModLink after a reconnect. Keep one
    // durable diagnostic per request/type and never persist the command text.
    if (this.journal.select(worldId,row => row.type === type && row.data.requestId === request.requestId,1).length>0) return;
    await this.journal.append(type, worldId, { role: "系统", requestId: request.requestId, operation: request.operation, ...data });
  }
  private uiDiagnosticReason(error: unknown): string {
    const message = error instanceof Error ? error.message : String(error);
    return message.replace(/[A-Za-z]:[\\/][^\s"'<>]+/g, "[路径]")
      .replace(/(?:^|\s)\/[^\s"'<>]+/g, " [路径]")
      .replace(/\b(token|password|secret|api[_-]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, "$1=[已隐藏]")
      .slice(0, 160) || "未知错误";
  }
  private async sendUiUpdate(connection: ModConnection, request: UiRequestBody, payload: Record<string, unknown>): Promise<void> {
    if (!this.sameUiSession(connection, connection.sessionContext?.worldId ?? "", connection.sessionContext?.sessionEpoch ?? -1)) return;
    const json = JSON.stringify(payload);
    if (new TextEncoder().encode(json).byteLength > 60 * 1024) {
      await connection.request("ui.update", { requestId: request.requestId, ownerId: request.ownerId, uiSession: request.uiSession, json: JSON.stringify({ ok: false, error: "界面数据过大，已拒绝发送" }) }, { timeoutMs: 5_000 }).catch(() => {});
      return;
    }
    await connection.request("ui.update", { requestId: request.requestId, ownerId: request.ownerId, uiSession: request.uiSession, json }, { timeoutMs: 5_000 }).catch(() => {});
  }
  private async handleUiRequestSerial(connection: ModConnection, event: EventEnvelope, request: UiRequestBody): Promise<boolean> {
    const worldId = event.worldId; const sessionEpoch = event.sessionEpoch;
    let claim: unknown;
    try {
      claim = await connection.request<unknown>("ui.claim", { requestId: request.requestId, ownerId: request.ownerId, uiSession: request.uiSession }, { timeoutMs: 5_000 });
    } catch (error) {
      await this.appendUiDiagnostic("ui.failure", worldId, request, { stage: "claim", reason: this.uiDiagnosticReason(error) }).catch(() => {});
      if (this.sameUiSession(connection, worldId, sessionEpoch)) await this.sendUiUpdate(connection, request, { ok: false, error: `界面请求无法确认：${uiError(error)}` });
      return true;
    }
    const claimBody = recordValue(claim);
    const decision = typeof claimBody?.decision === "string" ? claimBody.decision : "UNKNOWN";
    await this.appendUiDiagnostic("ui.claim", worldId, request, { decision }).catch(() => {});
    if (!this.sameUiSession(connection, worldId, sessionEpoch)) return true;
    if (claimBody?.decision !== "CLAIMED" || claimBody.operation !== request.operation || claimBody.message !== request.message) {
      await this.appendUiDiagnostic("ui.failure", worldId, request, { stage: "claim", reason: "界面请求已过期或重复" }).catch(() => {});
      await this.sendUiUpdate(connection, request, { ok: false, error: "界面请求已过期或重复，未执行操作" });
      return true;
    }
    if (request.operation !== "status" && !this.sameUiSession(connection, worldId, sessionEpoch)) return true;
    try {
      if(request.operation==="detail"||request.operation==="history"){
        const query=object(JSON.parse(request.message));
        if(query.worldId!==worldId)throw new Error("界面已切换世界，请刷新");
        const cursor=query.cursor===undefined?0:Number(query.cursor);
        if(!Number.isSafeInteger(cursor)||cursor<0)throw new Error("invalid history cursor");
        let page:Record<string,unknown>;
        if(request.operation==="detail"){
          const botId=text(query.botId,"botId",128),body=this.view?.companions.find(b=>b.botId===botId);
          if(!body)throw new Error("伙伴不属于当前世界");
          const history=this.stages.history(worldId,botId,Number.MAX_SAFE_INTEGER).slice().reverse();
          const selected=history.slice(cursor,cursor+3),ids=new Set(selected.flatMap(s=>s.actions.slice(-8)));
          const receipts=this.journal.select(worldId,row=>{
            const d=row.type==="action.receipt"?recordValue(row.data.receipt):row.type==="game.event"&&row.data.event==="action.terminal"?recordValue(row.data.body):undefined;
            return !!d&&(d.botId??row.data.botId)===botId&&ids.has(String(recordValue(d.id)?.value??d.actionId??row.data.actionId));
          },128).map(row=>row.type==="action.receipt"?row.data.receipt:row.data.body);
          page={type:"detail",worldId,botId,cursor,rows:stageRows(selected,{...body,actionJournal:[...receipts,...(body.actionJournal??[])]},v=>publicText(v)),nextCursor:cursor+3<history.length?cursor+3:null};
        }else{
          const spoken=this.journal.select(worldId,row=>["crew.chat","owner.command","role.shared","owner.request.queued"].includes(row.type)&&(cursor===0||row.sequence<cursor),21);
          const hasMore=spoken.length>20,selected=spoken.slice(-20);
          const ids=new Set(selected.map(row=>String(row.data.messageId??row.data.uiRequestId??row.data.id??"")));
          const receipts=this.journal.select(worldId,row=>["ui.failure","owner.request.queued","owner.request.closed","chat.owner_result"].includes(row.type)&&ids.has(String(row.data.requestId??row.data.messageId??row.data.id??"")),80);
          page={type:"history",worldId,cursor,rows:messages([...selected,...receipts].sort((a,b)=>a.sequence-b.sequence)),nextCursor:hasMore?selected[0]?.sequence:null};
        }
        await this.sendUiUpdate(connection,request,{ok:true,page});return true;
      }
      let operationResult: Record<string, string | number | boolean> = { operation: request.operation };
      if (request.operation === "status") {
        // Read-only refresh; it never schedules a model turn.
        await this.status();
      } else if (request.operation === "task" || request.operation === "retry") {
        const payload=JSON.parse(request.message);const botId=typeof payload.botId==="string"?payload.botId:"";
        const fresh=await connection.request<WorldView>("status",{},{timeoutMs:5000});if(fresh.worldId!==worldId||!this.sameUiSession(connection,worldId,sessionEpoch))throw Error("世界或连接已变化");this.view=fresh;const current=fresh.companions.find(b=>b.botId===botId);
        if(!current)throw Error("指定伙伴未同步；未改为小队发送");
        if(request.operation==="task") {
          const message=text(payload.message,"message",512);
          operationResult=uiOperationResult(await this.command(message,botId,request.requestId));
        } else {
          const role=[...this.roles.values()].find(r=>r.worldId===worldId&&r.botId===botId);
          if(!role||!role.intentId)throw Error("伙伴尚无目标，请先下达任务");
          if(current.paused||current.stopped||current.standby||role.standby)throw Error("伙伴处于玩家暂停、急停或待命，请先使用恢复控制");
          if(current.recoveryInvalid||this.goalWaits.get(`${worldId}:${botId}`)?.condition?.kind==="unknown_effect")throw Error("存在待核对世界效果，请先核对；未重放旧动作");
          if(role.run||role.awaitingTurn||role.dispatching||role.activeAction||activeAction(current.action))throw Error("伙伴正在执行或思考，请等待当前工作结束");
          await this.journal.append("goal.state",worldId,{botId,intentId:role.intentId,state:"continue",reason:"玩家要求保留目标重新评估",uiRequestId:request.requestId});
          this.goalWaits.delete(`${worldId}:${botId}`);role.waitReason=undefined;role.wakeConditions=undefined;role.status="idle";role.noWorkCorrections=0;role.rejectionRecoveryUsed=false;role.preparationGrace=false;
          role.recoveryExhausted=false;role.recoveryAttempts=0;role.failureSignature=undefined;this.failedAttempts.delete(`${worldId}:${botId}`);
          this.alternativeUsed.delete(`${worldId}:${botId}:${role.intentId}`);
          this.trigger(role,"owner_command",{message:"玩家明确要求重新评估原目标。核对当前位置和库存，选择不同候选、准备或授权开路；不要重放旧动作。"});
          operationResult={operation:"retry",botId,accepted:true};
        }
      } else if (request.operation === "command") {
        if (!request.message) throw new Error("界面命令不能为空");
        operationResult = uiOperationResult(await this.command(request.message, undefined, request.requestId));
      } else {
        operationResult = uiOperationResult(await this.control(request.operation));
      }
      if (!this.sameUiSession(connection, worldId, sessionEpoch)) return true;
      const currentStatus = await this.status();
      const view = makeUiView(currentStatus, this.recentUiEvents());
      await this.sendUiUpdate(connection, request, { ok: true, view, operationResult });
      return true;
    } catch (error) {
      await this.appendUiDiagnostic("ui.failure", worldId, request, { stage: request.operation, reason: this.uiDiagnosticReason(error) }).catch(() => {});
      if (this.sameUiSession(connection, worldId, sessionEpoch)) {
        const currentStatus = await this.status().catch(() => ({}));
        await this.sendUiUpdate(connection, request, { ok: false, error: uiError(error), view: makeUiView(currentStatus, this.recentUiEvents()) });
      }
      return true;
    }
  }
  private async handleUiRequest(connection: ModConnection, event: EventEnvelope, details: Record<string, unknown>): Promise<boolean> {
    const request = uiRequestBody(details);
    if (!request) return true;
    const key = `${event.worldId}:${request.requestId}`;
    if (this.uiRequestHandled.has(key)) return true;
    const inFlight = this.uiRequestInFlight.get(key);
    if (inFlight) return inFlight;
    const operation = this.handleUiRequestSerial(connection, event, request);
    this.uiRequestInFlight.set(key, operation);
    try { return await operation; } finally { this.uiRequestInFlight.delete(key); this.uiRequestHandled.add(key); }
  }
  async toolCall(raw: unknown): Promise<unknown> {
    const toolStartedAt=Date.now();
    try {
      const call = object(raw); const rawArgs=object(call.arguments ?? {});const args=call.tool==="act"?normalizeActArguments(rawArgs):rawArgs;
      const role = [...this.roles.values()].find(role => role.thread.threadId === call.threadId);
      const run = role?.run;
      if (!role || !run || !run.valid || run.connection !== this.connection || (run.handle && run.handle.turnId !== call.turnId)) throw new Error("stale role turn");
      if (run.yieldForBodyResult) throw new Error("body action result pending; current turn is yielding");
      let result: unknown;
      switch (call.tool) {
        case "observe": {
          if(args.contextRef!==undefined){
            const reference=text(args.contextRef,"contextRef",64);
            const archive=this.journal.context(role.worldId,role.botId,reference);
            if(!archive||typeof archive.data.json!=="string"){
              await this.journal.append("context.reference_missing",role.worldId,{botId:role.botId,reference,queryType:"context"});
              result={available:false,reason:"REFERENCE_NOT_AVAILABLE",references:this.journal.contextReferences(role.worldId,role.botId),next:"Use currentExecution and current capabilities. Query one of these exact role-scoped references only if historical detail is needed."};break;
            }
            result={reference,path:archive.data.path,...contextPage(archive.data.json,args.cursor===undefined?0:Number(args.cursor))};break;
          }
          if(args.teamInventory!==undefined){
            for(const k of ["recipes","targets","containers"])if(args[k]===null||Array.isArray(args[k])&&!(args[k] as unknown[]).length)delete args[k];
            if(Object.keys(args).some(k=>k!=="teamInventory"&&!(k==="radius"&&args.radius===32)))throw Error("teamInventory is a separate directed query");
            const known=this.inventoryIdentities(role.worldId,run.lastObserved);
            const query=inventoryQuery(args.teamInventory,role.botId,known);
            const fresh=await run.connection.request<WorldView>("status",{},{timeoutMs:5000});
            if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId||fresh.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("stale team inventory query");
            const peer=fresh.companions.find(b=>b.botId===query.botId)??{botId:query.botId,name:known.find(b=>b.botId===query.botId)?.name};
            const cacheKey=JSON.stringify(["teamInventory",query,inventoryVersion(peer),fresh.companions.find(b=>b.botId===role.botId)?.dimension]);
            if(run.observeCache?.has(cacheKey)){result=run.observeCache.get(cacheKey);break;}
            if((run.queries??0)>=4){result={budgetExhausted:true,queryType:"teamInventory",next:"Use existing resource summary; no scheduled retry."};break;}
            run.queries=(run.queries??0)+1;run.lastObserved=fresh;this.view=fresh;
            result=teamInventory(fresh,role.botId,query.botId,query.items,peer);(run.observeCache??=new Map()).set(cacheKey,result);break;
          }
          // Validate before consuming the observation budget; identical reads reuse this turn snapshot.
          const radius = args.radius ?? 32;
          if (!Number.isInteger(radius) || Number(radius) < 1 || Number(radius) > 32) throw new Error("radius must be 1..32");
          if (!run.valid || run.connection !== this.connection || role.run !== run) throw new Error("stale role turn");
          if(args.targets !== undefined && (!Array.isArray(args.targets)||args.targets.length>32))throw new Error("targets must contain at most 32 positions");
          const targets=args.targets === undefined ? undefined : (args.targets as unknown[]).map(position=>validateActionParameters("MOVE",{position}).parameters.position);
          if(args.recipes!==undefined&&(!Array.isArray(args.recipes)||args.recipes.length>16||args.recipes.some(r=>typeof r!=="string"||!/^[-a-z0-9_.]+:[-a-z0-9_./]+$/.test(r))))throw new Error("invalid recipe queries");
          if(args.containers!==undefined&&(!Array.isArray(args.containers)||args.containers.length>16))throw new Error("invalid container queries");
          const containers=args.containers===undefined?undefined:(args.containers as unknown[]).map(position=>validateActionParameters("MOVE",{position}).parameters.position);
          if(args.recipeCount!==undefined&&(!Number.isSafeInteger(args.recipeCount)||Number(args.recipeCount)<1||Number(args.recipeCount)>64||!args.recipes))throw Error("recipeCount requires recipes and an integer 1..64");
          const queryKey=JSON.stringify([radius,targets??[],args.recipes??[],containers??[],args.recipeCount??1]);
          if(run.observeCache?.has(queryKey)){result=run.observeCache.get(queryKey);break;}
          const directed=!!(targets?.length || (args.recipes as unknown[]|undefined)?.length || containers?.length);
          if(directed ? (run.queries??0)>=4 : run.observations>=2) {
            result={budgetExhausted:true,queryType:directed?"targeted":"environment",observed:run.lastObserved,
              next:"Use the available facts to act or report a concrete waiting condition. No scan_ready event is scheduled by this quota."};break;
          }
          if(directed)run.queries=(run.queries??0)+1;else run.observations++;
          const observed = await run.connection.request<WorldView>("observe", { botId: role.botId, radius, ...(args.recipes?{recipes:args.recipes,recipeCount:args.recipeCount??1}:{}),...(containers?{containers}:{}), ...(targets ? {targets} : {}) }, { timeoutMs: 10_000 });
          const body = observed.companions.find(body => body.botId === role.botId);
          if (!run.valid || run.connection !== this.connection || role.run !== run || !body || body.bodyGeneration !== run.generation || observed.worldId !== role.worldId) { run.valid = false; throw new Error("body changed; re-observation in a new turn required"); }
          this.view = observed;
          run.lastObserved = observed;
          run.reobserveRequired = undefined;
          role.pending.delete("fresh_observation");
          if(args.recipes)this.recipeFacts.set(`${role.worldId}:${role.botId}:${run.intentId}`,(observed as unknown as Record<string,unknown>).recipes);
          result = { ...observed, intentId: run.intentId, capabilities: this.executionCapabilities() };(run.observeCache??=new Map()).set(queryKey,result); break;
        }
        case "act": {
          if (run.communicationOnly) throw new Error("communication turn cannot change body work; discuss first and wait for the action result");
          if (run.ownerChat) throw new Error("owner task already queued; finish this turn before retasking");
          if (run.submitted) throw new Error("one body action per model turn; wait for its result event");
          if (args.intentId !== undefined && args.intentId !== run.intentId) throw new Error("intentId must match current owner mission");
          await this.ensureCurrentBinding(role, run);
          // A server-accepted team task owns this body's executor until its
          // authoritative terminal event.  A normal personal act must not
          // silently replace that lease; the model must execute the task by
          // passing its taskId so the server can enforce the same ownership.
          if (role.assignedTaskId && args.taskId === undefined) throw new Error("team task owns current body; execute taskId");
          if (args.taskId !== undefined) {
            if (args.kind !== undefined || args.parameters !== undefined) throw new Error("team task execution accepts only taskId; use the server-registered definition");
            result = await this.executeTeamAction(role, run, this.namespacedTaskId(run.intentId, text(args.taskId, "taskId", 128)));
            break;
          }
          const validated = validateActionParameters(args.kind, args.parameters ?? {});
          if(run.connection.executionBackend?.id==="numen"&&validated.kind==="COLLECT_RESOURCE"&&validated.parameters.position!==undefined)throw new Error("COLLECT_RESOURCE.position is unsupported: scope is centered on the body at start; omit position");
          if(!(this.executionCapabilities().self as readonly string[]).includes(validated.kind))throw new Error("BACKEND_ACTION_UNAVAILABLE: 当前后端没有接通该动作，请使用本回合能力列表");
          const targetPositions=["MINE","PLACE","EXCAVATE"].includes(validated.kind)?[validated.parameters.position!]:validated.kind==="BUILD"?validated.parameters.steps!.map(s=>s.position):[];
          if(targetPositions.length){
            const checked=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,targets:targetPositions},{timeoutMs:10000});
            if(!run.valid||role.run!==run||run.connection!==this.connection||checked.worldId!==role.worldId||checked.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw new Error("body changed during action preflight");
            this.view=checked;run.lastObserved=checked;
          }
          const target=validated.parameters.position??validated.parameters.steps?.[0]?.position;
          if(target){const key=this.regionFailureKey(role,validated.kind,target);if((this.taskRegionFailures.get(key)??0)>=3)throw new Error("TASK_REGION_STALLED: 同一总任务和目标区域已三次无有效推进；换阶段编号无效。选择不同入口、不同准备/开路动作或明确等待，不原样重试。");}
          // Verified target/fingerprint restrictions below apply; path failures do not prohibit unrelated workstation preparation.
          const contextFingerprint = this.actionContext(role, validated.kind, validated.parameters, run.lastObserved);
          if (this.failedAttempts.get(`${role.worldId}:${role.botId}`)?.has(contextFingerprint)) throw new Error("UNCHANGED_FAILED_ACTION: 相同条件下该动作已失败；重新观察后改变目标、位置或策略，或报告等待条件");
          const intentId = run.intentId;
          const localId = args.actionId === undefined ? `call-${createHash("sha256").update(`${role.worldId}:${role.botId}:${call.threadId}:${call.turnId}:${text(String(call.callId ?? call.rpcRequestId ?? ""),"tool call identity",256)}`).digest("hex").slice(0,40)}` : text(args.actionId,"actionId",80);
          if(!/^[A-Za-z0-9._:-]+$/.test(localId))throw new Error("actionId must use letters, digits, . _ : or -; omit it for automatic identity"); const actionId = `${run.intentId}:${localId}`;
          const payload = { ...validated.parameters, botId: role.botId, kind: validated.kind };
          const fingerprint = JSON.stringify(canonical(payload)); const key = `${role.worldId}:${role.botId}:${actionId}`;
          const previous = this.actionAttempts.get(key);
          if (previous !== undefined) {
            if (previous.fingerprint !== fingerprint) throw new Error("action identity conflicts with a previous request");
            result = { state: "RECONCILE_REQUIRED", actionId, message: "该请求已记录；读取真实动作账本核对，不重放。" }; break;
          }
          this.actionAttempts.set(key, { fingerprint, intentId }); run.submitted = true; run.submittedActionId = actionId;
          this.actionContexts.set(`${role.worldId}:${role.botId}:${actionId}`, contextFingerprint);
          await this.journal.append("action.prepared", role.worldId, { botId: role.botId, actionId, intentId, fingerprint, contextFingerprint, bodyGeneration:run.generation, toolCallId: call.callId ?? call.rpcRequestId });
          await this.stages.attach(role.worldId,role.botId,run.intentId,actionId);
          if (!run.valid || run.connection !== this.connection || role.run !== run) throw new Error("turn superseded before submission");
          this.evidence.apply(role.worldId,role.botId,actionId,"PREPARED",run.generation,fingerprint);
          let receipt: unknown;
          try {
            receipt = await run.connection.request("action.submit", { ...payload, intentGeneration: run.intentGeneration }, { actionId, intentId, bodyGeneration: run.generation, timeoutMs: 10_000 });
          } catch (error) {
            this.acceptEvidence(role,actionId,"RECONCILE_REQUIRED",run.generation,"submit_timeout");
            role.waitReason="提交响应超时，世界效果待核对；不重放请求";
            throw error;
          }
          await this.journal.append("action.receipt", role.worldId, { botId: role.botId, actionId, receipt });
          const receiptState = receipt && typeof receipt === "object" && !Array.isArray(receipt) && typeof (receipt as Record<string, unknown>).state === "string"
            ? String((receipt as Record<string, unknown>).state).toUpperCase() : "ACCEPTED";
          // The body executor remains authoritative even when the model turn
          // timed out or was invalidated while the RPC was in flight.  Track
          // an accepted receipt before checking turn freshness so its later
          // terminal event can reconcile the healthy body action.
          this.acceptEvidence(role,actionId,receiptState,run.generation,"submit_response",recordValue(receipt)?.sequence);
          const receiptDecision=String(recordValue(receipt)?.decision ?? "");
          if(receiptDecision.startsWith("REJECTED") || receiptState === "REJECTED") {
            run.submitted=false;run.submittedActionId=undefined;
            role.status="thinking";
            throw new Error(`${receiptDecision || receiptState}: ${String(recordValue(receipt)?.message ?? "action was not accepted; no execution lease")}`);
          }
          if (!run.valid || run.connection !== this.connection || role.run !== run) {
            result = { state: "RECONCILE_REQUIRED", actionId, receipt, message: "回合已失效；已记录真实回执，需重新核对。" };
          } else {
            result = receipt; role.status = role.activeAction ? "working" : "thinking";
            if (["ACCEPTED", "RUNNING", "COMPLETED", "PARTIAL"].includes(receiptState)) await this.consumeResults(role, run);
            if (["ACCEPTED", "RUNNING", "COMPLETED", "PARTIAL"].includes(receiptState))
              await this.mailbox.markRead(role.worldId, role.botId, (run.inbox ?? []).map(m => m.messageId));
          }
          break;
        }
        case "remember": {
          const entry = text(args.entry, "entry");
          result = await this.journal.append("role.memory", role.worldId, { role: role.id, botId: role.botId, entry }); break;
        }
        case "share": {
          result = await this.sendRoleChat(role, run, args); break;
        }
        case "propose_work": {
          if (args.operation === "owner_task") {
            if (run.submitted) throw new Error("body action already submitted; wait for the next turn before retasking");
            const messageId = text(args.messageId, "messageId", 128);
            const message = this.mailbox.get(role.worldId, messageId);
            if (!message || message.origin !== "player" || !message.recipientIds.includes(role.botId)
              || !run.inbox?.some(m => m.messageId === messageId)) throw new Error("owner_task must reference a player message in this turn inbox");
            if (run.ownerChat && run.ownerChat !== messageId) throw new Error("only one owner request per turn");
            run.ownerChat = messageId;
            result = { queued: true, messageId, message: "Only explicit instructions qualify. Controller will dispatch the original player text after this turn." }; break;
          }
          if (args.operation === "discuss_work") {
            result = await this.sendRoleChat(role, run, { message: text(args.summary, "summary", 200), recipient: args.recipient, replyTo: args.replyTo }); break;
          }
          if(args.operation==="cooperate"){
            if(args.proposalId!==undefined){
              const decision=String(args.decision);if(!["accept","reject"].includes(decision))throw new Error("decision must be accept or reject");
              const proposal=this.cooperation().get(role.worldId,String(args.proposalId));
              if(decision==="accept"&&proposal?.recipient===role.botId&&proposal.state==="proposed"&&proposal.action.kind==="TRANSFER"&&proposal.action.parameters.target===proposal.requester){
                const fresh=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32},{timeoutMs:10000});
                if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId||fresh.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("supply preflight superseded");
                run.lastObserved=fresh;this.view=fresh;const assessment=assessSupply(fresh,proposal);
                await this.journal.append("cooperation.supply_checked",role.worldId,{botId:role.botId,proposalId:proposal.id,assessment});
                if(assessment.state!=="candidate"){result=await this.cooperation().save({...proposal,state:"blocked",reason:String(assessment.reason)});run.preparationRegistered=true;await this.notifyCooperation(result as Cooperation,role.botId);break;}
              }
              result=await this.cooperation().decide(role.worldId,text(args.proposalId,"proposalId",128),role.botId,decision==="accept",typeof args.reason==="string"?args.reason:undefined);
              if(decision==="accept"){role.waitReason=undefined;role.pending.set("cooperation_ready",{proposalId:args.proposalId});}
              await this.notifyCooperation(this.cooperation().get(role.worldId,String(args.proposalId)),role.botId);
            }else{
              const recipient=text(args.recipient,"recipient",128).toLowerCase();const target=run.lastObserved.companions.find(b=>b.botId.toLowerCase()===recipient||b.name.toLowerCase()===recipient);
              if(!target||target.botId===role.botId)throw new Error("choose another companion: "+run.lastObserved.companions.map(b=>b.name).join(", "));
              const category=String(args.category);if(!["yield","transfer","materials","assignment"].includes(category))throw new Error("category must be yield | transfer | materials | assignment; example: {operation:cooperate,category:yield,recipient:Moss,summary:请让出通道}");
              let parameters=args.parameters;
              if(parameters&&typeof parameters==="object"&&!Array.isArray(parameters)){const p=parameters as Record<string,unknown>;if(typeof p.target==="string"){const receiver=run.lastObserved.companions.find(b=>b.botId===p.target||b.name.toLowerCase()===String(p.target).toLowerCase());if(receiver)parameters={...p,target:receiver.botId};}}
              const action=validateActionParameters(category==="yield"?"YIELD":args.kind,category==="yield"?{}:parameters);
              const materialRequest=["materials","transfer"].includes(category)&&action.kind==="TRANSFER"&&action.parameters.target===role.botId;
              if(materialRequest&&action.parameters.resource){
                const check=await this.selfPreparation(role,run,action.parameters.resource,action.parameters.count??1);
                if(check){run.preparationRegistered=true;result={proposed:false,public:false,reason:"SELF_PREPARATION_AVAILABLE",preparation:check,next:{tool:"act",kind:"COLLECT_RESOURCE",parameters:{resource:action.parameters.resource,count:action.parameters.count??1}}};break;}
              }
              const id=createHash("sha256").update(`${role.worldId}:${role.botId}:${call.turnId}:${call.callId??call.rpcRequestId}`).digest("hex").slice(0,32);
              const prior=this.cooperation().get(role.worldId,id);
              result=prior??await this.cooperation().save({id,worldId:role.worldId,requester:role.botId,recipient:target.botId,summary:text(args.summary,"summary",200),category:category as Cooperation["category"],action,state:"proposed",...(action.kind==="TRANSFER"?{supplierId:target.botId,beneficiaryId:action.parameters.target}:{}),...(typeof args.messageId==="string"?{messageId:args.messageId}:{})});
              const cycles=await this.cooperation().resolveCycles(role.worldId);
              if(cycles.length){for(const bot of cycles){const r=[...this.roles.values()].find(r=>r.worldId===role.worldId&&r.botId===bot);if(r){r.waitReason=undefined;this.goalWaits.delete(`${r.worldId}:${r.botId}`);this.trigger(r,"cooperation_changed",{reason:"CYCLIC_SUPPLY_WAIT: inspect own recipes, tools and workstations; no new circular request"});}}result={...(result as object),state:"blocked",reason:"CYCLIC_SUPPLY_WAIT"};}
              else {const receiver=[...this.roles.values()].find(r=>r.worldId===role.worldId&&r.botId===target.botId);if(receiver)this.trigger(receiver,"cooperation_proposed",{proposalId:id});}
            }break;
          }
          if (run.communicationOnly) throw new Error("communication turn: use cooperate to record a proposal; direct body work is forbidden");
          if(args.operation === "stage") {
            const stageId=text(args.stageId,"stageId",80);
            const prior=this.stages.get(role.worldId,role.botId,run.intentId,stageId);
            const state=String(args.state ?? "continue");if(!["continue","complete","blocked"].includes(state))throw new Error("stage state must be continue, complete or blocked");
            if(!prior && state!=="continue"){result={accepted:false,state:"not_created",allowedNext:["continue"],reason:"Create the stage with summary, scope and completion before updating its state; or continue the original body task"};(run.toolRejections??=[]).push("STAGE_TRANSITION: create with continue; stage was not changed");break;}
            const currentStage=this.stages.current(role.worldId,role.botId,run.intentId);
            if(!prior&&currentStage?.state==="continue")throw new Error("continue the current stage, or explicitly record it blocked before changing work");
            if(prior?.state==="complete") {result=prior;break;}
            if(args.title!==undefined)text(args.title,"title",48);
            if(args.stagePurpose!==undefined&&!(STAGE_PURPOSES as readonly unknown[]).includes(args.stagePurpose))throw Error("invalid stagePurpose");
            if(args.proposalId!==undefined&&!this.cooperation().list(role.worldId,role.botId).some(p=>p.id===args.proposalId))throw Error("stage proposal must belong to this companion");
            let stage=prior ?? {...(args.stagePurpose?{purpose:args.stagePurpose as typeof STAGE_PURPOSES[number]}:{}),...(typeof args.proposalId==="string"?{proposalId:args.proposalId}:{}),worldId:role.worldId,botId:role.botId,intentId:run.intentId,stageId,summary:text(args.summary,"summary",500),...(args.title!==undefined?{title:text(args.title,"title",48)}:{}),scope:text(args.scope,"scope",1000),completion:completionSpec(args.completion),actions:[],state:"continue" as const};
            if(state==="complete") {
              const targets=stage.completion.kind==="blocks"?stage.completion.steps.map(s=>s.position):[];
              const inspections:unknown[]=[];
              let fresh=await run.connection.request<WorldView>("status",{},{timeoutMs:5000});
              for(let i=0;i<targets.length;i+=32){
                if(!run.valid||role.run!==run||run.connection!==this.connection)throw new Error("stage verification superseded");
                fresh=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,targets:targets.slice(i,i+32)},{timeoutMs:10000});inspections.push(...(fresh.targetInspections??[]));
              }
              if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId)throw new Error("stage turn changed");
              const actual=fresh.companions.find(b=>b.botId===role.botId);
              if(!actual||actual.bodyGeneration!==run.generation||activeAction(actual.action)||role.assignedTaskId||role.suspendedActionIds.size)throw new Error("stage still has active or unsettled work");
              if(!stageSatisfied(stage,actual,inspections))throw new Error("stage completion conditions not verified in the actual world");
              run.lastObserved=fresh;this.view=fresh;
              stage=await this.stages.resolveVerified(stage,actual,fresh.gameTick);
              if(!stage.actions.length||this.stages.unresolved(stage).length)throw new Error("stage contains unresolved effects; inspect harvestEvidence/pendingDrops; known failures can resolve after independently verified goal, receipts never overwritten");
            }
            if(!prior&&state==="continue")run.preparationRegistered=true;
            if(state==="blocked") {
              const reason=typeof args.reason==="string"?text(args.reason,"reason",1000):stage.summary;
              const condition=parseWait(args.condition??{kind:/path|NAV|stance|通路|路径|不可达|入口|出口/i.test(reason)?"path":"model_recovery"},reason,run.lastObserved.gameTick,role.recoveryAttempts??0);
              if(condition.peerId){const peers=resolveRecipients(condition.peerId,run.lastObserved.companions,[...this.roles.values()],role.botId);if(peers.length!==1)throw Error("condition.peerId requires one other companion");condition.peerId=peers[0].botId;}
              run.goalState="blocked";run.goalReason=reason;run.goalCondition=condition;
              role.noWorkCorrections=0;
            }
            await this.stages.save({...stage,state:state as "continue"|"complete"|"blocked",...(state==="complete"?{verifiedAtTick:run.lastObserved.gameTick,verifiedDimension:run.lastObserved.companions.find(b=>b.botId===role.botId)?.dimension}:{}),...(args.reason?{reason:text(args.reason,"reason",1000)}:{})});
            result=this.stages.get(role.worldId,role.botId,run.intentId,stageId);break;
          }
          const requestedState = args.operation === "goal_state" ? args.state : undefined;
          if (requestedState !== undefined) {
            const fresh = await run.connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
            if (!run.valid || role.run !== run || role.intentId !== run.intentId || run.connection !== this.connection || fresh.worldId !== role.worldId) throw new Error("stale goal state");
            const currentBody = fresh.companions.find(body => body.botId === role.botId);
            if (!currentBody || currentBody.bodyGeneration !== run.generation) throw new Error("body changed before goal state");
            this.view = fresh; this.syncTeamAssignments(fresh); this.syncActiveAction(role, currentBody);
            if (!["continue", "complete", "blocked", "standby"].includes(String(requestedState))) throw new Error("goal_state.state must be continue, complete, blocked, or standby");
            if (args.taskId !== undefined || args.recipient !== undefined || args.kind !== undefined || args.parameters !== undefined) throw new Error("goal_state cannot contain team task fields");
            const reason = args.reason === undefined ? undefined : text(args.reason, "reason", 1000);
            const wakeConditions = args.wakeConditions === undefined ? undefined : Array.isArray(args.wakeConditions) && args.wakeConditions.length <= 8 && args.wakeConditions.every((entry): entry is string => typeof entry === "string" && entry.length > 0 && entry.length <= 200) ? args.wakeConditions : (() => { throw new Error("wakeConditions must contain at most 8 text conditions"); })();
            if (["complete", "standby"].includes(String(requestedState)) && (this.stages.current(role.worldId,role.botId,run.intentId)?.state === "continue" || role.activeAction || role.assignedTaskId || role.suspendedActionIds.size > 0)) throw new Error("当前动作或团队任务尚未完成，不能结束目标");
            const condition=parseWait(args.condition,reason??"等待具体条件",fresh.gameTick,role.recoveryAttempts??0);
            if(condition.peerId){const peers=resolveRecipients(condition.peerId,fresh.companions,[...this.roles.values()],role.botId);if(peers.length!==1)throw Error("condition.peerId requires one other companion");condition.peerId=peers[0].botId;}
            run.lastObserved=fresh;
            run.goalState = goalStateForSource(requestedState as string,run.origin,role.command) as GoalState; run.goalReason = reason; run.goalWakeConditions = wakeConditions;run.goalCondition=condition;
            result = { state: run.goalState, ...(run.goalState !== requestedState ? {notice:"模型遇阻保留自主模式，请等待实际条件变化"} : {}), ...(reason ? { reason } : {}), ...(wakeConditions ? { wakeConditions } : {}), accepted: true };
            break;
          }
          const localTaskId = text(args.taskId, "taskId", 80);
          const recipientId = text(args.recipient, "recipient", 128);
          if (typeof args.kind === "string" && !(this.executionCapabilities().team as readonly string[]).includes(args.kind.toUpperCase())) throw new Error("TEAM_ACTION_UNSUPPORTED: 团队不能委派该动作；GATHER仅支持接收者本人act，可先share请求协作，未派发成功");
          if (args.intentId !== undefined && args.intentId !== run.intentId) throw new Error("intentId must match current owner mission");
          const resolved = resolveRecipients(recipientId, run.lastObserved.companions, [...this.roles.values()]);
          if (resolved.length !== 1) throw new Error("team task requires exactly one named recipient");
          const recipient = [...this.roles.values()].find(candidate => candidate.botId === resolved[0]!.botId && candidate.worldId === role.worldId);
          if (!recipient || !recipient.intentId || recipient.status === "paused" || recipient.status === "disconnected" || recipient.status === "awaiting_body") throw new Error("recipient is not an active squad member");
          if (role.groupIntentId ? recipient.groupIntentId !== role.groupIntentId : recipient !== role) throw new Error("recipient is outside the current squad intent");
          const recipientIntentId = recipient.intentId;
          const recipientRevision = recipient.revision;
          const taskId = this.namespacedTaskId(recipientIntentId, localTaskId);
          if ((recipient.assignedTaskId && recipient.assignedTaskId !== taskId) ||
            (!recipient.assignedTaskId && (recipient.activeAction || recipient.suspendedActionIds.size > 0))) throw new Error("recipient already owns or executes another task");
          const description = text(args.summary, "summary", 2000);
          const validated = validateActionParameters(args.kind, args.parameters ?? {});
          if (!run.valid || run.connection !== this.connection || role.run !== run) throw new Error("stale role turn");
          if (run.reobserveRequired) throw new Error(run.reobserveRequired);
          if (recipient.bodyGeneration === undefined) throw new Error("recipient body generation is unavailable");
          // A proposal is a world-dependent write.  Read the current state
          // immediately before sending it, then compare only the recipient
          // body/task facts that can invalidate this plan.  A coordinator may
          // have been thinking while another role mined, crafted, or received
          // an item; that old turn must observe again instead of reserving a
          // second task from stale inventory/ledger data.
          const current = await run.connection.request<WorldView>("status", {}, { timeoutMs: 5_000 });
          if (!run.valid || run.connection !== this.connection || role.run !== run || role.intentId !== run.intentId
            || current.worldId !== role.worldId || run.connection.sessionContext?.worldId !== current.worldId) throw new Error("stale role turn");
          const target = validated.kind === "TRANSFER" ? validated.parameters.target : undefined;
          const staleReason = stalePlanningReason(run, current, recipient.botId, target);
          if (staleReason) {
            const message = `REOBSERVE_REQUIRED: ${staleReason}; call observe before proposing work`;
            run.reobserveRequired = message;
            // Keep one explicit reason for the next turn.  This is consumed by
            // the normal finally/drain path and does not cancel a healthy
            // executor or loop status polling inside the current turn.
            role.pending.set("fresh_observation", { recipientBotId: recipient.botId, reason: staleReason });
            throw new Error(message);
          }
          const currentRecipient = current.companions.find(body => body.botId === recipient.botId);
          if (!currentRecipient || currentRecipient.bodyGeneration !== recipient.bodyGeneration) {
            const message = "REOBSERVE_REQUIRED: recipient body generation changed; call observe before proposing work";
            run.reobserveRequired = message;
            role.pending.set("fresh_observation", { recipientBotId: recipient.botId, reason: "body generation changed" });
            throw new Error(message);
          }
          // The read above is not a lease.  Recheck all in-memory CAS tokens
          // after the await and before the world-writing request.
          if (!run.valid || run.connection !== this.connection || role.run !== run || role.intentId !== run.intentId
            || recipient.revision !== recipientRevision || recipient.intentId !== recipientIntentId || recipient.worldId !== role.worldId) throw new Error("stale role turn");
          if (recipient !== role && (activeAction(currentRecipient.action) || recipient.suspendedActionIds.size > 0)) throw new Error("RECIPIENT_BUSY: 队友优先完成自己的工作；请share协商，空闲后再提案，未派发成功");
          const teamBody = { taskId, botId: recipient.botId, description, kind: validated.kind, ...validated.parameters };
          const receipt = await run.connection.request("team.propose", { ...teamBody, intentGeneration: recipient.intentGeneration }, { intentId: recipientIntentId, bodyGeneration: currentRecipient.bodyGeneration, timeoutMs: 10_000 });
          const stillCurrent = run.valid && run.connection === this.connection && role.run === run && role.intentId === run.intentId
            && recipient.revision === recipientRevision && recipient.intentId === recipientIntentId && recipient.worldId === role.worldId;
          await this.journal.append("team.proposed", role.worldId, { taskId, localTaskId, senderBotId: role.botId, recipientBotId: recipient.botId, senderIntentId: run.intentId, recipientIntentId, receipt });
          if (!stillCurrent) { result = { state: "RECONCILE_REQUIRED", taskId, receipt, message: "团队提案回执到达时接收者或发送者已换代；未写入任务归属。" }; break; }
          const decision = receipt && typeof receipt === "object" && !Array.isArray(receipt) && typeof (receipt as Record<string, unknown>).decision === "string"
            ? String((receipt as Record<string, unknown>).decision).toUpperCase() : "";
          if (decision === "ACCEPTED" || decision === "IDEMPOTENT_REPLAY") {
            const newAssignment = recipient.assignedTaskId !== taskId;
            recipient.assignedTaskId = taskId; recipient.assignedTaskIntentId = recipient.intentId;
            // A proposal can arrive while the recipient is still thinking
            // about an older personal plan.  If that turn has not submitted a
            // body action, retire it immediately so the new assignment gets a
            // fresh turn.  Once an action was accepted, its executor owns the
            // body and the existing terminal-driven path must finish it.
            if (newAssignment && recipient !== role && recipient.run && !recipient.run.submitted) this.invalidate(recipient, "idle");
            if (newAssignment && recipient !== role && (recipient.status === "idle" || recipient.run !== undefined)) this.trigger(recipient, "team_task", { taskId, from: role.botId, summary: description });
          }
          result = receipt && typeof receipt === "object" && !Array.isArray(receipt) ? { ...receipt, taskId } : { taskId, receipt };
          break;
        }
        default: throw new Error("unknown game tool");
      }
      await this.journal.append("tool.finished",role.worldId,{botId:role.botId,threadId:call.threadId,turnId:call.turnId,tool:call.tool,durationMs:Date.now()-toolStartedAt});
      return { contentItems: [{ type: "inputText", text: JSON.stringify(result) }], success: true };
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const call = recordValue(raw), role = [...this.roles.values()].find(r => r.thread.threadId === call?.threadId);
      if(role?.run?.valid)(role.run.toolRejections??=[]).push(reason);
      if (role) await this.journal.append("tool.rejected", role.worldId, { botId: role.botId, threadId: role.thread.threadId, turnId: call?.turnId, tool: call?.tool, durationMs:Date.now()-toolStartedAt, reason: uiError(error) }).catch(() => {});
      return { contentItems: [{ type: "inputText", text: JSON.stringify({ accepted: false, code: reason.startsWith("TEAM_ACTION_UNSUPPORTED") ? "TEAM_ACTION_UNSUPPORTED" : reason.startsWith("UNCHANGED_FAILED_ACTION") ? "UNCHANGED_FAILED_ACTION" : "TOOL_REJECTED", reason, allowedNext: ["observe", "share", "choose_supported_action", "report_wait_condition"], capabilities: this.executionCapabilities(), requestedAction: (this.executionCapabilities().actions as Record<string,unknown>)[String(recordValue(call?.arguments)?.kind ?? "").toUpperCase()], completionExamples: {inventory:{kind:"inventory",resource:"minecraft:cobblestone",count:8},position:{kind:"position",position:{x:0,y:64,z:0},dimension:"minecraft:overworld"},blocks:{kind:"blocks",steps:[{position:{x:0,y:64,z:0},block:"minecraft:crafting_table"}]}}, actualBody: role ? this.view?.companions.find(b => b.botId === role.botId) : undefined }) }], success: false };
    }
  }
  private async selfPreparation(role:Role,run:NonNullable<Role["run"]>,resource:string,count:number):Promise<unknown> {
    if(count>64)return undefined; // A bounded resource action cannot promise a larger automatic preparation batch.
    let fresh=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,recipes:[resource],recipeCount:1},{timeoutMs:10000});
    if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId||fresh.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("preparation preflight superseded");
    const first=(fresh as unknown as {recipes?:Record<string,any>[]}).recipes?.find(r=>r.output===resource);
    const repetitions=Math.ceil(count/Math.max(1,Number(first?.count??1)));
    if(repetitions>1){fresh=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,recipes:[resource],recipeCount:repetitions},{timeoutMs:10000});if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId||fresh.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("preparation preflight superseded");}
    run.lastObserved=fresh;this.view=fresh;
    const recipes=(fresh as unknown as {recipes?:Record<string,any>[]}).recipes??[];
    return recipes.find(r=>r.output===resource&&r.outputCapacity!==false&&r.selfPreparation?.state==="ready_step");
  }
  private async executeTeamAction(role: Role, run: NonNullable<Role["run"]>, taskId: string): Promise<unknown> {
    await this.ensureCurrentBinding(role, run);
    if (role.assignedTaskId !== taskId || role.assignedTaskIntentId !== run.intentId) throw new Error("team task is not assigned to the current owner intent");
    if (role.teamTaskState && TEAM_RECONCILE_STATES.has(role.teamTaskState)) {
      throw new Error(`team task is ${role.teamTaskState}; reconcile the authoritative result before executing it again`);
    }
    const body = { taskId, botId: role.botId };
    run.submitted = true; run.submittedTaskId = taskId;
    await this.journal.append("team.execute.prepared", role.worldId, { botId: role.botId, taskId, intentId: run.intentId });
    if (!run.valid || run.connection !== this.connection || role.run !== run) throw new Error("turn superseded before team execution");
    const receipt = await run.connection.request("team.execute", { ...body, intentGeneration: run.intentGeneration }, { intentId: run.intentId, bodyGeneration: run.generation, timeoutMs: 10_000 });
    await this.journal.append("team.execute.receipt", role.worldId, { botId: role.botId, taskId, intentId: run.intentId, receipt });
    const details = receipt && typeof receipt === "object" && !Array.isArray(receipt) ? receipt as Record<string, unknown> : undefined;
    const actionId = details ? actionIdFromValue(details.actionId) ?? actionIdFromValue(details.id) : undefined;
    const state = typeof details?.state === "string" ? details.state.toUpperCase() : "";
    const reconcileOnly = state === "RECONCILE_REQUIRED" || !actionId;
    if (state === "PARTIAL" || state === "RECONCILE_REQUIRED") {
      // An explicit uncertain/partial result retains the lease and waits for
      // authoritative reconciliation.  It must not wake another turn.
      role.teamTaskState = state;
      role.pending.set(`team_reconcile:${taskId}`, { taskId, receipt });
    } else if (reconcileOnly) {
      // A temporary admission refusal (for example a resource conflict) has
      // no action identity to reconcile.  Preserve ownership, but allow a
      // later team.changed event to retry once the reservation changes.
      role.pending.set(`team_wait:${taskId}`, { taskId, receipt });
    }
    if (actionId) { await this.stages.attach(role.worldId,role.botId,run.intentId,actionId); run.submittedActionId = actionId; this.acceptEvidence(role,actionId,state || "ACCEPTED",run.generation,"team_response",recordValue(receipt)?.sequence); }
    role.status = role.activeAction ? "working" : "idle";
    if (actionId && run.valid && role.run === run && run.connection === this.connection) await this.consumeResults(role, run);
    if (actionId && run.valid && role.run === run && run.connection === this.connection)
      await this.mailbox.markRead(role.worldId, role.botId, (run.inbox ?? []).map(m => m.messageId));
    return receipt;
  }
  private ready(): ModConnection {
    if (!this.connection || this.connection.connectionState !== "ready" || !this.view) throw new Error("game is disconnected or awaiting reconciliation");
    return this.connection;
  }
  private serializeMutation<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.mutationTail.then(operation, operation);
    this.mutationTail = next.then(() => undefined, () => undefined);
    return next;
  }
  private namespacedTaskId(intentId: string, localTaskId: string): string {
    const prefix = `${intentId}:`;
    const full = localTaskId.startsWith(prefix) ? localTaskId : `${prefix}${localTaskId}`;
    if (full.length > 128) throw new Error("taskId exceeds the 128-character wire limit");
    return full;
  }
  private syncTeamAssignments(observed: WorldView): void {
    const team = observed.team;
    if (!team || (team.worldId !== undefined && team.worldId !== observed.worldId)) return;
    const snapshots = new Map<string, string>();
    const ledger = recordValue(team.ledger);
    const tasks = Array.isArray(ledger?.tasks) ? ledger.tasks : [];
    for (const raw of tasks) {
      const snapshot = recordValue(raw); const task = recordValue(snapshot?.task);
      const taskId = taskIdFromValue(task?.id);
      const state = typeof snapshot?.state === "string" ? snapshot.state.toUpperCase() : undefined;
      if (taskId && state) snapshots.set(taskId, state);
    }
    const works = Array.isArray(team.works) ? team.works : [];
    for (const role of this.roles.values()) {
      const candidate = works.map(recordValue).find(work => {
        const taskId = taskIdFromValue(work?.taskId); const botId = taskIdFromValue(work?.botId);
        const intentId = typeof work?.intentId === "string" ? work.intentId : undefined;
        const generation = work?.bodyGeneration;
        const state = taskId ? snapshots.get(taskId) : undefined;
        // A missing or unfamiliar ledger state is unsafe to interpret as
        // released.  Keep the work owned and make the next operation
        // reconcile it against the authoritative server state.
        const ownershipState = state === undefined || !TEAM_KNOWN_STATES.has(state) ? "RECONCILE_REQUIRED" : state;
        return Boolean(taskId && botId === role.botId && intentId === role.intentId &&
          typeof generation === "number" && generation === role.bodyGeneration &&
          TEAM_ACTIVE_STATES.has(ownershipState));
      });
      const candidateId = taskIdFromValue(candidate?.taskId);
      if (candidateId) {
        const rawState = snapshots.get(candidateId);
        const state = rawState === undefined || !TEAM_KNOWN_STATES.has(rawState) ? "RECONCILE_REQUIRED" : rawState;
        role.assignedTaskId = candidateId;
        role.assignedTaskIntentId = role.intentId;
        role.teamTaskState = state;
        if (TEAM_RECONCILE_STATES.has(state)) role.pending.set(`team_reconcile:${candidateId}`, { taskId: candidateId, state });
        else {
          for (const reason of role.pending.keys()) {
            if (reason === `team_reconcile:${candidateId}` || reason === `team_wait:${candidateId}`) role.pending.delete(reason);
          }
        }
      } else if (!role.activeAction && !role.run) {
        role.assignedTaskId = undefined;
        role.assignedTaskIntentId = undefined;
        role.teamTaskState = undefined;
        for (const reason of role.pending.keys()) if (reason.startsWith("team_reconcile:") || reason.startsWith("team_wait:")) role.pending.delete(reason);
      }
    }
    if (typeof team.revision === "number" && Number.isSafeInteger(team.revision) && team.revision >= 0) this.teamSnapshotRevision = team.revision;
  }
  private async reconcileTeamChanged(connection: ModConnection, worldId: string, details: Record<string, unknown>): Promise<void> {
    const revision = details.revision;
    if (typeof revision === "number" && Number.isSafeInteger(revision) && this.teamEventRevision !== undefined && revision <= this.teamEventRevision) return;
    const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
    if (connection !== this.connection || connection.connectionState !== "ready" || observed.worldId !== worldId) return;
    this.view = observed;
    this.syncTeamAssignments(observed);
    if (typeof revision === "number" && Number.isSafeInteger(revision) && revision >= 0) this.teamEventRevision = revision;
    else if (typeof observed.team?.revision === "number" && Number.isSafeInteger(observed.team.revision) && observed.team.revision >= 0) this.teamEventRevision = observed.team.revision;
    for (const role of this.roles.values()) {
      if (role.worldId !== worldId || !role.assignedTaskId) continue;
      if (role.run) role.pending.set("team_changed", { revision: observed.team?.revision ?? revision });
      else if (role.status === "idle" && !role.activeAction) this.trigger(role, "team_changed", { revision: observed.team?.revision ?? revision });
    }
  }
  private shareKey(worldId: string, intentId: string, sender: string, recipient: string, message: string): string {
    return JSON.stringify([worldId, intentId, sender, recipient, message]);
  }
  private async sendRoleChat(role: Role, run: NonNullable<Role["run"]>, args: Record<string, unknown>): Promise<unknown> {
    if (!run.valid || role.run !== run || run.connection !== this.connection) throw new Error("stale chat turn");
    const purpose=typeof args.purpose === "string" ? args.purpose : "progress";
    if(!["coordination","stage_result","help","discovery","danger","answer","progress","social"].includes(purpose))throw new Error("invalid share purpose");
    if(purpose==="progress"){await this.journal.append("work.progress",role.worldId,{botId:role.botId,stageId:args.stageId,message:publicText(text(args.message,"message",200))});return {recorded:true,public:false,reason:"ordinary_progress_is_internal",next:"Continue actual work; only report verified collaboration impact."};}
    const stageId=typeof args.stageId==="string"?args.stageId:this.stages.current(role.worldId,role.botId,run.intentId)?.stageId;
    const stage=stageId ? this.stages.get(role.worldId,role.botId,run.intentId,stageId):undefined;

    const message = publicText(text(args.message, "message", 400)).replace(/[\u0000-\u001f§]/g, " ");
    if ([...message].length > 200) throw new Error("public chat is limited to 200 characters");
    const fresh=await run.connection.request<WorldView>("status",{}, {timeoutMs:5000});
    if(!run.valid||role.run!==run||run.connection!==this.connection||fresh.worldId!==role.worldId||fresh.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("report evidence changed; reobserve current body");
    run.lastObserved=fresh;
    const bodies = fresh.companions;
    const self = bodies.find(b => b.botId === role.botId);
    if (!self) throw new Error("speaker body unavailable");
    let target = typeof args.recipient === "string" ? args.recipient.replace(/^@/, "") : "team";
    const replyTo = args.replyTo === undefined ? (run.communicationOnly ? run.inbox?.at(-1)?.messageId : undefined) : text(args.replyTo, "replyTo", 128);
    const parent = replyTo ? this.mailbox.get(role.worldId, replyTo) : undefined;
    if (replyTo && (!parent || parent.senderId !== role.botId && !parent.recipientIds.includes(role.botId))) throw new Error("reply is outside this body's conversation");
    const proposalId=typeof args.proposalId==="string"?args.proposalId:stage?.proposalId;
    const proposal=proposalId?this.cooperation().get(role.worldId,proposalId):undefined;
    const resultRef=typeof args.resultRef==="string"?args.resultRef:undefined;
    const resultRecord=resultRef?this.journal.action(role.worldId,role.botId,resultRef).filter(r=>r.type==="game.event"&&r.data.event==="action.terminal").at(-1):undefined;
    let verifiedFacility=false;let verifiedFacilityKey:string|undefined;
    if(purpose==="stage_result"&&stage?.state==="complete"&&stage.purpose==="public_build"&&stage.completion.kind==="blocks"&&stage.verifiedDimension===self.dimension){
      const inspections:unknown[]=[];
      for(let i=0;i<stage.completion.steps.length;i+=32){
        const checked=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,targets:stage.completion.steps.slice(i,i+32).map(s=>s.position)},{timeoutMs:10000});
        if(!run.valid||role.run!==run||run.connection!==this.connection||checked.worldId!==role.worldId||checked.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("facility verification superseded");
        inspections.push(...(checked.targetInspections??[]));
      }
      verifiedFacility=stageSatisfied(stage,self,inspections);
    }
    if(purpose==="stage_result"&&resultRecord){
      const receipt=recordValue(resultRecord.data.body)??{},payload=recordValue(receipt.payload)??{};
      const epoch=recordValue(receipt.epoch)??{};
      const steps=payload.kind==="PLACE"?[{position:payload.position,block:payload.resource}]:payload.kind==="BUILD"&&Array.isArray(payload.steps)?payload.steps:[];
      const facilities=steps.filter((s:any)=>s.position&&/^minecraft:(crafting_table|furnace|smoker|blast_furnace|chest|barrel)$/.test(String(s.block))).slice(0,16);
      if(receipt.botId===role.botId&&!receipt.historical&&receipt.state==="COMPLETED"&&epoch.bodyGeneration===run.generation&&facilities.length){
        const checked=await run.connection.request<WorldView>("observe",{botId:role.botId,radius:32,targets:facilities.map((s:any)=>s.position)},{timeoutMs:10000});
        if(!run.valid||role.run!==run||run.connection!==this.connection||checked.worldId!==role.worldId||checked.companions.find(b=>b.botId===role.botId)?.bodyGeneration!==run.generation)throw Error("facility verification superseded");
        const confirmed=(checked.targetInspections??[]).map(v=>recordValue(v)??{}).filter(row=>row.publicUsable===true&&facilities.some((s:any)=>row.block===s.block&&JSON.stringify(row.position)===JSON.stringify(s.position)));
        if(confirmed.length)verifiedFacilityKey=publicFacilityKey(self.dimension,confirmed);
      }
    }
    if(proposal&&proposal.requester===role.botId&&proposal.action.kind==="TRANSFER"&&proposal.action.parameters.target===role.botId&&["help","coordination"].includes(purpose)){
      const preparation=await this.selfPreparation(role,run,proposal.action.parameters.resource!,proposal.action.parameters.count??1);
      if(preparation){run.preparationRegistered=true;return {public:false,reason:"SELF_PREPARATION_AVAILABLE",preparation,next:"Use COLLECT_RESOURCE to prepare your own requested tool and then resume the original task."};}
    }
    let decision=reportDecision(purpose,{verifiedFacilityKey,verifiedFacility,world:role.worldId,bot:role.botId,intent:run.intentId,tick:run.lastObserved.gameTick,origin:run.origin,goalComplete:run.goalState==="complete"&&!this.stages.current(role.worldId,role.botId,run.intentId),stage,proposal,parent,body:self,result:resultRecord?recordValue(resultRecord.data.body):undefined,resultRef,condition:(run.goalState==="blocked"?run.goalCondition:undefined)??this.goalWaits.get(`${role.worldId}:${role.botId}`)?.condition});
    const silent=async(reason:string)=>{await this.journal.append("work.progress",role.worldId,{botId:role.botId,stageId,message,purpose,reason});return {recorded:true,public:false,reason,next:"继续实际工作；不要换措辞重发或唤醒队友。"};};
    if(!decision.publish)return silent(decision.reason);
    target=decision.recipient??target;
    const recipients=resolveRecipients(target,bodies,[...this.roles.values()],role.botId);
    if((run.publicCount??0)>=2)return silent("public_turn_limit");
    if(!["help","danger","answer","stage_result"].includes(purpose)&&!run.chatReset&&(run.chatDepth??0)>=4)return silent("message_chain_limit");
    decision=await this.reporting().claim(role.worldId,role.botId,run.lastObserved.gameTick,decision);
    if(!decision.publish)return silent(decision.reason);
    const localId = purpose === "coordination" && stage ? `coordination:${stage.stageId}:${target}` : purpose === "stage_result" && stage ? `stage:${stage.stageId}:${stage.state}` : args.messageId === undefined ? `${run.handle?.turnId ?? run.token}:${message}:${target}` : text(args.messageId, "messageId", 128);
    const messageId = createHash("sha256").update(`${role.worldId}:${role.botId}:${decision.key??localId}`).digest("hex");
    const old = this.mailbox.get(role.worldId, messageId);
    if (old && (purpose==="stage_result" || purpose==="coordination" && stage))return {messageId,duplicate:true,public:false};
    if (!old && !["help","danger","answer","stage_result"].includes(purpose) && !run.chatReset && (run.chatDepth ?? 0) >= 4) throw new Error("chat chain budget reached; wait for new player input or verified game progress");
    if (!old && (run.publicCount ?? 0) >= 2) throw new Error("public message budget reached; wait for actual progress");
    if (!old) run.publicCount = (run.publicCount ?? 0) + 1;
    const outgoing: CrewChat = { messageId, worldId: role.worldId, origin: "companion", senderId: role.botId,
      senderName: self.name, recipientIds: recipients.map(b => b.botId), recipientNames: target==="player"?["玩家"]:recipients.map(b => b.name),
      purpose, stageId, message, replyTo, replyName: parent?.senderName, depth: Math.min(4, (run.chatReset ? 0 : Math.max(run.chatDepth ?? 0, parent?.depth ?? 0)) + 1), gameTick: run.lastObserved.gameTick };
    const saved = await this.mailbox.append(outgoing);
    if (!saved.duplicate) {
      if (!run.valid || role.run !== run || run.connection !== this.connection) throw new Error("chat recorded before turn was superseded; no live broadcast");
      // Delivery failures stay in H history. Never replay chat history into the
      // vanilla HUD on reconnect, and never convert a chat failure into action failure.
      await run.connection.request("chat.publish", { ...outgoing, botId: role.botId, intentGeneration: run.intentGeneration }, { intentId: run.intentId, bodyGeneration: run.generation, timeoutMs: 5000 })
        .catch(async error => { await this.journal.append("chat.delivery_failed", role.worldId, { messageId, reason: uiError(error) }); });
      if (outgoing.depth < 4) for (const other of this.roles.values()) {
        if (other.worldId === role.worldId && outgoing.recipientIds.includes(other.botId)) this.trigger(other, "chat_message", { messageId });
      }
    }
    return { messageId, delivered: outgoing.recipientIds, duplicate: saved.duplicate, awaitingWorldEvent: outgoing.depth >= 4 };
  }
  private suspendedFor(worldId: string, botId: string): Set<string> {
    const key = `${worldId}:${botId}`;
    let ids = this.suspendedByBody.get(key);
    if (!ids) { ids = new Set(); this.suspendedByBody.set(key, ids); }
    return ids;
  }
  private async restoreGoals(worldId: string): Promise<void> {
    if (!this.brain) throw new Error("Codex runtime is not started");
    const operation = this.restoreTail.then(() => this.restoreGoalsSerial(worldId));
    this.restoreTail = operation.then(() => undefined, () => undefined);
    await operation;
  }
  private async restoreGoalsSerial(worldId: string): Promise<void> {
    for (const goal of this.ownerGoals.values()) {
      if (goal.worldId !== worldId) continue;
      let role = this.roles.get(goal.role);
      if (role) {
        if (role.worldId !== goal.worldId || role.botId !== goal.botId) {
          this.restoreFailures.set(`${goal.worldId}:${goal.botId}`, `role ${goal.role} is bound to a different world/body`);
          continue;
        }
        role.origin = "owner"; role.intentId = goal.intentId; role.groupIntentId = goal.groupIntentId; role.command = goal.command; role.standby = false; role.waitReason = undefined; role.noWorkCorrections = 0;
        this.restoreFailures.delete(`${goal.worldId}:${goal.botId}`);
        if (role.status === "disconnected") role.status = "idle";
        continue;
      }
      if (this.bindings.has(goal.botId) || this.bindings.has(goal.role)) {
        this.restoreFailures.set(`${goal.worldId}:${goal.botId}`, "companion session is being created");
        continue;
      }
      this.bindings.add(goal.botId); this.bindings.add(goal.role);
      try {
        const thread = await this.brain!.createThread(goal.role,this.connection?.executionBackend);
        if (this.view?.worldId !== worldId || this.connection?.sessionContext?.worldId !== worldId) return;
      role = { id: goal.role, botId: goal.botId, worldId: goal.worldId, thread, status: "idle", origin: "owner", intentGeneration: 0, autonomyEnabled: this.autonomyMode(goal.worldId, goal.botId) !== "off", standby: false, noWorkCorrections: 0, pending: new Map(), dispatching: false, revision: 0, intentId: goal.intentId, groupIntentId: goal.groupIntentId, command: goal.command, suspendedActionIds: new Set(this.suspendedFor(goal.worldId, goal.botId)) };
        if ([...this.roles.values()].some(other => other.thread.threadId === thread.threadId)) throw new Error("independent companion thread ID was reused");
        this.roles.set(goal.role, role);
        this.restoreFailures.delete(`${goal.worldId}:${goal.botId}`);
      } catch (error) {
        this.restoreFailures.set(`${goal.worldId}:${goal.botId}`, error instanceof Error ? error.message : String(error));
      } finally { this.bindings.delete(goal.botId); this.bindings.delete(goal.role); }
    }
  }
  private async bind(body: BodyView, worldId: string): Promise<Role> {
    if (!this.brain) throw new Error("Codex runtime is not started");
    const connection = this.connection;
    if (!connection) throw new Error("game connection is unavailable");
    if (this.bindings.has(body.botId)) throw new Error("companion session is being created");
      const preferred = this.roleAssignments.get(`${worldId}:${body.botId}`);
      const profile = AGENT_PROFILES.find(profile => (!preferred || profile.id === preferred) && !this.roles.has(profile.id) && !this.bindings.has(profile.id));
    if (!profile) throw new Error("three role sessions are already assigned");
    this.bindings.add(body.botId); this.bindings.add(profile.id);
    try {
      const thread = await this.brain.createThread(profile.id,this.connection?.executionBackend);
      if (connection !== this.connection || this.view?.worldId !== worldId) throw new Error("world changed while creating companion session");
      if (this.roles.has(profile.id)) throw new Error("role session was assigned while creating companion session");
      const role: Role = { id: profile.id, botId: body.botId, worldId, thread, status: "idle", entityId: body.entityId, bodyGeneration: body.bodyGeneration, origin: "autonomous", intentGeneration: 0, autonomyEnabled: this.autonomyMode(worldId, body.botId) !== "off", standby: this.autonomyMode(worldId, body.botId) === "standby", noWorkCorrections: 0, pending: new Map(), dispatching: false, revision: 0, suspendedActionIds: new Set(this.suspendedFor(worldId, body.botId)) };
      if ([...this.roles.values()].some(other => other.thread.threadId === thread.threadId)) throw new Error("independent companion thread ID was reused");
      await this.journal.append("role.bound", worldId, { botId: body.botId, role: profile.id, name: body.name });
      if (connection !== this.connection || this.view?.worldId !== worldId) throw new Error("world changed before role binding");
      this.roleAssignments.set(`${worldId}:${body.botId}`, profile.id);
      this.roles.set(profile.id, role); return role;
    } finally { this.bindings.delete(body.botId); this.bindings.delete(profile.id); }
  }
  private ensureAutonomousBodies(observed: WorldView, onlyBotId?: string): Promise<void> {
    const operation = this.autoBootstrapTail.then(() => this.ensureAutonomousBodiesSerial(observed, onlyBotId), () => this.ensureAutonomousBodiesSerial(observed, onlyBotId));
    this.autoBootstrapTail = operation.then(() => undefined, () => undefined);
    return operation;
  }
  private async ensureAutonomousBodiesSerial(observed: WorldView, onlyBotId?: string): Promise<void> {
    if (!this.brain) return;
    this.autonomyBootstrapDepth++;
    try {
    const bodies = observed.companions.filter(body => (!onlyBotId || body.botId === onlyBotId) && !body.stopped && !body.paused && !body.recoveryInvalid);
    for (const body of bodies) {
      try {
        const key = `${observed.worldId}:${body.botId}`;
        if (this.ownerGoals.has(key)) continue;
        let role = [...this.roles.values()].find(candidate => candidate.botId === body.botId && candidate.worldId === observed.worldId);
        if (!role) role = await this.bind(body, observed.worldId);
        role.entityId = body.entityId; role.bodyGeneration = body.bodyGeneration;
        if (typeof body.autonomyEnabled === "boolean" || typeof body.standby === "boolean") this.autonomyModes.set(`${observed.worldId}:${body.botId}`, body.standby === true ? "standby" : body.autonomyEnabled === true ? "on" : "off");
        const mode = this.autonomyMode(observed.worldId, body.botId);
        role.autonomyEnabled = mode === "on"; role.standby = mode === "standby";
        if (mode === "on" && !role.intentId) await this.activateAutonomy(role, observed);
      } catch (error) {
        this.restoreFailures.set(`${observed.worldId}:${body.botId}`, error instanceof Error ? error.message : String(error));
      }
    }
    } finally {
      this.autonomyBootstrapDepth--;
      if (this.autonomyBootstrapDepth === 0) for (const role of this.roles.values()) {
        if (role.worldId === observed.worldId && role.intentId && role.status === "idle" && role.pending.size) this.trigger(role, "autonomy_bootstrap_complete", {});
      }
    }
  }
  private async activateAutonomy(role: Role, observed: WorldView): Promise<void> {
    if (this.autonomyMode(observed.worldId, role.botId) !== "on" || this.ownerGoals.has(`${observed.worldId}:${role.botId}`)) return;
    const existing = this.autonomousGoals.get(`${observed.worldId}:${role.botId}`);
    if (existing?.active) {
      role.origin = "autonomous"; role.intentId = existing.intentId; role.groupIntentId = existing.groupIntentId; role.command = existing.command;
      role.intentGeneration = existing.intentGeneration; role.autonomyEnabled = true; role.standby = false; role.waitReason = undefined;
      if (role.bodyGeneration === undefined) throw new Error("body generation unavailable while restoring autonomous intent");
      const connection = this.connection;
      if (!connection) throw new Error("game connection unavailable while restoring autonomous intent");
      await this.bindIntent(connection, role, observed.companions.find(body => body.botId === role.botId));
      role.status = "idle"; this.trigger(role, "autonomy_reconnected", { goal: existing.command, groupIntentId: existing.groupIntentId });
      return;
    }
    const groupIntentId = existing?.groupIntentId ?? this.autonomyGroups.get(observed.worldId) ?? randomUUID();
    this.autonomyGroups.set(observed.worldId, groupIntentId);
    const intentGeneration = Math.max(role.intentGeneration, existing?.intentGeneration ?? 0) + 1;
    const intentId = randomUUID();
    const command = "独立自主游玩：你首先有自己的目标，先完成当前任务，自行选择并推进采集、制作、建设、探索和补给。玩家和其他伙伴是队友，合作请求在合适时机处理，不以跟随主人、等待下令为默认活动。自救优先，结合自身处境救助队友；明确紧急改派才中断当前普通工作。";
    const goal: AutonomousGoal = { worldId: observed.worldId, botId: role.botId, role: role.id, intentId, groupIntentId, command, intentGeneration, active: true };
    await this.journal.append("autonomy.goal", observed.worldId, goal as unknown as Record<string, unknown>);
    this.autonomousGoals.set(`${observed.worldId}:${role.botId}`, goal);
    role.origin = "autonomous"; role.intentId = intentId; role.groupIntentId = groupIntentId; role.command = command;
    role.intentGeneration = intentGeneration; role.autonomyEnabled = true; role.standby = false; role.waitReason = undefined; role.noWorkCorrections = 0;
    const connection = this.connection;
    if (!connection || role.bodyGeneration === undefined) throw new Error("body generation unavailable while binding autonomous intent");
    await this.bindIntent(connection, role, observed.companions.find(body => body.botId === role.botId));
    role.status = "idle";
    this.trigger(role, "autonomy_ready", { goal: command, groupIntentId });
  }
  private async bindIntent(connection: ModConnection, role: Role, body?: BodyView): Promise<void> {
    if (!role.intentId || role.bodyGeneration === undefined) throw new Error("intent binding requires an active body generation");
    const binding = body?.intentBinding;
    if (binding && typeof binding.intentGeneration === "number" && Number.isSafeInteger(binding.intentGeneration) && binding.intentGeneration >= 0) {
      role.intentGeneration = binding.intentId === role.intentId ? Math.max(role.intentGeneration, binding.intentGeneration) : Math.max(role.intentGeneration, binding.intentGeneration + 1);
    }
    await connection.request("intent.bind", { botId: role.botId, origin: role.origin, intentGeneration: role.intentGeneration }, { intentId: role.intentId, bodyGeneration: role.bodyGeneration, timeoutMs: 5_000 });
    role.boundControlRevision = role.controlRevision;
  }
  private async ensureCurrentBinding(role: Role, run: NonNullable<Role["run"]>): Promise<void> {
    if (role.controlRevision === undefined || role.boundControlRevision === role.controlRevision) return;
    const observed = await run.connection.request<WorldView>("status", {}, { timeoutMs: 5_000 });
    if (!run.valid || role.run !== run || run.connection !== this.connection || observed.worldId !== role.worldId) throw new Error("stale turn while rebinding intent");
    const body = observed.companions.find(candidate => candidate.botId === role.botId);
    if (!body || body.bodyGeneration !== run.generation) throw new Error("body changed while rebinding intent");
    this.view = observed; this.syncRoleAuthoritative(body);
    await this.bindIntent(run.connection, role, body);
  }
  private async suspendPlanning(role: Role, reason: string): Promise<void> {
    this.clearRecovery(role);
    role.revision++;
    if (role.run?.handle && !role.run.interruptRequested) { role.run.interruptRequested = true; void role.run.handle.interrupt().catch(() => {}); }
    if (role.run) role.run.valid = false;
    role.run = undefined; role.pending.clear();
    role.waitReason = reason; role.status = role.activeAction || role.suspendedActionIds.size > 0 ? "working" : "idle";
    if (role.origin !== "autonomous" || role.activeAction || role.assignedTaskId || role.suspendedActionIds.size > 0) return;
    const key = `${role.worldId}:${role.botId}`;
    await this.retireAutonomyGoal(role, reason);
    role.intentId = undefined; role.groupIntentId = undefined; role.command = undefined;
  }
  private async retireAutonomyGoal(role: Role, reason: string): Promise<void> {
    const key = `${role.worldId}:${role.botId}`;
    const previous = this.autonomousGoals.get(key);
    if (previous) { this.autonomousGoals.delete(key); await this.journal.append("autonomy.goal", role.worldId, { ...previous, active: false, retiredReason: reason }); }
  }
  private invalidate(role: Role, status: Role["status"]): void {
    this.clearRecovery(role);
    role.revision++;
    if (role.run) {
      role.run.valid = false;
      if (role.run.handle && !role.run.interruptRequested) {
        role.run.interruptRequested = true;
        void role.run.handle.interrupt().catch(() => {});
      }
    }
    role.status = status;
  }
  private acceptEvidence(role:Role,actionId:string,state:string,generation:number|undefined,source:string,sequence?:unknown):boolean {
    this.noteActionSequence(role,generation,sequence);
    const accepted=this.evidence.apply(role.worldId,role.botId,actionId,state,generation);
    if(!accepted){void this.journal.append("action.late_ignored",role.worldId,{botId:role.botId,actionId,state,generation,source});return false;}
    if(ActionEvidence.terminal.has(state.toUpperCase())) {
      role.suspendedActionIds.delete(actionId);this.suspendedFor(role.worldId,role.botId).delete(actionId);
      if(role.activeAction?.actionId===actionId){role.activeAction=undefined;role.lastTerminalActionId=actionId;}
    } else if((generation===undefined || generation===role.bodyGeneration) && ["ACCEPTED","RUNNING","SUSPENDED","RECONCILE_REQUIRED"].includes(state)) {
      if(!role.activeAction || role.activeAction.actionId===actionId)role.activeAction={actionId,state};
    }
    return true;
  }
  private noteActionSequence(role:Role,generation:number|undefined,sequence:unknown):void {
    if(generation!==role.bodyGeneration || typeof sequence!=="number" || !Number.isSafeInteger(sequence) || sequence<0)return;
    if(role.sequenceGeneration!==generation){role.sequenceGeneration=generation;role.actionSequence=sequence;}
    else role.actionSequence=Math.max(role.actionSequence??0,sequence);
  }
  private syncActiveAction(role: Role, body: BodyView): boolean {
    if(body.bodyGeneration!==role.bodyGeneration)return !!role.activeAction || role.suspendedActionIds.size>0;
    if(role.sequenceGeneration===body.bodyGeneration && typeof body.actionSequence==="number" && body.actionSequence<(role.actionSequence??0))return !!role.activeAction || role.suspendedActionIds.size>0;
    this.noteActionSequence(role,body.bodyGeneration,body.actionSequence);
    let observed = activeAction(body.action);
    if(observed && this.evidence.settled(role.worldId,role.botId,observed.actionId)) observed=null;
    // This complete list describes execution ownership, not success or effects.
    // Older Mods omit it: an absent field must never be mistaken for an empty list.
    if (body.bodyGeneration === role.bodyGeneration && observed !== undefined && Array.isArray(body.suspendedActionIds)
        && body.suspendedActionIds.every(id => typeof id === "string")) {
      role.suspendedActionIds = new Set(body.suspendedActionIds.filter(id=>!this.evidence.settled(role.worldId,role.botId,id)));
      this.suspendedByBody.set(`${role.worldId}:${role.botId}`, new Set(role.suspendedActionIds));
    }
    // Fresh server receipts settle execution ownership, even when a body was
    // recreated and the receipt retains its old generation. Keep uncertain
    // results in the journal and team reconciliation gates; never replay them.
    if (observed !== undefined && Array.isArray(body.actionJournal)) {
      const latest = new Map<string, string>();
      for (const value of body.actionJournal) {
        const receipt = recordValue(value), id = receipt ? terminalActionId(receipt) : undefined;
        if (id && typeof receipt?.state === "string") {
          this.evidence.apply(role.worldId,role.botId,id,receipt.state,terminalBodyGeneration(receipt).value);
          latest.set(id,this.evidence.get(role.worldId,role.botId,id)?.state??receipt.state.toUpperCase());
        }
      }
      for (const [id, state] of latest) {
        if (observed?.actionId === id || !["COMPLETED", "FAILED", "CANCELLED", "REJECTED", "PARTIAL", "STALE", "EXPIRED", "RECONCILE_REQUIRED"].includes(state)) continue;
        role.suspendedActionIds.delete(id);
        this.suspendedFor(role.worldId, role.botId).delete(id);
        if (role.activeAction?.actionId === id) role.activeAction = undefined;
      }
    }
    const trackedSuspended = role.activeAction !== undefined && role.suspendedActionIds.has(role.activeAction.actionId);
    if (observed === undefined) return role.activeAction !== undefined || role.suspendedActionIds.size > 0;
    if (observed === null || observed.actionId === role.lastTerminalActionId) {
      if (trackedSuspended || role.suspendedActionIds.size > 0) return true;
      role.activeAction = undefined;
      return false;
    }
    if (trackedSuspended && observed.actionId !== role.activeAction?.actionId) return true;
    if (role.suspendedActionIds.size > 0 && !role.suspendedActionIds.has(observed.actionId)) {
      role.activeAction = observed;
      return true;
    }
    if (role.suspendedActionIds.has(observed.actionId)) role.suspendedActionIds.delete(observed.actionId);
    role.activeAction = observed;
    return true;
  }
  private syncRoleAuthoritative(body: BodyView): void {
    const role = [...this.roles.values()].find(candidate => candidate.botId === body.botId && candidate.worldId === this.view?.worldId);
    if (!role) return;
    if (typeof body.autonomyEnabled === "boolean" || typeof body.standby === "boolean") {
      role.autonomyEnabled = body.autonomyEnabled === true;
      role.standby = body.standby === true;
      this.autonomyModes.set(`${role.worldId}:${role.botId}`, role.standby ? "standby" : role.autonomyEnabled ? "on" : "off");
    }
    if (typeof body.controlRevision === "number" && Number.isSafeInteger(body.controlRevision) && body.controlRevision >= 0) role.controlRevision = body.controlRevision;
    const binding = body.intentBinding;
    // A stale server binding must never overwrite a newer local owner intent.
    // A generation is authoritative only inside the same intent namespace.
    if (binding && role.intentId && binding.intentId === role.intentId) {
      if (binding.origin === "owner" || binding.origin === "autonomous") role.origin = binding.origin;
      if (typeof binding.intentGeneration === "number" && Number.isSafeInteger(binding.intentGeneration) && binding.intentGeneration >= role.intentGeneration) role.intentGeneration = binding.intentGeneration;
    }
  }
  private inventoryIdentities(world:string,view:WorldView):Record<string,any>[] {
    const known=new Map<string,Record<string,any>>();
    for(const r of this.journal.select(world,r=>r.type==="role.bound",24))if(typeof r.data.botId==="string")known.set(r.data.botId,{botId:r.data.botId,name:r.data.name});
    for(const body of view.companions)known.set(body.botId,body);
    return [...known.values()].slice(0,3);
  }
  private async notifyCooperation(p:Cooperation|undefined,actor:string):Promise<void>{
    if(!p)return;
    const key=`cooperation:${p.worldId}:${p.id}`,signal=p.state;
    if(this.materialSignals.get(key)===signal)return;
    this.materialSignals.set(key,signal);
    await this.journal.append("cooperation.notified",p.worldId,{key,signal,proposalId:p.id});
    const peer=actor===p.requester?p.recipient:p.requester;
    const role=[...this.roles.values()].find(r=>r.worldId===p.worldId&&r.botId===peer);
    if(role){role.pending.set("cooperation_changed",{proposalId:p.id,state:p.state});this.trigger(role,"cooperation_changed",{proposalId:p.id,state:p.state});}
  }
  private async cooperationTerminal(world:string,bot:string,actionId:string,state:string,reason:string):Promise<void>{
    await this.cooperation().terminal(world,bot,actionId,state,reason);
    await this.notifyCooperation(this.cooperation().list(world,bot).find(p=>p.actionId===actionId),bot);
  }
  private async notifyMaterialConditions(world:WorldView):Promise<void>{
    for(const role of this.roles.values()){
      if(role.worldId!==world.worldId||!role.intentId||role.status==="paused"||role.status==="disconnected"||role.standby)continue;
      const actual=world.companions.find(b=>b.botId===role.botId);
      if(!actual||actual.paused||actual.stopped||actual.standby)continue;
      const condition=this.goalWaits.get(`${role.worldId}:${role.botId}`)?.condition;
      if(!condition?.peerId||!condition.resource||!condition.count)continue;
      const key=`${role.worldId}:${role.botId}:${role.intentId}:${condition.peerId}:${condition.resource}:${condition.count}`;
      const signal=materialConditionKey(world,condition.peerId,condition.resource,condition.count),prior=this.materialSignals.get(key);
      this.materialSignals.set(key,signal);
      if(prior===signal)continue;
      if(prior===undefined&&!signal.endsWith(":available")){
        await this.journal.append("resource.condition_baseline",role.worldId,{botId:role.botId,key,signal,gameTick:world.gameTick});continue;
      }
      await this.journal.append("resource.condition_changed",role.worldId,{botId:role.botId,intentId:role.intentId,key,signal,gameTick:world.gameTick});
      role.waitReason=undefined;this.goalWaits.delete(`${role.worldId}:${role.botId}`);this.trigger(role,"resource_condition_changed",{condition,signal});
    }
  }
  private trigger(role: Role, reason: string, data: unknown): void {
    if (!role.intentId || role.status === "paused" || role.status === "disconnected" || role.standby || (role.origin === "autonomous" && !role.autonomyEnabled)) return;
    const chatWake = reason === "chat_message" || reason === "queued_chat" || reason==="cooperation_proposed";
    role.pending.set(reason, data);
    if (role.awaitingTurn || role.recoveryTimer || role.recoveryExhausted) return;
    // A partial or uncertain team result still owns the body.  Keep the
    // reason for status/reconciliation, but do not start another model turn:
    // there is no safe action to replay until the authoritative task state is
    // resolved.  An explicit owner command clears this state first.
    if (!chatWake && role.teamTaskState && TEAM_RECONCILE_STATES.has(role.teamTaskState)) return;
    if (!chatWake && [...role.pending.keys()].some(key => key.startsWith("team_reconcile:"))) return;
    if (this.autonomyBootstrapDepth > 0) return;
    // Chat wakes allow a restricted communication turn. Other reasons plan
    // after the body's real executor reports its current action terminal.  We
    // retain the pending reason so the terminal event can coalesce it into one
    // follow-up turn instead of competing with the accepted action.
    if (!chatWake && (role.activeAction || role.suspendedActionIds.size > 0) && reason !== "action_result" && !(reason === "reobserve_body_generation" && !role.run)) { role.status = "working"; return; }
    if (!role.dispatching) { role.dispatching = true; queueMicrotask(() => void this.think(role)); }
  }
  private async applyGoalState(role: Role, run: NonNullable<Role["run"]>): Promise<void> {
    if (!run.valid || role.run !== run || role.intentId !== run.intentId) return;
    const state = goalStateForSource(run.goalState, run.origin, role.command) as GoalState | undefined;
    if (state !== run.goalState) run.goalState = state;
    if (!state || state === "continue") {
      if (state === "continue") { role.waitReason = run.goalReason; role.wakeConditions = run.goalWakeConditions;this.goalWaits.delete(`${role.worldId}:${role.botId}`);await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:run.intentId,state}); }
      return;
    }
    const reason = run.goalReason ?? (state === "blocked" ? "伙伴报告当前目标暂时无法推进" : state === "standby" ? "伙伴进入待命" : "目标已完成");
    await this.journal.append("goal.state", role.worldId, { botId: role.botId, role: role.id, intentId: run.intentId, origin: run.origin, intentGeneration: run.intentGeneration, state, reason, condition:run.goalCondition, ...(run.goalWakeConditions ? { wakeConditions: run.goalWakeConditions } : {}) });
    if (!run.valid || role.run !== run || role.intentId !== run.intentId) return;
    if (state === "blocked") {
      // A blocked goal is still the live goal.  Keep its intent and durable
      // owner/autonomy record; a later meaningful wake event may unblock it.
      const alternativeKey=`${role.worldId}:${role.botId}:${run.intentId}`;
      if(!this.alternativeUsed.has(alternativeKey)&&!(run.goalCondition?.kind==="materials"&&run.goalCondition.peerId)) {
        this.alternativeUsed.add(alternativeKey);await this.journal.append("goal.alternative",role.worldId,{botId:role.botId,intentId:run.intentId,reason});
        role.pending.set("alternative_work",{reason,instruction:"当前目标受阻。保持当前总目标；重新检查真实背包和地形，评估一次工具准备、替代候选或不同入口；若仍无可做工作，用blocked登记具体等待条件，不反复请求或闲聊。"});
      }
      role.waitReason = reason; role.wakeConditions = run.goalWakeConditions; role.status = "idle";
      this.goalWaits.set(`${role.worldId}:${role.botId}`, { intentId: run.intentId, reason, condition:run.goalCondition, wakeConditions: run.goalWakeConditions });
      await this.notifyMaterialConditions(run.lastObserved);
      return;
    }
    this.goalWaits.delete(`${role.worldId}:${role.botId}`);
    if (state === "standby") {
      const connection = this.connection;
      const body = this.view?.companions.find(candidate => candidate.botId === role.botId);
      if (!connection || !body || body.bodyGeneration !== run.generation) throw new Error("body changed before entering standby");
      await connection.request("control", { operation: "standby", botId: role.botId, intentGeneration: run.intentGeneration }, { intentId: run.intentId, bodyGeneration: run.generation, timeoutMs: 5_000 });
      const refreshed = await connection.request<WorldView>("status", {}, { timeoutMs: 5_000 });
      if (connection !== this.connection || refreshed.worldId !== role.worldId) throw new Error("connection or world changed before standby commit");
      this.view = refreshed; for (const candidate of refreshed.companions) this.syncRoleAuthoritative(candidate);
      this.autonomyModes.set(`${role.worldId}:${role.botId}`, "standby");
      await this.journal.append("autonomy.mode", role.worldId, { mode: "standby", botId: role.botId, changedAtGameTick: refreshed.gameTick });
    }
    if (!run.valid || role.run !== run || role.intentId !== run.intentId) return;
    if (run.origin === "owner") {
      const key = `${role.worldId}:${role.botId}`;
      const owner = this.ownerGoals.get(key);
      if (owner?.intentId === run.intentId) {
        this.ownerGoals.delete(key);
        await this.journal.append("owner.goal_state", role.worldId, { botId: role.botId, role: role.id, intentId: run.intentId, state, reason });
      }
    } else {
      const previous = this.autonomousGoals.get(`${role.worldId}:${role.botId}`);
      if (previous?.intentId === run.intentId) {
        this.autonomousGoals.delete(`${role.worldId}:${role.botId}`);
        await this.journal.append("autonomy.goal", role.worldId, { ...previous, active: false, retiredReason: reason });
      }
    }
    if (!run.valid || role.run !== run || role.intentId !== run.intentId) return;
    role.revision++; run.valid = false;
    role.intentId = undefined; role.groupIntentId = undefined; role.command = undefined; role.run = undefined; role.pending.clear();
    role.waitReason = reason; role.wakeConditions = run.goalWakeConditions; role.standby = state === "standby"; role.autonomyEnabled = this.autonomyMode(role.worldId, role.botId) === "on"; role.status = "idle";
    if (state === "complete" && run.origin === "autonomous" && this.autonomyMode(role.worldId, role.botId) === "on") {
      const latest = this.view;
      if (latest?.worldId === role.worldId) await this.activateAutonomy(role, latest);
    } else if (state === "complete" && run.origin === "owner" && this.autonomyMode(role.worldId, role.botId) === "on") {
      const latest = this.view;
      if (latest?.worldId === role.worldId) await this.activateAutonomy(role, latest);
    }
  }
  private async think(role: Role): Promise<void> {
    let timer: NodeJS.Timeout | undefined;
    const revision = role.revision;
    let run: NonNullable<Role["run"]> | undefined;
    let turnFailed = false;
    let outcome = "completed";
    try {
      const brain = this.brain;
      if (!role.intentId || !brain || !role.pending.size || role.status === "paused" || role.status === "disconnected") return;
      const connection = this.ready();
      const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
      if (revision !== role.revision || connection !== this.connection || observed.worldId !== role.worldId) return;
      const body = observed.companions.find(body => body.botId === role.botId);
      if (!body) { if (revision === role.revision) role.status = "awaiting_body"; return; }
      this.view = observed;
      this.syncRoleAuthoritative(body);
      if((observed.modVersion!==undefined||observed.stage!==undefined) && (observed.executionProtocol??0)<5){role.status="failed";role.waitReason=`版本不兼容：游戏 ${observed.modVersion} 缺少完整执行恢复协议；安装0.2.14后重启游戏（需要持续准备和历史结果分流协议）`;return;}
      if (revision === role.revision) { role.entityId = body.entityId; role.bodyGeneration = body.bodyGeneration; }
      if (body.stopped || body.standby) await this.cancelDeferred(role.worldId, role.botId, "explicit stop or standby");
      if (body.stopped || body.paused || body.recoveryInvalid) { if (revision === role.revision) role.status = "paused"; return; }
      const deferredKey = `${role.worldId}:${role.botId}`;
      const deferred = this.deferredRequests.get(deferredKey)?.[0];
      if (deferred && this.stages.current(role.worldId,role.botId,role.intentId)?.state !== "continue" && !role.run && !activeAction(body.action) && role.suspendedActionIds.size === 0 && !role.assignedTaskId && !body.standby) {
        await this.serializeMutation(async () => {
          if (revision !== role.revision || connection !== this.connection || this.deferredRequests.get(deferredKey)?.[0]?.id !== deferred.id) return;
          // Durable claim prevents replay after an uncertain restart. A claimed request without a command remains reviewable in the journal.
          await this.journal.append("owner.request.claimed", role.worldId, { id: deferred.id, botId: role.botId });
          this.deferredRequests.get(deferredKey)?.shift();
          try { await this.commandSerial(deferred.message, role.botId, deferred.id, role.worldId, [body], true); }
          catch (error) { await this.journal.append("owner.request.closed", role.worldId, { id: deferred.id, botId: role.botId, reason: uiError(error), state: "RECONCILE_REQUIRED" }); }
        });
        return;
      }
      if (role.standby || (role.origin === "autonomous" && !role.autonomyEnabled)) {
        if (revision === role.revision) { role.pending.clear(); role.status = role.activeAction ? "working" : "idle"; }
        return;
      }
      if (role.intentId && role.controlRevision !== role.boundControlRevision) await this.bindIntent(connection, role, body);
      if (revision !== role.revision || connection !== this.connection || this.view?.worldId !== observed.worldId) return;
      const accepted=this.cooperation().list(role.worldId,role.botId).find(p=>p.recipient===role.botId&&p.state==="accepted");
      if(accepted && !role.assignedTaskId && !this.syncActiveAction(role,body)){
        const actionId=`${role.intentId}:coop-${accepted.id}`;
        await this.cooperation().save({...accepted,state:"submitted",actionId});
        this.evidence.apply(role.worldId,role.botId,actionId,"PREPARED",body.bodyGeneration);
        try{const receipt=await connection.request<any>("action.submit",{botId:role.botId,kind:accepted.action.kind,...accepted.action.parameters,intentGeneration:role.intentGeneration},{actionId,intentId:role.intentId,bodyGeneration:body.bodyGeneration,timeoutMs:10000});
          this.acceptEvidence(role,actionId,String(receipt.state??"RECONCILE_REQUIRED"),body.bodyGeneration,"cooperation_response",recordValue(receipt)?.sequence);
          await this.cooperationTerminal(role.worldId,role.botId,actionId,String(receipt.state??"RECONCILE_REQUIRED"),String(receipt.message??""));
        }catch(error){this.acceptEvidence(role,actionId,"RECONCILE_REQUIRED",body.bodyGeneration,"cooperation_timeout");await this.cooperation().save({...accepted,state:"reconcile",actionId,reason:"submission uncertain; inspect actual body, never replay"});}
        role.pending.delete("cooperation_ready");if(role.activeAction){role.status="working";return;}
      }
      const inbox = this.mailbox.unread(role.worldId, role.botId);
      const resultEntries = [...this.resultQueue(role.worldId, role.botId)].slice(0, 32);
      const onlyChat = resultEntries.length === 0 && [...role.pending.keys()].every(key => key === "chat_message" || key === "queued_chat");
      const chatFactIds = [
        ...this.stages.history(role.worldId,role.botId).filter(s=>s.state==="complete").map(s=>`stage:${s.intentId}:${s.stageId}`),
        ...inbox.filter(m => m.origin === "player").map(m => `player:${m.messageId}`)
      ];
      const freshWorldEvent = consumeNovelFacts(this.chatFacts(role.worldId, role.botId), chatFactIds);
      const chatReset = freshWorldEvent;
      if (chatReset) role.chatDepth = 0;
      const canChat = role.pending.has("cooperation_proposed") || inbox.some(m => m.origin === "player" || m.depth < 4 || freshWorldEvent);
      const working = this.syncActiveAction(role, body);
      const communicationOnly = canChat && (working || onlyChat || !!role.teamTaskState && TEAM_RECONCILE_STATES.has(role.teamTaskState));
      if (working && !communicationOnly) { if (revision === role.revision) role.status = "working"; return; }
      if (onlyChat && !canChat) { role.pending.delete("chat_message"); role.pending.delete("queued_chat"); return; }
      const reasons = [...role.pending,["cooperation",this.cooperation().list(role.worldId,role.botId).filter(p=>["proposed","accepted","submitted","blocked","reconcile"].includes(p.state))]];
      if (communicationOnly) { role.pending.delete("chat_message"); role.pending.delete("queued_chat");role.pending.delete("cooperation_proposed"); }
      else role.pending.clear();
      run = { startedAt: Date.now(), resultIds: communicationOnly ? [] : resultEntries.map(([id]) => id), communicationOnly, chatReset, inbox, publicCount: 0, chatDepth: chatReset ? 0 : Math.max(role.chatDepth ?? 0, ...inbox.map(m => m.depth)), token: randomUUID(), connection, generation: body.bodyGeneration, intentId: role.intentId, origin: role.origin, intentGeneration: role.intentGeneration, observations: 0, submitted: false, yieldForBodyResult: false, interruptRequested: false, valid: true, revision, lastObserved: observed };
      if (role.pending.has("model_recovery") || (role.recoveryAttempts ?? 0) > 0) role.waitReason = undefined;
      const currentRun = run;
      role.run = currentRun; role.chatDepth = run.chatDepth; role.status = "thinking";
      const memories = this.journal.role(role.worldId,role.botId,row => row.type === "role.memory" && row.data.role === role.id,24).map(row => row.data.entry);
      const context = planningContext({ identity: { name: body.name, botId: role.botId, role: role.id },
        teamResources:resourceSummary({...observed,companions:this.inventoryIdentities(role.worldId,observed)},role.botId,relevantItems(this.stages.current(role.worldId,role.botId,run.intentId)?.completion,this.recipeFacts.get(`${role.worldId}:${role.botId}:${run.intentId}`),this.cooperation().list(role.worldId,role.botId),role.botId,recordValue(observed.team)?.inventoryReservations),this.cooperation().list(role.worldId)),
        teammates: observed.companions.map(b => ({ name: b.name, botId: b.botId, role: [...this.roles.values()].find(r => r.botId === b.botId)?.id })),
        independentPlay: "先完成自己的当前工作；普通玩家请求在安全工作间隙接入，明确紧急改派除外。队友建议先协商。即时自卫由本地执行器处理，不要提交动作覆盖它。",
        inbox, communicationOnly, actionResults: resultEntries.map(([eventId, result]) => ({ eventId, ...result })), capabilities: this.executionCapabilities(),
        communicationRules: REPORT_RULES,
        taskStage: this.stages.current(role.worldId,role.botId,run.intentId), stageHistory: this.stages.history(role.worldId,role.botId),
        recoveryAssessment: this.recoveryEvaluations.get(`${role.worldId}:${role.botId}`)===body.bodyGeneration ? {required:true,instruction:"先核对新身体实际背包、装备、生命、食物、空气、位置。结合上次目标、阶段回执与未确认项，决定续做、补给、寻回物资或换任务；不能沿用死亡前库存或重放旧动作。",body} : undefined,
        stageRules: connection.executionBackend ? "保持总目标与阶段。阶段只登记可核验的范围、目的和完成条件，常规准备不另建总目标。具体可用动作严格以capabilities.actions为准，不逐块汇报或等待队友确认。" : "阶段可带48字以内title概括任务面板标题；不是公开发言，不需要额外模型回合。使用propose_work(operation=stage,stageId,scope,summary,completion,state=continue)登记一段完整工作，连续多个act同一阶段。阶段结束更新看板；仅有协作价值才share，遵循communicationRules。不要逐块汇报或等待队长确认；普通进度purpose=progress只记诊断。危险、求助、重要发现和回答玩家可以及时简短交流。材料、工具准备属于原阶段，可用SEQUENCE持续执行已确定的最多8步。按物品数量采集用COLLECT_RESOURCE，按方块数量用MINE；均由本地连续执行。EXCAVATE只负责安全开路。动作参数以capabilities.actions为准。COLLECT_RESOURCE可省略candidates，preparation默认允许在32格工作范围内按真实配方准备工具与材料，最多6层/16步/64块准备采掘，共享accessBudget；遇到缺镐优先使用此授权，不逐步问队友。",
        mission: role.command, intentId: run.intentId, intentOrigin: run.origin, intentGeneration: run.intentGeneration, autonomyEnabled: role.autonomyEnabled, standby: role.standby, wakeConditions: role.wakeConditions, botId: role.botId, world: this.planningWorld(observed, role.botId), reasons, memories,
        preparationRules: "把补工具和材料视为当前采集阶段的前置工作，不改变玩家总目标。先核对完整背包、目标工具资格、真实配方、材料缺口与工位；自己能安全采集/制作时自行完成准备，再继续原目标。没有石镐不等于任务只能等队友送镐；同时不要在没有木镐时徒手挖石头假称获得圆石。确实缺资源、能力或队友交接明显更合适时再求助。无需为准备步骤反复新建阶段。",
        throughputRules: connection.executionBackend ? "数量目标优先使用实际已接通的持续资源动作，在原授权内准备工具、换候选和回收；只有整批完成或需要新决策时再返回模型。未接通的旧能力不可假定可用。" : "对20个铁或一组煤等数量目标，优先按实际所需物品提交COLLECT_RESOURCE(count<=64)，由执行器处理工具准备和多个候选；只有指定方块任务才提交MINE，由本地连续挖掘拾取；不要无理由每块提交一次。MINE会自动从整个背包选适用工具，不必每次先SELECT。达到整批结果、真实受阻或危险才重新规划；矿脉不足时另找下一矿脉，不能把请求数当实际产量。保持原版挖掘时间。",
        recoveryReview: {records:body.harvestEvidence??[], instruction:"历史回收记录不是当前动作锁。visible/pickup_delayed 表示实际可见，确认可达后可PICKUP；removed表示已观察到实体移除，不能再捡；not_observable只表示缺少当前证据，不能假称捡到或消失。受阻阶段用propose_work(operation=stage,stageId,state=blocked,reason)登记后，可以建立替代阶段继续其他工作，不需要清空历史记录。不要反复WAIT等待不可见的旧掉落；未确认效果和原始失败仍保留。"},
        rules: "通过observe获取附近资料；每回合至多提交一个act，接受后等真实结果再计划。所有动作、树种及参数以本轮capabilities.actions为准，不使用旧版单树/橡木白桦限制。扫描refreshing时可使用上一份已完成线索并核对当前目标，scanning不等于没有资源。阶段目标由你决策；不轮询、不生成物品、不修改未知建筑，失败必须说明缺少条件并评估其他工作。" });
      for(const archive of context.archives)if(!this.journal.context(role.worldId,role.botId,archive.reference))
        await this.journal.append("context.archive",role.worldId,{botId:role.botId,...archive});
      const prompt=context.prompt;
      run.promptBytes = Buffer.byteLength(prompt, "utf8");
      await this.journal.append("turn.started", role.worldId, { promptBytes: run.promptBytes, botId: role.botId, threadId: role.thread.threadId, bodyGeneration: run.generation, mode: communicationOnly ? "communication" : "work", chatFacts: chatFactIds, resultIds: run.resultIds, recoveryAttempts: role.recoveryAttempts ?? 0 });
      const rawHandle = await brain.startTurn(role.thread, prompt, role.thread.profile, body.bodyGeneration);
      const handle:TurnHandle={...rawHandle,interrupt:async()=>{const at=Date.now();const request=rawHandle.interrupt();void this.journal.append("turn.interrupt_requested",role.worldId,{botId:role.botId,threadId:rawHandle.threadId,turnId:rawHandle.turnId}).catch(()=>{});try{return await request;}finally{void this.journal.append("turn.interrupt_settled",role.worldId,{botId:role.botId,threadId:rawHandle.threadId,turnId:rawHandle.turnId,durationMs:Date.now()-at}).catch(()=>{});}}};currentRun.handle=handle;
      void handle.completion.catch(() => {});
      await this.journal.append("turn.attached", role.worldId, { botId: role.botId, threadId: handle.threadId, turnId: handle.turnId, bodyGeneration: run.generation, startupMs:Date.now()-(run.startedAt??Date.now()) });
      const timedOut = new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          currentRun.valid = false;
          // Settle the deadline first: an immediate interrupt acknowledgement
          // must not win the race and masquerade as user cancellation.
          reject(new Error("model turn timed out; healthy body action continues"));
          if (!currentRun.interruptRequested) {
            currentRun.interruptRequested = true;
            void handle.interrupt().catch(() => {});
          }
        }, Math.max(1,this.turnTimeoutMs-(Date.now()-(currentRun.startedAt??Date.now()))));
      });
      // An interrupt may itself await the app server; keep the deadline observed
      // immediately, then race that request as well as turn completion.
      void timedOut.catch(() => {});
      if (revision !== role.revision || role.run !== currentRun || !currentRun.valid || connection !== this.connection || currentRun.yieldForBodyResult) {
        currentRun.valid = false;
        if (!currentRun.interruptRequested) {
          currentRun.interruptRequested = true;
          await Promise.race([handle.interrupt().catch(() => {}), timedOut]);
        }
        await Promise.race([handle.completion, timedOut]);
        outcome = currentRun.yieldForBodyResult ? "body_result" : "cancelled";
        return;
      }
      const completion = await Promise.race([handle.completion, timedOut]);
      if (currentRun.yieldForBodyResult || revision !== role.revision || !currentRun.valid) {
        outcome = currentRun.yieldForBodyResult ? "body_result" : "cancelled";
        return;
      }
      if (recordValue(completion)?.status !== "completed") throw new Error("model turn did not complete; unread messages retained");
      // Public speech is explicit share only; normal final text stays inside the private model session.
      if (revision === role.revision && role.run === run && run.valid) {
        await this.consumeResults(role, run);
        if(!run.communicationOnly)this.recoveryEvaluations.delete(`${role.worldId}:${role.botId}`);
        await this.mailbox.markRead(role.worldId, role.botId, inbox.map(m => m.messageId));
        if (run.ownerChat && run.valid && role.run === run && role.revision === revision) {
          const message = this.mailbox.get(role.worldId, run.ownerChat)!;
          if (await this.mailbox.claimOwner(role.worldId, message.messageId, role.botId)) {
            try {
              const dispatchResult = await this.serializeMutation(() => {
                if (!run?.valid || role.run !== run || role.revision !== revision) throw new Error("owner chat turn was superseded");
                return this.commandSerial(message.message, message.recipientIds.length > 1 ? undefined : role.botId, message.messageId, role.worldId,
                  run.lastObserved.companions.filter(b => message.recipientIds.includes(b.botId)));
              });
              await this.journal.append("chat.owner_result", role.worldId, { messageId: message.messageId, state: recordValue(dispatchResult)?.queued === true ? "QUEUED" : "DISPATCHED" });
            } catch (error) {
              await this.journal.append("chat.owner_result", role.worldId, { messageId: message.messageId, state: "RECONCILE_REQUIRED", reason: uiError(error) });
            }
          }
        }
      }
      if (communicationOnly && revision === role.revision && role.run === run && !working && !role.standby
          && (!role.waitReason || /聊天|chat|交流/.test(role.waitReason)) && !(role.teamTaskState && TEAM_RECONCILE_STATES.has(role.teamTaskState))) {
        role.pending.set("resume_work", { message: "交流回合结束，继续原目标；不要重复闲聊，选择动作或说明等待条件。" });
      }
      if (!communicationOnly && revision === role.revision && role.run === run) {
        await this.applyGoalState(role, run);
        if (role.run === run && !run.goalState && run.submitted) { role.noWorkCorrections = 0; role.rejectionRecoveryUsed=false; role.preparationGrace=false; role.waitReason = undefined; }
        else if (role.run === run && (!run.goalState || run.goalState === "continue") && !run.submitted) {
          if(run.toolRejections?.length){
            const reason=run.toolRejections.at(-1)!;
            if(!role.rejectionRecoveryUsed){role.rejectionRecoveryUsed=true;role.waitReason=`接口或执行条件被拒绝，重新评估：${reason}`;role.pending.set("goal_correction",{message:"上一回合有具体工具拒绝，不是无计划。根据返回字段修正，或使用自动准备；仍受阻登记具体条件。",reason});}
            else {role.waitReason=`等待恢复条件：${reason}`;role.status="idle";this.goalWaits.set(`${role.worldId}:${role.botId}`,{intentId:run.intentId,reason:role.waitReason,condition:parseWait({kind:/path|station|stance|BLOCKED|STALLED/i.test(reason)?"path":"model_recovery"},reason,run.lastObserved.gameTick,1)});role.pending.delete("goal_correction");role.pending.delete("resume_work");await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:run.intentId,state:"blocked",reason:role.waitReason,condition:this.goalWaits.get(`${role.worldId}:${role.botId}`)?.condition});await this.journal.append("wait.tool_rejection",role.worldId,{botId:role.botId,reason});}
          } else if(run.preparationRegistered&&!role.preparationGrace){
            role.preparationGrace=true;role.waitReason="阶段已登记，继续提交准备或资源动作";
            role.pending.set("resume_work",{message:"已保存本任务阶段。直接使用 COLLECT_RESOURCE 的准备授权或其他实际动作，不必再次登记阶段。"});
          } else if (role.noWorkCorrections < 2) {
            role.noWorkCorrections++;
            role.waitReason = "本轮没有提交动作；下一次回合要求模型说明原因或提交一个动作";
            role.pending.set("goal_correction", { message: role.noWorkCorrections===1 ? "上一回合没有动作。使用现有真实观察提交可执行动作，或登记具体受阻条件。" : "纠正回合仍无动作。评估不同出口、准备工具或其他可执行工作；仍受阻必须说明资源、位置及唤醒条件。" });
          } else {
            role.waitReason="模型未形成可执行计划：纠正与替代评估均没有动作或具体等待条件";
            role.status="idle";
            const condition=parseWait({kind:"model_recovery"},role.waitReason,run.lastObserved.gameTick,2);
            this.goalWaits.set(`${role.worldId}:${role.botId}`,{intentId:run.intentId,reason:role.waitReason,condition});
            role.pending.delete("goal_correction");role.pending.delete("resume_work");
            await this.journal.append("goal.state",role.worldId,{botId:role.botId,intentId:run.intentId,state:"blocked",reason:role.waitReason,condition});
            await this.journal.append("role.no_plan",role.worldId,{botId:role.botId,threadId:role.thread.threadId,corrections:role.noWorkCorrections});
          }
        }
      }
      if (revision === role.revision && role.run === run) { run.valid = false; role.run = undefined; if (role.status === "thinking") role.status = role.activeAction ? "working" : "idle"; }
    } catch (error) {
      turnFailed = true;
      outcome = run?.yieldForBodyResult ? "body_result_waiting_end" : revision !== role.revision ? "cancelled" : "failed";
      if (revision === role.revision) {
        if (role.run === run && run) { run.valid = false; role.run = undefined; }
        role.status = role.activeAction ? "working" : "recovering";
        role.waitReason = run?.yieldForBodyResult ? "动作已结束，等待旧模型回合退出后处理结果" : "模型回合失败，等待旧回合退出后恢复；身体动作独立继续";
        if (run?.handle) this.recoverAfterEnd(role, run, revision, !!run.yieldForBodyResult);
        else role.status = role.activeAction ? "working" : "failed";
        if (!run?.yieldForBodyResult) await this.journal.append("role.failure", role.worldId, { botId: role.botId, threadId: role.thread.threadId, turnId: run?.handle?.turnId, role: role.id, reason: error instanceof Error ? error.message : String(error) }).catch(() => {});
      }
    } finally {
      if (timer) clearTimeout(timer);
      if (run && role.run === run && revision === role.revision) {
        run.valid = false; role.run = undefined;
        if (outcome === "body_result" || outcome === "cancelled") role.status = role.activeAction ? "working" : "idle";
      }
      if (run) await this.journal.append("turn.ended", role.worldId, { botId: role.botId, threadId: role.thread.threadId, turnId: run.handle?.turnId, actionId: run.submittedActionId, outcome, durationMs:Date.now()-(run.startedAt??Date.now()), recoveryAttempts: role.recoveryAttempts ?? 0 }).catch(() => {});
      role.dispatching = false;
      const waitingForTeamReconcile = role.pending.size > 0 && [...role.pending.keys()].every(reason => reason.startsWith("team_reconcile:") || reason.startsWith("team_wait:"));
      const pendingChat = this.mailbox.unread(role.worldId, role.botId).some(m => m.origin === "player" || m.depth < 4);
      if ((role.resumeAfterEnd || role.pending.has("model_recovery")) && !role.awaitingTurn && !role.recoveryTimer && !role.recoveryExhausted) {
        role.resumeAfterEnd = false;
        this.trigger(role, "action_result", { retained: true });
      }
      else if (!turnFailed && pendingChat && !["failed", "paused", "awaiting_body", "disconnected"].includes(role.status)) this.trigger(role, "queued_chat", {});
      else if (!turnFailed && role.pending.size && !waitingForTeamReconcile && !["failed", "paused", "awaiting_body", "disconnected"].includes(role.status)) {
        this.trigger(role, role.pending.has("reobserve_body_generation") ? "reobserve_body_generation" : "queued_event", {});
      }
    }
  }
  private async gameEvent(connection: ModConnection, event: EventEnvelope): Promise<boolean> {
    if (connection !== this.connection) return false;
    if (connection.sessionContext?.worldId !== event.worldId || connection.sessionContext?.sessionEpoch !== event.sessionEpoch) return false;
    const key = `${event.worldId}:${event.eventId}`;
    if (this.seenEvents.has(key) && !(event.event === "body.available" && !this.handledBodyAvailable.has(key)) && event.event !== "player.chat") return true;
    const inFlight = this.eventWrites.get(key);
    if (inFlight) return inFlight;
    const write = this.persistGameEvent(connection, event, key);
    this.eventWrites.set(key, write);
    try { return await write; } finally { this.eventWrites.delete(key); }
  }
  private async persistGameEvent(connection: ModConnection, event: EventEnvelope, key: string): Promise<boolean> {
    if (this.seenEvents.has(key) && !(event.event === "body.available" && !this.handledBodyAvailable.has(key)) && event.event !== "player.chat") return true;
    const details = event.event === "ui.request" ? (recordValue(event.body) ?? {}) : object(event.body);
    if (!this.seenEvents.has(key)) {
      await this.journal.append("game.event", event.worldId, { eventId: event.eventId, event: event.event, body: details });
      this.seenEvents.add(key);
    }
    if (connection !== this.connection) {
      return event.event !== "body.available";
    }
    if (event.event === "diagnostic.snapshot") {
      this.lastGameHealth={atUtc:new Date().toISOString(),gameTick:details.gameTick,bodies:details.companions};
      // Reconcile only explicit complete execution evidence for the current body.
      // This event does not poll the model, manufacture progress, or consume results.
      for (const raw of Array.isArray(details.companions) ? details.companions : []) {
        const body = raw as BodyView;
        const role = [...this.roles.values()].find(r=>r.worldId===event.worldId && r.botId===body.botId);
        if (!role || role.run || role.bodyGeneration!==body.bodyGeneration || !Array.isArray(body.suspendedActionIds)) continue;
        const hadOwnership = !!role.activeAction || role.suspendedActionIds.size>0;
        const working = this.syncActiveAction(role,body);
        if (!working && (hadOwnership || this.resultQueue(role.worldId,role.botId).size || role.pending.size && !role.waitReason)) {
          await this.journal.append("execution.snapshot_reconciled",event.worldId,{botId:role.botId,bodyGeneration:body.bodyGeneration});
          if (!body.paused && !body.stopped && !body.recoveryInvalid && !body.standby && (role.pending.size || this.resultQueue(role.worldId,role.botId).size)) this.trigger(role,"action_result",{message:"执行权已释放；先核对真实结果，不重放旧动作"});
        }
      }
      return true;
    }
    if (event.event === "body.available") {
      const handled = await this.handleBodyAvailable(connection, event.worldId, availableBody(details));
      if (handled) this.handledBodyAvailable.add(key);
      return handled;
    }
    if (event.event === "player.chat") {
      const recipients = Array.isArray(details.recipientIds) ? details.recipientIds.filter((id): id is string => typeof id === "string") : [];
      const observed = await connection.request<WorldView>("status", {}, { timeoutMs: 5000 });
      if (connection !== this.connection || observed.worldId !== event.worldId) return false;
      this.view = observed;
      if (this.brain) await this.ensureAutonomousBodies(observed);
      const recipientNames = recipients.map(id => observed.companions.find(b => b.botId === id)?.name ?? id);
      const message: CrewChat = { messageId: text(details.messageId, "messageId", 128), worldId: event.worldId,
        origin: "player", senderId: text(details.ownerId, "ownerId", 128), senderName: text(details.ownerName, "ownerName", 128),
        recipientIds: recipients, recipientNames, message: text(details.message, "message", 512), depth: 0, gameTick: observed.gameTick };
      await this.mailbox.append(message);
      for (const role of this.roles.values()) if (role.worldId === event.worldId && recipients.includes(role.botId)) this.trigger(role, "chat_message", { messageId: message.messageId });
      return true;
    }
    if (event.event === "ui.request") return this.handleUiRequest(connection, event, details);
    if (event.event === "body.wakeup" || event.event === "mode_changed") {
      const botId = typeof details.botId === "string" ? details.botId : undefined;
      const reasons = Array.isArray(details.reasons) ? details.reasons.filter((reason): reason is string => typeof reason === "string").slice(0, 8) : [];
      const bodyGeneration = details.bodyGeneration;
      const modeChanged = event.event === "mode_changed" || reasons.includes("mode_changed");
      const refreshed = await connection.request<WorldView>("status", {}, { timeoutMs: 5_000 });
      if (connection !== this.connection || refreshed.worldId !== event.worldId) return false;
      this.view = refreshed;
      await this.notifyMaterialConditions(refreshed);
      for (const body of refreshed.companions) {
        this.syncRoleAuthoritative(body);
        if (body.stopped || body.standby) await this.cancelDeferred(event.worldId, body.botId, "local stop or standby");
      }
      this.syncTeamAssignments(refreshed);
      if (modeChanged) {
        for (const role of this.roles.values()) {
          if (role.worldId !== event.worldId || (botId && role.botId !== botId)) continue;
          if (role.standby) {
            await this.suspendPlanning(role, "已进入待命");
            const key = `${role.worldId}:${role.botId}`, goal = this.ownerGoals.get(key);
            if (goal) { await this.journal.append("owner.goal_state", role.worldId, { botId: role.botId, intentId: goal.intentId, state: "standby", reason: "玩家本地待命" }); this.ownerGoals.delete(key); role.intentId = undefined; }
          }
        }
        if (this.brain) await this.ensureAutonomousBodies(refreshed, botId);
        await this.wakeReconciledRoles(connection, refreshed, "mode_changed", botId);
        return true;
      }
      for (const role of this.roles.values()) {
        if (role.worldId !== event.worldId || (botId && role.botId !== botId)) continue;
        if (typeof bodyGeneration === "number" && bodyGeneration !== role.bodyGeneration) { role.waitReason = "身体代次变化，等待重新核对"; continue; }
        if (role.waitReason && reasons.every(reason => reason === "body_idle")) continue;
        const condition=this.goalWaits.get(`${role.worldId}:${role.botId}`)?.condition;
        if(condition?.peerId&&condition.resource&&condition.count&&reasons.every(r=>["inventory_changed","environment_changed","body_idle"].includes(r)))continue;
        if(role.waitReason&&!waitingEventRelevant(condition,reasons)&&!this.resultQueue(role.worldId,role.botId).size)continue;
        if(role.waitReason&&waitingEventRelevant(condition,reasons))await this.journal.append("wait.released",role.worldId,{botId:role.botId,condition,reasons,gameTick:refreshed.gameTick});
        if(reasons.some(r=>["inventory_changed","environment_changed"].includes(r)) && (role.waitReason || this.alternativeUsed.has(`${role.worldId}:${role.botId}:${role.intentId}`))) {
          this.alternativeUsed.delete(`${role.worldId}:${role.botId}:${role.intentId}`);
          await this.journal.append("goal.alternative_reset",role.worldId,{botId:role.botId,intentId:role.intentId});role.waitReason=undefined;role.noWorkCorrections=0;role.rejectionRecoveryUsed=false;role.preparationGrace=false;
          this.goalWaits.delete(`${role.worldId}:${role.botId}`);
        }
        this.trigger(role, "body_wakeup", { reasons, gameTick: details.gameTick, bodyGeneration });
      }
      return true;
    }
    if (["action.progress","action.terminal"].includes(event.event)) {
      for(const role of this.roles.values())if(role.worldId===event.worldId&&role.botId===details.botId)this.noteActionSequence(role,terminalBodyGeneration(details).value,details.sequence);
      const id=terminalActionId(details);
      if(id && typeof details.botId==="string" && typeof details.state==="string" &&
        !this.evidence.apply(event.worldId,details.botId,id,details.state,terminalBodyGeneration(details).value)) {
        await this.journal.append("action.late_ignored",event.worldId,{botId:details.botId,actionId:id,state:details.state,source:event.event});
        for(const role of this.roles.values())if(role.worldId===event.worldId&&role.botId===details.botId&&role.activeAction?.actionId===id&&terminalBodyGeneration(details).present&&terminalBodyGeneration(details).value!==role.bodyGeneration)this.trigger(role,"reobserve_body_generation",{actionId:id,details});
        return true;
      }
    }
    if (event.event === "action.progress") {
      const actionId = terminalActionId(details);
      if (!actionId || typeof details.botId !== "string") return true;
      const state = typeof details.state === "string" ? details.state.toUpperCase() : "";
      const ids = this.suspendedFor(event.worldId, details.botId);
      if (state === "SUSPENDED") ids.add(actionId);
      else if (["RUNNING", "STARTED", "RESUMED"].includes(state)) ids.delete(actionId);
      for (const role of this.roles.values()) {
        if (role.worldId !== event.worldId || role.botId !== details.botId) continue;
        if (state === "SUSPENDED") {
          role.suspendedActionIds.add(actionId);
          if (role.activeAction?.actionId === actionId) role.activeAction.state = "SUSPENDED";
        } else if (["RUNNING", "STARTED", "RESUMED"].includes(state)) {
          role.suspendedActionIds.delete(actionId);
          if (role.activeAction?.actionId === actionId) role.activeAction.state = state;
        }
      }
      return true;
    }
    if (event.event === "team.changed") {
      await this.reconcileTeamChanged(connection, event.worldId, details);
      return true;
    }
    if (event.event === "action.terminal") {
      const cooperationAction=terminalActionId(details);
      if(cooperationAction&&typeof details.botId==="string")await this.cooperationTerminal(event.worldId,details.botId,cooperationAction,String(details.state),String(details.message??""));
      if (details.historical === true) {
        const id = terminalActionId(details);
        if (id && typeof details.botId === "string") {
          this.suspendedFor(event.worldId, details.botId).delete(id);
          for (const role of this.roles.values()) {
            if (role.worldId !== event.worldId || role.botId !== details.botId) continue;
            const released = role.suspendedActionIds.delete(id) || role.activeAction?.actionId===id;
            if(role.activeAction?.actionId===id){role.activeAction=undefined;role.lastTerminalActionId=id;}
            if(released && role.status==="working")role.status="idle";
            if (released) {
              await this.journal.append("execution.historical_lock_released",event.worldId,{botId:role.botId,actionId:id,state:details.state});
              if (!role.run && !role.activeAction && !role.suspendedActionIds.size && (role.pending.size || this.resultQueue(role.worldId,role.botId).size)) this.trigger(role,"execution_reconciled",{actionId:id});
            }
          }
        }
        return true;
      }
      if (typeof details.botId === "string") this.resultQueue(event.worldId, details.botId).set(event.eventId, details);
      const actionId = terminalActionId(details);
      if (!actionId || typeof details.botId !== "string") return true;
      const terminalGeneration = terminalBodyGeneration(details);
      const failedContext = this.actionContexts.get(`${event.worldId}:${details.botId}:${actionId}`);
      if (String(details.state).toUpperCase() === "FAILED" && failedContext) this.recordFailedContext(`${event.worldId}:${details.botId}`, failedContext);
      for (const role of this.roles.values()) {
        if (role.worldId !== event.worldId || role.botId !== details.botId) continue;
        const run = role.run;
        if (typeof details.intentId === "string" && details.intentId !== role.intentId && details.intentId !== run?.intentId) continue;
        if (typeof details.taskId === "string" && role.assignedTaskId && details.taskId !== role.assignedTaskId) continue;
        const tracked = role.activeAction;
        const trackedMatch = tracked?.actionId === actionId;
        if (trackedMatch && terminalGeneration.present && terminalGeneration.value !== role.bodyGeneration) {
          role.activeAction=undefined;role.suspendedActionIds.delete(actionId);
          this.trigger(role, "reobserve_body_generation", { actionId, details });
          continue;
        }
        const completedKind=String(recordValue(details.payload)?.kind??"");
        if(["MOVE","EXCAVATE","EXPLORE"].includes(completedKind)&&["FAILED","PARTIAL"].includes(String(details.state))){
          const current=this.view?.companions.find(b=>b.botId===role.botId),failureKey=`${role.worldId}:${role.botId}:${role.intentId}`;
          if(current){const prior=this.localPathFailures.get(failureKey);const nearby=prior&&Math.hypot(current.position.x-prior.position.x,current.position.y-prior.position.y,current.position.z-prior.position.z)<4;const state={position:nearby?prior.position:current.position,count:nearby?prior.count+1:1};await this.journal.append("path.local_state",role.worldId,{botId:role.botId,intentId:role.intentId,...state});this.localPathFailures.set(failureKey,state);}
        }
        const failedTarget=recordValue(recordValue(details.payload)?.position),resultExecution=recordValue(details.execution);
        const resultReason=String(details.message??details.reason??"");
        if(failedTarget&&[failedTarget.x,failedTarget.y,failedTarget.z].every(n=>typeof n==="number")&&["FAILED","PARTIAL","EXPIRED"].includes(String(details.state))&&/PATH|ACCESS|STANCE|BLOCKED|unreachable|通路|采掘/i.test(resultReason)){
          const key=this.regionFailureKey(role,completedKind,failedTarget as {x:number;y:number;z:number});
          const production=Object.values(recordValue(resultExecution?.acquired)??{}).reduce<number>((n,v)=>n+(typeof v==="number"?v:0),0);
          if(production===0){const count=(this.taskRegionFailures.get(key)??0)+1;await this.journal.append("task.region_failure",role.worldId,{botId:role.botId,key,count,actionId});this.taskRegionFailures.set(key,count);}
        }
        const movement=recordValue(details.execution),start=recordValue(movement?.start),end=recordValue(movement?.position);
        const realMovement=start&&end&&Math.hypot(Number(end.x)-Number(start.x),Number(end.y)-Number(start.y),Number(end.z)-Number(start.z))>=1;
        const meaningful=!["WAIT","SELECT","PLACE","BUILD","EXCAVATE"].includes(completedKind)&&(!["MOVE","FOLLOW","GUARD"].includes(completedKind)||realMovement);
        if (trackedMatch && meaningful && String(details.state).toUpperCase() === "COMPLETED" && (!terminalGeneration.present || terminalGeneration.value === role.bodyGeneration)) {
          role.recoveryAttempts = 0; role.recoveryExhausted = false; role.failureSignature = undefined; this.clearRecovery(role);
          if (role.status === "failed") role.status = "idle";
        }
        role.suspendedActionIds.delete(actionId);
        this.suspendedFor(event.worldId, details.botId).delete(actionId);
        if (trackedMatch) {
          const teamTaskCompleted = Boolean(role.assignedTaskId && (
            details.taskId === role.assignedTaskId ||
            (run?.submittedTaskId === role.assignedTaskId && run.submittedActionId === actionId)));
          role.activeAction = undefined; role.lastTerminalActionId = actionId;
          if (teamTaskCompleted) {
            const state = typeof details.state === "string" ? details.state.toUpperCase() : "";
            if (TEAM_RECONCILE_STATES.has(state) || !TEAM_RELEASED_STATES.has(state)) {
              // PARTIAL, RECONCILE_REQUIRED, and an absent/unfamiliar state
              // retain the team lease.  The body effect is not safe to replay
              // or replace until a fresh authoritative status resolves it.
              role.teamTaskState = state === "PARTIAL" ? "PARTIAL" : "RECONCILE_REQUIRED";
              role.pending.set(`team_reconcile:${role.assignedTaskId}`, { taskId: role.assignedTaskId, state: role.teamTaskState, actionId });
            } else {
              role.assignedTaskId = undefined; role.assignedTaskIntentId = undefined; role.teamTaskState = undefined;
            }
          }
          if (role.origin === "autonomous" && !role.autonomyEnabled && !role.activeAction && !role.assignedTaskId && role.suspendedActionIds.size === 0) await this.suspendPlanning(role, "autonomy_off");
          if (role.standby && !role.activeAction && role.suspendedActionIds.size === 0) role.status = "idle";
        }
        // A terminal from an earlier action is journalled but cannot preempt a
        // newer model turn.  When there is an active run, only its exact
        // submitted action may yield the turn.
        if (run) {
          const currentRunAction = actionId.startsWith(`${run.intentId}:`) || run.submittedActionId === actionId;
          if (!run.valid || run.connection !== connection || run.intentId !== role.intentId || !currentRunAction) continue;
          if (terminalGeneration.present && terminalGeneration.value !== run.generation) {
            // The terminal receipt belongs to another physical body. Preserve
            // it in the journal, but force a fresh observation after the
            // current turn instead of interrupting or treating it as success.
            this.trigger(role, "reobserve_body_generation", { actionId, details });
            continue;
          }
          if (run.submittedActionId === undefined) {
            // A reconnect can start a recovery turn before a retained
            // terminal event arrives. Queue the result behind that turn so
            // the event is not ACKed and forgotten, while never interrupting
            // a turn that has not submitted a replacement action.
            this.trigger(role, "action_result", details);
            continue;
          }
          if (run.submittedActionId !== actionId) continue;
          if (!run.yieldForBodyResult) {
            run.yieldForBodyResult = true;
            if (run.handle) {
              run.valid = false;
              if (!run.interruptRequested) {
                run.interruptRequested = true;
                void run.handle.interrupt().catch(() => {});
              }
            }
          }
          this.trigger(role, "action_result", details);
          continue;
        }
        // A model may have ended naturally before the body emits its terminal
        // event.  Recover only actions belonging to the current owner intent.
        if (role.intentId && (actionId.startsWith(`${role.intentId}:`) || (trackedMatch && role.suspendedActionIds.size === 0))) this.trigger(role, "action_result", details);
      }
    }
    return true;
  }
  private async handleBodyAvailable(connection: ModConnection, eventWorldId: string,
    available: ReturnType<typeof availableBody>): Promise<boolean> {
    if (!available || connection !== this.connection || connection.connectionState !== "ready"
      || connection.sessionContext?.worldId !== eventWorldId) return true;
    let observed: WorldView;
    try {
      observed = await connection.request<WorldView>("reconcile", {}, { timeoutMs: 10_000 });
    } catch (error) {
      if (connection === this.connection) {
        const reason = error instanceof Error ? error : new Error(String(error));
        // Let ModConnection emit the negative ACK before closing. The event
        // remains retained by ModLink and can be replayed on the replacement
        // connection; closing synchronously would make ACK delivery throw.
        setTimeout(() => { if (connection === this.connection) connection.close(reason); }, 0);
      }
      return false;
    }
    if (connection !== this.connection || connection.connectionState !== "ready"
      || connection.sessionContext?.worldId !== eventWorldId || observed.worldId !== eventWorldId) return false;
    await this.applyRecoveryRequests(connection,observed);
    await this.reconcileHistoricalResults(connection,observed);
    const body = observed.companions.find(candidate => candidate.botId === available.botId);
    if (!body || body.entityId !== available.entityId || body.bodyGeneration !== available.bodyGeneration
      || body.dimension !== available.dimension) return true;
    this.view = observed;
    for (const role of this.roles.values()) {
      if (role.worldId !== eventWorldId || role.botId !== available.botId) continue;
      // A previous body may have left the role paused. The new body's
      // authoritative controls decide whether play can resume, not that cache.
      const manualPause = body.stopped === true || body.paused === true || body.recoveryInvalid === true;
      const changedPhysical = (role.entityId !== undefined && role.entityId !== body.entityId)
        || (role.bodyGeneration !== undefined && role.bodyGeneration !== body.bodyGeneration);
      const changedRun = role.run !== undefined && role.run.generation !== body.bodyGeneration;
      if(changedPhysical||changedRun) {
        this.recoveryEvaluations.set(`${role.worldId}:${role.botId}`,body.bodyGeneration);
        await this.journal.append("body.recovery_assessment",role.worldId,{botId:role.botId,bodyGeneration:body.bodyGeneration,entityId:body.entityId,previousGoal:role.command,stages:this.stages.history(role.worldId,role.botId),body});
      }
      if(changedPhysical){role.activeAction=undefined;role.suspendedActionIds.clear();this.suspendedFor(role.worldId,role.botId).clear();}
      // The new explicit body snapshot owns the new lease; old outcomes remain in the journal.
      role.bodyGeneration=body.bodyGeneration;
      const bodyHasActiveAction = this.syncActiveAction(role, body);
      role.entityId = body.entityId; role.bodyGeneration = body.bodyGeneration;
      const shouldWake = !manualPause && (role.status === "awaiting_body" || role.status === "disconnected" || changedPhysical || changedRun);
      if (changedRun || changedPhysical) {
        this.invalidate(role, manualPause ? "paused" : "disconnected");
        role.status = manualPause ? "paused" : bodyHasActiveAction ? "working" : "idle";
      }
      if (shouldWake && !manualPause) {
        role.status = "idle";
        this.trigger(role, "body_available", { botId: body.botId, entityId: body.entityId, bodyGeneration: body.bodyGeneration });
      }
    }
    // Do not overwrite the old generation before invalidating its model turn.
    if (this.brain) await this.ensureAutonomousBodies(observed, available.botId);
    await this.wakeReconciledRoles(connection, observed, "body_available", available.botId);
    return true;
  }
}
