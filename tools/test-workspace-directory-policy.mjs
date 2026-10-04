import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { mkdtemp, mkdir, readFile, stat, symlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = path.resolve(import.meta.dirname, '..');
const verifyArchive = process.argv.slice(2).includes('--verify-archive');
assert(process.argv.slice(2).every(argument => argument === '--verify-archive'));
const runtime = testRuntime('raw');
const require = createRequire(pathToFileURL(path.join(runtime, 'package.json')));
const asset = 'app/src/main/assets/workspace-directory-policy-patch.json';
const recipe = JSON.parse(await readFile(path.join(root, asset), 'utf8'));
const canonical = await readFile(path.join(runtime, 'node_modules', recipe.module), 'utf8');
const version = JSON.parse(await readFile(
  require.resolve('@deepseek-ai/dsh-api-workspace-controller/package.json'), 'utf8')).version;
assert.equal(version, '0.2.0-rc.2');
assert.equal(recipe.dshVersion, version);
const fixture = await mkdtemp(path.join(root, 'app/build/workspace-policy-'));
await symlink(path.join(runtime, 'node_modules'), path.join(fixture, 'node_modules'),
  process.platform === 'win32' ? 'junction' : 'dir');
const sha = text => createHash('sha256').update(text).digest('hex');

const python = process.env.DSHA_PYTHON || 'python';
const buildScript = path.join(root, 'tools/build-dsh-runtime.py');
const patchScript = [
  'import importlib.util,json,pathlib,sys',
  'sys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))',
  'spec=importlib.util.spec_from_file_location("workspace_builder",sys.argv[1])',
  'builder=importlib.util.module_from_spec(spec)',
  'spec.loader.exec_module(builder)',
  'request=json.load(sys.stdin)',
  'source=request["source"].encode("utf-8")',
  'patched=builder.patched_content(pathlib.PurePosixPath(request["module"]),source)',
  'print(json.dumps({"source":patched.decode("utf-8"),"inputs":builder.recipe_inputs()}))'
].join(';');
function build(source, module = recipe.module) {
  return JSON.parse(execFileSync(python, ['-B', '-c', patchScript, buildScript], {
    input: JSON.stringify({ source, module }), encoding: 'utf8', windowsHide: true,
    timeout: 30000, maxBuffer: 8 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe']
  }));
}
const transformed = build(canonical);
assert.equal(transformed.inputs[asset], sha(await readFile(path.join(root, asset))));
const expected = canonical.replace(recipe.patches[0].before, recipe.patches[0].after);
assert.equal(transformed.source, expected, 'actual builder must apply the exact locked recipe');
const rejectsAnchor = source => assert.throws(() => build(source), /source anchor changed/);
rejectsAnchor(canonical.replace(recipe.patches[0].before, 'changed locked resolver'));
rejectsAnchor(canonical + '\n' + recipe.patches[0].before);
rejectsAnchor(transformed.source);
assert.equal(build(canonical, '@deepseek-ai/unrelated/lib/index.js').source, canonical);
let testedSource = transformed.source;
if (verifyArchive) {
  const managed = testRuntime('managed');
  testedSource = await readFile(path.join(managed, 'node_modules', recipe.module), 'utf8');
  assert.equal(testedSource.split('DSHA_PRIVATE_WORKSPACE_DOCUMENTS_V1').length - 1, 1);
  assert(testedSource.includes(recipe.patches[0].after));
  const archive = path.join(root, 'app/src/main/assets/dsh-runtime.bin');
  const inputs = JSON.parse(await readFile(path.join(root,
    'app/src/main/assets/dsh-runtime.inputs.json'), 'utf8'));
  assert.equal(inputs.inputs[asset], transformed.inputs[asset]);
  assert.equal(inputs.inputs['tools/build-dsh-runtime.py'], sha(await readFile(buildScript)));
  assert.equal(inputs.archive_sha256, sha(await readFile(archive)));
  const entry = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/' + recipe.module;
  const archiveRead = [
    'import pathlib,sys,tarfile',
    'archive=tarfile.open(sys.argv[1],"r:gz")',
    'sys.stdout.buffer.write(archive.extractfile(sys.argv[2]).read())'
  ].join(';');
  const packed = execFileSync(python, ['-B', '-c', archiveRead, archive, entry],
    { encoding: 'utf8', windowsHide: true, timeout: 30000, maxBuffer: 8 * 1024 * 1024 });
  assert.equal(packed, testedSource, 'actual archive and proven current managed module must agree');
}
const moduleFile = path.join(fixture, 'workspace-policy.mjs');
await writeFile(moduleFile, testedSource + '\nexport { defaultWorkspaceDirectory as policy };\n');
const { policy, WorkspaceController } = await import(pathToFileURL(moduleFile));
const { WorkspaceRegistry } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-workspace')));
const { Context, Service } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { JsonStorageBackend } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-json')));
const { DomainFacility } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-storage-domain')));
const checks = [];
async function check(name, work) {
  await work();
  checks.push(name);
}
async function marked(marker, work) {
  const previous = process.env.DSHA_WORKSPACE_DOCUMENTS;
  try {
    if (marker === undefined) delete process.env.DSHA_WORKSPACE_DOCUMENTS;
    else process.env.DSHA_WORKSPACE_DOCUMENTS = marker;
    return await work();
  } finally {
    if (previous === undefined) delete process.env.DSHA_WORKSPACE_DOCUMENTS;
    else process.env.DSHA_WORKSPACE_DOCUMENTS = previous;
  }
}
const signal = () => new AbortController().signal;
const forbiddenLookup = async () => { throw Error('XDG_LOOKUP_MUST_NOT_RUN'); };
const privatePath = '/root/Documents/deepseek-harness/default-workspace';
await check('unmarked Linux retains xdg discovery', () => marked(undefined, async () => {
  const calls = [];
  const result = await policy(undefined, signal(), {
    platform: 'linux', home: '/root',
    run: async (...args) => { calls.push(args); return { stdout: '/system/Documents\n' }; }
  });
  assert.equal(result, '/system/Documents/deepseek-harness/default-workspace');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'xdg-user-dir');
  assert.deepEqual(calls[0][1], ['DOCUMENTS']);
}));
await check('unmarked missing xdg remains a discovery failure', () => marked(undefined, async () => {
  const failure = Object.assign(Error('fixture missing executable'), { code: 'ENOENT' });
  await assert.rejects(policy(undefined, signal(), {
    platform: 'linux', home: '/root', run: async () => { throw failure; }
  }), error => error === failure);
}));
await check('exact DSHA marker and Linux private HOME bypass xdg', () => marked('/root/Documents',
  async () => assert.equal(await policy(undefined, signal(),
    { platform: 'linux', home: '/root', run: forbiddenLookup }), privatePath)));
await check('the actual HOME decides fallback when no HOME fact is injected',
  () => marked('/root/Documents', async () => {
    let calls = 0;
    const result = await policy(undefined, signal(), {
      platform: 'linux', run: async () => { calls++; return { stdout: '/system/Documents' }; }
    });
    assert.equal(result, homedir() === '/root' ? privatePath
      : '/system/Documents/deepseek-harness/default-workspace');
    assert.equal(calls, homedir() === '/root' ? 0 : 1);
  }));
await check('an unrelated official config value preserves the DSHA directory default',
  () => marked('/root/Documents', async () => {
    const config = WorkspaceController.Config({ documentsLookupTimeoutMs: 2500 });
    assert.equal(config.documentsLookupTimeoutMs, 2500);
    assert.equal(config.documentsDirectory, undefined);
    assert.equal(await policy(config.documentsDirectory, signal(),
      { platform: 'linux', home: '/root', run: forbiddenLookup }), privatePath);
  }));
for (const [name, marker, home] of [
  ['foreign marker retains discovery', '/sdcard/Documents', '/root'],
  ['empty marker retains discovery', '', '/root'],
  ['foreign HOME retains discovery', '/root/Documents', '/other-home']
]) {
  await check(name, () => marked(marker, async () => {
    let calls = 0;
    assert.equal(await policy(undefined, signal(), {
      platform: 'linux', home, run: async () => { calls++; return { stdout: '/system/Documents' }; }
    }), '/system/Documents/deepseek-harness/default-workspace');
    assert.equal(calls, 1);
  }));
}
await check('Windows keeps the upstream native discovery', () => marked('/root/Documents', async () => {
  let command;
  const result = await policy(undefined, signal(), {
    platform: 'win32', home: '/root',
    run: async name => { command = name; return { stdout: 'C:\\Users\\fixture\\Documents\r\n' }; }
  });
  assert.equal(command, 'powershell.exe');
  assert.equal(result, 'C:\\Users\\fixture\\Documents\\deepseek-harness\\default-workspace');
}));
await check('macOS keeps the upstream native discovery', () => marked('/root/Documents', async () => {
  let command;
  const result = await policy(undefined, signal(), {
    platform: 'darwin', home: '/root',
    run: async name => { command = name; return { stdout: '/Users/fixture/Documents\n' }; }
  });
  assert.equal(command, 'osascript');
  assert.equal(result, '/Users/fixture/Documents/deepseek-harness/default-workspace');
}));
await check('explicit custom Documents always wins', () => marked('/root/Documents', async () => {
  assert.equal(await policy('/custom/Documents', signal(),
    { platform: 'linux', home: '/root', run: forbiddenLookup }),
  '/custom/Documents/deepseek-harness/default-workspace');
}));
for (const directory of ['', 'relative/Documents']) {
  await check('invalid explicit Documents rejects: ' + JSON.stringify(directory),
    () => marked('/root/Documents', async () => {
      await assert.rejects(policy(directory, signal(),
        { platform: 'linux', home: '/root', run: forbiddenLookup }), /fully qualified/);
    }));
}
await check('explicit null is not replaced with the private default',
  () => marked('/root/Documents', async () => {
    await assert.rejects(policy(null, signal(),
      { platform: 'linux', home: '/root', run: forbiddenLookup }), TypeError);
  }));
await check('pre-cancelled DSHA request keeps the original cancellation', () => marked('/root/Documents',
  async () => {
    const controller = new AbortController(), reason = Error('fixture cancellation');
    controller.abort(reason);
    await assert.rejects(policy(undefined, controller.signal,
      { platform: 'linux', home: '/root', run: forbiddenLookup }), error => error === reason);
  }));
await check('cancellation during upstream discovery still rejects', () => marked(undefined, async () => {
  const controller = new AbortController(), reason = Error('cancelled during lookup');
  await assert.rejects(policy(undefined, controller.signal, {
    platform: 'linux', home: '/root',
    run: async () => { controller.abort(reason); return { stdout: '/system/Documents' }; }
  }), error => error === reason);
}));

async function registryAt(name) {
  const directory = path.join(fixture, name), storage = path.join(directory, 'storages');
  const backend = new JsonStorageBackend(storage), context = new Context();
  const peers = { live: [], stored: [] };
  context.provide('logger', { warn() {}, error() {} });
  context.provide('storage', { backend: { get: key => {
    assert.equal(key, 'json');
    return backend;
  } } });
  context.provide('sessions', { list: () => peers.live });
  context.provide('sessionPersistence', { list: async () => peers.stored });
  const domains = new DomainFacility(context, { backend: 'json' });
  context.provide('storageDomain', domains);
  const registry = new WorkspaceRegistry(context);
  await registry[Service.init]();
  return {
    directory, registry, peers, file: path.join(storage, 'workspace.json'),
    async close() {
      await domains.closeAll();
      await backend.close();
      await context.fiber.dispose();
    }
  };
}
await check('DSHA resolver feeds real recursive creation and durable workspace registration',
  () => marked('/root/Documents', async () => {
    const state = await registryAt('private-default');
    const candidate = path.join(state.directory, 'private-root', 'Documents',
      'deepseek-harness', 'default-workspace');
    try {
      assert.equal(state.registry.requireState().initialized, true);
      assert.deepEqual(state.registry.list(), []);
      const workspace = await state.registry.initializeDefault(async () => {
        assert.equal(await policy(undefined, signal(),
          { platform: 'linux', home: '/root', run: forbiddenLookup }), privatePath);
        // Windows cannot execute a POSIX /root path. Map only this verified guest path
        // into the owned fixture; Registry, mkdir, realpath and JSON writes stay real.
        return candidate;
      });
      assert((await stat(candidate)).isDirectory());
      assert.equal(workspace.path, candidate);
      const document = JSON.parse(await readFile(state.file, 'utf8'));
      assert.equal(document.global.defaultWorkspaceId, workspace.id);
      assert.deepEqual(document.global.workspaceIds, [workspace.id]);
      assert.equal(document.tables.workspaces[workspace.id].path, candidate);
      const id = workspace.id;
      await state.close();
      const reopened = await registryAt('private-default');
      try {
        const before = await readFile(reopened.file);
        const existing = await reopened.registry.initializeDefault(() => {
          throw Error('existing default must not resolve or create another directory');
        });
        assert.equal(existing.id, id);
        assert.deepEqual(await readFile(reopened.file), before);
      } finally { await reopened.close(); }
    } finally { await state.close(); }
  }));
await check('existing live Session prevents default creation without changing the registry', async () => {
  const state = await registryAt('existing-live');
  try {
    state.peers.live = [{ header: { id: 'fixture-existing-session' } }];
    const before = await readFile(state.file);
    assert.equal(await state.registry.initializeDefault(forbiddenLookup), undefined);
    assert.deepEqual(await readFile(state.file), before);
  } finally { await state.close(); }
});
await check('existing persisted Session prevents default creation', async () => {
  const state = await registryAt('existing-stored');
  try {
    state.peers.stored = [{ header: { id: 'fixture-existing-session' } }];
    const before = await readFile(state.file);
    assert.equal(await state.registry.initializeDefault(forbiddenLookup), undefined);
    assert.deepEqual(await readFile(state.file), before);
  } finally { await state.close(); }
});
await check('cancelled DSHA initialization leaves the durable registry unchanged',
  () => marked('/root/Documents', async () => {
    const state = await registryAt('cancelled-default');
    try {
      const before = await readFile(state.file), controller = new AbortController();
      const reason = Error('fixture initialization cancellation');
      controller.abort(reason);
      await assert.rejects(state.registry.initializeDefault(() => policy(undefined, controller.signal,
        { platform: 'linux', home: '/root', run: forbiddenLookup })), error => error === reason);
      assert.deepEqual(await readFile(state.file), before);
      assert.deepEqual(state.registry.list(), []);
    } finally { await state.close(); }
  }));
await check('explicit host directory creates and registers its own path',
  () => marked('/root/Documents', async () => {
    const state = await registryAt('explicit-directory');
    const documents = path.join(state.directory, 'user-chosen-documents');
    try {
      const candidate = await policy(documents, signal(),
        { platform: process.platform, home: '/root', run: forbiddenLookup });
      const workspace = await state.registry.initializeDefault(async () => candidate);
      assert.equal(workspace.path, path.join(documents, 'deepseek-harness', 'default-workspace'));
      assert((await stat(workspace.path)).isDirectory());
      const document = JSON.parse(await readFile(state.file, 'utf8'));
      assert.equal(document.tables.workspaces[workspace.id].path, workspace.path);
    } finally { await state.close(); }
  }));
await writeFile(path.join(fixture, 'receipt.json'), JSON.stringify({
  schema: 1, exitCode: 0, cases: checks, passed: checks.length,
  runtime, rawModuleSha256: sha(canonical), testedModuleSha256: sha(testedSource),
  recipeSha256: transformed.inputs[asset], archiveVerified: verifyArchive,
  scope: 'Actual locked rc2 builder/resolver/WorkspaceRegistry/DomainFacility/JsonStorageBackend. Linux facts and empty/existing Session lists are controlled peers; guest default path maps to an owned host fixture for real filesystem creation and durable reopen. No device, actual Session writes or APK generation.'
}, null, 2) + '\n');
console.log('PASS workspace Documents policy: ' + checks.length + ' checks; ' +
  (verifyArchive ? 'actual current archive bound' : 'current recipe over proven raw bytes; archive deferred'));
console.log('Fixture evidence: ' + path.join(fixture, 'receipt.json'));
