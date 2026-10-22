import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';
import {browserFixture} from './rc1-browser-fixture.mjs';

// Proven, untouched raw peers; the complete current target-module chains are
// rebuilt privately. Browser transport connects the real Controller to the
// real Host services, with explicit failure/delay controls at that boundary.
const S=path.resolve(import.meta.dirname,'..'),assets=path.join(S,'app/src/main/assets');
const raw=testRuntime('raw'),sha=value=>createHash('sha256').update(value).digest('hex');
const registry=JSON.parse(fs.readFileSync(path.join(assets,'runtime-patches.json')));
const inventoryName='@deepseek-ai/dsh-host-plugin-inventory/lib/index.js';
const managerName='@deepseek-ai/dsh-plugin-manager/lib/index.js';
const clientName='@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js';
const ownNames=['plugin-inventory-management-patch.json','plugin-manager-management-probe-patch.json','plugin-inventory-management-host-codec-patch.json','plugin-inventory-management-remote-codec-patch.json','plugin-inventory-management-api-codec-patch.json'];
const inputs={};
function recipe(name){const bytes=fs.readFileSync(path.join(assets,name));inputs[name]=sha(bytes);return JSON.parse(bytes);}
function apply(text,row){for(const patch of row.patches){assert.equal(text.split(patch.before).length,2,row.module+' exact anchor');text=text.replace(patch.before,(patch.prependAsset?fs.readFileSync(path.join(assets,patch.prependAsset),'utf8')+'\n':'')+patch.after);}return text;}
function current(module){let text=fs.readFileSync(path.join(raw,'node_modules',module),'utf8').replaceAll('\r\n','\n');const canonical=sha(text),rows=[];for(const row of registry.active){if(ownNames.includes(row.asset))continue;const item=recipe(row.asset);if((row.module??item.module)!==module)continue;if(item.sourceSha256!==undefined)assert.equal(item.sourceSha256,canonical,row.asset+' canonical identity');text=apply(text,item);rows.push(item);}return {text,canonical,rows};}
const inventory=current(inventoryName),manager=current(managerName),client=current(clientName);
const inventoryRecipe=recipe(ownNames[0]),clientRecipe=recipe(ownNames[1]);
assert.equal(inventoryRecipe.sourceSha256,inventory.canonical);assert.equal(clientRecipe.sourceSha256,client.canonical);
const afterInventory=apply(inventory.text,inventoryRecipe),afterClient=apply(client.text,clientRecipe);
const codecRows=ownNames.slice(2).map(name=>{const item=recipe(name),base=current(item.module);assert.equal(item.sourceSha256,base.canonical);return {name,recipe:item,base,patched:apply(base.text,item)};});
function reverse(text,row){for(const patch of [...row.patches].reverse()){assert.equal(text.split(patch.after).length,2);text=text.replace(patch.after,patch.before);}return text;}
assert.equal(reverse(afterInventory,inventoryRecipe),inventory.text,'full-inventory implementation must remain byte-identical');
assert.equal(reverse(afterClient,clientRecipe),client.text,'all Controller error/freshness/mutation logic must remain byte-identical');
assert.match(inventory.text,/const management = ctx\.get\("pluginManager"\).*\n\tif \(presets === void 0\)/);
assert.ok(inventory.text.indexOf('const management =')<inventory.text.indexOf('await presets.compositionInventory()'),'the old capability snapshot already precedes the unrelated diagnostic wait');

const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-plugin-management-probe-'));
fs.symlinkSync(path.join(raw,'node_modules'),path.join(owned,'node_modules'),process.platform==='win32'?'junction':'dir');
const beforeFile=path.join(owned,'inventory-before.mjs'),afterFile=path.join(owned,'inventory-after.mjs');
fs.writeFileSync(beforeFile,inventory.text);fs.writeFileSync(afterFile,afterInventory);
const needle='from "@deepseek-ai/dsh-host-plugin-inventory"';assert.equal(manager.text.split(needle).length,2);
const managerSource=manager.text.replace(needle,'from '+JSON.stringify(pathToFileURL(afterFile).href));
assert.equal(managerSource.replace('from '+JSON.stringify(pathToFileURL(afterFile).href),needle),manager.text);
const managerFile=path.join(owned,'manager.mjs');fs.writeFileSync(managerFile,managerSource);
const mod=name=>import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai',name,'lib/index.js')));
const [{PluginInventoryGateway:Before,readPluginInventory:readBefore},{PluginInventoryGateway:After,readPluginInventory:readAfter},{PluginManager},{boot,readProfilePatches},{default:Hmr},{default:Timer},{AgentPresetRegistry}]=await Promise.all([
 import(pathToFileURL(beforeFile)),import(pathToFileURL(afterFile)),import(pathToFileURL(managerFile)),mod('dsh-app-boot'),mod('dsh-hmr'),mod('cordis-plugin-timer'),mod('dsh-agent-preset-registry')
]);
const home=path.join(owned,'home'),profileDir=path.join(home,'profiles/web');fs.mkdirSync(path.join(profileDir,'node_modules'),{recursive:true});
const state={events:[],gate:Promise.withResolvers()};globalThis.__DSHA_MANAGEMENT_PROBE__=state;
const pendingFile=path.join(owned,'pending.mjs');fs.writeFileSync(pendingFile,"export const name='fixture-pending';export const inject=['fixtureNeverProvided'];export function apply(){throw Error('must remain pending');}");
const slowFile=path.join(owned,'slow.mjs');fs.writeFileSync(slowFile,"export const name='fixture-slow-host';export async function apply(){globalThis.__DSHA_MANAGEMENT_PROBE__.entered=true;await globalThis.__DSHA_MANAGEMENT_PROBE__.gate.promise;}");
const liveFile=path.join(owned,'live.mjs');fs.writeFileSync(liveFile,"export const name='fixture-toggle';export function apply(ctx){globalThis.__DSHA_MANAGEMENT_PROBE__.events.push('active');ctx.effect(()=>()=>globalThis.__DSHA_MANAGEMENT_PROBE__.events.push('disposed'));}");
function bundle(name,rows){const folder=path.join(owned,name);fs.mkdirSync(folder);fs.writeFileSync(path.join(folder,'package.json'),JSON.stringify({name,version:'1.0.0',dsh:{bundle:{patch:'cordis.patch.yml'}}}));fs.writeFileSync(path.join(folder,'cordis.patch.yml'),JSON.stringify([{insert:rows}]));fs.symlinkSync(folder,path.join(profileDir,'node_modules',name),process.platform==='win32'?'junction':'dir');}
bundle('fixture-base-bundle',[{id:'host-pending',name:pathToFileURL(pendingFile).href}]);bundle('fixture-toggle-bundle',[{id:'live-toggle',name:pathToFileURL(liveFile).href}]);
const installAnchor=path.join(owned,'installation.json');fs.writeFileSync(installAnchor,JSON.stringify({name:'@deepseek-ai/dsh',version:'0.2.1-rc.2'}));
const profile={name:'web',dir:profileDir,home,patchPath:path.join(profileDir,'cordis.patch.yml'),overlays:[],startedBundles:['fixture-base-bundle'],installAnchor};
fs.writeFileSync(path.join(profileDir,'package.json'),JSON.stringify({name:'management-probe-fixture',private:true,dsh:{profile:{bundles:['fixture-base-bundle']}}}));fs.writeFileSync(profile.patchPath,'[]\n');
const configPath=path.join(profileDir,'cordis.yml');fs.writeFileSync(configPath,'[]\n');
const ctx=await boot('dsh',configPath,readProfilePatches('dsh',profile),async scope=>{scope.provide('profileContext',profile);scope.provide('sessionProjections',{register(){}});scope.provide('appReady',{onReady(fn){queueMicrotask(fn);return ()=>{};}});});
const evidence={schema:1,node:process.version,owned,inputs,sourceHashes:{inventoryBefore:sha(inventory.text),inventoryAfter:sha(afterInventory),manager:sha(manager.text),clientBefore:sha(client.text),clientAfter:sha(afterClient)},scope:'real Cordis Loader/HMR/AgentPresetRegistry, pinned browser Controller, generated ClientRemoteService proxy and aggregate descriptors over loopback HTTP to actual Host Gateway input/result codecs; fixture-owned owner admission, not a phone/authentication benchmark',cases:[]};
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let browser,managerFiber,wireServer;
const calls={};let transportFailure=false,holdBundles;
try{
 await ctx.plugin(Timer,{});await ctx.plugin(Hmr,{base:pathToFileURL(owned+'/').href,root:[],ignored:[]});await ctx.plugin(After,{});await ctx.plugin(AgentPresetRegistry,{default:'pending-preset'});await ctx.loader.await();
 await ctx.agentPresets.register({id:'pending-preset',name:'Actual pending preset',plugins:[{id:'pending-row',name:pathToFileURL(pendingFile).href}]});
 const gateway=ctx.pluginInventory;
 assert.deepEqual(await gateway.management(),{managementAvailable:false});
 const absent=await gateway.list();assert.equal(absent.managementAvailable,undefined);assert.ok(absent.agentPresets[0].broken.includes('fixtureNeverProvided'));
 managerFiber=ctx.plugin(PluginManager,{});await managerFiber;assert.equal(managerFiber.state,2);
 assert.deepEqual(await gateway.management(),{managementAvailable:true});
 assert.deepEqual(await gateway.list(),await Before.prototype.list.call(gateway),'default list complete contract unchanged');
 assert.deepEqual(await gateway.list('unknown-projection'),await gateway.list(),'unknown/old argument remains a full list');
 assert.deepEqual(await readBefore(ctx),await readAfter(ctx),'direct full reader byte-preserved semantics');
 await assert.rejects(After.prototype.management.call({ctx:{get(){throw Error('fixture-host-read-failure');}}}),/fixture-host-read-failure/,'service read failures must not be converted to unavailable');
 evidence.cases.push({name:'live-absent-active-default-equivalence-and-error-propagation',ok:true});
 const on=await ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true);assert.equal(on.application,'applied');assert.equal((await ctx.pluginManager.listPlugins()).find(row=>row.entryId==='include:live-toggle').fiberPhase,'active');
 assert.deepEqual(await gateway.management(),{managementAvailable:true});
 const off=await ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',false);assert.equal(off.application,'applied');assert.equal((await ctx.pluginManager.listPlugins()).some(row=>row.entryId==='include:live-toggle'),false);assert.deepEqual(state.events,['active','disposed']);
 evidence.cases.push({name:'real-HMR-enable-disable-and-live-capability',on:on.application,off:off.application});
 // Real generated Host descriptors, Gateway input/result codecs and browser
 // ClientRemoteService. Only admission is fixture-owned (loopback owner Peer);
 // no phone authentication or external model capability is claimed.
 const hostCodecFile=path.join(owned,'inventory-host-codec.mjs'),remoteCodecFile=path.join(owned,'inventory-remote-codec.mjs');
 fs.writeFileSync(hostCodecFile,codecRows[0].patched);fs.writeFileSync(remoteCodecFile,codecRows[1].patched);
 const [{TYPERT:hostInfo},{default:remoteInfo},{TYPERT:oldHostInfo},{default:oldRemoteInfo},{default:TypertRegistry},{default:HostGateway},{TYPERT:managerInfo}]=await Promise.all([
  import(pathToFileURL(hostCodecFile)),import(pathToFileURL(remoteCodecFile)),import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai/dsh-host-plugin-inventory/lib/typert.host.js'))),import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai/dsh-host-plugin-inventory/lib/typert.remote-client.js'))),mod('dsh-typert-registry'),mod('dsh-api-gateway'),import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai/dsh-plugin-manager/lib/typert.host.js')))
 ]);
 const hostDefault=hostInfo.invocations.find(row=>row.method==='list'),oldDefault=oldHostInfo.invocations.find(row=>row.method==='list');
 assert.deepEqual(hostDefault.parameters,oldDefault.parameters);assert.deepEqual(hostDefault.result.create().parse(await gateway.list()),oldDefault.result.create().parse(await gateway.list()));
 assert.equal(remoteInfo.methods['pluginInventory/list'].parameters.length,0);assert.equal(remoteInfo.methods['pluginInventory/management'].parameters.length,0);assert.equal(oldRemoteInfo.methods['pluginInventory/list'].parameters.length,0);
 assert.equal(hostDefault.result.create().safeParse({managementAvailable:true}).success,false,'complete-list result must still require entries');
 const managementHost=hostInfo.invocations.find(row=>row.method==='management');assert.equal(managementHost.result.create().safeParse({}).success,false);assert.equal(managementHost.result.create().safeParse({managementAvailable:'yes'}).success,false);
 await ctx.plugin(TypertRegistry,{});await ctx.typert.register(hostInfo);await ctx.typert.register(managerInfo);ctx.provide('remoteAccess',{decide(){throw Error('fixture owner must not use a non-owner access bypass');}});await ctx.plugin(HostGateway,{});
 const peer={principal:{kind:'owner'},interactions:'none',signal:new AbortController().signal};
 wireServer=http.createServer(async(req,res)=>{
  res.setHeader('Access-Control-Allow-Origin','*');res.setHeader('Access-Control-Allow-Headers','Content-Type,X-Fixture-Label');res.setHeader('Content-Type','application/json');if(req.method==='OPTIONS'){res.statusCode=204;res.end();return;}
  const endpoint=decodeURIComponent(req.url.slice('/api/'.length)),label=String(req.headers['x-fixture-label']??'wire-check'),item=calls[label]??={inventory:0,bundles:0,plugins:0,projections:[]};
  try{
   let body='';for await(const chunk of req){body+=chunk;if(body.length>65536)throw Error('fixture request too large');}
   if(endpoint.startsWith('pluginInventory/')){item.inventory++;item.projections.push(endpoint.endsWith('/management')?'management':null);if(transportFailure)throw Error('fixture-inventory-rpc-failure');}
   else if(endpoint==='pluginManager/listBundles')item.bundles++;else if(endpoint==='pluginManager/listPlugins')item.plugins++;
   const result=await ctx.typertGateway.dispatchRpc(endpoint,JSON.parse(body),new AbortController().signal,peer);
   if(endpoint==='pluginManager/listBundles'&&holdBundles)await holdBundles.promise;
   res.end(JSON.stringify(result));
  }catch(error){res.end(JSON.stringify({ok:false,error:{code:'fixture/carrier-failure',message:error.message}}));}
 });
 await new Promise(resolve=>wireServer.listen(0,'127.0.0.1',resolve));const wireOrigin='http://127.0.0.1:'+wireServer.address().port;
 const malformed=await (await fetch(wireOrigin+'/api/pluginInventory/management',{method:'POST',body:JSON.stringify({args:{projection:'management'}})})).json();assert.equal(malformed.ok,false,'management is a strictly zero-argument RPC');
 evidence.cases.push({name:'strict-generated-contracts-preserve-default-zero-args-and-required-results',ok:true,modules:codecRows.map(row=>({module:row.recipe.module,before:sha(row.base.text),after:sha(row.patched)}))});
 const audit=ctx.agentPresets.compositionInventory;let audits=0;ctx.agentPresets.compositionInventory=function(...args){audits++;return audit.apply(this,args);};
 const hostTree=[...ctx.loader.entries()].find(entry=>entry.options.id==='host-pending').parent.tree;
 const slow=hostTree.create({id:'unrelated-slow-host',name:pathToFileURL(slowFile).href});
 for(let i=0;i<100&&!state.entered;i++)await wait(5);assert.equal(state.entered,true);
 const fullAt=performance.now();let fullSettled=false;const full=gateway.list().then(value=>{fullSettled=true;return value;});
 const probeAt=performance.now(),probe=await gateway.management(),probeMs=performance.now()-probeAt;assert.deepEqual(probe,{managementAvailable:true});
 await wait(120);assert.equal(fullSettled,false,'negative control must really block in the unchanged full diagnostic');
 const afterFullAuditCalls=audits;assert.equal(afterFullAuditCalls,1);

 browser=await browserFixture(raw);const page=browser.page;
 await page.addScriptTag({content:fs.readFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-typert-registry/lib/client.browser.js'),'utf8')});
 await page.addScriptTag({content:fs.readFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-api-gateway/lib/client.browser.js'),'utf8')});
 await browser.load('dsh-api-remotes',[...codecRows[2].base.rows,codecRows[2].recipe]);
 await browser.load('dsh-client-ui-plugin-manager',client.rows,'exports.audit={PluginManagerController};');
 await page.evaluate(()=>{window.BeforeController=auditExports['@deepseek-ai/dsh-client-ui-plugin-manager'].audit.PluginManagerController;});
 await browser.load('dsh-client-ui-plugin-manager',[...client.rows,clientRecipe],'exports.audit={PluginManagerController};');
 await page.evaluate(()=>{window.AfterController=auditExports['@deepseek-ai/dsh-client-ui-plugin-manager'].audit.PluginManagerController;});
 await page.evaluate(({wireOrigin})=>{
  window.controllers={};window.startProbe=async label=>{
   const Controller=label==='before'?BeforeController:AfterController;
   const ctx=new auditModules['@deepseek-ai/cordis'].Context();
   ctx.provide('connection',{interactions:'none',rpc:{open(){throw Error('fixture does not open streams');},async call(prefix,endpoint,payload,signal){return await (await fetch(wireOrigin+prefix+'/'+endpoint,{method:'POST',headers:{'Content-Type':'application/json','X-Fixture-Label':label},body:JSON.stringify(payload),signal})).json();}},generation:{getSnapshot:()=>undefined}});
   await ctx.plugin(auditExports['@deepseek-ai/dsh-typert-registry']);await ctx.plugin(auditExports['@deepseek-ai/dsh-api-gateway']);await ctx.plugin(auditExports['@deepseek-ai/dsh-api-remotes']);
   const manager=new Controller(ctx);controllers[label]={ctx,manager,started:performance.now(),settled:false};manager.load().finally(()=>{controllers[label].settled=true;controllers[label].ms=performance.now()-controllers[label].started;});
  };
 },{wireOrigin});
 audits=0;await page.evaluate(()=>startProbe('before'));
 for(let i=0;i<100&&calls.before?.inventory!==1;i++)await wait(5);assert.equal(calls.before.inventory,1);
 await wait(120);assert.equal(await page.evaluate(()=>controllers.before.settled),false);
 await page.evaluate(()=>startProbe('after'));
 await page.waitForFunction(()=>controllers.after.settled);
 const pending=await page.evaluate(()=>({before:{settled:controllers.before.settled,status:controllers.before.manager.getSnapshot().status},after:{settled:controllers.after.settled,status:controllers.after.manager.getSnapshot().status,ms:controllers.after.ms}}));
 assert.equal(pending.before.settled,false);assert.equal(pending.after.status,'ready');assert.deepEqual(calls.before,{inventory:1,bundles:0,plugins:0,projections:[null]});assert.deepEqual(calls.after,{inventory:1,bundles:1,plugins:1,projections:['management']});assert.equal(audits,1,'only baseline must invoke discarded preset composition');
 state.gate.resolve();await slow;await full;await page.waitForFunction(()=>controllers.before.settled);
 const completed=await page.evaluate(()=>({before:{status:controllers.before.manager.getSnapshot().status,packages:controllers.before.manager.getSnapshot().packages,ms:controllers.before.ms},after:{status:controllers.after.manager.getSnapshot().status,packages:controllers.after.manager.getSnapshot().packages,ms:controllers.after.ms}}));
 assert.equal(completed.before.status,'ready');assert.deepEqual(completed.after.packages,completed.before.packages);
 evidence.cases.push({name:'actual-unrelated-loader-negative-and-real-client-page',probeMs,fullHeldMs:performance.now()-fullAt,baselineBlockedAtCandidateReady:true,clientDurationsMs:{before:completed.before.ms,after:completed.after.ms},rpcCounts:{before:calls.before,after:calls.after},compositionAudits:{before:1,after:0},rowsEqual:true,limitation:'controlled pending barrier over actual proxy/HTTP/Gateway wire; not a phone total-latency estimate'});
 holdBundles=Promise.withResolvers();await page.evaluate(()=>startProbe('held-bundles'));
 for(let i=0;i<100&&calls['held-bundles']?.bundles!==1;i++)await wait(5);assert.equal(calls['held-bundles'].bundles,1);await wait(60);
 assert.equal(await page.evaluate(()=>controllers['held-bundles'].settled),false,'capability is not a list or mutation completion proof');
 holdBundles.resolve();holdBundles=undefined;await page.waitForFunction(()=>controllers['held-bundles'].settled);
 transportFailure=true;await page.evaluate(()=>startProbe('error'));await page.waitForFunction(()=>controllers.error.settled);assert.equal(await page.evaluate(()=>controllers.error.manager.getSnapshot().status),'error');assert.equal(calls.error.bundles,0);transportFailure=false;
 await managerFiber.dispose();assert.deepEqual(await gateway.management(),{managementAvailable:false});
 await page.evaluate(()=>startProbe('absent'));await page.waitForFunction(()=>controllers.absent.settled);assert.equal(await page.evaluate(()=>controllers.absent.manager.getSnapshot().status),'unavailable');assert.equal(calls.absent.bundles,0);
 state.gate=Promise.withResolvers();state.entered=false;
 const reappearing=hostTree.create({id:'unrelated-reappearance-barrier',name:pathToFileURL(slowFile).href});
 for(let i=0;i<100&&!state.entered;i++)await wait(5);assert.equal(state.entered,true);
 let absentFullSettled=false;const absentFull=gateway.list().then(value=>{absentFullSettled=true;return value;});
 await wait(60);assert.equal(absentFullSettled,false);
 managerFiber=ctx.plugin(PluginManager,{});await managerFiber;assert.deepEqual(await gateway.management(),{managementAvailable:true});
 state.gate.resolve();await reappearing;assert.equal((await absentFull).managementAvailable,undefined,'the original full reader captured absence before its unrelated wait; it did not wait for this service to become present');
 await page.evaluate(()=>{window.reappear=controllers.absent.manager.load();});await page.evaluate(()=>reappear);assert.equal(await page.evaluate(()=>controllers.absent.manager.getSnapshot().status),'ready');
 evidence.cases.push({name:'authoritative-lists-still-required-and-live-provider-removal-reappearance-error',ok:true,oldCapabilityCapturedBeforeDiagnosticWait:true,reappearanceReadIsLive:true});
 assert.equal(browser.errors.length,0,JSON.stringify(browser.errors));
}finally{
 state.gate.resolve();if(browser){await browser.page.evaluate(async()=>{for(const value of Object.values(window.controllers??{})){value.manager.dispose();await value.ctx.fiber.dispose();}});await browser.close();}if(wireServer)await new Promise(resolve=>wireServer.close(resolve));await ctx.fiber.dispose();delete globalThis.__DSHA_MANAGEMENT_PROBE__;
}
const report=process.env.DSHA_PLUGIN_MANAGEMENT_REPORT;if(report){fs.mkdirSync(path.dirname(report),{recursive:true});fs.writeFileSync(report,JSON.stringify(evidence,null,2)+'\n');}
console.log('PASS: real Host capability projection, complete default diagnostics, live absent/active/HMR states, real Controller three-RPC page load, discarded audit removed and failures/readiness preserved.');
