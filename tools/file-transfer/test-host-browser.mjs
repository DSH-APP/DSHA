// Actual rc3 CLI, authenticated WebServer gzip, signed mobile/app-integration
// and shipped file controls. Only native queue/publication replies are mocked.
// In particular, fetch and response compression are never mocked or bypassed.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { randomUUID, createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { testRuntime, requireNativeHost } from '../test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '../..');
const inputFiles = ['tools/file-transfer/test-host-browser.mjs', 'tools/file-transfer/client-controls.js', 'tools/file-transfer/file-transfer.js',
  'tools/mobile-server/compress.js', 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js',
  'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/compress.js', 'app/src/main/assets/app-integration/client.js',
  'app/src/main/assets/web-integration/files.js'];
const inputSha256 = Object.fromEntries(inputFiles.map(file => [file, createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex')]));
const runtime = testRuntime('raw'); requireNativeHost(runtime);
const version = JSON.parse(fs.readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'))).version;
const output = path.resolve(process.env.DSHA_DOWNLOAD_BROWSER_OUTPUT || path.join(root, 'app/build/download-host-browser'));
fs.mkdirSync(output, { recursive: true });
const base = fs.mkdtempSync(path.join(output, 'fixture-'));
const home = path.join(base, 'home'), profile = path.join(home, 'profiles/web'), workspace = path.join(base, 'workspace');
for (const folder of [path.join(profile, 'node_modules'), path.join(workspace, 'generate/image')]) fs.mkdirSync(folder, { recursive: true });
const image = Buffer.concat([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aVZsAAAAASUVORK5CYII=', 'base64'), Buffer.alloc(2_600_000)]);
const fixtures = [
  { path: 'generate/image/image-fixture.png', bytes: image },
  { path: '任意文件 100%＋.txt', bytes: Buffer.from('real authenticated file download 中文\n') },
  { path: 'empty.bin', bytes: Buffer.alloc(0) },
];
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
for (const fixture of fixtures) fs.writeFileSync(path.join(workspace, fixture.path), fixture.bytes);
const bundles = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', 'dsh-web-mobile', 'dsh-app-integration'];
for (const [name, source] of [['dsh-web-mobile', 'app/src/main/assets/builtin-plugins/dsh-web-mobile'], ['dsh-app-integration', 'app/src/main/assets/app-integration']]) {
  fs.symlinkSync(path.join(root, source), path.join(profile, 'node_modules', name), process.platform === 'win32' ? 'junction' : 'dir');
}
fs.writeFileSync(path.join(profile, 'package.json'), JSON.stringify({ name: 'dsha-download-browser-fixture', version: '1.0.0', private: true, dsh: { profile: { bundles } } }));
const hook = path.join(base, 'normal-stop.mjs');
fs.writeFileSync(hook, `process.on('message', message => { if (message?.type === 'dsha-test-normal-stop') { process.emit('SIGTERM'); if (process.connected) process.disconnect(); } });\n`);
const clean = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/(API_KEY|TOKEN|SECRET|PASSWORD|DSH_HOME|NODE_OPTIONS|DSHA_WEB_GENERATION)/i.test(key)));
const child = spawn(process.execPath, ['--import', pathToFileURL(hook).href, path.join(runtime, 'node_modules/@deepseek-ai/dsh/lib/bin.js'), 'web', '--no-open', '--host', '127.0.0.1', '--port', '0'], {
  cwd: workspace, windowsHide: true,
  env: { ...clean, DSH_HOME: home, HOME: home, USERPROFILE: home, SSH_CONNECTION: '127.0.0.1 1 127.0.0.1 2', BROWSER: 'true', NARB_DISABLE_NATIVE_CACHE: '1', DSH_TELEMETRY_DISABLED: '1' },
  stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
});
let log = '', exited = false, exitCode, browser, result;
child.on('exit', code => { exited = true; exitCode = code; });
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { log = (log + chunk).slice(-500000); });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const redact = text => text.replace(/token=[A-Za-z0-9_-]+/g, 'token=[REDACTED]');
try {
  let auth;
  for (let attempt = 0; attempt < 400 && !exited; attempt++) {
    auth = log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];
    if (auth) break; await delay(150);
  }
  assert(auth, 'actual CLI boot failed: ' + redact(log.slice(-3000)));
  const origin = new URL(auth).origin;
  const exchange = await fetch(auth, { redirect: 'manual' }); assert.equal(exchange.status, 303);
  const cookie = exchange.headers.get('set-cookie').split(';')[0];
  const rpc = async (method, args = {}) => {
    const response = await fetch(origin + '/api/' + method, { method: 'POST', headers: { Cookie: cookie, Origin: origin, 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method, payload: { args } }), signal: AbortSignal.timeout(20000) });
    assert.equal(response.status, 200); const answer = await response.json(); assert(answer.result.ok, JSON.stringify(answer.result.error)); return answer.result.value;
  };
  const sessionId = (await rpc('session/create', { request: { cwd: workspace, agentPreset: 'standard' } })).sessionId;
  const require = createRequire(import.meta.url);
  let playwright;
  try { playwright = require(process.env.DSHA_PLAYWRIGHT || 'playwright'); }
  catch { playwright = require(path.join(process.env.USERPROFILE || '', '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
  const engine = process.env.DSHA_BROWSER || 'chromium'; assert(['chromium', 'firefox'].includes(engine));
  browser = await playwright[engine].launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 360, height: 800 } });
  const failures = [], errors = [], observed = [];
  page.on('requestfailed', request => failures.push({ path: new URL(request.url()).pathname, error: request.failure()?.errorText }));
  page.on('pageerror', error => errors.push(redact(String(error))));
  page.on('response', response => { if (new URL(response.url()).pathname === '/api/dsha/files/prepare') observed.push({ status: response.status(), headers: response.headers() }); });
  let exposed = 0;
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    if (url.pathname.endsWith('.js') || url.pathname === '/plugins/' && url.search.startsWith('??')) {
      const response = await route.fetch(), source = await response.text();
      if (source.includes('window.__ModuleLoader__.load({ id: "dsh-web-mobile"')) {
        assert.equal(source.split('var __cache = {};').length, 2);
        exposed++; return route.fulfill({ response, body: source.replace('var __cache = {};', 'window.__downloadRequire = __localRequire; var __cache = {};') });
      }
      return route.fulfill({ response });
    }
    return route.continue();
  });
  // The exact page bridge remains responsible for opaque manifest validation,
  // nonce/generation binding and request correlation; native publication is out
  // of this browser fixture's scope. Do not turn a 5GB source into a page Blob.
  await page.addInitScript({ content: `window.__DSHA_NATIVE_PLUGINS__=true;window.__downloadRequests=[];window.DshaFiles={postMessage(text){const request=JSON.parse(text);__downloadRequests.push(request);queueMicrotask(()=>DshaFiles.onmessage({data:JSON.stringify({id:request.id,ok:true,result:{status:'queued',taskId:'fixture-'+__downloadRequests.length}})}));}};\n`
    + fs.readFileSync(path.join(root, 'app/src/main/assets/web-integration/files.js'), 'utf8')
    + `\n__dshaFilesBind({nonce:'owned-download-document',generation:194,instance:'isolated-host'});` });
  await page.goto(auth);
  await page.waitForFunction(() => window.__downloadRequire && document.documentElement.getAttribute('data-dsha-integration') === 'ready', undefined, { timeout: 45000 });
  assert.equal(exposed, 1, 'actual signed mobile module served once');
  await page.evaluate(({ sessionId }) => {
    document.documentElement.lang = 'zh-CN';
    const React = __downloadRequire('react'), ReactDOM = __downloadRequire('react-dom');
    const controls = __downloadRequire('./components/file-share-controls.js');
    // This isolated control root owns its actual shipped sheet/style effect,
    // independently of which first-run host screen is currently mounted.
    window.__stopDownloadActions = controls.installNativeFileActions();
    const host = document.createElement('div'); host.id = 'download-browser-fixture'; host.style.cssText = 'position:fixed;inset:0;z-index:100000;background:white'; document.body.appendChild(host);
    // The fixture root covers unrelated first-run host modals. Keep the actual
    // owned file sheet above this fixture-only layer so real pointer clicks work.
    const layer = document.createElement('style'); layer.textContent = '[data-dsha-file-sheet]{z-index:100001!important}'; document.head.appendChild(layer);
    window.__downloadRoot = ReactDOM.createRoot(host);
    window.__renderDownload = absolutePath => __downloadRoot.render(React.createElement(controls.FileDeliveryActions, { sessionId, absolutePath }));
    window.__sourceBlobCalls = 0; const create = URL.createObjectURL.bind(URL); URL.createObjectURL = file => { __sourceBlobCalls++; return create(file); };
  }, { sessionId });
  const checks = [];
  for (const [index, fixture] of fixtures.entries()) {
    await page.evaluate(file => __renderDownload(file), path.join(workspace, fixture.path));
    await page.locator('#download-browser-fixture button[aria-label="下载"]').click();
    await page.waitForFunction(count => __downloadRequests.filter(row => row.action === 'artifact').length === count, index + 1, { timeout: 15000 });
    const request = await page.evaluate(index => __downloadRequests.filter(row => row.action === 'artifact')[index], index);
    assert.equal(request.artifact.bytes, fixture.bytes.length); assert.equal(request.artifact.sha256, sha(fixture.bytes));
    assert.equal(request.destination, 'downloads'); assert.equal(request.binding.generation, 194);
    assert.deepEqual(Object.keys(request.artifact).sort(), ['id', 'name', 'mime', 'bytes', 'sha256', 'version'].sort());
    const resolved = await fetch(origin + '/api/dsha/files/resolve', { method: 'POST', headers: { Cookie: cookie, Origin: origin, 'Content-Type': 'application/json' }, body: JSON.stringify(request.artifact) });
    assert.equal(resolved.status, 200); const manifest = await resolved.json();
    const content = await fetch(origin + manifest.contentUrl, { headers: { Cookie: cookie, Origin: origin } }); assert.equal(content.status, 200);
    assert.equal(content.headers.get('content-length'), String(fixture.bytes.length)); assert.equal(content.headers.get('content-encoding'), null);
    assert.deepEqual(Buffer.from(await content.arrayBuffer()), fixture.bytes);
    checks.push({ name: fixture.path, bytes: fixture.bytes.length, sha256: sha(fixture.bytes), prepared: true, nativeQueued: true, contentExact: true });
  }
  const destinationActions = [];
  for (const [offset, destination, label, copy] of [
    [0, 'share', '分享', /正在准备分享/], [1, 'saveas', '另存为…', /正在准备另存为/],
  ]) {
    await page.evaluate(file => __renderDownload(file), path.join(workspace, fixtures[1].path));
    await page.locator('#download-browser-fixture button[aria-label="更多文件操作"]').click();
    await page.getByRole('dialog').getByRole('button', { name: label, exact: true }).click();
    await page.waitForFunction(count => __downloadRequests.filter(row => row.action === 'artifact').length === count, fixtures.length + offset + 1);
    const request = await page.evaluate(() => __downloadRequests.filter(row => row.action === 'artifact').at(-1));
    assert.equal(request.destination, destination); assert.equal(request.artifact.sha256, sha(fixtures[1].bytes));
    const notice = await page.locator('[data-mobile-nav="file-share-toast"]').textContent();
    assert.match(notice, copy); assert.doesNotMatch(notice, /已加入下载任务|发送成功|已分享/);
    destinationActions.push({ destination, requestExact: true, distinctProgressCopy: true });
    await page.waitForFunction(() => !document.querySelector('#download-browser-fixture button').disabled);
  }
  assert.equal(await page.evaluate(() => __sourceBlobCalls), 0);
  for (const [headers, expected] of [[{ Origin: origin }, 401], [{ Cookie: cookie, Origin: 'http://foreign.invalid' }, 403]]) {
    const denied = await fetch(origin + '/api/dsha/files/prepare', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, path: fixtures[0].path }) });
    assert.equal(denied.status, expected, 'authentication and same-origin checks still precede preparation');
    assert.equal((await denied.json()).error.code, 'access-denied');
  }
  assert.equal(observed.length, fixtures.length + destinationActions.length); assert(observed.every(row => row.status === 200));
  assert.deepEqual(failures, []); assert.deepEqual(errors, []);
  result = { status: 'PASS', version, engine, checks, destinationActions, prepareStatuses: observed.map(row => row.status), noPersistentSourceBlob: true, inputSha256,
    realCli: true, realBrowserAuth: true, actualMobileAndIntegration: true, unauthorizedAndForeignOriginRejected: true, deviceValidation: false, failures, errors };
  console.log(JSON.stringify(result));
} finally {
  if (browser) await browser.close();
  if (!exited) child.send({ type: 'dsha-test-normal-stop' });
  for (let attempt = 0; attempt < 100 && !exited; attempt++) await delay(100);
  fs.writeFileSync(path.join(base, 'host.log'), redact(log));
  if (!exited) { child.kill(); throw Error('owned CLI normal stop failed; original fixture retained'); }
  assert.equal(exitCode, 0, 'actual CLI normal shutdown');
  if (result) fs.writeFileSync(path.join(base, 'result.json'), JSON.stringify({ ...result, normalShutdown: true, exitCode }, null, 2) + '\n');
}
