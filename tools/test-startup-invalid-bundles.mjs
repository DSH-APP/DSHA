// Locked upstream loading + real observer events; no production profile or model request.
import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const raw = testRuntime('raw');
const requireRuntime = createRequire(path.join(raw, 'package.json'));
const { loadProfileDirectory, composeEntries } = await import(pathToFileURL(requireRuntime.resolve('@deepseek-ai/dsh-app-boot')));
const parent = path.join(root, 'app/build/startup-invalid-bundles');
fs.mkdirSync(parent, { recursive: true });
const home = fs.mkdtempSync(path.join(parent, 'fixture-'));
const profile = path.join(home, 'profiles/web');
const plugin = path.join(profile, 'node_modules/fixture-good-bundle');
fs.mkdirSync(plugin, { recursive: true });
const manifest = JSON.stringify({ name: 'isolated-profile', private: true,
  dsh: { profile: { bundles: ['invalid optional entry!', 'fixture-missing-bundle', 'fixture-missing-patch', 'fixture-invalid-json', 'fixture-good-bundle'] } } });
fs.writeFileSync(path.join(profile, 'package.json'), manifest);
fs.writeFileSync(path.join(profile, 'cordis.patch.yml'), '[]\n');
fs.writeFileSync(path.join(plugin, 'package.json'), JSON.stringify({ name: 'fixture-good-bundle', version: '1.0.0', type: 'module',
  dsh: { bundle: { patch: 'cordis.patch.yml' } } }));
fs.writeFileSync(path.join(plugin, 'cordis.patch.yml'), '- insert:\n    - id: fixture-good\n      name: fixture-good-bundle\n');

for (const name of ['fixture-missing-patch', 'fixture-invalid-json']) {
  const directory = path.join(profile, 'node_modules', name);
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, 'package.json'), name === 'fixture-invalid-json' ? '{broken'
    : JSON.stringify({ name, version: '1.0.0', dsh: { bundle: { patch: 'missing.patch.yml' } } }));
}

const loaded = loadProfileDirectory('dsh', profile, path.join(raw, 'node_modules/@deepseek-ai/dsh/package.json'));
assert.equal(loaded.skippedBundles.length, 4);
assert.equal(loaded.skippedBundles[0].packageName, 'invalid optional entry!');
assert.deepEqual(loaded.layers.map(layer => layer.packageName), ['fixture-good-bundle']);
const composed = composeEntries([...loaded.layers.map(layer => layer.patches), loaded.patches]);
assert.equal(composed.some(entry => entry.id === 'fixture-good'), true);
assert.equal(fs.readFileSync(path.join(profile, 'package.json'), 'utf8'), manifest);

const observer = process.env.DSHA_TEST_OBSERVER
  ? path.resolve(process.env.DSHA_TEST_OBSERVER)
  : path.join(root, 'app/src/main/assets/startup-observer.cjs');
const entry = path.join(plugin, 'index.js');
const events = result => result.stdout.split('\n').filter(line => line.startsWith('[DSHA_STARTUP] ')).map(line => JSON.parse(line.slice(15)));
for (const language of ['zh', 'en']) {
  fs.writeFileSync(entry, "console.log('FIXTURE_PLUGIN_EXECUTED_ONCE'); console.log('FIXTURE_READY');\n");
  const result = spawnSync(process.execPath, ['--import', pathToFileURL(observer).href, entry], {
    env: { ...process.env, DSH_HOME: home, DSHA_OBSERVER_INSTALL: path.join(raw, 'node_modules/@deepseek-ai/dsh'),
      DSHA_STARTUP_PROFILE: 'web', DSHA_UI_LANGUAGE: language }, encoding: 'utf8', timeout: 15000, windowsHide: true
  });
  assert.equal(result.status, 0, result.stderr);
  const warnings = events(result).filter(event => event.type === 'issue');
  assert.equal(warnings.length, 4);
  assert.equal(warnings.every(event => event.fatal === false), true);
  assert.equal(result.stdout.match(/FIXTURE_PLUGIN_EXECUTED_ONCE/g)?.length, 1);
  assert.equal(result.stdout.includes('FIXTURE_READY'), true);
  assert.equal(fs.readFileSync(path.join(profile, 'package.json'), 'utf8'), manifest);
}
fs.writeFileSync(entry, "throw new Error('fixture actual core failure');\n");
const failed = spawnSync(process.execPath, ['--import', pathToFileURL(observer).href, entry], {
  env: { ...process.env, DSH_HOME: home, DSHA_OBSERVER_INSTALL: path.join(raw, 'node_modules/@deepseek-ai/dsh'), DSHA_STARTUP_PROFILE: 'web' },
  encoding: 'utf8', timeout: 15000, windowsHide: true
});
assert.equal(failed.status, 1);
assert.equal(events(failed).some(event => event.type === 'issue' && event.message.includes('fixture actual core failure') && event.fatal !== false), true);
assert.equal(fs.readFileSync(path.join(profile, 'package.json'), 'utf8'), manifest);
// Exercise the actual boot/audit boundary as well as the observer's required Entry hook.
// Invalid plugin exports reject Entry.init; successful siblings must still run once.
const badEntry = path.join(home, 'bad-entry.mjs');
const goodEntry = path.join(home, 'good-entry.mjs');
const bootEntry = path.join(home, 'boot-entry.mjs');
const config = path.join(home, 'boot.yml');
fs.writeFileSync(badEntry, 'export default 42;\n');
fs.writeFileSync(goodEntry, "export default function apply() { console.log('FIXTURE_BOOT_GOOD_ONCE'); }\n");
fs.writeFileSync(bootEntry, `import { boot } from ${JSON.stringify(pathToFileURL(requireRuntime.resolve('@deepseek-ai/dsh-app-boot')).href)};
const ctx = await boot('fixture', ${JSON.stringify(config)});
console.log('FIXTURE_BOOT_READY');
await ctx.fiber.dispose();\n`);
for (const id of ['fixture-optional', 'webserver', 'web-runtime', 'modules']) {
  fs.writeFileSync(config, `- id: ${id}\n  name: ${JSON.stringify(pathToFileURL(badEntry).href)}\n- id: fixture-good\n  name: ${JSON.stringify(pathToFileURL(goodEntry).href)}\n`);
  const result = spawnSync(process.execPath, ['--expose-internals', '--import', pathToFileURL(observer).href, bootEntry], {
    env: { ...process.env, DSH_HOME: home, DSHA_OBSERVER_INSTALL: path.join(raw, 'node_modules/@deepseek-ai/dsh'), DSHA_STARTUP_PROFILE: 'web' },
    encoding: 'utf8', timeout: 15000, windowsHide: true
  });
  const optional = id === 'fixture-optional';
  assert.equal(result.status, optional ? 0 : 1, result.stderr);
  assert.equal(result.stdout.match(/FIXTURE_BOOT_GOOD_ONCE/g)?.length, 1, 'real boot must execute the good sibling once');
  assert.equal(result.stdout.includes('FIXTURE_BOOT_READY'), optional);
  assert.equal(events(result).some(event => event.type === 'issue' && event.message.includes('invalid plugin') && event.fatal === !optional), true,
    'observer must match actual required-entry audit: ' + result.stdout + result.stderr);
  assert.equal(fs.readFileSync(path.join(profile, 'package.json'), 'utf8'), manifest);
}
console.log('PASS locked upstream skips invalid/missing/unreadable optional bundles; observer warnings nonfatal; actual optional Entry failure preserves siblings, webserver/web-runtime/modules and uncaught failures stay fatal; manifest unchanged');
