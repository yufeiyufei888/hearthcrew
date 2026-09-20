import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CrewController, ACTION_CAPABILITIES, type BrainPort, type WorldView } from "../src/crew-controller.js";
import { CrewJournal } from "../src/crew-journal.js";
import { AGENT_PROFILES, type StartedThread, type TurnHandle } from "../src/app-server-client.js";
import type { ModConnection } from "../src/mod-connection.js";
import { makeUiView } from "../src/ui-view.js";

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
async function until(check: () => boolean) { for (let i=0;i<400;i++) { if(check()) return; await sleep(5); } throw new Error("condition timed out"); }
class Link extends EventEmitter {
  connectionState = "ready";
  sessionContext = { protocol: "hearthcrew.v1", worldId: "world", sessionEpoch: 1 };
  calls: {op:string; body:any; options:any}[]=[];
  handler?: (event:any)=>Promise<boolean>;
  constructor(public view: WorldView) {super();}
  close() { this.emit("disconnect"); }
  onEvent(fn:any) {this.handler=fn;}
  async request<T>(op:string, body:any, options:any={}):Promise<T> {
    this.calls.push({op,body,options});
    if (["status","observe","reconcile"].includes(op)) return structuredClone(this.view) as T;
    const b=this.view.companions.find(b=>b.botId===body.botId);
    if(op==="intent.bind") {if(b)b.intentBinding={intentId:options.intentId,origin:body.origin,intentGeneration:body.intentGeneration}; return {} as T;}
    if(op==="action.submit") { if(b)b.action={id:{value:options.actionId},state:"RUNNING",payload:body}; return {state:"ACCEPTED",actionId:options.actionId} as T;}
    if(op==="chat.publish") return {delivered:true} as T;
    if(op==="control") { for(const target of this.view.companions.filter(b=>!body.botId||b.botId===body.botId)) {
      if(body.operation==="pause")target.paused=true;
      if(body.operation==="stop")target.stopped=true;
      if(body.operation==="standby")target.standby=true;
      if(body.operation==="retask"){target.action=null;target.standby=false;target.paused=false;target.stopped=false;}
    } return {count:1} as T; }
    if(op==="action.cancel") {if(b)b.action=null;return {state:"CANCELLED"} as T;}
    throw new Error(`unexpected ${op}`);
  }
  async terminal(id:string, actionId:string, state="FAILED", botId="bot-0") {
    const body={botId,id:{value:actionId},state,message:"no approved tree; collected=0",payload:{kind:"GATHER"},epoch:{bodyGeneration:7},gatherDiagnostics:{phase:"finished",candidates:3,eligibleRoots:0,requested:3,collected:0,rejections:{tree_shape_or_canopy:3},examples:{tree_shape_or_canopy:{x:5,y:64,z:0}}}};
    const b=this.view.companions.find(b=>b.botId===botId)!;b.action=null;b.actionJournal=[...(b.actionJournal??[]),body];
    return this.handler!({...this.sessionContext,kind:"event",eventId:id,event:"action.terminal",body});
  }
}
class Brain implements BrainPort {
  turns: {handle:TurnHandle; input:any; resolve:(value:any)=>void}[]=[];
  active=new Set<string>(); hangInterrupt=false; overlaps=0;
  async createThread(id:any):Promise<StartedThread> { const p=AGENT_PROFILES.find(p=>p.id===id)!;return {threadId:`thread-${id}`,profile:p,model:p.model,reasoningEffort:p.reasoningEffort,effectiveServiceTier:"default"}; }
  async startTurn(thread:StartedThread,input:string):Promise<TurnHandle> {
    if(this.active.has(thread.threadId)){this.overlaps++;throw new Error("overlapping turn");}
    this.active.add(thread.threadId);
    let done!:(v:any)=>void;
    const completion=new Promise(resolve=>{done=v=>{this.active.delete(thread.threadId);resolve(v);};});
    const handle={threadId:thread.threadId,turnId:`turn-${this.turns.length}`,completion,interrupt:async()=>{if(!this.hangInterrupt)done({status:"interrupted"});}};
    this.turns.push({handle,input:JSON.parse(input),resolve:done}); return handle;
  }
  diagnostics(){return {};}
}
async function fixture(count=1,timeout=1500) {
  const dir=await mkdtemp(join(tmpdir(),"hearthcrew-recovery-"));
  const journal=new CrewJournal(join(dir,"events.jsonl"));
  const controller=new CrewController(journal,timeout,[20,40]);
  const link=new Link({worldId:"world",gameTick:1,companions:Array.from({length:count},(_,i)=>({botId:`bot-${i}`,name:["Ember","Moss","Flint"][i],bodyGeneration:7,dimension:"minecraft:overworld",position:{x:i,y:64,z:0},inventory:[],action:null,autonomyEnabled:true,standby:false}))});
  const brain=new Brain(); await controller.initialize();await controller.setBrain(brain);await controller.attach(link as unknown as ModConnection);await until(()=>brain.turns.length===count);
  const call=async(index:number,tool:string,args:any)=>controller.toolCall({threadId:brain.turns[index].handle.threadId,turnId:brain.turns[index].handle.turnId,tool,arguments:args}) as Promise<any>;
  const close=async()=>{link.close();for(const turn of brain.turns)turn.resolve({status:"interrupted"});await sleep(30);await rm(dir,{recursive:true,force:true});};
  return {controller,journal,link,brain,call,close};
}

test("failed body result interrupts normally, reaches next work turn, and persists until consumed",async()=>{
 const f=await fixture();try{
  const a=await f.call(0,"act",{actionId:"wood",kind:"GATHER",parameters:{resource:"minecraft:oak_log",count:3}});assert.equal(a.success,true);
  const id=f.link.calls.find(c=>c.op==="action.submit")!.options.actionId;
  await f.link.terminal("result-a",id); await until(()=>f.brain.turns.length===2);
  assert.equal(f.brain.turns[1].input.communicationOnly,false);assert.equal(f.brain.turns[1].input.actionResults[0].eventId,"result-a");
  assert.equal(f.journal.rows().filter(r=>r.type==="role.failure").length,0);
  assert.equal(f.journal.rows().filter(r=>r.type==="action.results_consumed").length,0);
  const refused=await f.call(1,"act",{actionId:"duplicate-different-id",kind:"GATHER",parameters:{resource:"minecraft:oak_log",count:3}});
  assert.equal(refused.success,false);assert.match(refused.contentItems[0].text,/UNCHANGED_FAILED_ACTION/);
  await f.call(1,"propose_work",{operation:"goal_state",state:"blocked",reason:"choose another location after owner confirmation"});
  f.brain.turns[1].resolve({status:"completed"});await until(()=>f.journal.rows().some(r=>r.type==="action.results_consumed"));
  assert.equal(f.brain.overlaps,0);
 }finally{await f.close();}
});

test("timeout recovers twice after actual turn end and stops without polling chat",async()=>{
 const f=await fixture(1,100);try{
  await until(()=>f.brain.turns.length===3);await until(()=>f.journal.rows().filter(r=>r.type==="role.failure").length===3);
  await sleep(80);assert.equal(f.brain.turns.length,3);assert.equal(f.brain.overlaps,0);
  assert.deepEqual(f.journal.rows().filter(r=>r.type==="turn.recovery").map(r=>r.data.attempt),[1,2]);
  assert.equal((await f.controller.status() as any).roles[0].status,"failed");
 }finally{await f.close();}
});

test("unconfirmed interrupt cannot start a replacement; late tools are rejected",async()=>{
 const f=await fixture(1,80);try{
  f.brain.hangInterrupt=true;await until(()=>f.journal.rows().some(r=>r.type==="role.failure"));await sleep(80);
  assert.equal(f.brain.turns.length,1);assert.equal((await f.call(0,"act",{actionId:"late",kind:"WAIT"})).success,false);
  f.brain.turns[0].resolve({status:"interrupted"});await until(()=>f.brain.turns.length===2);assert.equal(f.brain.overlaps,0);
 }finally{await f.close();}
});

test("pause cancels scheduled recovery and retains queued results",async()=>{
 const f=await fixture(1,100);try{
  await until(()=>f.journal.rows().some(r=>r.type==="turn.recovery"));await f.controller.control("pause");await sleep(100);assert.equal(f.brain.turns.length,1);
 }finally{await f.close();}
});

test("active body allows communication but no new action or team mutation",async()=>{
 const f=await fixture();try{
  await f.call(0,"act",{actionId:"walk",kind:"MOVE",parameters:{position:{x:5,y:64,z:0}}});f.brain.turns[0].resolve({status:"completed"});
  await until(()=>!(f.controller as any).roles.get("coordinator").dispatching);
  const role=(f.controller as any).roles.get("coordinator");
  await (f.controller as any).mailbox.append({worldId:"world",messageId:"help",origin:"player",senderId:"owner",senderName:"player",recipientIds:["bot-0"],recipientNames:["Ember"],message:"现在怎么样",depth:0,gameTick:2});
  (f.controller as any).trigger(role,"chat_message",{});await until(()=>f.brain.turns.length===2);
  assert.equal(f.brain.turns[1].input.communicationOnly,true);
  assert.equal((await f.call(1,"act",{actionId:"bad",kind:"WAIT"})).success,false);
  assert.equal((await f.call(1,"propose_work",{taskId:"bad",kind:"MOVE",recipient:"coordinator",summary:"bad"})).success,false);
  assert.equal((await f.call(1,"share",{recipient:"player",message:"正在行走"})).success,true);
  assert.equal(f.link.calls.filter(c=>c.op==="action.submit").length,1);
 }finally{await f.close();}
});

test("capabilities distinguish delegation and gather diagnostics survive UI projection",async()=>{
 const f=await fixture();try{
  const observed=await f.call(0,"observe",{});assert.deepEqual(JSON.parse(observed.contentItems[0].text).capabilities,ACTION_CAPABILITIES);
  const reject=await f.call(0,"propose_work",{taskId:"wood",recipient:"coordinator",kind:"GATHER",summary:"collect wood"});assert.equal(reject.success,false);assert.match(reject.contentItems[0].text,/TEAM_ACTION_UNSUPPORTED/);
  await f.link.terminal("diagnostic","older-action");
  const view=makeUiView(await f.controller.status(),f.journal.rows());assert.equal(view.messages.some(m=>m.message.includes("tree_shape_or_canopy")),false,"technical receipts stay out of chat");
  assert.match(JSON.stringify(f.journal.rows()),/tree_shape_or_canopy/);assert.equal((f.controller as any).resultQueue("world","bot-0").get("diagnostic").gatherDiagnostics.collected,0);
 }finally{await f.close();}
});

test("one role timeout does not cancel teammates body work",async()=>{
 const f=await fixture(3,180);try{
  for(const i of [1,2]) {await f.call(i,"act",{actionId:`walk-${i}`,kind:"MOVE",parameters:{position:{x:5,y:64,z:i}}});f.brain.turns[i].resolve({status:"completed"});}
  await until(()=>f.journal.rows().some(r=>r.type==="role.failure"));
  assert.equal(f.link.calls.filter(c=>c.op==="action.cancel").length,0);
  const status=await f.controller.status() as any;assert.equal(status.roles.filter((r:any)=>r.status==="working").length,2);
 }finally{await f.close();}
});

for (const operation of ["standby", "stop", "disconnect"] as const) test(`${operation} prevents scheduled recovery from waking the world`,async()=>{
 const f=await fixture(1,100);try{
  await until(()=>f.journal.rows().some(r=>r.type==="turn.recovery"));
  if(operation==="disconnect")f.link.close();else await f.controller.control(operation);
  await sleep(120);assert.equal(f.brain.turns.length,1);assert.equal(f.link.calls.filter(c=>c.op==="action.submit").length,0);
 }finally{await f.close();}
});

test("unconsumed terminal results and gather diagnostics restore from the durable journal",async()=>{
 const f=await fixture();try{
  await f.link.terminal("persisted-result","old-action");f.link.close();
  for(const turn of f.brain.turns)turn.resolve({status:"interrupted"});await sleep(30);
  const restored=new CrewController(f.journal);await restored.initialize();
  const result=(restored as any).resultQueue("world","bot-0").get("persisted-result");
  assert.equal(result.state,"FAILED");assert.equal(result.gatherDiagnostics.collected,0);
  assert.equal(f.journal.rows().some(r=>r.type==="action.results_consumed"),false);
 }finally{await f.close();}
});

test("new player command waits for an old timed-out turn and then resumes only the new intent",async()=>{
 const f=await fixture(1,100);try{
  f.brain.hangInterrupt=true;await until(()=>f.journal.rows().some(r=>r.type==="role.failure"));
  const command=await f.controller.command("new goal after timeout","bot-0") as any;
  assert.equal(f.brain.turns.length,1);
  f.brain.turns[0].resolve({status:"interrupted"});await until(()=>f.brain.turns.length===2);
  assert.equal(f.brain.turns[1].input.intentId,command.intentId);assert.equal(f.brain.overlaps,0);
 }finally{await f.close();}
});
