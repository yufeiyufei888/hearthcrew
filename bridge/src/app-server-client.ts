import type {ExecutionBackendDescriptor} from "../../protocol/execution-backend.js";
import {REPORT_RULES, REPORT_PURPOSES, STAGE_PURPOSES} from "./reporting-policy.js";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface, type Interface } from "node:readline";
import { join } from "node:path";
import { prepareGameModelCatalog } from "./game-model-catalog.js";
import { ACTION_KINDS, ACTION_PARAMETERS_SCHEMA } from "./action-parameters.js";
import { assertGameModelCatalog, buildIsolatedEnvironment, detectCodexCliVersion, ensureDedicatedCodexConfig, isolatedAppServerArgs, PINNED_CODEX_CLI_VERSION, validateRuntimeConfig, type BridgeRuntimeConfig } from "./runtime-config.js";

export interface AgentProfile {
  readonly id: "coordinator" | "gatherer" | "builder";
  readonly displayName: string;
  readonly model: "gpt-5.6-luna";
  readonly reasoningEffort: "high";
  readonly serviceTierRequested?: "fast";
  readonly developerInstructions: string;
}

export const AGENT_PROFILES: readonly AgentProfile[] = [
  { id: "coordinator", displayName: "炉火队长", model: "gpt-5.6-luna", reasoningEffort: "high", serviceTierRequested: "fast", developerInstructions: "你是负责协调也亲自工作的生存队长。所有游戏操作必须通过 hearthcrew 工具。" },
  { id: "gatherer", displayName: "寻路采集者", model: "gpt-5.6-luna", reasoningEffort: "high", serviceTierRequested: "fast", developerInstructions: "你是谨慎的探索采集者。报告可核验的物品、位置和风险，所有游戏操作必须通过 hearthcrew 工具。" },
  { id: "builder", displayName: "营地建设者", model: "gpt-5.6-luna", reasoningEffort: "high", serviceTierRequested: "fast", developerInstructions: "你是重视结构和补给的建设者。先观察再施工，所有游戏操作必须通过 hearthcrew 工具。" },
];

/** Replaces Codex's coding-agent base prompt so a HearthCrew turn has game context only. */
export const HEARTHCREW_BASE_INSTRUCTIONS = `你是 HearthCrew 的 Minecraft 生存伙伴。你首先是独立游玩的队友：自己选择有意义的生存目标并完成当前任务，同时兼顾合作与救助。不要把跟随玩家、等玩家下令或替玩家干活作为默认目标。

本会话的操作能力只来自 HearthCrew 动态游戏工具。没有玩家新命令时，你仍要自主观察、判断和推进自己的长期生存目标；具体路线、分工和步骤由你与队友根据真实状态决定。需要执行游戏行为时，使用代码模式调用已注册的 HearthCrew 工具；不要调用、模拟或请求文件、命令行、浏览器、联网搜索、MCP、插件、图像或桌面工具。不要把工具自报或计划当成真实结果。

能力限制：团队propose_work只支持BUILD/MINE/PLACE/TRANSFER/CRAFT/MOVE/WAIT/SELECT/EAT；GATHER只能由本人act执行，不能把委派拒绝说成成功。观察到原木不等于已通过采木验证，附近矿石可能隐藏在地面以下，不代表你在地下。收到失败actionResults后改变策略或报告条件，不重复无变化的失败动作。

你是三个独立 Luna 会话之一，只控制自己的身体；队长同样亲自工作。主动用 share 讨论真实发现、分工和求助，回应队友，允许有实际经历依据的简短闲聊，不重复套话。每轮最多两条公开消息、每条200字。communicationOnly 回合可回应消息但不能 act 或改变正在执行的工作；通过 discuss_work 讨论后等动作结束。玩家提问不等于命令，只有明确任务才用 owner_task 引用 inbox 中的玩家消息ID；普通请求等手头任务完成再接，明确“立即/先停下/改做”才中断改派。先 observe，再根据真实世界状态提出工作或提交一个 act。observe 可同时查询目标、配方及容器，扫描中不是没有资源；等待scan_ready事件，不轮询。采石/采矿用MINE(position,count=所需方块数,resource可选)一次提交一批，执行器会持续完成。失败后先看harvestEvidence：已破坏但pendingDrops未回收应PICKUP或另行开路，禁止重挖空气。阶段完成条件是对象，例如completion={"kind":"inventory","resource":"minecraft:cobblestone","count":8}；不是文本。材料达到目标后尝试阶段complete，已知失败尝试由控制器独立核对，未知效果仍需恢复。MINE/COLLECT_RESOURCE/PICKUP 默认 accessBudget=16，0表示禁止额外开路；SEQUENCE所有步骤共享根预算。先核对回收路径，不把矿石线索当可采条件。困难不自动换新阶段逃避总任务失败，换真正不同路线或准备工作。GATHER 只用于经过自然树与建筑保护检查的树木，支持主世界树种和分段树枝采掘，resource 使用 capabilities 中支持的主世界原木种类，count 使用 1..64（通常每次 2..4）；本地执行器会逐 tick 靠近、采掘并等待真实拾取，无法确认目标树形或资源时应拒绝。BUILD 一次提交一个 1..16 步的方块蓝图，由本地执行器持续逐步完成；已存在的方块不计入实际新建贡献。接受只表示仲裁器接收意图，不表示动作已经完成。收到 act 回执后立即结束本回合，让本地执行器持续运行；动作结果事件会触发下一回合，届时依据位置、维度、背包或方块状态确认结果。GATHER 回执中的 requested/collected 仅采信服务端真实字段，失败或部分完成必须如实报告。不要在当前回合等待、轮询或连续提交动作。本地 localSafety 自卫/撤退会独立运行，不要用新动作打断它。遇到危险先自救；保护队友时判断自己的血量、装备和距离，不盲目送死。遇到失败说明所缺条件并改变策略。保持自己的角色目标和记忆，同时尊重玩家命令、团队任务、资源预留和保护区域。每个目标必须通过 propose_work 的 operation=goal_state 明确 continue、complete、blocked 或 standby；目标完成前确认实际动作和团队任务已经结束。困难时使用 blocked，不能自行让全局自主模式 standby；只有玩家明确要求完成后待命才可 standby。SELECT 必须 parameters.slot，PLACE 可传 resource 指定背包中方块；查阅 capabilities.actions 的每种动作示例，不能猜字段。资源列表不等于地形全貌；陆地施工不依赖岸边候选。先propose_work(operation=stage,stageId,scope,summary,completion,state=continue)登记一段有意义的任务，再连续推进同一阶段。不要默认每块一个阶段。completion使用inventory(resource,count)、blocks(steps)或position(position,dimension)；结束要真实核验。多步BUILD按1..16步批次执行，同一阶段可以多批。每块进展留在内部回执，不必share；阶段结束不要求公开汇报；仅影响其他人的工作或回答玩家时才按communicationRules交流。回合结束文字不会公开。矿物不可达时用observe.targets核对工具、地形，再考虑EXCAVATE(position,count默认32最多64)开安全通路；开路不等于采到矿物。重生后先使用当前新身体快照重新评估，不继承旧库存假设。新世界三人空背包开局，不能使用旧世界坐标或记忆。${REPORT_RULES} 所有可用动作、字段、限制以 capabilities.actions 单一能力表为准。MINE、EXCAVATE、CRAFT、COLLECT_RESOURCE 新请求默认允许授权内自动准备；只剩残镐不代表工具准备完成。先查自身配方与工位，存在可执行准备步骤时直接执行原任务，不请求队友送镐。materials 等待必须包含 resource、count 及 peerId 或 taskId。observe.recipes 按输出物品查询真实配方和工位，再用返回的配方 ID 合成。加工用 PROCESS 投料、observe.containers 查询、COLLECT_PROCESS 取真实产物；等待期间可做其他工作，不反复询问同一工位。没有附近木材或食物时先看 farHints、实体与探索历史，选择未尝试方向用 EXPLORE 分段主动寻找；未加载不等于无资源。普通移动只走陆地；大水域由你根据实际材料决定绕行、SWIM 或 CRAFT 船后 LAUNCH_BOAT、BOARD_BOAT、SAIL、DISEMBARK。SAIL 使用水面航点，不把岸上方块当作驾驶终点。入水后保持向已确定安全岸边推进，不反复改变目标。查询新设施和工具的真实能力，而不是空等队友给出逐格操作。`;

export interface GameToolSpec {
  readonly type: "function";
  readonly name: "observe" | "propose_work" | "act" | "share" | "remember";
  readonly description: string;
  readonly inputSchema: Record<string, unknown>;
}

export const GAME_TOOLS: readonly GameToolSpec[] = [
  { type: "function", name: "observe", description: "读取附近的游戏状态；不读取世界种子或全图。", inputSchema: { type: "object", properties: { teamInventory:{type:"object",required:["companion"],properties:{companion:{type:"string",description:"另一位伙伴名字或botId"},items:{type:"array",maxItems:16,items:{type:"string",pattern:"^[-a-z0-9_.]+:[-a-z0-9_./]+$"}}},additionalProperties:false}, contextRef:{type:"string",minLength:64,maxLength:64,description:"本回合paged证据的reference，只能读取本世界本角色资料"},cursor:{type:"integer",minimum:0}, recipeCount:{type:"integer",minimum:1,maximum:64,description:"配方检查的制作次数，仅与recipes一起使用"}, recipes: { type:"array",maxItems:16,items:{type:"string",pattern:"^[a-z0-9_.-]+:[a-z0-9_./-]+$"}}, containers:{type:"array",maxItems:16,items:(ACTION_PARAMETERS_SCHEMA.properties as Record<string,unknown>).position}, radius: { type: "integer", minimum: 1, maximum: 32 }, targets: { type: "array", maxItems: 32, items: (ACTION_PARAMETERS_SCHEMA.properties as Record<string,unknown>).position } }, additionalProperties: false } },
  { type: "function", name: "propose_work", description: "阶段受阻使用operation=stage,state=blocked并提供reason和condition；这会保留总目标并安排一次替代方案评估。不要因受阻关闭自主。使用operation=cooperate提出协作（category/recipient/summary/kind/parameters），收到proposalId后用decision=accept或reject回应；接受后在安全边界由执行器处理，聊天承诺不算完成。或用operation=goal_state更新目标。团队任务使用 taskId/recipient/kind/summary；目标状态使用 state 和可选 reason/wakeConditions。", inputSchema: { type: "object", properties: { stagePurpose:{type:"string",enum:STAGE_PURPOSES}, condition:{type:"object",properties:{kind:{type:"string",enum:["materials","path","processing","teammate","danger","model_recovery","player_standby","unknown_effect"]},resource:{type:"string"},count:{type:"integer",minimum:1,maximum:2304},position:(ACTION_PARAMETERS_SCHEMA.properties as Record<string,unknown>).position,peerId:{type:"string"},taskId:{type:"string"}},required:["kind"],allOf:[{if:{properties:{kind:{const:"materials"}},required:["kind"]},then:{required:["resource","count"],anyOf:[{required:["peerId"]},{required:["taskId"]}]}}],additionalProperties:false},proposalId:{type:"string",maxLength:128},decision:{type:"string",enum:["accept","reject"]},category:{type:"string",enum:["yield","transfer","materials","assignment"]},stageId: {type:"string",maxLength:80}, scope: {type:"string",maxLength:1000}, completion: {type:"object", properties:{ kind:{type:"string",enum:["inventory","blocks","position"]}, resource:{type:"string"}, count:{type:"integer",minimum:1}, steps:{...((ACTION_PARAMETERS_SCHEMA.properties as Record<string,unknown>).steps as Record<string,unknown>),maxItems:256}, position:(ACTION_PARAMETERS_SCHEMA.properties as Record<string,unknown>).position, dimension:{type:"string"}}, required:["kind"],anyOf:[{properties:{kind:{const:"inventory"}},required:["kind","resource","count"]},{properties:{kind:{const:"position"}},required:["kind","position","dimension"]},{properties:{kind:{const:"blocks"}},required:["kind","steps"]}],additionalProperties:false}, operation: { type: "string", enum: ["goal_state", "owner_task", "discuss_work", "stage", "cooperate"] }, messageId: { type: "string", minLength: 1, maxLength: 128 }, replyTo: { type: "string", maxLength: 128 }, state: { type: "string", enum: ["continue", "complete", "blocked", "standby"] }, reason: { type: "string", minLength: 1, maxLength: 1000 }, wakeConditions: { type: "array", items: { type: "string", minLength: 1, maxLength: 200 }, maxItems: 8 }, taskId: { type: "string", minLength: 1, maxLength: 79 }, recipient: { type: "string", minLength: 1, maxLength: 128 }, intentId: { type: "string", minLength: 1, maxLength: 128 }, kind: { type: "string", enum: ACTION_KINDS }, parameters: ACTION_PARAMETERS_SCHEMA, title: { type: "string", minLength: 1, maxLength: 48 }, summary: { type: "string", minLength: 1, maxLength: 2000 } }, anyOf: [{required:["operation","proposalId","decision"]},{ required: ["taskId", "recipient", "kind", "summary"] }, { required: ["operation", "state"] }, { required: ["operation", "messageId"] }, { required: ["operation", "summary"] }], additionalProperties: false } },
  { type: "function", name: "act", description: "提交当前任务的一个游戏动作。执行团队任务时只传 taskId（使用 propose_work 返回的完整 taskId），服务端保存的任务定义是唯一动作来源；普通动作传 kind 和 parameters；actionId 可省略，由控制器按工具调用身份生成。COLLECT_RESOURCE 按实际新增物品数量连续执行；SEQUENCE 串联最多8个已确定准备步骤；GATHER 按能力表验证自然树；回执的 requested/collected 必须以服务端真实结果为准。BUILD 的 parameters.steps 是一次 1..16 步的有序方块蓝图，由本地执行器持续完成，已存在方块不计入新建贡献。接受后立即结束本回合，实际完成由游戏事件通知。", inputSchema: { type: "object", anyOf: [{ required: ["taskId"] }, { required: ["kind"] }], properties: { taskId: { type: "string", minLength: 1, maxLength: 128 }, actionId: { type: "string", minLength: 1, maxLength: 80 }, kind: { type: "string", enum: ACTION_KINDS, description: "普通动作类型。MINE 会自动接近目标方块；GATHER 只采集已识别的已验证的天然树段，使用 resource 与 count；BUILD 使用 parameters.steps 提交多块蓝图，无需拆成多个 act。" }, parameters: ACTION_PARAMETERS_SCHEMA }, additionalProperties: false } },
  { type: "function", name: "share", description: "按协作价值申请发言，缺省progress只记内部进度。协作引用proposalId，回答玩家引用replyTo，阶段结果引用stageId，发现/闲聊引用真实完成动作resultRef。控制器核验、定向和去重；public:false是正常静默，继续工作，不重复措辞。建议80字以内，每回合最多2条，每条200字。", inputSchema: { type: "object", required: ["message"], properties: { proposalId:{type:"string",maxLength:128},resultRef:{type:"string",maxLength:128}, purpose:{type:"string",enum:REPORT_PURPOSES}, stageId:{type:"string",maxLength:80}, recipient: { type: "string", maxLength: 128 }, message: { type: "string", minLength: 1, maxLength: 200 }, messageId: { type: "string", maxLength: 128 }, replyTo: { type: "string", maxLength: 128 } }, additionalProperties: false } },
  { type: "function", name: "remember", description: "写入角色自己的长期记忆；不写入认证或私人凭据。", inputSchema: { type: "object", required: ["entry"], properties: { entry: { type: "string", maxLength: 2000 } }, additionalProperties: false } },
];

export const BACKEND_BASE_INSTRUCTIONS = `你是HearthCrew中一个独立的Minecraft玩家伙伴，由自己的Luna会话控制。只使用observe/propose_work/act/share/remember五个游戏工具，不使用文件、网络或控制台。你的目标和分工由你决定，能力与参数以当前服务器核验的capabilities.actions为准，不能沿用旧版动作清单。
先核对真实身体、背包、环境、工位和当前任务，选择可核验的目标及阶段。在授权范围内把常规工具和材料准备交给持续执行器，避免逐块模型往返。接受动作后结束本回合；以实际游戏结果继续规划，不能把受理、到达节点、聊天承诺或实体消失当作任务完成。未知数据保持未知，不生成物品、不传送脱困、不破坏受保护建筑。
保持自己的任务与记忆，普通玩家请求排队，明确紧急改派才抢占。队友建议保持团队来源。交流回合不能提交身体动作或消费工作结果。只在分工、依赖变化、实际需要帮助、公共成果或回答玩家时公开交流；普通准备安静执行，允许少量真实经历闲聊。暂停、急停、待命、死亡及身体代次变化优先。受阻时在原目标内检查准备和其他候选，条件仍不足则登记明确等待，不反复重试或无依据求助。详细规则使用本回合当前状态与communicationRules。`;

export function gameToolsForBackend(tools:readonly GameToolSpec[], backend?:ExecutionBackendDescriptor):readonly GameToolSpec[] {
  if(!backend)return tools;
  if(!ACTION_KINDS.some(kind=>backend.actions.includes(kind)))throw new Error("BACKEND_NO_SUPPORTED_ACTIONS");
  return tools.map(tool=>{
    if(tool.name!=="act")return tool;
    const spec=structuredClone(tool);const properties=spec.inputSchema.properties as Record<string,Record<string,unknown>>;
    properties.kind={type:"string",enum:ACTION_KINDS.filter(kind=>backend.actions.includes(kind)),description:"本身体后端实际接通的动作；字段与限制查询capabilities.actions。"};
    if(backend.id==="numen") {delete properties.taskId;delete spec.inputSchema.anyOf;spec.inputSchema.required=["kind"];Object.assign(properties,structuredClone(ACTION_PARAMETERS_SCHEMA.properties));}
    if(backend.id==="numen")spec.inputSchema.allOf=[{if:{properties:{kind:{const:"EXCAVATE"}},required:["kind"]},then:{not:{required:["preparation"]},properties:{parameters:{not:{required:["preparation"]}}}}},{if:{properties:{kind:{const:"COLLECT_RESOURCE"}},required:["kind"]},then:{not:{required:["position"]},properties:{parameters:{not:{required:["position"]}}}}}];
    return {...spec,description:'提交一个已支持动作，推荐格式为 {kind:"COLLECT_RESOURCE",parameters:{resource:"minecraft:coal",count:16}}。也兼容将动作字段直接放顶层，但不能同时传parameters和顶层动作字段。准备和批量执行由唯一执行器推进；受理后结束回合。实际资源以观察为准，示例不代表环境中存在。actionId可省略，由控制器生成并去重。'};
  });
}

export function assertGameOnlyTools(tools: readonly GameToolSpec[]): void {
  const allowed = new Set(GAME_TOOLS.map((tool) => tool.name));
  for (const tool of tools) {
    if (tool.type !== "function" || !allowed.has(tool.name) || typeof tool.description !== "string" || typeof tool.inputSchema !== "object" || tool.inputSchema === null) throw new Error(`tool ${tool.name} is outside the HearthCrew game-only capability set`);
  }
}

interface RpcRequest { readonly jsonrpc: "2.0"; readonly id: string | number; readonly method: string; readonly params?: unknown; }
interface RpcResponse { readonly jsonrpc: "2.0"; readonly id: string | number; readonly result?: unknown; readonly error?: { code: number; message: string; data?: unknown }; }
interface RpcNotification { readonly jsonrpc: "2.0"; readonly method: string; readonly params?: unknown; }
type RpcMessage = RpcRequest | RpcResponse | RpcNotification;

async function waitForChildExit(child: ChildProcessWithoutNullStreams, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return true;
  return await new Promise<boolean>((resolve) => {
    let settled = false;
    const finish = (exited: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.removeListener("exit", onExit);
      resolve(exited);
    };
    const onExit = () => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    child.once("exit", onExit);
  });
}

async function killOwnedProcessTree(child: ChildProcessWithoutNullStreams): Promise<void> {
  if (child.pid === undefined || child.pid < 1) {
    if (!child.killed) child.kill();
    return;
  }
  if (process.platform === "win32") {
    await new Promise<void>((resolve) => {
      let settled = false;
      const finish = () => { if (settled) return; settled = true; clearTimeout(timer); resolve(); };
      const killer = spawn("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
      const timer = setTimeout(finish, 2_000);
      killer.once("error", finish);
      killer.once("close", finish);
    });
  } else if (!child.killed) child.kill("SIGTERM");
  if (!(await waitForChildExit(child, 1_000))) child.kill("SIGKILL");
}

export interface AppServerClientOptions {
  readonly runtime: BridgeRuntimeConfig;
  /** Test diagnostics only: expose raw tool items; product sessions keep this off. */
  readonly diagnosticRawEvents?: boolean;
  readonly profiles?: readonly AgentProfile[];
  readonly gameTools?: readonly GameToolSpec[];
  readonly spawnProcess?: (command: string, args: readonly string[], options: { cwd: string; env: NodeJS.ProcessEnv; stdio: ["pipe", "pipe", "pipe"]; windowsHide?: boolean }) => ChildProcessWithoutNullStreams;
  readonly requestTimeoutMs?: number;
  /** Refuse a silent CLI upgrade or downgrade; defaults to the verified wire build. */
  readonly detectVersion?: () => string;
  readonly expectedCliVersion?: string;
  readonly dynamicToolHandler?: (params: unknown) => Promise<unknown> | unknown;
}

interface PendingRpc { readonly resolve: (value: unknown) => void; readonly reject: (reason: unknown) => void; readonly timer?: NodeJS.Timeout; }
interface TurnCompletion { readonly resolve: (value: unknown) => void; readonly reject: (reason: unknown) => void; }

export interface FastServiceTierVerification {
  readonly verified: boolean;
  readonly requested: "fast";
  readonly echoed?: string | null;
  readonly reason?: string;
}

interface ActiveTurn {
  readonly turnId: string;
  readonly completion: Promise<unknown>;
  /** Controller-owned physical body identity, if this turn is bound to one. */
  readonly bodyGeneration?: number;
  /** Local model-turn sequence; never compared with a physical body generation. */
  readonly modelTurnGeneration: number;
}

const MAX_EARLY_TOOL_CALLS_PER_TURN = 32;
/** Codex 0.153.3 accepts the public request alias "fast" and echoes its wire tier as "priority". */
const VERIFIED_FAST_SERVICE_TIER_ECHOES = new Set(["fast", "priority"]);

export interface StartedThread {
  readonly profile: AgentProfile;
  readonly threadId: string;
  readonly model: string;
  readonly reasoningEffort?: string | null;
  readonly effectiveServiceTier?: string | null;
}

export interface TurnHandle {
  readonly threadId: string;
  readonly turnId: string;
  readonly completion: Promise<unknown>;
  interrupt(): Promise<unknown>;
}

/** Minimal stdio JSON-RPC App Server client. It contains no game loop and never touches global Codex config. */
export class AppServerClient extends EventEmitter {
  private child?: ChildProcessWithoutNullStreams;
  private lines?: Interface;
  private nextRpcId = 1;
  private readonly pending = new Map<string | number, PendingRpc>();
  private readonly turns = new Map<string, ActiveTurn>();
  private readonly startingTurns = new Set<string>();
  private readonly modelTurnGenerations = new Map<string, number>();
  private readonly modelTurnGenerationCounters = new Map<string, number>();
  private readonly completions = new Map<string, TurnCompletion>();
  private readonly completedBeforeHandle = new Map<string, unknown>();
  private readonly earlyToolCalls = new Map<string, RpcRequest[]>();
  private readonly interruptedTurns = new Set<string>();
  private readonly profiles: readonly AgentProfile[];
  private readonly tools: readonly GameToolSpec[];
  private config: BridgeRuntimeConfig;
  private initialized = false;
  private closed = false;
  private failedError?: Error;
  private observedCliVersion = "unknown";
  private fastServiceTierVerified: boolean;
  private modelCatalogSha256?: string;

  constructor(private readonly options: AppServerClientOptions) {
    super();
    this.config = validateRuntimeConfig(options.runtime);
    this.profiles = options.profiles ?? AGENT_PROFILES;
    this.tools = options.gameTools ?? GAME_TOOLS;
    this.fastServiceTierVerified = false;
    assertGameOnlyTools(this.tools);
    if (this.profiles.length < 1 || this.profiles.length > 3) throw new Error("HearthCrew requires one to three agent profiles");
    if (new Set(this.profiles.map((profile) => profile.id)).size !== this.profiles.length) throw new Error("HearthCrew profile ids must be unique");
  }

  get isInitialized(): boolean { return this.initialized; }
  get cliVersion(): string { return this.observedCliVersion; }

  async start(): Promise<unknown> {
    if (this.initialized) return undefined;
    await ensureDedicatedCodexConfig(this.config);
    this.observedCliVersion = this.options.detectVersion ? this.options.detectVersion() : detectCodexCliVersion(this.config.codexCommand ?? "codex", this.config);
    const expectedCliVersion = this.options.expectedCliVersion ?? PINNED_CODEX_CLI_VERSION;
    if (this.observedCliVersion !== expectedCliVersion) throw new Error(`unsupported Codex CLI version ${this.observedCliVersion}; HearthCrew requires ${expectedCliVersion}`);
    if (this.config.modelCatalogJson === undefined) {
      const prepared = await prepareGameModelCatalog({ command: this.config.codexCommand ?? "codex", env: buildIsolatedEnvironment(this.config), workspace: this.config.workspace, outputDirectory: join(this.config.codexHome, "model-catalog") });
      this.config = { ...this.config, modelCatalogJson: prepared.catalogPath };
      this.modelCatalogSha256 = prepared.sha256;
      this.emit("modelCatalogPrepared", prepared);
    } else this.modelCatalogSha256 = await assertGameModelCatalog(this.config.modelCatalogJson, this.config.codexHome);
    const spawnProcess = this.options.spawnProcess ?? ((command, args, spawnOptions) => spawn(command, [...args], spawnOptions));
    try {
      this.child = spawnProcess(this.config.codexCommand ?? "codex", isolatedAppServerArgs(this.config), { cwd: this.config.workspace, env: buildIsolatedEnvironment(this.config), stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
      this.lines = createInterface({ input: this.child.stdout });
      this.lines.on("line", (line) => this.onLine(line));
      this.child.stderr.on("data", (chunk: Buffer) => this.emit("stderr", chunk.toString("utf8")));
      this.child.once("error", (error) => this.failAll(error));
      this.child.once("exit", (code, signal) => this.failAll(new Error(`Codex App Server exited (${code ?? "signal " + signal})`)));
      const result = await this.request("initialize", { clientInfo: { name: "hearthcrew-bridge", title: "HearthCrew Bridge", version: this.observedCliVersion }, capabilities: { experimentalApi: true, extensions: null } });
      this.notify("initialized");
      this.initialized = true;
      return result;
    } catch (error) {
      const failure = error instanceof Error ? error : new Error(String(error));
      this.failAll(failure);
      const child = this.child;
      this.child = undefined;
      if (child) await killOwnedProcessTree(child);
      throw failure;
    }
  }

  async stop(): Promise<void> {
    if (this.closed && !this.child) return;
    this.closed = true;
    this.lines?.close();
    this.failAll(new Error("App Server stopped"));
    const child = this.child;
    this.child = undefined;
    if (!child) return;
    try { child.stdin.end(); } catch { /* process may already be closing */ }
    if (!(await waitForChildExit(child, 1_500))) await killOwnedProcessTree(child);
  }

  async createThread(profileId: AgentProfile["id"], backend?:ExecutionBackendDescriptor): Promise<StartedThread> {
    if (!this.initialized) throw new Error("App Server is not initialized");
    const profile = this.profiles.find((candidate) => candidate.id === profileId);
    if (!profile) throw new Error(`unknown profile: ${profileId}`);
    const result = await this.request("thread/start", { model: profile.model, baseInstructions: backend ? BACKEND_BASE_INSTRUCTIONS : HEARTHCREW_BASE_INSTRUCTIONS, developerInstructions: profile.developerInstructions, cwd: this.config.workspace, ephemeral: true, dynamicTools: gameToolsForBackend(this.tools,backend), config: { model_reasoning_effort: profile.reasoningEffort }, serviceTier: this.fastServiceTierVerified ? profile.serviceTierRequested : undefined, ...(this.options.diagnosticRawEvents ? { experimentalRawEvents: true } : {}) });
    const response = result as { thread?: { id?: string }; model?: string; reasoningEffort?: string | null; serviceTier?: string | null };
    const threadId = response.thread?.id;
    if (!threadId) throw new Error("thread/start response did not include thread.id");
    if (typeof response.model !== "string" || response.model.length === 0) throw new Error("thread/start response did not confirm the effective model");
    const actualModel = response.model;
    if (actualModel !== profile.model) throw new Error(`thread/start returned unexpected model: ${actualModel}`);
    if (response.reasoningEffort !== profile.reasoningEffort) throw new Error(`thread/start did not confirm reasoning effort ${profile.reasoningEffort}`);
    if (this.fastServiceTierVerified && (!response.serviceTier || !VERIFIED_FAST_SERVICE_TIER_ECHOES.has(response.serviceTier))) throw new Error(`thread/start did not confirm a verified fast service tier (requested ${profile.serviceTierRequested}, echoed ${response.serviceTier ?? "null"})`);
    return { profile, threadId, model: actualModel, reasoningEffort: response.reasoningEffort, effectiveServiceTier: response.serviceTier };
  }

  async startTurn(thread: StartedThread | { threadId: string }, input: string | readonly Record<string, unknown>[], profile?: AgentProfile, bodyGeneration?: number): Promise<TurnHandle> {
    const threadId = thread.threadId;
    if (this.turns.has(threadId) || this.startingTurns.has(threadId)) throw new Error(`thread ${threadId} already has an active or starting turn`);
    this.startingTurns.add(threadId);
    const selected = profile ?? (thread as StartedThread).profile;
    if (!selected) { this.startingTurns.delete(threadId); throw new Error("startTurn requires a configured agent profile"); }
    const configured = this.profiles.find((candidate) => candidate.id === selected.id);
    if (!configured || configured.model !== selected.model || configured.reasoningEffort !== selected.reasoningEffort) { this.startingTurns.delete(threadId); throw new Error("startTurn profile is not one of the configured HearthCrew profiles"); }
    try {
      const result = await this.request("turn/start", { threadId, input: typeof input === "string" ? [{ type: "text", text: input }] : input, model: selected?.model, effort: selected?.reasoningEffort, serviceTierForTurn: this.fastServiceTierVerified ? selected?.serviceTierRequested : undefined });
      const turnId = (result as { turn?: { id?: string } }).turn?.id;
      if (!turnId) throw new Error("turn/start response did not include turn.id");
      const key = this.turnKey(threadId, turnId);
      let resolve!: (value: unknown) => void; let reject!: (reason: unknown) => void;
      const completion = new Promise<unknown>((res, rej) => { resolve = res; reject = rej; });
      this.completions.set(key, { resolve, reject });
      const modelTurnGeneration = (this.modelTurnGenerationCounters.get(threadId) ?? 0) + 1;
      this.modelTurnGenerationCounters.set(threadId, modelTurnGeneration);
      this.startingTurns.delete(threadId);
      this.turns.set(threadId, { turnId, completion, bodyGeneration, modelTurnGeneration });
      const early = this.completedBeforeHandle.get(key);
      if (early !== undefined) {
        this.completedBeforeHandle.delete(key);
        this.completions.delete(key);
        this.turns.delete(threadId);
        this.discardEarlyToolCalls(threadId, key, "turn completed before it became active");
        this.discardCompletedBeforeHandle(threadId, undefined);
        const earlyError = this.turnFailure(early);
        if (earlyError) reject(earlyError); else resolve(early);
        return { threadId, turnId, completion, interrupt: () => this.interruptTurn(threadId, turnId) };
      }
      this.modelTurnGenerations.set(key, modelTurnGeneration);
      this.flushEarlyToolCalls(key);
      this.discardEarlyToolCalls(threadId, undefined, "different turn identity");
      this.discardCompletedBeforeHandle(threadId, key);
      return { threadId, turnId, completion, interrupt: () => this.interruptTurn(threadId, turnId) };
    } catch (error) { this.startingTurns.delete(threadId); this.discardEarlyToolCalls(threadId, undefined, "turn start failed"); this.discardCompletedBeforeHandle(threadId, undefined); throw error; }
  }

  async interruptTurn(threadId: string, turnId?: string): Promise<unknown> {
    const active = this.turns.get(threadId);
    const effectiveTurnId = turnId ?? active?.turnId;
    if (!effectiveTurnId) throw new Error(`thread ${threadId} has no active turn`);
    const key = this.turnKey(threadId, effectiveTurnId);
    this.interruptedTurns.add(key);
    return this.request("turn/interrupt", { threadId, turnId: effectiveTurnId });
  }

  /** Read-only handshake diagnostics. It deliberately does not start a thread or a model turn. */
  async readOnlyDiagnostics(): Promise<{ readonly account: unknown; readonly models: unknown; readonly mcpServers: unknown; readonly config: unknown }> {
    if (!this.initialized) throw new Error("App Server is not initialized");
    const [account, models, mcpServers, config] = await Promise.all([
      this.request("account/read", {}),
      this.request("model/list", { limit: 200, includeHidden: false }),
      this.request("mcpServerStatus/list", { limit: 200, detail: "toolsAndAuthOnly" }),
      this.request("config/read", { cwd: this.config.workspace, includeLayers: false }),
    ]);
    return { account, models, mcpServers, config };
  }

  /**
   * Verify the fast request alias on this exact initialized App Server before
   * enabling it for role threads. The probe starts an ephemeral thread only;
   * it never starts a model turn.
   */
  async verifyFastServiceTier(profileId: AgentProfile["id"] = "coordinator"): Promise<FastServiceTierVerification> {
    if (!this.initialized) throw new Error("App Server is not initialized");
    let catalog: unknown;
    try { catalog = await this.request("model/list", { limit: 200, includeHidden: false }); }
    catch (error) { this.fastServiceTierVerified = false; return { verified: false, requested: "fast", reason: error instanceof Error ? error.message : String(error) }; }
    const models = catalog && typeof catalog === "object" && Array.isArray((catalog as { data?: unknown[] }).data)
      ? (catalog as { data: unknown[] }).data : [];
    const luna = models.find((model) => model && typeof model === "object" && ["id", "model", "slug"].some((key) => (model as Record<string, unknown>)[key] === "gpt-5.6-luna")) as Record<string, unknown> | undefined;
    const additionalSpeedTiers = Array.isArray(luna?.additionalSpeedTiers) ? luna.additionalSpeedTiers : [];
    const serviceTiers = Array.isArray(luna?.serviceTiers) ? luna.serviceTiers : [];
    const advertised = additionalSpeedTiers.some((tier) => tier === "fast") || serviceTiers.some((tier) => tier === "fast" || (tier && typeof tier === "object" && ((tier as Record<string, unknown>).id === "fast" || (tier as Record<string, unknown>).name === "fast" || (tier as Record<string, unknown>).id === "priority" || (tier as Record<string, unknown>).name === "Fast")));
    if (!advertised) { this.fastServiceTierVerified = false; return { verified: false, requested: "fast", reason: "Luna catalog did not advertise a fast service tier" }; }
    this.fastServiceTierVerified = true;
    try {
      const thread = await this.createThread(profileId);
      return { verified: true, requested: "fast", echoed: thread.effectiveServiceTier };
    } catch (error) {
      this.fastServiceTierVerified = false;
      return { verified: false, requested: "fast", reason: error instanceof Error ? error.message : String(error) };
    }
  }

  diagnostics(): { readonly appServer: "connected" | "stopped"; readonly gameOnlyTools: "pending"; readonly toolNames: readonly string[]; readonly productionGameplay: "unsupported"; readonly modelProfiles: readonly string[]; readonly cliVersion: string; readonly modelCatalogConfigured: boolean; readonly modelCatalogPath?: string; readonly modelCatalogSha256?: string } {
    return { appServer: this.initialized && !this.closed ? "connected" : "stopped", gameOnlyTools: "pending", toolNames: this.tools.map((tool) => tool.name), productionGameplay: "unsupported", modelProfiles: this.profiles.map((profile) => `${profile.id}:${profile.model}:${profile.reasoningEffort}:${this.fastServiceTierVerified ? (profile.serviceTierRequested ?? "default") : "default"}`), cliVersion: this.observedCliVersion, modelCatalogConfigured: this.config.modelCatalogJson !== undefined, ...(this.config.modelCatalogJson === undefined ? {} : { modelCatalogPath: this.config.modelCatalogJson }), ...(this.modelCatalogSha256 === undefined ? {} : { modelCatalogSha256: this.modelCatalogSha256 }) };
  }

  private request(method: string, params?: unknown): Promise<unknown> {
    if (this.closed || !this.child?.stdin.writable) return Promise.reject(new Error("App Server transport is closed"));
    const id = this.nextRpcId++;
    const message: RpcRequest = { jsonrpc: "2.0", id, method, ...(params === undefined ? {} : { params }) };
    return new Promise((resolve, reject) => {
      const timeout = this.options.requestTimeoutMs ?? 30_000;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`App Server request timed out: ${method}`)); }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.child!.stdin.write(`${JSON.stringify(message)}\n`);
    });
  }

  private notify(method: string, params?: unknown): void {
    if (!this.child?.stdin.writable) throw new Error("App Server transport is closed");
    const message: RpcNotification = { jsonrpc: "2.0", method, ...(params === undefined ? {} : { params }) };
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  private onLine(line: string): void {
    if (line.length > 8 * 1024 * 1024) { this.failAll(new Error("App Server JSONL message exceeds 8 MiB")); return; }
    let raw: unknown;
    try { raw = JSON.parse(line); } catch { this.emit("protocolError", new Error("invalid App Server JSONL")); return; }
    if (!raw || typeof raw !== "object") return;
    const message = raw as { id?: string | number; method?: string; params?: unknown; result?: unknown; error?: { code: number; message: string; data?: unknown } };
    if (message.id !== undefined && (message.result !== undefined || message.error !== undefined) && message.method === undefined) { this.onResponse(message as RpcResponse); return; }
    if (message.method && message.id !== undefined) { void this.onServerRequest(message as RpcRequest); return; }
    if (message.method) { this.onNotification(message as RpcNotification); return; }
  }

  private onResponse(response: RpcResponse): void {
    const pending = this.pending.get(response.id);
    if (!pending) { this.emit("lateResponse", response); return; }
    this.pending.delete(response.id);
    if (pending.timer) clearTimeout(pending.timer);
    if (response.error) pending.reject(new Error(`${response.error.code}: ${response.error.message}`)); else pending.resolve(response.result);
  }

  private async onServerRequest(request: RpcRequest, announce = true): Promise<void> {
    if (announce) this.emit("serverRequest", request);
    try {
      let result: unknown;
      if (request.method === "item/tool/call") {
        const params = request.params as { threadId?: string; turnId?: string; tool?: string; callId?: string; arguments?: Record<string, unknown> } | undefined;
        if (!params?.threadId || !params.turnId || typeof params.tool !== "string" || !this.tools.some((tool) => tool.name === params.tool)) result = this.staleToolResult("missing or unauthorized game tool identity");
        else {
          const threadId = params.threadId;
          const turnId = params.turnId;
          const key = this.turnKey(threadId, turnId);
          if (this.startingTurns.has(threadId)) {
            this.bufferEarlyToolCall(key, request);
            return;
          }
          result = await this.dispatchToolCall(request, { threadId, turnId, arguments: params.arguments });
        }
      }
      else if (request.method === "currentTime/read") result = { currentTimeAt: Math.floor(Date.now() / 1000) };
      else if (request.method === "mcpServer/elicitation/request" || request.method === "item/tool/requestUserInput" || request.method.includes("Approval") || request.method.includes("approval") || request.method === "applyPatchApproval" || request.method === "execCommandApproval") throw new Error("HearthCrew denies non-game App Server requests");
      else throw new Error(`unsupported App Server request: ${request.method}`);
      this.writeRpc({ jsonrpc: "2.0", id: request.id, result });
    } catch (error) {
      this.writeRpc({ jsonrpc: "2.0", id: request.id, error: { code: -32601, message: error instanceof Error ? error.message : String(error) } });
    }
  }

  private onNotification(notification: RpcNotification): void {
    this.emit("notification", notification);
    if (notification.method !== "turn/completed") return;
    const params = notification.params as { threadId?: string; turn?: { id?: string } } | undefined;
    if (!params?.threadId || !params.turn?.id) return;
    const key = this.turnKey(params.threadId, params.turn.id);
    const active = this.turns.get(params.threadId);
    if (active?.turnId === params.turn.id) this.turns.delete(params.threadId);
    this.modelTurnGenerations.delete(key);
    this.interruptedTurns.delete(key);
    const completion = this.completions.get(key);
    if (completion) {
      this.completions.delete(key);
      const failure = this.turnFailure(params.turn);
      if (failure) completion.reject(failure); else completion.resolve(params.turn);
    }
    else if (this.startingTurns.has(params.threadId)) {
      this.completedBeforeHandle.set(key, params.turn);
      this.discardEarlyToolCalls(params.threadId, key, "turn completed before it became active");
    }
  }

  private writeRpc(message: RpcResponse): void { if (this.child?.stdin.writable) this.child.stdin.write(`${JSON.stringify(message)}\n`); }
  private turnKey(threadId: string, turnId: string): string { return `${threadId}\u0000${turnId}`; }
  private failAll(error: Error): void {
    if (this.failedError) return;
    this.failedError = error;
    this.closed = true;
    for (const pending of this.pending.values()) { if (pending.timer) clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
    for (const completion of this.completions.values()) completion.reject(error);
    this.completions.clear();
    this.startingTurns.clear();
    this.turns.clear();
    this.modelTurnGenerations.clear();
    this.modelTurnGenerationCounters.clear();
    this.completedBeforeHandle.clear();
    this.earlyToolCalls.clear();
    this.interruptedTurns.clear();
    this.emit("disconnect", error);
  }

  private bufferEarlyToolCall(key: string, request: RpcRequest): void {
    const queued = this.earlyToolCalls.get(key) ?? [];
    if (queued.length >= MAX_EARLY_TOOL_CALLS_PER_TURN) {
      this.writeRpc(this.staleToolRpcResult(request.id, "turn-start tool-call buffer is full"));
      return;
    }
    queued.push(request);
    this.earlyToolCalls.set(key, queued);
  }

  private flushEarlyToolCalls(key: string): void {
    const queued = this.earlyToolCalls.get(key);
    this.earlyToolCalls.delete(key);
    for (const request of queued ?? []) void this.onServerRequest(request, false);
  }

  private discardEarlyToolCalls(threadId: string, keepKey: string | undefined, reason: string): void {
    const prefix = `${threadId}\u0000`;
    for (const [key, queued] of this.earlyToolCalls) {
      if (!key.startsWith(prefix) || key === keepKey) continue;
      this.earlyToolCalls.delete(key);
      for (const request of queued) this.writeRpc(this.staleToolRpcResult(request.id, reason));
    }
    if (keepKey !== undefined) {
      const queued = this.earlyToolCalls.get(keepKey);
      if (queued) {
        this.earlyToolCalls.delete(keepKey);
        for (const request of queued) this.writeRpc(this.staleToolRpcResult(request.id, reason));
      }
    }
  }

  private discardCompletedBeforeHandle(threadId: string, keepKey: string | undefined): void {
    const prefix = `${threadId}\u0000`;
    for (const key of this.completedBeforeHandle.keys()) {
      if (key.startsWith(prefix) && key !== keepKey) this.completedBeforeHandle.delete(key);
    }
  }

  private async dispatchToolCall(request: RpcRequest, params: { threadId: string; turnId: string; arguments?: Record<string, unknown> }): Promise<unknown> {
    const active = this.turns.get(params.threadId);
    const key = this.turnKey(params.threadId, params.turnId);
    const modelTurnGeneration = this.modelTurnGenerations.get(key);
    const requestedGeneration = params.arguments?.bodyGeneration;
    if (modelTurnGeneration === undefined || !active || active.turnId !== params.turnId || this.interruptedTurns.has(key) || (requestedGeneration !== undefined && requestedGeneration !== active.bodyGeneration)) return this.staleToolResult("stale, interrupted, or mismatched body generation");
    return this.options.dynamicToolHandler ? await this.options.dynamicToolHandler({ ...(request.params as Record<string,unknown>), rpcRequestId: request.id }) : { contentItems: [{ type: "inputText", text: "tool unavailable" }], success: false };
  }

  private staleToolResult(reason: string): { readonly contentItems: readonly [{ readonly type: "inputText"; readonly text: string }]; readonly success: false } {
    return { contentItems: [{ type: "inputText", text: `stale or inactive turn; tool call rejected (${reason})` }], success: false };
  }

  private staleToolRpcResult(id: string | number, reason: string): RpcResponse {
    return { jsonrpc: "2.0", id, result: this.staleToolResult(reason) };
  }

  private turnFailure(turn: unknown): Error | undefined {
    if (!turn || typeof turn !== "object" || (turn as { status?: unknown }).status !== "failed") return undefined;
    const error = (turn as { error?: unknown }).error;
    const detail = error && typeof error === "object" && typeof (error as { message?: unknown }).message === "string"
      ? (error as { message: string }).message
      : typeof error === "string" ? error : "unknown App Server turn failure";
    return new Error(`App Server turn failed: ${detail}`);
  }
}
