# 物理屏动作提速：动作即观察 / 稳定编号+差异 / 事件驱动 settle（T105）

状态：**实现完成，未真机测量、未 push/合并/发布**。本文只陈述代码与单测能证明的范围；真机时延与各家 ROM 的
无障碍事件行为以实际设备回执为准，不从参考报告或本机推断。

## 1. 背景与基线

物理屏（display 0）操作链路原来是一个动作一次工具调用、再单独读一次屏：

- 动作：`/app/ui/node`、`/app/ui/tap`、`/app/ui/input`、`/app/ui/key`、`/app/ui/swipe`，成功后只返回
  `SCREEN_ACTION_SUBMITTED` / `actionAccepted:true` / `effectVerified:false`，提示模型「重新观察」。
- 观察：`/app/ui/dump`、`/app/ui/tree` 每次调用都重新遍历无障碍树，签发新的 `treeId` / `nodeId`。
- 凭据：`treeId` + `nodeId` 一次性、30 秒、绑定任务 / 窗口 / 服务代次；坐标手势只认最新 `android_screenshot`
  的 `observationId`；`k***` 预览编号从不是凭据。
- 动作链路本来就没有固定 sleep；本次的 settle 是**新增**等待，不是替换现有等待。

因此提速空间不在「换更快的读屏守护进程」（本项目本来就是进程内 `AccessibilityService`，没有每次启动
`uiautomator dump` 的代价），而在**少一个模型回合**、少回重复文本、以及把「等界面稳定」做成有界的事件等待。

## 2. 三项改动

### 2.1 事件驱动 settle

`util/ScreenSettlePolicy`（纯判定，无 Android 依赖）+ `util/ScreenEventPulse`（事件记录与有界等待）。

- 预算：动作派发后首个无障碍事件 ≤ 800ms；出现事件后等 250ms 静默；整段等待硬上限 2.5s。
- 没有事件：首个事件预算到点就按「界面没动」结束，不空等 2.5s。
- 事件只在等待期间记录（最近 64 条，只存时间 / 窗口号 / 包名），没有等待者时 `record` 直接返回，不构成
  读屏缓存；不保存任何节点或文本。
- 等待在监视器上用 `wait/notify` 唤醒，按策略边界有界等待，不忙轮询；任务暂停、撤销、请求截止都会提前结束。
- 回执字段 `settle` 如实写 `settled` / `timedOut` / `sawEvent` / `firstEventMs` / `quietMs` / `totalMs`
  / `events` 与三项预算。界面持续动画时标 `timedOut:true`，不把「没等到静默」说成「没有变化」。

### 2.2 动作即观察（`observe=1`）

`/app/ui/node|tap|input|key|swipe` 增加可选参数 `observe=1`（MCP 动作工具默认带上，可用
`observe:false` 关闭）。成功且已验证完成（`state=COMPLETED`）时，同一次请求内：

1. 先按 2.1 等界面稳定；
2. 再重新解析当前目标并读一份**新树**，签发全新的 `treeId` / `nodeId`；
3. 在动作回执里附 `observation`：`kind=dsha-screen-after-action-v1`、`treeId`、`text`（同 dump 的文字树）、
   `nodes`、`target`、`settle`，以及 `screenshotAvailable:false`。

约束与边界：

- **坐标动作不返回截图**。`observation` 没有 `observationId`，坐标操作仍必须重新 `/app/ui/screenshot`；
  动作回执里的 `coordinateAuthorization` 保持 `SCREENSHOT_OBSERVATION_REQUIRED`。
- **新观察只属于这份新树**：旧 `treeId` / `nodeId` 在动作完成时已作废；回执里的新租约在本次请求结束后仍可用，
  下一次语义动作可以直接消费。凭据规则（一次性 / 30 秒 / 任务 / 窗口 / 服务代次）没有被放宽。
- **观察复用本次动作的授权**：读屏发生在同一次动作请求内部，只读本次动作已授权的同一目标窗口；不新增授权面、
  不向其它窗口读屏、不改变「本轮允许此应用」等确认策略。
- **观察失败不改写动作结果**：只追加 `observationUnavailable`（如 `SCREEN_TARGET_CHANGED`、
  `SCREEN_OBSERVATION_DEADLINE`）与 `nextAction:OBSERVE_A_FRESH_SCREEN`。动作的 `COMPLETED` / `UNKNOWN`
  / `NOT_SENT` 保持原样，UNKNOWN 仍不得重放。
- **目标窗口换了就读不了**：动作打开了新窗口时，原授权绑定的是旧窗口目标，不能拿旧凭据读新窗口；此时返回
  `observationUnavailable:SCREEN_TARGET_CHANGED`，模型重新获取观察即可。这是有意的安全边界，不是缺省
  兜底：不借用旧凭据、不静默降级到别的屏。
- **凭据失效也能拿到新树**：`STALE_NODE`、`FRAME_NOT_OBSERVED`、`STALE_FRAME`、`EDITOR_CHANGED`、
  `NODE_NOT_FOUND`、`INPUT_NOT_FOCUSED` 这些「动作未执行」的拒绝会直接附当前屏的新树（`staleObservation:true`），
  让调用方用新 `treeId` / `nodeId` 重试而不是重放旧凭据；其余拒绝（含用户拒绝、任务变化）不附观察。
- 请求截止前预留 1s 给读树；预算不够时只回动作回执并标 `SCREEN_OBSERVATION_DEADLINE`，不把回执拖成超时。
- 旧调用方不传 `observe` 时序、字段、租约清理与本改动前完全一致。

MCP 侧（内置 `dsh-computer-use-android`）：

- 7 个动作工具新增可选 `observe`（boolean，可省略）；省略时默认附带观察，`observe:false` 关闭。
- 动作工具描述与服务说明同步改写：成功后直接利用回执里的 `observation`；只有观察不可用、目标切换或
  UNKNOWN 时才重新读屏。
- 输入 schema 是**加字段**：老调用方不传 `observe` 仍合法；返回体也只是多一个 `observation` 字段。

### 2.3 稳定编号 + 差异（`/app/sense/state`）

`util/ScreenSenseDeltaPolicy` + 已有的 `ScreenSenseCodec.diff/encodeDelta`（本次新增预计算差异的编码重载）。

- `mode=full`：全量（首次 / 加 `full=1` / 窗口或尺寸、焦点、输入法、来源、截断状态变化 / 差异不可用）。
- `mode=delta`：只列变化行（`~` 修改、`+` 新增、`-` 移除），前缀 `D <sid> <previousSid> <changed>`。
- `mode=same`：与上次相同，只有头部一行，`unchanged:true`、`nextAction:SCREEN_UNCHANGED`。
- 变化超过三分之一（`changed * 3 > max(前后元素数)`）回全量；顺序漂移会表现为多个位置变化，由阈值兜住。
- `k***` 仍只是阅读顺序里的位置编号，只用于预览与差异展示，**不是动作凭据**；动作仍使用 `treeId` / `nodeId`
  租约。格式升为 `v4-preview-3`，`encodedState` 的内容由 `mode` 决定（全量或 `D` 开头的差异）。
- 连续的两次读屏之间不插入动作；动作会使旧预览失效，下一次读屏回全量。缓存只按完整任务 owner、无障碍代次和
  窗口身份保存，30 秒过期，不作为授权。
- `tools/screen-eval/device_probe.py` 固定加 `full=1`，避免探针把差异当全量统计。

## 3. 明确不做的

- 不引入 root / Shizuku / ADB 常驻采集进程，不改 `UiAutomation`；物理屏仍走进程内无障碍服务。
- 不把 `k***` 或差异行提升为动作凭据，不缩短 30 秒有效期，不跨观察复用旧 `treeId` / `nodeId`。
- 不碰虚拟屏 Core 与 ADR0003 的帧 / 节点 / anchor 身份。
- 不复制参考实现的源码、包名、链接；本文只称「外部参考实现（结构参考）」，也不把对方设备上的时延数字
  当作本项目结论。
- 没有固定 sleep 需要「删除」；settle 是新增等待，只在 `observe=1` 时发生。

## 4. 验证

单测（纯逻辑，不依赖真机）：

- `ScreenSettlePolicyTest`：首个事件预算、静默窗口、总上限、旧事件忽略、时钟倒退、回执字段。
- `ScreenEventPulseTest`：事件后静默返回、无事件按首个预算返回、持续事件按总上限超时、目标过滤、撤销与截止。
- `ScreenSenseDeltaPolicyTest`：全量 / 差异 / 相同、恰好三分之一、超过三分之一、窗口/尺寸/代次/目标变化、
  编码重载与双参数编码一致。
- 既有 `ScreenSenseStateTest` / `ScreenSenseBudgetTest` 编码摘要不受影响（只改增量路径与新增重载）。

未做：真机测量动作到稳定回执的耗时、各家 ROM 的事件密度、MCP 侧整体 token 变化。这些需要用户授权在实体机上
操作并记录；在拿到数据前不声明「提速若干倍」。

## 5. 兼容与回退

- 桥：不传 `observe` 即完全旧行为；`/app/sense/state` 加 `full=1` 即旧的全量语义。
- MCP：`observe:false` 关闭自动观察；插件回退到上一版即恢复「每次动作后单独读屏」。
- 若真机发现某类 ROM 事件过密导致 settle 经常 `timedOut`，只影响 `settle` 元数据；观察仍是动作后新生成的
  新树，调用方可用 `observe:false` 或直接读屏。
