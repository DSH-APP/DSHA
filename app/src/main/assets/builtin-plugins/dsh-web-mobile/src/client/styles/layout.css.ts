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
`
