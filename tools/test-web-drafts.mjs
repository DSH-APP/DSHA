import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import {createRequire} from 'node:module';
let api;
const sandbox={window:{__ModuleLoader__:{load:d=>{api=d.factory()}}},Blob,File,Promise,Date,Math,Map,Set,Array,JSON,Error};
vm.createContext(sandbox);
vm.runInContext(await readFile(new URL('../app/src/main/assets/app-integration/client.js',import.meta.url),'utf8'),sandbox);
const file = new File(['original-image'],'a.png',{type:'image/png'});
const record = {id:'session',revision:'saved',files:[{blob:file,name:file.name,type:file.type,lastModified:0}],bytes:file.size};
test('alpha.2 使用驻留 Session binding，不能遍历 WeakMap 或激活冷会话',()=>{
 const a={},b={},shellA={},shellB={},calls=[];
 const ctx={sessions:{list:{getSnapshot:()=>({byId:{a:{id:'a'},b:{id:'b'},cold:{id:'cold'}}})},binding:id=>id==='a'?{ctx:a}:id==='b'?{ctx:b}:undefined},
 conversation:{input:{shells:new WeakMap(),for(context){calls.push(context);return context===a?shellA:shellB;}}}};
 const inputs=api.residentInputs(ctx);assert.equal(inputs.size,2);assert.equal(inputs.get('a'),shellA);assert.equal(inputs.get('b'),shellB);assert.deepEqual(calls,[a,b]);
});
test('阅读位置把一帧内的滚动合并，并延迟一次写入',()=>{
  class TestElement {
    constructor(slot,parent=null){this.slot=slot;this.parentElement=parent;this.children=[];this.scrollTop=12;this.scrollLeft=0;if(parent)parent.children.push(this)}
    hasAttribute(name){return name==='data-slot'&&this.slot!==null}
    getAttribute(name){return name==='data-slot'?this.slot:null}
  }
  const body=new TestElement(null),scroller=new TestElement('messages',body),listeners=new Map(),windowListeners=new Map();
  let writes=0,next=1;const rafs=new Map(),timeouts=new Map(),intervals=new Map();
  const document={body,scrollingElement:body,hidden:false,readyState:'complete',querySelectorAll:()=>[],
    addEventListener(type,fn,options){listeners.set(type,{fn,options})},removeEventListener(){}};
  const pageWindow=sandbox.window;
  pageWindow.addEventListener=(type,fn)=>windowListeners.set(type,fn);pageWindow.removeEventListener=()=>{};
  Object.assign(sandbox,{document,Element:TestElement,location:{pathname:'/chat',search:''},
    localStorage:{getItem:()=>null,setItem(){writes++}},
    requestAnimationFrame:fn=>{const id=next++;rafs.set(id,fn);return id},cancelAnimationFrame:id=>rafs.delete(id),
    setTimeout:fn=>{const id=next++;timeouts.set(id,fn);return id},clearTimeout:id=>timeouts.delete(id),
    setInterval:fn=>{const id=next++;intervals.set(id,fn);return id},clearInterval:id=>intervals.delete(id)});
  const ctx={sessions:{list:{getSnapshot:()=>({phase:'ready',byId:{session:{id:'session',retainedBy:{mainView:1}}}})}},uiWorkspace:{openSession(){}}};
  const stop=api.installReadingPosition(ctx);
  listeners.get('pointerdown').fn({});
  for(let i=0;i<24;i++)listeners.get('scroll').fn({target:scroller});
  assert.equal(writes,0);assert.equal(rafs.size,1);
  assert.equal(listeners.get('scroll').options.capture,true);
  assert.equal(listeners.get('scroll').options.passive,true);
  [...rafs.values()][0]();
  assert.equal(writes,0);assert.equal(timeouts.size,1);
  [...timeouts.values()][0]();
  assert.equal(writes,1);
  stop();
});
function fixture(saved = record) {
  let release, writes=[], listeners=new Set(), notices=[];
  const storage = new Map([['dsha.images.revision:session','saved']]);
  storage.getItem=k=>storage.get(k)??null;storage.setItem=(k,v)=>storage.set(k,v);
  storage.removeItem=k=>storage.delete(k);storage.key=i=>Array.from(storage.keys())[i]??null;
  Object.defineProperty(storage,'length',{get:()=>storage.size});
  const db={transaction(name,mode){
    const tx={objectStore(){return {
      get(){const req={};release=(error)=>{if(error){req.error=error;req.onerror();}else{req.result=saved;req.onsuccess();}};return req},
      getAll(){const req={};queueMicrotask(()=>{req.result=[];req.onsuccess();queueMicrotask(()=>tx.oncomplete())});return req},
      put(v){writes.push(v)},delete(id){writes.push({deleted:id})}
    }},abort(){queueMicrotask(()=>tx.onabort())}};
    return tx;
  }};
  const conversation={attachments:new Map(),resolveDraftAttachments(ids){return ids.map(id=>this.attachments.get(id))},createDrafts(sessionId,files){return files.map((file,i)=>{const a={kind:'image',id:'restored-'+i,file};this.attachments.set(a.id,a);return a})},releaseDraftAttachment(id){this.attachments.delete(id)}};
  const shell={attachmentIds:[],state:{getSnapshot(){return {attachmentIds:shell.attachmentIds}},subscribe(fn){listeners.add(fn);return()=>listeners.delete(fn)}},notify:(...args)=>notices.push(args),actions:{addAttachments(ids){shell.attachmentIds.push(...ids);for(const fn of listeners)fn();return true}}};
  return {db,storage,conversation,shell,writes,notices,release:error=>release(error)};
}
test('旧阅读位置令牌被实际迁移，新位置不持久化任何查询串',()=>{
  const handlers=new Map(),timers=[],writes=[];
  const previous=JSON.stringify({id:'session',url:'/chat?token=fixture-private&workspace=x',positions:[]});
  const document={body:{},scrollingElement:{},hidden:false,readyState:'complete',querySelectorAll:()=>[],
    addEventListener:(type,fn)=>handlers.set(type,fn),removeEventListener:()=>{}};
  sandbox.window.addEventListener=()=>{};sandbox.window.removeEventListener=()=>{};
  Object.assign(sandbox,{document,Element:class {},location:{pathname:'/chat',search:'?token=current-private'},
    localStorage:{getItem:()=>previous,setItem:(_key,value)=>writes.push(value)},
    requestAnimationFrame:()=>1,cancelAnimationFrame:()=>{},setInterval:()=>1,clearInterval:()=>{},
    setTimeout:fn=>{timers.push(fn);return timers.length},clearTimeout:()=>{}});
  const stop=api.installReadingPosition({sessions:{list:{}}}, {current:()=> 'session'});
  assert.equal(writes.length,1);
  assert.equal(JSON.parse(writes[0]).url,'/chat');
  assert.ok(!writes[0].includes('fixture-private'));
  handlers.get('pointerdown')();
  timers[0]();
  assert.ok(writes.every(value=>!value.includes('private')&&!value.includes('?')));
  stop();
});
test('revision mismatch, wrong MIME and excessive size are not restored',()=>{
  assert.equal(api.usable(record,'saved'),true);
  assert.equal(api.usable(record,'removed'),false);
  assert.equal(api.usable({...record,files:[{...record.files[0],type:'text/html'}]},'saved'),false);
  const huge=new Blob(['x']);Object.defineProperty(huge,'size',{value:256*1024*1024+1});
  assert.equal(api.usable({...record,files:[{...record.files[0],blob:huge}]},'saved'),false);
});
test('unchanged empty input restores exact bytes without sending',async()=>{
  const f=fixture();const pending=api.watchDraft(f.db,f.conversation,'session',f.shell,()=>true,f.storage);f.release();
  const off=await pending;await new Promise(queueMicrotask);
  assert.equal(f.shell.attachmentIds.length,1);
  assert.equal(await f.conversation.resolveDraftAttachments(f.shell.attachmentIds)[0].file.text(),'original-image');
  assert.equal(f.notices.length,0);off();
});
test('an image added during database loading wins over the saved image',async()=>{
  const f=fixture();const pending=api.watchDraft(f.db,f.conversation,'session',f.shell,()=>true,f.storage);
  f.conversation.attachments.set('new',{kind:'image',id:'new',file:new File(['new-image'],'new.png',{type:'image/png'})});
  f.shell.actions.addAttachments(['new']);f.release();const off=await pending;await new Promise(queueMicrotask);
  assert.deepEqual(f.shell.attachmentIds,['new']);assert.equal(await f.writes[0].files[0].blob.text(),'new-image');off();
});
test('disposed session cannot restore an attachment after its delayed read',async()=>{
  const f=fixture();let alive=true;const pending=api.watchDraft(f.db,f.conversation,'session',f.shell,()=>alive,f.storage);
  alive=false;f.release();await pending;assert.equal(f.shell.attachmentIds.length,0);assert.equal(f.writes.length,0);
});
test('synchronous tombstone prevents old attachments from returning after interrupted commit',async()=>{
  const f=fixture();f.storage.setItem('dsha.images.revision:session','deleted-before-crash');
  const pending=api.watchDraft(f.db,f.conversation,'session',f.shell,()=>true,f.storage);f.release();const off=await pending;
  assert.equal(f.shell.attachmentIds.length,0);off();
});
test('failed load or cross-page revision change never replaces a saved record with an empty draft',async()=>{
  for(const mode of ['read-failed','new-revision']){
    const f=fixture();const pending=api.watchDraft(f.db,f.conversation,'session',f.shell,()=>true,f.storage);
    if(mode==='read-failed')f.release(new Error('load failed'));
    else{f.storage.setItem('dsha.images.revision:session','other-page-pending');f.release();}
    const off=await pending;await new Promise(queueMicrotask);
    assert.equal(f.writes.length,0);assert.equal(f.shell.attachmentIds.length,0);
    assert.equal(f.storage.getItem('dsha.images.revision:session'),mode==='read-failed'?'saved':'other-page-pending');off();
  }
});

test('真实Chromium IndexedDB：有限GC、引用保护、容量和跨页删除证明',
 {skip:process.argv.includes('--unit-only')?'Explicit fast VM-only scope; native store is tested by the full browser run':false,timeout:60000},async t=>{
  const require=createRequire(import.meta.url);let playwright;
  try{playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}
  catch{playwright=require(path.join(process.env.USERPROFILE||'','.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
  const server=http.createServer(async(req,res)=>{
    if(req.url.startsWith('/api/mobile-nav.session.delete')){
      let body='';for await(const chunk of req)body+=chunk;
      const {sessionId}=JSON.parse(body);res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify({ok:true,deleted:sessionId}));return;
    }
    res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>Owned draft fixture</title>');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser,context;const url='http://127.0.0.1:'+server.address().port;
  try {
  browser=await playwright.chromium.launch({headless:true,executablePath:process.env.DSHA_CHROME});
  context=await browser.newContext();
  const source=await readFile(new URL('../app/src/main/assets/app-integration/client.js',import.meta.url),'utf8');
  const page=await context.newPage();await page.goto(url);
  await page.evaluate(()=>{window.__ModuleLoader__={load:d=>window.api=d.factory()};});
  await page.addScriptTag({content:source});
  await page.evaluate(()=>{
    window.make=(id,size,revision='old')=>({id,revision,bytes:size,files:[{blob:new Blob(['x'.repeat(size)],{type:'image/png'}),name:id+'.png',type:'image/png',lastModified:0}]});
    window.open=()=>new Promise((resolve,reject)=>{const r=indexedDB.open('native-'+Math.random(),1);r.onupgradeneeded=()=>r.result.createObjectStore('drafts',{keyPath:'id'});r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
    window.seed=(db,rows)=>new Promise((resolve,reject)=>{const tx=db.transaction('drafts','readwrite');for(const row of rows)tx.objectStore('drafts').put(row);tx.oncomplete=resolve;tx.onabort=()=>reject(tx.error);});
    window.rows=db=>new Promise((resolve,reject)=>{const r=db.transaction('drafts').objectStore('drafts').getAll();r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
    window.save=(db,row,options={})=>api.writeDraft(db,row,()=>row.revision,{storage:localStorage,limit:80,...options});
    window.key=id=>'dsha.images.revision:'+id;
    window.ref=(id,state='active')=>localStorage.setItem('dsha.images.lease:other-'+id,JSON.stringify({owner:'DSHA_IMAGE_DRAFT_V1',id,state}));
  });
    await t.test('known stale revision releases actual Blob records; valid other draft remains byte-identical',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('stale',50),make('valid',20)]);localStorage.setItem(key('stale'),'newer');localStorage.setItem(key('valid'),'old');await save(db,make('writer',30,'new'));const result=await rows(db);db.close();return {ids:result.map(r=>r.id),valid:await result.find(r=>r.id==='valid').files[0].blob.text()};});
      assert.deepEqual(value.ids,['valid','writer']);assert.equal(value.valid,'x'.repeat(20));
    });
    for(const state of ['active','pending'])await t.test(`${state} other-page lease prevents stale record GC and capacity overwrite`,async()=>{
      const value=await page.evaluate(async state=>{localStorage.clear();const db=await open();await seed(db,[make('held',60)]);localStorage.setItem(key('held'),'newer');ref('held',state);let error;try{await save(db,make('writer',30,'new'));}catch(e){error=e.message;}const result=await rows(db);db.close();return {error,ids:result.map(r=>r.id),bytes:await result[0].files[0].blob.text()};},state);
      assert.equal(value.error,'DRAFT_CAPACITY_LIMIT');assert.deepEqual(value.ids,['held']);assert.equal(value.bytes,'x'.repeat(60));
    });
    await t.test('missing revision is unknown, not proof of an orphan',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('unknown-revision',60)]);let error;try{await save(db,make('writer',30,'new'));}catch(e){error=e.message;}const result=await rows(db);db.close();return {error,ids:result.map(r=>r.id)};});
      assert.equal(value.error,'DRAFT_CAPACITY_LIMIT');assert.deepEqual(value.ids,['unknown-revision']);
    });
    await t.test('unknown/forged byte declaration stays intact and does not provide false capacity',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();const row=make('unknown',60);row.bytes=0;await seed(db,[row]);localStorage.setItem(key('unknown'),'different');let error;try{await save(db,make('writer',30,'new'));}catch(e){error=e.message;}const result=await rows(db);db.close();return {error,ids:result.map(r=>r.id),data:await result[0].files[0].blob.text()};});
      assert.equal(value.error,'DRAFT_CAPACITY_UNKNOWN');assert.deepEqual(value.ids,['unknown']);assert.equal(value.data,'x'.repeat(60));
    });
    await t.test('proof read failure aborts the real transaction before deleting any candidate',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('stale',50)]);localStorage.setItem(key('stale'),'newer');const broken={get length(){return localStorage.length;},key:i=>localStorage.key(i),getItem:k=>{if(k===key('stale'))throw Error('read denied');return localStorage.getItem(k);}};let error;try{await save(db,make('writer',30,'new'),{storage:broken});}catch(e){error=e.message;}const result=await rows(db);db.close();return {error,ids:result.map(r=>r.id)};});
      assert.equal(value.error,'read denied');assert.deepEqual(value.ids,['stale']);
    });
    await t.test('unknown lease and same-session other-page reference both preserve the actual previous row',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('writer',60,'old')]);localStorage.setItem(key('writer'),'old');ref('writer');let other;try{await save(db,make('writer',20,'new'));}catch(e){other=e.message;}localStorage.clear();localStorage.setItem('dsha.images.lease:unknown','{broken');let unknown;try{await save(db,make('writer',20,'new'));}catch(e){unknown=e.message;}const result=await rows(db);db.close();return {other,unknown,revision:result[0].revision,bytes:await result[0].files[0].blob.text()};});
      assert.equal(value.other,'DRAFT_OTHER_PAGE_REFERENCE');assert.ok(value.unknown);assert.equal(value.revision,'old');assert.equal(value.bytes,'x'.repeat(60));
    });
    await t.test('queued stale save cannot delete/overwrite a newer record; explicit own empty save can release capacity',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('writer',60,'old')]);localStorage.setItem(key('writer'),'first');const pending=api.writeDraft(db,make('writer',20,'first'),()=>localStorage.getItem(key('writer')),{storage:localStorage,limit:80});localStorage.setItem(key('writer'),'second');await pending;const kept=(await rows(db))[0];localStorage.setItem(key('writer'),'empty');await api.writeDraft(db,{id:'writer',revision:'empty',files:[],bytes:0},()=>localStorage.getItem(key('writer')),{storage:localStorage,limit:80});const left=(await rows(db)).length;db.close();return {keptRevision:kept.revision,keptBytes:await kept.files[0].blob.text(),left};});
      assert.equal(value.keptRevision,'old');assert.equal(value.keptBytes,'x'.repeat(60));assert.equal(value.left,0);
    });
    await t.test('one GC transaction removes at most32 records and oversize store fails closed',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();const initial=Array.from({length:40},(_,n)=>make('stale-'+n,1));await seed(db,initial);for(const row of initial)localStorage.setItem(key(row.id),'newer');await save(db,make('writer',1,'new'),{limit:80});const remaining=await rows(db);db.close();const tooMany=await open();await seed(tooMany,Array.from({length:513},(_,n)=>make('row-'+n,1)));let error;try{await save(tooMany,make('writer',1,'new'));}catch(e){error=e.message;}const count=(await rows(tooMany)).length;tooMany.close();return {remaining:remaining.length,error,count};});
      assert.equal(value.remaining,9);assert.equal(value.error,'DRAFT_RECORD_LIMIT');assert.equal(value.count,513);
    });
    await t.test('real cross-page lease preserves persisted/current-upload Blob bytes',async()=>{
      const other=await context.newPage();await other.goto(url);
      const value=await page.evaluate(async()=>{localStorage.clear();window.shared=await open();await seed(shared,[make('other-page',60)]);localStorage.setItem(key('other-page'),'newer');const borrowed=(await rows(shared))[0].files[0].blob;window.borrowed=borrowed;return shared.name;});
      await other.evaluate(async name=>{window.db=await new Promise(resolve=>{const r=indexedDB.open(name);r.onsuccess=()=>resolve(r.result);});localStorage.setItem('dsha.images.lease:other-page',JSON.stringify({owner:'DSHA_IMAGE_DRAFT_V1',id:'other-page',state:'pending'}));window.upload=await new Promise(resolve=>{const r=db.transaction('drafts').objectStore('drafts').get('other-page');r.onsuccess=()=>resolve(r.result.files[0].blob);});},value);
      const state=await page.evaluate(async()=>{let error;try{await save(shared,make('writer',30,'new'));}catch(e){error=e.message;}return {error,ids:(await rows(shared)).map(r=>r.id)};});
      assert.equal(state.error,'DRAFT_CAPACITY_LIMIT');assert.deepEqual(state.ids,['other-page']);assert.equal(await other.evaluate(()=>upload.text()),'x'.repeat(60));
      await other.evaluate(()=>{db.close();});await other.close();await page.evaluate(()=>shared.close());
    });
    await t.test('successful exact deletion binds revision; changed revision during request gets no proof',async()=>{
      const value=await page.evaluate(async()=>{localStorage.clear();const db=await open();await seed(db,[make('deleted',60)]);localStorage.setItem(key('deleted'),'old');const off=api.installDraftDeletionProof();await fetch('/api/mobile-nav.session.delete',{method:'POST',body:JSON.stringify({sessionId:'deleted'})});for(let n=0;n<20&&!localStorage.getItem('dsha.images.deleted:deleted');n++)await new Promise(r=>setTimeout(r,5));const proof=JSON.parse(localStorage.getItem('dsha.images.deleted:deleted'));await save(db,make('writer',30,'new'));const result=await rows(db);off();db.close();return {proof,ids:result.map(r=>r.id)};});
      assert.equal(value.proof.revision,'old');assert.deepEqual(value.ids,['writer']);
      const race=await page.evaluate(async()=>{localStorage.clear();localStorage.setItem(key('racing'),'before');const off=api.installDraftDeletionProof();const response=fetch('/api/mobile-nav.session.delete',{method:'POST',body:JSON.stringify({sessionId:'racing'})});localStorage.setItem(key('racing'),'concurrent-pending');await response;await new Promise(r=>setTimeout(r,20));const proof=localStorage.getItem('dsha.images.deleted:racing');off();return proof;});assert.equal(race,null);
    });
  }finally{await context?.close();await browser?.close();await new Promise(resolve=>server.close(resolve));}
 });
