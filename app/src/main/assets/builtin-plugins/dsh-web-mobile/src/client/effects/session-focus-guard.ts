import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { currentSessionIdOf } from '../core/sessions-compat.ts'
import { SHADOW_MARKER } from './composer-keyboard-guard.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * Mobile guard: entering a session — or coming back from a main-slot panel —
 * must not raise the soft keyboard by itself.
 *
 * `dsh-client-ui-conversation`'s InputBar focuses the Lexical editor from a
 * passive effect keyed on `[locked, sessionId, editor]` — every session switch
 * programmatically focuses the editing surface (`focusDraftEditor`:
 * `getRootElement()?.focus(...)` plus `editor.focus(...)`). On desktop that is
 * a convenience. On a phone it costs the user half the screen the moment the
 * session opens — they typically want to READ the history first (issue #140).
 *
 * Fix strategy: on a snapshot-observed current-session change, open a short
 * guard window and shadow the editor's own `focus` property (the same
 * own-property recipe as `composer-keyboard-guard.ts`, sharing its marker) so
 * the host's session-switch autofocus lands on the no-op. A real tap is
 * unaffected: the browser focuses natively and never routes through the JS
 * method. The window is finite (see the constant) and also closes early when
 * the user taps the editing surface, so no legitimate programmatic refocus
 * (e.g. the `+` command menu, which needs the caret) is swallowed after the
 * switch has settled.
 *
 * Timing: the host focus runs in a PASSIVE effect, which React schedules after
 * commit — a MutationObserver callback is a microtask and therefore runs
 * before it (measured precedent: `shortcut-modal-keyboard-guard.ts` header).
 * Session switches remount the InputBar (the editor is Session-owned), so the
 * observer re-shadows the freshly mounted `[data-composer-input]` inside the
 * window; arming also shadows an already-present input for switches that
 * reuse the element.
 *
 * 2026-10-20 (店主报障)：退出插件面板回会话时也会弹键盘。同一台机器上的成因是
 * 同一个：面板占住主区时会话被卸载，退场（panel-exit 挂 data-mobile-panel-exit，
 * 220ms 动画后才 selectPanel）结束时 InputBar 重新挂载，它的挂载期聚焦又跑了一遍。
 * 退场标记由 panel-exit 在动画开始前就挂上，早于会话重挂载，所以这层只需要在
 * MutationObserver 里看到该标记出现时顺手开同一个窗口；其余机制（影子 + focusin
 * 兜底 + 点编辑面提前收窗）完全复用，不新增第二套 focus 拦截。
 *
 * 2026-09-29 headless correction (issue #140 verification run): the shadow
 * alone is NOT sufficient. When the InputBar remounts for the new session,
 * the host's focus call runs inside the commit's synchronous layout-effect
 * phase — BEFORE any MutationObserver microtask — so the freshly mounted
 * editor got focused while it still had no shadow (focusin measured at
 * t=314ms with marker=false; the observer only shadowed it afterwards). The
 * guard therefore keeps a focusin fallback for the window's lifetime: any
 * DOM focus landing on the editing surface is blurred synchronously — the
 * same recipe `composer-keyboard-guard.ts` proved on a real device in the
 * 2026-09-23 keepFocus loop (blur at the focusin capture phase happens
 * before the IME can rise, and drafts/caret live in the host's keyboard
 * state, not in DOM focus). A real tap is unaffected: the pointerdown
 * early-close below shuts the window before the browser's native focus of
 * that tap runs.
 *
 * DOM contract (verified against 0.1.7-rc.2):
 * - `[data-composer-input]` — the Lexical contenteditable surface (count=1;
 *   present in every released host since 0.1.2-alpha.2, per
 *   docs/debug/composer-tree-recon.md).
 * - `data-mobile-nav-focus-shadow` — the shared shadow marker.
 * Re-audit when the conversation package upgrades.
 */

/** Guard window for one session switch. Long enough for a slow phone to
 *  render + run passive effects; short enough that a user tapping `+` right
 *  after the switch only rarely lands inside it. */
const FOCUS_GUARD_WINDOW_MS = 800

/** The Lexical editing surface — the only element whose autofocus we swallow. */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]'

/** Frame marker panel-exit sets before it starts leaving (see effects/panel-exit.ts). */
const PANEL_EXIT_ATTR = 'data-mobile-panel-exit'

/**
 * Synchronous companion event, dispatched by panel-exit the moment the marker
 * lands. The marker alone is too late: the InputBar remounts and focuses inside
 * the commit's synchronous layout-effect phase, i.e. before an observer
 * microtask can open the window (the same 2026-09-29 measurement quoted in the
 * header). The event opens the window first, the marker stays as the backstop.
 */
const PANEL_EXIT_EVENT = 'dsh-mobile-panel-exit'

/**
 * Keep the session-switch autofocus from raising the soft keyboard, on the
 * mobile breakpoint only.
 * @param ctx - client root context.
 */
export function installSessionFocusGuard(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: session focus guard', () => {
    const list = ctx.sessions.list
    // Snapshot value at install time: subscribing must not arm the window by
    // itself — only a CHANGE of the current session id does.
    let lastSessionId = currentSessionIdOf(list.getSnapshot())
    let windowTimer = 0
    let windowOpen = false

    const restore = (): void => {
      window.clearTimeout(windowTimer)
      windowTimer = 0
      windowOpen = false
      const el = document.querySelector<HTMLElement>(`[${SHADOW_MARKER}]`)
      if (el === null) return
      el.removeAttribute(SHADOW_MARKER)
      const shadowed = el as Partial<Record<'focus', () => void>>
      if (Object.prototype.hasOwnProperty.call(el, 'focus')) delete shadowed.focus
    }

    const shadow = (el: HTMLElement): void => {
      if (el.hasAttribute(SHADOW_MARKER)) return
      el.setAttribute(SHADOW_MARKER, '')
      Object.defineProperty(el, 'focus', {
        configurable: true,
        writable: true,
        value: function swallowedFocus(): void {
          /* session-switch autofocus; keep the keyboard down */
        },
      })
    }

    const hasPanelExitMarker = (): boolean =>
      document.querySelector(`[data-mobile-nav="frame"][${PANEL_EXIT_ATTR}]`) !== null

    const arm = (): void => {
      restore()
      windowOpen = true
      const el = document.querySelector<HTMLElement>(COMPOSER_INPUT_SELECTOR)
      if (el !== null) shadow(el)
      windowTimer = window.setTimeout(restore, FOCUS_GUARD_WINDOW_MS)
    }

    // Session switches remount the InputBar: catch the freshly mounted editor
    // inside the window. Microtask timing beats the host's passive effect.
    // The same observer watches the panel-exit marker: a panel occupies the
    // main slot while open, so leaving it remounts the InputBar and re-runs its
    // mount-time focus. Arm on the marker's arrival — it lands before the panel
    // actually leaves (panel-exit animates first, then selectPanel), so the
    // window is already open when the conversation remounts.
    const observer = new MutationObserver(() => {
      if (!windowOpen && hasPanelExitMarker()) arm()
      if (!windowOpen) return
      const el = document.querySelector<HTMLElement>(COMPOSER_INPUT_SELECTOR)
      if (el !== null) shadow(el)
    })

    // A tap on the editing surface is the user saying "I want to type": close
    // the window on the spot so the residual shadow cannot eat anything.
    const onPointerDown = (event: Event): void => {
      if (!windowOpen) return
      const target = event.target
      if (target instanceof Element && target.closest(COMPOSER_INPUT_SELECTOR) !== null) restore()
    }

    // Timing fallback for the window's lifetime: the host focuses the freshly
    // mounted editor from the commit's synchronous phase, before the observer
    // microtask can shadow it (headless-measured 2026-09-29, see header), so
    // any focus that still lands on the editing surface inside the window is
    // blurred on the spot — before the IME can rise. User taps never reach
    // this: their pointerdown closed the window above.
    const onFocusIn = (event: Event): void => {
      if (!windowOpen) return
      const target = event.target
      if (target instanceof HTMLElement && target.closest(COMPOSER_INPUT_SELECTOR) !== null) target.blur()
    }

    // Invalidation callback (zustand-style): re-read the snapshot and arm only
    // when the current session id actually changed — list churn (titles,
    // ordering, refresh) must never open the window.
    const unsubscribe = list.subscribe(() => {
      const current = currentSessionIdOf(list.getSnapshot())
      if (current === lastSessionId) return
      lastSessionId = current
      arm()
    })

    const onPanelExit = (): void => {
      if (!windowOpen) arm()
    }

    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: [PANEL_EXIT_ATTR],
    })
    document.addEventListener(PANEL_EXIT_EVENT, onPanelExit)
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('focusin', onFocusIn, true)
    return () => {
      unsubscribe()
      observer.disconnect()
      document.removeEventListener(PANEL_EXIT_EVENT, onPanelExit)
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('focusin', onFocusIn, true)
      restore()
    }
  })
}
