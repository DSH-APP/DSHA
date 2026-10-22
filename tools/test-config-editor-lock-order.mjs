import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {AsyncLocalStorage} from 'node:async_hooks';
import {performance} from 'node:perf_hooks';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';
import {preparePrivatePluginInventory} from './private-plugin-inventory-fixture.mjs';

// Full real Cordis Include/Loader/HMR/PluginManager/ConfigEditor lifecycle in
// private host fixtures. No device, credentials, network install, or runtime
// fixture bytes are changed. The original lock order is a required negative
// control, not a reimplementation of either mutex.
const source=path.resolve(import.meta.dirname,'..');
const raw=testRuntime('raw');
const sha=value=>createHash('sha256').update(value).digest('hex');
const moduleName='@deepseek-ai/dsh-config-editor/lib/index.js';
const original=fs.readFileSync(path.join(raw,'node_modules',moduleName),'utf8').replaceAll('\r\n','\n');
const hmrModule='@deepseek-ai/dsh-hmr/lib/index.js';
const hmrSource=fs.readFileSync(path.join(raw,'node_modules',hmrModule),'utf8').replaceAll('\r\n','\n');
assert.equal(sha(hmrSource),'143b445f1b303be83d56f9e7d3992e1a4885850c08f1082065d332ba7a70ae0a','review the pinned HMR AsyncLocalStorage contract when upgrading');
assert.match(hmrSource,/executing = new AsyncLocalStorage\(\);/);
assert.match(hmrSource,/if \(this\.executing\.getStore\(\)\) return Promise\.reject\(.*HMR transactions cannot be nested/);
const recipe=JSON.parse(fs.readFileSync(path.join(source,'app/src/main/assets/config-editor-lock-order-patch.json')));
assert.equal(recipe.module,moduleName);assert.equal(recipe.dshVersion,'0.2.1-rc.2');
assert.equal(sha(original),recipe.sourceSha256,'unknown upstream ConfigEditor must fail closed');
let patched=original;
for(const patch of recipe.patches){assert.equal(patched.split(patch.before).length,2);patched=patched.replace(patch.before,patch.after);}
assert.match(patched,/DSHA_CONFIG_EDITOR_LOCK_ORDER_V1/);
assert.equal(patched.split(recipe.patches[0].before).length,1,'reapplying without the registry marker must not silently duplicate');
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-config-lock-order-'));
fs.symlinkSync(path.join(raw,'node_modules'),path.join(owned,'node_modules'),process.platform==='win32'?'junction':'dir');
const beforeFile=path.join(owned,'config-editor-before.mjs'),afterFile=path.join(owned,'config-editor-after.mjs');
fs.writeFileSync(beforeFile,original);fs.writeFileSync(afterFile,patched);
// Rebuild the manager from the complete current recipe chain beside the proven
// raw runtime, just as the provenance-concurrency fixture does. An unrelated
// concurrently changing APK asset must not make us use a stale managed overlay.
const managerModule='@deepseek-ai/dsh-plugin-manager/lib/index.js';
let managerSource=fs.readFileSync(path.join(raw,'node_modules',managerModule),'utf8').replaceAll('\r\n','\n');
const managerRecipes=[];
for(const row of JSON.parse(fs.readFileSync(path.join(source,'app/src/main/assets/runtime-patches.json'))).active){
 const currentRecipe=JSON.parse(fs.readFileSync(path.join(source,'app/src/main/assets',row.asset)));
 assert.notEqual(row.module??currentRecipe.module,hmrModule,'a new HMR recipe requires review of this pinned immediate-rejection contract');
 if((row.module??currentRecipe.module)!==managerModule)continue;
 managerRecipes.push({asset:row.asset,sha256:sha(fs.readFileSync(path.join(source,'app/src/main/assets',row.asset)))});
 for(const patch of currentRecipe.patches){assert.equal(managerSource.split(patch.before).length,2,row.asset);const after=patch.prependAsset===undefined?patch.after:fs.readFileSync(path.join(source,'app/src/main/assets',patch.prependAsset),'utf8')+'\n'+patch.after;managerSource=managerSource.replace(patch.before,after);}
}
const inventoryDependency=preparePrivatePluginInventory({source,raw,directory:owned,managerSource});
assert.equal(inventoryDependency.evidence.importAdaptation.count,1);
const managerFile=path.join(owned,'plugin-manager-current.mjs');fs.writeFileSync(managerFile,inventoryDependency.source);
const mod=name=>import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai',name,'lib/index.js')));
const [{boot,readProfilePatches},{default:Hmr},{PluginManager},{default:Timer},{withFileLock},{ConfigEditor:BeforeEditor},{ConfigEditor:AfterEditor}]=await Promise.all([
 mod('dsh-app-boot'),mod('dsh-hmr'),import(pathToFileURL(managerFile)),mod('cordis-plugin-timer'),mod('dsh-atomic-write'),
 import(pathToFileURL(beforeFile)),import(pathToFileURL(afterFile))
]);
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const calls=new AsyncLocalStorage(),results=[];
let serial=0;
globalThis.__DSHA_CONFIG_LOCK_LIFECYCLE__=new Map();

async function fixture(Editor,{hmr=true}={}){
 const dir=path.join(owned,'case-'+(++serial));fs.mkdirSync(dir);
 const profileDir=path.join(dir,'profile');fs.mkdirSync(path.join(profileDir,'node_modules'),{recursive:true});
 const state={events:[]};globalThis.__DSHA_CONFIG_LOCK_LIFECYCLE__.set(dir,state);
 const stateExpression=`globalThis.__DSHA_CONFIG_LOCK_LIFECYCLE__.get(${JSON.stringify(dir)})`;
 const configFile=path.join(dir,'config.mjs');
 fs.writeFileSync(configFile,`import z from '@deepseek-ai/schemastery';export const name='fixture-config';export const Config=z.object({value:z.number().default(0)});export function apply(ctx,config){const state=${stateExpression};state.events.push(['config-initialize',config.value]);if(config.value===13)throw Error('fixture-rejected-config');state.events.push(['config-active',config.value]);ctx.effect(()=>()=>state.events.push(['config-disposed',config.value]));}`);
 const toggleFile=path.join(dir,'toggle.mjs');
 fs.writeFileSync(toggleFile,`export const name='fixture-toggle';export async function apply(ctx){const state=${stateExpression};state.events.push(['toggle-initialize']);if(state.failToggle)throw Error('fixture-toggle-failure');if(state.toggleGate)await state.toggleGate.promise;state.events.push(['toggle-active']);ctx.effect(()=>()=>state.events.push(['toggle-disposed']));}`);
 function bundle(name,rows){const folder=path.join(dir,name);fs.mkdirSync(folder);fs.writeFileSync(path.join(folder,'package.json'),JSON.stringify({name,version:'1.0.0',dsh:{bundle:{patch:'cordis.patch.yml'}}}));fs.writeFileSync(path.join(folder,'cordis.patch.yml'),JSON.stringify([{insert:rows}]));fs.symlinkSync(folder,path.join(profileDir,'node_modules',name),process.platform==='win32'?'junction':'dir');}
 bundle('fixture-base-bundle',[{id:'fixture-config',name:pathToFileURL(configFile).href,config:{value:0}}]);
 bundle('fixture-toggle-bundle',[{id:'fixture-toggle',name:pathToFileURL(toggleFile).href}]);
 const installAnchor=path.join(dir,'installation.json');fs.writeFileSync(installAnchor,JSON.stringify({name:'@deepseek-ai/dsh',version:'0.2.1-rc.2'}));
 const profile={name:'web',dir:profileDir,home:dir,patchPath:path.join(profileDir,'cordis.patch.yml'),overlays:[],startedBundles:['fixture-base-bundle'],installAnchor};
 fs.writeFileSync(path.join(profileDir,'package.json'),JSON.stringify({name:'lock-order-fixture',private:true,dsh:{profile:{bundles:['fixture-base-bundle']}}}));
 fs.writeFileSync(profile.patchPath,'[]\n');
 const configPath=path.join(profileDir,'cordis.yml');fs.writeFileSync(configPath,'[]\n');
 const ctx=await boot('dsh',configPath,readProfilePatches('dsh',profile),async context=>{context.provide('profileContext',profile);context.provide('appReady',{onReady(fn){queueMicrotask(fn);return ()=>{};}});});
 try{
  await ctx.plugin(Timer,{});
  if(hmr)await ctx.plugin(Hmr,{base:pathToFileURL(dir+'/').href,root:[],ignored:[]});
  await ctx.plugin(PluginManager,{});await ctx.plugin(Editor,{});await ctx.loader.await();
  assert.ok(ctx.get('configEditor'));assert.ok(ctx.get('pluginManager'));
  if(hmr)assert.ok(ctx.get('hmr'));
 }catch(error){await ctx.fiber.dispose();throw error;}
 return {ctx,dir,profile,state,entry:()=>[...ctx.loader.entries()].find(row=>row.options.id==='fixture-config'),lock:()=>path.join(profileDir,'package.json.lock'),close:()=>ctx.fiber.dispose()};
}
const edit=(f,value)=>f.ctx.configEditor.edit(f.entry(),current=>({...current,value}));
const valueOf=f=>f.entry().options.config.value;
const toggle=f=>[...f.ctx.loader.entries()].find(row=>row.options.id==='fixture-toggle');
const noLock=f=>assert.equal(fs.existsSync(f.lock()),false,'writer lock leaked');

async function concurrentControl(Editor,kind){
 const f=await fixture(Editor);const marks=[],at=performance.now();
 const mark=(event,extra={})=>marks.push({event,atMs:performance.now()-at,...extra});
 try{
  const hmr=f.ctx.hmr,originalExclusive=hmr.runExclusive;
  const entered=Promise.withResolvers(),release=Promise.withResolvers(),configQueued=Promise.withResolvers(),pluginQueued=Promise.withResolvers();
  hmr.runExclusive=function(operation){const caller=calls.getStore()??'watcher';mark('queue',{caller});if(caller==='config')configQueued.resolve();if(caller==='plugin')pluginQueued.resolve();return originalExclusive.call(this,async()=>{mark('enter',{caller});try{return await operation();}finally{mark('leave',{caller});}});};
  const blocker=calls.run('barrier',()=>hmr.runExclusive(async()=>{entered.resolve();await release.promise;}));await entered.promise;
  const config=calls.run('config',()=>edit(f,2)).then(()=>({ok:true}),error=>({ok:false,message:error.message}));await configQueued.promise;
  const enabled=calls.run('plugin',()=>f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true));
  if(kind==='before')await pluginQueued.promise;else await wait(60);
  assert.equal(fs.existsSync(f.lock()),true);mark('release-barrier');release.resolve();
  const [saved,result]=await Promise.all([config,enabled,blocker]);
  assert.equal(result.application,'applied',JSON.stringify(result));assert.equal(toggle(f)?.fiber?.state,2);
  if(kind==='before'){assert.equal(saved.ok,false);assert.match(saved.message,/timed out waiting for the writer lock/);assert.equal(valueOf(f),0);}
  else{assert.equal(saved.ok,true);assert.equal(valueOf(f),2);assert.match(fs.readFileSync(f.profile.patchPath,'utf8'),/value: 2/);}
  const off=await f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',false);assert.equal(off.application,'applied');assert.equal(toggle(f),undefined);
  noLock(f);results.push({name:'controlled-concurrent-'+kind,durationMs:performance.now()-at,marks,saved,enabled:result,lifecycleActivationCovered:true});
 }finally{await f.close();}
}

try{
 await concurrentControl(BeforeEditor,'before');
 await concurrentControl(AfterEditor,'after');
 const f=await fixture(AfterEditor);
 try{
  await edit(f,3);assert.equal(valueOf(f),3);noLock(f);
  const before=fs.readFileSync(f.profile.patchPath);
  await assert.rejects(f.ctx.configEditor.edit(f.entry(),()=>{throw Error('fixture-transform-failure');}),/fixture-transform-failure/);assert.deepEqual(fs.readFileSync(f.profile.patchPath),before);noLock(f);
  await assert.rejects(edit(f,13),/fixture-rejected-config/);await f.ctx.loader.await();assert.deepEqual(fs.readFileSync(f.profile.patchPath),before);assert.equal(valueOf(f),3);assert.equal(f.entry().fiber.state,2);noLock(f);
  await edit(f,4);assert.equal(valueOf(f),4);noLock(f);
  await assert.rejects(f.ctx.hmr.runExclusive(()=>edit(f,5)),/HMR transactions cannot be nested/);assert.equal(valueOf(f),4);noLock(f);
  const nestedAt=performance.now();
  await withFileLock(path.join(f.profile.dir,'package.json'),()=>f.ctx.hmr.runExclusive(async()=>{
   await assert.rejects(edit(f,5),/HMR transactions cannot be nested/);
  }));
  assert.ok(performance.now()-nestedAt<1000,'nested HMR must reject before waiting on its own two-second file lock');
  assert.equal(valueOf(f),4);noLock(f);
  results.push({name:'config-validation-activation-rollback-nested-guard',ok:true});
  const held=Promise.withResolvers(),release=Promise.withResolvers();
  const holder=withFileLock(path.join(f.profile.dir,'package.json'),async()=>{held.resolve();await release.promise;});await held.promise;
  let replayed=0;const contended=f.ctx.configEditor.edit(f.entry(),current=>{replayed++;return {...current,value:6};}).then(()=>null,error=>error);
  const probe=Promise.withResolvers();f.ctx.hmr.runExclusive(()=>{probe.resolve();});
  await Promise.race([probe.promise,wait(250).then(()=>{throw Error('file-lock wait occupied HMR');})]);
  const refused=await contended;assert.match(refused.message,/timed out waiting for the writer lock/);assert.equal(replayed,0);release.resolve();await holder;assert.equal(valueOf(f),4);noLock(f);
  results.push({name:'external-lock-timeout-no-hmr-starvation-no-replay',ok:true});
  f.state.failToggle=true;const failure=await f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true);assert.equal(failure.application,'failed');noLock(f);
  f.state.failToggle=false;const off=await f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',false);assert.equal(off.application,'applied');assert.equal(toggle(f),undefined);noLock(f);
  const previousInitializations=f.state.events.filter(row=>row[0]==='toggle-initialize').length;
  f.state.toggleGate=Promise.withResolvers();let settled=false;
  const waiting=f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true).then(result=>{settled=true;return result;});
  for(let i=0;i<100&&f.state.events.filter(row=>row[0]==='toggle-initialize').length===previousInitializations;i++)await wait(5);
  assert.equal(f.state.events.filter(row=>row[0]==='toggle-initialize').length,previousInitializations+1);
  await wait(60);assert.equal(settled,false,'must not report applied before plugin activation completes');assert.notEqual(toggle(f)?.fiber?.state,2);
  f.state.toggleGate.resolve();assert.equal((await waiting).application,'applied');assert.equal(toggle(f)?.fiber?.state,2);noLock(f);
  assert.equal((await f.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',false)).application,'applied');noLock(f);
  results.push({name:'plugin-failure-release-and-real-loader-readiness',ok:true});
 }finally{await f.close();}
 const plain=await fixture(AfterEditor,{hmr:false});
 try{await edit(plain,8);assert.equal(valueOf(plain),8);assert.equal(plain.entry().fiber.state,2);noLock(plain);const selected=await plain.ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true);assert.equal(selected.application,'restart-required');assert.equal(toggle(plain),undefined);noLock(plain);results.push({name:'without-hmr-config-save-and-restart-required',ok:true});}finally{await plain.close();}
 const receipt={schema:1,node:process.version,platform:process.platform,owned,recipeSha256:sha(fs.readFileSync(path.join(source,'app/src/main/assets/config-editor-lock-order-patch.json'))),upstreamSha256:sha(original),patchedSha256:sha(patched),managerSha256:sha(managerSource),managerRecipes,inventoryDependency:inventoryDependency.evidence,fixtureScope:'current-recipe target modules and real privately rebuilt inventory dependency with proven pinned raw peers; exactly one import adaptation; real Cordis Loader Include, HMR queue and file lock; local plugins only, no browser/device/network/model claims',results};
 const report=process.env.DSHA_CONFIG_LOCK_REPORT;if(report){fs.mkdirSync(path.dirname(report),{recursive:true});fs.writeFileSync(report,JSON.stringify(receipt,null,2)+'\n');}
 console.log('PASS: exact-source ABBA negative control, concurrent config/plugin success, validation/activation rollback, lock expiry, HMR guard, honest readiness and no-HMR path.');
}finally{delete globalThis.__DSHA_CONFIG_LOCK_LIFECYCLE__;}
