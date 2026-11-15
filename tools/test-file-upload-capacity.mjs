// Isolated real locked Host/attachment store. Full-size mode actually writes and
// rereads 5,000,000,000 bytes; the separate unknown-length negative writes a
// temporary prefix and must remove it without creating an object or receipt.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import http from 'node:http';
import {createHash, randomUUID} from 'node:crypto';
import {spawn, spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {Readable} from 'node:stream';
import {testRuntime} from './test-runtime-fixture.mjs';
import {createRequire} from 'node:module';

const root = path.resolve(import.meta.dirname, '..');
const full = process.argv.includes('--full-5gb');
const fullOverflow = process.argv.includes('--full-overflow');
const limit = 5_000_000_000;
const output = path.resolve(process.env.DSHA_UPLOAD_CAPACITY_OUTPUT || path.join(root, 'app/build/current-upload-capacity'));
fs.mkdirSync(output, {recursive:true});
const base = fs.mkdtempSync(path.join(root, 'app/build/upload-capacity-'));
const runtime = testRuntime('raw');
const assets = path.join(root, 'app/src/main/assets');
const modules = path.join(runtime, 'node_modules');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const records = [];
// 0.2.2-rc.1: resolver-lifecycle and conversation-large-image-file are retired (upstream absorbed).
const recipes = ['file-upload-capacity-patch.json', 'file-upload-binary-client-patch.json'].map(name => [name, JSON.parse(fs.readFileSync(path.join(assets,name)))]);
const sources = new Map();
for (const [name, recipe] of recipes) {
  const original = fs.readFileSync(path.join(modules,recipe.module),'utf8');
  assert.equal(sha(original), recipe.sourceSha256, name);
  let source = sources.get(recipe.module) ?? original;
  for (const patch of recipe.patches) {
    assert.equal(source.split(patch.before).length,2,name);
    source = source.replace(patch.before, () => patch.after);
  }
  sources.set(recipe.module, source);
}
for (const [module, source] of sources) {
  const result = spawnSync(process.execPath,['--check','--input-type=module'],{input:source,encoding:'utf8',windowsHide:true});
  assert.equal(result.status,0,module+' '+result.stderr);
}
records.push({name:'two-raw-locked-upload-recipes',status:'PASS'});

// Run the actual browser service body with browser carriers. A byte-array input
// must become a Blob and must never invoke the remote/base64 path.
let clientSource = sources.get('@deepseek-ai/dsh-client-file-upload/lib/client.js');
clientSource = clientSource.replace('exports.apply = apply;', 'exports.FileUploadRuntime = FileUploadRuntime; exports.dshaBoundedUploadStream = dshaBoundedUploadStream; exports.apply = apply;');
let client;
let posts = 0;
const context = vm.createContext({Blob,ReadableStream,Uint8Array,URLSearchParams,URL,AbortController,DOMException,console,
  __DSH_FILE_UPLOAD__: {fetch:async(url,init)=>{
    posts++; assert.equal(init.headers['content-type'],'application/octet-stream');
    assert(init.body instanceof Blob); assert.deepEqual(Buffer.from(await init.body.arrayBuffer()),Buffer.from([1,2,3]));
    if (new URL(url,'http://fixture.invalid/').searchParams.has('mediaType'))
      return new Response(JSON.stringify({ok:true,value:{receiptId:'i',image:{attachmentId:'sha256:i',mediaType:'image/png',bytes:3,width:1,height:1}}}),{status:200});
    return new Response(JSON.stringify({ok:true,value:{receiptId:'r',file:{attachmentId:'sha256:x',name:'x',bytes:3}}}),{status:200});
  }},
  window:{__ModuleLoader__:{load({factory}){client = factory(()=>({Service:class {constructor(ctx){this.ctx=ctx;}}}));}}}
});
vm.runInContext(clientSource,context);
const browser = new client.FileUploadRuntime({remote:{fileUploads:{upload(){throw Error('base64 fallback used');}}}});
assert((await browser.upload('s',new Uint8Array([1,2,3]),'x')).ok); assert.equal(posts,1);
class SizedBlob extends Blob {get size(){return limit+1;}}
const rejectedBlob = await browser.upload('s',new SizedBlob(),'too-large');
assert.equal(rejectedBlob.ok,false); assert.equal(rejectedBlob.error.details.reason,'FILE_TOO_LARGE'); assert.equal(posts,1);
// Upstream uploadImage() stays untouched: Blob body through postRaw, Host imageLimits bound it.
const image = await browser.uploadImage('s',new Blob([new Uint8Array([1,2,3])]),'image/png','x.png');
assert(image.ok); assert.equal(image.value.image.mediaType,'image/png'); assert.equal(posts,2);
let cancelled = false;
const chunk = new Uint8Array(65536);
let remaining = limit+2*65536;
const counted = client.dshaBoundedUploadStream(new ReadableStream({pull(controller){if(!remaining){controller.close();return;}const n=Math.min(chunk.length,remaining);remaining-=n;controller.enqueue(chunk.subarray(0,n));},cancel(){cancelled=true;}}));
let bytes=0; const reader=counted.getReader();
await assert.rejects(async()=>{for(;;){const item=await reader.read();if(item.done)break;bytes+=item.value.length;}},e=>e.details?.reason==='FILE_TOO_LARGE');
assert(bytes<=limit);assert(cancelled);
records.push({name:'actual-client-service-binary-transport-and-synthetic-unknown-5GB-plus-one',status:'PASS',upstreamUploadImageUnchanged:true,transportPosts:posts,syntheticOnly:true,bytesPassed:bytes,sourceCancelled:true});

// conversation-large-image-file retirement proof: the raw locked composer stages
// images through the binary upload route and intake rejects images over imageLimits.
const conversationSource = fs.readFileSync(path.join(modules,'@deepseek-ai/dsh-client-ui-conversation/lib/client.js'),'utf8');
assert.equal(conversationSource.includes('DSHA_LARGE_IMAGE_FILE_V1'),false);
assert.equal(conversationSource.split('if (images.some((file) => file.size > imageLimits.maxImageBytes)) return t("image.fileTooLarge"').length,2);
assert.equal(conversationSource.split('attachment.kind === "image" ? await this.ctx.fileUpload.uploadImage(sessionId, attachment.file,').length,2);
const draftStart = conversationSource.indexOf('createDrafts(sessionId, files, uploadImages) {');
assert(draftStart>0);
const draftEnd = conversationSource.indexOf('\n\t\t\t/**',draftStart);
let probes=0,uploadsStarted=[];
const Drafts = vm.runInNewContext('class Drafts {'+conversationSource.slice(draftStart,draftEnd)+'}; Drafts', {
  randomUUID, browserDraftAttachment:file=>({kind:'image',id:randomUUID(),file}),
  probeDimensions(){probes++;},isImageMediaType:type=>type==='image/png'
});
const drafts = new Drafts(); drafts.draftAttachments=new Map();drafts.beginFileUpload=(sessionId,attachment)=>uploadsStarted.push(attachment.kind);
assert.equal(drafts.createDrafts('s',[{type:'image/png',size:50*1024*1024}],true)[0].kind,'image');
assert.equal(drafts.createDrafts('s',[{type:'text/plain',size:1}],true)[0].kind,'file');
assert.equal(drafts.createDrafts('s',[{type:'image/png',size:1}],false)[0].kind,'image');
assert.equal(probes,2);assert.deepEqual(uploadsStarted,['image','file']);
records.push({name:'upstream-composer-binary-image-upload-retires-large-image-file',status:'PASS',imageUploadsUseBinaryRoute:true,intakeRejectsOverImageLimit:true});

const serverModule = '@deepseek-ai/dsh-client-file-upload/lib/index.js';
const serverSource = sources.get(serverModule);
const serverFile = path.join(base,'server.mjs'); fs.writeFileSync(serverFile,serverSource);
fs.symlinkSync(modules,path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const probeFile=path.join(base,'server-probe.mjs');
fs.writeFileSync(probeFile,serverSource+'\nexport {dshaUploadChunks};\n');
const {dshaUploadChunks,default:FileUploads}=await import(pathToFileURL(probeFile));
for(const size of [limit,limit+1]){
  const state={exceeded:false};let passed=0,closed=false;
  async function* source(){try {let left=size;while(left){const n=Math.min(chunk.length,left);left-=n;yield chunk.subarray(0,n);}}finally{closed=true;}}
  const consume=async()=>{for await(const data of dshaUploadChunks(source(),state))passed+=data.length;};
  if(size===limit){await consume();assert.equal(passed,limit);assert.equal(state.exceeded,false);}
  else {await assert.rejects(consume,e=>e.details?.reason==='FILE_TOO_LARGE');assert(state.exceeded);assert(passed<=limit);}
  assert(closed);
}
records.push({name:'actual-server-generator-synthetic-exact-5GB-and-unknown-length-plus-one',status:'PASS',syntheticOnly:true,partialChunkNotForwarded:true,sourceIteratorClosed:true});
// Exercise the real attachment temporary-cleanup and service error boundary
// without writing another 5GB: a synthetic chunk declares overflow before yield.
const require=createRequire(pathToFileURL(path.join(runtime,'package.json')));
const [{Context},{default:Commands},{default:Attachments}]=await Promise.all([
  '@deepseek-ai/cordis','@deepseek-ai/dsh-commands','@deepseek-ai/dsh-attachment-local'
].map(specifier=>import(pathToFileURL(require.resolve(specifier)))));
const integration=new Context(),integrationAgents=new Map();
integration.provide('agents',{get:id=>integrationAgents.get(id)});
integration.provide('connection',{fetch:{register:()=>()=>{}}});
await integration.plugin(Commands);
const integrationHome=path.join(base,'cleanup-home');
await integration.plugin(Attachments,{dshHome:integrationHome});
await integration.plugin(FileUploads);
const integrationAgent={id:'overflow-fixture',ctx:integration,session:{header:{origin:'user'}}};
integrationAgents.set(integrationAgent.id,integrationAgent);
const syntheticOverflow=new Proxy(new Uint8Array(1),{get(target,key){return key==='byteLength'?limit+1:Reflect.get(target,key,target);}});
await assert.rejects(integration.fileUploads.uploadStream({sessionId:integrationAgent.id,name:'over.bin',data:(async function*(){yield syntheticOverflow;})()}),error=>error.code==='session/attachment-invalid'&&error.details.reason==='FILE_TOO_LARGE');
assert.deepEqual(fs.readdirSync(path.join(integrationHome,'attachments/v1/tmp')),[]);
await integration.fiber.dispose();
records.push({name:'actual-service-and-attachment-store-overflow-cleans-temporary',status:'PASS',syntheticChunkLength:true,actual5GBWritten:false,receiptReturned:false,temporaryDirectoryEmpty:true});
const hook=path.join(base,'hook.mjs');
fs.writeFileSync(hook,`import {registerHooks} from 'node:module';import fs from 'node:fs';import {createHash} from 'node:crypto';
const target=${JSON.stringify(pathToFileURL(path.join(modules,serverModule)).href)};
registerHooks({load(url,context,next){const result=next(url,context);if(url!==target)return result;
const source=typeof result.source==='string'?result.source:Buffer.from(result.source).toString('utf8');
if(createHash('sha256').update(source).digest('hex')!==${JSON.stringify(recipes[0][1].sourceSha256)})throw Error('UPLOAD_OFFICIAL_SOURCE_CHANGED');
return {...result,source:fs.readFileSync(${JSON.stringify(serverFile)},'utf8')};}});
const sample=setInterval(()=>console.error('[upload-fixture-rss] '+process.memoryUsage().rss),1000);sample.unref();
`);
const home=path.join(base,'home'),workspace=path.join(base,'workspace');
fs.mkdirSync(path.join(home,'profiles/web'),{recursive:true});fs.mkdirSync(workspace);
fs.writeFileSync(path.join(workspace,'retained.txt'),'owned fixture only\n');
fs.writeFileSync(path.join(home,'profiles/web/package.json'),JSON.stringify({name:'dsha-upload-capacity-fixture',version:'1.0.0',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app']}}}));
const clean=Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(API_KEY|TOKEN|SECRET|PASSWORD|DSH_HOME|NODE_OPTIONS)/i.test(key)));
const child=spawn(process.execPath,['--import',pathToFileURL(hook).href,path.join(modules,'@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port','0'],{
  cwd:workspace,windowsHide:true,env:{...clean,DSH_HOME:home,HOME:home,USERPROFILE:home,SSH_CONNECTION:'127.0.0.1 1 127.0.0.1 2',BROWSER:'true',DSH_TELEMETRY_DISABLED:'1',NARB_DISABLE_NATIVE_CACHE:'1'},stdio:['ignore','pipe','pipe']
});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const redact=text=>text.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]');
let log='',exited=false;child.on('exit',()=>exited=true);
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>log=(log+chunk).slice(-500000));
let fullWritten=0;
try {
  let auth;
  for(let i=0;i<500&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(100);}
  assert(auth,'Host boot failed: '+redact(log.slice(-3000)));
  const origin=new URL(auth).origin;
  const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);
  const cookie=exchange.headers.get('set-cookie').split(';')[0];
  const headers={Cookie:cookie,Origin:origin,'Content-Type':'application/octet-stream'};
  const rpc=async(method,args)=>{const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:randomUUID(),method,payload:{args}})});assert.equal(response.status,200);return (await response.json()).result;};
  const created=await rpc('session/create',{request:{cwd:workspace}});assert(created.ok,JSON.stringify(created));
  const url=origin+'/api/session/uploadFileBinary?sessionId='+created.value.sessionId+'&name=boundary.bin';
  const small=Buffer.from('real bounded upload\n');
  const smallResponse=await fetch(url,{method:'POST',headers,body:small});assert.equal(smallResponse.status,200);
  const smallResult=await smallResponse.json();assert(smallResult.ok,JSON.stringify(smallResult));assert.equal(smallResult.value.file.bytes,small.length);
  // A declared oversized request must be refused before receiving/storing it.
  const declared=await new Promise((resolve,reject)=>{const request=http.request(url,{method:'POST',agent:false,headers:{...headers,'Content-Length':String(limit+1)}},response=>{response.resume();response.on('end',()=>resolve(response.statusCode));});request.on('error',reject);request.setTimeout(15000,()=>request.destroy(Error('declared request deadline')));request.end();});
  assert.equal(declared,413);
  const unauth=await fetch(url,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:small});assert([401,403].includes(unauth.status));
  const foreign=await fetch(url,{method:'POST',headers:{...headers,Origin:'http://foreign.invalid'},body:small});assert([401,403].includes(foreign.status));
  records.push({name:'actual-authenticated-CLI-stream-route-small-and-declared-overflow',status:'PASS',declaredStatus:declared,authenticationPreserved:true});
  if(full){
    const pattern=Buffer.alloc(65536);for(let i=0;i<pattern.length;i++)pattern[i]=(i*31+17)&255;
    const hash=createHash('sha256');
    async function* body(size,hashValue){let remaining=size;while(remaining){const n=Math.min(pattern.length,remaining);const data=pattern.subarray(0,n);hashValue?.update(data);remaining-=n;yield data;}}
    console.log('Writing actual 5,000,000,000-byte upload through isolated authenticated CLI route');
    const start=Date.now();
    const sourceFile=process.env.DSHA_UPLOAD_5GB_SOURCE;
    let uploadBody;
    if(sourceFile){assert.equal(fs.statSync(sourceFile).size,limit);uploadBody=fs.createReadStream(sourceFile,{highWaterMark:65536});uploadBody.on('data',data=>hash.update(data));}
    else uploadBody=Readable.from(body(limit,hash),{objectMode:false});
    const response=await fetch(url,{method:'POST',headers:{...headers,'Content-Length':String(limit)},body:uploadBody,duplex:'half',signal:AbortSignal.timeout(900000)});
    assert.equal(response.status,200);const result=await response.json();assert(result.ok,JSON.stringify(result));
    const digest=hash.digest('hex'),ref=result.value.file;assert.equal(ref.bytes,limit);assert.equal(ref.attachmentId,'sha256:'+digest);
    if(process.env.DSHA_UPLOAD_5GB_SHA256)assert.equal(digest,process.env.DSHA_UPLOAD_5GB_SHA256);
    const stored=path.join(home,'attachments/v1/files',digest.slice(0,2),digest,ref.name);assert.equal(fs.statSync(stored).size,limit);
    const readHash=createHash('sha256');let readBytes=0;for await(const data of fs.createReadStream(stored,{highWaterMark:65536})){readBytes+=data.length;readHash.update(data);}
    assert.equal(readBytes,limit);assert.equal(readHash.digest('hex'),digest);fullWritten=limit;
    records.push({name:'actual-5GB-upload-durable-store-and-independent-full-readback',status:'PASS',actualBytesWritten:limit,actualBytesReadBack:readBytes,sha256:digest,elapsedMs:Date.now()-start,receiptReturned:true,androidDevice:false});
    console.log('Verified actual 5GB bytes and digest');
    if(fullOverflow){
    const overflow=await fetch(url,{method:'POST',headers,body:Readable.from(body(limit+1),{objectMode:false}),duplex:'half',signal:AbortSignal.timeout(900000)});
    assert.equal(overflow.status,200);const failure=await overflow.json();assert.equal(failure.ok,false);assert.equal(failure.error.details.reason,'FILE_TOO_LARGE');assert.equal(failure.error.details.maxBytes,limit);
    const tmp=path.join(home,'attachments/v1/tmp');assert.deepEqual(fs.readdirSync(tmp),[]);
    assert.equal(fs.statSync(stored).size,limit);
    records.push({name:'actual-unknown-length-5GB-plus-one-rejected-and-temporary-cleaned',status:'PASS',bytesOffered:limit+1,receiptReturned:false,temporaryDirectoryEmpty:true,prior5GBObjectPreserved:true,androidDevice:false});
    }
  }
  assert.equal(fs.readFileSync(path.join(workspace,'retained.txt'),'utf8'),'owned fixture only\n');
  assert(!/chat\/completions|api\.deepseek\.com.*request/i.test(log));
} finally {
  if(!exited)child.kill();for(let i=0;i<100&&!exited;i++)await delay(100);assert(exited,'owned Host exited');
  fs.writeFileSync(path.join(output,'host.log'),redact(log));
}
const peaks=[...log.matchAll(/\[upload-fixture-rss\] (\d+)/g)].map(match=>Number(match[1]));
const report={schema:1,status:'PASS',fullSizeMode:full,actual5GBWrittenAndReadBack:fullWritten===limit,androidDevice:false,modelNetworkRequested:false,peakHostRssBytes:Math.max(0,...peaks),fixtureDirectory:base,records};
fs.writeFileSync(path.join(output,full?'result-full-5gb.json':'result.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
