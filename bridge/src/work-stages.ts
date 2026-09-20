import { CrewJournal } from "./crew-journal.js";
import { validateActionParameters, type BlockPosition, type BuildStep } from "./action-parameters.js";

export type Completion = {kind:"inventory";resource:string;count:number} | {kind:"blocks";steps:readonly BuildStep[]} | {kind:"position";position:BlockPosition;dimension:string};
export interface WorkStage {
  worldId:string;botId:string;intentId:string;stageId:string;summary:string;title?:string;purpose?:"preparation"|"resource"|"delivery"|"public_build"|"exploration"|"recovery";proposalId?:string;scope:string;completion:Completion;
  state:"continue"|"complete"|"blocked"; reason?:string; actions:string[]; verifiedDimension?:string; verifiedAtTick?:number; resolutions?:Record<string,{reason:string; verifiedAtTick:number}>;
}
export function completionSpec(raw:unknown): Completion {
  if(!raw || typeof raw!=="object")throw new Error("stage completion required: inventory(resource,count), blocks(steps), or position(position,dimension)");
  const c=raw as Record<string,unknown>;
  if(c.kind==="blocks") {
    if(!Array.isArray(c.steps)||c.steps.length<1||c.steps.length>256)throw new Error("stage blueprint requires 1..256 steps; execute in batches of at most 16");
    const steps:BuildStep[]=[];for(let i=0;i<c.steps.length;i+=16)steps.push(...validateActionParameters("BUILD",{steps:c.steps.slice(i,i+16)}).parameters.steps!);
    if(new Set(steps.map(s=>JSON.stringify(s.position))).size!==steps.length)throw new Error("duplicate stage blueprint position");
    return {kind:"blocks",steps};
  }
  if(c.kind==="position" && typeof c.dimension==="string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(c.dimension))
    return {kind:"position",position:validateActionParameters("MOVE",{position:c.position}).parameters.position!,dimension:c.dimension};
  if(c.kind==="inventory" && typeof c.resource==="string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(c.resource) && Number.isSafeInteger(c.count) && Number(c.count)>0 && Number(c.count)<=2304)
    return {kind:"inventory",resource:c.resource,count:Number(c.count)};
  throw new Error("invalid stage completion criteria");
}
export function stageSatisfied(stage:WorkStage, body:{inventory:unknown[];position:BlockPosition;dimension:string;onGround?:boolean}, inspections:unknown[]):boolean {
  const c=stage.completion;
  if(c.kind==="inventory")return body.inventory.reduce<number>((n,r)=>{const s=r as {item?:string;count?:number};return n+(s.item===c.resource&&Number.isSafeInteger(s.count)?s.count!:0);},0)>=c.count;
  if(c.kind==="position")return c.dimension===body.dimension && body.onGround===true && Math.abs(body.position.y-c.position.y)<.15 && Math.hypot(body.position.x-c.position.x-.5,body.position.z-c.position.z-.5)<=.6;
  return c.steps.every(step=>inspections.some(raw=>{const t=raw as {position?:BlockPosition;block?:string};return t.position?.x===step.position.x&&t.position.y===step.position.y&&t.position.z===step.position.z&&t.block===step.block;}));
}
/** Stages describe work; the executor receipts remain the only action outcome authority. */
export class WorkStages {
  private readonly values=new Map<string,WorkStage>();
  constructor(private readonly journal:CrewJournal){}
  private key(w:string,b:string,i:string,s:string){return `${w}:${b}:${i}:${s}`;}
  restore(){for(const row of this.journal.rows())if(row.type==="work.stage") {const s=row.data as unknown as WorkStage;this.values.set(this.key(row.worldId,s.botId,s.intentId,s.stageId),s);}}
  get(w:string,b:string,i:string,id:string){return this.values.get(this.key(w,b,i,id));}
  current(w:string,b:string,i:string){return [...this.values.values()].reverse().find(s=>s.worldId===w&&s.botId===b&&s.intentId===i&&s.state!=="complete");}
  history(w:string,b:string,limit=8){return [...this.values.values()].filter(s=>s.worldId===w&&s.botId===b).slice(-limit);}
  async save(stage:WorkStage){await this.journal.append("work.stage",stage.worldId,stage as unknown as Record<string,unknown>);this.values.set(this.key(stage.worldId,stage.botId,stage.intentId,stage.stageId),structuredClone(stage));}
  async attach(w:string,b:string,i:string,actionId:string){const s=this.current(w,b,i);if(s&&!s.actions.includes(actionId))await this.save({...s,actions:[...s.actions,actionId]});}
  /** Resolve known effects independently from the immutable historical action outcome. */
  async resolveVerified(stage:WorkStage, body:{harvestEvidence?:unknown[]}, tick:number):Promise<WorkStage>{
    const resolutions={...stage.resolutions};
    const receipts=new Map<string,Record<string,unknown>>();
    for(const row of this.journal.rows(stage.worldId))if(row.type==="game.event"&&row.data.event==="action.terminal"){
      const d=row.data.body as Record<string,unknown>;
      if(d.botId===stage.botId && d.historical!==true)receipts.set(String((d.id as {value?:string})?.value??d.actionId),d);
    }
    for(const id of this.unresolved(stage)){
      const receipt=receipts.get(id);if(!receipt)continue;
      const state=String(receipt.state), reason=String(receipt.message??"");
      if(state==="FAILED" && !/uncertain|unconfirmed|POSTCONDITION|exception|unknown|changed world/i.test(reason))
        resolutions[id]={reason:"known failed attempt; current stage goal independently verified",verifiedAtTick:tick};
      else if(state==="PARTIAL" && /drop|pickup|acquisition|inventory full/i.test(reason)){
        const evidence=(body.harvestEvidence??[]).find(v=>(v as {actionId?:string}).actionId===id) as {recovery?:string;pendingDrops?:unknown[];closedDrops?:unknown[]}|undefined;
        if(evidence?.recovery==="accounted"&&evidence.pendingDrops?.length===0)resolutions[id]={reason:"recorded harvest obligations accounted by actual pickup; stage goal independently verified",verifiedAtTick:tick};
        else if(evidence?.recovery==="unavailable"&&(evidence.closedDrops?.length||evidence.pendingDrops?.length)&&(evidence.closedDrops?.length?evidence.closedDrops:evidence.pendingDrops!).every(raw=>{const d=raw as {availability?:string;removalEvidence?:string};return d.availability==="removed"&&/^entity_removal:(DISCARDED|KILLED)$/.test(d.removalEvidence??"");}))resolutions[id]={reason:"recorded drop loss, not pickup; current stage goal independently verified with replacement resources",verifiedAtTick:tick};
      } else if(state==="PARTIAL" && stage.completion.kind==="blocks" && /; placed=\d+; existing=\d+/.test(reason) && !/unconfirmed|uncertain|exception|ambiguous|postcondition/i.test(reason))
        resolutions[id]={reason:"recorded build effects retained; every stage blueprint block independently verified",verifiedAtTick:tick};
      else if(state==="PARTIAL" && /broken=\d+/.test(reason) && !/unconfirmed|uncertain|exception/i.test(reason))
        resolutions[id]={reason:"known excavation changes retained; current stage goal independently verified",verifiedAtTick:tick};
    }
    const next={...stage,resolutions};if(JSON.stringify(stage.resolutions??{})!==JSON.stringify(resolutions))await this.save(next);return next;
  }
  unresolved(stage:WorkStage):string[]{
    const states=new Map<string,string>();
    for(const row of this.journal.rows(stage.worldId)){
      if(row.type==="action.receipt"&&row.data.botId===stage.botId){const r=row.data.receipt as {state?:string}|undefined;if(r?.state)states.set(String(row.data.actionId),r.state);}
      if(row.type==="game.event"&&row.data.event==="action.terminal") {const d=row.data.body as {botId?:string;id?:{value:string};actionId?:string;state?:string};if(d.botId===stage.botId)states.set(d.actionId??d.id?.value??"",d.state??"UNKNOWN");}
    }
    return stage.actions.filter(id=>states.get(id)!=="COMPLETED" && !stage.resolutions?.[id]);
  }
}
