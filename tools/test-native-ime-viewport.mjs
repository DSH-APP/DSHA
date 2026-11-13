// 真实聊天 sticky CSS + 完整移动可见区 effect；延迟 visualViewport 重现收键盘空白。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const f = await browserFixture();
const mobile = fs.readFileSync(process.env.DSHA_MOBILE_CANDIDATE || 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
const near = (value, expected, name) => assert.ok(Math.abs(value - expected) < 1, `${name}: ${value} != ${expected}`);
try {
  await f.load('dsh-client-ui-conversation', [], 'module.exports.imeCss=ConversationRoot_module_css_default;');
  await f.page.addScriptTag({content:mobile.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
  await f.page.evaluate(() => {
    const style = document.createElement('style');
    style.textContent = mobileRequire('./styles/index.js').MOBILE_CSS;
    document.head.appendChild(style);
    const c = auditExports['@deepseek-ai/dsh-client-ui-conversation'].imeCss;
    document.querySelector('#root').innerHTML = `<div data-mobile-nav="frame"><div class="${c.root}" data-phase="active"><header style="height:50px;flex:none">Header</header><div class="${c.body}" data-conversation-content data-content-phase="active"><div class="${c.scrollBody}" data-conversation-scroll><div class="${c.viewArea}" data-chat-flow><div style="height:1400px">Messages</div></div><div class="${c.composerSeat}" data-composer-seat><div data-composer-input contenteditable="true" style="height:80px">Input</div></div></div></div></div></div>`;
    window.binding = {nonce:'c'.repeat(32),generation:9,instance:'delayed-viewport'};
    window.tick = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    window.vv = new EventTarget();
    Object.assign(vv, {height:800,width:360,offsetTop:0,scale:1});
    Object.defineProperty(window, 'visualViewport', {configurable:true,value:vv});
    window.viewport = (height, top=0) => {vv.height=height;vv.offsetTop=top;vv.dispatchEvent(new Event('resize'));};
    window.readGeometry = () => {
      const frame=document.querySelector('[data-mobile-nav=frame]').getBoundingClientRect();
      const seat=document.querySelector('[data-composer-seat]').getBoundingClientRect();
      return {frame:frame.height,bottom:seat.bottom,top:seat.top,viewportTop:document.documentElement.style.getPropertyValue('--dsha-mobile-viewport-top')};
    };
  });
  await f.page.addStyleTag({content:'html,body,#root{height:100%;margin:0;overflow:hidden}[data-mobile-nav=frame]{display:grid;grid-template-rows:minmax(0,1fr)}'});
  await f.page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/safe-area.js','utf8')});
  // 先保留 build361 的旧帧契约回归；当前 WebView resize 模式在下方单独验证。
  await f.page.evaluate(()=>document.documentElement.removeAttribute('data-dsha-ime-mode'));
  await f.page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/ime.js','utf8')});
  await f.page.evaluate(async () => {
    __dshaIme.bind(binding);
    window.id=0;
    window.send = (height,phase='frame',epoch=1) => new Promise(resolve => __dshaIme.receive({...binding,frameId:++id,epoch,width:360,height,phase}, resolve));
    window.disposeViewport=mobileRequire('./effects/viewport-meta.js').syncStableViewportHeight();
    document.querySelector('[data-composer-input]').focus();
    document.querySelector('[data-conversation-scroll]').scrollTop=10000;
    await tick();
  });
  const gap=800-(await f.page.evaluate(()=>readGeometry())).bottom;
  assert.ok(gap>=0&&gap<32,'preserve actual mobile composer bottom spacing');
  const writes=await f.page.evaluate(async()=>{
    let count=0;const observer=new MutationObserver(rows=>count+=rows.length);
    observer.observe(document.documentElement,{attributes:true,attributeFilter:['style']});
    for(let h=790;h>=500;h-=10){viewport(h);await tick();}
    observer.disconnect();return count;
  });
  assert.equal(writes,0,'native visualViewport animation cannot rewrite whole-page height or pan');
  // 打开键盘，native 的绝对帧先到；浏览器 visualViewport 随后缩小。
  await f.page.evaluate(async()=>{await send(500);viewport(500);await tick();await tick();});
  let g=await f.page.evaluate(()=>readGeometry());
  near(g.frame,800,'native shell must keep layout viewport during animation');
  near(g.bottom,500-gap,'native target is applied once, never doubled by shell shrink');
  await f.page.setViewportSize({width:360,height:500});
  await f.page.evaluate(async()=>{await send(500,'end');await tick();});
  g=await f.page.evaluate(()=>readGeometry());near(g.frame,500,'open settled shell');near(g.bottom,500-gap,'open settled composer');
  // 收起时 native 扩容而 visualViewport 仍为 500；不允许再按旧视觉带裁一次。
  await f.page.setViewportSize({width:360,height:800});
  await f.page.evaluate(async()=>{await tick();});
  g=await f.page.evaluate(()=>readGeometry());near(g.frame,800,'expanded native shell with stale visual viewport');near(g.bottom,500-gap,'retain actual first animation target');
  for (const height of [550,650,740,800]) {
    await f.page.evaluate(async h=>{await send(h,'frame',2);await tick();},height);
    g=await f.page.evaluate(()=>readGeometry());near(g.frame,800,'stable shell while hiding');near(g.bottom,height-gap,'hide composer follows absolute native frame');
  }
  await f.page.evaluate(async()=>{await send(800,'end',2);viewport(500,120);await tick();});
  g=await f.page.evaluate(()=>readGeometry());near(g.frame,800,'end ignores stale visual viewport');near(g.bottom,800-gap,'no final composer gap');assert.equal(g.viewportTop,'0px');
  // visualViewport 在终态两秒后才恢复；等待期不能再次把聊天变矮。
  await f.page.waitForTimeout(1200);
  g=await f.page.evaluate(()=>readGeometry());near(g.frame,800,'no deferred stale viewport collapse');near(g.bottom,800-gap,'no delayed jump');
  await f.page.evaluate(async()=>{viewport(800);await tick();});
  near((await f.page.evaluate(()=>readGeometry())).bottom,800-gap,'late visual viewport is harmless');
  // 反向时序：end IPC 先到，容器 resize 后到；最终仍无残留偏移。
  await f.page.evaluate(async()=>{await send(500,'end',3);viewport(500);await tick();});
  await f.page.setViewportSize({width:360,height:500});
  await f.page.evaluate(async()=>{await tick();await send(800,'end',4);await tick();});
  await f.page.setViewportSize({width:360,height:800});
  await f.page.evaluate(async()=>{await tick();});
  g=await f.page.evaluate(()=>readGeometry());near(g.frame,800,'end-before-resize shell');near(g.bottom,800-gap,'end-before-resize composer');
  // 当前 WebView：即使 JS resize 回调被阻塞、旧变量/visualViewport 停在键盘高度，
  // 真实百分比布局仍随原生浏览器尺寸变化；旧 IPC 不能再单独移动输入框。
  await f.page.evaluate(()=>{
    __dshaIme.cancel();disposeViewport();
    document.documentElement.setAttribute('data-dsha-ime-mode','resize');
    document.documentElement.style.setProperty('--dsha-mobile-visible-vh','500px');
    viewport(500);
    window.blockResize=e=>e.stopImmediatePropagation();
    window.addEventListener('resize',blockResize,true);
  });
  for(const height of [740,650,550,500,550,650,740,800]) {
    await f.page.setViewportSize({width:360,height});
    await f.page.evaluate(async()=>{await tick();});
    g=await f.page.evaluate(()=>readGeometry());near(g.frame,height,'native percentage frame without resize JS');near(g.bottom,height-gap,'whole chat and composer resize together');
    const ack=await f.page.evaluate(()=>send(400,'frame',5));
    assert.equal(ack.supported,false,'retired async seat path must refuse late native frames');
    near((await f.page.evaluate(()=>readGeometry())).bottom,height-gap,'late IPC cannot create another layout stage');
    assert.equal(await f.page.locator('[data-dsha-ime-seat]').count(),0);
  }
  await f.page.evaluate(()=>{
    window.removeEventListener('resize',blockResize,true);
    document.documentElement.removeAttribute('data-dsha-ime-mode');
  });
  // 普通浏览器仍使用实际 visualViewport，不能破坏覆盖式键盘。
  await f.page.evaluate(async()=>{__dshaIme.cancel();disposeViewport();document.documentElement.removeAttribute('data-dsha-safe-top');viewport(500);disposeViewport=mobileRequire('./effects/viewport-meta.js').syncStableViewportHeight();await tick();await tick();});
  near((await f.page.evaluate(()=>readGeometry())).frame,500,'browser tab retains visual viewport handling');
  assert.deepEqual(f.errors,[]);
  console.log('PASS pinned sticky chat + mobile viewport effect: delayed visualViewport, open/close native frames, no double compensation, browser fallback');
} finally { await f.close(); }
