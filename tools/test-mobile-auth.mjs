// 删除入口必须经过新版 dsh 的 Host/Origin 和浏览器鉴权，未授权时不读取会话或请求体。
import test from 'node:test';
import assert from 'node:assert/strict';
import {apply} from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
function fixture(rejection) {
  let handler, checked=0, consulted=0;
  const ctx={effect(){},get(){consulted++;throw Error('不应访问会话');},inject(names,fn){
    assert.ok(names.includes('connection'));
    fn({connection:{requestRejection(){checked++;return rejection;}},effect:fn=>fn(),webServer:{register:route=>{handler=route.handler;}}});
  }};
  apply(ctx);
  return {handler,get checked(){return checked;},get consulted(){return consulted;}};
}
for(const status of [401,403]) test(`删除会话拒绝 ${status} 请求且不读取正文`,async()=>{
  const f=fixture(status); let code,body;
  await f.handler({method:'POST'},{writeHead:status=>code=status,end:text=>body=JSON.parse(text)});
  assert.equal(code,status);assert.equal(body.error.code,'access-denied');assert.equal(f.checked,1);assert.equal(f.consulted,0);
});
test('已鉴权但方法错误的请求仍不能删除会话',async()=>{
  const f=fixture(undefined);let code;
  await f.handler({method:'GET'},{writeHead:status=>code=status,end(){}});
  assert.equal(code,405);assert.equal(f.checked,1);assert.equal(f.consulted,0);
});
test('移动 UI 模块对文件面板声明完整服务依赖',()=>{
  let plugin;
  vm.runInNewContext(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),
    {window:{__ModuleLoader__:{load:value=>plugin=value}}});
  const exported=plugin.factory(()=>({}));
  assert.ok(exported.inject.includes('sidebarRight'));
  assert.ok(exported.inject.includes('sessions')&&exported.inject.includes('sessionLogDownload'));
});
