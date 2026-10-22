import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const server=fileURLToPath(new URL('../app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs',import.meta.url));
const {validate,definitions,tools,bridgeFailed,screenshotContent,call,readBridgeResponse}=createRequire(import.meta.url)(server);
assert.equal(tools.length,8);
assert.equal(bridgeFailed('[ERR] Permission unavailable'),true);
assert.equal(bridgeFailed('[POLICY_BLOCKED] command denied'),true);
assert.equal(bridgeFailed('[UNAUTHORIZED]'),true);
assert.equal(bridgeFailed('[STOPPED] BRIDGE_RUN_CHANGED'),true);
for(const state of ['NOT_SENT','REJECTED','PARTIAL','UNKNOWN'])assert.equal(bridgeFailed(JSON.stringify({ok:false,state,code:'SCREEN_RUN_CHANGED',error:'SCREEN_RUN_CHANGED'})),true);
assert.equal(bridgeFailed(JSON.stringify({ok:true,state:'COMPLETED',code:'SCREEN_COMPLETED'})),false);
assert.equal(bridgeFailed(JSON.stringify({ok:false,error:'ordinary page data'})),false);
assert.equal(bridgeFailed('窗口应用: com.dsh.client\n[1] "[ERR] earlier terminal failure"'),false);
assert.equal(bridgeFailed('窗口应用: com.dsh.client\n[1] "DISABLED"'),false);
assert.equal(bridgeFailed('OK 截屏已保存：/sdcard/Download/DSHA/screen-1.png'),false);
const byName=name=>definitions.find(d=>d[0]===name);
assert.doesNotThrow(()=>validate(byName('android_click'),{observationId:'native-shot',x:50,y:70}));
assert.throws(()=>validate(byName('android_click'),{x:-1,y:70}));
assert.throws(()=>validate(byName('android_click'),{x:1,y:2,shell:'id'}));
assert.throws(()=>validate(byName('android_click'),{x:1,y:1}),/INVALID_observationId/);
assert.throws(()=>validate(byName('android_click'),{observationId:'',x:1,y:1}),/INVALID_observationId/);
assert.throws(()=>validate(byName('android_long_press'),{observationId:'id',x:1,y:1,ms:499}),/INVALID_ms/);
assert.throws(()=>validate(byName('android_type'),{text:'a'.repeat(2001)}));
assert.throws(()=>validate(byName('android_key'),{name:'factory-reset'}));
assert.doesNotThrow(()=>validate(byName('android_get_state'),{}));
// Exercise the actual tool call with a native image response from a secondary
// Android user's directory. No guest filesystem access to that directory exists.
const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCXYAAAAASUVORK5CYII=';
const shot={kind:'dsha-screenshot-v1',mimeType:'image/png',path:'/storage/emulated/10/Android/data/com.dsh.client/files/Pictures/DSHA/screen-host-1.png',data:png,observationId:'native-observation',coordinatesAvailable:true,serviceEpoch:7,target:{displayId:0,windowId:41,packageName:'org.example.app'},observationRegion:{left:0,top:2,right:8,bottom:10}};
let fsReads=0,requestsSent=0;
const signal=new AbortController().signal;
const fakeNative={fs:{async readFile(file,encoding){fsReads++;assert.equal(file,'/root/.dsh/.bridge_token');assert.equal(encoding,'utf8');return 'fixture-token';}},
  async fetch(url,options){requestsSent++;assert.equal(url.pathname,'/app/ui/screenshot');assert.equal(url.searchParams.get('format'),'mcp');assert.equal(url.searchParams.has('token'),false);assert.equal(options.headers['X-Token'],'fixture-token');assert.equal(options.signal,signal);return new Response(JSON.stringify({result:JSON.stringify(shot)}));}};
const imageResult=await call('android_screenshot',{},signal,fakeNative);
assert.equal(imageResult.isError,false);assert.equal(imageResult.content[1].type,'image');
assert.equal(imageResult.content[1].data,png);assert.match(imageResult.content[0].text,/emulated\/10/);
const metadata=JSON.parse(imageResult.content[0].text); assert.equal(metadata.observationId,shot.observationId);assert.equal(metadata.coordinatesAvailable,true);assert.deepEqual(metadata.target,shot.target);assert.equal(Object.hasOwn(metadata,'data'),false);
assert.equal(fsReads,1);assert.equal(requestsSent,1);
const denied=await call('android_screenshot',{},signal,{...fakeNative,async fetch(){return new Response(JSON.stringify({result:'[ERR] SCREENSHOT_RUN_CHANGED'}));}});
assert.equal(denied.isError,true);assert.equal(denied.content.length,1);
// Real ScreenReply failures must reach the MCP adapter before screenshot decoding.
// Each call performs exactly one request, retaining UNKNOWN instead of replaying it.
for(const name of ['android_screenshot','android_get_state','android_click','android_double_click','android_long_press','android_swipe']){
  let attempts=0;
  const failure=JSON.stringify({ok:false,state:'UNKNOWN',code:'SCREEN_RUN_CHANGED',error:'SCREEN_RUN_CHANGED',executionUnknown:true,automaticRetry:false});
  const args=name==='android_swipe'?{observationId:'native-shot',x1:1,y1:1,x2:2,y2:2,ms:300}:['android_click','android_double_click','android_long_press'].includes(name)?{observationId:'native-shot',x:1,y:1,...(name==='android_long_press'?{ms:600}:{})}:{};
  const reply=await call(name,args,signal,{...fakeNative,async fetch(){attempts++;return new Response(JSON.stringify({result:failure}));}});
  assert.equal(reply.isError,true,name);assert.deepEqual(reply.content,[{type:'text',text:failure}]);assert.equal(attempts,1);
}
const stopped=await call('android_get_state',{},signal,{...fakeNative,async fetch(){return new Response(JSON.stringify({result:'[STOPPED] BRIDGE_RUN_CHANGED'}));}});
assert.equal(stopped.isError,true);assert.equal(stopped.content[0].text,'[STOPPED] BRIDGE_RUN_CHANGED');
assert.throws(()=>screenshotContent(JSON.stringify({...shot,data:'hello'})),/SCREENSHOT_RESPONSE_INVALID/);
assert.throws(()=>screenshotContent(JSON.stringify({...shot,mimeType:'text/html'})),/SCREENSHOT_RESPONSE_INVALID/);
assert.throws(()=>screenshotContent('OK saved: /storage/emulated/10/screen.png'),/SCREENSHOT_RESPONSE_INVALID/);
await assert.rejects(readBridgeResponse(new Response('abcdef'),4),/BRIDGE_RESPONSE_TOO_LARGE/);
await assert.rejects(readBridgeResponse(new Response('x',{headers:{'content-length':'100'}}),4),/BRIDGE_RESPONSE_TOO_LARGE/);
const nativeIndex=process.argv.indexOf('--native-shot');
let wireMetadata=metadata;
if(nativeIndex!==-1){
  const {readFile}=await import('node:fs/promises');
  const nativeRaw=await readFile(process.argv[nativeIndex+1],'utf8');
  const actual=screenshotContent(nativeRaw); wireMetadata=JSON.parse(actual[0].text);
  assert.equal(wireMetadata.coordinatesAvailable,true);assert.equal(wireMetadata.target.displayId,0);
  assert.match(wireMetadata.observationId,/^[0-9a-f-]{36}$/);
  assert.equal(Object.hasOwn(wireMetadata,'data'),false);
}
const {createServer}=await import('node:http');
const seenRequests=[];
const http=createServer((request,response)=>{seenRequests.push({url:new URL(request.url,'http://127.0.0.1'),token:request.headers['x-token']});response.setHeader('Content-Type','application/json');response.end(JSON.stringify({result:JSON.stringify({ok:true,state:'COMPLETED',code:'SCREEN_COMPLETED',effectVerified:false})}));});
await new Promise(resolve=>http.listen(0,'127.0.0.1',resolve));
try{
  for(const [name,args,gesture] of [
    ['android_click',{observationId:wireMetadata.observationId,x:4,y:6},null],
    ['android_double_click',{observationId:wireMetadata.observationId,x:4,y:6},'double_tap'],
    ['android_long_press',{observationId:wireMetadata.observationId,x:4,y:6,ms:600},'long_press'],
    ['android_swipe',{observationId:wireMetadata.observationId,x1:4,y1:4,x2:4,y2:8,ms:300},null]]){
    const before=seenRequests.length;
    const result=await call(name,args,signal,{...fakeNative,fetch(url,options){assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'3090');return fetch('http://127.0.0.1:'+http.address().port+url.pathname+url.search,options);}});
    assert.equal(result.isError,false);assert.equal(seenRequests.length,before+1);
    const delivered=seenRequests.at(-1);assert.equal(delivered.token,'fixture-token');assert.equal(delivered.url.searchParams.get('observationId'),wireMetadata.observationId);assert.equal(delivered.url.searchParams.get('gesture'),gesture);
  }
}finally{await new Promise(resolve=>http.close(resolve));}
const unavailable=screenshotContent(JSON.stringify({...shot,coordinatesAvailable:false,observationId:undefined,reason:'TARGET_WINDOW_OCCLUDED'}));
assert.equal(JSON.parse(unavailable[0].text).coordinatesAvailable,false);assert.equal(Object.hasOwn(JSON.parse(unavailable[0].text),'observationId'),false);
const child=spawn(process.execPath,[server],{stdio:['pipe','pipe','pipe']});
let output='',stderr='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>stderr+=b);
const requests=[{id:1,method:'initialize',params:{protocolVersion:'2025-03-26'}},{id:2,method:'tools/list'},
  {id:3,method:'tools/call',params:{name:'android_click',arguments:{x:-1,y:1}}},
  {method:'notifications/cancelled',params:{requestId:3}}];
child.stdin.write(requests.map(r=>JSON.stringify({jsonrpc:'2.0',...r})).join('\n')+'\n');
await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('MCP fixture timeout')),5000);child.stdout.on('data',()=>{if(output.trim().split('\n').length>=3){clearTimeout(timeout);resolve();}});child.on('error',reject);});
child.stdin.end();await new Promise(resolve=>child.on('close',resolve));
const replies=output.trim().split('\n').map(JSON.parse);
assert.equal(replies.find(r=>r.id===1).result.serverInfo.name,'DSHA Android Computer Use');
assert.equal(replies.find(r=>r.id===2).result.tools.length,8);
assert.equal(replies.find(r=>r.id===3).error.code,-32800);
assert.equal(stderr,'');
console.log('Android Computer Use: 8 tools; screenshot metadata/credential, real local HTTP for tap/double/long/swipe, six UNKNOWN no-replay paths, response bounds, rejection, discovery and cancellation passed; no device command was sent.');
