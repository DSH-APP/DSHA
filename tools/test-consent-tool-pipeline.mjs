// Pinned 0.2.2 ToolsService + native executor/pipeline, Bash and FS bodies plus the actual APK consent SDK.
// Only the guest platform files/HTTP, session projections and shell process are controlled adapters.
// FS writes are limited to this runner-created private fixture directory.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import Module from 'node:module';
import crypto from 'node:crypto';
import vm from 'node:vm';
import { EventEmitter } from 'node:events';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..'), assets = path.join(root, 'app/src/main/assets');
const raw = testRuntime('raw');
const expectedDshVersion = JSON.parse(fs.readFileSync(path.join(path.resolve(import.meta.dirname, '..'), 'app/src/main/assets/runtime-patches.json'), 'utf8')).dshVersion;
assert.equal(JSON.parse(fs.readFileSync(path.join(raw, 'node_modules/@deepseek-ai/dsh/package.json'))).version, expectedDshVersion);
const directory = fs.mkdtempSync(path.join(root, 'app/build/consent-tools-'));
const modules = path.join(directory, 'node_modules'); fs.mkdirSync(modules);
const recipes = ['guard-tools-patch.json','tool-dispatch-validation-pipeline-patch.json','guard-tools-pipeline-patch.json','guard-bash-patch.json','guard-fs-patch.json','guard-editor-patch.json'].map(name => JSON.parse(fs.readFileSync(path.join(assets, name))));
// Executors resolve @deepseek-ai/dsh-tools/pipeline from their real path, so they are copied beside the patched registry.
const owned = new Set([...recipes.map(recipe => recipe.module.split('/').slice(0, 2).join('/')), '@deepseek-ai/dsh-tool-executor-native']);
for (const name of fs.readdirSync(path.join(raw, 'node_modules'))) {
  const source = path.join(raw, 'node_modules', name), target = path.join(modules, name);
  if (name === '@deepseek-ai') {
    fs.mkdirSync(target);
    for (const child of fs.readdirSync(source)) {
      if (owned.has(name + '/' + child)) fs.cpSync(path.join(source, child), path.join(target, child), { recursive: true });
      else fs.symlinkSync(path.join(source, child), path.join(target, child), process.platform === 'win32' ? 'junction' : 'dir');
    }
  } else if (fs.statSync(source).isDirectory()) fs.symlinkSync(source, target, process.platform === 'win32' ? 'junction' : 'dir');
}
for (const recipe of recipes) {
  const target = path.join(modules, recipe.module);
  let content = fs.readFileSync(target, 'utf8');
  if (recipe.sourceSha256) assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(raw, 'node_modules', recipe.module))).digest('hex'), recipe.sourceSha256);
  for (const patch of recipe.patches) { assert.equal(content.split(patch.before).length - 1, 1); content = content.replace(patch.before, patch.after); }
  fs.writeFileSync(target, content);
}
fs.cpSync(path.join(assets, 'destructive-policy'), path.join(modules, 'dsha-destructive-policy'), { recursive: true });
let mode = 'valid-off', requests = 0, bodies = 0, approvals = 0, port = '3092';
const token = 't'.repeat(43);
const consent = { schema: 1, available: true, globalAllow: false, revision: 0,
  installationId: '11111111-2222-3333-4444-555555555555', bridgeRunId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  webGeneration: 7, runtimeInstanceId: '22222222-3333-4444-5555-666666666666:7' };
const info = (directory, value) => ({ dev: 1, ino: directory ? 1 : 2, size: value.length, mode: 0o100600,
  isDirectory: () => directory, isFile: () => !directory, isSymbolicLink: () => false });
const platformFs = { constants: { O_RDONLY: 0, O_NOFOLLOW: 0x20000 },
  lstatSync: file => info(['/root','/root/.dsh'].includes(file), file.endsWith('_port') ? port : token),
  openSync: file => file.endsWith('_port') ? 8 : 7,
  fstatSync: fd => info(false, fd === 8 ? port : token),
  readSync: (fd, bytes) => bytes.write(fd === 8 ? port : token), closeSync() {} };
const http = { request(options, accept) {
  requests++; assert.equal(options.hostname, '127.0.0.1'); assert.equal(options.port, 3092);
  assert.equal(options.path, '/app/native-policy'); assert.equal(options.headers['X-Token'], token);
  const request = new EventEmitter();
  request.end = () => queueMicrotask(() => {
    const response = new EventEmitter(); response.statusCode = mode === 'wrong-token' ? 401 : 200;
    response.destroy = error => response.emit('error', error); accept(response);
    const value = { ...consent, globalAllow: mode === 'valid-on' };
    const body = mode === 'malformed' ? '{invalid' : JSON.stringify({ result: mode === 'wrong-port' ? '[UNKNOWN_ENDPOINT]' : JSON.stringify(value) });
    response.emit('data', Buffer.from(body)); response.emit('end');
  });
  return request;
} };
function load(name, dependencies) {
  const module = { exports: {} };
  vm.runInNewContext('(function(require,module,Buffer){' + fs.readFileSync(path.join(assets, name), 'utf8') + '\n})', {})(name => {
    assert(name in dependencies, 'unexpected SDK import ' + name); return dependencies[name];
  }, module, Buffer);
  return module.exports;
}
const sdk = load('native-consent.cjs', { 'node:fs': platformFs, 'node:http': http, 'node:crypto': crypto,
  './bridge-port.cjs': load('bridge-port.cjs', { 'node:fs': platformFs }) });
const originalLoad = Module._load;
Module._load = function(name, ...args) { return name === '/run/dsha-launcher/native-consent.cjs' ? sdk : originalLoad.call(this, name, ...args); };
const imported = name => import(pathToFileURL(path.join(modules, '@deepseek-ai', name, 'lib/index.js')));
const { Context } = await imported('cordis'), { ToolsService } = await imported('dsh-tools'), native = await imported('dsh-tool-executor-native');
const { ApprovalService } = await imported('dsh-user-approval'), { LocalFileSystem } = await imported('dsh-fs-local');
const bash = await imported('dsh-tool-bash'), files = await imported('dsh-tool-fs'), search = await imported('dsh-tool-fs-search');
const results = [];
async function test(name, action) {
  try { await action(); results.push({ name, status: 'PASS' }); }
  catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); }
}
async function fixture(action) {
  const ctx = new Context();
  ctx.provide('systemPrompt', { tools(){}, context(){}, getContextOrder(){return 10;}, getSectionOrder(){return 10;}, section(){} });
  ctx.provide('workingDirectory', { get: () => '/root/work', ensure: async () => '/root/work' });
  ctx.provide('shellEnv', { collect: () => ({ DSHA_FIXTURE: 'frozen' }) });
  ctx.provide('subprocess', { spawn(request) {
    bodies++; assert(request.argv.includes('--no-config'));
    return { done: Promise.resolve({ signal: null, exitCode: 0 }), collected: {
      stdout: { readFrom: () => ({ text: 'fixture.txt\n', lossy: false, nextOffset: 12 }) },
      stderr: { readFrom: () => ({ text: '', lossy: false, nextOffset: 0 }) } } };
  } });
  ctx.provide('shell', { resolve: request => request, execute: async request => {
    bodies++; assert.deepEqual(request.dshEnv, { DSHA_FIXTURE: 'frozen' });
    return { result: async () => ({ exitCode: 0, signal: null, timedOut: false, aborted: false, timeoutMs: 30000,
      stdout: { text: 'ready', truncated: false }, stderr: { text: '', truncated: false } }) };
  } });
  ctx.provide('sessionProjections', { async register() { return { stateOf: () => ({ turnOpen: true }) }; } });
  const tools = new ToolsService(ctx); ctx.plugin(ApprovalService, { policy: 'ask' }); ctx.plugin(native);
  await new Promise(resolve => setTimeout(resolve, 0)); assert(ctx.get('approval'));
  new LocalFileSystem(ctx, { cwd: directory, diffBasisMaxBytes: 1048576 });
  bash.apply(ctx, { enableRunInBackground: false, promoteOnTimeout: false });
  files.apply(ctx, { read: true, readImage: false, write: true, edit: true,
    readLimit: 2000, readMaxLineLength: 2000, readMaxBytes: 1048576, readStreamMinSize: 1048576 });
  await search.apply(ctx, { sampleOverCapGlobResults: false, globMaxResults: search.GLOB_MAX_RESULTS,
    grepMaxMatches: search.GREP_MAX_MATCHES, grepMaxLineBytes: search.GREP_MAX_LINE_BYTES,
    searchMetaMaxBytes: search.SEARCH_META_MAX_BYTES, rawOutputMaxBytes: search.RAW_OUTPUT_MAX_BYTES,
    graceMs: search.SEARCH_GRACE_MS, stderrMaxBytes: search.SEARCH_STDERR_MAX_BYTES, timeoutMs: search.SEARCH_TIMEOUT_MS });
  ctx.on('approval/request', async () => { approvals++; return 'allowed-once'; });
  const events = [{ type: 'turn/start', data: {} }];
  const session = { header: { cwd: directory }, get seq(){ return events.length; }, eventAt: i => events[i], append(type, data){ events.push({ type, data }); return { seq: events.length }; } };
  for (const name of ['bash','read','write','glob']) assert(tools.get(name), 'missing actual tool ' + name);
  const call = (name, args) => tools.execute({ calls: [{ type: 'tool-call', id: crypto.randomUUID(), name, arguments: JSON.stringify(args) }], agent: { ctx, session }, signal: new AbortController().signal }).then(batch => batch.results[0].result);
  try { await action(call); } finally { await ctx.fiber.dispose(); }
}
try {
  for (const failure of ['malformed','wrong-port','wrong-token']) {
    mode = failure;
    await test(failure + ': actual safe Bash, file read and discovery dispatch without a native request', () => fixture(async call => {
      const before = requests, file = path.join(directory, 'read-' + failure); fs.writeFileSync(file, 'original');
      for (const command of ['echo ready','curl https://example.com']) {
        const result = await call('bash', { command, description: 'private consent fixture' }); assert(!result.isError, JSON.stringify(result));
      }
      for (const [name, args] of [['read',{ file_path: file }],['glob',{ pattern: '*.txt' }]]) {
        const result = await call(name, args); assert(!result.isError, JSON.stringify(result));
      }
      assert.equal(requests, before);
    }));
    for (const command of ['rm /tmp/existing','python -c "opaque()"','echo x > /tmp/existing']) await test(failure + ': ' + command + ' cannot reach the shell body', () => fixture(async call => {
      const before = bodies, result = await call('bash', { command, description: 'blocked fixture' });
      assert(result.isError, JSON.stringify(result)); assert.match(result.error.message, /DSHA_NATIVE_CONSENT_/); assert.equal(bodies, before);
    }));
    await test(failure + ': overwrite keeps original bytes', () => fixture(async call => {
      const file = path.join(directory, 'write-' + failure); fs.writeFileSync(file, 'original');
      const result = await call('write', { file_path: file, content: 'replacement' });
      assert(result.isError, JSON.stringify(result)); assert.match(result.error.message, /DSHA_NATIVE_CONSENT_/); assert.equal(fs.readFileSync(file, 'utf8'), 'original');
    }));
  }
  for (const allowed of ['valid-off','valid-on']) {
    mode = allowed;
    await test(allowed + ': actual opaque Bash and FS overwrite honor current policy', () => fixture(async call => {
      const before = approvals, initialBodies = bodies;
      let result = await call('bash', { command: 'python -c "opaque()"', description: 'memory process fixture' }); assert(!result.isError, JSON.stringify(result));
      const file = path.join(directory, allowed); fs.writeFileSync(file, 'original');
      result = await call('write', { file_path: file, content: 'replacement' }); assert(!result.isError, JSON.stringify(result));
      assert.equal(fs.readFileSync(file, 'utf8'), 'replacement'); assert.equal(bodies, initialBodies + 1);
      assert.equal(approvals - before, allowed === 'valid-off' ? 2 : 0);
    }));
  }
} finally { Module._load = originalLoad; }
console.log(JSON.stringify({ scope: 'ACTUAL_PINNED_DSH022_TOOLS_PIPELINE_BASH_FS_AND_NATIVE_SDK_CONTROLLED_PLATFORM_IO',
  actualDshVersion: expectedDshVersion, shellSpawned: false, networkRequested: false, nativeEndpointTested: false,
  discoveryBody: 'actual pinned 0.2.2 glob; controlled subprocess', fixture: directory,
  cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results }, null, 2));
if (results.some(row => row.status !== 'PASS')) process.exitCode = 1;
