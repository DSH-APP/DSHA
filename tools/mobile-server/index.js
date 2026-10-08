import { installResponseCompression } from './compress.js';
import { deleteSession, installSessionDeletionLifecycle } from './delete-session.js';
import { LLM_PI_AI_ENTRY, fillReasoningEffortDefaults } from './reasoning-effort.js';
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
 * Keep hand-declared `llm-pi-ai` models usable straight from the composer.
 *
 * `llm-pi-ai` describes reasoning per MODEL, so a route the user declared by
 * hand shows no 「推理等级」 control until its entries spell their levels out.
 * This installer fills the conventional set into the user's own layer (see
 * `reasoning-effort.ts` for the store contract and the safety invariants): once
 * when the settings service is available, and again whenever the `llm-pi-ai`
 * document changes — a model added in the Models page therefore gains its
 * levels without anyone editing the profile file. A host without the settings
 * service (older DSH) never registers the scope and does nothing.
 *
 * @param ctx - host context (the fill lives in the `settings` inject scope).
 */
function installReasoningEffortDefaults(ctx) {
    ctx.inject(['settings'], (settingsCtx) => {
        const settings = settingsCtx.get('settings');
        if (settings === undefined)
            return;
        /** Delays between attempts while `llm-pi-ai` is not yet describable. */
        const RETRY_DELAYS_MS = [200, 800, 2000];
        // One fill at a time: our own write emits `settings/document-updated`, and
        // a second pass while the first is in flight would only restate it.
        let running = false;
        // Set once the section was describable: the initial-pass retries and the
        // service-provision re-runs both stop there.
        let settled = false;
        // Latched by the scope disposer. The retry timer is armed from INSIDE the
        // async fill's `.then`, so disposing while a fill is in flight cannot be
        // handled by clearing handles alone — without this flag that late `.then`
        // would arm a fresh timer after the scope is gone (2026-10-07 audit H1).
        let disposed = false;
        let attempts = 0;
        let timer;
        let scheduled;
        // Declared here (not at its assignment below) so the disposer registered
        // next can always reference it — the assignment happens a few statements
        // later and a synchronous dispose must not hit a TDZ (audit H2).
        let sweep;
        // Log the unreachable count only when it changes, so a stable profile does
        // not repeat the same line on every settings change.
        let lastUnresolved = -1;
        const run = () => {
            if (disposed || running)
                return;
            running = true;
            void fillReasoningEffortDefaults(settings)
                .then((outcome) => {
                const retryable = outcome.status === 'unavailable' || outcome.status === 'failed';
                if (!disposed && retryable && attempts < RETRY_DELAYS_MS.length) {
                    // Two transient refusals must not disable the feature:
                    // · `unavailable` — `describe()` only lists ACTIVE entries, and this
                    //   plugin can apply while `llm-pi-ai` is still loading;
                    // · `failed` — the event that woke us is emitted from inside the
                    //   settings service's own write (`describe()` detects the change
                    //   there), so our batch can still collide with that transaction or
                    //   with the edit that produced it. Neither is an error the user
                    //   can act on, and nothing else would re-describe the section.
                    timer = setTimeout(run, RETRY_DELAYS_MS[attempts++]);
                    return;
                }
                settled = true;
                if (outcome.status === 'filled') {
                    settingsCtx.logger.warn(`dsh-web-mobile: added default reasoning levels to ${outcome.filled} hand-declared model(s)`);
                }
                else if (outcome.status === 'failed') {
                    settingsCtx.logger.warn(`dsh-web-mobile: reasoning-level fill gave up after ${RETRY_DELAYS_MS.length + 1} attempts: ${outcome.reason}`);
                }
                if (outcome.unresolved > 0 && outcome.unresolved !== lastUnresolved) {
                    settingsCtx.logger.warn(`dsh-web-mobile: ${outcome.unresolved} model(s) below the profile layer declare no reasoning levels and stay untouched`);
                }
                lastUnresolved = outcome.unresolved;
            })
                .catch((error) => {
                settingsCtx.logger.warn(`dsh-web-mobile: reasoning-level fill failed: ${error instanceof Error ? error.message : String(error)}`);
            })
                .finally(() => {
                running = false;
            });
        };
        settingsCtx.effect(() => () => {
            // Stop every later pass first: the retry timer is armed from inside the
            // async fill's `.then` and the sweep from a `setInterval`, so clearing
            // handles without latching `disposed` would let an in-flight fill arm a
            // new timer after disposal (audit H1/H2).
            disposed = true;
            settled = true;
            if (timer !== undefined)
                clearTimeout(timer);
            timer = undefined;
            if (scheduled !== undefined)
                clearTimeout(scheduled);
            scheduled = undefined;
            if (sweep !== undefined)
                clearInterval(sweep);
            sweep = undefined;
        });
        /**
         * Defer a fill out of the settings service's own write.
         *
         * `settings/document-updated` is emitted from inside `describe()`, which the
         * service calls while it holds its write transaction — a `mutate` issued
         * synchronously from that listener is refused as a nested transaction (the
         * same trap the community plugin documents). Leaving the current turn first
         * makes the fill a normal write again. Coalesced: one pending fill per turn.
         */
        const schedule = () => {
            if (disposed || scheduled !== undefined)
                return;
            scheduled = setTimeout(() => {
                scheduled = undefined;
                run();
            }, 0);
        };
        // Safety net: nothing guarantees that some other component re-describes the
        // section after a user edit (the change event is a SIDE EFFECT of the next
        // `describe()`), so a slow sweep re-reads it. `describe()` is a cheap walk
        // over the configurable entries, and the fill only writes when a level set
        // is actually missing.
        const SWEEP_INTERVAL_MS = 10_000;
        sweep = setInterval(schedule, SWEEP_INTERVAL_MS);
        run();
        // A service provided later is the other moment the section can become
        // describable; stop listening once the first pass landed.
        settingsCtx.on?.('internal/service', () => {
            if (!settled)
                run();
        });
        settingsCtx.on?.('settings/document-updated', (...args) => {
            if (args[0] === LLM_PI_AI_ENTRY)
                schedule();
        });
    });
}
/**
 * Plugin name, per the official minimal plugin shape (name + apply). The patch
 * row in cordis.patch.yml carries the same id, so nothing resolves through this
 * value in this repo; it labels the runtime record and is what the documented
 * form declares. Kept in sync with package.json name.
 */
export const name = 'dsh-web-mobile';
export function apply(ctx) {
    installReasoningEffortDefaults(ctx);
    // Only this public WebServer carrier's actual listening port is eligible;
    // other HTTP servers in the process retain their response behavior.
    ctx.inject(['webServer'], (webCtx) => {
        webCtx.effect(() => installResponseCompression({
            matches: (req) => Number.isInteger(webCtx.webServer.port)
                && req?.socket?.localPort === webCtx.webServer.port,
        }), 'dsh-web-mobile: response compression');
    });
    // Register the rc2 public lifecycle capture before the deletion route.
    // Persistence / session / workspace services are read per request.
    ctx.inject(['webServer', 'connection', 'agents'], (webCtx) => {
        webCtx.effect(() => installSessionDeletionLifecycle(webCtx.agents), 'dsh-web-mobile: public session lifecycle');
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
                let result;
                try {
                    result = await deleteSession({
                        persistence: persistence,
                        sessions: ctx.get('sessions'),
                        agents: ctx.get('agents'),
                        workspaceRegistry: ctx.get('workspaceRegistry'),
                    }, sessionId);
                } catch {
                    ctx.logger.warn('dsh-web-mobile: session-delete failed unexpectedly');
                    respond(res, 500, {error: {code: 'delete-failed', message: 'session delete failed unexpectedly'}});
                    return;
                }
                if (result.ok) {
                    respond(res, 200, { ok: true, deleted: result.deleted, ...(result.warnings ? { warnings: result.warnings } : {}) });
                    return;
                }
                ctx.logger.warn(`dsh-web-mobile: session-delete failed for '${sessionId}' (${result.error.code}): ${result.error.message}`);
                respond(res, result.status, { error: result.error });
            },
        }), 'dsh-web-mobile: session-delete route');
    });
}
//# sourceMappingURL=index.js.map
