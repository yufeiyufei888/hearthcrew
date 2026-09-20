import test from "node:test";
import assert from "node:assert/strict";
import { assertWireMessage } from "../../protocol/types.js";

const context = { protocol: "hearthcrew.v1", worldId: "world", sessionEpoch: 2 } as const;

test("wire validator rejects unknown kinds and malformed capability/terminal fields", () => {
  assert.throws(() => assertWireMessage({ ...context, kind: "unknown" }), /unknown wire|invalid/);
  assert.throws(() => assertWireMessage({ ...context, kind: "handshake", requestId: "h", token: "t", client: "mod", capabilities: { events: true } }), /invalid handshake/);
  assert.throws(() => assertWireMessage({ ...context, kind: "response", requestId: "r", ok: true, error: { code: "BAD", message: "should not coexist" } }), /invalid response/);
  assert.throws(() => assertWireMessage({ ...context, kind: "ack", eventId: "e", accepted: "yes" }), /invalid acknowledgement/);
  assert.throws(() => assertWireMessage({ ...context, kind: "request", requestId: "r", op: "shell.exec", body: {} }), /invalid request/);
  assert.throws(() => assertWireMessage({ ...context, kind: "handshake_ack", requestId: "h", accepted: true, serverCapabilities: { events: true } }), /invalid handshake acknowledgement/);
  assert.throws(() => assertWireMessage({ ...context, kind: "request", requestId: "r", op: "observe", body: {}, bodyGeneration: -1 }), /invalid request/);
});
