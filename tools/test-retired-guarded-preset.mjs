// Only the new retirement delta: exact published source patch/parse plus the
// actual patched choice/legacy-read functions with controlled values. No SDK.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
const assets = new URL('../app/src/main/assets/', import.meta.url), rawRoot = process.argv[2];
assert(rawRoot, 'explicit locked official raw root is required');
const OLD = 'guarded-full-access', FULL = 'danger-full-access', results = [], inputs = [];
async function test(name, body) { try { await body(); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
function patched(asset) {
  const recipe = JSON.parse(fs.readFileSync(new URL(asset, assets), 'utf8'));
  const file = path.join(rawRoot, 'node_modules', recipe.module);
  let source = fs.readFileSync(file, 'utf8');
  const sha256 = crypto.createHash('sha256').update(source).digest('hex');
  inputs.push({ path: file, sha256, bytes: Buffer.byteLength(source) });
  if (recipe.sourceSha256) assert.equal(sha256, recipe.sourceSha256);
  for (const patch of recipe.patches) {
    assert.equal(source.split(patch.before).length - 1, 1, patch.before.slice(0, 100));
    const after = patch.prependAsset ? fs.readFileSync(new URL(patch.prependAsset, assets), 'utf8') + '\n' + patch.after : patch.after;
    source = source.replace(patch.before, after);
  }
  return source;
}
let host, client;
await test('retirement host recipe exact source applies and ESM parses without evaluation', async () => {
  host = patched('retire-guarded-preset-patch.json'); new vm.SourceTextModule(host);
});
await test('whole official client with full-access and retirement chains parses without evaluation', async () => {
  client = patched('permission-full-access-patch.json'); new vm.Script(client);
});
function method(source, name) {
  const prefix = '\t\t' + name, indent = prefix.match(/^\t*/)[0];
  const start = source.indexOf(prefix); assert(start >= 0);
  const end = source.indexOf('\n' + indent + '/**', start); assert(end > start);
  return source.slice(start, end).trim();
}
await test('actual host names/catalog hide old ID while preserving official choices and Auto', async () => {
  const sample = new Function('AUTO_PRESET', 'return {' + [method(host, 'get names()'), method(host, 'catalog()'), method(host, 'resolve(name)')].join(',') + '};')('auto');
  sample.presets = { 'read-only': { sandbox: 'read-only', approval: 'ask' }, 'workspace-write': { sandbox: 'workspace-write', approval: 'ask' }, [FULL]: { sandbox: FULL, approval: 'never' }, [OLD]: { sandbox: FULL, approval: 'ask' } };
  sample.specOf = name => sample.presets[name]; sample.optionOf = name => ({ value: name });
  sample.defaultSettings = () => ({ defaultPreset: OLD });
  const before = JSON.stringify(sample.presets);
  assert.deepEqual(sample.names, ['read-only', 'workspace-write', FULL]);
  sample.autoAdmit = () => {};
  assert.deepEqual(sample.names, ['read-only', 'workspace-write', FULL, 'auto']);
  const catalog = sample.catalog();
  assert.equal(catalog.options.some(value => value.value === OLD), false);
  assert.equal(catalog.defaultOptions.some(value => value.value === OLD), false);
  assert.equal(catalog.defaultPreset, OLD, 'never rewrite an existing default');
  assert.equal(sample.resolve(OLD), sample.presets[OLD], 'historical resolve remains readable');
  assert.equal(JSON.stringify(sample.presets), before);
});
await test('actual client legacy-default read succeeds without offering the retired option', async () => {
  const start = client.indexOf('function permissionDefaultOf('), end = client.indexOf('\n\t\t/** Controller deriving', start);
  assert(start > 0 && end > start);
  const read = new Function('displayPermissionPreset', 'return (' + client.slice(start, end) + ');')((_id, label) => label);
  const catalog = { defaultPreset: OLD, defaultOptions: [{ value: FULL, name: 'Full access' }] };
  const result = read({ value: { defaultPreset: OLD } }, catalog);
  assert.equal(result.currentValue, OLD); assert.deepEqual(result.options.map(option => option.id), [FULL]);
});
await test('actual stale settings selection rejects the retired ID before touching a store or Remote', async () => {
  const select = new Function('return ({' + method(client, '\tasync select(preset)') + '}).select;')();
  let failures = 0;
  await select.call({ fail(error) { assert.match(error.message, /仅兼容读取/); failures++; } }, OLD);
  assert.equal(failures, 1);
});
await test('current/slash coordinator rejects retired ID before any context or command call', async () => {
  const helper = fs.readFileSync(new URL('web-integration/permission-full-access.js', assets), 'utf8');
  const create = new vm.Script(helper + '\ncreateDshaPermissionSubmit;').runInNewContext({ Error, Promise, Map, Number });
  const coordinator = create(new Proxy({}, { get() { throw Error('must not access context'); } }), {});
  await assert.rejects(coordinator.submit('old-session', OLD), /仅兼容读取/); coordinator.dispose();
});
console.log(JSON.stringify({ scope: 'SOURCE_UNIT_RETIREMENT_DELTA_ONLY', sdkLoaded: false, remoteCalled: false, sessionsRewritten: false, cases: results.length, passed: results.filter(row => row.status === 'PASS').length, inputs, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
