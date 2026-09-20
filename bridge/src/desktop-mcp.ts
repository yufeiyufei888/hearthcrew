import type { Readable, Writable } from "node:stream";

export interface DesktopControl {
  status(): Promise<unknown>;
  command(message: string, botId?: string): Promise<unknown>;
  control(operation: "pause" | "resume" | "stop" | "auto_on" | "auto_off" | "standby", botId?: string): Promise<unknown>;
  events(after?: number, limit?: number): unknown;
}
const schema = (properties: object, required: string[] = []) => ({ type: "object", properties, required, additionalProperties: false });
const optionalBot = { botId: { type: "string", description: "status 返回的伙伴身份；省略时 command 选首个伙伴，control 操作全队。" } };
const SUPPORTED_MCP_VERSIONS = new Set(["2025-06-18", "2025-11-25"]);
export const DESKTOP_TOOLS = [
  { name: "status", description: "查看 HearthCrew 游戏、控制器、Codex 与角色状态，以及实际背包和动作。", inputSchema: schema({}), annotations: { readOnlyHint: true } },
  { name: "command", description: "给伙伴下达中文游戏任务。返回接受回执；通过 events/status 查询实际完成情况。", inputSchema: schema({ message: { type: "string", minLength: 1, maxLength: 2000 }, ...optionalBot }, ["message"]) },
  { name: "control", description: "暂停、恢复、急停或切换伙伴自主模式。auto_off 停止自主规划但保留玩家指令能力，standby 让指定伙伴暂时待命。", inputSchema: schema({ operation: { type: "string", enum: ["pause", "resume", "stop", "auto_on", "auto_off", "standby"] }, ...optionalBot }, ["operation"]) },
  { name: "events", description: "按游标读取已确认的动作与角色事件。", inputSchema: schema({ after: { type: "integer", minimum: 0 }, limit: { type: "integer", minimum: 1, maximum: 100 } }), annotations: { readOnlyHint: true } },
] as const;

/** MCP stdio subset: lifecycle, ping, tools/list and tools/call. No HTTP listener. */
export class DesktopMcp {
  private initialized = false;
  private ready = false;
  private pending = new Map<string | number, string>();
  private replies = new Map<string | number, { request: string; response: unknown }>();
  constructor(private readonly control: DesktopControl) {}
  async receive(raw: unknown): Promise<unknown | undefined> {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return this.error(null, -32600, "Invalid Request");
    const request = raw as { jsonrpc?: string; id?: unknown; method?: unknown; params?: unknown };
    const id = request.id;
    if (request.jsonrpc !== "2.0" || typeof request.method !== "string" || (id !== undefined && typeof id !== "string" && !(typeof id === "number" && Number.isSafeInteger(id)))) return this.error(null, -32600, "Invalid Request");
    if (id === undefined) {
      if (request.method === "notifications/initialized" && this.initialized) this.ready = true;
      return undefined;
    }
    const key = id as string | number; const fingerprint = JSON.stringify(raw);
    const cached = this.replies.get(key);
    if (cached) return cached.request === fingerprint ? cached.response : this.error(key, -32600, "Request ID already used");
    if (this.pending.has(key)) return this.error(key, -32600, "Request ID is pending");
    if (this.replies.size >= 8192) return this.error(key, -32000, "MCP session capacity reached; reconnect");
    this.pending.set(key, fingerprint);
    let response: unknown;
    try {
      let result: unknown;
      if (request.method === "initialize") {
        if (this.initialized) throw new Error("Session already initialized");
        const params = this.arguments(request.params, ["protocolVersion", "capabilities", "clientInfo"], false);
        if (typeof params.protocolVersion !== "string" || !SUPPORTED_MCP_VERSIONS.has(params.protocolVersion) || !params.clientInfo || !params.capabilities) throw new Error("Unsupported or invalid MCP initialize parameters");
        this.initialized = true;
        result = { protocolVersion: params.protocolVersion, capabilities: { tools: {} },
          serverInfo: { name: "hearthcrew", version: "0.3.2" }, instructions: "这是开发中的本地 Minecraft 伙伴控制器；接受不等于完成。" };
      } else if (request.method === "ping") result = {};
      else if (!this.ready) throw new Error("MCP initialization is incomplete");
      else if (request.method === "tools/list") result = { tools: DESKTOP_TOOLS };
      else if (request.method === "tools/call") {
        const params = this.arguments(request.params, ["name", "arguments", "_meta"]);
        const name = params.name;
        if (!DESKTOP_TOOLS.some(tool => tool.name === name)) throw new Error("Unknown tool");
        try {
          let value: unknown;
          if (name === "status") { this.arguments(params.arguments ?? {}, []); value = await this.control.status(); }
          else if (name === "events") {
            const args = this.arguments(params.arguments ?? {}, ["after", "limit"]);
            if (args.after !== undefined && (!Number.isSafeInteger(args.after) || (args.after as number) < 0)) throw new Error("after must be a non-negative integer");
            if (args.limit !== undefined && (!Number.isSafeInteger(args.limit) || (args.limit as number) < 1 || (args.limit as number) > 100)) throw new Error("limit must be an integer from 1 to 100");
            value = this.control.events(args.after as number | undefined, args.limit as number | undefined);
          } else {
            const args = this.arguments(params.arguments, name === "command" ? ["message", "botId"] : ["operation", "botId"]);
            if (args.botId !== undefined && (typeof args.botId !== "string" || !/^[0-9a-f-]{36}$/i.test(args.botId))) throw new Error("Invalid botId");
            if (name === "command") {
              if (typeof args.message !== "string" || !args.message.trim() || args.message.length > 2000) throw new Error("message must contain 1..2000 characters");
              value = await this.control.command(args.message, args.botId as string | undefined);
            } else {
              if (!["pause", "resume", "stop", "auto_on", "auto_off", "standby"].includes(String(args.operation))) throw new Error("Invalid control operation");
              value = await this.control.control(args.operation as "pause" | "resume" | "stop" | "auto_on" | "auto_off" | "standby", args.botId as string | undefined);
            }
          }
          const structuredContent = value && typeof value === "object" && !Array.isArray(value) ? value : { result: value };
          result = { content: [{ type: "text", text: JSON.stringify(structuredContent) }], structuredContent, isError: false };
        } catch (error) { result = { content: [{ type: "text", text: error instanceof Error ? error.message : String(error) }], isError: true }; }
      } else { response = this.error(key, -32601, "Method not found"); }
      response ??= { jsonrpc: "2.0", id: key, result };
    } catch (error) { response = this.error(key, -32602, error instanceof Error ? error.message : String(error)); }
    finally { this.pending.delete(key); }
    this.replies.set(key, { request: fingerprint, response }); return response;
  }
  private arguments(value: unknown, keys: string[], strict = true): Record<string, unknown> {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Object parameters required");
    const result = value as Record<string, unknown>;
    if (strict && Object.keys(result).some(key => !keys.includes(key))) throw new Error("Unexpected parameter");
    return result;
  }
  private error(id: unknown, code: number, message: string): unknown { return { jsonrpc: "2.0", id, error: { code, message } }; }
}

export function serveStdioMcp(server: DesktopMcp, input: Readable, output: Writable): () => void {
  let buffered = Buffer.alloc(0); let closed = false;
  const write = (value: unknown) => { if (!closed && value !== undefined) output.write(JSON.stringify(value) + "\n"); };
  const onData = (chunk: Buffer | string) => {
    buffered = Buffer.concat([buffered, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)]);
    for (;;) {
      const end = buffered.indexOf(10);
      if (end < 0) break;
      if (end > 1_048_576) { close(); input.destroy(new Error("MCP frame exceeds 1 MiB")); return; }
      const frame = buffered.subarray(0, end); buffered = buffered.subarray(end + 1);
      if (!frame.length) continue;
      try {
        const raw = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(frame));
        void server.receive(raw).then(write).catch(() => write({ jsonrpc: "2.0", id: null, error: { code: -32603, message: "Internal error" } }));
      } catch { write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }); }
    }
    if (buffered.length > 1_048_576) { close(); input.destroy(new Error("MCP frame exceeds 1 MiB")); }
  };
  const close = () => { closed = true; input.off("data", onData); buffered = Buffer.alloc(0); };
  input.on("data", onData); return close;
}
