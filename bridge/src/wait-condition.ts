export const WAIT_KINDS=["materials","path","processing","teammate","danger","model_recovery","player_standby","unknown_effect"] as const;
export type WaitKind=typeof WAIT_KINDS[number];
export interface WaitCondition {kind:WaitKind;reason:string;taskId?:string;resource?:string;count?:number;position?:{x:number;y:number;z:number};peerId?:string;sinceTick:number;recoveryAttempts:number;}
export function parseWait(value:unknown,reason:string,tick:number,recoveryAttempts:number):WaitCondition {
 const v=value&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:{};
 const kind=v.kind??"model_recovery";if(!(WAIT_KINDS as readonly unknown[]).includes(kind))throw new Error("condition.kind must describe materials/path/processing/teammate/danger/model_recovery/player_standby/unknown_effect");
 if(v.count!==undefined&&(!Number.isSafeInteger(v.count)||Number(v.count)<1||Number(v.count)>2304))throw Error("condition.count must be 1..2304");
 if(kind==="materials"&&(typeof v.resource!=="string"||!/^[-a-z0-9_.]+:[-a-z0-9_./]+$/.test(v.resource)||v.count===undefined||!v.peerId&&!v.taskId))throw Error("materials condition requires resource, count and peerId or taskId; use path for inaccessible stations");
 const p=v.position as WaitCondition["position"];
 if(p&&(![p.x,p.y,p.z].every(Number.isSafeInteger)))throw new Error("condition.position requires integer coordinates");
 return {kind:kind as WaitKind,reason,sinceTick:tick,recoveryAttempts,
  ...(typeof v.taskId==="string"?{taskId:v.taskId}:{}),...(typeof v.count==="number"?{count:v.count}:{}),...(typeof v.resource==="string"?{resource:v.resource}:{}),...(p?{position:{...p}}:{}),...(typeof v.peerId==="string"?{peerId:v.peerId}:{})};
}
export function waitingEventRelevant(wait:WaitCondition|undefined,reasons:readonly string[]):boolean {
 if(!wait)return true;
 const events:Record<WaitKind,string[]>={materials:["inventory_changed"],path:["environment_changed"],processing:["processing_changed","processing_complete","inventory_changed","environment_changed"],teammate:["team_changed","inventory_changed","chat_message"],danger:["danger_changed","inventory_changed"],model_recovery:["model_recovery"],player_standby:[],unknown_effect:["inventory_changed","environment_changed","action_result","execution_reconciled"]};
 return reasons.some(r=>events[wait.kind].includes(r)||["body_available","owner_command"].includes(r));
}
