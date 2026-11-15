# 物理屏两套观察路径设计评估（T107，2026-11-07）

> 状态：**设计评估，未实现**。本稿只做方案对比、接口/授权/兼容/回退设计与分阶段计划；不写产品代码、不 push、不合并、不安装、不操作手机。
> 基线：`origin/main` `61245640`（build337 之后的 UI 第二批已合入）。源码取证为只读；**未在真机验证**抓帧、evdev 或各通道权限。
> 参考方式：只取外部参考实现的**结构**，不复制源码；对外文档不写参考项目名称、包名、作者或链接，统一称「外部参考实现（结构参考）」。
> 前置：与 T105「动作即观察 / 稳定编号+差异 / 事件驱动 settle」按顺序实施，见 §8.5。方向 C 已由用户确认；本稿剩余待确认项见 §10。

## 0. 结论

1. **推荐方向 C：实体屏特权镜像读屏 + evdev 用户物理输入检测**，作为 physical 识屏模式下的**子能力**，不新增第三种识屏模式，不与 virtual 模式互斥关系冲突。
2. **C 分两半，可以拆开落地**：
   - **用户操作检测（不依赖画面）**：特权 Core 读 `/dev/input/event*`，只识别物理触摸与导航键；AI 的无障碍 `dispatchGesture`、`performTextInput` 与 `InputManager.injectInputEvent` 不经过 evdev，天然可区分。检测到物理输入立即推进「用户介入 epoch」，使未消费的 `treeId` / `nodeId` / `observationId` 失效，工具响应附带自上次观察以来的 `userActivity`。
   - **镜像帧（观察面，不是输入面）**：特权 Core 抓 display 0 的合成帧，按需返回低帧率 JPEG；人类预览可高帧率。镜像帧**永不签发坐标/动作凭据**，触摸仍只认 `/app/ui/screenshot` 的 `observationId`，ADR0003 的不变量不改变。
3. **A 轻量方案保留为无特权降级**：无障碍树 + 按需截图 + T105 的动作后新观察。它能减少回合、不能可靠发现「用户正在手动操作」；工具必须把它标成 `userActivity=UNKNOWN`，不能把「没检测到」说成「没有操作」。
4. **B（MediaProjection 镜像）本期不推荐**：逐次系统授权、前台服务与隐私指示、同一时间一个投影会话（Android 14+ 每次会话重新授权），而且它仍拿不到原始触摸；用帧差推断「用户动过」会与 App 自身动画混淆，无法满足「用户一碰就停手」的核心要求。可作为将来「人类录屏/预览」的独立选项，不与 C 混用。
5. **授权默认关、按任务激活、可撤销、失败 fail-closed**：新增「读取实体屏画面」与「监听用户触摸（不记录文字）」两个敏感开关；没有 Root/Shizuku/ADB 或通道不可用时，功能返回明确不可用，**不回退成无障碍假装可用**。
6. **不做**：第三种识屏模式；把实体屏动作改成特权注入（本期）；多指缩放注入；常驻录像/落盘；镜像输入；任何跨屏兜底。

## 1. 背景与目标

### 1.1 要解决的真实场景

用户的核心诉求是**共操与打断**：AI 正在操作实体屏时，如果点错、分析错或用户改主意，用户会直接用手在实体屏上操作。此时 AI 必须：

1. 尽快知道「用户自己动了」（而不是继续按旧计划盲点）；
2. 停手或重规划，并且**不能**继续消费用户操作之前拿到的树/截图凭据；
3. 如果能定位用户点了哪个控件，优先用它理解用户意图；定位不了也要如实说「只看到坐标」。

此外还有一个次级场景：AI 空闲时用户直接操作手机，AI 下一次工具调用应立刻看到「刚刚有用户操作」，而不是等用户发消息。

### 1.2 现状为什么不够

- 实体屏 `/app/ui/*` 走应用内无障碍服务：有语义树、`treeId`/`nodeId` 一次性租约、截图 `observationId`（一次手势、30 秒、绑定窗口/代次），但**动作完成后没有「用户是否插手」的信号**；动作结果只说明 Android 接受了动作，`effectVerified:false`，仍须再观察一次。
- 无障碍服务当前只订阅 `typeWindowStateChanged|typeWindowContentChanged`（`app/src/main/res/xml/accessibility_service_config.xml`），不订阅触摸交互事件，也不请求触摸探索；屏幕上的物理触摸不会进入现有读屏链路。即使改成订阅，能拿到的也只是开始/结束级别的事件，没有坐标与手势轨迹；请求触摸探索还会改变系统手势行为，不能作为方案。
- 现有的「用户正在输入」闸 `ComposeGate`（`util/ComposeGate.java`）只看**对话输入框/编辑器**，不看实体屏触摸；用户直接在别的 App 里点，它不知道。
- 虚拟屏 Core 是**独立显示**（`VirtualDisplay` + `ImageReader`），不是 display 0 的镜像（`docs/virtual-screen-architecture.md` §5）。把它的预览当成实体屏镜像是错的；也不能向镜像注入输入。

### 1.3 目标

1. 物理触摸/导航键 → 结构化事件 → 当前任务 `userInterventionEpoch` 推进；任何旧凭据立即失效。
2. 每次实体屏工具响应附带自上次观察以来的用户活动摘要；用户一操作，AI 下一次调用就能看到。
3. 提供按需的 display 0 镜像帧，作为「用户改了什么」的视觉证据；不进模型上下文时不产生图片 token。
4. 授权、隐私、失败语义与现有识屏模式一致：默认关、可撤销、前台可见、无静默回退。
5. 为 T105 的动作即观察提供**用户介入**这一维；不重复实现 T105 的 settle 与稳定编号。

## 2. 现状事实（源码取证，只读）

| # | 事实 | 源码位置 | 对本设计的影响 |
|---|---|---|---|
| F1 | `/app/ui/*` 入口统一做模式门控、任务身份、桥代次、任务围栏与动作目标复核 | `HttpShellService.appUi`（1692） | 新端点必须挂在同一围栏内，不能绕过任务/模式/桥代次 |
| F2 | 动作前必须有当前观察：`ControlTaskAuthority.actionAllowed` 在 `needsObservation` 为真时拒绝 | `control/ControlTaskAuthority.java`（118、154） | 用户介入可以复用同一「重新观察」闸，只需增加 epoch 失效因素 |
| F3 | 观察有代次：`beginObservation` 递增序号并清掉旧目标，只有最近一次观察能建立动作目标 | `control/ControlTaskAuthority.java`（131、140） | epoch 应绑定到「观察/租约」而不是只存在全局变量 |
| F4 | `treeId`/`nodeId` 一次性、30 秒、绑定任务/窗口/代次；截图 `observationId` 一次手势 | `screen/ScreenNodeLeaseStore`、`screen/ScreenObservationLedger`、`screen/MainScreenActions` | epoch 变化必须使这两类租约都失效；不能让旧凭据跨用户操作复用 |
| F5 | 截图走 `AccessibilityService.takeScreenshot`（API 30+），支持 `format=mcp`（base64）与 `format=path`（应用私有 `Pictures/DSHA`）；`coordinatesAvailable` 只在区域可测时给出 | `screen/ScreenScreenshotCapture.capture`（64）、`screen/MainScreenActions.screenshot`（316） | 镜像帧可复用「mcp 内存返回」形态，但不签发 `coordinatesAvailable`/`observationId` |
| F6 | 无障碍服务声明 `canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows`；事件类型只有窗口状态/内容变化 | `app/src/main/res/xml/accessibility_service_config.xml` | 现有配置下没有物理触摸事件；不要把窗口事件当触摸 |
| F7 | 动作结果只有 `SCREEN_ACTION_SUBMITTED` / `actionAccepted` / `effectVerified:false`，WebView 变更不可由无障碍动作直接证明 | `screen/ScreenActionExecutor`、`screen/ScreenReply`、`DshaAccessibilityService.execute`（287） | 「动作即观察」属于 T105；本设计只追加 `userIntervention`，不伪造效果验证 |
| F8 | 虚拟屏 Core 通过 `setsid app_process --server` 启动，bootstrap secret 只经受管通道返回，随后用回环 + peer proof + 固定动词通信；60 秒无请求自动退出 | `vscreen/VirtualScreenCore.launch`（232）、`vscreen/VirtualScreenTransport`（20） | 镜像 Core 复用同一启动/传输/固定动词结构，不新增任意 shell |
| F9 | 虚拟屏通道按 Root → Shizuku → ADB 顺序选择，只选「真正可用」的通道；启动结果未知不换通道重放 | `vscreen/VirtualScreenChannels`、`util/ScreenCapabilityGate.chooseUsable`（132） | 镜像 Core 的通道选择与启动失败语义照抄，不降级到无障碍 |
| F10 | 识屏模式 off/physical/virtual 互斥；模式不匹配返回稳定拒绝码，不跨屏回退；`task-end` 不受模式门控 | `util/ScreenModePolicy.rejection`（39）、`ScreenModePolicy.admitted`（110） | 镜像属于 physical 子能力，不新增模式；virtual 模式下镜像端点必须拒绝 |
| F11 | 设备敏感能力存在独立原生授权（如短信 `sms_read`），不随备份恢复；设置页由 `DeviceGrantsFragment` 管 | `core/DeviceGrants`、`ui/DeviceGrantsFragment` | 新增两个敏感开关走同一存储与撤销风格 |
| F12 | 用户消息输入有 `ComposeGate`（等待用户输入，动作停在原地），与物理触摸无关 | `util/ComposeGate`、`HttpShellService.appUi` 的动作等待段 | 两个信号并存：`userComposing` 仍由 ComposeGate 给，`userIntervention` 由本设计给 |

### 2.1 外部参考实现（结构参考）能借什么、不能借什么

- **可借**：特权 Core 的「固定动词 + 双传输（受管启动 + 回环会话）+ 元素 fingerprint 复核」结构；把用户事件前置到下一次工具结果；统一 `screen` 命名空间与「绝不跨屏回退」。
- **不能借**：它的后台屏是另一块独立显示上的另一个 App，**没有把实体屏镜像成虚拟屏，也没有原始触摸检测**；它的用户介入靠悬浮条暂停/停止/插话与显式 handoff。镜像采集与 evdev 检测需要我们新增。
- **不复制源码**；语义命名、错误词表、参数范围按本仓库既有约定重写。

## 3. 能力矩阵：各方案对场景的覆盖

场景：

- **S1 抢占**：AI 动作后/动作中，用户直接摸屏幕。
- **S2 空闲等待**：AI 停下等用户操作或回话。
- **S3 同时操作**：AI 的手势还在执行时用户又碰屏幕。
- **S4 敏感界面**：锁屏、密码框、支付/验证码等 `FLAG_SECURE` 或不可读窗口。
- **S5 无通道**：只有无障碍，没有 Root/Shizuku/ADB。

| 能力 | A：无障碍 + 按需截图 + T105 | B：MediaProjection 镜像 | C：特权镜像 + evdev |
|---|---|---|---|
| S1 抢占检测 | 不能可靠检测（只有窗口内容变化可猜） | 不能可靠归因（帧差=动画/用户混合） | 能：物理事件立即推进 epoch |
| S2 空闲等待 | 不能等物理触摸 | 可等画面变化，误报高 | 能：长轮询等物理事件（限时） |
| S3 动作中用户介入 | 看不到 | 可能看到画面变化，晚且不可归因 | 能：动作返回时带 `userIntervention` |
| S4 锁屏/FLAG_SECURE | 截图/树本身受限 | 帧黑；系统隐私指示 | 帧可标不可用，只给坐标；不推断内容 |
| S5 无通道 | 可用（就是 A） | 可用但授权重 | 不可用；明确报不可用，不假装 |
| 新权限面 | 无新增 | 系统投影授权 + 前台服务 | 复用已授权 Root/Shizuku/ADB；新增画面+输入元数据授权 |
| 图片 token | 只按需截图 | 常驻/高频，成本高 | 按需低帧率，可控 |
| 结论 | 降级基线 | 本期不做 | **推荐** |

## 4. 方案对比

### 4.1 A：无障碍树 + 按需帧 + 动作回观察（T105 范围）

- **做法**：T105 让每个动作响应直接带一份新观察（新 `treeId`/`nodeId`，新代次）与 settle 状态；提供稳定编号/差异预览；截图仍按需单独取。
- **优点**：零新增权限，覆盖所有支持机型；实现风险最低；对「AI 连续操作」的回合数与 token 收益明确。
- **缺点**：**没有物理触摸信号**。用户直接在屏幕上操作时，AI 只能从下一次树/截图 diff 猜；在 WebView/游戏/自绘界面里甚至看不到变化。S1/S2/S3 不成立。
- **结论**：作为 T105 单独交付，也是 C 不可用时的降级；但不能把 A 描述成「能发现用户操作」。

### 4.2 B：MediaProjection 镜像观察层

- **做法**：用户逐次系统授权后，前台 `mediaProjection` 服务用 `VirtualDisplay`/`ImageReader` 收 display 0 画面；AI 按需取帧。
- **优点**：不需要 Root/Shizuku/ADB；系统级画面来源；可做人类可见的录屏式预览。
- **缺点**：
  1. 系统隐私指示与前台服务类型是强约束；Android 14+ 每次会话要重新授权，长时间手机任务会反复打断用户；
  2. 同一时间通常只允许一个投影会话，会与用户自己的投屏/录屏冲突；
  3. **拿不到物理触摸**：要用帧差检测「用户动了」，而 App 动画、视频、输入法弹出都会造成同样差异；归因不可靠；
  4. 帧解码/压缩带来额外 token 与内存成本；在低端机上与容器抢内存。
- **结论**：本期不选。若将来要做「用户可见的录屏/回放」再单独立项，不与抢占检测混在一个能力里。

### 4.3 C：Root/Shizuku/ADB 特权镜像 + evdev 用户检测（推荐）

- **做法**：
  1. 在 physical 模式、有活动手机任务、用户开关打开时，经既有受管通道启动一个**镜像 Core**（app_process，固定动词，回环 + peer proof，闲时自退）。
  2. Core 打开 `/dev/input/event*`，只解析触摸屏与导航键的物理事件（evdev 层），按设备 ABS 范围与 display 0 旋转换算成像素坐标，输出结构化事件。
  3. Core 抓 display 0 合成帧；AI 按需取低帧率 JPEG，人类预览可选高帧率。
  4. App 侧把事件同步进当前任务的 `userInterventionEpoch`；任何旧凭据在 epoch 变化后失效；工具响应带 `userActivity`。
- **优点**：唯一能同时满足「用户一碰就知道」与「看得到画面」的方案；事件在输入层，天然不受 App 自绘/WebView 影响；镜像帧是只读观察面，不改变输入凭据模型。
- **代价**：新增画面与输入元数据两类敏感数据；依赖设备通道；Shell UID 的 evdev/抓帧权限与 SELinux 策略必须逐机型验证；Root 与 Shizuku/ADB 的权限面不同。
- **结论**：推荐。分 M1（只做用户检测）/ M2（镜像帧）/ M3（预览与优化）三步，见 §9。

### 4.4 对比结论

- 选 C，保留 A 作降级；B 不做。
- C 不改变以下既有约束：动作凭据仍一次性、30 秒、绑定任务/窗口/代次；截图仍是一次手势凭据；镜像帧不是凭据；`UNKNOWN` 不重放；不跨屏回退。

## 5. 推荐方案 C 的架构

### 5.1 总体结构

```text
容器 / 助手工具
   │  /app/ui/*（现有围栏：模式、任务、桥代次、确认、动作目标复核）
   ▼
HttpShellService.appUi
   │  ──> 现有无障碍路径（读树/截图/动作）           ── 语义主路径
   │  ──> 新增 mirror / user-activity 适配层
   ▼
MirrorObservation（新，App 内）
   │  1) 权限与开关判定（physical + task + 两个敏感开关 + 通道）
   │  2) 向 Core 取增量用户事件；推进 userInterventionEpoch
   │  3) 把 Core 的帧原样转成 MCP 图片/私有文件（不签发坐标凭据）
   ▼
MirrorCore（新，app_process，shell/root uid）
   │  ├─ /mirror/ping|status|activity|watch|frame|start|stop（固定动词）
   │  ├─ evdev reader（触摸屏 + 导航键）
   │  └─ display 0 抓帧（SurfaceControl 优先，screencap 降级）
```

关键分工：

- **无障碍服务仍是语义来源**：树、节点租约、文本输入、语义动作都在现有链路；镜像不替代树。
- **Core 只做两件事**：读物理输入、抓显示 0 帧。它不接受调用者提供的 displayId、路径、命令或 shell 文本。
- **App 侧是唯一授权与代次权威**：Core 不知道任务身份，也不直接接触容器；它只认 App 签发的短期 peer 证明与固定动词。
- **预览不是输入权**：人类看到的镜像帧不能用于触摸；虚拟屏的「最新帧 → 输入许可」规则不适用于镜像（镜像没有输入）。

### 5.2 MirrorCore 的启动、身份与退出

复用虚拟屏 Core 的受管模式（`VirtualScreenCore.launch` / `VirtualScreenTransport`）：

1. **启动命令固定**：复用虚拟屏的受管启动封装（`setsid` + `/system/bin/app_process` + 独立会话，类路径只能等于已安装 APK 的真实路径，bootstrap 端口由 Core 自选并经通道结果返回）。不拼调用者参数、不接受命令行里的任意路径。
2. **bootstrap secret 只经通道结果返回**：与虚拟屏一样，token 不出现在 `app_process` argv 或系统命令行；首次 `ping` 通过同一回环 + peer proof 校验。
3. **固定动词**：`/mirror/ping`、`/mirror/status`、`/mirror/activity`、`/mirror/watch`、`/mirror/frame`、`/mirror/start`、`/mirror/stop`。不接受 `displayId`、`path`、`command`、`shell` 等参数。
4. **身份**：`coreInstance`（随机 UUID）、`generation`（每次 start/显示尺寸/旋转变化换新）、`frameSeq`（仅帧）、`userInterventionEpoch`（事件）。App 端只认同一次观察/同一 core 的身份，不跨 Core 混用。
5. **退出**：60 秒无请求自退（与虚拟屏一致）；任务结束、暂停、撤销开关、切换模式、停止 Web 时由 App 显式 `stop`；`stop` 结果未知时只 `status` 核对，不自动重建。
6. **通道选择**：`ScreenCapabilityGate.chooseUsable`，Root → Shizuku → ADB；启动结果 `START_UNKNOWN`/`DISCONNECTED` 不换通道重放，按回复的 `nextAction` 处理。
7. **与 virtual 互斥**：physical 模式下只允许 MirrorCore；virtual 模式下镜像端点直接拒绝（`SCREEN_MODE_PHYSICAL_OFF` 语义）；同一时刻不并存两块屏的采集 Core。

### 5.3 镜像帧：AI 按需、人类可选预览

**抓帧来源（从稳到降级）**：

1. **SurfaceControl 合成抓帧**（首选）：在 shell/root 进程内通过反射调用隐藏 API（与现有 `VirtualScreenPolicy.requestTrustedDisplay` 的反射风格一致），抓 display 0 当前合成结果；适合按需帧与低帧预览。该隐藏 API 在 shell/root 进程的实际可用性需真机探测，探测失败即按下一级降级。
2. **`screencap` 单帧**（降级）：Core 内部执行系统 `screencap`（固定参数、无调用者输入）取一帧；延迟约 100–400 ms，只适合按需帧，不适合高帧预览。
3. **不可用**：返回 `MIRROR_FRAME_UNAVAILABLE`，不伪造黑帧，不切换到无障碍截图假装镜像。

**AI 侧**：

- 只返回**最新一帧**，不排队、不补帧；`format=mcp` 时以 base64 直接回给 MCP 图片工具（内存），默认不落盘；`format=path` 只用于诊断，写应用私有目录并在任务结束后清理。
- 默认 JPEG、长边 ≤ 1280、质量 80、单帧预算 ≤ 1 MiB；超过预算由 Core 缩小重编，不返回超限图片。
- 元数据必须带：`displayId=0`、`width`/`height`、`rotation`、`frameSeq`、`capturedAtMs`、`generation`、`channel`、`contentAvailable`、`contentHidden`（`NONE|LOCKED|SECURE_OR_BLACK|UNKNOWN`）。
- **明确 `credentials:"none"`**：镜像帧不产生 `observationId`、不产生 `coordinatesAvailable`、不能用于点击/滑动/输入。坐标触控仍必须先 `/app/ui/screenshot` 拿 `observationId`。
- **帧不是稳定证据**：帧只说明「某一刻的画面」，不证明窗口已稳定；需要语义时仍读树（T105 的 settle 负责动作后稳定性）。

**人类预览（M3 可选）**：

- 复用虚拟屏预览的「提示条 / 完整预览 / 关闭」三档与让位规则（`util/VirtualScreenPreviewPolicy`、`VirtualScreenStrip` 的风格），但**只做展示**：不登记观察、不签发帧号/节点凭据、不参与输入。
- 预览帧率可高于 AI 取帧（例如 10–15 fps），但仍由 Core 限流并受内存预算约束；预览开启不得让 AI 侧无限制取帧。
- 预览必须显示「镜像 ≠ 可控」的说明，避免用户误以为能在预览里点。

### 5.4 用户物理输入检测（evdev）

**只读物理层**：

- Core 打开 `/dev/input/event*`，用 `EVIOCGNAME`/`EVIOCGBIT`/`EVIOCGABS` 分类：
  - 触摸屏：具备 `ABS_MT_POSITION_X/Y`（或单点 `ABS_X/Y` + `BTN_TOUCH`）；
  - 导航键：`KEY_BACK`/`KEY_HOME`/`KEY_APPSELECT`（以及是否含音量键由 §10-Q4 决定）。
- 其他设备（传感器、耳机键、鼠标、手柄）默认忽略并计入诊断（不输出内容）。
- **事件归属**：`AccessibilityService.dispatchGesture`、`performAction(ACTION_SET_TEXT)` 与 `InputManager.injectInputEvent` 都在输入分发层之上，不产生 evdev 事件；因此 evdev 看到的触摸默认就是「用户物理操作」。本期不使用 `sendevent` 注入；如果未来动作改为特权注入，必须在设计上显式标记并重新评估归属，不能默默复用本规则。
- **结构化输出**（按手势合并，不逐 move 上报）：
  - `kind`：`tap` / `long_press` / `swipe` / `multi_touch` / `key`；
  - `x,y`（起点，display 0 像素）、`endX,endY`（滑动）、`durationMs`、`pointerCount`；
  - `keyCode`（仅导航键）；
  - `atMs`（单调时钟）；
  - `node`（best-effort）：按事件点所在位置读一次当前无障碍树，取包含该点的最小可点击节点，给出 `role`、`viewId`、截断标签；密码框只给 `password:true`，**不返回标签内容**；
  - 定位不到时 `node=null`，不猜。
- **坐标换算**：ABS 原始范围 → 设备坐标 → display 0 像素；按当前 `Display.getRotation()` 处理 0/90/180/270；尺寸/旋转变化立即换 `generation` 并丢弃旧帧坐标。换算失败（无 ABS 范围、外接屏、异常设备）只报原始设备与时间，不报假坐标。
- **采样与合并**：单指移动按 16–32 ms 采样，手势结束后合并成一条；连续操作最多保留最近 8 条（与 §6.2 的响应预算一致），旧事件滚动丢弃，不写盘。
- **锁屏/内容隐藏**：锁屏时只输出坐标与按键（不抓帧、不读树）；`FLAG_SECURE` 无法从公开 API 可靠判断，抓帧全黑且屏幕亮时标 `contentHidden=SECURE_OR_BLACK`，**不推断内容，不把黑屏说成空界面**。

### 5.5 用户介入 epoch 与凭据失效

这是本设计的核心安全语义，必须和现有观察/租约模型对齐：

1. **epoch 位置**：当前手机任务内单调递增（从 1 开始），由 App 侧 `PhoneControlCoordinator`/`ControlTaskAuthority` 持有；Core 只提供事件流，不决定任务身份。
2. **推进时机**：每个**新手势的第一个物理事件**（DOWN 或导航键按下）推进一次；同一手势的 MOVE/UP 不重复推进。这样 AI 不会因为用户手指抖动被反复打断，但「用户开始操作」的瞬间一定失效。
3. **绑定**：
   - 每次观察（`beginObservation`）记录当时的 epoch；
   - `treeId`/`nodeId` 租约、截图 `observationId` 都绑定 epoch；
   - 动作准入时同时核对「任务/窗口/代次」与「epoch」；不一致返回 `USER_INTERVENTION_REQUIRED`（或复用 `FRESH_SCREEN_OBSERVATION_REQUIRED` 并附 `reason:"USER_INTERVENTION"`），不执行动作。
4. **动作进行中被打断**：已经交给 Android 的 `dispatchGesture` 无法安全撤回；动作结果照常返回，但必须带 `userIntervention:true`、`epoch` 已推进、`effectVerified:false`，并提示「重新观察，不要重放」。不能把「用户碰了屏幕」当成动作失败而自动重试。
5. **观察中的竞态**：一次读屏请求从开始到返回如果跨过 epoch 变化，返回结果标 `staleForUserIntervention:true`，树仍可读（用户可能正需要 AI 看见），但**不能据此签发可动作凭据**；下一次动作必须重新观察。
6. **watch 返回**：长轮询返回新事件时同样推进 epoch，返回 `needsObservation:true`；不返回「可直接点的节点」。
7. **任务边界**：`task-end`、暂停、停止、换轮、撤销开关、模式关闭时清空 epoch 与事件缓冲；不把上一任务的用户操作带到下一任务。

### 5.6 「调用即响应」的返回形状

在 T105 的「动作 → 新观察」基础上，实体屏工具响应统一追加用户介入块（字段名以实现为准，语义固定）：

```json
{
  "ok": true,
  "state": "SCREEN_ACTION_SUBMITTED",
  "actionAccepted": true,
  "effectVerified": false,
  "userIntervention": {
    "monitor": "evdev",
    "epoch": 4,
    "sinceLastObservation": 1,
    "events": [
      {"kind": "tap", "x": 540, "y": 1200, "durationMs": 72,
       "pointerCount": 1, "node": {"role": "button", "viewId": "", "password": false}}
    ],
    "lastInputAtMs": 1234567,
    "contentHidden": "NONE"
  },
  "needsObservation": true,
  "nextAction": "OBSERVE_THE_SCREEN_AGAIN"
}
```

- `monitor` 三态：`evdev`（可用）、`unavailable`（开关开但通道/权限不可用）、`disabled`（开关关）。**`unavailable` 时绝不可以把 `sinceLastObservation` 说成 0 就是「没有用户操作」**；必须同时在 `userActivityUnknown:true` 里说明。
- 事件最多回 8 条、每条最多 4 个关键字段；不返回原始 move 轨迹、不返回输入文字、不返回密码标签。
- 读屏/截图响应同样带 `epoch` 与自上次观察以来的事件；避免 AI 以为「刚读的树一定覆盖了用户操作」。
- 动作响应可选带 `mirrorHint`（当前是否有可用镜像、建议下一步取帧），但**不自动附图片**；图片必须由 AI 显式调用取帧工具，避免 unknowingly 花 token。

### 5.7 可选 watch 长轮询

- 端点：`/app/ui/user-activity?sinceEpoch=<n>&waitMs=<100..20000>`；工具：`android_wait_user`（可选）。
- 语义：最多等 `waitMs`，用户第一次物理操作即返回一条摘要；超时返回 `WATCH_TIMEOUT`、`epoch` 不变；不执行任何动作，不签发凭据。
- 只允许在活动任务内调用；任务暂停/结束/撤销时立即返回 `TASK_ENDED`。
- 不提供无限 watch、不自动重连；模型自己决定是否再等一次（一次工具调用一个等待窗口）。
- 这是**可选项**，M3 再做；M1/M2 先保证动作响应里的 `userIntervention` 正确。

## 6. 接口设计

### 6.1 端点

所有端点都在现有 `/app/ui` 围栏内，必须带 `taskSessionId`/`turnId`，受 `ScreenModePolicy`（physical）与桥代次门控。新增端点的授权能力名建议 `physical.mirror.*` / `physical.input.*`，具体以 `CapabilityBroker` 实现为准。

| 端点 | 作用 | 读/写 | 关键参数 | 说明 |
|---|---|---|---|---|
| `GET /app/ui/mirror/status` | Core/能力状态 | 读 | 无 | 返回 `enabled/available/channel/coreInstance/generation/frameSeq/inputMonitor/contentHidden`；不启动 Core |
| `GET /app/ui/mirror/frame` | 取一帧 | 读（可触发启动） | `format=mcp` 或 `path`、`maxEdge≤1280`、`quality≤80` | 只回最新帧；不签发坐标/动作凭据；`credentials:"none"` |
| `GET /app/ui/user-activity` | 同步/等待用户事件 | 读（可触发启动） | `sinceEpoch`、`waitMs` | 返回事件摘要与当前 epoch；不签发凭据 |
| `GET /app/ui/mirror/stop` | 显式停止 Core | 写（清理） | 无 | `task-end` 收尾调用；结果未知不自动重建 |
| 现有 `dump/tree/screenshot/tap/node/input/key/swipe` | 保持不变 | — | — | 响应追加 `epoch`/`userIntervention`（向后兼容，不改原有字段语义） |

**为什么不放在 `/app/vscreen/*`**：镜像永远是 display 0，属于 physical 模式；放进 vscreen 会打破「虚拟屏只操作虚拟显示」的边界，也会让工具路由产生跨屏歧义。

### 6.2 响应字段（新增部分）

| 字段 | 出现位置 | 类型 | 说明 |
|---|---|---|---|
| `epoch` | 所有实体屏响应 | int | 当前用户介入 epoch；任务开始时从 1 递增 |
| `userIntervention` | 动作/观察/截图 | object | 见 §5.6；`monitor` 三态必须给 |
| `userActivityUnknown` | 观察类响应 | bool | `monitor=unavailable/disabled` 时 true；不能把未知当「无」 |
| `staleForUserIntervention` | 读屏类响应 | bool | 采集期间 epoch 变化 |
| `mirror` | 可选 | object | `{available, channel, coreInstance, generation, frameSeq}`；不附图片 |
| `credentials` | 镜像帧 | string | 固定 `"none"`，防止调用方误用 |
| `contentHidden` | 镜像帧/事件 | string | `NONE`/`LOCKED`/`SECURE_OR_BLACK`/`UNKNOWN` |

### 6.3 错误码与下一步

沿用现有 `ScreenReply` 的 `{ok:false,error,reason,nextAction,retryable,state}` 形状；新增稳定码：

| 错误码 | 含义 | nextAction 建议 | 是否可重试 |
|---|---|---|---|
| `MIRROR_DISABLED` | 用户没开镜像开关 | 让用户去设置打开；不要重试 | 否 |
| `USER_ACTIVITY_DISABLED` | 用户没开触摸监听开关 | 让用户去设置打开；不要重试 | 否 |
| `MIRROR_CHANNEL_UNAVAILABLE` | 没有可用 Root/Shizuku/ADB | 告诉用户连接设备通道 | 否（重试不会变） |
| `MIRROR_START_UNKNOWN` | 启动结果未知 | 先 `status` 核对，不自动重建 | 否 |
| `MIRROR_FRAME_UNAVAILABLE` | 抓帧失败/不支持 | 可用无障碍截图做补充，但明确标注不是镜像 | 视情况一次 |
| `MIRROR_CONTENT_HIDDEN` | 锁屏/安全内容 | 只给坐标；等用户解锁 | 否 |
| `USER_ACTIVITY_UNAVAILABLE` | 开关开但 evdev 读不到 | 告诉用户该通道不支持；不要假装无操作 | 否 |
| `USER_INTERVENTION_REQUIRED` | 旧凭据已被用户操作作废 | 重新观察，再决定 | 否（重新观察后） |
| `WATCH_TIMEOUT` | 等待窗口内没有用户操作 | 结束等待，可继续别的步骤 | 是 |
| `TASK_ENDED` | 任务已结束/暂停 | 停止等待 | 否 |

### 6.4 工具面

在 `dsh-computer-use-android` 增加最少工具（具体命名以实现评审为准）：

- `android_mirror`：取当前实体屏镜像帧；描述里明确「只读、不能用于坐标点击、没有 observationId」。
- `android_wait_user`：限时等用户物理操作；描述里明确「不是自动连续等待、超时正常、等待期间视为用户可能正在操作」。
- `android_mirror_status` 可选；若工具数量敏感，可合并进 `android_get_state` 的 `mirror` 字段（不附图片）。

工具描述必须写明：镜像开关默认关；没有通道时返回不可用；`UNKNOWN` 不重放；不跨屏（不要用虚拟屏工具兜底）。

### 6.5 版本与特性检测

- `/app/version` 增加能力位（如 `capabilities: ["physical.mirror.v1","physical.input.v1"]`）；插件按 `>=` 判断，不用 `==` 版本号。
- 不改现有 `/app/ui/*` 字段语义；新增字段向后兼容；旧插件不调用新端点不受影响。
- 若需要在 MCP 工具里做能力探测，以 `/app/version` 的 capabilities 为准，不按 App 版本号猜。

## 7. 授权、隐私与安全

### 7.1 敏感开关

在 `设置 → 设备能力授权` 增加「实体屏增强」组（`DeviceGrantsFragment`）：

1. **读取实体屏画面（镜像）**：默认关；只在 physical 模式 + 有活动手机任务时生效；没有设备通道时置灰并说明原因。
2. **监听用户触摸（不记录文字）**：默认关；只读取坐标、手势参数、导航键；不读取输入法文字、不读剪贴板、不落盘；与画面开关互相独立（用户可只开触摸检测，不开画面）。

存储：`dsha_device_grants`（与短信等敏感开关同库），字段值缺失或损坏一律按关处理（fail-closed）；不随备份恢复；`capabilityBroker().revoke(...)` 立即生效。

### 7.2 激活与撤销生命周期

- **激活**：用户开关打开 + physical 模式 + `android_control begin` 任务建立 + 有可用通道；首次取镜像/事件时才启动 Core（懒启动），不常驻。
- **运行中**：每次请求先同步一次 Core 事件；任务结束/暂停/停止/换轮/开关关闭/模式切换时 App 显式 `stop` Core 并清 epoch；Core 侧 60 秒空闲自退作为兜底。
- **撤销**：关闭开关即停止 Core、清事件、清 epoch；正在进行的 watch 立即返回 `TASK_ENDED`；不删除历史数据（本来就没有落盘）。
- **异常**：Core 退出/断连/结果未知 → `status` 核对；`START_UNKNOWN`/`DISCONNECTED` 不自动重建（与虚拟屏一致），需要用户重新触发或下一次任务。

### 7.3 前台可见指示

镜像读取期间**必须有用户可见的指示**，因为特权抓帧没有系统级隐私指示：

- 首选：常驻通知「DSH-A 正在读取实体屏画面 / 监听触摸」+ 停止按钮；
- 有悬浮窗权限时叠加一个小指示条（红色圆点 + 文字，可点开停止/预览），让位规则参考 `VirtualScreenStrip`；
- 指示在能力激活期间常亮，Core 停止后立即消失；
- 不能因为任务窗口隐藏（`begin` 后任务窗口先隐藏）而失去指示——通知与任务窗口独立。

### 7.4 数据保留与隐私边界

- **不录像、不写盘**：Core 内存里只保留最新一帧；AI 取帧默认走 MCP base64；`format=path` 仅诊断，写应用私有目录并在任务结束后清理（与现有截图「不能声称完全不落盘」的口径一致，见 `docs/security-model.md`）。
- **事件不落盘**：坐标、手势、按键只在当前任务内存里；任务结束清空。
- **不采集输入文字**：evdev 事件不含输入法文本；`ACTION_SET_TEXT` 的文本不进入本设计；`node` 定位不返回密码标签。
- **不写公开日志**：错误码可诊断，坐标/标签不进日志；与现有「不把配对码或敏感屏幕内容写入公开日志」一致。
- **锁屏**：不抓帧、不读树；坐标事件只用于「用户动过」的判定，不显示内容。
- **FLAG_SECURE**：抓帧可能全黑；标 `contentHidden=SECURE_OR_BLACK`，不尝试绕过、不用无障碍树补内容。
- **无网络上传**：Core 只走本机回环；帧不经过外部网络。

### 7.5 与现有安全边界的关系

- **ADR0003**：镜像帧不是输入凭据；不改变「最新帧、锚点一次性、semantic 与 raw 不互相 fallback」。
- **截图遮挡 P1**：现有截图有窗口区域/遮挡判定；镜像帧是整屏合成，**不受**这层区域校验保护，因此敏感度更高，必须由独立开关与前台指示覆盖；不能因为镜像可用就放宽截图遮挡检查。
- **虚拟屏**：不共用 Core、不共用 generation、不跨屏回退；virtual 模式下镜像端点拒绝，`close` 虚拟屏不能影响实体屏状态。
- **设备命令策略**：Core 的启动只走既有受管 `app_process` 入口；不接受任意 shell；`DeviceShellPolicy` 对 `app_process` 的专用放行不扩大。
- **任务身份**：镜像/事件只能服务当前 `taskSessionId+turnId`；不缓存跨任务；换轮清空。

### 7.6 威胁与残余风险

| 风险 | 处理 | 残余 |
|---|---|---|
| 容器内代码伪造事件/读取他人帧 | 授权在原生侧，Core 无任务身份；token/peer proof 绑定连接 | 同 App UID 的任意代码仍可调用桥（既有模型，不是内核沙箱） |
| Shell UID 权限过大 | 固定动词、无调用者路径/命令、displayId 固定 0 | Root/Shizuku 本身已是高权限，镜像只是新增数据面 |
| 误把注入当用户操作 | 只用 evdev；本期不用 sendevent 注入 | 第三方无障碍/输入法注入在 evdev 不可见（安全方向），但 userActivity 会漏报「其他自动化操作」，文档需说明 |
| 用户没开触摸监听时 AI 以为「没人操作」 | `userActivityUnknown:true`；工具描述强制 | 模型可能忽略标志；需在工具描述与返回文案双重强调 |
| 锁屏/安全界面泄露 | 锁屏不抓帧；全黑标隐藏 | 无法可靠识别所有厂商安全窗口；标 UNKNOWN |
| 通道被系统杀 | 惰性重启 + 用户可见指示 + 不自动重放 | OEM 后台策略可能让 watch 提前结束；按超时处理 |

## 8. 兼容、回退与失败处理

### 8.1 通道矩阵

| 通道 | 抓帧 | evdev | 备注 |
|---|---|---|---|
| Root（su） | 预期可用（uid 0） | 预期可用 | 最稳；仍受 SELinux 设备策略约束 |
| Shizuku（shell uid） | 需真机验证 | 需真机验证 | 与 ADB 同为 shell uid，但不能只凭 uid 推断 SELinux/权限 |
| ADB（无线认证） | 需真机验证 | 需真机验证 | 与 Shizuku 共用 shell 权限面；连接断开即不可用 |
| 仅无障碍 | 不可用（A 降级） | 不可用 | 明确报不可用，不假装 |

**验证要求**：三条通道 ×（抓帧、evdev、锁屏、旋转）逐项真机记录；没有证据的组合只能写「未验证」，不能写成「支持」。

### 8.2 Android 版本

- 基线 API 30（Android 11），与当前 Standard 一致；无障碍截图/手势从 API 30 起可用。
- SurfaceControl 抓帧用反射探测；探测失败走 `screencap`；再失败报不可用。
- Android 14+ 的投影限制只影响 B，不影响 C；但 Android 14+ 对后台进程/OEM 保活更激进，Core 必须有 watchdog 与状态核对。
- 不承诺 Android 10 及以下。

### 8.3 多显示、旋转、尺寸变化

- 镜像只抓**display 0（默认显示）**；外接屏/副屏不镜像，不提供 `displayId` 参数。
- 旋转/折叠/尺寸变化：Core 换 `generation`，旧帧、旧坐标、旧事件换算全部作废；App 侧同时要求重新观察。
- 分屏/悬浮窗：坐标按 display 0 换算；用户事件对应的节点定位是 best-effort，定位不到就给坐标，不猜。
- 输入设备变化（蓝牙键鼠、手写笔）：默认忽略非触摸类设备；若用户用鼠标点击，可能不被 evdev 触摸规则采集——在文档中明确这是已知边界（是否纳入由 Q4 决定）。

### 8.4 失败与回退规则

1. 开关关 → `MIRROR_DISABLED` / `USER_ACTIVITY_DISABLED`，不启动 Core。
2. 无可用通道 → `MIRROR_CHANNEL_UNAVAILABLE`，提示用户连接；**不回退到无障碍截图假装镜像**。
3. 启动未知 → `MIRROR_START_UNKNOWN`，先 `status`；不自动重建、不换通道。
4. 抓帧失败 → `MIRROR_FRAME_UNAVAILABLE`；AI 可以用普通 `/app/ui/screenshot` 补一张，但返回里要说明「这是无障碍截图，不是镜像」。
5. 用户监听不可用 → `USER_ACTIVITY_UNAVAILABLE`；所有响应标 `userActivityUnknown:true`；不能写「用户没有操作」。
6. 任务/模式/桥代次变化 → 现有拒绝码优先；镜像端点也走同一代次检查。
7. `UNKNOWN`/`[EXECUTION_UNKNOWN]`：先核对再决定，不重放；镜像/停止动作同样适用。

### 8.5 与 T105 的依赖与顺序

- **T105 先做**：动作即观察、稳定编号/diff、事件驱动 settle。它为实体屏提供「动作响应带新观察」的基础；T107 的 `userIntervention` 是叠加在这一返回上的另一维。
- **T107 M1 不等待镜像帧**：用户操作检测可以与 T105 并行/随后落地；但若 T105 尚未合入，M1 只能给「事件 + 需要重新观察」，**不得在动作响应里附带伪造的新观察**。
- **settle 与用户介入**：settle 期间检测到用户操作，立即结束等待并标记 `userIntervention:true`，不继续等「界面稳定」；返回的树/帧只能作为「用户操作后」的新观察，且必须推进代次。
- **编号/差异**：T105 的稳定编号不能进凭据；T107 的 epoch 只做失效条件，不能替代窗口/目标复核。

## 9. 分阶段落地计划

### M1：用户操作检测（最小敏感面）

- MirrorCore 的 input-only 模式：固定动词 `/mirror/ping|status|activity|watch|stop`；只读 evdev，不抓帧。
- `userInterventionEpoch` + 观察/租约绑定 + 动作准入拒绝。
- 两个敏感开关先只上「监听用户触摸」；设置页状态、通知指示、撤销。
- 工具：`android_wait_user`（可选）+ 所有实体屏响应追加 `userIntervention`。
- 测试：坐标换算/事件合并/epoch 失效的 JVM 单测；root 与 shell 通道各一台设备的手动验证。
- 交付标准：用户物理点击→AI 下一次动作被拒并收到事件；无通道时明确不可用。

### M2：镜像帧

- 抓帧梯子（SurfaceControl → screencap → 不可用）、Core 帧缓存、`/app/ui/mirror/frame|status|stop`。
- 画面敏感开关、前台通知、锁屏/黑帧语义、`credentials:"none"`。
- 工具：`android_mirror`；响应不自动附图。
- 测试：抓帧尺寸/旋转/预算；锁屏；任务结束清理；与截图遮挡检查互不干扰。

### M3：人类预览与体验

- 预览三档（复用虚拟屏预览策略）、高帧率限流、让位规则。
- watch 与 T105 settle 的组合、事件到节点的 best-effort 定位、差异/区域取帧降 token。
- 工具描述与 UI 文案的十语落位（若加设置项，需按 i18n 政策走）。

### 测试计划

- **JVM 单测**（纯逻辑，不依赖 Android）：evdev 事件合并、ABS→像素换算（含旋转）、epoch 失效规则、响应预算（最多 8 条/字段上限）、错误码与 nextAction 映射、开关/模式 fail-closed。
- **编译门禁**：实现阶段跑 `dsha-gradle` 定向单测与 `assembleStandardRelease`；设计阶段不需要构建。
- **真机矩阵**（实现阶段）：root / Shizuku / ADB × 抓帧/evdev；锁屏；旋转；分屏；任务结束/暂停/撤销；模式切换；通道断开；OEM 后台限制。
- **不可宣称**：未跑过的通道/ROM 只能写「未验证」；不把设计稿当验收证据。

### 回滚

- 关闭两个开关即停用；不涉及数据迁移；无持久帧/事件可清理。
- 若 M2 抓帧在某个 ROM 上不稳定，仅关闭画面能力，M1 的事件检测仍可用（两开关独立）。
- 若 M1 的 evdev 在某通道不可用，该通道标不可用，不影响 A 的读屏/动作。

## 10. 待确认问题（请用户评审时拍板）

1. **检测粒度**：只报「用户动过」（epoch + 时间，隐私最省），还是报坐标 + 基础手势 + best-effort 节点（推荐）？
2. **物理动作是否也改特权注入**：本期推荐不改（继续无障碍动作，evdev 天然区分）；若改，需重做目标校验与注入归属，风险面明显变大。
3. **watch 是否占一次工具调用**：推荐做可选工具，单次 ≤20 秒，不自动连续等待；请确认是否接受。
4. **通道与输入范围**：推荐 root/Shizuku/ADB 都支持但逐通道真机验证；多指只报 `pointerCount` 与中心点，不做双指缩放；键盘只报导航键（是否含音量键请定）。
5. **开关粒度**：两个开关（画面 / 触摸监听）还是一个总开关？推荐两个，便于「只看不摸」或「只摸不看」。
6. **锁屏/安全界面**：推荐锁屏不抓帧、只给坐标；黑帧标 `SECURE_OR_BLACK`，不推断内容。是否接受？
7. **帧返回形态**：默认 MCP base64（内存）；`path` 仅诊断。是否接受私有临时文件这一例外？
8. **排期**：是否接受先做 M1（无帧，只做用户操作检测），M2 镜像帧在 T105 稳定后再排？

## 11. 取证与验证边界

- 本稿源码事实来自 `origin/main` `61245640` 的只读检查；引用类/方法见 §2 表格。
- **未做**：真机抓帧、evdev 读取、SELinux/通道权限实测；因此 §8.1 的通道矩阵是设计预期，不是验收结论。
- **未实现**：没有改产品代码、没有新增依赖、没有构建、没有安装、没有 push/合并/发布。
- 后续实现会话必须以本稿 §10 的用户决定为准；T105 未落地前先做 M1，不得在动作响应里伪造新观察。
