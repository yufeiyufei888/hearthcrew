import test from 'node:test';import assert from 'node:assert/strict';
import {inventoryQuery,inventoryVersion,teamInventory,resourceSummary,relevantItems,materialConditionKey} from '../src/team-resources.js';
const world={worldId:'w',gameTick:100,companions:[{botId:'b',name:'Ember',dimension:'overworld',bodyGeneration:1,inventory:[]},{botId:'m',name:'Moss',dimension:'nether',bodyGeneration:2,inventory:[{slot:4,item:'minecraft:stick',count:5}],tools:[{slot:4,remaining:20}],equipment:{offhand:{item:'minecraft:shield',count:1}}}]};
test('read-only inventory distinguishes unknown, empty, filtered and cross-dimension',()=>{
 const before=structuredClone(world);const result=teamInventory(world,'b','m',['minecraft:stick']);assert.equal(result.slots[0].count,5);assert.equal(result.sameDimension,false);assert.equal(result.requiresAgreement,true);assert.deepEqual(world,before);
 assert.equal(teamInventory(world,'m','b').inventoryState,'empty');
 assert.equal(teamInventory({...world,companions:[world.companions[0],{...world.companions[1],inventory:undefined}]},'b','m').inventoryState,'unknown');
 assert.equal(teamInventory(world,'b','m',['minecraft:coal']).inventoryState,'known');
 const absent=teamInventory({...world,companions:[world.companions[0]]},'b','m',undefined,world.companions[1]);
 assert.equal(absent.inventoryState,'unknown');assert.equal(absent.slots,null);assert.equal(absent.observedAtGameTick,null);assert.equal(absent.dimension,null);
});
test('query scope and version change follow actual identity and inventory',()=>{
 assert.equal(inventoryQuery({companion:'Moss'},'b',world.companions).botId,'m');
 for(const companion of ['Ember','player','not_loaded'])assert.throws(()=>inventoryQuery({companion},'b',world.companions));
 assert.throws(()=>inventoryQuery({companion:'Moss',items:['bad']},'b',world.companions));
 const b=world.companions[1];assert.notEqual(inventoryVersion(b),inventoryVersion({...b,bodyGeneration:3,inventory:[]}));
});
test('summaries do not invent reservation evidence or consent',()=>{
 const result=resourceSummary(world,'b',['minecraft:stick'],[])[0].resources[0];assert.equal(result.held,5);assert.equal(result.reserved,null);assert.equal(result.unreserved,null);
 const values=relevantItems({kind:'inventory',resource:'minecraft:coal'},[{ingredients:[{alternatives:['minecraft:stick']}]}],[],'b');assert.deepEqual(values,['minecraft:coal','minecraft:stick']);
 assert.equal(relevantItems({},[{ingredients:[{alternatives:Array.from({length:30},(_,n)=>`minecraft:item${n}`)}]}],[],'b').length,16);
});
test('material thresholds ignore slot changes, durability and insufficient fluctuations',()=>{
 const first=materialConditionKey(world,'m','minecraft:stick',4);assert.match(first,/available/);
 const mutate=(count:number)=>({...world,companions:[world.companions[0],{...world.companions[1],inventory:[{slot:1,item:'minecraft:stick',count}]}]});
 assert.equal(materialConditionKey(mutate(7),'m','minecraft:stick',4),first);
 assert.equal(materialConditionKey(mutate(1),'m','minecraft:stick',4),materialConditionKey(mutate(3),'m','minecraft:stick',4));
 assert.notEqual(materialConditionKey(mutate(3),'m','minecraft:stick',4),first);
});

test('recipe gaps aggregate repeated cells and confirmed reservations never imply consent',()=>{
 const cell={alternatives:['minecraft:stick'],availableMatching:1,requiredForThisCell:1};
 assert.deepEqual(relevantItems({},[{ingredients:[cell,cell]}],[],'b'),['minecraft:stick']);
 assert.deepEqual(relevantItems({},[{ingredients:[{...cell,availableMatching:2},{...cell,availableMatching:2}]}],[],'b'),[]);
 const next={...world,team:{inventoryReservations:[{owner:'m',item:'minecraft:stick',dimension:'nether',count:2}]}};
 const resources=resourceSummary(next,'b',['minecraft:stick'],[])[0].resources[0];
 assert.equal(resources.reserved,2);assert.equal(resources.unreserved,null);
 assert.deepEqual(relevantItems({},[],[],'m',next.team.inventoryReservations),['minecraft:stick']);
});
