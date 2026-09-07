# rc1.4 备份页面状态与环境维护

## 最终集成验收 · 2026-09-08

主流程已完成两版构建、Lint 和单测，以及 Android 13 最终维护全链 **91 条通过**、运行登记 **16 条通过**、原生停止边界 **36 条通过**。真实用户环境的自动备份、Web 运行中备份和实际 Node/launcher 停止屏障也通过。进程被保留期间拒绝自动回切，退出后才能恢复；首次安装停止无副作用、PID 断链拒绝、PTY 及简易终端回收均已补验。证据见[功能验收](functional-audit-rc1.4.md)。

以下保留实现及协作者阶段记录；其中“仍需执行”“本 worker 未运行”等描述是当时状态，不代表最终集成未验收。

范围：WorkspaceFragment / ExtractActivity / SettingsFragment，新增 BackupTask、EnvironmentMaintenance、BackupTaskState、MaintenanceTransaction、EnvironmentTaskGate、RuntimeTaskRegistry；后续授权补充 RuntimeTasks 维护保护与 PTY 异步寿命登记。BackupManager 只增加公开查询/安全任务入口；本 worker 未改 ProotBootstrap、版本或 debug manifest，也未运行 Gradle、设备命令或删除用户文件。

## 实现与边界

- 所有页面数据动作使用应用级 BackupTask。RUNNING/PREVIEW 均占用单一任务，页面重建只读取快照；预览用任务 ID 确认。结果与阶段同步写独立偏好，进程重建显示 INTERRUPTED，不自动重试破坏性动作。
- 备份和恢复预检使用 runSnapshotTask，不停止正在运行的 Web。快照过程中数据变化由现有引擎报错，用户可等任务完成后重试。恢复预览等待时不持有归档 LOCK/RuntimeTasks，只保留原子任务 Lease；明确确认恢复才调用 runDataTask 停止 Web。相关确认 UI 明示最终恢复/维护会中断运行中的任务。
- EnvironmentTaskGate.tryAcquire(kind) 在发布 RUNNING 前原子取得可转交 worker 的 Lease；获取失败立即拒绝。worker 的 try(lease) 包住 lease.run(() -> ...)，直到状态/结果收尾再释放。Lease 自身不停止 Web，也不等待；安装检查/repair 可持同一 Lease 防止环境切换。禁止在 lease.run 内提前 close 或让同一 Lease 同时运行两个 worker。
- BackupManager.runDataTask 先占用启动门控（现有 isRestoring），再等待 Web 停止队列和宿主 PID 消失，最后获取原有归档 LOCK。不能反过来拿锁，否则与启动前自动备份形成互等。45 秒内不能确认停止则失败；探活权限错误不能当成已停止。
- 停止条件还要求 Controller.hasLiveWebProcesses() 为 false，覆盖所有已登记代次的 Web launcher；只检查存活，不强杀 proot。停止完成后、创建自身 RuntimeTasks 前，tryEnterMaintenance 原子拒绝其他线程工作和异步终端，并在维护期间拒绝新的外部 RuntimeTasks。当前线程上层同步作用域可嵌套，外层 token 不会被释放。PTY 使用 beginDetached 且在任何准备动作前登记；Proot 旧交互终端对应改动交安装 owner 接线。
- 快照→快照嵌套可重入，已有 Lease 不重复获取/释放；快照→停止维护被 Thread.holdsLock(LOCK) 明确拒绝，防止误用形成锁升级互等。stopWebForMaintenance 也拒绝 Android UI 线程；dsh-io 队列仍不能调用等待自身停止的方法，自动备份专用入口只有快照路径。
- 维护首先创建完整 v3 归档并完成引擎逐文件检查、复制摘要检查及私有文件重新读取校验。归档放 files/maintenance/UUID/safety.tar.gz，位于 linux 外。
- 只有校验通过后才记录 intent 并将旧 files/linux 改名为 previous-linux。新环境从真实 APK 解压，移开内置 .dsh 后恢复，让会话/设置成为新私有目录里的真实文件；本地插件和运行依赖由 v3 内联源码恢复。
- 失败将新 linux 保留为 failed-linux，再回切 previous-linux。成功保留旧环境和安全归档，不自动清理。恢复前或切换前的失败均不移走旧环境。
- 源环境不可运行 Python、数据链接断裂、插件源码丢失或空间不足时，保守拒绝重建，不把坏环境当作空环境清掉。额外安装的系统软件仍在旧环境，未自动迁入新环境。

## 协调接入（由对应 owner 修改）

进程重启时静态 isRestoring 不存在，必须查磁盘 intent。以下 Main/Controller/InstallRepository/InstallFragment/Proot pending 接线已由主线程与安装 owner 实现，本 worker 已只读核对；仍需遵守这些调用约束：

1. MainActivity 的解压门禁加入 `BackupManager.hasPendingMaintenance(controller)`，有未完成维护时进入 ExtractActivity。不能让 skip_extract 绕过此检查。
2. HarnessController.requestStart 及排队 start 的检查加入 `BackupManager.hasPendingMaintenance(controller)`，拒绝启动并提示进入维护页。不要在 dsh-io 队列中调用 recoverMaintenanceBeforeStart；它会等待同一队列的停止任务。
3. ProotBootstrap 的自动/安装解压入口在执行前检查 `BackupManager.hasPendingMaintenance(ctx.getFilesDir())`，非数据任务 owner 必须拒绝覆盖。`BackupManager.isDataTaskOwner()` 是合法维护线程的豁免，真实维护在持锁期间调用现有 extractOfflineBundle。
4. 如需在启动 UI 前主动回滚，可在独立后台线程调用 `BackupManager.recoverMaintenanceBeforeStart(controller)`；或由 ExtractActivity 的“恢复中断维护”交 BackupTask 执行。
5. HarnessController 启动前自动备份使用 `BackupManager.backupForAutomaticLaunch(ctx, this)`，它通过 runSnapshotTask 取得/复用 Lease，再调用现有备份引擎，不调用 stopWeb。已有同线程 Lease 时不重复获取/释放它；其他线程持有 Lease 时立即跳过，不等待。用 `isEnvironmentTaskBusy()` 阻止安装/数据任务期间的新 Web 启动。InstallRepository 已在发布 running 前取得 Lease、整个 worker 收尾后释放，InstallFragment 旧直接 uninstall 入口已收口到 BackupTask。

主线程随后提供：完整 MaintenanceFixture 真机 53 assertions PASS，真实用户环境的启动前自动备份、普通全量备份保持 Web generation/运行、实际停止队列/PID 屏障与 Gate 释放均 PASS。下面新增的 RuntimeTasks 拒绝、launcher 等待及首次安装用例属于此后补充，仍需重新编译后在设备执行。

## 已执行的本地验证

- javac + JUnit：BackupTaskStateTest / MaintenanceTransactionTest / EnvironmentTaskGateTest / RuntimeTaskRegistryTest，25 tests PASS。新增 7 项覆盖同线程同步嵌套、异步寿命不可豁免、跨线程退出关闭、旧任务拒绝、维护期间新任务拒绝、异常释放及 40 轮维护/任务同时登记竞争。所有文件数据来自 JUnit TemporaryFolder。
- 不经 Gradle，使用现有 Android 37 jar、已构建应用类和依赖缓存，javac --release 17 编译本次生产代码及新增 debug instrumentation，通过。它是 Java 类型/语法检查，不替代 APK 集成构建或真机结果。
- Windows 的现有 test-backup-engine.py：11 PASS、4 个 Linux 专属测试跳过、1 个 POSIX 工作区绝对路径 fixture 失败（Windows 路径不以 `/` 开头）。未修改不归本 worker 所有的测试/引擎。主线程反馈本轮 Linux 16 项备份及 27 项插件测试均 PASS，以主线程 Linux 结果为准。

## Android debug 自插桩入口（未自行执行）

类：`com.deepseekharness.app.core.MaintenanceFixtureInstrumentation`

源文件：`app/src/debug/java/com/deepseekharness/app/core/MaintenanceFixtureInstrumentation.java`

由持有 debug manifest 的主线程新增下面一项；本 worker 未修改 manifest，且不需要额外 test APK：

```xml
<instrumentation
    android:name="com.deepseekharness.app.core.MaintenanceFixtureInstrumentation"
    android:targetPackage="com.dsh.client"
    android:functionalTest="true" />
```

完整用例（设备操作由主线程执行）：

```text
am instrument -w -e case all com.dsh.client/com.deepseekharness.app.core.MaintenanceFixtureInstrumentation
```

快速运行边界用例：`case runtime`，仅创建小型专属文件，验证旧后台任务拒绝、异步终端拒绝、同步嵌套保留、维护期间新任务拒绝和 launcher 退出谓词，不解压 assets。首次安装用例：`case fresh`，从 linux 不存在开始，直接校验真实 WebProcessManager.stop 不创建目录，再经 BackupTask→runDataTask→rebuild 完成真实 assets 初始化。`case failure` 和 `case all` 同样先跑首次安装完整链及 runtime 用例，all 额外执行真实维护重建、恢复、预览锁与结果持久化检查。

隔离方式：ContextWrapper.getApplicationContext 返回自身；getFilesDir/getCacheDir 指向真实 App cache 下全新 UUID fixture；所有 SharedPreferences 使用同一 UUID 名字前缀；不使用 HarnessController 或 BackupTask 的应用单例。FixtureHarness 不经全局停止队列，调用指向隔离目录的真实 WebProcessManager，并强制拒绝 fixture 内出现任何 PID 文件；生命周期/launcher 谓词可控，不向真实进程发信号、不启动 Web、不访问公有 Download。假公有 symlink 指向 fixture 容器自身 /root 下的专属目录，未绑定实际用户 Documents/dshdata。假 API 凭据仅是容器普通文本，不读写真实 Keystore key。

检查内容：

- 真实引擎遇到断链数据备份失败，旧环境仍在原位。
- 真实备份成功后注入解压失败，旧私有数据逐字一致，失败新环境和归档保留。
- 用专属 Error 故障注入绕过自动回切，保留切换中断现场；公开恢复入口重新读取磁盘日志并回切，验证私有数据一致。
- 全链运行前人为保持停止屏障，确认环境切换尚未开始；释放后才继续。
- 真实 assets 重建并恢复私有文件、配置、公有链接指向的会话数据、本地插件及其 node_modules 运行依赖。
- 新环境不存在原假公有目录/原本地插件目录，恢复数据与插件仍可读取，证明不依赖旧 symlink。
- 维护/恢复预览期间重复重建、重置及第二数据任务被拒绝；旧任务 ID 不能确认；取消不覆盖；明确确认后真实恢复成功。
- 预检/取消的 stop 调用计数保持不变，最终确认恢复后计数才增加；预览等待不持有 RuntimeTasks。自动备份在现有 Lease 中进入并在导出前注入失败，验证外层 Lease/RuntimeTasks 未误释放；另一线程的自动备份遇到预览 Lease 在 3 秒内返回，不死锁且不停止 Web。
- 在真实快照回调内尝试 runDataTask，会立即拒绝且 stop 计数不变；在 Android UI 线程尝试停止等待也立即拒绝，不操作 Web。
- 新 BackupTask 宿主读取相同 UUID 偏好，恢复已有结果，不重复执行任务。

输出 `status=PASS/FAIL`、`assertions` 和 `fixture` 绝对路径。fixture 及其安全副本原样保留；除 runtime 外至少预留 3 GiB 空间，空间不足会停止，不清理任何目录。新增 launcher/RuntimeTasks 边界仍用可控生命周期及真实任务登记验证；真实进程残留与停止超时场景由主线程补充设备验收。
