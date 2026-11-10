// layout — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: the mobile media query opens and closes in this file.

export const LAYOUT_CSS = `/* ---------- mobile-only layout (narrow viewport AND touch-primary pointer) ---------- */

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
       no CSS opt-out (WebKit bug 240183) — there the mitigation is the
       gesture layer's own rule: a drawer stroke is a RIGHTWARD drag, the
       opposite direction of the edge-back swipe, and since issue #268 a
       stroke may start at any x (no start zone to dodge the edge strip).
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
  /* ---------- the shell is exactly the VISIBLE band, and the document cannot
     scroll (2026-10-31) ----------
     Field dump, 402x684 iPhone with the keyboard up:

       META     vv=402x345@325  inner=402x359  scrollY=325  doc=402x684
       FRAME    [0,-325 402x684]

     The frame is still the full 684 while the visible band is 345 tall, so the
     document is scrollable by exactly the keyboard height — and something in the
     host scrolls it to that maximum (the Lexical caret helper's window.scrollBy
     is the known caller, issue #149). Once the document has scrolled 325px, the
     whole frame — composer included — sits 325px above the band. That is the
     real cause of 「输入框缩下去 / 跳下去」; every compensation applied downstream
     of it (transform, container height) was fighting the symptom, and the
     engine's numbers only looked self-inconsistent because the page had been
     scrolled underneath us.

     Fix at the source: size the shell to the visible band and take the document
     out of the scrolling business entirely. 「--dsha-mobile-visible-vh」 is
     maintained live by viewport-meta (min of innerHeight and the visual viewport height),
     so this follows the keyboard without any per-engine arithmetic; the app's own
     containers keep their scrolling. */
  /* overflow: hidden only stops the USER; the host's caret helper calls
     window.scrollBy, which still moves an overflow:hidden document. Field dump
     2026-10-31 11:44:50 (keyboard up): scrollY=92 with the shell at -92 while the
     visible band was [92, 437] — the shell's bottom edge sat at 253, so the lower
     184px of the band showed the page background under the composer. That is the
     owner's 「输入框被顶飞，看到底部的空白」. Locking the scroll from CSS via
     position: fixed on html broke the whole document paint on the owner's phone
     (2026-10-31 「炸了」), so the lock is done in JS instead — see
     effects/document-scroll-lock.ts. */
  html,
  body {
    height: 100% !important;
    overflow: hidden !important;
    overscroll-behavior: none !important;
    /* The shell now ends where the visible band ends, so while the keyboard is
       still animating there is a strip of body below it. Without this the strip
       is the browser's default white — read as "白屏" for the 1-2s the keyboard
       takes to rise. Paint it with the host's own page colour so the strip reads
       as the app, and the keyboard simply slides over it. */
    background: var(--dsw-alias-bg-base, #fff) !important;
  }
  html [data-mobile-nav="frame"] {
    box-sizing: border-box !important;
    position: relative !important;
    height: var(--dsha-mobile-visible-vh, 100%) !important;
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
    /* 优先用 App 自己发布的 --dsha-safe-area-inset-top：它是「这个 WebView 实际被
       状态栏遮住多少」。env() 报的是屏幕刘海/状态栏高度，与 WebView 的摆放位置无关——
       App 内嵌浏览器把 WebView 放在自己的顶栏下面时，env() 仍是 38px，就让一次位、
       页头下面多出一条空白（2026-11-06 真机读数：framePad 38px）。App 没发布该变量时
       仍回退到 env()，行为不变。 */
    padding-top: var(--dsha-safe-area-inset-top, env(safe-area-inset-top, 0px)) !important;
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

  /* @patch
     id: shell-grid-drawer-mask
     for: dsh-client-ui-layout 0.2.2-rc.1 的 AppFrame（三列显式 grid-area + 原生 drawerMask 作为首个子元素）
     why: 本插件把侧栏绝对定位、只留一条 1fr 轨道；主列继承宿主写死的 1/2 会落在 0 宽轨道上（真机整页白屏），
          原生遮罩还会抢走所有 '> :first-child' 抽屉锚点。
     until: 宿主给移动壳提供独立的列轨道/标记，或不再在 frame 里插入首个子元素遮罩
     test: tests/css-rules.test.ts
  */
  /* 0.2.2 host pins the three shell columns with explicit grid-area
     (sidebar 1/1, center 1/2, right 1/3). Our drawer keeps the sidebar out of
     flow and leaves a single 1fr track, so the center column must not keep the
     host's hard-coded 1/2: that track is one of the two we force to 0, which
     collapses the whole conversation area to 0px wide (phone blank page).
     Re-seat it explicitly on the 1fr track. */
  [data-mobile-nav="frame"] > [class*="_centerCol"] {
    grid-area: 1 / 1 / 2 / 2 !important;
  }
  /* 0.2.2 also inserts a native drawer mask into the frame, and renders it as
     the FIRST child. We keep our own backdrop (z 1250) and own drawer column,
     so the host layer would only double-dim; worse, every drawer rule below
     anchors on '> :first-child' and would style the mask as the sidebar.
     Hide the host mask and keep the plugin's own. */
  [data-mobile-nav="frame"] > [class*="_drawerMask"] {
    display: none !important;
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
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
    position: absolute !important;
    inset: 0 auto 0 0 !important;
    /* !important is load-bearing: 0.2.2's AppFrame writes the drawer column's
       width as an INLINE style (style={{ width: sidebarWidth }} when drawer),
       which beats any plain declaration here. (The 0.1.x host rule
       [data-dsh-frame] [data-pane="sidebar"] that first made this important is
       gone from the 0.2.2 bundles.)
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
     intact). Start-hit lives in the gesture layer alone — no hotspot element
     (removed per audit C2, 2026-08-27) and, since issue #268, no geometric
     start zone either: the closed state may start a stroke anywhere and the
     DIRECTION picks the family. pinch-zoom rides along with the
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
  body[data-dsh-bg] [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
    background: var(--dsw-static-neutral-bluish-50, #f9fafb);
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
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
  [data-mobile-nav="frame"][data-sidebar-collapsed] > [class*="_sidebarCol"] {
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
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) > [class*="_sidebarCol"] {
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
  /* 2026-11-05：0.1.x 的 [data-pane="sidebar"] / [data-dsh-responsive-part="sidebar-toggle"]
     两臂已删——0.2.2 宿主包里这两个属性零输出（当年与 @linxin666/dsh-web-all 的 (0,4,0) 平局也
     随之消失）。留下的哈希类与按标签的兜底都带前导 html，与第三方注入的同类规则不再按表序分胜负。 */
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
     pins it as a full-bleed sheet: [data-sidebar-right-panel=fullscreen]
     carries inset:0 (0.1.5: position:fixed; 0.2.x dockkit: position:absolute,
     both spanning the whole viewport) and no inset of its own (the host CSS
     never mentions safe-area at all). Its top row - the tab strip holding the
     tab label, the + button and the Split / Exit-fullscreen pair at the right
     edge - therefore sat UNDER the status bar: measured at 390x844 with the
     panel open, the strip is [0,0,390,38] and the phone's status bar owns the
     top of the screen. The frame's own safe-area padding cannot reach it: the
     panel starts at the viewport top regardless of the frame's padding box.
     Taking the inset as padding pushes the whole row below the status bar,
     with the right-hand buttons still on the right edge.
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

  /* 0.2.0 dockkit 重构删掉了面板本体自带的 z-index 与底色（0.1.5 是
     .P3OORG_panel{z-index:10; background:var(--dsw-alias-bg-base)}）。下面两条
     规则各补回一条，共用 [data-sidebar-right-open] 门控：fullscreen 形态值由
     视口宽度推导、关态也在场，且关态面板的 rect 同样铺满视口（实测 390x844 →
     [0,0,390,844]），漏门会给关闭态那块空面板涂色/抬层。 */
  /* @patch
     id: fullscreen-panel-sheet
     for: dsh-client-ui-sidebar-right 0.2.x 的 [data-sidebar-right-panel="fullscreen"]（0.2.0 起面板本体不再自带 z-index）
     why: 面板 z auto 而活跃会话的输入卡座是 sticky z-index:7，面板自身表面被输入卡压住；恢复 0.1.5 的 z-index:10，面板重新是一整张 sheet
     until: 上游把 z-index 还给面板本体，或给全屏面板一个不依赖 z 的层叠接缝——届时本块删除
     test: tests/fullscreen-panel-sheet.test.ts
  */
  [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    /* 11：高过会话顶栏（10）——两者都从视口顶边起画，顶栏不透明且此前是 30，
       把面板顶部的标签条整条盖掉了；仍低于宿主 AppFrame 的 overlayLayer（20），
       设置等弹层照旧盖在面板之上。 */
    z-index: 11 !important;
  }
  /* @patch
     id: wallpaper-panel-fill
     for: deepseek-harness-background 0.6.x 的 body[data-dsh-bg]（壁纸启用时把 --dsw-alias-bg-base 置为 transparent）+ 0.2.x 全屏右侧栏面板
     why: 面板唯一的底色就是继承来的 --dsw-alias-bg-base（浅色 = neutral-bluish-00、深色 = neutral-bluish-950）；令牌被清空后全屏面板整片透出会话正文，无壁纸时内容层自绘该令牌故不需要常驻补色
     until: 上游给全屏面板提供不透明的填充接缝，或不再把 --dsw-alias-bg-base 置空——届时本块删除
     test: tests/wallpaper-surface-fill.test.ts
  */
  body[data-dsh-bg] [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    background-color: var(--dsw-static-neutral-bluish-00, #fff);
  }
  body[data-ds-dark-theme][data-dsh-bg] [data-sidebar-right-open][data-sidebar-right-panel="fullscreen"] {
    background-color: var(--dsw-static-neutral-bluish-950, #151517);
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
    [data-mobile-nav="frame"] > [class*="_sidebarCol"] {
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
  html[data-mobile-nav-selecting] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
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

  /* Message tooltips are redundant on touch: the icon already flips to a
     checkmark. role="tooltip" stays globally suppressed: genuine ARIA
     tooltips are exactly what the sticky-residue fix targets. The old
     actions-row "_bubble" arm (DSH 0.1.2 inline tooltip shape) was removed
     with the low-generation branches. Never widen this to
     [data-message-bubble]: that is the user message itself (2026-09-06
     regression hid every user message on touch devices). */
  /* （2026-11-06 审计 D7）这里原本还套了一层 @media (hover:none),(pointer:coarse)，
     外层已经是 pointer:coarse，内层恒真，包裹去掉。 */
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
  [data-phase] [class*="_userStack"] [data-message-bubble] {
    box-sizing: border-box;
    width: fit-content;
    max-width: 100%;
  }

  /* --- Hero 工作区行上的「工作树」块（2026-11-07，店主拍板方案 E）---
     默认布局：heroWorkspaceRow 是一条 nowrap 的 flex 行（宿主 padding 0 16 0 20、
     gap 2px、align-items:center），里面依次是工作区 chip、directoryFlow / 预设两个
     槽、以及 margin-left:auto 的 heroOptions。forge 插件（开源版 0.2.2-rc.1 的
     WorktreeToggle）把自己的「分支」按钮 + 「新建工作树」复选挂进 heroOptions，并在
     自己的 @media (width<=600px) 里把根盒改成 flex-direction:column —— 两行 28px
     叠成 56px 高。390px 真机实测：heroOptions 126.1×56，把同一行的模式 chip 压到
     67.3px（「标准模式」只剩两个字宽），右侧同时留下一条竖直空白。
     店主否决了「只加高不换行」（A）与「砍文字」（其它分支），要的是**工作树块自己
     占一行、且分支与新建工作树横向排开**。三条规则配套：
       ① 行允许换行并给 4px 行距；
       ② heroOptions 撑满整行（basis 100% 必折行）+ order:-1 排到工作区 chip 之上
          + 右对齐（保留宿主本来 margin-left:auto 的观感）；
       ③ 工作树根盒改回横排，间距 8px（比宿主 2px 松，两颗控件各自有 28px 高度，
          贴太近容易误触）。
     实测（390×844，触摸仿真）：行高 56→60（多出的一行被工作树块自己 56→28 抵掉），
     工作树块 126.1×56→202.6×28，模式 chip 67.3→143.5px，doc scrollWidth 恒 390、
     无横向溢出，composer 卡顶 425.4→427.4（+2px，挤进 hero 相位本来就富余的留白）。
     全部规则用 :has([data-forge-worktree]) 门控：没装 forge 的 profile 上 hero 行
     一个像素都不变，也不给空 heroOptions 留出空行。 */
  /* @patch
     id: hero-forge-worktree-row
     for: @deepseek-ai/dsh-experimental-client-ui-forge 的 WorktreeToggle（heroOptions 槽内的 [data-forge-worktree]）
     why: 插件自带的 @media (width<=600px) 把根盒改成纵向两行 56px，同一条 nowrap 的 hero 行里把模式 chip 压窄并留下竖直空白
     until: forge 自己给出窄屏横排形态，或宿主把 heroOptions 挪出 heroWorkspaceRow，届时删本块
     test: tests/css-rules.test.ts
  */
  @media (max-width: 767px) and (pointer: coarse) {
    /* 只在手机档折行。平板（768–1023）原本就放得下：实测 768 时行宽 744、
       工作区 chip 152.6 + 预设 chip ≈120 + 工作树块 202.6 加起来远小于可用宽，
       forge 自己的媒体查询（视口 600px 起效）在那一档也还没把根盒改成竖直，
       所以那里一个像素都不该动。（2026-11-07 首版写在顶层移动块里，768 上
       实测多折出一行 60px 高——属于加料，收窄到此档。） */
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
  /* （2026-11-06 审计 D2 复核：这三条**保留**）0.1.7-rc.2 起宿主把 ContextMeter
     移进了输入卡下方的 data-composer-dock 行，官方 DOM 里 trailing 车道已经没有
     aria-haspopup="dialog" 触发器，所以这三条对官方布局是 no-op。但它们服务的是
     第三方模型座位插件（tests/third-party-model-seat.test.ts 明确钉住「座位规则不得
     碰官方 pill」）；该插件不在本机，删掉等于拿一个活跃的集成去赌，风险不对等。
     若将来确认第三方座位也不再渲染 dialog 触发器，再把这三条一起删。 */
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
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
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
    /* （2026-11-06 审计 C6）background / transition 两条已删：下一层同选择器用
       「background: transparent !important」把这层永久钉成透明，写了也永远不生效。 */
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
  body[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]),
  html[data-ds-dark-theme] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
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
  body[data-ds-dark-theme][data-dsh-bg] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
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
  /* 输入框字号（owner 2026-11-05 真机：「输入框的字很大」，收到 14px）：
     宿主给粗指针设备的下限是 @media (pointer:coarse) 的
     .uV2eYG_card{font-size:max(16px, var(--dsh-content-font-size,14px))}，
     那是给 iOS 聚焦放大留的保险；Android 没有这个限制，13–14px 才是宿主
     自己的字号轴默认值。这里把卡片字号拉回字号轴（默认 14px，用户在
     「字号大小」里调大仍然跟随），编辑器与镜像层都继承它。
     iOS 不受影响：misc.css 的 html[data-mobile-nav-ios] 那条直接写在
     [contenteditable] / textarea 上（16px !important），元素自身声明
     永远压过继承值。 */
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
  /* --- Session header on mobile（宿主 ≥0.2.2-rc.1，单套规则）---
     0.2.2 DOM（dsh-client-ui-conversation 的 ConversationHeader + 会话头槽）：
       header.wSkVaW_header（grid: auto minmax(0,1fr)）
         > div.headerLeading[data-conversation-header-leading]（grid-area 1/1，内含 conversation.header.leading 槽）
         > div[data-slot="conversation.session.header"]（display:contents）
             > div.titleRow（grid-column 2）> titleCluster（crumbs + headerActions）、headerUtilities、headerCorner
             > div.tabs（grid-column 1/-1）> div.viewTabs[role="tablist"][data-conversation-tabs] + viewsMoreAnchor
     插件的 toggle / files 由 conversation.session.header.actions 槽渲染进 headerActions，再绝对
     定位到 header 两角（header 是包含块）。标题的住所在 titleRow 自己身上，读宿主的
     --dsh-frame-leading-clearance（见下方 titleRow 条），不靠座位格的宽度。
     锚：会话顶栏一律写 header:has([class*="_headerLeading"])。0.2.2 起它已不是代际门，而是
     「这是会话顶栏、不是提问卡 Mbwy4a_header」的结构锚（pitfalls「提问卡顶栏误伤」）；唯一不带
     这个锚的是「header:has(> *)」行高地板——它同时管着提问卡的 header，改锚会改提问卡几何。
     三代（0.1.5 / 0.1.6-alpha.2 / 0.2.0-rc）叠加规则于 2026-11-05 合并为本块；删除清单与宿主
     证据见 docs/handover/2026-11-05-3.4.0-css-header-worker.md。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    /* 标题让位改读宿主变量：宿主 titleRow 自带
       padding-inline-start: max(0px, calc(var(--dsh-frame-leading-clearance,0px) - 20px))，
       frame[data-sidebar-drawer] 时宿主把变量设 84px（html 无 data-platform 时 48px），按宿主自己
       「header 左内边距 20px」的几何推算。本插件 header 左内边距是 8px、开关 28px 坐 left:8，
       标题行需要 22px（标题字左缘约 x=43，开关右缘 36）⇒ 变量取 42px。写在 header 上而不是
       frame 上：只有本顶栏这一个消费方会读到，frame 下其它读该变量的面不受影响。 */
    --dsh-frame-leading-clearance: 42px;
    /* 宿主 header 自带 padding: 10px 28px 0 20px 与 min-height:76px（内容只有 ~69px，
       贴底定位的状态 chip 会被底部空白顶下去）。手机上贴住内容高度、两侧 8px。 */
    padding-top: 0 !important;
    padding-left: 8px !important;
    padding-right: 8px !important;
    min-height: 0 !important;
    /* 头部弹层与绝对定位的 chip / 角落按钮都以 header 为包含块。 */
    position: relative !important;
    /* 不透明且压在消息流之上，两者缺一不可：宿主顶栏没有底色，而消息流是后续兄弟，
       会画到顶栏上面（2026-10-07 三张 headless 截图：透明→标题行透出内容；不透明但不抬层
       →与透明逐字节相同；不透明+抬层→与原样逐像素一致）。滚动盒从下面穿过是因为上方
       「selection-drag autoscroll ramp」把它上提了 --mobile-nav-header-h。
       层序（0.2.2 实测值）：消息流横幅 1、composer 座位 9（.wSkVaW_composerSeat）、
       本顶栏 10、全屏右侧栏面板 11、宿主 AppFrame 的弹层通道 overlayLayer 20、
       本插件遮罩/弹层 55+、抽屉/菜单 1000+。顶栏只须高过 composer 座位（9），
       不能高过右侧栏面板——面板是 top:0 的整张 sheet，顶栏一压上去，面板自己的
       标签条（文件 × + 与分屏/退出全屏/面板三个按钮）就被不透明顶栏盖住
       （真机 2026-11-05：打开文件面板后标签条整条不见，退出文件夹才随重排露出）。 */
    background: var(--dsw-alias-bg-layer-1, #fff) !important;
    z-index: 10 !important;
  }
  /* hero 相位的空头部：0.2.2 的 headerBlank 只去边框与最小高、不隐藏，空头部仍占一条；
     手机 hero 的抽屉入口是 FAB，这一条整个藏掉（宿主 display 不带 !important，本表最后加载）。 */
  [data-mobile-nav="frame"] [data-phase] header[class*="headerBlank"] {
    display: none;
  }
  /* 座位格（headerLeading）：手机档这一格是插件自己的抽屉开关（绝对定位 left:8），
     宿主 conversation.header.leading 槽里的控件必然与它叠在一起，按槽名显式藏掉；
     座位格本身塌成 0 宽。padding 归零同时压掉第三方兼容层误标进来的预留
     （@linxin666/dsh-web-all 按 0.1.5 结构注入 padding-inline-end:44px）。 */
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
  /* 标题行记座位（pitfalls「header 座位归属」）：几何补偿挂在会被布局约束的 titleRow 上，
     座位格被谁塌掉都不影响标题落点。值 = 宿主同一公式，变量由上面 header 条给出（42 ⇒ 22px）；
     兜底也写 42px，变量缺席时像素不变。!important 防第三方 padding 简写把它清零。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {
    box-sizing: border-box !important;
    padding-left: max(0px, calc(var(--dsh-frame-leading-clearance, 42px) - 20px)) !important;
  }
  /* 右侧两格在手机上都让给插件的 files 按钮：headerCorner 是「右侧栏展开」入口
     （手机上右侧栏就是 Files 面板，与 files 按钮重复，且 margin-right:-16px 会从按钮右侧漏一角），
     headerUtilities 只装会话日志导出等（抽屉品牌行已有同一入口）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] > [class*="_headerCorner"],
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerUtilities"] {
    display: none !important;
  }
  /* 两角按钮：toggle 左上、files 右上，同一个包含块（header），随 frame 的安全区一起移动。
     files 在流里到不了右上角（宿主给空 utilities 座位留了尾巴），绝对定位同时把它的流宽还给
     标题道。纵向不写死 top（2026-11-05 自适应）：top:auto 让两颗按钮停在静态位置——它们是
     headerActions（align-items:center）的弹性子项，静态位置就是动作行的纵向居中线，而动作行
     与标题同在 titleCluster 里居中，所以无论字号缩放、WebView textZoom 还是行高怎么变，
     按钮都与标题同心（旧的 top:6 / 2 / 5 是 14px 字号下的魔数，真机放大字体后图标偏上）。 */
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
  /* 标题簇：标题 + 动作行一条 flex。右侧 46px 预留是承重值（pitfalls「header 拥挤」：files 是
     right:8 的 36px 盒，预留不足时尾部 chip 会被按钮压住、点 chip 尾巴打开文件面板），且记在
     margin 上——簇是 overflow:auto 的滚动容器，裁剪线在 padding box，padding 预留挡不住溢出。
     簇内间隙 4px（手机档 6px，见下）。overflow-x:auto 是极端字体下的溢出守卫，放得下时惰性；
     手机档另由 html body 条改回 visible（1f8089c）。
     0.2.2 的簇还带 mask-image 右缘渐隐（wSkVaW_titleCluster）：遮罩按簇的边框盒绘制，会把
     座位在簇外的绝对定位后代一起抹掉——真机 2026-11-05：左上开关只剩右缘一条「]」、右上文件
     按钮整个不见、计数 chip 被切。overflow:visible 管不到遮罩，这里直接撤掉；标题截断由
     crumbCurrent 的省略号负责。 */
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
  /* 把子代理 chip 与 header 之间的祖先钉成 static：绝对定位的 chip 以最近的已定位祖先为包含块，
     别的插件给 headerActions 加 position:relative 就会挪走它（lineage-chip-pinned 的配套）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_headerActions"] {
    position: static !important;
  }
  /* 第三方兼容层（@linxin666/dsh-web-all）把簇里的按钮注入 min-width/min-height:44px，
     toggle/files/chip 被顶成 44、三心不齐；这里把外来下限归零。两个状态 chip 用 :not 豁免：
     它们的 21px 高由下方 ZKlsPq 专条供给，不豁免会被本条（更高特异性）顺手压掉。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] :is(button, [role="button"]):not([class*="QsffPG_trigger"]):not([class*="ZKlsPq_root"] button) {
    min-width: 0 !important;
    min-height: 0 !important;
  }
  /* 面包屑条吃掉动作行之外的剩余宽度；72px 地板保底约 4 个字，防长预设名把标题挤没。 */
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
  /* 标题本体：共用档（= 平板档）是 100px 横滑窗（pan-x 让浏览器先认领横滑，左缘抽屉手势不抢）；
     手机档改成省略号窗口，见下方手机档作用域。 */
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
  /* 子代理会话里父会话那一段同样是 <button>，不设窗口就会顶出去。 */
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
  /* 动作行（断点 A，2026-09-19 用户拍板，全移动档无条件）：不参与收缩，chips 按自然宽渲染，
     收缩职责全数交给 crumbs 的窗口当避震器——内容是字体相对的、预算是固定像素的，按比例收缩时
     行内唯一无下限的模式 label 会被截成「创造…」（真机 vivo V2425A 360/dpr 3.5 读数证伪了按
     视口分档的首版）。chip 之间 4px（手机档 6px）。 */
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
  /* 模式 label（智能体预设 SVAs4q_label）：手机上它是唯一的模式切换入口，图标与文字都保住；
     38vw 从 320px 起保住整名，宽屏上仍先于标题省略。 */
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
  /* 计数类状态 chip（子代理计数 / Agent Team）保住状态文字与命中区：钉住 + 封顶，标题省略、
     计数保字。真实谱系根是 class="ZKlsPq_root "（尾随空格，宿主模板字面量所致），[class$=] 永不
     命中，只能子串匹配，并排除 switcher 根让它自己的标题能省略（pitfalls「header 拥挤」）。 */
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
  /* Agent Team chip（VoX2oq_root，data-team-action）：被上面的 pin 钉成不可缩时会把模式 chip
     压成「创造…」。让它先让：可缩 + 28px 下限（宿主 @container(width<=480px) 已藏掉标签，
     只剩 14px 图标 + 7px 内边距，与 toggle/files 同尺寸）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
    flex: 0 1 auto !important;
    min-width: 28px !important;
  }
  /* View tabs（#41）：页签多于两颗时宿主的可缩按钮会塌成一字一行；改成横滚条——每个标签整字
     （flex-shrink:0 + nowrap）、pan-x 只让这条横滑、contain 防甩出、snap 对齐（pitfalls「tab strip」）。
     满宽 + border-box 让 8px 右内边距收在宽度之内（content-box 时右缘越出 header 8px）。 */
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
  /* 行高地板（2026-09-14 / 09-23）：宿主手机版 rows 被空座位与 tab 地板顶成 44/44。
     标题行地板 36（文字中心要与 top:6 的圆形按钮对齐），页签行 32（手机档 26、横屏矮带 24）。
     :has(> *) 不带会话顶栏锚：它同时命中提问卡的 header，保持原状。 */
  [data-mobile-nav="frame"] [data-phase] header:has(> *) {
    min-height: 0 !important;
    grid-template-rows: minmax(36px, auto) minmax(32px, auto) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tab"] {
    min-height: 32px !important;
  }
  /* 子代理谱系 chip（dsh-client-ui-subagent 的 ZKlsPq_root；计数变体在 headerActions、switcher
     变体在 crumbs 的 lineage 槽）：两个变体都绝对定位到页签行右缘（header 为包含块）。盒子镜像
     页签按钮的盒模型（21px 高、5px 下内边距），文字与「对话/轨迹」同基线。 */
  /* @patch
     id: header-chip-seat
     for: 宿主 0.2.2 的 headerActions / titleCluster 槽位（子智能体谱系 chip 的座位）
     why: 槽位树由宿主 React 管理，本插件不能插入自有节点，只能靠 CSS 改排位
     until: 上游在标题行提供稳定的 actions/title 槽标记，或把该 chip 分到独立容器
     test: tests/a2-header-gating.test.ts
  */
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
  /* 页签 ≥3 颗时谱系 chip 停靠右缘（两种渲染：tab 是 tablist 直接子按钮 / 被容器包裹）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] button:nth-of-type(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] > button:nth-child(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    left: auto !important;
    right: 8px !important;
    margin: 0 !important;
  }
  /* 头部里任何滚动条在手机上都不要（真机：标题下面多一条灰色滑条）；本机 WebView 不认
     scrollbar-width，只有 ::-webkit-scrollbar 生效，两条并用。滑动能力保留。 */
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
  /* switcher 变体（双类 ZKlsPq_root ZKlsPq_switcherRoot）：根帽 min(46vw,180px) + overflow 收口 +
     trigger max-width:100%，让宿主自带的标题省略号链在根内收口（宿主 trigger 上限 244px 大于根帽）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] {
    max-width: min(46vw, 180px) !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] > button {
    max-width: 100% !important;
    min-width: 0 !important;
  }
  /* 落点钉死（2026-10-12 用户要求「位置定死，不被其他插件移动」）：四边偏移写全、transform /
     margin 归零、z 固定；包含块由上面 headerActions 的 static 锁在 header。top:48 是平板档
     （标题行 40 + 页签行 36）的页签中心；手机档改贴 header 底边，见手机档作用域。 */
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
  /* @patch
     id: lineage-chip-pinned
     for: 宿主 ZKlsPq 子智能体 chip 的绝对定位落点（含包含块）
     why: 别的插件只要给任一祖先加 position:relative，包含块被抢、chip 就会挪位；四边偏移加 transform 是唯一能钉死的手段
     until: 宿主为该 chip 提供非绝对定位的稳定槽（届时四边偏移与祖先 static 都可删）
     test: tests/a2-header-gating.test.ts
  */
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
  /* @patch
     id: lineage-chip-views-more-yield
     for: 页签行右缘的「更多视图」⋯（宿主 ViewsMoreMenu 的 anchor，[class*=viewsMoreAnchor]）与谱系 chip 的同一落点
     why: chip 按 lineage-chip-pinned 钉在 right:8px，⋯ 也在 right:8px —— 390px 实测 chip 盒 282–382、⋯ 盒 360–382（22×19 完全重合），chip 的 z-index:3 又压在上面，于是 ⋯ 点不到、chip 右端也被自己的省略号窗吃掉（#281）。只能由 CSS 让位：⋯ 的宽度与边距都宿主所有，我们不能改
     until: 宿主把 ⋯ 移出页签行，或给谱系 chip 一个不与之共享的稳定槽——届时删本块
     test: tests/a2-header-gating.test.ts
  */
  /* 让位量 = 宿主右边距 8 + ⋯ 宽 22 + 间距 4。⋯ 是第三方 conversation.view 的唯一入口，
     不许靠藏它来避开（pitfalls「更多视图入口」）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"]):has([class*="viewsMoreAnchor"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    right: 34px !important;
  }
  /* 谱系 chip 里的文字（子代理标题 /「N 个子代理」）给一个规矩的省略号窗口。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] span {
    display: block !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  /* ---------- 手机档专属（≤767px + coarse）----------
     数值照 360×754 真机量的；768–1023 的平板档保持上游手机 UI 的排布，不套这台手机的魔数。
     同选择器、书写在后 ⇒ 覆盖上面的共用档。html body / html 前缀是为了压过 DSHA App 注入表
     （它把行高、页签内边距钉成固定值，见 pitfalls「header 行高与弹层」），不是风格。 */
  @media (max-width: 767px) and (pointer: coarse) {
    /* 「标题↔对话」间距的唯一有效旋钮是 header 的 row-gap：两行网格回到内容高（App 注入表
       原本钉死 40px + 36px），row-gap 3.5px 是店主定稿值（2026-10-20）。 */
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
      grid-template-rows: auto auto !important;
      row-gap: 3.5px !important;
    }
    /* 标题行回到内容高（auto + min-height:0，不写死 36：将来出现更高的东西也不会被裁）。 */
    html [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {
      height: auto !important;
      min-height: 0 !important;
    }
    /* 标题簇：右侧预留 46 → 34（2026-10-12/13「46→40→36→32→26→34」串的终值，与 files 座位
       36→30、右移 4px 配对），簇内间隙 4 → 6。 */
    /* 标题行按内容高（2026-11-05 真机量：标题字上方空 14px，其中约 6px 是簇 min-height:40 与
       动作行 min-height:36 两道地板垫出来的）：手机档清零两道地板，行高回到行内最高控件（面包屑 /
       chip 触发器的 28px），标题与页签整体上移。两角按钮随行居中（共用档 top:auto），不用跟着改数。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
      margin-right: 34px !important;
      gap: 0 6px !important;
      min-height: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_headerActions"] {
      min-height: 0 !important;
    }
    /* 0.2.2（1f8089c）：toggle/files 落在 titleCluster 里的 headerActions，而簇是 overflow:auto
       的横滚容器——以 header 为包含块的两颗绝对定位按钮仍被它裁掉（真机：左侧 ☰ 只剩右缘一条，
       右侧文件夹整个不见）。簇本身不再裁剪，溢出守卫交给 crumbs 的窗口。 */
    html body [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
      overflow: visible !important;
    }
    /* 标题本体：可扩展 + 省略号（2026-10-12 用户规格）；横滑认领随之撤掉。pan-y pinch-zoom 与
       全库 touch-action 同形（tests/ios-zoom-guard.test.ts 要求非 pan-x 值含 pinch-zoom）。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
      max-width: 100% !important;
      overflow-x: hidden !important;
      text-overflow: ellipsis !important;
      touch-action: pan-y pinch-zoom !important;
    }
    /* 动作行 chip 之间 4 → 6（2026-10-12 用户要求）。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
      gap: 6px !important;
    }
    /* files：座位 36 → 30、右缘 8 → 4（2026-10-12 用户规格）；纵向随动作行居中（见共用档）。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
      right: 4px !important;
      width: 30px !important;
      height: 30px !important;
      flex: 0 0 30px !important;
    }
    /* 预设 chip 的不可见胶囊 + 长名字防护（2026-10-12 用户规格）。 */
    /* @patch
       id: preset-invisible-capsule
       for: 宿主 SVAs4q_label 智能体预设 chip 的外包裹层
       why: 该 chip 的图标是 position:absolute 贴左缘，给本体加内边距会让文字与图标叠上；只能加一层绝对定位外壳
       until: 宿主给预设 chip 自身加上左右内边距（本外壳与 margin 一并删）
       test: tests/a2-header-gating.test.ts
    */
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
    /* 团队 chip：左内边距 7 → 2（预设↔团队间距 11 → 4），图标在「标准模式」与 files 之间
       绘制层右移 3px 居中（宿主 .VoX2oq_root 本来就是 position:relative，布局不变）。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] > button,
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="VoX2oq_trigger"] {
      padding-left: 2px !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
      left: 3px !important;
    }
    /* 后台任务 chip（dsh-client-ui-jobs）：只留状态圆点 + 折叠箭头，排在团队 chip 右边。锚用
       「trigger 直接挂 _count」的形状——0.2.1 起包裹 div 无类名；槽位是 display:contents，
       故不写直接子组合符。 */
    /* @patch
       id: header-job-chip-collapse
       for: dsh-client-ui-jobs 的后台任务 chip（0.2.1 起包裹 div 无类名）
       why: chip 由宿主作为 header.actions 槽的贡献节点渲染，类名与结构归宿主所有，
            插件只能在样式层收窄可见内容与序位
       until: 宿主给该 chip 稳定的数据标记，或在手机档自带折叠形态
       test: tests/a2-header-gating.test.ts
    */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] div:has(> button[class*="_trigger"] > [class*="_count"]) {
      order: 9 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] button[class*="_trigger"] > [class*="_count"] {
      display: none !important;
    }
    /* 谱系 chip 贴 header 底边（chip 21px 盒、下内边距 5px 与页签按钮同盒模型，页签行怎么压都
       不错位；top 必须显式 auto，否则共用档的 48px 继续执政）。两个选择器与共用档两条同特异性，
       靠书写在后取胜。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
      top: auto !important;
      bottom: -2px !important;
    }
    /* 页签行：容器（div.tabs，外面套着 display:contents 的槽包装，必须写后代）去掉宿主 margin-top
       10px、左缩进对齐 toggle；tablist 按内容撑高、取消上提、不裁剪（1f8089c：「21px 固定行高 +
       -8px 上提」把页签文字顶出 header 底边、第一颗页签被裁成只剩「话」）；按钮 6 / 3 内边距
       贴近分隔线（2026-10-20 店主定稿）。0.2.2 的 wSkVaW_tab 用 --dsh-tab-hit-inline（10px）
       同时算左右负外边距、内边距与下划线内缩；本档把内边距清成 0 后负外边距还在，两颗页签各
       吃进 20px、16px 间隙被抵成 -4px（真机 2026-11-05「对话轨迹」叠字），所以变量一并归零。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_tabs"] {
      padding-left: 8px !important;
      margin-top: 0 !important;
      margin-bottom: 0 !important;
    }
    /* 页签行恒单行：宿主在窄屏按宽度折页签（visibleViewLimit），放不下的第三方 conversation.view
       只出现在「更多视图」⋯ 菜单里（misc 的手机档隐藏规则 2026-11-07 已删）——一旦换行（宿主
       tabs 是 wrap 容器，tablist 满宽 + ⋯ 22px 必然超宽），⋯ 掉到第二行（行高 16 → 51）。
       两处都关换行；tablist 保持满宽吃掉剩余（收成内容宽会让 ⋯ 悬在页签右侧、右边空一大片，
       真机 2026-11-07 报「位置非常奇怪」），⋯ 因此落在页签行右缘，与桌面同一位置。
       **不许给 tablist 加 overflow 裁剪**：宿主 tab 靠 margin-top:-10px 把热区撑出 16px 高的
       tablist 盒（按钮 23..49 / 文字 30..46 / tablist 33..49），任何非 visible 的 overflow 都会
       把标签顶部裁掉（真机 2026-11-07 实测截字）。 */
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
  /* 横屏短高度（旋转后的手机 / 横置的小平板，2026-10-29 812×375 实测 header 87 → 80）：只按形状
     收紧页签行地板 32 → 24，不按宽度分档；须写在手机档之后，740 宽横屏两档同时命中时取 24。 */
  @media (max-height: 480px) and (orientation: landscape) {
    [data-mobile-nav="frame"] [data-phase] header:has(> *) {
      grid-template-rows: minmax(36px, auto) minmax(24px, auto) !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tab"] {
      min-height: 24px !important;
    }
  }
  /* 320px 级屏幕的最后手段：标题与两颗状态 chip 放不下模式文字，模式 chip 整颗让出。 */
  @media (max-width: 359px) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      display: none !important;
    }
  }
  /* ---------- 键盘压矮的横屏：composer 座位必须压在顶栏之上（issue #245）----------
     报障（owner，2026-10-25）：「当输入时屏幕变为横屏时，输入框可能会错位甚至
     消失,目前只能进行刷新」。机理不是某个视口变量写错，是**两块盒子的几何打架**：
       · 宿主座位「_composerSeat」是纯 CSS 的 position:sticky; bottom:0 —— 0.2.1-rc.3
         的对话包里它只出现在样式表、类名表与 JSX，没有任何 JS 读它的 rect，也没有
         100dvh，所以座位的顶边永远 = 可见带底 − 座位高；
       · 上面那条顶栏规则把顶栏抬到消息流之上（z-index:30 + 不透明底），而
         scrollBody 的负 margin 抬升又让座位的盒区一直延伸到 y=0，于是座位与顶栏
         在没有足够高度时必然重叠。
     竖屏键盘弹起仍有 400+px，座位够不到顶栏；横屏（本机 792×360）键盘不收，布局
     视口只剩 ~110-160px，而顶栏 67-77px + 座位 70-102px 已经超过可见带 —— 座位的
     上半截（就是 Lexical 编辑行）落进顶栏的盒子里，被不透明顶栏整块盖住：用户看到
     「输入框消失、只剩发送行」；只重叠一部分时就是「错位」。sticky 滚不开，刷新会
     收起键盘，所以看起来「只能刷新」。
     标记由 effects/composer-clearance.ts 按实测几何开关（不用媒体查询：顶栏高、
     座位高、可见带三个量都随设备、输入法与草稿长度变），这里只做两件事：
     ① 让座位压过顶栏（31 > 30）——用户正在打字的表面绝不许被盖住；
     ② 横屏矮带里座位本来就放不下，先让掉次要行（dock 行：上下文圈/统计胶囊），
        把高度还给编辑行，顺带把顶栏的标题行与抽屉入口露出来。
     dock 锚稳定的 data-composer-dock（0.2.1 起的 InputBar 行标记；0.1.x/0.2.0 的 data-composer-stats
     在 0.2.2 宿主包里已无输出，那一臂于 2026-11-05 随低代际清理删除）。 */
  /* @patch
     id: composer-seat-above-header
     for: dsh-client-ui-conversation 0.2.1 的 composer 座位（[class*="_composerSeat"]，position:sticky; bottom:0）+ 本插件顶栏规则（header.wSkVaW_header 的 z-index:30 与不透明底）
     why: 宿主没把「座位 vs 顶栏」的层叠关系暴露成槽位或变量，而插件又必须让顶栏盖住消息流（否则消息从标题行底下透出），键盘压矮的横屏里只能由插件给座位让位；触发条件是实测几何，CSS 媒体查询写不出设备无关的阈值，故层叠覆盖只在本效果置标记时生效
     until: 宿主把 composer 做成独立浮层（座位自带高于顶栏的层叠层）或提供座位/顶栏之间的层叠契约 —— 届时整块删除
     test: tests/composer-clearance.test.ts
  */
  [data-mobile-nav="frame"][data-mobile-nav-composer-tight] [class*="_composerSeat"] {
    z-index: 31 !important;
  }
  /* 只在横屏让出次要行。竖屏里座位本来就放得下，让掉它只会让 composer 变矮 30px——
     2026-10-30 真机逐帧（402x684 竖屏）：tight=T 时 seatH=76、tight=- 时 seatH=106，
     42 帧对 65 帧，正好是统计行那一行的高度；店主的「输入框缩下去」就是这个。
     z-index 那条不受影响：竖屏里它只负责让座位压过顶栏。 */
  @media (orientation: landscape) {
    [data-mobile-nav="frame"][data-mobile-nav-composer-tight] [class*="_composerSeat"] [data-composer-dock] {
      display: none !important;
    }
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
  /* 2026-11-03 统计行收拾（店主截图二轮）：图标与组间距保持原样；环未被折进输入行时
     （stats-line 没认出它，环仍是 [data-composer-dock] 里 ContextMeter 那个兄弟，进度圆带
     stroke-dasharray）——只留环、隐掉「21%」，贴行尾右缘（去掉宿主 8px 内边距），与统计
     之间只留 4px，腾出的宽度给统计条（缓存命中不再被截成「缓…」）。锚稳定属性，不依赖
     stats 标记；已折叠的环带 data-mobile-nav，本条不碰。 */
  /* 三轮（店主截图：两组之间 ~26px，组内「·」只有 ~4px，环 ~12px，疏密不一）：
     组间距原是 stats gap 6 + 锚 margin 3 + 两侧药丸内边距 6+6 叠出来的，这里把药丸
     朝向组间的那侧内边距与锚 margin 清零，组间只由 stats 的 gap 决定（12px）；环与
     统计之间由 dock gap 决定（8px）。首组左内边距保留，行首对齐不变。 */
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
  /* 四轮（店主截图：行首留白 ~11px、行尾环外 ~7px，左宽右窄）：首组药丸左内边距
     6px 也清零，两侧都只剩容器自身的边距。 */
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
  /* 命中区（店主：触控范围大一点点）：画出来的环不变，透明 ::after 外扩成 26×38；
     横向 6px 小于 dock gap 8px，不会盖到「缓存命中」那颗药丸。 */
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) button::after {
    content: "";
    position: absolute;
    inset: -8px -6px;
  }
  [data-mobile-nav="frame"] [data-phase] [data-composer-dock] > :not([data-mobile-nav]):not(:has([data-composer-stat])):has(circle[stroke-dasharray]) button > span {
    display: none !important;
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
     export dialog gets.

     The gate is the host's own contract first, shape second (2026-10-30,
     owner report 「手机上经常不显示输入框」): a pure shape guess used to
     claim ANY aria-modal whose last child holds a button, and a third-party
     consent dialog matched it exactly — measured at 412px, its mask was
     forced to position:absolute / width:100vw-16 at z-index 10100 (a
     full-screen cover) and its panel to flex-direction:row, rendering the
     notice as six 30-130px columns of 7-glyph lines. The dialog then cannot
     be dismissed, so the composer underneath stays invisible for the whole
     session. Fix: gate on 「data-shortcut-modal="settings"」 — the attribute
     the host itself stamps on SettingsRoot (verified in
     dsh-client-ui-settings-general 0.2.0-rc.2 / 0.2.1-rc.2) — and keep one
     shape arm only for the 0.1.7 line, which does not tag its panel. That
     arm requires TWO adjacent buttons in the panel's last child (the
     settings nav list is a row of tab buttons), which a consent dialog's
     single confirm button never satisfies. No third-party class name is
     named anywhere in this block. */
  /* 卡片形态只给平板档（768–1023）：手机档（≤767）回到宿主官方全屏，见下方「手机档设置全屏」。 */
  @media (min-width: 768px) {
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) {
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
  }
  /* --- 手机档设置全屏（≤767 + coarse，2026-11-05 owner 真机反馈「像另一个界面、不好看」）---
     宿主 dsh-client-ui-settings-general（css$7）在 (width<=767px) 下自带全屏形态：overlay
     position:absolute + height:100%、panel width/height 100% + radius 0、navList 横滚。上面的
     内缩卡片（left 8 / radius 14 / height auto + 灰遮罩露出下半屏）已门控到 min-width:768，
     手机档不再改形态，这里只补宿主没有的修补：
     ① max-width 撤顶：上方「导出弹层」那条 calc(100vw - 32px) 封顶与宿主全屏规则同特异性
        (0,3,1)、书写在后，会把全屏面板右侧收窄 32px；
     ② 顶部安全区：overlay 从 y=0 起铺，刘海 / 状态栏下的标题与 ✕ 需要让位（border-box，
        不撑高 100%）；
     ③ 关闭键热区：与平板档共用，见下方「打开配置文件」隐藏条之后的热区规则；
     ④ 页签条细滚动条：宿主把横滚条的滚动条整个藏掉（display:none），手机上没有「还能往右滑」
        的提示；恢复成 2px 细条（与平板档同一套颜色）。
     配置文件按钮（settings.action 槽）在整个移动档隐藏，见下方同名规则。
     入场动画：手机档不加。全屏面板上 translateY(14px) 会在底边露出一条底层内容、读起来像
     整页跳动；淡入在 #124 里被证实会把还开着的抽屉叠印出来。宿主官方形态本身也无动画。
     选择器只锚宿主自己的 data-shortcut-modal="settings"，不动上面那条 :is() 的形状。 */
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
    [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]),
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
  @media (min-width: 768px) {
    /* Nav bar: hide the "Settings" caption (redundant on a full-width sheet)
       and wrap the tab list so every tab is visible — a horizontal scroll cut
       the last tab ("Plugins") off with no affordance to scroll. */
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
  /* Hairline scrollbar for the tab strip: the default WebKit scrollbar
     reads fat on a phone; 2px keeps the scroll affordance without the
     bulk. (Portal-aware copies of the frame-scoped rules in compat.css,
     which died with the rc.2 portal move.) */
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
  /* Cells stay whole inside the scroller: no shrink, no wrap, compact
     metrics. (Portal-aware copies of the frame-scoped rules in compat.css,
     which died with the rc.2 portal move.) */
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
  /* 设置弹层的「打开配置文件」（dsh-client-ui-settings-general 的 SettingsDocumentAction，
     id open-document，经 settings.action 槽渲染进标题行 VOzbGW_header > VOzbGW_actions，
     与关闭 ✕ 同排、在其左侧）在整个移动档隐藏（owner 2026-11-05）：手机上打开的是一个
     宿主本机文件，几乎用不上，却占掉标题行约 94px。锚宿主自己的槽包装
     div[data-slot="settings.action"]（renderer SlotOutlet，display:contents），并限定在
     设置弹层 [data-shortcut-modal="settings"] 内；槽里目前只有这一个贡献者。✕ 是 _actions 的
     兄弟、不在槽里，出口不受影响。桌面档不在本移动块内，保持原样。
     旧 0.1.x 的工具栏五条（锚「面板末子里的 _header」：钉右上角、圆底 ✕、热区、藏
     _actions）已删：0.2.2 的面板末子是 VOzbGW_options，那条链 0 命中。 */
  [aria-modal="true"][data-shortcut-modal="settings"] [data-slot="settings.action"] {
    display: none !important;
  }
  /* 关闭键热区（手机与平板同一条）：宿主 ✕ 是 28px，::after 四向外扩 8px 到 44px。配置文件
     按钮隐藏后 ✕ 是标题行唯一的控件，左侧无邻居；右侧落在面板内边距里（手机 header
     padding-right 16、平板 14，外扩 8 不出面板），下方是导航页签条的上内边距（手机 12px，
     平板 nav 在左列、与 header 不相邻），不压任何可点控件。 */
  [aria-modal="true"][data-shortcut-modal="settings"] > [class*="_header"] > button:last-child {
    position: relative;
  }
  [aria-modal="true"][data-shortcut-modal="settings"] > [class*="_header"] > button:last-child::after {
    content: "";
    position: absolute;
    inset: -8px;
    border-radius: 50%;
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
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child {
    flex: 1 1 auto;
    min-height: 0;
  }
  [aria-modal="true"]:is([data-shortcut-modal="settings"], :has(> :first-child > :last-child > button + button)):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]) > :last-child > :last-child {
    padding: 0 12px 24px;
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
  /* 手机档收掉搜索行（报障人拍板 2026-09-25：「加回来又闪了，不要这个了，手机上也不怎么用」）。
     因果已由报障人两次实机复现钉死：**行在 → 打开就闪；行藏 → 不闪**。机理：宿主 Modal 会把
     焦点抢到 [data-modal-autofocus]（就是这个搜索框），键盘在弹层打开那一瞬就抬起来，布局
     视口随之 754→471，整页重排 —— 就是「全屏闪」。收掉这个唯一的文本输入，弹层里就再也
     弹不出键盘，那一步不存在；而不是靠 focus 影子去拦（那条守卫在这台引擎上并不总是拦得住）。
     只做 CSS 隐藏，**绝不删宿主节点**：宿主是 React 渲染的，删掉它卸载时 parent.removeChild
     会抛 NotFoundError，被 SlotErrorBoundary 吞掉后整个 slot 变空白（见 pitfalls「搬宿主
     React 节点」）。想恢复搜索只需删掉这两行，但要接受打开瞬间那一下全屏闪。 */
  /* 手机档（窄屏）才收；768–1023 的平板档与桌面档照旧保留搜索
     （报障人 2026-09-25 拍板：「手机端不要了，平板电脑端照旧」）。 */
  @media (max-width: 767px) {
    [aria-modal="true"][data-shortcut-modal="shortcuts"] [class*="_searchRow"] {
      display: none !important;
    }
  }
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
     (panel-exit.ts ghostEnd). dsh-web-mobile-fade also runs on the dialogs,
     which are frame descendants too — reusing it would end the ghost early.
     (The backdrop carried it until 2026-11-08; the dialogs alone still block
     the reuse.) Comment corrected 2026-10-13 (was claiming the whole exit
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
  /* ===== 提问卡（ask_user_question）：横滑收敛 / 正文列宽 / 全屏回答 =====
     2026-10-25 owner 真机三报障（360×792 竖屏、792×360 横屏，DSHA WebView
     0.2.1-rc.3）：① 卡片正文能左右滑（截图里文字被横向滚过、左侧被截断）；
     ② 长问题 + 键盘弹起后答案框看不见字（「收起键盘才看到」），内容上下猛跳；
     ③ 滚卡片掉帧。本节只做 ① 与 ② 的几何；③ 的逐条归因见
     docs/maintenance/pitfalls.md §提问卡全屏。

     A. 横滑收敛。宿主 Mbwy4a_body 只写了 overflow-y:auto，另一轴被 CSS 计算成
        auto —— 任何比卡宽的子节点都能把它撑成横向滚动容器；而宿主只给
        Mbwy4a_field > * 加了 overflow-wrap:anywhere，_title / _description /
        _detail / _optionLabel / _reviewNote 五族都没有换行兜底（长 token 与长英文
        单词直接溢出）。手机档把横轴钉成 hidden，并给这五族补换行兜底；optionLabel
        与 description 是 _optionLine（flex 行）的子项，min-width:0 让它们真的能
        收缩到内容宽。刻意不用 overflow-x:clip：本代 WebView 上它与 overflow-y:auto
        组合按规范会退化成 hidden，同一结果却多一条要引擎理解的规则。

     B. 正文列宽。实测卡宽 ≈293 / 屏 360：_frame 左右各 clearance(16) + 16 = 32，
        正文列 = 卡宽 − _header 左右内边距：宿主 ≤720 档是 18/12 ⇒ ≈263，桌面档
        24/16 ⇒ ≈253（owner 报的 253 是后一档口径；同一段文字两次读数差 10px，这里
        按更窄的那个记）。≈70%，owner 看到的就是右边那片留白。手机档把 _frame 多吃的
        那 16px 还回去——对齐 composer 卡自己的列：宿主 uV2eYG_root 的左右 padding
        就是 --dsh-composer-side-clearance 本身，两张卡同一列；composer 卡同样带
        elevation 阴影，说明阴影不需要额外侧留白。_header 左右各收到 12/8（宿主
        ≤720 档是 18/12），与标题行的 12px 落点同一条线。
        数值取舍：侧留白绝不再压过 clearance —— 那是宿主给顶栏两个角按钮（left:8
        起、28px 宽）与抽屉/文件面板留的列，压过去正文列线就与顶栏 chip 错开；
        12/8 与 owner 已经验收过的标题行缩进同源，不再自造第二套边距。合计正文列
        253–263 → 308（+45~55px，≈17~22%）。只对 ≤767 真手机档生效，768–1023 平板保持宿主
        排布（与本文件「头部留白收紧」块同一约定）。

     C. 全屏回答（effects/question-fullscreen.ts 打 data-mobile-nav-question-full）。
        几何全部来自插件已维护的两个变量：可见带 --dsha-mobile-visible-vh（键盘会
        缩）与顶栏底 --mobile-nav-header-h；唯一的实测项是卡片下面那一截（frame 的
        上下 padding + 座位里 composer 自己的行），由效果量出来写成
        --dsha-mobile-question-reserve。卡片用 height + max-height 吃掉整条可见带，
        body（宿主 flex:auto;min-height:0;overflow-y:auto）滚动、footer（跳过/提交）
        恒在卡底。缺任一变量 ⇒ --dsha-mobile-question-band 变成 guaranteed-invalid
        ⇒ 两条声明各自回落 auto / 宿主自己的 min(60vh,520px)（fail-open，画面回到
        宿主默认）。刻意不用 --dsh-web-mobile-vh：那是「键盘不缩」的稳定高度，给
        弹层定高用的；全屏回答必须跟着键盘让位，否则下半截还是被盖住。
        与 issue #245 的紧凑态共存：那条只把座位抬到 z-index 31（压顶栏 30）并收起
        dock 行，两节都挂在同一个 frame 上、都只读自己的标记，可以同时为真。
        _cardMinimized 让路：宿主的收起键仍然能把卡折回一行。 */
  /* @patch
     id: ask-question-card-phone
     for: dsh-client-ui-user-questions 的 QuestionComposer.module.css（哈希前缀 Mbwy4a_：_frame / _card / _body / _header / _headerActions / _title 等）与其宿主 composerSeat
     why: 宿主卡片把 overflow-x 计算成 auto 且只给自定义回答框补了换行；60vh 定高在 adjustResize 的 WebView 上随软键盘缩，回答框因此被滚出 body 可视区。插件不能在宿主包里改这两件事，只能在自己的手机档样式表里收横轴、补换行、按实测可见带重算高度
     until: 宿主把 Mbwy4a_body 的 overflow-x 钉成 hidden/clip 并给五族文本补换行、或卡片自带「展开」形态与键盘感知定高 —— 届时整节删除
     test: tests/question-fullscreen.test.ts
  */
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
  /* B 的 header 锚必须排掉 _headerActions：class 属性里 "Mbwy4a_header" 是
     "Mbwy4a_headerActions" 的前缀子串（pitfalls §哈希子串：前缀重叠片段用 :not
     防误伤），少了这个 :not 就会把动作行的 padding 一起改成 10px 8px 0 12px。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [class*="Mbwy4a_frame"] {
      padding-left: var(--dsh-composer-side-clearance, 16px) !important;
      padding-right: var(--dsh-composer-side-clearance, 16px) !important;
    }
    [data-mobile-nav="frame"] [class*="Mbwy4a_header"]:not([class*="Mbwy4a_headerActions"]) {
      position: relative;
      padding: 10px 8px 0 12px !important;
    }
    /* 2026-10-25 owner 真机反馈「你提的问题右边有一大片留白，真的舒服吗」：
       宿主把动作行（收起/关闭 + 我们注入的展开）当普通 flex 项摆在同一行，
       「_headingBlock」 只有 min-width:0、没有 flex-grow —— 三颗 24px 图标 + 12px
       gap（我们扩过 gap）合计 ≈96px 的**整块**列被从正文里永久扣掉：问题正文
       只有卡宽 − 96 − 16gap ≈ 230px（≈ 屏宽的 64%），而且每一行都短、右侧永远
       空一截。图标只在卡头那一行有用，正文没理由陪着让位。
       改法：动作行脱离 flex 流（绝对定位到卡头右上），headingBlock 吃满整行；
       首行之下不再被图标占用。代价是 eyebrow 必须自己让开图标列（短标题，
       margin-right 给足 116px 即可，长了它会换行而不是压到正文）。
       40px 顶部：8 行高 + 图标 24px + 两次 4px 呼吸，标题第一行不会钻到图标
       底下（实测图标底边 30，标题顶边 34）。
       间距 2026-10-25 二批由 6 改 20：44px 热区要求三颗图标两两中心距 ≥44px，
       否则外扩的命中盒会彼此叠住、抢同一笔（见下一条）。可见图标仍是 24px，
       整排从 84px 变 112px；动作行 top 6 → 10 让 44px 热区整块落在卡片内。 */
    [data-mobile-nav="frame"] [class*="Mbwy4a_headerActions"] {
      position: absolute;
      /* 10 而不是 6：宿主 _card 是 overflow:hidden，热区顶边要落在卡片内
         （6 − 10 = −4 会被裁掉 4px，44 只剩 40）。10 − 10 = 0，整 44 都在卡内；
         图标可见位置随之下移 4px（底边 34），仍不超过正文顶边。 */
      top: 10px;
      right: 6px;
      gap: 20px !important;
      z-index: 1;
    }
    /* 三颗图标的 44px 热区（2026-10-25 二批，owner 拍板「拉到 44」）：
       宿主只给 _iconButton 24×24 的盒子，本插件注入的展开键同样 24×24；
       24 + 20 间距 = 44，据此把每颗的透明 ::after 外扩 10px：44×44，相邻热区
       刚好相接、不重叠（再大就互相抢笔，见上一条 gap）。::after 只管命中，
       可见尺寸 / 圆底 / 行高一律不动（与 035dd21 的 44px 热区同一手法）。
       纵向会伸进正文首行右侧约 13px（热区 y 6~50、正文顶边 33）：正文不可点，
       可接受；换来的是 44px 的最小命中。 */
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
      /* 112（三颗 24 + 两个 20 间距）+ 6 右距 − 8 卡头右内边距 + 4 呼吸 ≈ 116：
         图标列整体右移后，eyebrow（短标题）不能钻到里面。 */
      margin-right: 116px;
    }
    /* 正文可读性（owner 两轮口径）：第一轮「太挤」→ 16/24；第二轮「字体缩小一点，
       但不要太缩小」→ 15/23。取宿主手机档正文族的字号（15px），只把行距从 21 抬到
       23 —— 字号不动、行距放宽，是同一密度带里最保守的一档，不再自创刻度。
       首行 padding-top 2：图标底边 30，标题顶边 = 10 + 16 + 5 + 2 = 33，仍不重叠。
       Markdown 段落（_detail 走 MarkdownText）补 8px 段距，末段不留尾隙。 */
    [data-mobile-nav="frame"] [class*="Mbwy4a_title"] {
      padding-top: 2px;
      font-size: 15px;
      line-height: 23px;
      /* owner 第三轮（2026-10-25）：问题正文本身也要封顶 —— 超长问题现在会把卡头
         撑到占满整张卡（body 被压成一条缝、底部按钮被顶出可视区）。宿主把 question
         正文渲染成 header 里的 h2、header 是 flex-shrink:0，所以正文多长卡头就多高。
         这里让正文自己变成和下面选项区同族的滚动容器：超过 min(34vh,232px) 就在
         标题内部滚动（键盘态 vh 会缩，232 是竖屏 360×792 下 34vh≈269 的封顶值，
         保证 body 至少还剩半屏可用）。2026-10-25 第三轮再次收窄：owner 实测
         「问题区还是太长，再缩 1/3」→ 34vh/232px 的三分之二 ≈ 23vh/155px
         （约 6~7 行正文），问题区与选项区高度观感终于同级。
         overscroll-behavior:contain 防止滑到底后
         把会话一起带走；touch-action 按全库约定写 pan-y pinch-zoom。
         2026-11-03 owner 第四轮：封顶撤掉——全屏了问题区仍被压在 155px 里滚，
         两个滚动区观感割裂。改为「整张卡一个滚动区」（见下方 card-single-scroll）：
         问题正文按自然高度排，问题与选项一起滚，卡高仍由宿主 min(60vh,520px)
         或全屏带决定。 */
    }
    /* @patch
       id: question-card-single-scroll
       for: dsh-client-ui-user-questions 的 QuestionComposer（Mbwy4a_card > header + body + footer；
            宿主 card overflow:hidden、header flex-shrink:0、body 是唯一滚动区）
       why: 宿主让问题正文（header 里的 h2）不参与滚动，长问题会把 body 压成一条缝；
            插件不能改它的 DOM，只能把滚动区从 body 换成整张卡
       until: 宿主让 header 参与滚动或自己给长问题限高
       test: tests/question-fullscreen.test.ts
    */
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
    /* body 不再自己滚：按内容自然高度排，滚动交给卡片。两轴都必须是 visible ——
       上面 A 节给 body 钉的 overflow-x:hidden 会让它仍是一个滚动容器（另一轴被算成
       auto），加上宿主的 overscroll-behavior:contain，手指落在选项区时滑动停在 body
       里、传不到卡片（owner 实测「问题区能滑、回答区动不了」）。横向裁切挪到卡片上。 */
    [data-mobile-nav="frame"] [class*="Mbwy4a_card"]:not([class*="Mbwy4a_cardMinimized"]) > [class*="Mbwy4a_body"] {
      flex: 0 0 auto !important;
      overflow: visible !important;
      overscroll-behavior: auto !important;
    }
    /* 提交 / 跳过按钮钉在卡片底：卡片 padding-bottom 10px，sticky 用 -10px 贴到底边，
       底色取卡片同色并向下补 10px，滚动内容不会从按钮下面透出来。 */
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
    /* 排版微调（owner 2026-10-25）：问题区与选项区之间加一条分隔线——两段内容
       同色同字重，靠留白分不开，读起来像一段连续文字。用 0.5px 光学细线
       （本库弹层/卡片的既有刻度，见 b-end 的那套 0.5px 微边框约定）。颜色取宿主
       描边色阶的第二档 --dsw-alias-border-l2：l1 在真机上「几乎看不见」，l2 是宿主
       自己给浮层描边用的那一档（elevation-stroke-color 也是它），「看得见但不显眼」；
       深浅仍随主题走，不自造色值。
       线画在 header 的 border box 上 = 卡片整宽，与卡片的圆角裁剪路径一致；
       padding-bottom 2 → 8 给线上下各留呼吸。 */
    /* 线画在伪元素上而不是 border：owner 三轮口径（l1「几乎看不见」→ l2「太深了」）
       说明要的是两档之间。同一个 token 加 opacity 比再找一档色值可控，也仍然跟随
       主题（深浅两侧都比 l2 淡同样的比例）；0.5px 高度 = 本库的微边框刻度。 */
    [data-mobile-nav="frame"] [class*="Mbwy4a_header"]:not([class*="Mbwy4a_headerActions"]) {
      position: relative;
      padding-bottom: 8px;
    }
    /* 2026-11-03 owner 第四轮：问题与选项改成同一个滚动区后，分隔线去掉
       （「问题区和回答区中间的细线去掉」）。header 的 padding-bottom 8 保留作留白。 */
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
  /* C：展开/收起按钮，与宿主那两颗 24px 图标键同盒。三颗统一 44×44 命中盒
     （::after ±10；间距 20 见上面手机档「三颗图标的 44px 热区」一条），
     可见图标仍是 24px。 */
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
  /* @patch
     id: zen-remote-header-yield
     for: dsh-zen-remote 2.2.5 的手机档 header reflow（headerActions 里注入 [data-mobile-nav="header-back"]，titleRow 改成 92px/1fr/92px 三栏，titleCluster 靠 display:contents 变成直系 grid 项）
     why: 本插件 0.2.x 块对 _titleCluster 的 display:flex !important 会打败 zen-remote 的 display:contents，集群退化成一个 92px 的 grid 项，再减 34px 预留只剩 58px，注入的 44px 返回键被 overflow-x:auto 裁掉；改 titleRow 列模板会把 zen-remote 的三栏整体压平、返回键落到右上，不能治根
     until: dsh-zen-remote 不再重排 header，或它改为不依赖 display:contents 的稳定槽位，届时删本块
     test: tests/a2-header-gating.test.ts
  */
  @media (max-width: 767px) and (pointer: coarse) {
    /* zen-remote 2.2.5 的手机档 home CSS 把本插件的 toggle 藏了，32px 的开关座位
       在这条路径上是死空间；归零后返回键落在 x≈8 的原生位，而不是 x≈40。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleRow"] {
      padding-left: 0 !important;
    }
    /* 让回 zen-remote 的三栏：集群不生成盒子，crumbs / headerActions 直接落进它的 grid。
       只在 header-back 在场时生效——没装 zen-remote 的页面仍走共用档的 flex 集群。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] {
      display: contents !important;
    }
    /* 共用档用 margin-left:auto + flex-end 把动作行贴右；在 zen-remote 的第 1 列里
       那会把返回键推离列首（实测 x≈118，落在 58px 滚动盒外），归零后它贴列左缘（x≈8）。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] > [class*="_headerActions"] {
      margin-left: 0 !important;
      justify-content: flex-start !important;
    }
    /* 共用档为了横滑窗保留 overflow:visible；zen-remote 的居中标题靠 hidden 裁边。 */
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([data-mobile-nav="header-back"]) [class*="_titleCluster"] > [class*="_crumbs"] {
      overflow: hidden !important;
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
`
