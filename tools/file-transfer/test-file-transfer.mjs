import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer, request } from 'node:http';
import { mkdtemp, mkdir, open, readFile, writeFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { testRuntime } from '../test-runtime-fixture.mjs';
import { FileTransfers, byteRange, CHUNK_BYTES, MAX_FILE_BYTES } from './file-transfer.js';

const root = path.resolve(import.meta.dirname, '../..');
const runtime = testRuntime('raw');
const require = createRequire(pathToFileURL(path.join(runtime, 'package.json')));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { LocalFileSystem } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-fs-local')));
const fixture = await mkdtemp(path.join(root, 'app/build/file-transfer-host-'));
const sessionsRoot = path.join(fixture, 'sessions'), workspace = path.join(fixture, 'workspace');
await mkdir(sessionsRoot); await mkdir(workspace);
const context = new Context();
const actualFs = new LocalFileSystem(context, { cwd: workspace, diffBasisMaxBytes: 1024 * 1024 });
let reads = 0, largestRead = 0, denied = false, fakeInfo;
const fs = new Proxy(actualFs, { get(target, key) {
    if (key === 'readByteRange') return async (...args) => {
        reads++; largestRead = Math.max(largestRead, args[1].length);
        if (denied) throw Object.assign(new Error('blocked composed read'), { code: 'FS_PERMISSION_DENIED' });
        return target.readByteRange(...args);
    };
    if (key === 'stat') return async (...args) => fakeInfo ?? target.stat(...args);
    return typeof target[key] === 'function' ? target[key].bind(target) : target[key];
} });
const persistence = { config: { root: sessionsRoot }, stat: async id => id === 'stored' ? { header: { cwd: workspace } } : undefined };
const ctx = { fs, connection: { requestRejection: req => req.headers.cookie === 'session=current' ? undefined : 401 },
    sessions: { get: id => id === 'live' ? { header: { cwd: workspace } } : undefined },
    sandboxPolicy: { workspaceRoot: workspace }, get: name => name === 'sessionPersistence' ? persistence : undefined };
let service = new FileTransfers(ctx);
const server = createServer((req, res) => {
    const kind = new URL(req.url, 'http://localhost').pathname.split('/').at(-1);
    if (!['prepare', 'resolve', 'content', 'release'].includes(kind)) { res.writeHead(404); res.end(); return; }
    service.handle(kind, req, res).catch(error => { console.error(error); res.destroy(); });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}/api/dsha/files/`;
const checks = [];
async function check(name, work) { await work(); checks.push(name); console.log('PASS ' + name); }
async function call(kind, body, headers = {}, method) {
    const response = await fetch(base + kind, { method: method ?? (body === undefined ? 'GET' : 'POST'),
        headers: { cookie: 'session=current', ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...headers },
        body: body === undefined ? undefined : JSON.stringify(body) });
    return response;
}
const hash = data => createHash('sha256').update(data).digest('hex');
const name = '报告 100% ＋ sample.bin', source = path.join(workspace, name);
const data = Buffer.alloc(CHUNK_BYTES * 5 + 113);
for (let i = 0; i < data.length; i++) data[i] = (i * 31 + (i >>> 8)) & 255;
await writeFile(source, data);
let artifact;
try {
    await check('current auth and same-origin before source reads', async () => {
        assert.equal((await call('prepare', { sessionId: 'live', path: name }, { cookie: 'session=old' })).status, 401);
        assert.equal((await call('prepare', { sessionId: 'live', path: name }, { origin: 'http://evil.invalid' })).status, 403);
        assert.equal(reads, 0);
    });
    await check('real locked composed fs prepare streams fixed windows and persists no credential', async () => {
        const response = await call('prepare', { sessionId: 'live', path: name });
        assert.equal(response.status, 200); artifact = await response.json();
        assert.equal(artifact.bytes, data.length); assert.equal(artifact.sha256, hash(data));
        assert.equal(artifact.name, name); assert.equal(largestRead, CHUNK_BYTES); assert.match(artifact.id, /^[a-f0-9]{48}$/);
        const serialized = await readFile(path.join(sessionsRoot, '.dsha-file-transfers', artifact.id + '.json'), 'utf8');
        assert(!/cookie|credential|session=current|generation|authorization/i.test(serialized));
    });
    await check('persisted id survives restart and each prepare has a distinct release identity', async () => {
        service.close(); service = new FileTransfers(ctx);
        const response = await call('resolve', artifact); assert.equal(response.status, 200);
        assert.equal((await response.json()).contentUrl, '/api/dsha/files/content?id=' + artifact.id);
        const again = await call('prepare', { sessionId: 'live', path: name });
        assert.notEqual((await again.json()).id, artifact.id);
        assert.equal((await readdir(path.join(sessionsRoot, '.dsha-file-transfers'))).filter(f => f.endsWith('.json')).length, 2);
    });
    await check('real HTTP resume range and strong ETag exact bytes', async () => {
        const response = await call('content?id=' + artifact.id, undefined, { range: 'bytes=19-900001', 'if-range': '"' + artifact.sha256 + '"' });
        assert.equal(response.status, 206); assert.equal(response.headers.get('etag'), '"' + artifact.sha256 + '"');
        assert.equal(response.headers.get('content-range'), `bytes 19-900001/${data.length}`);
        assert.deepEqual(Buffer.from(await response.arrayBuffer()), data.subarray(19, 900002));
        const suffix = await call('content?id=' + artifact.id, undefined, { range: 'bytes=-123' });
        assert.equal(suffix.status, 206); assert.deepEqual(Buffer.from(await suffix.arrayBuffer()), data.subarray(-123));
    });
    await check('stale If-Range restarts full and HEAD carries exact complete metadata', async () => {
        const response = await call('content?id=' + artifact.id, undefined, { range: 'bytes=999-', 'if-range': '"old"' });
        assert.equal(response.status, 200); assert.deepEqual(Buffer.from(await response.arrayBuffer()), data);
        const head = await call('content?id=' + artifact.id, undefined, {}, 'HEAD');
        assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), String(data.length));
        assert.equal((await head.arrayBuffer()).byteLength, 0);
    });
    await check('multi-range outside range and traversal ids rejected', async () => {
        const response = await call('content?id=' + artifact.id, undefined, { range: 'bytes=0-1,4-5' });
        assert.equal(response.status, 416); assert.equal(response.headers.get('content-range'), `bytes */${data.length}`);
        assert.equal((await call('content?id=../../secret')).status, 400);
        assert.equal((await call('content?id=' + 'a'.repeat(48))).status, 404);
        assert.deepEqual(byteRange('bytes=4294967296-4999999999', undefined, MAX_FILE_BYTES, '"x"'), { start: 4294967296, end: 4999999999, partial: true });
    });
    await check('metadata mismatch and composed provider read denial fail closed', async () => {
        assert.equal((await call('resolve', { ...artifact, bytes: 1 })).status, 409);
        denied = true; assert.equal((await call('resolve', artifact)).status, 403); denied = false;
    });
    await check('actual session lookup absent denied; stored session resolved', async () => {
        assert.equal((await call('prepare', { sessionId: 'unknown', path: name })).status, 404);
        assert.equal((await call('prepare', { sessionId: 'stored', path: name })).status, 200);
        assert.equal((await call('prepare', { sessionId: 'live', path: '.' })).status, 403);
    });
    await check('5000000000 inclusive cap accepts metadata; +1 refuses before any read', async () => {
        service.checkInfo({ type: 'file', size: MAX_FILE_BYTES, version: 'bounded' });
        const count = reads; fakeInfo = { type: 'file', size: MAX_FILE_BYTES + 1, version: 'bounded' };
        assert.equal((await call('prepare', { sessionId: 'live', path: name })).status, 413);
        assert.equal(reads, count); fakeInfo = undefined;
    });
    await check('bounded JSON malformed and oversized bodies produce concrete errors', async () => {
        assert.equal((await call('prepare', { path: 'x'.repeat(17000), sessionId: 'live' })).status, 413);
        assert.equal((await fetch(base + 'prepare', { method: 'POST', headers: { cookie: 'session=current' }, body: '{' })).status, 400);
        assert.equal((await call('prepare', undefined)).status, 405);
    });
    await check('release is authenticated; late release cannot close a new prepare identity', async () => {
        assert.equal((await call('release', { id: artifact.id }, { cookie: 'old' })).status, 401);
        assert.equal((await call('release', { id: artifact.id })).status, 200);
        assert.equal(await service.isClosed(artifact.id, await service.storeRoot()), true);
        assert.equal((await call('resolve', artifact)).status, 410);
        const next = await (await call('prepare', { sessionId: 'live', path: name })).json();
        assert.notEqual(next.id, artifact.id);
        assert.equal((await call('release', { id: artifact.id })).status, 200);
        assert.equal((await call('resolve', next)).status, 200);
        assert.equal(await service.isClosed(next.id, await service.storeRoot()), false);
        artifact = next;
    });
    await check('Unicode basename truncates at UTF-8 boundary while preserving extension', async () => {
        const long = '报'.repeat(80) + '.png'; await writeFile(path.join(workspace, long), 'bounded');
        const response = await call('prepare', { sessionId: 'live', path: long }); assert.equal(response.status, 200);
        const value = await response.json(); assert(Buffer.byteLength(value.name) <= 240); assert(value.name.endsWith('.png'));
        assert.equal(value.mime, 'image/png'); assert(!value.name.includes('\ufffd'));
    });
    await check('zero-byte source has exact digest and rejects unsatisfiable byte range', async () => {
        await writeFile(path.join(workspace, 'empty.bin'), Buffer.alloc(0));
        const response = await call('prepare', { sessionId: 'live', path: 'empty.bin' }); assert.equal(response.status, 200);
        const value = await response.json(); assert.equal(value.bytes, 0); assert.equal(value.sha256, hash(Buffer.alloc(0)));
        const full = await call('content?id=' + value.id); assert.equal(full.status, 200); assert.equal((await full.arrayBuffer()).byteLength, 0);
        assert.equal((await call('content?id=' + value.id, undefined, { range: 'bytes=0-' })).status, 416);
    });
    await check('same size source replacement refuses old descriptor and creates new id', async () => {
        await writeFile(source, Buffer.alloc(data.length, 22));
        assert.equal((await call('resolve', artifact)).status, 409);
        assert.equal((await call('content?id=' + artifact.id)).status, 409);
        const next = await call('prepare', { sessionId: 'live', path: name }); assert.equal(next.status, 200);
        assert.notEqual((await next.json()).id, artifact.id);
    });
    await check('disconnect cancels producer and plugin disposal drains active operations', async () => {
        await writeFile(source, data);
        const response = await call('prepare', { sessionId: 'live', path: name }); const current = await response.json();
        await new Promise((resolve, reject) => {
            const req = request(base + 'content?id=' + current.id, { headers: { cookie: 'session=current' } }, res => {
                res.once('data', () => { res.destroy(); req.destroy(); resolve(); });
            }); req.on('error', reject); req.end();
        });
        for (let i = 0; service.active.size && i < 50; i++) await new Promise(resolve => setTimeout(resolve, 10));
        assert.equal(service.active.size, 0);
        service.close(); assert.equal((await call('resolve', current)).status, 503); service = new FileTransfers(ctx);
    });
    await check('1024 pending manifests preserved; only explicit closed descriptor retired', async () => {
        const original = await service.storeRoot();
        const model = JSON.parse(await readFile(path.join(original, artifact.id + '.json'), 'utf8'));
        const capRoot = path.join(fixture, 'cap-sessions'); await mkdir(capRoot);
        const capService = new FileTransfers({ ...ctx, get: name => name === 'sessionPersistence' ? { ...persistence, config: { root: capRoot } } : undefined });
        const dir = await capService.storeRoot();
        await writeFile(path.join(dir, artifact.id + '.json'), JSON.stringify(model));
        let last;
        for (let i = 1; i < 1024; i++) {
            const id = i.toString(16).padStart(48, '0');
            await writeFile(path.join(dir, id + '.json'), JSON.stringify({ ...model, id })); last = id;
        }
        const next = { ...model, id: 'f'.repeat(48) }, signal = new AbortController().signal;
        await assert.rejects(capService.save(next, signal), error => error.status === 429 && error.code === 'pending-source-limit');
        await capService.release({ id: last }, signal); await capService.save(next, signal);
        await assert.rejects(capService.load(last), error => error.status === 404);
        assert.equal((await capService.load(artifact.id)).id, artifact.id);
        assert.equal((await readdir(dir)).filter(name => name.endsWith('.json')).length, 1024);
    });
    const result = { status: 'PASS', checks, fixture, runtime, largestRead, reads,
        serverSourceSha256: hash(await readFile(path.join(import.meta.dirname, 'file-transfer.js'))) };
    const proof = process.argv.find(value => value.startsWith('--output='))?.slice('--output='.length);
    if (proof) { await mkdir(path.dirname(path.resolve(proof)), { recursive: true }); await writeFile(proof, JSON.stringify(result, null, 2) + '\n'); }
    console.log(JSON.stringify(result));
} finally {
    service.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await context.fiber.dispose();
}
