// 删除入口必须经过新版 dsh 的 Host/Origin 和浏览器鉴权，未授权时不读取会话或请求体。
import test from 'node:test';
import assert from 'node:assert/strict';
import {apply} from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import { Readable } from 'node:stream';
function fixture(rejection, t) {
  let handler, checked=0, consulted=0;
  const disposers=[];
  class Agents {
    async create(){throw Error('鉴权与正文检查前不得创建会话');}
    async resume(){throw Error('鉴权与正文检查前不得恢复会话');}
  }
  const webServer={port:3080,register:route=>{
    assert.equal(route.kind,'exact');assert.equal(route.path,'/api/mobile-nav.session.delete');
    assert.equal(handler,undefined);handler=route.handler;return ()=>{};
  }};
  t.after(()=>{for(const dispose of disposers.reverse())dispose();});
  const ctx={effect(){},get(){consulted++;throw Error('不应访问会话');},inject(names,fn){
    assert.deepEqual(names,names.length===1?['webServer']:['webServer','connection','agents']);
    const services=names.length===1?{webServer}:{webServer,agents:new Agents(),connection:{requestRejection(){checked++;return rejection;}}};
    fn({...services,effect:run=>{const dispose=run();assert.equal(typeof dispose,'function');disposers.push(dispose);}});
  }};
  apply(ctx);
  assert.equal(typeof handler,'function');
  return {handler,get checked(){return checked;},get consulted(){return consulted;}};
}
for(const status of [401,403]) test(`删除会话拒绝 ${status} 请求且不读取正文`,async t=>{
  const f=fixture(status,t); let code,body;
  await f.handler({method:'POST'},{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
  assert.equal(code,status);assert.equal(body.error.code,'access-denied');assert.equal(f.checked,1);assert.equal(f.consulted,0);
});
test('已鉴权但方法错误的请求仍不能删除会话',async t=>{
  const f=fixture(undefined,t);let code;
  await f.handler({method:'GET'},{writeHead:status=>code=status,end(){}});
  assert.equal(code,405);assert.equal(f.checked,1);assert.equal(f.consulted,0);
});

for(const value of ['null','[]','true','"x"','{bad'])
  test(`非法删除正文保持结构化错误：${value.slice(0,20)}`,async t=>{
    const f=fixture(undefined,t);let code,body;
    const req=Readable.from([value]);req.method='POST';
    req.headers={host:'127.0.0.1:3080',origin:'http://127.0.0.1:3080'};
    await f.handler(req,{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
    assert.equal(code,400);assert.equal(body.error.code,'invalid-body');assert.equal(f.consulted,0);
  });

for(const origin of [undefined,'','http://127.0.0.1:3080'])
  test(`Host鉴权通过后允许同源或非浏览器请求：${origin??'无Origin'}`,async t=>{
    const f=fixture(undefined,t);let code,body;
    const req=Readable.from(['null']);req.method='POST';req.headers={host:'127.0.0.1:3080',origin};
    await f.handler(req,{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
    assert.equal(code,400);assert.equal(body.error.code,'invalid-body');assert.equal(f.checked,1);assert.equal(f.consulted,0);
  });

for(const origin of ['http://evil.example','http://127.0.0.1:3081','null','invalid://'])
  test(`Origin拒绝跨域/畸形来源且不读取正文：${origin}`,async t=>{
    const f=fixture(undefined,t);let code,body;
    const req={method:'POST',headers:{host:'127.0.0.1:3080',origin},setEncoding(){throw Error('不得读取正文');}};
    await f.handler(req,{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
    assert.equal(code,403);assert.equal(body.error.code,'cross-origin');assert.equal(f.checked,1);assert.equal(f.consulted,0);
  });

for(const [name,value,status,error] of [
  ['精确1MiB', '"'+'x'.repeat(1_048_574)+'"',400,'invalid-body'],
  ['超过1MiB', '"'+'x'.repeat(1_048_575)+'"',413,'payload-too-large'],
  ['多字节超过1MiB','"'+'中'.repeat(349_526)+'"',413,'payload-too-large'],
]) test(`删除正文按真实字节限制：${name}`,async t=>{
  const f=fixture(undefined,t);let code,body;
  const req=Readable.from([value]);req.method='POST';req.headers={host:'127.0.0.1:3080'};
  await f.handler(req,{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
  assert.equal(code,status);assert.equal(body.error.code,error);assert.equal(f.checked,1);assert.equal(f.consulted,0);
});
test('移动 UI 模块对锁定 rc2 Host 声明实际服务依赖',()=>{
  let plugin;
  vm.runInNewContext(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),
    {window:{__ModuleLoader__:{load:value=>plugin=value}}});
  const exported=plugin.factory(()=>({}));
  for (const name of ['slots','layout','locale','sessions','workspaces','sessionLogDownload'])
    assert.ok(exported.inject.includes(name), name);
  assert.equal(exported.inject.includes('sidebarRight'), false);
});
