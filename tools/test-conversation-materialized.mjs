import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const runtime = path.resolve(process.env.DSHA_TEST_RUNTIME || path.join(root, 'app/build/locked-dsh-runtime-rc1'));
const archive = path.resolve(process.env.DSHA_RUNTIME_ARCHIVE || path.join(root, 'app/src/main/assets/dsh-runtime.bin'));
const recipe = JSON.parse(await readFile(path.join(root, 'app/src/main/assets/conversation-materialized-patch.json'), 'utf8'));
const expectedVersion = JSON.parse(await readFile(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
const source = await readFile(path.join(runtime, 'node_modules', '@deepseek-ai', 'dsh-client-ui-conversation', 'lib', 'client.js'), 'utf8');
assert.equal(recipe.dshVersion, expectedVersion);
assert.equal(recipe.module, '@deepseek-ai/dsh-client-ui-conversation/lib/client.js');
let patched = source;
for (const patch of recipe.patches) {
  assert.equal(patched.split(patch.before).length, 2, 'locked current source must expose the materialized withdrawal anchor');
  patched = patched.replace(patch.before, patch.after);
}
assert.match(patched, /DSHA_CONVERSATION_MATERIALIZED_WITHDRAWAL_V1/);
assert.match(patched, /previous\.visibility === "hidden"/);
assert.doesNotMatch(patched, /withdrew materialized target/);

const entry = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js';
const archiveSource = execFileSync('tar', ['-xOf', archive, entry], {
  encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true
});
assert.match(archiveSource, /DSHA_CONVERSATION_MATERIALIZED_WITHDRAWAL_V1/, 'generated dsh-runtime.bin must contain the withdrawal guard');
assert.doesNotMatch(archiveSource, /withdrew materialized target/, 'generated runtime must not retain the throwing withdrawal guard');
console.log('PASS: conversation target withdrawal retains a hidden stable node');
