import { createServer, type Server, type Socket } from "node:net";
import { ModConnection, type ModConnectionOptions } from "./mod-connection.js";

export interface ModBridgeServerOptions extends ModConnectionOptions {
  readonly host?: string;
  readonly port?: number;
}

/** Loopback-only listener used by the controller. The Minecraft server connects to it. */
export class ModBridgeServer {
  private readonly server: Server;
  private readonly connections = new Set<ModConnection>();
  private readonly readyHandlers = new Set<(connection: ModConnection) => void>();
  private listening = false;
  private boundPort?: number;

  constructor(private readonly options: ModBridgeServerOptions) {
    const host = options.host ?? "127.0.0.1";
    if (host !== "127.0.0.1" && host !== "localhost" && host !== "::1") throw new Error("ModBridgeServer only permits loopback hosts");
    this.server = createServer((socket: Socket) => this.accept(socket));
    this.server.on("error", () => { /* surfaced by start() and connection lifecycle */ });
  }

  get port(): number | undefined { return this.boundPort; }
  get activeConnections(): readonly ModConnection[] { return [...this.connections]; }

  async start(): Promise<number> {
    if (this.listening && this.boundPort !== undefined) return this.boundPort;
    await new Promise<void>((resolve, reject) => {
      const onError = (error: Error) => { this.server.off("listening", onListening); reject(error); };
      const onListening = () => { this.server.off("error", onError); this.listening = true; this.boundPort = (this.server.address() as { port: number }).port; resolve(); };
      this.server.once("error", onError);
      this.server.once("listening", onListening);
      this.server.listen(this.options.port ?? 0, this.options.host ?? "127.0.0.1");
    });
    return this.boundPort!;
  }

  async stop(): Promise<void> {
    for (const connection of this.connections) connection.close();
    this.connections.clear();
    if (!this.listening) return;
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
    this.listening = false;
    this.boundPort = undefined;
  }

  onConnection(handler: (connection: ModConnection) => void): this {
    this.readyHandlers.add(handler);
    return this;
  }

  private accept(socket: Socket): void {
    const connection = new ModConnection(socket, this.options);
    this.connections.add(connection);
    connection.once("disconnect", () => this.connections.delete(connection));
    connection.once("ready", () => { for (const handler of this.readyHandlers) handler(connection); });
  }
}
