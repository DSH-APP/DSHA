# 外接设备页面变化快速识别（T134）：观察通道设计 + 真机测量

日期：2026-11-10（+08）。范围：`extdev/` 外接 Android 目标的只读观察通道，对应 `/app/extdev/watch` 与
`/app/extdev/operate?...&observe=1`。本文记录候选机制真机测量、选型、接口契约、失败语义与实施边界。

## 1. 问题与现状

用 USB-ADB 操作另一台设备时，宿主只能靠重新 `dumpsys` / `uiautomator dump` / `screencap` 才知道目标是否换了
页面：每次检查都要重新拉起一次专属 adb server（`ExternalDeviceHost` 每次桥请求新建实例，`runAdbSteps` 内部
一个 nodaemon server + 若干客户端命令），延迟数百 ms 到数秒，且没有“动作后新观察”的等价语义。

源码事实（基线 `2dcd07e61`）：

- `ExternalDeviceHost` 只有请求/响应式 `discover / inspect / operate / preflight / import`；无事件流、无动作后
  观察、无 settle。
- 每个桥请求一个 `ExternalDeviceHost`；`runAdbSteps` 在同一个 root helper 内启动**一个**专属 nodaemon adb
  server，最多 64 步，每步输出经 `[DSHA_STEP <nonce> <n>]` 分隔；root helper 整段 stdout 上限 **768 KiB**
  （Base64 后约 576 KiB 有效载荷），单步 `ShellService.execExternal` 上限 768 KiB。
- 目标命令白名单（`ExternalToolPolicy`）只允许只读形态：`dumpsys` 仅 `battery / display / meminfo / cpuinfo /
  window` 单参数；`logcat` 允许 `-d / -b / -s / -t / -v` 等诊断参数；`screencap` 属文件传输组件，未开放；
  刷写类全部拒绝。
- 宿主侧 `ScreenSettlePolicy`（T105：首个事件 ≤800ms、静默 250ms、总上限 2.5s）只适用于本机无障碍事件，外接
  设备没有等价事件源。

## 2. 候选机制与真机测量

测量环境：OnePlus PJD110 / Android 16 / USB-ADB（容器侧 `/root/usb-adb` → root adb server
`127.0.0.1:5038`，serial `f55ffc45`）。只读命令与 `am start` 打开系统设置 / 本机 DSHA 测试页 / 桌面做页面切换；
截图只在内存做哈希，未落盘、未入库。测量窗口 2026-11-10 04:49–05:05（+08），起测前台为 DSHA 测试页，测量后
回到桌面；未观察到用户操作打断。

### 2.1 机制 A：logcat events 事件流（主通道）

事实：Android 16 的页面事件是 **`wm_resume_activity` / `wm_set_resumed_activity`**
（payload 如 `[0,token,taskId,pkg/.Act]`、`[0,pkg/.Act,reason]`），本机没有 `am_focused_activity`。
`logcat -b events -s <tag>:V ... -v epoch` 的过滤对**整个 events 缓冲区**生效，输出只含匹配事件（实测 174 行 /
约 16.7 KB）。

关键坑（真机实测）：`logcat -t N` 先截取原始缓冲区尾部、再应用标签过滤；带 `-t` 时过滤结果为空。观察采样命令
**不能带 `-t`**。

| 指标 | 值 |
|---|---|
| 单次过滤采样（一次 adb shell） | 平均 154 ms，约 16.7 KB |
| 事件投递延迟（流式读取） | 约 100 ms（event_age 700 ms − 设备时钟快 597 ms） |
| 轮询检测延迟（9 次页面切换） | p50 123.2 ms / p95 146.0 ms / max 146.0 ms（多数一次命中） |
| 连续流检测延迟（9 次） | p50 114.9 ms / p95 167.9 ms / max 167.9 ms |

结论：事件驱动、开销低、能给出包名 / Activity 与发生顺序；不覆盖 WebView 内部跳转、Canvas、画面内动画（事件
只描述 Activity/窗口层级）。

### 2.2 机制 B：`dumpsys window` 权威窗口采样（兜底）

| 指标 | 值 |
|---|---|
| 单次调用 | 平均 166.6 ms，112.4 KB |
| 轮询检测延迟（9 次） | p50 128.4 ms / p95 159.9 ms / max 159.9 ms |

结论：`mCurrentFocus` / `mFocusedApp` 是权威前台状态，适合在事件样本缺失（旧系统、OEM 无事件）时做一次确认；
单次 112 KB 输出不适合作为连续采样主通道（多份会逼近 768 KiB helper 上限）。

### 2.3 机制 C：画面通道

- `screencap -p` 轮询（内存哈希）：单次平均 **1610.9 ms**、2.78 MB；检测 p50 **921.6 ms** / p95 **2729.6 ms** /
  max 2729.6 ms。太慢、太贵，只配逐次兜底。
- `screenrecord --output-format=h264` 流：启动 277.6–548.2 ms；有页面变化时 6.86 MB / 4.4 s、153 个 VCL 帧
  （约 35 fps、约 15.6 Mbps）；页面切换后首个编码帧 **37.0–42.6 ms** 到达；静态画面 3.6 s 只有 5 帧 / 85.5 KB，
  说明编码输出受画面内容/管道背压影响。要真正做帧差还需解码器、分块哈希与背压处理，带宽与 CPU 成本高。

结论：画面通道延迟潜力好（首帧 ≈40 ms），但宿主 App 内需要完整 h264 解码/帧差管线；本轮不进入 Phase B，
留作 Phase C 视觉兜底（见 §7）。

## 3. 选型

1. **主通道：logcat events 过滤采样**（机制 A）。在**同一个 adb 会话**里顺序采样多次，避免每次检查都重启
   server；默认 6 个样本，样本节奏即 adb 往返（实测约 120–160 ms/次）。返回变化判定与 settle。
2. **权威兜底：`dumpsys window` 单次采样**（机制 B），`mode=window`；事件样本为空或调用方要确认“当前前台到底是
   谁”时使用。
3. **Phase C 视觉兜底**（机制 C）：`screenrecord` h264 + 帧差需要解码/背压与更完整测量，且 15.6 Mbps 级别流量
   不适合默认开启；只作为后续候选，不在本轮接口中承诺。

## 4. 接口契约

### 4.1 `/app/extdev/watch`

只读；要求外接设备能力已开启、root 可用、目标已绑定（与 `operate` 相同的前置检查；不需要原生确认）。

参数：

| 参数 | 取值 | 默认 | 说明 |
|---|---|---|---|
| `mode` | `events` / `window` | `events` | `events` 在一个 adb 会话内多采样；`window` 单次权威采样 |
| `since` | 空或 16–64 位小写十六进制 | 空 | 上次返回的 `digest`；与当前对比，不一致直接 `CHANGED` |
| `samples` | 2–12 | 6 | `events` 的采样次数（`window` 忽略） |
| `quiet` | 1–samples-1 | 2 | 末尾连续多少个样本同一页面算 `settled` |

返回（节选）：

```json
{
  "kind": "dsha-extdev-observation-v1",
  "mode": "events",
  "state": "CHANGED | UNCHANGED | UNCERTAIN | OBSERVED",
  "serial": "f55ffc45", "product": "PJD110", "epoch": 9,
  "sequence": 42, "timestampMs": 1794258000123,
  "changed": true, "sawChange": true, "partial": false,
  "samples": 6, "plannedSamples": 6,
  "changedAtSample": 2, "newEvents": 1,
  "foregroundPackage": "com.android.settings",
  "foregroundActivity": "com.android.settings.Settings",
  "component": "com.android.settings/com.android.settings.Settings",
  "digest": "…32 位十六进制…",
  "sinceDigest": "…（调用方传入时原样返回）",
  "settle": {"settled": true, "sawChange": true, "newEvents": 1, "samples": 6,
             "changedAtSample": 2, "quietRun": 3, "quietSamples": 2, "source": "logcat-event"},
  "elapsedMs": 812, "nextAction": "READ_STATE_OR_ACT"
}
```

语义：

- `state=CHANGED`：本窗口内出现新页面事件且前台摘要与 `since`/窗口基线不同，或样本直接与 `since` 不同。
- `state=UNCHANGED`：窗口内没有看到前台页面变化；`settle.settled=true`（同一摘要的末尾连续样本 ≥ `quiet`）。
- `state=OBSERVED`：`mode=window` 且没有 `since` 时的单次权威采样（不判断变化）。
- `state=UNCERTAIN`：没有拿到可用样本（事件标签缺失、读取失败、会话中断）；`error` 给出原因，
  `automaticRetry=false`。**只读失败不是动作 UNKNOWN**：没有修改目标，可以直接重新观察，但不得据此重放任何动作。
- `digest` 是 `SHA-256("dsha-extdev-observe-v1\n<epoch>\n<mode>\n<pkg>\n<activity>")` 的前 128 位；目标代次参与
  摘要，重新绑定后旧 `since` 自动失效；摘要不含屏幕文本，可安全回传。
- `sequence` 是 App 进程内单调递增的观察序号（重启归零）；`timestampMs` 是宿主墙钟。
- 限速：同一进程两次 `watch` 间隔 < 250 ms 时返回
  `{"state":"NOT_SENT","error":"RATE_LIMITED","retryAfterMs":N}`，**未发送任何目标命令**。
- 订阅生命周期：桥是请求/响应模型，没有常驻订阅；每次调用是一个有界采样窗口，`since` 让调用方把多次调用串成
  连续的“等待变化”。无状态，不会在宿主或目标留下观察进程。
- 预算：单次最多 12 个样本 + 2 个身份属性步骤；会话上限 30 s；目标身份（`getprop ro.product.device` /
  `ro.build.fingerprint`）在同一会话内先复核，再使用样本；结束后再次核对绑定未被清除/替换。

### 4.2 `operate?...&observe=1`

- 只对已派发且不是 `UNKNOWN` 的动作生效（安装 `state=UNKNOWN` 不附）；确认流程、`NOT_SENT`、
  `[POLICY_BLOCKED]`、`[EXECUTION_UNKNOWN]` 语义不变。
- 动作执行后在同一请求内追加 **4 个事件样本**（固定），附 `settle` 与 `observation`（`kind` 同上，含前台包 /
  Activity / `digest` / `changed`）。
- 观察失败只写 `observationUnavailable` + `nextAction=OBSERVE_WITH_WATCH` + `automaticRetry=false`，**不改写动作
  状态**；动作 UNKNOWN 时调用方仍须先核对目标实际状态，不得重放。

## 5. 失败与并发语义

- 未开启能力 / 无 root / 未绑定 / 请求或 Web 代次失效：抛出既有错误（`[DISABLED]`、`[NO_PERMISSION]`、
  `TARGET_NOT_BOUND`、`EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH`），HTTP 层返回 `state=ERROR`。
- 同一 root helper 最多 3 个并发；`watch` 会占用一个槽位直到采样结束（默认约 1 s）。限速 + 3050 ms 级总预算
  用于避免多个调用方把槽位打满。
- 目标在读样本期间被 `forget` / 重绑：会话结束前会复核绑定，返回 `TARGET_BINDING_CHANGED`；不自动重试。
- 采样命令全部只读，不写目标文件、不读用户数据、不落盘、不记录屏幕文本；拒绝把屏幕文本或截图写入仓库。

## 6. 真机测量表（T134 Phase A 结论）

| 机制 | 检测延迟 p50 | p95 | max | 单次开销 | 适用 |
|---|---|---|---|---|---|
| logcat events 采样（主） | 123 ms | 146 ms | 146 ms | 154 ms / 16.7 KB | 页面级变化、动作后 settle |
| `dumpsys window`（兜底） | 128 ms | 160 ms | 160 ms | 167 ms / 112 KB | 权威前台确认 |
| `screencap` 哈希轮询 | 922 ms | 2730 ms | 2730 ms | 1611 ms / 2.78 MB | 逐次视觉兜底 |
| `screenrecord` h264（Phase C） | 首帧 37–43 ms（编码帧到达） | — | — | 277–548 ms 启动；~15.6 Mbps | 需解码/帧差管线 |

## 7. 限制与后续

- **事件只到 Activity/窗口层级**：同一 Activity 内的 WebView 跳转、Fragment 切换、Canvas/视频内容变化不会产生
  `wm_resume_activity`；这些场景需要 Phase C 视觉摘要（`screenrecord` h264 + 帧差 / 解码后分块哈希）。
- **OEM 差异**：Android 11–16 的页面事件标签以 `wm_*` 为主，旧版可能有 `am_*`；观察解析同时接受两族标签，但
  “目标系统是否真的写入 events 缓冲区”需逐机型验收。事件缺失时接口返回 `UNCERTAIN`，调用方改用 `mode=window`。
- **采样节奏不是硬实时**：样本间隔由 adb 往返决定（实测约 120–160 ms），`settle` 按“末尾连续样本”判定，不承诺
  毫秒级静默窗口；接口如实返回 `samples / quietRun / changedAtSample`。
- **旧系统**：`logcat -s` 的多标签过滤与 `-v epoch` 在 Android 11+ 已覆盖；若目标不支持，`UNCERTAIN` +
  `mode=window` 兜底。
- **Phase C 验收点**：页面切换 / 滚动 / 弹窗 / WebView 内部跳转 / 前台包变化 / 目标锁屏与亮屏；对比 logcat 与
  `dumpsys window` 的一致性；事件缺失机型的降级路径；多调用并发与 root 槽位耗尽。

## 8. 测试

- 纯逻辑：`ExternalFocusEventsTest`（epoch/threadtime 解析、组件规范化、`dumpsys window` 解析、摘要绑定代次与
  模式、不含屏幕文本）、`ExternalWatchPolicyTest`（无变化 / 变化后静默 / 仍在变化 / since 命中与失效 / 缺样本
  partial / UNCERTAIN）、`ExternalRateLimitTest`。
- 路由与边界：`BridgeRoutesTest`（`/app/extdev/watch` 精确匹配、子路径拒绝）、`ExternalToolPolicyTest`（观察
  采样命令与 `dumpsys window` 在原生只读白名单内，`logcat -c` 仍拒绝）。
- 真机验收（Phase C 待用户窗口）：观察接口的 p50/p95 与本文测量一致；`observe=1` 在安装 / 重启等动作后不改变
  动作回执状态；UNKNOWN 不附观察；限速返回 `NOT_SENT`。
