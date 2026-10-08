// 验证实际随包的 3.0.5 设置补齐及 DSHA 服务入口，使用独立设置夹具。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {apply} from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js';
import {DEFAULT_REASONING_EFFORTS, planReasoningEffortFill, fillReasoningEffortDefaults}
  from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/reasoning-effort.js';

test('实际部署清单覆盖移动插件所有相对服务模块依赖', () => {
  const manifest = JSON.parse(readFileSync('app/src/main/assets/managed-runtime-inputs.json','utf8'));
  const prefix = 'builtin-plugins/dsh-web-mobile/';
  const entries = manifest.installs.filter(row=>row.asset.startsWith(prefix));
  const queue = ['lib/index.js'];
  const visited = new Set();
  while(queue.length) {
    const relative = queue.pop();
    if(visited.has(relative))continue;
    visited.add(relative);
    const deployed = entries.find(row=>row.asset===prefix+relative);
    assert.equal(deployed?.target,'root/dsha-web-mobile/'+relative,relative+' 必须实际部署到guest');
    const content = readFileSync('app/src/main/assets/'+prefix+relative,'utf8');
    for(const match of content.matchAll(/from ['"]\.\/([^'"]+)['"]/g))
      queue.push('lib/'+match[1]);
  }
});

test('默认档位只补用户层缺项，保留显式映射、禁用值与其他模型字段', () => {
  const user = {providers:{custom:{models:[{id:'missing',contextWindow:128000},
    {id:'mapped',reasoningEfforts:{high:'ultra'}},{id:'disabled',reasoningEfforts:false}]},
    inherited:{baseUrl:'https://example.invalid'}}};
  const before = structuredClone(user);
  const resolved = structuredClone(user);
  resolved.providers.inherited.models = [{id:'lower-only',input:['text']}];
  const plan = planReasoningEffortFill(resolved,user);
  assert.equal(plan.filled,1);
  assert.equal(plan.unresolved,1);
  assert.equal(plan.ops.length,1);
  assert.deepEqual(plan.ops[0].value,[{...user.providers.custom.models[0],reasoningEfforts:DEFAULT_REASONING_EFFORTS},
    ...user.providers.custom.models.slice(1)]);
  assert.deepEqual(user,before);
});

test('补齐写入带同次读取的版本，冲突失败时不重放旧候选', async () => {
  let writes = 0;
  const user = {providers:{custom:{models:[{id:'missing'}]}}};
  const result = await fillReasoningEffortDefaults({
    describe:()=>[{ns:'llm-pi-ai',revision:42,value:user,user}],
    mutate(ns,ops,revision){writes++;assert.equal(ns,'llm-pi-ai');assert.equal(revision,42);throw Error('revision conflict');},
  });
  assert.equal(result.status,'failed');
  assert.equal(writes,1);
  assert.equal(user.providers.custom.models[0].reasoningEfforts,undefined);
});

test('DSHA 服务入口实际启用上游设置补齐并释放定时器', async () => {
  const user = {providers:{custom:{models:[{id:'missing'}]}}};
  const disposers = [];
  let writes = 0;
  const settings = {
    describe:()=>[{ns:'llm-pi-ai',revision:7,value:user,user}],
    async mutate(ns,ops,revision){assert.equal(revision,7);writes++;return {};},
  };
  try {
    apply({inject(names,fn){
      if(names[0]!=='settings')return;
      fn({get:()=>settings,logger:{warn(){}},effect(run){disposers.push(run());},on(){}});
    }});
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(writes,1);
    assert.equal(disposers.length,1);
  } finally { for(const dispose of disposers.reverse())dispose(); }
});
