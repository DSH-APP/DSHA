import { getFrame } from '../platform/frame.ts'

/** The host's own right-sidebar opener (ui-sidebar-right: ExpandButton). */
export const HOST_FILES_OPENER = '[data-sidebar-right-expand]'
/** The host's collapse control, mounted while the right sidebar is open. */
export const HOST_FILES_CLOSER = '[data-sidebar-right-toggle]'

/** Minimal document surface this helper needs (injectable for tests). */
interface FilesPanelDocument {
  querySelector: (selector: string) => unknown
}

/** Minimal frame surface the explorer fallback touches (injectable for tests). */
interface FilesPanelFrame {
  removeAttribute: (name: string) => void
  setAttribute: (name: string, value: string) => void
}

/**
 * Open the file browser from a mobile control, preferring the surface the user
 * actually has.
 *
 * 1. The host's own right sidebar (`data-sidebar-right-expand`) is the current
 *    file browser: on 0.1.5 it holds the workspace tree (Files tab). The host
 *    renders its opener inside `headerCorner`, which the desktop layout hides
 *    with `display: none`, so the control exists and its click handler runs
 *    while it has no painted size — acting on it programmatically is the
 *    supported path, and forcing the corner visible would fight the very
 *    layout that hides it.
 * 2. Only when that surface is absent (hosts without ui-sidebar-right) do we
 *    fall back to the third-party explorer column, which needs its
 *    `data-aionui-explorer-open` marker; the preview sheet is yielded first
 *    because compat.css gives preview precedence over explorer.
 *
 * Returns true when the official sidebar took the action, so callers can skip
 * their own layout work (the host panel covers the frame on its own).
 */
export function openFilesPanel(
  doc: FilesPanelDocument = document,
  frame: FilesPanelFrame | null = getFrame(),
): boolean {
  // Toggle semantics, because the host swaps controls with the panel state
  // (measured on 0.1.5): while the panel is CLOSED the only opener is
  // `data-sidebar-right-expand`; once it is OPEN that element is unmounted and
  // only `data-sidebar-right-toggle` (Collapse right sidebar) remains. Acting
  // on the expand button alone was a no-op whenever the panel happened to be
  // already open — the exact "tap does nothing / position looks wrong" report.
  // Closing first also keeps the control reachable: the full-screen panel
  // covers the header, so a second tap could never reach our button.
  // 0.2.2 (dsh-client-ui-sidebar-right ExpandButton): on mobile devices the
  // expand button is NOT unmounted while the panel is open - it stays rendered
  // with `disabled`, so clicking it is a no-op and the panel could never be
  // closed from the header (real device 2026-11-05: repeated taps on the files
  // button left the panel open). A disabled opener counts as absent.
  const closer = doc.querySelector(HOST_FILES_CLOSER) as { click?: () => void } | null
  const opener = doc.querySelector(HOST_FILES_OPENER) as { click?: () => void; disabled?: unknown } | null
  const openerUsable = typeof opener?.click === 'function' && opener.disabled !== true
  const hostControl = openerUsable ? opener : closer
  // Duck-typed on purpose: any element exposing click() acts as the control.
  if (typeof hostControl?.click === 'function') {
    hostControl.click()
    return true
  }
  if (frame === null) return false
  frame.removeAttribute('data-aionui-preview-open')
  frame.setAttribute('data-aionui-explorer-open', '')
  return false
}
