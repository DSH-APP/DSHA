// Actual production launcher query with controlled platform fs/http adapters.
// No token from this computer is read and no network request is made.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { fixture as portFixture, loadCjs } from './test-bridge-port.mjs';
const source = fs.readFileSync(new URL('../app/src/main/assets/native-consent.cjs', import.meta.url), 'utf8');
const results = [];
function fixture() {
  const f = { token: 'a'.repeat(43), mode: 0o100600, ino: 1, port: 3090, portMode: 0o100600,
    portIno: 2, requests: [], closes: 0, body: '{"result":"native-payload"}', status: 200 };
  const info = directory => ({ dev: 1, ino: f.ino, size: f.token.length, mode: f.mode,
    isDirectory: () => directory && !f.parentLink, isFile: () => !directory && !f.fileLink,
    isSymbolicLink: () => directory ? !!f.parentLink : !!f.fileLink });
  const portInfo = () => ({ dev: 2, ino: f.portIno, size: String(f.port).length, mode: f.portMode,
    isDirectory: () => false, isFile: () => !f.portLink, isSymbolicLink: () => !!f.portLink });
  const missing = () => Object.assign(Error('ENOENT'), { code: 'ENOENT' });
  const platformFs = {
    constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000 },
    lstatSync(path) {
      assert(['/root', '/root/.dsh', '/root/.dsh/.bridge_token', '/root/.dsh/.bridge_port'].includes(path));
      if (path === '/root/.dsh/.bridge_port') {
        if (f.port === undefined) throw missing();
        return portInfo();
      }
      return info(path !== '/root/.dsh/.bridge_token');
    },
    openSync(path, flags) {
      assert.equal(flags, 0x20000);
      if (path === '/root/.dsh/.bridge_port') { assert.notEqual(f.port, undefined); return 8; }
      assert.equal(path, '/root/.dsh/.bridge_token'); if (f.onOpen) f.onOpen(); return 7;
    },
    fstatSync(fd) { if (fd === 8) return portInfo(); assert.equal(fd, 7); return info(false); },
    readSync(fd, bytes) { if (fd === 8) return bytes.write(String(f.port)); assert.equal(fd, 7); return bytes.write(f.token); },
    closeSync(fd) { assert([7, 8].includes(fd)); f.closes++; },
  };
  const http = { request(options, accept) {
    f.requests.push(options);
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => {
      if (f.onResponse) f.onResponse();
      const response = new EventEmitter(); response.statusCode = f.status;
      response.destroy = error => response.emit('error', error);
      accept(response); response.emit('data', Buffer.from(f.body)); response.emit('end');
    });
    return request;
  } };
  const module = { exports: {} };
  const portModule = { exports: {} };
  const portSource = fs.readFileSync(new URL('../app/src/main/assets/bridge-port.cjs', import.meta.url), 'utf8');
  vm.runInNewContext('(function(require,module,Buffer){' + portSource + '\n})', {})(name => {
    assert.equal(name, 'node:fs'); return platformFs;
  }, portModule, Buffer);
  const require = name => ({ 'node:fs': platformFs, 'node:http': http, 'node:crypto': crypto, './bridge-port.cjs': portModule.exports })[name];
  vm.runInNewContext('(function(require,module,Buffer){' + source + '\n})', {})(require, module, Buffer);
  f.sdk = module.exports;
  f.query = signal => {
    const connection = f.sdk.readConnection();
    return f.sdk.bridgeFetch('/device/consent', {
      expectedConnectionGeneration: connection.generation,
      expectedConnectionPort: connection.port,
      signal,
    });
  };
  return f;
}
async function test(name, body) { try { await body(); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
await test('fixed authenticated endpoint returns only native data and a credential generation', async () => {
  const f = fixture(), descriptor = f.sdk.readConnection();
  assert.equal(descriptor.applicationId, 'io.github.sunsetrne.dsha'); assert.deepEqual(Object.keys(descriptor).sort(), ['applicationId', 'generation', 'port']);
  assert.equal(descriptor.port, 3090);
  const response = await f.query(); assert.equal(response.ok, true); assert.equal((await response.json()).result, 'native-payload');
  const wire = f.requests[0]; assert.equal(wire.hostname, '127.0.0.1'); assert.equal(wire.port, 3090); assert.equal(wire.path, '/app/native-policy');
  assert.equal(wire.method, 'GET'); assert.equal(wire.headers['X-Token'], f.token); assert.equal(wire.agent, false); assert(f.closes >= 4);
});
await test('published fallback port is used and must stay stable', async () => {
  const f = fixture(); f.port = 3091;
  const descriptor = f.sdk.readConnection();
  assert.equal(descriptor.port, 3091);
  const response = await f.query(); assert.equal(response.ok, true);
  assert.equal(f.requests[0].port, 3091);
  await assert.rejects(
    f.sdk.bridgeFetch('/device/consent', {
      expectedConnectionGeneration: descriptor.generation,
      expectedConnectionPort: 3090,
    }), /PORT_CHANGED/);
  assert.equal(f.requests.length, 1);
});
await test('invalid or missing port file fails closed without guessing a fixed port', async () => {
  for (const arrange of [f => { f.port = 3091; f.portMode = 0o100644; }, f => { f.port = undefined; }]) {
    const f = fixture(); arrange(f);
    assert.throws(() => f.sdk.readConnection(), { code: 'BRIDGE_PORT_UNAVAILABLE' });
    await assert.rejects(f.sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: 'x' }), { code: 'BRIDGE_PORT_UNAVAILABLE' });
    assert.equal(f.requests.length, 0);
  }
});
for (const [name, arrange] of [
  ['parent symlink', f => { f.parentLink = true; }], ['token symlink', f => { f.fileLink = true; }],
  ['shared token permissions', f => { f.mode = 0o100644; }], ['oversized credential', f => { f.token = 'a'.repeat(130); }],
  ['non-token bytes', f => { f.token = 'a/b'; }], ['inode changed between lstat and open', f => { f.onOpen = () => { f.ino++; }; }],
]) await test(name + ' rejects before a request', async () => {
  const f = fixture(); arrange(f); await assert.rejects(async () => f.query(), /NATIVE_CONSENT/); assert.equal(f.requests.length, 0);
});
await test('caller cannot substitute endpoint or request headers', async () => {
  const f = fixture(); await assert.rejects(f.sdk.bridgeFetch('/app/exec'), /QUERY_ONLY/);
  await assert.rejects(f.sdk.bridgeFetch('/device/consent', { headers: { 'X-Token': 'chosen' } }), /QUERY_ONLY/); assert.equal(f.requests.length, 0);
});
await test('pre-send cancellation does not open credential or network', async () => {
  const f = fixture(), controller = new AbortController(); controller.abort(Error('cancelled'));
  await assert.rejects(f.sdk.bridgeFetch('/device/consent', { signal: controller.signal }), /cancelled/);
  assert.equal(f.closes, 0); assert.equal(f.requests.length, 0);
});
await test('credential rotation rejects an already returned response', async () => {
  const f = fixture(); f.onResponse = () => { f.token = 'b'.repeat(43); };
  await assert.rejects(f.query(), /TOKEN_CHANGED/); assert.equal(f.requests.length, 1);
});
await test('redirect is returned as failure and is never followed', async () => {
  const f = fixture(); f.status = 302; const reply = await f.query(); assert.equal(reply.ok, false); assert.equal(f.requests.length, 1);
});
await test('response bytes are bounded before JSON decode', async () => {
  const f = fixture(); f.body = 'x'.repeat(8193); await assert.rejects(f.query(), /REPLY_LIMIT/);
});
await test('old credential generation cannot send', async () => {
  const f = fixture(), previous = f.sdk.readConnection(); f.token = 'b'.repeat(43);
  await assert.rejects(f.sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: previous.generation }), /TOKEN_CHANGED/);
  assert.equal(f.requests.length, 0);
});
await test('launcher and plugins load the same deployable parser', () => {
  assert(source.includes("const bridgePort = require('./bridge-port.cjs');"));
  assert.equal(fs.readFileSync(new URL('../app/src/main/assets/bridge-port.cjs', import.meta.url), 'utf8'),
    fs.readFileSync(new URL('../app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/bridge-port.cjs', import.meta.url), 'utf8'));
});
await test('descriptor follows the actual installed application identity without reading another token', () => {
  const f = portFixture(), sdk = loadCjs('native-consent.cjs', f);
  const original = sdk.readConnection();
  assert.equal(original.applicationId, 'io.github.sunsetrne.dsha');
  f.env.DSHA_BRIDGE_APPLICATION_ID = 'io.github.sunsetrne.dsha.test';
  const variant = sdk.readConnection();
  assert.equal(variant.applicationId, 'io.github.sunsetrne.dsha.test');
  assert.equal(variant.generation, original.generation);
  assert.equal(f.requests.length, 0);
});
await test('launcher rereads strict file/environment ports without caching the fallback', async () => {
  const f = portFixture(), sdk = loadCjs('native-consent.cjs', f);
  for (const port of ['3091', '3092']) {
    f.setPort(port); const connection = sdk.readConnection(); assert.equal(connection.port, Number(port));
    await sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: connection.generation, expectedConnectionPort: connection.port });
    assert.equal(f.requests.at(-1).port, Number(port));
  }
  f.missing = true;
  f.env.DSHA_BRIDGE_PORT = '3097'; assert.equal(sdk.readConnection().port, 3097);
  for (const raw of ['0xc13', '3e3', '3091\n', '³', '9'.repeat(100000)]) {
    f.env.DSHA_BRIDGE_PORT = raw; assert.throws(() => sdk.readConnection(), { code: 'BRIDGE_PORT_UNAVAILABLE' });
  }
});
await test('port rotation during response rejects without replay or accepting authority', async () => {
  const f = portFixture(), sdk = loadCjs('native-consent.cjs', f), connection = sdk.readConnection();
  f.onResponse = () => f.setPort('3092');
  await assert.rejects(sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: connection.generation, expectedConnectionPort: connection.port }), /PORT_CHANGED/);
  assert.equal(f.requests.length, 1);
});
for (const status of [401, 200]) await test('transport preserves ' + (status === 401 ? '401' : 'legacy unauthorized') + ' for fail-closed policy', async () => {
  const f = portFixture(), sdk = loadCjs('native-consent.cjs', f), connection = sdk.readConnection();
  f.status = status; f.body = '{"result":"[UNAUTHORIZED]"}';
  const reply = await sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: connection.generation, expectedConnectionPort: connection.port });
  assert.equal(reply.status, status); assert.equal(reply.ok, status === 200); assert.equal((await reply.json()).result, '[UNAUTHORIZED]');
  assert.equal(f.requests.length, 1); assert.equal(f.requests[0].path, '/app/native-policy');
});
console.log(JSON.stringify({ scope: 'ACTUAL_LAUNCHER_QUERY_CONTROLLED_PLATFORM_IO', networkRequested: false,
  deviceCredentialRead: false, sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
  cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
