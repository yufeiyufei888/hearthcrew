import test from 'node:test';
import assert from 'node:assert/strict';
import {makeUiView,messages,publicText} from '../src/ui-view.js';
import {stageRows,workBoard} from '../src/work-board.js';
const body={botId:'b',name:'Ember',bodyGeneration:3,inventory:[{item:'minecraft:coal',count:19}],action:{id:{value:'a'},state:'RUNNING',epoch:{bodyGeneration:3},payload:{kind:'MINE',resource:'minecraft:coal_ore',count:16}},execution:{acquired:{'minecraft:coal':2},acquiredByOthers:{'minecraft:coal':1},pendingDrops:[],phase:'recovering_drops'}};
const role={botId:'b',rootGoal:'收集20个煤炭',intentId:'i',intentOrigin:'owner',taskStage:{stageId:'s',summary:'准备矿道并采煤',state:'continue',completion:{kind:'inventory',resource:'minecraft:coal',count:20},actions:['a']}};

test('search and disk wait are not described as active mining; safety preserves goal',()=>{
 const card=(b:any)=>workBoard({game:{companions:[b]},roles:[role]},publicText)[0];
 assert.match(card({...body,execution:{navigation:{phase:'SEARCHING'}}}).step,/寻找通路/);
 assert.match(card({...body,execution:{persistence:'WAITING_FOR_DURABILITY'}}).step,/写盘/);
 const w=card({...body,localSafety:{active:true,reaction:'WAITING_NO_SAFE_SHORE'}});
 assert.match(w.step,/无已确认岸点/);assert.equal(w.goal,'收集20个煤炭');
});
test('board separates root goal, stage, live step and actual receipt from stock',()=>{
 const view=makeUiView({game:{worldId:'w',companions:[body]},roles:[role]},[]);
 assert.equal(view.schemaVersion,2);assert.equal(view.tasks.length,0);const w=view.work[0];assert.equal(w.goal,'收集20个煤炭');assert.equal(w.stage,'准备矿道并采煤');assert.match(w.step,/回收掉落/);assert.match(w.progress,/本人取得 2，他人取得 1/);assert.match(w.progress,/当前持有 19/);
});
test('history cannot invent live work, generation mismatch and pause clear current action',()=>{
 for(const b of [{...body,action:null,actionJournal:Array(20).fill(body.action)},{...body,bodyGeneration:4},{...body,paused:true}]){
 const w=workBoard({game:{companions:[b]},roles:[role]},publicText)[0];assert.equal(w.actionId,'');assert.doesNotMatch(w.step,/连续采掘|回收掉落/);
 }
});
test('same title remains distinct by identity while late accepted cannot undo terminal attempt',()=>{
 const s={worldId:'w',botId:'b',intentId:'i',summary:'采煤',actions:['a']};
 const rows=stageRows([{...s,stageId:'s1'},{...s,stageId:'s2'}],{actionJournal:[{...body.action,state:'COMPLETED',message:'done'},{...body.action,state:'ACCEPTED'}]},publicText);
 assert.notEqual(rows[0].key,rows[1].key);assert.match(rows[0].attempts[0],/COMPLETED/);
});
test('native transient safety retains original goal, named ordering stays stable',()=>{
 const w=workBoard({game:{companions:[{...body,name:'Moss'},{...body,name:'Flint'},{...body,action:{...body.action,payload:{kind:'BREATHE'}}}]},roles:[role]},publicText);
 assert.deepEqual(w.map(x=>x.name),['Ember','Moss','Flint']);assert.match(w[0].step,/临时求生/);assert.equal(w[0].goal,'收集20个煤炭');
});
test('public chat dedupes identities not text; noise and final reasoning are absent',()=>{
 const rows=[{sequence:1,atUtc:'a',type:'owner.command',data:{uiRequestId:'q',message:'采煤'}},{sequence:2,type:'owner.command',data:{uiRequestId:'q',message:'采煤'}},{sequence:3,type:'ui.failure',data:{requestId:'q',reason:'断线'}},{sequence:4,type:'crew.chat',data:{messageId:'m',senderName:'Moss',recipientNames:['Ember'],message:'收到',replyTo:'q',replyName:'你'}},{sequence:5,type:'crew.chat',data:{messageId:'n',senderName:'Moss',recipientNames:['Ember'],message:'收到'}},...Array.from({length:200},(_,i)=>({sequence:6+i,type:'tool.rejected',data:{reason:'noise'}})),{sequence:999,type:'role.final',data:{message:'private reasoning'}}];
 const result=messages(rows);assert.equal(result.length,3);assert.equal(result[0].state,'FAILED');assert.equal(result[1].replyTo,'q');assert.equal(result[1].role,'Moss');assert.equal(result[1].message,result[2].message);assert.doesNotMatch(JSON.stringify(result),/noise|reasoning/);
});
test('large multilingual board fits UI budget without deleting goals',()=>{
 const huge='长中文'.repeat(500);const view=makeUiView({game:{companions:['Ember','Moss','Flint'].map(name=>({...body,name,inventory:Array.from({length:36},(_,slot)=>({slot,item:'a'.repeat(220),count:64}))}))},roles:[{...role,rootGoal:huge,taskStage:{...role.taskStage,summary:huge},pendingRequests:Array.from({length:8},()=>({message:huge}))}]},[]);
 assert.ok(Buffer.byteLength(JSON.stringify(view))<=58*1024);assert.equal(view.work.length,3);assert.ok(view.work[0].fullGoal);
});
test('backend preparation uses current native step and proven output, never total stock',()=>{
 const b={...body,action:{...body.action,payload:{kind:'COLLECT_RESOURCE',resource:'minecraft:coal',count:16}},execution:{accessSpent:2,accessBudget:16,preparation:{condition:'CONTINUING_VERIFIED_RESOURCE_SHORTFALL',goalOutput:{ownNew:5,teamNew:2,requestedNew:16,sources:[{pendingEvidence:[{entityId:'drop'},{entityId:'drop'}]}]}}}};
 const w=workBoard({game:{companions:[b]},roles:[role]},publicText)[0];assert.match(w.step,/^继续补采实际差额/);assert.match(w.progress,/本人取得 5，队友取得 2，目标 16/);assert.match(w.progress,/待核对 1 组/);assert.match(w.progress,/当前持有 19/);
 const paused=workBoard({game:{companions:[{...b,paused:true}]},roles:[role]},publicText)[0];assert.equal(paused.step,'已暂停');assert.doesNotMatch(paused.progress,/本人取得 5/);
});
