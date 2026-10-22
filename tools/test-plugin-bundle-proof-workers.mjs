import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';
import {preparePrivatePluginInventory} from './private-plugin-inventory-fixture.mjs';

const S=path.resolve(import.meta.dirname,'..'),assets=path.join(S,'app/src/main/assets');
const raw=testRuntime('raw'),sha=value=>createHash('sha256').update(value).digest('hex');
const own='plugin-bundle-proof-workers-patch.json',moduleName='@deepseek-ai/dsh-plugin-manager/lib/index.js';
let before=fs.readFileSync(path.join(raw,'node_modules',moduleName),'utf8').replaceAll('\r\n','\n');
const canonical=sha(before),inputs={};
for(const row of JSON.parse(fs.readFileSync(path.join(assets,'runtime-patches.json'))).active){
 if(row.asset===own)continue;const file=path.join(assets,row.asset),recipe=JSON.parse(fs.readFileSync(file));if((row.module??recipe.module)!==moduleName)continue;
 if(recipe.sourceSha256!==undefined)assert.equal(recipe.sourceSha256,canonical);inputs[row.asset]=sha(fs.readFileSync(file));
 for(const patch of recipe.patches){assert.equal(before.split(patch.before).length,2,row.asset);before=before.replace(patch.before,(patch.prependAsset?fs.readFileSync(path.join(assets,patch.prependAsset),'utf8')+'\n':'')+patch.after);}
}
const file=path.join(assets,own),recipe=JSON.parse(fs.readFileSync(file));assert.equal(recipe.sourceSha256,canonical);inputs[own]=sha(fs.readFileSync(file));
let after=before;for(const patch of recipe.patches){assert.equal(after.split(patch.before).length,2);after=after.replace(patch.before,patch.after);}
let reversed=after;for(const patch of [...recipe.patches].reverse()){assert.equal(reversed.split(patch.after).length,2);reversed=reversed.replace(patch.after,patch.before);}assert.equal(reversed,before,'proof, classification, row projection, write and removal code outside the anchored loop must remain exact');
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-bundle-proof-workers-'));
fs.symlinkSync(path.join(raw,'node_modules'),path.join(owned,'node_modules'),process.platform==='win32'?'junction':'dir');
const dependency=preparePrivatePluginInventory({source:S,raw,directory:owned,managerSource:before});
const importFrom='from "@deepseek-ai/dsh-host-plugin-inventory"',importTo='from '+JSON.stringify(pathToFileURL(path.join(owned,'dsha-current-plugin-inventory.mjs')).href);
assert.equal(after.split(importFrom).length,2);const beforeFile=path.join(owned,'before.mjs'),afterFile=path.join(owned,'after.mjs');fs.writeFileSync(beforeFile,dependency.source);fs.writeFileSync(afterFile,after.replace(importFrom,importTo));
const [{PluginManager:Before},{PluginManager:After}]=await Promise.all([import(pathToFileURL(beforeFile)),import(pathToFileURL(afterFile))]);
const home=path.join(owned,'home'),profileDir=path.join(home,'profiles/web'),sdk=path.join(owned,'sdk');fs.mkdirSync(profileDir,{recursive:true});fs.mkdirSync(path.join(sdk,'node_modules'),{recursive:true});
const names=['@fixture/first','@fixture/second','@fixture/third'],proofs={},packageDirs=[];
function contentProof(root){const rows=[];function walk(folder,relative=''){for(const name of fs.readdirSync(folder).sort()){const item=path.join(folder,name),key=relative?relative+'/'+name:name,stat=fs.lstatSync(item);if(stat.isDirectory())walk(item,key);else if(stat.isFile())rows.push([key,key+'\0FILE\0'+(stat.mode&511)+'\0'+sha(fs.readFileSync(item))+'\n']);else if(stat.isSymbolicLink())rows.push([key,key+'\0LINK\0'+fs.readlinkSync(item)+'\n']);else throw Error('unexpected fixture node');}}walk(root);return sha(rows.sort(([a],[b])=>a<b?-1:a>b?1:0).map(([,value])=>value).join(''));}
for(const [index,name] of names.entries()){
 const root=path.join(sdk,'node_modules',name);packageDirs.push(root);fs.mkdirSync(path.join(root,'small'),{recursive:true});
 fs.writeFileSync(path.join(root,'package.json'),JSON.stringify({name,version:'1.0.0',dsh:{bundle:{patch:'cordis.patch.yml'}}}));fs.writeFileSync(path.join(root,'cordis.patch.yml'),'[]\n');
 fs.writeFileSync(path.join(root,'payload.bin'),Buffer.alloc(1024*1024,index+1));for(let i=0;i<12;i++)fs.writeFileSync(path.join(root,'small',String(i)+'.txt'),'fixture-'+index+'-'+i);
 proofs[name]=contentProof(root);
}
const library='@fixture/unused-library',libraryDir=path.join(sdk,'node_modules',library);fs.mkdirSync(libraryDir,{recursive:true});fs.writeFileSync(path.join(libraryDir,'package.json'),JSON.stringify({name:library,version:'1.0.0'}));fs.writeFileSync(path.join(libraryDir,'large.bin'),Buffer.alloc(1024*1024,9));proofs[library]=contentProof(libraryDir);
fs.writeFileSync(path.join(sdk,'package.json'),JSON.stringify({name:'@deepseek-ai/dsh',version:'0.2.1-rc.2',dependencies:Object.fromEntries([...names,library].map(name=>[name,'1.0.0']))}));
const manifestPath=path.join(profileDir,'package.json');fs.writeFileSync(manifestPath,JSON.stringify({name:'private-worker-fixture',private:true,dsh:{profile:{bundles:names}}}));fs.writeFileSync(path.join(profileDir,'cordis.patch.yml'),'[]\n');
const proofFile=path.join(home,'managed-package-proofs.json');fs.writeFileSync(proofFile,JSON.stringify({version:1,packages:proofs}));
const previous=process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256=sha(fs.readFileSync(proofFile));
const profile={name:'web',dir:profileDir,home,patchPath:path.join(profileDir,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:path.join(sdk,'package.json')};
function manager(Type){const ctx={get:()=>undefined,emit(){},loader:{entries:()=>[]},logger:{warn(){}}},item=Object.create(Type.prototype);Object.defineProperty(item,'ctx',{value:ctx});Object.assign(item,{profile,ownerContext:ctx,managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController(),lockWaitMs:1000});return item;}
const old=manager(Before),candidate=manager(After),originalOpen=fs.promises.open;
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let measure=false,delay=0,mutation,readGate,stats;
function reset(){assert.equal(stats?.active??0,0);stats={active:0,maxActive:0,proofOpens:0,bytes:0,libraryBytes:0,files:new Set(),packages:new Set()};}
fs.promises.open=async function(file,...args){
 const handle=await originalOpen.call(this,file,...args);if(!measure)return handle;const resolved=path.resolve(file);
 if(resolved===proofFile){stats.proofOpens++;return handle;}
 if(!resolved.startsWith(path.resolve(sdk)+path.sep))return handle;
 stats.active++;stats.maxActive=Math.max(stats.maxActive,stats.active);stats.files.add(resolved);const packageIndex=packageDirs.findIndex(root=>resolved.startsWith(root+path.sep));if(packageIndex>=0)stats.packages.add(packageIndex);
 const read=handle.read.bind(handle),close=handle.close.bind(handle);
 handle.read=async function(buffer,...readArgs){assert(buffer.byteLength<=65536);if(readGate)await readGate.promise;if(delay)await wait(delay);const result=await read(buffer,...readArgs);stats.bytes+=result.bytesRead;if(resolved.startsWith(libraryDir+path.sep))stats.libraryBytes+=result.bytesRead;if(mutation&&result.bytesRead){const action=mutation;mutation=undefined;await action(resolved);}return result;};
 handle.close=async function(){try{return await close();}finally{stats.active--;}};return handle;
};
const result={schema:1,node:process.version,owned,inputs,sourceHashes:{raw:canonical,before:sha(before),after:sha(after)},inventoryDependency:dependency.evidence,scope:'real current Host methods and exact full content proofs in private fixtures; injected per-read I/O delay is an algorithmic control, not a phone benchmark',cases:[]};
const snapshot=()=>({...stats,files:stats.files.size,packages:[...stats.packages]});
async function timed(label,item){reset();const start=performance.now(),rows=await item.listBundles();assert.equal(stats.active,0);const observed={label,ms:performance.now()-start,...snapshot()};assert.equal(observed.proofOpens,1);assert.equal(observed.libraryBytes,0);assert(observed.bytes>=3*1024*1024);assert.equal(rows.filter(row=>names.includes(row.name)&&row.origin==='dsh-managed').length,3);return {rows,observed};}
let server;
try{
 measure=true;delay=2;
 const runs=[];let expected;
 for(const [label,item] of [['before-1',old],['after-1',candidate],['after-2',candidate],['before-2',old]]){const run=await timed(label,item);if(expected)assert.deepEqual(run.rows,expected,'original names/rows/order and full classification must match');else expected=run.rows;assert.equal(run.observed.maxActive,label.startsWith('before')?1:2);runs.push(run.observed);}
 result.cases.push({name:'counterbalanced-identical-input-controlled-read-delay',injectedDelayPerReadMs:delay,runs,rowsOrderAndContentsEqual:true,fullBytesAndFreshProofEachTime:true});
 reset();const targetRows=await candidate.listBundles(names[1]);assert.equal(targetRows.length,1);assert.equal(stats.maxActive,1);assert.deepEqual([...stats.packages],[1]);result.cases.push({name:'target-only-read-remains-one-package-one-worker',...snapshot()});
 delay=0;const payload=path.join(packageDirs[0],'payload.bin'),original=fs.readFileSync(payload),originalStat=fs.statSync(payload);fs.writeFileSync(payload,Buffer.alloc(original.length,7));fs.utimesSync(payload,originalStat.atime,originalStat.mtime);
 assert.equal((await candidate.listBundles(names[0]))[0].origin,'user');fs.writeFileSync(payload,original);assert.equal((await candidate.listBundles(names[0]))[0].origin,'dsh-managed');
 mutation=async()=>{fs.writeFileSync(path.join(packageDirs[0],'concurrent.txt'),'race');};await assert.rejects(candidate.listBundles(names[0]),/PACKAGE_PROOF_CHANGED/);assert.equal(stats.active,0);fs.unlinkSync(path.join(packageDirs[0],'concurrent.txt'));
 const proofBytes=fs.readFileSync(proofFile);fs.appendFileSync(proofFile,' ');assert.equal((await candidate.listBundles(names[0]))[0].origin,'user');fs.writeFileSync(proofFile,proofBytes);assert.equal((await candidate.listBundles(names[0]))[0].origin,'dsh-managed');
 result.cases.push({name:'restored-mtime-edit-directory-race-and-unauthenticated-proof-remain-rejected',ok:true});
 reset();delay=2;mutation=async()=>{throw Error('fixture-proof-read-failure');};await assert.rejects(candidate.listBundles(),/fixture-proof-read-failure/);assert.equal(stats.active,0,'error settlement must drain both workers');assert.ok(stats.packages.size<=2,'no third proof may be scheduled after the first failure');result.cases.push({name:'error-stops-new-packages-and-drains-active-handles',...snapshot()});
 reset();const cancelled=manager(After),beforeManifest=fs.readFileSync(manifestPath);mutation=async()=>cancelled.abort.abort(Error('fixture-proof-cancelled'));await assert.rejects(cancelled.listBundles(),/fixture-proof-cancelled/);assert.equal(stats.active,0);assert.deepEqual(fs.readFileSync(manifestPath),beforeManifest);result.cases.push({name:'mid-read-cancellation-drains-and-does-not-replay-writer',...snapshot()});
 reset();let active=0;
 server=http.createServer(async(req,res)=>{res.setHeader('Content-Type','application/json');if(req.url==='/ping'){res.end(JSON.stringify({scanning:active>0}));return;}active++;try{const rows=await candidate.listBundles();res.end(JSON.stringify({count:rows.length}));}catch(error){res.statusCode=500;res.end(JSON.stringify({error:error.message}));}finally{active--;}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const port=server.address().port;
 const reads=[fetch('http://127.0.0.1:'+port+'/list'),fetch('http://127.0.0.1:'+port+'/list')];for(let i=0;i<100&&!stats.active;i++)await wait(5);assert.ok(stats.active>0);
 const ping=await (await fetch('http://127.0.0.1:'+port+'/ping')).json();assert.equal(ping.scanning,true);for(const response of await Promise.all(reads)){assert.equal(response.status,200);assert.equal((await response.json()).count,expected.length);}assert.equal(stats.active,0);assert.equal(stats.maxActive,2,'global two-proof bound must hold across multiple two-worker RPCs');assert.equal(stats.proofOpens,2);result.cases.push({name:'two-concurrent-rpcs-retain-global-proof-bound-and-http-responsiveness',...snapshot()});
 // Explicit overload contract: the existing two-active/32-waiter bound is
 // preserved, rather than enlarged to hide the second worker per request.
 // A pathological burst can therefore receive BUSY earlier than before.
 delay=0;reset();readGate=Promise.withResolvers();const burstSettled=[];
 const burst=Array.from({length:18},()=>candidate.listBundles().then(value=>{const row={ok:true,rows:value.length};burstSettled.push(row);return row;},error=>{const row={ok:false,error:error.message};burstSettled.push(row);return row;}));
 for(let i=0;i<200&&!burstSettled.some(row=>!row.ok);i++)await wait(5);
 assert.equal(stats.active,2);assert.ok(burstSettled.some(row=>row.error==='PACKAGE_PROOF_BUSY'),'18 simultaneous two-worker reads must exercise the unchanged bounded queue');
 readGate.resolve();readGate=undefined;const settled=await Promise.all(burst);assert.equal(stats.active,0);assert.equal(stats.maxActive,2);assert.ok(settled.every(row=>row.ok&&row.rows===expected.length||row.error==='PACKAGE_PROOF_BUSY'));
 const afterBurst=await candidate.listBundles();assert.deepEqual(afterBurst,expected,'overload rejection cannot poison later proof sessions');
 result.cases.push({name:'18-request-burst-remains-bounded-and-recovers',requests:18,accepted:settled.filter(row=>row.ok).length,busy:settled.filter(row=>!row.ok).length,maxActive:stats.maxActive,limitation:'two proof jobs per full request can exhaust the existing 32-waiter bound earlier; the bound is not enlarged'});
 delay=0;reset();const remove=await candidate.removeBundle(names[1]);assert.equal(remove.application,'failed');assert.equal(remove.error.code,'not-removable');assert.equal(stats.proofOpens,2);assert.deepEqual([...stats.packages],[1]);assert(stats.bytes>=2*1024*1024&&stats.bytes<3*1024*1024);assert.deepEqual(fs.readFileSync(manifestPath),beforeManifest);result.cases.push({name:'removal-still-authenticates-preflight-and-locked-target-only-recheck',...snapshot()});
}finally{readGate?.resolve();measure=false;fs.promises.open=originalOpen;if(server)await new Promise(resolve=>server.close(resolve));if(previous===undefined)delete process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;else process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256=previous;}
const report=process.env.DSHA_PLUGIN_WORKERS_REPORT;if(report){fs.mkdirSync(path.dirname(report),{recursive:true});fs.writeFileSync(report,JSON.stringify(result,null,2)+'\n');}
console.log('PASS: same full provenance/row order, two bounded workers, fresh request proofs, target-only scans, integrity failures, cancellation/error draining and HTTP concurrency.');
