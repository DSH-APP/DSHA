// Actual locked CLI, Loader, Cordis services, Typert Gateway and filesystem.
// Module hooks substitute only the byte-checked signed recipe in memory. The
// shared raw/managed trees, marker and current pointer are never written.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn, execFileSync} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {testRuntime, requireNativeHost} from './test-runtime-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const runtime=testRuntime('raw');requireNativeHost(runtime);
const output=path.resolve(process.env.DSHA_DIRECTORY_PICKER_OUTPUT || 'F:/DSHA_RESTART/app/build/build190-fix13/directory-picker');
fs.mkdirSync(output,{recursive:true});
const base=fs.mkdtempSync(path.join(root,'app/build/directory-picker-service-'));
const recipeFile=path.join(root,'app/src/main/assets/directory-picker-service-patch.json');
const recipe=JSON.parse(fs.readFileSync(recipeFile,'utf8'));
const original=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
const sha=value=>createHash('sha256').update(value).digest('hex');
assert.equal(sha(original),recipe.sourceSha256);
const lock=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'),'utf8'));
assert.equal(recipe.packageIntegrity,lock.packages['node_modules/@deepseek-ai/dsh-api-workspace-controller'].integrity);
assert.equal(JSON.parse(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh-api-workspace-controller/package.json'),'utf8')).version,recipe.dshVersion);
const directoryPolicy=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/workspace-directory-policy-patch.json'),'utf8'));
function apply(source, patches) {
  for(const patch of patches){assert.equal(source.split(patch.before).length,2);source=source.replace(patch.before,()=>patch.after);}
  return source;
}
const oldPolicy=apply(original,directoryPolicy.patches);
const fixed=apply(oldPolicy,recipe.patches);
assert.equal(fixed.split('DSHA_DIRECTORY_PICKER_INDEPENDENT_LIFETIME_V1').length,2);
// Follow the actual builder's specialized -> stable recipe order. Before the
// coordinator registers this new recipe, apply it with the same stable engine.
const buildCheck=[
  'import importlib.util,json,pathlib,sys,shutil',
  'sys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))',
  's=importlib.util.spec_from_file_location("picker_builder",sys.argv[1])',
  'm=importlib.util.module_from_spec(s)', 's.loader.exec_module(m)',
  'request=json.load(sys.stdin)',
  'recipe=json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf8"))',
  'assets=pathlib.Path(request["assetsFixture"])',
  'assets.mkdir(parents=True,exist_ok=True)',
  'registry=json.loads((m.HOOKS.parent/"runtime-patches.json").read_text(encoding="utf8"))',
  'registered=any(row["asset"]=="directory-picker-service-patch.json" for row in registry["active"])',
  'for file in m.load_chains(m.HOOKS.parent,recipe["dshVersion"])[1]:',
  ' target=assets/file',
  ' target.parent.mkdir(parents=True,exist_ok=True)',
  ' shutil.copy2(m.HOOKS.parent/file,target)',
  'shutil.copy2(sys.argv[2],assets/"directory-picker-service-patch.json")',
  'if not registered: registry["active"].append({"asset":"directory-picker-service-patch.json","markers":["DSHA_DIRECTORY_PICKER_INDEPENDENT_LIFETIME_V1"]})',
  '(assets/"runtime-patches.json").write_text(json.dumps(registry),encoding="utf8")',
  'chains,inputs=m.load_chains(assets,recipe["dshVersion"])',
  'content=m.patched_content(pathlib.PurePosixPath(request["module"]),request["source"].encode())',
  'patched=m.apply_stable_patches(content,chains.get(request["module"],[]))',
  'assert m.apply_stable_patches(patched,chains.get(request["module"],[]))==patched',
  'print(json.dumps({"source":patched.decode(),"recipeAlreadyRegistered":registered,"stableInputs":inputs}))'
].join('\n');
const stableAssets=path.join(base,'stable-assets');
const builder=JSON.parse(execFileSync(process.env.DSHA_PYTHON||'python',['-B','-c',buildCheck,path.join(root,'tools/build-dsh-runtime.py'),recipeFile],
  {input:JSON.stringify({source:original,module:recipe.module,assetsFixture:stableAssets}),encoding:'utf8',windowsHide:true,timeout:30000,maxBuffer:4*1024*1024}));
assert.equal(builder.source,fixed);
const hook=path.join(base,'recipe-hook.mjs');
fs.writeFileSync(hook,`import {registerHooks} from 'node:module';
import fs from 'node:fs';import {createHash} from 'node:crypto';
// Drive the CLI's actual registered SIGTERM cleanup on Windows over an owned
// test IPC channel; no production function or signal handler is replaced.
process.on('message',message=>{if(message?.type==='dsha-test-normal-stop'){process.emit('SIGTERM');if(process.connected)process.disconnect();}});
const target=${JSON.stringify(pathToFileURL(path.join(runtime,'node_modules',recipe.module)).href)};
registerHooks({load(url,context,nextLoad){const answer=nextLoad(url,context);if(url!==target)return answer;
 const original=typeof answer.source==='string'?answer.source:Buffer.from(answer.source).toString('utf8');
 if(createHash('sha256').update(original).digest('hex')!==${JSON.stringify(recipe.sourceSha256)})throw Error('PICKER_RAW_SOURCE_CHANGED');
 return {...answer,source:fs.readFileSync(process.env.DSHA_PICKER_BODY,'utf8')};}});
`,{encoding:'utf8'});
const bodies={original:oldPolicy,fixed,named:fixed.replace('WorkspaceControllerPlugin as default','WorkspaceController as default')};
for(const [name,body] of Object.entries(bodies))fs.writeFileSync(path.join(base,name+'.mjs'),body,{encoding:'utf8'});
fs.symlinkSync(path.join(runtime,'node_modules'),path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const composed=await import(pathToFileURL(path.join(base,'fixed.mjs')));
const rawExports=await import(pathToFileURL(path.join(runtime,'node_modules',recipe.module)));
assert.equal(composed.default.name,composed.WorkspaceController.name);
assert.equal(composed.default.Config,composed.WorkspaceController.Config);
for(const config of [{},{documentsDirectory:path.join(base,'documents'),documentsLookupTimeoutMs:250}])
 assert.deepEqual(composed.default.Config(config),rawExports.WorkspaceController.Config(config));
assert.throws(()=>composed.default.Config({documentsLookupTimeoutMs:0}));
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const redact=text=>text.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]');
const records=[];
async function run(label,variant,{blocked=false,pickerDisabled=false,reuse}={},work) {
 const directory=path.join(base,reuse??label),home=path.join(directory,'home'),profile=path.join(home,'profiles/web'),workspace=path.join(directory,'workspace');
 for(const folder of [profile,workspace])fs.mkdirSync(folder,{recursive:true});
 if(!reuse){
  fs.mkdirSync(path.join(workspace,'existing'));fs.mkdirSync(path.join(workspace,'.hidden'));fs.writeFileSync(path.join(workspace,'retained.txt'),'unchanged user fixture\n');
  fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify({name:'dsha-picker-host-fixture',version:'1.0.0',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app']}}}));
 }
 const patchFile=path.join(profile,'cordis.patch.yml');
 const patchText=disabled=>'- id: workspace\n  disabled: '+disabled+'\n- id: workspace-controller\n  config:\n    documentsDirectory: '+JSON.stringify(path.join(directory,'documents'))+'\n'+(pickerDisabled?'- id: directory-picker\n  disabled: true\n':'');
 if(!reuse)fs.writeFileSync(patchFile,patchText(blocked));
 const clean=Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(API_KEY|TOKEN|SECRET|PASSWORD|DSH_HOME|NODE_OPTIONS)/i.test(key)));
 const child=spawn(process.execPath,['--import',pathToFileURL(hook).href,path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port','0'],
  {cwd:workspace,windowsHide:true,env:{...clean,DSH_HOME:home,HOME:home,USERPROFILE:home,SSH_CONNECTION:'127.0.0.1 1 127.0.0.1 2',BROWSER:'true',NARB_DISABLE_NATIVE_CACHE:'1',DSH_TELEMETRY_DISABLED:'1',DSHA_PICKER_BODY:path.join(base,variant+'.mjs')},stdio:['ignore','pipe','pipe','ipc']});
 let log='',exited=false,exitCode,completedRecord;child.on('exit',code=>{exited=true;exitCode=code;});for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>log=(log+chunk).slice(-500000));
 const observations=[];
 try{
  let auth;for(let i=0;i<400&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(150);}
  assert(auth,'Host boot failed: '+redact(log.slice(-3000)));
  const origin=new URL(auth).origin;const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);const cookie=exchange.headers.get('set-cookie').split(';')[0];
  async function rpc(method,args={}) {
   const id=randomUUID();const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:id,method,payload:{args}}),signal:AbortSignal.timeout(15000)});
   assert.equal(response.status,200);const answer=await response.json();assert.equal(answer.rpcId,id);return answer.result;
  }
  async function inventory(){const answer=await rpc('pluginInventory/list');assert(answer.ok,JSON.stringify(answer));return answer.value.entries.filter(row=>/directory-picker|workspace-controller|dsh-workspace$/.test(row.moduleName));}
  const info={rpc,inventory,workspace,home,origin,cookie,patchFile,patchText,observations};
  await work(info);
  assert.equal(fs.readFileSync(path.join(workspace,'retained.txt'),'utf8'),'unchanged user fixture\n');
  assert(!/service "directoryPickerController" has been registered/.test(log),'no duplicate controller instance');
  completedRecord={label,variant,blocked,pickerDisabled,reuse,observations,inventory:await inventory(),origin,retainedBytes:true,status:'PASS'};records.push(completedRecord);
 } finally {
  if(!exited)child.send({type:'dsha-test-normal-stop'});
  for(let i=0;i<100&&!exited;i++)await delay(100);
  fs.writeFileSync(path.join(output,label+'.host.log'),redact(log));
  if(!exited){if(completedRecord)completedRecord.status='FAILED_NORMAL_STOP';child.kill();throw Error('owned Host normal cleanup did not finish; fixture retained');}
  assert.equal(exitCode,0,'actual CLI SIGTERM cleanup exit');
  if(completedRecord)Object.assign(completedRecord,{normalShutdown:true,exitCode});
 }
}
async function listing(api) {
 const answer=await api.rpc('directoryPicker/list',{path:api.workspace});assert(answer.ok,JSON.stringify(answer));
 assert.equal(answer.value.path,api.workspace);assert.equal(answer.value.home,api.home);
 assert.deepEqual(answer.value.entries.map(row=>row.name),['.hidden','existing']);assert.equal(answer.value.truncated,false);
 api.observations.push('actual browse listing, hidden row and bounded result');
}
async function exercises(api) {
 await listing(api);
 const made=await api.rpc('directoryPicker/createDirectory',{path:api.workspace,name:'created'});assert(made.ok,JSON.stringify(made));assert.equal(made.value,path.join(api.workspace,'created'));assert(fs.statSync(made.value).isDirectory());
 const listed=await api.rpc('directoryPicker/list',{path:made.value});assert(listed.ok);assert.deepEqual(listed.value.entries,[]);
 for(const name of ['../escape','..','.','a/b','a\\b','']){const result=await api.rpc('directoryPicker/createDirectory',{path:api.workspace,name});assert.equal(result.ok,false);assert.match(result.error.code,/gateway\/bad-request|directory-picker\/create-failed/);}
 assert.equal(fs.existsSync(path.join(path.dirname(api.workspace),'escape')),false);
 const duplicate=await api.rpc('directoryPicker/createDirectory',{path:api.workspace,name:'created'});assert.equal(duplicate.ok,false);assert.equal(duplicate.error.code,'directory-picker/exists');
 const relative=await api.rpc('directoryPicker/list',{path:'relative-path'});assert.equal(relative.ok,false);assert.equal(relative.error.code,'directory-picker/unreadable');
 const wrongKind=await api.rpc('directoryPicker/pick');assert.equal(wrongKind.ok,false);assert.equal(wrongKind.error.code,'directory-picker/unavailable');
 const missing=await api.rpc('directoryPicker/list',{path:path.join(api.workspace,'missing')});assert.equal(missing.ok,false);assert.equal(missing.error.code,'directory-picker/unreadable');assert(!fs.existsSync(path.join(api.workspace,'missing')));
 for(const headers of [{Origin:api.origin},{Cookie:api.cookie,Origin:'http://foreign.invalid'}]){const response=await fetch(api.origin+'/api/directoryPicker/createDirectory',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:randomUUID(),method:'directoryPicker/createDirectory',payload:{args:{path:api.workspace,name:'forbidden'}}})});assert([401,403].includes(response.status));}
 assert(!fs.existsSync(path.join(api.workspace,'forbidden')));
 api.observations.push('real create/readback, existing child, bad segment, relative/missing path, capability and HTTP auth failures preserved');
}
try{
 await run('negative-disabled-registry','original',{blocked:true},async api=>{
  const answer=await api.rpc('directoryPicker/list',{path:api.workspace});assert.equal(answer.ok,false);assert.equal(answer.error.code,'gateway/service-unavailable');assert.match(answer.error.message,/directoryPickerController/);
  const rows=await api.inventory();assert(rows.some(row=>row.moduleName==='@deepseek-ai/dsh-host-directory-picker-browse'&&row.fiberPhase==='active'));
  api.observations.push('original function: active real browse backend, absent DirectoryPickerController and exact gateway error');
 });
 await run('fixed-disabled-registry','fixed',{blocked:true},async api=>{
  await exercises(api);const failed=await api.rpc('workspace/create',{request:{path:api.workspace}});assert.equal(failed.ok,false);assert.equal(failed.error.code,'gateway/service-unavailable');
  api.observations.push('workspace remains explicitly unavailable; no fake registry or success, retained fixture bytes unchanged');
 });
 let persistedWorkspace;
 await run('fixed-healthy-lifecycle','fixed',{},async api=>{
  await exercises(api);const first=await api.rpc('workspace/create',{request:{path:api.workspace}});assert(first.ok,JSON.stringify(first));
  persistedWorkspace=first.value.workspace.workspaceId;
  api.observations.push('actual healthy Workspace registered before normal CLI cleanup');
 });
 for(let generation=1;generation<=2;generation++)await run('fixed-healthy-restart-'+generation,'fixed',{reuse:'fixed-healthy-lifecycle'},async api=>{
  const directory=await api.rpc('directoryPicker/list',{path:api.workspace});assert(directory.ok,JSON.stringify(directory));assert(directory.value.entries.some(row=>row.name==='created'));
  const workspace=await api.rpc('workspace/create',{request:{path:api.workspace}});assert(workspace.ok,JSON.stringify(workspace));assert.equal(workspace.value.created,false);assert.equal(workspace.value.workspace.workspaceId,persistedWorkspace);
  api.observations.push('same actual directory and Workspace id after prior normal CLI cleanup/reopen; no profile or data reseeding');
 });
 await run('fixed-missing-backend','fixed',{pickerDisabled:true},async api=>{
  const answer=await api.rpc('directoryPicker/list',{path:api.workspace});assert.equal(answer.ok,false);assert.equal(answer.error.code,'gateway/service-unavailable');
  const workspace=await api.rpc('workspace/create',{request:{path:api.workspace}});assert(workspace.ok,JSON.stringify(workspace));
  api.observations.push('missing real picker stays unavailable while healthy Workspace still works');
 });
 await run('named-class-compatibility','named',{},async api=>{await listing(api);const workspace=await api.rpc('workspace/create',{request:{path:api.workspace}});assert(workspace.ok,JSON.stringify(workspace));api.observations.push('named WorkspaceController retains original child mounting behavior');});
 const previousStableAssets=process.env.DSHA_WORKSPACE_POLICY_STABLE_ASSETS;
 try{process.env.DSHA_WORKSPACE_POLICY_STABLE_ASSETS=stableAssets;await import('./test-workspace-directory-policy.mjs');}
 finally{if(previousStableAssets===undefined)delete process.env.DSHA_WORKSPACE_POLICY_STABLE_ASSETS;else process.env.DSHA_WORKSPACE_POLICY_STABLE_ASSETS=previousStableAssets;}
 const result={schema:1,status:'PASS',dshVersion:recipe.dshVersion,rawModuleSha256:sha(original),oldPolicySha256:sha(oldPolicy),fixedModuleSha256:sha(fixed),recipeSha256:sha(fs.readFileSync(recipeFile)),packageIntegrity:recipe.packageIntegrity,builderRegistered:builder.recipeAlreadyRegistered,workspaceDirectoryPolicy:'PASS existing actual policy suite in the same fully proven process',fixture:base,records,scope:'Actual isolated Host RPC and filesystem; no Android, model, real account or user data; screenshots alone do not identify which real registry dependency was unavailable.'};
 fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}catch(error){fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({error:String(error),stack:error.stack,records,fixture:base},null,2));throw error;}
