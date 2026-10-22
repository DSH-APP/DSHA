// Actual policy/classifier with a controlled replacement for the exact signed
// launcher SDK path. No SDK, HTTP, shell, model, phone or filesystem mutation.
import assert from 'node:assert/strict';
import Module from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';

const FULL = 'danger-full-access', GUARDED = 'guarded-full-access', CWD = '/dsha-memory-workspace';
const descriptor = { applicationId: 'com.dsh.client', installInstanceId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', bridgeRunId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', generation: 2 };
const consent = { schema: 1, available: true, globalAllow: true, revision: 9, installationId: descriptor.installInstanceId, bridgeRunId: descriptor.bridgeRunId, webGeneration: 7, runtimeInstanceId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc:7' };
let active;
const sdk = {
  readConnection() { if (active.sdkThrows) throw active.sdkThrows === true ? Error('controlled SDK unavailable') : active.sdkThrows; return { ...active.descriptor }; },
  async bridgeFetch(route, init) {
    active.reads++;
    assert.equal(route, '/device/consent'); assert.equal(init.expectedConnectionGeneration, active.descriptor.generation);
    assert(init.signal instanceof AbortSignal);
    if (active.fetch) return active.fetch(active.reads, init);
    return { ok: true, json: async () => ({ result: JSON.stringify(active.consent) }) };
  },
};
const load = Module._load;
Module._load = function(request, ...args) {
  if (request !== '/run/dsha-launcher/native-consent.cjs') return load.call(this, request, ...args);
  if (active.moduleError) throw active.moduleError;
  return nativeWireOnly ? actualTransport : sdk;
};
const policy = await import('../app/src/main/assets/destructive-policy/index.js');
const results = [];
const availabilityDelta = process.argv.includes('--mandatory-availability-only');
const nativeFixtureAt = process.argv.indexOf('--native-wire-fixture');
const nativeWireOnly = nativeFixtureAt !== -1;
assert(!(availabilityDelta && nativeWireOnly), 'select one related group');
// The Java-wire group crosses the actual APK launcher query as well as the
// actual policy. Only Android filesystem/socket adapters are controlled here.
const actualTransport = (() => {
  const token = 't'.repeat(43);
  const info = directory => ({ dev: 1, ino: 1, size: token.length, mode: 0o100600,
    isDirectory: () => directory, isFile: () => !directory, isSymbolicLink: () => false });
  const platformFs = { constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000 },
    lstatSync: file => info(file !== '/root/.dsh/.bridge_token'),
    openSync: () => 7, fstatSync: () => info(false),
    readSync: (_fd, bytes) => bytes.write(token), closeSync: () => {} };
  const http = { request(options, accept) {
    assert.equal(options.hostname, '127.0.0.1'); assert.equal(options.port, 3090);
    assert.equal(options.path, '/app/native-policy'); assert.equal(options.headers['X-Token'], token);
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => {
      active.reads++;
      const response = new EventEmitter(); response.statusCode = 200;
      response.destroy = error => response.emit('error', error);
      accept(response);
      response.emit('data', Buffer.from(JSON.stringify({ result: JSON.stringify(active.consent) })));
      response.emit('end');
    });
    return request;
  } };
  const module = { exports: {} }, require = name => ({ 'node:fs': platformFs, 'node:http': http, 'node:crypto': crypto })[name];
  const source = fs.readFileSync(new URL('../app/src/main/assets/native-consent.cjs', import.meta.url), 'utf8');
  vm.runInNewContext('(function(require,module,Buffer){' + source + '\n})', {})(require, module, Buffer);
  return module.exports;
})();
let serial = 0;
function fixture({ global = true, preset = GUARDED, approval = 'ask' } = {}) {
  const f = { descriptor: { ...descriptor }, consent: { ...consent, globalAllow: global }, reads: 0, approvals: 0, effects: 0, stats: 0 };
  active = f;
  const session = { header: { cwd: CWD } }, raw = { preset, sandbox: FULL, approval, seeded: false };
  const disposers = [];
  const services = {
    workingDirectory: { get: () => CWD, ensure: async () => CWD }, shell: {}, shellEnv: { collect: () => ({}) },
    permissionPresets: { current: () => raw.preset }, sessionProjections: { stateOf: () => raw },
    sandboxPolicy: { resolve: () => ({ mode: raw.sandbox }) },
    approval: { effectivePolicy: () => raw.approval, request: async () => { f.approvals++; return f.answer ? f.answer() : 'allowed-once'; } },
    fs: { stat: async () => { f.stats++; return f.stat ? f.stat(f.stats) : { type: 'file', version: 3 }; } },
  };
  const ctx = { get: name => services[name], effect: callback => disposers.push(callback()), get fs() { return services.fs; } };
  const runtime = { ctx }; services.tools = runtime;
  policy.attachPolicy(runtime, ctx);
  const agent = { ctx, session };
  const tool = { execute() { f.effects++; } }; f.currentTool = tool;
  const controller = new AbortController();
  const exec = { name: 'bash', arguments: { command: 'mkdir -p /tmp/dsha-memory-only' }, token: {}, callId: 'global-' + ++serial, agent, signal: controller.signal };
  f.abort = cause => controller.abort(cause);
  f.capture = name => { exec.name = name; policy.captureExecution(runtime, exec, tool); };
  f.capture('bash');
  f.prepare = () => policy.prepareOperation(runtime, exec);
  f.dispatch = async () => {
    await policy.verifyGlobalConsent(exec);
    // Matches the actual signed recipe: re-resolve only after the await.
    const current = f.currentTool;
    policy.verifyDispatch(runtime, exec, current);
    if (exec.name === 'bash') {
      await policy.verifyGlobalConsent(exec); policy.verifyBashRequest(exec, CWD); policy.preparedEnvironment(exec);
    }
    current.execute(exec.arguments, exec);
  };
  f.intent = () => policy.guardIntent(ctx, exec, { targetKey: 'memory:file', displayPath: '/dsha-memory-file' }, { version: 3 }, 'write');
  f.createIntent = () => policy.guardIntent(ctx, exec, { targetKey: 'memory:fresh', displayPath: '/dsha-memory-fresh' }, undefined, 'write');
  f.prepareFile = async () => { f.capture('write'); await f.prepare(); await policy.verifyGlobalConsent(exec); policy.verifyDispatch(runtime, exec, tool); };
  f.services = services; f.exec = exec; f.raw = raw;
  f.dispose = () => { for (const dispose of disposers) dispose(); };
  return f;
}
async function using(options, body) { const f = fixture(options); try { await body(f); } finally { f.dispose(); } }
async function test(name, body, related = false, wire = false) { if (availabilityDelta && !related || nativeWireOnly && !wire) return; try { await body(); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
const blocked = /DSHA_DESTRUCTIVE_BLOCKED/;
let nativeFixture;
try {
  if (nativeWireOnly) {
    const fixturePath = process.argv[nativeFixtureAt + 1]; assert(fixturePath, 'explicit native producer fixture is required');
    const bytes = fs.readFileSync(fixturePath), wire = JSON.parse(bytes.toString('utf8'));
    const context = { applicationId: 'com.dsh.client', installInstanceId: '11111111-2222-3333-4444-555555555555', bridgeRunId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', generation: 3, webGeneration: 7, runtimeInstanceId: '22222222-3333-4444-5555-666666666666:7' };
    const unsafe = ['read_unavailable', 'malformed', 'wrong_type', 'changing', 'save_failed', 'reconfirm', 'stale_revision', 'missing'];
    const controls = ['on', 'off', 'unconfigured'];
    assert.equal(wire.schema, 1); assert.equal(wire.producer, 'DeviceGrants.consentSnapshot -> NativeConsentProjection.payload');
    assert.deepEqual(wire.context, context);
    assert.deepEqual(Object.keys(wire.cases).sort(), [...unsafe, ...controls].sort());
    nativeFixture = { path: path.resolve(fixturePath), sha256: crypto.createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length, producer: wire.producer };
    for (const name of [...unsafe, ...controls]) await test('native wire ' + name + ' reaches the real guest policy without changing the payload', () => using({}, async f => {
      const row = wire.cases[name], payload = row.payload;
      assert.equal(payload.schema, 1); assert.equal(payload.installationId, context.installInstanceId); assert.equal(payload.bridgeRunId, context.bridgeRunId);
      assert.equal(payload.webGeneration, context.webGeneration); assert.equal(payload.runtimeInstanceId, context.runtimeInstanceId);
      assert(Number.isSafeInteger(payload.revision)); assert.equal(typeof row.readState, 'string'); assert.equal(typeof row.saveState, 'string');
      f.descriptor = { applicationId: context.applicationId, installInstanceId: context.installInstanceId, bridgeRunId: context.bridgeRunId, generation: context.generation };
      // Transport replacement only: every production mapper field is returned
      // unchanged; no unavailable/allow value is manufactured by this consumer.
      f.consent = payload;
      if (unsafe.includes(name)) {
        assert.equal(payload.available, false); assert.equal(payload.globalAllow, false);
        await assert.rejects(async () => { await f.prepare(); await f.dispatch(); }, error => error.code === 'DSHA_NATIVE_CONSENT_UNAVAILABLE' && error.message.includes('NOT_AVAILABLE'));
        assert.equal(f.approvals, 0); assert.equal(f.effects, 0);
      } else {
        assert.equal(payload.available, true); assert.equal(payload.globalAllow, name === 'on');
        await f.prepare(); await f.dispatch(); assert.equal(f.effects, 1); assert.equal(f.approvals, name === 'on' ? 0 : 1);
      }
      assert.strictEqual(f.consent, payload);
    }), false, true);
  }
  for (const approval of ['ask', 'never']) await test('authenticated global ON overrides DSHA guard under ' + approval, () => using({ approval }, async f => {
    await f.prepare(); await f.dispatch(); assert.equal(f.effects, 1); assert.equal(f.approvals, 0); assert(f.reads >= 4);
  }), approval === 'ask');
  await test('global OFF retains guarded one-time approval', () => using({ global: false }, async f => {
    await f.prepare(); await f.dispatch(); assert.equal(f.approvals, 1); assert.equal(f.effects, 1);
  }), true);
  await test('global OFF retains official explicit full exemption', () => using({ global: false, preset: FULL, approval: 'never' }, async f => {
    await f.prepare(); await f.dispatch(); assert.equal(f.approvals, 0); assert.equal(f.effects, 1);
  }));
  await test('global OFF and non-full never still reject', () => using({ global: false, approval: 'never' }, async f => {
    await assert.rejects(f.prepare, blocked); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  const malformed = [
    ['unavailable', value => { value.available = false; }], ['missing availability', value => { delete value.available; }],
    ['wrong schema', value => { value.schema = 2; }], ['nonboolean', value => { value.globalAllow = 'true'; }],
    ['negative revision', value => { value.revision = -1; }], ['fractional revision', value => { value.revision = 1.5; }],
    ['invalid installation', value => { value.installationId = 'invalid-installation'; }],
    ['invalid bridge run', value => { value.bridgeRunId = 'invalid-bridge'; }],
    ['no web generation', value => { value.webGeneration = 0; }], ['bad runtime identity', value => { value.runtimeInstanceId = ''; }],
  ];
  for (const [name, mutate] of malformed) await test(name + ' is not global authority', () => using({ approval: 'never' }, async f => {
    mutate(f.consent); await assert.rejects(f.prepare, blocked); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  for (const kind of ['sdk-error', 'http-error', 'non-string-envelope', 'oversize-envelope']) await test(kind + ' grants no exemption', () => using({ approval: 'never' }, async f => {
    if (kind === 'sdk-error') f.sdkThrows = true;
    else f.fetch = async () => ({ ok: kind !== 'http-error', json: async () => ({ result: kind === 'non-string-envelope' ? { ...f.consent } : 'x'.repeat(4097) }) });
    await assert.rejects(f.prepare, blocked); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  const changed = [
    ['OFF', f => { f.consent.globalAllow = false; f.consent.revision++; }],
    ['ON new revision', f => { f.consent.revision++; }],
    ['new installation', f => { f.consent.installationId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'; }],
    ['new runtime', f => { f.consent.runtimeInstanceId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd:7'; }],
    ['new web', f => { f.consent.webGeneration++; f.consent.runtimeInstanceId = f.consent.runtimeInstanceId.split(':')[0] + ':' + f.consent.webGeneration; }],
    ['new bridge', f => { f.descriptor.bridgeRunId = f.consent.bridgeRunId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'; }],
    ['descriptor generation', f => { f.descriptor.generation++; }],
    ['unavailable', f => { f.sdkThrows = true; }],
  ];
  for (const [name, mutate] of changed) await test('after prepare ' + name + ' blocks actual memory dispatch', () => using({}, async f => {
    await f.prepare(); mutate(f); await assert.rejects(f.dispatch, blocked); assert.equal(f.effects, 0);
  }));
  const unavailable = [
    ['HTTP_ERROR HTTP 503', f => { f.fetch = async () => ({ ok: false, status: 503 }); }],
    ['INVALID_REPLY', f => { const cause = new SyntaxError('controlled reply decode failed'); f.expectedCause = cause; f.fetch = async () => ({ ok: true, json: async () => { throw cause; } }); }],
    ['INVALID_REPLY', f => { f.fetch = async () => ({ ok: true, json: async () => ({ result: JSON.stringify({ ...f.consent, revision: -1 }) }) }); }],
    ['NOT_AVAILABLE', f => { f.fetch = async () => ({ ok: true, json: async () => ({ result: JSON.stringify({ schema: 1, available: false, globalAllow: false }) }) }); }],
    ['DESCRIPTOR_UNAVAILABLE', f => { f.expectedCause = f.sdkThrows = Error('controlled descriptor unavailable'); }],
    ['QUERY_FAILED', f => { const cause = Error('controlled bridge transport unavailable'); f.expectedCause = cause; f.fetch = async () => { throw cause; }; }],
  ];
  for (const [reason, arrange] of unavailable) await test('fresh native read ' + reason + ' is unavailable, not facts-changed', () => using({}, async f => {
    await f.prepare(); arrange(f);
    await assert.rejects(f.dispatch, error => {
      assert.equal(error.code, 'DSHA_NATIVE_CONSENT_UNAVAILABLE');
      assert(error.message.includes(reason)); assert.equal(error.cause, f.expectedCause);
      assert(!error.message.includes('controlled '));
      return true;
    });
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  await test('actual bounded native-read timeout preserves its cause and dispatches nothing', () => using({}, async f => {
    await f.prepare(); let cause;
    f.fetch = async (_read, init) => new Promise((_resolve, reject) => {
      const hold = setTimeout(() => reject(Error('controlled deadline did not fire')), 2500);
      init.signal.addEventListener('abort', () => { clearTimeout(hold); cause = init.signal.reason; reject(cause); }, { once: true });
    });
    await assert.rejects(f.dispatch, error => {
      assert.equal(error.code, 'DSHA_NATIVE_CONSENT_UNAVAILABLE'); assert(error.message.includes('TIMEOUT'));
      assert.equal(error.cause, cause); assert.equal(cause.name, 'TimeoutError'); return true;
    });
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }), true);
  await test('different validated native facts retain their distinct rejection', () => using({}, async f => {
    await f.prepare(); f.consent.revision++;
    await assert.rejects(f.dispatch, error => error.code === 'DSHA_NATIVE_CONSENT_FACTS_CHANGED' && error.cause === undefined);
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  await test('mandatory failed capture cannot upgrade when native consent later becomes available', () => using({ preset: FULL, approval: 'never' }, async f => {
    const cause = Error('controlled initial descriptor unavailable'); f.sdkThrows = cause;
    await assert.rejects(f.prepare, error => error.code === 'DSHA_NATIVE_CONSENT_UNAVAILABLE' && error.cause === cause);
    f.sdkThrows = false;
    await assert.rejects(f.dispatch, error => error.code === 'DSHA_NATIVE_CONSENT_AVAILABILITY_CHANGED' && error.cause === cause);
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }), true);
  const initialUnavailable = [
    ['SDK_UNAVAILABLE', f => { f.expectedCause = f.moduleError = Error('controlled signed SDK missing'); }],
    ['HTTP_ERROR HTTP 503', f => { f.fetch = async () => ({ ok: false, status: 503 }); }],
    ['INVALID_REPLY', f => { const cause = new SyntaxError('controlled initial reply invalid'); f.expectedCause = cause; f.fetch = async () => ({ ok: true, json: async () => { throw cause; } }); }],
    ['NOT_AVAILABLE', f => { f.consent.available = false; }],
  ];
  for (const [reason, arrange] of initialUnavailable) await test('mandatory initial ' + reason + ' rejects without an approval request', () => using({}, async f => {
    arrange(f);
    await assert.rejects(f.prepare, error => {
      assert.equal(error.code, 'DSHA_NATIVE_CONSENT_UNAVAILABLE'); assert(error.message.includes(reason));
      assert.equal(error.cause, f.expectedCause); return true;
    });
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }), true);
  for (const phase of ['before-query', 'during-query']) await test('mandatory cancellation ' + phase + ' retains its original cause and never asks', () => using({}, async f => {
    const cause = Error('controlled execution cancellation');
    if (phase === 'before-query') f.abort(cause);
    else f.fetch = async () => { f.abort(cause); throw cause; };
    await assert.rejects(f.prepare, error => error.code === 'DSHA_EXECUTION_CANCELLED' && error.cause === cause);
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0); if (phase === 'before-query') assert.equal(f.reads, 0);
  }), true);
  await test('ON during an outstanding OFF approval invalidates the old answer', () => using({ global: false }, async f => {
    f.answer = async () => { f.consent.globalAllow = true; f.consent.revision++; return 'allowed-once'; };
    await assert.rejects(f.prepare, blocked); assert.equal(f.approvals, 1); assert.equal(f.effects, 0);
  }));
  await test('OFF revision change during an outstanding approval invalidates the old answer', () => using({ global: false }, async f => {
    f.answer = async () => { f.consent.revision++; return 'allowed-once'; };
    await assert.rejects(f.prepare, blocked); assert.equal(f.effects, 0);
  }));
  await test('OFF while settling the live cwd cancels ON exemption', () => using({}, async f => {
    f.services.workingDirectory.ensure = async () => { f.consent.globalAllow = false; f.consent.revision++; return CWD; };
    await assert.rejects(f.prepare, blocked); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  await test('tool replacement during awaited authority check does not dispatch old body', () => using({}, async f => {
    await f.prepare();
    f.fetch = async () => { f.currentTool = { execute() { f.effects++; } }; return { ok: true, json: async () => ({ result: JSON.stringify(f.consent) }) }; };
    await assert.rejects(f.dispatch, blocked); assert.equal(f.effects, 0);
  }));
  await test('global ON overwrites retain CAS without an approval lease', () => using({}, async f => {
    await f.prepareFile(); assert.deepEqual(await f.intent(), { kind: 'replaceIfVersion', version: 3 });
    assert.equal(f.approvals, 0); assert.equal(f.stats, 2);
  }));
  await test('absent creation rejects provider replacement during awaited native consent read', () => using({}, async f => {
    await f.prepareFile(); f.stat = () => undefined;
    f.fetch = async () => {
      f.services.fs = { ...f.services.fs };
      return { ok: true, json: async () => ({ result: JSON.stringify(f.consent) }) };
    };
    await assert.rejects(f.createIntent, /Filesystem service changed/); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  for (const kind of ['revision-revoke', 'file-version-race', 'filesystem-replace']) await test('overwrite keeps ' + kind + ' fence', () => using({}, async f => {
    await f.prepareFile();
    f.stat = count => {
      if (count === 2 && kind === 'revision-revoke') { f.consent.globalAllow = false; f.consent.revision++; }
      if (count === 1 && kind === 'filesystem-replace') f.services.fs = { ...f.services.fs };
      return { type: 'file', version: count === 2 && kind === 'file-version-race' ? 4 : 3 };
    };
    await assert.rejects(f.intent, blocked); assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }));
  await test('mandatory native authority cannot fall back to official full on an unavailable read', () => using({ preset: FULL, approval: 'never' }, async f => {
    f.sdkThrows = true; await assert.rejects(f.prepare, error => error.code === 'DSHA_NATIVE_CONSENT_UNAVAILABLE');
    assert.equal(f.effects, 0); assert.equal(f.approvals, 0);
  }), true);
  const raw = process.argv[2]; assert(raw, 'explicit locked official raw runtime is required');
  for (const [asset, module] of [['guard-tools-patch.json', '@deepseek-ai/dsh-tools/lib/index.js'], ['guard-bash-patch.json', '@deepseek-ai/dsh-tool-bash/lib/index.js']]) {
    await test(asset + ' exact raw application and ESM parse without evaluation', async () => {
      const recipe = JSON.parse(fs.readFileSync(new URL('../app/src/main/assets/' + asset, import.meta.url), 'utf8'));
      let source = fs.readFileSync(path.join(raw, 'node_modules', module), 'utf8');
      assert.equal(crypto.createHash('sha256').update(source).digest('hex'), recipe.sourceSha256);
      for (const patch of recipe.patches) { assert.equal(source.split(patch.before).length - 1, 1); source = source.replace(patch.before, patch.after); }
      new vm.SourceTextModule(source);
      if (asset === 'guard-tools-patch.json') assert(source.includes('await verifyGlobalConsent(exec);\n\t\t\tconst tool = this.resolveExecution('));
      else assert(source.includes('await verifyGlobalConsent(exec);\n\t\t\t\tverifyBashRequest(exec, workdir);'));
    });
  }
} finally { Module._load = load; }
console.log(JSON.stringify({ scope: 'SOURCE_UNIT_REAL_POLICY_CONTROLLED_SIGNED_SDK_PATH_AND_MEMORY_EFFECT', selection: nativeWireOnly ? 'ACTUAL_NATIVE_MAPPER_WIRE_DELTA' : availabilityDelta ? 'MANDATORY_AVAILABILITY_DELTA' : 'ALL', nativeFixture, sdkLoaded: nativeWireOnly, deviceCredentialRead: false, networkRequested: false, commandBodyExecuted: false, nativeEndpointTested: false, cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
