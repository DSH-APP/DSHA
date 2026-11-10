// Reconciler task: phone conversation header height → CSS variable.
//
// 2026-10-12 P1 拆分第二片：由 effects/phone-chrome.ts 逐字搬出（纯搬运，不改行为）。
// 拆出的形状沿用本仓既有的 task 工厂模式（preview-fullscreen / drawer-layer /
// panel-exit 同型）：模块导出 create<X>Task()，组装处只 addReconcilerTask(工厂())。
import { getFrame } from '../platform/frame.ts'
import type { ReconcilerTask } from '../core/reconciler-core.ts'

/**
 * Height of the phone conversation header, published as a CSS variable so the
 * scroll body can extend up under it (layout.css.ts "selection-drag autoscroll
 * ramp": the distance from the list's top edge decides how fast Blink
 * autoscrolls a selection drag). Measured from the frame's top to the header's
 * bottom - exactly how far the scrollport's box has to grow upward - which also
 * stays correct when the header's own top is pushed down by safe-area padding.
 */
const HEADER_HEIGHT_VAR = '--mobile-nav-header-h'
const HEADER_SELECTOR = '[data-mobile-nav="frame"] [data-phase] header'

let observedHeader: Element | null = null
let headerObserver: ResizeObserver | null = null

/** 写回 <html>：比对后才写 —— 变量写回本身就是一次 attribute mutation，
 *  无条件写会一直把 reconciler 弄脏（幂等在这里是必须的，不是优化）。 */
function publish(next: string): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (root.style.getPropertyValue(HEADER_HEIGHT_VAR) !== next) {
    root.style.setProperty(HEADER_HEIGHT_VAR, next)
  }
}

/** 量一次 H = frame 顶边 → header 底边。刻意读两个 rect，而不是用 ResizeObserver
 *  回调里的 entry.contentRect：contentRect 只描述 header 自己的盒子，丢掉了
 *  frame.top 这一段，而 safe-area 把顶栏整体下推时 H 必须跟着长（见文件头）。 */
function measureHeaderHeight(): string {
  const frame = getFrame()
  const header = observedHeader
  if (header === null || frame === null || !(header instanceof HTMLElement)) return '0px'
  return `${Math.round(Math.max(0, header.getBoundingClientRect().bottom - frame.getBoundingClientRect().top))}px`
}

/** **几何路径**：只量、只发布，不读 `offsetHeight`。
 *
 *  由 ResizeObserver 回调驱动 —— 顶栏尺寸变了就是它在喊，不必再问一次「你的
 *  offsetHeight 是 0 吗」：不可见/已卸载的顶栏 rect 全零，`Math.max(0, …)` 已经把它
 *  夹到 0px，与旧版那道 offsetHeight 门控同一结果。 */
function publishGeometry(): void {
  publish(measureHeaderHeight())
}

/** **结构路径**：顶栏节点刚被换过（React 换子树 / 首次挂载）才走这里，除量/发布外还
 *  要挡住「换上一个没有盒子的顶栏」（display:none / 尚未布局）→ 回落 0px。 */
function publishStructure(): void {
  const header = observedHeader
  publish(header instanceof HTMLElement && header.offsetHeight === 0 ? '0px' : measureHeaderHeight())
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
function syncHeaderHeight(): void {
  if (typeof document === 'undefined') return
  const header = document.querySelector(HEADER_SELECTOR)
  if (header === observedHeader) {
    // 结构没变 ⇒ 不做 layout 工作。宿主没有 ResizeObserver 时几何路径永远不会被驱动，
    // 只有这一种情况才退化为每趟量一次几何（拿不到 RO 的宿主也得能更新）。
    if (header !== null && typeof ResizeObserver === 'undefined') publishGeometry()
    return
  }
  headerObserver?.disconnect()
  observedHeader = header
  if (header !== null && typeof ResizeObserver !== 'undefined') {
    headerObserver = headerObserver ?? new ResizeObserver(() => { publishGeometry() })
    headerObserver.observe(header)
  }
  publishStructure()
}

/** Reconciler task face for the header-height variable (see the doc comment above).
 *  与拆分前同形：name/scopes/ensure/dispose 四项一字未改，只是从调用处搬进工厂。
 *  ensure 走**结构路径**（顶栏没换就不做 layout 工作），几何路径由 ResizeObserver 驱动。 */
export function createHeaderMetricsTask(): ReconcilerTask {
  return {
    name: 'header-metrics',
    scopes: ['*'],
    ensure: () => {
      syncHeaderHeight()
    },
    dispose: () => {
      headerObserver?.disconnect()
      headerObserver = null
      observedHeader = null
      if (typeof document !== 'undefined') {
        document.documentElement.style.removeProperty(HEADER_HEIGHT_VAR)
      }
    },
  }
}
