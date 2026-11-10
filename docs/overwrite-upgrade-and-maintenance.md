# 覆盖安装、运行时维护与内置插件依赖契约

> English: [Over-the-top upgrades, runtime maintenance, and built-in plugin dependency contracts](en/overwrite-upgrade-and-maintenance.md)
>
> 核验日期：2026-11-06。基线：build324（已发布）/ build325（T61 移动壳齿轮让位）/ build326（T60 展示层与旧 Web 会话修复）/ build327（T67 维护前自行完成完全停止）/ T91（覆盖更新弃旧版：受控结束上一版启动器，下一构建）。本文把“覆盖安装后白屏、不触发环境维护、侧栏重复叠加、维护页要求用户绕道停止 Web”这次事故的判定层、展示层、自动开始安全门禁、维护前停止与 Web 启动准入写成持久化不变式。只核对源码与已有回归，不代替真机验收；具体版本的验证范围以 [docs/releases/](releases/) 对应回执为准。

## 30 秒结论

1. **判定层本来就是对的**：`EnvironmentAccess.requiresMaintenanceBeforeStart` 与 `EnvironmentIdentity.shouldBlockRuntimeStart` 在 322→323（dsh 版本变化）和 323→324（同 dsh、只换受管补丁）两条路径上都返回“需要维护”。不要去改这两个函数，也不要再把事故归因到它们。
2. **缺口在展示层**：`RuntimeInstallationState.isEnvironmentReady()` 只回答“旧树还能不能用”，不回答“是不是当前候选”。只按它渲染维护页，会把补丁级覆盖误判成“无事可做”：维护页把“进入主界面”升为主操作、更新降为次要；受限入口不记录限制身份；主界面门禁仍要求维护，于是来回跳。
3. **自动开始可能被静默拦下**：覆盖安装后旧 proot Web 进程若仍存活，`hasUnresolvedWebSession()` 为真，`shouldAutoStart` 返回 false。这是**必须保留**的安全门禁（未知实例不自动重放），但用户必须仍能看到整屏维护页并自己点一次更新。
4. **白屏机制**：`WebLifecycleController` 在 `!isEnvironmentReady() || requiresMaintenanceBeforeStart()` 时直接把启动判为 `REJECTED`，防止看门狗用“兼容但过期”的旧运行时拉起 Web。于是宿主重建 / 看门狗重新拉起时，“旧树 + 新 APK”的 Web 根本不会启动，主界面 Web 区域空白。若旧 proot Web 进程仍存活、页面已加载旧树内容，内置插件对宿主指示灯的依赖又会让内容区白屏并出现侧栏重复；T61 真机对照中只改这条插件契约后白屏消失，但直接 JS 栈未取得，见[白屏排查记录](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md)。
5. **客户端插件不得依赖宿主某个控件是否存在**：内置 `dsh-web-mobile` 的侧栏齿轮曾用 `:has(...)` 依赖宿主 `ConnectionIndicator` 是否渲染；当受管补丁让该指示灯首次不显示时，插件渲染链失效，用户禁用插件才恢复。修复为“按实测宽度让位”，结论幂等写入插件自有属性 `data-mobile-nav-quick-fit`。
6. **维护动作必须自己完成“完全停止”**（build327 新增）：用户手点「更新运行时」后，维护任务先按既有停止设计（哨兵 → PID 文件 + 出生身份核验）结束旧 Web；普通停止未确认退出时，用同一套身份核验准备强制候选再结束一次。维护页不再要求用户绕道「受限主界面 → 停止 → 回来更新」。停不掉时保留原环境并给出可重试的失败文案。
7. **覆盖更新弃旧版**（T91 新增）：只有「已装 APK 身份 ≠ 记录中的会话身份」时才允许在维护动作内按记录出生身份先结束上一版 guest，再结束该记录对应的旧 proot 启动器；两者各自两次完整核验，任何一项不符都不发信号。非覆盖路径、当前实例、终端与隔离试运行不变（I9 仍是默认规则）。

## 不变量（不可破坏）

| # | 不变式 | 检查点 / 回归 |
|---|---|---|
| I1 | 覆盖安装后，只要**已装运行时不是当前候选**（`RuntimeDescriptor.latest` 为假），就必须判为“需要维护”；即使它仍兼容、仍健康、dsh 版本相同。 | `OverwriteUpgradeGateTest`（`app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java`，T60 / build326）覆盖 322→323、323→324 与“只改受管输入”的合成前代 |
| I2 | `isEnvironmentReady()` 只表示“环境可继续运行”（安装完整 + 兼容 + 健康回执），**不等于**“无事可做”。展示层必须把“可运行”和“是当前候选”分开判断。 | `RuntimeUpgradeState.upToDate(ready, latest)`；build326 起维护页就绪判定同时要求 `runtimeLatest` |
| I3 | 覆盖安装后必须让用户看到**整屏维护页**并可一键更新。自动开始可以因未关闭的旧 Web 会话被拦，但入口不能消失，也不能用“进入主界面”代替修复入口。 | `RuntimeUpgradeState.openMaintenancePage`；`MainActivity` 入口门禁；`OverwriteUpgradeGateTest.unresolvedOldWebStillOpensMaintenancePageButNeverAutoStarts` |
| I4 | 未确认的旧 Web 会话（`hasUnresolvedWebSession()`）**只拦自动开始**，不拦维护页；不得自动重放未知实例，只能由用户在维护页明确手点。 | `EnvironmentIdentity.shouldAutoStart(..., unresolvedWebSession)` 保持为真值时返回 false；`MainActivity` 不再把 `unresolved` 当打开维护页的前置条件 |
| I5 | Web 启动准入必须在“旧树 + 新 APK”时拒绝，除非维护已完成或用户已按规则进入受限主界面；看门狗/前台服务与主界面共用同一判定。 | `WebLifecycleController.requestStart` 的 `!isEnvironmentReady() || requiresMaintenanceBeforeStart` 门禁；不得为“消灭白屏”放宽 |
| I6 | 只改 UI 的 APK 覆盖**不得**重建环境；同 dsh 版本、受管补丁或描述符输入变化**必须**视为补丁级更新（重算描述符、进维护页、重装受管树）。 | `RuntimeDescriptor.latest` 比较 `id` 与 `inputs`；`OverwriteUpgradeGateTest.uiOnlyApkUpdateWithUnchangedManagedInputsNeverNeedsMaintenance` |
| I7 | 同一候选只自动尝试一次；失败、中断、旋转、进程重建都不自动重放。受限入口不写就绪标记，终端 / Web / 插件执行仍须过环境门禁。 | `EnvironmentIdentity.shouldAutoStart`；`MaintenanceCoordinator.pending`；`ExtractActivity.proceed()` 的 `limitedEntry` |
| I8 | 内置插件只能依赖**自己的属性 + 实测几何**，不得依赖宿主某个控件是否存在（类名、`data-*`、`role`、`:has()` 都不行）。宿主 UI 变化不得让插件渲染链崩掉。 | [`tools/test-mobile-gear-contract.mjs`](../tools/test-mobile-gear-contract.mjs)（T61 / build325）；[`tools/apply-mobile-client-patches.mjs`](../tools/apply-mobile-client-patches.mjs) 的 `DSHA_GEAR_FIT_V1` |
| I9 | 用户显式确认维护后，维护动作必须**自行完成完全停止**，再取维护屏障与执行更新；普通停止未确认时只允许升级一次**身份核验**的强制结束（PID + 出生身份 + 命令行 + 代次），不得按端口 / 名称 / 进程组；无法核验或仍停不掉时保留原环境并给出可重试文案。未确认的旧 Web 会话仍只拦自动开始，不拦用户手点。非覆盖路径（当前实例、普通停止、试运行）不变；覆盖更新例外见 I10。 | `MaintenancePreStop`（`core/`）与 `WebProofStop`（`util/`）；`MaintenancePreStopTest`、`WebProofStopTest`、`WebRecordedStopTest`、`WebForceStopTest`、`WebSessionProofTest`（T67 / build327） |
| I10 | **覆盖更新弃旧版（T91）**：只有「已装 APK 身份 ≠ 记录中的会话 `appIdentity`」时，才允许在维护 / 强制停止动作内按记录出生身份（boot、PID、出生时间、命令行、UID、代次/实例/nonce）**双次核验**后，先结束该记录中的 guest，再结束它对应的旧 proot 启动器；SIGTERM 后短等待，未退出再 SIGKILL。guest 未确认退出时不碰启动器；任一核验失败 / 读不了 / PID 复用 / 他人 UID / 两次观察变化都**不发信号**并保留原环境。绝不按端口 / 名称 / 进程组 / 扫描；不碰当前实例启动器、终端、虚拟屏与隔离试运行。 | `WebLifecycleController.previousBuildInstance` / `overwriteRetireCandidate`；`WebProcessManager.prepareOverwriteRetire` / `observeRecordedLauncher`；`WebProofStop.runRetiringLauncher`；`WebOverwriteRetireTest`、`WebProofStopTest`、`WebSessionProofTest`、`WebForceStopGateTest`（T91） |

## 四层机制

### 1. 判定层：入口与 Web 启动共用同一身份

- [`EnvironmentAccess.requiresMaintenanceBeforeStart`](../app/src/main/java/com/deepseekharness/app/core/EnvironmentAccess.java) 先看 `DshVersionPolicy`（已装更新 → 必须维护），并直接返回 true；再比较 `runtimeLatest`，若已是当前候选则无需维护；否则用 `candidateAttemptKey`（`runtime:<runtimeId>@<APK lastUpdateTime>`）调 [`EnvironmentIdentity.shouldBlockRuntimeStart`](../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java)。
- `shouldBlockRuntimeStart` 的语义：
  - `latest` 为真 → 不拦（无需维护）。
  - 包内描述符不可认证 → 必须拦（不能让看门狗用未知运行时拉起 Web）。
  - 否则返回 `shouldAutoStart(false,false,false,candidate,attempted)`：**尚未尝试过的新候选必须维护**；同一候选已经尝试过一次后，是否继续由维护页与健康前代决定。
  - `busy` / `pending` 只决定“维护现在能不能立刻执行”，**不能**把“尚未尝试的新候选”判成无需维护。
- 回归夹具见 `OverwriteUpgradeGateTest`：322→323 与 323→324 都断言 `compatible()` 为真、`latest()` 为假、`shouldBlockRuntimeStart` 为真。这证明**判定层不需要改**。

> 修改提醒：不要为了“让用户少跳一次”去改 `requiresMaintenanceBeforeStart` 或 `shouldBlockRuntimeStart`。事故的修复点在展示层与入口是否打开，不在判定真假。

### 2. 展示层：补丁级更新也必须算“没做完”

事故真正缺口在渲染与受限入口：

- [`RuntimeInstallationState.isEnvironmentReady()`](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeInstallationState.java) = `isEnvironmentInstalled() && rootfsVersionMatches()`。
- `rootfsVersionMatches()` 只用 [`RuntimeDescriptor.compatible()`](../app/src/main/java/com/deepseekharness/app/backup/RuntimeDescriptor.java)（比较 `baseVersion` / `launcherContract` / `bridgeProtocol` / `dataRead` 是否包含 `dataWrite`）加健康回执。**它不看 `runtimeId` 与 `inputs`**。所以“同 DSH 版本、只换受管补丁”的覆盖安装会被判“环境就绪”。
- 若展示层直接用这个“就绪”：
  - [`MaintenanceFooterPolicy.decide(..., ready=true)`](../app/src/main/java/com/deepseekharness/app/util/MaintenanceFooterPolicy.java) 返回 `Primary.DEMOTED` + `Entry.PRIMARY`：**“进入主界面”升为主操作，更新/重试降为次要按钮**；
  - `ExtractActivity.proceed()` 不把“不是当前候选”计入 `limitedEntry`，主界面门禁 `requiresMaintenanceBeforeStart` 又要求维护，于是用户被反复送回维护页；
  - [`EnvironmentUiStatus`](../app/src/main/java/com/deepseekharness/app/ui/EnvironmentUiStatus.java) 只看 `isEnvironmentReady()`，受限主界面里没有回维护页的入口；
  - 维护页也没有“内置补丁尚未落地”的说明，用户无法判断补丁到底有没有更新上。
- build326 起（T60）新增纯逻辑 `RuntimeUpgradeState`（`app/src/main/java/com/deepseekharness/app/util/RuntimeUpgradeState.java`），把两维分开：
  - `upToDate(environmentReady, installedLatest)`：只有“可继续运行 **且** 已装运行时就是当前候选”才算无事可做；
  - `maintenanceRequired(...)`：补丁级更新未完成也算需要维护；
  - `limitedEntry(ready, latest, busy, pending)`：环境未就绪、不是当前候选、维护忙或 pending 时，进入主界面必须记受限身份；
  - `openMaintenancePage(gateFailed, maintenance, restoring)`：未确认旧 Web 会话不阻止打开维护页。

> 判定层与展示层必须同源：入口、维护页、主界面横幅、Web 启动准入都应从 `EnvironmentAccess` / `RuntimeUpgradeState` 取得同一结论。任何一处自行用 `isEnvironmentReady()` 代替“可以收工”，都会重新制造本事故。

### 3. 自动开始安全门禁：拦自动，不拦入口

- 覆盖安装会改变 `StoredWebSession.appIdentity()`（含 APK `lastUpdateTime`）。旧 proot Web 进程仍存活时，新包观察到的是 `LIVE` 但身份不兼容，恢复只能保守为 UNKNOWN，于是 `HarnessController.hasUnresolvedWebSession()` 为真。
- [`EnvironmentIdentity.shouldAutoStart`](../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java) 的完整重载明确包含 `!unresolvedWebSession`：存在未确认原实例时，自动开始返回 false。**这是正确的安全门禁，必须保留**。
- 用户看到的现象是“维护没有自动开始”。正确产品行为不是强行自动开始，而是：
  1. 仍然打开整屏维护页；
  2. 用醒目文案说明“已装入的运行时仍是上一版，本次内置补丁尚未落地”；
  3. 提供“更新运行时”作为主操作（用户明确手点）；
  4. 允许用户进入受限主界面查看日志 / 配置，但 Web 与插件执行仍受环境门禁。
- 失败 / 中断的旧候选按 `shouldAutoStart` 的 `previousStatus` / `previousRuntimeHealthy` 条件处理；不因一次 APK 修复包升级就把未知现场当成可重放。
- 该门禁只决定“要不要自动开始”。用户在维护页手点「更新运行时」不受它阻挡；手点之后由维护动作自己完成完全停止（见下一节）。

### 4. Web 启动准入：白屏的真正原因

[`WebLifecycleController`](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) 的 `requestStart` 在入队前有：

```java
if (!proot.isEnvironmentReady() || EnvironmentAccess.requiresMaintenanceBeforeStart(owner)) {
  // “运行环境缺失或需要更新，请进入安装与修复”
  return StartAdmission.REJECTED;
}
```

源码注释写明目的：覆盖安装后前台 `START_STICKY` 服务可能先于 `MainActivity` 被系统重建，若放行，看门狗会用**兼容但过期**的旧运行时重新拉起 Web，使本 APK 的运行补丁与系统插件尚未落地。

因此“旧树 + 新 APK”时：

- 宿主重建或看门狗重新拉起时，Web 不会被允许启动，主界面 Web 区域就是空白；
- 这不是 WebView 故障、网络故障或插件整体崩溃，**不要**去改 `WebView`、清缓存、重装 APK 或放宽启动门禁；
- 若旧 proot Web 进程仍存活、页面已加载旧树内容，界面证据为 UNKNOWN；叠加 0.2.2 首次连接不再渲染指示灯，移动壳插件的旧依赖会失效，用户还会看到内容区白屏与侧栏重复叠加层（见下一节）。[白屏排查记录](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md)保留了未取到的直接 JS 栈与后续取证方法；[build325 记录](releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md)记录了真机对照结果。

### 5. 维护前停止：维护动作自己完成“完全停止”

用户手点「更新运行时」后，维护任务必须在取屏障前自行完成停止，不再把停止甩给用户：

- **入口**：[`RuntimeMaintenanceStarter.showRetryDialog`](../app/src/main/java/com/deepseekharness/app/ui/RuntimeMaintenanceStarter.java) 的确认按钮 → `BackupTask.updateEnvironment(true)` / `rebuild()` / `recoverMaintenance()`；只有用户在确认对话框里点「继续」后，任务才带 `allowForceStop=true`。
- **停止顺序**：`MaintenanceCoordinator.exclusiveUserConfirmed` → 关闭终端 → [`MaintenancePreStop`](../app/src/main/java/com/deepseekharness/app/core/MaintenancePreStop.java)：
  1. 请求普通停止（停止队列内先写哨兵，再按 PID 文件 + 出生身份核验，只给同一已核验进程发信号）；
  2. 等待 `WebProcessManager` / `WebProcessSession` 的既有退出证明（guest 与启动器都退出）；
  3. 普通停止未确认时，才调用 `HarnessController.prepareForceStop()` 准备**身份核验**候选（PID、出生时间、命令行、UID、代次），再 `forceStop` 结束同一 guest；
  4. 仍无法确认退出 → 失败文案明确写出原环境未改动，并让用户点「重试更新运行时」。
- **覆盖安装后的残留进程**：App 已被系统结束、`/root/.dsha-web.pid` / `.dsha-web.identity` 可能缺失时，用会话记录里已保存的出生身份（`StoredWebSession.Record.proof`：boot、代次、实例、nonce、guest 与 launcher 的 PID + 出生时间 + 命令行）作为兜底证据。[`WebProofStop`](../app/src/main/java/com/deepseekharness/app/util/WebProofStop.java) 只在两次完整核验一致时先 SIGTERM，允许强制时再次复核后 SIGKILL；PID 复用、出生时间或命令行变化、记录不可读都**不发送任何信号**。非覆盖路径（当前实例、普通停止、试运行）下启动器仍不被结束（I9）。
- **覆盖更新弃旧版（T91 / I10）**：覆盖更新后旧 proot 启动器可能先于 guest 存活，只结束 guest 无法解除维护屏障。只有已装 APK 身份与记录身份不同时，`WebLifecycleController.overwriteRetireCandidate` 才按记录准备候选；`WebProcessManager.prepareOverwriteRetire` 对 guest 与旧启动器各自连读两次出生身份，`WebProofStop.runRetiringLauncher` 在 guest 确认退出后才对旧启动器 SIGTERM → 短等待 → 复核 → SIGKILL。guest 未确认退出、启动器 PID 复用 / 属于他人 UID / 命令行或代次不符 / 环境不可读时都不发信号，维护页保留原环境并给出重试文案；用户确认的强制停止对话框会明确写出「放弃上一版 Web 并强制结束」。自动开始 / 闲时清理等自动路径仍不升级。
- **自动路径不升级**：覆盖更新后的自动开始、闲时清理等仍走 `MaintenanceCoordinator.exclusive`（不传 `allowForceStop`）；未确认旧 Web 会话只拦自动开始（I4），只有用户手点维护才允许身份核验的强制结束（I9 / I10）。
- **隔离试运行收尾（T91 / T103）**：proroot 启动器可能在试用 guest 已退出后晚退或残留（Android 16 实测），因此 `WebProcessManager.confirmTrackedTrialStopped` 只按本记录 guest 的退出证据（PID 退休 + 无本应用 Web）确认，不把启动器对象仍活当成试用失败；`RuntimeTrial` 只对本次持有的 `Process` 句柄做一次有界清理（超时 / 失败只写 `TRIAL_LAUNCHER_RETENTION` 诊断，不改变 guest 退出结论）。`TrialRecovery` 遇到 `launched` 但缺 `.dsha-web.pid` / `.stale` 的记录时，改为要求平台按该记录 id 推导的试运行 profile **确认无此进程**（两次一致出生身份、同 UID；仍存在 / 拒读 / 身份变化继续 fail-closed），不再无条件留下无法自愈的 `TRIAL_NO_PID_OR_BOUND_SESSION_EXIT_EVIDENCE`。
- **优雅停止未确认不再立即失败（T103）**：试运行收尾先按记录优雅停止（SIGTERM + 3 秒窗口）；未确认时 `TrialGuestCleanup` 不立即抛错，而是有界等待本次持有的启动器（3 秒），再由 `WebProcessManager.forceStopTrialGuest` 按本轮记录、唯一 profile 与保存的出生身份连读复核后只对本轮 guest 发 SIGKILL，然后重跑一次记录停止与全局无 Web 核验。强制路径缺出生身份、PID 记录对不上、两次观察变化或强制后仍非空时才保留现场并报 `TRIAL_PROCESS_UNCONFIRMED`；脱敏详情同时写入可导出操作记录与记录目录（成功试用后的收尾失败写 `failure.json`，恢复路径写 `recovery-stop.json`，已有失败详情不覆盖）。强杀不扫描 `/proc`、不按 PID / 名称 / 进程组结束任何其它进程，也不作用于主 Web；`TrialRecovery` 走同一顺序，因此一次 `recover-maintenance` 就能关闭此前停在 guest 未退的记录，不再需要第二次恢复。
- **半提交恢复与维护页出口（T91 / T103）**：trial 失败留下的 `files-committed / settings-committed` 半提交事务仍只能由 `EnvironmentMaintenance.recover()` 回切；恢复失败的原因现在同时写入任务状态与可导出的操作记录（`MAINTENANCE_TASK_START / MAINTENANCE_TASK_FAILED`），维护页必须能显示。回切后已装运行时不是当前 APK 候选，启动仍必须走一次更新（I5，这是设计）；但页面**不能**再把旧的 `TRIAL_PROCESS_UNCONFIRMED` 留在主位或继续写“中断维护”。外部完成恢复后，页面只要环境可运行、无维护进行 / 待恢复，就丢弃旧的失败快照（`RuntimeUpgradeState.clearStaleFailure`），回到“上一版运行时待更新”的标准状态并给出更新入口。`RuntimeMaintenanceStarter.pendingTrial` 不再跨调用缓存，维护页 `onResume` 还会主动重扫一次宿主维护记录，避免从应急 DSH 返回后沿用恢复前的 UI 缓存。应急 DSH、维护记录、导出可读副本与「进入受限主界面」在任何失败态都保持可点，不能只剩进度条。
- **三步绕道作废**：维护页失败文案不再要求“先去受限主界面停止”。主界面 → 停止仍是既有的手动停止 / 强制停止入口，但不再是完成运行环境维护的必经步骤。

## 内置插件对宿主 UI 的依赖禁令

### 事故案例：`dsh-web-mobile` 侧栏齿轮

- 上游（3.2.2 起，3.3.1 仍保留）的 `drawer-quick-settings-chip-guard` 用 `:has(> button[data-phase], > [role="status"])` 判断连接指示灯是否在场，再决定 footer 齿轮要不要让位。这使插件渲染**依赖宿主某个子节点存在**。
- build324 的受管补丁 `DSHA_FIRST_CONNECT_SILENT_V1` 让首次进入不再渲染 `ConnectionIndicator`。于是该 `:has()` 判据永远“放得下”，但行的真实宽度没人核对；插件渲染链失效，用户看到侧栏 footer 重复叠加，禁用内置插件后恢复正常。
- 修复（T61，`DSHA_GEAR_FIT_V1`）：
  - 导出纯判据 `dshaGearFits(rowWidth, usedWidth)`：`行内容宽度 − 其它可见孩子宽度 ≥ 40px` 才显示齿轮；布局未就绪（宽度 ≤ 0 / 非有限数）按“放不下”处理，保守保留账户菜单入口；
  - 结论幂等写入插件自有属性 `data-mobile-nav-quick-fit`，CSS 只认这个属性；
  - 账户菜单设置项与齿轮可见性共用同一结论，保证任何时刻只有一条设置入口；
  - 产物在 [`app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js`](../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js)，补丁由 [`tools/apply-mobile-client-patches.mjs`](../tools/apply-mobile-client-patches.mjs) 管理。
- 契约测试：[`tools/test-mobile-gear-contract.mjs`](../tools/test-mobile-gear-contract.mjs)（T61，随 build325 合入）。用真实产物 + 最小 DOM 覆盖：无指示灯时齿轮正常注入、指示灯出现/消失时按实测宽度让位/恢复、行宽不足时保守让位、重复 `ensure` 幂等。

### 写插件 / 改宿主 UI 时的规则

1. **不要**用宿主节点的类名、`data-phase`、`role="status"`、`:has()` 等“存在性”判据决定插件是否渲染；宿主删一个子节点就可能让整条渲染链崩掉。
2. 需要感知布局时，只量 `getBoundingClientRect()` / `getComputedStyle()` 的几何值，并把结论写进**插件自己的**属性（例如 `data-mobile-nav-quick-fit`）。
3. 尺寸不可读、布局未就绪时选择**保守降级**（少显示一个入口），不要选择“假设存在、直接渲染”。
4. 每个类似依赖都要有可在 Node 里跑的契约测试，用真实产物 + 最小 DOM，而不是只测手写函数。
5. 宿主 UI 变更（例如新增/删除连接指示灯、更新条）必须同步检查所有内置插件补丁的 CSS 选择器与 DOM 假设。
6. **`dsh-web-mobile` 从 build336 起恢复默认启用**：上游 3.3.1（4e71eb67）已适配宿主 0.2.2，插件字节、锁与补丁取舍见[插件页](plugins.md)和 [build336 记录](releases/v0.2.1-rc.5-build336-web-mobile-3.3.1-20261106.md)；build327 的默认禁用与事故原因保留在 build327 记录。恢复引用了原硬门槛：build325 齿轮契约与 build324 白屏路径回归全绿，`generate-builtin-plugins` / `prepare-runtime-descriptor` 与发布前门禁已重跑；**真机覆盖升级验收（不白屏、侧栏不重复、footer 设置入口不重复、升级后默认启用）仍须在 build336 发布前补写回执**，只凭 CI 或单测通过不得宣称真机通过。

## 发布前必跑清单

改受管补丁、内置插件、`app/src/main/assets/**` 中的受管输入，或改覆盖升级 / 维护相关 Java 后，至少完成以下检查（命令均在仓库根执行）：

```bash
# 1. 重算受管运行时描述符（受管补丁 / 内置插件产物 / runtime 输入变化后必须）
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py

# 2. 第三方声明（补丁链或内置插件摘要变化会让它变；324/325 都踩过）
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
# 若 --check 失败，先运行不带 --check 的生成命令更新 THIRD_PARTY_NOTICES.md，再重跑上面两条

# 3. 契约整组（与 .github/workflows/_node-contracts.yml 一致）
python3 tools/prepare-runtime-tools.py --check
python3 tools/verify-runtime-descriptor-source.py
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
python3 tools/generate-builtin-plugins.py --verify
python3 tools/generate-credential-paths.py --check
python3 tools/prepare-semver-reference.py
python3 tools/run-host-tests.py --check-manifest
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/generate-changelog.py --check
python3 tools/check-document-links.py

# 4. 本次事故的两条直接回归
node tools/test-mobile-gear-contract.mjs          # 内置移动壳齿轮契约（T61 / build325）
dsha-gradle :app:testStandardDebugUnitTest --tests '*OverwriteUpgradeGateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*RuntimeUpgradeStateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*MaintenancePreStopTest'   # build327：维护前自行停止
dsha-gradle :app:testStandardDebugUnitTest --tests '*WebProofStopTest'         # build327：残留进程身份核验
```

补充规则：

- **固定输入**：如果变化的是固定输入 12 项之一（`offline-rootfs.bin`、`dsh-runtime.bin`、`ubuntu-tools.bin` 等），必须按 [固定构建输入](fixed-build-inputs.md) 重新打包 / 上传预发布资产、更新 `ci/sunset-inputs.lock.json`，并同步相关 lock 与文档；只改 APK 资源（如内置插件 `client.js`）则不重打固定输入包，但**仍要重算 runtime-descriptor 与第三方声明**。
- **只改 UI 的 APK** 不要重建 Ubuntu，也不要让维护页出现“需要重建”的误导；只有受管输入变化才进补丁级维护。
- **外部 PR / 外部合并**：先由专职 AI 审查会话逐行审阅全部 diff，按[外部 PR 与外部合并审查流程](external-pr-review.md)给出 APPROVE / REQUEST_CHANGES；CI 全绿与冲突解决都不等于可合并，审查会话不合并、不 push 作者分支。
- 报“可合并 / 可发布”前列出**实际跑过**的命令与结果；没跑真机就写“未真机验证”，不要把宿主测试写成设备结论。

## 真机验收步骤（覆盖安装）

发布验收必须做一次“**上一版 → 本版**”的真实覆盖安装，并写进版本回执：

1. **准备前代**：在独立验收设备安装上一版正式包（同包名、同签名、非调试），完成欢迎流程，启动一次 Web 并确认环境健康；记录版本、`runtimeId` 与是否已连接。
2. **覆盖安装（保留旧 Web 场景）**：不手动停止 DSH / Web，直接用应用内更新或同签名 APK 覆盖安装。打开 App 后检查：
   - 是否出现**整屏维护页**（而不是直接停在主界面或空白 Web）；
   - 是否显示“已装入的运行时仍是上一版 / 内置补丁尚未落地”一类说明；
   - “更新运行时 / 重试”是否是主操作，且可点击；
   - 自动开始若被未确认旧 Web 会话拦下，页面是否仍给出明确手点入口（这是合格行为，不是故障）；
   - 手点「更新运行时」后，维护任务是否**自行**停止旧 Web 并继续，而不是要求用户先进受限主界面点停止。
3. **完成维护**：在维护页手点更新，**不需要先进入受限主界面点停止**；确认维护任务自行停止旧 Web（普通停止未确认时会按身份核验强制结束），随后候选准备、试运行、提交都完成。维护后 Web 是否正常启动、插件是否加载、侧栏是否只有一条设置入口。用诊断页 / 环境信息核对 `runtimeId` 已变成当前 APK 的候选。若停止阶段失败，确认页面给出原因与「重试更新运行时」，且原环境未改动。
4. **冷覆盖场景**：先正常停止 DSH 与终端，再覆盖安装同一对版本，确认维护页自动开始一次、成功后进入主界面且 Web 正常。
5. **插件回归**：在“首次进入无连接指示灯”和“连接后出现指示灯”两种状态下各看一次侧栏 footer：齿轮只出现一个、不覆盖账户按钮、账户菜单仍能打开设置。
6. **UI-only 反例**：用只改 UI、描述符未变的覆盖包验证不触发环境重建、无需维护。
7. **回执**：把“上一版 → 本版、安装方式、旧 Web 是否存活、看到的页面、自动开始是否被拦、手点后是否成功、Web / 插件结果、`runtimeId` 前后值”写进 `docs/releases/` 的版本记录；未覆盖的机型、ROM、外部模型矩阵如实标注。设备截图与日志不进入仓库。

> 本机（与 DSH 共享的构建环境）**不要**覆盖安装正式包；真机验收在独立设备进行。

## 反例：323 → 324 到底发生了什么

背景：build323 与 build324 内置同一个 dsh 版本 `0.2.2-rc.1`；324 只新增两条受管补丁（`DSHA_FIRST_CONNECT_SILENT_V1`、`DSHA_USER_ONLY_SKILL_APPROVAL_V1`）。补丁链本身没坏：324 的 `dsh-runtime.bin` 在构建期已烘入两个标记，`verify-runtime-descriptor-source` 也一致；用户看到的“补丁没更新上”其实来自“**环境根本没更新**”。补丁链侧的完整取证见 [build324 白屏排查记录](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md)与 [build325 齿轮修复记录](releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md)。

真机对照（2026-11-05）：把同一份 main 内容 + T61 齿轮修复的测试包装到设备后，**白屏消失、且能正常触发环境重建**；同一版其余真机项当时仍待用户补确认。T60 的展示层与入口修复随 build326 合入，**尚未真机验证**——不要因为 build325 真机通过就跳过 build326 的覆盖安装验收。

| 层 | 当时行为 | 用户可见结果 |
|---|---|---|
| 判定层 | `compatible()` 为真（同 dsh / 同基础契约），`latest()` 为假；`requiresMaintenanceBeforeStart` / `shouldBlockRuntimeStart` 正确返回“需要维护” | 判定正确，但没人把它变成可用界面 |
| 展示层 | 维护页用 `isEnvironmentReady()` 当“无事可做”，把“进入主界面”升为主操作；更新降为次要；受限入口不记受限身份；主界面横幅无回维护页入口 | 用户看到“跳过维护进主界面”摆在主位，维护永不开始；进了主界面又被门禁送回，来回跳 |
| 自动开始 | 旧 proot Web 仍存活 → `hasUnresolvedWebSession()` 为真 → `shouldAutoStart` 返回 false | “维护没有自动开始”，且没有明显的手点入口 |
| Web 启动准入 | `!isEnvironmentReady() || requiresMaintenanceBeforeStart` → `REJECTED` | 主界面 Web 区域空白；旧 Web 残留 + 插件 `:has()` 依赖失效 → 侧栏重复叠加；禁用内置插件才恢复 |

修复拆成四块，缺一不可：

1. **展示层（T60 / build326）**：`RuntimeUpgradeState` 把“可继续运行”和“是当前候选”分开；维护页 `ready` 同时要求 `runtimeLatest`；`limitedEntry` 计入补丁级未完成；主界面横幅在补丁级未完成时也留回维护页入口；维护页给出“内置补丁尚未落地”的说明。
2. **入口（T60 / build326）**：未确认旧 Web 会话只拦自动开始，不再阻止打开维护页；用户始终看得到唯一修复入口。
3. **插件依赖（T61 / build325）**：齿轮让位改实测宽度 + 自有属性，不再依赖宿主指示灯存在。
4. **维护前停止（T67 / build327）**：用户手点更新后由维护动作自行完成完全停止；普通停止未确认时按身份核验升级一次强制结束，失败给出可重试文案，不再要求用户绕道受限主界面点停止（I9）。

这次事故最容易被误判的点：

- 以为是 WebView / 网络 / 插件整体崩溃 → **不是**：Web 可能根本没被允许启动；若旧页面已加载，可见触发点是插件对宿主控件存在性的依赖；
- 以为是 `requiresMaintenanceBeforeStart` / `shouldBlockRuntimeStart` 的 bug → **不是**，两条路径都判对了；
- 以为是补丁没打进 `dsh-runtime.bin` → **不是**，标记在构建期已烘入；
- 以为是 WebView / 网络 / 插件整体崩溃 → **不是**，Web 根本没有被允许启动；
- 用“禁用内置插件”当长期修复 → 只是掩盖了插件对宿主控件存在性的依赖；
- 用“放宽 Web 启动门禁”消灭白屏 → 会让看门狗用过期运行时拉起 Web，是更严重的回归。

## 排障速查

| 现象 | 先查什么 | 不要做什么 |
|---|---|---|
| 覆盖安装后直接进主界面、Web 空白 | 维护页是否被打开；`EnvironmentAccess.requiresMaintenanceBeforeStart` 的返回值；`RuntimeDescriptor.latest(installed, expected)` | 不要改 WebView / 清缓存 / 重装 APK / 放宽启动门禁 |
| 维护页“进入主界面”是主按钮、更新是次要 | 维护页传入的 `ready` 是否只看 `isEnvironmentReady()`；`RuntimeUpgradeState.upToDate` 是否把 `latest` 算进去 | 不要只改按钮文案或样式；根因是判定输入错了 |
| 维护页进主界面又被弹回 | `limitedEntry` 是否记录了受限身份；补丁级未完成是否被算进限制条件 | 不要禁止主界面门禁；那只是把问题藏起来 |
| “维护没有自动开始” | `hasUnresolvedWebSession()`；上次任务是否 FAILED / INTERRUPTED；同一候选是否已尝试过 | 不要自动重放未知旧实例；应让用户手点 |
| 维护页失败说 Web 未停止 / 被迫去主界面点停止 | 维护动作是否走 `MaintenanceCoordinator.exclusiveUserConfirmed`；`MaintenancePreStop` 是否升级强制；停止错误码与 `WebProofStop` 的核验结论 | 不要把「主界面 → 停止」当成完成维护的必经步骤；不要放宽身份核验或按端口 / 名称杀 |
| 侧栏 footer 重复 / 齿轮挡住头像 | 是否仍依赖宿主 `:has(button[data-phase], [role="status"])`；`data-mobile-nav-quick-fit` 是否正常写入 | 不要把“禁用内置插件”当修复 |
| “补丁没更新上” | 先比 `runtimeId` / `inputs` 是否变成当前候选；再看 `dsh-runtime.bin` 标记与 `verify-runtime-descriptor-source` | 不要先怀疑补丁链本身 |

## 回归入口与相关文档

| | 内容 | 入口 |
|---|---|---|
| 覆盖升级门禁（322→323、323→324、合成补丁级前代、UI-only、未确认旧 Web） | `app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java`（T60 / build326） |
| `RuntimeUpgradeState` 纯逻辑 | `app/src/test/java/com/deepseekharness/app/util/RuntimeUpgradeStateTest.java`（T60 / build326） |
| 维护前自行停止与强制升级顺序 | `app/src/test/java/com/deepseekharness/app/core/MaintenancePreStopTest.java`（T67 / build327） |
| 覆盖更新弃旧版身份核验与 trial 收尾 | `app/src/test/java/com/deepseekharness/app/runtime/WebOverwriteRetireTest.java`（T91）、`TrialRecoveryTest`（缺 PID 证据的收尾）、`ManagedRuntimeTransactionTest`（半提交回切） |
| 试运行收尾顺序与有界强杀 | `app/src/test/java/com/deepseekharness/app/runtime/TrialGuestCleanupTest.java`、`TrialRecoveryTest`（强制后一次恢复关闭 / 仍非空留 `recovery-stop.json`）、`WebRecordedStopTest`（强制路径必须先复核出生身份）（T103） |
| 维护页旧失败快照清理 | `app/src/test/java/com/deepseekharness/app/util/RuntimeUpgradeStateTest.java`（T91） |
| 残留 Web 按出生身份核验回收（PID 复用 / 记录缺失不杀） | `app/src/test/java/com/deepseekharness/app/util/WebProofStopTest.java`（T67 / build327）、`WebSessionProofTest` |
| 停止算法与强制结束身份门禁 | `app/src/test/java/com/deepseekharness/app/runtime/WebRecordedStopTest.java`、`app/src/test/java/com/deepseekharness/app/util/WebForceStopTest.java`、`app/src/test/java/com/deepseekharness/app/core/WebForceStopGateTest.java` |
| 移动壳齿轮契约 | `tools/test-mobile-gear-contract.mjs`（T61 / build325） |
| 维护事务与停止屏障 | [`MaintenanceGateTest`](../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java) |
| 运行环境维护页实现 | [`ExtractActivity`](../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java)、[`MaintenanceFooterPolicy`](../app/src/main/java/com/deepseekharness/app/util/MaintenanceFooterPolicy.java) |
| Web 启动准入与旧会话恢复 | [`WebLifecycleController`](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)、[启动与诊断](startup-and-diagnostics.md) |
| 受管更新事务与试运行 | [`EnvironmentMaintenance`](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)、[安装与维护](install-and-maintenance.md) |
| 应用更新与签名 | [应用更新](update.md)、[下载校验](download-verification.md) |
| 固定输入与描述符 | [固定构建输入](fixed-build-inputs.md)、[环境、数据与插件机制](runtime-data-plugin-architecture.md) |
| 移动 Web 适配补丁 | [移动 Web 适配补丁](web-mobile-patches.md) |
| 324 首次连接静默事故记录 | [build324 记录](releases/v0.2.1-rc.5-build324-first-connect-indicator-20261105.md) |
| 323 升级记录 | [build323 记录](releases/v0.2.1-rc.5-build323-dsh022-s1-connection-display-20261105.md) |
| 324 白屏排查记录 | [build324 白屏排查](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md) |
| 325 齿轮修复记录 | [build325 齿轮记录](releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md) |
| 326 覆盖安装维护判定记录 | [build326 记录](releases/v0.2.1-rc.5-build326-overwrite-maintenance-20261105.md) |
| 327 维护前自行完全停止记录 | [build327 记录](releases/v0.2.1-rc.5-build327-maintenance-self-stop-20261105.md) |
| 英文契约 | [English contract](en/overwrite-upgrade-and-maintenance.md) |
| 外部 PR / 外部合并审查 | [外部 PR 与外部合并审查流程](external-pr-review.md) |
| 契约整组工作流 | [`.github/workflows/_node-contracts.yml`](../.github/workflows/_node-contracts.yml) |
