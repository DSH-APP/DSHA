import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';
// Current exact recipe targets are built privately beside a fully proven raw
// runtime. Expected pending/failed fixture warnings are negative state coverage.
const S=path.resolve(import.meta.dirname,'..');
const raw=testRuntime('raw'),assets=path.join(S,'app/src/main/assets'),recipes=assets;
const report=process.env.DSHA_PLUGIN_ENTRIES_REPORT;
const sha=value=>createHash('sha256').update(value).digest('hex');
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-plugin-entries-proof-'));
const modules=path.join(owned,'node_modules');fs.mkdirSync(modules);
const link=(from,to)=>fs.symlinkSync(from,to,process.platform==='win32'?'junction':'dir');
for(const entry of fs.readdirSync(path.join(raw,'node_modules'),{withFileTypes:true})){
 if(entry.name==='@deepseek-ai'||!entry.isDirectory())continue;link(path.join(raw,'node_modules',entry.name),path.join(modules,entry.name));
}
const scope=path.join(modules,'@deepseek-ai');fs.mkdirSync(scope);
for(const entry of fs.readdirSync(path.join(raw,'node_modules/@deepseek-ai'),{withFileTypes:true})){
 if(entry.name==='dsh-host-plugin-inventory'||!entry.isDirectory())continue;link(path.join(raw,'node_modules/@deepseek-ai',entry.name),path.join(scope,entry.name));
}
function apply(source,recipe,base){for(const part of recipe.patches){assert.equal(source.split(part.before).length,2);const after=(part.prependAsset?fs.readFileSync(path.join(base,part.prependAsset),'utf8')+'\n':'')+part.after;source=source.replace(part.before,after);}return source;}
const inventoryName='@deepseek-ai/dsh-host-plugin-inventory/lib/index.js',managerName='@deepseek-ai/dsh-plugin-manager/lib/index.js';
const originalInventory=fs.readFileSync(path.join(raw,'node_modules',inventoryName),'utf8').replaceAll('\r\n','\n');
const inventoryRecipe=JSON.parse(fs.readFileSync(path.join(recipes,'plugin-inventory-entries-patch.json')));
assert.equal(inventoryRecipe.module,inventoryName);assert.equal(inventoryRecipe.dshVersion,'0.2.1-rc.2');
assert.equal(sha(originalInventory),inventoryRecipe.sourceSha256);
const inventorySource=apply(originalInventory,inventoryRecipe,assets);
let reversedInventory=inventorySource;for(const part of [...inventoryRecipe.patches].reverse()){assert.equal(reversedInventory.split(part.after).length,2);reversedInventory=reversedInventory.replace(part.after,part.before);}assert.equal(reversedInventory,originalInventory,'unrelated full-inventory projection/semantics changed');
const inventoryPackage=path.join(scope,'dsh-host-plugin-inventory');fs.mkdirSync(path.join(inventoryPackage,'lib'),{recursive:true});
fs.copyFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-host-plugin-inventory/package.json'),path.join(inventoryPackage,'package.json'));
const inventoryFile=path.join(inventoryPackage,'lib/index.js');fs.writeFileSync(inventoryFile,inventorySource);
let beforeManager=fs.readFileSync(path.join(raw,'node_modules',managerName),'utf8').replaceAll('\r\n','\n');
const rawManagerSha=sha(beforeManager);
for(const row of JSON.parse(fs.readFileSync(path.join(assets,'runtime-patches.json'))).active){const recipe=JSON.parse(fs.readFileSync(path.join(assets,row.asset)));if(row.asset==='plugin-manager-entries-patch.json')continue;if((row.module??recipe.module)===managerName)beforeManager=apply(beforeManager,recipe,assets);}
const managerRecipe=JSON.parse(fs.readFileSync(path.join(recipes,'plugin-manager-entries-patch.json')));
assert.equal(managerRecipe.module,managerName);assert.equal(managerRecipe.dshVersion,'0.2.1-rc.2');assert.equal(managerRecipe.sourceSha256,rawManagerSha);
const afterManager=apply(beforeManager,managerRecipe,assets);
let reversed=afterManager;for(const part of [...managerRecipe.patches].reverse()){assert.equal(reversed.split(part.after).length,2);reversed=reversed.replace(part.after,part.before);}assert.equal(reversed,beforeManager,'unrelated manager/proof/lifecycle code changed');
const beforeFile=path.join(owned,'manager-before.mjs'),afterFile=path.join(owned,'manager-after.mjs');
// The baseline uses the actual original inventory. The candidate resolves its
// normal package import to the private overlay above. No loader hook is used.
fs.writeFileSync(beforeFile,beforeManager.replace('from "@deepseek-ai/dsh-host-plugin-inventory"','from '+JSON.stringify(pathToFileURL(path.join(raw,'node_modules',inventoryName)).href)));
fs.writeFileSync(afterFile,afterManager);
const mod=name=>import(pathToFileURL(path.join(raw,'node_modules/@deepseek-ai',name,'lib/index.js')));
const [{PluginManager:Before},{PluginManager:After},beforeInventory,afterInventory,{boot,readProfilePatches},{default:Hmr},{default:Timer},{AgentPresetRegistry}]=await Promise.all([
 import(pathToFileURL(beforeFile)),import(pathToFileURL(afterFile)),import(pathToFileURL(path.join(raw,'node_modules',inventoryName))),import(pathToFileURL(inventoryFile)),
 mod('dsh-app-boot'),mod('dsh-hmr'),mod('cordis-plugin-timer'),mod('dsh-agent-preset-registry')
]);
const home=path.join(owned,'home'),profileDir=path.join(home,'profiles/web');fs.mkdirSync(path.join(profileDir,'node_modules'),{recursive:true});
globalThis.__DSHA_ENTRIES_PROOF__={events:[],slow:Promise.withResolvers()};
const pluginFile=path.join(owned,'plugin.mjs');
fs.writeFileSync(pluginFile,`export const name='fixture-live-entry';export function apply(ctx,config){if(config.fail)throw Error('fixture-entry-failure');globalThis.__DSHA_ENTRIES_PROOF__.events.push(['active',config.id]);ctx.effect(()=>()=>globalThis.__DSHA_ENTRIES_PROOF__.events.push(['disposed',config.id]));}`);
const pendingFile=path.join(owned,'pending.mjs');fs.writeFileSync(pendingFile,`export const name='fixture-pending';export const inject=['fixtureNeverProvided'];export function apply(){throw Error('must stay pending');}`);
const slowFile=path.join(owned,'slow.mjs');fs.writeFileSync(slowFile,`export const name='fixture-slow';export async function apply(){globalThis.__DSHA_ENTRIES_PROOF__.slowEntered=true;await globalThis.__DSHA_ENTRIES_PROOF__.slow.promise;}`);
const live=(id,extra={})=>({id,name:pathToFileURL(pluginFile).href,config:{id},...extra});
const rows=[live('active'),live('disabled',{disabled:true}),{id:'pending',name:pathToFileURL(pendingFile).href},live('failed',{config:{fail:true}})];
function bundle(name,entries){const root=path.join(owned,name);fs.mkdirSync(root);fs.writeFileSync(path.join(root,'package.json'),JSON.stringify({name,version:'1.0.0',dsh:{bundle:{patch:'cordis.patch.yml'}}}));fs.writeFileSync(path.join(root,'cordis.patch.yml'),JSON.stringify([{insert:entries}]));link(root,path.join(profileDir,'node_modules',name));}
bundle('fixture-base-bundle',rows);bundle('fixture-toggle-bundle',[live('toggled')]);
const installAnchor=path.join(owned,'installation.json');fs.writeFileSync(installAnchor,JSON.stringify({name:'@deepseek-ai/dsh',version:'0.2.1-rc.2'}));
const profile={name:'web',dir:profileDir,home,patchPath:path.join(profileDir,'cordis.patch.yml'),overlays:[],startedBundles:['fixture-base-bundle'],installAnchor};
fs.writeFileSync(path.join(profileDir,'package.json'),JSON.stringify({name:'entries-proof',private:true,dsh:{profile:{bundles:['fixture-base-bundle']}}}));fs.writeFileSync(profile.patchPath,'[]\n');
const configPath=path.join(profileDir,'cordis.yml');fs.writeFileSync(configPath,'[]\n');
const ctx=await boot('dsh',configPath,readProfilePatches('dsh',profile),async context=>{context.provide('profileContext',profile);context.provide('sessionProjections',{register(){}});context.provide('appReady',{onReady(fn){queueMicrotask(fn);return ()=>{};}});});
const evidence={schema:1,owned,scope:'real host lifecycle and a controlled async Host-loading barrier; no device speed claim',sourceHashes:{inventory:sha(originalInventory),managerBefore:sha(beforeManager),inventoryAfter:sha(inventorySource),managerAfter:sha(afterManager)},cases:[]};
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
try{
 await ctx.plugin(Timer,{});await ctx.plugin(Hmr,{base:pathToFileURL(owned+'/').href,root:[],ignored:[]});await ctx.plugin(After,{});await ctx.plugin(AgentPresetRegistry,{default:'fixture-preset'});await ctx.loader.await();
 await ctx.agentPresets.register({id:'fixture-preset',name:'Real pending preset',plugins:[{id:'preset-pending',name:pathToFileURL(pendingFile).href}]});
 const baseline=Object.create(Before.prototype);Object.defineProperty(baseline,'ctx',{value:ctx});Object.assign(baseline,{profile,ownerContext:ctx});
 const registry=ctx.agentPresets,composition=registry.compositionInventory;let audits=0;registry.compositionInventory=function(...args){audits++;return composition.apply(this,args);};
 const wholeBefore=await beforeInventory.readPluginInventory(ctx),wholeAfter=await afterInventory.readPluginInventory(ctx);assert.deepEqual(wholeAfter,wholeBefore);assert.ok(wholeAfter.agentPresets[0].broken.includes('fixtureNeverProvided'));
 const metadata={title:'Fixture metadata passthrough',description:'Must survive without field loss',icon:'fixture-icon'};
 const metadataContext={loader:ctx.loader,baseUrl:ctx.baseUrl,get:name=>name==='pluginPackages'?{metaOf:()=>metadata}:ctx.get(name)};
 const metaBefore=await beforeInventory.readPluginInventory(metadataContext),metaAfter=await afterInventory.readPluginInventory(metadataContext);assert.deepEqual(metaAfter,metaBefore);assert.deepEqual(metaAfter.entries.find(row=>row.entryId==='include:active').meta,metadata);
 audits=0;const beforeRows=await baseline.listPlugins();const beforeCount=audits;audits=0;const afterRows=await ctx.pluginManager.listPlugins();const afterCount=audits;assert.deepEqual(afterRows,beforeRows);assert.equal(beforeCount,1);assert.equal(afterCount,0);
 const phases=Object.fromEntries(afterRows.map(row=>[row.entryId,row.fiberPhase]));assert.equal(phases['include:active'],'active');assert.equal(phases['include:pending'],'pending');assert.equal(phases['include:failed'],'failed');assert.equal(afterRows.find(row=>row.entryId==='include:disabled').enabled,false);
 evidence.cases.push({name:'full-inventory-equivalence-and-one-unused-audit-removed',beforeCount,afterCount,phases});
 audits=0;const rowOn=await ctx.pluginManager.setPluginEnabled('include:disabled',true);assert.equal(rowOn.application,'applied',JSON.stringify(rowOn));assert.equal((await ctx.pluginManager.listPlugins()).find(row=>row.entryId==='include:disabled').fiberPhase,'active');const rowOff=await ctx.pluginManager.setPluginEnabled('include:disabled',false);assert.equal(rowOff.application,'applied',JSON.stringify(rowOff));assert.equal((await ctx.pluginManager.listPlugins()).find(row=>row.entryId==='include:disabled').enabled,false);assert.equal(audits,0);
 evidence.cases.push({name:'real-row-enable-disable-no-cache-or-unused-preset-audits',ok:true,presetAudits:audits});
 const on=await ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',true);assert.equal(on.application,'applied');assert.equal((await ctx.pluginManager.listPlugins()).find(row=>row.entryId==='include:toggled').fiberPhase,'active');
 const off=await ctx.pluginManager.setBundleEnabled('fixture-toggle-bundle',false);assert.equal(off.application,'applied');assert.equal((await ctx.pluginManager.listPlugins()).some(row=>row.entryId==='include:toggled'),false);
 evidence.cases.push({name:'real-HMR-bundle-on-off',on: on.application,off: off.application});
 const tree=[...ctx.loader.entries()].find(row=>row.options.id==='active').parent.tree;
 const start=performance.now();const creating=tree.create({id:'slow-unrelated-host',name:pathToFileURL(slowFile).href});
 for(let i=0;i<100&&!globalThis.__DSHA_ENTRIES_PROOF__.slowEntered;i++)await wait(5);assert.equal(globalThis.__DSHA_ENTRIES_PROOF__.slowEntered,true);
 let oldSettled=false,fullSettled=false;
 const oldRequest=baseline.listPlugins().then(value=>{oldSettled=true;return value;});
 const fullRequest=afterInventory.readPluginInventory(ctx).then(value=>{fullSettled=true;return value;});
 const fast=await ctx.pluginManager.listPlugins();const fastAt=performance.now()-start;
 assert.equal(fast.find(row=>row.entryId==='include:slow-unrelated-host').fiberPhase,'loading');await wait(120);assert.equal(oldSettled,false);assert.equal(fullSettled,false);
 globalThis.__DSHA_ENTRIES_PROOF__.slow.resolve();await creating;const [old]=await Promise.all([oldRequest,fullRequest]);assert.deepEqual(old,fast,'old entries were also captured before the unrelated audit wait');
 assert.equal((await ctx.pluginManager.listPlugins()).find(row=>row.entryId==='include:slow-unrelated-host').fiberPhase,'active');
 evidence.cases.push({name:'real-unrelated-Host-loading-barrier',fastSnapshotMs:fastAt,baselineStillBlockedAfterControlled120ms:true,fullInventoryStillWaited:true,oldSnapshotEqualsImmediateSnapshot:true});
}finally{globalThis.__DSHA_ENTRIES_PROOF__.slow.resolve();await ctx.fiber.dispose();delete globalThis.__DSHA_ENTRIES_PROOF__;}
if(report){fs.mkdirSync(path.dirname(report),{recursive:true});fs.writeFileSync(report,JSON.stringify(evidence,null,2)+'\n');}console.log('PASS: real Host entries equivalence, one discarded preset audit removed, row/bundle ON/OFF, pending/failed/disabled facts and honest async loading snapshots.');
