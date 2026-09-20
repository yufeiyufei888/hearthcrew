import { workBoard, type WorkCard } from "./work-board.js";
/**
 * A deliberately small, read-only projection for the future in-game UI.
 *
 * This module accepts untrusted controller/journal-shaped values and builds a
 * new object.  It never returns a status/event object or a nested value from
 * either input, and it intentionally has no dependency on the controller.
 */

export type UiUnknown = "unknown";
export type UiText = string | UiUnknown;
export type UiNumber = number | UiUnknown;

export interface UiPosition { readonly x: UiNumber; readonly y: UiNumber; readonly z: UiNumber; }
export interface UiInventorySlot { readonly slot: number; readonly item: string; readonly count: number; }
export interface UiEquippedItem { readonly item: UiText; readonly count: UiNumber; }
export type UiEquipment = Readonly<Record<"head" | "chest" | "legs" | "feet" | "offhand", UiEquippedItem | null | UiUnknown>>;

export interface UiCompanion {
  readonly botId: UiText;
  readonly name: UiText;
  readonly role: UiText;
  readonly dimension: UiText;
  readonly position: UiPosition | UiUnknown;
  readonly health: UiNumber;
  readonly food: UiNumber;
  readonly inventory: readonly UiInventorySlot[];
  readonly selectedSlot: UiNumber;
  readonly equipment: UiEquipment | UiUnknown;
  readonly action: UiText;
  readonly autonomyEnabled: boolean;
  readonly activity: UiText;
  readonly goal: UiText;
  readonly waitReason: UiText;
  readonly recoverySummary: UiText;
}

export interface UiBuildStep {
  readonly position: UiPosition | UiUnknown;
  readonly block: UiText;
}

export interface UiTaskParameters {
  readonly position?: UiPosition | UiUnknown;
  readonly target?: UiText;
  readonly count?: UiNumber;
  readonly resource?: UiText;
  readonly steps?: readonly UiBuildStep[];
}

export interface UiTask {
  readonly taskId: UiText;
  readonly botId: UiText;
  readonly intentId: UiText;
  readonly kind: UiText;
  readonly state: UiText;
  readonly actionId: UiText;
  readonly bodyGeneration: UiNumber;
  readonly parameters: UiTaskParameters;
}

export interface UiMessage {
  readonly sequence: number | UiUnknown;
  readonly messageId?: string;
  readonly atUtc?: string;
  readonly origin?: string;
  readonly replyTo?: string;
  readonly replyName?: string;
  readonly statusReason?: string;
  /** UI request identity used to reconcile an optimistic owner message. */
  readonly requestId: UiText;
  readonly type: string;
  readonly role: UiText;
  readonly botId: UiText;
  readonly recipients: readonly string[];
  readonly message: string;
  readonly state: UiText;
  readonly taskId: UiText;
  readonly actionId: UiText;
}

export interface UiDiagnosticLayer {
  readonly status: UiText;
  readonly details: Readonly<Record<string, UiText | UiNumber | boolean>>;
}

export interface UiRoleDiagnostic {
  readonly role: UiText;
  readonly name?: UiText;
  readonly threadId?: UiText;
  readonly botId: UiText;
  readonly status: UiText;
  readonly taskId: UiText;
  readonly taskState: UiText;
  readonly model: UiText;
  readonly effort: UiText;
  readonly serviceTier: UiText;
  readonly autonomyEnabled: boolean;
  readonly activity: UiText;
  readonly goal: UiText;
  readonly waitReason: UiText;
}

export interface UiDiagnostics {
  readonly game: UiDiagnosticLayer;
  readonly controller: UiDiagnosticLayer;
  readonly appServer: UiDiagnosticLayer;
  readonly roles: readonly UiRoleDiagnostic[];
}

export interface UiView {
  readonly schemaVersion: 2;
  readonly truncated: boolean;
  readonly worldId: string;
  readonly updatedAt: string;
  readonly work: readonly WorkCard[];
  readonly companions: readonly UiCompanion[];
  readonly tasks: readonly UiTask[];
  readonly messages: readonly UiMessage[];
  readonly diagnostics: UiDiagnostics;
}

type RecordValue = Record<string, unknown>;
const UNKNOWN: UiUnknown = "unknown";
const MAX_TEXT = 256;
const MAX_MESSAGE = 512;
const MAX_ID = 128;

function record(value: unknown): RecordValue | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as RecordValue : undefined;
}

function array(value: unknown): readonly unknown[] { return Array.isArray(value) ? value : []; }

function text(value: unknown, max = MAX_TEXT): string | undefined {
  if (typeof value !== "string" || value.length === 0 || value.length > max) return undefined;
  return value;
}

function id(value: unknown): string | undefined {
  const direct = text(value, MAX_ID);
  if (direct) return direct;
  const object = record(value);
  return text(object?.value, MAX_ID);
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function safeText(value: unknown, max = MAX_TEXT): UiText {
  return text(value, max) ?? UNKNOWN;
}

function safeNumber(value: unknown): UiNumber {
  return numberValue(value) ?? UNKNOWN;
}

function safeBoolean(value: unknown): boolean { return typeof value === "boolean" ? value : false; }

function position(value: unknown): UiPosition | UiUnknown {
  const source = record(value);
  if (!source) return UNKNOWN;
  return { x: safeNumber(source.x), y: safeNumber(source.y), z: safeNumber(source.z) };
}

export function publicText(value: unknown): string {
  let result = typeof value === "string" ? value : "unknown";
  result = result.slice(0, MAX_MESSAGE);
  // Messages and failure reasons are public UI text, but paths and common
  // credential-shaped values must never cross this projection boundary.
  result = result.replace(/[A-Za-z]:[\\/][^\s"'<>]+/g, "[path]");
  result = result.replace(/(?:^|\s)\/[^\s"'<>]+/g, " [path]");
  result = result.replace(/\b(token|password|secret|api[_-]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, "$1=[redacted]");
  return result;
}

function actionName(value: unknown, body: RecordValue): UiText {
  if (body.recoveryInvalid === true) return "待核对";
  if (body.stopped === true) return "已停止";
  if (body.paused === true) return "已暂停";
  const source = record(value);
  const payload = record(source?.payload);
  const kind = text(source?.kind ?? payload?.kind ?? source?.type, 64);
  if (kind?.toUpperCase() === "GATHER") return "连续采木";
  if (kind) return kind;
  const state = text(source?.state, 64)?.toUpperCase();
  if (state === "ACCEPTED" || state === "RUNNING" || state === "SUSPENDED") return "执行中";
  if (state === "COMPLETED") return "已完成";
  if (state === "CANCELLED") return "已取消";
  if (state === "FAILED") return "失败";
  return body.standby===true?"待命":"当前无身体动作";
}

function companionRole(source: RecordValue, matchingRole: RecordValue | undefined): UiText {
  const assigned = text(matchingRole?.role, 64) ?? text(source.role, 64);
  if (assigned) return assigned;
  // Development fixtures expose their intended stable role in the display
  // name before a Codex thread has been bound. Custom names remain unknown.
  const name = text(source.name, 128)?.toLowerCase() ?? "";
  if (/(^|[^a-z])(captain|coordinator)([^a-z]|$)/.test(name) || name.includes("队长") || name.includes("协调")) return "coordinator";
  if (/(^|[^a-z])(gatherer|explorer)([^a-z]|$)/.test(name) || name.includes("采集") || name.includes("探索")) return "gatherer";
  if (/(^|[^a-z])(builder|logistics)([^a-z]|$)/.test(name) || name.includes("建设") || name.includes("后勤")) return "builder";
  return UNKNOWN;
}

function inventory(value: unknown): readonly UiInventorySlot[] {
  const result: UiInventorySlot[] = [];
  const seen = new Set<number>();
  for (const raw of array(value)) {
    if (result.length >= 36) break;
    const item = record(raw);
    const slot = item?.slot;
    const count = item?.count;
    const name = text(item?.item, MAX_TEXT);
    if (!Number.isSafeInteger(slot) || (slot as number) < 0 || (slot as number) >= 36 || seen.has(slot as number)
      || !Number.isSafeInteger(count) || (count as number) < 1 || !name) continue;
    seen.add(slot as number);
    result.push({ slot: slot as number, item: name, count: count as number });
  }
  return result;
}

function equipped(value: unknown): UiEquippedItem | null | UiUnknown {
  if (value === null) return null;
  const source = record(value);
  if (!source) return UNKNOWN;
  const name = text(source.item, MAX_TEXT);
  const count = numberValue(source.count);
  if (!name || !Number.isSafeInteger(count) || (count as number) < 1) return UNKNOWN;
  return { item: name, count: count as number };
}

function equipment(value: unknown): UiEquipment | UiUnknown {
  const source = record(value);
  if (!source) return UNKNOWN;
  return {
    head: equipped(source.head),
    chest: equipped(source.chest),
    legs: equipped(source.legs),
    feet: equipped(source.feet),
    offhand: equipped(source.offhand),
  };
}

function steps(value: unknown): readonly UiBuildStep[] | undefined {
  const source = array(value);
  if (!source.length) return undefined;
  const result: UiBuildStep[] = [];
  for (const raw of source) {
    if (result.length >= 16) break;
    const step = record(raw);
    if (!step) continue;
    result.push({ position: position(step.position), block: safeText(step.block, MAX_TEXT) });
  }
  return result;
}

function taskParameters(source: RecordValue | undefined): UiTaskParameters {
  if (!source) return {};
  const result: { position?: UiPosition | UiUnknown; target?: UiText; count?: UiNumber; resource?: UiText; steps?: readonly UiBuildStep[] } = {};
  if (source.position !== undefined) result.position = position(source.position);
  if (source.target !== undefined) result.target = safeText(id(source.target) ?? source.target, MAX_ID);
  if (source.count !== undefined) result.count = safeNumber(source.count);
  if (source.resource !== undefined) result.resource = safeText(source.resource, MAX_TEXT);
  const buildSteps = steps(source.steps);
  if (buildSteps) result.steps = buildSteps;
  return result;
}

function companionView(source: RecordValue, roles: readonly RecordValue[]): UiCompanion {
  const botId = id(source.botId);
  const matchingRole = botId === undefined ? undefined : roles.find(candidate => id(candidate.botId) === botId);
  const localSafety = record(source.localSafety);
  const travel = record(source.travel);
  const execution=record(source.execution);
  const mining=execution && typeof execution.requested==="number";
  const recovery=array(source.harvestEvidence).map(record).filter(e=>e&&e.recovery!=="accounted");
  const drops=recovery.flatMap(e=>array(e?.pendingDrops).map(record));
  const visible=drops.filter(d=>d?.availability==="visible"||d?.availability==="pickup_delayed").length;
  const removed=drops.filter(d=>d?.availability==="removed").length+recovery.reduce((n,e)=>n+array(e?.closedDrops).length,0);
  const total=(v:unknown)=>Object.values(record(v)??{}).reduce<number>((n,x)=>n+(typeof x==="number"?x:0),0);
  const miningText=mining?`采掘 ${execution.broken??0}/${execution.requested} 块 · 开路 ${execution.accessBroken??0} 块 · 本人取得 ${total(execution.acquired)} · 队友取得 ${total(execution.acquiredByOthers)} · 未回收 ${array(execution.pendingDrops).length} 组`:undefined;
  return {
    botId: safeText(botId, MAX_ID),
    name: safeText(source.name, 64),
    role: companionRole(source, matchingRole),
    dimension: safeText(source.dimension, MAX_TEXT),
    position: position(source.position),
    health: safeNumber(source.health),
    food: safeNumber(source.food),
    inventory: inventory(source.inventory),
    selectedSlot: Number.isSafeInteger(source.selectedSlot) && (source.selectedSlot as number) >= 0 && (source.selectedSlot as number) <= 8
      ? source.selectedSlot as number : UNKNOWN,
    equipment: source.equipment === undefined ? UNKNOWN : equipment(source.equipment),
    action: actionName(source.action, source),
    autonomyEnabled: safeBoolean(matchingRole?.autonomyEnabled ?? source.autonomyEnabled),
    activity: source.recoveryInvalid === true ? "reconcile_required" : source.stopped === true ? "stopped"
      : source.paused === true ? "paused" : mining ? safeText(execution.phase,64) : travel?.active === true ? safeText(travel.phase,64) : localSafety?.active === true ? safeText(localSafety.mode, 64) : safeText(matchingRole?.activity ?? source.activity, 64),
    goal: safeText(matchingRole?.goal ?? source.goal, MAX_MESSAGE),
    recoverySummary: recovery.length?`历史回收：可见 ${visible} 组，已消失 ${removed} 组，未观测 ${drops.filter(d=>d?.availability!=="removed"&&d?.availability!=="visible"&&d?.availability!=="pickup_delayed").length} 组；不代表当前正在等待，不阻止其他工作`:UNKNOWN,
    waitReason: safeText(source.standby === true && source.waitReason ? source.waitReason : travel?.active === true ? miningText??`空气 ${travel.air} · ${travel.phase}` : localSafety?.active === true ? localSafety.reason : matchingRole?.waitReason ?? miningText ?? source.waitReason, MAX_MESSAGE),
  };
}

function taskId(source: RecordValue | undefined): string | undefined {
  return id(source?.taskId) ?? id(source?.id) ?? id(record(source?.task)?.id);
}

function bodyActionTasks(game: RecordValue | undefined): readonly UiTask[] {
  const latest = new Map<string, { body: RecordValue; row: RecordValue; tick: number }>();
  for (const rawBody of array(game?.companions)) {
    const body = record(rawBody);
    if (!body) continue;
    for (const rawRow of array(body.actionJournal)) {
      const row = record(rawRow);
      const payload = record(row?.payload);
      const actionId = id(row?.id) ?? id(row?.actionId);
      const kind = text(payload?.kind, 64);
      if (!row || !payload || !actionId || !kind) continue;
      const tick = numberValue(row.gameTick) ?? numberValue(row.worldTick) ?? numberValue(row.sequence) ?? -1;
      const previous = latest.get(actionId);
      if (!previous || tick >= previous.tick) latest.set(actionId, { body, row, tick });
    }
  }
  const rows = [...latest.values()].sort((left, right) => {
    const leftActive = ["ACCEPTED", "RUNNING", "SUSPENDED"].includes(String(left.row.state).toUpperCase());
    const rightActive = ["ACCEPTED", "RUNNING", "SUSPENDED"].includes(String(right.row.state).toUpperCase());
    if (leftActive !== rightActive) return leftActive ? -1 : 1;
    return right.tick - left.tick;
  });
  return rows.map(({ body, row }) => {
    const payload = record(row.payload)!;
    const actionId = id(row.id) ?? id(row.actionId)!;
    const epoch = record(row.epoch);
    return {
      taskId: safeText(actionId, MAX_ID),
      botId: safeText(id(body.botId), MAX_ID),
      intentId: safeText(row.intentId, MAX_ID),
      kind: safeText(payload.kind, 64),
      state: safeText(row.state, 64),
      actionId: safeText(actionId, MAX_ID),
      bodyGeneration: safeNumber(epoch?.bodyGeneration ?? body.bodyGeneration),
      parameters: taskParameters(payload),
    };
  });
}

function taskViews(game: RecordValue | undefined): readonly UiTask[] {
  const team = record(game?.team);
  const works = array(team?.works).map(record).filter((value): value is RecordValue => value !== undefined);
  const ledger = record(team?.ledger);
  const ledgerTasks = array(ledger?.tasks).map(record).filter((value): value is RecordValue => value !== undefined);
  const bindings = array(team?.bindings).map(record).filter((value): value is RecordValue => value !== undefined);
  const ledgerById = new Map<string, RecordValue>();
  for (const entry of ledgerTasks) { const key = taskId(entry); if (key) ledgerById.set(key, entry); }
  const bindingById = new Map<string, RecordValue>();
  for (const entry of bindings) { const key = taskId(entry); if (key) bindingById.set(key, entry); }
  const workById = new Map<string, RecordValue>();
  for (const entry of works) { const key = taskId(entry); if (key && !workById.has(key)) workById.set(key, entry); }
  const ids = [...new Set([...workById.keys(), ...ledgerById.keys(), ...bindingById.keys()])];
  const releasedStates = new Set(["COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "STALE"]);
  const orderedIds = ids.map((key, index) => {
    const entry = ledgerById.get(key);
    const rawState = text(entry?.state, 64)?.toUpperCase();
    const updatedTick = numberValue(entry?.updatedGameTick) ?? numberValue(entry?.gameTick) ?? -1;
    return { key, index, active: rawState === undefined || !releasedStates.has(rawState), updatedTick };
  }).sort((left, right) => {
    if (left.active !== right.active) return left.active ? -1 : 1;
    if (!left.active && right.updatedTick !== left.updatedTick) return right.updatedTick - left.updatedTick;
    return left.index - right.index;
  }).slice(0, 24).map(entry => entry.key);
  const projected = orderedIds.map(key => {
    const work = workById.get(key);
    const ledgerEntry = ledgerById.get(key);
    const ledgerTask = record(ledgerEntry?.task);
    const binding = bindingById.get(key);
    return {
      taskId: safeText(key, MAX_ID),
      botId: safeText(id(work?.botId) ?? id(ledgerEntry?.assignedBody) ?? id(ledgerEntry?.assignedBotId), MAX_ID),
      intentId: safeText(work?.intentId, MAX_ID),
      kind: safeText(work?.kind, 64),
      state: safeText(ledgerEntry?.state, 64),
      actionId: safeText(id(binding?.actionId) ?? id(binding?.id), MAX_ID),
      bodyGeneration: safeNumber(work?.bodyGeneration ?? ledgerEntry?.bodyGeneration ?? binding?.bodyGeneration),
      parameters: taskParameters(work ?? ledgerTask),
    };
  });
  const assignedActionIds = new Set(projected.map(task => task.actionId).filter((value): value is string => value !== UNKNOWN));
  const fallback = bodyActionTasks(game).filter(task => typeof task.actionId === "string" && !assignedActionIds.has(task.actionId));
  return [...projected, ...fallback].slice(0, 24);
}

function messageView(row: RecordValue): UiMessage | undefined {
  const type = text(row.type, 64);
  const data = record(row.data);
  if (!type || !data) return undefined;
  let message: string | undefined;
  let state: unknown;
  let task: unknown;
  let action: unknown;
  let requestId: unknown;
  let role: unknown = data.role;
  let bot: unknown = data.botId;
  let recipients: string[] = [];
  if (type === "crew.chat") {
    requestId = data.messageId; role = data.senderName; bot = data.origin === "companion" ? data.senderId : undefined;
    recipients = array(data.recipientNames).map(n => safeText(n, 64));
    message = (typeof data.replyTo === "string" ? `↪ ${publicText(data.replyName ?? String(data.replyTo).slice(0, 8))}：` : "") + publicText(data.message);
  } else if (type === "chat.owner_result") { requestId = data.messageId; state = data.state; role = "系统"; message = data.state === "DISPATCHED" ? "点名任务已派发" : data.state === "QUEUED" ? "已加入待办，先完成伙伴自己的当前工作" : "点名任务需要核对，未自动重复派发";
  } else if (type === "owner.request.queued") { role = "系统"; requestId = data.id; message = `已加入待办（不打断手头工作）：${publicText(data.message)}`;
  } else if (type === "owner.request.closed") { role = "系统"; requestId = data.id; message = `待办请求已停止或待核对：${publicText(data.reason)}`;
  } else if (type === "owner.command") { requestId = data.uiRequestId; message = publicText(data.message);recipients=array(data.recipientNames).map(n=>safeText(n,64)); }
  else if (type === "role.shared") {
    message = publicText(data.message);
    recipients = array(data.recipients).map(value => text(value, 64)).filter((value): value is string => value !== undefined).slice(0, 3);
  } else if (type === "tool.rejected") { role = "系统"; bot = data.botId; message = publicText(data.reason);
  } else if (type === "turn.recovery") { role = "系统"; bot = data.botId; message = `模型恢复第${data.attempt}次，等待${Number(data.delayMs) / 1000}秒`;
  } else if (type === "role.failure") message = publicText(data.reason);
  else if (type === "ui.claim") {
    requestId = data.requestId;
    const decision = text(data.decision, 64) ?? "UNKNOWN";
    state = decision;
    message = decision === "CLAIMED" ? "界面请求已确认，正在派发" : `界面请求确认结果：${publicText(decision)}`;
  } else if (type === "ui.failure") {
    requestId = data.requestId;
    state = "FAILED";
    message = publicText(data.reason);
  }
  else if (type === "role.final" || type === "role.message") message = publicText(data.message ?? data.text ?? data.output);
  else if (type === "team.proposed") { task = data.taskId; message = "团队任务已提交"; }
  else if (type === "team.execute.receipt") {
    task = data.taskId;
    const receipt = record(data.receipt);
    state = receipt?.state;
    message = "团队动作已收到实际回执";
  } else if (type === "cooperation.state") {
    role="协作";bot=data.recipient;state=data.state;message=`${publicText(data.summary)}：${publicText(data.state)}${data.reason?"；"+publicText(data.reason):""}`;
  } else if (type === "game.event") { return undefined;
  } else return undefined;
  return {
    sequence: Number.isSafeInteger(row.sequence) ? row.sequence as number : UNKNOWN,
    requestId: safeText(id(requestId) ?? requestId, MAX_ID),
    type,
    role: safeText(role, 64),
    botId: safeText(id(bot) ?? bot, MAX_ID),
    recipients,
    message: message ?? "unknown",
    state: safeText(state, 64),
    taskId: safeText(id(task) ?? task, MAX_ID),
    actionId: safeText(id(action) ?? action, MAX_ID),
  };
}

export function messages(events: unknown): readonly UiMessage[] {
  const rows=array(events).map(record).filter((r):r is RecordValue=>!!r);
  const states=new Map<string,{state:string;reason?:string}>();
  for(const row of rows){const d=record(row.data)??{};const key=id(d.requestId)??id(d.uiRequestId)??id(d.messageId)??id(d.id);if(!key)continue;
    if(row.type==="ui.failure")states.set(key,{state:"FAILED",reason:publicText(d.reason)});
    else if(row.type==="owner.request.queued")states.set(key,{state:"QUEUED"});
    else if(row.type==="owner.request.closed")states.set(key,{state:"CLOSED",reason:publicText(d.reason)});
    else if(row.type==="chat.owner_result")states.set(key,{state:String(d.state)});
    else if(row.type==="owner.command"&&!states.has(key))states.set(key,{state:"ACCEPTED"});
  }
  const result=new Map<string,UiMessage>();
  for(const row of rows){if(!["crew.chat","owner.command","role.shared","owner.request.queued"].includes(String(row.type)))continue;
    const d=record(row.data)??{},item=messageView(row);if(!item)continue;
    const key=id(d.messageId)??id(d.uiRequestId)??id(d.id)??`legacy:${row.sequence}`;
    if(result.has(key)&&row.type!=="crew.chat")continue;
    const sender=["owner.command","owner.request.queued"].includes(String(row.type))?"你":item.role;
    const origin=["owner.command","owner.request.queued"].includes(String(row.type))?"player":typeof d.origin==="string"?d.origin:"companion";
    result.set(key,{...item,role:sender,type:row.type==="owner.request.queued"?"owner.command":item.type,messageId:key,atUtc:typeof row.atUtc==="string"?row.atUtc:"",origin,
      message:publicText(d.message),replyTo:typeof d.replyTo==="string"?d.replyTo:undefined,replyName:typeof d.replyName==="string"?d.replyName:undefined,
      state:states.get(key)?.state??(origin==="player"?"SENT":"unknown"),
      ...(states.get(key)?.reason?{statusReason:states.get(key)!.reason}:{})});
  }
  return [...result.values()].sort((a,b)=>Number(a.sequence)-Number(b.sequence));
}

function layer(status: UiText, details: Record<string, UiText | UiNumber | boolean>): UiDiagnosticLayer {
  return { status, details: { ...details } };
}

function gameStatus(game: RecordValue | undefined): UiText {
  const explicit = text(game?.status, 64);
  if (explicit) return explicit;
  const bodies = array(game?.companions).map(record).filter((value): value is RecordValue => value !== undefined);
  if (bodies.some(body => body.stopped === true)) return "STOPPED";
  if (bodies.some(body => body.paused === true)) return "PAUSED";
  if (typeof game?.worldId === "string" && game.worldId.length > 0 && bodies.length > 0) return "RUNNING";
  return UNKNOWN;
}

function diagnostics(root: RecordValue, game: RecordValue | undefined, roles: readonly RecordValue[]): UiDiagnostics {
  const team = record(game?.team);
  const app = record(root.appServer);
  const gameLayer = layer(gameStatus(game), {
    stage: safeText(game?.stage, 64),
    protocol: safeNumber(game?.executionProtocol),
    lastAction: publicText(array(root.diagnosticEvents).map(record).filter(row=>row?.type==="game.event"&&record(row.data)?.event==="action.terminal").slice(-1).map(row=>record(record(row?.data)?.body)?.message).join("")),
    worldId: safeText(game?.worldId, MAX_ID),
    gameTick: safeNumber(game?.gameTick),
    companions: Math.min(array(game?.companions).length, 3),
    teamRecoveryInvalid: typeof team?.recoveryInvalid === "boolean" ? team.recoveryInvalid : UNKNOWN,
  });
  const controllerLayer = layer(safeText(root.controller, 64), {
    version:"0.3.2",
    quietProgress: publicText(record(array(root.diagnosticEvents).map(record).filter(r=>r?.type==="work.progress").at(-1)?.data)?.reason??"无近期静默记录；阶段结束不要求公开发言"),
    lastError: publicText(array(root.diagnosticEvents).map(record).filter(r=>["ui.failure","tool.rejected","role.failure","turn.recovery"].includes(String(r?.type))).at(-1)?.data && record(array(root.diagnosticEvents).map(record).filter(r=>["ui.failure","tool.rejected","role.failure"].includes(String(r?.type))).at(-1)?.data)?.reason),
    restoration: Math.min(array(root.restoration).length, 24),
    gameEvidence: safeText(record(root.runtimeDiagnostics)?.gameEvidence,160),
    connection: safeText(record(root.runtimeDiagnostics)?.connection,64),
    sampledAt: safeText(record(root.runtimeDiagnostics)?.atUtc,64),
  });
  const appStatus = app ? safeText(app.appServer ?? app.status, 64) : safeText(root.appServer, 64);
  const appLayer = layer(appStatus, {
    cliVersion: safeText(app?.cliVersion, 64),
    gameOnlyTools: safeText(app?.gameOnlyTools, 64),
    productionGameplay: safeText(app?.productionGameplay, 64),
    modelCatalogConfigured: typeof app?.modelCatalogConfigured === "boolean" ? app.modelCatalogConfigured : UNKNOWN,
    toolCount: Array.isArray(app?.toolNames) ? Math.min(app.toolNames.length, 32) : UNKNOWN,
  });
  const roleViews = roles.slice(0, 3).map(role => ({
    name: safeText(array(game?.companions).map(record).find(b => b?.botId === role.botId)?.name, 64), threadId: safeText(role.threadId, 128),
    role: safeText(role.role, 64), botId: safeText(id(role.botId) ?? role.botId, MAX_ID), status: safeText(role.status, 64),
    taskId: safeText(id(role.taskId) ?? role.taskId, MAX_ID), taskState: safeText(role.taskState, 64), model: safeText(role.model, 64),
    effort: safeText(role.reasoningEffort, 32), serviceTier: safeText(role.serviceTier, 32),
    autonomyEnabled: safeBoolean(role.autonomyEnabled), activity: safeText(role.activity, 64),
    goal: safeText(role.goal, MAX_MESSAGE), waitReason: safeText(role.waitReason, MAX_MESSAGE),
  }));
  return { game: gameLayer, controller: controllerLayer, appServer: appLayer, roles: roleViews };
}

// Leave room for the controller's {ok, view, operationResult} response
// envelope before ModLink applies its 60 KiB UI update budget.
const UI_MAX_BYTES = 58 * 1024;

function byteLength(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).byteLength;
}

function compactCompanions(companions: readonly UiCompanion[], inventoryLimit: number, itemLimit: number): readonly UiCompanion[] {
  return companions.map(companion => ({
    ...companion,
    name: typeof companion.name === "string" ? companion.name.slice(0, 64) : companion.name,
    dimension: typeof companion.dimension === "string" ? companion.dimension.slice(0, 96) : companion.dimension,
    inventory: companion.inventory.slice(0, inventoryLimit).map(slot => ({ ...slot, item: slot.item.slice(0, itemLimit) })),
  }));
}

function compactTasks(tasks: readonly UiTask[], limit: number, keepParameters: boolean): readonly UiTask[] {
  return tasks.slice(0, limit).map(task => ({
    ...task,
    taskId: typeof task.taskId === "string" ? task.taskId.slice(0, 96) : task.taskId,
    botId: typeof task.botId === "string" ? task.botId.slice(0, 96) : task.botId,
    intentId: typeof task.intentId === "string" ? task.intentId.slice(0, 96) : task.intentId,
    actionId: typeof task.actionId === "string" ? task.actionId.slice(0, 96) : task.actionId,
    parameters: keepParameters ? { ...task.parameters, steps: undefined } : {},
  }));
}

function compactMessages(messages: readonly UiMessage[], limit: number, messageLimit: number): readonly UiMessage[] {
  return messages.slice(-limit).map(message => ({
    ...message,
    message: message.message.slice(0, messageLimit),
    role: typeof message.role === "string" ? message.role.slice(0, 48) : message.role,
    botId: typeof message.botId === "string" ? message.botId.slice(0, 96) : message.botId,
    taskId: typeof message.taskId === "string" ? message.taskId.slice(0, 96) : message.taskId,
    actionId: typeof message.actionId === "string" ? message.actionId.slice(0, 96) : message.actionId,
    recipients: message.recipients.slice(0, 3).map(recipient => recipient.slice(0, 48)),
  }));
}

function fitBudget(view: Omit<UiView, "truncated">): UiView {
  const full = { ...view, truncated: false as const };
  if (byteLength(full) <= UI_MAX_BYTES) return full;
  let candidate: UiView = {
    ...view, truncated: true,
    companions: compactCompanions(view.companions, 18, 128),
    tasks: compactTasks(view.tasks, 24, true),
    messages: compactMessages(view.messages, 20, 384),
  };
  if (byteLength(candidate) <= UI_MAX_BYTES) return candidate;
  candidate = {
    ...view, truncated: true,
    companions: compactCompanions(view.companions, 9, 96),
    tasks: compactTasks(view.tasks, 16, false),
    messages: compactMessages(view.messages, 12, 192),
  };
  if (byteLength(candidate) <= UI_MAX_BYTES) return candidate;
  candidate = {
    ...view, truncated: true,
    companions: compactCompanions(view.companions, 3, 64),
    tasks: compactTasks(view.tasks, 8, false),
    messages: compactMessages(view.messages, 4, 128),
  };
  if (byteLength(candidate) <= UI_MAX_BYTES) return candidate;
  // The bounded input fields above make this final form comfortably below the
  // limit while retaining all bodies and all four diagnostic layers.
  return {
    ...view, truncated: true,
    companions: compactCompanions(view.companions, 0, 32),
    tasks: [],
    messages: [],
  };
}

export function makeUiView(status: unknown, events: unknown): UiView {
  const root = record(status) ?? {};
  const game = record(root.game);
  const roles = array(root.roles).map(record).filter((value): value is RecordValue => value !== undefined).slice(0, 3);
  return fitBudget({
    schemaVersion: 2,
    companions: array(game?.companions).map(record).filter((value): value is RecordValue => value !== undefined).slice(0, 3).map(value => companionView(value, roles)).sort((a,b)=>["Ember","Moss","Flint"].indexOf(a.name)-["Ember","Moss","Flint"].indexOf(b.name)),
    tasks: [],
    worldId: safeText(game?.worldId), updatedAt: new Date().toISOString(),
    work: workBoard(root,v=>publicText(v).slice(0,1600)),
    messages: messages(events).slice(-30),
    diagnostics: diagnostics({...root,diagnosticEvents:events}, game, roles),
  });
}
