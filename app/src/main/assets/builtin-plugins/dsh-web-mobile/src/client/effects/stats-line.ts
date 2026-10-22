import { TEXT_MUTATION_SCOPE, type ReconcilerTask } from '../core/reconciler-core.ts'

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
export function statsAnchorAlive(el: Element | null): boolean {
  if (el === null || !el.isConnected) return false
  if (el.closest('[data-phase]') === null) return false
  // The strip must stay inside the composer stack, but it need not be a
  // DIRECT child of it: 0.1.5 nests the status row (bOPqQW_root) under the
  // composer card wrapper (uV2eYG_root), so the marked element sits one level
  // deeper than on rc.2 hosts. Ancestry is still the right test — only the
  // marker's own subtree position changed, not its container relationship.
  return el.closest('[class*="_composerStack"]') !== null
}

// Which row in the composer stack is the TPS readout ("TPS 89.4 tok/s"), and
// whether it still needs our marker. Kept pure so the decision is testable
// without a DOM (the effect maps the real nodes onto this shape).
//
// It must NOT treat an already-marked row as "done". The marker is written on
// the very first fold, so skipping marked rows makes every later pass report
// "no readout" — which both drops the reading out of the pass key and stops the
// TPS side from re-folding at all: `maxWidth` then keeps the width measured
// before the reading (or the slot) changed. Found by the #194 review (three
// measured leaks: in-place text change, anchor resize, removed placeholder).
export type TpsCandidate = {
  textContent: string | null
  children: { length: number }
  parentElement: Element | null
  getAttribute(name: string): string | null
}
export type TpsPick = { index: number; mark: boolean }
/** 读数文本的形状，**只此一份**：候选按 trim 后的起点锚定（pickTpsReadout），
 *  预筛只问「栈内有没有这种形状」（无需锚定，见 resolveTpsForKey）。 */
const TPS_TEXT_AT_START = /^TPS\s+\d/
const TPS_TEXT_ANYWHERE = /TPS\s+\d/
export const pickTpsReadout = (candidates: readonly TpsCandidate[]): TpsPick => {
  for (const [index, el] of candidates.entries()) {
    if (!TPS_TEXT_AT_START.test((el.textContent ?? '').trim())) continue
    // The readout is a bare text node holder; a row with children is a wrapper.
    if (el.children.length > 0) continue
    // No parent means the row is mid-teardown — a later candidate may be the
    // live one (the scan this replaced used to `continue` here too).
    if (el.parentElement === null) continue
    return { index, mark: el.getAttribute('data-mobile-nav') !== 'stats-tps' }
  }
  return { index: -1, mark: false }
}

/** TPS 折叠的定位结果（原在 createStatsLineTask 内部；2026-10-13 提到模块级，
 *  因为快路径的纯核 resolveTpsForKey 也要用这个形状）。 */
export interface TpsLocated {
  readonly el: Element
  readonly reserve: Element
  readonly live: string
}

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
export function resolveTpsForKey(
  remembered: TpsLocated | null,
  rememberedText: string,
  stackText: () => string,
  locate: () => TpsLocated | null,
): TpsLocated | null {
  if (remembered !== null) return { el: remembered.el, reserve: remembered.reserve, live: rememberedText }
  if (!TPS_TEXT_ANYWHERE.test(stackText())) return null
  return locate()
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
export function tpsSlotsSettled(
  absentNow: boolean,
  absentBefore: boolean,
  tpsAlive: boolean,
  reserveAlive: boolean,
): boolean {
  return absentNow ? absentBefore : tpsAlive && reserveAlive
}

export function createStatsLineTask(): ReconcilerTask {
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
  const ensurePositioned = (el: Element, marker: string): void => {
    if (getComputedStyle(el).position === 'static') el.setAttribute('data-mobile-nav', marker)
  }
  const positionedAncestor = (el: Element): Element | null => {
    for (let node = el.parentElement; node !== null; node = node.parentElement) {
      if (getComputedStyle(node).position !== 'static') return node
    }
    return null
  }
  // An overlay whose geometry has been measured but not yet written. The split
  // is what lets a pass read every rectangle it needs before it writes any
  // style: interleaving reads and writes is what forced a synchronous layout
  // after each write (measured 3 per pass, issue #194).
  type Measured = { host: Element; left: number; top: number }
  // The TPS fold after its read phase: the readout element, its measured
  // overlay (null when no positioned ancestor exists), and the placeholder
  // width the readout's max-width has to mirror.
  type TpsFold = { el: Element; measured: Measured | null; width: number }
  // The TPS fold after its locate phase: the readout, its placeholder, and the
  // reading text as it stands right now (the text is part of the pass key, so a
  // changed number always forces a re-fold). The shape itself is lifted to
  // module scope (`TpsLocated`) because the fast path's pure core needs it too.
  // The ring fold: its readout, the placeholder that reserves its slot, and the
  // measured overlay (null when no positioned ancestor exists).
  type RingFold = { ring: Element; reserve: Element; measured: Measured | null }
  const measureOverlay = (host: Element, reserve: Element): Measured | null => {
    const container = positionedAncestor(host)
    if (container === null) return null
    const box = reserve.getBoundingClientRect()
    const base = container.getBoundingClientRect()
    // Center the host on its slot vertically, not top-align it. Measured
    // 2026-09-29 (issue #140 acceptance): the 20px ring top-aligned on its
    // 16px reserve hung its center at y=793 while the neighbouring keys sit
    // at 789-791 — reported as「不与其他小UI对齐」. Centering is a no-op for
    // same-height overlays (the 0.1.5/0.1.6 TPS text) and aligns the ring
    // with the cluster. hostRect is read BEFORE the style write below; its
    // height does not depend on top/left, so the math is stable across flushes.
    const hostRect = host.getBoundingClientRect()
    return {
      host,
      left: box.left - base.left - container.clientLeft,
      top: box.top - base.top - container.clientTop - (hostRect.height - box.height) / 2,
    }
  }
  const writeOverlay = (measured: Measured): void => {
    const styled = measured.host as HTMLElement
    if (styled.style.left !== `${measured.left}px`) styled.style.left = `${measured.left}px`
    if (styled.style.top !== `${measured.top}px`) styled.style.top = `${measured.top}px`
  }

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
  const locateTps = (stats: Element): TpsLocated | null => {
    const stack = stats.closest('[class*="_composerStack"]')
    if (stack === null) return null
    let reserve = stats.querySelector(':scope > [data-mobile-nav="stats-tps-reserve"]')
    const candidates = [...stack.querySelectorAll('div')]
    const picked = pickTpsReadout(candidates)
    if (picked.index < 0) return null
    const el = candidates[picked.index]
    if (el === undefined) return null
    if (reserve === null) {
      reserve = document.createElement('span')
      reserve.setAttribute('data-mobile-nav', 'stats-tps-reserve')
      reserve.setAttribute('aria-hidden', 'true')
      stats.appendChild(reserve)
    }
    const live = el.textContent ?? ''
    if (reserve.textContent !== live) reserve.textContent = live
    if (picked.mark) el.setAttribute('data-mobile-nav', 'stats-tps')
    const tpsRow = el.parentElement
    if (tpsRow === null) return null
    ensurePositioned(tpsRow, 'stats-tps-row')
    return { el, reserve, live }
  }
  // Measure half — geometry reads only, no style writes, and no queries either:
  // it works on whatever locateTps already resolved.
  const measureTps = (located: TpsLocated): TpsFold => {
    const { el, reserve } = located
    const measured = measureOverlay(el, reserve)
    // The strip's last child is the flex shrink group: mirror whatever width
    // the placeholder settled on so the overlay clips with the same ellipsis
    // instead of overlapping the neighbouring group. Measured inside the SAME
    // read phase as the overlay boxes above — this read used to run after the
    // left/top write and forced a second synchronous layout.
    return { el, measured, width: reserve.getBoundingClientRect().width }
  }
  // Write half — touches styles only.
  const writeTps = (fold: TpsFold): void => {
    if (fold.measured !== null) writeOverlay(fold.measured)
    const styled = fold.el as HTMLElement
    const maxWidth = `${fold.width}px`
    if (styled.style.maxWidth !== maxWidth) styled.style.maxWidth = maxWidth
  }
  // 2026-09-23（店主最终确认）：**环要、百分比数字不要** —— 环显示在输入框行
  // 的右簇（模型/麦克风旁），CSS 用 font-size:0 只留环、隐掉 "45%" 文本；统计条
  // 拿满整宽。与 moveTps 同款 overlay：环留在 React 渲染的 dock 原位，插件自建
  // 占位 span 顶住右簇槽位（16px 环 + 2px 边距，行 gap 补足余量）。
  // 环读数的选取判据：dock 里第一个「不含统计条 + 文本带百分比」的子节点。
  // 单独提出来是要让快路径的身份校验与 measureRing 用**同一条**判据 —— 两处漂移就会漏掉
  // 被 React 换掉的那个环（见 ringIdentitySettled）。
  const isRingCandidate = (child: Element, stats: Element): boolean =>
    !child.contains(stats) && /\d\s*%/.test(child.textContent ?? '')
  // 环坐在统计条**所在的同一行**（dock）里，但中间隔着几层包装并不固定：0.1.7–0.2.0 的
  // 统计条是 bOPqQW_root，外面还套着 slot 包装层（固定两级上溯刚好落到 dock）；0.2.1 把
  // 统计条拆成两个 slot 条目、没有共同 _root，标记落在 slot 包装本身，固定两级就越过 dock
  // 指到 composer 根，环永远找不到（实测 0.2.1-rc.1：dock 里 ring 明明在，measureRing 返回 null）。
  // 所以按「谁的 children 里有环候选」向上找，包装层几层都不影响。
  // 必须封顶在 composerStack：不封顶会一路扫到 body，而会话正文里带「50%」的块正好是
  // isRingCandidate 的形状（不含 stats、文本有百分比），宿主结构再变一次就会把消息区
  // 当成环、把 composer 根当成 dock 去叠绝对定位。
  const locateRing = (stats: Element): { dock: Element; ring: Element } | null => {
    const stack = stats.closest('[class*="_composerStack"]')
    for (let node = stats.parentElement; node !== null; node = node.parentElement) {
      const ring = [...node.children].find((child) => isRingCandidate(child, stats))
      if (ring !== undefined) return { dock: node, ring }
      if (node === stack) break
    }
    return null
  }
  const measureRing = (stats: Element): RingFold | null => {
    const located = locateRing(stats)
    if (located === null) return null
    const { dock, ring } = located
    const row = document.querySelector('[data-composer-card] [class*="_row"] [class*="_trailing"]')
    if (row === null) return null
    let reserve = row.querySelector(':scope > [data-mobile-nav="stats-ring-reserve"]')
    const primary = row.querySelector(':scope > [class*="_primary"]')
    if (reserve === null) {
      reserve = document.createElement('span')
      reserve.setAttribute('data-mobile-nav', 'stats-ring-reserve')
      row.insertBefore(reserve, primary)
    } else if (
      primary === null ? row.lastElementChild !== reserve : reserve.nextElementSibling !== primary
    ) {
      // React rebuilt the row and shuffled its children around our
      // placeholder: put the reserved slot back at the anchor position.
      row.insertBefore(reserve, primary)
    }
    if (ring.getAttribute('data-mobile-nav') !== 'stats-ring') {
      ring.setAttribute('data-mobile-nav', 'stats-ring')
    }
    ensurePositioned(dock, 'stats-ring-dock')
    return { ring, reserve, measured: measureOverlay(ring, reserve) }
  }
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
  const moveTps = (stats: Element): void => {
    const located = locateTps(stats)
    if (located !== null) writeTps(measureTps(located))
  }
  const moveRing = (stats: Element): void => {
    const fold = measureRing(stats)
    if (fold !== null && fold.measured !== null) writeOverlay(fold.measured)
  }
  let viewportHandler: (() => void) | null = null
  // The keyboard animation drives visualViewport.resize once per frame, and the
  // two folds below read rects and write styles (forced synchronous layout), so
  // one event per frame meant one overlay pass per frame. Coalesce every event
  // in a frame into a single pass, and skip the pass entirely when the anchor
  // box did not move. Measured 2026-10-09 on Android 16 WebView: opening the
  // drawer went from a 58ms long task to none. A React rebuild that re-creates
  // the TPS readout at the same box is still re-folded by mark()'s own fast
  // path, which runs on every tree mutation (scopes: the tree key).
  let relayoutRaf = 0
  let relayoutKey = ''
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
  const ringReserveBoxKey = (reserve: Element | null): string => {
    if (reserve === null) return 'none'
    const box = reserve.getBoundingClientRect()
    return `${Math.round(box.left)}:${Math.round(box.top)}:${Math.round(box.width)}`
  }
  const relayoutNow = (): void => {
    const anchor = document.querySelector('[data-mobile-nav="stats"]')
    if (anchor === null) return
    const rect = anchor.getBoundingClientRect()
    // The resize path may run before any mark() fold, so it resolves the ring
    // reserve by query here; only the fast path is bound by the no-query rule.
    const key = `${Math.round(rect.left)}:${Math.round(rect.top)}:${Math.round(rect.width)}:${Math.round(rect.height)}|${ringReserveBoxKey(document.querySelector('[data-mobile-nav="stats-ring-reserve"]'))}`
    if (key === relayoutKey) return
    relayoutKey = key
    // Same read-before-write ordering as mark()'s fast path.
    const located = locateTps(anchor)
    const tps = located === null ? null : measureTps(located)
    const ring = measureRing(anchor)
    if (tps !== null) writeTps(tps)
    if (ring !== null && ring.measured !== null) writeOverlay(ring.measured)
  }
  const relayout = (): void => {
    if (relayoutRaf !== 0) return
    relayoutRaf = requestAnimationFrame(() => {
      relayoutRaf = 0
      relayoutNow()
    })
  }
  // Candidate C state: the key the last completed fold was keyed on, and the
  // elements it folded. See the fast path in mark() below.
  let fastKey = ''
  let foldedTps: Element | null = null
  let foldedTpsReserve: Element | null = null
  let foldedRing: Element | null = null
  let foldedRingReserve: Element | null = null
  const stillFolded = (el: Element | null, marker: string): boolean =>
    el !== null && el.isConnected && el.getAttribute('data-mobile-nav') === marker
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
  const ringIdentitySettled = (stats: Element): boolean => {
    const located = locateRing(stats)
    // 认不出 dock（宿主结构变了）：宁可不 skip。
    if (located === null) return false
    return located.ring === foldedRing
  }
  const mark = (): void => {
    // Keyboard open/close and viewport rotations relayout the composer without
    // any DOM mutation, so the overlays need their own re-layout channel.
    if (viewportHandler === null) {
      viewportHandler = relayout
      window.addEventListener('resize', relayout)
      window.visualViewport?.addEventListener('resize', relayout)
    }
    // Fast path: the marked strip usually survives React rebuilds between
    // tokens; re-verifying the anchor is O(1) while the full-tree hunt below
    // grows with the conversation. moveTps still re-runs so a rebuilt TPS
    // readout is re-folded.
    const anchor = document.querySelector('[data-mobile-nav="stats"]')
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
      const rect = anchor.getBoundingClientRect()
      const box = `${Math.round(rect.left)}:${Math.round(rect.top)}:${Math.round(rect.width)}:${Math.round(rect.height)}`
      // 2026-10-13（本效果仅存的每帧查询）：TPS 那一段改成三级短路解析 —— 记得住的读数
      // 节点还活着就零查询读它的文本；否则先用栈内文本做零查询预筛；只有预筛命中才做
      // locateTps 里那条 `stack.querySelectorAll('div')`（详见 resolveTpsForKey）。
      const tpsAlive = stillFolded(foldedTps, 'stats-tps')
      const tpsReserveAlive = stillFolded(foldedTpsReserve, 'stats-tps-reserve')
      const rememberedTps = tpsAlive && tpsReserveAlive && foldedTps !== null && foldedTpsReserve !== null
        ? { el: foldedTps, reserve: foldedTpsReserve, live: '' }
        : null
      const tpsStack = anchor.closest('[class*="_composerStack"]')
      const located = resolveTpsForKey(
        rememberedTps,
        foldedTps?.textContent ?? '',
        () => tpsStack?.textContent ?? '',
        () => locateTps(anchor),
      )
      const key = `${box}|${located === null ? 'none' : located.live}|${ringReserveBoxKey(foldedRingReserve)}`
      // 2026-10-13：TPS 两个槽的 liveness 门改成「与 key 里那一段一致」—— key 本趟已经
      // **现读**了「在不在 + 读数文本」，所以「本趟缺席且上一趟也缺席」是一个稳定态、
      // 不该再 veto（旧版这两个 stillFolded 在缺席时恒 false ⇒ 永不 skip、每帧白折一遍）。
      // 判据本身抽成纯函数 tpsSlotsSettled，好让这条修复被测试直接钉住。
      // 环形那两个槽没有 presence 位（环缺席时 key 里没有它的盒子），保持原样：缺席时
      // 宁可不 skip，也不能漏掉「环新出现」的那一趟。
      const tpsSettled = tpsSlotsSettled(
        located === null,
        foldedTps === null && foldedTpsReserve === null,
        stillFolded(foldedTps, 'stats-tps'),
        stillFolded(foldedTpsReserve, 'stats-tps-reserve'),
      )
      if (
        key === fastKey &&
        tpsSettled &&
        stillFolded(foldedRing, 'stats-ring') &&
        // 身份校验放在最后：它只在前四条都过（= 真要跳过了）时才需要跑，而它要扫一遍 dock
        // 的 children。零查询，但不该在流式那种「key 每趟都变」的路上白跑。
        ringIdentitySettled(anchor) &&
        stillFolded(foldedRingReserve, 'stats-ring-reserve')
      ) {
        return
      }
      fastKey = key
      // 真折这一趟：合成路径（el 就是上一趟折过的那个节点）按老路径再解析一次，把镜像文本 /
      // 标记 / 占位定位这些副作用做到位 —— 这一趟的开销与旧版完全相同（旧版每趟都解析）；
      // 预筛真查到的那种直接复用，不重复查询。
      const foldTps = located !== null && located.el === foldedTps ? locateTps(anchor) : located
      const tps = foldTps === null ? null : measureTps(foldTps)
      const ring = measureRing(anchor)
      if (tps !== null) writeTps(tps)
      if (ring !== null && ring.measured !== null) writeOverlay(ring.measured)
      foldedTps = foldTps === null ? null : foldTps.el
      foldedTpsReserve = foldTps === null ? null : foldTps.reserve
      foldedRing = ring === null ? null : ring.ring
      foldedRingReserve = ring === null ? null : ring.reserve
      // Re-key with the reserve we just folded: the key above was built before the
      // fold existed, so it could only say 'none' on the first pass — leaving the
      // next (unchanged) pass to re-fold once for nothing. Re-keying here keeps the
      // 「unchanged ⇒ skip」 invariant from the very first pass on.
      fastKey = `${box}|${located === null ? 'none' : located.live}|${ringReserveBoxKey(foldedRingReserve)}`
      return
    }
    // Stale marker on a node that left the composer stack/phase context:
    // drop it so the slow path can re-anchor cleanly.
    anchor?.removeAttribute('data-mobile-nav')
    // Scope decision: the status row is a DESCENDANT of the composer stack,
    // not necessarily its child. On rc.2 hosts it is a direct child (its own
    // `_root`); on 0.1.5 the composer card wrapper (uV2eYG_root) sits between
    // the stack and the row (bOPqQW_root), so requiring a direct child made
    // the hunt permanently miss and the strip was never marked (measured: row
    // present at 16,814 carrying "8 turns 582 steps · 103 tok/s" while
    // [data-mobile-nav="stats"] was absent). Body blocks outside the stack are
    // still skipped by the containment test below.
    const stack = document.querySelector('[class*="_composerStack"]')
    if (stack === null) return
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
        if (root.matches('[data-testid="todo-panel"]')) continue
        // Dock panels are skipped by never marking a candidate whose buttons are
        // actionable controls. 0.1.5 renders the status row ITSELF as two
        // popover buttons (bOPqQW_pill, aria-haspopup="dialog"), so an
        // "any button" guard excluded the one row this task exists to mark
        // (measured: bOPqQW_root rejected solely by hasButton, marker count 0).
        // Every popover button counts as a status widget: the composer's real
        // controls (model bar, context meter) carry no metrics text and are
        // filtered by the text test above, and a panel with an actionable button
        // still fails here.
        const buttons = root.querySelectorAll('button')
        if (buttons.length > 0 && ![...buttons].every((button) => button.getAttribute('aria-haspopup') !== null)) continue
        const text = root.textContent ?? ''
        if (!/(turns|steps|\bLLM\b|轮|步)/.test(text)) continue
        // Composer card must never be mistaken for the status strip; exclude
        // its input region across both composer DOMs (textarea / Lexical
        // contentEditable marked data-composer-input).
        if (root.querySelector('textarea, [data-composer-input]') !== null) continue
        root.setAttribute('data-mobile-nav', 'stats')
        moveTps(root)
        moveRing(root)
        return
      }
    }
  }
  // Scope decision: TPS readout updates are text mutations in the composer
  // stack, in BOTH shapes -- React rebuilding the readout node (childList, the
  // tree key) and React rewriting the text in place (characterData, the text
  // key exported as TEXT_MUTATION_SCOPE). Both are declared: dropping either
  // stops the readout from being re-folded. A subtree observer would need one
  // per container, so the shared full-tree observer keeps its expensive scan.
  return {
    name: 'stats-line',
    scopes: ['*', TEXT_MUTATION_SCOPE],
    ensure: mark,
    dispose: () => {
      // Hand the official layout back: drop every marker (the strip loses its
      // one-line layout, ring/TPS overlays return to static flow) and remove
      // the plugin-owned placeholders.
      if (relayoutRaf !== 0) {
        cancelAnimationFrame(relayoutRaf)
        relayoutRaf = 0
      }
      relayoutKey = ''
      if (viewportHandler !== null) {
        window.removeEventListener('resize', viewportHandler)
        window.visualViewport?.removeEventListener('resize', viewportHandler)
        viewportHandler = null
      }
      for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring"], [data-mobile-nav="stats-tps"]')) {
        const styled = el as HTMLElement
        styled.style.left = ''
        styled.style.top = ''
        styled.style.maxWidth = ''
      }
      for (const key of ['stats', 'stats-ring', 'stats-ring-dock', 'stats-tps', 'stats-tps-row']) {
        for (const el of document.querySelectorAll(`[data-mobile-nav="${key}"]`)) {
          el.removeAttribute('data-mobile-nav')
        }
      }
      for (const el of document.querySelectorAll('[data-mobile-nav="stats-ring-reserve"], [data-mobile-nav="stats-tps-reserve"]')) {
        el.remove()
      }
    },
  }
}
