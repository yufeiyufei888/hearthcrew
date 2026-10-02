export const WAIT_KINDS=["materials","resources","path","processing","teammate","danger","model_recovery","player_standby","unknown_effect"] as const;
export type WaitKind=typeof WAIT_KINDS[number];
export interface WaitCondition {kind:WaitKind;reason:string;taskId?:string;resource?:string;count?:number;position?:{x:number;y:number;z:number};peerId?:string;sinceTick:number;recoveryAttempts:number;}
export function parseWait(value:unknown,reason:string,tick:number,recoveryAttempts:number):WaitCondition {
 const v=value&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:{};
 const inferred=inferWaitKind(reason);
 // Older models used model_recovery for ordinary resource/path failures. Preserve
 // the reason and identity, but route the wait to facts that can actually release it.
 const kind=v.kind===undefined||v.kind==="model_recovery"&&inferred!=="model_recovery"?inferred:v.kind;
 if(!(WAIT_KINDS as readonly unknown[]).includes(kind))throw new Error("condition.kind must describe materials/resources/path/processing/teammate/danger/model_recovery/player_standby/unknown_effect");
 if(v.count!==undefined&&(!Number.isSafeInteger(v.count)||Number(v.count)<1||Number(v.count)>2304))throw Error("condition.count must be 1..2304");
 if(kind==="materials"&&(typeof v.resource!=="string"||!/^[-a-z0-9_.]+:[-a-z0-9_./]+$/.test(v.resource)||v.count===undefined||!v.peerId&&!v.taskId))throw Error("materials condition requires resource, count and peerId or taskId; use path for inaccessible stations");
 const p=v.position as WaitCondition["position"];
 if(p&&(![p.x,p.y,p.z].every(Number.isSafeInteger)))throw new Error("condition.position requires integer coordinates");
 return {kind:kind as WaitKind,reason,sinceTick:tick,recoveryAttempts,
  ...(typeof v.taskId==="string"?{taskId:v.taskId}:{}),...(typeof v.count==="number"?{count:v.count}:{}),...(typeof v.resource==="string"?{resource:v.resource}:kind==="resources"&&reason.match(/minecraft:[a-z0-9_]+/)?{resource:reason.match(/minecraft:[a-z0-9_]+/)![0]}:{}),...(p?{position:{...p}}:{}),...(typeof v.peerId==="string"?{peerId:v.peerId}:{})};
}
export function waitingEventRelevant(wait:WaitCondition|undefined,reasons:readonly string[]):boolean {
 if(!wait)return true;
 const events:Record<WaitKind,string[]>={materials:["inventory_changed"],resources:["scan_ready","environment_changed","inventory_changed"],path:["environment_changed"],processing:["processing_changed","processing_complete","inventory_changed","environment_changed"],teammate:["team_changed","inventory_changed","chat_message"],danger:["danger_changed","inventory_changed"],model_recovery:["model_recovery","inventory_changed","environment_changed","scan_ready","processing_changed"],player_standby:[],unknown_effect:["inventory_changed","environment_changed","action_result","execution_reconciled"]};
 return reasons.some(r=>events[wait.kind].includes(r)||["body_available","owner_command"].includes(r));
}
export function inferWaitKind(reason:string):WaitKind {
 if(/RECONCILE_REQUIRED|unknown.*effect|未确认.*(?:修改|效果)/i.test(reason))return "unknown_effect";
 if(/WAITING_PROCESS|加工|熔炼.*等待/i.test(reason))return "processing";
 if(/METHOD_CAPACITY|NO_SATISFIABLE|UNVERIFIED_SOURCE|ACQUISITION_BATCH|no reachable|候选|资源.*(?:不足|耗尽)|(?:原木|矿物).*采集方法/i.test(reason))return "resources";
 if(/path|NAV|stance|STALLED|通路|路径|不可达|入口|出口/i.test(reason))return "path";
 return "model_recovery";
}
/** Stable physical condition identity; a new label/stage is not new retry authority. */
export function waitIdentity(wait:WaitCondition):string {
 return JSON.stringify([wait.kind,wait.resource??null,wait.count??null,wait.peerId??null,
  wait.position?[Math.floor(wait.position.x/4),Math.floor(wait.position.y/4),Math.floor(wait.position.z/4)]:null]);
}
// Game-time checks, not wall-clock/model polling. Pauses do not spend this lease.
export const WAIT_RECHECK_TICKS=1200;
export function recheckableWait(wait:WaitCondition|undefined):boolean {
 return !!wait&&!["player_standby","unknown_effect","danger"].includes(wait.kind);
}
