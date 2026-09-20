import assert from "node:assert/strict";
import test from "node:test";
import { ACTION_GUIDE, ACTION_KINDS, ACTION_PARAMETERS_SCHEMA, validateActionParameters } from "../src/action-parameters.js";

const target = "123e4567-e89b-12d3-a456-426614174000";
const position = { x: 4, y: 64, z: -8 };

test("action schema exposes every server action kind and bounded parameters", () => {
  const properties = ACTION_PARAMETERS_SCHEMA.properties as Record<string, { minimum?: number; maximum?: number }>;
  assert.equal(ACTION_PARAMETERS_SCHEMA.additionalProperties, false);
  assert.ok(properties.position);
  assert.ok(properties.target);
  assert.equal(properties.count.minimum, 0);
  assert.equal(properties.count.maximum, 100000);
});

test("validates all ModLink action shapes without world knowledge", () => {
  const valid: Record<string, Record<string, unknown>> = {
    YIELD:{},COLLECT_RESOURCE:{resource:"minecraft:coal",count:16,candidates:["minecraft:coal_ore"]},SEQUENCE:{actions:[{kind:"WAIT",parameters:{count:1}}]},
    USE_ITEM:{count:32},INTERACT:{position},EQUIP:{resource:"minecraft:iron_helmet"},STORE:{position,resource:"minecraft:dirt",count:1},TAKE:{position,resource:"minecraft:dirt",count:1},PROCESS:{position,resource:"minecraft:raw_iron",count:1},COLLECT_PROCESS:{position},SWIM:{position},EXPLORE:{position},LAUNCH_BOAT:{position},BOARD_BOAT:{target},SAIL:{position},DISEMBARK:{position},
    EXCAVATE: {position,count:32}, MOVE: { position }, FOLLOW: { target }, MINE: { position,count:1 }, PLACE: { position }, EAT: {}, SLEEP: { position },
    ATTACK: { target }, GUARD: { target }, PICKUP: { target }, PORTAL: { position }, WAIT: { count: 100 },
    CRAFT: { resource: "minecraft:oak_planks", count: 1 }, TRANSFER: { target, resource: "minecraft:oak_planks", count: 4 }, GATHER: { resource: "minecraft:oak_log", count: 2 }, SELECT: { count: 0 },
    BUILD: { steps: [{ position: { x: 1, y: 64, z: 2 }, block: "minecraft:oak_planks" }] },
  };
  for (const kind of ACTION_KINDS) assert.deepEqual(validateActionParameters(kind.toLowerCase(), valid[kind]), { kind, parameters: valid[kind] }, kind);
});

test("rejects coordinate object accidentally supplied as MOVE target", () => {
  assert.throws(() => validateActionParameters("MOVE", { target: position }), /requires position/);
  assert.throws(() => validateActionParameters("MOVE", { position, target: position }), /does not accept target/);
  assert.throws(() => validateActionParameters("MOVE", {}), /requires position/);
  assert.throws(() => validateActionParameters("MOVE", { position: { x: 1, y: 64 } }), /position.z/);
});

test("rejects target/resource/count shape errors before a world request", () => {
  assert.throws(() => validateActionParameters("TRANSFER", { target: "not-an-entity", resource: "minecraft:oak_planks", count: 4 }), /UUID/);
  assert.deepEqual(validateActionParameters("CRAFT", { resource: "minecraft:oak_planks" }), { kind: "CRAFT", parameters: { resource: "minecraft:oak_planks" } });
  assert.deepEqual(validateActionParameters("TRANSFER", { target, resource: "minecraft:oak_planks" }), { kind: "TRANSFER", parameters: { target, resource: "minecraft:oak_planks" } });
  assert.throws(() => validateActionParameters("SELECT", { count: 36 }), /0\.\.35/);
  assert.throws(() => validateActionParameters("EAT", { count: 1 }), /non-zero count/);
  assert.throws(() => validateActionParameters("WAIT", { resource: "minecraft:stick" }), /does not accept resource/);
  assert.throws(() => validateActionParameters("WAIT", { unexpected: true }), /unsupported action parameter/);
  assert.throws(() => validateActionParameters("TELEPORT", {}), /unsupported action kind/);
  assert.deepEqual(validateActionParameters("FOLLOW", { target: "ffffffff-ffff-ffff-ffff-ffffffffffff" }).kind, "FOLLOW");
});

test("GATHER accepts only supported logs with a bounded positive request", () => {
  assert.deepEqual(validateActionParameters("gather", { resource: "minecraft:birch_log", count: 4 }), {
    kind: "GATHER", parameters: { resource: "minecraft:birch_log", count: 4 },
  });
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log" }), /count in 1\.\.64/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log", count: 0 }), /count in 1\.\.64/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log", count: 65 }), /count in 1\.\.64/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_planks", count: 2 }), /supported overworld log/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log", count: 2, position }), /does not accept position/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log", count: 2, target }), /does not accept target/);
  assert.throws(() => validateActionParameters("GATHER", { resource: "minecraft:oak_log", count: 2, steps: [] }), /does not accept steps/);
});

test("BUILD validates a bounded unique blueprint and returns a deep frozen copy", () => {
  const input = { steps: [
    { position: { x: 1, y: 64, z: 2 }, block: "minecraft:oak_planks" },
    { position: { x: 2, y: 64, z: 2 }, block: "minecraft:oak_stairs" },
  ] };
  const result = validateActionParameters("build", input);
  assert.deepEqual(result, { kind: "BUILD", parameters: input });
  assert.notEqual(result.parameters, input);
  assert.notEqual(result.parameters.steps, input.steps);
  assert.notEqual(result.parameters.steps![0], input.steps[0]);
  assert.equal(Object.isFrozen(result), true);
  assert.equal(Object.isFrozen(result.parameters), true);
  assert.equal(Object.isFrozen(result.parameters.steps), true);
  input.steps[0].position.x = 99;
  input.steps[0].block = "minecraft:stone";
  assert.equal(result.parameters.steps![0].position.x, 1);
  assert.equal(result.parameters.steps![0].block, "minecraft:oak_planks");
});

test("BUILD rejects duplicate positions, nested unknown fields, illegal primitive fields, and bad bounds", () => {
  const step = { position: { x: 1, y: 64, z: 2 }, block: "minecraft:oak_planks" };
  assert.throws(() => validateActionParameters("BUILD", { steps: [step, { ...step }] }), /duplicate position/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [{ ...step, color: "red" }] }), /unsupported field/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [{ position: { ...step.position, extra: 1 }, block: step.block }] }), /unsupported field/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [{ ...step, block: "Oak Planks" }] }), /ResourceLocation/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [] }), /1\.\.16/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [step], count: 1 }), /non-zero count/);
  assert.throws(() => validateActionParameters("BUILD", { steps: [step], target }), /does not accept target/);
  assert.throws(() => validateActionParameters("WAIT", { steps: [step] }), /does not accept steps/);
});

test("SELECT rejects omission/conflict and normalizes canonical and legacy slots", () => {
  assert.throws(()=>validateActionParameters("SELECT",{}),/requires slot/);
  assert.throws(()=>validateActionParameters("SELECT",{slot:2,count:0}),/conflict/);
  assert.deepEqual(validateActionParameters("SELECT",{slot:27}).parameters,{count:27});
  assert.deepEqual(validateActionParameters("SELECT",{slot:0,count:0}).parameters,{count:0});
  assert.throws(()=>validateActionParameters("MOVE",{position,slot:0}),/only accepted/);
});
test("all published examples validate including PLACE resource", () => {
  for(const [kind,guide] of Object.entries(ACTION_GUIDE))assert.equal(validateActionParameters(kind,guide.example).kind,kind);
  assert.equal(validateActionParameters("PLACE",{position,resource:"minecraft:dirt"}).parameters.resource,"minecraft:dirt");
});

test("EXCAVATE defaults to 32 and never accepts an unlimited digging budget",()=>{assert.equal(validateActionParameters("EXCAVATE",{position}).parameters.count,32);for(const count of [0,65,-1,1.5])assert.throws(()=>validateActionParameters("EXCAVATE",{position,count}));});
test("shared access budget accepts zero, rejects excess and stays in sequence root",()=>{
  for(const budget of [0,16,64])assert.equal(validateActionParameters("MINE",{position,accessBudget:budget}).parameters.accessBudget,budget);
  for(const accessBudget of [-1,65,1.5])assert.throws(()=>validateActionParameters("PICKUP",{target,accessBudget}));
  assert.throws(()=>validateActionParameters("PLACE",{position,accessBudget:16}),/does not accept/);
  const action=validateActionParameters("SEQUENCE",{accessBudget:11,actions:[{kind:"MINE",parameters:{position}},{kind:"PICKUP",parameters:{target,accessBudget:0}}]});
  assert.equal(action.parameters.accessBudget,11);assert.equal(action.parameters.actions![1].parameters.accessBudget,0);
});

test("0213 preparation authorization is bounded and resources need no mechanical candidate list",()=>{
 const basic={resource:"minecraft:raw_iron",count:20};
 assert.equal(validateActionParameters("COLLECT_RESOURCE",basic).parameters.count,20);
 assert.deepEqual(validateActionParameters("COLLECT_RESOURCE",{...basic,preparation:{}}).parameters.preparation,{enabled:true,maxDepth:6,maxSteps:16,maxBreaks:64});
 assert.equal(validateActionParameters("COLLECT_RESOURCE",{...basic,preparation:{enabled:false},accessBudget:0}).parameters.preparation!.enabled,false);
 for(const preparation of [{maxDepth:7},{maxSteps:17},{maxBreaks:65},{enabled:"yes"},{extra:1}])assert.throws(()=>validateActionParameters("COLLECT_RESOURCE",{...basic,preparation}));
 for(const [kind,parameters] of [["MINE",{position}],["EXCAVATE",{position}],["CRAFT",{resource:"minecraft:stone_pickaxe",count:1}]] as const)assert.equal(validateActionParameters(kind,{...parameters,preparation:{}}).parameters.preparation!.enabled,true);
});
