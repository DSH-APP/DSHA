import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
let api;
vm.runInNewContext(await readFile(new URL('../app/src/main/assets/app-integration/client.js',import.meta.url),'utf8'),
  {window:{__ModuleLoader__:{load:d=>{api=d.factory()}}},Blob,File,Promise,Date,Math,Map,Array,JSON,Error});
const file = new File(['original-image'],'a.png',{type:'image/png'});
const record = {id:'session',revision:'saved',files:[{blob:file,name:file.name,type:file.type,lastModified:0}],bytes:file.size};
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
