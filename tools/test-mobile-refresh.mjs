// 实际随包 bundle 与 rc2 前端样式；独立 DOM 验证点击边界和两次独立模块加载。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture=await browserFixture();
try {
  const page=fixture.page;
  page.setDefaultTimeout(3000);
  const runtime=JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw;
  const frontend=path.join(runtime,'node_modules/@deepseek-ai/dsh-web-frontend/dist');
  for(const match of fs.readFileSync(path.join(frontend,'index.html'),'utf8').matchAll(/href="\.\/(assets\/[^" ]+\.css)"/g))
    await page.addStyleTag({content:fs.readFileSync(path.join(frontend,match[1]),'utf8')});
  const bundle=fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8')
    .replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')
    .replace("exports.inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions'];",
      "exports.inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions']; window.swapTest={rememberConversationScroll,restoreConversationScroll};");
  const load=async()=>{
    await page.evaluate(()=>{window.__ModuleLoader__={load:({factory})=>factory(()=>({}))};});
    await page.addScriptTag({content:bundle});
  };
  await load();
  await page.evaluate(()=>{
    for(const name of ['base','layout','compat','misc']) {
      const style=document.createElement('style');
      style.textContent=Object.values(mobileRequire('./styles/'+name+'.css.js')).filter(v=>typeof v==='string').join('\n');
      document.head.append(style);
    }
    document.getElementById('root').innerHTML=`<div data-composer-card><input type="file" hidden><button id="upload" data-mobile-nav="file-upload">📎</button></div>
      <ul><li data-plugin-package="fixture" style="width:300px;padding:15px;margin-top:30px">
      <button class="fixture_cardOpen">Open</button><p id="description">Plugin description</p>
      <button id="toggle" role="switch" aria-checked="false">Toggle</button></li></ul>
      <div data-mobile-nav="frame"><div id="scroller" class="fixture_scrollBody" style="height:120px;overflow:auto"><div style="height:1800px">History</div></div></div>`;
    window.opens=0;window.toggles=0;window.disposers=[];
    document.querySelector('.fixture_cardOpen').onclick=()=>opens++;
    document.getElementById('toggle').onclick=()=>toggles++;
    mobileRequire('./effects/plugin-card-tap.js').installPluginCardTap({effect(run){disposers.push(run());}});
  });
  const tapDescription=async()=>{
    const box=await page.locator('#description').boundingBox();
    await page.touchscreen.tap(box.x+box.width/2,box.y+box.height/2);
  };
  for(const [index,kind] of ['package','row','item'].entries()) {
    await page.evaluate(kind=>{
      const card=document.querySelector('li');
      for(const key of ['pluginPackage','pluginRow','pluginItem'])delete card.dataset[key];
      card.dataset['plugin'+kind[0].toUpperCase()+kind.slice(1)]='fixture';
      card.querySelector('button').className=kind==='row'?'fixture_rowOpen':'fixture_cardOpen';
    },kind);
    await tapDescription();
    await page.locator('li > button').first().tap();
    await page.locator('#toggle').tap();
    assert.deepEqual(await page.evaluate(()=>({opens,toggles})),{opens:2*(index+1),toggles:index+1},kind);
  }
  await page.locator('#upload').hover();
  const pill=await page.locator('#upload').evaluate(el=>({
    before:getComputedStyle(el,'::before').backgroundColor,
    width:el.getBoundingClientRect().width,height:el.getBoundingClientRect().height}));
  assert.deepEqual(pill,{before:'rgba(0, 0, 0, 0)',width:28,height:28});
  await page.evaluate(()=>{document.getElementById('scroller').scrollTop=400;swapTest.rememberConversationScroll();});
  await load(); // Host热替换会生成新模块，不能靠旧模块的局部变量保存位置。
  await page.evaluate(()=>{document.getElementById('scroller').scrollTop=0;swapTest.restoreConversationScroll();});
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));
  assert.equal(await page.locator('#scroller').evaluate(el=>el.scrollTop),400,'fresh module restores the same scroller');
  await page.evaluate(()=>{swapTest.rememberConversationScroll();document.getElementById('scroller').scrollTop=100;swapTest.restoreConversationScroll();});
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));
  assert.equal(await page.locator('#scroller').evaluate(el=>el.scrollTop),100,'host-selected position remains');
  await page.evaluate(()=>{
    swapTest.rememberConversationScroll();
    const old=document.getElementById('scroller');old.replaceWith(old.cloneNode(true));
    swapTest.restoreConversationScroll();
  });
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));
  assert.equal(await page.locator('#scroller').evaluate(el=>el.scrollTop),0,'new DOM never inherits the old scroller position');
  await page.setViewportSize({width:1200,height:800});
  await tapDescription();
  assert.equal(await page.evaluate(()=>opens),6,'desktop description is not forwarded');
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({cardRoots:['package','row','item'],cardDescription:true,titleOnce:true,toggleIndependent:true,pill,
    freshModuleScrollRestore:true,preserveHostScroll:true,desktopNoForward:true,errors:[]}));
} finally {await fixture.close();}
