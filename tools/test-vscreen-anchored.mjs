// Real adapter and real stdio initialize/tools/list. Calls below replace only
// bridge transport with memory replies; no SDK, HTTP, Core or Android execution.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
const require=createRequire(import.meta.url);
const server=new URL('../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs',import.meta.url);
const {tools,requestFor,call}=require(fileURLToPath(server));
const coreInstance='11111111-1111-4111-8111-111111111111';
const observationId='22222222-2222-4222-8222-222222222222';
const anchorId='33333333-3333-4333-8333-333333333333';
const original={coreInstance,generation:'original-generation',frameSeq:10};
const select={...original,observationId,x:301,y:402};
const click={...original,anchorId};
const results=[];let stdio;
async function test(name,body){try{await body();results.push({name,status:'PASS'});}catch(error){results.push({name,status:'FAIL',message:error.message,stack:error.stack});}}
const reply=value=>({ok:true,text:async()=>JSON.stringify({result:JSON.stringify(value)})});
const text=result=>JSON.parse(result.content[0].text);
await test('two explicit tools have exact required schemas and safe original-frame limits',()=>{
  assert.equal(tools.length,15);
  assert.deepEqual(tools.find(row=>row.name==='android_vscreen_anchor').inputSchema.required,['coreInstance','generation','frameSeq','observationId','x','y']);
  assert.deepEqual(tools.find(row=>row.name==='android_vscreen_anchored_click').inputSchema.required,['coreInstance','generation','frameSeq','anchorId']);
  for(const [name,args]of [['android_vscreen_anchor',select],['android_vscreen_anchored_click',click]]){
    for(const key of Object.keys(args)){const missing={...args};delete missing[key];assert.throws(()=>requestFor(name,missing),new RegExp('INVALID_'+key));}
    assert.throws(()=>requestFor(name,{...args,frameSeq:Number.MAX_SAFE_INTEGER+1}),/INVALID_frameSeq/);
    assert.throws(()=>requestFor(name,{...args,frameSeq:0}),/INVALID_frameSeq/);
  }
  assert.throws(()=>requestFor('android_vscreen_anchor',{...select,x:1.5}),/INVALID_x/);
  assert.throws(()=>requestFor('android_vscreen_anchor',{...select,y:-1}),/INVALID_y/);
  assert.throws(()=>requestFor('android_vscreen_anchor',{...select,observationId:observationId.toUpperCase().replace('2222','ABCD')}),/INVALID_observationId/);
});
await test('anchor and anchored click POST the untouched original evidence',()=>{
  for(const [name,args,route]of [['android_vscreen_anchor',select,'anchor'],['android_vscreen_anchored_click',click,'anchored-click']]){
    const request=requestFor(name,args);assert.equal(request.method,'POST');assert.equal(request.route,'/app/vscreen/'+route);
    assert.deepEqual(JSON.parse(request.body),args);assert.equal(request.headers['Content-Type'],'application/json; charset=utf-8');
  }
});
await test('see keeps all original observation metadata beside the original JPEG',async()=>{
  const value={ok:true,...original,sourceFrameSeq:10,displayId:7,width:1080,height:1920,observationId,anchoredAvailable:true,availabilityReason:'AVAILABLE',anchorCandidateCount:1,previewB64:'original-jpeg',previewWidth:540,previewHeight:960};
  const result=await call('android_vscreen_see',{coreInstance},undefined,{bridgeFetch:async()=>reply(value)});
  const {previewB64,...metadata}=value;assert.deepEqual(text(result),metadata);
  assert.deepEqual(result.content[1],{type:'image',mimeType:'image/jpeg',data:previewB64});
});
await test('unavailable anchoring still preserves the screenshot and fixed reason',async()=>{
  const value={ok:true,...original,sourceFrameSeq:10,anchoredAvailable:false,availabilityReason:'TREE_UNAVAILABLE',previewB64:'original-jpeg'};
  const result=await call('android_vscreen_see',{coreInstance},undefined,{bridgeFetch:async()=>reply(value)});
  assert.equal(text(result).anchoredAvailable,false);assert.equal(text(result).availabilityReason,'TREE_UNAVAILABLE');assert.equal(result.content[1].data,'original-jpeg');
});
await test('anchor result and later click validation sequence remain distinct',async()=>{
  const requests=[];
  const bridgeFetch=async(route,init)=>{requests.push({route,body:JSON.parse(init.body)});return reply(route.endsWith('/anchor')?{ok:true,anchorId,sourceFrameSeq:10,mode:'ANCHORED_NODE'}:{ok:true,sourceFrameSeq:10,validatedCurrentSeq:11,mode:'ANCHORED_NODE'});};
  const selected=await call('android_vscreen_anchor',select,undefined,{bridgeFetch});assert.equal(text(selected).anchorId,anchorId);
  const clicked=await call('android_vscreen_anchored_click',click,undefined,{bridgeFetch});assert.equal(text(clicked).sourceFrameSeq,10);assert.equal(text(clicked).validatedCurrentSeq,11);
  assert.deepEqual(requests.map(row=>row.body),[select,click]);assert.equal(requests.length,2);
});
await test('anchor lost response makes one selection request without hidden refresh or retry',async()=>{
  const requests=[];const result=await call('android_vscreen_anchor',select,undefined,{bridgeFetch:async route=>{requests.push(route);throw Error('controlled lost response');}});
  assert.deepEqual(requests,['/app/vscreen/anchor']);assert.equal(result.isError,true);
  assert.deepEqual(text(result),{ok:false,error:'ANCHOR_RESULT_UNKNOWN',selectionUnknown:true,noReplay:true,sourceFrameSeq:10});
});
await test('anchored click UNKNOWN makes one request and never falls back to tap',async()=>{
  const requests=[];const result=await call('android_vscreen_anchored_click',click,undefined,{bridgeFetch:async route=>{requests.push(route);throw Error('controlled lost response');}});
  assert.deepEqual(requests,['/app/vscreen/anchored-click']);assert.equal(result.isError,true);
  assert.deepEqual(text(result),{ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true,noReplay:true,mode:'ANCHORED_NODE',sourceFrameSeq:10});
});
await test('definitely not-sent anchored click retains its original failure without replay',async()=>{
  const cause=Object.assign(Error('controlled pre-socket refusal'),{executionUnknown:false});let requests=0;
  await assert.rejects(call('android_vscreen_anchored_click',click,undefined,{bridgeFetch:async()=>{requests++;throw cause;}}),error=>error===cause);assert.equal(requests,1);
});
await test('ANCHOR_ALREADY_ISSUED is returned without a new observation or selection',async()=>{
  const requests=[];const value={ok:false,error:'ANCHOR_ALREADY_ISSUED'};
  const result=await call('android_vscreen_anchor',select,undefined,{bridgeFetch:async route=>{requests.push(route);return reply(value);}});
  assert.equal(result.isError,true);assert.deepEqual(text(result),value);assert.deepEqual(requests,['/app/vscreen/anchor']);
});
await test('legacy tap remains a single GET with its explicit original arguments',()=>{
  const args={...original,x:1.5,y:2.5},request=requestFor('android_vscreen_tap',args);
  assert.equal(request.method,'GET');const url=new URL(request.route,'http://localhost');assert.equal(url.pathname,'/app/vscreen/tap');assert.equal(url.searchParams.get('frameSeq'),'10');assert.equal(url.searchParams.get('x'),'1.5');
});
await test('real MCP stdio initializes and lists exactly fifteen tools without loading the bridge SDK',async()=>{
  const env={};for(const key of ['PATH','SystemRoot','SystemDrive','COMSPEC','WINDIR','TEMP','TMP','PATHEXT'])if(process.env[key]!==undefined)env[key]=process.env[key];
  const child=spawn(process.execPath,['--max-old-space-size=128',fileURLToPath(server)],{env,windowsHide:true,stdio:['pipe','pipe','pipe']});
  stdio={pid:child.pid,naturalClosed:false};
  child.once('spawn',()=>{stdio.birth={source:'NODE_CHILD_PROCESS_SPAWN_HANDLE',pid:child.pid,ownerPid:process.pid,observedAtUtc:new Date().toISOString(),monotonicNs:process.hrtime.bigint().toString()};});
  const closed=once(child,'close');let output='',errors='';const pending=new Map();
  child.once('close',(code,signal)=>{stdio.exitCode=code;stdio.signal=signal;stdio.closed=true;});
  child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');child.stderr.on('data',chunk=>{errors+=chunk;});
  child.stdout.on('data',chunk=>{output+=chunk;let end;while((end=output.indexOf('\n'))!==-1){const line=output.slice(0,end);output=output.slice(end+1);if(!line.trim())continue;const value=JSON.parse(line);const waiter=pending.get(value.id);if(waiter){pending.delete(value.id);waiter(value);}}});
  const request=(id,method)=>new Promise(resolve=>{pending.set(id,resolve);child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params:method==='initialize'?{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'source-unit',version:'1'}}:{}})+'\n');});
  const timer=setTimeout(()=>{child.kill();},5000);
  try{
    const initialized=await Promise.race([request(1,'initialize'),closed.then(()=>{throw Error('stdio closed before initialize: '+errors);})]);
    assert.equal(initialized.result.serverInfo.name,'DSHA Virtual Screen');assert.match(initialized.result.instructions,/android_vscreen_anchor then android_vscreen_anchored_click/);
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
    const listed=await Promise.race([request(2,'tools/list'),closed.then(()=>{throw Error('stdio closed before tools/list: '+errors);})]);
    assert.deepEqual(listed.result.tools.map(row=>row.name),tools.map(row=>row.name));assert.equal(listed.result.tools.length,15);
    child.stdin.end();const [code,signal]=await closed;assert.equal(code,0);assert.equal(signal,null);assert.equal(errors,'');stdio.naturalClosed=true;stdio.tools=15;
  }finally{clearTimeout(timer);if(child.exitCode===null&&child.signalCode===null){child.stdin.end();child.kill();await closed;}}
});
console.log(JSON.stringify({scope:'REAL_STDIO_SURFACE_AND_REAL_ADAPTER_CONTROLLED_BRIDGE_TRANSPORT',sdkLoaded:false,httpRequested:false,coreOrAndroidExecuted:false,cordisRegistrationTested:false,stdio,cases:results.length,passed:results.filter(row=>row.status==='PASS').length,results},null,2));
if(results.some(row=>row.status!=='PASS'))process.exitCode=1;
