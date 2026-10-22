// Real current theme/mobile CSS + trusted Chromium touch. No account/device data.
// The synthetic host only toggles its marker; Android/React host timing is not measured.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {upstreamClientHash} from './apply-mobile-client-patches.mjs';

const baselineOnly = process.argv.includes('--baseline-only');
const runtime = process.env.DSHA_TEST_RUNTIME || JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json', 'utf8')).raw;
const oldSource = fs.readFileSync('tools/mobile-plugin-upstream/lib/client.js', 'utf8');
assert.equal(createHash('sha256').update(oldSource).digest('hex'), upstreamClientHash);
const currentSource = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
const output = process.env.DSHA_BACKDROP_EVIDENCE;
if (output) fs.mkdirSync(output, {recursive:true});
const results = [];

async function run(source, label, fixed) {
  const fixture = await browserFixture(runtime);
  const page = fixture.page;
  page.setDefaultTimeout(3000);
  await page.setViewportSize({width:390, height:844});
  const cdp = await page.context().newCDPSession(page);
  const touch = (type, x=350, y=450) => cdp.send('Input.dispatchTouchEvent', {
    type, touchPoints: ['touchEnd','touchCancel'].includes(type) ? [] : [{x,y,id:1}],
  });
  const snapshot = () => page.evaluate(() => {
    const el = document.querySelector('[data-mobile-nav="backdrop"]');
    const css = el && getComputedStyle(el);
    return {active:el?.matches(':active'), shadow:css?.boxShadow, opacity:css?.opacity,
      background:css?.backgroundColor, role:el?.getAttribute('role'),
      animation:css?.animationName, transition:css?.transitionProperty,
      token:getComputedStyle(document.body).getPropertyValue('--dsw-alias-interactive-bg-active').trim(),
      coarse:matchMedia('(pointer: coarse)').matches, hoverNone:matchMedia('(hover: none)').matches,
      closed:document.getElementById('frame').hasAttribute('data-sidebar-collapsed'),
      backdropCount:document.querySelectorAll('[data-mobile-nav="backdrop"]').length};
  });
  const capture = async name => {
    const bytes = await page.screenshot();
    if (output) fs.writeFileSync(path.join(output, `${label}-${name}.png`), bytes);
    return page.evaluate(async data => {
      const img = new Image(); img.src = 'data:image/png;base64,' + data;
      await img.decode(); const canvas = document.createElement('canvas');
      canvas.width=img.width; canvas.height=img.height;
      const context=canvas.getContext('2d'); context.drawImage(img,0,0);
      return Array.from(context.getImageData(350,450,1,1).data);
    }, bytes.toString('base64'));
  };
  try {
    await fixture.load('dsh-client-ui-theme', [], 'module.exports.audit = {base_css_default, design_platform_css_default};');
    await page.evaluate(() => {
      const theme=document.createElement('style');
      theme.textContent=Object.values(auditExports['@deepseek-ai/dsh-client-ui-theme'].audit).join('\n');
      document.head.append(theme);
    });
    await page.evaluate(() => {window.__ModuleLoader__={load({factory}) {factory(() => ({}));}};});
    await page.addScriptTag({content:source.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
    await page.evaluate(() => {
      document.body.dataset.dsDarkTheme='';
      for(const name of ['base','layout']) {
        const style=document.createElement('style');
        style.textContent=Object.values(mobileRequire(`./styles/${name}.css.js`)).filter(v=>typeof v==='string').join('\n');
        document.head.append(style);
      }
      const fixtureStyle=document.createElement('style');
      fixtureStyle.textContent='html,body,#root{margin:0;width:100%;height:100%;overflow:hidden}#frame{height:100%;width:100%;background:var(--dsw-alias-bg-base)}#drawer{height:100%}#center{grid-column:1;width:100%;height:100%;background:var(--dsw-alias-bg-base)}#ordinary{position:absolute;left:305px;top:550px;width:70px;height:50px;border:0;color:var(--dsw-alias-label-primary);background:transparent}';
      document.head.append(fixtureStyle);
      document.getElementById('root').innerHTML='<main id="frame" data-mobile-nav="frame" data-dsh-frame><aside id="drawer" data-pane="sidebar"><div>Drawer</div></aside><section id="center"><button id="ordinary">Ordinary</button></section><div data-shell-overlay></div></main>';
      window.cleanups=[]; window.toggles=0;
      window.ctx={effect(run){const dispose=run();if(dispose)cleanups.push(dispose);},
        layout:{toggleSidebar(){toggles++;document.getElementById('frame').toggleAttribute('data-sidebar-collapsed');overlay.ensure();}},
        sessions:{list:{getSnapshot(){return {byId:{}};},subscribe(){return ()=>{};}}}};
      window.overlayModule=mobileRequire('./effects/overlay-backdrop-fab.js');
      window.drawerClose=mobileRequire('./effects/drawer-close.js');
      window.overlay=overlayModule.createOverlayTask(key=>key,()=>drawerClose.toggleDrawer(ctx),null);
      overlay.ensure();
    });
    await page.waitForTimeout(350);
    const before = await snapshot();
    assert.equal(before.coarse,true); assert.equal(before.hoverNone,true);
    assert.equal(before.token,'#ffffff24', 'must use actual current dark theme token');
    assert.equal(before.role,'button', 'keep backdrop accessibility role');
    assert.notEqual(before.animation,'none'); assert.equal(before.transition,'opacity');
    const idlePixel = await capture('idle');
    await touch('touchStart'); await page.waitForTimeout(170);
    // Chromium touch dispatch activates this div during the compatibility tap
    // after touchEnd, not during the held touchStart; do not force pseudo states.
    await touch('touchEnd'); await page.waitForTimeout(30);
    const pressed=await snapshot(), pressedPixel=await capture('pressed');
    assert.equal(pressed.active,true,'CDP touch must produce real CSS :active');
    if(fixed) {
      assert.equal(pressed.shadow,'none');
      assert.deepEqual(pressedPixel,idlePixel,'press must keep dark backdrop pixels unchanged');
    } else {
      assert.notEqual(pressed.shadow,'none','negative control must reproduce actual inset shadow');
      assert.ok(pressedPixel[0]>idlePixel[0]+10,'negative control must visibly brighten the same pixel');
    }
    await page.waitForTimeout(170);
    assert.equal((await snapshot()).closed,false,'isolated paint probe does not fake a product close');

    // Dispose/re-arm is a supported task lifecycle; timers may not revive old nodes.
    await page.evaluate(()=>overlay.dispose()); await page.waitForTimeout(300);
    assert.equal((await snapshot()).backdropCount,0);
    await touch('touchStart',340,575); await page.waitForTimeout(170);
    await touch('touchEnd',340,575); await page.waitForTimeout(30);
    const ordinary=await page.locator('#ordinary').evaluate(el=>({active:el.matches(':active'),shadow:getComputedStyle(el).boxShadow}));
    assert.equal(ordinary.active,true); assert.notEqual(ordinary.shadow,'none','ordinary button feedback must remain');
    await page.waitForTimeout(170);

    const lifecycle={};
    if(fixed) {
      await page.evaluate(()=>{
        overlay.ensure();
        drawerClose.installOverlayInteractions(ctx);
        mobileRequire('./effects/sidebar-swipe.js').installSidebarSwipe(ctx);
      });
      await page.waitForTimeout(350);
      await touch('touchStart'); await page.waitForTimeout(80); await touch('touchEnd');
      await page.waitForFunction(()=>document.getElementById('frame').hasAttribute('data-sidebar-collapsed'));
      // Marker has closed, but 260ms removal is still pending: reopen that same task/node.
      await page.evaluate(()=>{window.reusedBackdrop=document.querySelector('[data-mobile-nav="backdrop"]');ctx.layout.toggleSidebar();});
      await page.waitForTimeout(650);
      assert.equal(await page.evaluate(()=>document.querySelector('[data-mobile-nav="backdrop"]')===reusedBackdrop),true);
      assert.equal((await snapshot()).closed,false); assert.equal((await snapshot()).backdropCount,1);
      assert.equal((await snapshot()).opacity,'1'); lifecycle.quickReopen=true;

      // Cancelled real touch follow must spring back; complete drag still closes.
      await touch('touchStart',180,450); await touch('touchMove',155,450); await touch('touchCancel');
      await page.waitForTimeout(450);
      assert.equal((await snapshot()).closed,false); lifecycle.cancel=true;
      await touch('touchStart',180,450);
      for(const x of [150,110,60,10]) {await touch('touchMove',x,450);await page.waitForTimeout(30);}
      await touch('touchEnd',10,450); await page.waitForTimeout(700);
      assert.equal((await snapshot()).closed,true,'real drawer follow must still close');
      assert.equal((await snapshot()).backdropCount,0); lifecycle.drag=true;

      await page.evaluate(()=>ctx.layout.toggleSidebar()); await page.waitForTimeout(450);
      await page.evaluate(()=>{drawerClose.toggleDrawer(ctx);for(const dispose of cleanups.splice(0))dispose();overlay.dispose();});
      await page.waitForTimeout(800);
      assert.equal((await snapshot()).backdropCount,0,'dispose during pending close must not resurrect a backdrop');
      lifecycle.disposeDuringCommit=true;
    }
    assert.deepEqual(fixture.errors,[]);
    results.push({label,before,pressed,idlePixel,pressedPixel,ordinary,lifecycle});
  } finally {await cdp.detach();await fixture.close();}
}

await run(oldSource,'upstream-negative',false);
if(!baselineOnly) await run(currentSource,'managed-candidate',true);
if(output) fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({baselineOnly,results},null,2)+'\n');
console.log(JSON.stringify({status:'PASS',baselineOnly,results}));
