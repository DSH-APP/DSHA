// 真实锁定组件与移动样式：俄语抽屉按钮、团队弹层和原生 IME 恢复。
import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
import {browserFixture} from './rc1-browser-fixture.mjs';
const runtime=process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime';
const rawMobile=fs.readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
const fixedMobile=fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8');
function mobileCss(source){const c=vm.createContext({window:{__ModuleLoader__:{load:({factory})=>factory(()=>({}))}}});vm.runInContext(source.replace('var __cache = {};','globalThis.mobileRequire=__localRequire; var __cache = {};'),c);return c.mobileRequire('./styles/index.js').MOBILE_CSS;}
const cssBefore=mobileCss(rawMobile),cssAfter=mobileCss(fixedMobile);
const f=await browserFixture(runtime);
try{
 const frontend=runtime+'/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets';
 for(const name of fs.readdirSync(frontend).filter(n=>n.endsWith('.css')))await f.page.addStyleTag({content:fs.readFileSync(frontend+'/'+name,'utf8')});
 await f.load('dsh-client-ui-conversation',[],'module.exports.audit={ConversationHeader};');
 await f.load('dsh-client-ui-sidebar',[],'module.exports.audit={HeaderLeadingControls};');
 await f.load('dsh-experimental-client-ui-agent-team',[],'module.exports.audit={TeamAction,ru};');
 await f.page.evaluate(()=>{window.originalLoader=window.__ModuleLoader__;window.__ModuleLoader__={load:({factory})=>factory(n=>auditModules[n]||{})};});
 await f.page.addScriptTag({content:fixedMobile.replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
 await f.page.evaluate(()=>{window.__ModuleLoader__=originalLoader;});
 const mobileStyle=await f.page.addStyleTag({content:cssBefore});
 await f.page.addStyleTag({content:'html,body,#root{margin:0;width:100%;height:100%;overflow:hidden;font-family:sans-serif}[data-shell-leading]{position:absolute;top:1px;left:8px;z-index:15}.test-start{position:absolute;left:8px;top:1px}.test-team{position:absolute;right:40px;top:1px;z-index:20}[data-test-title]{font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:190px}[data-mobile-nav=frame]{height:100%;position:relative}.wSkVaW_tabs{height:30px;grid-column:1/-1;display:flex;align-items:center}'});
 await f.page.evaluate(()=>{
  const R=auditModules.react;const H=auditExports['@deepseek-ai/dsh-client-ui-conversation'].audit.ConversationHeader;const Native=auditExports['@deepseek-ai/dsh-client-ui-sidebar'].audit.HeaderLeadingControls;const Mobile=mobileRequire('./components/MobileNavToggle.js').MobileNavToggle;const {TeamAction:T,ru}=auditExports['@deepseek-ai/dsh-experimental-client-ui-agent-team'].audit;
  const team={members:[{id:'lead',name:'lead',phase:'active'}],tasks:[]};const state={phase:'ready',projectionsBySession:{lead:{values:{agentTeam:team}}},byId:{lead:{}}};
  const props={sessionId:'lead',useSession:fn=>fn({openState:'ready'}),useSessions:fn=>fn(state),useSessionStatus:fn=>fn(new Map()),openTeammate:()=>{},t:(k,a={})=>Object.entries(a).reduce((s,[k,v])=>s.replace('{'+k+'}',v),ru[k]||k)};
  window.mountPresentation=(key,mode)=>{
   window.drawerClicks=window.startClicks=0;const toggle=()=>{drawerClicks++;};
   const native=R.createElement('div',{'data-shell-leading':true},R.createElement(Native,{toggleSidebar:toggle,useRemoteAccess:()=>true,useShortcuts:()=>undefined,t:k=>k==='toggle.open'?'Развернуть боковую панель':k}));
   const mobile=R.createElement(Mobile,{toggleSidebar:toggle,t:k=>k==='open'?'Открыть боковую панель':'Файлы'});
   const header=R.createElement(H,{sessionId:'lead',useSession:f=>f({blank:false}),useConversation:f=>f({}),renderSlot:name=>name==='conversation.header.leading'?null:R.createElement(R.Fragment,null,R.createElement('div',{className:'wSkVaW_titleRow'},R.createElement('span',{'data-test-title':true,className:'wSkVaW_titleCluster'},'Приветствие и начало сеанса'),mobile),R.createElement('div',{className:'wSkVaW_tabs','data-test-tabs':true},'Чат    Траектория'))});
   window.presentationRoot??=auditModules['react-dom/client'].createRoot(document.getElementById('root'));
   presentationRoot.render(R.createElement('div',{'data-case':key,'data-mobile-nav':'frame'},native,mode==='tools'?R.createElement('button',{className:'test-start',onClick:()=>startClicks++},'Старт'):R.createElement('section',{'data-phase':'active'},header),R.createElement('div',{className:'test-team'},R.createElement(T,props))));
  };mountPresentation('original','chat');
 });
 await f.page.waitForSelector('[data-sidebar-opener]');
 const count=()=>f.page.locator('[data-sidebar-opener]:visible,[data-mobile-nav=toggle]:visible').count();
 assert.equal(await count(),2,'Original Russian duplicate must reproduce');
 await f.page.locator('[data-team-action]>button').click();await f.page.waitForSelector('[data-team-panel]');
 const original=await f.page.locator('[data-team-panel]').evaluate(e=>{const r=e.getBoundingClientRect();return Math.abs(r.left-(innerWidth-r.right));});assert.ok(original>5,'Original offset popup must reproduce');await f.page.keyboard.press('Escape');
 await mobileStyle.evaluate((s,css)=>s.textContent=css,cssAfter);
 const teamRecipe=JSON.parse(fs.readFileSync('app/src/main/assets/mobile-team-panel-layout-patch.json','utf8'));
 const rule=teamRecipe.patches[0].after.slice(teamRecipe.patches[0].before.length);await f.page.addStyleTag({content:rule});
 let cases=0;
 for(const width of [320,360,393,480])for(const scale of [1,1.3])for(const dark of [true,false]){
  await f.page.setViewportSize({width,height:820});
  await f.page.evaluate(({key,scale,dark})=>{document.body.toggleAttribute('data-ds-dark-theme',dark);document.body.style.setProperty('--dsw-alias-bg-base',dark?'#141414':'#fff');document.body.style.fontSize=14*scale+'px';document.body.style.color=dark?'#eee':'#222';for(const [key,value] of Object.entries({'--dsw-alias-label-primary':dark?'#eee':'#222','--dsw-alias-label-secondary':dark?'#ccc':'#444','--dsw-alias-label-tertiary':dark?'#aaa':'#666','--dsw-alias-bg-layer-1':dark?'#262626':'#fff','--dsw-alias-bg-layer-2':dark?'#29292c':'#fff','--dsw-alias-bg-layer-3':dark?'#29292c':'#fff','--dsw-alias-bg-module-platform':dark?'#303033':'#f5f5f5','--dsw-alias-border-l2':dark?'#444':'#ddd','--dsw-alias-border-l3':dark?'#444':'#ddd','--dsw-alias-border-l4':dark?'#444':'#ddd'}))document.body.style.setProperty(key,value);mountPresentation(key,'chat');},{key:String(cases),scale,dark});
  await f.page.waitForSelector(`[data-case="${cases}"] [data-mobile-nav=toggle]`);assert.equal(await count(),1);
  const bounds=await f.page.evaluate(()=>{const a=document.querySelector('[data-mobile-nav=toggle]').getBoundingClientRect(),b=document.querySelector('[data-test-tabs]').getBoundingClientRect();return {a:{bottom:a.bottom,top:a.top},b:{top:b.top}};});assert.ok(bounds.a.bottom<=bounds.b.top+1,JSON.stringify(bounds));
  await f.page.locator('[data-mobile-nav=toggle]').click();assert.equal(await f.page.evaluate(()=>drawerClicks),1);
  await f.page.locator('[data-team-action]>button').click();await f.page.waitForSelector('[data-team-panel]');
  await f.page.locator('[data-team-panel]').evaluate(async(e,scale)=>{const sizes=[e,...e.querySelectorAll('*')].map(x=>[x,parseFloat(getComputedStyle(x).fontSize)]);for(const [x,size] of sizes)x.style.fontSize=size*scale+'px';await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));},scale);
  if(width===393&&scale===1.3&&dark&&process.env.DSHA_TEST_SCREENSHOT)await f.page.screenshot({path:process.env.DSHA_TEST_SCREENSHOT});
  const box=await f.page.locator('[data-team-panel]').evaluate(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:innerWidth-r.right,scroll:e.scrollWidth,width:e.clientWidth};});assert.ok(Math.abs(box.left-box.right)<1,JSON.stringify(box));assert.ok(box.left>=15&&box.scroll<=box.width+1,JSON.stringify(box));
  await f.page.keyboard.press('Escape');await f.page.waitForSelector('[data-team-panel]',{state:'detached'});
  await f.page.evaluate(key=>mountPresentation(key,'tools'),'tools'+cases);await f.page.waitForSelector(`[data-case="tools${cases}"] .test-start`);assert.equal(await f.page.locator('[data-sidebar-opener]:visible').count(),0);await f.page.locator('.test-start').click();assert.equal(await f.page.evaluate(()=>startClicks),1);cases++;
 }
 // 子智能体目录独立于团队弹层；实际 portal、条目导航与 Escape 不能被居中规则破坏。
 await f.page.setViewportSize({width:393,height:820});
 const catalogRecipe=JSON.parse(fs.readFileSync('app/src/main/assets/mobile-subagent-catalog-patch.json','utf8'));
 await f.load('dsh-client-ui-subagent',[{...catalogRecipe,patches:catalogRecipe.patches.slice(1)}],'module.exports.audit={SubagentCatalogAction,ru};');
 await f.page.evaluate(()=>{
  const R=auditModules.react,{SubagentCatalogAction:C,ru}=auditExports['@deepseek-ai/dsh-client-ui-subagent'].audit;
  const entries=[{id:'child-a',label:'Client UI plugin feasibility',mode:'continuable'},{id:'child-b',label:'Host API and package reuse feasibility',mode:'continuable'}];
  const state={byId:{lead:{origin:'root'},'child-a':{title:'Ты — исследователь',running:true},'child-b':{title:'Исследование API',running:true}},projectionsBySession:{lead:{state:'idle',values:{subagentCatalog:entries}},'child-a':{state:'idle',values:{subagentCatalog:[]}},'child-b':{state:'idle',values:{subagentCatalog:[]}}}};
  window.mountCatalog=key=>{window.childOpened=window.asideOpened=null;presentationRoot.render(R.createElement('div',{'data-catalog-case':key,style:{position:'absolute',right:'40px',top:'80px'}},R.createElement(C,{sessionId:'lead',useSessions:fn=>fn(state),useSessionStatus:fn=>fn(new Map()),openChild:value=>childOpened=value,openChildAside:value=>asideOpened=value,refreshProjection:()=>{},t:(k,a={})=>Object.entries(a).reduce((s,[k,v])=>s.replace('{'+k+'}',v),ru[k]||k)})));};mountCatalog('original');
 });
 await f.page.locator('.ZKlsPq_trigger').click();await f.page.waitForSelector('.ZKlsPq_menu');
 const catalogBounds=()=>f.page.locator('.ZKlsPq_menu').evaluate(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:innerWidth-r.right,scroll:e.scrollWidth,width:e.clientWidth};});
 const offset=await catalogBounds();assert.ok(Math.abs(offset.left-offset.right)>5,JSON.stringify(offset));
 await f.page.keyboard.press('Escape');
 const cssRule=catalogRecipe.patches[0].after.slice(0,-catalogRecipe.patches[0].before.length);await f.page.addStyleTag({content:cssRule});
 let catalogCases=0;
 for(const width of [320,360,393,480,800])for(const scale of [1,1.3]){
  await f.page.setViewportSize({width,height:820});await f.page.evaluate(k=>mountCatalog(k),String(catalogCases));
  await f.page.waitForSelector(`[data-catalog-case="${catalogCases}"]`);await f.page.locator('.ZKlsPq_trigger').click();await f.page.waitForSelector('.ZKlsPq_menu');
  await f.page.locator('.ZKlsPq_menu').evaluate((e,s)=>{const sizes=[e,...e.querySelectorAll('*')].map(x=>[x,parseFloat(getComputedStyle(x).fontSize)]);for(const [x,size] of sizes)x.style.fontSize=size*s+'px';},scale);
  assert.match(await f.page.locator('.ZKlsPq_menu').innerText(),/Работает/);
  const b=await catalogBounds();assert.ok(Math.abs(b.left-b.right)<1&&b.left>=16&&b.scroll<=b.width+1,JSON.stringify(b));
  assert.equal(await f.page.getByRole('treeitem').count(),2);
  if(width===393&&scale===1.3&&process.env.DSHA_CATALOG_SCREENSHOT)await f.page.screenshot({path:process.env.DSHA_CATALOG_SCREENSHOT});
  await f.page.getByRole('treeitem').first().press('Enter');await f.page.waitForSelector('.ZKlsPq_menu',{state:'detached'});assert.equal(await f.page.evaluate(()=>childOpened.childSessionId),'child-a');
  await f.page.locator('.ZKlsPq_trigger').click();await f.page.waitForSelector('.ZKlsPq_menu');await f.page.locator('.ZKlsPq_sidebarButton').first().click();await f.page.waitForSelector('.ZKlsPq_menu',{state:'detached'});assert.equal(await f.page.evaluate(()=>asideOpened.childSessionId),'child-a');
  await f.page.locator('.ZKlsPq_trigger').click();await f.page.waitForSelector('.ZKlsPq_menu');await f.page.keyboard.press('Escape');await f.page.waitForSelector('.ZKlsPq_menu',{state:'detached'});catalogCases++;
 }
 await f.page.setViewportSize({width:1280,height:820});await f.page.locator('.ZKlsPq_trigger').click();await f.page.waitForSelector('.ZKlsPq_menu');const desktop=await catalogBounds();assert.ok(Math.abs(desktop.width-336)<1&&Math.abs(desktop.left-desktop.right)>100,JSON.stringify(desktop));await f.page.keyboard.press('Escape');
 console.log(`PASS actual subagent catalog: original offset, ${catalogCases} phone/tablet/font cases, child/sidebar navigation, Escape, desktop trigger alignment`);
 // 原生容器一次扩容的恢复必须立即发布；普通浏览器仍由上游两帧稳定策略负责。
 const growth=await f.page.evaluate(async()=>{
  const root=document.documentElement;let h=800;const viewport=new EventTarget();viewport.height=800;viewport.offsetTop=0;Object.defineProperty(window,'innerHeight',{configurable:true,get:()=>h});Object.defineProperty(window,'visualViewport',{configurable:true,value:viewport});root.setAttribute('data-dsha-safe-top','native');
  const dispose=mobileRequire('./effects/viewport-meta.js').syncStableViewportHeight();await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  h=500;viewport.height=500;window.dispatchEvent(new Event('resize'));h=490;viewport.height=490;window.dispatchEvent(new Event('resize'));h=800;viewport.height=800;window.dispatchEvent(new Event('resize'));const value=root.style.getPropertyValue('--dsha-mobile-visible-vh');dispose();return value;
 });assert.equal(growth,'800px');
 // body-only 暗色变量也要给根画布着色，避免 IME 动画区域退回浏览器白底。
 await f.page.evaluate(()=>{document.body.style.setProperty('--dsw-alias-bg-base','#141414');window.DshaTheme={postMessage:()=>{}};});
 await f.page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/page.js','utf8')});
 await f.page.evaluate(()=>__dshaNativePresentation.install({nonce:'1'.repeat(32),generation:1,instance:'test'}));await f.page.waitForFunction(()=>getComputedStyle(document.documentElement).backgroundColor==='rgb(20, 20, 20)');
 assert.deepEqual(f.errors,[]);console.log(`PASS original Russian duplicate and off-center popup reproduced; ${cases} phone/font/theme cases; real drawer/Start/Escape actions; immediate native IME growth and themed canvas`);
}finally{await f.close();}
