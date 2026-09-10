# AGENTS.md

DSHA 重构骨架。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

## 一句话

APK 用 proot/proroot 把完整 Ubuntu rootfs 搬进 app 私有目录，在里面跑 Node 24 +
pnpm + `@deepseek-ai/dsh`（**0.1.5-rc.1**）的 Web UI（`:3080`）。原生层是纯 Java 17、
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
- `client-combo-patch.json` 与 `client-combo-cache` 适配锁定的网页拼接器；只复用上一轮未变化的脚本/映射，HMR、插件移除和顺序变化必须失效，保留上游脚本、源码映射、URL 与修订号语义。
- Web 启动等待鉴权不设强制终止时限，60 秒仅提示；保持 `isStarting`，避免看门狗把慢启动当故障。停止与维护必须共用 `WebProcessManager` 的 PID 身份/进程核验，不能重复使用裸 `kill(pid, 0)` 阻塞过期编号。
- 启动诊断通过 `StartupDiagnostics` / `StartupTrace` 保存阶段、实际插件错误和退出码；失败输出另存，后续启动不可覆盖。兼容重试仅在 proroot 已确认退出、鉴权前且没有明确插件故障时进行一次，不能由超时触发。
- 安全启动使用 `dsha-recovery-<16位十六进制>` 独立 profile，只加载官方基础组件，保留原 web profile；`WebProcSel` 必须能正确停止此类 Node 进程。启动观察器适配锁定 Cordis 的真实 Entry.init，不拦截全部 Node 模块解析，不把可选依赖探测误报为故障。
- 离线 curl/git/证书及依赖由 `tools/ubuntu-tools/packages.lock.json` 锁定，运行 `tools/prepare-ubuntu-tools.py` 生成 `ubuntu-tools.bin`；新环境通过 dpkg 离线安装后删除安装包。生成文件不提交，不省略冷环境的完整安装检查。
- `standard` / `low` 两个 flavor 共用功能代码与 Ubuntu Python。标准版使用系统 WebView，兼容版额外带 Gecko 143，在 Android 6/7 或旧 WebView 时使用；构建任务为 `assembleStandardRelease` / `assembleLowRelease`。
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
- 完整版的「解压门禁」（`ExtractActivity`、`.offline-extracted` 标记）待回填，
  契约沿用原版：进主 UI 只看 `.offline-extracted`。

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
