import type {ExecutionBackendDescriptor} from "../../protocol/execution-backend.js";

export function backendDefinition(kind:string,definition:Record<string,any>,backend?:ExecutionBackendDescriptor){
  const copy=structuredClone(definition);
  if(backend?.id==="numen"&&kind==="COLLECT_RESOURCE"){
    copy.allowed=copy.allowed?.filter((x:string)=>x!=="position");
    if(copy.example)delete copy.example.position;
    copy.fields=(copy.fields??"")+" 工作范围以身体开始位置为中心；不接受position。";
  }
  if(backend?.id==="numen"&&kind==="EXCAVATE"){
    copy.allowed=["position","count","accessBudget"];
    copy.fields="position是必须实际到达的脚位（不是矿石坐标）；count为破坏上限1..64，实际破坏预算取count与accessBudget较小值，accessBudget默认16。只在起点32格内安全开路，不搭桥、不采集目标矿物；缺工具或保护/液体阻挡时停止。";
    copy.example={position:{x:4,y:64,z:8},count:16,accessBudget:16};
  }
  return {...copy,toolExample:{kind,parameters:copy.example}};
}
/** The transport-negotiated backend is authoritative; old guides are not proof of support. */
export function projectBackendCapabilities<T extends {self: readonly string[]; team: readonly string[]; actions: object}>(base:T, backend?:ExecutionBackendDescriptor) {
  if (!backend) return {...base};
  const declared=new Set(backend.actions);
  const self=base.self.filter(kind=>declared.has(kind));
  return {...base, self, actions:Object.fromEntries(Object.entries(base.actions).filter(([kind])=>self.includes(kind)).map(([kind,definition])=>[kind,backendDefinition(kind,definition,backend)])),
    // The new adapter has not negotiated the legacy team's body-dispatch protocol.
    team:backend.id==="legacy" ? base.team.filter(kind=>declared.has(kind)) : [],
    backend:{id:backend.id,version:backend.version,executionProtocol:backend.executionProtocol},
    unavailable:base.self.filter(kind=>!declared.has(kind)),
    guidance:"仅使用self/actions列出的已接通执行能力。未列出的旧动作当前不可用；队友协商不能绕过实际后端能力或直接改派身体。"};
}
