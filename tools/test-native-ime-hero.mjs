// Real pinned rc3 HeroShell + ConversationRoot CSS and current mobile CSS.
// Browser viewport resize stands in for native end resize; no Android device claims.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture = await browserFixture();
const asset = name => fs.readFileSync('app/src/main/assets/web-integration/' + name, 'utf8');
const seatHeightNegative = process.argv.includes('--seat-height-negative');
assert(process.argv.slice(2).every(value=>value==='--seat-height-negative'));
const mobile = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8');
const results = [];
const close = (value, expected, label) => assert.ok(Math.abs(value-expected)<1.1, label+': '+value+' != '+expected);
try {
  const page = fixture.page;
  for (const transport of ['webview','gecko']) {
    if (transport === 'gecko') { await page.reload(); await page.waitForFunction(()=>globalThis.auditModules); }
    await page.setViewportSize({width:393,height:840});
    await fixture.load('dsh-client-ui-conversation',[], 'module.exports.imeFixture={HeroShell, css:ConversationRoot_module_css_default};');
    await page.addScriptTag({content:mobile.replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
    await page.evaluate(() => {
      const style = document.createElement('style');
      style.textContent = mobileRequire('./styles/index.js').MOBILE_CSS;
      document.head.append(style);
    });
    await page.addStyleTag({content:'html,body,#root{height:100%;margin:0;overflow:hidden}#root{font-family:sans-serif} [data-mobile-nav="frame"]{height:100%}.ime-card{box-sizing:border-box;border:1px solid #888;border-radius:18px;margin:0 16px;padding:12px}.ime-card [data-composer-input]{min-height:44px;max-height:336px;overflow:auto;white-space:pre-wrap;outline:none}.ime-tools{height:28px} [data-ime-header]{min-height:50px;flex:none}'});
    await page.evaluate(() => {
      const React=auditModules.react.default||auditModules.react, ReactDOM=auditModules['react-dom'].default||auditModules['react-dom'];
      const {HeroShell,css:c}=auditExports['@deepseek-ai/dsh-client-ui-conversation'].imeFixture;
      const h=React.createElement;
      window.heroRoot=ReactDOM.createRoot(document.getElementById('root'));
      heroRoot.render(h('div',{'data-mobile-nav':'frame'},
        h('div',{className:c.root,'data-phase':'hero','data-ime-root':''},
          h('header',{'data-ime-header':''},'Header'),
          h('div',{className:c.body,'data-conversation-content':'','data-conversation-session':'fixture','data-content-phase':'hero'},
            h('div',{className:c.scrollBody,'data-conversation-scroll':''},
              h('div',{className:c.composerSeat,'data-composer-seat':''},
                h('div',{className:c.composerStack+' '+c.composerHero},
                  h(HeroShell,{t:key=>({'hero.headline':'有问题，尽管问','hero.preview':'预览版'}[key]||key),renderSlot:(name,props,options)=>options?.fallback||null}),
                  h('div',{className:c.heroWorkspaceRow},'DSHA / workspace'),
                  h('div',{className:'ime-card','data-composer-card':''},
                    h('div',{'data-composer-input':'',contentEditable:true,suppressContentEditableWarning:true},''),
                    h('div',{className:'ime-tools'},'+　工具　模型　发送')))))))));
      window.tick=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
      window.measure=()=>{
        const seat=document.querySelector('[data-composer-seat]'),headline=seat.querySelector('[class*="_headline"]');
        const input=seat.querySelector('[data-composer-input]'),r=seat.getBoundingClientRect();
        const card=input.closest('[data-composer-card]').getBoundingClientRect();
        return {top:r.top,bottom:r.bottom,height:r.height,headline:headline.getBoundingClientRect().top,
          inputTop:input.getBoundingClientRect().top,inputBottom:input.getBoundingClientRect().bottom,
          cardBottom:card.bottom,requiredHeight:card.bottom-r.top+seat.scrollTop,tail:r.bottom-card.bottom,
          scroll:seat.scrollHeight,client:seat.clientHeight,overflow:getComputedStyle(seat).overflowY,
          translate:getComputedStyle(seat).translate,rootHeight:document.querySelector('[data-ime-root]').getBoundingClientRect().height};
      };
    });
    await page.locator('[data-composer-input]').waitFor();
    await page.evaluate(async()=>{await tick();});
    const original=await page.evaluate(()=>measure());
    // Positive reproduction: official flex centering changes the whole group after a plain resize.
    await page.setViewportSize({width:393,height:540});
    await page.evaluate(async()=>{await tick();});
    const unowned=await page.evaluate(()=>measure());
    assert.ok(original.top-unowned.top>60,'official hero must reproduce the reported recenter');
    await page.setViewportSize({width:393,height:840});
    await page.evaluate(async()=>{await tick();});
    await page.evaluate(() => {
      window.binding={nonce:'b'.repeat(32),generation:7,instance:'hero-fixture'};
      window.acks=[]; window.DshaTheme={postMessage:text=>acks.push(JSON.parse(text))};
      window.portListener=undefined;
      window.__dshaStartupPort={onMessage:{addListener:fn=>{portListener=fn;}},postMessage:value=>{if(value.type==='presentation')acks.push(value.report);}};
      window.browser={runtime:{connectNative:()=>__dshaStartupPort}};
    });
    let ime=asset('ime.js');
    if(seatHeightNegative){
      const anchor='const naturalHeight = heroContentHeight(before);';
      assert.equal(ime.split(anchor).length,2,'negative control replaces exactly the former seat-height boundary');
      ime=ime.replace(anchor,'const naturalHeight = rule.style.maxHeight ? Math.max(before.height, seat.scrollHeight) : before.height;');
    }
    await page.addScriptTag({content:ime});
    await page.addScriptTag({content:asset('page.js')});
    if (transport === 'gecko') await page.addScriptTag({content:asset('content.js')});
    await page.evaluate(transport=>{
      if(transport==='gecko')portListener({type:'presentation-init',binding});else __dshaNativePresentation.install(binding);
      window.frameId=0;
      window.send=(height,phase='frame',epoch=1,extra={})=>{
        const packet={...binding,frameId:++frameId,epoch,width:innerWidth*2,height:height*2,phase,...extra};
        if(transport==='gecko')portListener({type:'ime-frame',request:packet});else __dshaNativePresentation.ime(packet);
      };
      document.querySelector('[data-composer-input]').focus();
    },transport);
    const baseline=await page.evaluate(()=>measure());
    close(baseline.tail,32,'official composerHero has real 32px layout tail outside visible card');
    // Disabled/no animation: the first native packet can arrive only after the browser resized.
    await page.setViewportSize({width:393,height:660});
    await page.evaluate(async()=>{send(660,'end');await tick();});
    close((await page.evaluate(()=>measure())).top,baseline.top,'first static packet keeps focus-before-resize anchor');
    await page.setViewportSize({width:393,height:840});
    await page.evaluate(async()=>{send(840,'end');await tick();});
    // No overlap: every actual frame retains exactly the pre-keyboard position.
    const frames=await page.evaluate(async()=>{
      const values=[];window.styleWrites=0;
      const observer=new MutationObserver(rows=>{styleWrites+=rows.filter(r=>r.type==='attributes').length;});
      observer.observe(document.documentElement,{attributes:true,subtree:true,attributeFilter:['style','class']});
      for(const height of [840,820,780,740,700,660,620,580,560,540,520]){send(height);await tick();values.push({visible:height,...measure()});}
      observer.disconnect();return {values,styleWrites};
    });
    for(const row of frames.values){
      close(row.top,Math.min(baseline.top,row.visible-8-row.requiredHeight),'minimal visible-card movement');
      close(row.headline-row.top,baseline.headline-baseline.top,'title stays in group');
      close(row.inputTop-row.top,baseline.inputTop-baseline.top,'input stays in group');
    }
    // The previous seat-height clamp moved a clear card at visible=580 and left 40px at 520.
    const clearCard=frames.values.find(row=>row.visible===580);
    close(clearCard.top,baseline.top,'only invisible tail is covered: keep original position');
    close(frames.values.at(-1).cardBottom,512,'visible card has exactly 8px keyboard clearance');
    assert.equal(frames.styleWrites,0,'hero frames must not wake inline style/class observers');
    // An end-state resize that does not cover the group must not recenter it at all.
    await page.evaluate(async()=>{send(660,'end');await tick();});
    await page.setViewportSize({width:393,height:660});
    await page.evaluate(async()=>{await tick();});
    close((await page.evaluate(()=>measure())).top,baseline.top,'unobscured end resize retains keyboard-before position');
    // Native end first, resize second.
    await page.evaluate(async()=>{send(520,'end');await tick();});
    await page.setViewportSize({width:393,height:520});
    await page.evaluate(async()=>{await tick();});
    const afterResize=await page.evaluate(()=>measure());
    close(afterResize.top,Math.min(baseline.top,520-8-afterResize.requiredHeight),'end then resize');
    close(afterResize.cardBottom,512,'end resize excludes outer 32px tail');
    close(afterResize.headline-afterResize.top,baseline.headline-baseline.top,'no title/composer separation');
    // Multi-line draft grows the group, preserving the same anchor unless now occluded.
    await page.evaluate(async()=>{document.querySelector('[data-composer-input]').textContent=Array.from({length:9},(_,i)=>'line '+i).join('\n');await tick();await tick();});
    const multiline=await page.evaluate(()=>measure());
    assert.ok(multiline.height>afterResize.height+50,'actual multiline draft grows');
    close(multiline.top,Math.max(50,Math.min(baseline.top,520-8-multiline.requiredHeight)),'multiline uses current visible content size');
    // A very short visible region gets a bounded scrollport inside this hero group.
    await page.evaluate(async()=>{send(200,'end');await tick();await tick();});
    await page.setViewportSize({width:393,height:200});
    await page.evaluate(async()=>{await tick();await tick();});
    const narrow=await page.evaluate(()=>measure());
    assert.equal(narrow.overflow,'auto');assert.ok(narrow.scroll>narrow.client+20);
    assert.ok(narrow.top>=49 && narrow.bottom<=193,'bounded hero remains in current safe region');
    const scrollReach=await page.evaluate(()=>{const seat=document.querySelector('[data-composer-seat]');seat.scrollTop=seat.scrollHeight;return {top:seat.scrollTop,inputBottom:seat.querySelector('[data-composer-input]').getBoundingClientRect().bottom,bottom:seat.getBoundingClientRect().bottom};});
    assert.ok(scrollReach.top>20);assert.ok(scrollReach.inputBottom<=scrollReach.bottom+1,'input can be reached by internal scrolling');
    // Shrinking text and hide: resize first, end second. Original pre-keyboard anchor returns.
    await page.evaluate(async()=>{document.querySelector('[data-composer-input]').textContent='';await tick();});
    await page.setViewportSize({width:393,height:840});
    await page.evaluate(async()=>{await tick();send(840,'end',2);await tick();await tick();});
    const hidden=await page.evaluate(()=>measure());
    close(hidden.top,baseline.top,'hide restores original anchor');
    assert.notEqual(hidden.overflow,'auto','temporary narrow scrollport retired');
    const dock=await page.evaluate(async()=>{
      const stack=document.querySelector('[class*="_composerStack"]');
      const dock=document.createElement('div');dock.setAttribute('data-composer-dock','');
      dock.style.height='64px';dock.innerHTML='<button style="display:none">hidden fixture tool</button>';
      stack.append(dock);send(520,'end',2);await tick();await tick();
      const empty=measure();
      dock.style.height='24px';dock.firstElementChild.style.cssText='display:block;height:24px';
      await tick();await tick();
      const visible={...measure(),dockBottom:dock.firstElementChild.getBoundingClientRect().bottom};
      dock.remove();send(840,'end',2);await tick();await tick();return {empty,visible};
    });
    close(dock.empty.cardBottom,512,'empty/hidden dock layout tail must not create phantom obstruction');
    close(dock.visible.dockBottom,512,'real visible tool outside card receives its own clearance');
    // Focus/page/rotation/session boundaries must drop their old geometry immediately.
    const boundaries=await page.evaluate(async()=>{
      const seat=document.querySelector('[data-composer-seat]'),input=seat.querySelector('[data-composer-input]');
      const retired=()=>!seat.hasAttribute('data-dsha-ime-seat');
      const values={};
      for(const name of ['blur','pagehide','orientationchange']){
        send(520,'end',++window.frameId);await tick();window.dispatchEvent(new Event(name));values[name]=retired();
      }
      send(520,'end',++window.frameId);await tick();document.dispatchEvent(new Event('dsha-session-open'));values.session=retired();
      input.blur();input.focus();send(840,'end',++window.frameId);await tick();
      const content=document.querySelector('[data-conversation-content]');
      document.querySelector('[data-ime-header]').style.minHeight='100px';
      content.setAttribute('data-conversation-session','replacement');await tick();
      values.replacement=Math.abs(measure().top-(innerHeight+100-measure().height)/2)<1.1;
      document.querySelector('[data-ime-header]').style.minHeight='50px';
      window.__dshaIme.cancel();values.cancel=retired();
      return values;
    });
    for(const [key,value] of Object.entries(boundaries))assert.equal(value,true,key);
    // Width rotation retires the former anchor; a new focus measures the current layout.
    await page.setViewportSize({width:600,height:600});
    await page.evaluate(async()=>{await tick();});
    assert.equal(await page.locator('[data-composer-seat]').getAttribute('data-dsha-ime-seat'),null);
    const rotated=await page.evaluate(async()=>{
      const input=document.querySelector('[data-composer-input]');input.blur();input.focus();
      const base=measure();send(400,'end',500);await tick();return {base,current:measure()};
    });
    close(rotated.current.top,Math.min(rotated.base.top,400-8-rotated.current.requiredHeight),'rotation acquires current visible geometry');
    await page.setViewportSize({width:393,height:840});
    await page.evaluate(async()=>{await tick();});
    // Enter a real chat phase on the same retained DOM: old hero anchor must not pin it.
    await page.evaluate(async()=>{
      const c=document.querySelector('[data-conversation-content]');c.setAttribute('data-content-phase','active');
      document.querySelector('[data-ime-root]').setAttribute('data-phase','active');
      const seat=document.querySelector('[data-composer-seat]');
      seat.style.position='absolute';seat.style.bottom='0';seat.style.width='100%';
      send(520,'frame',1000);await tick();
    });
    const chat=await page.evaluate(()=>measure());
    close(chat.bottom,520,'chat follows current keyboard after hero phase');
    results.push({transport,baseline,unowned,afterResize,multiline,narrow,scrollReach,hidden,dock,boundaries,rotated,chatBottom:chat.bottom,frameStyleWrites:frames.styleWrites});
  }
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',status:'PASS',scope:'pinned rc3 HeroShell + host/mobile CSS; native transport and resize fixtures; no Android device',results},null,2));
} finally {await fixture.close();}
