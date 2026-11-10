# 插件列表检测与 DSH/终端准入解耦实施记录（Phase 1+2 核心）

日期：2026-10-27（Asia/Shanghai）  
授权范围：用户确认按 Phase 1+2 核心实施；只运行 Standard 源码检查/测试/构建，不安装、不启动/停止手机应用、不发布/上传。  
基线：`Sunset-3090-Q`，实施前 HEAD `5d694b65f5ceca9ccbd3d854a2b1efbacd53c159`，已跟踪工作区无修改。

## 1. 已实施改动

### 1.1 环境租约拆成共享/独占两态

`app/src/main/java/com/deepseekharness/app/util/EnvironmentTaskGate.java`

- 新增 `tryAcquireShared(kind)`、`sharedCount()`、`hasSharedUse()`、`sharedKinds()`、`ownsExclusiveCurrentThread()`。
- `tryAcquire()`（独占）只在没有独占租约且没有共享使用者时成功。
- `tryAcquireShared()` 只与独占租约互斥，多个共享使用者可并存。
- `isBusy()` 仍表示“独占环境忙”，因此查询/启动/终端创建不会被 UI 显示成维护任务。
- `retainCurrentUntilExit()`、`canExecuteCurrentThread()`、维护凭据只接受独占租约。
- 共享租约不承担进程退出等待；进程寿命仍归 `RuntimeTasks`。
- `MaintenanceCoordinator` 新增 `isExclusiveWorkBlocked()` / `activeEnvironmentDescription()`，安装页、备份任务等独占动作仍能正确显示“查询/启动占用环境”，不会因共享查询放开而误导为可立即开始。

迁移：

- `MaintenanceCoordinator` 的执行凭据所有权改用 `ownsExclusiveCurrentThread()`。
- `AdbEnvironmentTask` 的嵌套判断改用独占所有权，避免共享租约被误当 ADB/维护凭据。
- 备份、恢复、安装、诊断、配置修复、插件提交/安装/删除/更新、插件加载确认继续使用独占租约。

### 1.2 插件查询不再持有全局独占租约

`app/src/main/java/com/deepseekharness/app/core/PluginRepository.java`

- `executeRequest()` 按请求类型选择：`work.query()` → `tryAcquireShared("插件查询")`；其余 → `tryAcquire("插件操作")`。
- 查询租约覆盖来源解析、脚本执行、结果处理，但不再阻塞 DSH 启动或终端创建。
- `probeSource()` 使用共享租约保护只读来源核对，避免与安装/备份/插件提交并发。
- `refreshRuntimeProjection()` 改用共享租约，不再占用独占环境任务。
- 非查询操作仍独占；共享使用者存在时独占获取失败，不等待、不旋转。

### 1.3 自动查询让路

`PluginRepository`：

- 查询请求增加 `automatic` 标记；`refresh()` = 用户/页面需求，`refreshAutomatic()` = 后台预取。
- 新增 `isQueryRunning()`、`hasPendingAutomaticQuery()`、`cancelAutomaticQueryForUserAction()`；只取消自动查询，不触碰用户显式刷新或正在提交的插件修改。

`PluginManagementEngine` / `Coordinator`：

- 新增 `yieldToUserAction()`：取消自动查询、最多等待 3 秒退出，并设置 120 秒自动查询暂停窗口。
- `issueQuery(boolean automatic)`：暂停窗口内跳过自动查询，但页面需求/显式刷新仍可立即读取。
- 前台恢复仍保留轻量来源核对与既有预取逻辑；用户启动时由启动流程显式让路。

`StartupPipeline` / `StartupEnvironmentActions`：

- 新增 `Actions.yieldPluginQuery()`（默认 no-op），`prepare()` 在插件脚本/迁移前调用。
- `StartupEnvironmentActions` 实现为调用 `PluginManagementEngine.yieldToUserAction()`。

### 1.4 脚本队列优先用户启动/修改

`app/src/main/java/com/deepseekharness/app/runtime/GuestScriptQueue.java`

- 等待者分高/普通两队列：高优先级可以跳过等待中的普通查询，但不抢占正在运行的脚本。
- 高优先级等待仍可由 `GuestScriptBudget` 设上界。

`GuestPluginScripts`：

- `startup()` / `builtin()` 使用高优先级、35 秒等待预算；超时明确失败，不启动一半环境。
- `manager()` 查询走普通优先级；非查询（启停、安装、更新等）走高优先级。
- 查询准备改为 `preparePluginsForQuery()`：只做环境就绪 + 只读工具校验，不执行 Python/pnpm/受管资产修复。

### 1.5 查询只读准备

`RuntimeTools.queryReady(context, rootfs)`：

- 只读校验运行描述符身份、`managed-assets-v2` marker、Python 入口/动态库、插件管理器及其依赖脚本；
- 不调用 `prepare()`、`RootfsInstaller.python()`/`pnpm()`、不写临时目录/覆盖层/marker；
- 缺失或身份不一致返回 false，由上层转“需要环境准备/维护”。

`GuestPluginScripts`：

- `Preparation.queryReady()`；生产实现调用 `RuntimeTools.queryReady()`。
- 查询路径只调用 `environmentReady()` + `queryReady()`；完整准备仍属于启动、迁移和修改路径。
- `TarGzipExtractorTest` 的“完整准备超时/取消”用例改用 `import fixture` / `enable-state` 变更路径，并断言 `PLUGIN_INTENT_TIMEOUT` / `PLUGIN_INTENT_CANCELLED`；查询路径不再承担完整准备，这是新契约。
- 新增 `PREPARATION_REQUIRED` 失败码，`PluginRepository.failureMessage()` 映射为“运行工具或资产不完整，请先到「安装与修复」完成环境准备；上次插件列表已保留”。

`app/src/main/assets/plugin-manager.py`：

- 新增 `READ_ONLY`（`DSHA_PLUGIN_QUERY=1`）。
- 查询模式不 `os.makedirs(DSH_HOME)`；目录缺失明确失败。
- 查询模式不写进度文件；取消仍由 `DSHA_PLUGIN_TASK` 的取消文件和 Python `check_cancel()` 协作处理。
- 保留 `cmd_list()` 的 manifest/来源/激活状态前后核对；变化时拒绝混合列表。

### 1.6 DSH 生命周期与终端准入

`WebLifecycleController`：

- `requestStart()` 与 `retryWithProot()` 改用共享租约；启动仍保留 generation、停止意图、鉴权、端口、前台服务准入和进程退出证明。
- 独占维护/安装/备份仍会挡住启动；Dsh 启动不再因插件查询被拒绝。

`MainActivity.openTerminal()`：

- 移除打开终端页面前的 `terminalBlockMessage()` 检查；查看/切换已有会话不再依赖插件列表或普通环境任务。
- 仍保留 `EnvironmentAccess.needsRecovery()` 的启动恢复分流。

`SimpleTerminalBackend`：

- `open()`/`write()` 改共享租约；`blockMessage()` 仍拒绝 pending maintenance / 独占维护。
- 终端寿命仍由 `RuntimeTasks`/`TerminalRuntimeProtection`/退出核验持有。

`PtySession.start()`：

- 在 `RuntimeTasks.beginDetached("终端")` 之后、fork 之前获取共享租约，覆盖 `requireUserRuntime()`、`ensureAndroidGroups()`、`ptyLaunch()` 和 `initializeOnMain()`；
- 结束后释放共享租约；进程寿命仍归 RuntimeTasks/PTY 退出核验。

### 1.7 生成输入

- 因修改 `plugin-manager.py`、`PtySession.java`、`PluginRepository.java`、`EnvironmentTaskGate.java` 等运行输入，重新生成：
  - `app/src/main/assets/runtime-descriptor.json`
  - `app/src/main/assets/managed-package-proofs.json`（本地固定输入归档对齐）
- `prepare-runtime-descriptor.py --check` 与 `prepare-backup-assets.py --check` 均通过。

## 2. 验证结果

### 2.1 已通过

| 项 | 结果 |
|---|---|
| `:app:compileStandardDebugJavaWithJavac` | 通过 |
| `:app:compileStandardDebugUnitTestJavaWithJavac` | 通过 |
| `:app:verifyRuntimeDescriptorInputs` | 通过（85 项源输入/181 项契约/438 项 package 文件） |
| `prepare-backup-assets.py --check` | 通过（624 proofs） |
| `prepare-runtime-descriptor.py --check` | 通过 |
| 纯 JVM 定向 JUnit | **122 tests / 0 fail** |
| Robolectric 定向 JUnit（本机 aarch64 适配参数） | **64 tests / 0 fail** |
| Gradle Standard 全量 JUnit（本机临时资源工具链） | **2792 tests / 6 fail / 1 skip**；6 项失败均为环境/夹具问题 |
| `:app:lintStandardRelease`（临时工具链） | 通过，**0 errors / 376 warnings** |
| `:app:assembleStandardDebug`（临时工具链） | 通过；debug APK 441,624,563 bytes，SHA `e370f920...`；不是发布 APK |

Robolectric 定向 JUnit：

- `PluginRepositoryQueryLifecycleTest`：32 tests
- `WebLifecycleResponsivenessTest`：11 tests
- `WebLifecycleStartProtectionTest`：11 tests
- `TerminalSessionOwnerTest`：10 tests

本机运行参数：`--add-opens=java.base/java.io=ALL-UNNAMED`、`-Drobolectric.conscryptMode=OFF`；未修改测试源文件。  
`conscryptMode=OFF` 只用于绕开 aarch64 上仅有 x86_64 native 的 conscrypt；本次目标用例不验证 TLS provider 行为，不能用该参数替代 CI 的默认 Robolectric 配置。

纯 JVM 定向 JUnit 类：

- `EnvironmentTaskGateTest`（含新增共享/独占测试）
- `GuestScriptQueueTest`
- `GuestPluginScriptsTest`（查询只读准备路径）
- `PluginManagementEngineTest`（含自动查询暂停/页面需求测试）
- `MaintenanceGateIntegrationTest`
- `MaintenanceGateTest`
- `TerminalRuntimeProtectionTest`
- `PluginOperationQueueTest`
- `PluginChangeCoalescerTest`

### 2.2 未完成/受环境限制

- 全量 Gradle Standard JUnit 已实际执行：**2792 tests / 6 fail / 1 skip**。
  - 5 项 `RuntimeToolsRegistryAndroidTest`：`Current runtime archive must match the fixture`。本机 `app/build/test-runtimes` 夹具 recipe 与当前源码/固定输入不一致（差异文件为 `language-patch.json`、`web-integration/language.js`、`tools/runtime_stable_patches.py`，均非本次改动）；属于本地夹具/固定输入问题。
  - 1 项 `RemoteHttpGatewayTest.fiveBillionBytesTravelBothWays...`：180 秒超时；属于本机长流 I/O 环境限制。
  - 本次改动相关测试类均通过。
- 本次全量 Gradle 运行使用临时本机工具链适配：Android 34 资源表合并到 Android 37 classes jar、arm64 AAPT2 2.19、Gradle init script 设置 `robolectric.conscryptMode=OFF`。运行后已恢复原始 Android 37 `android.jar`；仓库没有提交这些临时修改，不能替代 CI 默认工具链。
- 依赖编译后资源的 UI/布局用例已随全量 Gradle 运行；仍需在正常 CI 工具链复核。
- `lintStandardDebug` 有 1 个既有 debug-only 错误：`app/src/debug/.../StartupRecoveryAudit.java:199` 调用了 API 33 的 `String.lines`（minSdk 30），与本次改动无关；Release Lint（CI 门禁）已通过。
- `assembleStandardRelease`、签名发布 APK、CI、真机均未运行；`assembleStandardDebug` 使用本机临时资源工具链并跳过资产准备任务，产物只作构建链证据，不是可交付 APK。
- 本机临时工具链适配（合并 Android 34 资源表、arm64 AAPT2 2.19、AIDL shim、init script）均在运行后恢复；仓库没有提交这些环境修改。
- 未安装、未启动/停止手机应用、未发布/上传。
- 未采集设备计时/日志，不能声明性能提升百分比。

### 2.3 仍需在 CI/标准环境补跑

1. `bash build.sh :app:testStandardDebugUnitTest` 全量 Standard；
2. 相关 Robolectric 用例：`PluginRepositoryQueryLifecycleTest`、`WebLifecycleResponsivenessTest`、`WebLifecycleStartProtectionTest`、`MaintenanceGateIntegrationTest`（本机手工与全量 Gradle 均已通过，仍需正常 CI 工具链复核）；
3. Release Lint；
4. `assembleStandardRelease` 或当前分支 CI 等价构建；
5. 若交付 APK，绑定实际 commit + APK 摘要 + 设备验收，另行授权。

## 3. 已知边界与风险

1. **维护屏障没有新增“取消自动查询”步骤**：查询/启动/终端持有共享租约时，备份/安装/维护的独占获取仍会失败（与改动前查询持有独占时的失败行为一致）。后续若要求维护主动排空查询，需要增加维护到查询控制面的取消接口；本次未做。
2. **DSH 启动让路是协作取消 + 有界等待**：若自动查询在 3 秒内未退出，启动仍会在高优先级脚本队列等待其最多 35 秒预算；不会启动一半环境，但用户可能看到等待。
3. **用户显式刷新不取消**：若用户在启动前主动刷新且查询未结束，启动可能等待该查询；这是“用户主动查询不被静默取消”的取舍。
4. **PTY/简易终端共享租约只覆盖创建/fork 窗口**：终端寿命仍由 RuntimeTasks 与退出核验保护；备份/安装不会因终端运行而永久阻塞，维护仍通过 `stopTerminals` 处理。
5. **查询只读 readiness 依赖运行描述符身份**：APK/运行资产未对齐时列表返回“需要环境准备”，保留旧快照；不会在查询中隐式修复。
6. **未做真机/OEM 文件事件/慢 I/O 验证**：共享租约的并发正确性目前由源码调用链、编译和纯 JVM 测试支持，仍需 CI 全量 + 设备验证。

## 4. 回滚

- 按文件组回退：
  1. 生成输入 `runtime-descriptor.json`/`managed-package-proofs.json`；
  2. 查询只读准备（`RuntimeTools`/`GuestPluginScripts`/`plugin-manager.py`/测试）；
  3. 共享/独占租约与消费者迁移（`EnvironmentTaskGate` 及入口）；
  4. 自动查询让路/队列优先级。
- 回退不涉及用户插件数据格式、包名、签名、终端内核或 Ubuntu 基础环境。
- 若共享租约在 CI/设备上暴露未知退出或一致性问题，回到独占门禁版本，保留现场，不清锁、不重放。
