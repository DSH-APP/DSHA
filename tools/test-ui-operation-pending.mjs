// Real pinned rc2 browser Controllers, snapshot stores, React and controls.
// Only the asynchronous remote transport is controlled. No APK or device data.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';
const args = process.argv.slice(2);
const before = args.includes('--before');
const stalePresetNegative = args.includes('--stale-preset-negative');
const duplicatePluginNegative = args.includes('--duplicate-plugin-negative');
const presetLayoutNegative = args.includes('--preset-layout-negative');
const surface = args.find(value => value.startsWith('--surface='))?.slice(10) || 'all';
assert(['all','plugin','preset','session'].includes(surface));
assert(args.every(value => value === '--before' || value === '--stale-preset-negative' || value === '--duplicate-plugin-negative' || value === '--preset-layout-negative' || value.startsWith('--surface=')));
assert(!stalePresetNegative || !before && (surface === 'preset' || surface === 'all'));
assert(!duplicatePluginNegative || !before && (surface === 'plugin' || surface === 'all'));
assert(!presetLayoutNegative || !before && (surface === 'preset' || surface === 'all'));
const runtime = testRuntime('raw');
const assets = path.resolve('app/src/main/assets');
const recipeInputs = {};
const recipe = name => {const bytes=fs.readFileSync(path.join(assets,name));recipeInputs[name]=createHash('sha256').update(bytes).digest('hex');return JSON.parse(bytes);};
const recipes = {plugin:recipe('plugin-manager-pending-patch.json'),preset:recipe('agent-preset-pending-patch.json'),session:recipe('session-interaction-patch.json')};
if (stalePresetNegative) {
  // Explicit negative control of the real recipe: restore only the V1
  // pre-write in-flight reuse, retaining all other feedback and errors.
  const boundary = '                    const previousRead = this.loading;\n                    if (previousRead !== void 0) await previousRead;\n';
  let removed = 0;
  for (const patch of recipes.preset.patches) {
    if (patch.after.includes(boundary)) { assert.equal(patch.after.split(boundary).length,2);patch.after=patch.after.replace(boundary,'');removed++; }
  }
  assert.equal(removed,1,'fresh-read negative control must remove one real boundary');
}
if (duplicatePluginNegative) {
  // Keep real pending/error behavior, but restore only the previous eager
  // read + unconditional completion load to measure the duplicate round.
  let readRestored=0,runRestored=0;
  for(const patch of recipes.plugin.patches) {
    if(patch.after.includes('DSHA_PLUGIN_REFRESH_BARRIER_V1')) {patch.after=patch.before;readRestored++;}
    if(patch.after.includes('DSHA_PLUGIN_PENDING_V2')) {
      const registration='                const mutation = Promise.withResolvers();\n                ++this.dshaMutationSerial;\n                this.dshaPendingMutations.add(mutation.promise);\n';
      const release='                    this.dshaPendingMutations.delete(mutation.promise);\n                    mutation.resolve();\n';
      for(const boundary of [registration,release]) assert.equal(patch.after.split(boundary).length,2);
      patch.after=patch.after.replace(registration,'').replace(release,'').replace('await this.dshaConfirmMutation();','await this.load();');
      runRestored++;
    }
  }
  assert.equal(readRestored,1);assert.equal(runRestored,1);
}
if(presetLayoutNegative) {
  // Restore only the prior extra paragraph, retaining the exact same
  // persistence/verification/session synchronization implementation.
  let restored=0;
  for(const patch of recipes.preset.patches) if(patch.after.includes('DSHA_PRESET_PENDING_VIEW_V2')) {patch.after=patch.before;restored++;}
  assert.equal(restored,1);
  const anchor='\t\t\t\t\tstate.error === null ? null : (0, react_jsx_runtime.jsx)("p", {';
  recipes.preset.patches.push({before:anchor,after:`
                    state.saving ? (0, react_jsx_runtime.jsx)("p", {
                        role: "status", "aria-live": "polite", "data-dsha-preset-pending": state.savePhase,
                        children: t(state.savePhase === "syncing" ? "presetSyncing" : state.savePhase === "confirming" ? "presetConfirming" : "presetSaving")
                    }) : null,
${anchor}`});
}
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
for (const name of ['plugin','preset']) {
  const row = recipes[name];
  const source = fs.readFileSync(path.join(runtime,'node_modules',row.module),'utf8');
  assert.equal(sha(Buffer.from(source)),row.sourceSha256,'official browser source');
  const lock = JSON.parse(fs.readFileSync('tools/dsh-runtime/package-lock.json','utf8'));
  assert.equal(row.packageIntegrity,lock.packages['node_modules/'+row.module.split('/lib/')[0]].integrity);
  let changed=source;
  for(const patch of row.patches){assert.equal(changed.split(patch.before).length,2);changed=changed.replace(patch.before,patch.after);}
  new vm.Script(changed);
}
const fixture = await browserFixture(runtime);
const page = fixture.page;
// browserFixture supplies the pinned React/Primitives JS. Geometry also
// requires the same frontend's emitted stylesheets, including Tag/Switch.
const frontend = path.join(runtime,'node_modules/@deepseek-ai/dsh-web-frontend/dist');
const stylesheets=[...fs.readFileSync(path.join(frontend,'index.html'),'utf8').matchAll(/<link\b[^>]*rel="stylesheet"[^>]*href="\.\/([^\"]+)"/g)].map(match=>match[1]);
assert(stylesheets.length>0,'the pinned frontend must declare its real CSS');
const results=[];
try {
for(const stylesheet of stylesheets) {
  recipeInputs['frontend/'+stylesheet]=createHash('sha256').update(fs.readFileSync(path.join(frontend,stylesheet))).digest('hex');
  await page.addStyleTag({url:'/'+stylesheet});
}
await page.evaluate(()=>document.fonts.ready);
  await page.evaluate(() => { window.unhandled=[];addEventListener('unhandledrejection',event=>unhandled.push(String(event.reason))); });
  if (surface==='plugin'||surface==='all') {
    await fixture.load('dsh-client-ui-plugin-manager',before?[]:[recipes.plugin],
      'exports.audit={PluginManagerController,EnableSwitch,RowSwitch,PackageCard,PluginRefreshToast,en,zh};');
    await page.evaluate(() => {
      const api=auditExports['@deepseek-ai/dsh-client-ui-plugin-manager'].audit;
      const React=auditModules.react;
      const translate=(key,values={})=>Object.entries(values).reduce((text,[name,value])=>text.replaceAll('{'+name+'}',value),api.en[key]??key);
      window.setupPlugin=({enabled=false,readOnly=false,holdBundles=false}={})=>{
        window.testRoot?.unmount();window.manager?.dispose();
        window.mutations=[];window.reads=[];window.hostEnabled=enabled;window.inventoryReads=0;window.bundleReads=0;window.pluginReads=0;window.bundleReplies=[];window.holdBundles=holdBundles;
        const staticStore={getSnapshot:()=>[],subscribe:()=>()=>{}};
        const ctx={get:()=>undefined,configForms:{describe:()=>staticStore},remote:{
          pluginInventory:{list:()=>{++inventoryReads;return new Promise((resolve,reject)=>reads.push({resolve,reject}));}},
          pluginManager:{
            setBundleEnabled:(name,value)=>new Promise((resolve,reject)=>mutations.push({name,value,resolve,reject})),
            listBundles:async()=>{++bundleReads;const result={ok:true,value:[{...window.baseBundle,enabled:hostEnabled}]};return window.holdBundles?await new Promise(resolve=>bundleReplies.push(()=>resolve(result))):result;},
            listPlugins:async()=>{++pluginReads;return {ok:true,value:[]};}
          }}};
        window.baseBundle={name:'@deepseek-ai/dsh-experimental-fixture',meta:{title:'A long plugin title that remains readable without moving its switch'},enabled,official:true,availability:'installation',installed:true,optional:true,removable:false,rows:[],...(readOnly?{readOnlyReason:'management-required'}:{})};
        window.manager=new api.PluginManagerController(ctx);
        manager.patch({status:'ready',packages:[baseBundle]});
        window.face=manager.inject(staticStore,staticStore,text=>text);
        testRoot=auditModules['react-dom/client'].createRoot(document.getElementById('root'));
        function Demo(){const state=React.useSyncExternalStore(manager.store.subscribe,manager.store.getSnapshot);
          return React.createElement(React.Fragment,null,
            React.createElement('ul',{style:{listStyle:'none',padding:0,margin:0}},React.createElement(api.PackageCard,{pkg:state.packages[0],t:translate,resolveText:text=>text,busy:state.busy.includes(baseBundle.name),onOpen:()=>{},onSetEnabled:value=>face.setEnabled(baseBundle.name,value)})),
            React.createElement(api.PluginRefreshToast,{usePluginManager:selector=>selector(state),dismissNotice:()=>{},t:translate}));}
        testRoot.render(React.createElement(Demo));
      };
      window.finishMutation=(application='applied',index=mutations.length-1)=>{
        const call=mutations[index];
        if(application==='applied'||application==='restart-required')hostEnabled=call.value;
        call.resolve({ok:true,value:{application,changed:true,...(application==='failed'?{error:{code:'operation-error',diagnostic:'fixture refused'}}:{}),...(application==='restart-required'?{restartReason:'no-hmr'}:{})}});
      };
      window.releaseRead=()=>{const call=reads.shift();call.resolve({ok:true,value:{managementAvailable:true}});};
    });
    await page.evaluate(()=>setupPlugin());
    const toggle=page.getByRole('switch');await toggle.waitFor();
    const initialBox=await toggle.boundingBox(),initialCard=await page.locator('[data-plugin-package]').boundingBox();
    await toggle.click();
    await page.waitForFunction(()=>mutations.length===1);
    assert.equal(await toggle.getAttribute('aria-checked'),'false','no optimistic enabled');
    assert.equal(await toggle.isDisabled(),true);
    assert.match(await page.locator('#root').innerText(),/Working/,'visible mutation feedback');
    assert.equal(await page.locator('[role=status]').getAttribute('aria-label'),'Applying change and checking status…');
    const bounds=await toggle.boundingBox();assert(bounds.x>=0 && bounds.x+bounds.width<=360,'pending control fits a mobile viewport');
    assert.deepEqual(bounds,initialBox,'pending text cannot move the switch');
    assert.deepEqual(await page.locator('[data-plugin-package]').boundingBox(),initialCard,'pending text cannot change the row height');
    await page.evaluate(()=>face.setEnabled(baseBundle.name,true));
    assert.equal(await page.evaluate(()=>mutations.length),1,'same-key duplicate mutation');
    await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>reads.length===1);
    assert.equal(await toggle.isDisabled(),true,'busy must cover authoritative refresh');
    assert.equal(await toggle.getAttribute('aria-checked'),'false');
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.equal(await toggle.getAttribute('aria-checked'),'true');
    assert.deepEqual(await page.evaluate(()=>[inventoryReads,bundleReads,pluginReads]),[1,1,1],'no-change-event path still performs one real read');
    results.push('plugin: real click, visible pending, one mutation, no optimistic checked, busy covers confirmation');
    // A changed notification can precede its unary response on an independent
    // stream. Queue the read, then make exactly one complete post-write round.
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
    await page.evaluate(()=>{window.changedRead=manager.load();});
    await page.evaluate(()=>finishMutation());
    for(let round=0;round<4;round++) {
      await page.waitForFunction(()=>reads.length>0 || manager.getSnapshot().busy.length===0);
      if(await page.evaluate(()=>manager.getSnapshot().busy.length===0))break;
      await page.evaluate(()=>releaseRead());
    }
    await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    const singleRound=await page.evaluate(()=>({inventory:inventoryReads,bundles:bundleReads,plugins:pluginReads}));
    console.log(JSON.stringify({scenario:'changed-before-unary-settlement',negative:duplicatePluginNegative,requests:singleRound}));
    assert.deepEqual(singleRound,{inventory:1,bundles:1,plugins:1},'one post-write round must satisfy both notification and completion');
    results.push('plugin: changed plus completion share one actual post-write three-RPC read');
    // A new external invalidation during a real RPC remains a new freshness
    // request and must rerun. It cannot be merged into an older snapshot.
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
    await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>reads.length===1);
    await page.evaluate(()=>{window.externalRead=manager.load();});
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>reads.length===1);
    assert.equal(await toggle.isDisabled(),true,'busy covers the freshness rerun');
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.equal(await page.evaluate(()=>inventoryReads),2);
    assert.equal(await page.evaluate(()=>bundleReads),2);assert.equal(await page.evaluate(()=>pluginReads),2);
    await page.evaluate(()=>setupPlugin({holdBundles:true}));await toggle.waitFor();await toggle.click();
    await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());
    await page.waitForFunction(()=>bundleReplies.length===1);
    await page.evaluate(()=>{window.externalRead=manager.load();holdBundles=false;bundleReplies.shift()();});
    await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.deepEqual(await page.evaluate(()=>[inventoryReads,bundleReads,pluginReads]),[2,2,2]);
    results.push('plugin: external invalidation during inventory or bundles preserves the complete freshness rerun');
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();
    await page.evaluate(()=>{window.preMutationRead=manager.load();});await page.waitForFunction(()=>reads.length===1);
    await toggle.click();await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>manager.rerun===true);
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>reads.length===1);
    assert.equal(await toggle.isDisabled(),true,'a read started before this mutation cannot confirm it');
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.deepEqual(await page.evaluate(()=>[inventoryReads,bundleReads,pluginReads]),[2,2,2]);
    results.push('plugin: a genuinely pre-mutation read must be followed by a new confirmation round');
    // Two different keys share one read only after BOTH mutation RPCs settle.
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
    await page.evaluate(()=>{face.setEnabled('second-key',true);window.changedRead=manager.load();});
    await page.waitForFunction(()=>mutations.length===2);
    assert.equal(await page.evaluate(()=>inventoryReads),0,'no read starts while either mutation is unresolved');
    await page.evaluate(()=>finishMutation('applied',1));
    assert.equal(await page.evaluate(()=>inventoryReads),0);
    await page.evaluate(()=>finishMutation('applied',0));await page.waitForFunction(()=>reads.length===1);
    await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.deepEqual(await page.evaluate(()=>[inventoryReads,bundleReads,pluginReads]),[1,1,1]);
    assert.equal(await page.evaluate(()=>manager.dshaPendingMutations.size),0);
    results.push('plugin: concurrent different-key mutations share one post-settlement read without self-wait');
    await page.evaluate(()=>setupPlugin());
    await page.evaluate(()=>{window.syncThrow=manager.run('sync-throw',{packageName:'sync-throw',action:'enable'},()=>{throw new Error('fixture synchronous failure');});});
    await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());await page.evaluate(()=>syncThrow);
    assert.equal(await page.evaluate(()=>manager.dshaPendingMutations.size),0);assert.equal(await page.evaluate(()=>manager.getSnapshot().notice.kind),'failed');
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
    await page.evaluate(()=>{window.changedRead=manager.load();mutations[0].reject(new Error('fixture disconnected unary'));});
    await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.equal(await page.evaluate(()=>manager.dshaPendingMutations.size),0);assert.equal(await page.evaluate(()=>manager.getSnapshot().notice.kind),'failed');
    results.push('plugin: synchronous throw and disconnected unary release the mutation-only fence');
    await page.setViewportSize({width:280,height:800});await page.evaluate(()=>setupPlugin());await toggle.waitFor();
    const narrowBefore=await toggle.boundingBox(),narrowCard=await page.locator('[data-plugin-package]').boundingBox();
    await toggle.tap();await page.getByText('Working…',{exact:true}).waitFor();
    assert.deepEqual(await toggle.boundingBox(),narrowBefore);assert.deepEqual(await page.locator('[data-plugin-package]').boundingBox(),narrowCard);
    const note=await page.locator('[role=status]').boundingBox();assert(note.x>=0&&note.x+note.width<=280&&note.y>=narrowCard.y,'short status stays inside the narrow card');
    await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    await page.setViewportSize({width:360,height:800});
    results.push('plugin: real narrow-screen touch keeps switch coordinates and row height unchanged');
    for(const application of ['failed','cancelled','overridden','restart-required']) {
      await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
      await page.evaluate(value=>finishMutation(value),application);await page.waitForFunction(()=>reads.length===1);
      assert.equal(await toggle.isDisabled(),true);
      await page.evaluate(()=>releaseRead());await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
      assert.equal(await toggle.getAttribute('aria-checked'),application==='restart-required'?'true':'false');
      const notice=await page.evaluate(()=>manager.getSnapshot().notice?.kind);
      assert.equal(notice,({failed:'failed',cancelled:'cancelled',overridden:'overridden','restart-required':'restart'})[application]);
    }
    results.push('plugin: failed/cancelled/overridden/restart-required preserve exact outcome');
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();await page.evaluate(()=>finishMutation());await page.waitForFunction(()=>reads.length===1);
    await page.evaluate(()=>reads.shift().reject(new Error('fixture transport offline')));
    await page.waitForFunction(()=>manager.getSnapshot().busy.length===0);
    assert.equal(await toggle.getAttribute('aria-checked'),'false');
    assert.equal(await page.evaluate(()=>manager.getSnapshot().notice.kind),'refresh-failed');
    await page.getByText('Refresh failed. Please try again.',{exact:true}).waitFor();
    await page.evaluate(()=>{window.retry=manager.load();});await page.waitForFunction(()=>reads.length===1);await page.evaluate(()=>releaseRead());
    await page.waitForFunction(()=>manager.getSnapshot().packages[0].enabled===true);
    results.push('plugin: rejected verification is unconfirmed, releases busy, explicit retry can recover');
    await page.evaluate(()=>setupPlugin({readOnly:true}));await toggle.waitFor();assert.equal(await toggle.isDisabled(),true);
    assert.equal(await page.locator('[role=status]').count(),0);
    await page.evaluate(()=>setupPlugin());await toggle.waitFor();await toggle.click();
    await page.evaluate(async()=>{window.changedRead=manager.load();manager.dispose();finishMutation();await changedRead;});
    assert.equal(await page.evaluate(()=>reads.length),0,'disposed owner does not start confirmation');
    assert.equal(await page.evaluate(()=>manager.dshaPendingMutations.size),0);
    results.push('plugin: readonly is not pending; disposed owner does not publish/start late refresh');
  }
  if(surface==='preset'||surface==='all') {
    await fixture.load('dsh-client-ui-agent-preset',before?[]:[recipes.preset],
      'exports.audit={AgentPresetSectionController,AgentPresetSection,AgentPresetSeatController,en,zh};');
    await page.evaluate(()=>{
      const api=auditExports['@deepseek-ai/dsh-client-ui-agent-preset'].audit;
      const React=auditModules.react;
      const t=(key,values={})=>Object.entries(values).reduce((text,[name,value])=>text.replaceAll('{'+name+'}',value),api[window.presetLanguage||'en'][key]??key);
      window.setupPreset=({language='en'}={})=>{
        window.presetLanguage=language;
        window.testRoot?.unmount();window.writes=[];window.rosters=[];window.syncs=[];window.hostDefault='standard';window.firstRoster=true;
        const ctx={locale:{bind:()=>t},remote:{settings:{update:(...args)=>new Promise(resolve=>writes.push({args,resolve}))},agentPresets:{list:()=>{
          if(firstRoster){firstRoster=false;return Promise.resolve({ok:true,value:{presets:window.presetRows()}});}
          const presets=window.presetRows();
          return new Promise((resolve,reject)=>rosters.push({resolve,reject,presets}));
        }}}};
        window.presetRows=()=>['standard','ptc'].map(id=>({id,name:id,isDefault:id===hostDefault}));
        window.section=new api.AgentPresetSectionController(ctx);
        window.loadSection=()=>section.load();
        const synchronize=id=>new Promise(resolve=>syncs.push({id,resolve}));
        testRoot=auditModules['react-dom/client'].createRoot(document.getElementById('root'));
        function Demo(){return React.createElement('div',{id:'preset-scroll',style:{height:'560px',overflowY:'auto'}},React.createElement(api.AgentPresetSection,{
          useAgentPresetSection:selector=>selector(React.useSyncExternalStore(section.store.subscribe,section.store.getSnapshot)),
          useDeveloperTools:selector=>selector(true),load:loadSection,view:()=>{},closeView:()=>{},close:()=>{},
          makeDefault:id=>{window.save=section.makeDefault(id,synchronize);},t}),React.createElement('div',{style:{height:'400px'},'aria-hidden':true}));}
        testRoot.render(React.createElement(Demo));
      };
      window.finishWrite=(ok=true)=>{if(ok)hostDefault='ptc';writes[0].resolve(ok?{ok:true,value:undefined}:{ok:false,error:{message:'fixture write refused'}});};
      window.finishRoster=(ok=true)=>{const call=rosters.shift();call.resolve(ok?{ok:true,value:{presets:call.presets}}:{ok:false,error:{message:'fixture roster unavailable'}});};
    });
    const pick=page.locator('[data-agent-preset-id=ptc] > button');
    if(!before) {
      for(const width of [280,360]) for(const language of ['en','zh']) {
        await page.setViewportSize({width,height:800});await page.evaluate(language=>setupPreset({language}),language);await pick.waitFor();
        await page.evaluate(()=>{document.getElementById('preset-scroll').scrollTop=32;});await pick.scrollIntoViewIfNeeded();
        const geometry=()=>page.evaluate(()=>{
          const rect=element=>{const {x,y,width,height}=element.getBoundingClientRect();return {x,y,width,height};};
          return {cards:[...document.querySelectorAll('[data-agent-preset-id]')].map(rect),scrollTop:document.getElementById('preset-scroll').scrollTop,windowY:window.scrollY};
        });
        const normal=await geometry();await pick.tap();await page.locator('[data-dsha-preset-pending=saving]').waitFor();
        const saving=await geometry();
        if(presetLayoutNegative)console.log(JSON.stringify({scenario:'preset-pending-card-layout',width,language,normal,saving}));
        assert.deepEqual(saving,normal,'pending cannot move preset cards or the scroll position');
        assert.equal(await page.locator('[data-dsha-preset-pending]').getAttribute('aria-live'),'polite');
        assert.equal(await pick.getAttribute('aria-pressed'),'false');
        await page.evaluate(()=>finishWrite());await page.waitForFunction(()=>rosters.length===1);
        const confirming=await geometry();assert.deepEqual(confirming,normal,'confirmation cannot move preset cards');
        await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>syncs.length===1);
        const syncing=await geometry();assert.deepEqual(syncing,normal,'blank-task synchronization cannot move preset cards');
        const textFits=await page.locator('[data-dsha-preset-intro]').evaluate(element=>element.scrollHeight<=element.clientHeight+1);
        assert.equal(textFits,true,'full pending copy fits the preserved intro footprint');
        await page.evaluate(()=>syncs[0].resolve(undefined));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
        const complete=await geometry();assert.deepEqual(complete,normal,'completion cannot move preset cards');
        assert.equal(await page.locator('[data-dsha-preset-pending]').count(),0);
        assert.equal(await page.locator('[data-dsha-preset-intro] > span').getAttribute('aria-hidden'),'false');
        console.log(JSON.stringify({scenario:'preset-pending-card-layout',width,language,normal,saving,confirming,syncing,complete,textFits}));
      }
      await page.setViewportSize({width:360,height:800});
      results.push('preset: 280/360px English/Chinese actual touch keeps card rectangles and scroll position stable through every phase');
    }
    await page.evaluate(()=>setupPreset());await pick.waitFor();await pick.click();
    await page.waitForFunction(()=>writes.length===1);
    assert.match(await page.locator('#root').innerText(),/Saving new task default/,'visible default persistence feedback');
    assert.equal(await pick.getAttribute('aria-pressed'),'false');assert.equal(await pick.isDisabled(),true);
    await page.evaluate(()=>section.makeDefault('ptc'));assert.equal(await page.evaluate(()=>writes.length),1);
    await page.evaluate(()=>finishWrite());await page.waitForFunction(()=>rosters.length===1);
    assert.match(await page.locator('#root').innerText(),/Checking the saved default/);
    assert.equal(await pick.getAttribute('aria-pressed'),'false');
    await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>syncs.length===1);
    assert.match(await page.locator('#root').innerText(),/Updating the current blank task/);
    assert.equal(await pick.getAttribute('aria-pressed'),'true','only verified default is marked');
    await page.evaluate(()=>syncs[0].resolve(undefined));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.equal(await page.locator('[data-dsha-preset-pending]').count(),0);
    results.push('preset: saving/verification/blank-task stages are visible, default is marked after real read');
    await page.evaluate(()=>setupPreset());await pick.waitFor();await pick.click();await page.evaluate(()=>finishWrite(false));await page.waitForFunction(()=>rosters.length===1);await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.equal(await page.evaluate(()=>syncs.length),0);assert.match(await page.locator('[role=alert]').innerText(),/write refused/);
    await page.evaluate(()=>setupPreset());await pick.waitFor();await pick.click();await page.evaluate(()=>finishWrite());await page.waitForFunction(()=>rosters.length===1);await page.evaluate(()=>finishRoster(false));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.equal(await page.evaluate(()=>syncs.length),0,'failed roster cannot synchronize stale default');assert.match(await page.locator('[role=alert]').innerText(),/roster unavailable/);
    results.push('preset: write/read failures never synchronize stale/default state');
    await page.evaluate(()=>setupPreset());await pick.waitFor();await pick.click();await page.evaluate(()=>finishWrite());await page.waitForFunction(()=>rosters.length===1);await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>syncs.length===1);await page.evaluate(()=>syncs[0].resolve('fixture session locked'));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.match(await page.locator('[role=alert]').innerText(),/default was saved.*current blank task could not switch.*fixture session locked/);
    results.push('preset: saved default is distinguished from refused blank-task recomposition');
    // Reproduce a real notification read started before the settings write:
    // its response is old even though it arrives after the write commits.
    await page.evaluate(()=>setupPreset());await pick.waitFor();
    await page.evaluate(()=>{window.preWriteRead=section.load();});await page.waitForFunction(()=>rosters.length===1);
    await pick.click();await page.evaluate(()=>finishWrite());
    await page.waitForFunction(()=>section.store.getSnapshot().savePhase==='confirming');
    await page.evaluate(()=>finishRoster());
    await page.waitForFunction(()=>rosters.length===1 || syncs.length>0 || !section.store.getSnapshot().saving);
    assert.equal(await page.evaluate(()=>syncs.length),0,'a pre-write roster must never synchronize its stale default');
    assert.equal(await page.evaluate(()=>section.store.getSnapshot().saving),true,'confirmation remains pending until a post-write read');
    assert.equal(await pick.getAttribute('aria-pressed'),'false');
    // Another notification may share the explicitly post-write read; it is
    // not cleared or ignored in order to force the confirmation request.
    await page.evaluate(()=>{window.postWriteNotificationRead=section.load();});
    assert.equal(await page.evaluate(()=>rosters.length),1);
    await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>syncs.length===1);
    assert.equal(await page.evaluate(()=>syncs[0].id),'ptc');
    await page.evaluate(()=>syncs[0].resolve(undefined));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.equal(await pick.getAttribute('aria-pressed'),'true');
    results.push('preset: pre-write in-flight roster cannot confirm a new default; a post-write read is required');
    await page.evaluate(()=>setupPreset());await pick.waitFor();
    await page.evaluate(()=>{window.preWriteRead=section.load();});await page.waitForFunction(()=>rosters.length===1);
    await pick.click();await page.evaluate(()=>finishWrite());await page.waitForFunction(()=>section.store.getSnapshot().savePhase==='confirming');
    await page.evaluate(()=>finishRoster());await page.waitForFunction(()=>rosters.length===1);
    await page.evaluate(()=>finishRoster(false));await page.waitForFunction(()=>!section.store.getSnapshot().saving);
    assert.equal(await page.evaluate(()=>hostDefault),'ptc','the successful write is retained');
    assert.equal(await page.evaluate(()=>syncs.length),0,'failed post-write verification must not synchronize retained standard');
    assert.match(await page.locator('[role=alert]').innerText(),/roster unavailable/);
    results.push('preset: failed post-write read preserves the write and never synchronizes retained old rows');
    const seatBoundary=await page.evaluate(async()=>{
      const {AgentPresetSeatController}=auditExports['@deepseek-ai/dsh-client-ui-agent-preset'].audit;
      let session={id:'captured',blank:true,projectionValues:{agentPreset:'standard'}}, calls=[];
      const seat=new AgentPresetSeatController({remote:{agentPresets:{select:async(id,preset)=>{calls.push([id,preset]);return {ok:true,value:preset};}}}},()=>session,{id:undefined,introduce:false});
      let release;seat.pendingSelection=new Promise(resolve=>{release=resolve;});
      const oldSync=seat.syncBlankSession('captured','ptc');
      session={id:'new-session',blank:true,projectionValues:{agentPreset:'standard'}};
      seat.pendingSelection=undefined;release();await oldSync;
      const afterChanged=calls.length;
      session={id:'captured',blank:false,projectionValues:{agentPreset:'standard'}};
      await seat.syncBlankSession('captured','ptc');const afterStarted=calls.length;
      session={id:'captured',blank:true,projectionValues:{agentPreset:'standard'}};
      await seat.syncBlankSession('captured','ptc');
      return {afterChanged,afterStarted,calls};
    });
    assert.deepEqual(seatBoundary,{afterChanged:0,afterStarted:0,calls:[['captured','ptc']]});
    results.push('preset: actual Seat preserves captured identity, pending-selection and first-turn boundaries');
  }
  if(surface==='session'||surface==='all') {
    await fixture.load('dsh-client-ui-workspace',[recipes.session],
      'exports.audit={SearchResultItem,dshaSessionSelection};');
    await page.evaluate(()=>{
      const api=auditExports['@deepseek-ai/dsh-client-ui-workspace'].audit;
      const React=auditModules.react;window.testRoot?.unmount();window.opened=[];window.unarchived=0;window.openFacts=0;
      document.addEventListener('dsha-session-open',()=>++openFacts);
      testRoot=auditModules['react-dom/client'].createRoot(document.getElementById('root'));
      testRoot.render(React.createElement(React.Fragment,null,
        React.createElement(api.SearchResultItem,{result:{id:'new',title:'New task',workspace:'fixture',archived:false,statuses:[]},currentId:'old',onOpen:id=>opened.push(id),onUnarchive:()=>++unarchived,t:key=>key}),
        React.createElement(api.SearchResultItem,{result:{id:'archived',title:'Archived task',workspace:'fixture',archived:true,statuses:[]},currentId:'old',onOpen:id=>opened.push(id),onUnarchive:()=>++unarchived,t:key=>key})));
    });
    const row=page.locator('[data-dsha-session-select=new]');await row.waitFor();await row.tap();
    assert.deepEqual(await page.evaluate(()=>opened),['new'],'one real touch tap opens once');assert.equal(await page.evaluate(()=>openFacts),1);
    await row.click();assert.equal(await page.evaluate(()=>opened.length),2,'one real mouse click opens once');
    await row.focus();await page.keyboard.press('Enter');await page.keyboard.press('Space');assert.equal(await page.evaluate(()=>opened.length),4);
    await page.locator('[data-dsha-session-select=archived]').hover();
    await page.locator('[aria-label="menu.unarchiveSession"]').click();assert.equal(await page.evaluate(()=>unarchived),1);assert.equal(await page.evaluate(()=>opened.length),4,'row action does not bubble into navigation');
    results.push('session: actual React row real touch tap, Enter, Space; action button does not navigate');
  }
  assert.deepEqual(await page.evaluate(()=>unhandled),[],'no unhandled rejection');
  assert.deepEqual(fixture.errors,[],'no browser page error');
  console.log(JSON.stringify({status:'PASS',mode:before?'unpatched-negative':'patched',surface,browser:await fixture.browser.version(),inputs:recipeInputs,results},null,2));
} finally { await fixture.close(); }
