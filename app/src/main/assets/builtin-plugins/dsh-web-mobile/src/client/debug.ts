import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { DESKTOP_QUERY, MOBILE_QUERY } from './platform/breakpoints.ts'
/**
 * Debug badge — ?mobile-nav-debug=1
 * Renders a live state overlay (URL, viewport, media queries, shell chrome,
 * aionui columns, genui cards, captured errors) so a phone-side repro can be
 * diagnosed without guessing. No-op unless the query param is present.
 */

/**
 * Combo id segment identifying this plugin's own bundle inside the host's
 * plugin route (`/plugins/??<id>/client.js,…&rev=<12 hex>`). The leading `?` or
 * `,` is a boundary so a scoped fork (`@someone/dsh-web-mobile`) cannot match.
 */
const BUNDLE_ID = /[?,]dsh-web-mobile\/client\.js/

/**
 * Revision from a plugin bundle URL, or null when it carries none.
 * The host mints it per build (`dsh-client-modules` HASH_REVISION_LENGTH = 12)
 * and stamps the same value on the URL it actually serves, so it answers
 * "which build is this page running" with no build-time injection.
 */
export function parseBundleRev(url: string | null | undefined): string | null {
  if (typeof url !== 'string' || url === '') return null
  try {
    const rev = new URL(url, 'http://x').searchParams.get('rev')
    return rev === null || rev === '' ? null : rev
  } catch {
    return null
  }
}

/**
 * Revision of the bundle the host served this plugin's code from, read off the
 * head element the host injected for it, or null when that element is absent.
 *
 * The host injects an application-batch combo as
 * `<link rel="preload" as="script" href="/plugins/??…,dsh-web-mobile/client.js,…&rev=…">`
 * and the browser fetches exactly that URL (verified against
 * `performance.getEntriesByType('resource')`), so the `rev` here IS the running
 * build's revision. The bundle id — not "the first rev in head" — selects our
 * row: the only `<script src>` row belongs to the client-modules bootstrap
 * batch and carries a different, unrelated revision.
 */
export function readBundleRev(): string | null {
  for (const el of document.head.querySelectorAll<HTMLElement>('script[src], link[rel="preload"]')) {
    const url = el.getAttribute('src') ?? el.getAttribute('href')
    if (url !== null && BUNDLE_ID.test(url)) return parseBundleRev(url)
  }
  return null
}
export function installDebugBadge(ctx: ClientContext): void {
  ctx.effect(() => {
    // 也认 hash：带 token 的地址会先 303 跳到 `/` 鉴权，跳转会把 `?` 后面的参数
    // 全部丢掉（2026-11-06 实测：`/?token=…&mobile-nav-debug=1` → `301/303` →
    // `http://127.0.0.1:41333/`）。`#` 后面的片段在跳转中会保留，所以
    // `/?token=…#mobile-nav-debug=1` 才是这个环境里唯一能活到页面的写法。
    const params = new URLSearchParams(location.search)
    if (!params.has('mobile-nav-debug') && !location.hash.includes('mobile-nav-debug')) return () => {}
    const errors: string[] = []
    const onError = (event: ErrorEvent) => errors.push(`ERR ${event.message.slice(0, 120)}`)
    const onRejection = (event: PromiseRejectionEvent) => errors.push(`REJ ${String(event.reason).slice(0, 120)}`)
    window.addEventListener('error', onError)
    window.addEventListener('unhandledrejection', onRejection)

    const badge = document.createElement('div')
    badge.style.cssText = [
      'position:fixed', 'top:40px', 'right:6px', 'z-index:2147483000',
      'background:rgba(0,0,0,.82)', 'color:#fff', 'font:11px/1.5 ui-monospace,monospace',
      'padding:8px 10px', 'border-radius:8px', 'max-width:94vw', 'max-height:70vh',
      'overflow:auto', 'white-space:pre-wrap', 'pointer-events:none',
    ].join(';')

    const read = (): string => {
      const q = (sel: string) => !!document.querySelector(sel)
      const vis = (sel: string) => {
        const el = document.querySelector<HTMLElement>(sel)
        return el === null ? 'absent' : getComputedStyle(el).visibility
      }
      const frame = document.querySelector<HTMLElement>('[data-mobile-nav="frame"]')
      // Safe-area diagnosis (read-only: the observer below re-enters on any
      // node this adds to the document). framePad IS the resolved
      // env(safe-area-inset-top) - the frame already consumes it - and
      // rightPanel reports whether the host panel carries it too. If a phone
      // shows a covered top row while framePad reads 0px, the inset is 0 on
      // that device and the fix needs another source for the height.
      const rightPanel = (): string => {
        const el = document.querySelector<HTMLElement>('[data-sidebar-right-panel]')
        if (el === null) return 'absent'
        const b = el.getBoundingClientRect()
        return `${el.getAttribute('data-sidebar-right-panel')} pad ${getComputedStyle(el).paddingTop} rect ${Math.round(b.top)},${Math.round(b.left)} ${Math.round(b.width)}x${Math.round(b.height)}`
      }
      /** 浏览器真正给到的 safe-area-inset-top（env() 读不到，只能量一个探针）。 */
      const envInset = (): string => {
        const probe = document.createElement('div')
        probe.style.cssText = 'position:fixed;top:0;left:0;width:0;height:env(safe-area-inset-top,0px);pointer-events:none;visibility:hidden'
        document.body.appendChild(probe)
        const h = probe.getBoundingClientRect().height
        probe.remove()
        return `${Math.round(h)}px`
      }
      const rev = readBundleRev()
      return [
        `build rev ${rev ?? '?'} (diag chips)`,
        `URL ${location.pathname}${location.search}`,
        `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
        `mq≤1023 ${matchMedia(MOBILE_QUERY).matches}  mq≥1024 ${matchMedia(DESKTOP_QUERY).matches}`,
        `safeTop ${frame === null ? 'n/a' : getComputedStyle(frame).getPropertyValue('--dsha-safe-area-inset-top').trim() || 'unset'}  framePad ${frame === null ? 'n/a' : getComputedStyle(frame).paddingTop}  rightPanel ${rightPanel()}`,
        `env(inset) ${envInset()}  vvOffsetTop ${Math.round(window.visualViewport?.offsetTop ?? -1)}  standalone ${matchMedia('(display-mode: standalone)').matches}  dsha ${typeof (window as { DSHA?: unknown }).DSHA !== 'undefined'}`,
        `css ${q('style[data-plugin-css*="mobile"]')}  frame ${!!frame}`,
        `previewCol ${vis('[data-aionui-preview-col]')}  explorerCol ${vis('[data-aionui-explorer-col]')}`,
        `previewOpen ${frame?.hasAttribute('data-aionui-preview-open') ?? '?'}  explorerOpen ${frame?.hasAttribute('data-aionui-explorer-open') ?? '?'}  previewFull ${frame?.hasAttribute('data-mobile-preview-full') ?? '?'}`,
        `header ${vis('[data-phase] header')}  composer ${q('textarea, [data-composer-input]')}`,
        `genui cards ${document.querySelectorAll('[data-genui]').length}  panel ${q('[data-genui-panel]')}`,
        `phase ${document.querySelector('[data-phase]')?.getAttribute('data-phase') ?? '?'}`,
        `errs ${errors.slice(-5).join(' | ') || 'none'}`,
      ].join('\n')
    }
    const paint = (): void => { badge.textContent = read() }
    paint()
    // Never re-enter on the badge's own textContent mutations: paint() writes
    // into a body subtree, so a naive full-tree observer would feed its own
    // output back into paint() forever and starve the page (observed as a hard
    // freeze with ?mobile-nav-debug=1).
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        if (record.target === badge || badge.contains(record.target)) continue
        paint()
        return
      }
    })
    observer.observe(document.body, { childList: true, subtree: true, attributes: true })
    const timer = setInterval(paint, 1500)
    document.body.appendChild(badge)

    // Opt-in device beacon: the same readings are POSTed to a local listener
    // (default http://127.0.0.1:3199/diag, override with ?beacon=<url>) so a
    // phone-side repro can be read from the machine serving the page without
    // anyone copying numbers off the screen. no-cors + a string body keeps it a
    // simple request (no preflight); a missing listener is ignored.
    // Rects of the plugin's own header controls plus the host row they live in:
    // the phone-side position of the files opener is what a "not pinned to the
    // top-right corner" report is about, and it cannot be measured headless.
    const marker = (sel: string): string => {
      const el = document.querySelector<HTMLElement>(sel)
      if (el === null) return 'absent'
      const b = el.getBoundingClientRect()
      return `${Math.round(b.x)},${Math.round(b.y)} ${Math.round(b.width)}x${Math.round(b.height)}`
    }
    const payload = (): string => [
      read(),
      `rects toggle ${marker('[data-mobile-nav="toggle"]')} files ${marker('[data-mobile-nav="files"]')} header ${marker('[data-phase] header')} titleCluster ${marker('[class*="_titleCluster"]')}`,
      `chips crea ${marker('[class*="SVAs4q_"]')} team ${marker('[data-team-action]')} model ${marker('[class*="_7KE1Ra_trigger"]')} crumbs ${marker('[class*="_crumbs"]')} crumbCurrent ${marker('[class*="_crumbCurrent"]')}`,
      `ua ${navigator.userAgent}`,
      `screen ${screen.width}x${screen.height} standalone ${matchMedia('(display-mode: standalone)').matches}`,
      `vv ${visualViewport === null ? 'n/a' : `${Math.round(visualViewport.width)}x${Math.round(visualViewport.height)}@${Math.round(visualViewport.offsetTop)}`}`,
    ].join('\n')
    const beacon = params.get('beacon') || 'http://127.0.0.1:3199/diag'
    const beaconTimer = setInterval(() => {
      void fetch(beacon, { method: 'POST', mode: 'no-cors', body: payload() }).catch(() => {})
    }, 2000)

    return () => {
      window.removeEventListener('error', onError)
      window.removeEventListener('unhandledrejection', onRejection)
      observer.disconnect()
      clearInterval(timer)
      clearInterval(beaconTimer)
      badge.remove()
    }
  }, 'dsh-web-mobile: debug badge')
}
