window.__ModuleLoader__.load({ id: "dsh-web-mobile", factory: (require) => {
var __modules = {};
__modules["core/icon-compat.js"] = function (require, module, exports) {
"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.IconFolderOpen = exports.IconPanelLeft = exports.IconPaperclip = void 0;
const primitives = __importStar(require("@deepseek-ai/dsh-client-ui-primitives"));
/** 命名缺失时的兜底：渲染成空，绝不抛错。 */
const missingIcon = () => null;
/** 按候选名字顺序在宿主模块里寻找图标组件。 */
const pickIcon = (names) => {
    const table = primitives;
    for (const name of names) {
        const found = table[name];
        if (found !== undefined)
            return found;
    }
    return missingIcon;
};
/** 输入区文件入口（回形针）。 */
exports.IconPaperclip = pickIcon(['IconPaperclipOutlineRegular']);
/** 会话头部的目录抽屉开关。 */
exports.IconPanelLeft = pickIcon(['IconPanelLeftOutlineRegular']);
/** 会话头部的 Files/右侧栏入口。 */
exports.IconFolderOpen = pickIcon(['IconFolderOpenOutlineRegular']);
};
__modules["platform/frame.js"] = function (require, module, exports) {
"use strict";
// Platform layer: the plugin-owned frame marker lookup.
//
// Moved out of effects/phone-chrome.ts (architecture P1). Six effects plus the
// open-files-panel component needed this lookup, which made phone-chrome the
// shared surface of the effects layer (the only runtime import cycle in
// src/client) and the one components → effects layer violation. Nothing here
// knows about features; the marker itself is still owned and set by
// phone-chrome's frame-marker task.
Object.defineProperty(exports, "__esModule", { value: true });
exports.findFrame = findFrame;
exports.getFrame = getFrame;
exports.getDrawer = getDrawer;
exports.foreignModalOpen = foreignModalOpen;
/** The AppFrame element: direct parent of the shell overlay layer. */
function findFrame() {
    return document.querySelector('[data-shell-overlay]')?.parentElement ?? null;
}
/**
 * Memoized frame lookup. The marked column survives React swaps, so the cached
 * node stays valid while it is still connected AND still carries the marker;
 * anything else re-queries. Streamed output wakes every `*` task each frame and
 * each task resolves the frame again (measured: 1063 full-document
 * `[data-mobile-nav="frame"]` queries in a 4s mutation storm at ~60Hz).
 *
 * The cache is the one piece of module-level state this layer owns; the
 * architecture ledger carries that fact explicitly.
 */
let frameCache = null;
/** Resolve the plugin-owned frame marker, falling back to the raw shell frame. */
function getFrame() {
    const cached = frameCache;
    if (cached !== null && cached.isConnected && cached.getAttribute('data-mobile-nav') === 'frame') {
        return cached;
    }
    const marked = document.querySelector('[data-mobile-nav="frame"]');
    const found = (marked ?? findFrame());
    frameCache = found;
    return found;
}
/**
 * The sidebar column, i.e. the frame's drawer element.
 *
 * Host 0.2.2 renders a native drawer mask BEFORE the sidebar whenever the shell
 * is in drawer mode, so a `> :first-child` anchor resolves to the mask (drawer
 * chrome drawn on the mask, real drawer unstyled, gestures aimed at the mask).
 * The column is matched by its `_sidebarCol` class suffix, which every
 * supported host (>=0.2.2-rc.1, dsh-client-ui-layout) renders; the first-child
 * fallback for pre-0.2.2 hosts was removed with the host floor.
 */
function getDrawer() {
    const frame = getFrame();
    if (frame === null)
        return null;
    const column = frame.querySelector('[class*="_sidebarCol"]');
    return column instanceof HTMLElement ? column : null;
}
/**
 * Whether a dialog that the drawer itself does NOT contain owns the screen.
 *
 * Host 0.2.2 turned the sidebar into a modal dialog: dsh-client-ui-sidebar
 * renders `role="dialog"` + `aria-modal="true"` + `data-shortcut-modal="sidebar"`
 * on the SidebarRoot it puts INSIDE the `_sidebarCol` column, whenever the
 * shell is in drawer mode and the drawer is expanded. A bare
 * `[aria-modal="true"]` probe therefore reports "a modal is up" for as long as
 * the drawer is open — and every guard written against it (gesture start,
 * per-move abort, Escape, row-tap close) silently yields to the drawer itself.
 *
 * Measured on 0.2.2-rc.1 at 390x844: the only `aria-modal` while the drawer is
 * open is the drawer's own `hHd-Xa_root`, and `_sidebarCol` CONTAINS it
 * (`colContainsModal: true`); the settings sheet (`VOzbGW_panel`) mounts with
 * `insideCol: false`. On 0.1.0-rc.6 the sidebar package emits no `aria-modal`
 * at all (grep: 0 vs 1), so this is a 0.2.2 addition, not a long-standing shape.
 */
/* @patch
   id: drawer-own-dialog-not-a-modal
   for: dsh-client-ui-sidebar 0.2.2-rc.1 的 SidebarRoot（抽屉态、展开时在 _sidebarCol 内渲染 role=dialog + aria-modal=true + data-shortcut-modal=sidebar）
   why: 0.2.2 之前侧栏没有任何 aria-modal（0.1.0-rc.6 该包 grep 计数 0 vs 1），插件各处守卫都按「出现 aria-modal = 外来模态升起」写；宿主把抽屉本身标成模态后，裸查询在抽屉打开期间恒真，手势起手/每帧/松手判定全部让位给自己
   until: 宿主不再把抽屉渲染成 aria-modal 对话框，或给「外来模态」提供独立标记（届时可退回单次查询）
   test: tests/sidebar-swipe.test.ts
*/
function foreignModalOpen() {
    const modals = document.querySelectorAll('[aria-modal="true"]');
    if (modals.length === 0)
        return false;
    const drawer = getDrawer();
    if (drawer === null)
        return true;
    // Every match must be checked, not just the first: document order puts the
    // drawer's own dialog ahead of a sheet opened ON TOP of it (measured with the
    // drawer expanded and the settings sheet open: ["sidebar", "settings"]), so a
    // single querySelector would answer "inside the drawer" and miss the sheet.
    for (const modal of modals)
        if (!drawer.contains(modal))
            return true;
    return false;
}
};
__modules["components/open-files-panel.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HOST_FILES_CLOSER = exports.HOST_FILES_OPENER = void 0;
exports.openFilesPanel = openFilesPanel;
const frame_ts_1 = require("./platform/frame.js");
/** The host's own right-sidebar opener (ui-sidebar-right: ExpandButton). */
exports.HOST_FILES_OPENER = '[data-sidebar-right-expand]';
/** The host's collapse control, mounted while the right sidebar is open. */
exports.HOST_FILES_CLOSER = '[data-sidebar-right-toggle]';
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
function openFilesPanel(doc = document, frame = (0, frame_ts_1.getFrame)()) {
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
    const closer = doc.querySelector(exports.HOST_FILES_CLOSER);
    const opener = doc.querySelector(exports.HOST_FILES_OPENER);
    const openerUsable = typeof opener?.click === 'function' && opener.disabled !== true;
    const hostControl = openerUsable ? opener : closer;
    // Duck-typed on purpose: any element exposing click() acts as the control.
    if (typeof hostControl?.click === 'function') {
        hostControl.click();
        return true;
    }
    if (frame === null)
        return false;
    frame.removeAttribute('data-aionui-preview-open');
    frame.setAttribute('data-aionui-explorer-open', '');
    return false;
}
};
__modules["components/MobileNavToggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MobileNavToggle = MobileNavToggle;
const jsx_runtime_1 = require("react/jsx-runtime");
const icon_compat_ts_1 = require("./core/icon-compat.js");
const open_files_panel_ts_1 = require("./components/open-files-panel.js");
/**
 * Mobile-only icon buttons next to the session title:
 * - toggle: opens the directory drawer on narrow screens.
 * - files: opens the file browser directly — one tap, no drawer round-trip.
 *   Which surface that is (host right sidebar vs. the third-party explorer
 *   sheet) is decided in open-files-panel.ts. The hero/blank phases have no
 *   session header, so this control is absent there; the files entry in those
 *   phases is the right-edge leftward swipe (sidebar-swipe.ts).
 * Hidden entirely on wide screens (CSS media query).
 */
function MobileNavToggle({ toggleSidebar, t }) {
    const toggleExplorer = () => {
        (0, open_files_panel_ts_1.openFilesPanel)();
    };
    return ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "toggle", "aria-label": t('open'), title: t('open'), onClick: () => toggleSidebar(), children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconPanelLeft, { size: 16 }) }), (0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "files", "aria-label": t('files'), title: t('files'), onClick: toggleExplorer, children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconFolderOpen, { size: 16 }) })] }));
}
};
__modules["components/ComposerFileButton.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ComposerFileButton = ComposerFileButton;
const jsx_runtime_1 = require("react/jsx-runtime");
const icon_compat_ts_1 = require("./core/icon-compat.js");
/**
 * Mobile-only composer file entry, kept visible outside the "+" command menu.
 *
 * The 0.1.6-alpha.2 host deleted the composer's paperclip attach button: the
 * only file entry left is the 「文件」row inside the "+" listbox (the trigger's
 * aria-label is 「添加文件或调用指令」). The host still mounts its own hidden
 * `input[type=file]` in the composer tool row and its own command opens the
 * native dialog with exactly `fileInputRef.current?.click()`, so this control
 * hands the choice back to that same input instead of reimplementing intake:
 * file validation, upload and the availability policy all stay host-owned.
 * Since 2026-10-06 the tap opens the plugin's two-option sheet first
 * (effects/composer-file-picker.ts: 上传图片 / 上传附件) — the button itself only
 * renders and carries the disabled arms, so the picker path lives in one place.
 *
 * The control is contributed to the host-declared `conversation.input.left`
 * list slot ("Compact controls at the left of the composer tool row"), which
 * keeps it inside the tools lane beside the plus button without touching
 * host-owned React DOM. The seat is session-scoped, so the hero/blank phase
 * (no session) keeps the "+" menu as its only file entry.
 *
 * Availability mirrors the host's `canAcceptDrop` as far as it is observable:
 * a non-plain input phase (adjudicating/claimed/submitting = the machine is
 * busy) and a subagent session both refuse attachments. The host's own
 * `locked` / `addFiles === undefined` arms are package-private, so a missing
 * session seat also disables the control. Hidden on mouse-driven windows at
 * every width (the shared desktop hide block in misc.css.ts); wide touch
 * (tablet ≥1024 + coarse pointer) puts it back in that file's tablet block (#259).
 */
function ComposerFileButton({ useInput, useSession, t }) {
    const busy = useInput((state) => state.phase !== 'plain');
    const subagent = useSession((state) => state.subagent !== null);
    const disabled = busy || subagent;
    return ((0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "file-upload", "aria-label": t('fileUpload'), title: t('fileUpload'), disabled: disabled, children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconPaperclip, { size: 16 }) }));
}
};
__modules["styles/base.css.js"] = function (require, module, exports) {
"use strict";
// base — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Do not reorder: styles/index.ts concatenates in this exact order.
Object.defineProperty(exports, "__esModule", { value: true });
exports.BASE_CSS = void 0;
exports.BASE_CSS = `
[data-mobile-nav="toggle"],
[data-mobile-nav="files"] {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--dsw-alias-label-secondary, inherit);
  cursor: pointer;
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
@media (hover: hover) {
  [data-mobile-nav="toggle"]:hover,
  [data-mobile-nav="files"]:hover {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
}
[data-mobile-nav="toggle"]:active,
[data-mobile-nav="files"]:active {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="toggle"]::after,
[data-mobile-nav="files"]::after {
  content: '';
  position: absolute;
  inset: -8px;
}
[data-mobile-nav="toggle"]:focus-visible,
[data-mobile-nav="files"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 1px;
}
[data-mobile-nav="session-log-wrap"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
}
[data-mobile-nav="session-log"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: var(--dsw-radius-md, 8px);
  background: transparent;
  color: var(--dsw-alias-label-secondary, inherit);
  cursor: pointer;
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="session-log"]::after {
  content: '';
  position: absolute;
  inset: -8px -6px;
}
@media (hover: hover) {
  [data-mobile-nav="session-log"]:hover:not(:disabled) {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
}
[data-mobile-nav="session-log"]:active:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="session-log"][aria-expanded="true"]:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="quick-settings-wrap"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  margin-right: 6px;
}
[data-mobile-nav="quick-settings"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: var(--dsw-radius-md, 8px);
  background: transparent;
  color: var(--dsw-alias-label-secondary, inherit);
  cursor: pointer;
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="quick-settings"]::after {
  content: '';
  position: absolute;
  inset: -7px;
}
@media (hover: hover) {
  [data-mobile-nav="quick-settings"]:hover {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
}
[data-mobile-nav="quick-settings-wrap"] {
  position: relative;
  z-index: 1;
}
[class*="sidebarCol"] [class*="triggerRow"][data-mobile-nav-row-pressed] > button,
[class*="sidebarCol"] [class*="triggerRow"][data-mobile-nav-row-pressed] > * > button:not([data-mobile-nav="quick-settings"]) {
  box-shadow: inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active, color-mix(in srgb, currentColor 8%, transparent));
}
[data-mobile-nav="quick-settings"]:active,
[data-mobile-nav="quick-settings"][data-mobile-nav-quick-pressed] {
  background: var(--dsw-alias-interactive-bg-active, rgba(0, 0, 0, .1));
  transform: scale(.94);
}
[data-mobile-nav="quick-settings"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 1px;
}
[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"]:has(> button[data-phase], > [role="status"]) [data-mobile-nav="quick-settings-wrap"] {
  display: none !important;
}
html[data-mobile-nav-quick-pending] [role="menu"] > [role="presentation"] > :first-child > [role="menuitem"],
[data-mobile-nav-quick-menu] > [role="presentation"] > :first-child > [role="menuitem"] {
  display: none !important;
}
[data-mobile-nav-quick] [role="menu"] {
  visibility: hidden !important;
}
[data-mobile-nav="delete-confirm-title"] {
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  color: var(--dsw-alias-text-primary, rgb(15, 17, 21));
}
[data-mobile-nav="delete-confirm-desc"] {
  font-size: 12px;
  line-height: 17px;
  color: var(--dsw-alias-label-secondary, inherit);
}
[data-mobile-nav="delete-confirm-actions"] {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
[data-mobile-nav="delete-confirm-actions"] > button {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
  border-radius: 18px;
  background: transparent;
  color: var(--dsw-alias-label-primary, inherit);
  font-family: inherit;
  font-size: 14px;
  line-height: 20px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="delete-confirm-yes"] {
  border-color: transparent !important;
  background: var(--dsw-alias-state-error-primary, #b91c1c) !important;
  color: #ffffff !important;
}
[data-mobile-nav="delete-confirm-actions"] > button:disabled {
  opacity: .55;
  cursor: default;
}
[data-mobile-nav="delete-error"] {
  width: 100%;
  font-size: 14px;
  line-height: 20px;
  color: var(--dsw-alias-state-error-primary, #b91c1c);
}
[data-mobile-nav="delete-dialog-backdrop"] {
  position: fixed;
  inset: 0;
  z-index: 55;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(0, 0, 0, .4);
  animation: dsh-web-mobile-fade .2s var(--ds-ease-in-out, ease-in-out);
}
[data-mobile-nav="delete-dialog"] {
  position: static;
  width: min(420px, calc(100vw - 32px));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(248, 249, 250, .58);
  -webkit-backdrop-filter: blur(40px) saturate(1.5);
  backdrop-filter: blur(40px) saturate(1.5);
  box-shadow: rgba(0, 0, 0, .04) 0 0 0 .5px, rgba(0, 0, 0, .04) 0 3px 8px 0, rgba(0, 0, 0, .05) 0 0 20px 0;
}
@media (prefers-reduced-motion: reduce) {
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="file-picker"] {
    animation: none !important;
  }
}
[data-mobile-nav="file-picker-backdrop"] {
  position: fixed;
  inset: 0;
  z-index: 55;
  background: transparent;
}
[data-mobile-nav="file-picker"] {
  position: fixed;
  box-sizing: border-box;
  width: max-content;
  min-width: 0;
  max-width: min(78vw, 240px);
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 5px;
  border-radius: 12px;
  background: var(--dsw-alias-bg-layer-2, #fff);
  box-shadow: rgba(0, 0, 0, .16) 0 8px 28px, rgba(0, 0, 0, .06) 0 0 0 .5px;
  animation: dsh-web-mobile-fade .12s var(--ds-ease-in-out, ease-in-out);
}
[data-mobile-nav="file-picker-option"] {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--dsw-alias-label-primary, currentColor);
  font: inherit;
  font-size: 14px;
  line-height: 1.3;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="file-picker-icon"] {
  flex: none;
  display: inline-flex;
  color: var(--dsw-alias-label-secondary, currentColor);
}
[data-mobile-nav="file-picker-icon"] svg {
  width: 18px;
  height: 18px;
}
[data-mobile-nav="file-picker-option"]:active {
  background: var(--dsw-alias-interactive-bg-active, rgba(127, 127, 127, .12));
}
@media (max-width: 1023px) and (pointer: coarse) {
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed])) [role="menu"] {
    z-index: 1400 !important;
  }
  body:has([data-mobile-nav="hit"][data-active])
    > div:has(> [role="dialog"][aria-modal="true"]) {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog-backdrop"] {
    z-index: 1400 !important;
  }
  [data-mobile-nav="file-picker-backdrop"] {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog"] {
    z-index: 1401 !important;
  }
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [data-usage-stats-panel] {
    z-index: 1400 !important;
  }
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [class*="_overlayLayer"] {
    z-index: 1400 !important;
  }
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"]) [data-mobile-nav="fab"] {
    z-index: 55 !important;
  }
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"])
    [class*="_overlayLayer"] {
    z-index: 1400 !important;
  }
}
@media (max-width: 1023px) and (pointer: coarse) {
  @media (hover: none) {
    html,
    body {
      -webkit-tap-highlight-color: transparent;
    }
    :where(button, [role="button"], [role="menuitem"], [role="menuitemradio"], [role="option"], [role="tab"], [role="treeitem"], a[href]) {
      transition: box-shadow .12s var(--ds-ease-in-out, ease-in-out), transform .12s var(--ds-ease-in-out, ease-in-out);
    }
    :where(button, [role="button"], [role="menuitem"], [role="menuitemradio"], [role="option"], [role="tab"], [role="treeitem"], a[href]):where(:active):where(:not(:disabled, [aria-disabled="true"], [aria-selected="true"], [aria-pressed="true"], [aria-checked="true"])) {
      transition: none;
      box-shadow: inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active, color-mix(in srgb, currentColor 8%, transparent));
    }
    :where(button:has(> svg:only-child), button[aria-label]:not(:has(> span, > div))):where(:active):where(:not(:disabled, [aria-disabled="true"])) {
      transition: none;
      transform: scale(.96);
    }
    [data-team-action] > button:active,
    [data-dsha-lineage-trigger]:active {
      box-shadow: none;
      transform: none;
    }
    @media (prefers-reduced-motion: reduce) {
      :where(button, [role="button"], [role="menuitem"], [role="menuitemradio"], [role="option"], [role="tab"], [role="treeitem"], a[href]) {
        transition: none;
      }
      :where(button:has(> svg:only-child), button[aria-label]:not(:has(> span, > div))):where(:active) {
        transform: none;
      }
    }
  }
}
[data-mobile-nav="fab"] {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 12px);
  left: 10px;
  z-index: 21;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
  border-radius: 50%;
  background: var(--dsw-alias-button-floating-fill, #ffffff);
  color: var(--dsw-alias-label-primary, inherit);
  cursor: pointer;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .18);
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="fab"]::after {
  content: '';
  position: absolute;
  inset: -3px;
}
@media (hover: hover) {
  [data-mobile-nav="fab"]:hover {
    background: var(--dsw-alias-button-floating-hover, rgba(0, 0, 0, .08));
  }
}
[data-mobile-nav="fab"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 2px;
}
html [data-mobile-nav="frame"]::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 1250;
  background: rgba(0, 0, 0, .45);
  opacity: 0;
  transition: opacity .28s var(--ds-ease-in-out, ease-in-out);
  pointer-events: none;
}
html [data-mobile-nav="frame"][data-mobile-nav-dim="1"]::after {
  opacity: 1;
}
@media (prefers-reduced-motion: reduce) {
  html [data-mobile-nav="frame"]::after {
    transition: none;
  }
}
[data-mobile-nav="hit"] {
  position: absolute;
  inset: 0;
  z-index: 1250;
  background: transparent;
  cursor: pointer;
  transition: none;
  -webkit-tap-highlight-color: transparent;
}
@keyframes dsh-web-mobile-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes dsh-web-mobile-sheet-in {
  from {
    transform: translateY(14px) scale(.98);
  }
  to {
    transform: none;
  }
}
@keyframes dsh-web-mobile-sheet-up {
  from {
    opacity: 0;
    transform: translateY(28px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
`;
};
__modules["styles/layout.css.js"] = function (require, module, exports) {
"use strict";
// layout — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: the mobile media query opens and closes in this file.
Object.defineProperty(exports, "__esModule", { value: true });
exports.LAYOUT_CSS = void 0;
exports.LAYOUT_CSS = `
@media (max-width: 1023px) and (pointer: coarse) {
  html,
  body {
    touch-action: pan-y pinch-zoom !important;
    overscroll-behavior-x: none !important;
  }
  html,
  body {
    height: 100% !important;
    overflow: hidden !important;
    overscroll-behavior: none !important;
    background: var(--dsw-alias-bg-base, #fff) !important;
  }
  html [data-mobile-nav="frame"] {
    box-sizing: border-box !important;
    position: relative !important;
    height: var(--dsha-mobile-visible-vh, 100%) !important;
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
    padding-top: var(--dsha-safe-area-inset-top, env(safe-area-inset-top, 0px)) !important;
    padding-bottom: env(safe-area-inset-bottom, 0px) !important;
  }
  [data-mobile-nav="frame"] > [class*="_centerCol"] {
    grid-area: 1 / 1 / 2 / 2 !important;
  }
  [data-mobile-nav="frame"] > [class*="_drawerMask"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
    position: absolute !important;
    inset: 0 auto 0 0 !important;
    width: min(88vw, 280px) !important;
    z-index: 1300 !important;
    transform: translateX(-110%);
    transition: transform .28s var(--ds-ease-in-out, ease-in-out);
    padding-top: env(safe-area-inset-top, 0px) !important;
    border-right: none !important;
    background: var(--dsw-specific-sidebar-fill, #f9fafb);
    touch-action: pan-y pinch-zoom !important;
  }
  body[data-dsh-bg] [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
    background: var(--dsw-static-neutral-bluish-50, #f9fafb);
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
    background: var(--dsw-static-neutral-bluish-900, #1b1b1c);
  }
  [data-mobile-nav="frame"][data-sidebar-collapsed] > [class*="_sidebarCol"] {
    width: min(88vw, 280px) !important;
    transform: translateX(-110%) !important;
  }
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) > [class*="_sidebarCol"] {
    transform: none !important;
  }
  html [data-mobile-nav="frame"] [class*="hHd-Xa_toggle"]:is([aria-label*="sidebar" i], [aria-label*="侧边栏"]),
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="侧边栏"],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="侧边栏"] {
    display: none !important;
  }
  [data-sidebar-right-panel="fullscreen"] {
    padding-top: env(safe-area-inset-top, 0px) !important;
  }
  [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    z-index: 11 !important;
  }
  body[data-dsh-bg] [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    background-color: var(--dsw-static-neutral-bluish-00, #fff);
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    background-color: var(--dsw-static-neutral-bluish-950, #151517);
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-sidebar-right-panel] [data-dockkit-split-button],
    [data-sidebar-right-panel] span:has(> [data-dockkit-split-button]),
    [data-sidebar-right-panel] [data-sidebar-right-mode="push"] {
      display: none !important;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
      transition: none !important;
    }
  }
  [data-side="sidebar"],
  [data-side="details"] {
    display: none !important;
  }
  [data-phase] [class*="_scrollBody"] {
    scrollbar-gutter: auto !important;
    scrollbar-width: none;
  }
  [data-phase] [class*="_scrollBody"]::-webkit-scrollbar {
    display: none !important;
    width: 0;
    height: 0;
  }
  [data-mobile-nav="frame"] [data-phase] [class*="_scrollBody"] {
    margin-top: calc(-1 * var(--mobile-nav-header-h, 0px)) !important;
    padding-top: var(--mobile-nav-header-h, 0px) !important;
  }
  html[data-mobile-nav-selecting] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    pointer-events: none !important;
  }
  [data-phase] [class*="_actions"] {
    overflow: hidden;
  }
  [data-phase] [class*="_actions"] [class*="_timeEnd"] {
    flex: 0 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-phase] [role="tooltip"] {
    display: none !important;
    visibility: hidden !important;
    opacity: 0 !important;
    pointer-events: none !important;
  }
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) {
    padding-left: 20px;
    padding-right: 20px;
    font-size: var(--dsw-font-markdown-base-font-size, var(--dsh-content-font-size, 14px)) !important;
  }
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) p,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) li,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) [
      class*="_text_"
    ] {
    font-size: inherit !important;
  }
  [data-phase] table {
    width: 100%;
    max-width: 100%;
  }
  [data-phase] th,
  [data-phase] td {
    max-width: none;
    min-width: 0;
  }
  [data-phase] [class*="_scroll"]:not([class*="_scrollBody"]) img {
    width: auto !important;
    max-width: 100% !important;
    height: auto !important;
    max-height: 220px !important;
    max-height: min(40dvh, 220px) !important;
  }
  [data-phase] [class*="_userStack"],
  [data-phase] [class*="_userStack"] [data-message-bubble] {
    box-sizing: border-box;
    width: fit-content;
    max-width: 100%;
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [class*="heroWorkspaceRow"]:has([data-forge-worktree]) {
      flex-wrap: wrap !important;
      row-gap: 4px !important;
    }
    [class*="heroWorkspaceRow"]:has([data-forge-worktree]) > [class*="heroOptions"] {
      flex: 0 0 100% !important;
      order: -1 !important;
      justify-content: flex-end !important;
      margin-left: 0 !important;
    }
    [class*="heroWorkspaceRow"]:has([data-forge-worktree]) [data-forge-worktree] {
      flex-direction: row !important;
      align-items: center !important;
      gap: 8px !important;
    }
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
    box-sizing: border-box;
    container-type: inline-size;
    container-name: dsh-mobile-composer;
    flex-wrap: nowrap;
    gap: 3px;
    padding-left: 6px;
    padding-right: 6px;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) {
    flex-wrap: wrap;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] > [class*="_primary"]:has(~ [class*="_primary"]) {
    margin-right: 8px;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child {
    flex: 0 1 auto;
    min-width: 0;
    gap: 8px;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] {
    flex: 1 1 auto;
    min-width: 0;
    gap: 3px;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] {
    flex: 0 1 auto;
    min-width: 0;
    max-width: none;
    gap: 0;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] {
    flex: 1 1 auto;
    min-width: 28px;
    max-width: 100%;
    display: flex !important;
    overflow: hidden;
    padding: 0 !important;
    gap: 0 !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > :not([class*="_trigger"]) {
    flex: 0 1 auto;
    min-width: 34px;
    max-width: max-content;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_wrap"] > [class*="_chip"] {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
      display: none !important;
    }
  }
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_trigger"] {
    padding: 0 !important;
    gap: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_triggerIcon"] svg {
    width: 16px !important;
    height: 16px !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    flex: 0 1 auto;
    min-width: 0;
    overflow: visible;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
      flex-basis: auto;
    }
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] {
    display: flex !important;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    overflow: hidden;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] > [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"]):not(:has(> [class*="_trigger"][aria-haspopup="menu"])) {
    flex: 0 0 auto;
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [class*="_7KE1Ra_menu"][role="menu"]:has(> [role="menuitemradio"]) {
      width: min(208px, calc(100vw - 32px)) !important;
      min-width: 0 !important;
      max-width: calc(100vw - 32px) !important;
    }
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > :not([class*="_add"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > :not([class*="_primary"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_add"] {
    flex: none;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"] {
    flex: none;
    min-width: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) {
    justify-content: flex-end;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) > :first-child {
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-right: 0px;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    margin-left: auto;
    margin-right: -4px;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_trigger"] {
    width: 28px;
    height: 34px;
    padding: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [data-mobile-nav="stats-ring-reserve"] {
    margin-left: auto !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_primary"] {
    margin-left: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] {
    flex: none;
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"], > [class*="_root"] > [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_primary"] {
    margin-left: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] {
    justify-content: flex-end;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] > [class*="_primary"] {
    margin-left: 0;
  }
  [class*="_trailing"] > [class*="_primary"] {
    box-sizing: border-box !important;
    width: 28px !important;
    min-width: 28px !important;
    height: 28px !important;
    min-height: 28px !important;
    padding: 0 !important;
    border-radius: 50% !important;
  }
  [class*="_trailing"] > [class*="_primary"] svg {
    width: 16px !important;
    height: 16px !important;
  }
  [data-phase] [class*="_dock"] [class*="_trigger"][aria-haspopup="dialog"] {
    position: relative;
  }
  [data-phase] [class*="_dock"] [class*="_trigger"][aria-haspopup="dialog"]::after {
    content: '';
    position: absolute;
    inset: -4px;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] {
    flex: 1 1 auto;
    justify-content: flex-end;
    position: static !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] > [data-seat-panel] {
    left: 0 !important;
    right: 0 !important;
    max-width: min(100%, 420px) !important;
    transform: none !important;
    margin-left: auto !important;
    margin-right: auto !important;
  }
  @media (max-height: 700px) {
    [data-seat-model-menu] {
      max-height: min(220px, calc(50vh - 110px)) !important;
    }
  }
  @media (orientation: portrait) and (max-height: 505px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] > [data-seat-panel] {
      max-height: calc(50dvh - 45px) !important;
      overflow-y: auto !important;
      overscroll-behavior: contain !important;
    }
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] > [data-seat-panel] > * {
      flex: 0 0 auto !important;
    }
  }
  @media (orientation: landscape) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] > [data-seat-panel] {
      position: fixed !important;
      left: 16px !important;
      right: 16px !important;
      bottom: 12px !important;
      top: auto !important;
      transform: none !important;
      margin: 0 auto !important;
      max-width: min(100%, 420px) !important;
    }
  }
  [data-composer-card] [data-mobile-nav="file-upload"] {
    flex: 0 0 auto !important;
    width: 28px !important;
    min-width: 28px !important;
    max-width: 28px !important;
    height: 28px !important;
    min-height: 28px !important;
    padding: 0 !important;
    position: relative !important;
    margin: 0 0 0 -8px !important;
    display: grid !important;
    place-items: center;
    border: 0 !important;
    border-radius: 999px;
    background: transparent;
    color: inherit;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    content: '';
    position: absolute;
    inset: 3px;
    border-radius: 999px;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    background: transparent !important;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:hover,
  [data-composer-card] [data-mobile-nav="file-upload"]:active,
  [data-composer-card] [data-mobile-nav="file-upload"]:focus-visible {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
    box-shadow: none !important;
    outline: none !important;
    border-color: transparent !important;
  }
  [data-mobile-nav="frame"] [data-phase] header button,
  [data-mobile-nav="frame"] [data-phase] header [role="tab"],
  [data-mobile-nav="frame"] [data-phase] header [role="menuitem"],
  [data-mobile-nav="frame"] [data-phase] header [role="button"],
  [data-mobile-nav="frame"] [data-phase] header [tabindex],
  [data-composer-card] button,
  [data-composer-card] [role="button"],
  [data-composer-card] [tabindex] {
    -webkit-tap-highlight-color: transparent;
  }
  body[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]),
  html[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    background-color: var(--dsw-alias-bg-base, #151517) !important;
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    background-color: var(--dsw-static-neutral-bluish-950, #151517) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header button:active,
  [data-mobile-nav="frame"] [data-phase] header [role="tab"]:active {
    filter: brightness(.92);
  }
  [data-composer-card] [data-mobile-nav="file-upload"]::after {
    content: '';
    position: absolute;
    inset: -7px;
    border-radius: 999px;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:disabled {
    opacity: 0.38;
    cursor: default;
  }
  [data-composer-card]:not(:has(input[type=file])) [data-mobile-nav="file-upload"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-card] {
    font-size: max(14px, var(--dsh-content-font-size, 14px)) !important;
  }
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] {
    padding-top: 2px !important;
    gap: 4px !important;
  }
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [class*="_row"] {
    padding: 0 8px !important;
  }
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [data-composer-input],
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [class*="_scroll"] {
    min-height: 28px !important;
    padding-top: 2px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    --dsh-frame-leading-clearance: 42px;
    padding-top: 0 !important;
    padding-left: 8px !important;
    padding-right: 8px !important;
    min-height: 0 !important;
    position: relative !important;
    background: var(--dsw-alias-bg-layer-1, #fff) !important;
    z-index: 10 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header[class*="headerBlank"] {
    display: none;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > [data-conversation-header-leading] {
    display: flex !important;
    flex-wrap: nowrap !important;
    align-items: center !important;
    box-sizing: border-box;
    width: auto !important;
    min-width: 0 !important;
    padding: 0 !important;
    gap: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-slot="conversation.header.leading"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {
    box-sizing: border-box !important;
    padding-left: max(0px, calc(var(--dsh-frame-leading-clearance, 42px) - 20px)) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] > [class*="_headerCorner"],
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerUtilities"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="toggle"] {
    position: absolute !important;
    left: 8px !important;
    top: auto !important;
    bottom: auto !important;
    z-index: 2 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
    position: absolute !important;
    right: 8px !important;
    left: auto !important;
    top: auto !important;
    bottom: auto !important;
    z-index: 2 !important;
    width: 36px !important;
    height: 36px !important;
    flex: 0 0 36px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
    -webkit-mask-image: none !important;
    mask-image: none !important;
    display: flex !important;
    flex-wrap: nowrap !important;
    flex: 1 1 auto !important;
    width: auto !important;
    max-width: none !important;
    min-width: 0 !important;
    min-height: 40px !important;
    padding-right: 0 !important;
    margin-right: 46px !important;
    gap: 0 4px !important;
    justify-content: flex-start !important;
    align-items: center !important;
    overflow-x: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_headerActions"] {
    position: static !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] :is(button, [role="button"]):not([class*="QsffPG_trigger"]):not([class*="ZKlsPq_root"] button) {
    min-width: 0 !important;
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_crumbs"] {
    flex: 1 1 auto !important;
    width: auto !important;
    min-width: 72px !important;
    max-width: none !important;
    margin-left: 0 !important;
    margin-right: 0 !important;
    min-height: 0 !important;
    padding-right: 0 !important;
    overflow: visible !important;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
    flex: 0 1 auto !important;
    width: auto !important;
    min-width: 0 !important;
    max-width: 100px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    text-overflow: clip !important;
    white-space: nowrap !important;
    text-align: left !important;
    justify-content: flex-start !important;
    touch-action: pan-x !important;
    overscroll-behavior-x: contain !important;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbSeg"] > button {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    max-width: 80px !important;
    overflow-x: hidden !important;
    overflow-y: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    text-align: left !important;
    touch-action: pan-x !important;
    overscroll-behavior-x: contain !important;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbSeg"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    justify-content: flex-start !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    flex: 0 0 auto !important;
    width: auto !important;
    min-width: 0;
    max-width: none !important;
    min-height: 36px !important;
    margin-left: auto !important;
    padding: 0 !important;
    border-top: 0 !important;
    justify-content: flex-end !important;
    gap: 4px !important;
    overflow-x: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_label"]:has(> svg) {
    order: 1;
    flex: 0 1 auto;
    min-width: 0;
    max-width: min(38vw, 220px);
    display: block;
    position: relative;
    box-sizing: border-box;
    padding-left: 18px;
    padding-right: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_label"]:has(> svg) > svg {
    position: absolute !important;
    left: 0 !important;
    top: 50% !important;
    transform: translateY(-50%) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    order: 2;
    max-width: min(40vw, 180px);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button {
    min-width: 0;
    max-width: 100%;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button > * {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button,
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button * {
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
    flex: 0 1 auto !important;
    min-width: 28px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] {
    flex-wrap: nowrap;
    gap: 0 16px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scroll-snap-type: x proximity;
    touch-action: pan-x;
    box-sizing: border-box !important;
    width: 100% !important;
    margin-top: 4px !important;
    padding-right: 8px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] > button {
    flex-shrink: 0;
    white-space: nowrap;
    scroll-snap-align: start;
  }
  [data-mobile-nav="frame"] [data-phase] header:has(> *) {
    min-height: 0 !important;
    grid-template-rows: minmax(36px, auto) minmax(32px, auto) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tab"] {
    min-height: 32px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] {
    height: 21px !important;
    min-height: 21px !important;
    align-items: stretch !important;
    width: max-content !important;
    min-width: 0 !important;
    max-width: min(32vw, 116px) !important;
    flex: 0 0 auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] > button {
    height: 21px !important;
    min-height: 21px !important;
    line-height: 16px !important;
    padding: 0 4px 5px !important;
    align-items: center !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] button:nth-of-type(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] > button:nth-child(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    left: auto !important;
    right: 8px !important;
    margin: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) * {
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"])::-webkit-scrollbar,
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) *::-webkit-scrollbar {
    display: none !important;
    width: 0 !important;
    height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] {
    max-width: min(46vw, 180px) !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] > button {
    max-width: 100% !important;
    min-width: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
    position: absolute !important;
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
    bottom: auto !important;
    margin: 0 !important;
    transform: none !important;
    z-index: 3 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    position: absolute !important;
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
    bottom: auto !important;
    margin: 0 !important;
    transform: none !important;
    z-index: 3 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"]):has([class*="viewsMoreAnchor"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    right: 34px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] span {
    display: block !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  @media (max-width: 767px) and (pointer: coarse) {
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
      grid-template-rows: auto auto !important;
      row-gap: 3.5px !important;
    }
    html [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {
      height: auto !important;
      min-height: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
      margin-right: 34px !important;
      gap: 0 6px !important;
      min-height: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_headerActions"] {
      min-height: 0 !important;
    }
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
      overflow: visible !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
      max-width: 100% !important;
      overflow-x: hidden !important;
      text-overflow: ellipsis !important;
      touch-action: pan-y pinch-zoom !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
      gap: 6px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
      right: 4px !important;
      width: 30px !important;
      height: 30px !important;
      flex: 0 0 30px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="SVAs4q_label"] {
      position: relative !important;
      margin-right: 2px !important;
      flex: 0 1 auto !important;
      min-width: 0 !important;
      overflow: hidden !important;
      text-overflow: ellipsis !important;
      border-radius: 999px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="SVAs4q_label"]::before {
      content: '' !important;
      position: absolute !important;
      inset: -1px -5px !important;
      border-radius: 999px !important;
      background: transparent !important;
      pointer-events: none !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] > button,
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="VoX2oq_trigger"] {
      padding-left: 2px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
      left: 3px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] div:has(> button[class*="_trigger"] > [class*="_count"]) {
      order: 9 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] button[class*="_trigger"] > [class*="_count"] {
      display: none !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
      top: auto !important;
      bottom: -2px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_tabs"] {
      padding-left: 8px !important;
      margin-top: 0 !important;
      margin-bottom: 0 !important;
    }
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_tabs"] {
      overflow: visible !important;
      flex-wrap: nowrap !important;
      align-items: center !important;
    }
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-conversation-tabs][role="tablist"] {
      height: auto !important;
      min-height: 0 !important;
      margin-top: 0 !important;
      overflow: visible !important;
      align-items: flex-end !important;
      flex: 1 1 auto !important;
      min-width: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="viewsMoreAnchor"] {
      flex: 0 0 auto !important;
    }
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-conversation-tabs][role="tablist"] > button[role="tab"],
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-conversation-tabs] > button[class*="_tab"]:not([class*="_tabs"]) {
      --dsh-tab-hit-inline: 0px;
      margin-left: 0 !important;
      margin-right: 0 !important;
      padding: 6px 0 3px !important;
    }
    html [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_tab"]:not([class*="_tabs"]) {
      padding-top: 0 !important;
      align-self: flex-end !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has(> *) {
      grid-template-rows: minmax(36px, auto) minmax(26px, auto) !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tab"] {
      min-height: 26px !important;
    }
  }
  @media (max-height: 480px) and (orientation: landscape) {
    [data-mobile-nav="frame"] [data-phase] header:has(> *) {
      grid-template-rows: minmax(36px, auto) minmax(24px, auto) !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tab"] {
      min-height: 24px !important;
    }
  }
  @media (max-width: 359px) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      display: none !important;
    }
  }
  [data-mobile-nav="frame"][data-mobile-nav-composer-tight] [class*="_composerSeat"] {
    z-index: 31 !important;
  }
  @media (orientation: landscape) {
    [data-mobile-nav="frame"][data-mobile-nav-composer-tight] [class*="_composerSeat"] [data-composer-dock] {
      display: none !important;
    }
  }
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] {
    justify-content: flex-start !important;
    gap: 6px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] [class*="_sep"] {
    margin: 0 2px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] [class*="_pill"] {
    padding-left: 6px !important;
    padding-right: 6px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock]:has([data-composer-stat]) {
    gap: 8px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > [data-mobile-nav="stats"] {
    flex: 1 1 auto !important;
    width: auto !important;
    gap: 12px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] [data-composer-stat] {
    margin: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] [data-composer-stat] [class*="_pill"] {
    padding-right: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] [data-composer-stat] [class*="_pill"] {
    padding-left: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) {
    flex: 0 0 auto !important;
    margin-left: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) button {
    padding: 0 !important;
    gap: 0 !important;
    position: relative !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) button::after {
    content: "";
    position: absolute;
    inset: -8px -6px;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) button > span {
    display: none !important;
  }
  @media (max-width: 767px) {
    [data-mobile-nav="frame"] [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
      --dsh-composer-model-text-display: none;
      --dsh-composer-model-icon-display: block;
    }
    [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_trigger"] {
      padding: 0 !important;
      gap: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_chevron"] {
      margin-left: 0 !important;
    }
  }
  [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_trigger"] {
    max-width: min(360px, 60cqw) !important;
  }
  @media (min-width: 768px) {
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) {
      position: absolute !important;
      left: 8px !important;
      top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
      width: calc(100vw - 16px);
      max-width: calc(100vw - 16px);
      height: auto;
      max-height: min(800px, calc(100vh - 24px - env(safe-area-inset-top, 0px)));
      max-height: min(800px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px)));
      transition: max-height .2s var(--ds-ease-out, ease-in-out);
      flex-direction: column !important;
      border-radius: 14px !important;
      animation: dsh-web-mobile-sheet-in .22s var(--ds-ease-out, ease-in-out);
    }
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [aria-modal="true"][data-shortcut-modal="settings"] {
      max-width: none !important;
      box-sizing: border-box !important;
      padding-top: env(safe-area-inset-top, 0px) !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] [class*="_navList"] {
      scrollbar-width: thin !important;
      -webkit-overflow-scrolling: touch;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] [class*="_navList"]::-webkit-scrollbar {
      display: block !important;
      height: 2px !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] [class*="_navList"]::-webkit-scrollbar-thumb {
      background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
      border-radius: 1px !important;
    }
    [aria-modal="true"][data-shortcut-modal="settings"] [class*="_navList"]::-webkit-scrollbar-track {
      background: transparent !important;
    }
  }
  :has(> [aria-modal="true"][data-shortcut-modal="settings"]) > [class*="_mask"] {
    animation: dsh-web-mobile-fade .18s var(--ds-ease-out, ease-in-out);
  }
  @media (prefers-reduced-motion: reduce) {
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]),
    :has(> [aria-modal="true"][data-shortcut-modal="settings"]) > [class*="_mask"] {
      animation: none !important;
    }
  }
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    max-width: calc(100vw - 32px);
  }
  div[role="dialog"][aria-modal="true"]:has(> :first-child > img) {
    left: 0 !important;
    right: 0 !important;
    width: auto !important;
    max-width: none !important;
    margin-left: 0 !important;
    margin-right: 0 !important;
    touch-action: none !important;
  }
  @media (min-width: 768px) {
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child {
      width: 100%;
      flex-direction: row !important;
      align-items: center;
      gap: 6px;
      padding: 10px 12px 8px;
    }
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child > :first-child {
      display: none !important;
    }
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"] {
    flex: 1 1 auto;
    min-width: 0;
    flex-direction: row !important;
    flex-wrap: nowrap !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    gap: 6px;
    margin-right: 42px;
    scrollbar-width: thin;
    -webkit-overflow-scrolling: touch;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar {
    height: 2px !important;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
    border-radius: 1px !important;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar-track {
    background: transparent !important;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navCell"] {
    flex: 0 0 auto !important;
    white-space: nowrap !important;
    padding: 6px 8px !important;
    gap: 6px !important;
    font-size: 13px !important;
    justify-content: flex-start !important;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navCell"] svg {
    width: 14px !important;
    height: 14px !important;
    flex: none !important;
  }
  [aria-modal="true"][data-shortcut-modal="settings"] [data-slot="settings.action"] {
    display: none !important;
  }
  [aria-modal="true"][data-shortcut-modal="settings"] > [class*="_header"] > button:last-child {
    position: relative;
  }
  [aria-modal="true"][data-shortcut-modal="settings"] > [class*="_header"] > button:last-child::after {
    content: "";
    position: absolute;
    inset: -8px;
    border-radius: 50%;
  }
  [aria-modal="true"] [class*="_cubeRow"] {
    gap: 6px;
  }
  [aria-modal="true"] [class*="_cubeRow"] > * {
    flex: 1 1 0;
    flex-direction: row !important;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 10px 8px;
    min-height: 0;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child {
    flex: 1 1 auto;
    min-height: 0;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > :last-child {
    padding: 0 12px 24px;
  }
  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    position: absolute !important;
    left: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px) !important;
    max-width: calc(100vw - 16px) !important;
    max-height: min(760px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px))) !important;
    transition: max-height .2s var(--ds-ease-out, ease-in-out);
    transform: none !important;
    border-radius: 14px !important;
    animation: none !important;
  }
  @media (max-width: 767px) {
    [aria-modal="true"][data-shortcut-modal="shortcuts"] [class*="_searchRow"] {
      display: none !important;
    }
  }
  :has(> [aria-modal="true"][data-shortcut-modal="shortcuts"]) > [class*="_mask"]::after {
    animation: none !important;
    background: transparent !important;
  }
  li[data-plugin-package],
  li[data-plugin-row],
  li[data-plugin-item] {
    position: relative !important;
  }
  li[data-plugin-package] button[class*="_cardOpen"]::after,
  li[data-plugin-row] button[class*="_rowOpen"]::after,
  li[data-plugin-item] button[class*="_cardOpen"]::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    z-index: 1;
  }
  li[data-plugin-package] :is(button:not([class*="_cardOpen"]), a, input),
  li[data-plugin-row] :is(button:not([class*="_rowOpen"]), a, input),
  li[data-plugin-item] :is(button:not([class*="_cardOpen"]), a, input) {
    position: relative;
    z-index: 2;
  }
  @keyframes dsh-web-mobile-panel-in {
    from { opacity: .55; transform: translateX(100%); }
  }
  @keyframes dsh-web-mobile-panel-reveal {
    from { opacity: 0; transform: translateX(-14px); }
  }
  [data-mobile-nav="frame"]:has([class*="panelRow"][aria-current="page"]) [class*="_centerCol"] > * > * {
    animation: dsh-web-mobile-panel-in .28s cubic-bezier(0, 0, .2, 1) backwards;
  }
  [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {
    animation: dsh-web-mobile-panel-reveal .18s cubic-bezier(0, 0, .2, 1) backwards;
  }
  [data-mobile-nav="panel-ghost"] {
    position: fixed;
    inset: 0;
    z-index: 1200;
    pointer-events: none;
    overflow: hidden;
    background: var(--dsw-alias-bg-layer-1, #fff);
    animation: dsh-web-mobile-panel-ghost-out .28s cubic-bezier(.4, 0, .2, 1) both;
  }
  [data-mobile-nav="panel-ghost"] > * {
    width: 100%;
    height: 100%;
  }
  @keyframes dsh-web-mobile-panel-ghost-out {
    to { opacity: .72; transform: translateX(100%); }
  }
  [data-mobile-nav="frame"] [class*="scrollBody"] [class*="_scroll"]:not([class*="scrollBody"]) > * {
    content-visibility: auto;
    contain-intrinsic-size: auto 320px;
  }
  [data-mobile-nav="frame"] [class*="_textDocument"] [class*="_line"] {
    content-visibility: auto;
    contain-intrinsic-size: auto 20px;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"]:has([class*="panelRow"][aria-current="page"]) [class*="_centerCol"] > * > *,
    [data-mobile-nav="panel-ghost"],
    [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {
      animation: none !important;
    }
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor {
    order: 1;
    width: max-content;
    flex: 0 1 auto;
    min-width: 0;
    max-width: min(40vw, 130px);
    margin-left: auto;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] {
    display: inline-flex !important;
    align-items: center;
    gap: 4px;
    width: 100%;
    max-width: 100%;
    height: 36px !important;
    min-height: 36px !important;
    padding: 0 4px;
    border: 0;
    background: transparent;
    font: inherit;
    font-size: 12px;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > svg {
    position: static !important;
    transform: none !important;
    flex: 0 0 auto;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > svg:last-of-type {
    transition: transform .12s;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"][aria-expanded="true"] > svg:last-of-type {
    transform: rotate(180deg) !important;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > svg:last-of-type {
      transition: none !important;
    }
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor {
      left: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([data-team-action]) .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > span {
      max-width: 4em;
    }
  }
  [data-mobile-nav="frame"] [class*="sessionRow"] [class*="_rowActions"] {
    display: inline-flex !important;
  }
  [data-mobile-nav="frame"] [class*="sessionRow"] [class*="_rowActions"] :is([aria-label="归档会话"], [aria-label="取消归档"], [aria-label="置顶会话"], [aria-label="取消置顶"], [aria-label="Archive session"], [aria-label="Unarchive session"], [aria-label="Pin session"], [aria-label="Unpin session"]) {
    display: none !important;
  }
  [class*="Mbwy4a_headerActions"] {
    gap: 12px !important;
  }
  [class*="Mbwy4a_iconButton"] {
    position: relative;
  }
  [class*="Mbwy4a_headerActions"] [class*="Mbwy4a_iconButton"]::after {
    content: '';
    position: absolute;
    inset: -4px;
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_body"] {
    min-width: 0;
    overflow-x: hidden !important;
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_title"],
  [data-mobile-nav="frame"] [class*="Mbwy4a_detail"],
  [data-mobile-nav="frame"] [class*="Mbwy4a_reviewNote"] {
    overflow-wrap: anywhere;
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_optionLabel"],
  [data-mobile-nav="frame"] [class*="Mbwy4a_description"] {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [class*="Mbwy4a_frame"] {
      padding-left: var(--dsh-composer-side-clearance, 16px) !important;
      padding-right: var(--dsh-composer-side-clearance, 16px) !important;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_header"]:not([class*="Mbwy4a_headerActions"]) {
      position: relative;
      padding: 10px 8px 0 12px !important;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] {
      position: absolute;
      top: 10px;
      right: 6px;
      gap: 20px !important;
      z-index: 1;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] [class*="Mbwy4a_iconButton"] {
      position: relative;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] [class*="Mbwy4a_iconButton"]::after {
      content: '';
      position: absolute;
      inset: -10px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_headingBlock"] {
      flex: 1 1 100%;
      min-width: 0;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_eyebrow"] {
      margin-right: 116px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_title"] {
      padding-top: 2px;
      font-size: 15px;
      line-height: 23px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_card"]:not([class*="Mbwy4a_cardMinimized"]) {
      overflow-x: hidden !important;
      overflow-y: auto !important;
      overscroll-behavior: contain;
      touch-action: pan-y pinch-zoom;
      scrollbar-width: none;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_card"]::-webkit-scrollbar {
      display: none;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_card"]:not([class*="Mbwy4a_cardMinimized"]) > [class*="Mbwy4a_body"] {
      flex: 0 0 auto !important;
      overflow: visible !important;
      overscroll-behavior: auto !important;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_card"]:not([class*="Mbwy4a_cardMinimized"]) > [class*="Mbwy4a_footer"] {
      position: sticky;
      bottom: -10px;
      z-index: 1;
      background: var(--dsw-specific-input-major);
      padding-top: 8px !important;
      padding-bottom: 10px !important;
      margin-top: 4px !important;
      margin-bottom: -10px !important;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_header"]:not([class*="Mbwy4a_headerActions"]) {
      position: relative;
      padding-bottom: 8px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_options"] {
      padding-left: 8px;
      padding-right: 8px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_detail"] p {
      margin: 0 0 8px;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_detail"] p:last-child {
      margin-bottom: 0;
    }
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] [data-mobile-nav="question-expand"] {
    position: relative;
    display: grid;
    place-items: center;
    flex: none;
    width: 24px;
    height: 24px;
    padding: 0;
    border: 0;
    border-radius: 999px;
    background: 0 0;
    color: var(--dsw-alias-label-tertiary);
    cursor: pointer;
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] [data-mobile-nav="question-expand"]::after {
    content: '';
    position: absolute;
    inset: -10px;
  }
  [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] [data-mobile-nav="question-expand"] svg {
    width: 14px;
    height: 14px;
  }
  [data-mobile-nav="frame"] [data-mobile-nav="question-expand"] .dsh-web-mobile-question-collapse {
    display: none;
  }
  [data-mobile-nav="frame"][data-mobile-nav-question-full] [data-mobile-nav="question-expand"] .dsh-web-mobile-question-expand {
    display: none;
  }
  [data-mobile-nav="frame"][data-mobile-nav-question-full] [data-mobile-nav="question-expand"] .dsh-web-mobile-question-collapse {
    display: block;
  }
  [data-mobile-nav="frame"][data-mobile-nav-question-full] [class*="Mbwy4a_frame"] {
    --dsha-mobile-question-band: calc(
      var(--dsha-mobile-visible-vh) - var(--mobile-nav-header-h) - var(--dsha-mobile-question-reserve, 0px)
    );
  }
  [data-mobile-nav="frame"][data-mobile-nav-question-full] [class*="Mbwy4a_card"]:not([class*="Mbwy4a_cardMinimized"]) {
    height: var(--dsha-mobile-question-band, auto);
    max-height: var(--dsha-mobile-question-band, min(60vh, 520px));
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleRow"] {
      padding-left: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] {
      display: contents !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] > [class*="_headerActions"] {
      margin-left: 0 !important;
      justify-content: flex-start !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] > [class*="_crumbs"] {
      overflow: hidden !important;
    }
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] [data-chat-running]:not(:first-child) {
      margin-top: 4px !important;
    }
    [data-mobile-nav="frame"] [data-phase] [data-chat-running] [class*="_runningDivider"] {
      margin: 3px 0 4px !important;
    }
    [data-mobile-nav="frame"] [data-phase] [class*="_scroll"]:has(> [data-chat-flow]) {
      padding-bottom: 6px !important;
    }
    [data-mobile-nav="frame"] [data-phase] [data-chat-flow-kind="turn-tail"] {
      margin-top: 4px !important;
    }
    [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [class*="_row"]:has(> [class*="_trailing"]) {
      padding-bottom: 6px !important;
    }
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) [class*="_add"] {
      width: 30px !important;
      min-width: 30px !important;
      height: 30px !important;
      min-height: 30px !important;
    }
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"],
    [class*="_trailing"] > [class*="_primary"] {
      width: 30px !important;
      min-width: 30px !important;
      height: 30px !important;
      min-height: 30px !important;
      transform: none !important;
    }
    [data-mobile-nav="frame"] [data-phase] [data-composer-card] [data-mobile-nav="file-upload"] {
      width: 30px !important;
      min-width: 30px !important;
      max-width: 30px !important;
      height: 30px !important;
      min-height: 30px !important;
      margin-left: -9px !important;
    }
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) [class*="_add"] svg,
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] svg,
    [class*="_trailing"] > [class*="_primary"] svg,
    [data-mobile-nav="frame"] [data-phase] [data-composer-card] [data-mobile-nav="file-upload"] svg {
      width: 18px !important;
      height: 18px !important;
    }
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_triggerIcon"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_triggerIcon"] svg,
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_triggerIcon"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] button[class*="_trigger"]:not([aria-haspopup]) svg {
      width: 18px !important;
      height: 18px !important;
    }
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) [class*="_add"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_triggerAnchor"] > button,
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_trigger"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"] > [class*="_trigger"],
    [data-mobile-nav="frame"] [data-phase] [data-composer-card] [data-mobile-nav="file-upload"] {
      color: var(--dsw-alias-label-secondary) !important;
    }
  }
}
`;
};
__modules["styles/compat.css.js"] = function (require, module, exports) {
"use strict";
// compat — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: every rule here is mobile-only and the media query opens
// and closes in this file. Concatenation order still matters for the
// cascade (compat intentionally overrides layout), just not for syntax.
Object.defineProperty(exports, "__esModule", { value: true });
exports.COMPAT_CSS = void 0;
exports.COMPAT_CSS = `@media (max-width: 1023px) and (pointer: coarse) {
  .aionui-explorer-handle,
  .aionui-preview-handle {
    display: none !important;
  }
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    position: fixed !important;
    z-index: 55 !important;
    background: var(--aion-bg-base, #ffffff) !important;
    border-left: none !important;
  }
  [data-aionui-explorer-col] {
    visibility: hidden !important;
    left: 8px !important;
    right: 8px !important;
    top: auto !important;
    bottom: 36px !important;
    width: auto !important;
    height: min(55dvh, 460px) !important;
    max-height: calc(100dvh - 44px) !important;
    border-radius: 14px !important;
    overflow: hidden !important;
    box-shadow: 0 -4px 28px rgba(0, 0, 0, .18) !important;
    animation: dsh-web-mobile-sheet-up .24s var(--ds-ease-out, ease-in-out) !important;
  }
  [data-aionui-preview-col] {
    visibility: hidden !important;
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: auto !important;
    bottom: 40px !important;
    width: auto !important;
    height: min(50dvh, 420px) !important;
    max-height: calc(100dvh - 48px) !important;
    border-radius: 14px !important;
    overflow: hidden !important;
    box-shadow: 0 -4px 28px rgba(0, 0, 0, .18) !important;
    z-index: 56 !important;
    animation: dsh-web-mobile-sheet-up .24s var(--ds-ease-out, ease-in-out) !important;
    transition:
      left .24s var(--ds-ease-out, ease-in-out),
      right .24s var(--ds-ease-out, ease-in-out),
      top .24s var(--ds-ease-out, ease-in-out),
      bottom .24s var(--ds-ease-out, ease-in-out),
      width .24s var(--ds-ease-out, ease-in-out),
      height .24s var(--ds-ease-out, ease-in-out),
      border-radius .24s var(--ds-ease-out, ease-in-out),
      box-shadow .24s var(--ds-ease-out, ease-in-out),
      padding-top .24s var(--ds-ease-out, ease-in-out) !important;
  }
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] {
    visibility: visible !important;
  }
  [data-mobile-nav="frame"][data-aionui-explorer-open] [data-aionui-explorer-col] {
    visibility: visible !important;
  }
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-explorer-col] {
    visibility: hidden !important;
  }
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-explorer-col],
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-preview-col] {
    visibility: hidden !important;
    display: none !important;
  }
  .aionui-floating-expand {
    display: none !important;
  }
  [data-mobile-nav="preview-full-toggle"] {
    position: absolute !important;
    right: 36px !important;
    top: 8px !important;
    z-index: 57 !important;
    display: none !important;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    padding: 0;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--aion-text-secondary, var(--dsw-alias-label-secondary, inherit));
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
    transition: background-color .15s, top .24s var(--ds-ease-out, ease-in-out);
  }
  [data-mobile-nav="preview-full-toggle"]::after {
    content: '';
    position: absolute;
    inset: -12px -4px;
  }
  @media (hover: hover) {
    [data-mobile-nav="preview-full-toggle"]:hover {
      background: var(--aion-bg-3, rgba(0, 0, 0, .22));
    }
  }
  [data-mobile-nav="preview-full-toggle"]:active {
    background: var(--aion-bg-active, rgba(0, 0, 0, .28));
  }
  [data-mobile-nav="preview-full-toggle"]:focus-visible {
    outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
    outline-offset: 2px;
  }
  [data-mobile-nav="preview-full-toggle"] svg {
    width: 14px;
    height: 14px;
  }
  [data-aionui-preview-col] [class*="_tabScroll"] {
    padding-right: 34px !important;
  }
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] {
    display: inline-flex !important;
  }
  [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-in {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: inline !important;
  }
  [data-mobile-nav="frame"][data-aionui-preview-open][data-mobile-preview-full] [data-aionui-preview-col] {
    inset: 0 !important;
    left: 0 !important;
    right: 0 !important;
    top: 0 !important;
    bottom: 0 !important;
    width: 100% !important;
    height: 100dvh !important;
    max-height: none !important;
    box-sizing: border-box !important;
    padding-top: env(safe-area-inset-top, 0px) !important;
    border-radius: 0 !important;
    box-shadow: none !important;
    z-index: 57 !important;
    animation: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] {
    top: calc(env(safe-area-inset-top, 0px) + 8px) !important;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-aionui-preview-col],
    [data-mobile-nav="preview-full-toggle"] {
      transition: none !important;
      animation: none !important;
    }
  }
  button[data-dsh-taskboard-entry],
  button[data-dsh-ssh-entry] {
    margin-bottom: 8px !important;
  }
  [data-dsh-taskboard-board] > [class*="_columns"] {
    grid-template-columns: repeat(5, minmax(240px, 1fr)) !important;
    overflow-x: auto !important;
  }
  html[data-dsh-taskboard-active] [data-mobile-nav="fab"],
  html[data-dsh-ssh-active] [data-mobile-nav="fab"],
  html[data-dsh-taskboard-active] [data-mobile-nav="hit"],
  html[data-dsh-ssh-active] [data-mobile-nav="hit"] {
    display: none !important;
  }
  html[data-dsh-taskboard-active] [data-mobile-nav="frame"]::after,
  html[data-dsh-ssh-active] [data-mobile-nav="frame"]::after {
    opacity: 0;
    transition: none;
  }
  [data-dsh-taskboard-board] > [class*="_boardHeader"] [class*="_search"] {
    flex: 1 1 auto !important;
    min-width: 80px !important;
  }
  [aria-modal="true"] [class*="_tabs"] {
    flex-wrap: wrap !important;
    row-gap: 8px !important;
  }
  [aria-modal="true"] [class*="_searchInline"] {
    flex: 1 1 100% !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  [data-dsh-market-root] [class*="tabSearch"] input,
  [data-dsh-market-root] input[class*="tabSearch"] {
    font-size: 16px !important;
  }
  [data-dsh-market-root] [class*="_opPanel"] {
    position: fixed !important;
    top: 50% !important;
    bottom: auto !important;
    left: 50% !important;
    right: auto !important;
    transform: translate(-50%, -50%) !important;
  }
  [data-dsh-market-root] [class*="_titleRow"] {
    flex-wrap: wrap !important;
    row-gap: 6px !important;
  }
  [data-dsh-market-root] [class*="_titleRow"] [class*="_title"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    white-space: nowrap !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
  }
  [data-dsh-market-root] [class*="_titleRow"] button {
    white-space: nowrap !important;
  }
  [data-dsh-market-root] [class*="_byline"] {
    flex-wrap: nowrap !important;
  }
  [data-dsh-market-root] {
    margin-top: 12px !important;
  }
  @media (max-width: 360px) {
    [data-dsh-market-root] [class*="_byline"] {
      gap: 4px !important;
      font-size: 10px !important;
    }
    [data-dsh-market-root] [class*="_byline"] [class*="_dot"] {
      margin-left: 3px !important;
    }
  }
  @media (max-width: 560px) {
    [data-mobile-nav="frame"] [role="dialog"]:has([data-dsh-market-root]) > nav {
      display: flex !important;
    }
    [role="dialog"]:has([data-dsh-market-root]) > nav {
      display: flex !important;
    }
  }
  [class*="usg_"][class*="_statsRow"] {
    flex-direction: column !important;
  }
  [class*="usg_"][class*="_stat"]:not([class*="_statsRow"]) {
    flex: 0 0 auto !important;
    width: 100% !important;
    min-width: 0 !important;
  }
  [aria-modal="true"] details[class*="_customized"]:not([open]) > [class*="_customizedBody"] {
    display: none !important;
  }
  [role="dialog"][aria-modal="true"] [class*="_footer"] {
    flex-wrap: wrap !important;
  }
  [role="dialog"][aria-modal="true"] [class*="_footer"] button[class*="_button"] {
    white-space: nowrap !important;
  }
  [aria-modal="true"] [class*="_cubeRow"] > * {
    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12)) !important;
  }
  [data-aionui-explorer-col] [class*="_tabBar"]:not([class*="_tabBarRight"]) {
    height: 36px !important;
  }
  [data-aionui-explorer-col] [class*="_tabBtn"],
  [data-aionui-explorer-col] [class*="_tabBtnActive"] {
    padding: 0 12px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_searchBox"] {
    height: 32px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_treeRow"] {
    height: 30px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_treeRow"] svg {
    width: 14px !important;
    height: 14px !important;
  }
  [data-aionui-explorer-col] [class*="_scrollArea"] {
    padding-bottom: 28px !important;
  }
  [data-mobile-nav="frame"] [class*="_footerActions"] {
    flex-wrap: wrap !important;
    gap: 6px !important;
  }
  body:has([aria-modal="true"]) .dsh-pet-root,
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"]) .dsh-pet-root,
  body:has([aria-modal="true"]) > [class*="_float"]:has([class*="_sprite"][role="button"]),
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"])
    > [class*="_float"]:has([class*="_sprite"][role="button"]) {
    display: none !important;
  }
  [data-mobile-nav="stats"] {
    margin-top: -2px !important;
    display: flex !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
    height: 28px !important;
    min-height: 28px !important;
    max-height: 28px !important;
    box-sizing: border-box !important;
    white-space: nowrap !important;
    overflow: hidden !important;
    overscroll-behavior-x: none;
    scrollbar-width: none !important;
    padding: 0 !important;
    line-height: 18px !important;
    font-size: 10px !important;
  }
  [data-mobile-nav="stats"]::-webkit-scrollbar {
    display: none !important;
    width: 0 !important;
    height: 0 !important;
  }
  [data-mobile-nav="stats"] > * {
    display: flex !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    white-space: nowrap !important;
    padding: 0 !important;
  }
  [data-mobile-nav="stats"] > *:first-child {
    flex: 0 0 auto !important;
    width: max-content !important;
    min-width: max-content !important;
    max-width: none !important;
  }
  [data-mobile-nav="stats"] > *:last-child {
    display: flex !important;
    align-items: center !important;
    flex: 0 1 auto !important;
    width: auto !important;
    min-width: 0 !important;
    max-width: none !important;
    overflow: hidden !important;
    margin-right: 0 !important;
  }
  [data-mobile-nav="stats"] > *:last-child > * {
    display: flex !important;
    align-items: center !important;
    min-width: 0 !important;
    max-width: 100% !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="stats"] > *:last-child svg {
    flex: 0 0 auto !important;
  }
  [data-mobile-nav="stats"] > *:last-child span {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
  }
  [data-mobile-nav="stats"] button {
    padding: 0 3px !important;
    margin: 0 !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  [data-mobile-nav="stats"] button,
  [data-mobile-nav="stats"] button span,
  [data-mobile-nav="stats"] button svg,
  [data-mobile-nav="stats"] > * {
    font-size: 10px !important;
    line-height: 18px !important;
  }
  [data-mobile-nav="stats"] > *:not(:last-child) {
    margin-right: 3px !important;
  }
  [data-mobile-nav="stats"] * {
    white-space: nowrap !important;
  }
  [data-mobile-nav="stats-ring"] {
    position: absolute !important;
    flex: 0 0 auto !important;
    display: inline-flex !important;
    align-items: center !important;
    min-width: 0 !important;
    margin: 0 2px 0 0 !important;
    padding: 0 !important;
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
  }
  [data-mobile-nav="stats-ring"],
  [data-mobile-nav="stats-ring"] * {
    font-size: 0 !important;
  }
  [data-mobile-nav="stats-ring"] button {
    padding: 0 !important;
    margin: 0 !important;
    gap: 0 !important;
    min-width: 0 !important;
    width: auto !important;
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
  }
  [data-mobile-nav="stats-ring"] svg {
    display: inline-block !important;
    width: 18px !important;
    height: 18px !important;
    flex: 0 0 auto !important;
  }
  [data-mobile-nav="stats-ring"] [class*="_track"] {
    stroke: color-mix(in srgb, var(--dsw-alias-label-primary, #000) 25%, transparent) !important;
  }
  [data-mobile-nav="stats-ring-reserve"],
  [data-mobile-nav="stats-tps-reserve"] {
    visibility: hidden !important;
    pointer-events: none !important;
  }
  [data-mobile-nav="stats-ring-reserve"] {
    flex: 0 0 auto !important;
    display: inline-block !important;
    width: 18px !important;
    height: 18px !important;
    margin: 0 2px 0 0 !important;
    padding: 0 !important;
  }
  [data-mobile-nav="stats-tps"] {
    display: flex !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    margin: 0 !important;
    padding: 0 !important;
    font-size: 10px !important;
    line-height: 18px !important;
    white-space: nowrap !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
  }
  [data-mobile-nav="stats-tps"] * {
    white-space: nowrap !important;
  }
  [data-mobile-nav="stats-tps"] span {
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    min-width: 0 !important;
  }
  [data-mobile-nav="stats-ring-dock"],
  [data-mobile-nav="stats-tps-row"] {
    position: relative;
  }
  [data-mobile-nav="frame"] [data-genui-panel] {
    margin: 6px 12px 4px !important;
    border-color: var(--dsw-alias-border-l1, rgba(0, 0, 0, .12)) !important;
    border-radius: 12px !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelToggle"] {
    padding: 7px 12px !important;
    gap: 8px !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelBadge"] {
    padding: 0 7px !important;
    border-radius: 5px !important;
    font-size: 10.5px !important;
    line-height: 1.7 !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelTitle"] {
    flex: 1 1 auto !important;
    min-width: 0 !important;
    font-size: 12.5px !important;
    line-height: 1.45 !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelChevron"] {
    flex: none !important;
    margin-left: 0 !important;
    padding-left: 4px !important;
  }
  [data-mobile-nav="frame"] [class*="_composerStack"] {
    position: relative;
  }
  [data-mobile-nav="frame"] [data-gitgraph-chip-anchor] {
    position: absolute !important;
    top: 12px !important;
    left: 28px !important;
    right: auto !important;
    bottom: auto !important;
    z-index: 1 !important;
  }
  [data-mobile-nav="frame"] [data-phase="hero"] [data-gitgraph-chip-anchor] {
    top: 134.9px !important;
  }
  [data-mobile-nav="frame"] [class*="_composerStack"]:has([data-gitgraph-chip-anchor]) [class*="_card"]:has(textarea, [data-composer-input]) {
    padding-top: 44px !important;
  }
  [data-mobile-nav="frame"] .meme-picker {
    left: 0 !important;
    right: 0 !important;
    width: auto !important;
    box-sizing: border-box !important;
    max-width: 386px !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid {
    display: grid !important;
    grid-template-columns: repeat(auto-fill, minmax(64px, 1fr)) !important;
    scrollbar-width: thin !important;
    scrollbar-color: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) transparent !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-cell {
    width: 100% !important;
    height: auto !important;
    aspect-ratio: 1 !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar {
    width: 4px !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) !important;
    border-radius: 999px !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar-track {
    background: transparent !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) {
    top: auto !important;
    left: 50% !important;
    right: auto !important;
    bottom: 12px !important;
    transform: translateX(-50%) !important;
    width: min(100% - 24px, 360px) !important;
    max-width: 360px !important;
    max-height: min(55dvh, 440px) !important;
    padding: 30px 6px 10px !important;
    border-radius: 16px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"])::before {
    content: '';
    position: absolute;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    width: 36px;
    height: 4px;
    border-radius: 999px;
    background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
    pointer-events: none;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"] {
    scrollbar-width: thin !important;
    scrollbar-color: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) transparent !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar {
    width: 4px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) !important;
    border-radius: 999px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar-track {
    background: transparent !important;
  }
  [aria-modal="true"] [class*="tabSearchRow"] {
  padding: 2px 4px 16px !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div > [class*="spec"] {
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  max-width: 100% !important;
  font-size: 12px !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div > [class*="nm"] {
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  max-width: 100% !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) {
    flex-wrap: wrap !important;
    align-items: center !important;
    gap: 4px 10px !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div:first-child {
    flex: 1 1 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="grow"] {
    flex: 1 1 auto !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button {
    flex: 0 0 auto !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button[class*="switch"] {
    order: 3 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button:not([class*="switch"]) {
    order: 2 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="owner"] {
    order: 1 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="grow"] {
    order: 0 !important;
  }
  [data-mobile-nav="frame"] [class*="cardShots"] {
  display: flex !important;
  flex-wrap: nowrap !important;
  overflow-x: auto !important;
  -webkit-overflow-scrolling: touch !important;
  scrollbar-width: thin !important;
  min-width: 0 !important;
  width: 100% !important;
  max-width: 100% !important;
  gap: 8px !important;
  padding: 4px 0 !important;
  }
  [data-mobile-nav="frame"] [class*="cardShots"] > [class*="cardShot"] {
  flex: 0 0 min(100%, 420px) !important;
  width: min(100%, 420px) !important;
  max-width: 100% !important;
  height: auto !important;
  display: block !important;
  object-fit: contain !important;
  }
  [data-mobile-nav="frame"] [class*="cardShots"]::-webkit-scrollbar {
  height: 4px !important;
  }
  [data-mobile-nav="frame"] [class*="cardShots"]::-webkit-scrollbar-thumb {
  background: var(--ds-border-color, #ccc) !important;
  border-radius: 4px !important;
}
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-panel {
    min-width: 0 !important;
    max-width: 100% !important;
    overflow-x: hidden !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar {
    gap: 4px !important;
    padding: 6px 8px !important;
    flex-wrap: nowrap !important;
    min-width: 0 !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar-path {
    min-width: 0 !important;
    padding-right: 4px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-path {
    font-size: 13px !important;
    min-width: 0 !important;
    max-width: 220px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar-actions {
    flex-wrap: wrap !important;
    gap: 4px !important;
    justify-content: flex-end !important;
    margin-left: auto !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-toolbar-btn {
    min-height: 34px !important;
    padding: 0 10px !important;
    font-size: 13px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-icon-btn,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-back-btn {
    min-height: 34px !important;
    min-width: 34px !important;
  }
  @media (max-width: 480px) {
    [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-meta {
      display: none !important;
    }
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-statusbar {
    flex-wrap: wrap !important;
    gap: 4px 10px !important;
    padding: 4px 8px calc(4px + env(safe-area-inset-bottom, 0px)) !important;
    font-size: 12px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-renderer,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-renderer-stack,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-csv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-code-body {
    min-width: 0 !important;
    max-width: 100% !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-csv-scroll {
    overflow-x: auto !important;
    -webkit-overflow-scrolling: touch !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-browser-nav,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-subtoolbar {
    flex-wrap: wrap !important;
    gap: 6px !important;
    padding: 4px 8px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-file-row {
    min-height: 44px !important;
    padding: 8px 10px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-file-list [class*="name"] {
    min-width: 0 !important;
  }
  [data-mobile-nav="frame"] .dsfv-produced-chip,
  [data-mobile-nav="frame"] .dsfv-produced-folder {
    min-height: 40px !important;
    padding: 0 12px !important;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"][data-file-viewer-open] [class*="dsfv-"] {
      transition: none !important;
      animation: none !important;
    }
  }
  [data-files-body] li[data-files-entry="file"]:has(> [data-mobile-nav="file-share-row"]) {
    display: flex;
    align-items: center;
  }
  [data-files-body] li[data-files-entry="file"]:has(> [data-mobile-nav="file-share-row"]) > button:first-child {
    flex: 1 1 auto;
    min-width: 0;
  }
  [data-mobile-nav="file-share-row"],
  [data-mobile-nav="file-share-preview"] {
    flex: none;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    border: 0;
    border-radius: var(--dsw-radius-sm, 6px);
    background: transparent;
    color: var(--dsw-alias-label-tertiary, currentColor);
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  [data-mobile-nav="file-share-row"] {
    width: 36px;
    height: 32px;
  }
  [data-mobile-nav="file-share-preview"] {
    width: 32px;
    height: 32px;
    color: var(--dsw-alias-label-secondary, currentColor);
  }
  [data-mobile-nav="file-share-row"] svg,
  [data-mobile-nav="file-share-preview"] svg {
    width: 16px;
    height: 16px;
  }
  [data-mobile-nav="file-share-row"]:active,
  [data-mobile-nav="file-share-preview"]:active {
    background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, 0.16));
  }
  [data-mobile-nav="file-share-row"]:focus-visible,
  [data-mobile-nav="file-share-preview"]:focus-visible {
    outline: 2px solid var(--dsw-alias-border-focus, #4d6bfe);
    outline-offset: -2px;
  }
  [data-mobile-nav="file-share-row"][aria-busy="true"],
  [data-mobile-nav="file-share-preview"][aria-busy="true"] {
    opacity: 0.45;
    cursor: progress;
  }
  [data-mobile-nav="file-share-toast"] {
    position: fixed;
    left: 50%;
    bottom: calc(24px + env(safe-area-inset-bottom, 0px));
    z-index: 1500;
    max-width: min(90vw, 420px);
    padding: 8px 14px;
    border-radius: 10px;
    background: rgba(28, 28, 30, 0.92);
    color: #fff;
    font-size: 13px;
    line-height: 1.45;
    transform: translateX(-50%);
    pointer-events: none;
  }
  .ofm_aurora {
    display: none !important;
  }
  .ofm_wave,
  .ofm_bubble,
  .ofm_fish,
  .ofm_tankrays {
    animation: none !important;
  }
  .ofm_nav,
  .ofm_glass,
  .ofm_hero,
  .ofm_panel,
  .ofm_ann,
  .ofm_modal,
  .ofm_toast {
    -webkit-backdrop-filter: none !important;
    backdrop-filter: none !important;
    background: var(--dsw-alias-bg-layer-2, #fff) !important;
  }
  .ofm_scrim {
    -webkit-backdrop-filter: none !important;
    backdrop-filter: none !important;
  }
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] .md-table-wide {
      overflow-x: auto !important;
      -webkit-overflow-scrolling: touch;
    }
    [data-mobile-nav="frame"] .md-table-wide > table {
      width: auto !important;
      min-width: 100% !important;
    }
    [data-mobile-nav="frame"] .md-table-wide > table th {
      white-space: nowrap !important;
    }
    [data-mobile-nav="frame"] .md-table-wide > table th,
    [data-mobile-nav="frame"] .md-table-wide > table td {
      min-width: 4.5em !important;
    }
  }
  body > .ofm_toasts {
    z-index: 1310 !important;
  }
}
@media (max-width: 1023px) and (pointer: coarse) {
  [class*="GTtnTq_root"] [class*="_disclosure"][aria-expanded] {
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    width: 22px !important;
    height: 22px !important;
    font-size: 15px !important;
    line-height: 1 !important;
  }
  [class*="GTtnTq_objectTree"] details > summary::marker {
    font-size: 15px !important;
  }
  [class*="GTtnTq_modeSelector"] {
    font-size: 13px !important;
    padding: 4px 6px !important;
    min-height: 32px !important;
  }
  [class*="GTtnTq_table"] {
    border-collapse: separate !important;
    border-spacing: 0 !important;
  }
  [class*="GTtnTq_table"] [class*="GTtnTq_sticky"] {
    will-change: transform !important;
  }
}
`;
};
__modules["styles/misc.css.js"] = function (require, module, exports) {
"use strict";
// misc — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: each section (composer / tablet / desktop) carries its own
// media query.
Object.defineProperty(exports, "__esModule", { value: true });
exports.MISC_CSS = void 0;
exports.MISC_CSS = `@media (max-width: 1023px) and (pointer: coarse) {
  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]) {
    gap: 8px !important;
  }
  [data-phase="hero"] [class*="_composerStack"]:not(:has([data-gitgraph-chip-anchor])) [class*="_card"]:has(textarea, [data-composer-input]) {
    padding-top: 6px !important;
  }
  [data-phase="hero"] textarea:placeholder-shown {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) > [class*="_scroll"],
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) [class*="_grow"] {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]) > [class*="_row"] {
    padding-top: 2px !important;
  }
  [data-phase="hero"] [class*="_headline"] {
    line-height: 1.15 !important;
    margin-bottom: 0 !important;
  }
  [data-phase="hero"] [class*="_stack"] {
    gap: 0 !important;
  }
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] {
    order: -1 !important;
  }
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] [data-gitgraph-chip] {
    touch-action: manipulation !important;
    min-height: 34px !important;
    padding: 0 12px !important;
    font-size: 13px !important;
  }
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] [data-gitgraph-chip]:active {
    transform: scale(.96) !important;
    transition: transform .12s !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule {
    gap: 1px 3px !important;
    padding: 3px 4px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    scrollbar-width: none !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule::-webkit-scrollbar {
    display: none !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-capsule-word {
    padding: 2px 4px !important;
    column-gap: 3px !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-seg {
    padding: 2px 4px !important;
    gap: 3px !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-split .dsws-split-part {
    padding: 2px 4px !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-timebtn {
    padding: 2px 4px !important;
  }
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-skillbtn,
  [data-slot="conversation.input.dock"] .dsws-capsule .dsws-fold-toggle {
    padding: 1px 2px !important;
  }
  html[data-mobile-nav-ios] [data-question-key] [class*="Mbwy4a_fieldInput"] {
    font-size: 16px !important;
  }
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-search-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-jump-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-page-input"] {
    font-size: 16px !important;
  }
  html[data-mobile-nav-ios] textarea,
  html[data-mobile-nav-ios] [contenteditable]:not([contenteditable="false"]),
  html[data-mobile-nav-ios] [data-input-mirror],
  html[data-mobile-nav-ios] [data-input-backdrop],
  html[data-mobile-nav-ios] input:not([type="button"]):not([type="checkbox"]):not([type="color"]):not([type="file"]):not([type="hidden"]):not([type="image"]):not([type="radio"]):not([type="range"]):not([type="reset"]):not([type="submit"]) {
    font-size: 16px !important;
  }
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] [role="tree"] {
    content-visibility: auto;
    contain-intrinsic-size: auto 600px;
  }
  [data-mobile-nav="frame"] > [class*="_sidebarCol"],
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]),
  [data-mobile-nav="frame"] [data-composer-card],
  [data-mobile-nav="stats"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="hit"] {
    -webkit-user-select: none !important;
    user-select: none !important;
  }
  [data-mobile-nav="frame"] [data-composer-input],
  [data-mobile-nav="frame"] [data-composer-card] textarea,
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] [role="dialog"],
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] input,
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] textarea,
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) input {
    -webkit-user-select: text !important;
    user-select: text !important;
  }
}
@media (min-width: 768px) and (max-width: 1023px) and (pointer: coarse) {
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])),
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    left: 0 !important;
    right: 0 !important;
    margin-left: auto !important;
    margin-right: auto !important;
    width: min(calc(100vw - 32px), 720px) !important;
    max-width: min(calc(100vw - 32px), 720px) !important;
  }
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    left: 0 !important;
    right: 0 !important;
    width: min(calc(100vw - 32px), 720px) !important;
    margin-left: auto !important;
    margin-right: auto !important;
  }
  [aria-modal="true"] [class*="_section"] {
    width: 100% !important;
    max-width: none !important;
  }
}
@media (min-width: 1024px), (pointer: fine), (pointer: none) {
  [data-mobile-nav="toggle"],
  [data-mobile-nav="files"],
  [data-mobile-nav="file-upload"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="hit"],
  [data-mobile-nav="session-log"],
  [data-mobile-nav="session-log-wrap"],
  [data-mobile-nav="quick-settings"],
  [data-mobile-nav="quick-settings-wrap"],
  [data-mobile-nav="preview-full-toggle"],
  [data-mobile-nav="question-expand"] {
    display: none !important;
  }
}
@media (pointer: fine), (pointer: none) {
  [data-mobile-nav="session-delete"],
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="delete-dialog"] {
    display: none !important;
  }
}
@media (min-width: 1024px) and (pointer: coarse) {
  [class*="sessionRow"] [class*="_rowActions"] {
    display: inline-flex !important;
  }
  [class*="sessionRow"] {
    min-height: 44px !important;
  }
  [class*="sessionRow"] [class*="_rowActions"] {
    gap: 4px !important;
  }
  [class*="sessionRow"] [class*="_rowActions"] button,
  [class*="sessionRow"] [class*="_rowActions"] [role="button"] {
    width: 40px !important;
    height: 40px !important;
    min-width: 40px !important;
    min-height: 40px !important;
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    padding: 0 !important;
    border-radius: 10px;
  }
  [class*="sessionRow"] [class*="_rowActions"] :is([aria-label="归档会话"], [aria-label="取消归档"], [aria-label="置顶会话"], [aria-label="取消置顶"], [aria-label="Archive session"], [aria-label="Unarchive session"], [aria-label="Pin session"], [aria-label="Unpin session"]) {
    display: none !important;
  }
  header [class*="viewsMore"],
  header [class*="viewsMoreAnchor"] {
    display: none !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) {
    position: absolute !important;
    left: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px) !important;
    max-width: calc(100vw - 16px) !important;
    height: auto;
    max-height: calc(100dvh - 24px - env(safe-area-inset-top, 0px));
    flex-direction: column !important;
    border-radius: 14px !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child {
    flex: 1 1 auto;
    min-height: 0;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child {
    width: 100%;
    flex-direction: row !important;
    align-items: center;
    gap: 6px;
    padding: 10px 12px 8px;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child > :first-child {
    display: none !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"] {
    flex: 1 1 auto;
    min-width: 0;
    flex-direction: row !important;
    flex-wrap: wrap !important;
    overflow: visible !important;
    gap: 6px;
    margin-right: 42px;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navCell"] {
    flex: 0 0 auto !important;
    white-space: nowrap !important;
    padding: 6px 8px !important;
    gap: 6px !important;
    font-size: 13px !important;
    justify-content: flex-start !important;
  }
  [class*="titleCluster"] [class*="headerActions"] {
    flex: 1 1 auto !important;
  }
  [class*="titleCluster"] [class*="headerActions"] [class*="SVAs4q_label"] {
    order: -1 !important;
  }
  [class*="titleCluster"] [class*="headerActions"] [class*="VoX2oq_root"] {
    margin-left: auto !important;
  }
  [class*="titleCluster"] [class*="headerActions"] > div > :last-child {
    margin-right: auto !important;
  }
  [data-mobile-nav="quick-settings-wrap"],
  [data-mobile-nav="quick-settings"] {
    display: inline-flex !important;
  }
  [data-composer-card] [data-mobile-nav="file-upload"] {
    flex: 0 0 auto !important;
    position: relative !important;
    width: 28px !important;
    min-width: 28px !important;
    height: 28px !important;
    padding: 0 !important;
    display: inline-grid !important;
    place-items: center;
    border: 0 !important;
    border-radius: 999px;
    background: transparent;
    color: inherit;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:active {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
  [data-composer-card] [data-mobile-nav="file-upload"]::after {
    content: '';
    position: absolute;
    inset: -7px;
    border-radius: 999px;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:disabled {
    opacity: 0.38;
    cursor: default;
  }
  [data-composer-card]:not(:has(input[type=file])) [data-mobile-nav="file-upload"] {
    display: none !important;
  }
}
@media (max-width: 1023px) and (pointer: coarse) {
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed])) [class*="moreMenu"] {
    left: 8px !important;
    right: auto !important;
    max-width: calc(min(88vw, 280px) - 16px) !important;
  }
}
[data-mobile-nav="session-log-menu"] {
  position: fixed;
  z-index: 1400;
  box-sizing: border-box;
  min-width: 200px;
  max-width: calc(100vw - 24px);
  padding: 4px;
  border: 0;
  border-radius: var(--dsw-radius-lg, 12px);
  background: var(--dsw-menu-surface-fill, var(--dsw-alias-bg-elevated, #fff));
  backdrop-filter: var(--dsw-menu-backdrop-filter, blur(20px));
  --dsw-elevation-stroke-color: var(--dsw-alias-border-l1);
  box-shadow: var(--dsw-elevation-prominent, 0 3px 8px rgba(0, 0, 0, .04), 0 0 20px rgba(0, 0, 0, .05));
}
[data-mobile-nav="session-log-menu"] [role="menuitem"] {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 34px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--dsw-radius-md, 8px);
  background: transparent;
  color: var(--dsw-alias-label-primary, #141414);
  font: inherit;
  font-size: 13px;
  line-height: 20px;
  text-align: left;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="session-log-menu"] [role="menuitem"] svg {
  flex: none;
  color: var(--dsw-alias-label-secondary, inherit);
}
[data-mobile-nav="session-log-menu"] [role="menuitem"]:hover:not(:disabled),
[data-mobile-nav="session-log-menu"] [role="menuitem"]:active:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="session-log-menu"] [role="menuitem"]:disabled {
  opacity: .45;
}
`;
};
__modules["styles/index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MOBILE_CSS = void 0;
const base_css_ts_1 = require("./styles/base.css.js");
const layout_css_ts_1 = require("./styles/layout.css.js");
const compat_css_ts_1 = require("./styles/compat.css.js");
const misc_css_ts_1 = require("./styles/misc.css.js");
/**
 * All mobile styles, concatenated in the exact order of the original
 * single-file stylesheet (base → layout → compat → misc, where misc keeps
 * composer → tablet → desktop). Injected as ONE <style data-plugin> tag —
 * do not reorder.
 */
exports.MOBILE_CSS = [base_css_ts_1.BASE_CSS, layout_css_ts_1.LAYOUT_CSS, compat_css_ts_1.COMPAT_CSS, misc_css_ts_1.MISC_CSS].join('\n');
};
__modules["platform/breakpoints.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TABLET_TOUCH_QUERY = exports.TOUCH_QUERY = exports.DESKTOP_QUERY = exports.MOBILE_QUERY = void 0;
exports.installMobileEffect = installMobileEffect;
/** Same width bound as the shell's SIDEBAR_AUTO_COLLAPSE (viewport < 1024),
 *  ANDed with a touch-primary pointer guard. Width alone cannot tell a phone
 *  from a desktop window: split views and OS display scaling push a PC's CSS
 *  viewport below 1024px too, and the whole mobile shell (drawer, header
 *  Files button, gestures) would mount there. (pointer: coarse) keeps the
 *  adaptation on touch-primary devices — phones, tablets, DSHA — while any
 *  mouse-driven window stays desktop at every width. Headless probes have no
 *  pointer at all: arm the mobile branch with Emulation.setTouchEmulation-
 *  Enabled before asserting mobile UI. */
exports.MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)';
/** Informational wide-bound for the debug badge. The authoritative desktop
 *  guard is the CSS hide block in misc.css.ts — the exact complement of
 *  MOBILE_QUERY — because slot-rendered controls exist at every width. */
exports.DESKTOP_QUERY = '(min-width: 1024px)';
/** Pointer-only guard for the ONE feature that has no desktop equivalent:
 *  the session-delete menu injection. Armed on touch-primary devices at
 *  EVERY width — a large tablet in landscape (e.g. 1238px) keeps the desktop
 *  layout but still gets the 「删除会话」 item. Mouse-driven or pointer-less
 *  windows never arm it, at any width. */
exports.TOUCH_QUERY = '(pointer: coarse)';
/** 宽屏触屏（平板 ≥1024 + 粗指针）：宿主原生布局照旧，本插件只补「触屏缺 hover /
 *  触屏热区」这一类补丁（2026-10-29 owner 定的方向：平板走原生，不做手机壳）。
 *  与 MOBILE_QUERY 互补但不重叠；桌面（pointer: fine）任何宽度都不命中。 */
exports.TABLET_TOUCH_QUERY = '(min-width: 1024px) and (pointer: coarse)';
/**
 * Re-arm a mobile-only DOM effect on every query change. Replaces the
 * repeated matchMedia + change-listener scaffold so all breakpoint strings
 * live in one place. `query` defaults to MOBILE_QUERY; effects that arm on a
 * different condition (e.g. TOUCH_QUERY) pass their own string instead of
 * building a private matchMedia scaffold.
 */
function installMobileEffect(ctx, label, install, query = exports.MOBILE_QUERY) {
    ctx.effect(() => {
        const narrow = window.matchMedia(query);
        let cleanup;
        const arm = () => {
            cleanup?.();
            cleanup = narrow.matches ? install(narrow) : undefined;
        };
        arm();
        narrow.addEventListener('change', arm);
        return () => {
            narrow.removeEventListener('change', arm);
            cleanup?.();
        };
    }, label);
}
};
__modules["platform/device.js"] = function (require, module, exports) {
"use strict";
// Platform layer: engine/device probes. Moved out of effects/phone-chrome.ts
// (architecture P1) so the two composer keyboard guards depend on the platform
// layer instead of on another effect.
Object.defineProperty(exports, "__esModule", { value: true });
exports.detectIosWebKit = detectIosWebKit;
/**
 * Whether the page runs on iOS / iPadOS WebKit, where focusing a text field
 * whose computed font-size is below 16px zooms the whole visual viewport
 * (#45). Every other engine ignores field font-size, so the 16px floor in
 * misc.css.ts is gated on this marker instead of applying to every phone —
 * Android would only get bigger search boxes for no benefit.
 *
 * Pure and injectable so the decision table is unit-testable:
 * - The feature probe is the reliable signal: `font: -apple-system-body` is
 *   Safari-only and `-webkit-touch-callout` is an iOS property, so the pair
 *   is true on iOS WebKit (including Chrome / Edge / Opera on iOS, which are
 *   WebKit and zoom identically) and false on Chromium (measured) and on
 *   macOS Safari.
 * - The UA fallback covers engines whose CSS.supports is missing or which
 *   parse the probe differently: iPhone / iPad / iPod UAs, plus iPadOS 13+
 *   which reports a Macintosh UA and is told apart by its touch points.
 */
function detectIosWebKit(nav, supports) {
    if (supports !== null) {
        try {
            if (supports('(font: -apple-system-body) and (-webkit-touch-callout: none)'))
                return true;
        }
        catch {
            // A UA that rejects the condition string falls through to the UA test.
        }
    }
    const ua = nav.userAgent;
    if (/iP(hone|ad|od)/.test(ua))
        return true;
    return /Macintosh/.test(ua) && nav.maxTouchPoints > 1;
}
};
__modules["effects/header-metrics.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createHeaderMetricsTask = createHeaderMetricsTask;
// Reconciler task: phone conversation header height → CSS variable.
//
// 2026-10-12 P1 拆分第二片：由 effects/phone-chrome.ts 逐字搬出（纯搬运，不改行为）。
// 拆出的形状沿用本仓既有的 task 工厂模式（preview-fullscreen / drawer-layer /
// panel-exit 同型）：模块导出 create<X>Task()，组装处只 addReconcilerTask(工厂())。
const frame_ts_1 = require("./platform/frame.js");
/**
 * Height of the phone conversation header, published as a CSS variable so the
 * scroll body can extend up under it (layout.css.ts "selection-drag autoscroll
 * ramp": the distance from the list's top edge decides how fast Blink
 * autoscrolls a selection drag). Measured from the frame's top to the header's
 * bottom - exactly how far the scrollport's box has to grow upward - which also
 * stays correct when the header's own top is pushed down by safe-area padding.
 */
const HEADER_HEIGHT_VAR = '--mobile-nav-header-h';
const HEADER_SELECTOR = '[data-mobile-nav="frame"] [data-phase] header';
let observedHeader = null;
let headerObserver = null;
/** 写回 <html>：比对后才写 —— 变量写回本身就是一次 attribute mutation，
 *  无条件写会一直把 reconciler 弄脏（幂等在这里是必须的，不是优化）。 */
function publish(next) {
    if (typeof document === 'undefined')
        return;
    const root = document.documentElement;
    if (root.style.getPropertyValue(HEADER_HEIGHT_VAR) !== next) {
        root.style.setProperty(HEADER_HEIGHT_VAR, next);
    }
}
/** 量一次 H = frame 顶边 → header 底边。刻意读两个 rect，而不是用 ResizeObserver
 *  回调里的 entry.contentRect：contentRect 只描述 header 自己的盒子，丢掉了
 *  frame.top 这一段，而 safe-area 把顶栏整体下推时 H 必须跟着长（见文件头）。 */
function measureHeaderHeight() {
    const frame = (0, frame_ts_1.getFrame)();
    const header = observedHeader;
    if (header === null || frame === null || !(header instanceof HTMLElement))
        return '0px';
    return `${Math.round(Math.max(0, header.getBoundingClientRect().bottom - frame.getBoundingClientRect().top))}px`;
}
/** **几何路径**：只量、只发布，不读 `offsetHeight`。
 *
 *  由 ResizeObserver 回调驱动 —— 顶栏尺寸变了就是它在喊，不必再问一次「你的
 *  offsetHeight 是 0 吗」：不可见/已卸载的顶栏 rect 全零，`Math.max(0, …)` 已经把它
 *  夹到 0px，与旧版那道 offsetHeight 门控同一结果。 */
function publishGeometry() {
    publish(measureHeaderHeight());
}
/** **结构路径**：顶栏节点刚被换过（React 换子树 / 首次挂载）才走这里，除量/发布外还
 *  要挡住「换上一个没有盒子的顶栏」（display:none / 尚未布局）→ 回落 0px。 */
function publishStructure() {
    const header = observedHeader;
    publish(header instanceof HTMLElement && header.offsetHeight === 0 ? '0px' : measureHeaderHeight());
}
/** Reattach the ResizeObserver when React swaps the header element, then
 *  publish. Cheap while nothing changes: one querySelector + an identity
 *  compare, and **no layout work at all** until the header is actually replaced.
 *
 *  2026-10-13：这条「没换就不做 layout 工作」以前只是注释里的承诺 —— ensure 每趟都
 *  调一遍 publishHeaderHeight()，读 offsetHeight + 两次 rect。任务是 scopes:['*']，
 *  于是流式期间每个 mutation 批次都白读一次布局。现在按信号分流：本函数只在**结构**
 *  变化时做 layout 工作并发布；几何变化由 ResizeObserver 的几何路径负责。
 *  scopes 仍须 ['*']：顶栏节点可以在任意一次 commit 里被换掉，没有更窄的脏键能覆盖它。 */
function syncHeaderHeight() {
    if (typeof document === 'undefined')
        return;
    const header = document.querySelector(HEADER_SELECTOR);
    if (header === observedHeader) {
        // 结构没变 ⇒ 不做 layout 工作。宿主没有 ResizeObserver 时几何路径永远不会被驱动，
        // 只有这一种情况才退化为每趟量一次几何（拿不到 RO 的宿主也得能更新）。
        if (header !== null && typeof ResizeObserver === 'undefined')
            publishGeometry();
        return;
    }
    headerObserver?.disconnect();
    observedHeader = header;
    if (header !== null && typeof ResizeObserver !== 'undefined') {
        headerObserver = headerObserver ?? new ResizeObserver(() => { publishGeometry(); });
        headerObserver.observe(header);
    }
    publishStructure();
}
/** Reconciler task face for the header-height variable (see the doc comment above).
 *  与拆分前同形：name/scopes/ensure/dispose 四项一字未改，只是从调用处搬进工厂。
 *  ensure 走**结构路径**（顶栏没换就不做 layout 工作），几何路径由 ResizeObserver 驱动。 */
function createHeaderMetricsTask() {
    return {
        name: 'header-metrics',
        scopes: ['*'],
        ensure: () => {
            syncHeaderHeight();
        },
        dispose: () => {
            headerObserver?.disconnect();
            headerObserver = null;
            observedHeader = null;
            if (typeof document !== 'undefined') {
                document.documentElement.style.removeProperty(HEADER_HEIGHT_VAR);
            }
        },
    };
}
};
__modules["core/reconciler-core.js"] = function (require, module, exports) {
"use strict";
// reconciler-core.ts — DOM-free reconciler engine shared by every mobile DOM
// reconciler task. Deliberately has ZERO import statements:
//  - the custom client bundler cannot resolve `../` requires from
//    src/client/effects, and a file without imports has nothing to resolve;
//  - node:test imports it directly (Node's native type stripping) without a
//    DOM or DSH runtime, so registration / dirty routing / coalescing /
//    error-isolation can be covered by plain unit tests.
//
// The browser half (reconciler-adapter.ts) is a thin adapter: it owns the
// MutationObserver and requestAnimationFrame scheduler, feeds mutation keys
// into note(), and delegates task lifecycle to register() / activate() /
// deactivate(). The dirty-key vocabulary both halves must agree on lives just
// below: the core never interprets a key, it only publishes the spellings so
// the adapter, every task's scopes and the unit tests cannot drift apart.
Object.defineProperty(exports, "__esModule", { value: true });
exports.TEXT_MUTATION_SCOPE = exports.TREE_MUTATION_SCOPE = void 0;
exports.mutationDirtyKey = mutationDirtyKey;
exports.createReconcilerCore = createReconcilerCore;
/**
 * Tree-change key: the sentinel for a childList record (a node was added,
 * removed or moved anywhere in the observed tree). Coarse on purpose -- one
 * observer watches the whole tree, so any structural change can invalidate a
 * task's cached node, and the core cannot know which subtree a task cares
 * about.
 */
exports.TREE_MUTATION_SCOPE = '*';
/**
 * Text-change key: one characterData record (a text node's value was rewritten
 * in place, no node added or removed). Kept separate from the tree sentinel so
 * a task that only measures structure is not re-run for every streamed token,
 * while a task that reads text declares this key and stays woken. A text
 * reader MUST declare it next to TREE_MUTATION_SCOPE: React rebuilds a node
 * for some updates and rewrites the text in place for others, so both record
 * shapes reach a live readout.
 */
exports.TEXT_MUTATION_SCOPE = 'characterData';
/**
 * Map one observer record onto the dirty key it dirties: attribute records
 * route by attribute name, text records to TEXT_MUTATION_SCOPE, tree records
 * to TREE_MUTATION_SCOPE. Fail-open on the two shapes it cannot name -- an
 * attribute record without a name, and any record type this mapping does not
 * know, both widen to the tree sentinel, so a mapping gap can only cost extra
 * wakeups, never a missed one.
 */
function mutationDirtyKey(record) {
    if (record.type === 'attributes') {
        return record.attributeName === null ? exports.TREE_MUTATION_SCOPE : record.attributeName;
    }
    return record.type === 'characterData' ? exports.TEXT_MUTATION_SCOPE : exports.TREE_MUTATION_SCOPE;
}
function createReconcilerCore(options) {
    const onError = options.onError ??
        ((taskName, error, phase) => {
            console.error(`[dsh-web-mobile] reconciler task ${taskName}${phase === 'dispose' ? ' dispose' : ''} failed`, error);
        });
    const registered = new Set();
    let active = null;
    let dirty = new Set();
    let forceAll = false;
    let pending = null;
    const runEnsure = (task) => {
        try {
            task.ensure();
        }
        catch (error) {
            onError(task.name, error, 'ensure');
        }
    };
    const runDispose = (task) => {
        try {
            task.dispose();
        }
        catch (error) {
            onError(task.name, error, 'dispose');
        }
    };
    const flush = () => {
        if (pending !== null) {
            pending();
            pending = null;
        }
        if (active === null) {
            dirty.clear();
            forceAll = false;
            return;
        }
        // Snapshot and clear BEFORE running tasks: a task that calls note() from
        // inside ensure() must leave its key dirty for the next pass. Clearing at
        // the end swallowed exactly those keys (issue #86).
        const keys = dirty;
        const all = forceAll;
        dirty = new Set();
        forceAll = false;
        if (all) {
            for (const task of active)
                runEnsure(task);
        }
        else if (keys.size > 0) {
            for (const task of active) {
                const scopes = task.scopes;
                if (scopes === undefined || scopes.some((key) => keys.has(key)))
                    runEnsure(task);
            }
        }
    };
    const schedule = () => {
        if (pending !== null)
            return;
        pending = options.requestFrame(() => {
            pending = null;
            flush();
        });
    };
    const register = (task) => {
        registered.add(task);
        if (active !== null) {
            active.add(task);
            runEnsure(task);
        }
        return () => {
            registered.delete(task);
            // Ownership check: the disposer is idempotent and only disposes a task
            // that is actually active. Without it, calling one disposer twice ran
            // dispose() twice (and a stale disposer could dispose a task this call
            // never registered — issue #86).
            if (active !== null && active.delete(task))
                runDispose(task);
        };
    };
    const activate = () => {
        if (active !== null)
            return;
        active = new Set(registered);
        forceAll = true;
        flush();
    };
    const deactivate = () => {
        if (pending !== null) {
            pending();
            pending = null;
        }
        dirty.clear();
        forceAll = false;
        if (active !== null) {
            const snapshot = active;
            active = null;
            for (const task of snapshot)
                runDispose(task);
        }
    };
    return {
        get size() {
            return registered.size;
        },
        register,
        activate,
        deactivate,
        note: (keys) => {
            for (const key of keys)
                dirty.add(key);
            schedule();
        },
        flush,
    };
}
};
__modules["i18n/locales.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.en = exports.zh = exports.NS = void 0;
/**
 * Namespace id for the drawer-control dictionaries.
 *
 * Package-prefixed on purpose: the host's `locale.register` THROWS on a
 * duplicate namespace (no merge, no override), and that throw fails the whole
 * plugin fiber — a dead web boot on narrow screens. Downstream copies of this
 * plugin keep our namespace, so a bare, generic name is a loaded gun:
 * dsh-pocket embeds a copy of the mobile adaptation and still registers
 * `mobileNav` (issue #165).
 */
exports.NS = 'dshWebMobileNav';
/** Simplified Chinese dictionary (the key-set source of truth). */
exports.zh = {
    'open': '打开目录',
    'back': '返回',
    'backToConversation': '返回会话',
    'sessionLog': '导出会话日志',
    'downloadMenu': '下载',
    'downloads': '下载记录',
    'quickSettings': '设置',
    'files': '文件浏览',
    'fileUpload': '添加文件',
    'fileUploadImage': '上传图片',
    'fileUploadAttachment': '上传附件',
    'previewFullscreen': '全屏预览',
    'previewExitFullscreen': '退出全屏',
    'questionExpand': '展开回答',
    'questionCollapse': '收起回答',
    'deleteSession': '删除会话',
    'deleteConfirmTitle': '删除会话？',
    'deleteConfirmDesc': '将删除「{title}」的完整会话记录，此操作不可恢复。',
    'deleteConfirmYes': '删除',
    'deleteConfirmNo': '取消',
    'deletePending': '正在删除…',
    'deleteErrorBusy': '该会话正在运行且无法停止，请稍后重试。',
    'deleteErrorNotFound': '会话不存在或已被删除。',
    'deleteErrorResolve': '无法确定要删除的会话，请重试。',
    'deleteErrorGeneric': '删除失败：{message}',
};
/** English dictionary, key-identical to the Chinese source of truth. */
exports.en = {
    'open': 'Open directory',
    'back': 'Back',
    'backToConversation': 'Back to conversation',
    'sessionLog': 'Session log',
    'downloadMenu': 'Downloads',
    'downloads': 'Downloads history',
    'quickSettings': 'Settings',
    'files': 'Files',
    'fileUpload': 'Add files',
    'fileUploadImage': 'Upload image',
    'fileUploadAttachment': 'Upload attachment',
    'previewFullscreen': 'Fullscreen preview',
    'previewExitFullscreen': 'Exit fullscreen',
    'questionExpand': 'Expand answer',
    'questionCollapse': 'Collapse answer',
    'deleteSession': 'Delete session',
    'deleteConfirmTitle': 'Delete session?',
    'deleteConfirmDesc': 'The complete log of “{title}” will be permanently removed. This cannot be undone.',
    'deleteConfirmYes': 'Delete',
    'deleteConfirmNo': 'Cancel',
    'deletePending': 'Deleting…',
    'deleteErrorBusy': 'This session is running and could not be stopped. Try again later.',
    'deleteErrorNotFound': 'The session does not exist or was already deleted.',
    'deleteErrorResolve': 'Could not identify the session to delete. Please try again.',
    'deleteErrorGeneric': 'Delete failed: {message}',
};
};
__modules["effects/aionui-compat.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installAionuiCompat = installAionuiCompat;
exports.createPreviewCloseTask = createPreviewCloseTask;
exports.createSheetRiseTask = createSheetRiseTask;
const frame_ts_1 = require("./platform/frame.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/** dsh-web-ui 兼容：explorer / preview 列的显隐标记与升起动画（同域同机制，合并一处）。 */
function installAionuiCompat(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: aionui explorer close marker', () => {
        const onChevronClick = (event) => {
            const target = event.target;
            if (target === null || !target.closest('.aionui-collapse-chevron'))
                return;
            (0, frame_ts_1.getFrame)()?.removeAttribute('data-aionui-explorer-open');
        };
        document.addEventListener('click', onChevronClick, true);
        return () => document.removeEventListener('click', onChevronClick, true);
    });
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: preview sheet open marker', () => {
        const closePreview = () => {
            (0, frame_ts_1.getFrame)()?.removeAttribute('data-aionui-preview-open');
            (0, frame_ts_1.getFrame)()?.removeAttribute('data-mobile-preview-full');
        };
        // Temporarily spoof platform/userAgent/appVersion to Win32 desktop to
        // bypass the suite's Android check. The spoof is global, so it must be
        // restore-safe: one in-flight timer, re-entrancy guarded, and always
        // restored on effect disposal (narrow→wide / plugin reload).
        const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
        const DESKTOP_APPVERSION = '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
        let restoreTimer = null;
        let spoofed = false;
        // Restore what was actually there: an own descriptor if the page had one,
        // otherwise REMOVE the own property we added so the prototype's getter shows
        // through again. Writing the old VALUE back as an own property leaves a
        // permanent own-property shadow behind (issue #86), which changes
        // `Object.keys(navigator)` / descriptor probes for the rest of the session.
        const NAV_KEYS = ['platform', 'userAgent', 'appVersion'];
        const savedNavigator = new Map();
        const restoreNavigator = () => {
            if (restoreTimer !== null) {
                window.clearTimeout(restoreTimer);
                restoreTimer = null;
            }
            if (!spoofed)
                return;
            spoofed = false;
            for (const key of NAV_KEYS) {
                const descriptor = savedNavigator.get(key) ?? null;
                if (descriptor === null)
                    delete navigator[key];
                else
                    Object.defineProperty(navigator, key, descriptor);
            }
            savedNavigator.clear();
        };
        const spoofDesktop = () => {
            if (!spoofed) {
                for (const key of NAV_KEYS) {
                    savedNavigator.set(key, Object.getOwnPropertyDescriptor(navigator, key) ?? null);
                }
                Object.defineProperty(navigator, 'platform', { value: 'Win32', configurable: true });
                Object.defineProperty(navigator, 'userAgent', { value: DESKTOP_UA, configurable: true });
                Object.defineProperty(navigator, 'appVersion', { value: DESKTOP_APPVERSION, configurable: true });
                spoofed = true;
            }
            if (restoreTimer !== null)
                window.clearTimeout(restoreTimer);
            restoreTimer = window.setTimeout(restoreNavigator, 1000);
        };
        const onTap = (event) => {
            const target = event.target;
            if (target === null)
                return;
            const row = target.closest('[data-aionui-explorer-col] [class*="_treeRow"]');
            if (row === null)
                return;
            if (row.querySelector('[class*="_treeArrow"]:not([class*="_treeArrowEmpty"])') !== null)
                return;
            spoofDesktop();
            (0, frame_ts_1.getFrame)()?.setAttribute('data-aionui-preview-open', '');
        };
        const onCollapse = (event) => {
            const target = event.target;
            if (target === null)
                return;
            if (target.closest('[data-aionui-preview-col] [class*="_panelCollapse"]') !== null) {
                closePreview();
            }
        };
        document.addEventListener('click', onTap, true);
        document.addEventListener('click', onCollapse, true);
        return () => {
            restoreNavigator();
            document.removeEventListener('click', onTap, true);
            document.removeEventListener('click', onCollapse, true);
        };
    });
}
function createPreviewCloseTask() {
    return {
        name: 'preview-close-sync',
        // Only acts when the suite hides the col via inline style. Deliberately
        // NOT scoped to data-aionui-preview-open: our own open marker is set
        // before the suite necessarily flips its inline visibility, so waking on
        // that marker would read the still-hidden style as a "suite close" and
        // immediately undo the file-row tap.
        scopes: ['style'],
        ensure: () => {
            const pv = document.querySelector('[data-aionui-preview-col]');
            if (pv === null)
                return;
            if (pv.style.visibility === 'hidden') {
                (0, frame_ts_1.getFrame)()?.removeAttribute('data-aionui-preview-open');
                (0, frame_ts_1.getFrame)()?.removeAttribute('data-mobile-preview-full');
            }
        },
        dispose: () => { },
    };
}
function createSheetRiseTask() {
    const cols = ['[data-aionui-explorer-col]', '[data-aionui-preview-col]'];
    const seen = new Map();
    // 每列**我们自己**启动的那一条 Animation。只记录自有的：宿主自己创建的动画
    // （第三方包/React 组件的 WAAPI）从不进这张表，因此也**永远不会被我们 cancel**。
    const own = new Map();
    // reduce 必须在**播放那一刻**读：用户中途改系统开关也要生效（CSS 的 animation:none
    // 管不到 WAAPI 造的动画，只能自己判）。
    const reduceMotion = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const cancelOwn = (sel) => {
        const rec = own.get(sel);
        if (rec === undefined)
            return;
        own.delete(sel);
        // cancel() 会让 finished 以一个 AbortError 拒绝，那条 promise 已经在 play 里吞掉。
        try {
            rec.anim.cancel();
        }
        catch { /* 已自然结束或已取消 */ }
    };
    /** 释放失效记录：元素已脱离文档，或该槽位已经换成别的节点。 */
    const sweep = () => {
        for (const sel of cols) {
            const rec = own.get(sel);
            if (rec === undefined)
                continue;
            if (!rec.el.isConnected) {
                cancelOwn(sel);
                continue;
            }
            const current = document.querySelector(sel);
            if (current !== null && current !== rec.el)
                cancelOwn(sel);
        }
    };
    const play = (sel, el) => {
        // 同列重开：先取消上一条自有动画，避免两条 fill:backwards 叠在一起互相抢手态。
        cancelOwn(sel);
        if (reduceMotion())
            return;
        const anim = el.animate([
            { opacity: 0, transform: 'translateY(28px)' },
            { opacity: 1, transform: 'none' },
        ], { duration: 280, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'backwards' });
        own.set(sel, { el, anim });
        // 自然结束后把记录摘掉（避免表无限增长）；cancel 造成的拒绝在此静默。
        anim.finished.then(() => {
            if (own.get(sel)?.anim === anim)
                own.delete(sel);
        }).catch(() => { });
    };
    return {
        name: 'sheet-rise-replay',
        // 含树变更键 `*`：父容器被整体从祖父节点摘掉时，childList 的 target 是**祖父**，
        // 局部观察器收不到回调；改为复用 reconciler 的共享全树观察（frame-marker /
        // header-metrics / stats-line 已用同一通道）。代价由下面的便宜路径压住：没有自有动画、
        // 也没有目标列时，只做 2 次 querySelector 就返回，**不**读样式、**不**播放、**不**写 DOM。
        scopes: [
            '*',
            'style',
            'class',
            'data-aionui-explorer-open',
            'data-aionui-preview-open',
            'data-mobile-preview-full',
        ],
        ensure: () => {
            const els = cols.map((sel) => document.querySelector(sel));
            // 便宜路径：手上没有自有动画、两个槽位也都不在 ⇒ 立刻返回。
            if (own.size === 0 && els[0] === null && els[1] === null)
                return;
            sweep();
            for (let i = 0; i < cols.length; i++) {
                const sel = cols[i];
                const el = els[i];
                // 槽位缺席：释放该列的所有权记录（旧实现直接 continue，记录会一直挂着）。
                // 刻意**不**动 seen ——「摘掉再挂回来是否重播」沿用既有语义，不做行为变更。
                if (el === null) {
                    cancelOwn(sel);
                    continue;
                }
                const rec = own.get(sel);
                if (rec !== undefined && rec.el !== el)
                    cancelOwn(sel);
                const visible = getComputedStyle(el).visibility === 'visible';
                const prev = seen.get(sel) ?? false;
                if (visible && !prev)
                    play(sel, el);
                seen.set(sel, visible);
            }
        },
        dispose: () => {
            // 停用/卸载：取消自己启动的动画（fill:backwards 的起手态否则会留在元素上）。
            for (const sel of cols)
                cancelOwn(sel);
            own.clear();
            seen.clear();
        },
    };
}
};
__modules["platform/composer-anchor.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.carriesComposer = carriesComposer;
exports.isRingCandidate = isRingCandidate;
exports.locateRing = locateRing;
/**
 * Which element inside the composer is the "ring" readout, and which one merely
 * LOOKS like it.
 *
 * The ring is found by walking up from the stats line and taking the first
 * sibling whose text carries a percentage. That shape also matches things that
 * must never be touched:
 *
 *   2026-10-31, iPhone CriOS 156, host 0.2.1 — the composer card's own text
 *   carried a percentage, so the walk picked the *card* as the ring. The plugin
 *   marked it `data-mobile-nav="stats-ring"` and the fold wrote `left/top` on it,
 *   which threw the whole composer to x=299,y=30: input squeezed to 22px, seat
 *   collapsed to 34px, buttons parked off the right edge, frozen until a refresh.
 *   Captured live: `<div class="…_card" data-composer-card="true"
 *   data-mobile-nav="stats-ring" style="left: 299px; top: 30px;">`.
 *
 * A ring is a small readout, so anything that is or contains the composer — the
 * card, the editing surface, or any control — is disqualified by construction
 * rather than by a size threshold.
 */
const COMPOSER_OR_CONTROL = '[data-composer-card], [data-composer-input], [contenteditable], input, textarea, button';
/**
 * True when `element` is, or contains, the composer or a control.
 *
 * The test doubles used by this package implement only the parts of the DOM a
 * pass touches, so both calls are probed instead of assumed.
 */
function carriesComposer(element) {
    const matcher = element.matches;
    if (typeof matcher === 'function' && matcher.call(element, COMPOSER_OR_CONTROL))
        return true;
    const query = element.querySelector;
    return typeof query === 'function' && query.call(element, COMPOSER_OR_CONTROL) !== null;
}
/**
 * True when the element actually draws a progress ring.
 *
 * The ring in the host is `ContextMeter` (`@deepseek-ai/dsh-client-ui-conversation`,
 * `skeleton/ContextMeter.tsx`): a 14x14 `<svg>` holding two `<circle>`s, the
 * second of which sets `stroke-dasharray` to the fraction used. That dash array
 * is what makes it a progress ring — it is a property of the drawing, not of the
 * wording around it, so it survives copy changes, locale changes and any node
 * that merely *mentions* a percentage (the composer card's model line does).
 *
 * Test doubles implement only the handful of DOM calls a pass touches, so a
 * missing `querySelectorAll`/`getComputedStyle` falls back to the old text test
 * — the shipped behaviour is ring-first, the fallback only keeps the stubs
 * honest.
 */
function hasProgressRing(element) {
    const query = element.querySelectorAll;
    if (typeof query !== 'function' || typeof getComputedStyle !== 'function') {
        return /\d\s*%/.test(element.textContent ?? '');
    }
    for (const circle of query.call(element, 'svg circle')) {
        const dash = getComputedStyle(circle).strokeDasharray;
        if (dash !== '' && dash !== 'none' && !/^(0px?)(\s+0px?)*$/.test(dash))
            return true;
    }
    return false;
}
/** First sibling of the stats line that is not the stats line, not composer, and draws a ring. */
function isRingCandidate(child, stats) {
    if (child.contains(stats))
        return false;
    if (carriesComposer(child))
        return false;
    return hasProgressRing(child);
}
/**
 * Locate the ring by HIERARCHY first, geometry only as a tie-break.
 *
 * The host mounts both halves in one row — `InputBar.tsx` renders
 *
 *   <div data-composer-dock>
 *     {renderSlot('conversation.composer.dock')}   ← the stats pills (ui-chat)
 *     <ContextMeter />                             ← the ring
 *   </div>
 *
 * so the ring is simply the dock's other child: same parent as the stats pills,
 * not the branch that holds them. `data-composer-dock` is the host's own stable
 * attribute (no hashed class), which is why this survives rebuilds while the old
 * "walk up until something mentions a percentage" walk did not.
 *
 * The composer stack walk stays as a fallback for hosts that render the stats row
 * outside the dock; the ring-shaped check then breaks ties.
 */
function locateRing(stats) {
    const dock = stats.closest('[data-composer-dock]');
    if (dock !== null) {
        const siblings = [...dock.children].filter((child) => !child.contains(stats) && !carriesComposer(child) && !child.hasAttribute('data-mobile-nav'));
        const ring = siblings.find(hasProgressRing) ?? (siblings.length === 1 ? siblings[0] : undefined);
        if (ring !== undefined)
            return { dock, ring };
    }
    const stack = stats.closest('[class*="_composerStack"]');
    for (let node = stats.parentElement; node !== null; node = node.parentElement) {
        const ring = [...node.children].find((child) => isRingCandidate(child, stats));
        if (ring !== undefined)
            return { dock: node, ring };
        if (node === stack)
            break;
    }
    return null;
}
};
__modules["effects/stats-line.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.pickTpsReadout = void 0;
exports.statsAnchorAlive = statsAnchorAlive;
exports.resolveTpsForKey = resolveTpsForKey;
exports.tpsSlotsSettled = tpsSlotsSettled;
exports.createStatsLineTask = createStatsLineTask;
const reconciler_core_ts_1 = require("./core/reconciler-core.js");
const composer_anchor_ts_1 = require("./platform/composer-anchor.js");
// The official conversation status row (turns / steps / LLM time / TTFT /
// cache) has a hashed class, so the stylesheet cannot target it directly.
// Mark the exact row on narrow screens by text: a [class*=_root] that
// carries the metrics text and no composer input (textarea or the
// data-composer-input Lexical node; the composer card also ends in
// _root and can mention turns in its model line). The CSS then lays the
// marked row out as ONE horizontally scrolling line with every metric
// reachable.
// Fast-path predicate: is the previously marked strip still alive in place?
// Re-verifying one anchor per flush is O(1); the full-tree hunt in mark()
// grows with the conversation and runs on every streaming token.
function statsAnchorAlive(el) {
    if (el === null || !el.isConnected)
        return false;
    if (el.closest('[data-phase]') === null)
        return false;
    // The strip must stay inside the composer stack, but it need not be a
    // DIRECT child of it: 0.1.5 nests the status row (bOPqQW_root) under the
    // composer card wrapper (uV2eYG_root), so the marked element sits one level
    // deeper than on rc.2 hosts. Ancestry is still the right test — only the
    // marker's own subtree position changed, not its container relationship.
    return el.closest('[class*="_composerStack"]') !== null;
}
/** 读数文本的形状，**只此一份**：候选按 trim 后的起点锚定（pickTpsReadout），
 *  预筛只问「栈内有没有这种形状」（无需锚定，见 resolveTpsForKey）。 */
const TPS_TEXT_AT_START = /^TPS\s+\d/;
const TPS_TEXT_ANYWHERE = /TPS\s+\d/;
const pickTpsReadout = (candidates) => {
    for (const [index, el] of candidates.entries()) {
        if (!TPS_TEXT_AT_START.test((el.textContent ?? '').trim()))
            continue;
        // The readout is a bare text node holder; a row with children is a wrapper.
        if (el.children.length > 0)
            continue;
        // No parent means the row is mid-teardown — a later candidate may be the
        // live one (the scan this replaced used to `continue` here too).
        if (el.parentElement === null)
            continue;
        return { index, mark: el.getAttribute('data-mobile-nav') !== 'stats-tps' };
    }
    return { index: -1, mark: false };
};
exports.pickTpsReadout = pickTpsReadout;
/**
 * 快路径上解析 TPS 那一段（2026-10-13，纯函数 + 注入 ⇒ 可数）。三级，能短路就短路：
 *
 *   ① 记得住的读数节点（上一趟折过的那个）仍活着 ⇒ **零查询**：直接拿它和它的实时文本。
 *      旧版每趟都在 key 比较**之前**跑 locateTps，而 locateTps 的第 134 行是
 *      `stack.querySelectorAll('div')` —— 把整个 composer 栈的 div 全materialize 出来，
 *      再对每个候选读一次 textContent（各自还要走整棵子树）。那是本效果仅存的每帧查询。
 *   ② 否则先用栈内文本做一次**零查询预筛**：文本里没有「TPS <数字>」⇒ 栈内不可能有读数
 *      （pickTpsReadout 只认这种形状、且要求节点是裸文本持有者）⇒ 判定缺席。
 *   ③ 预筛命中才做那条全栈查询（locate），把读数真正找出来。
 *
 * 返回 null = 本趟缺席（key 里写 'none'，并且「上一趟也缺席」成为一个**可跳过**的稳定态 ——
 * 旧版缺席时四个 stillFolded 恒 false，于是永不 skip、每帧白折一遍）。
 * @param remembered - 上一趟折过的读数（节点都还活着时），否则 null。
 * @param rememberedText - 那个节点的实时文本（只在 remembered 非 null 时被读）。
 * @param stackText - 栈内文本的读取器（只在需要预筛时才调用，零查询）。
 * @param locate - 唯一的查询步骤（全栈 div 查询）。
 * @returns 该用的读数，或 null 表示缺席。
 */
function resolveTpsForKey(remembered, rememberedText, stackText, locate) {
    if (remembered !== null)
        return { el: remembered.el, reserve: remembered.reserve, live: rememberedText };
    if (!TPS_TEXT_ANYWHERE.test(stackText()))
        return null;
    return locate();
}
/**
 * TPS 两个槽（读数 + 它的占位）是否已经在「本趟不需要再折」的定态（2026-10-13 抽出为纯函数，
 * 因为它正是「缺席即永不 skip」的修复点）。
 *
 * 关键：key 本趟已经**现读**了「在不在 + 读数文本」（见 resolveTpsForKey），所以
 *   · 本趟缺席时该问的是「上一趟也缺席吗」—— 是，则定态成立、这一趟可以跳；
 *   · 不能问「折过的读数节点还活着吗」：缺席时它必然是 null，旧版因此恒 false ⇒ 永不 skip ⇒
 *     每帧白折一遍（含 measure/write 那串强制同步布局）。
 * 本趟在、或上一趟在而这一趟不在（读数刚消失）都要真折，所以那两种情形仍按 liveness 判。
 * 环形那两个槽没有 presence 位（环缺席时 key 里没有它的盒子），不适用这条规则，保持原判据。
 * @param absentNow - 本趟是否缺席（key 里那一段是 'none'）。
 * @param absentBefore - 上一趟是否缺席（两个槽都是 null）。
 * @param tpsAlive - 上一趟折过的读数节点是否仍活着且带标记。
 * @param reserveAlive - 上一趟折过的占位 span 是否仍活着且带标记。
 * @returns 定态成立 ⇒ 允许这一趟跳过。
 */
function tpsSlotsSettled(absentNow, absentBefore, tpsAlive, reserveAlive) {
    return absentNow ? absentBefore : tpsAlive && reserveAlive;
}
function createStatsLineTask() {
    // React-owned nodes must never be relocated (issue #104): on unmount React
    // calls parent.removeChild(child) against the parent it rendered the node
    // into, so a node this task moved makes that throw NotFoundError and the
    // SlotErrorBoundary blanks the whole composer slot until a reload. The
    // offline-reconnect rebuild hits exactly this path. Both folded readouts
    // (context ring, TPS text) therefore STAY where React rendered them; the
    // visible slot is held by a plugin-owned placeholder element React does not
    // track, and the host node is absolutely positioned on top of it.
    // Coordinates refresh on every flush and on viewport resizes — the keyboard
    // changes layout without any DOM mutation to wake the reconciler.
    // The overlay must resolve against a positioned ancestor. The host rarely
    // positions these containers, so mark the expected one (CSS sets
    // position: relative for the marker, without !important so host styles stay
    // in charge) and let measureOverlay walk to whichever ancestor actually ends
    // up positioned — the math is self-consistent with any container.
    const ensurePositioned = (el, marker) => {
        if (getComputedStyle(el).position === 'static')
            el.setAttribute('data-mobile-nav', marker);
    };
    const positionedAncestor = (el) => {
        for (let node = el.parentElement; node !== null; node = node.parentElement) {
            if (getComputedStyle(node).position !== 'static')
                return node;
        }
        return null;
    };
    const measureOverlay = (host, reserve) => {
        const container = positionedAncestor(host);
        if (container === null)
            return null;
        const box = reserve.getBoundingClientRect();
        const base = container.getBoundingClientRect();
        // Center the host on its slot vertically, not top-align it. Measured
        // 2026-09-29 (issue #140 acceptance): the 20px ring top-aligned on its
        // 16px reserve hung its center at y=793 while the neighbouring keys sit
        // at 789-791 — reported as「不与其他小UI对齐」. Centering is a no-op for
        // same-height overlays (the 0.1.5/0.1.6 TPS text) and aligns the ring
        // with the cluster. hostRect is read BEFORE the style write below; its
        // height does not depend on top/left, so the math is stable across flushes.
        const hostRect = host.getBoundingClientRect();
        return {
            host,
            left: box.left - base.left - container.clientLeft,
            top: box.top - base.top - container.clientTop - (hostRect.height - box.height) / 2,
        };
    };
    const writeOverlay = (measured) => {
        const styled = measured.host;
        if (styled.style.left !== `${measured.left}px`)
            styled.style.left = `${measured.left}px`;
        if (styled.style.top !== `${measured.top}px`)
            styled.style.top = `${measured.top}px`;
    };
    // The composer root renders the TPS readout ("TPS 89.4 tok/s") as its own
    // row BELOW the status strip; fold it into the strip so every metric sits
    // on one line. Idempotent: the placeholder's text mirrors the readout and
    // the readout itself is overlaid on the placeholder's box.
    //
    // Locate half — queries and text only, NO geometry. It runs only when the pass
    // has already decided there may be something to write: the pass key's cheap
    // parts (anchor box + ring reserve box) are computed first, and the TPS part
    // goes through the three-tier short circuit in resolveTpsForKey (remembered
    // readout / zero-query pre-filter / real query). So a pass whose key did not
    // change reads no rectangle here and, in the common case, runs no query either.
    const locateTps = (stats) => {
        const stack = stats.closest('[class*="_composerStack"]');
        if (stack === null)
            return null;
        let reserve = stats.querySelector(':scope > [data-mobile-nav="stats-tps-reserve"]');
        const candidates = [...stack.querySelectorAll('div')];
        const picked = (0, exports.pickTpsReadout)(candidates);
        if (picked.index < 0)
            return null;
        const el = candidates[picked.index];
        if (el === undefined)
            return null;
        if (reserve === null) {
            reserve = document.createElement('span');
            reserve.setAttribute('data-mobile-nav', 'stats-tps-reserve');
            reserve.setAttribute('aria-hidden', 'true');
            stats.appendChild(reserve);
        }
        const live = el.textContent ?? '';
        if (reserve.textContent !== live)
            reserve.textContent = live;
        if (picked.mark)
            el.setAttribute('data-mobile-nav', 'stats-tps');
        const tpsRow = el.parentElement;
        if (tpsRow === null)
            return null;
        ensurePositioned(tpsRow, 'stats-tps-row');
        return { el, reserve, live };
    };
    // Measure half — geometry reads only, no style writes, and no queries either:
    // it works on whatever locateTps already resolved.
    const measureTps = (located) => {
        const { el, reserve } = located;
        const measured = measureOverlay(el, reserve);
        // The strip's last child is the flex shrink group: mirror whatever width
        // the placeholder settled on so the overlay clips with the same ellipsis
        // instead of overlapping the neighbouring group. Measured inside the SAME
        // read phase as the overlay boxes above — this read used to run after the
        // left/top write and forced a second synchronous layout.
        return { el, measured, width: reserve.getBoundingClientRect().width };
    };
    // Write half — touches styles only.
    const writeTps = (fold) => {
        if (fold.measured !== null)
            writeOverlay(fold.measured);
        const styled = fold.el;
        const maxWidth = `${fold.width}px`;
        if (styled.style.maxWidth !== maxWidth)
            styled.style.maxWidth = maxWidth;
    };
    // 2026-09-23（店主最终确认）：**环要、百分比数字不要** —— 环显示在输入框行
    // 的右簇（模型/麦克风旁），CSS 用 font-size:0 只留环、隐掉 "45%" 文本；统计条
    // 拿满整宽。与 moveTps 同款 overlay：环留在 React 渲染的 dock 原位，插件自建
    // 占位 span 顶住右簇槽位（16px 环 + 2px 边距，行 gap 补足余量）。
    // 环读数的选取判据：dock 里第一个「不含统计条 + 文本带百分比」的子节点。
    // 单独提出来是要让快路径的身份校验与 measureRing 用**同一条**判据 —— 两处漂移就会漏掉
    // 被 React 换掉的那个环（见 ringIdentitySettled）。
    const measureRing = (stats) => {
        const located = (0, composer_anchor_ts_1.locateRing)(stats);
        if (located === null)
            return null;
        const { dock, ring } = located;
        const row = document.querySelector('[data-composer-card] [class*="_row"] [class*="_trailing"]');
        if (row === null)
            return null;
        let reserve = row.querySelector(':scope > [data-mobile-nav="stats-ring-reserve"]');
        const primary = row.querySelector(':scope > [class*="_primary"]');
        if (reserve === null) {
            reserve = document.createElement('span');
            reserve.setAttribute('data-mobile-nav', 'stats-ring-reserve');
            row.insertBefore(reserve, primary);
        }
        else if (primary === null ? row.lastElementChild !== reserve : reserve.nextElementSibling !== primary) {
            // React rebuilt the row and shuffled its children around our
            // placeholder: put the reserved slot back at the anchor position.
            row.insertBefore(reserve, primary);
        }
        if (ring.getAttribute('data-mobile-nav') !== 'stats-ring') {
            ring.setAttribute('data-mobile-nav', 'stats-ring');
        }
        ensurePositioned(dock, 'stats-ring-dock');
        return { ring, reserve, measured: measureOverlay(ring, reserve) };
    };
    // Slow path only (re-anchoring after React rebuilt the marker): measure and
    // write each fold on its own. The hot fast path in mark() below does not use
    // these; its own per-pass bound is documented there.
    //
    // Do not carry that bound over to the slow path — it does not hold here, and
    // this used to claim it did. Each wrapper keeps a write→read boundary of its
    // own: moveTps mirrors the reading into the placeholder before measuring it
    // (inherent — the slot width cannot be measured until the text is in it), and
    // measureRing re-inserts the placeholder before measuring when React shuffled
    // it. relayoutNow() runs both, so it can force two layouts in one pass. Each
    // wrapper alone forces at most one.
    const moveTps = (stats) => {
        const located = locateTps(stats);
        if (located !== null)
            writeTps(measureTps(located));
    };
    const moveRing = (stats) => {
        const fold = measureRing(stats);
        if (fold !== null && fold.measured !== null)
            writeOverlay(fold.measured);
    };
    let viewportHandler = null;
    // The keyboard animation drives visualViewport.resize once per frame, and the
    // two folds below read rects and write styles (forced synchronous layout), so
    // one event per frame meant one overlay pass per frame. Coalesce every event
    // in a frame into a single pass, and skip the pass entirely when the anchor
    // box did not move. Measured 2026-10-09 on Android 16 WebView: opening the
    // drawer went from a 58ms long task to none. A React rebuild that re-creates
    // the TPS readout at the same box is still re-folded by mark()'s own fast
    // path, which runs on every tree mutation (scopes: the tree key).
    let relayoutRaf = 0;
    let relayoutKey = '';
    // The ring overlay's visual anchor is its reserve span, and that span lives in
    // the composer card's trailing lane — NOT in the dock the stats strip sits in.
    // The lane re-lays out on its own: measured 2026-10-11 on a live host session
    // (393×844, a running turn), turning the send button into 「Stop generating +
    // Steer message」 moved the reserve 310px -> 265px while the strip's own box did
    // not move at all. A skip key built only from the strip box therefore skipped
    // the pass, the ring kept its stale left/top and painted 45px off its slot —
    // right on top of the Stop button (report: 「发送键旁边两个蓝色控件叠在一起」).
    // So the reserve's box is part of both skip keys. The task's own rule is that a
    // SKIPPED pass runs no query, so the fast path keys off the CACHED reserve
    // element it folded last time instead of re-querying it: one extra rect read,
    // still before any write and in the same read phase as the strip's own read, so
    // it forces no additional layout. A rebuilt or detached reserve reads as a
    // different box (or 0×0) and therefore forces the re-fold, which is intended.
    const ringReserveBoxKey = (reserve) => {
        if (reserve === null)
            return 'none';
        const box = reserve.getBoundingClientRect();
        return `${Math.round(box.left)}:${Math.round(box.top)}:${Math.round(box.width)}`;
    };
    const relayoutNow = () => {
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor === null)
            return;
        const rect = anchor.getBoundingClientRect();
        // The resize path may run before any mark() fold, so it resolves the ring
        // reserve by query here; only the fast path is bound by the no-query rule.
        const key = `${Math.round(rect.left)}:${Math.round(rect.top)}:${Math.round(rect.width)}:${Math.round(rect.height)}|${ringReserveBoxKey(document.querySelector('[data-mobile-nav="stats-ring-reserve"]'))}`;
        if (key === relayoutKey)
            return;
        relayoutKey = key;
        // Same read-before-write ordering as mark()'s fast path.
        const located = locateTps(anchor);
        const tps = located === null ? null : measureTps(located);
        const ring = measureRing(anchor);
        if (tps !== null)
            writeTps(tps);
        if (ring !== null && ring.measured !== null)
            writeOverlay(ring.measured);
    };
    const relayout = () => {
        if (relayoutRaf !== 0)
            return;
        relayoutRaf = requestAnimationFrame(() => {
            relayoutRaf = 0;
            relayoutNow();
        });
    };
    // Candidate C state: the key the last completed fold was keyed on, and the
    // elements it folded. See the fast path in mark() below.
    let fastKey = '';
    let foldedTps = null;
    let foldedTpsReserve = null;
    let foldedRing = null;
    let foldedRingReserve = null;
    const stillFolded = (el, marker) => el !== null && el.isConnected && el.getAttribute('data-mobile-nav') === marker;
    /**
     * 环形读数是否还是上一趟折过的**同一个节点**（零查询：只扫 dock 的 children，与
     * measureRing 共用 isRingCandidate）。
     *
     * 为什么 liveness 不够（#194 的坑）：React 换掉环时旧节点**可能仍然连着**、marker 也还在，
     * stillFolded 完全看不出来 —— 而新节点没有我们的 inline left/top，不重折就会停在错位置。
     * 旧版是靠「TPS 缺席 ⇒ 永不 skip」这条副作用顺带兜住的（那份每帧空转正是 2026-10-13 要
     * 修掉的东西），所以「缺席也能 skip」之后必须自己验一次身份；顺带把 TPS 在场时的同一个
     * 漏洞也堵上（那时旧版同样看不出来，只是没人碰过）。
     */
    const ringIdentitySettled = (stats) => {
        const located = (0, composer_anchor_ts_1.locateRing)(stats);
        // 认不出 dock（宿主结构变了）：宁可不 skip。
        if (located === null)
            return false;
        return located.ring === foldedRing;
    };
    const mark = () => {
        // Keyboard open/close and viewport rotations relayout the composer without
        // any DOM mutation, so the overlays need their own re-layout channel.
        if (viewportHandler === null) {
            viewportHandler = relayout;
            window.addEventListener('resize', relayout);
            window.visualViewport?.addEventListener('resize', relayout);
        }
        // Fast path: the marked strip usually survives React rebuilds between
        // tokens; re-verifying the anchor is O(1) while the full-tree hunt below
        // grows with the conversation. moveTps still re-runs so a rebuilt TPS
        // readout is re-folded.
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null && statsAnchorAlive(anchor)) {
            // Read—write separation (candidate B): every rectangle both folds need is
            // measured BEFORE either fold writes a style. Interleaving them (write
            // left/top, then read the placeholder width, then read the ring's boxes)
            // forced a synchronous layout after each write — three per pass. After the
            // split the only write still followed by a geometry read is locateTps'
            // placeholder-text mirror, and that one is inherent: the slot width can
            // only be measured once the reading is in it (true before #194 too). So a
            // pass with an unchanged reading forces one layout, a changed reading
            // forces two — down from three, not to zero.
            //
            // Skip-when-unchanged (candidate C): when the anchor box AND the TPS
            // reading are both unchanged there is nothing to write, so the pass skips
            // the ring query, every geometry read and every style write. The key is
            // read before anything is written, so it costs no extra layout. The
            // remembered fold targets are then re-checked for liveness, because React
            // replaces these readouts on rebuild: a marker left on a disconnected node
            // must force a fresh fold, which the unconditional fast path used to
            // provide for free — dropping that check would leave a rebuilt readout
            // unfolded on screen.
            //
            // 2026-10-13 两处修正（都在下面就地注明）：
            //   ① TPS 那一段的解析改成三级短路 —— 旧版在 key 比较**之前**无条件跑 locateTps，
            //      而它是本效果仅存的每帧全栈查询；
            //   ② TPS 缺席不再让跳过判定恒 false（旧版缺席时每帧白折一遍）。
            // 环形的两个 liveness 门一个字没动（#194 的三条实测泄漏靠它们兜）。
            const rect = anchor.getBoundingClientRect();
            const box = `${Math.round(rect.left)}:${Math.round(rect.top)}:${Math.round(rect.width)}:${Math.round(rect.height)}`;
            // 2026-10-13（本效果仅存的每帧查询）：TPS 那一段改成三级短路解析 —— 记得住的读数
            // 节点还活着就零查询读它的文本；否则先用栈内文本做零查询预筛；只有预筛命中才做
            // locateTps 里那条 `stack.querySelectorAll('div')`（详见 resolveTpsForKey）。
            const tpsAlive = stillFolded(foldedTps, 'stats-tps');
            const tpsReserveAlive = stillFolded(foldedTpsReserve, 'stats-tps-reserve');
            const rememberedTps = tpsAlive && tpsReserveAlive && foldedTps !== null && foldedTpsReserve !== null
                ? { el: foldedTps, reserve: foldedTpsReserve, live: '' }
                : null;
            const tpsStack = anchor.closest('[class*="_composerStack"]');
            const located = resolveTpsForKey(rememberedTps, foldedTps?.textContent ?? '', () => tpsStack?.textContent ?? '', () => locateTps(anchor));
            const key = `${box}|${located === null ? 'none' : located.live}|${ringReserveBoxKey(foldedRingReserve)}`;
            // 2026-10-13：TPS 两个槽的 liveness 门改成「与 key 里那一段一致」—— key 本趟已经
            // **现读**了「在不在 + 读数文本」，所以「本趟缺席且上一趟也缺席」是一个稳定态、
            // 不该再 veto（旧版这两个 stillFolded 在缺席时恒 false ⇒ 永不 skip、每帧白折一遍）。
            // 判据本身抽成纯函数 tpsSlotsSettled，好让这条修复被测试直接钉住。
            // 环形那两个槽没有 presence 位（环缺席时 key 里没有它的盒子），保持原样：缺席时
            // 宁可不 skip，也不能漏掉「环新出现」的那一趟。
            const tpsSettled = tpsSlotsSettled(located === null, foldedTps === null && foldedTpsReserve === null, stillFolded(foldedTps, 'stats-tps'), stillFolded(foldedTpsReserve, 'stats-tps-reserve'));
            if (key === fastKey &&
                tpsSettled &&
                stillFolded(foldedRing, 'stats-ring') &&
                // 身份校验放在最后：它只在前四条都过（= 真要跳过了）时才需要跑，而它要扫一遍 dock
                // 的 children。零查询，但不该在流式那种「key 每趟都变」的路上白跑。
                ringIdentitySettled(anchor) &&
                stillFolded(foldedRingReserve, 'stats-ring-reserve')) {
                return;
            }
            fastKey = key;
            // 真折这一趟：合成路径（el 就是上一趟折过的那个节点）按老路径再解析一次，把镜像文本 /
            // 标记 / 占位定位这些副作用做到位 —— 这一趟的开销与旧版完全相同（旧版每趟都解析）；
            // 预筛真查到的那种直接复用，不重复查询。
            const foldTps = located !== null && located.el === foldedTps ? locateTps(anchor) : located;
            const tps = foldTps === null ? null : measureTps(foldTps);
            const ring = measureRing(anchor);
            if (tps !== null)
                writeTps(tps);
            if (ring !== null && ring.measured !== null)
                writeOverlay(ring.measured);
            foldedTps = foldTps === null ? null : foldTps.el;
            foldedTpsReserve = foldTps === null ? null : foldTps.reserve;
            foldedRing = ring === null ? null : ring.ring;
            foldedRingReserve = ring === null ? null : ring.reserve;
            // Re-key with the reserve we just folded: the key above was built before the
            // fold existed, so it could only say 'none' on the first pass — leaving the
            // next (unchanged) pass to re-fold once for nothing. Re-keying here keeps the
            // 「unchanged ⇒ skip」 invariant from the very first pass on.
            fastKey = `${box}|${located === null ? 'none' : located.live}|${ringReserveBoxKey(foldedRingReserve)}`;
            return;
        }
        // Stale marker on a node that left the composer stack/phase context:
        // drop it so the slow path can re-anchor cleanly.
        anchor?.removeAttribute('data-mobile-nav');
        // Scope decision: the status row is a DESCENDANT of the composer stack,
        // not necessarily its child. On rc.2 hosts it is a direct child (its own
        // `_root`); on 0.1.5 the composer card wrapper (uV2eYG_root) sits between
        // the stack and the row (bOPqQW_root), so requiring a direct child made
        // the hunt permanently miss and the strip was never marked (measured: row
        // present at 16,814 carrying "8 turns 582 steps · 103 tok/s" while
        // [data-mobile-nav="stats"] was absent). Body blocks outside the stack are
        // still skipped by the containment test below.
        const stack = document.querySelector('[class*="_composerStack"]');
        if (stack === null)
            return;
        // 候选集分两档，**先旧代后新代**：0.1.5–0.2.0 的统计条是 `bOPqQW_root`，`_root` 档命中，
        // 标记落在统计条自身（历代验收过的几何）。0.2.1 把 StatsPills 拆成 activity/usage 两个
        // slot 条目，渲染进同一个 slot 包装（`[data-slot]`，无 class），composerStack 内再没有共同
        // 的 `_root`——只认 `_root` 时四个候选（输入卡 / Full access / 模型名 / 环）全被下面的判据
        // 筛掉，标记永远打不上，整条 stats 与环的 CSS 集体哑火。
        // 两档分开查而不是合成一条选择器：`querySelectorAll` 按文档序返回，而 slot 包装在旧代里是
        // 统计条的**祖先**，合成一条会让标记从 `bOPqQW_root` 挪到外层包装，改掉旧代几何。
        for (const selector of ['[class*="_root"]', '[data-slot]']) {
            for (const root of stack.querySelectorAll(selector)) {
                // The status row lives inside the composer stack. The query is already
                // scoped to the stack, so every candidate is inside it by construction —
                // message-area blocks that mention turns/steps never enter this loop. (A
                // `stack.contains(root)` guard stood here and its comment claimed to skip
                // those blocks; it was unreachable.)
                // The todo plan strip also lives in the composer stack and its root
                // ends in _root. Its items may legitimately contain "步"/"steps" in
                // their text, so never mistake it (or any interactive dock panel)
                // for the stats strip.
                if (root.matches('[data-testid="todo-panel"]'))
                    continue;
                // Dock panels are skipped by never marking a candidate whose buttons are
                // actionable controls. 0.1.5 renders the status row ITSELF as two
                // popover buttons (bOPqQW_pill, aria-haspopup="dialog"), so an
                // "any button" guard excluded the one row this task exists to mark
                // (measured: bOPqQW_root rejected solely by hasButton, marker count 0).
                // Every popover button counts as a status widget: the composer's real
                // controls (model bar, context meter) carry no metrics text and are
                // filtered by the text test above, and a panel with an actionable button
                // still fails here.
                const buttons = root.querySelectorAll('button');
                if (buttons.length > 0 && ![...buttons].every((button) => button.getAttribute('aria-haspopup') !== null))
                    continue;
                const text = root.textContent ?? '';
                if (!/(turns|steps|\bLLM\b|轮|步)/.test(text))
                    continue;
                // Composer card must never be mistaken for the status strip; exclude
                // its input region across both composer DOMs (textarea / Lexical
                // contentEditable marked data-composer-input).
                if (root.querySelector('textarea, [data-composer-input]') !== null)
                    continue;
                root.setAttribute('data-mobile-nav', 'stats');
                moveTps(root);
                moveRing(root);
                return;
            }
        }
    };
    // Scope decision: TPS readout updates are text mutations in the composer
    // stack, in BOTH shapes -- React rebuilding the readout node (childList, the
    // tree key) and React rewriting the text in place (characterData, the text
    // key exported as TEXT_MUTATION_SCOPE). Both are declared: dropping either
    // stops the readout from being re-folded. A subtree observer would need one
    // per container, so the shared full-tree observer keeps its expensive scan.
    return {
        name: 'stats-line',
        scopes: ['*', reconciler_core_ts_1.TEXT_MUTATION_SCOPE],
        ensure: mark,
        dispose: () => {
            // Hand the official layout back: drop every marker (the strip loses its
            // one-line layout, ring/TPS overlays return to static flow) and remove
            // the plugin-owned placeholders.
            if (relayoutRaf !== 0) {
                cancelAnimationFrame(relayoutRaf);
                relayoutRaf = 0;
            }
            relayoutKey = '';
            if (viewportHandler !== null) {
                window.removeEventListener('resize', viewportHandler);
                window.visualViewport?.removeEventListener('resize', viewportHandler);
                viewportHandler = null;
            }
            for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring"], [data-mobile-nav="stats-tps"]')) {
                const styled = el;
                styled.style.left = '';
                styled.style.top = '';
                styled.style.maxWidth = '';
            }
            for (const key of ['stats', 'stats-ring', 'stats-ring-dock', 'stats-tps', 'stats-tps-row']) {
                for (const el of document.querySelectorAll(`[data-mobile-nav="${key}"]`)) {
                    el.removeAttribute('data-mobile-nav');
                }
            }
            for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring-reserve"], [data-mobile-nav="stats-tps-reserve"]')) {
                el.remove();
            }
        },
    };
}
};
__modules["effects/preview-fullscreen.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createPreviewFullscreenTask = createPreviewFullscreenTask;
const frame_ts_1 = require("./platform/frame.js");
function createPreviewFullscreenTask(t) {
    let button = null;
    const syncLabel = (target) => {
        const full = (0, frame_ts_1.getFrame)()?.hasAttribute('data-mobile-preview-full') ?? false;
        const label = t(full ? 'previewExitFullscreen' : 'previewFullscreen');
        if (target.getAttribute('aria-label') === label)
            return;
        target.setAttribute('aria-label', label);
        target.title = label;
    };
    const onClick = () => {
        (0, frame_ts_1.getFrame)()?.toggleAttribute('data-mobile-preview-full');
        if (button !== null)
            syncLabel(button);
    };
    return {
        name: 'preview-fullscreen-toggle',
        scopes: ['data-aionui-preview-open', 'data-mobile-preview-full'],
        ensure: () => {
            const col = document.querySelector('[data-aionui-preview-col]');
            if (col === null)
                return;
            if (button === null) {
                button = document.createElement('button');
                button.type = 'button';
                button.dataset.mobileNav = 'preview-full-toggle';
                button.innerHTML = [
                    '<svg class="dsh-web-mobile-full-in" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
                    '<path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
                    '</svg>',
                    '<svg class="dsh-web-mobile-full-out" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
                    '<path d="M6 2v4H2M10 2v4h4M6 14v-4H2M10 14v-4h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
                    '</svg>',
                ].join('');
                button.addEventListener('click', onClick);
            }
            syncLabel(button);
            if (button.parentElement !== col)
                col.appendChild(button);
        },
        dispose: () => {
            button?.remove();
            button = null;
        },
    };
}
};
__modules["platform/drawer-open.js"] = function (require, module, exports) {
"use strict";
// Platform layer: the one "the drawer is about to open" entry every opener shares.
//
// Why it lives here and not in an effect: the openers are drawer-close.ts
// (toggleDrawer: header toggle, FAB) and sidebar-swipe.ts (the gesture's arm
// step), and the thing that must run before them belongs to a third effect
// (the backdrop in drawer-layer.ts). Wiring them to each other would add
// effects → effects edges (the architecture ledger only shrinks); a neutral hook
// slot plus a focus helper keeps all three on platform/ instead. Nothing here
// knows what the hook paints.
//
// Two jobs (2026-11-05 drawer-jank report, R2 / R3):
// 1. Release text focus before the host opens the drawer. The host's
//    useModalLayer moves focus into the drawer from a layout effect
//    (dsh-client-ui-primitives lib/index.js:1683). With the composer focused,
//    that focus move closes the soft keyboard WHILE the slide runs, the visual
//    viewport grows mid-transition, viewport-meta republishes
//    --dsha-mobile-visible-vh and the whole shell re-lays out under the moving
//    drawer. Tap openers blur first and open once the keyboard has gone; the
//    gesture opener blurs at arm time without waiting (the finger is moving).
// 2. Fire the registered opening hook synchronously right before the toggle,
//    so the backdrop is appended in the SAME task as the host commit: its
//    childList record joins the commit's mutation batch, i.e. one reconciler
//    flush instead of two, and the dim starts on the drawer's first frame.
Object.defineProperty(exports, "__esModule", { value: true });
exports.onDrawerOpening = onDrawerOpening;
exports.drawerOpening = drawerOpening;
exports.softKeyboardUp = softKeyboardUp;
exports.releaseTextFocus = releaseTextFocus;
exports.openDrawerAfterKeyboard = openDrawerAfterKeyboard;
/** Text-entry surfaces whose focus keeps a soft keyboard up. */
const TEXT_ENTRY_SELECTOR = '[contenteditable]:not([contenteditable="false"]), textarea, ' +
    'input:not([type]), input[type="text"], input[type="search"], input[type="email"], ' +
    'input[type="url"], input[type="tel"], input[type="password"], input[type="number"]';
/** Keyboard at least this tall counts as up (same threshold as composer-keyboard-guard.ts). */
const KEYBOARD_MIN_PX = 120;
/** Quiet time after the last viewport resize, once the keyboard reads down, before opening. */
const KEYBOARD_SETTLE_MS = 60;
/** Hard cap on the wait: an engine that never resizes must not hold the drawer back. */
const KEYBOARD_WAIT_CAP_MS = 450;
/**
 * The one piece of module state: the opening hook slot and the pending
 * deferred open. A const object (not two `let`s), so this file adds no entry
 * to the architecture ledger's module-state table.
 */
const state = {
    hook: null,
    cancelPending: null,
};
/**
 * Register the callback that runs right before an opening toggle.
 * @param hook - runs synchronously, before the host state flips.
 * @returns unregister (only clears the slot while it still holds this hook).
 */
function onDrawerOpening(hook) {
    state.hook = hook;
    return () => {
        if (state.hook === hook)
            state.hook = null;
    };
}
/** Run the registered opening hook (no-op when none is registered). */
function drawerOpening() {
    state.hook?.();
}
/**
 * Whether the soft keyboard is up: the keyboard-less height viewport-meta keeps
 * in --dsh-web-mobile-vh (falls back to innerHeight) against the visual
 * viewport height.
 */
function softKeyboardUp() {
    const stable = Number.parseFloat(document.documentElement.style.getPropertyValue('--dsh-web-mobile-vh'));
    const base = Number.isFinite(stable) && stable > 0 ? stable : window.innerHeight;
    const visible = window.visualViewport?.height ?? window.innerHeight;
    return base - visible > KEYBOARD_MIN_PX;
}
/**
 * Blur the focused text-entry element, if any.
 * @returns true when focus was released AND the soft keyboard was up at that moment.
 */
function releaseTextFocus() {
    const active = document.activeElement;
    if (!(active instanceof HTMLElement) || !active.matches(TEXT_ENTRY_SELECTOR))
        return false;
    const keyboardWasUp = softKeyboardUp();
    active.blur();
    return keyboardWasUp;
}
/**
 * Tap-opener entry: release text focus, then open — immediately when no
 * keyboard was up, otherwise once the keyboard has gone (it reads down and the
 * viewport has been quiet for KEYBOARD_SETTLE_MS) or after KEYBOARD_WAIT_CAP_MS.
 * A second call while an open is pending cancels it (the user tapped the toggle
 * again) and does nothing else.
 * @param open - fires the opening hook and flips the host state.
 */
function openDrawerAfterKeyboard(open) {
    if (state.cancelPending !== null) {
        state.cancelPending();
        return;
    }
    if (!releaseTextFocus()) {
        open();
        return;
    }
    let quietTimer = 0;
    const onResize = () => {
        window.clearTimeout(quietTimer);
        if (!softKeyboardUp())
            quietTimer = window.setTimeout(finish, KEYBOARD_SETTLE_MS);
    };
    const cancel = () => {
        window.clearTimeout(quietTimer);
        window.clearTimeout(capTimer);
        window.visualViewport?.removeEventListener('resize', onResize);
        window.removeEventListener('resize', onResize);
        state.cancelPending = null;
    };
    const finish = () => {
        cancel();
        open();
    };
    const capTimer = window.setTimeout(finish, KEYBOARD_WAIT_CAP_MS);
    state.cancelPending = cancel;
    window.visualViewport?.addEventListener('resize', onResize);
    window.addEventListener('resize', onResize);
}
};
__modules["effects/drawer-layer.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.fadeDimOut = fadeDimOut;
exports.createDrawerLayerTask = createDrawerLayerTask;
const frame_ts_1 = require("./platform/frame.js");
const drawer_open_ts_1 = require("./platform/drawer-open.js");
/** Start the dim's fade-out and disable the hit target early. Called by the
 * gesture layer when a close commit starts animating, so the dimming fades
 * WITH the drawer's slide-out instead of vanishing after it. The dim itself is
 * painted by the frame's ::after (data-mobile-nav-dim); the catcher element is
 * persistent and only re-arms when the marker flips. */
function fadeDimOut() {
    fadeHook?.();
}
let fadeHook = null;
/** The dim's opacity transition, shared with the drawer column's .28s slide
 * (user request 2026-08-29 「背景黑色遮罩进行渐变动画」). */
const DIM_FADE_MS = 280;
/** How long the presence-gated band outlives the marker flip: one fade plus
 * the 60ms slack the old removal timer carried. The slack is load-bearing for
 * the 快捷键弹层 z band (2026-09-25): the raised modal root must outlive the
 * host's pane-subtree swap, measured at ~200-280ms after the flip. */
const BAND_RELEASE_MS = DIM_FADE_MS + 60;
/** How long an optimistically started dim (see showNow) waits for the host's
 * open commit before the closed-state fade is allowed to take it back. */
const OPEN_CONFIRM_MS = 120;
/** The FAB's second face: an arrow, shown while a sidebar panel owns the main
 * area — both the subpage 「返回」 and the panel-root 「返回会话」 modes. */
const FAB_BACK_ICON = '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
    '<path d="M9.8 3.4 5.2 8l4.6 4.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>';
const FAB_DRAWER_ICON = '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
    '<path fill-rule="evenodd" clip-rule="evenodd" d="M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C4.29683 14.1164 5.13612 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C5.13612 1.88307 4.29683 1.88358 3.65076 1.95353C3.47672 1.97129 3.30027 1.98546 3.10872 1.9954L4.1828 1.91166Z" fill="currentColor"/>' +
    '</svg>';
/**
 * @param t - our dictionary (the `NS` locale namespace, i18n/locales.ts).
 * @param toggleSidebar - opens/closes the drawer.
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts). The FAB is the
 *   screen's only full-size control while a panel owns the main area — the
 *   header toggle does not render there — so it also steps back: one host view
 *   level on a panel subpage, out of the panel at its root. Null on a host that
 *   cannot select panels (rc.6), where it stays a plain drawer button.
 */
function createDrawerLayerTask(t, toggleSidebar, panelExit) {
    let fab = null;
    /**
     * Persistent click catcher (`hitLayer`). It paints nothing and carries no interactive
     * role/aria on purpose: the dim is painted by the frame's own ::after (same
     * paint tree as the conversation, no separate full-screen layer). When this
     * element carried role="button", the device browser treated the full-screen
     * surface as a pressed button — the "tap the blank area → the right side
     * flashes bright" report (2026-11-08, device-only).
     */
    let hitLayer = null;
    /** Pending confirm of an optimistic dim (showNow); while armed the closed
     * branch leaves the dim alone — the host commit may simply not have landed. */
    let openConfirmTimer = null;
    /** Releases [data-active] after the close fade so presence-gated CSS never
     * flips in the same recalculation that starts the dim change. */
    let activeReleaseTimer = null;
    /** True while a close commit has pre-faded the dim but the marker is still
     * open (late commit). The open branch must not re-light the dim in that
     * window: a tree mutation from anywhere in the document runs this task
     * (`scopes` carries the tree key), and restoring there would undo the
     * pre-fade and hand the screen a second dark step — the bug the removed
     * mount-based guard used to prevent (2026-11-08 review). Cleared by the
     * marker flip. */
    let dimHeldOff = false;
    let removeOpeningHook = null;
    const ensureHitLayer = (frame) => {
        if (hitLayer === null) {
            hitLayer = document.createElement('div');
            hitLayer.dataset.mobileNav = 'hit';
            hitLayer.setAttribute('aria-hidden', 'true');
            hitLayer.style.pointerEvents = 'none';
            frame.appendChild(hitLayer);
        }
        return hitLayer;
    };
    const setDim = (frame, on) => {
        frame.setAttribute('data-mobile-nav-dim', on ? '1' : '0');
    };
    /** Cancel the optimistic-open confirm window (armed by showNow). */
    const cancelOpenConfirm = () => {
        if (openConfirmTimer === null)
            return;
        window.clearTimeout(openConfirmTimer);
        openConfirmTimer = null;
    };
    /** Cancel the pending band release ([data-active] removal). */
    const cancelActiveRelease = () => {
        if (activeReleaseTimer === null)
            return;
        window.clearTimeout(activeReleaseTimer);
        activeReleaseTimer = null;
    };
    const releaseActiveAfter = (ms) => {
        cancelActiveRelease();
        activeReleaseTimer = window.setTimeout(() => {
            activeReleaseTimer = null;
            delete hitLayer?.dataset.active;
        }, ms);
    };
    const drawerOpen = () => {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return false;
        // The user's call (2026-09-13): our drawer is the one users get, even on
        // hosts that ship their own overlay drawer. The host's version measures
        // 321px wide with z-index:1100 and, notably, NO full-screen backdrop at all
        // (measured: the conversation stays hit-testable beside it), which is the
        // behaviour the phone owner rejected as unusable. So the legacy column
        // rules stay armed and the drawer keeps our own dim and hit target.
        return !frame.hasAttribute('data-sidebar-collapsed');
    };
    const heroPhase = () => document.querySelector('[data-phase="active"]') === null;
    /**
     * The FAB does one job per view. While a sidebar panel owns the main area it
     * steps back through the panel and everywhere else it opens the drawer.
     *
     * It matters because the FAB is the ONLY full-size control on screen there:
     * the panel replaces the conversation, so the header (and with it the drawer
     * toggle) does not render. A panel subpage does ship the host's own
     * 「返回上一级」 control (moreBack / crumb), but it sits in the same top-left
     * corner and is geometrically covered by the FAB — so the FAB has to be the
     * one that climbs a level.
     */
    const onFabClick = (event) => {
        if (panelExit !== null && panelExit.panelOpen()) {
            event.preventDefault();
            event.stopPropagation();
            // One level, not the whole panel: on a plugin subpage this clicks the
            // host's own 「返回上一级」 (see back in panel-exit.ts).
            panelExit.back();
            return;
        }
        toggleSidebar();
    };
    /**
     * Icon and accessible name follow the view so the button never reads as a
     * mystery control. Three states, two icons: open-drawer (no panel),
     * back-level (panel subpage — same arrow, named 「返回」, because it climbs a
     * host view level and does not leave the panel) and exit-panel (panel root —
     * named 「返回会话」). Idempotent: ensure() runs on every mutation burst.
     */
    const syncFab = () => {
        if (fab === null)
            return;
        const onPanel = panelExit !== null && panelExit.panelOpen();
        const onSubpage = onPanel && panelExit !== null && panelExit.subpageOpen();
        const mode = onSubpage ? 'back-level' : onPanel ? 'exit-panel' : 'open-drawer';
        if (fab.dataset.mobileNavFabMode === mode)
            return;
        fab.dataset.mobileNavFabMode = mode;
        const label = onSubpage ? t('back') : onPanel ? t('backToConversation') : t('open');
        fab.setAttribute('aria-label', label);
        fab.title = label;
        fab.innerHTML = onPanel ? FAB_BACK_ICON : FAB_DRAWER_ICON;
    };
    /**
     * Opening hook (platform/drawer-open.ts, drawer-jank R3): every opener calls
     * it right before flipping the host, so the dim starts on the drawer's first
     * frame. Unlike the old optimistic mount this only flips the frame marker —
     * no element is inserted and no full-screen layer is created on the open
     * frame (that insertion was the device flash). The next ensure() stays the
     * source of truth and fades the dim back if the open never landed.
     */
    const showNow = () => {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null || !frame.hasAttribute('data-sidebar-collapsed'))
            return;
        // A fresh open cancels any pending pre-fade from a close still in flight.
        dimHeldOff = false;
        ensureHitLayer(frame);
        setDim(frame, true);
        cancelOpenConfirm();
        openConfirmTimer = window.setTimeout(() => {
            openConfirmTimer = null;
            sync();
        }, OPEN_CONFIRM_MS);
    };
    const sync = () => {
        // Re-armed on every ensure, not once in the factory: core.deactivate()
        // runs dispose() on every MOBILE_QUERY flip (reconciler-core.ts), and
        // reactivation only re-runs ensure() - a hook assigned in the factory
        // body stayed null from the first breakpoint flip onwards, silently
        // killing the dim fade.
        fadeHook = () => {
            const frame = (0, frame_ts_1.getFrame)();
            dimHeldOff = true;
            if (frame !== null)
                setDim(frame, false);
            if (hitLayer !== null)
                hitLayer.style.pointerEvents = 'none';
            releaseActiveAfter(BAND_RELEASE_MS);
        };
        removeOpeningHook ??= (0, drawer_open_ts_1.onDrawerOpening)(showNow);
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return;
        const hit = ensureHitLayer(frame);
        if (drawerOpen()) {
            cancelOpenConfirm();
            // A close commit may still be animating (marker not flipped yet): the
            // pre-fade owns the dim until that flip, so restoring here is a no-op.
            if (!dimHeldOff) {
                cancelActiveRelease();
                setDim(frame, true);
                hit.dataset.active = '';
                hit.style.pointerEvents = 'auto';
            }
        }
        else if (openConfirmTimer === null) {
            // Fade the dim out (geometry-static opacity transition on the frame's
            // ::after) and release the presence-gated band once it has landed.
            dimHeldOff = false;
            setDim(frame, false);
            if (hit.style.pointerEvents !== 'none') {
                hit.style.pointerEvents = 'none';
                releaseActiveAfter(BAND_RELEASE_MS);
            }
        }
        /**
         * 插件二级页（面板子页）不挂悬浮键（owner 2026-11-05：真机上它压在最上面，
         * 与宿主自己的「‹ 插件」返回条重复，要求去掉）。一级面板仍留着它当「返回会话」，
         * 会话页则是抽屉入口。
         */
        const onSubpage = panelExit !== null && panelExit.subpageOpen();
        if (heroPhase() && !drawerOpen() && !onSubpage && fab === null) {
            fab = document.createElement('button');
            fab.type = 'button';
            fab.dataset.mobileNav = 'fab';
            fab.setAttribute('aria-label', t('open'));
            fab.title = t('open');
            fab.addEventListener('click', onFabClick);
            frame.appendChild(fab);
        }
        else if ((!heroPhase() || drawerOpen() || onSubpage) && fab !== null) {
            fab.remove();
            fab = null;
        }
        syncFab();
    };
    return {
        name: 'drawer-layer',
        scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
        ensure: sync,
        dispose: () => {
            cancelOpenConfirm();
            cancelActiveRelease();
            removeOpeningHook?.();
            removeOpeningHook = null;
            fadeHook = null;
            (0, frame_ts_1.getFrame)()?.removeAttribute('data-mobile-nav-dim');
            hitLayer?.remove();
            hitLayer = null;
            fab?.remove();
            fab = null;
        },
    };
}
};
__modules["effects/file-viewer-compat.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createFileViewerMarkerTask = createFileViewerMarkerTask;
const frame_ts_1 = require("./platform/frame.js");
/**
 * dsh-file-viewer open marker. The plugin renders a
 * <section class="dsfv-panel" data-conversation-composer-overlay> as a
 * conversation.view tab next to "对话"/"轨迹" (its own idempotent <style>
 * uses the stable `dsfv-` prefix — no CSS Modules). Detection keys on the
 * panel's own `.dsfv-panel` class, NOT on `data-conversation-composer-overlay`:
 * that attribute is the host conversation.view system's generic overlay flag
 * shared by every view tab (the built-in trajectory tab sets it too), so it
 * would false-positive the marker whenever any overlay is open. The marker
 * only reflects the file-viewer panel existing and serves the compat.css
 * mobile layout rules. Gesture takeover is a separate signal —
 * sidebar-swipe.ts reads the generic overlay attribute directly.
 * Idempotent like the other frame markers; dispose clears it when the tab
 * unmounts or on deactivation.
 * Scoped to the mobile branch because the reconciler only runs when the
 * mobile breakpoint is active (registerReconcileTasks installs it there).
 * (Port of community fork fix 2ff7976, re-scoped per design
 * 2026-09-06-conversation-overlay-takeover-design.md §4.1.)
 */
function createFileViewerMarkerTask() {
    return {
        name: 'file-viewer-open-marker',
        scopes: ['*'],
        ensure: () => {
            const frame = (0, frame_ts_1.getFrame)();
            if (frame === null)
                return;
            const active = document.querySelector('.dsfv-panel') !== null;
            if (active) {
                frame.setAttribute('data-file-viewer-open', '');
            }
            else if (frame.hasAttribute('data-file-viewer-open')) {
                frame.removeAttribute('data-file-viewer-open');
            }
        },
        dispose: () => {
            (0, frame_ts_1.getFrame)()?.removeAttribute('data-file-viewer-open');
        },
    };
}
};
__modules["core/sessions-compat.js"] = function (require, module, exports) {
"use strict";
// Sessions service shape on supported hosts (>=0.2.2-rc.1): there is no
// `ISessions.open`/`clear` and no `SessionListState.current`; selection lives in
// per-session `retainedBy` counters (dsh-api-session-controller
// `projectList()`; dsh-client-ui-layout reads it as
// `Object.values(byId).find(s => (s.retainedBy.mainView ?? 0) > 0)?.id`).
// The repo type-checks against pinned 0.1.0-rc.6 typings that still describe
// the old shape, so the read stays structural instead of importing types.
Object.defineProperty(exports, "__esModule", { value: true });
exports.DELETE_VERIFY_INTERVAL_MS = exports.DELETE_VERIFY_ATTEMPTS = void 0;
exports.currentSessionIdOf = currentSessionIdOf;
exports.verifySessionDeleted = verifySessionDeleted;
/** The current session id: the main-view-retained session. Undefined when no
 *  session is retained by the main view or the shape is foreign. The rc.2
 *  `current` field read was removed with the host floor (0.2.2 never sets it). */
function currentSessionIdOf(list) {
    if (typeof list !== 'object' || list === null)
        return undefined;
    const snapshot = list;
    for (const key in snapshot.byId) {
        const summary = snapshot.byId[key];
        // for-in guarantees the key exists, not the value — an explicitly
        // undefined property still reaches the guard below.
        if (summary === undefined)
            continue;
        const mainView = summary.retainedBy?.mainView;
        if (typeof mainView === 'number' && mainView > 0 && typeof summary.id === 'string')
            return summary.id;
    }
    return undefined;
}
/** How many times an unanswered delete request is re-checked against the list. */
exports.DELETE_VERIFY_ATTEMPTS = 4;
/** Delay between two re-checks of the session list. */
exports.DELETE_VERIFY_INTERVAL_MS = 350;
/**
 * Decide whether a session delete landed even though no HTTP response arrived.
 *
 * The delete route is the plugin's only REST call, and the host half deployed
 * on DSHA aborts its reply AFTER the handler already moved the session into the
 * trash: the browser rejects the fetch with `TypeError: Failed to fetch`
 * (`net::ERR_EMPTY_RESPONSE`) for a delete that DID happen. Reporting that as a
 * failure is a lie the user has to work around, so the session list — not the
 * fetch promise — is the judge: re-read it a bounded number of times and only
 * conclude failure when the id survives every attempt.
 *
 * @param deps - list accessors plus the injected wait.
 * @returns true as soon as the id is gone, false when it survives the budget.
 */
async function verifySessionDeleted(deps) {
    const attempts = deps.attempts ?? exports.DELETE_VERIFY_ATTEMPTS;
    const intervalMs = deps.intervalMs ?? exports.DELETE_VERIFY_INTERVAL_MS;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
        try {
            await deps.refresh?.();
        }
        catch {
            // A failed refresh is not an answer; the snapshot below still is.
        }
        if (!deps.listed())
            return true;
        if (attempt + 1 < attempts)
            await deps.sleep(intervalMs);
    }
    return !deps.listed();
}
};
__modules["effects/drawer-session-log.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createDrawerSessionLogTask = createDrawerSessionLogTask;
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const locales_ts_1 = require("./i18n/locales.js");
const frame_ts_1 = require("./platform/frame.js");
/**
 * 16px download outline glyph: the host's own `IconDownloadOutlineRegular`
 * artwork (three stroked paths), inlined because this node is built without
 * React (the host icon components are React-only).
 */
const DOWNLOAD_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">'
    + '<path d="M8 1.95317V10.0469" stroke="currentColor" stroke-width="1"/>'
    + '<path d="M4.25 6.29688L8 10.0469L11.75 6.29688" stroke="currentColor" stroke-width="1"/>'
    + '<path d="M1.5 10.0469V13.158C1.5 13.3937 1.60536 13.6198 1.79289 13.7865C1.98043 13.9532 2.23478 14.0469 2.5 14.0469H13.5C13.7652 14.0469 14.0196 13.9532 14.2071 13.7865C14.3946 13.6198 14.5 13.3937 14.5 13.158V10.0469" stroke="currentColor" stroke-width="1"/>'
    + '</svg>';
/** 16px clock glyph for the Downloads row (recent-history semantics). */
const DOWNLOADS_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">'
    + '<circle cx="8" cy="8" r="6.2" stroke="currentColor" stroke-width="1"/>'
    + '<path d="M8 4.6V8l2.2 1.6" stroke="currentColor" stroke-width="1" stroke-linecap="round"/>'
    + '</svg>';
/**
 * The drawer's brand-row download menu (replaces the footer pill; issue #225).
 *
 * The task is scoped to tree changes (`*`) because React may recreate the
 * host's logo row on any sidebar re-render; `ensure()` re-mounts the wrapper
 * idempotently. The button never sits directly in the row (see `mount()`).
 * `dispose()` (mobile breakpoint teardown or task removal) unsubscribes,
 * closes the menu and removes the node.
 *
 * @param ctx - client root context: locale, sessions list and the host's
 *   session-log download service.
 * @returns the reconciler task.
 */
function createDrawerSessionLogTask(ctx) {
    const t = ctx.locale.bind(locales_ts_1.NS);
    const sessionId = () => (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot());
    let wrapper = null;
    let button = null;
    let unsubscribe = null;
    let menu = null;
    let exportItem = null;
    let onDocPointerDown = null;
    let onKeyDown = null;
    let frameObserver = null;
    let onViewportChange = null;
    /** Close the menu and drop every listener it installed. Safe to call twice. */
    const closeMenu = () => {
        menu?.remove();
        menu = null;
        exportItem = null;
        if (onDocPointerDown !== null)
            document.removeEventListener('pointerdown', onDocPointerDown, true);
        onDocPointerDown = null;
        if (onKeyDown !== null)
            document.removeEventListener('keydown', onKeyDown, true);
        onKeyDown = null;
        frameObserver?.disconnect();
        frameObserver = null;
        if (onViewportChange !== null) {
            window.removeEventListener('resize', onViewportChange);
            window.removeEventListener('orientationchange', onViewportChange);
        }
        onViewportChange = null;
        button?.setAttribute('aria-expanded', 'false');
    };
    /** Right-align the menu under the icon; re-run on viewport changes. */
    const placeMenu = () => {
        if (menu === null || button === null)
            return;
        const rect = button.getBoundingClientRect();
        menu.style.top = `${Math.round(rect.bottom + 4)}px`;
        menu.style.right = `${Math.round(window.innerWidth - rect.right)}px`;
    };
    /** One 44px menu row. */
    const makeItem = (label, marker, icon) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.setAttribute('role', 'menuitem');
        item.dataset.mobileNav = marker;
        item.innerHTML = icon;
        const text = document.createElement('span');
        text.textContent = label;
        item.appendChild(text);
        return item;
    };
    const openMenu = () => {
        if (button === null)
            return;
        if (menu !== null) {
            closeMenu();
            return;
        }
        const panel = document.createElement('div');
        panel.dataset.mobileNav = 'session-log-menu';
        panel.setAttribute('role', 'menu');
        panel.setAttribute('aria-label', t('downloadMenu'));
        const exportButton = makeItem(t('sessionLog'), 'session-log-export', DOWNLOAD_SVG);
        exportButton.disabled = sessionId() === undefined;
        exportButton.addEventListener('click', () => {
            const id = sessionId();
            closeMenu();
            if (id === undefined)
                return;
            ctx.sessionLogDownload.download(id);
        });
        panel.appendChild(exportButton);
        exportItem = exportButton;
        const bridge = window.DSHA;
        if (typeof bridge?.openDownloads === 'function') {
            const downloadsButton = makeItem(t('downloads'), 'session-log-downloads', DOWNLOADS_SVG);
            downloadsButton.addEventListener('click', () => {
                const pending = bridge?.openDownloads?.();
                closeMenu();
                if (pending !== undefined)
                    void pending.catch(() => { });
            });
            panel.appendChild(downloadsButton);
        }
        document.body.appendChild(panel);
        menu = panel;
        placeMenu();
        button.setAttribute('aria-expanded', 'true');
        onDocPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Node))
                return;
            if (panel.contains(target))
                return;
            if (button !== null && (target === button || button.contains(target)))
                return;
            closeMenu();
        };
        document.addEventListener('pointerdown', onDocPointerDown, true);
        onKeyDown = (event) => {
            if (event.key !== 'Escape')
                return;
            event.preventDefault();
            closeMenu();
        };
        document.addEventListener('keydown', onKeyDown, true);
        // A drawer close must take the menu with it (it is a body portal and
        // would otherwise be left floating over the conversation).
        const frame = (0, frame_ts_1.getFrame)();
        if (frame !== null) {
            frameObserver = new MutationObserver(() => {
                if (frame.hasAttribute('data-sidebar-collapsed'))
                    closeMenu();
            });
            frameObserver.observe(frame, { attributes: true, attributeFilter: ['data-sidebar-collapsed'] });
        }
        onViewportChange = () => { placeMenu(); };
        window.addEventListener('resize', onViewportChange);
        window.addEventListener('orientationchange', onViewportChange);
        window.setTimeout(() => { exportButton.focus({ preventScroll: true }); }, 0);
    };
    /** Keep the export row's disabled state in step with the current session. */
    const syncDisabled = () => {
        if (exportItem === null)
            return;
        exportItem.disabled = sessionId() === undefined;
    };
    /** The drawer's brand row. Scoped to the sidebar column first: the shell
     *  frame is the mobile branch only, but the column keeps the lookup off any
     *  future logo row outside the drawer. */
    const logoRow = () => {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return null;
        return frame.querySelector('[class*="sidebarCol"] [class*="logoRow"]')
            ?? frame.querySelector('[class*="logoRow"]');
    };
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
    const mount = (row, target) => {
        if (target.parentElement !== row)
            row.appendChild(target);
    };
    return {
        name: 'drawer-session-log',
        scopes: ['*'],
        ensure: () => {
            const row = logoRow();
            if (row === null)
                return;
            if (button === null) {
                wrapper = document.createElement('span');
                wrapper.dataset.mobileNav = 'session-log-wrap';
                button = document.createElement('button');
                button.type = 'button';
                button.dataset.mobileNav = 'session-log';
                button.setAttribute('aria-haspopup', 'menu');
                button.setAttribute('aria-expanded', 'false');
                button.setAttribute('aria-label', t('downloadMenu'));
                button.title = t('downloadMenu');
                button.innerHTML = DOWNLOAD_SVG;
                button.addEventListener('click', openMenu);
                wrapper.appendChild(button);
                unsubscribe = ctx.sessions.list.subscribe(() => { syncDisabled(); });
            }
            if (wrapper !== null)
                mount(row, wrapper);
            syncDisabled();
        },
        dispose: () => {
            closeMenu();
            unsubscribe?.();
            unsubscribe = null;
            wrapper?.remove();
            wrapper = null;
            button = null;
        },
    };
}
};
__modules["effects/drawer-quick-settings.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createDrawerQuickSettingsTask = createDrawerQuickSettingsTask;
const locales_ts_1 = require("./i18n/locales.js");
const frame_ts_1 = require("./platform/frame.js");
/**
 * 17px 设置描边图形：宿主 `IconSettingsOutlineMedium` 的两条 path
 * （`dsh-client-ui-primitives`，`stroke-width: 1.3`），viewBox 仍是 16 格、按 17px 渲染。
 * 2026-10-21 用户「UI太小了!」由 16px 放大到 20px；2026-10-22 用户「还是太大了」按盒子
 * 36→30 同比例缩回 17px（20 × 30/36）。内联因为本节点不经 React 构建。
 */
const SETTINGS_SVG = '<svg width="17" height="17" viewBox="0 0 16 16" fill="none" aria-hidden="true" stroke-width="1.3">'
    + '<path d="M8 9.75012C8.9665 9.75012 9.75 8.96662 9.75 8.00012C9.75 7.03362 8.9665 6.25012 8 6.25012C7.0335 6.25012 6.25 7.03362 6.25 8.00012C6.25 8.96662 7.0335 9.75012 8 9.75012Z" stroke="currentColor"/>'
    + '<path d="M13.0107 7.79377C12.9505 7.89401 12.9205 7.94413 12.9205 7.99951C12.9205 8.0549 12.9505 8.10502 13.0106 8.20528L13.9849 9.83006C14.045 9.93029 14.0751 9.9804 14.0751 10.0358C14.0751 10.0911 14.045 10.1413 13.9849 10.2415L13.0037 11.8777C12.9468 11.9726 12.9184 12.0201 12.8725 12.0461C12.8267 12.072 12.7713 12.072 12.6607 12.072H10.6704C10.5598 12.072 10.5045 12.072 10.4586 12.098C10.4128 12.1239 10.3843 12.1714 10.3274 12.2662L9.33825 13.9142C9.28133 14.009 9.25287 14.0564 9.20703 14.0823C9.16118 14.1083 9.10588 14.1083 8.99529 14.1083H7.00486C6.89426 14.1083 6.83896 14.1083 6.79312 14.0823C6.74727 14.0564 6.71881 14.009 6.6619 13.9142L5.67273 12.2662C5.61581 12.1714 5.58735 12.1239 5.54151 12.098C5.49566 12.072 5.44036 12.072 5.32977 12.072H3.33945C3.2288 12.072 3.17347 12.072 3.12761 12.0461C3.08176 12.0201 3.0533 11.9726 2.9964 11.8777L2.0152 10.2415C1.9551 10.1413 1.92505 10.0911 1.92505 10.0358C1.92505 9.9804 1.9551 9.93029 2.0152 9.83006L2.98951 8.20528C3.04963 8.10502 3.07969 8.0549 3.07969 7.99951C3.07968 7.94413 3.04961 7.89401 2.98946 7.79377L2.01529 6.17011C1.95514 6.06987 1.92507 6.01975 1.92507 5.96437C1.92506 5.90899 1.95512 5.85886 2.01524 5.7586L2.9964 4.1224C3.0533 4.0275 3.08176 3.98005 3.12761 3.95408C3.17347 3.92811 3.2288 3.92811 3.33945 3.92811H5.32977C5.44036 3.92811 5.49566 3.92811 5.54151 3.90216C5.58735 3.87621 5.61581 3.82879 5.67273 3.73397L6.6619 2.08599C6.71881 1.99116 6.74727 1.94375 6.79312 1.9178C6.83896 1.89185 6.89426 1.89185 7.00486 1.89185H8.99529C9.10588 1.89185 9.16118 1.89185 9.20703 1.9178C9.25287 1.94375 9.28133 1.99116 9.33825 2.08599L10.3274 3.73397C10.3843 3.82879 10.4128 3.87621 10.4586 3.90216C10.5045 3.92811 10.5598 3.92811 10.6704 3.92811H12.6607C12.7713 3.92811 12.8267 3.92811 12.8725 3.95408C12.9184 3.98005 12.9468 4.0275 13.0037 4.1224L13.9849 5.7586C14.045 5.85886 14.0751 5.90899 14.0751 5.96437C14.0751 6.01975 14.045 6.06987 13.9849 6.17011L13.0107 7.79377Z" stroke="currentColor" stroke-miterlimit="10"/>'
    + '</svg>';
/**
 * 账户行的锚点：抽屉侧栏列里的 `triggerRow`（settingsArea 里那一行）。
 * 前缀匹配按仓库约定用子串，绝不用属性后缀。
 */
const ROW_SELECTOR = '[class*="sidebarCol"] [class*="triggerRow"]';
/** 账户 launcher 的唯一稳定特征：`aria-haspopup="menu"` 的按钮。 */
const ACCOUNT_TRIGGER_SELECTOR = 'button[aria-haspopup="menu"]';
/**
 * 菜单项还没落地时的收尾时限：超时仍展开就回点 launcher 收起。
 * 400ms 覆盖宿主从 click 到 React 挂菜单的一帧 + 慢机余量。
 */
const MENU_SETTLE_MS = 400;
/**
 * 标记属性：打在**账户菜单**节点上，CSS 借此收起它的第一项（设置）。
 * 值恒为空串；属性名跟 `data-mobile-nav-quick` 同族，不进 `[data-mobile-nav]` 语义。
 */
const MENU_ENTRY_ATTR = 'data-mobile-nav-quick-menu';
/** 已标记菜单的选择器（dispose 与让位时清场用）。 */
const MENU_ENTRY_SELECTOR = '[data-mobile-nav-quick-menu]';
/**
 * 「点下那一刻」就挂上的预标记：让账户菜单从第一帧起就没有被我们收起的那一项。
 * 只在 pointerdown → 菜单被打上正式标记之间存在（另有 PENDING_MS 兜底），
 * 期间任何 `[role="menu"]` 的第一项都不画——这正是「不跳」的代价，窗口只有几帧。
 */
const PENDING_ATTR = 'data-mobile-nav-quick-pending';
/** 预标记最长存活时间：宿主没开菜单（长按、拖走）时不能一直挂着。 */
const PENDING_MS = 700;
/** 齿轮按下态的最短可见时长：短到一次点击也要闪一下，长到能看清。 */
const PRESS_MIN_MS = 140;
/** 齿轮按下态标记（属性值恒为空串，CSS 只看存在性）。 */
const PRESSED_ATTR = 'data-mobile-nav-quick-pressed';
/** 账户行按下态标记（同上，属性值恒为空串）。 */
const ROW_PRESSED_ATTR = 'data-mobile-nav-row-pressed';
/**
 * 抽屉页脚账户行右端的一键设置齿轮。
 *
 * 任务靠 `getFrame()` 只在移动分支存在时注入（桌面帧标记不存在 → 直接 no-op），
 * `scopes: ['*']` 让宿主 React 重建该行后由 `ensure()` 幂等重挂。
 *
 * @param ctx - 客户端根上下文：locale 文案。
 * @returns reconciler 任务。
 */
function createDrawerQuickSettingsTask(ctx) {
    const t = ctx.locale.bind(locales_ts_1.NS);
    let wrap = null;
    let button = null;
    let pending = null;
    const row = () => {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return null;
        return frame.querySelector(ROW_SELECTOR);
    };
    const clearPending = () => {
        if (pending === null)
            return;
        window.clearTimeout(pending);
        pending = null;
    };
    let pendingMark = 0;
    /** 齿轮按下态的计时（保证短按也看得见）。 */
    let pressTimer = 0;
    let pressedAt = 0;
    /** 账户行按下态的计时。 */
    let rowPressTimer = 0;
    let rowPressedAt = 0;
    /** 摘掉 pointerdown 预标记（幂等）。 */
    const clearPendingMark = () => {
        if (pendingMark === 0)
            return;
        window.clearTimeout(pendingMark);
        pendingMark = 0;
        document.documentElement.removeAttribute(PENDING_ATTR);
    };
    /**
     * 点账户行为菜单做准备：在宿主开菜单之前把「第一项不画」挂到 html 上。
     *
     * 不这么做的话，菜单会先按完整三项画出来，等 reconciler 下一拍打上标记再收起
     * 第一项——整张卡片的高度变化就是真机上看到的「弹跳」（2026-11-05）。
     * 只认账户行内的、不是我们齿轮的按下；预标记在菜单被打标记时摘掉，超时兜底。
     */
    /**
     * 账户行的按下态也用显式属性（2026-11-05 真机：点它开菜单时看不到任何反馈，
     * 关掉时才闪一下灰）。成因与齿轮相同——菜单在同一拍挂载，浏览器把 :active 收掉，
     * 0.12s 的淡入一帧都没画出来。这里在 pointerdown 时给行挂 ROW_PRESSED_ATTR，
     * 松开后再留 140ms，CSS 见 base.css 同族规则。
     */
    const pressRow = (on) => {
        const target = row();
        if (target === null)
            return;
        if (on)
            target.setAttribute(ROW_PRESSED_ATTR, '');
        else
            target.removeAttribute(ROW_PRESSED_ATTR);
    };
    const onRowPressEnd = () => {
        if (rowPressedAt === 0)
            return;
        const remain = PRESS_MIN_MS - (performance.now() - rowPressedAt);
        rowPressedAt = 0;
        window.clearTimeout(rowPressTimer);
        if (remain > 0) {
            rowPressTimer = window.setTimeout(() => pressRow(false), remain);
            return;
        }
        pressRow(false);
    };
    const onRowPointerDown = (event) => {
        // 鸭子类型而不是 instanceof Element：跨 realm（iframe / 测试桩）时 instanceof 会假阴性，
        // 而这颗监听只需要 closest/contains 两个能力（同 open-files-panel 的写法）。
        const target = event.target;
        if (target === null || typeof target.closest !== 'function')
            return;
        if (target.closest('[data-mobile-nav="quick-settings"]') !== null)
            return;
        const owner = row();
        if (owner === null || typeof owner.contains !== 'function' || !owner.contains(target))
            return;
        document.documentElement.setAttribute(PENDING_ATTR, '');
        window.clearTimeout(pendingMark);
        pendingMark = window.setTimeout(clearPendingMark, PENDING_MS);
        window.clearTimeout(rowPressTimer);
        rowPressedAt = performance.now();
        pressRow(true);
    };
    /** 预标记与按下态监听只在任务首次 armed 之后挂（构造期不碰 document）。 */
    let rowHookBound = false;
    const bindRowHook = () => {
        if (rowHookBound)
            return;
        rowHookBound = true;
        document.addEventListener('pointerdown', onRowPointerDown, true);
        document.addEventListener('pointerup', onRowPressEnd, true);
        document.addEventListener('pointercancel', onRowPressEnd, true);
    };
    const unbindRowHook = () => {
        if (!rowHookBound)
            return;
        rowHookBound = false;
        document.removeEventListener('pointerdown', onRowPointerDown, true);
        document.removeEventListener('pointerup', onRowPressEnd, true);
        document.removeEventListener('pointercancel', onRowPressEnd, true);
    };
    /**
     * 点击链：账户 launcher → 下一帧 → 菜单第一项（宿主 `AccountMenu` 的 items
     * 顺序固定以 `settings` 开头；它按 `onSelect(id)` 分发，DOM 里没有 id 可读，
     * 所以只能认「第一个 menuitem」）。宿主的菜单是 portal 到 body 的单例列表，
     * 这里取最后一个即刚打开的那一个，并以 launcher 的 `aria-expanded` 复核。
     * 第一项此刻被 `syncMenuEntry` 的标记 + CSS `display: none` 收起了——`click()`
     * 不要求元素可见，链照旧点它；改成「点第一个可见项」会点到 Feedback。
     */
    const selectSettingsItem = (trigger) => {
        const root = document.documentElement;
        root.setAttribute('data-mobile-nav-quick', '');
        let settled = false;
        const settle = () => {
            if (settled)
                return;
            settled = true;
            clearPending();
            root.removeAttribute('data-mobile-nav-quick');
        };
        const expanded = () => trigger.getAttribute('aria-expanded') === 'true';
        if (!expanded())
            trigger.click();
        // 中间帧不闪：标记在场时 CSS 让菜单不可见，`click()` 不要求元素可见。
        requestAnimationFrame(() => {
            const menus = document.querySelectorAll('[role="menu"]');
            const menu = expanded() && menus.length > 0 ? menus[menus.length - 1] : null;
            const item = menu?.querySelector('[role="menuitem"]') ?? null;
            if (item === null) {
                // 菜单没开或没有可选项：把我们可能开出来的菜单收回去，不留屏。
                if (expanded())
                    trigger.click();
                settle();
                return;
            }
            item.click();
            pending = window.setTimeout(() => {
                // 选中后菜单自己会关；仍展开说明宿主结构漂移，回点一次兜底。
                if (expanded())
                    trigger.click();
                settle();
            }, MENU_SETTLE_MS);
        });
    };
    const onClick = () => {
        const target = row();
        if (target === null)
            return;
        const trigger = target.querySelector(ACCOUNT_TRIGGER_SELECTOR);
        if (trigger === null)
            return;
        selectSettingsItem(trigger);
    };
    /**
     * 齿轮的按下态用显式属性，而不是只靠 `:active`（2026-11-05 真机：`:active` 在
     * 这颗按钮上一点都画不出来——它 30px、透明底、又在一颗整行大按钮的同一行里）。
     * pointerdown 立刻挂 `data-mobile-nav-quick-pressed`，松开后再留 PRESS_MIN_MS 才摘，
     * 短促一点击也能看见一眼；指针取消/离开同样收尾。CSS 见 base.css 同族规则。
     */
    const press = (on) => {
        if (button === null)
            return;
        if (on)
            button.setAttribute(PRESSED_ATTR, '');
        else
            button.removeAttribute(PRESSED_ATTR);
    };
    const onPressStart = () => {
        window.clearTimeout(pressTimer);
        press(true);
        pressedAt = performance.now();
    };
    const onPressEnd = () => {
        const remain = PRESS_MIN_MS - (performance.now() - pressedAt);
        window.clearTimeout(pressTimer);
        if (remain > 0) {
            pressTimer = window.setTimeout(() => press(false), remain);
            return;
        }
        press(false);
    };
    const build = () => {
        wrap = document.createElement('span');
        wrap.dataset.mobileNav = 'quick-settings-wrap';
        button = document.createElement('button');
        button.type = 'button';
        button.dataset.mobileNav = 'quick-settings';
        button.setAttribute('aria-label', t('quickSettings'));
        button.setAttribute('aria-haspopup', 'dialog');
        button.title = t('quickSettings');
        button.innerHTML = SETTINGS_SVG;
        button.addEventListener('click', onClick);
        button.addEventListener('pointerdown', onPressStart);
        button.addEventListener('pointerup', onPressEnd);
        button.addEventListener('pointercancel', onPressEnd);
        button.addEventListener('pointerleave', onPressEnd);
        wrap.appendChild(button);
    };
    /**
     * 齿轮此刻在不在场：唯一会隐藏它的是连接条守卫（`drawer-quick-settings-chip-guard`），
     * 条件就是行的**直接孩子**里有连接条（`button[data-phase]` / `[role="status"]`）。
     * 这里镜像同一判据而不是读计算样式——两处口径必须一致，改守卫时一起改。
     */
    const gearAvailable = (target) => ![...target.children].some((child) => child.getAttribute('data-phase') !== null || child.getAttribute('role') === 'status');
    /**
     * 上一次打上正式标记的那个菜单节点。闭态摘标记只碰它，不再为「文档里有没有菜单」
     * 查一遍 `[role="menu"]`——账户菜单关着是绝大多数手势，那次查询完全白付（2026-11-06
     * 性能初审 A）。节点被宿主换掉时这里是已脱离的旧节点，摘属性无副作用；新菜单在下一次
     * 展开的 ensure 里重新查、重新打标记。
     */
    let markedMenu = null;
    /** 摘掉所有菜单上的标记（dispose 时用；这里是清场，扫一次全文档是值得的）。 */
    const clearMenuEntry = () => {
        for (const menu of document.querySelectorAll(MENU_ENTRY_SELECTOR)) {
            menu.removeAttribute(MENU_ENTRY_ATTR);
        }
        markedMenu = null;
    };
    /**
     * 收起账户菜单里的「设置」项（2026-10-21 用户：「把原来的打开方式进行删除，只保留这个」）。
     *
     * 宿主 `AccountMenu` 的 items 数组把 `settings` 写在第一位、DOM 里没有 id 可读，插件也不能
     * 改宿主源码，所以只在**那一个**菜单节点上打标记、由 CSS 收起第一项。菜单 portal 到 body、
     * 与账户行没有结构关系，唯一能认出它的是点击链用的同一判据：账户 launcher 此刻
     * `aria-expanded="true"` → 文档里最后一个 `[role="menu"]` 就是它。
     *
     * 齿轮让位（连接条在场）时不打标记：那段时间账户菜单是设置的唯一入口。
     *
     * 判定顺序按代价排：闭态先看行内 `aria-expanded` 就返回，`[role="menu"]` 只在真要
     * 打标记时才查（2026-11-06 性能初审 A：原来先全文档取菜单、再判开态，菜单关着也付查询）。
     */
    const syncMenuEntry = (target) => {
        const trigger = target.querySelector(ACCOUNT_TRIGGER_SELECTOR);
        const keep = trigger !== null && trigger.getAttribute('aria-expanded') === 'true' && gearAvailable(target);
        if (!keep) {
            // 闭态/让位：只摘自己打过的那一个，保留「标记不残留」的既有语义。
            markedMenu?.removeAttribute(MENU_ENTRY_ATTR);
            markedMenu = null;
            return;
        }
        const menus = document.querySelectorAll('[role="menu"]');
        const menu = menus.length > 0 ? menus[menus.length - 1] ?? null : null;
        if (menu === null)
            return;
        // 幂等：写过的属性不再重写，免得自己的 mutation 把任务再唤醒一轮。
        if (!menu.hasAttribute(MENU_ENTRY_ATTR))
            menu.setAttribute(MENU_ENTRY_ATTR, '');
        markedMenu = menu;
        // 正式标记已到位，预标记可以撤了（CSS 两条并联，撤了也不跳）。
        clearPendingMark();
    };
    return {
        name: 'drawer-quick-settings',
        scopes: ['*'],
        ensure: () => {
            const target = row();
            if (target === null)
                return;
            if (button === null || wrap === null)
                build();
            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();
            syncMenuEntry(target);
        },
        dispose: () => {
            window.clearTimeout(pressTimer);
            window.clearTimeout(rowPressTimer);
            clearPending();
            clearPendingMark();
            unbindRowHook();
            document.documentElement.removeAttribute('data-mobile-nav-quick');
            clearMenuEntry();
            wrap?.remove();
            wrap = null;
            button = null;
        },
    };
}
};
__modules["core/image-zoom-core.js"] = function (require, module, exports) {
"use strict";
// image-zoom-core.ts — DOM-free math for the image lightbox zoom.
//
// Deliberately ZERO imports and no DOM globals: the lightbox effect
// (effects/image-lightbox-zoom.ts) owns the listeners and the CSS transform,
// this module owns only the numbers, so node:test can pin the geometry with
// no browser. Conventions used by every function here:
//  - x/y are CSS translate values in the image's own coordinate space, i.e.
//    the translate3d() the effect writes before scale(s);
//  - the image is rendered with transform-origin 50% 50%, so a point p given
//    relative to the image centre lands at (x + s * p, y + s * p);
//  - an "anchor" is such a point in that same centre-relative space (the
//    effect converts a screen-space midpoint by subtracting the image's
//    untransformed centre before calling zoomAround);
//  - scale is clamped to [MIN_SCALE, MAX_SCALE]; at MIN_SCALE the image is
//    centred again, so no translation may survive.
Object.defineProperty(exports, "__esModule", { value: true });
exports.DOUBLE_TAP_SCALE = exports.MAX_SCALE = exports.MIN_SCALE = void 0;
exports.clampScale = clampScale;
exports.identityZoom = identityZoom;
exports.zoomAround = zoomAround;
exports.clampTranslation = clampTranslation;
exports.pinchFactor = pinchFactor;
exports.doubleTapTarget = doubleTapTarget;
exports.MIN_SCALE = 1;
exports.MAX_SCALE = 4;
exports.DOUBLE_TAP_SCALE = 2.5;
/** Clamp a raw scale factor into the supported range (NaN collapses to 1:1). */
function clampScale(n) {
    if (Number.isNaN(n))
        return exports.MIN_SCALE;
    return Math.min(exports.MAX_SCALE, Math.max(exports.MIN_SCALE, n));
}
/** The neutral 1:1 state. A fresh object every call: callers may hold it. */
function identityZoom() {
    return { scale: exports.MIN_SCALE, x: 0, y: 0 };
}
/**
 * Scale by `factor` while keeping `anchor` (centre-relative, see the header)
 * at the same screen position. The new translation is derived from the fixed
 * point relation x' + s' * p = x + s * p, so no iteration or accumulated
 * drift is involved. Clamping to MIN_SCALE recentres the image; clamping to
 * MAX_SCALE keeps the anchored relation at the clamped scale.
 */
function zoomAround(state, factor, anchor) {
    if (!Number.isFinite(factor) || factor <= 0)
        return { ...state };
    if (!(state.scale > 0))
        return identityZoom();
    const nextScale = clampScale(state.scale * factor);
    if (nextScale === exports.MIN_SCALE)
        return identityZoom();
    const ratio = nextScale / state.scale;
    return {
        scale: nextScale,
        x: anchor.x - (anchor.x - state.x) * ratio,
        y: anchor.y - (anchor.y - state.y) * ratio,
    };
}
/**
 * Keep a zoomed image from being dragged off its viewport. The allowance per
 * axis is half of the overflow the current scale creates, so the image edge
 * can reach the viewport edge but never pass it. A 1:1 image cannot move at
 * all. `viewport` is the visible box the image is allowed to travel within
 * (the effect passes the visible viewport size, not a cached box).
 */
function clampTranslation(state, viewport) {
    const scale = clampScale(state.scale);
    if (scale === exports.MIN_SCALE)
        return identityZoom();
    const width = Number.isFinite(viewport.width) && viewport.width > 0 ? viewport.width : 0;
    const height = Number.isFinite(viewport.height) && viewport.height > 0 ? viewport.height : 0;
    const boundX = ((scale - 1) * width) / 2;
    const boundY = ((scale - 1) * height) / 2;
    const x = Number.isFinite(state.x) ? Math.min(boundX, Math.max(-boundX, state.x)) : 0;
    const y = Number.isFinite(state.y) ? Math.min(boundY, Math.max(-boundY, state.y)) : 0;
    return { scale, x, y };
}
/**
 * Ratio between two pinch spans, each given as the vector between the two
 * touches (p2 - p1). A degenerate previous span (both fingers on one point)
 * returns 1 instead of dividing by zero.
 */
function pinchFactor(prev, next) {
    const from = Math.hypot(prev.x, prev.y);
    const to = Math.hypot(next.x, next.y);
    if (from === 0)
        return 1;
    if (!Number.isFinite(from) || !Number.isFinite(to))
        return 1;
    return to / from;
}
/** The double-tap step: 1:1 zooms to DOUBLE_TAP_SCALE, anything else resets. */
function doubleTapTarget(state) {
    return state.scale <= exports.MIN_SCALE ? { scale: exports.DOUBLE_TAP_SCALE, x: 0, y: 0 } : identityZoom();
}
};
__modules["effects/image-lightbox-zoom.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createImageLightboxZoomTask = createImageLightboxZoomTask;
const image_zoom_core_ts_1 = require("./core/image-zoom-core.js");
/**
 * Image lightbox zoom (dsh-client-ui-primitives ImageLightbox).
 *
 * Background: the host lightbox (a body-portal div[role=dialog][aria-modal]
 * whose stage holds an img + a close button) ships no zoom at all, while the
 * page-level touch-action in layout.css.ts grants pinch-zoom to the document.
 * So a two-finger gesture over the image used to zoom the whole WebView. This
 * effect gives the IMAGE its own transform and keeps the browser gesture out
 * of the lightbox layer:
 *  - the dialog root carries data-mobile-nav="image-lightbox" while open;
 *  - only the img gets translate3d(x,y,0) scale(s) (never zoom:, never the
 *    standalone scale property), with transform-origin 50% 50%;
 *  - pointer events, gesturestart and touchmove are attached to the dialog
 *    root ONLY. Never document: cdp-zoom-probe A5 dispatches gesturestart on
 *    document and the #45 contract requires it not to be prevented; a
 *    document-level listener here would turn that probe red and kill page
 *    pinch on every other surface.
 * The math lives in core/image-zoom-core.ts (DOM-free, unit-tested); this
 * module owns listeners and the transform string only.
 *
 * Lifecycle: a ReconcilerTask (scopes ['*']) so the full-tree observer wakes
 * it when the portal mounts/unmounts and when the mobile breakpoint
 * deactivates. Everything it creates lives in this factory closure, so the
 * dispose() path strips every listener, attribute and inline style with no
 * module-level state left behind. A host generation that changes the portal
 * shape simply stops being detected (lazy deactivation) instead of throwing.
 */
function createImageLightboxZoomTask() {
    let root = null;
    let image = null;
    let zoom = (0, image_zoom_core_ts_1.identityZoom)();
    const pointers = new Map();
    let pan = null;
    let pinch = null;
    let lastTap = null;
    const viewport = () => ({
        width: window.innerWidth,
        height: window.innerHeight,
    });
    /** The portal root this effect recognises: a body child dialog whose stage
     *  holds the preview image as a direct child. Settings and other modals are
     *  direct body children too, hence the img shape test rather than "first". */
    const findLightbox = () => {
        for (const candidate of document.body.querySelectorAll(':scope > [role="dialog"][aria-modal="true"]')) {
            if (!(candidate instanceof HTMLElement))
                continue;
            const found = candidate.querySelector(':scope > :first-child > img');
            if (found instanceof HTMLImageElement)
                return { root: candidate, image: found };
        }
        return null;
    };
    const clearImage = () => {
        if (image === null)
            return;
        image.style.transform = '';
        image.style.transformOrigin = '';
        image.style.willChange = '';
        image = null;
    };
    const detach = () => {
        if (root !== null) {
            root.removeEventListener('pointerdown', onPointerDown);
            root.removeEventListener('pointermove', onPointerMove);
            root.removeEventListener('pointerup', onPointerUp);
            root.removeEventListener('pointercancel', onPointerCancel);
            root.removeEventListener('gesturestart', onGestureStart);
            root.removeEventListener('touchmove', onTouchMove);
            if (root.getAttribute('data-mobile-nav') === 'image-lightbox') {
                root.removeAttribute('data-mobile-nav');
            }
        }
        clearImage();
        pointers.clear();
        pan = null;
        pinch = null;
        lastTap = null;
        zoom = (0, image_zoom_core_ts_1.identityZoom)();
        root = null;
    };
    /** untransformed image centre in viewport coordinates (the transform's
     *  scale is about the centre, so only the translate has to be undone). */
    const baseCentre = () => {
        if (image === null)
            return { x: 0, y: 0 };
        const rect = image.getBoundingClientRect();
        return { x: rect.left + rect.width / 2 - zoom.x, y: rect.top + rect.height / 2 - zoom.y };
    };
    const paint = () => {
        if (image === null)
            return;
        image.style.transform = 'translate3d(' + zoom.x + 'px, ' + zoom.y + 'px, 0) scale(' + zoom.scale + ')';
    };
    const startPinch = () => {
        const points = [...pointers.values()];
        const first = points[0];
        const second = points[1];
        if (first === undefined || second === undefined)
            return;
        const startMid = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
        const centre = baseCentre();
        pinch = {
            startSpan: { x: second.x - first.x, y: second.y - first.y },
            startMid,
            startZoom: { ...zoom },
            anchor: { x: startMid.x - centre.x, y: startMid.y - centre.y },
        };
        pan = null;
    };
    const onPointerDown = (event) => {
        if (event.pointerType === 'mouse')
            return;
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pointers.size >= 2) {
            startPinch();
            return;
        }
        pan = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            originX: zoom.x,
            originY: zoom.y,
            moved: false,
            startedOnImage: event.target === image,
        };
    };
    const onPointerMove = (event) => {
        if (!pointers.has(event.pointerId))
            return;
        const point = { x: event.clientX, y: event.clientY };
        pointers.set(event.pointerId, point);
        if (pointers.size >= 2) {
            if (pinch === null) {
                startPinch();
                return;
            }
            const points = [...pointers.values()];
            const first = points[0];
            const second = points[1];
            if (first === undefined || second === undefined)
                return;
            const mid = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
            const factor = (0, image_zoom_core_ts_1.pinchFactor)(pinch.startSpan, { x: second.x - first.x, y: second.y - first.y });
            const scaled = (0, image_zoom_core_ts_1.zoomAround)(pinch.startZoom, factor, pinch.anchor);
            zoom = (0, image_zoom_core_ts_1.clampTranslation)({
                scale: scaled.scale,
                x: scaled.x + (mid.x - pinch.startMid.x),
                y: scaled.y + (mid.y - pinch.startMid.y),
            }, viewport());
            paint();
            return;
        }
        if (pan === null || pan.pointerId !== event.pointerId || !pan.startedOnImage)
            return;
        const dx = point.x - pan.startX;
        const dy = point.y - pan.startY;
        if (Math.abs(dx) > 4 || Math.abs(dy) > 4)
            pan.moved = true;
        zoom = (0, image_zoom_core_ts_1.clampTranslation)({ scale: zoom.scale, x: pan.originX + dx, y: pan.originY + dy }, viewport());
        paint();
    };
    const onPointerUp = (event) => {
        if (!pointers.delete(event.pointerId))
            return;
        if (pointers.size >= 2) {
            startPinch();
            return;
        }
        if (pointers.size === 1) {
            // One finger lifted first: the remaining finger keeps panning from the
            // current zoom instead of jumping back to its pre-pinch origin.
            const remaining = [...pointers.entries()][0];
            pinch = null;
            pan = null;
            if (remaining !== undefined) {
                const [id, point] = remaining;
                pan = {
                    pointerId: id,
                    startX: point.x,
                    startY: point.y,
                    originX: zoom.x,
                    originY: zoom.y,
                    moved: true,
                    startedOnImage: true,
                };
            }
            return;
        }
        const tap = pan;
        pan = null;
        pinch = null;
        if (tap === null || tap.pointerId !== event.pointerId || !tap.startedOnImage || tap.moved) {
            lastTap = null;
            return;
        }
        const at = Date.now();
        if (lastTap !== null
            && at - lastTap.at <= 320
            && Math.hypot(event.clientX - lastTap.x, event.clientY - lastTap.y) <= 28) {
            zoom = (0, image_zoom_core_ts_1.clampTranslation)((0, image_zoom_core_ts_1.doubleTapTarget)(zoom), viewport());
            lastTap = null;
            paint();
            return;
        }
        lastTap = { at, x: event.clientX, y: event.clientY };
    };
    const onPointerCancel = (event) => {
        pointers.delete(event.pointerId);
        if (pointers.size < 2)
            pinch = null;
        if (pointers.size === 0) {
            pan = null;
            lastTap = null;
        }
    };
    // iOS gesture events bubble from the touched element; keeping this on the
    // dialog root stops the WebView's own pinch but leaves every other surface
    // (and the document-level #45 contract) untouched.
    const onGestureStart = (event) => {
        event.preventDefault();
    };
    // Pointer-events belt and braces: some WebViews still deliver TouchEvents
    // first. Two touches on the lightbox are always the image pinch, never a
    // page pinch, so the fallback cancels the default only then.
    const onTouchMove = (event) => {
        if (event.touches.length > 1)
            event.preventDefault();
    };
    const attach = (nextRoot, nextImage) => {
        root = nextRoot;
        image = nextImage;
        zoom = (0, image_zoom_core_ts_1.identityZoom)();
        pointers.clear();
        pan = null;
        pinch = null;
        lastTap = null;
        root.setAttribute('data-mobile-nav', 'image-lightbox');
        image.style.transformOrigin = '50% 50%';
        image.style.willChange = 'transform';
        root.addEventListener('pointerdown', onPointerDown);
        root.addEventListener('pointermove', onPointerMove);
        root.addEventListener('pointerup', onPointerUp);
        root.addEventListener('pointercancel', onPointerCancel);
        root.addEventListener('gesturestart', onGestureStart);
        root.addEventListener('touchmove', onTouchMove, { passive: false });
    };
    return {
        name: 'image-lightbox-zoom',
        scopes: ['*'],
        ensure: () => {
            const found = findLightbox();
            if (found === null) {
                detach();
                return;
            }
            if (root === found.root) {
                if (image !== found.image) {
                    clearImage();
                    image = found.image;
                    zoom = (0, image_zoom_core_ts_1.identityZoom)();
                    image.style.transformOrigin = '50% 50%';
                    image.style.willChange = 'transform';
                }
                return;
            }
            detach();
            attach(found.root, found.image);
        },
        dispose: () => {
            detach();
        },
    };
}
};
__modules["effects/gesture-guard.js"] = function (require, module, exports) {
"use strict";
/**
 * Gesture-consumption contract between the sidebar swipe layer and every
 * other document-level listener that would otherwise treat the release as a
 * plain tap (the overlay's drawer-close click/pointerup handlers and the
 * FAB / backdrop element listeners).
 *
 * Two independent signals, because they answer questions at different times:
 * - the axis-lock flag (`markStrokeLocked` at tryLock, i.e. during
 *   pointermove) tells a host handler running EARLIER in the same release
 *   event's capture phase that this pointerup is a swipe release, not a tap;
 * - the consume marks (`markGestureConsumed` at the gesture layer's own
 *   pointerup, after classification) cover the events that come AFTER the
 *   release.
 *
 * When the gesture layer classifies a stroke as a real swipe it calls
 * `markGestureConsumed(target, windowMs, upTo)`; any later listener that
 * calls `consumeIfGestured(event)` on the same stroke returns true and bails
 * out, so a swipe can never toggle the drawer twice or navigate a session
 * row — including the synthetic click the browser dispatches after the
 * stroke (its target is the release point or an ancestor of it, which is why
 * the mark walks the ancestor chain up to `upTo`).
 *
 * Non-gesture taps leave both signals clear, so the host's own close / tap /
 * nav-arm logic keeps working untouched.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.markStrokeLocked = markStrokeLocked;
exports.clearStrokeLocked = clearStrokeLocked;
exports.isStrokeLocked = isStrokeLocked;
exports.markGestureConsumed = markGestureConsumed;
exports.consumeIfGestured = consumeIfGestured;
exports.isGestureConsumed = isGestureConsumed;
/** Marked targets with their expiry timestamp (monotonic performance.now). */
const consumed = new WeakMap();
/**
 * True while the live stroke is axis-locked horizontal. Unlike the consume
 * marks (written at the gesture layer's OWN pointerup, after
 * classification), this flag is written at tryLock time — during
 * pointermove, strictly before any pointerup can fire — so a host handler
 * registered earlier in the capture phase can consult it on the same
 * release event without losing the race (audit S0/S1, 2026-08-27): while
 * the flag is up, the pointerup it is seeing is a swipe release, never a
 * tap, classified or not.
 */
let strokeLocked = false;
/** Flag the live stroke as axis-locked horizontal (called by tryLock). */
function markStrokeLocked() {
    strokeLocked = true;
}
/** Clear the axis-lock flag (called by reset and on a new pointer epoch). */
function clearStrokeLocked() {
    strokeLocked = false;
}
/** True while a stroke is axis-locked horizontal (host handlers yield). */
function isStrokeLocked() {
    return strokeLocked;
}
/**
 * True when the value looks like a DOM node that can carry an ancestor
 * chain. Feature-detected (no `instanceof Element`) so the guard stays
 * importable and testable in non-DOM environments (node:test).
 */
function isElementLike(value) {
    return (typeof value === 'object' &&
        value !== null &&
        'parentElement' in value &&
        value.parentElement !== undefined);
}
/**
 * Register that the stroke ending on `target` is a gesture. The mark covers
 * `target` itself and every ancestor up to and including `upTo` (when given
 * and present in the chain), so a follow-up synthetic click — whose target
 * is usually an ancestor of the release point — is reported as consumed
 * too. Multiple marks accumulate independently and expire after `windowMs`.
 */
function markGestureConsumed(target, windowMs, upTo) {
    const until = performance.now() + windowMs;
    if (!isElementLike(target)) {
        if ((typeof target !== 'object' && typeof target !== 'function') || target === null)
            return;
        consumed.set(target, until);
        return;
    }
    let el = target;
    while (el !== null) {
        consumed.set(el, until);
        if (el === upTo)
            break;
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
}
/**
 * True when the event belongs to a stroke already marked as a gesture.
 * Matches the event target itself or any of its ancestors. Stale marks are
 * dropped lazily.
 */
function consumeIfGestured(event) {
    const now = performance.now();
    const target = event.target;
    if (!isElementLike(target)) {
        if ((typeof target !== 'object' && typeof target !== 'function') || target === null)
            return false;
        const until = consumed.get(target);
        if (until === undefined)
            return false;
        if (until <= now) {
            consumed.delete(target);
            return false;
        }
        return true;
    }
    let el = target;
    while (el !== null) {
        const until = consumed.get(el);
        if (until !== undefined) {
            if (until <= now) {
                consumed.delete(el);
            }
            else {
                return true;
            }
        }
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
    return false;
}
/**
 * Test-only probe: true when a gesture mark is still live for the given
 * element (tests/sidebar-swipe.test.ts asserts expiry with it; production
 * code never calls it — the swipe layer gates on its own consumedEl
 * instead). Returns false for stale marks.
 */
function isGestureConsumed(target) {
    const until = consumed.get(target);
    if (until === undefined)
        return false;
    if (until <= performance.now()) {
        consumed.delete(target);
        return false;
    }
    return true;
}
};
__modules["effects/session-row-fiber.js"] = function (require, module, exports) {
"use strict";
// session-row-fiber.ts — DOM-free core behind the drawer session-row tap
// fallback (#49 / PR #49): on some WebKit/iOS builds a tap on a session row
// never produces a `click`, so the row's own React onClick never runs and
// navigation silently does nothing. The runtime half resolves the tapped
// element's fiber with `reactFiberOf`, walks it with `findSessionIdInFiber`,
// and hands the id to the host. This file is the PURE half, so the walk is
// unit-testable with no DOM, no renderer and no DSH runtime.
//
// Deliberately has ZERO import statements, like reconciler-core.ts: node:test
// loads it directly through Node's native type stripping, and the client bundle
// has nothing to resolve. Nothing here touches `document` or `window`.
//
// Two measured facts from the live 0.1.5-rc.2 host shaped the walk:
//  - the row ITEM fiber is 3 hops above the tapped element and carries the
//    session id at `props.node.id`;
//  - hop 32 is a `ScopeProvider` ancestor whose `props.scope` is the literal
//    string 'session-maybe'. Anything trusting the SHAPE of an id
//    (/^session[-_]/) would hand that string to `ctx.sessions.open(id)`, and
//    that contract fails loud on unknown ids. So a value counts only when the
//    caller's `isKnownId` accepts it — there is no shape-based fallback, and a
//    "plausible looking id" must never be guessed.
Object.defineProperty(exports, "__esModule", { value: true });
exports.FIBER_WALK_LIMIT = void 0;
exports.findSessionIdInFiber = findSessionIdInFiber;
exports.reactFiberOf = reactFiberOf;
exports.isTapWithinSlop = isTapWithinSlop;
/**
 * Hop budget for one walk. The measured row item fiber is 3 hops up and the
 * whole chain is well under this; the bound exists so an unexpectedly deep or
 * malformed chain cannot spin.
 */
exports.FIBER_WALK_LIMIT = 60;
/** Props keys holding an OBJECT with an `.id` (row items, session records). */
const OBJECT_KEYS = ['node', 'session', 'summary', 'result'];
/** Props keys holding the id STRING directly. */
const ID_KEYS = ['sessionId', 'id'];
/** React stamps a fiber on a node under a per-renderer random suffix. */
const FIBER_KEY_PREFIXES = ['__reactFiber$', '__reactInternalInstance$'];
/** `value.id` when `value` is an object carrying a string id, else null. */
function objectIdOf(value) {
    if (typeof value !== 'object' || value === null)
        return null;
    const id = value.id;
    return typeof id === 'string' ? id : null;
}
/** The first id this hop's props offer that `isKnownId` accepts. */
function acceptedIdInProps(props, isKnownId) {
    if (props === null || props === undefined)
        return null;
    for (const key of OBJECT_KEYS) {
        const candidate = objectIdOf(props[key]);
        if (candidate !== null && isKnownId(candidate))
            return candidate;
    }
    for (const key of ID_KEYS) {
        const candidate = props[key];
        if (typeof candidate === 'string' && isKnownId(candidate))
            return candidate;
    }
    return null;
}
/**
 * Walk from `fiber` towards the root (`.return`) and return the session id of
 * the NEAREST hop offering one the caller knows. A hop whose candidate is
 * rejected does not stop the walk, so an outer row item fiber still wins over
 * an inner fiber carrying an unrelated or stale id. Returns null when nothing
 * within `limit` hops (the starting fiber counts as the first) is accepted.
 */
function findSessionIdInFiber(fiber, isKnownId, limit = exports.FIBER_WALK_LIMIT) {
    let hop = fiber;
    for (let walked = 0; walked < limit && hop !== null && hop !== undefined; walked += 1) {
        const found = acceptedIdInProps(hop.memoizedProps, isKnownId);
        if (found !== null)
            return found;
        hop = hop.return;
    }
    return null;
}
/**
 * The React fiber a DOM node (or any renderer-stamped object) carries: React
 * assigns it under `__reactFiber$<rendererKey>` and keeps the legacy
 * `__reactInternalInstance$<rendererKey>` alias. First stamp wins; null when
 * the value is not a stamped object.
 */
function reactFiberOf(instance) {
    if (instance === null || instance === undefined)
        return null;
    if (typeof instance !== 'object')
        return null;
    const record = instance;
    for (const prefix of FIBER_KEY_PREFIXES) {
        for (const key of Object.keys(record)) {
            if (!key.startsWith(prefix))
                continue;
            const fiber = record[key];
            if (typeof fiber === 'object' && fiber !== null)
                return fiber;
        }
    }
    return null;
}
/**
 * Whether a pointer release still counts as a tap: it stayed within `slopPx` on
 * BOTH axes (max-norm, not Euclidean). The drawer list scrolls vertically, so a
 * 60px vertical drift must not navigate.
 */
function isTapWithinSlop(from, to, slopPx) {
    return Math.abs(to.x - from.x) <= slopPx && Math.abs(to.y - from.y) <= slopPx;
}
};
__modules["effects/sidebar-swipe.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.classifySwipe = classifySwipe;
exports.classifyFilesSwipe = classifyFilesSwipe;
exports.slidingVelocity = slidingVelocity;
exports.openStateStartMode = openStateStartMode;
exports.closedStateStrokeMode = closedStateStrokeMode;
exports.followTranslate = followTranslate;
exports.followOpenTransform = followOpenTransform;
exports.findHorizontalScroller = findHorizontalScroller;
exports.filesPanelOpen = filesPanelOpen;
exports.selectionOwnsStroke = selectionOwnsStroke;
exports.findFloatingWidget = findFloatingWidget;
exports.closeDrawerAnimated = closeDrawerAnimated;
exports.installSidebarSwipe = installSidebarSwipe;
const frame_ts_1 = require("./platform/frame.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const drawer_open_ts_1 = require("./platform/drawer-open.js");
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const drawer_layer_ts_1 = require("./effects/drawer-layer.js");
/**
 * Sidebar drawer swipe gestures (B 档 hybrid follow, per the 2026-08-29
 * controlled upgrade of docs/specs/2026-08-27-sidebar-swipe-gestures.md).
 *
 * Three gestures:
 * - edge swipe-in: the pointer goes down anywhere in the frame and drags
 *   rightward (LTR) while the drawer is closed → the host state is flipped AT
 *   AXIS-LOCK (early commit) while the drawer is pinned in its closed slot,
 *   so the REAL open subtree mounts off-screen and then follows the finger
 *   out of the slot (see startFollow for why the flip has to come first);
 * - content swipe-toward-slot: the pointer goes down inside the open drawer
 *   and drags LEFT (LTR) → the drawer FOLLOWS the finger (inline translateX,
 *   transition:none) and releases into the host's transition;
 * - content swipe-out (legacy): drag RIGHT inside the open drawer → no
 *   follow (A 档 semantics preserved verbatim), release classifies.
 *
 * The release decision is UNCHANGED from A 档: classifySwipe (distance ratio
 * OR recent-window velocity) — after a follow stroke, dx IS the followed
 * position, so the same function decides complete vs spring-back. The commit
 * is still just `ctx.layout.toggleSidebar()`. The follow mechanics ride the
 * host transition instead of fighting it: during the stroke the drawer gets
 * inline `transition: none` + translateX; on release the inline styles are
 * dropped and the commit retargets the host transition IN THE SAME TASK (no
 * paint in between), so the drawer animates from the finger position to the
 * final state with zero custom animation code.
 *
 * Review constraints honored (spec 2026-08-27 second review): the backdrop
 * stays binary (appears at commit — never opacity-followed, 缺陷 2); a modal
 * rising mid-stroke reverts the drawer every move event (缺陷 1's per-frame
 * guard); the OPEN final state must end with transform:none (the containing
 * block invariant for fixed descendants) — a transitionend-free cleanup pair
 * (inline clear + host value) guarantees it because the host open rule is
 * transform:none. No gesture-layer DOM, no setPointerCapture. Zero transform
 * writes remain true for the LEGACY rightward-close path.
 *
 * Coexistence with the host's overlay interactions (document capture click /
 * pointerup) is two-layered via gesture-guard.ts: (1) tryLock publishes an
 * axis-lock flag the instant the stroke locks horizontal — during
 * pointermove, strictly before any pointerup — and the host handlers yield
 * on it first, because they are registered EARLIER and the post-release
 * consume marks do not exist yet on the stroke's own release event (audit
 * S0: the host toggled first and the gesture toggled back, net zero);
 * (2) a classified swipe additionally marks its target chain consumed so
 * the synthetic click after the stroke can never toggle twice or navigate
 * a row.
 */
/**
 * Axis-lock threshold: once the stroke's dominant axis has moved this far,
 * the axis is decided. Horizontal-dominant (|dx| > |dy|) locks the stroke
 * to X (a swipe); vertical-dominant abandons it to native scrolling.
 * Replaces the old 4px slop + 1.5× direction-bias pair — a 1.5× bias
 * rejected natural ~45° diagonal swipes (the other half of the
 * "识别成滚动" report). MUI uses a 3px uncertainty threshold; 8px is a
 * comfortable margin against tap jitter while still deciding in the first
 * ~16ms of movement.
 */
const LOCK_PX = 8;
/** Distance thresholds as a fraction of the viewport width.
 *  Second tuning pass (2026-08-27, "识别成滚动" feedback): 0.16 open = ~62px
 *  on a 390px phone, 0.13 close = ~51px. Keep the open threshold above the
 *  close threshold so an accidental reverse swipe cannot re-open. */
const OPEN_DISTANCE_RATIO = 0.16;
const CLOSE_DISTANCE_RATIO = 0.13;
/** Velocity window: most-recent-60ms instantaneous speed (end-segment slope). */
const VELOCITY_WINDOW_MS = 60;
/** px/ms speed thresholds for open / close (MUI uses 0.45). */
const OPEN_VELOCITY = 0.45;
const CLOSE_VELOCITY = 0.45;
/** Covers the .28s CSS transition; prevents reverse-gesture double-toggles. */
const COOLDOWN_MS = 350;
/** How long a consumed gesture mark stays live (covers the synthetic click).
 * Short by design: browsers dispatch the synthetic click within tens of ms,
 * while iOS shells suppress it entirely — a long window with no delivery
 * would let the marks swallow the user's next genuine tap (dead-tap bug).
 * When upTo is absent from the release chain (edge swipe-in releases over
 * the main content) the mark walk reaches the document root, so this short
 * window is also the bound on how long any tap can be suppressed. */
const CONSUME_WINDOW_MS = 300;
/**
 * Rightward travel (from the stroke start) that arms the OPEN follow, i.e.
 * flips the host state early so the real drawer subtree mounts. Slightly
 * above LOCK_PX so an 8px horizontal twitch during the first move does not
 * mount-and-unmount 389 nodes; small enough that the dead zone before the
 * drawer's edge appears is imperceptible.
 */
/** The open follow arms at the AXIS LOCK itself: tryLock already demanded
 * 8px of horizontal-dominant travel, so no extra twitch margin is needed —
 * every pixel between lock and arm was dead drag (user report 2026-08-29
 * 「右滑的过程中最开始有真空期,有一段卡的地方」). The release verdict still
 * decides the outcome, so arming early cannot commit a false open. */
const OPEN_FOLLOW_ARM_PX = 8;
/**
 * The host's closed-slot offset as a PERCENTAGE of the drawer's own width
 * (`transform: translateX(-110%)` — the 10% overshoot hides the drawer's
 * shadow). Percentages are load-bearing for the open follow: the element
 * width changes mid-stroke when React swaps the collapsed rail for the real
 * drawer, and a percentage re-resolves against the current width while a
 * cached px value would not.
 */
const CLOSED_SLOT_PCT = 110;
/** Duration of the self-run terminal close animation. Matches the host's
 * .28s drawer transition so the handoff feels identical. */
const COMMIT_ANIM_MS = 280;
/** Percentage baseline of the OPEN-direction follow. The host's closed slot
 * is -110%, but following from -110% hides the first 28px of travel (the
 * 10% overshoot of the 280px drawer): the drawer stayed invisible until
 * ~dx=28 — user report 「刚开始会卡一下，之后才会拖出来」(measured: first
 * paint at dx=12 was left=-296, edge reached the viewport only at dx=28).
 * 101% keeps a small hidden margin (subpixel safety, would-be sliver at
 * exactly -100%) so the drawer edge answers the finger right after the
 * axis lock: at the 8px arm the edge is already ~5px on-screen (-102% left
 * only 2.4px and read as a vacuum; -110% hid the first 28px entirely).
 * The closed slot itself is only ever needed at TERMINAL states,
 * where CLOSED_SLOT_PCT is used verbatim. */
const OPEN_FOLLOW_BASE_PCT = 101;
/**
 * Files-panel (host right sidebar) gesture constants (spec
 * 2026-09-13-files-swipe-gesture-design.md).
 *
 * The right-edge START ZONE is gone with issue #268: the panel is full-screen
 * and opens/closes from anywhere, so there is no geometric strip left to
 * describe.
 */
/**
 * Distance threshold as a fraction of the viewport width for BOTH files
 * directions (≈62px on a 390px phone). The drawer's separate close ratio
 * (0.13) exists because the close stroke follows into its slot; files
 * strokes have no follow, so one ratio serves both directions.
 */
const FILES_DISTANCE_RATIO = 0.16;
/** px/ms velocity threshold for both files directions (drawer parity). */
const FILES_VELOCITY = 0.45;
/** Pointer id we are tracking (multi-touch is ignored). */
let trackingPointer = 0;
/** True once the stroke is axis-locked (direction bias passed). */
let tracking = false;
/** Stroke samples (x + timestamp) for the recent-window velocity. */
let samples = [];
/** Stroke origin (for the direction-bias check). */
let startX = 0;
let startY = 0;
/** Drawer visibility at lock time. */
let lockDrawerOpen = false;
/** Expiry of the post-release cooldown (performance.now()). */
let cooldownUntil = 0;
/** Element whose stroke was marked consumed (null = no live mark). */
let consumedEl = null;
/** B 档 follow state — one cache per stroke, set once at lock time so the
 * per-move writes never read layout (the spec review's rAF-contention
 * constraint). followDrawer stays bound for the whole stroke so a
 * released-then-re-engaged stroke (direction wobble) reuses the cache. */
let followDrawer = null;
let followEngaged = false;
let strokeClosedTx = 0;
let strokeRtl = false;
/** True while an OPEN stroke has early-committed the host state (the drawer
 * subtree is mounted but pinned in its slot, following the finger). The
 * release must then either keep it open or toggle it back. */
let openFollowArmed = false;
/** True once an open stroke has decided NOT to arm the follow (aborted arm:
 * a modal/takeover veto, a missing drawer) so it never retries mid-stroke. */
let openFollowRefused = false;
/**
 * Which gesture family owns the current stroke: 'drawer' = the sidebar
 * gestures (every pre-existing behavior, untouched); 'files' = the
 * right-edge files-panel gesture (no follow painting, host-panel commit).
 * Written by beginStroke only; a fresh beginStroke always routes it.
 */
let strokeMode = 'drawer';
/** Files-panel visibility at lock time (the mirror of lockDrawerOpen). */
let lockFilesOpen = false;
/**
 * The files-panel toggle injected at install (openFilesPanel: it toggles by
 * the host's own control state, so open and close share one function).
 * Module-level because endStroke is a module-level function; the default is
 * a no-op so the node:test suite can import the module without a DOM.
 */
let filesToggleFn = () => false;
/**
 * Pure decision: what does this stroke do, given the drawer state?
 * `dx`/`dy` are raw pointer deltas (RTL mirrors X through `rtl`), `velX` is
 * the raw recent-window X velocity. The stroke must be locked horizontal
 * (|dx| > |dy| and past the lock slop) and direction-consistent; then
 * distance OR velocity wins, with the drawer-state-specific threshold.
 */
function classifySwipe(t, m, rtl) {
    // RTL mirrors the X axis: a rightward stroke (positive dx in LTR) is
    // leftward in RTL. Normalize to the logical direction before judging.
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    if (t.drawerOpen) {
        // BOTH horizontal directions close (2026-08-29 sixth round, user report
        // 「根本没法左滑关闭」). Leftward is the natural "push it back into its
        // slot" gesture — and the only one the follow animation actually paints
        // (followTranslate's close branch follows leftward), so refusing it made
        // the drawer track the finger and then spring back, i.e. the animation
        // promised a close the classifier would not honor. Rightward stays
        // accepted verbatim: four tuning rounds of muscle memory ride on it and
        // failure scenarios B0/B1/B2 assert it. Nothing else competes for a
        // horizontal stroke while the drawer is open, so accepting both costs no
        // ambiguity.
        const travel = Math.abs(dx);
        if (travel / t.viewportWidthPx >= t.closeDistanceRatio)
            return 'close';
        const velX = rtl ? -m.velX : m.velX;
        // A fling only counts when it agrees with the stroke's own direction
        // (same contradiction guard the open branch applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return Math.abs(velX) >= t.closeVelocity ? 'close' : 'none';
    }
    if (dx <= 0)
        return 'none';
    if (dx / t.viewportWidthPx >= t.openDistanceRatio)
        return 'open';
    const velX = rtl ? -m.velX : m.velX;
    return velX >= t.openVelocity ? 'open' : 'none';
}
/**
 * Pure decision for the FILES gesture (right-edge zone), the mirror twin of
 * classifySwipe. RTL mirrors the X axis exactly like classifySwipe. The
 * verdict space extends the drawer's with `files` (the files-panel commit:
 * open the panel on a leftward stroke when everything is closed, close it on
 * a rightward stroke when it is open):
 * - leftward-logical strokes only ever mean "open the panel" and fire ONLY
 *   when panel and drawer are BOTH closed — the panel would mount under the
 *   open drawer (z-1100) and be invisible, so the stroke is 'none' (the
 *   2026-09-13 narrowing: a leftward stroke NEVER collapses anything);
 * - rightward-logical strokes close the VISIBLE TOP: drawer open → 'close'
 *   (the animated commitFollowClose path, gated on the drawer's own
 *   distance/velocity thresholds so both families judge a stroke alike);
 *   else panel open → 'files'; else 'none'.
 */
function classifyFilesSwipe(t, m, rtl) {
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    const velX = rtl ? -m.velX : m.velX;
    if (dx < 0) {
        if (t.panelOpen || t.drawerOpen)
            return 'none';
        if (-dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        // A fling only counts when it agrees with the stroke's own direction
        // (the same contradiction guard classifySwipe applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return -velX >= t.velocity ? 'files' : 'none';
    }
    if (t.drawerOpen) {
        // Same gates as the drawer family's close (classifySwipe), including its
        // CLOSE_DISTANCE_RATIO: this cell IS the drawer-close commit path, so the
        // two families must judge one physical stroke alike. Without the gate a
        // thumb resting on a row drifts ~8px sideways while scrolling — that
        // closed the drawer AND consumed the tap, and since issue #268 any start
        // point can close it. The leftward cell beside the drawer is already
        // 'none', so this is what makes the families agree.
        const closeRatio = t.drawerCloseDistanceRatio ?? t.distanceRatio;
        if (dx / t.viewportWidthPx >= closeRatio)
            return 'close';
        if (velX <= 0)
            return 'none';
        return velX >= t.velocity ? 'close' : 'none';
    }
    if (t.panelOpen) {
        if (dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        if (velX > 0 !== dx > 0)
            return 'none';
        return velX >= t.velocity ? 'files' : 'none';
    }
    return 'none';
}
/**
 * Recent-window instantaneous velocity (px/ms) from the tail of the last
 * `windowMs` milliseconds of samples, up to `now`. Sliding X per ms between
 * the LAST TWO in-window samples — the end-of-stroke slope — so a long slow
 * drag then a quick flick reports the flick, not the drag average. Samples
 * older than the window are ignored. Fewer than two in-window samples → 0.
 */
function slidingVelocity(samples, windowMs, now) {
    const cutoff = now - windowMs;
    const inWindow = samples.filter((s) => s.t >= cutoff);
    if (inWindow.length < 2)
        return 0;
    const a = inWindow[inWindow.length - 2];
    const b = inWindow[inWindow.length - 1];
    const dt = b.t - a.t;
    if (dt <= 0)
        return 0;
    return (b.x - a.x) / dt;
}
/** Which gesture family owns a stroke that begins while a panel is OPEN.
 *
 * The TOP-most open panel owns it: the drawer's column is painted above the
 * files panel, so a drawer sitting on top still owns the stroke. The panels
 * are mutually exclusive (classifyFilesSwipe's leftward cell answers 'none'
 * while `drawerOpen`), so no second family ever competes for it.
 *
 * The drawer's own rect is deliberately NOT consulted — a stale rect
 * mid-animation would hand the stroke to a family that can open the wrong
 * panel. Issue #268 (2026-11-07) removed the last geometric gate here; see
 * docs/specs/2026-08-27-sidebar-swipe-gestures.md 第十四轮. */
function openStateStartMode(drawerOpen, panelOpen) {
    return !drawerOpen && panelOpen ? 'files' : 'drawer';
}
/** Which gesture family a stroke belongs to when BOTH panels were shut at
 * pointerdown — the whole-viewport start rule (issue #268, 2026-11-07).
 *
 * Direction is the only gate: each classifier already refuses strokes that
 * disagree with its own family's direction, so the retired left/right 45%
 * zones were a redundant second gate (and left a 38px dead strip at 390px).
 *
 *   rightward-logical (toward the drawer's edge) → drawer
 *   leftward-logical  (toward the files edge)    → files
 *
 * RTL mirrors the axis exactly like the classifiers: `rightward` arrives
 * already normalized, so no `rtl` parameter is needed. */
function closedStateStrokeMode(rightward) {
    return rightward ? 'drawer' : 'files';
}
/**
 * Pure follow mapping (B 档): the translateX (px) to paint for a stroke
 * sample, or null when THIS sample has no follow. `closedTx` is the signed
 * closed-slot translateX (negative LTR, positive RTL — the drawer slides
 * off the anchored edge); `dx` is the RAW pointer delta; normalization
 * mirrors classifySwipe (`d = rtl ? -dx : dx`, rightward-logical positive =
 * toward open).
 *
 * Decision table (C3 hybrid, 2026-08-29 user decision):
 * - close stroke (drawer open): LEFTWARD-logical travel drags the drawer
 *   toward its closed slot, clamped at the slot; rightward-logical → null
 *   (the legacy A 档 close owns that direction — no follow, momentum-honest);
 * - open stroke (drawer closed): NOT used at runtime — the open direction
 *   follows through `followOpenTransform` instead, because its baseline has
 *   to stay a percentage across the subtree swap (see that function). The px
 *   mapping is kept pure and tested as the reference semantics;
 * - a zero closed slot (degenerate host without a closed transform) yields
 *   a constant 0 — the follow degrades to a no-op instead of inventing
 *   travel.
 */
function followTranslate(closedTx, dx, rtl, drawerOpen) {
    const dir = closedTx <= 0 ? -1 : 1;
    const slot = Math.abs(closedTx);
    const d = rtl ? -dx : dx;
    if (drawerOpen) {
        if (d >= 0)
            return null;
        // + 0 normalizes -0 (dir=-1 times a clamped 0) so strict equality in the
        // decision table and in probe comparisons sees a plain zero.
        return dir * Math.min(slot, -d) + 0;
    }
    if (d <= 0)
        return null;
    return dir * (slot - Math.min(slot, d)) + 0;
}
/**
 * Pure follow mapping for the OPEN direction (B 档, 2026-08-29 second pass).
 * Returns the CSS transform to paint for a stroke that has already
 * early-committed the host state, or null when this sample has no follow
 * (leftward-logical travel, i.e. pulled back past the stroke origin).
 *
 * The baseline is the host's own PERCENTAGE slot (`translateX(-110%)`), kept
 * symbolic on purpose: at arm time the element is still the ~206px collapsed
 * rail and a frame later React has swapped in the ~280px drawer. A px
 * baseline captured before the swap would leave the wider drawer 74px
 * off-position (its slot is -308px, not -227px); `-110%` re-resolves against
 * the element's current width on every frame, so the same declaration is
 * correct across the mount. `min()`/`max()` clamp the open end so overshoot
 * cannot drag the drawer past its resting position.
 */
function followOpenTransform(travelPx, rtl) {
    const t = rtl ? -travelPx : travelPx;
    if (t <= 0)
        return null;
    return rtl
        ? `translateX(max(0px, calc(${OPEN_FOLLOW_BASE_PCT}% - ${t}px)))`
        : `translateX(min(0px, calc(-${OPEN_FOLLOW_BASE_PCT}% + ${t}px)))`;
}
/**
 * Pure walk: the innermost element of the chain (self included) that is a
 * GENUINELY horizontally scrollable container — overflow-x auto/scroll AND
 * content actually overflowing (scrollWidth > clientWidth + 1; the +1
 * absorbs subpixel rounding). A stroke beginning inside one belongs to that
 * scroller: the browser claims the horizontal pan (pointercancel on real
 * devices) and the release classification must neither compete with it nor
 * preventDefault it away — prevention is what would break the strip's native
 * scrolling (the stats line spans the full width; message code blocks are
 * overflow-x:auto too).
 * CDP failure scenario C1 pins this contract. overflow-x:hidden/clip never
 * match: clipped content cannot pan, so a horizontal stroke there stays free
 * for the gesture layer.
 */
function findHorizontalScroller(node) {
    let cur = node;
    while (cur !== null) {
        if ((cur.overflowX === 'auto' || cur.overflowX === 'scroll') &&
            cur.scrollWidth > cur.clientWidth + 1) {
            return cur;
        }
        cur = cur.parent;
    }
    return null;
}
/** The open drawer element: the frame's sidebar column (0.2.2 adds a mask before it). */
function findDrawer() {
    return (0, frame_ts_1.getDrawer)();
}
/** True when the drawer is currently open (per the collapsed marker). */
function drawerOpen() {
    const frame = (0, frame_ts_1.getFrame)();
    return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
}
/**
 * True when the host's right sidebar files panel is currently OPEN (any form:
 * fullscreen on phones, docked on tablets).
 *
 * The host owns the authoritative marker: `data-sidebar-right-open` is present
 * exactly while the panel is expanded (live-verified on 0.2.1-rc.2).
 * The 0.1.5-era geometry probe (`rect.left < innerWidth` on a mounted, not
 * visibility:hidden panel) died with the 0.2.0 dockkit refactor: the panel is
 * now a persistent viewport-wide box whose CLOSED form hides its children
 * instead of itself, so its own rect always spans the viewport and every
 * closed panel read as open (measured 2026-10-29 at 390x844: closed rect
 * [0,0,390,844] without the marker, open the same rect with it). That read
 * killed the documented right-edge LEFTWARD entry and left an undocumented
 * rightward one (#249). A host without this panel keeps returning false and
 * the explorer fallback applies.
 */
function filesPanelOpen() {
    const panel = document.querySelector('[data-sidebar-right-panel]');
    return panel !== null && panel.hasAttribute('data-sidebar-right-open');
}
/**
 * Map the real DOM ancestor chain (target first, root last) onto the plain
 * SwipeChainNode shape findHorizontalScroller walks. Bounded by the document
 * depth (~15 nodes in this app) and run once per pointerdown, so the
 * getComputedStyle calls are not a per-frame cost.
 */
function chainFrom(target) {
    let node = null;
    let el = target;
    while (el !== null) {
        node = {
            parent: node,
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
            overflowX: getComputedStyle(el).overflowX,
        };
        el = el.parentElement;
    }
    return node;
}
/** A modal the drawer does not own (0.2.2 marks the drawer itself aria-modal). */
function modalOpen() {
    return (0, frame_ts_1.foreignModalOpen)();
}
/** True when a full-screen takeover (taskboard / ssh) owns the frame, or any
 *  host conversation.view overlay (trajectory tab, dsh-file-viewer, or a
 *  future third-party view — they all set the generic
 *  `data-conversation-composer-overlay` attribute on their root) is open.
 *  In both cases the drawer edge-swipe gestures yield so horizontal content
 *  scrolling (kanban columns, trajectory tables, CSV/code panes) wins the
 *  stroke; the FAB still opens the drawer. This reads the
 *  generic overlay attribute directly — deliberately decoupled from the
 *  file-viewer marker, which keys on `.dsfv-panel` only (design
 *  2026-09-06-conversation-overlay-takeover-design.md §4.2). */
function takeoverActive() {
    return (document.documentElement.hasAttribute('data-dsh-taskboard-active') ||
        document.documentElement.hasAttribute('data-dsh-ssh-active') ||
        document.querySelector('[data-conversation-composer-overlay]') !== null);
}
/**
 * Whether a live, non-collapsed text selection owns the pointer stroke.
 * A selection-handle drag (and a long-press selection that appears between
 * pointerdown and the axis lock) is horizontally dominant and geometrically
 * indistinguishable from a drawer swipe — the browser must keep it (#43,
 * iPad WebKit). Feature-detected so the node:test suite can import the
 * predicate without a DOM.
 *
 * TWO selection models must be read, because they are disjoint:
 * - the DOCUMENT selection (window.getSelection) covers message-flow text
 *   and contenteditable hosts;
 * - a selection inside a text control lives on the ELEMENT as
 *   selectionStart/selectionEnd and is INVISIBLE to window.getSelection —
 *   measured on the composer during a hijacked stroke (#44, real iPad):
 *   taStart=0 taEnd=20 while the document selection reported isCollapsed.
 *   Reading only the document selection let the swipe layer arm, lock, and
 *   collapse the composer selection the user was extending.
 * document.activeElement is the right anchor for the element model: a handle
 * drag keeps focus inside the control, and it also covers strokes whose
 * points land outside the control's own box.
 */
function selectionOwnsStroke() {
    if (typeof window === 'undefined')
        return false;
    const sel = window.getSelection();
    if (sel !== null && !sel.isCollapsed)
        return true;
    if (typeof document === 'undefined')
        return false;
    const el = document.activeElement;
    if (el === null)
        return false;
    const tag = el.tagName;
    if (tag !== 'TEXTAREA' && tag !== 'INPUT')
        return false;
    // Input types without a text selection (checkbox, number, email, …) report
    // null here — measured in Chromium — and older WebKit/Gecko throw
    // InvalidStateError instead. Both mean "no text selection is being
    // dragged", never "the control owns this stroke", so neither may be
    // allowed to escape from a pointer handler.
    try {
        const { selectionStart: start, selectionEnd: end } = el;
        return typeof start === 'number' && typeof end === 'number' && start !== end;
    }
    catch {
        return false;
    }
}
/** Whether the swipe layer is on cooldown (animation in flight). */
function onCooldown() {
    return performance.now() < cooldownUntil;
}
/**
 * Draggable-element yield mark (2026-09-11, 桌宠拖动冲突 D 方案的 C 侧):
 * a dragging component (desktop pet, floating ball, drag-to-reorder, …)
 * marks itself with `data-mobile-nav-dragging` while its drag is live — on
 * the element the pointer is holding (or any ancestor), or on
 * documentElement/body as a global mark when the dragged node moves around
 * or the dragger prefers not to touch the node tree. The gesture layer
 * reads the mark at pointerdown AND at every axis-lock attempt before the
 * stroke locks: a mark present at either point yields the whole stroke (no
 * drawer arm, no touchmove preventDefault) because the two layers would
 * otherwise both answer the same pointer stream — the exact bug the probe
 * reproduces (draggable-conflict-probe pet.t1: a 56px floating ball dragged
 * rightward inside the fifth-pass 45% zone opened the drawer mid-drag).
 * Semantics mirror selectionOwnsStroke: the mark must be up by the first
 * few move events (a pointerdown handler is the natural place); once the
 * stroke axis-locks the gesture stays committed — a mark appearing
 * mid-locked-stroke does not unwind an already-armed open follow.
 */
function dragMarkYields(event) {
    if (document.documentElement.hasAttribute('data-mobile-nav-dragging'))
        return true;
    if (document.body.hasAttribute('data-mobile-nav-dragging'))
        return true;
    return (event.target instanceof Element &&
        event.target.closest('[data-mobile-nav-dragging]') !== null);
}
/** Upper bound (px) of the "small floating widget" positional heuristic.
 * Sized for third-party widgets that ship no nameable root: dsh-pet's 0.3.x
 * hit box (measured 173x206 on the live profile page) already exceeds it and
 * is claimed by name below instead, while a full-screen overlay (backdrop,
 * sheets, dialogs) still cannot pass. */
const FLOATING_WIDGET_MAX_PX = 200;
/**
 * Floating-widget positional yield (2026-09-11, 悬浮窗拖动冲突 B 侧): plugins
 * ship draggable floating widgets (desktop-pet / floating-ball / draggable
 * panel shapes) that carry NO standard "draggable" DOM mark, yet the user
 * presses the widget itself — so the stroke's start target sits inside that
 * widget's layer. Draggable widgets almost always live in a SMALL
 * freely-positioned layer (position: fixed | absolute, own box under
 * FLOATING_WIDGET_MAX_PX) hovering above the page, so walk the ancestor chain
 * from the event target: the first small positioned ancestor counts as a
 * floating widget and the stroke yields (no arm, no touchmove preventDefault).
 * Pairs with dragMarkYields (cooperation mark) which needs no shape guessing.
 * Named exception (2026-10-29, #249-1): dsh-pet 0.3.x registers its root
 * (.dsh-pet-root) into the shell overlay slot, i.e. INSIDE our frame subtree,
 * and its hit box is 173x206 — the frame exclusion and the size cap both miss
 * it, so a stroke started on the pet dragged it AND armed the drawer. Known
 * roots are claimed by name before either filter runs.
 * Excluded: anything else inside our own frame subtree — the FAB / backdrop /
 * drawer content carry their own gesture semantics and must never be
 * misread as floating widgets (the closed-state FAB sits near the left edge).
 * ponytail: no DOM-standard draggable signal exists; shape ≈ draggable is an
 * approximation with a known ceiling — a STATIC small positioned element
 * (e.g. a message badge) also yields, costing a stroke start under a
 * FLOATING_WIDGET_MAX_PX dot; a REAL widget that misses upgrades via the
 * data-mobile-nav-dragging mark, a named branch like the pet one, or the cap.
 */
function findFloatingWidget(target) {
    const pet = target.closest('.dsh-pet-root');
    if (pet !== null)
        return pet;
    if (target.closest('[data-mobile-nav="frame"]') !== null)
        return null;
    let el = target;
    while (el !== null) {
        if (el instanceof HTMLElement) {
            const cs = getComputedStyle(el);
            if ((cs.position === 'fixed' || cs.position === 'absolute') &&
                el.offsetWidth <= FLOATING_WIDGET_MAX_PX &&
                el.offsetHeight <= FLOATING_WIDGET_MAX_PX) {
                return el;
            }
        }
        el = el.parentElement;
    }
    return null;
}
function floatingWidgetYields(event) {
    return (event.target instanceof Element &&
        findFloatingWidget(event.target) !== null);
}
/**
 * Cache the follow geometry for a freshly locked stroke. Runs ONCE per
 * stroke (one getComputedStyle, plus one getBoundingClientRect only for the
 * cold-start fallback); the per-move path afterwards is write-only.
 *
 * CLOSE strokes follow from a px baseline read here. OPEN strokes cannot:
 * the host renders TWO different subtrees in the same sidebar column —
 * collapsed it is a ~206px rail holding only Task Board / SSH / Files /
 * Session log (79 nodes, ZERO `role=treeitem`), open it is the ~280px drawer
 * with the session tree and footer (389 nodes, 15 treeitems). Dragging the
 * closed column would only reveal the rail (measured 2026-08-29, the user's
 * "完全不同的 UI、没有真实会话、位置全乱" report). The open direction therefore
 * commits FIRST and follows AFTER (armOpenFollow), which is also why its
 * baseline must stay a percentage rather than a px value cached here.
 */
function startFollow() {
    // Unbind first: followDrawer survives across strokes (endStroke releases
    // the styles AFTER reset(), so reset must not clear it). Without this an
    // open stroke would inherit the binding left by the previous close-follow
    // and start following after all — exactly what the probe assertion
    // swipe.open-stroke-no-follow catches.
    followDrawer = null;
    followEngaged = false;
    openFollowArmed = false;
    openFollowRefused = false;
    // strokeRtl is read by the OPEN branch of applyFollow BEFORE it arms, so it
    // must be refreshed for every locked stroke — not only the close branch —
    // or an open stroke would inherit the previous stroke's reading direction.
    strokeRtl = frameRtl();
    const drawer = findDrawer();
    if (drawer === null)
        return;
    // A closed stroke binds nothing here: the OPEN direction early-commits and
    // binds inside armOpenFollow, using a percentage baseline (the element's
    // width changes when React swaps the rail for the real drawer).
    if (!lockDrawerOpen)
        return;
    followDrawer = drawer;
    // The slot is 110% of the element's OWN width (the host's closed rule is
    // translateX(-110%), the extra 10% covering any shadow).
    //
    // Measuring the OPEN drawer is load-bearing (2026-08-29 seventh round,
    // user report 「左滑的时候会卡一下…会突然有出现半开不开的样子」 →
    // 「UI 会停在我最终滑动的地方，之后消失」). The previous baseline was a
    // slot observed on the CLOSED host, i.e. on the ~206px nav rail
    // (~-226.7px) — but the drawer being dragged is ~280px and parks at
    // ~-308px. followTranslate clamps at the slot, so the drag froze 81px
    // short of the edge: the drawer stopped under a still-moving finger
    // (「半开不开」), and the release then had to travel that remainder,
    // reading as a stall followed by a disappearance.
    //
    // Width is stable for the duration of a close stroke (no subtree swap
    // until the release commits), so a px baseline is safe here — unlike the
    // open direction, which must stay percentage-based because React swaps the
    // rail for the real drawer mid-stroke.
    const slot = (drawer.getBoundingClientRect().width * CLOSED_SLOT_PCT) / 100;
    strokeClosedTx = strokeRtl ? slot : -slot;
}
/**
 * Arm the OPEN follow: pin the drawer in its closed slot with an important
 * inline pair, THEN flip the host state in the same task. React mounts the
 * real ~280px drawer subtree while our inline transform holds it off-screen,
 * so the next move samples slide the genuine drawer — session tree and all —
 * out of the slot under the finger. Ordering matters: pin before the flip,
 * or the host's open rule (`transform: none`) paints the drawer at rest for
 * one frame and the user sees it snap into place before the follow starts.
 *
 * The backdrop and the FAB swap at the flip, which is the documented binary
 * behavior (spec review 缺陷 2: no opacity-following backdrop).
 */
/** True while the drawer subtree layout+paint is deliberately deferred by
 * the arm-time content-visibility split (see armOpenFollow). */
let cvDeferred = false;
/** Pending reveal chain handle (double rAF); cancelled on dispose. */
let armRevealRaf = 0;
/** Re-materialize the drawer contents after the mount-frame split. */
function revealDrawerContent() {
    if (!cvDeferred)
        return;
    cvDeferred = false;
    followDrawer?.style.removeProperty('content-visibility');
    const el = findDrawer();
    if (el !== null && el !== followDrawer)
        el.style.removeProperty('content-visibility');
}
function armOpenFollow(ctx) {
    if (openFollowArmed || openFollowRefused)
        return;
    const drawer = findDrawer();
    if (drawer === null || modalOpen() || takeoverActive()) {
        openFollowRefused = true;
        return;
    }
    followDrawer = drawer;
    followEngaged = true;
    drawer.style.setProperty('transition', 'none', 'important');
    const pinned = followOpenTransform(0.0001, strokeRtl);
    drawer.style.setProperty('transform', pinned ?? `translateX(-${CLOSED_SLOT_PCT}%)`, 'important');
    // Split the mount cost (2026-08-29, user report 「滑动不会立刻生效，而是卡
    // 那么零点几秒」): the toggle below synchronously mounts the 389-node
    // drawer subtree, and reconcile + style + layout + paint all land in ONE
    // long task — measured 308ms at 4x CPU throttle, a quarter-second of
    // frozen screen on a phone. content-visibility:hidden (set BEFORE the
    // flip, on the column that survives the subtree swap) makes the mount
    // frame skip subtree layout+paint — the panel BOX still paints and the
    // compositor keeps following the finger — and the contents materialize
    // two frames later via revealDrawerContent(), where the motion masks the
    // second (smaller) block. Ignored by browsers without support (no-op).
    drawer.style.setProperty('content-visibility', 'hidden', 'important');
    cvDeferred = true;
    openFollowArmed = true;
    (0, drawer_open_ts_1.releaseTextFocus)(); // drawer-jank R2: blur before the flip; no wait, the finger is moving
    (0, drawer_open_ts_1.drawerOpening)(); // R3: the backdrop mounts with the flip, not one rAF later
    ctx.layout.toggleSidebar();
    // Two frames (layout flush, then reveal). Kept in module state so a dispose
    // between them cancels the chain instead of revealing a drawer that no
    // longer belongs to this effect (issue #86).
    armRevealRaf = requestAnimationFrame(() => {
        armRevealRaf = requestAnimationFrame(() => {
            armRevealRaf = 0;
            revealDrawerContent();
        });
    });
}
/**
 * Paint this move sample's follow position. Null mapping (legacy direction
 * or pulled back past the stroke origin) releases the inline styles so the
 * host transition is live again — the drawer springs to wherever the host
 * state puts it and the classification still owns the release. Re-engaging
 * after a null sample rewrites both inline properties, which also
 * self-heals anything that restored them mid-stroke (React re-render).
 *
 * Both properties MUST be written with `important` priority. The open state
 * is styled by our own `transform: none !important` (layout.css.ts — the
 * containing-block rule for the settings overlay), which outranks a plain
 * inline declaration: a normal `style.transform = ...` leaves the computed
 * transform at `none` and the drawer never moves. That is exactly how the
 * first follow implementation shipped invisible while every inline-string
 * assertion passed (2026-08-29) — assert COMPUTED transform, never
 * `element.style.transform`.
 */
function applyFollow(ctx, dx) {
    if (!tracking || strokeMode !== 'drawer')
        return;
    if (!lockDrawerOpen) {
        // OPEN direction: arm past the twitch threshold, then follow with the
        // percentage baseline (the element's width changes across the mount).
        const travel = strokeRtl ? -dx : dx;
        if (!openFollowArmed) {
            if (travel < OPEN_FOLLOW_ARM_PX)
                return;
            armOpenFollow(ctx);
            if (!openFollowArmed)
                return;
        }
        const value = followOpenTransform(dx, strokeRtl);
        if (value === null) {
            // Pulled back past the origin: hold the drawer parked in its slot
            // rather than releasing (releasing would let the host animate it open
            // behind the finger). The release still classifies and may revert.
            followDrawer?.style.setProperty('transform', `translateX(-${CLOSED_SLOT_PCT}%)`, 'important');
            return;
        }
        followDrawer?.style.setProperty('transform', value, 'important');
        return;
    }
    if (followDrawer === null)
        return;
    const tx = followTranslate(strokeClosedTx, dx, strokeRtl, lockDrawerOpen);
    if (tx === null) {
        // Pulled back past the origin. Hold the drawer at rest instead of
        // releasing the inline pair: releasing would restore the host's .28s
        // transition mid-stroke, so a direction wobble would animate the drawer
        // and then jump when the finger crosses back — the same reason the open
        // branch pins instead of releasing.
        followEngaged = true;
        followDrawer.style.setProperty('transition', 'none', 'important');
        followDrawer.style.setProperty('transform', 'translateX(0px)', 'important');
        return;
    }
    followEngaged = true;
    followDrawer.style.setProperty('transition', 'none', 'important');
    followDrawer.style.setProperty('transform', `translateX(${tx}px)`, 'important');
}
/**
 * Drop the inline follow styles. The host stylesheet retakes control: with
 * the transition restored, clearing the transform animates the drawer from
 * the finger position to whatever the CURRENT host state says. Called on
 * every end-stroke branch (revert: this IS the spring-back; commit: the
 * same-task retarget below overrides the initial leg before any paint).
 */
function releaseFollowStyles() {
    const el = followDrawer;
    if (!followEngaged || el === null)
        return;
    followEngaged = false;
    el.style.removeProperty('transition');
    el.style.removeProperty('transform');
}
/** A close commit that is still animating to the closed slot before the host
 * state flips. The flip MUST wait: the sidebar column renders two mutually
 * exclusive subtrees (280px drawer when open, 206px nav rail when closed),
 * and React swaps them some ~200ms after the marker flips — measured
 * mid-animation at t≈200ms of a 280ms transition (width 280→206, tx jumped
 * -207.6→-181.9 as -110% re-resolved against the narrower rail). Flipping
 * first therefore replaces the drawer's content and retargets its transition
 * IN FLIGHT — user report 「最后抽屉样式突然消失,不是自然的动画收起」.
 * Late commit: animate the inline transform to the slot, flip only when the
 * drawer is already off-screen, then drop the inline pair. */
let pendingCommit = null;
function finishPendingCommit() {
    const pending = pendingCommit;
    if (pending === null)
        return;
    pendingCommit = null;
    window.clearTimeout(pending.timer);
    // The element may already be unmounted (React swaps the subtree at the
    // flip); stripping inline from a detached node is a harmless no-op.
    pending.el.style.removeProperty('transition');
    pending.el.style.removeProperty('transform');
    // If the host already closed while our animation ran (e.g. a genuine
    // backdrop tap inside the 280ms window), the flip already happened and a
    // blind toggle would RE-OPEN the drawer — skip it.
    const frame = (0, frame_ts_1.getFrame)();
    if (frame !== null && !frame.hasAttribute('data-sidebar-collapsed')) {
        pending.ctx.layout.toggleSidebar();
    }
}
/** Animate `el` to `targetTx` with our own transition, flip the host when it
 * lands. A repeat of the SAME intent inside the window is claimed by the
 * in-flight commit (no-op); a different intent settles it first. */
function commitWithAnimation(ctx, el, targetTx) {
    // Same target, same element = the drawer is already leaving that way. Let the
    // first commit land: force-settling here would flip the host state NOW and
    // then animate an already-closed drawer (Escape twice inside the 320ms window
    // used to toggle the host twice — the "晚提交" contract broken by us).
    const inFlight = pendingCommit;
    if (inFlight !== null && inFlight.el === el && inFlight.target === targetTx)
        return;
    finishPendingCommit();
    // prefers-reduced-motion: degrade the animation instead of adding one. This
    // cannot be left to CSS — the transition written below is inline
    // `!important`, which the stylesheet's reduce block cannot override, and the
    // gesture-close path reaches here without a reduce check (issue #86). Land
    // the exact same guarded commit, just without the animation beat.
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        (0, drawer_layer_ts_1.fadeDimOut)();
        cooldownUntil = performance.now() + COOLDOWN_MS;
        pendingCommit = { el, ctx, timer: 0, target: targetTx };
        finishPendingCommit();
        return;
    }
    el.style.setProperty('transition', `transform ${COMMIT_ANIM_MS}ms ease-in-out`, 'important');
    // Flush the before-change style ONLY when an inline transform is already
    // present — that is the case this flush exists for: a finger-follow wrote the
    // current position in this same frame, and the transition has to start from it
    // instead of from a coalesced recalc. A click/Escape/backdrop close starts from
    // the stylesheet's open state (`transform: none`, no inline value), so the
    // transition is well defined without forcing a reflow of the whole column —
    // measured 105ms of `onDrawerClick` on a 3.5k-node session, growing with the
    // conversation.
    if (el.style.getPropertyValue('transform') !== '')
        void el.getBoundingClientRect();
    el.style.setProperty('transform', targetTx, 'important');
    // Fade the dimming in step with the slide-out: the marker flips only when
    // the drawer lands, so without this the screen would go drawer-then-dark
    // (backdrop snapping away ~260ms AFTER the drawer already left).
    (0, drawer_layer_ts_1.fadeDimOut)();
    cooldownUntil = performance.now() + COOLDOWN_MS;
    pendingCommit = {
        el,
        ctx,
        timer: window.setTimeout(finishPendingCommit, COMMIT_ANIM_MS + 40),
        target: targetTx,
    };
}
/** Terminal close commit: animate the drawer into the closed slot, then flip
 * the host. The slot must be the host's REAL closed rule (-110%), because
 * after the flip the closed host paints exactly this value — dropping the
 * inline pair must be a no-op, not a jump. */
function commitFollowClose(ctx) {
    const el = followDrawer;
    followDrawer = null;
    followEngaged = false;
    if (el === null) {
        // No follow binding (defensive): fall back to the immediate flip.
        releaseFollowStyles();
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
        return;
    }
    const target = strokeRtl
        ? `translateX(${CLOSED_SLOT_PCT}%)`
        : `translateX(-${CLOSED_SLOT_PCT}%)`;
    commitWithAnimation(ctx, el, target);
}
/** Animate an OPEN drawer into its closed slot and flip the host state once it
 * has landed. Every non-gesture closer (backdrop tap, Escape, navigation taps)
 * routes through this, so a click close animates exactly like a swipe close:
 * the host swaps the pane's subtree AND drops its surface (transparent,
 * borderless, content display:none) at the marker flip, so a plain CSS
 * transition would slide out an invisible shell - the same reason the gesture
 * close uses a late commit (eighth round, 2026-08-29). The OPEN direction needs
 * none of this: the host keeps the pane's visuals until the marker flips, so
 * its own transform transition plays (spec 2026-08-27, A 档).
 * Returns false when the caller must fall back to a plain toggleSidebar(): the
 * drawer is already closed (that call would OPEN it) or the user asked for
 * reduced motion, where the spec degrades the animation instead of adding one. */
function closeDrawerAnimated(ctx) {
    if (!drawerOpen())
        return false;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches)
        return false;
    const drawer = findDrawer();
    if (drawer === null)
        return false;
    const target = frameRtl()
        ? `translateX(${CLOSED_SLOT_PCT}%)`
        : `translateX(-${CLOSED_SLOT_PCT}%)`;
    commitWithAnimation(ctx, drawer, target);
    return true;
}
/** Cancel paths: styles back to the host, pointer state to idle. An armed
 * open follow has already flipped the host state, so a cancel must also
 * toggle it back — release the inline pair first so the host transition
 * animates home from the finger position within the same task. */
function abortStroke(ctx, immediate = false) {
    if (pendingCommit !== null) {
        // A terminal commit is animating: this stroke already ended. Only a
        // teardown (dispose) must settle it synchronously; otherwise let the
        // timer land the flip.
        if (immediate)
            finishPendingCommit();
        return;
    }
    const wasArmed = openFollowArmed;
    openFollowArmed = false;
    openFollowRefused = false;
    revealDrawerContent();
    if (wasArmed && ctx !== null && followDrawer !== null && !immediate) {
        // Armed open stroke aborted mid-follow: the host is already open, and
        // flipping now would swap the subtree mid-motion — same artifact as the
        // close release. Animate back into the slot, then flip.
        reset();
        commitFollowClose(ctx);
        return;
    }
    releaseFollowStyles();
    reset();
    if (wasArmed && ctx !== null) {
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
}
/** Start a stroke; returns true when it may be tracked. */
function beginStroke(event) {
    if (onCooldown())
        return false;
    if (modalOpen())
        return false;
    if (takeoverActive())
        return false;
    // A live text selection owns the stroke (a selection-handle drag is
    // horizontally dominant and geometrically identical to a swipe — #43,
    // iPad WebKit): yield before any geometric test. This also blocks
    // swipe-open while a stale selection is alive; one tap collapses the
    // selection everywhere, and backdrop tap-to-close is unaffected (a tap
    // never reaches tryLock).
    if (selectionOwnsStroke())
        return false;
    // A live draggable (data-mobile-nav-dragging, see dragMarkYields) owns the
    // stroke: yield before any geometric test so the drawer cannot arm for a
    // drag on the element itself.
    if (dragMarkYields(event))
        return false;
    // Plugin-shipped draggable floating widgets (pet / floating-ball shapes
    // without any cooperation mark) yield the same way, via the positional
    // heuristic — the user pressed the widget itself.
    if (floatingWidgetYields(event))
        return false;
    if (!(event.target instanceof Element))
        return false;
    // A stroke beginning inside a genuinely horizontally scrollable container
    // belongs to that scroller (the stats line, a message code block, any
    // carousel): yield it so its native horizontal pan survives instead of
    // turning into a drawer/panel open (failure scenario C1). Applies to both
    // branches: inside the drawer the same "scroller owns horizontal"
    // semantics hold.
    if (findHorizontalScroller(chainFrom(event.target)) !== null)
        return false;
    const open = drawerOpen();
    // The files panel is full-screen and mutually exclusive with the drawer, so
    // it opens the SAME close-stroke surface (issue #268): while either one is
    // up, the whole frame may start the stroke that closes it.
    const panelOpen = filesPanelOpen();
    if (open || panelOpen) {
        // An open drawer or panel owns every stroke over the frame (2026-08-29
        // sixth round, user report 「希望打开抽屉之后以外的部分可以进行左滑」;
        // issue #268 replaced the 2026-09-13 narrowing's "inside the drawer body"
        // gate with the whole viewport). Nothing else competes for a horizontal
        // stroke while the backdrop covers the conversation. Tap-to-close on the
        // backdrop is unaffected: a tap never reaches tryLock, so no consume mark
        // is written.
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return false;
        const rect = frame.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right)
            return false;
        if (event.clientY < rect.top || event.clientY > rect.bottom)
            return false;
        // A session-row action menu (kebab) owns its own tap.
        if (event.target.closest('[class*="sessionRow"] button') !== null)
            return false;
        // Who is open owns the stroke — no rect test, no viewport split.
        strokeMode = openStateStartMode(open, panelOpen);
    }
    else {
        // Closed state: the whole viewport starts a stroke and the family is
        // decided at the axis lock, where the direction exists (issue #268).
        // Vertical strokes still belong to native scrolling — they fail the axis
        // lock exactly as before, and a tap never reaches tryLock.
        strokeMode = 'pending';
    }
    trackingPointer = event.pointerId;
    tracking = false;
    startX = event.clientX;
    startY = event.clientY;
    samples = [{ t: event.timeStamp, x: event.clientX }];
    return true;
}
/**
 * Axis-lock the stroke once its dominant axis has moved LOCK_PX. Horizontal
 * dominance (|dx| > |dy|) locks to X and is tracked; vertical dominance
 * abandons the stroke back to native scrolling (browser takes over, no
 * further preventDefault). Once locked the axis never re-decides — matching
 * MUI's UNCERTAINTY_THRESHOLD semantics.
 */
function tryLock(event, rtl) {
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < LOCK_PX)
        return false;
    // Second timing window for the drag mark (same pattern as the selection
    // check in onPointerMove): the dragger often raises the mark in its own
    // pointerdown/move handler, i.e. AFTER our beginStroke ran. Re-check at
    // every lock attempt so the stroke yields before the axis locks.
    if (dragMarkYields(event) || floatingWidgetYields(event)) {
        reset();
        return false;
    }
    if (Math.abs(dx) <= Math.abs(dy)) {
        // Vertical-dominant: hand the touch back to scrolling.
        reset();
        return false;
    }
    tracking = true;
    lockDrawerOpen = drawerOpen();
    // Closed-state strokes were never routed to a family (issue #268): the
    // direction that decides it is known only here. RTL mirrors the raw delta
    // exactly like both classifiers do, so the family and the verdict always
    // agree about which way the finger went. beginStroke routes the OPEN state
    // itself (openStateStartMode), so 'pending' can only mean "both panels were
    // shut at pointerdown" — re-read the panel anyway: the two events are
    // separate, and a tap that opens the panel in between must not be judged by
    // the closed-state rule.
    if (strokeMode === 'pending') {
        const rightward = rtl ? dx < 0 : dx > 0;
        strokeMode = closedStateStrokeMode(rightward);
    }
    if (strokeMode === 'files') {
        lockFilesOpen = filesPanelOpen();
        // An OPEN drawer shares this stroke (the right-edge rightward close):
        // bind the drawer's close follow so the commit animates exactly like
        // today's right-zone close. applyFollow stays mode-guarded, so no follow
        // ever paints for files strokes — the LEFTWARD narrowing stroke gets no
        // painting either, which is the point (it must not drag the drawer).
        if (lockDrawerOpen)
            startFollow();
        (0, gesture_guard_ts_1.markStrokeLocked)();
        return true;
    }
    // Publish the lock to the host handlers (see gesture-guard.ts): they run
    // EARLIER in this release event's capture phase, before endStroke writes
    // any consume mark — the flag is their only ordering-proof yield signal
    // (audit S0/S1).
    (0, gesture_guard_ts_1.markStrokeLocked)();
    startFollow();
    return true;
}
/** Append a sample and prune the window. */
function pushSample(event) {
    samples.push({ t: event.timeStamp, x: event.clientX });
    const cutoff = event.timeStamp - VELOCITY_WINDOW_MS;
    let i = 0;
    while (i < samples.length - 1 && samples[i].t < cutoff)
        i += 1;
    if (i > 0)
        samples = samples.slice(i);
}
/**
 * Release the stroke: classify, then either commit or spring back.
 *
 * B 档 ordering is load-bearing: the verdict is computed FIRST (the follow
 * position IS dx, so classifySwipe decides complete-vs-revert exactly as in
 * A 档), then the inline follow styles are dropped — restoring the host
 * transition and clearing the transform starts an animation toward the
 * drawer's CURRENT host state — and only then does the commit flip the host
 * state, retargeting that transition within the SAME task. No paint happens
 * between the two, so the user sees one continuous motion from the finger
 * position into the final state; a reverted stroke simply animates home.
 *
 * An ARMED OPEN follow inverts the commit: the host state was already
 * flipped at arm time, so a positive verdict must NOT toggle again (that
 * would close the drawer the user just pulled out) and a negative verdict
 * must toggle BACK. Either way the inline release comes first, so the host
 * transition animates from the finger position to whichever state wins.
 */
function endStroke(ctx, event, rtl, viewportWidthPx) {
    const wasTracking = tracking;
    const armedOpen = openFollowArmed;
    openFollowArmed = false;
    openFollowRefused = false;
    // The stroke's mode and panel flag must be captured BEFORE reset(): reset()
    // rewrites strokeMode to 'drawer' and clears lockFilesOpen, and the verdict
    // below branches on them. Harmless for drawer strokes (whose mode already
    // reads 'drawer'), fatal for files strokes — the verdict silently degraded
    // to the drawer classifier and every files commit turned into 'none'.
    const filesMode = strokeMode === 'files';
    const filesOpenAtLock = lockFilesOpen;
    // Velocity must be computed before reset() clears the samples.
    const vel = slidingVelocity(samples, VELOCITY_WINDOW_MS, event.timeStamp);
    // Distance is measured from the stroke START (not the axis-lock point):
    // the slop is an activation gate, not travel that should consume the
    // user's swipe distance. Measuring from the lock point made the effective
    // travel = slop + threshold (e.g. 4px + 78px), so a 78px threshold
    // actually needed ~82px+ of finger travel — the "feels like half the
    // screen" complaint. From the start, a 78px threshold is a 78px swipe.
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    reset();
    if (!wasTracking) {
        // A stroke that armed the follow is by definition locked, so this branch
        // cannot leave the host state flipped — but keep the invariant explicit.
        if (armedOpen) {
            commitFollowClose(ctx);
        }
        return;
    }
    const modal = modalOpen();
    // An armed open follow has already flipped the marker, so classifySwipe
    // must still be asked the question the USER answered: it was a closed
    // drawer when the stroke began (lockDrawerOpen), which is what the stored
    // flag holds — never re-read drawerOpen() here.
    const verdict = modal || (!armedOpen && onCooldown())
        ? 'none'
        : filesMode
            ? classifyFilesSwipe({
                distanceRatio: FILES_DISTANCE_RATIO,
                velocity: FILES_VELOCITY,
                lockPx: LOCK_PX,
                viewportWidthPx,
                panelOpen: filesOpenAtLock,
                drawerOpen: lockDrawerOpen,
                // The drawer-open cell commits a drawer close, so it keeps the
                // drawer's own close distance (the spec's "┍ identical to
                // today's close").
                drawerCloseDistanceRatio: CLOSE_DISTANCE_RATIO,
            }, { dx, dy, velX: vel }, rtl)
            : classifySwipe({
                openDistanceRatio: OPEN_DISTANCE_RATIO,
                closeDistanceRatio: CLOSE_DISTANCE_RATIO,
                openVelocity: OPEN_VELOCITY,
                closeVelocity: CLOSE_VELOCITY,
                lockPx: LOCK_PX,
                viewportWidthPx,
                drawerOpen: lockDrawerOpen,
            }, { dx, dy, velX: vel }, rtl);
    // The mount-frame split must never survive into a terminal state: reveal
    // the contents (no-op unless armed this stroke) before any release or
    // commit animation.
    revealDrawerContent();
    // Terminal styles, per verdict. CLOSE commits are LATE: animate the inline
    // transform into the closed slot and flip the host only when the drawer is
    // already off-screen (commitFollowClose → commitWithAnimation) — flipping
    // first swaps the sidebar subtree mid-animation (measured: width 280→206
    // at t≈200ms of the 280ms transition, tx jumped backward). OPEN verdicts
    // and the revert/modal/cooldown paths keep the plain release: the host
    // stays in its current state, so its own transition finishes the motion
    // and no subtree swap can be in flight. Every path either releases or
    // hands the inline pair to the pending commit — it can never leak.
    if (armedOpen) {
        // The host is already open (early commit). Keep it on 'open', otherwise
        // animate back into the slot and flip closed.
        if (verdict === 'open') {
            releaseFollowStyles();
            cooldownUntil = performance.now() + COOLDOWN_MS;
        }
        else {
            commitFollowClose(ctx);
        }
        if (event.target instanceof Element)
            markStrokeConsumed(event.target);
        return;
    }
    if (!(event.target instanceof Element))
        return;
    if (verdict === 'close') {
        // Mark the stroke consumed so the tap's synthetic click cannot
        // double-toggle or navigate a row. The mark walks the ancestor chain up
        // to the DRAWER (not the frame): the synthetic click always lands on the
        // stroke's own start target (the frame or the drawer content), never
        // on the backdrop — but the backdrop is a frame child, so marking up to
        // the frame would make the host treat a genuine backdrop tap within the
        // 300ms window as consumed and swallow the close (the "tap twice to close"
        // bug). Marking stays IMMEDIATE even though the flip is late: the mark
        // snapshots the chain now, and the synthetic click arrives within ~10ms.
        markStrokeConsumed(event.target);
        commitFollowClose(ctx);
        return;
    }
    releaseFollowStyles();
    if (verdict === 'open') {
        // Unreachable for a tracked stroke (an unarmed stroke is by definition
        // drawer-open at start), but keep the host-service commit symmetric.
        markStrokeConsumed(event.target);
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
    if (verdict === 'files') {
        // The files-panel commit: open (both closed + leftward) or close (panel
        // open + rightward). Toggle FIRST, then mark: the consume mark walks the
        // stroke-start target's ancestors up to the FRAME, and the frame is also
        // an ancestor of the host control the toggle clicks programmatically —
        // marking first matches that very click through the shared upper chain
        // segments and swallows it, so the panel never opens (0.1.5 live
        // observation). The mark still covers the browser's own synthetic click,
        // which is dispatched asynchronously after the release (the send button,
        // a row button, … sit under the release point).
        filesToggleFn();
        markStrokeConsumed(event.target);
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
    if (filesMode && verdict === 'none') {
        // A 'none' files release is still a gesture (panel open + leftward, or
        // too short): consume its synthetic click so it cannot flip the panel
        // through the host toggle under the release point. This must run AFTER
        // the commit branches above, never before a programmatic toggle click.
        markStrokeConsumed(event.target);
    }
}
/**
 * Mark the released stroke so its synthetic click cannot re-toggle the drawer
 * or activate a row.
 *
 * The mark walks the ancestor chain up to the DRAWER when the stroke started
 * inside it: the backdrop is a frame child, so stopping at the frame would
 * make the host treat a genuine backdrop tap within the window as consumed
 * and swallow the close (the "tap twice to close" bug). A stroke that started
 * OUTSIDE the drawer (since #268 anywhere over the frame, the backdrop
 * included) has no drawer in its chain, so the
 * walk would otherwise run all the way to the document root and briefly
 * shadow every tap on the page; the frame is the tightest correct stop for
 * those, and it is what must be marked anyway, because a backdrop-started
 * close stroke needs its own overlay click consumed.
 */
function markStrokeConsumed(target) {
    const drawer = findDrawer();
    const upTo = drawer !== null && drawer.contains(target) ? drawer : (0, frame_ts_1.getFrame)() ?? null;
    (0, gesture_guard_ts_1.markGestureConsumed)(target, CONSUME_WINDOW_MS, upTo);
    consumedEl = target;
}
/** Forget stroke state (called on cancel / visibility change / blur). */
function reset() {
    trackingPointer = 0;
    tracking = false;
    samples = [];
    strokeMode = 'drawer';
    lockFilesOpen = false;
    (0, gesture_guard_ts_1.clearStrokeLocked)();
}
/** The logical reading direction of the frame (RTL support). */
function frameRtl() {
    const frame = (0, frame_ts_1.getFrame)();
    return frame !== null && getComputedStyle(frame).direction === 'rtl';
}
/** Install the gesture layer for the current mobile breakpoint. */
function installSidebarSwipe(ctx, filesToggle) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: sidebar swipe gestures', () => {
        filesToggleFn = filesToggle;
        const viewportWidth = () => window.innerWidth || document.documentElement.clientWidth || 0;
        const onPointerDown = (event) => {
            // A new pointer starts a new interaction epoch: drop the previous
            // stroke's click gate. When the browser never delivers the synthetic
            // click (iOS shells suppress it after a swipe), this — together with
            // the short CONSUME_WINDOW_MS — keeps the next genuine tap alive
            // instead of eating it at the document-capture click handler.
            consumedEl = null;
            (0, gesture_guard_ts_1.clearStrokeLocked)(); // belt-and-suspenders: a lost stroke must not leak its lock into this epoch
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            // A second finger means the browser owns this interaction (pinch zoom),
            // and a two-finger drag is never a drawer swipe. Merely ignoring the
            // extra pointer would keep the stroke alive — and with it the
            // touchmove preventDefault below, which cancels the native pinch. On
            // iOS that pinch is the only way back out of a zoom, so fighting it
            // recreates exactly the trap #45 reported. Hand the whole interaction
            // back instead.
            if (trackingPointer !== 0 && trackingPointer !== event.pointerId) {
                abortStroke(ctx);
                return;
            }
            beginStroke(event);
        };
        const onPointerMove = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            // A modal may rise mid-stroke (e.g. an a11y trap opening) — spec review
            // 缺陷 1's guard, now per-MOVE because B 档 paints a transform the
            // modal must not inherit: abandon and spring the drawer back.
            if (modalOpen() || takeoverActive()) {
                abortStroke(ctx);
                return;
            }
            if (!tracking) {
                // A long-press selection can appear AFTER pointerdown but BEFORE the
                // axis lock (#43 second timing window): abandon the stroke and hand
                // the touch back so the handles become draggable (reset() also lifts
                // the touchmove preventDefault). Once locked the gesture stays
                // committed — a selection never appears mid-swipe.
                if (selectionOwnsStroke()) {
                    reset();
                    return;
                }
                if (tryLock(event, frameRtl())) {
                    pushSample(event);
                    applyFollow(ctx, event.clientX - startX);
                }
            }
            else {
                pushSample(event);
                applyFollow(ctx, event.clientX - startX);
            }
        };
        const onPointerUp = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            endStroke(ctx, event, frameRtl(), viewportWidth());
        };
        const onPointerCancel = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            abortStroke(ctx);
        };
        // The browser may synthesize a click a few ms after the stroke's
        // pointerup. The host overlay handlers and the FAB / backdrop element
        // listeners would treat it as a tap; swallow it at document capture so
        // a swipe can never toggle twice or navigate a row. Non-gesture taps
        // (no live mark) pass through untouched.
        //
        // A genuine backdrop / FAB tap is never a synthetic click of OURS, and the
        // mark chain can reach the overlay in degenerate hit-test cases — swallow
        // it and the user needs two taps to close ("tap twice to close"). So those
        // clicks pass. The one exception is handled below: an overlay click that
        // STARTED the just-committed stroke (issue #268 lets a close stroke begin
        // anywhere, the backdrop included) must not re-toggle the drawer.
        const onClick = (event) => {
            if (consumedEl === null)
                return;
            if (!(event.target instanceof Element))
                return;
            // Let a genuine overlay tap through (see above), but never the click
            // whose overlay IS the element this stroke started on: re-toggling the
            // drawer straight back open is exactly what that would do.
            const overlay = event.target.closest('[data-mobile-nav="hit"], [data-mobile-nav="fab"]');
            if (overlay !== null && !overlay.contains(consumedEl))
                return;
            if (!(0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            event.stopPropagation();
            event.preventDefault();
            consumedEl = null;
        };
        const onVisibility = () => {
            if (document.hidden)
                abortStroke(ctx);
        };
        // Edge-touch priority, from the AXIS LOCK onward only: once the stroke is
        // known horizontal, preventDefault (passive:false) stops the browser from
        // claiming it as a scroll, keeping the pointer stream intact (iOS
        // UIScreenEdgePanGestureRecognizer semantics). Before the lock the handler
        // must stay out of the way entirely: canceling a touchmove suppresses the
        // touch point's default action for the WHOLE gesture (Touch Events
        // cancelability), and WebKit does not resume scrolling after reset() —
        // preventing here killed native scrolling on iOS for every stroke that
        // began in the old start areas (#222). Multi-touch is never prevented: two fingers mean a pinch, and
        // the pointerdown guard above plus this check hand it back (#46).
        const onTouchMove = (event) => {
            if (trackingPointer === 0)
                return;
            if (event.touches.length !== 1 || !event.cancelable || document.hidden) {
                abortStroke(ctx);
                return;
            }
            if (!tracking)
                return;
            event.preventDefault();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointermove', onPointerMove, true);
        document.addEventListener('pointerup', onPointerUp, true);
        document.addEventListener('pointercancel', onPointerCancel, true);
        document.addEventListener('click', onClick, true);
        document.addEventListener('touchmove', onTouchMove, { capture: true, passive: false });
        const onBlur = () => abortStroke(ctx);
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('blur', onBlur);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('pointermove', onPointerMove, true);
            document.removeEventListener('pointerup', onPointerUp, true);
            document.removeEventListener('pointercancel', onPointerCancel, true);
            document.removeEventListener('click', onClick, true);
            document.removeEventListener('touchmove', onTouchMove, { capture: true });
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('blur', onBlur);
            if (armRevealRaf !== 0) {
                cancelAnimationFrame(armRevealRaf);
                armRevealRaf = 0;
            }
            abortStroke(ctx, true);
        };
    });
}
};
__modules["effects/drawer-close.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TAP_CLOSE_NAV_SELECTOR = void 0;
exports.toggleDrawer = toggleDrawer;
exports.installOverlayInteractions = installOverlayInteractions;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const frame_ts_1 = require("./platform/frame.js");
const drawer_open_ts_1 = require("./platform/drawer-open.js");
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const session_row_fiber_ts_1 = require("./effects/session-row-fiber.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
/** Finger-down to finger-up travel that still counts as a tap on a session row
 *  (the DSHA click replacement below). Per-axis (`isTapWithinSlop` is max-norm, not Euclidean): the drawer
 *  list scrolls vertically, so a 60px vertical drift must not navigate while a
 *  diagonal wobble still reads as a tap. */
const TAP_NAV_SLOP_PX = 12;
/** Where the current touch started (null for a mouse, and between touches).
 *  The DSHA click replacement only fires when the finger stayed put, so every
 *  touch pointerdown records this BEFORE any early return. Cleared by the
 *  effect's disposer. */
let touchDownAt = null;
/**
 * Drawer close interactions that are plain event listeners, not DOM
 * reconciliation:
 * - Escape closes the drawer (yielding to any open modal dialog, which owns
 *   its own Escape handling).
 * - Tapping a navigation target inside the drawer (session row, sidebar panel
 *   row, task board / ssh takeover entries, search results) closes the drawer
 *   so the content it opened gets the whole screen. Session-row action buttons
 *   (kebab) are excluded — they open a menu that must survive the tap.
 *
 * The touch close always rides the synthesized click. Closing a non-row
 * target from pointerup collapsed the drawer before that click existed, and
 * a collapsed drawer no longer owns the touch point, so the browser
 * dispatched no click at all and the target's own onClick never ran (「新会话」
 * did nothing but retract the drawer, 2026-09-13).
 */
exports.TAP_CLOSE_NAV_SELECTOR = 'button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*="newSession"], [class*="sessionRow"], [class*="searchResultRow"], [class*="searchResultWorkspace"], [class*="panelRow"]';
/**
 * The one drawer toggle every non-gesture entry point shares: a CLOSE animates
 * into the closed slot and flips the host marker only once it has landed
 * (closeDrawerAnimated's late commit — spec 2026-08-27), an OPEN stays a plain
 * toggle so the host's own .28s transform transition plays.
 *
 * Load-bearing for layering, not just for looks (2026-09-25): the popover
 * band's modal-root raise is gated on our backdrop being on screen, and the
 * backdrop outlives the marker flip by design (fade .2s + removal 260ms). A
 * closer that flips the marker while the column is still painted therefore
 * leaves an open modal under the drawer band for the length of the
 * transition — that is the 快捷键弹层「抽搐/闪」 root cause. Routing every
 * closer through here removes the window at the source instead of relying on
 * the band to cover it.
 *
 * An OPEN (frame still collapsed) goes through openDrawerAfterKeyboard: a
 * focused composer is blurred first and the drawer opens once the soft keyboard
 * has gone, so the host's own focus move into the drawer cannot close the
 * keyboard mid-slide (2026-11-05 drawer-jank report, R2). Every tap opener —
 * the header toggle (index.tsx) and the FAB — routes through here. The opening
 * hook runs right before the flip so the backdrop joins the same commit (R3).
 */
function toggleDrawer(ctx) {
    if ((0, sidebar_swipe_ts_1.closeDrawerAnimated)(ctx))
        return;
    const frame = (0, frame_ts_1.getFrame)();
    if (frame !== null && frame.hasAttribute('data-sidebar-collapsed')) {
        (0, drawer_open_ts_1.openDrawerAfterKeyboard)(() => {
            (0, drawer_open_ts_1.drawerOpening)(); // backdrop lands in the same task as the host commit (R3)
            ctx.layout.toggleSidebar();
        });
        return;
    }
    ctx.layout.toggleSidebar();
}
function installOverlayInteractions(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: drawer close (Escape + navigate)', () => {
        // Every non-gesture close funnels through here (backdrop tap, Escape, the
        // nav observers, navigation taps). A close animates first - the host tears
        // the pane's subtree and surface at the marker flip, so the slide has to
        // land before it (closeDrawerAnimated) - while opening stays a plain toggle
        // so the host's own .28s transform transition plays.
        const toggleSidebar = () => {
            toggleDrawer(ctx);
        };
        const drawerOpen = () => {
            const frame = (0, frame_ts_1.getFrame)();
            return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
        };
        const onKeyDown = (event) => {
            if (event.key !== 'Escape')
                return;
            if (document.querySelector('[aria-modal="true"]') !== null)
                return;
            if (drawerOpen())
                toggleSidebar();
        };
        // Capture phase: run before the shell or a plugin processes the click,
        // so takeover panels never render under the open drawer.
        const drawerRoot = () => (0, frame_ts_1.getDrawer)();
        // Shared frame: inside the drawer, on a row navigation target, and not on
        // one of its buttons. Deliberately free of the DSHA tap-close exemption —
        // onDrawerPointerDown arms long-press through this base, and starving that
        // arming would make the host's ⋯ row menu unreachable (#82).
        const isDrawerNavTarget = (target) => {
            if (document.querySelector('[aria-modal="true"]') !== null)
                return false;
            if (!drawerOpen())
                return false;
            if (!(target instanceof Element))
                return false;
            const drawer = drawerRoot();
            if (drawer === null || !drawer.contains(target))
                return false;
            if (target.closest('[class*="sessionRow"] button') !== null)
                return false;
            // 「更多」这类面板行按钮是菜单锚点（aria-haspopup=menu，菜单走 portal）。
            // 它们命中 panelRow 选择器，但点它只该开菜单：收抽屉会把 portal 菜单留在
            // 会话上（2026-10-25 真机：侧边栏「更多」的菜单浮在聊天区）。
            if (target.closest('button[class*="panelRow"][aria-haspopup="menu"]') !== null)
                return false;
            return target.closest(exports.TAP_CLOSE_NAV_SELECTOR) !== null;
        };
        // DSHA_SESSION_INTERACTION_V1：宿主把「单击=选中、双击=打开」拆成了两步
        // （data-dsha-session-select 标记 + dsha-session-open 事件）。上游的
        // 「点行即关抽屉」会在第一次单击就把抽屉收掉，双击永远到不了。
        // 这些行改由 dsha-session-open 事件关闭（见下方 document 监听）。
        // 非 DSHA 宿主没有这个标记，这一条天然不命中。
        // 豁免只属于点行关抽屉的两个调用方（click / pointerup），不得回流进
        // 长按武装门，否则 DSHA 行长按开不了 ⋯ 菜单（#82）。
        const shouldCloseOnTapInsideDrawer = (target) => !(target instanceof Element && target.closest('[data-dsha-session-select]') !== null)
            && isDrawerNavTarget(target);
        // Touch path for session/search rows: never close the drawer from pointer
        // events. Closing at pointerup (or deferring the close) races the browser's
        // synthesized click; some iOS shells suppress that click entirely, so the
        // row's onClick never runs. Instead arm the drawer to close on the *fact*
        // of navigation: when the selected row's title changes, React has already
        // opened the conversation, so the drawer can close safely.
        let lastTouchNavAt = 0;
        let navSignatureAtArm = '';
        let navObserver = null;
        let navTimer = null;
        /** Swallow the host's title double-click rename: on touch a double *tap*
         *  means "open the session" (DSHA's own two-tap detection sits on the row's
         *  click handler), and letting the browser's `dblclick` reach the title would
         *  open the host's rename dialog on that same gesture. Capture phase on
         *  `document`, so the event never reaches React's root container. Only real
         *  (trusted) events are swallowed — nothing here dispatches a synthetic
         *  dblclick since the long-press rename was dropped (2026-10-07). Armed only
         *  inside the mobile environment (this effect is MOBILE_QUERY-gated), so
         *  mouse-driven desktops keep the host behaviour untouched. */
        const onDrawerDoubleClick = (event) => {
            if (!event.isTrusted)
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest('[class*="sessionRow"] [class*="_title"]') === null)
                return;
            event.preventDefault();
            event.stopPropagation();
        };
        const selectedRowSignature = () => {
            const selected = drawerRoot()?.querySelector('[role="treeitem"][aria-selected="true"]');
            const title = selected?.querySelector('[class*="_title"]');
            return title?.textContent?.trim() ?? null;
        };
        const disarmNav = () => {
            navObserver?.disconnect();
            navObserver = null;
            if (navTimer !== null)
                window.clearTimeout(navTimer);
            navTimer = null;
            navSignatureAtArm = '';
        };
        const armNav = () => {
            disarmNav();
            navSignatureAtArm = selectedRowSignature() ?? '';
            const root = drawerRoot();
            if (root === null)
                return;
            navObserver = new MutationObserver(() => {
                if (!drawerOpen()) {
                    disarmNav();
                    return;
                }
                const signature = selectedRowSignature();
                if (signature !== null && signature !== navSignatureAtArm) {
                    disarmNav();
                    toggleSidebar();
                }
            });
            navObserver.observe(root, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['aria-selected'],
            });
            navTimer = window.setTimeout(disarmNav, 2000);
        };
        /** Record where a touch started. The DSHA click replacement in
         *  onDrawerClick needs the down point to tell a tap from a scroll or a
         *  swipe, so every touch pointerdown records it BEFORE any early return. */
        let touchStartedAt = 0;
        const onDrawerPointerDown = (event) => {
            touchStartedAt = performance.now();
            touchDownAt = event.pointerType === 'touch' || event.pointerType === 'pen'
                ? { x: event.clientX, y: event.clientY }
                : null;
        };
        const onDrawerClick = (event) => {
            const target = event.target;
            // A classified swipe already toggled the drawer; never let its
            // synthetic tap also close it / navigate a row (gesture-guard).
            // isStrokeLocked: a stroke axis-locked mid-swipe (audit S0) — the
            // consume marks do not exist until the gesture layer's own pointerup,
            // which runs AFTER this handler on the same release event.
            if ((0, gesture_guard_ts_1.isStrokeLocked)() || (0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            // The backdrop keeps its own listener, but the third-party mobile shim
            // stops click propagation at the frame for anything outside the drawer
            // (its own dismiss path), so that listener never sees the tap. Decide
            // here instead — before both the shim and the element handler.
            if (target instanceof Element && target.closest('[data-mobile-nav="hit"]') !== null) {
                if (drawerOpen())
                    toggleSidebar();
                return;
            }
            // DSHA detail=0 follows the host's keyboard/programmatic open path.
            // Replace the real click, rather than opening on pointerup and allowing
            // the later native click to select again. dsha-session-open owns closing.
            const dshaRow = target instanceof Element
                ? target.closest('[role="treeitem"][data-dsha-session-select]') : null;
            if (dshaRow !== null && event.detail > 0 && isDrawerNavTarget(target)) {
                if (event.defaultPrevented || touchDownAt !== null &&
                    (performance.now() - touchStartedAt >= 500 ||
                        !(0, session_row_fiber_ts_1.isTapWithinSlop)(touchDownAt, { x: event.clientX, y: event.clientY }, TAP_NAV_SLOP_PX)))
                    return;
                event.preventDefault();
                event.stopPropagation();
                disarmNav();
                lastTouchNavAt = performance.now();
                dshaRow.dispatchEvent(new MouseEvent('click', {
                    bubbles: true, cancelable: true, view: window, detail: 0,
                }));
                return;
            }
            // A touch row-tap owns the close (pointerup or the navigation observer);
            // let the row's click reach React without toggling the drawer twice.
            if (performance.now() - lastTouchNavAt < 500)
                return;
            if (shouldCloseOnTapInsideDrawer(target))
                toggleSidebar();
        };
        const onDrawerPointerUp = (event) => {
            // A classified swipe must not arm the nav observer or toggle again
            // (gesture-guard): the drawer already toggled, and the row under the
            // stroke was never a tap. isStrokeLocked covers the release event of
            // a stroke locked mid-swipe but not yet classified — this handler
            // runs before the gesture layer's own pointerup (audit S0/S1: without
            // it the host toggled first and the gesture toggled back, net zero).
            if ((0, gesture_guard_ts_1.isStrokeLocked)() || (0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (!shouldCloseOnTapInsideDrawer(target))
                return;
            const row = target.closest('[role="treeitem"]');
            if (row !== null) {
                lastTouchNavAt = performance.now();
                if (row.getAttribute('aria-selected') === 'true') {
                    // Already-selected row will not navigate; closing immediately is safe.
                    toggleSidebar();
                }
                else {
                    // Unselected row: the row's own onClick navigates; close once the DOM
                    // shows that navigation landed (selected-row title change). The
                    // former store-driven `sessions.open` fallback (#49) is gone: hosts
                    // >=0.2.2 expose no `sessions.open` and publish no `current`, so that
                    // branch could never run.
                    armNav();
                }
                return;
            }
            // Non-row nav targets (newSession / taskboard / ssh / search rows that
            // are not treeitems) are closed by the capture click handler below:
            // closing here would retract the drawer before the browser dispatches
            // the tap's click, and the target's onClick would never run.
        };
        // DSHA 宿主在「真正打开会话」时才派发 dsha-session-open（单击只选中），
        // 所以抽屉的关闭挂在这个事实上，而不是挂在点击上。
        const onDshaSessionOpen = () => {
            if (drawerOpen())
                toggleSidebar();
        };
        document.addEventListener('dsha-session-open', onDshaSessionOpen);
        document.addEventListener('dblclick', onDrawerDoubleClick, true);
        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('click', onDrawerClick, true);
        document.addEventListener('pointerdown', onDrawerPointerDown, true);
        document.addEventListener('pointerup', onDrawerPointerUp, true);
        return () => {
            disarmNav();
            touchDownAt = null;
            document.removeEventListener('dsha-session-open', onDshaSessionOpen);
            document.removeEventListener('dblclick', onDrawerDoubleClick, true);
            document.removeEventListener('keydown', onKeyDown, true);
            document.removeEventListener('click', onDrawerClick, true);
            document.removeEventListener('pointerdown', onDrawerPointerDown, true);
            document.removeEventListener('pointerup', onDrawerPointerUp, true);
        };
    });
}
};
__modules["effects/reconciler-adapter.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installReconciler = installReconciler;
exports.addReconcilerTask = addReconcilerTask;
exports.registerReconcileTasks = registerReconcileTasks;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const reconciler_core_ts_1 = require("./core/reconciler-core.js");
const locales_ts_1 = require("./i18n/locales.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const stats_line_ts_1 = require("./effects/stats-line.js");
const preview_fullscreen_ts_1 = require("./effects/preview-fullscreen.js");
const drawer_layer_ts_1 = require("./effects/drawer-layer.js");
const file_viewer_compat_ts_1 = require("./effects/file-viewer-compat.js");
const drawer_session_log_ts_1 = require("./effects/drawer-session-log.js");
const drawer_quick_settings_ts_1 = require("./effects/drawer-quick-settings.js");
const image_lightbox_zoom_ts_1 = require("./effects/image-lightbox-zoom.js");
const drawer_close_ts_1 = require("./effects/drawer-close.js");
let reconcilerInstalled = false;
let reconcileTasksRegistered = false;
// The DOM-free core owns the task registry, dirty-key routing, and coalesced
// flush scheduling; this module is the thin browser adapter that feeds it
// MutationObserver records and drives its lifecycle from the mobile effect.
const core = (0, reconciler_core_ts_1.createReconcilerCore)({
    requestFrame: (flush) => {
        let id = 0;
        const run = () => {
            id = 0;
            flush();
        };
        id = requestAnimationFrame(run);
        return () => {
            if (id !== 0)
                cancelAnimationFrame(id);
        };
    },
});
/**
 * One full-tree MutationObserver for every mobile DOM reconciler. Tasks can be
 * registered from React or plain effects; they only run while the mobile
 * breakpoint is active and are re-armed automatically on width changes.
 */
function installReconciler(ctx) {
    if (reconcilerInstalled)
        return () => { };
    reconcilerInstalled = true;
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: DOM reconciler', () => {
        // Coalesce every mutation burst (typing, animations, per-token TPS
        // re-renders) into one dirty-key pass per animation frame. Each task
        // declares scopes so only intersecting tasks run on a given flush. The
        // record-to-key mapping is the core's (reconciler-core.ts), so the keys a
        // task declares and the keys this observer produces cannot drift apart.
        const observer = new MutationObserver((records) => {
            const keys = new Set();
            // One batch usually touches a couple of containers (React reuses the
            // message row for every streamed token), so the chat-flow test is cached
            // per element instead of running `closest()` once per record. The cache
            // lives only for this callback: membership can change between batches.
            const inChatFlow = new Map();
            for (const record of records) {
                // DSHA: message history and streaming text do not change shell geometry.
                // This filter drops body records for EVERY key (text included), which is
                // deliberate: it is why the chat flow cannot wake shell-geometry tasks.
                const target = record.target instanceof Element ? record.target : record.target.parentElement;
                if (target === null)
                    continue;
                let inside = inChatFlow.get(target);
                if (inside === undefined) {
                    inside = target.closest('[data-chat-flow]') !== null;
                    inChatFlow.set(target, inside);
                }
                if (inside)
                    continue;
                keys.add((0, reconciler_core_ts_1.mutationDirtyKey)(record));
            }
            if (keys.size > 0)
                core.note(keys);
        });
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            // In-place text rewrites (React updates a text node's value instead of
            // rebuilding the node) only surface as characterData records. Without
            // this flag they never reach note() at all, so a text-only update used to
            // wake nothing; with it they map to TEXT_MUTATION_SCOPE and wake exactly
            // the tasks that declare that key (stats-line reads the TPS text).
            characterData: true,
            attributeFilter: [
                'style',
                'class',
                'data-phase',
                'data-sidebar-collapsed',
                'data-aionui-explorer-open',
                'data-aionui-preview-open',
                'data-mobile-preview-full',
            ],
        });
        core.activate();
        return () => {
            observer.disconnect();
            core.deactivate();
        };
    });
    return () => {
        reconcilerInstalled = false;
    };
}
/** Register a reconciler task. The returned disposer removes it immediately. */
function addReconcilerTask(task) {
    return core.register(task);
}
/**
 * Register the shared DOM reconciler tasks. Returns a disposer that
 * unregisters every task and resets the flag, so a same-environment plugin
 * reload can rebuild the reconciler from scratch.
 *
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts): its system-back
 *   route is registered here so it shares this reconciler, and the FAB reads it
 *   to switch its meaning while a panel owns the main area.
 */
function registerReconcileTasks(ctx, panelExit) {
    if (reconcileTasksRegistered)
        return () => { };
    reconcileTasksRegistered = true;
    const t = ctx.locale.bind(locales_ts_1.NS);
    const removeTasks = [
        addReconcilerTask((0, preview_fullscreen_ts_1.createPreviewFullscreenTask)(t)),
        addReconcilerTask((0, aionui_compat_ts_1.createPreviewCloseTask)()),
        addReconcilerTask((0, aionui_compat_ts_1.createSheetRiseTask)()),
        addReconcilerTask((0, stats_line_ts_1.createStatsLineTask)()),
        addReconcilerTask((0, drawer_layer_ts_1.createDrawerLayerTask)(t, () => (0, drawer_close_ts_1.toggleDrawer)(ctx), panelExit)),
        addReconcilerTask(panelExit.task),
        addReconcilerTask((0, file_viewer_compat_ts_1.createFileViewerMarkerTask)()),
        addReconcilerTask((0, drawer_session_log_ts_1.createDrawerSessionLogTask)(ctx)),
        addReconcilerTask((0, drawer_quick_settings_ts_1.createDrawerQuickSettingsTask)(ctx)),
        addReconcilerTask((0, image_lightbox_zoom_ts_1.createImageLightboxZoomTask)()),
    ];
    return () => {
        for (const remove of removeTasks)
            remove();
        reconcileTasksRegistered = false;
    };
}
};
__modules["platform/visible-band.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.VISIBLE_BAND_EVENT = void 0;
/** Dispatched on `document` right after viewport-meta publishes
 *  `--dsha-mobile-visible-vh`. The publish waits for the keyboard to settle
 *  (two equal frames), so it lands after the last resize event; effects that lay
 *  out against the variable re-measure on this instead of racing it (2026-11-03). */
exports.VISIBLE_BAND_EVENT = 'dsh-mobile-visible-band';
};
__modules["effects/viewport-meta.js"] = function (require, module, exports) {
"use strict";
// Viewport meta ownership + the stable (keyboard-less) viewport height + the
// iOS focus-zoom marker.
//
// 2026-10-12 P1 拆分第五片：由 effects/phone-chrome.ts 逐字搬出（纯搬运，不改行为）。
// 形状：这三段的语句原本全在 installPhoneChrome 的**同一个** `installMobileEffect`
// 回调里，所以这里导出的是三个**普通函数**（各自返回自己的 disposer），由
// installPhoneChrome 按原顺序调用并把三个 disposer 与 theme-color 的清理合成一个。
// 本模块**不**调 installMobileEffect / ctx.effect：各段自持的 matchMedia 会变成
// 「每次外层重新武装就往 ctx 上再注册三个收不到的 effect」的累积注册，也会把
// 一段确定的回调顺序拆成三个监听器各自的（规范未定义的）触发顺序。
Object.defineProperty(exports, "__esModule", { value: true });
exports.STABLE_VIEWPORT_VAR = void 0;
exports.preserveViewportMeta = preserveViewportMeta;
exports.syncStableViewportHeight = syncStableViewportHeight;
exports.applyIosMarker = applyIosMarker;
/** Marker the iOS-only zoom-guard CSS is scoped to (html element). */
const IOS_MARKER = 'data-mobile-nav-ios';
/**
 * Viewport content the plugin owns while the mobile branch is armed.
 * Deliberately zoom-free: iOS 10+ ignores maximum-scale/user-scalable for
 * user pinch but other engines honor them, so writing them would only take
 * zoom away from Android/DSHA; the iOS focus-zoom fix is the >=16px field
 * floor (data-mobile-nav-ios), not a zoom ban (#45).
 *
 * Also deliberately free of `interactive-widget=resizes-content`.
 *
 * That hint asks the engine to shrink the LAYOUT viewport when the keyboard
 * opens, which is the model Android WebView (`adjustResize`) already uses and
 * the one every keyboard measurement in this repo was taken in. It was added
 * on 2026-10-18 to stop Chromium browsers from panning instead. Two things
 * went wrong with keeping it:
 *
 *   - It is a Chromium convenience, not a contract. WebKit only started
 *     implementing it in 2026 (w3c/editing#549: "Webkit team is actively
 *     working on supporting resizes-content and overlays-content"), so the
 *     support that exists is brand new.
 *   - Where it is half-implemented the page is compensated twice. Field data
 *     2026-10-30, 402x684 iPhone (CriOS 156): `innerHeight` 684 -> 345 while
 *     `visualViewport.offsetTop` went to 339, carrying the composer from
 *     578..684 to 239..345 — off the visible band `[339, 684]` — before the
 *     pan settled at 246 and it came back to 362..438.
 *
 * The viewport this plugin actually relies on is `visualViewport`, which every
 * mobile engine implements and whose band `[offsetTop, offsetTop + height]` is
 * the single model the composer lift is written against (team-standard recipe,
 * see keyboard-visibility / the composer lift's own derivation). Asking the
 * engine to also resize the layout buys nothing that band does not already
 * describe, and it is what created the double compensation. So: declare no
 * hint, handle both engine models with the band.
 */
const VIEWPORT_CONTENT = 'width=device-width, initial-scale=1, viewport-fit=cover';
/**
 * CSS custom property carrying the viewport height WITHOUT the soft keyboard
 * (px), maintained by `syncStableViewportHeight()` in this module. Mobile
 * cards that must not move when the keyboard appears size themselves with it
 * instead of a viewport unit — see the settings sheet / shortcut card rules in
 * layout.css.ts.
 *
 * 本模块不自起 effect：`syncStableViewportHeight()` 是返回 disposer 的普通函数，
 * 调用方是 phone-chrome 的 status-bar effect（P1 拆分第五片，2026-10-12）。
 */
const visible_band_ts_1 = require("./platform/visible-band.js");
exports.STABLE_VIEWPORT_VAR = '--dsh-web-mobile-vh';
const findViewportMeta = () => document.querySelector('meta[name="viewport"]');
/** Viewport meta ownership: re-assert our content on every host rewrite, node
 *  replacement, or late injection while the mobile branch is armed. */
function preserveViewportMeta() {
    let originalViewport = null;
    let observedMeta = null;
    // Our own write retriggers the observers; the equality check in
    // assertViewport turns that pass into a no-op. `applying` guards the
    // write itself against re-entrant observer callbacks on exotic engines.
    let applying = false;
    // The plugin owns the meta while armed, so a host rewrite, a node
    // replacement, or a meta that arrives after this effect arms cannot
    // silently drop viewport-fit=cover and shift every surface under the
    // notch. Both observers funnel into the same assertion;
    // attachMetaObserver re-binds to the current node so a replacement keeps
    // being watched.
    const assertViewport = () => {
        const viewport = findViewportMeta();
        if (viewport === null)
            return;
        if (originalViewport === null)
            originalViewport = viewport.content;
        if (applying || viewport.content === VIEWPORT_CONTENT)
            return;
        applying = true;
        viewport.content = VIEWPORT_CONTENT;
        applying = false;
    };
    const metaObserver = new MutationObserver(assertViewport);
    const attachMetaObserver = () => {
        const viewport = findViewportMeta();
        if (viewport === observedMeta)
            return;
        if (observedMeta !== null)
            metaObserver.disconnect();
        observedMeta = viewport;
        if (viewport !== null) {
            metaObserver.observe(viewport, { attributes: true, attributeFilter: ['content'] });
        }
    };
    const headObserver = new MutationObserver(() => {
        attachMetaObserver();
        assertViewport();
    });
    headObserver.observe(document.head, { childList: true });
    attachMetaObserver();
    assertViewport();
    return () => {
        metaObserver.disconnect();
        headObserver.disconnect();
        const viewport = findViewportMeta();
        // Hand the meta back only if it still holds OUR content; a host value
        // written while we were armed wins on dispose.
        if (viewport !== null && originalViewport !== null && viewport.content === VIEWPORT_CONTENT) {
            viewport.content = originalViewport;
        }
    };
}
/** The keyboard-less viewport height (STABLE_VIEWPORT_VAR). */
function syncStableViewportHeight() {
    const root = document.documentElement;
    let stableVh = 0;
    let stableWidth = 0;
    // Writing a custom property on :root invalidates style for the WHOLE
    // document, so the value is compared before the property is touched: a
    // width change that leaves the height alone (split-screen resize, a
    // rotation that lands back on the same height) used to re-run the document
    // style pass for an identical string. Measured 2026-10-09 on Android 16
    // WebView: the monotonic rule above already suppresses every keyboard
    // driven write (the keyboard only lowers the height, and a lower height
    // never passes the growth test), so no per-frame dedup is needed here —
    // this guard only removes the redundant identical writes.
    let stableValue = '';
    let pendingVisible = '';
    let pendingVisibleFrames = 0;
    let pendingVisibleFrame = 0;
    // 2026-11-03：发布时机晚于最后一次 resize 事件（要等两帧确认），而读这个变量排版的
    // 效果（提问卡全屏带）只在 resize 时量。没有这个通知，它们最后一次测量量到的是
    // 发布前的旧几何——录屏实测：键盘收起后全屏卡片停在键盘高度不回来。
    const publishVisible = (value) => {
        root.style.setProperty('--dsha-mobile-visible-vh', value);
        document.dispatchEvent(new CustomEvent(visible_band_ts_1.VISIBLE_BAND_EVENT));
    };
    const syncStableViewport = () => {
        const height = window.innerHeight;
        const width = window.innerWidth;
        const visible = window.visualViewport;
        const available = Math.max(1, Math.min(height, visible?.height ?? height));
        const visibleValue = `${available}px`;
        const topValue = `${visible?.offsetTop ?? 0}px`;
        // Only publish the band height once it STOPS MOVING (two consecutive frames
        // agree). The shell is sized from this variable, and a custom property on
        // :root invalidates style for the whole document — following the keyboard's
        // 300ms rise frame by frame re-laid-out the entire app ~20 times and the
        // conversation list painted blank (white) for 1-2s on the owner's phone
        // (2026-10-31). Publishing on settle costs one resize, right after the
        // keyboard stops, and the animation itself is left to the compositor.
        // 2026-11-04 店主报「键盘收起/弹起时闪一下」（录屏逐帧：整页跳一帧再回来；二分定位到本处两帧确认）：
        // DSHA 壳不逐帧改 WebView 尺寸，键盘动画的起止各一步跳到终值（WebFullscreenUi 的 IME 内边距）。
        // 只等停稳才发布，外壳就比视口晚 ≥2 帧，中间那帧按旧高度排版 = 闪。所以静止后的**第一次**变化立即发布
        // （一步跳变的引擎这就是终值），动画中的后续帧仍不写，停稳再收拢一次 —— 逐帧动画的引擎每次键盘动画
        // 最多写两次，不回到 2026-10-31 的逐帧重排。
        const atRest = pendingVisibleFrames >= 2;
        pendingVisibleFrames = visibleValue === pendingVisible ? pendingVisibleFrames + 1 : 0;
        pendingVisible = visibleValue;
        if ((atRest || pendingVisibleFrames >= 2) &&
            root.style.getPropertyValue('--dsha-mobile-visible-vh') !== visibleValue)
            publishVisible(visibleValue);
        // The keyboard stops without announcing it: the last resize event still sees
        // a fresh value, so confirmation has to come from a frame of its own. One
        // rAF at a time; during the animation each tick re-arms exactly once.
        // 立即发布之后也要继续确认（不是 else）：否则计数停在 0，下一次键盘动画不算「静止后」，又会晚两帧。
        if (pendingVisibleFrames < 2 && pendingVisibleFrame === 0)
            pendingVisibleFrame = requestAnimationFrame(() => {
                pendingVisibleFrame = 0;
                syncStableViewport();
            });
        if (root.style.getPropertyValue('--dsha-mobile-viewport-top') !== topValue)
            root.style.setProperty('--dsha-mobile-viewport-top', topValue);
        if (stableVh === 0 || height > stableVh || width !== stableWidth) {
            stableVh = height;
            stableWidth = width;
            const next = `${height}px`;
            if (next !== stableValue) {
                stableValue = next;
                root.style.setProperty(exports.STABLE_VIEWPORT_VAR, next);
            }
        }
    };
    syncStableViewport();
    window.addEventListener('resize', syncStableViewport);
    window.visualViewport?.addEventListener('resize', syncStableViewport);
    window.visualViewport?.addEventListener('scroll', syncStableViewport);
    return () => {
        window.removeEventListener('resize', syncStableViewport);
        window.visualViewport?.removeEventListener('resize', syncStableViewport);
        window.visualViewport?.removeEventListener('scroll', syncStableViewport);
        if (pendingVisibleFrame !== 0)
            cancelAnimationFrame(pendingVisibleFrame);
        pendingVisibleFrame = 0;
        root.style.removeProperty('--dsha-mobile-visible-vh');
        root.style.removeProperty('--dsha-mobile-viewport-top');
        stableValue = '';
        root.style.removeProperty(exports.STABLE_VIEWPORT_VAR);
    };
}
/** iOS-only: mark documentElement so the stylesheet can hold text fields at
 *  >=16px and Safari never focus-zooms the viewport (#45). */
function applyIosMarker(isIosWebKit) {
    if (!isIosWebKit)
        return undefined;
    const root = document.documentElement;
    root.setAttribute(IOS_MARKER, '');
    return () => {
        root.removeAttribute(IOS_MARKER);
    };
}
};
__modules["effects/phone-chrome.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ensureDismissShadow = ensureDismissShadow;
exports.installFrameController = installFrameController;
exports.installPhoneChrome = installPhoneChrome;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const device_ts_1 = require("./platform/device.js");
const frame_ts_1 = require("./platform/frame.js");
const header_metrics_ts_1 = require("./effects/header-metrics.js");
const reconciler_adapter_ts_1 = require("./effects/reconciler-adapter.js");
const viewport_meta_ts_1 = require("./effects/viewport-meta.js");
/** The third-party mobile compat shim shipped inside `@linxin666/dsh-web-all`
 *  collapses the drawer on ANY click inside `[role="treeitem"]` at ≤768px by
 *  clicking the host's logo-row toggle — with no `_rowActions` exemption, so a
 *  tap on a row's ⋯ closed the drawer instead of opening its menu (2026-09-14;
 *  its sibling implementation inside `@linxin666/dsh-remote-web-ui` does exempt
 *  the row actions). It resolves that toggle with
 *  `frame.querySelector('[data-dsh-responsive-part="sidebar-toggle"]')`, so an
 *  inert element carrying the same stamp EARLIER in tree order turns every one
 *  of its dismiss calls into a no-op and leaves dismiss ownership to us (row
 *  taps close through the navigation observer, backdrop taps through the
 *  capture click path). Gated on the shim's own stamp: hosts without it stay
 *  untouched. */
const HOST_TOGGLE_SELECTOR = '[data-dsh-responsive-part="sidebar-toggle"]:not([data-mobile-nav])';
const DISMISS_SHADOW_SELECTOR = '[data-mobile-nav="dismiss-shadow"]';
function ensureDismissShadow() {
    if (typeof document === 'undefined')
        return;
    const shadow = document.querySelector(DISMISS_SHADOW_SELECTOR);
    const real = document.querySelector(HOST_TOGGLE_SELECTOR);
    const pane = real?.closest('[data-pane="sidebar"]') ?? null;
    if (real === null || pane === null) {
        shadow?.remove();
        return;
    }
    // Must stay a no-op once in place: the reconciler observes the whole tree and
    // a task that mutates on every flush would re-trigger itself forever.
    if (shadow !== null && shadow.parentElement === pane && pane.firstElementChild === shadow)
        return;
    const element = shadow ?? document.createElement('span');
    if (shadow === null) {
        element.setAttribute('data-mobile-nav', 'dismiss-shadow');
        element.setAttribute('data-dsh-responsive-part', 'sidebar-toggle');
        element.setAttribute('aria-hidden', 'true');
        // The shim's collapsed-rail rule forces `display: inline-flex !important`
        // on anything carrying the stamp; only an inline !important outranks it.
        element.style.setProperty('display', 'none', 'important');
    }
    pane.insertBefore(element, pane.firstElementChild);
}
/**
 * Frame marker controller: owns `data-mobile-nav="frame"` and every plugin
 * marker that can survive on the shell-owned frame. Installed once at apply
 * time so effects no longer each need to find/set/clear the frame. Returns a
 * disposer that unregisters the task and resets the installed flag, so a
 * same-environment plugin reload can rebuild the reconciler from scratch.
 * (The host-generation probe this controller used to call was dead code —
 * nothing ever read `data-mobile-nav-gen`, and the plugin deliberately does
 * not yield the drawer to the host's one: see docs/maintenance/pitfalls.md
 * §0.1.5 抽屉 z 与遮罩.)
 */
function installFrameController() {
    if (frameControllerInstalled)
        return () => { };
    frameControllerInstalled = true;
    let frame = null;
    const removeTask = (0, reconciler_adapter_ts_1.addReconcilerTask)({
        name: 'frame-marker',
        scopes: ['*'],
        ensure: () => {
            frame = (0, frame_ts_1.findFrame)();
            if (frame !== null && !frame.hasAttribute('data-mobile-nav')) {
                frame.setAttribute('data-mobile-nav', 'frame');
            }
            ensureDismissShadow();
        },
        dispose: () => {
            if (frame !== null) {
                frame.removeAttribute('data-mobile-nav');
                frame.removeAttribute('data-mobile-preview-full');
                frame.removeAttribute('data-aionui-explorer-open');
                frame.removeAttribute('data-aionui-preview-open');
            }
            if (typeof document !== 'undefined') {
                document.querySelector(DISMISS_SHADOW_SELECTOR)?.remove();
            }
            frame = null;
        },
    });
    const removeHeaderMetrics = (0, reconciler_adapter_ts_1.addReconcilerTask)((0, header_metrics_ts_1.createHeaderMetricsTask)());
    return () => {
        removeHeaderMetrics();
        removeTask();
        frameControllerInstalled = false;
    };
}
let frameControllerInstalled = false;
/**
 * Phone chrome: KEEP the system status bar (no fullscreen) and make it
 * blend into the page. On narrow screens:
 * - The viewport meta is OWNED by the plugin while armed (viewport-meta.ts):
 *   width=device-width, initial-scale=1, viewport-fit=cover,
 *   without interactive-widget=resizes-content (dropped 2026-10-30: WebKit's
 *   implementation is new and where it is half-done the page is compensated
 *   twice — the plugin models the keyboard with visualViewport instead),
 *   re-asserted on every host
 *   rewrite, node replacement, or late injection, so
 *   env(safe-area-inset-top) stays the real status-bar / notch height
 *   instead of silently going stale when the host touches the meta. No zoom
 *   tokens here: iOS 10+ ignores them for user pinch but other engines
 *   honor them, and the focus-zoom fix is the >=16px field floor (#45), not
 *   a zoom ban. Dispose restores the host's own content as observed at arm
 *   time.
 * - A theme-color meta tracks the shell background (the official theme is
 *   toggled by body[data-ds-dark-theme], which flips --dsw-alias-bg-base):
 *   Android then paints the status bar / URL bar with the page's own base
 *   color, so the status bar reads as part of the UI instead of a foreign
 *   strip. The drawer paints the same strip on iOS / notch displays.
 * - documentElement carries data-mobile-nav-ios on iOS WebKit so the
 *   stylesheet can hold every text field at >=16px and Safari never
 *   focus-zooms the viewport (#45). Double-tap zoom is off through
 *   touch-action; pinch zoom stays available on purpose — it is the only way
 *   back out of a zoom the browser applied on its own.
 */
/**
 * 手机档启动时把宿主的侧栏收起来（2026-11-06 真机/浏览器：局域网在浏览器打开时，
 * 宿主的 narrow 模式默认处于「展开」，而本插件的手机版几何是按「收起=抽屉」写的，
 * 于是侧栏以桌面形态压在主区上，右边只剩一条会话、中间一大片空白）。
 *
 * 只做一次：本次页面生命周期里第一次武装之后，如果侧栏不是收起态，就点一次宿主的
 * 折叠开关。用户之后自己拉开/收起都尊重现状，不会被反复纠正。
 */
function collapseSidebarOnStartup(ctx) {
    let done = false;
    (0, reconciler_adapter_ts_1.addReconcilerTask)({
        name: 'collapse-sidebar-on-startup',
        scopes: ['*', 'data-sidebar-collapsed'],
        ensure: () => {
            if (done)
                return;
            const frame = (0, frame_ts_1.findFrame)();
            if (frame === null)
                return;
            done = true;
            if (frame.hasAttribute('data-sidebar-collapsed'))
                return;
            ctx.layout.toggleSidebar();
        },
        dispose: () => { },
    });
}
function installPhoneChrome(ctx) {
    collapseSidebarOnStartup(ctx);
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: status bar theme + viewport + zoom guard', () => {
        const themeMeta = document.createElement('meta');
        themeMeta.name = 'theme-color';
        const bodyBg = () => getComputedStyle(document.body).backgroundColor;
        const cssSupports = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
            ? (condition) => CSS.supports(condition)
            : null;
        themeMeta.content = bodyBg();
        if (themeMeta.parentElement === null)
            document.head.appendChild(themeMeta);
        const observer = new MutationObserver(() => {
            themeMeta.content = bodyBg();
        });
        observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] });
        // 武装顺序照 876 行原始版：viewport meta → iOS 标记 → 稳定视口高度。
        // 这个次序不是排版偏好：viewport meta 的所有权会改视口，而稳定视口高度读的是
        // window.innerHeight，所以「先认领 meta、最后读高度」才是原行为。
        const stopViewportMeta = (0, viewport_meta_ts_1.preserveViewportMeta)();
        const stopIosMarker = (0, viewport_meta_ts_1.applyIosMarker)((0, device_ts_1.detectIosWebKit)(navigator, cssSupports));
        const stopStableViewport = (0, viewport_meta_ts_1.syncStableViewportHeight)();
        // 清理顺序同样照原版——它**不是**武装顺序的逆序，而是三块与 theme-color 清理
        // 交错的固定序：稳定视口 → observer → 恢复 viewport meta → 摘 themeMeta →
        // 摘 iOS 标记。故逐位写，不写成 for 循环。
        return () => {
            stopStableViewport();
            observer.disconnect();
            stopViewportMeta();
            themeMeta.remove();
            stopIosMarker?.();
        };
    });
}
};
__modules["effects/selection-chrome.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSelectionChromeYield = installSelectionChromeYield;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * Marker set on <html> while a non-collapsed text selection lives in the
 * conversation. CSS uses it to drop the header out of the hit test (see
 * layout.css.ts "selection handle drag: keep the extent local"):
 * the native Android handle drag resolves the selection extent by hit-testing
 * the handle position, and an unselectable bar sitting there makes Blink walk
 * FORWARD in DOM order to the first selectable node - measured on the live host
 * as the flow's very first item (the "Load earlier" gate) - which the browser
 * then reveals, so the viewport teleports to the top of the conversation instead
 * of extending the selection by a line. Drop the bar out of the hit test and the
 * same point resolves to the message the lifted scroll box now puts there.
 */
const SELECTING_ATTR = 'data-mobile-nav-selecting';
/** True while the document selection is a non-collapsed range inside the
 *  conversation (composer / drawer selections do not count - they must keep
 *  their own chrome interactive). Cheap by design: no layout reads, because
 *  selectionchange fires on every handle move. */
function selectionLivesInConversation() {
    const selection = window.getSelection();
    if (selection === null || selection.isCollapsed)
        return false;
    const node = selection.anchorNode;
    if (node === null)
        return false;
    const element = node instanceof HTMLElement ? node : node.parentElement;
    if (element === null)
        return false;
    if (element.closest('[data-composer-card]') !== null)
        return false;
    return element.closest('[data-phase]') !== null;
}
/** Toggle the selection marker for the duration of a conversation selection. */
function installSelectionChromeYield(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: selection chrome yield', () => {
        if (typeof document === 'undefined')
            return undefined;
        const root = document.documentElement;
        const sync = () => {
            const wanted = selectionLivesInConversation();
            if (wanted === root.hasAttribute(SELECTING_ATTR))
                return;
            if (wanted)
                root.setAttribute(SELECTING_ATTR, '');
            else
                root.removeAttribute(SELECTING_ATTR);
        };
        document.addEventListener('selectionchange', sync);
        sync();
        return () => {
            document.removeEventListener('selectionchange', sync);
            root.removeAttribute(SELECTING_ATTR);
        };
    });
}
};
__modules["effects/subagent-chip-touch.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSubagentChipTouch = installSubagentChipTouch;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * Touch support for the lineage-count chip ("N 个子代理") that
 * `dsh-client-ui-subagent` renders in the session header.
 *
 * Upstream history (both observed live on the served bundle):
 *
 * 1. The original count-variant trigger shipped without an onClick handler
 *    (`onClick: openTitle === void 0 ? void 0 : …`) and drove its card purely
 *    through onMouseEnter/onMouseLeave hover timers — enter arms a 150 ms
 *    open timer, leave arms a 120 ms close timer, and each cancels the
 *    other. On touch devices every tap makes the browser synthesize paired
 *    mouseenter/mouseleave from its tracked mouse position, which usually
 *    differs from the tap point: taps did nothing, or the card popped back
 *    open ~200 ms after an outside close (the “点了没反应 / 自弹回” era).
 *
 * 2. A later host removed the hover timers and gave the trigger
 *    a native `onClick: () => changeOpen(!open)`. A phone tap now crosses
 *    TWO toggle sources: the browser fires pointerup first (this shim
 *    dispatches the synthetic ArrowDown there, capture phase — BEFORE the
 *    click), which opens the card through the component's own keyboard
 *    path, and then the tap's click reaches the native onClick, which
 *    toggles the card right back shut. The two toggles cancel each other:
 *    the panel flashes open for a frame and is gone (「闪退」), and the
 *    chip reads as unresponsive.
 *
 * Fix strategy, scoped to touch pointers (mouse users keep native hover):
 * 1. Toggle the card ourselves along the component's own keyboard path —
 *    ArrowDown keydown on the trigger opens (+focus first row), Escape
 *    closes (both verified against the live component in both upstream
 *    versions). React delivers dispatched KeyboardEvents to onKeyDown like
 *    any bubbling event.
 * 2. Swallow the tap's own follow-up click on the trigger we just toggled,
 *    so a native onClick (era 2) can never cancel the keyboard-path
 *    toggle. On the hover-only build the click never toggled anything, so
 *    swallowing it is a no-op — one deterministic toggle per tap across
 *    both upstreams.
 * 3. For a short window after every touch pointer activity, swallow trusted
 *    synthesized mouseover/out/enter/leave events targeting the lineage
 *    root or its menu, so era-1 hover timers can neither cancel our toggle
 *    nor resurrect a just-closed card (no-op on rc.6, which has no hover
 *    timers at all).
 */
/** Count-variant trigger only: the switcher variant has its own onClick. */
const CHIP_TRIGGER_SELECTOR = '[data-mobile-nav="frame"] button[class*="_trigger"][aria-haspopup="tree"][aria-expanded]:not([class*="_switcherTrigger"])';
/**
 * Lineage root plus its menu (dsh-client-ui-subagent 0.2.2: ZKlsPq_).
 * The pre-0.2 h8S2Va_ fallback was removed with the low-generation
 * branches. Audit these selectors when the host package upgrades.
 */
const HOVER_SUBTREE_SELECTOR = '[class*="ZKlsPq_root"], [class*="ZKlsPq_menu"]';
/** How long after touch activity synthesized hover events stay suppressed. */
const SWALLOW_WINDOW_MS = 800;
/**
 * How long the tap's follow-up click stays suppressed on the trigger we
 * toggled through the keyboard path. A touch click lands a few ms after its
 * pointerup; 1 s is a generous upper bound that still expires before the
 * user's next deliberate tap.
 */
const CLICK_GRACE_MS = 1000;
const SWALLOWED_TYPES = [
    'mouseover',
    'mouseout',
    'mouseenter',
    'mouseleave',
    // 2026-11-08 (#281): the same hover timers moved to the POINTER channel —
    // the lineage root and the menu container now carry onPointerLeave →
    // scheduleHoverClose (120 ms). Swallowing only the mouse family leaves the
    // tap's own pointerout/pointerleave untouched, and because the keyboard path
    // never sets the component's pinnedRef, that timer closes the card we just
    // opened (CDP: expanded is true right after pointerup, still true at 60 ms,
    // false by 150 ms; blocking these two events keeps it open).
    'pointerout',
    'pointerleave',
];
function installSubagentChipTouch(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: lineage chip touch toggle', () => {
        if (typeof PointerEvent === 'undefined')
            return undefined;
        let swallowUntil = 0;
        const armSwallowWindow = () => {
            swallowUntil = Date.now() + SWALLOW_WINDOW_MS;
        };
        // The trigger whose tap we just toggled through the keyboard path, and
        // how long that tap's follow-up click must be suppressed on it.
        let toggledTrigger = null;
        let toggledUntil = 0;
        const onPointerUp = (event) => {
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            armSwallowWindow();
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const trigger = target.closest(CHIP_TRIGGER_SELECTOR);
            if (trigger === null)
                return;
            const open = trigger.getAttribute('aria-expanded') === 'true';
            // The component's own keyboard path: navigate() treats ArrowDown as
            // open (+focus first row) and Escape as close-with-focus-restore.
            trigger.dispatchEvent(new KeyboardEvent('keydown', {
                key: open ? 'Escape' : 'ArrowDown',
                bubbles: true,
                cancelable: true,
            }));
            toggledTrigger = trigger;
            toggledUntil = Date.now() + CLICK_GRACE_MS;
        };
        /**
         * The tap's own click must not re-toggle the trigger: on 0.1.0-rc.6 the
         * trigger carries a native onClick (changeOpen(!open)) that would cancel
         * the keyboard-path toggle fired on pointerup — the flash-and-close
         * race. stopPropagation() at document capture blocks the click from
         * reaching the container-level React delegation (so the trigger's
         * onClick never runs) while letting other document listeners observe it.
         * Identity-checked, so taps on menu rows or anywhere else pass through
         * untouched.
         */
        const onClick = (event) => {
            if (toggledTrigger === null)
                return;
            if (Date.now() >= toggledUntil) {
                toggledTrigger = null;
                return;
            }
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(CHIP_TRIGGER_SELECTOR) !== toggledTrigger)
                return;
            toggledTrigger = null;
            event.stopPropagation();
        };
        const onAnyPointerActivity = (event) => {
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            armSwallowWindow();
            void event;
        };
        const swallowSyntheticHover = (event) => {
            if (Date.now() >= swallowUntil)
                return;
            if (!event.isTrusted)
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(HOVER_SUBTREE_SELECTOR) === null)
                return;
            event.stopImmediatePropagation();
        };
        document.addEventListener('pointerdown', onAnyPointerActivity, true);
        document.addEventListener('pointerup', onPointerUp, true);
        document.addEventListener('click', onClick, true);
        for (const type of SWALLOWED_TYPES) {
            document.addEventListener(type, swallowSyntheticHover, true);
        }
        return () => {
            document.removeEventListener('pointerdown', onAnyPointerActivity, true);
            document.removeEventListener('pointerup', onPointerUp, true);
            document.removeEventListener('click', onClick, true);
            for (const type of SWALLOWED_TYPES) {
                document.removeEventListener(type, swallowSyntheticHover, true);
            }
        };
    });
}
};
__modules["effects/session-menu.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSessionMenuDelete = installSessionMenuDelete;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const frame_ts_1 = require("./platform/frame.js");
const drawer_close_ts_1 = require("./effects/drawer-close.js");
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const locales_ts_1 = require("./i18n/locales.js");
const session_row_fiber_ts_1 = require("./effects/session-row-fiber.js");
/** The ui-workspace dictionary namespace the host session menu labels come from. */
const WORKSPACE_NS = 'workspace';
/** Marker on the injected menu item (idempotence across React re-renders). */
const DELETE_ITEM_MARKER = 'data-mobile-nav="session-delete"';
/** Danger accent read from the theme, with a fixed fallback. */
const DANGER_COLOR = 'var(--dsw-alias-state-error-primary, #b91c1c)';
/** 16px outline trash glyph (IconTrashOutline16 path), currentColor-filled. */
const TRASH_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M14.4782 4.84067L14.2138 10.1152C14.1102 12.1872 14.067 13.0115 13.3866 13.9607C13.1044 14.3546 12.7498 14.6912 12.3424 14.9535C11.8239 15.2872 11.2415 15.4316 10.5585 15.4998C9.88727 15.5668 9.04946 15.5656 7.99998 15.5656C6.95051 15.5656 6.1127 15.5668 5.44142 15.4998C4.75851 15.4316 4.17602 15.2872 3.65753 14.9535C3.25012 14.6912 2.89559 14.3546 2.61332 13.9607C1.93296 13.0115 1.88979 12.1872 1.78619 10.1152L1.52179 4.84067L2.89006 4.77277L3.15343 10.0463C3.26221 12.2218 3.32452 12.6015 3.72646 13.1624C3.90825 13.4161 4.13686 13.6334 4.39927 13.8023C4.66204 13.9714 5.00263 14.0792 5.57825 14.1367C6.16562 14.1953 6.92298 14.1963 7.99998 14.1963C9.07699 14.1963 9.83434 14.1953 10.4217 14.1367C10.9973 14.0792 11.3379 14.1367 11.6007 13.8023C11.8631 13.6334 12.0917 13.4161 12.2735 13.1624C12.6755 12.6015 12.7378 12.2218 12.8465 10.0463L13.1099 4.77277L14.4782 4.84067ZM5.43011 6.22849H6.7994V11.3909H5.43011V6.22849ZM9.20056 6.22849H10.5699V11.3909H9.20056V6.22849ZM8.53597 0.434431C9.17976 0.434431 9.6522 0.426926 10.0966 0.571258C10.2357 0.616451 10.3717 0.672554 10.502 0.738948C10.9182 0.951107 11.2464 1.29099 11.7015 1.74612L12.4978 2.54136H15.3742V3.91169H0.625732V2.54136H3.50218L4.29845 1.74612C4.75358 1.29099 5.08174 0.951107 5.49801 0.738948C5.62831 0.672554 5.76425 0.616451 5.90334 0.571258C6.34776 0.426926 6.82021 0.434431 7.46399 0.434431H8.53597ZM7.46399 1.80476C6.73208 1.80476 6.51641 1.81187 6.32617 1.87369C6.25545 1.89667 6.18668 1.92533 6.12041 1.95907C5.96398 2.03878 5.82348 2.16253 5.44142 2.54136H10.5585C10.1765 2.16253 10.036 2.03878 9.87955 1.95907C9.81329 1.92533 9.74452 1.89667 9.6738 1.87369C9.48356 1.81187 9.26789 1.80476 8.53597 1.80476H7.46399Z" fill="currentColor" /></svg>';
/** Escape text destined for innerHTML (session titles are user content). */
function escapeHtml(value) {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
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
function installSessionMenuDelete(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: session-menu delete', () => {
        const navT = ctx.locale.bind(locales_ts_1.NS);
        // Host workspace-browser dictionary for menu-signature detection. Bound
        // lazily so a later-registered dictionary is picked up; the general
        // overload accepts the raw namespace id.
        const wsT = (key, params) => ctx.locale.bind(WORKSPACE_NS)(key, params);
        let anchor = null;
        let injectRaf = 0;
        let dialogHost = null;
        let closeDialogOnKey = null;
        // 请求身份（epoch），每次确认点击自增。更晚的删除一发起，在途响应即过期 ——
        // 过期结果不得 closeDialog / 收抽屉，否则会拿旧响应改新对话框与新当前会话。
        let deleteEpoch = 0;
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
        const rowSessionId = (row) => {
            const rowKey = row.getAttribute('data-row-key') ?? '';
            if (rowKey.startsWith('session:')) {
                const id = rowKey.slice('session:'.length);
                if (id !== '')
                    return id;
            }
            const stamped = row.getAttribute('data-dsha-session-select');
            if (stamped !== null && stamped !== '')
                return stamped;
            return (0, session_row_fiber_ts_1.findSessionIdInFiber)((0, session_row_fiber_ts_1.reactFiberOf)(row), isKnownSessionId);
        };
        /** Read one menu item's visible label from its `_itemLabel` span. */
        const itemLabel = (item) => item.querySelector('[class*="_itemLabel"]')?.textContent?.trim() ?? '';
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
        const isSessionMenu = (menu) => {
            const labels = [...menu.querySelectorAll('[role="menuitem"]')]
                .map(itemLabel);
            const rename = wsT('rename');
            const fork = wsT('menu.fork');
            return labels.includes(rename) && labels.includes(fork)
                && labels.includes(wsT('menu.archiveSession'));
        };
        const closeDialog = () => {
            if (closeDialogOnKey !== null) {
                document.removeEventListener('keydown', closeDialogOnKey, true);
                closeDialogOnKey = null;
            }
            if (dialogHost !== null) {
                dialogHost.backdrop.remove();
                dialogHost.card.remove();
                dialogHost = null;
            }
        };
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
        const showDeleteDialog = (sessionId, title) => {
            closeDialog();
            const host = document.body;
            const backdrop = document.createElement('div');
            backdrop.dataset.mobileNav = 'delete-dialog-backdrop';
            const card = document.createElement('div');
            card.dataset.mobileNav = 'delete-dialog';
            card.setAttribute('role', 'dialog');
            card.setAttribute('aria-modal', 'true');
            card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteConfirmTitle'))}</div>
        <div data-mobile-nav="delete-confirm-desc">${escapeHtml(navT('deleteConfirmDesc', { title }))}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
          <button type="button" data-mobile-nav="delete-confirm-yes">${escapeHtml(navT('deleteConfirmYes'))}</button>
        </div>
        <div data-mobile-nav="delete-error" role="alert" hidden></div>`;
            const noButton = card.querySelector('[data-mobile-nav="delete-confirm-no"]');
            const yesButton = card.querySelector('[data-mobile-nav="delete-confirm-yes"]');
            const errorLine = card.querySelector('[data-mobile-nav="delete-error"]');
            noButton?.addEventListener('click', closeDialog);
            // The card is a CHILD of the backdrop (the CSS centers it through the
            // backdrop's flex), so close only on genuine backdrop taps — without
            // the target guard every card click (the async yes tap included) would
            // bubble here and close the dialog before the fetch settles, killing
            // the pending state and the error display path.
            backdrop.addEventListener('click', (event) => {
                if (event.target !== backdrop)
                    return;
                closeDialog();
            });
            const onKey = (event) => {
                if (event.key === 'Escape')
                    closeDialog();
            };
            document.addEventListener('keydown', onKey, true);
            closeDialogOnKey = onKey;
            const resetButtons = () => {
                if (yesButton !== null) {
                    yesButton.disabled = false;
                    yesButton.textContent = navT('deleteConfirmYes');
                }
                if (noButton !== null)
                    noButton.disabled = false;
            };
            const fail = (message) => {
                if (errorLine !== null) {
                    errorLine.textContent = message;
                    errorLine.hidden = false;
                }
                resetButtons();
            };
            const mapError = (payload, reason) => {
                const code = payload?.error?.code;
                if (code === 'session-not-found')
                    return navT('deleteErrorNotFound');
                if (code === 'session-busy')
                    return navT('deleteErrorBusy');
                const message = payload?.error?.message ?? (reason instanceof Error ? reason.message : String(reason));
                return navT('deleteErrorGeneric', { message });
            };
            yesButton?.addEventListener('click', async () => {
                yesButton.disabled = true;
                if (noButton !== null)
                    noButton.disabled = true;
                yesButton.textContent = navT('deletePending');
                if (errorLine !== null)
                    errorLine.hidden = true;
                // 请求身份：本次点击的序号。期间又发起删除时，这次的结果就过期了。
                const epoch = ++deleteEpoch;
                try {
                    const response = await fetch('/api/mobile-nav.session.delete', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ sessionId }),
                    });
                    const payload = await response.json().catch(() => null);
                    if (!response.ok || payload === null || payload.ok !== true) {
                        fail(mapError(payload, new Error(`HTTP ${response.status}`)));
                        return;
                    }
                }
                catch (reason) {
                    // A rejected fetch proves nothing (DSHA's host half aborts the reply
                    // after the trash move — see verifySessionDeleted). Ask the session
                    // list instead; only a session that survives every re-read failed.
                    // Verification itself must not become the new failure mode.
                    let landed = false;
                    try {
                        landed = await (0, sessions_compat_ts_1.verifySessionDeleted)({
                            listed: () => ctx.sessions.list.getSnapshot()?.byId?.[sessionId] !== undefined,
                            // Called AS A METHOD: refresh() reads `this.manager`.
                            refresh: async () => {
                                await ctx.sessions.refresh?.();
                            },
                            sleep: (ms) => new Promise((resolve) => {
                                window.setTimeout(() => { resolve(); }, ms);
                            }),
                        });
                    }
                    catch {
                        landed = false;
                    }
                    if (!landed) {
                        fail(mapError(null, reason));
                        return;
                    }
                }
                // 「是否仍是当前会话」必须在**请求完成时**重新核对：请求期间用户可能已经切到
                // 别的会话，用请求前算好的旧值会把新当前会话所在的抽屉收掉（2026-10-11）。
                // （宿主 >=0.2.2 没有 sessions.clear()，旧代的「清选中态」一步已随下限删除。）
                const wasCurrent = (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) === sessionId;
                // 过期结果（期间又发起过删除）只丢弃，不关别人的对话框、也不动状态。
                if (epoch !== deleteEpoch)
                    return;
                closeDialog();
                // Repull the baseline so the deleted row disappears; called AS A METHOD
                // on ctx.sessions because refresh() reads `this.manager`.
                const sessions = ctx.sessions;
                try {
                    await sessions.refresh?.();
                }
                catch (reason) {
                    // 列表重拉失败只影响刷新，不代表删除失败 —— 单独 warn，也不许变成未处理 rejection。
                    console.warn('[dsh-web-mobile] session list refresh after delete failed:', reason);
                }
                // 手机档抽屉里就是列表，删完当前会话顺手收起；桌面档的面板不得被收起（MOBILE_QUERY
                // 门保持）。只有抽屉**确实开着**才关：盲 toggle 会把已关的抽屉重新打开（2026-10-11）。
                const frame = (0, frame_ts_1.getFrame)();
                if (frame === null || frame.hasAttribute('data-sidebar-collapsed'))
                    return;
                if (wasCurrent && window.matchMedia(breakpoints_ts_1.MOBILE_QUERY).matches)
                    (0, drawer_close_ts_1.toggleDrawer)(ctx);
            });
            host.appendChild(backdrop);
            backdrop.appendChild(card);
            dialogHost = { backdrop, card };
        };
        /** Show a non-destructive error card (session could not be resolved). */
        const showError = (message) => {
            closeDialog();
            const host = document.body;
            const backdrop = document.createElement('div');
            backdrop.dataset.mobileNav = 'delete-dialog-backdrop';
            const card = document.createElement('div');
            card.dataset.mobileNav = 'delete-dialog';
            card.setAttribute('role', 'dialog');
            card.setAttribute('aria-modal', 'true');
            card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteSession'))}</div>
        <div data-mobile-nav="delete-error" role="alert">${escapeHtml(message)}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
        </div>`;
            card.querySelector('[data-mobile-nav="delete-confirm-no"]')?.addEventListener('click', closeDialog);
            // Same child-of-backdrop target guard as showDeleteDialog: error-card
            // taps must not bubble into the backdrop's close.
            backdrop.addEventListener('click', (event) => {
                if (event.target !== backdrop)
                    return;
                closeDialog();
            });
            const onKey = (event) => {
                if (event.key === 'Escape')
                    closeDialog();
            };
            document.addEventListener('keydown', onKey, true);
            closeDialogOnKey = onKey;
            host.appendChild(backdrop);
            backdrop.appendChild(card);
            dialogHost = { backdrop, card };
        };
        /**
         * The clones injected into host menus. They are plugin-owned nodes inside
         * React-owned lists, so disposal must remove them explicitly — the host
         * never unmounts them on our behalf, and the click listener rides the clone
         * (issue #86: the old disposer left the node and its listener behind, and a
         * later tap went through showError on a dead anchor).
         */
        const injected = new Set();
        /** Inject the delete item into one open session menu (idempotent). */
        const injectInto = (menu) => {
            if (menu.querySelector(`[${DELETE_ITEM_MARKER}]`) !== null)
                return;
            const template = menu.querySelector('[role="menuitem"]');
            // `?? null` collapses the optional chain so the guard is exact: the old
            // `wrap === undefined` arm was unreachable once `template === null` had
            // returned (issue #86).
            const wrap = template?.parentElement ?? null;
            const viewport = menu.querySelector('[class*="_viewport"]');
            if (template === null || wrap === null || viewport === null)
                return;
            const clone = wrap.cloneNode(true);
            const button = clone.querySelector('[role="menuitem"]');
            if (button === null)
                return;
            const icon = button.querySelector('[class*="_itemIcon"]');
            if (icon !== null) {
                icon.innerHTML = TRASH_SVG;
                icon.style.color = DANGER_COLOR;
            }
            // A template without a label span is an unknown shape: inject nothing
            // rather than ship a delete item that reads as the cloned host action.
            const label = button.querySelector('[class*="_itemLabel"]');
            if (label === null)
                return;
            label.textContent = navT('deleteSession');
            label.style.color = DANGER_COLOR;
            button.setAttribute('data-mobile-nav', 'session-delete');
            button.addEventListener('click', (event) => {
                event.preventDefault();
                event.stopPropagation();
                const captured = anchor;
                // Close the host menu by toggling its anchor (React-owned state).
                captured?.button.click();
                try {
                    if (captured === null) {
                        showError(navT('deleteErrorResolve'));
                        return;
                    }
                    // 用录制点击时从行上读到的会话 id（`data-row-key` → DSHA 戳 → fiber）。
                    // 读不到就报错，不再按标题/行序猜 —— 2026-10-07 实测那条路在 0.2.0-rc.2
                    // 上会静默删到**另一个同名会话**（宿主把行包进 HoverCard 后 `:scope >` 取不到行，
                    // rowIndex 变成 -1，于是永远取"第一个同名 id"）。
                    const sessionId = captured.sessionId;
                    if (sessionId === null) {
                        showError(navT('deleteErrorResolve'));
                        return;
                    }
                    showDeleteDialog(sessionId, captured.title);
                }
                catch (reason) {
                    // Never fail silently: surface internal resolution errors instead of
                    // leaving the tap with no visible result.
                    console.error('[dsh-web-mobile] session delete failed:', reason);
                    showError(navT('deleteErrorGeneric', {
                        message: reason instanceof Error ? reason.message : String(reason),
                    }));
                }
            });
            viewport.appendChild(clone);
            injected.add(clone);
        };
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
        const injectAll = () => {
            // Drop references to clones the host already unmounted with its menu.
            for (const clone of injected)
                if (!clone.isConnected)
                    injected.delete(clone);
            const blankLabel = wsT('session.new');
            for (const menu of document.querySelectorAll('[role="menu"]')) {
                if (!isSessionMenu(menu))
                    continue;
                if (anchor !== null && anchor.title === blankLabel)
                    continue;
                injectInto(menu);
            }
        };
        const scheduleInject = () => {
            if (injectRaf !== 0)
                return;
            injectRaf = requestAnimationFrame(() => {
                injectRaf = 0;
                injectAll();
            });
        };
        /** Whether an id is a session this client knows (the fiber walk's filter). */
        const isKnownSessionId = (id) => ctx.sessions.list.getSnapshot().byId[id] !== undefined;
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
        const menuButtonOf = (row) => {
            const buttons = [...row.querySelectorAll('button')];
            const labelled = buttons.find((candidate) => {
                const label = candidate.getAttribute('aria-label') ?? '';
                return /session actions/i.test(label);
            });
            if (labelled !== undefined)
                return labelled;
            const fallback = buttons.find((candidate) => {
                const label = candidate.getAttribute('aria-label') ?? '';
                return !/archive session|pin session/i.test(label);
            });
            return fallback ?? null;
        };
        // Capture the ⋯ button click before React handles it, so the row/title/session
        // id are known when the portaled menu appears.
        //
        // The id comes off the row's React FIBER first (same proven path the
        // long-press navigation uses in drawer-close.ts:249). The old title/group
        // heuristic is kept only as a fallback: on 0.2.0-rc.2 the `_projectRow`
        // group header no longer sits inside `_groupSection`, so duplicate titles
        // (three rows all called 「你好」) could not be disambiguated and the delete
        // tap died with 「无法确定要删除的会话」.
        const onDocumentClick = (event) => {
            const target = event.target;
            if (target === null)
                return;
            const row = target.closest('[class*="_sessionRow"]');
            if (row === null)
                return;
            const button = menuButtonOf(row);
            if (button === null)
                return;
            const title = row.querySelector('[class*="_title"]')?.textContent?.trim() ?? '';
            const sessionId = rowSessionId(row);
            anchor = { button, title, sessionId };
            scheduleInject();
        };
        document.addEventListener('click', onDocumentClick, true);
        // Re-inject whenever a menu list mounts/updates (React recreates the list
        // on every open, so the injected node must follow).
        const observer = new MutationObserver((records) => {
            for (const record of records) {
                if (record.type !== 'childList')
                    continue;
                const target = record.target;
                if (target === document.body) {
                    scheduleInject();
                    break;
                }
                if (target instanceof HTMLElement
                    && (target.matches('[role="menu"]') || target.closest('[role="menu"]') !== null)) {
                    scheduleInject();
                    break;
                }
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });
        injectAll();
        return () => {
            document.removeEventListener('click', onDocumentClick, true);
            observer.disconnect();
            if (injectRaf !== 0)
                cancelAnimationFrame(injectRaf);
            closeDialog();
            // Remove the injected items (and with them their click listeners).
            for (const clone of injected)
                clone.remove();
            injected.clear();
            anchor = null;
        };
    }, breakpoints_ts_1.TOUCH_QUERY);
}
};
__modules["effects/composer-keyboard-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SHADOW_MARKER = void 0;
exports.installComposerKeyboardGuard = installComposerKeyboardGuard;
const device_ts_1 = require("./platform/device.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * iOS keyboard guard for the composer's fixed control cluster.
 *
 * Upstream `dsh-client-ui-conversation` (0.1.2-rc.1) hangs the same
 * `keepFocus` handler on the composer row's three buttons (send, stop, the
 * `+` commands trigger):
 *
 *   const keepFocus = (e) => {
 *     e.preventDefault()
 *     editor?.getRootElement()?.focus({ preventScroll: true })
 *   }
 *
 * `onMouseDown` keeps the caret in the editor across button clicks on
 * desktop. On iOS WebKit the same handler runs inside the tap's synthesized
 * mousedown, and that programmatic `focus()` call re-raises the on-screen
 * keyboard whenever it had closed (scroll-to-dismiss, keyboard dismissal,
 * PWA relaunch) while logical focus never left the contenteditable. The
 * user taps Send on a collapsed keyboard and the keyboard springs back up
 * over the running conversation — the message still sends, but the screen
 * is now half keyboard.
 *
 * Fix strategy, scoped to iOS WebKit (the engine that re-raises keyboards
 * from a programmatic focus; Android/desktop behavior is untouched):
 *
 * In the capture phase of every mousedown whose target sits inside the
 * composer card but is NOT the editing surface itself, temporarily install
 * an own no-op `focus` property on the `[data-composer-input]` element.
 * React's `keepFocus` then calls the shadow instead of the prototype
 * method, the keyboard stays down, and the shadow is removed on the next
 * macrotask so nothing outlives the tap:
 *
 *   capture mousedown → shadow focus → (bubbling) keepFocus → click →
 *   macrotask restore
 *
 * Why shadowing instead of intercepting the event: `keepFocus`'s own
 * `preventDefault()` must keep running (it stops the tap from blurring
 * the editor), and the editor's own tap-to-type path must never be
 * touched — only the button-initiated programmatic focus is undesirable
 * on iOS. A capture-phase `stopPropagation` would break both.
 *
 * DOM contract (verified against 0.1.2-rc.1 dsh-client-ui-conversation):
 * - `[data-composer-card]` — the composer card root (InputBar).
 * - `[data-composer-input]` — the Lexical contenteditable surface.
 * - The buttons carry hashed `_primary`/`_add` classes and no stable
 *   data marker, so the card boundary (not the buttons) is the anchor.
 * Audit both markers when the conversation package upgrades.
 */
/** The composer card root that owns the fixed control cluster. */
const COMPOSER_CARD_SELECTOR = '[data-composer-card]';
/** The Lexical editing surface (the only element allowed to raise the keyboard). */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
/** Interactive controls inside the card — the only presses whose handlers
 *  (keepFocus on send/stop/+, the + onClick's focusDraftEditor) refocus the
 *  editor programmatically. Blank card padding/gaps are not controls. */
const COMPOSER_CONTROL_SELECTOR = 'button, [role="button"], [role="option"], [role="menuitem"], [role="combobox"], [aria-haspopup], select, a[href], label, input';
/** Re-arm marker kept on the editor element while its focus is shadowed.
 *  Exported for its source-contract test. (Its former second user,
 *  session-focus-guard.ts, was removed 2026-11-05: host 0.2.2's InputBar skips
 *  the mount-time editor focus on mobile devices.) */
exports.SHADOW_MARKER = 'data-mobile-nav-focus-shadow';
/** 键盘至少吃掉这么多像素才算「弹着」（滚动条/地址栏抖动远小于此，最矮的输入法也远大于此）。 */
const KEYBOARD_MIN_PX = 120;
/**
 * 软键盘此刻是否弹着：无键盘高度（phone-chrome 维护的 --dsh-web-mobile-vh，只增不减）与可见带高度之差。
 * 平板档不跑 phone-chrome，变量缺席时退回 innerHeight —— adjustResize 下会把「弹着」误判成「收着」，
 * 后果只是这一下按钮会把键盘收起，不会反过来把收着的键盘顶起来。
 */
function keyboardUp() {
    const stable = Number.parseFloat(document.documentElement.style.getPropertyValue('--dsh-web-mobile-vh'));
    const base = Number.isFinite(stable) && stable > 0 ? stable : window.innerHeight;
    const visible = window.visualViewport?.height ?? window.innerHeight;
    return base - visible > KEYBOARD_MIN_PX;
}
function installComposerKeyboardGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer keyboard guard', () => {
        // 2026-09-23 扩档（店主报"点加号会弹键盘、而且再点关不掉"）：
        // `+` 的 onClick 是宿主的 onToggleCommandMenu，它先 focusDraftEditor()
        // 再 toggleCommandMenu(caretSpan) —— 命令菜单需要光标，于是每次点 + 都
        // 把键盘顶起来。键盘一开，整行上移 ~283px（真机探针：composer y 687→404），
        // 店主第二次点的是"加号原来的位置"，自然关不掉，看起来像 toggle 坏了。
        // 同一段 focusDraftEditor 在 iOS 上就是本守卫要拦的调用，所以把启用条件
        // 从"仅 iOS WebKit"放宽到"触屏档（pointer: coarse）"：桌面（精细指针）保持
        // 原样，手机/平板上一律不让 composer 按钮去抢编辑器焦点。拦截的是**程序
        // 化** focus()，原生点输入框聚焦不受影响（点输入框的路径已被下面的 early
        // return 排除）。
        const ios = (0, device_ts_1.detectIosWebKit)(navigator, typeof CSS !== 'undefined' && typeof CSS.supports === 'function' ? CSS.supports.bind(CSS) : null);
        const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
        if (!ios && !coarse) {
            return undefined;
        }
        /** 影子撤除计时器（每次按钮点按重置；见 onPointerDown 里的时间轴注释）。 */
        let shadowTimer = 0;
        /**
         * 撤影子 **并且关掉守卫窗口**。
         *
         * 2026-09-23 二次修订（店主报"点两下加号之后输入框动不了了"）：
         * 原来这里只删影子、不归零 `shadowTimer`，而下面 `onFocusIn` 的开关就是
         * `shadowTimer === 0` —— 于是**点过一次 composer 按钮之后守卫永久生效**：
         * 任何 focusin 都被当场 `blur()`，店主点输入框再也弹不出键盘。
         * 真机行为级取证（探针：先合成一次 composer 按钮 pointerdown，等过 700ms 窗口，
         * 再 blur + focus 编辑器）：
         *   修前 `shadowAttrLeft=false ownFocusLeft=false blurWorked=true focusHeld=false k=754`
         *   ⇒ 影子已撤、计时器却还挂着 ⇒ 守卫一直在，编辑器拿不回焦点。
         */
        const restore = () => {
            window.clearTimeout(shadowTimer);
            shadowTimer = 0;
            const el = document.querySelector(`[${exports.SHADOW_MARKER}]`);
            if (el === null)
                return;
            el.removeAttribute(exports.SHADOW_MARKER);
            const shadowed = el;
            if (Object.prototype.hasOwnProperty.call(el, 'focus'))
                delete shadowed.focus;
        };
        const onPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (typeof target.closest !== 'function')
                return;
            const card = target.closest(COMPOSER_CARD_SELECTOR);
            if (card === null)
                return;
            const editor = card.querySelector(COMPOSER_INPUT_SELECTOR);
            if (editor === null)
                return;
            // 店主自己点编辑面：这是"我要打字"的正路，立刻解除守卫窗口，
            // 绝不让兜底 blur 打到这一下（窗口内点输入框也必须能弹键盘）。
            if (target.closest(COMPOSER_INPUT_SELECTOR) !== null) {
                restore();
                return;
            }
            // 2026-10-04（B2「长按粘贴被吞」）：只有按在**控件**上才开窗口。卡片里的
            // 空白（输入区滚动层的留白、输入区与按钮行之间 12px 的 gap 等）不是 keepFocus
            // 的来源，却曾经同样开出 700ms 的 blur 窗口：长按落在这些空白上时 pointerdown
            // 命中空白容器，而引擎的长按手势经触点校正（touch adjustment）把光标放进编辑面
            // 并聚焦它——约 500ms，正在窗口内——onFocusIn 当场 blur，系统菜单的「粘贴」
            // 随后派发到 body，内容不进草稿（headless 探针：修前长按 gap 处 paste 目标 BODY、
            // 草稿不变；修后 paste 进编辑面）。空白处按下按"要打字"处理，与按在编辑面上
            // 同路；控件判定限定在卡片内，卡片外的祖先不算。
            const control = target.closest(COMPOSER_CONTROL_SELECTOR);
            if (control === null || !card.contains(control)) {
                restore();
                return;
            }
            // A button-area tap: shadow focus for the remainder of this dispatch.
            restore();
            // 2026-11-04 店主报障「输完字收起输入法，点发送又弹出来」：返回键收起输入法不会让编辑面失焦，
            // keepFocus 又 preventDefault 掉了按钮的 mousedown 失焦 ⇒ 点完发送，引擎看到「焦点仍在可编辑元素上
            // 的一次真实点按」就把输入法顶回来——没有 focus() 调用、也没有 focusin，影子和兜底都拦不到。
            // 键盘收着时先把焦点从编辑面拿走：之后提交清空草稿时 Lexical 的 root.focus() 落在下面的影子上，
            // 兜底 focusin 也还在窗口内。键盘弹着时不动，连续发送照旧不收键盘。草稿与光标在宿主 keyboard 状态里。
            if (document.activeElement === editor && !keyboardUp())
                editor.blur();
            editor.setAttribute(exports.SHADOW_MARKER, '');
            Object.defineProperty(editor, 'focus', {
                configurable: true,
                writable: true,
                value: function swallowedFocus() {
                    /* keepFocus called; keep the dismissed keyboard dismissed */
                },
            });
            // 影子的存活窗口 = 700ms 固定窗口，**不能**"click 后立刻撤"。
            // 2026-09-23 真机探针的事件轨迹（点一次 `+`）：
            //   51.5 clicks:添加文件或调用指令 / shadow:ON
            //   51.6 shadow:off          ← 旧的"click 后 setTimeout(0) 撤"
            //   51.7 vv 754→471          ← 键盘此时才弹 ⇒ 宿主是在"菜单打开后的 effect"
            //                              里再 focus 一次，撤早了等于白装。
            // 影子只拦**程序化** focus()；窗口内用户点输入框由上面那个 early return
            // 当场解除窗口，所以放宽到 700ms 是安全的；窗口内新的按钮点按会重置计时。
            window.clearTimeout(shadowTimer);
            shadowTimer = window.setTimeout(restore, 700);
        };
        // 2026-09-23：只挂 mousedown 会空转。Android WebView 上按钮的点击经常吃不到
        // 兼容性 mousedown（touchstart 被 preventDefault 时更甚），于是影子从没装上，
        // 宿主 click 里的 focusDraftEditor 照样把键盘顶起来 —— 店主实测"两个问题都还在"。
        // 三个入口都挂上，处理体是幂等的（每次先 restore 再重装影子）。
        // 兜底：万一"按钮点按 → 编辑器被聚焦"仍然把键盘顶起来（真机可能走
        // 我们拦不到的路径），在同一个 700ms 窗口内立刻把焦点还回去 —— blur 会
        // 收起软键盘。宿主的光标/草稿来自它自己的 keyboard 状态，不依赖 DOM focus，
        // 所以这里 blur 不会丢草稿（用户随后点输入框照常输入）。
        const onFocusIn = (event) => {
            if (shadowTimer === 0)
                return;
            const target = event.target;
            if (!(target instanceof HTMLElement))
                return;
            if (target.closest(COMPOSER_INPUT_SELECTOR) === null)
                return;
            // 同步 blur：放到宏任务里 IME 已经开始弹了（真机实测 setTimeout 版无效，
            // vv 仍然 754→471）。在 focusin 的捕获阶段当场 blur，键盘根本不会出现。
            target.blur();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('touchstart', onPointerDown, true);
        document.addEventListener('mousedown', onPointerDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('touchstart', onPointerDown, true);
            document.removeEventListener('mousedown', onPointerDown, true);
            document.removeEventListener('focusin', onFocusIn, true);
            restore();
        };
    });
}
};
__modules["effects/composer-keyboard-lift.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CHANNEL_TOLERANCE_PX = void 0;
exports.computeComposerLift = computeComposerLift;
exports.installComposerKeyboardLift = installComposerKeyboardLift;
const device_ts_1 = require("./platform/device.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/** How far the two scroll channels may drift before the coordinate model
 *  counts as unverified: covers rounding and event-order jitter, still small
 *  enough to catch a real visual-viewport pan. */
exports.CHANNEL_TOLERANCE_PX = 24;
/** CSS px to lift the composer seat by; 0 rests (fail-open for every
 *  unverified model, not just for "no overlap"). */
function computeComposerLift(state) {
    const { seatBottom, viewportHeight, scale, scrollY, offsetTop } = state;
    if (!Number.isFinite(seatBottom) || !Number.isFinite(viewportHeight))
        return 0;
    if (!Number.isFinite(scale) || Math.abs(scale - 1) > 0.01)
        return 0;
    if (!Number.isFinite(scrollY) || !Number.isFinite(offsetTop))
        return 0;
    if (Math.abs(scrollY - offsetTop) > exports.CHANNEL_TOLERANCE_PX)
        return 0;
    const overlap = seatBottom - viewportHeight;
    return overlap > 0 ? overlap : 0;
}
/** Host composer seat, scoped under the plugin frame marker. */
const SEAT_SELECTOR = '[data-mobile-nav="frame"] [class*="_composerSeat"]';
/** The Lexical editing surface — the only focus that arms the lift. */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
function installComposerKeyboardLift(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer keyboard lift', () => {
        if (!(0, device_ts_1.detectIosWebKit)(navigator, typeof CSS !== 'undefined' && typeof CSS.supports === 'function' ? CSS.supports.bind(CSS) : null)) {
            return undefined;
        }
        const viewport = window.visualViewport;
        if (!viewport)
            return undefined;
        let seat = null;
        let appliedLift = 0;
        let frame = 0;
        const sync = () => {
            frame = 0;
            if (seat === null || !seat.isConnected)
                return;
            const lift = computeComposerLift({
                // The rect already carries our own transform: add the applied lift
                // back so `seatBottom` reads as the un-lifted screen bottom.
                seatBottom: seat.getBoundingClientRect().bottom + appliedLift,
                viewportHeight: viewport.height,
                scale: viewport.scale,
                scrollY: window.scrollY,
                offsetTop: viewport.offsetTop,
            });
            appliedLift = lift;
            seat.style.transform = lift > 0 ? `translateY(${-lift}px)` : '';
        };
        const schedule = () => {
            if (frame === 0)
                frame = requestAnimationFrame(sync);
        };
        const release = () => {
            viewport.removeEventListener('resize', schedule);
            viewport.removeEventListener('scroll', schedule);
            window.removeEventListener('scroll', schedule);
            if (frame !== 0) {
                cancelAnimationFrame(frame);
                frame = 0;
            }
            if (seat !== null)
                seat.style.transform = '';
            seat = null;
            appliedLift = 0;
        };
        const onFocusIn = (event) => {
            const target = event.target;
            if (!(target instanceof Element) || target.closest(COMPOSER_INPUT_SELECTOR) === null)
                return;
            release();
            const found = document.querySelector(SEAT_SELECTOR);
            if (found === null)
                return;
            seat = found;
            viewport.addEventListener('resize', schedule);
            viewport.addEventListener('scroll', schedule);
            window.addEventListener('scroll', schedule, { passive: true });
            schedule();
        };
        const onFocusOut = (event) => {
            const target = event.target;
            if (!(target instanceof Element) || target.closest(COMPOSER_INPUT_SELECTOR) === null)
                return;
            const next = event.relatedTarget;
            // Focus moving inside the seat keeps the keyboard up: stay armed.
            if (next instanceof Element && next.closest(SEAT_SELECTOR) !== null)
                return;
            release();
        };
        document.addEventListener('focusin', onFocusIn, true);
        document.addEventListener('focusout', onFocusOut, true);
        return () => {
            document.removeEventListener('focusin', onFocusIn, true);
            document.removeEventListener('focusout', onFocusOut, true);
            release();
        };
    });
}
};
__modules["core/composer-clearance.js"] = function (require, module, exports) {
"use strict";
// Composer clearance: how much of the host composer seat sits outside the band
// the user can actually see. Pure geometry + the marker's hysteresis — no DOM
// (architecture: core/ stays DOM-free), so both halves are unit-tested without
// an engine. The browser adapter is effects/composer-clearance.ts; the CSS that
// consumes its marker lives in styles/layout.css.ts.
//
// 事故（issue #245，owner 亲报 2026-10-25）：「当输入时屏幕变为横屏时，输入框
// 可能会错位甚至消失，目前只能进行刷新」。横屏 + 软键盘把布局视口压到 ~110-160px
// 之后，composer 座位（宿主 `_composerSeat`，position:sticky; bottom:0）仍然按
// 自己的高度（~70-102px）钉在可见带底部；它的**上半截就是 Lexical 编辑行**，于是
// 编辑行落进会话顶栏的盒子（插件给顶栏 `z-index:30` + 不透明底，见 layout.css.ts
// 「Opaque AND above the flow」），被顶栏整块盖住 —— 用户看到的就是「输入框消失」，
// 只露出底部的发送行（「错位」）。sticky 是纯 CSS，滚动改不了它的位置，键盘不收
// 起来就一直在；刷新会收起键盘、布局视口回到 360px，所以看起来「只能刷新」。
//
// 这里只回答一个问题：「座位有多少像素落在用户看不见的地方」——两个互相独立的
// 来源相加：
//   ① 被顶栏盖住：座位顶边（按 sticky 语义 = 可见带底 − 座位高）高于顶栏底边；
//   ② 掉到可见带外：座位底边在可见带底边之下（可写视口 / 布局视口不同步时的兜底）。
// 只要这个数 > 0，座位就不可能被完整看到，标记就该武装。判定刻意用**未缓解的座位
// 高度**（调用方在标记关闭时缓存），否则 CSS 一收起次要行，几何就自证清白、标记
// 会退出，下一次 resize 又进来 —— 一个自激的状态机。
Object.defineProperty(exports, "__esModule", { value: true });
exports.TIGHT_ENTER_PX = exports.SCROLL_CHANNEL_TOLERANCE_PX = void 0;
exports.composerOccludedPx = composerOccludedPx;
exports.nextComposerTight = nextComposerTight;
/** How far the page scroll offset and the visual viewport's offsetTop may drift before the
 *  screen-space model counts as unverified. Same value and reasoning as
 *  composer-keyboard-lift.ts's CHANNEL_TOLERANCE_PX, restated here because
 *  core/ may not import effects/ (layer direction) and the tolerance is part
 *  of this module's fail-open contract, not of that effect. */
exports.SCROLL_CHANNEL_TOLERANCE_PX = 24;
/** Occlusion is only real from 1px on: sub-pixel overlap is rect rounding, not
 *  a hidden editing row. Entering at 0 would also flip the marker on every
 *  fractional resize tick. */
exports.TIGHT_ENTER_PX = 1;
/** Pixels of the composer seat the user cannot see, 0 when the model is not
 *  trusted. Fail-open on every unverified input: a wrong 0 (the status quo)
 *  costs nothing, a wrong positive would re-stack the header under the
 *  composer on devices that never had the defect. */
function composerOccludedPx(band) {
    const { visibleHeight, headerBottom, seatHeight, seatBottom, scrollY, offsetTop } = band;
    for (const value of [visibleHeight, headerBottom, seatHeight, seatBottom, scrollY, offsetTop]) {
        if (!Number.isFinite(value))
            return 0;
    }
    // Screen-space coordinates only make sense while both scroll channels agree
    // (layout viewport == visual viewport, the Android adjustResize model). A iOS
    // style visual-viewport pan separates them; then rect coordinates are not
    // screen positions and any occlusion number derived from them is fiction.
    if (Math.abs(scrollY - offsetTop) > exports.SCROLL_CHANNEL_TOLERANCE_PX)
        return 0;
    const visible = Math.max(0, visibleHeight);
    // Sticky bottom pins the seat's bottom edge to the band's bottom; what is
    // left above it is the seat itself. A seat taller than the band starts at 0.
    const seatTop = Math.max(0, visible - Math.max(0, seatHeight));
    const underHeader = Math.max(0, headerBottom - seatTop);
    const belowBand = Math.max(0, seatBottom - visible);
    return underHeader + belowBand;
}
/** Hysteresis for the marker: enter on a real overlap, leave only when the
 *  occlusion is gone. Without the dead band a 1px IME animation frame or a
 *  rounding tick would toggle the layout on and off. */
function nextComposerTight(previous, occludedPx) {
    if (!Number.isFinite(occludedPx))
        return false;
    return previous ? occludedPx > 0 : occludedPx >= exports.TIGHT_ENTER_PX;
}
};
__modules["effects/composer-clearance.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installComposerClearance = installComposerClearance;
const composer_clearance_ts_1 = require("./core/composer-clearance.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const frame_ts_1 = require("./platform/frame.js");
/**
 * Issue #245: rotating to landscape while the composer holds focus and the soft
 * keyboard is up leaves the input row misaligned or gone until the page is
 * reloaded.
 *
 * Mechanism (derivation, numbers and the host contract in
 * core/composer-clearance.ts):
 * - The host composer seat `[class*="_composerSeat"]` is `position: sticky;
 *   bottom: 0` inside the conversation scroll body — pure CSS, the host never
 *   measures it in JS (host 0.2.1-rc.3: `composerSeat` appears only in its
 *   stylesheet, class map and JSX; nowhere does the package read its rect or a
 *   `dvh`). So the seat's top edge is always `band bottom - seat height`.
 * - This plugin owns the header stacking: styles/layout.css.ts paints the
 *   conversation header opaque and above the flow (`z-index: 30`, the
 *   "Opaque AND above the flow" block) and lifts the scroll body's box under
 *   it, so the seat's box region includes the header band.
 * - Portrait leaves 700+px above the keyboard, so the seat never reaches the
 *   header. Landscape (792x360 here) with the IME still up leaves the layout
 *   viewport at ~110-160px while the header is ~67-77px tall and the seat
 *   ~70-102px: `67 + 70 > 110`, the editing row is laid out *inside the header
 *   band* and the opaque header paints over it. Sticky cannot be scrolled out
 *   of that state, and reloading closes the keyboard — hence "only a refresh
 *   helps".
 *
 * What this module does: measure the band/header/seat on every viewport event
 * and publish the decision as a DOM marker on the frame; the CSS block keyed on
 * `data-mobile-nav-composer-tight` then lets the seat outrank the header and
 * drops the seat's secondary rows so the editing row fits. No transform, no
 * inline styles on host nodes: a lift would be wrong here (the seat is already
 * bottom-pinned; the covered part is its top) — that is why
 * composer-keyboard-lift.ts stays iOS-only and is not touched.
 *
 * Why not a CSS media query: the trigger is `header height + seat height >
 * visible band`, and all three depend on the device, the IME and the draft
 * (a multi-line draft grows the seat). A px threshold would be a magic number
 * that misses the next phone and misfires on tablets in landscape. The
 * decision is measured, the pure half is unit-tested
 * (tests/composer-clearance.test.ts).
 *
 * Fail-open: `composerOccludedPx` returns 0 for garbage readings and whenever
 * the two scroll channels disagree (visual-viewport pan), and the marker is
 * dropped whenever the composer is not laid out (hero phase, panel takeover,
 * host unmount).
 *
 * DOM contract (audit on host upgrades): the seat is the host-owned hashed
 * `_composerSeat` (the same substring composer-keyboard-lift.ts anchors), the
 * header is the host header class `wSkVaW_header` (not a bare `header`: the
 * ask-question card renders its own header inside the seat, pitfalls §提问卡
 * 顶栏误伤), and the secondary row the CSS drops is `[data-composer-dock]`
 * (the InputBar dock row; the older `[data-composer-stats]` arm was removed
 * with the host floor >=0.2.2-rc.1).
 */
/** Marker written on the frame while the composer seat is occluded. */
const TIGHT_MARKER = 'data-mobile-nav-composer-tight';
/** Host composer seat, scoped under the plugin frame marker. */
const SEAT_SELECTOR = '[class*="_composerSeat"]';
/** The conversation header — the host header class, never a bare `header`.
 *  The anchor is the underscore suffix, not a hash prefix: the host renamed
 *  its CSS-module prefix (wSkVaW_ → tlcmdG_ in 0.2.1-rc.2) and every
 *  prefix-anchored rule silently stopped matching. */
const HEADER_SELECTOR = 'header:has([class*="_headerLeading"])';
function installComposerClearance(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer clearance', () => {
        /** Current state of the marker (the hysteresis input). */
        let tight = false;
        /** Seat height with every row visible. Refreshed only while the marker is
         *  off: while it is on our own CSS has already dropped the dock row, and
         *  feeding that shrunk height back would turn the state machine off on the
         *  next resize tick and on again one tick later. */
        let fullHeight = 0;
        let frame = 0;
        const clear = () => {
            tight = false;
            fullHeight = 0;
            (0, frame_ts_1.getFrame)()?.removeAttribute(TIGHT_MARKER);
        };
        const measure = () => {
            frame = 0;
            const host = (0, frame_ts_1.getFrame)();
            if (host === null) {
                clear();
                return;
            }
            const seat = host.querySelector(SEAT_SELECTOR);
            const header = host.querySelector(HEADER_SELECTOR);
            const rect = seat?.getBoundingClientRect() ?? null;
            // Not laid out: hero phase, panel takeover (the host hides the seat), or
            // a host that has not mounted the composer yet. Nothing to clear up.
            if (seat === null || header === null || rect === null || rect.width === 0 || rect.height === 0) {
                clear();
                return;
            }
            if (!tight)
                fullHeight = rect.height;
            const viewport = window.visualViewport;
            const occluded = (0, composer_clearance_ts_1.composerOccludedPx)({
                visibleHeight: Math.min(window.innerHeight, viewport?.height ?? window.innerHeight),
                headerBottom: header.getBoundingClientRect().bottom,
                seatHeight: fullHeight > 0 ? fullHeight : rect.height,
                seatBottom: rect.bottom,
                scrollY: window.scrollY,
                offsetTop: viewport?.offsetTop ?? 0,
            });
            tight = (0, composer_clearance_ts_1.nextComposerTight)(tight, occluded);
            if (tight) {
                if (!host.hasAttribute(TIGHT_MARKER))
                    host.setAttribute(TIGHT_MARKER, '');
            }
            else if (host.hasAttribute(TIGHT_MARKER)) {
                host.removeAttribute(TIGHT_MARKER);
            }
        };
        const schedule = () => {
            if (frame === 0)
                frame = requestAnimationFrame(measure);
        };
        // The IME animation, the rotation and the keyboard-less restore all arrive
        // as resize storms; one measurement per frame is enough and keeps the
        // whole effect off the streaming path. `orientationchange` is kept for the
        // WebView sequences that fire it before the final resize.
        window.addEventListener('resize', schedule);
        window.addEventListener('orientationchange', schedule);
        window.visualViewport?.addEventListener('resize', schedule);
        window.visualViewport?.addEventListener('scroll', schedule);
        // A composer that mounts under an already-shrunk band (session switch with
        // the keyboard up) sees no resize at all; its own autofocus is the signal.
        document.addEventListener('focusin', schedule, true);
        document.addEventListener('focusout', schedule, true);
        schedule();
        return () => {
            window.removeEventListener('resize', schedule);
            window.removeEventListener('orientationchange', schedule);
            window.visualViewport?.removeEventListener('resize', schedule);
            window.visualViewport?.removeEventListener('scroll', schedule);
            document.removeEventListener('focusin', schedule, true);
            document.removeEventListener('focusout', schedule, true);
            if (frame !== 0) {
                cancelAnimationFrame(frame);
                frame = 0;
            }
            clear();
        };
    });
}
};
__modules["effects/document-scroll-lock.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installDocumentScrollLock = installDocumentScrollLock;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * Keep the mobile shell from ever being scrolled as a document.
 *
 * Derivation, from the recorder's full-state dumps (1604 snapshots, 2026-10-31):
 *
 *   scrollY === visualViewport.offsetTop     1604 / 1604
 *   frameTop === -scrollY                    1604 / 1604
 *
 * On this engine the "visual viewport pan" the keyboard causes IS the document
 * scroll — there is no second mechanism. And the shell's own geometry is only
 * correct while that scroll is zero: with `scrollY === 0` and the keyboard up,
 * 139 dumps read `seat bottom - frame bottom = -1..0`, i.e. the composer sits
 * exactly on the shell's bottom edge. Once the document has scrolled (131 dumps,
 * 84..339px) the whole shell — composer included — is displaced by exactly that
 * amount, which is the owner's 「输入框被顶飞，下面是空白背景」.
 *
 * Who scrolls it: the host's caret-visibility helper calls `window.scrollBy`
 * (the caller behind issue #149). `overflow: hidden` does not stop that call,
 * and `position: fixed` on html/body (tried first) broke the document paint
 * outright. So the document is pinned back in JS: the scroll lands, we put it
 * back, same task, before paint.
 */
function installDocumentScrollLock(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: document scroll lock', () => {
        // Undoing the scroll after the fact is not enough: the call itself has to be
        // refused, because one frame of it is already one frame of the shell sitting
        // 339px above the band (11:46:58 dump: sy=339, frame=-339+345, seat=-101 —
        // the owner's 「炸了」). `overflow: hidden` does not stop window.scrollBy and
        // neither does `position: fixed` on html (tried, same dump).
        const nativeScrollBy = window.scrollBy.bind(window);
        const nativeScrollTo = window.scrollTo.bind(window);
        const refuse = () => {
            if (window.scrollX !== 0 || window.scrollY !== 0)
                nativeScrollTo(0, 0);
        };
        const patchedScrollBy = ((...args) => {
            void args;
            refuse();
        });
        const patchedScrollTo = ((...args) => {
            void args;
            refuse();
        });
        const pin = () => {
            if (window.scrollX !== 0 || window.scrollY !== 0)
                nativeScrollTo(0, 0);
        };
        window.scrollBy = patchedScrollBy;
        window.scrollTo = patchedScrollTo;
        window.addEventListener('scroll', pin, { passive: true });
        document.addEventListener('scroll', pin, true);
        pin();
        return () => {
            window.scrollBy = nativeScrollBy;
            window.scrollTo = nativeScrollTo;
            window.removeEventListener('scroll', pin);
            document.removeEventListener('scroll', pin, true);
        };
    });
}
};
__modules["core/question-fullscreen.js"] = function (require, module, exports) {
"use strict";
// Ask-question card: the fullscreen answer band, and the answer field's way
// back into the card body's visible box. Pure geometry — no DOM (architecture
// R4: core/ stays engine-free), so every number below is unit-tested without a
// browser. The browser adapter is effects/question-fullscreen.ts; the CSS that
// consumes its marker and its measured reserve lives in styles/layout.css.ts.
//
// 事故（2026-10-25 owner 亲报三条，本文件对应第 2 条）：长问题 + 键盘弹起后
// 「打字时看不到自己打的字，收起键盘才看到」，内容整体上下猛跳。
//
// 机制是两块盒子的算术，不是某个变量写错：
//  - 宿主卡片 `Mbwy4a_card` 自带 `max-height: min(60vh, 520px)`。这台 Android
//    WebView（adjustResize）上 vh 跟着软键盘一起缩（实测 754 → 471，见 pitfalls
//    §弹层闪），于是键盘一响，卡片的高度预算从 452 掉到 283；
//  - 卡片里唯一能滚的是 `Mbwy4a_body`（宿主 `flex:auto; min-height:0;
//    overflow-y:auto`），自定义回答框是 body 的**最后一块**。浏览器只在「聚焦那
//    一刻」把输入框滚进可视区，而那一刻卡片还没缩；之后卡片缩掉 169px、body 的
//    scrollTop 不变 ⇒ 回答框落到 body 可视区之外，用户盲打；
//  - 收起键盘 vh 回弹、卡片长回去，回答框又「出现」—— 录屏里那句「收起键盘才看到」
//    与上下猛跳都是这一条。
//
// 解法两半都在本模块的调用方：① owner 拍板的手动「展开」按钮把卡片撑到「顶栏底 →
// composer 顶」的整条可见带（CSS 只做算术，数值由 questionBandPx 交出去）；
// ② answerFieldScrollTop 把回答框重新滚回 body 可视区（focusin 与视口变化各一次）。
//
// 只回答「可用高度」与「该不该真的进全屏态」两件事，量都在调用方取：
//  - 可见带底 = `--dsha-mobile-visible-vh`（viewport-meta.ts 维护，= min(innerHeight,
//    visualViewport.height)），顶栏底 = `--mobile-nav-header-h`（header-metrics.ts
//    维护，= frame 顶 → header 底，已含安全区下推）；
//  - 卡片下面还压着 composer 自己的东西（座位里的 dock 行等）以及 frame 自带的上下
//    内边距，这一截由调用方量出来当 reservePx 交回 CSS（CSS 只做
//    visible - header - reserve，不出现任何写死的 px）。
Object.defineProperty(exports, "__esModule", { value: true });
exports.ANSWER_FIELD_MARGIN_PX = exports.HOST_CARD_HEIGHT_CAP_PX = exports.HOST_CARD_HEIGHT_RATIO = void 0;
exports.questionBandPx = questionBandPx;
exports.hostCardHeightPx = hostCardHeightPx;
exports.nextQuestionFull = nextQuestionFull;
exports.answerFieldScrollTop = answerFieldScrollTop;
/** The host card's own height ceiling: `max-height: min(60vh, 520px)`. Kept as
 *  numbers so "does arming fullscreen actually add room" is a comparison
 *  against the host's own formula instead of a copied magic number. */
exports.HOST_CARD_HEIGHT_RATIO = 0.6;
exports.HOST_CARD_HEIGHT_CAP_PX = 520;
/** Gap kept between the answer field and the card body's edges when the field
 *  is scrolled back into view. The field's own block padding is 8px, so the
 *  text keeps a readable margin instead of grazing the box. */
exports.ANSWER_FIELD_MARGIN_PX = 8;
const USELESS = { reservePx: 0, bandPx: 0 };
/** The fullscreen band: the visible strip between the header's bottom and the
 *  composer's top, minus what the card does not own.
 *
 *  Fail-open on every unverified input: a wrong 0 means "keep the host's own
 *  min(60vh, 520px)", which is the pre-existing state, while a wrong positive
 *  would size the card against a band that is not on screen (overlapping the
 *  header, or taller than the viewport). */
function questionBandPx(input) {
    const { visibleHeight, headerHeight, frameBottom, seatBottom, framePadding } = input;
    for (const value of [visibleHeight, headerHeight, frameBottom, seatBottom, framePadding]) {
        if (!Number.isFinite(value))
            return USELESS;
    }
    if (visibleHeight <= 0 || headerHeight < 0)
        return USELESS;
    // The seat is sticky-bottom: it should sit exactly on the band's bottom, and
    // anything left between them is room the card cannot use. The second term is
    // the composer's own rows under the question frame; the third is the frame's
    // padding, which the card's box does not include.
    const belowSeatPx = Math.max(0, visibleHeight - seatBottom);
    const belowFramePx = Math.max(0, seatBottom - frameBottom);
    const reservePx = belowSeatPx + belowFramePx + Math.max(0, framePadding);
    const bandPx = Math.max(0, visibleHeight - headerHeight - reservePx);
    return { reservePx, bandPx };
}
/** The host's own ceiling for the same viewport, i.e. what the card would get
 *  without this feature. 0 when the viewport is unreadable. */
function hostCardHeightPx(visibleHeight) {
    if (!Number.isFinite(visibleHeight) || visibleHeight <= 0)
        return 0;
    return Math.min(visibleHeight * exports.HOST_CARD_HEIGHT_RATIO, exports.HOST_CARD_HEIGHT_CAP_PX);
}
/** Whether the fullscreen marker is on.
 *
 *  - `requested` is the user's manual toggle (`false` always wins: the owner
 *    asked for a manual button, never for an automatic expansion);
 *  - entering needs proof that fullscreen actually adds room: a band at or
 *    below the host's own min(60vh, 520px) would only redraw the same card;
 *  - leaving only happens when the measurement becomes unusable, never because
 *    the band shrank. The keyboard opening shrinks the band on every IME frame;
 *    dropping out of fullscreen there would be the exact 「上下猛跳」 this
 *    feature exists to remove (same hysteresis reasoning as nextComposerTight
 *    in core/composer-clearance.ts). */
function nextQuestionFull(previous, requested, bandPx, hostDefaultPx) {
    if (!requested)
        return false;
    if (!Number.isFinite(bandPx) || bandPx <= 0)
        return false;
    if (previous)
        return true;
    if (!Number.isFinite(hostDefaultPx) || hostDefaultPx <= 0)
        return true;
    return bandPx > hostDefaultPx;
}
/** Where the card body has to scroll so the answer field is inside its visible
 *  box again, or the current scrollTop when nothing needs to move.
 *
 *  The visible box is the body's own box **intersected with the visual
 *  viewport**: with `adjustResize` the layout viewport shrinks and bodyBottom
 *  already is the screen edge, but a platform that instead pans the visual
 *  viewport leaves the body box hanging below the pixel band the user can
 *  actually see. In that model "field is inside bodyBottom" is true while the
 *  user still types blind, so the caller hands us the visual viewport and the
 *  intersection is what counts (2026-10-25 residual report).
 *
 *  Deliberately NOT scrollIntoView: that walks every scrollable ancestor and
 *  would move the conversation behind the card (the host owns that scroll and
 *  re-anchors it while streaming). Only the card body moves, and only when the
 *  field is actually outside the box — a field taller than the body is aligned
 *  to its bottom edge, which is where the caret is. */
function answerFieldScrollTop(view) {
    const { fieldTop, fieldBottom, bodyTop, bodyBottom, scrollTop, scrollHeight, clientHeight, visibleTop, visibleBottom, } = view;
    for (const value of [fieldTop, fieldBottom, bodyTop, bodyBottom, scrollTop, scrollHeight, clientHeight]) {
        if (!Number.isFinite(value))
            return scrollTop;
    }
    const top = visibleTop === undefined || !Number.isFinite(visibleTop) ? bodyTop : Math.max(bodyTop, visibleTop);
    const bottom = visibleBottom === undefined || !Number.isFinite(visibleBottom) ? bodyBottom : Math.min(bodyBottom, visibleBottom);
    // No overlap at all: the body (or the screen) is not somewhere scrolling can
    // fix — leave the content alone instead of jumping to a bogus offset.
    if (bottom - top < 1)
        return scrollTop;
    const max = Math.max(0, scrollHeight - clientHeight);
    const room = Math.max(0, bottom - top);
    const margin = Math.min(exports.ANSWER_FIELD_MARGIN_PX, room / 2);
    let next = scrollTop;
    if (fieldTop < top + margin)
        next = scrollTop - (top + margin - fieldTop);
    else if (fieldBottom > bottom - margin)
        next = scrollTop + (fieldBottom - (bottom - margin));
    return Math.min(Math.max(next, 0), max);
}
};
__modules["effects/question-fullscreen.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installQuestionFullscreen = installQuestionFullscreen;
const question_fullscreen_ts_1 = require("./core/question-fullscreen.js");
const locales_ts_1 = require("./i18n/locales.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const frame_ts_1 = require("./platform/frame.js");
const visible_band_ts_1 = require("./platform/visible-band.js");
const reconciler_adapter_ts_1 = require("./effects/reconciler-adapter.js");
/** Frame marker: the card owns the whole answer band. */
const FULL_MARKER = 'data-mobile-nav-question-full';
/** Measured "everything under the card" height, consumed by the fullscreen CSS
 *  as the only number it does not derive from the two published variables. */
const RESERVE_VAR = '--dsha-mobile-question-reserve';
/** Our control; also the CSS anchor for its box and its icon swap. */
const BUTTON_MARKER = 'question-expand';
/** Ask-question card family (`QuestionComposer.module.css`, hash substring). */
const CARD_FRAME_SELECTOR = '[class*="Mbwy4a_frame"]';
const HEADER_ACTIONS_SELECTOR = '[class*="Mbwy4a_headerActions"]';
const CARD_BODY_SELECTOR = '[class*="Mbwy4a_body"]';
const ANSWER_FIELD_SELECTOR = '[class*="Mbwy4a_fieldInput"]';
/** The host composer seat (same anchor as composer-clearance.ts): the card's
 *  bottom edge has to be measured against the seat it sits in. */
const SEAT_SELECTOR = '[class*="_composerSeat"]';
/** Published viewport facts, written INLINE on <html> by viewport-meta.ts
 *  (`--dsha-mobile-visible-vh`) and header-metrics.ts (`--mobile-nav-header-h`).
 *  Read from the inline declaration on purpose: no style resolution, and the
 *  numbers are exactly the ones the stylesheet will use. */
const VISIBLE_VH_VAR = '--dsha-mobile-visible-vh';
const HEADER_H_VAR = '--mobile-nav-header-h';
/** Expand = corners out, collapse = corners in, same 16-grid / 1.5-stroke
 *  language as the host's own card icons (and as preview-fullscreen.ts). The
 *  two are swapped by CSS on the frame marker, so the JS never rewrites markup. */
const ICONS = [
    '<svg class="dsh-web-mobile-question-expand" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
    '<path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4v-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
    '</svg>',
    '<svg class="dsh-web-mobile-question-collapse" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
    '<path d="M2 6h4V2M14 6h-4V2M2 10h4v4M14 10h-4v4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
    '</svg>',
].join('');
/** Inline custom-property read; NaN when the variable is absent. */
const readVarPx = (name) => {
    const raw = document.documentElement.style.getPropertyValue(name).trim();
    const value = Number.parseFloat(raw);
    return Number.isFinite(value) ? value : Number.NaN;
};
/**
 * Fullscreen answer for the ask-question card: the injected expand/collapse
 * toggle, the measured band marker, and the answer field's way back into the
 * card body.
 *
 * Registered from index.tsx next to the other composer effects. Everything
 * lives inside one mobile effect so a width change tears the listeners and the
 * reconciler task down together (and re-arms them on the way back to a phone).
 *
 * @param ctx - client root context: locale dictionary for the button labels.
 */
function installQuestionFullscreen(ctx) {
    const t = ctx.locale.bind(locales_ts_1.NS);
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: question fullscreen', () => {
        /** The user's manual toggle. Set only by a tap, cleared when the card goes
         *  away — fullscreen must never re-arm itself (owner: 手动展开，不要自动全屏). */
        let requested = false;
        /** Whether the frame currently carries the marker (= the button's state). */
        let armed = false;
        /** Injected control; kept across host re-renders and re-attached by ensure. */
        let button = null;
        /** The card frame we wrote the reserve variable on (cleared on dispose). */
        let published = null;
        /** Last reserve text written; '' when nothing was written. */
        let reserveText = '';
        let frame = 0;
        // 2026-11-03 性能：本任务 scopes:['*']，流式时每批变动都跑；原先每次都在整个 frame
        // 上做一次 [class*=] 子串查询，卡片不在时（绝大多数时间）就是一次全树扫。卡片只会
        // 出现在 composer 座位里，所以先缓存座位、只在座位子树里找；找到的卡片也缓存，
        // 仍连在座位里就直接复用。座位不在（老宿主 / 空态）时退回原来的整 frame 查询。
        let seatCache = null;
        let cardCache = null;
        const composerSeat = (host) => {
            if (seatCache !== null && seatCache.isConnected && host.contains(seatCache))
                return seatCache;
            seatCache = host.querySelector(SEAT_SELECTOR);
            return seatCache;
        };
        const cardFrame = () => {
            const host = (0, frame_ts_1.getFrame)();
            if (host === null)
                return null;
            const seat = composerSeat(host);
            const scope = seat ?? host;
            if (cardCache !== null && cardCache.isConnected && scope.contains(cardCache))
                return cardCache;
            cardCache = scope.querySelector(CARD_FRAME_SELECTOR);
            return cardCache;
        };
        const label = () => t(armed ? 'questionCollapse' : 'questionExpand');
        /** Reflect the state on the button. Only on change: attribute writes wake
         *  the full-tree reconciler, this task included. */
        const syncButton = () => {
            if (button === null)
                return;
            const text = label();
            if (button.getAttribute('aria-label') !== text) {
                button.setAttribute('aria-label', text);
                button.title = text;
            }
            const expanded = armed ? 'true' : 'false';
            if (button.getAttribute('aria-expanded') !== expanded) {
                button.setAttribute('aria-expanded', expanded);
            }
        };
        /** Drop every trace of the feature and forget the user's intent. */
        const clear = () => {
            requested = false;
            armed = false;
            if (frame !== 0) {
                cancelAnimationFrame(frame);
                frame = 0;
            }
            const host = (0, frame_ts_1.getFrame)();
            if (host !== null && host.hasAttribute(FULL_MARKER))
                host.removeAttribute(FULL_MARKER);
            published?.style.removeProperty(RESERVE_VAR);
            published = null;
            reserveText = '';
            syncButton();
        };
        /** Scroll the answer field back inside the card body's visible box, but
         *  only while it holds focus (a scroll the user started must not be undone)
         *  and only when it actually left the box. Never focuses, never touches the
         *  host's draft state, never scrolls an ancestor (see
         *  core/question-fullscreen.ts on why not scrollIntoView).
         *
         *  The visual viewport is handed to the pure core as the clipping rectangle
         *  (2026-10-25 residual report): a platform that pans the visual viewport
         *  instead of resizing the layout viewport leaves the body box below the
         *  band the user can see, so "inside bodyBottom" is not "on screen". On
         *  `adjustResize` engines `offsetTop` is 0 and the intersection is a no-op. */
        const keepAnswerVisible = () => {
            // Read the focus owner first and only then walk up: on the keyboard's
            // resize storm this runs once per frame, and probing for the card with two
            // document queries would pay that cost even when no answer field is
            // focused (i.e. every keyboard episode that is not about this card).
            const active = document.activeElement;
            if (!(active instanceof HTMLElement) || !active.matches(ANSWER_FIELD_SELECTOR))
                return;
            // 2026-11-03：手机档滚动区从 body 换成了整张卡（layout.css.ts question-card-single-scroll），
            // 所以滚卡片；卡片不滚（老样式 / 最小化）时仍退回 body。底部钉住的按钮行要从
            // 可视底边里扣掉，否则回答框会停在按钮后面。
            const cardEl = active.closest('[class*="Mbwy4a_card"]');
            const bodyEl = active.closest(CARD_BODY_SELECTOR);
            const body = cardEl !== null && cardEl.scrollHeight > cardEl.clientHeight + 1 ? cardEl : bodyEl;
            if (body === null)
                return;
            const pinned = body === cardEl ? cardEl.querySelector(':scope > [class*="Mbwy4a_footer"]') : null;
            const field = active;
            const fieldRect = field.getBoundingClientRect();
            const box = body.getBoundingClientRect();
            const pinnedTop = pinned?.getBoundingClientRect().top ?? Number.NaN;
            const bodyRect = { top: box.top, bottom: Number.isFinite(pinnedTop) ? Math.min(box.bottom, pinnedTop) : box.bottom };
            const visual = window.visualViewport;
            const visibleTop = visual?.offsetTop ?? Number.NaN;
            const visibleBottom = visual === null || visual === undefined ? Number.NaN : visual.offsetTop + visual.height;
            const next = (0, question_fullscreen_ts_1.answerFieldScrollTop)({
                fieldTop: fieldRect.top,
                fieldBottom: fieldRect.bottom,
                bodyTop: bodyRect.top,
                bodyBottom: bodyRect.bottom,
                scrollTop: body.scrollTop,
                scrollHeight: body.scrollHeight,
                clientHeight: body.clientHeight,
                visibleTop,
                visibleBottom,
            });
            if (Math.abs(next - body.scrollTop) >= 1)
                body.scrollTop = next;
        };
        /** Measure the band, publish the reserve and the marker. One rAF per
         *  viewport episode: the IME animation arrives as a resize storm. */
        const measure = () => {
            frame = 0;
            const host = (0, frame_ts_1.getFrame)();
            const card = cardFrame();
            if (host === null || card === null) {
                clear();
                return;
            }
            const seat = host.querySelector(SEAT_SELECTOR);
            const cardRect = card.getBoundingClientRect();
            const seatRect = seat?.getBoundingClientRect() ?? null;
            const style = getComputedStyle(card);
            const visible = readVarPx(VISIBLE_VH_VAR);
            const band = (0, question_fullscreen_ts_1.questionBandPx)({
                visibleHeight: visible,
                headerHeight: readVarPx(HEADER_H_VAR),
                frameBottom: cardRect.bottom,
                seatBottom: seatRect === null ? Number.NaN : seatRect.bottom,
                framePadding: Number.parseFloat(style.paddingTop) + Number.parseFloat(style.paddingBottom),
            });
            const next = (0, question_fullscreen_ts_1.nextQuestionFull)(armed, requested, band.bandPx, (0, question_fullscreen_ts_1.hostCardHeightPx)(visible));
            const nextReserve = `${Math.round(band.reservePx)}px`;
            if (published !== card) {
                published?.style.removeProperty(RESERVE_VAR);
                published = card;
                reserveText = '';
            }
            if (reserveText !== nextReserve) {
                reserveText = nextReserve;
                card.style.setProperty(RESERVE_VAR, nextReserve);
            }
            if (next !== host.hasAttribute(FULL_MARKER)) {
                if (next)
                    host.setAttribute(FULL_MARKER, '');
                else
                    host.removeAttribute(FULL_MARKER);
            }
            armed = next;
            syncButton();
            // A band that cannot be measured must not keep the intent alive: the next
            // successful measurement would otherwise expand the card on its own.
            if (!armed)
                requested = false;
        };
        const schedule = () => {
            if (frame !== 0)
                return;
            frame = requestAnimationFrame(() => {
                frame = 0;
                keepAnswerVisible();
                if (requested)
                    measure();
            });
        };
        const onClick = () => {
            // Toggle the state that is actually on screen, not the intent: an
            // unmeasurable band leaves the marker off, so a second tap must try to
            // arm again instead of "collapsing" a card that never expanded.
            requested = !armed;
            if (frame !== 0) {
                cancelAnimationFrame(frame);
                frame = 0;
            }
            measure();
            if (!armed)
                requested = false;
        };
        /** Idempotent injection into the card's action row. Inserted just before
         *  the host's last action (the close/cancel button) so the two sizing
         *  controls sit together and the destructive one keeps the far edge; the
         *  row's existing 12px gap then gives this control the same 32px touch
         *  target as the host pair (4px expansion lives in the stylesheet). */
        const ensureButton = (card) => {
            const actions = card.querySelector(HEADER_ACTIONS_SELECTOR);
            if (actions === null)
                return;
            if (button === null) {
                const element = document.createElement('button');
                element.type = 'button';
                element.dataset.mobileNav = BUTTON_MARKER;
                element.innerHTML = ICONS;
                element.addEventListener('click', onClick);
                button = element;
            }
            // Parent identity only: re-inserting on every flush would be a mutation
            // loop, and the row's order is stable enough for a position check to be
            // noise.
            if (button.parentElement !== actions) {
                actions.insertBefore(button, actions.lastElementChild);
            }
            syncButton();
        };
        const task = {
            name: 'question-fullscreen',
            scopes: ['*'],
            ensure: () => {
                const card = cardFrame();
                if (card === null) {
                    clear();
                    return;
                }
                // Fullscreen only makes sense while the card has an answer area: the
                // host drops Mbwy4a_body when the card is minimized, and the review
                // panel (PlanReviewPanel) never renders one — both keep the header row,
                // so injecting there would offer a toggle with nothing to expand.
                if (card.querySelector(CARD_BODY_SELECTOR) === null) {
                    button?.remove();
                    clear();
                    return;
                }
                ensureButton(card);
                // Geometry is not read here: this task wakes on every mutation batch
                // (streaming included), and a rect read per batch is exactly the cost
                // header-metrics.ts removed. One coalesced frame is enough.
                if (requested)
                    schedule();
            },
            dispose: () => {
                clear();
                if (button !== null) {
                    button.removeEventListener('click', onClick);
                    button.remove();
                    button = null;
                }
            },
        };
        const removeTask = (0, reconciler_adapter_ts_1.addReconcilerTask)(task);
        // Every episode that relayouts the band without a DOM mutation: the IME
        // animation, the rotation, the keyboard-less restore, and the card mounting
        // under an already-shrunk band. visualViewport.resize is the per-frame IME
        // signal; `scroll` is deliberately absent — panning the visual viewport
        // does not change the card body's own scroll geometry, and listening to it
        // would fight a drag inside the card.
        window.addEventListener('resize', schedule);
        window.addEventListener('orientationchange', schedule);
        window.visualViewport?.addEventListener('resize', schedule);
        // 可见带变量是在键盘停稳后才发布的（viewport-meta），晚于最后一次 resize：
        // 发布那一刻再量一次，否则 reserve 停在发布前的旧几何上。
        document.addEventListener(visible_band_ts_1.VISIBLE_BAND_EVENT, schedule);
        document.addEventListener('focusin', schedule, true);
        document.addEventListener('focusout', schedule, true);
        schedule();
        return () => {
            window.removeEventListener('resize', schedule);
            window.removeEventListener('orientationchange', schedule);
            window.visualViewport?.removeEventListener('resize', schedule);
            document.removeEventListener(visible_band_ts_1.VISIBLE_BAND_EVENT, schedule);
            document.removeEventListener('focusin', schedule, true);
            document.removeEventListener('focusout', schedule, true);
            removeTask();
        };
    });
}
};
__modules["effects/composer-plus-toggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installComposerPlusToggle = installComposerPlusToggle;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 加号「再点关闭」的接管（2026-09-23 二轮修订，推翻上一轮的根因判断）。
 *
 * 症状：加号点开候选菜单后，**再点加号关不掉**（菜单原地不动，等于又开一次）。
 *
 * 真根因（读 0.1.7 产物得出，另有 `aria-expanded` 旁证）：
 * `dsh-client-ui-input-trigger` 的 `toggleSource()` 本来就会关 ——
 *
 *   toggleSource(source, hit) {
 *     if (this.launcher.getSnapshot() === source && this.menu.getSnapshot().open) {
 *       this.dismiss()            // ← 第二击本应走这里
 *       return
 *     }
 *     …打开…
 *   }
 *
 * 但加号的 onClick 先跑 `focusDraftEditor(editor, revealSelection)`，编辑器一有
 * update 就回调 `onEditorUpdate()` → `inputTriggers.track(...)`；而 `track()` 开头是
 *
 *   const launched = this.launcher.getSnapshot() !== null
 *   this.clearLauncher()                              // ← launcher 被清成 null
 *   const raw = detectTrigger(draft, caret, guard)
 *   if (raw === null) { if (launched) return; … }     // ← 菜单留着，launcher 却没了
 *
 * ⇒ 轮到 toggleSource 时 `launcher` 已是 null，"已开就关"这一支永远不可达，
 *   于是每一击都等于"再开一次"。
 * 旁证：`+` 的 `aria-expanded` = `useMenuLauncher(s => s === 'command')`，
 *   菜单明明开着它却恒为 false —— 正是 launcher 被 track 清掉后的直接读数。
 *
 * 上一轮的两个误判，一并纠正在这里：
 *   · `shell.dismissPopup()` 关的是 `commandUi.popupFor()` 的 **popupSelect 壳**
 *     （选完指令后的选项面板，此刻根本没开），跟这个菜单无关，删它不解决问题；
 *   · 这个菜单是 `input-trigger` 的 MenuView：根节点带 **`data-trigger-menu`**，
 *     `role="listbox"` 只是它内部那一层 viewport；它挂在 `[data-composer-card]`
 *     里面，MenuView 的 outside-pointerdown 还专门豁免了这张卡片
 *     （`listRef.closest('[data-composer-card]')`），所以点卡片内的加号根本不会
 *     触发它的关闭。而 popupSelect 壳的卡片**没有任何 role**，上一轮按 role 找它
 *     必然找不到（`probe_esc` 全程 `menu=none` 就是这么来的）。
 *
 * 修法（只补这一个缺口，宿主行为原样保留）：
 *   click **捕获**阶段记下"点加号之前菜单是不是开着"；
 *   click **冒泡**阶段（React 挂在 root 容器上的监听器早已跑完）若菜单**仍然**开着，
 *   说明宿主的关闭分支又被 launcher 清空吃掉了 —— 这时才补一刀：朝 Lexical 根
 *   （`[data-composer-input]`）发一次 Escape。
 *   Escape 是宿主自己的关闭路径（编辑器的 escape 命令 → `arbitrate('escape')`
 *   → `reduce({ close })`），而且只认挂在编辑器根上的 keydown —— 必须 dispatch 在
 *   编辑器上，不能像上一轮那样发在菜单元素上（那边事件根本到不了 Lexical）。
 * 菜单本来就关着时（第一击的开启路径）完全不介入。
 */
/** 宿主加号按钮（dsh-client-ui-conversation 0.2.2 的 uV2eYG_add）；带包前缀，
 *  不再用裸 `_add` 子串——它会误中 `_addon`/`_address` 一类无关类名。 */
const ADD_SELECTOR = '[class*="uV2eYG_add"]';
/** slash/命令候选菜单的根：MenuView 自带这个标记，比样式哈希稳定。 */
const MENU_SELECTOR = '[data-trigger-menu]';
/** Lexical 的可编辑根：Escape 只在这里被宿主映射成命令。 */
const EDITOR_SELECTOR = '[data-composer-input]';
/** 收起后 React 会立刻摘掉节点，这里再兜一层"看得见才算开着"。 */
const isVisible = (el) => {
    const box = el.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && el.getClientRects().length > 0;
};
const openMenu = () => {
    for (const el of document.querySelectorAll(MENU_SELECTOR))
        if (isVisible(el))
            return el;
    return null;
};
const editorEl = () => {
    const el = document.querySelector(EDITOR_SELECTOR);
    return el instanceof HTMLElement ? el : null;
};
/**
 * 命令菜单不需要软键盘。
 *
 * 真机机制（2026-09-23 三次取证）：点 `+` 之前编辑器往往**还"逻辑上"聚焦着**
 * （用户滚屏收起了键盘，DOM focus 没走），宿主的 `keepFocus` 又对按钮的 mousedown
 * 做了 `preventDefault()` ⇒ 这一下不会 blur，于是 Android 在真实手势里把刚收起的
 * IME 重新顶起来（探针：点前 `k754`，点后 ~170ms `k471`），整行上移 ~283 CSS px，
 * 店主第二下点的"加号原位"就落进软键盘了（页面收不到任何事件）。
 * 守卫那套影子只拦**程序化 focus()**，拦不到这条 IME 路径，所以必须主动收：
 * 按下加号的捕获阶段先把 DOM 焦点放掉，IME 就没有可依附的编辑面。
 */
const dropEditorFocus = () => {
    const editor = editorEl();
    if (editor !== null && document.activeElement === editor)
        editor.blur();
};
/** 走宿主自己的关闭路径。菜单没开时它一路 no-op（`arbitrate` 回 'pass'），可以放心重发。 */
const escapeEditor = () => {
    const editor = editorEl();
    if (editor === null)
        return;
    editor.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape',
        code: 'Escape',
        keyCode: 27,
        which: 27,
        bubbles: true,
        cancelable: true,
    }));
};
function installComposerPlusToggle(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer plus toggle', () => {
        /** 这一击落下之前菜单开着吗（捕获阶段读，早于宿主 onClick）。 */
        let openBeforeClick = false;
        /** 菜单开着期间的兜底收键盘计时器；用户一点编辑面就全部取消。 */
        let collapseTimers = [];
        const cancelCollapse = () => {
            for (const id of collapseTimers)
                window.clearTimeout(id);
            collapseTimers = [];
        };
        const onClickCapture = (event) => {
            const target = event.target;
            openBeforeClick =
                target instanceof Element && target.closest(ADD_SELECTOR) !== null && openMenu() !== null;
        };
        const onPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(ADD_SELECTOR) === null)
                return;
            // 断掉 IME 的依附面（见 dropEditorFocus 的注释）。
            dropEditorFocus();
        };
        const onClickBubble = (event) => {
            const wasOpen = openBeforeClick;
            openBeforeClick = false;
            const target = event.target;
            if (!(target instanceof Element) || target.closest(ADD_SELECTOR) === null)
                return;
            // 宿主那套 focus → track(clearLauncher) → toggle 此刻已经跑完：
            // 菜单还开着 = 它的关闭分支又被吃了，由我们关；已经关掉就什么都不做。
            if (wasOpen && openMenu() !== null)
                escapeEditor();
            // 兜底：宿主可能在"菜单打开后的 effect"里再聚焦一次，把键盘重新顶起来。
            // 只在菜单开着时按，用户一碰编辑面就全撤（见 onEditorPointerDown）。
            cancelCollapse();
            for (const delay of [120, 320, 640]) {
                collapseTimers.push(window.setTimeout(() => {
                    if (openMenu() !== null)
                        dropEditorFocus();
                }, delay));
            }
        };
        /** 用户点了编辑面 = 要打字，任何兜底收键盘立刻作废（别和手指抢）。 */
        const onEditorPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(EDITOR_SELECTOR) === null)
                return;
            cancelCollapse();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointerdown', onEditorPointerDown, true);
        document.addEventListener('click', onClickCapture, true);
        document.addEventListener('click', onClickBubble, false);
        return () => {
            cancelCollapse();
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('pointerdown', onEditorPointerDown, true);
            document.removeEventListener('click', onClickCapture, true);
            document.removeEventListener('click', onClickBubble, false);
        };
    });
}
};
__modules["effects/workspace-chip-toggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installWorkspaceChipToggle = installWorkspaceChipToggle;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 工作区 chip「再点关闭」的接管（2026-09-23，对账 0.1.7-rc.1）。
 *
 * 症状：hero 空态的工作区 chip，第一次点开工作区列表，**再点 chip 关不掉**
 * （列表原地不动，等于又开一次）。要求的行为：单击打开、再单击关闭。
 *
 * 真根因（读宿主源码得出）：
 * `ui-conversation` 的 chip 自己是正常 toggle（`ConversationContent.tsx`）：
 *
 *   onClick: () => { setPickerOpen(open => !open) }
 *
 * 关不掉的原因在 `ui-workspace` 的 WorkspacePickFlow 怎么开这个菜单：
 *
 *   <Menu anchor={null} portal getAnchorRect={anchorRef.current.getBoundingClientRect} … />
 *
 * `anchor={null}` ⇒ Menu 的 rootRef 是一个**空 span**，触发器 chip 在 Menu 子树之外；
 * 而 `ui-primitives/Menu.tsx` 的「外部 pointerdown 关闭」只豁免 rootRef / listRef：
 *
 *   if (rootRef.current?.contains(target) === true) return
 *   if (listRef.current?.contains(target) === true) return
 *   onClose()
 *
 * ⇒ 菜单开着时点 chip：pointerdown 先被判成「外部点击」→ onClose()（翻到 false），
 *   紧接着的 click 到达 chip 的 onClick → 又翻回 true。净效果＝再开一次。
 * 旁证：同行的「预设」触发器传的是 `anchor={<button …/>}`（按钮在 rootRef 内），
 * pointerdown 不被判外部，所以它没有这个毛病 —— 同一份 Menu，两种接线。
 *
 * 修法（只补这一个缺口，宿主关闭路径原样保留）：
 *   pointerdown 捕获阶段：chip 自报 `aria-expanded="true"`（＝菜单真开着）且宿主的
 *   portal 菜单在场时，记下这一击；
 *   click 捕获阶段：同一 chip 的 click 直接 stopPropagation —— React 挂在 root 容器上
 *   的 onClick 不再执行，chip 的 toggle 不会被翻回「开」，宿主 pointerdown 的那次
 *   关闭成为唯一结果。菜单本来就关着时（第一击的开启路径）完全不介入。
 *
 * 开态读 `aria-expanded` 而不是「点在不在菜单里」：pointerdown 阶段 React 尚未重渲染，
 * 读到的是这一击之前的真实状态；等到 click 再读 DOM 会读到未冲刷的旧树。
 */
/** hero 工作区 chip：hero 行的**直接子**按钮（预设触发器在 Menu 的 anchor span 里，不是直接子）。 */
const CHIP_SELECTOR = '[class*="heroWorkspaceRow"] > button[aria-haspopup="menu"]';
/** 宿主 Menu 的 portal 列表：只有它在场，宿主的「外部 pointerdown 关闭」才存在。 */
const OPEN_MENU_SELECTOR = '[role="menu"]';
function installWorkspaceChipToggle(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: workspace chip toggle', () => {
        /** 这一击之前 chip 报「菜单开着」的那颗 chip（否则 null）。 */
        let armed = null;
        const chipFrom = (target) => target instanceof Element ? target.closest(CHIP_SELECTOR) : null;
        const onPointerDownCapture = (event) => {
            armed = null;
            const chip = chipFrom(event.target);
            if (chip === null)
                return;
            if (chip.getAttribute('aria-expanded') !== 'true')
                return;
            if (document.querySelector(OPEN_MENU_SELECTOR) === null)
                return;
            armed = chip;
        };
        const onClickCapture = (event) => {
            const chip = armed;
            armed = null;
            if (chip === null || chipFrom(event.target) !== chip)
                return;
            event.stopPropagation();
        };
        document.addEventListener('pointerdown', onPointerDownCapture, true);
        document.addEventListener('click', onClickCapture, true);
        return () => {
            armed = null;
            document.removeEventListener('pointerdown', onPointerDownCapture, true);
            document.removeEventListener('click', onClickCapture, true);
        };
    });
}
};
__modules["effects/team-chip-toggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installTeamChipToggle = installTeamChipToggle;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 团队 chip「再点关闭」的接管（2026-09-23，对账 0.1.7-rc.1）。
 *
 * 症状：点头部那颗智能体团队图标能打开面板，**再点同一颗关不掉**，必须点面板
 * 四周（或按 Escape）才关。要求的行为：单击打开、再单击关闭。
 *
 * 真根因（读宿主源码得出，本轮真机复现）：
 * `dsh-experimental-client-ui-agent-team/lib/client.js` 的触发器 onClick 只处理
 * 「开」，开态时改为聚焦面板 —— 它自己**永远不关**：
 *
 *   onClick: () => {
 *     cancelHoverChange()
 *     pinnedRef.current = true
 *     if (!open) changeOpen(true)
 *     else panelRef.current?.focus()   // 开着就只 focus，不 toggle
 *   }
 *
 * 关闭路径只有两条：`ui-primitives` 的 useDismissOnOutsidePointer（document 上的
 * pointerdown，靶心在 root/panel 之外即 setOpen(false)）与面板内注册的 Escape
 * keydown。桌面靠 hover 开合，这个「点了只 pin 不 toggle」不成问题；手机上就成了
 * 「点了关不掉」。
 *
 * 修法（替用户把「点四周」这件事做掉，宿主两条关闭路径原样保留）：
 *   pointerdown 捕获阶段：触发器自报 aria-expanded="true" 且宿主的 body portal
 *   面板在场时，记下这一击；
 *   click 捕获阶段：同一触发器 → 先向 document.body 派发一次合成 pointerdown
 *   （靶心在 root 与面板之外 ⇒ 命中宿主自己的 outside-dismiss ⇒ 真关闭），
 *   再 stopPropagation 掉这一击 click —— 否则宿主的 onClick 还会在旧闭包里走
 *   else 分支去 focus 面板。
 *
 * 为什么先派发再吞 click（顺序不可换）：宿主 dismiss 是 document 上的 bubble
 * 监听，我们处在 capture 阶段，同步派发即同步生效；而 click 一旦放过去，宿主的
 * onClick 会看到尚未冲刷的 open=true 并执行 focus。
 *
 * 开态读 aria-expanded 而不是「点在不在面板里」：pointerdown 阶段 React 尚未重渲染，
 * 读到的是这一击之前的真实状态（与 workspace-chip-toggle 同一条判据）。
 *
 * 门控为什么是 TOUCH_QUERY 而不是 MOBILE_QUERY（2026-10-11，用户报障「平板模式下再点
 * 收不起来」）：宽视口（平板 / 折叠屏展开 / DSHA 平板模式）下 `max-width: 1023px` 恒不命中
 * ⇒ 原先 MOBILE_QUERY 门控时本效果**根本没安装**；而宽触屏同样没有 hover 兜底，用户只剩
 * 「点面板外」或 Escape 一条路。这与 v2.4.1「删除会话三件套」同构（session-menu 走
 * TOUCH_QUERY：该特性在桌面没有等价物，宽触屏也该有）。门控取 (pointer: coarse) ⇒
 * 鼠标驱动的窗口（含窄桌面窗口）在任意宽度都不命中，桌面仍是零介入。
 */
/** 团队 chip 根：宿主 agent-team 实验插件的稳定标记。 */
const ROOT_SELECTOR = '[data-team-action]';
/** 触发器：根的直接子按钮（aria-haspopup 是 dialog，不是 menu）。 */
const TRIGGER_SELECTOR = '[data-team-action] > button[aria-haspopup="dialog"]';
/** 面板：宿主 portal 到 body、带稳定标记与 role="dialog"。 */
const PANEL_SELECTOR = '[data-team-panel]';
function installTeamChipToggle(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: team chip toggle', () => {
        /** 这一击之前自报「面板开着」的那颗触发器（否则 null）。 */
        let armed = null;
        const triggerFrom = (target) => target instanceof Element ? target.closest(TRIGGER_SELECTOR) : null;
        const onPointerDownCapture = (event) => {
            armed = null;
            const trigger = triggerFrom(event.target);
            if (trigger === null)
                return;
            if (trigger.getAttribute('aria-expanded') !== 'true')
                return;
            if (document.querySelector(ROOT_SELECTOR) === null)
                return;
            if (document.querySelector(PANEL_SELECTOR) === null)
                return;
            armed = trigger;
        };
        const onClickCapture = (event) => {
            const trigger = armed;
            armed = null;
            if (trigger === null || triggerFrom(event.target) !== trigger)
                return;
            // 「点四周」这一步必须用 pointerdown：宿主的 dismiss 只监听 pointerdown。
            // 靶心选 document.body —— 它既不在 root 内、也不在面板内，是最省事的真外部。
            document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
            event.stopPropagation();
        };
        document.addEventListener('pointerdown', onPointerDownCapture, true);
        document.addEventListener('click', onClickCapture, true);
        return () => {
            armed = null;
            document.removeEventListener('pointerdown', onPointerDownCapture, true);
            document.removeEventListener('click', onClickCapture, true);
        };
    }, breakpoints_ts_1.TOUCH_QUERY);
}
};
__modules["effects/model-menu-anchor.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installModelMenuAnchor = installModelMenuAnchor;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 模型 / 推理等级菜单的锚点修正（2026-09-23 店主："这个模型打开，是不是有点偏左边？"）。
 *
 * 真机取证（360×754）：
 *   MENU  box=12,605,246,74   class=_7KE1Ra_menu  role=menu  aria-label="模型与推理等级"
 *         style="left: 12px; top: 605px"  position:fixed  **parent=BODY**（portal 出去的）
 *   CARD  [data-composer-card] = 16..342      TRIG = 219..249, 687..715
 *
 * 宿主按「菜单右缘贴触发器右缘」定位 ⇒ 菜单落 12..258（店主第一眼："是不是有点偏左边？"）。
 * 本插件早先用 CSS 居中过它，但菜单 portal 到 body 之后那条 `_root > _menu` 子代链断掉，
 * 规则成了**死规则**。CSS 够不到 portal 节点，所以在这里用 JS 重锚。
 *
 * 落位：菜单能完整放入视口时右缘贴触发器；窄屏越界或触发器不可用时，
 * 回退为输入框内居中。两种锚点都不可用时保持宿主位置。其它菜单不碰。
 *
 * ## 成本（2026-09-23 优化，店主批准）
 *
 * 本机实测（18,359 节点）：`querySelectorAll('[class*="_7KE1Ra_menu"]')` = **0.568ms/次**、
 * `querySelector('[data-composer-card]')` = 0.08ms/次。旧版把这些查询挂在
 * `pointerdown`/`click`/`resize`/`scroll` 上 ⇒ **菜单关着时每次滚动也白花约 0.65ms/帧**
 * （模型流式输出时页面每帧都在滚，最吃这一口；60Hz 帧预算的 4%、120Hz 的 8%）。
 *
 * 现在分两条路径：
 *   · `refresh()` —— **唯一的查询入口**，只在"可能开关菜单"的交互后跑（点到/聚焦/按键在
 *     触发器或菜单上）。查到后缓存菜单、触发器和输入框。
 *   · `follow()` —— 滚动/改变尺寸只对缓存节点重算位置，
 *     `active === null` 时**直接返回、零查询**。
 *
 * 为什么不能干脆删掉 scroll 监听：实测滚动时输入框卡片会移动（同会话内 365 → 648），
 * 菜单开着时得跟着挪，否则会和卡片错位。
 *
 * 为什么不用 MutationObserver：会话在流式输出，`subtree` 观察等于每帧扫全场。
 *
 * ## 成本（2026-10-13 第二轮，三处同帧重复工作）
 *
 * 1. **rect 一帧一读**：`findMenu()` 为判定「菜单可见」先读一次菜单盒，`place()` 紧接着
 *    又对**同一个**菜单节点读第二次（旧版 :76-77 → :84）。现在本帧内同一个元素的
 *    `getBoundingClientRect()` 只读一次、结果在帧内复用（`frameRects`）。
 * 2. **rAF 槽合并**：旧版 `if (raf !== 0) return` 是「先到者胜」——同一帧里先排了
 *    `follow()` 再来的 `refresh()` 会被整份丢掉，那次交互当帧就不落位。改成「槽位保存
 *    最新那份意图」，后到者覆盖先到者，请求不再丢。
 * 3. **还值不置空**：旧版收尾统一把 inline left 置成空串，会把宿主自己那行位置一起抹掉；
 *    现在记下第一次接管时的原值并按原值还回去。
 */
/** 模型触发器（图标形态的 chip）。 */
const MODEL_TRIGGER = '[class*="_7KE1Ra_trigger"]';
/** 模型 / 推理等级菜单（portal 在 body 下）。 */
const MODEL_MENU = '[class*="_7KE1Ra_menu"]';
/** 输入框（composer 卡片）—— 菜单在它里面水平居中。 */
const COMPOSER_CARD = '[data-composer-card]';
/** 贴边留白。 */
const GUTTER = 8;
/** 宿主在打开动画/二次测量里会再写位置，补几次收尾（毫秒）。 */
const SETTLE_MS = [0, 60, 200];
function installModelMenuAnchor(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: model menu anchor', () => {
        let raf = 0;
        /** 本帧待跑的那一份意图。后到的请求覆盖它（见 schedule()）。 */
        let pending = null;
        /**
         * 本帧的 rect 缓存（键 = 元素）。同一趟定位里同一个元素只读一次：`findMenu()` 判定
         * 可见性要读菜单盒，`place()` 又用同一个菜单盒。帧开始重建、帧结束丢弃，
         * 所以跨帧（布局可能已经变了）绝不会用到陈旧值。
         */
        let frameRects = null;
        const timers = [];
        /** 已确认「开着」的菜单节点；null 表示当前没有菜单（滚动路径据此零查询）。 */
        let active = null;
        let activeTrigger = null;
        let activeCard = null;
        /**
         * 我们最后写进 inline left 的节点与值。卸载/菜单消失时要把这行还回去 ——
         * 否则宿主之后再渲染同一个菜单会带着我们留下的位置（issue #86）。
         * 只在值仍是我们写的那份时才还，避免抹掉宿主自己写的位置。
         *
         * `original` = **第一次接管这个菜单时**宿主留下的那行 inline left（可能是空串，
         * 也可能是宿主自己写过的值）。收尾按它还原 —— 旧版一律置空串，宿主的位置会被一起抹掉。
         */
        let placedLeft = null;
        /** 交还我们写过的那行 inline left（值仍是我们写的才动），并放弃所有权。 */
        const releasePlacement = () => {
            const placed = placedLeft;
            placedLeft = null;
            if (placed !== null && placed.el.style.left === placed.value)
                placed.el.style.left = placed.original;
        };
        /**
         * 本文件**唯一**的 rect 读点。同一帧里同一个元素复用第一次的结果
         * （`frameRects` 为 null 时——理论上只在帧外——退化为裸读，行为不变）。
         */
        const laidOut = (el) => {
            if (el === null)
                return null;
            const cached = frameRects?.get(el);
            if (cached !== undefined)
                return cached;
            const box = el.getBoundingClientRect();
            const result = box.width > 0 && box.height > 0 ? box : null;
            frameRects?.set(el, result);
            return result;
        };
        /** 唯一的全文档查询入口（只在交互路径调用，见文件头「成本」）。 */
        const findMenu = () => {
            for (const el of document.querySelectorAll(MODEL_MENU)) {
                if (laidOut(el) !== null)
                    return el;
            }
            return null;
        };
        /** Align to the trigger's right edge when it fits; otherwise center on the card. */
        const place = (menu) => {
            const menuBox = laidOut(menu);
            if (menuBox === null)
                return;
            const width = menuBox.width;
            const viewport = document.documentElement.clientWidth;
            const max = Math.max(GUTTER, viewport - width - GUTTER);
            const triggerBox = laidOut(activeTrigger);
            const cardBox = laidOut(activeCard);
            const flush = triggerBox === null ? null : triggerBox.right - width;
            let left;
            if (flush !== null && flush >= GUTTER && flush <= max)
                left = flush;
            else if (cardBox !== null)
                left = cardBox.left + (cardBox.width - width) / 2;
            else
                return;
            const next = `${Math.round(Math.min(Math.max(left, GUTTER), max))}px`;
            if (placedLeft !== null && placedLeft.el !== menu)
                releasePlacement();
            if (placedLeft === null) {
                // 第一次接管：先记原值，再写我们的值。
                placedLeft = { el: menu, value: next, original: menu.style.left };
            }
            else {
                placedLeft.value = next;
            }
            if (menu.style.left !== next)
                menu.style.left = next;
        };
        /** 交互路径：刷新缓存（会查询）并按新位置落位。 */
        const refresh = () => {
            active = findMenu();
            activeTrigger = active === null ? null : document.querySelector(MODEL_TRIGGER);
            activeCard = active === null ? null : document.querySelector(COMPOSER_CARD);
            if (active !== null)
                place(active);
        };
        /** 滚动 / 改变尺寸路径：只用缓存节点重算，不查询。 */
        const follow = () => {
            if (active === null)
                return;
            if (laidOut(active) === null) {
                // 菜单已经不在了（收起/卸载）：把位置还回去，别再占着。
                releasePlacement();
                active = null;
                activeTrigger = null;
                activeCard = null;
                return;
            }
            place(active);
        };
        /**
         * 把一次定位请求排进下一帧。**合并语义**：一帧只跑一次，槽位存的是「最新那份意图」，
         * 后到的请求覆盖先到的（不是被丢掉）。
         *
         * 旧版是「先到者胜」的 `if (raf !== 0) return`：同一帧里 `follow()` 之后来的
         * `refresh()` 会被整份吃掉 —— 用户刚点开菜单、同一帧页面又在滚（流式输出时常态）时，
         * 那次交互当帧不落位，只能等 60ms/200ms 的收尾计时器兜底。
         */
        const schedule = (run) => {
            pending = run;
            if (raf !== 0)
                return;
            raf = window.requestAnimationFrame(() => {
                raf = 0;
                const job = pending;
                pending = null;
                frameRects = new Map();
                try {
                    if (job !== null)
                        job();
                }
                finally {
                    frameRects = null;
                }
            });
        };
        /** 交互后补几次落位（宿主在打开动画/二次测量里还会再写一次）。 */
        const scheduleRefresh = () => {
            schedule(refresh);
            for (const delay of SETTLE_MS)
                timers.push(window.setTimeout(() => schedule(refresh), delay));
            // 计时器只留最近一轮，避免长会话里越积越多。
            while (timers.length > SETTLE_MS.length * 2) {
                const stale = timers.shift();
                if (stale !== undefined)
                    window.clearTimeout(stale);
            }
        };
        /** 只有"可能开关菜单"的交互才需要查询：命中触发器或菜单本身。 */
        const touchesMenu = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return false;
            return target.closest(MODEL_TRIGGER) !== null || target.closest(MODEL_MENU) !== null;
        };
        const onPointerDown = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        // 键盘/无障碍路径（聚焦触发器后按 Enter）与合成 click 也要覆盖。
        const onKeyDown = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        const onFocusIn = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        const onClick = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        // 视口变化只走"零查询"的重算路径。
        const onViewportChange = () => {
            schedule(follow);
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        document.addEventListener('click', onClick, true);
        window.addEventListener('resize', onViewportChange);
        document.addEventListener('scroll', onViewportChange, true);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('keydown', onKeyDown, true);
            document.removeEventListener('focusin', onFocusIn, true);
            document.removeEventListener('click', onClick, true);
            window.removeEventListener('resize', onViewportChange);
            document.removeEventListener('scroll', onViewportChange, true);
            if (raf !== 0)
                window.cancelAnimationFrame(raf);
            pending = null;
            frameRects = null;
            for (const timer of timers)
                window.clearTimeout(timer);
            timers.length = 0;
            // 还回我们改写的那行 inline left（只在值仍是我们写的时候，按第一次接管时的原值还原）。
            releasePlacement();
            active = null;
            activeTrigger = null;
            activeCard = null;
        };
    });
}
};
__modules["core/prototype-focus-shadow.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.shadowFocus = shadowFocus;
const rules = new Set();
let restore = null;
function shadowFocus(shouldSkip) {
    if (restore === null) {
        const proto = HTMLInputElement.prototype;
        const descriptor = Object.getOwnPropertyDescriptor(proto, 'focus');
        const previous = proto.focus;
        if (typeof previous !== 'function' || descriptor?.configurable === false ||
            (!descriptor && !Object.isExtensible(proto)))
            return () => { };
        const wrapper = function focus(options) {
            for (const rule of rules)
                if (rule.shouldSkip(this))
                    return;
            previous.call(this, options);
        };
        try {
            Object.defineProperty(proto, 'focus', {
                configurable: true, writable: true,
                enumerable: descriptor?.enumerable ?? false, value: wrapper,
            });
        }
        catch {
            return () => { };
        }
        restore = () => {
            if (Object.getOwnPropertyDescriptor(proto, 'focus')?.value !== wrapper)
                return;
            try {
                if (descriptor)
                    Object.defineProperty(proto, 'focus', descriptor);
                else
                    delete proto.focus;
            }
            catch {
                // An externally frozen wrapper is transparent once its rules are gone.
            }
        };
    }
    const rule = { shouldSkip };
    rules.add(rule);
    return () => {
        if (!rules.delete(rule))
            return;
        if (rules.size === 0) {
            const release = restore;
            restore = null;
            release?.();
        }
    };
}
};
__modules["effects/shortcut-modal-keyboard-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installShortcutModalKeyboardGuard = installShortcutModalKeyboardGuard;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const prototype_focus_shadow_ts_1 = require("./core/prototype-focus-shadow.js");
const AUTOFOCUS_FIELD = '[data-shortcut-modal="shortcuts"] [data-modal-autofocus]';
/** DSHA: suppress only the first automatic focus, retaining user search access. */
function installShortcutModalKeyboardGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: shortcut modal keyboard guard', () => {
        const initialized = new WeakSet(document.querySelectorAll(AUTOFOCUS_FIELD));
        const pending = new WeakSet();
        const release = (0, prototype_focus_shadow_ts_1.shadowFocus)(element => {
            if (element.matches(AUTOFOCUS_FIELD)) {
                if (!initialized.has(element) && element.hasAttribute('data-dsh-automatic-focus')) {
                    if (!pending.has(element)) {
                        pending.add(element);
                        Promise.resolve().then(() => initialized.add(element));
                        const dialog = element.closest('[role="dialog"][aria-modal="true"]');
                        if (dialog !== null && !dialog.contains(document.activeElement)) {
                            dialog.focus({ preventScroll: true });
                        }
                    }
                    return true;
                }
                initialized.add(element);
            }
            return false;
        });
        return () => release();
    });
}
};
__modules["effects/model-menu-keyboard-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installModelMenuKeyboardGuard = installModelMenuKeyboardGuard;
const prototype_focus_shadow_ts_1 = require("./core/prototype-focus-shadow.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * Mobile guard: opening the host's model / reasoning-level menu must not raise
 * the soft keyboard by itself (owner report 2026-10-07, screenshot: the menu
 * opens with 「搜索模型…」 focused and the keyboard covers the whole list).
 *
 * `dsh-client-ui-model-selection` renders a 模型 / 推理等级 menu; tapping 「模型」
 * drills into the model pane and a `useEffect` calls
 * `searchRef.current?.focus()` (`dsh-client-ui-model-selection/lib/client.js:29497`,
 * the same call also fires from the menu's ArrowDown handler at :33452). On a
 * phone that costs half the screen the moment the user asks for the model list.
 *
 * Why the METHOD shadow (the technique `shortcut-modal-keyboard-guard.ts` uses)
 * and why the capture-phase pointerdown: that focus runs in a PASSIVE effect
 * right after the pane commits, so any observer-based arming is a race — the
 * arming has to happen strictly earlier, and the pointerdown that will become
 * the drill tap is the last deterministic moment before it. A tap ON the field
 * still focuses it natively (only the JS method is replaced), so searching
 * stays one deliberate tap away.
 *
 * Cost (deliberately minimal): one capture listener, one `querySelector` per
 * tap plus one idle check ~2s after the last tap. NO MutationObserver — the
 * session streams text, so a subtree observer would run every frame (the same
 * reasoning as `model-menu-anchor.ts`, which documents the measured cost).
 *
 * DOM contract (verified against 0.2.0-rc.2):
 * - the portaled menu surface carries the primitives' `data-menu-material`
 *   marker (`dsh-client-ui-primitives` MenuSurface) and, for this menu, the
 *   hashed `_7KE1Ra_menu` class;
 * - the pane's search field is an `input[role="searchbox"]` with
 *   `aria-controls="<menuId>-models"`.
 * Re-audit both when the host or dsh-client-ui-model-selection upgrades.
 */
/** A host menu surface (primitive marker first, hashed class as the fallback). */
const HOST_MENU = '[data-menu-material], [class*="_7KE1Ra_menu"]';
/**
 * The field the drill-in focus must not reach on phones: the model pane's
 * search box. Scoped two ways — inside a host menu surface, or carrying the
 * model list's own `-models` control — so neither the shortcut modal's search
 * field nor a settings-page input ever matches.
 */
const MODEL_SEARCHBOX = '[data-menu-material] input[role="searchbox"], input[role="searchbox"][aria-controls$="-models"]';
/** How long the shadow stays armed after the last tap while no menu is up. */
const IDLE_DISARM_MS = 2_000;
/**
 * Keep the model menu's search field from grabbing focus (and the soft
 * keyboard) by itself, on the mobile breakpoint only.
 * @param ctx - client root context.
 */
function installModelMenuKeyboardGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: model menu keyboard guard', () => {
        // The shared manager owns the single prototype patch: with the shortcut
        // modal's guard (or any future guard) loaded at the same time, neither can
        // wipe the other's rule or leave a stale wrapper behind (see
        // core/prototype-focus-shadow.ts).
        let release = null;
        let idleTimer = 0;
        const arm = () => {
            if (release !== null)
                return;
            release = (0, prototype_focus_shadow_ts_1.shadowFocus)((element) => element.matches(MODEL_SEARCHBOX));
        };
        const disarm = () => {
            release?.();
            release = null;
        };
        const onPointerDown = () => {
            // A tap is the moment before React can open the menu or drill into a
            // pane, so arm now; the check below only decides when to give the
            // prototype back, and a menu opened by this same gesture keeps it armed.
            arm();
            window.clearTimeout(idleTimer);
            idleTimer = window.setTimeout(() => {
                if (document.querySelector(HOST_MENU) === null)
                    disarm();
            }, IDLE_DISARM_MS);
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            window.clearTimeout(idleTimer);
            disarm();
        };
    });
}
};
__modules["core/plugin-card-tap-core.js"] = function (require, module, exports) {
"use strict";
// plugin-card-tap-core.ts — 插件管理页「整卡可点」的判定纯核（零 import，node --test 直跑）。
//
// 背景（2026-10-07 店主报障：「打开如图里面的功能，需要点击那些加黑字体才行」）：
// 宿主 dsh-client-ui-plugin-manager 的卡片只把**标题**渲染成 <button>
// （class 含 cardOpen；行式条目是 rowOpen 那颗），描述、图标、徽标、留白都不响应点击。
// 宿主自己给标题按钮带了一层 stretched-link（.cardOpen:after{position:absolute;inset:0}），
// 但卡片 DOM 顺序是 icon → titleRow(button) → **cardDesc**，描述那个 -webkit-box 盒子
// 绘制在覆盖层之后 ⇒ 把点击整块吃掉。真机实测：补 z-index 抬升仍不生效，
// 所以改成**捕获期转发点击**（行为级、不依赖绘制顺序）：点在卡片内、又不在任何交互
// 控件上 ⇒ 替用户点一次那颗打开按钮。
Object.defineProperty(exports, "__esModule", { value: true });
exports.decidePluginCardTap = decidePluginCardTap;
/**
 * 判定一次点击要不要转发给「打开详情」按钮。
 *
 * - 点标题本身 ⇒ 'ignore'（宿主自己会开，转发会双重触发）；
 * - 点开关/链接/输入框等交互件 ⇒ 'ignore'（绝不抢它们的点击）；
 * - 其余（描述、图标、徽标、留白）⇒ 'open'，由调用方转发。
 * @param target - 命中的元素信息。
 * @returns 'open' 需要转发；'ignore' 放行。
 */
function decidePluginCardTap(target) {
    if (target.insideOpen)
        return 'ignore';
    if (target.onInteractive)
        return 'ignore';
    return 'open';
}
};
__modules["effects/plugin-card-tap.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installPluginCardTap = installPluginCardTap;
const plugin_card_tap_core_ts_1 = require("./core/plugin-card-tap-core.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 插件管理页：整卡 / 整行可点（2026-10-07 店主报障）。
 *
 * 症状：点卡片只有那行**加黑标题**能打开详情，描述/图标/留白点了没反应。
 *
 * 根因（读宿主 `dsh-client-ui-plugin-manager` 源码 + 真机截图取证）：宿主只把标题
 * 渲染成 `<button aria-label=打开…详情>`，且自己带了一层 stretched-link
 * （`.cardOpen:after{position:absolute;inset:0}` 配 `.cardLink{position:relative}`）；
 * 但卡片 DOM 顺序是 `icon → titleRow(button) → cardDesc`，描述是个 `-webkit-box`
 * 盒子，**绘制在覆盖层之后**，于是描述整块把点击吃掉。真机上给覆盖层补 `z-index`
 * 抬升仍不生效（本文件所在的同一批修复里试过），所以这里改走**行为级**：
 * 在捕获期把「卡片内、且不在任何交互控件上」的点击转发给那颗打开按钮。
 *
 * 与 CSS 的分工：layout.css.ts 里那组 `li[data-plugin-*] …::after` 规则保留
 * （负责悬停视觉与桌面浏览器上的 stretched-link 语义），点击的**确定性**由本效果保证。
 * 桌面档由 installMobileEffect 门控 ⇒ 鼠标环境零影响。
 *
 * 2026-10-08 补第三种根（店主截图：「官方」组里**从「终端」起**的四张点不动，之前四张正常）：
 * 宿主的「官方」组是两类卡拼起来的 —— `official.map(packageCard)` 出 `li[data-plugin-package]`，
 * `ledger.items.map(ItemCard)` 出 **`li[data-plugin-item]`**（`plugins.item` 槽的条目）。
 * 两类卡的标题按钮都由同一个 `CardHead` 渲染（class 同样含 `cardOpen`），只是 item 卡没有
 * 开关/徽标，所以症状看起来像「有开关的能点、没开关的不能点」。CARD 选择器当初漏了后者。
 */
/**
 * 插件管理页条目根节点（宿主三种稳定标记）：
 * `li[data-plugin-package]` 包卡片、`li[data-plugin-row]` 包详情里的行、
 * `li[data-plugin-item]` 官方 item 卡（`plugins.item` 槽）。
 */
const CARD = 'li[data-plugin-package], li[data-plugin-row], li[data-plugin-item]';
/** 条目里唯一能打开详情的按钮（卡片 `cardOpen` / 行式 `rowOpen`）。 */
const OPEN = 'button[class*="_cardOpen"], button[class*="_rowOpen"]';
/**
 * 条目内其它交互件：一律不抢。
 * `[role="switch"]` 是宿主 Switch 的既有半区；其余是防御（后代可能新增控件）。
 */
const INTERACTIVE = 'button, a, input, select, textarea, [role="switch"], [role="button"], [role="tab"], [contenteditable="true"]';
/**
 * Install the capture-phase click forwarder for plugin-manager entries.
 * @param ctx - client root context.
 */
function installPluginCardTap(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: plugin card tap', () => {
        const onClick = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const card = target.closest(CARD);
            if (card === null)
                return;
            const open = card.querySelector(OPEN);
            if (!(open instanceof HTMLButtonElement))
                return;
            const decision = (0, plugin_card_tap_core_ts_1.decidePluginCardTap)({
                insideOpen: open.contains(target),
                onInteractive: target.closest(INTERACTIVE) !== null,
            });
            if (decision === 'ignore')
                return;
            // 只有描述/图标/留白会走到这里：别让这次点击再做别的事（选中文本、聚焦），
            // 然后替用户点一次宿主那颗按钮。转发出来的 click 会再次经过本监听器，
            // 但那时 target 就在 open 内部 ⇒ insideOpen ⇒ 直接放过，不会递归。
            event.preventDefault();
            open.click();
        };
        // 捕获期：宿主的 outside-click 之类监听器都在冒泡，先一步拿到事件。
        document.addEventListener('click', onClick, true);
        return () => document.removeEventListener('click', onClick, true);
    });
}
};
__modules["core/raf-scheduler.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createRafScheduler = createRafScheduler;
function createRafScheduler(raf, caf) {
    let pending = 0;
    let queued = false;
    return {
        schedule(fn) {
            if (queued)
                return;
            queued = true;
            pending = raf(() => {
                queued = false;
                fn();
            });
        },
        cancel() {
            if (!queued)
                return;
            caf(pending);
            queued = false;
        },
    };
}
};
__modules["effects/installed-list.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installInstalledListRows = installInstalledListRows;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const raf_scheduler_ts_1 = require("./core/raf-scheduler.js");
// Market「installed」列表行的内联样式效果——2026-10-12 由 src/client/index.tsx
// 逐字搬运（P3：组装根只留组装 + 调用）。搬运不改语义：写前比较、按所有权释放的
// 账本、rAF 合帧与「flush 时复查断点」全部原样保留。
// Hard-fix the installed-plugins list text layout: the host market UI
// injects its own CSS after this plugin's stylesheet, so CSS overrides can
// be beaten. Inline !important styles win over every external rule. Keep
// the selector on outer rows only; irowActions/irowTrailing are nested
// flex containers and must retain the market's own action geometry.
//
// 2026-10-11：apply() 原每次 flush 无条件重写 !important（值没变也让样式失效），clear() 原裸
// removeProperty 一批属性、连宿主的内联样式一起删；现在写前比较、释放只回收本效果写过的值。
// 2026-10-12：行内目标（行 / 首个子节点 / spec / nm）原先在 apply 与 clear 里各写一遍遍历，
// 现将「写哪个元素、写哪些属性」收敛成下面那张 rowTargets 表——apply 按它写、clear 按同一张表
// 释放，将来加目标元素只改表里的一条。
function installInstalledListRows(ctx) {
    ctx.effect(() => {
        const mq = window.matchMedia(breakpoints_ts_1.MOBILE_QUERY);
        const rowSelector = '[class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"])';
        // 行内目标的**单一描述**：pick 取目标元素（取不到就跳过这一条），props 是 apply 要写的
        // 属性。数组顺序 = 写入顺序 = 释放顺序，别重排；三个选择器字符串在本 effect 里各自只出现
        // 这一次，改结构只改这张表。
        // 解析与写入交替进行（旧形态是「先把 spec/nm 都查出来再一起写」）；写入动的是内联 style，
        // class 子串查询不受其影响，所以这点顺序差别不改变结果。
        const TEXT = { 'white-space': 'nowrap', 'overflow': 'hidden', 'text-overflow': 'ellipsis', 'max-width': '100%' };
        const rowTargets = [
            { pick: (row) => row, props: { 'flex-wrap': 'wrap', 'align-items': 'center', 'gap': '4px 10px' } },
            { pick: (row) => row.children[0] ?? null, props: { 'flex': '1 1 100%', 'max-width': '100%', 'min-width': '0' } },
            { pick: (row) => row.querySelector('[class*="spec"]'), props: TEXT },
            { pick: (row) => row.querySelector('[class*="nm"]'), props: TEXT },
        ];
        const owned = new WeakMap();
        const write = (el, props) => {
            let account = owned.get(el);
            if (account === undefined)
                owned.set(el, account = new Map());
            for (const [key, value] of Object.entries(props)) {
                const priority = 'important';
                const current = el.style.getPropertyValue(key);
                const currentPriority = el.style.getPropertyPriority(key);
                // 写前比较：值 + 优先级已经一致就跳过——重设同一个值同样会让样式失效并同步
                // 重算这一行，而这是 apply 的热路径（每帧可能重扫整份已安装列表）。
                if (current === value && currentPriority === priority)
                    continue;
                const held = account.get(key);
                // 只在首次写、或「我们上次写的值已被改掉」时更新 before：后一种情况当前值
                // 才是释放时该恢复的前置状态（旧 before 已不是这一格的原始值）。
                const stillOurs = held !== undefined && held.value === current && held.priority === currentPriority;
                account.set(key, stillOurs ? { ...held, value, priority } : { value, priority, before: current, beforePriority: currentPriority });
                el.style.setProperty(key, value, priority);
            }
        };
        const release = (el) => {
            const account = owned.get(el);
            if (account === undefined)
                return;
            for (const [key, held] of account) {
                // 先摘账：无论回不回收，这一格都不再归本效果（值被人改过就永久弃权）。
                account.delete(key);
                if (el.style.getPropertyValue(key) !== held.value || el.style.getPropertyPriority(key) !== held.priority)
                    continue;
                if (held.before === '')
                    el.style.removeProperty(key);
                else
                    el.style.setProperty(key, held.before, held.beforePriority);
            }
            if (account.size === 0)
                owned.delete(el);
        };
        const clear = () => {
            document.querySelectorAll(rowSelector).forEach((row) => {
                for (const target of rowTargets) {
                    const el = target.pick(row);
                    if (el)
                        release(el);
                }
            });
        };
        // 市场容器的缓存（2026-10-13）。apply() 原来每趟都做两件**全文档**的事：① 用
        // `[data-dsh-market-root], [role="dialog"]` 判存在性；② 全文档扫 irow 行。市场根一挂上
        // 就不会自己搬家，两件事都没必要每帧重来 —— 尤其②：任意 dialog 在场（会话删除确认、
        // 快捷键弹层、设置面板）都会触发一次 18k 节点的 class 子串全扫。现在缓存容器：根还挂在
        // 文档上就零查询，行扫描收窄到容器内。
        // clear() 刻意**不**收窄：它要负责把本效果写过的样式全部还回去，容器可能是旧的/已断开的，
        // 收窄会漏掉写入点、把 !important 内联样式永久留在宿主行上。
        let marketRoot = null;
        /** 存在性判定用的选择器：与旧版逐字相同，语义不变。 */
        const MARKET_GATE = '[data-dsh-market-root], [role="dialog"]';
        /** 市场根自己的标记（dshmarket 1.20.x）。 */
        const MARKET_ROOT = '[data-dsh-market-root]';
        /**
         * 拿到该扫的容器。缓存命中（仍挂在文档上）⇒ 零查询。
         *
         * 缓存失效时先跑那条与旧版逐字相同的存在性查询：两个标记都不在 ⇒ null ⇒ 早退
         * ——「不在场即早退」的语义、查询条数都与旧版一致。
         *
         * 命中的不是市场根时**再补一条查询**把根找出来。收窄之后「命中谁」变成语义了：旧版
         * 反正随后全文档扫，命中哪个都无所谓；现在若按文档序拿到别的弹层（会话删除确认、快捷键），
         * 市场行会被漏掉。所以优先市场根、退而用弹层兜底。
         *
         * 兜底本身不足以兑现「降级为成本，不是静默死掉」（2026-10-13 独立验证 V5）：容器不是
         * 市场根时，市场行可能长在它**外面**（标记换代、只命中设置弹层）——收窄扫一条都拿不到，
         * 却也不报错。这一档由 apply 的 fail-open 补上（见下）。
         */
        const resolveRoot = () => {
            const cached = marketRoot;
            if (cached !== null && cached.isConnected)
                return cached;
            const gate = document.querySelector(MARKET_GATE);
            if (gate === null) {
                marketRoot = null;
                return null;
            }
            marketRoot = gate.matches(MARKET_ROOT) ? gate : document.querySelector(MARKET_ROOT) ?? gate;
            return marketRoot;
        };
        const apply = () => {
            // The market rows only exist while the market UI is mounted (inside a
            // settings dialog). Skip the scan on every streamed mutation frame with
            // no market open; dshmarket keeps the data-dsh-market-root marker
            // (1.20.x), [role="dialog"] covers the settings dialog generically so a
            // marker change degrades to cost, not to a silently dead effect.
            const root = resolveRoot();
            if (root === null)
                return;
            let rows = root.querySelectorAll(rowSelector);
            // fail-open（2026-10-13 独立验证 V5）：容器**不是市场根本身**时，它不代表市场 ——
            // 收窄扫一条行都没拿到就说明「市场行长在这个容器外面」（市场根标记换代、或只命中了
            // 设置/会话删除确认弹层），旧行为是静默漏写。同一趟（同一个 apply 调用里）退回全文档
            // 扫，把这一档的成本补回来。
            // 两个前提都不能少：① 有行存在的信号 —— 收窄扫非空就说明容器是对的，不必也不能兜底；
            // ② 容器不是市场根本身 —— 根上的空结果语义是「市场当前没有行」，不是「找错容器」。
            // 正常一代（容器就是市场根）因此永远走不到这里：零查询缓存与收窄扫描都不变。
            if (rows.length === 0 && !root.matches(MARKET_ROOT))
                rows = document.querySelectorAll(rowSelector);
            rows.forEach((row) => {
                for (const target of rowTargets) {
                    const el = target.pick(row);
                    if (el)
                        write(el, target.props);
                }
            });
        };
        const arm = () => {
            clear();
            if (mq.matches)
                apply();
        };
        arm();
        // Streaming floods this observer with document-wide childList batches;
        // coalesce to one apply per frame and re-check the breakpoint at flush
        // time so a queued callback never writes mobile styles on desktop.
        const scheduler = (0, raf_scheduler_ts_1.createRafScheduler)((cb) => window.requestAnimationFrame(cb), (id) => window.cancelAnimationFrame(id));
        // 2026-10-13 受控实测（流式帧成本 A/B）：正文内流式时共享协调器**明确丢弃**正文 mutation
        // （phone-chrome 的 `closest('[data-chat-flow]')` 过滤），但本观察器当时没有这层过滤 ——
        // 于是它成了流式期间**唯一**的每帧查询（实测 +8.09 次 document.querySelector/秒，
        // 全部是同一条 `[data-dsh-market-root], [role="dialog"]`）。
        // 这里补上同一套过滤：**整批记录都在正文内**时直接不排程（市场根不可能长在会话正文里，
        // 真正的开合 mutation 落在正文之外，仍会照常排程）。一行过滤换掉一次每帧全文档查询。
        /* @patch
               id: installed-list-chatflow-filter
               for: 共享协调器的「正文整批丢弃」语义（phone-chrome 侧既有过滤）
               why: 本观察器与协调器各自观察 DOM；正文内 mutation 不可能含市场根，不补这层过滤就会在流式期间每帧做一次全文档查询
               until: 宿主把市场根挂到稳定容器、且协调器把 childList 一并按容器路由（届时可改为按容器早期退出）
               test: tests/installed-list.test.ts
            */
        const mo = new MutationObserver((records = []) => {
            // 空批次（或测试夹具不传参数）一律按「需要跑」处理 —— 只有**明确整批都在正文内**
            // 时才跳过排程。生产环境里 MutationObserver 必然带 records，这里只为稳妥。
            let relevant = records.length === 0;
            for (const record of records) {
                const target = record.target instanceof Element ? record.target : record.target.parentElement;
                if (target?.closest('[data-chat-flow]') == null) {
                    relevant = true;
                    break;
                }
            }
            if (relevant && mq.matches)
                scheduler.schedule(() => { if (mq.matches)
                    apply(); });
        });
        mo.observe(document.documentElement, { childList: true, subtree: true });
        mq.addEventListener('change', arm);
        return () => {
            scheduler.cancel();
            mo.disconnect();
            mq.removeEventListener('change', arm);
            clear();
            marketRoot = null;
        };
    }, 'dsh-web-mobile: installed-list-inline-styles');
}
};
__modules["effects/tablet-quick-settings.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installTabletQuickSettings = installTabletQuickSettings;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const drawer_quick_settings_ts_1 = require("./effects/drawer-quick-settings.js");
/** 宽屏触屏上补一颗和手机档一致的一键设置齿轮。 */
function installTabletQuickSettings(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'tablet-quick-settings', () => {
        const task = (0, drawer_quick_settings_ts_1.createDrawerQuickSettingsTask)(ctx);
        task.ensure();
        let queued = false;
        const run = () => {
            queued = false;
            try {
                task.ensure();
            }
            catch (error) {
                // 与协调器同口径：任务失败不拖垮其它效果，错误交给 debug 徽章可见的 console。
                console.error('[dsh-web-mobile] tablet-quick-settings', error);
            }
        };
        const observer = new MutationObserver(() => {
            if (queued)
                return;
            queued = true;
            requestAnimationFrame(run);
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
        return () => {
            observer.disconnect();
            task.dispose();
        };
    }, breakpoints_ts_1.TABLET_TOUCH_QUERY);
}
};
__modules["effects/composer-paste-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.shouldRerouteInsertText = shouldRerouteInsertText;
exports.installComposerPasteGuard = installComposerPasteGuard;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 手机档：多行粘贴只剩首行（输入法上屏之后再粘贴）。
 *
 * 机制（宿主 dsh-client-ui-conversation 内置 Lexical，0.2.0-rc.2 / 0.2.1-alpha.1 同形）：
 * Android WebView 的长按「粘贴」菜单与输入法 commitText（含剪贴板候选）派发的是
 * `beforeinput` + `inputType: 'insertText'`，而不是 `paste` 事件。Lexical 的 beforeinput
 * 分发里，`data` 恰为 '\n' 才走换行命令；含换行的整段文本落进
 * CONTROLLED_TEXT_INSERTION_COMMAND。输入法上屏后节点处于 dirty 态，这条命令只写入
 * 第一段，其余段落被吞（同一会话内不会自愈）。真正的 `paste` 事件走 PASTE_COMMAND，
 * 不吞段 —— 这就是「直接粘贴正常、先打字再粘贴丢内容」的分叉点。
 * 上游报告：https://github.com/deepseek-ai/deepseek-harness/discussions/8823
 *
 * 修法（不改宿主）：document 捕获期拦下「可信的 insertText + 多行文本 + 落在 composer
 * 编辑面内」的 beforeinput，改派一个携带同一文本的合成 `paste` 事件到编辑面，让宿主走
 * 自己的 PASTE_COMMAND。先构造 DataTransfer/ClipboardEvent，构造失败就不拦，原路放行。
 *
 * DOM 契约（0.2.0-rc.2 核对）：`[data-composer-input]` 是 Lexical 的 contenteditable 根
 * （同元素带 `data-lexical-editor`）。宿主修复此 bug 后可整体删除本效果。
 */
/** composer 的 Lexical 编辑面。 */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
/**
 * 这一次 beforeinput 是否需要改投 paste 通道。
 * 单个 '\n' 是宿主自己的换行命令（Shift+Enter / IME 换行），绝不拦。
 * @param event - beforeinput 的相关字段。
 * @returns 需要改投时为 true。
 */
function shouldRerouteInsertText(event) {
    if (!event.isTrusted || event.defaultPrevented)
        return false;
    if (event.inputType !== 'insertText')
        return false;
    const text = event.data;
    if (typeof text !== 'string')
        return false;
    // 单个换行符交回宿主：'\n'（宿主换行命令）与孤立 '\r'（老式 Mac 换行）同等对待。
    // 只认 '\n' 会把单个 '\r' 当成"多行"改投合成 paste，反而给草稿插入一个字面的 CR。
    // '\r\n' 是两个字符，不走这条；宿主那条命令只匹配 data === '\n'，所以它仍按既有
    // 意图改投 paste，断行不会丢。
    if (text === '\n' || text === '\r')
        return false;
    return text.includes('\n') || text.includes('\r');
}
/**
 * 构造携带纯文本的合成 paste 事件；环境不支持时返回 null（调用方原路放行）。
 * @param text - 要粘贴的文本。
 * @returns 合成事件或 null。
 */
function createPasteEvent(text) {
    if (typeof DataTransfer !== 'function' || typeof ClipboardEvent !== 'function')
        return null;
    try {
        const clipboardData = new DataTransfer();
        clipboardData.setData('text/plain', text);
        const event = new ClipboardEvent('paste', { clipboardData, bubbles: true, cancelable: true });
        // 少数引擎会忽略构造参数里的 clipboardData，此时合成事件对宿主是空粘贴，不能用。
        if (event.clipboardData?.getData('text/plain') !== text)
            return null;
        return event;
    }
    catch {
        return null;
    }
}
function installComposerPasteGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer paste guard', () => {
        const onBeforeInput = (event) => {
            if (!(event instanceof InputEvent))
                return;
            if (!shouldRerouteInsertText(event))
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const editor = target.closest(COMPOSER_INPUT_SELECTOR);
            if (editor === null)
                return;
            const paste = createPasteEvent(event.data ?? '');
            if (paste === null)
                return;
            event.preventDefault();
            event.stopPropagation();
            editor.dispatchEvent(paste);
        };
        document.addEventListener('beforeinput', onBeforeInput, true);
        return () => {
            document.removeEventListener('beforeinput', onBeforeInput, true);
        };
    });
}
};
__modules["effects/composer-enter-newline.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.shouldEnterInsertNewline = shouldEnterInsertNewline;
exports.findCommand = findCommand;
exports.installComposerEnterNewline = installComposerEnterNewline;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 触屏：软键盘的回车键是「换行」，不是「发送」（2026-11-05 owner 真机要求）。
 *
 * 宿主行为（dsh-client-ui-conversation 0.2.2-rc.1，内置 Lexical）：输入框在
 * KEY_ENTER_COMMAND 上挂了 CRITICAL 优先级（4）的处理器——无修饰键的 Enter 直接
 * `handlers.submit()`，只有 Shift+Enter 放行给纯文本插件插入换行。手机软键盘没有
 * Shift，长按回车也不出换行，于是回车只能发送。回车到达 KEY_ENTER_COMMAND 有两条路：
 *   ① 真实 keydown（Gboard 等直接派 Enter 键）；
 *   ② 输入法组字结束时文本以 '\n' 收尾，Lexical 以 `null` 事件派发同一命令——此时
 *      没有事件对象可改，只能在命令层拦。
 * 所以本效果在命令层接管：给当前编辑器注册一个排在宿主前面的 KEY_ENTER_COMMAND
 * 处理器（Lexical 的 registerCommand 在「优先级带高位标记」时 addFront，同桶内先于
 * 宿主执行），对「普通回车」改派 INSERT_LINE_BREAK_COMMAND 并吞掉命令，宿主的发送
 * 分支不会再跑。发送改由发送按钮完成（宿主按钮不受影响）。
 *
 * 放行给宿主的情形（行为与桌面一致）：
 *   - Ctrl/Cmd/Alt + Enter：外接键盘的发送 / 加速发送快捷键；
 *   - Shift + Enter：宿主本来就放行给换行，不重复处理；
 *   - 输入法组字中的回车（`isComposing`）：确认候选词，宿主自己吞掉；
 *   - 斜杠 / @ 候选菜单打开时（`[data-trigger-menu]` 可见）：回车是「选中候选项」。
 *
 * 命令对象不由宿主导出：Lexical 的命令是 `{ type: 'KEY_ENTER_COMMAND' }` 形状的单例，
 * 编辑器把注册过的命令存在 `editor._commands`（Map，键即命令对象）。按 type 找回同一个
 * 对象即可注册；找不到（宿主换编辑器 / 未来改名）就什么都不做，回车保持宿主原样。
 * 编辑器实例取自 contenteditable 根上的 `__lexicalEditor`（Lexical 自己写的反向引用）。
 *
 * 作用域：MOBILE_QUERY（<1024 且粗指针）。平板 768–1023 同样生效（软键盘一样没有 Shift）。
 * 每次编辑器根获得焦点时幂等挂钩（会话切换 / 子代理视图会换新编辑器）。
 */
/** composer 的 Lexical contenteditable 根。 */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
/** 斜杠 / @ 候选菜单根（宿主 MenuView 自带标记）。 */
const TRIGGER_MENU_SELECTOR = '[data-trigger-menu]';
/** Lexical COMMAND_PRIORITY_CRITICAL；带 8 位标记让 registerCommand 走 addFront。 */
const FRONT_OF_CRITICAL = 4 | 8;
/**
 * 这一次 KEY_ENTER_COMMAND 是否应改成换行。
 * @param event - 键盘事件；输入法组字结束派发的命令没有事件对象（null），按普通回车处理。
 * @param menuOpen - 候选菜单是否可见。
 * @returns 应插入换行时为 true；false 表示交回宿主（发送 / 选中候选 / 宿主自己的换行）。
 */
function shouldEnterInsertNewline(event, menuOpen) {
    if (menuOpen)
        return false;
    if (event === null)
        return true;
    if (event.isComposing)
        return false;
    if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey)
        return false;
    return true;
}
/**
 * 按 type 找回编辑器已注册的命令单例。
 * @param editor - Lexical 编辑器。
 * @param type - 命令 type 字符串。
 * @returns 命令对象；未注册时为 null。
 */
function findCommand(editor, type) {
    const commands = editor._commands;
    if (!(commands instanceof Map))
        return null;
    for (const command of commands.keys()) {
        if (command !== null && typeof command === 'object' && command.type === type)
            return command;
    }
    return null;
}
function editorOf(root) {
    const editor = root.__lexicalEditor;
    if (editor === null || typeof editor !== 'object')
        return null;
    const face = editor;
    return typeof face.registerCommand === 'function' && typeof face.dispatchCommand === 'function'
        ? editor
        : null;
}
/** 候选菜单是否真的看得见（收起后 React 可能还没摘节点）。 */
function triggerMenuOpen() {
    for (const menu of document.querySelectorAll(TRIGGER_MENU_SELECTOR)) {
        const box = menu.getBoundingClientRect();
        if (box.width > 0 && box.height > 0)
            return true;
    }
    return false;
}
function installComposerEnterNewline(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer enter inserts newline', () => {
        /** 已挂钩的编辑器 → 撤销函数。WeakMap：编辑器被宿主丢弃后自然回收。 */
        const hooked = new WeakMap();
        /** 撤销用的强引用表（WeakMap 不可遍历）。 */
        const disposers = new Set();
        const hook = (root) => {
            const editor = editorOf(root);
            if (editor === null || hooked.has(editor))
                return;
            const enter = findCommand(editor, 'KEY_ENTER_COMMAND');
            const lineBreak = findCommand(editor, 'INSERT_LINE_BREAK_COMMAND');
            if (enter === null || lineBreak === null)
                return;
            const unregister = editor.registerCommand(enter, (payload) => {
                const event = payload instanceof KeyboardEvent ? payload : null;
                if (payload !== null && event === null)
                    return false;
                if (!shouldEnterInsertNewline(event, triggerMenuOpen()))
                    return false;
                event?.preventDefault();
                // 宿主纯文本插件的同一路径：false = 光标停在换行之后。
                editor.dispatchCommand(lineBreak, false);
                return true;
            }, FRONT_OF_CRITICAL);
            const dispose = () => {
                unregister();
                disposers.delete(dispose);
                hooked.delete(editor);
            };
            hooked.set(editor, dispose);
            disposers.add(dispose);
        };
        /**
         * 按事件目标补挂（幂等）。只靠 focusin 不够：宿主在 hero → active 相位切换、
         * 会话切换、子代理视图这些时机换掉编辑器实例，换掉之后若不再触发 focusin
         * （键盘一直弹着、焦点没变），新编辑器上就没有我们的处理器，回车又变回发送
         * ——真机 2026-11-05「有概率发送」正是这个窗口。所有能引出 KEY_ENTER_COMMAND
         * 的事件都在捕获期先补挂：keydown 与 beforeinput 都早于 Lexical 自己的根监听，
         * 所以补挂能赶在同一拍生效。
         */
        const ensure = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const root = target.closest(COMPOSER_INPUT_SELECTOR);
            if (root !== null)
                hook(root);
        };
        document.addEventListener('focusin', ensure, true);
        document.addEventListener('keydown', ensure, true);
        document.addEventListener('beforeinput', ensure, true);
        // 安装时键盘可能已经弹着：当前聚焦的编辑器也挂上。
        const active = document.activeElement?.closest(COMPOSER_INPUT_SELECTOR);
        if (active instanceof Element)
            hook(active);
        return () => {
            document.removeEventListener('focusin', ensure, true);
            document.removeEventListener('keydown', ensure, true);
            document.removeEventListener('beforeinput', ensure, true);
            for (const dispose of [...disposers])
                dispose();
        };
    });
}
};
__modules["effects/focus-gesture-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isTextEntry = isTextEntry;
exports.shouldBlockProgrammaticFocus = shouldBlockProgrammaticFocus;
exports.installFocusGestureGuard = installFocusGestureGuard;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 触屏：只有「手指点在输入框上」才允许唤起软键盘（owner 2026-11-05）。
 *
 * 报障：「随便一个返回就会唤起输入法，真的好烦」——宿主大量流程会在挂载、
 * 返回、切换页签时代码里 `el.focus()`（或给输入框挂 `autoFocus`），桌面上这是
 * 体贴，手机上每一次都等于把键盘顶到屏幕下半张。
 *
 * 做法：在移动档内包一层 `focus`，对**文本输入面**（input 的文本类 type、
 * textarea、contenteditable）加一个手势门：
 *   - 最近 GESTURE_WINDOW_MS 内，用户真的在该元素（或它的祖先/后代）上按下过 →
 *     放行，走原生 focus（键盘正常弹出）；
 *   - 否则吞掉这次 focus（元素不获得焦点，键盘不弹）。
 * 非文本目标（按钮、菜单容器、对话框、`tabindex=-1` 的壳）一律不受影响 ——
 * 宿主靠它们做焦点管理，压掉会破坏键盘/读屏导航。
 *
 * 为什么改原型而不是逐个改调用方：宿主自己有几十处 `ref.current?.focus()` /
 * `autoFocus`（见 docs 里的清点），插件不可能逐一改写；原型层是唯一能把
 * 「有没有真实手势」统一判定的位置。
 *
 * 作用域：MOBILE_QUERY（<1024 且粗指针）。桌面保留宿主原有的自动聚焦。
 * 与 composer-keyboard-guard（iOS 的 keepFocus 影子）互不影响：两者都只是
 * 「让某次 focus 不生效」，叠加时先命中者返回即可。
 */
/** 输入卡根：卡内的按下不在这里收键盘（见 blurUnfocusedTap）。 */
const COMPOSER_CARD_SELECTOR = '[data-composer-card]';
/** 用户手指在输入面上的记录有效时长：轻点后 React 的回调链通常 <100ms。 */
const GESTURE_WINDOW_MS = 1200;
/** 会唤起软键盘的 input type（file/checkbox/button 等不算）。 */
const TEXT_INPUT_TYPES = new Set([
    '', 'text', 'search', 'email', 'url', 'tel', 'password', 'number',
    'date', 'time', 'datetime-local', 'month', 'week',
]);
/**
 * 这个元素是不是「文本输入面」——即聚焦它就会弹出软键盘。
 * @param element - 待判定元素。
 * @returns 是文本输入面时为 true。
 */
function isTextEntry(element) {
    if (element === null)
        return false;
    const tag = element.tagName;
    if (tag === 'TEXTAREA')
        return true;
    if (tag === 'INPUT') {
        const type = element.type ?? '';
        return TEXT_INPUT_TYPES.has(type.toLowerCase());
    }
    const editable = element.getAttribute('contenteditable');
    return editable !== null && editable !== 'false';
}
/**
 * 这次 focus 该不该被吞掉。
 * @param element - 收到 focus() 的元素。
 * @param gestureTarget - 最近一次真实手势落在的元素（无手势为 null）。
 * @param sinceGestureMs - 距那次手势的毫秒数。
 * @returns 需要拦截时为 true。
 */
function shouldBlockProgrammaticFocus(element, gestureTarget, sinceGestureMs) {
    if (!isTextEntry(element))
        return false;
    if (gestureTarget === null)
        return true;
    if (sinceGestureMs > GESTURE_WINDOW_MS)
        return true;
    // 手势落在输入面上、或落在这颗输入框包着的子节点里 → 放行。
    return !(element === gestureTarget || element.contains(gestureTarget) || gestureTarget.contains(element));
}
function installFocusGestureGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: focus requires a real tap', () => {
        let gestureTarget = null;
        let gestureAt = 0;
        /**
         * 点输入框以外的地方 = 收起键盘。
         *
         * 只拦「程序化 focus」还不够：真机 2026-11-05（返回会话时输入法自己弹起来）——
         * 输入框在打开面板前拿过焦点，面板撤掉时 WebView 会把软键盘还给那颗**已经聚焦**
         * 的可编辑面，这一步不经过任何 JS `focus()`，拦不到。所以按下的位置不在输入面上时
         * 主动 blur 掉当前文本焦点：面板再关掉时就没有「已聚焦的输入框」可还原，
         * 键盘也就不会自己冒出来。顺带得到常见行为：点空白处收键盘。
         */
        const blurUnfocusedTap = (target) => {
            const active = document.activeElement;
            if (!(active instanceof Element) || !isTextEntry(active))
                return;
            if (active === target || active.contains(target) || target.contains(active))
                return;
            // 输入卡内部的按钮（发送 / 停止 / 📎 / 模型）不在这里收键盘：真机 2026-11-05
            // 「点发送，键盘退下去了但消息没发出去，要再点一次」——手指按下时收掉焦点，
            // 这一次 tap 就被 Android 当成「关键盘」吃掉了，click 不再派发。卡内的收键盘
            // 归宿主自己的 keepFocus 管，这里放行。
            if (target.closest(COMPOSER_CARD_SELECTOR) !== null)
                return;
            if (typeof active.blur !== 'function')
                return;
            active.blur();
        };
        const record = (event) => {
            const target = event.target;
            if (target instanceof Element) {
                gestureTarget = target;
                gestureAt = performance.now();
                blurUnfocusedTap(target);
            }
        };
        const isAllowed = (element) => !shouldBlockProgrammaticFocus(element, gestureTarget, gestureTarget === null ? Number.POSITIVE_INFINITY : performance.now() - gestureAt);
        /** 包一层 focus：只拦文本输入面的「非手势」聚焦。 */
        const patch = (owner) => {
            if (owner === undefined)
                return () => { };
            const original = owner.focus;
            if (typeof original !== 'function')
                return () => { };
            const wrapper = function focus(options) {
                if (isAllowed(this))
                    original.call(this, options);
            };
            try {
                Object.defineProperty(owner, 'focus', {
                    configurable: true, writable: true,
                    enumerable: Object.getOwnPropertyDescriptor(owner, 'focus')?.enumerable ?? false,
                    value: wrapper,
                });
            }
            catch {
                return () => { };
            }
            return () => {
                if (Object.getOwnPropertyDescriptor(owner, 'focus')?.value !== wrapper)
                    return;
                try {
                    Object.defineProperty(owner, 'focus', { configurable: true, writable: true, value: original });
                }
                catch {
                    // 被外部改写：规则已移除，保持现状即可。
                }
            };
        };
        const disposers = [];
        disposers.push(patch(globalThis.HTMLElement?.prototype));
        if (typeof HTMLInputElement !== 'undefined')
            disposers.push(patch(HTMLInputElement.prototype));
        if (typeof HTMLTextAreaElement !== 'undefined')
            disposers.push(patch(HTMLTextAreaElement.prototype));
        /**
         * 兜底：**任何**来源的聚焦都在 focusin 复核一次。
         *
         * 原型上包 focus 只能拦住 JS 主动调用；真机 2026-11-05 第二次取证（返回会话时
         * 键盘仍自己弹起）说明还有别的入口：面板被移除时浏览器会把焦点**还原**给上一个
         * 聚焦过的可编辑面，这条路径根本不调 focus()，是引擎自己做的。focusin 是它们
         * 共同的出口——不管谁把焦点放上去，只要不是手指点在这颗输入面上，当场收掉。
         * 代价是这类「还原」可能闪一帧键盘，但不会停留。
         */
        const onFocusIn = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (isAllowed(target))
                return;
            if (typeof target.blur)
                target.blur();
        };
        document.addEventListener('pointerdown', record, true);
        document.addEventListener('touchstart', record, true);
        document.addEventListener('mousedown', record, true);
        document.addEventListener('focusin', onFocusIn, true);
        return () => {
            document.removeEventListener('focusin', onFocusIn, true);
            document.removeEventListener('pointerdown', record, true);
            document.removeEventListener('touchstart', record, true);
            document.removeEventListener('mousedown', record, true);
            for (const dispose of disposers)
                dispose();
        };
    });
}
};
__modules["effects/composer-file-picker.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.acceptForKind = acceptForKind;
exports.bridgePickFile = bridgePickFile;
exports.pickResultFiles = pickResultFiles;
exports.classifyBridgeResult = classifyBridgeResult;
exports.handFilesToHost = handFilesToHost;
exports.openAttachmentPicker = openAttachmentPicker;
exports.computePickerPlacement = computePickerPlacement;
exports.installComposerFilePicker = installComposerFilePicker;
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const locales_ts_1 = require("./i18n/locales.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * 手机档：输入框回形针 → 「上传图片 / 上传附件」两选弹层。
 *
 * 背景（2026-10-06 报障：「点击输入框的回形针，会弹出上传图片，上传附件两个选项」）：
 * `ComposerFileButton` 直接点宿主的隐藏 `input[type=file]`，Android WebView 于是弹系统
 * 文件选择器 —— 形态由系统决定，插件控制不了，也没有「只挑图片」这条路。
 *
 * 修法：插件自绘一个小浮层给两个选项，选完把点击交回宿主那个 hidden input（`input.click()`），
 * 只给图片那一路临时写 image 通配 accept（click 返回后立刻还原；附件那路不动属性）—— 选择器在
 * click 的同一次调用里读走 accept，所以还原不影响它；校验/上传/可用性/草稿全归宿主 intake。
 *
 * 交接的身份契约（2026-10-11 缺陷）：桥是异步的（原生选择器可以停几十秒），期间用户可能切会话、
 * composer 也可能被 React 重建。所以打开选择器时捕获身份，resolve 后校验「同一元素 + 仍
 * isConnected + 同一会话」，对不上就丢弃这次结果（不交给后来那个输入区、不重放选择器）。
 *
 * 形态归属（2026-10-08，已提 DSH-APP/DSHA#101）：弹的是文件管理还是系统「打开方式」只由 App 侧
 * `onShowFileChooser` 决定（`createIntent()` 直接 launch，少包一层 `Intent.createChooser`）——
 * 插件只能影响 accept ⇒ 换不出 chooser。
 *
 * 三路并存（按可用性排序，都是宿主 intake，不另起一套）：
 *   1. App 侧给了 `window.DSHA.pickFile` 桥 → 走桥（原生弹「打开方式」），文件以 `File[]` 回来写进宿主 hidden input 再派发 `change`；
 *   2. 没有桥但 App 侧合入了 #101 的补丁 → 仍走 hidden input，系统这次会弹 chooser；
 *   3. 以上都没有 → 现状（系统文件管理），行为与今天一致。
 * 图片那一路不上桥：宿主 input + `accept="image/*"` 已经是「只挑图片」，桥反而可能带回通用选择器。
 *
 * DOM 契约（0.2.0-rc.2 核对）：入口 `[data-mobile-nav="file-upload"]` 由
 * components/ComposerFileButton.tsx 渲染（官方 `conversation.input.left` 槽）；宿主 input 在
 * `[data-composer-card]` 内，选择器 `input[type=file]`（0.1.6 起宿主删掉了自己的回形针按钮，
 * 只留这个隐藏 input，其「文件」菜单项也是这么点的）。弹层挂 `document.body`（与 session-menu.ts
 * 同一取舍：frame 里会被第三方 dismiss shim 的捕获期点击链吞掉）。宿主恢复自带两选项入口后可整体删掉本效果。
 */
/** 回形针入口按钮。 */
const TRIGGER_SELECTOR = '[data-mobile-nav="file-upload"]';
/** composer 卡片：宿主 hidden input 在其中。 */
const CARD_SELECTOR = '[data-composer-card]';
/** 宿主真正接收文件的 input —— 插件不另起 intake。 */
const HOST_INPUT_SELECTOR = 'input[type=file]';
/**
 * 当前打开着的那张浮层的 close（一次只会有一张）。效果的 disposer 与「再点一次回形针」
 * 都必须走 close 才能把 keydown / resize / orientationchange / visualViewport(resize+scroll)
 * 这几条持久监听与 rAF 链一起摘掉；只 remove() 节点会把它们留在 document/window 上，
 * 每次档位切换或热重载叠一套，回调还在找已经不在的浮层。
 */
let activeClose = null;
/**
 * 选项对应的 accept（空串 = 不改宿主 input 的属性）。
 *
 * 2026-10-07 试过给附件那路显式写全通配 accept（想换出系统「打开方式」应用列表）：真机形态没变，
 * 已回退 —— 根因在 App 侧少包一层 `Intent.createChooser`（见文件头）。所以这里仍是「不改属性」。
 * @param kind - 用户选择的入口。
 * @returns 要临时写到宿主 input 上的 accept；空串表示不动该属性。
 */
function acceptForKind(kind) {
    return kind === 'image' ? 'image/*' : '';
}
/**
 * 取出可用的 `pickFile`（绑定好 this）；没有桥或方法不是函数时返回 undefined。
 * @param bridge - `window.DSHA`。
 * @returns 可直接调用的桥方法。
 */
function bridgePickFile(bridge) {
    return typeof bridge?.pickFile === 'function' ? bridge.pickFile.bind(bridge) : undefined;
}
/**
 * 桥的返回值 → 可信的 `File` 列表（脏数据一律丢弃，不把非 File 塞进宿主 input）。
 * @param value - 桥的 resolve 值。
 */
function pickResultFiles(value) {
    return Array.isArray(value)
        ? value.filter((item) => typeof File !== 'undefined' && item instanceof File)
        : [];
}
/**
 * 桥的结果该走哪条路：用户取消（`null` / `undefined` / 空数组）**不回落** —— 否则一次取消
 * 会紧接着弹出第二个选择器；桥抛错或返回脏数据才回落，宁可回落到现状也不能点了没反应。
 * @param value - 桥的 resolve 值。
 * @param failed - 桥是否抛错（Promise reject / 同步抛出）。
 * @returns 分类结果。
 */
function classifyBridgeResult(value, failed) {
    if (failed)
        return 'fallback';
    if (value === null || value === undefined)
        return 'cancelled';
    if (!Array.isArray(value))
        return 'fallback';
    // 空数组 = 用户没选（原生侧通常这么回）；元素全不是 File = 脏数据，回落到现状。
    if (value.length === 0)
        return 'cancelled';
    return pickResultFiles(value).length > 0 ? 'files' : 'fallback';
}
/** 宿主 hidden input（图片/附件两路唯一的 intake）。 */
function hostInput() {
    const card = document.querySelector(CARD_SELECTOR);
    return card === null ? null : card.querySelector(HOST_INPUT_SELECTOR);
}
/**
 * 把桥挑好的文件交给宿主 intake：写进 hidden input 再派发 `change`。与 `input.click()` 那条路
 * 是同一个入口 —— 校验、大小/数量策略、上传、草稿全部仍归宿主。目标元素**由调用方传入**
 * （发起时捕获的那一个），这里不再全局查询：桥是异步的，resolve 时命中的 input 可能已经
 * 属于另一个会话。
 * @param files - 桥返回的文件。
 * @param input - 发起时捕获的宿主 input；null（当时就没有）时不交接。
 * @returns 是否成功交给宿主（false 时调用方回落 `openHostPicker`）。
 */
function handFilesToHost(files, input) {
    if (input === null)
        return false;
    try {
        const transfer = new DataTransfer();
        for (const file of files)
            transfer.items.add(file);
        input.files = transfer.files;
    }
    catch {
        return false;
    }
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
}
/** 两个选项的行图标（内联静态 SVG，无用户数据）。 */
const IMAGE_ICON = '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true" width="20" height="20">' +
    '<rect x="2.5" y="3.5" width="15" height="13" rx="2.5" stroke="currentColor" stroke-width="1.5"/>' +
    '<circle cx="7.2" cy="8" r="1.4" fill="currentColor"/>' +
    '<path d="M4.2 14.6l3.6-3.4 2.6 2.4 2.3-2.3 3.1 3.3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>';
const FILE_ICON = '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true" width="20" height="20">' +
    '<path d="M12.6 3.2H6.4A1.9 1.9 0 0 0 4.5 5.1v9.8a1.9 1.9 0 0 0 1.9 1.9h7.2a1.9 1.9 0 0 0 1.9-1.9V6.6l-2.9-3.4z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>' +
    '<path d="M12.4 3.4v3.3h3.1" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>' +
    '</svg>';
/**
 * 把点击交回宿主的 hidden input，按选项临时改 accept（两路都会写，click 返回后还原）。
 * @param kind - 用户选择的入口。
 * @param input - 要点的 input；缺省取「当前那一个」（图片路与无桥回落都是同步的，没有身份风险）。
 */
function openHostPicker(kind, input = hostInput()) {
    if (input === null)
        return;
    const accept = acceptForKind(kind);
    const hadAccept = input.hasAttribute('accept');
    const priorAccept = input.getAttribute('accept');
    if (accept !== '')
        input.setAttribute('accept', accept);
    try {
        input.click();
    }
    finally {
        if (accept !== '') {
            if (hadAccept)
                input.setAttribute('accept', priorAccept ?? '');
            else
                input.removeAttribute('accept');
        }
    }
}
/**
 * 附件那一路：App 侧有 `pickFile` 桥就先走桥（原生自己弹「打开方式」），没有就回落宿主 input。
 *
 * 回落链：桥不存在 → 直接 `openHostPicker('file')`；桥抛错 / 脏数据 / 注入失败 → 也回落（那时
 * 若 App 侧已合入 #101，宿主 input 自己就会弹 chooser）。
 * @param t - 插件 `NS` 命名空间文案。
 * @param readSessionId - 取当前会话 id；宿主缺 sessions 服务时传 undefined（只校验元素身份）。
 */
async function openAttachmentPicker(t, readSessionId) {
    const bridge = window.DSHA;
    const pick = bridgePickFile(bridge);
    if (pick === undefined) {
        openHostPicker('file');
        return;
    }
    // 打开选择器时捕获身份：桥是异步的，这期间用户可能切会话，composer 也可能被 React 重建。
    const target = hostInput();
    const sessionId = readSessionId?.() ?? null;
    let value = null;
    let failed = false;
    try {
        value = await pick({ accept: [], multiple: true, title: t('fileUploadAttachment') });
    }
    catch {
        failed = true;
    }
    // 交接前校验：同一个元素、仍在文档里、仍是同一个会话。`hostInput()` 只可能命中已挂载的节点，
    // 「重查仍是同一个元素」即同时证明同一性与 `isConnected`；会话那一路防宿主复用同一棵 composer
    // DOM 换会话。对不上就**丢弃这次结果**：不二手交接、不重放选择器、也不回落去点新 input。
    if (target === null || hostInput() !== target)
        return;
    if (sessionId !== null && (readSessionId?.() ?? null) !== sessionId)
        return;
    const outcome = classifyBridgeResult(value, failed);
    if (outcome === 'cancelled')
        return;
    if (outcome === 'files' && handFilesToHost(pickResultFiles(value), target))
        return;
    openHostPicker('file', target);
}
/** 浮层与锚点的间距。 */
const GAP = 8;
/** 夹进视口时留的边距。 */
const EDGE = 8;
/**
 * 浮层落位纯核（无 DOM，2026-10-13 抽出并导出，好让数值被测试钉住）：
 * 锚点上方优先 8px，放不下就翻到下方，最后夹进可见带、四舍五入到整像素。
 *
 * 为什么是「带」而不是「视口高」：`getBoundingClientRect()` 与 `position: fixed` 的
 * top/left 都是 layout 视口坐标，与视觉视口可能只差一个平移（iOS 键盘滚动模型，Safari
 * 不认 `interactive-widget`（本仓 2026-10-30 起也不再声明它）；键盘模型统一按 visualViewport 处理，两条通道重合）
 * —— 锚点盒与返回值都**不需要**换算，只有「夹进哪一段」得用视觉视口那一段（`visibleBand()`）。
 * @param anchor - 锚点（回形针）盒，layout 视口坐标。
 * @param own - 浮层自身尺寸。
 * @param band - 可见带，layout 视口坐标。
 * @returns 写进 `style.top` / `style.left` 的整像素值。
 */
function computePickerPlacement(anchor, own, band) {
    let top = anchor.top - GAP - own.height;
    if (top < band.top + EDGE)
        top = Math.min(anchor.bottom + GAP, band.bottom - own.height - EDGE);
    const left = Math.max(band.left + EDGE, Math.min(anchor.left, band.right - own.width - EDGE));
    return { top: Math.round(Math.max(band.top + EDGE, top)), left: Math.round(left) };
}
/**
 * 可见几何带，layout 视口坐标。优先 `visualViewport`：键盘弹起时它只缩视觉视口，
 * `window.innerHeight` 仍是整块布局视口高，按它夹会把浮层夹到键盘底下（那正是
 * 2026-10-07 真机报障「浮层停在会话中部」的同一族问题）。拿不到 visualViewport
 * （旧宿主 / 测试桩）才回退 `innerWidth` / `innerHeight` —— 回退档与旧版逐字等价。
 *
 * `offsetTop` / `offsetLeft` 是视觉视口相对 layout 视口的位移：捏合缩放或键盘引起
 * 页面滚动时非 0，所以带的两端都要带上它，否则锚点在带内的判定会整体偏掉。
 * @returns 可见带（layout 视口坐标）。
 */
function visibleBand() {
    const vv = window.visualViewport;
    if (vv !== undefined && vv !== null) {
        return { top: vv.offsetTop, bottom: vv.offsetTop + vv.height, left: vv.offsetLeft, right: vv.offsetLeft + vv.width };
    }
    return { top: 0, bottom: window.innerHeight, left: 0, right: window.innerWidth };
}
/**
 * 弹出两选小浮层；已打开时先关掉旧的（幂等）。
 *
 * 形态（2026-10-06 店主反馈：「这个 UI 太丑了，缩小点，不要从底部弹出」）：不是整宽底部弹层，
 * 而是贴着回形针的小浮层 —— 宽度贴内容（`max-content`，上限 min(78vw,240px)），行高 34px、
 * 间距 0、字号 14px；位置由触发按钮的 rect 算：上方优先 8px，放不下才翻到下方，夹进视口。
 * 位置每次视口变化都重算（2026-10-07 真机报障：只算一次会停在会话中部）。
 * 无「取消」行：点浮层外或按返回键关闭（浮层外的透明遮罩只负责收点击）。
 * @param t - 插件 `NS` 命名空间文案。
 * @param trigger - 被点的回形针入口（定位锚点）。
 * @param readSessionId - 附件那一路的会话身份来源（见 `openAttachmentPicker`）。
 */
function openSheet(t, trigger, readSessionId) {
    // 关掉上一张浮层 —— 必须走它自己的 close（摘监听/rAF/计时器），只 remove() 节点会把监听留在
    // document/window 上（见下方的 disposer 注释）。
    activeClose?.();
    activeClose = null;
    const backdrop = document.createElement('div');
    backdrop.dataset.mobileNav = 'file-picker-backdrop';
    const sheet = document.createElement('div');
    sheet.dataset.mobileNav = 'file-picker';
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    sheet.setAttribute('aria-label', t('fileUploadAttachment'));
    const rows = [
        { kind: 'image', icon: IMAGE_ICON, label: t('fileUploadImage') },
        { kind: 'file', icon: FILE_ICON, label: t('fileUploadAttachment') },
    ];
    /** 打开期间挂上的监听/rAF，关闭时逐个摘掉。 */
    const teardown = [];
    const close = () => {
        if (activeClose === close)
            activeClose = null;
        for (const off of teardown.splice(0))
            off();
        backdrop.remove();
    };
    activeClose = close;
    const onKey = (event) => {
        if (event.key === 'Escape')
            close();
    };
    for (const row of rows) {
        const button = document.createElement('button');
        button.type = 'button';
        button.dataset.mobileNav = 'file-picker-option';
        button.dataset.kind = row.kind;
        const icon = document.createElement('span');
        icon.dataset.mobileNav = 'file-picker-icon';
        icon.innerHTML = row.icon;
        const label = document.createElement('span');
        label.textContent = row.label;
        button.append(icon, label);
        button.addEventListener('click', () => {
            close();
            // 附件优先走桥（App 侧有的话），图片仍走宿主的 accept="image/*"。
            if (row.kind === 'file')
                void openAttachmentPicker(t, readSessionId);
            else
                openHostPicker(row.kind);
        });
        sheet.append(button);
    }
    // 只认浮层外的点按（浮层内的单击不关，避免选完立刻又被关闭）。
    backdrop.addEventListener('click', (event) => {
        if (event.target === backdrop)
            close();
    });
    document.addEventListener('keydown', onKey, true);
    teardown.push(() => document.removeEventListener('keydown', onKey, true));
    backdrop.append(sheet);
    document.body.append(backdrop);
    // 定位：锚点 rect → 上方优先，夹进视口。挂载后再量，所以这里量得到自身尺寸。
    // 先定好位再显形（2026-10-07 店主：「点击回形针弹出来的两个选项，会弹一下」）：点回形针会让
    // 软键盘收起，视口在随后约 200ms 里持续变化，按旧坐标立刻画出来就是「先出现在键盘上方、再跳
    // 一下」。所以隐藏挂载（`visibility` 不参与布局、仍能量尺寸），等连续两帧位置相同再显形；
    // follow 跑完或 400ms 兜底也必须显形，免得 rAF 停摆（后台标签页）把浮层永久留在隐藏态。
    sheet.style.visibility = 'hidden';
    let revealed = false;
    const reveal = () => {
        if (revealed)
            return;
        revealed = true;
        sheet.style.visibility = '';
    };
    let stableKey = '';
    let stableFrames = 0;
    const revealTimer = window.setTimeout(reveal, 400);
    teardown.push(() => window.clearTimeout(revealTimer));
    const place = () => {
        if (!trigger.isConnected) {
            close();
            return;
        }
        // 每一次都重取锚点 rect —— 键盘动画期间锚点自己在动，缓存它等于不跟随
        // （「键盘抬起时浮层仍跟着锚点走」就是这个效果的原始目的）。
        const anchor = trigger.getBoundingClientRect();
        const own = sheet.getBoundingClientRect();
        const settled = computePickerPlacement(anchor, own, visibleBand());
        const nextTop = `${settled.top}px`;
        const nextLeft = `${settled.left}px`;
        // 写前比较：follow 链每帧都调一次 place，值没变时重写 inline 样式会让样式失效并同步
        // 重算这一层（本仓同型修复见 installed-list 的 write()）。
        if (sheet.style.top !== nextTop)
            sheet.style.top = nextTop;
        if (sheet.style.left !== nextLeft)
            sheet.style.left = nextLeft;
        const key = `${settled.top}:${settled.left}`;
        if (key === stableKey)
            stableFrames += 1;
        else {
            stableKey = key;
            stableFrames = 0;
        }
        if (stableFrames >= 2)
            reveal();
    };
    place();
    // 重锚定（2026-10-07 真机报障）：浮层是 fixed，坐标只在打开时算一次的话，点回形针导致软键盘
    // 收起时布局整体下移约一个键盘高，浮层却留在旧视口坐标 —— 真机上停在会话中部（离回形针
    // ≈350px）。视口一变就重算，键盘动画是渐进的所以再跟 12 帧 rAF；锚点脱离文档时直接收起。
    //
    // 「键盘跟随」为什么没被这次去重削弱（本效果的原始目的，2026-10-13 复核）：跟随靠的是
    // ①`place()` 每次都重取锚点 rect、②每次都重算可见带（`visibleBand()` 现读 visualViewport，
    // 键盘动画的每一帧高度都不一样）、③四类视口监听与 12 帧 follow 都仍然接在 `place()` 上。
    // 变的只有**排程去重**：一次键盘动画会在同一帧里连发 resize + visualViewport.resize +
    // visualViewport.scroll（有时再叠 orientationchange），原来每一个都同步 place 一遍，
    // 同一帧做三四遍同样的测量；follow 链本来就每帧 place，所以链在跑时把这些事件吸收掉。
    // 链一结束（12 帧 ≈ 键盘动画跑完）立刻恢复「视口一变就 place」的旧行为。
    let following = true;
    const onViewport = () => {
        if (following)
            return;
        place();
    };
    window.addEventListener('resize', onViewport);
    window.addEventListener('orientationchange', onViewport);
    window.visualViewport?.addEventListener('resize', onViewport);
    window.visualViewport?.addEventListener('scroll', onViewport);
    teardown.push(() => window.removeEventListener('resize', onViewport));
    teardown.push(() => window.removeEventListener('orientationchange', onViewport));
    teardown.push(() => window.visualViewport?.removeEventListener('resize', onViewport));
    teardown.push(() => window.visualViewport?.removeEventListener('scroll', onViewport));
    let frames = 0;
    let raf = requestAnimationFrame(function follow() {
        // The keyboard animation is progressive, so the anchored position only
        // stops changing after ~10 frames; reveal on the last one at the latest.
        place();
        // 浮层可能刚在 place() 里被收起（锚点脱离文档 → close()）：close 的 teardown 已经取消了
        // 本链，这里只需把标志复位、不再排帧、也不再显形。
        if (!sheet.isConnected) {
            following = false;
            return;
        }
        if (++frames < 12)
            raf = requestAnimationFrame(follow);
        else {
            following = false;
            reveal();
        }
    });
    teardown.push(() => cancelAnimationFrame(raf));
}
/**
 * 安装输入框文件入口弹层。按 TOUCH_QUERY 武装（#259 ②：平板 ≥1024 原生布局同样没有拖放/hover；落位不依赖手机壳）。
 * @param ctx - client 上下文。
 */
function installComposerFilePicker(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer file picker', () => {
        const t = ctx.locale.bind(locales_ts_1.NS);
        // 会话身份来源：切了会话就不能再往原 input 交接 —— 宿主切会话时可能复用同一棵
        // composer DOM（元素还连着，只比元素会认错），所以会话 id 也要一起对账。
        const sessionIdOf = () => (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) ?? null;
        const onClick = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const trigger = target.closest(TRIGGER_SELECTOR);
            if (trigger === null || trigger.hasAttribute('disabled'))
                return;
            // 入口自己不再直点宿主 input（ComposerFileButton 2026-10-06 起只渲染按钮），
            // 这里吞掉这一击，唯一的后续是弹层。
            event.preventDefault();
            event.stopPropagation();
            openSheet(t, trigger, sessionIdOf);
        };
        document.addEventListener('click', onClick, true);
        return () => {
            document.removeEventListener('click', onClick, true);
            // 浮层还开着就按它自己的路径关掉（先摘监听/rAF，再删节点）——只 remove() 节点会把 5 个
            // 持久监听留在 document/window 上，切换档位/热重载就叠一套。
            activeClose?.();
            activeClose = null;
            document.querySelector('[data-mobile-nav="file-picker-backdrop"]')?.remove();
        };
    }, breakpoints_ts_1.TOUCH_QUERY);
}
};
__modules["core/attachment-mention-core.js"] = function (require, module, exports) {
"use strict";
// attachment-mention-core.ts — DOM-free, service-free half of the
// 「本次附件」 @ source (effects/attachment-mention.ts). Everything that can be
// decided from plain data lives here so node:test can pin it with no DOM, no
// renderer and no DSH runtime: host-shape normalization, candidate building,
// query filtering, pick parsing and the model serialization of one chip.
//
// Deliberately has ZERO import statements, like reconciler-core.ts and
// session-row-fiber.ts: tests load it through Node's native type stripping and
// the client bundle has nothing to resolve.
Object.defineProperty(exports, "__esModule", { value: true });
exports.ATTACHMENT_SOURCE_ORDER = exports.ATTACHMENT_SOURCE = void 0;
exports.draftAttachmentIdsOf = draftAttachmentIdsOf;
exports.normalizeDraftAttachments = normalizeDraftAttachments;
exports.formatBytes = formatBytes;
exports.buildRefs = buildRefs;
exports.matchesQuery = matchesQuery;
exports.buildCandidates = buildCandidates;
exports.parseRef = parseRef;
exports.clipboardTextOf = clipboardTextOf;
exports.insertForPick = insertForPick;
exports.serializeRef = serializeRef;
/** Source name: unique per trigger in the input-trigger registry, and the
 *  routing key the composer uses to find our codec at submit time. */
exports.ATTACHMENT_SOURCE = 'mobile-nav-attachments';
/** Group display order. The registry sorts ascending (default 0, which is
 *  where ui-reference's @文件/@会话 group sits), so a negative order puts the
 *  small, context-local 「本次附件」 section ABOVE the long file listing. */
exports.ATTACHMENT_SOURCE_ORDER = -10;
/** Version tag inside the opaque ref, so a future format change can tell its
 *  own refs apart from ones cached in an older draft occurrence. */
const REF_VERSION = 1;
function isRecord(value) {
    return typeof value === 'object' && value !== null;
}
/**
 * Ordered draft attachment ids from one input-state snapshot (`attachmentIds`,
 * files + images). The rc.6 image-only `imageIds` fallback was removed with
 * the host floor. Anything else → empty.
 */
function draftAttachmentIdsOf(state) {
    if (!isRecord(state))
        return [];
    const ids = Array.isArray(state.attachmentIds) ? state.attachmentIds : [];
    return ids.filter((id) => typeof id === 'string');
}
/**
 * Normalize host draft descriptors (`{ kind, id, file }`, plus `previewUrl` on
 * images) into plain records. `uploads` is the host `fileUploads` snapshot
 * (`{ [id]: { status } }`), optional. Unknown shapes are dropped, not guessed.
 */
function normalizeDraftAttachments(descriptors, uploads) {
    if (!Array.isArray(descriptors))
        return [];
    const out = [];
    for (const raw of descriptors) {
        if (!isRecord(raw) || typeof raw.id !== 'string')
            continue;
        const kind = raw.kind === 'image' || raw.kind === 'file' ? raw.kind : undefined;
        if (kind === undefined)
            continue;
        const file = isRecord(raw.file) ? raw.file : undefined;
        const name = typeof file?.name === 'string' ? file.name : '';
        const size = typeof file?.size === 'number' && Number.isFinite(file.size) ? file.size : undefined;
        const upload = isRecord(uploads) && isRecord(uploads[raw.id]) ? uploads[raw.id] : undefined;
        const status = upload?.status === 'uploading' || upload?.status === 'ready' || upload?.status === 'error' ? upload.status : undefined;
        out.push({
            id: raw.id,
            kind,
            name,
            ...(size === undefined ? {} : { bytes: size }),
            ...(kind === 'file' && status !== undefined ? { status } : {}),
        });
    }
    return out;
}
/** Compact byte size for the row description. */
function formatBytes(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    const units = ['KB', 'MB', 'GB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit += 1;
    }
    return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} ${units[unit]}`;
}
/** Pick-time refs for the whole rail: ordinal + ambiguity need every sibling. */
function buildRefs(attachments) {
    const counts = new Map();
    for (const a of attachments)
        counts.set(a.name, (counts.get(a.name) ?? 0) + 1);
    return attachments.map((a, index) => ({
        v: REF_VERSION,
        id: a.id,
        kind: a.kind,
        name: a.name,
        ordinal: index + 1,
        ambiguous: a.name === '' || (counts.get(a.name) ?? 0) > 1,
    }));
}
/** Chip / row title: the file name, or the localized unnamed label. */
function labelOf(ref, copy) {
    return ref.name === '' ? copy.unnamed(ref.ordinal) : ref.name;
}
/**
 * Whether one attachment survives the live query. A drilled listing or a
 * path-shaped query (contains `/`) belongs to the @文件 browser, so the
 * section stays out of it; an empty query lists everything.
 */
function matchesQuery(name, query, drilled) {
    if (drilled || query.includes('/'))
        return false;
    const q = query.trim().toLowerCase();
    return q === '' || name.toLowerCase().includes(q);
}
/**
 * Menu rows for the current rail. Empty rail or no match → [] (the menu drops
 * an empty ready group, so the section simply does not appear). Prefix
 * matches rank before infix matches; ties keep rail order.
 */
function buildCandidates(attachments, query, drilled, copy) {
    const q = query.trim().toLowerCase();
    const rows = [];
    for (const [index, ref] of buildRefs(attachments).entries()) {
        const attachment = attachments[index];
        if (attachment === undefined)
            continue;
        const label = labelOf(ref, copy);
        if (!matchesQuery(label, query, drilled))
            continue;
        const parts = [attachment.kind === 'image' ? copy.kindImage : copy.kindFile];
        if (attachment.bytes !== undefined)
            parts.push(formatBytes(attachment.bytes));
        if (attachment.status === 'uploading')
            parts.push(copy.uploading);
        else if (attachment.status === 'error')
            parts.push(copy.failed);
        rows.push({
            rank: q !== '' && label.toLowerCase().startsWith(q) ? 0 : 1,
            index,
            row: { name: label, description: parts.join(' · '), icon: 'file', section: copy.section, value: JSON.stringify(ref) },
        });
    }
    rows.sort((a, b) => a.rank - b.rank || a.index - b.index);
    return rows.map((entry) => entry.row);
}
/** Decode a ref string (candidate value or chip ref); undefined when foreign. */
function parseRef(value) {
    if (typeof value !== 'string')
        return undefined;
    let parsed;
    try {
        parsed = JSON.parse(value);
    }
    catch {
        return undefined;
    }
    if (!isRecord(parsed) || parsed.v !== REF_VERSION)
        return undefined;
    const { id, kind, name, ordinal, ambiguous } = parsed;
    if (typeof id !== 'string' || (kind !== 'file' && kind !== 'image') || typeof name !== 'string')
        return undefined;
    if (typeof ordinal !== 'number' || !Number.isInteger(ordinal) || ordinal < 1 || typeof ambiguous !== 'boolean')
        return undefined;
    return { v: REF_VERSION, id, kind, name, ordinal, ambiguous };
}
/** Clipboard / persistence projection: `@附件:name` (or `@附件:#n`). */
function clipboardTextOf(ref, copy) {
    return `@${copy.mention}:${ref.name === '' ? `#${ref.ordinal}` : ref.name}`;
}
/**
 * Turn one menu pick into the insert outcome; undefined (= default sink) for a
 * value this source did not mint.
 */
function insertForPick(value, copy) {
    const ref = parseRef(value);
    if (ref === undefined)
        return undefined;
    return {
        source: exports.ATTACHMENT_SOURCE,
        ref: JSON.stringify(ref),
        label: labelOf(ref, copy),
        appearance: 'file',
        clipboardText: clipboardTextOf(ref, copy),
    };
}
/**
 * Model form of one chip. It mirrors the identity the model already sees for
 * the attachment itself — dsh-llm renders a file part as
 * `File "<name>" (<bytes> bytes, sha256:…)` and an image part as
 * `Image "<name>" (<attachmentId>)` — so `[attachment: File "report.pdf"]`
 * lines up with exactly one block of the same user message. Names are
 * JSON-quoted like the host does. The ordinal (`#n`, rail order = the order
 * the attachment parts precede the text) is added only when the name alone
 * is ambiguous (unnamed paste, duplicate names). English on purpose: it is
 * model-facing and must not change with the UI locale.
 */
function serializeRef(ref) {
    const parsed = parseRef(ref);
    if (parsed === undefined)
        throw new Error(`${exports.ATTACHMENT_SOURCE}: unrecognized attachment reference`);
    const kind = parsed.kind === 'image' ? 'Image' : 'File';
    const named = parsed.name === '' ? kind : `${kind} ${JSON.stringify(parsed.name)}`;
    return parsed.ambiguous ? `[attachment #${parsed.ordinal}: ${named}]` : `[attachment: ${named}]`;
}
};
__modules["effects/attachment-mention.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ATTACHMENT_EN = exports.ATTACHMENT_ZH = exports.ATTACHMENT_NS = void 0;
exports.readDraftAttachments = readDraftAttachments;
exports.createAttachmentSource = createAttachmentSource;
exports.installAttachmentMention = installAttachmentMention;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const attachment_mention_core_ts_1 = require("./core/attachment-mention-core.js");
/**
 * 「本次附件」 @ source: while the composer rail above the input holds draft
 * attachments (uploaded files and images), typing `@` lists them in their own
 * menu section; a pick inserts one non-editable chip whose model form names
 * that attachment (format + rationale: `serializeRef` in the core file).
 * The `/` command menu is untouched — this source binds `@` only.
 *
 * Mobile-only by the plugin's desktop no-op promise: the source is registered
 * under MOBILE_QUERY via installMobileEffect and unregistered when it stops
 * matching.
 *
 * HOW THE RAIL IS READ (public services only — no DOM, no fiber):
 *   sessions.scope(sessionId)                 → the session-scope ctx
 *   conversation.input.for(actx).state        → SnapshotStore<InputState>
 *       (frozen contract `SessionInput.state`, ui-conversation input/contract.d.ts)
 *   InputState.attachmentIds                  → ordered draft ids
 *   conversation.resolveDraftAttachments(ids) → `{ kind, id, file }` descriptors
 *       (the rc.6 `imageIds` / `draftImages(ids)` predecessors were removed with
 *       the host floor: 0 hits in the 0.2.2-rc.1 ui-conversation bundle)
 *   conversation.fileUploads.getSnapshot()    → `{ [id]: { status } }` (optional)
 * This is exactly what the host's own composer renders the rail from
 * (ui-conversation lib/client.js: InputBar `resolveDraftAttachments(input.attachmentIds)`).
 *
 * RE-AUDIT ON EVERY HOST UPGRADE (ui-conversation / ui-input-trigger):
 * - `attachmentIds` / `resolveDraftAttachments` / `fileUploads` names (the
 *   two latter are NOT in the frozen IConversation face — public class
 *   members only). A rename degrades to "section never appears", not to an error.
 * - `sinkSerialized` clears attachmentIds BEFORE codec.serialize runs, which is
 *   why the chip ref is a self-contained pick-time snapshot.
 * - rc.2 candidate fields `section`/`value`, `showGroupTitle`, `drilled`, and
 *   the insert `appearance` (chip glyphs: 'session' | 'file' | 'folder' only —
 *   there is no image glyph, so images use 'file').
 */
/** Own locale namespace, package-prefixed (locales.ts stays untouched; same NS/zh/en shape as ui-reference). */
exports.ATTACHMENT_NS = 'dshWebMobileNav.attachments';
exports.ATTACHMENT_ZH = {
    'section': '本次附件',
    'kind.file': '文件',
    'kind.image': '图片',
    'status.uploading': '上传中',
    'status.failed': '上传失败',
    'unnamed': '附件 {n}',
    'mention': '附件',
};
exports.ATTACHMENT_EN = {
    'section': 'Attachments in this message',
    'kind.file': 'File',
    'kind.image': 'Image',
    'status.uploading': 'Uploading',
    'status.failed': 'Upload failed',
    'unnamed': 'Attachment {n}',
    'mention': 'attachment',
};
/**
 * The current draft rail of one session, or [] when this host generation
 * does not expose it (missing members = capability absent, not an error).
 */
function readDraftAttachments(ctx, sessionId) {
    const sessions = ctx.get('sessions');
    const conversation = ctx.get('conversation');
    const actx = sessions?.scope?.(sessionId);
    if (actx === undefined || conversation?.input?.for === undefined)
        return [];
    const ids = (0, attachment_mention_core_ts_1.draftAttachmentIdsOf)(conversation.input.for(actx)?.state?.getSnapshot());
    if (ids.length === 0)
        return [];
    const descriptors = typeof conversation.resolveDraftAttachments === 'function'
        ? conversation.resolveDraftAttachments(ids)
        : undefined;
    return (0, attachment_mention_core_ts_1.normalizeDraftAttachments)(descriptors, conversation.fileUploads?.getSnapshot());
}
function copyOf(ctx) {
    const t = ctx.locale.bind(exports.ATTACHMENT_NS);
    return {
        section: t('section'),
        kindFile: t('kind.file'),
        kindImage: t('kind.image'),
        uploading: t('status.uploading'),
        failed: t('status.failed'),
        unnamed: (n) => t('unnamed', { n }),
        mention: t('mention'),
    };
}
/** The source object; `ctx` must be a scope where inputTriggers/sessions/conversation resolve. */
function createAttachmentSource(ctx) {
    return {
        trigger: '@',
        name: attachment_mention_core_ts_1.ATTACHMENT_SOURCE,
        order: attachment_mention_core_ts_1.ATTACHMENT_SOURCE_ORDER,
        // Section title comes from the candidates' own `section`; the group title
        // would otherwise look up `slash.menu` with our raw source name.
        showGroupTitle: false,
        candidates(session, req) {
            if (req.signal.aborted)
                return Promise.resolve([]);
            // MUST NOT THROW. The host calls `source.candidates(...)` synchronously and
            // only attaches `.then(onFulfilled, onRejected)` AFTER the call returns
            // (ui-input-trigger `fetchCandidates`: 「for (const source of roster)
            // source.candidates(projection, …).then(…)」), so a synchronous throw here
            // is nobody's rejection and takes down that whole `@` / `/` menu beat.
            // The read below is deliberately synchronous, and its dependencies are
            // host-side getters that can throw on a released scope
            // (`conversation.input.for()` → "requires a retained Session scope"), so a
            // capability gap must stay a capability gap: an empty list, the same
            // result as a host generation without these members.
            try {
                // Synchronous read: resolves in the same microtask turn as the reference
                // source's skeleton, so no pending row flashes for this group.
                return Promise.resolve((0, attachment_mention_core_ts_1.buildCandidates)(readDraftAttachments(ctx, session.sessionId), req.query, req.drilled === true, copyOf(ctx)));
            }
            catch {
                return Promise.resolve([]);
            }
        },
        onPick({ candidate }) {
            const insert = (0, attachment_mention_core_ts_1.insertForPick)(candidate.value, copyOf(ctx));
            return insert === undefined ? undefined : { insert };
        },
        codec: {
            // The chip's own clipboard projection is cached on the occurrence at
            // insert time; this path only serves owner-side re-projection.
            clipboardText: (ref) => (0, attachment_mention_core_ts_1.insertForPick)(ref, copyOf(ctx))?.clipboardText ?? '',
            serialize: (ref) => {
                try {
                    return Promise.resolve((0, attachment_mention_core_ts_1.serializeRef)(ref));
                }
                catch (error) {
                    return Promise.reject(error);
                }
            },
        },
    };
}
/**
 * Register the 「本次附件」 @ source while the mobile breakpoint matches.
 * `inputTriggers` is optional at the fiber level (ctx.inject): a host
 * without ui-input-trigger simply never arms the feature.
 * @param ctx - client root context.
 */
function installAttachmentMention(ctx) {
    ctx.effect(() => ctx.locale.register(exports.ATTACHMENT_NS, { zh: exports.ATTACHMENT_ZH, en: exports.ATTACHMENT_EN }), 'dsh-web-mobile: attachment mention dictionaries');
    ctx.inject(['inputTriggers'], (scope) => {
        (0, breakpoints_ts_1.installMobileEffect)(scope, 'dsh-web-mobile: attachment mention source', () => {
            const registry = scope.get('inputTriggers');
            if (typeof registry?.registerSource !== 'function')
                return undefined;
            return registry.registerSource(createAttachmentSource(scope));
        });
    });
}
};
__modules["components/file-share-locale.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.fileShareEn = exports.fileShareZh = exports.FILE_SHARE_NS = void 0;
/**
 * `dshWebMobileNavFileShare` namespace: file-share copy, registered by
 * installFileShare (effects/file-share.ts) through ctx.locale.register — kept
 * out of i18n/locales.ts so the feature owns its dictionary end to end.
 */
exports.FILE_SHARE_NS = 'dshWebMobileNavFileShare';
/** Simplified Chinese dictionary (the key-set source of truth). */
exports.fileShareZh = {
    'shareFile': '分享「{name}」',
    'downloadFile': '下载「{name}」',
    'sharing': '正在准备文件…',
    'downloadedUnsupported': '当前环境不支持直接分享；已开始下载，请在系统保存位置确认',
    'downloadedTooLarge': '文件超过 50 MB；已开始下载，请在系统保存位置确认',
    'downloadedShareFailed': '分享未能完成；已开始下载，请在系统保存位置确认',
    'errorTooLarge': '文件太大（超过 200 MB），无法在手机端分享或下载',
    'errorNotFound': '文件不存在或已被移动',
    'errorGeneric': '分享失败：{message}',
};
/** English dictionary, key-identical to the Chinese source of truth. */
exports.fileShareEn = {
    'shareFile': 'Share "{name}"',
    'downloadFile': 'Download "{name}"',
    'sharing': 'Preparing file…',
    'downloadedUnsupported': 'Direct sharing is unavailable here; a download has started — confirm it at the location your system shows',
    'downloadedTooLarge': 'The file is over 50 MB; a download has started — confirm it at the location your system shows',
    'downloadedShareFailed': 'Sharing did not finish; a download has started — confirm it at the location your system shows',
    'errorTooLarge': 'The file is too large (over 200 MB) to share or download on mobile',
    'errorNotFound': 'The file no longer exists or was moved',
    'errorGeneric': 'Share failed: {message}',
};
};
__modules["components/file-share-core.js"] = function (require, module, exports) {
"use strict";
/**
 * File-share pure core: how a workspace file's bytes are read and how they
 * reach the user — the system share sheet when the platform can share files,
 * a plain download otherwise. DOM-free and import-free so node --test drives
 * every decision with injected fakes (tests/file-share.test.ts); the React
 * controls in file-share-controls.tsx bind the real navigator/document.
 *
 * Reading goes through the host's own Remote (`remote.workspaceFiles.readBytes`,
 * dsh-api-workspace-files) — the same call the document preview uses, so
 * authentication, sandbox policy and workspace confinement stay host-owned.
 * Reads are windowed: one ranged call is capped by the host `maxBytes`
 * (default 2 MiB, a larger window is refused rather than shortened), so the
 * file is pulled in CHUNK_BYTES windows until EOF.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.FileShareError = exports.CHUNK_BYTES = exports.SHARE_MAX_BYTES = void 0;
exports.readWholeFile = readWholeFile;
exports.isAbortError = isAbortError;
exports.canShareFiles = canShareFiles;
exports.deliverFile = deliverFile;
exports.fileNameOf = fileNameOf;
exports.mimeTypeOf = mimeTypeOf;
/** Above this size the share sheet is skipped and the file is downloaded instead. */
exports.SHARE_MAX_BYTES = 50 * 1024 * 1024;
/** Above this size nothing is read at all: the browser would hold the whole file in memory. */
const READ_MAX_BYTES = 200 * 1024 * 1024;
/** One ranged read; well under the host's default 2 MiB per-call window cap. */
exports.CHUNK_BYTES = 1024 * 1024;
/** A share-flow failure carrying its stable code. */
class FileShareError extends Error {
    code;
    /**
     * @param code - stable failure code.
     * @param message - diagnostic text.
     */
    constructor(code, message) {
        super(message);
        this.code = code;
        this.name = 'FileShareError';
    }
}
exports.FileShareError = FileShareError;
/** Map one Remote failure onto a share-flow failure. */
function remoteFailure(error) {
    const code = error.code ?? '';
    if (code === 'workspace-file/too-large')
        return new FileShareError('too-large', error.message ?? code);
    if (code === 'workspace-file/not-found' || code === 'workspace-file/not-regular-file') {
        return new FileShareError('not-found', error.message ?? code);
    }
    return new FileShareError('read-failed', error.message ?? (code || 'read failed'));
}
/**
 * Read a whole file in windows until EOF. The first window also reports the
 * file's size (`bytes`), so an oversized file is refused after one small read.
 * @param read - bound ranged read.
 * @param path - file path.
 * @param signal - cancellation; an abort rejects with the signal's reason.
 * @param chunk - window size (tests shrink it).
 * @param max - refuse files above this size.
 * @returns the chunks and their total size.
 */
async function readWholeFile(read, path, signal, chunk = exports.CHUNK_BYTES, max = READ_MAX_BYTES) {
    const parts = [];
    let offset = 0;
    for (;;) {
        signal.throwIfAborted();
        const result = await read(path, { offset, length: chunk }, signal);
        signal.throwIfAborted();
        if (!result.ok)
            throw remoteFailure(result.error);
        const { data, eof, bytes } = result.value;
        if (bytes !== undefined && bytes > max) {
            throw new FileShareError('too-large', `${path}: ${bytes} bytes exceed the ${max} byte share cap`);
        }
        // Copy into a fresh ArrayBuffer-backed view: the payload may be a view
        // over a larger transport buffer, and Blob parts want ArrayBuffer views.
        parts.push(new Uint8Array(data));
        offset += data.length;
        if (offset > max)
            throw new FileShareError('too-large', `${path}: more than ${max} bytes`);
        // An empty window with eof unset would loop forever; treat it as the end.
        if (eof || data.length === 0)
            break;
    }
    return { parts, size: offset };
}
/** Whether a rejection is the user dismissing the share sheet. */
function isAbortError(error) {
    return typeof error === 'object' && error !== null && error.name === 'AbortError';
}
/**
 * Whether this platform can deliver a file to another app at all.
 *
 * The UI reads it to label the button HONESTLY: when nothing can share, the
 * press can only end in a download, so calling it 「分享」 would be a lie (owner
 * report 2026-10-07: 「还是选择存到哪里，而不是分享到媒体，比如说微信QQ，不然这不算是
 * 文件分享」). Deliberately a *surface* probe, not a per-file `canShare` call:
 * `canShare` wants real `File` objects, and a fabricated probe object makes
 * browsers report false even for shareable files.
 *
 * Deliberately NOT named after `navigator.share`: Android WebView implements no
 * Web Share at all (only standalone browsers do), so a host-side bridge — e.g. a
 * DSHA `ACTION_SEND` entry point — is the route that actually matters on a phone,
 * and it would NOT show up in these two seams. When such a bridge lands, extend
 * `ShareDeps` with it and make this predicate (`bridge !== undefined ||` …) and
 * the `deliverFile` routing order **bridge → Web Share → download** agree; the
 * label and the glyph follow this predicate automatically, so the button flips
 * back to 「分享「x」」 with no UI change. Do not pre-guess the bridge's shape from
 * here: a `path`-only bridge needs the host half, a byte/base64 bridge does not.
 * @param deps - platform seams.
 * @returns true when both Web Share members are present.
 */
function canShareFiles(deps) {
    return deps.share !== undefined && deps.canShare !== undefined;
}
/**
 * Deliver one file: the system share sheet when the platform can share it,
 * otherwise a download. A dismissed sheet (AbortError) is a silent cancel;
 * any other share rejection (NotAllowedError after the user-activation window
 * lapsed during a long read, a target app refusing the type, …) still gets
 * the file to the user through the download route.
 * @param file - the file to deliver.
 * @param deps - platform seams.
 * @returns the outcome.
 */
async function deliverFile(file, deps) {
    if (file.size > (deps.shareMax ?? exports.SHARE_MAX_BYTES)) {
        deps.download(file);
        return { kind: 'downloaded', reason: 'too-large' };
    }
    // No share seam at all: a real branch of its own (it used to hide inside
    // `!supported || deps.share === undefined`, where the second half could
    // never decide anything because `supported` was already false).
    const share = deps.share;
    if (share === undefined) {
        deps.download(file);
        return { kind: 'downloaded', reason: 'unsupported' };
    }
    let supported = false;
    try {
        supported = deps.canShare?.({ files: [file] }) === true;
    }
    catch {
        supported = false;
    }
    if (!supported) {
        deps.download(file);
        return { kind: 'downloaded', reason: 'unsupported' };
    }
    try {
        await share({ files: [file], title: file.name });
        return { kind: 'shared' };
    }
    catch (error) {
        if (isAbortError(error))
            return { kind: 'cancelled' };
        deps.download(file);
        return { kind: 'downloaded', reason: 'share-failed' };
    }
}
/** Common extensions → MIME type; share targets filter on it, so a guess beats octet-stream. */
const MIME_BY_EXTENSION = {
    txt: 'text/plain', log: 'text/plain', md: 'text/markdown', markdown: 'text/markdown',
    csv: 'text/csv', tsv: 'text/tab-separated-values', json: 'application/json',
    xml: 'application/xml', yaml: 'text/yaml', yml: 'text/yaml', html: 'text/html', htm: 'text/html',
    css: 'text/css', js: 'text/javascript', mjs: 'text/javascript', ts: 'text/plain', tsx: 'text/plain',
    py: 'text/plain', sh: 'text/plain', svg: 'image/svg+xml',
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
    bmp: 'image/bmp', heic: 'image/heic', avif: 'image/avif',
    mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4', ogg: 'audio/ogg',
    mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm',
    pdf: 'application/pdf', zip: 'application/zip', gz: 'application/gzip', tar: 'application/x-tar',
    doc: 'application/msword',
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    xls: 'application/vnd.ms-excel',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    ppt: 'application/vnd.ms-powerpoint',
    pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
};
/**
 * The basename of a host path, in either separator spelling.
 * @param path - absolute or relative path.
 * @returns the last segment (the path itself when it has none).
 */
function fileNameOf(path) {
    const segments = path.split(/[/\\]+/).filter(Boolean);
    return segments.at(-1) ?? path;
}
/**
 * MIME type guessed from a file name's extension.
 * @param name - file name.
 * @returns the guessed type, or application/octet-stream.
 */
function mimeTypeOf(name) {
    const dot = name.lastIndexOf('.');
    if (dot <= 0)
        return 'application/octet-stream';
    return MIME_BY_EXTENSION[name.slice(dot + 1).toLowerCase()] ?? 'application/octet-stream';
}
};
__modules["components/file-share-controls.js"] = function (require, module, exports) {
"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.FilePreviewShareButton = FilePreviewShareButton;
exports.FilesRowShare = FilesRowShare;
const jsx_runtime_1 = require("react/jsx-runtime");
const react_1 = require("react");
const primitives = __importStar(require("@deepseek-ai/dsh-client-ui-primitives"));
const file_share_core_ts_1 = require("./components/file-share-core.js");
const hostTable = primitives;
// 宿主下限 0.2.2-rc.1 只导出 *OutlineRegular（*16 是 0.1.x 的名字，永远取不到）。
const asIcon = (value) => typeof value === 'function' ? value : undefined;
const hostShareIcon = asIcon(hostTable.IconShareOutlineRegular);
const hostWarningIcon = asIcon(hostTable.IconWarningOutlineRegular);
const HostToastComponent = typeof hostTable.Toast === 'function' ? hostTable.Toast : undefined;
/** Inline share glyph for injected DOM rows and hosts without a share icon (currentColor). */
const SHARE_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M8 1.5v8.5M8 1.5 5 4.5M8 1.5l3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>'
    + '<path d="M5 7H4a1.5 1.5 0 0 0-1.5 1.5v4A1.5 1.5 0 0 0 4 14h8a1.5 1.5 0 0 0 1.5-1.5v-4A1.5 1.5 0 0 0 12 7h-1" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>';
const DOWNLOAD_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M8 1.5v8.5M8 10 5 7M8 10l3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>'
    + '<path d="M5 12.5H4A1.5 1.5 0 0 0 2.5 14v.5h11V14A1.5 1.5 0 0 0 12 12.5h-1" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>';
/** 平台没有 Web Share 时按钮只能"下载"，图标也跟着换成下载方向（诚实语义）。 */
function DownloadGlyph() {
    return (0, jsx_runtime_1.jsx)("span", { "aria-hidden": "true", dangerouslySetInnerHTML: { __html: DOWNLOAD_SVG } });
}
function ShareGlyph() {
    if (hostShareIcon !== undefined) {
        const Icon = hostShareIcon;
        return (0, jsx_runtime_1.jsx)(Icon, { size: 16 });
    }
    return (0, jsx_runtime_1.jsx)("span", { "aria-hidden": "true", dangerouslySetInnerHTML: { __html: SHARE_SVG } });
}
/**
 * Minimal self-built notice for hosts without the primitives Toast: one
 * body-level status line, replaced by the next one, gone after 3s. Mounted on
 * <body> so no transformed sheet ancestor can mis-place the fixed box.
 */
function showFallbackNotice(text) {
    document.querySelector('[data-mobile-nav="file-share-toast"]')?.remove();
    const node = document.createElement('div');
    node.dataset.mobileNav = 'file-share-toast';
    node.setAttribute('role', 'status');
    node.textContent = text;
    document.body.appendChild(node);
    setTimeout(() => node.remove(), 3000);
}
/**
 * The button label: 「分享」 only when the platform really has Web Share, else
 * 「下载」 — the press can only end in a download there, and calling that
 * 「分享」 was the owner's 2026-10-07 complaint.
 * @param t - file-share copy.
 * @param name - displayed file name.
 * @returns the label for the current state.
 */
function shareLabel(t, name) {
    return (0, file_share_core_ts_1.canShareFiles)(platformDeps()) ? t('shareFile', { name }) : t('downloadFile', { name });
}
/** Copy for one outcome; null when the outcome speaks for itself (sheet shown / dismissed). */
function outcomeText(outcome, t) {
    if (outcome.kind !== 'downloaded')
        return null;
    if (outcome.reason === 'too-large')
        return t('downloadedTooLarge');
    if (outcome.reason === 'share-failed')
        return t('downloadedShareFailed');
    return t('downloadedUnsupported');
}
/** Copy for one failure. */
function failureText(error, t) {
    if (error instanceof file_share_core_ts_1.FileShareError) {
        if (error.code === 'too-large')
            return t('errorTooLarge');
        if (error.code === 'not-found')
            return t('errorNotFound');
    }
    return t('errorGeneric', { message: error instanceof Error ? error.message : String(error) });
}
/** Anchor download, revoked once the browser has had time to start it. */
function downloadFile(file) {
    const url = URL.createObjectURL(file);
    const link = document.createElement('a');
    link.href = url;
    link.download = file.name;
    link.rel = 'noopener';
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
/** The live platform seams for deliverFile. */
function platformDeps() {
    const nav = navigator;
    const bridge = window.DSHA;
    return {
        canShare: typeof bridge?.canShareFile === 'function'
            ? data => data.files.length === 1 && bridge.canShareFile(data.files[0])
            : typeof nav.canShare === 'function' ? data => nav.canShare(data) : undefined,
        share: typeof bridge?.shareFile === 'function'
            ? data => bridge.shareFile(data.files[0])
            : typeof nav.share === 'function' ? data => nav.share(data) : undefined,
        download: downloadFile,
    };
}
/** Shared share-flow state: single flight, busy path, failure notice. */
function useFileShare(sessionId, readFileRange, t) {
    const [busyPath, setBusyPath] = (0, react_1.useState)(null);
    const [notice, setNotice] = (0, react_1.useState)(null);
    const inFlight = (0, react_1.useRef)(null);
    const seq = (0, react_1.useRef)(0);
    const latest = (0, react_1.useRef)({ sessionId, readFileRange, t });
    latest.current = { sessionId, readFileRange, t };
    (0, react_1.useEffect)(() => () => inFlight.current?.abort(), []);
    const announce = (0, react_1.useCallback)((text) => {
        if (HostToastComponent === undefined) {
            showFallbackNotice(text);
            return;
        }
        seq.current += 1;
        setNotice({ seq: seq.current, text });
    }, []);
    const share = (0, react_1.useCallback)((path) => {
        if (inFlight.current !== null)
            return;
        const controller = new AbortController();
        inFlight.current = controller;
        setBusyPath(path);
        const { sessionId: id, readFileRange: read, t: tr } = latest.current;
        const run = async () => {
            const { parts } = await (0, file_share_core_ts_1.readWholeFile)((p, range, signal) => read(id, p, range, signal), path, controller.signal);
            const name = (0, file_share_core_ts_1.fileNameOf)(path);
            const file = new File(parts, name, { type: (0, file_share_core_ts_1.mimeTypeOf)(name) });
            const outcome = await (0, file_share_core_ts_1.deliverFile)(file, platformDeps());
            const text = outcomeText(outcome, tr);
            if (text !== null)
                announce(text);
        };
        run().catch((error) => {
            if (controller.signal.aborted)
                return;
            console.warn('[dsh-web-mobile] file share failed:', error);
            announce(failureText(error, tr));
        }).finally(() => {
            if (inFlight.current === controller)
                inFlight.current = null;
            if (!controller.signal.aborted)
                setBusyPath(null);
        });
    }, [announce]);
    const toast = notice === null || HostToastComponent === undefined
        ? null
        : ((0, jsx_runtime_1.jsx)(HostToastComponent, { text: notice.text, ...(hostWarningIcon === undefined ? {} : { icon: (0, jsx_runtime_1.jsx)(IconWarning, {}) }), onDone: () => setNotice(null) }, notice.seq));
    return { busyPath, share, toast };
}
function IconWarning() {
    const Icon = hostWarningIcon;
    return Icon === undefined ? null : (0, jsx_runtime_1.jsx)(Icon, {});
}
/**
 * Preview header share button, contributed to the documentpreview-declared
 * `sidebar.right.tab.document.actions` list slot ("Header toolbar
 * contributions acting on the previewed file"). The owner renders the slot
 * only once the file's Host path is known, for every renderer (text, code,
 * image, PDF, Office, unsupported).
 */
function FilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }) {
    const { busyPath, share, toast } = useFileShare(sessionId, readFileRange, t);
    const busy = busyPath !== null;
    return ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "file-share-preview", "aria-label": busy ? t('sharing') : shareLabel(t, (0, file_share_core_ts_1.fileNameOf)(absolutePath)), "aria-busy": busy, disabled: busy, onClick: () => share(absolutePath), children: (0, file_share_core_ts_1.canShareFiles)(platformDeps()) ? (0, jsx_runtime_1.jsx)(ShareGlyph, {}) : (0, jsx_runtime_1.jsx)(DownloadGlyph, {}) }), toast] }));
}
/** Marker on each injected row button (idempotence + CSS + probe key). */
const ROW_BUTTON = 'file-share-row';
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
function FilesRowShare({ sessionId, readFileRange, t }) {
    const { busyPath, share, toast } = useFileShare(sessionId, readFileRange, t);
    const anchorRef = (0, react_1.useRef)(null);
    const latest = (0, react_1.useRef)({ busyPath, share, t });
    latest.current = { busyPath, share, t };
    const decorateRef = (0, react_1.useRef)(null);
    (0, react_1.useEffect)(() => {
        const root = anchorRef.current?.closest('[data-files-state="tree"]') ?? null;
        const body = root?.querySelector('[data-files-body]') ?? null;
        if (body === null)
            return undefined;
        const onClick = (event) => {
            event.preventDefault();
            event.stopPropagation();
            const button = event.currentTarget;
            const path = button.parentElement?.getAttribute('data-files-path');
            if (path)
                latest.current.share(path);
        };
        const decorate = () => {
            const { busyPath: busy, t: tr } = latest.current;
            for (const stale of body.querySelectorAll(`[data-mobile-nav="${ROW_BUTTON}"]`)) {
                if (stale.parentElement?.getAttribute('data-files-entry') !== 'file')
                    stale.remove();
            }
            for (const row of body.querySelectorAll('li[data-files-entry="file"]')) {
                let button = row.querySelector(`:scope > [data-mobile-nav="${ROW_BUTTON}"]`);
                if (button === null) {
                    button = document.createElement('button');
                    button.type = 'button';
                    button.dataset.mobileNav = ROW_BUTTON;
                    button.innerHTML = (0, file_share_core_ts_1.canShareFiles)(platformDeps()) ? SHARE_SVG : DOWNLOAD_SVG;
                    button.addEventListener('click', onClick);
                    row.appendChild(button);
                }
                const path = row.getAttribute('data-files-path') ?? '';
                const active = busy !== null && busy === path;
                const label = active ? tr('sharing') : shareLabel(tr, (0, file_share_core_ts_1.fileNameOf)(path));
                // Single flight is per tree (`inFlight` lives in this component) and
                // `share()` returns immediately while one is running, so a tap on any
                // OTHER row used to do visibly nothing. Block every row button for the
                // flight: the tapped one keeps its 「分享中」 label, the rest read as
                // disabled instead of silently dead. No new async path, no change to the
                // success/failure routes.
                const blocked = busy !== null;
                if (button.getAttribute('aria-label') !== label)
                    button.setAttribute('aria-label', label);
                if (button.disabled !== blocked)
                    button.disabled = blocked;
                if (active)
                    button.setAttribute('aria-busy', 'true');
                else
                    button.removeAttribute('aria-busy');
            }
        };
        decorateRef.current = decorate;
        let raf = 0;
        const schedule = () => {
            if (raf !== 0)
                return;
            raf = requestAnimationFrame(() => {
                raf = 0;
                decorate();
            });
        };
        // childList only: our own aria/disabled writes never re-trigger it, and
        // the appended buttons settle in one extra (idempotent) pass.
        const observer = new MutationObserver(schedule);
        observer.observe(body, { childList: true, subtree: true });
        decorate();
        return () => {
            observer.disconnect();
            if (raf !== 0)
                cancelAnimationFrame(raf);
            decorateRef.current = null;
            for (const button of body.querySelectorAll(`[data-mobile-nav="${ROW_BUTTON}"]`)) {
                button.removeEventListener('click', onClick);
                button.remove();
            }
        };
    }, []);
    // Busy state and locale switches re-label the existing buttons in place.
    (0, react_1.useEffect)(() => {
        decorateRef.current?.();
    }, [busyPath, t]);
    return ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("span", { hidden: true, "data-mobile-nav": "file-share-anchor", ref: anchorRef }), toast] }));
}
};
__modules["effects/file-share.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installFileShare = installFileShare;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const file_share_locale_ts_1 = require("./components/file-share-locale.js");
const file_share_controls_tsx_1 = require("./components/file-share-controls.js");
/**
 * Install the mobile file-share entries.
 * @param ctx - client root context.
 */
function installFileShare(ctx) {
    // `remote.workspaceFiles` is not in the root inject list: a child fiber
    // waits for it, so hosts without the namespace stay a quiet no-op.
    ctx.inject(['slots', 'locale', 'remote', 'remote.workspaceFiles'], (scope) => {
        scope.effect(() => scope.locale.register(file_share_locale_ts_1.FILE_SHARE_NS, { zh: file_share_locale_ts_1.fileShareZh, en: file_share_locale_ts_1.fileShareEn }), 'dsh-web-mobile: file-share dictionaries');
        const remote = scope.remote;
        const inject = () => ({
            readFileRange: (sessionId, path, range, signal) => remote.workspaceFiles.readBytes(sessionId, path, { range }, signal),
        });
        (0, breakpoints_ts_1.installMobileEffect)(scope, 'dsh-web-mobile: file share', () => {
            const stops = [
                scope.slots.inject('sidebar.right.tab.document.actions', () => scope.slots.register({
                    name: 'sidebar.right.tab.document.actions',
                    id: 'mobile-nav-file-share',
                    order: 100,
                    locale: file_share_locale_ts_1.FILE_SHARE_NS,
                    inject,
                }, file_share_controls_tsx_1.FilePreviewShareButton)),
                scope.slots.inject('sidebar.right.tab.files.actions', () => scope.slots.register({
                    name: 'sidebar.right.tab.files.actions',
                    id: 'mobile-nav-file-share-rows',
                    order: 100,
                    locale: file_share_locale_ts_1.FILE_SHARE_NS,
                    inject,
                }, file_share_controls_tsx_1.FilesRowShare)),
            ];
            return () => {
                for (const stop of stops)
                    stop();
            };
        });
    });
}
};
__modules["core/layout-compat.js"] = function (require, module, exports) {
"use strict";
// Every supported host (>=0.2.2-rc.1, dsh-client-ui-layout) exposes
// selectPanel(panelId | null) on the layout service. The repo still
// type-checks against the pinned 0.1.0-rc.6 typings, whose ILayout carries only
// toggleSidebar/openDetails/closeDetails, so the call stays a structural probe:
// compile-green against those typings, and inert rather than throwing if a
// host ever drops the method.
//
// Kept DOM-free and dependency-free (like sessions-compat.ts) so it is directly
// unit-testable and compiles against either generation's typings — never import
// the layout service types here.
Object.defineProperty(exports, "__esModule", { value: true });
exports.panelSelectorOf = panelSelectorOf;
/**
 * The "leave the panel" action, or null when the host has no panel-selection
 * API at all (rc.6).
 *
 * The sidebar panel list is an alpha.2-era surface, so on rc.6 nothing arms
 * this — but the capability is probed rather than assumed, because a plugin
 * that throws on an older host is worse than one that goes inert.
 *
 * @param layout - `ctx.layout` as handed to the plugin.
 * @returns a no-argument action calling `selectPanel(null)`, or null.
 */
function panelSelectorOf(layout) {
    if (typeof layout !== 'object' || layout === null)
        return null;
    const select = layout.selectPanel;
    if (typeof select !== 'function')
        return null;
    return () => {
        ;
        select.call(layout, null);
    };
}
};
__modules["effects/panel-exit.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.panelOwnsMainArea = panelOwnsMainArea;
exports.createPanelExit = createPanelExit;
exports.createPanelBackExitTask = createPanelBackExitTask;
exports.installPanelRowExit = installPanelRowExit;
const layout_compat_ts_1 = require("./core/layout-compat.js");
const frame_ts_1 = require("./platform/frame.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
/**
 * Sidebar panel exit.
 *
 * The host's sidebar panels REPLACE the main area (`ctx.layout.selectPanel(id)`),
 * and the host ships no way back out of one: `PanelRow.onClick` is just
 * `selectPanel(id)` (tapping the already-selected row stays on the panel), and
 * the only caller of `selectPanel(null)` in the whole host is
 * `workspace.replaceMain` — i.e. opening a session. So on a phone, once the
 * drawer row puts you on the plugin-manager page, that page is a dead end
 * unless you already know to open the drawer and pick a session.
 *
 * Three exits, roughly in the order a phone user reaches for them:
 *  1. the system back key / back gesture (popstate) — the returned task
 *  2. tapping the already-selected panel row again — installPanelRowExit
 *  3. the top-left FAB, which reads as 「返回会话」 at the panel root and as
 *     「返回」 on a panel subpage — see createDrawerLayerTask in
 *     drawer-layer.ts
 *
 * All three funnel through the same `exit`, so they cannot drift apart.
 *
 * Panels can have SUBPAGES (2026-10: the plugin manager's 「更多」 list and its
 * three detail roots). Those are component state inside the panel — they add no
 * history entry and expose no service face — but the host does render its own
 * 「返回上一级」 control for them (list: the moreBack button in the more head;
 * detail: the crumb button). So the FAB and the system back go through back
 * first: click that host control one level at a time, and only fall back to
 * exit when no subpage control is on screen. Without it a single back press
 * on a detail page closed the whole panel.
 *
 * History entries are pushed BEFORE a back press and never after one: this
 * WebView drops a pushState issued in the same task as a same-document
 * traversal (measured 2026-10-20 three ways). The panel pushes one entry for
 * itself and one more for each subpage level it descends into, so every back
 * press merely consumes an entry that is already there.
 */
/**
 * The host marks the SELECTED sidebar panel row with `aria-current="page"`.
 * Measured on 0.1.6-alpha.2: `null` in the conversation, `"page"` on a panel —
 * the two states are cleanly separable. `panelRow` is a CSS-module fragment
 * (the host class is `hHd-Xa_panelRow`), so it is matched as a substring per
 * the repo's hashed-class convention.
 *
 * Why DOM and not `ctx.layout`: the layout face carries no readable panel id —
 * the selected panel lives on the host's own `PanelInfo` store, which is handed
 * only to the host's components.
 */
const PANEL_ROW_ACTIVE = '[class*="panelRow"][aria-current="page"]';
/**
 * True from the moment an exit starts until React has committed the swap.
 * During that window the panel row STILL carries `aria-current="page"`, but the
 * panel is semantically gone — history bookkeeping must treat it as closed,
 * otherwise it arms a second entry while the first exit is still in flight.
 */
let panelLeaving = false;
/**
 * The instance that last armed `panelLeaving`. Only the owner may clear it, so a
 * torn-down instance can never blank the flag of the instance that replaced it.
 */
let panelLeavingOwner = null;
/** Whether a panel owns the main area (DOM truth, ignoring the exit window). */
function panelOwnsMainArea() {
    return document.querySelector(PANEL_ROW_ACTIVE) !== null;
}
/** Whether a panel owns the main area, counting the in-flight exit as closed. */
function panelViewOpen() {
    return !panelLeaving && panelOwnsMainArea();
}
/** Marker set on the frame while the incoming conversation fades in. */
const PANEL_EXIT_ATTR = 'data-mobile-panel-exit';
/** Safety net: clears the marker if the reveal animation never fires. */
const PANEL_EXIT_FALLBACK_MS = 2000;
/** The frozen panel snapshot that slides back out (see `exit`). */
const PANEL_GHOST_VALUE = 'panel-ghost';
/**
 * Ghost slide-out length. Must stay in sync with `dsh-web-mobile-panel-ghost-out`
 * in layout.css.ts (same .28s as the drawer's transition); this constant only
 * drives the "animation never fired" cleanup.
 */
const PANEL_GHOST_MS = 280;
/**
 * The panel page React swaps into the main area — the very element the enter
 * rule animates (`[class*="_centerCol"] > * > *`). Queried only while a panel
 * row is active, so it can never pick up conversation content.
 */
const PANEL_ELEMENT_SELECTOR = '[data-mobile-nav="frame"] [class*="_centerCol"] > * > *';
/* @patch
   id: plugin-panel-subpage-back
   for: 0.1.6-alpha.2 宿主的插件管理页子页：列表「更多」头的 button class 含 _moreBack，三种详情根（data-plugin-detail / data-plugin-item-detail / data-plugin-row-detail）里的 crumb button class 含 _crumb
   why: 宿主的「返回上一级」只是组件闭包里的 setView({kind:"list"}) 与 DetailTop 的 onBack，没有公开服务面；而子页是同一个 store 的 view 状态，不产生 history entry —— 只能点宿主自己的控件，不能替它改状态
   until: 宿主把面板子页的返回暴露成 ctx.layout / panel 服务上的公开动作，或子页自己进 history
   test: tests/panel-exit-animation.test.ts
*/
/**
 * The host's own 「返回上一级」 control while a plugin-panel subpage is open.
 *
 * Comma-merged because the three detail roots are siblings, not one family:
 * builtin detail (data-plugin-detail), official item detail
 * (data-plugin-item-detail) and market row detail (data-plugin-row-detail)
 * each get their own crumb. Anchors are host data-* markers / hashed-class
 * SUBSTRINGS only (the repo forbids suffix matching: [class*="_crumb"]
 * deliberately also excludes the crumbIcon svg because it is not a button).
 * Scoped to section[data-plugin-panel] so no other panel's crumb can match.
 */
const PANEL_SUBPAGE_BACK = 'section[data-plugin-panel] button[class*="_moreBack"],' +
    'section[data-plugin-panel] [data-plugin-detail] button[class*="_crumb"],' +
    'section[data-plugin-panel] [data-plugin-item-detail] button[class*="_crumb"],' +
    'section[data-plugin-panel] [data-plugin-row-detail] button[class*="_crumb"]';
/**
 * Leave the panel: freeze the panel into a plugin-owned snapshot that slides back
 * out to the side, and let the conversation come back on the SAME tick
 * (2026-10-07: 店主先报「退出像掉帧、直接回聊天界面」，再报「空档有点久了。」)
 *
 * Two earlier shapes and why they failed:
 *  · marker-then-swap only: React unmounts the panel inside that same commit, so
 *    the exit animation painted an empty 0×0 slot — measured frame by frame, the
 *    panel element was already gone on the first frame after the tap;
 *  · animate-the-real-panel-first: the departure became visible, but the
 *    conversation's remount commit (~390ms on a long session) then started 220ms
 *    later — the owner felt exactly that added blank window.
 *
 * Current shape: clone the panel into `[data-mobile-nav="panel-ghost"]` (fixed,
 * pointer-events:none), swap RIGHT AWAY, then slide the clone out over
 * `PANEL_GHOST_MS` (the drawer's .28s). The clone's transform/opacity run on the
 * compositor, so the slide stays smooth while the main thread pays for the
 * conversation remount — no added gap, and the direction matches the drawer the
 * owner asked to copy.
 *
 * @param layout - `ctx.layout`; probed, never assumed.
 * @returns the exit action (idempotent while an exit is in flight, so a double
 *   tap cannot queue two swaps) and the system-back reconciler task.
 */
function createPanelExit(layout) {
    const selectPanel = (0, layout_compat_ts_1.panelSelectorOf)(layout);
    const supported = selectPanel !== null;
    let leaving = false;
    let disposed = false;
    /** Safety net for the main timeline: the animation's own length + buffer. */
    let exitTimer = null;
    /** Frame callback that samples the swapped subtree (React commits a frame late). */
    let settleRaf = 0;
    /** The snapshot this instance owns, and its own completion signals. */
    let ghost = null;
    let ghostTimer = null;
    let ghostEnd = null;
    /** Only the instance that armed `panelLeaving` may clear it. */
    const owner = Symbol('panel-exit');
    /**
     * The animations actually running on the reveal target
     * (`[class*="_centerCol"] > * > *`, layout.css.ts). Deliberately NOT matched by
     * name: the JS side once spelled the keyframe without its `web-` segment, so
     * `animationend` never fired and the marker lived until the 2000ms net; and
     * `prefers-reduced-motion` replaces the animation with `none`. A name test can
     * never cover both — the animation objects can.
     */
    function revealAnimations() {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return [];
        const running = [];
        for (const center of frame.querySelectorAll('[class*="_centerCol"]')) {
            const target = center.firstElementChild?.firstElementChild;
            if (!(target instanceof HTMLElement))
                continue;
            for (const animation of target.getAnimations()) {
                if (animation.playState !== 'idle')
                    running.push(animation);
            }
        }
        return running;
    }
    /**
     * Finish the MAIN timeline: marker, frame callback, flags — and nothing else.
     * The snapshot owns a longer timeline (PANEL_GHOST_MS); clearing it here was
     * exactly the trap the old name-based path hid behind its 2000ms net.
     */
    function finishMain() {
        if (!leaving)
            return;
        if (exitTimer !== null) {
            window.clearTimeout(exitTimer);
            exitTimer = null;
        }
        if (settleRaf !== 0) {
            cancelAnimationFrame(settleRaf);
            settleRaf = 0;
        }
        // Ownership, not presence: the marker goes on in `exit()` in the same breath
        // as panelLeaving, so the instance that owns that flag also owns the marker.
        // An unconditional removeAttribute would let a disposer that lands after a
        // NEWER instance armed its own exit strip the marker off the reveal that
        // instance is still running (the conversation then flashes at full opacity).
        if (panelLeavingOwner === owner) {
            const frame = (0, frame_ts_1.getFrame)();
            if (frame !== null)
                frame.removeAttribute(PANEL_EXIT_ATTR);
            panelLeaving = false;
            panelLeavingOwner = null;
        }
        leaving = false;
    }
    /** Drop the snapshot this instance owns (idempotent, independent timeline). */
    function finishGhost() {
        if (ghostTimer !== null) {
            window.clearTimeout(ghostTimer);
            ghostTimer = null;
        }
        const el = ghost;
        ghost = null;
        if (el === null)
            return;
        if (ghostEnd !== null)
            el.removeEventListener('animationend', ghostEnd, true);
        ghostEnd = null;
        el.remove();
    }
    /**
     * Wait for the reveal to finish, one frame after the swap so React has
     * committed the incoming subtree. No animation at all (reduced-motion, or a
     * host rule that never applies) finishes on the spot instead of waiting for an
     * event that will never arrive.
     */
    function armExit(attempt) {
        if (disposed || !leaving)
            return;
        const animations = revealAnimations();
        if (animations.length === 0) {
            if (attempt < 2) {
                settleRaf = requestAnimationFrame(() => {
                    settleRaf = 0;
                    armExit(attempt + 1);
                });
                return;
            }
            finishMain();
            return;
        }
        let longest = 0;
        for (const animation of animations) {
            const timing = animation.effect?.getTiming();
            const delay = typeof timing?.delay === 'number' ? timing.delay : 0;
            const duration = typeof timing?.duration === 'number' ? timing.duration : 0;
            const iterations = typeof timing?.iterations === 'number' ? timing.iterations : 1;
            longest = Math.max(longest, delay + duration * iterations);
            // `finished` resolves on completion and rejects on cancel; either way the
            // main timeline is done. The timer below only covers a node React
            // unmounts mid-animation.
            void animation.finished.then(() => finishMain(), () => finishMain());
        }
        exitTimer = window.setTimeout(finishMain, Math.min(PANEL_EXIT_FALLBACK_MS, Math.max(250, longest + 200)));
    }
    /** The panel page element, or null when no panel owns the main area. */
    function panelElement() {
        if (!panelOwnsMainArea())
            return null;
        const el = document.querySelector(PANEL_ELEMENT_SELECTOR);
        return el instanceof HTMLElement ? el : null;
    }
    /**
     * The host's own subpage back control, or null at the panel root. Queried
     * live: host component state, so it appears/disappears with no URL or marker
     * of ours changing. `open` = caller already read the panel state (ensure()).
     */
    function subpageControl(open = false) {
        if (!open && !panelOwnsMainArea())
            return null;
        const el = document.querySelector(PANEL_SUBPAGE_BACK);
        return el instanceof HTMLElement ? el : null;
    }
    /** Whether a subpage (not the panel root) is on screen right now. */
    function subpageOpen() {
        return subpageControl() !== null;
    }
    /**
     * Freeze the outgoing panel into an inert clone that can slide away.
     *
     * An older snapshot of ours is dropped through `finishGhost()` — this
     * instance's own handle — and never through a document-wide sweep of
     * `[data-mobile-nav="panel-ghost"]`. That marker is shared by every instance
     * of this effect, so a sweep (the shape this used to have, in freezePanel and
     * in dispose) lets a stale disposer delete the snapshot a NEWER instance just
     * mounted: the panel vanishes mid-slide and the survivor keeps a handle to a
     * detached node. Ownership is the rule this file already follows for
     * panelLeavingOwner and for the frame marker one function up.
     */
    function freezePanel(panel) {
        finishGhost();
        const ghost = document.createElement('div');
        ghost.dataset.mobileNav = PANEL_GHOST_VALUE;
        ghost.setAttribute('aria-hidden', 'true');
        ghost.append(panel.cloneNode(true));
        document.body.append(ghost);
        return ghost;
    }
    const exit = () => {
        if (disposed || !supported || leaving)
            return;
        leaving = true;
        panelLeaving = true;
        panelLeavingOwner = owner;
        // The marker goes on BEFORE the swap: the incoming conversation must carry its
        // reveal from its first style resolution (no full-opacity frame).
        const frame = (0, frame_ts_1.getFrame)();
        if (frame !== null)
            frame.setAttribute(PANEL_EXIT_ATTR, '');
        const panel = panelElement();
        ghost = panel === null ? null : freezePanel(panel);
        // Commit on this very tick — the whole point of the snapshot is that the
        // remount no longer waits for any animation to finish.
        // 2026-10-13：selectPanel 由**宿主**实现，可能抛。它排在本行的上面（冻结全屏快照）
        // 之后、下面两个兜底（采样帧 + 幽灵计时器）之前 —— 裸调一旦抛错，屏幕上会留下
        // 一张**无人清理的全屏 ghost**，而且面板退场状态永远卡住。
        // try/finally 保证后面的排程与兜底**一定**被装上（异常照旧向外抛，不吞宿主错误）。
        try {
            selectPanel();
        }
        finally {
            // Sample the reveal one frame later: React commits the swapped subtree after
            // this handler returns, and by then the animation objects exist.
            settleRaf = requestAnimationFrame(() => {
                settleRaf = 0;
                armExit(0);
            });
            if (ghost !== null) {
                const owned = ghost;
                ghostEnd = (event) => {
                    if (event.animationName === 'dsh-web-mobile-panel-ghost-out')
                        finishGhost();
                };
                owned.addEventListener('animationend', ghostEnd, true);
                // Fallback: reduced-motion kills the animation, and a layer left behind
                // would cover the whole screen — never rely on animationend alone.
                ghostTimer = window.setTimeout(finishGhost, PANEL_GHOST_MS + 120);
            }
        }
    };
    /**
     * Leave ONE level. A plugin-panel subpage has its own 「返回上一级」 control
     * owned by the host; clicking it is the only available API (the host keeps
     * that view in component state and publishes no service). At the panel root
     * there is no such control, so this is exactly exit.
     *
     * Same guards as exit: a tap that lands while the panel is already leaving,
     * or after teardown, must be a no-op rather than a second swap.
     */
    const back = () => {
        if (disposed || !supported || leaving)
            return;
        const control = subpageControl();
        if (control !== null) {
            // No marker, no history work: the panel is still open and its single
            // armed history entry stays valid. The host re-renders the previous view
            // and the next back call re-queries.
            control.click();
            return;
        }
        exit();
    };
    /**
     * Teardown: the plugin may be unloaded (or the breakpoint flipped) while an
     * exit is in flight, and nothing of ours may survive it — not the marker, not
     * the timers, not a snapshot still covering the screen. Both halves are
     * ownership-scoped: `finishGhost` drops the one snapshot this instance
     * created, so a stale disposer cannot take down a newer instance's.
     */
    function dispose() {
        disposed = true;
        finishMain();
        finishGhost();
    }
    return {
        exit,
        back,
        panelOpen: panelOwnsMainArea,
        subpageOpen,
        task: createPanelBackExitTask(exit, supported, back, subpageControl),
        dispose,
    };
}
/**
 * The system back key / back gesture exits the panel.
 *
 * The host core does not touch the browser history at all (only the PDF preview
 * plugin does, and that is unrelated), and measured on a phone the panel view
 * sits at `history.length === 1` — pressing back there leaves the page
 * entirely. So this layer is free to take over: arm one history entry while a
 * panel is open, and exit the panel when it is popped.
 *
 * Two edges are handled explicitly:
 *  - Our own `history.back()` (used when the panel is left by another route)
 *    echoes back as a popstate. `selfBackPending` swallows that echo, with a
 *    timeout so a host WebView that never emits popstate cannot leave the flag
 *    stuck and eat the user's next real back press.
 *  - Between the click and React's commit the panel row is still marked active;
 *    `panelViewOpen()` reports closed during that window, so no second history
 *    entry is armed.
 *
 * @param exitPanel - the shared exit action.
 * @param supported - false on hosts with no panel-selection API; the task then
 *   never arms a history entry at all.
 * @param backOneLevel - the shared one-level action (the FAB's too): click the
 *   host's own subpage back control, or leave the panel when none is on screen.
 * @param subpageOpen - whether that host control is on screen right now. A
 *   panel subpage gets no history entry of its own, so a back press there must
 *   climb the host view and RE-ARM the entry it just consumed.
 */
function createPanelBackExitTask(exitPanel, supported, backOneLevel, subpageControl) {
    /** Entry for the panel itself: the press that consumes it leaves the panel. */
    let rootArmed = false;
    /** Entries pre-pushed for host subpage levels — one per level we descend. */
    let extraArmed = 0;
    /** Which host back control is on screen; its level tells us where we are. */
    let lastControl = null;
    /** Set by a popstate climb so ensure() does not arm for the level it just left. */
    let justClimbed = false;
    let listening = false;
    let selfBackPending = false;
    let selfBackTimer = null;
    function clearSelfBack() {
        selfBackPending = false;
        if (selfBackTimer !== null) {
            window.clearTimeout(selfBackTimer);
            selfBackTimer = null;
        }
    }
    function selfBack() {
        selfBackPending = true;
        if (selfBackTimer !== null)
            window.clearTimeout(selfBackTimer);
        selfBackTimer = window.setTimeout(clearSelfBack, 1200);
        try {
            history.back();
        }
        catch {
            clearSelfBack();
        }
    }
    /** Add one poppable entry (no URL change). False when the frame refuses it. */
    function pushEntry() {
        try {
            history.pushState({ mobilePanelExit: true }, '');
            return true;
        }
        catch {
            return false;
        }
    }
    function armRoot() {
        if (!supported || rootArmed)
            return;
        if (pushEntry())
            rootArmed = true;
    }
    function armExtra() {
        if (!supported)
            return;
        if (pushEntry())
            extraArmed += 1;
    }
    /** Deeper control = deeper host view. 0 = panel root, 1 = more list, 2 = detail. */
    function levelOf(control) {
        if (control === null)
            return 0;
        return control.className.includes('_moreBack') ? 1 : 2;
    }
    function onPopState() {
        if (selfBackPending) {
            clearSelfBack();
            return;
        }
        // LIFO: the per-level entries sit above the panel entry, so the top one is
        // an extra entry while any remain.
        if (extraArmed > 0)
            extraArmed -= 1;
        else if (rootArmed)
            rootArmed = false;
        else
            return;
        if (panelViewOpen() && subpageControl() !== null) {
            justClimbed = true;
            backOneLevel();
            return;
        }
        if (panelViewOpen())
            exitPanel();
    }
    function listen() {
        if (listening)
            return;
        window.addEventListener('popstate', onPopState);
        listening = true;
    }
    function unlisten() {
        if (!listening)
            return;
        window.removeEventListener('popstate', onPopState);
        listening = false;
    }
    return {
        name: 'panel-back-exit',
        scopes: ['*'],
        /*
         * Entries are pushed BEFORE a back press, never after: this WebView drops a
         * pushState issued in the same task as a same-document traversal (measured
         * 2026-10-20 three ways — inside popstate, from the reconciler rAF right
         * after it, and from a setTimeout(0) task). So the panel pushes one entry
         * for itself, and one more for every host subpage level it descends into;
         * a back press then merely consumes what is already there.
         */
        ensure: () => {
            if (!supported)
                return;
            listen();
            if (!panelViewOpen()) {
                if (rootArmed) {
                    rootArmed = false;
                    selfBack();
                }
                lastControl = null;
                justClimbed = false;
                return;
            }
            armRoot();
            const control = subpageControl(true); // panelViewOpen() 刚读过：同拍复用，3 次查询 → 2 次。
            const previous = levelOf(lastControl);
            const current = levelOf(control);
            if (current === previous)
                return;
            const climbed = justClimbed;
            justClimbed = false;
            lastControl = control;
            if (current > previous) {
                if (!climbed)
                    armExtra();
                return;
            }
            // Went up without a back press (host crumb or the FAB): give back the
            // entry that level was holding.
            if (!climbed && extraArmed > 0) {
                extraArmed -= 1;
                selfBack();
            }
        },
        dispose: () => {
            unlisten();
            clearSelfBack();
            if (rootArmed) {
                rootArmed = false;
                selfBack();
            }
        },
    };
}
/**
 * Tapping the already-selected panel row again returns to the conversation.
 * Unselected rows are left alone — they still go through the host's own
 * `selectPanel(id)`.
 *
 * Runs in the capture phase so it can stop the host's onClick. The drawer close
 * rides the same click (phone-chrome's navigation-tap whitelist), which is the
 * only ordering that survives a touch: closing the drawer on pointerup cancels
 * the synthesized click entirely (see the 抽屉导航 click pitfall).
 */
function installPanelRowExit(ctx, exitPanel) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: panel row returns to conversation', () => {
        const onClick = (event) => {
            const target = event.target;
            if (!(target instanceof Element) || typeof target.closest !== 'function')
                return;
            const row = target.closest('[class*="panelRow"]');
            if (row === null)
                return;
            if (row.getAttribute('aria-current') !== 'page')
                return;
            event.preventDefault();
            event.stopPropagation();
            exitPanel();
        };
        document.addEventListener('click', onClick, true);
        return () => document.removeEventListener('click', onClick, true);
    });
}
};
__modules["debug.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.parseBundleRev = parseBundleRev;
exports.readBundleRev = readBundleRev;
exports.installDebugBadge = installDebugBadge;
const breakpoints_ts_1 = require("./platform/breakpoints.js");
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
const BUNDLE_ID = /[?,]dsh-web-mobile\/client\.js/;
/**
 * Revision from a plugin bundle URL, or null when it carries none.
 * The host mints it per build (`dsh-client-modules` HASH_REVISION_LENGTH = 12)
 * and stamps the same value on the URL it actually serves, so it answers
 * "which build is this page running" with no build-time injection.
 */
function parseBundleRev(url) {
    if (typeof url !== 'string' || url === '')
        return null;
    try {
        const rev = new URL(url, 'http://x').searchParams.get('rev');
        return rev === null || rev === '' ? null : rev;
    }
    catch {
        return null;
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
function readBundleRev() {
    for (const el of document.head.querySelectorAll('script[src], link[rel="preload"]')) {
        const url = el.getAttribute('src') ?? el.getAttribute('href');
        if (url !== null && BUNDLE_ID.test(url))
            return parseBundleRev(url);
    }
    return null;
}
function installDebugBadge(ctx) {
    ctx.effect(() => {
        // 也认 hash：带 token 的地址会先 303 跳到 `/` 鉴权，跳转会把 `?` 后面的参数
        // 全部丢掉（2026-11-06 实测：`/?token=…&mobile-nav-debug=1` → `301/303` →
        // `http://127.0.0.1:41333/`）。`#` 后面的片段在跳转中会保留，所以
        // `/?token=…#mobile-nav-debug=1` 才是这个环境里唯一能活到页面的写法。
        const params = new URLSearchParams(location.search);
        if (!params.has('mobile-nav-debug') && !location.hash.includes('mobile-nav-debug'))
            return () => { };
        const errors = [];
        const onError = (event) => errors.push(`ERR ${event.message.slice(0, 120)}`);
        const onRejection = (event) => errors.push(`REJ ${String(event.reason).slice(0, 120)}`);
        window.addEventListener('error', onError);
        window.addEventListener('unhandledrejection', onRejection);
        const badge = document.createElement('div');
        badge.style.cssText = [
            'position:fixed', 'top:40px', 'right:6px', 'z-index:2147483000',
            'background:rgba(0,0,0,.82)', 'color:#fff', 'font:11px/1.5 ui-monospace,monospace',
            'padding:8px 10px', 'border-radius:8px', 'max-width:94vw', 'max-height:70vh',
            'overflow:auto', 'white-space:pre-wrap', 'pointer-events:none',
        ].join(';');
        const read = () => {
            const q = (sel) => !!document.querySelector(sel);
            const vis = (sel) => {
                const el = document.querySelector(sel);
                return el === null ? 'absent' : getComputedStyle(el).visibility;
            };
            const frame = document.querySelector('[data-mobile-nav="frame"]');
            // Safe-area diagnosis (read-only: the observer below re-enters on any
            // node this adds to the document). framePad IS the resolved
            // env(safe-area-inset-top) - the frame already consumes it - and
            // rightPanel reports whether the host panel carries it too. If a phone
            // shows a covered top row while framePad reads 0px, the inset is 0 on
            // that device and the fix needs another source for the height.
            const rightPanel = () => {
                const el = document.querySelector('[data-sidebar-right-panel]');
                if (el === null)
                    return 'absent';
                const b = el.getBoundingClientRect();
                return `${el.getAttribute('data-sidebar-right-panel')} pad ${getComputedStyle(el).paddingTop} rect ${Math.round(b.top)},${Math.round(b.left)} ${Math.round(b.width)}x${Math.round(b.height)}`;
            };
            /** 浏览器真正给到的 safe-area-inset-top（env() 读不到，只能量一个探针）。 */
            const envInset = () => {
                const probe = document.createElement('div');
                probe.style.cssText = 'position:fixed;top:0;left:0;width:0;height:env(safe-area-inset-top,0px);pointer-events:none;visibility:hidden';
                document.body.appendChild(probe);
                const h = probe.getBoundingClientRect().height;
                probe.remove();
                return `${Math.round(h)}px`;
            };
            const rev = readBundleRev();
            return [
                `build rev ${rev ?? '?'} (diag chips)`,
                `URL ${location.pathname}${location.search}`,
                `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
                `mq≤1023 ${matchMedia(breakpoints_ts_1.MOBILE_QUERY).matches}  mq≥1024 ${matchMedia(breakpoints_ts_1.DESKTOP_QUERY).matches}`,
                `safeTop ${frame === null ? 'n/a' : getComputedStyle(frame).getPropertyValue('--dsha-safe-area-inset-top').trim() || 'unset'}  framePad ${frame === null ? 'n/a' : getComputedStyle(frame).paddingTop}  rightPanel ${rightPanel()}`,
                `env(inset) ${envInset()}  vvOffsetTop ${Math.round(window.visualViewport?.offsetTop ?? -1)}  standalone ${matchMedia('(display-mode: standalone)').matches}  dsha ${typeof window.DSHA !== 'undefined'}`,
                `css ${q('style[data-plugin-css*="mobile"]')}  frame ${!!frame}`,
                `previewCol ${vis('[data-aionui-preview-col]')}  explorerCol ${vis('[data-aionui-explorer-col]')}`,
                `previewOpen ${frame?.hasAttribute('data-aionui-preview-open') ?? '?'}  explorerOpen ${frame?.hasAttribute('data-aionui-explorer-open') ?? '?'}  previewFull ${frame?.hasAttribute('data-mobile-preview-full') ?? '?'}`,
                `header ${vis('[data-phase] header')}  composer ${q('textarea, [data-composer-input]')}`,
                `genui cards ${document.querySelectorAll('[data-genui]').length}  panel ${q('[data-genui-panel]')}`,
                `phase ${document.querySelector('[data-phase]')?.getAttribute('data-phase') ?? '?'}`,
                `errs ${errors.slice(-5).join(' | ') || 'none'}`,
            ].join('\n');
        };
        const paint = () => { badge.textContent = read(); };
        paint();
        // Never re-enter on the badge's own textContent mutations: paint() writes
        // into a body subtree, so a naive full-tree observer would feed its own
        // output back into paint() forever and starve the page (observed as a hard
        // freeze with ?mobile-nav-debug=1).
        const observer = new MutationObserver((records) => {
            for (const record of records) {
                if (record.target === badge || badge.contains(record.target))
                    continue;
                paint();
                return;
            }
        });
        observer.observe(document.body, { childList: true, subtree: true, attributes: true });
        const timer = setInterval(paint, 1500);
        document.body.appendChild(badge);
        // Opt-in device beacon: the same readings are POSTed to a local listener
        // (default http://127.0.0.1:3199/diag, override with ?beacon=<url>) so a
        // phone-side repro can be read from the machine serving the page without
        // anyone copying numbers off the screen. no-cors + a string body keeps it a
        // simple request (no preflight); a missing listener is ignored.
        // Rects of the plugin's own header controls plus the host row they live in:
        // the phone-side position of the files opener is what a "not pinned to the
        // top-right corner" report is about, and it cannot be measured headless.
        const marker = (sel) => {
            const el = document.querySelector(sel);
            if (el === null)
                return 'absent';
            const b = el.getBoundingClientRect();
            return `${Math.round(b.x)},${Math.round(b.y)} ${Math.round(b.width)}x${Math.round(b.height)}`;
        };
        const payload = () => [
            read(),
            `rects toggle ${marker('[data-mobile-nav="toggle"]')} files ${marker('[data-mobile-nav="files"]')} header ${marker('[data-phase] header')} titleCluster ${marker('[class*="_titleCluster"]')}`,
            `chips crea ${marker('[class*="SVAs4q_"]')} team ${marker('[data-team-action]')} model ${marker('[class*="_7KE1Ra_trigger"]')} crumbs ${marker('[class*="_crumbs"]')} crumbCurrent ${marker('[class*="_crumbCurrent"]')}`,
            `ua ${navigator.userAgent}`,
            `screen ${screen.width}x${screen.height} standalone ${matchMedia('(display-mode: standalone)').matches}`,
            `vv ${visualViewport === null ? 'n/a' : `${Math.round(visualViewport.width)}x${Math.round(visualViewport.height)}@${Math.round(visualViewport.offsetTop)}`}`,
        ].join('\n');
        const beacon = params.get('beacon') || 'http://127.0.0.1:3199/diag';
        const beaconTimer = setInterval(() => {
            void fetch(beacon, { method: 'POST', mode: 'no-cors', body: payload() }).catch(() => { });
        }, 2000);
        return () => {
            window.removeEventListener('error', onError);
            window.removeEventListener('unhandledrejection', onRejection);
            observer.disconnect();
            clearInterval(timer);
            clearInterval(beaconTimer);
            badge.remove();
        };
    }, 'dsh-web-mobile: debug badge');
}
};
__modules["index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.inject = void 0;
exports.apply = apply;
const MobileNavToggle_tsx_1 = require("./components/MobileNavToggle.js");
const ComposerFileButton_tsx_1 = require("./components/ComposerFileButton.js");
const open_files_panel_ts_1 = require("./components/open-files-panel.js");
const index_ts_1 = require("./styles/index.js");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const reconciler_adapter_ts_1 = require("./effects/reconciler-adapter.js");
const selection_chrome_ts_1 = require("./effects/selection-chrome.js");
const drawer_close_ts_1 = require("./effects/drawer-close.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
const subagent_chip_touch_ts_1 = require("./effects/subagent-chip-touch.js");
const session_menu_ts_1 = require("./effects/session-menu.js");
const composer_keyboard_guard_ts_1 = require("./effects/composer-keyboard-guard.js");
const composer_keyboard_lift_ts_1 = require("./effects/composer-keyboard-lift.js");
const composer_clearance_ts_1 = require("./effects/composer-clearance.js");
const document_scroll_lock_ts_1 = require("./effects/document-scroll-lock.js");
const question_fullscreen_ts_1 = require("./effects/question-fullscreen.js");
const composer_plus_toggle_ts_1 = require("./effects/composer-plus-toggle.js");
const workspace_chip_toggle_ts_1 = require("./effects/workspace-chip-toggle.js");
const team_chip_toggle_ts_1 = require("./effects/team-chip-toggle.js");
const model_menu_anchor_ts_1 = require("./effects/model-menu-anchor.js");
const shortcut_modal_keyboard_guard_ts_1 = require("./effects/shortcut-modal-keyboard-guard.js");
const model_menu_keyboard_guard_ts_1 = require("./effects/model-menu-keyboard-guard.js");
const plugin_card_tap_ts_1 = require("./effects/plugin-card-tap.js");
const installed_list_ts_1 = require("./effects/installed-list.js");
const tablet_quick_settings_ts_1 = require("./effects/tablet-quick-settings.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const composer_paste_guard_ts_1 = require("./effects/composer-paste-guard.js");
const composer_enter_newline_ts_1 = require("./effects/composer-enter-newline.js");
const focus_gesture_guard_ts_1 = require("./effects/focus-gesture-guard.js");
const composer_file_picker_ts_1 = require("./effects/composer-file-picker.js");
const attachment_mention_ts_1 = require("./effects/attachment-mention.js");
const file_share_ts_1 = require("./effects/file-share.js");
const panel_exit_ts_1 = require("./effects/panel-exit.js");
const debug_ts_1 = require("./debug.js");
const locales_ts_1 = require("./i18n/locales.js");
/**
 * Required services (cordis fiber inject — the loader passes all module exports
 * as an object plugin). `inject` is a HARD dependency: a missing service stops
 * the whole plugin from loading, so nothing may be listed here that the client
 * does not actually read (issue #86 removed the never-read `workspaces`).
 */
exports.inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions'];
/**
 * How long a remembered scroll position stays usable (see {@link restoreConversationScroll}).
 * The gap between our teardown and re-apply is one dynamic import inside the host's
 * client-modules queue — well under a second in practice; ten is generous.
 */
const SWAP_RESTORE_MS = 10_000;
const scrollWindow = window;
const scrollState = scrollWindow.__dshaMobileScrollState instanceof WeakMap
    ? scrollWindow.__dshaMobileScrollState
    : (scrollWindow.__dshaMobileScrollState = new WeakMap());
// 宿主自己写在会话滚动区上的 [data-conversation-scroll] 打头（2026-11-04 热换后对话弹到顶，内置 3.2.2 同样）：
// effect 按后注册先清理（cordis clear() 反序），frame 标记归后注册的 reconciler 所有、先被摘掉，
// 只靠它的话卸载时 0 命中、什么也没存下。
function conversationScrollers() {
    return [...document.querySelectorAll('[data-conversation-scroll], [data-mobile-nav="frame"] [class*="scrollBody"]')];
}
/**
 * Where the conversation was scrolled to, independent of the element.
 *
 * The per-element WeakMap below only survives a stylesheet swap, where the
 * scroll body keeps its identity. A hot swap that re-renders the tree (the
 * host re-imports the client module) replaces the node, and the frame marker
 * the scroller query keys on is only applied by a reconciler task on the NEXT
 * frame — so the old restore found nothing and the conversation stayed at 0.
 * Field data 2026-10-30 (phone beacon): convTop 5546 -> 0 within one second of
 * a plugin re-apply, with no pointer or key event in between; the owner's
 * 「时不时跳到上文很久以前的内容」 is exactly that.
 */
function rememberConversationScroll() {
    for (const element of conversationScrollers()) {
        if (element.scrollTop > 0) {
            const saved = { top: element.scrollTop, savedAt: Date.now(), url: location.href };
            scrollState.set(element, saved);
            scrollWindow.__dshaMobileLastScroll = saved;
        }
        else
            scrollState.delete(element);
    }
}
function restoreConversationScroll() {
    // Same node (stylesheet-only swap): the cheap path.
    for (const element of conversationScrollers()) {
        const saved = scrollState.get(element);
        scrollState.delete(element);
        if (!saved || Date.now() - saved.savedAt > SWAP_RESTORE_MS || saved.url !== location.href)
            continue;
        requestAnimationFrame(() => {
            if (element.isConnected && saved.url === location.href &&
                Date.now() - saved.savedAt <= SWAP_RESTORE_MS && element.scrollTop === 0)
                element.scrollTop = saved.top;
        });
    }
    // Remounted tree (plugin re-apply): retry until the new scroller exists, has
    // content, and the frame marker is in place. Bounded, and it never fights a
    // scroll the user started — it only writes while the scroller sits at 0.
    const saved = scrollWindow.__dshaMobileLastScroll;
    if (saved === undefined)
        return;
    let tries = 0;
    const attempt = () => {
        tries += 1;
        if (saved.url !== location.href || Date.now() - saved.savedAt > SWAP_RESTORE_MS)
            return;
        const element = document.querySelector('[class*="scrollBody"]');
        if (element !== null && element.scrollTop === 0 && element.scrollHeight >= saved.top + element.clientHeight) {
            element.scrollTop = saved.top;
            return;
        }
        if (tries < 30)
            requestAnimationFrame(attempt);
    };
    requestAnimationFrame(attempt);
}
/**
 * Mobile-adaptive shell, browser half: injects the mobile stylesheet, then
 * contributes the directory toggle to the session header and the backdrop +
 * floating button to the shell overlay.
 * @param ctx - client root context.
 */
function apply(ctx) {
    ctx.effect(() => ctx.locale.register(locales_ts_1.NS, { zh: locales_ts_1.zh, en: locales_ts_1.en }), 'dsh-web-mobile: dictionaries');
    ctx.effect(() => {
        // 先清掉可能残留的同 id 样式表。
        // 2026-09-23：app 的 P8 自愈会把 client.js 换回上游版、页面里也随之挂着
        // **上游那份 CSS**；我们重新部署产物后，只 append 不清旧的话，页面里那份旧表
        // 仍然压在上面（实测：开关/权限图标/头部留白改完"看着没生效"）。
        // 本插件是原地热重载（不整页刷新），所以这一步必须自己保证"页面里的样式表
        // 就是当前产物里的这一份"。
        for (const stale of document.querySelectorAll('style[data-plugin-css="dsh-web-mobile/mobile.css"]')) {
            stale.remove();
        }
        const tag = document.createElement('style');
        tag.dataset.plugin = 'dsh-web-mobile';
        tag.dataset.pluginCss = 'dsh-web-mobile/mobile.css';
        tag.textContent = index_ts_1.MOBILE_CSS;
        document.head.appendChild(tag);
        // Keep this stylesheet last in <head> so its overrides win over the
        // host UI's own styles (some host rules also use !important).
        setTimeout(() => {
            if (tag.isConnected)
                document.head.appendChild(tag);
        }, 0);
        // 热换后的滚动回位（2026-10-07 店主：「聊到一半突然闪到最上面」）。
        // 宿主热换插件走 client-modules.replace()：tearDown → import → refresh，
        // 我们这张表在窗口里会**整个消失**，长会话的 content-visibility 估算与布局
        // 翻转会让 Chromium 把会话滚动区重锚到顶部。卸载前记下位置、重挂后回填。
        restoreConversationScroll();
        return () => {
            rememberConversationScroll();
            tag.remove();
        };
    }, 'dsh-web-mobile: styles');
    // Market「installed」列表行的内联样式：实现与全部注释见 effects/installed-list.ts。
    (0, installed_list_ts_1.installInstalledListRows)(ctx);
    // 宽屏触屏（平板 ≥1024）：只补一颗一键设置齿轮，布局仍交给宿主原生。
    (0, tablet_quick_settings_ts_1.installTabletQuickSettings)(ctx);
    // Leaving a sidebar panel. The host's panels replace the main area and ship
    // no way back, so every exit route (system back, re-tapping the selected
    // panel row, the FAB) shares this one action.
    const panelExit = (0, panel_exit_ts_1.createPanelExit)(ctx.layout);
    // Shared mobile infrastructure: frame marker ownership and the single
    // full-tree reconciler. Installed inside one effect so a plugin reload in
    // the same JS environment tears the whole reconciler down and rebuilds it.
    ctx.effect(() => {
        const stops = [
            (0, phone_chrome_ts_1.installFrameController)(),
            (0, reconciler_adapter_ts_1.installReconciler)(ctx),
            (0, reconciler_adapter_ts_1.registerReconcileTasks)(ctx, panelExit),
            // Last: stop the tasks before releasing the exit instance they drive.
            () => panelExit.dispose(),
        ];
        return () => {
            for (const stop of stops)
                stop();
        };
    }, 'dsh-web-mobile: reconciler infrastructure');
    // Selection handle drag: while a conversation selection is live the header drops
    // out of the hit test, so the native extent stays local instead of snapping to the
    // flow's first item and revealing it (teleporting to the top of the session).
    (0, selection_chrome_ts_1.installSelectionChromeYield)(ctx);
    // Drawer close interactions: Escape and navigation taps inside the drawer.
    (0, drawer_close_ts_1.installOverlayInteractions)(ctx);
    // Sidebar panel exit: re-tapping the already-selected panel row returns to
    // the conversation (the system-back route is a reconciler task; both call the
    // same action).
    (0, panel_exit_ts_1.installPanelRowExit)(ctx, panelExit.exit);
    // Session deletion, injected into each session row's ⋯ menu (beside
    // rename / fork / archive) with a confirm dialog. Mobile-only.
    (0, session_menu_ts_1.installSessionMenuDelete)(ctx);
    // Sidebar swipe gestures: edge swipe-in opens the drawer, content swipe-out
    // closes it (release-classified, zero inline transforms — A 档). Since
    // 2026-09-13 the layer also owns the right-edge files gesture (leftward
    // opens the files panel via openFilesPanel, rightward closes whatever is
    // on top — the panel or the drawer); a leftward stroke never collapses
    // anything.
    (0, sidebar_swipe_ts_1.installSidebarSwipe)(ctx, open_files_panel_ts_1.openFilesPanel);
    // Lineage-count chip: reliable open/close on touch pointers (upstream is
    // hover-timer driven and has no onClick on the count variant).
    (0, subagent_chip_touch_ts_1.installSubagentChipTouch)(ctx);
    // iOS: tapping the composer's send/stop/+ buttons must not re-raise the
    // dismissed keyboard (upstream keepFocus focuses the editor on mousedown).
    (0, composer_keyboard_guard_ts_1.installComposerKeyboardGuard)(ctx);
    // iOS: the host Lexical scroll helper mis-scrolls the window on every
    // keystroke (issue #149); pin the composer seat above the keyboard.
    // 2026-10-31: 键盘抬升不武装。帧数据显示它和引擎自己的滚动纠正互相拉扯
    // （seat 578→283→560，conv 8572↔8587 每帧来回），而引擎单独工作时座位是落在
    // 可见带内的。实现原样保留在 effects/composer-keyboard-lift.ts，改回一行即可重开。
    void composer_keyboard_lift_ts_1.installComposerKeyboardLift;
    // Keyboard-shrunken landscape (issue #245): the sticky composer seat's editing
    // row lands inside the opaque, z-index-30 conversation header, so the input
    // box is covered until the page reloads. Measure the band and let the CSS
    // outrank the header while it is covered.
    (0, composer_clearance_ts_1.installComposerClearance)(ctx);
    (0, document_scroll_lock_ts_1.installDocumentScrollLock)(ctx);
    // Ask-question card (owner report 2026-10-25): a manual expand/collapse control
    // in the card header turns the answer area into the whole visible band
    // (the host's 60vh ceiling collapses with the keyboard), and the answer field
    // is scrolled back into the card body when it is covered.
    (0, question_fullscreen_ts_1.installQuestionFullscreen)(ctx);
    (0, composer_plus_toggle_ts_1.installComposerPlusToggle)(ctx);
    // Hero workspace chip: the host's picker portaled its Menu with
    // `anchor={null}`, so its own outside-pointerdown close eats the trigger's
    // tap and the chip's toggle re-opens it. Swallow that one click.
    (0, workspace_chip_toggle_ts_1.installWorkspaceChipToggle)(ctx);
    // Agent Team chip: the host trigger only opens (its onClick focuses the panel
    // when open, never toggles), so a second tap could not close it. Dispatch the
    // outside pointerdown its own dismiss hook waits for, then swallow the click.
    (0, team_chip_toggle_ts_1.installTeamChipToggle)(ctx);
    // Model/reasoning menu portals to <body>; the CSS centering rule died with the
    // portal move, so re-anchor it on the trigger here (owner report: opens far left).
    (0, model_menu_anchor_ts_1.installModelMenuAnchor)(ctx);
    // Shortcut modal (settings → 通用设置 → 快捷键): the host focuses its search
    // field on open, which raises the soft keyboard over a page the user came to
    // EDIT, and the keyboard shrinking the viewport resizes the sheet (owner
    // report: 「打开的时候还是会闪，而且还会唤起键盘」).
    (0, shortcut_modal_keyboard_guard_ts_1.installShortcutModalKeyboardGuard)(ctx);
    // Model / reasoning-level menu (owner report 2026-10-07): drilling into the
    // model pane focuses the host's 「搜索模型…」 field from a passive effect, so the
    // keyboard covers the list the user just opened. Same method-shadow cure,
    // armed from the capture-phase tap that precedes the pane switch.
    (0, model_menu_keyboard_guard_ts_1.installModelMenuKeyboardGuard)(ctx);
    (0, plugin_card_tap_ts_1.installPluginCardTap)(ctx);
    // Multi-line paste after an IME commit keeps only the first line (host
    // Lexical routes Android's insertText paste through the text-insertion
    // command). Re-dispatch those as a paste event; mobile-only.
    (0, composer_paste_guard_ts_1.installComposerPasteGuard)(ctx);
    // Soft-keyboard Enter inserts a newline instead of sending (owner 2026-11-05):
    // phone keyboards have no Shift, so the host's Enter-to-send left no way to
    // break a line. Send stays on the button; Ctrl/Cmd+Enter still sends.
    (0, composer_enter_newline_ts_1.installComposerEnterNewline)(ctx);
    // Only a real tap on a text field may raise the soft keyboard (owner
    // 2026-11-05): every host flow that focuses a field programmatically (mount
    // autoFocus, returning from a subpage, switching tabs) otherwise pops the IME
    // over the page.
    (0, focus_gesture_guard_ts_1.installFocusGestureGuard)(ctx);
    // Composer paperclip (owner report 2026-10-06): the system file picker is the
    // wrong shape and cannot pick "images only", so the tap opens the plugin's own
    // two-option sheet (上传图片 / 上传附件) and hands the choice back to the host's
    // hidden input. Intake stays fully host-owned.
    (0, composer_file_picker_ts_1.installComposerFilePicker)(ctx);
    // @ menu 「本次附件」: mention the composer's draft attachments as locked chips.
    (0, attachment_mention_ts_1.installAttachmentMention)(ctx);
    (0, phone_chrome_ts_1.installPhoneChrome)(ctx);
    (0, aionui_compat_ts_1.installAionuiCompat)(ctx);
    // Mobile file sharing: Files-tree row buttons + preview header button
    // (official 0.2.0 slots), share sheet with download fallback.
    (0, file_share_ts_1.installFileShare)(ctx);
    // Debug badge (?mobile-nav-debug=1): live state overlay for phone-side
    // repros. No-op without the query param (docs: README, AGENTS.md).
    (0, debug_ts_1.installDebugBadge)(ctx);
    ctx.slots.inject('conversation.session.header.actions', () => ctx.slots.register({
        name: 'conversation.session.header.actions',
        id: 'mobile-nav-toggle',
        order: 10,
        locale: locales_ts_1.NS,
        inject: () => ({
            toggleSidebar: () => (0, drawer_close_ts_1.toggleDrawer)(ctx),
        }),
    }, MobileNavToggle_tsx_1.MobileNavToggle));
    // Session log download lives in the drawer brand row as an icon (issue #225):
    // the reconciler task in effects/drawer-session-log.ts injects it there, so
    // the drawer footer no longer carries a plugin row.
    // Composer file entry (0.1.6 host): the host deleted the paperclip attach
    // button, leaving the 「文件」row inside the "+" listbox as the only file
    // entry. Re-add a permanent one in the host's own conversation.input.left
    // seat (inside the tools lane, beside the plus button). It triggers the
    // host's hidden input[type=file] — the same fileInputRef.current.click()
    // the host's own command runs — so intake validation, upload and the
    // availability policy stay host-owned.
    ctx.slots.inject('conversation.input.left', () => ctx.slots.register({
        name: 'conversation.input.left',
        id: 'mobile-nav-file-upload',
        order: 10,
        locale: locales_ts_1.NS,
        inject: () => ({}),
    }, ComposerFileButton_tsx_1.ComposerFileButton));
}
};
var __cache = {};
function __localRequire(id) {
  if (id.charCodeAt(0) !== 46) return require(id);
  id = id.slice(2);
  var cached = __cache[id];
  if (cached) return cached.exports;
  var module = { exports: {} };
  __cache[id] = module;
  __modules[id](__localRequire, module, module.exports);
  return module.exports;
}
var module = { exports: {} };
__modules["index.js"](__localRequire, module, module.exports);
return module.exports; } });
