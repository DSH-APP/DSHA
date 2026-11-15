# 识屏单一守卫 ScreenGuard（T39 设计稿）

状态：**设计稿，未实现**。Q1–Q7 已由用户 2026-11-03 定（§11）。基线 build299（`a1aabfaa`），只读源码取证，未在真机复现；T35（`fix/vscreen-error-detail`）、T36（`docs/screen-prompt-consolidation`）进 main 后再动代码。
这是授权边界改动：整稿还要用户最终确认后才能写代码。

## 0. 结论

1. 把「能不能调屏、调哪块屏、虚拟屏走哪条通道、要不要弹窗」收进一个纯逻辑判定器（设备命令不在内，§5.4） `util/ScreenGuard`，按固定顺序判定，生产调用点（实体 `/app/ui/*`、虚拟屏 `/app/vscreen/*`、`/app/launch`）只调它。
2. 规则焊死在 App 原生 Java 里；用户只能在原生设置页、在焊死范围内选开关。容器、插件、MCP、助手只转述结果。
3. 弹窗过多的主要原因是「本轮允许」按**包名**记、虚拟屏**没有任务身份**、读屏也逐次确认。改成按**本轮任务**记，系统界面不单独计，虚拟屏用自己的会话身份。
4. 每个拒绝统一成 `{error, reason, screen, channel, nextAction, userAction, retry}`，nextAction 用 T35 的短词表，组合矩阵写成表驱动单测。

## 1. 现状（源码事实，build299）

| # | 现象 | 源码位置 | 影响 |
|---|---|---|---|
| A1 | 实体屏每次调用（含 dump / tree / screenshot / senseState 只读）都过 `screenAuthorization` | `HttpShellService.appUi` 约 1794 行 | MANUAL / CUSTOM 下一次任务 6–8 次调用都可能弹窗 |
| A2 | `TurnScreenGrants` 键 = 任务身份 + **前台包名** | `util/TurnScreenGrants.key` | 桌面 / 目标应用 / 通知栏 / 输入法每换一个包重新问；包名空时键为 null，什么都记不住 |
| A3 | `com.android.systemui`、`com.android.settings`、空包名都判为敏感 | `util/SensitiveAppPolicy` | 下拉通知栏也走「每 5 次再问」；读不到包名时每次都问 |
| A4 | 虚拟屏不调 begin，`PhoneControlCoordinator.snapshot().binding()` 为空 | `appVscreen` → `screenAuthorization` 里 `turnKey == null` | 虚拟屏上「本轮允许」永远不生效；确认走通知 + 悬浮条 + 前台 AlertDialog，没有「本轮允许」按钮 |
| A5 | 虚拟屏 MCP（`dsh-tool-vscreen/lib/server.cjs`）不转发 `_meta['dsha/phone-task']` | 只有 `dsh-computer-use-android` 注入任务身份 | 虚拟屏请求本身没有对话身份，守卫无从按任务记忆 |
| A6 | 允许后关窗等目标复核，截止 1500 ms；超时即 `targetChanged` | `finishAllowedConfirmation` 3600 行起 | 窗口列表刷新慢时，刚关的确认窗被判成目标变化，本次失败，助手重试又弹一次 |
| A7 | 动作前若助手窗可见：先隐藏、`requireObservation`，返回 `FRESH_SCREEN_OBSERVATION_REQUIRED` | `AssistantService.prepareScreenOperation` | 浮层出现一次就打断一次动作；这是有意的几何围栏，但回复没告诉助手「只需重新观察一次」 |
| A8 | 拒绝码分散、格式不一：`[ERR] USER_REJECTED`、`{"ok":false,"error":...}`、`ScreenReply.refused(...)`；nextAction 长短两套词表 | `appUi` / `appVscreen` / `MainScreenActions` / T35 `VirtualScreenObservationHint` | 助手难以一次选对下一步 |
| A9 | 通道顺序两套：设备命令 Root → Shizuku(`hasPermission`) → ADB；虚拟屏 `ScreenCapabilityGate.order` 在 API≥37 且 ADB 已连时 ADB 先于 Shizuku，且 Shizuku 要求用户服务已绑定 | `deviceExecute` 1065 行 / `ScreenCapabilityGate.order` | 同一台设备上「走哪条」的答案不一致，报错里也说不清用的是哪条 |

A6、A7 是浮层打断的两个来源；哪个占主导要真机取证（见 §9）。

## 2. 目标与非目标

目标：一处判定；规则焊死；同一简单任务确认次数明显下降；「本轮允许」在应用切换和虚拟屏上生效；确认窗不再导致工具报错；每个拒绝能让助手一次选对下一步。

非目标（保持不动）：
- 不删确认，不默认放宽读屏（是否免确认由用户选，见 §6）。
- 不放宽 `DeviceShellPolicy`，不改 ADR0003 不变量（最新帧、锚点一次性、semantic 与 raw 不互相 fallback）。
- UNKNOWN 不重放；不换屏兜底；守卫可以按规则**选**通道，执行失败后不自动换另一条重放。
- 不改目标复核、遮挡、代次、出生身份等执行期围栏；守卫只决定「能否进入执行」，执行期围栏照旧。

## 3. 模型

```java
// util/ScreenGuard.java —— 纯逻辑，零 Android import
public final class ScreenGuard {
  enum Screen { PHYSICAL, VIRTUAL }
  enum Kind { LIFECYCLE, OBSERVE, ACT, LAUNCH }   // task-begin/end、create/close/status 归 LIFECYCLE

  record Request(Screen screen, Kind kind, String operation,
                 String targetPackage,            // 可空：读不到时为 ""
                 String taskIdentity,             // 实体：session+turn；虚拟：见 §5.3
                 boolean channelRequired) {}      // 虚拟屏、ADB 计划等需要设备通道

  record State(ScreenModePolicy.Mode mode,
               ScreenCapabilityGate.Inputs caps,  // 无障碍、Root/Shizuku/ADB、悬浮窗、API
               ScreenConfirmationPolicy policy, boolean globalAllow,
               ReadConfirm readConfirm,           // 用户选项，§6
               boolean bridgeCurrent, boolean webRunning, boolean grantPaused,
               boolean taskBound, boolean taskPaused, boolean userComposing,
               boolean virtualCleanupPending, String virtualCoreState,
               TurnGrant turnGrant) {}            // 本轮记忆的快照（只读）

  enum Decision { ALLOW, CONFIRM, REFUSE }

  record Verdict(Decision decision, Screen screen, String channel,   // "" | root | shizuku | adb
                 Refusal refusal,                                     // ALLOW/CONFIRM 时为 null
                 ConfirmScope offerScope) {}                          // CONFIRM 时可提供的记忆范围

  static Verdict evaluate(Request request, State state);
}
```

调用点只做三件事：采样 `State`（Android 侧，`ScreenGuardInputs.sample(ctx)`）、调 `evaluate`、按 Verdict 执行或弹窗。弹窗结果回来后**重新采样再调一次**（确认不是终局，只是把 `CONFIRM` 变成本次可 ALLOW 的一次性票据）。

## 4. 判定顺序（焊死）

每一步失败即返回，后面不再看。顺序本身是规则的一部分，写进单测。

| 步 | 检查 | 拒绝码（error） | 谁能补 |
|---|---|---|---|
| G0 | 路由合法、参数无重复、Kind 可识别 | `SCREEN_REQUEST_INVALID` | 助手改参数 |
| G1 | 桥运行身份当前、Web 在跑、未在启动/停止 | `SCREEN_RUN_CHANGED` / `SCREEN_RUNTIME_UNAVAILABLE` | 等待后重试一次 |
| G2 | 识屏模式匹配屏（LIFECYCLE 的 task-end 豁免，沿用 `ScreenModePolicy.exempt`） | `SCREEN_MODE_OFF` / `SCREEN_MODE_PHYSICAL_OFF` / `SCREEN_MODE_VIRTUAL_OFF` | 用户：设置 → 设备能力授权 → 识屏 |
| G3 | 该屏的准入能力（实体：无障碍；虚拟：API≥30 + 至少一条可用通道） | `ACCESSIBILITY_NOT_ENABLED` / `ACCESSIBILITY_NOT_CONNECTED` / `VSCREEN_API_UNSUPPORTED` / `DEVICE_CHANNEL_UNAVAILABLE` | 用户开无障碍 / 授权通道；`NOT_CONNECTED` 可等待 |
| G4 | 选通道（§5.1）；虚拟屏待清理时只允许 status / close | `VSCREEN_CLEANUP_PENDING` | 助手先 status / close |
| G5 | 任务身份：实体须已 begin 且未暂停；虚拟须有会话身份（§5.3） | `SCREEN_TASK_IDENTITY_REQUIRED` / `SCREEN_TASK_NOT_BEGUN` / `ASSISTANT_LOCAL_RESUME_REQUIRED` / `SCREEN_TURN_ENDED` | 助手 begin / 用户点继续 |
| G6 | 用户正在输入（仅 ACT） | `USER_COMPOSING` / `USER_MESSAGE_ARRIVED` | 等待 / 读新消息 |
| G6a | ACT / LAUNCH 的目标是 DSHA 自身普通界面（§5.5）；OBSERVE 不在此拦 | `SCREEN_TARGET_SELF_APP` | 用户自己操作 |
| G7 | 确认：全局同意 / ALLOW_ALL / CUSTOM 规则 / 读屏选项 / 本轮记忆 → ALLOW；否则 CONFIRM | —（CONFIRM 不是拒绝） | — |
| G8 | 确认结果：拒绝 / 无人应答 / 确认通道不可用 | `USER_REJECTED` / `USER_NO_ANSWER` / `USER_CONFIRM_UNAVAILABLE` | 拒绝不重试；无应答可再问一次 |

G7 之后的执行期失败（目标变化、遮挡、帧过期、UNKNOWN）不归守卫判定，但**回复格式**统一走 §7。

## 5. 屏与通道

### 5.1 通道选择（焊死）

只管识屏（虚拟屏 Core 的启动与输入通道）；实体屏只用无障碍，不选设备通道。沿用 `ScreenCapabilityGate.order`：

- 顺序：Root → Shizuku → ADB；API≥37 且 ADB 连接已验证时 ADB 先于 Shizuku（沿用现状理由：Android 17 上 Shizuku 用户服务可能提前退出）。
- 「可用」用严格判据 `channelUsable`；已运行虚拟屏会话的保活用宽判据 `channelAuthorized`（不变）。
- 守卫只**选一次**，结果写进 Verdict.channel 和回复；执行失败返回该通道的失败码，**不**换下一条重放。助手若要换通道，需要用户改授权后重新请求。

设备命令（`/exec`、`adb-shell`）不在本守卫内，见 §5.4。

### 5.2 屏的选择

不由守卫「推断」：工具本身决定屏（`mcp__dsha-android__*` / `/app/ui/*` = 实体；`mcp__dsha-vscreen__*` = 虚拟）。守卫只校验它与识屏模式一致，不一致即 G2 拒绝，不转发到另一块屏。

### 5.3 虚拟屏的任务身份

现在虚拟屏请求没有对话身份（A4、A5）。方案：

- `dsh-tool-vscreen` 的工具调用改为与 `dsh-computer-use-android` 相同的方式经插件包装注入 `_meta['dsha/phone-task']`，server.cjs 把 `taskSessionId` / `turnId` 带到 `/app/vscreen/*`。
- 原生侧虚拟屏任务身份 = `taskSessionId + turnId + coreInstance`。Core 重建（新 coreInstance）后记忆失效。
- 虚拟屏**不需要** begin / 隐藏助手窗（它不遮挡虚拟屏），但需要身份才能记忆；没有身份的老客户端照旧逐次确认，不报错（兼容）。
- 虚拟屏确认改走 `AssistantConfirmationWindow`（有「本轮允许」按钮），没有悬浮窗权限时退回现有通知 / 对话框路径。

### 5.4 设备命令（已拆出，不在本守卫内）

**现状（源码事实，build299）**

| 环节 | 位置 | 做什么 |
|---|---|---|
| 主机命令守卫 | `util/HostDeviceCommandGuard` | 先拒一批主机侧危险命令 |
| 白名单解析 | `util/DeviceShellPolicy.inspect` | 只接受可识别的单条 argv，分类为 READ / FILE / STOP / SENSITIVE_READ / VIRTUAL_SCREEN / SHELL / DENY；默认拒绝 |
| 档位 | `ConfigStore.rootPolicyMode`：STRICT（默认）/ RELAXED / FULL | 只对**真实可用的 Root**生效；Shizuku / ADB 恒为 STRICT |
| 短信 | `devicePlan` 1236 行 | `smsReadAllowed()` 关 → DENY；开 → 直接放行（`authorization: remembered`），只走 Root / ADB |
| 选通道 | `deviceExecute` 1065 行 | Root（enabled + present）→ Shizuku（`hasPermission`）→ ADB（enabled）；SHELL 只走 Root 或 ADB+su |
| 执行侧复核 | `DeviceShellExecutor`、`device-shell-policy.py` | 在实际身份下复核路径、应用清单、停止目标 |
| 失败语义 | 文本 + `[EXIT=N]` | 126 `POLICY_BLOCKED`、124 `DEVICE_CHANNEL_UNAVAILABLE`、125 `EXECUTION_UNKNOWN` |

和识屏相比的四处不同：

1. **原生侧没有任何逐次确认**。白名单通过即执行，没有 `CapabilityBroker` 请求、没有弹窗。提示词说短信「默认逐次确认」，实际是容器里 DSH 的工具审批（权限预设 / Auto 审核）在问，`danger-full-access` 或自动审核下这一层就不存在；原生只有短信预授权开关。这和「规则焊死在壳上」不一致。
2. 通道顺序与虚拟屏不一致（A9）：这里 Shizuku 只看 `hasPermission`，虚拟屏要求用户服务已绑定；API≥37 时虚拟屏 ADB 优先，这里不是。
3. 没有任务身份：设备命令不绑定对话 / 本轮，无法做「本轮允许」，暂停 / 停止手机任务也围栏不到它。
4. 错误是文本码，没有 `nextAction` / `userAction`。

**决定（用户 2026-11-03）：原生设备命令和识屏拆开。**

- ScreenGuard 只管识屏：实体屏 `/app/ui/*`、虚拟屏 `/app/vscreen/*`、`/app/launch`。不碰 `deviceExecute` / `devicePlan`、`DeviceShellPolicy`、档位与短信开关。
- 识屏的确认策略、本轮记忆、读屏选项都不作用于设备命令；设备命令将来要加的任何确认也不作用于识屏。两边各自的修订号互不影响。
- 设备命令的确认与报错另立任务（建议编号 T40，由主会话登记），上面的现状表和下列候选方向留给那个任务继续讨论，本稿不做决定：
  - D1 只统一报错格式，不加确认，提示词和安全模型照实写「原生不确认」；
  - D2 按类别加原生确认（写文件、结束应用、读短信、放宽档原始 shell），独立的 `DeviceGuard`；
  - 原 D3（并入 ScreenGuard）因本决定作废。
- 通道顺序：识屏沿用 `ScreenCapabilityGate.order`，本任务不改设备命令的选通道逻辑；两边顺序不一致（A9）记作已知差异，交给 T40。
- 递归 READ 跟随子链接的已知缺口（[权限架构](../../device-permissions-architecture.md) §6.2）同样不在本任务范围。

#### 5.4.0 见 §5.5：自身界面与会话绑定（权限审计补充）

#### 5.4.1 唯一交叉点：读屏类设备命令（用户 2026-11-03 定）

规则：**读屏没有允许时，读屏类设备命令一律拦截；其他设备命令照旧按档位处理。** 理由：设备 shell 能绕过无障碍和确认窗直接截屏或导出界面，不拦就等于读屏开关形同虚设。

「读屏已允许」= 识屏模式为 PHYSICAL **且** 实体屏只读请求在 G7 能直接 ALLOW（全局同意、ALLOW_ALL，或读屏选项为「不确认读屏」）。shell 没有确认窗，也没有任务身份，所以「需确认」在这里按「未允许」处理，不弹窗。虚拟屏模式下，读屏类设备命令同样拦截（这些命令读的是实体屏）。

读屏类命令清单（焊死在 `DeviceShellPolicy`，建议值，实现前再核一遍）：

| 命令 | 现状 | 归类 |
|---|---|---|
| `screencap`、`screenrecord` | 严格档不认识 → 已拒；放宽 / 完全放宽档可执行 | 读屏 |
| `uiautomator dump` | 同上 | 读屏 |
| `dumpsys activity top` | 严格档放行 | 读屏（含顶层界面的视图树与文字） |
| `dumpsys window` / `window windows`、`dumpsys activity` / `activities` / `recents`、`dumpsys accessibility` | 前四个严格档放行 | 前台元数据：暴露当前应用和窗口标题，不含界面文字。**不算**读屏，照旧放行（Q6 已定） |
| `input`（tap / text / keyevent / swipe）、`monkey` | 严格档不认识 → 已拒；放宽 / 完全放宽档可执行 | 操作，不算读屏，按档位处理（Q7 已定） |

判定方式：
- **严格档**：argv 已解析，按上表精确匹配，命中且读屏未允许 → `DENY`，返回码 `SCREEN_READ_NOT_ALLOWED`（`[POLICY_BLOCKED]` / 126），`userAction` 写明去哪里开。
- **放宽 / 完全放宽档（Root 原始 shell）**：命令是整串文本，只能像短信那样按关键字拦截（`smsSurface` 的做法）：出现 `screencap`、`screenrecord`、`uiautomator`、`dumpsys activity top` 就拒。这是**尽力拦截**：完全放宽档本来就不检查命令内容，原始 shell 可以拼接或编码绕过关键字，这一点要写进安全模型，不能宣称拦得住。
- 判定只读识屏状态，不改识屏状态；设备命令仍不进入 ScreenGuard 的确认和本轮记忆（§5.4 拆开的决定不变）。

单测：`DeviceShellPolicyTest` 加「读屏允许 × 档位 × 命令清单」表，并锁住「严格档未允许时 `screencap` 带任何参数都拒」「放宽档关键字命中即拒」两条。

### 5.5 自身界面与会话绑定（主会话权限审计 2026-11-03 第 5、6 条）

审计原文在 `.agents/claims/T42/perm-audit-20261103.md`。下面是本会话只读源码核实的结果（build299 / build301，未上真机）。

**第 5 条：识屏目标没有排除 DSHA 自身的普通界面。核实属实。**

- `ScreenTargetResolver.physical`（113–132 行）判断出 `ownPackage` 后，只交给 `OwnedScreenOverlay.match`（已登记的自有悬浮窗）和 `MainScreenWindowPolicy.ownFallbackExclusion` 处理。后者在 `focused == true` 时原样返回，不排除。所以前台获得焦点的 DSHA Activity（主界面、设置、设备能力授权页）会被选为识屏目标。
- `SensitiveAppPolicy` 里没有 DSHA 包名。`appLaunch` → `AppDeviceActions.launch` 也不拒绝启动自身。只有 `/app/install` 拒绝覆盖安装自身，那条和这里无关。
- `screenAuthorization` 先判 `policy.allows(pkg)`。在 ALLOW_ALL 或全局同意下，这里直接放行，不看是否敏感。
- 布局里没有找到 `filterTouchesWhenObscured` 或「拒绝无障碍注入」一类的防护。授权页的开关只要无障碍点得到，就能被助手切换。
- 结论：在 ALLOW_ALL 或全局同意下，助手可以 `launch` DSHA，再点「设备能力授权」给自己开识屏、Root 或短信。这破坏了「能写容器的助手不能给自己加权限」这条前提。真机可行性还没验证。

**处置（焊死项；Q8 用户 2026-11-03 已定）**

- G6a：目标包等于 DSHA 自身包名（含 `.test` 变体，取 `Context.getPackageName()`）时，ACT 一律拒绝，返回 `SCREEN_TARGET_SELF_APP`、`retry: never`、`nextAction: REPORT_TO_USER`，`userAction` 写明「请你在 DSHA 里自己操作」。这一条不受 ALLOW_ALL、全局同意或本轮记忆影响。
- LAUNCH 自身包名也拒绝，实体屏 `/app/launch` 和虚拟屏 `launch` 都算。
- OBSERVE 自身界面（截图、读树）：**放行**，按普通应用走确认策略和本轮记忆（Q8 已定）。用户的依据是：配置页基于 DSH 配置，不直接显示 API Key；令牌和端口是动态的；最新 UI 已经做了遮挡；DSH 本身不重复显示敏感值。
- 以后如果某个页面确实会显示敏感值，就给那个页面单独加「读屏受限」（页面级标记，命中时 OBSERVE 返回 `SCREEN_TARGET_RESTRICTED_PAGE`），不收紧整个 DSHA。本任务不预设具体页面。
- 自有悬浮窗（助手窗、确认窗）照旧按排除区处理，不算目标，不受本条影响。
- 设备命令 `am start` 拉起自身属于 T40 / T42 的范围，本条只在识屏侧拦截。另外提醒 T40：ALLOW_ALL 下系统「设置」（无障碍 / 悬浮窗授权页）同样不再询问。这是确认策略本身的语义，不在本条修复范围，但要在安全模型里如实写明「ALLOW_ALL 下助手可操作系统设置」。

**第 6 条：任务身份是会话绑定，不是调用者认证。**

- `taskSessionId` / `turnId` 由容器侧参数传入（`/app/ui/*` 的 query，或 MCP `_meta['dsha/phone-task']`）。按审计记录，原生核对用的会话状态也来自容器插件 `dsh-status-overlay`，这一点本会话没有另读源码复核。
- 所以 `TurnScreenGrants`、§5.3 的虚拟屏身份、§6.1 的「本轮任务」都只能说明「这次请求自称属于哪一轮」，不能证明「是哪个调用者发来的」。同 UID 的任何容器进程只要拿到或猜到当前身份，都能共享本轮记忆。
- 设计上的含义：
  - 本轮记忆只用来省弹窗，不能当作授权的唯一依据。真正的边界仍是识屏模式、确认策略、桥 token、运行身份、执行期围栏，以及本节的自身界面拒绝。
  - 本轮记忆在暂停、停止、修订号变化、本轮结束时失效（§6.1），把被冒用的时间窗限制在一轮之内。
  - 文档、提示词和回复里一律写「会话绑定」，不写「调用者认证」或「仅当前对话可用」。

## 6. 弹窗：本轮记忆与读屏选项

### 6.1 记忆范围

`TurnScreenGrants` 的键从「任务 + 包名」改为「任务身份 + 确认策略修订号」，值记录已允许的范围：

| 前台 | 本轮允许后 |
|---|---|
| 普通应用（含切换到另一个普通应用） | 免确认 |
| 桌面、输入法 | 不单独计，免确认 |
| `com.android.systemui`（通知栏 / 快捷设置） | 移出敏感名单（用户 2026-11-03 定），按普通应用计，本轮允许后免确认 |
| 敏感应用（`SensitiveAppPolicy`，去掉 systemui 后；`com.android.settings` 仍在） | 每个敏感包首次进入时单独确认一次，之后每 5 次再问（保留现有额度） |
| 读不到包名 | 归入「未知」桶，按敏感处理：确认一次后每 5 次再问（不再每次都问） |

失效条件不变：本轮结束、暂停、停止、确认策略修订号变化；虚拟屏另加 Core 重建。记忆只省弹窗，执行期围栏全部照旧。

按钮文案：「本轮允许此应用」改为「本轮允许（敏感应用另问）」。

### 6.2 读屏是否免确认（用户选，默认要确认；Q1 已定）

新增原生设置项「读屏（截图 / 读控件树）确认」：

- **跟随确认策略**（默认，同现状）：读屏与动作同样确认。
- **本轮首次确认**：本轮第一次读屏问一次，之后读屏免确认；动作仍按确认策略。
- **不确认读屏**：只读调用免确认；动作仍按确认策略；敏感应用的读屏仍问（截图可能含余额 / 验证码）。

该项和确认策略一样只存在 App 原生偏好里，改动即递增修订号、废掉本轮记忆。

### 6.3 确认窗关闭后的稳定窗口

针对 A6：
- 关窗后先等 `OwnedScreenOverlay` 中 `assistant-confirm` 条目移除、且无障碍窗口列表里不再有该 windowId，再开始目标复核；等待截止从 1500 ms 改为「窗口移除确认后再给 600 ms 稳定期」，总上限 3000 ms。
- 上限内仍不一致：返回 `SCREEN_TARGET_CHANGED` + `reason: CONFIRM_WINDOW_NOT_SETTLED` + `nextAction: SEE_AGAIN`，并标明 `actionExecuted: false`、`confirmationConsumed: false`——同一任务内下一次相同请求直接走本轮/一次性票据，不再弹窗（票据绑定原目标与 30 s 有效期）。

针对 A7：保持「助手窗可见 → 隐藏 → 要求重新观察」的围栏，但回复改为 `FRESH_SCREEN_OBSERVATION_REQUIRED` + `reason: ASSISTANT_WINDOW_HIDDEN` + `nextAction: SEE_AGAIN`，并说明本次未执行、重新观察后可直接动作，不会再弹确认。

## 7. 统一错误语义

所有屏幕相关拒绝（守卫与执行期）统一为：

```json
{
  "ok": false,
  "state": "REFUSED | NOT_SENT | EXECUTED_UNVERIFIED | UNKNOWN",
  "error": "SCREEN_MODE_VIRTUAL_OFF",
  "reason": "细分原因，可空",
  "screen": "physical | virtual",
  "channel": "root | shizuku | adb | accessibility | \"\"",
  "actionExecuted": false,
  "retry": "never | once_after_wait | after_observation | after_user",
  "nextAction": "REPORT_TO_USER",
  "userAction": "设置 → 设备能力授权 → 识屏 → 选择「虚拟」"
}
```

- `nextAction` 统一用 T35 短词表，并补齐：`SEE_AGAIN`、`WAIT_AND_SEE_AGAIN`、`CREATE_FIRST`、`LAUNCH_APP_FIRST`、`LAUNCH_APP_OR_WAIT`、`CHECK_STATUS`、`ENABLE_ACCESSIBILITY`、`WAIT_ACCESSIBILITY_RECONNECT`、`ASK_USER_TO_CLOSE`、`REPORT_TO_USER`，新增 `BEGIN_TASK`、`WAIT_USER_MESSAGE`、`READ_USER_MESSAGE`、`ASK_USER_TO_RESUME`、`STOP`。实体屏现有长词（如 `CLEAR_OCCLUSION_OR_USE_FRESH_TREE_NODE`）保留在 `reason` 旁的 `detail` 字段一个版本，再删。
- 词表归属：nextAction 词表抽成共用的 `util/AgentNextAction`（枚举 + 线上字符串，配单测锁住取值），T39 与 T40（设备命令）共用一张。谁先进 main 谁建，后到的变基复用，不另起一份。
- `userAction` 只在需要用户动手时给，文字走 `UiText`（中英文），写明设置入口路径。
- `UNKNOWN` 一律 `retry: never`，并带 `executionUnknown: true`。
- 旧格式兼容（Q5 已定）：`[ERR] CODE` 文本前缀和实体屏长 nextAction 词只保留一个版本，下一版直接删除。删除那一版同时发一份声明 `docs/releases/screen-error-format-removal-<日期>.md`，写明删了什么（前缀格式、长词清单、涉及的端点）、用什么替代（`error` / `nextAction` 字段）、插件和脚本需要怎么改。兼容版内插件改为只按 JSON 的 `error` 字段转述。

## 8. 组合矩阵（单测骨架）

表驱动单测 `ScreenGuardTest`，每行一个 `(State, Request) → Verdict`。维度与取值：

| 维度 | 取值 |
|---|---|
| 识屏模式 | OFF / PHYSICAL / VIRTUAL |
| 请求屏 | PHYSICAL / VIRTUAL |
| Kind | LIFECYCLE(task-end) / LIFECYCLE(其他) / OBSERVE / ACT / LAUNCH |
| 无障碍 | 未启用 / 启用未连接 / 已连接 |
| 通道 | 无 / 仅 Root / 仅 Shizuku(PERMITTED) / 仅 Shizuku(READY) / 仅 ADB(未连) / 仅 ADB(已连) / 全有；API 30 与 37 各一组 |
| 虚拟屏状态 | 无 Core / RUNNING / 待清理 |
| 任务身份 | 无 / 有未 begin / 已 begin / 已暂停 / 本轮已结束 |
| 确认策略 | 全局同意 / ALLOW_ALL / CUSTOM(命中) / CUSTOM(未命中) / MANUAL |
| 读屏选项 | 跟随 / 首次 / 不确认 |
| 本轮记忆 | 无 / 普通已允许 / 敏感已用 4 次 / 敏感已用 5 次 / 修订号已变 |
| 目标包 | 普通 / 桌面 / systemui / 敏感 / 空 |

全积约 10⁶，不穷举。单测分三层：
1. **顺序锁定**：每一步 G0–G8 至少一组「前面全过、本步失败」与「本步过、下一步失败」，锁住判定顺序（约 40 行）。
2. **成对覆盖**：对上表用 pairwise 生成（约 60 行，生成器写在测试里、固定种子），断言 Verdict 与错误字段齐全（error / screen / channel / nextAction 非空；需要用户时 userAction 非空）。
3. **不变式**：任意组合下 —— 模式不匹配永不 ALLOW；VIRTUAL 请求永不选空通道后 ALLOW；Verdict.screen 恒等于 Request.screen；UNKNOWN 永不 `retry != never`；本轮记忆修订号不符永不免确认。

## 9. 落点与实施顺序（确认后）

1. `util/ScreenGuard` + `ScreenGuardTest`（纯逻辑，可先独立合入，生产不调用）。
2. `util/AgentNextAction`（若 T40 尚未建）+ `util/ScreenRefusal`（§7 格式）+ 测试；`DeviceShellPolicy` 只加 §5.4.1 读屏类拦截，T40 的按类别确认在 `util/DeviceGuard`，互不改对方部分；`TurnScreenGrants` 改键（§6.1）+ 测试。
3. `screen/ScreenGuardInputs.sample(ctx)`：把现在散在 `screenAuthorization` / `ScreenMode.bridgeRejection` / `ScreenCapabilities.sample` 的采样收拢。
4. `HttpShellService`：`appUi`、`appVscreen`、`appLaunch` 的前置判定换成 `ScreenGuard.evaluate`；`screenAuthorization` 只留弹窗与票据。不往 HttpShellService 加新职责，新逻辑放 `screen/ScreenGuardBridge`。
5. 确认窗稳定窗口（§6.3）、读屏选项设置页（§6.2，含中英文与布局验收）。
6. `dsh-tool-vscreen` 注入任务身份（§5.3）；提示词与 `tools/test-prompt-language.mjs` 同步 T36 约束锁定。
7. 测试包真机：同一简单任务前后确认次数对比。

取证（实现前，测试包上做，需要用户操作手机）：
- 记录一次「打开设置 → 找到某项 → 返回」任务的确认次数、每次的前台包名、失败码；区分 A6 与 A7 各占几次。
- 虚拟屏同一任务的确认次数。

## 10. 焊死项 / 用户可调项

| 焊死（原生代码，容器 / 插件 / 助手不能改、不能下发） | 用户可调（原生设置页，只能在左侧范围内选） |
|---|---|
| 判定顺序 G0–G8 | 识屏模式：关闭 / 实体 / 虚拟 |
| 模式不匹配即拒绝、不换屏 | 确认策略：手动 / 自定义按应用 / 全部允许 |
| 通道顺序与各能力的允许通道子集 | 全局同意开关（已有） |
| 执行失败不换通道重放、UNKNOWN 不重放 | 读屏确认：跟随 / 本轮首次 / 不确认（新增） |
| 敏感应用名单与「每 5 次再问」 | Root / Shizuku / ADB 各自授权（已有） |
| 拒绝操作 / 启动 DSHA 自身普通界面（§5.5，不受 ALLOW_ALL / 全局同意影响） | — |
| 任务身份只算会话绑定，本轮记忆不作为唯一授权依据（§5.5） | — |
| 本轮记忆失效条件（结束 / 暂停 / 停止 / 修订号 / Core 重建） | 无障碍、悬浮窗（Android 系统授权） |
| 执行期围栏：最新帧、目标复核、遮挡、代次、锚点一次性 | — |
| 错误格式与 nextAction 词表 | — |
| 读屏未允许时拦截读屏类设备命令，以及该命令清单（§5.4.1） | — |

理由：容器、插件、助手和 App 同 UID（见 [安全模型](../../security-model.md)「这不是内核沙箱」），能写容器文件的助手不能给自己加权限，所以规则不读任何容器侧配置。

## 11. 决定记录

用户 2026-11-03 已定：

- **Q1 读屏确认默认值**：默认要确认，即「跟随确认策略」（§6.2）；另两档留给用户自己选。
- **Q2 本轮记忆范围**：本轮允许 = 本轮所有普通应用 + 桌面 / 输入法 / 通知栏，敏感应用另问（§6.1）。
- **Q3 systemui**：通知栏、快捷设置移出敏感名单，按普通应用计（§6.1）。原因：常因这类确认卡住。
- **Q5 旧格式**：`[ERR] CODE` 文本前缀只保留一个版本，下一版直接删掉，并在 `docs/releases/` 发一份删除声明，列出被删的前缀格式、长 nextAction 词、受影响的端点，以及替代字段（§7）。

- **Q4 设备命令**：原生设备命令和识屏拆开（§5.4）。ScreenGuard 不碰 `deviceExecute`，设备命令的确认与报错另立任务。唯一交叉点：读屏没有允许时，读屏类设备命令被拦截，其他命令按档位处理（§5.4.1）。

- **Q6 前台元数据**：`dumpsys window` / `activity activities` 等只暴露当前应用和窗口标题，不算读屏，照旧放行（§5.4.1）。
- **Q7 输入注入**：`input tap` / `input text` 不算读屏，按档位处理：严格档不认识照旧拒绝，放宽 / 完全放宽档的 Root 原始 shell 可执行；识屏模式不影响它。

权限审计补充（2026-11-03）：

- **Q8 DSHA 自身界面**：操作和启动自身一律拒绝，焊死（G6a）。读自身界面放行，按普通应用走确认策略。依据：配置页不直接显示 API Key，令牌和端口是动态的，最新 UI 已做遮挡，DSH 不重复显示。以后某页确有敏感值时，再给那一页单独加读屏受限（§5.5）。
