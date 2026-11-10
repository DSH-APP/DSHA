import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement, ReactNode } from 'react'
import type { PropsLocale } from '@deepseek-ai/dsh-client-ui-slots'
import * as primitives from '@deepseek-ai/dsh-client-ui-primitives'
import {
  FileShareError,
  deliverFile,
  fileNameOf,
  canShareFiles,
  mimeTypeOf,
  readWholeFile,
} from './file-share-core.ts'
import type { ReadBytesResult, ShareOutcome } from './file-share-core.ts'
import { FILE_SHARE_NS } from './file-share-locale.ts'
import type { FileShareKey } from './file-share-locale.ts'

/**
 * Host 0.2.0 slot contracts this feature contributes to (declared by
 * ui-sidebar-files and ui-sidebar-documentpreview; the repo's locked
 * dsh-client-ui-slots 0.1.0-rc.6 typings predate them). Both are session
 * list slots whose owner passes `{ absolutePath }` (files: the tree root;
 * document: the previewed file's Host path, rendered only once it is known).
 */
declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface SlotMap {
    'sidebar.right.tab.files.actions': { kind: 'list'; scope: 'session'; owner: { absolutePath: string } }
    'sidebar.right.tab.document.actions': { kind: 'list'; scope: 'session'; owner: { absolutePath: string } }
  }
}

/**
 * Session-bound ranged read over the host's `remote.workspaceFiles.readBytes`.
 * @param sessionId - Session whose workspace authorizes the read.
 * @param path - absolute or workspace-relative path.
 * @param range - byte window.
 * @param signal - cancellation.
 */
export type ReadFileRange = (
  sessionId: string,
  path: string,
  range: { offset: number; length: number },
  signal: AbortSignal,
) => Promise<ReadBytesResult>

/** Business face both share controls receive through their slot `inject`. */
export interface FileShareInject {
  readFileRange: ReadFileRange
}

/** Props both slot entries consume (a supertype of the composed slot props). */
export interface FileShareSlotProps extends FileShareInject, PropsLocale<typeof FILE_SHARE_NS> {
  /** Owner share: the tree root (files) or the previewed file (document). */
  absolutePath: string
  /** Framework-resolved session id. */
  sessionId: string
}

type Translate = (key: FileShareKey, params?: Record<string, unknown>) => string

/** Host icon / toast component shapes, typed locally (see core/icon-compat.ts for why). */
type HostIcon = (props: { size?: number; className?: string }) => ReactElement | null
type HostToast = (props: { text: string; icon?: ReactNode; onDone: () => void }) => ReactElement | null

const hostTable = primitives as unknown as Record<string, unknown>
// 宿主下限 0.2.2-rc.1 只导出 *OutlineRegular（*16 是 0.1.x 的名字，永远取不到）。
const asIcon = (value: unknown): HostIcon | undefined =>
  typeof value === 'function' ? value as HostIcon : undefined
const hostShareIcon = asIcon(hostTable.IconShareOutlineRegular)
const hostWarningIcon = asIcon(hostTable.IconWarningOutlineRegular)
const HostToastComponent = typeof hostTable.Toast === 'function' ? hostTable.Toast as HostToast : undefined

/** Inline share glyph for injected DOM rows and hosts without a share icon (currentColor). */
const SHARE_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">'
  + '<path d="M8 1.5v8.5M8 1.5 5 4.5M8 1.5l3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>'
  + '<path d="M5 7H4a1.5 1.5 0 0 0-1.5 1.5v4A1.5 1.5 0 0 0 4 14h8a1.5 1.5 0 0 0 1.5-1.5v-4A1.5 1.5 0 0 0 12 7h-1" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>'

const DOWNLOAD_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">'
  + '<path d="M8 1.5v8.5M8 10 5 7M8 10l3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>'
  + '<path d="M5 12.5H4A1.5 1.5 0 0 0 2.5 14v.5h11V14A1.5 1.5 0 0 0 12 12.5h-1" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>'

/** 平台没有 Web Share 时按钮只能"下载"，图标也跟着换成下载方向（诚实语义）。 */
function DownloadGlyph(): ReactElement {
  return <span aria-hidden="true" dangerouslySetInnerHTML={{ __html: DOWNLOAD_SVG }} />
}

function ShareGlyph(): ReactElement {
  if (hostShareIcon !== undefined) {
    const Icon = hostShareIcon
    return <Icon size={16} />
  }
  return <span aria-hidden="true" dangerouslySetInnerHTML={{ __html: SHARE_SVG }} />
}

/**
 * Minimal self-built notice for hosts without the primitives Toast: one
 * body-level status line, replaced by the next one, gone after 3s. Mounted on
 * <body> so no transformed sheet ancestor can mis-place the fixed box.
 */
function showFallbackNotice(text: string): void {
  document.querySelector('[data-mobile-nav="file-share-toast"]')?.remove()
  const node = document.createElement('div')
  node.dataset.mobileNav = 'file-share-toast'
  node.setAttribute('role', 'status')
  node.textContent = text
  document.body.appendChild(node)
  setTimeout(() => node.remove(), 3000)
}

/**
 * The button label: 「分享」 only when the platform really has Web Share, else
 * 「下载」 — the press can only end in a download there, and calling that
 * 「分享」 was the owner's 2026-10-07 complaint.
 * @param t - file-share copy.
 * @param name - displayed file name.
 * @returns the label for the current state.
 */
function shareLabel(t: Translate, name: string): string {
  return canShareFiles(platformDeps()) ? t('shareFile', { name }) : t('downloadFile', { name })
}

/** Copy for one outcome; null when the outcome speaks for itself (sheet shown / dismissed). */
function outcomeText(outcome: ShareOutcome, t: Translate): string | null {
  if (outcome.kind !== 'downloaded') return null
  if (outcome.reason === 'too-large') return t('downloadedTooLarge')
  if (outcome.reason === 'share-failed') return t('downloadedShareFailed')
  return t('downloadedUnsupported')
}

/** Copy for one failure. */
function failureText(error: unknown, t: Translate): string {
  if (error instanceof FileShareError) {
    if (error.code === 'too-large') return t('errorTooLarge')
    if (error.code === 'not-found') return t('errorNotFound')
  }
  return t('errorGeneric', { message: error instanceof Error ? error.message : String(error) })
}

/** Anchor download, revoked once the browser has had time to start it. */
function downloadFile(file: File): void {
  const url = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = url
  link.download = file.name
  link.rel = 'noopener'
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

/** The live platform seams for deliverFile. */
function platformDeps(): Parameters<typeof deliverFile<File>>[1] {
  const nav = navigator as Navigator & Partial<Pick<Navigator, 'canShare' | 'share'>>
  const bridge = (window as Window & { DSHA?: {
    canShareFile?: (file: File) => boolean
    shareFile?: (file: File) => Promise<void>
  } }).DSHA
  return {
    canShare: typeof bridge?.canShareFile === 'function'
      ? data => data.files.length === 1 && bridge.canShareFile!(data.files[0]!)
      : typeof nav.canShare === 'function' ? data => nav.canShare!(data) : undefined,
    share: typeof bridge?.shareFile === 'function'
      ? data => bridge.shareFile!(data.files[0]!)
      : typeof nav.share === 'function' ? data => nav.share!(data) : undefined,
    download: downloadFile,
  }
}

/** Shared share-flow state: single flight, busy path, failure notice. */
function useFileShare(sessionId: string, readFileRange: ReadFileRange, t: Translate): {
  busyPath: string | null
  share: (path: string) => void
  toast: ReactNode
} {
  const [busyPath, setBusyPath] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ seq: number; text: string } | null>(null)
  const inFlight = useRef<AbortController | null>(null)
  const seq = useRef(0)
  const latest = useRef({ sessionId, readFileRange, t })
  latest.current = { sessionId, readFileRange, t }

  useEffect(() => () => inFlight.current?.abort(), [])

  const announce = useCallback((text: string): void => {
    if (HostToastComponent === undefined) {
      showFallbackNotice(text)
      return
    }
    seq.current += 1
    setNotice({ seq: seq.current, text })
  }, [])

  const share = useCallback((path: string): void => {
    if (inFlight.current !== null) return
    const controller = new AbortController()
    inFlight.current = controller
    setBusyPath(path)
    const { sessionId: id, readFileRange: read, t: tr } = latest.current
    const run = async (): Promise<void> => {
      const { parts } = await readWholeFile((p, range, signal) => read(id, p, range, signal), path, controller.signal)
      const name = fileNameOf(path)
      const file = new File(parts, name, { type: mimeTypeOf(name) })
      const outcome = await deliverFile(file, platformDeps())
      const text = outcomeText(outcome, tr)
      if (text !== null) announce(text)
    }
    run().catch((error: unknown) => {
      if (controller.signal.aborted) return
      console.warn('[dsh-web-mobile] file share failed:', error)
      announce(failureText(error, tr))
    }).finally(() => {
      if (inFlight.current === controller) inFlight.current = null
      if (!controller.signal.aborted) setBusyPath(null)
    })
  }, [announce])

  const toast = notice === null || HostToastComponent === undefined
    ? null
    : (
      <HostToastComponent
        key={notice.seq}
        text={notice.text}
        {...(hostWarningIcon === undefined ? {} : { icon: <IconWarning /> })}
        onDone={() => setNotice(null)}
      />
    )
  return { busyPath, share, toast }
}

function IconWarning(): ReactElement | null {
  const Icon = hostWarningIcon
  return Icon === undefined ? null : <Icon />
}

/**
 * Preview header share button, contributed to the documentpreview-declared
 * `sidebar.right.tab.document.actions` list slot ("Header toolbar
 * contributions acting on the previewed file"). The owner renders the slot
 * only once the file's Host path is known, for every renderer (text, code,
 * image, PDF, Office, unsupported).
 */
export function FilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }: FileShareSlotProps): ReactElement {
  const { busyPath, share, toast } = useFileShare(sessionId, readFileRange, t)
  const busy = busyPath !== null
  return (
    <>
      <button
        type="button"
        data-mobile-nav="file-share-preview"
        aria-label={busy ? t('sharing') : shareLabel(t, fileNameOf(absolutePath))}
        aria-busy={busy}
        disabled={busy}
        onClick={() => share(absolutePath)}
      >
        {canShareFiles(platformDeps()) ? <ShareGlyph /> : <DownloadGlyph />}
      </button>
      {toast}
    </>
  )
}

/** Marker on each injected row button (idempotence + CSS + probe key). */
const ROW_BUTTON = 'file-share-row'

/**
 * Row-end share buttons for the Files tree.
 *
 * The host file tree (ui-sidebar-files FilesBody) gives rows no menu, no
 * long-press and no per-row slot: each file is `<li data-files-entry="file"
 * data-files-path=…>` holding exactly one `<button>` whose click opens the
 * preview. The only extension seat is `sidebar.right.tab.files.actions`, in
 * the tree header. This entry sits there invisibly (a hidden anchor span) to
 * get a session-bound lifetime scoped to ONE tree, and decorates that tree's
 * file rows by APPENDING a plugin-owned button after the host's button.
 * Host nodes are never moved, wrapped or re-parented (#104 rule): React only
 * reconciles the children it rendered, so an extra trailing sibling survives
 * re-renders and leaves with the `li` on unmount. Directories and "other"
 * entries get no button; a reused `li` that turned into a directory drops it.
 */
export function FilesRowShare({ sessionId, readFileRange, t }: FileShareSlotProps): ReactElement {
  const { busyPath, share, toast } = useFileShare(sessionId, readFileRange, t)
  const anchorRef = useRef<HTMLSpanElement | null>(null)
  const latest = useRef({ busyPath, share, t })
  latest.current = { busyPath, share, t }
  const decorateRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    const root = anchorRef.current?.closest<HTMLElement>('[data-files-state="tree"]') ?? null
    const body = root?.querySelector<HTMLElement>('[data-files-body]') ?? null
    if (body === null) return undefined

    const onClick = (event: MouseEvent): void => {
      event.preventDefault()
      event.stopPropagation()
      const button = event.currentTarget as HTMLButtonElement
      const path = button.parentElement?.getAttribute('data-files-path')
      if (path) latest.current.share(path)
    }

    const decorate = (): void => {
      const { busyPath: busy, t: tr } = latest.current
      for (const stale of body.querySelectorAll<HTMLButtonElement>(`[data-mobile-nav="${ROW_BUTTON}"]`)) {
        if (stale.parentElement?.getAttribute('data-files-entry') !== 'file') stale.remove()
      }
      for (const row of body.querySelectorAll<HTMLElement>('li[data-files-entry="file"]')) {
        let button = row.querySelector<HTMLButtonElement>(`:scope > [data-mobile-nav="${ROW_BUTTON}"]`)
        if (button === null) {
          button = document.createElement('button')
          button.type = 'button'
          button.dataset.mobileNav = ROW_BUTTON
          button.innerHTML = canShareFiles(platformDeps()) ? SHARE_SVG : DOWNLOAD_SVG
          button.addEventListener('click', onClick)
          row.appendChild(button)
        }
        const path = row.getAttribute('data-files-path') ?? ''
        const active = busy !== null && busy === path
        const label = active ? tr('sharing') : shareLabel(tr, fileNameOf(path))
        // Single flight is per tree (`inFlight` lives in this component) and
        // `share()` returns immediately while one is running, so a tap on any
        // OTHER row used to do visibly nothing. Block every row button for the
        // flight: the tapped one keeps its 「分享中」 label, the rest read as
        // disabled instead of silently dead. No new async path, no change to the
        // success/failure routes.
        const blocked = busy !== null
        if (button.getAttribute('aria-label') !== label) button.setAttribute('aria-label', label)
        if (button.disabled !== blocked) button.disabled = blocked
        if (active) button.setAttribute('aria-busy', 'true')
        else button.removeAttribute('aria-busy')
      }
    }
    decorateRef.current = decorate

    let raf = 0
    const schedule = (): void => {
      if (raf !== 0) return
      raf = requestAnimationFrame(() => {
        raf = 0
        decorate()
      })
    }
    // childList only: our own aria/disabled writes never re-trigger it, and
    // the appended buttons settle in one extra (idempotent) pass.
    const observer = new MutationObserver(schedule)
    observer.observe(body, { childList: true, subtree: true })
    decorate()
    return () => {
      observer.disconnect()
      if (raf !== 0) cancelAnimationFrame(raf)
      decorateRef.current = null
      for (const button of body.querySelectorAll<HTMLButtonElement>(`[data-mobile-nav="${ROW_BUTTON}"]`)) {
        button.removeEventListener('click', onClick)
        button.remove()
      }
    }
  }, [])

  // Busy state and locale switches re-label the existing buttons in place.
  useEffect(() => {
    decorateRef.current?.()
  }, [busyPath, t])

  return (
    <>
      <span hidden data-mobile-nav="file-share-anchor" ref={anchorRef} />
      {toast}
    </>
  )
}
