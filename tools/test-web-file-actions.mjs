// 实际随包 JS 在浏览器中传输字节；原生端结果由夹具控制，不冒充 Android 分享/SAF 验收。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture = await browserFixture();
try {
  const page = fixture.page;
  await page.evaluate(() => {
    window.requests = []; window.notices = [];
    window.alert = text => notices.push(text);
    window.DshaFiles = {postMessage: text => requests.push(JSON.parse(text))};
  });
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/files.js','utf8')});
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/blob.js','utf8')});
  const result = await page.evaluate(async () => {
    const bytes = new Uint8Array(50 * 1024);
    for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
    const file = new File([bytes], '报告-99.md', {type:'text/markdown'});
    const shared = DSHA.shareFile(file);
    const share = requests.shift();
    const copy = new Uint8Array(await (await fetch(share.url)).arrayBuffer());
    if(!copy.every((n,i)=>n===bytes[i]))throw Error('share bytes differ');
    DshaFiles.onmessage({data:JSON.stringify({id:share.id,ok:true,detail:'chooser-opened'})});
    const shareResult = await shared;
    const original = URL.createObjectURL(file);
    const a = document.createElement('a');a.href=original;a.download=file.name;
    document.body.append(a);a.click();a.remove();
    await new Promise(resolve=>setTimeout(resolve,40));
    const save = requests.shift();
    URL.revokeObjectURL(original);
    if(!save)throw Error('anchor was not intercepted');
    const transfer = await new Promise((resolve,reject)=>{
      const ports = new MessageChannel(), chunks=[];let sequence=0;
      const timeout=setTimeout(()=>reject(Error('blob timeout')),2000);
      ports.port1.onmessage = event => {
        const value=JSON.parse(event.data);
        if(value.type==='chunk') {
          if(value.sequence!==sequence++)return reject(Error('sequence'));
          chunks.push(...Array.from(atob(value.data),ch=>ch.charCodeAt(0)));
          ports.port1.postMessage(JSON.stringify({type:'ack'}));
        } else if(value.type==='done') {
          clearTimeout(timeout);ports.port1.close();resolve({bytes:value.bytes,chunks});
        } else if(value.type==='error')reject(Error(value.message));
      };
      window.postMessage('dsha-blob-port',location.origin,[ports.port2]);
      ports.port1.postMessage(JSON.stringify({type:'blob',url:save.url}));
    });
    if(!transfer.chunks.every((n,i)=>n===bytes[i]))throw Error('download bytes differ');
    DshaFiles.onmessage({data:JSON.stringify({id:save.id,ok:true,detail:'saved'})});
    const cancelled=DSHA.saveFile(file).then(()=>false,error=>error.name==='AbortError');
    const request=requests.shift();
    DshaFiles.onmessage({data:JSON.stringify({id:request.id,ok:false,detail:'AbortError'})});
    return {name:save.name,mime:save.mime,bytes:transfer.bytes,shareResult,cancelled:await cancelled,notices};
  });
  assert.deepEqual(result,{name:'报告-99.md',mime:'text/markdown',bytes:51200,
    shareResult:{status:'chooser-opened'},cancelled:true,notices:[]});
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify(result));
} finally {await fixture.close();}
