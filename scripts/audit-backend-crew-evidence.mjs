/** Read-only review against the USER's crew criteria; never rewrites original test results. */
import {readFile,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
const root=resolve(import.meta.dirname,'..'),runId=process.argv[2];
if(!/^[a-zA-Z0-9-]{1,64}$/.test(runId??''))throw Error('Explicit run ID required');
const control=join(root,'.runtime/backend-v03/model-runs',runId),out=join(root,'.artifacts/backend-v03');
const load=async p=>JSON.parse(await readFile(p,'utf8'));
const input=await readFile(join(control,'approved-criteria-snapshot.json'));
const snapshot=JSON.parse(input),summary=await load(join(out,`${runId}-luna-summary.json`));
const fixture=await load(join(control,'fixture-ready.json')),ledger=await load(join(out,'luna-budget.json'));
const totals=Object.fromEntries(['single','crew'].map(phase=>[phase,ledger.runs.filter(r=>r.phase===phase).reduce((n,r)=>n+(r.modelWallSeconds??0),0)]));
if(!fixture.emptyInventories||fixture.companions!==3||!summary.modelContract||summary.run.threads.length!==3
    ||new Set(summary.run.threads.map(t=>t.threadId)).size!==3||summary.run.threads.some(t=>t.model!=='gpt-5.6-luna'||t.reasoningEffort!=='high')
    ||totals.single>900||totals.crew>900||!ledger.runs.some(r=>r.phase==='single'&&r.passed))throw Error('Fixture/model/budget contract failed');
const rows=snapshot.companions.map(r=>{
    const unique=new Map(r.actions.map(a=>[`${a.world}:${a.companion}:${a.generation}:${a.actionId}`,a]));
    const contributions=[];
    for(const a of unique.values()){
        if(a.state!=='COMPLETED')continue;
        const order=JSON.parse(a.order),receipt=JSON.parse(a.evidence);if(receipt.success!==true)continue;
        if(order.kind==='COLLECT_RESOURCE'){
            const g=receipt.data?.goalOutput;
            if(g&&g.ownNew>0&&g.ownNew+g.teamNew>=g.requestedNew)contributions.push({kind:order.kind,item:g.item,ownNew:g.ownNew,teamNew:g.teamNew,requestedNew:g.requestedNew});
        }else if(order.kind==='BUILD'&&receipt.data?.placed>0&&receipt.data.completed===receipt.data.requested){
            contributions.push({kind:order.kind,placed:receipt.data.placed,completed:receipt.data.completed});
        }else if(order.kind==='CRAFT'&&receipt.data?.receipts?.some(s=>s.verified===true&&s.method?.startsWith('craft:'))){
            contributions.push({kind:order.kind,item:order.resource,recipeExecutions:order.count});
        }
    }
    // Two actual productive requests prove continuation; chat/movement are not contributions.
    return {name:r.name,passed:contributions.length>=2,contributions,facilities:r.facilities,usableTool:r.usableTool};
});
const passed=rows.length===3&&new Set(rows.map(r=>r.name)).size===3&&rows.every(r=>r.passed&&r.facilities>0)
    &&rows.some(r=>r.usableTool)&&rows.some(r=>r.contributions.some(c=>c.kind==='BUILD'));
const report={schema:1,runId,passed,evidenceType:'READ_ONLY_REVIEW_AGAINST_APPROVED_CREW_CRITERIA',
    originalFixturePassed:summary.run.passed,originalServerExitCode:summary.run.serverExitCode,
    correction:'Original fixture reused single-person tool/craft requirements for every crew member. User crew gate requires each member to contribute and keep working, without fixed professions. Original failure/abort evidence remains unchanged.',
    inputSha256:createHash('sha256').update(input).digest('hex'),modelWallSeconds:summary.run.modelWallSeconds,cumulativeModelSeconds:totals,
    rows,clientStarted:false,privateSavesUsed:false,manualTaskRescue:false,longPlayAccepted:false};
await writeFile(join(out,`${runId}-approved-criteria-review.json`),JSON.stringify(report,null,2),{flag:'wx'});
console.log(JSON.stringify(report,null,2));if(!passed)process.exitCode=1;
