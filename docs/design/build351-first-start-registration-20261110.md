# build351 覆盖升级首次启动失败排查（T133）：插件注册超时的根因分类

> 状态：源码级排查报告，**不改生产代码**；结论供 build352 修复任务使用。
> 基线 `origin/main` = `bc9bce87d`（build351 发布提交）。证据来自 2026-11-10 04:23–04:28（+08）
> 一台 OnePlus PJD110 的维护页 / 启动恢复页截图与主会话记录；本机（TB321FU，同为 build351 正式包）
> 只提供正常启动基线对照，**没有**在该机复现覆盖升级（不在本机覆盖安装正式包）。
> 截图与用户对话内容不进入仓库，本文只保留应用自产生的错误码、阶段名与时间点。

## 0. 结论摘要

归类：**③ 机制缺失（主因）+ ① 设计链路（加重）**，并以 **② 语义不足** 放大成用户看到的「原因未分类」。

- **直接失败点**：启动流水线「注册插件」阶段调用 `register-builtin-plugins.py`，
  该 guest 命令的宿主硬预算只有 **90 秒**，命令被预算杀死（`BUILTIN_REGISTER_TIMEOUT, exit=-1, timedOut=true`）。
- **③ 机制**：覆盖升级后的**第一次**启动要补做受管资产覆盖层与共享依赖链接修复（受管资产身份变化 → 链接缓存失效），
  但预算仍是单条固定 90s：没有分阶段预算、没有进度续期、没有可恢复语义；失败时只保留输出尾部 1200 字符，
  无法判断时间花在哪个子阶段。
- **① 设计链路**：超时强杀只结束 guest 进程，**不清理 profile writer 锁文件**（`package.json.lock`）；
  恢复页随后按「锁文件存在即视为配置正在修改」报出第二张卡（插件清单不可读）。这不是第二个故障，
  是超时被杀的连带产物。重试能过，是因为下一次注册会**接管死锁**（take-over）且链接修复幂等，
  并不代表"等一会儿锁就释放了"。
- **② 语义**：`BUILTIN_REGISTER_TIMEOUT` 不匹配 `StartupRecoveryState.classify` 的任何模板，
  页面落到 `Kind.FAILED` →「启动失败：原因未分类 / STARTUP_FAILED」，并把 `exit=-1 timedOut=true`
  与完整 JSON 行原样展示；没有"插件注册超时，可重试"这类可行动结论。
- **待解释的证据矛盾**：`REGISTER_TIMING: {"phase":"runtime-links-stamp","elapsedMs":479409,"entries":101}`
  与宿主 90s 硬预算、以及「04:25:03 仍在维护 4/5 → 04:27:11 已是失败页」的时间线**互相矛盾**，
  不能据此把耗时归因到 `runtime-links-stamp` 子阶段。需要设备上的完整输出/启动历史记录来定案，
  取证清单见第 7 节；这个矛盾本身就是 ② 的证据缺口。

## 1. 证据与时间线

| 时间（+08） | 现象 | 来源 |
|---|---|---|
| 04:23:21 | `adb install -r` build351 成功，versionCode=351，旧数据保留 | 主会话 `pm dump` 记录 |
| 04:25:03 | 维护页「4/5 · 保留并切换受管文件」，第 5 步（隔离试运行与就绪检查）尚未开始 | 截图 |
| 04:27:11 | 启动恢复页：`启动失败：原因未分类`；详情 `启动失败：BUILTIN_REGISTER_TIMEOUT: exit=-1 timedOut=true truncated=false output=REGISTER_TIMING: {"phase":"runtime-links-stamp","elapsedMs":479409,"entries":101}` 与 `RUNTIME_LINKS_CHECK: 检查共享依赖链接`；`失败阶段：注册插件 · STARTUP_FAILED`；插件区 `STARTUP_PLUGIN_EXIT_1: exit=1 timedOut=false truncated=false output=PLUGIN_RESULT: {"status":"error","message":"插件配置正在修改或有待确认的锁；保留上次列表，请稍后刷新"}` | 截图 |
| 04:28:10 | 点「重试启动」后 **READY**，实际 Web 端口 42117，旧会话数据保留、无白屏 | 截图 |

本机正常启动对照（同一 build351，`files/startup-history` 中的 ready 记录）：

```
STARTUP_PREP_TIMING: phase=prepare-plugin-helpers durationMs=41  ...
STARTUP_PREP_TIMING: phase=register-builtins      durationMs=870 ...
REGISTER_TIMING: {"phase":"runtime-links-stamp","elapsedMs":64.538,"entries":101}
RUNTIME_LINKS_CHECK: 检查共享依赖链接
REGISTER_TIMING: {"phase":"runtime-links-repair","elapsedMs":530.843,"changed":0}
REGISTER_TIMING: {"phase":"runtime-links-cache-write","elapsedMs":67.282}
BUILTIN_REGISTER_OK: 10 个内置插件注册就绪
```

即：正常路径 register 约 0.9s、stamp 约 65ms、101 个条目；失败现场的 `479409ms` **不是正常量级**
（相差约 7400 倍），而且与第 3.2 节的 90s 预算冲突。

## 2. 启动链与失败点（file:line）

| 环节 | 位置 | 行为 |
|---|---|---|
| 维护 5 步 | `app/src/main/java/com/deepseekharness/app/util/DeployStages.java:146-153` | 停止 → 解压 dsh → 准备内置插件/界面适配 → 保留并切换受管文件 → **隔离试运行与就绪检查** |
| 维护提交 + 试运行 | `app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java:143-155` | `transaction.commit(...)` 内跑 `RuntimeTrial.verify(...)`，再 `confirmRuntimeHealth` / `isEnvironmentReady` |
| 维护阶段的运行时校验 | `app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java:438-453` | update 路径传 `register=false`；只有 `validateRuntime(proot)` 单参重载才注册（`EnvironmentMaintenance.java:433-435`），**这次升级不经过它** |
| 启动阶段「注册插件」 | `app/src/main/java/com/deepseekharness/app/core/StartupPipeline.java:290-313` | `actions.stage("注册插件")` → `timing.run("register-builtins", plugins::register)`；失败时 `issue("插件注册失败：…")` 后抛出，页面阶段即「注册插件」 |
| 注册命令与 90s 预算 | `app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java:158-168`（另见 `:195-201`、`:216`） | `runner.run("python3 -u /root/.dsh/register-builtin-plugins.py 2>&1", 90_000)` → `GuestCommandOutcome.requireCompleted(result, "BUILTIN_REGISTER")` |
| 启动前准备 | `app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java:294-330` | 环境就绪 → python → pnpm → `assets()`（`RuntimeTools.prepare`），随后才执行注册 |
| 超时 → 错误文本 | `app/src/main/java/com/deepseekharness/app/util/GuestCommandOutcome.java:11-32` | `timedOut` → `"BUILTIN_REGISTER_TIMEOUT: exit=… timedOut=true truncated=… output=<tail 1200 字符>"` |
| 宿主硬预算实现 | `app/src/main/java/com/deepseekharness/app/util/BoundedProcessRunner.java:96-120` | 以 `System.nanoTime()` 截止；到点返回 `exitCode=-1, timedOut=true`，`finally` 里 `Compat::destroy` 杀进程 |
| 杀 proot | `app/src/main/java/com/deepseekharness/app/util/Compat.java:166-191` | 先给 proot `SIGQUIT`（让它带走登记过的 tracee），750/2000ms 后 `SIGKILL`；**不清理 guest 写在磁盘上的锁文件** |
| guest 注册脚本 | `app/src/main/assets/register-builtin-plugins.py:1096-1103`、`:218-247`、`:258-290` | `register()` → `ensure_runtime_modules()`：stamp → 缓存比对 → `RUNTIME_LINKS_CHECK` → `repair_runtime_modules()` → 写缓存；之后才合 profile |
| guest 锁 | `app/src/main/assets/register-builtin-plugins.py:294-395`、`:397-430` | 先数据锁 `flock(.dsha-data.lock)`（120s），再写 `package.json.lock`（120s，含死锁接管）；`finally: os.unlink(lock_path)` |
| 插件列表查询 | `app/src/main/java/com/deepseekharness/app/core/StartupRepairs.java:47-52`、`:125-130` | 恢复页的插件卡走 `plugin-manager.py list`，错误前缀 `STARTUP_PLUGIN` |
| 锁存在性判定 | `app/src/main/assets/plugin-manager.py:1024` | `if os.path.lexists(MANIFEST + '.lock'): raise ValueError('插件配置正在修改或有待确认的锁；保留上次列表，请稍后刷新')` —— **只看文件是否存在，不看持有者是否存活** |
| 失败分类 | `app/src/main/java/com/deepseekharness/app/util/StartupRecoveryState.java:82-101`、`:211`、`:323` | 无匹配模板 → `Failure(Kind.FAILED, …, raw)`；标题「启动失败：原因未分类」、代码 `STARTUP_FAILED` |
| 显示原文 | `app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java:154`、`:274-275` | `Kind.FAILED` 时把 `failure.raw` 原样拼进详情，即 `exit=-1 timedOut=true … output=<JSON>` |
| 文案渲染 | `app/src/main/java/com/deepseekharness/app/util/StartupText.java:62-70` | 只认 `BUILTIN_REGISTER_OK` / `BUILTIN_REGISTER_FAIL`，**不认 TIMEOUT** |
| 启动历史留存 | `app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java`、`app/src/main/java/com/deepseekharness/app/util/StartupHistoryStore.java:21`、`:80` | 最近 5 条记录（`startup-history/<uuid>.properties`），含 reason/zhLog/elapsed |

## 3. 逐项分析

### 3.1 为什么失败落在「注册插件」

恢复页的阶段名来自 `StartupPipeline.java:290`，`STARTUP_FAILED` 是 `Kind.FAILED.code`；
异常文本前缀 `BUILTIN_REGISTER_TIMEOUT` 只可能由 `GuestPluginScripts` 的两个注册入口产生
（`StartupPipeline.java:141` 的 `boot.registerBuiltinPluginsResult()` 与 `:155` 的 `scripts.register()`），
两者都是 **90 秒**预算。维护路径不经过注册（`EnvironmentMaintenance.java:433-453` 的 `register=true`
重载只在单参 `validateRuntime(proot)` 里，update 不调用），所以问题发生在**维护提交后的正常启动**，
而不是维护的 5 个步骤内部。

### 3.2 90 秒预算与 479409ms 的矛盾（证据缺口）

- 宿主侧：`BoundedProcessRunner.collect` 的预算是 `System.nanoTime()` 的硬截止，
  到 90s 必返回 `timedOut=true`；`GuestCommandOutcome.detail()` 取的是**已收集**的尾部，
  因此能被写进 `output=` 的内容一定早于超时判定。
- guest 侧：`registration_timing()` 用 `time.monotonic()` 差值打印 `elapsedMs`，
  同一进程两次相邻读数不可能凭空跨过 390 秒。
- 时间线：安装 04:23:21、维护 4/5 在 04:25:03、失败页 04:27:11。整个窗口约 230 秒，
  **比 guest 报的 479 秒还短**。

三者不能同时成立。可能的解释只有两类，需要现场证据区分：

1. **guest 计时源/进程状态异常**（例如该次命令在 proot/proroot 下被冻结、暂停或时钟被运行库扭曲），
   `elapsedMs` 不可信；真实耗时仍在 90s 附近，实际卡住的是 `RUNTIME_LINKS_CHECK` 之后的
   `repair_runtime_modules()`（输出尾部最后一行的位置也指向这一点）。
2. **该行来自更早一次运行的残留输出**（例如此前一次被取消/超时的注册，或另一条命令的缓冲），
   即 `output=` 混入了旧内容。

无论哪一种，结论相同：**当前失败消息无法把耗时定责到子阶段**。这属于 ②（证据/语义不足），
修复时应当补宿主侧时间戳（第 5 节 P1-1），而不是继续猜测 guest 计时。

### 3.3 锁：超时被杀 → 残留锁文件 → 恢复页第二张卡

1. 注册脚本在 `main()` 外层持两把锁：数据锁（`flock`，进程死即释放）与
   `package.json.lock`（**文件**，`profile_writer_lock` 用 `os.O_CREAT|os.O_EXCL` 创建，
   正常结束时 `finally: os.unlink`，见 `register-builtin-plugins.py:294-395`）。
2. 90s 预算到期后 `Compat.destroy` 杀 proot/tracee（`Compat.java:166-191`），
   Python 的 `finally` 不会执行 → **`package.json.lock` 留在磁盘上**。
3. 恢复页的插件卡用 `plugin-manager.py list`（`StartupRecoveryModel.java:58-66` →
   `StartupRepairs.java:125-130`），而 `plugin-manager.py:1024` 对锁只做**存在性检查**，
   于是报「插件配置正在修改或有待确认的锁」——这是超时的**连带误报**，不是独立故障。
4. 重试为什么能过：`register-builtin-plugins.py:333-395` 的 `take_over()` 会读锁记录里的 PID，
   用 `os.kill(pid, 0)` 判断持有者已死，然后原子接管；同时 `repair_runtime_modules()` 幂等，
   第一次被杀前已经写下的链接会被下一次快速跳过，所以重试的 register 只要 <1s。
   **"重试成功"不代表锁"过一会儿自己释放了"**，而是接管 + 幂等。

这也解释了 04:27:11 页面上为什么两张卡同时出现：第一张是超时本身，第二张是同一次超时的残留锁。

### 3.4 为什么只有「覆盖升级后的第一次启动」出现

同版本正常启动只有 `stamp`（约 65ms）并命中链接缓存；而 350→351 改变了受管输入：
`app/src/main/assets/managed-runtime-inputs.json`、`runtime-descriptor.json`、内置
`dsh-web-mobile` 3.3.5（243 个部署成员，含新增 `assets/icon.png` 与 `locale/{en,zh}.json`，
上游 37 个提交）——见 `docs/releases/build351-changes.zh.md` 第 4、5 条。

首次启动因此多出两类工作：

1. **受管资产覆盖层重装**：`RuntimeTools.prepare` 在 APK 身份/根路径/戳记不匹配时走
   `cacheMiss` → `prepareManagedOverlay` / `installManagedAssets`
   （`RuntimeTools.java:95-130`、`:176-224`、`:533-560`、`:581`）。维护事务是在**暂存目录**
   （`ManagedRuntimeAssets.java:120-155` 的 `stage/linux/ubuntu`）里执行 `RuntimeTools.stage` 的，
   切换的是目录本身；进程内的 `preparedRoot/preparedApk/preparedStamp` 静态缓存键仍是暂存路径，
   所以**切换后的第一次启动很可能再按 live rootfs 重装一遍覆盖层**（`cacheMiss … reason=root`）。
   这一点可以直接验证：`RuntimeTools.reportCacheMiss`（`:216-224`）在 logcat 打
   `RUNTIME_TOOLS_CACHE_MISS reason=…`。这是一条独立的 ①/设计链路浪费点，量级需实测，
   但它会把首启的 I/O 压力推到最高。
2. **共享依赖链接缓存失效**：`register-builtin-plugins.py:218-247` 的缓存绑定
   `managedAssetId`（`managed_asset_identity()`），受管资产身份一变，缓存必然失配 →
   打印 `RUNTIME_LINKS_CHECK` 并进入 `repair_runtime_modules()`（`:258-290`）。
   正常启动命中缓存，首启要全量修复。

叠加 APK 更新后的系统侧负载（安装/校验、dex2oat、资产写入）后，90s 的固定预算就不再是安全的：
命令在 `RUNTIME_LINKS_CHECK` 后被杀，正好与输出尾部最后一行一致。重试时覆盖层已装好、
链接已大部分写好，所以快。**这也解释了"只失败一次、重试就好"的可复现形态。**

### 3.5 为什么显示「原因未分类」

`StartupRecoveryState.classify`（`:82-101`）只识别端口、`proot/proroot 进程退出`、健康检查、
API Key、`插件加载异常：<name>`、`启动配置检查失败：` 这几类；`BUILTIN_REGISTER_TIMEOUT` 全不匹配，
于是 `Kind.FAILED`，标题 `:323`「启动失败：原因未分类」，代码 `STARTUP_FAILED`。
`StartupText.render`（`:62-70`）只把 `BUILTIN_REGISTER_OK/FAIL` 转成可读文本，
TIMEOUT 的 `exit=-1 timedOut=true … output={…}` 原样贴给用户（`StartupRecoveryActivity.java:274-275`），
用户既看不出是"插件注册超时"，也看不出"可以直接重试"。

## 4. 归因判定

| 类别 | 判定 | 证据 |
|---|---|---|
| ③ 机制缺失（主因） | **是** | 单条固定 90s 预算覆盖首启的"资产覆盖层 + 链接全量修复 + 注册"（`GuestPluginScripts.java:158-168`）；无分阶段预算/进度续期；超时后无锁清理、无子阶段定位（`BoundedProcessRunner.java:96-120`、`Compat.java:166-191`、`GuestCommandOutcome.java:21-32`） |
| ① 设计链路 | **是（加重）** | 维护提交后首启重复装覆盖层（`ManagedRuntimeAssets.java:120-155` + `RuntimeTools.java:95-130`）；超时残留 `package.json.lock` 被恢复页当故障再报一次（`plugin-manager.py:1024`）；恢复页与注册并发触碰同一 profile 锁 |
| ② 语义不足 | **是** | `BUILTIN_REGISTER_TIMEOUT` 未分类 →「原因未分类/STARTUP_FAILED」（`StartupRecoveryState.java:82-101`、`:211`、`:323`）；原始 `exit/timedOut/JSON` 直出（`StartupRecoveryActivity.java:274-275`、`StartupText.java:62-70`）；guest 479s 与宿主 90s 无法互证 |
| 是否数据/持久损坏 | **否** | 重试后 READY、旧会话数据保留；注册与链接修复幂等，失败=未完成而非错误状态 |

一句话：**这是"首启工作量 + I/O 压力"超过了单条固定预算的机制问题；锁残留与失败分类把一次可重试的超时
放大成了"原因未分类 + 插件清单不可读"两个吓人的结论。**

## 5. 最小修复建议（build352，本任务不实施）

**P0（直接消除该失败体验）**

1. **给注册单独分级预算 + 首启识别**：`GuestPluginScripts.register/builtin` 的 `90_000`
   改为例如 `常规 90s / 覆盖升级后首启 240–300s`，判据用本次启动的受管资产身份是否与上次记录一致
   （`RuntimeTools` 的 `preparedStamp` / `managed-assets-v2` 标记已有素材）；或把预算改成
   "分阶段预算+进度续期"：宿主解析 guest 的 `REGISTER_TIMING` 行，收到 `runtime-links-check`
   等进度就顺延，直到总上限。
2. **超时后清锁 / 明示接管**：`register-builtin-plugins.py` 的锁记录已含 PID；
   在宿主杀进程后，下一次注册已能 take-over（`:333-395`），但目前**恢复页的列表查询会先报错**。
   建议 `plugin-manager.py:1024` 在锁存在时进一步做 `holder_exited` 判定：死持有者就按"有待确认的锁"
   提示可清理/可重试，而不是笼统的"配置正在修改"；或在超时处理里删除本进程刚创建的锁文件。
3. **失败分类**：`StartupRecoveryState.classify` 增加 `BUILTIN_REGISTER_TIMEOUT` /
   `BUILTIN_REGISTER_EXIT_` / `PLUGIN_PROFILE_LOCK_TIMEOUT` → `Kind.PLUGIN`（或新增 `REGISTER`），
   文案「插件注册超时（可重试）」；`StartupText` 只保留阶段与"可重试"结论，去掉原始 `exit=/JSON`。

**P1（避免下次再踩 / 让证据可诊断）**

4. **宿主侧分段计时**：已有 `RuntimeCommandTiming` / `CAS_HOST_TIMING` 先例
   （`app/src/main/java/com/deepseekharness/app/runtime/RuntimeCommandTiming.java:30`、
   `StartupPipeline.java:379` 的 `relayCasTiming`）。给注册命令传入同样的 host timing，
   在超时消息里记录 `hostElapsedMs` 与最后收到的 guest 阶段行；两者不一致就显式标
   `GUEST_TIMING_MISMATCH`，不再让 479s 这类数字误导排查。
5. **避免首启重复装覆盖层**：维护事务切换到 live rootfs 后，让下一次 `RuntimeTools.prepare`
   直接命中"本次安装已完成"的戳记（例如在事务提交时把 live rootfs 的 stamp 持久化到
   `runtime-health`/私有标记，而不是只放在进程内静态字段），或把覆盖层安装并入维护的 5/5 就绪检查，
   让启动只做注册。
6. **注册纳入维护就绪检查**：维护 5/5 的 `RuntimeTrial` 前后增加一次与启动等价的注册验证
   （目前 `validateRuntime(proot, false)` 明确不注册），这样"首启需要注册"变成"维护时已完成"，
   启动路径只剩缓存命中。若担心耗时，可只校验 `BUILTIN_REGISTER_OK` 与链接戳记是否已写。

## 6. 复现与验收建议（测试包，不中断本机 DSH）

本机不能覆盖安装正式包（会把 DSH 与会话一起结束），推荐用测试变体 `io.github.sunsetrne.dsha.test`
在两台设备上做 350→351 的合成升级：

1. 安装 build350 的 `.test` 包（同签名测试证书），造出"已注册 + 有会话数据 + 有第三方插件"的状态；
2. `adb install -r` build351 的 `.test` 包（同一应用身份），等维护页跑到就绪；
3. 第一次启动前把 `/data/data/<pkg>/files/linux/ubuntu/root/.dsh/repair-builtin.log` 清空/记录长度，
   启动后抓：`files/startup-history/*.properties`（reason/zhLog）、`logcat -s DSHA`、
   `repair-builtin.log` 新增段、以及（如可导出）恢复页"查看并下载日志"；
4. 记录字段：宿主 `STARTUP_PREP_TIMING`（`prepare-plugin-helpers`/`register-builtins`）、
   guest `REGISTER_TIMING` 全部行、`RUNTIME_LINKS_*`、`BUILTIN_REGISTER_*`、`运行方式`（proot/proroot）、
   `logcat` 的 `RUNTIME_TOOLS_CACHE_MISS reason=…`（确认首启是否重装覆盖层及原因）、
   install 到失败、失败到重试的墙钟时间、设备 uptime；
5. 控制组：a) 同一 build 纯正常重启（应命中缓存，register <1s）；b) 只改 UI 的 APK 覆盖（受管资产不变，
   应不需要重建链接）；c) 受管资产变化的覆盖（应能稳定复现首启重活）；每组至少 3 次。
6. 若要在 CI 侧做静态回归：给 `register-builtin-plugins.py` 的首启路径补一个 FakeRoot 测试
   （`DSHA_TEST_ROOT` 已支持），断言 `managedAssetId` 变化会触发 repair，且预算/进度续期逻辑
   （修复后）不会在同一次调用里丢阶段。

## 7. 待补现场证据（PJD110）

本文结论不依赖这些证据也能成立（机制与锁链已能解释失败与重试），但要把 479s 与子阶段钉死，需要：

1. 该次启动在 `files/startup-history/` 的记录（`reason` 与 `zhLog` 全文；可脱敏后给出），
   以及恢复页"查看并下载日志"的导出（含完整 register 输出 HEAD/TAIL）；
2. 失败时的运行方式（恢复页/维护记录里的「运行方式：proot / proroot」）；
3. 设备 `uptime`/`CLOCK_BOOTTIME` 与系统时间，判断是否发生冻结/挂起；
4. `/root/.dsh/package.json.lock` 是否残留、内容 PID 是否已死（下一次注册前抓）；
5. 维护事务 `runtime-health` / 维护记录，确认首启前覆盖层是否已装、`managed-assets-v2` 标记是否已写；
   以及同一次首启的 `RUNTIME_TOOLS_CACHE_MISS` 行（判断是否 `reason=root|apk|metadata`）。

## 8. 附：本机基线（TB321FU，build351）

- `startup-history` 五条记录全部 `ready`；`register-builtins` 870ms / 218ms / 264ms / 2.2s，
  `runtime-links-stamp` 58–139ms（entries=101），`runtime-links-repair` 530ms–1.5s（changed=0）。
- 手动在本机 guest 复算 `runtime_links_stamp()` 的 101 个路径：约 125ms，无单路径 >20ms；
  说明"正常文件系统下这个阶段不可能到秒级"，479s 只能来自异常状态或错误来源。
- 本机未做覆盖安装，不把该基线当作 PJD110 的性能证据。
