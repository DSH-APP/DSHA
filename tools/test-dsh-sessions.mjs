import { testRuntime } from './test-runtime-fixture.mjs';
// 使用固定版本的真实持久化后端，覆盖 V4 写入和 rc.1 旧代际升级；不访问模型。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, readdir, lstat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { default: Jsonl } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')));
const { Session } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session')));
const { sessionFormatLogFilename } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-format')));
const { zstdCompressSync, constants } = await import('node:zlib');
const event = (type, seq, data, extra = {}) => ({ type, seq, time: seq + 100, data, ...extra });
const user = { id: 'fixture-user', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '保留这句话' }] };
const rows = [event('turn/start', 0, { turn: 1 }), event('user/message', 1, user, { surfaceOp: 'append' }),
  event('step/start', 2, { turn: 1, step: 1 }), event('step/end', 3, { turn: 1, step: 1 }),
  event('turn/end', 4, { turn: 1, reason: { kind: 'completed' } })];

async function fixture(compression, run) {
  const root = await mkdtemp(join(tmpdir(), 'dsha-session-'));
  const ctx = new Context();
  try { await ctx.plugin(Jsonl, { root, compression }); await run(ctx.sessionPersistence, root); }
  finally { await ctx.fiber.dispose(); await rm(root, { recursive: true, force: true }); }
}
function filename(version, compression) { return sessionFormatLogFilename(version) + (compression === 'zstd' ? '.zstd' : ''); }

for (const compression of ['none', 'zstd']) {
  test(`V4 ${compression} 写入、继续追加和排他写句柄`, () => fixture(compression, async (store, root) => {
    const id = 'new-session';
    const handle = await store.create({ version: 4, id, createdAt: 1, isSeeded: false });
    await handle.append(rows); await handle.flush();
    assert.deepEqual((await handle.read()).events, rows);
    await assert.rejects(store.open(id, 'write'));
    await handle.close();
    const file = join(root, '_no-cwd', id, filename(4, compression));
    assert.equal((await lstat(file)).isSymbolicLink(), false);
    const writer = await store.open(id, 'write');
    const extra = [event('turn/start', 5, { turn: 2 }), event('turn/end', 6, { turn: 2, reason: { kind: 'completed' } })];
    await writer.append(extra); await writer.close();
    const reader = await store.open(id, 'read');
    assert.deepEqual((await reader.read()).events, [...rows, ...extra]); await reader.close();
  }));
  for (const version of [0, 1, 2, 3]) test(`真实 V${version} ${compression} 输入转换为 V4 并保留旧源`, () => fixture(compression, async (store, root) => {
    const id = `legacy-v${version}`;
    const directory = join(root, '_no-cwd', id); await mkdir(directory, { recursive: true });
    let header = { type:'session', version, id, createdAt:1, delegationDepth:0, ...(version >= 2 ? {isSeeded:false} : {}) };
    const legacyRows = [event('turn/start', 0, { turn: 1 }), event('step/start', 1, { turn: 1, step: 1 }), event('user/message', 2, user, { surfaceOp: 'append' }), event('request/header', 3, { reason: 'initial', header: { config: { provider: 'mock', model: 'mock' }, system: 'legacy system' } })];
    let physicalRows = legacyRows;
    if (version >= 2) {
      const { releasedV2SessionFormatCodec, releasedV3SessionFormatCodec } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-format-v2-to-v3')));
      const codec = version === 2 ? releasedV2SessionFormatCodec : releasedV3SessionFormatCodec;
      header = codec.encodeHeader({version,id,createdAt:1,delegationDepth:0,isSeeded:false}, 0);
      physicalRows = rows.map(event => codec.encodeEvent(event));
    }
    const first = JSON.stringify(header)+'\n', tail=physicalRows.map(row=>JSON.stringify(row)).join('\n')+'\n';
    const bytes=compression==='zstd'?Buffer.concat([zstdCompressSync(Buffer.from(first)),zstdCompressSync(Buffer.from(tail))]):Buffer.from(first+tail);
    const original=join(directory,filename(version,compression));await writeFile(original,bytes);
    let reader;
    try { reader=await store.open(id,'read'); }
    catch (error) {
      // V0/V1/V2 的最小合成序列若缺少可证明的 system head，rc1 必须拒绝而保留原件；
      // 这仍然是可接受的升级结果，不能伪造 chronology。V3 夹具继续要求 V4 successor。
      if (version < 3) { assert.match(String(error), /chronology|system head|unsupported|cannot safely transform/); assert.deepEqual(await readFile(original),bytes); assert.equal((await readdir(directory)).some(name=>name.startsWith('session.v4.')),false); return; }
      throw error;
    }
    assert.equal(reader.header.version,4);const prepared=(await reader.read()).events;await reader.close();
    assert.deepEqual(await readFile(original),bytes);assert.equal((await readdir(directory)).some(name=>name.startsWith('session.v4.')),false);
    const writer=await store.open(id,'write');assert.equal(writer.header.version,4);assert.deepEqual((await writer.read()).events,prepared);
    const restored=Session.fromRestore(id,prepared,writer.header,writer.inheritedEventCount,'shared-frozen');assert.ok(restored.deriveMessages().some(message=>message.id==='fixture-user'));
    await writer.close();assert.deepEqual(await readFile(original),bytes);assert.equal((await lstat(join(directory,filename(4,compression)))).isSymbolicLink(),false);
    const reopened=await store.open(id,'write');await reopened.append([event('turn/start',prepared.length,{turn:2}),event('turn/end',prepared.length+1,{turn:2,reason:{kind:'completed'}})]);await reopened.close();
    assert.deepEqual(await readFile(original),bytes);
  }));
}

test('不受支持的真实 V0 chronology 拒绝迁移并保留原件，不伪造事件',()=>fixture('none',async(store,root)=>{
  const content=await readFile(new URL('./fixtures/sessions/released-v0-real-shapes.jsonl',import.meta.url));
  const id='released-v0-real-shapes',directory=join(root,'--work--',id);await mkdir(directory,{recursive:true});const original=join(directory,filename(0,'none'));await writeFile(original,content);
  await assert.rejects(store.open(id,'write'),/unsupported|cannot.*migrat|format|chronology|turn|step/i);
  assert.deepEqual(await readFile(original),content);assert.equal((await readdir(directory)).some(name=>name.startsWith('session.v4.')),false);
}));

test('旧版设备引导消息的自定义来源不会阻断 V3 迁移', () => fixture('none', async (store, root) => {
  const id = 'old-guide'; const directory = join(root, '_no-cwd', id); await mkdir(directory, { recursive: true });
  const guide = { ...user, id: 'old-device-guide', source: { kind: 'dsh-device-guide', plugin: 'dsh-device-shell-guide' } };
  const oldRows = rows.map(row => row.type === 'user/message' ? { ...row, data: guide } : row);
  const original = [{ type: 'session', version: 0, id, createdAt: 1, delegationDepth: 0 }, ...oldRows].map(row => JSON.stringify(row)).join('\n') + '\n';
  await writeFile(join(directory, filename(0, 'none')), original);
  await assert.rejects(store.open(id, 'write'), /source|chronology|unsupported|cannot safely transform/);
  assert.deepEqual(await readFile(join(directory, filename(0, 'none'))), Buffer.from(original));
}));
