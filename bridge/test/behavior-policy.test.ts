import test from "node:test";
import assert from "node:assert/strict";
import {consumeNovelFacts,goalStateForSource,resolveRecipients,sameHistoricalRequest,chatProgressFact} from "../src/behavior-policy.js";
test("old receipts, failed actions and repeated hazards do not reopen conversation budget",()=>{
 const seen=new Set<string>();
 const r={id:{value:"a"},payload:{kind:"MOVE"},epoch:{world:1,body:1},state:"COMPLETED"};
 const fact=chatProgressFact(r)!;assert.ok(fact);
 assert.equal(consumeNovelFacts(seen,[fact]),true);assert.equal(consumeNovelFacts(seen,[fact]),false);
 for(const other of [{...r,historical:true},{...r,state:"FAILED"},{...r,state:"RECONCILE_REQUIRED"},{...r,payload:{kind:"SELECT"}},{...r,payload:{kind:"BREATHE"}}])assert.equal(chatProgressFact(other),undefined);
 assert.equal(consumeNovelFacts(seen,[]),false);
 assert.equal(consumeNovelFacts(seen,["player:2"]),true);
});
test("model standby becomes recoverable blocked; explicit owner after-task standby remains",()=>{
 for(const origin of ["autonomous","owner"])assert.equal(goalStateForSource("standby",origin,"继续生存"),"blocked");
 assert.equal(goalStateForSource("standby","owner","采木，完成后待命"),"standby");
 assert.equal(goalStateForSource("blocked","autonomous",undefined),"blocked");
});
test("names, mentions and roles resolve to the same stable identity",()=>{
 const bodies=[{botId:"id-a",name:"Ember"},{botId:"id-b",name:"Moss"}];const roles=[{id:"gatherer",botId:"id-a"}];
 for(const name of [" Ember ","@EMBER","gatherer","id-a"])assert.equal(resolveRecipients(name,bodies,roles)[0].botId,"id-a");
 assert.equal(resolveRecipients("小队",bodies,roles,"id-a").length,1);
 assert.throws(()=>resolveRecipients("unknown",bodies,roles),/Ember.*Moss/);
});
test("historical proof requires identical request identity, priority, epoch and payload",()=>{
 const a={id:{value:"a"},priority:"PERSONAL",epoch:{world:1,body:1},payload:{kind:"SELECT",count:3}};
 assert.equal(sameHistoricalRequest(a,structuredClone(a)),true);
 for(const b of [{...a,id:{value:"b"}},{...a,epoch:{world:2,body:1}},{...a,payload:{kind:"SELECT",count:0}},{...a,priority:"OWNER"}])assert.equal(sameHistoricalRequest(a,b),false);
 assert.equal(sameHistoricalRequest(a,{id:a.id}),false);
});
