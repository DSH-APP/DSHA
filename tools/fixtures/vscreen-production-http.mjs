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
  if (active.mode === 'launch-destroy' && request.url.startsWith('/app/vscreen/launch?')) { request.socket.destroy(); return; }
  if (active.mode === 'destroy') { request.socket.destroy(); return; }
  if (active.mode === 'rotate') active.token = 'rotated-fixture-token';
  if (active.mode === 'large') { response.end('x'.repeat(24 * 1024 * 1024 + 1)); return; }
  if (active.mode === 'redirect') response.writeHead(302, { Location: 'http://127.0.0.1:1/must-not-follow' });
  const value = { ok: active.mode !== 'redirect', marker: 'actual-http' };
  if (active.launchError && request.url.startsWith('/app/vscreen/launch?')) {
    value.ok = false; value.state = 'REJECTED'; value.error = active.launchError;
  }
  if (request.url === '/app/vscreen/status') {
    value.coreInstance = '11111111-2222-3333-4444-555555555555';
    value.generation = active.statusGeneration || 'run-1';
  }
  response.end(JSON.stringify({ result: JSON.stringify(value) }));
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
const portModule = { exports: {} };
const portSource = fs.readFileSync(new URL('../../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/bridge-port.cjs', import.meta.url), 'utf8');
// 夹具显式发布端口（环境值），不依赖「读不到即 3090」——生产已不再回退固定端口。
vm.runInNewContext('(function(require,module,Buffer){' + portSource + '\n})', { process: { env: { DSHA_BRIDGE_PORT: '3090' } } })(name => {
  assert.equal(name, 'node:fs'); return platformFs;
}, portModule, Buffer);
const module = { exports: {} }, require = name => ({ 'node:fs': platformFs, 'node:http': transport, './bridge-port.cjs': portModule.exports })[name];
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
  await test('launch without generation sends one old-protocol request', async before => {
    const reply = await adapter.call('android_vscreen_launch', { coreInstance: args.coreInstance, package: 'com.example.app' }, new AbortController().signal);
    assert.equal(reply.isError, false); assert.equal(requests.length, before + 1);
    const url = new URL(requests[before].path, 'http://127.0.0.1');
    assert.equal(url.pathname, '/app/vscreen/launch');
    assert.deepEqual([...url.searchParams], [['coreInstance', args.coreInstance], ['package', 'com.example.app']]);
    assert.equal(requests[before].method, 'GET'); assert.equal(requests[before].token, token);
    const value = JSON.parse(reply.content[0].text);
    assert.equal(value.generationCheckedBeforeLaunch, false); assert.equal(value.generationEnforcedAtLaunch, false);
  });
  await test('launch with generation checks real status then sends only old-protocol fields', async before => {
    const reply = await adapter.call('android_vscreen_launch', { coreInstance: args.coreInstance, generation: 'run-1', package: 'com.example.app' }, new AbortController().signal);
    assert.equal(reply.isError, false); assert.equal(requests.length, before + 2);
    assert.equal(requests[before].path, '/app/vscreen/status'); assert.equal(requests[before].token, token);
    const url = new URL(requests[before + 1].path, 'http://127.0.0.1');
    assert.equal(url.pathname, '/app/vscreen/launch');
    assert.deepEqual([...url.searchParams].sort(), [['coreInstance', args.coreInstance], ['package', 'com.example.app']]);
    assert.equal(requests[before + 1].token, token);
    const value = JSON.parse(reply.content[0].text);
    assert.equal(value.generationCheckedBeforeLaunch, true); assert.equal(value.generationEnforcedAtLaunch, false);
  });
  await test('stale generation sends status but no launch', async before => {
    active.statusGeneration = 'run-2';
    const reply = await adapter.call('android_vscreen_launch', { coreInstance: args.coreInstance, generation: 'run-1', package: 'com.example.app' }, new AbortController().signal);
    assert.equal(reply.isError, true); assert.equal(requests.length, before + 1);
    assert.equal(requests[before].path, '/app/vscreen/status');
    const value = JSON.parse(reply.content[0].text);
    assert.equal(value.state, 'NOT_SENT'); assert.equal(value.error, 'VSCREEN_GENERATION_CHANGED');
  });
  await test('lost launch reply after status sends once and is UNKNOWN without replay', async before => {
    active.mode = 'launch-destroy';
    const reply = await adapter.call('android_vscreen_launch', { coreInstance: args.coreInstance, generation: 'run-1', package: 'com.example.app' }, new AbortController().signal);
    assert.equal(reply.isError, true); assert.equal(requests.length, before + 2);
    assert.equal(requests[before].path, '/app/vscreen/status'); assert.match(requests[before + 1].path, /^\/app\/vscreen\/launch\?/);
    const value = JSON.parse(reply.content[0].text);
    assert.equal(value.state, 'UNKNOWN'); assert.equal(value.error, 'LAUNCH_RESULT_UNKNOWN');
    assert.equal(value.executionUnknown, true); assert.equal(value.noReplay, true);
    assert.equal(value.generationCheckedBeforeLaunch, true); assert.equal(value.generationEnforcedAtLaunch, false);
  });
  await test('legacy App transport failure after launch is UNKNOWN without replay', async before => {
    active.launchError = 'VSCREEN_RESPONSE_LIMIT';
    const reply = await adapter.call('android_vscreen_launch', { coreInstance: args.coreInstance, generation: 'run-1', package: 'com.example.app' }, new AbortController().signal);
    assert.equal(reply.isError, true); assert.equal(requests.length, before + 2);
    assert.equal(requests[before].path, '/app/vscreen/status'); assert.match(requests[before + 1].path, /^\/app\/vscreen\/launch\?/);
    const value = JSON.parse(reply.content[0].text);
    assert.equal(value.state, 'UNKNOWN'); assert.equal(value.error, 'LAUNCH_RESULT_UNKNOWN'); assert.equal(value.detail, 'VSCREEN_RESPONSE_LIMIT');
    assert.equal(value.executionUnknown, true); assert.equal(value.noReplay, true);
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
