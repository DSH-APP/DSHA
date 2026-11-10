# 原生设备命令确认与报错 DeviceGuard（T40 设计稿）

状态：**设计稿，未实现**。Q1–Q5 已由用户 2026-11-03 定（§9）；Q6 已定（另立 T43）；Q7 已定（强确认）。ADB 通道上的确认依赖 T42。基线 build300（`ee969f59`），只读源码取证，未在真机复现。
来源：T39 设计稿（`design/screen-guard`，`docs/design/screen-guard-20261103/README.md`）Q4 用户决定「设备命令与识屏拆开」，§5.4 留下 D1 / D2 两个候选。
这是授权边界改动：Q1–Q7 已全部定，整稿经用户确认后才能写代码。

## 0. 结论

1. 选 **D2**：在原生 `deviceExecute` 里加一个纯逻辑判定器 `util/DeviceGuard`，按**命令类别**决定「直接执行 / 需确认 / 拒绝」。只读命令不确认；写文件、结束应用、读短信（「每次询问」档）、Root 原始 shell 需确认。
2. 确认**只能把「允许」收紧成「问一下」，不能把拒绝变成允许**：`HostDeviceCommandGuard`、`DeviceShellPolicy` 的 DENY、档位、短信关闭、读屏未允许（T39 §5.4.1）都在确认之前判定，确认窗永远看不到被拒的命令。
3. 「本轮允许」按**本轮任务 + 命令类别**记，和识屏的本轮记忆完全分开（T39 Q4）。Root 原始 shell 默认每条都问，确认窗给「本轮允许」（Q2）。
4. 报错统一成 T39 §7 的结构，去掉 `screen`、加 `kind`；`nextAction` 用 T39 词表，只新增 3 个设备专用词（§6.2），两份词表登记在同一张表里，避免分叉。
5. 必须照实写清：确认只约束**随包入口**（`adb-shell`、`/exec`、`/device/execute`）。ADB 私钥在容器里、Root 授权给的是整个 App UID，同 UID 的自写代码可以绕过确认；只有 Shizuku 通道的确认是原生侧真正挡得住的（§7）。**ADB 通道上的确认要真正生效，依赖 T42「ADB 通道原生化」**（B8）。

D1（只统一报错、不加确认）作为保底：如果用户觉得确认太多，D2 的「设备命令确认」开关选「不确认」就等于 D1，代码只写一套。

## 1. 现状（源码事实，build300）

调用链：`adb-shell`（`app/src/main/assets/adb-shell.py`）→ `GET /device/execute?cmd=&su=&adb=` → `HttpShellService.deviceExecute`（1065 行）→ 已执行结果，或返回 ADB 计划由 guest 自己连 ADB 发送。旧 `/exec` 走 `execOutput` → `deviceExecute(cmd,false,false)`，不支持 ADB 计划。

| # | 现象 | 位置 | 影响 |
|---|---|---|---|
| B1 | 原生侧**没有任何逐次确认**：白名单通过即执行 | `deviceExecute` / `devicePlan` | 写 Download、`am force-stop`、放宽档 Root shell 都不问；唯一的人为闸门是容器里 DSH 的工具审批，`danger-full-access` 或自动审核下不存在 |
| B2 | 短信只有开 / 关：关 → DENY；开 → `authorization: remembered` 直接放行 | `devicePlan` 1236 行 | 提示词（`dsh-device-shell-guide/lib/index.js:115`「默认逐次确认」）、ADB 说明（messages m0144「或逐次确认」）、撤销提示 m0433「后续查询需逐次确认」都和实际不符——实际是**拒绝**，没有逐次确认 |
| B3 | 设备命令不带任务身份 | `/device/execute` 只有 `cmd/su/adb` | 无法做「本轮允许」，手机任务暂停 / 停止也围栏不到设备命令 |
| B4 | 报错是文本码 + `[EXIT=N]`，同一个 126 涵盖主机守卫、白名单、未开 Root、短信关闭、计划校验失败 | `completedExecution` / `adb-shell.py main` | 助手分不清「换命令」还是「请用户去开」；没有 `nextAction` / `userAction` |
| B5 | guest 侧未知结果写成 `EXECUTION_UNKNOWN:`，原生侧写成 `[EXECUTION_UNKNOWN]` | `adb-shell.py` 514 / 551 行 vs `deviceExecute` 1107 行 | 两种前缀，插件要各认一次 |
| B6 | 通道顺序和虚拟屏不一致（T39 A9）：这里 Shizuku 只看 `hasPermission`，API≥37 时也不让 ADB 先于 Shizuku | `deviceExecute` 1090–1103 行 vs `ScreenCapabilityGate.order` | 同一台设备「走哪条」答案不同；报错里也没写用的是哪条 |
| B7 | ADB 路径的发送前复核只针对短信：连接建立后重取一次计划，内容或授权变化即拒 | `adb-shell.py` 300–308 行 | 这套「发送前复核」可直接扩成「确认票据复核」（§5.3） |
| B8 | ADB 路径原生只出计划，命令由容器 Python 用容器里的私钥发出；`su -c` 由 guest 拼接；`--host/--port` 可强制走 ADB 跳过 Root/Shizuku 执行器 | `deviceExecute` 1081–1083、1097–1103；`adb-shell.py` 27–29、509、519–543 | 本稿在 ADB 路径上的确认可被绕过；结构性修复归 **T42「ADB 通道原生化」**（主会话审计 `claims/T42/perm-audit-20261103.md` #1 #3 #11） |
| B9 | 读剪贴板只受系统「前台」限制，没有 DSHA 授权开关；`/app/open` 放行 `tel:` / `mailto:` / `market://` 等跳转，不确认 | `bridge/AppDeviceActions.clipboard` 91–133；`HttpShellService.appOpen` 3040–3064 | 剪贴板可能含密码、验证码；`tel:` 为 `ACTION_VIEW`，只打开拨号盘、不直接拨出。审计 #9；用户定另立 T43（Q6），方案见 §4.4 |

已有可复用件：`capabilities/CapabilityBroker`（确认代次、pending）、`util/BridgeConfirmations`（一次确认的等待与回执）、`assistant/AssistantConfirmationWindow`（带「本轮允许」的悬浮确认窗）、`control/PhoneControlCoordinator`（本轮任务身份）、DSH 已向工具环境注入 `DSH_PHONE_SESSION_ID` / `DSH_PHONE_TURN_ID`（本会话核对过存在）。

## 2. 目标与非目标

目标：原生侧对有副作用的设备命令有一道用户看得见的确认；确认次数可控（本轮记忆）；每个拒绝让助手一次选对下一步；文案和实际行为一致。

非目标（保持不动）：
- 不放宽 `DeviceShellPolicy`，不改三档语义，不改 `HostDeviceCommandGuard`。
- 批准不覆盖显式拒绝；没有「仍然允许」按钮。
- UNKNOWN 不重放；执行失败不换通道重放。
- 不动识屏：ScreenGuard 的确认策略、本轮记忆、读屏选项都不作用于设备命令，反之亦然（T39 Q4）。唯一交叉点 T39 §5.4.1（读屏类设备命令）由 ScreenGuard 那边定义，本稿只在判定顺序里给它留位置（D3）。
- 不改通道顺序（B6 另立 T41 对齐，Q5）。
- 不改虚拟屏受管启动 `/device/vscreen/start|commit`：它已有一次性原生票据，不再叠一层确认。

## 3. 模型

```java
// util/DeviceGuard.java —— 纯逻辑，零 Android import
public final class DeviceGuard {
  enum Category { READ, FILE_WRITE, STOP, SMS_READ, ROOT_SHELL }   // 由 DeviceShellPolicy.Kind 映射，见 §4.1

  record Request(String command, boolean forceRoot, boolean forceAdb,
                 DeviceShellPolicy.Plan plan,        // 已由 devicePlan 解析
                 String taskIdentity) {}             // session+turn；老客户端为 ""

  record State(DeviceShellPolicy.Mode mode, boolean rootAllowed, boolean rootPresent,
               boolean shizukuPermitted, boolean adbEnabled,
               SmsGrant sms,                         // OFF / ASK / REMEMBERED，§4.3
               boolean screenReadAllowed,            // 由 ScreenGuard 提供，只读
               ConfirmPolicy confirm,                // 用户设置，§4.2
               boolean confirmUiAvailable,           // 悬浮窗或通知可用
               TurnDeviceGrant turnGrant) {}         // 本轮记忆快照

  enum Decision { RUN, CONFIRM, REFUSE }

  record Verdict(Decision decision, Category category, String channel,   // root | shizuku | adb
                 DeviceRefusal refusal,              // RUN/CONFIRM 时为 null
                 boolean offerTurnScope) {}          // 确认窗是否给「本轮允许此类」

  static Verdict evaluate(Request request, State state);
}
```

`deviceExecute` 只做：采样 `State`（`device/DeviceGuardInputs.sample(ctx)`）→ `evaluate` → RUN 执行 / CONFIRM 弹窗 / REFUSE 返回。弹窗回来后**重新采样再判一次**（期间档位、短信开关、通道可能变了），确认只换来一次性票据。新逻辑放 `device/DeviceGuardBridge`，不往 `HttpShellService` 加职责。

## 4. 判定顺序与确认粒度

### 4.1 判定顺序（焊死，每步失败即返回）

| 步 | 检查 | error | 谁能补 |
|---|---|---|---|
| D0 | 主机命令守卫 `HostDeviceCommandGuard` | `DEVICE_HOST_COMMAND_BLOCKED` | 助手换命令 |
| D1 | 白名单解析 `DeviceShellPolicy.inspect`（按档位）；`DENY` | `DEVICE_COMMAND_NOT_ALLOWED`（`reason` 带策略原因） | 助手换命令或改用 App 层接口 |
| D2 | 能力闸门：`--su` 未允许 Root；放宽档无可用 Root；安装包路径不可核验 | `DEVICE_ROOT_NOT_ALLOWED` / `DEVICE_ROOT_REQUIRED` / `DEVICE_SELF_UNVERIFIED` | 用户开 Root；`SELF_UNVERIFIED` 报告用户 |
| D3 | 独立敏感能力：短信关闭；读屏类命令且读屏未允许（T39 §5.4.1） | `DEVICE_SMS_OFF` / `SCREEN_READ_NOT_ALLOWED` | 用户去设备能力授权 / 识屏设置 |
| D4 | 选通道（现有顺序不变：Root → Shizuku → ADB；SHELL 只 Root 或 ADB+su；短信不走 Shizuku） | `DEVICE_CHANNEL_UNAVAILABLE` | 用户连接通道 |
| D5 | 确认：按 §4.2 表；本轮记忆命中 → RUN；否则 CONFIRM | —（CONFIRM 不是拒绝） | — |
| D6 | 确认结果 | `DEVICE_USER_REJECTED` / `DEVICE_USER_NO_ANSWER` / `DEVICE_CONFIRM_UNAVAILABLE` | 拒绝不重试；无应答可再问一次 |
| D7 | 执行（含 ADB 发送前复核） | `DEVICE_EXECUTION_UNKNOWN`、`DEVICE_CONNECT_FAILED`、`DEVICE_CONFIRM_TICKET_INVALID` | 见 §6 |

D0–D4 都在 D5 之前：**被拒的命令永远不弹确认窗**，确认窗只出现在「本来就能执行」的命令上，这是「批准不覆盖显式拒绝」的结构保证，写进顺序锁定单测。
D4 放在 D5 之前，确认窗才能写清「将通过 Root / Shizuku / ADB 执行」，也避免通道不可用时白问一次。

### 4.2 确认粒度（Q1 已定）

| 类别 | 来源 Kind | 示例 | 默认 | 「本轮允许此类」 |
|---|---|---|---|---|
| READ | READ（含 `dumpsys`、`ls`、`cat`、`pm list`、`getprop`） | 查状态、读日志 | 不确认 | — |
| FILE_WRITE | FILE | Download / `/data/local/tmp` 下 `cp mv rm mkdir rmdir touch` | 确认 | 可（只限同类，路径不限） |
| STOP | STOP | `am force-stop <包>`、`kill <PID>`、`pkill -x` | 确认，窗上写解析出的**包名** | 可 |
| SMS_READ | SENSITIVE_READ | `content query --uri content://sms` | 按短信三态（§4.3） | 不可；ASK 档每条都问 |
| ROOT_SHELL | SHELL（放宽 / 完全放宽档） | 任意 Root 原始命令 | 确认，窗上显示**完整命令原文**和档位 | 可（Q2 已定）；档位变化即失效 |
| — | VIRTUAL_SCREEN | 受管虚拟屏启动 | 不经本守卫（已有原生票据） | — |

用户可调（原生设置 → 设备能力授权，新增「设备命令确认」）：
- **按类别确认**（默认）：上表。
- **只确认 Root 原始 shell 与短信**：FILE_WRITE / STOP 不问。
- **不确认**：等于 D1，只保留拒绝与统一报错；短信 ASK 档仍按条问（短信是独立敏感能力，不跟这个开关走）。

改动该项即递增修订号、废掉设备命令的本轮记忆。没有「全部允许包括被拒命令」这一档。

### 4.3 短信三态（B2）

现在只有开 / 关，文案却写「逐次确认」。改为三态，默认仍是最严：

| 档 | 行为 |
|---|---|
| 关闭（默认，同现状） | `DEVICE_SMS_OFF`，不弹窗 |
| 每次询问（新增） | 每条短信查询原生确认一次，窗上显示查询的列与条件；不给本轮记忆 |
| 预授权（同现状「开启」） | 直接执行；可随时撤销 |

撤销提示 m0433 按选中的档改写（撤到「关闭」写「后续查询将被拒绝」）。三态都只在 App 原生偏好，不从备份恢复（沿用现有规则）。三态已由用户定（Q3）。

提示词照实改写（审计 #2）：`dsh-device-shell-guide/lib/index.js:115` 的「默认逐次确认」改成「默认关闭；用户可在设备能力授权中改为每次询问或预授权」，m0144 同步。ADB 路径的短信发送前复核目前在 guest 里做，要等 T42 才是原生强制。

### 4.4 剪贴板与外部跳转（审计 #9，Q6 已定：另立 T43）

不在本任务内（用户 2026-11-03 定：另立 T43「剪贴板读取授权开关 + /app/open 外跳确认」，P2，等 DeviceGuard 与 AgentNextAction 进 main 后做）。方案记在这里供 T43 引用：

| 类别 | 端点 | 方案 |
|---|---|---|
| CLIPBOARD_READ | `/app/clip`（读） | 设备能力授权加开关「读剪贴板」：关闭（默认）/ 每次询问 / 预授权，与短信三态同构；写剪贴板不确认 |
| EXTERNAL_OPEN | `/app/open` 的 `tel:` `mailto:` `market://` | 确认，可「本轮允许」；`http(s)` `geo:` 不问。`tel:` 只打开拨号盘，不直接拨出 |

拒绝码 `DEVICE_CLIPBOARD_OFF`（126，after_user，REPORT_TO_USER，设备能力授权 → 读剪贴板）；报错格式与词表沿用 §6。`/app/share`、`/app/notify`、`/app/toast`、`/app/vibrate` 不加确认：它们只把内容交给用户或提醒用户，不读取数据。

### 4.5 设备命令作用于 DSHA 自身（@t39-guard 转来，Q7 已定：强确认）

T39 的 G6a 只拦识屏路径操作 DSHA 自己的界面；设备命令路径归本任务。源码现状：

- 严格档（Root / Shizuku / ADB 通用执行器）：`am` 只放行 `get-current-user`、`force-stop` / `kill <包>`，`am start` / `monkey` / `input` / `settings put` 都不认识 → 已拒。结束 DSHA 自身已被执行侧保护（`device-shell-policy.py` 243–244 行把自身包与 Shizuku 列为受保护）。
- 放宽 / 完全放宽档的 Root 原始 shell：不检查命令内容，可以 `am start` 拉起 DSHA 的「设备能力授权」页，再配合 `input` 改开关，或直接改 DSHA 的偏好文件。

规则（用户 2026-11-03 定 Q7）：ROOT_SHELL 命令原文里出现 DSHA 包名（`io.github.sunsetrne.dsha`，测试包 `.test`）或其私有数据路径时，**强确认**：
- 每条都问，**不给「本轮允许」**，也不受已有的 ROOT_SHELL 本轮允许豁免（Q2 的本轮允许对这类命令无效）；
- 「设备命令确认」开关选「不确认」或「只确认 Root shell 与短信」时仍然问，强确认不可被开关关掉；
- 窗上醒目提示「此命令会操作 DSHA 自身（可能改动授权）」并显示完整命令原文；
- 无人应答 / 拒绝的回复同 §6（`DEVICE_USER_NO_ANSWER` / `DEVICE_USER_REJECTED`），`reason: SELF_TARGET`。

实现上在 `Verdict` 加 `strongConfirm` 标志（`offerTurnScope` 恒 false），关键字清单焊死在 `DeviceGuard`，单测锁定「本轮允许不豁免」「开关不豁免」两条。和读屏类命令一样，这是按关键字的**尽力拦截**，原始 shell 可以拼接或编码绕过，安全模型照实写。不选直接拒绝：用户自己用 Root shell 维护 DSHA 时仍可确认后执行。

ALLOW_ALL 下识屏可以操作系统设置页，属于 ScreenGuard 的判定（T39 敏感应用名单），本任务不处理；设备命令本身改不了系统设置（严格档 `settings` 只允许 get / list）。

## 5. 任务身份、本轮记忆与确认窗

### 5.1 身份传递

- `adb-shell.py` 从环境读 `DSH_PHONE_SESSION_ID` / `DSH_PHONE_TURN_ID`（DSH 已向工具环境注入），作为 `taskSessionId` / `turnId` 参数带到 `/device/execute`；读不到就不带。
- 原生侧用 `PhoneControlCoordinator` 核对该会话 / 轮次仍是当前活动的对话轮次；对不上按「无身份」处理。**身份只用于记忆和显示，不作授权**：没有身份（终端手动 `adb-shell`、老插件、容器脚本）照样按类别弹窗确认，只是没有「本轮允许」按钮，每条都问（Q4 已定）。缺身份永远不会变成免确认，否则去掉环境变量就能绕过。
- 不要求先 `android_control begin`：设备命令不是操作屏幕，强制 begin 会让纯查询任务也弹出任务状态窗。

### 5.2 本轮记忆

键 = `taskSessionId + turnId + 设备确认修订号`，值 = 已允许的类别集合。失效：本轮结束、手机任务暂停 / 停止、修订号变化、Root / ADB / Shizuku 授权变化、档位变化。和 `TurnScreenGrants` 是两个对象，互不读写。

### 5.3 确认流程与票据

- 原生路径（Root / Shizuku）：`/device/execute` 在原生侧阻塞等确认，确认后立即执行。确认等待上限 60 s，超时 `DEVICE_USER_NO_ANSWER` + `actionExecuted:false`。guest 侧 `request_native_execution` 的读超时从 155 s 核对为「确认 60 s + 执行 90 s + 余量」，不够则加大；超时一律按现有逻辑判 UNKNOWN，所以**确认等待必须明确短于 guest 超时**，否则用户还没点、助手已收到 UNKNOWN。
- ADB 路径：确认在返回 ADB 计划**之前**完成，计划里带一次性 `nativeTicket`（绑定 argv / 命令原文、类别、30 s 有效）。guest 连上 ADB 后、发送前调 `/device/commit?ticket=`（复用 B7 的发送前复核位置），原生核销后才发送；票据无效 → `DEVICE_CONFIRM_TICKET_INVALID`，命令未发送。
- 确认窗：有悬浮窗权限时用 `AssistantConfirmationWindow` 的样式（按钮：允许一次 / 本轮允许此类 / 拒绝）；没有时退回通知 + `ConfirmReceiver`。窗上写：类别、完整命令、通道、解析出的目标（路径 / 包名 / 短信列）。全局「识屏全部允许」不代答设备命令确认。
- 用户正在打字（`USER_COMPOSING`）不影响设备命令：它不碰屏幕。

## 6. 统一错误语义

### 6.1 格式

`/device/execute` 的已完成结果在现有 `state/transport/output/exit` 之外加 `refusal` 对象，结构沿用 T39 §7，去掉 `screen`、加 `kind`：

```json
{
  "ok": false,
  "state": "REFUSED | NOT_SENT | UNKNOWN",
  "error": "DEVICE_SMS_OFF",
  "reason": "细分原因，可空（如策略原因原文）",
  "kind": "READ | FILE_WRITE | STOP | SMS_READ | ROOT_SHELL",
  "channel": "root | shizuku | adb | \"\"",
  "actionExecuted": false,
  "retry": "never | once_after_wait | after_user | after_fix",
  "nextAction": "REPORT_TO_USER",
  "userAction": "设置 → 设备能力授权 → 短信读取"
}
```

`adb-shell` 的文本输出保持现有首行 `[CODE] 说明` 与末行 `[EXIT=N]`（退出码 124 / 125 / 126 含义不变），在两者之间加一行机器可读的 `[DSHA_REFUSAL] {...}`。guest 侧未知结果统一成 `[EXECUTION_UNKNOWN]` 前缀（B5），旧 `EXECUTION_UNKNOWN:` 保留一个版本，删除节奏与 T39 Q5 相同，可并入同一份删除声明。

### 6.2 词表（与 T39 共用一张）

`nextAction` 复用 T39：`REPORT_TO_USER`、`STOP`、`ASK_USER_TO_RESUME`。新增 3 个设备专用词，登记进 T39 的同一张词表（实现时放 `util/AgentNextAction`，两边共用，单测锁定无重复）：

| 新词 | 含义 |
|---|---|
| `CHANGE_COMMAND` | 换一条白名单内的命令（附 `reason`） |
| `USE_APP_API` | 改用 `/app/*` 接口（如设备信息、应用列表、启动应用） |
| `VERIFY_DEVICE_STATE` | 结果未知：先用只读命令核对实际状态，不重放 |

### 6.3 码表

| error | 退出码 | retry | nextAction | userAction |
|---|---|---|---|---|
| `DEVICE_HOST_COMMAND_BLOCKED` | 126 | after_fix | CHANGE_COMMAND | — |
| `DEVICE_COMMAND_NOT_ALLOWED` | 126 | after_fix | CHANGE_COMMAND / USE_APP_API | — |
| `DEVICE_ROOT_NOT_ALLOWED` | 126 | after_user | REPORT_TO_USER | 设备能力授权 → Root shell |
| `DEVICE_ROOT_REQUIRED`（放宽档无 Root） | 124 | after_user | REPORT_TO_USER | 同上 |
| `DEVICE_SELF_UNVERIFIED` | 126 | never | REPORT_TO_USER | 重新安装 / 联系支持 |
| `DEVICE_SMS_OFF` | 126 | after_user | REPORT_TO_USER | 设备能力授权 → 短信读取 |
| `SCREEN_READ_NOT_ALLOWED` | 126 | after_user | REPORT_TO_USER | 识屏设置（文案由 ScreenGuard 定义） |
| `DEVICE_CHANNEL_UNAVAILABLE` | 124 | after_user | USE_APP_API / REPORT_TO_USER | 设备能力授权 → 连接 Root / Shizuku / ADB |
| `DEVICE_USER_REJECTED` | 126 | never | REPORT_TO_USER | — |
| `DEVICE_USER_NO_ANSWER` | 126 | once_after_wait | REPORT_TO_USER | — |
| `DEVICE_CONFIRM_UNAVAILABLE`（无悬浮窗且无通知权限） | 126 | after_user | REPORT_TO_USER | 允许 DSHA 通知或悬浮窗 |
| `DEVICE_CONFIRM_TICKET_INVALID` | 126 | once_after_wait | REPORT_TO_USER | — |
| `DEVICE_CONNECT_FAILED`（ADB 未发送） | 124 | after_user | REPORT_TO_USER | 无线调试与配对 |
| `DEVICE_EXECUTION_UNKNOWN` | 125 | never | VERIFY_DEVICE_STATE | — |

拒绝 / 无应答 / 票据失效都是 `actionExecuted:false`、命令未发送；只有 125 是可能已执行。

## 7. 能挡住什么（要写进安全模型）

| 通道 | 确认是否原生侧可强制 | 原因 |
|---|---|---|
| Shizuku | 是 | Binder 调用只有 App Java 侧能发，容器里拿不到 |
| Root | 否，只约束随包入口 | Root 管理器授权给 App UID；同 UID 的容器代码可以直接执行 `su` |
| ADB | 否，只约束随包入口；**依赖 T42** | ADB 私钥在 `/root/.dsh/adbkeys`，命令由容器 Python 发出，`su -c` 也由 guest 拼接，容器代码可以自己连 ADB；票据复核只约束 `adb-shell.py`。T42 把 ADB 连接与发送移到原生侧后，ADB 通道的确认才能和 Shizuku 一样原生强制 |

这和白名单本身的边界相同（[安全模型](../../security-model.md)「这不是内核沙箱」）：确认防的是助手**按规矩调用**时的误操作和越界，不防同 UID 的恶意代码。文档与提示词不能写成「设备命令必须经用户确认才能执行」。

## 8. 落点与实施顺序（确认后）

1. `util/DeviceGuard` + `DeviceGuardTest`（顺序锁定 + 类别 × 档位 × 通道 × 短信三态 × 确认设置 × 本轮记忆的表驱动；不变式：DENY 永不 CONFIRM、REFUSE 时 `actionExecuted` 恒 false、125 恒 `retry: never`、修订号不符永不免确认）。可先独立合入，生产不调用。
2. `util/DeviceRefusal`（§6 格式）+ `util/AgentNextAction` 共用词表（已与 T39 约定：谁先进 main 谁建文件，后到的变基复用）。`DeviceShellPolicy` 里 T39 只加读屏类命令拦截（其 §5.4.1）；本任务不改 `DeviceShellPolicy`，只读它的 Kind，避开那块。
3. `device/DeviceGuardInputs`、`device/TurnDeviceGrants`、`device/DeviceGuardBridge`；`deviceExecute` / `execOutput` 前置判定换成它。
4. ADB 票据：`/device/commit` 路由 + `adb-shell.py` 发送前核销 + `tools/test-bridge-routes.py`、`device-shell-policy` 相关 Python 单测。
5. 设置项「设备命令确认」与短信三态（中英文、布局验收）；改 m0433、m0144、`dsh-device-shell-guide` 提示词（同步 T36 / `tools/test-prompt-language.mjs` 约束锁定），插件资产改动重算 runtime-descriptor。
6. `docs/security-model.md`、`docs/device-permissions-architecture.md` 按 §7 改写。
7. 测试包真机：每类各执行一次，核对确认窗内容、本轮记忆、拒绝 / 超时 / 票据失效的回复；不做破坏性命令，写操作只在 `Download/` 下的临时文件上做。

## 9. 决定记录

用户 2026-11-03 已定：

- **Q1 确认粒度**：采用 D2，只读不问；写文件、结束应用、Root 原始 shell 要问（§4.2）。
- **Q2 Root 原始 shell**：默认每条都问，确认窗给「本轮允许」（§4.2）。
- **Q3 短信**：改为关闭 / 每次询问 / 预授权三档，默认关闭（§4.3）。
- **Q5 通道顺序（B6）**：本任务不改；另立 T41（P2，主会话已登记）对齐设备命令与 `ScreenCapabilityGate.order`，等 T39、T40 实现进 main 后再做。

- **Q4 无任务身份的调用**：照样按类别弹窗，不给「本轮允许」，每条都问（§5.1）。不选「直接执行」，因为去掉 `DSH_PHONE_SESSION_ID` / `DSH_PHONE_TURN_ID` 就能跳过确认。

- **Q6 剪贴板与外部跳转**（§4.4、B9，主会话权限审计后补）：不并入本任务，另立 T43，按 §4.4 方案做（读剪贴板三档默认关闭；`tel:` / `mailto:` / `market://` 确认、可本轮允许）。

- **Q7 Root 原始 shell 操作 DSHA 自身**（§4.5，@t39-guard 转来）：强确认。每条都问、无本轮允许、醒目提示，确认设置开关不能关掉；不直接拒绝。
