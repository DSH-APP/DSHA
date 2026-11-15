# 对话页错误面板原生实现（实验性）

按同目录 `preview.html`（v2）落到 `WebPreviewActivity` 的错误覆盖层 `web_error_panel`。

**实验性变更声明**：崩溃现场依赖真实进程退出、渲染器崩溃、保活重启与网络错误，无法快速稳定复现；本次只做到尽力而为，仅宿主验证，未上真机。合入后需统一视觉与行为验收（清单见文末），验收前不得写成「崩溃场景已全部覆盖」。

## 实现映射

| 原型 | 原生 |
|---|---|
| 17 种结论、按钮、逐层状态 | `util/WebCrashReport`：纯判定，不含显示文案，不 import Android |
| 结论文案、状态表、宽窄三档、技术详情复制 | `ui/WebCrashPanel`：只显示，动作经回调交给 Activity |
| 布局 | `activity_web_preview.xml` 的 `web_error_panel` 重写；既有 ID（`web_retry` / `web_error_title` / `web_error_detail` / `web_error_browser` / `web_error_logs` / `web_error_recovery`）保留 |
| 宽窄三档 | 按面板实际宽度：<600dp 单列；≥600dp 单列限宽 520、主次按钮并排；≥900dp 左右两栏 |

### 各入口 → 结论

| 入口 | 结论 |
|---|---|
| `suspendRuntimePage(exited=true / false)` | `EXITED_KEPT` / `VERIFY` |
| 就绪身份消失、页面未被接回保留（`synchronizeWebSession` 销毁 WebView 之后） | `WebCrashReport.ended(...)`：预算用尽 → `BLOCKED`；用户停止或普通维护停止 → 照常收尾；新代次启动中 → `RESTARTING`；其余 → `EXITED` |
| `loadSession` 无就绪身份 | 启动中 → `STARTING`，否则 `GENERIC`（保留原文） |
| `onReceivedError` | `ERROR_CONNECT` → `HUNG`，其它 → `NETWORK` |
| `onReceivedHttpError` | 401/403（已自动重新鉴权一次）→ `AUTH`，其它 → `HTTP` |
| 凭据刷新失败 | `AUTH_REFRESH` |
| `onRenderProcessGone` | `RENDER_CRASH` / `RENDER_RECLAIMED` |
| 能力检查失败 / `createWebView` 异常 | `WEBVIEW_OUTDATED` / `WEBVIEW_INIT` |
| 对话地址无效 | `INVALID_ADDRESS`（保留原文） |

## 行为改动（不只是重绘）

1. **对话中途服务退出不再留空白页**。原来 WebView 被销毁，但代次没变，`retireEmptyHost` 不收尾，窗口留空。现在销毁后先经 `showEndedRuntime` 判定：需要说明时停在面板上，否则照原逻辑收尾。
2. **重启预算用尽先说明再离开**。预算用尽时 Controller 自己走停止流程，`WebLifecycle` 也会记为「用户停止」，所以 `ended()` 先判预算再判用户停止。主按钮「查看原因与修复」打开 `StartupRecoveryActivity` 并隐藏对话。
3. **服务结束后的面板每 1.5 秒复核一次**（仅前台、仅 `BLOCKED` / `RESTARTING` / `EXITED`）：新服务就绪时按原 `retireEmptyHost` 交接到新页面；用户停止后照常收尾。结论未变时不重复写诊断记录。
4. **主按钮动作按结论变化**，例如已退出时是「返回启动页」，内核过旧时是「更新 WebView」（打开当前 WebView 提供方的商店页，没有商店时退回网页版）。新增顶栏返回，与系统返回键一致。

## 未做与已知限制

- **退出码不显示在状态行**。`HarnessController` / `WebLifecycleController` 是运行时启动器字节钉输入（`runtime-descriptor.json`），改动会让 UI 更新变成运行时更新，因此不改。退出码只在失败原因原文里，状态行写「已退出」，原文进入「技术详情」。`WebCrashReport.Facts.exitCode` 与退出码解释文案已就位，以后若在不受钉的位置拿到退出码可直接接入。
- **无法区分「卡死」和「刚退出」**：页面只能看到连接被拒绝（`HUNG`）；若随后 `exited()` 到达，就绪身份消失，面板转为 `EXITED`。
- `RESTARTING` 不区分保活自动重启和用户在主界面手动重启，文案按两者通用写。
- 启动前就失败（鉴权前退出、`[DSHA_PAGE] fatal`）仍走 `StartupRecoveryActivity`，该页由另一会话重做，本分支未改。
- 布局预览（`LayoutPreviewActivity`）未接入本面板。

## 已验证（宿主）

- `:app:compileStandardDebugJavaWithJavac` 通过。
- 定向单测 51 项通过：`WebCrashReportTest`（6）、`WebCrashPanelTest`（6，含九种非中文语言逐结论、逐状态行不回落中文）、`WebBackgroundPageLifecycleTest`（5，英文标题断言随新文案更新）、`BrowserTasksTest`、`DirectWebEntryTest`、`WebPreviewPolicyTest`。
- `check-source-hygiene`（刷新 8 份 translations-extra 字节钉）、`check-ui-i18n`（0 新增发现）、`test-ui-language-catalog`、`test-terminal-home-i18n`、`test-ui-i18n`、`test-dsha-ui-regressions`、`localize-android-ui --check`、`format-java --check` 通过。
- 新增 76 条十语文案（`wcrash272a00`–`wcrash272a75`）；其余复用既有条目。

## 未验证

- 未跑 Lint、全量单测、`LayoutAuditInstrumentation`，未上真机。
- 以下判断来自读代码，未在真机复现：原空白页现象、预算用尽时的停止顺序、`RESTARTING` 期间 `isStarting()` 为真、就绪后交接到新页面。

## 统一验收清单

- 日夜、中英文（另抽查阿拉伯语 RTL）、短屏、1.3 倍字体；手机竖横屏、平板竖屏（≥600dp）、平板横屏（≥900dp 两栏）。
- 对话中途让 dsh 退出：显示「DSH 服务意外退出」而不是空白；返回键与顶栏返回都能离开。
- 连续失败 3 次：先显示「已暂停自动重启」，点「查看原因与修复」进入启动恢复页。
- 卡死（端口不通）：先显示「服务暂时没有响应」；保活重启后面板转「正在重新启动」，就绪后自动回到对话。
- 用户在启动页手动停止：对话窗口照常关闭，不出现错误面板。
- App 重开接回：已退出 / 待确认两种面板，页面半透明保留；「重新验证」可恢复。
- 渲染器崩溃 / 内存回收 / 内核过旧（「更新 WebView」能打开商店页）/ 连接错误 / 401 / 5xx。
- 技术详情展开、可选中、复制；TalkBack 读出标题（标题级）、状态行（整行一次读出）、详情展开状态。
- 画中画中出错时的表现与原来一致。
