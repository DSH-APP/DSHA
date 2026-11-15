# K5 真机验收回执（2026-11-07，部分执行；场景 1 已通过）

> 设备：LENOVO TB321FU / Android 16 (SDK 36) / 系统 WebView 140.0.7339.207（诊断 `WEB_ENGINE`）
> 正式包：`0.2.1-rc.5-20261107.1900-dsh0.2.2-rc.1` / build337（commit 33719399，tag `dsha-a-su-build337-20261107-190000`）
> 测试包：`io.github.sunsetrne.dsha.test`，prep/build337-k5 25463a86（K5 部分验收）；两轮修复包 9ca5d238 / 3d3866f7（见下）
> 原则：正式包不含 T110；K5 场景 1 已经真机复测通过，其余场景未执行；K5 整体不写成全部通过，T110 合并范围由版本线决定。单设备完成项如实标注。

## 已完成（真机）

| 项 | 结果 | 证据 |
|---|---|---|
| **K5 场景 1：流式长页面应用内往返（二轮诊断包 3d3866f7）** | **通过（用户实测）** | 应用内往返后**黄→绿重连消失**；**流式输出不再整页重载、不再长时间卡加载**（用户 2026-11-07 确认）；草稿保留沿用前次观察 |
| T115 手机任务接口（正式包 337） | **通过** | `android_control begin` / `screenshot` / 语义节点 click 均成功；诊断日志无新增 `[ASSISTANT_CONNECT] LOCAL_HTTP status=409`（升级前有 17:42–17:44 三次 409） |
| T116 Web 终端 `pty.fork`（正式包 337） | **通过（同后端链路）** | 本会话 `terminal_open(type=shell)` 成功，`echo T116-OK` 回显、`uname -m`=aarch64；审计确认工具终端与 Web 侧栏终端同一 node-pty 后端。UI 侧栏终端未手动点开，标注「同后端已验证、UI 未手点」 |
| T111 隐藏后台 recents（正式包 337） | **通过（用户实测）** | Lenovo 单设备，用户确认后台/最近任务行为符合预期 |
| K5 场景 10 冷启动部分 | **界面状态已观察** | 测试包连续截图捕获：`正在检查运行环境…` → `正在启动 Web…` → `已就绪，可进入`；帧留存设备 `/sdcard/Download/k5cap2/r2_f14、r2_f16、r2_f18` 等，不提交仓库。内存指标未测 |

## 失败 / 观察项（2026-11-07 一轮复测；二轮已修复）

- **场景 1：流式长页面 + 应用内往返 → 黄→绿重走、整页重渲染（用户实测，2026-11-07 20:27，稳定复现）**：
  - 重上下文条件：**流式输出中的长页面**。
  - 触发动作：Web → 设置 / 终端 / 插件页 → 回 Web。
  - 表现：连接状态**黄→绿重走**，长页面整页重渲染；**无原生加载遮罩**；输入草稿 `123456789` 保留。
  - 稳定性：同一重上下文会话可稳定复现；轻上下文会话只出现「重新连接中…」→「连接成功」（2 秒内）。
  - 范围：**测试包（含 T110 K2）与正式包 337 都会出现**；正式包无 T110 符合预期，但测试包同样出现说明 T110 当前 K2 未消除该现象。
  - **用户补充（2026-11-07 20:45）**：重连后**流式输出完全重载**（重新拉取 / 重渲染），并**长时间卡在加载状态**；草稿保留。
  - 判定：这不是可接受的「仅重连」；按 §7 场景 1 判**失败**。A 口径（放宽为允许重连）不成立，T110 需修复（K2 路径或客户端重同步），修复后重跑场景 1/2。
- 按 §7 场景 1 的「侧栏不重走黄→绿」断言，本项在一轮复测时**判失败**；「无原生加载遮罩、草稿保留」部分通过。二轮复测后本项已通过（见下）。

## 场景 1 根因与修复（2026-11-07；一轮 9ca5d238，最终复位条件 3d3866f7，已真机通过）

> 历史记录：本节保留第一轮修复/复测过程；最终生效的复位条件见「二轮修复与诊断包（3d3866f7）」。场景 1 已于 2026-11-07 真机通过。

**根因：不是 K2 keeper 缺口，也不是页面文档被重建；是客户端在「同一文档前台恢复」时无条件重置 Connection。**

- 页面生命周期：这条往返走 `WebPreviewActivity.hideConversation()` → `BrowserTasks.nativeNavigation()`，只把 Web 宿主切到后台表现并让 Main 到前台，**没有 `finish()`**；K2 的 `onDestroy(isFinishing)` / 系统回收两条退役路径都不触发。正式包在不被系统回收时同样保留文档，所以「测试包与正式包都出现」不能证明 K2 失效。
- 客户端：回到 Web 时原生 `resumePageStreams()` 派发 `dsha-browser-resume`；`app-integration/client.js` 的 `installStreamResume` 同时监听 `visibilitychange` / `dsha-browser-resume`，收到即无条件 `ctx.connection.reconnect()`。Connection 换代后网关按新基线重发所有 Remote 流（`session.control` 与当前会话的 journal / assistant 流），长会话重新拉取并重渲染整份历史 → 「黄→绿 + 流式输出完全重载 + 长时间加载」。草稿在 IndexedDB / localStorage，故保留；文档没换，故没有原生加载遮罩。
- 这条无条件重置是既有兜底（用于「后台 carrier 已死但 WebSocket 仍报 OPEN」）；K1/K2 保活后，同一文档内的应用页往返不再需要它，反而制造了 K5 场景 1 的回归。

**修复（最小、可回滚）**：`installStreamResume` 重置前先用一条只读 `session.control` 流探测当前物理 carrier：

- 有任一帧返回 → 保留连接，不重建 Remote 基线（长页面不重渲染）；
- 探测 2 秒无响应（死 carrier）→ 保持原有无条件重置；
- Connection 已不是 `connected`（Android 漏掉 online 事件的后台等待）→ 保持原来立即重置；
- `remote` / 探测端点不可用 → 退回原行为（不因探测能力缺失而卡死）。

探测只读、不重放用户输入、不新增原生信号；每次应用内返回最多增加一条短命 Session 控制流（网关本就支持多客户端）。

**证据**：

| 项 | 结果 |
|---|---|
| `DSHA_TEST_RUNTIME=<本地 0.2.2-rc.1 raw 夹具> node tools/test-browser-stream-resume.mjs` | **11/11 通过**：新增「应用内往返保留活 carrier」「visibility 往返保留基线」「生成式 `session.control` 探测」「探测能力缺失回退」；原有死 carrier / 离线 / 隐藏期 pending / Gecko / 单次 admission / rc3 流全部保持通过 |
| contracts（descriptor / builtin / runtime-tools / third-party / credential / semver / manifest / hygiene / i18n / changelog / document-links） | 全部 exit 0（逐项见下方「门禁回执」） |
| `format-java-tree.py --check`（JDK17） | exit 0（1550 文件，0 need formatting） |
| `prepare-runtime-descriptor.py --check` | exit 0；`app-integration/client.js` 摘要与 `runtimeId` 已更新 |

**待真机复测（未验证，不得写成通过）**：

1. 安装新测试包（`io.github.sunsetrne.dsha.test`），完成覆盖升级 / 运行时维护；
2. 打开一个有**正在流式输出**的长会话，记住当前输出位置与输入草稿；
3. 按场景 1 往返：Web → 设置 / 终端 / 插件页 → 回 Web，至少 3 次；
4. 断言：无新 `WEB_ENGINE` / `WEB_PAGE_DESTROY`；连接状态不重走黄→绿；页面不整页重渲染、不长时间卡加载；流式输出继续追加；草稿 / 滚动保留；
5. 若仍失败：抓 `WEB_ENGINE` / `WEB_PAGE_DESTROY` / `WEB_KEEPALIVE_*` 与时间线，区分「文档重载」还是「客户端仍重置」；同时记录是否出现探测超时（2 秒）后重连。

## 修复候选测试包（2026-11-07）

- 构建：`dsha-gradle :app:assembleStandardDebug -Pdsha.sunsetTest=true`，exit 0（BUILD SUCCESSFUL in 55s，日志 `/root/.cache/dsha-gradle-logs/k5fix-20261107T1256Z.log`）。
- 设备路径：`/sdcard/Download/dsha-test-k5fix-9ca5d238-20261107.apk`
- 本机构建产物：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- bytes：321,652,289；SHA-256：`e93393282e7aa489687efc355f36bc8ac0a8a5070405f51aac2953e385052f04`
- 包名 `io.github.sunsetrne.dsha.test`；versionCode 336；versionName `0.2.1-rc.5-20261107.0330-dsh0.2.2-rc.1-SunsetTest`；debug 签名（证书 SHA-256 `790c7fb5…0ce76ba`，与上一测试包 `dsha-test-build337-k5-v2-20261107.apk` 相同，可直接覆盖升级）。
- 包内核对：`assets/app-integration/client.js` SHA-256 = `5ef90deb…8b4143dd`（与工作树一致）；`assets/runtime-descriptor.json` runtimeId = `9a0c5f45…f4f4f4afe`。
- 基线：`feat/webview-keepalive-v2` tip = 9ca5d238（build336 基线 + K2 + 本修复 + 本回执），**不含 build337 其它主线修复**；只用于 K5 场景 1 复测，不作为功能全集验收包。

## 2026-11-07 真机复测（修复包 9ca5d238）——场景 1 仍失败

- 结果：维护重建后首次进入 Web **无黄→绿**；但应用内往返（Web → 设置 / 终端 / 插件页 → 回 Web）返回时**仍有黄→绿**，且**流式输出完全重载 + 长时间卡加载**，与修复前同级别。首次进入没有 resume 事件，不能单独证明修复生效；返回路径仍触发全量重建。
- 待区分（本轮新增诊断）：① 部署的 client 是否仍是旧版；② 探针 2s 超时是否只是长页面主线程繁忙；③ hidden 期间 socket 是否真被服务端心跳终止；④ 是否还有另一条全量 resync 路径。

## 二轮修复与诊断包（提交 3d3866f7）

- 复位条件：探针失败时若 Connection 仍为 `connected`，**不再强制 `reconnect()`**（避免长页面慢回包被误判成死 carrier 而重建整份 Remote 基线）；只有 Connection 已 `connecting` / `disconnected` 才保持原来的立即重置。真正的 dead-but-OPEN 交给服务端心跳（2s×2）或 socket close 的官方恢复。
- 诊断（脱敏）：客户端每次 resume 写 `window.__DSHA_RESUME_DIAG__`（`impl=k5fix2`、`trigger`、`visibility`、`state`、`generation`、`probe`、`elapsedMs`、`reset`、`stateAfter`、`hiddenMs`）；native 在派发 resume 后 2.6s 读取并写 `WEB_PAGE_RESUME` 行；旧客户端/未部署时该行为 `probe=absent`。
- native resume 事件带 `detail.foreground`（`ForegroundActivity.isAnotherActivityVisible`），用于区分应用内切换与整体退后台；诊断行有 `inApp`。
- 新测试包：`/sdcard/Download/dsha-test-k5fix2-diag-3d3866f7-20261107.apk`
  - bytes 327,899,929；SHA-256 `810cd6596c0a01fcc57b40c9da809854f94ac82ba41f20e730fc00d2275a1b61`
  - `io.github.sunsetrne.dsha.test`，versionCode 336，debug 签名同上一个测试包；包内 `client.js` SHA-256 `268c48702fc790e249b3a82feee0a096c8fdc5e8380d5552f17f31befe6e4403`，descriptor runtimeId `60e9b7361dee832460822c7718f1e1a758580372beda90bcdaec555d2fbea26e`
  - 基线仍为 build336 + K2 + 本轮修复，不含 build337 主线提交（与上一测试包 25463a86 的 base 不同；若诊断显示页面被重建而非 client reset，需在新 main 基线上另建测试包）
- 门禁：JS `test-browser-stream-resume` **11/11**（探针超时仍 connected → 不 reset，随后 close 才恢复）；定向 Java `WebPageDiagnosticTextTest` / `BrowserPageKeeperTest` / `WebBackgroundPageLifecycleTest` **36 tests / 0 failures / 0 errors / 0 skipped**；`format-java-tree --check` exit 0；descriptor check/verify exit 0；`assembleStandardDebug -Pdsha.sunsetTest=true` exit 0（53s）。

**复测与诊断抓取**：

1. 安装新包并完成维护 / 运行时更新；
2. 长流式会话中 Web → 设置 / 终端 / 插件 → 回 Web ≥3 次；
3. 若仍出现黄→绿 / 整页重载：读取测试包诊断日志，搜索 `WEB_PAGE_RESUME`、`WEB_ENGINE`、`WEB_PAGE_DESTROY`、`WEB_KEEPALIVE_*`；
   - `impl` 不是 `k5fix2` 或 `probe=absent` → 新 client 未部署，先解决部署 / 重建；
   - `probe=timeout-kept` 且仍重载 → 探针外还有全量 resync 路径，或 socket 真死（看 `generation` / `generationAfter` 与随后的重连）；
   - `probe=alive` 且仍重载 → 重载不来自本次探测分支；
   - 有 `WEB_ENGINE` / `WEB_PAGE_DESTROY` → 页面 / 文档被重建，问题在 K2 / 生命周期或基线，而不是 client reset。
4. 核对测试包 rootfs 的 `/root/dsha-app-integration/client.js` SHA-256 是否为 `268c4870…befe6e4403`（与 APK 包内一致）。

## 2026-11-07 真机复测（二轮诊断包 3d3866f7）——场景 1 通过

- **用户实测确认**：应用内往返（Web → 设置 / 终端 / 插件 → 回 Web）后**黄→绿不再出现**；同一路径下**流式输出不再整页重载、不再长时间卡加载**。此前稳定复现的两项症状均消失。
- 修复点：二轮 `3d3866f7` 把复位条件收紧为「探针失败但 Connection 仍 `connected` 时不 `reconnect()`」。黄→绿是 Connection 重置的可见指示；重置消失后网关不再重建整份 Remote 基线，长流式页面不再整页重拉/重渲染。诊断（`WEB_PAGE_RESUME`）保留在包内用于后续回归。
- 判定：**K5 场景 1 通过（单设备，LENOVO TB321FU / Android 16 / WebView 140）**。证据为用户本次真机确认；未附 `WEB_PAGE_RESUME` 原始行/录屏，若需逐项归档可由用户补一次设置→诊断报告（预期 `impl=k5fix2`、`probe=timeout-kept`、`reset=false`）。
- 范围：其它 K5 场景（2/3/4/5/6/7/8/9/10/11/12/13）仍未执行；第二台设备/第二种 WebView 未安排。**K5 整体未完成，不写成全部通过。**

## 未执行（不得写成通过）

- 场景 1 应用内往返**已通过**（二轮诊断包 3d3866f7，用户实测；见上），不再列为未执行。
- 场景 2 Home/切 App 30 秒/5 分钟/30 分钟未执行。
- 场景 3 开发者选项「不保留活动」/系统回收 Activity 未执行。
- 场景 4 进程死亡（最近任务划掉 / force-stop / OOM）未执行。
- 场景 5 旋转、深浅色、语言、字体缩放未执行。
- 场景 6 PiP、场景 7 停止/维护/备份、场景 8 origin-restore、场景 9 renderer、场景 11 多窗口、场景 12 上传/下载/麦克风、场景 13 日志脱敏片段均未执行。
- 第二台设备与第二种 WebView 未安排（§7 前置要求 2 设备 / 2 WebView）。

## 结论

K5 **场景 1 已在单设备真机通过**（二轮诊断包 3d3866f7，2026-11-07 用户实测：黄→绿消失，流式输出不再整页重载/长时间卡加载）；**K5 整体未完成**——场景 2/3/4/5/6/7/8/9/10/11/12/13 仍未执行，第二台设备/第二种 WebView 未安排。T110 `55ef9d68` 的合并范围（是否以场景 1 通过 + 其余场景后续补测为条件）由主会话/版本线决定，本回执不单方面宣称 K5 全部通过。后续按 §7 继续其余场景并补充回执。

## 门禁回执（本修复）

| 检查 | 命令 | 退出码 / 结果 |
|---|---|---|
| JS 流恢复单测 | `DSHA_TEST_RUNTIME=<本地 0.2.2-rc.1 raw 夹具> node tools/test-browser-stream-resume.mjs` | 0 · 11/11 |
| Java 格式 | `JAVA_HOME=<JDK17> python3 tools/format-java-tree.py --check` | 0 · 1550 文件，0 need formatting |
| 运行时描述符 | `python3 tools/prepare-runtime-descriptor.py --check` | 0 |
| 描述符源核验 | `python3 tools/verify-runtime-descriptor-source.py` | 0 · 202 assets / 472 launchers |
| 内置插件 | `python3 tools/generate-builtin-plugins.py --verify` | 0 |
| Runtime tools | `python3 tools/prepare-runtime-tools.py --check` | 0 |
| 第三方声明 | `python3 tools/generate-third-party-notices.py --check`；`python3 tools/check-third-party-notices.py` | 0 / 0 |
| 凭据路径 | `python3 tools/generate-credential-paths.py --check` | 0 |
| SemVer 参考 | `python3 tools/prepare-semver-reference.py` | 0 |
| Host 测试清单 | `python3 tools/run-host-tests.py --check-manifest` | 0 · 250 tests |
| 源卫生 | `python3 tools/check-source-hygiene.py` | 0 |
| UI i18n | `python3 tools/check-ui-i18n.py` | 0 · newFindings 0 |
| CHANGELOG | `python3 tools/generate-changelog.py --check` | 0 |
| 文档链接 | `python3 tools/check-document-links.py` | 0（文档最终提交前重跑） |
| 定向 Java 单测（二轮后） | `dsha-gradle :app:testStandardDebugUnitTest --tests '*WebPageDiagnosticTextTest' --tests '*BrowserPageKeeperTest' --tests '*WebBackgroundPageLifecycleTest'` | 0 · 36 tests / 0 failures / 0 errors / 0 skipped |
| 测试包构建（一轮） | `dsha-gradle :app:assembleStandardDebug -Pdsha.sunsetTest=true` | 0 · BUILD SUCCESSFUL in 55s（`k5fix-20261107T1256Z.log`） |
| 测试包构建（二轮） | 同上 | 0 · BUILD SUCCESSFUL in 53s（`k5fix2-build-20261107T1320Z.log`） |
| K5 场景 1 真机复测（二轮包） | 用户实测（设置→诊断可查 `WEB_PAGE_RESUME`） | **通过**：黄→绿消失；流式输出不再整页重载/长时间卡加载 |

说明：一轮只改 JS/受管资产；二轮为诊断新增了 `WebPageDiagnosticText.resume`、`WebPageDiagnostics.resume`、native `WEB_PAGE_RESUME` 读取与 `ForegroundActivity.isAnotherActivityVisible`（均非 launcher 源，不改变 runtime launcher 身份；受管 `client.js` 变化已重算 descriptor）。构建日志见上表；二轮定向单测日志 `/root/.cache/dsha-gradle-logs/20261107T131850Z-webview-keepalive-v2-_app_testStandardDebugUnitTest_--tests__WebPageDiagnosticTex.log`。

## 用户决定（2026-11-07）：其余场景声明为实验性

用户明确：没有时间继续跑 K5 剩余场景，**把未执行场景（2/3/4/5/6/7/8/9/10/11/12/13 及第二设备/第二 WebView）声明为实验性，不阻塞 T110 合并**。已通过项：场景 1（应用内往返：不再整页重载/黄→绿，流式不丢，二轮包 3d3866f7 真机确认）；冷启动加载三态已观察。T110 合并前按版本线 rebase 到当前 main 并重算 runtime-descriptor。
