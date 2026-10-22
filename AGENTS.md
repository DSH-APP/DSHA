# AGENTS.md

> **当前发布（2026-10-23）**：[v0.2.1-rc.2 正式版](docs/releases/v0.2.1-rc.2-notes.md)，沿用2100标准APK / `dsh.android.client`。用户已授权以贡献者身份直接公开发布至 DSH-APP/DSHA，不创建PR；覆盖此前公开暂停规则。下方较早的“仅私有交付/公开暂停”按当时记录阅读，不作为当前发布规则；本次发布不修改应用实现与APK字节。

> **2026-10-22 插件速度维护（当前2100）**：`0.2.1-rc.2-20261022.2100-dsh0.2.1-rc.2` / code190 / `dsh.android.client` / E7E3非调试Standard，292,198,739字节，SHA `d62f17f23a7949f0f25285ec7afa44cb35de24a6bc91887ba4600023d18ffee0`。独立management查询保留原完整list合同；两worker仍完整校验，V2保留旧policy实际幂等锚点。2020候选真机被PATCH_CHAIN_MISMATCH拒绝，未交付；修正后真实Java六模块、旧版升级和精确APK门禁通过。两版44类各332项零失败/错误/skip、17宿主及APK7通过；Lint警告248/161保留，失败原件与test-only依赖修正分开记录。停止覆盖58文件首启前逐字节相同，手机proot工作台与有效插件启停已验；开关反馈到确认约3.10–3.39秒，页面三轮1.41–2.47秒波动，不能证明普遍整页提速。PR119的既有生产布局修复保留并补22项回归，1817像素记录仍有3帧局部标题异常，不能宣称全部PiP问题消失。默认画中画关闭，用户临时验收后已还原。公开DSHA上传仍暂停，仅私有维护交付。[本轮报告](docs/releases/v0.2.1-rc.2-build190-ui-performance-20261022.md)。

> **2026-10-22 UI与息屏维护（1817历史）**：`0.2.1-rc.2-20261022.1817-dsh0.2.1-rc.2` / code190 / `dsh.android.client` / E7E3非调试Standard，默认proot、可选proroot。APK 292,189,033字节，SHA `8460ee70f5f6946132372737cfc90f097e00e89dc519973af5eb783f833ac60e`。插件配置锁序、重复刷新和无关诊断等待已修；会话单击切换、侧栏按压闪屏与预设pending布局/旧V1升级已修。可见用户启动在当前帧申请FGS，真实promotion后准入，Stop/超时/服务丢失按精确票据及代次收尾，不后台任意提升或重放业务。两版关联42类各310项零失败/错误/skip、host14和APK6门禁通过；Lint零错误/致命、警告248/161。Low API24误用与测试SDK重载编译失败原件均保留，经过修正重跑，非一次连续全绿。最终包正常停止覆盖安装，58个受检查用户文件在首启前逐字节一致；双运行时点击后立即息屏建立后端与CPU锁，不能外推自然Doze/OEM或整机功耗。完整最终手机范围见本轮报告。Root递归READ子链接缺口及旧内部入口锁链未扩称修复；Low172原字节不动。公开DSHA上传/PR/发布仍暂停，仅私有维护交付。[本轮报告](docs/releases/v0.2.1-rc.2-build190-ui-performance-20261022.md) · [实现与回滚](docs/ui-performance-20261022.md)。

> **2026-10-22 性能维护**：`0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / code190 / `dsh.android.client` / E7E3非调试Standard。当前身份与默认proot/可选proroot覆盖旧规则。同台隔离完整初始化65.128→42.772秒，生产温启动proot三轮12.8/12.5/12.3秒、proroot6.8/5.2/5.4秒（有限顺序/缓存样本，不外推普遍百分比）。精确APK292,159,963字节、SHA `d77bf985a5f9ec49be0667625894bcd16c5f3a23df2210a837527af8a172a947`；两版选定JUnit各1,143项零失败/错误，为分段结果，skip3/76、Lint警告249/162保留。已做真实同签名停止覆盖、冷流程、双引擎Web、三次后台返回及独立应急正常启动/停止；无真实模型对话或新长时/功耗对照。升级trial的proroot126后回退proot不算proroot成功；Root递归READ子链接缺口未修复且已披露。公开DSHA上传/PR/发布仍暂停，仅私有维护交付；Low172不变。[本轮报告](docs/releases/v0.2.1-rc.2-build190-performance-20261022.md) · [调用链与回滚](docs/performance-startup-20261022.md)。

> 当前发布身份：**`dsh.android.client`**；code190、E7E3及功能保留，诊断版本以顶部本轮记录为准，覆盖下方历史包名规则。与旧 `com.dsh.client` 的数据独立，通过备份导出/恢复迁移。当前授权：公开 DSH-APP/DSHA 的上传、PR 与发布继续暂停；本轮修复和验收完成后仅上传既有私有维护仓库。[本次记录](docs/releases/v0.2.1-rc.2-build190-package-rename-20261021.md)。


> F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190：修复目录服务依赖组合、附件resolver退休及后台临时身份失败后的恢复。Standard选定150类/952项JUnit（3跳过），Low选定150类/952项（76跳过），两版零失败/错误；Release Lint零错误、Standard/Low警告249/162，八项实际APK检查通过。原始失败、纠正及负控分开保留，不称一次连续全绿；无设备/ADB、真实账号/模型或新长时验收。[关联Release入口](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [范围与证据](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。实际远端可得性由Release与独立回执核验。

已发布F12保留原身份：[原记录](docs/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [原Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413)；F11仍为未上传本地检查点。

> build190 本轮用户决定（2026-10-19）：以已发布的 build170 / `v0.2.0-rc2`（8362bba747fcf84c19e436c3dbb594a0fc388a67）为源码基座，定向迁入 build187（ac7bf8eda8b46a22f6412b17ec3574bf895b1876）的全局授权、虚拟屏、任务通知等功能。保留生产旧数据、宿主 v5 备份和迁移链，不引入实验版 fresh-only 数据/备份架构。引擎恢复 `proot + proroot`，首次安装与未显式选择时默认稳定 `proot`，显式 `proroot` 可选；不迁入 bxroot。正式身份保持 `com.dsh.client` / E7E3 / 非调试，versionCode 190，基础版本及下载文件版本 `0.2.1-rc.2`，内置官方 DSH `0.2.1-rc.2`，Ubuntu base10 不变。危险的完整格式化生产入口与擦除后端仍退休，外置工作区不能清理或重放旧事务写删。标准版优先，历史 Low172 与既有正式交付原件保留。总资产、描述符、签名打包及交付由主代理统一完成；下面 build170 数量与版本按基座历史阅读。

DSHA 的 AI / 新贡献者入口。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

**F13当前入口**：`0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` /code190。F13冻结源码2215成员/200篇Markdown，Java adoption 1029项。分发/格式证明与JUnit执行计数分开。固定输入包738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，原始输入直接展开到项目路径；不是完整源码ZIP的大小或摘要。本批软件与APK见[F13记录](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。UNKNOWN保留原DOM等待同身份核验，不授ready；旧票不复活、业务不重发；guest与launcher停止仍分证。

**仓库事实速览**：build170基座/code190，Java17/:app/arm64，DSH0.2.1-rc.2/base10，proot默认+proroot可选。固定包内启动入口及受管dsh包装继续保留。键盘动画期间不逐帧setPadding，原生有界帧通道用当前完整身份校验，网页只合成移动聚焦composer，末帧提交布局，失焦/分离/PiP清理。mobile3.2.1固定main10e58ce，保留安全服务端差异，不恢复上游撤回的#236。OneClickBackup复用NativeBackupJobs安全停止/v5/tar.gz/读回验证；Standard写Download/DSHA，Low旧系统首次选目录；不扫外置工作区、不默认导出原生密钥、不自动重启或重放。源码冻结、测试原始失败与补跑、精确APK资产及最终上传回执分开保存，不把浏览器机制回归写成手机FPS。完整源码ZIP直接展开固定输入；不能从F盘旧173构建。

维护入口见[文档总索引](docs/documentation-index.md)、[项目总手册](docs/project-architecture-handbook.md)、[权限](docs/device-permissions-architecture.md)、[环境/数据/插件](docs/runtime-data-plugin-architecture.md)、[运行引擎](docs/runtime-engines.md)、[端口](docs/ports-and-networking.md)、[DNS](docs/dns-configuration.md)、[虚拟屏](docs/virtual-screen-architecture.md)、[ADR0003](docs/adr/0003-virtual-screen-lifecycle-and-input.md)及[来源依据](docs/references-and-provenance.md)。

**build170基座历史策略**：移动 UI 3.0.5；第三方插件检查后自动提交/启用，允许依赖生命周期脚本和 pnpmfile；新导出为无密码 tar.gz，内部自动副本及旧加密原件读取保留。两版各45项关联检查和 Android13 非破坏性正式包验收范围见[历史发布说明](docs/releases/v0.2.0-rc2-notes.md)与[build170](docs/releases/v0.2.21-build170-20261009.md)。移动UI等当前值以上方本轮决定为准，下方目录数量为历史索引。

---

## 目录地图（build170历史数量；当前职责导航）

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
| `DeviceShellPolicy` / `DeviceShellExecutor` | 受管设备命令解析与执行前守卫；批准不覆盖显式拒绝，Root 也受此策略检查 |
| `BridgeAskDialog` / `ConfirmReceiver` | 原生业务提问与安全确认通知回执；业务选择不由全局确认代答 |
| `DshaApp` / `UpdateDownloadService` | Application 入口（系统语言锁存等）；应用内更新下载 |

Root 维护以 [权限架构](docs/device-permissions-architecture.md) 与 [固定上游源码核查范围](docs/references-and-provenance.md) 为准：九个 su 候选及 metadata 检查不等于管理器识别或全版本兼容；65 秒只限制结果收集。2026-10-22 对照 `140c7ba` 的审查发现递归 READ 尚未核验子链接目标，短信保护存在实现覆盖缺口，不能写成“仅有文档问题”或“完整防绕过”；未修状态、回归与设备证据须分别记录。历史 `DangerShellGuard` 已不在生产调用链。

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

## 测试布局（build170历史快照）

下列数量仅描述基座历史分布；F13实际选择类/XML、失败/错误/跳过与最终软件结果以[F13记录](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)及统一回执为准，不从静态文件数推导执行数。

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

- **当前身份以本轮冻结回执及app/build.gradle为准**：code190、E7E3用于正式APK；0.1.7-rc2/147及A3F4均为历史记录，不作为本轮构建或签名输入。最新软件交付范围见上方决定。
- `standard`：minSdk 30，系统 WebView；`low`：minSdk 23，内置 GeckoView 143。构建任务 `assembleStandardRelease` / `assembleLowRelease`。兼容版 proot / loader / 终端 JNI 以 API 23 重编（`tools/build-low-proot.py`、`tools/termux-jni/`），**不要把标准版 proot 当 Android 6 可执行文件**。
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
- 内置移动插件 `dsh-web-mobile` 为 3.2.1，固定上游 main `10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`；`tools/apply-mobile-client-patches.mjs` 管理本地差异，不恢复上游已撤回的视口改动；上游已接管的 rc1 导航补丁不要重新套回。

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

- 已发布F12保留[原记录](docs/releases/v0.2.1-rc.2-build190-fix12-20261021.md)与[原Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413)；当前F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2`继续使用既有私有维护仓库。最终软件/APK见[本批记录](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)；源码/附件/远端状态由独立回执核验，旧包不能代替本批证明。
- 所有设备操作已暂停，不安装、启动、关闭或对话；软件检查核对实际包名、code190、E7E3及`debuggable=false`，不以检查通过授权操作手机。
- Git保留构建必需、有notice/锁的小型JNI/vendor/解释器预编译依赖及27个必要历史构建源码、唯一website夹具；不宣称所有vendor native可从源码重现。大生成归档不入Git；Release完整源码ZIP额外含ci-assets.py逐SHA核对的原始大构建输入，配置工具链后按recipe构建，不需独立bundle，不能从APK逆恢复全部输入。6个审计JSON原路径白名单见[CONTRIBUTING](CONTRIBUTING.md)。私钥、用户数据、设备图/日志和历史现场不上传；ZIP自身摘要只写Release正文，避免自引用。
- 验收范围如实声明：不把本机验证扩写成 Android 6/7、16 KiB 或全部外部模型服务矩阵完成；Standard/Low 与虚拟屏能力按实际设备证据声明。
- 本轮只新增预发布范围与软件证据，历史fix2/fix3保持原样；不更新原仓库、原Release或官网清单，不部署网站。

---

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
| [docs/android-low.md](docs/android-low.md) | Low 版兼容差异（API 23 重编、desugaring、老系统权限） |
| [docs/releases/](docs/releases/) | 各版本发布说明与真机验收记录（新版本验收写这里） |
| [CHANGELOG.md](CHANGELOG.md) | 完整更新记录 |


## build190 fix1 历史补充（2026-10-19，所列有限验收仅属当时APK）

fix1当时本地日期版versionName **`0.2.1-rc.2-20261019.2352-dsh0.2.1-rc.2`**，versionCode190、`com.dsh.client`和下载文件名 `dsha-0.2.1-rc.2.apk` 不变。正式APK SHA-256 `5b1d8f5d6f71c7527f48d336013c74c6a2bf9e4f8811df6d416337deaac1ac38`，314,189,001 bytes；`fix1-dated-version-final` 91.39秒通过，两版各511项零失败/错误（Standard508pass/3skip、Low481pass/30skip），Lint0error、253/165warning，runtimeId仍为 `fa4759062b5cc65fea8bdff170fc4554083555f598d42596f57ad023d0442da8`。15:53:39 UTC正常停止后精确同签名覆盖并原名早交付，安装APK摘要匹配、UID/首次安装时间保持，f744入history。

本包15:55:04 UTC实际DSH就绪/proroot/0.2.1-rc.2/环境READY，日志 **7.7秒后端鉴权就绪、无fallback**；15:55:29 UTC同一既有验收会话可读。

真实DeepSeek第六轮先status STOPPED/cleanupfalse，再空portrait create（1,330ms）→同core active status→精确close（964ms），原始工具详情确认oktrue、EXITED、displayReleaseAcknowledgedtrue、STOPPED、cleanupPendingfalse。请求720×1280但接口仅orientation，实际1008×1792，不计精确分辨率通过。模型未调用其他app/input/screenshot/个人读取工具。

本包16:00:51 UTC关modal后Back→Main、同会话再入；16:02:53 Home实际PiP，16:06:11真实物理启动器→Main+原PiP，16:07:51原同任务同会话恢复全屏。小窗正常Stop后16:10:17旧Web任务移除、DSH未运行/环境READY、自有UID仅宿主、HarnessService不存在、独立DeviceBridge保留；16:11:48 proroot3.2秒后端鉴权就绪、无fallback，16:12:57以不同新任务恢复同会话，全屏1440×3200/PiPfalse，无旧小屏残留。

两轮网页ready日志（含人工停留）分别46.0秒/96.1秒，与后端鉴权7.7秒/3.2秒分开，不作全程启动耗时。

16:18:49 UTC临时ON撤回OFF/CACHE_VERIFIED/rev3；16:21:19 DSH正常停止，无Web/Harness、仅host，独立DeviceBridge保留；16:21:31停止同SHA覆盖，UID/首次安装时间保持，16:24:09新host进程读回OFF/NONE/rev3，仍无Web/Harness。用户随后切到其他应用即停止操作，16:25该屏排除为DSHA验收判据；没有新导出最后诊断。仅本机Standard/WebView所列有限路径已验。

源码zip预定 `artifacts/source/build190-fix1/dsha-build190-fix1-source.zip`，最终回执预定 `artifacts/deliveries/build190-fix1.json`，本次文档冻结时待生成核验，不声称已存在。旧SHA结果不转记本版。

此前已交付f744为 `0.2.1-rc.2 / 190`、E7E3、非调试，SHA-256 `f744bbb21b5df4c5eee3e72ee8321bfb5994d852c08b98d65b9b82d65c9c3738`，314,188,808 bytes。其两版JUnit各511项：Standard508通过/3skip、Low481通过/30skip、零失败及错误；Lint0error、253/165warning；actualAPK验证通过，75 installs/147 assets/371 launchers，runtimeId `fa4759062b5cc65fea8bdff170fc4554083555f598d42596f57ad023d0442da8`。这些仅为f744历史。该包精确同签名覆盖与早交付已完成；获准真实重启及boot变化核对后，实际Main为环境READY/Web未运行，全局ON/NONE/revision2可重开、原生ADB连接验证。MCP完整功能未通过，不扩大为全部设备验收；日期版已安装并有独立proroot就绪及空虚拟屏创建/关闭实测，其所列有限路径已完成，长期/完整矩阵未覆盖。源码zip与最终回执待生成核验，未上传GitHub、网站或Release。

- 本轮已修版本/注册表一致性、rc1迁移来源、wrapper兼容、BrowserOrigin私有根/官方空草稿、Harness退休、proroot入口/shim、授权XML尾LF、混合任务导航和MCP错误映射。生产数据、同代网页、未知退出保护及独立DeviceBridge保留；授权37、导航41、原生工具链21等宿主边界见[fix1报告](docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md)。
- 历史692：Android13有限OFF修复；Android16已复现9ee Back/启动器故障，692实际READY、proroot6.1秒无fallback、Back/Main/物理启动器/同会话全屏有限通过。原生恢复OFF/revision1后，同SHA覆盖重开13:10:31 UTC读回ON/NONE/revision2；真实只读模型轨迹printf、/app/device、/app/plugins（163/0/0）有结果，授权失败和CLI缺profile后的修正保留。MCP status为NOT_RUNNING，ask-user人答FAIL并提交；create随后JOURNAL_UNAVAILABLE，不能算虚拟屏通过。
- f316修NioInstallationIdentity的FileStore拒绝，保留NOFOLLOW/CREATE_NEW/Unix0600与原身份；11项定向通过。实际第五轮已越journal，却ADB_CONNECT_FAILED→START_UNKNOWN、cleanupRequired=true、无core身份；旧屏障保留。后续原生ADB连接验证不能补成前次未执行证明；约21:20本地用户并行配对freeform→全屏片段不作单一因果归因。
- f744四类NOT_SENT修复仅承认helper实际退出、输出完整及精确有效未commit票的原子撤销；commit/撤销同锁，已commit/丢回执/失配/过期/截断/异常仍UNKNOWN。manager仍核对epoch、状态与journal提交，不清f316旧UNKNOWN、不自动重放。代理定向131项（1skip）零失败/错误及Python8/29项通过，后两项当次输出OK但未保存原始日志；独立四类复核21/21，设备效果待验。
- 旧UNKNOWN无core身份时，主负责人先正常停止DSH并确认无自有guest，取得用户**仅本次明确重启许可**后约15:03 UTC真实重启且确认boot变化，再覆盖f744。不能将其变成自动重启或清旧屏障的通用授权。f744最新有限Main/授权/ADB记录不代表MCP完整功能通过；当前日期版已有有限导航/停止换代、OFF跨进程及最终停止证明，未新增最后诊断导出；692局部结果和9ee115.042秒默认proot冷测各属旧SHA。Low190未验设备，未新增长时、全模型或全部ROM通过结论。本地交付以 artifacts/deliveries/build190-fix1.json 的实际生成及摘要核验为准；不发布GitHub。
