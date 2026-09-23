#!/usr/bin/env node
// 桌面协议回归：真实 JSON 存储后端 + 合成浏览器事件。不是 Android / Gecko 健康证明。
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
import vm from 'node:vm';
import {randomUUID} from 'node:crypto';

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const modules=path.resolve(process.argv[2]||path.join(repository,'app/build/rc2-20260911/locked-runtime/node_modules'));
const parent=path.resolve(process.argv[3]||path.join(repository,'app/build/backup-upgrade-baseline'));
if(!parent.startsWith(path.join(repository,'app/build')+path.sep))throw Error('Unsafe test output directory');
await fs.mkdir(parent,{recursive:true});
const owned=await fs.mkdtemp(path.join(parent,'trial-protocol-'));
const results=[];
async function test(name,run){try{await run();results.push({name,status:'PASS'});}catch(error){results.push({name,status:'FAIL',error:error.code||error.name});throw error;}}
async function loadTrialPlugin(){
 const directory=path.join(modules,`.dsha-runtime-trial-test-${randomUUID()}`);
 await fs.mkdir(directory);
 await Promise.all([
  fs.copyFile(path.join(repository,'app/src/main/assets/runtime-trial-plugin.js'),path.join(directory,'index.mjs')),
  fs.copyFile(path.join(repository,'app/src/main/assets/runtime-trial-page.js'),path.join(directory,'page.js')),
 ]);
 try{return {plugin:await import(pathToFileURL(path.join(directory,'index.mjs')).href+`?v=${randomUUID()}`),directory}}
 catch(error){await fs.rm(directory,{recursive:true,force:true});throw error}
}
function trialContext(backend){
 let header;
 return {
  storage:{backend:{get(name){assert.equal(name,'json');return backend}}},
  sessionPersistence:{
   async create(value){header=value;return {async flush(){},async close(){}}},
   async open(id){return {header:{...header,id,version:4},async read(){return {events:[]}},async close(){}}},
  },
  webServer:{port:3080,register(){return ()=>{}}},connection:{requestRejection(){return undefined}},
  effect(install){return install()},on(){return ()=>{}},
 };
}
try{
 await test('android_health_gate_requires_fresh_process_reopen',async()=>{
  const host=await fs.readFile(path.join(repository,'app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java'),'utf8');
  const bootstrap=await fs.readFile(path.join(repository,'app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java'),'utf8');
  const receipt=await fs.readFile(path.join(repository,'app/src/main/java/com/deepseekharness/app/backup/RuntimeDescriptor.java'),'utf8');
  const plugin=await fs.readFile(path.join(repository,'app/src/main/assets/runtime-trial-plugin.js'),'utf8');
  const builder=await fs.readFile(path.join(repository,'tools/build-dsh-runtime.py'),'utf8');
  assert.match(host,/List\.of\([^\n]*"storageFreshReopened"[^\n]*\)/);
  assert.match(host,/proof\.put\("storageFreshReopened",true\)/);
  assert.match(receipt,/Arrays\.asList\([^\n]*"storageFreshReopened"[^\n]*\)/);
  // process.execPath may be the host-side proroot bridge.  A fresh child must
  // be launched through the guest Node path so it receives the same path
  // translation and can independently read the durable record.
  assert.match(plugin,/const trialNode\s*=\s*process\.platform===['"]win32['"]\?process\.execPath:['"]\/usr\/local\/bin\/node['"];[\s\S]*spawnSync\(trialNode/);
  assert.match(plugin,/DSHA_TRIAL_STORAGE_RECORD_KEYS/);
  assert.match(plugin,/hints\.reserve\(join\(process\.env\.DSHA_TRIAL_STORAGE_ROOT/);
  assert.match(plugin,/Session\.create\(sessionId,\[\],\{version:4,id:sessionId,[\s\S]*cwd:process\.cwd\(\)/);
  assert.match(builder,/SESSION_PERSISTENCE_JSONL_MODULE/);
  assert.match(builder,/DSHA_SESSION_DIRECT_HINTS_V1/);
  assert.match(builder,/resolveHintedGeneration/);
  assert.match(bootstrap,/DSHA_SESSION_DIRECT_HINTS_V1[\s\S]*publishSessionExclusive as link/);
  assert.match(bootstrap,/if\s*\(c\.contains\("DSHA_SESSION_DIRECT_HINTS_V1"\)[\s\S]*\) return;/);
});
 await test('real_locked_json_backend_reopens_isolated_record',async()=>{
  const {JsonStorageBackend}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/dsh-storage-json/lib/index.js')));
  const backend=new JsonStorageBackend(path.join(owned,'storage')),nonce=randomUUID().replaceAll('-','');
  const descriptor={name:'dsha_trial_'+nonce,version:1,tables:['items'],hasGlobal:false,layout:'per-record'};
  let unit=await backend.kv.open(descriptor);await unit.putRecord('items','probe',{nonce});await unit.close();
  unit=await backend.kv.open(descriptor);assert.equal((await unit.loadAll()).tables.items.probe.nonce,nonce);await unit.close();await backend.close();
 });
 await test('trial_plugin_uses_isolated_dsh_home_and_real_json_backend',async()=>{
  const {JsonStorageBackend}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/dsh-storage-json/lib/index.js')));
  const loaded=await loadTrialPlugin(),home=path.join(owned,'plugin-success'),nonce=randomUUID().replaceAll('-','');
  const before={home:process.env.DSH_HOME,nonce:process.env.DSHA_RUNTIME_TRIAL_NONCE};
  process.env.DSH_HOME=home;process.env.DSHA_RUNTIME_TRIAL_NONCE=nonce;
  const backend=new JsonStorageBackend(path.join(home,'storages'));
  try{
   await loaded.plugin.apply(trialContext(backend));
   const record=JSON.parse(await fs.readFile(path.join(home,'storages',`dsha_trial_${nonce}`,'items','probe.json'),'utf8'));
   assert.equal(record.version,1);assert.equal(record.record.nonce,nonce);
  }finally{
   await backend.close();await fs.rm(loaded.directory,{recursive:true,force:true});
   if(before.home===undefined)delete process.env.DSH_HOME;else process.env.DSH_HOME=before.home;
   if(before.nonce===undefined)delete process.env.DSHA_RUNTIME_TRIAL_NONCE;else process.env.DSHA_RUNTIME_TRIAL_NONCE=before.nonce;
  }
 });
 await test('trial_plugin_reports_bounded_kv_reopen_diagnostics',async()=>{
  const {JsonStorageBackend}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/dsh-storage-json/lib/index.js')));
  const loaded=await loadTrialPlugin(),home=path.join(owned,'plugin-diagnostic'),nonce=randomUUID().replaceAll('-','');
  const before={home:process.env.DSH_HOME,nonce:process.env.DSHA_RUNTIME_TRIAL_NONCE};
  process.env.DSH_HOME=home;process.env.DSHA_RUNTIME_TRIAL_NONCE=nonce;
  const real=new JsonStorageBackend(path.join(home,'storages'));let opened=0;
  const backend=new Proxy(real,{get(target,property,receiver){
   if(property==='kv')return {open:async descriptor=>{
    const unit=await target.kv.open(descriptor);opened++;
    if(opened!==2)return unit;
    return {putRecord:(...args)=>unit.putRecord(...args),deleteRecord:(...args)=>unit.deleteRecord(...args),setGlobal:(...args)=>unit.setGlobal(...args),close:()=>unit.close(),loadAll:async()=>({tables:{items:{}},global:null})};
   }};
   return Reflect.get(target,property,receiver);
  }});
  try{
   let failure;try{await loaded.plugin.apply(trialContext(backend))}catch(error){failure=error}
   assert.ok(failure instanceof Error);
   assert.match(failure.message,/^Trial KV reopen mismatch; DSHA_TRIAL_KV_DIAG=/);
   const diagnostic=JSON.parse(failure.message.split('DSHA_TRIAL_KV_DIAG=')[1]);
   assert.equal(diagnostic.backendRole,'JsonStorageBackend');assert.equal(diagnostic.rootRole,'dsh-home-storages');assert.equal(diagnostic.rootBase,'storages');
   assert.equal(diagnostic.layout,'per-record');assert.equal(diagnostic.tableRole,'items');assert.equal(diagnostic.recordRole,'probe.json');
   assert.equal(diagnostic.backendWrite,'ok');assert.equal(diagnostic.recordRead,'ok');assert.equal(diagnostic.recordBytesNonzero,true);assert.equal(diagnostic.recordParse,'ok');assert.equal(diagnostic.recordJsonObject,true);assert.equal(diagnostic.recordVersion,'match');assert.equal(diagnostic.recordNonce,'match');assert.equal(diagnostic.backendReload,'mismatch');
   assert.equal(diagnostic.freshProcess.status,'not-attempted');
   assert.equal(diagnostic.unitDirectory.expected,true);assert.equal(diagnostic.tableDirectory.expected,true);
   assert.match(diagnostic.rootHash,/^[a-f0-9]{64}$/);assert.match(diagnostic.patch.entryHash,/^(?:[a-f0-9]{64}|unavailable)$/);
   assert.equal(typeof diagnostic.patch.directRecordHints,'boolean');
   assert.doesNotMatch(failure.message,new RegExp(home.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')));
  }finally{
   await real.close();await fs.rm(loaded.directory,{recursive:true,force:true});
   if(before.home===undefined)delete process.env.DSH_HOME;else process.env.DSH_HOME=before.home;
   if(before.nonce===undefined)delete process.env.DSHA_RUNTIME_TRIAL_NONCE;else process.env.DSHA_RUNTIME_TRIAL_NONCE=before.nonce;
  }
 });
 await test('locked_session_header_contract',async()=>{
  const {Session,SESSION_FORMAT_VERSION}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/dsh-session/lib/index.js')));
  const id=randomUUID(),session=Session.create(id);assert.equal(session.header.id,id);assert.equal(session.header.version,4);assert.equal(SESSION_FORMAT_VERSION,4);
 });
 const home='/root/.dsha-runtime-trial-'+ 'a'.repeat(32)+'/isolated-user-home';
 const readyFrame={type:'item',streamId:'test-stream',value:{type:'ready',clientId:'test-client',host:{home}}};
 const source=(await fs.readFile(path.join(repository,'app/src/main/assets/runtime-trial-page.js'),'utf8')).replace('__DSHA_TRIAL_NONCE__','a'.repeat(32)).replace('__DSHA_TRIAL_HOME__',JSON.stringify(home));
 function browser(responses){let tick,cleared=0,requests=0;const sockets=[];
  function Socket(){this.events={};this.addEventListener=(name,fn)=>{this.events[name]=fn};sockets.push(this)}
  const context={window:{WebSocket:Socket,addEventListener(){}},URL,Object,JSON,location:{href:'http://127.0.0.1:3080/',host:'127.0.0.1:3080'},
   document:{getElementById:()=>({children:[{}]}),querySelector:()=>null},setInterval(fn){tick=fn;return 7},clearInterval(id){assert.equal(id,7);cleared++},
   fetch:async()=>{requests++;const response=responses.shift();if(response instanceof Error)throw response;return response||{ok:true}}};
  vm.runInNewContext(source,context);return{connect(url='ws://127.0.0.1:3080/api/remote.mux',frame=readyFrame){new context.window.WebSocket(url);const socket=sockets.at(-1);socket.events.open?.();socket.events.message?.({data:JSON.stringify(frame)});},async tick(){tick();await new Promise(resolve=>setImmediate(resolve));},get requests(){return requests},get cleared(){return cleared}};
 }
 for(const response of [{ok:false},new Error('synthetic_network_failure')])await test('synthetic_renderer_retry_'+(response instanceof Error?'network':'http'),async()=>{
  const page=browser([response,{ok:true}]);page.connect();await page.tick();assert.equal(page.requests,1);assert.equal(page.cleared,0);
  await page.tick();assert.equal(page.requests,2);assert.equal(page.cleared,1);
 });
 await test('synthetic_foreign_socket_cannot_acknowledge_trial',async()=>{const page=browser([]);page.connect('ws://127.0.0.1:9999/socket');await page.tick();assert.equal(page.requests,0);assert.equal(page.cleared,0);});
 await test('synthetic_error_or_unrelated_frame_is_not_a_backend_handshake',async()=>{for(const frame of [{type:'error',streamId:'bad',error:{}},{type:'item',streamId:'another',value:{message:'not ready'}}]){const page=browser([]);page.connect(undefined,frame);await page.tick();assert.equal(page.requests,0)}});
 await test('synthetic_wrong_trial_home_cannot_acknowledge',async()=>{const page=browser([]);page.connect(undefined,{...readyFrame,value:{...readyFrame.value,host:{home:'/other-generation'}}});await page.tick();assert.equal(page.requests,0)});
}catch{process.exitCode=1;}
finally{
 const report={version:1,platform:process.platform,node:process.version,fixture:'synthetic, private temporary directory',androidRendererVerified:false,results};
 await fs.writeFile(path.join(parent,'runtime-trial-protocol.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
 const resolved=await fs.realpath(owned),allowed=await fs.realpath(parent);
 if(!resolved.startsWith(allowed+path.sep)||!path.basename(resolved).startsWith('trial-protocol-'))throw Error('Unsafe fixture cleanup');
 await fs.rm(resolved,{recursive:true});
}
