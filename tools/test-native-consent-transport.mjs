// Actual production launcher query with controlled platform fs/http adapters.
// No token from this computer is read and no network request is made.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
const source = fs.readFileSync(new URL('../app/src/main/assets/native-consent.cjs', import.meta.url), 'utf8');
const results = [];
function fixture() {
  const f = { token: 'a'.repeat(43), mode: 0o100600, ino: 1, requests: [], closes: 0,
    body: '{"result":"native-payload"}', status: 200 };
  const info = directory => ({ dev: 1, ino: f.ino, size: f.token.length, mode: f.mode,
    isDirectory: () => directory && !f.parentLink, isFile: () => !directory && !f.fileLink,
    isSymbolicLink: () => directory ? !!f.parentLink : !!f.fileLink });
  const platformFs = {
    constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000 },
    lstatSync(path) { assert(['/root', '/root/.dsh', '/root/.dsh/.bridge_token'].includes(path)); return info(path !== '/root/.dsh/.bridge_token'); },
    openSync(path, flags) { assert.equal(path, '/root/.dsh/.bridge_token'); assert.equal(flags, 0x20000); if (f.onOpen) f.onOpen(); return 7; },
    fstatSync(fd) { assert.equal(fd, 7); return info(false); },
    readSync(fd, bytes) { assert.equal(fd, 7); return bytes.write(f.token); },
    closeSync(fd) { assert.equal(fd, 7); f.closes++; },
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
  const require = name => ({ 'node:fs': platformFs, 'node:http': http, 'node:crypto': crypto })[name];
  vm.runInNewContext('(function(require,module,Buffer){' + source + '\n})', {})(require, module, Buffer);
  f.sdk = module.exports;
  f.query = signal => f.sdk.bridgeFetch('/device/consent', { expectedConnectionGeneration: f.sdk.readConnection().generation, signal });
  return f;
}
async function test(name, body) { try { await body(); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
await test('fixed authenticated endpoint returns only native data and a credential generation', async () => {
  const f = fixture(), descriptor = f.sdk.readConnection();
  assert.equal(descriptor.applicationId, 'dsh.android.client'); assert.deepEqual(Object.keys(descriptor).sort(), ['applicationId', 'generation']);
  const response = await f.query(); assert.equal(response.ok, true); assert.equal((await response.json()).result, 'native-payload');
  const wire = f.requests[0]; assert.equal(wire.hostname, '127.0.0.1'); assert.equal(wire.port, 3090); assert.equal(wire.path, '/app/native-policy');
  assert.equal(wire.method, 'GET'); assert.equal(wire.headers['X-Token'], f.token); assert.equal(wire.agent, false); assert(f.closes >= 4);
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
console.log(JSON.stringify({ scope: 'ACTUAL_LAUNCHER_QUERY_CONTROLLED_PLATFORM_IO', networkRequested: false,
  deviceCredentialRead: false, sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
  cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
