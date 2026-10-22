// 真实浏览器加载当前移动插件与两内核页面桥；不访问手机或模型服务。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture = await browserFixture();
const asset = name => fs.readFileSync('app/src/main/assets/web-integration/' + name, 'utf8');
const mobile = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8');
const results = [];
try {
  for (const transport of ['webview','gecko']) {
    const page = await fixture.browser.newPage({viewport:{width:360,height:800},hasTouch:true});
    await page.setContent('<!doctype html><html><head><style>html,body{margin:0;height:100%;overflow:hidden}.frame{height:100%;position:relative}header{position:absolute;top:0;height:50px}.abc_composerSeat{position:absolute;bottom:0;height:80px;width:100%}[data-composer-input]{height:40px}</style></head><body><div data-mobile-nav="frame" class="frame"><header>Header</header><div class="abc_composerSeat"><div data-composer-input contenteditable="true">fixture</div></div></div></body></html>');
    await page.evaluate(() => {
      window.__ModuleLoader__ = {load:({factory})=>factory(()=>({}))};
      window.reconciles = 0; window.resizes = 0; window.styleMutations = 0;
      window.disposers = []; window.acks = [];
      window.addEventListener('resize',()=>resizes++);
      new MutationObserver(records=>{styleMutations+=records.filter(row=>row.type==='attributes'&&['style','class'].includes(row.attributeName)).length;})
        .observe(document.documentElement,{subtree:true,attributes:true,attributeFilter:['style','class']});
      window.binding = {nonce:'1'.repeat(32),generation:7,instance:'actual-fixture'};
      window.DshaTheme = {postMessage:text=>acks.push(JSON.parse(text))};
      window.portListener = undefined;
      window.__dshaStartupPort = {onMessage:{addListener:fn=>{portListener=fn;}},postMessage:value=>{if(value.type==='presentation')acks.push(value.report);}};
      window.browser = {runtime:{connectNative:()=>__dshaStartupPort}};
    });
    await page.addScriptTag({content:mobile.replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
    await page.evaluate(() => {
      const ctx = {effect:run=>disposers.push(run())};
      mobileRequire('./effects/phone-chrome.js').installPhoneChrome(ctx);
      const adapter = mobileRequire('./effects/reconciler-adapter.js');
      adapter.addReconcilerTask({name:'native-ime-observation',scopes:['*','style','class'],ensure:()=>reconciles++,dispose(){}});
      adapter.installReconciler(ctx);
    });
    await page.addScriptTag({content:asset('ime.js')});
    await page.addScriptTag({content:asset('page.js')});
    if (transport === 'gecko') await page.addScriptTag({content:asset('content.js')});
    await page.evaluate(transport => {
      if(transport==='gecko') portListener({type:'presentation-init',binding});
      else __dshaNativePresentation.install(binding);
      document.querySelector('[data-composer-input]').focus();
      window.frameId=0;
      window.send=(height,phase='frame',epoch=1,extra={})=>{
        const packet={...binding,frameId:++frameId,epoch,width:720,height:height*2,phase,...extra};
        if(transport==='gecko')portListener({type:'ime-frame',request:packet});
        else __dshaNativePresentation.ime(packet);
      };
      window.tick=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    },transport);
    await page.evaluate(async()=>{send(800);await tick();await tick();});
    const mechanism = await page.evaluate(async()=>{
      const seat=document.querySelector('.abc_composerSeat'),header=document.querySelector('header');
      let rectReads=0;const rect=seat.getBoundingClientRect.bind(seat);seat.getBoundingClientRect=()=>{rectReads++;return rect();};
      reconciles=styleMutations=resizes=0;
      const top=header.getBoundingClientRect().top;
      for(let height=795;height>=500;height-=5){send(height);await tick();}
      const value={frames:60,reconciles,styleMutations,resizes,rectReads,headerDelta:header.getBoundingClientRect().top-top,
        bottom:rect().bottom,inlineTransform:seat.style.transform,inlineTranslate:seat.style.translate};
      // 同一真实上游观察器的正控：inline style 每帧确会唤醒其任务。
      let before=reconciles;
      for(let i=0;i<5;i++){seat.style.setProperty('--fixture-inline-write',String(i));await tick();}
      value.inlineControlReconciles=reconciles-before;
      seat.style.removeProperty('--fixture-inline-write');
      return value;
    });
    assert.equal(mechanism.reconciles,0);assert.equal(mechanism.styleMutations,0);assert.equal(mechanism.resizes,0);
    assert.ok(mechanism.rectReads<=2,JSON.stringify(mechanism));assert.equal(mechanism.headerDelta,0);
    assert.ok(Math.abs(mechanism.bottom-500)<0.6);assert.equal(mechanism.inlineTransform,'');assert.equal(mechanism.inlineTranslate,'');
    assert.ok(mechanism.inlineControlReconciles>=5,'真实上游 observer 的 inline 属性负控应唤醒任务');
    // native 末态 resize 与 end 包两种先后顺序均按同一绝对坐标收敛。
    await page.evaluate(async()=>{send(500,'end');await tick();});
    await page.setViewportSize({width:360,height:500});
    await page.evaluate(async()=>{await tick();});
    assert.ok(await page.locator('.abc_composerSeat').evaluate(el=>Math.abs(el.getBoundingClientRect().bottom-500)<0.6));
    assert.equal(await page.locator('.abc_composerSeat').getAttribute('data-dsha-ime-seat'),null);
    // hide 边界扩容仍保持旧绝对目标；随后只有 composer 随实际帧移动。
    await page.setViewportSize({width:360,height:800});
    await page.evaluate(async()=>{await tick();});
    assert.ok(await page.locator('.abc_composerSeat').evaluate(el=>Math.abs(el.getBoundingClientRect().bottom-500)<0.6));
    await page.evaluate(async()=>{for(let h=520;h<=800;h+=20){send(h,'frame',2);await tick();}send(800,'end',2);await tick();});
    assert.equal(await page.locator('.abc_composerSeat').getAttribute('data-dsha-ime-seat'),null);
    // 忙 renderer 的 end 排队、旧 epoch、错误身份、取消后晚到帧、用户样式与新绑定。
    const safety = await page.evaluate(async()=>{
      send(650,'frame',3);send(500,'end',3);await tick();
      const bottom=document.querySelector('.abc_composerSeat').getBoundingClientRect().bottom;
      send(200,'frame',2);send(200,'frame',3,{nonce:'2'.repeat(32)});await tick();
      const unchanged=document.querySelector('.abc_composerSeat').getBoundingClientRect().bottom;
      send(0,'cancel',4);await tick();
      send(200,'frame',3);await tick();
      const canceled=document.querySelector('.abc_composerSeat').getAttribute('data-dsha-ime-seat')===null;
      const seat=document.querySelector('.abc_composerSeat');seat.style.transform='translateX(4px)';
      send(400,'frame',5);await tick();send(0,'cancel',6);await tick();
      const retained=seat.style.transform==='translateX(4px)';seat.style.transform='';
      send(400,'frame',7);await tick();window.dispatchEvent(new Event('blur'));await tick();
      const blur=seat.getAttribute('data-dsha-ime-seat')===null;
      __dshaNativePresentation.install({...binding,nonce:'3'.repeat(32),generation:8,instance:'replacement'});
      send(200,'frame',8);await tick();
      return {bottom,unchanged,canceled,retained,blur,oldBindingIgnored:seat.getAttribute('data-dsha-ime-seat')===null,
        ackSupported:acks.some(row=>row.type==='ime-ack'&&row.supported===true),ackUnsupported:acks.some(row=>row.type==='ime-ack'&&row.supported===false)};
    });
    assert.ok(Math.abs(safety.bottom-500)<0.6);assert.equal(safety.unchanged,safety.bottom);
    for(const key of ['canceled','retained','blur','oldBindingIgnored','ackSupported','ackUnsupported'])assert.equal(safety[key],true,key);
    // 同一保留文档旋转或重新认领后，新 epoch 可以从新的帧序号开始；旧宿主不能覆盖它。
    await page.evaluate(async()=>{
      binding={nonce:'3'.repeat(32),generation:8,instance:'replacement'};
      send(700,'frame',100,{frameId:1});await tick();
    });
    assert.ok(await page.locator('.abc_composerSeat').evaluate(el=>Math.abs(el.getBoundingClientRect().bottom-700)<0.6));
    await page.setViewportSize({width:400,height:800});
    await page.evaluate(async()=>{send(0,'cancel',101);send(600,'frame',102,{width:800,frameId:1});await tick();});
    assert.ok(await page.locator('.abc_composerSeat').evaluate(el=>Math.abs(el.getBoundingClientRect().bottom-600)<0.6));
    assert.equal(await page.locator('header').evaluate(el=>el.getBoundingClientRect().top),0);
    const fallback = await page.evaluate(async()=>{
      send(0,'cancel',103);await tick();
      const seat=document.querySelector('.abc_composerSeat');
      send(400,'frame',103);
      __dshaNativePresentation.install({...binding,generation:9,instance:'same-nonce-new-generation'});
      await tick();
      const fullBindingReset=!seat.hasAttribute('data-dsha-ime-seat');
      binding={...binding,generation:9,instance:'same-nonce-new-generation'};
      seat.querySelector('[data-composer-input]').blur();
      send(400,'frame',104);await tick();
      const noComposer=acks.at(-1).type==='ime-ack'&&acks.at(-1).supported===false
        &&!seat.hasAttribute('data-dsha-ime-seat');
      seat.querySelector('[data-composer-input]').focus();send(400,'frame',105);await tick();
      seat.style.translate='7px 9px';send(350,'frame',105);await tick();
      send(0,'cancel',106);await tick();
      return {fullBindingReset,noComposer,userTranslatePreserved:seat.style.translate==='7px 9px'};
    });
    assert.deepEqual(fallback,{fullBindingReset:true,noComposer:true,userTranslatePreserved:true});
    results.push({transport,mechanism,safety,fallback});
    await page.close();
  }
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',status:'PASS',results},null,2));
} finally { await fixture.close(); }
