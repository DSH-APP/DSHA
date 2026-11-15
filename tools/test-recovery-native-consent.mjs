import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const require = createRequire(import.meta.url);
const instance = randomBytes(16).toString('hex'), token = randomBytes(32).toString('hex');
const installation = [instance.slice(0, 8), instance.slice(8, 12), instance.slice(12, 16), instance.slice(16, 20), instance.slice(20)].join('-');
const run = randomUUID();
let stopped = false, bypass = false, calls = 0, heldReply;
const broker = createServer(async (request, response) => {
  calls++;
  assert.equal(request.method, 'POST');
  assert.equal(request.url, '/v1/consent');
  assert.equal(request.headers.authorization, 'Bearer ' + token);
  let body = ''; for await (const part of request) body += part;
  assert.equal(body, '{}');
  if (heldReply) { heldReply.entered.resolve(); await heldReply.release.promise; }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ schema: 1, available: true, globalAllow: bypass, revision: 0,
    installationId: installation, bridgeRunId: run, webGeneration: 7,
    runtimeInstanceId: run + ':7', recoveryInstanceId: instance }));
});
await new Promise(resolve => broker.listen(0, '127.0.0.1', resolve));
const env = { DSHA_RECOVERY_INSTANCE_ID: instance, DSHA_RECOVERY_GENERATION: '7',
  DSHA_RECOVERY_BROKER_PORT: String(broker.address().port), DSHA_RECOVERY_BROKER_TOKEN: token };
const source = await readFile(path.join(root, 'app/src/main/assets/recovery-native-consent.cjs'), 'utf8');
const exports = { exports: {} };
const script = new vm.Script('(function(require,module,process){' + source + '\n})', { filename: 'recovery-native-consent.cjs' });
script.runInThisContext()(name => name === 'node:fs' ? {
  lstatSync(target) {
    assert.equal(target, '/root/.recovery-stop');
    if (stopped) return {};
    const error = Error('missing'); error.code = 'ENOENT'; throw error;
  },
} : require(name), exports, { env });
const sdk = exports.exports;
const query = async () => {
  const connection = sdk.readConnection();
  const response = await sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: connection.generation,
    expectedConnectionPort: connection.port, signal: AbortSignal.timeout(2000) });
  return JSON.parse((await response.json()).result);
};
try {
  const production = sdk.readConnection();
  assert.equal(production.applicationId, 'io.github.sunsetrne.dsha');
  env.DSHA_BRIDGE_APPLICATION_ID = 'io.github.sunsetrne.dsha.test';
  assert.equal(sdk.readConnection().applicationId, 'io.github.sunsetrne.dsha.test');
  assert.equal(sdk.readConnection().generation, production.generation);
  assert.equal(calls, 0, 'application identity selection does not dispatch a request');
  assert.equal((await query()).globalAllow, false);
  assert.equal((await query()).bridgeRunId, run);
  const originalPort = env.DSHA_RECOVERY_BROKER_PORT;
  const changedPort = String(Number(originalPort) === 65535 ? 65534 : Number(originalPort) + 1);
  const connection = sdk.readConnection();
  assert.equal(connection.port, Number(originalPort));
  const beforePortRejections = calls;
  await assert.rejects(sdk.bridgeFetch('/device/consent', {
    expectedConnectionGeneration: connection.generation, expectedConnectionPort: Number(changedPort),
  }), /CHANGED/);
  env.DSHA_RECOVERY_BROKER_PORT = changedPort;
  try {
    assert.notEqual(sdk.readConnection().generation, connection.generation);
    await assert.rejects(sdk.bridgeFetch('/device/consent', {
      expectedConnectionGeneration: connection.generation, expectedConnectionPort: connection.port,
    }), /CHANGED/);
    await assert.rejects(sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: connection.generation }), /CHANGED/);
    assert.equal(calls, beforePortRejections, 'stale or mismatched port must be rejected before dispatch');
  } finally { env.DSHA_RECOVERY_BROKER_PORT = originalPort; }
  heldReply = { entered: Promise.withResolvers(), release: Promise.withResolvers() };
  const pending = query();
  const rejected = assert.rejects(pending, /CHANGED/);
  await heldReply.entered.promise;
  env.DSHA_RECOVERY_BROKER_PORT = changedPort;
  try { heldReply.release.resolve(); await rejected; }
  finally { env.DSHA_RECOVERY_BROKER_PORT = originalPort; heldReply = undefined; }
  assert.equal(calls, beforePortRejections + 1, 'in-flight port change must not replay the query');
  await assert.rejects(sdk.bridgeFetch('/app/execute', {}), /QUERY_ONLY/);
  await assert.rejects(sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: 'old' }), /CHANGED/);
  bypass = true; await assert.rejects(query(), /REPLY/); bypass = false;
  stopped = true; assert.throws(() => sdk.readConnection(), /STOPPED/); stopped = false;
  const previous = sdk.readConnection(); env.DSHA_RECOVERY_BROKER_TOKEN = 'b'.repeat(64);
  await assert.rejects(sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: previous.generation }), /CHANGED/);
  env.DSHA_RECOVERY_BROKER_TOKEN = token;

  // Run the existing five-tool/guard/profile checks through the real new DSH
  // mandatory policy, translating only the fixed guest SDK path for this host.
  const runtime = process.env.DSHA_TEST_RUNTIME;
  if (runtime) {
    const Module = require('node:module');
    const originalLoad = Module._load;
    Module._load = function (id, parent, isMain) {
      return id === '/run/dsha-launcher/native-consent.cjs' ? sdk : originalLoad.call(this, id, parent, isMain);
    };
    try { await import('./test-recovery-agent.mjs'); }
    finally { Module._load = originalLoad; }
  }
  console.log('PASS independent recovery consent: owned broker only, global=false, epoch/stop/reply fencing' + (runtime ? ', actual pinned ToolRuntime five-tool dispatch and monotonic guard' : ''));
} finally {
  await new Promise(resolve => broker.close(resolve));
}
