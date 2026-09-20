import test from "node:test";
import assert from "node:assert/strict";
import { createServer, connect } from "node:net";
import { ModConnection } from "../src/mod-connection.js";
import { encodeFrame } from "../src/framing.js";
import {executionRuntime} from "../src/execution-runtime.js";
import type { ExecutionBackendDescriptor } from "../../protocol/execution-backend.js";

const backend: ExecutionBackendDescriptor = { id: "numen", version: "947f0064-lab", executionProtocol: 6, bodyType: "ServerPlayer",
  actions: ["MOVE", "COLLECT_RESOURCE"], continuousTasks: true, preparationCheckpoints: true,
  pauseResume: true, bodyGenerations: true, terminalFinality: true,exactMoveCompletion:true,navigationProgressVersion:1,boundedNoProgress:true,facilityStanceVerification:true };
const base = { events: true, acknowledgements: true, reconciliation: true, maxFrameBytes: 1048576 };
const cases = [
  { name:"0.3.0 backend cannot silently omit recovery",protocol:6,supported:6,descriptor:{...backend,navigationProgressVersion:undefined},ready:false },
  { name: "legacy remains readable", protocol: 5, supported: 6, descriptor: undefined, ready: true },
  { name: "new backend cannot fall through an old controller", protocol: 6, supported: 5, descriptor: backend, ready: false },
  { name: "protocol 6 requires a backend descriptor", protocol: 6, supported: 6, descriptor: undefined, ready: false },
  { name: "missing pause recovery blocks admission", protocol: 6, supported: 6, descriptor: { ...backend, pauseResume: false }, ready: false },
  { name: "future protocol is rejected", protocol: 7, supported: 6, descriptor: backend, ready: false },
  { name: "descriptor cannot ride an old protocol", protocol: 5, supported: 6, descriptor: backend, ready: false },
  { name: "duplicate action declarations are malformed", protocol: 6, supported: 6, descriptor: { ...backend, actions: ["MOVE", "MOVE"] }, ready: false },
  { name: "valid execution contract is retained", protocol: 6, supported: 6, descriptor: backend, ready: true },
];
for (const scenario of cases) test(`backend negotiation: ${scenario.name}`, async () => {
  const server = createServer(); let connection: ModConnection | undefined; let requests = 0;
  server.once("connection", socket => {
    connection = new ModConnection(socket, { token: "fixture-token", ...(scenario.supported===6?executionRuntime("numen"):{capabilities:{...base,executionProtocol:scenario.supported}}) });
    connection.onRequest(() => { requests++; return {}; });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const socket = connect((server.address() as { port: number }).port, "127.0.0.1");
  try {
    socket.write(encodeFrame({ protocol: "hearthcrew.v1", kind: "handshake", requestId: "handshake", worldId: "fixture-world",
      sessionEpoch: 1, client: "mod", token: "fixture-token", capabilities: { ...base, executionProtocol: scenario.protocol },
      ...(scenario.descriptor ? { backend: scenario.descriptor } : {}) }));
    for (let i = 0; i < 100 && (!connection || connection.connectionState === "awaiting_handshake"); i++) await new Promise(r => setTimeout(r, 5));
    assert.ok(connection);
    assert.equal(connection.connectionState, scenario.ready ? "ready" : "closed");
    assert.equal(requests, 0, "handshake cannot dispatch world operations");
    if (scenario.ready) assert.deepEqual(connection.executionBackend, scenario.descriptor);
  } finally { connection?.close(); socket.destroy(); await new Promise<void>(r => server.close(() => r())); }
});
