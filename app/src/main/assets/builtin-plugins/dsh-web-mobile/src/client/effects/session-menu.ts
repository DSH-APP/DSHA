/**
 * Session-row action-menu injection: on touch-primary devices, adds a
 * "delete session" item to the host's per-row ⋯ menu (beside the host's own
 * items — rename / fork / archive, plus the 0.1.7 pin item; blank rows stay
 * host-native) and drives the whole delete flow: row → session id
 * resolution, a confirm dialog, the host delete endpoint, and the list
 * refresh.
 *
 * The host menu is React-owned (ui-workspace) with no extension slot, so the
 * item is injected into the portaled `[role="menu"]` list by cloning the
 * host's own item markup (reusing the hashed classes keeps the styling
 * identical), and re-injected whenever React recreates the menu. The item
 * shape is the primitives Menu's: `_itemIcon` + `_itemLabel` spans inside the
 * `role="menuitem"` button (dsh-client-ui-primitives 0.2.2 lib/index.js
 * MenuItem). The 0.1.5 bare-text item shape was dropped with the host floor.
 *
 * Row → session id: session rows carry no id in the DOM, so the id is read
 * off the host's own stable anchors (see `rowSessionId`).
 *
 * Ported from community-fork wzxmt-zhc v2.7.0; the only mainline delta is
 * where the delete-dialog CSS lives: base.css.ts, with corrected animation
 * names (`dsh-web-mobile-*`; the fork's originals referenced the pre-rename
 * `dsh-mobile-nav-*` names, which silently no-op).
 */
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { MOBILE_QUERY, TOUCH_QUERY, installMobileEffect } from '../platform/breakpoints.ts'
import { getFrame } from '../platform/frame.ts'
import { toggleDrawer } from './drawer-close.ts'
import { currentSessionIdOf, verifySessionDeleted } from '../core/sessions-compat.ts'
import { NS } from '../i18n/locales.ts'
import { findSessionIdInFiber, reactFiberOf } from './session-row-fiber.ts'

/** The ui-workspace dictionary namespace the host session menu labels come from. */
const WORKSPACE_NS = 'workspace'

/** Marker on the injected menu item (idempotence across React re-renders). */
const DELETE_ITEM_MARKER = 'data-mobile-nav="session-delete"'

/** Danger accent read from the theme, with a fixed fallback. */
const DANGER_COLOR = 'var(--dsw-alias-state-error-primary, #b91c1c)'

/** 16px outline trash glyph (IconTrashOutline16 path), currentColor-filled. */
const TRASH_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">'
  + '<path d="M14.4782 4.84067L14.2138 10.1152C14.1102 12.1872 14.067 13.0115 13.3866 13.9607C13.1044 14.3546 12.7498 14.6912 12.3424 14.9535C11.8239 15.2872 11.2415 15.4316 10.5585 15.4998C9.88727 15.5668 9.04946 15.5656 7.99998 15.5656C6.95051 15.5656 6.1127 15.5668 5.44142 15.4998C4.75851 15.4316 4.17602 15.2872 3.65753 14.9535C3.25012 14.6912 2.89559 14.3546 2.61332 13.9607C1.93296 13.0115 1.88979 12.1872 1.78619 10.1152L1.52179 4.84067L2.89006 4.77277L3.15343 10.0463C3.26221 12.2218 3.32452 12.6015 3.72646 13.1624C3.90825 13.4161 4.13686 13.6334 4.39927 13.8023C4.66204 13.9714 5.00263 14.0792 5.57825 14.1367C6.16562 14.1953 6.92298 14.1963 7.99998 14.1963C9.07699 14.1963 9.83434 14.1953 10.4217 14.1367C10.9973 14.0792 11.3379 14.1367 11.6007 13.8023C11.8631 13.6334 12.0917 13.4161 12.2735 13.1624C12.6755 12.6015 12.7378 12.2218 12.8465 10.0463L13.1099 4.77277L14.4782 4.84067ZM5.43011 6.22849H6.7994V11.3909H5.43011V6.22849ZM9.20056 6.22849H10.5699V11.3909H9.20056V6.22849ZM8.53597 0.434431C9.17976 0.434431 9.6522 0.426926 10.0966 0.571258C10.2357 0.616451 10.3717 0.672554 10.502 0.738948C10.9182 0.951107 11.2464 1.29099 11.7015 1.74612L12.4978 2.54136H15.3742V3.91169H0.625732V2.54136H3.50218L4.29845 1.74612C4.75358 1.29099 5.08174 0.951107 5.49801 0.738948C5.62831 0.672554 5.76425 0.616451 5.90334 0.571258C6.34776 0.426926 6.82021 0.434431 7.46399 0.434431H8.53597ZM7.46399 1.80476C6.73208 1.80476 6.51641 1.81187 6.32617 1.87369C6.25545 1.89667 6.18668 1.92533 6.12041 1.95907C5.96398 2.03878 5.82348 2.16253 5.44142 2.54136H10.5585C10.1765 2.16253 10.036 2.03878 9.87955 1.95907C9.81329 1.92533 9.74452 1.89667 9.6738 1.87369C9.48356 1.81187 9.26789 1.80476 8.53597 1.80476H7.46399Z" fill="currentColor" /></svg>'

/** One captured session-row menu anchor. */
interface MenuAnchor {
  /** The ⋯ button that opened the menu (used to close it). */
  button: HTMLButtonElement
  /** The row's displayed session title (display + legacy fallback only). */
  title: string
  /**
   * The session id read straight off the row's React fiber — the primary source
   * (2026-10-07). `null` when the fiber walk found nothing, in which case the
   * delete flow falls back to the title/group heuristic.
   */
  sessionId: string | null
}

/** Host delete-endpoint response shape. */
interface DeleteResponse {
  ok?: true
  deleted?: string
  error?: { code?: string; message?: string }
}

/** Escape text destined for innerHTML (session titles are user content). */
function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

/**
 * Install the mobile session-delete menu machinery. Touch-gated: the whole
 * effect arms under TOUCH_QUERY — (pointer: coarse) at EVERY width — so a
 * large tablet in landscape keeps the desktop layout but still gets the
 * delete item, while any mouse-driven or pointer-less window stays a
 * complete no-op. Returns a disposer (via installMobileEffect) that removes
 * every listener, observer, injected node, and the confirm dialog.
 * @param ctx - client root context.
 */
export function installSessionMenuDelete(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: session-menu delete', () => {
    const navT = ctx.locale.bind(NS)
    // Host workspace-browser dictionary for menu-signature detection. Bound
    // lazily so a later-registered dictionary is picked up; the general
    // overload accepts the raw namespace id.
    const wsT = (key: string, params?: Record<string, unknown>): string =>
      ctx.locale.bind(WORKSPACE_NS)(key, params)

    let anchor: MenuAnchor | null = null
    let injectRaf = 0
    let dialogHost: { backdrop: HTMLElement; card: HTMLElement } | null = null
    let closeDialogOnKey: ((event: KeyboardEvent) => void) | null = null

    // 请求身份（epoch），每次确认点击自增。更晚的删除一发起，在途响应即过期 ——
    // 过期结果不得 closeDialog / 收抽屉，否则会拿旧响应改新对话框与新当前会话。
    let deleteEpoch = 0

    /**
     * The session id of one row, from the host's own stable anchors.
     *
     * 2026-10-07 — this replaces the whole "match the displayed title, then
     * disambiguate duplicates by group position" heuristic, which 0.2.0-rc.2
     * broke twice over on the real drawer: every row is now wrapped in the
     * host's `HoverCard` `<span>`, so `:scope > [class*="_sessionRow"]` and the
     * `_projectRow` group header matched NOTHING → duplicate titles silently
     * resolved to the FIRST session with that title, i.e. **deleting the wrong
     * conversation**.
     *
     * Order of preference, all host-owned:
     *  1. `data-row-key="session:<id>"` — the same attribute the host's own
     *     `AnimatedRows.readPositions()` uses, so it is a real contract;
     *  2. `data-dsha-session-select` — the DSHA build stamps the id directly;
     *  3. the React fiber (`session-row-fiber.ts`, the proven path long-press
     *     navigation already uses).
     * @param row - the session row element.
     * @returns the session id, or null when the row offers none.
     */
    const rowSessionId = (row: HTMLElement): string | null => {
      const rowKey = row.getAttribute('data-row-key') ?? ''
      if (rowKey.startsWith('session:')) {
        const id = rowKey.slice('session:'.length)
        if (id !== '') return id
      }
      const stamped = row.getAttribute('data-dsha-session-select')
      if (stamped !== null && stamped !== '') return stamped
      return findSessionIdInFiber(reactFiberOf(row), isKnownSessionId)
    }

    /** Read one menu item's visible label from its `_itemLabel` span. */
    const itemLabel = (item: HTMLElement): string =>
      item.querySelector<HTMLElement>('[class*="_itemLabel"]')?.textContent?.trim() ?? ''

    /**
     * Whether a menu list is the host's per-session row menu. Containment
     * style, never an exact item count: 0.1.7 added a fourth 「置顶会话」
     * item (menu.pinSession, alongside rename / fork / archive) — a
     * `length === 3` gate silently disabled the whole feature on 0.1.7.
     * rename + fork + archiveSession is the discriminating triple (a
     * full-host label audit: the fork label exists only in ui-workspace's
     * session menu). Archived rows swap archive for 取消归档, so they do NOT
     * match this signature and get no delete item — the host's own look
     * (delete via unarchive first); resolution excludes archived ids anyway,
     * so an injected item there would be a doomed deleteErrorResolve tap
     * (#V1 N1: the removed unarchive branch used to inject exactly that).
     */
    const isSessionMenu = (menu: HTMLElement): boolean => {
      const labels = [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')]
        .map(itemLabel)
      const rename = wsT('rename')
      const fork = wsT('menu.fork')
      return labels.includes(rename) && labels.includes(fork)
        && labels.includes(wsT('menu.archiveSession'))
    }

    const closeDialog = (): void => {
      if (closeDialogOnKey !== null) {
        document.removeEventListener('keydown', closeDialogOnKey, true)
        closeDialogOnKey = null
      }
      if (dialogHost !== null) {
        dialogHost.backdrop.remove()
        dialogHost.card.remove()
        dialogHost = null
      }
    }

    /** Show the delete confirmation as a centered frosted-glass modal over
     *  the frame. Mounted on <body>, NOT in the frame: the third-party mobile
     *  shim (@linxin666/dsh-web-all) listens in the CAPTURE phase on the frame
     *  and, while the drawer is open, answers every click inside the frame but
     *  outside [data-pane="sidebar"] with preventDefault + stopPropagation.
     *  A card inside the frame therefore had dead buttons — measured
     *  2026-09-14: a real touch tap on 「取消」 left the card open, and only
     *  Escape closed it. Body-level, the shim's listener never sees these
     *  clicks (its sibling menus are portaled there for the same reason), and
     *  the dialog's band lives in base.css (backdrop z 1400 above the drawer
     *  on the mobile branch). The card is appended INTO the backdrop so the
     *  backdrop's flex centers it (base.css 2026-09-24 rework). */
    const showDeleteDialog = (sessionId: string, title: string): void => {
      closeDialog()
      const host = document.body
      const backdrop = document.createElement('div')
      backdrop.dataset.mobileNav = 'delete-dialog-backdrop'
      const card = document.createElement('div')
      card.dataset.mobileNav = 'delete-dialog'
      card.setAttribute('role', 'dialog')
      card.setAttribute('aria-modal', 'true')
      card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteConfirmTitle'))}</div>
        <div data-mobile-nav="delete-confirm-desc">${escapeHtml(navT('deleteConfirmDesc', { title }))}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
          <button type="button" data-mobile-nav="delete-confirm-yes">${escapeHtml(navT('deleteConfirmYes'))}</button>
        </div>
        <div data-mobile-nav="delete-error" role="alert" hidden></div>`
      const noButton = card.querySelector<HTMLButtonElement>('[data-mobile-nav="delete-confirm-no"]')
      const yesButton = card.querySelector<HTMLButtonElement>('[data-mobile-nav="delete-confirm-yes"]')
      const errorLine = card.querySelector<HTMLElement>('[data-mobile-nav="delete-error"]')
      noButton?.addEventListener('click', closeDialog)
      // The card is a CHILD of the backdrop (the CSS centers it through the
      // backdrop's flex), so close only on genuine backdrop taps — without
      // the target guard every card click (the async yes tap included) would
      // bubble here and close the dialog before the fetch settles, killing
      // the pending state and the error display path.
      backdrop.addEventListener('click', (event) => {
        if (event.target !== backdrop) return
        closeDialog()
      })
      const onKey = (event: KeyboardEvent): void => {
        if (event.key === 'Escape') closeDialog()
      }
      document.addEventListener('keydown', onKey, true)
      closeDialogOnKey = onKey

      const resetButtons = (): void => {
        if (yesButton !== null) {
          yesButton.disabled = false
          yesButton.textContent = navT('deleteConfirmYes')
        }
        if (noButton !== null) noButton.disabled = false
      }
      const fail = (message: string): void => {
        if (errorLine !== null) {
          errorLine.textContent = message
          errorLine.hidden = false
        }
        resetButtons()
      }
      const mapError = (payload: DeleteResponse | null, reason: unknown): string => {
        const code = payload?.error?.code
        if (code === 'session-not-found') return navT('deleteErrorNotFound')
        if (code === 'session-busy') return navT('deleteErrorBusy')
        const message = payload?.error?.message ?? (reason instanceof Error ? reason.message : String(reason))
        return navT('deleteErrorGeneric', { message })
      }
      yesButton?.addEventListener('click', async () => {
        yesButton.disabled = true
        if (noButton !== null) noButton.disabled = true
        yesButton.textContent = navT('deletePending')
        if (errorLine !== null) errorLine.hidden = true
        // 请求身份：本次点击的序号。期间又发起删除时，这次的结果就过期了。
        const epoch = ++deleteEpoch
        try {
          const response = await fetch('/api/mobile-nav.session.delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId }),
          })
          const payload = await response.json().catch(() => null) as DeleteResponse | null
          if (!response.ok || payload === null || payload.ok !== true) {
            fail(mapError(payload, new Error(`HTTP ${response.status}`)))
            return
          }
        } catch (reason) {
          // A rejected fetch proves nothing (DSHA's host half aborts the reply
          // after the trash move — see verifySessionDeleted). Ask the session
          // list instead; only a session that survives every re-read failed.
          // Verification itself must not become the new failure mode.
          let landed = false
          try {
            landed = await verifySessionDeleted({
              listed: () => ctx.sessions.list.getSnapshot()?.byId?.[sessionId] !== undefined,
              // Called AS A METHOD: refresh() reads `this.manager`.
              refresh: async () => {
                await (ctx.sessions as { refresh?: () => Promise<void> }).refresh?.()
              },
              sleep: (ms) => new Promise<void>((resolve) => {
                window.setTimeout(() => { resolve() }, ms)
              }),
            })
          } catch {
            landed = false
          }
          if (!landed) {
            fail(mapError(null, reason))
            return
          }
        }
        // 「是否仍是当前会话」必须在**请求完成时**重新核对：请求期间用户可能已经切到
        // 别的会话，用请求前算好的旧值会把新当前会话所在的抽屉收掉（2026-10-11）。
        // （宿主 >=0.2.2 没有 sessions.clear()，旧代的「清选中态」一步已随下限删除。）
        const wasCurrent = currentSessionIdOf(ctx.sessions.list.getSnapshot()) === sessionId
        // 过期结果（期间又发起过删除）只丢弃，不关别人的对话框、也不动状态。
        if (epoch !== deleteEpoch) return
        closeDialog()
        // Repull the baseline so the deleted row disappears; called AS A METHOD
        // on ctx.sessions because refresh() reads `this.manager`.
        const sessions = ctx.sessions as { refresh?: () => Promise<void> }
        try {
          await sessions.refresh?.()
        } catch (reason) {
          // 列表重拉失败只影响刷新，不代表删除失败 —— 单独 warn，也不许变成未处理 rejection。
          console.warn('[dsh-web-mobile] session list refresh after delete failed:', reason)
        }
        // 手机档抽屉里就是列表，删完当前会话顺手收起；桌面档的面板不得被收起（MOBILE_QUERY
        // 门保持）。只有抽屉**确实开着**才关：盲 toggle 会把已关的抽屉重新打开（2026-10-11）。
        const frame = getFrame()
        if (frame === null || frame.hasAttribute('data-sidebar-collapsed')) return
        if (wasCurrent && window.matchMedia(MOBILE_QUERY).matches) toggleDrawer(ctx)
      })

      host.appendChild(backdrop)
      backdrop.appendChild(card)
      dialogHost = { backdrop, card }
    }

    /** Show a non-destructive error card (session could not be resolved). */
    const showError = (message: string): void => {
      closeDialog()
      const host = document.body
      const backdrop = document.createElement('div')
      backdrop.dataset.mobileNav = 'delete-dialog-backdrop'
      const card = document.createElement('div')
      card.dataset.mobileNav = 'delete-dialog'
      card.setAttribute('role', 'dialog')
      card.setAttribute('aria-modal', 'true')
      card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteSession'))}</div>
        <div data-mobile-nav="delete-error" role="alert">${escapeHtml(message)}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
        </div>`
      card.querySelector<HTMLButtonElement>('[data-mobile-nav="delete-confirm-no"]')?.addEventListener('click', closeDialog)
      // Same child-of-backdrop target guard as showDeleteDialog: error-card
      // taps must not bubble into the backdrop's close.
      backdrop.addEventListener('click', (event) => {
        if (event.target !== backdrop) return
        closeDialog()
      })
      const onKey = (event: KeyboardEvent): void => {
        if (event.key === 'Escape') closeDialog()
      }
      document.addEventListener('keydown', onKey, true)
      closeDialogOnKey = onKey
      host.appendChild(backdrop)
      backdrop.appendChild(card)
      dialogHost = { backdrop, card }
    }

    /**
     * The clones injected into host menus. They are plugin-owned nodes inside
     * React-owned lists, so disposal must remove them explicitly — the host
     * never unmounts them on our behalf, and the click listener rides the clone
     * (issue #86: the old disposer left the node and its listener behind, and a
     * later tap went through showError on a dead anchor).
     */
    const injected = new Set<HTMLElement>()

    /** Inject the delete item into one open session menu (idempotent). */
    const injectInto = (menu: HTMLElement): void => {
      if (menu.querySelector(`[${DELETE_ITEM_MARKER}]`) !== null) return
      const template = menu.querySelector<HTMLElement>('[role="menuitem"]')
      // `?? null` collapses the optional chain so the guard is exact: the old
      // `wrap === undefined` arm was unreachable once `template === null` had
      // returned (issue #86).
      const wrap = template?.parentElement ?? null
      const viewport = menu.querySelector<HTMLElement>('[class*="_viewport"]')
      if (template === null || wrap === null || viewport === null) return
      const clone = wrap.cloneNode(true) as HTMLElement
      const button = clone.querySelector<HTMLButtonElement>('[role="menuitem"]')
      if (button === null) return
      const icon = button.querySelector<HTMLElement>('[class*="_itemIcon"]')
      if (icon !== null) {
        icon.innerHTML = TRASH_SVG
        icon.style.color = DANGER_COLOR
      }
      // A template without a label span is an unknown shape: inject nothing
      // rather than ship a delete item that reads as the cloned host action.
      const label = button.querySelector<HTMLElement>('[class*="_itemLabel"]')
      if (label === null) return
      label.textContent = navT('deleteSession')
      label.style.color = DANGER_COLOR
      button.setAttribute('data-mobile-nav', 'session-delete')
      button.addEventListener('click', (event) => {
        event.preventDefault()
        event.stopPropagation()
        const captured = anchor
        // Close the host menu by toggling its anchor (React-owned state).
        captured?.button.click()
        try {
          if (captured === null) {
            showError(navT('deleteErrorResolve'))
            return
          }
          // 用录制点击时从行上读到的会话 id（`data-row-key` → DSHA 戳 → fiber）。
          // 读不到就报错，不再按标题/行序猜 —— 2026-10-07 实测那条路在 0.2.0-rc.2
          // 上会静默删到**另一个同名会话**（宿主把行包进 HoverCard 后 `:scope >` 取不到行，
          // rowIndex 变成 -1，于是永远取"第一个同名 id"）。
          const sessionId = captured.sessionId
          if (sessionId === null) {
            showError(navT('deleteErrorResolve'))
            return
          }
          showDeleteDialog(sessionId, captured.title)
        } catch (reason) {
          // Never fail silently: surface internal resolution errors instead of
          // leaving the tap with no visible result.
          console.error('[dsh-web-mobile] session delete failed:', reason)
          showError(navT('deleteErrorGeneric', {
            message: reason instanceof Error ? reason.message : String(reason),
          }))
        }
      })
      viewport.appendChild(clone)
      injected.add(clone)
    }

    /**
     * Inject into every open session menu. Blank (new-session) rows are
     * excluded: the host renders their title as the localized "New session"
     * label (`t("session.new")`) while the summary's `displayTitle` stays
     * empty, so the delete flow could never resolve them — the tap would
     * only end in a deleteErrorResolve card. A menu without the delete item
     * is the host's own look for those rows. Known ceiling: a normal session
     * manually titled exactly the host's "New session" label is mistaken for
     * a blank row and gets no delete item either (accepted trade-off; its
     * resolution itself would still work).
     */
    const injectAll = (): void => {
      // Drop references to clones the host already unmounted with its menu.
      for (const clone of injected) if (!clone.isConnected) injected.delete(clone)
      const blankLabel = wsT('session.new')
      for (const menu of document.querySelectorAll<HTMLElement>('[role="menu"]')) {
        if (!isSessionMenu(menu)) continue
        if (anchor !== null && anchor.title === blankLabel) continue
        injectInto(menu)
      }
    }
    const scheduleInject = (): void => {
      if (injectRaf !== 0) return
      injectRaf = requestAnimationFrame(() => {
        injectRaf = 0
        injectAll()
      })
    }

    /** Whether an id is a session this client knows (the fiber walk's filter). */
    const isKnownSessionId = (id: string): boolean => ctx.sessions.list.getSnapshot().byId[id] !== undefined

    /**
     * The row's ⋯ anchor button.
     *
     * 2026-10-07 (host 0.2.0-rc.2): a row now carries THREE buttons — the ⋯
     * (`aria-label="Session actions for …"`), `Archive session` and
     * `Pin session`. Reading `querySelector('button')` only worked because the ⋯
     * happened to come first; match it by its label and exclude the two row
     * actions instead of betting on document order.
     * @param row - the session row.
     * @returns the ⋯ button, or null when this row has none.
     */
    const menuButtonOf = (row: HTMLElement): HTMLButtonElement | null => {
      const buttons = [...row.querySelectorAll<HTMLButtonElement>('button')]
      const labelled = buttons.find((candidate) => {
        const label = candidate.getAttribute('aria-label') ?? ''
        return /session actions/i.test(label)
      })
      if (labelled !== undefined) return labelled
      const fallback = buttons.find((candidate) => {
        const label = candidate.getAttribute('aria-label') ?? ''
        return !/archive session|pin session/i.test(label)
      })
      return fallback ?? null
    }

    // Capture the ⋯ button click before React handles it, so the row/title/session
    // id are known when the portaled menu appears.
    //
    // The id comes off the row's React FIBER first (same proven path the
    // long-press navigation uses in drawer-close.ts:249). The old title/group
    // heuristic is kept only as a fallback: on 0.2.0-rc.2 the `_projectRow`
    // group header no longer sits inside `_groupSection`, so duplicate titles
    // (three rows all called 「你好」) could not be disambiguated and the delete
    // tap died with 「无法确定要删除的会话」.
    const onDocumentClick = (event: MouseEvent): void => {
      const target = event.target as HTMLElement | null
      if (target === null) return
      const row = target.closest<HTMLElement>('[class*="_sessionRow"]')
      if (row === null) return
      const button = menuButtonOf(row)
      if (button === null) return
      const title = row.querySelector<HTMLElement>('[class*="_title"]')?.textContent?.trim() ?? ''
      const sessionId = rowSessionId(row)
      anchor = { button, title, sessionId }
      scheduleInject()
    }
    document.addEventListener('click', onDocumentClick, true)

    // Re-inject whenever a menu list mounts/updates (React recreates the list
    // on every open, so the injected node must follow).
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        if (record.type !== 'childList') continue
        const target = record.target
        if (target === document.body) { scheduleInject(); break }
        if (target instanceof HTMLElement
          && (target.matches('[role="menu"]') || target.closest('[role="menu"]') !== null)) {
          scheduleInject()
          break
        }
      }
    })
    observer.observe(document.body, { childList: true, subtree: true })

    injectAll()
    return () => {
      document.removeEventListener('click', onDocumentClick, true)
      observer.disconnect()
      if (injectRaf !== 0) cancelAnimationFrame(injectRaf)
      closeDialog()
      // Remove the injected items (and with them their click listeners).
      for (const clone of injected) clone.remove()
      injected.clear()
      anchor = null
    }
  }, TOUCH_QUERY)
}
