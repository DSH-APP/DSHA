// Real rc3 WebServer sockets, with the same explicit gzip configuration as CLI.
import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { brotliDecompressSync, gunzipSync } from 'node:zlib';
import { testRuntime } from './test-runtime-fixture.mjs';
import { installResponseCompression } from './mobile-server/compress.js';

const runtime = testRuntime('raw');
const require = createRequire(path.join(runtime, 'package.json'));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { default: WebServer } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-host-webserver')));
const small = JSON.stringify({ id: 'a'.repeat(48), name: 'image-sample.png', mime: 'image/png', bytes: 2600000,
    sha256: 'b'.repeat(64), version: 'stat-version' });
const large = JSON.stringify({ text: '中文-carrier-'.repeat(12000) });
const methodNotAllowed = JSON.stringify({ ok: false, error: 'method-not-allowed' });
const binary = Buffer.alloc(2_600_000, 0xa5);

function call(port, pathname, method = 'GET', headers = {}) {
    return new Promise((resolve, reject) => {
        const req = request({ hostname: '127.0.0.1', port, path: pathname, method,
            headers: { 'Accept-Encoding': 'gzip', ...headers } }, res => {
            const chunks = [];
            res.on('data', part => chunks.push(part));
            res.once('error', reject);
            res.once('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
        });
        req.setTimeout(5000, () => req.destroy(new Error('carrier request timed out')));
        req.once('error', reject); req.end();
    });
}
function decoded(result) {
    if (result.headers['content-encoding'] === 'gzip') return gunzipSync(result.body);
    if (result.headers['content-encoding'] === 'br') return brotliDecompressSync(result.body);
    return result.body;
}

for (const compression of ['none', 'gzip']) test(`DSHA and actual rc3 WebServer ${compression} preserve complete responses and streams`, async () => {
    const ctx = new Context(), errors = [];
    // CLI resolves these defaults before constructing WebServer. Passing only
    // host/port to Context.plugin silently omits the official gzip middleware.
    await ctx.plugin(WebServer, { host: '127.0.0.1', port: 0, compression,
        compressionLevel: 4, compressionThresholdBytes: 1024 });
    const dispose = installResponseCompression({ matches: req => req.socket.localPort === ctx.webServer.port });
    const routes = {
        '/prepare': [200, small], '/empty': [200, '{}'],
        '/error': [403, JSON.stringify({ error: { code: 'FS_PERMISSION_DENIED', message: 'download source could not be read' } })],
        '/method-not-allowed': [405, methodNotAllowed],
        '/large': [200, large],
    };
    for (const [url, [status, body]] of Object.entries(routes)) ctx.webServer.register({ kind: 'exact', path: url,
        handler(req, res) {
            try {
                res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
                res.end(req.method === 'HEAD' ? undefined : body);
            } catch (error) { errors.push({ path: url, code: error.code, message: error.message }); res.destroy(); }
        } });
    ctx.webServer.register({ kind: 'exact', path: '/stream', handler(_req, res) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.write('{"stream":"'); res.end('bytes"}');
    } });
    ctx.webServer.register({ kind: 'exact', path: '/events', handler(_req, res) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.write('data: first\n\n'); res.end('data: last\n\n');
    } });
    ctx.webServer.register({ kind: 'exact', path: '/content', handler(req, res) {
        const start = req.headers.range ? 19 : 0, end = req.headers.range ? 900001 : binary.length - 1;
        res.writeHead(req.headers.range ? 206 : 200, { 'Content-Type': 'application/octet-stream',
            'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes', ETag: '"source-digest"',
            ...(req.headers.range ? { 'Content-Range': `bytes ${start}-${end}/${binary.length}` } : {}) });
        if (req.method === 'HEAD') { res.end(); return; }
        for (let offset = start; offset <= end; offset += 256 * 1024)
            res.write(binary.subarray(offset, Math.min(offset + 256 * 1024, end + 1)));
        res.end();
    } });
    try {
        for (const [url, [status, body]] of Object.entries(routes)) {
            const response = await call(ctx.webServer.port, url);
            assert.equal(response.status, status); assert.equal(decoded(response).toString(), body);
            if (!response.headers['content-encoding']) assert.equal(Number(response.headers['content-length']), Buffer.byteLength(body));
            else assert.equal(response.headers['content-length'], undefined);
            const head = await call(ctx.webServer.port, url, 'HEAD');
            assert.equal(head.status, status); assert.equal(head.body.length, 0);
            assert.equal(Number(head.headers['content-length']), Buffer.byteLength(body));
            assert.equal(head.headers['content-encoding'], undefined);
        }
        // PR 200: the small 405 capability probe must survive both browser
        // negotiation paths when the host's gzip middleware owns the response.
        for (const encoding of ['gzip', 'br']) {
            const probe = await call(ctx.webServer.port, '/method-not-allowed', 'GET', { 'Accept-Encoding': encoding });
            assert.equal(probe.status, 405);
            assert.equal(probe.headers['content-encoding'], undefined);
            assert.deepEqual(probe.body, Buffer.from(methodNotAllowed));
            const compressed = await call(ctx.webServer.port, '/large', 'GET', { 'Accept-Encoding': encoding });
            assert.equal(compressed.status, 200);
            assert.deepEqual(decoded(compressed), Buffer.from(large));
            assert.equal(compressed.headers['content-encoding'], encoding === 'gzip' ? 'gzip' : compression === 'none' ? 'br' : undefined);
        }
        const stream = await call(ctx.webServer.port, '/stream');
        assert.equal(decoded(stream).toString(), '{"stream":"bytes"}');
        assert.equal(stream.headers['content-length'], undefined);
        const events = await call(ctx.webServer.port, '/events');
        assert.equal(events.body.toString(), 'data: first\n\ndata: last\n\n');
        assert.equal(events.headers['content-encoding'], undefined);
        for (const method of ['GET', 'HEAD']) for (const range of [false, true]) {
            // The native transfer explicitly negotiates identity so its source
            // byte offsets and durable resume lengths refer to the same bytes.
            const result = await call(ctx.webServer.port, '/content', method,
                { 'Accept-Encoding': 'identity', ...(range ? { Range: 'bytes=19-900001' } : {}) });
            assert.equal(result.status, range ? 206 : 200);
            assert.equal(result.headers['content-encoding'], undefined);
            assert.equal(Number(result.headers['content-length']), range ? 900001 - 19 + 1 : binary.length);
            assert.equal(result.headers['content-range'], range ? `bytes 19-900001/${binary.length}` : undefined);
            assert.deepEqual(result.body, method === 'HEAD' ? Buffer.alloc(0) : range ? binary.subarray(19, 900002) : binary);
        }
        const encodedContent = await call(ctx.webServer.port, '/content');
        assert.deepEqual(decoded(encodedContent), binary);
        assert.equal(encodedContent.headers['content-length'], compression === 'gzip' ? undefined : String(binary.length));
        assert.deepEqual(errors, []);
    } catch (error) { if (errors.length) console.error('CARRIER_RESPONSE_ERRORS', JSON.stringify(errors)); throw error; }
    finally { dispose(); await ctx.fiber.dispose(); }
});
