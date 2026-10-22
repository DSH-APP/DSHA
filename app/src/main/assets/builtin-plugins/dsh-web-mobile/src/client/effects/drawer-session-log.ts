// Drawer brand-row session-log icon (issue #225, 2026-10-18).
//
// The mobile drawer used to carry the session-log download as a full-width
// pill in the host's `sidebar.footer.action` slot. On a 390x844 phone that
// row cost 40px of the session list (measured: list viewport 450px, pill
// 34px + 6px gap) for an action that is low-frequency on touch devices, so
// the owner's call was to keep the entry but move it into the drawer's brand
// row as a single icon: zero vertical cost, still one tap for the current
// session.
//
// The host has no top-area action slot: `sidebar.brand.mark` /
// `sidebar.brand.name` / `sidebar.toggle.badge` are single-owner slots already
// used by the brand, and `sidebar.footer.action` is the only list slot (where
// the pill lived). So the icon is a plugin-owned node injected into the host's
// logo row (`[class*="logoRow"]`): that row is a flex row whose brand button is
// `flex: 1`, so the icon takes the right end and the brand gives up exactly the
// icon's width instead of being overlapped. It is wrapped in a `<span>` because
// the third-party `@linxin666/dsh-web-all` responsive layer treats the LAST
// direct `<button>` of that row as the sidebar toggle and clicks it to close the
// drawer; a nested button is invisible to that heuristic (see `mount()`). The
// reconciler task re-appends the wrapper whenever React rebuilds the row, and
// one `ctx.sessions.list` subscription keeps the disabled state in step with the
// current session.
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import type { ReconcilerTask } from '../core/reconciler-core.ts'
import { currentSessionIdOf } from '../core/sessions-compat.ts'
import { NS } from '../i18n/locales.ts'
import { getFrame } from '../platform/frame.ts'
// Type-only augmentation: brings the host's `sessionLogDownload` service into
// the ClientContext type used below (no runtime import).
import type {} from '@deepseek-ai/dsh-session-log-export/client'

/**
 * Session-id shape the host's `sessionLogDownload.download` expects. Derived,
 * never imported, so one program type-checks against every host generation
 * (0.1.x types the parameter as plain string, 0.1.2-alpha.1 brands the id).
 */
type DownloadSessionId = Parameters<ClientContext['sessionLogDownload']['download']>[0]

/**
 * 16px download outline glyph: the host's own `IconDownloadOutlineRegular`
 * artwork (three stroked paths), inlined because this node is built without
 * React (the host icon components are React-only).
 */
const DOWNLOAD_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">'
  + '<path d="M8 1.95317V10.0469" stroke="currentColor" stroke-width="1"/>'
  + '<path d="M4.25 6.29688L8 10.0469L11.75 6.29688" stroke="currentColor" stroke-width="1"/>'
  + '<path d="M1.5 10.0469V13.158C1.5 13.3937 1.60536 13.6198 1.79289 13.7865C1.98043 13.9532 2.23478 14.0469 2.5 14.0469H13.5C13.7652 14.0469 14.0196 13.9532 14.2071 13.7865C14.3946 13.6198 14.5 13.3937 14.5 13.158V10.0469" stroke="currentColor" stroke-width="1"/>'
  + '</svg>'

/**
 * The drawer's brand-row export icon (replaces the footer pill; issue #225).
 *
 * The task is scoped to tree changes (`*`) because React may recreate the
 * host's logo row on any sidebar re-render; `ensure()` re-mounts the wrapper
 * idempotently. The button never sits directly in the row (see `mount()`).
 * `dispose()` (mobile breakpoint teardown or task removal) unsubscribes and
 * removes the node.
 *
 * @param ctx - client root context: locale, sessions list and the host's
 *   session-log download service.
 * @returns the reconciler task.
 */
export function createDrawerSessionLogTask(ctx: ClientContext): ReconcilerTask {
  const t = ctx.locale.bind(NS)
  const sessionId = (): string | undefined => currentSessionIdOf(ctx.sessions.list.getSnapshot())
  let wrapper: HTMLSpanElement | null = null
  let button: HTMLButtonElement | null = null
  let unsubscribe: (() => void) | null = null

  /** Mirror "no current session" into the native disabled state. */
  const syncDisabled = (): void => {
    if (button === null) return
    button.disabled = sessionId() === undefined
  }

  const onClick = (): void => {
    const id = sessionId()
    if (id === undefined) return
    ctx.sessionLogDownload.download(id as unknown as DownloadSessionId)
  }

  /** The drawer's brand row. Scoped to the sidebar column first: the shell
   *  frame is the mobile branch only, but the column keeps the lookup off any
   *  future logo row outside the drawer. */
  const logoRow = (): HTMLElement | null => {
    const frame = getFrame()
    if (frame === null) return null
    return frame.querySelector<HTMLElement>('[class*="sidebarCol"] [class*="logoRow"]')
      ?? frame.querySelector<HTMLElement>('[class*="logoRow"]')
  }

  /**
   * Mount the wrapper as a direct (non-button) child of the logo row.
   *
   * The third-party `@linxin666/dsh-web-all` responsive layer stamps the LAST
   * direct `<button>` of the logo row as `data-dsh-responsive-part="sidebar-toggle"`
   * (its own source: `querySelectorAll(':scope > button, :scope > [role="button"]')`
   * then `.item(length - 1)`) and its dismiss shim clicks that node to close the
   * drawer. A button appended directly to the row is adopted instead: measured
   * 2026-10-18, the icon computed `display:none` and an outside tap would fire
   * the export. Keeping the button nested one level down in a `<span>` hides it
   * from the `:scope > button` selector without depending on the host toggle's
   * class (which may change per host generation).
   */
  const mount = (row: HTMLElement, target: HTMLElement): void => {
    if (target.parentElement !== row) row.appendChild(target)
  }

  return {
    name: 'drawer-session-log',
    scopes: ['*'],
    ensure: () => {
      const row = logoRow()
      if (row === null) return
      if (button === null) {
        wrapper = document.createElement('span')
        wrapper.dataset.mobileNav = 'session-log-wrap'
        button = document.createElement('button')
        button.type = 'button'
        button.dataset.mobileNav = 'session-log'
        button.setAttribute('aria-label', t('sessionLog'))
        button.title = t('sessionLog')
        button.innerHTML = DOWNLOAD_SVG
        button.addEventListener('click', onClick)
        wrapper.appendChild(button)
        unsubscribe = ctx.sessions.list.subscribe(() => { syncDisabled() })
      }
      if (wrapper !== null) mount(row, wrapper)
      syncDisabled()
    },
    dispose: () => {
      unsubscribe?.()
      unsubscribe = null
      wrapper?.remove()
      wrapper = null
      button = null
    },
  }
}
