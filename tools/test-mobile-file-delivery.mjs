// 执行实际随包移动模块和文件桥：浏览器字节、文件名、取消与迟到附件归属。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';
const fixture = await browserFixture();
try {
  const page=fixture.page;
  await page.evaluate(()=>{
    window.requests=[];window.notices=[];window.alert=value=>notices.push(value);
    window.DshaFiles={postMessage:value=>requests.push(JSON.parse(value))};
    window.__ModuleLoader__={load:({factory})=>factory(()=>({}))};
  });
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/files.js','utf8')});
  await page.evaluate(() => __dshaFilesBind({nonce:'fixture-document', generation:1, instance:'fixture-runtime'}));
  const header='__modules["components/file-share-controls.js"] = function (require, module, exports) {';
  const bundle=fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8')
    .replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')
    .replace(header,header+'\nexports.auditPlatformDeps=platformDeps;');
  await page.addScriptTag({content:bundle});
  const shared=await page.evaluate(async()=>{
    const core=mobileRequire('./components/file-share-core.js'),controls=mobileRequire('./components/file-share-controls.js');
    const bytes=new Uint8Array(2048);for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
    const file=new File([bytes],'实际附件-α.txt',{type:'text/plain'});
    const transfer=core.deliverFile(file,controls.auditPlatformDeps());
    const request=requests.shift();
    if(request.action!=='share')throw Error('mobile did not select the native File bridge');
    const read=new Uint8Array(await(await fetch(request.url)).arrayBuffer());
    if(!read.every((value,i)=>value===bytes[i]))throw Error('native share bytes differ');
    DshaFiles.onmessage({data:JSON.stringify({id:request.id,ok:true,detail:'chooser-opened'})});
    const outcome=await transfer;
    const cancelled=core.deliverFile(file,controls.auditPlatformDeps());const cancel=requests.shift();
    DshaFiles.onmessage({data:JSON.stringify({id:cancel.id,ok:false,detail:'AbortError'})});
    const cancelOutcome = await cancelled;
    const failed=core.deliverFile(file,controls.auditPlatformDeps()).then(()=>'unexpected',error=>error.message);
    const fail=requests.shift(); DshaFiles.onmessage({data:JSON.stringify({id:fail.id,ok:false,detail:'SHARE_UNAVAILABLE'})});
    const failure=await failed;
    const tooLarge=await core.deliverFile(new File([new Uint8Array(32*1024*1024+1)],'large.bin'),controls.auditPlatformDeps())
      .then(()=>'unexpected',error=>error.message);
    return {name:request.name,bytes:request.bytes,outcome,cancelled:cancelOutcome,failure,tooLarge,extraRequests:requests.length};
  });
  assert.equal(shared.name,'实际附件-α.txt');assert.equal(shared.bytes,2048);
  assert.equal(shared.outcome.kind,'shared');assert.equal(shared.cancelled.kind,'cancelled');assert.equal(shared.extraRequests,0);
  assert.equal(shared.failure,'SHARE_UNAVAILABLE'); assert.match(shared.tooLarge,/32 MiB/);
  await page.evaluate(()=>{
    document.getElementById('root').innerHTML='<div data-composer-card><input type="file" multiple hidden><button data-mobile-nav="file-upload">Attach</button></div>';
    window.picks=[];window.changed=[];window.currentSession='old';window.stops=[];
    window.hostInput=document.querySelector('input[type=file]');
    hostInput.addEventListener('click',event=>{event.preventDefault();picks.push({accept:hostInput.accept,multiple:hostInput.multiple});});
    hostInput.addEventListener('change',async()=>{
      const files=await Promise.all([...hostInput.files].map(async file=>({name:file.name,bytes:[...new Uint8Array(await file.arrayBuffer())]})));
      changed.push(files);window.changeResolve?.();
    });
    window.picker=mobileRequire('./effects/composer-file-picker.js');
    picker.installComposerFilePicker({effect:run=>stops.push(run()),locale:{bind:()=>key=>key},
      sessions:{list:{getSnapshot:()=>({byId:{[currentSession]:{id:currentSession,retainedBy:{mainView:1}}}})}}});
  });
  const trigger=page.locator('[data-mobile-nav="file-upload"]');
  await trigger.click();await page.getByRole('button',{name:'fileUploadImage',exact:true}).click();
  await trigger.click();await page.getByRole('button',{name:'fileUploadAttachment',exact:true}).click();
  assert.deepEqual(await page.evaluate(()=>picks),[{accept:'image/*',multiple:true},{accept:'',multiple:true}]);
  const selected=await page.evaluate(async()=>{
    const files=[new File([new Uint8Array([1,2,3])],'first.txt'),new File([new Uint8Array([4,5])],'second.txt')];
    const complete=new Promise(resolve=>window.changeResolve=resolve);
    DSHA.pickFile=async()=>files;
    await picker.openAttachmentPicker(key=>key,()=>currentSession);
    await Promise.race([complete,new Promise((_,reject)=>setTimeout(()=>reject(Error('host file-byte read did not finish')),2000))]);
    window.changeResolve=null;
    const accepted=changed.pop();
    let resolve;DSHA.pickFile=()=>new Promise(done=>resolve=done);
    const pending=picker.openAttachmentPicker(key=>key,()=>currentSession);
    currentSession='later';resolve(files);await pending;
    const staleChanges=changed.length;
    DSHA.pickFile=async()=>null;await picker.openAttachmentPicker(key=>key,()=>currentSession);
    stops.reverse().forEach(stop=>stop?.());
    return {accepted,staleChanges,pickerCount:picks.length,acceptRestored:hostInput.accept,notices};
  });
  assert.deepEqual(selected,{accepted:[{name:'first.txt',bytes:[1,2,3]},{name:'second.txt',bytes:[4,5]}],
    staleChanges:0,pickerCount:2,acceptRestored:'',notices:[]});
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',shared,selected,errors:[]},null,2));
} finally { await fixture.close(); }
