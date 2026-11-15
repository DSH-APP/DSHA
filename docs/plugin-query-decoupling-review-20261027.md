# 插件列表检测与 DSH/终端准入解耦复核

日期：2026-10-27（Asia/Shanghai）  
性质：复核当时为只读源码分析；**用户随后已授权 Phase 1+2 核心实施**，实际改动与验证见 [实施记录](plugin-query-decoupling-implementation-20261027.md)。本文件保留复核时点的结论与风险清单。

## 0. 结论摘要

1. 报告中“插件查询、DSH 启动、终端入口被同一个全局独占门禁串行化”的源码判断，在当前 HEAD 上成立（B 级证据）。
2. 当前代码比报告基线更复杂：已有 `PluginManagementEngine`、变更合并器、单消费者插件队列、查询预算/取消、来源代次校验。实施不能回退这些能力。
3. 不能只删除 `PluginRepository` 的外层门禁：`GuestScriptQueue`、`RuntimeTools.prepare`、`RootfsInstaller.python/pnpm` 仍会形成等待或写入；其中查询前的宿主准备并非只读。
4. 建议的核心改动是引入**共享/独占两态环境租约**，而不是三个交互 shell：
   - 插件查询、DSH 启动/兼容重试、终端创建使用共享租约；
   - 插件提交/安装/备份/恢复/配置修复/运行环境替换继续使用独占租约；
   - 维护屏障保留，并增加“先取消可退出的后台查询，再有界排空”的步骤。
5. 同时必须提供**查询只读准备路径**：工具或运行资产不完整时返回“需要环境准备/维护”，不得在列表刷新里隐式修复。
6. 实施顺序建议：Phase 0 诊断 → Phase 1 自动查询让路 → Phase 2 共享/独占租约与只读查询（核心）→ Phase 3 可选会话展示。Phase 1 单独完成不能声称“彻底解耦”。

## 1. 基线与范围

| 项 | 当前事实 |
|---|---|
| 仓库 | `<workspace>/DSHA-Sunset-mod-3090-Q` |
| 分支 | `Sunset-3090-Q`（跟踪 `origin/Sunset-3090-Q`） |
| HEAD | `5d694b65f5ceca9ccbd3d854a2b1efbacd53c159`（2026-10-27 00:29:56 UTC） |
| 工作区 | 已跟踪文件无修改；无未提交改动 |
| 版本 | `app/build.gradle`：applicationId `dsh.android.client`，versionCode 203，versionName `0.2.1-rc.3-20261027.0700-dsh0.2.1-rc.3` |
| 分支约束 | 只验证 Standard，不运行 Low |
| 与报告基线关系 | 报告基线 `fc3998d…`；`fc3998d..HEAD` 在本报告相关生产路径（core/plugins/runtime/util/EnvironmentTaskGate/plugin-manager.py）**无生产代码差异**，仅测试、版本与交付文档变化 |
| 未核对 | 用户实际体验的新独立包 APK、安装版本与源码对应关系；本次没有设备日志、计时或真机操作 |

本报告的证据等级：

- **B 级**：源码调用链可重复核对；
- **C 级**：根据代码结构推断的运行时行为；
- **D 级**：待设备/日志/性能实测确认。

## 2. 调用链复核

### 2.1 插件查询确实申请全局独占环境凭据

- `PluginRepository.executeRequest()`：
  - 先调用 `environmentBlockOnWorker(controller)`；该方法把 `MaintenanceCoordinator.isEnvironmentTaskBusy()`（含 `EnvironmentTaskGate.isBusy()`）当作拒绝条件（`PluginRepository.java:1047`、`:1224-1229`）。
  - 随后 `EnvironmentTaskGate.tryAcquire("插件操作")`（`:1049-1050`）。
  - 查询 `refresh` 也走同一路径：`refresh()` → `withItems("refresh", true)`（`:1337-1339`）→ `executeRequest()`；租约覆盖来源解析、脚本执行、结果处理和收尾（`:1100-1117`、`:1180`）。
  - 查询本身有 30 秒预算与取消文件：`PendingWork.queryBudget`（`:868-872`）、`GuestScriptBudget`（`GuestScriptBudget.java:8`、`:23-30`）。
- `PluginRepository.probeSource()` 也先走 `environmentBlockOnWorker`（`:1869-1877`）。
- `PluginRepository.refreshRuntimeProjection()` 也申请 `EnvironmentTaskGate.tryAcquire("插件运行状态读取")`（`:1896`），虽然它只读运行快照。
- `PluginBackendAdapter.resolveSource()` / `environmentBlockMessage()` 在来源解析前再次检查 `EnvironmentTaskGate.isBusy()`（`PluginBackendAdapter.java:111-123`、`:129-170`）。

**结论**：查询不是“只读旁路”，它持有同一把进程级独占租约。

### 2.2 DSH 启动会被该门禁拒绝

- `WebLifecycleController.requestStart()` 在启动准入中两次检查 `MaintenanceCoordinator.isEnvironmentTaskBusy()`（`:796-800`、`:832-836`）。
- 随后 `EnvironmentTaskGate.tryAcquire("启动 Web")`（`:841-848`）；失败时直接回调“已有环境任务启动，请等待完成后重试”，不是等待队列。
- 租约被转移到 `io` 启动 worker，覆盖 `startWeb()`（`:883-909`）。
- `retryWithProot()` 也重新申请独占租约（`:1246-1266`）。
- 启动内部的插件注册/迁移/激活核验是真实写操作：
  - `StartupPipeline.prepare()` 在 `safeMode=false` 时执行 `startupPlugins()`、`register()`、`legacy()`（`StartupPipeline.java:286-316`）；
  - `StartupEnvironmentActions.beforeLaunch()` → `PluginActivationHooks.beforeLaunch()`（`StartupEnvironmentActions.java:63-65`）；
  - `PluginActivationHooks.finish()` 还会申请 `EnvironmentTaskGate.tryAcquire("插件加载确认")`（`PluginActivationHooks.java:101-125`），失败时最多重试 40 次。
- `StartupPipeline` 的启动插件准备走 `GuestPluginScripts.startup()` → `SCRIPT_QUEUE.enter()`（`GuestPluginScripts.java:118-127`），而查询的 `manager()` 也走同一个 `SCRIPT_QUEUE.enter(budget)`（`:294`）。

**结论**：DSH 启动失败/等待至少有两条独立原因：外层全局租约，以及底层共享脚本队列。只拆外层仍可能让启动排在扫描后面。

### 2.3 终端入口与新建受同一忙状态影响

- 打开终端页面：`MainActivity.openTerminal()` 在启动 `TerminalActivity` 前调用 `TerminalFragment.terminalBlockMessage(controller.proot())`，非空则只弹 Toast 并返回（`MainActivity.java:660-693`）。`terminalBlockMessage()` 直接转 `SimpleTerminalBackend.blockMessage()`（`TerminalFragment.java:315-317`）。
- `SimpleTerminalBackend.blockMessage()` 检查 `MaintenanceCoordinator.isEnvironmentTaskBusy()`（`SimpleTerminalBackend.java:124-135`），因此插件查询也会挡住“打开终端页面”，不只是新建。
- 简易终端新建：`TerminalFragment.newSession()` 先 `refuseDuringMaintenance()`（`:168-228`、`:319-327`）；实际 `open()` 再 `acquire()` 独占租约（`SimpleTerminalBackend.java:29-42`、`:108-116`）。
- PTY 新建：`PtyTerminalFragment.newSession()` 先查 `terminalBlockMessage()`（`:126-133`），但 PTY 本身在 `PtySession.start()` 里只登记 `RuntimeTasks.beginDetached("终端")`（`PtySession.java:110-119`），并不申请 `EnvironmentTaskGate`。所以 PTY 的阻塞主要是入口预检，简易终端是入口预检加真实独占租约，两者不对称。
- 已有终端页面/会话本身不依赖插件列表；真正被挡住的是 `MainActivity` 的入口。会话生命周期由 `TerminalSessionOwner` / `TerminalRuntimeProtection` / `RuntimeTasks` 持有。

**结论**：报告的方向正确，但应分别修：
1. 打开页面/查看已有会话：不应被任何普通环境任务挡住；
2. 新建 PTY：去掉过宽预检，用共享租约保护 fork 窗口；
3. 新建简易终端：去掉独占租约，保留维护/环境就绪检查和 `RuntimeTasks` 寿命保护。

### 2.4 自动插件核对由 App 前台生命周期触发

- `DshaApp.PluginLifecycle.onActivityResumed()` 在主消息队列 idle 时调用 `pluginManagementEngine().onForeground()`（`DshaApp.java:299-309`）。
- `PluginManagementEngine.onForeground()` 调 `coordinator.foreground(true)`（`PluginManagementEngine.java:197-205`）。
- `Coordinator.foreground(true)` 调 `reconcile()` 与 `scheduleScan()`（`:512-539`）；`reconcile` 在空闲时 `probe()`（`:547-557`、`:639-657`）；`acceptProbe` 在缓存不新鲜且可重试时会 `issueQuery()`（`:710-721`）。
- 插件页面 `PluginFragment.onResume()` 仅在“已安装”标签调用 `ensureLoaded()`（`PluginFragment.java:99-105`、`:294-298`），但应用级前台核对对所有页面生效。

**结论**：用户即使没有进入插件页面，前台恢复也可能触发查询；这与体验描述一致。

### 2.5 Python 列表扫描是只读设计，但宿主准备不是

- `plugin-manager.py` 的 `refresh/list` 直接 `cmd_list()`（`plugin-manager.py:1180-1184`、`:1282-1284`）。
- `cmd_list()` 使用 `list_state_revision()` 做前后版本核对（`:973-1022`），读取 manifest/sources/updates/safe-mode/activations 和目录状态；发现变化时抛错，不发布混合列表（`:1105-1107`）。
- 但 `main()` 在分发前执行 `os.makedirs(local(DSH_HOME), exist_ok=True)`（`:1172-1174`），`progress()` 会写任务进度文件；这些是受控任务文件，不是插件配置，但严格说仍不是“零写入”。
- 宿主外层 `GuestPluginScripts.preparePlugins()` 在查询前总会执行：
  - `preparation.environmentReady()`；
  - `preparation.pythonReady()` → `RootfsInstaller.python()`（可能解压、`setExecutable`、写版本标记，`RootfsInstaller.java:41-88`）；
  - `preparation.pnpm()` → `RootfsInstaller.pnpm()`（可能解压、写 wrapper，`:91-142`）；
  - `preparation.assets()` → `RuntimeTools.prepare()`（临时目录、DNS/resolver、受管覆盖层、marker 等写入，`RuntimeTools.java:33-101`）。
- 即使工具已就绪，`RuntimeTools.prepare()` 在早退前仍先执行 `RuntimeTemporaryDirectories.prepare()` 和 `prepareResolver()`（`:44-51`）；`RootfsInstaller.python()` 会 `setExecutable` 并写版本标记（`:70-86`）；`pnpm()` 总会写 wrapper（`:132-140`）。

**结论**：不能只删除 Java 层门禁就声称“查询只读”。必须新增只校验、不修复的查询准备路径；否则查询与启动并发时仍可能写共享运行资产。

### 2.6 维护屏障与脚本队列

- `MaintenanceCoordinator.isEnvironmentTaskBusy()` = `MaintenanceGate.isExclusive() || EnvironmentTaskGate.isBusy()`（`MaintenanceCoordinator.java:35-37`）。
- `MaintenanceGate.exclusive()` 先拿执行凭据，再 `stopTerminals()`、`stopWeb()`，然后最多 3 秒尝试 `RuntimeTasks.tryEnterMaintenance()`（`MaintenanceGate.java:79-120`）。
- `RuntimeTaskRegistry.tryEnterMaintenance()` 只要存在其他/异步任务就返回 null（`RuntimeTaskRegistry.java:57-61`）。
- 查询在 `PluginRepository.executeRequest()` 中 `RuntimeTasks.beginDetached("插件管理")`（`:1051`）。因此即使查询不再持有全局独占租约，维护也会因这个 detached 任务在 3 秒围栏内失败；必须让维护先取消可退出的查询，或有界等待其退出（报告 6.C）。
- `GuestScriptQueue` 是进程级单活动脚本队列（`GuestScriptQueue.java:21-52`），只被 `GuestPluginScripts` 使用；启动注册、查询、插件变更都共用它。只拆外层门禁后，慢查询仍可能让启动在队列上等待。

### 2.7 现有测试与文档约束

- `PluginRepositoryQueryLifecycleTest` 明确断言查询/任务结束后 `EnvironmentTaskGate.isBusy()==false`，并用 `EnvironmentTaskGate` 租约模拟其它环境任务（如 `:547-568`）。
- `WebLifecycleResponsivenessTest` 用 `EnvironmentTaskGate.tryAcquire("ADB reconnect")` 验证启动前拒绝（`:192-200`）。
- `MaintenanceGateIntegrationTest` 验证 detached token 阻止维护、维护围栏拒绝外来/异步任务（`:15-102`）。
- `docs/plugin-management-modularization-20261025.md:32` 记录上一轮刻意保留 `EnvironmentTaskGate → RuntimeTasks → 维护屏障` 顺序。本次改动会改变这个顺序假设，必须同步修订文档和测试。
- `docs/plugin-management-modularization-20261025.md:49` 记录插件队列“等待不占 EnvironmentTaskGate/RuntimeTasks”；但实际执行期仍占用，正是本次要拆的部分。

## 3. 与报告结论的差异与修正

| 报告表述 | 复核结果 |
|---|---|
| 查询与修改都经过全局门禁 | 成立；且查询先经过 `environmentBlockOnWorker` 的忙状态拒绝，再申请租约 |
| DSH 启动会被拒绝 | 成立；是 fail-fast，不是排队/死锁 |
| 终端入口经过 `terminalBlockMessage` | 成立；但 PTY 只在入口预检，简易终端还真实持有租约；两者要分别处理 |
| Python 列表扫描只读 | Python 列表核对是只读设计；但 `main()` 有目录创建/进度写入，宿主准备链有真实写入；不能直接并发 |
| 自动核对由前台触发 | 成立；当前是应用级前台 reconcile + 可能的 query，不只插件页 |
| 只拆外层门禁即可 | 不成立；`GuestScriptQueue` 与只读准备链仍需处理 |
| 延迟几秒/隐藏提示可解决 | 不成立；需要资源边界与准入语义变化 |

## 4. 建议的改动点

### Phase 0：基线与诊断（先做，可独立回退）

1. 在 `StartupDiagnostics` / `DiagnosticLog` 增加分段计时（脱敏）：
   - 用户请求 → 准入；
   - 脚本队列等待；
   - 查询准备（只读校验/完整准备）；
   - Python 扫描；
   - 结果解析/提交；
   - 取消请求 → 实际退出；
   - 进程/guest 退出清理。
2. 每次阻塞记录：阻塞类型、资源、是否持有写权限、任务身份、环境代次、执行/待清理状态。
3. 不输出 token、API Key、完整敏感环境变量；不把日志写入公开取证文件。

### Phase 1：自动查询让路（低风险，但不足以彻底解耦）

1. `PluginManagementEngine.Coordinator.foreground(true)` 不再无条件 `scheduleScan()`；前台恢复只标记“需要核对”（probe/失效），完整查询由插件页 `ensureLoaded()`/显式刷新或空闲窗口按需触发。
2. `acceptProbe` 在 `demand==false` 时不要仅因缓存不新鲜就 `issueQuery()`；保留快照并标记 stale。
3. `DshaApp.PluginLifecycle` 仍保留前后台补偿，但补偿只做轻量来源核对；不做周期全量查询。
4. 用户 DSH 启动/终端新建时：
   - 停止派发新的自动查询；
   - 对已派发但可退出的自动查询发取消，等待有界退出；
   - 不取消用户显式刷新（除非用户动作需要独占资源）。
5. 状态文案区分：未准入 / 已接受待执行 / 已开始 / 已取消 / 结果未知；不要只显示“环境忙”。

### Phase 2：共享/独占环境租约 + 只读查询（核心交付）

#### 2.1 `EnvironmentTaskGate` 增加共享模式

建议保留现有类名与独占租约行为，新增：

```java
static Lease tryAcquireShared(String kind);
static int sharedCount();
static boolean hasSharedUse();
static boolean ownsExclusiveCurrentThread();
```

语义：

- `tryAcquire()`（现有独占）只在 `active==null && sharedCount==0` 时成功；
- `tryAcquireShared()` 只在 `active==null` 时成功，允许多个共享持有者；
- `Lease.run()` 仍绑定当前线程；共享租约不能 `retainCurrentUntilExit()`（进程寿命继续归 `RuntimeTasks`）；
- `isBusy()` 保持“独占忙”语义，避免现有 UI 把普通共享任务显示成环境维护；
- 新增 `MaintenanceCoordinator.isExclusiveWorkBlocked()`（独占忙或 sharedCount>0）给备份/安装/插件修改等独占入口做 UI 预判；
- `ownsExecutionTicket()` 改用 `ownsExclusiveCurrentThread()`，防止共享租约被误当维护凭据；
- 审计所有 `EnvironmentTaskGate` 消费者（当前 main 代码 17 处），逐一决定共享/独占，不能只改三处入口。

#### 2.2 消费者迁移

| 路径 | 现在 | 建议 |
|---|---|---|
| 插件查询/来源 probe | 独占 `插件操作` | 查询用共享；probe 可改为无租约的只读来源核对（仅维护/独占时拒绝） |
| 运行投影读取 | 独占 `插件运行状态读取` | 共享或只读，不阻塞启动/终端 |
| DSH 启动/兼容重试 | 独占 `启动 Web` | 共享；保留生命周期 generation/停止保护 |
| 简易终端 open/write | 独占 `启动简易终端`/`终端命令发送` | 共享；保留 `RuntimeTasks` 寿命与退出核验 |
| PTY fork 窗口 | 无租约 | `PtySession.start()` 内共享租约，覆盖 `requireUserRuntime` 到 fork/初始化；结束后释放，寿命仍归 `RuntimeTasks` |
| 插件提交/安装/删除/更新 | 独占 `插件操作`（提交还走 `MaintenanceCoordinator.exclusive`） | 保持独占；这是真实共享写 |
| 备份/恢复/安装/配置修复/ADB 修复 | 独占 | 保持独占 |
| `PluginActivationHooks` | 独占 `插件加载确认` | 保持独占（写激活状态），失败重试有界 |

#### 2.3 查询只读准备路径

- `GuestPluginScripts.Preparation` 增加只读就绪查询，例如：
  - `RuntimeTools.queryReady(context, rootfs)`：校验运行描述符、`managed-assets-v2` marker、`python3.12`/动态库、所需插件脚本与受管文件存在；**不调用** `prepare()`；
  - `RootfsInstaller.pythonQueryReady(rootfs)`：只检查 Python 入口/动态库/版本标记；**不调用** `python()`/`pnpm()`；
  - 若缺失或身份不一致：抛 `GuestPreparationFailure.Code.MAINTENANCE_REQUIRED`，由安装/维护入口处理。
- `GuestPluginScripts.manager()` 对 `list`/`refresh` 使用只读准备；`builtin`/`startup`/`migration` 继续完整准备。
- `plugin-manager.py` 查询模式：
  - 不 `os.makedirs(DSH_HOME)`；目录不存在时明确失败；
  - 进度写入限制在 `DSHA_PLUGIN_TASK` 指定的任务文件，或查询模式改为 no-op；不得写 profile、plugin-sources、激活状态或插件目录；
  - 不执行插件自身代码；不触发安装/修复/迁移。
- `PluginRepository` 查询失败时保留旧快照并标记 stale；不把失败发布成空列表。

#### 2.4 启动与查询的让路/队列

- 在 `StartupPipeline` 增加一个 `Actions.beforePluginPreparation()`（默认 no-op），由 `StartupEnvironmentActions` 调 `PluginManagementEngine.yieldToUserAction()`：
  - 只取消自动查询，等待 `queryRunning==false` 有界（例如 3 秒）；
  - 设置短时自动查询暂停窗口（例如 120 秒或绑定本次启动 generation），避免查询在启动注册期间反复插队；
  - 用户显式刷新不取消，除非启动需要脚本队列独占。
- `GuestScriptQueue` 增加高优先级入口（启动注册/加载确认）：
  - 等待者分高/普通两队列；高优先级不能抢占已运行脚本，但可以跳过等待中的普通查询；
  - 高优先级等待有界；超时返回明确错误（“插件查询尚未退出，未启动 Web”），不得启动一半环境；
  - 保持同一线程重入与取消语义。
- 若查询取消后无法在预算内退出：记录诊断、保留旧快照、返回明确状态；不自动重放、不把 UNKNOWN 当成功。

#### 2.5 维护屏障

- `MaintenanceGate.exclusive()` 在获取执行凭据前：
  1. 取消可退出的后台查询；
  2. 有界等待其共享租约释放；
  3. 再走现有 `stopTerminals/stopWeb/tryEnterMaintenance` 流程。
- 若查询在不可中断的提交阶段，完成/回滚后再释放；不得用“已发送取消”替代退出证明。
- 若共享持有者是不可取消的 DSH 启动/终端，维护仍按现有策略拒绝或等待 `stopWeb`/`closeAllAndConfirm`；不能先等任务结束再登记维护意图。

#### 2.6 明确不做的内容

- 不新增三个“预留交互 shell”来替代资源边界；
- 不把 DSH 启动改成向普通终端输入 `dsh web`；
- 不删除启动必需的插件注册/迁移/激活核验；
- 不因查询失败/超时清空全局锁或旧快照；
- 不用缓存/mtime 代替实际来源身份与完整校验；
- 不改包名、签名、数据格式、终端内核、PTY/JNI、Ubuntu 基础环境；
- 不做 Phase 3 的“统一会话展示”，除非用户另行确认。

## 5. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| 共享/独占语义迁移不完整 | `EnvironmentTaskGate` 有 17 个生产消费者；误把维护/安装当普通共享，可能让环境替换与运行任务并发 | 建立消费者清单与单测；`ownsExecutionTicket` 只用独占；先迁移查询/启动/终端，其余保持独占 |
| 维护被共享查询卡住 | 查询持有 `RuntimeTasks` detached token；维护 3 秒围栏会失败 | 维护前取消查询并等待退出；失败保留现场并提示，不清锁 |
| 脚本队列仍串行 | 外层拆开后启动仍可能等待慢查询 | 高优先级队列 + 取消自动查询 + 有界超时；不静默等待 |
| 查询准备仍写环境 | `RuntimeTools.prepare`/`RootfsInstaller` 会写临时目录、resolver、wrapper、marker | 只读 readiness 校验；缺失时转维护，不在查询内修复 |
| 取消后结果未知 | 查询已派发但脚本未退出，可能发布过期/混合列表 | 来源代次 + manifest revision + 取消预算；UNKNOWN 不重放，保留旧快照 |
| PTY 与简易终端不对称 | PTY 无真实租约，简易终端有；只修一个入口会漏 | 分别测试 open/new/send/close；共享租约覆盖 fork 窗口 |
| 自动查询暂停导致外部变化丢失 | 暂停窗口内文件变化未扫描 | 暂停期间继续 markDirty；窗口结束/页面打开时补偿；不丢事件 |
| 测试契约变化 | 现有测试断言 gate busy/维护围栏；改动会红 | 先改测试模型，保留并发/取消/UNKNOWN 负向断言；不要放宽失败断言 |
| 无设备证据 | 未验证新 APK、OEM 文件事件、真实 proot 慢 I/O | 只声明源码/测试范围；真机需另行授权并绑定准确 APK |
| 性能结论 | 没有计时基线，不能编造提升百分比 | Phase 0 先采集分段数据；只报告样本与离散度 |

## 6. 验证计划（获准实施后）

### 6.1 定向测试

1. 查询共享租约 + 启动共享租约可同时获取；独占安装/备份/插件提交不能进入。
2. 查询运行中启动 DSH：不被 `EnvironmentTaskGate` 拒绝；若脚本队列冲突，取消自动查询并在有界时间内进入；不启动一半环境。
3. 查询运行中打开 `TerminalActivity`：不弹“环境忙”；已有会话可查看/切换。
4. 查询运行中新建 PTY 和简易终端：分别通过；fork 窗口有共享保护；失败时明确提示。
5. 查询准备只读：只读校验就绪时不调用 `RuntimeTools.prepare`/`RootfsInstaller.python/pnpm`（用 fake/spy 断言零写入）；工具缺失时返回 `MAINTENANCE_REQUIRED`，不发布空列表。
6. 查询过程中插件配置变化：不发布混合结果；旧快照标记 stale。
7. 用户启动后点击 Stop：旧排队启动失效；取消后的查询不复活启动。
8. 维护/恢复：查询可被取消并退出；不可取消的启动/终端按现有策略阻止环境切换；退出未知保留保护。
9. 旋转/主题/语言/通知返回：不重复创建终端，不重放插件任务。
10. 插件提交中取消：事务与回执不破坏；不提前释放独占保护。

### 6.2 回归与门禁

- `bash build.sh :app:testStandardDebugUnitTest`（全量 Standard）；
- 相关 Robolectric 测试：`PluginRepositoryQueryLifecycleTest`、`WebLifecycleResponsivenessTest`、`WebLifecycleStartProtectionTest`、`MaintenanceGateIntegrationTest`、`EnvironmentTaskGateTest`；
- Release Lint；
- `assembleStandardRelease` 或当前分支 CI 等价构建；
- 若修改随包脚本/受管工具：核对部署链、摘要/描述符和实际 APK，不能只改源文件；
- 不运行 Low；不安装/启动/停止手机应用，不发布/上传；真机验收另行授权。

## 7. 回滚

- 按 Phase 独立提交，逆序 revert：
  1. 只读查询准备与测试；
  2. 共享/独占租约与消费者迁移；
  3. 自动查询让路/高优先级队列；
  4. 诊断计时。
- 不迁移用户插件数据格式；不新增持久缓存；回滚不影响已提交插件事务。
- 若共享租约引入未知退出/一致性风险，保留现场，回到独占门禁版本；不强行清锁继续。

## 8. 实施授权与后续

用户已确认按 Phase 1 + Phase 2 核心实施；实际变更、验证和未完成项见 [实施记录](plugin-query-decoupling-implementation-20261027.md)。本报告保留为复核基线，不再代表当前工作区状态。

原建议范围：

- 修改 `EnvironmentTaskGate`、`PluginRepository`、`PluginBackendAdapter`、`PluginManagementEngine`、`WebLifecycleController`、`StartupPipeline`/`StartupEnvironmentActions`、`SimpleTerminalBackend`、`PtySession`、`MainActivity`/`TerminalFragment`/`PtyTerminalFragment`、`GuestPluginScripts`/`RuntimeTools`/`RootfsInstaller` 及对应测试；
- 不安装、不启动/停止手机应用、不发布/上传、不操作设备；
- 只运行 Standard 测试/构建/Lint。

请确认是否按此范围开始 Phase 1 + Phase 2，或只先做 Phase 1 / 只保留复核报告。
