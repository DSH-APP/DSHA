# WebView 页面保活 B-min：K0 证据 + K1 keeper + K2 生命周期安全（实验性）

- 日期：2026-11-07（UTC）
- 任务：T110（P2，目标 build337）。用户 335→336 验收发现每次从网页返回原生主页面再回网页，网页侧栏连接状态重走「黄→绿」、原生侧闪一次加载遮罩；根因是页面未保活。
- 基线：`origin/main` = `fe7f5e15`（build336 发布提交；本分支建立在 build336 上，不合并旧 build333 分支 `9deffea2`——仓库历史已改写）。
- 范围：**K0 证据 + K1 keeper + K2 生命周期安全**；K3（插件契约）、K4（认证/首帧/内存策略）、K5（真机矩阵）不在本次实现范围。
- 定位：**实验性、尽力适配**。只承诺“同一进程内 Activity 被系统回收时页面不变”；进程死亡/强杀/APK 更新仍然重载，靠文档恢复（方案 D）兜底；不承诺所有手机、平板、自由窗口或 OEM WebView 表现一致。
- K5 真机：**部分执行**——T115/T116/T111 通过；场景 1（流式长页面应用内往返）经两轮修复后**真机通过**（二轮诊断包 3d3866f7；黄→绿与整页重载/长加载均消失）。其余场景未执行；T110 合并范围由版本线决定。根因、修复与证据见 [K5 回执](k5-acceptance-20261107.md)。

## 0. 一句话结论

`Retained` 的所有权从 Activity/ViewModel 提升到进程级 `BrowserPageKeeper`：

- `onDestroy(isChangingConfigurations)` → 只摘挂，页面留在 keeper；新 Activity 接管同一 WebView；
- `onDestroy(isFinishing)` → retire/destroy；
- 系统回收（两者皆 false）→ 摘挂保留，新 Activity 以同一 generation/instance 接管，**不 `loadUrl`**；
- was detached 页面在 Web 停止/维护/备份/换代、renderer 退出、origin-restore、任务移除、低内存 trim 时销毁；
- generation/instance 不匹配或 renderer 已死 → 销毁，不复用死页面。

K0 只加脱敏诊断，用于真机区分 P0（Activity 被回收）/ P1（进程死亡）/ P2（Web 换代）/ P3（renderer 退出）。

## 1. 为什么需要 K1（设计报告摘要）

原 `WebPreviewActivity.onDestroy` 只有两个分支：

```java
if (isChangingConfigurations() && webView != null) {
  // 配置变更：detach，WebView 交给 ViewModel
} else destroyWebView();
```

系统在进程存活时回收 stopped Activity（`isFinishing()==false`、`isChangingConfigurations()==false`）会走进 `else`，把唯一页面销毁；用户回到 Web 时 ViewModel 已清空，只能重新 `loadUrl`。`singleTask` / `reorderToFront` / `launchMode` 都解决不了“唯一实例已被系统回收”。

B-min 把 `Retained` 从 Activity 作用域提升到应用作用域，并把“何时销毁”从 Activity 的隐式行为改成显式策略。

## 2. 实现范围

### 2.1 新增

| 文件 | 职责 |
|---|---|
| `ui/BrowserPageKeeper.java` | 应用作用域、单 owner 的页面保管器；`acquire/detach/retireFrom/retire/retireDetachedPage/trimMemory/onTaskRemoved`；实现 `ComponentCallbacks2`（低内存）；监听 Web 运行时身份变化 |
| `ui/WebPageDiagnostics.java` | K0 脱敏诊断入口：`WEB_ENGINE`、`WEB_PAGE_DESTROY`、`WEB_KEEPALIVE_ATTACH/DETACH/RETIRE` |
| `util/WebPageDiagnosticText.java` | 诊断行纯格式化；原因码白名单（小写字母/数字/连字符，≤24），URL/token/控制字符只会得到 `unknown` |

### 2.2 修改

| 文件 | 改动 |
|---|---|
| `ui/WebPreviewActivity.java` | `Retained` 从 keeper 借阅；`onDestroy` 三分支（config detach / finish retire / 系统回收保留）；`detachPageView()`；`destroyWebView(reason)`；renderer 退出与各销毁入口记录 K0 原因 |
| `HarnessService.java` | `onTaskRemoved` 显式 retire 页面，不依赖进程随后死亡 |
| `ui/BrowserOriginRestore.java` | `open()` 先退役已 detached 页面；`awaitClosed` 同时等待预览 Activity 与 keeper 页面销毁 |
| `assets/runtime-descriptor.json` | `HarnessService.java` 属钉住的 launcher 源：按 `tools/prepare-runtime-descriptor.py --write` 重算 `launcherInputs` 与 `runtimeId`（见 §5.2） |
| `tools/java-format-files.json` | 登记 5 个新 Java 文件 |

**没有改**：`app-integration/client.js`（K3）、`DshaApp.java`、`HarnessController.java`、`WebLifecycleController.java`（复用现有 `addReadyWebPageListener`，不新增编排职责）、PiP/多窗口策略、认证/首帧。

## 3. K0：脱敏证据

只记录可枚举事实，不写页面地址、cookie、token 或页面标题。

| 日志 stage | 内容 | 用途 |
|---|---|---|
| `WEB_ENGINE` | `pid=<pid> generation=<gen> webview=<包名 版本>` | 区分“新进程”（pid 变化）与“新文档” |
| `WEB_PAGE_DESTROY` | `reason=<finish|identity|session-changed|reload|init-failed|fallback|renderer> changingConfig=<bool> finishing=<bool> generation=<gen>` | 证明销毁发生在哪条路径 |
| `WEB_KEEPALIVE_ATTACH` | `action=ATTACH reason=<上次 detach 原因> generation=<gen> ready=<bool>` | 新 Activity 接管 detached 页面 |
| `WEB_KEEPALIVE_DETACH` | `action=DETACH reason=<config|system> generation=<gen> ready=<bool>` | 页面被保留 |
| `WEB_KEEPALIVE_RETIRE` | `action=RETIRE reason=<finish|renderer|trim|task-removed|web-session|origin-restore|empty> generation=<gen> ready=<bool>` | keeper 主动销毁 |

一次真机往返即可判断：

- 有 `WEB_ENGINE` + 新 pid → P1 进程死亡；
- 有 `WEB_ENGINE`、pid 相同、且没有 `WEB_PAGE_DESTROY(finishing=false,changingConfig=false)` → 进程内 Activity 被回收（P0，K1 目标）；
- `WEB_PAGE_DESTROY reason=session-changed` → Web 服务换代/停止（P2，预期重载）；
- `WEB_PAGE_DESTROY reason=renderer` → renderer 退出（P3）。

原因码经 `WebPageDiagnosticText.reasonCode` 白名单处理；`WebPageDiagnostics` 不接收 URL。

## 4. K1+K2：状态机与规则

```
[无页面]
   │ Activity 首次创建 → keeper.acquire() → loadSession → createWebView
   ▼
[attached] ──onStop/Home/Back──> [attached, Activity stopped]
   │                                    │
   │ onDestroy(changingConfig)          │ onDestroy(system reclaim)
   ▼                                    ▼
[detached in keeper] <──────────────────┘
   │ 新 Activity onCreate（identity 匹配）→ attach，不 loadUrl
   ▼
[attached, 同一 WebView]

[detached] ──finish/identity/renderer/trim/task-removed/web-session/origin-restore→ [retire/destroy]
[attached] ──finish/identity/session-changed/renderer────────────────────────────> [retire/destroy]
进程死亡 ─────────────────────────────────────────────────────────────────────> [无页面，走 D 恢复]
```

### 4.1 `onDestroy` 策略（K1）

| 条件 | 行为 |
|---|---|
| `isChangingConfigurations()` | `detachPageView()` + `keeper.detach(config)`；新 Activity acquire 同一页面 |
| `isFinishing()` | 记录 `WEB_PAGE_DESTROY reason=finish` → 摘视图 → `keeper.retireFrom(finish)` → `Retained.dispose()` 销毁 WebView/上传/下载/麦克风 |
| 两者皆 false（系统回收） | 摘视图 + `keeper.detach(system)`；页面保留，等新 Activity 接管 |
| keeper 已不拥有本 Activity | 只移除本地视图引用，不摘别人的页面（防止旧实例误杀新 owner） |

`detachPageView()` 只做视图/客户端摘除：`container.removeView`、`WebViewClient` 换成只处理 renderer 退出的 `RetainedRendererClient`、`WebChromeClient`/`DownloadListener` 置空、`MutableContextWrapper` 切到 application context。页面自己的 `pageSession`/`themeGate`/`pageEvents`/`compatibilityScript` 属于 WebView 级，保留。

### 4.2 接管条件（K1）

- `keeper.acquire()` 返回应用级唯一 `Retained`；新 Activity 仍走原有 `onCreate` 判定：
  - `retained.view != null` 且 `pageSession` 与当前 `getReadyWebPageIdentity()` 一致 → 挂回容器，`continueInitialNavigation()`；
  - 身份不一致 → `destroyWebView(identity)` 销毁旧 WebView，再用当前凭据 `loadSession`；
  - 没有页面 → 正常 `loadSession`。
- 首次导航由 `PreviewNavigation.claim()` 保证只发生一次：已完成的页面在 Activity 重建后 `claim()` 返回 false，不会二次 `loadUrl`。
- `keeper.acquire` 若发现页面仍挂在旧容器上（极端竞态），先 `removeView` 再交给新 Activity，避免 “already has a parent”。

### 4.3 销毁规则（K1/K2）

- `detach` 只清 owner；空壳页面（`view == null`）没有保留价值，直接 retire。
- `retireFrom(activity, reason)` 只在 keeper 仍属于该 Activity 时生效；旧 Activity 不能销毁新 Activity 已接管的页面。
- renderer 在 detached 空窗退出：`Retained.retireRendererView` 清理后调用 `keeper.retireDetached(renderer)`；前台 renderer 退出仍由现有错误面板/重试路径接管。
- `trimMemory`：只有 `detached` 页面在 `RUNNING_LOW/RUNNING_CRITICAL/COMPLETE` 时销毁；`TRIM_MEMORY_UI_HIDDEN` 不等于内存不足。
- `onTaskRemoved()`：`HarnessService` 在用户划掉任务时 retire 页面（不再依赖进程随后死亡）。
- `retireDetachedPage(reason)`：只销毁已 detached 页面，供 origin-restore 使用；attached 页面交给 Activity 的 finish 流程。

### 4.4 Web 运行时身份监听（K2）

`BrowserPageKeeper` 在首次 `acquire` 时向现有 `HarnessController.addReadyWebPageListener` 注册一次回调（不新增 Controller 职责）：

- 只有页面 **detached** 时才处理；attached 页面由 `WebPreviewActivity.synchronizeWebSession()` 自己收尾，keeper 不抢先销毁正在显示的 WebView。
- 当前 ready identity 与页面 identity 相同 → 保留；
- 当前 identity 为 null 且 `isWebSessionRetained(pageIdentity)` 为真（恢复中的未知会话）→ 保留，避免“启动瞬态 null 就丢页面”；
- 其它情况（停止/维护/备份/换代/启动失败）→ `retire("web-session")`，销毁页面并清 owner。
- 回调本身由 `WebLifecycleController.notifyReadyWebPageChanged()` post 到主线程；销毁 WebView 在主线程执行。

### 4.5 origin-restore（K2）

- `BrowserOriginRestore.open()` 在 QUIESCE 广播前调用 `keeper.retireDetachedPage("origin-restore")`：已摘挂的保活页面先销毁，避免迁移与活页面同时读写旧 origin。
- 仍附着的预览 Activity 走既有 QUIESCE→`finish()`→`onDestroy(isFinishing)`→retire。
- `awaitClosed` 的等待条件从「PREVIEWS 为空」扩展为「PREVIEWS 为空且 `BrowserPageKeeper.hasKeptPage()` 为假」，页面没真正销毁就不会开始迁移。

### 4.6 运行开关

没有 feature flag（K1 设计如此，避免引入设置项与 i18n）；不可接受时回退方式为 revert 本分支。K4 可评估 `ConfigStore` 开关。

## 5. 测试与门禁

### 5.1 单测（Robolectric，`sdk=34`，不运行真实网页/guest）

- `BrowserPageKeeperTest`（16）：单页复用、config detach、finish retire、owner 隔离、空壳、trim、task-removed、renderer gap、**运行时身份监听（换代 retire / 恢复期保留 / attached 不销毁）**、`retireDetachedPage`、`hasKeptPage`。
- `WebBackgroundPageLifecycleTest`（新增 7 + 既有 13）：`ActivityController` 模拟系统回收后新 Activity 接管同一 `Page` 且 `navigations` 仍为 1；配置变更重建不 load；`finish()` 销毁并释放 keeper；`onNewIntent` 同代不增加导航；Web 换代销毁旧页面；`HarnessService.onTaskRemoved` 退役页面。
- `WebPageDiagnosticTextTest`（5）：URL/token/控制字符不能进入诊断行；原因码白名单。
- 回归：`BrowserTasksTest`、`PictureInPictureOptInTest`、`WebPageBootOverlayTest`、`WebCrashPanelTest` 全部通过。

命令：`dsha-gradle :app:testStandardDebugUnitTest --tests '*BrowserPageKeeperTest' ...`（定向，不跑全量）。

### 5.2 运行时描述符重算（必须说明）

`HarnessService.java` 在 `app/src/main/assets/managed-runtime-inputs.json` 的 `launcherSources` 里，属于受管运行时身份的一部分。K2 修改它之后：

- `tools/prepare-runtime-descriptor.py --write` 重算 `launcherInputs["HarnessService.java"]` 与 `runtimeId`；
- 本分支的描述符差异：K2 的 `HarnessService.java` 摘要、K5 场景 1 修复的受管运行时输入 `app-integration/client.js` 摘要，以及 `runtimeId`；其它 launcher/资产摘要不变；
- 后果：已装 build336 的运行时健康回执与 build337 的描述符不再相等，覆盖升级后会按既有“运行时不是当前候选”门禁重新验证/进入维护页。这是修改 launcher 源的既定代价，不是本功能额外引入的运行时替换。
- 不放宽 `RuntimeDescriptor.latest()` / `healthy()` 的核验。

## 6. 已知限制与风险（必须如实声明）

1. **进程死亡/强杀/APK 更新**：keeper 随进程消失，DOM/JS 堆不可恢复；仍走 `WebView.saveState` + 插件草稿/阅读位置恢复。不要把本方案写成“杀进程也不变”。
2. **K5 真机仅部分执行**：本分支有代码 + Robolectric + 场景 1 修复单测；场景 1 已真机通过（二轮诊断包 3d3866f7，见 K5 回执）；Activity 系统回收、WebView detach/attach、renderer 回收时序、OEM WebView 差异、内存与 PiP/多窗口仍必须在真机验证后才能宣称“已验收”。测试包安装与设备操作需用户明确授权。
3. **K3 插件契约未实现**：`client.js` 不知道“同一文档 suspend/resume”；保活页不会伪造 `pageshow/pagehide`，但旧插件在后台的草稿 lease/阅读位置行为未验证。K3 需要 `__DSHA_NATIVE_LIFECYCLE__` + `documentToken` + 握手降级。
4. **K4 认证/首帧/内存未加固**：detached 页面复用旧 cookie；`onResume` 仍比较 `getWebAuthUrl()` 并重推语言/主题，但没有主动 auth lease 探测、detach 时 `pauseTimers()`、首帧遮罩或空闲超时。后台 JS/网络仍可能执行。
5. **上传/下载/麦克风**：`Retained.dispose()` 沿用现有 `onCleared` 清理；detached 期间 `pendingPicker/uploadSession/blobDownload/microphone` 只做静态清理，`retained.blobDownload` 仍持有旧 Activity 的 `WebDownloads.model`（与既有配置变更路径一致，本分支未改），K4/K5 需真机验证。
6. **单宿主约束**：keeper 只持有一个页面，依赖现有 `BrowserConversationRoute` + `forwardToLiveBrowser` 保证同一时刻只有一个 Web 宿主；多窗口/自由窗口/PiP 双宿主互抢页面未在 K2 处理。
7. **origin-restore 的极端竞态**：若 open() 时页面仍 attached、随后 Activity 又被系统回收，QUIESCE 没有可 finish 的 Activity，`awaitClosed` 会在 20 秒后安全失败（`RESTORE_PREVIEW_CLOSE_UNCONFIRMED`），不会并发迁移；K5 应覆盖。
8. **已装运行时身份变化**：见 §5.2；覆盖升级会重新验证运行时，需要用户在真机验收时知悉。

## 7. K5 真机验收计划（场景 1 已执行；其余需用户授权）

> 场景 1 已于 2026-11-07 在 LENOVO TB321FU 通过（二轮诊断包 3d3866f7：黄→绿消失，流式输出不再整页重载/长时间卡加载）。下表其余场景仍未执行。

前置：用户在主会话明确同意“安装 `io.github.sunsetrne.dsha.test` 测试包 + 真机操作”；不得安装正式包 `io.github.sunsetrne.dsha`（会结束整个 DSHA/会话）。

设备/内核：至少 2 台设备、2 种 WebView 版本（建议一台 AOSP/Android 13+、一台 OEM）。

| # | 场景 | 断言 |
|---|---|---|
| 1 | 应用内往返：Web → Main → 设置/终端/插件页 → 回 Web | 无新 `WEB_ENGINE`、无新 `onPageStarted`、侧栏不重走黄→绿、无原生加载遮罩、草稿/滚动/流式状态保留 |
| 2 | Home/切 App 30 秒/5 分钟/30 分钟 | 同上；detach 后无新页面加载 |
| 3 | 开发者选项“不保留活动”/系统回收 Activity | `WEB_KEEPALIVE_DETACH/A` 与 `ATTACH`，同 pid、无 `WEB_ENGINE`、页面不变（P0 核心场景） |
| 4 | 进程死亡：最近任务划掉 / force-stop（需授权）/ OOM | 预期重载 + 草稿/滚动恢复；不崩溃、不重复恢复 |
| 5 | 配置：旋转、深浅色、语言、字体缩放 | 不重载；主题/语言/字体生效 |
| 6 | PiP：进入/退出、PiP 中切后台 | 不重载、页面可见、返回路径正确 |
| 7 | 停止/维护/备份 | keeper 页面销毁、无旧 cookie 页面；重启后新代次页面；`dumpsys meminfo` 回落 |
| 8 | origin-restore：旧端口草稿恢复 | 主页面先关闭、迁移成功/冲突保留、恢复后重新进入不重复恢复 |
| 9 | renderer：`chrome://crash` / 系统回收 renderer | 错误面板、重试新建、不复用死 WebView |
| 10 | 内存：冷启动、首次 Web、10 次往返、30 分钟后台 | 无持续增长；trim 后能降级重载 |
| 11 | 多窗口/分屏/自由窗口 | T46 单窗口约束仍成立；无第二个 WebView/任务 |
| 12 | 上传/下载/麦克风 | 保活前后各一次；旧 Activity 回调不能批准新页 |
| 13 | 日志 | `WEB_PAGE_DESTROY` / `WEB_KEEPALIVE_*` 原因完整；无 token/URL |

记录要求：每条场景保留设备型号/Android/WebView 版本、操作时间线、脱敏日志片段；未执行项不得写成通过。

> 2026-11-07 用户决定：未执行场景（含 2/3/4/5/6/7/8/9/10/11/12/13、第二设备/第二 WebView）**声明为实验性，不阻塞 T110 合并**；已通过场景 1（应用内往返）；未执行项仍不得写成通过。

## 8. 后续任务

- **K3 插件契约**：`__DSHA_NATIVE_LIFECYCLE__`、`dsha-browser-suspend/resume`、`documentToken`；`client.js` 的 lease/阅读位置/流恢复；旧插件握手失败则不保活。
- **K4 认证/首帧/内存**：detach 时 `pauseTimers()`、attach 首帧遮罩、auth lease 探测、空闲超时/内存预算/feature flag。
- **K5**：场景 1 已通过；按 §7 继续其余场景并归档回执；需要用户授权。
- **多窗口/PiP**：若后续产品允许，补 keeper 双宿主互斥/转交策略。

## 9. 参考

- 设计报告：`/tmp/webview-keepalive.md`（本机只读评估，不入仓库）
- 关键代码：`app/src/main/java/com/deepseekharness/app/ui/{WebPreviewActivity,BrowserPageKeeper,WebPageDiagnostics,BrowserOriginRestore}.java`、`app/src/main/java/com/deepseekharness/app/util/WebPageDiagnosticText.java`、`app/src/main/java/com/deepseekharness/app/HarnessService.java`
- 测试：`app/src/test/java/com/deepseekharness/app/ui/{BrowserPageKeeperTest,WebBackgroundPageLifecycleTest}.java`、`app/src/test/java/com/deepseekharness/app/util/WebPageDiagnosticTextTest.java`
