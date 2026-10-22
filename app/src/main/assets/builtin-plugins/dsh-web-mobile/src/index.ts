/**
 * dsh-web-mobile, node half. Mostly a client UI plugin: apply() exists so the
 * plugin appears in the host Loader. It installs transparent gzip/brotli
 * compression for large JSON responses (long-session history is megabytes on
 * a phone; patches http.ServerResponse.prototype, disposer restores it), and
 * — ported from community-fork wzxmt-zhc v2.7.0 — the ONE host capability the
 * mobile drawer needs that the harness does not provide: deleting a session
 * (the host session menu only knows rename / fork / archive; archive only
 * hides a row).
 *
 * `POST /api/mobile-nav.session.delete` receives `{ sessionId }` and hands
 * the work to `deleteSession()` (see `delete-session.ts`). Services are read
 * at request time through `ctx.get()` so the row fails with a clear error
 * (never crashes) in host shapes that omit them.
 *
 * The browser half ships via exports["./client"], discovered through the
 * package.json dsh.client declaration. Host packages are intentionally NOT
 * type-imported: this repo's node_modules only carries the client-side
 * @deepseek-ai packages, so all host faces are declared structurally below.
 */
import type { IncomingMessage, ServerResponse } from 'node:http'
import { installResponseCompression } from './compress.js'
import { deleteSession, type DeleteSessionDeps, type DeleteSessionResult } from './delete-session.js'

/** Minimal structural slice of the host cordis Context that apply() needs. */
export interface HostContext {
  /** Register one disposable installer; its return value disposes on unload. */
  effect(install: () => unknown, label?: string): unknown
  /** Read one optional service by name (undefined when the host omits it). */
  get(service: string): unknown
  /** Run apply once the named services exist (cordis fiber inject). */
  inject(services: readonly string[], apply: (scoped: ScopedContext) => void): void
  /** Host logger service face (warn-level is all this plugin uses). */
  logger: { warn(message: string): void }
}

/** Context shape inside the `webServer` inject scope. */
export interface ScopedContext extends HostContext {
  webServer: {
    register(route: {
      kind: 'exact'
      path: string
      handler: (req: IncomingMessage, res: ServerResponse) => void | Promise<void>
    }): unknown
  }
}

/** Wire contract of the session-delete endpoint. */
interface DeleteSessionBody {
  sessionId?: unknown
}

/** Maximum accepted request body size (1 MiB — the delete body is one id). */
const MAX_BODY_BYTES = 1_048_576

/** Sentinel: the request body grew past MAX_BODY_BYTES. */
class PayloadTooLargeError extends Error {}

/** Drain a request body as UTF-8 text, rejecting with PayloadTooLargeError
 * once the accumulated size exceeds MAX_BODY_BYTES. Past the limit the
 * buffered data is released and further chunks are discarded (the socket is
 * left to drain so the 413 response can actually be delivered — destroying
 * the request mid-stream would race the response and yield an empty reply). */
function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = ''
    let bytes = 0
    let tooLarge = false
    req.setEncoding('utf8')
    req.on('data', (chunk: string) => {
      bytes += Buffer.byteLength(chunk)
      if (bytes > MAX_BODY_BYTES) {
        tooLarge = true
        data = ''
        return
      }
      if (!tooLarge) data += chunk
    })
    req.on('end', () => {
      if (tooLarge) reject(new PayloadTooLargeError())
      else resolve(data)
    })
    req.on('error', reject)
  })
}

/** Same-origin gate: a browser-supplied Origin header must name the same host
 * as the request itself. Missing/empty Origin = non-browser client = allowed.
 * No allowlist: localhost / 127.0.0.1 / LAN entries all work via host
 * equality, so same-origin browser POSTs (which always carry Origin) pass. */
function sameOrigin(req: IncomingMessage): boolean {
  const origin = req.headers.origin
  if (origin === undefined || origin === '') return true
  try {
    return new URL(origin).host === req.headers.host
  } catch {
    return false
  }
}

/** Write one JSON response with a fixed content type. */
function respond(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

/**
 * Plugin name, per the official minimal plugin shape (name + apply). The patch
 * row in cordis.patch.yml carries the same id, so nothing resolves through this
 * value in this repo; it labels the runtime record and is what the documented
 * form declares. Kept in sync with package.json name.
 */
export const name = 'dsh-web-mobile'

export function apply(ctx: HostContext): void {
  // Transparent gzip/brotli for large JSON responses (long-session history
  // is megabytes on a phone). Patches http.ServerResponse.prototype; the
  // disposer restores it on plugin unload/reload.
  ctx.effect(() => installResponseCompression(), 'dsh-web-mobile: response compression')

  // Session-delete route (port of fork wzxmt-zhc v2.7.0). Registers once the
  // web route registry exists; the persistence / session / agent / workspace
  // services are read per request so host shapes without them degrade to a
  // structured 503 instead of a crash.
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'exact',
      path: '/api/mobile-nav.session.delete',
      handler: async (req, res) => {
        if (req.method !== 'POST') {
          respond(res, 405, { error: { code: 'method-not-allowed', message: 'POST required' } })
          return
        }
        if (!sameOrigin(req)) {
          respond(res, 403, { error: { code: 'cross-origin', message: 'cross-origin request rejected: Origin host does not match the request host' } })
          return
        }
        let body: DeleteSessionBody
        try {
          body = JSON.parse(await readBody(req)) as DeleteSessionBody
        } catch (error) {
          if (error instanceof PayloadTooLargeError) {
            respond(res, 413, { error: { code: 'payload-too-large', message: `request body exceeds the ${MAX_BODY_BYTES}-byte limit` } })
            return
          }
          respond(res, 400, {
            error: { code: 'invalid-body', message: 'expected a JSON body of the form { "sessionId": string }' },
          })
          return
        }
        const { sessionId } = body
        if (typeof sessionId !== 'string' || sessionId === '') {
          respond(res, 400, {
            error: { code: 'invalid-session-id', message: 'sessionId must be a non-empty string' },
          })
          return
        }

        const persistence = ctx.get('sessionPersistence')
        if (persistence === undefined) {
          respond(res, 503, {
            error: { code: 'persistence-unavailable', message: 'session persistence is not configured' },
          })
          return
        }
        // The pure core reports structured failures, but a defect or an
        // unexpected host rejection must still answer the client: this handler
        // owns the response, so it may never reject with the socket open (the
        // host's dispatcher would then answer a bare 400 with no body).
        let result: DeleteSessionResult
        try {
          result = await deleteSession({
            persistence: persistence as DeleteSessionDeps['persistence'],
            sessions: ctx.get('sessions') as DeleteSessionDeps['sessions'] | undefined,
            agents: ctx.get('agents') as DeleteSessionDeps['agents'] | undefined,
            workspaceRegistry: ctx.get('workspaceRegistry') as DeleteSessionDeps['workspaceRegistry'] | undefined,
          }, sessionId)
        } catch (error) {
          ctx.logger.warn(`dsh-web-mobile: session-delete threw for '${sessionId}': ${String(error)}`)
          respond(res, 500, { error: { code: 'delete-failed', message: 'session delete failed unexpectedly' } })
          return
        }
        if (result.ok) {
          respond(res, 200, { ok: true, deleted: result.deleted })
          return
        }
        ctx.logger.warn(
          `dsh-web-mobile: session-delete failed for '${sessionId}' (${result.error.code}): ${result.error.message}`,
        )
        respond(res, result.status, { error: result.error })
      },
    }), 'dsh-web-mobile: session-delete route')
  })
}
