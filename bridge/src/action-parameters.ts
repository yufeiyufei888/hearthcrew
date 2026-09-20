/** Structural validation shared by the model tool boundary and controller.
 *
 * This deliberately checks only the wire shape and the limits enforced by
 * ModLink.  Reachability, inventory, recipes and protection are world facts
 * and remain server-side decisions.
 */
import definitions from "../../protocol/action-capabilities.json" with { type: "json" };
export type ActionKind = keyof typeof definitions;
export const ACTION_KINDS = Object.keys(definitions) as ActionKind[];
export const ACTION_DEFINITIONS = definitions;
export type BlockPosition = { readonly x: number; readonly y: number; readonly z: number };
export type BuildStep = { readonly position: BlockPosition; readonly block: string };
export type ActionParameters = {
  readonly actions?: readonly ValidatedAction[];
  readonly candidates?:readonly string[];
  readonly radius?:number;
  readonly accessBudget?:number;
  readonly preparation?:{enabled:boolean;maxDepth:number;maxSteps:number;maxBreaks:number};
  readonly position?: BlockPosition;
  readonly target?: string;
  readonly count?: number;
  readonly slot?: number;
  readonly resource?: string;
  readonly steps?: readonly BuildStep[];
};
export type ValidatedAction = { readonly kind: ActionKind; readonly parameters: ActionParameters };

/** Two explicit wire spellings, one validated meaning. Never merge ambiguous quantities/budgets. */
export function normalizeActArguments(args:Record<string,unknown>):Record<string,unknown> {
  const parameterFields=new Set(Object.keys(ACTION_PARAMETERS_SCHEMA.properties as object));
  const envelopeFields=new Set(["kind","parameters","taskId","actionId","intentId"]);
  for(const key of Object.keys(args))if(!parameterFields.has(key)&&!envelopeFields.has(key))throw Error(`act unknown field ${key}; use {kind,parameters:{...}}`);
  const flat=Object.keys(args).filter(key=>parameterFields.has(key));
  if(!flat.length)return args;
  if(args.parameters!==undefined)throw Error("act parameters conflict: use nested parameters or top-level action fields, never both");
  return {...Object.fromEntries(Object.entries(args).filter(([key])=>envelopeFields.has(key))),parameters:Object.fromEntries(flat.map(key=>[key,args[key]]))};
}

// UUID.fromString accepts every canonical hexadecimal UUID version. Entity
// identity is supplied by observe; the bridge must not narrow that wire type
// to today's UUID version, since saved worlds and future Java runtimes may use
// another version.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const ACTION_PARAMETERS_SCHEMA: Record<string, unknown> = {
  type: "object",
  description: "动作参数。坐标使用整数方块坐标；target 只接受实际实体 UUID。",
  additionalProperties: false,
  properties: {
    preparation:{type:"object",additionalProperties:false,properties:{enabled:{type:"boolean"},maxDepth:{type:"integer",minimum:1,maximum:6},maxSteps:{type:"integer",minimum:1,maximum:16},maxBreaks:{type:"integer",minimum:0,maximum:64}}},
    actions: {type:"array",minItems:1,maxItems:8,items:{type:"object",additionalProperties:false,required:["kind","parameters"],properties:{kind:{type:"string",enum:ACTION_KINDS.filter(k=>k!=="SEQUENCE")},parameters:{type:"object"}}}},
    candidates:{type:"array",minItems:1,maxItems:16,items:{type:"string",pattern:"^[a-z0-9_.-]+:[a-z0-9_./-]+$"}},
    radius:{type:"integer",minimum:1,maximum:32},
    accessBudget:{type:"integer",minimum:0,maximum:64,default:16,description:"同一根动作（含SEQUENCE所有步骤）共享安全自然地形开路预算；0禁止额外破坏，不随回收或重规划重置。"},
    position: {
      type: "object", additionalProperties: false, required: ["x", "y", "z"],
      properties: { x: { type: "integer", minimum: -30_000_000, maximum: 30_000_000 }, y: { type: "integer", minimum: -2_048, maximum: 2_048 }, z: { type: "integer", minimum: -30_000_000, maximum: 30_000_000 } },
    },
    target: { type: "string", pattern: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", description: "真实观察到的目标实体 UUID；FOLLOW/GUARD/ATTACK/PICKUP/TRANSFER 必需。" },
    slot: { type: "integer", minimum: 0, maximum: 35, description: "SELECT 必须明确指定槽位；9..35 会与当前快捷栏槽位交换，不会增加快捷栏数量。" },
    count: { type: "integer", minimum: 0, maximum: 100000, description: "数量；SELECT 时为 0..35 的背包槽位，CRAFT 为制作次数，TRANSFER 为交付数量，GATHER 为 1..64 根已识别木干。" },
    resource: { type: "string", minLength: 1, maxLength: 256, pattern: "^[a-z0-9_.-]+:[a-z0-9_./-]+$", description: "CRAFT 的服务器配方 ID；其他动作的物品/方块 ID，支持种类以 capabilities 为准。" },
    steps: {
      type: "array", minItems: 1, maxItems: 16,
      description: "BUILD 的有序方块步骤；每步包含唯一坐标和严格 ResourceLocation 方块 ID。全部位置须在接单起点32格内。支持原版非重力 BlockItem，使用真实材料与原版点击面；特殊形状或多格设施由原版放置规则核验。",
      items: {
        type: "object", additionalProperties: false, required: ["position", "block"],
        properties: {
          position: { type: "object", additionalProperties: false, required: ["x", "y", "z"], properties: {
            x: { type: "integer", minimum: -30_000_000, maximum: 30_000_000 },
            y: { type: "integer", minimum: -2_048, maximum: 2_048 },
            z: { type: "integer", minimum: -30_000_000, maximum: 30_000_000 },
          } },
          block: { type: "string", minLength: 1, maxLength: 256, pattern: "^[a-z0-9_.-]+:[a-z0-9_./-]+$" },
        },
      },
    },
  },
};

function record(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as Record<string, unknown>;
}
function integer(value: unknown, label: string, min: number, max: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) throw new Error(`${label} must be an integer in ${min}..${max}`);
  return value as number;
}
function nonEmptyString(value: unknown, label: string, max: number): string {
  if (typeof value !== "string" || value.length < 1 || value.length > max) throw new Error(`${label} must be a non-empty string of at most ${max} characters`);
  return value;
}
function position(value: unknown, label = "parameters.position"): BlockPosition {
  const p = record(value, label);
  for (const key of Object.keys(p)) if (!(key === "x" || key === "y" || key === "z")) throw new Error(`${label} has unsupported field: ${key}`);
  return { x: integer(p.x, `${label}.x`, -30_000_000, 30_000_000), y: integer(p.y, `${label}.y`, -2_048, 2_048), z: integer(p.z, `${label}.z`, -30_000_000, 30_000_000) };
}

const RESOURCE_LOCATION = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const GATHER_RESOURCES = new Set(["oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry"].map(name=>`minecraft:${name}_log`));

function buildSteps(value: unknown): readonly BuildStep[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16) throw new Error("BUILD steps must contain 1..16 steps");
  const seen = new Set<string>();
  return value.map((raw, index) => {
    const step = record(raw, `parameters.steps[${index}]`);
    for (const key of Object.keys(step)) if (key !== "position" && key !== "block") throw new Error(`parameters.steps[${index}] has unsupported field: ${key}`);
    const normalizedPosition = position(step.position, `parameters.steps[${index}].position`);
    const block = nonEmptyString(step.block, `parameters.steps[${index}].block`, 256);
    if (!RESOURCE_LOCATION.test(block)) throw new Error(`parameters.steps[${index}].block must be a ResourceLocation`);
    const coordinate = `${normalizedPosition.x},${normalizedPosition.y},${normalizedPosition.z}`;
    if (seen.has(coordinate)) throw new Error(`BUILD steps contain duplicate position ${coordinate}`);
    seen.add(coordinate);
    return { position: normalizedPosition, block };
  });
}

function deepFreeze<T>(value: T): T {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value as Record<string, unknown>)) deepFreeze(child);
  return Object.freeze(value);
}

export function validateActionParameters(kind: unknown, parameters: unknown): ValidatedAction {
  const normalized = nonEmptyString(kind, "kind", 32).toUpperCase() as ActionKind;
  if (!(ACTION_KINDS as readonly string[]).includes(normalized)) throw new Error(`unsupported action kind: ${String(kind)}`);
  const source = parameters === undefined ? {} : record(parameters, "parameters");
  const allowed = new Set(["position", "target", "count", "slot", "resource", "steps", "actions", "candidates", "radius", "accessBudget", "preparation"]);
  for (const key of Object.keys(source)) if (!allowed.has(key)) throw new Error(`unsupported action parameter: ${key}`);
  const rule = definitions[normalized];
  let actions:ValidatedAction[]|undefined,candidates:string[]|undefined;
  if(source.actions!==undefined){
    if(!Array.isArray(source.actions)||source.actions.length<1||source.actions.length>8)throw new Error("SEQUENCE requires 1..8 actions");
    actions=source.actions.map((v,i)=>{const a=record(v,`actions[${i}]`);if(Object.keys(a).some(k=>!["kind","parameters"].includes(k)))throw new Error("unexpected sequence step field");if(["SEQUENCE","FOLLOW","GUARD","SELF_DEFENCE","BREATHE"].includes(String(a.kind).toUpperCase()))throw new Error("nested or unbounded SEQUENCE step");return validateActionParameters(a.kind,a.parameters);});
  }
  if(source.candidates!==undefined){if(!Array.isArray(source.candidates)||source.candidates.length<1||source.candidates.length>16||source.candidates.some(v=>typeof v!=="string"||!RESOURCE_LOCATION.test(v)))throw new Error("candidates requires 1..16 block IDs");candidates=[...new Set(source.candidates as string[])];}
  if(source.slot!==undefined&&normalized!=="SELECT")throw new Error("slot is only accepted by SELECT");
  for(const key of rule.required)if(source[key]===undefined)throw new Error(`${normalized} requires ${key}${key==="count"?` in ${rule.minCount}..${rule.maxCount}`:""}`);
  for(const key of Object.keys(source))if(!(rule.allowed as string[]).includes(key))throw new Error(`${normalized} does not accept ${key}`);
  if(normalized==="INTERACT" && Number(source.position!==undefined)+Number(source.target!==undefined)!==1)throw new Error("INTERACT requires exactly one of position or target");
  const hasSteps=source.steps!==undefined, hasPosition=source.position!==undefined, hasTarget=source.target!==undefined, hasResource=source.resource!==undefined;
  const legacyCount = source.count===undefined ? undefined : integer(source.count,"parameters.count",0,100_000);
  const slot = source.slot===undefined ? undefined : integer(source.slot,"parameters.slot",0,35);
  if(normalized==="SELECT" && slot===undefined && legacyCount===undefined)throw new Error("SELECT requires slot in 0..35 (legacy count supported)");
  if(slot!==undefined && legacyCount!==undefined && slot!==legacyCount)throw new Error("SELECT slot and count conflict");
  const countValue=slot??legacyCount??(normalized==="EXCAVATE"?32:normalized==="MINE"?1:undefined);
  if(countValue!==undefined && (countValue<rule.minCount || countValue>rule.maxCount))throw new Error(rule.maxCount===0?`${normalized} does not accept a non-zero count`:`${normalized} requires count in ${rule.minCount}..${rule.maxCount}`);
  let preparation:ActionParameters["preparation"];
  if(source.preparation!==undefined){const p=record(source.preparation,"preparation");if(Object.keys(p).some(k=>!["enabled","maxDepth","maxSteps","maxBreaks"].includes(k))||p.enabled!==undefined&&typeof p.enabled!=="boolean")throw Error("invalid preparation policy");preparation={enabled:p.enabled!==false,maxDepth:integer(p.maxDepth??6,"maxDepth",1,6),maxSteps:integer(p.maxSteps??16,"maxSteps",1,16),maxBreaks:integer(p.maxBreaks??64,"maxBreaks",0,64)};}
  const result: ActionParameters = {
    ...(preparation?{preparation}:{}),
    ...(source.accessBudget!==undefined?{accessBudget:integer(source.accessBudget,"accessBudget",0,64)}:{}),
    ...(actions?{actions}:{}),...(candidates?{candidates}:{}),...(source.radius!==undefined?{radius:integer(source.radius,"radius",1,32)}:{}),
    ...(hasPosition ? { position: position(source.position) } : {}),
    ...(hasTarget ? { target: nonEmptyString(source.target, "parameters.target", 128) } : {}),
    ...(countValue === undefined ? {} : { count: countValue }),
    ...(hasResource ? { resource: nonEmptyString(source.resource, "parameters.resource", 256) } : {}),
    ...(hasSteps ? { steps: buildSteps(source.steps) } : {}),
  };
  if (hasTarget && !UUID.test(result.target!)) throw new Error("parameters.target must be a canonical entity UUID");
  if (hasResource && !RESOURCE_LOCATION.test(result.resource!)) throw new Error("parameters.resource must be a ResourceLocation");
  if (normalized === "GATHER" && !GATHER_RESOURCES.has(result.resource!)) throw new Error("GATHER resource must be a supported overworld log");
  return deepFreeze({ kind: normalized, parameters: result });
}

/** Generated into model context and rejection responses; examples must pass the same validator. */
export const ACTION_GUIDE: Record<string, {fields:string; example:Record<string,unknown>}> = definitions;
