// 3.2.0 retires automatic model-setting writes; the official settings UI owns reasoning levels.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {apply} from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js';

test('actual deployed server module closure stays complete after retiring reasoning writes', () => {
  const manifest = JSON.parse(readFileSync('app/src/main/assets/managed-runtime-inputs.json','utf8'));
  const prefix = 'builtin-plugins/dsh-web-mobile/';
  const entries = manifest.installs.filter(row=>row.asset.startsWith(prefix));
  const queue = ['lib/index.js'], visited = new Set();
  while (queue.length) {
    const relative = queue.pop();
    if (visited.has(relative)) continue;
    visited.add(relative);
    assert.equal(entries.find(row=>row.asset===prefix+relative)?.target, 'root/dsha-web-mobile/'+relative);
    const content = readFileSync('app/src/main/assets/'+prefix+relative,'utf8');
    for (const match of content.matchAll(/from ['"]\.\/([^'"]+)['"]/g)) queue.push('lib/'+match[1]);
  }
  assert.deepEqual([...visited].sort(), ['lib/compress.js','lib/delete-session.js','lib/index.js']);
});

for (const [name, models] of Object.entries({
  missing:[{id:'missing',contextWindow:128000}],
  explicit:[{id:'mapped',reasoningEfforts:{high:'ultra'}}],
  disabled:[{id:'disabled',reasoningEfforts:false}],
  unsupported:[{id:'native-default',compat:{supportsReasoningEffort:false}}],
})) test(`real plugin activation leaves ${name} model settings untouched`, async t => {
  const user={providers:{custom:{models}}}, before=structuredClone(user);
  let reads=0,writes=0;const requested=[],disposers=[];
  const settings={describe(){reads++;return [{ns:'llm-pi-ai',revision:7,value:user,user}];},
    mutate(){writes++;throw Error('3.2.0 must not write model settings');}};
  t.after(()=>{for(const dispose of disposers.reverse())dispose?.();});
  apply({inject(names,fn){requested.push(names);if(!names.includes('settings'))return;
    fn({get:()=>settings,logger:{warn(){}},effect(run){disposers.push(run());},on(){}});
  }});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(requested.some(names=>names.includes('settings')),false);
  assert.equal(reads,0);assert.equal(writes,0);assert.equal(disposers.length,0);
  assert.deepEqual(user,before);
});

test('retired server and client defaults are absent from the actual bundled package', () => {
  const root='app/src/main/assets/builtin-plugins/dsh-web-mobile/';
  for(const name of ['lib/reasoning-effort.js','lib/reasoning-effort.js.map',
    'src/reasoning-effort.ts','src/client/effects/reasoning-defaults.ts'])
    assert.equal(existsSync(root+name),false,name);
  const client=readFileSync(root+'lib/client.js','utf8');
  assert.equal(client.includes('__modules["effects/reasoning-defaults.js"]'),false);
  assert.equal(client.includes('fillReasoningEffortDefaults'),false);
  assert.equal(readFileSync(root+'lib/index.js','utf8').includes('fillReasoningEffortDefaults'),false);
});
