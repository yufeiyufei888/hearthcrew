import { CrewJournal } from "./crew-journal.js";

export interface CrewChat {
  messageId: string; worldId: string; origin: "player" | "companion";
  senderId: string; senderName: string; recipientIds: string[]; recipientNames: string[];
  purpose?: string; stageId?: string; message: string; replyTo?: string; replyName?: string; depth: number; gameTick: number;
}

/** Durable messages and per-body read receipts, independent of model sessions. */
export class CrewMailbox {
  readonly messages = new Map<string, CrewChat>();
  private readonly reads = new Set<string>();
  private readonly ownerClaims = new Set<string>();
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private readonly journal: CrewJournal) {}
  private key(world: string, id: string): string { return `${world}:${id}`; }
  restore(): void {
    for (const row of this.journal.rows()) {
      if (row.type === "crew.chat") {
        const m = row.data as unknown as CrewChat;
        if (typeof m.messageId !== "string" || m.worldId !== row.worldId || !Array.isArray(m.recipientIds)
          || !Array.isArray(m.recipientNames) || typeof m.message !== "string" || !Number.isInteger(m.depth))
          throw new Error("invalid durable crew chat");
        this.messages.set(this.key(row.worldId, m.messageId), m);
      }
      if (row.type === "chat.read") for (const id of row.data.messageIds as string[]) this.reads.add(`${row.worldId}:${row.data.botId}:${id}`);
      if (row.type === "chat.owner_claim") this.ownerClaims.add(this.key(row.worldId, String(row.data.messageId)));
    }
  }
  get(world: string, id: string): CrewChat | undefined { return this.messages.get(this.key(world, id)); }
  unread(world: string, bot: string): CrewChat[] {
    return [...this.messages.values()].filter(m => m.worldId === world && m.recipientIds.includes(bot)
      && !this.reads.has(`${world}:${bot}:${m.messageId}`)).slice(0, 24);
  }
  async append(message: CrewChat): Promise<{ message: CrewChat; duplicate: boolean }> {
    const operation = this.tail.then(async () => {
      const key = this.key(message.worldId, message.messageId), old = this.messages.get(key);
      if (old) {
        if (old.senderId !== message.senderId || old.message !== message.message || JSON.stringify(old.recipientIds) !== JSON.stringify(message.recipientIds)
          || old.replyTo !== message.replyTo) throw new Error("chat message identity conflict");
        return { message: old, duplicate: true };
      }
      await this.journal.append("crew.chat", message.worldId, message as unknown as Record<string, unknown>);
      this.messages.set(key, message);
      return { message, duplicate: false };
    });
    this.tail = operation.catch(() => {});
    return operation;
  }
  async markRead(world: string, bot: string, ids: string[]): Promise<void> {
    if (!ids.length) return;
    await this.journal.append("chat.read", world, { botId: bot, messageIds: ids });
    ids.forEach(id => this.reads.add(`${world}:${bot}:${id}`));
  }
  /** Claim before retask; uncertain crash outcomes remain visible and are never replayed. */
  async claimOwner(world: string, id: string, bot: string): Promise<boolean> {
    const operation = this.tail.then(async () => {
      const message = this.get(world, id), key = this.key(world, id);
      if (!message || message.origin !== "player" || !message.recipientIds.includes(bot)) throw new Error("not an addressed player message");
      if (this.ownerClaims.has(key)) return false;
      await this.journal.append("chat.owner_claim", world, { messageId: id, botId: bot });
      this.ownerClaims.add(key);
      return true;
    });
    this.tail = operation.catch(() => {});
    return operation;
  }
}
