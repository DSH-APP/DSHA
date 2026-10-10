# AGENTS.md

DSHA 的 AI / 新贡献者入口。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

**仓库事实速览**：单 Gradle 模块 `:app`，纯 Java 17、无 Kotlin；`applicationId com.dsh.client`，Java 包 `com.deepseekharness.app`；两个 flavor（`standard` / `low`，共用功能代码）；**arm64-v8a only**。APK 用 proot/proroot 把 Ubuntu rootfs 搬进应用私有目录，在里面跑 Node 24 + pnpm + `@deepseek-ai/dsh`（0.2.0-rc.2）的 Web UI（`:3080`）。当前正式版 v0.2.0-rc2 / build170，APK 版本 0.2.21-20261009.0750-dsh0.2.0-rc.2 / versionCode 170。

**build170 当前策略（覆盖下文同项历史说明）**：移动 UI 3.0.5；第三方插件检查后自动提交/启用，允许依赖生命周期脚本和 pnpmfile；新导出为无密码 tar.gz，内部自动副本及旧加密原件读取保留。两版各45项关联检查和 Android13 非破坏性正式包验收范围见[当前发布说明](docs/releases/v0.2.0-rc2-notes.md)与[build170](docs/releases/v0.2.21-build170-20261009.md)。下方目录数量为历史索引，不代表当前源码统计。

---

## 目录地图（按包，先看这里）

```
app/src/main/java/com/deepseekharness/app/
├── *.java（根包，27 个）      设备与服务层：无障碍、桥、LAN、悬浮条、Shizuku/Root、PTY
├── ui/      （69 个）         界面：MainActivity + 各 Fragment / Activity / 对话框
├── core/    （27 个）         编排：HarnessController、维护屏障、备份任务、诊断
├── runtime/ （18 个）         容器：ProotBootstrap、ContainerRuntime、WebProcessManager、装机
├── backup/  （62 个）         数据：v5 加密备份、恢复、迁移、格式化、自动备份（最大的包）
├── vscreen/ （10 个）         独立虚拟屏：通道 / 预览 / 输入 / 无障碍桥
├── recovery/(4 个)            独立应急 DSH：与正式环境完全分开的生命周期
├── bridge/  （3 个）          AppBridge 契约、AdbBridge、LAN 访问
├── data/    （3 个）          KeyVault（Keystore）、便携设置、导出
└── util/    （118 个）        纯逻辑：不 import 任何 Android API，必须配单测
```

### 根包（`com.deepseekharness.app`）——设备与系统服务

| 类 | 职责 |
|---|---|
| `HttpShellService` | **3090 桥本体**（约 105 KB，最大的类）：`/exec` 与 `/app/*` 共 24 个端点，token 门控 |
| `DshaAccessibilityService` | 无障碍：`uiDump / uiTap / uiTapText / uiInput / uiKey / uiSwipe / uiScreenshot` + 虚拟屏转发 |
| `DeviceBridgeService` | 设备通道总管：ADB / Shizuku / Root 三通道选择与保活 |
| `DeviceShellExecutor` / `DeviceAppInventory` / `DeviceSense` | 设备命令执行（白名单在 util）、应用清单、传感器 / 位置 / 手电 |
| `LanProxyService` / `LanAuth` | 局域网代理 `:3081`（token fail-closed）与鉴权 |
| `OverlayController` / `OverlayLines` | 流式悬浮条（歌词式 AI 输出 + 命令批准） |
| `HarnessService` / `WebPreviewActivity` | 前台服务 + 看门狗；Web 内嵌预览（WebView / Gecko） |
| `PtySession` | Termux terminal-emulator JNI 包装（真 PTY 终端） |
| `ShizukuShell` / `RootShell` / `ShellService` / `AdbKeepAliveReceiver` / `BootReceiver` | 通道实现与开机 / 保活接收器 |
| `DshaDocumentsProvider` | 文件管理器访问私有目录（MT 管理器等） |
| `DangerShellGuard` / `BridgeAskDialog` / `ConfirmReceiver` | 危险命令确认（通知 / 弹窗 / 悬浮条三渠道）；agent 征询用户 |
| `DshaApp` / `UpdateDownloadService` | Application 入口（系统语言锁存等）；应用内更新下载 |

### 分层归属（改代码前先看这里）

| 层 | 关键类 | 职责 |
|---|---|---|
| `ui/` | MainActivity / WelcomeActivity / Launch·Config·Settings·Plugin·Workspace Fragment / WebPreviewActivity | 界面与启动门禁 |
| `core/` | `HarnessController`（约 57 KB，编排核心） | 启动 / 停止 dsh、鉴权链接捕获；**不要再往里塞新职责**，新逻辑写成独立协作者 |
| `core/` | `ConfigStore` | 配置唯一读写入口（端口 / 模型 / workdir / API key） |
| `core/` | `InstallRepository` / `MaintenanceCoordinator` / `RuntimeTasks` / `StartupDiagnostics` | 安装状态、维护屏障、运行任务锁、启动诊断 |
| `runtime/` | `ProotBootstrap` / `ContainerRuntime` / `WebProcessManager` | proot 命令组装；运行时选择 + BINDS 挂载清单；停止 Web（哨兵 + pid + 出生身份核验） |
| `runtime/` | `InstallPipeline` / `ManagedRuntimeAssets` / `RuntimeTrial` | 六步安装编排、受管资产、更新试运行 |
| `backup/` | `NativeBackupJobs` / `BackupArchive` / `HostDataTransaction` / `AutomaticBackups` / `FactoryReset` | v5 加密备份 / 恢复 / 数据事务 / 自动计划 / 格式化 |
| `recovery/` | `RecoveryController` / `RecoveryRuntime` / `RecoveryRepairBroker` | 独立应急 DSH，不经正式环境的迁移与启动锁 |
| `vscreen/` | `VirtualScreenManager` / `VirtualScreenAccessibility` / `VirtualScreenPreviewView` | App 持有认证连接与心跳；输入必须带最新帧号 |
| `bridge/` | `AppBridge`（契约）/ `AdbBridge`（无线配对 TLS1.3-PSK + SPAKE2）/ `LocalNetworkAccess` | 桥接缝与设备通道 |
| `data/` | `KeyVault` | Keystore AES/GCM 加密 API key |
| `util/` | `Constants` / `ShellQuote` / `Query` / `WebProcSel` / `DeviceShellPolicy` / `HttpProtocol` / `BackupScope` 等 118 个 | 纯逻辑，全包零 Android import，**必须配单测** |

---

## 测试布局

`app/src/test/java/com/deepseekharness/app/` 共 172 个测试类，分布：`util/` 112、`backup/` 45、`core/` 6、`runtime/` 6、`vscreen/` 1、`ui/` 1、根包 1（`LanAuthTest`）。

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

- **当前本地交付版本为 0.1.7-rc2 / 147**：E7E3 发布证书（完整指纹见 build 131 记录）用于正式 APK；A3F4 仅保留为历史手机验收规则。验收交付流程见下文「交付与验收」。
- `standard`：minSdk 30，系统 WebView；`low`：minSdk 23，内置 GeckoView 143。构建任务 `assembleStandardRelease` / `assembleLowRelease`。兼容版 proot / loader / 终端 JNI 以 API 23 重编（`tools/build-low-proot.py`、`tools/termux-jni/`），**不要把标准版 proot 当 Android 6 可执行文件**。
- 离线 rootfs（`assets/offline-rootfs.bin`）**不提交**，由准备脚本生成；APK 用 `offline-rootfs.layout=split-runtime-v1` 标记 Ubuntu 与 `dsh-runtime.bin` 分包，构建核验两份摘要。
- 新 dsh 依赖由 `tools/dsh-runtime/package-lock.json` 锁定；`tools/prepare-runtime-descriptor.py` 生成独立受管契约描述，APK 版本仅作诊断来源。相同基础版本更新受管树，个人数据保持原位；**不因 UI-only APK 更新替换环境，不因普通 dsh 更新递增 Ubuntu 基础环境版本**。
- 离线 curl / git / 证书由 `tools/ubuntu-tools/packages.lock.json` 锁定（`tools/prepare-ubuntu-tools.py` 生成 `ubuntu-tools.bin`）；网页 ES 兼容依赖锁在 `tools/web-compat/`（`node tools/prepare-web-compat.mjs`）。生成文件不提交，构建核验摘要。
- 中英文界面：文案目录 `tools/i18n/messages.json`，构建生成 Java 文案字典；偏好 `system`（默认）/ `zh` / `en`；**系统语言必须在进程最早时刻锁存**（`SystemLanguage.initialize()`，早于任何 `Locale.setDefault`），否则「跟随系统」切一次就自我锁死。语言切换只重建界面，不停终端或 Web；用户输入、命令原文不做自动替换。
- 改文案的三条硬要求：① `messages.json` 里的 `zh` 必须与 Java 字面量**逐字节一致**（用 `json.dumps` 做定点替换，别整体重写该文件）；② 跑 `tools/check-ui-i18n.py`，`newFindings` 必须为 0 —— 顺带注意：改动带 `nonFindings` 声明的文件（`BackupTask` / `EnvironmentMaintenance` / `RecoveryController`）会让声明失效，必须一并处理；③ 跑 `tools/prepare-ui-languages.py`，带 `%s` 的模板中英签名必须一致。
- 旧 WebView 的 `AbortSignal.any/timeout` 与 `crypto.randomUUID` 补齐：兼容脚本须进入受管 HTML 的应用脚本之前，并覆盖文档起始与 Worker。

### 数据与备份

- **自动备份默认开启**（2026-09-17 要求，覆盖更早的禁用规则）：`AutomaticBackups` 支持每日指定时间、1–168 小时间隔、停止后三种模式（`util/AutomaticBackupPolicy` 有单测）；到期仍在运行则延后，不强停 Web / 终端；保留最近 3 份已验证自动副本，手动与未知记录不自动删除。
- 宿主归档 v5 使用 `DSHA-data-v5-<UUID>.dshbak`，密码默认必需、原生 API Key 默认排除；先完整 AEAD 认证再预检，只写私有编号槽位。本机状态不能从用户归档生效；受管脚本、可执行配置和未知插件先隔离。
- 数据格式不能由 APK 或 dsh 版本号推导：`DataFormatEvidence` 对未经完整检查的数据保留 unknown；发件人声称的已验证格式不能成为删除原件的依据。
- 覆盖更新闲时清理可再生缓存及多余副本：保留至少两份健康且字节符合计划的副本，修改过或含额外文件的原件不删除；新重建通过核验后建立 `retired-proof.json` 再清理 `previous-linux`。
- 完整格式化对会话、插件、配置、API Key、运行时等核心私有根保持严格删除与复核；仅 WebView 缓存等可重建目录可把持续 `ENOTEMPTY` 降级为明确警告。
- 覆盖安装用原包名、同签名、非调试正式包，做非破坏性检查；不在正式数据上运行强杀、删 Bash、坏插件等故障注入。

### 运行时与更新

- 新受管更新使用 `ManagedRuntimeTransaction` / `HostDataTransaction`；真实隔离 `RuntimeTrial` 核验静态资产 / 原生模块、实际端口 / 鉴权、存储和会话写后重开、网页内核与后端握手及 guest 退出后才能提交。回执保存在 rootfs 外并绑定文件摘要；至少保留直接健康前代。
- `RuntimeTrial` 必须记录实际 `runtimeMode`。所选 proroot 在鉴权前明确退出且无插件故障时，可按 `WebRuntimeFallback` 仅重试一次 proot；**不能把 proot 成功报告成 proroot 原生成功**。静态检查先选 proot，慢启动不触发重试。
- `RuntimeTrialRecords` 只轮换已确认关闭的现场：保留最近 3 条成功和 5 条失败；未关闭或标记异常的目录不得自动删除，也不能以累计次数设永久上限。
- 就绪检查必须解析实际 `/bin/bash` 的 ELF 和加载器路径；可执行位用 lstat（受 Android W^X 影响的 canExecute 不可靠）。损坏系统不得反复执行配置安全启动，应进入环境修复。
- 插件依赖用随包 pnpm 10.34.5 原生锁，冻结安装、禁用生命周期脚本与 pnpmfile 钩子；插件安装原件与目录切换由 `plugin-transactions.py` 记录，未提交日志不得被普通启动覆盖。第三方插件启用必须经静态审阅与内容复核，安装后先保持停用。
- 系统插件与用户插件分层：系统插件源码不进用户备份，由当前 APK 按精确受管证明重建；`.disabled` 与旧实体冲突时禁用意图优先。
- `NARB_DISABLE_NATIVE_CACHE=1` 让原生扩展直接从随包目录加载（默认缓存在 `--link2symlink` 下首次悬空）。

### Web / 桥 / 终端

- dsh 启动契约：`exec dsh web --no-open --host 127.0.0.1 --port 3080`；env `DSH_HOME=/root/.dsh`、`DEEPSEEK_API_KEY`（非空才 export）、`DSH_PERMISSION_MODE`、`DSH_CONFIRM=1`、`BROWSER=true`、`cd /root`。pid 文件在 `exec` 之前写（`exec` 不换 pid）。proot 二进制从 `nativeLibraryDir/libproot.so` 执行（不能放 filesDir，Android 10+ W^X）。
- Web 首选端口冲突时保留用户配置，先试本机成功备用端口，否则用锁定 dsh 的 `--port 0` 分配；动态鉴权只接收本轮官方启动行；保活、鉴权和 LAN 必须用实际端口，**不能按端口猜 PID 终止进程**。
- Web 启动等待鉴权不设强制终止时限（60 秒仅提示）；停止与维护必须共用 `WebProcessManager` 的 PID 身份 / 出生身份核验。
- 3090 桥协议版本做特性检测（`/app/version`，`BRIDGE_PROTOCOL=2`）；插件判版本用 `>=` 而不是 `==`。命令查询串预算不得缩到低于 8192 字符需求。
- LAN 正文由方法、状态码和明确传输边界决定；只有真正验证过的 101 WebSocket 握手可切隧道；不把 hop-by-hop 头移除后继续发送不匹配的编码体。
- PTY 出生身份在原生 fork/exec 握手中登记：子进程自读 stat，父进程确认后才 exec；不得退回 Java 启动后单次读取。PTY 与简易终端独立标签和会话，跨切页 / 旋转 / 语言切换保持进程；关闭单个标签必须核验该会话退出后再移除。终端会话 ID 永久递增，显示编号独立复用最小空缺。

### 应急 / 虚拟屏 / 设备

- **独立应急 DSH**：`recovery/` 不经过正式环境的迁移、数据绑定及全局启动锁；归档由 `tools/recovery-runtime/lock.json` 单独锁定；应急 Profile 仅有五个受控修复工具。不能用「原生安全 Profile」替代独立运行根。`RecoveryRepairBroker` 的 HTTP 接口不能确认写入：原生确认绑定候选、源摘要及数据代次，再经停止屏障和宿主事务。
- 应急资产去重：`recovery-asset-locations.json` 仅映射物理位置；仅当正式归档 SHA 与独立应急锁完全相同才共用包内字节，解压运行根仍独立，不读取正式 rootfs 作为救援依赖。
- **虚拟屏**（Standard，API 30+）：App 持有认证连接和心跳；**坐标与文本输入**必须带"刚 `see` 过且仍是最新"的 frameSeq（`util/VirtualScreenPolicy.frameRejection` / `observationRejection`，拒绝时回 `STALE_FRAME` + `reason`），语义操作（`node` / `editor`）不校验帧号，安全性由无障碍层的树新鲜度与节点身份承担；`VIRTUAL_DISPLAY_FLAG_TRUSTED` 只在调用方确实持有 `ADD_TRUSTED_DISPLAY` 时请求（API 31+ 不持有却请求会被系统拒绝）；动作失败**绝不换通道重放**。
- **麦克风**：清单同时声明 `RECORD_AUDIO` 与 `MODIFY_AUDIO_SETTINGS`；由 `BrowserMicrophone` 按网页请求申请，不能在普通启动时预授权；只放行当前本机页面的纯音频请求，摄像头 / 屏幕音频拒绝；旧回调不能批准新页。
- **设备验收经验**：`uiautomator dump` 会抑制其他无障碍服务；截图授权绑定 DSH generation，停止 / 断连 / 撤销后失效；截图存应用私有 Pictures/DSHA，无需所有文件访问；PiP 可能盖住底部控件，自动化必须核对实际可见区域与屏幕方向；截图 / 读屏不可将敏感值写入公开取证文件。
- Shizuku 必须注册 `rikka.shizuku.ShizukuProvider`；标准版 13.1.5 / 兼容版 12.2.0（不用 overrideLibrary 掩盖 minSdk 24）。回调严格核对管理器 UID、Binder 描述符、单次请求与超时。
- 内置移动插件 `dsh-web-mobile` 标 3.0.3，源码固定到 `a094288883b343e848d7f9cf302d73ad8ed4794b`；`tools/apply-mobile-client-patches.mjs` 管理本地差异，不恢复上游已撤回的整批改动；上游已接管的 rc1 导航补丁不要重新套回。

---

## 启动契约（不可破坏）

- `welcomed == false` → `WelcomeActivity` → 点开始 → `MainActivity`；`MainActivity` 进入前校验 `welcomed`，否则永远回 Welcome。
- 正常运行检查 `.offline-extracted` 与完整环境身份。维护页允许进入受限主界面查看配置和日志；**此入口不能伪造就绪标记**，终端、Web 与插件执行仍须通过环境门禁。
- 启动失败自动进入原生 `StartupRecoveryActivity`；等待鉴权本身不是故障。最近五次脱敏启动记录独立保存在私有目录。
- 兼容重试仅在 proroot 已确认退出、鉴权前且无明确插件故障时进行一次，**不能由超时触发**。
- 配置快照由宿主 `ConfigurationSnapshots` 执行：正文逐文件存于 rootfs 外，保留三份健康与三份修复前快照；旧日志已提交后不得覆盖后来修改的配置。
- DocumentsProvider 保留 `root` 和 `linux/ubuntu/...` 的既有不透明文档 ID；guest 绝对软链接按 rootfs 解析，删除链接不递归目标；写文件先验证已打开的文件描述符再截断。
- 短信查询属于独立敏感能力：只允许当前 Android 用户的严格 `content query`；原生预授权默认关闭、可撤销、不从系统备份恢复。

---

## 已知 trap（搬自原版，骨架已按此设计）

- **停止靠 pid 文件 + 出生身份，不靠端口反查**：`/proc/net/tcp` 非 root 读不到（静默空），`/proc` 有 hidepid。启动时 `echo $$ > /root/.dsha-web.pid` 再 `exec node`；停止先写哨兵 `/root/.dsha-stopped`（看门狗见到就退出，否则「秒复活」）。旧 PID 复用按内核 `kill(pid,0)` + 出生身份二次核验，不得按裸 PID / 端口 / 名称强杀。
- **app 私有目录禁 `link(2)`**（SELinux）：proot 必须带 `--link2symlink`；`PROOT_L2S_DIR` 在 rootfs 内的 `.l2s` 必须绑定到相同宿主绝对路径，否则 dpkg 安装对硬链接 chown/stat 会报文件不存在。
- **两把签名钥匙各管一件事**：线上 APK 用历史发布证书（E7E3），增量更新清单用独立 keystore，绝不混用。
- Android 的 `FileInputStream(FileDescriptor)` 不接管传入描述符：宿主备份通过 `ParcelFileDescriptor` 自动关闭流明确转移所有权。
- 数据维护先关闭 PTY 与简易终端再取 `RuntimeTasks` 屏障；proroot 终端按本次独立会话核验并回收 guest，不能只结束启动器就释放工作锁。
- 维护成功文案不能代替就绪检查；未就绪时留在原生页，不自动跳回循环。维护页忙状态不使用插件查询 / 终端等通用锁冒充数据维护。

---

## 交付与验收

- **发布交付目录固定**（见 BUILD.md）：两版 APK 及 `.apk.sha256` 用常规文件名；同版本验收后覆盖，**不新增 `-buildNNN` 并列副本**，不同历史版本保留。
- 覆盖安装前核对包名、版本码、证书和 `debuggable=false`；不得卸载后用错误证书替代。
- 验收范围如实声明：不把本机验证扩写成 Android 6/7、16 KiB 或全部外部模型服务矩阵完成；Standard/Low 与虚拟屏能力按实际设备证据声明。
- 各版本发布说明、真机验收与专项证据写进 `docs/releases/`；官网 dsha.cc 的清单同步与部署验证范围见 `docs/website-rc2-20260928.md`。

---

## 编码约定

- 注释与 UI 串用中文；提交信息用中文 + `type:` 前缀说明原因。
- 每个协作者单一职责；纯逻辑抽到 `util/` 并配测试；不改历史 SharedPreferences 键名。
- **对外错误码只表达“发生了什么、下一步做什么”**：不得把异常类名或原始异常消息当错误码外泄（`util/VirtualScreenErrors` 是唯一的映射点）；同一个码不得覆盖“没等到用户回答”与“用户明确拒绝”两种语义。
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
| [docs/android-low.md](docs/android-low.md) | Low 版兼容差异（API 23 重编、desugaring、老系统权限） |
| [docs/releases/](docs/releases/) | 各版本发布说明与真机验收记录（新版本验收写这里） |
| [CHANGELOG.md](CHANGELOG.md) | 完整更新记录 |
