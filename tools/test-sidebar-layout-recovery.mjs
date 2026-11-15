// Locked rc3 React + genuine sidebar store + hook-safe error boundary.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture=await browserFixture();
try {
  const recipe=JSON.parse(fs.readFileSync('app/src/main/assets/sidebar-layout-recovery-patch.json','utf8'));
  await fixture.load('dsh-client-ui-sidebar-right',[recipe], 'module.exports.audit={createSidebarRightStore,RightbarSeat,dshaValidSurface,readSidebarLayout};');
  const page=fixture.page;
  await page.evaluate(() => {
    const React=auditModules.react.default||auditModules.react, ReactDOM=auditModules['react-dom'].default||auditModules['react-dom'];
    const api=auditExports['@deepseek-ai/dsh-client-ui-sidebar-right'].audit;
    window.layoutApi=api; window.handle=api.createSidebarRightStore(() => ({kind:'guide',contentId:'guide',title:'Guide'}));
    window.instance=handle.create('fixture'); instance.actions.open('fixture'); instance.actions.open('other');
    window.other=instance.getSnapshot().bySession.other;
    localStorage.setItem('dsh.conversation.fixture',JSON.stringify({draft:'KEEP TEXT',view:null,viewRequest:null}));
    window.root=ReactDOM.createRoot(document.getElementById('root'));
    const noop=()=>{};
    window.props={sessionId:'fixture',width:350,viewportWidth:360,canShow:true,active:true,actions:instance.actions,
      t:key=>key, useStore:pick=>pick(React.useSyncExternalStore(instance.subscribe,instance.getSnapshot)),
      useShortcuts:pick=>pick([]),useTabTypes:pick=>pick([]),renderSlot:()=>null,
      useTabNavigation:()=>({}),occurrence:tab=>({id:tab.id,signal:new AbortController().signal,tabActions:{}}),retainTab:noop,
      syncPresentation:noop,measureRoom:noop,reportAutoFullscreen:noop,openTab:noop,closeTab:noop,splitPane:noop,toggleFullscreen:noop,
      // 0.2.2-rc.1 RightbarSeat also receives the context-menu hooks and flash projection.
      useContextMenu:()=>({getTriggerProps:(target,handlers={})=>handlers}),useContextMenuTarget:()=>false,useFlash:pick=>pick(undefined)};
    window.render=()=>root.render(React.createElement(api.RightbarSeat,props)); render();
  });
  await page.locator('[data-sidebar-right-session="fixture"]').waitFor();
  for (const kind of ['missing-tabs','missing-root','active-tab']) {
    await page.evaluate(kind => {
      const value=JSON.parse(JSON.stringify(instance.getSnapshot())), s=value.bySession.fixture;
      if (kind==='missing-tabs') delete s.layout.nodes[s.layout.rootId].tabs;
      if (kind==='missing-root') s.layout.rootId='pane999';
      if (kind==='active-tab') s.layout.nodes[s.layout.rootId].activeTabId='tab999';
      instance.store.set(value);
    },kind);
    await page.locator('[data-dsha-layout-recovery]').waitFor();
    await page.locator('[data-dsha-layout-recovery] button').click();
    await page.locator('[data-sidebar-right-session="fixture"]').waitFor();
    const result=await page.evaluate(() => ({valid:layoutApi.dshaValidSurface(instance.getSnapshot().bySession.fixture),
      other:JSON.stringify(instance.getSnapshot().bySession.other)===JSON.stringify(other),draft:JSON.parse(localStorage.getItem('dsh.conversation.fixture')).draft,
      copies:Object.keys(localStorage).filter(key=>key.startsWith('dsha.layout-recovery.v1.fixture.')).length}));
    assert.equal(result.valid,true); assert.equal(result.other,true); assert.equal(result.draft,'KEEP TEXT'); assert.ok(result.copies>0);
  }
  const persisted=await page.evaluate(() => {
    const raw=JSON.stringify({bySession:{bad:{layout:{nodes:{},tabs:{},rootId:'pane1',floats:[],activePaneId:'pane1',expanded:false,mode:'push'},minted:1}}});
    localStorage.setItem('dsh.sidebar-right.v1.bad',raw);
    const result=layoutApi.readSidebarLayout('bad');
    return {absent:result===undefined,copy:Object.keys(localStorage).some(key=>key.startsWith('dsha.layout-recovery.v1.bad.')&&localStorage.getItem(key)===raw)};
  });
  assert.deepEqual(persisted,{absent:true,copy:true});
  assert.ok(!fixture.errors.some(error=>/hooks|Rendered more|Rendered fewer/i.test(error)),JSON.stringify(fixture.errors));
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',cases:3,persisted,conversationPreserved:true,hookErrors:0}));
} finally {await fixture.close();}
