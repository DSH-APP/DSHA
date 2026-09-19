// 验证 alpha.2 受管别名能让仍引用 0.1.5 包名的社区预设通过真实健康扫描。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const root = path.resolve(import.meta.dirname, '..');
const runtime = path.resolve(process.env.DSHA_TEST_RUNTIME || path.join(root, 'app/build/locked-dsh-runtime-138'));
const modules = path.join(runtime, 'node_modules');
const { scanRoot } = await import(pathToFileURL(path.join(modules, '@deepseek-ai/dsh-agent-presets/lib/index.js')));
const build = path.join(root, 'app/build/workflow-compat-alias');
fs.mkdirSync(build, { recursive: true });
const fixture = fs.mkdtempSync(path.join(build, 'run-'));
const harness = path.join(fixture, 'usr/local/lib/node_modules/@deepseek-ai/dsh');
const scope = path.join(harness, 'node_modules/@deepseek-ai');
const current = path.join(scope, 'dsh-workflow-ptc');
const legacy = path.join(scope, 'dsh-workflow-worker-thread');
const presets = path.join(fixture, 'presets');
fs.mkdirSync(path.join(harness, 'lib'), { recursive: true });
fs.mkdirSync(current, { recursive: true });
fs.copyFileSync(path.join(modules, '@deepseek-ai/dsh-workflow-ptc/package.json'), path.join(current, 'package.json'));
fs.symlinkSync(current, legacy, process.platform === 'win32' ? 'junction' : 'dir');
fs.mkdirSync(path.join(presets, 'liangshen'), { recursive: true });
fs.writeFileSync(path.join(presets, 'liangshen/agent.cordis.yml'), [
  '- id: workflow-worker-thread',
  "  name: '@deepseek-ai/dsh-workflow-worker-thread'",
  '  config:',
  '    provider: spawn',
  ''
].join('\n'));

const fromHarness = createRequire(path.join(harness, 'package.json'));
assert.equal(fs.realpathSync(path.dirname(fromHarness.resolve('@deepseek-ai/dsh-workflow-worker-thread/package.json'))), fs.realpathSync(current));
const rows = await scanRoot({ path: presets, trust: 'user' }, pathToFileURL(path.join(harness, 'lib/index.js')).href);
assert.equal(rows.length, 1);
assert.equal(rows[0].id, 'liangshen');
assert.equal(rows[0].broken, undefined, rows[0].broken);
console.log('PASS: legacy workflow package alias resolves and the Liangshen preset remains selectable on dsh 0.1.6-alpha.2.');
