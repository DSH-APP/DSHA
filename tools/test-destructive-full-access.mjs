// Source/unit only: real mandatory policy and classifier, controlled services.
// No SDK, subprocess, shell command, model, bridge or actual file mutation.
import assert from 'node:assert/strict';
import Module from 'node:module';
import {
  attachPolicy, captureExecution, prepareOperation, verifyDispatch,
  preparedEnvironment, verifyBashRequest, guardIntent,
} from '../app/src/main/assets/destructive-policy/index.js';
import { classifyCommand } from '../app/src/main/assets/destructive-policy/destructive.js';

const FULL = 'danger-full-access', GUARDED = 'guarded-full-access';
const CWD = '/dsha-memory-workspace', COMMAND = 'mkdir -p /tmp/dsha-memory-only';
const results = [];
let sequence = 0;

// This suite isolates the official session-preset proof. The independent native
// prerequisite is explicitly available and OFF, rather than missing or ON.
// Global consent mutations and unavailable reads have their own companion suite.
const originalLoad = Module._load;
const nativeOff = Object.freeze({ schema: 1, available: true, globalAllow: false, revision: 0,
  installationId: '11111111-2222-3333-4444-555555555555',
  bridgeRunId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', webGeneration: 7,
  runtimeInstanceId: '22222222-3333-4444-5555-666666666666:7' });
Module._load = function(request, ...args) {
  if (request !== '/run/dsha-launcher/native-consent.cjs') return originalLoad.call(this, request, ...args);
  return { readConnection: () => ({ applicationId: 'com.dsh.client', generation: 'controlled-native-off' }),
    bridgeFetch: async (route, init) => {
      assert.equal(route, '/device/consent'); assert.equal(init.expectedConnectionGeneration, 'controlled-native-off');
      assert(init.signal instanceof AbortSignal);
      return { ok: true, json: async () => ({ result: JSON.stringify(nativeOff) }) };
    } };
};

function fixture({ preset = FULL, mode = FULL, policy = 'never', raw, agentless = false } = {}) {
  const state = { preset, mode, policy, raw: raw ?? { preset, sandbox: mode, approval: policy, seeded: false } };
  const counts = { approvals: 0, effects: 0, ensures: 0, stats: 0 };
  const disposers = [], session = { header: { cwd: CWD } };
  let statHook;
  const services = {
    workingDirectory: { get: actual => { assert.equal(actual, session); return CWD; }, ensure: async () => { counts.ensures++; return CWD; } },
    shell: {}, shellEnv: { collect: () => ({ DSHA_MEMORY_FIXTURE: 'fixed' }) },
    permissionPresets: { current: actual => { assert.equal(actual, session); return state.preset; } },
    sessionProjections: { stateOf: (actual, name) => { assert.equal(actual, session); assert.equal(name, 'permissions'); return state.raw; } },
    sandboxPolicy: { resolve: ({ session: actual }) => { assert.equal(actual, session); return { mode: state.mode }; } },
    approval: {
      effectivePolicy: actual => { assert.equal(actual, session); return state.policy; },
      request: async () => { counts.approvals++; return 'allowed-once'; },
    },
    fs: { stat: async () => { counts.stats++; return statHook ? statHook(counts.stats) : { type: 'file', version: 7 }; } },
  };
  const ctx = { get: name => {
    const value = services[name];
    return state.proxyReads && value ? new Proxy(value, { get: (target, key) => key === Symbol.for('cordis.original') ? target : Reflect.get(target, key) }) : value;
  }, effect: callback => disposers.push(callback()), get fs() { return services.fs; } };
  const runtime = { ctx };
  services.tools = runtime;
  attachPolicy(runtime, ctx);
  const agent = agentless ? undefined : { ctx, session };
  function captured(name = 'bash', args = { command: COMMAND }) {
    const exec = { name, arguments: args, token: {}, callId: 'memory-' + ++sequence, agent, signal: new AbortController().signal };
    const tool = { execute(_args, actual) {
      if (name === 'bash') {
        assert.deepEqual(preparedEnvironment(actual), { DSHA_MEMORY_FIXTURE: 'fixed' });
        verifyBashRequest(actual, actual.arguments.workdir ?? CWD);
      }
      counts.effects++;
    } };
    captureExecution(runtime, exec, tool);
    return { exec, tool, prepare: () => prepareOperation(runtime, exec), dispatch: () => verifyDispatch(runtime, exec, tool), effect: () => tool.execute(exec.arguments, exec) };
  }
  return { state, counts, services, ctx, runtime, session, captured,
    onStat: callback => { statHook = callback; },
    guarded() { state.preset = GUARDED; state.mode = FULL; state.policy = 'ask'; state.raw = { preset: GUARDED, sandbox: FULL, approval: 'ask', seeded: false }; },
    dispose: () => { for (const dispose of disposers) dispose(); },
  };
}

async function test(name, body) {
  try { await body(); results.push({ name, status: 'PASS' }); }
  catch (error) { results.push({ name, status: 'FAIL', error: { name: error.name, message: error.message, stack: error.stack } }); }
}
async function withFixture(options, body) { const f = fixture(options); try { await body(f); } finally { f.dispose(); } }
const blocked = /DSHA_DESTRUCTIVE_BLOCKED/;

for (const command of [COMMAND, 'rm -rf /dsha-policy-memory-only', 'printf x > /dsha-policy-memory-only']) {
  await test('explicit-full-memory-effect: ' + command, () => withFixture({}, async f => {
    assert.equal(classifyCommand(command, { cwd: CWD }).requiresApproval, true);
    const call = f.captured('bash', { command });
    await call.prepare(); call.dispatch(); call.effect();
    assert.deepEqual(f.counts, { approvals: 0, effects: 1, ensures: 1, stats: 0 });
    assert.throws(call.dispatch, blocked, 'a full-access dispatch still cannot be replayed');
  }));
}
await test('guarded default retains one-time web approval', () => withFixture({ preset: GUARDED, policy: 'ask' }, async f => {
  const call = f.captured(); await call.prepare(); call.dispatch(); call.effect();
  assert.equal(f.counts.approvals, 1); assert.equal(f.counts.effects, 1);
}));
await test('fresh Cordis-shaped proxies preserve original provider identity', () => withFixture({}, async f => {
  f.state.proxyReads = true;
  const call = f.captured(); await call.prepare(); call.dispatch(); call.effect();
  assert.equal(f.counts.approvals, 0); assert.equal(f.counts.effects, 1);
}));
await test('fresh projection objects and seeded explicit full remain valid', () => withFixture({}, async f => {
  f.state.raw.seeded = true;
  f.services.sessionProjections.stateOf = () => ({ ...f.state.raw });
  const call = f.captured(); await call.prepare(); call.dispatch(); call.effect();
  assert.equal(f.counts.approvals, 0); assert.equal(f.counts.effects, 1);
}));
await test('model/header full-access claims do not replace current authority', () => withFixture({ preset: GUARDED, policy: 'ask' }, async f => {
  f.session.header.permissionPreset = FULL;
  const call = f.captured('bash', { command: COMMAND, permissionPreset: FULL, approval: 'never' });
  await call.prepare(); call.dispatch(); call.effect();
  assert.equal(f.counts.approvals, 1); assert.equal(f.counts.effects, 1);
}));

const nonAuthorities = [
  ['derived full without explicit preset', f => { f.state.raw = { sandbox: FULL, approval: 'never', seeded: false }; }],
  ['single never on guarded', f => { f.state.preset = GUARDED; f.state.raw.preset = GUARDED; }],
  ['read-only sandbox', f => { f.state.mode = 'read-only'; f.state.raw.sandbox = 'read-only'; }],
  ['workspace sandbox', f => { f.state.mode = 'workspace-write'; f.state.raw.sandbox = 'workspace-write'; }],
  ['raw sandbox mismatch', f => { f.state.raw.sandbox = 'read-only'; }],
  ['raw approval mismatch', f => { f.state.raw.approval = 'ask'; }],
  ['current preset mismatch', f => { f.state.preset = GUARDED; }],
  ['missing permissions projection', f => { delete f.services.sessionProjections; }],
  ['missing preset service', f => { delete f.services.permissionPresets; }],
  ['missing sandbox service', f => { delete f.services.sandboxPolicy; }],
  ['missing approval service', f => { delete f.services.approval; }],
  ['projection throws', f => { f.services.sessionProjections.stateOf = () => { throw Error('controlled projection failure'); }; }],
  ['current preset throws', f => { f.services.permissionPresets.current = () => { throw Error('controlled preset failure'); }; }],
];
for (const [name, arrange] of nonAuthorities) await test(name + ' keeps original rejection', () => withFixture({}, async f => {
  arrange(f); const call = f.captured();
  await assert.rejects(call.prepare, blocked);
  assert.equal(f.counts.approvals, 0); assert.equal(f.counts.effects, 0);
}));
await test('full named preset with ask uses original approval', () => withFixture({ policy: 'ask' }, async f => {
  const call = f.captured(); await call.prepare(); call.dispatch(); call.effect();
  assert.equal(f.counts.approvals, 1); assert.equal(f.counts.effects, 1);
}));
await test('agent-less call cannot acquire full exemption', () => withFixture({ agentless: true }, async f => {
  const call = f.captured(); await assert.rejects(call.prepare, blocked);
  assert.equal(f.counts.effects, 0); assert.equal(f.counts.approvals, 0);
}));

const revoked = [
  ['switch back to guarded', f => f.guarded()],
  ['projection becomes unavailable', f => { f.services.sessionProjections.stateOf = () => { throw Error('controlled unavailable'); }; }],
  ...['permissionPresets', 'sessionProjections', 'sandboxPolicy', 'approval'].map(name => ['replace ' + name + ' provider', f => { f.services[name] = { ...f.services[name] }; }]),
];
for (const [name, revoke] of revoked) await test('prepare then ' + name + ' blocks dispatch', () => withFixture({}, async f => {
  const call = f.captured(); await call.prepare(); revoke(f);
  assert.throws(call.dispatch, blocked); assert.equal(f.counts.effects, 0); assert.equal(f.counts.approvals, 0);
}));
for (const boundary of ['preparedEnvironment', 'verifyBashRequest']) await test('revoke after dispatch blocks ' + boundary, () => withFixture({}, async f => {
  const call = f.captured(); await call.prepare(); call.dispatch(); f.guarded();
  assert.throws(() => boundary === 'preparedEnvironment' ? preparedEnvironment(call.exec) : verifyBashRequest(call.exec, CWD), blocked);
  assert.equal(f.counts.effects, 0);
}));
await test('working-directory wait rechecks full authority', () => withFixture({}, async f => {
  f.services.workingDirectory.ensure = async () => { f.guarded(); return CWD; };
  const call = f.captured(); await assert.rejects(call.prepare, blocked); assert.equal(f.counts.effects, 0);
}));
await test('full access retains final Bash workdir check', () => withFixture({}, async f => {
  const call = f.captured(); await call.prepare(); call.dispatch();
  assert.throws(() => verifyBashRequest(call.exec, '/changed'), blocked); assert.equal(f.counts.effects, 0);
}));
await test('disposed policy cannot dispatch a prepared full call', () => withFixture({}, async f => {
  const call = f.captured(); await call.prepare(); f.dispose(); assert.throws(call.dispatch, blocked); assert.equal(f.counts.effects, 0);
}));

const target = { targetKey: 'memory:file', displayPath: '/dsha-memory-file' };
for (const [name, operation] of [['write', 'write'], ['edit', 'edit'], ['str_replace_editor', 'edit']]) {
  await test('explicit full ' + name + ' retains version intent without approval', () => withFixture({}, async f => {
    const call = f.captured(name, { memoryOnly: true }); await call.prepare(); call.dispatch();
    const intent = await guardIntent(f.ctx, call.exec, target, { kind: 'replaceIfVersion', version: 7 }, operation);
    assert.deepEqual(intent, operation === 'edit' ? { version: 7 } : { kind: 'replaceIfVersion', version: 7 });
    call.effect(); assert.equal(f.counts.effects, 1); assert.equal(f.counts.approvals, 0); assert.equal(f.counts.stats, 2);
  }));
}
await test('guarded overwrite still asks once', () => withFixture({ preset: GUARDED, policy: 'ask' }, async f => {
  const call = f.captured('write', {}); await call.prepare(); call.dispatch();
  await guardIntent(f.ctx, call.exec, target, { version: 7 }, 'write'); call.effect();
  assert.equal(f.counts.approvals, 1); assert.equal(f.counts.effects, 1);
}));
await test('never without explicit full still rejects overwrite', () => withFixture({ preset: GUARDED }, async f => {
  const call = f.captured('write', {}); await call.prepare(); call.dispatch();
  await assert.rejects(() => guardIntent(f.ctx, call.exec, target, { version: 7 }, 'write'), blocked);
  assert.equal(f.counts.approvals, 0); assert.equal(f.counts.effects, 0);
}));
for (const kind of ['version-race', 'observed-version-mismatch', 'create-overwrite', 'non-file', 'revoke-on-first-stat', 'revoke-on-second-stat', 'replace-fs']) {
  await test('full overwrite preserves ' + kind + ' rejection', () => withFixture({}, async f => {
    const call = f.captured('write', {}); await call.prepare(); call.dispatch();
    const intent = kind === 'observed-version-mismatch' ? { version: 8 } : kind === 'create-overwrite' ? { kind: 'createIfAbsent' } : { version: 7 };
    f.onStat(count => {
      if (kind === 'revoke-on-first-stat' && count === 1 || kind === 'revoke-on-second-stat' && count === 2) f.guarded();
      if (kind === 'replace-fs' && count === 1) f.services.fs = { ...f.services.fs };
      return { type: kind === 'non-file' ? 'directory' : 'file', version: kind === 'version-race' && count === 2 ? 8 : 7 };
    });
    await assert.rejects(() => guardIntent(f.ctx, call.exec, target, intent, 'write'), blocked);
    assert.equal(f.counts.effects, 0); assert.equal(f.counts.approvals, 0);
  }));
}
Module._load = originalLoad;
console.log(JSON.stringify({ scope: 'SOURCE_UNIT_REAL_POLICY_CONTROLLED_SERVICES_MEMORY_EFFECT_ONLY', nativeConsent: 'CONTROLLED_AVAILABLE_OFF', sdkLoaded: false, shellExecuted: false, actualPermissionProjectionFold: false, cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
