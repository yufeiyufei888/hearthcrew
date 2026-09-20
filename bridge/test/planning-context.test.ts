import test from "node:test";
import assert from "node:assert/strict";
import {planningContext,contextPage} from "../src/planning-context.js";
test("24 KiB context keeps safety and execution authority; large evidence has lossless pages",()=>{
 const input={botId:"moss",intentId:"goal",communicationOnly:true,world:{worldId:"world",companions:[{botId:"moss",bodyGeneration:4,stopped:false,activeAction:{actionId:"new",state:"RUNNING"},suspendedActionIds:["old"],localSafety:{air:8,mode:"breathe"},harvestEvidence:Array.from({length:300},(_,i)=>({actionId:String(i),state:"RECONCILE_REQUIRED",description:"未知矿物🙂".repeat(20)}))}]},actionResults:[{eventId:"e",actionId:"a",state:"RECONCILE_REQUIRED",execution:{text:"细节".repeat(15000)}}]};
 const built=planningContext(input);assert.ok(Buffer.byteLength(built.prompt)<=24576);const parsed=JSON.parse(built.prompt);
 assert.equal(parsed.currentExecution.activeAction.actionId,"new");assert.equal(parsed.currentExecution.localSafety.air,8);assert.equal(parsed.currentExecution.communicationOnly,true);
 assert.equal(parsed.actionResults[0].state,"RECONCILE_REQUIRED");assert.ok(built.archives.length);
 for(const archive of built.archives){let cursor:number|null=0,full="";while(cursor!==null){const page=contextPage(archive.json,cursor);assert.ok(Buffer.byteLength(page.text)<=4096);full+=page.text;cursor=page.nextCursor;}assert.equal(full,archive.json);assert.doesNotThrow(()=>JSON.parse(full));}
});
test("critical facts overflow is explicit and does not launch a truncated unsafe turn",()=>assert.throws(()=>planningContext({identity:{description:"x".repeat(30000)}}),/CRITICAL_OVERFLOW/));

test("pending processing facts remain inline when world detail is paged",()=>{
 const orders=[{orderId:"iron",state:"OUTPUT_READY",expected:3,collected:0,position:{x:1,y:64,z:2}}];
 const built=planningContext({botId:"moss",world:{worldId:"w",terrain:"x".repeat(30000),companions:[{botId:"moss",execution:{processingOrders:orders}}]}});
 const parsed=JSON.parse(built.prompt);assert.equal(parsed.world.paged,true);
 assert.deepEqual(parsed.currentExecution.processingOrders,orders);
});
