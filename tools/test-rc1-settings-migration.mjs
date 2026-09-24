import { testRuntime } from './test-runtime-fixture.mjs';
// 真实锁定 SettingsForms.describe/update + schema，隔离临时数据，无模型/设备访问。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const expectedVersion = JSON.parse(await readFile(join(runtime,'package.json'),'utf8')).version;
const { SettingsForms } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')));
const { default: z } = await import(pathToFileURL(require.resolve('@deepseek-ai/schemastery')));
const { resolveConfig } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { parse } = await import(pathToFileURL(require.resolve('yaml')));
const { migrate } = createRequire(import.meta.url)('../app/src/main/assets/rc1-settings-migration.cjs');
const clone = value => JSON.parse(JSON.stringify(value));
function forms(home) {
  const entries = new Map(); let writes = 0;
  function add(ns, value = { model:'initial', temperature:1 }) {
    const Config = z.object({ model:z.string().volatile(), temperature:z.number().min(0).volatile(), fixed:z.string().default('untouched') });
    const runtime = { Config };
    const entry = { id:ns, options:{id:ns,config:clone(value)}, fiber:{uid:ns,state:2,runtime,ctx:{},config:resolveConfig(runtime,value)} };
    entries.set(ns, entry); return entry;
  }
  const settings=Object.create(SettingsForms.prototype);
  settings.revisions=new Map();settings.presentations=new Map();
  settings.ownerContext={profileContext:{home,name:'web'},emit(){},logger:{info(){}},configEditor:{
    entries:()=>[...entries.values()],
    configuration:()=>[...entries.values()].map(entry=>({entry,inherited:{},override:entry.options.config})),
    async edit(entry,change){const next=change(clone(entry.options.config),{});const config=resolveConfig(entry.fiber.runtime,next);entry.options.config=next;entry.fiber.config=config;writes++;}
  }};
  return {settings,add,entries,get writes(){return writes;},change(ns,value){const entry=entries.get(ns);entry.options.config={...entry.options.config,...value};entry.fiber.config=resolveConfig(entry.fiber.runtime,entry.options.config);}};
}
async function fixture(document, run) {
  const root=await mkdtemp(join(tmpdir(),'dsha-real-settings-'));const home=join(root,'.dsh'),state=join(root,'state');await mkdir(home);await writeFile(join(home,'settings.yaml'),document);
  const p=spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py','prepare','--root',root,'--state-root',state,'--startup-id','test'],{encoding:'utf8'});
  assert.equal(p.status,0,p.stderr+p.stdout);
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));const ledger=join(state,'generations',current.generation,'settings-result.json');
  try{await run(forms(home),state,ledger,root);}finally{await rm(root,{recursive:true,force:true});}
}
test('pending真实次序只重试失败节，已成功节的用户新改动保持',()=>fixture('first: {model: old1}\nmissing: {model: old2}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,1);assert.equal(JSON.parse(await readFile(ledger)).status,'pending');
  api.change('first',{model:'user-later'});api.add('missing');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,2);assert.equal(api.settings.describe().find(x=>x.ns==='first').value.model,'user-later');assert.equal(api.settings.describe().find(x=>x.ns==='missing').value.model,'old2');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,2);assert.equal(JSON.parse(await readFile(ledger)).status,'verified');
}));
test('真实schema拒绝非法值，日志缺失不等于成功',()=>fixture('first: {temperature: -1}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);assert.equal(JSON.parse(await readFile(ledger)).status,'pending');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);
}));
test('pending后目标改变时冲突保留，不重放旧值',()=>fixture('first: {temperature: -1}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});api.change('first',{model:'user-new'});await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(JSON.parse(await readFile(ledger)).sections.first.status,'conflict');assert.equal(api.settings.describe()[0].value.model,'user-new');assert.equal(api.writes,0);
}));
test('快照正文被改动时不调用任何配置写入',()=>fixture('first: {model: old}\n',async(api,state,ledger,root)=>{
  api.add('first');const current=JSON.parse(await readFile(join(state,'current.json')));await writeFile(join(state,'generations',current.generation,'snapshots','0'),'tampered');
  await assert.rejects(migrate(api.settings,parse,{}, {stateRoot:state}),/SNAPSHOT_CHANGED/);assert.equal(api.writes,0);
}));

test('敏感字段只通过实际设置服务读回，不写入迁移回执',()=>fixture('first: {model: old, options: {apiKey: incoming-secret}}\n',async(api,state,ledger)=>{
  const entry=api.add('first',{model:'initial',options:{size:1,apiKey:'retained-secret'}});
  // fixture schema has no options field, so this is rejected before any write; ledger must remain redacted.
  await migrate(api.settings,parse,{}, {stateRoot:state});
  const text=await readFile(ledger,'utf8');assert.equal(text.includes('incoming-secret'),false);assert.equal(text.includes('retained-secret'),false);
}));

test('源正文在正式写入前变化时拒绝旧快照',()=>fixture('first: {model: old}\n',async(api,state,ledger,root)=>{
  api.add('first');await writeFile(join(root,'.dsh','settings.yaml'),'first: {model: changed}\n');
  await assert.rejects(migrate(api.settings,parse,{}, {stateRoot:state}),/SOURCE_CHANGED/);assert.equal(api.writes,0);
}));
