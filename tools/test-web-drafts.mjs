import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
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
  const db={transaction(name,mode){
    const tx={objectStore(){return {
      get(){const req={};release=()=>{req.result=saved;req.onsuccess()};return req},
      getAll(){const req={};queueMicrotask(()=>{req.result=[];req.onsuccess();queueMicrotask(()=>tx.oncomplete())});return req},
      put(v){writes.push(v)},delete(id){writes.push({deleted:id})}
    }},abort(){queueMicrotask(()=>tx.onabort())}};
    return tx;
  }};
  const conversation={attachments:new Map(),resolveDraftAttachments(ids){return ids.map(id=>this.attachments.get(id))},createDrafts(sessionId,files){return files.map((file,i)=>{const a={kind:'image',id:'restored-'+i,file};this.attachments.set(a.id,a);return a})},releaseDraftAttachment(id){this.attachments.delete(id)}};
  const shell={attachmentIds:[],state:{getSnapshot(){return {attachmentIds:shell.attachmentIds}},subscribe(fn){listeners.add(fn);return()=>listeners.delete(fn)}},notify:(...args)=>notices.push(args),actions:{addAttachments(ids){shell.attachmentIds.push(...ids);for(const fn of listeners)fn();return true}}};
  return {db,storage,conversation,shell,writes,notices,release:()=>release()};
}
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
