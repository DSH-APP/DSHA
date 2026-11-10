// 真实插件与解析模块，全部 fs/HTTP 由夹具提供；不访问本机桥或凭据。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
const assets = new URL('../app/src/main/assets/', import.meta.url);
const plugins = ['dsh-computer-use-android', 'dsh-tool-vscreen', 'dsh-device-shell-guide', 'dsh-status-overlay', 'dsh-task-notifier', 'dsh-launch-target'];
const helperPath = 'builtin-plugins/dsh-device-shell-guide/lib/bridge-port.cjs';
const read = name => fs.readFileSync(new URL(name, assets), 'utf8');
const results = [];
async function test(name, body) { try { await body(); results.push({ name, status: 'PASS' }); } catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); } }
const settle = () => new Promise(setImmediate);
export function fixture() {
  const f = { port: Buffer.from('3091'), env: { DSHA_BRIDGE_PORT: '3097' }, token: 'fixture-token',
    fields: { dev: 1, ino: 2, size: 4, mode: 0o100600, mtimeMs: 1, ctimeMs: 1 },
    reads: 0, opens: 0, closes: 0, requests: [], status: 200, body: '{"result":"OK"}' };
  const info = () => ({ ...f.fields, isFile: () => (f.fields.mode & 0o170000) === 0o100000, isSymbolicLink: () => (f.fields.mode & 0o170000) === 0o120000 });
  const tokenInfo = () => ({ dev: 1, ino: 3, size: f.token.length, mode: 0o100600, isFile: () => true, isSymbolicLink: () => false });
  const tokenPath = '/root/.dsh/.bridge_token', portPath = '/root/.dsh/.bridge_port';
  f.fs = {
    constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000, O_NONBLOCK: 0x800 },
    lstatSync(path) {
      if (path === tokenPath) return tokenInfo();
      if (path === '/root' || path === '/root/.dsh') return { isDirectory: () => true, isSymbolicLink: () => false };
      assert.equal(path, portPath);
      if (f.missing) throw Object.assign(Error('fixture missing'), { code: 'ENOENT' });
      return info();
    },
    openSync(path, flags) {
      if (path === tokenPath) { assert([0x20000, 0x20800].includes(flags)); return 7; }
      assert.equal(path, portPath); assert.equal(flags, 0x20800); f.opens++; f.onOpen?.(); return 8;
    },
    fstatSync(fd) { assert([7, 8].includes(fd)); return fd === 7 ? tokenInfo() : info(); },
    readSync(fd, bytes, offset, length, position) {
      if (fd === 7) return bytes.write(f.token);
      assert.equal(fd, 8); assert.equal(bytes.length, 6); assert.equal(length, 6); assert.equal(offset, 0); assert.equal(position, 0);
      f.reads++; const size = f.port.copy(bytes, 0, 0, 6); f.onRead?.(); return size;
    },
    closeSync(fd) { assert([7, 8].includes(fd)); if (fd === 8) f.closes++; },
    readFileSync(path) { assert.equal(path, tokenPath, 'port reads must stay bounded'); return f.token; },
  };
  f.asyncFs = { async readFile(path) { return f.fs.readFileSync(path); } };
  f.fetch = async (url, init) => {
    url = new URL(url); f.requests.push({ port: Number(url.port), path: url.pathname, query: url.search, ...init });
    assert.equal(url.hostname, '127.0.0.1'); assert.equal(init.headers['X-Token'], f.token); assert(!url.href.includes(f.token));
    f.onResponse?.(); if (f.networkError) throw Error('fixture transport failure');
    return new Response(f.body, { status: f.status });
  };
  f.http = { request(options, accept) {
    f.requests.push(options); assert.equal(options.hostname, '127.0.0.1'); assert.equal(options.headers['X-Token'], f.token);
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => {
      if (f.networkError) { request.emit('error', Error('fixture network error')); return; }
      f.onResponse?.(); const response = new EventEmitter(); response.statusCode = f.status;
      response.destroy = error => response.emit('error', error); accept(response);
      response.emit('data', Buffer.from(f.body)); response.emit('end');
    }); return request;
  } };
  f.setPort = raw => { f.port = Buffer.isBuffer(raw) ? raw : Buffer.from(raw); f.fields.size = f.port.length; };
  f.context = vm.createContext({ Buffer, URL, URLSearchParams, AbortSignal, AbortController, Response, queueMicrotask,
    process: { env: f.env }, fetch: f.fetch, setTimeout, clearTimeout, setInterval, clearInterval });
  return f;
}
export function loadCjs(name, f) {
  const module = { exports: {} };
  const require = dependency => {
    if (dependency === './bridge-port.cjs') return loadCjs(name.slice(0, name.lastIndexOf('/') + 1) + 'bridge-port.cjs', f);
    const known = { 'node:fs': f.fs, 'node:fs/promises': f.asyncFs, 'node:http': f.http, 'node:crypto': crypto };
    assert(Object.hasOwn(known, dependency), dependency); return known[dependency];
  };
  vm.runInContext('(function(require,module){' + read(name) + '\n})', f.context)(require, module);
  return module.exports;
}
async function loadPlugin(name, f) {
  const module = new vm.SourceTextModule(read('builtin-plugins/' + name + '/lib/index.js'), { context: f.context });
  await module.link(async dependency => {
    if (dependency === './session-adapter.js')
      return new vm.SourceTextModule(read('builtin-plugins/' + name + '/lib/session-adapter.js'), { context: f.context });
    const value = dependency === './bridge-port.cjs' ? { default: loadCjs('builtin-plugins/' + name + '/lib/bridge-port.cjs', f) }
      : dependency === 'node:fs' ? f.fs : dependency === 'node:fs/promises' ? f.asyncFs
      : dependency === 'node:crypto' ? crypto
      : dependency === '@deepseek-ai/dsh-llm' ? { createUserMessage: message => ({ ...message, role: 'user' }) } : undefined;
    assert(value, dependency);
    return new vm.SyntheticModule(Object.keys(value), function () { for (const [key, entry] of Object.entries(value)) this.setExport(key, entry); }, { context: f.context });
  });
  await module.evaluate(); return module.namespace;
}
function context() {
  const events = new Map();
  return { events, on(name, fn) { const rows = events.get(name) ?? []; rows.push(fn); events.set(name, rows); },
    // No optional Host services exist in this bridge-consumer fixture, so
    // Cordis inject callbacks remain pending. Load the real adapter module;
    // its own pinned-runtime behavior is covered by test-local-session-adapter.
    inject(names, callback) { assert(Array.isArray(names)); assert.equal(typeof callback, 'function'); },
    emit(name, ...args) { return (events.get(name) ?? []).map(fn => fn(...args)); } };
}
async function run() {
  await test('all independently deployable plugin helpers are byte-identical', () => {
    for (const name of plugins) assert.equal(read('builtin-plugins/' + name + '/lib/bridge-port.cjs'), read('bridge-port.cjs'), name);
  });
  await test('deployment places helpers beside each declared plugin consumer', () => {
    const installs = JSON.parse(read('managed-runtime-inputs.json')).installs;
    const registry = JSON.parse(read('builtin-plugins.json')).plugins;
    for (const name of plugins) {
      const root = registry.find(row => row.name === name).guestDirectory.replace(/^\//, '');
      const helper = installs.filter(row => row.asset === 'builtin-plugins/' + name + '/lib/bridge-port.cjs');
      assert.equal(helper.length, 1, name);
      assert.equal(helper[0].target, root + '/lib/bridge-port.cjs', name);
      assert.equal(helper[0].executable, false, name);
    }
    assert(installs.some(row => row.asset === 'bridge-port.cjs' && row.target === 'root/.dsh/bridge-port.cjs' && row.executable === false));
    const deployment = JSON.parse(fs.readFileSync(new URL('./asset-deployment.json', import.meta.url), 'utf8'));
    assert(deployment.packagedFiles.includes('bridge-port.cjs'));
  });
  await test('strict decimal file/environment precedence and live rereads', () => {
    const f = fixture(), port = loadCjs(helperPath, f);
    assert.equal(port(), 3091); f.setPort('3092'); assert.equal(port(), 3092);
    f.missing = true; assert.equal(port(), 3097); f.env.DSHA_BRIDGE_PORT = '3098'; assert.equal(port(), 3098);
    for (const [raw, expected] of [['1', 1], ['65535', 65535], ['03091', 3091]]) {
      f.env.DSHA_BRIDGE_PORT = raw; assert.equal(port(), expected, raw.slice(0, 20));
    }
    // 文件与环境都无效时不回退任何固定端口（3090 可能属于同机另一个 DSHA 安装）。
    for (const raw of ['', '0', '65536', '0xc13', '0b110000010011', '3e3', '+3091', '3091.0', ' 3091', '3091\n', '٣٠٩١', '²', '9'.repeat(100000)]) {
      f.env.DSHA_BRIDGE_PORT = raw; assert.throws(() => port(), { code: 'BRIDGE_PORT_UNAVAILABLE' }, raw.slice(0, 20));
    }
    delete f.env.DSHA_BRIDGE_PORT; assert.throws(() => port(), { code: 'BRIDGE_PORT_UNAVAILABLE' });
  });
  await test('unpublished port sends no credential from any consumer', async () => {
    const unpublished = () => { const f = fixture(); f.missing = true; delete f.env.DSHA_BRIDGE_PORT; return f; };
    const signal = new AbortController().signal, task = { sessionId: 'fixture-full-session', turnId: '1' };
    { const f = unpublished(), sdk = loadCjs('builtin-plugins/dsh-computer-use-android/lib/server.cjs', f);
      await assert.rejects(sdk.call('android_get_state', {}, signal, undefined, task), /BRIDGE_PORT_UNAVAILABLE/); assert.equal(f.requests.length, 0); }
    { const f = unpublished(), sdk = loadCjs('builtin-plugins/dsh-tool-vscreen/lib/server.cjs', f);
      // 未发送的失败按 vscreen 约定抛出，写操作也不能被报成 UNKNOWN。
      await assert.rejects(sdk.call('android_vscreen_status', {}, signal), error => error.message === 'BRIDGE_PORT_UNAVAILABLE' && error.executionUnknown === false);
      const args = { coreInstance: '11111111-2222-3333-4444-555555555555', generation: 'fixture', frameSeq: 1, text: 'fixture' };
      await assert.rejects(sdk.call('android_vscreen_type', args, signal), error => error.executionUnknown === false);
      assert.equal(f.requests.length, 0); }
    { const f = unpublished(), sdk = loadCjs('native-consent.cjs', f); assert.throws(() => sdk.readConnection(), { code: 'BRIDGE_PORT_UNAVAILABLE' });
      await assert.rejects(sdk.bridgeFetch('/app/native-policy', { expectedConnectionGeneration: 'x' }), { code: 'BRIDGE_PORT_UNAVAILABLE' }); assert.equal(f.requests.length, 0); }
    { const f = unpublished(), plugin = await loadPlugin('dsh-task-notifier', f), reporter = new plugin.TaskReporter({}, undefined, undefined, {});
      await assert.rejects(reporter.transport('claim', {})); assert.equal(f.requests.length, 0); reporter.dispose(); }
    { const f = unpublished(); f.context.setTimeout = () => ({ unref() {} }); f.context.clearTimeout = () => {};
      const plugin = await loadPlugin('dsh-status-overlay', f), ctx = context(); plugin.apply(ctx);
      ctx.emit('session/event', { id: 'fixture-session' }, { type: 'tool/call', data: { name: 'read' } }); await settle(); assert.equal(f.requests.length, 0); }
    { const f = unpublished(), plugin = await loadPlugin('dsh-device-shell-guide', f), text = plugin.promptText('zh');
      assert(!/127\.0\.0\.1:30\d\d/.test(text), 'guide must not advertise a guessed bridge port');
      assert(text.includes('127.0.0.1:<PORT>/app/help')); assert(text.includes('BRIDGE_PORT') || text.includes('.bridge_port')); }
  });
  await test('file rejects high-bit bytes, whitespace, excessive size and unsafe types', () => {
    for (const raw of ['', '0', '65536', '309100', '3091\n', ' 3091', '0xc13', '3e3', Buffer.from([0xb3, 0xb0, 0xb9, 0xb1])]) {
      const f = fixture(); f.setPort(raw); assert.equal(loadCjs(helperPath, f)(), 3097); assert.equal(f.opens, f.closes);
    }
    for (const mode of [0o100644, 0o120600, 0o010600, 0o040600]) {
      const f = fixture(); f.fields.mode = mode; assert.equal(loadCjs(helperPath, f)(), 3097); assert.equal(f.opens, 0);
    }
  });
  await test('open/read races, growth and short reads cannot escape the six-byte bound', () => {
    for (const phase of ['onOpen', 'onRead']) for (const [key, value] of [['dev', 2], ['ino', 3], ['mode', 0o100644], ['mode', 0o010600], ['size', 5], ['mtimeMs', 2], ['ctimeMs', 2]]) {
      const f = fixture(); f[phase] = () => { f.fields[key] = value; };
      assert.equal(loadCjs(helperPath, f)(), 3097, phase + key); assert.equal(f.closes, 1); assert.equal(f.reads, phase === 'onRead' ? 1 : 0);
    }
    for (const raw of ['309', '3091000000']) { const f = fixture(); f.port = Buffer.from(raw); assert.equal(loadCjs(helperPath, f)(), 3097); assert.equal(f.closes, 1); }
    const f = fixture(); f.onRead = () => { f.missing = true; }; assert.equal(loadCjs(helperPath, f)(), 3097);
    for (const key of ['lstatSync', 'openSync', 'fstatSync', 'readSync', 'closeSync']) { const f = fixture(); f.fs[key] = () => { throw Error('fixture IO'); }; assert.equal(loadCjs(helperPath, f)(), 3097); }
  });
  await test('guide follows current section.text(context) API and rereads on every assembly', async () => {
    const f = fixture(), plugin = await loadPlugin('dsh-device-shell-guide', f), ctx = context();
    let section, dispose;
    ctx.inject = (_names, callback) => callback({ systemPrompt: { section(value) { section = value; } }, effect(fn) { dispose = fn(); } });
    plugin.apply(ctx); assert.equal(f.opens, 0); assert.equal(typeof section.text, 'function');
    for (const port of ['3091', '3092']) { f.setPort(port); const text = section.text({}); assert(text.includes('127.0.0.1:' + port + '/app/help')); assert(text.includes('-H @/root/.dsh/.bridge_headers')); }
    const input = { agent: { session: {} }, step: 1 }, decision = { kind: 'enter', messages: [{ role: 'user' }] };
    const handler = ctx.events.get('agent/pre-step')[0]; assert.equal(await handler(input, async () => decision), decision);
    dispose(); f.setPort('3093'); const fallback = await handler(input, async () => decision);
    assert(fallback.messages[0].content[0].text.includes('127.0.0.1:3093/app/help'));
    ctx.emit('session/event', input.agent.session, { type: 'user/message', data: fallback.messages[0] });
    assert.equal(await handler(input, async () => decision), decision);
    assert(f.requests.length >= 1);
    assert(f.requests.every(request => request.path === '/app/version'), 'only the read-only language metadata may be queried');
  });
  await test('Android consumer rereads on each call and never repeats 401/legacy/UNKNOWN', async () => {
    const f = fixture(), sdk = loadCjs('builtin-plugins/dsh-computer-use-android/lib/server.cjs', f), signal = new AbortController().signal;
    const task={sessionId:'fixture-full-session',turnId:'1'};
    for (const port of ['3091', '3092']) { f.setPort(port); await sdk.call('android_get_state', {}, signal, undefined, task); assert.equal(f.requests.at(-1).port, Number(port)); }
    for (const mode of ['401', 'legacy', 'UNKNOWN', 'network']) {
      f.status = mode === '401' ? 401 : 200; f.networkError = mode === 'network';
      f.body = JSON.stringify({ result: mode === 'legacy' ? '[UNAUTHORIZED]' : JSON.stringify({ ok: false, state: 'UNKNOWN', error: 'SCREEN_RUN_CHANGED', code: 'SCREEN_RUN_CHANGED', executionUnknown: true }) });
      const before = f.requests.length, request = sdk.call('android_type', { text: 'fixture' }, signal, undefined, task);
      if (mode === '401' || mode === 'network') await assert.rejects(request); else assert.equal((await request).isError, true);
      assert.equal(f.requests.length, before + 1);
    }
  });
  await test('VScreen consumer rereads and retains UNKNOWN after port change without replay', async () => {
    const f = fixture(), sdk = loadCjs('builtin-plugins/dsh-tool-vscreen/lib/server.cjs', f), signal = new AbortController().signal;
    f.body = JSON.stringify({ result: JSON.stringify({ ok: true }) });
    for (const port of ['3091', '3092']) { f.setPort(port); assert.equal((await sdk.call('android_vscreen_status', {}, signal)).isError, false); assert.equal(f.requests.at(-1).port, Number(port)); }
    const args = { coreInstance: '11111111-2222-3333-4444-555555555555', generation: 'fixture', frameSeq: 1, text: 'fixture' };
    for (const mode of ['401', 'legacy', 'rotate', 'network']) {
      f.status = mode === '401' ? 401 : 200; f.body = mode === 'legacy' ? '{"result":"[UNAUTHORIZED]"}' : '{"ok":false,"error":"REJECTED"}';
      f.networkError = mode === 'network'; f.onResponse = mode === 'rotate' ? () => f.setPort('3093') : undefined;
      const before = f.requests.length, reply = await sdk.call('android_vscreen_type', args, signal);
      assert.equal(reply.isError, true); assert.equal(f.requests.length, before + 1);
      if (mode === 'rotate' || mode === 'network') assert.equal(JSON.parse(reply.content[0].text).executionUnknown, true);
    }
  });
  await test('VScreen self-launch refusal stays NOT_SENT and never turns into UNKNOWN', async () => {
    // 宿主自身被原生端在 am start 之前拒绝；MCP 侧必须报未发送，不能暗示动作可能执行过。
    const f = fixture(), sdk = loadCjs('builtin-plugins/dsh-tool-vscreen/lib/server.cjs', f), signal = new AbortController().signal;
    f.body = JSON.stringify({ result: JSON.stringify({ ok: false, state: 'NOT_SENT', error: 'VSCREEN_SELF_LAUNCH_REFUSED', code: 'VSCREEN_SELF_LAUNCH_REFUSED' }) });
    const reply = await sdk.call('android_vscreen_launch', { coreInstance: '11111111-2222-3333-4444-555555555555', package: 'io.github.sunsetrne.dsha' }, signal);
    const value = JSON.parse(reply.content[0].text);
    assert.equal(reply.isError, true);
    assert.equal(value.state, 'NOT_SENT');
    assert.equal(value.error, 'VSCREEN_SELF_LAUNCH_REFUSED');
    assert.equal(value.executionUnknown, undefined);
    assert.equal(value.noReplay, undefined);
    assert.equal(f.requests.length, 1);
    assert(f.requests[0].path.startsWith('/app/vscreen/launch'));
    assert(JSON.stringify(f.requests[0]).includes('io.github.sunsetrne.dsha'));
  });
  await test('overlay state dedup includes port and never caches unauthorized success', async () => {
    const f = fixture(), plugin = await loadPlugin('dsh-status-overlay', f), ctx = context(); let next;
    plugin.reportPluginStates(ctx, { token: () => f.token, fetch: f.fetch, setTimeout: fn => { next = fn; }, clearTimeout: () => {} });
    await next(); assert.equal(f.requests.length, 1); await next(); assert.equal(f.requests.length, 1);
    f.setPort('3092'); await next(); assert.equal(f.requests.length, 2); assert.equal(f.requests.at(-1).port, 3092);
    for (const mode of ['401', 'legacy']) {
      f.setPort(mode === '401' ? '3093' : '3094'); f.status = mode === '401' ? 401 : 200; f.body = mode === 'legacy' ? '{"result":"[UNAUTHORIZED]"}' : '{"result":"OK"}';
      const before = f.requests.length; await next(); assert.equal(f.requests.length, before + 1);
      f.status = 200; f.body = '{"result":"OK"}'; await next(); assert.equal(f.requests.length, before + 2);
    }
    ctx.emit('dispose');
  });
  await test('overlay content rereads port for each fresh event', async () => {
    const f = fixture(); f.context.setTimeout = () => ({ unref() {} }); f.context.clearTimeout = () => {};
    const plugin = await loadPlugin('dsh-status-overlay', f), ctx = context(); plugin.apply(ctx);
    for (const port of ['3091', '3092']) { f.setPort(port); ctx.emit('session/event', { id: 'fixture-session' }, { type: 'tool/call', data: { name: 'read' } }); await settle(); assert.equal(f.requests.at(-1).port, Number(port)); }
    assert.equal(f.requests.length, 2);
  });
  await test('task notifier rereads all four operations and rejects 401/legacy without replay', async () => {
    const f = fixture(), plugin = await loadPlugin('dsh-task-notifier', f), reporter = new plugin.TaskReporter({}, undefined, undefined, {});
    for (const port of ['3091', '3092']) for (const operation of ['context', 'event', 'claim', 'ack']) {
      f.setPort(port); await reporter.transport(operation, {}); assert.equal(f.requests.at(-1).port, Number(port)); assert.equal(f.requests.at(-1).path, '/app/tasks/' + operation);
    }
    for (const mode of ['401', 'legacy', 'network']) {
      f.status = mode === '401' ? 401 : 200; f.body = mode === 'legacy' ? '{"result":"[UNAUTHORIZED]"}' : '{"result":"OK"}'; f.networkError = mode === 'network';
      const before = f.requests.length; await assert.rejects(reporter.transport('claim', {})); assert.equal(f.requests.length, before + 1);
    }
    reporter.dispose();
  });
  console.log(JSON.stringify({ scope: 'REAL_PLUGIN_CONSUMERS_CONTROLLED_FS_HTTP', networkRequested: false, deviceCredentialRead: false, cases: results.length, passed: results.filter(r => r.status === 'PASS').length, results }, null, 2));
  if (results.some(r => r.status !== 'PASS')) process.exitCode = 1;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await run();
