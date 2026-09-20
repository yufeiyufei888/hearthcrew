import {test} from "node:test";
import assert from "node:assert/strict";
import {normalizeActArguments,validateActionParameters} from "../src/action-parameters.js";
test("flat Luna resource request preserves amount and shared authorization",()=>{
  const input={kind:"COLLECT_RESOURCE",resource:"minecraft:birch_log",count:8,radius:32,accessBudget:0,preparation:{enabled:false}};
  const normalized=normalizeActArguments(input);const validated=validateActionParameters(normalized.kind,normalized.parameters);
  assert.equal(validated.parameters.count,8);assert.equal(validated.parameters.resource,input.resource);assert.equal(validated.parameters.accessBudget,0);assert.equal(validated.parameters.preparation?.enabled,false);
  assert.equal(input.count,8);assert.ok(!('parameters' in input));
});
test("nested/flat conflicts and unknown fields cannot silently change a request",()=>{
  assert.throws(()=>normalizeActArguments({kind:"CRAFT",count:8,parameters:{resource:"minecraft:stick",count:1}}),/conflict/);
  assert.throws(()=>normalizeActArguments({kind:"CRAFT",resouce:"minecraft:stick"}),/unknown field resouce/);
  assert.throws(()=>{const v=normalizeActArguments({kind:"MOVE",resource:"minecraft:coal"});validateActionParameters(v.kind,v.parameters);});
});
test("existing nested task/identity remains unchanged",()=>{
  const input={kind:"CRAFT",actionId:"unique-request",parameters:{resource:"minecraft:stick",count:2}};
  assert.equal(normalizeActArguments(input),input);
  assert.deepEqual(normalizeActArguments({taskId:"registered-team-task"}),{taskId:"registered-team-task"});
});
