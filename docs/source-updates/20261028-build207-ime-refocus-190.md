# 键盘切后台回前台后 composer 停在矮视口的修复（issue #190 原生侧）

本轮基于 build206（`0.2.1-rc.3-20261028.1200-dsh0.2.1-rc.3`）源码，处理公开仓库 issue #190
的**原生侧缺口**：带键盘切后台再回前台后，`innerHeight` 与 `visualViewport.height`
同步停在键盘占位的 451px（752 − 301），composer 漂移，且不随任何事件收敛。
issue 中另附第三方只读排查：客户端 `syncStableViewportHeight` 只有 resize /
visualViewport 三个监听器（无 `visibilitychange`/`pageshow`），原生
`WebFullscreenUi` 从未核对 IME 的真实可见性。
源码版本为 `0.2.1-rc.3-20261028.0843-dsh0.2.1-rc.3` / build207；本轮未构建、安装或
发布 APK，未操作手机。

## 根因（原生侧，两层表述）

`WebFullscreenUi.ContentInsets.onWindowFocusChanged(true)` 回前台时只做
`ViewCompat.requestApplyInsets(content)`，没有核对 IME 的真实可见性。本修复保证
的确定行为是：**回前台且 root insets 报告 IME 不可见时，容器立即清掉键盘边距并
强制一次布局**——等价于 issue 中 workaround 的那次真实 IME 高度变化，让
WebView 的布局视口从键盘占位的矮视口收敛。

对 issue 描述的「stale 边距派发重新吃进键盘占位」这一类根因，本修复不构成拦截：
Standard（minSdk 30）上 `View.getRootWindowInsets()` 与 `requestApplyInsets`
读同一份 ViewRootImpl 状态，若该状态仍带键盘占位（`isVisible(ime)` 为 true），
修复分支不会执行，行为与修复前一致；若状态已报告不可见，常规派发本就能收敛。
真机上「带键盘切后台再回前台」后该状态的实际取值由各 ROM 决定，无设备环境无法
确认 issue 现象对应哪一类；本修复的强制布局对另一类可能根因（Chromium 端视口
缓存不随容器 padding 收敛刷新）有直接作用。设备验收继续暂停，真机复现 #190
后按实际行为补充验收项。

## 修复

回前台时核对 IME 的真实可见性：
`ViewCompat.getRootWindowInsets(content).isVisible(Type.ime())` 为 false 时
（或拿不到 root insets 时按保守处理）执行 `forgetKeyboard()`、`render()` 并
`content.requestLayout()`——等价于 issue 中 workaround 的那次真实 IME 高度变化，
随后再走常规的 `requestApplyInsets`。IME 仍可见时的路径与既有行为一致。

## 守护边界：网页侧视口函数本轮不动

issue 与第三方排查还指出客户端视口函数的两处改进（`visibilitychange`/`pageshow`
重同步、`--dsha-mobile-visible-vh` 的逐帧写入合并）。**本轮不实现**：
`AGENTS.md` 明文要求「不恢复上游已撤回的视口改动」（上游 dsh-web-mobile PR #236
已由 #239 revert），`tools/test-mobile-client-patches.mjs` 将
`syncStableViewportHeight` 与上游固定 commit 逐字节锁定；#187 维护者亦已回复
「rc3 我修了，等我再完善一下」。重开该区域属于维护者的设计决定，需要先修订
AGENTS.md 与守护测试，不应由本轮顺手突破。

## 测试

- `WebFullscreenUiImeTest` 新增两例：
  - `refocusWithoutVisibleImeReleasesStaleKeyboardAndForcesRelayout`：键盘稳态 →
    失焦 → root insets 的 ime 置零 → 回前台，断言键盘边距被释放且触发了一次
    `requestLayout`（Content 类新增 `layoutRequests` 计数）；
  - `refocusWithVisibleImeKeepsTheKeyboardInsetPath`：root insets 仍报告 IME
    可见时，键盘边距照常应用，不改变既有路径。
- 该测试类全量通过（Robolectric SDK 34，`BUILD SUCCESSFUL`）。

## 验证范围与限制

- Windows 宿主、source-only 模式运行 Standard Debug 定向单元测试与
  `check-source-hygiene.py`、changelog 门禁；完整输入验收未做。
- 真机上「切后台 → 回前台」的 IME 时序由各 ROM 决定：本修复保证「回前台时 IME
  不可见则容器立即收敛」这一确定性路径，不证明所有机型的 stale 派发顺序均已覆盖；
  设备验收继续暂停，留待恢复时确认。
- Low（API 23–29）共用本类：androidx 的 `isVisible(ime)` 在旧 API 上是启发式，
  误报「不可见」时会产生一次先清后恢复的视口抖动（可自愈、无崩溃级风险）。
  Low 未在本轮验证范围，仅如实记录差异。
- 未构建、安装或发布 APK；未操作手机。
