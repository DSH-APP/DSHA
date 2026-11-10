/** Explicit opt-in: reuse a real uploaded 5 GB file; never regenerate its bytes. */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, open, writeFile, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { testRuntime } from '../test-runtime-fixture.mjs';
import { FileTransfers, CHUNK_BYTES, MAX_FILE_BYTES } from './file-transfer.js';

const args = Object.fromEntries(process.argv.slice(2).map(value => { const split = value.indexOf('='); return [value.slice(0, split), value.slice(split + 1)]; }));
assert(args['--source'] && args['--output-dir'] && /^[a-f0-9]{64}$/.test(args['--sha256'] ?? ''), 'explicit --source= --output-dir= --sha256= required');
const started = performance.now(), source = path.resolve(args['--source']), outputDir = path.resolve(args['--output-dir']);
assert.equal((await stat(source)).size, MAX_FILE_BYTES);
const runtime = testRuntime('raw');
const require = createRequire(pathToFileURL(path.join(runtime, 'package.json')));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { LocalFileSystem } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-fs-local')));
await mkdir(outputDir, { recursive: true });
const fixture = await mkdtemp(path.join(outputDir, 'download-')), sessionsRoot = path.join(fixture, 'sessions');
await mkdir(sessionsRoot);
const context = new Context(), real = new LocalFileSystem(context, { cwd: path.dirname(source), diffBasisMaxBytes: 1048576 });
let readCalls = 0, readBytes = 0, maxWindow = 0, credential = 'current-1';
const fs = new Proxy(real, { get(target, key) {
    if (key === 'readByteRange') return async (...args) => {
        readCalls++; maxWindow = Math.max(maxWindow, args[1].length);
        const data = await target.readByteRange(...args); readBytes += data.length; return data;
    };
    return typeof target[key] === 'function' ? target[key].bind(target) : target[key];
} });
const persistence = { config: { root: sessionsRoot }, stat: async id => id === 'large' ? { header: { cwd: path.dirname(source) } } : undefined };
const ctx = { fs, connection: { requestRejection: req => req.headers.cookie === 'session=' + credential ? undefined : 401 },
    sessions: { get: () => undefined }, sandboxPolicy: { workspaceRoot: path.dirname(source) },
    get: name => name === 'sessionPersistence' ? persistence : undefined };
let service = new FileTransfers(ctx);
const server = createServer((req, res) => service.handle(new URL(req.url, 'http://dsha.invalid').pathname.split('/').at(-1), req, res));
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}/api/dsha/files/`;
async function post(kind, body, cookie = credential) {
    return fetch(base + kind, { method: 'POST', headers: { cookie: 'session=' + cookie, 'content-type': 'application/json' }, body: JSON.stringify(body) });
}
let artifact, written = 0;
const output = path.join(fixture, 'downloaded-boundary.bin');
const target = await open(output, 'wx');
const ranges = [], digest = createHash('sha256');
try {
    const prepared = await post('prepare', { sessionId: 'large', path: source });
    assert.equal(prepared.status, 200); artifact = await prepared.json();
    assert.equal(artifact.bytes, MAX_FILE_BYTES); assert.equal(artifact.sha256, args['--sha256']);
    console.log('PASS real 5000000000-byte source prepared through locked fs, sha256=' + artifact.sha256);
    for (const end of [123_456_788, 4_294_967_295, MAX_FILE_BYTES - 1]) {
        const start = written, response = await fetch(base + 'content?id=' + artifact.id, {
            headers: { cookie: 'session=' + credential, range: `bytes=${start}-${end}`, 'if-range': '"' + artifact.sha256 + '"' } });
        assert.equal(response.status, 206); assert.equal(response.headers.get('content-range'), `bytes ${start}-${end}/${MAX_FILE_BYTES}`);
        assert.equal(response.headers.get('etag'), '"' + artifact.sha256 + '"');
        assert.equal(response.headers.get('content-length'), String(end - start + 1));
        let bytes = 0;
        for await (const value of response.body) {
            const data = Buffer.from(value.buffer, value.byteOffset, value.byteLength);
            for (let offset = 0; offset < data.length;) {
                const result = await target.write(data, offset, data.length - offset, null); assert(result.bytesWritten > 0); offset += result.bytesWritten;
            }
            digest.update(data); bytes += data.length; written += data.length;
        }
        assert.equal(bytes, end - start + 1); await target.sync(); ranges.push({ start, end, status: response.status, bytes });
        console.log(`PASS real HTTP bytes ${start}-${end}, file size=${written}`);
        if (written === 123_456_789) {
            service.close(); service = new FileTransfers(ctx); credential = 'current-2';
            assert.equal((await post('resolve', artifact, 'current-1')).status, 401);
            const resumed = await post('resolve', artifact); assert.equal(resumed.status, 200);
            const resolved = await resumed.json();
            for (const key of ['id', 'version', 'sha256', 'bytes']) assert.equal(resolved[key], artifact[key]);
            console.log('PASS service restart re-read persisted manifest, rejected old credential and rehashed source with fresh auth');
        }
    }
    await target.close();
    assert.equal(written, MAX_FILE_BYTES); assert.equal((await stat(output)).size, MAX_FILE_BYTES);
    const transmittedSha = digest.digest('hex'); assert.equal(transmittedSha, args['--sha256']);
    const diskHash = createHash('sha256'); let diskBytes = 0;
    for await (const chunk of createReadStream(output, { highWaterMark: CHUNK_BYTES })) { diskBytes += chunk.length; diskHash.update(chunk); }
    const diskSha256 = diskHash.digest('hex'); assert.equal(diskBytes, MAX_FILE_BYTES); assert.equal(diskSha256, transmittedSha);
    const released = await post('release', { id: artifact.id }); assert.equal(released.status, 200);
    const proof = { status: 'PASS', scope: 'host real locked composed fs + authenticated HTTP Range + real disk output; no Android device evidence',
        runtime, source, output, artifact, transmittedBytes: written, transmittedSha256: transmittedSha, diskReadBytes: diskBytes, diskSha256,
        resumeOffset: 123456789, beyondUint32RangeOffset: 4294967296, ranges, sourceProviderReadBytes: readBytes, readCalls, maxWindow,
        elapsedSeconds: (performance.now() - started) / 1000 };
    await writeFile(path.join(outputDir, 'download-result.json'), JSON.stringify(proof, null, 2) + '\n');
    console.log(JSON.stringify(proof));
} finally {
    await target.close().catch(() => {}); service.close(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve)); await context.fiber.dispose();
}
