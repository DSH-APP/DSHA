/**
 * Transparent response compression for large JSON payloads.
 *
 * Long sessions make `session.history` responses megabytes of JSON; on a
 * phone that is a slow, data-hungry transfer. This module patches
 * `http.ServerResponse.prototype` (process-wide, restored on dispose) so any
 * JSON response the host serves — the harness's own `/api/*` routes included —
 * is compressed when the client accepts it:
 *
 * - The client's `Accept-Encoding` picks the codec: `br` (brotli, quality 6)
 *   preferred, `gzip` fallback.
 * - Only JSON responses of at least MIN_JSON_BYTES are compressed; small
 *   JSON and every other content type (HTML, static assets, ZIP, SSE streams)
 *   pass through byte-identical with the original headers.
 * - The response header write is deferred until the body is known, so the
 *   decision (compress or not) is made on the actual size, and `Content-Length`
 *   always matches what is sent. Non-JSON responses call the original
 *   `writeHead` immediately and are never touched.
 *
 * The browser's fetch decompresses transparently, so no client change is
 * needed. SSE (`text/event-stream`) is intentionally left uncompressed: it is
 * a continuous stream and the /api bridge never buffers it.
 *
 * Ported from community fork wzxmt-zhc/dsh-web-mobile (v2.5.0).
 */
import { brotliCompressSync, constants as zlibConstants, gzipSync } from 'node:zlib';
import { ServerResponse as NodeServerResponse } from 'node:http';
/** Only payloads at least this large are worth compressing. */
const MIN_JSON_BYTES = 4 * 1024;
/** Brotli quality: 6 balances size and CPU for large JSON (17MB → ~1MB). */
const BROTLI_QUALITY = 6;
/** Per-response state; only present while a JSON response is being deferred. */
const deferred = new WeakMap();
/** Choose the codec the client accepts; `br` outranks `gzip`. */
function pickEncoding(res) {
    const accepted = res.req?.headers['accept-encoding'] ?? '';
    if (/\bbr\b/.test(accepted))
        return 'br';
    if (/\bgzip\b/.test(accepted))
        return 'gzip';
    return null;
}
/**
 * Find a header value regardless of the caller's key casing. The patch sees
 * the RAW writeHead argument (before Node lowercases), and HTTP header names
 * are case-insensitive — a caller may pass `Content-Type` or `content-type`.
 * (Case-insensitivity fix ported from community fork wzxmt-zhc/dsh-web-mobile.)
 */
export function headerValue(headers, name) {
    for (const key of Object.keys(headers)) {
        if (key.toLowerCase() === name)
            return String(headers[key]);
    }
    return undefined;
}
/** Whether a response warrants deferred (potentially compressed) handling. */
export function isDeferrable(headers) {
    if (headerValue(headers, 'content-encoding') !== undefined)
        return false;
    const contentType = headerValue(headers, 'content-type') ?? '';
    return contentType.includes('json');
}
/** Append the Accept-Encoding Vary token without clobbering an existing Vary. */
export function varyWithAcceptEncoding(headers) {
    const existingKey = Object.keys(headers).find((key) => key.toLowerCase() === 'vary');
    if (existingKey === undefined) {
        headers['vary'] = 'Accept-Encoding';
    }
    else {
        headers[existingKey] = `${String(headers[existingKey])}, Accept-Encoding`;
    }
}
/** Buffer one body chunk for a deferred response. */
function bufferChunk(pending, chunk) {
    if (typeof chunk === 'string')
        pending.chunks.push(Buffer.from(chunk));
    else if (chunk instanceof Uint8Array)
        pending.chunks.push(Buffer.from(chunk));
    else if (chunk !== null && chunk !== undefined)
        pending.chunks.push(Buffer.from(String(chunk)));
}
/** Replay the stored writeHead args with a replacement headers object. */
function writeHeadWith(res, origWriteHead, pending, headers) {
    const args = pending.writeHeadArgs.slice();
    if (typeof args[1] === 'string')
        args[2] = headers;
    else
        args[1] = headers;
    // Keep the receiver: node's writeHead reads this._header etc.
    return origWriteHead.apply(res, args);
}
/**
 * Install the compression patch on http.ServerResponse.prototype.
 * @returns disposer restoring the original methods (plugin reload safety).
 */
export function installResponseCompression() {
    const proto = NodeServerResponse.prototype;
    // Capture the originals under the simple signatures the wrappers use; the
    // real overloaded implementations are restored unchanged on dispose.
    const origWriteHead = proto.writeHead;
    const origWrite = proto.write;
    const origEnd = proto.end;
    function patchedWriteHead(...args) {
        const rawHeaders = typeof args[1] === 'string' ? args[2] : args[1];
        const headers = rawHeaders;
        if (headers === undefined || !isDeferrable(headers)) {
            return origWriteHead.apply(this, args);
        }
        const encoding = pickEncoding(this);
        if (encoding === null) {
            return origWriteHead.apply(this, args);
        }
        // Hold the header write until the body size is known (see module doc).
        deferred.set(this, { writeHeadArgs: args, headers, encoding, chunks: [] });
        return this;
    }
    function patchedWrite(chunk, ...rest) {
        const pending = deferred.get(this);
        if (pending !== undefined) {
            bufferChunk(pending, chunk);
            return true;
        }
        return origWrite.apply(this, [chunk, ...rest]);
    }
    function patchedEnd(chunk, ...rest) {
        const pending = deferred.get(this);
        if (pending === undefined) {
            return chunk === undefined
                ? origEnd.apply(this, rest)
                : origEnd.apply(this, [chunk, ...rest]);
        }
        deferred.delete(this);
        if (chunk !== undefined)
            bufferChunk(pending, chunk);
        const body = Buffer.concat(pending.chunks);
        // Small or empty JSON: replay the ORIGINAL header write and body verbatim
        // (no Content-Encoding, original Content-Length intact).
        if (body.byteLength < MIN_JSON_BYTES) {
            writeHeadWith(this, origWriteHead, pending, pending.headers);
            return body.byteLength === 0
                ? origEnd.apply(this, rest)
                : origEnd.apply(this, [body, ...rest]);
        }
        // Large JSON: compress and rewrite the length-bearing headers.
        const compressed = pending.encoding === 'br'
            ? brotliCompressSync(body, { params: { [zlibConstants.BROTLI_PARAM_QUALITY]: BROTLI_QUALITY } })
            : gzipSync(body, { level: 6 });
        const headers = { ...pending.headers };
        for (const key of Object.keys(headers)) {
            if (key.toLowerCase() === 'content-length')
                delete headers[key];
        }
        headers['content-encoding'] = pending.encoding;
        headers['content-length'] = compressed.byteLength;
        varyWithAcceptEncoding(headers);
        writeHeadWith(this, origWriteHead, pending, headers);
        origWrite.call(this, compressed);
        return origEnd.apply(this, rest);
    }
    proto.writeHead = patchedWriteHead;
    proto.write = patchedWrite;
    proto.end = patchedEnd;
    return () => {
        if (proto.writeHead === patchedWriteHead)
            proto.writeHead = origWriteHead;
        if (proto.write === patchedWrite)
            proto.write = origWrite;
        if (proto.end === patchedEnd)
            proto.end = origEnd;
    };
}
//# sourceMappingURL=compress.js.map