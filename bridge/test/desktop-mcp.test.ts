import test from "node:test";
import assert from "node:assert/strict";
import { DesktopMcp } from "../src/desktop-mcp.js";

test("MCP lifecycle and duplicate call cannot repeat a command", async () => {
  let commands = 0;
  const server = new DesktopMcp({ status: async () => ({ game: "ready" }), command: async () => ({ accepted: true, number: ++commands }), control: async () => ({}), events: () => ({ events: [] }) });
  const send = (id: number, method: string, params?: unknown) => server.receive({ jsonrpc: "2.0", id, method, params }) as Promise<any>;
  assert.equal((await send(1, "tools/list")).error.code, -32602);
  assert.equal((await send(2, "initialize", { protocolVersion: "2025-11-25", clientInfo: { name: "test", version: "1" }, capabilities: {} })).result.protocolVersion, "2025-11-25");
  await server.receive({ jsonrpc: "2.0", method: "notifications/initialized" });
  assert.equal((await send(3, "tools/list")).result.tools.length, 4);
  const request = { jsonrpc: "2.0", id: "command1", method: "tools/call", params: { name: "command", arguments: { message: "采木后交给我" } } };
  const first = await server.receive(request); assert.deepEqual(await server.receive(request), first); assert.equal(commands, 1);
  assert.equal((await server.receive({ ...request, params: { name: "command", arguments: { message: "另一条" } } }) as any).error.code, -32600);
  assert.equal(commands, 1);
});
test("unsupported tool fields and invalid control fail before side effects", async () => {
  let calls = 0;
  const server = new DesktopMcp({ status: async () => ({}), command: async () => { calls++; }, control: async () => { calls++; }, events: () => ({}) });
  await server.receive({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", clientInfo: {}, capabilities: {} } });
  await server.receive({ jsonrpc: "2.0", method: "notifications/initialized" });
  const invalid = await server.receive({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "command", arguments: { message: "hello", executable: "anything" } } }) as any;
  assert.equal(invalid.result.isError, true);
  const stop = await server.receive({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "control", arguments: { operation: "delete_world" } } }) as any;
  assert.equal(stop.result.isError, true); assert.equal(calls, 0);
});

test("events validates cursor bounds before calling the controller", async () => {
  let calls = 0;
  const server = new DesktopMcp({ status: async () => ({}), command: async () => ({}), control: async () => ({}), events: () => { calls += 1; return {}; } });
  await server.receive({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-11-25", clientInfo: {}, capabilities: {} } });
  await server.receive({ jsonrpc: "2.0", method: "notifications/initialized" });
  const badAfter = await server.receive({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "events", arguments: { after: -1 } } }) as any;
  const badLimit = await server.receive({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "events", arguments: { limit: 101 } } }) as any;
  assert.equal(badAfter.result.isError, true);
  assert.equal(badLimit.result.isError, true);
  assert.equal(calls, 0);
});

test("unknown MCP protocol versions are rejected instead of silently upgraded", async () => {
  const server = new DesktopMcp({ status: async () => ({}), command: async () => ({}), control: async () => ({}), events: () => ({}) });
  const response = await server.receive({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "future", clientInfo: {}, capabilities: {} } }) as any;
  assert.equal(response.error.code, -32602);
  assert.match(response.error.message, /Unsupported/);
});
