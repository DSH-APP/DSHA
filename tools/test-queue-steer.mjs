import {testRuntime} from './test-runtime-fixture.mjs';
// Verify the rc2 queue arrow fix against the locked DSH client and session host.
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const runtime = process.env.DSHA_QUEUE_RUNTIME ? testRuntime('raw', 'DSHA_QUEUE_RUNTIME') : testRuntime('raw');
const assets = name => JSON.parse(readFileSync(path.join(root, 'app/src/main/assets', name), 'utf8'));
const apply = (source, recipe) => recipe.patches.reduce((value, patch) => {
  assert.equal(value.split(patch.before).length - 1, 1, `queue patch anchor mismatch: ${patch.before.slice(0, 80)}`);
  return value.replace(patch.before, patch.after);
}, source);

const dock = apply(readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js'), 'utf8'), assets('queue-dock-patch.json'));
assert.match(dock, /label: t\("queue\.steer"\)[\s\S]{0,300}disabled: false/);
assert.match(dock, /title: void 0/);
assert.match(dock, /disabled: busy !== null/);

const agent = apply(readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js'), 'utf8'), assets('queue-agent-patch.json'));
assert.match(agent, /only next-turn queue items can be sent/);
assert.match(agent, /if \(agent\.status === "running"\) agent\.steer\(message\);[\s\S]{0,140}else agent\.followup\(message\);/);
console.log('queue-steer patch anchors and idle follow-up behavior verified');
