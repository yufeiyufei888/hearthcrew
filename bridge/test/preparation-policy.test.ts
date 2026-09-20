import test from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,rm} from "node:fs/promises";
import {tmpdir} from "node:os";import {join} from "node:path";
import {CooperationQueue,assessSupply,type Cooperation} from "../src/cooperation.js";
import {CrewJournal} from "../src/crew-journal.js";
import {parseWait} from "../src/wait-condition.js";
const proposal=(id:string,from:string,to:string):Cooperation=>({id,worldId:"fixture",requester:from,recipient:to,beneficiaryId:from,supplierId:to,summary:"tool request",category:"materials",action:{kind:"TRANSFER",parameters:{target:from,resource:"minecraft:stone_pickaxe",count:1}},state:"proposed"});
test("0214 three-way supply cycle closes once, preserves history and does not touch submitted work",async()=>{
 const dir=await mkdtemp(join(tmpdir(),"hc-prep-"));try{const j=new CrewJournal(join(dir,"events.jsonl"));await j.load();const q=new CooperationQueue(j);
 for(const p of [proposal("a","Ember","Moss"),{...proposal("b","Moss","Flint"),state:"accepted" as const},proposal("c","Flint","Ember")])await q.save(p);
 assert.deepEqual((await q.resolveCycles("fixture")).sort(),["Ember","Flint","Moss"]);assert.equal(q.list("fixture").every(p=>p.state==="blocked"),true);assert.equal(j.rows().length,6);assert.deepEqual(await q.resolveCycles("fixture"),[]);
 const restored=new CooperationQueue(j);restored.restore();assert.equal(restored.get("fixture","b")?.state,"blocked");
 await q.save({...proposal("live","Ember","Moss"),state:"submitted",actionId:"live-body"});await q.resolveCycles("fixture");assert.equal(q.get("fixture","live")?.state,"submitted");
 }finally{await rm(dir,{recursive:true,force:true});}
});
test("0214 supply separates missing stock, worn/self-use tools, dimension and unknown path",()=>{
 const p=proposal("a","Ember","Moss"),supplier:any={botId:"Moss",dimension:"overworld",inventory:[{item:"minecraft:stone_pickaxe",count:2}],tools:[{item:"minecraft:stone_pickaxe",count:2,remainingDurability:2}]};const world={companions:[{botId:"Ember",dimension:"overworld"},supplier]};
 assert.equal(assessSupply(world,p).reason,"SUPPLIER_TOOL_SELF_USE_OR_WORN");supplier.tools[0].remainingDurability=100;assert.equal(assessSupply(world,p).state,"candidate");assert.equal(assessSupply(world,p).path,"executor_must_verify");supplier.dimension="nether";assert.equal(assessSupply(world,p).reason,"TRANSFER_DIFFERENT_DIMENSION");delete supplier.inventory;assert.equal(assessSupply(world,p).state,"unknown");
});
test("0214 material wait needs a real resource quantity and dependency",()=>{
 assert.throws(()=>parseWait({kind:"materials"},"waiting for pickaxe",1,0));assert.throws(()=>parseWait({kind:"materials",resource:"minecraft:stone_pickaxe",count:1},"wait",1,0));assert.equal(parseWait({kind:"materials",resource:"minecraft:stone_pickaxe",count:1,peerId:"Moss"},"wait",1,0).peerId,"Moss");
});
