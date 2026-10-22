import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { Worker } from 'node:worker_threads';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { testRuntime } from './test-runtime-fixture.mjs';
import { preparePrivatePluginInventory } from './private-plugin-inventory-fixture.mjs';

// Import the complete current recipe chain beside the verified, untouched raw
// runtime. No stale managed fixture or hand-reimplemented Host decision is used.
const source = path.resolve(import.meta.dirname, '..');
const raw = testRuntime('raw');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-plugin-async-'));
const previousProof = process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const moduleName = '@deepseek-ai/dsh-plugin-manager/lib/index.js';
let host = fs.readFileSync(path.join(raw, 'node_modules', moduleName), 'utf8').replaceAll('\r\n', '\n');
for (const row of JSON.parse(fs.readFileSync(path.join(source, 'app/src/main/assets/runtime-patches.json'))).active) {
  const recipe = JSON.parse(fs.readFileSync(path.join(source, 'app/src/main/assets', row.asset)));
  if ((row.module ?? recipe.module) !== moduleName) continue;
  for (const patch of recipe.patches) {
    assert.equal(host.split(patch.before).length, 2, row.asset);
    const after = patch.prependAsset === undefined ? patch.after
      : fs.readFileSync(path.join(source, 'app/src/main/assets', patch.prependAsset), 'utf8') + '\n' + patch.after;
    host = host.replace(patch.before, after);
  }
}
fs.symlinkSync(path.join(raw, 'node_modules'), path.join(directory, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
const moduleFile = path.join(directory, 'plugin-manager.mjs');
const inventoryDependency = preparePrivatePluginInventory({source,raw,directory,managerSource:host});
assert.equal(inventoryDependency.evidence.importAdaptation.count, 1);
fs.writeFileSync(moduleFile, inventoryDependency.source);
const { PluginManager } = await import(pathToFileURL(moduleFile));
const home = path.join(directory, 'home'), profile = path.join(home, 'profiles/web'), sdk = path.join(directory, 'sdk');
fs.mkdirSync(profile, { recursive: true });
fs.mkdirSync(path.join(sdk, 'node_modules'), { recursive: true });
const installation = JSON.parse(fs.readFileSync(path.join(raw, 'node_modules/@deepseek-ai/dsh/package.json')));
for (const name of Object.keys(installation.dependencies ?? {})) {
  const target = path.join(raw, 'node_modules', name);
  if (!fs.existsSync(target)) continue;
  const alias = path.join(sdk, 'node_modules', name);
  fs.mkdirSync(path.dirname(alias), { recursive: true });
  fs.symlinkSync(target, alias, process.platform === 'win32' ? 'junction' : 'dir');
}
const fixtureNames = ['@fixture/content-a', '@fixture/content-b', '@fixture/content-c'];
const libraryName = '@fixture/large-library';
const roots = new Set(), aliasTargets = new Set();
function writePackage(name, bundle) {
  const folder = path.join(sdk, 'node_modules', name);
  fs.mkdirSync(folder, { recursive: true });
  fs.writeFileSync(path.join(folder, 'package.json'), JSON.stringify({ name, version: '1.0.0', ...bundle ? { dsh: { bundle: { patch: 'cordis.patch.yml' } } } : {} }));
  if (bundle) fs.writeFileSync(path.join(folder, 'cordis.patch.yml'), '[]\n');
  fs.writeFileSync(path.join(folder, 'payload.bin'), Buffer.alloc(4 * 1024 * 1024, 0x31));
  roots.add(folder);
  installation.dependencies[name] = '1.0.0';
  return folder;
}
for (const name of fixtureNames) writePackage(name, true);
const library = writePackage(libraryName, false);
fs.writeFileSync(path.join(sdk, 'package.json'), JSON.stringify(installation));
fs.writeFileSync(path.join(profile, 'package.json'), JSON.stringify({ name: 'isolated-async-profile', private: true, dsh: { profile: { bundles: [] } } }));
fs.writeFileSync(path.join(profile, 'cordis.patch.yml'), '[]\n');
function proofOf(folder) {
  const rows = new Map();
  function walk(folder, prefix = '') {
    for (const name of fs.readdirSync(folder)) {
      const file = path.join(folder, name), relative = prefix ? prefix + '/' + name : name, stat = fs.lstatSync(file);
      if (stat.isSymbolicLink()) rows.set(relative, relative + '\0LINK\0' + fs.readlinkSync(file) + '\n');
      else if (stat.isDirectory()) walk(file, relative);
      else rows.set(relative, relative + '\0FILE\0' + (stat.mode & 0o777) + '\0' + sha(fs.readFileSync(file)) + '\n');
    }
  }
  walk(folder);
  return sha([...rows.keys()].sort().map(key => rows.get(key)).join(''));
}
const packages = { ...JSON.parse(fs.readFileSync(path.join(source, 'app/src/main/assets/managed-package-proofs.json'))).packages };
for (const name of [...fixtureNames, libraryName]) packages[name] = proofOf(path.join(sdk, 'node_modules', name));
const proofFile = path.join(home, 'managed-package-proofs.json');
fs.writeFileSync(proofFile, JSON.stringify({ version: 1, packages }));
process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256 = sha(fs.readFileSync(proofFile));
const ctx = { get: () => undefined, emit: () => {}, loader: { entries: () => [] }, logger: { warn: () => {} } };
const manager = Object.create(PluginManager.prototype);
Object.defineProperty(manager, 'ctx', { value: ctx });
Object.assign(manager, {
  profile: { name: 'web', dir: profile, home, patchPath: path.join(profile, 'cordis.patch.yml'), overlays: [], startedBundles: [], installAnchor: path.join(sdk, 'package.json') },
  ownerContext: ctx, managementBundles: new Set(), installedFrom: new Map(), packageOperations: new Set(), installs: new Map(), abort: new AbortController(), lockWaitMs: 1000
});

const originalOpen = fs.promises.open, originalAlloc = Buffer.alloc;
let measured = false, mutation, activeHandles = 0, maxHandles = 0, proofOpens = 0, contentBytes = 0, payloadBytes = 0, libraryBytes = 0;
let buffers = new Set(), contentFiles = new Set();
const isContent = file => path.resolve(file) !== proofFile && (path.resolve(file).startsWith(path.resolve(sdk) + path.sep) || path.resolve(file).startsWith(path.resolve(raw) + path.sep) || [...aliasTargets].some(target => path.resolve(file).startsWith(target + path.sep)));
fs.promises.open = async function(file, ...args) {
  const handle = await originalOpen.call(this, file, ...args);
  if (!measured) return handle;
  const resolved = path.resolve(file);
  if (resolved === proofFile) proofOpens++;
  if (!isContent(file)) return handle;
  activeHandles++; maxHandles = Math.max(maxHandles, activeHandles); contentFiles.add(resolved);
  const read = handle.read.bind(handle), close = handle.close.bind(handle);
  handle.read = async function(buffer, ...args) {
    assert(buffer.byteLength <= 65536, 'unbounded proof buffer'); buffers.add(buffer);
    const result = await read(buffer, ...args);
    contentBytes += result.bytesRead;
    if (roots.has(path.dirname(resolved))) payloadBytes += result.bytesRead;
    if (resolved.startsWith(library + path.sep)) libraryBytes += result.bytesRead;
    if (mutation && result.bytesRead) { const mutate = mutation; mutation = undefined; await mutate(resolved); }
    return result;
  };
  handle.close = async function() { try { return await close(); } finally { activeHandles--; } };
  return handle;
};
Buffer.alloc = function(size, ...args) {
  if (measured) assert(size !== 1048576, 'one MiB allocated for every file');
  return originalAlloc.call(this, size, ...args);
};
function reset() { maxHandles = proofOpens = contentBytes = payloadBytes = libraryBytes = 0; buffers = new Set(); contentFiles = new Set(); }
const workerClient = `const {parentPort,workerData}=require('node:worker_threads');const http=require('node:http');const {performance}=require('node:perf_hooks');
function request(route){return new Promise((resolve,reject)=>{const start=performance.now();http.get('http://127.0.0.1:'+workerData.port+route,res=>{let body='';res.on('data',v=>body+=v);res.on('end',()=>resolve({ms:performance.now()-start,status:res.statusCode,body:JSON.parse(body)}));}).on('error',reject);});}
(async()=>{const lists=[request('/list'),request('/list')];await new Promise(r=>setTimeout(r,10));const ping=await request('/ping');const results=await Promise.all(lists);parentPort.postMessage({ping,lists:results});})().catch(error=>{throw error;});`;
let listsActive = 0;
const server = http.createServer(async (req, res) => {
  res.setHeader('content-type', 'application/json');
  if (req.url === '/ping') { res.end(JSON.stringify({ scanning: listsActive > 0 })); return; }
  if (req.url !== '/list') { res.writeHead(404).end('{}'); return; }
  listsActive++;
  try { const rows = await manager.listBundles(); res.end(JSON.stringify({ rows: rows.length, managedFixtures: rows.filter(row => fixtureNames.includes(row.name) && row.origin === 'dsh-managed').length })); }
  catch (error) { res.writeHead(500).end(JSON.stringify({ error: String(error) })); }
  finally { listsActive--; }
});
const report = { node: process.version, policySha256: sha(fs.readFileSync(path.join(source, 'app/src/main/assets/plugin-manager-policy-patch.json'))), patchedHostSha256: sha(host), inventoryDependency: inventoryDependency.evidence, installationDependencies: Object.keys(installation.dependencies).length };
try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  measured = true; reset();
  report.concurrent = await new Promise((resolve, reject) => {
    const worker = new Worker(workerClient, { eval: true, workerData: { port: server.address().port } });
    worker.once('message', resolve); worker.once('error', reject);
  });
  assert.equal(report.concurrent.ping.body.scanning, true, 'HTTP must respond before the ongoing content scans finish');
  for (const list of report.concurrent.lists) { assert.equal(list.status, 200, list.body.error); assert.equal(list.body.managedFixtures, 3); }
  assert.equal(proofOpens, 2, 'authenticate once for each list request');
  assert(maxHandles <= 2 && maxHandles > 0, 'global content concurrency');
  assert.equal(libraryBytes, 0, 'an SDK/library without dsh.bundle must not be hashed');
  assert(contentBytes >= 24 * 1024 * 1024, 'complete payloads must be read');
  assert(buffers.size < contentFiles.size, 'a package buffer must be reused across its files');
  report.scan = { proofOpens, maxHandles, contentBytes, buffers: buffers.size, contentFiles: contentFiles.size, libraryBytes };
  reset(); await manager.listBundles();
  assert.equal(proofOpens, 1); assert(contentBytes >= 12 * 1024 * 1024, 'a later request cannot trust cached mtime/digests');
  report.repeated = { proofOpens, contentBytes };
  const fixture = path.join(sdk, 'node_modules', fixtureNames[0]), payload = path.join(fixture, 'payload.bin'), oldStat = fs.statSync(payload);
  const fd = fs.openSync(payload, 'r+'); fs.writeSync(fd, Buffer.from('2'), 0, 1, 0); fs.closeSync(fd); fs.utimesSync(payload, oldStat.atime, oldStat.mtime);
  assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'user', 'same-size writes with restored mtime require a complete new digest');
  fs.writeFileSync(payload, Buffer.alloc(4 * 1024 * 1024, 0x31));
  mutation = async () => fs.promises.writeFile(path.join(fixture, 'concurrent-member.txt'), 'private data');
  await assert.rejects(manager.listBundles(fixtureNames[0]), /PACKAGE_PROOF_CHANGED/);
  fs.unlinkSync(path.join(fixture, 'concurrent-member.txt'));
  assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'dsh-managed');
  report.integrity = ['same-size restored-mtime edit rejected', 'concurrent directory member rejected', 'later full digest recovers original classification'];
  const originalMode = fs.statSync(payload).mode & 0o777;
  try {
    fs.chmodSync(payload, originalMode & ~0o222);
    if ((fs.statSync(payload).mode & 0o777) !== originalMode) {
      assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'user', 'FILE mode is part of the proof');
      report.integrity.push('FILE mode change rejected');
    } else report.integrity.push('SKIP: filesystem does not expose chmod mode changes');
  } finally { fs.chmodSync(payload, originalMode); }
  const link = path.join(fixture, 'payload.link');
  let junction = false;
  const removeLink = () => { if (fs.existsSync(link)) junction ? fs.rmdirSync(link) : fs.unlinkSync(link); };
  try {
    try { fs.symlinkSync('payload.bin', link, 'file'); }
    catch (error) {
      if (process.platform !== 'win32' || error.code !== 'EPERM') throw error;
      junction = true;
      fs.symlinkSync(path.join(sdk, 'node_modules', fixtureNames[1]), link, 'junction');
    }
    packages[fixtureNames[0]] = proofOf(fixture);
    fs.writeFileSync(proofFile, JSON.stringify({ version: 1, packages }));
    process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256 = sha(fs.readFileSync(proofFile));
    assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'dsh-managed');
    removeLink();
    fs.symlinkSync(junction ? path.join(sdk, 'node_modules', fixtureNames[2]) : 'cordis.patch.yml', link, junction ? 'junction' : 'file');
    assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'user', 'LINK target must be part of the proof');
    report.integrity.push('LINK target change rejected');
  } catch (error) {
    if (!['EPERM', 'ENOTSUP'].includes(error.code)) throw error;
    report.integrity.push('SKIP: file symlink creation unavailable: ' + error.code);
  } finally {
    removeLink();
    packages[fixtureNames[0]] = proofOf(fixture);
    fs.writeFileSync(proofFile, JSON.stringify({ version: 1, packages }));
    process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256 = sha(fs.readFileSync(proofFile));
  }
  const authenticated = fs.readFileSync(proofFile);
  fs.writeFileSync(proofFile, JSON.stringify({ version: 1, packages: {} }));
  assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'user', 'a changed proof file cannot authenticate itself');
  fs.writeFileSync(proofFile, authenticated);
  assert.equal((await manager.listBundles(fixtureNames[0]))[0].origin, 'dsh-managed');
  report.integrity.push('unauthenticated proof edit rejected');
  // The source alias must still resolve to the hashed physical tree after all
  // asynchronous work, even when both old/new package directories are stable.
  const alias = path.join(sdk, 'node_modules', fixtureNames[2]);
  const oldTarget = path.join(directory, 'canonical-old'), newTarget = path.join(directory, 'canonical-user');
  fs.renameSync(alias, oldTarget); fs.cpSync(oldTarget, newTarget, { recursive: true });
  fs.appendFileSync(path.join(newTarget, 'cordis.patch.yml'), '# user replacement\n');
  aliasTargets.add(oldTarget); aliasTargets.add(newTarget);
  fs.symlinkSync(oldTarget, alias, process.platform === 'win32' ? 'junction' : 'dir');
  mutation = async () => {
    process.platform === 'win32' ? fs.rmdirSync(alias) : fs.unlinkSync(alias);
    fs.symlinkSync(newTarget, alias, process.platform === 'win32' ? 'junction' : 'dir');
  };
  await assert.rejects(manager.listBundles(fixtureNames[2]), /PACKAGE_PROOF_CHANGED/);
  assert.equal((await manager.listBundles(fixtureNames[2]))[0].origin, 'user');
  process.platform === 'win32' ? fs.rmdirSync(alias) : fs.unlinkSync(alias);
  fs.symlinkSync(oldTarget, alias, process.platform === 'win32' ? 'junction' : 'dir');
  assert.equal((await manager.listBundles(fixtureNames[2]))[0].origin, 'dsh-managed');
  report.integrity.push('canonical alias substitution during async hash rejected');

  const voice = '@deepseek-ai/dsh-experimental-voice-input-bundle';
  const beforePort = server.address().port;
  const enable = await manager.setBundleEnabled(voice, true);
  const afterEnable = JSON.parse(fs.readFileSync(path.join(profile, 'package.json')));
  const voiceRow = (await manager.listBundles(voice))[0];
  assert.notEqual(enable.application, 'failed', JSON.stringify(enable));
  assert.equal(enable.application, 'restart-required');
  assert(afterEnable.dsh.profile.bundles.includes(voice), 'the official bundle selection must persist');
  assert.equal(voiceRow.enabled, true); assert.equal(voiceRow.rows.length, 4);
  assert.deepEqual(voiceRow.rows.map(row => row.rowId).sort(), ['api-speech-to-text', 'speech-to-text', 'speech-to-text-sensevoice', 'ui-voice-input']);
  assert(voiceRow.rows.every(row => typeof row.moduleName === 'string' && row.entryId === undefined), 'no live loader was started by this fixture');
  const disable = await manager.setBundleEnabled(voice, false);
  const afterDisable = JSON.parse(fs.readFileSync(path.join(profile, 'package.json')));
  assert.equal(disable.application, 'restart-required');
  assert(!afterDisable.dsh.profile.bundles.includes(voice), 'explicit disable must remain persisted');
  assert.equal((await manager.listBundles(voice))[0].enabled, false);
  assert.equal(server.address().port, beforePort);
  report.voice = { enable, disable, declaredComponents: voiceRow.rows, portBefore: beforePort, portAfter: server.address().port, scope: 'real Host with no HMR: persisted intent, explicit restart-required; this fixture never starts speech providers or claims live component activation' };

  reset();
  const protectedRemoval = await manager.removeBundle(fixtureNames[1]);
  assert.equal(protectedRemoval.application, 'failed');
  assert.equal(protectedRemoval.error.code, 'not-removable');
  assert.equal(proofOpens, 2, 'removal must authenticate both preflight and locked recheck');
  assert(contentBytes >= 8 * 1024 * 1024 && contentBytes < 9 * 1024 * 1024, 'both checks must read all target content without scanning unrelated packages');
  assert.equal(contentFiles.size, 3);
  report.removeTarget = { result: protectedRemoval, proofOpens, contentBytes, contentFiles: contentFiles.size };

  reset();
  const missing = await manager.removeBundle('@fixture/missing');
  assert.equal(missing.application, 'failed'); assert.equal(contentBytes, 0, 'remove preflight and locked recheck must not hash the whole table');
  report.removeMissing = { result: missing, proofOpens, contentBytes };
  const beforeCancel = fs.readFileSync(path.join(profile, 'package.json'));
  manager.abort.abort(new Error('fixture-request-cancelled'));
  await assert.rejects(manager.listBundles(), /fixture-request-cancelled/);
  await assert.rejects(manager.removeBundle(fixtureNames[1]), /fixture-request-cancelled/);
  assert.deepEqual(fs.readFileSync(path.join(profile, 'package.json')), beforeCancel, 'cancellation cannot replay a writer');
  report.cancel = 'aborted list/removal reject without writing';
  console.log(JSON.stringify(report, null, 2));
  console.log('PASS: real Host async provenance/HTTP concurrency, bounded scans, complete content stability, voice intent, fixed server port and target-only removal.');
} finally {
  measured = false; fs.promises.open = originalOpen; Buffer.alloc = originalAlloc;
  await new Promise(resolve => server.close(resolve));
  if (previousProof === undefined) delete process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
  else process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256 = previousProof;
  fs.rmSync(directory, { recursive: true, force: true });
}
