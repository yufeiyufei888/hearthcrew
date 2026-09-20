import type { CrewJournal } from "./crew-journal.js";
import type { ValidatedAction } from "./action-parameters.js";
export interface Cooperation {
  id:string;worldId:string;requester:string;recipient:string;messageId?:string;summary:string;
  category:"yield"|"transfer"|"materials"|"assignment";
  action:ValidatedAction;state:"proposed"|"accepted"|"rejected"|"submitted"|"completed"|"blocked"|"reconcile";
  actionId?:string;reason?:string;supplierId?:string;beneficiaryId?:string;
}
/** Acceptance queues work. Only a later body receipt can close it successfully. */
export class CooperationQueue {
  private readonly proposals=new Map<string,Cooperation>();
  constructor(private readonly journal:CrewJournal){}
  restore(){for(const row of this.journal.rows())if(row.type==="cooperation.state"){const p=row.data as unknown as Cooperation;this.proposals.set(`${row.worldId}:${p.id}`,structuredClone(p));}}
  list(world:string,bot?:string){return [...this.proposals.values()].filter(p=>p.worldId===world&&(!bot||p.recipient===bot||p.requester===bot)).map(p=>structuredClone(p));}
  get(world:string,id:string){return this.list(world).find(p=>p.id===id);}
  async save(proposal:Cooperation){await this.journal.append("cooperation.state",proposal.worldId,{...proposal});this.proposals.set(`${proposal.worldId}:${proposal.id}`,structuredClone(proposal));return structuredClone(proposal);}
  async decide(world:string,id:string,bot:string,accept:boolean,reason?:string){const p=this.get(world,id);if(!p||p.recipient!==bot)throw new Error("proposal is not addressed to this companion");if(p.state!=="proposed")return p;return this.save({...p,state:accept?"accepted":"rejected",reason});}
  async resolveCycles(world:string):Promise<string[]> {
    const pending=this.list(world).filter(p=>["proposed","accepted"].includes(p.state)&&p.action.kind==="TRANSFER");
    const affected=new Set<string>();
    for(const p of pending){const from=p.action.parameters.target??p.requester,to=p.recipient;
      const visit=(node:string,seen:Set<string>):boolean=>{if(node===from)return true;if(seen.has(node))return false;seen.add(node);return pending.filter(q=>(q.action.parameters.target??q.requester)===node).some(q=>visit(q.recipient,seen));};
      if(from===to||visit(to,new Set())){affected.add(from);affected.add(to);await this.save({...p,state:"blocked",reason:"CYCLIC_SUPPLY_WAIT: inspect local preparation or another actual supplier"});}
    }return [...affected];
  }
  async terminal(world:string,bot:string,actionId:string,state:string,reason:string){
    if(!["COMPLETED","PARTIAL","FAILED","CANCELLED","REJECTED","STALE","EXPIRED","RECONCILE_REQUIRED"].includes(state))return;
    const p=this.list(world,bot).find(p=>p.recipient===bot&&p.actionId===actionId);
    if(p&&(p.state==="submitted"||p.state==="reconcile"))await this.save({...p,state:state==="COMPLETED"?"completed":state==="RECONCILE_REQUIRED"?"reconcile":"blocked",reason});
  }
}

/** Read-only preflight. A request never grants remote inventory or a route. */
export function assessSupply(world:Record<string,any>,p:Cooperation):Record<string,unknown> {
 const bodies=world.companions??[],supplier=bodies.find((b:any)=>b.botId===p.recipient),receiver=bodies.find((b:any)=>b.botId===(p.beneficiaryId??p.action.parameters.target));
 if(!supplier||!receiver||!Array.isArray(supplier.inventory))return {state:"unknown",reason:"SUPPLIER_INVENTORY_UNKNOWN",path:"unknown"};
 const resource=p.action.parameters.resource,count=p.action.parameters.count??1;
 const held=supplier.inventory.filter((s:any)=>s.item===resource).reduce((n:number,s:any)=>n+s.count,0);
 const tool=/_pickaxe$|_axe$|_shovel$|_hoe$|_sword$/.test(resource??"");
 const usable=tool&&Array.isArray(supplier.tools)?supplier.tools.filter((s:any)=>s.item===resource&&(s.remainingDurability===-1||s.remainingDurability>=16)).reduce((n:number,s:any)=>n+s.count,0):held;
 // Automatic requester must not drain the supplier's last usable tool. Explicit gifts and owner actions bypass this check.
 const selfReserve=tool?1:0;
 if(tool&&!Array.isArray(supplier.tools))return {state:"unknown",reason:"SUPPLIER_TOOL_DURABILITY_UNKNOWN",held,path:"unknown"};
 if(usable-selfReserve<count)return {state:"blocked",reason:held<count?"SUPPLIER_MATERIALS_MISSING":"SUPPLIER_TOOL_SELF_USE_OR_WORN",held,usable,selfReserve,path:"unknown"};
 if(supplier.dimension!==receiver.dimension)return {state:"blocked",reason:"TRANSFER_DIFFERENT_DIMENSION",held,path:"unknown"};
 return {state:"candidate",held,usable,selfReserve,path:"executor_must_verify",reason:"Physical approach and inventory recheck required; not delivered"};
}
