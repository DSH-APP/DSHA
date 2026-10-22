// Actual production adapter and HTTP request/response handling. Only private
// credential reads and the destination port are controlled; no Android device.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs', import.meta.url), 'utf8');
const token = 'owned-fixture-token', requests = [], results = [];
let active;
const server = http.createServer(async (request, response) => {
  let body = ''; for await (const part of request) body += part;
  requests.push({ method: request.method, path: request.url, token: request.headers['x-token'], body });
  if (active.mode === 'destroy') { request.socket.destroy(); return; }
  if (active.mode === 'rotate') active.token = 'rotated-fixture-token';
  if (active.mode === 'large') { response.end('x'.repeat(24 * 1024 * 1024 + 1)); return; }
  if (active.mode === 'redirect') response.writeHead(302, { Location: 'http://127.0.0.1:1/must-not-follow' });
  response.end(JSON.stringify({ result: JSON.stringify({ ok: active.mode !== 'redirect', marker: 'actual-http' }) }));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const info = directory => ({ dev: 1, ino: 1, size: active.token.length, mode: 0o100600,
  isDirectory: () => directory, isFile: () => !directory && !active.link, isSymbolicLink: () => !!active.link });
const platformFs = { constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000 },
  lstatSync: file => info(file !== '/root/.dsh/.bridge_token'), openSync: () => 7,
  fstatSync: () => info(false), readSync: (_fd, bytes) => bytes.write(active.token), closeSync: () => {} };
const transport = { request(options, accept) {
  assert.equal(options.hostname, '127.0.0.1'); assert.equal(options.port, 3090);
  return http.request({ ...options, port: server.address().port }, accept);
} };
const module = { exports: {} }, require = name => ({ 'node:fs': platformFs, 'node:http': transport })[name];
vm.runInNewContext('(function(require,module,Buffer,URL,URLSearchParams){' + source + '\n})', {})(require, module, Buffer, URL, URLSearchParams);
const adapter = module.exports, args = { coreInstance: '11111111-2222-3333-4444-555555555555', generation: 'run-1', frameSeq: 1, text: '中文\nhello' };
async function test(name, body) { active = { token, mode: 'success' }; const before = requests.length;
  try { await body(before); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
try {
  await test('production GET uses X-Token and bounded response decoding', async before => {
    const reply = await adapter.call('android_vscreen_status', {}, new AbortController().signal);
    assert.equal(reply.isError, false); assert.equal(JSON.parse(reply.content[0].text).marker, 'actual-http');
    assert.equal(requests.length, before + 1); assert.equal(requests[before].method, 'GET');
    assert.equal(requests[before].path, '/app/vscreen/status'); assert.equal(requests[before].token, token);
  });
  await test('production POST retains Unicode JSON and current observation evidence', async before => {
    const reply = await adapter.call('android_vscreen_type', args, new AbortController().signal);
    assert.equal(reply.isError, false); assert.equal(requests.length, before + 1);
    assert.equal(requests[before].method, 'POST'); assert.equal(requests[before].path, '/app/vscreen/type');
    assert.deepEqual(JSON.parse(requests[before].body), args); assert.equal(requests[before].token, token);
  });
  await test('cancelled before dispatch sends no request', async before => {
    const controller = new AbortController(); controller.abort();
    await assert.rejects(adapter.call('android_vscreen_status', {}, controller.signal), error => error.executionUnknown === false);
    assert.equal(requests.length, before);
  });
  await test('unsafe private token sends no request', async before => {
    active.link = true;
    await assert.rejects(adapter.call('android_vscreen_status', {}, new AbortController().signal), error => error.executionUnknown === false);
    assert.equal(requests.length, before);
  });
  for (const mode of ['destroy', 'rotate', 'large']) await test(mode + ' after one POST is unknown and never replayed', async before => {
    active.mode = mode; const reply = await adapter.call('android_vscreen_type', args, new AbortController().signal);
    const value = JSON.parse(reply.content[0].text); assert.equal(reply.isError, true);
    assert.equal(value.error, 'INPUT_RESULT_UNKNOWN'); assert.equal(value.executionUnknown, true);
    assert.equal(requests.length, before + 1);
  });
  await test('redirect is not followed', async before => {
    active.mode = 'redirect'; const reply = await adapter.call('android_vscreen_status', {}, new AbortController().signal);
    assert.equal(reply.isError, true); assert.equal(requests.length, before + 1);
  });
} finally { await new Promise(resolve => server.close(resolve)); }
console.log(JSON.stringify({ scope: 'REAL_PRODUCTION_VSCREEN_ADAPTER_REAL_LOOPBACK_HTTP_CONTROLLED_CREDENTIAL_AND_PORT',
  androidDevice: false, deviceCredentialRead: false, cases: results.length,
  passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
