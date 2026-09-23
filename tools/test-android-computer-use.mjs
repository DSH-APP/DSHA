import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const server=fileURLToPath(new URL('../app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs',import.meta.url));
const {validate,definitions,tools,bridgeFailed,screenshotTarget}=createRequire(import.meta.url)(server);
assert.equal(tools.length,6);
for(const base of ['/sdcard/Download/DSHA','/storage/emulated/0/Download/DSHA'])
  assert.deepEqual(screenshotTarget('OK 截屏已保存：'+base+'/screen-20260916-100000.png（1080x2400）'),{file:base+'/screen-20260916-100000.png',base});
assert.equal(screenshotTarget('/sdcard/Download/other/screen-1.png'),null);
assert.equal(screenshotTarget('/sdcard/Download/DSHA/../screen-1.png'),null);
for(const prefix of ['/sdcard','/storage/emulated/0','/storage/emulated/10']){
 const base=prefix+'/Android/data/com.dsh.client/files/Pictures/DSHA';assert.deepEqual(screenshotTarget('OK '+base+'/screen-private-1.png'),{base,file:base+'/screen-private-1.png'});
}
assert.equal(screenshotTarget('/sdcard/Android/data/another.app/files/Pictures/DSHA/screen-1.png'),null);
assert.equal(screenshotTarget('/sdcard/Pictures/screen-1.png'),null);
assert.equal(bridgeFailed('[ERR] Permission unavailable'),true);
assert.equal(bridgeFailed('[POLICY_BLOCKED] command denied'),true);
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
console.log('Android Computer Use: schemas, discovery, and cancellation passed; no device command was sent.');
