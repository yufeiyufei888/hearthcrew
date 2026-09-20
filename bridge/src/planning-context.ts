import { createHash } from "node:crypto";

export const PLANNING_CONTEXT_BYTES = 24 * 1024;
export type ContextArchive = {reference:string; path:string; json:string};
const bytes=(value:unknown)=>Buffer.byteLength(JSON.stringify(value),"utf8");
const object=(value:unknown):Record<string,unknown>=>value!==null&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:{};

/** Keep execution authority/safety inline. Larger evidence is explicitly referenced, never silently dropped. */
export function planningContext(input:Record<string,unknown>,limit=PLANNING_CONTEXT_BYTES):{prompt:string;archives:ContextArchive[]} {
 const value=structuredClone(input), archives:ContextArchive[]=[];
 const world=object(input.world),botId=input.botId;
 const self=(Array.isArray(world.companions)?world.companions:[]).map(object).find(b=>b.botId===botId)??{};
 value.currentExecution={worldId:world.worldId,botId,bodyGeneration:self.bodyGeneration,entityId:self.entityId,
  paused:self.paused,stopped:self.stopped,standby:self.standby,position:self.position,dimension:self.dimension,
  health:self.health,food:self.food,air:self.air,localSafety:self.localSafety,inventory:self.inventory,equipment:self.equipment,selectedSlot:self.selectedSlot,
  activeAction:self.activeAction,action:self.action,suspendedActionIds:self.suspendedActionIds,
  actionState:self.actionState,actionId:self.actionId,intentId:input.intentId,intentOrigin:input.intentOrigin,
  intentGeneration:input.intentGeneration,communicationOnly:input.communicationOnly,
  processingOrders:object(self.execution).processingOrders};
 value.contextAccess={instruction:"字段paged=true表示证据仍保留；使用observe(contextRef=reference,cursor=0)按页读取。不得把未展开的未知效果当成已完成，不盲目重放动作。",budgetBytes:limit};
 const page=(parent:Record<string,unknown>,key:string,path:string)=>{
  const original=parent[key];if(original===undefined||object(original).paged===true)return false;
  const json=JSON.stringify(original);if(Buffer.byteLength(json,"utf8")<700)return false;
  const reference=createHash("sha256").update(json).digest("hex");
  archives.push({reference,path,json});
  const record=object(original);
  parent[key]={paged:true,reference,bytes:Buffer.byteLength(json,"utf8"),...(Array.isArray(original)?{count:original.length}:{keys:Object.keys(record)}),
    ...(record.actionId?{actionId:record.actionId}:{}),...(record.state?{state:record.state}:{}),...(record.eventId?{eventId:record.eventId}:{}),
    ...(record.bodyGeneration!==undefined?{bodyGeneration:record.bodyGeneration}:{})};return true;
 };
 // Keep result identity and terminal/unknown state even when its detailed evidence is paged.
 // Avoid duplicate self payload; currentExecution is the canonical live body view.
 const liveWorld=object(value.world);if(Array.isArray(liveWorld.companions))liveWorld.companions=liveWorld.companions.map(raw=>{const b=object(raw);if(b.botId!==botId)return b;const copy={...b};for(const key of ["inventory","equipment","actionJournal","harvestEvidence"])delete copy[key];return copy;});
 for(const result of Array.isArray(value.actionResults)?value.actionResults:[]){const r=object(result);for(const key of ["execution","body","payload","harvestEvidence"])if(bytes(value)>limit)page(r,key,`actionResults.${String(r.eventId)}.${key}`);}
 for(const key of ["stageHistory","recoveryReview","memories","reasons","recoveryAssessment","world","actionResults","inbox"]){
  if(bytes(value)<=limit)break;
  if(key==="actionResults"){
   const results=Array.isArray(value.actionResults)?value.actionResults:[];
   value.resultIndex=results.map(r=>{const item=object(r);return {eventId:item.eventId,actionId:item.actionId,state:item.state,bodyGeneration:item.bodyGeneration};});
  }
  page(value,key,key);
 }
 const prompt=JSON.stringify(value);
 if(Buffer.byteLength(prompt,"utf8")>limit)throw new Error("CONTEXT_CRITICAL_OVERFLOW: critical execution/safety facts exceed context budget; no silent truncation");
 return {prompt,archives};
}

/** Page at Unicode codepoint boundaries, preserving exact JSON for reconstruction. */
export function contextPage(json:string,cursor=0){
 if(!Number.isSafeInteger(cursor)||cursor<0)throw new Error("context cursor must be a nonnegative integer");
 const points=Array.from(json);if(cursor>points.length)throw new Error("context cursor out of bounds");
 const text=points.slice(cursor,cursor+1024).join("");
 return {encoding:"json-text",text,cursor,nextCursor:cursor+1024<points.length?cursor+1024:null,totalCodepoints:points.length,totalBytes:Buffer.byteLength(json,"utf8")};
}
