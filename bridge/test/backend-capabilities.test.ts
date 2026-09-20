import {test} from "node:test";
import assert from "node:assert/strict";
import {projectBackendCapabilities} from "../src/backend-capabilities.js";
import type {ExecutionBackendDescriptor} from "../../protocol/execution-backend.js";
import {GAME_TOOLS,gameToolsForBackend,BACKEND_BASE_INSTRUCTIONS} from "../src/app-server-client.js";

const base={self:["MOVE","CRAFT","MINE","SAIL"],team:["MOVE","MINE"],actions:{MOVE:{required:["position"]},CRAFT:{required:["resource"]},MINE:{},SAIL:{}}};
const backend:ExecutionBackendDescriptor={id:"numen",version:"0.3.0-experimental",executionProtocol:6,bodyType:"ServerPlayer",
  actions:["MOVE","CRAFT","FUTURE_ACTION"],continuousTasks:true,preparationCheckpoints:true,pauseResume:true,bodyGenerations:true,terminalFinality:true};
test("new backend exposes only mutually understood implemented actions",()=>{
  const view=projectBackendCapabilities(base,backend);
  assert.deepEqual(view.self,["MOVE","CRAFT"]);assert.deepEqual(Object.keys(view.actions),["MOVE","CRAFT"]);
  assert.deepEqual(view.team,[]);assert.deepEqual(base.self,["MOVE","CRAFT","MINE","SAIL"]);
});
test("legacy connection retains its established capability guide",()=>assert.deepEqual(projectBackendCapabilities(base),base));
test("declared legacy backend still cannot inherit unsupported actions",()=>{
  const view=projectBackendCapabilities(base,{...backend,id:"legacy"});assert.deepEqual(view.team,["MOVE"]);
});
test("each new thread gets a matching five-tool schema without altering legacy threads",()=>{
  const before=JSON.stringify(GAME_TOOLS);const tools=gameToolsForBackend(GAME_TOOLS,backend);
  assert.deepEqual(tools.map(t=>t.name),["observe","propose_work","act","share","remember"]);
  const schema=tools.find(t=>t.name==="act")!.inputSchema;
  assert.deepEqual((schema.properties as any).kind.enum,["MOVE","CRAFT"]);
  assert.equal((schema.properties as any).taskId,undefined);assert.deepEqual(schema.required,["kind"]);
  assert.equal(JSON.stringify(GAME_TOOLS),before);assert.equal(gameToolsForBackend(GAME_TOOLS),GAME_TOOLS);
  assert.ok(!BACKEND_BASE_INSTRUCTIONS.includes("SAIL"));
});
test("no mutually supported action stops thread creation instead of sending an empty enum",()=>{
  assert.throws(()=>gameToolsForBackend(GAME_TOOLS,{...backend,actions:["FUTURE_ACTION"]}),/BACKEND_NO_SUPPORTED_ACTIONS/);
});

test("Numen collection contract removes position without altering legacy inputs",()=>{
 const base={self:["COLLECT_RESOURCE"],team:[],actions:{COLLECT_RESOURCE:{allowed:["position","resource","count"],example:{position:{x:0,y:64,z:0},resource:"minecraft:coal",count:16}}}};
 const projected=projectBackendCapabilities(base,{...backend,actions:["COLLECT_RESOURCE"]});
 assert.deepEqual(projected.actions.COLLECT_RESOURCE.allowed,["resource","count"]);
 assert.equal(projected.actions.COLLECT_RESOURCE.example.position,undefined);
 assert.ok(base.actions.COLLECT_RESOURCE.example.position);
 const spec=gameToolsForBackend(GAME_TOOLS,{...backend,actions:["COLLECT_RESOURCE"]}).find(t=>t.name==="act")!;
 assert.ok(spec.inputSchema.allOf,"dynamic schema carries action-specific prohibition");
});
