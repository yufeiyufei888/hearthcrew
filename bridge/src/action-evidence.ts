/** Results are immutable facts; execution ownership is a separate, current-body lease. */
export class ActionEvidence {
  private readonly entries = new Map<string, {state:string; generation?:number; fingerprint?:string}>();
  static readonly terminal = new Set(["COMPLETED","PARTIAL","FAILED","CANCELLED","REJECTED","STALE","EXPIRED"]);
  key(world:string,bot:string,id:string):string { return JSON.stringify([world,bot,id]); }
  get(world:string,bot:string,id:string) { return this.entries.get(this.key(world,bot,id)); }
  apply(world:string,bot:string,id:string,state:string,generation?:number,fingerprint?:string):boolean {
    state=state.toUpperCase();
    const key=this.key(world,bot,id), previous=this.entries.get(key);
    if(previous?.fingerprint && fingerprint && previous.fingerprint!==fingerprint) return false;
    if(previous?.generation!==undefined && generation!==undefined && previous.generation!==generation) return false;
    if(previous && ActionEvidence.terminal.has(previous.state)) return previous.state===state;
    if(previous?.state==="RUNNING" && ["ACCEPTED","PREPARED"].includes(state)) return false;
    this.entries.set(key,{state,generation:generation??previous?.generation,fingerprint:fingerprint??previous?.fingerprint});
    return true;
  }
  settled(world:string,bot:string,id:string):boolean {return ActionEvidence.terminal.has(this.get(world,bot,id)?.state??"");}
}
