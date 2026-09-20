/** Execution negotiation is separate from the framing protocol and UI snapshot version. */
export interface ExecutionBackendDescriptor {
  readonly id: "numen" | "legacy";
  readonly version: string;
  readonly bodyType: "ServerPlayer";
  readonly executionProtocol: 6;
  readonly actions: readonly string[];
  readonly continuousTasks: boolean;
  readonly preparationCheckpoints: boolean;
  readonly pauseResume: boolean;
  readonly bodyGenerations: boolean;
  readonly terminalFinality: boolean;
  readonly exactMoveCompletion?:boolean;
  readonly navigationProgressVersion?:number;
  readonly boundedNoProgress?:boolean;
  readonly facilityStanceVerification?:boolean;
}

export function isExecutionBackend(value: unknown): value is ExecutionBackendDescriptor {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  return (v.id === "numen" || v.id === "legacy") && typeof v.version === "string" && v.version.length > 0 && v.version.length <= 128 &&
    v.bodyType === "ServerPlayer" && v.executionProtocol === 6 && Array.isArray(v.actions) && v.actions.length > 0 && v.actions.length <= 64 &&
    v.actions.every(a => typeof a === "string" && /^[A-Z][A-Z_]{0,63}$/.test(a)) && new Set(v.actions).size === v.actions.length &&
    ["continuousTasks", "preparationCheckpoints", "pauseResume", "bodyGenerations", "terminalFinality"].every(k => typeof v[k] === "boolean");
}

/** Return an actionable incompatibility; never silently ignore a new execution contract. */
export function executionCompatibility(protocol: unknown, backend: ExecutionBackendDescriptor | undefined, supportedProtocol: number): string | undefined {
  if (protocol === undefined && backend === undefined) return undefined; // legacy framing peers
  if (typeof protocol !== "number" || !Number.isSafeInteger(protocol) || protocol < 1) return "INVALID_EXECUTION_PROTOCOL";
  if (protocol > supportedProtocol) return `EXECUTION_PROTOCOL_UNSUPPORTED:${protocol}>${supportedProtocol}`;
  if (protocol < 6) return backend ? "BACKEND_REQUIRES_EXECUTION_PROTOCOL_6" : undefined;
  if (protocol !== 6 || !backend || backend.executionProtocol !== protocol) return "EXECUTION_BACKEND_DESCRIPTOR_REQUIRED";
  for (const key of ["continuousTasks", "preparationCheckpoints", "pauseResume", "bodyGenerations", "terminalFinality"] as const) {
    if (!backend[key]) return `EXECUTION_BACKEND_CAPABILITY_MISSING:${key}`;
  }
  if(backend.id==="numen"&&(backend.navigationProgressVersion!==1||backend.boundedNoProgress!==true||backend.facilityStanceVerification!==true))return "BACKEND_0_3_1_REQUIRED: update Mod and controller together";
  if(backend.id==="numen"&&backend.exactMoveCompletion!==true)return "BACKEND_0_3_2_REQUIRED: update Mod and controller together";
  return undefined;
}
