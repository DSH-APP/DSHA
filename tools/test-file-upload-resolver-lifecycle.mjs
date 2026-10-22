// Actual locked Cordis/FileUploads/Commands/attachment store and isolated CLI RPC.
// Shared raw/managed fixtures and their proofs are read-only. The coordinator
// owns the final complete fixture proof; this focused run checks module/lock identity.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {spawn,spawnSync,execFileSync} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const root=path.resolve(import.meta.dirname,'..');
const output=path.resolve(process.env.DSHA_FILE_UPLOAD_OUTPUT||'F:/DSHA_RESTART/app/build/build190-fix13/file-upload-lifecycle');
fs.mkdirSync(output,{recursive:true});
const base=fs.mkdtempSync(path.join(root,'app/build/file-upload-resolver-'));
const pointerFile=path.join(root,'app/build/test-runtimes/current.json');
const pointerBytes=fs.readFileSync(pointerFile);
const runtime=path.resolve(process.env.DSHA_FILE_UPLOAD_TEST_RUNTIME||JSON.parse(pointerBytes).raw);
const markerFile=path.join(runtime,'dsha-test-runtime.json'),markerBytes=fs.readFileSync(markerFile),proof=JSON.parse(markerBytes);
const sha=value=>createHash('sha256').update(value).digest('hex');
const recipeFile=path.join(root,'app/src/main/assets/file-upload-resolver-lifecycle-patch.json');
const recipe=JSON.parse(fs.readFileSync(recipeFile,'utf8'));
const lockFile=path.join(root,'tools/dsh-runtime/package-lock.json'),lock=JSON.parse(fs.readFileSync(lockFile,'utf8'));
assert.equal(proof.kind,'raw');assert.equal(proof.dshVersion,recipe.dshVersion);
assert.deepEqual(proof.platform,{os:process.platform,cpu:process.arch});
assert.equal(proof.lockSha256,sha(fs.readFileSync(lockFile)));
const sourceFile=path.join(runtime,'node_modules',recipe.module),original=fs.readFileSync(sourceFile,'utf8');
assert.equal(sha(original),recipe.sourceSha256);
assert.equal(proof.contentProof.entries['node_modules/'+recipe.module].sha256,recipe.sourceSha256);
assert.equal(recipe.packageIntegrity,lock.packages['node_modules/@deepseek-ai/dsh-client-file-upload'].integrity);
let fixed=original;
for(const patch of recipe.patches){assert.equal(fixed.split(patch.before).length,2);fixed=fixed.replace(patch.before,()=>patch.after);}
const parse=spawnSync(process.execPath,['--check','--input-type=module'],{input:fixed,encoding:'utf8',windowsHide:true});
assert.equal(parse.status,0,parse.stderr);
const builder=execFileSync(process.env.DSHA_PYTHON||'python',['-B','-c',
  'import sys,json;from pathlib import Path;sys.path.insert(0,"tools");from runtime_stable_patches import apply;'+
  'p=json.loads(Path(sys.argv[1]).read_text(encoding="utf8"));b=Path(sys.argv[2]).read_bytes();'+
  'c=[(sys.argv[1],[(x["before"],x["after"]) for x in p["patches"]],["DSHA_FILE_UPLOAD_RESOLVER_LIFETIME_V1"])];'+
  's=apply(b,c);assert apply(s,c)==s;sys.stdout.buffer.write(s)',recipeFile,sourceFile],
  {encoding:'utf8',windowsHide:true,cwd:root,maxBuffer:4*1024*1024});
assert.equal(builder,fixed);
for(const [name,source] of [['original',original],['fixed',fixed]])fs.writeFileSync(path.join(base,name+'.mjs'),source);
fs.symlinkSync(path.join(runtime,'node_modules'),path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const require=createRequire(pathToFileURL(path.join(runtime,'package.json')));
const load=specifier=>import(pathToFileURL(require.resolve(specifier)));
const [{Context},{default:Commands},{default:Attachments},{default:RawUploads},{default:FixedUploads}]=await Promise.all([
  load('@deepseek-ai/cordis'),load('@deepseek-ai/dsh-commands'),load('@deepseek-ai/dsh-attachment-local'),
  load('@deepseek-ai/dsh-client-file-upload'),import(pathToFileURL(path.join(base,'fixed.mjs')))]);
const records=[];
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function unit(name,Uploads,negative=false){
  const ctx=new Context(),agents=new Map(),routes=[];
  ctx.provide('agents',{get:id=>agents.get(id)});
  ctx.provide('connection',{fetch:{register:route=>{routes.push(route);return()=>routes.splice(routes.indexOf(route),1);}}});
  await ctx.plugin(Commands);await ctx.plugin(Attachments,{dshHome:path.join(base,name+'-home')});
  const fiber=ctx.plugin(Uploads);await fiber;assert.equal(fiber.state,2);
  try{
    const uploads=ctx.fileUploads,raw=uploads[Symbol.for('cordis.original')];
    const a=async id=>({id}),dispose=uploads.registerAgentResolver(a);
    assert.throws(()=>uploads.registerAgentResolver(a),/Agent resolver is already registered/);
    dispose();
    if(negative){
      assert.equal(raw.agentResolver,a);assert.throws(()=>uploads.registerAgentResolver(async()=>({})),/Agent resolver is already registered/);
      records.push({name,status:'PASS',expectedNegative:true,productionDisposeLeavesOldResolver:true,nextRegistrationRejected:true});return;
    }
    assert.equal(raw.agentResolver,undefined);
    const again=uploads.registerAgentResolver(a);dispose();assert.notEqual(raw.agentResolver,undefined,'old disposer cannot remove a new registration of the same callback');again();
    let callbackThis='unset',calls=0;
    function makeAgent(id,origin='user'){
      const events=[],session={id,header:{origin},append(type,data){events.push({type,data});return events.length-1;}};
      return {id,ctx,session,events};
    }
    function resolve(id){callbackThis=this;calls++;const agent=makeAgent(id);agents.set(id,agent);return Promise.resolve(agent);}
    function Owner(scope){scope.effect(()=>scope.fileUploads.registerAgentResolver(resolve),'fixture resolver owner');}
    Owner.inject=['fileUploads'];
    const owner=ctx.plugin(Owner);await owner;assert.equal(owner.state,2);
    const data=Buffer.from('isolated file upload\nUnicode: 雪\n');
    const uploaded=await uploads.uploadStream({sessionId:'cold-fixture',name:'../report.txt',data:(async function*(){yield data.subarray(0,7);yield data.subarray(7);})()});
    assert.equal(calls,1);assert.equal(callbackThis,undefined,'callback remains an unbound call');
    assert.equal(uploaded.file.name,'report.txt');assert.equal(uploaded.file.bytes,data.length);
    assert.deepEqual(fs.readFileSync(ctx.attachments.fileHostPath(uploaded.file)),data);
    const agent=agents.get('cold-fixture');assert.deepEqual(uploads.resolve(agent,uploaded.receiptId),uploaded.file);
    const foreign=makeAgent('other');assert.equal(uploads.resolve(foreign,uploaded.receiptId),undefined);
    const replacement=makeAgent(agent.id);assert.equal(uploads.resolve(replacement,uploaded.receiptId),undefined,'Session object identity stays exact');
    const rollback=uploads.bindPrompt(agent,[uploaded.receiptId],'rollback');rollback[Symbol.dispose]();uploads.retirePrompt(agent,'rollback');
    assert.deepEqual(uploads.resolve(agent,uploaded.receiptId),uploaded.file);
    let handled=0;
    const unregister=ctx.commands.register({name:'fixture-file',description:'Fixture receipt command',input:{hint:'file',attachments:true},handler:request=>{
      handled++;assert.deepEqual(request.attachments,[{type:'file',attachment:uploaded.file}]);return {kind:'success',text:'receipt accepted'};
    }});
    const execution=await ctx.commands.execute(agent,'/fixture-file',[{type:'file',receiptId:uploaded.receiptId}],new AbortController().signal);
    assert.equal(execution.result.kind,'success');assert.equal(handled,1);assert.deepEqual(agent.events.map(e=>e.type),['command/run','command/done']);
    const binding=uploads.bindPrompt(agent,[uploaded.receiptId],'committed');binding.commit();binding[Symbol.dispose]();uploads.retirePrompt(agent,'committed');
    assert.equal(uploads.resolve(agent,uploaded.receiptId),undefined);
    const rejected=await ctx.commands.execute(agent,'/fixture-file',[{type:'file',receiptId:uploaded.receiptId}],new AbortController().signal);
    assert.equal(rejected.result.kind,'error');assert.equal(handled,1,'retired receipt never reaches the handler');unregister();
    const subagent=makeAgent('sub','subagent');agents.set(subagent.id,subagent);
    await assert.rejects(uploads.upload(subagent,{data:data.toString('base64')},new AbortController().signal),error=>error.code==='subagent/attachment-invalid');
    await owner.dispose();assert.equal(raw.agentResolver,undefined,'real owner effect disposal releases resolver');
    await assert.rejects(uploads.resolveAgent('missing'),error=>error.code==='session/not-found');
    records.push({name,status:'PASS',realCordis:true,realCommandRuntime:true,realAttachmentBytes:true,duplicateLiveRejected:true,oldDisposerFenced:true,callbackThisPreserved:true,ownerDisposalReleases:true,foreignAndRetiredReceiptsRejected:true,subagentRejected:true});
  }finally{await fiber.dispose();await ctx.fiber.dispose();}
}
const hook=path.join(base,'hook.mjs');
fs.writeFileSync(hook,`import {registerHooks} from 'node:module';import fs from 'node:fs';import {createHash} from 'node:crypto';
const target=${JSON.stringify(pathToFileURL(sourceFile).href)};
registerHooks({load(url,context,nextLoad){const result=nextLoad(url,context);if(url!==target)return result;
const source=typeof result.source==='string'?result.source:Buffer.from(result.source).toString('utf8');
if(createHash('sha256').update(source).digest('hex')!==${JSON.stringify(recipe.sourceSha256)})throw Error('UPLOAD_RAW_SOURCE_CHANGED');
return {...result,source:fs.readFileSync(process.env.DSHA_UPLOAD_BODY,'utf8')};}});
`);
const redact=text=>text.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]');
// Invalid requests may deliberately retire a connection before consuming its
// body. Give each negative check its own socket, with no pool reuse or replay.
function rejectedHttp(url,headers,body){return new Promise((resolve,reject)=>{
  const request=http.request(url,{method:'POST',agent:false,headers:{...headers,'Content-Length':Buffer.byteLength(body)}},response=>{
    let text='';response.setEncoding('utf8');response.on('data',chunk=>{text+=chunk;assert(text.length<65536);});
    response.on('end',()=>resolve({status:response.statusCode,text}));response.on('error',reject);
  });request.on('error',reject);request.setTimeout(15000,()=>request.destroy(Error('negative HTTP fixture deadline')));request.end(body);
});}
async function host(name,variant,home,workspace,work){
  const profile=path.join(home,'profiles/web');fs.mkdirSync(profile,{recursive:true});fs.mkdirSync(workspace,{recursive:true});
  const manifest=path.join(profile,'package.json');if(!fs.existsSync(manifest))fs.writeFileSync(manifest,JSON.stringify({name:'dsha-upload-fixture',version:'1.0.0',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app']}}}));
  const clean=Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(API_KEY|TOKEN|SECRET|PASSWORD|DSH_HOME|NODE_OPTIONS)/i.test(key)));
  const child=spawn(process.execPath,['--import',pathToFileURL(hook).href,path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port','0'],
    {cwd:workspace,windowsHide:true,env:{...clean,DSH_HOME:home,HOME:home,USERPROFILE:home,SSH_CONNECTION:'127.0.0.1 1 127.0.0.1 2',BROWSER:'true',DSH_TELEMETRY_DISABLED:'1',NARB_DISABLE_NATIVE_CACHE:'1',DSHA_UPLOAD_BODY:path.join(base,variant+'.mjs')},stdio:['ignore','pipe','pipe']});
  let log='',exited=false;child.on('exit',()=>exited=true);for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>log=(log+chunk).slice(-500000));
  try{
    let auth;for(let i=0;i<400&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(100);}
    assert(auth,'Host boot failed: '+redact(log.slice(-3000)));const origin=new URL(auth).origin;
    const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);const cookie=exchange.headers.get('set-cookie').split(';')[0];
    const rpc=async(method,args={})=>{const id=randomUUID();const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:id,method,payload:{args}}),signal:AbortSignal.timeout(15000)});assert.equal(response.status,200);const result=await response.json();assert.equal(result.rpcId,id);return result.result;};
    const upload=async(sessionId,label)=>{
      const bytes=Buffer.from('Host raw upload '+label+'\n');
      const reply=await fetch(origin+'/api/session/uploadFileBinary?sessionId='+encodeURIComponent(sessionId)+'&name=fixture.txt',{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/octet-stream'},body:bytes,signal:AbortSignal.timeout(15000)});
      assert.equal(reply.status,200);const result=await reply.json();assert(result.ok,JSON.stringify(result));
      const file=result.value.file,digest=sha(bytes);assert.equal(file.attachmentId,'sha256:'+digest);assert.equal(file.bytes,bytes.length);
      const stored=path.join(home,'attachments/v1/files',digest.slice(0,2),digest,file.name);assert.deepEqual(fs.readFileSync(stored),bytes);
      return {receiptReturned:typeof result.value.receiptId==='string',bytes:bytes.length,digest};
    };
    await work({rpc,upload,origin,cookie,log:()=>redact(log)});
    assert(!/chat\/completions|api\.deepseek\.com.*request/i.test(log),'no model operation was requested');
  }finally{
    if(!exited)child.kill();for(let i=0;i<100&&!exited;i++)await delay(100);assert(exited,'owned isolated Host exited');
    fs.writeFileSync(path.join(output,name+'.host.log'),redact(log));
  }
}
try{
  await unit('original-cordis-negative',RawUploads,true);
  await unit('fixed-cordis-files-commands',FixedUploads);
  for(const variant of ['original','fixed']){
    const home=path.join(base,variant+'-host-home'),workspace=path.join(base,variant+'-workspace');
    fs.mkdirSync(workspace,{recursive:true});fs.writeFileSync(path.join(workspace,'retained.txt'),'fixture remains unchanged\n');
    let sessionId;
    await host(variant+'-hmr',variant,home,workspace,async api=>{
      const created=await api.rpc('session/create',{request:{cwd:workspace}});assert(created.ok,JSON.stringify(created));sessionId=created.value.sessionId;
      const uploads=[await api.upload(sessionId,'before-HMR')];
      const inventory=await api.rpc('pluginInventory/list');assert(inventory.ok);const registry=inventory.value.entries.find(e=>e.moduleName==='@deepseek-ai/dsh-workspace');assert(registry);
      const changes=[];
      for(let round=1;round<=2;round++){
        const stop=await api.rpc('pluginManager/setPluginEnabled',{id:registry.entryId,enabled:false});assert(stop.ok,JSON.stringify(stop));assert.equal(stop.value.application,'applied');
        const start=await api.rpc('pluginManager/setPluginEnabled',{id:registry.entryId,enabled:true});assert(start.ok,JSON.stringify(start));changes.push({round,stop:stop.value.application,start:start.value.application});
        if(variant==='original'){
          assert.equal(start.value.application,'failed');assert.match(start.value.error.diagnostic,/file-upload: Agent resolver is already registered/);
          records.push({name:'actual-CLI-original-workspace-HMR',status:'PASS',expectedNegative:true,changes,exactDuplicateResolverFailure:true});return;
        }
        assert.equal(start.value.application,'applied',JSON.stringify(start));uploads.push(await api.upload(sessionId,'after-HMR-'+round));
        const typed=await api.rpc('fileUploads/upload',{agentId:sessionId,request:{data:Buffer.from('typed-'+round).toString('base64'),name:'typed.txt'}});assert(typed.ok,JSON.stringify(typed));assert.equal(typed.value.file.bytes,7);
      }
      const noAuth=await rejectedHttp(api.origin+'/api/session/uploadFileBinary?sessionId='+sessionId,{'Content-Type':'application/octet-stream'},'blocked');assert([401,403].includes(noAuth.status));
      const foreign=await rejectedHttp(api.origin+'/api/session/uploadFileBinary?sessionId='+sessionId,{Cookie:api.cookie,Origin:'http://foreign.invalid','Content-Type':'application/octet-stream'},'blocked');assert([401,403].includes(foreign.status));
      const badType=await rejectedHttp(api.origin+'/api/session/uploadFileBinary?sessionId='+sessionId,{Cookie:api.cookie,Origin:api.origin,'Content-Type':'application/json'},'{}');assert.equal(badType.status,415);
      const missingSession=await rejectedHttp(api.origin+'/api/session/uploadFileBinary',{Cookie:api.cookie,Origin:api.origin,'Content-Type':'application/octet-stream'},'blocked');assert.equal(missingSession.status,400);
      const unknown=await rejectedHttp(api.origin+'/api/session/uploadFileBinary?sessionId=missing-fixture',{Cookie:api.cookie,Origin:api.origin,'Content-Type':'application/octet-stream'},'blocked');const unknownResult=JSON.parse(unknown.text);assert.equal(unknownResult.ok,false);assert.equal(unknownResult.error.code,'session/not-found');
      records.push({name:'actual-CLI-fixed-two-workspace-HMR-rounds',status:'PASS',changes,uploads,typedUploads:true,httpAuthPreserved:true,badContentTypeAndMissingSessionRejected:true,unknownSessionRejected:true});
    });
    assert.equal(fs.readFileSync(path.join(workspace,'retained.txt'),'utf8'),'fixture remains unchanged\n');
    if(variant==='fixed')await host('fixed-cold-session-restart',variant,home,workspace,async api=>{
      const uploaded=await api.upload(sessionId,'cold-after-process-restart');
      const sessions=await api.rpc('session/list',{_request:{}});assert(sessions.ok,JSON.stringify(sessions));assert(sessions.value.items.some(s=>s.sessionId===sessionId));
      records.push({name:'actual-CLI-cold-Session-upload-after-process-restart',status:'PASS',uploaded,sessionIdentityRetained:true});
    });
  }
  assert.deepEqual(fs.readFileSync(markerFile),markerBytes,'shared raw proof never changed');
  assert.deepEqual(fs.readFileSync(pointerFile),pointerBytes,'shared current pointer never changed');
  assert.equal(sha(fs.readFileSync(sourceFile)),recipe.sourceSha256,'shared module never changed');
  const result={schema:1,status:'PASS',recipeSha256:sha(fs.readFileSync(recipeFile)),rawModuleSha256:recipe.sourceSha256,fixedModuleSha256:sha(fixed),module:recipe.module,
    archivePatchEngineAndIdempotence:true,fixture:base,records,scope:'Actual locked Cordis/Commands/attachment store and isolated CLI Host RPC with synthetic data; no Android, model request or user data; focused module/lock checks only, final complete runtime proof belongs to coordinator.'};
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
}catch(error){fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({error:String(error),stack:error.stack,records,fixture:base},null,2)+'\n');throw error;}
