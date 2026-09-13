# AGENTS.md

DSHA 重构骨架。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

## 一句话

APK 用 proot/proroot 把完整 Ubuntu rootfs 搬进 app 私有目录，在里面跑 Node 24 +
pnpm + `@deepseek-ai/dsh`（**0.1.5-rc.2**）的 Web UI（`:3080`）。原生层是纯 Java 17、
Material3、单 Gradle 模块 `:app`。

## 技术约束（围绕这些设计）

- **发布交付目录固定为 `F:\DSHA_RESTART\release`**（用户最新指定，即源码工作区的 release）：高安卓标准版和低安卓兼容版的 APK、对应 `.apk.sha256` 都放这里；既有历史文件保留。
- **Java 17，无 Kotlin**，单模块 `:app`。
- `applicationId com.dsh.client`；Java 包 `com.deepseekharness.app`；标准版 `minSdk 30`、兼容版 `minSdk 23`，
  `compileSdk/targetSdk 37`（SDK 平台包 `android-37.0`）、AGP 9.1.1、Gradle 9.3.1、NDK 26、**arm64-v8a only**。
- 离线 rootfs（`assets/offline-rootfs.bin`）**不提交**，CI 生成；本地骨架默认走精简包。
- 新 dsh 依赖由 `tools/dsh-runtime/package-lock.json` 锁定，运行 `tools/prepare-dsh-runtime.py` 生成覆盖层；构建会校验补丁与覆盖层摘要。环境身份为版本码 + Ubuntu 基础环境版本 + dsh 版本；基础版本相同则事务替换受管运行时，个人目录、会话、配置和第三方插件保持原位；基础版本不同才保护数据并重建。不要因普通 dsh 更新递增 Ubuntu 基础环境版本。
- 已删除按启动次数自动备份。不能通过默认值、旧偏好或旧备份重新启用；普通启动不得遍历整个工作区或会话目录做旧链接修复。
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
- 明确的启动配置/插件故障或启动进程退出后自动进入原生 `StartupRecoveryActivity`；等待鉴权本身不是故障。最近五次脱敏启动记录独立保存在私有目录。`startup-checkpoints.py` 仅处理六个固定声明文件，保留三次健康启动与三次修复前快照，不恢复旧的整库自动备份；健康快照须核对启动前捕获的配置与就绪时一致。恢复/新建配置/卸载插件仍通过 `BackupTask` 的停止与维护屏障；固定文件校验、原配置快照和中断写入日志不可省略。
- 安全启动使用 `dsha-recovery-<16位十六进制>` 独立 profile，只加载官方基础组件，保留原 web profile；`WebProcSel` 必须能正确停止此类 Node 进程。启动观察器适配锁定 Cordis 的真实 Entry.init，不拦截全部 Node 模块解析，不把可选依赖探测误报为故障。
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
