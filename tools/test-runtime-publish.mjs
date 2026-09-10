import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, lstat, readdir, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/dsh-alpha15/linux-runtime');
const { publishExclusive, createPublisher } = await import(pathToFileURL(join(runtime, 'node_modules/dsha-runtime-fs/index.js')));
async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'dsha-publish-'));
  try { await run(root); } finally { await rm(root, { recursive: true, force: true }); }
}
test('发布完整普通文件并保留原始暂存文件', () => fixture(async root => {
  const source = join(root, 'source'), target = join(root, 'target');
  await writeFile(source, '完整的会话数据'); await publishExclusive(source, target);
  assert.equal(await readFile(target, 'utf8'), '完整的会话数据');
  assert.equal(await readFile(source, 'utf8'), '完整的会话数据');
  assert.equal((await lstat(target)).isSymbolicLink(), false);
}));
test('已有目标绝不覆盖，失败清理自己的临时文件', () => fixture(async root => {
  const source = join(root, 'source'), target = join(root, 'target');
  await writeFile(source, 'new'); await writeFile(target, 'old');
  await assert.rejects(publishExclusive(source, target), { code: 'EEXIST' });
  assert.equal(await readFile(target, 'utf8'), 'old');
  assert.equal(await readFile(source, 'utf8'), 'new');
  assert.deepEqual((await readdir(root)).sort(), ['source', 'target']);
}));
test('两个并发发布者只允许一个成功', () => fixture(async root => {
  const a = join(root, 'a'), b = join(root, 'b'), target = join(root, 'target');
  await writeFile(a, 'first'); await writeFile(b, 'second');
  const result = await Promise.allSettled([publishExclusive(a, target), publishExclusive(b, target)]);
  assert.equal(result.filter(row => row.status === 'fulfilled').length, 1);
  assert.equal(result.find(row => row.status === 'rejected').reason.code, 'EEXIST');
  assert.ok(['first', 'second'].includes(await readFile(target, 'utf8')));
}));
test('失效符号链接也算已有目标，不能穿透写入', { skip: process.platform !== 'linux' }, () => fixture(async root => {
  const source = join(root, 'source'), target = join(root, 'target');
  await writeFile(source, 'new'); await symlink('absent', target);
  await assert.rejects(publishExclusive(source, target), { code: 'EEXIST' });
  assert.equal((await lstat(target)).isSymbolicLink(), true);
  await assert.rejects(lstat(join(root, 'absent')), { code: 'ENOENT' });
}));
test('内核失败保留源与目标，普通文件不降级排他语义', { skip: process.platform !== 'linux' }, () => fixture(async root => {
  const source = join(root, 'source'), target = join(root, 'target');
  await writeFile(source, 'new');
  const unsupported = createPublisher(async () => { throw Object.assign(new Error('kernel'), { code: 'ENOSYS' }); });
  await assert.rejects(unsupported(source, target), { code: 'ENOSYS' });
  assert.deepEqual(await readdir(root), ['source']);
}));
test('老内核的私有会话降级仍串行且不覆盖既有数据', { skip: process.platform !== 'linux' }, () => fixture(async root => {
  const a = join(root, 'a'), b = join(root, 'b'), target = join(root, 'target');
  await writeFile(a, 'first'); await writeFile(b, 'second');
  const fallback = createPublisher(async () => { throw Object.assign(new Error('kernel'), { code: 'ENOSYS' }); });
  const result = await Promise.allSettled([fallback(a, target, true), fallback(b, target, true)]);
  assert.equal(result.filter(row => row.status === 'fulfilled').length, 1);
  assert.equal(result.find(row => row.status === 'rejected').reason.code, 'EEXIST');
  assert.ok(['first', 'second'].includes(await readFile(target, 'utf8')));
  assert.equal((await readdir(root)).some(name => name.endsWith('.tmp')), false);
}));
