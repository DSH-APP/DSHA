#!/usr/bin/env node
// Explicit historical/current runtimes; never infer format compatibility from version order.
// All writes are confined to a fresh fixture tree. Each phase runs in a new Node process.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { zstdCompressSync } from 'node:zlib';

const script = fileURLToPath(import.meta.url);
const sha = data => createHash('sha256').update(data).digest('hex');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const bytes = Buffer.from('isolated attachment\0保留附件\n' + 'x'.repeat(8192));
const value = { text: '保留个人数据', nested: { optional: null, flag: true }, list: [1, 'two', false] };
const event = (type, seq, data, extra = {}) => ({ type, seq, time: seq + 100, data, ...extra });
const rows = [event('turn/start', 0, { turn: 1 }), event('user/message', 1,
  { id: 'fixture-user', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: '保留这句话' }] }, { surfaceOp: 'append' }),
event('step/start', 2, { turn: 1, step: 1 }), event('step/end', 3, { turn: 1, step: 1 }),
event('turn/end', 4, { turn: 1, reason: { kind: 'completed' } })];
const tail = [event('turn/start', 5, { turn: 2 }), event('turn/end', 6, { turn: 2, reason: { kind: 'completed' } })];

async function worker(runtime, root, phase) {
  const require = createRequire(join(runtime, 'package.json'));
  const imported = name => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await imported('@deepseek-ai/cordis');
  const { JsonStorageBackend } = await imported('@deepseek-ai/dsh-storage-json');
  const Jsonl = await imported('@deepseek-ai/dsh-session-persistence-jsonl');
  const { sessionFormatLogFilename } = await imported('@deepseek-ai/dsh-session-format');
  const { releasedV3SessionFormatCodec } = await imported('@deepseek-ai/dsh-session-format-v2-to-v3');
  const { LocalAttachmentStore } = await imported('@deepseek-ai/dsh-attachment-local');
  const { LocalCredentialProvider } = await imported('@deepseek-ai/dsh-credentials-local');
  const { loadProfileDirectory, composeEntries } = await imported('@deepseek-ai/dsh-app-boot');
  const { stringify } = await imported('yaml');
  const initial = phase === 'old-write';
  const writingNew = phase === 'new-write';
  const verifyNew = phase === 'new-reopen' || phase === 'old-read-new';

  for (const layout of ['single', 'per-record']) {
    const backend = new JsonStorageBackend(join(root, 'storage'));
    try {
      const store = await backend.kv.open({ name: `compat_${layout.replace('-', '_')}`, version: 1,
        layout, hasGlobal: true, tables: ['items'] });
      if (initial) {
        await store.putRecord('items', 'old_record', value);
        await store.setGlobal({ owner: 'old', retained: value });
      } else {
        const state = await store.loadAll();
        assert.deepEqual(state.tables.items.old_record, value);
        assert.deepEqual(state.global, { owner: 'old', retained: value });
        if (verifyNew) assert.deepEqual(state.tables.items.new_record, { ...value, owner: 'new' });
        if (writingNew) await store.putRecord('items', 'new_record', { ...value, owner: 'new' });
      }
      await store.close();
    } finally { await backend.close(); }
  }

  for (const compression of ['none', 'zstd']) {
    const ctx = new Context();
    try {
      await ctx.plugin(Jsonl, { root: join(root, 'sessions', compression), compression });
      const persistence = ctx.sessionPersistence;
      if (initial) {
        const handle = await persistence.create({ version: 4, id: 'old-session', createdAt: 1, isSeeded: false });
        await handle.append(rows); await handle.close();
        const directory = join(root, 'sessions', compression, '_no-cwd', 'old-v3');
        await mkdir(directory, { recursive: true });
        const header = releasedV3SessionFormatCodec.encodeHeader({ version: 3, id: 'old-v3', createdAt: 1,
          delegationDepth: 0, isSeeded: false }, 0);
        const first = Buffer.from(JSON.stringify(header) + '\n');
        const rest = Buffer.from(rows.map(row => JSON.stringify(releasedV3SessionFormatCodec.encodeEvent(row))).join('\n') + '\n');
        const physical = compression === 'zstd' ? Buffer.concat([zstdCompressSync(first), zstdCompressSync(rest)]) : Buffer.concat([first, rest]);
        const legacyPath = join(directory, sessionFormatLogFilename(3) + (compression === 'zstd' ? '.zstd' : ''));
        await writeFile(legacyPath, physical);
        await writeFile(join(root, `legacy-${compression}.sha256`), sha(physical));
      } else {
        const handle = await persistence.open('old-session', writingNew ? 'write' : 'read');
        assert.equal(handle.header.version, 4);
        assert.deepEqual((await handle.read()).events, verifyNew ? [...rows, ...tail] : rows);
        if (writingNew) { await handle.append(tail); await handle.flush(); }
        await handle.close();
        if (writingNew) {
          const fresh = await persistence.create({ version: 4, id: 'new-session', createdAt: 2, isSeeded: false });
          await fresh.append(rows); await fresh.close();
        }
        if (verifyNew) {
          const fresh = await persistence.open('new-session', 'read');
          assert.deepEqual((await fresh.read()).events, rows); await fresh.close();
        }
        const legacyPath = join(root, 'sessions', compression, '_no-cwd', 'old-v3',
          sessionFormatLogFilename(3) + (compression === 'zstd' ? '.zstd' : ''));
        const legacy = await persistence.open('old-v3', writingNew ? 'write' : 'read');
        assert.equal(legacy.header.version, 4);
        assert.deepEqual((await legacy.read()).events, verifyNew ? [...rows, ...tail] : rows);
        if (writingNew) await legacy.append(tail);
        await legacy.close();
        assert.equal(sha(await readFile(legacyPath)), await readFile(join(root, `legacy-${compression}.sha256`), 'utf8'));
      }
    } finally { await ctx.fiber.dispose(); }
  }

  const attachmentContext = new Context();
  try {
    await attachmentContext.plugin(LocalAttachmentStore, { dshHome: join(root, 'home') });
    const store = attachmentContext.attachments;
    if (initial) {
      const ref = await store.saveFile({ data: bytes, name: '附件.bin' });
      await writeFile(join(root, 'attachment-ref.json'), JSON.stringify(ref));
      const sharp = require('sharp');
      const png = await sharp({ create: { width: 3, height: 2, channels: 4,
        background: { r: 12, g: 64, b: 128, alpha: 0.5 } } }).png().toBuffer();
      await writeFile(join(root, 'image-source.png'), png);
      const image = await store.saveImage({ data: png, mediaType: 'image/png', name: '保留图片.png' });
      await writeFile(join(root, 'image-ref.json'), JSON.stringify(image));
      await writeFile(join(root, 'image-data.sha256'), sha((await store.readImage(image)).data));
    } else {
      const ref = await json(join(root, 'attachment-ref.json'));
      const chunks = []; for await (const chunk of store.readFileStream(ref)) chunks.push(chunk);
      assert.deepEqual(Buffer.concat(chunks), bytes);
      const image = await json(join(root, 'image-ref.json'));
      assert.equal(sha((await store.readImage(image)).data), await readFile(join(root, 'image-data.sha256'), 'utf8'));
      if (writingNew) {
        async function* source() { yield bytes.subarray(0, 19); yield bytes.subarray(19); }
        const next = await store.saveFileStream({ data: source(), name: 'new-file.bin' });
        await writeFile(join(root, 'new-attachment-ref.json'), JSON.stringify(next));
        const nextImage = await store.saveImage({ data: await readFile(join(root, 'image-source.png')),
          mediaType: 'image/png', name: 'new-image.png' });
        await writeFile(join(root, 'new-image-ref.json'), JSON.stringify(nextImage));
      }
      if (verifyNew) {
        const next = await json(join(root, 'new-attachment-ref.json'));
        const more = []; for await (const chunk of store.readFileStream(next)) more.push(chunk);
        assert.deepEqual(Buffer.concat(more), bytes);
        const nextImage = await json(join(root, 'new-image-ref.json'));
        assert.equal(sha((await store.readImage(nextImage)).data), await readFile(join(root, 'image-data.sha256'), 'utf8'));
      }
    }
  } finally { await attachmentContext.fiber.dispose(); }

  const credentialsContext = new Context();
  try {
    await credentialsContext.plugin(LocalCredentialProvider, { dshHome: join(root, 'home'), watch: false });
    const provider = credentialsContext.credentials;
    if (initial) await provider.set('COMPAT_OLD', 'fixture-old-value');
    else {
      assert.equal((await provider.resolve('COMPAT_OLD')).value, 'fixture-old-value');
      if (writingNew) await provider.set('COMPAT_NEW', 'fixture-new-value');
      if (verifyNew) assert.equal((await provider.resolve('COMPAT_NEW')).value, 'fixture-new-value');
    }
  } finally { await credentialsContext.fiber.dispose(); }

  // Read the real profile format. Serialization uses the pinned YAML package;
  // this does not claim a full ConfigEditor/Loader live-write trial.
  const profileDir = join(root, 'home', 'profiles', 'compat');
  const patchPath = join(profileDir, 'cordis.patch.yml');
  if (initial) {
    await mkdir(profileDir, { recursive: true });
    await writeFile(join(profileDir, 'package.json'), JSON.stringify({ private: true, dsh: { profile: { bundles: [] } } }));
    await writeFile(patchPath, stringify([{ insert: [{ id: 'compat-settings', name: 'fixture', config: value }] }]));
  } else {
    const profile = loadProfileDirectory('dsh-compat', profileDir, join(runtime, 'package.json'));
    const entries = composeEntries([profile.patches]);
    assert.deepEqual(entries[0].config, value);
    if (verifyNew) assert.deepEqual(entries[1].config, { ...value, owner: 'new' });
    if (writingNew) await writeFile(patchPath, stringify([...profile.patches,
      { insert: [{ id: 'compat-new-settings', name: 'fixture', config: { ...value, owner: 'new' } }] }]));
  }
  console.log(`PASS ${phase}: storage/sessions/files/credentials/profile-read`);
}

async function tree(root, relative = '') {
  const result = {};
  for (const entry of await readdir(join(root, relative), { withFileTypes: true })) {
    const name = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) Object.assign(result, await tree(root, name));
    else { assert.equal(entry.isFile(), true, 'unexpected nonregular fixture file'); result[name] = sha(await readFile(join(root, name))); }
  }
  return result;
}

const readerPackages = ['dsh-storage', 'dsh-storage-json', 'dsh-storage-domain', 'dsh-session',
  'dsh-session-persistence', 'dsh-session-persistence-jsonl', 'dsh-session-format', 'dsh-session-format-catalog',
  'dsh-session-format-v0-to-v1', 'dsh-session-format-v1-to-v2', 'dsh-session-format-v2-to-v3',
  'dsh-session-format-v3-to-v4', 'dsh-session-projection', 'dsh-session-projection-cache', 'dsh-session-query',
  'dsh-session-query-sqlite', 'dsh-attachment', 'dsh-attachment-local', 'dsh-settings', 'dsh-config-editor',
  'dsh-app-boot', 'dsh-atomic-write', 'dsh-credentials', 'dsh-credentials-local', 'dsh-agent-preset',
  'dsh-workspace', 'dsh-jobs-local', 'dsh-permission-presets', 'dsh-output-retention', 'dsh-home-paths',
  'cordis-plugin-loader'];
async function provenance(runtime) {
  const require = createRequire(join(runtime, 'package.json'));
  const files = {};
  const visit = async (base, output, relative = '') => {
    for (const entry of await readdir(join(base, relative), { withFileTypes: true })) {
      const name = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await visit(base, output, name);
      else if (/\.(?:js|cjs|mjs)$/.test(name)) output[name] = sha(await readFile(join(base, name)));
    }
  };
  for (const name of readerPackages) {
    const base = join(runtime, 'node_modules', '@deepseek-ai', name);
    const current = {};
    await visit(base, current);
    files[name] = current;
  }
  const helpers = {};
  for (const name of ['dsha-runtime-fs', 'dsha-session-compat']) {
    const current = {};
    try { await visit(join(runtime, 'node_modules', name), current); helpers[name] = current; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const dsh = require.resolve('@deepseek-ai/dsh/package.json');
  let markerSha256;
  try { markerSha256 = sha(await readFile(join(runtime, 'dsha-test-runtime.json'))); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  return { version: (await json(dsh)).version, dshPackageSha256: sha(await readFile(dsh)), markerSha256,
    lockSha256: sha(await readFile(join(runtime, 'package-lock.json'))), files, helpers };
}

if (process.argv[2] === '--worker') {
  await worker(resolve(process.argv[3]), resolve(process.argv[4]), process.argv[5]);
} else {
  const args = Object.fromEntries(Array.from({ length: (process.argv.length - 2) / 2 }, (_, i) => [process.argv[2 + i * 2], process.argv[3 + i * 2]]));
  assert(args['--old'] && args['--new'] && args['--report'], 'usage: --old RUNTIME --new RUNTIME --report FILE');
  const oldRuntime = resolve(args['--old']), newRuntime = resolve(args['--new']);
  const root = await mkdtemp(join(tmpdir(), 'dsha-cross-runtime-'));
  const report = { schema: 1, oldRuntime, newRuntime, node: process.version, toolSha256: sha(await readFile(script)), phases: [],
    scope: ['single/per-record JSON storage', 'V4 none/zstd sessions and append', 'V3 none/zstd migration retaining source bytes', 'file attachments and stream', 'native-normalized image attachment write/read',
      'credential references', 'profile YAML read and composition'],
    excluded: ['device data', 'image format/dimension matrix', 'SQLite query rebuild', 'full ConfigEditor live write',
      'every historical session shape', 'business task replay', 'runtime rollback authorization'] };
  try {
    report.inputs = { old: await provenance(oldRuntime), new: await provenance(newRuntime) };
    // 默认边：rc5 正式数据 → 0.2.2-rc.1；旧边须显式传 --old-version/--new-version。
    assert.equal(report.inputs.old.version, args['--old-version'] || '0.2.1-rc.5');
    assert.equal(report.inputs.new.version, args['--new-version'] || '0.2.2-rc.1');
    for (const [phase, runtime] of [['old-write', oldRuntime], ['new-read', newRuntime], ['new-write', newRuntime],
      ['new-reopen', newRuntime], ['old-read-new', oldRuntime]]) {
      const before = phase.includes('read') || phase === 'new-reopen' ? await tree(root) : undefined;
      const run = spawnSync(process.execPath, [script, '--worker', runtime, root, phase], {
        encoding: 'utf8', windowsHide: true, timeout: 120000, env: { ...process.env, DSH_HOME: join(root, 'home') } });
      assert.equal(run.status, 0, `${phase}: ${run.stdout}\n${run.stderr}`);
      if (before) assert.deepEqual(await tree(root), before, `${phase} changed durable source bytes`);
      report.phases.push({ phase, pass: true, stdout: run.stdout.trim(), stderr: run.stderr.trim() });
      console.log(run.stdout.trim());
    }
    assert.deepEqual(await provenance(oldRuntime), report.inputs.old, 'old runtime bytes changed during test');
    assert.deepEqual(await provenance(newRuntime), report.inputs.new, 'new runtime bytes changed during test');
    report.result = 'PASS_FINITE_HOST_CROSS_READ_WRITE';
  } catch (error) { report.result = 'FAIL'; report.error = String(error); throw error; }
  finally {
    await mkdir(dirname(resolve(args['--report'])), { recursive: true });
    await writeFile(resolve(args['--report']), JSON.stringify(report, null, 2) + '\n');
    await rm(root, { recursive: true, force: true });
  }
}
