import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tempRoot = path.resolve(tmpdir());
const temp = mkdtempSync(path.join(tempRoot, 'dsha-web-ui-fixture-'));
try {
  const source = path.join(project, 'app/src/main/java/com/deepseekharness/app/util');
  const fixture = path.join(project, 'tools/fixtures/WebUiHostFixture.java');
  const compile = spawnSync('javac', ['-encoding', 'UTF-8', '-d', temp, path.join(source, 'WebUploadSessionBudget.java'),
    path.join(source, 'RecoveryLocalePolicy.java'), fixture], { encoding: 'utf8' });
  assert.equal(compile.status, 0, `javac failed:\n${compile.stdout}\n${compile.stderr}`);
  const run = spawnSync('java', ['-cp', temp, 'WebUiHostFixture'], { encoding: 'utf8' });
  assert.equal(run.status, 0, `Java fixture failed:\n${run.stdout}\n${run.stderr}`);
  process.stdout.write(run.stdout);

  const recoveryManifest = JSON.parse(readFileSync(path.join(project,
    'app/src/main/assets/recovery-web-integration/manifest.json'), 'utf8'));
  const formalManifest = JSON.parse(readFileSync(path.join(project,
    'app/src/main/assets/web-integration/manifest.json'), 'utf8'));
  assert.deepEqual(recoveryManifest.content_scripts[0].matches, ['http://localhost/*']);
  assert.deepEqual(formalManifest.content_scripts.map(x => x.matches), [['http://127.0.0.1/*'], ['http://127.0.0.1/*']]);
  const relay = readFileSync(path.join(project, 'app/src/main/assets/recovery-web-integration/locale-relay.js'), 'utf8');
  const geckoSurface = readFileSync(path.join(project,
    'app/src/low/java/com/deepseekharness/app/ui/RecoveryGeckoSurface.java'), 'utf8');
  const nativePort = geckoSurface.match(/NATIVE_PORT\s*=\s*"([^"]+)"/)?.[1];
  const connectName = relay.match(/connectNative\('([^']+)'\)/)?.[1];
  const geckoApplicationName = /^\w+(\.\w+)*$/;
  assert.equal(recoveryManifest.version, '1.1', 'changed built-in extension must refresh its cached version');
  assert.ok(nativePort && geckoApplicationName.test(nativePort), 'native app name must satisfy Gecko syntax');
  assert.equal(connectName, nativePort, 'Gecko Java and content script must use the same native app name');
  const sent = [], injected = [];
  const listenerMap = new Map();
  const port = { onMessage: { addListener: fn => listenerMap.set('message', fn) }, postMessage: value => sent.push(value) };
  const pageWindow = {
    top: null,
    addEventListener: (name, fn) => listenerMap.set(name, fn),
  };
  pageWindow.top = pageWindow;
  const document = {
    head: { appendChild(script) { injected.push(script.textContent); }, },
    documentElement: {},
    createElement: () => ({ textContent: '', remove() {} }),
  };
  const geckoRuntime = { connectNative(name) {
    if (!geckoApplicationName.test(name)) throw new TypeError('invalid Gecko native application name');
    assert.equal(name, nativePort);
    return port;
  } };
  assert.throws(() => geckoRuntime.connectNative('dsha-recovery-locale'),
    /invalid Gecko native application name/, 'old hyphenated name must fail before a port exists');
  vm.runInNewContext(relay, {
    window: pageWindow,
    location: { protocol: 'http:', hostname: 'localhost', port: '3081', username: '', password: '' },
    document,
    browser: { runtime: geckoRuntime },
    MutationObserver: class { observe() {} disconnect() {} },
  });
  listenerMap.get('message')({ type: 'language', language: 'en' });
  listenerMap.get('message')({ type: 'language', language: 'fr' });
  assert.equal(injected.length, 1);
  assert.match(injected[0], /__DSHA_LANGUAGE__='en'/);
  assert.deepEqual(sent, []);
  listenerMap.get('dsha-language-selected')({ detail: 'zh' });
  listenerMap.get('dsha-language-selected')({ detail: 'fr' });
  assert.deepEqual(JSON.parse(JSON.stringify(sent)), [{ type: 'language-selected', language: 'zh' }]);
  assert.doesNotMatch(relay, /cookie|authorization|api.?key/i, 'locale relay must not read or send credentials');
  console.log('PASS recovery Gecko relay is localhost-only, zh/en-only, and credential-blind');
} finally {
  const resolved = path.resolve(temp);
  if (!resolved.startsWith(tempRoot + path.sep)) throw new Error('refusing to remove fixture path outside temp root');
  rmSync(resolved, { recursive: true, force: true });
}
