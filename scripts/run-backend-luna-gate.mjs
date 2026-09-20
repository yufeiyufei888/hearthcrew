/** Explicit user-authorized bounded REAL model gate. No client/private save/model rescue. */
import {readFile,writeFile,mkdir,open,unlink,appendFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {resolve,dirname,join} from 'node:path';
import {randomBytes} from 'node:crypto';
import {AppServerClient} from '../bridge/dist/bridge/src/app-server-client.js';
import {CrewController} from '../bridge/dist/bridge/src/crew-controller.js';
import {CrewJournal} from '../bridge/dist/bridge/src/crew-journal.js';
import {ModBridgeServer} from '../bridge/dist/bridge/src/mod-bridge-server.js';
import {executionRuntime} from '../bridge/dist/bridge/src/execution-runtime.js';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
function arg(name){const index=process.argv.indexOf(name);if(index<0||!process.argv[index+1])throw Error(`Required ${name}`);return process.argv[index+1];}
const phase=arg('--phase'),runId=arg('--run-id'),bundle=resolve(arg('--dependency-bundle'));
if(!['single','crew'].includes(phase)||!/^[a-zA-Z0-9-]{1,64}$/.test(runId))throw Error('Invalid explicit phase/run id');
const base=join(root,'.artifacts/backend-v03'),control=join(root,'.runtime/backend-v03/model-runs',runId),budgetFile=join(base,'luna-budget.json');
await mkdir(base,{recursive:true});await mkdir(control,{recursive:true});
const lock=await open(join(base,'luna-gate.lock'),'wx');await lock.writeFile(JSON.stringify({pid:process.pid,runId,phase}));await lock.close();
let app,bridge,child,timer,traceTail=Promise.resolve(),finished=false,firstTurnAt,run,ledger;
const evidence=join(base,`${runId}-luna-summary.json`);
let reason='startup failed',physicalPassed=false,modelContract=true,actualThreads=new Map();
const readJSON=async path=>JSON.parse(await readFile(path,'utf8'));
const saveBudget=()=>writeFile(budgetFile,JSON.stringify(ledger,null,2));
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
try{
    try{ledger=await readJSON(budgetFile);}catch(e){if(e.code!=='ENOENT')throw e;ledger={schema:1,limitSecondsPerPhase:900,runs:[]};}
    if(ledger.runs.some(r=>r.runId===runId))throw Error('Run ID used; never overwrite prior model evidence');
    if(phase==='crew'&&!ledger.runs.some(r=>r.phase==='single'&&r.passed===true))throw Error('Single Luna must pass before three-Luna gate');
    const used=ledger.runs.filter(r=>r.phase===phase).reduce((sum,r)=>sum+(r.startedAt?(r.finishedAt?(Date.parse(r.finishedAt)-Date.parse(r.startedAt))/1000:900):0),0);
    const remaining=Math.max(0,900-used);if(remaining<10)throw Error('Cumulative real-Luna phase budget exhausted');
    run={runId,phase,remainingSecondsAtStart:remaining,passed:false};ledger.runs.push(run);await saveBudget();
    await writeFile(join(control,'authorization.json'),JSON.stringify({companions:phase==='single'?1:3,maximumSeconds:remaining,fixture:'controlled-peaceful-resource-landscape',highLevelOnly:true}),{flag:'wx'});
    const installed=await readJSON(join(process.env.LOCALAPPDATA,'HearthCrew/runtime/runtime-metadata.json'));
    const workspace=join(process.env.LOCALAPPDATA,'HearthCrew/backend-v03-model-workspace');await mkdir(workspace,{recursive:true});
    const controller=new CrewController(new CrewJournal(join(control,'crew-events.jsonl')));await controller.initialize();
    app=new AppServerClient({runtime:{codexHome:installed.codexRuntime,workspace,codexCommand:installed.codexCommand},diagnosticRawEvents:true,dynamicToolHandler:p=>controller.toolCall(p)});
    app.on('notification',notification=>{
        const item=notification.params?.item;
        const keep=['turn/started','turn/completed','error','warning'].includes(notification.method)
            ||notification.method==='rawResponseItem/completed'&&['custom_tool_call','custom_tool_call_output','function_call','function_call_output'].includes(item?.type)
            ||notification.method==='item/completed'&&item?.type==='agentMessage';
        if(keep)traceTail=traceTail.then(()=>appendFile(join(control,'model-tools-PRIVATE.jsonl'),JSON.stringify({at:new Date().toISOString(),...notification})+'\n'));
    });
    await app.start();const diagnostics=await app.readOnlyDiagnostics();
    if(!diagnostics.account?.account||diagnostics.mcpServers?.data?.length!==0)throw Error('Dedicated authenticated game-only runtime required');
    await controller.setBrain({
        createThread:async(id,backend)=>{
            const thread=await app.createThread(id,backend);actualThreads.set(thread.threadId,{id,model:thread.model});
            if(thread.model!=='gpt-5.6-luna'||actualThreads.size>(phase==='single'?1:3)){modelContract=false;throw Error('Independent Luna contract not met');}
            return thread;
        },
        startTurn:async(thread,input,profile,generation)=>{
            if(finished)throw Error('Trial ended; no late model turn');
            if(profile.model!=='gpt-5.6-luna'||profile.reasoningEffort!=='high'){modelContract=false;throw Error('Luna/high required');}
            if(!firstTurnAt){firstTurnAt=Date.now();run.startedAt=new Date(firstTurnAt).toISOString();await saveBudget();console.log(`REAL_LUNA_STARTED phase=${phase} remainingSeconds=${remaining.toFixed(1)}`);}
            if(Date.now()-firstTurnAt>=(remaining-3)*1000)throw Error('Bounded trial time exhausted');
            return app.startTurn(thread,input,profile,generation);
        },
        diagnostics:()=>({...app.diagnostics(),authenticated:true}),
    });
    const token=randomBytes(32).toString('hex');bridge=new ModBridgeServer({token,...executionRuntime('numen')});
    bridge.onConnection(connection=>{void controller.attach(connection).catch(e=>{reason=`connection reconciliation: ${e.message}`;finished=true;});});
    const port=await bridge.start();await writeFile(join(control,'pairing.json'),JSON.stringify({port,token}));
    const gradle=resolve(root,'../.tool-cache/gradle-9.2.0/bin/gradle.bat');
    const args=['-PbackendLab','-PbackendHost=true',`-PbackendRunId=${runId}`, '-PbackendSuite=autonomy',`-PlunaControlDirectory=${control}`,`-PbackendDependencyBundle=${bundle}`,':backend-lab:runGameTestServer','--console=plain','--no-daemon'];
    // PowerShell literal arguments avoid cmd's nested quoting across Windows paths.
    const literal=s=>"'"+s.replaceAll("'","''")+"'";
    const launchFile=join(control,'launch-fixture.ps1');
    await writeFile(launchFile,`& ${literal(gradle)} @(${args.map(literal).join(',')})\nexit $LASTEXITCODE\n`);
    child=spawn('powershell.exe',['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',launchFile],{cwd:root,windowsHide:true,env:{...process.env,JAVA_HOME:join(process.env.APPDATA,'.minecraft/runtime/java-runtime-delta'),GRADLE_USER_HOME:resolve(root,'../.tool-cache/gradle-user-home')},stdio:['ignore','pipe','pipe']});
    const gameLog=createWriteStream(join(base,`${runId}-game.log`),{flags:'wx'});child.stdout.pipe(gameLog);child.stderr.pipe(gameLog,{end:false});
    let exited=false;child.on('exit',code=>{exited=true;run.serverExitCode=code;});child.on('error',e=>{reason=e.message;finished=true;});
    const boot=Date.now();let terminalFaultSince;
    while(!finished){
        const status=controller.diagnosticSnapshot();await writeFile(join(control,'controller-diagnostics.json'),JSON.stringify(status,null,2));
        try{const outcome=await readJSON(join(control,'physical-result.json'));physicalPassed=outcome.passed===true;reason=physicalPassed?'physical production criteria met':'physical criteria failed';break;}catch(e){if(e.code!=='ENOENT'&&!(e instanceof SyntaxError))throw e;}
        if(exited){reason='headless server exited before physical success';break;}
        if(firstTurnAt&&Date.now()-firstTurnAt>=(remaining-3)*1000){reason='phase budget exhausted';break;}
        if(!firstTurnAt&&Date.now()-boot>180000){reason='no first model turn within startup window';break;}
        const roles=status.roles??status.companions??[];
        const everyFailed=roles.length===(phase==='single'?1:3)&&roles.every(r=>r.status==='failed'&&!r.pending?.length);
        terminalFaultSince=everyFailed?(terminalFaultSince??Date.now()):undefined;
        if(terminalFaultSince&&Date.now()-terminalFaultSince>10000){reason='all trial roles stalled in explicit model/planning fault';break;}
        await pause(1000);
    }
    finished=true;
    await writeFile(join(control,'abort.request'),reason);
    await bridge.stop();await app.stop();await traceTail;
    if(firstTurnAt){run.finishedAt=new Date().toISOString();run.modelWallSeconds=(Date.now()-firstTurnAt)/1000;}
    run.passed=physicalPassed&&modelContract&&actualThreads.size===(phase==='single'?1:3);run.reason=reason;run.threads=[...actualThreads].map(([threadId,data])=>({threadId,...data,reasoningEffort:'high'}));await saveBudget();
    for(let i=0;i<20&&!exited;i++)await pause(1000);
    if(!exited)throw Error('Headless server has not stopped; inspect exact test process before cleanup');
    if(run.serverExitCode!==0){run.passed=false;if(physicalPassed)reason='physical criteria recorded but server shutdown failed';await saveBudget();}
}catch(error){reason=error.message;process.exitCode=1;}
finally{
    finished=true;clearInterval(timer);
    await writeFile(join(control,'abort.request'),reason).catch(()=>{});
    await bridge?.stop().catch(()=>{});await app?.stop().catch(()=>{});await traceTail.catch(()=>{});
    if(run){if(firstTurnAt&&!run.finishedAt){run.finishedAt=new Date().toISOString();run.modelWallSeconds=(Date.now()-firstTurnAt)/1000;}run.reason=reason;await saveBudget();}
    await writeFile(evidence,JSON.stringify({runId,phase,reason,physicalPassed,modelContract,run,privateData:control,clientStarted:false,installed:false},null,2));
    await unlink(join(base,'luna-gate.lock'));
    console.log(JSON.stringify({runId,phase,passed:run?.passed===true,reason,modelWallSeconds:run?.modelWallSeconds??0,summary:evidence}));
    if(!run?.passed)process.exitCode=1;
}
