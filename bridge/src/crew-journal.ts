import { open, mkdir, readdir } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { dirname, isAbsolute, basename, join } from "node:path";

export interface CrewRecord { readonly atUtc?: string; readonly version?: string; readonly sequence: number; readonly type: string; readonly worldId: string; readonly data: Record<string, unknown>; }

/** An append-only controller journal. Acknowledgement follows flush, never precedes it. */
export class CrewJournal {
  private records: CrewRecord[] = [];
  private readonly references = new Map<string,CrewRecord>();
  private readonly roleRows=new Map<string,CrewRecord[]>();
  private readonly actionRows=new Map<string,CrewRecord[]>();
  private index(row:CrewRecord){
    if(row.type==="context.archive")this.references.set(`${row.worldId}:${row.data.botId}:${row.data.reference}`,row);
    const body=row.data.body as Record<string,any>|undefined;
    const bot=row.data.botId??body?.botId, action=row.data.actionId??body?.id?.value??body?.actionId;
    if(typeof bot==="string"){
      const key=JSON.stringify([row.worldId,bot]);const rows=this.roleRows.get(key)??[];rows.push(row);this.roleRows.set(key,rows);
      if(typeof action==="string"){const key=JSON.stringify([row.worldId,bot,action]);const rows=this.actionRows.get(key)??[];rows.push(row);this.actionRows.set(key,rows);}
    }
  }
  action(world:string,bot:string,id:string):CrewRecord[]{return (this.actionRows.get(JSON.stringify([world,bot,id]))??[]).map(r=>structuredClone(r));}
  role(world:string,bot:string,predicate:(row:Readonly<CrewRecord>)=>boolean,limit:number):CrewRecord[]{
    const rows=this.roleRows.get(JSON.stringify([world,bot]))??[],result:CrewRecord[]=[];
    for(let i=rows.length-1;i>=0&&result.length<limit;i--)if(predicate(rows[i]))result.push(structuredClone(rows[i]));return result.reverse();
  }
  context(world:string,bot:string,reference:string):CrewRecord|undefined{const r=this.references.get(`${world}:${bot}:${reference}`);return r?structuredClone(r):undefined;}
  contextReferences(world:string,bot:string){return [...this.references.values()].filter(r=>r.worldId===world&&r.data.botId===bot).slice(-16).map(r=>({reference:r.data.reference,path:r.data.path}));}
  private readonly worlds = new Map<string,CrewRecord[]>();
  private writePath: string;
  private segmentRows = 0;
  private pending: {type:string;worldId:string;data:Record<string,unknown>;resolve:(row:CrewRecord)=>void;reject:(reason:unknown)=>void}[]=[];
  private flushing=false;
  private writeError:unknown;
  private loadPromise?: Promise<void>;
  private loaded = false;
  constructor(private readonly path: string, private readonly segmentSize=10_000) { if (!isAbsolute(path)) throw new Error("journal path must be absolute"); if(!Number.isSafeInteger(segmentSize)||segmentSize<1)throw new Error("invalid segment size");this.writePath=path; }
  async load(): Promise<void> {
    if (this.loaded) return;
    if (this.loadPromise) return this.loadPromise;
    const operation = this.loadFromDisk();
    this.loadPromise = operation;
    try { await operation; } finally { if (this.loadPromise === operation) this.loadPromise = undefined; }
  }
  private async loadFromDisk(): Promise<void> {
    await mkdir(dirname(this.path), { recursive: true });
    const parsed: CrewRecord[] = [];
    const prefix=basename(this.path)+".segment-";
    const segments=(await readdir(dirname(this.path))).filter(name=>name.startsWith(prefix)&&/^\d{16}\.jsonl$/.test(name.slice(prefix.length))).sort();
    const files=[this.path,...segments.map(name=>join(dirname(this.path),name))];
    for(const path of files) { let count=0;try {
      // Stream the retained history: an ordinary long session can exceed 32 MiB.
      // Never truncate records or reset sequence IDs to make startup succeed.
      let pending = "";
      for await (const chunk of createReadStream(path, { encoding: "utf8" })) {
        pending += chunk;
        let newline: number;
        while ((newline = pending.indexOf("\n")) >= 0) {
          const line = pending.slice(0, newline);
          pending = pending.slice(newline + 1);
          if (!line) continue;
          const row = JSON.parse(line) as CrewRecord;
          if (!Number.isSafeInteger(row.sequence) || row.sequence !== parsed.length + 1 || typeof row.type !== "string" || !row.type || typeof row.worldId !== "string" || !row.worldId || !row.data || typeof row.data !== "object" || Array.isArray(row.data)) throw new Error("invalid journal record");
          parsed.push({ ...(row.atUtc ? {atUtc:row.atUtc} : {}), ...(row.version ? {version:row.version} : {}), sequence: row.sequence, type: row.type, worldId: row.worldId, data: structuredClone(row.data) });
          count++;
        }
      }
      if (pending) throw new Error("journal has an incomplete write; recovery required");
    } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT" || path!==this.path) throw error; }
      this.writePath=path;this.segmentRows=count;
    }
    this.records = parsed;this.references.clear();this.roleRows.clear();this.actionRows.clear();for(const row of parsed)this.index(row);
    this.worlds.clear();for(const row of parsed){const rows=this.worlds.get(row.worldId)??[];rows.push(row);this.worlds.set(row.worldId,rows);}
    this.loaded = true;
  }
  rows(worldId?: string): readonly CrewRecord[] { return (worldId?this.worlds.get(worldId)??[]:this.records).map(row => structuredClone(row)); }
  select(worldId:string|undefined,predicate:(row:Readonly<CrewRecord>)=>boolean,limit:number,after=0,newest=true):CrewRecord[]{
    const source=worldId?this.worlds.get(worldId)??[]:this.records,result:CrewRecord[]=[];
    for(let n=0;n<source.length&&result.length<limit;n++){const row=source[newest?source.length-1-n:n];if(row.sequence>after&&predicate(row))result.push(structuredClone(row));}
    return newest?result.reverse():result;
  }
  append(type: string, worldId: string, data: Record<string, unknown>): Promise<CrewRecord> {
    const storedData=JSON.parse(JSON.stringify(data)) as Record<string,unknown>;
    if(!this.loaded)return Promise.reject(new Error("journal must be loaded"));
    if(this.writeError)return Promise.reject(this.writeError);
    return new Promise((resolve,reject)=>{
      this.pending.push({type,worldId,data:storedData,resolve,reject});
      if(!this.flushing){this.flushing=true;setImmediate(()=>void this.flush());}
    });
  }
  private async flush():Promise<void>{
    while(this.pending.length){
      if(this.segmentRows>=this.segmentSize){this.writePath=this.path+".segment-"+String(this.records.length+1).padStart(16,"0")+".jsonl";this.segmentRows=0;}
      const jobs=this.pending.splice(0,Math.min(256,this.segmentSize-this.segmentRows));
      const rows: CrewRecord[]=jobs.map((job,i)=>({atUtc:new Date().toISOString(),version:"0.3.2",sequence:this.records.length+i+1,type:job.type,worldId:job.worldId,data:job.data}));
      try{
        const file=await open(this.writePath,"a");
        try{await file.writeFile(rows.map(row=>JSON.stringify(row)+"\n").join(""),"utf8");await file.sync();}finally{await file.close();}
        // Only a durable batch becomes visible or receives an acknowledgement.
        for(let i=0;i<rows.length;i++){const row=rows[i];this.records.push(row);this.index(row);this.segmentRows++;const world=this.worlds.get(row.worldId)??[];world.push(row);this.worlds.set(row.worldId,world);jobs[i].resolve(structuredClone(row));}
      }catch(error){this.writeError=error;for(const job of [...jobs,...this.pending.splice(0)])job.reject(error);break;}
    }
    this.flushing=false;
  }
}
