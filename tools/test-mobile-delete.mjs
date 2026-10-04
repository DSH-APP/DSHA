import { testRuntime } from './test-runtime-fixture.mjs';
// 用锁定的持久化后端验证 UI 删除入口；所有数据位于随机测试目录。
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm, readdir, mkdir, readFile, writeFile, utimes, lstat, symlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const runtime = testRuntime('raw');
const require = createRequire(join(runtime,'package.json'));
const {Context} = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const {default:Jsonl} = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')));
const {deleteSession,installSessionDeletionLifecycle} = await import(pathToFileURL(resolve(process.env.DSHA_MOBILE_DELETE || 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/delete-session.js')));
const load = name => import(pathToFileURL(require.resolve(name)));
const services = await Promise.all(['dsh-session','dsh-agent','dsh-session-projection','dsh-llm','dsh-system-prompt','dsh-tools','dsh-agent-loop'].map(name => load('@deepseek-ai/'+name)));

async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(),'dsha-mobile-delete-')), ctx = new Context();
  try {
    await ctx.plugin(Jsonl,{root,compression:'none'});
    for (const id of ['own/../测试','keep-neighbor']) {
      const handle = await ctx.sessionPersistence.create({version:4,id,createdAt:1,isSeeded:false,cwd:'/root/临时项目'});
      await handle.append([{type:'turn/start',seq:0,time:1,data:{turn:1}},
        {type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}]);
      await handle.close();
    }
    await run(ctx.sessionPersistence,root);
  } finally { await ctx.fiber.dispose(); await rm(root,{recursive:true,force:true}); }
}
test('新版移动 UI 可删除真实 V4 会话，路径转义保留相邻会话',()=>fixture(async persistence=>{
  const result = await deleteSession({persistence},'own/../测试');
  assert.equal(result.ok,true);
  assert.deepEqual((await persistence.list()).map(row=>row.header.id),['keep-neighbor']);
  const reader = await persistence.open('keep-neighbor','read');
  assert.equal((await reader.read()).events.length,2); await reader.close();
}));
test('没有可停止句柄的活动会话明确拒绝删除，已有日志保持可读',()=>fixture(async persistence=>{
  const result=await deleteSession({persistence,sessions:{get:()=>({})},agents:{get:()=>({})}},'own/../测试');
  assert.equal(result.status,409); assert.equal((await persistence.list()).length,2);
}));

test('删除保留真实日志原件、相邻数据与超过24小时的未知历史trash',()=>fixture(async(persistence,root)=>{
  const historical=join(root,'.sessions-trash','unknown-history');
  await mkdir(historical,{recursive:true}); await writeFile(join(historical,'original.bin'),'must remain');
  await utimes(historical,1,1);
  const result=await deleteSession({persistence},'own/../测试');assert.equal(result.ok,true);
  assert.equal(await readFile(join(historical,'original.bin'),'utf8'),'must remain');
  const trash=(await readdir(join(root,'.sessions-trash'))).find(name=>name!=='unknown-history');
  const manifest=JSON.parse(await readFile(join(root,'.sessions-trash',trash,'manifest.json'),'utf8'));
  assert.equal(manifest.id,'own/../测试');assert.equal(manifest.files.length,1);
  const retained=await readFile(join(root,'.sessions-trash',trash,manifest.files[0].to),'utf8');
  assert.equal(retained.split('\n').filter(Boolean).length,3);
  assert.equal((await persistence.list()).length,1);
}));

test('trash同名原件冲突拒绝覆盖，并回滚先前已改名的旧代日志',()=>fixture(async(persistence,root)=>{
  const project=(await readdir(root)).find(name=>name.startsWith('--'));
  const idDirectory=(await readdir(join(root,project))).find(name=>name.startsWith('own'));
  const directory=join(root,project,idDirectory);
  const current=(await readdir(directory)).find(name=>name.endsWith('.jsonl'));
  const original=await readFile(join(directory,current));
  const legacy=JSON.stringify({type:'session',version:0,id:'own/../测试',createdAt:1,delegationDepth:0,cwd:'/root/临时项目'})+'\n';
  await writeFile(join(directory,'session.jsonl'),legacy);
  await writeFile(join(directory,current+'.trash'),'unknown original');
  const result=await deleteSession({persistence},'own/../测试');assert.equal(result.status,500,JSON.stringify(result));
  assert.equal(await readFile(join(directory,current+'.trash'),'utf8'),'unknown original');
  assert.deepEqual(await readFile(join(directory,current)),original);
  assert.equal(await readFile(join(directory,'session.jsonl'),'utf8'),legacy);
  assert.equal((await persistence.list()).length,2);
}));

test('trash链接拒绝写入其他目录，真实会话原件保持可读',()=>fixture(async(persistence,root)=>{
  const outside=join(root,'external-target');await mkdir(outside);await writeFile(join(outside,'original'),'untouched');
  await symlink(outside,join(root,'.sessions-trash'),process.platform==='win32'?'junction':'dir');
  const result=await deleteSession({persistence},'own/../测试');assert.equal(result.status,500);
  assert.equal(await readFile(join(outside,'original'),'utf8'),'untouched');
  assert.equal((await persistence.list()).length,2);
}));

async function liveFixture(run,{capture=true}={}) {
  const root=await mkdtemp(join(tmpdir(),'dsha-mobile-rc2-live-')),ctx=new Context();let release=()=>{};
  try {
    for(let i=0;i<services.length-1;i++)await ctx.plugin(services[i].default, i===5?{mode:'native'}:{});
    await ctx.plugin(Jsonl,{root,compression:'none'});
    await ctx.plugin(services.at(-1).default,{agents:[],maxParallelToolCalls:1});
    if(capture)release=installSessionDeletionLifecycle(ctx.agents);
    await run(ctx,root,{persistence:ctx.sessionPersistence,sessions:ctx.sessions,agents:ctx.agents});
  }finally{release();await ctx.fiber.dispose();await rm(root,{recursive:true,force:true});}
}

test('真实rc2运行中Agent：取消→whenIdle→durable flush→完整dispose后才保留到trash',()=>liveFixture(async(ctx,root,deps)=>{
  const handle=await ctx.agents.create({sessionId:'live',meta:{cwd:'/owned/project'}}),agent=handle.agent;
  const sequence=[];let lastEvent;
  ctx.on('session/flush',session=>{if(session.id==='live')sequence.push('flush');});
  ctx.on('agent/disposed',({agent})=>{if(agent.id==='live')sequence.push('agent-disposed');});
  ctx.on('session/disposed',session=>{if(session.id==='live')sequence.push('session-disposed');});
  let aborted;
  const work=agent.runMaintenance(async signal=>{
    agent.session.append('turn/start',{turn:1});
    await new Promise(resolve=>signal.addEventListener('abort',async()=>{
      aborted=signal.reason;sequence.push('cancel');
      await new Promise(resolve=>setTimeout(resolve,25));
      lastEvent=agent.session.append('turn/end',{turn:1,reason:{kind:'completed'}});
      sequence.push('idle');resolve();
    },{once:true}));
  });
  assert.equal(await ctx.sessions.flush(agent.session),true);
  sequence.length=0;
  const result=await deleteSession(deps,'live');await work;
  assert.equal(result.ok,true,JSON.stringify(result));assert.equal(aborted.kind,'disposed');
  assert.deepEqual(sequence,['cancel','idle','flush','agent-disposed','session-disposed']);
  assert.equal(ctx.agents.get('live'),undefined);assert.equal(ctx.sessions.get('live'),undefined);
  assert.deepEqual(await ctx.sessionPersistence.list(),[]);
  const trash=(await readdir(join(root,'.sessions-trash')))[0];
  const manifest=JSON.parse(await readFile(join(root,'.sessions-trash',trash,'manifest.json'),'utf8'));
  const rows=(await readFile(join(root,'.sessions-trash',trash,manifest.files[0].to),'utf8')).trim().split('\n').map(JSON.parse);
  assert.ok(rows.some(row=>row.type===lastEvent.type&&row.seq===lastEvent.seq),'final cancellation event must be durably retained');
  // Disposal released the write lease: retained bytes can be restored/reopened.
  const restored=join(root,'--owned-project--','live');await mkdir(restored,{recursive:true});
  for(const file of manifest.files)await writeFile(join(restored,file.from),await readFile(join(root,'.sessions-trash',trash,file.to)));
  const reopened=await ctx.sessionPersistence.open('live','write');assert.equal((await reopened.read()).events.at(-1).type,'turn/end');await reopened.close();
}));

test('真实rc2恢复句柄也可安全删除，保留最后写入而不访问私有store',()=>liveFixture(async(ctx,root,deps)=>{
  const original=await ctx.agents.create({sessionId:'resumed',seed:[{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}]});
  await original.dispose();
  const resumed=await ctx.agents.resume({resumeSessionId:'resumed'});
  assert.equal(ctx.sessions.get('resumed'),resumed.agent.session);
  const result=await deleteSession(deps,'resumed');assert.equal(result.ok,true,JSON.stringify(result));
  assert.equal(ctx.agents.get('resumed'),undefined);assert.deepEqual(await ctx.sessionPersistence.list(),[]);
  assert.equal((await readdir(join(root,'.sessions-trash'))).length,1);
}));

test('rc2 Cordis scope中的公开创建仍保留精确handle，删除后不dispose整个调用者fiber',()=>liveFixture(async(ctx,_root,deps)=>{
  const {createScope}=await load('@deepseek-ai/dsh-scope');const scope=createScope(ctx,{});
  try{
    let consumer;await scope.ctx.plugin({inject:['agents'],apply(owner){consumer=owner;}});
    const handle=await consumer.agents.create({sessionId:'scoped-owner',seed:[{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}]});
    const result=await deleteSession(deps,'scoped-owner');assert.equal(result.ok,true,JSON.stringify(result));
    consumer.fiber.assertActive();assert.equal(ctx.agents.get('scoped-owner'),undefined);
    await handle.dispose();
  }finally{await scope.dispose();}
}));

test('真实rc2未捕获的live句柄拒绝注销，现有可读日志和registry保留',()=>liveFixture(async(ctx,_root,deps)=>{
  const handle=await ctx.agents.create({sessionId:'uncaptured',seed:[{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}]});
  const result=await deleteSession(deps,'uncaptured');assert.equal(result.status,409);
  assert.equal(ctx.agents.get('uncaptured'),handle.agent);assert.equal(ctx.sessions.get('uncaptured'),handle.agent.session);
  assert.equal((await ctx.sessionPersistence.list()).length,1);await handle.dispose();
},{capture:false}));

for(const mode of ['flush-failed','flush-unproven','idle-failed','dispose-failed'])test(`真实rc2 ${mode}原件与注册关系保持，不伪报删除`,()=>liveFixture(async(ctx,root,deps)=>{
  const handle=await ctx.agents.create({sessionId:mode,seed:[{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}]});
  let fakeDeps=deps,restore=()=>{};
  if(mode.startsWith('flush')) fakeDeps={...deps,sessions:{get:id=>ctx.sessions.get(id),flush:async()=>{if(mode==='flush-failed')throw Error('fixture I/O rejection');return false;}}};
  else if(mode==='idle-failed'){const original=handle.agent.whenIdle;handle.agent.whenIdle=async()=>{throw Error('fixture idle rejection');};restore=()=>handle.agent.whenIdle=original;}
  else{const original=handle.dispose;handle.dispose=async()=>{throw Error('fixture close rejection');};restore=()=>handle.dispose=original;}
  try{
    const result=await deleteSession(fakeDeps,mode);assert.equal(result.status,409,JSON.stringify(result));
    assert.equal(ctx.agents.get(mode),handle.agent);assert.equal(ctx.sessions.get(mode),handle.agent.session);
    assert.equal((await ctx.sessionPersistence.list()).length,1);
    await assert.rejects(lstat(join(root,'.sessions-trash')),{code:'ENOENT'});
  }finally{restore();await handle.dispose();}
}));

test('实际创建尚在setup时拒绝删除；删除持有期间阻止同id resume并拒绝重复删除',()=>liveFixture(async(ctx,_root,deps)=>{
  let finishSetup;const setupBarrier=new Promise(resolve=>finishSetup=resolve);
  const pending=ctx.agents.create({sessionId:'racing',seed:[{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'turn/end',seq:1,time:2,data:{turn:1,reason:{kind:'completed'}}}],setup:()=>setupBarrier});
  const blocked=await deleteSession(deps,'racing');assert.equal(blocked.status,409);finishSetup();
  const handle=await pending;let finishFlush;const flushBarrier=new Promise(resolve=>finishFlush=resolve);
  const slowDeps={...deps,sessions:{get:id=>ctx.sessions.get(id),flush:async session=>{await flushBarrier;return ctx.sessions.flush(session);}}};
  const first=deleteSession(slowDeps,'racing');await new Promise(resolve=>setTimeout(resolve,15));
  await assert.rejects(ctx.agents.resume({resumeSessionId:'racing'}),/being deleted/);
  assert.equal((await deleteSession(deps,'racing')).status,409);
  finishFlush();assert.equal((await first).ok,true);await handle.dispose();
}));

test('workspace注销失败明确返回警告，日志仍完整保留；缺少public方法提前拒绝',()=>fixture(async(persistence,root)=>{
  const bad=await deleteSession({persistence,workspaceRegistry:{list:()=>[{}]}},'own/../测试');
  assert.equal(bad.ok,false);assert.equal((await persistence.list()).length,2);
  const result=await deleteSession({persistence,workspaceRegistry:{list:()=>[{id:'fixture',detachSession:async()=>{throw Error('fixture');}}]}},'own/../测试');
  assert.equal(result.ok,true);assert.equal(result.warnings.length,1);assert.equal((await readdir(join(root,'.sessions-trash'))).length,1);
}));
