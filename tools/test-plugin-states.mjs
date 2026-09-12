// 使用锁定的 Cordis 与可控桥 IO，覆盖真实状态变化、迟到的桥及卸载时序。
import test from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { collectPluginStates, reportPluginStates } from '../app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js';

test('等待、卸载与未知状态不报错；一个失败实例不能被成功实例掩盖', () => {
  const states = new Map(['pending','loading','active','failed','disposed','unloading','unknown','empty','mixed']
    .map((name,i) => [name,{name,fibers:i===7?[]:i===8?[{state:2},{state:3}]:[{state:i}]}]));
  assert.deepEqual(collectPluginStates({registry:states}), {
    loaded:['active','mixed'],failed:['failed:加载失败','mixed:加载失败'],pending:['loading:正在加载','pending:等待依赖'],
  });
});

test('桥晚于启动可重试，状态变化串行发送并清除已恢复故障，卸载取消后续发送', async () => {
  const handlers=new Map(), timers=new Map(), requests=[];
  let serial=0, token='', settle;
  const runtime={name:'test-plugin',fibers:[{state:3}]};
  const ctx={registry:new Map([['test',runtime]]),on:(event,fn)=>handlers.set(event,fn)};
  const io={token:()=>token,setTimeout:fn=>{timers.set(++serial,fn);return serial;},clearTimeout:id=>timers.delete(id),
    fetch:(url,options)=>{requests.push({url,options});return new Promise(done=>settle=done);}};
  const tick=()=>{const [id,fn]=timers.entries().next().value;timers.delete(id);return fn();};
  const ok=()=>settle({ok:true,text:async()=> 'OK'});
  reportPluginStates(ctx,io);await tick();assert.equal(requests.length,0);assert.equal(timers.size,1);
  token='test-secret';const first=tick();assert.equal(requests.length,1);
  assert.equal(new URL(requests[0].url).searchParams.get('token'),null);
  assert.equal(requests[0].options.headers['X-Token'],token);
  runtime.fibers[0].state=2;handlers.get('internal/status')();assert.equal(timers.size,0);
  ok();await first;const second=tick();assert.equal(requests.length,2);
  assert.equal(new URL(requests[1].url).searchParams.get('failed'),'');
  assert.equal(new URL(requests[1].url).searchParams.get('loaded'),'test-plugin');
  ok();await second;await tick();assert.equal(requests.length,2);
  runtime.fibers=[];handlers.get('internal/plugin')();const last=tick();assert.equal(requests.length,3);
  handlers.get('dispose')();ok();await last;assert.equal(timers.size,0);
});

test('锁定 Cordis 实例：等待依赖、异步加载、实际失败和卸载的报告一致', async () => {
  const {Context}=await import(pathToFileURL(resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/rc1-20260910/locked-runtime','node_modules/@deepseek-ai/cordis/lib/index.js')));
  const ctx=new Context();const fibers=[];
  try {
    const pending=ctx.plugin({name:'test-pending',inject:['fixtureMissing'],apply(){}});fibers.push(pending);
    assert.equal(pending.state,0);
    let resolveLoad;
    const loading=ctx.plugin({name:'test-loading',apply:()=>new Promise(done=>resolveLoad=done)});fibers.push(loading);
    for(let i=0;i<20&&!resolveLoad;i++)await new Promise(done=>setImmediate(done));
    assert.equal(loading.state,1);
    const failed=ctx.plugin({name:'test-failed',apply(){throw new Error('fixture-expected-failure');}});fibers.push(failed);
    await assert.rejects(failed.await());
    const first=collectPluginStates(ctx);
    assert.ok(first.failed.includes('test-failed:加载失败'));
    assert.ok(first.pending.includes('test-pending:等待依赖'));
    assert.ok(first.pending.includes('test-loading:正在加载'));
    resolveLoad();await loading;
    assert.ok(collectPluginStates(ctx).loaded.includes('test-loading'));
    await failed.dispose();assert.deepEqual(collectPluginStates(ctx).failed,[]);
  } finally { await Promise.all(fibers.map(fiber=>fiber.dispose())); }
});
