import test from "node:test";
import assert from "node:assert/strict";
import {parseWait,waitingEventRelevant} from "../src/wait-condition.js";

test("processing state changes wake the waiting worker without releasing unrelated conditions",()=>{
 const event=["processing_changed"];
 const cooking=parseWait({kind:"processing",position:{x:3,y:64,z:4}},"等待本订单原版烧制",10,0);
 assert.equal(waitingEventRelevant(cooking,event),true);
 for(const kind of ["path","danger","model_recovery","player_standby","teammate","unknown_effect"]){
  assert.equal(waitingEventRelevant(parseWait({kind},"不相关等待",10,0),event),false);
 }
 assert.equal(waitingEventRelevant(cooking,["body_idle"]),false);
});
