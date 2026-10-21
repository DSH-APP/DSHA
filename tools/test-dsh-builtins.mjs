// 新版消息工厂参与的设备引导回归；模拟准入与已提交事件，不调用模型。
import test from 'node:test';
import assert from 'node:assert/strict';
import { cp, readFile, mkdtemp, rm, mkdir, writeFile, symlink } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/dsh-alpha15/host-runtime');
const directory = resolve(runtime, 'node_modules/dsh-device-shell-guide');
if (!process.env.DSHA_TEST_GUIDE) {
  if (!runtime.startsWith(resolve('app/build'))) throw Error('测试运行时必须位于 app/build');
  await cp('app/src/main/assets/builtin-plugins/dsh-device-shell-guide', directory, {recursive:true});
}
const guide = await import(pathToFileURL(process.env.DSHA_TEST_GUIDE || resolve(directory, 'lib/index.js')));

function fixture(standard = false, history = []) {
  const handlers = new Map(), sections = [];
  guide.apply({on:(type,fn)=>handlers.set(type,fn),inject(names,apply){
    if (standard) apply({systemPrompt:{section:section=>sections.push(section)},effect:fn=>fn()});
  }});
  const session = { snapshotEvents:()=>history }, original = {kind:'enter', messages:[{id:'human'}]};
  return {sections, original, session, handlers,
    step: (decision=original, extra={})=>handlers.get('agent/pre-step')({agent:{session},step:1,signal:new AbortController().signal,...extra},async()=>decision)};
}
test('标准模式只注入一份系统引导', async()=>{
  const f=fixture(true); assert.equal(f.sections.length,1);
  assert.equal(await f.step(), f.original);
});
test('极简模式必须等消息提交后才判重，失败准入不会吞掉引导', async()=>{
  const f=fixture(); const first=await f.step();
  assert.equal(first.messages[0].role,'user'); assert.equal(first.messages[0].source.kind,'plugin');
  assert.equal(first.messages[0].source.plugin,guide.name); assert.ok(first.messages[0].id);
  assert.equal((await f.step()).messages.length,2);
  f.handlers.get('session/event')(f.session,{type:'user/message',data:first.messages[0]});
  assert.equal(await f.step(),f.original);
});
test('恢复会话、拒绝准入、取消步骤和非首步均不重复注入', async()=>{
  const restored=fixture(false,[{type:'user/message',data:{source:{kind:'plugin',plugin:guide.name}}}]);
  assert.equal(await restored.step(),restored.original);
  const f=fixture(), decision={kind:'reject'};
  assert.equal(await f.step(decision),decision);
  assert.equal(await f.step(f.original,{signal:{aborted:true}}),f.original);
  assert.equal(await f.step(f.original,{step:2}),f.original);
});


// Exercise the actual upstream admission policy. A signed wrapper is subject to
// exactly the same no-exemption version gate as a third-party bundle.
const runtimeRequire = createRequire(resolve(runtime, 'package.json'));
const { evaluatePluginCompatibility, getDshRuntimeVersion } = await import(
  pathToFileURL(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot')));
const builtinRegistry = JSON.parse(await readFile('app/src/main/assets/builtin-plugins.json','utf8'));
const officialLock = JSON.parse(await readFile('tools/dsh-runtime/package-lock.json','utf8'));
const officialManifest = JSON.parse(await readFile('tools/dsh-runtime/package.json','utf8'));
const runtimeVersion = getDshRuntimeVersion();
const bundledManifest = async plugin => JSON.parse(await readFile(plugin.name === 'dsh-app-integration'
  ? 'app/src/main/assets/app-integration/package.json'
  : resolve('app/src/main/assets/builtin-plugins', plugin.name, 'package.json'),'utf8'));
const migratedPeers = {
  'dsh-auto-review': ['@deepseek-ai/dsh-experimental-auto-review'],
  'dsh-computer-use-android': ['@deepseek-ai/dsh-computer-use','@deepseek-ai/dsh-mcp-client'],
  'dsh-device-shell-guide': ['@deepseek-ai/dsh-system-prompt','@deepseek-ai/dsh-llm'],
};
test('all signed builtin manifests pass the actual upstream version gate without exemptions',async()=>{
  assert.equal(runtimeVersion,officialManifest.dependencies['@deepseek-ai/dsh']);
  assert.equal(builtinRegistry.plugins.length,8);
  for (const plugin of builtinRegistry.plugins) {
    const manifest = await bundledManifest(plugin);
    assert.equal(evaluatePluginCompatibility(manifest,{},runtimeVersion),undefined,
      plugin.name+' must pass the unmodified upstream version gate');
  }
});
test('migrated exact peers match actual installed official modules and npm lock',async()=>{
  for (const [name,peers] of Object.entries(migratedPeers)) {
    const manifest = await bundledManifest({name});
    for (const peer of peers) {
      const installed=JSON.parse(await readFile(resolve(runtime,'node_modules',peer,'package.json'),'utf8'));
      assert.equal(installed.name,peer);
      assert.equal(installed.version,runtimeVersion);
      assert.equal(manifest.peerDependencies[peer],installed.version);
      assert.equal(officialManifest.dependencies[peer],installed.version);
      assert.equal(officialLock.packages['node_modules/'+peer].version,installed.version);
    }
  }
});
test('the upstream version gate still rejects each original stale wrapper declaration',async()=>{
  for (const [name,peers] of Object.entries(migratedPeers)) {
    const original=await bundledManifest({name});
    for(const peer of peers) original.peerDependencies[peer]='0.2.0-rc.2';
    const issue=evaluatePluginCompatibility(original,{},runtimeVersion);
    assert.equal(issue.name,name);assert.equal(issue.runtimeVersion,runtimeVersion);
    assert.equal(issue.exempted,false);
    assert.deepEqual(issue.requirements,Object.fromEntries(peers.map(peer=>[peer,'0.2.0-rc.2'])));
  }
});


test('all eight signed wrappers activate with real 0.2.1 services; actual MCP and Auto lifecycles dispose cleanly',async()=>{
  process.env.DSHA_BUILTIN_TEST_RUNTIME = runtime;
  await import('./fixtures/builtin-host-hooks.mjs');
  const load = async name => import(pathToFileURL(runtimeRequire.resolve(name)));
  const {Context} = await load('@deepseek-ai/cordis');
  const {default:SystemPrompt} = await load('@deepseek-ai/dsh-system-prompt');
  const {default:ToolRuntime} = await load('@deepseek-ai/dsh-tools');
  const {default:ComputerUse} = await load('@deepseek-ai/dsh-computer-use');
  const {default:Agents} = await load('@deepseek-ai/dsh-agent');
  const {default:Projections} = await load('@deepseek-ai/dsh-session-projection');
  const {default:Permissions} = await load('@deepseek-ai/dsh-permission-presets');
  const {default:LocalFS} = await load('@deepseek-ai/dsh-fs-local');
  const {default:WorkingDirectory} = await load('@deepseek-ai/dsh-working-directory');
  const {default:WebServer} = await load('@deepseek-ai/dsh-host-webserver');
  const {HostConnectionService:Connection} = await load('@deepseek-ai/dsh-client-connection');
  for (const method of ['register']) assert.equal(typeof WebServer.prototype[method],'function');
  for (const method of ['requestRejection','authorizeIndex']) assert.equal(typeof Connection.prototype[method],'function');
  const owned = await mkdtemp(resolve('app/build/builtin-runtime-activation-'));
  assert.ok(owned.startsWith(resolve('app/build')+sep),'owned cleanup must remain below app/build');
  const ctx = new Context(), routes = [], fibers = [];
  let llmRequests = 0;
  const oldGeneration = process.env.DSHA_WEB_GENERATION;
  delete process.env.DSHA_WEB_GENERATION; // no application activity file outside the owned fixture
  try {
    // Controlled host carriers own no socket, model credentials, or user Session.
    ctx.provide('webServer',{port:41821,register(route){routes.push(route);return()=>{routes.splice(routes.indexOf(route),1);};}});
    ctx.provide('connection',{requestRejection(){return 401;},authorizeIndex(_req,res){res.writeHead(401);res.end();return false;}});
    ctx.provide('sessions',{list(){return [];}});
    ctx.provide('shell',{sandboxMode:'danger-full-access'});
    ctx.provide('approval',{config:{policy:'never'},overrideOf(){return 'never';}});
    ctx.provide('llm',{stream(){llmRequests++;throw Error('No model request is permitted by this fixture');}});
    for (const [plugin,config] of [[SystemPrompt,{}],[ToolRuntime,{mode:'native'}],[ComputerUse,{}],
      [Agents,{}],[Projections,{}],[LocalFS,{cwd:owned}],[WorkingDirectory,{defaultDirectory:owned}],
      [Permissions,{defaultPreset:'danger-full-access'}]]) {
      const fiber=ctx.plugin(plugin,config);await fiber;assert.equal(fiber.state,2);fibers.push(fiber);
    }
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access');
    assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    const mounted = [];
    for (const plugin of builtinRegistry.plugins) {
      const filename = plugin.name==='dsh-app-integration' ? resolve('app/src/main/assets/app-integration/index.js')
        : resolve('app/src/main/assets/builtin-plugins',plugin.name,plugin.entrypoint);
      const module = await import(pathToFileURL(filename));
      assert.equal(typeof module.apply,'function',plugin.name+' apply export');
      const fiber=ctx.plugin(module);await fiber;assert.equal(fiber.state,2,plugin.name+' must activate');mounted.push(fiber);
    }
    // The wrapper's cordis.patch inserts this actual official integration.
    const auto=ctx.plugin(await load('@deepseek-ai/dsh-experimental-auto-review'));await auto;
    assert.equal(auto.state,2);assert.equal(ctx.permissionPresets.names.includes('auto'),true);
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access','Auto is offered, never selected by this bundle');
    let next=0;
    const passthrough=await ctx.waterfall(null,'tools/pre-execute',{name:'fixture',signal:new AbortController().signal},async()=>{next++;return {kind:'allow'};});
    assert.equal(passthrough.kind,'allow');assert.equal(next,1);assert.equal(llmRequests,0);
    const schemas=ctx.tools.schemas().map(tool=>tool.name);
    assert.equal(schemas.filter(name=>name.startsWith('mcp__dsha-android__')).length,6);
    assert.equal(schemas.filter(name=>name.startsWith('mcp__dsha-vscreen__')).length,15);
    assert.equal(ctx.computerUse.providerName,'dsha-android');
    const assembly=await ctx.systemPrompt.assemble();
    assert.ok(assembly.sections.some(section=>section.name==='dsh:device-shell-guide'));
    assert.ok(routes.some(route=>route.path==='/api/mobile-nav.session.delete'));
    const restore=routes.find(route=>route.path==='/__dsha/browser-origin-restore');assert.ok(restore);
    let denied;
    await restore.handler({method:'GET'},{writeHead(code){denied=code;},end(){}});assert.equal(denied,401);
    await auto.dispose();assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    for (const fiber of mounted.reverse()) await fiber.dispose();
    assert.equal(ctx.tools.schemas().length,0);assert.equal(ctx.computerUse.providerName,undefined);
    assert.equal(routes.length,0);assert.equal(llmRequests,0);
  } finally {
    await ctx.fiber.dispose();
    if(oldGeneration===undefined)delete process.env.DSHA_WEB_GENERATION;else process.env.DSHA_WEB_GENERATION=oldGeneration;
    delete process.env.DSHA_BUILTIN_TEST_RUNTIME;
    await rm(owned,{recursive:true,force:true});
  }
});



test('status overlay actual apply forwards current stream and tool events through bounded authenticated bridge IO',async()=>{
  const {default:fs} = await import('node:fs');
  const {syncBuiltinESMExports} = await import('node:module');
  const {Context} = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
  const originalRead = fs.readFileSync, originalFetch = globalThis.fetch;
  const traffic = []; let tokenReads=0;
  const ctx = new Context();
  try {
    // Only the guest token read and network boundary are controlled. The actual
    // plugin body, timers, event callbacks, aggregation and request construction run.
    fs.readFileSync = function(file,...args) {
      if(file === '/root/.dsh/.bridge_token'){tokenReads++;return 'owned-fixture-token';}
      return originalRead.call(this,file,...args);
    };
    syncBuiltinESMExports();
    globalThis.fetch = async (input,options) => {
      const url=new URL(input);
      assert.equal(url.origin,'http://127.0.0.1:3090');
      assert.ok(['/app/overlay','/app/plugins'].includes(url.pathname));
      assert.equal(url.searchParams.has('token'),false);
      assert.equal(options.headers['X-Token'],'owned-fixture-token');
      assert.ok(options.signal instanceof AbortSignal);
      traffic.push(url);
      return new Response(JSON.stringify({result:'OK'}));
    };
    const overlay=await import(pathToFileURL(resolve('app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js')).href+'?builtin-activation-stream');
    const mounted=ctx.plugin(overlay);await mounted;assert.equal(mounted.state,2);
    const session={id:'owned-overlay-session'};
    ctx.emit('session/event',session,{type:'turn/start',data:{}});
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'start'}});
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'chunk',chunk:{type:'reasoning-delta',text:'fixture thought'}}});
    await new Promise(done=>setTimeout(done,160));
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'chunk',chunk:{type:'text-delta',text:'fixture reply'}}});
    await new Promise(done=>setTimeout(done,160));
    ctx.emit('session/event',session,{type:'tool/call',data:{name:'bash',arguments:JSON.stringify({command:'printf fixture'})}});
    ctx.emit('session/event',session,{type:'turn/end',data:{reason:{kind:'completed'}}});
    await new Promise(done=>setImmediate(done));
    const overlayRequests=traffic.filter(url=>url.pathname==='/app/overlay');
    for(const [kind,text] of [['reasoning','fixture thought'],['text','fixture reply'],['tool','printf fixture']])
      assert.ok(overlayRequests.some(url=>url.searchParams.get('kind')===kind&&url.searchParams.get('text').includes(text)),kind+' current rc2 event must forward');
    assert.ok(overlayRequests.some(url=>url.searchParams.get('kind')==='done'));
    assert.ok(tokenReads>0);await mounted.dispose();
  } finally {
    await ctx.fiber.dispose();fs.readFileSync=originalRead;syncBuiltinESMExports();globalThis.fetch=originalFetch;
  }
});


test('actual official base/Web and all signed bundle patches compose with every intended plugin enabled',async()=>{
  const {loadProfileDirectory,composeEntries} = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot')));
  const owned=await mkdtemp(resolve('app/build/builtin-profile-composition-'));
  assert.ok(owned.startsWith(resolve('app/build')+sep),'owned cleanup must remain below app/build');
  try {
    await mkdir(resolve(owned,'node_modules'));
    for(const plugin of builtinRegistry.plugins) {
      const directory=plugin.name==='dsh-app-integration' ? resolve('app/src/main/assets/app-integration')
        : resolve('app/src/main/assets/builtin-plugins',plugin.name);
      await symlink(directory,resolve(owned,'node_modules',plugin.name),process.platform==='win32'?'junction':'dir');
    }
    await writeFile(resolve(owned,'package.json'),JSON.stringify({name:'owned-builtin-activation-profile',version:'1.0.0',
      dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app',...builtinRegistry.plugins.map(plugin=>plugin.name)]}}}));
    await writeFile(resolve(owned,'cordis.patch.yml'),'[]\n');
    const profile=loadProfileDirectory('dsh',owned,resolve(runtime,'node_modules/@deepseek-ai/dsh/package.json'));
    assert.deepEqual(profile.skippedBundles,[]);assert.equal(profile.layers.length,10);
    const entries=composeEntries([...profile.layers.map(layer=>layer.patches),profile.patches]);
    for(const name of ['dsh-device-shell-guide','dsh-task-notifier','dsh-status-overlay','dsh-web-mobile',
      '@deepseek-ai/dsh-computer-use','dsh-computer-use-android','@deepseek-ai/dsh-experimental-auto-review',
      'dsh-tool-vscreen','dsh-app-integration']) {
      const rows=entries.filter(row=>row.name===name);assert.equal(rows.length,1,name+' must be composed exactly once');
      assert.notEqual(rows[0].disabled,true,name+' must not be accidentally disabled');
    }
    for(const name of ['@deepseek-ai/dsh-working-directory','@deepseek-ai/dsh-permission-presets',
      '@deepseek-ai/dsh-agent','@deepseek-ai/dsh-session-projection','@deepseek-ai/dsh-tools',
      '@deepseek-ai/dsh-host-webserver','@deepseek-ai/dsh-client-connection'])
      assert.ok(entries.some(row=>row.name===name),name+' required service provider must be in official profile');
  } finally {await rm(owned,{recursive:true,force:true});}
});
