// misc — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: each section (composer / tablet / desktop) carries its own
// media query.

export const MISC_CSS = `@media (max-width: 1023px) and (pointer: coarse) {
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
      (e.g. the dialog is dismissed). On 0.2.2 the ask composer's answer
      field is a single .fieldInput (it replaced the older .customInput /
      .customTextarea pair) and inherits the card's 14px (ui-user-questions
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
  html[data-mobile-nav-ios] [data-question-key] [class*="Mbwy4a_fieldInput"] {
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
  [data-mobile-nav="frame"] > [class*="_sidebarCol"] [role="tree"] {
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

/* 宽屏触屏（≥1024，壳不生效）同样没有 hover：会话行的 ⋯ 必须常显。
   宿主只在 :hover / menuOpen 时显示 _rowActions（dsh-client-ui-workspace），
   而这一档没有移动壳的帧内规则兜底（手机档那条在 layout.css 的 frame 作用域里），
   于是触屏平板点不到重命名 / 分叉 / 归档 / 删除 —— 只按指针门挂上同一条可见性
   规则，不动行内布局（标题仍是 flex:1 + min-width:0 自己让位）。
   与 session-delete 三件套同一门控口径：粗指针在任何宽度都成立。 */
@media (min-width: 1024px) and (pointer: coarse) {
  [class*="sessionRow"] [class*="_rowActions"] {
    display: inline-flex !important;
  }

  /* ①′ 触屏平板的行内热区（2026-10-29 实测：会话行 32px 高、行距 34，
     行内操作按钮只有 16×16、彼此相隔 31px —— 手指点不中，也点不准）。
     做法：行抬到 44px（宿主没有行高约束，标题仍 flex:1 自己省略），
     按钮盒 40×40、间距 4px（盒间净空 0、不重叠），图标本身仍是 16px 不放大。
     40 而不是 44：三个按钮 + 净空在 256px 侧栏里已经占掉 128px，
     再宽就把标题挤到 100px 以下；44 需要侧栏加宽，是产品取舍，不在本轮。 */
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

  /* @patch
     id: wide-touch-session-shortcuts
     for: dsh-client-ui-workspace 的会话行 _rowActions 与归档 / 置顶快捷按钮
     why: 宽屏触屏常显三颗 40px 按钮挤压标题；沿用手机档 #154 的精确标签收进 ⋯ 菜单（#255）
     until: 宿主自带触屏快捷按钮收起规则，或会话行提供独立的操作区布局契约，届时删本块
     test: tests/dsha-session-interaction.test.ts
  */
  [class*="sessionRow"] [class*="_rowActions"] :is([aria-label="归档会话"], [aria-label="取消归档"], [aria-label="置顶会话"], [aria-label="取消置顶"], [aria-label="Archive session"], [aria-label="Unarchive session"], [aria-label="Pin session"], [aria-label="Unpin session"]) {
    display: none !important;
  }

  /* ① 隐藏「更多视图」⋯（owner 2026-10-29）：这一档页签全放得下，宿主折进菜单的只有
     插件市场的「浏览 / 创建」，对触屏平板无用；而平板没有壳的帧内规则，按钮会漏出来，
     所以只藏按钮与锚点。**手机档相反、不能藏**：宿主按宽度折页签，⋯ 是放不下的第三方
     conversation.view 的唯一入口（手机档那条隐藏规则 2026-11-07 已删，页签行单行排，
     见 layout.css 手机档）。 */
  header [class*="viewsMore"],
  header [class*="viewsMoreAnchor"] {
    display: none !important;
  }

  /* ② 设置弹层改成手机档的「顶边贴齐、整宽、内容高」sheet（owner 2026-10-29：
     「手机端和设置界面是分开的，平板还是原生，也改造成手机一样」）。
     原生的 800×800 居中模态在平板上离拇指太远；手机档的几何在 layout.css 的
     移动块里（同一条家族谓词），这里只按粗指针补同样的几何与横向页签导航，
     不动内容列。谓词与手机档完全一致：首孩子末孩子里有 button、且不是导航树 /
     目录选择器 / 快捷键弹层。 */
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
  /* ②″ 内容列可收缩（#259 ①，横屏设置拖不动、下半截被裁）：上面把 panel 改成纵向 flex 后，
     内容列这个 flex item 的 min-height 默认 auto，不会缩到 panel 的 max-height 以下，
     于是宿主内层 options（flex:1; min-height:0; overflow-y:auto）永远拿不到受限高度，
     超出部分被 panel 的 overflow:hidden 裁掉。与手机档（layout.css 设置 sheet 内容列）同形。
     max-height 仍用 100dvh：平板档不跑 syncStableViewportHeight，没有 --dsh-web-mobile-vh 可用；
     sheet 顶边贴齐，键盘弹起时 dvh 变矮只是让内容区多滚一截，不会把 sheet 推走。 */
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
  /* 手机档隐藏的是导航标题（整宽 sheet 上冗余）；横排后页签独占一行。 */
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
  /* ②′ 平板标题行布局（owner 2026-10-29 团队投票：其余功能居中）。
     宿主把标题、预设、Agent Team / 子智能体 / 后台任务都塞在 titleCluster 的
     headerActions 里，从左一个接一个排；平板宽屏上它们全挤在标题后面。
     这里把「预设」用 order 提到最前（留在标题旁），其余状态入口用一对对称的
     margin:auto 居中 —— 对称 margin 的好处是：后台任务 chip 出现/消失时，
     整组的**中心点不动**，只有两翼伸缩，不会像左对齐那样把标题区推来推去。
     只作用于 ≥1024 + 粗指针（平板原生布局档），≤1023 的壳与桌面都不命中。 */
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

  /* ③ 一键设置齿轮放回来（owner 2026-10-29：「手机上是分开的，平板还是原生」——
     手机档把设置从账户菜单里拆出来、给了独立齿轮，平板这一档协调器不跑，齿轮又被
     上面的桌面隐藏块一起藏掉。这里按粗指针放回，注入逻辑见 effects/tablet-quick-settings.ts；
     账户菜单里那份设置项的收起由 base.css 的 data-mobile-nav-quick-menu 规则负责，那条是全局的。 */
  [data-mobile-nav="quick-settings-wrap"],
  [data-mobile-nav="quick-settings"] {
    display: inline-flex !important;
  }

  /* ③′ 输入框回形针放回来（#259 ②）：触屏平板同样没有拖放与 hover，回形针是顺手的文件入口；
     弹层逻辑见 effects/composer-file-picker.ts（按 TOUCH_QUERY 武装）。手机档的盒子样式
     （28px 圆、-8px 左移、::after 外扩命中区）在 layout.css 的移动块里，平板不命中，这里补
     同尺寸的盒子，不带手机那条按宿主 modes 留白量出来的 -8px 左移（平板工具行是宿主原生排布）。
     宿主没有 hidden input 的旧代（rc）照手机档同样藏掉：点了是永久的空操作。 */
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

  /* （2026-11-06 审计 D4）这里原本是两条 0.1.x 的设置工具栏规则，锚在
     「面板末子 > _header」上。0.2.2 的 aria-modal 面板子节点顺序是
     navTitle → nav → header → options —— 末子是 options，这条链 0 命中
     （layout.css 的同批清理早已删掉同一串）。现行做法见 layout.css 的
     settings.action 隐藏与「关闭键热区」两条，手机与平板共用。 */
}

/* 抽屉打开时，「更多」菜单（宿主 PanelMoreMenu，align=start、宽 288）从锚点
   左缘起算会越过 min(88vw,280px) 的抽屉右缘。把菜单钉在抽屉内：左缘 8px、
   宽度跟着抽屉算，右侧留 8px。 */
@media (max-width: 1023px) and (pointer: coarse) {
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed])) [class*="moreMenu"] {
    left: 8px !important;
    right: auto !important;
    max-width: calc(min(88vw, 280px) - 16px) !important;
  }
}

/* 抽屉品牌行「下载」菜单：视觉与宿主 Menu 对齐（Menu.module.css / MenuSurface）：
   4px 内边距、radius-lg 表面、elevation-prominent 阴影、34px 行高、13px 字、
   6px 图标间距；行高与「更多」菜单完全一致。 */
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
  /* 与宿主 Menu.module.css 同款：prominent 阴影的第一段是 0.5px 描边环，
     颜色取自 --dsw-elevation-stroke-color；不声明它会落成黑色，就是用户看到的黑边。 */
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


/* 头部顶部留白：自适应（2026-11-05 店主定）。顶部只认外壳的
   padding-top: env(safe-area-inset-top)（layout.css 的 frame 规则）：有刘海/状态栏 inset 的
   全屏 WebView、PWA、iOS Safari 由系统给出真实 inset，非全屏容器 inset 为 0、header 贴视口顶。
   以前按一台机器量出来的 -8 / -12 / -16 负上移（及 max(-12px, -inset) 夹取）全部撤掉：
   它在 inset 为 0 的容器里是 0，在 inset 真实的设备上却会把标题拉进状态栏。 */

`
