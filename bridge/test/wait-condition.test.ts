import test from "node:test";
import assert from "node:assert/strict";
import {parseWait,waitingEventRelevant,recheckableWait,waitIdentity} from "../src/wait-condition.js";
test("resource capacity and missing methods are factual waits, not model recovery",()=>{
 for(const reason of ["METHOD_CAPACITY: acquire:minecraft:dark_oak_log","NO_SATISFIABLE_METHOD: minecraft:coal","UNVERIFIED_SOURCE_FOR_ITEM: minecraft:raw_copper"]){
  const wait=parseWait({kind:"model_recovery"},reason,8,0);
  assert.equal(wait.kind,"resources");assert.ok(wait.resource?.startsWith("minecraft:"));
  assert.equal(waitingEventRelevant(wait,["scan_ready"]),true);
 }
});

test("failed acquisition remains a resource condition and stable retry identity ignores wording",()=>{
 const a=parseWait(undefined,"ACQUISITION_BATCH_REJECTED: minecraft:coal",1,0);
 const b=parseWait({kind:"resources",resource:"minecraft:coal",taskId:"renamed-stage"},"another summary",20,2);
 assert.equal(a.kind,"resources");assert.equal(waitIdentity(a),waitIdentity(b));
 assert.equal(waitingEventRelevant(parseWait(undefined,"model plan fault",1,2),["environment_changed"]),true);
});
test("actual model faults, processing and unknown effects keep distinct wake rules",()=>{
 assert.equal(parseWait(undefined,"90 second model timeout",10,2).kind,"model_recovery");
 assert.equal(parseWait(undefined,"WAITING_PROCESS:PROCESSING",10,0).kind,"processing");
 const wait=parseWait(undefined,"RECONCILE_REQUIRED: uncertain effect",10,0);
 assert.equal(wait.kind,"unknown_effect");assert.equal(recheckableWait(wait),false);
 assert.equal(recheckableWait(parseWait({kind:"player_standby"},"owner standby",10,0)),false);
});
