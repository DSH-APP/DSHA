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
exports.IconPaperclip = pickIcon(['IconPaperclipOutlineRegular', 'IconPaperclipOutline16']);
/** 会话头部的目录抽屉开关。 */
exports.IconPanelLeft = pickIcon(['IconPanelLeftOutlineRegular', 'IconPanelLeftOutline16']);
/** 会话头部的 Files/右侧栏入口。 */
exports.IconFolderOpen = pickIcon(['IconFolderOpenOutlineRegular', 'IconFolderOpenOutline16']);
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
    const closer = doc.querySelector(exports.HOST_FILES_CLOSER);
    const opener = doc.querySelector(exports.HOST_FILES_OPENER);
    const hostControl = typeof opener?.click === 'function' ? opener : closer;
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
 * session seat also disables the control. Hidden entirely on wide screens
 * (CSS media query, and the shared desktop hide block in misc.css.ts).
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
/* ---------- base control styles (rendered at any width, hidden where unused) ---------- */

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
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="toggle"]:hover,
[data-mobile-nav="files"]:hover,
[data-mobile-nav="toggle"]:active,
[data-mobile-nav="files"]:active {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="toggle"]:focus-visible,
[data-mobile-nav="files"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 1px;
}

/* Drawer brand-row session-log icon (issue #225): the plugin-owned button is
   injected into the host's logo row by effects/drawer-session-log.ts, so it
   sizes as a compact icon control instead of the old footer pill. The host
   brand button is flex:1 and gives up exactly this width. The wrapper keeps the
   button out of the row's direct-button set: the third-party dsh-web-all layer
   adopts that row's last direct button as its sidebar toggle (see
   effects/drawer-session-log.ts#mount). */
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
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="session-log"]:hover:not(:disabled),
[data-mobile-nav="session-log"]:active:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="session-log"]:disabled {
  color: var(--dsw-alias-label-dimmed, rgba(0, 0, 0, .35));
  cursor: default;
  background: transparent;
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

/* Centered frosted-glass modal for the delete confirm / error card: the
   backdrop is a flex positioning container (centering + 16px inset padding)
   and the card rides inside it as a static child (session-menu.ts appends
   the card INTO the backdrop for exactly this reason). Look baseline = the
   host ⋯ menu's portal root, measured 2026-09-24: translucent
   rgba(248,249,250,.58) fill with blur(40px) saturate(1.5) frosted glass,
   16px radius, hairline + soft shadow. Geometry baseline = the host Dialog,
   measured the same day: centered modal, 16px/500 title, 36px pill buttons,
   solid-fill primary. [hidden] keeps the error line out of layout until a
   failure lands. */
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
  [data-mobile-nav="delete-dialog"],
  [data-mobile-nav="file-picker-backdrop"],
  [data-mobile-nav="file-picker"] {
    animation: none !important;
  }
}

/* ---------- composer file picker (effects/composer-file-picker.ts) ----------
   手机档回形针入口的两选项浮层（上传图片 / 上传附件）。宿主在 0.1.6 删掉了自带的
   附件按钮，只剩 composer 里一个隐藏 input，直接点它只会弹系统文件选择器 ——
   形态不可控、也没有「只挑图片」这条路（2026-10-06 报障）。
   形态（同日店主反馈「这个 UI 太丑了，缩小点，不要从底部弹出」）：**贴锚点的小浮层**，
   不是整宽底部弹层 —— 宽度贴着内容（「width: max-content」，上限 min(78vw, 240px)），行高 34px、
   行间距 0（2026-10-07 店主：「之间的距离更紧致一点」+「右边的留白有点多了，往左边缩点」），字号 14px。
   定位用「position: fixed」，top/left 由 JS 按回形针 rect 算（上方优先，夹进视口 8px
   内边距，且**视口一变就重算** —— 键盘收起把整体下移一个键盘高时浮层必须跟着锚点走，
   否则会停在会话中部，2026-10-07 真机报障），所以这里只写盒子、不写定位与整宽。
   没有「取消」行：点浮层外或按返回键关闭（外层透明遮罩只负责收点击）。
   挂 document.body（与上面的 delete-dialog 同一取舍：挂在 frame 里会被第三方
   dismiss shim 的捕获链吞点击），z 在移动档块里抬到 1400。 */
[data-mobile-nav="file-picker-backdrop"] {
  position: fixed;
  inset: 0;
  z-index: 55;
  background: transparent;
}
[data-mobile-nav="file-picker"] {
  position: fixed;
  box-sizing: border-box;
  /* 宽度贴着内容（2026-10-07 店主：「右边的留白有点多了，往左边缩点」）：
     两行都是「图标 + 两字标签」，固定 168px 起步会在右侧留一大块空白。 */
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

/* ---------- popover band above the open drawer (mobile only) ----------
   The host portals its menus to <body> as position: fixed with z-index 1100,
   while the drawer column carries 1300 and our backdrop 1250. A menu opened
   from inside the drawer therefore painted UNDER both: measured 2026-09-14 at
   390px on the session row's ⋯ menu — menu rect [160,454,218,168] z1100, and
   elementFromPoint at its centre AND at both of its ends returned drawer
   elements, so the whole menu was unreachable and the row could not be
   renamed/forked/archived/deleted from the phone (the second half of the
   owner's report: the popup the ⋯ opens is pressed under the drawer).
   The raise is gated on the open drawer: our backdrop makes that the only
   state in which a menu can be opened from the drawer, so the closed-drawer
   and desktop stacks keep the host's own ordering.
   The plugin's own confirm card sits in the same band: it mounts on
   document.body (NOT in the frame — see session-menu.ts, which appends the
   backdrop and the card there so the third-party dismiss shim's capture-phase
   click chain cannot swallow its buttons) and carried the base z 55/56, so the
   drawer covered its left 272px (measured: elementFromPoint inside that band hit
   the drawer's own button, and the confirm card is 358px wide starting at x=8). */
@media (max-width: 1023px) and (pointer: coarse) {
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed])) [role="menu"] {
    z-index: 1400 !important;
  }
  /* Host modal dialogs (workspace rename, and any future dialog of the same
     shape) portal to a direct body child that carries the stacking context:
     body > div { position: fixed; z-index: 1000 } wrapping
     [role="dialog"][aria-modal="true"] (inner z-index: 1 — raising the dialog
     itself is useless, it only sorts inside that root).
     NOTE (2026-09-23): the hashes once recorded here (_root_w1urq_2 /
     _dialog_w1urq_22) are gone from both 0.1.7-alpha.1 and alpha.2, so this
     comment anchors on the SHAPE only — which is what the rule below already
     matches. Do not reintroduce a hash here without re-measuring.
     Measured 2026-09-19: with the drawer open (column z 1300) the workspace
     Rename dialog sat entirely under it and needed the drawer closed first.
     Raise the portal root, not the dialog.
     GATE (2026-09-25, real device): the gate is OUR BACKDROP'S PRESENCE, not
     the drawer-open marker. Marker and paint disagree for the whole close
     transition — the backdrop fades over .2s and is removed 260ms after the
     marker flips (overlay-backdrop-fab.ts), the column transitions .28s
     (layout.css.ts) and React swaps the pane subtree ~200ms late — so a
     marker-gated raise went dark inside that window and the drawer band
     covered any open modal. Measured on the reporter's phone (Android 16
     WebView) with the shortcut modal open: forcing data-sidebar-collapsed
     dropped this root 1400 -> 1000 and made elementsFromPoint(0.85w, .30h)
     return [data-mobile-nav="backdrop"] — rgba(0,0,0,.45) over the modal's
     white = luminance 141, matching the reporter's recording (140 behind a
     280px drawer edge). That is the "快捷键弹层抽搐/闪" report: a ~200-280ms
     dark frame with the drawer over the shortcut modal, not a compositing
     tear. The backdrop's presence IS the drawing condition, so gating on it
     has no such window; with no backdrop the host's own ordering stands (a
     menu opened inside a modal still sorts above it). Our own delete backdrop
     matches this rule too since the 2026-09-24 centered rework (its direct
     child card carries role=dialog) — harmlessly: it sets the same 1400 the
     dedicated rule below sets. */
  body:has([data-mobile-nav="backdrop"])
    > div:has(> [role="dialog"][aria-modal="true"]) {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog-backdrop"] {
    z-index: 1400 !important;
  }
  /* 同一个带里：回形针弹层也要压过抽屉（z 1300）与抽屉遮罩（1250）。 */
  [data-mobile-nav="file-picker-backdrop"] {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog"] {
    z-index: 1401 !important;
  }
  /* dsh-usage-stats portals its panel to <body> as position: fixed with
     z-index 100 (desktop-designed; the drawer does not exist there). With our
     drawer open (column z 1300) the「用量/余额」panel sat under it: measured
     2026-09-20 at 390px — panel rect [12,91,366,625] z100 vs drawer 280px wide
     z1300, and elementFromPoint at the panel's centre AND its left corners
     returned drawer elements, so only a ~98px strip on the right stayed
     reachable. The plugin also unmounts the panel when the drawer closes
     (Escape-linked dismissal) and the badge only renders in the open drawer,
     so the open-drawer gate covers the panel's only reachable state; the
     closed-drawer and desktop stacks keep the plugin's own ordering. */
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [data-usage-stats-panel] {
    z-index: 1400 !important;
  }
  /* AppFrame overlayLayer band — the class self-fix of our own layering
     contract (NOT a per-plugin adaptation): plugin and host sheets portal
     INTO the AppFrame overlay layer (position: absolute; z-index 20 — its
     own stacking context), so no z-index on a sheet itself can out-rank the
     drawer column's 1300 in the root context, and every sheet opened from
     the drawer landed behind it. Measured 2026-09-22 at 390px with the
     drawer open: a probe sheet inside the layer stayed hit-blocked by
     drawer elements at z auto AND z 9999 alike, and only raising the layer
     ROOT revealed it (elementFromPoint). Raise the stacking root, not the
     children — the same shape as the dialog portal-root raise above. The
     body-ported bands keep their own raises (menus 1100, dialog root 1000,
     usage-stats panel 100): each is a per-surface adaptation patch for a
     portal OUTSIDE this layer. Closed-drawer and desktop stacks keep the
     host's own ordering. */
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [class*="_overlayLayer"] {
    z-index: 1400 !important;
  }
  /* 0.1.7 fullscreen sidebar panels (the sidebar terminal / files / preview /
     browser tabs) state the dockkit cell at z 40 — the host sets
     --dsh-dockkit-dock-layer: 40 on .panel[data-sidebar-right-panel=fullscreen]
     — which outranks the
     host's own overlay layer (20) and our FAB (21). Measured 2026-09-23 at
     390px with the terminal panel open: elementFromPoint at the FAB's centre
     returned a panel child, and a real tap on it left the drawer closed — the
     phone lost its only way back to navigation (the FAB is the screen's only
     control once a panel owns the main area, see overlay-backdrop-fab.ts).
     Raise OUR two surfaces for that state, the same "raise the root, not the
     children" shape as above; the FAB stays in the below-the-drawer band (55)
     so an open drawer keeps covering it. Gate on the open attribute: the
     presentation attribute alone survives a closed panel. Closed-panel,
     docked-panel (dock layer 10) and desktop stacks keep the host's order. */
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"]) [data-mobile-nav="fab"] {
    z-index: 55 !important;
  }
  body:has([data-sidebar-right-open][data-sidebar-right-panel="fullscreen"])
    [class*="_overlayLayer"] {
    z-index: 1400 !important;
  }
}

/* ---------- touch press feedback (mobile + hover: none only) ----------
   Android WebView paints its own translucent blue tap highlight
   (-webkit-tap-highlight-color default) over every tapped control. The
   property is inherited and no host package sets it on descendants (grep of
   dsh-client-ui-* bundles, 2026-10), so transparent on html/body clears it
   page-wide; the explicit transparent values on our own controls above stay
   as the desktop-width fallback. In its place the UI gives its own pressed
   state: an inset box-shadow tint (paint-only, no layout, does not fight a
   host background) in the host's interactive-bg-active colour, which the
   theme defines for both light and dark. Every selector sits in :where(), so
   (0,0,0) loses to any host or plugin rule that styles the element itself —
   aria-selected / aria-pressed / own :active states keep their look — and
   :focus-visible outlines are untouched. Desktop and narrow mouse windows
   never enter this block (MOBILE_QUERY + hover: none). */
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
      box-shadow: inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active, color-mix(in srgb, currentColor 8%, transparent));
    }
    /* Icon-only buttons (a lone svg child, or a button that carries only an
       aria-label) also shrink a touch. */
    :where(button:has(> svg:only-child), button[aria-label]:not(:has(> span, > div))):where(:active):where(:not(:disabled, [aria-disabled="true"])) {
      transform: scale(.96);
    }
    /* 例外（2026-10-20，店主）：头部那两颗芯片不要按压反馈。
       症状：点「智能体团队」图标开面板、再点它关闭时，图标周围闪一块浅灰
       （:active 的 inset 填充 + 图标缩到 .96）；「子智能体 / N 个子代理」芯片同样。
       为什么要去掉：这两颗**开启态本来就有宿主自己的反馈** —— 团队图标打开时变粗，
       子智能体芯片右侧有 chevron；按压灰圈和它们说的是同一件事，等于两份反馈叠在
       一起，功能重复。加上两颗平时没有底色，整块浮灰在浅色壁纸上像一块脏。所以
       只给它们清掉，其它按钮（抽屉行、菜单项、标签页、回形针等）的按压反馈原样保留。
       两个标记都是宿主稳定的 data-*（不是哈希类）；宿主换标记时本块惰性失效，
       届时按新标记改选择器即可，不必连带改上面两条通用规则。 */
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

/* DSHA_BACKDROP_PRESS_V1: a dimming surface must not become a bright pressed button. */
@media (max-width: 1023px) and (pointer: coarse) and (hover: none) {
  [data-mobile-nav="backdrop"]:active {
    box-shadow: none;
  }
}

/* Floating fallback button (hero / blank phases without a session header).
   Top aligns with the session header's toggle row (that row sits 12px below
   the frame's safe-area padding); when the client has set viewport-fit=cover
   the safe-area inset moves it below the notch too. */
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
[data-mobile-nav="fab"]:hover {
  background: var(--dsw-alias-button-floating-hover, rgba(0, 0, 0, .08));
}
[data-mobile-nav="fab"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 2px;
}

/* Dimmed backdrop under the open drawer; above every column, below the drawer.
   z 1250: 0.1.5 pins its native sidebarCol at z-index:1100 and paints mid
   layers up to that band; the backdrop must sit above the host stack
   (below the drawer's 1300) so the dim covers the content area on every
   host generation. Keep in sync with the drawer z in layout.css.ts. */
[data-mobile-nav="backdrop"] {
  position: absolute;
  inset: 0;
  z-index: 1250;
  background: rgba(0, 0, 0, .45);
  cursor: pointer;
  animation: dsh-web-mobile-fade .2s var(--ds-ease-in-out, ease-in-out);
  /* Fade-out twin of the mount animation: the task eases the dimming away
     (inline opacity 0 + pointer-events none) and removes the element after
     the fade. Also used by the gesture layer so the backdrop fades in step
     with a close-follow commit's slide-out. */
  transition: opacity .2s var(--ds-ease-in-out, ease-in-out);
  -webkit-tap-highlight-color: transparent;
}
@keyframes dsh-web-mobile-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  [data-mobile-nav="backdrop"] {
    animation: none !important;
    transition: none !important;
  }
}
/* Settings sheet entrance: the official dialog mounts with no animation at
   all, so it snaps in. A slight rise/scale reads as a proper sheet.
   No opacity arm (issue #124, 2026-09-25): checker scene 4 screencast caught
   the fade double-exposing the still-open drawer underneath the panel
   (frame a005) — sliding in fully opaque keeps the motion, drops the bleed. */
@keyframes dsh-web-mobile-sheet-in {
  from {
    transform: translateY(14px) scale(.98);
  }
  to {
    transform: none;
  }
}
/* Preview sheet rise: the aionui preview column opens as a bottom sheet. */
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
exports.LAYOUT_CSS = `/* ---------- mobile-only layout (narrow viewport AND touch-primary pointer) ---------- */

@media (max-width: 1023px) and (pointer: coarse) {
  /* --- Phone chrome ---
     The system status bar stays visible (no fullscreen). Three adjustments
     make it behave:
     - touch-action: pan-y pinch-zoom kills double-tap-to-zoom (and the 300ms
       tap delay) while keeping vertical pan. Omitting pan-x (i.e. not using
       the manipulation alias) forbids HORIZONTAL pan on the root: a
       left-edge horizontal drag would otherwise be claimed by the browser as
       a pan (firing pointercancel) before the sidebar swipe layer can
       classify it. touch-action does not inherit and the behavior
       intersection stops at the first scroll container, so only touches
       landing directly on the root background are affected — inner
       horizontal scrolling of content containers is untouched. pinch-zoom is
       listed on purpose (#45): a bare pan-y also drops pinch, and then a
       zoom the browser applied by itself — iOS enlarges the viewport when a
       field under 16px takes focus — can no longer be undone by the user,
       so the app stays magnified until it is reopened or rotated. Two-finger
       zoom is also the WCAG 1.4.4 escape hatch and costs the gesture layer
       nothing: pinch is not a horizontal pan.
     - overscroll-behavior-x: none suppresses the browser's edge history
       navigation on the root scroller — Android Chrome claims a horizontal
       stroke that STARTS within its edge band (EDGE_WIDTH_DP=48dp,
       NavigationHandler.java) and navigates BACK, the exact gesture that
       opens the drawer ("页面直接返回上一页", 2026-08-29 user report). Only
       html/body count for this (Chromium issue 41483088: inner containers
       are ignored by the navigation path). iOS Safari's edge back-swipe has
       no CSS opt-out (WebKit bug 240183) — there the widened gesture start
       zone (START_ZONE_RATIO 0.45 of the viewport width, ~176px at 390px,
       past every browser's edge-claim strip) is the mitigation.
     - With the client's viewport-fit=cover, env(safe-area-inset-top) is the
       status bar / notch height; the rules below push the app content below
       it so the status bar never covers anything. Off notched phones (or in
       a normal browser tab where the layout viewport already sits below the
       status bar) the inset is 0 and nothing shifts. */
  html,
  body {
    touch-action: pan-y pinch-zoom !important;
    overscroll-behavior-x: none !important;
  }

  /* AppFrame: the drawer takes the sidebar column out of grid flow, so the
     remaining in-flow items (center, details) land in tracks 1..2: give the
     center every pixel and keep the details track at zero. The top padding
     clears the status bar / notch for every in-flow surface (session header,
     messages, composer); the absolutely-positioned drawer is unaffected (its
     containing block is the frame's padding box, i.e. still the frame top).
     box-sizing MUST be border-box: the official frame is height:100% of a
     100%-height body, and it is content-box by default, so the safe-area
     padding is ADDED on top of the full viewport height. The frame then grows
     to 100% + inset, the document itself becomes scrollable by exactly the
     inset, and the sticky composer seat (bottom:0 of the scroll body) lands
     below the visual viewport. Symptoms on a notched phone: the whole UI can
     be swiped up, the composer lifts off the bottom leaving a blank strip,
     and the newest message sits under the composer because the host's
     at-bottom follow scrolls its own scroll body, not the document. With
     border-box the padding is taken out of the 100% height instead, so the
     frame is exactly one viewport tall and the document never scrolls.

     The leading html element selector is load-bearing, not decoration: the
     third-party @linxin666/dsh-web-all sheet ships an equal-specificity
     !important grid-template-columns for this same element under
     (max-width: 768px), so without the extra element the winner is decided by
     which sheet happens to be injected later. Measured before and after with
     scripts/probes/cascade-conflict-probe.mjs: no computed value moves, the
     rule only stops depending on sheet order (audit D-5 option A). */
  html [data-mobile-nav="frame"] {
    box-sizing: border-box !important;
    position: relative !important;
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
    padding-top: env(safe-area-inset-top, 0px) !important;
    /* Bottom safe area, symmetric with the top inset above. It MUST stay in
       this rule: box-sizing: border-box is the pair that makes the padding eat
       into the one-viewport height instead of adding scrollable overflow
       (pitfalls §safe-area — padding without border-box pushes the composer
       seat below the fold).
       Why we have to own it: the third-party @linxin666 sheet (the same one
       the grid-template-columns note above is about) ships this padding only
       under (max-width: 768px), and the host ships none at all, so 769-1023px
       touch devices had no bottom inset from either side and their content sat
       in the system-bar band (issue #193). In the <=768px band that sheet's
       declaration and this one are the same property with the same value, so
       they do not stack. */
    padding-bottom: env(safe-area-inset-bottom, 0px) !important;
  }

  /* The sidebar column (first grid child) becomes a left drawer. The drawer
     hugs the sidebar content exactly (the wide sidebar carries an inline
     width, ~280px): a fixed 92vw box would leave a white strip where the
     container background shows beside the content.
     Closed state: translateX(-110%) — more than -100% of the max-content
     width — guarantees the whole drawer (and its shadow, had it one) leaves
     the viewport. A mere -100% leaves a sliver on screen; -105% (as used
     before) left 14px of the drawer plus a long 32px-blur shadow gradient
     visible along the left edge of the main UI. No box-shadow at all: the
     dimmed backdrop already separates drawer from content. */
  /* These legacy column rules stay armed on every host generation: the phone
     owner prefers this drawer over the official overlay one (2026-09-13). The
     host's own drawer ships NO full-screen backdrop, so the conversation beside
     it stays hit-testable - the rejection reason.
     Layering contract: this column is 1300 and the backdrop 1250 (base.css),
     both deliberately above the host's native sidebarCol at 1100. The earlier
     value of 40 sat BELOW that 1100: because this same rule also forces
     position/inset/width on the element, the column kept a correct-looking box
     while neither painting nor hit-testing, which is the "all black, click
     anywhere closes" root cause. The backdrop we append carries the dimming. */
  [data-mobile-nav="frame"] > :first-child {
    position: absolute !important;
    inset: 0 auto 0 0 !important;
    /* !important is load-bearing: the host ships
       [data-dsh-frame] [data-pane="sidebar"] { width: min(88vw, 320px) !important }
       under (max-width: 768px), which at 390px resolves to a flat 320px and
       BEATS a plain declaration here - measured: our max-content never applied
       and the column stayed 320px.
       280 is the drawer's hard floor, measured by sweeping the column width from
       304 down to 264: the inner surface is a FIXED 280px box and never
       reflows, so every pixel below 280 is simply clipped off its right edge
       (the list stays 270px at every width and its right edge sits at 278, so
       270 and below cut into the list itself). At exactly 280 the panel is fully
       intact - only the 12px of its right-hand padding is given up - which is
       what the owner asked for over the previous 304. Going narrower is a
       one-line change, but it starts eating content. */
    width: min(88vw, 280px) !important;
    /* 1300 is a contract with base.css: the host pins its native sidebarCol at
       z-index:1100 and paints its mid layers up to that band, so the drawer must
       sit above the host stack AND above our own backdrop at 1250 (which dims
       the content area). At 40 the backdrop covered the drawer itself, so
       opening it showed a full-screen dim with no drawer (measured 2026-09-13
       at 390px: backdrop [0,0,390,844] z1250 over column [0,0,320,844] z40, and
       elementFromPoint(40,300) returned the backdrop). Keep in sync with the
       backdrop z in base.css. */
    z-index: 1300 !important;
    transform: translateX(-110%);
    transition: transform .28s var(--ds-ease-in-out, ease-in-out);
    /* Keep the drawer's own content below the status bar / notch: the drawer
       spans the full frame height (its absolute containing block is the
       frame's padding box, so the frame's own safe-area padding does NOT
       reach it). The drawer background paints the status-bar strip, which
       the client's theme-color meta matches, so the strip reads seamless. */
    padding-top: env(safe-area-inset-top, 0px) !important;
    /* Kill the official sidebarCol right border: with the backdrop the edge
       reads cleanly, and the settings dialog (width:100% of this box) stays
       pixel-flush with the drawer. */
    border-right: none !important;

    /* The drawer's inner surface is 280px wide while the column is 88vw/320px, so
     the remaining 40px showed our own column background as a vertical strip
     along the right edge (measured: content right edge 280, column 320; the
     owner reported a white bar). The inner surface owns that band instead, so
     the strip is filled by the drawer's real surface colour.
     That scenario cannot recur as the layout stands: the column is
     min(88vw, 280px), never wider than the 280px inner surface, so the
     inequality that produced the band (column > surface) no longer holds at any
     viewport width (measured 430/390/340/320 = flush at 280, 300 -> 264,
     280 -> 246; a narrower column just crops the surface). The only change that
     would bring it back is raising the column's upper bound past 280px, or the
     host narrowing the inner surface below it. */
    /* The 40px band is a STACKING result, not a colour one: the drawer's inner
     surface is only 280px wide (host markup), while our column is 320px and
     carries z-index 1300 - so the column's own background paints OVER the
     surface's right 40px. Pixel-verified from a screenshot with the drawer open:
     x=10..270 rgb(249,250,251) (the surface) against x=285..315 rgb(255,255,255)
     (our white column). Repainting the column with the surface's own value makes
     the seam invisible.
     The value pinned here is the host's own drawer-fill token - the one
     .pI_x6G_sidebarCol paints with: --dsw-specific-sidebar-fill, i.e.
     --dsw-static-neutral-bluish-50 (#f9fafb) in light and
     --dsw-static-neutral-bluish-900 (#1b1b1c) in dark. Pinning it, rather than
     letting the host rule paint, keeps the band covered without depending on
     cascade order. The fallback is deliberately the token's LIGHT value: if the
     host ever renames this internal token the declaration degrades to today's
     light behaviour instead of going unset, so it cannot get worse. Do not
     "simplify" this back to --dsw-alias-bg-surface: that name is defined nowhere
     in the host, which froze this rule on its fallback and painted the drawer
     light in the dark theme (issue #192). */
    background: var(--dsw-specific-sidebar-fill, #f9fafb);
    /* Drawer swipe gestures (edge swipe-in / content swipe-out, see
     docs/specs/2026-08-27-sidebar-swipe-gestures.md).
     One rule is load-bearing for the gesture layer: dropping pan-x on the
     drawer lets horizontal pointermove events reach the gesture code —
     WITHOUT it the browser treats a horizontal stroke as a pan, fires
     pointercancel and the gesture never classifies (vertical panning stays
     intact). Start-hit is decided purely by geometry on the document
     capture listener (START_ZONE_RATIO = 0.45 of the viewport width, ~176px
     at 390px); there is no hotspot element (removed per audit C2,
     2026-08-27). pinch-zoom rides along with the
     root value so a browser-applied zoom stays undoable inside the drawer
     too (#45); touch-action intersects down the ancestor chain, so a bare
     pan-y here would cancel the root's pinch permission. */
    touch-action: pan-y pinch-zoom !important;
  }

  /* 壁纸兼容（issue #228）：deepseek-harness-background 0.6.x 在
     body[data-dsh-bg] 上把 --dsw-specific-sidebar-fill 置为 transparent（body 内联），
     而抽屉列以该令牌为唯一底色；令牌存在时 var() 的 fallback 不会生效，整列
     于是透出壁纸。宿主内表面（sidebarCol）用同一令牌，也透明，没有第二层
     填充源。壁纸启用时按该令牌自身的两档静态值补回原色：浅色
     --dsw-static-neutral-bluish-50、深色 --dsw-static-neutral-bluish-900。
     这两个静态令牌在明暗两套里取值相同，所以深色必须单独一条。
     规则只在 body[data-dsh-bg] 下命中，无壁纸时完全不参与。 */
  /* @patch
     id: wallpaper-drawer-fill
     for: deepseek-harness-background 0.6.x 的 body[data-dsh-bg]（壁纸启用时把 --dsw-specific-sidebar-fill 置为 transparent）
     why: 抽屉列的唯一底色就是该令牌，第三方插件把它清空后整列透出壁纸；宿主内表面同样透明，没有其它填充源
     until: 上游提供不透明的面板填充接缝，或不再把 --dsw-specific-sidebar-fill 置空——届时本块删除
     test: tests/wallpaper-surface-fill.test.ts
  */
  body[data-dsh-bg] [data-mobile-nav="frame"] > :first-child {
    background: var(--dsw-static-neutral-bluish-50, #f9fafb);
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] > :first-child {
    background: var(--dsw-static-neutral-bluish-900, #1b1b1c);
  }

  /* Closed slot, at the host's OWN specificity. 0.1.5 added a narrow-branch
     rule [data-dsh-frame][data-sidebar-collapsed] [data-pane="sidebar"]
     { width:52px !important; transform:none; pointer-events:none;
     background:transparent !important } - specificity (0,3,0), one class above
     the rule above, so it won BOTH width and transform: the closed drawer
     stayed a 52px transparent shell at x=0 and the only state delta left was
     the width (52<->280), which "transition: transform" cannot animate.
     Measured 2026-09-17: closed pane transform:none / width:52 /
     rect [0,0,52,844], and every frame sampled across a toggle click stayed
     transform:none - the owner's "no slide animation on click" report.
     Matching that specificity (plus !important, since the host declaration is
     important) restores the design's own slot (spec 2026-08-27, drawer DOM):
     a min(88vw, 280px) column translated -110% of its own width, i.e. -308px
     at 390px. The gesture layer never depended on this rule - it writes an
     inline transform !important - so only the CSS-driven click paths regressed. */
  [data-mobile-nav="frame"][data-sidebar-collapsed] > :first-child {
    width: min(88vw, 280px) !important;
    transform: translateX(-110%) !important;
  }

  /* Expanded state (frame without data-sidebar-collapsed) slides the drawer in.
     The open state must be transform:none — NOT translateX(0): an identity
     transform still makes the drawer the containing block for fixed-position
     descendants (the settings dialog's .VOzbGW_overlay is portaled into the
     sidebar DOM). With the identity transform the wide settings sheet
     (100vw-16) overflows the 280px drawer, the dialog's focus scrolls the
     overflow:hidden drawer to scrollLeft=102, and every static child (plus the
     fixed overlay) shifts 102px off-screen. With transform:none the overlay is
     viewport-anchored: it dims the full screen and the sheet sits at left:8. */
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) > :first-child {
    transform: none !important;
  }


  /* The host's own drawer handle. It renders the branded fish glyph (a 24x17
     path in a 23.16x17.04 viewBox) and the phone owner reads it as a stray
     "whale" sitting at the very top-left of the header: measured [10,14,44,44]
     against our own toggle at [8,12,28,28], i.e. the two overlap in the same
     corner. It also duplicates what our toggle already does, so on the mobile
     branch it is removed. The selector keys on the host's own label - the
     element carries no distinguishing class (hHd-Xa_iconButton is shared with
     every other icon button, and the label flips to "Collapse sidebar" when the
     drawer is open, which is why the attribute prefix matches both states and
     both get removed). Nothing in this plugin queries that element; the drawer
     still opens from our toggle, the edge swipe, and closes by tapping the
     backdrop or swiping it away. */
  /* Two selectors and both are needed. The competitor is NOT the host's own
     CSS: @linxin666/dsh-web-all injects, under (max-width: 768px),
     [data-dsh-frame][data-sidebar-collapsed] [data-pane="sidebar"]
     [data-dsh-responsive-part="sidebar-toggle"] { pointer-events: auto;
     display: inline-flex !important }. That is 4 attribute selectors AND
     !important - exactly what the first selector below is - so this is NOT an
     out-specify, it is a TIE decided by sheet order, and it holds only because
     our sheet is injected after theirs. Measured twice, not inferred:
     scripts/probes/cascade-conflict-probe.mjs reports both sides imp=true at
     (0,4,0) and lists this as a reviewed order-tie; if the injection order
     flips, the dismiss shadow returns as a visible inline-flex box with
     pointer-events restored. Done (audit D-5 option A, 2026-09-16): every
     selector below carries a leading html, which lifts the first one to
     (0,4,1) and ends the tie - the outcome no longer depends on which sheet is
     injected later. The hash class and the label stay as fallbacks for hosts
     without that hook. */
  html [data-mobile-nav="frame"][data-sidebar-collapsed] [data-pane="sidebar"] [data-dsh-responsive-part="sidebar-toggle"],
  html [data-mobile-nav="frame"] [data-dsh-responsive-part="sidebar-toggle"],
  html [data-mobile-nav="frame"] [class*="hHd-Xa_toggle"]:is([aria-label*="sidebar" i], [aria-label*="侧边栏"]),
  /* The label-only fallbacks MUST stay scoped to the seats the host's own
     drawer handle can live in. Unscoped they match by aria-label substring,
     and the session row's ⋯ carries 会话“<title>”的操作 — so any session
     whose title contains 侧边栏 (or "sidebar") lost its ⋯ menu entirely
     (2026-09-22 phone repro: title 侧边栏不见了 → rowActions button
     display:none, row height unchanged, time shifted right by the 16px the
     button would have taken). Anchor them to the frame's leading seat and to
     the header's leading cell instead. */
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="侧边栏"],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="侧边栏"] {
    display: none !important;
  }

  /* The host's own right sidebar IS the Files panel on phones, and the host
     pins it as a fixed full-bleed sheet: [data-sidebar-right-panel=fullscreen]
     carries position:fixed; inset:0 and no inset of its own (the host CSS
     never mentions safe-area at all). Its top row - the tab strip holding the
     tab label, the + button and the Split / Exit-fullscreen pair at the right
     edge - therefore sat UNDER the status bar: measured at 390x844 with the
     panel open, the strip is [0,0,390,38] and the phone's status bar owns the
     top of the screen. The frame's own safe-area padding cannot reach it: a
     fixed element's containing block is the viewport, not the frame's padding
     box. Taking the inset as padding keeps the panel's own --dsw-alias-bg-base
     covering the whole viewport (no seam behind the status bar) and drops the
     entire row below it, with the right-hand buttons still on the right edge.
     ONLY the fullscreen form: the host's docked form (measured at 820x1180 -
     form=push, position:absolute, 365px right-anchored) has the frame's
     padding box as its containing block, so it already starts below the
     status bar; padding it too would add the inset a second time. A host
     generation that renames the form value should fail the probe loudly
     instead of silently double-padding. The rule lives in the mobile branch,
     so desktop keeps the host layout. */
  [data-sidebar-right-panel="fullscreen"] {
    padding-top: env(safe-area-inset-top, 0px) !important;
  }

  /* Phone chrome keeps only the collapse control. Split visibility must not
     depend on data-dockkit-split-blocked: DockLayout measures after each commit,
     so conditional display can alternate blocked/allowed at the width threshold.
     Hide the wrapper too, since a blocked split wrapper is a tab stop. */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-sidebar-right-panel] [data-dockkit-split-button],
    [data-sidebar-right-panel] span:has(> [data-dockkit-split-button]),
    [data-sidebar-right-panel] [data-sidebar-right-mode="push"] {
      display: none !important;
    }
  }

  /* prefers-reduced-motion: the drawer's .28s slide is motion; drop it
     (audit S2 2026-08-27 — the old reduce block only covered the settings
     sheet and its mask). Same idiom as the animation:none blocks below. */
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"] > :first-child {
      transition: none !important;
    }
  }

  /* Drag handles are useless on touch and would float over the drawer. */
  [data-side="sidebar"],
  [data-side="details"] {
    display: none !important;
  }

  /* --- Conversation text on mobile ---
     The official message flow keeps desktop's 32px side gutters and 16px
     type. On a phone: shrink the type a notch and widen the lines by
     trimming the gutters (the sidebar drawer list keeps its size). The
     flow's scroll container holds the markdown <p> paragraphs; since
     DSH 0.1.2-rc.1 the composer is a Lexical contenteditable that also
     renders real <p> paragraphs inside its own _scroll container, so the
     composer must be excluded explicitly via
     :not(:has([data-composer-input])). */
  /* The official main scroll body reserves scrollbar-gutter for desktop
     scrollbars (8px), which shoves every column off-center on a phone.
     Classic desktop scrollbars (Edge/Chrome) also occupy ~8-17px in a
     phone-sized viewport, shifting the column further. Mobile scrolling
     is touch/wheel, so remove the scrollbar entirely on phones: the
     column is then exactly centered in every browser. */
  [data-phase] [class*="_scrollBody"] {
    scrollbar-gutter: auto !important;
    scrollbar-width: none;
  }
  [data-phase] [class*="_scrollBody"]::-webkit-scrollbar {
    display: none !important;
    width: 0;
    height: 0;
  }
  /* ---------- selection-drag autoscroll ramp (2026-10-07 live-host probe) ----------
     Report (Android): once the conversation chrome became unselectable, dragging
     the selection handle up stopped grabbing the title bar, but the conversation
     did not scroll WITH the finger - it teleported a few screens up instead.
     Mechanism measured on the live host (390x844, touch-emulated, selection anchor
     inside the message flow, pointer held 1.4s): Blink only starts autoscrolling a
     selection drag once the pointer is within ~3px of the scrollport's top edge,
     and the RATE grows with how far the pointer sits ABOVE that edge, saturating
     fast - y=140/100 -> 0 px/s, y=70 -> 1018, y=50 -> 2225, y=30 -> 3329,
     y=10 -> 3232 px/s. The conversation scrollport's top edge is the header's
     bottom edge (y=67), so every finger position on the header is 17-57px "above
     the list" and lands in the 2200-3300 px/s band: one 777px screen every ~0.23s,
     which reads as dropped frames / a jump to somewhere else. The ramp is
     geometric, not ours - from y>=100 nothing scrolls at all, so the speed is
     decided by where the list's box starts.
     Fix: extend the scrollport's box up under the header so a drag point over the
     header is only a few px above the list edge. Measured with the box moved to
     y=0: y=30 -> 0 px/s, y=10 -> 595 px/s. The visual position is unchanged by
     construction - the box grows upward by H while padding-top: H pushes the
     content back down, so an element at content offset X still paints at
     H + X - scrollTop (live-host check: header [0,0,390,67] and the composer seat
     [0,740,388,104] are identical before/after; only the box [0,67,388,777] ->
     [0,0,388,844] and scrollHeight +H move).
     H comes from --mobile-nav-header-h, written by the reconciler task
     "header-metrics" (phone-chrome.ts) as the header's bottom edge measured from
     the frame's top. It is 0px whenever the header is not measurable (hero / blank
     header), which makes both declarations below a no-op - the layout fails open if
     the host ever stops rendering a conversation header. Phone tier only: the
     desktop header is an ordinary in-flow bar and there is no touch selection drag
     to ramp. */
  [data-mobile-nav="frame"] [data-phase] [class*="_scrollBody"] {
    margin-top: calc(-1 * var(--mobile-nav-header-h, 0px)) !important;
    padding-top: var(--mobile-nav-header-h, 0px) !important;
  }
  /* ---------- selection handle drag: keep the extent local (2026-10-07) ----------
     The phone selection handle drag is a native (Android WebView
     TouchSelectionController) gesture: each move hit-tests the handle position to
     resolve the selection extent and then reveals that extent. With the bar
     unselectable but still hit-testable it won that hit test, Blink walked FORWARD
     in DOM order to the next selectable node - the flow's first item, the
     "Load earlier" gate, measured on the live host - and revealed it: the viewport
     teleported to the top of the conversation instead of extending by a line
     (reported as "not scroll-selection, it jumps somewhere else"). Dropping the bar
     out of the hit test for the duration of the selection lets the same point
     resolve into the message the lifted box above now puts underneath it, so the
     extent stays local and the scroll ramp stays gentle. 「pointer-events」 only -
     no layout change, so nothing reflows while the finger is down. The marker is
     set by installSelectionChromeYield (phone-chrome.ts) and only for selections
     that live in the conversation (composer/drawer keep their chrome
     interactive); header buttons remain reachable the moment the selection
     collapses, which is also what a tap anywhere else does. */
  html[data-mobile-nav-selecting] [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header {
    pointer-events: none !important;
  }
  /* A transparent header was harmless while nothing could scroll underneath it;
     with the box lifted it would show the messages through the title row
     (headless screenshot 2026-10-07: message text bled across "Standard mode"
     and the tab strip), so the header rule below now carries an opaque
     background. */
  /* Message action rows (copy / run-time badges) can overflow the right
     edge on narrow screens — keep them inside the message width. */
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

  /* Message tooltip bubbles (copy / feedback labels, message-row hover
     bubbles) are redundant on touch: the icon already flips to a checkmark.
     Suppress only inside the actions row — the fork's original scope. On
     this host (0.1.1-rc.2) NO tooltip renders as a visible bubble: the copy
     label is a visuallyHidden span and no client-ui package emits
     role="tooltip". The user message bubble (Sixlwa_bubble since the host moved
     it to dsh-client-ui-chat) and the goal
     bubble (oRe1gG_bubble) live in _userStack/_row, NOT in _actions — the
     previously unscoped selector hid every user message on touch devices
     (2026-09-06 live regression). role="tooltip" stays globally suppressed:
     genuine ARIA tooltips are exactly what the sticky-residue fix targets,
     and nothing legitimate carries the role today. The actions-row arm
     re-activates by itself when a host version renders tooltip labels
     inline in the actions row (DSH 0.1.2 shape). */
  @media (hover: none), (pointer: coarse) {
    [data-phase] [role="tooltip"],
    [data-phase] [class*="_actions"] [class*="_bubble"] {
      display: none !important;
      visibility: hidden !important;
      opacity: 0 !important;
      pointer-events: none !important;
    }
  }

  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) {
    padding-left: 20px;
    padding-right: 20px;
    /* Message text follows the host's own font-size axis (Settings -> 字号大小)
       instead of a frozen phone constant. The host writes the user's choice to
       <body> as --dsh-content-font-size and derives the longhand token
       --dsw-font-markdown-base-font-size from it; the previous 15px !important
       cut that chain at the container, so settings 12-17 did nothing for message
       text while the host's own markdown blocks still moved — two sizes mixed in
       one column (#52). Read the longhand token only: the other token ending in
       -base is the font shorthand, an invalid font-size value that the parser
       drops and the cascade silently falls back on. The fallback chain ends at
       the host's own default axis value. */
    font-size: var(--dsw-font-markdown-base-font-size, var(--dsh-content-font-size, 14px)) !important;
  }
  /* Descendants only inherit: the host already resolves the same token on its
     own markdown blocks (and its styles pin 16px on paragraphs / list items),
     so a rule per p / li / user-message text would cut the axis a second time. */
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

  /* Markdown tables: the official table uses width:max-content, so on a phone
     it hugs the content and leaves dead space beside/inside the table. Force
     the table to fill the message column and let the table wrapper handle
     overflow if a cell is genuinely too wide. */
  [data-phase] table {
    width: 100%;
    max-width: 100%;
  }
  [data-phase] th,
  [data-phase] td {
    max-width: none;
    min-width: 0;
  }

  /* Markdown images: the official rule often forces width:100%, which
     upscales small square images to the full message column. Show small
     images at their intrinsic size; large / very wide images still scale
     down to fit the column (max-width:100% keeps horizontal panoramas
     adaptive without overflowing). */
  [data-phase] [class*="_scroll"]:not([class*="_scrollBody"]) img {
    width: auto !important;
    max-width: 100% !important;
    height: auto !important;
    /* Cap square / tall images so a big sticker does not dominate the
       narrow column; landscape images stay governed by max-width only.
       The plain px line is the fallback for engines without dvh. */
    max-height: 220px !important;
    max-height: min(40dvh, 220px) !important;
  }

  /* User bubbles: the official stack is capped at min(525px, 82%), which on a
     phone leaves a large blank strip on the left and pushes the bubble high.
     On mobile let the user message fill the same full width as assistant
     messages (the bubble background then spans the whole message column). */
  [data-phase] [class*="_userStack"],
  [data-phase] [class*="_userStack"] [class*="_bubble"] {
    box-sizing: border-box;
    width: fit-content;
    max-width: 100%;
  }

  /* --- Composer bottom row on mobile ---
     The official row contains two lanes: tools (plus + permission/mode
     controls) and trailing (model + context + send). The previous rules made
     the modes lane flex:none, so its full intrinsic width collided with the
     model selector on narrow phones. Keep fixed hit targets fixed, but let
     text-bearing controls shrink and ellipsize before they paint over the
     trailing lane. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
    box-sizing: border-box;
    container-type: inline-size;
    container-name: dsh-mobile-composer;
    flex-wrap: nowrap;
    /* 2026-09-23 店主："每个功能键隔的空间太多"。真机实测间距主要不是 gap（6px）
       而是各控件自己的内边距；这里 gap 收到 3px，配合下面模型 chip 的 padding
       收紧，把右簇焊成一团。 */
    gap: 3px;
    padding-left: 6px;
    padding-right: 6px;
    /* The dropdown menu is absolutely positioned inside this row; any
       overflow: hidden here would clip it. Inner lanes keep their own
       overflow clipping, so the row itself can stay visible. */
    overflow: visible;
  }
  /* Dual-primary form (subagent view: stop + send). The four-control
     cluster [model][meter][stop][send] overflows the single-row lane the
     nowrap rule above enforces; the model pill is the only shrinkable
     item, so it collapses to zero and the fixed trio loses its auto
     margin (all hug the lane's left edge, send may even paint off-view).
     Restore the official wrap for this form only: the trailing lane
     drops to a second full-width row where the four controls always
     fit. Main-session three-control form keeps single-row layout. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) {
    flex-wrap: wrap;
  }
  /* Issue #140: in that same dual-primary form the stop key and the send key
     sit one 3px lane-gap apart — two same-shaped 34px pills where a mis-touch
     on the left one interrupts the running reply. The 2026-09-23 「焊在一起」
     3px decision keeps governing the main session's [model][send] cluster;
     only this form (the one with two adjacent destructive-adjacent primaries)
     gets +8px between the stop and the send key. Knob: margin-right. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] > [class*="_primary"]:has(~ [class*="_primary"]) {
    margin-right: 8px;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child {
    flex: 0 1 auto;
    min-width: 0;
    /* 2026-09-23 店主第三轮："左边那三个功能区挨得太近了，隔开一点点"。
       权限控件收窄 16px 后，📎 跟着整体左移、贴到了 ⌄ 上（实测墨迹间距只剩 ~3px）。
       工具道 gap 单列放宽到 8px（右簇仍 3px，保持焊在一起）。 */
    gap: 8px;
    /* The permission dropdown (Menu, side: top) pops upward from inside the
       tools lane; overflow hidden here would crop it, same as the row. Text
       ellipsis is handled by the trigger label itself. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] {
    flex: 1 1 auto;
    min-width: 0;
    gap: 3px;
    /* Must not clip the model dropdown; the model trigger clips its own label. */
    overflow: visible;
  }
  /* Permission / plan controls share the tools lane inside the a2-style
     'div.modes' container (class survives as 'css.modes'; audit doc §10.1 /
     E-1). The positional anchor '> :first-child > :nth-child(2)' was already
     off-target on rc.2 and dies entirely on a2, so the series re-anchors on
     the tools lane's modes container: '[class*="_tools"] > [class*="_modes"]'
     (live-verified on the rc.2 host: the modes div is a direct child of the
     tools lane, a grandchild of the row — a row-direct-child anchor matches
     nothing on either generation). The permission label uses the remaining
     tools width, while the lower-priority plan slot keeps an icon-sized
     target instead of stealing model width. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] {
    flex: 0 1 auto;
    min-width: 0;
    max-width: none;
    /* 2026-09-23 店主："左边那个权限的也缩一点点"：容器 gap 4→0。 */
    gap: 0;
    /* The permission Menu list (side: top) pops upward out of this lane;
       overflow hidden crops it. The trigger label clips its own text. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] {
    flex: 1 1 auto;
    min-width: 28px;
    max-width: 100%;
    display: flex !important;
    overflow: hidden;
    /* 权限 trigger 自带内边距 + flex gap（图标与 ⌄ 之间），图标化后都是浪费：
       2026-09-23 按店主"缩一点点"归零（真机 44px 盒 → ~34px）。 */
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
  /* Slot wrappers such as the live plan chip are not trigger elements. Do
     not force them into an icon-sized box: their child button would overflow
     that wrapper and paint over PermissionSelect. Keep the wrapper intrinsic;
     the model lane below is the one that sacrifices width. */
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
  /* 权限触发器（宿主 dsh-client-ui-permission-presets，样式哈希 iWlSmW_）自身带
     padding:0 4px 0 8px + gap:4px —— 与模型 chip 同款浪费（左 8px 是给文字留的）。
     2026-09-23 店主："左边那个权限的也缩一点点"。注意：它外面套了一层
     display:contents 包装（真机探针：modes[55,44] > div[contents] > root[55,44]），
     所以「_modes > _trigger」这类直接子代锚点命不中（上一版改了没反应），
     必须用哈希后代锚点；哈希变了整条自动失效，不会误伤别家。 */
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_trigger"] {
    padding: 0 !important;
    gap: 0 !important;
  }
  /* 2026-09-23 店主："权限的图标有点小，稍微大一点点，不然左边轻右边重"。
     宿主把图标包在 _triggerIcon 里、自己写死 14px（iWlSmW_triggerIcon svg
     的 width/height 都是 14px），与 + / 📎 的 16px 不齐。只放大那个包装里的
     svg：⌄ 箭头不在 _triggerIcon 内，不会被一起放大。盒子 28×28 不变（16 仍有余量）。
     注意：本文件是模板字符串，注释里**不能出现反引号**（会劈开 CSS）。 */
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_triggerIcon"] svg {
    width: 16px !important;
    height: 16px !important;
  }

  /* Model selector: flexible and shrinkable, but never clipped.
     The root must be overflow:visible so the dropdown menu can render.
     The trigger itself clips the label text. */
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

  /* Model switcher menu: was centered here with left:50% + translateX(-50%), but the
     host now PORTALS the menu to <body> (measured 2026-09-23: _7KE1Ra_menu, role=menu,
     position:fixed, parent=BODY, inline left/top), so this child-chain selector stopped
     matching and the rule had been dead. The re-anchor lives in JS instead —
     effects/model-menu-anchor.ts centers the panel on the trigger and clamps it to the
     viewport. Do not re-add a CSS rule here without checking the portal parent. */

  /* Phone reasoning-effort list is compact; the model catalog and the tablet stay
     host-sized. Measured on the real host bundle (dsh-client-ui-model-selection
     0.2.0-rc.2, 393x844, phone emulation): the effort pane renders 248px wide
     (its own min-width is 240px) while its four levels need far less.
     The two panes differ in the SHAPE of their radio list, and that difference is
     the only reliable discriminator (measured, same run):
       effort pane   DIV._surface_ri079_1._7KE1Ra_menu[role=menu]
                       > DIV._material_ri079_21
                       > button._7KE1Ra_option[role=menuitemradio]   <- DIRECT child
       model pane    DIV._surface_ri079_1._7KE1Ra_menu[role=group]
                       > DIV._material_ri079_21
                       > DIV._7KE1Ra_groups.scrollable[role=menu]
                           > SECTION._group_121p8_1[role=group] > button[role=menuitemradio]
     The host sets 「role: pane === "model" ? "group" : "menu"」, so the effort pane is
     the role=menu shell and its radios ARE direct children, while the catalog's sit
     two levels deeper. Requiring both halves — role=menu AND a direct-child radio —
     therefore selects the effort list and leaves the catalog at its host size.
     Neither half is safe alone: dropping the role also matches the catalog's inner
     _groups container, and matching radios as descendants selects the catalog shell.
     (Measured with each variant: the group/descendant form hit the catalog only.)
     「min-width: 0 !important」 is load-bearing: without it the host's own
     min-width 240px keeps the pane at 240 no matter what width is set. */
  @media (max-width: 767px) and (pointer: coarse) {
    [class*="_7KE1Ra_menu"][role="menu"]:has(> [role="menuitemradio"]) {
      width: min(208px, calc(100vw - 32px)) !important;
      min-width: 0 !important;
      max-width: calc(100vw - 32px) !important;
    }
  }

  /* --- Fix composer row overflow at narrow widths (320px-360px) ---
     Force every direct child of the tools and trailing lanes to shrink,
     so they can fit within the available space without causing horizontal
     overflow. The fixed-size icon buttons are exempt: officially both are
     flex:none at a fixed size (plus 28x28, send 34x34) and must stay put,
     not participate in adaptation. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > :not([class*="_add"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > :not([class*="_primary"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  /* Pin the plus button at the left edge of the tools lane: official
     flex:none 28x28, never squeezed by narrower viewports. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_add"] {
    flex: none;
  }
  /* The context meter in the trailing lane is another fixed-size icon
     control: its trigger is officially width:28px flex:none, but the root
     itself is shrinkable, so a squeezed root lets the trigger paint over
     the pinned send button. Keep the whole meter at its natural size; its
     trigger uses aria-haspopup="dialog", so the model-selector menu rules
     (keyed on "menu") still do not apply.
     GENERATION NOTE (issue #140, 2026-09-29): 0.1.7-rc.2 moved the
     ContextMeter out of this lane into the dock row under the card (next to
     the TPS stats pills) — on rc.2 NONE of the trailing-lane meter rules in
     this section match any more, and the trigger is back to its official
     ~22px-tall box. They stay for the 0.1.5/0.1.6 generations where the
     meter really rendered in the lane (structural anchors, inert elsewhere);
     the rc.2+ hit-area repair lives in the dock-row section below. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"] {
    flex: none;
    min-width: 0;
  }
  /* --- 右簇贴右：2026-09-23 重新对锚（模型胶囊改"只留图标"后暴露的旧账）---
     宿主把右簇（模型座位 standardControls / 麦克风 activity / 发送 primary）
     放进可增长的 trailing 车道，靠"某个成员带 margin-left:auto"把整簇顶到右缘。
     插件原来把吸收器挂在模型 root 上：
       > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"])
     但 0.1.7 的祖先链变成了
       trailing > standardControls(flex item) > div[display:contents] > _root > _trigger
     于是 root 只是 standardControls **内部**的 flex item，auto 外边距落在一个
     内容宽度的盒子里 ⇒ 等于失效。真机探针实测三者 ml 全 = 0px，就是铁证。
     2026-09-23 之前胶囊很宽、把车道填满，看不出来；胶囊一收成图标，右簇立刻
     塌到左边（发送 x≈316 → 224，右边空出 ~76px，店主一眼看出"位置被移了"）。
     修法：把吸收器改锚到「车道的第一个 flex item」，并只在模型座位在场时生效
     —— 那时宿主那条把 primary 的 auto 清零的规则也在生效，避免两个 auto 平分
     空隙；模型不在场（子代理视图）照旧由 primary 自己的 auto 收尾。
     justify-content: flex-end 是兜底：万一首个 child 是 display:none，auto
     无处可挂时仍能贴右（此时无 auto 外边距，flex-end 才起作用）。 */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) {
    justify-content: flex-end;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) > :first-child {
    margin-left: auto;
  }
  /* ContextMeter (JObwrW_ hash family) hugging the primary key. This single
     value is the whole spacing knob, and because the trigger box is centred on
     the ring ink it doubles as the ink offset:
       6px + margin-right = the sliver before the primary key = the ink's
       leftward shift. 0px (current) therefore shifts the ink 6px -- exactly the
       official lane gap, with no negative-margin trick left in the chain --
       while -6px pins the ink perfectly still and +8px was vetoed on
       2026-09-17 as "too much" (14px). The phone owner asked for a visible
       shift after 1px (-5px) proved imperceptible, and will re-tune this number
       by eye: change it and nothing else moves.
     Anchor on the unique aria-haspopup="dialog" trigger (no other composer
     control uses it), not the hashed class, so an upstream hash bump cannot
     silently unhook us. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-right: 0px;
  }
  /* The model pill joins the same right cluster: its margin-left:auto absorbs
     ALL trailing slack, so the adaptive void sits between the tools lane and
     the pill (visible on wide phones/tablets), while [pill][meter][send] stay
     welded together at the right edge on every width. Descendant combinator
     on purpose: the pill root sits behind a display:contents wrapper, so a
     direct-child combinator silently misses (probe-verified). Within the
     trailing lane aria-haspopup="menu" belongs to the model trigger alone. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    margin-left: auto;
    margin-right: -4px;
  }
  /* Grow only the invisible trigger BOX, never the ring ink: 24x24 -> 28x34.
     The WIDTH is capped at 28 by pure geometry, not by taste: the box is
     centred on the ink, and the primary key's hit box begins 14px right of the
     ink's centre, so 28 is the widest box that can reach that boundary without
     stealing a single pixel from the destructive key (the current 1px sliver
     is the spacing knob on the root rule above); the same arithmetic puts the
     left edge on the model pill's edge. The 34px HEIGHT is free: the primary
     key is already the tallest control in the lane, so the box cannot overlap
     anything vertically and the row height does not move. Hit area 576 -> 952
     square px (+65%) with the ink within 1px of its old spot (probe-asserted),
     and the ring's ink stays at its official 14px -- enlarging it is rejected
     as attention-grabbing. Knob: height can drop to 28 if the tap halo should
     be a circle rather than a stadium. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_trigger"] {
    width: 28px;
    height: 34px;
    padding: 0;
  }
  /* Slack-absorber priority in the trailing lane: model pill > meter > send.
     Exactly one element carries margin-left:auto so the adaptive void always
     sits BEFORE the welded right cluster, never inside it. The meter itself
     never had an auto before 2026-09-06: in subagent sessions the model seat
     is officially absent (the parent pins the model), and zeroing the send's
     auto on the meter's aria-haspopup="dialog" then left NOTHING to absorb
     slack -- the whole right cluster hugged the lane's left edge (user
     screenshot). Fix: when no model pill renders, the meter root becomes the
     absorber, welding [meter][send] at the right edge like the main view's
     [pill][meter][send]; the send's auto only survives when neither renders. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-left: auto;
  }

  /* --- rc.2+ 的第二个失效面：子代理 composer 里环锚点回不到发送键左边（2026-10-12）---
     上面那条「无模型胶囊时由 ContextMeter 当吸收器」的兜底，在 **0.1.7-rc.2+** 上
     再也匹配不到：宿主已经把 ContextMeter 从 trailing 车道搬进卡片下方的 dock 行
     （实测路径 … > [class*="_card"] > [class*="_root"] > [class*="_dock"] > span.JObwrW_root），
     它不再是车道的子元素，「:has(> [class*=\"_trigger\"][aria-haspopup=\"dialog\"])」落空。
     后果（headless 393×844 实测，主/子两视图同机对比）：子代理会话里模型胶囊整个不渲染，
     「standardControls」塌成 0×0 且 margin-left:0，于是车道里唯一带 auto 的是发送键自己，
     插件注入的环锚点 span[data-mobile-nav=\"stats-ring-reserve\"]（普通流内元素）被留在
     车道左缘 —— 环 x=127，而主会话同一元素 x=310、发送键在 333：**188px 空档**，
     用户看到的就是「上下文表跑到左边去了」。
     修法与 #191 同源（同一根因族：rc.2 把 meter 搬走后，车道里的 auto 归属没人接管）：
     没有模型胶囊时让**环锚点**顶替 meter 当吸收器，并把发送键的 auto 清零 ——
     不清零的话两个 auto 会平分空隙，环同样回不到键边。
     门控与上面那条兜底完全一致（车道里没有 aria-haspopup="menu"），所以主会话
     （模型胶囊在场）零改动。 */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [data-mobile-nav="stats-ring-reserve"] {
    /* !important 不可省：compat 层有一条 [data-mobile-nav="stats-ring-reserve"]
       { margin: 0 2px 0 0 !important }。两条都 important 时先比特异性 —— 本选择器
       远高于那条裸属性选择器，加 !important 就能赢；不加则被它压成 margin-left:0。
       2026-10-12 受控实验实测：不加 → 环仍停在 x=127、发送键反被拉到 150（188px 空档
       只是从「环与键之间」搬到「键的右侧」）；加 → 环 310 / 键 333，正是期望值。 */
    margin-left: auto !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_primary"] {
    margin-left: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] {
    flex: none;
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"], > [class*="_root"] > [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_primary"] {
    margin-left: 0;
  }
  /* Issue #191 — the subagent running form (stop + send, both _primary) has
     NEITHER of the zeroing rule's two gates: ModelSelect renders nothing in an
     addressed subagent session (no aria-haspopup="menu" in the lane) and the
     meter lives in the card's dock row since rc.2 (no _root > dialog trigger
     in the lane either). Both primaries therefore keep the auto margin from the
     rule above, and flex SPLITS the free space between the two auto margins —
     the stop key lands mid-lane with a void on both sides of it.
     Same recipe as the model-present case (:707-712): zero every auto so no
     element competes for the slack, and let justify-content pin the whole
     cluster right. Zeroing alone would hug the LEFT edge instead, so both
     halves are required. Scoped to the dual-primary form, so the main
     session's single-primary path keeps its auto untouched. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] {
    justify-content: flex-end;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) > [class*="_trailing"] > [class*="_primary"] {
    margin-left: 0;
  }
  /* 2026-10-20（店主）：「暂停键和发送键……大小缩小，和其他功能键保持一样的大小」。
     同行三个功能键（+ / 权限 / 📎）的官方标称是 28×28，主键（发送、停止生成）官方
     是 34×34 —— 手机档把主键压到同一档：盒 28×28、内边距清零、圆角保持正圆，
     图标仍是 16px，因此圆心与同行按钮落在同一条线上。双主键形态（停止 + 发送）
     同样命中，两者都收小，8px 的防误触间距规则不受影响。
     2026-10-20 二轮：App 会往页面注入一张读不到 cssRules 的样式表（见上方页签条
     注释），第一版没加 !important，真机上尺寸没变。这里按「抗覆盖」写：三项尺寸
     全部 important，并补一条不依赖 [data-phase] / _card / _row 祖先链的兜底选择器
     （车道直系子元素即主键），祖先链任一新代断层都不至于整条规则死掉。
     后续：2026-10-20 四/五轮把**手机档**再改到 30px + 18px 图标并统一颜色，
     覆盖在文件末尾「输入区功能键尺寸」段；本段仍是 768–1023 平板档的值，别删。 */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] {
    box-sizing: border-box !important;
    width: 28px !important;
    min-width: 28px !important;
    height: 28px !important;
    min-height: 28px !important;
    padding: 0 !important;
    border-radius: 50% !important;
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
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] svg {
    width: 16px !important;
    height: 16px !important;
  }
  [class*="_trailing"] > [class*="_primary"] svg {
    width: 16px !important;
    height: 16px !important;
  }

  /* --- ContextMeter hit area on 0.1.7-rc.2+ (issue #140) ---
     The stats-line overlay parks the ring in the composer card's trailing
     lane (right cluster next to the send key — a deliberate 2026-09-23
     placement, kept per the reporter's confirmation at acceptance). The
     official trigger is 16x20px, far under the touch minimum. Regrow the
     hit area IN PLACE with a transparent ::after (the 📎 recipe).
     2026-09-29 acceptance, final geometry (headless-measured): the ring
     svg and its reserve are set to 18px (compat.css.ts — 16px was too
     small to aim, 24px/20px too big, the reporter settled on 18px), the
     width growth is absorbed by the lane's left slack so the key gaps
     stay 6px/5px, and the hit box is a SYMMETRIC 26x26 square
     (inset -4px) keeping the generous touch area around the smaller
     ring — 2px/1px clearance to the model and send keys. The track is
     deepened to 25% black (compat.css.ts) so the donut reads as a
     meter, not a spinner.
     Knobs: the svg/reserve size (compat.css.ts) and this inset; the box
     must stay a square hugging the ring, its edges clamped by the two
     neighbouring keys. The dock container owns only this one dialog
     trigger (the TPS stats pills render plain text), so a scoped
     aria-haspopup="dialog" anchor cannot cross-match anything; the DOM
     ancestry (ring inside the dock container) is unchanged by the
     overlay's absolute positioning. */
  [data-phase] [class*="_dock"] [class*="_trigger"][aria-haspopup="dialog"] {
    position: relative;
  }
  [data-phase] [class*="_dock"] [class*="_trigger"][aria-haspopup="dialog"]::after {
    content: '';
    position: absolute;
    inset: -4px;
  }

  /* --- Third-party model seats (issue #60: @hytime/dsh-thinking-effort) ---
     A seat registered on conversation.input.model replaces the official pill,
     so the trailing lane no longer contains an aria-haspopup="menu" trigger:
     the pill absorber rule above never matches, and the meter fallback below
     would split the slack with the seat (two auto margins share it), leaving
     the seat stranded mid-lane. Worse, in the seat's open state the root's
     only child is the absolutely positioned panel, so the root collapses to
     zero width and the panel's right:0 anchor (width min(336px, 100vw - 32px))
     sweeps 336px leftward from wherever the stranded root sits — 230px off
     screen at 393px (reporter-measured: root x=106, panel left=-230; with our
     stylesheet disabled the root sat at x=339 and the panel at +3, which pins
     the blame on our injection). Both repairs anchor on the plugin's own
     stable data-seat-* markers (identical across v0.2.3-v0.3.7) and leave the
     official pill untouched:
     1. the seat root stretches across the trailing lane with its content
        pushed to the right edge, so the closed chip welds onto the
        [meter][send] cluster AND the grown root consumes all free space,
        which zeroes the meter fallback's margin-left:auto (flexible lengths
        resolve before auto margins — no double void). The root is also made
        position:static so the panel below anchors to the composer card
        instead — the root is 0-width while the panel is open, so any
        root-relative offset is meaningless;
     2. while the panel is open its anchor is laid over the composer card
        (left:0/right:0 against the card's padding box + an auto-margin
        centre), so the panel is centred on the card, never rides the
        collapsed root and never leaves the viewport.

     ── 2026-10-04, thinking-effort 0.3.7: the old centring recipe had to go ──
     Repair 2 used to be 'left:50%; right:auto; transform:translateX(-50%)'.
     That recipe only worked while the plugin positioned nothing itself: a
     mobile viewport left the panel hanging off whichever edge the collapsed
     root sat near. 0.3.7 added its own narrow-screen clamp, which writes an
     INLINE 'left' (390px phone, new chat: root x=122, inline left=-106px —
     i.e. 16px, exactly the right place). Inline 'left' wins over the
     stylesheet, so the recipe's 'left' became dead weight while its
     'transform' kept firing, shifting the panel half its width further left:
     x = 122 + (-106) + (-168) = -152px, off screen by 152px — the reproduced
     bug. Two layers each half-applied. The fix keeps repair 1 (the stretch
     is still what welds the closed chip onto the [meter][send] cluster) and
     replaces repair 2 with the card-anchored recipe above; 'transform:none'
     is what neutralises the stray translateX, and 'left:0' is '!important'
     because it must beat that inline value. Panel width stays the plugin's
     own (336px, capped at 100%) so the Off/High/Max ticks keep their
     designed spacing.

     The panel opens bottom:calc(100% + 8px) above the card, but the card is
     only 70-108px tall on a phone while the panel is 208px, so it always
     overflows upward — that is the plugin's own desktop behaviour and is
     kept. What is NOT acceptable is overflowing the VIEWPORT, so three
     guards follow (the inner listbox, the keyboard-short phone, landscape).
     Every guard stays inside this mobile media query: desktop untouched. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [data-seat-root] {
    flex: 1 1 auto;
    justify-content: flex-end;
    /* Keep parked here rather than in a second rule: the panel below anchors
       to the card, not to this root. */
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

  /* Guard 1 — the panel's inner model listbox opens upward (bottom:58px
     inside the panel) with max-height:min(220px, 100vh - 96px). On a short
     screen that ceiling is taller than the room above the panel, so the
     listbox pokes past the viewport top (320x568: menu y=-41). Clamp it to
     half the viewport minus the panel's own 58px offset + padding. */
  @media (max-height: 700px) {
    [data-seat-model-menu] {
      max-height: min(220px, calc(50vh - 110px)) !important;
    }
  }

  /* Guard 2 — with the on-screen keyboard up (portrait, ≤505px tall) there
     is not even 208px of room above the card. Let the whole panel scroll
     inside itself instead of escaping the top; children keep their natural
     height so the slider and its ticks are never squashed. */
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

  /* Guard 3 — landscape: above the card there are only ~156px (the card
     sits low, the panel is 208px), so an upward opening can never fit.
     Detach the panel into a bottom-docked sheet instead; it floats clear
     of the card and the viewport top. */
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

  /* --- Composer file entry (0.1.6 host) ---
     The 0.1.6-alpha.2 host deleted the composer's paperclip attach button, so
     the only file entry left is the 文件 row inside the "+" listbox. The
     control is contributed to the host-declared conversation.input.left list
     slot ("Compact controls at the left of the composer tool row"), inside the
     tools lane beside the plus button, so only its own box is needed here:
     28x28 like the plus button and fixed — one of the row's hit targets, never
     part of the adaptive shrink. Its click handler triggers the host's own
     hidden input[type=file], so intake validation and upload stay host-owned. */
  [data-composer-card] [data-mobile-nav="file-upload"] {
    flex: 0 0 auto !important;
    /* 2026-09-23 店主："触发点有点小，没那么容易点" ⇒ 盒子 28×28 → 34×34
       （面积 +47%），再由下面的 ::after 向外扩 4px（最终命中区约 42×42）。
       **图标位置不变**：盒宽 +6 后 margin-left 从 -10 收到 -13，图标中心原地不动；
       高度对齐发送键的 34px，行高不受影响。 */
    /* 2026-10-07 店主：「点一下有一层深一点的灰色，也有一层浅一点的灰色，去掉一层」。
       根因是**嵌套两层**：宿主的按钮盒底色（34x34、圆角 8）与我们的 ::before 胶囊
       （28x28 圆）半透明叠半透明 —— 重叠区深、外圈浅。压层叠（!important）在真机上
       压不住那一层（实测按下仍是 233 外圈 + 222 内层），所以改成**结构上只有一层**：
       可见胶囊 = 按钮盒本身。盒子 28x28 + 全圆角，与加号同尺寸；图标中心不变
       （盒宽 34→28 ⇒ margin-left 由 -11 收到 -8）；命中区仍由 ::after 外扩。 */
    width: 28px !important;
    min-width: 28px !important;
    max-width: 28px !important;
    height: 28px !important;
    min-height: 28px !important;
    padding: 0 !important;
    position: relative !important;
    /* 左移 10px + 图标 14→16px（2026-09-23，店主："太往右了、有点小"）：
       工具道现在是 [+][⚠⌄][📎]，宿主给 modes 控件留了较宽的尾部留白，📎 看着
       离左边一截。与参考图逐像素对齐（以 + 为锚点）：参考 📎 墨迹 107..117 CSS，
       我们原先是 114..123；而墨迹高度 48 vs 参考 54 物理 px ⇒ 图标 14 偏小，
       换成宿主通用的 16（+ / ⚠ 都是 16）。28px 盒 + 16px 图标居中 ⇒ 墨迹左缘
       = 盒左缘 + 8.85，故盒左缘取 98 ⇒ margin-left: -10px（吃掉 6px gap 后再
       压进 modes 尾部留白 4px，不碰它的墨迹：chevron 墨迹止于 ~91）。
       这一个数值就是"往左多少"的旋钮，可按眼睛调，别动别的。 */
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
  /* 按下/悬停反馈：宿主其它按钮（加号常驻、模型与权限触发器按下）都有灰胶囊，
     只有我们这个是纯透明、也没有 :active —— 店主 2026-09-23："文件上传那个图标
     怎么没有胶囊？"（点了没反应）。用宿主自己的 hover token，视觉与官方一致。 */
  /* 可见胶囊只在 ::before 上画 28×28 的圆（与加号同尺寸，店主："胶囊有点太大"），
     按钮盒子仍是 34×34 + ::after 外扩 —— 命中区大、看起来小，两者解耦。 */
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    content: '';
    position: absolute;
    inset: 3px;
    border-radius: 999px;
    background: transparent;
    transition: background .12s ease;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    /* 胶囊已由盒子本体承担，这一层永久透明（保留节点是为了不惊动既有锚点）。 */
    background: transparent !important;
  }
  /* 2026-10-07 店主："点一下有一层深一点的灰色，也有一层浅一点的灰色，去掉一层"。
     真因是**半透明叠半透明**，不是我们画了两层胶囊：宿主全局 CSS 里有
     「button:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover)}」
     （特异性 0,2,1 > 上面那条基础规则的 0,2,0，所以它赢了）与
     「button:active{background-color:#0000001f}」，两者都画在这个按钮的**盒子**上；
     而 ::before 的可见胶囊又画一层 ⇒ 重叠区深、外圈浅，看着就是两层灰。
     可见胶囊只留 ::before 一层：盒子在所有状态下强制透明（::after 只有命中区、无底色）。 */
  [data-composer-card] [data-mobile-nav="file-upload"]:hover,
  [data-composer-card] [data-mobile-nav="file-upload"]:active,
  [data-composer-card] [data-mobile-nav="file-upload"]:focus-visible {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
    box-shadow: none !important;
    outline: none !important;
    border-color: transparent !important;
  }
  /* 压掉浏览器默认的淡蓝 tap 高亮（店主 2026-09-23："单纯点击图标，出现一个淡蓝色
     的原始的点击画面"）。读源码取证：宿主头部那几个包（dsh-client-ui-subagent /
     agent-preset / dsh-experimental-client-ui-agent-team / jobs）**都没有 :active、
     也没有任何 tap-highlight 处理**，触摸设备上点"标准模式 / Agent Team / 1 个子代理 /
     对话·轨迹"就会叠一层原始高亮；我们输入区的控件早已处理（见上面 file-upload 那组）。
     做法与输入区同源：高亮透明，按下反馈交给宿主自己的 :hover/:active token
     （那几个包各有 2~9 条 :hover 规则，触摸时 Chromium 会套用）。 */
  /* 覆盖范围放宽：宿主有些控件不是 button（实测输入区里就有 [role=button]、带
     tabindex 的 div 形态），所以三类一起收。 */
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

  /* 深色下顶栏与页面底色的断层（issue #176「减轻顶部纯黑区域与网页头部的突兀色差」/
     2026-10-11 真机反馈：手机上头部呈灰黑一块，跟上面那行系统时间栏分得太开）。
     实测（393×844，真实会话，深色）：page/body 与 frame 都是 #151517（--dsw-alias-bg-base），
     而宿主会话头部自带层色 #232324，头部上方还夹着一条跟随页面底色的安全区带
     ⇒ 「深色带 → 较亮头部 → 深色内容」三段断层。
     处理：手机档（本块在移动媒体查询内）把头部底色并到页面底色，层次改由分隔线与内容承担；
     浅色档宿主两头同为白、本来就没有断层，不参与；桌面档不碰。
     宿主深色标记挂在 body 上（实测设 body[data-ds-dark-theme] 即切到 #151517），
     html 上若也挂同一个标记一并覆盖。 */
  body[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header,
  html[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header {
    background-color: var(--dsw-alias-bg-base, #151517) !important;
  }
  /* 壁纸兼容（issue #228）：同一个插件把 --dsw-alias-bg-base 也置为 transparent
     （见上方深色顶栏规则）。深色下顶栏用该令牌与页面同色，令牌被清空后顶栏
     失去底色，消息滚动区从标题行下方穿过。壁纸启用时用该令牌自身的深色静态
     定义补回原色；浅色档通用规则已经用未被覆盖的 --dsw-alias-bg-layer-1，
     不需要新规则。门控含 data-ds-dark-theme，特异性 (0,4,2) 高于上方深色
     规则的 (0,3,2)，不依赖源码顺序。 */
  /* @patch
     id: wallpaper-header-fill
     for: deepseek-harness-background 0.6.x 的 body[data-dsh-bg]（壁纸启用时把 --dsw-alias-bg-base 置为 transparent）
     why: 深色顶栏以该令牌与页面同色，第三方插件把它清空后顶栏失去底色，滚动内容从标题行穿过
     until: 上游提供不透明的面板填充接缝，或不再把 --dsw-alias-bg-base 置空——届时本块删除
     test: tests/wallpaper-surface-fill.test.ts
  */
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header {
    background-color: var(--dsw-static-neutral-bluish-950, #151517) !important;
  }
  /* 头部 UI 的按下反馈（店主 2026-09-23："是头部 UI，没有触发反馈"）。
     取证：头部四个宿主包 :active 全为 0，反馈只挂 :hover。
     ⚠ 第一版我给整颗 button 上 background-color，店主实测"胶囊过宽、跑到子代理下面"
     —— 因为 button 盒比可见胶囊大（芯片文字只占盒的一部分）。所以改成**不改几何**的
     按下效果：整体压暗（.92 ≈ 宿主 token 的观感强度；太淡店主会觉得"没变"）。胶囊类的视觉仍由宿主自己的 chip 背景负责。
     不用 position/伪元素：头部芯片里挂着宿主的弹层，改 position 会挪动包含块。 */
  [data-mobile-nav="frame"] [data-phase] header button:active,
  [data-mobile-nav="frame"] [data-phase] header [role="tab"]:active {
    filter: brightness(.92);
  }
  /* 头部那些 v 的翻转：**标准模式那个现在会翻** —— 规则在本文件「DSHA 集成层：预设 chip」
     那一块（搜 data-dsha-agent-preset="header" 的 svg:last-of-type 两条）。这里留一段纠正记录，
     免得后人被已经作废的旧结论误导：

     · 旧结论（同日早先写的）称"本 WebView 里该 svg 的 CSS transform 完全失效"——**错的**。
       真因是预设 chip 的 > svg 上有一条我们自己写的
       [data-dsha-agent-preset="header"] > svg { transform: none !important }（DSHA 集成层拿它把
       图标拉回静态流）。**内联 transform: rotate(45deg) 没带 !important，被那条压掉**，
       于是量出"盒子不变、computed 仍是 none"，被我误判成 WebView 不吃 CSS transform。
     · 子代理 chip 的 v 一直是宿主自带：dsh-client-ui-subagent 的
       .ZKlsPq_trigger svg{transition:transform .12s} + 类 .ZKlsPq_triggerOpen{transform:rotate(180deg)}。
     · 禁止对头部 svg 写通配规则（header svg{...} / [class*=chevron]{...}）：那会覆盖子代理 chip
       自己的 triggerOpen 状态，出现"修一个压掉另一个"（这正是当时反复翻车的原因）。 */
  /* 输入区**宿主渲染**的功能键**不再自加胶囊**（店主 2026-09-23："点击功能键怎么有两个
     灰色的叠加？"）。
     原因：宿主本来就有自己的 hover 底色（conversation 包 13 条 :hover、model-selection 3 条、
     permission-presets 2 条、input-trigger 3 条），我们再加一层 ::before 就是**两层灰叠在一起**。
     教训：上一轮店主说"这几个功能没有触击反馈"，我据此加了胶囊 —— 实际是那次刚把浏览器默认
     淡蓝 tap 高亮压掉、观感反差的错觉；**宿主已有的反馈不要再叠一层**。
     我们自己注入的 📎（[data-mobile-nav="file-upload"]）例外：宿主没有对应控件、也就没有底色，
     它的胶囊留在上面那组规则里。 */
  /* 命中区外扩：::after 属于按钮本身，一起参与命中测试，视觉完全不变。 */
  [data-composer-card] [data-mobile-nav="file-upload"]::after {
    content: '';
    position: absolute;
    inset: -7px;
    border-radius: 999px;
  }
  /* A busy submit phase or a subagent session refuses attachments. The host
     gates intake on canAcceptDrop (package-private), so this reads the closest
     observable facts — input phase and subagent — and keeps the control from
     opening a dialog the host would then reject. */
  [data-composer-card] [data-mobile-nav="file-upload"]:disabled {
    opacity: 0.38;
    cursor: default;
  }
  /* The hidden input[type=file] this control triggers only exists on
     0.1.6-alpha.2+ hosts (the rc generation's intake is paste/drop only),
     while the host renders the input.left seat on both generations. Hide the
     control wherever the host has no file input for it to trigger — its click
     would otherwise be a permanent silent no-op there (review 2026-09-19). */
  [data-composer-card]:not(:has(input[type=file])) [data-mobile-nav="file-upload"] {
    display: none !important;
  }

  /* --- Composer vertical slack on mobile (0.1.6 host) ---
     The host's own .card padding-top:8px + gap:12px and .row padding leave 29px of pure
     blank space in a 98px single-line card (measured). Only the vertical slack is trimmed;
     horizontal padding and both hit targets stay untouched. Scoped to the active phase on
     purpose: the hero composer's input carries the host's own min-height floor, and trimming
     it there re-creates the clip/scrollbar defect recorded under Pitfalls「hero 输入框下限」. */
  /* DSHA：输入卡片自身留白偏大。宿主那两声明全出自它自己的
     dsh-client-ui-conversation（.uV2eYG_card 是 padding-top:8px + gap:12px，
     .uV2eYG_row 再吃 padding:2px 8px 6px），单行输入时卡片 98px 里有 29px
     是纯空白。手机上只压纵向留白（真机实测 moderate 档）：
       卡片 98 -> 78、编辑器 36 -> 32、按钮行 42 -> 36、文字底到按钮顶 29 -> 19px。
     横向 padding（8px）与两个按钮尺寸（28/34px）一律不动，触控目标不变；
     编辑器仍是可增长的多行框（max-height 336px），只是单行时不再垫高。 */
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
  /* --- Session header on mobile ---
     Keep the host-owned metadata in one responsive row. The conversation
     title, the mode text and the running/subagent status all keep their
     words; the one tenant that yields width when a phone runs out of it is
     the background-job trigger's verbose label ("1 background job running"),
     while Files keeps its hit area. */
  /* Both !important flags are load-bearing. The host's session-controller sheet
     ships [data-dsh-frame] [data-dsh-responsive-part="conversation-header"] with
     padding-left: 60px !important under (max-width: 768px), so a plain
     declaration here loses however specific it is: measured 2026-09-13 at
     390px, the computed padding-left stayed 60px and the title still began at
     x=100 with our rule present, matching and later in source order. The value
     is 0 because our own toggle already occupies that left seat (painted at
     x=8-36), so the host reservation is pure dead space on a phone. */
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header {
    padding-left: 0 !important;
    padding-right: 8px !important;
    position: relative !important;
    /* Opaque AND above the flow, both required: the host bar has no background,
       and the message flow is a later sibling, so it painted OVER the bar (three
       headless screenshots 2026-10-07: transparent -> content read through the
       title row; opaque but unraised -> byte-identical to transparent, i.e. the
       background was painted underneath; opaque + raised -> pixel-identical to
       the untouched band). The scroll box runs underneath because of the
       "selection-drag autoscroll ramp" rule above (it lifts it by
       --mobile-nav-header-h). Same surface token the panel ghost uses, so themes
       carry over; the host keeps its --dsw-alias-border-l3 bottom border.
       z-index 30 clears the flow's own raised layers (host banners measured at
       z 6-7 and the marks slot / composer seat at 7 on the live host) while
       staying under this plugin's overlay tier (55+ backdrops/panels, FAB 21 is
       hero-only where the header is hidden, drawer/menus 1000+) so a dialog or
       the drawer still covers the bar. */
    background: var(--dsw-alias-bg-layer-1, #fff) !important;
    z-index: 30 !important;
  }
  /* The hero phase's empty header must stay hidden on phones. The host hides
     it via the headerHidden class at (0,1,0), but its own session-controller
     sheet re-shows the conversation header as a grid at <=768px —
     [data-dsh-frame] [data-dsh-responsive-part="conversation-header"] with
     display grid at (0,2,0) — and that beats the hide on the very element
     carrying both classes. Result measured 2026-09-19 at 390px: an empty 85px
     header paints only its 1px border-bottom (--dsw-alias-border-l3) as a stray
     gray hairline under the status bar (pixel-scanned at y=84-85,
     rgb(224,224,224)); desktop keeps display none and no line. Our (0,3,1)
     re-hide needs no !important: the grid rule's display is a normal
     declaration and our style tag loads last. The header carries no children in
     hero (drawer entry is the FAB), so hiding it frees the dead 85px too. */
  [data-mobile-nav="frame"] [data-phase] header[class*="_headerHidden"] {
    display: none;
  }
  /* 0.1.6-alpha.2 renamed the hero-empty marker: headerHidden -> headerBlank
     (audit §1 row 3), so the rule above is a dead needle on alpha.2 and this
     one is dead on rc hosts — together they cover both generations. Same
     (0,3,1) shape, same no-!important reasoning as above. */
  [data-mobile-nav="frame"] [data-phase] header[class*="headerBlank"] {
    display: none;
  }
  /* Header popovers resolve against the header, not against their 28px flow
     box. 0.1.5's background-job chip anchors its menu with
     position:absolute; top:calc(100% + 5px) inside .QsffPG_root
     {position:relative} — a 28px-tall chip — so the menu was laid out at
     x=-16 (our right:8px resolved against that 156px chip root) and then
     clipped twice: by our own overflow:hidden on the chip root and by the
     host's [data-dsh-responsive-part="session-title-cluster"]
     {overflow:hidden}. The chip still reported aria-expanded=true with
     nothing painted and nothing hit-testable: measured 2026-09-14 at 390px,
     menu rect [-16,49,336,40], elementFromPoint at its centre returned the
     view tabs row. A positioned header plus a static chip root puts the same
     menu at [46,77,336,73] — inside the viewport, its rows hit-testable, and
     an outside tap still dismisses it (menus 1 -> 0). On 0.1.6-alpha.2 the
     chip leaves the flow entirely (absolute, in the gated block below), and
     this rule's higher specificity ((0,6,2) vs the new (0,4,1)) would pin it
     static there too — so it is excluded on alpha.2 hosts via :not(:has(...))
     and keeps governing rc hosts (review 2026-09-19).
     BOTH halves are load-bearing: forcing the chip root static without
     positioning the header moves the containing block out to the frame, and
     the menu lands at x=8 y=849 — past the 844px viewport (A/B 2026-09-13).
     Scoped to the header actions slot, so the subagent lineage root inside
     the crumbs keeps its own anchored, fixed-position menu. */
  [data-mobile-nav="frame"] [data-phase] header:not(:has([class*="_headerLeading"])) [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    position: static !important;
  }
  /* The tab strip is a separate grid item from the title row and does not
     inherit the title row's inset, so after the header padding above went to 0
     it sat flush against the bezel (measured: tablist x=0, first tab 0..30
     while the title starts at 40). Give it the same left inset as the toggle so
     the two rows read as one column. */
  /* ---------- 手机档专属：头部留白收紧（≤767px + coarse）----------
     数值是照 360×754 真机量的（页签条 margin-top -4 / 页签下划线 5px /
     页签按钮去上内边距、靠底对齐 / 标题行回到内容高度）。**只对真·手机档生效**：
     768–1023 的平板档保持上游手机 UI 的排布，不套这台手机的魔数
     （仓库既有惯例，见文件末尾的 DSHA 预设块）。
     真机读数（修前 → 修后）：头部 77 → 67px、标题↔页签文字间距 22 → 15px。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_tabs"] {
      padding-left: 8px !important;
      /* 2026-09-23 店主："标题和下面『对话』中间的空白有点多"。
         宿主给这条页签条 margin-top:10px，页签按钮自己还带 padding-bottom:9px
         （给选中下划线留位），两行文字之间就空出一条。收紧：
         margin 归零（真机 4 → 0）+ 下划线贴到 5px（页签条 36 → 31px）。
         ⚠ 选择器必须用**后代**：页签条外面套了一层 display:contents 的 div
         （真机链：div.wSkVaW_tabs < div[0..0] < header.wSkVaW_header），
         所以原来的「header > [class*="wSkVaW_tabs"]」是条死规则 —— padding-left
         从来没生效过（现按后代写，值仍是实测的 8px，视觉不变）。 */
      margin-top: 0 !important;
      margin-bottom: 0 !important;
    }
    /* 2026-10-20 五轮（真机截图实测）：上面那条 5px 一直没生效 —— 宿主按钮自带
       padding: 0 0 9px，App 注入表又把它钉回 9px，所以文字离下划线 10px、离标题只有
       7px（店主：「太挤了，把对话那一行移动贴近下面那条线」）。改用宿主稳定的
       data-conversation-tabs 标记 + html body 前缀把特异性拉满，把下内边距压到 3px：
       文字随之下沉 6px —— 贴近分隔线，同时标题侧的留白回来。 */
    html body [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [data-conversation-tabs][role="tablist"] > button[role="tab"],
    html body [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [data-conversation-tabs] > button[class*="wSkVaW_tab"] {
      /* 2026-10-20：店主「先把刚刚的改动还原」——回到上一版定稿的 6 / 3
         （总高仍是 16 + 9 = 25px，文字下沉、下划线不动）。后续再按店主给的目标间距调。 */
      padding: 6px 0 3px !important;
    }
    /* 2026-10-20 修正：页签条的 margin-top **是有效的**（早前判定"无效"是误判 —— 当时
       与行高改动叠在一起，测到的位移被行高变化掩盖）。经验公式（设备像素）：
       间距 ≈ 67.5 + 3.81 × (row-gap + margin-top)；基线 margin-top 由本文件下方
       「header:has(_headerLeading) [role="tablist"] { margin-top: 4px }」 给出。
       终态 = row-gap 3.5 + margin-top −8 ⇒ 净 −4.5 ⇒ 实测 50（店主定稿）。 */
    html body [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [data-conversation-tabs][role="tablist"],
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-conversation-tabs][role="tablist"] {
      /* 必须压过本文件下方 header:has(_headerLeading) [role="tablist"] 的 4px（(0,4,1)），
         所以这里挂满 data 标记（(0,5,3)）；只写 [role="tablist"] 会输。 */
      margin-top: -8px !important;
    }
    /* 2026-10-20（终态）：「标题↔对话」间距的唯一有效旋钮是 header 的 row-gap。
       两行网格先回到内容高（App 注入表原本钉死 40px + 36px），再由 row-gap 撑缝：
       页签行整体下移、header 跟着长高 ⇒ 标题侧留白变大、对话到下方黑线的距离不变。
       实测（设备像素）：auto/0 → 37；+6px → 60；+3.5px → 50（店主定稿值）。
       页签条自身的 margin-top 被 App 表钉死，-4/-8/0/6px 四个值都无效，已放弃。 */
    html body [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header {
      grid-template-rows: auto auto !important;
      row-gap: 3.5px !important;
    }
    /* 页签行钉到内容高（16px 行高 + 5px 下划线位 = 21px）：文字与下划线贴住行底，
       不再在 36px 的老行高里居中，多出来的空白由上面的 row-gap 统一管理。 */
    html body [data-mobile-nav="frame"] [data-phase] header [role="tablist"] {
      min-height: 0 !important;
      height: 21px !important;
      align-items: flex-end !important;
    }

    /* 真机读数：头部的 grid-template-rows 被钉成固定的 40px 36px（宿主自己没写行高，
       是 DSHA 那张表给的），于是标题行、页签行都各留一截死空间。改成 auto：两行各自
       贴住内容，标题行按 36px 的预设 chip 走、页签行由页签按钮撑开。 */
    /* 标题行实测 40px 高，而里面最高的东西是 36px 的预设 chip（"标准模式"）——
       多出来的 4px 是死空间。让行高回到内容高度（用 auto + min-height:0，
       不写死 36：将来标题簇里出现更高的东西（子代理谱系等）也不会被裁）。 */
    html [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="wSkVaW_titleRow"] {
      height: auto !important;
      min-height: 0 !important;
    }
    /* 页签按钮文字上方还有 6px 空白（按钮被容器撑到 32px 高、文字居中）：去掉上内边距，
       下内边距 5px 已在上面钉住（下划线位置不变）。 */
    html [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="wSkVaW_tab"] {
      padding-top: 0 !important;
      align-self: flex-end !important;
    }
  }
  /* NOTHING extra here on purpose. The header's own padding is already forced
     to 0 above, and the title row carries padding-left:40px of its own, so the
     title lands at x=40 - the toggle's right edge (36) plus 4px. A negative
     margin added on top of that over-corrected and pulled the title off the
     left edge (measured 2026-09-13: crumb x=20, and the string's first glyph
     painted partially outside the viewport), so the reclaim lives in exactly
     one place: the header padding. */

  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header > :first-child {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    gap: 2px;
    /* Just enough for the toggle (28px at left:8 -> right edge 36) plus 4px of
       breathing room; the host's 60px rail reservation is neutralised above. */
    padding-left: 40px;
  }
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header > :first-child > :first-child {
    display: flex !important;
    align-items: center;
    flex: 1 1 auto;
    min-width: 0;
    gap: 2px;
  }
  /* The directory toggle stays at the far left of the header. */
  [data-mobile-nav="toggle"] {
    position: absolute !important;
    left: 8px !important;
    top: 12px !important;
    z-index: 2 !important;
  }
  /* The files opener is pinned to the header's right corner, mirroring the
     directory toggle on the left (same 8px edge, same 12px seat). In flow it
     can never reach that corner: the host reserves the last 44px of the title
     cluster for a utilities seat that is EMPTY on mobile - measured at 390px,
     headerUtilities sits at x=374 with width 0 while the title cluster carries
     padding-right: 44px - so the button stopped at x=300..328 and left 62px of
     bare header to its right (2026-09-14 phone-side report: the opener is not
     pinned to the top-right corner). Absolute positioning also returns its
     28px of flow width to the title lane, and the containing block is the same
     one the toggle resolves against, so both controls shift together with the
     frame's safe-area padding. The 44px reservation itself is trimmed to the
     28px band this button actually paints in the compact-rows block below, so
     the title lane keeps the difference. */
  [data-mobile-nav="files"] {
    position: absolute !important;
    right: 8px !important;
    left: auto !important;
    top: 12px !important;
    z-index: 2 !important;
  }
  /* 手机档（≤767 + coarse）：文件夹按钮右移 4px（原 8px），为标题行右簇再腾出空间。
     与左侧 ☰ 的 8px 不再对称，是用户明确接受的取舍（2026-10-12 用户规格）。
     平板档这一档没被要求动，所以留在这条手机档作用域里；共用档保持本批之前的值。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="files"] {
      right: 4px !important;
    }
  }
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="_headerActions"] {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    flex: 0 1 auto;
    min-width: 0;
    max-width: calc(100% - 32px);
    margin-left: auto;
    justify-content: flex-end;
    gap: 2px;
  }
  /* The title takes the remaining width and never paints outside it; the
     metadata lane's mode text is what shrinks first. */
  /* min-width is a readable floor (2026-09-13 phone report: the title showed a
     single glyph then an ellipsis). This lane has flex basis 0, so it is the
     first thing every crowding neighbour eats: measured at 320px with a lineage
     chip in the row, the crumb client width collapsed to 16px and NOTHING of
     the title was painted. 30% of the row keeps 2-4 CJK glyphs plus the host's
     own ellipsis whatever else is pinned next to it. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
    flex: 1 1 0;
    min-width: 30%;
    max-width: none;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  /* Mode label: keep the icon AND the words. On a phone this chip is the only
     mode switcher there is, so its text is not the surplus it was once
     treated as: the longest preset name measured needs 121px including the
     18px icon seat, while the old cap min(22vw, 220px) allowed just 85.8px at
     390px — the text was clipped at every phone width even before the
     crowding rules below pinned it to the icon alone (2026-09-14 phone
     report: the mode label showed only its glyph). 38vw keeps the label whole
     from 320px up and still lets it ellipsize before the title on wider
     screens. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_label"]:has(> svg) {
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
  [data-mobile-nav="frame"] [data-phase] header [class*="_label"]:has(> svg) > svg {
    position: absolute !important;
    left: 0 !important;
    top: 50% !important;
    transform: translateY(-50%) !important;
  }
  /* Running/subagent controls keep their full status text and hit area; they
     do not give up width to the mode label. NOTE: the real subagent lineage
     root has class="ZKlsPq_root " — a TRAILING SPACE from the plugin's
     template-literal className — so [class$="_root"] never matches it. Use
     [class*="_root"] and exclude the switcher root ([class*="_switcherRoot"])
     so only the count/job roots get pinned (the switcher must stay shrinkable
     so its own title can ellipsize). */
  /* Pinned (flex 0 0 auto) with a max-width cap. A shrinkable chip is squeezed
     below its content and the count reads as clipped or overwritten (the
     2026-08-22 report), while a bare max-content pin eats the session title,
     whose flex basis is 0: measured 2026-09-13 at 320px, the crumb went 68px
     -> 16px and the painted title was EMPTY while the chip kept its full text.
     Pinned + capped + the crumbs min-width floor above is what holds both —
     the title ellipsizes, the count keeps its words, and the hit area stays
     one inline-flex button.
     NOTE: the popover containment lives with the header rules above, which
     force this root position:static. That only works together with the
     positioned header: static on its own moved the containing block out to
     the frame and the menu landed at x=8 y=849, past the 844px viewport
     (A/B 2026-09-13). */
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    order: 2;
    flex: 0 0 auto;
    min-width: 0;
    max-width: min(40vw, 180px);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button {
    min-width: 0;
    max-width: 100%;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button > * {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button,
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button * {
    white-space: nowrap !important;
  }
  /* The lineage count's leading "/" (ZKlsPq_separator — official desktop
     chrome rendered only for a root session inside the crumbs) looks like a
     stray extra breadcrumb level on small screens; hide it. The crumbSep "/"
     between ancestry segments (subagent sessions) is a real separator and
     stays. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] [class*="_separator"] {
    display: none !important;
  }
  /* The header's right-hand slot clips its own dropdown away (0.1.5 host bug).
     wSkVaW_headerUtilities is a 44x44 grid cell with overflow:auto, and the host
     mounts its "More actions" menu INSIDE it: the menu is 218x52, so the cell
     clipped it to 44x44 and the menu was never painted and never hit-testable
     (measured: menu rect 156,56 218x52, computed flex/visible/opacity 1, yet
     elementsFromPoint at the item centre returned the view tabs row and nothing
     from the menu). Raising the menu z-index cannot help - the cell's own
     stacking context traps it. Releasing the overflow paints the menu where the
     host positioned it, and the item then works (verified: a real tap opening
     the session-log export dialog, menus 1 -> 0 dialogs 1). Scoped to the mobile
     branch and to this one cell, so desktop keeps the host layout. The section
     is hidden on mobile anyway - the drawer footer carries the same action - but
     the release stays for any plugin that registers a header dropdown here. */
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_headerUtilities"] {
    overflow: visible !important;
    /* The seat is empty on a phone (its only button is hidden just below) yet
       still 44px tall, which floors the whole title row — see the compact-rows
       block after the tab strip. */
    height: 30px !important;
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_headerUtilities"] [class*="nL4_yW_moreButton"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [data-mobile-nav="files"] {
    width: 28px;
  }
  /* Session log download: gone from the header row on mobile (the utilities
     seat holds only the session-log-export capsule).
     ⚠ 作用域必须是会话顶栏本身（header.wSkVaW_header），不能写成裸 header：
     提问卡（dsh-client-ui-user-questions）的 Mbwy4a_frame 就渲染在 composerSeat 里、
     也在 [data-phase] 之下，它的 Mbwy4a_header > headingBlock > :last-child 正是问题正文
     <h2>。裸 header 版本把问题正文整行 display:none —— 店主「你提的问题被吞掉了」即此
     （2026-10-20 真机 DOM 取证：h2 computed display:none，逐条禁用本样式表内的规则定位到
     这条）。顶栏的签名类由宿主 ConversationRoot.module.css 的 .header 提供（wSkVaW_header），
     本文件其它顶栏规则同样以它/其子类作代际锚。 */
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header > :first-child > :last-child {
    display: none !important;
  }
  /* View tabs strip (official [role="tablist"] under the crumbs row).
     Desktop ships a single flex row (gap: 36) sized for the two stock tabs
     (对话/轨迹). Plugins register further views (memory / skill / todo
     panels, per-plugin settings pages), and once the count passes two the
     shrinkable buttons collapse to their min-content: CJK labels stack one
     glyph per line (staircase), latin labels break word-per-line — the
     strip eats a screenful of vertical space (#41, 8 tabs, HarmonyOS
     browser). Scroll the strip horizontally instead — the standard mobile
     tab-bar pattern — with every label kept whole (flex-shrink: 0 +
     nowrap). Affordance is the peek: the naturally cut-off tab at the right
     edge says "more this way" (unlike the settings navList, whose buttons
     nearly fit and would show no cut edge), which is why this strip scrolls
     while that one wraps. touch-action: pan-x opts the strip into
     horizontal panning — the root's pan-y intersection stops at this first
     scroll container (same mechanism as the drawer's pan-y), so the page
     never scrolls sideways. overscroll-behavior-x: contain stops a flick
     from chaining past the ends; snap keeps tabs edge-aligned after a
     fling; the scrollbar stays hidden like every native tab bar. */
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"] {
    flex-wrap: nowrap;
    gap: 0 16px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scroll-snap-type: x proximity;
    touch-action: pan-x;
    scrollbar-width: none;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"]::-webkit-scrollbar {
    display: none;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"] > button {
    flex-shrink: 0;
    white-space: nowrap;
    scroll-snap-align: start;
  }
  /* Compact session header rows (2026-09-14 phone report: the top is very
     empty). The host's own mobile sheet lays the header out as
     grid-template-rows: minmax(32px, auto) minmax(44px, auto) with
     [role="tab"] { min-height: 44px }, and both rows then grow to 44: the
     title row is floored by the empty utilities seat above, the tab row by the
     buttons' own floor. Measured at 390px: header 97px = 8 padding + 44 + 44 +
     1 border, for 36px of painted content. Capping the rows at 36/32 and the
     tabs at their own content height gives 77px, with nothing else degraded —
     title, mode text, status chips, chevrons and both pinned corner buttons
     keep their measured geometry, and the tab strip keeps its #41 contract
     (horizontal scroll, 16px gap, whole labels, pan-x).
     The host's 8px padding-top is deliberately kept: the title row's 28px
     content then centres at y=26, exactly the centre of the pinned corner
     controls (toggle and Files opener both sit at top:12, 28px tall). Trimming
     that padding to 4 shaved 4 more px but left the text row visibly riding
     above both buttons (2026-09-14 phone report: the text row sits too high
     against the drawer and Files controls), so the row height is what pays for
     the compaction, not the alignment.
     :has(> *) guards the hero header: it is an EMPTY, host-hidden grid that
     still occupies 85px while the composer is laid out under it. In the hero
     the header has 0 element children, so the guard leaves it at its official
     height — measured, the hero composer rect [0,349,388,231] is identical
     with and without this block. */
  [data-mobile-nav="frame"] [data-phase] header:has(> *) {
    min-height: 0 !important;
    /* 2026-09-23 二轮：页签行地板 32 → 26（店主："标题和下面『对话』中间空白有点多"）。
       标题行地板保持 36 —— 它下面的文字要跟 top:6 的圆形按钮对齐（实测文字中心
       y=20 = 圆形按钮中心），压标题行会把文字顶得比按钮高（2026-09-14 已踩过）。
       页签按钮的 32px 地板同理下到 26px（文字 16px + 下划线留 5px），
       页签条的下沿随之从 76 收到 66。 */
    grid-template-rows: minmax(36px, auto) minmax(32px, auto) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tab"] {
    min-height: 32px !important;
  }
  /* 手机档专属（≤767px + coarse）：页签行地板 32 → 26（下划线收到 5px 后仍够点）。
     平板档保留 32px 的既有值，不跟手机一起压。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has(> *) {
      grid-template-rows: minmax(36px, auto) minmax(26px, auto) !important;
    }
    [data-mobile-nav="frame"] [data-phase] header [role="tab"] {
      min-height: 26px !important;
    }
  }
  /* The title cluster reserves its last 44px for that empty utilities seat.
     The lane's right edge must stay clear of the Files opener's HIT BOX,
     otherwise the opener eats the trailing chips' taps. The pre-2026-09-22
     value (26px, a width optimisation) only cleared at the 390px test width:
     measured at 360x754 (dpr 4) the Agent Team chip ran to x=334 while the
     opener's 36px box started at x=316 — an 18px overlap, so tapping the
     chip's tail opened the Files panel. The reference phone UI shows the lane
     ending at ~314 with the opener box at 316..352, i.e. the full 44px seat
     plus 2px of breathing room, so restore that instead of narrowing the
     opener: 46px clears the 36px box at right:8 by 2px at every width. */
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_titleCluster"] {
    /* 本批之前的值。2026-10-12/13 那串「46 → 40 → 36 → 32 → 26 → 34」的实验
       **只针对手机档**（后台任务圆点搬进标题行后与预设 chip 的间距），已整串
       搬进下面那条手机档作用域；共用档（= 平板档）保持本批之前的 46px 不动。
       预留从 padding 改记 margin（2026-10-17）：标题簇是 overflow:auto 的滚动
       容器，内容溢出时的裁剪线是 **padding box** —— 写在 padding 上会留下 46px
       的「可绘制溢出区」，挤爆的那一截 chip 正好画进预留带、被绝对定位的文件
       按钮压住（真机症状：预设 chip 末字被文件夹图标盖住）。改记 margin 后簇的
       border box 收在预留带左侧，溢出再大也止步于按钮之前，内容宽度不变
       （345 − 46 ⇄ 299 − 0）。 */
    padding-right: 0 !important;
    margin-right: 46px !important;
  }
  /* Header crowding on narrow phones.
     Three tenants want the same row: the session title, the mode chip and the
     status chips. The mode chip is the only mode switcher a phone has and the
     title is the only session identity, so both keep their words and the title
     ellipsizes instead (measured 2026-09-14 at 390px with a lineage chip
     present: after this the mode label keeps 101px of text and the crumb
     135px).
     The lineage root (dsh-client-ui-subagent) sits in the crumbs for BOTH
     running and idle descendants, so the guards below key on that root rather
     than the transient running-state dot — otherwise the row would reflow the
     moment agents go idle. Match roots with [class*="_root"] (the real class
     carries a trailing space; [class$="_root"] matches nothing).
     The background-job label used to yield here through two width-gated rules
     (<=440px, and <=559px when the lineage chip was present). Both were keyed
     on the chip's classed wrapper, which host 0.2.1 no longer renders, so they
     are deleted: the label is now hidden unconditionally by the job-chip rule
     in the phone-scope section (see header-job-chip-collapse). */
  /* With the subagent lineage (any state) AND a background job present
     together, 390px cannot hold the title, the mode words and the lineage
     count at once; the crumb gives 8px back to the action lane. */
  @media (max-width: 559px) {
    [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
      padding-right: 8px;
    }
  }
  /* Last resort on 320px-class screens: the title and both status chips cannot
     share the row with the mode words, so the mode chip keeps only its icon. */
  @media (max-width: 359px) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      display: none !important;
    }
  }

  /* --- 顶栏状态 chip 座位契约：**暂缓**（2026-10-12 受控实验后整块撤回）---
     用户规格：「子智能体 chip 固定不变，后台任务 chip 出现在它左侧、间隔 2px」。
     首版用 flex 容器 + order + gap 实现，**受控实验证明该机制不成立**：
     子智能体 chip（ZKlsPq_root）在 alpha.2 代宿主上被插件自己钉成 position:absolute，
     绝对定位元素脱离文档流，flex 的 order 与容器 gap 都决定不了它的落点 ——
     注入一颗合成 jobs chip 后该 chip 实测从 x=292 跳到 x=174（视觉间距 −211px），
     三个目标点无一达成；容器 computed gap 也仍是宿主自己的 4px。
     真实 jobs chip 无法在受控环境构造（宿主在后台任务数为 0 时整颗不渲染），
     故本条**已整块撤回**，等拿到「真机带后台任务的截图 + 视觉稿」后按绝对定位口径重做。
     撤回是安全的：本组只在两颗 chip 同时在场时匹配，撤掉后单 chip 形态回到既有那批
     规则的管辖（受控实验：单 chip 的 rect 与撤前逐像素一致，规则匹配数=0）。 */

  /* --- Header popovers on mobile (dsh-client-ui-jobs / dsh-client-ui-subagent) --- */
  /* Both entries sit in the session header and both anchor their panel to the
     trigger's left edge (left:0 inside their own root), so clamp them to the
     viewport. The background-job menu resolves against the header (see the
     containment rules at the top of this section) and the subagent lineage
     menu is position:fixed, so right:8px pins either panel 8px from the
     phone's right edge: measured [46,77,336,73] for the job menu and
     [38,41,336,58] for the lineage menu at 390px, both fully inside the
     viewport. Do NOT clamp with left:8px: measured, that put the panel at
     x=350..686 (off-screen) against a right-anchored x=30..366. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_menu"] {
    left: auto !important;
    right: 8px !important;
    width: min(336px, calc(100vw - 16px));
    max-width: none;
    max-height: min(420px, calc(100dvh - 120px));
  }

  /* --- 0.1.6-alpha.2 session-header adaptation (audited on a real device) ---
     The 16-item reconciliation in docs/upstream/2026-09-19-mobile-header-0.1.6-adaptation.md,
     landing the 14 items whose anchors exist in 0.1.6-alpha.2 host builds. Two preset items
     (#6/#7) are deliberately omitted: they anchor on .dsha-preset-header-anchor, a marker
     that exists only in the DSHA build, so they would be dead rules here.
     GENERATION GATING: only _headerLeading/_crumbCurrent/_crumbSeg/_headerCorner are
     alpha.2-only classes — every other anchor below (_titleCluster/_crumbs/_headerActions/
     _headerUtilities/tablist/QsffPG_/ZKlsPq_ and the :first-child chains) also exists on
     0.1.5-rc hosts, where these rules would silently re-tune geometry the older rules
     measured (review 2026-09-19). Every selector therefore carries
     header:has([class*="_headerLeading"]): the whole block is dead on pre-alpha.2 hosts and
     the rc-generation rules keep governing there unchanged. */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    /* 顶部留白收窄：宿主 header 自带 padding-top: 10px、标题行再垫 2px，
       叠在刘海/状态栏避让之上就显空。这两处一起清零。 */
    padding-left: 8px !important;
    padding-right: 8px !important;
    padding-top: 0 !important;
    /* 宿主 header 有 min-height: 76px，而内容只有 ~69px，底部会垫出 7.6px 空白
       （实测：标签行底边 106，header 底边 113.6）。贴底定位的状态 chip 会被这
       段空白顶下去、和标签行错开。手机上让 header 贴住内容高度。 */
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child {
    flex-wrap: nowrap !important;
    align-items: center !important;
    gap: 0 !important;
    padding-left: 32px !important;
    padding-right: 0 !important;
    padding-top: 0 !important;
  }
  /* 目录开关跟着一起上移，保持与标题/按钮同一行居中。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="toggle"] {
    top: 6px !important;
  }
   /* 空座位不判空、只塌宽：a2 槽位渲染器永远在 headerLeading 里挂一个
      [data-slot] 包装元素（display:contents、0×0），:empty 与 :not(:has(*))
      两种「空」判定都恒不命中（宿主自己的 :empty 规则同样失效），而
      display:none 又会在某代真的渲染控件时误藏真控件。这里不判定空不空，
      只把第三方误标进来的预留 padding 塌掉——web-all 兼容层按 0.1.5 结构
      把本座位误标成 session-title-cluster，注入 padding-inline-end:44px，
      座位于是 0 内容 + 44 padding = 44px 死占（实测 390px：座位
      [40,22,44,0]、titleCluster 被顶到 x=84）。padding 归零后空座位 = 0×0，
      真有内容的宿主也不受影响（内容盒照常渲染）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerLeading"] {
    /* 不能塌掉左侧座位本身：上面那条（> :first-child）给本座位留了
       padding-left:32px 作面板开关的座位，而 padding: 0 !important 是
       简写，会把它一并清零。两条规则特异性同为 (0,4,1)，按源序本块在后
       ⇒ 简写胜出，座位塌成 0×0、网格第一列 0px、标题直接压到 left:8 的
       面板按钮上（真机实测 2026-09-22，360x754@4：crumbs x=8、
       toggle 8,6 28x28；真机 DOM 规则枚举确认胜出者就是本块）。
       NOTE: 本文件整体是 JS 模板字符串，注释里绝不能出现反引号。 */
    padding: 0 !important;
    padding-left: 32px !important;
  }
  /* --- 0.2.0-rc.x：header 变 grid，座位从 titleRow 内部搬到了 header 自己的第一列 ---
     上面两条把「面板开关的 32px 座位」记在座位自己身上（padding-left）。在
     0.1.6-alpha.2 里 headerLeading 是 titleRow 的第一个孩子，这个 padding 就是
     titleRow 内容盒的起点，标题的 40px 落点是它直接算出来的。0.2.0-rc.x 把 header
     改成 grid-template-columns: auto minmax(0,1fr)：座位成了 header 的直接孩子
     （grid-area: 1/1），titleRow 独占第二列（grid-column: 2），于是标题的 40px 落点
     变成「网格第一列恰好 32px」的副作用——座位一旦被谁塌掉（padding 被覆盖、宽度被
     第三方归零、display 被藏），第一列就回到 0px，标题立刻压到 left:8 的圆形开关上。
     2026-09-22 在 0.1.6 上实测过同一个失败面（座位被 padding 简写清零 → 第一列 0px
     → crumbs x=8、toggle 8,6 28x28），当时靠把 padding-left 补回来止住；这一代改成
     把座位记在标题行自己身上：padding-left 加在 titleRow 上（border-box，不撑破第二
     列），座位格归零，标题落点不再依赖座位格存在、也不依赖它的宽度算得对不对。
     判别式是「座位是不是 header 的直接孩子」——0.1.6 及更早座位在 titleRow 内部，
     整块不命中，老规则原样管老宿主。
     附带修掉一条静默漂移：老规则「header > :first-child > :last-child { display:none }」
     在 0.1.5 藏的是会话日志胶囊；0.2.0 里 header 的第一格是座位，它最后一个孩子是
     conversation.header.leading 槽的包装元素，整条规则于是变成「藏掉整个 leading 槽」
     （实测：往槽里注入 28x28 按钮，计算盒 0x0）。手机档这一格是插件自己的抽屉开关
     （绝对定位 left:8），槽里的控件必然和它叠在一起，所以「藏」的结果是对的、理由
     一直没写下来；这里按槽名显式藏，别再靠 :last-child 误伤。
     NOTE: 本文件整体是 JS 模板字符串，注释里绝不能出现反引号。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has(> [data-conversation-header-leading]) > [data-conversation-header-leading] {
    width: auto !important;
    min-width: 0 !important;
    padding: 0 !important;
    gap: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has(> [data-conversation-header-leading]) [data-slot="conversation.header.leading"] {
    display: none !important;
  }
  /* 这里用 [class*="_titleRow"] 后缀形而不是邻近两条的 [class*="wSkVaW_titleRow"]：
     全包只有 wSkVaW_titleRow 含这个片段（无前缀重叠，且已被 header:has 门控），
     后缀形对哈希前缀改名免疫——座位记错地方的代价是标题静默压回开关上，不值得
     为风格统一冒这个险。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has(> [data-conversation-header-leading]) [class*="_titleRow"] {
    box-sizing: border-box !important;
    padding-left: 32px !important;
  }
  /* 0.1.6 的新头部里，titleRow 的第一个孩子是新增的空座位
     headerLeading（macOS 桌面控件，安卓上渲染 null）。插件按 0.1.5 老结构
     写的「header > :first-child > :first-child { flex: 1 1 auto }」现在套在
     这个空座位上，于是它吃掉全部剩余宽度、把标题顶到右侧（实测 411px 宽
     屏幕上标题被推到 131px 处）。让它不参与伸缩即可——有内容时也不会塌。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child > :first-child {
    flex: 0 0 auto !important;
    width: auto !important;
    min-width: 0 !important;
    gap: 0 !important;
  }
   /* 第三方兼容层（@linxin666/dsh-web-all 的 web-ui-compat 行）按 0.1.5 结构
      把本代 titleCluster 误标成 session-utilities，给里面所有按钮注入
      min-width/min-height:44px + flex:none：toggle/files、模式/团队/面包屑
      按钮全被顶成 44 —— toggle (8,6,44,44) 中心 28、files (338,2,44,44)
      中心 24、标题带中心 22 三心不齐；files 加宽后越过 headerActions 流右缘
      6px（338 < 344）。宿主 0.1.6-alpha.2 自身没有任何 44px 下限（全包
      grep 零命中），这里把外来下限归零：控件回到各自设计尺寸（toggle 28
      来自 base.css、files 36 来自下面的 a2 专条、chips/面包屑回宿主自然
      高度），三心回到 20，titleCluster 的 min-height:40 !important 重新
      主导行高。QsffPG/ZKlsPq 两个状态 chip 用 :not 明确豁免：它们的
      25px 下限由后面 min-height:25px !important 专条供给，特异性 (0,4,1)
      低于本条 (0,7,1)，不豁免会被顺手压掉，不靠书写顺序。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] :is(button, [role="button"]):not([class*="QsffPG_root"] button):not([class*="ZKlsPq_root"] button) {
    min-width: 0 !important;
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
    display: flex !important;
    flex-wrap: nowrap !important;
    flex: 1 1 auto !important;
    width: auto !important;
    max-width: none !important;
    min-width: 0 !important;
    min-height: 40px !important;
    /* 三级间隙 6 → 4（2026-09-23 用户拍板）：团队 chip 在手机档被宿主
       @container(width<=480px) 藏掉标签、只剩 14px 图标（对账见 §6），
       这一行不再需要 6px 的呼吸量；收成 4px 让「模式 / 团队 / 文件夹」
       看起来是一组。 */
    /* 本批之前的值。2026-10-12 的「标题这一行三个之间放宽到 6px」只针对手机档
       （后台任务圆点搬进标题行后与预设 chip 的间距），已搬进手机档作用域。 */
    gap: 0 4px !important;
    justify-content: flex-start !important;
    align-items: center !important;
    /* 簇溢出守卫，随断点 A 无条件化并入本显示规则（原为独立条）：极端
       字体下 crumbs 触地板后的残余溢出保持可横滑，不依赖 web-all 垫片
       （缺席时簇溢出默认 visible，会压画到 corner 按钮上）；内容放得下时
       本声明完全惰性。x:auto 把 y 也算成 auto，簇内容高 ≤40px 恒不纵溢
       无实害；findHorizontalScroller 对 overflow-x 容器让位。 */
    overflow-x: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_crumbs"] {
    /* 标题改成自适应：面包屑条吃掉动作区之外的剩余宽度，标题多长就显示多少，
       装不下时由每一段自己的滑动窗口（见下）横向滑。min-width 保底 4 字，
       防止预设名字很长时把标题挤没。 */
    flex: 1 1 auto !important;
    width: auto !important;
    min-width: 72px !important;
    max-width: none !important;
    margin-left: 0 !important;
    margin-right: 0 !important;
    min-height: 0 !important;
    padding-right: 0 !important;
    overflow: visible !important;
    white-space: nowrap !important;
  }
  /* 标题本体：自适应宽度 + 横向滑动。宽度由上面面包屑条的剩余空间决定，
     装不下时在本段内左右滑（touch-action: pan-x 让浏览器先认领横滑，
     左缘抽屉手势不会抢走这一笔）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
    flex: 0 1 auto !important;
    width: auto !important;
    min-width: 0 !important;
    /* 6 个汉字上限：6×14px + 左右 padding 16px = 100px。再长就在本段内横滑，
       这样标题永远不会顶到右侧的预设。 */
    /* 本批之前的值（100px 横滑窗 + 硬切）。2026-10-12 的「缩到 80px 再改省略号、
       最后取消硬顶改 max-width: 100%」只针对手机档（子智能体 chip 进标题行后
       要让出约 100px），已整串搬进手机档作用域；共用档（= 平板档）保持不动。 */
    max-width: 100px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    text-overflow: clip !important;
    white-space: nowrap !important;
    text-align: left !important;
    justify-content: flex-start !important;
    touch-action: pan-x !important;
    overscroll-behavior-x: contain !important;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"]::-webkit-scrollbar {
    display: none;
  }
  /* 面包屑的父会话段同样是 <button>，不设窗口就会顶出去（子代理会话实测）。 */
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
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbSeg"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    justify-content: flex-start !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
    /* 断点 A（用户拍板 2026-09-19，全移动档无条件生效）：动作行不参与收缩，
       chips 按自然宽渲染，收缩职责全数交还 crumbs 滑动窗口当避震器。
       根因链：内容是字体相对的、预算是固定像素的——headless（CJK
       fallback 字体）文字窄、真机（另叠加 Android fontScale）文字宽，
       flex:0 1 auto 按 basis 比例收缩时行内唯一无下限的项是模式 label
       （min-width:0），真机截成「创造…」「Agent Te…」而 headless 全字。
       为什么无条件化：首版用 min-width:377 分档（按 390 假设视口的 k≈1.2
       破坏点推演），真机 diag 读数证伪——设备实测视口 360、dpr 3.5
       （vivo V2425A，Android 16，Chrome 151），整台设备落在档位之下，A 档
       从未绘制、旧收缩机制照跑、芯片照压；同一读数里无门的 stats 规则真机
       验证生效、A 档未生效，对照坐实是分档包裹死档而非声明无效。目标任何
       手机宽度芯片全字、极端窄屏靠滑窗降级不靠截断——分档与目标矛盾，删，
       flex 直接并入本几何规则唯一声明。新几何：lane 停缩后行内唯一可缩项
       是 crumbs（flex 1 1 auto，地板 72px；窗口帽 max-width:100px 是字体
       无关盒子，窗内 pan-x 滑动保证长标题可读），避震容量 = crumbs 自然
       宽−72（根会话约 28px、子代理会话双窗最多 128px；360 真机肥字体
       k>1.22 时 crumbs 触地板、残余走 cluster 横滑）。级联核查：本规则是
       全档唯一 flex 来源、无其他 flex 分量；rc 代 840 行是普通权重且
       prelude 不同（无 :has 门），被本条 importance 压制，无 order-tie。 */
    flex: 0 0 auto !important;
    width: auto !important;
    max-width: none !important;
    min-height: 36px !important;
    margin-left: auto !important;
    padding: 0 !important;
    border-top: 0 !important;
    justify-content: flex-end !important;
    /* 与 titleCluster 同步收到 4px（2026-09-23）：动作行里的 chip（任务 /
       谱系 / 团队）之间也只留 4px。 */
    /* 本批之前的值。2026-10-12 的「动作行内各 chip 之间放宽到 6px」只针对手机档，
       已搬进手机档作用域。 */
    gap: 4px !important;
    overflow-x: auto !important;
    scrollbar-width: none;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"]::-webkit-scrollbar {
    display: none;
  }
  /* stats 行左端「N 轮」被裁且不可达（用户真机两帧 + headless 390 复现）：
     宿主 bOPqQW_root 是 justify-content:center 的横向滚动容器，内容溢出
     49px（scrollWidth 333 / clientWidth 284）时两侧对称各裁 ~50px——右侧
     scrollLeft 最大 49 可达，左侧起点 x=-34 是负坐标、scrollLeft 恒 ≥0
     永不可达，center+overflow 经典陷阱。改 flex-start 后溢出全落在右侧，
     滑动全程可达；取舍：内容放得下时行内从宿主的居中变左对齐（视觉差异
     仅空隙分布），功能缺陷（指标永久丢一段）优先。特异性 (0,3,0) 带
     !important 胜宿主 (0,1,0) 普通声明，与书写顺序无关；data-mobile-nav=
     "stats" 是 stats-line 效果打的稳定标记，无哈希、跨宿主代际可用。
     本条置于 ①嵌套块外：裁切陷阱与断点 A 的档位无关，全移动宽度生效。 */
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] {
    justify-content: flex-start !important;
    /* 指标行的空隙在手机上偏松（2026-10-07 店主截图：「距离缩一点，更紧致一点」）。
       宿主 dsh-client-ui-chat 的 StatsPills 叠了三层：root 的 12px 列间距 +
       sep 的 6px 两侧 margin + pill 的 8px 内边距 ⇒ 390px 实测两个指标之间的
       视觉空隙 ~28px。手机档收成 gap 6 / sep 2 / pill 6：同组内 ~14px、组间
       6px，仍然留得住「·」的读法，pill 的垂直尺寸没动（触控高度由宿主
       line-height 决定）。类名前缀 bOPqQW_ 会随宿主换代，故用子串锚并整条
       限定在本插件自己的 stats 标记内：换代只会让规则惰性化，不会误伤别处。 */
    gap: 6px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] [class*="_sep"] {
    margin: 0 2px !important;
  }
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] [class*="_pill"] {
    padding-left: 6px !important;
    padding-right: 6px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
    /* Keep the 36px seat the reference phone UI shows (opener box 316..352 at
       360px, icon 326..342): it is the geometry the lane's 46px reservation
       above is tuned against. Mirror the toggle's centre (top:6px for a 28px
       control -> centre y=20) by lifting the taller box to top:2px.
       本批之前的值；2026-10-12 的「座位 36→30 + top 2→5」只针对手机档，见手机档作用域。 */
    width: 36px !important;
    height: 36px !important;
    flex: 0 0 36px !important;
    top: 2px !important;
  }
  /* 手机档（≤767 + coarse）：2026-10-12 用户规格 —— 座位 36 → 30px（原来比左侧 ☰
     的 28px 胖一圈，用户指出"外部灰色胶囊"偏大）。top 由 2 改 5，保持图标中心仍在
     y=20，与 ☰ 同一水平线。平板档没被要求动，仍是 36px / top:2。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
      width: 30px !important;
      height: 30px !important;
      flex: 0 0 30px !important;
      top: 5px !important;
    }
  }
  /* 新宿主把「右侧栏入口」放进了 titleRow 的 headerCorner。手机上市宿右侧栏
     就是 Files 面板，所以它和插件的文件按钮是同一个面板的两个入口；而它带
     margin-right:-16px，36px 盒子在 360px 视口下会从文件按钮右侧漏出一角
     （2026-09-22 实测：corner [332,2 36x36]、图标 343..358 外露，被视口裁切），
     与参考图"右上角只有一个文件夹图标"不一致，也与插件自己的文件按钮重复。
     只针对标题行内的 corner，老一代宿主（corner 是唯一入口）不受影响。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="wSkVaW_titleRow"] > [class*="_headerCorner"] {
    display: none !important;
  }
  /* 右上角换人：0.1.6 把「右侧栏展开按钮」放进了 headerCorner，而插件的
     老规则「header > :first-child > :last-child 显示 none」在 0.1.5
     藏的是「会话日志胶囊」；新结构里 titleRow 的 :last-child 变成 corner，
     于是右侧栏入口被误藏、面板在手机上打不开。这里把 corner 放出来，
     同时让出「⋯」菜单那一格（360px 一行塞不下两个）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child > :last-child[class*="_headerCorner"] {
    display: flex !important;
    flex: 0 0 auto !important;
    margin-left: 4px !important;
    margin-right: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerCorner"] button {
    width: 36px !important;
    height: 36px !important;
    min-width: 36px !important;
    min-height: 36px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerUtilities"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] {
    width: 100% !important;
    margin-top: 4px !important;
  }
  /* 状态 chip 的座位：
     · 子代理谱系 chip（dsh-client-ui-subagent 的 ZKlsPq_root）—— 绝对定位到
       「对话/轨迹」行右侧；
     · 后台任务 chip（dsh-client-ui-jobs）—— 2026-10-17 起不再搬座位：宿主
       0.2.1 只渲染无类名包裹 div，旧代 QsffPG_root 那套座位规则已整块删除，
       现行规则见本条下方的 header-job-chip-collapse。
    谱系 chip 若不搬，会和标题窗口 + 预设 + 文件抢同一条 flex，实测直接叠在
     一起（进子代理会话时最明显）；动作行因此只留 [预设][团队][文件]。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    position: relative !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] {
    padding-right: 8px !important;
    /* 宿主的标签行宽度是满宽、默认 content-box，加 padding 会把它顶到
       x=8..368（右缘越过 header 右缘 360 共 8px，header.scrollWidth-clientWidth=8），
       也就是下面那条 118px 预留里有 8px 落在屏外。补 border-box 把它收回来，
       预留才是"整整 118px"。 */
    box-sizing: border-box !important;
  }
  /* 2026-10-12（受控实测后删除）：原「jobs 在场 ⇒ tablist 右侧预留 118px」规则
     被证明是**惰性的** —— 同一 DOM 上切 8px ↔ 118px，tablist 的 rect、clientWidth 与
     注入元素的 x **差量全为 0**。原因：本表 box-sizing 为 border-box，padding 落在声明
     宽度之内、不缩小 flex 容器的 content box；而 tablist 是 overflow-x 为 auto 的滚动容器，
     子元素可以排进 padding 区 ⇒ 预留区挡不住任何视觉重叠，只让 scrollWidth 多 110px，
     并在溢出后造成整行左移 8px（临界从 159 抬到 167）。
     实测临界（393×844，合成"第三方 chip"）：无 jobs 时 >159px 压到谱系 chip；
     有 jobs（74px 文案态）时 >83px 压到 jobs chip；标签行溢出要到 260px 才发生。
     要真正预留，border-box 下 padding 做不到，须用 margin-right 或
     width: calc(100% - Npx)（该替代方案未实测，属推断）；而 83px 这个上限的根源是
     jobs 的定位公式（左缘 = 谱系左缘 − 2 − jobs 宽），要腾更多得动定位而不是 padding。 */
   /* Agent Team chip（VoX2oq_root，data-team-action）被 rc 代 pin 规则钉死
      （flex 0 0 auto + order 2，实测 98.7px），动作行里唯一可缩的模式 chip
      被压到 56.2px（390px 实测「创造模式」只剩「创造…」）。模式 chip 是
      手机端唯一的模式切换入口（pitfalls ⑤：必须保字），团队 chip 的完整
      文字在自己的面板里有承载（点开即达），所以让它先让：保持 order:2
      不变（创造在前、团队在后的次序不能翻），只把不可缩改成可缩，并加
      收缩下限保住图标点击区；内部省略号窗口由 rc 代的
      > button / > button > * 规则继续供给。特异性 (0,5,1) 高于 pin 规则
      (0,4,1)，且 !important，不依赖书写顺序；:has 门控保证 rc 宿主不命中。
      2026-09-23 下限 44 → 28（用户拍板）：宿主自己那条 @container(width<=480px)
      把标签藏了，手机档这颗 chip 实际只剩 14px 图标，44px 的盒子成了那一行
      最宽的空占位（真机 dpr 4：图标右缘 291 → 文件按钮图标左缘 326，观感 35px
      留白）。28 = 图标 14 + 宿主自带左右内边距 7（.VoX2oq_trigger padding），
      与本插件 toggle/files 同尺寸，不再额外扩拍击区。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
    flex: 0 1 auto !important;
    min-width: 28px !important;
  }
  /* 团队 chip 图标在「标准模式」与文件按钮之间居中（2026-09-26 用户拍板）。
     真机 360px / dpr 4 实测（无障碍盒 = 绘制盒）：标准模式 205..274、团队 chip
     278..306、文件按钮 316..352 —— 左缝 4、右缝 10，盒心 292 落在区间心 295 左侧。
     只做绘制层位移（宿主 .VoX2oq_root 本来就是 position:relative，不新增包含块、
     也不动它自己的弹层锚定），布局一个像素不变：46px 承重预留保持原样（见
     pitfalls「header 拥挤」），文件按钮不会被压。位移后两缝 7/7，图标正好居中。
     只在真·手机档生效：768–1023 平板档排布不同，不套这台手机的魔数。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
      left: 3px !important;
    }
  }
  /* 后台任务 chip 的**现行**规则（宿主 0.2.1 起）：上面那整个 QsffPG_root 族在该代
     静默失配 —— 宿主只渲染一个**无类名**的包裹 div，类名映射表里已经没有 root 键。
     失效后果实测：8 字文案（1 个后台任务运行中）回到标题行，标题触 72px 地板后动作
     簇仍放不下，溢出的 30px 画进文件按钮预留带、被文件夹图标压住（真机「顶部错位」）。
     用户规格（2026-10-17）：只留状态圆点 + 折叠箭头，位置排在预设与文件夹之间、
     智能体团队 chip（pin 为 order 2）的右边。锚改用「trigger 直接挂 _count」这一
     形状，不依赖包裹 div 的类名；槽位是 display:contents 的包裹节点，故不写直接
     子元素组合符。诊断与判据见 pitfalls「header 拥挤」。 */
  /* @patch
     id: header-job-chip-collapse
     for: dsh-client-ui-jobs 的后台任务 chip（0.2.1 起包裹 div 无类名）
     why: chip 由宿主作为 header.actions 槽的贡献节点渲染，类名与结构归宿主所有，
          插件只能在样式层收窄可见内容与序位
     until: 宿主给该 chip 稳定的数据标记，或在手机档自带折叠形态
     test: tests/a2-header-gating.test.ts
  */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] div:has(> button[class*="_trigger"] > [class*="_count"]) {
      order: 9 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] button[class*="_trigger"] > [class*="_count"] {
      display: none !important;
    }
  }
  /* 头部弹层定位（jobs 任务列表 / subagent 谱系 / 预设菜单都会命中的同一族）：
     插件老规则是「弹层左缘 = chip 左缘 + 8px」，那条规则成立的年代 chip 都
     贴着 header 左缘；现在标题窗口 72px + 子代理 chip + 预设都靠中右，336px
     宽的面板会被整体推到视口外 —— 点开就像没反应。
     统一改成视口定位：贴在 header 下方、左右各留 8px 满宽展开；顺带脱离
     headerActions 的 overflow 裁剪（绝对定位的面板会被那个 auto 裁掉）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_menu"]:not([class*="_menuAnchor"]) {
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 80px) !important;
    bottom: auto !important;
    width: auto !important;
    max-width: none !important;
    max-height: calc(100dvh - 96px) !important;
  }
  /* 官方 agent-team 插件（@deepseek-ai/dsh-experimental-client-ui-agent-team）的
     TeamAction 弹层：根元素 data-team-action（VoX2oq_root，挂在 headerActions 槽
     order 20），面板 VoX2oq_panel 是 absolute 弹层。它和上面 _menu 族栽在同一个
     裁剪问题上 —— _headerActions 的 overflow 滚动盒把它整个裁掉（实测 390/360px
     视口均不可见、关闭键落在视口外），但类名不含 _menu，上面那条规则救不到，
     所以这里同款视口定位脱离裁剪。哈希前缀 VoX2oq_ 跨版本会变，按仓库约定用
     _panel 子串匹配；不会误伤其他弹层 —— data-team-action 根标记只有 agent-team
     插件在用，特异性 (0,5,1) 也高于 _menu 族的 (0,4,1)。代际上整条已由外层
     header:has([class*="_headerLeading"]) 门控，pre-alpha.2 宿主不命中。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action] [class*="_panel"] {
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 80px) !important;
    bottom: auto !important;
    width: auto !important;
    max-width: none !important;
    /* 底部让位 composer 区：会话页 composer 卡顶缘实测 y=738、stats line
       到 840（844 视口，底部区共 106px）——原 max-height 100dvh-96px 让
       面板伸到 y=828，底部 90px 的任务列表被输入框盖住（2026-09-19 用户
       报障）。120px = composer 区 106px + 14px 呼吸间距；键盘弹出时 dvh
       收缩，面板随之再缩。 */
    max-height: calc(100dvh - 200px) !important;
    /* 面板虽被拖出头部渲染点，white-space 仍继承 0.1.6 头部的 nowrap
       （头部整行防换行是既有决策）——手机 374px 宽 + 长任务标题时内容
       单行撑出面板（实测 scrollWidth 541 / clientWidth 374，任务状态
       徽标被推到面板外 x=496 处）。恢复面板内正常换行。 */
    white-space: normal !important;
  }
  /* 子代理谱系 chip（ZKlsPq_root）：0.1.6 把它渲染在标题面包屑内部。进子代理
     会话时面包屑变成「父会话 / 当前会话」两段 + 这个 chip，动作行就叠在一起，
     所以整块搬到「对话/轨迹」这一行的空白区里居中，并与标签文字纵向对齐。
     后台任务 chip（同族的另一颗状态 chip）2026-10-17 起改由「trigger 直接挂
     _count」的形状锚收窄（见下方 header-job-chip-collapse）——宿主 0.2.1 不再给
     它的包裹 div 类名，QsffPG_root 那套座位规则已整块删除。 */
  /* @patch
     id: header-chip-seat
     for: 宿主 3.0.6 的 headerActions / titleCluster 槽位（子智能体谱系 chip 的座位）
     why: 槽位树由宿主 React 管理，本插件不能插入自有节点，只能靠 CSS 改排位
     until: 上游在标题行提供稳定的 actions/title 槽标记，或把该 chip 分到独立容器
     test: tests/a2-header-gating.test.ts
  */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] {
    position: absolute !important;
    /* 在「标签右侧的空白区」里居中（左边界让开对话/轨迹，约 104px），
       比整行居中往右一些。 */
    left: 104px !important;
    right: 8px !important;
    /* 纵向对齐标签：直接镜像标签的盒模型 —— 标签是「16px 行高 + 9px 下内边距」，
       总高 25px 且贴着 header 底边。chip 也做成 25px 高、bottom:0、下内边距 9px，
       内容区正好落在同一段 16px 里，文字必然与「对话/轨迹」齐平。 */
    bottom: 0 !important;
    /* 2026-10-20：页签行收到 21px（16px 行高 + 5px 下划线位）后，chip 的 25px 盒
       会比标签行高出 4px、文字随之上浮。这里镜像标签的盒：21px 高、贴底。 */
    height: 21px !important;
    min-height: 21px !important;
    align-items: stretch !important;
    z-index: 3 !important;
    margin: 0 auto !important;
    width: max-content !important;
    min-width: 0 !important;
    max-width: min(32vw, 116px) !important;
    flex: 0 0 auto !important;
  }
  /* 2026-10-12：原「后台任务在场 ⇒ 谱系 chip 让位到 right:126px」规则已删除。
     用户规格是主次对调（谱系 chip 固定不动、后台任务 chip 让到它左侧），
     让位一旦保留就会把谱系 chip 推走 —— 与规格直接冲突。2026-10-17 起后台
     任务 chip 已不在标签行（宿主去了类名、旧座位规则删除），让位更没有存在
     的理由。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] > button {
    height: 21px !important;
    min-height: 21px !important;
    line-height: 16px !important;
    /* 原 9px 下内边距是「贴 header 底边、与标签文字齐平」用的；搬进标题行后要改贴
       预设 chip 的竖直中心（与后台任务 chip 搬上去时同一处理）。 */
    /* 2026-10-12 用户规格：右侧内边距 4 → 2px，让 chip 的文字/箭头更靠近右缘。 */
    /* 下内边距 9 → 5，与页签按钮的 5px 下划线位一致：两者文字底边都离 header 底边 5px。 */
    padding: 0 4px 5px !important;
    align-items: center !important;
  }
  /* 已知边界：标签行出现第三个标签时，标签总宽约
     252px，已经越过子代理 chip 居中区的左边界（104px），两者会叠在一起。
     这里用 :has() 按标签数量切换策略 —— ≥3 个标签时不再居中，改成停靠在标签行
     右侧的空白区（右缘 8px；有后台任务 chip 时让到 126px）。标签行本身可横向
     滑动，chip 不会被挤到下面，也不再盖住第三个标签：
       chip 占 268~352（宽 84），标签止于 8+252=260，右侧余量 8px。
     两个变体并列，兼容「tab 是 tablist 直接子按钮」与「tab 被容器包裹」两种渲染；
     两条变体均 (0,5,2)（带 QsffPG 的二次覆盖规则为 (0,6,2)），高于上面两条既有规则，
     不依赖书写顺序。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] button:nth-of-type(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] > button:nth-child(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    left: auto !important;
    right: 8px !important;
    margin: 0 !important;
  }
  /* 2026-10-12：≥3 标签版的让位规则（原 right:126px）已一并删除 —— 同 Y1，
     主次对调后谱系 chip 不再为后台任务 chip 让位。 */
  /* 真机反馈：「标题下面多了一条灰色滑条」。第 4/5 条为了让长标题能左右拖着看，
     把面包屑做成了横向滚动容器 —— 实测 button.wSkVaW_crumb: overflow-x:auto、
     scrollWidth − clientWidth = 88；像素实测那条灰条是 x=40.0~89.5、高 7.8、
     拇指宽 ≈50 的圆角滚动条（100×100/188 ≈ 53，吻合）。
     本机 WebView 不认 scrollbar-width（CSS.supports 为 false），只有
     ::-webkit-scrollbar 生效；而且滚动条是「经典占位式」的 8px（合成容器实测
     offsetHeight − clientHeight = 8）。所以这里对整个会话头部统一掐掉滚动条：
     滑动能力保留，视觉上不再多一条。头部里任何位置的滚动条在 360px 宽的手机上
     都不是想要的，故不再按具体类名收窄范围。 */
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
  /* 单子代理运行态的 switcher 变体（宿主 SubagentHeaderLineage variant=
     "switcher"，根类是「基类 + 修饰类」双类 ZKlsPq_root ZKlsPq_switcherRoot，
     挂在 crumbs 内同一 lineage 槽位，a2 bundle line 615 实锤）：pin 规则按
     设计排除 _switcherRoot（切换器必须保持可缩），于是它从我方链里继承了
     零溢出约束——宿主 trigger 上限 max-width:244px 大于我方根帽 116px，
     根又没有 overflow，trigger 连同标题从右锚定的根左缘向右画出最多
     128px：真机 390 上文字冲到 ≈389、越过条带右缘 374，省略号点也在视口
     外，看起来像「无省略号」。聚合态「N 个子代理」类表不含 _switcherRoot，
     不被本条命中（结构锚区分，文本无关）。修法：根帽提到 min(46vw,180px)
     （数值可调，给运行中标题比计数 chip 更多余地）+ 根 overflow 收口 +
     trigger max-width:100%，让宿主自带的 title 省略号链（flex:1 +
     min-width:0 + ellipsis）在根内收口；svg 宿主自带 flex:none，⋮⋮/箭头
     图标与省略号共存；菜单是 position:fixed，不受根 overflow 裁剪，点击
     不受损。特异性与上面 ZKlsPq_root 规则同类同权 (0,4,1)，靠书写在后接管
     switcher 变体；h8S2Va 旧代是否有同名修饰类未取证，a2 (ZKlsPq_) 已实测
     对号。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] {
    max-width: min(46vw, 180px) !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] > button {
    max-width: 100% !important;
    min-width: 0 !important;
  }
  /* switcher 定位（用户拍板 2026-09-19：右靠 + 与 tab 基线对齐）：基础规则
     把 switcher 与聚合 chip 一起居中/让位，取证实测 179.4 宽被推到
     84.6..264，整个压进 tab 带（tab 按钮 y 44-76、中心 y=60）盖住轨迹/
     记忆两 tab。本条用双类结构锚（聚合态类表无 _switcherRoot，零误伤）
     把 switcher 拉回右缘 8px 惯例位；top:48 使 25 高中心 60.5 ≈ tab 中心
     60，完成基线对齐——基础规则的 bottom:0 因 top+height+bottom 全非 auto
     过约束，按 spec 忽略 bottom、top 执政，行为确定。
     360 真机推演：右锚后左缘 360−8−180=172 > tabs 端 ~126，46px 空隙。
     菜单 position:fixed 独立定位层，不受本条影响（取证已证）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
    /* 同上，进标题行流程。变体实测宽 180px，而标题行右簇只有约 229px 可用
       （= 可用 305 − 标题地板 72 − 间隙 4），减去预设 chip 95 与间距后只剩约 124px，
       所以这里封顶，超出部分由内部 span 的 ellipsis 收掉（见本文件 ZKlsPq span 规则）。 */
    /* 换位后同样回标签行；标题行才需要的 124px 封顶随之撤掉。 */
    /* 2026-10-12 用户要求：**位置定死，不被其他插件移动**。
       绝对定位元素的落点由「四边偏移 + 包含块 + transform」三者决定，所以三处都钉：
       ① 四边写全（含 bottom: auto），别的插件只改一条也推不动；
       ② 包含块锁死在 header（见下面把 _titleCluster/_headerActions 钉成 static 的那条）；
       ③ transform/margin 归零并加 !important，防被 translate / 外边距挪走；
       ④ z-index 提固定值，防止标签行后来者把它盖住。
       ⚠ 本块只钉「不被别的插件移动」——落点本身仍是本批之前那套（绝对定位贴
       标签行右缘）；手机档的形态差异由手机档作用域表达。 */
    position: absolute !important;
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
    bottom: auto !important;
    margin: 0 !important;
    transform: none !important;
    z-index: 3 !important;
  }
  /* 聚合 chip 右锚（用户拍板 2026-09-19：中置的子代理元素应右靠）：与
     switcher 同款右靠 + 基线（top:48 → 25 高中心 60.5 ≈ tab 中心 60；
     bottom:0 过约束被忽略、top 执政）。聚合 ~97 宽右锚后 285..382，标签行
     无第二颗状态 chip 时零碰撞（tabs 端 ≤170）。:not(_switcherRoot) 把变体
     让给上面 switcher 专属规则，二者匹配集不相交、无 order-tie。聚合
     max-width min(32vw,116) 沿用基础规则不动；数值 48/8 可调。 */
  /* @patch
     id: lineage-chip-pinned
     for: 宿主 ZKlsPq 子智能体 chip 的绝对定位落点（含包含块）
     why: 别的插件只要给任一祖先加 position:relative，包含块被抢、chip 就会挪位；四边偏移加 transform 是唯一能钉死的手段
     until: 宿主为该 chip 提供非绝对定位的稳定槽（届时四边偏移与祖先 static 都可删）
     test: tests/a2-header-gating.test.ts
  */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    /* 2026-10-12 用户规格（第三批·B 方案）：把**子智能体 chip 从标签行搬进标题行**，
       坐在「智能体预设 chip」与「文件夹按钮」之间 —— 即原先后台任务圆点占的那个位置；
       后台任务 chip 已进标题行（现由 header-job-chip-collapse 收窄）。
       原来它是 absolute + right:8px + top:48px（相对 header，视觉上落在标签行 y=42..67）。
       进流程后 left/right/top 全部失效，由 order 排在预设 chip 之后。 */
    /* 2026-10-12 换位后回标签行：绝对定位贴右缘、top:48（原形态）。 */
    /* 2026-10-12 用户要求：**位置定死，不被其他插件移动**。
       绝对定位元素的落点由「四边偏移 + 包含块 + transform」三者决定，所以三处都钉：
       ① 四边写全（含 bottom: auto），别的插件只改一条也推不动；
       ② 包含块锁死在 header（见下面把 _titleCluster/_headerActions 钉成 static 的那条）；
       ③ transform/margin 归零并加 !important，防被 translate / 外边距挪走；
       ④ z-index 提固定值，防止标签行后来者把它盖住。 */
    position: absolute !important;
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
    bottom: auto !important;
    margin: 0 !important;
    transform: none !important;
    z-index: 3 !important;
  }
  /* 手机档（≤767 + coarse）：上面两条的 top:48 是按"标题行 40 + 页签行 36"的老几何
     推的（25 高中心 60.5 ≈ tab 中心 60）。本插件的手机档把页签行压到 26px 之后，
     真机页签文字落在 43..58（tab 盒 40..66），芯片还停在 48 ⇒ 文字 49..63、比
     "对话/轨迹"低 6px（店主 2026-09-23 截图报障："调了间距但忘记把这个调了"）。
     同特异性 + 同 !important 时后到先得，所以本条必须写在那两条之后：42 让芯片
     文字落到 43..57，与页签文字 43..58 对齐。平板档不压页签行，仍用 48。 */
  @media (max-width: 767px) and (pointer: coarse) {
    /* ⚠ 两个选择器都必须写成与上面两条**同特异性**：
       聚合变体的 48px 规则是 …[class*=ZKlsPq_root]:not([class*=_switcherRoot])，
       :not() 会把参数的特异性算进去 ⇒ (0,5,1)。我第一版第一个选择器写成通用的
       …[class*=ZKlsPq_root]（只有 (0,4,1)）⇒ 特异性输给那条 48px，
       店主实测"又没对齐了"（聚合芯片文字回到 49..63）。带上 :not(...) 才并列、
       再靠"后到先得"取胜。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
      /* 2026-10-20：原先钉 top: 42px 是按「标题行 40 + 页签行 36」的固定几何推的，
         本插件这几轮把页签行压到 21px 之后，42 就比「对话」低了一截、还压到正文上。
         改成贴 header 底边：chip 盒 21px、下内边距 5px，与页签按钮同一个盒模型，
         页签行再怎么压缩都不会再错位（top 必须显式 auto，否则上面那条 48px 继续执政）。 */
      top: auto !important;
      /* 截图实测逐轮校：页签文字本轮下沉后，chip 需再降 5px 才与「对话」同基线
         （负值不越出 header 视觉范围，chip 高 21、z-index 3）。 */
      bottom: -2px !important;
    }
  }
  /* 谱系 chip 里的文字（子代理标题 /「N 个子代理」）给一个规矩的省略号窗口：
     不要裁成半个字，也不要靠滚动去够剩下的字。 */
   /* :not([class*="_separator"])：rc 代有 (0,4,1) !important 的
      [class*="_crumbs"] [class*="_separator"] display:none 规则，专门隐藏
      谱系计数前的「/」（小屏上它读起来像多出一层面包屑层级）；本条原来
      同为 (0,4,1) !important 且书写在后，同特异性后到先得把 separator
      顶回 display:block（实测 390px separator [269.1,·,5.5,25] 实绘可见）。
      加 :not 把 separator 从本条管辖范围摘掉，隐藏权交还 rc 代那条。 */
  /* 2026-10-12 用户规格：给智能体预设 chip 套一层**不可见的胶囊**。
     动机：公共车道里预设 chip 与子智能体 chip 会贴在一起（间距已被收到 0），
     不套这层就分不清是两个控件。
     做法：外壳用 ::before 画一个透明底的圆角胶囊（「灰色层、不显现」——形状与圆角
     就位，底色透明；将来要显灰只需把 background 换成变量），实际间隔由 margin-right
     给出（几 px，用户明确要求「不至于为 0」）。
     用 ::before 而不是给本体加 padding：本 chip 的图标是 position:absolute 贴左缘的，
     给本体加左内边距会让文字右移而图标不动，两者会叠上。 */
  /* @patch
     id: preset-invisible-capsule
     for: 宿主 SVAs4q_label 智能体预设 chip 的外包裹层
     why: 该 chip 的图标是 position:absolute 贴左缘，给本体加内边距会让文字与图标叠上；只能加一层绝对定位外壳
     until: 宿主给预设 chip 自身加上左右内边距（本外壳与 margin 一并删）
     test: tests/a2-header-gating.test.ts
  */
  /* 智能体预设 chip（SVAs4q_label）：**本批之前这里没有任何本插件的规则** ——
     宿主的 max-width 180px + ellipsis 边界照旧，外层不加包裹层。
     2026-10-12 的「不可见胶囊 + 可收缩 + 超长省略号」是一组只针对手机档的改动，
     见下面的手机档作用域（平板档继续走宿主原样）。 */
  /* 手机档（≤767 + coarse）：2026-10-12 用户规格 —— 给智能体预设 chip 套一层
     **不可见的胶囊**。动机：公共车道里预设 chip 与子智能体 chip 会贴在一起
     （间距已被收到 0），不套这层就分不清是两个控件。做法：外壳用 ::before 画一个
     透明底的圆角胶囊（「灰色层、不显现」——形状与圆角就位，底色透明），实际间隔由
     margin-right 给出（几 px，用户明确要求「不至于为 0」）。用 ::before 而不是给
     本体加 padding：本 chip 的图标是 position:absolute 贴左缘的，给本体加左内边距
     会让文字右移而图标不动，两者会叠上。
     另加「长名字防护」：宿主自带的 min-width:auto（= 内容宽）会让长名字把同级挤出去、
     先压垮标题的 72px 地板再让整簇溢出；这里补可收缩 + min-width:0 + 省略号。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="SVAs4q_label"] {
      position: relative !important;
      /* 先归零（去掉与宿主自带 2px 右内边距的重复），用户看过之后要求
         「外包裹层的 px 抬一丢丢」⇒ 占回 2px。现在的构成 = 宿主自带右内边距 2 + 本 margin 2
         + 容器 gap 0 + 团队 chip 左内边距 2 = 6px（改前是 11px，大头那 7px 已收掉）。 */
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
      /* 用户要求「外包裹层的 px 抬一丢丢」：左右各 4 → 5px，上下也包 1px。 */
      inset: -1px -5px !important;
      border-radius: 999px !important;
      background: transparent !important;
      pointer-events: none !important;
    }
    /* 2026-10-12 用户反馈「预设与团队之间距离太多」：实测大头来自**团队 chip 自己**的
       左内边距（宿主 .VoX2oq_trigger 是 padding: 3px 7px）。7 → 2px 收掉 5px，
       与上面预设的 margin 归零合起来，这段总间距从 11px 降到 4px（视觉上才看得出来）。
       代价：该按钮左侧命中区窄 5px（高度仍由 min-height: 28px 保证）—— 用户若觉得难点，
       把 2px 往上调即可，值就是唯一旋钮。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] > button,
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="VoX2oq_trigger"] {
      padding-left: 2px !important;
    }
  }
  /* 2026-10-12：把子智能体 chip 与 header 之间的两个祖先钉成 static ——
       绝对定位元素认「最近的已定位祖先」作包含块，别的插件只要给它们其中任何一个
       加上 position:relative，chip 就会以那个元素为基准重新落点（相对位移）。
       二者今天本来就不是定位元素，钉成 static 对现有布局零影响，只是把包含块锁死在 header。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > [class*="_titleCluster"],
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_headerActions"] {
    position: static !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] span:not([class*="_separator"]) {
    display: block !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  /* composer 模型选择 chip（dsh-client-ui-model-selection，样式哈希
     _7KE1Ra_）。2026-09-23 改档：真·手机档一律**只留图标**
     （IconDataOutlineRegular），模型名与 effort 不再常驻 —— 官方新加的语音
     按钮吃掉宽度后「图标+名字」把动作行挤爆；点图标打开菜单后再选模型。

     上游 0.1.7 已把这个能力做成契约：conversation 的 observeControlRow()
     实测 row 装不下时才打 data-model-compact，其 CSS
     「.uV2eYG_row[data-model-compact]」向下传
       --dsh-composer-model-text-display: none;
       --dsh-composer-model-icon-display: block;
     由 model-selection 消费：triggerIcon display:var(…icon…,none)、
     triggerLabel/triggerEffort display:var(…text…,block)。
     **旧规则 [class*="_7KE1Ra_triggerLabel"]{display:inline !important}
     正是把它顶掉的那一条** —— 图标被 compact 显形、名字又被我们拉回来，
     所以现场是"图标和名字同时出现"，也就是"上游代码里有、却不生效"。
     这里删掉它，并在 ≤767px 直接钉住这两个变量，不去赌宿主的实测结果
     （否则一旦某项变窄让 row 装得下，文字又会长回来，来回抖）。

     _7KE1Ra_ 是本代 model-selection 的样式哈希，包不在则整条死规则，无需
     另加代际门；必须带哈希前缀，裸 [class*=_triggerLabel] 会误伤
     permission-presets / settings-general 的同名片段。 */
  @media (max-width: 767px) {
    [data-mobile-nav="frame"] [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
      --dsh-composer-model-text-display: none;
      --dsh-composer-model-icon-display: block;
    }
    /* 图标化后（上方变量钉死为 icon-only）宿主那套 padding:0 4px 0 8px 纯属
       浪费（左 8px 是给文字留的）。2026-09-23 店主第二轮："范围有点大、
       ⌄ 离图标远" ⇒ padding 归零、gap 归零，匣子只剩「图标 + ⌄」本身
       （实测墨迹间距 10px → ~4px，匣宽 46 → ~32px）。issue #101 对账：原与
       max-width 同块、无档位限定，768–1023 平板档文字在场时也被归零，chip
       内部「图标|模型名|effort|⌄」贴死 —— 2026-09-24 挪进本 ≤767 专档。 */
    [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_trigger"] {
      padding: 0 !important;
      gap: 0 !important;
    }
    /* ⌄ 的 svg 自身带内边距（墨迹比 viewBox 窄），再拉近 2px。gap 归零后两个
       svg 的内边距会让墨迹直接贴住（实测墨迹连成一段），这里不再加负 margin，
       留 ~2px 呼吸 —— 间距从 10px 收到 2px。 */
    [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_chevron"] {
      margin-left: 0 !important;
    }
  }
  /* 模型 chip 宽度预算（只对仍显示文字的 768–1023 平板档有意义）：宿主
     trigger 的 max-width min(360px,45cqw) 在窄容器下只给
     label+icon+effort+chevron 留 ~160px，模型名会省略成「GLM-5.3-Fla…」
     （headless 字体窄恰好放得下，同一盲区）。放宽到 60cqw，effort 有宿主
     自带 flex-shrink:1000 先让位。手机档文字已隐藏，这条不参与。特异性
     (0,3,0)+!important 胜宿主 (0,1,0) 普通声明；60 数值可调。 */
  [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_trigger"] {
    max-width: min(360px, 60cqw) !important;
    /* issue #101 对账：padding/gap 归零与 chevron margin-left:0 已分档至上方
       ≤767 专档（那是「图标化后」的前提）；768–1023 文字显示档保留宿主
       padding 0 4px 0 8px 与宿主 gap，⌄ 回宿主 margin。 */
  }
  /* --- Settings dialog on mobile ---
     Desktop: 800px two-column flex (188px nav + content). Mobile: a
     near-full-width sheet — nav tabs wrap into rows on top, option rows
     stay horizontal (title+description left, control right). Structural
     selectors are scoped to the unique aria-modal dialog; every
     settings-specific rule is gated with
     :has(> :first-child > :last-child > button) — the settings nav tab
     list holds <button> tabs, so the transient export dialog (the same
     primitives Modal, header(title+close)+description+body) keeps its
     official centered card layout. Requires :has() support
     (Chromium 105+, 2022).

     The directory picker (dsh-client-ui-directory-picker-browse) must be
     excluded too: its footer bar holds <button> children AND its breadcrumb
     trail (role="navigation") — which the role gate relies on to exclude
     it — is REPLACED by the path input in edit mode (pencil button), so
     without the ZuhsRW exclusion clicking the pencil would suddenly match
     this sheet rule: the dialog jumps to the top of the screen, the header
     (with the path input) is hidden by the > :first-child > :first-child
     display:none rule below, and the user can no longer type a path
     (issue #12, 2026-08-16). The picker family keeps the official layout
     on mobile in every mode.

     The keyboard-shortcut modal (dsh-client-ui-shortcuts, the same
     primitives Modal → data-shortcut-modal="shortcuts") needs the same
     exclusion for the same class of reason: its first child is the
     CONTENT column (nhfO0a_contents = header + search row + list +
     footer), not a nav row, and its footer holds <button> children, so
     the family predicate matched it and the sheet rules transposed the
     whole dialog — measured 2026-09-25 at 390px: the
     > :first-child { flex-direction: row } rule laid search row / list /
     footer SIDE BY SIDE (x=20 / 118 / 278, list 1296px tall, spilling
     far outside the sheet), and > :first-child > :first-child
     { display: none } swallowed the 「快捷键」 title together with its
     close button (owner report). The host tags every modal of this
     family: data-shortcut-modal="settings" on the settings sheet,
     "shortcuts" on this one — gating on that attribute (not on a hashed
     class) keeps the official centered card, the same treatment the
     export dialog gets. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) {
    position: absolute !important;
    left: 8px !important;
    /* Fixed top (no translateY): a transform on the panel combined with the
       panel overflowing the max-content drawer shifts the fixed overlay's
       coordinate frame, dragging the whole sidebar content off-screen. The
       safe-area inset keeps the sheet below the status bar / notch. */
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px);
    max-width: calc(100vw - 16px);
    /* Height follows the content (no dead space under a short page); it
       caps at the KEYBOARD-LESS viewport height minus 24 (less the safe-area
       top) and the options area scrolls only then. STABLE_VIEWPORT_VAR, not
       100dvh: measured 2026-09-25 on Android 16 WebView (adjustResize), the
       soft keyboard takes the layout viewport 754 -> 471 and vh / svh / lvh /
       dvh all follow it, so a dvh-sized sheet collapses a step the moment the
       shortcut modal's search field raises the keyboard — the reporter's
       「又闪一下」. The variable never moves for the keyboard, so the sheet
       keeps its size and the keyboard covers its lower half instead. */
    height: auto;
    max-height: min(800px, calc(100vh - 24px - env(safe-area-inset-top, 0px)));
    max-height: min(800px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px)));
    /* Only a real viewport change (rotation / window resize) reaches this now,
       so the short transition reads as a slide instead of a jump. */
    transition: max-height .2s var(--ds-ease-out, ease-in-out);
    flex-direction: column !important;
    border-radius: 14px !important;
    animation: dsh-web-mobile-sheet-in .22s var(--ds-ease-out, ease-in-out);
  }
  /* The settings sheet's dimmed mask fades in with the panel: the mask is the
     overlay's first child, so it is reached as a direct child of the overlay
     that holds the settings panel. The panel is identified by its stable
     data-shortcut-modal="settings" marker (host value; the shortcut editor uses
     "shortcut-edit" instead, so this cannot reach that family) plus
     role/aria-modal. Keep the outer :has() ATTRIBUTE-ONLY: an earlier form
     tested the panel's shape by nesting a :has() inside this one, which is an
     invalid selector — Chromium dropped the entire rule without a word and the
     fade never ran once (issue: settings mask, found 2026-09-27 and 2026-10-10).
     scripts/css-structure-check.mjs now fails the build on nested :has(). */
  :has(> [aria-modal="true"][data-shortcut-modal="settings"]) > [class*="_mask"] {
    animation: dsh-web-mobile-fade .18s var(--ds-ease-out, ease-in-out);
  }
  @media (prefers-reduced-motion: reduce) {
    [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]),
    :has(> [aria-modal="true"][data-shortcut-modal="settings"]) > [class*="_mask"] {
      animation: none !important;
    }
  }
  /* The export dialog (not the settings sheet) must never overflow the
     viewport: the official centered card can be wider than 390px. */
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    max-width: calc(100vw - 32px);
  }
  /* Image lightbox (dsh-client-ui-primitives ImageLightbox; body-portal
     div[role=dialog][aria-modal=true] whose stage holds the image and the
     close button). The export rule directly above only excludes dialogs whose
     stage's LAST child contains a button; in this lightbox the stage's last
     child IS the close button (its own child is the icon svg), so the
     :not(:has(...)) predicate is true and the 100vw - 32px cap hits the
     backdrop: on a 390 viewport the mask covers [0,358] and the right 32px is
     neither painted nor hit-testable. The misc.css 720px centered tablet rule
     hits it the same way. This positive override restores the full-bleed
     backdrop without editing either of them: the leading div buys the extra
     specificity point, and that point is load-bearing on the tablet branch —
     misc.css.ts is concatenated AFTER layout.css.ts, so an equal-specificity
     override would lose to the 720px centered tablet rule (0,3,1 vs 0,3,1)
     and leave the 768-1023 lightbox inset on both sides.
     The :has() arm stays SINGLE-level: a nested :has() is an invalid selector
     and Chromium would drop the whole rule silently (see the settings mask
     episode in docs/maintenance/pitfalls.md).
     touch-action:none keeps the page-level pinch/pan out of this layer (the
     root rule's pan-y pinch-zoom stays untouched for every other surface);
     tests/ios-zoom-guard.test.ts pins this deliberate none and
     effects/image-lightbox-zoom.ts attaches its gesture listeners to this
     dialog node only, never to document. */
  /* @patch
     id: image-lightbox-fullbleed
     for: dsh-client-ui-primitives ImageLightbox（role=dialog + aria-modal=true，stage 末孩子是关闭按钮）
     why: 宿主遮罩自身是 fixed inset:0，却被上方「非设置形状弹层」的 max-width 收窄规则与 misc.css 平板档 720px 居中规则命中，右侧漏画且不拦点击
     until: 宿主给灯箱提供独立的 data-* 标记或自带移动端铺满形态，届时删本块
     test: tests/css-rules.test.ts
  */
  div[role="dialog"][aria-modal="true"]:has(> :first-child > img) {
    left: 0 !important;
    right: 0 !important;
    width: auto !important;
    max-width: none !important;
    margin-left: 0 !important;
    margin-right: 0 !important;
    touch-action: none !important;
  }
  /* Nav bar: hide the "Settings" caption (redundant on a full-width sheet)
     and wrap the tab list so every tab is visible — a horizontal scroll cut
     the last tab ("Plugins") off with no affordance to scroll. */
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
  /* The tab strip stays clear of the toolbar: the toolbar (the close ✕ on
     this host — the config-file button is hidden below) is absolutely
     positioned over the nav row's right end (#105 A' — it stays at its
     React home in the content column; see below). The strip is pinned to
     ONE horizontal scroller: flex-wrap:nowrap + overflow-x:auto.
     2026-09-25, rc.2 portal regression: 0.1.7-rc.1 rendered this sheet in
     place (inside the app frame); rc.2 wraps it in
     createPortal(..., document.body) — diffed rc.1 vs rc.2 bundles, no
     createPortal before — so every [data-mobile-nav="frame"]-scoped
     dialog rule (the frame-era single-row scroller in compat.css among
     them) went dead the moment the overlay became a direct body child.
     What survived was this rule's own flex-wrap:wrap, which had been
     losing to the host's nowrap scroller and now had nothing to lose to:
     the cells broke into uneven rows (3/2/3/2/1 at 402px) whose first row
     slid under the 138px toolbar (config-file button + close) — the
     settings-sheet half of the owner's 2026-09-25 report. Pinning the
     scroller here makes the geometry host-generation independent again;
     the cells keep flex-shrink:0 + nowrap (rule below), the strip
     scrolls, and the hairline scrollbar is the affordance. The scroller
     VIEWPORT stops short of the toolbar zone: margin-right = toolbar
     width (36: the 32px round close + 4px) + 6px gap (measured
     2026-09-24) reproduces the reparent-era scroller geometry (its box
     ended 6px short of the toolbar). The strip must be anchored by its
     class. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"] {
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
  /* Hairline scrollbar for the tab strip: the default WebKit scrollbar
     reads fat on a phone; 2px keeps the scroll affordance without the
     bulk. (Portal-aware copies of the frame-scoped rules in compat.css,
     which died with the rc.2 portal move.) */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar {
    height: 2px !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
    border-radius: 1px !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navList"]::-webkit-scrollbar-track {
    background: transparent !important;
  }
  /* Cells stay whole inside the scroller: no shrink, no wrap, compact
     metrics. (Portal-aware copies of the frame-scoped rules in compat.css,
     which died with the rc.2 portal move.) */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navCell"] {
    flex: 0 0 auto !important;
    white-space: nowrap !important;
    padding: 6px 8px !important;
    gap: 6px !important;
    font-size: 13px !important;
    justify-content: flex-start !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :first-child [class*="_navCell"] svg {
    width: 14px !important;
    height: 14px !important;
    flex: none !important;
  }
  /* Content toolbar (close, plus the config-file button on hosts that
     render one): pinned over the nav row's right end, flush right.
     #105 A' — the toolbar stays at its React home (the content column's
     direct child) and is absolutely positioned against the dialog; the
     dialog is position:absolute itself, so it is the containing block
     and no new one is introduced. Constants measured 2026-09-24 (CDP,
     393px): the reparented toolbar — whose visual this replaces — sat
     at in-dialog dy=10 / fromRight=12, hence top 10px / right 12px.
     Out of flow, the toolbar's own row disappears and the options area
     starts right under the nav row; the navList's scroll viewport stops
     short of the toolbar with margin-right = toolbar width (36: the 32px
     round close + 4px) + 6px gap (measured). Children carry official
     auto-margins
     that would defeat flex-end, so neutralize them. The close button
     gets a round tappable base so it reads as its own control, not
     part of the outline button.
     Anchored structurally, not by class substring: a bare [class*="_header"]
     also matches every plugin settings card header in the options area —
     the official Plugins config cards (YyYd_a_header) and the dsh-web-ui-all
     group cards (Kwoi6G_header / Jh0q7G_header / rUBhvW_header; the bpnj3G_/jmhvDG_
     siblings were renamed upstream in dsh-web-all 0.3.20, verified 2026-09-18), all sharing the upstream template text-align:left,
     gap:12px, padding:14px 16px). The old broad anchor right-aligned their
     text, gutted the padding and painted a 32px gray circle behind the
     chevron (2026-09-05 sweep: 8 bleeding headers). The toolbar's one
     structural home is the content column's direct child (the panel's
     :last-child); the post-reparent nav-row home died with the
     settings-toolbar-reparent task. Card headers live deeper — inside
     the options scroll area — and match neither, so no per-plugin hash
     guards are needed. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > [class*="_header"]:not([class*="_headerActions"]) {
    position: absolute;
    top: 10px;
    right: 12px;
    /* z-index is load-bearing since 0.1.7-rc.2 (owner report 2026-09-25):
       the sheet is portaled to <body>, and the market page (dshmarket's
       nUhMVa_root, position:relative, z:auto) paints AFTER this header in
       DOM order — both are z:auto positioned, so the market head covered
       the pinned toolbar: the close ✕ stayed visible through the head's
       transparent right end but hit-testing returned the head, so tapping
       the ✕ did nothing ("按了关闭没用"). z-index lifts the toolbar into
       the painted-above layer: above the market root and its sticky list
       heads (.stickyHead z:5), still below the market's own transient
       layers (.opPanel z:40, .lightbox z:10000) which SHOULD cover it.
       Settings view: the toolbar sits over the nav row's reserved right
       end (margin-right 42px), so nothing there to cover or be covered. */
    z-index: 10;
    flex: 0 0 auto;
    justify-content: flex-end;
    align-items: center;
    gap: 8px;
    padding: 0 0 0 4px;
    /* Hug the close ✕ only: the host header box is 54px tall, and with the
       actions hidden its empty lower half (above the market's "导出日志"
       button, which starts ~13px under the ✕) formed a dead zone that
       ate the export button's top-right corner once z-index lifted the
       toolbar above it (owner report follow-up 2026-09-25). 32px = the
       close's own height, so the toolbar's box ends where the ✕ ends. */
    height: 32px;
    min-height: 32px;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > [class*="_header"]:not([class*="_headerActions"]) > * {
    margin-left: 0 !important;
    margin-right: 0 !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > [class*="_header"]:not([class*="_headerActions"]) > :last-child {
    position: relative;
    width: 32px;
    height: 32px;
    border-radius: 50% !important;
    display: inline-flex !important;
    align-items: center;
    justify-content: center;
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06)) !important;
  }
  /* 32px is under the ~44px touch minimum and this ✕ shares the corner
     with the market's version text (above-left) and its export button
     (below-left) — the owner's "很容易误触" report 2026-09-25. Extend the
     HIT area only (no visual change): the pseudo-element grows up, left
     and right by 6px — never downward, where the market's "导出日志"
     button starts ~13px under the ✕'s bottom edge and must keep its own
     top-right corner. Anchored to the button (position:relative above),
     so the extension travels with the pinned toolbar. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > [class*="_header"]:not([class*="_headerActions"]) > :last-child::after {
    content: "";
    position: absolute;
    inset: -6px -6px 0 -6px;
    border-radius: 50%;
  }
  /* The config-file action (a settings.action slot — dsh-version-update's
     "打开配置文件") is hidden on phones: it is rarely needed here, and its
     ~94px next to the 32px close made the pinned toolbar 138px wide —
     wide enough to swallow the nav strip's first cells while the strip
     still wrapped (2026-09-25 report, the other half of the same
     regression as the scroller fix above). The close ✕ is the toolbar's
     SIBLING, not its child (verified in the live DOM: header children are
     [actions, close]), so hiding the actions never removes the way out.
     Desktop keeps the button: this whole block sits inside the mobile
     media wrapper. (Portal-aware replacement for the frame-scoped rule in
     compat.css, which died with the rc.2 portal move.) */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > [class*="_header"]:not([class*="_headerActions"]) [class*="_actions"] {
    display: none !important;
  }
  /* Appearance mode cards: the official cube row renders three tall
     vertical cards (~268px) that eat half the sheet. Turn them into a
     compact horizontal trio (icon + label inline, equal widths).
     Relies on the official cube-row class name of this version. */
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
  /* Content: the options scroll area gets bottom breathing room so the last
     row never sits flush against the sheet's rounded corner. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child {
    flex: 1 1 auto;
    min-height: 0;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > :last-child {
    padding: 0 12px 24px;
  }
  /* 0.1.6-alpha.2 宿主的插件管理页（dsh-client-ui-plugin-manager 渲染的
     section[data-plugin-panel]）。FAB 是全站恒定的左上角控件（用户明确
     要求：右下角不对，就放左上角），所以规则不做的是挪 FAB，做的是给
     面板自己的左上角内容让位。两个页面都要让，偏移量同一个：
     - 列表页：**有**内容在左上角 —— H1「插件」实测 [24,28,91,28]，与 FAB 盒
       [10,12,38,38] 重叠 24×22；点标题左缘命中的是 FAB 而不是标题
       （2026-09-19 报障）。上一版这里记的是「热区为空、零规则」，实测不成立。
     - 详情页（DetailTop 组件）：宿主返回键「返回插件列表」crumb，文字实测
       [24,28,70,19]，同样压在 FAB 左半——点它会触发开抽屉而不是返回。
       详情根的 data-* 标记有三种：内置插件详情 data-plugin-item-detail、市场
       插件详情 data-plugin-row-detail、builtin 详情 data-plugin-detail
       （实测「智能体团队」卡走的就是第三种），三条选择器并列全覆盖。
     让位量 = FAB 右缘（10 + 38 = 48）+ 8px 间距 = 56px，**写成相对量**：
     减掉宿主自己的 padding（clamp(24px,4vw,48px)），这样它跟着视口走，而不是
     把 390px 上量到的 32px 钉死（768px 平板上宿主 padding 是 30.7px，钉死的
     32px 会过量）。在 390px 上它算出来正好还是 32px，与上一版行为一致。
     锚点全部是宿主 data-* 标记，比 css-module 哈希类（X_2TxG_）稳定；
     pre-alpha.2 宿主没有这些标记，规则天然不命中（代际门控）。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] {
    --dsh-web-mobile-panel-clearance: calc(56px - clamp(24px, 4vw, 48px));
  }
  /* 页首是宿主滚动盒的直接子元素，宿主给它 width:100%（.X_2TxG_page>*）。
     这种盒子上用 margin 会把整行顶出右缘、给面板加出一条横向滚动条，所以
     这里用 margin + 等量收窄：margin 盒仍是 100%，左缘让开 FAB，右缘不再
     外扩。0.2.1-rc.2 起工具栏已换行且按 flex-start 排布，「添加插件」不再
     贴右，旧注释里那个「保持贴右」的右缘假设已不成立；规则本身的行为
     （左缘让位、右缘不越界）不变。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] [class*="_pageHead"] {
    margin-left: var(--dsh-web-mobile-panel-clearance) !important;
    width: calc(100% - var(--dsh-web-mobile-panel-clearance)) !important;
  }
  /* 详情 crumb 是被拉伸的 flex item（没有 width:100%），margin 就是对的工具。
     **0.1.7-rc.2 起「直子」形态落空**：宿主把 crumb 套进了 DetailTop 的根盒
     （实测链 [data-plugin-detail] > div.X_2TxG_detailTop > button.X_2TxG_crumb），
     于是上面三条「> button:first-child」在详情页全部 matches()=false ——
     crumb 的 margin-left 计算值 0px，停在宿主 padding 上：盒 [24,28,342,14]、
     自带箭头图标 [24,28,14,14]、文字 span x=44，整条压在 FAB 盒
     [10,12,38,38]（右缘 48）里 —— 图标 14px 全遮、文字首字压 4px；
     elementFromPoint 在图标中心与文字首字处都命中 FAB，点「返回插件列表」
     实际触发的是 FAB 的 exit-panel（2026-09-25 报障截图同形）。
     所以保留直子三条（旧代宿主仍走它们），再按 crumb 自己的哈希片段补三条
     后代选择器。片段取「_crumb」：同前缀的 svg.crumbIcon 不是 button 天然排除，
     本子树里也没有别的 crumb 家族（文件面板 ZuhsRW_crumb* 在另一棵树）。
     实测让位后 crumb 变 [56,28,310,14] —— flex 拉伸项自己收窄 32px，无横向
     溢出（面板 scrollWidth 恒 390），点文字可正常返回列表。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-detail] > button:first-child,
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-item-detail] > button:first-child,
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-row-detail] > button:first-child,
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-detail] button[class*="_crumb"],
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-item-detail] button[class*="_crumb"],
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-row-detail] button[class*="_crumb"] {
    margin-left: var(--dsh-web-mobile-panel-clearance) !important;
  }
  /* 自动化任务面板（@deepseek-ai/dsh-client-ui-schedule，0.2.0-rc.2 随实验性
     bundle 提供）不是 section[data-plugin-panel]：该 bundle 里
     「data-plugin-panel」命中 0 次，页头是 t-XoWW_pageHeading(display:flex)，
     标题是 h1{flex:1;min-width:0;margin:0}。盒模型取自宿主自己的 CSS：
     pageContent padding = clamp(24px,4vw,48px)（390px 视口取 24px），
     于是标题左缘 24px 落进 FAB 盒 [10,12,38,38]（右缘 48）——20px 字号的
     首字被整颗压住（2026-10-06 报障截图）。让位量沿用同一口径
     （56px − 宿主自身 padding）；作用对象取 h1：它是 margin:0 的 flex item，
     margin-left 只吃 flex 自由空间，不需要 width 补偿，也不会像整宽盒那样
     顶出横向滚动条。0.2.1-rc.2 起插件管理页的标题 h1 也命中「_pageHeading」
     片段（旧注释「全部宿主 bundle 只有这一个渲染者」的假设已不成立），本页
     会被一起串到；下方嵌套的手机档块把 section[data-plugin-panel] 里的两级
     margin-left 归零，自动化任务面板仍用本条让位。 */
  [data-mobile-nav="frame"] [class*="_pageHeading"] > h1 {
    margin-left: var(--dsh-web-mobile-panel-clearance, calc(56px - clamp(24px, 4vw, 48px))) !important;
  }
  /* 插件管理页页首左对齐（2026-10-20 实测）：插件管理页的页头同时被上面两条
     「给 FAB 让位」的规则命中 —— 一条给 [class*="_pageHead"] 加 margin-left，
     另一条给 [class*="_pageHeading"] > h1 加 margin-left。两个片段互为子串，
     页首容器与标题各自被让位一次，页首于是离左缘远、与左上角浮起的 ← 之间
     空出一大截。手机档只对 section[data-plugin-panel] 收口：先把 clearance
     改成 60px 口径（FAB 右缘 48 + 12 间距；390px 视口上算出 36px），再归零被
     「_pageHead」子串误伤的两级 margin。只影响本页；自动化任务面板与详情
     crumb 的让位规则照旧。
     同日按店主参考图再落一档：页首必须「一行内排」——标题+说明在左、刷新键与
     添加插件按钮在右。宿主 pageHead 是 flex-wrap:wrap，这一步直接禁止换行、让
     heading 可收缩（min-width:0），工具栏 flex:0 0 auto + margin-left:auto 贴右；
     说明文字过长时在左列内换行，不再把工具栏挤到第二行。 */
  /* @patch
     id: plugin-panel-head-left-align
     for: dsh-client-ui-plugin-manager 0.2.1-rc.2 的 section[data-plugin-panel] 页首（_pageHead / _pageHeading / h1 三层）
     why: 两条让位规则靠子串同时命中页首容器与页首标题，叠加后页首离左缘过远；宿主没有区分「页头容器」与「页首标题」的稳定标记
     until: 宿主给页首容器独立标记，或页首不再需要给 FAB 让位，届时删本块
     test: tests/css-rules.test.ts
  */
  @media (max-width: 767px) and (pointer: coarse) {
    /* 一行内排：标题 + 说明在左、工具栏（刷新 + 添加插件）在右（2026-10-20 店主参考图）。
       宿主 pageHead 自己是 flex-wrap:wrap + space-between；内容宽 300 出头时
       heading(≈154) + gap16 + toolbar(≈172) 放不下一行，就折成三行堆叠。
       这里禁止换行、把 heading 让成可收缩的 flex 项（min-width:0 才能压到内容宽），
       toolbar 保持自身宽度贴右；说明过长时在左列内换行，不再把工具栏挤下去。 */
    [data-mobile-nav="frame"] section[data-plugin-panel] > [class*="_pageHead"] {
      --dsh-web-mobile-panel-clearance: calc(60px - clamp(24px, 4vw, 48px));
      flex-wrap: nowrap !important;
      align-items: flex-start !important;
      gap: 8px !important;
    }
    [data-mobile-nav="frame"] section[data-plugin-panel] [class*="_pageHeading"] {
      flex: 1 1 auto !important;
      min-width: 0 !important;
      margin-left: 0 !important;
      width: auto !important;
    }
    [data-mobile-nav="frame"] section[data-plugin-panel] [class*="_toolbar"] {
      flex: 0 0 auto !important;
      margin-left: auto !important;
    }
    [data-mobile-nav="frame"] section[data-plugin-panel] [class*="_pageHeading"] > h1 {
      margin-left: 0 !important;
    }
  }
  /* 快捷键弹层在手机上的落地形态。上面那条 :not([data-shortcut-modal="shortcuts"])
     只是把它从设置面板家族里摘出来、还它官方的内部排版（2026-09-25 实测：纵向列
     回来了、标题「快捷键」回来了、列表 441px 可滚、无横向溢出、docScrollWidth
     恒 390）。但官方的外框在手机上仍会「抽搐」：宿主 Modal 的 _root 是
     position:fixed; inset:0; align-items:center（视口居中），而弹层打开时会自动
     聚焦搜索框（实测 activeElement = INPUT「搜索快捷键」），手机随即弹软键盘 ——
     视口一缩，居中卡片就整体重排/回弹，肉眼即抖动。所以这里给它插件自己的「纸片」
     几何：顶部锚定（键盘怎么变，上缘都钉在 12px）+ 与设置面板同款左缘/宽度/圆角/
     入场动画。高度沿用宿主的 600px：nhfO0a_contents 是 flex:1 1 0%，要有一个确定的
     高度才撑得开列表，故不改成 auto；max-height 再按视口收口，超出的部分进列表自己
     的 scroll（_list 已是 flex:1 + min-height:0 + overflow-y:auto），与设置面板同款。
     宿主那 30px 的 translateY 是桌面居中卡的微调，顶部锚定后必须归零。 */
  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    position: absolute !important;
    left: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px) !important;
    max-width: calc(100vw - 16px) !important;
    /* 同上：键盘不进这层的高度。这一层下面就是键盘，卡片缩一次就一定被看见，
       所以用「不含键盘的视口高度」定高 → 点搜索框时卡片纹丝不动，键盘盖住下半截。 */
    max-height: min(760px, calc(var(--dsh-web-mobile-vh, 100dvh) - 24px - env(safe-area-inset-top, 0px))) !important;
    transition: max-height .2s var(--ds-ease-out, ease-in-out);
    transform: none !important;
    border-radius: 14px !important;
    /* 不做透明度淡入。改动前（#124 修法二刀，2026-09-25）设置面板的
       dsh-web-mobile-sheet-in 还带 opacity 段，而本层叠在**同样全宽全白**的
       设置面板上，淡入的 .22s 里两层文字互相透出：CDP screencast 逐帧实拍
       （390×844）第 10-15 帧能看到「权限/语言/外观」与「快捷键速查/新会话」
       重影，肉眼就是「闪」。该刀后 sheet-in 已是纯滑入，不再有透明度重影的
       机制；本层维持瞬时出现（不写 animation 会落回宿主的 _modalEnter，
       同样是透明度淡入）；遮罩自己的淡入保留，整体仍是一次正常的弹层出现。 */
    animation: none !important;
  }
  /* DSHA 保留手机搜索；上游定向首轮焦点守卫不删除宿主 React 节点。 */
  /* 这一层的遮罩也在每次挂载时跑宿主的 _modalEnter（0.2s 透明度淡入）：全屏亮度在
     0.24 档上渐变一次，肉眼看就是「全屏闪」。上一版只掐了卡片自己的动画、**故意保留**
     了遮罩的淡入；报障人 2026-09-25 的反馈（「全屏闪」）说明那一步同样看得见。
     这里连同卡片一起瞬时化：弹层与遮罩同帧出现、同帧消失，中间没有渐变。 */
  :has(> [aria-modal="true"][data-shortcut-modal="shortcuts"]) > [class*="_mask"]::after {
    animation: none !important;
    /* 手机档这个弹层只能从设置面板里打开，而设置面板自己已经压了一层 0.24 的遮罩；
       再叠一层就是全屏暗度 0.24 → 0.42 的一步 —— 报障人说的「全屏闪」。这一层不再
       重复变暗：屏幕的整体明暗在弹层开合前后完全一致，剩下的变化只有卡片本身。 */
    background: transparent !important;
  }
  /* --- 插件管理页（dsh-client-ui-plugin-manager）：整卡 / 整行可点 ---
     2026-10-07 店主：「打开如图里面的功能，需要点击那些加黑字体才行」。读宿主源码确认到两层：
     ① 宿主**已经**给标题按钮铺了整卡覆盖层（「.cardOpen:after{position:absolute;inset:0}」 配
        「.cardLink{position:relative}」，标准 stretched-link）；
     ② 但卡片 DOM 顺序是 icon → titleRow(button) → **cardDesc**，描述是个 -webkit-box 盒子，
        绘制顺序排在覆盖层**之后** ⇒ 描述整块把点击吃掉，点它什么都不发生；只有标题（按钮自身）
        与描述以外的细缝能开详情。
     所以本规则不是「再加一层」，而是**把覆盖层抬到描述之上**（z-index），并把卡片里的其它
     交互件（开关等）抬得更高，保证它们仍能单独点。
     根节点仍钉 「position: relative」：一旦宿主换代不再给 cardLink 定位，「inset:0」 的包含块会
     落到更外层 —— 覆盖层铺满整页，就成了「点哪都开第一个插件的详情」。
     2026-10-08 补：宿主的条目根有**三种**（li[data-plugin-package] 包卡片、li[data-plugin-row]
     包详情里的行、li[data-plugin-item] 官方 item 卡）。item 卡（plugins.item 槽）当初漏了 ——
     真机症状正是「有开关的包卡片能整卡点，从『终端』起的 item 卡只有加黑标题能点」。 */
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
    /* 关键一行：压过同卡片里的描述/徽标等静态盒子（宿主那层没有 z-index）。 */
    z-index: 1;
  }
  li[data-plugin-package] :is(button:not([class*="_cardOpen"]), a, input),
  li[data-plugin-row] :is(button:not([class*="_rowOpen"]), a, input),
  li[data-plugin-item] :is(button:not([class*="_cardOpen"]), a, input) {
    position: relative;
    /* 比覆盖层高一档：开关等控件仍在覆盖层之上，可单独点。 */
    z-index: 2;
  }


  /* DSHA 可见区域边界：原生已消费 IME/systemBars，只读浏览器实际视口。 */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]),
  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    top: calc(env(safe-area-inset-top, 0px) + 12px + var(--dsha-mobile-viewport-top, 0px)) !important;
    max-height: max(1px, calc(var(--dsha-mobile-visible-vh, 100vh) - 24px - env(safe-area-inset-top, 0px))) !important;
    min-height: 0 !important;
    box-sizing: border-box;
    overflow-y: auto;
    transition: none;
  }
  [aria-modal="true"][data-shortcut-modal="shortcuts"] > :first-child {
    min-height: 0;
    max-height: 100%;
  }
  /* ---------- sidebar panel enter / exit (see effects/panel-exit.ts) ----------
     A sidebar panel REPLACES the main area. Two motions, both short and
     horizontal, matching the drawer's own rail-in (.15s, translate + fade):
       · enter — the panel slides in from the right;
       · exit  — the panel does NOT animate out; the conversation it hands the
         main area back to fades in instead.
     The asymmetry is deliberate. selectPanel(null) remounts the whole
     conversation and that commit blocks the main thread long enough to matter
     (measured on a phone: ~390 ms for a long session), so fading the panel out
     first would leave the screen blank for that whole window — panel already
     transparent, conversation not mounted yet. Keeping the panel opaque until
     the commit means the two swap on one frame.
     The enter rule is a CSS condition on purpose: :has() matches in the same
     commit that swaps the main slot, so the animation is already running at the
     element's first style resolution and there is no full-opacity frame first.
     The exit marker is set by JS before the swap for the same reason. */
  @keyframes dsh-web-mobile-panel-in {
    from { opacity: .55; transform: translateX(100%); }
  }
  /* Deliberately NOT reusing dsh-web-mobile-fade. The reason is the GHOST tail,
     not the whole exit: since 2026-10-12 the reveal side compares Animation
     objects (panel-exit.ts finishMain reads event.animationName only for the
     ghost), and the ghost cleanup still listens on animationend BY NAME
     (panel-exit.ts ghostEnd). dsh-web-mobile-fade also runs on the backdrop and
     the dialogs, which are frame descendants too — reusing it would end the
     ghost early. Comment corrected 2026-10-13 (was claiming the whole exit
     listens by name; the code had moved on). */
  @keyframes dsh-web-mobile-panel-reveal {
    from { opacity: 0; transform: translateX(-14px); }
  }
  [data-mobile-nav="frame"]:has([class*="panelRow"][aria-current="page"]) [class*="_centerCol"] > * > * {
    animation: dsh-web-mobile-panel-in .28s cubic-bezier(0, 0, .2, 1) backwards;
  }
  [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {
    /* ease-out rather than the shared in-out curve: the panel vanishes and the
       conversation appears on the same frame, so the fade has to come up fast
       or the first frames read as a flash of empty background. */
    animation: dsh-web-mobile-panel-reveal .18s cubic-bezier(0, 0, .2, 1) backwards;
  }
  /* ---------- 面板进出：从侧面滑出 / 从原路滑回（2026-10-07 店主两轮报障） ----------
     ① 第一轮：「退出的时候看着跟掉帧一样，直接回到聊天界面」—— 逐帧实测（390×844，插件页）：
        点返回后的**第一帧**面板元素就已从 DOM 消失，退场动画落在 0×0 空槽上。
     ② 第二轮：「空档有点久了……像打开侧边栏一样，从侧面滑出来，然后从原路返回」——
        上一版把切换推迟 220ms 等真面板滑完，结果会话重挂那次 commit（长会话 ~390ms）
        又往后挪了 220ms，空档反而更明显。
     终态：退场不再动真面板，而是把面板 clone 成**冻结快照**（panel-exit.ts 里的
     panel-ghost），**立刻**切换，让快照在合成器上滑回右侧 —— 主线程忙着重挂会话时它照样
     不掉帧；进场整屏从右侧滑入（时长与抽屉的 .28s 对齐），会话侧从左侧 14px 滑回。
     方向：进 = 从右往左推入，退 = 原路推回右侧。 */
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
  /* ---------- 长会话：消息块 content-visibility（2026-10-07 实测） ----------
     店主报「对话越长、切换/开侧边栏越卡」。真宿主取证（最长会话，中心列 ~6000 节点）：
       · 点抽屉那一拍 = 236ms longtask / 3 条（短会话 0ms），且随会话长度暴涨；
       · CPU profile：「(program)」（样式计算/布局/绘制）1216ms、宿主自己的
         「getAnimations({subtree:true})」 75ms、宿主 JS 个位数 ms、**插件 JS ≈ 8ms**
         ⇒ 代价是「大 DOM 的布局/绘制」，不是插件逻辑。
     这里让会话滚动区里的**消息块**自己跳过屏外内容的布局/绘制（窄选择器：只命中
     滚动区的直接子块，本次实测 3 个；宿主的 scrollBody 自身用 :not 排除）：
       · 运行时注入 A/B（同一会话，每臂 n=3）：点抽屉长任务 关 = 247/62/143ms
         （复测 386/206）→ 开 = **57/61/70ms**（紧致一条 ~60ms 带）；
       · 「contain-intrinsic-size: auto 320px」：auto 表示「渲染过就用记住的真实高度」，
         只有从未渲染过的块才吃 320px 兜底 ⇒ 本次实测 scrollHeight 17699 前后不变、
         置顶/置底位置正常，没有滚动跳变。 */
  [data-mobile-nav="frame"] [class*="scrollBody"] [class*="_scroll"]:not([class*="scrollBody"]) > * {
    content-visibility: auto;
    contain-intrinsic-size: auto 320px;
  }
  /* ---------- 文档预览的代码行：屏外行跳过布局/绘制（2026-10-09 实测） ----------
     报障场景：打开一份 13.4k 行文件的文档预览（DOM 23,662 节点）后整页卡死。
     真机读数（Android 16 WebView）：单帧最差 269 / 310 / 395 / 485 / 530ms，
     rAF 帧率掉到 2 / 5 / 17，长任务每秒合计最高 1031ms；加上下面这条规则后
     最长帧 33ms、帧率 88–119、长任务归零，只剩首次挂载建 React 元素的那几帧。
     代码行行高统一（约 20px），所以 contain-intrinsic-size 的估值精确，滚回很久
     以前的历史也不会跳滚动条 —— 这正是它与上面那条消息块规则（高度差异大、
     兜底给 320px）分开写的原因。
     选择器保持窄：只在移动 frame 内，且必须落在宿主文档预览模块自己的代码行
     容器里（dsh-client-ui-sidebar-documentpreview 的 TextPreview：_textDocument
     容器中的 _line 行）。同模块的 _preview 是更泛的词根，chat / conversation /
     deliverables 等别的宿主模块也有这个名字，纳入会越界改到那些面板，所以不收。
     这一条只省布局与绘制，不省 React 挂载的 JS，「切换会话」的卡顿不在它的射程内。 */
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

  /* ---------- DSHA 集成层：预设 chip 布局（宿主 @deepseek-ai/dsh-client-ui-agent-preset
     的 DSHA 补丁标记 .dsha-preset-header-anchor / [data-dsha-agent-preset]）。
     按 CSS 宽分两档，自动切换：
     ① 结构修正：图标与下拉箭头都是 position:absolute; left:0，会双双叠在
        「标准模式」文字上。这是宿主 DOM 决定的 bug，**任何手机档都要修** ——
        否则换到 768–1023 的平板/折叠屏就复现同一处叠字。
     ② 按 360px 实测钉出来的调优值（left 归零、团队 chip 在场时预设名 4 字上限）：
        只在「真·手机」档（CSS 宽 ≤ 767px，对齐上游 768px 平板档边界）生效；
        768–1023 保留上游手机 UI 的排布，不套这台手机的魔数。
     非 DSHA 宿主上没有这些标记，整块天然不命中（死规则）。 ---------- */
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
    /* 左右内边距 6 → 4（2026-09-23 用户拍板）：与 6 → 4 的三级间隙一起，
       把「标准模式 / 智能体 / 文件夹」收成一组；文字本身不受影响。 */
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
  /* 预设 chip 的 ⌄ 翻转（上游/DSHA 都没给这个 v 做开合指示；子代理 chip 有宿主自带的）。
     两件事必须同时成立才修得好：
     ① 上面那条 > svg 写过 transform: none !important，任何旋转都会被它压死 —— 这里用
        svg:last-of-type 提高特异性 + !important 接管，**不去删那条通用规则**（它还管着图标 svg）。
     ② 钩子各走各的：预设 chip 只有 aria-expanded 属性，子代理 chip 是宿主自己的
        .ZKlsPq_triggerOpen 类。所以这里只锚 [data-dsha-agent-preset="header"]，
        **完全不碰子代理芯片**，改这边不会把那边压掉。
     svg:last-of-type 取 chip 里最后一个 svg（下拉箭头）；只有一个 svg 时同样命中。
     时长 .12s 与子代理 chip 自带的 transition 一致，两个 v 观感统一。 */
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
  /* ② 真·手机档（CSS 宽 ≤ 767px）才生效的调优值。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor {
      /* 宿主 cubgiG_menuAnchor 带 left:-8px（原本是给弹层对位用的），
         手机上和标题窗口右缘叠 2px；这里把它拉回 0，整体右移 8px。 */
      left: 0 !important;
    }
    /* 智能体团队 Web 开启后头部多一个 Agent Team chip；预设名超过 4 个字就会
       把它挤掉（实测 6 个字时 Agent Team 被裁成「Agent Te」并压住文件按钮）。
       此时把预设名收成 4 个字 + 省略号 —— 完整名字在预设菜单里点开即达。 */
    [data-mobile-nav="frame"] [data-phase] header:has([data-team-action]) .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > span {
      max-width: 4em;
    }
  }
  /* ---------- 会话行的 ⋯ 菜单在触屏常显（2026-09-22 交互契约） ----------
     宿主只在 :hover 和 menuOpen 时显示 _rowActions，而手机没有 hover。
     长按改名已于 2026-10-07 下线（真机上它依赖的事件重放到不了宿主），
     所以这个锚点是触屏到达重命名 / 分叉 / 归档 / 删除的唯一入口，必须常显。
     行内布局不动：标题是 flex:1 + min-width:0，自己让位并省略；host 的
     time / pinIndicator 保持原样。只作用于抽屉里的会话行，搜索行
     （searchResultRow）不受影响。 */
  [data-mobile-nav="frame"] [class*="sessionRow"] [class*="_rowActions"] {
    display: inline-flex !important;
  }
  /* ---------- 归档 / 置顶快捷按钮在手机档收进 ⋯ 菜单（issue #154） ----------
     宿主把两颗快捷按钮注册进「sidebar.workspaces.session.row.action」槽位
     （archive order 100 / pin order 200）；_rowActions 一常显，抽屉里每一行
     就多两颗图标、行尾发挤，而手机用户多数并不需要这两个入口。四个动作
     （归档 / 取消归档 / 置顶 / 取消置顶）在 ⋯ 菜单里都有，所以按宿主自己的
     aria-label 精确匹配隐藏它们——⋯ 锚点的 label 含会话标题（「会话“X”的
     操作」/「Session actions for X」），精确匹配不会误撞。宿主字典目前只有
     zh/en 两套（2026-10-10 核对 0.2.0-rc.2 产物），换到别的语言时本规则静默
     失效：按钮照常显示，不会隐藏错东西；第三方注册进同一槽位的快捷按钮也
     不动。 */
  [data-mobile-nav="frame"] [class*="sessionRow"] [class*="_rowActions"] :is([aria-label="归档会话"], [aria-label="取消归档"], [aria-label="置顶会话"], [aria-label="取消置顶"], [aria-label="Archive session"], [aria-label="Unarchive session"], [aria-label="Pin session"], [aria-label="Unpin session"]) {
    display: none !important;
  }

  /* ---------- 提问卡（ask-user）头部按钮热区（issue #140） ----------
     宿主 dsh-client-ui-user-questions 的 QuestionComposer 头部两颗图标按钮
     ——「收起问题卡片」与「放弃整组问题」——官方 24×24px、headerActions
     gap 4px，远低于触控下限；「放弃」紧贴「收起」（放弃 = pending.cancel()
     后 actions.clear，整组草稿不可恢复地清空，宿主无确认），单手误触即丢
     内容。手机档原地放大命中盒（::after 透明扩展，墨迹与版式零变化），
     同时拉开两颗按钮的节距：24px 按钮 + 12px gap + ±4px 扩展 = 32px 命中
     盒、命中盒之间净空 4px——扩展幅度若超过节距的一半，两颗命中盒会互相
     重叠，反而制造新的误触，这是本组数值的硬约束。
     作用域：头部动作区专用（_headerActions 后代），翻页器的 prev/next 也
     是同族 iconButton，但节距只有 6px，吃不下 ±4px 扩展，不掺和。哈希族
     Mbwy4a_ 是该包 QuestionComposer.module.css 的稳定前缀，哈希变更时整组
     规则自动失效，不误伤别家（哈希子串锚，非后缀锚，见 pitfalls「哈希子
     串」）。 */
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
  /* ===== 手机档作用域补充（2026-10-13）=====
     下面四组是本批（2026-10-12/13）顶栏改动里「共用档已还原成本批之前的值、
     手机档需要另一个值」的那部分。它们与本文件里其它 @media (max-width: 767px)
     and (pointer: coarse) 嵌套块同权限 —— 同层 + 同特异性 + 书写在后，覆盖共用档。 */
  /* ① 标题簇右侧预留：共用 46px（给右端文件夹按钮）→ 手机档 34px。
     34 是 2026-10-12/13 那串「46 → 40 → 36 → 32 → 26 → 34」的终值，每一档的动机：
       46 → 40：后台任务圆点搬进标题行后偏左、与预设 chip 显得拥挤，收 6px 整簇右移；
       40 → 36：文件夹按钮盒子从 x=349 起、36px 宽，36 让整簇右缘正好贴到它的左沿
                （视觉上离图标仍余 10px 内边距），是**安全上限**，再往下会压进命中区；
       36 → 32：与「文件夹按钮右移 4px」**配对** —— 只移按钮不缩本值，两者之间会凭空
                多出 4px（用户要的是整组右移，不是拉开）；
       32 → 26：与「文件夹座位 36→30」**配对** —— 座位窄 6px 后其左沿右移 6px；
       26 → 34：用户反馈圆点离文件夹太近（0px），左下 8px 后按钮盒内还有 7px 内边距到
                图标，视觉距离约 15px。代价：整簇左移 8px，标题窗口相应窄 8px。
       2026-10-17：数值不变，只把这条预留从 padding 改记 margin —— 理由同共用档那条
       （滚动容器的裁剪线在 padding box，padding 预留挡不住溢出，chip 会被文件按钮
       压住）。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_titleCluster"] {
      padding-right: 0 !important;
      margin-right: 34px !important;
    }
  }
  /* ② 标题行三个（标题 / 预设 / 后台任务）之间的间距：共用 4px → 手机档 6px
     （2026-10-12 用户要求；本条管「标题 ↔ 右侧 chip 簇」）。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
      gap: 0 6px !important;
    }
  }
  /* ③ 标题本体：共用 100px 横滑窗 + clip（截断处是硬切，像被裁掉）→ 手机档
     可扩展 + 省略号（2026-10-12 用户规格）。第一轮收 80px 硬顶，第二轮取消硬顶改
     100%：硬顶的问题是「关掉团队 chip 后腾出的空间标题吃不到」；改成 100% 后由 flex
     决定 —— 右侧各 chip 是 flex 0 0 auto（不收缩，先拿够自己的地方），标题再吃掉
     剩下的，不够时由本条 ellipsis 收尾，既有 72px 地板（在 _crumbs 上）仍兜底。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
      max-width: 100% !important;
      overflow-x: hidden !important;
      text-overflow: ellipsis !important;
      /* 共用档保留本批之前的 pan-x（那时标题是横滑窗，横滑要先被浏览器认领，
         免得左缘抽屉手势抢走这一笔）；手机档改成不横滑的省略号窗口 ⇒ 横滑认领
         随之撤掉，否则手势层的判定与实际可滚动性对不上。
         写成 pan-y pinch-zoom（而不是 auto）与全库其余的 touch-action 声明同形，
         tests/ios-zoom-guard.test.ts 会逐条检查「非 pan-x 的值必须含 pinch-zoom」。 */
      touch-action: pan-y pinch-zoom !important;
    }
  }
  /* ④ 动作行内各 chip 之间：共用 4px → 手机档 6px（2026-10-12 用户要求）。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
      gap: 6px !important;
    }
  }
  /* ===== 对话区留白 + 输入框高度（2026-10-20，店主截图定档）=====
     店主三处「留白太多」＋「输入框太小」，只对真·手机档生效（平板保持上游排布）。
     真机读数（1380×3036，dpr≈3.83，下同）：
       · 思考行文字底 → 运行状态分隔线 99 设备px、分隔线 →「深度求索中」文字 59（合计 158）；
       · 「深度求索中」文字底 → 输入卡片上缘 82；
       · 回复正文文字底 → 动作行图标顶 112。
     每条都取宿主自己那一层旋钮，不碰别的间距（一次只动一处，便于逐条回滚）：
       ① 运行状态是宿主的稳定标记 [data-chat-running]，它自己声明 --dsh-chat-flow-gap:12px
          给自己的 margin-top 供值；分隔线内距是 8/10。首轮按「旋钮各自减半」落成 6/4/5，
          真机复测「思考行文字底 → 分隔线」只从 99 降到 63（约 64%，不是 50%）——
          因为两行文字各自的 29px 行盒里还有约 25 设备px 的固定行距收不掉。于是第二轮按
          「文字到文字减半」反推：三条边距合计压到 11px（运行状态 margin-top 4、分隔线上 3、
          下 4），实测 99 → 51、158 → 86，才是店主眼里的「一半」。这一条同时把宿主
          「turn-process 之后 16px」那一档压平（合计仍是 8px）；header 那套页签规则不参与。
       ② 「深度求索中」到输入卡片之间没有别的留白，就是聊天滚动区的 padding-bottom；
          同样是先 16 → 8（实测 82 → 50，只有 61%），再收到 6px（实测 82 → 42）。
          滚动区用「直接子节点带 data-chat-flow」认 —— 带这个标记的元素不止一处
          （step-process 的内容容器也带），靠「_scroll 祖先 + 直接子代」两点收窄才不会误伤。
       ③ 动作行挂在 turn-tail（[data-turn-tail]）里，与正文的间距 = 流间距 12px +
          动作行自身 margin-top 4px ⇒ 把承载它的 flowItem（data-chat-flow-kind="turn-tail"）
          的流间距压到 4px，合计 8px，正好一半。
       ④ 输入卡片内的上下分布（2026-10-20 二轮）：首轮把编辑器 min-height 抬到 40px，
          真机看「文字 ↔ 功能键」空出 19px（卡片 65 → 77），功能键离卡片底缘却只剩 2px，
          店主两处都不满意。改成「文字段落回原高 + 给功能键留出底部呼吸」：编辑器回到共用档
          的 28px（本条覆盖物一并撤掉），行内底部给 6px——功能键相对卡片底缘上移 6px（2 → 8），
          文字与功能键之间由 19 回到 7px，卡片净高 71px（键行 30px 时为 73px）。
          会话区底部吸底，长高只往上吃聊天区，底部 stats 行不位移。
       2026-10-20 十轮（店主：「不行太挤了，还原吧」）：七～九轮对消息流的收紧
       （turn-tail 内部 gap 0、userRow gap 2 与时间行盒 20、turn-process 5、上下文环命中盒 -3）
       已整段删除，全部回到宿主默认值（16 / 6 + 28 / 16 / -4）；上面 ①～④ 是本轮之前
       店主已逐条验收过的值，保留。 */
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
  /* ===== 输入区功能键尺寸（2026-10-20 三轮 → 四轮）=====
     三轮初版把整行盒子统一抬到 32px，真机看「很不协调：偏中间那几颗（权限 / 模型 /
     语音）下面很空」——圆键（加号、发送 / 停止）有可见圆底，盒子就是它的可见边界；
     中间几颗是**无底图标键**，盒子加高只会给图标多垫一圈透明空白，眼睛读到的是
     「图标变小了、下面还空着」。所以盒子尺寸按元素分档，不再统一：
       · 有可见圆底的：加号、📎、发送 / 停止 = 30px（比原来 28 大一档，比 32 收一档）；
         📎 也走这一档，margin-left 由 -8 收到 -9（盒子 +2px 的一半），图标中心原地不动；
       · 无底图标键（权限 / 模型 chip / 语音）= 盒子不动，靠**图标**变大，一律 18px：
         加号 14、权限 14（.iWlSmW_triggerIcon svg）、模型 16、📎 与主键 16；
       · 上下文环不动 —— 它本来就是 18px 的仪表，图标统一到 18 后整行反而齐了。
     上一轮为「图标键」加的那两条透明 min-height 已删除（就是它们制造的空白）。 */
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
      /* 宿主 .uV2eYG_primary 自带 transform: translateY(-2px)（给 34px 主键做光学对齐）。
         现在整排键同高 30px、盒中心对齐，保留 -2px 会让发送/停止键比旁边高 2px
         （真机实测：蓝键中心 2817、其余图标 2825）。这里归零。 */
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
    /* 图标统一 18px（宿主：加号 14、主键 16；📎 是我们自己的控件，也是 16）。 */
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) [class*="_add"] svg,
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] svg,
    [class*="_trailing"] > [class*="_primary"] svg,
    [data-mobile-nav="frame"] [data-phase] [data-composer-card] [data-mobile-nav="file-upload"] svg {
      width: 18px !important;
      height: 18px !important;
    }
    /* 无底图标键：权限 / 模型 chip / 语音，图标一律 18px（宿主：权限 14 / 模型 16 /
       语音本来就是 18）。**两种形状都要写**：权限把图标包在 _triggerIcon 包装里
       （iWlSmW_triggerIcon > svg），模型的 triggerIcon 直接挂在 svg 自己身上
       （ModelSelect 的 IconDataOutlineRegular 带 className=triggerIcon）—— 只写
       「… _triggerIcon svg」时模型那颗静默不中（四轮真机复测墨迹 55 → 56 设备px）。
       上限 18：再大就会压到 ⌄ 的节距（图标与 chevron 之间只有 ~2px）。 */
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_triggerIcon"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] [class*="_triggerIcon"] svg,
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_triggerIcon"],
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] button[class*="_trigger"]:not([aria-haspopup]) svg {
      width: 18px !important;
      height: 18px !important;
    }
    /* ===== 功能键颜色统一（2026-10-20 五轮）=====
       店主：「功能键有部分是加黑加深的，有部分是灰色的，颜色不协调」。
       真机取色（设备像素，按最暗的实心像素）：
         · 加号 #07080c、📎 #08070c、语音 #08080a —— 近黑（label-primary）；
         · 权限 ⚠ #5a5c60、模型图标 #595c62 —— 灰（label-secondary）；
         · 两个 ⌄ #a6aaab —— 更浅一档（label-caption），宿主自己给 ⌄ 的层级。
       三种来源：加号是宿主 .uV2eYG_add 的 color:label-primary；📎 是我们的按钮
       color: inherit（吃到卡片正文色）；语音键没写 color 所以继承；
       权限/模型是宿主写死的 label-secondary。手机上把整行图标统一到
       **label-secondary**（与占多数的权限/模型/环一致，⌄ 仍留 caption 层级），
       发送键保持蓝色主色不动。只作用于手机档：平板/桌面保留宿主原本的分层。
       语音键不能靠「:not([aria-haspopup])」认：语音不可用时宿主会临时给它
       aria-haspopup="dialog"（打开安装引导），那一刻颜色就会漏掉、又变回黑的。
       改用它的锚点包装 _triggerAnchor > button。
       想反过来走深色的话，把这五条里的 token 换成 --dsw-alias-label-primary 即可。 */
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
  /* ---------- dsh-web-ui family compatibility ----------
     The linxin666 plugin suite extends the shell frame directly:
       - aionui-panel appends two trailing grid columns (explorer / preview)
         plus absolute drag handles to [data-dsh-frame]; its 5-track inline
         grid is already overridden above, but the handles and columns would
         still float over the main UI. On mobile the columns leave the grid
         as floating bottom sheets and keep their own visibility state —
         the suite's collapse chevron / preview tabs still work, so no
         feature is lost. The task-board / ssh plugins inject sidebar
         entries and center-column takeover panels; the entries need
         spacing and the kanban needs scrollable columns. */

  /* Touch devices: the drag handles are useless — the floating expand
     button is the opener. */
  .aionui-explorer-handle,
  .aionui-preview-handle {
    display: none !important;
  }

  /* Shared base: both columns leave the grid as floating panels. The
     explorer is gated shut by default (its own persisted expanded state
     must never cover the mobile UI on load); the header Files action opens
     it via the frame marker below, and the sheet's own collapse chevron
     clears it. Preview stays owned by the suite (hidden while no tab is
     open). The per-column rules below override the geometry. */
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    position: fixed !important;
    z-index: 55 !important;
    background: var(--aion-bg-base, #ffffff) !important;
    border-left: none !important;
  }
  /* Explorer (file tree) bottom sheet: bottom edge aligned exactly with
     the composer card's bottom line — the card sits 36px above the
     viewport bottom (8px composer padding + the 28px stats strip below
     the card), so the sheet uses the same 36px bottom offset. */
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
  /* Preview (file content) bottom sheet. Gated shut by default: the suite
     persists open preview tabs in localStorage and restores them on load,
     which would pop the sheet over the fresh UI. The client only sets the
     frame marker after the user taps a file row in the explorer; the
     suite's own collapse chevron clears it via the visibility watcher. */
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
    /* Fullscreen toggle (issue #8): animate the geometry change instead of
       snapping. visibility is deliberately not listed, so opening/closing
       the sheet stays instant; the open/close keyframes own transform. */
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
  /* User-opened preview sheet (frame marker, set on file-row tap). */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] {
    visibility: visible !important;
  }
  /* The Files action opens the explorer sheet (frame marker). */
  [data-mobile-nav="frame"][data-aionui-explorer-open] [data-aionui-explorer-col] {
    visibility: visible !important;
  }
  /* While the preview sheet is up, the explorer sheet yields (two stacked
     bottom sheets would read as one broken overlay). Closing the preview
     via its collapse chevron / tab close clears the marker, and the
     explorer sheet returns. Same specificity as the explorer-open rule, so
     this must stay AFTER it. */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-explorer-col] {
    visibility: hidden !important;
  }
  /* The open drawer must never sit under a sheet: while the frame is in the
     narrow-expanded state both sheets yield (later in the file than the
     open marker rule, so it wins at equal specificity). The fullscreen
     toggle is a descendant of this column, so it is hidden with the rest of
     the column — there is no separate drawer-open rule for it. */
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-explorer-col],
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-preview-col] {
    visibility: hidden !important;
    display: none !important;
  }
  /* The suite's own expand button reads the store state we bypass on
     mobile — hide it; the header Files action is the opener. */
  .aionui-floating-expand {
    display: none !important;
  }

  /* Preview sheet fullscreen toggle (issue #8): a fixed button parked in the
     sheet's titlebar row, just left of the suite's collapse chevron (24px at
     right:8px of the sheet, and the sheet spans 8px..(100vw-8px)). The top
     calc mirrors the sheet geometry above (bottom 40px + min(50dvh, 420px));
     when the frame carries "data-mobile-preview-full" the sheet goes
     fullscreen and the button moves to the viewport corner. */
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
    /* Native look: same size/radius/hover language as the suite's tab-bar
       icon buttons (the 20px panelCollapse next to it). The button lives
       INSIDE the preview column, so it rides the sheet's own open
       animation and geometry transition — no curve matching needed. */
    transition: background-color .15s, top .24s var(--ds-ease-out, ease-in-out);
  }
  [data-mobile-nav="preview-full-toggle"]:hover {
    background: var(--aion-bg-3, rgba(0, 0, 0, .22));
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
  /* Keep the last tab (and the "+" URL-tab trigger) from sliding under the
     fullscreen toggle: reserve the right end of the preview tab row. */
  [data-aionui-preview-col] [class*="_tabScroll"] {
    padding-right: 34px !important;
  }
  /* Visible only while the preview sheet is open. Visibility itself is
     inherited from the column, so the sheet's own hide rules (collapse,
     drawer open) cover the button too. */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] {
    display: inline-flex !important;
  }
  /* Icon swap on the frame fullscreen marker. */
  [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-in {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: inline !important;
  }
  /* Fullscreen preview: the sheet fills the whole viewport (notch included);
     the safe-area padding drops the titlebar row below the status bar, and
     the toggle follows the titlebar into the top corner. */
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
  /* Fullscreen: the column fills the viewport, so the button follows the
     titlebar row down below the notch. */
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

  /* dsh-web-ui sidebar entries (task board / ssh) sit flush against each
     other — give the injected rows breathing room. */
  button[data-dsh-taskboard-entry],
  button[data-dsh-ssh-entry] {
    margin-bottom: 8px !important;
  }

  /* Task board: five kanban columns at minmax(0,1fr) crush into ~78px phone
     strips. Give every column a usable minimum and let the row scroll. */
  [data-dsh-taskboard-board] > [class*="_columns"] {
    grid-template-columns: repeat(5, minmax(240px, 1fr)) !important;
    overflow-x: auto !important;
  }
  /* The floating button must not float over a takeover panel (task board /
     ssh own the center column while active). */
  html[data-dsh-taskboard-active] [data-mobile-nav="fab"],
  html[data-dsh-ssh-active] [data-mobile-nav="fab"],
  html[data-dsh-taskboard-active] [data-mobile-nav="backdrop"],
  html[data-dsh-ssh-active] [data-mobile-nav="backdrop"] {
    display: none !important;
  }
  /* Board header: let the search field take the slack instead of squeezing
     the action buttons. */
  [data-dsh-taskboard-board] > [class*="_boardHeader"] [class*="_search"] {
    flex: 1 1 auto !important;
    min-width: 80px !important;
  }

  /* ---------- dsh-web-ui polish: plugin market search ----------
     The market tab row (Discover / Themes / Installed + the plugin search
     box) is a no-wrap flex: at 390px the tabs plus the ~218px search box
     (~475px total) overflow the ~334px sheet and the search box runs off
     the right edge of the screen (it also forces a horizontal scrollbar on
     the sheet's options area). Let the row wrap: the tabs keep the first
     line and the search box gets its own full-width second line. */

  [aria-modal="true"] [class*="_tabs"] {
    flex-wrap: wrap !important;
    row-gap: 8px !important;
  }
  [aria-modal="true"] [class*="_searchInline"] {
    flex: 1 1 100% !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  /* iOS Safari auto-zooms a focused input whose computed font-size is below
     16px. dshmarket's tab search uses the shared primitive Input at 13px;
     raise only this market-owned field on mobile so focusing it keeps the
     current viewport scale. Scoped to the market root to avoid changing
     unrelated settings/search fields; pinch zoom stays available. */
  [data-dsh-market-root] [class*="tabSearch"] input,
  [data-dsh-market-root] input[class*="tabSearch"] {
    font-size: 16px !important;
  }

  /* ---------- dshmarket polish: Tasks operations popup ----------
     Upstream .opPanel is a small dropdown pinned to the right edge of its
     ~54px trigger button; on a phone it reads as stuck to the sheet edge
     instead of centered. Promote it to a fixed, viewport-centered card:
     no ancestor between the popup and the viewport carries a transform,
     so position:fixed centers against the real viewport (a plain left:50%
     would resolve against the tiny relative trigger wrapper and land even
     further right). The upstream 86vw width cap, 70vh max-height and
     internal scroll all still apply; the close button stays inside.
     2026-09-25: re-anchored from [data-mobile-nav="frame"] [aria-modal]
     to the market's own root marker — since rc.2 the whole settings
     sheet (market included) is portaled to <body> and no longer matches a
     frame-descendant selector. */
  [data-dsh-market-root] [class*="_opPanel"] {
    position: fixed !important;
    top: 50% !important;
    bottom: auto !important;
    left: 50% !important;
    right: auto !important;
    transform: translate(-50%, -50%) !important;
  }

  /* ---------- dshmarket polish: header title row ----------
     The title row (icon + title + repo link + version + optional
     "Update market" / "Update all" buttons) is a nowrap flex whose
     natural width (~450px with both update buttons) exceeds the ~334px
     sheet. Flex then crushes the flexible items below their content
     width and every label wraps word-by-word — the "text turns
     vertical" report. Trigger is state-dependent (the buttons only
     exist while plugin updates are pending), which explains the
     sometimes-horizontal/sometimes-vertical flapping. Let the row wrap
     instead: line 1 keeps icon + title + repo + version, the update
     buttons get their own full-width-feeling second line, and the title
     itself is locked to one ellipsized line no matter what follows it.
     2026-09-25: re-anchored from [data-mobile-nav="frame"] [aria-modal]
     to the market's own root marker — since rc.2 the whole settings
     sheet (market included) is portaled to <body> and no longer matches a
     frame-descendant selector. Same day, second pass: the ported rule's
     flex:1 1 auto on the title GREW it to fill the row, which pushed the
     repo link and the version "v1.65.1" to the far right — exactly where
     the pinned toolbar's close ✕ sits, crowding the corner the owner
     reported as "很容易误触" (hit-test: the version box reached x≈378,
     the close ✕ starts at x=350). flex:0 1 auto keeps the title at its
     natural width (repo + version pack left, as upstream intends) while
     still letting it shrink-and-ellipsize when the update buttons force a
     wrap — the wrap rule above, not flex-grow, is what makes room for
     them. */
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

  /* ---------- dshmarket polish: card byline stays on one line ----------
     The byline (avatar · owner · version · ↓downloads · ★stars) is a
     wrapping flex row by upstream design — the market would rather drop
     the counts to a second line than over-shrink the owner name (its
     .owner carries flex:0 1 auto + ellipsis + min-width:44px exactly for
     that). At a phone's card width the break point lands mid-row though:
     everything but the star fits, so a lone "· ★ 8k" wraps onto its own
     line under the author — inconsistent with the cards that happen to
     fit, which reads as a rendering bug (owner report 2026-09-25,
     IMG_4208: dsh-remote-web-ui and dsh-skill-explorer both orphaned the
     star). Pin the row to one line instead: the owner is the market's own
     flexible item, so it absorbs the squeeze and the counts stay whole.
     Desktop cards are far wider than the row and never wrapped anyway. */
  [data-dsh-market-root] [class*="_byline"] {
    flex-wrap: nowrap !important;
  }

  /* ---------- dshmarket polish: top inset ----------
     The sheet is pinned to the top of the screen (A': top = safe-area +
     12px) and the market page started FLUSH against the sheet's top
     edge — measured 2026-09-25: the title row's gap from the sheet's top
     was 0px while the pinned close ✕ sat 10px under it, so the whole
     page read as crushed against the boundary (owner report, IMG_4211:
     "最上面快要顶到边界了... 把整体往下移一点，有点留白会更美观").
     Give the page the same 12px inset its own horizontal padding already
     has (the root box was 12px from each side, 0px from the top), so the
     title lands ~12px under the sheet's rounded corner, level with the
     close ✕. The market page is the sheet's CONTENT, so it moves; the
     pinned toolbar (close ✕) belongs to the sheet and deliberately does
     NOT move ("关闭按钮可以不动"). Scrolls away naturally with the page.
     Desktop market is vertically centered with the host's own clearance
     and never had this read; the rule is mobile-only. */
  [data-dsh-market-root] {
    margin-top: 12px !important;
  }
  /* Below ~360px the fixed-width counts plus the owner's 44px min overrun
     the card, so the ellipsis eats most of the name ("omds..."). Trade text
     size for name length on the smallest phones: 11px → 10px text and
     6px → 4px gaps buy the owner roughly a third more room while the row
     stays one line. Tablet/phone tiers above this width are unaffected. */
  @media (max-width: 360px) {
    [data-dsh-market-root] [class*="_byline"] {
      gap: 4px !important;
      font-size: 10px !important;
    }
    [data-dsh-market-root] [class*="_byline"] [class*="_dot"] {
      margin-left: 3px !important;
    }
  }

  /* ---------- dshmarket 1.20+ compat: keep the settings nav visible ----------
     Upstream Market.module.css hides the host dialog's nav on phones
     ([role=dialog]:has([data-dsh-market-root]) > nav { display:none } at
     max-width:560px) so the market can take over the dialog; its comment
     assumes the host keeps "its own close button in the content header".
     Our host's only close ✕ lives inside that very nav, so the market
     would leave no categories and no way back or out (dead-end UI,
     2026-08-23). Mirror upstream's exact media condition and restore the
     nav: categories row + ✕ stay above the inline market page.
     2026-09-25 (rc.2 regression): 0.1.7-rc.2 renders this sheet through
     createPortal(..., document.body), so the frame-scoped selector matches
     nothing on rc.2+ hosts and the market takeover silently won — no
     categories row above the market page on every updated phone. The twin
     rule below carries the same declaration on a structural anchor
     ([role=dialog]:has(...) > nav, no frame prefix): on rc.1 hosts the
     frame-scoped rule does the work and the twin is inert (the sheet is a
     frame descendant there); on rc.2+ the twin carries it. Both stay
     inside this file's mobile media wrapper, so desktop never sees them.
     Premise note: this host generation keeps its close ✕ in the CONTENT
     header (pinned top-right — see layout.css), so the dead-end half of
     the 2026-08-23 report no longer applies; the rule is kept and twinned
     for the categories row it restores (guarded by the test suite). */
  @media (max-width: 560px) {
    [data-mobile-nav="frame"] [role="dialog"]:has([data-dsh-market-root]) > nav {
      display: flex !important;
    }
    [role="dialog"]:has([data-dsh-market-root]) > nav {
      display: flex !important;
    }
  }

  /* ---------- dsh-usage-stats polish: usage & balance panel ----------
     The panel's stats row shows three token counters side by side
     (today / month / total). The counters use tabular nowrap figures whose
     min-content width overflows the ~336px panel body on a phone: figures
     clip at the row's edges and the panel grows a horizontal scrollbar.
     Stack the three counters vertically — full-width rows, so the figures
     always fit. */

  [class*="usg_"][class*="_statsRow"] {
    flex-direction: column !important;
  }
  [class*="usg_"][class*="_stat"]:not([class*="_statsRow"]) {
    flex: 0 0 auto !important;
    width: 100% !important;
    min-width: 0 !important;
  }

  /* ---------- dsh-web-ui polish: settings sheet ----------
     Keep the nav tabs on ONE horizontally scrolling row. Setting rows need
     no mobile rework: the host redesigned them into compact space-between
     rows (text left, control right — verified in
     dsh-client-ui-settings-general .Pt1bsG_row, 2026-09-24). The old
     "stack each row" rules, written for the previous two-column generation
     with its dead label/control gap, now fight that design and double every
     row's height; they were removed (see the tombstone below). */

  /* Nav tabs + toolbar: TOMBSTONE (2026-09-25). This whole family —
     the single-row scroller, its hairline scrollbar, the compact cells and
     the hidden "Open configuration file" button — was scoped to
     [data-mobile-nav="frame"] because rc.1 rendered the settings sheet in
     place, inside the app frame. rc.2 wraps the sheet in
     createPortal(..., document.body): the overlay is a direct body child,
     nothing inside it matches a frame-descendant selector, and every rule
     here went dead at once. The live symptoms were the nav cells wrapping
     into uneven rows that slid under the 138px toolbar and the config-file
     button reappearing in that toolbar (owner report 2026-09-25). The
     portal-aware replacements live in layout.css.ts, in the "Settings
     dialog on mobile" section, anchored on the same structural
     :has(> :first-child > :last-child > button) gate (settings sheet only;
     export dialog and directory picker stay excluded). Nothing to restore
     here — do not re-add behind a frame selector. */
  /* Setting rows: no mobile rework — the host renders compact space-between
     rows natively (.Pt1bsG_row: text left, control right, 16px vertical
     padding, .5px divider). The previous "stack each row" rule family
     (column + gap:8 + control width:100% + the 36×20 switch cap that undid
     it) was written for the old two-column generation; on the redesigned
     host it doubled every row's height — the "settings feel vertically
     empty" report 2026-09-24 — and was removed in full. If an older host
     generation ever needs stacking again, reintroduce behind a generation
     guard, not as a blanket [class*="_row"] override. */
  /* Models provider editor: a CLOSED <details> ("_customized", the customized
     models section) must not paint its body. This engine paints the ~1500px
     model catalog of the closed details as a ghost layer anyway: it overlays
     the editor's own action rows (Fetch/Cancel/Apply/Add model) and the
     provider rows BEFORE the editor row in DOM order (those paint under the
     ghost and lose hit-testing), while rows after it paint above. Result
     (owner report 2026-09-19): providers cannot be deleted, "fetch available
     models" does nothing — every tap lands on whatever row overlaps the
     ghost. The host layout is computed for the collapsed details (editor
     217px, rows 903px), so the fix is to restore what the browser should do
     on its own: hide the body while the details is closed. Tapping the
     summary then opens it for real (details 33 → 1532px, rows re-flow,
     every button hittable — verified in place before this rule was written). */
  [aria-modal="true"] details[class*="_customized"]:not([open]) > [class*="_customizedBody"] {
    display: none !important;
  }
  /* Owner dialog footers (_w1urq family: the provider delete confirm, the
     workspace rename dialog, ...): the footer buttons keep white-space
     normal, so any width squeeze — a narrow viewport, a long provider name,
     Android font scaling (owner report 2026-09-19, verified at 320px with a
     1.3x font bump) — wraps the label inside the fixed 36px row where the
     second line clips. Keep each label on one line and let the footer wrap
     whole buttons to a second row instead. */
  [role="dialog"][aria-modal="true"] [class*="_footer"] {
    flex-wrap: wrap !important;
  }
  [role="dialog"][aria-modal="true"] [class*="_footer"] button[class*="_button"] {
    white-space: nowrap !important;
  }
  /* Appearance mode group: give the cube row a consistent bordered
     segmented look (the official borders differ per state). */
  [aria-modal="true"] [class*="_cubeRow"] > * {
    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12)) !important;
  }

  /* ---------- dsh-web-ui polish: explorer sheet ----------
     The aionui explorer was designed for a desktop side column: compact the
     header, search box and tree rows so a phone shows more entries, and pad
     the scroll bottom so the last row never sits flush on the edge. */

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

  /* ---------- dsh-web-ui polish: drawer footer ----------
     The official footerActions row also hosts the remote-web-ui entry row
     (two icon buttons) and the usage/balance badge; without wrapping the
     groups squeeze each other on one line. Wrap so each group gets its own
     row. (The plugin's own footer pill was removed on 2026-10-18 — the
     session-log entry now lives in the drawer brand row.) */
  [data-mobile-nav="frame"] [class*="_footerActions"] {
    flex-wrap: wrap !important;
    gap: 6px !important;
  }

  /* ---------- dsh-web-ui polish: floating pet ----------
     The whale-girl pet (dsh-pet) floats at the viewport corner with a
     persisted, draggable position. On phones the pet is scaled down so
     it does not dominate the screen; the plugin's own drag + persist
     still work (the position itself is left alone — the mobile default
     position is seeded via the pet API to just above the composer). */

  body > [class*="_float"]:has([class*="_sprite"][role="button"]) {
    transform: scale(.66);
    transform-origin: bottom right;
  }
  /* While a modal dialog (settings sheet / export) owns the screen the pet
     floats ABOVE it and covers the dialog content; modal semantics say the
     background is inert, so hide the pet for the modal's lifetime. */
  body:has([aria-modal="true"]) > [class*="_float"]:has([class*="_sprite"][role="button"]) {
    display: none !important;
  }

  /* ---------- dsh-web-ui polish: conversation stats line ----------
     The official session-status row (turns / steps / LLM time / TTFT /
     cache) is long. The client marks the exact row with
     [data-mobile-nav="stats"] (text-anchored, hashed classes can't be
     targeted). Layout: ONE fixed-height (28px) flex strip that scrolls
     horizontally — the full metrics stream stays reachable by swiping,
     the row never grows vertically, no ellipsis or fade, 12px gaps
     between metric groups, a 2px scrollbar as the swipe affordance.

     2026-09-23 改档（店主："把那个滑动的压缩一下，固定住，不再滑动"）：
     真机探针实测 可见宽 251px、内容 390px（"10 轮 268 步·244 tok/s" 178 +
     "57.5M tok·缓存命中 99%" 187，gap 12、font 12），右边 ~75px 被 dock 里的
     上下文百分比那块占着 ⇒ 一行本来就放不下。按店主选择：**保持一行 + 末尾
     省略号**。做法：字号 12→10（≈0.83×）、组间距 12→6、去掉为滚动条留的
     4px 下内边距；overflow 改 hidden（不可滑）、滚动条显式干掉；第一组
     flex:0 0 auto 保持完整，最后一组 flex:0 1 auto + min-width:0 自己吃掉
     差额并在末尾出省略号（实测截到"…缓存命…"，tok 数字仍完整可读）。
     高度仍是 28px：composer 的底部占位（8px + 28px）不变，其它几何不跟着动。

      2026-10-06 加档（店主："输入框下面的轮次这一条和输入框底部的距离缩减一下，
      但不能挨得太近，还是要留出一点空间"）。**实测（真机同源 headless，工厂版）**：
      卡片 rect.bottom=808、轮次条 rect.top=812 → flex 间隙只有 4px，视觉间距
      ≈ 4px + 28px 行高里文字上方的约 5px ≈ 9px。所以只上提 2px（margin-top:-2px）
      → 视觉间距约 7px，仍留余量；早前的 -4px 会把间隙压到 0（就是"挨得太近"），
      已按实测改回。不改 28px 行高、不动 composer 底部占位（8px + 28px 几何契约）。 */

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
    margin-right: 6px !important;
    padding: 0 !important;
  }
  /* 第一组（轮次·步数·tok/s）保持完整。 */
  [data-mobile-nav="stats"] > *:first-child {
    flex: 0 0 auto !important;
    width: max-content !important;
    min-width: max-content !important;
    max-width: none !important;
  }
  /* 最后一组（tok 总量·缓存命中）吃掉剩余宽度，末尾省略号。
     2026-09-23 第二版修正：第一版把整组改成 display:block + 子元素 inline，
     结果药丸里的图标变成 inline、基线对齐错位（店主："图标都出现位移"）。
     这版保持 flex 对齐，只让药丸**内部的文字 span** 收缩 + 出省略号；
     图标 svg 固定不缩。另外把两组药丸的左右内边距压到 6px、组间距压到 4px，
     抠出来的宽度全部让给第二组（实测它原本只分到 76px 而需要 156px）。 */
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
  /* 药丸内部的 span/svg 有自己的字号（宿主 .pill 自带），只在外层设 10px 不会被
     继承进去 —— 真机上第二组仍差 ~20px 被省略号切掉，所以这里显式压到内部。 */
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
  /* 「上下文环」显示在输入框行的右簇（店主 2026-09-23 确认：
     环要、百分比数字不要）。font-size:0 只塌掉文本、环 svg 有显式尺寸不受
     影响；绝对定位盖在自建占位上（见下方 #104 注释），不再搬动节点。 */
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
  /* 文本要连**药丸内部**一起塌掉：药丸自带字号，只在外层设 0 不继承进去，
     真机上会留下半个 "46"。整棵子树 font-size:0，环 svg 用显式 px 不受影响。 */
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
    /* 宿主给药丸画的灰底/描边在输入框行里显得比环大一倍（店主："圆圈占了很多空间"），
       全去掉，只留环本身。 */
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
  }
  [data-mobile-nav="stats-ring"] svg {
    display: inline-block !important;
    /* 2026-09-29 验收定稿（issue #140）：16px 难瞄准、24px 过大、20px 仍偏大，
       店主拍板 18px；与 26×26 命中盒（见 layout.css.ts 的环规则）匹配。
       增宽由尾道左侧富余吸收，按键间距不变。 */
    width: 18px !important;
    height: 18px !important;
    flex: 0 0 auto !important;
  }
  /* 轨道深化（2026-09-29 验收，issue #140）：宿主轨道只有 12% 黑，环放大到
     24px 后深灰进度弧显得像残缺的加载圈（店主「什么玩意儿」）。加深到 25%
     让 donut 成完整圆环——是「用量表」不是「spinner」。子串锚 class*=_track
     与仓库哈希锚惯例一致，环标记内不会跨匹配（fill 类名不同）。
     #142：25% 黑改按主题取色——color-mix 取 25% 标签色（label-primary 浅色
     =近黑、暗色=近白），浅色维持 #140 验收观感，暗色自动翻成 25% 白（裸
     rgba(0,0,0,.25) 在暗色下不可见，环又退回残缺加载圈）；token 缺失时 var
     兜底 #000 与原值等价。border 族没有 25% 等价档，纯 var 兜底会让浅色回
     归宿主 12%，故用 color-mix；宿主 CSS 已用同款 color-mix+var 组合。 */
  [data-mobile-nav="stats-ring"] [class*="_track"] {
    stroke: color-mix(in srgb, var(--dsw-alias-label-primary, #000) 25%, transparent) !important;
  }
  /* 环与 TPS 读数不再搬动宿主 React 节点（#104：搬动后宿主卸载调 removeChild
     对不上父节点直接抛 NotFoundError，SlotErrorBoundary 把整个 composer 槽位
     清空）。节点留在 React 渲染的原位，可见槽位由插件自建占位顶住，宿主节点
     绝对定位盖在占位上；占位是插件节点，宿主重建/卸载都不经过它。 */
  [data-mobile-nav="stats-ring-reserve"],
  [data-mobile-nav="stats-tps-reserve"] {
    visibility: hidden !important;
    pointer-events: none !important;
  }
  [data-mobile-nav="stats-ring-reserve"] {
    flex: 0 0 auto !important;
    display: inline-block !important;
    /* 18px 与定稿的环（上方 svg 规则）同尺寸：占位顶住的槽位即环的落点，
       尾道左侧富余吸收增宽，按键间距不变。 */
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
  /* overlay 的定位上下文：宿主自己没定位时才生效（无 !important，宿主样式随时
     可以接管；stats-line 每帧按真实 positioned ancestor 计算，不受影响）。 */
  [data-mobile-nav="stats-ring-dock"],
  [data-mobile-nav="stats-tps-row"] {
    position: relative;
  }

  /* ---------- dsh-genui panel dock ----------
     The genui panel docks above the composer (conversation.input.dock,
     id genui-panel). On a phone its business-blue outline, generous chrome
     and single-line ellipsis read as an unfinished artifact: long titles
     truncate mid-word ("…default b···") with the chevron glued to the
     ellipsis, and the pill crowds the composer. Mobile treatment: neutral
     card border matching the composer, tighter chrome so the full title
     fits, chevron with breathing room. Scoped to the mobile frame marker —
     desktop keeps genui's own styling untouched. */

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

  /* ---------- git-graph branch chip: CSS re-anchor, no reparent (A′) ----------
     The branch chip (conversation.input.dock) floats between the dock rows
     and the input card; on a phone it reads as a stray capsule crowding the
     composer. #105: the old fix reparented the chip INTO the composer card,
     and React's unmount removeChild then threw NotFoundError into the
     SlotErrorBoundary (same root cause as #104). A′ re-anchors instead:
     the chip stays where React rendered it (inside the dock subtree) and
     the composerStack becomes the containing block, with the anchor
     constants = the card's static offset inside the stack + the original
     (12,12) corner offset. Constants measured 2026-09-24 (CDP, 393px):
     conversation phase card offset (16,0) → top 12 / left 28; hero phase
     card offset (16,122.9) → top 134.9 / left 28 (hero override below).
     The plugin's own sheet sets all four offsets on the anchor, so
     right/bottom must be neutralized too. Desktop untouched: the frame
     marker only exists below 1024px. Chip row geometry (2026-08-16, user
     feedback): 48px padding left a 16px dead gap and made the composer read
     too tall; 40px = chip (24px) at corner +12 + ~4px to the textarea; the
     chip has since grown to 28px (git-graph chip CSS), so the row is 44px
     (2026-09-06). The 44px clearance now keys off a STACK-level :has() —
     the chip is no longer a card descendant, so a card-level :has() could
     never match; the card disambiguation keeps non-composer cards (e.g. a
     todo card sharing the stack) out of the chip row. */
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

  /* ---------- dsh-meme 表情选择卡片：右缘安全距离 ----------
     The meme picker (conversation.input.overlay, id meme-picker) is
     absolutely positioned left:0 inside the composer's overlay anchor with
     width:min(360px,90vw). That 90vw resolves against the VIEWPORT, not the
     anchor, and with the picker's own padding+border the border-box
     (377px on a 390px phone) exceeds the 356px anchor — the card's right
     edge then runs past the anchor and off the right screen edge, while the
     left edge keeps the anchor's 17px safe inset. Stretch the card to the
     anchor on both sides (left/right 0, width auto, border-box) so the
     right gap mirrors the left; cap at the card's original border-box size
     (360px content + 24px padding + 2px border) so tablets keep the
     intended card width instead of stretching. Desktop is untouched: the
     frame marker only exists below 1024px. */
  [data-mobile-nav="frame"] .meme-picker {
    left: 0 !important;
    right: 0 !important;
    width: auto !important;
    box-sizing: border-box !important;
    max-width: 386px !important;
  }

  /* dsh-meme 网格缩略图：自适应铺满卡片,保留 8px 间隙。
     dsh-meme 的 .mp-grid 是 flex-wrap + 固定 76px 的 .mp-cell(行内 style 再压到 74px):
     3 列(390px 手机)时每行右侧剩 ~78px 空白,卡片没有铺满。换成响应式 grid:
     repeat(auto-fill, minmax(64px,1fr)) 让列数随可用宽度伸缩、卡片 width:100% +
     aspect-ratio:1 随轨道自适应(方形,cover 裁切不变),gap 仍是 dsh-meme 的 8px。
     行内 width/height 用 !important 覆盖;手机端约 4 列、平板端约 5 列,均满宽。 */
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
  /* dsh-meme 网格右侧滚动条：默认 WebKit 滚动条在手机上看太粗,压成 4px
     细条——保留滚动指示又不占横向空间,thumb 圆角浅色、轨道透明。 */
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

  /* ---------- agent preset 模式选择菜单：手机端紧凑底部弹层 ----------
     The official agent-preset menu (role=menu, portal mounted on body) uses
     position:fixed + max-height:820px + bottom:12px, so on a phone it
     stretches from the trigger down to 12px above the screen bottom —
     effectively filling the screen. Turn it into a polished bottom sheet:
     cap the height, center it horizontally (the official max-width 360px
     left-anchors at left:12px, leaving 12/18px asymmetric gaps), add a
     drag-handle affordance, breathing room, and softer top radius; the
     inner viewport keeps scrolling. Scoped to the agent-preset item class
     (cubgiG_*) so other role=menu dropdowns (model/access mode) are
     untouched. Desktop ≥1024px is outside the media query, so it keeps the
     official large dropdown. */
  /* agent-preset 菜单依赖 @deepseek-ai/dsh-client-ui-agent-preset 的 CSS Module 哈希 (cubgiG_*)，升级该包时需验证此选择器是否仍有效 */
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
  /* 菜单内部滚动条：默认 WebKit 滚动条在竖屏太粗,会占 ~15px 宽度把文字描述
     挤窄,导致描述换行/截断不自然。压成 4px 细条(与表情网格一致),文字区域
     恢复自适应宽度。 */
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

/* 搜索框底部间距修复 */
  [aria-modal="true"] [class*="tabSearchRow"] {
  padding: 2px 4px 16px !important;
  }


  /* ===== 已安装列表：路径单行截断 ===== */
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
  /* ===== 已安装列表：手机端纵向重排 ===== */
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
  /* ===== 市场卡片图片容器：横向滚动 ===== */
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

  /* ---------- dsh-file-viewer (conversation.view tab「文件查看器」) ----------
     The plugin ships NO responsive CSS: its min-width:0 flex panels overflow
     on a phone — the titlebar caps the path at 520px beside a 5-button action
     row, and CSV/code headers row-stick inside content scrollers. It renders
     inline into the conversation view region (stable 'dsfv-*' prefix, injected
     <style>), not a modal sheet, so the fixes here are: stop the PANEL from
     scrolling horizontally (leave horizontal scrolling inside the content
     scrollers), compress the titlebar/statusbar, enlarge touch targets, and
     scope everything under [data-file-viewer-open] so only the active
     file-viewer tab is affected. The marker is owned by the
     file-viewer-open-marker reconciler task; nothing leaks to desktop because
     this whole block lives inside the mobile media query.
     (Port of community fork fix 2ff7976.) */

  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-panel {
    min-width: 0 !important;
    max-width: 100% !important;
    overflow-x: hidden !important;
  }
  /* Titlebar: single compact row; path truncates, secondary meta hides on
     narrow, the 5-button action row wraps to two rows of tall targets. */
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
  /* Status bar: wrap, safe-area bottom padding, compact. */
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-statusbar {
    flex-wrap: wrap !important;
    gap: 4px 10px !important;
    padding: 4px 8px calc(4px + env(safe-area-inset-bottom, 0px)) !important;
    font-size: 12px !important;
  }
  /* Content scrollers must own horizontal scrolling; the flex columns and the
     renderer stack must not let content push the panel wide. */
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
  /* Browser / subtoolbar rows wrap; file rows get touch-friendly height. */
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
  /* Produced-file chips render in the conversation tail, outside the viewer
     tab — keep tappable but not scoped to the marker. */
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

  /* ---------- File share (effects/file-share.ts) ----------
     Files tree rows: the plugin appends one share button after the host's
     own row button inside li[data-files-entry="file"] (host nodes are never
     moved). The li becomes a flex row only while it carries that button, the
     host button keeps the remaining width. Preview header: one icon button in
     the official document.actions slot, sized like the host tool buttons. */
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
  /* Fallback notice, only on hosts without the primitives Toast. */
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

  /* ---------- Markdown 表格：手机档（≤767px）不再被压成「一字一行」 ----------
     2026-10-06 真机报障：6 列宽表在 390px 上被 table-layout:auto 压到每列
     1~2 字宽，表头竖排成一列单字，完全不可读。宿主 chat 包（ui-chat）只给
     表格外面套了 .md-table-wide —— 用容器查询单位做「出血加宽」
     （--dsh-table-lead / --dsh-table-spare），**整包没有任何 th/td/table
     规则**（已 grep 确认，唯一命中在 settings-subagent 的 depthTable，与本页
     无关），所以列宽完全由内容最小宽度决定，没有下限也没有滚动容器。
     这里补齐手机档的两件事：给单元格一个最小宽度下限 + 让包裹层横向滚动。
     th 允许 nowrap（表头本来就短、竖排最难看），td 只设下限、长句仍可换行，
     不会把表格撑到无法阅读的宽度。
     平板（768–1023）与桌面不动 —— 店主明确「平板不用」（对齐上游 768px
     分档边界，与 layout.css.ts 的手机档写法一致）。 */
  /* @patch
     id: ofm-mobile-cost-ceiling
     for: dsh-our-free-model 2.0.0 的 ofm_ 私有类名（毛玻璃表面 + 极光层 + 装饰动画）
     why: 上游把设置页做成一张超长 backdrop-filter 大面 + 常驻全屏极光，插件侧没有手机档成本降级，本插件只能用自己的手机档样式兜
     until: 上游自己给手机档出成本降级（或拿掉极光层与 blur 表面）——届时本块整节删除
     test: tests/our-free-model-cost.test.ts
  */
  /* ---------- dsh-our-free-model: mobile cost ceiling ----------
     该插件设置页在手机上卡顿掉帧。实测数字、驱动限制与验证方法见
     docs/maintenance/pitfalls.md「第三方成本上限」——这里只记为什么这么写。

     选择器用裸的 ofm_ 前缀类名，不加任何祖先锚。设置页经 createPortal 挂到
     document.body，[data-mobile-nav="frame"] 后代选择器一个都不命中；而
     .ofm_scrim 与 .ofm_toast 也 portal 到 body，锚 .ofm_root 同样漏。前缀是
     本插件独占（宿主与全仓 0 命中），锚根买不到隔离，只赔掉 portal 出去的那几块。

     只砍装饰，且按「遮罩 vs 表面」分两组——同一个「去 blur」动作，两类需要相反
     的补法：表面按半透明设计，只摘 filter 会让页面从下面透出来，故补不透明底；
     .ofm_scrim 是蒙层，补底等于把整页涂黑，故只摘 filter。

     带状态的动画一律保留（loading 骨架、池子 busy/over 点、状态点、进度条、
     aria-busy 按钮）。.ofm_scanline 看着像装饰，实则挂在登录等待提示上，上游
     自己的 prefers-reduced-motion 块也不杀它。

     本块在顶层手机档（≤1023px + pointer: coarse），触屏平板也吃到——有意为之：
     这是成本修复不是布局修复，与屏宽无关。 */
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

  /* @patch
     id: ofm-toast-above-drawer
     for: dsh-our-free-model 2.0.0 的 .ofm_toasts（公告 toast，portal 到 document.body）
     why: 该插件把 toast 钉在 z-index 1200 并自称浮在所有层之上，但本插件移动抽屉是 1300、遮罩 1250、宿主原生侧栏 1100 —— 打开抽屉时整条 toast 既不可见也不可点，插件侧没有可调的层级接口
     until: 上游把 toast 抬到宿主弹层之上（1400+）或宿主提供官方通知层 —— 届时本块删除
     test: tests/our-free-model-toast-layer.test.ts
  */
  /* ---------- dsh-our-free-model: announcement toast above the drawer ----------
     2026-10-17 店主报障：打开侧边栏时左下角的官方推送消息被抽屉挡住。
     实测（390×844 触屏仿真）：toast .ofm_toasts rect(18,738,354×88) z1200；
     本插件 backdrop z1250、抽屉列 z1300 ⇒ elementFromPoint(195,780) 命中抽屉
     里的 Usage/Balance 按钮、(330,780) 命中遮罩 —— 整条与点击一起没了。

     选择器用 body 直子锚：插件 toastHost() 把 .ofm_toasts 挂在 document.body
     （不存在才建），.ofm_ 前缀又是本插件独占（宿主与全仓 0 命中，见上一节），
     所以这层锚既精确又不会误伤。

     1310 的取值：高于抽屉 1300（否则被盖），低于本插件自己的弹层 1400 与
     file-share toast 1500（公告 toast 不该压过模态）。桌面档没有抽屉，问题
     只在手机档出现，规则因此留在本顶层手机媒体查询内。

     代价（店主 2026-10-17 选定 A 方案时知情）：toast 通栏 354px 比抽屉 280px
     宽，浮起后会压住抽屉底部约 88px（用户行/设置按钮区）约 10 秒（插件
     holdMs 默认 10000），期间那几颗按钮点不到 —— 这是「推送可见」与「抽屉
     底部可点」的二选一，本次选了前者。 */
  body > .ofm_toasts {
    z-index: 1310 !important;
  }
}

/* ---------- session inspector: the disclosure triangles are too small ----------
   2026-10-20 店主报障：手机端「会话数据诊断」面板（宿主实验插件
   dsh-experimental-session-inspector）里那些深色小三角太小、点不准。

   三处 disclosure 不是一个东西，得分开打：
   A 表格「类型」列里的行展开箭头 —— 16px 宽、11px 的 ▸/▾ 文本按钮，
     组件自己只给了 width:16px，没有 height/line-height，实际可点面 ≈16×13px，
     而它紧贴「选中该行」的类型文本按钮（同一 td 内相邻），手机上很容易点错。
   B 「原始数据」JSON 树每层 —— 原生 details/summary 的 UA marker，
     宿主只设了 cursor:pointer，没有任何尺寸声明，随 root 的 11px 字号一起小。
   C 面板顶部「原始数据 ▾」—— 原生 select 的 UA 箭头，同样 11px。
   行高 30px 是宿主虚拟化的常量（client.js 里 size/estimateSize 与 sticky 偏移
   多处写死 30，且没有 measureElement），所以只放大控件本身，绝不改行高。 */
@media (max-width: 1023px) and (pointer: coarse) {
  /* @patch
     id: session-inspector-disclosure-size
     for: @deepseek-ai/dsh-experimental-session-inspector 0.2.1-rc.2（CSS 模块哈希前缀 GTtnTq）
     why: 树行展开箭头只有 16×≈13px、字号 11px，低于触控下限，且与相邻的「选中该行」按钮动作不同
     until: 宿主把 GTtnTq_disclosure 提到 ≥24px，或换成可缩放图标并自带内边距
     test: tests/css-rules.test.ts
  */
  /* A：行箭头。22px 正好等于行内容盒（30px 行高 − 上下 4px 内边距），
     不会撑高行、也不会打乱虚拟化的行位置模型。 */
  [class*="GTtnTq_root"] [class*="_disclosure"][aria-expanded] {
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    width: 22px !important;
    height: 22px !important;
    font-size: 15px !important;
    line-height: 1 !important;
  }
  /* B：JSON 树的原生 marker。字段名的缩进槽是 16px（ul 的 padding-inline-start），
     15px 字形正好卡进槽里，放大不会把字段名右移。 */
  [class*="GTtnTq_objectTree"] details > summary::marker {
    font-size: 15px !important;
  }
  /* C：顶部模式选择器（原生 select）。不在虚拟化区域里，可以直接长高。 */
  [class*="GTtnTq_modeSelector"] {
    font-size: 13px !important;
    padding: 4px 6px !important;
    min-height: 32px !important;
  }
  /* D：吸顶祖先行（顶部那一群灰色行）在快速滑动时错乱/拖影。
     宿主给它们是 position:sticky + 灰底，而整张表是 border-collapse:collapse ——
     Chromium 里「sticky 的 <tr> + 合并边框」会逐帧重算行盒，甩动时就撕开。
     两刀都只动呈现、不动布局：①把合并边框换成 separate + 0 间距（每行只有一条
     border-bottom，视觉与合并边框一致，但行盒不再是合并边框模型）；②给 sticky
     行独立合成层，吸顶位移整块平移，不再每帧重绘整行。
     计时器/虚拟区间仍归宿主，这里不碰 scrollTop 与 overflow-anchor。 */
  /* @patch
     id: session-inspector-sticky-flicker
     for: @deepseek-ai/dsh-experimental-session-inspector 0.2.1-rc.2（GTtnTq_table / GTtnTq_sticky）
     why: sticky 的 <tr> 叠在 border-collapse:collapse 表格上，快速滑动时吸顶祖先行被逐帧重算行盒，出现撕裂/拖影
     until: 宿主把表格改成 border-collapse:separate，或不再用 sticky <tr> 表达祖先上下文
     test: tests/css-rules.test.ts
  */
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
  /* ---------- hero composer on mobile ----------
     The official hero card carries a 2-line textarea plus a tall tool row,
     which reads oversized on a phone. Tighten the empty-state rhythm: keep
     the official centered hero, shrink the textarea line box, slim the card
     padding and the tool row, and close the gap under the headline. */

  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]) {
    gap: 8px !important;
  }
  /* Composer stacks carrying the git branch chip must keep compat.css's 44px
     chip clearance: that rule sets padding-top: 44px on the composer card of
     any stack that contains the absolutely-positioned chip anchor (A′, #105:
     the chip stays in the dock subtree and is re-anchored to the stack, so
     the exclusion moved from the card level to this stack-level :not(:has())
     — a card-level :has() could never match anymore). Chip geometry: top
     corner +12 + 28px chip — the chip grew 24→28px, so the clearance grew
     40→44px to keep the same ~4px breathing gap (2026-09-06). This compact
     override used to stomp the clearance back to 6px with the same
     specificity (this sheet loads after compat), so on the hero empty state
     the chip painted over the input line (2026-09-06). Excluding
     chip-bearing stacks restores the clearance; the textarea collapse below
     still applies to them. */
  [data-phase="hero"] [class*="_composerStack"]:not(:has([data-gitgraph-chip-anchor])) [class*="_card"]:has(textarea, [data-composer-input]) {
    padding-top: 6px !important;
  }
  /* The official composer autosizes the textarea and writes an inline
     height (2 lines on the hero empty state) on the textarea's scroll/grow
     wrappers. :placeholder-shown lets us collapse the EMPTY state to one
     line with !important; as soon as the user types, the pseudo-class no
     longer matches and the autosizer's inline height takes over again — so
     multi-line growth keeps working. */
  [data-phase="hero"] textarea:placeholder-shown {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) > [class*="_scroll"],
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) [class*="_grow"] {
    height: 28px !important;
  }
  /* The one-line collapse above is deliberately NOT mirrored onto the Lexical
     generation (0.1.2+), even though it signals its empty state with the
     separate [data-composer-placeholder] node. That host pins the hero input
     itself — hero-scoped min-height: 52px, because its hero hint wraps to two
     lines — and a min-height floor beats an outer height: the 28px wrappers of
     2026-09-05 only shrank the scrollport under a 52px input, i.e. overflow-y
     auto with scrollHeight 52 against clientHeight 28 → scrollbar plus a
     clipped first input line and hint line (phone report 2026-09-14, probe
     scripts/probes/hero-composer-clip-probe.mjs). The textarea generation this
     collapse was written for has no such floor — its input is a transparent
     height:100% layer over the wrappers — so it still collapses there. */
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

  /* ---------- composer dock: swap git branch chip with the todo card ----------
     The git-graph branch chip (conversation.input.dock, order 100) floats
     alone at the bottom-left above the input card, with a dead zone to its
     right; the full-width todo card (order 0) sits above it. Swap them so
     the chip reads as the stack's top row and the todo card fills the row
     above the composer. The dock container itself is display:contents
     (inline style) — its children are direct flex items of the composer
     stack, so order on the children is what reorders them. Only the chip
     needs an order change: -1 puts it before the todo card (order 0) and
     before the input card (order 0, later in DOM). The todo card must KEEP
     its order 0 — raising it past the input card's order 0 would drop it
     below the composer entirely (2026-08-16 regression, fixed). The queue
     strip (order 20) keeps hugging the input card. Desktop untouched (this
     block lives inside the max-width: 1023px media query). */
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] {
    order: -1 !important;
  }
  /* Mobile tap target + feedback for the branch chip (git-graph, 24px
     desktop spec). Two real-world problems: ① the chip is tiny and sits
     right above the expandable todo card — mis-taps land on the todo card;
     ② opening the popover waits for the host's /git/branches round-trip
     (~700ms on device) with zero feedback, so users tap again and toggle
     the popover closed. Enlarge the target, kill double-tap zoom delay,
     and give an instant pressed state so a tap reads as registered. */
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

  /* ---------- mattskills status capsule: keep its tail (skills button / fold
      chevron) inside the pill on portrait ----------
      dsh-mattpocock-skills-deck renders a capsule status bar in
      conversation.input.dock (order 40, the row above the composer card). Its
      own sheet deliberately never shrinks icons or counters ("图标与普通计数
      永不收缩"), so as soon as the capsule's content is wider than the pill the
      host wrapper's overflow:hidden cuts the right edge and the two segments
      that sit last — the skills-list button (2×2 grid) and the fold chevron
      that collapses the whole deck strip — are not painted at all.
      Measured 2026-10-10 on a 393px portrait viewport, session bound to a
      ticket with two-digit counters (可接 21 / 诊断 20): capsule content
      ≈ 415px against a ~360px pill, i.e. ~55px short — exactly those two
      segments; the same session on landscape shows both, so this is width
      pressure, not a missing feature (and not a deck version difference:
      1.7.51 / 1.8.0-rc.1 / 1.8.0-rc.2 all render both unconditionally).
      Reclaim the width from the capsule's desktop-sized spacing (6px column
      gap plus 7px padding on each of ~11 children) rather than hiding any
      segment, and keep a horizontal scroll as the fallback for the narrowest
      phones so the tail can still be swiped into view. The scrollbar stays
      hidden, so the pill still reads as one pill. Desktop untouched: this
      block lives inside the max-width: 1023px media query. */
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

  /* ---------- ask question composer (ask_user_question): kill iOS Safari
      input-focus auto-zoom ----------
      Safari on iPhone enlarges the whole viewport when a focused <input> /
      <textarea> computes font-size < 16px, and only reverts on blur. The ask
      dialog is a modal composer takeover, so taps outside never blur the
      field and the magnification persists until the field loses focus
      (e.g. the dialog is dismissed). The ask
      composer's custom-answer <input> (.customInput) and optionless free-form
      <textarea> (.customTextarea) both ship at 14px (ui-user-questions
      QuestionComposer.module.css). Raise them to 16px on iOS only, where the
      zoom can actually happen: on Android and desktop there is nothing to
      suppress, so they keep the compact size they were designed with
      (2026-09-16, audit D-1 option A; the iOS WebKit floor below covers these
      fields too, this rule keeps the requirement stated where it applies).
      Scoped to the ask
      composer's stable [data-question-key] root (AGENTS.md: scope hashed-class
      selectors to the owning region, prefer stable data-* markers); the
      class-name suffix match follows the plugin's established harness
      CSS-module convention (verified against the live app: generated names
      end with the original local name, e.g. uV2eYG_input / bhn1Oq_searchInput). */
  html[data-mobile-nav-ios] [data-question-key] [class*="_customInput"],
  html[data-mobile-nav-ios] [data-question-key] [class*="_customTextarea"] {
    font-size: 16px !important;
  }

  /* ---------- dsh-file-viewer inputs: kill iOS Safari auto-zoom ----------
     Same rule as the ask composer above: the file viewer's search / jump-to-
     line / pdf-page fields ship at 13-14px, which Safari auto-magnifies on
     focus inside a panel that does not blur on tap-away. Raise them to 16px
     on iOS only for the same reason as the ask composer above (2026-09-16,
     audit D-1 option A). Scoped to the frame marker; the
     viewer itself is scoped by its stable dsfv prefix.
     (Port of community fork fix 2ff7976.) */
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-search-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-jump-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-page-input"] {
    font-size: 16px !important;
  }

  /* ---------- iOS WebKit: hold every text field at >=16px so Safari never
      focus-zooms the viewport (#45) ----------
      Report (iPhone 15 Pro Max): the page magnifies as soon as a field takes
      focus, sometimes also when switching sessions (the host composer mounts
      with autoFocus), and it stays magnified until the app is closed and
      reopened or rotated landscape->portrait.
      Mechanism: iOS Safari enlarges the visual viewport whenever a focused
      input / textarea computes below 16px, and it only zooms back out on
      blur — a chat shell keeps the composer focused, so the zoom has no
      moment to revert; before this fix the root touch-action also withheld
      pinch-zoom, so the user could not pull it back out either (see
      layout.css.ts). maximum-scale=1 in the viewport meta is NOT the fix:
      iOS 10+ ignores it for user pinch zoom while other engines honor it, so
      writing it would only take zoom away from Android. Raising the fields is
      the fix that stays inside the standard.
      Gated on html[data-mobile-nav-ios] (phone-chrome.ts detectIosWebKit)
      because only WebKit on iOS zooms on focus: Android and desktop keep the
      compact 13px search boxes they were designed with. The floor covers
      every text-entry field on the page, including the ones portalled
      outside the frame (settings dialogs, the market sheet, third-party
      panels) — a phone can reach all of them. Button-like and widget inputs
      are excluded (nothing to type, no keyboard), and select is left alone on
      purpose: it would break the composer's 28px access-mode control, and a
      native picker overlays the screen instead of leaving a zoomed page
      behind. The composer's mirror / backdrop layers ride along with the
      textarea: they measure the autosize height and paint the highlight, so
      all three must share one font-size or the caret drifts off the text
      (they inherit 16px from the host card today — the rule locks that in on
      hosts whose composer ships smaller).
      The contenteditable branch is the forward-looking one: dsh
      0.1.2-rc.1 replaces the composer textarea with a Lexical
      contenteditable whose card reads font-size:
      var(--dsh-content-font-size, 14px), i.e. 14px by default — squarely in
      the zoom-triggering range. Match the attribute rather than the value
      "true" (Lexical writes "true", other hosts use plaintext-only or the
      bare attribute) and exclude contenteditable="false", which Lexical puts
      on decorator nodes inside the editor. */
  html[data-mobile-nav-ios] textarea,
  html[data-mobile-nav-ios] [contenteditable]:not([contenteditable="false"]),
  html[data-mobile-nav-ios] [data-input-mirror],
  html[data-mobile-nav-ios] [data-input-backdrop],
  html[data-mobile-nav-ios] input:not([type="button"]):not([type="checkbox"]):not([type="color"]):not([type="file"]):not([type="hidden"]):not([type="image"]):not([type="radio"]):not([type="range"]):not([type="reset"]):not([type="submit"]) {
    font-size: 16px !important;
  }

  /* ---------- drawer session tree: skip off-screen rendering ----------
     The drawer mounts ~389 nodes at once (the open gesture early-commits
     the host state while the drawer is still off-screen), and during
     streaming every token commit re-lays-out tree rows that are not even
     visible. content-visibility: auto lets the engine skip layout and
     paint of the session tree while it is outside the viewport (the arm
     moment of the open gesture) and of off-screen rows when the drawer is
     open on a long conversation. contain-intrinsic-size keeps the scroll
     geometry stable while rows are skipped. Scoped to the drawer tree via
     the frame marker + first child so the explorer sheet tree (a different
     subtree) is not affected. Measured with CDP Tracing on an empty
     conversation at 1x CPU (2026-08-29): biggest script task 104 -> 66ms,
     max rAF gap 167 -> 33ms; the benefit scales with conversation length.
     Desktop untouched (this block lives inside the max-width: 1023px
     media query). */
  [data-mobile-nav="frame"] > :first-child [role="tree"] {
    content-visibility: auto;
    contain-intrinsic-size: auto 600px;
  }

  /* ---------- text selection stays inside content (B1, 2026-10-04) ----------
     Report (Android): long-press a word in an AI reply, drag the selection
     handle up over the session header, and the selection jumps to "select
     all" instead of extending through the message list. Mechanism: the
     header and the composer card are ordinary selectable boxes; the header
     sits BEFORE the scrollport in DOM order (header, then
     [data-conversation-scroll]) and the drawer earlier still (frame first
     child, parked off-screen at -110%). A selection extent whose hit point
     resolves into that chrome lands IN the chrome, so the highlighted range
     covers the header and app shell on top of the messages - on a phone it
     reads as the whole page. With the chrome unselectable the extent snaps
     to the nearest message text instead (headless drag-select 390x844
     touch-emulated, message -> header: focus node went from the header
     title span to a message <p>; message -> composer row: from the Send
     button to a message <p>; the scrollport kept auto-scrolling in both).
     Layout is untouched: this only sets user-select.
     Kept selectable: the message flow (outside every rule below) and the
     composer editing surface, which is re-enabled explicitly - auto would
     inherit none from the card, and an unselectable contenteditable cannot
     host a caret or a paste on WebKit. Dialogs and text fields portalled
     into the drawer DOM (settings sheet) are re-enabled the same way, so
     their values stay copyable. Scope: the conversation header is the host
     header inside the [data-phase] root, anchored to the HOST HEADER CLASS
     (header.wSkVaW_header) rather than to a bare header - a bare header also
     hits every other <header> under [data-phase], and the ask-question card
     (dsh-client-ui-user-questions QuestionComposer) renders its own
     <header class="Mbwy4a_header"> in composerSeat with the question text
     as the heading. A bare header made that text unselectable, so the
     question could not be long-press-copied (2026-10-20, same anchor rule
     as the layout.css.ts header block; the dock above the input card is
     reply content and must stay copyable). The anchor stays the DESCENDANT
     form on purpose: 0.2.0-rc.2 (live-host probe 2026-10-07) renders an
     unclassed wrapper <div> between .wSkVaW_root[data-phase] and
     <header class="wSkVaW_header">, so the child-combinator form this rule
     used to carry matched 0 elements - the title bar kept user-select:auto
     and a drag-select up over it still ended in the header title span (4391
     selected chars, opening with the title / mode / tab labels). On the
     composer side only the input card ([data-composer-card], host marker
     since 0.1.2) and the plugin stats row. */
  [data-mobile-nav="frame"] > :first-child,
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header,
  [data-mobile-nav="frame"] [data-composer-card],
  [data-mobile-nav="stats"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="backdrop"] {
    -webkit-user-select: none !important;
    user-select: none !important;
  }
  [data-mobile-nav="frame"] [data-composer-input],
  [data-mobile-nav="frame"] [data-composer-card] textarea,
  [data-mobile-nav="frame"] > :first-child [role="dialog"],
  [data-mobile-nav="frame"] > :first-child input,
  [data-mobile-nav="frame"] > :first-child textarea,
  [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header input {
    -webkit-user-select: text !important;
    user-select: text !important;
  }
}

/* ---------- tablet / wide mobile: keep sheets from becoming full-width ----------
   Below 768px the near-full-width sheets are the right call for a phone.
   On wider but still sub-desktop viewports (foldables, tablet portrait,
   desktop-mode tall windows) the same full-bleed sheet leaves content
   clustered at the left edge with a large dead zone on the right. Cap and
   center the modal sheets and the aionui bottom sheets instead. */
@media (min-width: 768px) and (max-width: 1023px) and (pointer: coarse) {
  /* Centered, never edge-to-edge — for the modal shapes below, not for every
     modal dialog. Covered: modals that are not sheet-shaped, plus sheet-shaped
     ones with neither a navigation element nor a directory picker. A modal
     that is sheet-shaped AND carries the directory picker is left out on
     purpose — layout.css.ts holds the dedicated rule for it. The settings
     sheet has a higher-specificity full-width rule above, so repeat its
     selector here to win; the generic export/other-modal rule is covered by
     the second selector. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])),
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    left: 0 !important;
    right: 0 !important;
    margin-left: auto !important;
    margin-right: auto !important;
    width: min(calc(100vw - 32px), 720px) !important;
    max-width: min(calc(100vw - 32px), 720px) !important;
  }

  /* The dsh-web-ui explorer / preview bottom sheets: same treatment — keep
     the mobile bottom-sheet behavior, but stop them spanning the full width. */
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    left: 0 !important;
    right: 0 !important;
    width: min(calc(100vw - 32px), 720px) !important;
    margin-left: auto !important;
    margin-right: auto !important;
  }

  /* Settings sections (e.g. Agent presets) often carry a desktop max-width
     (720px) that leaves a dead strip on the right once the sheet is capped to
     the same width; let them fill the sheet body instead. */
  [aria-modal="true"] [class*="_section"] {
    width: 100% !important;
    max-width: none !important;
  }
}

/* ---------- desktop / non-touch: the mobile controls must never appear ----------
   Exact complement of the mobile query "(max-width: 1023px) and (pointer:
   coarse)" as a comma list (NOT A or NOT B): any viewport ≥1024px, plus any
   narrow viewport whose primary pointer is a mouse (fine) or absent (none).
   The pointer terms are what keep the header Files button off narrow desktop
   windows — the slot renders the buttons at every width, so before this the
   only guard was the width term (2026-08-30 PC leak: split windows and OS
   display scaling dropped the CSS viewport below 1024px and armed the whole
   mobile shell on desktop).

   The session-delete trio (menu item + confirm/error dialog) is the ONE
   deliberate exception: its effect arms on TOUCH_QUERY (pointer: coarse at
   every width — large tablets in landscape), so it lives in the pointer-only
   block below instead of this width arm. */

@media (min-width: 1024px), (pointer: fine), (pointer: none) {
  [data-mobile-nav="toggle"],
  [data-mobile-nav="files"],
  [data-mobile-nav="file-upload"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="backdrop"],
  [data-mobile-nav="session-log"],
  [data-mobile-nav="session-log-wrap"],
  [data-mobile-nav="preview-full-toggle"] {
    display: none !important;
  }
}

/* Session-delete trio: hide on mouse-driven or pointer-less windows at ANY
   width. No width term — the injection is armed on touch at every width, so
   a width arm here would hide the item on wide touch (the device class the
   injection exists for). */
@media (pointer: fine), (pointer: none) {
  [data-mobile-nav="session-delete"],
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="delete-dialog"] {
    display: none !important;
  }
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
exports.TOUCH_QUERY = exports.DESKTOP_QUERY = exports.MOBILE_QUERY = void 0;
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
// 拆出的形状沿用本仓既有的 task 工厂模式（preview-fullscreen / overlay-backdrop-fab /
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
    'close': '收起目录',
    'backdrop': '点击关闭目录',
    'back': '返回',
    'backToConversation': '返回会话',
    'sessionLog': '导出会话日志',
    'files': '文件浏览',
    'fileUpload': '添加文件',
    'fileUploadImage': '上传图片',
    'fileUploadAttachment': '上传附件',
    'previewFullscreen': '全屏预览',
    'previewExitFullscreen': '退出全屏',
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
    'close': 'Close directory',
    'backdrop': 'Click to close directory',
    'back': 'Back',
    'backToConversation': 'Back to conversation',
    'sessionLog': 'Session log',
    'files': 'Files',
    'fileUpload': 'Add files',
    'fileUploadImage': 'Upload image',
    'fileUploadAttachment': 'Upload attachment',
    'previewFullscreen': 'Fullscreen preview',
    'previewExitFullscreen': 'Exit fullscreen',
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
__modules["effects/stats-line.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.pickTpsReadout = void 0;
exports.statsAnchorAlive = statsAnchorAlive;
exports.resolveTpsForKey = resolveTpsForKey;
exports.tpsSlotsSettled = tpsSlotsSettled;
exports.createStatsLineTask = createStatsLineTask;
const reconciler_core_ts_1 = require("./core/reconciler-core.js");
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
    const isRingCandidate = (child, stats) => !child.contains(stats) && /\d\s*%/.test(child.textContent ?? '');
    // 环坐在统计条**所在的同一行**（dock）里，但中间隔着几层包装并不固定：0.1.7–0.2.0 的
    // 统计条是 bOPqQW_root，外面还套着 slot 包装层（固定两级上溯刚好落到 dock）；0.2.1 把
    // 统计条拆成两个 slot 条目、没有共同 _root，标记落在 slot 包装本身，固定两级就越过 dock
    // 指到 composer 根，环永远找不到（实测 0.2.1-rc.1：dock 里 ring 明明在，measureRing 返回 null）。
    // 所以按「谁的 children 里有环候选」向上找，包装层几层都不影响。
    // 必须封顶在 composerStack：不封顶会一路扫到 body，而会话正文里带「50%」的块正好是
    // isRingCandidate 的形状（不含 stats、文本有百分比），宿主结构再变一次就会把消息区
    // 当成环、把 composer 根当成 dock 去叠绝对定位。
    const locateRing = (stats) => {
        const stack = stats.closest('[class*="_composerStack"]');
        for (let node = stats.parentElement; node !== null; node = node.parentElement) {
            const ring = [...node.children].find((child) => isRingCandidate(child, stats));
            if (ring !== undefined)
                return { dock: node, ring };
            if (node === stack)
                break;
        }
        return null;
    };
    const measureRing = (stats) => {
        const located = locateRing(stats);
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
        const located = locateRing(stats);
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
__modules["effects/overlay-backdrop-fab.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.fadeOverlayOut = fadeOverlayOut;
exports.createOverlayTask = createOverlayTask;
const frame_ts_1 = require("./platform/frame.js");
/** Fade the CURRENT backdrop out (pointer-events off + opacity 0). Called by
 * the gesture layer when a close commit starts animating, so the dimming
 * fades WITH the drawer's slide-out instead of vanishing after it. The
 * element itself is removed later by the task's normal remove path (the
 * marker flip schedules it). */
function fadeOverlayOut() {
    fadeHook?.();
}
let fadeHook = null;
/** Removal is deferred by one fade so the dimming eases out instead of
 * snapping (user request 2026-08-29 「背景黑色遮罩进行渐变动画」; the fade-IN
 * already existed as a mount animation). */
const BACKDROP_FADE_MS = 200;
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
function createOverlayTask(t, toggleSidebar, panelExit) {
    let backdrop = null;
    let fab = null;
    let backdropRemoveTimer = null;
    /** True while the backdrop carries our inline faded state. Guards the
     * restore branch: while a late close commit is animating, the marker is
     * STILL open, so a plain open-branch restore would undo the pre-fade. */
    let faded = false;
    const drawerOpen = () => {
        const frame = (0, frame_ts_1.getFrame)();
        if (frame === null)
            return false;
        // The user's call (2026-09-13): our drawer is the one users get, even on
        // hosts that ship their own overlay drawer. The host's version measures
        // 321px wide with z-index:1100 and, notably, NO full-screen backdrop at all
        // (measured: the conversation stays hit-testable beside it), which is the
        // behaviour the phone owner rejected as unusable. So the legacy column
        // rules stay armed and this backdrop keeps being created.
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
    return {
        name: 'overlay-backdrop-fab',
        scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
        ensure: () => {
            // Re-armed on every ensure, not once in the factory: core.deactivate()
            // runs dispose() on every MOBILE_QUERY flip (reconciler-core.ts), and
            // reactivation only re-runs ensure() - a hook assigned in the factory
            // body stayed null from the first breakpoint flip onwards, silently
            // killing the backdrop fade.
            fadeHook = () => {
                if (backdrop === null)
                    return;
                faded = true;
                backdrop.style.pointerEvents = 'none';
                backdrop.style.opacity = '0';
            };
            const frame = (0, frame_ts_1.getFrame)();
            if (frame === null)
                return;
            if (drawerOpen()) {
                if (backdrop === null) {
                    backdrop = document.createElement('div');
                    backdrop.dataset.mobileNav = 'backdrop';
                    backdrop.setAttribute('role', 'button');
                    backdrop.setAttribute('aria-label', t('backdrop'));
                    // No element-level click listener: phone-chrome's capture-phase click
                    // handler owns the backdrop tap. The third-party mobile shim stops
                    // click propagation at the frame for anything outside the drawer, so
                    // a listener down here never sees the tap (2026-09-14).
                    frame.appendChild(backdrop);
                    faded = false;
                }
                else if (faded && backdropRemoveTimer !== null) {
                    // Quick close→reopen inside the fade window: cancel the pending
                    // removal and let the CSS transition ease the dimming back in.
                    window.clearTimeout(backdropRemoveTimer);
                    backdropRemoveTimer = null;
                    faded = false;
                    backdrop.style.removeProperty('pointer-events');
                    backdrop.style.removeProperty('opacity');
                }
            }
            else if (backdrop !== null) {
                backdrop.style.pointerEvents = 'none';
                backdrop.style.opacity = '0';
                faded = true;
                if (backdropRemoveTimer === null) {
                    backdropRemoveTimer = window.setTimeout(() => {
                        backdropRemoveTimer = null;
                        backdrop?.remove();
                        backdrop = null;
                    }, BACKDROP_FADE_MS + 60);
                }
            }
            if (heroPhase() && !drawerOpen() && fab === null) {
                fab = document.createElement('button');
                fab.type = 'button';
                fab.dataset.mobileNav = 'fab';
                fab.setAttribute('aria-label', t('open'));
                fab.title = t('open');
                fab.addEventListener('click', onFabClick);
                frame.appendChild(fab);
            }
            else if ((!heroPhase() || drawerOpen()) && fab !== null) {
                fab.remove();
                fab = null;
            }
            syncFab();
        },
        dispose: () => {
            if (backdropRemoveTimer !== null) {
                window.clearTimeout(backdropRemoveTimer);
                backdropRemoveTimer = null;
            }
            fadeHook = null;
            backdrop?.remove();
            backdrop = null;
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
// Sessions service shape drifted in 0.1.6-alpha.2 (audit doc §10.1): a2
// removed `ISessions.open`/`clear` and `SessionListState.current` /
// `currentAddress`, moving selection to per-session `retainedBy` counters
// (upstream reads it as
// `Object.values(byId).find(s => (s.retainedBy.mainView ?? 0) > 0)?.id`).
// These helpers let call sites stay compile-green against rc.2 typings while
// degrading explicitly on an a2 host instead of throwing or silently dying.
Object.defineProperty(exports, "__esModule", { value: true });
exports.DELETE_VERIFY_INTERVAL_MS = exports.DELETE_VERIFY_ATTEMPTS = void 0;
exports.currentSessionIdOf = currentSessionIdOf;
exports.sessionsCanClear = sessionsCanClear;
exports.sessionsCanOpen = sessionsCanOpen;
exports.verifySessionDeleted = verifySessionDeleted;
/** The current session id: rc.2's `current` field when present, else the a2
 *  main-view-retained session. Undefined when the shape matches neither. */
function currentSessionIdOf(list) {
    if (typeof list !== 'object' || list === null)
        return undefined;
    const snapshot = list;
    if (typeof snapshot.current === 'string')
        return snapshot.current;
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
/** a2 removed `clear()` (selection lifecycle moved to the retain model). */
function sessionsCanClear(sessions) {
    return typeof sessions?.clear === 'function';
}
/** a2 removed `open()`; callers must degrade (armNav fallback in
 *  phone-chrome) instead of throwing inside the capture pointerup listener. */
function sessionsCanOpen(sessions) {
    return typeof sessions?.open === 'function';
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
function createDrawerSessionLogTask(ctx) {
    const t = ctx.locale.bind(locales_ts_1.NS);
    const sessionId = () => (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot());
    let wrapper = null;
    let button = null;
    let unsubscribe = null;
    /** Mirror "no current session" into the native disabled state. */
    const syncDisabled = () => {
        if (button === null)
            return;
        button.disabled = sessionId() === undefined;
    };
    const onClick = () => {
        const id = sessionId();
        if (id === undefined)
            return;
        ctx.sessionLogDownload.download(id);
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
                button.setAttribute('aria-label', t('sessionLog'));
                button.title = t('sessionLog');
                button.innerHTML = DOWNLOAD_SVG;
                button.addEventListener('click', onClick);
                wrapper.appendChild(button);
                unsubscribe = ctx.sessions.list.subscribe(() => { syncDisabled(); });
            }
            if (wrapper !== null)
                mount(row, wrapper);
            syncDisabled();
        },
        dispose: () => {
            unsubscribe?.();
            unsubscribe = null;
            wrapper?.remove();
            wrapper = null;
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
exports.startZonePxFor = startZonePxFor;
exports.classifySwipe = classifySwipe;
exports.classifyFilesSwipe = classifyFilesSwipe;
exports.slidingVelocity = slidingVelocity;
exports.hitTestStart = hitTestStart;
exports.filesZoneHit = filesZoneHit;
exports.openStateStartMode = openStateStartMode;
exports.followTranslate = followTranslate;
exports.followOpenTransform = followOpenTransform;
exports.findHorizontalScroller = findHorizontalScroller;
exports.selectionOwnsStroke = selectionOwnsStroke;
exports.closeDrawerAnimated = closeDrawerAnimated;
exports.installSidebarSwipe = installSidebarSwipe;
const frame_ts_1 = require("./platform/frame.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const overlay_backdrop_fab_ts_1 = require("./effects/overlay-backdrop-fab.js");
/**
 * Sidebar drawer swipe gestures (B 档 hybrid follow, per the 2026-08-29
 * controlled upgrade of docs/specs/2026-08-27-sidebar-swipe-gestures.md).
 *
 * Three gestures:
 * - edge swipe-in: the pointer goes down within the start zone (45% of the
 *   left edge) and the drawer is closed → the host state is flipped AT
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
 * Start-zone width as a FRACTION of the viewport width: the pointer counts
 * as "from the left edge" anywhere inside the left (RTL: right) strip this
 * wide. The zone STAYS at 45% (2026-09-11 user decision): a brief seventh
 * pass shrank it to 0.25 for the draggable-widget conflict and was rolled
 * back the same day — the user keeps the "anywhere in the left half" feel
 * and the conflict is handled by yield signals instead (the
 * data-mobile-nav-dragging cooperation mark + the floating-widget positional
 * heuristic, see dragMarkYields/findFloatingWidget).
 * History of the constant: 24px (hotspot era) → 48px (third pass, fixed
 * "识别成对话内容滚动") → 96px (fourth pass — at that point the zone also
 * finally cleared Chrome Android's EDGE_WIDTH_DP=48dp history-navigation
 * trigger strip, whose strokes the browser claims and pointercancels; the
 * browser gesture itself is suppressed by the root overscroll-behavior-x:
 * none rule in layout.css.ts) → 0.45×viewport (fifth pass; brief 0.25
 * experiment rolled back) — this value.
 * Safety at this width: the release classification (0.16×w travel OR
 * 0.45px/ms velocity) still gates the commit, so widening cannot open on a
 * tap; vertical strokes never reach the touchmove preventDefault, so their
 * native scroll is untouched (see onTouchMove — the cancel is only allowed
 * after the horizontal axis lock); strokes beginning inside genuinely
 * horizontally scrollable containers are excluded from the zone entirely —
 * see findHorizontalScroller (at 45% the stats line / message code blocks
 * sit well inside the strip, so that guard is load-bearing).
 */
const START_ZONE_RATIO = 0.45;
/**
 * The zone in pixels for a given viewport width (pure, exported for the
 * decision-table tests). Rounded so the probe boundary assertions stay
 * integral (390px → Math.round(175.5) = 176).
 */
function startZonePxFor(viewportWidthPx, ratio = START_ZONE_RATIO) {
    return Math.round(viewportWidthPx * ratio);
}
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
 * above LOCK_PX so an 8px horizontal twitch inside the wide start zone does
 * not mount-and-unmount 389 nodes; small enough that the dead zone before
 * the drawer's edge appears is imperceptible.
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
 * Files-panel (host right sidebar) gesture constants — the right-edge mirror
 * of the drawer layer (spec 2026-09-13-files-swipe-gesture-design.md).
 *
 * FILES_ZONE_RATIO mirrors the drawer's START_ZONE_RATIO: a narrow strip
 * cannot be used because Chrome Android's history-nav edge strip (~48dp)
 * pointercancels strokes starting inside it — the same reason the drawer
 * zone grew to 45% of the viewport.
 */
const FILES_ZONE_RATIO = 0.45;
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
        // two families must judge one physical stroke alike. Without the gate the
        // files zone's 45% reaches ~66px into the open drawer column at 390px,
        // where a thumb resting on a row drifts ~8px sideways while scrolling —
        // that closed the drawer AND consumed the tap. The leftward cell beside the
        // drawer is already 'none', so this is what makes the families agree.
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
/**
 * Geometric start-hit test: the pointer went down in the left edge start
 * zone (when the drawer is closed) or inside the drawer content area (when
 * open). Pure and viewport-relative so it is unit-testable; the runtime
 * variant additionally checks the drawer geometry via the DOM.
 */
function hitTestStart(clientX, viewportWidthPx, rtl, t) {
    const edge = rtl ? viewportWidthPx - clientX : clientX;
    return edge >= 0 && edge <= t.startZonePx;
}
/**
 * Geometric start-hit test for the FILES gesture: the pointer went down in
 * the RIGHT edge zone (RTL: LEFT) — the exact mirror of hitTestStart. Pure
 * and viewport-relative.
 */
function filesZoneHit(clientX, viewportWidthPx, rtl, zonePx) {
    const edge = rtl ? clientX : viewportWidthPx - clientX;
    return edge >= 0 && edge <= zonePx;
}
/** Which gesture family owns a stroke that begins while the drawer is OPEN.
 * Inside the drawer body the drawer family always wins (owner's rule
 * 2026-09-17: the drawer's own surface must answer a leftward drag, whether or
 * not the viewport-ratio files zone overlaps it — at 390px that zone starts at
 * x=214, inside the 280px drawer); outside the body the right zone keeps its
 * files routing and its deliberate leftward 'none' verdict (2026-09-13
 * narrowing). */
function openStateStartMode(insideDrawer, inFilesZone) {
    return insideDrawer || !inFilesZone ? 'drawer' : 'files';
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
 * scrolling near the left edge once the start zone grew to 45% of the
 * viewport (the stats
 * line spans the full width; message code blocks are overflow-x:auto too).
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
/** The open drawer element: first child of the plugin frame. */
function findDrawer() {
    const frame = (0, frame_ts_1.getFrame)();
    return frame !== null && frame.firstElementChild instanceof HTMLElement
        ? frame.firstElementChild
        : null;
}
/** True when the drawer is currently open (per the collapsed marker). */
function drawerOpen() {
    const frame = (0, frame_ts_1.getFrame)();
    return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
}
/**
 * True when the host's right sidebar files panel is currently VISIBLE (any
 * form: fullscreen on phones, docked on tablets). Measured on 0.1.5: the
 * panel element is persistent — the closed fullscreen form stays in the DOM
 * at `visibility: hidden` with its rect pushed to x=viewport-width — so
 * presence alone is not the panel-state read. Detect the open state by the
 * three ways the host can hide it (visibility / display / pushed-off rect);
 * a host without this panel keeps returning false and the explorer fallback
 * applies.
 */
function filesPanelOpen() {
    const panel = document.querySelector('[data-sidebar-right-panel]');
    if (panel === null)
        return false;
    const cs = getComputedStyle(panel);
    if (cs.visibility === 'hidden' || cs.display === 'none')
        return false;
    return panel.getBoundingClientRect().left < window.innerWidth;
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
/** Whether a modal dialog owns the screen (gestures must yield to it). */
function modalOpen() {
    return document.querySelector('[aria-modal="true"]') !== null;
}
/** True when a full-screen takeover (taskboard / ssh) owns the frame, or any
 *  host conversation.view overlay (trajectory tab, dsh-file-viewer, or a
 *  future third-party view — they all set the generic
 *  `data-conversation-composer-overlay` attribute on their root) is open.
 *  In both cases the drawer edge-swipe gestures yield so horizontal content
 *  scrolling (kanban columns, trajectory tables, CSV/code panes) wins the
 *  left-edge start zone; the FAB still opens the drawer. This reads the
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
 * The real-world reference is dsh-pet's floating ball (kz2Bea_float,
 * position:fixed, measured 148x160 on the live profile page) — 160 would
 * sit exactly on that widget's edge; 200 leaves headroom for sibling
 * plugin widgets while a full-screen overlay (backdrop, sheets, dialogs)
 * still cannot pass. */
const FLOATING_WIDGET_MAX_PX = 200;
/**
 * Floating-widget positional yield (2026-09-11, 悬浮窗拖动冲突 B 侧): plugins
 * ship draggable floating widgets (desktop-pet / floating-ball / draggable
 * panel shapes) that carry NO standard "draggable" DOM mark, yet the user
 * presses the widget itself — so the stroke's start target sits inside that
 * widget's layer. Draggable widgets almost always live in a SMALL
 * freely-positioned layer (position: fixed | absolute, own box ≤ 160px)
 * hovering above the page, so walk the ancestor chain from the event target:
 * the first small positioned ancestor counts as a floating widget and the
 * stroke yields (no arm, no touchmove preventDefault). Pairs with
 * dragMarkYields (cooperation mark) which needs no shape guessing.
 * Excluded: anything inside our own frame subtree — the FAB / backdrop /
 * drawer content carry their own gesture semantics and must never be
 * misread as floating widgets (the closed-state FAB sits in the start zone).
 * ponytail: no DOM-standard draggable signal exists; shape ≈ draggable is an
 * approximation with a known ceiling — a STATIC small positioned element
 * (e.g. a message badge) also yields, costing a stroke start under a
 * ≤160px dot; a REAL widget that misses (bigger layer, static positioning)
 * upgrades via the data-mobile-nav-dragging mark or by raising the cap.
 */
function findFloatingWidget(target) {
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
        (0, overlay_backdrop_fab_ts_1.fadeOverlayOut)();
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
    (0, overlay_backdrop_fab_ts_1.fadeOverlayOut)();
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
function beginStroke(event, rtl, viewportWidthPx) {
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
    // drag that starts inside the start zone.
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
    // carousel): yield it so its native horizontal pan survives — and so the
    // wide 45%-of-viewport start zone cannot turn a strip scroll into a
    // drawer open (failure scenario C1). Applies to both branches: inside the
    // drawer the same "scroller owns horizontal" semantics should hold.
    if (findHorizontalScroller(chainFrom(event.target)) !== null)
        return false;
    const open = drawerOpen();
    const filesZonePx = startZonePxFor(viewportWidthPx, FILES_ZONE_RATIO);
    if (open) {
        // Close strokes may start ANYWHERE over the frame (2026-08-29 sixth
        // round, user report 「希望打开抽屉之后以外的部分可以进行左滑」). The
        // previous gate required the start point inside the drawer's own
        // geometry and explicitly rejected the backdrop, so the ~28% of the
        // screen beside the drawer swallowed every swipe — combined with the
        // leftward verdict being refused, closing felt impossible. Nothing else
        // owns a horizontal stroke while the drawer is open (the conversation is
        // behind the backdrop), so the whole frame is fair game.
        //
        // 2026-09-13 narrowing (user decision): the RIGHT zone beside the drawer
        // now belongs to the files gesture. Its leftward stroke must NOT close
        // the drawer (the files panel would mount UNDER the drawer — z-1100 —
        // and be invisible); its rightward stroke keeps the animated close via
        // classifyFilesSwipe's 'close' verdict. The left zone / drawer content
        // keeps every pre-existing drawer behavior. Drawer-mode follow painting
        // only ever maps LEFTWARD (followTranslate's close branch), which is
        // exactly the direction the narrowing removes from this zone — no close
        // follow is lost by the routing (probe swipe.close-follow-reaches-slot
        // was relocated into the drawer family accordingly).
        //
        // Tap-to-close on the backdrop is unaffected: a tap never reaches
        // tryLock, so endStroke returns on !wasTracking without writing a
        // consume mark, and the document-capture click handler passes backdrop /
        // FAB clicks through unconditionally anyway.
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
        //
        // 2026-09-17 (owner: the judgment zone must end at the drawer's right
        // edge): inside the drawer BODY the drawer family always wins. The files
        // zone is viewport-relative (0.45 from the right edge = x >= 214 at 390px)
        // and overlaps the 280px drawer by 66px, so that sliver of the drawer's own
        // surface used to answer 'none' to a leftward drag - touching the drawer and
        // dragging left did nothing. Routing on the drawer's live rect (not a
        // hardcoded width) keeps the rule true at every viewport: at >= 509px the
        // 0.45 zone starts right of the drawer and nothing changes. Outside the
        // body the files routing, including its leftward no-op, is untouched.
        const drawer = findDrawer();
        const drawerRect = drawer === null ? null : drawer.getBoundingClientRect();
        const insideDrawer = drawerRect !== null && event.clientX >= drawerRect.left && event.clientX <= drawerRect.right;
        strokeMode = openStateStartMode(insideDrawer, filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx));
    }
    else if (hitTestStart(event.clientX, viewportWidthPx, rtl, { startZonePx: startZonePxFor(viewportWidthPx) })) {
        strokeMode = 'drawer';
    }
    else if (filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx)) {
        strokeMode = 'files';
    }
    else {
        return false;
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
function tryLock(event) {
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
        // stroke's own start target (left-edge start zone / drawer content), never
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
 * OUTSIDE the drawer (the left-edge start zone, or — since closing accepts
 * the whole frame — the backdrop itself) has no drawer in its chain, so the
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
            beginStroke(event, frameRtl(), viewportWidth());
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
                if (tryLock(event)) {
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
        // A click whose target is (or is inside) the backdrop or the FAB is
        // NEVER a gesture's synthetic click: the stroke start is always the
        // left-edge start zone or the drawer content, never the backdrop (outside
        // the drawer, on the right) or the FAB. The mark chain can reach them
        // in degenerate hit-test cases (e.g. a stroke starting on a point where
        // the empty drawer does not register as the event target), and
        // swallowing that click would break the "tap the backdrop to close"
        // path — the "tap twice to close" bug. Let those clicks through.
        const onClick = (event) => {
            if (consumedEl === null)
                return;
            if (!(event.target instanceof Element))
                return;
            // A genuine backdrop / FAB tap is always let through: their own click
            // listeners toggle the drawer, and a consume mark that walked to the
            // document root would otherwise swallow it ("tap twice to close").
            // The one exception is a click on the overlay element that STARTED the
            // just-committed stroke — since close strokes may begin anywhere over
            // the frame, the backdrop can now be the stroke's own start target,
            // and letting its synthetic click through would re-toggle the drawer
            // straight back open.
            const overlay = event.target.closest('[data-mobile-nav="backdrop"], [data-mobile-nav="fab"]');
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
        // preventing here made both 45% start zones dead for scrolling on iOS
        // (#222). Multi-touch is never prevented: two fingers mean a pinch, and
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
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const session_row_fiber_ts_1 = require("./effects/session-row-fiber.js");
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
/** Finger-down to finger-up travel that still counts as a tap on a session row
 *  (#49). Per-axis (`isTapWithinSlop` is max-norm, not Euclidean): the drawer
 *  list scrolls vertically, so a 60px vertical drift must not navigate while a
 *  diagonal wobble still reads as a tap. */
const TAP_NAV_SLOP_PX = 12;
/** Where the current touch started (null for a mouse, and between touches).
 *  The no-click row-tap fallback resolves the row's session id at pointerup and
 *  only when the finger stayed put, so every touch pointerdown records this
 *  BEFORE any early return — a missed record silently disables the whole
 *  fallback. Cleared by the effect's disposer. */
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
 */
function toggleDrawer(ctx) {
    if (!(0, sidebar_swipe_ts_1.closeDrawerAnimated)(ctx))
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
        const drawerRoot = () => document.querySelector('[data-mobile-nav="frame"] > :first-child');
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
            return target.closest(exports.TAP_CLOSE_NAV_SELECTOR) !== null;
        };
        // DSHA_SESSION_INTERACTION_V4：有效的 touch/pen/mouse 单击由宿主打开会话。
        // data-dsha-session-select 标记的行等待真实 dsha-session-open 事件关闭抽屉。
        // 不在 pointerup 提前收起，避免吞掉浏览器随后派发的原始 click。
        // 关闭依据是宿主已经打开会话的事实（见下方 document 监听）。
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
        /** Swallow the host's title double-click rename: repeated touch taps already
         *  use the host's normal session-open path. Letting the browser's `dblclick`
         *  reach the title would also open the rename dialog on that gesture.
         *  Keep the original click handling unchanged. Capture phase on
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
        /** Whether the session list really knows an id. The fiber walk has no
         *  shape heuristic on purpose: hop 32 of a row's chain is a ScopeProvider
         *  whose `props.scope` is the literal 'session-maybe', and
         *  `ctx.sessions.open` fails loud on unknown ids — membership is the only
         *  filter that can never hand the host a guess. */
        const isKnownSessionId = (id) => {
            const snapshot = ctx.sessions.list.getSnapshot();
            return snapshot.byId[id] !== undefined;
        };
        /** The session a finished tap on `row` should open, or null to fall back to
         *  the DOM observer: no finger-down record, a release that travelled (a
         *  scroll or a swipe, not a tap), a fiber chain offering no known id, or a
         *  row that is already the current session. */
        const tappedRowSessionId = (row, event) => {
            if (touchDownAt === null)
                return null;
            if (!(0, session_row_fiber_ts_1.isTapWithinSlop)(touchDownAt, { x: event.clientX, y: event.clientY }, TAP_NAV_SLOP_PX))
                return null;
            const id = (0, session_row_fiber_ts_1.findSessionIdInFiber)((0, session_row_fiber_ts_1.reactFiberOf)(row), isKnownSessionId);
            if (id === null)
                return null;
            return (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) === id ? null : id;
        };
        // Close the drawer once the navigation we started ourselves lands (#49).
        // `armNav` watched the drawer's *selected row* change, but when WebKit drops
        // the tap's click the row's own onClick never runs, so that signal never
        // arrives — the store is the honest source of "navigation happened".
        let closeOnNavUnsub = null;
        let closeOnNavDone = false;
        /** The queued `fire` handle: cleared on disarm so disposal leaves no timer. */
        let closeOnNavTimer = 0;
        /** Disarming means spent: mark the close done before dropping the
         *  subscription, so a `fire` a subscription tick already queued cannot
         *  toggle the drawer after the close was handed to the other closer. */
        const disarmCloseOnNav = () => {
            closeOnNavDone = true;
            closeOnNavUnsub?.();
            closeOnNavUnsub = null;
            if (closeOnNavTimer !== 0) {
                window.clearTimeout(closeOnNavTimer);
                closeOnNavTimer = 0;
            }
        };
        const closeOnNavigation = (id) => {
            disarmCloseOnNav();
            closeOnNavDone = false;
            const fire = () => {
                if (closeOnNavDone)
                    return;
                disarmCloseOnNav();
                if (drawerOpen())
                    toggleSidebar();
            };
            closeOnNavUnsub = ctx.sessions.list.subscribe(() => {
                if ((0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) !== id)
                    return;
                if (closeOnNavTimer !== 0)
                    window.clearTimeout(closeOnNavTimer);
                closeOnNavTimer = window.setTimeout(() => {
                    closeOnNavTimer = 0;
                    fire();
                }, 0);
            });
        };
        /** Record where a touch started. The no-click row-tap fallback in
         *  onDrawerPointerUp resolves the row's session id at the lift and needs the
         *  down point to tell a tap from a scroll or a swipe, so every touch
         *  pointerdown records it BEFORE any early return. */
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
            if (target instanceof Element && target.closest('[data-mobile-nav="backdrop"]') !== null) {
                if (drawerOpen())
                    toggleSidebar();
                return;
            }
            // DSHA_SESSION_INPUT_V4：保留原始点击，实际打开仍由 dsha-session-open 关闭抽屉。
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
                    // Unselected row: navigate from the id we resolved at the touch point
                    // when this tap can supply one — on WebKit the row's own click may
                    // never come, and then nothing else would open the session. Fall back
                    // to closing once the DOM shows a navigation landed when it cannot.
                    const tappedId = tappedRowSessionId(row, event);
                    if (tappedId === null) {
                        // Exactly one closer at a time: this tap closes through the DOM
                        // observer, so drop the store subscription an earlier resolved tap
                        // armed — its stale id would toggle the drawer again on landing.
                        disarmCloseOnNav();
                        armNav();
                    }
                    else {
                        // The mirror case: this tap closes through the store, so drop the
                        // observer an earlier fallback tap armed — otherwise both fire on
                        // this one navigation (the observer on the selected-title change,
                        // the subscription on the landing) and race to toggle twice.
                        disarmNav();
                        if ((0, sessions_compat_ts_1.sessionsCanOpen)(ctx.sessions)) {
                            closeOnNavigation(tappedId);
                            ctx.sessions.open(tappedId);
                        }
                        else {
                            // a2 removed sessions.open (retain-model navigation) — the
                            // store-subscription closer above watches `current`, which a2
                            // no longer publishes, so arming it would only leak a
                            // subscription that can never fire. Degrade to the DOM
                            // observer: the row's own onClick still navigates where the
                            // browser dispatches it (audit doc §10.1 / F1).
                            disarmCloseOnNav();
                            armNav();
                        }
                    }
                }
                return;
            }
            // Non-row nav targets (newSession / taskboard / ssh / search rows that
            // are not treeitems) are closed by the capture click handler below:
            // closing here would retract the drawer before the browser dispatches
            // the tap's click, and the target's onClick would never run.
        };
        // DSHA V4 有效单击打开会话后派发 dsha-session-open；
        // 抽屉只在打开事实到达时关闭，保留原始 click 与行标记豁免。
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
            // Also marks the close spent, so a queued `fire` cannot outlive the effect.
            disarmCloseOnNav();
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
const overlay_backdrop_fab_ts_1 = require("./effects/overlay-backdrop-fab.js");
const file_viewer_compat_ts_1 = require("./effects/file-viewer-compat.js");
const drawer_session_log_ts_1 = require("./effects/drawer-session-log.js");
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
            for (const record of records) {
                // DSHA: message history and streaming text do not change shell geometry.
                // This filter drops body records for EVERY key (text included), which is
                // deliberate: it is why the chat flow cannot wake shell-geometry tasks.
                const target = record.target instanceof Element ? record.target : record.target.parentElement;
                if (target?.closest('[data-chat-flow]'))
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
        addReconcilerTask((0, overlay_backdrop_fab_ts_1.createOverlayTask)(t, () => (0, drawer_close_ts_1.toggleDrawer)(ctx), panelExit)),
        addReconcilerTask(panelExit.task),
        addReconcilerTask((0, file_viewer_compat_ts_1.createFileViewerMarkerTask)()),
        addReconcilerTask((0, drawer_session_log_ts_1.createDrawerSessionLogTask)(ctx)),
        addReconcilerTask((0, image_lightbox_zoom_ts_1.createImageLightboxZoomTask)()),
    ];
    return () => {
        for (const remove of removeTasks)
            remove();
        reconcileTasksRegistered = false;
    };
}
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
 * `interactive-widget=resizes-content`（2026-10-18 补）：手机上「键盘弹起时整页被
 * 顶上去、顶栏出屏」不是本插件的布局 bug，而是 Chrome for Android 的键盘视口模型——
 * 该 flag 缺省即 `resizes-visual`，只缩**视觉**视口，布局视口（`100%` / `100dvh`
 * 跟着的那个）仍是整屏高，浏览器为了露出被聚焦的输入框把整页往上平移。本仓所有
 * 键盘测量都是在 Android WebView（`adjustResize`，键盘缩**布局**视口）上取的，那台
 * 引擎没有这个问题；显式要 `resizes-content` 就是让 Chromium 浏览器回到同一模型：
 * `100dvh` / `innerHeight` / frame 全都等于可见区，页面不再平移。不认这个值的引擎
 * （iOS Safari）会忽略它，行为与改动前一致——iOS 的键盘模型仍由
 * composer-keyboard-lift.ts 兜。
 */
const VIEWPORT_CONTENT = 'width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content';
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
    const syncStableViewport = () => {
        const height = window.innerHeight;
        const width = window.innerWidth;
        const visible = window.visualViewport;
        const available = Math.max(1, Math.min(height, visible?.height ?? height));
        const visibleValue = `${available}px`;
        const topValue = `${visible?.offsetTop ?? 0}px`;
        if (root.style.getPropertyValue('--dsha-mobile-visible-vh') !== visibleValue)
            root.style.setProperty('--dsha-mobile-visible-vh', visibleValue);
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
 *   interactive-widget=resizes-content (keyboard shrinks the layout viewport
 *   on Chromium instead of panning the page), re-asserted on every host
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
function installPhoneChrome(ctx) {
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
 * Lineage root plus its menu. Defensive dual hash: ZKlsPq is present in
 * the current host; h8S2Va is retained as a historical fallback.
 * Hash presence does not identify the hover/click interaction era.
 * Audit these selectors when the host package upgrades.
 */
const HOVER_SUBTREE_SELECTOR = '[class*="ZKlsPq_root"], [class*="ZKlsPq_menu"], [class*="h8S2Va_root"], [class*="h8S2Va_menu"]';
/** How long after touch activity synthesized hover events stay suppressed. */
const SWALLOW_WINDOW_MS = 800;
/**
 * How long the tap's follow-up click stays suppressed on the trigger we
 * toggled through the keyboard path. A touch click lands a few ms after its
 * pointerup; 1 s is a generous upper bound that still expires before the
 * user's next deliberate tap.
 */
const CLICK_GRACE_MS = 1000;
const SWALLOWED_TYPES = ['mouseover', 'mouseout', 'mouseenter', 'mouseleave'];
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
        // 过期结果不得 closeDialog / clear / 收抽屉，否则会拿旧响应改新对话框与新当前会话。
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
        /**
         * Read one menu item's visible label across host generations: rc.2 nests
         * the text in an `_itemLabel` span (beside an `_itemIcon`), while 0.1.5
         * puts it directly in the button (`_item_1nxmc_92`, no child elements).
         * Falling back to the item's own textContent covers both — svg icons
         * contribute no text, so rc.2 items read identically either way.
         */
        const itemLabel = (item) => {
            const label = item.querySelector('[class*="_itemLabel"]');
            return (label ?? item).textContent?.trim() ?? '';
        };
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
                // 别的会话，用请求前算好的旧值 clear() 会把新当前会话的选中态清掉（2026-10-11）。
                const wasCurrent = (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) === sessionId;
                // 过期结果（期间又发起过删除）只丢弃，不关别人的对话框、也不动状态。
                if (epoch !== deleteEpoch)
                    return;
                closeDialog();
                if (wasCurrent && (0, sessions_compat_ts_1.sessionsCanClear)(ctx.sessions))
                    ctx.sessions.clear();
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
            const label = button.querySelector('[class*="_itemLabel"]');
            if (label !== null) {
                label.textContent = navT('deleteSession');
                label.style.color = DANGER_COLOR;
            }
            else if (button.firstElementChild === null) {
                // 0.1.5 shape: the menuitem button carries its text directly (no
                // `_itemLabel` span, no icon element). Replace the whole text and let
                // the danger color ride the button itself. A button WITH element
                // children but no label span is an unknown future shape — leave its
                // text alone rather than guess.
                button.textContent = navT('deleteSession');
                button.style.color = DANGER_COLOR;
            }
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
 *  Exported: session-focus-guard.ts shares the same shadow slot (one marker,
 *  one own-property recipe) so both guards stay interoperable. */
exports.SHADOW_MARKER = 'data-mobile-nav-focus-shadow';
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
/** 宿主加号按钮的类名片段；模型/权限触发器是 `_trigger`，不会被误伤。 */
const ADD_SELECTOR = '[class*="_add"]';
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
__modules["effects/session-focus-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSessionFocusGuard = installSessionFocusGuard;
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const composer_keyboard_guard_ts_1 = require("./effects/composer-keyboard-guard.js");
const breakpoints_ts_1 = require("./platform/breakpoints.js");
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
const FOCUS_GUARD_WINDOW_MS = 800;
/** The Lexical editing surface — the only element whose autofocus we swallow. */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
/** Frame marker panel-exit sets before it starts leaving (see effects/panel-exit.ts). */
const PANEL_EXIT_ATTR = 'data-mobile-panel-exit';
/**
 * Synchronous companion event, dispatched by panel-exit the moment the marker
 * lands. The marker alone is too late: the InputBar remounts and focuses inside
 * the commit's synchronous layout-effect phase, i.e. before an observer
 * microtask can open the window (the same 2026-09-29 measurement quoted in the
 * header). The event opens the window first, the marker stays as the backstop.
 */
const PANEL_EXIT_EVENT = 'dsh-mobile-panel-exit';
/**
 * Keep the session-switch autofocus from raising the soft keyboard, on the
 * mobile breakpoint only.
 * @param ctx - client root context.
 */
function installSessionFocusGuard(ctx) {
    (0, breakpoints_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: session focus guard', () => {
        const list = ctx.sessions.list;
        // Snapshot value at install time: subscribing must not arm the window by
        // itself — only a CHANGE of the current session id does.
        let lastSessionId = (0, sessions_compat_ts_1.currentSessionIdOf)(list.getSnapshot());
        let windowTimer = 0;
        let windowOpen = false;
        const restore = () => {
            window.clearTimeout(windowTimer);
            windowTimer = 0;
            windowOpen = false;
            const el = document.querySelector(`[${composer_keyboard_guard_ts_1.SHADOW_MARKER}]`);
            if (el === null)
                return;
            el.removeAttribute(composer_keyboard_guard_ts_1.SHADOW_MARKER);
            const shadowed = el;
            if (Object.prototype.hasOwnProperty.call(el, 'focus'))
                delete shadowed.focus;
        };
        const shadow = (el) => {
            if (el.hasAttribute(composer_keyboard_guard_ts_1.SHADOW_MARKER))
                return;
            el.setAttribute(composer_keyboard_guard_ts_1.SHADOW_MARKER, '');
            Object.defineProperty(el, 'focus', {
                configurable: true,
                writable: true,
                value: function swallowedFocus() {
                    /* session-switch autofocus; keep the keyboard down */
                },
            });
        };
        const hasPanelExitMarker = () => document.querySelector(`[data-mobile-nav="frame"][${PANEL_EXIT_ATTR}]`) !== null;
        const arm = () => {
            restore();
            windowOpen = true;
            const el = document.querySelector(COMPOSER_INPUT_SELECTOR);
            if (el !== null)
                shadow(el);
            windowTimer = window.setTimeout(restore, FOCUS_GUARD_WINDOW_MS);
        };
        // Session switches remount the InputBar: catch the freshly mounted editor
        // inside the window. Microtask timing beats the host's passive effect.
        // The same observer watches the panel-exit marker: a panel occupies the
        // main slot while open, so leaving it remounts the InputBar and re-runs its
        // mount-time focus. Arm on the marker's arrival — it lands before the panel
        // actually leaves (panel-exit animates first, then selectPanel), so the
        // window is already open when the conversation remounts.
        const observer = new MutationObserver(() => {
            if (!windowOpen && hasPanelExitMarker())
                arm();
            if (!windowOpen)
                return;
            const el = document.querySelector(COMPOSER_INPUT_SELECTOR);
            if (el !== null)
                shadow(el);
        });
        // A tap on the editing surface is the user saying "I want to type": close
        // the window on the spot so the residual shadow cannot eat anything.
        const onPointerDown = (event) => {
            if (!windowOpen)
                return;
            const target = event.target;
            if (target instanceof Element && target.closest(COMPOSER_INPUT_SELECTOR) !== null)
                restore();
        };
        // Timing fallback for the window's lifetime: the host focuses the freshly
        // mounted editor from the commit's synchronous phase, before the observer
        // microtask can shadow it (headless-measured 2026-09-29, see header), so
        // any focus that still lands on the editing surface inside the window is
        // blurred on the spot — before the IME can rise. User taps never reach
        // this: their pointerdown closed the window above.
        const onFocusIn = (event) => {
            if (!windowOpen)
                return;
            const target = event.target;
            if (target instanceof HTMLElement && target.closest(COMPOSER_INPUT_SELECTOR) !== null)
                target.blur();
        };
        // Invalidation callback (zustand-style): re-read the snapshot and arm only
        // when the current session id actually changed — list churn (titles,
        // ordering, refresh) must never open the window.
        const unsubscribe = list.subscribe(() => {
            const current = (0, sessions_compat_ts_1.currentSessionIdOf)(list.getSnapshot());
            if (current === lastSessionId)
                return;
            lastSessionId = current;
            arm();
        });
        const onPanelExit = () => {
            if (!windowOpen)
                arm();
        };
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: [PANEL_EXIT_ATTR],
        });
        document.addEventListener(PANEL_EXIT_EVENT, onPanelExit);
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        return () => {
            unsubscribe();
            observer.disconnect();
            document.removeEventListener(PANEL_EXIT_EVENT, onPanelExit);
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('focusin', onFocusIn, true);
            restore();
        };
    });
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
 * 不认 `interactive-widget`；Chromium 自 2026-10-18 起键盘缩 layout 视口，两条通道重合）
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
 * 安装手机档的输入框文件入口弹层。
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
    });
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
 * Ordered draft attachment ids from one input-state snapshot. 0.2.0-rc.2
 * publishes `attachmentIds` (files + images); the frozen rc.6 contract named
 * the image-only predecessor `imageIds`. Anything else → empty.
 */
function draftAttachmentIdsOf(state) {
    if (!isRecord(state))
        return [];
    const ids = Array.isArray(state.attachmentIds) ? state.attachmentIds : Array.isArray(state.imageIds) ? state.imageIds : [];
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
 *   InputState.attachmentIds                  → ordered draft ids (rc.2;
 *       the rc.6 contract named the image-only predecessor `imageIds`)
 *   conversation.resolveDraftAttachments(ids) → `{ kind, id, file }` descriptors
 *       (rc.2; rc.6 `draftImages(ids)` — both feature-detected)
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
        : typeof conversation.draftImages === 'function' ? conversation.draftImages(ids) : undefined;
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
    'share': '分享',
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
    'share': 'Share',
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
const hostShareIcon = ['IconShareOutlineRegular', 'IconShareOutline16']
    .map(name => hostTable[name])
    .find(value => typeof value === 'function');
const hostWarningIcon = ['IconWarningOutlineRegular', 'IconWarningOutline16']
    .map(name => hostTable[name])
    .find(value => typeof value === 'function');
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
// The layout service face drifted between host generations. rc.6's ILayout
// carries only toggleSidebar/openDetails/closeDetails; 0.1.6-alpha.2 added
// selectPanel(panelId | null) for the sidebar's main-area panels. This helper
// lets call sites stay compile-green against rc.6 typings while degrading
// explicitly on a host that cannot select a panel, instead of throwing.
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
 *     「返回」 on a panel subpage — see createOverlayTask in
 *     overlay-backdrop-fab.ts
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
/** Synchronous companion of the marker, for the composer focus guard. */
const PANEL_EXIT_EVENT = 'dsh-mobile-panel-exit';
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
     * live on every call: the subpage is host component state, so the control
     * appears and disappears without any URL or marker of ours changing.
     */
    function subpageControl() {
        if (!panelOwnsMainArea())
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
        // Synchronous heads-up for the composer focus guard
        // (effects/session-focus-guard.ts): leaving a panel remounts the InputBar,
        // and the host's mount-time focus runs inside the commit's synchronous
        // layout-effect phase — earlier than any MutationObserver microtask. A
        // custom event is the only decoupled way to open that window in time.
        document.dispatchEvent(new CustomEvent(PANEL_EXIT_EVENT));
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
        supported,
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
            const control = subpageControl();
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
        const params = new URLSearchParams(location.search);
        if (!params.has('mobile-nav-debug'))
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
            const rev = readBundleRev();
            return [
                `build rev ${rev ?? '?'} (diag chips)`,
                `URL ${location.pathname}${location.search}`,
                `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
                `mq≤1023 ${matchMedia(breakpoints_ts_1.MOBILE_QUERY).matches}  mq≥1024 ${matchMedia(breakpoints_ts_1.DESKTOP_QUERY).matches}`,
                `safeTop framePad ${frame === null ? 'n/a' : getComputedStyle(frame).paddingTop}  rightPanel ${rightPanel()}`,
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
const composer_plus_toggle_ts_1 = require("./effects/composer-plus-toggle.js");
const workspace_chip_toggle_ts_1 = require("./effects/workspace-chip-toggle.js");
const team_chip_toggle_ts_1 = require("./effects/team-chip-toggle.js");
const model_menu_anchor_ts_1 = require("./effects/model-menu-anchor.js");
const shortcut_modal_keyboard_guard_ts_1 = require("./effects/shortcut-modal-keyboard-guard.js");
const model_menu_keyboard_guard_ts_1 = require("./effects/model-menu-keyboard-guard.js");
const plugin_card_tap_ts_1 = require("./effects/plugin-card-tap.js");
const installed_list_ts_1 = require("./effects/installed-list.js");
const session_focus_guard_ts_1 = require("./effects/session-focus-guard.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const composer_paste_guard_ts_1 = require("./effects/composer-paste-guard.js");
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
function conversationScrollers() {
    return [...document.querySelectorAll('[data-mobile-nav="frame"] [class*="scrollBody"]')];
}
function rememberConversationScroll() {
    for (const element of conversationScrollers()) {
        if (element.scrollTop > 0)
            scrollState.set(element, { top: element.scrollTop, savedAt: Date.now(), url: location.href });
        else
            scrollState.delete(element);
    }
}
function restoreConversationScroll() {
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
    (0, composer_keyboard_lift_ts_1.installComposerKeyboardLift)(ctx);
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
    // Entering a session (issue #140): the host's InputBar focuses the editor
    // from a [locked, sessionId, editor] passive effect on every switch, which
    // raises the soft keyboard over the history the user wanted to read. A short
    // shadow-focus window per observed session switch swallows that one
    // autofocus; real taps are unaffected.
    (0, session_focus_guard_ts_1.installSessionFocusGuard)(ctx);
    // Multi-line paste after an IME commit keeps only the first line (host
    // Lexical routes Android's insertText paste through the text-insertion
    // command). Re-dispatch those as a paste event; mobile-only.
    (0, composer_paste_guard_ts_1.installComposerPasteGuard)(ctx);
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
            toggleSidebar: () => ctx.layout.toggleSidebar(),
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
