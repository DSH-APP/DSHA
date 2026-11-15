# 隐藏后台（从最近任务隐藏 DSHA）实现记录

- 日期：2026-11-06（UTC）
- 分支：`feat/hide-recents`，基线 `origin/main` = `9cc91f5e`（PR #3 SAF 已合并）
- 需求：ADB 保活的辅助项，在 设置 → 体验 增加独立开关，把 DSHA 的任务从最近任务隐藏；不改 ADB 通道、常驻通知、前台服务与停止语义。
- 参考：`/tmp/keepalive-feasibility.md` §5–§9（只读评审报告，未入仓库）。

## 1. 行为

| 状态 | 行为 |
|---|---|
| 默认（关） | 与现状一致：只留一张主卡，浏览器 / PiP / 旧 Main 壳按原策略隐藏 |
| 开 | 本应用全部有效任务 `setExcludeFromRecents(true)`；Android 13+ 会保留「最近可见任务」，从应用内直接滑到最近任务时当前卡片仍由系统保留（本开关同时关闭最近任务截图内容），离开应用后不再出现；常驻通知、Web、dsh 与 ADB 保活都不受影响 |
| 开 + 严格模式（实验，默认关） | 应用整体退到后台后结束 UI 任务，当前卡片也不再保留；dsh 进程、终端会话、ADB 保活与常驻通知均不受影响，重新打开会重建界面与网页 |
| 关 | 先全部恢复显示，再按现有单张主卡规则重新分类 |
| App 重启 / 进程被杀后重开 | `MainActivity` 的 `rememberMain`（create / resume）重新读偏好并应用 |
| 应用失败 | 偏好已保存，`DiagnosticLog` 记录 `RECENTS_HIDE_APPLY` / `RECENTS_STRICT_END`，toast 提示，下次打开重试；不崩溃、不重放 |

边界：`DeviceBridgeService` 在主进程，整包被滑卡清掉时 ADB 保活一起失效；本开关只降低「用户滑卡 / 一键清理」这一类风险，不改变进程优先级，不防 Doze、厂商省电或强行停止。开关不检查、不启动、不停止 `DeviceBridgeService`。

## 2. 实现

- `util/Constants.java`：新增 `KEY_HIDE_FROM_RECENTS = "hide_from_recents"`。
- `core/ConfigStore.java`：`isHideFromRecents()` / `setHideFromRecents(boolean)`，`flag(..., false)`；**不加入** `BACKUP_SETTING_KEYS`，与屏幕常亮一样是本机偏好。
- `util/TaskNavigationPolicy.java`：新增 5 参 `recents(..., boolean hideAll)`；`taskId < 0` 仍 `KEEP`，`hideAll` 时其余全部 `HIDE`；4 参重载委托 `hideAll=false`。
- `ui/BrowserTasks.java`：
  - `reconcileRecents` 读取一次偏好并传入策略，返回是否成功；启用时失败写诊断日志。
  - 新增 `applyRecentsPreference(Context)`：枚举本应用全部任务写入偏好值；关闭时再走 `reconcileRecents` 恢复单张主卡分类。
- `ui/SettingsFragment.java`：在「体验」组「省电模式」之后绑定新行；切换即保存 + 立即应用 + 失败 toast；整行可点。
- `res/layout/fragment_settings.xml`：新行 `settings_hidden_recents`（沿用 `ShellRow` / `ShellRowIcon` / `ShellRowTitle` / `ShellRowSub` / `ShellSwitch`）。
- `res/drawable/ic_ui2_hide_recents.xml`：20dp、24 viewport、`text_secondary` 描边的眼睛 + 斜杠图标，风格对齐 `ic_ui_keep_screen_on.xml`。
- 文案：`tools/i18n/messages.json` 新增 `m9025`–`m9028`，8 个 `tools/i18n/translations/*.json` 补齐十语。
- `app/src/main/assets/runtime-descriptor.json`：`ConfigStore.java` 属于 descriptor 的 `launcherSources`，改字节后按 `tools/prepare-runtime-descriptor.py --write` 重算 `runtimeId` 与该文件摘要。
- 未改：`AndroidManifest.xml`（无静态 `excludeFromRecents` / alias）、`HarnessService`、`DeviceBridgeService`、`BootReceiver`、`AdbKeepAliveReceiver`、`RuntimeTasks`、备份白名单、版本与发布文件。

## 3. 测试与门禁回执（2026-11-06）

- `dsha-gradle :app:testStandardDebugUnitTest --tests '*TaskNavigationPolicyTest' --tests '*BrowserTasksTest' --tests '*ConfigStoreHideFromRecentsTest'`：**通过**（TaskNavigationPolicyTest 5、BrowserTasksTest 43、ConfigStoreHideFromRecentsTest 3，0 失败 / 0 错误）。
- `dsha-gradle :app:compileStandardDebugJavaWithJavac`：**通过**。
- `python3 tools/check-ui-i18n.py`：`newFindings=0`，无 `NEW_UI_I18N`。
- `python3 tools/localize-android-ui.py --resources-only --check`、`python3 tools/test-ui-language-catalog.py`：**通过**。
- `python3 tools/check-source-hygiene.py`：**通过**（含刷新 `translations/{ar,fr,ja,ko,pt,ru}.json` 的语言字节钉）。
- `python3 tools/format-java-tree.py --check --java /usr/lib/jvm/java-17-openjdk-arm64/bin/java`：1536 个受管 Java，0 需格式化。
- `git diff --check`：**通过**。
- 首次构建需要按 `ci/sunset-inputs.lock.json` 还原固定输入（本机用 `tools/ci-assets.py --archive ... --sha256 ...`，`PASS locked CI asset inputs`）。

## 4. 未验证 / 风险

- **布局人工验收未做**：日 / 夜、360dp、1.3 倍字体、中 / 英 / 阿拉伯语 RTL 的截图核对未执行；`androidTest` 源集存在既有编译错误（`RecoveryInstrumentation.java` 调用 `HarnessService.refreshPowerMode()` 少传 `Context`），`ThemeInstrumentation` / `LayoutAuditInstrumentation` 不能作为本分支门禁。
- **真机功能未验证**：未构建 / 安装 `io.github.sunsetrne.dsha.test`，未在设备上核对最近任务卡片、通知、PiP、重启恢复与 OEM 行为；本机也不得覆盖安装正式包。
- **ROM 差异**：个别 ROM 可能忽略 `AppTask.setExcludeFromRecents` 或在当前前台任务上延迟生效；失败路径会保存偏好并重试，但无法保证所有厂商一致。
- **非保活承诺**：不防 Doze、厂商省电、强行停止或系统回收；文案与文档都不把它写成「后台保活」。

## 5. 建议验收步骤（测试包，需用户操作手机）

1. 默认关 → 最近任务显示 DSHA 卡片；打开开关 → 同一任务立即消失、常驻通知仍在、dsh Web 可访问；
2. 关闭开关 → 卡片恢复，重复 Main / 浏览器任务仍只留一张主卡；
3. 强杀 App 后从桌面图标重开 → 偏好仍生效；
4. 通知「回到网页」、Main ↔ Web、PiP、返回主界面正常；
5. 记录至少一台 OEM ROM（如本机 Lenovo Android 16）的结果，不外推到全部厂商。

## 6. T124 真机复核与修正（2026-11-09）

### 6.1 复核结论：平台保留最近可见任务

一加 ColorOS 16（Android 16，build345）用户实测：开启「隐藏后台」后，**按 Home 离开再从最近任务打开看不到 DSHA**（排除已生效），但**从 DSHA 内直接手势滑到最近任务时仍能看到当前卡片**。这不是 T111 的生产接线漏了，而是 Android 13+ 平台行为：

- AOSP `RecentTasks#isInVisibleRange` 明确「Keep the most recent task of home display even if it is excluded from recents」：带 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` 的任务只要仍是**最近可见任务**（`getTopVisibleActivity() != null`）就保留；进入后台后才排除。Android 11/12 没有这段逻辑。
- 参考同类项目 Stellar 的实现是**同一个 API**（`ActivityManager.appTasks.forEach { it.setExcludeFromRecents(hidden) }`，`BackgroundVisibilityUtils.kt`），没有额外手段，因此同样受这条平台规则约束。
- 公开 API 没有「任务活着但从最近任务移除」的能力：`AppTask.finishAndRemoveTask()` 会结束 UI；`WindowContainerTransaction#setTaskForceExcludedFromRecents` 是 SystemUI 气泡用的内部能力（Android 16.1 / SDK 36.1 才加入，本机 Android 16 framework 里没有该符号），普通应用与 shell 都不可用；无障碍 / 悬浮窗也盖不住系统最近任务界面。要真正移除当前卡片只能做系统注入（Xposed 类模块），不进入本仓库。

### 6.2 修正内容

- **内容守卫**：`ui/RecentsPrivacyGuard`（Application 级 `ActivityLifecycleCallbacks`）在开关开启时对全部界面调用 `setRecentsScreenshotEnabled(false)`（API 33+），当前卡片被系统保留时不显示聊天/网页内容；开关切换立即对存活界面重放。
- **严格模式（实验，默认关）**：同一守卫在应用整体退到后台 700ms 后结束 UI 任务（`finishAndRemoveTask`），让当前卡片也不再保留；SAF 等结果型外部选择器经 `RecentsPrivacyGuard.launch(...)` 启动，在途时不结束任务，避免结果回调随界面丢失；画中画 / 多窗口 / 旋转不触发。dsh 进程、终端会话、ADB 保活、常驻通知不受影响。
- **假成功校验**：`BrowserTasks.applyRecentsPreference` 刷完立即回读 `getAppTasks()` 的 root Intent 标志，任务列表为空或标志不符时写 `DiagnosticLog` 并返回失败，不再把「枚举不到任务」当成功。
- **设置页**：主开关补副标题（离开后移除、当前卡片不显示内容）；新增「严格模式（实验）」子行，父开关关闭时隐藏。
- **文案**：`tools/i18n/messages.json` 新增 `m9500`–`m9502`，8 个翻译文件补齐十语；语言字节钉同步刷新。
- **运行时描述**：`ConfigStore.java` / `DshaApp.java` 属于 descriptor `launcherSources`，已重算 `runtimeId`（T124 分支原为 `20c94c58…`，合入 build346 基线后为 `28dc573a…`，T128 插件 launcher 源变化后最终为 `5fec6de5…`，以仓库内 `app/src/main/assets/runtime-descriptor.json` 为准），合入/发版须带该文件。

### 6.3 门禁与验证

- 定向单测：`RecentsPrivacyPolicyTest`、`RecentsPreferenceContractTest`、`ConfigStoreHideFromRecentsTest`、`BrowserTasksTest`、`TaskNavigationPolicyTest`（结果见 `.SESSION-DONE`）。
- `check-ui-i18n.py`、`localize-android-ui.py --resources-only --check`、`test-ui-language-catalog.py`、`check-source-hygiene.py`、`format-java-tree.py --check`、`prepare-runtime-descriptor.py --check` 全部通过后交付。
- **未验证**：一加 C16 真机（需要测试包）：从应用内直接滑到最近任务看当前卡片内容是否空白、按 Home 后再看是否消失、严格模式开启后卡片是否消失且 dsh Web / 终端 / ADB 保活仍在。测试包 `io.github.sunsetrne.dsha.test` 由用户安装，不在本机覆盖正式包。
