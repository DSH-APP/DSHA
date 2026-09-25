import { installResponseCompression } from './compress.js';
import { deleteSession } from './delete-session.js';
/** Maximum accepted request body size (1 MiB — the delete body is one id). */
const MAX_BODY_BYTES = 1_048_576;
/** Sentinel: the request body grew past MAX_BODY_BYTES. */
class PayloadTooLargeError extends Error {
}
/** Drain a request body as UTF-8 text, rejecting with PayloadTooLargeError
 * once the accumulated size exceeds MAX_BODY_BYTES. Past the limit the
 * buffered data is released and further chunks are discarded (the socket is
 * left to drain so the 413 response can actually be delivered — destroying
 * the request mid-stream would race the response and yield an empty reply). */
function readBody(req) {
    return new Promise((resolve, reject) => {
        let data = '';
        let bytes = 0;
        let tooLarge = false;
        req.setEncoding('utf8');
        req.on('data', (chunk) => {
            bytes += Buffer.byteLength(chunk);
            if (bytes > MAX_BODY_BYTES) {
                tooLarge = true;
                data = '';
                return;
            }
            if (!tooLarge)
                data += chunk;
        });
        req.on('end', () => {
            if (tooLarge)
                reject(new PayloadTooLargeError());
            else
                resolve(data);
        });
        req.on('error', reject);
        req.on('aborted', () => reject(new Error('request aborted')));
    });
}
/** Same-origin gate: a browser-supplied Origin header must name the same host
 * as the request itself. Missing/empty Origin = non-browser client = allowed.
 * No allowlist: localhost / 127.0.0.1 / LAN entries all work via host
 * equality, so same-origin browser POSTs (which always carry Origin) pass. */
function sameOrigin(req) {
    const origin = req.headers.origin;
    if (origin === undefined || origin === '')
        return true;
    try {
        return new URL(origin).host === req.headers.host;
    }
    catch {
        return false;
    }
}
/** Write one JSON response with a fixed content type. */
function respond(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(payload),
    });
    res.end(payload);
}
/**
 * Plugin name, per the official minimal plugin shape (name + apply). The patch
 * row in cordis.patch.yml carries the same id, so nothing resolves through this
 * value in this repo; it labels the runtime record and is what the documented
 * form declares. Kept in sync with package.json name.
 */
export const name = 'dsh-web-mobile';
export function apply(ctx) {
    // Transparent gzip/brotli for large JSON responses (long-session history
    // is megabytes on a phone). Patches http.ServerResponse.prototype; the
    // disposer restores it on plugin unload/reload.
    ctx.effect(() => installResponseCompression(), 'dsh-web-mobile: response compression');
    // Session-delete route (port of fork wzxmt-zhc v2.7.0). Registers once the
    // web route registry exists; the persistence / session / agent / workspace
    // services are read per request so host shapes without them degrade to a
    // structured 503 instead of a crash.
    ctx.inject(['webServer', 'connection'], (webCtx) => {
        webCtx.effect(() => webCtx.webServer.register({
            kind: 'exact',
            path: '/api/mobile-nav.session.delete',
            handler: async (req, res) => {
                // DSHA host authentication remains authoritative for this exact route.
                const rejection = webCtx.connection.requestRejection(req);
                if (rejection !== undefined) {
                    respond(res, rejection, { error: { code: 'access-denied', message: 'authentication required' } });
                    return;
                }
                if (req.method !== 'POST') {
                    respond(res, 405, { error: { code: 'method-not-allowed', message: 'POST required' } });
                    return;
                }
                if (!sameOrigin(req)) {
                    respond(res, 403, { error: { code: 'cross-origin', message: 'cross-origin request rejected: Origin host does not match the request host' } });
                    return;
                }
                let body;
                try {
                    body = JSON.parse(await readBody(req));
                    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('expected object');
                }
                catch (error) {
                    if (error instanceof PayloadTooLargeError) {
                        respond(res, 413, { error: { code: 'payload-too-large', message: `request body exceeds the ${MAX_BODY_BYTES}-byte limit` } });
                        return;
                    }
                    respond(res, 400, {
                        error: { code: 'invalid-body', message: 'expected a JSON body of the form { "sessionId": string }' },
                    });
                    return;
                }
                const { sessionId } = body;
                if (typeof sessionId !== 'string' || sessionId === '') {
                    respond(res, 400, {
                        error: { code: 'invalid-session-id', message: 'sessionId must be a non-empty string' },
                    });
                    return;
                }
                const persistence = ctx.get('sessionPersistence');
                if (persistence === undefined) {
                    respond(res, 503, {
                        error: { code: 'persistence-unavailable', message: 'session persistence is not configured' },
                    });
                    return;
                }
                const result = await deleteSession({
                    persistence: persistence,
                    sessions: ctx.get('sessions'),
                    agents: ctx.get('agents'),
                    workspaceRegistry: ctx.get('workspaceRegistry'),
                }, sessionId);
                if (result.ok) {
                    respond(res, 200, { ok: true, deleted: result.deleted });
                    return;
                }
                ctx.logger.warn(`dsh-web-mobile: session-delete failed for '${sessionId}' (${result.error.code}): ${result.error.message}`);
                respond(res, result.status, { error: result.error });
            },
        }), 'dsh-web-mobile: session-delete route');
    });
}
//# sourceMappingURL=index.js.map