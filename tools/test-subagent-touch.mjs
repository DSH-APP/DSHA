// 执行锁定的真实 React 子代理组件，验证触摸打开、关闭和会话地址回调。
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),runtime=process.env.DSHA_TEST_RUNTIME||'app/build/alpha1-016-runtime';
const {chromium}=require(process.env.DSHA_PLAYWRIGHT||'playwright');
const spec=JSON.parse(fs.readFileSync('app/src/main/assets/subagent-navigation-patch.json','utf8'));
let source=fs.readFileSync(path.join(runtime,'node_modules',spec.module),'utf8');
for(const patch of spec.patches){assert.equal(source.split(patch.before).length-1,1);source=source.replace(patch.before,patch.after);}
source=source.replace('exports.apply = apply;','exports.testDropdown = CatalogDropdown; exports.apply = apply;');
const browser=await chromium.launch({headless:true,executablePath:process.env.DSHA_CHROME});
try{for(const width of [393,920]){
 const context=await browser.newContext({viewport:{width,height:800},hasTouch:true,isMobile:true});const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.setContent('<meta name="viewport" content="width=device-width, initial-scale=1"><div id="root" data-mobile-nav="frame"></div><button id="outside" style="position:fixed;bottom:8px">Outside</button>');
 const react=process.env.DSHA_REACT||path.join(runtime,'node_modules');
 await page.addScriptTag({path:path.join(react,'react/umd/react.development.js')});await page.addScriptTag({path:path.join(react,'react-dom/umd/react-dom.development.js')});
 await page.evaluate(()=>{window.__ModuleLoader__={load({factory}){window.component=factory(name=>{
  if(name==='react')return React;if(name==='react-dom')return ReactDOM;if(name==='react/jsx-runtime')return {jsx:(t,p,k)=>React.createElement(t,{...p,key:k}),jsxs:(t,p,k)=>React.createElement(t,{...p,key:k}),Fragment:React.Fragment};
  if(name==='@deepseek-ai/dsh-client-ui-primitives')return new Proxy({},{get:(_,key)=>props=>React.createElement('span',{'data-icon':key})});throw Error(name);
 });}};});
 await page.addScriptTag({content:source});
 await page.evaluate(()=>{
  const state={byId:{parent:{id:'parent'}},subagentsByParent:{parent:{state:'ready',parentAvailable:true,entries:[{kind:'child',id:'child-1',label:'My subagent',mode:'continuable',activity:'inactive',hasChildren:false}]}}};
  window.opened=[];window.catalogEvents=[];
  ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(component.testDropdown,{rootSessionId:'parent',currentSessionId:'parent',variant:'count',useSessions:select=>select(state),openChild:address=>opened.push(address),refresh(){},setCatalogOpen:(id,open)=>catalogEvents.push([id,open]),t:(key,values)=>key+(values?.count??'')}));
 });
 const trigger=page.locator('[data-dsha-lineage-trigger]');await trigger.tap();await page.locator('[role="tree"]').waitFor();
 await page.waitForTimeout(250);assert.equal(await trigger.getAttribute('aria-expanded'),'true');
 await page.locator('[role="treeitem"]').tap();await page.locator('[role="tree"]').waitFor({state:'detached'});
 assert.deepEqual(await page.evaluate(()=>opened),[{parentSessionId:'parent',childSessionId:'child-1',mode:'continuable'}]);
 await trigger.tap();await page.locator('[role="tree"]').waitFor();await page.locator('#outside').tap();await page.waitForTimeout(300);assert.equal(await trigger.getAttribute('aria-expanded'),'false');
 await trigger.tap();await page.locator('[role="tree"]').waitFor();await trigger.tap();assert.equal(await trigger.getAttribute('aria-expanded'),'false');assert.deepEqual(errors,[]);
 await context.close();console.log(`Subagent ${width}px: single-tap open, child address, outside close and repeat toggle passed.`);
 }}finally{await browser.close();}
