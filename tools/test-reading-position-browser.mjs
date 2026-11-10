#!/usr/bin/env node
// Actual browser DOM offsets and event delivery; generated page only, no device/account.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
const require=createRequire(import.meta.url);
let playwright;
try {playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}
catch {playwright=require(path.join(process.env.USERPROFILE||'','.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
const source=fs.readFileSync(new URL('../app/src/main/assets/app-integration/client.js',import.meta.url),'utf8');
const server=http.createServer((_req,res)=>{res.setHeader('Content-Type','text/html');res.end('<!doctype html><html><body style="margin:0"></body></html>');});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try {
  const engine=process.env.DSHA_BROWSER||'chromium';browser=await playwright[engine].launch({headless:true});
  const page=await browser.newPage({viewport:{width:800,height:800}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/chat/a`);
  await page.evaluate(()=>{window.__ModuleLoader__={load:declaration=>{window.readingApi=declaration.factory();}};});
  await page.addScriptTag({content:source});
  const result=await page.evaluate(async()=>{
    let checks=0,current='a';const check=(value,label)=>{checks++;if(!value)throw Error(label);};
    const saved=[],subscribers=new Set();let rafs=0;
    const originalRaf=window.requestAnimationFrame;
    window.requestAnimationFrame=(...args)=>{rafs++;return originalRaf(...args);};
    const originalSet=Storage.prototype.setItem;
    Storage.prototype.setItem=function(key,value){if(key==='dsha.reading-position')saved.push(JSON.parse(value));return originalSet.call(this,key,value);};
    const ctx={sessions:{list:{getSnapshot:()=>({phase:'ready',byId:{a:{id:'a'},b:{id:'b'}}}),subscribe(fn){subscribers.add(fn);return()=>subscribers.delete(fn);}}},uiWorkspace:{openSession(id){current=id;}}};
    const stop=readingApi.installReadingPosition(ctx,{current:()=>current,invalidate(){}});
    document.dispatchEvent(new Event('pointerdown'));
    const slot=document.createElement('div');slot.dataset.slot='conversation';document.body.append(slot);
    const make=()=>{const node=document.createElement('div');node.style.cssText='height:300px;width:500px;overflow:scroll';const child=document.createElement('div');child.style.cssText='height:10000px;width:1000px';node.append(child);slot.append(node);return node;};
    let node=make();
    for(let i=0;i<1200;i++){node.scrollTop=i;node.scrollLeft=19;node.dispatchEvent(new Event('scroll'));}
    check(rafs===0,'integration creates no animation frames');
    const removedTop=node.scrollTop;node.remove();check(node.scrollTop===0,'real detached element loses its scroll offset');
    document.dispatchEvent(new Event('scrollend'));
    check(saved.at(-1).positions[0].top===removedTop && saved.at(-1).positions[0].left===19,'last scroll snapshot survives actual DOM removal');
    node=make();node.scrollTop=777;node.dispatchEvent(new Event('scroll'));
    current='b';history.pushState({},'', '/chat/b');node.scrollTop=0;for(const fn of subscribers)fn();
    check(saved.at(-1).id==='a'&&saved.at(-1).url==='/chat/a'&&saved.at(-1).positions[0].top===777,'reused node cannot erase old route position');
    node.scrollTop=333;node.dispatchEvent(new Event('scroll'));node.dispatchEvent(new Event('scrollend'));
    check(saved.at(-1).id==='b'&&saved.at(-1).positions[0].top===333,'new session owns new offsets');
    node.scrollTop=444;node.dispatchEvent(new Event('scroll'));node.scrollTop=445;
    window.dispatchEvent(new Event('pagehide'));check(saved.at(-1).positions[0].top===445,'pagehide samples the final live position');
    const writes=saved.length;node.scrollTop=555;node.dispatchEvent(new Event('scroll'));check(saved.length===writes,'hidden page callbacks cannot overwrite saved position');
    window.dispatchEvent(new Event('pageshow'));node.scrollTop=666;node.dispatchEvent(new Event('scroll'));node.dispatchEvent(new Event('scrollend'));
    check(saved.at(-1).positions[0].top===666,'BFCache pageshow resumes tracking');
    // Native scroll delivery, without synthetic scroll events, reaches the same checkpoint.
    node.scrollTop=888;await new Promise(resolve=>setTimeout(resolve,260));
    check(saved.at(-1).positions[0].top===888,'native scroll and idle fallback preserve exact offset');
    stop();const finalWrites=saved.length;node.scrollTop=999;node.dispatchEvent(new Event('scroll'));window.dispatchEvent(new Event('pageshow'));
    await new Promise(resolve=>setTimeout(resolve,230));check(saved.length===finalWrites&&subscribers.size===0,'dispose cancels all later work');
    Storage.prototype.setItem=originalSet;window.requestAnimationFrame=originalRaf;
    return {checks,integrationRafRequests:rafs,finalSaved:saved.at(-1),writes:saved.length};
  });
  assert.deepEqual(errors,[]);
  const report={status:'PASS',engine:await browser.version(),sourceSha256:createHash('sha256').update(source).digest('hex'),...result,
    scope:'Synthetic page using actual browser DOM, native scroll delivery, and current integration source; no tablet CPU or gesture latency claim.'};
  if(process.env.DSHA_READING_BROWSER_OUTPUT)fs.writeFileSync(process.env.DSHA_READING_BROWSER_OUTPUT,JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
} finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
