# ADB debug 自插桩验收

## 最终设备结果 · 2026-09-08

主流程已在 Android 13 完成真实系统配对码 TLS 配对，App 通道 `id` 返回 `uid=2000(shell)`，关闭/重新开启无线调试后无需重新配对。完整测试 **170 条断言通过**，最终门控及 wheel 改动后连接复验 **10 条通过**。真实缺失路径退出码为 1，无响应 socket 在 2.563 秒退出 124，命令尚未发送；23 项 Python 故障场景另通过。配对码仅经私有临时输入传送，不进入日志或报告。见[完整功能验收](functional-audit-rc1.4.md)。

以下保留复现步骤与实现边界；入口创建时的“没有操作设备”仅描述协作者当时分工。

入口：`com.dsh.client/com.deepseekharness.app.ui.AdbFlowAudit`，已在 `app/src/debug/AndroidManifest.xml` 注册。同一个 debug APK 自插桩，不需要单独测试 APK；release 不包含此入口。没有运行 Gradle 或操作设备，以下命令由主线程/测试者执行。

## 最短实测步骤

先安装主线程构建的 debug APK。在终端一启动监听，并等到 `AdbFlowAudit READY`：

```powershell
& 'F:/DSHA/_toolchains/android-sdk/platform-tools/adb.exe' shell am instrument -w -e mode listen -e seconds 1800 com.dsh.client/com.deepseekharness.app.ui.AdbFlowAudit
```

多台设备时，在 `shell` 前加 `-s <USB序列号>`；下列助手也加同一个 `--serial`。

终端二准备工具路径，然后检查/准备环境：

```powershell
$adbAuditPython = 'C:/Users/18768/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$adbAuditControl = 'F:/DSHA_RESTART/tools/control-adb-flow-audit.py'
& $adbAuditPython -I -B $adbAuditControl status
& $adbAuditPython -I -B $adbAuditControl prepare
```

`prepare` 调用 `AdbBridge.ensureReady()`，会安装/更新 ADB 随包脚本并同步授权标记，不解压整个环境。先等它通过，再在手机上打开无线调试的配对弹窗，以免准备耗时导致配对码失效。

手机手动打开系统无线调试。MIUI 若不支持 `android.settings.WIRELESS_DEBUGGING_SETTINGS`，从开发者选项进入即可；配对页的直接打开按钮和自动读码按钮共用这一回退。本插桩只读取 `adb_wifi_enabled`，不自动打开无线调试。

实际配对用配对弹窗的端口；连接端口是无线调试主页面显示的另一个端口：

```powershell
# IP/端口替换为手机当前显示的值；命令行里没有配对码。
& $adbAuditPython -I -B $adbAuditControl pair --host <本机IP> --port <配对端口> --connect-port <连接端口>
```

助手随后使用隐藏输入读取六位码。默认后台调用 `AdbBridge.pair()`，不切换前台页面，不抢走系统配对弹窗。不确定连接端口可不传 `--connect-port`，由本机 mDNS 发现。

已有配对时，直接验证，不再消耗配对码：

```powershell
& $adbAuditPython -I -B $adbAuditControl verify --connect-port <连接端口>
& $adbAuditPython -I -B $adbAuditControl state
```

`PASS + CONNECT_OK` 才表示连接已验证。`PAIRED_UNVERIFIED` 表示配对完成但连接未验证，检查连接端口后运行 `verify`。无线调试为 0 时返回 `BLOCKED_WIRELESS_DISABLED`；USB 已连接不代表无线连接已通过。

## 旋转与主题恢复

不依赖实际无线连接的生命周期 fixture：

```powershell
& $adbAuditPython -I -B $adbAuditControl lifecycle
```

它打开真实配对 Activity，在不启动配对的前提下模拟 busy/完成状态，检查旋转和日夜切换后 ViewModel、任务身份和结果保留；检查 Bundle 没有配对码；用保存状态模拟进程恢复应提示中断且不重放任务。此项不等同真实握手或真实杀进程实测。

对实际 UI 任务验证重建：

```powershell
& $adbAuditPython -I -B $adbAuditControl launch
& $adbAuditPython -I -B $adbAuditControl verify --ui --no-wait
& $adbAuditPython -I -B $adbAuditControl rotate
& $adbAuditPython -I -B $adbAuditControl theme
& $adbAuditPython -I -B $adbAuditControl state
& $adbAuditPython -I -B $adbAuditControl theme --night restore
```

实际配对也可用 `pair --ui --no-wait`：先 `launch` 保留配对页，再手动进入系统配对弹窗，通过私有助手输入配对码。任务接受后回到 DSHA 再执行旋转/主题命令。`accepted` 仅表示派发，不是配对成功；后续看 `state` 的最终 outcome。任务可能很快完成，报告不会把“完成后的恢复”冒充“握手中恢复”。

主题测试只改变该 Activity 的 local night mode，不写用户外观偏好；`theme --night restore` 与插桩结束时会恢复原模式。旋转若被 ROM 限制、没有真实重建，会明确失败而不算通过。

## 私有命令与结果

- 私有目录：`cache/adb-flow-audit/`。
- 请求：`command-<32位随机ID>.json`。读取后、调用配对前删除；上次中断的请求在下次监听启动时丢弃，不自动重放。
- 配对码只由 `getpass` 或 `--code-stdin` 经标准输入传送，不出现在 adb 参数、主机临时文件、Intent、instrumentation Bundle 参数或报告中。程序化调用可把六位码通过 subprocess 的 `input` 传给 `--code-stdin`，不要嵌进命令行或脚本文件。
- 回执：`<ID>.json`；异步完成结果：`<ID>-result.json`；最新状态：`state.json`；最终报告：`report.json`。均不记录配对码。日志额外遮蔽所有独立六位数字，某些同长度普通数值也会被遮蔽。
- 任务进行时可发 `state/rotate/theme`；第二个实际任务返回 BUSY。发送/等待失败后助手不自动重发，应先查看 state。
- 单次模式可把启动参数改为 `-e mode status`、`prepare`、`verify`、`lifecycle`。实际配对仅接受监听模式下的私有请求，不能用 `-e code`。

测试完毕：

```powershell
& $adbAuditPython -I -B $adbAuditControl stop
```

仅在真实任务完成后接受 stop，避免中途终止配对。私有临时请求正常消费即删除，报告不含配对码。该验收不会读取 DeepSeek API Key 或修改 ConfigFragment/ConfigStore。

## 本地验证

`tools/test-adb-flow-audit.py` 用 fake subprocess 验证：配对码只进入 stdin；无效输入不派发；监听缺失不发送；accepted/完成分离；超时不重发；错误输出不回显配对码；只能读取验收报告路径。Android 代码另用独立 javac 检查，设备执行由主线程完成。

## 环境任务并发门禁

完整 UI 配对/验证、每轮后台准备/探活与公开 `AdbBridge` 环境访问共用 `EnvironmentTaskGate`。
进入 `Lease.run()` 后复用当前 owner，退出时只关闭本次自己取得的 Lease。未完成维护日志也会阻止准备；不会自动恢复或绕过维护。

被拒绝时返回 `ENVIRONMENT_BUSY: 环境任务进行中，稍后重试`，后台状态为 `environment_busy`，不增加连接失败次数或标记配对失效。
私有验收助手也将此 outcome 作为未完成返回；等待其他任务结束，或先恢复中断维护后再试。

保存配置的调用方若已取得 Lease，必须用 `saving.run(...)` 调用 `applySettings()` 才能复用 owner；仅 `tryAcquire()` 而没有 `run()` 不算 owner。
ADB 子进程使用现有 `InstallProcess` 的回收契约，确认进程退出后才交还 Lease。

新增 `AdbEnvironmentTaskTest` 验证互斥、嵌套复用、持锁检查维护标记、异常/中断释放、完整准备至验证范围及取消回收期间禁止维护进入。

## 备份恢复后的 wheel 缓存

备份引擎只省略与 APK 摘要完全相同的 wheel 和归档。ADB 准备需要补装依赖时，APK 会先解到独立临时目录，再逐个补齐缺少的文件名：已恢复的同名修改 wheel、额外 wheel、说明文件和修改过的归档均不覆盖。不能以“已有 15 个 wheel”判断标准依赖齐全。

安装使用保留下来的实际 wheel 内容；所有 ZIP 路径、长度和 CRC 在临时目录中校验成功后才发布 Python 文件。损坏 wheel 或两个版本提供不同的同名文件时，返回 `WHEELS_CACHE_INVALID`，列出文件及原因，停止安装，源缓存原样保留。

恢复步骤：先备份诊断指出的文件，将它移出 `wheels` 目录，再重试准备。属于 APK 的缺失文件会重新补齐；自定义额外 wheel 没有 APK 副本，需要用户提供有效版本。不会自动删除、覆盖或隔离被认为“损坏”的用户文件。

现有 `adb-wheels.tar.gz` 与 APK 不同会报告 `WHEELS_ARCHIVE_PRESERVED` 并保留；补缺始终读取 APK 临时副本，因此不依赖该缓存归档是否可解压。依赖已经正常时不强制重装缓存内容。

`AdbWheelCacheTest` 覆盖部分目录、同名修改、额外文件、修改归档、CRC 错误、解包冲突及显式恢复路径；`AdbWheelBundleTest` 用真实 APK 的 15 个 wheel 对照备份引擎摘要，验证只补齐被省略的原版文件。
