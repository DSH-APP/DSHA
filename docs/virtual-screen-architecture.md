# 虚拟屏架构与维护指南

本文按 build190 / F10 当前源码说明实现，面向需要定位问题或修改虚拟屏的维护者。
它描述代码契约，不构成任何手机、ROM、应用或输入法的兼容性验收结论。
设计决定及代价见 [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md)。
构建与项目入口见 [BUILD](../BUILD.md)、[AGENTS](../AGENTS.md) 和 [安全模型](security-model.md)。
面向使用者的入口与操作说明见 [虚拟屏使用指南](virtual-screen-guide.md)。

## 1. 先区分四个对象

| 对象 | 所有者与用途 | 不能替代的对象 |
|---|---|---|
| 物理屏 | Android 的 display 0；由物理 computer-use 路径操作 | 活跃虚拟屏 |
| 虚拟显示 | 特权 Core 创建的 `VirtualDisplay`，具有独立 displayId 和画布尺寸 | 原生预览控件 |
| 原生预览 | Activity、前台展示及悬浮预览消费的位图 | 可长期使用的输入许可 |
| DSH 观察 | 给模型的图片、节点或编辑器证据 | 当前系统状态或任意新 Core 的身份 |

虚拟屏是独立显示与受限控制协议，不是模拟器、完整桌面虚拟机或强沙箱。
运行其中的应用仍受 Android 的显示、窗口、权限、输入注入和 ROM 策略约束。
“能创建”“能看到图片”“能注入事件”“应用实际响应”是四件不同的事。
不得在物理屏工具失败时，把它自动路由到虚拟屏；反向转换也同样禁止。

## 2. 代码地图与进程边界

```mermaid
flowchart LR
  U[用户] --> UI[VirtualScreenActivity]
  UI --> M[应用进程 VirtualScreenManager]
  A[DSH 模型] --> P[dsh-tool-vscreen / stdio MCP]
  P --> H[HttpShellService /app/vscreen]
  H --> B[CapabilityBroker 与屏幕策略]
  B --> M
  M --> C[Root / Shizuku / ADB 启动通道]
  C --> L[app_process --launch]
  L --> K[独立 app_process --server Core]
  M <-->|回环连接与同连接证明| K
  K --> V[VirtualDisplay + ImageReader]
  K --> I[指定 display 的事件注入]
  M --> S[DshaAccessibilityService]
  S --> N[指定显示与窗口的语义节点]
```

| 文件 | 阅读重点 |
|---|---|
| [VirtualScreenActivity][activity] | 原生创建、启动应用、预览、输入结果与显式节点面板 |
| [VirtualScreenForeground][foreground] / [VirtualScreenStrip][strip] | 宿主页内的提示条与完整预览：档位、让位、收起持久化 |
| [VirtualScreenManager][manager] | 应用级唯一编排器、身份快照、串行操作、退出收敛 |
| [VirtualScreenCore][core] | 特权进程启动、回环服务器、显示与输入路由 |
| [VirtualScreenLauncher][launcher] / [VirtualScreenChannels][channels] | 固定命令生成及可用通道选择 |
| [VirtualScreenTransport][transport] / [VirtualScreenPeerProof][peer] | 单次连接与请求证明，不透明重试 |
| [HttpShellService][http] | bridge 鉴权、当前 DSH 运行与屏幕授权检查 |
| [VirtualScreenAccessibility][accessibility] / [SemanticAdapter][semantic] | 节点树、编辑器及原生语义操作适配 |
| [dsh-tool-vscreen index][plugin-index] / [server][plugin-server] | DSH 工具发现、参数约束、图片结果与错误语义 |

`DshaApp.virtualScreenManager()` 以应用级 owner 持有 Manager。
Activity 重建或关闭预览订阅，不等于把 Core 交给某个 View 管理。
Core 不运行在 Ubuntu 的 DSH Node 进程中；Ubuntu 侧插件通过宿主 bridge 请求能力。
特权通道用于建立受管 Core 及出生探针，日常输入不重新拼任意 shell 启动命令。

## 3. 创建与受管握手

原生 `start()` 与 `/app/vscreen/create` 最终进入 Manager 的 `startOrdered()`。
先检查 flavor、API、已有生命周期和清理现场；有未决现场不能直接再建一个。
同一 Manager 负责当前 Core；调用者应先 `status`，复用已经存在的目标。
创建方向由 `VirtualScreenPolicy.phoneSize()` 规范化，不能拿原生屏幕尺寸猜虚拟尺寸。
用户显式选择显示规格时，Manager 只把白名单预设 id 与方向交给 Core（见 3.1）；未携带预设的旧请求
（既有测试与直接 Core 调用）继续走 `phoneSize()` 与 320 dpi，默认创建的像素尺寸不变。

```mermaid
sequenceDiagram
  participant M as Manager
  participant T as 受管通道
  participant L as app_process --launch
  participant C as app_process --server
  M->>M: 记录启动 epoch 与本机现场
  M->>T: 固定启动命令；ADB 另需一次性 ticket
  T->>L: 当前已安装 APK 的 classpath
  L->>C: setsid 启动直接子进程
  C->>C: 动态回环端口、随机秘密、出生身份
  C-->>L: bootstrap（仅受管结果通道）
  L->>C: 带身份的 ping / 同连接证明
  L-->>M: 受管 STARTED 握手
  M->>C: create / 验证结果归属
  M->>M: 保存当前 handle 并进入 READY
```

`VirtualScreenLauncher.launchCommand()` 只指向当前安装包和固定 Core 类。
Core `main()` 检查包名、已安装 APK classpath、API 与动态端口要求。
`--launch` 父进程用 `setsid` 启动 `--server`，读 bootstrap 并确认可达后明确退出。
父进程结束不能被误认为常驻 Core 已结束；两者承担不同角色。
Core 生成秘密，不接受把秘密放进 `--token` 参数。
握手带端口、秘密和出生身份；不能只解析一个成功字样就接受启动。

[VirtualScreenChannels][channels] 通常按 Root、Shizuku、ADB 检查可用性。
API 37 且 ADB 已连接时，代码把 ADB 放到 Shizuku 前；Root 仍优先。
“可用”仍需对应本机授权，应用不会凭此自行获得 Root 或配对权限。
ADB 路径见 [AdbBridge][adb] 与 [AdbVirtualScreenStart][adb-start]。
Manager 签发与命令、epoch、期限绑定的一次性 ticket，经过 plan 授权和 commit。
ticket 经专用路径传递，不应写进日志、命令行或一般终端命令。
启动结果未知必须保留现场；换通道重新启动也可能重复创建，不能据超时推断未执行。

### 3.1 显示规格白名单与观察预算

默认规格保持 `1008×1792 @320`（16:9，逻辑 504×896 dp），与 [VirtualScreenPolicy][policy] 的
`phoneSize()` 归一化结果一致。用户在虚拟识屏页的「显示规格」里显式选择白名单预设，Manager 经
[VirtualScreenDisplaySettings][display-settings] 读取选择，创建/旋转时只向 Core 传 `preset=<id>` 与
`orientation`；Core 用 [VirtualScreenDisplayPreset][display-preset] 再解析一次白名单。

| 预设 id | 像素（竖屏） | dpi | 逻辑 dp | 比例 | 说明 |
|---|---|---:|---:|---|---|
| `default` | 1008×1792 | 320 | 504×896 | 16:9 | 默认，保持不变 |
| `compat-720` | 720×1600 | 320 | 360×800 | 20:9 | 360 dp 兼容基线 |

未知 id、空值与超出观察预算的规格都回退默认；不接受任意尺寸或 dpi，避免把创建参数变成新的资源面。
预设按白名单原尺寸创建，不再经过 `phoneSize()` 的 16:9 + 144 倍数归一化 —— 后者只保留给不带预设的旧
调用。选择只影响下一次创建或旋转重建，运行中的显示不会被悄悄改变，Core 的代次、帧身份与输入门不变。

观察预算与 [VirtualScreenObservedPixels][observed-pixels] 共用同一常量来源：最多 2 份原图、合计
24 MiB。默认两份约 13.8 MiB，`compat-720` 约 8.8 MiB；1440×2560 两份约 28.1 MiB，超过预算，因此不
进入白名单；若要启用，必须先核算并调整预算，而不是只加一行预设。单测 `VirtualScreenDisplayPresetTest`
守着白名单每一项都装得进预算。

**真机状态**：`compat-720` 下的目标应用布局、输入法、截图尺寸与多窗口行为尚未在真机验证，不宣称兼容。
默认规格沿用既有 build 基线（真机证据只覆盖默认规格）。

## 4. 身份不是一个 displayId

| 层次 | 当前代码使用的证据 | 防止的问题 |
|---|---|---|
| Core 出生 | `pid / started / uid / boot / instance` | PID、端口复用和旧 Core 冒充 |
| 连接 | handshake、动态端口、秘密、连接随机数和 HMAC | 连到错误回环服务或重用旧证明 |
| Manager | lifecycle epoch、active epoch、port、token、generation、revision | 关闭/重建后的迟到回调 |
| DSH 运行 | runtime identity、Web generation、bridge run、授权 epoch 与 revision | 旧会话运行请求跨启动生效 |
| 显示与窗口 | Core UUID、generation、displayId、windowId、package | 把可见目标换成另一个窗口 |
| 观察 | frameSeq、observationId 或一次性 node/editor/anchor ID、TTL | 旧画面和旧节点被用于新动作 |

出生身份定义在 [VirtualScreenCoreIdentity][identity]，Manager 快照使用 [RequestFence][fence]。
Core `instance` 对外表现为 `coreInstance`；`generation` 属于虚拟显示会话。
两者都不能与 DSH Web generation 混为一谈。
`status` 返回的当前 UUID 不能补贴到旧截图、旧树或旧 anchor 上。
Manager `decorate()` 不会把成功的旧观察重新标成当前 Core 的成功观察。

[VirtualScreenPeerProof][peer] 在同一 socket 上交换随机数，证明绑定应用、端口和完整出生身份。
请求证明还绑定 method、target 和正文摘要；原始秘密不作为 Core HTTP 头发送。
宿主 3090 bridge 的 `X-Token` 是另一层鉴权，不能替代 Core 的连接证明。

## 5. 采集、缩图与“用户实际看过”

Core 创建 `VirtualDisplay`，Surface 接到 RGBA `ImageReader`。
[VirtualScreenDisplaySession][display-session] 管理显示、reader、帧、输入和观察缓存的释放。
[VirtualScreenPixels][pixels] 的采集线程调用 `acquireLatestImage()`，处理行跨度和像素跨度。
只有像素确实变化才递增 `sequence`；相同像素会更新采集时刻而不伪造新帧号。
暂时无帧不会重建显示，也不会凭空制造一张可输入的帧。

这里有两条图片路径，修改时必须分别测试：

| 路径 | 图片与保留方式 | 输入含义 |
|---|---|---|
| Native preview | 宽度至多 720 的 JPEG，保留实际显示 width/height 元数据 | 展示成功后登记该帧；仍受最新帧门约束 |
| Agent `see` | [ObservedPixels][observed-pixels] 保留原始位图，返回全分辨率 JPEG | 可为显式 anchor 保留同次原观察，不续期旧证据 |

`ObservedPixels` 当前限制为 30 秒、最多两份、原始位图总预算 24 MiB。
预算统计不是 JPEG 文件长度；大分辨率原图可能直接被拒绝。
Native 缩图与 Agent 原图不能共享一个未经区分的坐标系。
`status` 只是读状态，**不签发看过图片的凭据**。

[VirtualScreenPreviews][previews] 让多个原生展示 owner 合并请求，各自持有解码位图和主线程回调。
订阅 `Lease` 只把当前 epoch 的帧送给 owner，并记录真正采用的 generation/sequence。
失败间隙会撤销输入身份；恢复时即使 sequence 相同也需重新交付，不能被去重吞掉。
[PreviewView][preview-view] 可在同代 `STALE_FRAME` / `FRAME_NOT_READY` 时保留上一张图以减小闪烁。
**保图不保输入权**：旧图可以继续可见，触摸必须禁用，直到有效帧重新采用。

### 5.1 前台预览形态：提示条 / 完整预览 / 关闭

[VirtualScreenForeground][foreground] 决定宿主页里显示什么；档位与收起意图由
[VirtualScreenPreviewSettings][preview-settings] 持久化（键 `vscreen_preview_mode` /
`vscreen_preview_collapsed`，见 [VirtualScreenPreviewPolicy][preview-policy]）：

| 档位 | 行为 |
|---|---|
| 提示条（默认） | [VirtualScreenStrip][strip]：顶部 3dp 蓝色呼吸线 + 目标文字，视觉总高 27dp、点按热区 40dp；只显示，不向虚拟屏转发触摸；点按打开控制页，`×` 收起 |
| 完整预览 | 旧的 172dp 页内小窗，可拖动标题、点画面打开控制页 |
| 关闭 | 不显示任何预览 |

提示条目标只来自 Manager 的只读 `targetStatus`（`packageVerified` 为真时的 `package`），
拿不到时显示「虚拟屏操作中」，不按最近一次启动记录猜测。刷新是 4 秒一次的只读 status，
不取帧、不登记观察、不签发帧或节点凭据；因此提示条不是输入许可，不改变 ADR0003 的观察与
输入身份。虚拟屏停止/撤销、宿主页离开前台、当前页是虚拟识屏页、系统悬浮预览显示时条和
小窗都移除；系统悬浮窗仍走原本的 opt-in 权限通道。

收起跨虚拟屏代次与 App 重启保持（不再按 generation 记忆），只能从
**设置 → 设备能力授权 → 虚拟屏预览** 三档中重新选择；选择档位即清除收起意图并重新显示。
系统动画关闭或无障碍减少动态时，呼吸线停在静态不透明状态。

## 6. 原始坐标与注入

`PreviewView.point()` 先逆变换缩放、平移和留白，再把位图坐标映射到显示 width/height。
落在图片外的点拒绝；不能直接把 Activity 截图坐标交给虚拟显示。
模型坐标以 `see` 元数据为准；若 `previewWidth/previewHeight` 与显示尺寸不同，必须换算。
当前 Agent 保留原图路径使这两组尺寸一致，协议仍明确描述差异情形。
横竖屏、缩放、Insets 和键盘出现后，都应重新核对展示几何。

原始 tap/swipe/type/key 要求：请求帧已观察、仍为最新帧、观察未超 30 秒。
判定集中在 [VirtualScreenPolicy.inputRejection()][policy]。
Core 消费观察后复位；不能把同一 `frameSeq` 当作可无限连点的许可。
流式 touch 在 DOWN 检查帧，后续阶段还受 stroke 身份与次序限制。
不能为了避免按压涟漪改变帧而删除 DOWN 前的最新帧校验。

[PreviewInputGate][preview-gate] 协调本机 preview fetch 与手势准入。
它只减少“取到下一帧但尚未画出”的本地竞争，不冻结 Android 页面或放宽远端帧门。
动态光标、动画、视频仍可能使坐标动作在派发前过期；这是实际可用性限制。
拒绝后应让用户核对画面，或由用户显式打开节点操作，不能自动刷新并重发。

[VirtualScreenInput][input] 有两类实际后端：

- 反射后端查找 `InputManager` / `InputManagerGlobal`，对事件明确调用 `setDisplayId`。
- 命令后端明确使用 `input -d <displayId>`；不能省略 display 参数去赌系统焦点。
- 反射逐阶段注入；命令后端在 UP 时汇总为命令，MOVE 不代表系统已收到每个中间事件。
- 输入结果区分接受、拒绝和未知；命令超时不等于业务未执行。
- Unicode 由无障碍输入路径处理；不能承诺 shell `input text` 支持所有文字与编辑行为。

### 6.1 助手文本写入的密码焦点守卫（T97）

`/app/vscreen/type` 的纯 ASCII 快路径由宿主直接转发 `input -d <displayId> text`，不经过无障碍
`set_text`/`editor` 的密码检查。因此助手写文本前，宿主会只读当前显示的 `FOCUS_INPUT` 焦点并做一次策略判定
（[VirtualScreenTextInputPolicy][text-policy]、`VirtualScreenPasswordProbe`）：

| 宿主证据 | 决策 | 回复 |
|---|---|---|
| 焦点编辑框是密码框 | 硬拒绝，不发送 | `PASSWORD_INPUT_UNAVAILABLE`、`state=NOT_SENT`、`nextAction=REPORT_TO_USER` |
| 焦点编辑框可编辑、启用、非密码 | 允许 | 正常结果 |
| 无障碍在线但目标窗口/焦点读不出来 | fail-closed，不发送 | `PASSWORD_FOCUS_UNVERIFIED`、`state=NOT_SENT`、`retryable=true`、`nextAction=SEE_AGAIN` |
| 无障碍未连接 | 保持纯 ASCII 能力，但标注未检查 | 成功结果带 `passwordFocusChecked=false` |

「无障碍未连接时保留输入」是产品文档化的降级模式：该模式下没有语义证据通道，回复不能声称已保护。
探测时窗口身份要与授权目标复核；目标已变返回未发送的 `SCREEN_TARGET_CHANGED`。

同一条硬边界覆盖其他助手写文本路径：非 ASCII `type` 的语义路径与 `node` 的 `set_text` 也在无障碍层拒绝密码节点；
`editor` 的 get/edit/submit 原本已拒绝。原生「节点操作」面板是用户本人输入，走独立租约，不受助手守卫影响。

限制：这是宿主侧最佳努力检查，不是内核沙箱；检查与 Core 注入之间存在时间窗，自定义控件不暴露
`isPassword()` 时只能按不可读处理；无障碍关闭时保护降级；同 UID 的插件/终端可以绕开受管入口（见安全模型）。

### 6.2 敏感界面分档与交接（T119）

在 6.1 的密码焦点硬守卫之外，宿主对虚拟屏的**整屏**做一次保守分类：命中密码框、验证码关键词、金融应用或
支付关键词、系统安全页面关键词，或已知敏感应用但树读不出来时，按类别交给
`util/SensitiveScreenPolicy`（纯逻辑）与 `util/SensitiveScreenClassifier`（纯逻辑）决策。分类只读当前显示的
无障碍树，预算 600 个节点 / 40 层，只保留类别与依据码，不把界面文本写进回执或日志。

| 类别 | 默认 | 说明 |
|---|---|---|
| `PASSWORD` | 整屏 `ASK`；文本读写**硬阻断不变** | 密码框存在即整屏按密码界面处理；6.1 的输入层拒绝不因确认而解除 |
| `OTP` | `ASK` | 可编辑控件出现验证码/动态码/OTP 等关键词 |
| `PAYMENT` | `ASK` | 金融类应用包名，或任意应用页面出现支付/收银台/转账等关键词（微信等通用应用只按关键词判） |
| `SYSTEM_SECURITY` | `ASK` | 设置/权限/系统界面 + 无障碍、开发者、账户、锁屏、安全等页面关键词 |
| `SECURE_UNKNOWN` | `ASK` | 已知敏感应用但窗口树读不出来；空屏/取不到包名不做无目标分类 |

- **存储与修订**：独立键 `sensitive_screen_v1`（`DeviceGrants`），严格解码、损坏回默认 ASK；应用名单精确包名、
  各自上限 64、默认空，两名单互斥。保存先失效旧请求/旧弹窗，再提交并回读。屏幕授权的 `policyRevision`
  使用 `screen_confirmation_v1` 与 `sensitive_screen_v1` 两个修订号之和，任一保存都让旧确认与在途请求失效；
  进程内还有单调下限，记录损坏被解码成默认值也不会把修订号退回旧值。
- **`ASK` 强制确认**：`ScreenConfirmationPolicy.ALLOW_ALL`、按应用 `ALLOW`、以及「本轮允许此应用」的 5 次内存
  都不能跳过敏感类别的确认；只有显式的信任名单才放行。敏感确认一次一授权，不写入本轮记忆。
- **`OFF` 只关闭整屏分档**：该类别回到普通屏幕确认策略（`全部手动` / `按应用` / `全部允许` 按原语义生效），
  不进入敏感分类的强制确认或交接；密码文本读写仍由输入层硬阻断，与 `OFF` 无关。默认所有类别 `ASK`，
  `OFF` 需要用户显式选择。
- **`HANDOFF` 交接**：在派发任何输入前暂停助手（`PhoneControlCoordinator.pause("SENSITIVE_SCREEN_<类别>")`），
  撤销在途请求并丢弃观察，返回 `ok:false`、`state=NOT_SENT`、`error=SENSITIVE_SCREEN_HANDOFF`、
  `sensitivity=<类别>`、`readOnly:true`、`retryable:false`、`nextAction=HANDOFF_TO_USER`。
  不改屏幕模式、不关闭 Core；用户恢复后助手必须重新 `see`，**不自动重放**上一动作（ADR0003 的 UNKNOWN 不重放
  同样适用于策略拒绝）。
- **确认后复核**：会注入输入的端点（`tap/swipe/touch/key/type/node/edit/submit/anchored-click`）在确认结束后、
  真正派发前再读一次分类；读不出返回 `SENSITIVE_SCREEN_UNVERIFIED`，类别变化返回 `SENSITIVE_SCREEN_CHANGED`，
  两者都是未发送、`retryable=true`、`nextAction=SEE_AGAIN`。读端点（`see/tree/editor/anchor`）与写端点都参与
  分类；生命周期端点（`status/close/create/launch/preview`）不分类。
- **密码文本路径**：`PASSWORD` 的 `type`/`edit`/`node set_text` 不先弹确认，直接交给 6.1/无障碍层的
  `PASSWORD_INPUT_UNAVAILABLE` 硬拒绝；密码页上的点按、按键等非文本动作仍按策略 `ASK`/`HANDOFF`。
- **设置入口**：设置 → 设备能力授权 → 识屏 → 敏感界面交接；逐类别选择询问/交接/不启用，维护强制交接与信任应用名单。
  文案明确「全部允许不解除密码文本阻断」「交接后不会重放」。

误伤与限制：包名子串与关键词会命中测试应用或无关页面；方向是「多问一次 / 交给用户」，不是永久拒绝，用户可把
应用加入信任名单或暂停助手。无障碍关闭时没有语义证据通道，不做分类（保护降级，不宣称安全）；自定义控件、
Canvas、语言与 ROM 差异可能漏判；检查与注入之间存在时间窗；同 UID 的插件/终端可以绕开受管入口 —— 这不是
内核沙箱，与 `docs/security-model.md` 的边界一致。真机验收需覆盖：正常页面不误弹、密码页只硬阻断文本、
支付/验证码默认多问一次、交接后暂停且恢复后必须重看、名单两向生效、短屏/大字体下设置页可滚动。

## 7. 三种显式语义操作

### 7.1 DSH tree / node / editor

`android_vscreen_tree` 经 Manager、SemanticAdapter 到无障碍窗口树。
`VirtualScreenAccessibility` 保存有界节点与实际目标，签发一次性 nodeId。
node 操作重新核对显示、窗口、包、树期限，并刷新目标节点；取出即从 map 移除。
语义节点不要求像素 `frameSeq` 相等，因此不会因纯光标闪动而必然失效。
这并不豁免目标身份、当前授权、可用服务和节点能力校验。
助手 `node` 的 `set_text` 与 `editor` 一样拒绝 password 节点（`PASSWORD_INPUT_UNAVAILABLE`），不提供「仍然允许」。
editor 路径另有编辑器身份及 get/edit/submit 参数，不能拿 nodeId 冒充 editorId。
Canvas、隐藏内容、不完整 WebView 语义树可能没有可用节点；不能据此猜中心点自动点击。

### 7.2 DSH see / anchor / anchored_click

[ObservedControl][observed-control] 把原观察与授权、运行、Core、Manager、窗口和包绑定。
[AnchorProof][anchor-proof] 核对语义树、几何、遮挡和候选唯一性，并比较原区域像素证据。
`anchor` 只选定，不点击，不续期；`anchored_click` 是另一次明确的动作。
调用必须保留原 coreInstance/generation/frameSeq/observationId 和返回 anchorId。
不能取新的 status 帧号“修复”旧 anchor，也不能先试 raw tap 再静默换 anchor。
anchor 选择回复丢失会标 selectionUnknown/noReplay；重新选择也可能重复消费，不能自动做。
点击只消费一次，返回的 `validatedCurrentSeq` 不把原 `sourceFrameSeq` 改写成新观察。

### 7.3 Native“节点操作”

Activity `showNodes()` 在用户明确打开面板时调用 `Manager.openNativeControls(Activity)`。
[VirtualScreenNativeControls][native-controls] 只接受处于 RESUMED 的真实 Activity owner。
它使用独立 [NativeScreenControlLease][native-lease]，不把缺少模型 broker 当成授权。
`ON_PAUSE / ON_STOP / ON_DESTROY`、取消面板、关闭页面都会永久撤销该 owner。
重回页面需要用户重新打开面板；旧回调不得复活旧节点列表。

面板显示后端核验的 packageName 与 displayId，不能从启动包名草稿推断实际目标。
列表只提供 enabled 的可点击或可编辑节点；Native 最小动作是 click 与 set_text。
set_text 还要求 editable、非 password、声明 ACTION_SET_TEXT，长度最多 16000 字符。
树文本展示超过 1000 字符会标 `textTruncated`；此时编辑框不预填截断内容。
用户须显式输入完整替换文本并提交，防止“原样提交”悄悄丢失字段尾部。
操作后清掉旧列表，继续操作需用户手动刷新；不自动刷新后再次执行。
UNKNOWN 会撤销 owner 并要求查看实际画面；文案不能诱导直接重试同一动作。

## 8. DSH 发现、路由与结果协议

插件 [index.js][plugin-index] 通过官方 MCP client 注册 stdio server，等待子插件完成初始化。
配置禁止自动 reconnect，工具调用期限为 60 秒；初始化失败不能假装工具已就绪。
[server.cjs][plugin-server] 的 `initialize.instructions` 和工具描述共同告诉模型使用专属虚拟屏工具。
所有现有 Core 操作需显式传原 `coreInstance`；create/status 是发现入口。
close 在存在 live/pending Core 时也必须带精确身份，不能关“当前碰巧存在”的另一 Core。

`requestFor()` 把 node/type/edit/submit/anchor 等构造为受限 JSON POST，其余使用固定 route。
[VscreenBridgeRequest][bridge-request] 在宿主重新解析字段、方法、大小和参数组合，不能只信 JS schema。
`HttpShellService.handleVscreen()` 再进入屏幕授权和当前运行检查，最终调用 Manager `bridge()` / `dispatch()`。
插件把 `see` 的 JPEG 作为真实 image content，文本中保留对应元数据。
失败返回 `isError:true`；不要把含 error 的 JSON 包成普通成功工具结果。
具体未发送与未知信息必须跨 JS、HTTP、Manager、Core 四层保留。

`/app/vscreen/launch` 不接受把宿主自身（`Context.getPackageName()`）启动到虚拟屏：系统不会为副屏另起实例，而是复用已在运行的同一进程，本进程里已绑定默认 display 的 WebView 在副屏会崩溃（T76 真机 tombstone：主线程 Looper → `libmonochrome` 空指针）。bridge 在授权询问前、Manager 在 claim 前、Core 在 `am start` 前都用 [VirtualScreenPolicy][policy] 的同一条判定拒绝，返回 `VSCREEN_SELF_LAUNCH_REFUSED`（`state=NOT_SENT`）；原生应用选择器也不列出宿主自己。其它应用不受这条策略影响，启动结果未知的语义照旧。

物理工具 [dsh-computer-use-android][physical-plugin] 使用另一条物理屏协议。
维护提示词时应保持两类工具的边界，不能用“active virtual”自动改物理请求含义。
Agent preset、会话权限与宿主全局能力是不同配置层；一个设置不能证明其他层已授权。

## 9. 授权、系统能力与竞争

[CapabilityBroker][broker] 提供当前 runtime/installation、期限、授权 epoch 和一次性 dispatch claim。
它只有一个原生确认槽，不在 broker 锁内执行平台操作、回调或 I/O。
[ScreenConfirmationPolicy][screen-policy] 支持全部手动、按应用规则、全部允许。
全部允许只减少 DSHA 的确认步骤，不能增加 Android 权限、产生节点或豁免目标检查。
密码焦点的硬拒绝与确认策略正交：即使选择全部允许，助手也不能把文本写进密码框（§6.1）。
本机规则持久化与本轮 [ScreenSessionGrant][screen-grant] 分开，授权不能从备份导入生效。

`HttpShellService.screenPolicyChanged()` 撤销请求及观察；全局同意变化会撤销各能力域。
停止、无障碍断开、手动撤销、暂停等路径使旧请求失效，部分路径同时撤销并关闭 Core。
请求排队后仍需再次检查 Web generation、运行状态、revision、实际窗口和包。
已 claim 后发生撤销，并不能证明系统调用没有发生，必须保留执行后变化或未知标记。

创建显示还取决于特权 UID、ROM 与隐藏 API。
`requestTrustedDisplay()` 只在实际 UID 为 Root 或确有 ADD_TRUSTED_DISPLAY 时请求 TRUSTED。
探测 UNKNOWN 按不持有处理；不能按宿主包名误判 shell 进程持有签名权限。
未受信任显示的焦点、IME、系统装饰和应用支持可能不同，不能用应用确认开关补齐。

## 10. 并发、超时与 UNKNOWN

Manager 以 `ACTIONS` 串行编排动作，以 `LOCK` 保护身份和状态快照。
状态锁与生命周期 epoch 共同阻止迟到回调更新新现场；不能只把 UI 按钮设为 disabled。
Core 的 `route()` 本身同步；捕获的 listener/socket 身份与 owner 检查在同一 monitor 下核对。
socket 握手、HTTP 头正文读写在路由锁外，避免慢客户端占住状态锁。
旧 listener 关闭后迟到的 IOException 只能退休自身，不能 shutdown 后来发布的新 binding。
Host fixture 允许同 JVM 重绑，因此尤其需要测试这种实例隔离；它不是手机复现声明。

[Transport][transport] 每次使用一个 socket、一次写入，不用可能隐式重试 GET 的连接池。
Core 请求有有界头、正文和读取期限；响应最大 4 MiB，不能无限读取半包。
插件另有响应预算与取消信号；较外层取消不代表较内层动作已取消。
`executionUnknown`、`INPUT_RESULT_UNKNOWN`、执行后 target/run changed 均禁止自动重放。
允许重新观察实际状态；不得把它实现成“失败后再点一次”的业务重试。

## 11. 关闭、退出证明与覆盖后恢复

```mermaid
sequenceDiagram
  participant U as Native 或精确 Core 的 DSH close
  participant M as Manager
  participant C as 原 Core
  participant J as 本机 journal
  U->>M: 关闭请求
  M->>M: detachLocked：先递增 epoch、撤销观察/启动票据
  M->>J: 保存 pending 清理身份
  M->>C: 一次 /vscreen/close
  C->>C: release 显示、帧与输入
  C-->>M: release acknowledgement
  M->>C: 原通道只读出生探针
  alt EXITED 且 journal commit 成功
    M->>J: STOPPED，清除 pending
  else ALIVE / UNKNOWN / journal 不可用
    M->>J: 保留 CLOSE_UNKNOWN / pending
    M->>M: 有界只读探针，不重发输入或启动
  end
```

Manager `closeExpected()` 核对 Core UUID，`detachLocked()` 先令旧请求失效。
`closeDetached()` 把 display release 回复和 `coreExitProof` 分开记录。
[ExitProofPolicy][exit-policy] 根据 boot、出生 stat、UID 和明确 ESRCH 分类 EXITED/ALIVE/UNKNOWN。
端口关闭、连接失败、权限拒读、超时、裸 PID 消失的猜测都不能替代完整出生证明。
已确认 EXITED 还需 `persistCleanupExited()` 成功提交 journal，才允许清除 pending。
正常完成应同时核对 `displayReleaseAcknowledged`、`coreExitProof`、`lifecycleState` 和 `cleanupPending`。
后台清理探针是只读收敛，不重发 close 或输入；不能改成无限启动/关闭循环。

[SessionJournal][journal] 保存应用私有 `vscreen-session-v1.json`，含本机秘密和清理 handle。
它绑定 installation/application，使用 NOFOLLOW、大小限制、私有权限和原子替换。
日志不得进入用户备份、公开诊断包或 Git；记录字段也不能复制为通用屏幕授权。
`restoreJournal()` 不恢复旧 UI 输入许可；同 boot 未清现场进入 DISCONNECTED 或 START_UNKNOWN。
新 boot 可改变退出证明条件，不能要求用户重启来掩盖同 boot 未知状态。
覆盖升级前应正常停止；外部覆盖可能终止宿主，不能承诺凭端口重连就完整恢复。
虚拟 Core 退出与 DSH guest/launcher 退出是两个证明域；正常 Stop 需分别遵守各自屏障。
后者由 [WebStopCoordinator](../app/src/main/java/com/deepseekharness/app/core/WebStopCoordinator.java) 等运行编排核验，不能用 Core ACK 替代。

## 12. 状态与诊断顺序

| 状态/错误 | 首先核对 | 不应采取的捷径 |
|---|---|---|
| START_UNKNOWN / DISCONNECTED | status 的 pending、清理身份是否存在、原通道可否只读探针 | 再 create 或换通道重放启动 |
| CLOSE_UNKNOWN | release ACK、出生 proof、journal commit 和 cleanup reason | 仅因画面消失就宣布 STOPPED |
| FRAME_NOT_READY / STALE_FRAME | generation、最新帧、是否刚从无帧恢复、采用回调 | 保图同时继续开放触摸 |
| 助手 see：VSCREEN_NO_WINDOW / FRAME_PENDING / FRAME_STALLED | windowState、windowEvidence、foregroundPackage、sinceReadyOrLaunchMs、nextAction | 空屏时等待或重建；无障碍不可用时把 UNKNOWN 当成空屏 |
| 助手 tree：NO_UNIQUE_APPLICATION_WINDOW | applicationWindowCount、windowAmbiguous、focusedWindowId、windowDiagnostic | 自动选一个应用窗口继续；对同一歧义屏反复重看 |
| FRAME_NOT_OBSERVED / FRAME_EXPIRED | requested/current/observed frame 字段及 TTL | 用 status 的新帧号替换旧图帧号 |
| STALE_TREE / STALE_NODE | 实际窗口/包、服务状态和树期限 | 自动刷新并补发旧业务动作 |
| NATIVE_CONTROL_REVOKED | 页面是否已暂停、面板是否已取消、Core 是否变更 | 重新激活旧 owner |
| INPUT_RESULT_UNKNOWN | 实际应用画面和状态，保留原动作回执 | 换坐标/节点/通道重复同一动作 |
| inputUnavailable / 无节点 | 后端缺失成员、系统权限、目标应用语义能力 | 宣称所有按钮都应可控 |

助手侧 `see` 把 Core 的 `FRAME_NOT_READY` 细分（`util/VirtualScreenObservationHint`，`legacyError` 保留原码）：该显示没有窗口且 Core 报不出前台应用 → `VSCREEN_NO_WINDOW` / `LAUNCH_APP_FIRST`；有窗口且距就绪或最近一次 launch 不足 10 秒 → `FRAME_PENDING` / `WAIT_AND_SEE_AGAIN`；超过 10 秒仍无帧 → `FRAME_STALLED` / `REPORT_TO_USER`；无障碍不可用且 Core 无前台包名时无法区分，保留 `FRAME_NOT_READY` / `LAUNCH_APP_OR_WAIT`。降级为纯图片时 `availabilityReason` 拆为 `ANCHOR_ACCESSIBILITY_NOT_ENABLED` / `_NOT_CONNECTED`、`ANCHOR_NO_WINDOW`、`ANCHOR_TARGET_EVIDENCE_UNAVAILABLE`、`ANCHOR_CONSENT_EVIDENCE_UNAVAILABLE`、`ANCHOR_REQUEST_EVIDENCE_REQUIRED`；tree/node/editor 拿不到窗口时 `error` 仍为 `ACCESSIBILITY_UNAVAILABLE`，另附 `reason` 与 `nextAction`。这些都是只读失败，不改变任何门控、不重放、不换屏；虚拟识屏页仍直接读 Core 预览，`FRAME_NOT_READY` 保持「等待中」语义。

先收集脱敏技术字段：错误码、阶段、方向/尺寸、状态、帧关系、后端类型和证明分类。
不要公开真实 token、handshake、清理 handle、用户输入、树正文或截图中的个人数据。
Native `lastStartOutcome()` / `safeStartOutcome()` 提供去除秘密和图像的启动技术摘要。
读日志前先确定它对应的源码、APK、flavor 与运行阶段；不要把另一包的 PASS 移植过来。

### 12.1 最小窗口证据与多窗口可行动提示（T98）

失败回复只做纯增量，不改变 `windowState` / `windowEvidence` / `windowDiagnostic` 既有语义，也不新增授权面
（`util/VirtualScreenWindowEvidence`，纯逻辑）：

- `see` 的 `FRAME_NOT_READY` 细分、tree/node/editor/anchor 的窗口不可用回复，在无障碍可读时附
  `applicationWindowCount` / `systemWindowCount` / `inputMethodWindowCount`、`focusedWindowId` /
  `activeWindowId`、`windowAmbiguous`；窗口唯一时再附 `targetWindowDisplayId` / `targetWindowId` /
  `targetWindowType` / `targetWindowLayer` / `targetWindowBounds` / `targetWindowFocused` /
  `targetWindowActive` / `targetWindowTitle` / `targetWindowPackage`。
- 字段只来自无障碍窗口清单：标题去掉控制字符、折叠空白并截断到 64 码点，包名按包名格式校验后保留，其余
  不合格值直接省略；不含节点文本、控件内容、截图或完整窗口清单。窗口 id 会复用，不能当长期身份。
- `NO_UNIQUE_APPLICATION_WINDOW` 用计数区分两种原因：多个应用窗口并列（分屏/悬浮窗）→
  `reason=VSCREEN_WINDOW_AMBIGUOUS`、`nextAction=ASK_USER_TO_CLOSE_SPLIT_SCREEN`；只有系统/输入法窗口、
  没有应用窗口 → `reason=VSCREEN_NO_WINDOW`、`nextAction=LAUNCH_APP_FIRST`。拿不到证据时仍回
  `SEE_AGAIN`，不凭空提示用户去关分屏。
- 证据只用于解释失败与下一步，不参与授权、选择或重试：语义路径仍然只认
  `VirtualScreenWindowPolicy.selectDetailed` 的唯一结果，锚点路径只认原观察携带的 windowId（同 id 重复
  按 `ANCHOR_WINDOW_AMBIGUOUS` 拒绝），不会因为“多窗口”自动换一个窗口。
- 真机验收：多窗口/分屏（应提示收起分屏而不是自动选窗）、IME 存在（只进 `inputMethodWindowCount`，不误判
  为应用窗口）、FLAG_SECURE 页面（截图失败时窗口证据仍只含元数据，不泄露内容）。

## 13. 修改指南与回归定位

| 修改范围 | 必须一起检查的实现 | 首选回归类或脚本 |
|---|---|---|
| 生命周期、关闭和 journal | Manager、Core、CoreIdentity、ExitProofPolicy | VirtualScreenLifecycleTest、CloseReconcileTest、PersistentCleanupTest、SessionJournalTest |
| Core listener / 协议 | Core、Transport、PeerProof、Handshake | VirtualScreenCoreControlTest、TransportTest、BridgeIdentityTest、VirtualScreenHandshakeTest |
| 启动通道与 ADB 票据 | Channels、Launcher、AdbBridge、AdbVirtualScreenStart | VirtualScreenChannelsTest、StartOutcomeTest、AdbVirtualScreenStartTest；test-adb-vscreen-bridge.py |
| 启动目标策略（self-launch） | VirtualScreenPolicy、Manager、Core、HttpShellService、Activity 选择器、server.cjs | VirtualScreenPolicyTest、VirtualScreenManagerOwnershipTest；test-bridge-port.mjs、test-prompt-language.mjs |
| 采集、预览与手势 | Pixels、Previews、PreviewView、PreviewInputGate | PixelsTest、PreviewContinuityTest、PreviewAdoptionTest、PreviewInputGateTest、NativeInputFrameBoundaryTest |
| 节点与原生面板 | Accessibility、NativeControls、Activity、NativeScreenControlLease | VirtualScreenNativeControlsTest、ActivityNodeControlsTest、ActivityLayoutTest |
| 原图锚点 | ObservedPixels、ObservedControl、AnchorProof | VirtualScreenObservedPixelsTest、VirtualScreenAnchoredChainTest；test-vscreen-anchored.mjs |
| 窗口证据与多窗口提示 | WindowPolicy、WindowEvidence、ScreenTargetResolver、ObservationHint、Manager/HttpShellService | VirtualScreenWindowPolicyTest、VirtualScreenWindowEvidenceTest、VirtualScreenObservationHintTest、ScreenTargetResolverEvidenceTest |
| DSH 工具/bridge | server.cjs、VscreenBridgeRequest、HttpShellService | VscreenBridgeRequestTest、VscreenAnchoredGrammarTest；test-vscreen-bundle.mjs、test-vscreen-manager-http.py |

表中省略共同前缀的测试类，位于 [vscreen 测试目录][tests] 或 [util 测试目录][util-tests]。
`AdbVirtualScreenStartTest` 在 bridge 测试目录；脚本在 [tools](../tools)。
不要只测试复制出来的 helper；布局/节点面板应覆盖真实 Activity，链测试应经过真实编排与传输边界。
资源型 fixture 构造中途失败也必须释放它自己的 Core，防止后续 CORE_ALREADY_BOUND 串案。
修改输入前先明确是 raw、semantic 还是 anchored 模式，不能通过移除身份检查修复可用性。
修改 UI 必须覆盖长错误、横竖屏、键盘安全区、面板取消、后台撤销和长字段不截断提交。
修改提示词应检查真实 assembled tool instructions，保留图片 content 与 isError，不只搜某个字符串。
修改资产后由统一构建负责人重算 descriptor、插件元数据和实际 APK 成员验证；不要并行改总资产。

## 14. 支持范围和验收边界

`VirtualScreenManager.supported()` 要求非 LOW_ANDROID 且 API 至少 30。
Standard 的 minSdk 为 30，但满足版本并不证明某个 ROM 的隐藏 API、显示和注入可用。
物理屏单独使用无障碍协议，不因虚拟屏不可用而自动切换。
显示可能创建成功但没有目标窗口；应用可能拒绝在外部显示启动或渲染。
安全窗口、密码内容、系统输入法、Canvas、动画与无障碍树缺失均可能限制功能。
事件或 ACTION_SET_TEXT 被 Android 接受，不等于目标应用业务结果已验证；动作后需重新观察。
软件单测可证明给定 fixture 的契约，不能证明手机帧率、触控普遍可用或全设备兼容。
真机结论必须绑定实际 APK，并记录具体操作、实际回执、画面变化和未执行范围。
当前暂停设备验收的交付不能附加“本轮手机已验证”的推断，也不应为补证擅自操作设备。

[activity]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenActivity.java
[manager]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenManager.java
[core]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenCore.java
[launcher]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenLauncher.java
[channels]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenChannels.java
[transport]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenTransport.java
[peer]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPeerProof.java
[http]: ../app/src/main/java/com/deepseekharness/app/HttpShellService.java
[accessibility]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenAccessibility.java
[semantic]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenSemanticAdapter.java
[plugin-index]: ../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/index.js
[plugin-server]: ../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs
[physical-plugin]: ../app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs
[adb]: ../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java
[adb-start]: ../app/src/main/java/com/deepseekharness/app/bridge/AdbVirtualScreenStart.java
[identity]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenCoreIdentity.java
[fence]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenRequestFence.java
[display-session]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenDisplaySession.java
[pixels]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPixels.java
[observed-pixels]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenObservedPixels.java
[previews]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPreviews.java
[foreground]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenForeground.java
[strip]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenStrip.java
[preview-settings]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPreviewSettings.java
[preview-policy]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenPreviewPolicy.java
[preview-view]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPreviewView.java
[preview-gate]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenPreviewInputGate.java
[policy]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenPolicy.java
[text-policy]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenTextInputPolicy.java
[display-preset]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenDisplayPreset.java
[display-settings]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenDisplaySettings.java
[input]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenInput.java
[observed-control]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenObservedControl.java
[anchor-proof]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenAnchorProof.java
[native-controls]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenNativeControls.java
[native-lease]: ../app/src/main/java/com/deepseekharness/app/screen/NativeScreenControlLease.java
[bridge-request]: ../app/src/main/java/com/deepseekharness/app/util/VscreenBridgeRequest.java
[broker]: ../app/src/main/java/com/deepseekharness/app/capabilities/CapabilityBroker.java
[screen-policy]: ../app/src/main/java/com/deepseekharness/app/util/ScreenConfirmationPolicy.java
[screen-grant]: ../app/src/main/java/com/deepseekharness/app/util/ScreenSessionGrant.java
[exit-policy]: ../app/src/main/java/com/deepseekharness/app/util/VirtualScreenExitProofPolicy.java
[journal]: ../app/src/main/java/com/deepseekharness/app/vscreen/VirtualScreenSessionJournal.java
[tests]: ../app/src/test/java/com/deepseekharness/app/vscreen
[util-tests]: ../app/src/test/java/com/deepseekharness/app/util
