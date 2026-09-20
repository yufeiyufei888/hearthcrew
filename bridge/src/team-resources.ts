import {createHash} from 'node:crypto';
import type {Cooperation} from './cooperation.js';
type Row=Record<string,any>;
const row=(v:unknown):Row=>v&&typeof v==='object'&&!Array.isArray(v)?v as Row:{};
const list=(v:unknown):Row[]=>Array.isArray(v)?v.map(row):[];
const itemId=(v:unknown):v is string=>typeof v==='string'&&/^[-a-z0-9_.]+:[-a-z0-9_./]+$/.test(v);
export function inventoryQuery(raw:unknown,self:string,bodies:Row[]):{botId:string;items?:string[]} {
 const q=row(raw);if(Object.keys(q).some(k=>!['companion','items'].includes(k))||typeof q.companion!=='string')throw Error('teamInventory requires companion (name or botId) and optional items');
 const b=bodies.find(b=>b.botId===q.companion||String(b.name).toLowerCase()===q.companion.toLowerCase());
 if(!b||b.botId===self)throw Error('teamInventory must address another companion in this world');
 if(q.items!==undefined&&(!Array.isArray(q.items)||q.items.length>16||!q.items.every(itemId)))throw Error('teamInventory.items: at most 16 item IDs');
 return {botId:b.botId,...(q.items?{items:[...new Set<string>(q.items)]}: {})};
}
export function inventoryVersion(body:Row):string {
 return createHash('sha256').update(JSON.stringify([body.botId,body.bodyGeneration,body.dimension,body.inventory??null,body.tools??null,body.equipment??null,body.selectedSlot??null])).digest('hex').slice(0,24);
}
export function teamInventory(world:Row,self:string,bot:string,items?:string[],knownIdentity?:Row):Row {
 const b=list(world.companions).find(b=>b.botId===bot)??(knownIdentity?.botId===bot?{botId:bot,name:knownIdentity.name}:undefined),me=list(world.companions).find(b=>b.botId===self);
 if(!b||bot===self)throw Error('teammate unavailable in current world');
 const known=Array.isArray(b.inventory),slots=known?b.inventory.filter((s:Row)=>!items||items.includes(s.item)):undefined;
 return {worldId:world.worldId,botId:b.botId,name:b.name,bodyGeneration:b.bodyGeneration??null,dimension:b.dimension??null,observedAtGameTick:known?world.gameTick:null,checkedAtGameTick:world.gameTick,
  inventoryVersion:inventoryVersion(b),inventoryState:known?(b.inventory.length?'known':'empty'):'unknown',slots:slots??null,
  tools:b.tools??null,equipment:b.equipment??null,selectedSlot:b.selectedSlot??null,
  sameDimension:me?.dimension&&b.dimension?me.dimension===b.dimension:null,immediatelyTransferable:false,requiresAgreement:true,
  note:'只读持有信息，不代表同意交付；实际交接仍需同意、预留、距离与执行器核验。未提供数据不是空背包。'};
}
export function relevantItems(completion:unknown,recipes:unknown,proposals:Cooperation[],bot:string,reservations:unknown=[]):string[] {
 const result=new Set<string>();const add=(v:unknown)=>{if(itemId(v)&&result.size<16)result.add(v);};add(row(completion).resource);
 for(const p of proposals)if([p.requester,p.recipient].includes(bot)&&!['completed','rejected'].includes(p.state))add(p.action.parameters.resource);
 for(const reservation of list(reservations))if(reservation.owner===bot)add(reservation.item);
 // Equivalent recipe cells consume the same stock; compare their combined demand.
 for(const recipe of list(recipes)){
  const groups=new Map<string,{items:string[];required:number;available:number|undefined}>();
  for(const ingredient of list(recipe.ingredients)){
   const items=(ingredient.alternatives??[]).filter(itemId).sort(),key=JSON.stringify(items),prior=groups.get(key);
   groups.set(key,{items,required:(prior?.required??0)+(ingredient.requiredForThisCell??1),available:typeof ingredient.availableMatching==='number'?ingredient.availableMatching:undefined});
  }
  for(const g of groups.values())if(g.available===undefined||g.available<g.required)for(const value of g.items)add(value);
 }
 return [...result];
}
export function resourceSummary(world:Row,self:string,items:string[],proposals:Cooperation[]):Row[] {
 return list(world.companions).filter(b=>b.botId!==self).map(b=>({botId:b.botId,name:b.name,dimension:b.dimension??null,observedAtGameTick:Array.isArray(b.inventory)?world.gameTick:null,inventoryVersion:inventoryVersion(b),inventoryState:Array.isArray(b.inventory)?(b.inventory.length?'known':'empty'):'unknown',
  resources:items.slice(0,16).map(item=>({item,held:Array.isArray(b.inventory)?list(b.inventory).reduce((n,s)=>n+(s.item===item&&Number.isSafeInteger(s.count)?s.count:0),0):null,
   ...reservationSummary(world,b,item),promised:proposals.filter(p=>p.recipient===b.botId&&['accepted','submitted'].includes(p.state)&&p.action.parameters.resource===item).map(p=>({proposalId:p.id,count:p.action.parameters.count??null,recipient:p.requester})),
   note:'协作承诺不是已核实的库存锁；持有和未预留都不等于交付许可。'}))}));
}
function reservationSummary(world:Row,body:Row,item:string):Row {
 const records=list(world.team?.inventoryReservations).filter(r=>r.owner===body.botId&&r.item===item&&r.dimension===body.dimension&&Number.isSafeInteger(r.count)&&r.count>0);
 // Exclusive body/slot locks and old protocols do not establish a free item quantity.
 return {reserved:records.length?records.reduce((n,r)=>n+r.count,0):null,unreserved:null,reservationState:records.length?'confirmed_quantities_free_unknown':'unknown'};
}
export function materialConditionKey(world:Row,peer:string,item:string,count:number):string {
 const b=list(world.companions).find(b=>b.botId===peer);if(!b||!Array.isArray(b.inventory))return 'unknown';
 const held=list(b.inventory).reduce((n,s)=>n+(s.item===item&&Number.isSafeInteger(s.count)?s.count:0),0);
 return `${b.bodyGeneration}:${b.dimension}:${held>=count?'available':'insufficient'}`;
}
