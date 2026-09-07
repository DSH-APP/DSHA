import test from 'node:test';
import assert from 'node:assert/strict';
import { knownIdle } from '../app/src/main/assets/app-integration/activity.js';

function context({status='idle', queued=[], jobs=[], active=[], missing=false}={}) {
  const agent={status,inbox:{nextTurn:queued,nextStep:[]}};
  return {agents:{list:()=>[agent]},sessions:{list:()=>[{}]},get:()=>({list:()=>jobs}),
    sessionProjections:{stateOf:()=>missing?undefined:{active}}};
}
test('生成、排队、后台作业和定时任务均不可当作空闲',()=>{
  assert.equal(knownIdle(context()),true);
  for (const state of [{status:'running'},{status:'unknown'},{queued:[{}]},
    {jobs:[{status:'running'}]},{jobs:[{status:'stopping'}]},{active:[{}]},{missing:true}])
    assert.equal(knownIdle(context(state)),false);
});
test('任务结束恢复空闲，接口失效保持保活',()=>{
  assert.equal(knownIdle(context({jobs:[{status:'completed'},{status:'failed'},{status:'killed'}]})),true);
  assert.equal(knownIdle({}),false);
  assert.equal(knownIdle(context({jobs:[{status:'new-upstream-state'}]})),false);
});
