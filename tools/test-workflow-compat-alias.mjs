import { testRuntime } from './test-runtime-fixture.mjs';
// 0.1.7 renamed several preset packages. The durable compatibility promise
// that still applies is the old workflow-worker-thread package name.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const modules = path.join(runtime, 'node_modules');
const current = path.join(modules, '@deepseek-ai/dsh-workflow-ptc', 'package.json');
assert.ok(fs.existsSync(current), '0.1.7 workflow package is present');
const builder = fs.readFileSync(path.join(root, 'tools/build-dsh-runtime.py'), 'utf8');
assert.match(builder, /dsh-workflow-worker-thread/);
assert.match(builder, /nested_alias/);
assert.match(builder, /global_alias/);
const preset = path.join(modules, '@deepseek-ai/dsh-agent-preset', 'package.json');
assert.ok(fs.existsSync(preset), '0.1.7 uses the singular dsh-agent-preset package');
console.log('PASS: legacy workflow alias is generated and the 0.1.7 preset package is present.');
