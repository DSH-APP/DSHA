# AGENTS.md

DSHA 重构骨架。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

## 一句话

APK 用 proot/proroot 把完整 Ubuntu rootfs 搬进 app 私有目录，在里面跑 Node 24 +
pnpm + `@deepseek-ai/dsh`（**0.1.7-rc.2**）的 Web UI（`:3080`）。原生层是纯 Java 17、
Material3、单 Gradle 模块 `:app`。

## 技术约束（围绕这些设计）

- **当前本地交付版本为 0.1.7-rc2 / 147**：在 rc1 数据保护、Profile 设置恢复、原生插件审阅、长会话和真机修复基础上，接入上游 `dsh-v0.1.7-rc.2`。详见 `docs/releases/v0.1.7-rc2-build147.md`。上游已将 Agent 预设会话头标签设为只读，并接管模型设置入口；不要把 rc1 的自定义标签/模型导航补丁重新套回 rc2。
- **备份新要求（2026-09-17）覆盖旧的禁用自动备份规则**：`AutomaticBackups` 默认开启，独立本机计划支持每日、1–168 小时间隔和停止后。使用既有宿主 v5 加密与预检，后台作业不强停 Web/终端。正常自动副本保留 3 份，手动、未知、部分记录不自动删除；插件依赖提示不能改称完全通过。本机密钥和副本不提供卸载存续保证，界面必须引导导出及保存密码。
- **旧环境清理新要求（2026-09-17）**：覆盖更新在闲时清理可再生缓存及多余健康受管副本；旧运行时的读写不兼容只阻止回退，不能令全部旧组件永久堆积。保留至少两份健康且字节仍符合计划的副本，修改过或含额外文件的原件不删除。新重建通过数据和运行核验后建立 `retired-proof.json`，核对旧树 inode、树摘要、数据归档及映射，再清理 `previous-linux`；无证明的历史重建原件继续保留。
- **设备验收经验**：`uiautomator dump` 会抑制其他无障碍服务，活动配对/持续读屏验收期间使用普通截图和实际本机桥；屏幕授权绑定 DSH generation，停止/断连/手动撤销后失效。截图使用应用私有 Pictures/DSHA，无需所有文件访问。PiP 可能盖住底部控件；自动化必须核对实际可见区域与屏幕方向，不能只按底层 XML 坐标点击。截图/读屏不可将敏感值写入公开取证文件。
- **交付边界**：E7E3 发布证书继续用于正式 APK；A3 仅用于明确的手机验收场景，本轮 rc2 按 E7E3 正式包覆盖验收。本机验证不得扩写成 Android 6/7、16 KiB 或全部外部模型服务矩阵完成。DSH 0.1.7-rc.2、Ubuntu 10；Standard/Low 和虚拟屏能力仍按实际设备证据声明。正式 APK/ELF、两 flavor 完整单测、Release Lint、插件门禁和真机覆盖完成后写入 `release`；网站仍为本地产物，尚未上线。


- **用户最新验收与交付要求（2026-09-14，覆盖下文和历史文档中的旧审计包流程）**：不再生成、安装额外测试/调试/审计 APK；直接使用原包名 `com.dsh.client`、历史同签名、非调试正式 APK 覆盖安装，在实际应用中做非破坏性操作检查。已获得覆盖安装授权，不反复询问。保留用户对话、配置、插件与文件，不在正式数据上运行强杀、删 Bash、坏插件等故障注入。确认对应正式版本正常后，替换 `release` 里同版本同 flavor 的既有 APK 与 `.apk.sha256`，使用常规文件名；不要再新增 `-buildNNN` 并列副本。不同历史版本继续保留。测试源码/单测与历史报告可保留，历史审计工具仅作参考，不因旧脚本默认值再次生成测试 APK。
- **签名规则（最新验收要求）**：`release` 和手机验收统一沿用 E7E3 历史发布证书（完整指纹见 build 131 记录）；A3F4 仅保留为历史手机验收规则，不得用于本轮正式验收或放入 `release`。覆盖安装前核对包名、版本码、证书和 `debuggable=false`，不得卸载后用错误证书替代。

- **发布交付目录固定为 `F:\DSHA_RESTART\release`**：两版 APK 及 `.apk.sha256` 放这里；同版本验收后覆盖，其他历史版本保留。
- **Java 17，无 Kotlin**，单模块 `:app`。
- `applicationId com.dsh.client`；Java 包 `com.deepseekharness.app`；标准版 `minSdk 30`、兼容版 `minSdk 23`，
  `compileSdk/targetSdk 37`（SDK 平台包 `android-37.0`）、AGP 9.1.1、Gradle 9.3.1、NDK 26、**arm64-v8a only**。
- 离线 rootfs（`assets/offline-rootfs.bin`）**不提交**，CI 生成；本地骨架默认走精简包。
- 新 dsh 依赖由 `tools/dsh-runtime/package-lock.json` 锁定，运行 `tools/prepare-dsh-runtime.py` 生成覆盖层；构建会校验补丁与覆盖层摘要。完整、兼容、最新分别判断：`tools/prepare-runtime-descriptor.py` 生成独立受管资产/启动器/桥接/数据契约描述，APK 版本仅作诊断来源。相同基础版本更新受管树，个人数据保持原位；不因 UI-only APK 更新替换环境，不因普通 dsh 更新递增 Ubuntu 基础环境版本。
- rc2.1 本地交付以 `docs/releases/v0.1.5-rc2.1-build130.md` 的产物摘要为准，专项逐项证据见 `docs/backup-upgrade-acceptance.md`。不得把早期 `app/build` APK 当作交付包，也不能把当前设备验收称为全部历史数据及设备矩阵完成。
- build 131 稳定性修订沿用 `0.1.5-rc2.1` 名称；之前的 `-build131` 文件名和不覆盖 build 130 的方式为历史记录，现按上方用户最新要求使用同版本常规文件名替换。验收记录见 `docs/stability-acceptance.md`；未经另行要求不得上传。
- `HttpProtocol` 在读取期间限制头部实际字节和基于单调时钟的总截止时间；桥的命令查询串预算不得缩到低于既有 8192 字符命令的编码需求。正文/鉴权后的执行与头预算分离。LAN 的 `SocketDispatch` 在登记前限制连接，短请求、长流及反向 pump 独立有界，停止必须关闭本轮所有 socket。
- LAN 正文由方法、状态码和明确的传输边界决定；零长度不是缺失长度，只有真正验证过的 101 WebSocket 握手可切隧道。分块需消费并检查 trailer，正文保持字节流；不得把 hop-by-hop 头移除后继续发送不匹配的编码体。
- 插件依赖使用随包 pnpm 10.34.5 原生锁，首次解析后保留锁及实际内容摘要，复用时冻结安装；所有路径禁用生命周期脚本与 pnpmfile 钩子。确认对象包含实际候选与依赖摘要，不能预览后重新解析最新版本。历史完整副本不重新解析依赖，未知信息如实标记。
- 插件安装的原件与目录切换由 `plugin-transactions.py` 记录，原生 `PluginInstallJournals` 加入既有维护门禁。未提交日志不得被普通启动或其它恢复覆盖；终止进程后按固定目标与前后摘要恢复，已提交日志不回滚后来数据。工作目录中的旧历史和失败候选保留，不由临时目录清理隐式删除。
- 第三方插件启用必须经过实际静态审阅和内容复核，安装后先保持停用。原生确认在停止 Web/终端并取得维护屏障后交给脚本单次确认对象；直接启用入口不能绕过。加载状态由真实启动与网页事件收敛，未确认的新候选不反复自动加载，失败不回退聊天或其它业务数据。
- 签名系统插件与用户插件必须分层：系统插件源码、实体和依赖不进入用户备份，只保存每个 profile 的启停意图，并由当前 APK 的精确受管证明重建；用户插件源码、实际依赖组和包装脚本照常归档。旧 v4 图可读，但其中系统插件字节必须丢弃。`.disabled` 与旧 bundle/依赖/实体冲突时，禁用意图优先，旧实体移出加载路径并保留到系统插件隔离区，不能静默覆盖或重新加载。
- DeepSeek Messages 的受管补丁只把用户消息或 `tool-result` 内用于展示的嵌套 `tool-call` 投影为稳定文本；assistant 的真实工具调用、调用 ID、顺序、错误语义与图片结果必须保持协议结构。补丁锁定 `@deepseek-ai/dsh-llm-deepseek` alpha.2 源码锚点，不修改旧会话文件；Agent Team、compact、普通双工具、错误结果和图片结果必须一起回归。
- `CredentialRead` 区分未配置、成功、暂时不可用与需处理状态；调用方不得重新折叠为空字符串。失败不自动清空旧密文或创建替代解密密钥；保存新值须先加密并读回验证，配置页保存其它选项不能清掉不可读凭据。
- `RetainedCatalogue` / `RetainedDataActivity` 仅提供只读清单、逐项检查、导出及预检恢复，不新增自动删除。旧树解析只用所选旧 rootfs 的映射，不借旧链接读取当前数据；未知记录单独保留。隔离插件的原始依赖组不修改，复制候选并重新检查后才可审阅启用；同名冲突保留当前版本。
- 数据格式不能由 APK 或 dsh 版本号推导。`DataFormatEvidence` 对未经完整检查的数据保留 unknown；运行时回退要求读写包与相关持久化配置补丁指纹一致，并继续执行实际试运行。发件人声称的已验证格式不能成为本机兼容或删除原件的依据。
- 新受管更新使用 `ManagedRuntimeTransaction` / `HostDataTransaction`，旧 `RuntimeUpdateTransaction` 仅保留历史日志恢复。真实隔离 `RuntimeTrial` 核验静态资产/原生模块、实际端口/鉴权、存储和会话写后重开、网页内核与后端握手及 guest 退出后才能提交。回执保存在 rootfs 外，并绑定受管文件摘要。至少保留直接健康前代；只在维护屏障内按已验证策略清理更旧版本，未知或受损原件不自动删除。
- 旧运行时自动轮换必须重新核对 plan 中的逐根摘要与目录成员，不能仅凭缓存健康回执计数或删除。修改过、不可读或含清单外文件的副本原位保留，也不能将其计入“仍有两份健康前代”的数量。
- `RuntimeTrial` 必须记录实际 `runtimeMode`。所选 proroot 在鉴权前明确退出、没有插件故障且 guest 已核验退出时，可按 `WebRuntimeFallback` 仅重试一次 proot；保留脱敏失败输出与退出码，回执标明 `fallbackFrom`，不能把 proot 成功报告成 proroot 原生成功。静态检查继续先选 proot，慢启动不触发重试。
- `RuntimeTrialRecords` 只轮换已确认关闭的现场：保留最近 3 条成功和 5 条失败，未关闭或标记异常的目录不得自动删除并继续要求恢复。不能再以累计 32 次为永久上限；连续 33 次成功/失败交错试运行必须仍可开始下一次。
- 冷解压和受管候选必须共用内置插件依赖链接与局域网设置补丁准备，先完整准备再计算摘要和切换。不能依赖注册用户 profile 或后续第一次 Web 启动补齐候选。
- Android 的 `FileInputStream(FileDescriptor)` / `FileOutputStream(FileDescriptor)` 不接管传入描述符；宿主备份通过 `ParcelFileDescriptor` 自动关闭流明确转移所有权。真机验收必须使用非调试构建并检查大量文件读写后的描述符数量。
- 兼容运行时回退仍使用当前 APK 的启动器，在隔离 profile 下重新试运行；不恢复旧对话快照。前代必须可读取当前运行时写入的数据格式，且其受管清单覆盖直接前代关系。未确认进程退出时保留运行任务锁和所有现场。
- 宿主归档 v5 使用 `DSHA-data-v5-<UUID>.dshbak`，密码默认必需、原生 API Key 默认排除；先完整 AEAD 认证再预检，只写私有编号槽位。范围、逻辑根和实际记录双向核验。本机状态不能从用户归档生效；受管脚本、可执行配置和未知插件先隔离。依赖版本/JAR 摘要由 `tools/backup-dependencies.lock.json` 与 `verifyBackupDependencies` 核验。
- 私有验证副本通过 `VerifiedBackupCopy` 重新核对加密文件摘要后，可由 `NativeBackupJobs.reexport` 复制到新目标；沿用原密码和真实范围，不提升 PARTIAL/BEST_EFFORT 或插件警告等级。坏记录保留并单独报告，不能挡住其他可读副本。
- SAF 查询、打开和流读写共用 `DocumentStreams`；取消不能在主线程等待远端 Provider。读取必须保留 AssetFileDescriptor 的 offset/length，不将部分区间误读成整个底层文件。
- 启动观察器沿用锁定 `dsh-app-boot.resolveBundleDir` 的安装/配置两个 anchor 与 Node 查找顺序，不能只查固定四目录误报全局插件丢失。`persona-compat-patch.json` 仅兼容已知旧 text 字段，保留用户提示词和现有 prefix，不能以默认空内容掩盖无效配置。
- 维护成功文案不能代替就绪检查；未就绪时留在原生页，不自动跳回循环。维护页的忙状态不使用插件查询/终端等通用锁冒充数据维护。终端出生身份不变；exec 窗口内无效 stat 必须按内核会话号复核，不能直接当作已退出。
- 配置重置由 `NativeConfigurationReset` 与原 `HostDataTransaction` 执行，只切换已确认的 settings.yaml 和私有工作目录 .env；使用检查后的原生凭据。重置日志仍归 `host-backup-operations`，统一恢复入口按专用记录识别；不再先运行旧 Python 备份，也不接受外部/链接目标作为可安全覆盖的私有配置。
- 完整格式化对会话、插件、配置、API Key、运行时和其它核心私有根保持严格删除与复核；仅 WebView、`cache`、`code_cache` 等 Android/渲染进程可重新创建的目录可把持续 `ENOTEMPTY`/`FILESYSTEM_39` 降为明确警告。取消必须继续抛出，核心根的相同错误不得借可再生缓存规则降级；完成后仍回欢迎页重新开始。
- 同基础版本的旧 dsh 缺失或被修改仍走受管更新，不因 package.json 检查失败转成 Ubuntu 重建。覆盖更新不归档系统；确需重建时只保护对话、附件、配置、插件与个人项目，旧系统目录仅通过同盘改名暂存作回切。
- 就绪检查必须解析实际 `/bin/bash` 的 ELF 和加载器路径，不能以 `/usr/bin/bash` 或旧标记存在代替；可执行位使用 lstat，不能用受 Android W^X 影响的 canExecute。损坏系统不得反复执行配置安全启动，应进入环境修复。
- 尚未迁完的旧重建链在停止屏障内使用从已签名 APK 提取的独立 Bash/glibc/Python；它不是宿主救援核心，也不能作为新宿主归档的隐式回退。继续按专项要求将基础重建的数据保护接到宿主核心，过渡期间保持原逐文件核验与回切保护。未完成安装且无法确认旧数据位置时不能宣称全量对话备份。
- 附件模块与文件/会话模块共用 dsha-runtime-fs 原子发布：附件别名必须保留源对象并保留 EEXIST 语义；老内核仅在协作发布者之间使用加锁降级。验证真实图片、文件、流式写入、同摘要别名、冲突和取消，并覆盖私有目录与 attachments 软链到 Android FUSE 的路径。
- 旧的按启动次数自动备份保持删除。按上方用户新要求使用独立 v5 自动备份计划，不复活历史计数偏好；普通启动不得遍历整个工作区或会话目录做旧链接修复。
- APK 使用 `offline-rootfs.layout=split-runtime-v1` 标记 Ubuntu 与 `dsh-runtime.bin` 分包；冷安装两份都解压，局部更新仅读 dsh 包。两份内容各保留一份，不改基础环境身份，构建核验两份摘要及运行时完整性。
- 数据维护先关闭 PTY 与简易终端，再取得 `RuntimeTasks` 屏障；proroot 终端按本次独立会话核验并回收 guest，不能只结束启动器就释放工作锁。无法确认时保留原环境。
- PTY 出生身份在原生 fork/exec 握手中登记：子进程自读 stat，父进程确认后才 exec。不得退回 Java 启动后单次读取，或停止时按裸 PID 临时认领；终端维护必须补测非调试 APK，调试版不能覆盖发布版的进程信息访问时序。
- `client-combo-patch.json` 与 `client-combo-cache` 适配锁定的网页拼接器；只复用上一轮未变化的脚本/映射，HMR、插件移除和顺序变化必须失效，保留上游脚本、源码映射、URL 与修订号语义。
- 网页 ES 兼容依赖锁在 `tools/web-compat/`，运行 `node tools/prepare-web-compat.mjs` 生成随包脚本、许可和输入摘要；构建核验摘要。官方 PDF 模块与独立 Worker 都需要兼容代码，旧 Chromium 的非特殊 URL 文件协议需单独适配；不能只在桌面新浏览器或预热环境验证。
- Agent 预设切换必须调用锁定 Host 的 `agentPresets.select` 并核对返回值。已有内容的会话保留，通过同工作区新会话应用所选预设；禁止只改标签伪装切换成功。
- 预设按钮自身提供基础样式，不能仅依赖窄屏移动插件消除浏览器默认边框。触屏宽屏的预设与文件入口靠右，文件入口调用真实 `sidebarRight.openTab('files')`；验证窄屏/平板横屏及菜单、文件面板行为，保留键盘焦点提示。
- 中英文界面默认跟随系统：偏好值为 `system`（默认）/ `zh` / `en`，系统语言是中文（或无法识别）落中文，其余落英文（英语是唯一可用的国际语言，比中文更可能被看懂）；旧版本只写过 zh/en，老用户升级后保持原语言。**系统语言必须在进程最早时刻锁存**（`SystemLanguage.initialize()`，早于任何 `Locale.setDefault`），否则 `Resources.getSystem()` 会被界面语言污染，「跟随系统」切一次就自我锁死。文案目录为 `tools/i18n/messages.json`，构建生成 Java 文案字典；布局使用对应中文/英文资源。语言切换只重建界面，不能停止终端或 Web；用户输入、聊天、文件名及命令原文不做自动替换。系统 WebView 与 Gecko 通过同源桥同步到实际 locale 服务。
- 应用状态在显示边界通过 `UiStateText` 重新渲染，不能缓存某种语言后一直显示；动态文案只匹配目录中声明的完整模板，参数与第三方插件描述保持原文。应用弹窗统一使用 `DshaDialogBuilder`，自定义内容必须能在短屏和大字体下滚动。
- DNS 默认 `auto`：Node 双栈 `lookup` 仅在 `EAI_AGAIN` / `EAI_FAIL` 时重试一次 IPv4，不重放 HTTP 请求，不降级显式 IPv6。配置页另有 `ipv4`（glibc `no-aaaa`）与 `native`；只增删受管解析选项，保留用户 nameserver/search/options。Web、插件、普通 shell 与 PTY 共用 `RuntimeTools` 的预加载环境，Ubuntu 基础环境版本不因该修复变化。
- 原生按钮和选项统一居中与字体边距；普通卡片、主按钮及文字状态共用主题资源，不能再用独立渐变或硬编码颜色制造同类框色差。布局修订运行 `LayoutAuditInstrumentation` 的 `style` 中英文验收，覆盖日夜、短屏和 1.3 倍字体；滚动内容按标题与底部按钮的实际高度分配，不写死英文标题所需高度。
- PTY 与简易终端使用独立标签和会话，跨切页、旋转及语言切换保留进程与各自状态。关闭单个标签必须核验该会话退出后再移除；失败保留标签和工作锁。维护入口仍要关闭全部终端并取得原有屏障，禁止把标签移除当作进程已退出。
- 终端会话 ID 永久递增，显示编号独立复用最小空缺；全部关闭后新建显示 1。关闭中或关闭失败仍占用编号，过期回调不能按显示编号关闭新会话。
- Web 启动等待鉴权不设强制终止时限，60 秒仅提示；保持 `isStarting`，避免看门狗把慢启动当故障。停止与维护必须共用 `WebProcessManager` 的 PID 身份/进程核验，不能重复使用裸 `kill(pid, 0)` 阻塞过期编号。
- Web 首选端口冲突时保留用户配置，先尝试本机成功备用端口，否则由锁定 dsh 的 `--port 0` 分配。动态鉴权只接收本轮官方启动行；保活、鉴权和 LAN 必须使用实际 Web 端口，不能继续探测已被占用的首选端口，也不能按端口猜 PID 终止进程。
- 旧 WebView 的 AbortSignal.any/timeout 与随机 UUID 补齐沿用 1.1.10 的提前注入思路：共同兼容脚本须进入受管 HTML 的应用脚本之前，并覆盖文档起始与 Worker；不能只在加载结束后隐藏能力错误。保留原生实现、取消原因与监听清理，验证接口缺失时的真实页面及重载。
- 启动诊断通过 `StartupDiagnostics` / `StartupTrace` 保存阶段、实际插件错误和退出码；失败输出另存，后续启动不可覆盖。兼容重试仅在 proroot 已确认退出、鉴权前且没有明确插件故障时进行一次，不能由超时触发。
- 启动日志区分应用事件与原始进程输出：应用事件在显示时按语言渲染，不能全文替换命令、插件异常或用户内容。维护横幅只读取数据维护状态，不能把启动、插件查询或终端使用的通用执行锁当成维护。
- 明确的启动配置/插件故障或启动进程退出后自动进入原生 `StartupRecoveryActivity`；等待鉴权本身不是故障。最近五次脱敏启动记录独立保存在私有目录。六文件检查点现由宿主 `ConfigurationSnapshots` 执行，原生读取旧 `startup-checkpoints.py` v1 数据；正文逐文件存于 rootfs 外，保留三次健康与三次修复前快照。健康快照须匹配启动前捕获的内容及本轮启动 ID。配置恢复/新建仍通过 `BackupTask` 停止屏障；旧日志已提交后不得覆盖后来修改的配置。缺日志的旧状态标记只保留并解除，不猜测要回滚哪份数据。
- 原生插件归档记录 package、profile 与 shared 的实际绑定，包含未声明但确实存在的依赖。`PluginRestoreGraph` 在同一隔离目录重建相对依赖链接及命令入口，保留改写前声明，不运行安装钩子。缺少可验证的当前受管依赖时，保留源码并报告关系未完整，不能伪称插件已启用或可运行。
- 安全启动使用宿主 `NativeSafeProfile` 创建 `dsha-recovery-<16位十六进制>` 独立 profile，不依赖 Python；只加载官方基础组件并保留原 web profile。`WebProcSel` 必须能正确停止此类 Node 进程。启动观察器适配锁定 Cordis 的真实 Entry.init，不拦截全部 Node 模块解析，不把可选依赖探测误报为故障。
- 个人文件迁移、安全配置与配置快照的 Python 维护脚本和插件管理一样，预先选择 proot 执行；目录存在探针不能代表完整遍历/归档可用。不能在写入结果未知后自动改通道重放。Web 本身继续使用所选运行方式。
- 离线 curl/git/证书及依赖由 `tools/ubuntu-tools/packages.lock.json` 锁定，运行 `tools/prepare-ubuntu-tools.py` 生成 `ubuntu-tools.bin`；新环境通过 dpkg 离线安装后删除安装包。生成文件不提交，不省略冷环境的完整安装检查。
- `standard` / `low` 两个 flavor 共用功能代码与 Ubuntu Python。标准版使用系统 WebView，兼容版额外带 Gecko 143，在 Android 6/7 或旧 WebView 时使用；构建任务为 `assembleStandardRelease` / `assembleLowRelease`。
- Shizuku 必须注册 `rikka.shizuku.ShizukuProvider`，由 Application 监听 Binder，不依赖 ADB 开关。标准版 API/provider 为 13.1.5；兼容版为支持 API 23 的 12.2.0，不用 overrideLibrary 掩盖新版库的 minSdk 24。Root、Shizuku、ADB 在发送前选择通道；结果未知时不能自动切换通道重放。
- Shizuku 管理器按 API_V23 权限所属包识别，用户点击时可通过标准 REQUEST_BINDER 入口恢复连接；回调严格核对管理器 UID、Binder 描述符、单次请求与超时，再交给本应用受保护的 Provider。不得放松 Provider 权限、代替管理器授予权限或执行回调附带的 APK 路径。
- WebView 与 Gecko 的文件选择结果统一通过 WebUploads 解析 ClipData，多选优先、单选回退，保持顺序并去重；只接收 content URI，复制与数量/大小限制保持。回归需验证真实 Activity 文件回调返回两份文件及其字节。
- 设备通道与权限集中在「设备能力授权」。启用且经管理器授权的 Root 直接走 su，同一设备命令策略在特权进程内再次验证；无须先配对 ADB。
- 兼容版 proot / loader 从 `src/low/jniLibs` 选择 API 23 构建；重编脚本 `tools/build-low-proot.py`。终端 JNI 同样以 API 23 构建，并保留 16 KB 对齐。不要把标准版 proot 当作 Android 6 可执行文件。
- 构建通过 `tools/prepare-standard-assets.py` 生成 `app/build/generated/standardAssets`，需要 Python 3.9+（可用 `DSHA_PYTHON` 指定）。不直接修改原始 rootfs；仅重新压缩和清理预装缓存时不要递增环境版本，避免触发旧用户清空重装。
- `RuntimeTools` 负责随包 CA、npm/npx 与 dsha-plugin 入口；插件、普通 shell、PTY 必须共用其环境，不能依赖用户先跑 apt 才有证书。终端 JNI 保持 max-page-size=16384 / common-page-size=4096，并核验 RELRO 在 4 KB 与 16 KB 页映射内。
- `NARB_DISABLE_NATIVE_CACHE=1` 让原生扩展直接从随包目录加载；其默认 link+unlink 缓存会在 `--link2symlink` 下首次悬空。冷安装必须验证 Cordis 内部加载器第一次即可初始化，不能只检查 .node 文件或预热后复测。

## 分层与归属（改代码前先看这里）

| 层 | 类 | 职责 |
|---|---|---|
| `ui/` | MainActivity / WelcomeActivity / Launch·Settings·TerminalFragment | 界面与启动门禁 |
| `core/` | HarnessController | 编排：把启动/停止 dsh 组合起来，**不再**装 8 类活 |
| `core/` | ConfigStore | 配置唯一读写入口（端口/模型/workdir/API key） |
| `runtime/` | ProotBootstrap | proot 命令组装与执行 |
| `runtime/` | ContainerRuntime | 运行时选择 + BINDS 挂载清单 |
| `runtime/` | WebProcessManager | 停止 Web（写哨兵 + 按 pid 文件杀） |
| `data/` | KeyVault | Keystore AES/GCM 加密 API key |
| `bridge/` | AppBridge | 3090 桥接缝（契约，待实现） |
| `util/` | Constants / ShellQuote / Query / WebProcSel / BackupScope | 纯逻辑，无 Android 依赖，**必须配单测** |

## 启动契约（不可破坏）

- `welcomed == false` → `WelcomeActivity` → 点开始 → `MainActivity`。
- `MainActivity` 进入前校验 `welcomed`，否则永远回 Welcome。
- 正常运行仍检查 `.offline-extracted` 与完整环境身份。维护页允许用户进入受限主界面查看配置和日志；此入口不能伪造就绪标记，终端、Web 与插件执行仍须通过环境门禁。
- 个人文件迁移读取独立映射下的实际 rootfs，不能遍历 guest 的外部挂载；宿主绝对路径镜像中的 `.l2s` 挂载占位要在遍历前精确排除，不能粗略跳过整个 `/data` 或忽略真实个人文件的权限错误。旧 L2S 文件链接按 guest 根与宿主别名映射读取内容，拒绝循环、越界和外部挂载。写归档时同步计算源文件摘要，保留归档与恢复后逐文件核验，并向原生界面持续反馈进度；重新打开维护页仍显示上次失败原因。
- 短信查询属于独立敏感能力，只允许当前 Android 用户的严格 `content query`。原生预授权默认关闭、可撤销，不从系统备份恢复；不能借此放开内容提供者写入或系统危险命令。
- DocumentsProvider 保留 `root` 和 `linux/ubuntu/...` 的既有不透明文档 ID；树授权依据逻辑子路径与实际解析路径共同核验，不靠字符串前缀改命名空间。支持创建、读写、重命名和删除；guest 绝对软链接按 rootfs 解析，删除链接不递归目标。写文件先验证已打开的文件描述符，再截断；描述符关闭前保留运行任务锁。

## 安全网：纯逻辑必须能单测

`util/` 下的类**不得** import Android API。任何新增纯逻辑都要在
`app/src/test/java/com/deepseekharness/app/util/` 下配 JUnit 断言，跑：

```bash
./build.sh :app:testStandardDebugUnitTest
```

当前 4 个测试类锁定的不变式（重构时绝不能改坏）：

- `ShellQuote`：POSIX 单引号转义，恶意值不能逃逸。
- `Query`：逐参数名匹配（`indexOf(key+"=")` 会被后缀劫持，已修）；「参数为空」≠「参数不存在」。
- `BackupScope`：部分备份绝不叫 `DSHA-backup-*`（否则老版本当全量恢复会清掉配置与插件）；
  `dshPaths` 与 `mergeSubdirs` 一一对应。
- `WebProcSel`：认得出 dsh 进程、**绝不误杀 proot/proroot**（杀到容器启动器 = 环境连 App 一起带走）。

## 已知 trap（搬自原版，骨架已按此设计）

- **停止靠 pid 文件，不靠端口反查**：`/proc/net/tcp` 非 root 读不到（静默空），`/proc` 有 hidepid。
  启动时 `echo $$ > /root/.dsha-web.pid` 再 `exec node`（exec 不换 pid）。
- **停止先写哨兵 `/root/.dsha-stopped`**：看门狗/重启脚本见到就退出，否则「秒复活」。
- **app 私有目录禁 `link(2)`**（SELinux），proot 必须带 `--link2symlink`。
- `PROOT_L2S_DIR` 在 rootfs 内的 `.l2s`，必须把该目录绑定到相同的宿主绝对路径；否则 dpkg 安装时对硬链接执行 chown/stat 会报文件不存在。
- **两把签名钥匙各管一件事**：线上 APK 用 debug keystore（历史原因），增量更新清单用
  `DSHA-release.keystore`，绝不混用。

## 回填清单（按 seam，一次一个）

1. ~~`ProotBootstrap`：libprootloader 加载细节 + 离线 rootfs 解压~~ ✅ 已接回（真实 proot 契约 + 离线包解压 + dsh 启动）。
2. ~~Web 内嵌预览~~ ✅ 标准版使用系统 WebView，含异步鉴权、文件选择、错误恢复；`HarnessService` 与看门狗已接回。
3. `bridge/HttpShellService`：实现 `AppBridge`，3090 桥 token 门控 + 单飞守卫。
4. 安装六步（rootfs→tools→node→pnpm→harness→guard）→ 独立 `InstallPipeline` 协作者。
5. `BackupManager` + `restore-merge.py`（资产已在 `assets/`）。
6. ADB/Shizuku、LAN 桥、悬浮条、终端 PTY、插件市场。

### dsh 启动契约（已实现，勿破坏）

- 入口：`exec dsh web --no-open --host 127.0.0.1 --port 3080`（`dsh` 在容器 PATH 的 `/usr/local/bin`）。
- env：`DSH_HOME=/root/.dsh`、`DEEPSEEK_API_KEY`（非空才 export）、`DSH_PERMISSION_MODE`、`DSH_CONFIRM=1`、`BROWSER=true`、`cd /root`。
- 写 pid 文件要在 `exec` 之前（`exec` 不换 pid）。
- proot 二进制从 `nativeLibraryDir/libproot.so` 执行（**不能放 filesDir**，Android 10+ W^X）；依赖 `libprootloader.so`/`libtalloc.so` 靠 `PROOT_LOADER`/`LD_LIBRARY_PATH` 引导。

## 编码约定

- 注释与 UI 串用中文；提交信息用中文 + `type:` 前缀说明原因。
- 每个协作者单一职责；纯逻辑抽到 `util/` 并配测试；不改历史 SharedPreferences 键名。
- 匹配现有风格：try/catch 包住有风险操作、优雅降级、失败 toast 给用户。
