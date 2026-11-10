# 终端内置 dsh CLI：三种落地方案决策备忘录

日期：2026-10-30 · 对象：build213 工作树（DSHA `0.2.1-rc.3` / 内置 DSH `0.2.1-rc.5`，升级进行中）
性质：技术决策备忘录，不是代码实现，也不代表本轮已改产品代码。

## 0. 结论（给决策者）

1. **先做 B：在终端页加一个“DSH CLI”入口，用现有 PTY 打开一个带固定 guest command 的会话；不新增会话类型。**
2. **不做 A：第三种 Kind 不带来任何新的进程生命周期能力，却要复制 PTY 已有的出生身份、保护票、关闭核验和维护屏障。**
3. **C 值得做，但不是第一增量；而且不必先走“受管资产”路线。** 第二步用 `RuntimeTools.preparePackageCommands` 已有的生成式 wrapper 先例，写一个 `/root/dsh-bin/dsha` 轻量命令（同 `dsh`/`npm`/`pnpm`），就能同时服务终端、脚本和 agent；`managed-runtime-inputs.json` / `runtime-descriptor.json` 的资产契约留到确实需要携带大脚本或桥接子命令时。
4. **必须先纠正一个前提：内置 DSH rc.5 没有交互式 TUI。** 上游 README 的 `dsh --profile tui` 只是“假设装了 tui profile”的示例（`app/build/locked-dsh-runtime/node_modules/@deepseek-ai/dsh/README.md:28`）；实际随包模板只有 `acp / web / headless / sdk / sdk-minimal`（`app/build/locked-dsh-runtime/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:551-557`）。实测在隔离 `DSH_HOME` 下 `dsh tui --help` 退出码 1，报 profile 不存在。因此 A/B/C 三种方案都**不能**提供“进入 dsh 的交互界面”，只能提供启动器、shell 和一次性 `headless`。
5. **另一个前提：`dsh` 已经在每个 PTY 的 PATH 上。** `RuntimeTools.preparePackageCommands` 写 `/root/dsh-bin/dsh`（`app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java:132-138`），`RuntimeLauncher` 把 `/root/dsh-bin` 放在 PATH 首位（`app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java:75-79`），终端启动前会准备这些命令（`app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java:33`）。所以 B 的增量不是“安装 CLI”，而是“让已有 CLI 可发现、可预期、有护栏”。

一句话：**先 B（PTY 固定预设 + 顶栏/新建选择器），关闭/保护/维护的算法与路径不改（只给 argv 加一个 command 参数）；C 第二步做生成式 `dsha` wrapper；A 除非产品明确要求“CLI 会话独立成第三种类型”，否则不做。**

## 1. 事实核查

### 1.1 终端页面现状与接入点

- 宿主是 [TerminalActivity](../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java)，同 task、非导出；页面只持有视图，进程属于进程级 owner（`docs/terminal.md:5-7`、`app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java:40-49`）。
- 工作台顶栏在 `TerminalActivity.java:163-200` 创建：返回、标题、显示设置、说明、会话菜单；`applyPresentation` 按工作台/极简与宽度重投影可见性（`TerminalActivity.java:798-826`）。
- 会话侧栏在 `TerminalActivity.buildSessionsPanel` 构造：新建按钮、会话列表、底部动作网格（`TerminalActivity.java:273-556`）；底部动作 wide/dense 两套布局分别有 6 个动作（`TerminalActivity.java:374-456`、`458-540`），列数由 `TerminalWorkspacePolicy.footerColumns` 决定（`app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java:91-103`）。
- “新建会话”入口是 `showCreateChooser`，目前只有 PTY / 简易两项（`TerminalActivity.java:1425-1442`）；创建流程是 `createInMode` → `replacePane` → `pane().newSession()`（`TerminalActivity.java:1949-2008`）。
- PTY 内容面板是 [PtyTerminalFragment](../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java)，新建时申请运行保护票并调用 `OWNER.createPty`（`PtyTerminalFragment.java:129-197`）；简易面板是 [TerminalFragment](../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java)（`TerminalFragment.java:168-230`）。
- `TerminalPane` 是宿主与内容面板的窄接口，目前是二值模式：`isPty()` 与 `Host.switchTerminalMode(boolean)`（`app/src/main/java/com/deepseekharness/app/ui/TerminalPane.java:4-31`）。`createPane(boolean pty, boolean skipInitial)` 同时是 Robolectric 测试的替换点（`TerminalActivity.java:2002-2008`，`app/src/test/java/com/deepseekharness/app/ui/TerminalActivityLifecycleTest.java:26-33`）。

### 1.2 关闭、保护与维护的不变式（这是选型的核心）

- 会话身份由进程级 [TerminalSessionOwner](../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) 持有：PTY 和简易各一组 `TerminalTabs`（`TerminalSessionOwner.java:18-35`）；创建必须消费精确保护票（`TerminalSessionOwner.java:162-241`）；关闭必须在 owner 的串行队列里核验退出后才移除标签（`TerminalSessionOwner.java:352-427`）。
- 运行保护由 [TerminalRuntimeProtection](../../app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java) 提供：`Kind` 只有 `PTY`/`SIMPLE`（`TerminalRuntimeProtection.java:18-21`），`beginCreate` 在锁内核对精确对象、ownerEpoch、Kind 和服务身份（`TerminalRuntimeProtection.java:200-218`）；服务丢失只降级为 `LOST`，不删除会话、不自动结束进程（`TerminalRuntimeProtection.java:306-321`）。
- 创建票由 [TerminalRuntimeService](../../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java) 在可见 Activity 主线程申请；只有真实 `startForeground` 成功、服务身份当前有效时才 `promoted`，服务销毁只把票标为 `LOST`（`TerminalRuntimeService.java:36-87`、`96-118`；`TerminalRuntimeProtection.java:169-197`、`307-321`）。A 的第三种 Kind 会复用同一个服务，但关闭/全量关闭仍必须在 owner 内新增分支，不能靠服务层兜底。
- PTY 的出生身份在原生 fork/exec 握手中登记，子进程自读 stat、父进程确认后才 exec（`app/src/main/java/com/deepseekharness/app/PtySession.java:122-183`；`AGENTS.md:150`）。终端关闭/维护使用保存的进程身份与会话关系核验退出，不按裸 PID/端口/名称强杀（`app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java:16-43`、`118-136`；`docs/runtime-engines.md:147`）。
- **维护流程会把“关闭全部终端”当作环境切换的前置屏障**：`MaintenanceCoordinator` 调 `TerminalSessionOwner.closeAllAndConfirm(5000)`（`app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java:131`），后者只关闭 PTY 与简易两组（`TerminalSessionOwner.java:458-477`）；`AGENTS.md:182` 明确要求“数据维护先关闭 PTY 与简易终端再取 RuntimeTasks 屏障”。新增第三种会话类型时，任何一条关闭/计数/维护路径漏掉它，都会把风险从 UI 缺陷升级为环境安全不变式缺陷。
- 标签编号、关闭中/未确认状态、显示编号复用由 [TerminalTabs](../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) 统一实现（`TerminalTabs.java:8-13`、`123-179`）；PTY 与简易共用同一套语义，但不共享关闭实现（PTY 走 `finishAndWait`，简易走 `disposeAndWait`）。
- 维护成功文案不能替代就绪检查；未就绪时留在原生页（`AGENTS.md:183`）。这条与 CLI 入口无关，但说明“CLI 入口不得绕过环境门禁”。

### 1.3 内置 dsh CLI 的真实能力（rc.5 实测）

在仓库已生成的 rc.5 运行时 `app/build/locked-dsh-runtime/` 上，用隔离的 `DSH_HOME=/tmp/dsha-probe-*` 运行 `lib/bin.js`（不启动 Web、不读/写用户 DSH_HOME、不调用模型；临时目录已删除）：

| 命令 | 实测结果 | 对“CLI 环境”的意义 |
|---|---|---|
| `dsh --version` | 输出 `0.2.1-rc.5`，退出 0 | 可做入口预检 |
| `dsh --help` | 启动器帮助，退出 0（`.../dsh/lib/bin.js:39` 的 `HELP_EXAMPLES`） | 可交互查看；不是 agent 界面 |
| `dsh headless --help` | 一次性任务帮助，退出 0；首次使用会自动在 `$DSH_HOME/profiles/headless` 初始化模板（写用户数据） | 最接近“命令行 agent”的真实能力，但**每次只跑一个任务**；入口 preset 不自动执行它 |
| `dsh web --help` | Web 应用的 flags（`--host/--port/--no-open/--public-url/...`），退出 0 | `--help` 只打印；若真跑 `dsh web` 会启动第二个 Web，不应做（见 1.3.3） |
| `dsh tui --help` | **退出 1**：`profile "tui" does not exist; create it with 'dsh plugin --profile tui add <package>'` | rc.5 没有交互式 TUI；tui 只是示例名 |
| `dsh` 无参数 | 按语法必须给 profile（`.../dsh/lib/bin.js:101-108` 报 `--profile <name> is required`） | 不能把 `dsh` 当成“打开 REPL” |

rc.5 的随包 profile 模板只有 `acp`、`web`、`headless`、`sdk`、`sdk-minimal`（`.../dsh-app-boot/lib/index.js:551-557`）；`headless` 是“一次任务、打印最终答案、退出，无交互追问”（`.../dsh-headless/README.md:12`），`acp`/`sdk` 是给自动化客户端的 stdio 协议，不是人用的 REPL。**结论：不存在“进入 dsh 交互 CLI”这件事；三种方案都只能做到 shell + 启动器/一次性任务。**

复现（不启动 Web、不读用户 DSH_HOME；临时目录用完即删）：

```bash
R=app/build/locked-dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js
T=$(mktemp -d)
DSH_HOME=$T node "$R" --version
DSH_HOME=$T node "$R" --help | head
DSH_HOME=$T node "$R" headless --help | head
DSH_HOME=$T node "$R" tui --help; echo "tui exit=$?"
rm -rf "$T"
```

#### 1.3.1 环境：PATH、DSH_HOME、PROFILE、workdir

- **PATH**：PTY 默认命令是 `/bin/bash -c 'stty sane ...; exec /bin/bash -l'`（`TerminalRuntime.java:47-55`），`RuntimeLauncher` 统一设置 `HOME=/root`、`PATH=/root/dsh-bin:/usr/local/sbin:...`、`TMPDIR=/tmp`（`RuntimeLauncher.java:75-79`），并叠加 `RuntimeTools.applyEnvironment` 的证书、DNS、npm 前缀等（`RuntimeTools.java:687-711`）。所以 PTY 里 `dsh` 直接在 PATH 上，不需要用户手动加路径。
- **DSH_HOME**：PTY 不显式设置 `DSH_HOME`；DSH 默认取 `$HOME/.dsh`（`@deepseek-ai/dsh-home-paths` 的默认规则），而 `HOME=/root`，即 `/root/.dsh`。Web 启动命令显式 `export DSH_HOME=/root/.dsh`（`app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java:12`），用户数据布局也把数据绑定到 `/root/.dsh`（`app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java:11-12`、`79`）。因此不设 `DSH_HOME` 也能工作；如果 CLI wrapper 要显式设置，应与 Web 用同一个值，避免两套来源。
- **PROFILE**：没有 `PROFILE` 环境变量；`dsh <name>` 等价于 `dsh --profile <name>`（`.../dsh/lib/bin.js:101-108`），必须显式传 profile。DSHA 自己的 `DSHA_STARTUP_PROFILE` 只是 DSHA 写进 Web 启动命令的 profile 标识，并用于进程识别（`WebLaunchCommand.java:26`、`app/src/main/java/com/deepseekharness/app/util/WebProcSel.java:125`），不是 dsh 的 profile 选择变量。
- **workdir**：终端以 `/root` 为 cwd（`TerminalRuntime.java:47-55`，proot 的 `--cwd=/root`）；`dsh` 以调用目录为默认 workspace root（`.../dsh/README.md:20`）。CLI 里的 `dsh headless` 默认在 `/root` 工作。
- **交互性**：PTY 本身是完整终端，`dsh --help`、`dsh --version`、`dsh plugin`、`dsh --profile headless ...` 都能跑；但没有 TUI/REPL。

#### 1.3.2 headless 的凭据与权限与 Web 不同（这是 B 最大的产品风险）

- headless 默认**不使用** GUI 保存的托管凭据：`.../dsh-headless/README.md:55` 明确“shipped headless profile disables the managed credential store and its authorization and DeepSeek account consumers”；API key 只从启动环境、`<cwd>/.env`、`$DSH_HOME/.env` 取；GUI 里保存的登录默认被忽略。要恢复，需要在 headless profile 的 `cordis.patch.yml` 或 `--patch` overlay 里把 `credentials` / `authorization` / `deepseek-account` 三行启用（`.../dsh-headless/README.md:59`；bundle 里这三行默认 `disabled: true`，`.../dsh-headless/cordis.patch.yml:36-43`）。
- 权限模式也不同：Web 启动命令设置 `DSH_PERMISSION_MODE=danger-full-access`（`WebLaunchCommand.java:14`，`app/src/main/java/com/deepseekharness/app/util/PermissionMode.java:5`）；headless 未设置时默认 `workspace-write`，审批策略为 `ask`（`.../dsh-base/cordis.patch.yml:246`、`262`）。终端里的 `dsh headless` 不继承 Web 的这套 env，实际工具权限和审批行为会与 Web 对话不同；headless 在无人值守场景下遇到 `ask` 会怎样处理，本轮未做任务级验证，必须真机核验后再宣称“可用”。
- 因此：**入口不能一点就自动跑模型任务**。MVP 只打开带 banner 的 shell，让用户自己输入；否则用户会在“凭据缺失/权限不同”上先撞墙。

#### 1.3.3 在终端里跑 `dsh web` / `dsh plugin --profile web` 的风险

- `dsh web` 会在同一个 `DSH_HOME` 起第二个 Web 进程；它不受 `WebProcessManager` 的 pid/哨兵/出生身份管理（停止路径见 `docs/runtime-engines.md:141-147`、`app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java:34-47`），维护/停止 Web 不会停它；多个 Web 同时写会话持久化也有竞争风险。
- `dsh plugin --profile web <pnpm>` 会在 Web 正在使用的 profile 上跑包操作、改 `package.json`/`node_modules`；上游虽允许 pnpm 转发（`.../dsh/README.md:18`），但 DSHA 的插件更新有自己的事务/维护语义（`AGENTS.md:139`、`docs/plugins.md:51-54`）。终端 CLI 不应被当成第二条插件安装路径。
- B 的 banner 必须明确警告“不要在这里起 Web / 改 Web profile”；C 的 `dsha` wrapper 可以对 `web` 子命令做拒绝（见第 4 节）。

#### 1.3.4 已有的 CLI 雏形（说明 C 不必从零开始）

- `/root/dsh-bin` 下已有一组生成的命令：`dsh`、`npm`、`npx`、`pnpm`（`RuntimeTools.java:104-138`），以及受管资产安装的 `dsha-plugin`、`adb-shell`、`install-ubuntu-tools`（`app/src/main/assets/managed-runtime-inputs.json:301-312`、`app/src/main/assets/asset-deployment.json:39-52`）。
- `dsha-plugin` 已有文档入口（`docs/plugins.md:51`），`adb-shell` 是设备通道 CLI（`app/src/main/assets/dsha-device-shell.sh`）；`ManagedRuntimeLayout` 把 `dsh/npm/npx/dsha-plugin/adb-shell/install-ubuntu-tools` 列为受管文件（`app/src/main/java/com/deepseekharness/app/util/ManagedRuntimeLayout.java:50-52`）。
- 所以“终端侧单独塞一个 CLI”在本仓库不是从零造 CLI，而是**决定它和终端页、受管运行时、3090 桥各是什么关系**。
- 资产层再补一句：`app/src/main/assets/runtime-fs/` 与 `runtime-python/` 是受管 Node/Python 运行时载荷，不是“安装命令”的入口；`install-basic-tools.sh` / `install-ubuntu-tools.sh` 是补 curl/git/python 的安装脚本，其中 `install-ubuntu-tools` 也作为受管命令装到 `/root/dsh-bin/install-ubuntu-tools`（`app/src/main/assets/managed-runtime-inputs.json:306-310`）。**要新增一个 CLI 命令，改的是 `managed-runtime-inputs.json` 的 `installs`（或像 `dsh`/`npm` 那样在 `RuntimeTools` 里生成），不是 `runtime-fs`。**

#### 1.3.5 3090 桥的真实边界（C 走桥时必须知道）

- 桥默认从 3090 起，但本包（`io.github.sunsetrne.dsha`）的候选顺序是 3091 起（`app/src/main/java/com/deepseekharness/app/util/BridgePortPolicy.java:5-24`）；本次实测 `.bridge_port` 为 `3091`，不是 3090。CLI 若访问桥，必须读 `/root/.dsh/.bridge_port` 并按 `bridge-port.cjs` 的严格规则校验（普通文件、0600、1–5 字节、纯十进制），**不能硬编码 3090**。
- token 由宿主写入 `/root/.dsh/.bridge_headers`（单个 `X-Token` 头），恢复提交后还会轮换（`app/src/main/java/com/deepseekharness/app/HttpShellService.java:789-803`；`docs/bridge-and-files.md:9-17`）。CLI 应每次调用都读头文件、`curl -H @/root/.dsh/.bridge_headers`，不把 token 放进 URL/argv/日志，不缓存、不使用 `-v`/`set -x`。
- 设备命令白名单始终生效（`AGENTS.md:103`，`docs/bridge-and-files.md:32-36`）；`/exec` 的 `[EXECUTION_UNKNOWN]`/126 语义不能被 CLI 包装成“自动重试”。桥不是任意 shell 的替代，终端更不是强沙箱（`AGENTS.md:107`、`docs/terminal.md:63`）。
- 桥接的边际价值有限：agent 侧已有内置插件/桥工具（`dsh-computer-use-android`、`dsh-tool-vscreen` 等），人写脚本也可以直接用 `curl`。因此 **C 的桥接子命令应排在 C-local 之后，且只在有明确消费者时做**。

## 2. 三种方案

### 2.1 A：新增第三种终端会话类型「CLI」

**用户价值**：侧栏出现独立的“CLI”分组/标签，视觉上更清楚；如果将来 CLI 需要与 PTY 不同的关闭策略或生命周期，有地方放。
**但当前 CLI 就是“在 PTY 里跑一条固定命令”**：它的出生身份、保护票、关闭核验、跨页保持、维护屏障与 PTY 完全相同。把它做成第三种 Kind，换来的只是**展示与分组**，没有进程级收益。

改动面（估计 8–12 个 Java/测试文件，700–1100 行）：

| 文件 | 必要改动 |
|---|---|
| `app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java:18-21` | `Kind` 加 `CLI`；所有按 Kind 分派的路径复核 |
| `app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java:18-35、80-90、162-241、352-493` | 第三组 `TerminalTabs`、第三张保护票表、claim/create/select/rename/close/closeAsync/closeAll/requestShutdown 的全套复制或泛化；`dispatchClose` 的 `if/else` 要变成三分支（`TerminalSessionOwner.java:418-421`） |
| `app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java:131` 及其调用链 | 必须让 `closeAllAndConfirm` 包含 CLI；否则维护屏障会在 CLI guest 仍存活时放行，违反 `AGENTS.md:182` |
| `app/src/main/java/com/deepseekharness/app/ui/TerminalPane.java:4-31` | `isPty()`/`switchTerminalMode(boolean)` 变三值；所有 `boolean pty` 参数与实现跟着改 |
| `app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java:1320-1390、1459-1493、1533-1645、1658-1729、1735-1873、1945-2008` | 标签命名/状态、分组头、摘要、`addSessionRow`、`selectSession`、`rename`、`requestCloseSession`、`confirmCloseAll`、`closeAllBestEffort`、`closeAllCounts`、`createInMode`、`replacePane`、`createPane` 全部加第三分支 |
| `app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java`、`TerminalFragment.java` | 三向模式切换与“当前面板”语义；`terminal_skip_initial_create` 之外的初始化路径要复核 |
| 测试 | `TerminalSessionOwnerTest`（12 个用例）加 CLI 创建/关闭/保护丢失/全量关闭；`TerminalActivityLifecycleTest`（26 个 Robolectric 用例）的 `Host.createPane`、`FakePane.isPty()`、模式切换全部要改；`TerminalNavigationTest`、`TerminalFloatingMenuTest` 编译/行为复核 |

风险：

- **关闭语义**：第三组必须复制“关闭中/未确认/核验后移除”的全部状态机；任何一条 `closeAll`/维护路径漏掉 CLI，都是 `AGENTS.md:150/182` 级别的缺陷。
- **保护票**：`beginCreate` 按 Kind 精确匹配（`TerminalRuntimeProtection.java:200-211`）；新增枚举本身容易，但 Java 的 `switch` 语句不会强制穷尽，漏分支会在运行时落到“else 简易”之类的错误路径。
- **进程身份**：不会变差（仍然走 `PtySession` 的出生握手），但也没有变好；新增的是**展示层复制**，不是身份能力。
- **可测试性**：owner 可以单测，但 UI 的三值化会重写 26 个既有 Robolectric 用例的假体；真机还要覆盖“新建第三类→关闭→结束全部→维护”的完整路径。
- **与 DSH 升级/终端抽屉收窄改动的冲突**：最大。`docs/design/terminal-20261025/implementation-guide.md:54` 已经计划把展示状态收敛到 `TerminalPresentationState`/`TerminalWorkspaceController`；现在临时把 `boolean pty` 扩成三值，等于给下一步重构增加合并成本。build213 的 224dp 抽屉收窄（`docs/terminal.md:13-16`、`TerminalWorkspacePolicy.java:55-70`）也要求侧栏行/底栏保持精简，第三组会增加分组头、空态、计数与底栏动作的排布压力。

**判定：不做。** 除非产品明确要求“CLI 会话必须与 PTY 有不同的生命周期/关闭策略”（例如常驻 ACP 客户端、独立通知原因、独立维护语义），否则不值得。

### 2.2 B：终端页加一个 CLI 入口，复用现有 PTY

**定义要收紧**：不是“再开一个普通 PTY”，而是**用现有 PTY 运行一条固定的 guest command**（脚本先打印 banner、预检 `dsh`、再 `exec /bin/bash -l`）。这样 CLI 标签有明确标题和起点，但仍是 PTY 会话，关闭/保护/维护全部原样复用。

**用户价值**：一键从终端进入“DSH CLI 起点”；能看到版本、真实可用命令和护栏；不需要记路径/环境。它把“已有但不可发现”的 `dsh` 变成可发现入口。

改动面（估计 6 个 Java 文件 + 1 个 XML + 3 个测试文件，250–350 行）：

| 文件 | 改动 | 关键约束 |
|---|---|---|
| 新增 `app/src/main/java/com/deepseekharness/app/util/DshaCliPreset.java` | 纯逻辑：生成固定 guest argv `{"/bin/bash","-c",script}`；script = `stty` 前导 + `dsh --version` 预检 + 本地化 banner（用 `ShellQuote.arg` 拼静态行）+ `exec /bin/bash -l`；标题常量 `DSH CLI` | 不把用户输入拼进脚本；banner 不写凭据；不自动跑模型任务 |
| `app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java:379-394` | 新增 `PtyLaunch ptyLaunch(String... guestCommand)`，内部仍走唯一的 `terminal.launch(guestCommand)` | 与默认 `ptyLaunch()` 共用同一 argv/env 构造；不复制环境 |
| `app/src/main/java/com/deepseekharness/app/PtySession.java:107-183` | 新增带 `String[] guestCommand` 的 `start(...)` 重载；默认重载传 null；`proot.ptyLaunch(guestCommand)` 替换唯一调用点（`:143`） | 出生握手、`beforeFork`、保护票、失败清理一行不改 |
| `app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java:162-219` | 新增带 `String[] guestCommand` 的 `createPty(...)` 重载；扩展包内 `PtyStart` 测试缝 | `beginCreate`/`retain`/`finishCreate` 时序不变；仍只允许精确票 |
| `app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java:129-197` | `newSession()` 保持默认；新增 `newSessionWithCommand(String[] guestCommand, String title)`；创建成功后 `OWNER.renamePty(tab.id, title)`；`onResume` 的首次默认创建仍走 `newSession()` | 双入口共享 `creationPending`/`operationGeneration`，不能并发创建 |
| `app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java:163-200、798-826、1425-1442、1949-2008` | 顶栏加一个 48dp CLI 图标按钮（仅工作台显示）；`showCreateChooser` 加第三项；新增 `createCliSession()`，沿用 `createInMode(true)` 的门禁/`replacePane(true,true)`，再调用 `newSessionWithCommand` | 极小模式仍通过侧栏“新建会话”选择；**不加第 7 个 footer 动作**（见下） |
| `app/src/main/res/values/terminal_workspace.xml:23` 附近 | 新增 `terminal_workspace_cli` ID | 只追加，不改旧 ID |
| 测试 | 新增 `DshaCliPresetTest`（纯）；`TerminalRuntimeTest` 加固定 argv 断言；`TerminalSessionOwnerTest` 加“命令传到 `PtyStart` 且保护票不变” | 不启动真实 guest；UI 入口用 Robolectric 只验按钮存在/可点，不验真实 shell |
| 生成物 | `app/src/main/assets/runtime-descriptor.json` 需按 `tools/prepare-runtime-descriptor.py --write` 重生成（`PtySession`/owner/runtime 都是 launcher 输入，见 `app/src/main/assets/managed-runtime-inputs.json:538-568`、`tools/prepare-runtime-descriptor.py:58-60`） | 与 build213 的 rc.5 升级协调，避免并行改同一 descriptor |

位置选择：

- **不做侧栏 footer 第 7 个动作**。build213 刚把手机窄窗抽屉上限收到 224dp（`docs/terminal.md:13-16`），底部 6 个动作在 2/3 列下已经占 3/2 行（`TerminalActivity.java:374-540`、`TerminalWorkspacePolicy.java:91-103`）；第 7 个动作在 wide/dense 都会多一行，直接挤压会话列表，和这次收窄的意图相反。
- **工作台顶栏按钮**是一键入口；**“新建会话”选择器第三项**覆盖极简模式和无障碍/键盘用户（两次点按）。两者共用 `createCliSession()`。
- 极简模式悬浮球不加长按/二级菜单：不可发现，且会扩大手势语义；本期不做。

固定命令的内容（示意，非最终代码）：

```sh
# 由 DshaCliPreset 用 ShellQuote 拼静态行，不含用户输入
stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true
if _dsh_v=$(dsh --version 2>/dev/null); then
  printf 'DSH CLI · dsh %s\n' "$_dsh_v"
else
  printf '%s\n' '<本地化的“dsh 不可用，请先完成环境安装”提示>'
fi
printf '%s\n' '<DSH CLI 能力与边界：dsh --help>'
printf '%s\n' '<dsh --profile headless "<任务>"：一次性任务；GUI 登录默认不读取，需环境变量/.env 或 patch 启用>'
printf '%s\n' '<dsh plugin --profile web ...：Web 运行中勿改>'
printf '%s\n' '<没有交互式 dsh TUI；dsh web 由 DSHA 管理，请勿在终端再启动>'
exec /bin/bash -l
```

`exec /bin/bash -l` 很关键：它让同一个进程（同一会话组长）变成登录 shell，PTY 的出生身份/会话关系不变；不会出现“父脚本等子 shell”导致关闭语义变化。默认命令的 `stty` 前导沿用 `TerminalRuntime.java:53-55` 的做法，避免某些 ROM 上终端模式不干净。

风险与缓解：

- **产品误解**：用户可能以为点开就是交互式 agent。banner 第一行就要写“没有 TUI；这里是 dsh 启动器 + shell”，并把 `headless` 的凭据/权限差异写清楚；否则入口会制造“点开→输入→认证失败”的差体验，比没有入口更糟。
- **受保护路径改动**：`PtySession`/`TerminalSessionOwner` 是锁定不变式所在类。缓解：只把 `guestCommand` 加到 argv 构造；不新增 fork 路径；原有重载保持行为；单测断言命令到达 `PtyStart` 且保护票 `beginCreate` 时序不变；真机覆盖关闭/旋转/维护。
- **`dsh` 消失或环境未就绪**：`dsh --version` 失败时打印本地化提示，仍然进 shell；不把“CLI 不可用”伪装成成功。环境未就绪时 `PtyTerminalFragment` 原有的 `terminalBlockMessage`（`PtyTerminalFragment.java:133-138`）继续生效。
- **升级冲突**：banner 里只硬编码 `--profile headless`、`plugin`、`--help` 这几项稳定语法；不复制上游完整命令表。rc.5→rc.6 升级时用锁定运行时跑一次 `--version`/`--help` 冒烟（可加 host test），发现语法变化再改 banner。
- **可测试性**：纯逻辑 `DshaCliPreset` 可单测；`TerminalRuntime.prepare` 可断言 argv；owner 可用 `PtyStart` 缝断言；UI 入口只能 Robolectric + 真机。真实 PTY、IME、关闭核验、维护屏障仍须正式 APK 设备记录（`docs/terminal.md:65-67`）。

### 2.3 C：容器内独立 `dsha` CLI（与终端页面解耦）

C 有三种实现层次，成本和风险差别很大：

| 变体 | 做法 | 成本 | 风险 | 评价 |
|---|---|---|---|---|
| C-asset | 新增 `app/src/main/assets/dsha-*.sh`，写进 `managed-runtime-inputs.json` 的 `installs`，安装到 `/root/dsh-bin/dsha`；同步 `tools/asset-deployment.json`、重生成 `runtime-descriptor.json` | 8–10 个文件（含资产、清单、摘要、host test） | 资产/摘要/发布身份变更；脚本成为受管文件，升级会覆盖用户修改；需要 host test 与设备验证 | 只有确实要携带大脚本/多文件时才值得 |
| **C-light（推荐第二步）** | 用 `RuntimeTools.preparePackageCommands` 的既有生成式 wrapper 先例（`RuntimeTools.java:104-138`），由纯 Java `DshaCliEntrypoint.wrapper()` 生成 `/root/dsh-bin/dsha`，并加入 `ManagedRuntimeLayout` 的 `root/dsh-bin` 列表（`ManagedRuntimeLayout.java:50-52`） | 4–6 个 Java/测试文件，150–250 行 | 与 `dsh`/`npm`/`pnpm` 相同的覆盖/修复语义；没有资产清单负担；仍会因 launcher 输入变化触发 descriptor 重生成 | 最贴合本仓库；先做 local 子命令，不做桥 |
| C-bridge | `dsha` 子命令封装 3090 桥（UI/设备/通知/导出等） | 在 C-light 上再加端点表/测试 | 动态端口、token、`[EXECUTION_UNKNOWN]` 不重放、固定端点表；成为新的公开接口；与内置插件能力重叠 | 只在有明确脚本/用户消费者时做 |

C-light 的最小命令面（建议）：

- `dsha cli` → 打印 banner 并 `exec /bin/bash -l`（与 B 的 preset 同一套文案，B 的入口可改为直接调用它）。
- `dsha version` → `exec dsh --version`；`dsha help` → DSHA 自己的帮助 + `dsh --help` 指针。
- `dsha <其它>` → `exec dsh "$@"`，透传退出码；**不自动重试**。
- `dsha web` / `dsha --profile web ...` → 拒绝并提示“Web 由 DSHA 启动；在终端起第二个 Web 会脱离 DSHA 的停止/维护管理”（允许 `--help` 查询）。
- 明确不接管 `headless` 的凭据/权限：要跑任务，用户需自己提供 `.env`/环境变量，或走单独的、经过评审的 credentials overlay；不要默认把 GUI 登录塞进 CLI。

C 的通用风险：

- **3090 桥 token**：只从 `/root/.dsh/.bridge_headers` 读，不落 argv/URL/日志；端口只从 `.bridge_port` 读并严格校验（1.3.5）。token 轮换后自动生效，不缓存。
- **设备命令白名单**：C 若调用 `adb-shell`，受 `DeviceShellPolicy` 约束；不要另开一条更宽松的设备执行路径。容器内命令不受该白名单约束，但也不因此获得 Android 权限（`AGENTS.md:103-107`）。
- **新公开契约**：一旦脚本/agent 依赖 `dsha`，改名/删子命令就是破坏性变更；先只做 `cli/version/help` 和透传，桥接端点表等有消费者再加。
- **与终端页解耦的代价**：C 本身不提供“终端页入口”；用户仍要自己知道 `dsha`。所以它适合作为 B 的命令后端，而不是替代 B。

## 3. 推荐的最小可交付增量（B）

### 3.1 范围

1. 新增 `DshaCliPreset`（纯逻辑）生成固定 guest command。
2. 把 `guestCommand` 从 `PtyTerminalFragment` 经 owner 传到 `PtySession`/`ProotBootstrap`，只影响 argv。
3. `TerminalActivity` 加工作台顶栏 CLI 按钮 + 新建选择器第三项。
4. 三个测试文件 + 一篇 `docs/terminal.md` 的“CLI 入口”小节。
5. 重生成 `runtime-descriptor.json`（构建任务要求；不是手工改语义）。
6. 不新增权限、不改 Manifest、不改 `managed-runtime-inputs.json`、不改 `tools/i18n/messages.json`（终端现有 `UiText.choose` 也大多不在目录里；新文案沿用同一模式，其他语言回退英文）。

### 3.2 验证

单测（JVM，无需真机）：

```bash
bash build.sh :app:testStandardDebugUnitTest \
  --tests 'com.deepseekharness.app.util.DshaCliPresetTest' \
  --tests 'com.deepseekharness.app.runtime.TerminalRuntimeTest' \
  --tests 'com.deepseekharness.app.core.TerminalSessionOwnerTest'
```

- `DshaCliPresetTest`：argv 形状、`exec /bin/bash -l`、无用户输入插值、zh/en banner、`dsh` 不可用时的提示不伪造成功。
- `TerminalRuntimeTest`：`TerminalRuntime.prepare(..., "/bin/bash", "-c", script)` 的 argv/env 与默认路径只差命令尾部。
- `TerminalSessionOwnerTest`：命令到达 `PtyStart`；`beginCreate`/保护票/关闭状态不变。

全量门禁（合并前）：

```bash
bash build.sh :app:testStandardDebugUnitTest
bash build.sh :app:assembleStandardRelease   # 或项目当前发布任务
python3 tools/prepare-runtime-descriptor.py --check
python3 tools/test-runtime-descriptor-inputs.py
```

真机（正式包，不用 debug 夹具；记录 APK 摘要）：

1. 工作台点顶栏 CLI → 新标签标题 `DSH CLI`，banner 显示 `dsh --version`；`dsh --help` 可滚动。
2. `dsh --profile headless --help` 可显示；**不点/不自动跑真实任务**。
3. 输入 `exit` → 标签显示“已核验退出 · 历史保留”；关闭该标签需确认且核验后才移除（`docs/terminal.md:53`）。
4. 旋转/切语言/回主界面再进终端 → 标签仍在（同进程 owner）；banner 不重放。
5. `结束全部` → 该会话按 PTY 计数/关闭；`confirmCloseAll` 文案仍是 PTY/简易两类（`TerminalActivity.java:1753-1760`），不需要新计数。
6. 维护/停止 Web：终端不被误停；维护前仍先关闭终端（`MaintenanceCoordinator.java:131` 路径不经过新代码）。
7. 224dp 抽屉/大字体/横屏：顶栏按钮增至 5 个（不同形态同时可见 3–4 个），48dp 目标不挤压标题；需在 320dp 宽窗口目视 + `LayoutAuditInstrumentation`；选择器第三项不溢出。
8. 环境未就绪：入口沿用 `terminalBlockMessage`，不创建半截会话。

### 3.3 不做（明确边界）

- 不做交互式 TUI（上游没有）；不把 `dsh headless` 做成“点开即跑”，不自动消费模型 token。
- 不默认启用 GUI 凭据给 CLI；headless 的凭据/权限差异单独评审（见 1.3.2）。
- 不把 `dsh web`/`dsh plugin --profile web` 纳入一键入口；不在终端侧新增第二条 Web/插件安装路径。
- 不加侧栏 footer 第 7 个动作（与 build213 的 224dp 收窄冲突）。
- 不改 `TerminalRuntimeProtection.Kind`、不改 `TerminalSessionOwner` 的关闭/全量关闭语义、不改维护屏障。

## 4. 平滑演进

1. **B → C-light（第二步）**：`DshaCliPreset` 的 script 改为 `exec /root/dsh-bin/dsha cli`；新增 `DshaCliEntrypoint.wrapper()` + `RuntimeTools.preparePackageCommands` 写入 + `ManagedRuntimeLayout` 路径 + `RuntimePackageCommandsAndroidTest` 断言；`dsha` 增加 `version/help` 与 `web` 拒绝，其余透传 `dsh`。UI 不变。
2. **C-light → C-bridge（按需）**：只有当脚本/用户明确需要“从终端调用屏幕/设备/通知/导出”时，才加固定子命令表；端口/token/125 语义按 1.3.5；不提供任意路径透传，不自动重试。
3. **B/C → A（仅在必要时）**：如果将来 CLI 需要独立于 PTY 的生命周期（例如常驻 ACP 客户端、独立保护原因、独立关闭策略），再引入第三种 Kind；届时优先按 `docs/design/terminal-20261025/implementation-guide.md:54` 的 `TerminalPresentationState`/`TerminalWorkspaceController` 重构一起做，而不是把 `boolean pty` 临时扩成三值。
4. **上游真出 TUI 时**：入口/`dsha` 只改命令串（例如 `dsh --profile tui` 或安装的第三方 bundle），终端的关闭/保护/维护代码不动；这是 B/C 相对 A 的最大演进优势。

## 5. 证据边界与未决问题

- 本文只写决策，没有修改产品代码，没有执行 Gradle 单测/构建，没有操作手机、没有启动 Web、没有调用模型。
- rc.5 的 CLI 行为来自 `app/build/locked-dsh-runtime/` 的已生成运行时，用隔离 `DSH_HOME=/tmp/...` 实测；`/usr/local` 当前仍是 build212 的 rc.3，不作为 rc.5 结论来源。工作树正在做 rc.5/build213 升级（`app/build.gradle:68`、`tools/dsh-runtime/package.json:3`），合并后行号可能再变。
- headless 在无人值守下遇到 `ask` 审批的具体行为、GUI 凭据 overlay 在 rc.5 的实际效果、`dsh headless` 真实任务的成功率：**均未验证**，需要真机 + 明确凭据策略后再对外宣称。
- B 的 preset 文案/命令表需要随 DSH 升级复验；建议加一个 host 冒烟：用锁定运行时的 `bin.js --version` / `--help` 断言退出 0（不启动 Web、不读用户 DSH_HOME）。
- 若产品最终目标是“像 Claude Code 那样的终端交互式 agent”，B/C 都不是终点：需要上游 TUI 或自建 REPL（后者超出最小增量范围），应在需求层面先确认。

## 6. 关键引用索引

- 终端入口/会话/关闭：`app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java:163-200、273-556、798-826、1425-1442、1949-2008`；`TerminalPane.java:4-31`；`PtyTerminalFragment.java:129-197`；`TerminalFragment.java:168-230`
- 所有者/保护/关闭核验：`TerminalSessionOwner.java:18-35、162-241、352-493`；`TerminalRuntimeProtection.java:18-21、200-218`；`TerminalRuntimeService.java:36-118`；`TerminalProcessCloser.java:16-43、118-136`；`TerminalTabs.java:8-13、123-179`
- 维护屏障：`MaintenanceCoordinator.java:131`；`AGENTS.md:150、182`
- 运行环境/命令入口：`RuntimeTools.java:104-140、687-711`；`RuntimeLauncher.java:75-79`；`TerminalRuntime.java:39-70`；`PtySession.java:107-183`；`ProotBootstrap.java:379-394`；`WebLaunchCommand.java:10-33`；`DshEntrypoint.java:1-16`
- 抽屉/底栏：`docs/terminal.md:13-16`；`TerminalWorkspacePolicy.java:55-70、91-103`
- 文档：`docs/runtime-engines.md:64、147`；`docs/runtime-data-plugin-architecture.md:203-208`；`docs/bridge-and-files.md:9-17、32-36`；`docs/design/terminal-20261025/implementation-guide.md:54`
- DSH rc.5：`app/build/locked-dsh-runtime/node_modules/@deepseek-ai/dsh/README.md:7-31`；`.../dsh/lib/bin.js:39、101-108`；`.../dsh-app-boot/lib/index.js:551-557`；`.../dsh-headless/README.md:12、55、59`；`.../dsh-headless/cordis.patch.yml:36-43`；`.../dsh-base/cordis.patch.yml:246、262`
- 桥：`BridgePortPolicy.java:5-24`；`HttpShellService.java:789-803`；`app/src/main/assets/bridge-port.cjs`；`app/src/main/assets/managed-runtime-inputs.json:301-312、538-568`
