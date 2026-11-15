# 实体屏特权执行核心（T130 Phase A）

> 状态：Phase A（实验、默认关闭、未真机验收）。目标构建 build351。
> 用户 2026-11-10 决定：实体屏（display 0）的 AI 操作改走特权执行路线，目标场景是「用助手玩游戏」；
> 该决定覆盖 T107 Q2「本期不改特权注入」的推荐。T107 的 evdev 用户介入检测并入 Phase B。
> 技术路线参考外部参考实现（结构参考）的「固定动词 + 特权 Core + 帧/动作一次往返」；本仓库未复制其源码。

## 它解决什么

无障碍动作在 Canvas / SurfaceView / 自绘游戏界面上覆盖很差，而且「动作 → 重新读屏」的往返对游戏太慢。
Phase A 复用已有的受管 `app_process` 特权 Core 基础设施，把「虚拟屏 Core」扩展成「物理屏 Core」：

- 在 display 0 上抓帧（有界尺寸/质量/帧率，**不落盘、不进日志**）；
- 向 display 0 注入 tap / swipe / key（固定动词，typed 参数）；
- 动作回执内直接带回新帧与新的一次性观察凭据，**一次往返继续下一步**；
- 整条路径不依赖无障碍服务。

## 组件与启动

| 组件 | 位置 | 职责 |
|---|---|---|
| `PhysicalScreenCore` | `app/src/main/java/com/deepseekharness/app/vscreen/PhysicalScreenCore.java` | Core 侧 `/priv/*` 路由、参数边界、目标复核、动作与回执 |
| `PhysicalScreenDisplaySession` | `vscreen/PhysicalScreenDisplaySession.java` | `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 镜像 display 0、ImageReader 抓帧、帧编解码、黑帧标记、凭据签发/消费 |
| `PhysicalScreenLauncher` | `vscreen/PhysicalScreenLauncher.java` | 只生成当前安装包的 `app_process --launch --physical` 启动器 |
| `PhysicalScreenManager` | `vscreen/PhysicalScreenManager.java` | App 侧握手、请求、凭据状态、`state/error/nextAction` 回执与 benchmark |
| `VirtualScreenCore` | `vscreen/VirtualScreenCore.java` | 共用受管入口；`--physical` 时只走 `/priv/*` 路由，虚拟屏路由不可达 |
| `PrivilegedScreenPolicy` / `PrivilegedScreenRoutes` | `util/` | 纯逻辑：参数边界、坐标换算、凭据判定、目标判定、benchmark 统计、端点表 |

启动复用虚拟屏的受管通道（Root / Shizuku / ADB）：

```text
app_process -Djava.class.path=<当前安装包> /system/bin \
  com.deepseekharness.app.vscreen.VirtualScreenCore \
  --launch --physical --port 0 --package <当前包名>
```

`DeviceShellPolicy.inspectPhysicalScreenLaunch` 对这条命令逐项核验（安装包路径、类名、固定 flag 顺序、
包名），并并入 `inspectVirtualScreenLaunch` / `inspectVirtualScreenProbe` 所在的同一受管入口。
`app_process` 的通用命令仍然直接拒绝。

Core 子进程用 `setsid` 独立会话启动，端口/token/出生身份只经受管通道回执返回，不出现在 argv；
同一 Core 类、同一握手与 peer proof（`X-DSHA-VScreen-Proof`）与虚拟屏共用。

## 桥端点

所有端点都是 `GET`，只接受完整精确路径；查询参数由桥侧解析后交给 Manager。
每个回执都带 `accessibilityRequired:false`；写入/动作类回执带 `state`、`error`、`nextAction`、
`retryable`、`automaticRetry`、`observation`。

| 端点 | 作用 | 关键参数 |
|---|---|---|
| `/app/priv/info` | 状态：displayId=0、代次、帧号、帧龄、目标包、输入后端、帧配置 | 无 |
| `/app/priv/frame` | 取帧并签发一次性 `observationId`；同帧只回 `unchanged` | `since` |
| `/app/priv/config` | 调整帧通道（变化会重建镜像并更换代次） | `maxWidth` 320–1920、`jpegQuality` 30–90、`maxFps` 1–10 |
| `/app/priv/tap` | 点击并在预算内等新帧 | `x`、`y`、`frameSeq`、`observationId`、`generation`、`waitMs` 0–500 |
| `/app/priv/swipe` | 滑动 | `x1`、`y1`、`x2`、`y2`、`ms` 1–3000，其余同上 |
| `/app/priv/key` | 固定按键（返回/主页/回车/退格：3/4/66/67） | `keycode`、`frameSeq`、`observationId`、`generation` |
| `/app/priv/benchmark` | N 次 tap 的低延迟基准，输出 p50/p95/max 与每步样本 | `n` 1–20、`x`、`y`、`waitMs` |
| `/app/priv/close` | 显式关闭 Core（失败按未确认处理，不重试） | 无 |

坐标使用**回执图像像素空间**（`width` × `height`）；Core 换算成 display 0 坐标后注入，
越界、NaN、无穷都返回 `PRIV_COORDINATE_OUT_OF_BOUNDS` / `PRIV_INVALID_COORDINATE`，不做静默夹取。

帧通道默认值：`maxWidth=1280`、`jpegQuality=60`、`maxFps=6`。实际值随每次 `info` / `frame` /
动作回执的 `frameConfig` 返回；benchmark 报告必须写清它。

## 动作凭据（与虚拟屏的差别）

虚拟屏是静态界面，输入要求「最新帧」；游戏画面连续变化，若坚持最新帧就永远无法动作。实体屏的规则是：

1. 调用方必须先用 `/app/priv/frame`（或上一次动作回执）拿到 `observationId`；
2. 动作必须带 `frameSeq` + `observationId` + `generation`，三者与 Core 最近一次签发的观察一致；
3. 观察自签发起 30 秒内有效（`OBSERVATION_WINDOW_MS`）；
4. 一次动作消费一次凭据；动作回执签发**新的**观察，可以直接做下一次动作，不必重新取帧；
5. Core 先校验 `expectedPackage`（来自最近一次回执的目标包）：包名变化或无法核验即
   `PRIV_TARGET_CHANGED` / `PRIV_TARGET_UNVERIFIABLE`，不注入；
6. 结果未知（`EXECUTION_UNKNOWN` / `executionUnknown`）**不自动重放**，旧凭据不恢复；
   调用方必须重新观察后自行决定；
7. 失败 fail-closed，**不静默回退无障碍**。

## 授权链

动作/观察请求按顺序经过：

1. **设备能力开关**：「设置 → 设备能力授权 → 特权执行」，原生授权、默认关闭、可随时关闭；
   存储键 `privileged_execution_v1`，不从环境、插件或备份恢复。关闭时立即丢弃本地 Core 连接。
2. **当前手机任务**：必须携带真实工具环境的 `taskSessionId` / `turnId`，并先 `/app/ui/task-begin`；
   任务不存在、不是当前任务或已暂停一律 `NOT_SENT`。这就是「仅任务期间」。
3. **目标窗口**：目标包必须可核验（`packageVerified`），否则 `PRIV_TARGET_UNVERIFIABLE`。
4. **敏感应用**：`SensitiveAppPolicy` 判为敏感的应用在 Phase A 一律拒绝
   （`PRIV_SENSITIVE_TARGET_UNSUPPORTED`）；无障碍关闭时没有敏感界面语义证据，不能安全放行。
5. **屏幕确认策略**：复用 `ScreenConfirmationPolicy.allows(package)`。Phase A 不发原生逐动作确认窗，
   因此 ASK 一律按 `NOT_SENT`（`SCREEN_CONFIRMATION_REQUIRED`）处理；只放行「全部允许」或该应用显式
   `ALLOW`。这与虚拟屏「复用当前屏幕授权确认」的语义一致，只是 Phase A 把 ASK 落成拒绝而不是弹窗。

## benchmark 与延迟口径

`/app/priv/benchmark?n=&x=&y=&waitMs=` 做 N 次完整动作链：请求发出 → Core 注入 → 等新帧 → 回执。
报告字段：

- `roundTripMs`：App 侧每次请求发出到回执到达（含网络与 JSON 解码）；
- `inputMs`：Core 内注入调用的耗时；
- `frameWaitMs`：Core 注入完成到观察到新帧的耗时；
- 三者各自的 `count` / `p50` / `p95` / `max` / `min`（最近秩百分位，取真实样本，不插值）；
- `frameConfig`、`waitMs`、`channel`、`coreInstance`、`generation`、`accessibilityRequired:false`。

单次请求边界：`n ≤ 20`，且 `n × (waitMs + 400) ≤ 8000`，避免 benchmark 自己撞上传输层 12 秒预算。
真机数据必须记录：机型/系统、通道、帧配置、N、p50/p95/max，以及是否关闭无障碍。

## 黑帧与安全界面

未受信任的镜像里，`FLAG_SECURE` / 安全图层通常表现为黑帧。Core 以 48 像素宽的采样做保守判定：
≥98% 采样点为近黑（RGB 各 ≤8）时标 `secureOrBlack:true`，同时仍允许动作（调用方看过黑帧，
选择是否动作是它的决定）。这是**启发式标记，不是「已确认没有敏感内容」的证明**；Phase A 也不承诺
对安全界面做分类，已知敏感应用直接拒绝。旋转 / 分屏 / 分辨率变化会重建镜像并更换 `generation`，
旧观察与旧凭据全部失效。

## 不做（Phase A 边界）

- 不做多指 / 缩放 / 手势宏；
- 不把特权注入用于没有当前手机任务授权的场景；
- 不做 evdev 用户介入检测（T107 M1，Phase B）；
- 不发原生逐动作确认窗（ASK 即拒绝）；
- 不保证 Shizuku / ADB 通道（Phase A 真机验收只覆盖 root 通道；Shizuku/ADB 走同一受管入口但未验收）；
- 不安装、不推送、不合并、不发布。

## 测试与真机验收

单测（纯逻辑，不依赖设备）：

- `PrivilegedScreenPolicyTest`：帧配置边界、采样尺寸、坐标/时长/等待边界、凭据（代次/观察/超时/单次）、
  目标、黑帧阈值、benchmark 边界与百分位；
- `PrivilegedScreenRoutesTest`：桥/Core 端点表、动作/内容读取分类；
- `PrivilegedScreenLaunchPolicyTest`：实体屏启动器只认精确参数，与虚拟屏启动/探针互不越权；
- `PhysicalScreenManagerTest`（Robolectric）：受管启动、一次性凭据、UNKNOWN 不重放、目标/代次绑定、
  benchmark 统计、传输不可用时丢弃端点。

真机验收（由主会话/用户窗口执行，工作会话不擅自上机点按）：Lenovo TB321FU / Android 16 / KernelSU：

1. 打开游戏（非无障碍可读界面）→ `info` 有目标包与 display 0；
2. `frame` 拿到图；`tap` 后一次回执拿到新帧；
3. `swipe` / `key` 到游戏；
4. **关闭无障碍服务后仍可抓帧与注入**；
5. `FLAG_SECURE` 界面标 `SECURE_OR_BLACK`；
6. 跑 `/app/priv/benchmark` 记录 p50/p95/max，并与同一动作的 `/app/ui/*` 路径对比（不作为替代）；
7. 关闭能力开关后所有端点 `NOT_SENT`，Core 空闲退出。

## 已知风险与后续

- 镜像抓帧依赖系统显示服务权限；Phase A 以 root 通道为准，其它通道需独立验证；
- 黑帧判定是采样启发式，安全界面拦截依赖「已知敏感应用拒绝 + 屏幕确认策略」，不是内容识别；
- 没有 per-action 确认窗意味着用户必须在设置里显式允许目标应用；这是有意的 fail-closed 取舍；
- Phase B 计划：evdev 用户介入检测（用户一碰屏幕即暂停注入）、多指/手势、Shizuku/ADB 真机矩阵、
  把特权路径接入内置工具（沿用 observationId 语义），以及更完整的敏感界面分类。
