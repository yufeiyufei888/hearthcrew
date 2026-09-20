import test from 'node:test';import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';
import {reportDecision,ReportingPolicy,publicFacilityKey,type ReportContext} from '../src/reporting-policy.js';
import {CrewJournal} from '../src/crew-journal.js';
const context:ReportContext={world:'w',bot:'b',intent:'i',tick:100,origin:'owner',goalComplete:false,body:{bodyGeneration:2},stage:{worldId:'w',botId:'b',intentId:'i',stageId:'s',summary:'做4根木棍',scope:'self',completion:{kind:'inventory',resource:'minecraft:stick',count:4},actions:['a'],state:'complete'}};

test('verified public facility can be reported without inventing a stage',()=>{
 const c={...context,stage:undefined,verifiedFacilityKey:'overworld/chest/1,64,1'};
 const first=reportDecision('stage_result',c);
 assert.equal(first.publish,true);assert.equal(first.recipient,'team');
 assert.equal(reportDecision('stage_result',{...c,intent:'another',stage:{...context.stage!,stageId:'new'}}).key,first.key);
 assert.equal(reportDecision('progress',c).publish,false);
 assert.equal(reportDecision('stage_result',{...c,verifiedFacilityKey:undefined}).publish,false);
});
test('facility identity stays the same across direct receipt and stage reports',()=>{
 const steps=[{position:{x:1,y:64,z:1},block:'minecraft:chest'}];
 const c={...context,body:{dimension:'minecraft:overworld'},stage:{...context.stage!,purpose:'public_build' as const,completion:{kind:'blocks' as const,steps}}};
 assert.equal(reportDecision('stage_result',{...c,verifiedFacility:true}).key,
  reportDecision('stage_result',{...c,stage:undefined,verifiedFacilityKey:publicFacilityKey(c.body.dimension,steps)}).key);
});
test('expired current-body mining can request directed help, old bodies cannot',()=>{
 const c={...context,result:{botId:'b',state:'EXPIRED',intentId:'i',epoch:{bodyGeneration:2}},condition:{kind:'path',peerId:'a'},resultRef:'i:coal'};
 assert.equal(reportDecision('help',c).recipient,'a');
 assert.equal(reportDecision('help',{...c,result:{...c.result,epoch:{bodyGeneration:1}}}).publish,false);
});
test('basic progress and unrequested stage ends stay internal even after relabeling',()=>{
 for(const p of ['progress','coordination','stage_result','help','discovery','answer','danger','social'])assert.equal(reportDecision(p,context).publish,false,p);
 assert.equal(reportDecision('stage_result',{...context,stage:{...context.stage!,stageId:'new',purpose:'public_build'}}).publish,false);
});
test('delivery notices are directed and insensitive to invented proposal IDs',()=>{
 const proposal:any={id:'p',requester:'a',recipient:'b',state:'accepted',category:'materials',action:{kind:'TRANSFER',parameters:{resource:'minecraft:stick',count:4}}};
 const a=reportDecision('coordination',{...context,proposal});assert.equal(a.recipient,'a');assert.equal(a.publish,true);
 assert.equal(reportDecision('stage_result',{...context,proposal:{...proposal,id:'another'}}).key,a.key);
 assert.notEqual(reportDecision('stage_result',{...context,proposal:{...proposal,state:'completed'}}).key,a.key);
 const ready=reportDecision('stage_result',{...context,proposal,body:{inventory:[{item:'minecraft:stick',count:4}]}});
 assert.notEqual(ready.key,a.key);assert.equal(ready.recipient,'a');
 assert.equal(reportDecision('stage_result',{...context,proposal,body:{inventory:[{item:'minecraft:stick',count:5}]}}).key,ready.key);
});

test('old body results and unverified facilities cannot be relabeled into public evidence',()=>{
 const result={botId:'b',state:'COMPLETED',intentId:'i',epoch:{body:2},payload:{kind:'EXPLORE'},message:'new observed region'};
 assert.equal(reportDecision('discovery',{...context,result,resultRef:'i:explore'}).publish,true);
 assert.equal(reportDecision('social',{...context,result:{...result,epoch:{body:1}},resultRef:'i:explore'}).publish,false);
 assert.equal(reportDecision('discovery',{...context,result:{...result,historical:true},resultRef:'i:explore'}).publish,false);
 const stage:any={...context.stage,purpose:'public_build',completion:{kind:'blocks',steps:[{position:{x:1,y:64,z:1},block:'minecraft:furnace'}]}};
 assert.equal(reportDecision('stage_result',{...context,stage}).publish,false);
});
test('owner completion and real public facilities have distinct verified routes',()=>{
 assert.equal(reportDecision('stage_result',{...context,goalComplete:true}).recipient,'player');
 const stage:any={...context.stage,purpose:'public_build',completion:{kind:'blocks',steps:[{position:{x:1,y:64,z:1},block:'minecraft:furnace'}]}};
 assert.equal(reportDecision('stage_result',{...context,stage,verifiedFacility:true}).recipient,'team');
 assert.equal(reportDecision('stage_result',{...context,stage:{...stage,state:'blocked'}}).publish,false);
 assert.equal(reportDecision('stage_result',{...context,stage:{...stage,completion:{kind:'blocks',steps:[{position:{x:1,y:64,z:1},block:'minecraft:dirt'}]}}}).publish,false);
});
test('actual danger, addressed answers and directed blockers can speak',()=>{
 assert.equal(reportDecision('danger',{...context,body:{localSafety:{active:true,threatId:'z'}}}).publish,true);
 const parent:any={messageId:'m',origin:'player',recipientIds:['b']};assert.equal(reportDecision('answer',{...context,parent}).recipient,'player');
 assert.equal(reportDecision('answer',{...context,parent:{...parent,recipientIds:['other']}}).publish,false);
 assert.equal(reportDecision('help',{...context,body:{inventory:[]},condition:{kind:'materials',resource:'minecraft:coal',count:8,peerId:'a'}}).recipient,'a');
 assert.equal(reportDecision('help',{...context,body:{inventory:[{item:'minecraft:coal',count:8}]},condition:{kind:'materials',resource:'minecraft:coal',count:8,peerId:'a'}}).publish,false);
});
test('durable claims, game-time social quotas and worlds remain separate',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'hc-report-'));try{
  const j=new CrewJournal(join(dir,'events'));await j.load();let policy=new ReportingPolicy(j);
  const social=(key:string)=>({publish:true,reason:'experience',key,recipient:'team',social:true});
  assert.equal((await policy.claim('w','b',100,social('one'))).publish,true);
  assert.equal((await policy.claim('w','b',100,social('two'))).publish,false);
  assert.equal((await policy.claim('w','a',1299,social('three'))).publish,false);
  assert.equal((await policy.claim('w','a',1300,social('three'))).publish,true);
  policy=new ReportingPolicy(j);policy.restore();assert.equal((await policy.claim('w','b',6099,social('two'))).publish,false);
  assert.equal((await policy.claim('w','b',6100,social('two'))).publish,true);
  assert.equal((await policy.claim('w','b',9999,social('one'))).publish,false);
  assert.equal((await policy.claim('other','b',1,social('one'))).publish,true);
 }finally{await rm(dir,{recursive:true,force:true,maxRetries:10,retryDelay:50});}
});
