// base — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Do not reorder: styles/index.ts concatenates in this exact order.

export const BASE_CSS = `
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

`
