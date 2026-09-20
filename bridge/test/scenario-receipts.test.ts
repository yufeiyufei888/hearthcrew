import test from "node:test";
import assert from "node:assert/strict";
import { assertNoExtraScenarioActions } from "../src/scenario-receipts.js";

const row = (state: string, id = "build-1", count = 0) => ({ id: { value: id }, state, payload: { kind: "BUILD", count } });
test("scenario audit counts action identities across legitimate lifecycle transitions", () => {
  assert.doesNotThrow(() => assertNoExtraScenarioActions([row("ACCEPTED"), row("RUNNING"), row("SUSPENDED"), row("RUNNING"), row("COMPLETED")], [row("COMPLETED")]));
});
test("scenario audit rejects an additional action even before it completes", () => {
  assert.throws(() => assertNoExtraScenarioActions([row("COMPLETED"), row("ACCEPTED", "extra")], [row("COMPLETED")]), /additional/);
});
test("scenario audit rejects unfinished, duplicated terminal, and changed payload records", () => {
  assert.throws(() => assertNoExtraScenarioActions([row("RUNNING")], [row("COMPLETED")]), /not all completed/);
  assert.throws(() => assertNoExtraScenarioActions([row("COMPLETED"), row("COMPLETED")], [row("COMPLETED")]), /after completion/);
  assert.throws(() => assertNoExtraScenarioActions([row("COMPLETED", "build-1", 1)], [row("COMPLETED")]), /changed/);
});
