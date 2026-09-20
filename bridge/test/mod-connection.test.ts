import test from "node:test";
import assert from "node:assert/strict";
import { connect, createServer, type Socket } from "node:net";
import { encodeFrame } from "../src/framing.js";
import { ModConnection } from "../src/mod-connection.js";

for (const kind of ["event", "request"] as const) test(`disconnect during awaited ${kind} handler does not reject outside dispatch or replay`, async () => {
  const server=createServer();let connection:ModConnection|undefined;let entered=false,calls=0;
  let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});
  server.once("connection",socket=>{connection=new ModConnection(socket,{token:"t"});
    connection.onEvent(async()=>{calls++;entered=true;await gate;return true;});
    connection.onRequest(async()=>{calls++;entered=true;await gate;return {accepted:true};});
  });
  await new Promise<void>(resolve=>server.listen(0,"127.0.0.1",resolve));
  const socket=connect((server.address() as {port:number}).port,"127.0.0.1");
  try {
    socket.write(encodeFrame({kind:"handshake",protocol:"hearthcrew.v1",requestId:"h",worldId:"w",sessionEpoch:1,token:"t",client:"mod",capabilities:{events:true,acknowledgements:true,reconciliation:true,maxFrameBytes:1048576}}));
    const c=await waitFor(()=>connection);await onceReady(c);
    socket.write(encodeFrame({kind,protocol:"hearthcrew.v1",worldId:"w",sessionEpoch:1,...(kind==="event"?{eventId:"e",event:"action.terminal",body:{}}:{requestId:"r",op:"status",body:{}})}));
    await waitFor(()=>entered?true:undefined);c.close();release();
    await new Promise(resolve=>setTimeout(resolve,20));
    assert.equal(c.connectionState,"closed");assert.equal(calls,1);
  } finally {release();connection?.close();socket.destroy();await new Promise<void>(resolve=>server.close(()=>resolve()));}
});

function onceReady(connection: ModConnection): Promise<void> { if (connection.connectionState === "ready") return Promise.resolve(); return new Promise((resolve) => connection.once("ready", () => resolve())); }
async function waitFor<T>(read: () => T | undefined): Promise<T> { for (let i = 0; i < 100; i++) { const value = read(); if (value !== undefined) return value; await new Promise((resolve) => setTimeout(resolve, 5)); } throw new Error("test condition timed out"); }

test("Mod connection authenticates, handles async request/event ack, and deduplicates request retry", async () => {
  const server = createServer();
  const accepted = new Promise<ModConnection>((resolve) => server.once("connection", (socket: Socket) => { const c = new ModConnection(socket, { token: "secret", expectedWorldId: "w", expectedSessionEpoch: 4 }); c.onRequest((request) => ({ accepted: request.body })); c.onEvent(() => true); c.once("ready", () => resolve(c)); }));
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const port = (server.address() as { port: number }).port;
  const socket = connect(port, "127.0.0.1");
  const decoder = new (await import("../src/framing.js")).FrameDecoder();
  const incoming: any[] = [];
  socket.on("data", (chunk) => { for (const frame of decoder.push(chunk)) { const message = JSON.parse(frame.toString("utf8")); incoming.push(message); if (message.kind === "request") socket.write(encodeFrame({ kind: "response", protocol: "hearthcrew.v1", requestId: message.requestId, worldId: message.worldId, sessionEpoch: message.sessionEpoch, ok: true, body: { accepted: message.body } })); } });
  const handshake = { kind: "handshake", protocol: "hearthcrew.v1", requestId: "h", worldId: "w", sessionEpoch: 4, token: "secret", client: "mod", capabilities: { events: true, acknowledgements: true, reconciliation: true, maxFrameBytes: 1048576 } };
  const frame = encodeFrame(handshake);
  socket.write(frame.subarray(0, 3)); socket.write(frame.subarray(3));
  const connection = await accepted;
  const response = await connection.request("status", { x: 1 }, { requestId: "r-1", intentId: "i-1", actionId: "a-1" });
  assert.deepEqual(response, { accepted: { x: 1 } });
  // The same incoming requestId is served from the response cache without running the handler twice.
  const retry = encodeFrame({ kind: "request", protocol: "hearthcrew.v1", requestId: "r-1", worldId: "w", sessionEpoch: 4, op: "status", body: { x: 1 } });
  socket.write(retry);
  const ackPromise = connection.emitEvent("notice", { value: 2 }, 1000);
  const event = await waitFor(() => incoming.find((message) => message.kind === "event"));
  socket.write(encodeFrame({ kind: "ack", protocol: "hearthcrew.v1", eventId: event.eventId, worldId: "w", sessionEpoch: 4, accepted: true }));
  const acked = await ackPromise;
  assert.equal(acked, true);
  await new Promise((resolve) => setImmediate(resolve));
  socket.destroy();
  await connection.close();
  (server as any).closeAllConnections?.();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

test("disconnect marks accepted-but-unresolved world writes uncertain and does not replay", async () => {
  const server = createServer();
  let connection: ModConnection | undefined;
  server.once("connection", (socket) => { connection = new ModConnection(socket, { token: "t" }); });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const socket = connect((server.address() as { port: number }).port, "127.0.0.1");
  const handshake = encodeFrame({ kind: "handshake", protocol: "hearthcrew.v1", requestId: "h", worldId: "w", sessionEpoch: 1, token: "t", client: "mod", capabilities: { events: true, acknowledgements: true, reconciliation: true, maxFrameBytes: 1048576 } });
  socket.write(handshake);
  const readyConnection = await waitFor(() => connection);
  await onceReady(readyConnection);
  const pending = readyConnection.request("action.submit", { intentId: "i", actionId: "a" }, { requestId: "write-1" });
  socket.destroy();
  await assert.rejects(pending, (error: unknown) => error instanceof Error);
  assert.equal(readyConnection.reconciliationRecords.some((record) => record.requestId === "write-1" && record.status === "uncertain"), true);
  readyConnection.close();
  (server as any).closeAllConnections?.();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});
