# AGENTS.md


> **当前状态**：`0.2.2-20261111.0200-dsh0.2.2-rc.1` / build357 / `dsh.android.client` / DSHA / 单一 Standard。
> 主线分支 `main`；Android 11+（API 30）/ arm64；proot 默认、proroot 可选；内置 DSH 0.2.2-rc.1。
> 上岛能力已并入正式包；旧 low/GeckoView 兼容线与独立上岛包已删除；签名使用历史 E7E3 发布证书（见 BUILD.md）。

DSHA 的 AI / 新贡献者入口。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

**仓库事实**：Java 17 / `:app` / arm64；移动 UI 3.3.5；proot 默认 + proroot 可选；固定离线输入由
`tools/ci-assets.py` 还原。构建、签名与门禁见 [BUILD.md](BUILD.md) 与 [CONTRIBUTING.md](CONTRIBUTING.md)。

维护入口：[文档总索引](docs/documentation-index.md)、[固定构建输入](docs/fixed-build-inputs.md)、[文案长度政策](docs/i18n-length-policy.md)、[项目总手册](docs/project-architecture-handbook.md)、
[权限](docs/device-permissions-architecture.md)、[环境/数据/插件](docs/runtime-data-plugin-architecture.md)、
[运行引擎](docs/runtime-engines.md)、[端口](docs/ports-and-networking.md)、[虚拟屏](docs/virtual-screen-architecture.md)、
[ADR0003](docs/adr/0003-virtual-screen-lifecycle-and-input.md)、[来源依据](docs/references-and-provenance.md)。

**历史记录**：各版本的构建与验收回执保留在 [CHANGELOG.md](CHANGELOG.md) 与 [docs/releases/](docs/releases/)；
下方目录数量为历史索引，以实际代码为准。

---

## 目录地图（build357 统计；当前职责导航）

```
app/src/main/java/com/deepseekharness/app/
├── *.java（根包，36 个）      设备与服务层：无障碍、桥、LAN、悬浮条、Shizuku/Root、PTY
├── ui/      （169 个）        界面：MainActivity + 各 Fragment / Activity / 对话框
├── core/    （55 个）         编排：HarnessController、维护屏障、备份任务、诊断
├── runtime/ （63 个）         容器：ProotBootstrap、ContainerRuntime、WebProcessManager、装机
├── backup/  （83 个）         数据：v5 备份（导出无密码，内部副本加密）、恢复、迁移、自动备份（最大的包）
├── vscreen/ （36 个）         独立虚拟屏：通道 / 预览 / 输入 / 无障碍桥
├── recovery/(5 个)            独立应急 DSH：与正式环境完全分开的生命周期
├── bridge/  （12 个）         AppBridge 契约、AdbBridge、LAN 访问
├── data/    （4 个）          KeyVault（Keystore）、便携设置、导出
└── util/    （397 个）        纯逻辑：不 import 任何 Android API，必须配单测
```

### 根包（`com.deepseekharness.app`）——设备与系统服务

| 类 | 职责 |
|---|---|
| `HttpShellService` | **3090 桥本体**（约 253 KB / 4.9k 行，最大的类）：`/exec` 与 `/app/*` 共 55 条精确路由（另有前缀族），token 门控 |
| `DshaAccessibilityService` | 无障碍：`uiDump / uiTap / uiTapText / uiInput / uiKey / uiSwipe / uiScreenshot` + 虚拟屏转发 |
| `DeviceBridgeService` | 设备通道总管：ADB / Shizuku / Root 三通道选择与保活 |
| `DeviceShellExecutor` / `DeviceAppInventory` / `DeviceSense` | 设备命令执行（白名单在 util）、应用清单、传感器 / 位置 / 手电 |
| `LanProxyService` / `LanAuth` | 局域网代理 `:3081`（token fail-closed）与鉴权 |
| `OverlayController` / `OverlayLines` | 流式悬浮条（歌词式 AI 输出 + 命令批准） |
| `HarnessService` / `WebPreviewActivity` | 前台服务 + 看门狗；Web 内嵌预览（WebView / Gecko） |
| `PtySession` | Termux terminal-emulator JNI 包装（真 PTY 终端） |
| `ShizukuShell` / `RootShell` / `ShellService` / `AdbKeepAliveReceiver` / `BootReceiver` | 通道实现与开机 / 保活接收器 |
| `DshaDocumentsProvider` | 文件管理器访问私有目录（MT 管理器等） |
| `DeviceShellPolicy` / `DeviceShellExecutor` | 受管设备命令解析与执行前守卫；批准不覆盖显式拒绝，Root 也受此策略检查 |
| `BridgeAskDialog` / `ConfirmReceiver` | 原生业务提问与安全确认通知回执；业务选择不由全局确认代答 |
| `DshaApp` / `UpdateDownloadService` | Application 入口（系统语言锁存等）；应用内更新下载 |

Root 维护以 [权限架构](docs/device-permissions-architecture.md) 与 [固定上游源码核查范围](docs/references-and-provenance.md) 为准：九个 su 候选及 metadata 检查不等于管理器识别或全版本兼容；65 秒只限制结果收集。2026-10-22 对照 `140c7ba` 的审查发现递归 READ 尚未核验子链接目标，短信保护存在实现覆盖缺口，不能写成“仅有文档问题”或“完整防绕过”；未修状态、回归与设备证据须分别记录。历史 `DangerShellGuard` 已不在生产调用链。

### 分层归属（改代码前先看这里）

| 层 | 关键类 | 职责 |
|---|---|---|
| `ui/` | MainActivity / OnboardingActivity / ExtractActivity（维护详情） / Launch·Config·Settings·Plugin·Workspace Fragment / WebPreviewActivity | 界面与启动门禁；首次部署在引导内后台进行并以部署胶囊显示；覆盖更新进入整屏维护页 |
| `core/` | `HarnessController`（约 8 KB / 300 行，编排入口；主要流程已拆到 `WebLifecycleController` 等协作者） | 启动 / 停止 dsh、鉴权链接捕获；**不要再往里塞新职责**，新逻辑写成独立协作者 |
| `core/` | `ConfigStore` | 配置唯一读写入口（端口 / 模型 / workdir / API key） |
| `core/` | `InstallRepository` / `MaintenanceCoordinator` / `RuntimeTasks` / `StartupDiagnostics` | 安装状态、维护屏障、运行任务锁、启动诊断 |
| `runtime/` | `ProotBootstrap` / `ContainerRuntime` / `WebProcessManager` | proot 命令组装；运行时选择 + BINDS 挂载清单；停止 Web（哨兵 + pid + 出生身份核验） |
| `runtime/` | `InstallPipeline` / `ManagedRuntimeAssets` / `RuntimeTrial` | 六步安装编排、受管资产、更新试运行 |
| `backup/` | `NativeBackupJobs` / `BackupArchive` / `HostDataTransaction` / `AutomaticBackups` / `FactoryReset` | v5 备份（导出无密码，内部副本加密）/ 恢复 / 数据事务 / 自动计划 / 格式化 |
| `recovery/` | `RecoveryController` / `RecoveryRuntime` / `RecoveryRepairBroker` | 独立应急 DSH，不经正式环境的迁移与启动锁 |
| `vscreen/` | `VirtualScreenManager` / `VirtualScreenAccessibility` / `VirtualScreenPreviewView` | App 持有认证连接与心跳；输入必须带最新帧号 |
| `bridge/` | `AppBridge`（契约）/ `AdbBridge`（无线配对 TLS1.3-PSK + SPAKE2）/ `LocalNetworkAccess` | 桥接缝与设备通道 |
| `data/` | `KeyVault` | Keystore AES/GCM 加密 API key |
| `util/` | `Constants` / `ShellQuote` / `Query` / `WebProcSel` / `DeviceShellPolicy` / `HttpProtocol` / `BackupScope` 等 397 个 | 纯逻辑，全包零 Android import，**必须配单测** |

---

## 测试布局（build357 统计）

下列数量仅描述基座历史分布；F13实际选择类/XML、失败/错误/跳过与最终软件结果以[F13记录](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)及统一回执为准，不从静态文件数推导执行数。

`app/src/test/java/com/deepseekharness/app/` 共 704 个测试类，分布：`util/` 378、`ui/` 78、`backup/` 71、`core/` 48、`runtime/` 43、`vscreen/` 32、`plugins/` 8、根包 10。

```bash
bash build.sh :app:testStandardDebugUnitTest   # 全量单测
```

锁定的不变式（重构时绝不能改坏）：

- `ShellQuote`：POSIX 单引号转义，恶意值不能逃逸。
- `Query`：逐参数名匹配（`indexOf(key+"=")` 会被后缀劫持，已修）；「参数为空」≠「参数不存在」。
- `BackupScope`：部分备份绝不叫 `DSHA-backup-*`（否则老版本当全量恢复会清掉配置与插件）；`dshPaths` 与 `mergeSubdirs` 一一对应。
- `WebProcSel`：认得出 dsh 进程、**绝不误杀 proot/proroot**（杀到容器启动器 = 环境连 App 一起带走）。
- `DeviceShellPolicy`：白名单默认拒绝，只接受可完整识别的单条 argv，不执行用户提供的 shell 程序。
- `HttpProtocol` / `SocketDispatch`：头部字节与总截止独立上限；LAN 连接登记、短请求 / 长流 / 反向 pump 独立有界。

---

## 安全模型（设计围绕这些边界）

先读 [docs/security-model.md](docs/security-model.md)。要点：

- **设备命令白名单始终开启**（`util/DeviceShellPolicy`）：格式化 / 分区 / SELinux / 系统属性写入直接拒绝；不认识的命令不放行；拦截返回 `[POLICY_BLOCKED]` + 退出码 126，**没有「仍然允许」按钮**。Root / Shizuku / ADB 三通道同一套策略。
- **桥的凭据护栏**（`util/BridgePathPolicy`）：`/app/export`、`/app/readfile` 拒绝 `.dsh` / `.ssh` / `.android` 等凭据区；字符串判据 + `getCanonicalPath()` 双重核验，堵软链接绕法。
- **最小权限默认**：所有敏感能力（ADB、Root、短信、传感器、位置、悬浮窗、LAN）默认关闭、可撤销；最小配置下 DSHA 照常跑 dsh。
- **备份凭据排除**：桥 token 整文件排除；`.credentials.yaml` 字段级剔除本机密钥、保留用户 API key。
- **这不是内核沙箱**：容器、插件、终端都在 App 的 Android UID 下运行。白名单保护随包入口，不约束任意自写代码 —— 别在文档里宣称「安全隔离」。
- 已知弱点（含 `danger-full-access`、历史明文备份、签名密钥待轮换）直接写在安全模型文档里，别删。

---

## 技术约束（围绕这些设计）

### 版本与构建

- **当前身份以 `app/build.gradle`、`ci/release-identity.json` 与 [CHANGELOG.md](CHANGELOG.md) 为准**：build357 / DSHA 修订 `0.2.2`（内置 DSH 0.2.2-rc.1）/ `dsh.android.client` / DSHA / 单一 Standard；`-Pdsha.identity=upstream`（默认）为官方发行身份，`mod`（`io.github.sunsetrne.dsha` / Mod 证书）仅保留用于历史包与共存验证。
- `standard`：minSdk 30（Android 11+），系统 WebView，arm64-v8a，是唯一构建线，任务为 `assembleStandardRelease`。`low`/GeckoView 兼容线已整体删除（含 `assembleLowRelease`、legacy proot/loader），**不再声明 Android 10 及以下可用**；测试变体通过 `-Pdsha.sunsetTest=true`（`.test`）、`-Pdsha.debugPackage=true`（`.debug`，debuggable 的 debug 构建）或 `-Pdsha.devPackage=true`（`.dev`，release 构建）生成，三者互斥，且必须用 `-Pdsha.unsignedPackage=true` 由测试证书签名。
- 离线 rootfs（`assets/offline-rootfs.bin`）**不提交**，由准备脚本生成；APK 用 `offline-rootfs.layout=split-runtime-v1` 标记 Ubuntu 与 `dsh-runtime.bin` 分包，构建核验两份摘要。
- 新 dsh 依赖由 `tools/dsh-runtime/package-lock.json` 锁定；`tools/prepare-runtime-descriptor.py` 生成独立受管契约描述，APK 版本仅作诊断来源。相同基础版本更新受管树，个人数据保持原位；**不因 UI-only APK 更新替换环境，不因普通 dsh 更新递增 Ubuntu 基础环境版本**。
- 离线 curl / git / 证书由 `tools/ubuntu-tools/packages.lock.json` 锁定（`tools/prepare-ubuntu-tools.py` 生成 `ubuntu-tools.bin`）；网页 ES 兼容依赖锁在 `tools/web-compat/`（`node tools/prepare-web-compat.mjs`）。生成文件不提交，构建核验摘要。
- 中英文界面：文案目录 `tools/i18n/messages.json`，构建生成 Java 文案字典；偏好 `system`（默认）/ `zh` / `en`；**系统语言必须在进程最早时刻锁存**（`SystemLanguage.initialize()`，早于任何 `Locale.setDefault`），否则「跟随系统」切一次就自我锁死。语言切换只重建界面，不停终端或 Web；用户输入、命令原文不做自动替换。
- 旧 WebView 的 `AbortSignal.any/timeout` 与 `crypto.randomUUID` 补齐：兼容脚本须进入受管 HTML 的应用脚本之前，并覆盖文档起始与 Worker。

### 数据与备份

- **自动备份默认开启**（2026-09-17 要求，覆盖更早的禁用规则）：`AutomaticBackups` 支持每日指定时间、1–168 小时间隔、停止后三种模式（`util/AutomaticBackupPolicy` 有单测）；到期仍在运行则延后，不强停 Web / 终端；保留最近 3 份已验证自动副本，手动与未知记录不自动删除。
- 用户导出 v5 使用无需密码的 `DSHA-data-v5-<UUID>.tar.gz`，封装 DSHADATA 完整性载荷；内部自动副本仍加密，旧加密原件读取仍需原密码并完整认证。原生 API Key 默认排除；先完整性/认证核验再预检，只写私有编号槽位。本机授权状态不能从用户归档生效；受管脚本、可执行配置和未知插件先隔离。
- 数据格式不能由 APK 或 dsh 版本号推导：`DataFormatEvidence` 对未经完整检查的数据保留 unknown；发件人声称的已验证格式不能成为删除原件的依据。
- 覆盖更新闲时清理可再生缓存及多余副本：保留至少两份健康且字节符合计划的副本，修改过或含额外文件的原件不删除；新重建通过核验后建立 `retired-proof.json` 再清理 `previous-linux`。
- 完整格式化的生产入口与擦除后端已退休，不恢复旧清理流程。外置工作区维护只断开私有引用，不遍历、覆盖或删除其内容；合法缓存清理仍须受控私有目标与 NOFOLLOW 核验。
- 覆盖安装用原包名、同签名、非调试正式包，做非破坏性检查；不在正式数据上运行强杀、删 Bash、坏插件等故障注入。

### 运行时与更新

- 新受管更新使用 `ManagedRuntimeTransaction` / `HostDataTransaction`；真实隔离 `RuntimeTrial` 核验静态资产 / 原生模块、实际端口 / 鉴权、存储和会话写后重开、网页内核与后端握手及 guest 退出后才能提交。回执保存在 rootfs 外并绑定文件摘要；至少保留直接健康前代。
- `RuntimeTrial` 必须记录实际 `runtimeMode`。所选 proroot 在鉴权前明确退出且无插件故障时，可按 `WebRuntimeFallback` 仅重试一次 proot；**不能把 proot 成功报告成 proroot 原生成功**。静态检查先选 proot，慢启动不触发重试。
- `RuntimeTrialRecords` 只轮换已确认关闭的现场：保留最近 3 条成功和 5 条失败；未关闭或标记异常的目录不得自动删除，也不能以累计次数设永久上限。
- 就绪检查必须解析实际 `/bin/bash` 的 ELF 和加载器路径；可执行位用 lstat（受 Android W^X 影响的 canExecute 不可靠）。损坏系统不得反复执行配置安全启动，应进入环境修复。
- 当前插件策略允许未锁定依赖解析、生命周期脚本与 pnpmfile；链接/本地包/更新通过格式、路径、实际依赖与摘要及事务检查后自动提交启用。用户主动禁用与安全模式意图保持，不恢复旧原生审阅门槛。`plugin-transactions.py` 记录候选与原件，未提交日志继续进入维护门禁。
- 系统插件与用户插件分层：系统插件源码不进用户备份，由当前 APK 按精确受管证明重建；`.disabled` 与旧实体冲突时禁用意图优先。
- `NARB_DISABLE_NATIVE_CACHE=1` 让原生扩展直接从随包目录加载（默认缓存在 `--link2symlink` 下首次悬空）。

### Web / 桥 / 终端

- 主Web从当前受管dsh包装和固定包内入口启动，加载共享RuntimeTools环境。默认自动端口`--port 0`，成功鉴权后记录实际端口；显式手动配置按当前选择策略处理。完整环境与入口见[运行机制](docs/runtime-data-plugin-architecture.md)，不复制旧固定3080命令。proot从nativeLibraryDir执行，不能移到filesDir绕过Android W^X。
- `WebPortPolicy.choose`在启动前选择固定/本机备用/动态端口；备用记忆仅本机，不跨设备迁移。当前生产未接入`retryBindRace`，不得把helper及单测写成已实现的绑定竞态自动重试。鉴权、保活与LAN使用实际端口，不按端口猜PID终止，见[端口专页](docs/ports-and-networking.md)。
- Web 启动等待鉴权不设强制终止时限（60 秒仅提示）；停止与维护必须共用 `WebProcessManager` 的 PID 身份 / 出生身份核验。
- 3090 桥协议版本做特性检测（`/app/version`，`BRIDGE_PROTOCOL=2`）；插件判版本用 `>=` 而不是 `==`。命令查询串预算不得缩到低于 8192 字符需求。
- LAN 正文由方法、状态码和明确传输边界决定；只有真正验证过的 101 WebSocket 握手可切隧道；不把 hop-by-hop 头移除后继续发送不匹配的编码体。
- PTY 出生身份在原生 fork/exec 握手中登记：子进程自读 stat，父进程确认后才 exec；不得退回 Java 启动后单次读取。PTY 与简易终端独立标签和会话，跨切页 / 旋转 / 语言切换保持进程；关闭单个标签必须核验该会话退出后再移除。终端会话 ID 永久递增，显示编号独立复用最小空缺。

### 应急 / 虚拟屏 / 设备

- **独立应急 DSH**：`recovery/` 不经过正式环境的迁移、数据绑定及全局启动锁；归档由 `tools/recovery-runtime/lock.json` 单独锁定；应急 Profile 仅有五个受控修复工具。不能用「原生安全 Profile」替代独立运行根。`RecoveryRepairBroker` 的 HTTP 接口不能确认写入：原生确认绑定候选、源摘要及数据代次，再经停止屏障和宿主事务。
- 应急资产去重：`recovery-asset-locations.json` 仅映射物理位置；仅当正式归档 SHA 与独立应急锁完全相同才共用包内字节，解压运行根仍独立，不读取正式 rootfs 作为救援依赖。
- **虚拟屏**（Standard，API30+）：App持有受管Core。raw坐标输入要求最新已观察帧；semantic节点/editor与anchored动作各有独立身份和目标复核，不强制像素静止、不静默互相fallback。UNKNOWN不重放，详见[架构](docs/virtual-screen-architecture.md)和[ADR0003](docs/adr/0003-virtual-screen-lifecycle-and-input.md)。
- **麦克风**：清单同时声明 `RECORD_AUDIO` 与 `MODIFY_AUDIO_SETTINGS`；由 `BrowserMicrophone` 按网页请求申请，不能在普通启动时预授权；只放行当前本机页面的纯音频请求，摄像头 / 屏幕音频拒绝；旧回调不能批准新页。
- **设备验收经验**：`uiautomator dump` 会抑制其他无障碍服务；截图授权绑定 DSH generation，停止 / 断连 / 撤销后失效；截图存应用私有 Pictures/DSHA，无需所有文件访问；PiP 可能盖住底部控件，自动化必须核对实际可见区域与屏幕方向；截图 / 读屏不可将敏感值写入公开取证文件。
- Shizuku 必须注册 `rikka.shizuku.ShizukuProvider`；标准版 13.1.5 / 兼容版 12.2.0（不用 overrideLibrary 掩盖 minSdk 24）。回调严格核对管理器 UID、Binder 描述符、单次请求与超时。
- 内置移动插件 `dsh-web-mobile` 为 3.3.5（适配宿主 0.2.2），固定上游 main `646fe837440f9939228bdba0024d407eaac01128`（2026-11-08；发布标签 v3.3.5 = `14ecfccf5a6acb69e3a84229dee328d31801306f`，标签后还有 README 与插件页交还宿主排版 3 个修复提交）；**完全追随上游**，`tools/apply-mobile-client-patches.mjs` 只保留宿主集成必需的最小集（服务端鉴权 / 安全区 / `DSHA_GEAR_FIT_V1`）与文件传输、多语言；会话输入 V4、手机搜索、快捷键弹层可见带、字体族行、遮罩按压染色等 UI/行为补丁已删除，不要重新套回；build336 起默认启用（真机覆盖升级验收待补）。

---

## 启动契约（不可破坏）

- `welcomed == false` → `OnboardingActivity`（欢迎 → 用途 → 授权 → 完成）→ `MainActivity`；`MainActivity` 进入前校验 `welcomed`，否则永远回引导。
- 正常运行检查 `.offline-extracted` 与完整环境身份。覆盖更新、环境未就绪、未完成维护事务或未关闭的隔离试运行现场，都整屏进入运行环境维护页（`ExtractActivity`），同一候选只自动尝试一次；用户在维护页显式选择后才进入受限主界面，**受限入口不写就绪标记**，终端、Web 与插件执行仍须通过环境门禁。
- **覆盖安装后只要已装运行时不是当前候选，就必须让用户看到整屏维护页并可一键更新**：`isEnvironmentReady()`（兼容 + 健康回执）不等于「无事可做」，补丁级更新也须按 `RuntimeDescriptor.latest` 进维护；自动开始可以因未关闭的 Web 会话被拦，但入口不能消失。**客户端插件不得依赖宿主某个控件是否存在**，只能用自有属性 + 实测几何。详见[覆盖升级与维护门禁](docs/overwrite-upgrade-and-maintenance.md)。
- 启动失败自动进入原生 `StartupRecoveryActivity`；等待鉴权本身不是故障。最近五次脱敏启动记录独立保存在私有目录。
- 兼容重试仅在 proroot 已确认退出、鉴权前且无明确插件故障时进行一次，**不能由超时触发**。
- 配置快照由宿主 `ConfigurationSnapshots` 执行：正文逐文件存于 rootfs 外，保留三份健康与三份修复前快照；旧日志已提交后不得覆盖后来修改的配置。
- DocumentsProvider 保留 `root` 和 `linux/ubuntu/...` 的既有不透明文档 ID；guest 绝对软链接按 rootfs 解析，删除链接不递归目标；写文件先验证已打开的文件描述符再截断。
- 短信查询属于独立敏感能力：只允许当前 Android 用户的严格 `content query`；原生预授权默认关闭、可撤销、不从系统备份恢复。

---

## 已知 trap（搬自原版，骨架已按此设计）

- **停止靠 pid 文件 + 出生身份，不靠端口反查**：`/proc/net/tcp` 非 root 读不到（静默空），`/proc` 有 hidepid。启动时 `echo $$ > /root/.dsha-web.pid` 再 `exec node`；停止先写哨兵 `/root/.dsha-stopped`（看门狗见到就退出，否则「秒复活」）。旧 PID 复用按内核 `kill(pid,0)` + 出生身份二次核验，不得按裸 PID / 端口 / 名称强杀。
- **app 私有目录禁 `link(2)`**（SELinux）：proot 必须带 `--link2symlink`；`PROOT_L2S_DIR` 在 rootfs 内的 `.l2s` 必须绑定到相同宿主绝对路径，否则 dpkg 安装对硬链接 chown/stat 会报文件不存在。
- **签名身份分开管理**：官方正式 APK 用历史 E7E3 发布证书（`ci/release-identity.upstream.json`，由 `verifyPublishCertificate` 强制核对），覆盖升级沿用原包名 `dsh.android.client`；Mod 包 `io.github.sunsetrne.dsha` 的 A4B0 证书只服务历史共存包，增量更新清单若启用则用独立 keystore，绝不混用。
- Android 的 `FileInputStream(FileDescriptor)` 不接管传入描述符：宿主备份通过 `ParcelFileDescriptor` 自动关闭流明确转移所有权。
- 数据维护先关闭 PTY 与简易终端再取 `RuntimeTasks` 屏障；proroot 终端按本次独立会话核验并回收 guest，不能只结束启动器就释放工作锁。
- 维护成功文案不能代替就绪检查；未就绪时留在原生页，不自动跳回循环。维护页忙状态不使用插件查询 / 终端等通用锁冒充数据维护。

---

## 交付与验收

- 当前版本的改动见 [CHANGELOG.md](CHANGELOG.md)；各版本的构建、验收与回执归档在 [docs/releases/](docs/releases/) 与 `artifacts/`。
- **外部 PR / 外部合并必须专职逐行审查**：由专门的 AI 审查会话审阅**全部 diff**（不得抽样、不得只看 CI），逐项检查安全边界、逻辑缺失、测试覆盖与仓库不变式；结论只有 APPROVE / REQUEST_CHANGES，打回意见必须带 `file:line`、问题、修法、P0/P1/P2。CI 全绿不等于可合并；冲突必须 rebase 后重看生成文件差异；审查会话不得合并 PR 或 push 作者分支，合并仍需用户在主会话明确同意。流程见[外部 PR 与外部合并审查流程](docs/external-pr-review.md)。
- 验收范围如实声明：不把本机验证扩写成全部系统版本、机型或外部模型服务矩阵；单一 Standard 与虚拟屏能力按实际设备证据声明，Android 10 及以下不在支持范围。
- 私钥、用户数据、设备截图与日志不进入仓库；安装、设备操作与发布按当次授权执行。

## 多会话并行约定

同一台机器上常有多个会话同时改本仓库。合并、发布、清理以「活跃声明」为准，不按空闲时长猜测会话是否结束。

- **一会话一工作树**：新任务用 `git worktree add -b <type>/<name> .agents/worktrees/<type>/<name> origin/main` 建独立分支，基线取当时的 `origin/main`（不要基于未进 main 的侧分支）。不在主检出 `/root/DSH-A-mod` 上改代码；主检出只做只读查看。`/root/dsha-build` 是 `local-ci` 的同步镜像，不是工作区。
- **改文件前用 `read` 读工作树路径**：DSH 的 `fs-observation-policy` 只认同一会话里 `read` 工具读过的同一绝对路径，否则 `edit` / 覆盖 `write` 报 `file has not been read`。在工作树里改文件，读和改都用 `.agents/worktrees/<type>/<name>/...`，读主检出的同名文件不算；`cat` / `sed` / `grep` 等 shell 读取不算；新会话、subagent、接手或 resume 后要重新读。授权只看版本，`read` 带 `offset`/`limit` 读一小段即可。shell 写过的文件要重新 `read` 后再 `edit`。
- **建树即声明**：在工作树根目录写 `.ACTIVE-SESSION`。该文件已列入共享的 `.git/info/exclude`，不会被 `git add -A` 带进提交；不要改用设计 README 等会进仓库的文件做声明。字段：

  ```text
  状态：活跃（请勿合并 / 删除 / 重置本分支与工作树）
  分支：<branch>（基于 <commit> <说明>）
  会话：<完整 session id>
  开始：<UTC 时间>
  更新：<UTC 时间>
  内容：<一句话任务>
  当前阶段：<进行中 / 待用户确认 / 待真机核对 …>
  占用文件：<会改动的文件或目录>
  合并：不可合并 | 可合并（已提交 <commit>，建议版本说明 <一句话>）
  后台任务：无 | <命令摘要> → 日志 <路径>，产物 <路径>
  ```
- **每轮结束更新**：每次回复用户前改「更新」「当前阶段」「合并」「后台任务」四项。等待用户确认、待真机核对仍算活跃。
- **结束即交还**：任务完成（或放弃）后把文件改名为 `.SESSION-DONE`，状态写「已结束」，「合并」写明是否可合并及提交号；放弃时写「放弃，勿合并」。
- **合并 / 发布会话**：只合并有 `.SESSION-DONE` 且「合并」为可合并的工作树；`.ACTIVE-SESSION` 或没有任何声明的工作树一律不动，先问用户。合并后在对方 `.SESSION-DONE` 末尾追加一行「已合入 buildNNN（<commit>）」，不改其它字段。**这一行只在推送成功、`git merge-base --is-ancestor <commit> origin/main` 为真之后写**；排队待推的写「待推送 buildNNN（<commit>）」。
- **查看全部声明**：`for f in .agents/worktrees/*/.*SESSION* .agents/worktrees/*/*/.*SESSION* .ACTIVE-SESSION; do [ -f "$f" ] && { echo "== $f"; sed -n '1,10p' "$f"; }; done`

### 本机共享环境（DSH 与所有会话同在一个 App 进程里）

- **Gradle 只走 `dsha-gradle`**：proroot v1.2.8 的 `libproroot-runtime.so` 导出的 `fcntl` 把所有失败改写成 EPERM（抢锁失败本应 EAGAIN）。JDK `libnio` 用这个符号，另一个 JVM 持锁时 `FileChannel.tryLock` 抛 `IOException: Operation not permitted`，Gradle 直接失败。源码修复在 `tools/proroot-compat/stack-sysconf.c`（兼容层导出正确的 `fcntl`），装上含修复的 APK 才生效。本机在工作树内一律用：

  ```bash
  dsha-gradle :app:testStandardDebugUnitTest --tests '*XxxTest'   # 共用 /root/.gradle、--offline、固定 aapt2/Robolectric/JDK17
  dsha-gradle --status                                            # 模式、槽位、谁在占用、日志在哪
  dsha-gradle --stop                                              # 空闲时停掉常驻 daemon
  ```

  它启动时实测 `fcntl`：未修复时全局互斥、`--no-daemon`；已修复时按可用内存开最多 3 个并发槽位并复用常驻 daemon，内存不够就排队。Gradle JVM 的 `oom_score_adj` 设为 900，内存吃紧时先杀构建、不先杀 dsh。日志在 `/root/.cache/dsha-gradle-logs/`。不要直接跑 `./gradlew`，不要再建 `/root/.gradle-<任务>` 副本绕锁（每份约 1 GB，并多起一个 2–3 GB 堆的 JVM），不要杀别的会话的 Gradle 进程。
- **proroot 下 git 大 diff 会 SIGSEGV**：build330（proroot v1.2.8）实测 `git show --stat`、`git diff --stat` / `--numstat` 对某些提交或 ≥8 路径组合 exit 139、stdout 0 字节、stderr 为 `[proroot] SIGSEGV`；`--name-status` / `--dirstat`、`git log` 与拆成 ≤7 路径的分片不崩，GitHub Linux CI 不受影响。审查 / 合入不要用 `--stat` 大范围输出，也不要让 `| head` 或 `;` 吞掉 rc；需要统计时用 `python3 tools/git_guard.py -- <git 参数>`（命中签名立即报错、不重试、不返回部分输出），仓库工具已统一走该封装。细节见[运行引擎](docs/runtime-engines.md)。
- **内存即稳定性**：并发 Gradle / Robolectric 抢内存会把 dsh web 一起拖死，所有会话同时中断。一次只跑一个构建；全量单测只在合并前跑，日常跑定向 `--tests`。
- **不在本机覆盖安装正式包**：安装 `io.github.sunsetrne.dsha` 会被系统以 PACKAGE UPDATED 结束整个 App，DSH 与全部会话随之中断。会话不得自行安装或降级正式包；测试包 `io.github.sunsetrne.dsha.test` / `.debug` / `.dev` 可装。用户要装正式包时，先让各活跃会话更新声明。
- **本机专用覆盖放仓库外**：Conscrypt 关闭等只走 `-I /opt/dsha-x86/local-ci.init.gradle`，不往工作树写 `app/src/test/resources/robolectric.properties` 这类临时文件（已被忽略的 `local.properties` 除外），中断后才不会留下会被提交的残留。
- **长任务写进声明**：后台 / `nohup` 任务必须输出到日志文件并登记在「后台任务」，结束时写明退出码。DSH 重启会杀掉这些任务。

### 中断与接手

- DSH 闪退或余额耗尽后，原会话的工作树和声明保持原样。新会话接手时把「会话」改为自己的 id，并注明「接手 <原 id>（原因）」；同一工作树同时只有一个持有者。
- 接手第一步只读核对：`git status`、「后台任务」日志末尾与产物、临时文件残留。结果为 UNKNOWN 的命令（构建、推送、发布 dispatch）先核对实际状态，**不自动重放**；推送 / dispatch 前先查 `origin/main` 与已有 tag。

## 编码约定

- 注释与 UI 串用中文；提交信息用中文 + `type:` 前缀说明原因。
- 每个协作者单一职责；纯逻辑抽到 `util/` 并配测试；不改历史 SharedPreferences 键名。
- 匹配现有风格：try/catch 包住有风险操作、优雅降级、失败 toast 给用户。
- 原生按钮和选项统一居中与字体边距；普通卡片、主按钮及文字状态共用主题资源，不用独立渐变或硬编码颜色制造同类框色差。布局修订跑 `LayoutAuditInstrumentation` 的 style 中英文验收（日夜、短屏、1.3 倍字体）。
- 应用弹窗统一 `DshaDialogBuilder`，自定义内容必须能在短屏和大字体下滚动；应用状态经 `UiStateText` 按显示边界重新渲染，不缓存语言。
- DNS 默认 `auto`：Node 双栈 `lookup` 仅在 `EAI_AGAIN` / `EAI_FAIL` 时重试一次 IPv4，不重放 HTTP 请求，不降级显式 IPv6；配置页另有 `ipv4`（glibc `no-aaaa`）与 `native`。Web、插件、shell 与 PTY 共用 `RuntimeTools` 的预加载环境。

---

## 文档与流程参考

| 文档 | 内容 |
|---|---|
| [BUILD.md](BUILD.md) | 环境（JDK 17 / SDK 37 / NDK 26 / Python 3.9+）、构建、签名与发布交付流程 |
| [docs/security-model.md](docs/security-model.md) | 安全模型与已知弱点 |
| [docs/plugins.md](docs/plugins.md) | 插件安装、打包契约与验证范围 |
| [docs/releases/](docs/releases/) | 各版本发布说明与真机验收记录（新版本验收写这里） |
| [CHANGELOG.md](CHANGELOG.md) | 完整更新记录 |
