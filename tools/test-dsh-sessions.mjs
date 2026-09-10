// 使用固定版本的真实持久化后端，覆盖 V3 写入和 rc.1 旧代际升级；不访问模型。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, readdir, lstat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/dsh-alpha15/linux-runtime');
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
  test(`V3 ${compression} 写入、继续追加和排他写句柄`, () => fixture(compression, async (store, root) => {
    const id = 'new-session';
    const handle = await store.create({ version: 3, id, createdAt: 1, isSeeded: false });
    await handle.append(rows); await handle.flush();
    assert.deepEqual((await handle.read()).events, rows);
    await assert.rejects(store.open(id, 'write'));
    await handle.close();
    const file = join(root, '_no-cwd', id, filename(3, compression));
    assert.equal((await lstat(file)).isSymbolicLink(), false);
    const writer = await store.open(id, 'write');
    const extra = [event('turn/start', 5, { turn: 2 }), event('turn/end', 6, { turn: 2, reason: { kind: 'completed' } })];
    await writer.append(extra); await writer.close();
    const reader = await store.open(id, 'read');
    assert.deepEqual((await reader.read()).events, [...rows, ...extra]); await reader.close();
  }));
  test(`旧 V0 ${compression} 会话迁移保留原文件与消息身份`, () => fixture(compression, async (store, root) => {
    const id = 'released-v0-real-shapes';
    const content = await readFile(process.env.DSHA_LEGACY_FIXTURE || 'app/build/dsh-alpha15/upstream/packages/session/session-persistence-jsonl/tests/fixtures/released-v0-real-shapes.jsonl', 'utf8');
    const directory = join(root, '--work--', id); await mkdir(directory, { recursive: true });
    const original = join(directory, filename(0, compression));
    const bytes = compression === 'none' ? Buffer.from(content) : zstdCompressSync(Buffer.from(content), { params: { [constants.ZSTD_c_checksumFlag]: 1 } });
    // 每份压缩日志的首帧只包含 header，遵循上游读取契约。
    if (compression === 'zstd') {
      const at = content.indexOf('\n') + 1;
      await writeFile(original, Buffer.concat([zstdCompressSync(Buffer.from(content.slice(0, at))), zstdCompressSync(Buffer.from(content.slice(at)))]));
    } else await writeFile(original, bytes);
    const before = await readFile(original);
    const reader = await store.open(id, 'read');
    assert.equal(reader.header.version, 3);
    const prepared = (await reader.read()).events; await reader.close();
    assert.deepEqual(await readFile(original), before);
    assert.equal((await readdir(directory)).some(name => name.startsWith('session.v3.')), false);
    const writer = await store.open(id, 'write');
    assert.equal(writer.header.version, 3);
    assert.deepEqual((await writer.read()).events, prepared);
    const restored = Session.fromRestore(id, prepared, writer.header, writer.inheritedEventCount, 'shared-frozen');
    assert.ok(restored.deriveMessages().some(message => message.id === 'late-user'));
    await writer.close();
    assert.deepEqual(await readFile(original), before);
    assert.equal((await lstat(join(directory, filename(3, compression)))).isSymbolicLink(), false);
  }));
}
test('旧版设备引导消息的自定义来源不会阻断 V3 迁移', () => fixture('none', async (store, root) => {
  const id = 'old-guide'; const directory = join(root, '_no-cwd', id); await mkdir(directory, { recursive: true });
  const guide = { ...user, id: 'old-device-guide', source: { kind: 'dsh-device-guide', plugin: 'dsh-device-shell-guide' } };
  const oldRows = rows.map(row => row.type === 'user/message' ? { ...row, data: guide } : row);
  await writeFile(join(directory, filename(0, 'none')), [{ type: 'session', version: 0, id, createdAt: 1, delegationDepth: 0 }, ...oldRows].map(row => JSON.stringify(row)).join('\n') + '\n');
  const writer = await store.open(id, 'write');
  assert.equal((await writer.read()).events.find(row => row.type === 'user/message').data.id, 'old-device-guide');
  await writer.close();
}));
