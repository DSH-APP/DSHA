// 新版消息工厂参与的设备引导回归；模拟准入与已提交事件，不调用模型。
import test from 'node:test';
import assert from 'node:assert/strict';
import { cp } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/dsh-alpha15/host-runtime');
const directory = resolve(runtime, 'node_modules/dsh-device-shell-guide');
if (!process.env.DSHA_TEST_GUIDE) {
  if (!runtime.startsWith(resolve('app/build'))) throw Error('测试运行时必须位于 app/build');
  await cp('app/src/main/assets/builtin-plugins/dsh-device-shell-guide', directory, {recursive:true});
}
const guide = await import(pathToFileURL(process.env.DSHA_TEST_GUIDE || resolve(directory, 'lib/index.js')));

function fixture(standard = false, history = []) {
  const handlers = new Map(), sections = [];
  guide.apply({on:(type,fn)=>handlers.set(type,fn),inject(names,apply){
    if (standard) apply({systemPrompt:{section:section=>sections.push(section)},effect:fn=>fn()});
  }});
  const session = { snapshotEvents:()=>history }, original = {kind:'enter', messages:[{id:'human'}]};
  return {sections, original, session, handlers,
    step: (decision=original, extra={})=>handlers.get('agent/pre-step')({agent:{session},step:1,signal:new AbortController().signal,...extra},async()=>decision)};
}
test('标准模式只注入一份系统引导', async()=>{
  const f=fixture(true); assert.equal(f.sections.length,1);
  assert.equal(await f.step(), f.original);
});
test('极简模式必须等消息提交后才判重，失败准入不会吞掉引导', async()=>{
  const f=fixture(); const first=await f.step();
  assert.equal(first.messages[0].role,'user'); assert.equal(first.messages[0].source.kind,'plugin');
  assert.equal(first.messages[0].source.plugin,guide.name); assert.ok(first.messages[0].id);
  assert.equal((await f.step()).messages.length,2);
  f.handlers.get('session/event')(f.session,{type:'user/message',data:first.messages[0]});
  assert.equal(await f.step(),f.original);
});
test('恢复会话、拒绝准入、取消步骤和非首步均不重复注入', async()=>{
  const restored=fixture(false,[{type:'user/message',data:{source:{kind:'plugin',plugin:guide.name}}}]);
  assert.equal(await restored.step(),restored.original);
  const f=fixture(), decision={kind:'reject'};
  assert.equal(await f.step(decision),decision);
  assert.equal(await f.step(f.original,{signal:{aborted:true}}),f.original);
  assert.equal(await f.step(f.original,{step:2}),f.original);
});
