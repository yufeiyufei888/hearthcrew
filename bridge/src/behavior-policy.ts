/** Pure policy shared by the real scheduler and deterministic regressions. */
export function consumeNovelFacts(seen: Set<string>, keys: readonly string[]): boolean {
  let fresh = false;
  for (const key of keys) if (!seen.has(key)) { seen.add(key); fresh = true; }
  return fresh;
}
export function goalStateForSource(state: string | undefined, origin: string, command: string | undefined): string | undefined {
  if (state !== "standby") return state;
  return origin === "owner" && /(?:完成后待命|做完后待命|然后待命|完成后停下|完成后停止|then stand by|standby after)/i.test(command ?? "") ? state : "blocked";
}
export function resolveRecipients<T extends {botId: string; name: string}>(value: string, bodies: readonly T[], roles: readonly {botId: string; id: string}[], self?: string): T[] {
  const target = value.trim().replace(/^@/, "").toLowerCase();
  if (["player","玩家","主人"].includes(target)) return [];
  const found = bodies.filter(b => b.botId !== self && (["team","小队","全队","all"].includes(target) || b.botId.toLowerCase() === target || b.name.toLowerCase() === target || roles.some(r => r.botId === b.botId && r.id.toLowerCase() === target)));
  if (!found.length) throw new Error(`recipient not found; valid recipients: ${bodies.map(b=>b.name+" ("+b.botId+")").join(", ")}; team; player`);
  return found;
}

export function sameHistoricalRequest(a: Record<string,unknown>, b: Record<string,unknown>): boolean {
  const canonical = (v: unknown): string => JSON.stringify(v, (_k,x) => x && typeof x === "object" && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a],[b])=>a.localeCompare(b))) : x);
  return ["id","payload","epoch","priority"].every(k => a[k] !== undefined && b[k] !== undefined && canonical(a[k]) === canonical(b[k]));
}
export function chatProgressFact(r: Record<string,unknown>): string | undefined {
  if (r.historical === true || !["COMPLETED","PARTIAL"].includes(String(r.state))) return;
  const p = r.payload as Record<string,unknown>|undefined;
  if (["SELECT","WAIT","BREATHE","SELF_DEFENCE"].includes(String(p?.kind))) return;
  return r.id && r.epoch ? `action:${JSON.stringify(r.id)}:${JSON.stringify(r.epoch)}:${r.state}` : undefined;
}
