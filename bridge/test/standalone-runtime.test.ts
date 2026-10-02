import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { once } from "node:events";
import test from "node:test";

// Since the strict 0.3.3 CLI contract, a deliberately missing CLI must fail
// closed. It cannot be a positive startup fixture or publish a stale pairing.
test("standalone missing CLI refuses pairing and releases the owned lock", async () => {
 const root=await mkdtemp(join(tmpdir(),"hearthcrew-standalone-")),state=join(root,"state");
 const child=spawn(process.execPath,[resolve("dist/bridge/src/play-runtime.js"),"--standalone","--state",state,"--runtime",join(root,"codex"),"--workspace",join(root,"workspace"),"--codex-command","hearthcrew-test-missing-codex"],{cwd:resolve(".."),stdio:["pipe","pipe","pipe"],windowsHide:true});
 let stderr="";child.stderr.on("data",chunk=>{stderr+=chunk.toString("utf8");});child.stdin.end();
 try{
  const [code]=await once(child,"exit");assert.equal(code,1);assert.match(stderr,/unsupported Codex CLI version unknown/);
  await assert.rejects(readFile(join(state,"pairing.json")),{code:"ENOENT"});
  await assert.rejects(readFile(join(state,"controller.lock")),{code:"ENOENT"});
  const metadata=JSON.parse(await readFile(join(state,"runtime-metadata.json"),"utf8"));assert.equal(metadata.status,"stopped");assert.equal(metadata.pid,child.pid);
 }finally{if(child.exitCode===null&&child.signalCode===null)child.kill();await rm(root,{recursive:true,force:true});}
});
