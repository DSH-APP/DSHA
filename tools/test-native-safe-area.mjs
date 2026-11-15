// Actual patched mobile CSS + native document-start script, with browser env()
// values deliberately left nonzero after the native viewport was inset.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {parseArgs} from 'node:util';
import {applyMobileClientPatches} from './apply-mobile-client-patches.mjs';
const {values:options}=parseArgs({options:{playwright:{type:'string'},'browsers-path':{type:'string'}},allowPositionals:false});
if(options['browsers-path'])process.env.PLAYWRIGHT_BROWSERS_PATH=path.resolve(options['browsers-path']);
const require = createRequire(import.meta.url);
const packagePath=options.playwright||process.env.DSHA_PLAYWRIGHT;
const playwright=require(packagePath?path.resolve(packagePath):'playwright');
const safeArea = fs.readFileSync('app/src/main/assets/web-integration/safe-area.js','utf8');
// Also exercise parser timing before <html> exists, and the top-frame boundary.
{
  let notify, disconnected = false;
  const root = {attrs:{},style:{values:{},setProperty(key,value){this.values[key]=value;}},
    setAttribute(key,value){this.attrs[key]=value;}};
  const document = {documentElement:null}, window = {}; window.top = window;
  const context = vm.createContext({document,window,MutationObserver:class {
    constructor(callback){notify=callback;} observe(target,options){assert.equal(target,document);assert.equal(options.childList,true);}
    disconnect(){disconnected=true;}
  }});
  vm.runInContext(safeArea,context);
  document.documentElement=root; notify();
  assert.equal(disconnected,true);
  assert.equal(root.attrs['data-dsha-safe-top'],'native');
  assert.deepEqual(Object.values(root.style.values),['0px','0px','0px','0px']);
  document.documentElement=null; window.top={}; notify=undefined;
  vm.runInContext(safeArea,context);
  assert.equal(notify,undefined,'a subframe cannot become a native inset owner');
}
const mobile = applyMobileClientPatches(fs.readFileSync('tools/mobile-plugin-upstream/lib/client.js'));
const browser = await playwright.chromium.launch({headless:true});
const results=[];
try {
  const page=await browser.newPage({viewport:{width:360,height:754},hasTouch:true});
  await page.setContent('<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"></head><body><div data-mobile-nav="frame"><main data-phase="conversation"><header><div class="fixture_headerLeading"></div><div class="fixture_titleRow"><span class="fixture_titleCluster" data-title>Conversation</span></div></header><div data-content>Messages</div></main></div><div data-probe></div></body></html>');
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Emulation.setSafeAreaInsetsOverride',{insets:{top:38,bottom:24,left:0,right:0}});
  await page.evaluate(()=>{window.__ModuleLoader__={load:({factory})=>factory(()=>({}))};});
  await page.addScriptTag({content:mobile.replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
  await page.evaluate(()=>{
    const style=document.createElement('style');style.textContent=mobileRequire('./styles/index.js').MOBILE_CSS;document.head.append(style);
    window.DSHA={downloadArtifact:()=>{}};
    window.disposeNativeFileActions=mobileRequire('./components/file-share-controls.js').installNativeFileActions();
    const sheet=document.createElement('div');sheet.dataset.dshaFileSheet='';
    sheet.innerHTML='<div>File action sheet</div>';document.body.append(sheet);
  });
  await page.addStyleTag({content:'html,body{height:100%;margin:0} [data-mobile-nav="frame"]{height:100%;display:grid}header{display:flex;align-items:center} [data-probe]{position:fixed;top:var(--dsha-safe-area-inset-top,env(safe-area-inset-top,0px));bottom:var(--dsha-safe-area-inset-bottom,env(safe-area-inset-bottom,0px));left:0;width:1px}'});
  const measure=()=>page.evaluate(()=>{
    const frame=document.querySelector('[data-mobile-nav="frame"]'),header=document.querySelector('header'),probe=document.querySelector('[data-probe]'),sheet=document.querySelector('[data-dsha-file-sheet]>div');
    return {paddingTop:getComputedStyle(frame).paddingTop,paddingBottom:getComputedStyle(frame).paddingBottom,
      headerTop:header.getBoundingClientRect().top,headerMargin:getComputedStyle(header).marginTop,
      probeTop:probe.getBoundingClientRect().top,probeBottom:probe.getBoundingClientRect().bottom,viewport:innerHeight,
      frameBottom:frame.getBoundingClientRect().bottom,scrollHeight:document.documentElement.scrollHeight,
      sheetPaddingBottom:getComputedStyle(sheet).paddingBottom};
  });
  const external=await measure();
  assert.equal(external.paddingTop,'38px','ordinary browser keeps its real status/cutout env inset');
  assert.equal(external.paddingBottom,'24px');
  assert.equal(external.probeTop,38);
  assert.equal(external.sheetPaddingBottom,'24px','browser file sheet keeps its navigation inset');
  await page.addScriptTag({content:safeArea});
  for (const size of [{width:360,height:754},{width:754,height:360},{width:754,height:210},{width:360,height:754}]) {
    await page.setViewportSize(size);
    const native=await measure();results.push({...size,...native});
    assert.equal(native.paddingTop,'0px','already-inset native viewport must not consume CSS safe-top again');
    assert.equal(native.paddingBottom,'0px','native navigation/IME boundary is not duplicated by CSS');
    assert.equal(native.probeTop,0,'fixed surfaces share the same inset owner');
    assert.equal(native.probeBottom,size.height);
    assert.equal(native.sheetPaddingBottom,'12px','native file sheet must not consume the navigation inset again');
    assert.equal(native.headerMargin,'0px','no negative header lift into system icons');
    assert.ok(native.headerTop>=0);
    assert.ok(native.frameBottom<=size.height+1);
    assert.ok(native.scrollHeight<=size.height+1,'safe padding cannot grow a second document viewport');
  }
  const manifest=JSON.parse(fs.readFileSync('app/src/main/assets/web-integration/manifest.json','utf8'));
  assert.ok(manifest.content_scripts.some(entry=>entry.run_at==='document_start' && entry.all_frames===false && entry.js[0]==='safe-area.js'),
    'Gecko must receive the same contract before its first content frame');
  const java=fs.readFileSync('app/src/main/java/com/deepseekharness/app/ui/WebPageScripts.java','utf8');
  assert.ok(java.includes('read(context, "web-integration/safe-area.js")'),'WebView compatibility path installs the same script');
  const output=path.resolve(process.env.DSHA_NATIVE_SAFE_AREA_OUTPUT||'app/build/native-safe-area.json');
  assert.ok(output.startsWith(path.resolve('app/build')+path.sep));
  fs.mkdirSync(path.dirname(output),{recursive:true});
  fs.writeFileSync(output,JSON.stringify({scope:'Chromium actual canonical mobile CSS, nonzero CDP safe-area values and document-start script; Gecko manifest/parser fixtures, no Android device pixels',external,results},null,2));
  console.log('PASS_NATIVE_SAFE_AREA: browser retains env, native viewport consumes safe area once, fixed surfaces/rotation/IME-sized bounds/parser/top-frame');
} finally {await browser.close();}
