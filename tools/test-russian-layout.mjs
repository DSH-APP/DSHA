// 使用当前锁定前端的真实 React、SegmentedControl 与样式验证窄屏和键盘行为。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {browserFixture} from './rc1-browser-fixture.mjs';
const require=createRequire(import.meta.url);
if(process.env.DSHA_CHROMIUM){const {chromium}=require(process.env.DSHA_PLAYWRIGHT||'playwright');const launch=chromium.launch.bind(chromium);chromium.launch=o=>launch({...o,executablePath:process.env.DSHA_CHROMIUM});}
const runtime=process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime';
const source=fs.readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-client-ui-settings-models/lib/client.js','utf8');
const literal=source.match(/const css\$3 = ("(?:\\.|[^"\\])*");/)[1];
const original=JSON.parse(literal);
const recipe=JSON.parse(fs.readFileSync('app/src/main/assets/russian-model-ui-patch.json','utf8'));
const cssPatch=recipe.patches.find(p=>p.before.startsWith('.zGbnIq_addModes'));
assert.equal(original.split(cssPatch.before).length,2);
const patched=original.replace(cssPatch.before,cssPatch.after);
const f=await browserFixture(runtime);
try {
 const frontend=runtime+'/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets';
 for(const name of fs.readdirSync(frontend).filter(n=>n.endsWith('.css')))
  await f.page.addStyleTag({content:fs.readFileSync(frontend+'/'+name,'utf8')});
 await f.page.addStyleTag({content:'body{margin:0;font-family:sans-serif}#root{padding:16px;box-sizing:border-box;width:100%}'});
 await f.page.evaluate(()=>{
  const R=auditModules.react, P=auditModules['@deepseek-ai/dsh-client-ui-primitives'];
  window.mountCase=(key)=>{
   function Case(){const [value,setValue]=R.useState('catalog');return R.createElement('section',{className:'zGbnIq_section','data-russian-layout-case':key},R.createElement('div',{className:'zGbnIq_addBlock'},R.createElement('div',{className:'zGbnIq_addCard'},R.createElement('div',{className:'zGbnIq_addModes'},R.createElement(P.SegmentedControl,{id:'model-mode',label:'Способ добавления',value,onChange:setValue,options:[{value:'catalog',label:'Сторонний поставщик моделей'},{value:'custom',label:'Пользовательская модель API'}]})))));}
   window.caseRoot??=auditModules['react-dom/client'].createRoot(document.getElementById('root'));
   caseRoot.render(R.createElement(Case,{key}));
  };
 });
 const style=await f.page.addStyleTag({content:original});
 await f.page.setViewportSize({width:360,height:800});await f.page.evaluate(()=>mountCase('before'));await f.page.waitForSelector('[role=tab]');
 const before=await f.page.evaluate(()=>{const c=document.querySelector('.zGbnIq_addCard').getBoundingClientRect(),t=document.querySelector('[role=tablist]').getBoundingClientRect();return t.right>c.right+1;});
 assert.equal(before,true,'The original screenshot regression must reproduce');
 await style.evaluate((el,css)=>{el.textContent=css},patched);
 let cases=0;
 for(const width of [320,360,393,480])for(const scale of [1,1.3])for(const theme of ['dark','light']){
  await f.page.setViewportSize({width,height:800});
  await f.page.evaluate(({scale,theme,key})=>{
   let s=document.getElementById('font-case');if(!s){s=document.createElement('style');s.id='font-case';document.head.append(s);}
   s.textContent=`.zGbnIq_addModes [role=tab]{font-size:${13*scale}px}body{background:${theme==='dark'?'#161616':'#fff'};color:${theme==='dark'?'#eee':'#222'}}`;
   document.documentElement.style.setProperty('--dsw-alias-label-primary',theme==='dark'?'#eee':'#222');
   mountCase(key);
  },{scale,theme,key:`${width}/${scale}/${theme}`});
  await f.page.waitForFunction(key=>document.querySelector('[data-russian-layout-case]')?.getAttribute('data-russian-layout-case')===key,`${width}/${scale}/${theme}`);
  const bounds=await f.page.evaluate(()=>{
   const card=document.querySelector('.zGbnIq_addCard').getBoundingClientRect(),track=document.querySelector('[role=tablist]').getBoundingClientRect();
   const tabs=[...document.querySelectorAll('[role=tab]')].map(t=>({right:t.getBoundingClientRect().right,scroll:t.scrollWidth,width:t.clientWidth}));
   return {card:card.right,track:track.right,tabs,doc:document.documentElement.scrollWidth,viewport:innerWidth};
  });
  assert.ok(bounds.track<=bounds.card+1,JSON.stringify(bounds));assert.ok(bounds.doc<=bounds.viewport+1,JSON.stringify(bounds));
  for(const tab of bounds.tabs){assert.ok(tab.right<=bounds.track+1);assert.ok(tab.scroll<=tab.width+1);}
  await f.page.getByRole('tab').nth(0).press('Home');
  await f.page.waitForFunction(()=>document.getElementById('model-mode-catalog').getAttribute('aria-selected')==='true');
  await f.page.getByRole('tab').nth(0).press('ArrowRight');
  await f.page.waitForFunction(()=>document.getElementById('model-mode-custom').getAttribute('aria-selected')==='true');
  cases++;
 }
 assert.deepEqual(f.errors,[]);
 console.log(`PASS original overflow reproduced; ${cases} narrow-screen/font/theme cases; real keyboard switching`);
}finally{await f.close();}
