// 用锁定的持久化后端验证 UI 删除入口；所有数据位于随机测试目录。
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm, readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/locked-dsh-runtime');
const require = createRequire(join(runtime,'package.json'));
const {Context} = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const {default:Jsonl} = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')));
const {deleteSession} = await import(pathToFileURL(resolve(process.env.DSHA_MOBILE_DELETE || 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/delete-session.js')));

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
