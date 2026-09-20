type Row = Record<string, unknown>;
function object(value: unknown): Row {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("action receipt must be an object");
  return value as Row;
}
function identity(row: Row): string {
  const id = typeof row.id === "string" ? row.id : object(row.id).value;
  if (typeof id !== "string" || !id) throw new Error("action receipt identity is missing");
  return id;
}
function canonical(value: unknown): string {
  const sort = (input: unknown): unknown => Array.isArray(input) ? input.map(sort)
    : input && typeof input === "object" ? Object.fromEntries(Object.entries(input).sort(([a], [b]) => a.localeCompare(b)).map(([key, child]) => [key, sort(child)])) : input;
  return JSON.stringify(sort(value));
}

/** A journal contains lifecycle transitions, not one row per action. */
export function assertNoExtraScenarioActions(rawRows: readonly unknown[], rawExpected: readonly unknown[]): void {
  const expected = new Map<string, Row>();
  for (const value of rawExpected) {
    const row = object(value); const id = identity(row);
    if (row.state !== "COMPLETED" || expected.has(id)) throw new Error("expected actions must have unique completed identities");
    expected.set(id, row);
  }
  const latest = new Map<string, Row>();
  const completions = new Set<string>();
  for (const value of rawRows) {
    const row = object(value); const id = identity(row); const wanted = expected.get(id);
    if (!wanted || canonical(row.payload) !== canonical(wanted.payload)) throw new Error("unexpected additional or changed body action in the controlled scenario");
    if (!["ACCEPTED", "RUNNING", "SUSPENDED", "COMPLETED"].includes(String(row.state))) throw new Error("unexpected body action outcome in the controlled scenario");
    if (completions.has(id)) throw new Error("body action has transitions after completion");
    if (row.state === "COMPLETED") completions.add(id);
    latest.set(id, row);
  }
  if (latest.size !== expected.size || [...latest.values()].some(row => row.state !== "COMPLETED")) throw new Error("scenario body actions have not all completed");
}
