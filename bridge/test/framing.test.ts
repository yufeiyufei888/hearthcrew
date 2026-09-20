import test from "node:test";
import assert from "node:assert/strict";
import { encodeFrame, FrameDecoder, FrameProtocolError } from "../src/framing.js";

test("length prefix decoder handles fragmentation and coalesced frames", () => {
  const first = encodeFrame({ n: 1 });
  const second = encodeFrame({ n: 2, text: "炉火" });
  const decoder = new FrameDecoder();
  const all = Buffer.concat([first, second]);
  const outputs: Buffer[] = [];
  for (let i = 0; i < all.length; i += 2) outputs.push(...decoder.push(all.subarray(i, i + 2)));
  assert.deepEqual(outputs.map((frame) => JSON.parse(frame.toString("utf8"))), [{ n: 1 }, { n: 2, text: "炉火" }]);
});

test("decoder rejects zero and oversized payloads", () => {
  const decoder = new FrameDecoder(8);
  assert.throws(() => decoder.push(Buffer.from([0, 0, 0, 0])), FrameProtocolError);
  assert.throws(() => decoder.push(Buffer.from([0, 0, 0, 9])), FrameProtocolError);
});

test("encoder enforces the one MiB protocol boundary", () => {
  assert.throws(() => encodeFrame({ value: "x".repeat(9) }, 8), FrameProtocolError);
});
