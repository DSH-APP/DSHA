// 使用真实设置组件验证卡片展开、预设切换与窄屏布局。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {browserFixture} from './rc1-browser-fixture.mjs';
const require=createRequire(import.meta.url);
if(process.env.DSHA_CHROMIUM){const {chromium}=require(process.env.DSHA_PLAYWRIGHT||'playwright');const launch=chromium.launch.bind(chromium);chromium.launch=o=>launch({...o,executablePath:process.env.DSHA_CHROMIUM});}
const runtime=process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime';
const recipes=JSON.parse(fs.readFileSync('tools/i18n/russian-followup-patches.json','utf8'));
const layout=recipes.find(r=>r.asset==='russian-plugin-settings-layout-patch.json');
const raw=fs.readFileSync(runtime+'/node_modules/'+layout.module,'utf8');
const original=JSON.parse(raw.match(/const css = ("(?:\\.|[^"\\])*");/)[1]);
let patched=original;for(const p of layout.patches.filter(p=>p.before.startsWith('.')||p.before.startsWith('@media'))){assert.equal(patched.split(p.before).length,2);patched=patched.replace(p.before,p.after);}
const f=await browserFixture(runtime);
try{
 const frontend=runtime+'/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets';
 for(const file of fs.readdirSync(frontend).filter(n=>n.endsWith('.css')))await f.page.addStyleTag({content:fs.readFileSync(frontend+'/'+file,'utf8')});
 await f.load('dsh-client-ui-settings-plugin-inventory',[],`module.exports.audit={PluginInventorySettingsTab,ru};`);
 await f.page.addStyleTag({content:'body{margin:0;font-family:sans-serif}#root{width:100%;padding:16px;box-sizing:border-box}.settings-test-shell{display:flex;flex-direction:column;width:100%;min-width:0;align-items:center}'});
 await f.page.evaluate(()=>{
  const R=auditModules.react, {PluginInventorySettingsTab:C,ru}=auditExports['@deepseek-ai/dsh-client-ui-settings-plugin-inventory'].audit;
  const rows=[{moduleName:'@deepseek-ai/dsh-tool-bash',entryId:'include:bash',enabled:true,fiberPhase:'active',meta:{title:'Командный инструмент Bash',description:'Длинное описание плагина с русским текстом и идентификаторами.'}}];
  const snapshot={entries:rows,agentPresets:[{id:'standard',isDefault:true,rows},{id:'minimal',rows}]};
  const props={list:async()=>snapshot,presetName:p=>p.id==='standard'?'Стандартный режим':'Минимальный режим',resolveText:v=>typeof v==='string'?v:v?.ru||v?.en||'',t:(k,args={})=>Object.entries(args).reduce((s,[k,v])=>s.replace('{'+k+'}',v),ru[k]||k),useClientSync:()=>({syncing:false,failures:[]}),retryClient:()=>{}};
  window.mountInventory=key=>{window.inventoryRoot??=auditModules['react-dom/client'].createRoot(document.getElementById('root'));inventoryRoot.render(R.createElement('main',{className:'settings-test-shell','data-case':key},R.createElement(C,{...props,key})));};
  mountInventory('original');
 });
 await f.page.waitForSelector('.qSYn7G_switcher');
 const before=await f.page.evaluate(()=>{const s=document.querySelector('.qSYn7G_switcher').getBoundingClientRect();return s.right>innerWidth||document.documentElement.scrollWidth>innerWidth;});
 assert.equal(before,true,'Original settings overflow must reproduce');
 await f.page.locator('style[data-plugin-css]').evaluate((el,css)=>el.textContent=css,patched);
 let count=0;
 for(const width of [320,360,393,480])for(const scale of [1,1.3])for(const theme of ['light','dark']){
  await f.page.setViewportSize({width,height:900});
  await f.page.evaluate(({key,scale,theme})=>{document.documentElement.style.fontSize=16*scale+'px';document.body.style.background=theme==='dark'?'#222':'#fff';let style=document.getElementById('scale');if(!style){style=document.createElement('style');style.id='scale';document.head.append(style);}style.textContent=`.qSYn7G_section button,.qSYn7G_section span,.qSYn7G_section p{font-size:${14*scale}px}`;mountInventory(key);},{key:String(count),scale,theme});
  await f.page.waitForSelector(`[data-case="${count}"] .qSYn7G_switcher`);
  const check=async()=>{
   const bounds=await f.page.evaluate(()=>({doc:document.documentElement.scrollWidth,viewport:innerWidth,items:[...document.querySelectorAll('.qSYn7G_section,.qSYn7G_search,.qSYn7G_search input,.qSYn7G_groupTitleRow,.qSYn7G_switcher,.qSYn7G_card')].map(e=>({class:e.className,left:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right}))}));
   assert.ok(bounds.doc<=bounds.viewport+1,JSON.stringify(bounds));for(const r of bounds.items)assert.ok(r.left>=-1&&r.right<=width+1,JSON.stringify(r));
  };
  await check();
  await f.page.locator('.qSYn7G_cardContent').first().click();
  await f.page.waitForSelector('.qSYn7G_card[data-open=true]');await check();
  await f.page.locator('.qSYn7G_switcher').click();await f.page.getByText('Минимальный режим',{exact:true}).click();
  await f.page.waitForFunction(()=>document.querySelector('.qSYn7G_switcherLabel').textContent==='Минимальный режим');await check();
  count++;
 }
 assert.deepEqual(f.errors,[]);console.log(`PASS original settings overflow reproduced; ${count} width/font/theme cases, card details and preset switching`);
}finally{await f.close();}
