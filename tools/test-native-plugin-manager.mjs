import os from 'node:os';
import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// The rc2.1 overlay must remove the old review denial from both Host and UI.
const managed = testRuntime('managed');
const raw = testRuntime('raw');
const hostPath = 'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js';
const uiPath = 'node_modules/@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js';
const policy = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-policy-patch.json', 'utf8'));
const upstreamHost = fs.readFileSync(path.join(raw, hostPath), 'utf8').replaceAll('\r\n', '\n');
const currentHost = fs.readFileSync(path.join(managed, hostPath), 'utf8').replaceAll('\r\n', '\n');
const [patch] = policy.patches;
assert.equal(policy.patches.length, 1);
assert.equal(upstreamHost.split(patch.before).length - 1, 1);
assert.ok(currentHost === upstreamHost.replace(patch.before, patch.after), 'Host overlay differs from the pinned one-patch source');
assert.doesNotMatch(currentHost, /--ignore-scripts/);
assert.doesNotMatch(currentHost, /--ignore-pnpmfile/);
assert.doesNotMatch(currentHost, /DSHA_NATIVE_REVIEW_REQUIRED|DSHA_NATIVE_PLUGIN_POLICY_V1/);
const uiPatch = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-auto-enable-patch.json', 'utf8')).patches[0];
const upstreamUi = fs.readFileSync(path.join(raw, uiPath), 'utf8').replaceAll('\r\n', '\n');
const currentUi = fs.readFileSync(path.join(managed, uiPath), 'utf8').replaceAll('\r\n', '\n');
assert.equal(upstreamUi.split(uiPatch.before).length - 1, 1);
assert.ok(currentUi === upstreamUi.replace(uiPatch.before, uiPatch.after),
  'Web plugin installation must enable the installed bundle by default');

const { PluginManager } = await import(pathToFileURL(path.join(managed, hostPath)));
const manager = Object.create(PluginManager.prototype);
const directory = fs.mkdtempSync(path.join(os.tmpdir(),'native-plugin-policy-'));
try {
const sdk = path.join(directory, 'sdk');
const profile = path.join(directory, 'profile');
fs.mkdirSync(sdk, { recursive: true });
fs.mkdirSync(profile, { recursive: true });
fs.writeFileSync(path.join(sdk, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh' }));
fs.writeFileSync(path.join(profile, 'package.json'), '{}');
manager.profile = { installAnchor: path.join(sdk, 'package.json'), dir: profile };
let changes = 0;
manager.change = async () => { changes++; return { changed: true, application: 'applied' }; };
const original = process.env.DSHA_NATIVE_PLUGIN_MANAGER;
process.env.DSHA_NATIVE_PLUGIN_MANAGER = '1';
try {
  for (const result of [
    await manager.installBundle('community-plugin@1.0.0', { enabled: true }),
    await manager.setBundleEnabled('community-plugin', true),
    await manager.removeBundle('community-plugin')
  ]) {
    assert.equal(result.application, 'applied');
  }
  assert.equal(changes, 3, 'Host operations must reach the upstream implementation');
} finally {
  if (original === undefined) delete process.env.DSHA_NATIVE_PLUGIN_MANAGER;
  else process.env.DSHA_NATIVE_PLUGIN_MANAGER = original;
}
console.log('PASS: Host plugin install, enable and remove are available; no review gate remains; unlocked lifecycle install is preserved.');

} finally { fs.rmSync(directory,{recursive:true,force:true}); }
