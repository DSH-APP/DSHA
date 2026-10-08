// 执行当前锁定 rc2 的真实 ShortcutReference/Modal、合入的移动 bundle 和 CSS。
// 合成快捷键目录不连接账户；浏览器验收不能替代 Android 软键盘/读屏实机验收。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { browserFixture } from './rc1-browser-fixture.mjs';

const runtime = process.env.DSHA_TEST_RUNTIME || JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json', 'utf8')).raw;
const fixture = await browserFixture(runtime);
const page = fixture.page;
page.setDefaultTimeout(3000);
try {
  const frontend = path.resolve(runtime, 'node_modules/@deepseek-ai/dsh-web-frontend/dist');
  const html = fs.readFileSync(path.join(frontend, 'index.html'), 'utf8');
  for (const match of html.matchAll(/href="\.\/(assets\/[^" ]+\.css)"/g))
    await page.addStyleTag({content: fs.readFileSync(path.join(frontend, match[1]), 'utf8')});
  await fixture.load('dsh-client-ui-shortcuts', [], 'module.exports.audit = {ShortcutReference};');
  const source = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
  await page.evaluate(() => { window.__ModuleLoader__ = {load({factory}) { factory(() => ({})); }}; });
  await page.addScriptTag({content: source.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
  await page.evaluate(() => {
    window.disposers = [];
    window.context = {effect(run) { disposers.push(run()); }};
    window.phone = mobileRequire('./effects/phone-chrome.js');
    window.guard = mobileRequire('./effects/shortcut-modal-keyboard-guard.js');
    window.originalFocusDescriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'focus');
    window.originalFocus = HTMLInputElement.prototype.focus;
    guard.installShortcutModalKeyboardGuard(context);
    phone.installPhoneChrome(context);
    for (const name of ['base', 'layout', 'compat', 'misc']) {
      const exports = mobileRequire(`./styles/${name}.css.js`);
      const style = document.createElement('style');
      style.textContent = Object.values(exports).filter(value => typeof value === 'string').join('\n');
      document.head.append(style);
    }
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const Reference = auditExports['@deepseek-ai/dsh-client-ui-shortcuts'].audit.ShortcutReference;
    const catalog = Array.from({length:40}, (_, i) => ({id:`action.${i}`, label:`Action ${i}`, aliases:[], keys:['Ctrl','+',String(i)], modified:false}));
    const config = {status:'ready', revision:'fixture', document:{profiles:{'web:windows':{}}}};
    window.state = {open:true, query:'', focusRequest:0};
    const props = {
      useStore: pick => pick(state), useCatalog: pick => pick(catalog), useConfig: pick => pick(config),
      useFixedCatalog: pick => pick([]), platform:'windows', runtime:'web', edit:async()=>({status:'saved'}),
      recording:{}, describeBinding:()=>'', t:key=>key,
      actions:{search(query){state.query=query; render();}, close(){state.open=false; render();}},
    };
    window.root = ReactDOM.createRoot(document.getElementById('root'));
    window.render = () => root.render(React.createElement(Reference, props));
    render();
  });
  const modal = page.locator('[data-shortcut-modal="shortcuts"]');
  const search = modal.getByRole('searchbox', {name:'search', exact:true});
  await modal.waitFor();
  assert.equal(await search.isVisible(), true, '手机搜索必须可见');
  assert.equal(await page.evaluate(() => document.activeElement?.dataset.shortcutModal), 'shortcuts', '真实 rc2 多个 layoutEffect 自动聚焦应停留在弹层');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  assert.equal(await search.evaluate(el => el === document.activeElement), true, 'Tab 可进入搜索');
  await search.fill('Action 31');
  assert.equal(await modal.locator('li').count(), 1, '真实目录过滤生效');
  await modal.getByRole('button', {name:'clear-search', exact:true}).click();
  assert.equal(await search.inputValue(), '');
  assert.equal(await search.evaluate(el => el === document.activeElement), true, '清除按钮的程序化聚焦保留');
  await modal.evaluate(el => el.focus());
  await search.tap();
  assert.equal(await search.evaluate(el => el === document.activeElement), true, '真实触摸可聚焦');
  assert.match(await modal.ariaSnapshot(), /searchbox "search"/, '搜索保留可访问名称与角色');

  const cases = [];
  for (const [width,height] of [[320,360],[360,260],[800,360],[360,800]]) {
    await page.setViewportSize({width,height});
    await page.waitForFunction(() => Number.parseFloat(document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh')) === window.innerHeight);
    const result = await modal.evaluate(el => {
      const box = el.getBoundingClientRect();
      const footer = el.querySelector('footer');
      footer.scrollIntoView({block:'end'});
      const bottom = footer.getBoundingClientRect().bottom;
      return {top:box.top,bottom:box.bottom,footerBottom:bottom,viewport:innerHeight,overflow:document.documentElement.scrollWidth-innerWidth};
    });
    assert.ok(result.top >= 0 && result.bottom <= height+1, JSON.stringify(result));
    assert.ok(result.footerBottom <= height+1, '底部操作必须可滚入可见区: '+JSON.stringify(result));
    assert.ok(result.overflow <= 1, '不得横向溢出');
    cases.push({width,height,...result});
  }
  await page.addStyleTag({content:'[data-shortcut-modal="shortcuts"], [data-shortcut-modal="shortcuts"] * {font-size:20.8px !important; line-height:1.3 !important;}'});
  await page.setViewportSize({width:320,height:320});
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh') === '320px');
  const largeFont = await modal.evaluate(el => {
    el.querySelector('footer').scrollIntoView({block:'end'});
    return {bottom:el.getBoundingClientRect().bottom,footerBottom:el.querySelector('footer').getBoundingClientRect().bottom};
  });
  assert.ok(largeFont.bottom <= 320 && largeFont.footerBottom <= 320, JSON.stringify(largeFont));
  await page.setViewportSize({width:360,height:800});
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh') === '800px');
  // 模拟 overlay 键盘：只有 visualViewport 变高/偏移，layout viewport 不改变。
  const visual = await page.evaluate(() => {
    Object.defineProperty(visualViewport, 'height', {configurable:true, value:290});
    Object.defineProperty(visualViewport, 'offsetTop', {configurable:true, value:16});
    visualViewport.dispatchEvent(new Event('resize'));
    const el = document.querySelector('[data-shortcut-modal="shortcuts"]');
    el.querySelector('footer').scrollIntoView({block:'end'});
    const box = el.getBoundingClientRect();
    return {top:box.top,bottom:box.bottom,footerBottom:el.querySelector('footer').getBoundingClientRect().bottom};
  });
  assert.ok(visual.top >= 16 && visual.bottom <= 306 && visual.footerBottom <= 306, JSON.stringify(visual));
  await page.evaluate(() => { delete visualViewport.height; delete visualViewport.offsetTop; visualViewport.dispatchEvent(new Event('resize')); });
  await page.keyboard.press('Escape');
  await modal.waitFor({state:'detached'});
  await page.evaluate(() => { state.open=true; state.focusRequest++; render(); });
  await modal.waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.dataset.shortcutModal), 'shortcuts', '重开新输入节点仍抑制首次自动聚焦');
  const subsequent = await page.evaluate(() => {
    const input = document.querySelector('[data-shortcut-modal="shortcuts"] input');
    auditModules['@deepseek-ai/dsh-client-ui-primitives'].focusWithoutRing(input);
    return document.activeElement === input;
  });
  assert.equal(subsequent, true, '挂载完成后自动聚焦API不能永久失效');
  const cleanup = await page.evaluate(() => {
    disposers.reverse().forEach(dispose => dispose?.()); disposers.length=0;
    return {same:HTMLInputElement.prototype.focus===originalFocus,
      owns:Object.hasOwn(HTMLInputElement.prototype,'focus')===Boolean(originalFocusDescriptor),
      viewport:document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh')};
  });
  assert.deepEqual(cleanup, {same:true,owns:true,viewport:''});
  const sharedFocus = await page.evaluate(() => {
    const results=[];
    for(const shortcutFirst of [true,false]) {
      const local=[];
      const ctx={effect(run){local.push(run());}};
      const modelGuard=mobileRequire('./effects/model-menu-keyboard-guard.js');
      guard.installShortcutModalKeyboardGuard(ctx);
      modelGuard.installModelMenuKeyboardGuard(ctx);
      document.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerType:'touch'}));
      const menu=document.createElement('div');menu.dataset.menuMaterial='';
      const model=document.createElement('input');model.setAttribute('role','searchbox');menu.append(model);
      document.querySelector('[data-shortcut-modal="shortcuts"]').append(menu);
      document.activeElement?.blur();model.focus();
      const blockedWithBoth=document.activeElement!==model;
      local[shortcutFirst?0:1]();
      model.focus();
      const modelRuleRemaining=shortcutFirst?document.activeElement!==model:document.activeElement===model;
      let shortcutRuleRemaining=true;
      if(!shortcutFirst) {
        const auto=document.createElement('input');auto.setAttribute('data-modal-autofocus','');
        auto.setAttribute('data-dsh-automatic-focus','');menu.append(auto);auto.focus();
        shortcutRuleRemaining=document.activeElement!==auto;
      }
      local[shortcutFirst?1:0]();menu.remove();
      results.push({shortcutFirst,blockedWithBoth,modelRuleRemaining,shortcutRuleRemaining,
        originalRestored:HTMLInputElement.prototype.focus===originalFocus});
    }
    return results;
  });
  for(const row of sharedFocus)assert.deepEqual(row,{shortcutFirst:row.shortcutFirst,blockedWithBoth:true,
    modelRuleRemaining:true,shortcutRuleRemaining:true,originalRestored:true});
  const ownership = await page.evaluate(() => {
    guard.installShortcutModalKeyboardGuard(context);
    const inner=HTMLInputElement.prototype.focus;
    let calls=0;
    const outer=function(options){calls++; return inner.call(this,options);};
    HTMLInputElement.prototype.focus=outer;
    disposers.pop()();
    const preserved=HTMLInputElement.prototype.focus===outer;
    const input=document.querySelector('[data-shortcut-modal="shortcuts"] input');
    input.blur(); input.setAttribute('data-dsh-automatic-focus',''); input.focus();
    const focused=document.activeElement===input;
    if(originalFocusDescriptor)Object.defineProperty(HTMLInputElement.prototype,'focus',originalFocusDescriptor); else delete HTMLInputElement.prototype.focus;
    return {preserved,calls,focused};
  });
  assert.deepEqual(ownership,{preserved:true,calls:1,focused:true});
  await page.evaluate(() => guard.installShortcutModalKeyboardGuard(context));
  await page.setViewportSize({width:1200,height:800});
  await page.waitForFunction(() => HTMLInputElement.prototype.focus === originalFocus);
  assert.equal(await search.isVisible(),true,'桌面搜索不受影响');
  await page.setViewportSize({width:360,height:800});
  await page.waitForFunction(() => HTMLInputElement.prototype.focus !== originalFocus);
  await page.evaluate(() => disposers.pop()());
  const gestures = await page.evaluate(() => {
    root.unmount();
    const frame=document.createElement('div'); frame.dataset.mobileNav='frame'; frame.setAttribute('data-sidebar-collapsed','');
    frame.style.cssText='position:fixed;inset:0';
    frame.innerHTML='<aside style="width:280px;height:100%"><span id="stroke-target">gesture fixture</span></aside>';
    document.body.append(frame);
    const target=frame.querySelector('span'), drawer=frame.firstElementChild;
    const ctx={effect:context.effect,layout:{toggleSidebar(){frame.toggleAttribute('data-sidebar-collapsed');}}};
    const swipe=mobileRequire('./effects/sidebar-swipe.js');
    swipe.installSidebarSwipe(ctx,()=>{});
    const pointer=(type,x=20)=>target.dispatchEvent(new PointerEvent(type,{bubbles:true,pointerType:'touch',pointerId:1,clientX:x,clientY:100}));
    const touch=(cancelable=true,count=1)=>{
      const event=new Event('touchmove',{bubbles:true,cancelable});
      Object.defineProperty(event,'touches',{value:Array(count).fill({})}); target.dispatchEvent(event); return event.defaultPrevented;
    };
    const rows=[];
    for(const cause of ['blur','hidden','uncancelable','multitouch','pointercancel']) {
      pointer('pointerdown'); const owned=touch();
      if(cause==='blur')window.dispatchEvent(new Event('blur'));
      if(cause==='hidden'){Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));delete document.hidden;}
      if(cause==='uncancelable')touch(false);
      if(cause==='multitouch')touch(true,2);
      if(cause==='pointercancel')pointer('pointercancel');
      rows.push({cause,owned,released:!touch()});
    }
    pointer('pointerdown');pointer('pointermove',100);
    disposers.pop()();
    const disposed={released:!touch(),closed:frame.hasAttribute('data-sidebar-collapsed'),inlineTransform:drawer.style.transform};
    frame.remove();
    return {rows,disposed};
  });
  for(const row of gestures.rows)assert.deepEqual(row,{cause:row.cause,owned:true,released:true});
  assert.deepEqual(gestures.disposed,{released:true,closed:true,inlineTransform:''});
  const frozen = await page.evaluate(() => {
    guard.installShortcutModalKeyboardGuard(context);
    Object.defineProperty(HTMLInputElement.prototype,'focus',{configurable:false});
    disposers.pop()(); // 冻结发生在安装后，清理仍不得抛异常。
    guard.installShortcutModalKeyboardGuard(context); // 后续装载检测不可写，保持默认能力。
    disposers.pop()?.();
    const el=document.createElement('input');document.body.append(el);el.focus();
    return document.activeElement===el;
  });
  assert.equal(frozen,true);
  assert.deepEqual(fixture.errors, []);
  console.log(JSON.stringify({runtime,host:'actual rc2 ShortcutReference + Modal',cases,largeFont,visual,gestures,sharedFocus,
    checks:['initial focus','touch','Tab','search/clear','accessibility tree','reopen','later automatic focus','owner cleanup','same-width resize','visualViewport keyboard'],errors:fixture.errors},null,2));
} finally { await fixture.close(); }
