import { isExecutionBackend, type ExecutionBackendDescriptor } from "./execution-backend.js";
export const PROTOCOL = "hearthcrew.v1" as const;
export const MAX_FRAME_BYTES = 1024 * 1024;
export const WIRE_OPERATIONS = ["status", "observe", "intent.bind", "action.submit", "recovery.standby", "action.reconcile_history", "action.history", "action.cancel", "control", "reconcile", "team.status", "team.propose", "team.execute", "ui.claim", "ui.update", "chat.publish"] as const;
export type WireOperation = typeof WIRE_OPERATIONS[number];

export interface BlockPosition { readonly x: number; readonly y: number; readonly z: number; }
export interface BuildStep { readonly position: BlockPosition; readonly block: string; }
export interface ActionSubmitBody {
  readonly botId: string;
  readonly kind: string;
  /** Monotonic controller intent generation for a live owner/autonomous binding. */
  readonly intentGeneration?: number;
  readonly position?: BlockPosition;
  /** Actual entity UUID from observation; botId is the separate logical companion UUID. */
  readonly target?: string;
  readonly count?: number;
  readonly accessBudget?: number;
  /** Registered recipe ID for CRAFT, or item ID for TRANSFER. */
  readonly resource?: string;
  /** Ordered BUILD steps; bridge validation limits this to 1..16 unique positions. */
  readonly steps?: readonly BuildStep[];
}
export interface IntentBindBody {
  readonly botId: string;
  readonly origin: "owner" | "autonomous";
  readonly intentGeneration: number;
}
export interface ActionCancelBody { readonly botId: string; readonly intentId: string; readonly actionId: string; readonly bodyGeneration: number; readonly reason?: string; }
export interface ControlBody { readonly botId: string; readonly operation: "pause" | "resume" | "stop" | "retask"; readonly bodyGeneration?: number; }
/** Reconciliation returns the current world, all live bodies, inventory and action receipts. */
export interface ReconcileBody { readonly botId?: string; readonly bodyGeneration?: number; }
export interface TeamProposeBody extends ActionSubmitBody {
  readonly taskId: string;
  readonly description: string;
}
export interface TeamExecuteBody {
  readonly taskId: string;
  readonly botId: string;
  readonly intentGeneration?: number;
}
export interface UiClaimBody {
  readonly requestId: string;
  readonly ownerId: string;
  readonly uiSession: string;
}
export interface UiUpdateBody {
  readonly requestId: string;
  readonly ownerId: string;
  readonly uiSession: string;
  readonly json: string;
}
export interface UiRequestBody {
  readonly requestId: string;
  readonly ownerId: string;
  readonly uiSession: string;
  readonly operation: "status" | "detail" | "history" | "command" | "task" | "retry" | "pause" | "resume" | "stop";
  readonly message: string;
}
export interface TeamStatus {
  readonly worldId: string;
  readonly revision: number;
  readonly recoveryInvalid: boolean;
  readonly reason?: string;
  readonly ledger: unknown;
  readonly works: readonly unknown[];
  readonly bindings: readonly unknown[];
}

export type ProtocolVersion = typeof PROTOCOL;

export interface SessionContext {
  readonly protocol: ProtocolVersion;
  readonly worldId: string;
  readonly sessionEpoch: number;
}

export interface CapabilitySet {
  readonly events: boolean;
  readonly acknowledgements: boolean;
  readonly reconciliation: boolean;
  readonly maxFrameBytes: number;
  readonly [key: string]: boolean | number;
}

export interface HandshakeMessage extends SessionContext {
  readonly kind: "handshake";
  readonly requestId: string;
  readonly token: string;
  readonly client: "mod" | "bridge";
  readonly modVersion?:string;
  readonly backend?: ExecutionBackendDescriptor;
  readonly capabilities: CapabilitySet;
}

export interface HandshakeAck extends SessionContext {
  readonly kind: "handshake_ack";
  readonly requestId: string;
  readonly accepted: boolean;
  readonly serverCapabilities?: CapabilitySet;
  readonly controllerVersion?:string;
  readonly error?: WireError;
}

export interface RequestEnvelope<T = unknown> extends SessionContext {
  readonly kind: "request";
  readonly requestId: string;
  readonly op: string;
  readonly body: T;
  readonly intentId?: string;
  readonly actionId?: string;
  readonly bodyGeneration?: number;
}

export interface ResponseEnvelope<T = unknown> extends SessionContext {
  readonly kind: "response";
  readonly requestId: string;
  readonly ok: boolean;
  readonly body?: T;
  readonly error?: WireError;
}

export interface EventEnvelope<T = unknown> extends SessionContext {
  readonly kind: "event";
  readonly eventId: string;
  readonly event: string;
  readonly body: T;
}

export interface AckEnvelope extends SessionContext {
  readonly kind: "ack";
  readonly eventId: string;
  readonly accepted: boolean;
  readonly error?: WireError;
}

export type WireMessage =
  | HandshakeMessage
  | HandshakeAck
  | RequestEnvelope
  | ResponseEnvelope
  | EventEnvelope
  | AckEnvelope;

export interface WireError {
  readonly code: string;
  readonly message: string;
  readonly retryable?: boolean;
  readonly details?: unknown;
}

export interface ReconciliationRecord {
  readonly requestId: string;
  readonly worldId: string;
  readonly sessionEpoch: number;
  readonly intentId?: string;
  readonly actionId?: string;
  readonly status: "uncertain" | "completed" | "failed" | "cancelled";
  readonly reason: "disconnect" | "late_response" | "reconnect_check";
  readonly recordedAt: number;
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isSessionContext(value: unknown): value is SessionContext {
  if (!isObject(value)) return false;
  const epoch = value.sessionEpoch;
  return value.protocol === PROTOCOL && typeof value.worldId === "string" && value.worldId.length > 0 && typeof epoch === "number" && Number.isSafeInteger(epoch) && epoch >= 0;
}

export function assertWireMessage(value: unknown): asserts value is WireMessage {
  if (!isObject(value) || value.protocol !== PROTOCOL || typeof value.kind !== "string") throw new Error("invalid hearthcrew wire message");
  if (value.kind === "handshake") {
    if (typeof value.requestId !== "string" || value.requestId.length === 0 || typeof value.token !== "string" || value.token.length === 0 || (value.client !== "mod" && value.client !== "bridge") || !isSessionContext(value) || !isCapabilitySet(value.capabilities)) throw new Error("invalid handshake");
    if (value.backend !== undefined && !isExecutionBackend(value.backend)) throw new Error("invalid execution backend descriptor");
    return;
  }
  if (value.kind === "handshake_ack") {
    if (typeof value.requestId !== "string" || value.requestId.length === 0 || typeof value.accepted !== "boolean" || !isSessionContext(value) || (!value.accepted && !isWireError(value.error)) || (value.accepted && value.error !== undefined) || (value.error !== undefined && !isWireError(value.error)) || (value.serverCapabilities !== undefined && !isCapabilitySet(value.serverCapabilities))) throw new Error("invalid handshake acknowledgement");
    return;
  }
  if (!isSessionContext(value)) throw new Error("invalid session context");
  if (value.kind === "request" && (typeof value.requestId !== "string" || value.requestId.length === 0 || typeof value.op !== "string" || !WIRE_OPERATIONS.includes(value.op as WireOperation) || !("body" in value) || !validOptionalActionFields(value))) throw new Error("invalid request envelope");
  if (value.kind === "response" && (typeof value.requestId !== "string" || value.requestId.length === 0 || typeof value.ok !== "boolean" || (!value.ok && !isWireError(value.error)) || (value.ok && value.error !== undefined))) throw new Error("invalid response envelope");
  if (value.kind === "event" && (typeof value.eventId !== "string" || value.eventId.length === 0 || typeof value.event !== "string" || value.event.length === 0 || !("body" in value))) throw new Error("invalid event envelope");
  if (value.kind === "ack" && (typeof value.eventId !== "string" || value.eventId.length === 0 || typeof value.accepted !== "boolean" || (value.error !== undefined && !isWireError(value.error)))) throw new Error("invalid acknowledgement envelope");
  if (value.kind !== "request" && value.kind !== "response" && value.kind !== "event" && value.kind !== "ack") throw new Error("unknown wire envelope kind");
}

function validOptionalActionFields(value: Record<string, unknown>): boolean {
  return (value.intentId === undefined || (typeof value.intentId === "string" && value.intentId.length > 0)) &&
    (value.actionId === undefined || (typeof value.actionId === "string" && value.actionId.length > 0)) &&
    (value.bodyGeneration === undefined || (typeof value.bodyGeneration === "number" && Number.isSafeInteger(value.bodyGeneration) && value.bodyGeneration >= 0));
}

function isCapabilitySet(value: unknown): value is CapabilitySet {
  if (!isObject(value) || typeof value.events !== "boolean" || typeof value.acknowledgements !== "boolean" || typeof value.reconciliation !== "boolean" || typeof value.maxFrameBytes !== "number" || !Number.isSafeInteger(value.maxFrameBytes) || value.maxFrameBytes <= 0 || value.maxFrameBytes > MAX_FRAME_BYTES) return false;
  return Object.values(value).every((entry) => typeof entry === "boolean" || typeof entry === "number");
}

function isWireError(value: unknown): value is WireError {
  return isObject(value) && typeof value.code === "string" && value.code.length > 0 && typeof value.message === "string";
}
