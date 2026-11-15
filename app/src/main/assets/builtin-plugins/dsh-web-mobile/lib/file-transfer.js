/** DSHA durable download sources. Source reads ALWAYS use the composed ctx.fs.
 * The only node:fs use below is our small, private manifest store; no source
 * processPath or targetKey is passed to a host filesystem primitive. */
import { constants } from 'node:fs';
import { open, mkdir, lstat, readdir, unlink } from 'node:fs/promises';
import { join, posix, win32 } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';

export const MAX_FILE_BYTES = 5_000_000_000;
export const CHUNK_BYTES = 256 * 1024;
const MAX_BODY_BYTES = 16 * 1024;
const MAX_MANIFESTS = 1024;
const ID = /^[a-f0-9]{48}$/;
const HASH = /^[a-f0-9]{64}$/;
const BASE = '/api/dsha/files/';
const PRIVATE_HEADERS = { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' };

class TransferError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const fail = (status, code, message) => { throw new TransferError(status, code, message); };
function text(value, name, max = 4096) {
    if (typeof value !== 'string' || !value || value.length > max || /[\0\r\n]/.test(value))
        fail(400, 'invalid-request', `${name} must be a non-empty bounded string`);
    return value;
}
function sameOrigin(req) {
    const origin = req.headers.origin;
    if (origin === undefined || origin === '') return true;
    try { return new URL(origin).host === req.headers.host; } catch { return false; }
}
function json(req, res, status, body, headers = {}) {
    const payload = JSON.stringify(body);
    res.writeHead(status, { ...PRIVATE_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(payload), ...headers });
    res.end(req.method === 'HEAD' ? undefined : payload);
}
async function readBody(req, signal) {
    return new Promise((resolve, reject) => {
        let bytes = 0, chunks = [], tooLarge = false;
        const timer = setTimeout(() => req.destroy(new Error('request body timeout')), 15_000);
        timer.unref?.();
        const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); };
        const abort = () => { cleanup(); reject(signal.reason); };
        signal.addEventListener('abort', abort, { once: true });
        req.on('data', chunk => {
            bytes += chunk.length;
            if (bytes > MAX_BODY_BYTES) { tooLarge = true; chunks = []; }
            if (!tooLarge) chunks.push(chunk);
        });
        // Drain oversized bodies without retaining them so the 413 reaches the caller.
        req.once('end', () => {
            cleanup();
            if (tooLarge) { reject(new TransferError(413, 'payload-too-large', 'request body exceeds 16384 bytes')); return; }
            try {
                const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
                resolve(value);
            } catch { reject(new TransferError(400, 'invalid-body', 'expected a JSON object')); }
        });
        req.once('error', error => { cleanup(); reject(error); });
        if (signal.aborted) abort();
    });
}
function safeName(path) {
    const value = win32.basename(posix.basename(path)).replace(/[\x00-\x1f\x7f]/g, '_').toWellFormed();
    if (value === '.' || value === '..' || !value.trim()) return 'download';
    const cut = (input, limit) => {
        let result = '', bytes = 0;
        for (const char of input) { const size = Buffer.byteLength(char); if (bytes + size > limit) break; result += char; bytes += size; }
        return result;
    };
    if (Buffer.byteLength(value) <= 240) return value;
    const dot = value.lastIndexOf('.'), extension = dot > 0 ? value.slice(dot) : '';
    if (extension && Buffer.byteLength(extension) < 240)
        return cut(value.slice(0, dot), 240 - Buffer.byteLength(extension)) + extension;
    return cut(value, 240);
}
function mimeOf(name) {
    const ext = name.split('.').at(-1).toLowerCase();
    return ({ png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
        heic: 'image/heic', avif: 'image/avif', svg: 'image/svg+xml', mp4: 'video/mp4', webm: 'video/webm',
        mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', pdf: 'application/pdf', txt: 'text/plain',
        json: 'application/json', zip: 'application/zip', gz: 'application/gzip', csv: 'text/csv' })[ext]
        ?? 'application/octet-stream';
}
function publicManifest(record) {
    const { id, name, mime, bytes, sha256, version } = record;
    return { id, name, mime, bytes, sha256, version };
}
function requireManifest(record, id) {
    if (!record || record.schema !== 1 || record.id !== id || !ID.test(id)
        || !HASH.test(record.sha256) || !Number.isSafeInteger(record.bytes) || record.bytes < 0 || record.bytes > MAX_FILE_BYTES)
        fail(409, 'manifest-invalid', 'download manifest is invalid');
    for (const key of ['sessionId', 'path', 'workspaceRoot', 'targetKey', 'version', 'name', 'mime']) text(record[key], key);
    text(record.version, 'version', 256);
    return record;
}

/** Parse precisely one RFC byte range. A stale If-Range deliberately yields 200. */
export function byteRange(range, ifRange, bytes, etag) {
    if (range === undefined || (ifRange !== undefined && ifRange !== etag)) return { start: 0, end: bytes - 1, partial: false };
    const match = typeof range === 'string' && /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!match || (!match[1] && !match[2]) || bytes === 0) fail(416, 'invalid-range', 'one satisfiable byte range is required');
    let start, end;
    if (!match[1]) {
        const suffix = Number(match[2]);
        if (!Number.isSafeInteger(suffix) || suffix <= 0) fail(416, 'invalid-range', 'invalid suffix range');
        start = Math.max(0, bytes - suffix); end = bytes - 1;
    } else {
        start = Number(match[1]); end = match[2] ? Number(match[2]) : bytes - 1;
        if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= bytes || end < start)
            fail(416, 'invalid-range', 'byte range is outside the file');
        end = Math.min(end, bytes - 1);
    }
    return { start, end, partial: true };
}

/** A source never becomes readable merely because a previous generation saved its manifest. */
export class FileTransfers {
    constructor(ctx) { this.ctx = ctx; this.active = new Set(); this.inUse = new Map(); this.closed = false; this.saving = Promise.resolve(); }
    close() { this.closed = true; for (const controller of this.active) controller.abort(); }
    async storeRoot() {
        const root = this.ctx.get('sessionPersistence')?.config?.root;
        if (typeof root !== 'string' || !root) fail(503, 'persistence-unavailable', 'session storage is unavailable');
        const parent = await lstat(root);
        if (!parent.isDirectory() || parent.isSymbolicLink()) fail(503, 'manifest-store-invalid', 'session storage root is not a private directory');
        const dir = join(root, '.dsha-file-transfers');
        await mkdir(dir, { recursive: true, mode: 0o700 });
        const info = await lstat(dir);
        if (!info.isDirectory() || info.isSymbolicLink()) fail(503, 'manifest-store-invalid', 'private manifest store is unavailable');
        return dir;
    }
    async save(record, signal) {
        // Serialize the member cap and exclusive create, including concurrent prepares.
        const work = this.saving.then(async () => {
            signal.throwIfAborted();
            const dir = await this.storeRoot();
            const file = join(dir, record.id + '.json');
            const members = (await readdir(dir)).filter(name => /^[a-f0-9]{48}\.json$/.test(name));
            if (members.length >= MAX_MANIFESTS) {
                // Only explicitly released descriptors are eligible. Prepare already
                // reserves an open source: native admission can pause before resolve.
                // Pending, paused and failed downloads are never rotated by count.
                const candidates = [];
                for (const member of members) {
                    const id = member.slice(0, -5);
                    if (this.inUse.has(id)) continue;
                    try {
                        await this.load(id);
                        const marker = await this.isClosed(id, dir) ? '.closed' : undefined;
                        if (marker) candidates.push({ id, marker, time: (await lstat(join(dir, id + marker))).mtimeMs });
                    }
                    catch { /* Unknown/corrupt originals are never deleted. */ }
                }
                candidates.sort((a, b) => a.time - b.time);
                const retired = candidates.find(row => !this.inUse.has(row.id));
                if (!retired) fail(429, 'pending-source-limit', '1024 download sources are pending; cancel an unused download before preparing another');
                await unlink(join(dir, retired.id + '.json'));
                await unlink(join(dir, retired.id + retired.marker));
            }
            const handle = await open(file, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0), 0o600);
            try { await handle.writeFile(JSON.stringify(record) + '\n'); await handle.sync(); }
            catch (error) { await handle.close(); await unlink(file).catch(() => {}); throw error; }
            await handle.close();
        });
        this.saving = work.catch(() => {});
        return work;
    }
    async load(id) {
        if (typeof id !== 'string' || !ID.test(id)) fail(400, 'invalid-id', 'invalid download source id');
        let handle;
        try {
            const file = join(await this.storeRoot(), id + '.json');
            const info = await lstat(file);
            if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_BODY_BYTES) fail(409, 'manifest-invalid', 'download manifest is invalid');
            handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
            const opened = await handle.stat();
            if (!opened.isFile() || opened.ino !== info.ino || opened.dev !== info.dev || opened.size !== info.size)
                fail(409, 'manifest-invalid', 'download manifest changed');
            const buffer = Buffer.alloc(MAX_BODY_BYTES + 1);
            const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
            if (bytesRead > MAX_BODY_BYTES) fail(409, 'manifest-invalid', 'download manifest exceeds limit');
            const record = requireManifest(JSON.parse(buffer.subarray(0, bytesRead).toString('utf8')), id);
            return record;
        } catch (error) {
            if (error.code === 'ENOENT') fail(404, 'source-not-found', 'download source is no longer available');
            if (error instanceof SyntaxError) fail(409, 'manifest-invalid', 'download manifest is incomplete');
            throw error;
        } finally { await handle?.close(); }
    }
    async isClosed(id, dir) {
        return this.isMarker(id, dir, '.closed', 'closed\n');
    }
    async isMarker(id, dir, suffix, value) {
        let handle;
        try {
            const file = join(dir, id + suffix), info = await lstat(file);
            if (!info.isFile() || info.isSymbolicLink() || info.size !== value.length) return false;
            handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
            const opened = await handle.stat();
            if (opened.dev !== info.dev || opened.ino !== info.ino || opened.size !== info.size) return false;
            const data = Buffer.alloc(value.length + 1), read = await handle.read(data, 0, data.length, 0);
            return read.bytesRead === value.length && data.subarray(0, value.length).toString() === value;
        } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
        finally { await handle?.close(); }
    }
    async mark(id, dir, suffix, value) {
        let handle;
        try {
            handle = await open(join(dir, id + suffix), constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0), 0o600);
            await handle.writeFile(value); await handle.sync();
        } catch (error) { if (error.code !== 'EEXIST' || !await this.isMarker(id, dir, suffix, value)) throw error; }
        finally { await handle?.close(); }
    }
    async requireOpen(id) {
        const dir = await this.storeRoot();
        if (await this.isClosed(id, dir)) fail(410, 'source-released', 'download source was released; prepare a new download');
    }
    async release(body, signal) {
        return this.withSource(body.id, async () => {
            await this.load(body.id);
            signal.throwIfAborted();
            const dir = await this.storeRoot();
            if (!await this.isClosed(body.id, dir)) {
                await this.mark(body.id, dir, '.closed', 'closed\n');
            }
            return { ok: true };
        });
    }
    async scope(sessionId) {
        text(sessionId, 'sessionId', 512);
        const live = this.ctx.sessions.get(sessionId)?.header;
        const stored = live === undefined ? await this.ctx.get('sessionPersistence')?.stat(sessionId) : undefined;
        const header = live ?? stored?.header;
        if (header === undefined) fail(404, 'session-not-found', 'session is no longer available');
        return text(header.cwd ?? this.ctx.sandboxPolicy.workspaceRoot, 'workspaceRoot');
    }
    async locate(sessionId, path, signal) {
        text(path, 'path');
        const workspaceRoot = await this.scope(sessionId), fs = this.ctx.fs;
        const entry = await fs.lstat(path, { cwd: workspaceRoot }, signal);
        if (entry === undefined) fail(404, 'source-not-found', 'download source is no longer available');
        if (entry.type !== 'file') fail(403, 'not-regular-file', 'download source must be a regular file');
        const target = await fs.resolve(path, { cwd: workspaceRoot, signal });
        const info = await fs.stat(target, signal);
        this.checkInfo(info);
        return { target, info, workspaceRoot };
    }
    checkInfo(info) {
        if (info === undefined) fail(404, 'source-not-found', 'download source is no longer available');
        if (info.type !== 'file') fail(403, 'not-regular-file', 'download source must be a regular file');
        if (!Number.isSafeInteger(info.size) || info.size < 0) fail(409, 'size-unavailable', 'download source size is unavailable');
        if (info.size > MAX_FILE_BYTES) fail(413, 'file-too-large', 'single file exceeds 5000000000 bytes');
        text(info.version, 'version', 256);
    }
    async unchanged(target, expected, signal) {
        signal.throwIfAborted();
        const info = await this.ctx.fs.stat(target, signal);
        this.checkInfo(info);
        if (info.version !== expected.version || info.size !== expected.bytes)
            fail(409, 'source-changed', 'download source changed; prepare a new download');
    }
    async *chunks(target, record, start, end, signal) {
        for (let offset = start; offset <= end;) {
            await this.unchanged(target, record, signal);
            const length = Math.min(CHUNK_BYTES, end - offset + 1);
            const data = await this.ctx.fs.readByteRange(target, { offset, length }, signal);
            signal.throwIfAborted();
            if (!(data instanceof Uint8Array) || data.byteLength !== length)
                fail(409, 'source-changed', 'download source returned an incomplete range');
            await this.unchanged(target, record, signal);
            offset += data.byteLength;
            yield data;
        }
        await this.unchanged(target, record, signal);
    }
    async digest(target, record, signal) {
        const hash = createHash('sha256');
        for await (const chunk of this.chunks(target, record, 0, record.bytes - 1, signal)) hash.update(chunk);
        return hash.digest('hex');
    }
    async prepare(body, signal) {
        const { sessionId, path } = body, source = await this.locate(sessionId, path, signal);
        const record = { schema: 1, id: randomBytes(24).toString('hex'), sessionId, path,
            workspaceRoot: source.workspaceRoot, targetKey: source.target.targetKey, version: source.info.version,
            bytes: source.info.size, name: safeName(path), createdAt: new Date().toISOString() };
        record.mime = mimeOf(record.name);
        record.sha256 = await this.digest(source.target, record, signal);
        signal.throwIfAborted();
        await this.save(requireManifest(record, record.id), signal);
        if (signal.aborted) {
            const dir = await this.storeRoot();
            // This unique id has not left prepare, so no native task can own it.
            await unlink(join(dir, record.id + '.json'));
            signal.throwIfAborted();
        }
        return publicManifest(record);
    }
    async source(record, signal) {
        const source = await this.locate(record.sessionId, record.path, signal);
        if (source.workspaceRoot !== record.workspaceRoot || source.target.targetKey !== record.targetKey)
            fail(409, 'source-changed', 'download source identity changed');
        await this.unchanged(source.target, record, signal);
        return source.target;
    }
    async resolve(body, signal) {
        return this.withSource(body.id, async () => {
        const record = await this.load(body.id);
        if (body.sha256 !== record.sha256 || body.bytes !== record.bytes || (body.version !== undefined && body.version !== record.version))
            fail(409, 'manifest-mismatch', 'download metadata does not match the saved source');
        await this.requireOpen(record.id);
        const target = await this.source(record, signal);
        if (await this.digest(target, record, signal) !== record.sha256) fail(409, 'source-changed', 'download source digest changed');
        return { ...publicManifest(record), contentUrl: BASE + 'content?id=' + record.id };
        });
    }
    async withSource(id, work) {
        if (typeof id !== 'string' || !ID.test(id)) fail(400, 'invalid-id', 'invalid download source id');
        this.inUse.set(id, (this.inUse.get(id) ?? 0) + 1);
        try { return await work(); }
        finally { const remaining = this.inUse.get(id) - 1; if (remaining) this.inUse.set(id, remaining); else this.inUse.delete(id); }
    }
    async content(req, res, id, signal) {
        const record = await this.load(id);
        await this.requireOpen(id);
        const target = await this.source(record, signal);
        const etag = '"' + record.sha256 + '"';
        let range;
        try { range = byteRange(req.headers.range, req.headers['if-range'], record.bytes, etag); }
        catch (error) { if (error.status === 416) error.headers = { 'Content-Range': `bytes */${record.bytes}` }; throw error; }
        const { start, end, partial } = range;
        res.writeHead(partial ? 206 : 200, { ...PRIVATE_HEADERS, 'Accept-Ranges': 'bytes', ETag: etag,
            'Content-Type': record.mime, 'Content-Length': Math.max(0, end - start + 1),
            'Content-Disposition': `attachment; filename="download"; filename*=UTF-8''${encodeURIComponent(record.name).replace(/'/g, '%27')}`,
            'Content-Security-Policy': "sandbox; default-src 'none'",
            ...(partial ? { 'Content-Range': `bytes ${start}-${end}/${record.bytes}` } : {}) });
        if (req.method === 'HEAD') { res.end(); return; }
        const hash = partial ? undefined : createHash('sha256');
        for await (const data of this.chunks(target, record, start, end, signal)) {
            hash?.update(data);
            if (!res.write(data)) await new Promise((resolve, reject) => {
                const done = error => { res.off('drain', drain); signal.removeEventListener('abort', abort); error ? reject(error) : resolve(); };
                const drain = () => done();
                const abort = () => done(signal.reason ?? new Error('download cancelled'));
                res.once('drain', drain); signal.addEventListener('abort', abort, { once: true });
                if (signal.aborted) abort();
            });
        }
        if (hash && hash.digest('hex') !== record.sha256) fail(409, 'source-changed', 'download source digest changed');
        res.end();
    }
    async handle(kind, req, res) {
        const rejection = this.ctx.connection.requestRejection(req);
        if (rejection !== undefined) { json(req, res, rejection, { error: { code: 'access-denied', message: 'authentication required' } }); return; }
        if (!sameOrigin(req)) { json(req, res, 403, { error: { code: 'cross-origin', message: 'cross-origin request rejected' } }); return; }
        const methods = kind === 'content' ? ['GET', 'HEAD'] : ['POST'];
        if (!methods.includes(req.method)) { json(req, res, 405, { error: { code: 'method-not-allowed', message: methods.join(' or ') + ' required' } }, { Allow: methods.join(', ') }); return; }
        if (this.closed || this.active.size >= 2) { json(req, res, 503, { error: { code: 'transfer-busy', message: 'download service is busy; retry later' } }); return; }
        const controller = new AbortController(), signal = controller.signal;
        const abort = () => controller.abort(new Error('download cancelled'));
        const closeResponse = () => { if (!res.destroyed && !res.writableEnded) res.destroy(); };
        signal.addEventListener('abort', closeResponse, { once: true });
        this.active.add(controller); req.once('aborted', abort); res.once('close', abort);
        try {
            if (kind === 'content') {
                const id = new URL(req.url, 'http://dsha.invalid').searchParams.get('id');
                await this.withSource(id, () => this.content(req, res, id, signal));
            }
            else json(req, res, 200, await this[kind](await readBody(req, signal), signal));
        } catch (error) {
            if (signal.aborted || res.destroyed) return;
            if (res.headersSent) { res.destroy(); return; }
            const fsStatus = { FS_NOT_FOUND: 404, FS_NOT_REGULAR_FILE: 403, FS_PERMISSION_DENIED: 403,
                FS_SANDBOX_DENIED: 403, FS_TOO_LARGE: 413, FS_STALE_VERSION: 409, FS_ABORTED: 499 }[error.code];
            json(req, res, error.status ?? fsStatus ?? 500, { error: {
                code: error instanceof TransferError ? error.code : fsStatus ? error.code : 'transfer-failed',
                message: error instanceof TransferError ? error.message : 'download source could not be read' } }, error.headers);
        } finally { this.active.delete(controller); req.off('aborted', abort); res.off('close', abort); signal.removeEventListener('abort', closeResponse); }
    }
}

export function installFileTransfers(ctx) {
    ctx.inject(['webServer', 'connection', 'fs', 'sandboxPolicy', 'sessions'], scope => {
        const transfers = new FileTransfers(scope);
        scope.effect(() => () => transfers.close(), 'dsh-web-mobile: download cancellation');
        for (const kind of ['prepare', 'resolve', 'content', 'release']) scope.effect(() => scope.webServer.register({
            kind: 'exact', path: BASE + kind, handler: (req, res) => transfers.handle(kind, req, res),
        }), 'dsh-web-mobile: download ' + kind);
    });
}
