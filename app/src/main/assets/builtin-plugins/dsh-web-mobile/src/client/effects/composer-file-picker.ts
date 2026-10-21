import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { currentSessionIdOf } from '../core/sessions-compat.ts'
import { NS } from '../i18n/locales.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'

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
const TRIGGER_SELECTOR = '[data-mobile-nav="file-upload"]'
/** composer 卡片：宿主 hidden input 在其中。 */
const CARD_SELECTOR = '[data-composer-card]'
/** 宿主真正接收文件的 input —— 插件不另起 intake。 */
const HOST_INPUT_SELECTOR = 'input[type=file]'

/**
 * 当前打开着的那张浮层的 close（一次只会有一张）。效果的 disposer 与「再点一次回形针」
 * 都必须走 close 才能把 keydown / resize / orientationchange / visualViewport(resize+scroll)
 * 这几条持久监听与 rAF 链一起摘掉；只 remove() 节点会把它们留在 document/window 上，
 * 每次档位切换或热重载叠一套，回调还在找已经不在的浮层。
 */
let activeClose: (() => void) | null = null

/** 两个选项：图片走 image 通配，附件优先走桥（见文件头「三路并存」）。 */
export type FilePickerKind = 'image' | 'file'

/**
 * 选项对应的 accept（空串 = 不改宿主 input 的属性）。
 *
 * 2026-10-07 试过给附件那路显式写全通配 accept（想换出系统「打开方式」应用列表）：真机形态没变，
 * 已回退 —— 根因在 App 侧少包一层 `Intent.createChooser`（见文件头）。所以这里仍是「不改属性」。
 * @param kind - 用户选择的入口。
 * @returns 要临时写到宿主 input 上的 accept；空串表示不动该属性。
 */
export function acceptForKind(kind: FilePickerKind): string {
  return kind === 'image' ? 'image/*' : ''
}

/** 本效果用到的两条文案（不把 locales 的类型拖进 effects 层）。 */
type PickerTranslate = (
  key: 'fileUploadImage' | 'fileUploadAttachment',
) => string

/**
 * App 侧文件选择桥（DSH-APP/DSHA#101 的备选方案）：命名空间同分享桥
 * （`window.DSHA.canShareFile` / `shareFile`），只声明本效果用得到的那一个方法 ——
 * 缺方法就当作没有桥（半实现的桥不能把附件路带进死胡同）。
 */
export interface DshaPickFileBridge {
  pickFile?: (options: DshaPickFileOptions) => Promise<unknown>
}

/** 桥的入参：accept 空数组 = 不限类型（与宿主 hidden input 不写 accept 等价）。 */
export interface DshaPickFileOptions {
  accept: string[]
  multiple: boolean
  title: string
}

/** 桥的结果分类：拿到文件 / 用户取消 / 该回落宿主 input。 */
export type BridgePickOutcome = 'files' | 'cancelled' | 'fallback'

/**
 * 取出可用的 `pickFile`（绑定好 this）；没有桥或方法不是函数时返回 undefined。
 * @param bridge - `window.DSHA`。
 * @returns 可直接调用的桥方法。
 */
export function bridgePickFile(
  bridge: DshaPickFileBridge | undefined,
): ((options: DshaPickFileOptions) => Promise<unknown>) | undefined {
  return typeof bridge?.pickFile === 'function' ? bridge.pickFile.bind(bridge) : undefined
}

/**
 * 桥的返回值 → 可信的 `File` 列表（脏数据一律丢弃，不把非 File 塞进宿主 input）。
 * @param value - 桥的 resolve 值。
 */
export function pickResultFiles(value: unknown): File[] {
  return Array.isArray(value)
    ? value.filter((item): item is File => typeof File !== 'undefined' && item instanceof File)
    : []
}

/**
 * 桥的结果该走哪条路：用户取消（`null` / `undefined` / 空数组）**不回落** —— 否则一次取消
 * 会紧接着弹出第二个选择器；桥抛错或返回脏数据才回落，宁可回落到现状也不能点了没反应。
 * @param value - 桥的 resolve 值。
 * @param failed - 桥是否抛错（Promise reject / 同步抛出）。
 * @returns 分类结果。
 */
export function classifyBridgeResult(value: unknown, failed: boolean): BridgePickOutcome {
  if (failed) return 'fallback'
  if (value === null || value === undefined) return 'cancelled'
  if (!Array.isArray(value)) return 'fallback'
  // 空数组 = 用户没选（原生侧通常这么回）；元素全不是 File = 脏数据，回落到现状。
  if (value.length === 0) return 'cancelled'
  return pickResultFiles(value).length > 0 ? 'files' : 'fallback'
}

/** 宿主 hidden input（图片/附件两路唯一的 intake）。 */
function hostInput(): HTMLInputElement | null {
  const card = document.querySelector(CARD_SELECTOR)
  return card === null ? null : card.querySelector<HTMLInputElement>(HOST_INPUT_SELECTOR)
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
export function handFilesToHost(files: File[], input: HTMLInputElement | null): boolean {
  if (input === null) return false
  try {
    const transfer = new DataTransfer()
    for (const file of files) transfer.items.add(file)
    input.files = transfer.files
  } catch {
    return false
  }
  input.dispatchEvent(new Event('change', { bubbles: true }))
  return true
}

/** 两个选项的行图标（内联静态 SVG，无用户数据）。 */
const IMAGE_ICON =
  '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true" width="20" height="20">' +
  '<rect x="2.5" y="3.5" width="15" height="13" rx="2.5" stroke="currentColor" stroke-width="1.5"/>' +
  '<circle cx="7.2" cy="8" r="1.4" fill="currentColor"/>' +
  '<path d="M4.2 14.6l3.6-3.4 2.6 2.4 2.3-2.3 3.1 3.3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
  '</svg>'

const FILE_ICON =
  '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true" width="20" height="20">' +
  '<path d="M12.6 3.2H6.4A1.9 1.9 0 0 0 4.5 5.1v9.8a1.9 1.9 0 0 0 1.9 1.9h7.2a1.9 1.9 0 0 0 1.9-1.9V6.6l-2.9-3.4z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>' +
  '<path d="M12.4 3.4v3.3h3.1" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>' +
  '</svg>'

/**
 * 把点击交回宿主的 hidden input，按选项临时改 accept（两路都会写，click 返回后还原）。
 * @param kind - 用户选择的入口。
 * @param input - 要点的 input；缺省取「当前那一个」（图片路与无桥回落都是同步的，没有身份风险）。
 */
function openHostPicker(kind: FilePickerKind, input: HTMLInputElement | null = hostInput()): void {
  if (input === null) return
  const accept = acceptForKind(kind)
  const hadAccept = input.hasAttribute('accept')
  const priorAccept = input.getAttribute('accept')
  if (accept !== '') input.setAttribute('accept', accept)
  try {
    input.click()
  } finally {
    if (accept !== '') {
      if (hadAccept) input.setAttribute('accept', priorAccept ?? '')
      else input.removeAttribute('accept')
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
export async function openAttachmentPicker(
  t: PickerTranslate,
  readSessionId?: () => string | null,
): Promise<void> {
  const bridge = (window as unknown as { DSHA?: DshaPickFileBridge }).DSHA
  const pick = bridgePickFile(bridge)
  if (pick === undefined) {
    openHostPicker('file')
    return
  }
  // 打开选择器时捕获身份：桥是异步的，这期间用户可能切会话，composer 也可能被 React 重建。
  const target = hostInput()
  const sessionId = readSessionId?.() ?? null
  let value: unknown = null
  let failed = false
  try {
    value = await pick({ accept: [], multiple: true, title: t('fileUploadAttachment') })
  } catch {
    failed = true
  }
  // 交接前校验：同一个元素、仍在文档里、仍是同一个会话。`hostInput()` 只可能命中已挂载的节点，
  // 「重查仍是同一个元素」即同时证明同一性与 `isConnected`；会话那一路防宿主复用同一棵 composer
  // DOM 换会话。对不上就**丢弃这次结果**：不二手交接、不重放选择器、也不回落去点新 input。
  if (target === null || hostInput() !== target) return
  if (sessionId !== null && (readSessionId?.() ?? null) !== sessionId) return
  const outcome = classifyBridgeResult(value, failed)
  if (outcome === 'cancelled') return
  if (outcome === 'files' && handFilesToHost(pickResultFiles(value), target)) return
  openHostPicker('file', target)
}

/** 浮层与锚点的间距。 */
const GAP = 8
/** 夹进视口时留的边距。 */
const EDGE = 8

/** 锚点盒（`getBoundingClientRect()` 的子集，纯核不依赖 DOMRect）。 */
export interface PickerAnchorBox {
  top: number
  right: number
  bottom: number
  left: number
}

/** 浮层自身尺寸。 */
export interface PickerSize {
  width: number
  height: number
}

/** 可见几何带 —— layout 视口坐标下「真正看得见」的那一段横带 / 竖带。 */
export interface PickerVisibleBand {
  top: number
  bottom: number
  left: number
  right: number
}

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
export function computePickerPlacement(
  anchor: PickerAnchorBox,
  own: PickerSize,
  band: PickerVisibleBand,
): { top: number; left: number } {
  let top = anchor.top - GAP - own.height
  if (top < band.top + EDGE) top = Math.min(anchor.bottom + GAP, band.bottom - own.height - EDGE)
  const left = Math.max(band.left + EDGE, Math.min(anchor.left, band.right - own.width - EDGE))
  return { top: Math.round(Math.max(band.top + EDGE, top)), left: Math.round(left) }
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
function visibleBand(): PickerVisibleBand {
  const vv = window.visualViewport
  if (vv !== undefined && vv !== null) {
    return { top: vv.offsetTop, bottom: vv.offsetTop + vv.height, left: vv.offsetLeft, right: vv.offsetLeft + vv.width }
  }
  return { top: 0, bottom: window.innerHeight, left: 0, right: window.innerWidth }
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
function openSheet(
  t: PickerTranslate,
  trigger: Element,
  readSessionId: () => string | null,
): void {
  // 关掉上一张浮层 —— 必须走它自己的 close（摘监听/rAF/计时器），只 remove() 节点会把监听留在
  // document/window 上（见下方的 disposer 注释）。
  activeClose?.()
  activeClose = null
  const backdrop = document.createElement('div')
  backdrop.dataset.mobileNav = 'file-picker-backdrop'
  const sheet = document.createElement('div')
  sheet.dataset.mobileNav = 'file-picker'
  sheet.setAttribute('role', 'dialog')
  sheet.setAttribute('aria-modal', 'true')
  sheet.setAttribute('aria-label', t('fileUploadAttachment'))

  const rows: ReadonlyArray<{ kind: FilePickerKind; icon: string; label: string }> = [
    { kind: 'image', icon: IMAGE_ICON, label: t('fileUploadImage') },
    { kind: 'file', icon: FILE_ICON, label: t('fileUploadAttachment') },
  ]
  /** 打开期间挂上的监听/rAF，关闭时逐个摘掉。 */
  const teardown: Array<() => void> = []
  const close = (): void => {
    if (activeClose === close) activeClose = null
    for (const off of teardown.splice(0)) off()
    backdrop.remove()
  }
  activeClose = close
  const onKey = (event: KeyboardEvent): void => {
    if (event.key === 'Escape') close()
  }
  for (const row of rows) {
    const button = document.createElement('button')
    button.type = 'button'
    button.dataset.mobileNav = 'file-picker-option'
    button.dataset.kind = row.kind
    const icon = document.createElement('span')
    icon.dataset.mobileNav = 'file-picker-icon'
    icon.innerHTML = row.icon
    const label = document.createElement('span')
    label.textContent = row.label
    button.append(icon, label)
    button.addEventListener('click', () => {
      close()
      // 附件优先走桥（App 侧有的话），图片仍走宿主的 accept="image/*"。
      if (row.kind === 'file') void openAttachmentPicker(t, readSessionId)
      else openHostPicker(row.kind)
    })
    sheet.append(button)
  }
  // 只认浮层外的点按（浮层内的单击不关，避免选完立刻又被关闭）。
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) close()
  })
  document.addEventListener('keydown', onKey, true)
  teardown.push(() => document.removeEventListener('keydown', onKey, true))
  backdrop.append(sheet)
  document.body.append(backdrop)

  // 定位：锚点 rect → 上方优先，夹进视口。挂载后再量，所以这里量得到自身尺寸。
  // 先定好位再显形（2026-10-07 店主：「点击回形针弹出来的两个选项，会弹一下」）：点回形针会让
  // 软键盘收起，视口在随后约 200ms 里持续变化，按旧坐标立刻画出来就是「先出现在键盘上方、再跳
  // 一下」。所以隐藏挂载（`visibility` 不参与布局、仍能量尺寸），等连续两帧位置相同再显形；
  // follow 跑完或 400ms 兜底也必须显形，免得 rAF 停摆（后台标签页）把浮层永久留在隐藏态。
  sheet.style.visibility = 'hidden'
  let revealed = false
  const reveal = (): void => {
    if (revealed) return
    revealed = true
    sheet.style.visibility = ''
  }
  let stableKey = ''
  let stableFrames = 0
  const revealTimer = window.setTimeout(reveal, 400)
  teardown.push(() => window.clearTimeout(revealTimer))
  const place = (): void => {
    if (!trigger.isConnected) {
      close()
      return
    }
    // 每一次都重取锚点 rect —— 键盘动画期间锚点自己在动，缓存它等于不跟随
    // （「键盘抬起时浮层仍跟着锚点走」就是这个效果的原始目的）。
    const anchor = trigger.getBoundingClientRect()
    const own = sheet.getBoundingClientRect()
    const settled = computePickerPlacement(anchor, own, visibleBand())
    const nextTop = `${settled.top}px`
    const nextLeft = `${settled.left}px`
    // 写前比较：follow 链每帧都调一次 place，值没变时重写 inline 样式会让样式失效并同步
    // 重算这一层（本仓同型修复见 installed-list 的 write()）。
    if (sheet.style.top !== nextTop) sheet.style.top = nextTop
    if (sheet.style.left !== nextLeft) sheet.style.left = nextLeft
    const key = `${settled.top}:${settled.left}`
    if (key === stableKey) stableFrames += 1
    else {
      stableKey = key
      stableFrames = 0
    }
    if (stableFrames >= 2) reveal()
  }
  place()
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
  let following = true
  const onViewport = (): void => {
    if (following) return
    place()
  }
  window.addEventListener('resize', onViewport)
  window.addEventListener('orientationchange', onViewport)
  window.visualViewport?.addEventListener('resize', onViewport)
  window.visualViewport?.addEventListener('scroll', onViewport)
  teardown.push(() => window.removeEventListener('resize', onViewport))
  teardown.push(() => window.removeEventListener('orientationchange', onViewport))
  teardown.push(() => window.visualViewport?.removeEventListener('resize', onViewport))
  teardown.push(() => window.visualViewport?.removeEventListener('scroll', onViewport))
  let frames = 0
  let raf = requestAnimationFrame(function follow() {
    // The keyboard animation is progressive, so the anchored position only
    // stops changing after ~10 frames; reveal on the last one at the latest.
    place()
    // 浮层可能刚在 place() 里被收起（锚点脱离文档 → close()）：close 的 teardown 已经取消了
    // 本链，这里只需把标志复位、不再排帧、也不再显形。
    if (!sheet.isConnected) {
      following = false
      return
    }
    if (++frames < 12) raf = requestAnimationFrame(follow)
    else {
      following = false
      reveal()
    }
  })
  teardown.push(() => cancelAnimationFrame(raf))
}

/**
 * 安装手机档的输入框文件入口弹层。
 * @param ctx - client 上下文。
 */
export function installComposerFilePicker(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: composer file picker', () => {
    const t = ctx.locale.bind(NS) as PickerTranslate
    // 会话身份来源：切了会话就不能再往原 input 交接 —— 宿主切会话时可能复用同一棵
    // composer DOM（元素还连着，只比元素会认错），所以会话 id 也要一起对账。
    const sessionIdOf = (): string | null =>
      currentSessionIdOf(ctx.sessions.list.getSnapshot()) ?? null
    const onClick = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      const trigger = target.closest(TRIGGER_SELECTOR)
      if (trigger === null || trigger.hasAttribute('disabled')) return
      // 入口自己不再直点宿主 input（ComposerFileButton 2026-10-06 起只渲染按钮），
      // 这里吞掉这一击，唯一的后续是弹层。
      event.preventDefault()
      event.stopPropagation()
      openSheet(t, trigger, sessionIdOf)
    }
    document.addEventListener('click', onClick, true)
    return () => {
      document.removeEventListener('click', onClick, true)
      // 浮层还开着就按它自己的路径关掉（先摘监听/rAF，再删节点）——只 remove() 节点会把 5 个
      // 持久监听留在 document/window 上，切换档位/热重载就叠一套。
      activeClose?.()
      activeClose = null
      document.querySelector('[data-mobile-nav="file-picker-backdrop"]')?.remove()
    }
  })
}
