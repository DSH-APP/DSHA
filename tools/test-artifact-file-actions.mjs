// Exercise the shipped page bridge. Native admission/publication is mocked here;
// this does not claim Android MediaStore, SAF, FGS or restart acceptance.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';

const fixture = await browserFixture();
try {
  const page = fixture.page;
  await page.evaluate(() => {
    window.requests = [];
    window.DshaFiles = {postMessage: text => requests.push(JSON.parse(text))};
  });
  await page.addScriptTag({content: fs.readFileSync('app/src/main/assets/web-integration/files.js', 'utf8')});
  const result = await page.evaluate(async () => {
    const artifact = {id:'a'.repeat(48),name:'原始文件.bin',mime:'application/octet-stream',
      bytes:5000000000,sha256:'b'.repeat(64),version:'provider-version-1'};
    const unbound = await DSHA.downloadArtifact(artifact).then(() => false, () => true);
    __dshaFilesBind({nonce:'first-document',generation:192,instance:'current-runtime'});
    const nativeReply = (request, result) => DshaFiles.onmessage({data:JSON.stringify({id:request.id,ok:true,result})});
    let blobs = 0;
    const create = URL.createObjectURL.bind(URL);
    URL.createObjectURL = value => { blobs++; return create(value); };
    const queued = DSHA.downloadArtifact(artifact, 'downloads');
    const queueRequest = requests.shift();
    if (queueRequest.url || blobs) throw Error('Persistent artifact became a page Blob');
    const query = DSHA.getDownloads('task-1');
    const queryRequest = requests.shift();
    nativeReply(queryRequest, {tasks:[{id:'task-1',state:'DOWNLOADING',bytes:1048576,total:5000000000}]});
    nativeReply(queueRequest, {status:'queued',taskId:'task-1'});
    const queueResult = await queued, status = await query;
    const oversizedShare = await DSHA.downloadArtifact(artifact, 'share').then(() => false, error => /32 MiB/.test(error.message));
    const opened = DSHA.openDownloads(), openRequest = requests.shift();
    nativeReply(openRequest, {status:'opened'});
    const openResult = await opened;
    const invalid = [];
    for (const bad of [{...artifact,path:'/sdcard/private'}, {...artifact,name:'../escape'},
      {...artifact,id:'http://127.0.0.1/file'}, {...artifact,bytes:5000000001},
      {...artifact,bytes:1.5}, {...artifact,version:'\ninvalid'}, {...artifact,mime:'image/png;foo=bar'},
      {...artifact,name:'图片'.repeat(41)+'.png'}]) {
      invalid.push(await DSHA.downloadArtifact(bad).then(() => false, () => true));
    }
    const gallery = DSHA.saveImage(new File([new Uint8Array([137,80,78,71])], 'original.png', {type:'image/png'}));
    const galleryRequest = requests.shift();
    if (galleryRequest.action !== 'gallery' || galleryRequest.bytes !== 4 || !galleryRequest.url.startsWith('blob:'))
      throw Error('Original image did not use the bounded Blob bridge');
    DshaFiles.onmessage({data:JSON.stringify({id:galleryRequest.id,ok:true,detail:'queued:task-image'})});
    const galleryResult = await gallery;
    const temporary = DSHA.saveFile(new File([new Uint8Array([1,2,3])], 'unfinished.bin'))
      .then(() => 'unexpected', error => error.message);
    const temporaryRequest = requests.shift();
    const pending = DSHA.downloadArtifact(artifact, 'saveas').then(() => 'unexpected', error => error.message);
    const stale = requests.shift();
    window.dispatchEvent(new Event('pagehide'));
    const interrupted = await pending;
    const temporaryInterrupted = await temporary;
    DshaFiles.onmessage({data:JSON.stringify({id:temporaryRequest.id,ok:true,detail:'saved'})});
    DshaFiles.onmessage({data:JSON.stringify({id:stale.id,ok:true,result:{status:'queued',taskId:'late-old-task'}})});
    window.dispatchEvent(new Event('pageshow'));
    __dshaFilesBind({nonce:'second-document',generation:192,instance:'current-runtime'});
    const current = DSHA.getDownloads(), currentRequest = requests.shift();
    // A late old-page admission cannot settle a new-page request.
    nativeReply(stale, {status:'queued',taskId:'late-old-task'});
    nativeReply(currentRequest, {tasks:[{id:'late-old-task',state:'WAITING_DESTINATION'}]});
    const currentStatus = await current;
    return {unbound, queueResult, status, openResult, invalid, galleryResult, interrupted, temporaryInterrupted, oversizedShare,
      firstBinding:queueRequest.binding,secondBinding:currentRequest.binding,currentStatus,
      blobs,remaining:requests.length};
  });
  assert.equal(result.unbound, true);
  assert.equal(result.oversizedShare, true);
  assert.deepEqual(result.queueResult, {status:'queued',taskId:'task-1'});
  assert.equal(result.status.tasks[0].bytes, 1048576);
  assert.deepEqual(result.openResult, {status:'opened'});
  assert.deepEqual(result.invalid, Array(8).fill(true));
  assert.deepEqual(result.galleryResult, {status:'queued',taskId:'task-image'});
  assert.match(result.interrupted, /Page closed/);
  assert.match(result.temporaryInterrupted, /temporary Blob transfers cannot resume/);
  assert.equal(result.firstBinding.nonce, 'first-document');
  assert.equal(result.secondBinding.nonce, 'second-document');
  assert.equal(result.currentStatus.tasks[0].state, 'WAITING_DESTINATION');
  assert.equal(result.blobs, 2);
  assert.equal(result.remaining, 0);
  assert.deepEqual(fixture.errors, []);
  console.log(JSON.stringify({passed:true,engine:process.env.DSHA_BROWSER||'chromium',result}, null, 2));
} finally { await fixture.close(); }
