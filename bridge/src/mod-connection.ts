import { EventEmitter } from "node:events";
import type { Socket } from "node:net";
import {
  assertWireMessage,
  type AckEnvelope,
  type CapabilitySet,
  type EventEnvelope,
  type HandshakeAck,
  type HandshakeMessage,
  type ReconciliationRecord,
  PROTOCOL,
  type RequestEnvelope,
  type ResponseEnvelope,
  type SessionContext,
  type WireError,
  type WireMessage,
  MAX_FRAME_BYTES,
} from "../../protocol/types.js";
import { encodeFrame, FrameDecoder } from "./framing.js";
import { executionCompatibility, type ExecutionBackendDescriptor } from "../../protocol/execution-backend.js";

export type ConnectionState = "awaiting_handshake" | "ready" | "closed";

export interface ModConnectionOptions {
  readonly token: string;
  readonly expectedWorldId?: string;
  readonly expectedSessionEpoch?: number;
  readonly maxFrameBytes?: number;
  readonly capabilities?: CapabilitySet;
  readonly controllerVersion?: string;
}

export interface RequestOptions {
  readonly requestId?: string;
  readonly intentId?: string;
  readonly actionId?: string;
  readonly bodyGeneration?: number;
  readonly timeoutMs?: number;
}

export interface ConnectionEvents {
  message: (message: WireMessage) => void;
  request: (request: RequestEnvelope) => void;
  event: (event: EventEnvelope) => void;
  disconnect: (reason: Error) => void;
}

export class BridgeDisconnectedError extends Error {
  constructor(message = "Mod connection disconnected; world writes require reconciliation") {
    super(message);
    this.name = "BridgeDisconnectedError";
  }
}

export class StaleSessionError extends Error {
  constructor(message = "message belongs to a stale or mismatched session") {
    super(message);
    this.name = "StaleSessionError";
  }
}

interface PendingRequest {
  readonly request: RequestEnvelope;
  readonly resolve: (value: unknown) => void;
  readonly reject: (reason: unknown) => void;
  readonly timer?: NodeJS.Timeout;
}

interface PendingEvent {
  readonly event: EventEnvelope;
  readonly resolve: (accepted: boolean) => void;
  readonly reject: (reason: unknown) => void;
  timer?: NodeJS.Timeout;
}

const defaultCapabilities: CapabilitySet = {
  events: true,
  acknowledgements: true,
  reconciliation: true,
  maxFrameBytes: 1024 * 1024,
};

function wireError(code: string, message: string, details?: unknown): WireError {
  return { code, message, ...(details === undefined ? {} : { details }) };
}

function id(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** One Mod socket. It owns no Minecraft state and never retries a world write. */
export class ModConnection extends EventEmitter {
  private peerBackend?: ExecutionBackendDescriptor;
  get executionBackend(): ExecutionBackendDescriptor | undefined { return this.peerBackend; }
  private readonly decoder: FrameDecoder;
  private readonly pendingRequests = new Map<string, PendingRequest>();
  private readonly pendingEvents = new Map<string, PendingEvent>();
  private readonly responseCache = new Map<string, ResponseEnvelope>();
  private readonly reconciliations: ReconciliationRecord[] = [];
  private state: ConnectionState = "awaiting_handshake";
  private readonly maxFrameBytes: number;
  private context?: SessionContext;
  private closeReason?: Error;
  private requestHandler?: (request: RequestEnvelope) => Promise<unknown> | unknown;
  private eventHandler?: (event: EventEnvelope) => Promise<boolean> | boolean;

  constructor(private readonly socket: Socket, private readonly options: ModConnectionOptions) {
    super();
    const maxFrameBytes = options.maxFrameBytes ?? MAX_FRAME_BYTES;
    if (!Number.isSafeInteger(maxFrameBytes) || maxFrameBytes <= 0 || maxFrameBytes > MAX_FRAME_BYTES) throw new Error(`maxFrameBytes must be between 1 and ${MAX_FRAME_BYTES}`);
    this.maxFrameBytes = maxFrameBytes;
    this.decoder = new FrameDecoder(maxFrameBytes);
    socket.on("data", (chunk: Buffer) => this.onData(chunk));
    socket.on("error", (error: Error) => this.close(error));
    socket.on("close", () => this.close(this.closeReason ?? new BridgeDisconnectedError()));
  }

  get connectionState(): ConnectionState { return this.state; }
  get sessionContext(): SessionContext | undefined { return this.context; }
  get reconciliationRecords(): readonly ReconciliationRecord[] { return this.reconciliations; }

  onRequest(handler: (request: RequestEnvelope) => Promise<unknown> | unknown): this {
    this.requestHandler = handler;
    return this;
  }

  onEvent(handler: (event: EventEnvelope) => Promise<boolean> | boolean): this {
    this.eventHandler = handler;
    return this;
  }

  request<T>(op: string, body: unknown, requestOptions: RequestOptions = {}): Promise<T> {
    if (this.state !== "ready" || !this.context) return Promise.reject(new BridgeDisconnectedError("Mod connection is not ready"));
    const requestId = requestOptions.requestId ?? id("req");
    if (this.pendingRequests.has(requestId)) return Promise.reject(new Error(`duplicate pending requestId: ${requestId}`));
    const request: RequestEnvelope = {
      ...this.context,
      kind: "request",
      requestId,
      op,
      body,
      ...(requestOptions.intentId === undefined ? {} : { intentId: requestOptions.intentId }),
      ...(requestOptions.actionId === undefined ? {} : { actionId: requestOptions.actionId }),
      ...(requestOptions.bodyGeneration === undefined ? {} : { bodyGeneration: requestOptions.bodyGeneration }),
    };
    return new Promise<T>((resolve, reject) => {
      const timer = requestOptions.timeoutMs === undefined ? undefined : setTimeout(() => {
        this.pendingRequests.delete(requestId);
        this.reconciliations.push(this.record(request, "uncertain", "reconnect_check"));
        reject(new BridgeDisconnectedError(`request ${requestId} timed out; reconcile before retry`));
      }, requestOptions.timeoutMs);
      this.pendingRequests.set(requestId, { request, resolve: resolve as (value: unknown) => void, reject, timer });
      try { this.write(request); } catch (error) {
        if (timer) clearTimeout(timer);
        this.pendingRequests.delete(requestId);
        reject(error);
      }
    });
  }

  emitEvent<T>(event: string, body: T, timeoutMs?: number): Promise<boolean> {
    if (this.state !== "ready" || !this.context) return Promise.reject(new BridgeDisconnectedError("Mod connection is not ready"));
    const message: EventEnvelope<T> = { ...this.context, kind: "event", eventId: id("evt"), event, body };
    return new Promise<boolean>((resolve, reject) => {
      const pending: PendingEvent = { event: message, resolve, reject };
      this.pendingEvents.set(message.eventId, pending);
      try { this.write(message); } catch (error) { this.pendingEvents.delete(message.eventId); reject(error); return; }
      if (timeoutMs !== undefined) pending.timer = setTimeout(() => {
        const pending = this.pendingEvents.get(message.eventId);
        if (!pending) return;
        this.pendingEvents.delete(message.eventId);
        this.reconciliations.push(this.record({ ...message, requestId: message.eventId } as unknown as RequestEnvelope, "uncertain", "disconnect"));
        pending.reject(new BridgeDisconnectedError(`event ${message.eventId} acknowledgement timed out`));
      }, timeoutMs);
    });
  }

  close(reason = new BridgeDisconnectedError()): void {
    if (this.state === "closed") return;
    this.closeReason = reason;
    this.state = "closed";
    for (const pending of this.pendingRequests.values()) {
      if (pending.timer) clearTimeout(pending.timer);
      this.reconciliations.push(this.record(pending.request, "uncertain", "disconnect"));
      pending.reject(reason);
    }
    for (const pending of this.pendingEvents.values()) {
      if (pending.timer) clearTimeout(pending.timer);
      this.reconciliations.push(this.record({ ...pending.event, requestId: pending.event.eventId } as unknown as RequestEnvelope, "uncertain", "disconnect"));
      pending.reject(reason);
    }
    this.pendingRequests.clear();
    this.pendingEvents.clear();
    this.emit("disconnect", reason);
    if (!this.socket.destroyed) this.socket.destroy();
  }

  private onData(chunk: Buffer): void {
    if (this.state === "closed") return;
    try {
      for (const frame of this.decoder.push(chunk)) this.onMessage(JSON.parse(frame.toString("utf8")) as unknown);
    } catch (error) {
      this.close(error instanceof Error ? error : new Error(String(error)));
    }
  }

  private onMessage(raw: unknown): void {
    try { assertWireMessage(raw); } catch (error) { this.close(error instanceof Error ? error : new Error(String(error))); return; }
    const message = raw as WireMessage;
    this.emit("message", message);
    if (message.kind === "handshake") { this.onHandshake(message); return; }
    if (message.kind === "handshake_ack") { this.onHandshakeAck(message); return; }
    if (!this.context || message.worldId !== this.context.worldId || message.sessionEpoch !== this.context.sessionEpoch) {
      if (message.kind === "request") this.sendResponse(message.requestId, false, undefined, wireError("STALE_SESSION", "request belongs to a different world/session"));
      else if (message.kind === "event") this.write({ ...this.contextForAck(message), kind: "ack", eventId: message.eventId, accepted: false, error: wireError("STALE_SESSION", "event belongs to a different world/session") });
      else if (message.kind === "response") {
        const pending = this.pendingRequests.get(message.requestId);
        if (pending) {
          this.pendingRequests.delete(message.requestId);
          if (pending.timer) clearTimeout(pending.timer);
          this.reconciliations.push(this.record(pending.request, "uncertain", "late_response"));
          pending.reject(new StaleSessionError());
        }
      }
      return;
    }
    if (message.kind === "request") { void this.onRequestMessage(message).catch(error => this.close(error instanceof Error ? error : new Error(String(error)))); return; }
    if (message.kind === "response") { this.onResponse(message); return; }
    if (message.kind === "event") { void this.onEventMessage(message).catch(error => this.close(error instanceof Error ? error : new Error(String(error)))); return; }
    if (message.kind === "ack") { this.onAck(message); return; }
  }

  private onHandshake(message: HandshakeMessage): void {
    if (this.state !== "awaiting_handshake") { this.close(new Error("duplicate handshake")); return; }
    const valid = message.token === this.options.token && (!this.options.expectedWorldId || message.worldId === this.options.expectedWorldId) && (this.options.expectedSessionEpoch === undefined || message.sessionEpoch === this.options.expectedSessionEpoch);
    if (!valid) {
      const ack: HandshakeAck = { protocol: PROTOCOL, worldId: message.worldId, sessionEpoch: message.sessionEpoch, kind: "handshake_ack", requestId: message.requestId, accepted: false, error: wireError("HANDSHAKE_REJECTED", "token, worldId, or sessionEpoch rejected") };
      this.write(ack);
      this.close(new Error("handshake rejected"));
      return;
    }
    const compatibility = executionCompatibility(message.capabilities.executionProtocol, message.backend,
      typeof this.options.capabilities?.executionProtocol === "number" ? this.options.capabilities.executionProtocol : 5);
    if (compatibility) {
      this.write({ protocol: PROTOCOL, worldId: message.worldId, sessionEpoch: message.sessionEpoch, kind: "handshake_ack",
        requestId: message.requestId, accepted: false, error: wireError("EXECUTION_BACKEND_INCOMPATIBLE", compatibility) });
      this.close(new Error(compatibility));
      return;
    }
    this.peerBackend = message.backend;
    this.context = { protocol: PROTOCOL, worldId: message.worldId, sessionEpoch: message.sessionEpoch };
    this.state = "ready";
    this.write({ ...this.context, kind: "handshake_ack", requestId: message.requestId, accepted: true, controllerVersion:this.options.controllerVersion??"0.3.2", serverCapabilities: this.options.capabilities ?? { ...defaultCapabilities, executionProtocol:5,unifiedPreparation:true,resourcePreparation:true,historySeparate:true,safeAccessBudget:true,nativePickupPost:true,sharedSequenceBudget:true, maxFrameBytes: this.maxFrameBytes } });
    this.emit("ready", this.context);
  }

  private onHandshakeAck(message: HandshakeAck): void {
    if (!message.accepted) { this.close(new Error(message.error?.message ?? "handshake rejected")); return; }
    this.context = { protocol: PROTOCOL, worldId: message.worldId, sessionEpoch: message.sessionEpoch };
    this.state = "ready";
    this.emit("ready", this.context);
  }

  private async onRequestMessage(request: RequestEnvelope): Promise<void> {
    const cached = this.responseCache.get(request.requestId);
    if (cached) { this.write(cached); return; }
    try {
      if (!this.requestHandler) throw new Error(`no handler registered for ${request.op}`);
      const body = await this.requestHandler(request);
      if (this.state === "closed") return;
      this.sendResponse(request.requestId, true, body);
    } catch (error) {
      if (this.state === "closed") return;
      this.sendResponse(request.requestId, false, undefined, wireError("REQUEST_FAILED", error instanceof Error ? error.message : String(error)));
    }
  }

  private onResponse(message: ResponseEnvelope): void {
    const pending = this.pendingRequests.get(message.requestId);
    if (!pending) return;
    this.pendingRequests.delete(message.requestId);
    if (pending.timer) clearTimeout(pending.timer);
    if (message.ok) pending.resolve(message.body);
    else pending.reject(new Error(message.error?.message ?? "request failed"));
  }

  private async onEventMessage(message: EventEnvelope): Promise<void> {
    let accepted = false;
    try { accepted = this.eventHandler ? await this.eventHandler(message) : false; } catch { accepted = false; }
    // The handler may await disk/game work while the peer disconnects. Never
    // acknowledge on a retired socket; journal/reconciliation owns any effects.
    if (this.state === "closed") return;
    this.write({ ...this.context, ...this.contextForAck(message), kind: "ack", eventId: message.eventId, accepted } as AckEnvelope);
    this.emit("event", message);
  }

  private onAck(message: AckEnvelope): void {
    const pending = this.pendingEvents.get(message.eventId);
    if (!pending) return;
    this.pendingEvents.delete(message.eventId);
    if (pending.timer) clearTimeout(pending.timer);
    pending.resolve(message.accepted);
  }

  private contextForAck(message: SessionContext): SessionContext {
    return { protocol: PROTOCOL, worldId: message.worldId, sessionEpoch: message.sessionEpoch };
  }

  private sendResponse(requestId: string, ok: boolean, body?: unknown, error?: WireError): void {
    if (!this.context) return;
    const response: ResponseEnvelope = { ...this.context, kind: "response", requestId, ok, ...(body === undefined ? {} : { body }), ...(error === undefined ? {} : { error }) };
    this.responseCache.set(requestId, response);
    this.write(response);
  }

  private write(message: unknown): void {
    if (this.state === "closed") throw new BridgeDisconnectedError();
    this.socket.write(encodeFrame(message, this.maxFrameBytes));
  }

  private record(request: RequestEnvelope, status: ReconciliationRecord["status"], reason: ReconciliationRecord["reason"]): ReconciliationRecord {
    return { requestId: request.requestId, worldId: request.worldId, sessionEpoch: request.sessionEpoch, ...(request.intentId === undefined ? {} : { intentId: request.intentId }), ...(request.actionId === undefined ? {} : { actionId: request.actionId }), status, reason, recordedAt: Date.now() };
  }
}
