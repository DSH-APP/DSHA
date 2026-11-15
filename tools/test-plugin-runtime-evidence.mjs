import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import Module, { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { testRuntime } from './test-runtime-fixture.mjs';
import { installedPluginSampler } from '../app/src/main/assets/app-integration/runtime-plugins.js';

const runtime = testRuntime('raw');
// 与现有真实启动测试一致，避免锁定 native helper 在其他 Node ABI 上被加载。
if (!process.execArgv.includes('--expose-internals')) {
  const env = { ...process.env, DSHA_TEST_RUNTIME: runtime }; delete env.NODE_TEST_CONTEXT;
  // runner 的证明绑定直接子进程；新进程不借用它，testRuntime 会重新逐字节核验。
  delete env.DSHA_TEST_RUN_RECEIPT;
  delete env.DSHA_TEST_RUN_SECRET;
  const child = spawnSync(process.execPath, ['--expose-internals', fileURLToPath(import.meta.url)], {
    env, stdio: 'inherit', timeout: 120000, windowsHide: true,
  });
  if (child.error || child.signal) console.error(child.error || `子测试被 ${child.signal} 终止`);
  process.exit(child.status ?? 1);
}

const root = path.resolve(import.meta.dirname, '..');
const runtimeRequire = createRequire(path.join(runtime, 'package.json'));
const { Context } = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
const { default: Loader } = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis-plugin-loader')));
const { mountRootInclude, loadOverlayPatches } = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot')));
const base = path.join(root, 'app/build/plugin-runtime-evidence');
fs.mkdirSync(base, { recursive: true });
const fixture = fs.mkdtempSync(path.join(base, 'run-'));
const home = path.join(fixture, 'home'), profile = path.join(home, 'profiles/web');
const generation = 'evidence-generation', instance = 'evidence-instance';
const identity = { generation, instance, profile: 'web' };
const official = ['@deepseek-ai/dsh-experimental-agent-team-profile',
  '@deepseek-ai/dsh-experimental-auto-review', '@deepseek-ai/dsh-experimental-badge-skill-bundle'];
const local = [];
function put(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
}
function bundle(name, patch, sources = {}) {
  local.push(name);
  const dir = path.join(profile, 'node_modules', name);
  put(path.join(dir, 'package.json'), { name, version: '1.0.0', type: 'module', dsh: { bundle: { patch: 'cordis.patch.yml' } } });
  put(path.join(dir, 'cordis.patch.yml'), patch);
  for (const [file, source] of Object.entries(sources)) put(path.join(dir, file), source);
  return dir;
}
const good = bundle('fixture-good', '- insert:\n    - id: good\n      name: ./index.js\n', {
  'index.js': 'globalThis.evidenceImports++; export function apply() { globalThis.evidenceApplies++; }',
});
bundle('fixture-import-failure', '- insert:\n    - id: import-failure\n      name: ./index.js\n', {
  'index.js': 'globalThis.evidenceFailures++; throw new Error("private-import-detail");',
});
bundle('fixture-apply-failure', '- insert:\n    - id: apply-failure\n      name: ./index.js\n', {
  'index.js': 'export function apply() { throw new Error("private-apply-detail"); }',
});
bundle('fixture-pending', '- insert:\n    - id: pending\n      name: ./index.js\n', {
  'index.js': 'export const inject = ["evidenceService"]; export function apply() {}',
});
bundle('fixture-loading', '- insert:\n    - id: loading\n      name: ./index.js\n', {
  'index.js': 'export async function apply() { globalThis.evidenceLoadingStarted(); await globalThis.evidenceLoadingGate; }',
});
bundle('fixture-disabled', '- insert:\n    - id: disabled\n      name: ./never.js\n      disabled: true\n', {
  'never.js': 'throw new Error("disabled code must not execute");',
});
bundle('fixture-expression', '- insert:\n    - id: expression\n      name: ./never.js\n      disabled: !!js "(globalThis.evidenceExpressions++, true)"\n', {
  'never.js': 'throw new Error("expression-disabled code must not execute");',
});
bundle('fixture-multi', '- insert:\n    - id: multi-active\n      name: ./active.js\n    - id: multi-pending\n      name: ./pending.js\n', {
  'active.js': 'export function apply() {}', 'pending.js': 'export const inject = ["evidenceMissing"]; export function apply() {}',
});
bundle('fixture-group', '- insert:\n    - id: nested-group\n      name: cordis:group\n      group: true\n      config:\n        - id: nested-child\n          name: ./child.js\n', { 'child.js': 'export function apply() {}' });
// 组内可选子项用 !!js 条件禁用（对应真实 terminal-bundle 的 pwsh 行）：禁用的子项不进 init，
// 采样不能把它当 unknown 拖累整组，也不能为了判定状态重新求值表达式。
bundle('fixture-group-optional', '- insert:\n    - id: optional-group\n      name: cordis:group\n      group: true\n      config:\n        - id: optional-child\n          name: ./child.js\n        - id: optional-expression\n          name: ./never.js\n          disabled: !!js "(globalThis.evidenceExpressions++, true)"\n', {
  'child.js': 'export function apply() {}',
  'never.js': 'throw new Error("expression-disabled child must not execute");',
});
bundle('fixture-override-only', '- id: good\n  disabled: false\n');
bundle('fixture-ambiguous', '- insert:\n    - id: opaque\n      name: cordis:include\n      config: { path: ./private.yml }\n');
bundle('fixture-duplicate-a', '- insert:\n    - id: duplicate\n      name: ./index.js\n', { 'index.js': 'export function apply() {}' });
bundle('fixture-duplicate-b', '- insert:\n    - id: duplicate\n      name: ./index.js\n', { 'index.js': 'export function apply() {}' });
bundle('fixture-missing-entry', '- insert:\n    - id: never-mounted\n      name: ./index.js\n', { 'index.js': 'throw new Error("not mounted");' });
bundle('fixture-config-replaced-group', '- insert:\n    - id: replaced-group\n      name: cordis:group\n      group: true\n      config: []\n- id: replaced-group\n  config: []\n');
// 包装 bundle 自身没有运行逻辑，只把 peerDependencies 锁定的官方模块插进补丁。
function wrapper(name, peer, ownVersion, pin) {
  local.push(name);
  const dir = path.join(profile, 'node_modules', name);
  put(path.join(dir, 'package.json'), {
    name, version: ownVersion, type: 'module',
    dsh: { bundle: { patch: 'cordis.patch.yml' } },
    peerDependencies: { [peer]: pin },
  });
  put(path.join(dir, 'cordis.patch.yml'), `- insert:\n    - id: ${name}-row\n      name: '${peer}'\n`);
  return dir;
}
function peerPackage(name, version) {
  const dir = path.join(fixture, 'node_modules', name);
  put(path.join(dir, 'package.json'), { name, version, type: 'module', main: 'index.js' });
  put(path.join(dir, 'index.js'), 'export function apply() {}\n');
}
peerPackage('@fixture/peer-target', '2.5.0');
// 只有 Auto review 别名对共用可见卡片，运行证据报告锁定官方版本。
const aliasTarget = '@deepseek-ai/dsh-experimental-auto-review';
const aliasTargetVersion =
    JSON.parse(fs.readFileSync(path.join(runtime, 'node_modules', aliasTarget, 'package.json'), 'utf8')).version;
wrapper('dsh-auto-review', aliasTarget, '1.0.0', aliasTargetVersion);
// 其它包装在原生列表里仍以包装版本出现，不能借用被包装模块的版本。
wrapper('fixture-wrapper', '@fixture/peer-target', '1.0.0', '2.5.0');
put(path.join(profile, 'package.json'), { dsh: { profile: { bundles: [...official, ...local, 'fixture-unresolvable'] } } });
process.env.DSH_HOME = home;
process.env.DSHA_OBSERVER_INSTALL = path.join(runtime, 'node_modules/@deepseek-ai/dsh');
process.env.DSHA_STARTUP_PROFILE = 'web';
process.env.DSHA_WEB_GENERATION = generation;
process.env.DSH_TASK_INSTANCE = instance;
// 在真实文件与锁定 YAML 上统计观察器工作；只复用解析设置，不复用包/补丁内容。
const observerRequire = createRequire(import.meta.url);
const observerYaml = createRequire(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot'))('js-yaml');
const originalRequire = Module.createRequire, originalRead = fs.readFileSync;
const originalSchemaExtend = observerYaml.JSON_SCHEMA.extend;
const ownSchemaExtend = Object.hasOwn(observerYaml.JSON_SCHEMA, 'extend');
const observerAnchors = new Map(), observerReads = new Map();
let observerSchemaBuilds = 0;
Module.createRequire = function (anchor) {
  observerAnchors.set(String(anchor), (observerAnchors.get(String(anchor)) || 0) + 1);
  return originalRequire.apply(this, arguments);
};
fs.readFileSync = function (file) {
  observerReads.set(String(file), (observerReads.get(String(file)) || 0) + 1);
  return originalRead.apply(this, arguments);
};
observerYaml.JSON_SCHEMA.extend = function () {
  observerSchemaBuilds++;
  return originalSchemaExtend.apply(this, arguments);
};
try { observerRequire('../app/src/main/assets/startup-observer.cjs'); }
finally {
  Module.createRequire = originalRequire;
  fs.readFileSync = originalRead;
  if (ownSchemaExtend) observerYaml.JSON_SCHEMA.extend = originalSchemaExtend;
  else delete observerYaml.JSON_SCHEMA.extend;
}
const evidence = globalThis[Symbol.for('dsha.installed-plugin-evidence')];
const hash = value => createHash('sha256').update(value).digest('hex');

test('启动观察只创建一份固定解析设置，仍逐项读取当前插件内容', () => {
  assert.equal(observerSchemaBuilds, 1);
  assert.equal(observerAnchors.size, 3, 'installation, profile and YAML dependency anchors');
  for (const count of observerAnchors.values()) assert.equal(count, 1);
  for (const name of local) {
    const directory = path.join(profile, 'node_modules', name);
    assert.equal(observerReads.get(path.join(directory, 'package.json')), 1, name);
    assert.equal(observerReads.get(path.join(directory, 'cordis.patch.yml')), 1, name);
  }
});

// 使用真实官方补丁验证归属，不执行这些官方业务插件。
test('官方 bundle 只拥有插入入口，禁用基础入口不是自身加载条件', () => {
  const expected = [['agent-team', 'tool-agent-team', 'ui-agent-team'], ['auto-review'], ['skill-badge']];
  official.forEach((name, i) => {
    const item = evidence.bundles.find(row => row.name === name);
    assert.deepEqual(item.entries.map(entry => entry.id), expected[i]);
    assert.equal(item.ambiguous, false);
    const patch = path.join(runtime, 'node_modules', name, 'cordis.patch.yml');
    const inserted = loadOverlayPatches('fixture', patch).flatMap(row => row.insert || []);
    assert.deepEqual(item.entries.map(entry => entry.moduleHash), inserted.map(entry => hash(entry.name)));
    assert.equal(item.version, JSON.parse(fs.readFileSync(path.join(runtime, 'node_modules', name, 'package.json'))).version);
  });
  assert.equal(evidence.bundles.find(row => row.name === 'fixture-override-only').entries.length, 0);
  // Auto review 别名对共用一张可见卡片：运行证据必须报告补丁实际插入的锁定官方版本。
  assert.equal(
      evidence.bundles.find(row => row.name === 'dsh-auto-review').version,
      aliasTargetVersion);
  // 其它包装不是同一可见卡片，仍报告包装自己的版本。
  assert.equal(evidence.bundles.find(row => row.name === 'fixture-wrapper').version, '1.0.0');
  assert.equal(evidence.bundles.find(row => row.name === 'fixture-ambiguous').ambiguous, true);
  assert.equal(evidence.bundles.find(row => row.name === 'fixture-config-replaced-group').ambiguous, true);
  assert(Object.isFrozen(evidence) && Object.isFrozen(evidence.bundles[0].entries[0]));
  const metadata = JSON.stringify(evidence);
  assert(!metadata.includes(fixture) && !metadata.includes(runtime));
  assert(!metadata.includes('private-') && !metadata.includes('__jsExpr'));
});

test('锁定 Loader 的实际 fiber、吞掉的导入失败与状态变化驱动心跳', async () => {
  globalThis.evidenceImports = globalThis.evidenceApplies = globalThis.evidenceFailures = globalThis.evidenceExpressions = 0;
  let release;
  globalThis.evidenceLoadingGate = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { globalThis.evidenceLoadingStarted = resolve; });
  const ctx = new Context();
  ctx.baseUrl = pathToFileURL(fixture + path.sep).href;
  const config = path.join(fixture, 'root.yml');
  put(config, '[]\n');
  await ctx.plugin(Loader);
  const selected = local.filter(name => !['fixture-ambiguous', 'fixture-missing-entry', 'fixture-duplicate-a',
    'fixture-config-replaced-group', 'dsh-auto-review'].includes(name));
  const patches = selected.flatMap(name => loadOverlayPatches('fixture', path.join(profile, 'node_modules', name, 'cordis.patch.yml')));
  const sample = installedPluginSampler(ctx, identity);
  const state = name => sample().find(item => item.name === name)?.state;
  try {
    await mountRootInclude(ctx, config, patches);
    await started;
    assert.equal(state('fixture-loading'), 'loading');
    release();
    await ctx.get('loader').await();
    assert.equal(state('fixture-good'), 'loaded');
    assert.equal(state('fixture-import-failure'), 'failed', 'Entry.init resolves after swallowing the actual import error');
    assert.equal(state('fixture-apply-failure'), 'failed');
    assert.equal(state('fixture-pending'), 'pending');
    assert.equal(state('fixture-loading'), 'loaded');
    assert.equal(state('fixture-disabled'), 'unloaded');
    assert.equal(state('fixture-expression'), 'unknown', '采样不重新求值 !!js');
    assert.equal(state('fixture-multi'), 'pending', '同 bundle 的一个 active 不能覆盖另一个 pending');
    assert.equal(state('fixture-group'), 'loaded');
    assert.equal(state('fixture-group-optional'), 'loaded', '条件禁用的可选子项不能拖累整组');
    for (const name of ['fixture-unresolvable', 'fixture-missing-entry', 'fixture-ambiguous',
      'fixture-override-only', 'fixture-duplicate-a', 'fixture-duplicate-b']) assert.equal(state(name), 'unknown', name);
    assert.equal(sample().length, official.length + local.length + 1);
    const expressions = globalThis.evidenceExpressions;
    const read = fs.readFileSync;
    fs.readFileSync = () => { throw new Error('心跳不能扫描磁盘'); };
    try { for (let i = 0; i < 5; i++) assert.equal(state('fixture-good'), 'loaded'); }
    finally { fs.readFileSync = read; }
    assert.equal(globalThis.evidenceImports, 1);
    assert.equal(globalThis.evidenceApplies, 1);
    assert.equal(globalThis.evidenceFailures, 1, '失败模块不可为采样而重新导入');
    assert.equal(globalThis.evidenceExpressions, expressions);
    const json = JSON.stringify(sample());
    assert(!json.includes(fixture) && !json.includes(runtime));
    assert(!json.includes('private-') && !json.includes('moduleHash'));
    assert.deepEqual(Object.keys(sample()[0]), ['name', 'version', 'profile', 'state']);

    put(path.join(good, 'package.json'), { name: 'fixture-good', version: '2.0.0' });
    assert.equal(sample().find(item => item.name === 'fixture-good').version, '1.0.0', '磁盘升级不能冒领旧 fiber');
    ctx.provide('evidenceService', {});
    await ctx.get('loader').await();
    assert.equal(state('fixture-pending'), 'loaded');
    const loader = ctx.get('loader');
    const group = [...loader.entries()].find(entry => entry.options.id === 'nested-group');
    const late = path.join(fixture, 'late.mjs');
    put(late, 'export function apply() {}');
    await loader.create({ id: 'late-child', name: pathToFileURL(late).href }, group.id);
    await loader.await();
    assert.equal(state('fixture-group'), 'unknown', 'group 新增未声明入口后不能只凭旧成员继续认领 loaded');
    const failed = [...loader.entries()].find(entry => entry.options.id === 'import-failure');
    await loader.update(failed.id, { disabled: true });
    assert.equal(state('fixture-import-failure'), 'unloaded');
    const active = [...loader.entries()].find(entry => entry.options.id === 'good');
    await loader.update(active.id, { disabled: true });
    await loader.await();
    assert.equal(state('fixture-good'), 'unloaded');
    active.parent.remove(active.options.id);
    assert(![...loader.entries()].includes(active));
    assert.equal(state('fixture-good'), 'unknown', '缺席不能证明未加载');
    for (const wrong of [{ ...identity, instance: 'other' }, { ...identity, generation: 'other' }, { ...identity, profile: 'other' }])
      assert(installedPluginSampler(ctx, wrong)().every(item => item.state === 'unknown'));
    assert(installedPluginSampler({ get() { throw new Error('unavailable'); } }, identity)().every(item => item.state === 'unknown'));
  } finally {
    release();
    await ctx.fiber.dispose();
  }
  assert(sample().every(item => item.state === 'unknown'), '丢失当前 Loader 后不能缓存 loaded');
});

test('官方多入口、重复 id、模块冲突与未知新 fiber 状态保守降级', () => {
  const loader = {}, include = { options: { id: 'include', name: 'cordis:include' }, parent: { tree: loader } };
  loader.root = include.parent;
  const tree = include.subtree = {};
  const rows = [include];
  const declaredNames = [
    ['@deepseek-ai/dsh-experimental-agent-team', '@deepseek-ai/dsh-experimental-tool-agent-team', '@deepseek-ai/dsh-experimental-client-ui-agent-team'],
    ['@deepseek-ai/dsh-experimental-auto-review'], ['@deepseek-ai/dsh-skill-badge'],
  ];
  official.forEach((name, index) => {
    evidence.bundles.find(item => item.name === name).entries.forEach((entry, position) => rows.push({
      options: { id: entry.id, name: declaredNames[index][position] }, fiber: { state: 2, uid: position + 1 },
      parent: { tree, ctx: { fiber: { entry: include } } },
    }));
  });
  loader.entries = () => rows.values();
  const sample = installedPluginSampler({ get: name => name === 'loader' ? loader : undefined }, identity);
  const states = () => sample().filter(row => official.includes(row.name)).map(row => row.state);
  assert.deepEqual(states(), ['loaded', 'loaded', 'loaded']);
  rows[2].fiber.state = 0;
  assert.deepEqual(states(), ['pending', 'loaded', 'loaded']);
  rows[2].fiber.state = 3;
  assert.equal(states()[0], 'failed');
  rows[2].fiber.state = 77;
  assert.equal(states()[0], 'unknown');
  rows[2].fiber.state = 2;
  rows[2].options.name = 'wrong-module';
  assert.equal(states()[0], 'unknown');
  rows[2].options.name = declaredNames[0][1];
  rows.push({ ...rows[2] });
  assert.equal(states()[0], 'unknown', '重复实际 id 无法确认归属');
  rows.pop();
  rows[2].parent = { tree: {}, ctx: { fiber: { entry: include } } };
  assert.equal(states()[0], 'unknown', '其他 include 子树不能冒充根 profile 入口');
});
