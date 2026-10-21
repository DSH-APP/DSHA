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
 * - Compression itself runs OFF the event loop (`brotliCompress` / `gzip` with
 *   a callback = libuv thread pool). It used to run synchronously inside
 *   `end()`, and on a phone the host process shares the device with the
 *   WebView: one multi-megabyte JSON response blocked every other request in
 *   flight — including the small settings write behind the first-run notice,
 *   which then sat at 「正在保存…」 for seconds. `end()` now returns before the
 *   headers are written; the reply completes from the zlib callback
 *   (2026-10-20, see tests/compress.test.ts「deferred off the event loop」).
 *   Identity (no compression needed) and the abandon paths stay synchronous.
 *
 * The browser's fetch decompresses transparently, so no client change is
 * needed. SSE (`text/event-stream`) is intentionally left uncompressed: it is
 * a continuous stream and the /api bridge never buffers it.
 *
 * Known limitations (issue #80): while a response is deferred, write()
 * reports unconditional success (true) — the socket is untouched, so no
 * backpressure signal exists; buffered write() completion callbacks replay
 * fire-once, in order, right after the real end(), without error propagation
 * (the real flush cannot fail them individually).
 *
 * Ported from community fork wzxmt-zhc/dsh-web-mobile (v2.5.0).
 */
import { brotliCompress, constants as zlibConstants, gzip } from 'node:zlib'
import { ServerResponse as NodeServerResponse } from 'node:http'
import type { IncomingMessage, ServerResponse } from 'node:http'

/** Only payloads at least this large are worth compressing. */
const MIN_JSON_BYTES = 4 * 1024

/** Brotli quality: 6 balances size and CPU for large JSON (17MB → ~1MB). */
const BROTLI_QUALITY = 6

/** One deferred response: headers held back until the body size is known. */
interface DeferredResponse {
  /** Original writeHead argument list (status/message/headers) to replay. */
  writeHeadArgs: unknown[]
  /** Original headers object carried by writeHeadArgs. */
  headers: Record<string, string | number | string[]>
  /** Codec chosen from the request's Accept-Encoding. */
  encoding: 'br' | 'gzip'
  /** Buffered body chunks. */
  chunks: Buffer[]
  /** write() completion callbacks buffered during deferral. */
  writeCallbacks: Array<() => void>
}

/** Per-response state; only present while a JSON response is being deferred. */
const deferred = new WeakMap<ServerResponse, DeferredResponse>()

/**
 * Parse one `Accept-Encoding` field into a coding → qvalue map. A missing
 * weight means 1; an unparsable or out-of-range weight means 0 ("not
 * acceptable"), which is what HTTP implementations do with garbage. The
 * wildcard `*` is kept as its own entry and consulted as the fallback for any
 * coding the client did not name.
 */
export function parseAcceptedEncodings(field: string): Map<string, number> {
  const quality = new Map<string, number>()
  for (const part of field.split(',')) {
    const [rawName, ...params] = part.trim().toLowerCase().split(';')
    const name = rawName.trim()
    if (name === '') continue
    let value = 1
    const weight = params.find((param) => /^\s*q\s*=/.test(param))
    if (weight !== undefined) {
      const parsed = Number.parseFloat(weight.slice(weight.indexOf('=') + 1))
      value = Number.isFinite(parsed) ? Math.min(Math.max(parsed, 0), 1) : 0
    }
    // First occurrence wins: RFC 9110 does not define duplicates and every
    // real client names a coding once.
    if (!quality.has(name)) quality.set(name, value)
  }
  return quality
}

/**
 * Choose the codec the client accepts; `br` outranks `gzip`, and a weight of 0
 * is a refusal (RFC 9110 §12.4.2) — `br;q=0` must not select brotli, and a
 * bare `*` is an offer, not a refusal. No acceptable coding means `null` and
 * the response passes through identity (never 406: identity stays acceptable).
 */
export function pickEncoding(res: ServerResponse): 'br' | 'gzip' | null {
  const accepted = (res.req as IncomingMessage | undefined)?.headers['accept-encoding'] ?? ''
  const quality = parseAcceptedEncodings(accepted)
  const wildcard = quality.get('*') ?? 0
  const br = quality.get('br') ?? wildcard
  const gzip = quality.get('gzip') ?? wildcard
  if (br > 0 && br >= gzip) return 'br'
  return gzip > 0 ? 'gzip' : null
}

/**
 * Find a header value regardless of the caller's key casing. The patch sees
 * the RAW writeHead argument (before Node lowercases), and HTTP header names
 * are case-insensitive — a caller may pass `Content-Type` or `content-type`.
 * (Case-insensitivity fix ported from community fork wzxmt-zhc/dsh-web-mobile.)
 */
export function headerValue(headers: Record<string, string | number | string[]>, name: string): string | undefined {
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === name) return String(headers[key])
  }
  return undefined
}

/** Whether a response warrants deferred (potentially compressed) handling. */
export function isDeferrable(headers: Record<string, string | number | string[]>): boolean {
  if (headerValue(headers, 'content-encoding') !== undefined) return false
  const contentType = headerValue(headers, 'content-type') ?? ''
  return contentType.includes('json')
}

/**
 * Append the Accept-Encoding Vary token without clobbering an existing Vary
 * and without duplicating the token: re-running the patch (or a caller that
 * already varied on it, in any casing) must stay idempotent, and `*` already
 * covers Accept-Encoding.
 */
export function varyWithAcceptEncoding(headers: Record<string, string | number | string[]>): void {
  const existingKey = Object.keys(headers).find((key) => key.toLowerCase() === 'vary')
  if (existingKey === undefined) {
    headers['vary'] = 'Accept-Encoding'
    return
  }
  const tokens = String(headers[existingKey])
    .split(',')
    .map((token) => token.trim())
    .filter((token) => token !== '')
  if (tokens.some((token) => token.toLowerCase() === 'accept-encoding' || token === '*')) return
  headers[existingKey] = [...tokens, 'Accept-Encoding'].join(', ')
}

/** Buffer one body chunk for a deferred response, honoring the caller's encoding. */
function bufferChunk(pending: DeferredResponse, chunk: unknown, encoding?: unknown): void {
  const enc = typeof encoding === 'string' ? (encoding as BufferEncoding) : undefined
  if (typeof chunk === 'string') pending.chunks.push(Buffer.from(chunk, enc))
  else if (chunk instanceof Uint8Array) pending.chunks.push(Buffer.from(chunk))
  else if (chunk !== null && chunk !== undefined) pending.chunks.push(Buffer.from(String(chunk)))
}

/** Fire the buffered write() callbacks once, in order, after the replay. */
function fireWriteCallbacks(pending: DeferredResponse): void {
  for (const callback of pending.writeCallbacks.splice(0)) callback()
}

/** Replay the stored writeHead args with a replacement headers object. */
function writeHeadWith(res: ServerResponse, origWriteHead: (...args: unknown[]) => ServerResponse, pending: DeferredResponse, headers: Record<string, string | number | string[]>): ServerResponse {
  const args = pending.writeHeadArgs.slice() as unknown[]
  if (typeof args[1] === 'string') args[2] = headers
  else args[1] = headers
  // Keep the receiver: node's writeHead reads this._header etc.
  return origWriteHead.apply(res, args) as ServerResponse
}

/**
 * Finish a deferred JSON response from the zlib callback, i.e. off the event
 * loop (libuv thread pool). Everything the synchronous path used to do inside
 * `end()` — header replay, `Content-Encoding`/`Content-Length` rewrite, body
 * write, buffered `write()` callbacks — happens here, one tick later at the
 * earliest. `end()` has already returned by then, so:
 *
 * - a caller that wrote or flushed headers during the compression window wins
 *   (its own headers are on the wire; the buffered body goes out verbatim
 *   instead of throwing ERR_HTTP_HEADERS_SENT out of a callback);
 * - a compression error degrades to identity rather than a broken reply;
 * - nothing may throw out of the callback — an uncaught throw there takes the
 *   whole host process down, and this patch is process-wide.
 */
function finishDeferred(
  res: ServerResponse,
  pending: DeferredResponse,
  body: Buffer,
  callbacks: unknown[],
  origWriteHead: (...args: unknown[]) => ServerResponse,
  origWrite: (chunk: unknown, ...rest: unknown[]) => boolean,
  origEnd: (chunk?: unknown, ...rest: unknown[]) => ServerResponse,
): void {
  const send = (compressed: Buffer | null): void => {
    try {
      if (res.writableEnded || res.destroyed) return
      if (res.headersSent || compressed === null) {
        // Abandon the deferral: headers are already out (or compression
        // failed), so replay the body verbatim, exactly like the sync path.
        if (!res.headersSent) writeHeadWith(res, origWriteHead, pending, pending.headers)
        if (body.byteLength === 0) origEnd.apply(res, callbacks as never)
        else origEnd.apply(res, [body, ...callbacks] as never)
        fireWriteCallbacks(pending)
        return
      }
      const headers = { ...pending.headers }
      for (const key of Object.keys(headers)) {
        if (key.toLowerCase() === 'content-length') delete headers[key]
      }
      headers['content-encoding'] = pending.encoding
      headers['content-length'] = compressed.byteLength
      varyWithAcceptEncoding(headers)
      writeHeadWith(res, origWriteHead, pending, headers)
      origWrite.call(res, compressed)
      origEnd.apply(res, callbacks as never)
      fireWriteCallbacks(pending)
    } catch (error) {
      // A throw out of an async callback is an unhandled exception: kill this
      // one reply instead of the host process.
      fireWriteCallbacks(pending)
      res.destroy(error instanceof Error ? error : new Error(String(error)))
    }
  }
  const done = (error: Error | null, compressed?: Buffer): void => {
    send(error === null && compressed !== undefined ? compressed : null)
  }
  if (pending.encoding === 'br') {
    brotliCompress(body, { params: { [zlibConstants.BROTLI_PARAM_QUALITY]: BROTLI_QUALITY } }, done)
  } else {
    gzip(body, { level: 6 }, done)
  }
}

/**
 * Install the compression patch on http.ServerResponse.prototype.
 * @returns disposer restoring the original methods (plugin reload safety).
 */
export function installResponseCompression(): () => void {
  const proto = NodeServerResponse.prototype
  // Capture the originals under the simple signatures the wrappers use; the
  // real overloaded implementations are restored unchanged on dispose.
  const origWriteHead = proto.writeHead as (...args: unknown[]) => ServerResponse
  const origWrite = proto.write as (chunk: unknown, ...rest: unknown[]) => boolean
  const origEnd = proto.end as (chunk?: unknown, ...rest: unknown[]) => ServerResponse

  function patchedWriteHead(this: ServerResponse, ...args: unknown[]): ServerResponse {
    const rawHeaders = typeof args[1] === 'string' ? args[2] : args[1]
    const headers = rawHeaders as Record<string, string | number | string[]> | undefined
    if (headers === undefined || !isDeferrable(headers)) {
      return origWriteHead.apply(this, args as never) as ServerResponse
    }
    const encoding = pickEncoding(this)
    if (encoding === null) {
      return origWriteHead.apply(this, args as never) as ServerResponse
    }
    // Hold the header write until the body size is known (see module doc).
    deferred.set(this, { writeHeadArgs: args, headers, encoding, chunks: [], writeCallbacks: [] })
    return this
  }

  function patchedWrite(this: ServerResponse, chunk: unknown, ...rest: unknown[]): boolean {
    const pending = deferred.get(this)
    if (pending !== undefined) {
      // Buffer the encoding with the chunk (a latin1 write must not be
      // silently re-encoded) and keep the completion callback for a
      // fire-once replay after the real end() (issue #80).
      bufferChunk(pending, chunk, typeof rest[0] === 'string' ? rest[0] : undefined)
      for (const arg of rest) {
        if (typeof arg === 'function') pending.writeCallbacks.push(arg as () => void)
      }
      return true
    }
    return origWrite.apply(this, [chunk, ...rest] as never) as boolean
  }

  function patchedEnd(this: ServerResponse, chunk?: unknown, ...rest: unknown[]): ServerResponse {
    const pending = deferred.get(this)
    if (pending === undefined) {
      return chunk === undefined
        ? origEnd.apply(this, rest as never) as ServerResponse
        : origEnd.apply(this, [chunk, ...rest] as never) as ServerResponse
    }
    deferred.delete(this)
    // `end(callback)`: the function is a completion callback, never body
    // data — keep it out of the buffers and replay it at the real end().
    const callbacks = (typeof chunk === 'function' ? [chunk, ...rest] : rest)
      .filter((arg) => typeof arg === 'function')
    // `end(data, encoding)` and friends: the data is buffered above and the
    // encoding is consumed by that buffering, so only the callbacks may be
    // replayed — origEnd('utf8') would write the string as body data after
    // the compressed payload (issue #78).
    if (chunk !== undefined && typeof chunk !== 'function') bufferChunk(pending, chunk, typeof rest[0] === 'string' ? rest[0] : undefined)
    const body = Buffer.concat(pending.chunks)

    // The header write was deferred to learn the body size, but something else
    // may have sent the headers first: `flushHeaders()`, a `write()` issued
    // before `writeHead()`, or any other path Node treats as an implicit
    // header write. Replaying the stored writeHead() then throws
    // ERR_HTTP_HEADERS_SENT synchronously out of end(), which terminates the
    // process when the caller has no try/catch. Abandon the deferral instead:
    // hand the buffered body to the native path verbatim (uncompressed, no
    // header replay) so the response still completes and no bytes are lost.
    if (this.headersSent) {
      const ended = body.byteLength === 0
        ? origEnd.apply(this, callbacks as never) as ServerResponse
        : origEnd.apply(this, [body, ...callbacks] as never) as ServerResponse
      fireWriteCallbacks(pending)
      return ended
    }

    // Small or empty JSON: replay the ORIGINAL header write and body verbatim
    // (no Content-Encoding, original Content-Length intact).
    if (body.byteLength < MIN_JSON_BYTES) {
      writeHeadWith(this, origWriteHead, pending, pending.headers)
      const ended = body.byteLength === 0
        ? origEnd.apply(this, callbacks as never) as ServerResponse
        : origEnd.apply(this, [body, ...callbacks] as never) as ServerResponse
      fireWriteCallbacks(pending)
      return ended
    }

    // Large JSON: compress ASYNCHRONOUSLY and finish the reply from the zlib
    // callback. See the module doc: keeping zlib on the event loop here used to
    // stall every other in-flight request on a phone-sized host (first-run
    // notice at 「正在保存…」). end() must therefore return before any byte of
    // this response is written — headers, body and the buffered write()
    // callbacks all land in finishDeferred().
    finishDeferred(this, pending, body, callbacks, origWriteHead, origWrite, origEnd)
    return this
  }

  proto.writeHead = patchedWriteHead
  proto.write = patchedWrite
  proto.end = patchedEnd

  return () => {
    if (proto.writeHead === patchedWriteHead) proto.writeHead = origWriteHead
    if (proto.write === patchedWrite) proto.write = origWrite
    if (proto.end === patchedEnd) proto.end = origEnd
  }
}
