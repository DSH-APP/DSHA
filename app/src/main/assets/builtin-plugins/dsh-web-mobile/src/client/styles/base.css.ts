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
  /* 44px 触控热区（2026-10-25 owner 指定）：盒子保持 28×28（观感与顶栏行高不变），
     命中区由下面的透明 ::after 外扩 8px 拿到 44×44。锚点必须是按钮自己，所以这里
     补 position:relative。 */
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
/* 悬停反馈只给真有指针的设备：手机上没有 hover，Chromium 会把点击后的 :hover
   粘住，图标一直是一块灰底 —— 这就是「粘滞 hover」。按下反馈由 :active 单独承担，
   触屏观感不变。 */
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
/* 顶栏两端各一颗，中间隔着标题，不与任何控件相邻，可以对称外扩。 */
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
  /* ::after 的锚点；28 + 8*2 纵向、+6*2 横向 = 44 高 / 40 宽命中盒（品牌按钮在左）。 */
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
/* 44px 热区：品牌行右端，左侧是宿主品牌按钮（flex:1，占满整行），所以横向只外扩
   6px（40 宽），纵向外扩 8px（44 高）；再宽就会从品牌按钮的右端抢走可点区域。 */
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
/* 菜单开着时触发器保持按下底（owner 2026-10-25：「点它弹面板时没有灰，反而是关闭
   那一下闪一层灰，顺序像反了」）。根因是 portal 菜单一挂载就把指针从按钮上接走，
   :active 立刻结束 ⇒ 开面板那一下的按下底几乎看不见；关闭那次点击时菜单已经不在了，
   :active 反而完整走完。用 aria-expanded 把「开着」表达成持久的按下态：开面板即
   有反馈、关掉即撤掉，与宿主菜单触发器的既有形态一致（选中态而不是悬停态）。 */
[data-mobile-nav="session-log"][aria-expanded="true"]:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}

/* Drawer quick-settings icon (2026-10-20): the host merged the settings entry
   into the account launcher (the settings.launcher slot), so opening settings on
   a phone costs two taps — the footer account row, then the menu's first item.
   effects/drawer-quick-settings.ts injects this gear at the right end of that
   same row and replays that path in one tap. The wrapper mirrors the brand-row
   icon so no row heuristic adopts the node as the host's own control. */
[data-mobile-nav="quick-settings-wrap"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  /* 2026-10-21 用户「UI应该再向左调整一下」：行宽是 calc(100% + 4px)、左右各外扩 2px，
     账户 launcher 内边距 6px —— 让齿轮右缘对齐那颗按钮的内容右缘（column − 4px）。 */
  margin-right: 6px;
}
[data-mobile-nav="quick-settings"] {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  /* 2026-10-21 用户「UI太小了!」：28 → 36（图标 16 → 20），仍在 44px 行内居中。
     2026-10-22 用户「还是太大了」：36 → 30（图标 20 → 17，同比例）。 */
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: var(--dsw-radius-md, 8px);
  background: transparent;
  color: var(--dsw-alias-label-secondary, inherit);
  cursor: pointer;
  /* 30 + 7*2 = 44 命中盒；账户 launcher 在左，只吃 7px。 */
  position: relative;
  -webkit-tap-highlight-color: transparent;
}
/* 44px 热区：30×30 的盒子外扩 7px；左侧是账户 launcher（整行大按钮），只吃 7px。 */
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
/* 2026-11-05 真机：齿轮点下去没有任何反馈。它是一颗 30px 的透明图标按钮，
   通用按压位（:active 的 inset 高亮 + scale .96）在它身上太淡；这里给足：
   用 active 级底色 + 缩到 .94，并把整颗抬到行内最上层，免得被账户 launcher
   的层盖住。 */
[data-mobile-nav="quick-settings-wrap"] {
  position: relative;
  z-index: 1;
}
/* 按下态用显式属性（effects/drawer-quick-settings.ts 在 pointerdown 挂、松开后
   再留 140ms）。:active 保留作键盘 / 桌面兜底：真机上这颗 30px 透明按钮的
   :active 一点都画不出来（2026-11-05）。 */
/* 账户行的按下态：同样用显式属性（effects/drawer-quick-settings.ts 在 pointerdown
   挂、松开后再留 140ms）。宿主那颗 launcher 的 :active 在「菜单同拍挂载」时会被
   浏览器收掉，淡入一帧都没画出来（2026-11-05 真机：点开没反馈、关掉才有灰）。
   形状镜像通用按压位：inset 高亮填满整颗按钮。 */
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

/* @patch
   id: drawer-quick-settings-chip-guard
   for: dsh-client-ui-primitives 的 ConnectionIndicator（connecting/disconnected 渲染 button[data-phase]，recovered 渲染 div[role="status"]）与账户 launcher 同处 settingsArea 的 triggerRow
   why: 该行只有 260px；连接条 flex:none（中文约 136px、英文约 190px），齿轮常驻会在英文下把账户按钮挤到约 26px、头像被裁
   until: 宿主给该行提供独立的次要动作位，或连接条不再占用行宽——届时删本块
   test: tests/drawer-quick-settings.test.ts
*/
[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"]:has(> button[data-phase], > [role="status"]) [data-mobile-nav="quick-settings-wrap"] {
  display: none !important;
}

/* @patch
   id: drawer-quick-settings-menu-entry
   for: dsh-client-ui-settings-account 的 AccountMenu items 数组（settings / contact / signout|signin）与 dsh-client-ui-primitives 的 Menu 列表结构（role=menu > div[role=presentation] > div > button[role=menuitem]）
   why: 2026-10-21 用户要求「把原来的打开方式进行删除，只保留这个」——齿轮已是设置入口，账户菜单里同一项成了重复路径；宿主源码不可改，只能由 effects/drawer-quick-settings.ts 在菜单节点上打标记、这里收起第一项
   until: 宿主给 settings.launcher 提供独立动作位，或账户菜单自己把设置项拆出去——届时删本块
   test: tests/drawer-quick-settings.test.ts
*/
/* 2026-11-05 真机：自己点账户行打开菜单时，卡片先按「3 项」画出来，等 reconciler
   下一拍打上标记再收起第一项 —— 高度变化让整张卡蹦一下。改成在**点下那一刻**就把
   html 标记挂上（drawer-quick-settings.ts 的 pointerdown 捕获），菜单从出生就没有
   那一项：没有任何一帧画过它，也就没有跳动。标记在菜单被打上正式标记后摘掉，
   另有超时兜底。 */
html[data-mobile-nav-quick-pending] [role="menu"] > [role="presentation"] > :first-child > [role="menuitem"],
[data-mobile-nav-quick-menu] > [role="presentation"] > :first-child > [role="menuitem"] {
  display: none !important;
}

/* Same click chain, flash guard: the gear opens the host account menu and picks
   its first item in the following frame. While the marker sits on <html> that
   intermediate menu stays unpainted (a JS click does not need visibility); the
   task removes the marker once the chain settles. */
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
  /* （2026-11-06 审计 C7）delete-dialog 自身没有动画，animation:none 是空操作，已移除。 */
  [data-mobile-nav="delete-dialog-backdrop"],
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
     GATE (2026-09-25, real device; re-anchored 2026-11-08): the gate follows
     the dim, NOT the drawer-open marker. Marker and paint disagree for the
     whole close transition — the dim fades over .28s with the column, the
     column transitions .28s (layout.css.ts) and React swaps the pane subtree
     ~200ms late — so a marker-gated raise went dark inside that window and
     the drawer band covered any open modal. Measured on the reporter's phone
     (Android 16 WebView) with the shortcut modal open: forcing
     data-sidebar-collapsed dropped this root 1400 -> 1000 and made
     elementsFromPoint(0.85w, .30h) return the dim — rgba(0,0,0,.45) over the
     modal's white = luminance 141, matching the recording (140 behind a 280px
     drawer edge). That is the "快捷键弹层抽搐/闪" report: a ~200-280ms dark
     frame with the drawer over the shortcut modal, not a compositing tear.
     The dim is painted by the frame's ::after now, so the trigger is
     [data-active] on our hit target: set exactly while the dim paints and
     released BAND_RELEASE_MS (one fade + the old 60ms slack, see
     drawer-layer.ts) after the marker flips — that slack keeps the
     raise past the ~200-280ms subtree swap above. With no active backdrop the
     host's own ordering stands (a menu opened inside a modal still sorts above
     it). Our own delete backdrop matches this rule too since the 2026-09-24
     centered rework (its direct child card carries role=dialog) — harmlessly:
     it sets the same 1400 the dedicated rule below sets. */
  body:has([data-mobile-nav="hit"][data-active])
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
     control once a panel owns the main area, see drawer-layer.ts).
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
      /* 按下当帧就到位（2026-11-05 真机：点账户行开菜单时「开启没有反馈、再点关掉
         才有灰色」——0.12s 的淡入还没画出来，菜单就把指针状态打断了；下面那条基态
         transition 只负责松开后的淡出）。 */
      transition: none;
      box-shadow: inset 0 0 0 100vmax var(--dsw-alias-interactive-bg-active, color-mix(in srgb, currentColor 8%, transparent));
    }
    /* Icon-only buttons (a lone svg child, or a button that carries only an
       aria-label) also shrink a touch. */
    :where(button:has(> svg:only-child), button[aria-label]:not(:has(> span, > div))):where(:active):where(:not(:disabled, [aria-disabled="true"])) {
      transition: none;
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
/* 44px 热区：38×38 的悬浮球外扩 3px。 */
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

/* Drawer dim, painted by the frame's own pseudo-element.
   z 1250: 0.1.5 pins its native sidebarCol at z-index:1100 and paints mid
   layers up to that band; the dim must sit above the host stack (below the
   drawer's 1300) so it covers the content area on every host generation.
   Keep in sync with the drawer z in layout.css.ts.
   Device-only report (2026-11-08): tapping the blank area to close flashed the
   right side bright, from the second open/close cycle on. Bisect: no overlay
   task → no flash; any full-screen element that paints (or even just
   hit-tests with an interactive role) → flash. A ::after on the frame lives in
   the frame's existing paint tree, so there is no extra composited full-screen
   layer to be re-rastered when the drawer animates. The dim only animates
   opacity with fixed geometry, which the device compositor cannot flash. */
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
/* Hit target only: paints nothing, no interactive role/aria, no animation.
   The dim comes from the frame's ::after. */
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
