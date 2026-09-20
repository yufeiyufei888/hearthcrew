import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, stat, appendFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CrewJournal } from "../src/crew-journal.js";
import { ActionEvidence } from "../src/action-evidence.js";
import { CooperationQueue } from "../src/cooperation.js";
import { parseWait, waitingEventRelevant } from "../src/wait-condition.js";

test("029 waiting is event-specific and player standby cannot be woken by chat",()=>{
 const wait=parseWait({kind:"materials",resource:"minecraft:cobblestone"},"缺圆石",40,1);
 assert.equal(wait.sinceTick,40);assert.equal(wait.recoveryAttempts,1);
 assert.equal(waitingEventRelevant(wait,["environment_changed"]),false);
 assert.equal(waitingEventRelevant(wait,["inventory_changed"]),true);
 assert.equal(waitingEventRelevant(parseWait({kind:"player_standby"},"主人待命",50,0),["chat_message","danger_changed"]),false);
});

test("029 segmented journal preserves identities, world index and cooperation after restart",async()=>{
 const t=await temporaryJournal();try{
  const journal=new CrewJournal(t.path,2);await journal.load();
  const queue=new CooperationQueue(journal);
  await queue.save({id:"yield-a",worldId:"world-a",requester:"a",recipient:"b",summary:"让路",category:"yield",action:{kind:"YIELD",parameters:{}},state:"proposed"});
  await assert.rejects(queue.decide("world-a","yield-a","wrong",true),/addressed/);
  await queue.decide("world-a","yield-a","b",true);
  for(let i=0;i<5;i++)await journal.append("action.prepared",i%2?"world-b":"world-a",{actionId:`action-${i}`,fingerprint:`f-${i}`});
  const restored=new CrewJournal(t.path,2);await restored.load();
  assert.deepEqual(restored.rows(),journal.rows());assert.equal((await restored.append("tail","world-a",{})).sequence,8);
  const q=new CooperationQueue(restored);q.restore();assert.equal(q.get("world-a","yield-a")?.state,"accepted");
 }finally{await t.cleanup();}
});
test("029 cooperation acceptance is not completion and reconciliation can settle later",async()=>{
 const t=await temporaryJournal();try{
  const journal=new CrewJournal(t.path);await journal.load();const q=new CooperationQueue(journal);
  await q.save({id:"yield",worldId:"w",requester:"a",recipient:"b",summary:"让路",category:"yield",action:{kind:"YIELD",parameters:{}},actionId:"act",state:"submitted"});
  for(const state of ["ACCEPTED","RUNNING","SUSPENDED"]){await q.terminal("w","b","act",state,"");assert.equal(q.get("w","yield")?.state,"submitted");}
  await q.terminal("w","b","act","RECONCILE_REQUIRED","");assert.equal(q.get("w","yield")?.state,"reconcile");
  await q.terminal("w","b","act","COMPLETED","");assert.equal(q.get("w","yield")?.state,"completed");
  await q.terminal("w","b","act","FAILED","");assert.equal(q.get("w","yield")?.state,"completed");
 }finally{await t.cleanup();}
});
test("029 terminal evidence is absorbing across receipt order and body generations",()=>{
 const e=new ActionEvidence();assert.equal(e.apply("w","b","a","PREPARED",7,"f"),true);
 assert.equal(e.apply("w","b","a","COMPLETED",7),true);
 for(const state of ["ACCEPTED","RUNNING","SUSPENDED"])assert.equal(e.apply("w","b","a",state,7),false);
 assert.equal(e.apply("w","b","a","COMPLETED",8),false);
 assert.equal(e.apply("w","b","new","RUNNING",8),true);
 assert.equal(e.settled("w","b","a"),true);assert.equal(e.settled("other","b","a"),false);
});

test("large retained journal restarts without losing identities and rejects an incomplete tail", async () => {
  const temporary = await temporaryJournal();
  try {
    const lines = Array.from({ length: 520 }, (_, i) => JSON.stringify({ sequence: i + 1, type: "game.event", worldId: "world-a", data: { evidence: "木".repeat(23000) } })).join("\n") + "\n";
    await writeFile(temporary.path, lines);
    assert.ok((await stat(temporary.path)).size > 32 * 1024 * 1024);
    const journal = new CrewJournal(temporary.path);
    await journal.load();
    assert.equal(journal.rows().length, 520);
    assert.equal(journal.rows()[519].data.evidence, "木".repeat(23000));
    assert.equal((await journal.append("next", "world-a", {})).sequence, 521);
    await appendFile(temporary.path, '{"sequence":522');
    await assert.rejects(new CrewJournal(temporary.path).load(), /incomplete write/);
  } finally { await temporary.cleanup(); }
});

async function temporaryJournal(): Promise<{ path: string; cleanup: () => Promise<void> }> {
  const directory = await mkdtemp(join(tmpdir(), "hearthcrew-journal-"));
  return { path: join(directory, "crew.jsonl"), cleanup: () => rm(directory, { recursive: true, force: true }) };
}

test("CrewJournal serializes concurrent load and returns defensive record copies", async () => {
  const temporary = await temporaryJournal();
  try {
    const writer = new CrewJournal(temporary.path);
    await writer.load();
    const data = { nested: { value: 1 } };
    await writer.append("role.memory", "world-a", data);
    data.nested.value = 2;

    const readers = [new CrewJournal(temporary.path), new CrewJournal(temporary.path), new CrewJournal(temporary.path)];
    await Promise.all(readers.map(reader => Promise.all([reader.load(), reader.load()])));
    const row = readers[0].rows()[0];
    assert.equal((row.data.nested as { value: number }).value, 1);
    (row.data.nested as { value: number }).value = 9;
    assert.equal((readers[0].rows()[0].data.nested as { value: number }).value, 1);
  } finally {
    await temporary.cleanup();
  }
});

test("CrewJournal snapshots append input before a queued disk write", async () => {
  const temporary = await temporaryJournal();
  try {
    const journal = new CrewJournal(temporary.path);
    await journal.load();
    const first = { value: 1 };
    const second = { value: 2 };
    const firstWrite = journal.append("first", "world-a", first);
    const secondWrite = journal.append("second", "world-a", second);
    first.value = 10;
    second.value = 20;
    const rows = await Promise.all([firstWrite, secondWrite]);
    assert.deepEqual(rows.map(row => row.data), [{ value: 1 }, { value: 2 }]);
    assert.deepEqual(journal.rows().map(row => row.data), [{ value: 1 }, { value: 2 }]);
  } finally {
    await temporary.cleanup();
  }
});


test("0213 batched durable writes rotate without losing role-scoped context identity",async()=>{
 const t=await temporaryJournal();try{const j=new CrewJournal(t.path,3);await j.load();
  const writes=Array.from({length:20},(_,n)=>j.append("context.archive",n%2?"w2":"w1",{botId:n%3?"a":"b",reference:`ref-${n}`,value:n}));
  assert.equal(j.rows().length,0,"unflushed writes must not be visible");
  const rows=await Promise.all(writes);assert.deepEqual(rows.map(r=>r.sequence),Array.from({length:20},(_,n)=>n+1));
  const restored=new CrewJournal(t.path,3);await restored.load();assert.deepEqual(restored.rows(),rows);
  assert.equal(restored.context("w1","a","ref-2")?.data.value,2);assert.equal(restored.context("w2","a","ref-2"),undefined);assert.equal(restored.context("w1","b","ref-2"),undefined);
 }finally{await t.cleanup();}
});
test("0213 missing wait category is planning recovery, never fabricated unknown world effect",()=>{assert.equal(parseWait(undefined,"needs reassessment",10,0).kind,"model_recovery");});

test("0213 action and role indexes preserve full identity across durable reload",async()=>{
 const t=await temporaryJournal();try{const j=new CrewJournal(t.path);await j.load();
 await Promise.all([j.append("game.event","w1",{event:"action.terminal",body:{botId:"a",id:{value:"same"},state:"COMPLETED"}}),j.append("game.event","w1",{event:"action.terminal",body:{botId:"b",id:{value:"same"},state:"FAILED"}}),j.append("game.event","w2",{event:"action.terminal",body:{botId:"a",id:{value:"same"},state:"FAILED"}})]);
 const r=new CrewJournal(t.path);await r.load();assert.equal(r.action("w1","a","same").length,1);assert.equal((r.action("w1","a","same")[0].data.body as any).state,"COMPLETED");assert.equal(r.role("w1","a",()=>true,5).length,1);
 }finally{await t.cleanup();}
});
