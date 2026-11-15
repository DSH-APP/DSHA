# DSH Web 独立进程（`:web`）设计

状态：**设计稿，待用户确认；未实现、未构建、未真机验证**。日期 2026-11-02，基线 build257（`9adc5aff`）。
来源：T14 结论（主进程 ANR → 用户「关闭应用」→ 主进程连同子进程 DSH Web 一起结束，全部会话中断）。

证据分级：**源码事实**＝本基线代码（路径相对 `app/src/main/java/com/deepseekharness/app/`）；
**平台事实**＝AOSP 行为，附来源；**估计**＝未测量的工作量或资源预算。

## 0. 结论

1. **可行，收益明确但有边界。** Android 结束一个进程时按 `uid_<UID>/pid_<PID>` cgroup 回收它和它 fork 出的全部子进程
   （[AOSP: use killProcessGroup to kill forked processes](https://android.googlesource.com/platform/frameworks/base/+/0161bbc6e54744d5e0348af3d123858818f966e9%5E%21/core/jni/com_android_internal_os_Zygote.cpp)）。
   今天 Web 的 proot/proroot 由主进程 `ProcessBuilder` 拉起（`runtime/RuntimeLauncher.java:125-135`），主进程死它必死。
   把 Web 生命周期放进独立的 `:web` 进程后，**界面 ANR、界面崩溃、主进程被单独回收**不再带走 DSH。
2. **保不住的情况不变**：强行停止、覆盖安装（PACKAGE UPDATED）、清数据、整 UID 回收、部分 OEM 划卡清理会结束全部进程；
   `:web` 自身崩溃仍会带走 Web。本设计不宣称“DSH 永不掉线”。
3. **最大的工作量不在进程声明，而在把同步的进程内调用改成“镜像 + 命令”**：约 55 个文件同步调用 `HarnessController`，
   维护屏障（`MaintenanceGate` / `EnvironmentTaskGate` / `RuntimeTasks`）全是 static 进程内锁。
   拆进程后它们会静默失去互斥，这是首要风险。
4. **硬规则：主线程不得对另一进程做同步阻塞调用。** 否则只是把 ANR 从“卡自己”换成“被对方卡”。
   主进程读 Web 状态一律读本地镜像，镜像由 `:web` 单向（oneway）推送。
5. 估计总工作量 **2–3 周**（分 5 阶段，每阶段可单独发版、行为可回退）。

## 1. 目标与非目标

目标：
- 主进程 ANR / 崩溃 / 被单独结束时，DSH Web 与其中的 Agent 会话继续运行，端口与鉴权不变。
- 主进程重新启动后自动重新附着：界面显示正确的运行态，可正常停止、预览、LAN。
- 维护（备份、恢复、受管更新、环境修复）仍保证“Web 已停止且不会被重启”，任何一方死亡都不能放开屏障。

非目标：
- 不把 3090 桥、无障碍、悬浮条、虚拟屏、PTY / 简易终端搬出主进程（理由见 §3）。
- 不改 `WebProcessManager` 的停止语义（哨兵 + pid 文件 + 出生身份），不新增按名称/端口终止。
- 不改 proot / proroot 选择、`WebRuntimeFallback` 一次性重试规则、`RuntimeTrial`。
- 不处理强行停止 / 覆盖安装场景（平台限制）。

## 2. 现状（源码事实）

| 方面 | 现状 | 位置 |
|---|---|---|
| 进程 | 只有 `ShellService` 声明 `:shizuku`；其余组件都在主进程 | `AndroidManifest.xml:213-219` |
| 启动 | `HarnessController` 是门面，逻辑在 `WebLifecycleController`（1930 行）；`dsh-io` 单线程执行 `startWeb` → `ProotBootstrap.execRootfs` → `ProcessBuilder.start()` | `core/WebLifecycleController.java:924-1182` |
| 内存态 | `WebRun`（launcher `Process`、authUrl、port、bridge lease、registration）、generation、监听器都在 `HarnessSessionState` | `core/HarnessSessionState.java:10-62` |
| 鉴权捕获 | `dsh-drain` 线程读 stdout 抓链接，随后写 port / 身份 / `StoredWebSession` | `WebLifecycleController.java:1155-1252` |
| 看门狗 | `HarnessService` 的 `dsha-keepalive` 每 15 秒探测，连续 3 次失败重启，冷却 120 秒；同线程还管 LAN、DeviceBridge、WakeLock | `HarnessService.java:351-603` |
| 停止 | 哨兵 → pid 记录 → `/proc` + `kill(pid,0)` + 出生身份 → SIGTERM；不要求调用方是父进程 | `runtime/WebProcessManager.java:254-526,908` |
| 接管 | 进程重启后 `restoreRunningWebSession` 读加密 `StoredWebSession` 只读核验仍存活的 guest | `WebLifecycleController.java:158+` |
| 桥 | `HttpShellService` 是主进程 static 单例，直接调无障碍、悬浮、虚拟屏；对 Web 只查 generation / 运行态 / registration | `HttpShellService.java:98-125,695,974-990…` |
| 维护 | `MaintenanceCoordinator.stopWeb` 进程内轮询 `isStarting/isStopping/isWebStoppedForMaintenance`；`HostMaintenancePending` 是磁盘日志 | `core/MaintenanceCoordinator.java:49-90` |
| IPC | 无任何 Web 相关 Binder；`HarnessService.onBind` 返回 null | `HarnessService.java:637` |

## 3. 进程划分

```
主进程（UI / 设备能力）                         :web 进程（Web 生命周期）
├─ Activity / Fragment / WebPreview            ├─ WebHostService（前台服务，specialUse）
├─ HttpShellService 3090 桥                     │   ├─ WebLifecycleController / WebProcessManager
├─ DshaAccessibilityService / Overlay / 虚拟屏  │   ├─ dsh-io / dsh-drain / 看门狗
├─ PTY / 简易终端 / TerminalRuntimeService      │   ├─ LanProxyService（随实际端口）
├─ 维护、备份、受管更新、RuntimeTrial           │   └─ WakeLock / WifiLock（仅 Web 运行时）
├─ DeviceBridgeService、通知、确认               └─ proot/proroot → node（DSH Web）
└─ WebStateMirror（只读镜像）  ←── oneway 事件 ──┘
         └── 命令（工作线程，带超时）──→ IWebHost
```

留在主进程的理由：
- **桥**直接调用无障碍 / 悬浮 / 虚拟屏，这些服务由系统绑定在主进程，搬走等于把整个设备层搬走。
  主进程死亡期间手机控制工具不可用（返回连接失败），Agent 的编码会话不受影响；桥重启时重写
  `/root/.dsh/.bridge_headers`（`util/BridgeCredentialFiles.java:20`），guest 每次请求重新读取，不需要重启 Web。
- **终端**出生身份要求父进程就是本进程（`PtySession.java:369-374`，`util/ProcessIdentity.java:38`），且维护必须在同一进程里先关终端再取屏障。
- **RuntimeTrial** 是维护流程的一部分，持维护租约（§6）运行，不与正式 Web 竞争。

`HarnessService` 拆分：Web 相关（前台通知、看门狗、Web WakeLock、LAN）进 `:web` 的 `WebHostService`；
DeviceBridge 拉起与主进程保活留在主进程的精简 `HarnessService`。前台服务必须与 Web 在同一进程，否则 `:web` 优先级过低。

## 4. 跨进程接口

`IWebHost`（AIDL，`exported=false` 的绑定服务；`onTransact` 再核对 `Binder.getCallingUid() == Process.myUid()`）：

| 方法 | 方向 | 说明 |
|---|---|---|
| `start(in StartRequest)` / `stop(int reason)` / `restart()` | 主 → web，工作线程 | 立即返回受理结果与 generation；完成由事件通知 |
| `beginMaintenance(String lease)` / `endMaintenance(String lease)` | 主 → web，工作线程 | 见 §6；阻塞到 Web 已停止或超时（沿用 45 秒） |
| `snapshot()` | 主 → web，工作线程 | 重新附着时拉全量状态 |
| `exchangeAuthCookie(...)` | 主 → web，工作线程 | 预览页需要时才调 |
| `register(IWebEvents)` | 主 → web | 注册回调；`:web` 对回调 `linkToDeath` |
| `IWebEvents.onState(in WebState)` | web → 主，**oneway** | generation、阶段、port、authUrl、页身份、registration 快照、错误 |

`StartRequest` 由主进程从 `ConfigStore` 组装（端口策略、引擎、DNS、workdir 等运行设置快照），`:web` 不读主进程的 SharedPreferences。
API Key 由 `:web` 直接经 `KeyVault` 解密（Keystore 按 UID，多进程可用）；为避免首次并发生成别名，主进程在首次绑定前确保别名已存在。

`WebStateMirror`（主进程）：
- 替代 `HarnessController` 的全部只读查询（`getWebAuthUrl/getWebPort/getWebGeneration/isWebRunning/isStarting/isStopping/getReadyTaskRegistration…`）以及 `add/removeReadyWebPageListener`。
- 未连接时状态为 `UNKNOWN`（不是“已停止”）；界面显示“正在连接 DSH”，桥对需要 registration 的请求返回明确的不可用错误，不猜测。
- 绑定断开（`:web` 死亡）→ 置 `UNKNOWN` 并按 §7 处理。

## 5. 共享状态逐项处理

| 状态 | 风险 | 处理 |
|---|---|---|
| `DshaApp.onCreate` 不分进程 | `:web` 重复锁存语言、初始化 Shizuku、调度备份/清理、装插件回调、共写崩溃日志 | 按 `Application.getProcessName()` 分支：`:web` 只做 `SystemLanguage.initialize`、语言/文案、通知渠道、独立崩溃日志文件；其余跳过。`:shizuku` 是否运行 `DshaApp` 需核验 |
| `MaintenanceGate` / `EnvironmentTaskGate` / `RuntimeTasks`（static） | 两个进程各一份，互斥失效 | 进程内锁保留；跨进程用 §6 的租约 + 文件锁 |
| `ConfigStore`（SharedPreferences） | 多进程写冲突、缓存不一致 | 主进程是唯一写者。Web 侧运行事实（备用端口记忆、启动恢复请求、`WebRecovery` 计数）由 `:web` 写入自己的原子替换文件，经事件同步给主进程后由主进程落 `ConfigStore`；不改历史键名，旧键继续由主进程读取 |
| `StoredWebSession.RECORD_LOCK`（static） | 两进程同时写加密会话记录 | 只允许 `:web` 写；主进程只读，不再调用写路径 |
| `KeyVault` 首次生成 | `containsAlias` 后生成的竞态 | 主进程绑定前预建别名 |
| `LanProxyService`（static） | `ConfigFragment` / `LanAuthBridge` 直接 static 调用 | 随 Web 进 `:web`，主进程改为命令 + 镜像 |
| `HttpShellService.revokeScreenGrant` / `VirtualScreenManager.stop` 绑在 Web 停止上 | 停止回调跨进程 | 主进程收到 generation 变化或停止事件后本地撤销；主进程不在线时，重启后以 generation 不匹配自然失效 |
| 进程扫描 `confirmNoUntrackedWebLaunchers` | 只排除 `myPid`，看到终端的 proot 就返回 false | 与今天单进程行为相同（主进程 pid 不是启动器），不新增误判；记录为既有限制 |
| `captureSession` 需要启动器 `Process` 对象 | 只能在启动它的进程里做 | 天然在 `:web` 里完成，主进程不涉及 |

## 6. 维护屏障跨进程协议

原则：**屏障的建立必须双向确认，任何一方死亡都不能自动放开。**

1. 主进程取本地 `MaintenanceGate` 独占（含先关终端，沿用现顺序）。
2. 主进程在私有目录持有 `env-task.lock` 的独占 `FileChannel.lock`（Android 应用进程使用 bionic `fcntl`，不受 proroot vendor 符号影响），
   并写入租约文件 `web-maintenance.lease`（租约 id、主进程 pid + 出生时间、开始时间）。
3. 调 `beginMaintenance(lease)`：`:web` 经 `WebProcessManager` 停止 Web、确认 `isWebStoppedForMaintenance`，记住租约后返回。
4. 维护结束：先删租约文件、释放文件锁，再调 `endMaintenance(lease)`。

`:web` 的启动准入（替代今天进程内的 `isEnvironmentTaskBusy` 检查）：
- 存在租约文件、`HostMaintenancePending.blocked(filesDir)` 为真、或拿不到 `env-task.lock` 的共享锁 → 拒绝启动，事件里给出原因。
- 启动期间持共享锁，鉴权成功后释放（与今天 `EnvironmentTaskGate.tryAcquireShared` 只覆盖启动阶段一致，需实现时逐行核对）。
- 主进程在维护中死亡：文件锁随进程释放，但租约文件仍在 → `:web` 继续拒绝启动；主进程重启后由维护页按现有未完成事务流程处理，
  确认 `HostMaintenancePending` 清除后再删除孤儿租约。**不按超时自动删除租约。**
- `:web` 在维护中死亡：Web 子进程随 cgroup 结束；主进程收到 binderDied，屏障本来就要求 Web 已停止，维护继续，结束后不自动拉起 `:web`。

## 7. 故障场景

| 场景 | Web | 主进程重启后 | 备注 |
|---|---|---|---|
| 主进程 ANR，用户选“关闭应用” | 继续运行 | 绑定 `:web` → `snapshot()` → 镜像恢复；桥重新发布凭据 | 本设计的主要收益；需真机验证 ANR 只回收主进程 |
| 主进程崩溃 / 被单独回收 | 继续运行 | 同上 | 手机控制在主进程不在时不可用 |
| `:web` 崩溃 | 随 cgroup 结束 | 主进程镜像置 `UNKNOWN` → 读取停止原因；未被用户停止且无维护时按现有自动重启策略（冷却 120 秒）重启一次 | 不把 `START_STICKY` 当作重启策略，避免越过用户停止哨兵 |
| 用户停止后 `:web` 被杀 | 已停止 | 哨兵存在 → 不复活 | 沿用 `/root/.dsha-stopped` |
| 维护中任一方死亡 | 保持停止 | 见 §6 | 不自动放开 |
| 强行停止 / 覆盖安装 / 清数据 | 全部结束 | 与今天相同 | 平台限制 |
| 内存紧张 | `:web` 有前台服务，优先级高于后台主进程 | 主进程可能先被回收，Web 继续 | `:web` 多一个 ART 进程，估计 30–60 MB PSS，需实测 |

可选（待决 D3）：`:web` 以 `BIND_AUTO_CREATE` 绑定主进程的一个轻量服务，使系统在主进程死亡后自动把它拉起，从而尽快恢复桥。代价是主进程反复 ANR 时会被反复拉起（系统有崩溃退避）。

## 8. 分阶段计划（每阶段可单独发版）

| 阶段 | 内容 | 行为变化 | 估计 |
|---|---|---|---|
| P0 | `DshaApp` 按进程名分支（暂无 `:web` 组件，只加分支与单测）；把约 55 处 `HarnessController` 只读调用收敛到 `WebStateView` 接口（进程内实现） | 无 | 2–3 天 |
| P1 | 引入 `WebStateMirror` 与事件模型，仍在同一进程；主线程禁止同步等待 Web（测试包加断言） | 无（应减少主线程等待） | 2–3 天 |
| P2 | 维护租约 + `env-task.lock` + Web 运行事实文件，仍在同一进程跑通，补单测（死亡、孤儿租约、重入） | 无 | 2–3 天 |
| P3 | 新增 `:web` 的 `WebHostService` + `IWebHost`；迁入生命周期、看门狗、LAN；拆 `HarnessService` | **进程拆分生效** | 3–5 天 |
| P4 | 测试包真机验收（§9），修问题后发正式版 | — | 1–2 天 + 用户时间 |

纯逻辑（租约判定、准入判定、镜像状态机、事件合并）放 `util/` 并配单测，遵守“零 Android import”。
`HarnessController` 不再加职责：新逻辑写成独立协作者（`WebStateMirror`、`WebHostClient`、`WebMaintenanceLease`）。

## 9. 验收（只用测试包 `io.github.sunsetrne.dsha.test`，需用户操作手机）

测试包增加仅 `sunsetTest` 可见的诊断入口：“结束界面进程”（`Process.killProcess(myPid())`）与“主线程阻塞 10 秒”（触发 ANR）。

1. Web 运行并有进行中的对话 → 结束界面进程 → 对话继续，`curl` 原端口可用；重开 App 显示运行中，可停止。
2. 触发 ANR → 选“关闭应用” → 同 1。
3. 结束 `:web`（测试入口）→ Web 退出，主进程不误报运行；按策略重启一次。
4. 用户停止后结束 `:web` → 不复活。
5. 备份进行中结束主进程 → Web 不启动；重开后维护页处理未完成事务，清除后才可启动。
6. proot 与 proroot 各跑一遍 1–4；记录 `:web` PSS。
7. 回归：PTY / 简易终端、LAN、预览、画中画、虚拟屏、手机控制任务、覆盖更新维护页。

验收只声明 Lenovo TB321FU / Android 16 一台设备的结果，不外推到其他 ROM（部分 OEM 划卡会清整个包）。

## 10. 风险

- **静默失去互斥**：漏改一处 static 屏障即可能在维护中启动 Web。对策：P2 先在单进程里切到新协议并删除旧准入路径，再拆进程；代码检索 `MaintenanceCoordinator.isEnvironmentTaskBusy` 等调用点逐一清零。
- **跨进程卡死**：任何 Binder 同步调用都放工作线程并带超时；`:web` 回调一律 oneway。
- **重复初始化**：`:web` 跑了不该跑的初始化（Shizuku 监听、备份调度）。对策：进程分支单测 + 启动日志标出进程名。
- **状态不一致窗口**：主进程刚重启、镜像未同步时界面误报。对策：`UNKNOWN` 独立状态，绝不默认“已停止”。
- **内存**：多一个 ART 进程；与 Gradle 等并发时更紧张，P4 实测后决定是否接受。

## 11. 待用户决定

- **D1** 是否立项实现（按 §8 分 5 阶段、约 2–3 周）。
- **D2** LAN 代理随 Web 进 `:web`（建议）还是留主进程。
- **D3** 是否让 `:web` 绑定主进程以便系统自动拉起主进程、尽快恢复手机控制（§7 可选项）。
- **D4** 发版节奏：P0–P2 各自小版本（建议，便于回退）还是 P3 一次发。
