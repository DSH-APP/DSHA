// Real locked Cordis dependency scopes/lifecycle; filesystem and 2s clock are
// fixtures so the test never writes the production /root activity record.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';
import {confirmedIdle} from '../app/src/main/assets/app-integration/activity.js';
import {installedPluginSampler,runtimePlugins} from '../app/src/main/assets/app-integration/runtime-plugins.js';

const runtime=testRuntime('raw');
const rawRequire=createRequire(path.join(runtime,'package.json'));
const {Context}=await import(pathToFileURL(rawRequire.resolve('@deepseek-ai/cordis')));
const ctx=new Context();
let local=ctx;
for(const service of ['agents','sessions','sessionProjections','dynamicCordisRunner','loader']) local=local.isolate(service);
const identity={generation:'lifecycle-generation',instance:'lifecycle-instance',profile:'web'};
const moduleName='./installed.js';
const evidence={...identity,bundles:[{name:'installed',version:'1.0.0',profile:'web',entries:[{
  id:'installed',moduleHash:createHash('sha256').update(moduleName).digest('hex'),group:false,
}]}]};
const loader={root:{}};
const include={options:{id:'include',name:'cordis:include'},parent:loader.root,subtree:{}};
loader.entries=()=>[include,{options:{id:'installed',name:moduleName},fiber:{state:2,uid:1},
  parent:{tree:include.subtree,ctx:{fiber:{entry:include}}}}].values();
ctx.provide('loader',{entries:()=>[].values()});
local.provide('loader',loader);
const agent={id:'local-session',status:'idle',inbox:{nextTurn:[],nextStep:[]},whenIdle:async()=>{}};
// Parent services must not satisfy an isolated page's sampling dependencies.
ctx.provide('agents',{list:()=>[{...agent,id:'wrong-parent-session'}]});
ctx.provide('sessions',{list:()=>[]});
ctx.provide('sessionProjections',{stateOf:()=>({active:[]})});
ctx.provide('jobs',{list:()=>[]});
const routes=new Set(), timers=new Map(), writes=[], samplerContexts=[];
let routeRegistrations=0, timerId=0, maxTimers=0;
ctx.provide('webServer',{register(route){routeRegistrations++;routes.add(route);return()=>routes.delete(route);}});
ctx.provide('connection',{authorizeIndex:()=>true});
const index=path.resolve('app/src/main/assets/app-integration/index.js');
const source=fs.readFileSync(index,'utf8').replace(/^import .*;\n/gm,'')
  .replace(/export (const|function) /g,'$1 ').replaceAll('import.meta.url',JSON.stringify(pathToFileURL(index).href));
const sandbox=vm.createContext({
  URL,
  process:{env:{DSHA_WEB_GENERATION:identity.generation,DSH_TASK_INSTANCE:identity.instance,DSHA_STARTUP_PROFILE:identity.profile},pid:123},
  confirmedIdle,runtimePlugins,
  installedPluginSampler(context,binding){
    assert.ok(Context.is(context));assert.notEqual(context,ctx);
    samplerContexts.push(context);return installedPluginSampler(context,binding,evidence);
  },
  readFileSync:fs.readFileSync,
  writeFileSync(file,json,options){assert.equal(options.mode,0o600);writes.push(JSON.parse(json));},
  renameSync(){},unlinkSync(){},
  setInterval(callback,ms){assert.equal(ms,2000);const timer={id:++timerId,unref(){}};timers.set(timer,callback);maxTimers=Math.max(maxTimers,timers.size);return timer;},
  clearInterval(timer){assert.ok(timers.delete(timer),'disposal must clear its active timer exactly once');},
});
vm.runInContext(source+'\nglobalThis.plugin={apply,inject:typeof inject === "undefined"?[]:inject};',sandbox);
const settle=()=>new Promise(setImmediate);
const tick=async()=>{for(const callback of [...timers.values()])await callback();await settle();};
const output=path.resolve(process.env.DSHA_INTEGRATION_LIFECYCLE_OUTPUT||'app/build/app-integration-lifecycle.json');
assert.ok(output.startsWith(path.resolve('app/build')+path.sep));
let removeAgents,removeSessions,removeProjection,removeRunner;
try {
  const plugin=await local.plugin(sandbox.plugin);
  await settle();
  assert.equal(routes.size,1,'origin route registers while optional activity services are unavailable');
  assert.equal(timers.size,0,'parent services cannot start a sampler in an isolated child');
  assert.equal(writes.length,0,'missing service creates no idle=true proof');
  const headers=[],bodies=[];
  await [...routes][0].handler({method:'GET'},{writeHead:(...args)=>headers.push(args),end:text=>bodies.push(text)});
  assert.equal(headers[0][0],200);assert.ok(bodies[0].includes('<!doctype html>'));
  removeAgents=local.provide('agents',{list:()=>[agent]});
  removeSessions=local.provide('sessions',{list:()=>[{}]});
  await settle();assert.equal(timers.size,0,'all sampling services must exist');
  removeProjection=local.provide('sessionProjections',{stateOf:()=>({active:[]})});
  await settle();await settle();
  assert.equal(timers.size,1);assert.equal(writes.length,1);
  assert.equal(writes.at(-1).idle,true);
  assert.equal(writes.at(-1).installedPlugins[0].state,'loaded','installed evidence reads the injected local Loader');
  assert.equal(writes.at(-1).plugins,null,'missing dynamic runner stays unknown');
  removeRunner=local.provide('dynamicCordisRunner',{listPlugins:current=>{
    assert.equal(current.id,'local-session');return [{pluginId:'dynamic',name:'local-plugin',activeRun:{}}];
  }});
  await settle();await tick();
  assert.equal(timers.size,1,'optional runner arrival does not duplicate heartbeat timers');
  assert.equal(writes.at(-1).plugins[0].sessionId,'local-session');
  await removeRunner();removeRunner=undefined;await tick();
  assert.equal(writes.at(-1).plugins,null,'runner withdrawal invalidates dynamic evidence');
  assert.equal(timers.size,1);
  // Withdraw while actual whenIdle() has an asynchronous sample in flight.
  let release;
  agent.whenIdle=()=>new Promise(resolve=>{release=resolve;});
  const pending=[...timers.values()][0]();
  await settle();const count=writes.length;
  await removeProjection();removeProjection=undefined;
  assert.equal(timers.size,0);assert.equal(routes.size,1);
  release();await pending;await settle();
  assert.equal(writes.length,count,'old sampler cannot publish after dependency withdrawal');
  agent.whenIdle=async()=>{};
  for(let cycle=0;cycle<3;cycle++){
    removeProjection=local.provide('sessionProjections',{stateOf:()=>({active:cycle===1?[{}]:[]})});
    await settle();await settle();
    assert.equal(timers.size,1);
    assert.equal(writes.at(-1).idle,cycle!==1,'unknown or active state cannot become idle');
    await removeProjection();removeProjection=undefined;
    assert.equal(timers.size,0);
  }
  assert.equal(routeRegistrations,1,'sampling churn must not remount independent route');
  assert.equal(maxTimers,1);
  await plugin.dispose();
  assert.equal(routes.size,0);assert.equal(timers.size,0);
} finally {
  await removeRunner?.();await removeProjection?.();await removeSessions?.();await removeAgents?.();
  await ctx.fiber.dispose();
}
testRuntime('raw');
fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,JSON.stringify({passed:true,scope:'Locked rc3 Cordis real Context/plugin/inject/isolation/disposal; production sampling and installed/dynamic helpers with Loader-service, FS and clock fixtures; no webpage marker or device claim',routeRegistrations,samplerAttachments:samplerContexts.length,maxTimers,writes:writes.length,rawContentRechecked:true},null,2));
console.log('PASS_APP_INTEGRATION_LIFECYCLE: route independence, real Cordis isolated scope, service arrival/withdrawal, pending-sample cancellation, single timer, cleanup and final raw byte proof');
