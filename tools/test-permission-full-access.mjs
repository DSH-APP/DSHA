// Source/unit: production coordinator with controlled official-Remote contracts.
// Does not load SDK, run a command, create a Host/browser, or mutate user data.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';

const assets = new URL('../app/src/main/assets/', import.meta.url);
const helper = fs.readFileSync(new URL('web-integration/permission-full-access.js', assets), 'utf8');
const create = new vm.Script(helper + '\ncreateDshaPermissionSubmit;').runInNewContext({ Error, Promise, Map, Number });
const FULL = 'danger-full-access', GUARDED = 'guarded-full-access';
const results = [];
const ok = value => ({ ok: true, value });

function fixture({ current = GUARDED, defaultPreset = GUARDED } = {}) {
  const state = { current, defaultPreset, generation: 'host-a', revision: 7, writable: true, namespace: true };
  const otherSession = { mode: 'read-only' }, calls = [], accepted = [];
  const hooks = {};
  let commandCount = 0, describeCount = 0, projectionCount = 0, mutateCount = 0;
  const describe = () => ({ writable: state.writable, namespaces: state.namespace ? [{ ns: 'permission', value: { defaultPreset: state.defaultPreset }, revision: state.revision }] : [] });
  const live = { async command(line) {
    calls.push(['command', line]); commandCount++;
    state.current = line.slice('/permission '.length);
    return hooks.command ? hooks.command(commandCount) : ok({ matched: true });
  } };
  let binding = { session: live };
  const ctx = {
    sessions: { binding(id) { assert.equal(id, 'current-a'); return binding; } },
    connection: { generation: { getSnapshot: () => ({ id: state.generation }) } },
    remote: {
      session: { async projections(request) {
        assert.equal(request.sessionId, 'current-a'); calls.push(['projection']); projectionCount++;
        return hooks.projection ? hooks.projection(projectionCount) : ok({ kind: 'sequenced', asOfSeq: 3, values: { permissions: { currentValue: state.current } } });
      } },
      settings: {
        async describe() {
          calls.push(['describe']); describeCount++;
          return hooks.describe ? hooks.describe(describeCount) : ok(describe());
        },
        async mutate(ns, ops, revision) {
          calls.push(['mutate']); mutateCount++;
          assert.equal(ns, 'permission'); assert.equal(revision, state.revision);
          assert.equal(JSON.stringify(ops), JSON.stringify([{ op: 'set', path: ['defaultPreset'], value: FULL }]));
          if (hooks.mutate) return hooks.mutate(mutateCount);
          state.defaultPreset = FULL; state.revision++;
          return ok(describe());
        },
      },
    },
  };
  const service = create(ctx, { acceptView: value => accepted.push(value) });
  return { state, hooks, calls, accepted, service, otherSession, describe,
    replaceBinding: () => { binding = { session: live }; },
    counts: () => ({ command: commandCount, describe: describeCount, projection: projectionCount, mutate: mutateCount }),
  };
}
async function test(name, body) {
  try { await body(); results.push({ name, status: 'PASS' }); }
  catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); }
}
async function using(options, body) { const f = fixture(options); try { await body(f); } finally { f.service.dispose(); } }
const incomplete = /权限设置未完成/;

await test('explicit full changes current and future default via official methods and reads both back', () => using({}, async f => {
  assert.equal(await f.service.submit('current-a', FULL), true);
  assert.deepEqual(f.calls.map(call => call[0]), ['describe', 'command', 'projection', 'mutate', 'describe', 'projection']);
  assert.deepEqual(f.counts(), { command: 1, describe: 2, projection: 2, mutate: 1 });
  assert.equal(f.state.current, FULL); assert.equal(f.state.defaultPreset, FULL);
  assert.equal(f.otherSession.mode, 'read-only'); assert.equal(f.accepted.length, 2);
}));
await test('already current full can still persist a guarded future default', () => using({ current: FULL }, async f => {
  await f.service.submit('current-a', FULL); assert.equal(f.counts().mutate, 1); assert.equal(f.state.defaultPreset, FULL);
}));
await test('already full future default is read back without redundant mutation', () => using({ defaultPreset: FULL }, async f => {
  await f.service.submit('current-a', FULL); assert.equal(f.counts().mutate, 0); assert.equal(f.counts().describe, 2); assert.equal(f.counts().projection, 2);
}));
await test('official workspace selection changes only this current session, preserving future choice', () => using({ current: FULL, defaultPreset: FULL }, async f => {
  await f.service.submit('current-a', 'workspace-write');
  assert.deepEqual(f.calls, [['command', '/permission workspace-write']]); assert.equal(f.state.defaultPreset, FULL); assert.equal(f.state.current, 'workspace-write');
}));
for (const [name, arrange] of [
  ['unwritable provider', f => { f.state.writable = false; }],
  ['missing namespace', f => { f.state.namespace = false; }],
  ['missing revision', f => { f.state.revision = undefined; }],
  ['failed describe', f => { f.hooks.describe = () => ({ ok: false }); }],
]) await test(name + ' fails before current or default write', () => using({}, async f => {
  arrange(f); await assert.rejects(f.service.submit('current-a', FULL), incomplete);
  assert.equal(f.counts().command, 0); assert.equal(f.counts().mutate, 0);
}));
for (const [name, response] of [['command not matched', () => ok({ matched: false })], ['command rejected', () => ({ ok: false })], ['command threw', () => { throw Error('controlled loss'); }]]) {
  await test(name + ' does not write default or replay command', () => using({}, async f => {
    f.hooks.command = response; await assert.rejects(f.service.submit('current-a', FULL), incomplete);
    assert.equal(f.counts().command, 1); assert.equal(f.counts().mutate, 0);
  }));
}
for (const [name, response] of [
  ['cached migration hints', ok({ kind: 'migration-required', values: { permissions: { currentValue: FULL } } })],
  ['non-full current', ok({ kind: 'sequenced', values: { permissions: { currentValue: GUARDED } } })],
  ['current read failure', { ok: false }],
]) await test(name + ' cannot authorize default write', () => using({}, async f => {
  f.hooks.projection = () => response; await assert.rejects(f.service.submit('current-a', FULL), incomplete); assert.equal(f.counts().mutate, 0);
}));
for (const [name, response] of [['rejected default write', () => ({ ok: false })], ['lost default response', () => { throw Error('controlled loss'); }]]) {
  await test(name + ' reports partial current result, no retry or rollback', () => using({}, async f => {
    f.hooks.mutate = response;
    await assert.rejects(f.service.submit('current-a', FULL), /当前会话：完全访问；新会话默认值：结果待核对/);
    assert.equal(f.counts().command, 1); assert.equal(f.counts().mutate, 1); assert.equal(f.state.current, FULL); assert.equal(f.accepted.length, 0);
  }));
}
await test('default readback disagreement is not success', () => using({}, async f => {
  f.hooks.describe = count => ok(count === 1 ? f.describe() : { ...f.describe(), namespaces: [{ ns: 'permission', value: { defaultPreset: GUARDED }, revision: 8 }] });
  await assert.rejects(f.service.submit('current-a', FULL), /DEFAULT_READBACK_NOT_FULL/); assert.equal(f.counts().mutate, 1);
}));
await test('current changed before final readback is not reported full', () => using({}, async f => {
  f.hooks.projection = count => ok({ kind: 'sequenced', values: { permissions: { currentValue: count === 1 ? FULL : GUARDED } } });
  await assert.rejects(f.service.submit('current-a', FULL), /当前会话：结果待核对；新会话默认值：完全访问/);
}));
for (const stage of ['describe', 'command', 'projection', 'mutate']) {
  for (const revoke of ['generation', 'binding', 'dispose']) await test(revoke + ' after awaited ' + stage + ' prevents later mutation/acceptance', () => using({}, async f => {
    f.hooks[stage] = () => {
      if (revoke === 'generation') f.state.generation = 'host-b';
      if (revoke === 'binding') f.replaceBinding();
      if (revoke === 'dispose') f.service.dispose();
      if (stage === 'describe' || stage === 'mutate') return ok(f.describe());
      if (stage === 'command') return ok({ matched: true });
      return ok({ kind: 'sequenced', values: { permissions: { currentValue: FULL } } });
    };
    await assert.rejects(f.service.submit('current-a', FULL), /CONTEXT_CHANGED/);
    assert.equal(f.counts().mutate, stage === 'mutate' ? 1 : 0); assert.equal(f.accepted.length, 0);
    if (stage === 'describe') assert.equal(f.counts().command, 0);
  }));
}
await test('simultaneous same-session clicks do not replay either write', () => using({}, async f => {
  let release; const gate = new Promise(resolve => { release = resolve; });
  f.hooks.describe = async count => { if (count === 1) await gate; return ok(f.describe()); };
  const first = f.service.submit('current-a', FULL);
  await assert.rejects(f.service.submit('current-a', FULL), /不会重复提交/);
  release(); await first; assert.equal(f.counts().command, 1); assert.equal(f.counts().mutate, 1);
}));

const input = process.argv[2];
assert(input, 'explicit locked official upstream client path or raw runtime root is required');
const upstream = fs.statSync(input).isDirectory()
  ? path.join(input, 'node_modules', '@deepseek-ai/dsh-client-ui-permission-presets/lib/client.js')
  : input;
const raw = fs.readFileSync(upstream, 'utf8');
const recipe = JSON.parse(fs.readFileSync(new URL('permission-full-access-patch.json', assets), 'utf8'));
await test('exact official client recipe applies once and parses without executing SDK', async () => {
  assert.equal(recipe.dshVersion, '0.2.1-rc.2');
  assert.equal(recipe.module, '@deepseek-ai/dsh-client-ui-permission-presets/lib/client.js');
  let patched = raw;
  for (const patch of recipe.patches) {
    assert.equal(patched.split(patch.before).length - 1, 1, patch.before.slice(0, 100));
    const after = patch.prependAsset ? fs.readFileSync(new URL(patch.prependAsset, assets), 'utf8') + '\n' + patch.after : patch.after;
    patched = patched.replace(patch.before, after);
  }
  new vm.Script(patched);
  assert(patched.includes('createDshaPermissionSubmit(ctx, ctx.configForms.describe())'));
  assert(patched.includes('"data-dsha-permission-error": true'));
  assert.equal(patched.includes('"preset.fullAccess": "完全权限"'), false);
});
console.log(JSON.stringify({ scope: 'SOURCE_UNIT_COORDINATOR_CONTROLLED_REMOTE_CONTRACTS', sdkLoaded: false, browserLaunched: false, commandsExecuted: false, remoteProviderPersistenceTested: false, upstream: { path: upstream, sha256: crypto.createHash('sha256').update(raw).digest('hex'), bytes: Buffer.byteLength(raw) }, cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
