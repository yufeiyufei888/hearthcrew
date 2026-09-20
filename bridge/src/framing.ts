import { Buffer } from "node:buffer";
import { MAX_FRAME_BYTES } from "../../protocol/types.js";

export class FrameProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FrameProtocolError";
  }
}

/** Decode length-prefixed frames while tolerating arbitrary TCP fragmentation. */
export class FrameDecoder {
  private buffer = Buffer.alloc(0);
  private failed = false;

  constructor(private readonly maxFrameBytes = MAX_FRAME_BYTES) {}

  push(chunk: Uint8Array): Buffer[] {
    if (this.failed) throw new FrameProtocolError("frame decoder is closed after a protocol error");
    if (chunk.byteLength === 0) return [];
    this.buffer = Buffer.concat([this.buffer, Buffer.from(chunk)]);
    const frames: Buffer[] = [];
    try {
      while (this.buffer.byteLength >= 4) {
        const length = this.buffer.readUInt32BE(0);
        if (length === 0) throw new FrameProtocolError("zero-length frame");
        if (length > this.maxFrameBytes) throw new FrameProtocolError(`frame exceeds ${this.maxFrameBytes} bytes`);
        if (this.buffer.byteLength < 4 + length) break;
        frames.push(this.buffer.subarray(4, 4 + length));
        this.buffer = this.buffer.subarray(4 + length);
      }
      return frames;
    } catch (error) {
      this.failed = true;
      throw error;
    }
  }

  get bufferedBytes(): number {
    return this.buffer.byteLength;
  }
}

export function encodeFrame(value: unknown, maxFrameBytes = MAX_FRAME_BYTES): Buffer {
  let payload: Buffer;
  try {
    payload = Buffer.from(JSON.stringify(value), "utf8");
  } catch (error) {
    throw new FrameProtocolError(`message is not JSON serializable: ${String(error)}`);
  }
  if (payload.byteLength === 0) throw new FrameProtocolError("empty JSON payload");
  if (payload.byteLength > maxFrameBytes) throw new FrameProtocolError(`frame exceeds ${maxFrameBytes} bytes`);
  const frame = Buffer.allocUnsafe(4 + payload.byteLength);
  frame.writeUInt32BE(payload.byteLength, 0);
  payload.copy(frame, 4);
  return frame;
}
