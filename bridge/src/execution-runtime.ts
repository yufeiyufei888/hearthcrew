import type {ModConnectionOptions} from "./mod-connection.js";

/** Opt-in until the experimental physical and Luna gates have passed. */
export function executionRuntime(mode:string):Pick<ModConnectionOptions,"capabilities"|"controllerVersion"> {
 if(mode==="legacy")return {};
 if(mode!=="numen")throw Error("execution-backend must be legacy or numen");
 return {controllerVersion:"0.3.2",capabilities:{events:true,acknowledgements:true,reconciliation:true,
  executionProtocol:6,exactMoveCompletion:true,navigationProgressVersion:1,boundedNoProgress:true,facilityStanceVerification:true,maxFrameBytes:1048576,unifiedPreparation:true,resourcePreparation:true,historySeparate:true,
  safeAccessBudget:true,nativePickupPost:true,sharedSequenceBudget:true}};
}
