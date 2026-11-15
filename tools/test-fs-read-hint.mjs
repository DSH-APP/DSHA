// dsh-fs-read-hint 回归：真实 0.2.2 的 ToolsService + 原生执行器 + dsh-fs-local + fs-observation-policy + dsh-tool-fs
// + str_replace_editor 组合，只在 app/build 下的临时目录读写。
// 「上游契约」用例失败即说明上游报错形状变了，按 docs/fs-read-hint-plugin.md 评估撤除插件。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';
import * as hint from '../app/src/main/assets/builtin-plugins/dsh-fs-read-hint/lib/index.js';

const root = path.resolve(import.meta.dirname, '..');
const raw = testRuntime('raw'), modules = path.join(raw, 'node_modules');
const load = name => import(pathToFileURL(path.join(modules, name)));
const { Context } = await load('@deepseek-ai/cordis/lib/index.js');
const { ToolsService } = await load('@deepseek-ai/dsh-tools/lib/index.js');
const native = await load('@deepseek-ai/dsh-tool-executor-native/lib/index.js');
const { LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local/lib/index.js');
const policy = await load('@deepseek-ai/dsh-fs-observation-policy/lib/index.js');
const fsTools = await load('@deepseek-ai/dsh-tool-fs/lib/index.js');
const editor = await load('@deepseek-ai/dsh-tool-str-replace-editor/lib/index.js');

fs.mkdirSync(path.join(root, 'app/build'), { recursive: true });
const directory = fs.mkdtempSync(path.join(root, 'app/build/fs-read-hint-'));
const main = path.join(directory, 'repo/app/src/X.java');
const tree = path.join(directory, 'repo/.agents/worktrees/fix/a/app/src/X.java');
const other = path.join(directory, 'elsewhere/X.java');
const third = path.join(directory, 'third/X.java');
let calls = 0, cases = 0;

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
async function harness({ plugin = true } = {}) {
  const ctx = new Context();
  ctx.provide('systemPrompt', { tools() {}, context() {}, getContextOrder() { return 10; }, getSectionOrder() { return 10; }, section() {} });
  // 工作目录服务只需给出临时目录；真实服务的会话 cwd 管理与本插件无关。
  ctx.provide('workingDirectory', { get: () => directory, async ensure() { return directory; } });
  // 0.2.2：执行在 ToolExecutionPipeline，由原生执行器插件挂到 tools 上。
  new ToolsService(ctx);
  ctx.plugin(native);
  new LocalFileSystem(ctx, { cwd: directory, diffBasisMaxBytes: 1048576 });
  policy.apply(ctx);
  fsTools.apply(ctx, { read: true, write: true, edit: true, readImage: false, readLimit: 2000, readMaxLineLength: 2000, readMaxBytes: 1048576, readStreamMinSize: 1048576 });
  editor.apply(ctx, {});
  if (plugin) hint.apply(ctx);
  const session = () => ({ header: { cwd: directory }, seq: 1, eventAt() { return { type: 'turn/start', data: {} }; }, append() {} });
  const own = session();
  const call = (name, args, s = own) => ctx.tools.execute({ calls: [{ type: 'tool-call', id: 'hint-' + calls++, name, arguments: JSON.stringify(args) }], agent: { ctx, session: s }, signal: new AbortController().signal }).then(batch => batch.results[0].result);
  await settle();
  return { ctx, call, session };
}
function reset() {
  for (const file of [main, tree, other, third]) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, 'hello\n'); }
}
const text = result => result.content?.[0]?.text ?? '';
async function run(name, body) {
  reset();
  await body();
  cases++;
  console.log('ok -', name);
}

try {
  await run('上游契约：未装插件时报错文字与错误码为已知形状', async () => {
    const h = await harness({ plugin: false });
    await h.call('read', { file_path: main });
    const edited = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    assert.equal(edited.isError, true);
    assert.equal(edited.error.info?.code, 'FS_NOT_OBSERVED');
    assert.equal(text(edited), `Error: cannot modify "${tree}": file has not been read — read the file, then retry`);
    assert.equal(hint.quotedPath(edited.error.message), tree);
    const written = await h.call('write', { file_path: tree, content: 'x' });
    assert.equal(written.error.info?.code, 'FS_NOT_OBSERVED');
    assert.equal(hint.quotedPath(written.error.message), tree);
    const replaced = await h.call('str_replace_editor', { command: 'str_replace', path: tree, old_str: 'hello', new_str: 'world' });
    assert.equal(replaced.error.info?.code, 'FS_NOT_OBSERVED');
    assert.equal(hint.quotedPath(replaced.error.message), tree);
    await h.ctx.fiber.dispose();
  });

  await run('读主检出、改 worktree：edit 报错附上已读的同名路径，门禁与文件不变', async () => {
    const h = await harness();
    await h.call('read', { file_path: main });
    const result = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    assert.equal(result.isError, true);
    assert.equal(result.error.info?.code, 'FS_NOT_OBSERVED');
    assert.equal(text(result), `Error: ${result.error.message}`);
    assert(result.error.message.startsWith(`cannot modify "${tree}": file has not been read — read the file, then retry`));
    assert(result.error.message.includes(`this session read "${main}"`), result.error.message);
    assert.equal(fs.readFileSync(tree, 'utf8'), 'hello\n');
    await h.ctx.fiber.dispose();
  });

  await run('write 覆盖与 str_replace_editor 同样附提示', async () => {
    const h = await harness();
    await h.call('read', { file_path: main });
    for (const [name, args] of [['write', { file_path: tree, content: 'x' }], ['str_replace_editor', { command: 'str_replace', path: tree, old_str: 'hello', new_str: 'world' }]]) {
      const result = await h.call(name, args);
      assert.equal(result.error.info?.code, 'FS_NOT_OBSERVED', name);
      assert(result.error.message.includes(`"${main}"`), name + ': ' + result.error.message);
      assert.equal(fs.readFileSync(tree, 'utf8'), 'hello\n', name);
    }
    await h.ctx.fiber.dispose();
  });

  await run('读对路径后照常放行：插件不影响正常修改', async () => {
    const h = await harness();
    await h.call('read', { file_path: main });
    await h.call('read', { file_path: tree, offset: 1, limit: 1 });
    const result = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    assert.notEqual(result.isError, true, text(result));
    assert.equal(fs.readFileSync(tree, 'utf8'), 'world\n');
    await h.ctx.fiber.dispose();
  });

  await run('没有同名已读文件：报错与上游完全一致', async () => {
    const h = await harness();
    const result = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    assert.equal(text(result), `Error: cannot modify "${tree}": file has not been read — read the file, then retry`);
    await h.ctx.fiber.dispose();
  });

  await run('候选按公共后缀排序；会话之间互不可见', async () => {
    const h = await harness();
    await h.call('read', { file_path: other });
    await h.call('read', { file_path: main });
    const stranger = h.session();
    const strangerRead = await h.call('read', { file_path: third }, stranger);
    assert.notEqual(strangerRead.isError, true, text(strangerRead));
    const result = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    const message = result.error.message;
    assert(message.includes(`"${main}"`) && message.includes(`"${other}"`), message);
    assert(message.indexOf(`"${main}"`) < message.indexOf(`"${other}"`), message);
    assert(!message.includes(`"${third}"`), message);
    const isolated = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' }, stranger);
    assert(isolated.error.message.includes(`"${third}"`), isolated.error.message);
    assert(!isolated.error.message.includes(`"${main}"`), isolated.error.message);
    await h.ctx.fiber.dispose();
  });

  await run('文件读后被改（FS_STALE_VERSION）不改写', async () => {
    const h = await harness();
    await h.call('read', { file_path: main });
    await h.call('read', { file_path: tree });
    fs.writeFileSync(tree, 'hello changed\n');
    const result = await h.call('edit', { file_path: tree, old_string: 'hello', new_string: 'world' });
    assert.equal(result.error.info?.code, 'FS_STALE_VERSION');
    assert(!result.error.message.includes(hint.HINT_PREFIX));
    await h.ctx.fiber.dispose();
  });

  await run('纯函数：形状不符原样返回，不重复追加', async () => {
    const observed = new Map([[main, true]]);
    const exec = { arguments: { file_path: tree } };
    const message = `cannot modify "${tree}": file has not been read — read the file, then retry`;
    const shaped = { isError: true, error: { message, info: { name: 'FsError', code: 'FS_NOT_OBSERVED' } }, content: [{ type: 'text', text: `Error: ${message}` }] };
    const once = hint.rewrite(shaped, exec, observed);
    assert.notEqual(once, shaped);
    assert.equal(hint.rewrite(once, exec, observed), once);
    for (const changed of [
      { ...shaped, error: { ...shaped.error, info: { code: 'FS_STALE_VERSION' } } },
      { ...shaped, content: [{ type: 'text', text: 'Error: other' }] },
      { ...shaped, content: [...shaped.content, { type: 'text', text: 'extra' }] },
      { ...shaped, isError: false },
    ]) assert.equal(hint.rewrite(changed, exec, observed), changed);
    assert.equal(hint.rewrite(shaped, exec, undefined), shaped);
    assert.deepEqual(hint.candidates([tree], tree), []);
    const many = new Map(Array.from({ length: hint.MAX_OBSERVED + 10 }, (_, i) => [`/p${i}/X.java`, true]));
    assert.equal(hint.candidates(many.keys(), tree).length, hint.MAX_HINTS);
  });

  await run('每会话记录有上限，超出淘汰最早', async () => {
    const handlers = new Map();
    hint.apply({ effect() {}, on: (type, fn) => handlers.set(type, fn) });
    const session = {}, actor = { agent: { session } };
    for (let i = 0; i < hint.MAX_OBSERVED + 5; i++) handlers.get('fs/observed')({ displayPath: `/p${i}/X.java` }, { kind: 'present' }, actor);
    handlers.get('fs/observed')({ displayPath: '/gone/X.java' }, { kind: 'absent' }, actor);
    const message = `cannot modify "/t/X.java": file has not been read — read the file, then retry`;
    const result = await handlers.get('tools/execute')({ name: 'edit', arguments: { file_path: '/t/X.java' }, agent: { session } }, async () => ({ isError: true, error: { message, info: { code: 'FS_NOT_OBSERVED' } }, content: [{ type: 'text', text: `Error: ${message}` }] }));
    assert(!result.error.message.includes('"/p0/X.java"'));
    assert(!result.error.message.includes('/gone/'));
    assert(result.error.message.includes(`"/p${hint.MAX_OBSERVED + 4}/X.java"`));
    const passthrough = { ok: true };
    assert.equal(await handlers.get('tools/execute')({ name: 'bash' }, async () => passthrough), passthrough);
  });

  console.log(JSON.stringify({ status: 'PASS', cases, realDsh022: true, runtime: raw }));
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
