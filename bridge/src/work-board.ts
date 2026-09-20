/** Read-only task presentation. Never infer an active action from the historical journal. */
type Row=Record<string,any>;
const obj=(v:unknown):Row=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Row:{};
const list=(v:unknown):Row[]=>Array.isArray(v)?v.map(obj):[];
const id=(v:unknown):string=>typeof v==='string'?v:typeof obj(v).value==='string'?obj(v).value:'';
const num=(v:unknown)=>typeof v==='number'&&Number.isFinite(v)?v:undefined;
export const shortText=(v:string,n=48)=>{const s=v.split(/[。\n]/)[0];return Array.from(s).length>n?Array.from(s).slice(0,n).join('')+'…':s;};
const stagePurposes:Record<string,string>={preparation:'内部准备',resource:'资源获取',delivery:'协作交付',public_build:'公共建设',exploration:'探索',recovery:'恢复'};
const purposeText=(s:Row)=>stagePurposes[s.purpose]?`用途：${stagePurposes[s.purpose]}；分类不代表需要公开汇报。\n`:'';
const labels:Record<string,string>={MOVE:'前往目标',EXPLORE:'探索环境',MINE:'连续采掘',COLLECT_RESOURCE:'采集资源',GATHER:'采集木材',PICKUP:'回收掉落',CRAFT:'制作物品',BUILD:'按蓝图施工',PLACE:'放置方块',PROCESS:'投入加工材料',COLLECT_PROCESS:'收取加工产物',SELECT:'选择物品',EQUIP:'装备物品',TRANSFER:'交付物品',STORE:'存入公共容器',TAKE:'从公共容器取物',WAIT:'短暂等待',BREATHE:'换气并寻找上岸点',SELF_DEFEND:'本地自卫',YIELD:'给队友让路',EXCAVATE:'挖掘通路',SWIM:'游向目标',SAIL:'驾驶船只',LAUNCH_BOAT:'放置船只',SEQUENCE:'执行准备步骤',FOLLOW:'跟随目标',GUARD:'护卫目标',EAT:'进食',SLEEP:'睡觉',ATTACK:'攻击目标',PORTAL:'穿越传送门',USE_ITEM:'使用物品',INTERACT:'操作目标',BOARD_BOAT:'登船',DISEMBARK:'靠岸下船'};
const phases:Record<string,string>={searching_workstation:'寻找合法工位',preparing_tool:'准备替换工具',placing_preparation_station:'放置准备工位',resuming_task:'恢复原任务',preparing_resource:'准备材料与工具',collecting_resource:'连续采集',waiting_processing:'等待炉子真实加工',preparation_scanning:'查找准备材料',resource_searching:'筛选资源目标',exploration_path_searching:'寻找前方通路',access_searching:'搜索安全通路',access_excavating:'挖掘接近通路',access_approaching:'沿通路接近',excavation_following:'沿通路前进',recovering_drops:'回收掉落',pickup_delayed:'等待原版拾取延迟',exploration_observing:'观察探索终点',mining:'连续采掘',scanning:'扫描资源'};
export interface WorkCard {botId:string;name:string;intentId:string;stageId:string;state:string;goal:string;fullGoal:string;stage:string;fullStage:string;step:string;progress:string;waitReason:string;source:string;completion:string;pending:string[];actionId:string;}
export interface StageRow {key:string;title:string;summary:string;state:string;reason:string;completion:string;attempts:string[];}
export function completionText(raw:unknown,clean:(v:unknown)=>string):string {
 const c=obj(raw);if(c.kind==='inventory')return `库存达到 ${num(c.count)??'未同步'} × ${clean(c.resource)}`;
 if(c.kind==='blocks')return `核验蓝图中的 ${list(c.steps).length} 个方块`;
 if(c.kind==='position'){const p=obj(c.position);return `实际到达 ${[p.x,p.y,p.z].map(n=>num(n)===undefined?'未同步':Math.floor(n)).join(', ')}（${clean(c.dimension)}）`;}
 return '尚未制定';
}
export function workBoard(raw:unknown,clean:(v:unknown)=>string):WorkCard[]{
 const root=obj(raw),game=obj(root.game),roles=list(root.roles);const names=['Ember','Moss','Flint'];
 return list(game.companions).slice(0,3).sort((a,b)=>(names.indexOf(a.name)<0?9:names.indexOf(a.name))-(names.indexOf(b.name)<0?9:names.indexOf(b.name))).map(body=>{
  const role=roles.find(r=>r.botId===body.botId)??{},s=obj(role.taskStage),a=obj(body.action),epoch=obj(a.epoch);
  const active=['ACCEPTED','RUNNING'].includes(a.state)&&num(epoch.bodyGeneration)!==undefined&&epoch.bodyGeneration===body.bodyGeneration&&!body.stopped&&!body.paused;
  const p=obj(a.payload),execution=obj(body.execution??a.execution),travel=obj(body.travel);
  const preparation=obj(execution.preparation),condition=typeof preparation.condition==='string'?preparation.condition.split(':')[0]:'';
  const preparationLabels:Record<string,string>={SEARCHING_FACILITY:'寻找合法工位',SCANNING_PREPARATION_RESOURCES:'查找准备材料',APPROACHING_FACILITY:'前往工作台',RETURNING_TO_FACILITY:'返回工作台',SETTLING_AT_FACILITY:'核对工位站位',PLACING_OWN_FACILITY:'放置自有工作台',PREPARING_ITEM:'按配方制作物品',ACQUIRING_PREPARATION_MATERIAL:'连续采集材料',RECOVERING_STEP_DROPS:'接近并回收掉落',RECOVERING_PRIOR_DROPS:'核对待回收物品',REPLANNING_BROKEN_TOOL:'准备替换工具',CONTINUING_VERIFIED_RESOURCE_SHORTFALL:'继续补采实际差额',RESUMING_PREPARATION:'继续原任务'};
  let step=active?(preparationLabels[condition]??phases[travel.phase]??phases[execution.phase]??labels[p.kind]??clean(p.kind)):'正在规划下一步';
  const navigation=obj(execution.navigation),safety=obj(body.localSafety);
  const navigationLabels:Record<string,string>={SEARCHING:'寻找通路',REPLANNING:'重新规划通路',APPROACHING:'接近目标',COLLECTING:'采掘与回收'};
  if(active&&['ACQUIRING_PREPARATION_MATERIAL','APPROACHING_FACILITY','RETURNING_TO_FACILITY',''].includes(condition)&&navigationLabels[navigation.phase])step=navigationLabels[navigation.phase];
  if(active&&condition==='PLANNING_PREPARATION')step='核对材料与准备方案';
  if(active&&execution.persistence==='WAITING_FOR_DURABILITY')step='等待执行记录写盘';
  if(safety.active===true&&!body.paused&&!body.stopped)step=({SURFACING:'求生：上浮恢复空气',FINDING_SHORE:'求生：寻找安全岸点',APPROACHING_SHORE:'求生：游向锁定岸点',VERIFYING_SAFE_SHORE:'求生：核对岸上稳定状态',WAITING_NO_SAFE_SHORE:'求生：浮水等待，无已确认岸点'} as Record<string,string>)[safety.reaction]??'临时求生，原工作保留';
  const suspended=list(body.actionJournal).filter(a=>a.state==='SUSPENDED');
  if(active&&['SELF_DEFEND','BREATHE'].includes(p.kind))step=`临时求生：${step}；原目标保留`;
  if(active&&p.kind==='COLLECT_RESOURCE'&&execution.childKind)step=`${execution.phase==='preparing_resource'?'准备':'采集'}：${labels[execution.childKind]??'执行子步骤'}`;
  if(active&&p.kind==='SEQUENCE'){const at=num(execution.sequenceIndex);const child=list(p.actions)[at??0];step=`准备步骤 ${at===undefined?'待同步':at+1}/${list(p.actions).length}：${labels[child?.kind]??'等待步骤回执'}`;}
  if(active&&p.resource)step+=` · ${clean(p.resource)}`;
  if(active&&p.position){const pos=obj(p.position);step+=`（${[pos.x,pos.y,pos.z].map(n=>num(n)===undefined?'未同步':Math.floor(n)).join(', ')}）`;}
  let state=body.recoveryInvalid?'reconcile_required':body.stopped?'stopped':body.paused?'paused':body.standby||role.activity==='standby'?'standby':active?'working':role.activity??'idle';
  const wait=active?'':typeof role.waitReason==='string'&&role.waitReason!=='unknown'?clean(role.waitReason):s.state==='blocked'?clean(s.reason):'';
  if(!active&&wait)step='等待条件';if(['paused','stopped','standby'].includes(state))step=state==='paused'?'已暂停':state==='stopped'?'已急停':'等待新安排';
  if(!active&&state==='thinking')step='正在规划下一步';
  if(!active&&suspended.length&&state==='idle')step='原工作已挂起，等待核对';
  let progress='暂无可核验数量';
  const stageActions=new Set(Array.isArray(s.actions)?s.actions:[]);const evidence=new Map<string,Row>();
  for(const h of list(body.harvestEvidence))if(stageActions.has(h.actionId)||h.actionId===id(a.id))evidence.set(h.actionId,h);
  if(id(a.id)&&Object.keys(execution).length&&active)evidence.set(id(a.id),{...evidence.get(id(a.id)),...execution});
  const resource=obj(s.completion).resource??(p.kind==='COLLECT_RESOURCE'?p.resource:undefined);let own=0,other=0,known=false,pending=0;
  for(const h of evidence.values()){if(h.acquired||h.acquiredByOthers){known=true;own+=resource?(num(obj(h.acquired)[resource])??0):Object.values(obj(h.acquired)).reduce<number>((n,v)=>n+(num(v)??0),0);other+=resource?(num(obj(h.acquiredByOthers)[resource])??0):Object.values(obj(h.acquiredByOthers)).reduce<number>((n,v)=>n+(num(v)??0),0);}pending+=list(h.pendingDrops).length;}
  if(known)progress=`本阶段本人取得 ${own}，他人取得 ${other}，待回收 ${pending} 组`;
  else if(active&&p.kind==='BUILD')progress=`蓝图 ${list(p.steps).length} 步；实际放置 ${num(execution.placed)??'未同步'}`;
  else if(active&&p.kind==='WAIT')progress=`等待上限 ${num(p.count)===undefined?'未同步':p.count/20} 游戏秒`;
  else if(active&&['CRAFT','PROCESS','COLLECT_PROCESS'].includes(p.kind))progress='以真实产物回执结算；投料不等于产出';
  if(num(execution.preparationSteps)!==undefined)progress+=`；已确认准备 ${execution.preparationSteps} 步`;
  if(num(execution.accessBroken)!==undefined)progress+=`；辅助开路 ${execution.accessBroken}/${p.accessBudget??'未同步'}`;
  const goalOutput=obj(preparation.goalOutput);
  if(active&&num(goalOutput.ownNew)!==undefined&&num(goalOutput.teamNew)!==undefined&&num(goalOutput.requestedNew)!==undefined){
    const unsettled=new Set(list(goalOutput.sources).flatMap(s=>list(s.pendingEvidence).map(e=>id(e.entityId))));unsettled.delete('');
    progress=`当前任务本人取得 ${goalOutput.ownNew}，队友取得 ${goalOutput.teamNew}，目标 ${goalOutput.requestedNew}；待核对 ${unsettled.size} 组`;
    if(num(execution.accessSpent)!==undefined)progress+=`；辅助开路 ${execution.accessSpent}/${execution.accessBudget??'未同步'}`;
  }
  if(resource&&Array.isArray(body.inventory)){const held=list(body.inventory).reduce((n,x)=>n+(x.item===resource?(num(x.count)??0):0),0);progress+=`；当前持有 ${held}`;}
  const fullGoal=clean(role.rootGoal??role.goal??'尚未制定'),fullStage=purposeText(s)+clean(s.summary??'尚未制定');
  return {botId:id(body.botId),name:clean(body.name),intentId:id(role.intentId),stageId:id(s.stageId),state,goal:shortText(fullGoal),fullGoal,stage:shortText(clean(s.title??s.summary??'尚未制定')),fullStage,step,progress,waitReason:wait,source:role.intentOrigin==='owner'?'玩家任务':role.intentOrigin==='autonomous'?'自主任务':'来源未同步',completion:completionText(s.completion,clean),pending:list(role.pendingRequests).slice(0,8).map(r=>clean(r.message)),actionId:active?id(a.id):''};
 });
}
export function stageRows(stages:unknown,body:unknown,clean:(v:unknown)=>string):StageRow[]{
 const b=obj(body);const latest=new Map<string,Row>();
 for(const a of list(b.actionJournal)){const k=`${obj(a.epoch).bodyGeneration}:${id(a.id)}`,old=latest.get(k);if(!old||!['COMPLETED','FAILED','CANCELLED','PARTIAL'].includes(old.state))latest.set(k,a);}
 return list(stages).map(s=>({key:`${s.worldId}:${s.botId}:${s.intentId}:${s.stageId}`,title:shortText(clean(s.title??s.summary)),summary:purposeText(s)+clean(s.summary),state:clean(s.state),reason:clean(s.reason??''),completion:completionText(s.completion,clean),attempts:(Array.isArray(s.actions)?s.actions:[]).slice(-8).map((action:string)=>{const rows=[...latest.values()].filter(a=>id(a.id)===action);const a=rows.at(-1);return a?`${labels[obj(a.payload).kind]??clean(obj(a.payload).kind)} · ${clean(a.state)} · ${clean(a.message??'')}`:'历史动作详情未同步';})}));
}
