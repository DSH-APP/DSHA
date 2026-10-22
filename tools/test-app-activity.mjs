import test from 'node:test';
import assert from 'node:assert/strict';
import { knownIdle, confirmedIdle } from '../app/src/main/assets/app-integration/activity.js';
import { runtimePlugins } from '../app/src/main/assets/app-integration/runtime-plugins.js';

function context({status='idle', queued=[], jobs=[], active=[], missing=false}={}) {
  const agent={status,inbox:{nextTurn:queued,nextStep:[]},whenIdle:async()=>{}};
  return {agents:{list:()=>[agent]},sessions:{list:()=>[{}]},get:()=>({list:()=>jobs}),
    sessionProjections:{stateOf:()=>missing?undefined:{active}}};
}
test('生成、排队、后台作业和定时任务均不可当作空闲',()=>{
  assert.equal(knownIdle(context()),true);
  for (const state of [{status:'running'},{status:'unknown'},{queued:[{}]},
    {jobs:[{status:'running'}]},{jobs:[{status:'stopping'}]},{active:[{}]},{missing:true}])
    assert.equal(knownIdle(context(state)),false);
});
test('公开 idle 仍在维护时不会撤销保活',async()=>{
  const ctx=context();
  assert.equal(await confirmedIdle(ctx),true);
  ctx.agents.list()[0].whenIdle=()=>new Promise(()=>{});
  assert.equal(await confirmedIdle(ctx,10),false);
  ctx.agents.list()[0].whenIdle=async()=>{ctx.agents.list()[0].status='running'};
  assert.equal(await confirmedIdle(ctx),false);
});
test('动态插件摘要只含公开身份，不保存源码或授权细节',()=>{
  const rows=runtimePlugins([{id:'test-session'}],{listPlugins:()=>[{
    pluginId:'plugin-id',name:'测试插件',currentPackageId:'v1',activeRun:{pluginRunId:'run'},
    hostCode:'sensitive-code',clientCode:'private-client',latestRun:{status:'running',secret:'not-exported'},
  }]});
  assert.equal(rows.length,1);assert.equal(rows[0].active,true);
  assert.equal(rows[0].sessionId,'test-session');
  assert.equal(JSON.stringify(rows).includes('sensitive-code'),false);
  assert.equal(JSON.stringify(rows).includes('not-exported'),false);
  assert.deepEqual(runtimePlugins([],undefined),[]);
});
test('任务结束恢复空闲，接口失效保持保活',()=>{
  assert.equal(knownIdle(context({jobs:[{status:'completed'},{status:'failed'},{status:'killed'}]})),true);
  assert.equal(knownIdle({}),false);
  assert.equal(knownIdle(context({jobs:[{status:'new-upstream-state'}]})),false);
});
