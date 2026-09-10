import { installResponseCompression } from './compress.js';
import { deleteSession } from './delete-session.js';
/** Drain a request body as UTF-8 text. */
function readBody(req) {
    return new Promise((resolve, reject) => {
        let data = '', bytes = 0;
        req.setEncoding('utf8');
        req.on('data', (chunk) => {
            bytes += Buffer.byteLength(chunk, 'utf8');
            if (bytes > 4096) { reject(new Error('删除请求过大')); return; }
            data += chunk;
        });
        req.on('end', () => resolve(data));
        req.on('error', reject);
        req.on('aborted', () => reject(new Error('请求已取消')));
    });
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
                // exact 路由优先于上游 /api 前缀，必须显式复用同一鉴权与来源检查。
                const rejection = webCtx.connection.requestRejection(req);
                if (rejection !== undefined) {
                    respond(res, rejection, { error: { code: 'access-denied', message: '需要当前浏览器鉴权' } });
                    return;
                }
                if (req.method !== 'POST') {
                    respond(res, 405, { error: { code: 'method-not-allowed', message: 'POST required' } });
                    return;
                }
                let body;
                try {
                    body = JSON.parse(await readBody(req));
                }
                catch {
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
