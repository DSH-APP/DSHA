import {testRuntime} from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const recipe = JSON.parse(await readFile(path.join(root, 'app/src/main/assets/lexical-claim-patch.json'), 'utf8'));
const expectedVersion = JSON.parse(await readFile(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
const moduleName = '@deepseek-ai/dsh-client-ui-conversation/lib/client.js';
const source = await readFile(path.join(runtime, 'node_modules', moduleName));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function verifyRetirement(bytes, proof) {
  assert.equal(proof.version, 1);
  assert.equal(proof.dshVersion, expectedVersion);
  assert.equal(proof.module, moduleName);
  assert.equal(proof.retired, true);
  assert.deepEqual(proof.patches, []);
  assert.equal(proof.sourceSha256, digest(bytes), 'retirement is bound to the exact reviewed official source');
  const text = bytes.toString('utf8');
  assert.equal(text.split('class CommandChipNode').length, 2);
  assert.doesNotMatch(text, /TOKEN_STYLE|node\.splitText\(token\.length\)|DSHA_CLAIM_DECOR_OVERFLOW_STYLE_V1/);
}
verifyRetirement(source, recipe);
assert.throws(() => verifyRetirement(Buffer.concat([source, Buffer.from('\n// drift')]), recipe));
assert.throws(() => verifyRetirement(source, {...recipe, retired: false}));
const archive = path.resolve(process.env.DSHA_RUNTIME_ARCHIVE || path.join(root, 'app/src/main/assets/dsh-runtime.bin'));
const archiveEntry = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/' + moduleName;
const archiveSource = execFileSync('tar', ['-xOf', archive, archiveEntry], {
  encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true
});
assert.equal(archiveSource.split('class CommandChipNode').length, 2, 'archive preserves the upstream atomic command chip');
assert.doesNotMatch(archiveSource, /TOKEN_STYLE|node\.splitText\(token\.length\)|DSHA_CLAIM_DECOR_OVERFLOW_STYLE_V1/);
console.log('PASS: exact official CommandChip retirement proof, drift negatives, and generated archive architecture');
