import {createHash} from 'node:crypto';
import type {CrewJournal} from './crew-journal.js';
import type {CrewChat} from './crew-mailbox.js';
import type {WorkStage} from './work-stages.js';
import type {Cooperation} from './cooperation.js';
export const REPORT_PURPOSES=['coordination','stage_result','help','discovery','danger','answer','progress','social'] as const;
export const STAGE_PURPOSES=['preparation','resource','delivery','public_build','exploration','recovery'] as const;
type Row=Record<string,any>;
export interface ReportContext {world:string;bot:string;intent:string;tick:number;origin:string;goalComplete:boolean;verifiedFacility?:boolean;verifiedFacilityKey?:string;stage?:WorkStage;proposal?:Cooperation;parent?:CrewChat;body:Row;result?:Row;resultRef?:string;condition?:Row;}
export interface ReportDecision {publish:boolean;reason:string;key?:string;recipient?:string;social?:boolean;}
const hash=(v:unknown)=>createHash('sha256').update(JSON.stringify(v)).digest('hex');
export const publicFacilityKey=(dimension:unknown,steps:readonly any[])=>JSON.stringify([dimension,steps.map(s=>[s.position?.x,s.position?.y,s.position?.z,s.block]).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)))]);
const quiet=(reason:string):ReportDecision=>({publish:false,reason});
export function reportDecision(purpose:string,c:ReportContext):ReportDecision {
 if(!REPORT_PURPOSES.includes(purpose as any))throw Error('invalid share purpose');
 const send=(key:string,recipient:string):ReportDecision=>({publish:true,reason:'verified_collaboration_value',key,recipient});
 if(purpose==='progress')return quiet('ordinary_progress_is_internal');
 if(purpose==='answer')return c.parent?.origin==='player'&&c.parent.recipientIds.includes(c.bot)?send(`answer:${c.bot}:${c.parent.messageId}`,'player'):quiet('answer_requires_addressed_player_message');
 const p=c.proposal;
 if(p&&[p.requester,p.recipient].includes(c.bot)&&['coordination','help','stage_result'].includes(purpose)){
  const peer=c.bot===p.requester?p.recipient:p.requester;
  const amount=p.action.parameters.count,resource=p.action.parameters.resource;
  const held=Array.isArray(c.body.inventory)?c.body.inventory.reduce((n:number,s:Row)=>n+(s.item===resource?Number(s.count)||0:0),0):undefined;
  const ready=c.bot===p.recipient&&['materials','transfer'].includes(p.category)&&['accepted','submitted'].includes(p.state)&&typeof amount==='number'&&held!==undefined&&held>=amount;
  // A ready notice confirms possession, never pretends that a transfer happened.
  return send(`cooperation:${hash([p.requester,p.recipient,p.category,p.action,ready?'materials_ready':p.state])}`,peer);
 }
 if(purpose==='stage_result'){
  if(c.verifiedFacilityKey)return send(`facility:${hash(c.verifiedFacilityKey)}`,'team');
  if(c.goalComplete&&c.origin==='owner'&&c.stage?.state==='complete')return send(`goal:${c.intent}:complete`,'player');
  if(c.verifiedFacility&&c.stage?.state==='complete'&&c.stage.purpose==='public_build'&&c.stage.completion.kind==='blocks'&&c.stage.completion.steps.some(s=>/minecraft:(crafting_table|furnace|smoker|blast_furnace|chest|barrel|.*_bed|torch|lantern)$/.test(s.block)))return send(`facility:${hash(publicFacilityKey(c.body.dimension,c.stage.completion.steps))}`,'team');
  return quiet('stage_end_alone_has_no_collaboration_impact');
 }
 if(purpose==='help'){
  const x=c.condition;
  const ownHeld=x&&Array.isArray(c.body.inventory)?c.body.inventory.reduce((n:number,s:Row)=>n+(s.item===x.resource?Number(s.count)||0:0),0):undefined;
  const missing=x?.kind==='materials'&&typeof x.resource==='string'&&Number.isSafeInteger(x.count)&&x.count>0&&ownHeld!==undefined&&ownHeld<x.count;
  const r=c.result, failed=r&&r.botId===c.bot&&!r.historical&&['FAILED','PARTIAL','EXPIRED'].includes(r.state)
   &&((r.epoch?.bodyGeneration??r.epoch?.body)===undefined||(r.epoch?.bodyGeneration??r.epoch?.body)===c.body.bodyGeneration)
   &&(r.intentId===c.intent||c.resultRef?.startsWith(`${c.intent}:`));
  if(x&&x.peerId&&(missing||failed&&['path','teammate','processing'].includes(x.kind)))return send(`help:${hash([c.intent,x.kind,x.resource,x.count,x.position,x.peerId,x.taskId])}`,x.peerId);
  return quiet('help_requires_actual_blocker_and_affected_peer');
 }
 if(purpose==='danger'){
  const s=c.body.localSafety;
  if(s&&s.active===true)return send(`danger:${hash([c.body.bodyGeneration,c.body.action?.id?.value,s.threatId??s.targetId??s.reason??s.kind])}`,'team');
  return quiet('no_current_verified_danger');
 }
 const r=c.result;
 const validResult=r&&r.botId===c.bot&&!r.historical&&r.state==='COMPLETED'
  && ((r.epoch?.bodyGeneration??r.epoch?.body)===undefined||(r.epoch?.bodyGeneration??r.epoch?.body)===c.body.bodyGeneration)
  && (r.intentId===c.intent||c.stage?.actions.includes(c.resultRef??'')||c.resultRef?.startsWith(`${c.intent}:`));
 if(purpose==='discovery'&&validResult&&r.payload?.kind==='EXPLORE'&&/new|observ|region/i.test(String(r.message)))return send(`discovery:${c.resultRef}`,'team');
 if(purpose==='social'&&validResult&&['EXPLORE','BUILD','SAIL','SELF_DEFENCE','GATHER','CRAFT'].includes(r.payload?.kind))return {...send(`experience:${c.resultRef}`,'team'),social:true};
 return quiet('no_verified_shared_dependency_or_result');
}
/** Durable notification identity and game-clock social limits; independent of work scheduling. */
export class ReportingPolicy {
 private sent=new Set<string>();private lastBot=new Map<string,number>();private lastTeam=new Map<string,number>();
 constructor(private journal:CrewJournal){}
 restore(){for(const r of this.journal.select(undefined,r=>r.type==='report.claimed',Number.MAX_SAFE_INTEGER)){this.sent.add(`${r.worldId}:${r.data.key}`);if(r.data.social===true){this.lastBot.set(`${r.worldId}:${r.data.botId}`,Number(r.data.tick));this.lastTeam.set(r.worldId,Number(r.data.tick));}}}
 async claim(world:string,bot:string,tick:number,d:ReportDecision):Promise<ReportDecision>{
  if(!d.publish||!d.key)return d;const key=`${world}:${d.key}`;
  if(this.sent.has(key))return quiet('same_event_already_notified');
  if(d.social&&(tick-(this.lastBot.get(`${world}:${bot}`)??-Infinity)<6000||tick-(this.lastTeam.get(world)??-Infinity)<1200))return quiet('social_game_time_limit');
  this.sent.add(key);if(d.social){this.lastBot.set(`${world}:${bot}`,tick);this.lastTeam.set(world,tick);}
  await this.journal.append('report.claimed',world,{key:d.key,botId:bot,tick,social:!!d.social,recipient:d.recipient});return d;
 }
}
export const REPORT_RULES='公开发言按是否影响别人下一步判断，不按任务简单程度或阶段结束判断。自用木材、木棍、工具、逐块采掘放置和可自行恢复的失败默认安静执行，purpose=progress只进诊断。阶段结束并不要求share。协作通知引用proposalId；求助引用真实condition（materials提供resource/count/peerId；路径、加工问题另引用当前失败动作resultRef）；回答玩家引用replyTo；公共设施结果可引用真实PLACE/BUILD完成动作resultRef（无需先建立阶段），或已核验public_build阶段；玩家总目标完成经goal_state complete核验后可向player总结；发现或闲聊引用本角色真实已完成actionId作为resultRef。缺省purpose为progress。少量闲聊只能基于真实经历，不能换用途逃避静默。建议一两句80字内，最多200字，每回合至多两条；public:false是正常静默结果，继续工作，不重试措辞、不再开回合。没有必要回复纯进度“收到”。库存通过teamResources摘要或observe.teamInventory查询，不用聊天广播数量；查询不代表同意交付。示例：observe({teamInventory:{companion:"Moss",items:["minecraft:stick"]}})；stage可选stagePurpose=preparation/resource/delivery/public_build/exploration/recovery，分类不改变汇报资格。材料等待condition使用kind=materials、resource、count及peerId，由内部阈值事件唤醒。';
