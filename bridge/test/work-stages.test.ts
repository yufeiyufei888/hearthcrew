import test from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,rm} from "node:fs/promises";
import {join} from "node:path";
import {tmpdir} from "node:os";
import {WorkStages,completionSpec,stageSatisfied,type WorkStage} from "../src/work-stages.js";
import {CrewJournal} from "../src/crew-journal.js";
test("stage persists multiple actions; failure and unknown remain unresolved after restart",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"hc-stages-"));
 try{
  const journal=new CrewJournal(join(dir,"events.jsonl"));await journal.load();const stages=new WorkStages(journal);
  const stage:WorkStage={worldId:"world",botId:"bot",intentId:"intent",stageId:"wall",summary:"build wall",scope:"camp boundary",completion:completionSpec({kind:"inventory",resource:"minecraft:stone",count:8}),state:"continue",actions:[]};
  await stages.save(stage);await stages.attach("world","bot","intent","first");await stages.attach("world","bot","intent","second");
  await journal.append("game.event","world",{event:"action.terminal",body:{botId:"other",id:{value:"first"},state:"COMPLETED"}});
  assert.deepEqual(stages.unresolved(stages.current("world","bot","intent")!),["first","second"]);
  await journal.append("game.event","world",{event:"action.terminal",body:{botId:"bot",id:{value:"first"},state:"COMPLETED"}});
  await journal.append("game.event","world",{event:"action.terminal",body:{botId:"bot",id:{value:"second"},state:"PARTIAL"}});
  const restored=new WorkStages(journal);restored.restore();assert.deepEqual(restored.unresolved(restored.current("world","bot","intent")!),["second"]);
  assert.equal(restored.current("world","bot","another-intent"),undefined);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test("recovery resolves a known failed attempt without rewriting its receipt",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"hc-stage-recovery-"));
 try{
  const journal=new CrewJournal(join(dir,"events.jsonl"));await journal.load();const stages=new WorkStages(journal);
  const stage:WorkStage={worldId:"w",botId:"b",intentId:"i",stageId:"s",summary:"stone",scope:"mine",completion:{kind:"inventory",resource:"minecraft:cobblestone",count:8},state:"continue",actions:["failed","partial","unknown"]};
  for(const [id,state,message] of [["failed","FAILED","no accessible interaction stance"],["partial","PARTIAL","no safe player pickup stance"],["unknown","RECONCILE_REQUIRED","unknown world effect"]])
   await journal.append("game.event","w",{event:"action.terminal",body:{botId:"b",id:{value:id},state,message}});
  const next=await stages.resolveVerified(stage,{harvestEvidence:[{actionId:"partial",recovery:"accounted",pendingDrops:[]}]},100);
  assert.deepEqual(stages.unresolved(next),["unknown"]);
  assert.equal((journal.rows("w")[0]!.data.body as {state:string}).state,"FAILED");
  const restored=new WorkStages(journal);restored.restore();assert.deepEqual(restored.unresolved(restored.current("w","b","i")!),["unknown"]);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test("position goals cannot complete mid-jump or on the wrong elevation",()=>{
 const stage:WorkStage={worldId:"w",botId:"b",intentId:"i",stageId:"s",summary:"walk",scope:"walk",completion:{kind:"position",position:{x:0,y:61,z:0},dimension:"minecraft:overworld"},state:"continue",actions:["move"]};
 const body={inventory:[],position:{x:.5,y:60.42,z:.5},dimension:"minecraft:overworld",onGround:false};
 assert.equal(stageSatisfied(stage,body,[]),false);
 assert.equal(stageSatisfied(stage,{...body,onGround:true},[]),false);
 assert.equal(stageSatisfied(stage,{...body,onGround:true,position:{x:.5,y:61,z:.5}},[]),true);
});
test("replacement goal can settle proven drop loss but not mere disappearance",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"hc-loss-"));try{
  const journal=new CrewJournal(join(dir,"events.jsonl"));await journal.load();const stages=new WorkStages(journal);
  const stage:WorkStage={worldId:"w",botId:"b",intentId:"i",stageId:"s",summary:"stone",scope:"mine",completion:{kind:"inventory",resource:"minecraft:cobblestone",count:8},state:"continue",actions:["lost","unknown"]};
  for(const id of stage.actions)await journal.append("game.event","w",{event:"action.terminal",body:{botId:"b",id:{value:id},state:"PARTIAL",message:"drop unavailable"}});
  const next=await stages.resolveVerified(stage,{harvestEvidence:[{actionId:"lost",recovery:"unavailable",pendingDrops:[{availability:"removed",removalEvidence:"entity_removal:DISCARDED"}]},{actionId:"unknown",recovery:"unavailable",pendingDrops:[{availability:"not_observable"}]}]},100);
  assert.deepEqual(stages.unresolved(next),["unknown"]);assert.match(next.resolutions!.lost!.reason,/not pickup/);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test("verified blueprint settles a known partial build but preserves ambiguous effects",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"hc-build-recovery-"));
 try{
  const journal=new CrewJournal(join(dir,"events.jsonl"));await journal.load();const stages=new WorkStages(journal);
  const stage:WorkStage={worldId:"w",botId:"b",intentId:"i",stageId:"s",summary:"wall",scope:"camp",completion:{kind:"blocks",steps:[{position:{x:0,y:64,z:0},block:"minecraft:cobblestone"}]},state:"continue",actions:["partial","unknown"]};
  await journal.append("game.event","w",{event:"action.terminal",body:{botId:"b",id:{value:"partial"},state:"PARTIAL",message:"matching building material unavailable; placed=1; existing=0"}});
  await journal.append("game.event","w",{event:"action.terminal",body:{botId:"b",id:{value:"unknown"},state:"PARTIAL",message:"ambiguous postcondition; placed=1; existing=0"}});
  assert.deepEqual(stages.unresolved(await stages.resolveVerified(stage,{},100)),["unknown"]);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test("completion checks actual inventory, dimension and every blueprint block",()=>{
 const base={worldId:"w",botId:"b",intentId:"i",stageId:"s",summary:"s",scope:"s",state:"continue" as const,actions:["a"]};
 const body={inventory:[{item:"minecraft:stone",count:2}],position:{x:0.5,y:64,z:0.5},dimension:"minecraft:overworld"};
 assert.equal(stageSatisfied({...base,completion:completionSpec({kind:"inventory",resource:"minecraft:stone",count:3})},body,[]),false);
 assert.equal(stageSatisfied({...base,completion:completionSpec({kind:"position",position:{x:0,y:64,z:0},dimension:"minecraft:the_nether"})},body,[]),false);
 const steps=[{position:{x:0,y:64,z:0},block:"minecraft:stone"},{position:{x:0,y:65,z:0},block:"minecraft:stone"}];
 const stage={...base,completion:completionSpec({kind:"blocks",steps})};
 assert.equal(stageSatisfied(stage,body,[steps[0]]),false);assert.equal(stageSatisfied(stage,body,steps),true);
 assert.throws(()=>completionSpec({kind:"blocks",steps:[{position:{x:0,y:64,z:0}}]}));
});
