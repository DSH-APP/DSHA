// dsh-fs-search-scope 回归：真实 0.2.2 managed 运行时（含 DSHA 必经策略补丁）的 ToolsService + 原生执行器 +
// dsh-subprocess-local + dsh-tool-fs-search 组合。夹具是系统临时目录下的 git 仓库与链接工作树：
// 放在 app/build 下会落进本仓库自己的 .agents/worktrees，插件按设计不剪枝，测不到。
// 「上游契约」用例失败即说明上游 glob 形状变了，按 docs/fs-search-scope-plugin.md 评估撤除插件。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const managed = testRuntime('managed'), modules = path.join(managed, 'node_modules');
const load = name => import(pathToFileURL(path.join(modules, name)));
const { Context } = await load('@deepseek-ai/cordis/lib/index.js');
const { ToolsService } = await load('@deepseek-ai/dsh-tools/lib/index.js');
const native = await load('@deepseek-ai/dsh-tool-executor-native/lib/index.js');
const { LocalSubprocessRuntime } = await load('@deepseek-ai/dsh-subprocess-local/lib/index.js');
const search = await load('@deepseek-ai/dsh-tool-fs-search/lib/index.js');

const directory = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-fs-search-scope-')));
// 按生产布局安装插件副本：插件目录内的 node_modules 指向运行时，裸导入与策略模块都从这里解析。
const pluginDir = path.join(directory, 'plugin');
fs.cpSync(path.join(root, 'app/src/main/assets/builtin-plugins/dsh-fs-search-scope'), pluginDir, { recursive: true });
fs.symlinkSync(modules, path.join(pluginDir, 'node_modules'));
const scope = await import(pathToFileURL(path.join(pluginDir, 'lib/index.js')));

const repo = path.join(directory, 'repo');
const git = (cwd, ...args) => {
  const run = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', '-c', 'commit.gpgsign=false', '-c', 'init.defaultBranch=main', ...args], { cwd, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
};
const write = (file, text = 'x\n') => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, text); };
write(path.join(repo, 'src/a.java'));
write(path.join(repo, '.hidden/h.java'));
write(path.join(repo, '.gitignore'), 'build/\n');
git(repo, 'init', '-q');
git(repo, 'add', '-A');
git(repo, 'commit', '-qm', 'init');
write(path.join(repo, 'build/ignored.java')); // 被 .gitignore 忽略：glob 仍须列出（--no-ignore 语义）
git(repo, 'worktree', 'add', '-q', '.agents/worktrees/fix/a', '-b', 'fix/a');
git(repo, 'worktree', 'add', '-q', 'nested/wt', '-b', 'nested');
write(path.join(repo, '.agents/worktrees/stale/s.java')); // 不在 git 登记里的残留目录：靠固定规则剪掉
write(path.join(directory, 'other/.keep'));
const OWN = ['.hidden/h.java', 'build/ignored.java', 'src/a.java'];

const config = { sampleOverCapGlobResults: false, globMaxResults: 100, grepMaxMatches: 250, grepMaxLineBytes: 2000, searchMetaMaxBytes: 65536, rawOutputMaxBytes: 2e7, graceMs: 3000, stderrMaxBytes: 65536, timeoutMs: 30000 };
const prompt = { sections: [], tools() {}, context() {}, getContextOrder() { return 10; }, getSectionOrder() { return 10; }, section(row) { this.sections.push(row); } };
let calls = 0, cases = 0;

async function harness({ plugin = true, workdir = repo } = {}) {
  const ctx = new Context();
  prompt.sections.length = 0;
  ctx.provide('systemPrompt', prompt);
  ctx.provide('workingDirectory', { async ensure() { return workdir; }, get() { return workdir; } });
  new ToolsService(ctx);
  ctx.plugin(native);
  await new Promise(resolve => setTimeout(resolve, 0));
  new LocalSubprocessRuntime(ctx);
  await search.apply(ctx, config);
  if (plugin) await scope.apply(ctx);
  const session = { header: { cwd: workdir }, seq: 1, eventAt() { return { type: 'turn/start', data: {} }; }, append() {} };
  const glob = args => ctx.tools.execute({ calls: [{ type: 'tool-call', id: 'scope-' + calls++, name: 'glob', arguments: JSON.stringify(args) }], agent: { ctx, session }, signal: new AbortController().signal }).then(batch => batch.results[0].result);
  return { ctx, glob };
}
const text = result => result.content?.[0]?.text ?? '';
const lines = result => text(result) === 'No files found' ? [] : text(result).split('\n\n')[0].split('\n').sort();
async function run(name, body) {
  await body();
  cases++;
  console.log('ok -', name);
}

try {
  await run('上游契约：未装插件时从仓库根 glob 会展开嵌套工作树', async () => {
    const h = await harness({ plugin: false });
    const result = await h.glob({ pattern: '*.java' });
    assert.notEqual(result.isError, true, text(result));
    assert.deepEqual(lines(result), [...OWN, '.agents/worktrees/fix/a/.hidden/h.java', '.agents/worktrees/fix/a/src/a.java', '.agents/worktrees/stale/s.java', 'nested/wt/.hidden/h.java', 'nested/wt/src/a.java'].sort());
    assert.deepEqual(result.value, { root: '.', paths: result.value.paths });
    assert(search.buildGlobCommand({ pattern: '*' }).includes('--no-ignore'));
    await h.ctx.fiber.dispose();
  });

  await run('仓库根：剪掉 .agents/worktrees 与链接工作树，保留忽略文件与隐藏文件', async () => {
    const h = await harness();
    const result = await h.glob({ pattern: '*.java' });
    assert.notEqual(result.isError, true, text(result));
    assert.deepEqual(lines(result), OWN);
    assert.equal(result.value.root, '.');
    assert.equal(result.meta?.shape, 'paths');
    assert.equal(prompt.sections.find(row => row.name === 'dsha:glob-scope')?.text({}), scope.PROMPT_NOTE);
    await h.ctx.fiber.dispose();
  });

  await run('path 指向工作树内或其上层：与上游逐字一致', async () => {
    for (const where of ['.agents/worktrees/fix/a', '.agents/worktrees', 'nested/wt', path.join(repo, 'nested/wt')]) {
      const off = await harness({ plugin: false }), on = await harness();
      const expected = await off.glob({ pattern: '*.java', path: where }), actual = await on.glob({ pattern: '*.java', path: where });
      assert.notEqual(actual.isError, true, text(actual));
      assert.deepEqual(lines(actual), lines(expected), where);
      assert(lines(actual).length >= 2, where);
      await off.ctx.fiber.dispose(); await on.ctx.fiber.dispose();
    }
  });

  await run('path 为包含链接工作树的子目录：链接工作树被剪掉', async () => {
    const h = await harness();
    assert.equal(text(await h.glob({ pattern: '*.java', path: 'nested' })), 'No files found');
    await h.ctx.fiber.dispose();
  });

  await run('检索根在工作目录之外（../ 与绝对路径）：同样剪枝', async () => {
    // 软链接检索根不测：本机 proroot 下 rg 以软链接为根本身就报 ENOENT（不装插件亦然），与插件无关。
    const h = await harness({ workdir: path.join(directory, 'other') });
    assert.deepEqual(lines(await h.glob({ pattern: '*.java', path: '../repo' })), OWN.map(file => '../repo/' + file).sort());
    assert.deepEqual(lines(await h.glob({ pattern: '*.java', path: repo })), OWN.map(file => path.join(repo, file)).sort());
    await h.ctx.fiber.dispose();
  });

  await run('超出上限：上游渲染与溢出提示照常生效', async () => {
    for (let i = 0; i < 105; i++) write(path.join(repo, 'many', `m${i}.java`));
    try {
      const h = await harness();
      const result = await h.glob({ pattern: '*.java' });
      assert.match(text(result), /\(Showing 100 of 108 paths\. The complete result could not be saved/);
      assert.equal(result.value.paths.length, 108);
      await h.ctx.fiber.dispose();
    } finally {
      fs.rmSync(path.join(repo, 'many'), { recursive: true, force: true });
    }
  });

  await run('非法 pattern 与非目录 path：报错与上游一致', async () => {
    for (const args of [{ pattern: '[' }, { pattern: '*.java', path: 'src/a.java' }, { pattern: '*.java', path: 'missing' }, { pattern: ' ' }]) {
      const off = await harness({ plugin: false }), on = await harness();
      const expected = await off.glob(args), actual = await on.glob(args);
      assert.equal(actual.isError, expected.isError, JSON.stringify(args));
      assert.equal(text(actual), text(expected), JSON.stringify(args));
      assert.deepEqual(actual.error?.info, expected.error?.info, JSON.stringify(args));
      await off.ctx.fiber.dispose(); await on.ctx.fiber.dispose();
    }
  });

  await run('接管前先过必经策略：同意 → 定义 → 派发，失败即报错且不执行', async () => {
    const order = [], tool = { name: 'glob', execute() {}, description: scope.UPSTREAM_GLOB_DESCRIPTION + '.', output: { schema: { properties: { root: {}, paths: {} } } } };
    const services = { tools: { resolveExecution: () => { order.push('resolve'); return tool; } }, workingDirectory: { ensure: async () => repo }, subprocess: {} };
    const ctx = { get: key => services[key] };
    const exec = { name: 'glob', arguments: { pattern: '*.java' }, agent: {}, signal: new AbortController().signal };
    const listLinked = async () => [];
    const blocked = await scope.createHandler(ctx, { search: { ...search, runRipgrep: () => assert.fail('must not run') }, listLinked, policy: {
      verifyGlobalConsent: async () => { order.push('consent'); },
      verifyDispatch: (runtime, e, t) => { order.push('dispatch'); assert.equal(runtime, services.tools); assert.equal(e, exec); assert.equal(t, tool); throw Object.assign(Error('DSHA_DESTRUCTIVE_BLOCKED: x'), { code: 'X' }); },
    } })(exec, () => assert.fail('must not fall back'));
    assert.deepEqual(order, ['consent', 'resolve', 'dispatch']);
    assert.equal(blocked.isError, true);
    assert.equal(text(blocked), 'Error: DSHA_DESTRUCTIVE_BLOCKED: x');
    let fellBack = 0;
    const other = await scope.createHandler(ctx, { search, listLinked, policy: { verifyGlobalConsent: async () => {}, verifyDispatch: () => assert.fail('must not dispatch') } });
    services.tools.resolveExecution = () => ({ ...tool, description: 'someone else' });
    await other(exec, async () => { fellBack++; return {}; });
    await other({ ...exec, parent: {} }, async () => { fellBack++; return {}; });
    await other({ ...exec, name: 'grep' }, async () => { fellBack++; return {}; });
    assert.equal(fellBack, 3);
  });

  await run('纯函数：区域判定、转义、解析、插入位置和新旧工具解析', async () => {
    const modernTool = { name: 'glob', execute() {} };
    const modernExec = { name: 'glob', agent: {}, toolSet: { modern: true } };
    assert.equal(
      scope.resolveTool(
        { executable: (name, agent, toolSet) => {
          assert.equal(name, 'glob');
          assert.equal(agent, modernExec.agent);
          assert.equal(toolSet, modernExec.toolSet);
          return modernTool;
        } },
        modernExec),
      modernTool);
    assert.equal(scope.resolveTool({ resolveExecution: () => modernTool }, modernExec), modernTool);
    assert(scope.inWorktreeZone('/r/.agents/worktrees') && scope.inWorktreeZone('/r/.agents/worktrees/a/b'));
    assert(!scope.inWorktreeZone('/r/.agents') && !scope.inWorktreeZone('/r/agents/worktrees'));
    assert.equal(scope.escapeGlob('/a [x]{y}*?!\\'), '/a \\[x\\]\\{y\\}\\*\\?\\!\\\\');
    assert.deepEqual(scope.parseWorktreeList('worktree /m\0HEAD 1\0branch refs/heads/main\0\0worktree /m/w\0HEAD 2\0detached\0\0'), ['/m', '/m/w']);
    assert.deepEqual(scope.withExcludes(['--files', '--', 'p'], ['!a']), ['--files', '--glob=!a', '--', 'p']);
    assert.deepEqual(scope.withExcludes(['--files'], ['!a']), ['--files', '--glob=!a']);
    assert.deepEqual(scope.pruneGlobs({ workdir: '/w', path: '.agents/worktrees/x' }), []);
    assert.deepEqual(scope.pruneGlobs({ workdir: '/w', linked: ['/w/n', '/elsewhere', '/w/.agents/worktrees/a'] }), ['!**/.agents/worktrees', '!n', '!**/w/n']);
  });

  console.log(JSON.stringify({ status: 'PASS', cases, realDsh022: true, runtime: managed }));
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
