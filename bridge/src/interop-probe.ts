import { randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join, relative, resolve, sep } from "node:path";
import { ModBridgeServer } from "./mod-bridge-server.js";
import type { ModConnection } from "./mod-connection.js";
import type { EventEnvelope } from "../../protocol/types.js";

type Position = { readonly x: number; readonly y: number; readonly z: number };
type JournalReceipt = { readonly id?: string | { value?: string }; readonly state?: string; readonly decision?: string };
type Fixture = { readonly botId: string; readonly entityId?: string; readonly bodyGeneration: number; readonly position: Position; readonly name?: string; readonly inventory?: readonly unknown[]; readonly actionJournal?: readonly JournalReceipt[] };
type Snapshot = { readonly worldId?: string; readonly companions?: readonly Fixture[] };

interface ProbeReport {
  readonly protocol: "hearthcrew.v1";
  readonly test: "transport-interop";
  readonly status: "PASS" | "FAIL";
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly pairingPath: string;
  readonly reportPath: string;
  readonly serverPort: number;
  readonly connectionReady: boolean;
  readonly fixture?: { readonly botId: string; readonly bodyGeneration: number; readonly target: Position };
  readonly checks: Record<string, unknown>;
  readonly eventAcksAccepted: number;
  readonly failure?: string;
}

function flag(name: string, fallback: string): string {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const testRunId = flag("--run-id", "");
const fixtureName = `interop_fixture_${testRunId}`;

function ownedRuntimePath(value: string): string {
  const path = resolve(value);
  const rel = relative(resolve(process.cwd()), path).toLowerCase();
  if (rel === ".." || rel.startsWith(`..${sep}`) || !(rel === ".runtime" || rel.startsWith(`.runtime${sep}`))) {
    throw new Error("interop probe paths must stay under the ignored HearthCrew .runtime directory");
  }
  return path;
}

async function waitUntil<T>(read: () => Promise<T>, ready: (value: T) => boolean, timeoutMs: number, label: string): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | undefined;
  while (Date.now() < deadline) {
    last = await read();
    if (ready(last)) return last;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new Error(`${label} timed out${last === undefined ? "" : `; last=${JSON.stringify(last).slice(0, 1000)}`}`);
}

async function waitForConnection(read: () => Promise<ModConnection>, timeoutMs: number): Promise<ModConnection> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      read(),
      new Promise<ModConnection>((_, reject) => {
        timer = setTimeout(() => reject(new Error("Mod did not complete the authenticated handshake")), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function fixture(snapshot: Snapshot): Fixture {
  const found = snapshot.companions?.find((candidate) => candidate.name === fixtureName);
  if (!found || typeof found.botId !== "string" || !Number.isSafeInteger(found.bodyGeneration) || !found.position) throw new Error(`${fixtureName} was not present in the actual Mod status response`);
  return found;
}

function receiptId(receipt: JournalReceipt): string | undefined {
  return typeof receipt.id === "string" ? receipt.id : receipt.id?.value;
}

function hasReceipt(body: Fixture, actionId: string, state?: string): boolean {
  return (body.actionJournal ?? []).some((receipt) => receiptId(receipt) === actionId && (state === undefined || receipt.state === state));
}

function receiptState(receipt: unknown): string | undefined {
  if (!receipt || typeof receipt !== "object") return undefined;
  const state = (receipt as { state?: unknown }).state;
  return typeof state === "string" ? state : undefined;
}

async function main(): Promise<void> {
  if (!/^[a-zA-Z0-9-]{1,48}$/.test(testRunId)) throw new Error("provide a unique --run-id (1–48 letters, digits or hyphens), matching hearthcrewInteropRunId");
  const startedAt = new Date().toISOString();
  const pairingPath = ownedRuntimePath(flag("--pairing", join(process.cwd(), ".runtime", "interop", "pairing.json")));
  const reportPath = ownedRuntimePath(flag("--report", join(process.cwd(), ".runtime", "interop", "report.json")));
  await mkdir(resolve(pairingPath, ".."), { recursive: true });
  await mkdir(resolve(reportPath, ".."), { recursive: true });

  const token = randomBytes(32).toString("hex");
  const server = new ModBridgeServer({ token });
  let active: ModConnection | undefined;
  let readyResolve!: (connection: ModConnection) => void;
  let readyReject!: (error: Error) => void;
  let readyPromise = new Promise<ModConnection>((resolveReady, rejectReady) => { readyResolve = resolveReady; readyReject = rejectReady; });
  let eventAcksAccepted = 0;
  const availableEvents = new Map<string, EventEnvelope>();
  const checks: Record<string, unknown> = {};
  server.onConnection((connection) => {
    active = connection;
    connection.onEvent(event => {
      eventAcksAccepted += 1;
      if (event.event === "body.available") availableEvents.set(event.eventId, event);
      return true;
    });
    readyResolve(connection);
  });

  let report: ProbeReport;
  let serverPort = 0;
  let fixtureInfo: ProbeReport["fixture"];
  try {
    serverPort = await server.start();
    // The token is intentionally written only to this ignored, probe-owned pairing file.
    await writeFile(pairingPath, JSON.stringify({ port: serverPort, token }), { encoding: "utf8" });
    const connection = await waitForConnection(() => readyPromise, 60_000);
    checks.handshake = "authenticated";
    const initialSessionEpoch = connection.sessionContext?.sessionEpoch;
    if (typeof initialSessionEpoch !== "number" || !Number.isSafeInteger(initialSessionEpoch)) throw new Error("authenticated connection did not expose a valid session epoch");
    // Gradle/GameTest may finish its server bootstrap after the Mod socket handshake.
    // Wait for the named fixture in the actual status response before deriving targets.
    const initial = await waitUntil(
      () => connection.request<Snapshot>("status", {}),
      (snapshot) => (snapshot.companions ?? []).filter((candidate) => candidate.name === fixtureName).length === 1,
      60_000,
      "interop_fixture creation",
    );
    const body = fixture(initial);
    if (!Array.isArray(body.inventory) || body.inventory.length !== 0) throw new Error("interop_fixture did not start with an empty actual inventory");
    const target: Position = { x: Math.floor(body.position.x) + 6, y: Math.floor(body.position.y), z: Math.floor(body.position.z) };
    fixtureInfo = { botId: body.botId, bodyGeneration: body.bodyGeneration, target };
    checks.emptyInventory = true;
    checks.fixtureName = fixtureName;
    checks.testRunId = testRunId;
    const availability = await waitUntil(async () => [...availableEvents.values()].filter(event => {
      if (!event.body || typeof event.body !== "object" || Array.isArray(event.body)) return false;
      const available = event.body as Record<string, unknown>;
      return event.worldId === initial.worldId && available.botId === body.botId
        && available.entityId === body.entityId && available.bodyGeneration === body.bodyGeneration;
    }),
      events => events.length > 0, 10_000, "actual body.available event");
    checks.bodyAvailable = { eventId: availability[0].eventId, worldId: availability[0].worldId, body: availability[0].body };

    // A long session's diagnostic reads must not consume the Mod's bounded
    // mutation-id ledger (4096 entries) and make later commands unavailable.
    for (let start = 0; start < 4100; start += 20) {
      const observations = await Promise.all(Array.from({ length: Math.min(20, 4100 - start) }, (_, offset) =>
        connection.request<Snapshot>("status", {}, { requestId: `interop-read-${start + offset}`, timeoutMs: 10_000 })));
      if (observations.some(snapshot => snapshot.worldId !== initial.worldId)) throw new Error("read stress changed world identity");
    }
    checks.readOnlyRequestsBeforeMutation = 4100;

    const moveBody = { botId: body.botId, kind: "move", position: target };
    const moveOptions = { requestId: "interop-move-request", intentId: "interop-intent", actionId: "interop-move", bodyGeneration: body.bodyGeneration, timeoutMs: 10_000 };
    const firstReceipt = await connection.request("action.submit", moveBody, moveOptions);
    if (receiptState(firstReceipt) !== "ACCEPTED") throw new Error(`MOVE was not accepted: ${JSON.stringify(firstReceipt).slice(0, 500)}`);
    checks.moveAccepted = true;
    const secondReceipt = await connection.request("action.submit", moveBody, moveOptions);
    checks.sameIdRetrySameReceipt = JSON.stringify(firstReceipt) === JSON.stringify(secondReceipt);
    if (!checks.sameIdRetrySameReceipt) throw new Error("same requestId/actionId retry returned a different receipt");

    const afterMove = await waitUntil(
      async () => fixture(await connection.request<Snapshot>("status", {}, { timeoutMs: 10_000 })),
      (current) => {
        return current.botId === body.botId && hasReceipt(current, "interop-move", "COMPLETED") && Math.hypot(current.position.x - (target.x + 0.5), current.position.z - (target.z + 0.5)) <= 1.25;
      },
      30_000,
      "actual MOVE postcondition",
    );
    const moved = afterMove;
    checks.actualPositionAndJournal = { position: moved.position, completed: hasReceipt(moved, "interop-move", "COMPLETED") };

    const wrongGenerationRequestId = "interop-wrong-generation-request";
    let wrongGenerationResponse: { readonly ok?: unknown; readonly error?: { readonly code?: unknown; readonly message?: unknown } } | undefined;
    const captureWrongGenerationResponse = (message: unknown) => {
      if (!message || typeof message !== "object") return;
      const candidate = message as { readonly kind?: unknown; readonly requestId?: unknown; readonly ok?: unknown; readonly error?: { readonly code?: unknown; readonly message?: unknown } };
      if (candidate.kind === "response" && candidate.requestId === wrongGenerationRequestId) wrongGenerationResponse = candidate;
    };
    connection.on("message", captureWrongGenerationResponse);
    let wrongGenerationError: unknown;
    try {
      await connection.request("action.submit", { ...moveBody, position: { x: target.x + 1, y: target.y, z: target.z } }, { requestId: wrongGenerationRequestId, intentId: "interop-wrong-generation-intent", actionId: "interop-wrong-generation", bodyGeneration: body.bodyGeneration + 1, timeoutMs: 5_000 });
    } catch (error) {
      wrongGenerationError = error;
    } finally {
      connection.off("message", captureWrongGenerationResponse);
    }
    if (!wrongGenerationError) throw new Error("wrong bodyGeneration was accepted");
    if (wrongGenerationResponse?.ok !== false || wrongGenerationResponse.error?.code !== "REQUEST_REJECTED" || typeof wrongGenerationResponse.error.message !== "string" || !/body generation|bodyGeneration|generation/i.test(wrongGenerationResponse.error.message)) {
      throw new Error(`wrong bodyGeneration did not produce the required REQUEST_REJECTED response: ${JSON.stringify(wrongGenerationResponse).slice(0, 500)}`);
    }
    checks.wrongBodyGenerationRejected = { code: wrongGenerationResponse.error.code, reason: wrongGenerationResponse.error.message };

    // Hold the actual executor's lease clock during this accepted-action
    // cancellation test. GameTest ticks are accelerated relative to TCP, so a
    // short MOVE could validly finish before a separate cancel frame arrives.
    await connection.request("control", { botId: body.botId, operation: "pause" }, { bodyGeneration: body.bodyGeneration });
    await connection.request("action.submit", { botId: body.botId, kind: "wait", count: 200 }, { requestId: "interop-cancel-submit-request", intentId: "interop-cancel-intent", actionId: "interop-cancel", bodyGeneration: body.bodyGeneration, timeoutMs: 5_000 });
    const cancelReceipt = await connection.request("action.cancel", { botId: body.botId, intentId: "interop-cancel-intent", actionId: "interop-cancel", bodyGeneration: body.bodyGeneration, reason: "interop cancellation" }, { requestId: "interop-cancel-request", intentId: "interop-cancel-intent", actionId: "interop-cancel", bodyGeneration: body.bodyGeneration, timeoutMs: 5_000 });
    if (receiptState(cancelReceipt) !== "CANCELLED") throw new Error(`cancel did not return a CANCELLED receipt: ${JSON.stringify(cancelReceipt).slice(0, 500)}`);
    checks.cancelResponse = cancelReceipt;
    await connection.request("control", { botId: body.botId, operation: "resume" }, { bodyGeneration: body.bodyGeneration });
    if (eventAcksAccepted < 1) throw new Error("no real Mod event was acknowledged");
    checks.eventAcks = { accepted: eventAcksAccepted, evidence: "Mod event handler returned accepted=true" };

    const oldConnection = active;
    if (!oldConnection) throw new Error("active connection disappeared before reconnect test");
    oldConnection.close();
    readyPromise = new Promise<ModConnection>((resolveReady, rejectReady) => { readyResolve = resolveReady; readyReject = rejectReady; });
    const reconnected = await waitUntil(async () => {
      if (active && active !== oldConnection) return active;
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
      return undefined;
    }, (candidate): candidate is ModConnection => !!candidate, 60_000, "Mod reconnect");
    if (!reconnected) throw new Error("Mod reconnect returned no connection");
    const reconnectedSessionEpoch = reconnected.sessionContext?.sessionEpoch;
    if (typeof reconnectedSessionEpoch !== "number" || !Number.isSafeInteger(reconnectedSessionEpoch) || reconnectedSessionEpoch <= initialSessionEpoch) throw new Error(`reconnect did not advance session epoch: initial=${initialSessionEpoch}, next=${String(reconnectedSessionEpoch)}`);
    const reconciled = await reconnected.request<Snapshot>("reconcile", { botId: body.botId, bodyGeneration: body.bodyGeneration }, { requestId: "interop-reconcile-request", bodyGeneration: body.bodyGeneration, timeoutMs: 10_000 });
    const reconciledBody = fixture(reconciled);
    if (reconciled.worldId !== initial.worldId || reconciledBody.botId !== body.botId || !hasReceipt(reconciledBody, "interop-move", "COMPLETED")) throw new Error("reconcile did not confirm the actual world/action journal");
    checks.reconnectReconcile = { sameWorld: reconciled.worldId === initial.worldId, sameBot: reconciledBody.botId === body.botId, moveStillCompleted: true, initialSessionEpoch, reconnectedSessionEpoch };
    checks.actualJournalAfterReconnect = reconciledBody.actionJournal;
    const finishBody = { botId: body.botId, kind: "wait", count: 1 };
    await reconnected.request("action.submit", finishBody, { requestId: "interop-finish-request", intentId: "interop-finish-intent", actionId: "interop-finish", bodyGeneration: body.bodyGeneration, timeoutMs: 5_000 });
    const afterFinish = await waitUntil(
      async () => fixture(await reconnected.request<Snapshot>("status", {}, { timeoutMs: 10_000 })),
      (current) => {
        return current.botId === body.botId && hasReceipt(current, "interop-move", "COMPLETED") && hasReceipt(current, "interop-finish", "COMPLETED") && Math.hypot(current.position.x - (target.x + 0.5), current.position.z - (target.z + 0.5)) <= 1.25;
      },
      10_000,
      "actual final probe postcondition",
    );
    const finishedBody = afterFinish;
    checks.finalWaitCompleted = hasReceipt(finishedBody, "interop-finish", "COMPLETED");
    checks.actualPositionAndJournalAfterFinalWait = { position: finishedBody.position, moveCompleted: hasReceipt(finishedBody, "interop-move", "COMPLETED"), finishCompleted: checks.finalWaitCompleted };
    checks.noModelTurn = true;
    report = { protocol: "hearthcrew.v1", test: "transport-interop", status: "PASS", startedAt, finishedAt: new Date().toISOString(), pairingPath, reportPath, serverPort, connectionReady: true, fixture: fixtureInfo, checks, eventAcksAccepted, };
  } catch (error) {
    readyReject(error instanceof Error ? error : new Error(String(error)));
    report = { protocol: "hearthcrew.v1", test: "transport-interop", status: "FAIL", startedAt, finishedAt: new Date().toISOString(), pairingPath, reportPath, serverPort, connectionReady: active !== undefined, fixture: fixtureInfo, checks, eventAcksAccepted, failure: error instanceof Error ? error.message : String(error) };
    process.exitCode = 1;
  } finally {
    await server.stop();
    await writeFile(reportPath, JSON.stringify(report!, null, 2), { encoding: "utf8" });
  }
}

main().catch((error) => { console.error(`interop probe failed: ${error instanceof Error ? error.message : String(error)}`); process.exitCode = 1; });
