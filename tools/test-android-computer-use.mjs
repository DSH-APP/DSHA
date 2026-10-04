import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const server=fileURLToPath(new URL('../app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs',import.meta.url));
const {validate,definitions,tools,bridgeFailed,screenshotContent,call,readBridgeResponse}=createRequire(import.meta.url)(server);
assert.equal(tools.length,6);
assert.equal(bridgeFailed('[ERR] Permission unavailable'),true);
assert.equal(bridgeFailed('[POLICY_BLOCKED] command denied'),true);
assert.equal(bridgeFailed('[UNAUTHORIZED]'),true);
assert.equal(bridgeFailed('窗口应用: com.dsh.client\n[1] "[ERR] earlier terminal failure"'),false);
assert.equal(bridgeFailed('窗口应用: com.dsh.client\n[1] "DISABLED"'),false);
assert.equal(bridgeFailed('OK 截屏已保存：/sdcard/Download/DSHA/screen-1.png'),false);
const byName=name=>definitions.find(d=>d[0]===name);
assert.doesNotThrow(()=>validate(byName('android_click'),{x:50,y:70}));
assert.throws(()=>validate(byName('android_click'),{x:-1,y:70}));
assert.throws(()=>validate(byName('android_click'),{x:1,y:2,shell:'id'}));
assert.throws(()=>validate(byName('android_type'),{text:'a'.repeat(2001)}));
assert.throws(()=>validate(byName('android_key'),{name:'factory-reset'}));
assert.doesNotThrow(()=>validate(byName('android_get_state'),{}));
// Exercise the actual tool call with a native image response from a secondary
// Android user's directory. No guest filesystem access to that directory exists.
const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCXYAAAAASUVORK5CYII=';
const shot={kind:'dsha-screenshot-v1',mimeType:'image/png',path:'/storage/emulated/10/Android/data/com.dsh.client/files/Pictures/DSHA/screen-host-1.png',data:png};
let fsReads=0,requestsSent=0;
const signal=new AbortController().signal;
const fakeNative={fs:{async readFile(file,encoding){fsReads++;assert.equal(file,'/root/.dsh/.bridge_token');assert.equal(encoding,'utf8');return 'fixture-token';}},
  async fetch(url,options){requestsSent++;assert.equal(url.pathname,'/app/ui/screenshot');assert.equal(url.searchParams.get('format'),'mcp');assert.equal(url.searchParams.has('token'),false);assert.equal(options.headers['X-Token'],'fixture-token');assert.equal(options.signal,signal);return new Response(JSON.stringify({result:JSON.stringify(shot)}));}};
const imageResult=await call('android_screenshot',{},signal,fakeNative);
assert.equal(imageResult.isError,false);assert.equal(imageResult.content[1].type,'image');
assert.equal(imageResult.content[1].data,png);assert.match(imageResult.content[0].text,/emulated\/10/);
assert.equal(fsReads,1);assert.equal(requestsSent,1);
const denied=await call('android_screenshot',{},signal,{...fakeNative,async fetch(){return new Response(JSON.stringify({result:'[ERR] SCREENSHOT_RUN_CHANGED'}));}});
assert.equal(denied.isError,true);assert.equal(denied.content.length,1);
assert.throws(()=>screenshotContent(JSON.stringify({...shot,data:'hello'})),/SCREENSHOT_RESPONSE_INVALID/);
assert.throws(()=>screenshotContent(JSON.stringify({...shot,mimeType:'text/html'})),/SCREENSHOT_RESPONSE_INVALID/);
assert.throws(()=>screenshotContent('OK saved: /storage/emulated/10/screen.png'),/SCREENSHOT_RESPONSE_INVALID/);
await assert.rejects(readBridgeResponse(new Response('abcdef'),4),/BRIDGE_RESPONSE_TOO_LARGE/);
await assert.rejects(readBridgeResponse(new Response('x',{headers:{'content-length':'100'}}),4),/BRIDGE_RESPONSE_TOO_LARGE/);
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
assert.equal(replies.find(r=>r.id===2).result.tools.length,6);
assert.equal(replies.find(r=>r.id===3).error.code,-32800);
assert.equal(stderr,'');
console.log('Android Computer Use: native PNG image transfer, secondary-user path, response bounds, rejection, discovery and cancellation passed; no device command was sent.');
