# 屏幕活动历史与高频操作会话设计稿

状态：**M1 已实现（分支 `feat/screen-activity-history`，2026-11-08），M2–M5 仍为设计稿**。基线 `origin/main = ad9b93849`。M1 实现范围与后续差异见 §8。
关联：[执行期护栏现状](docs/design/screen-action-guard-20261109/README.md)、[T39 单一守卫](docs/design/screen-guard-20261103/README.md)。

## 0. 目标

让助手**知道用户刚才在屏幕上做了什么**，并能在受控范围内以接近“玩游戏”的频率操作屏幕：

1. 实体主屏有**屏幕历史**：目标切换、用户触碰、内容变化、助手自己的动作，按时间可查。
2. 每次屏幕工具回执自动带**最近活动摘要**（`recentActivity`），不必额外轮询。
3. 原生**让出提示**：用户切走/触碰/界面变化时，用户和助手都能立刻知道。
4. 高频会话（高频操作模式）：绑定单个应用、有可见指示、有时限，期间放宽逐次确认与目标变化拒绝。

非目标：不改设备 shell 白名单；不绕过 Android 权限；不隐藏操作；不对敏感应用做高频会话。

## 1. 硬限制（决定架构，不因“砍守卫”消失）

| 限制 | 影响 | 应对 |
|---|---|---|
| 无障碍 `takeScreenshot` 有系统限流 | 连续截图到不了 30fps | 高频会话用 **MediaProjection**（VirtualDisplay + ImageReader，30–60fps）；无授权时退回 1–3fps 自适应 |
| 普通无障碍服务拿不到原始 MotionEvent | 用户手指坐标默认不可得 | 语义触碰（控件 + bounds 中心）；Android 14+ `setMotionEventSources(SOURCE_TOUCHSCREEN)` 做真机试验，可用才记轨迹 |
| LLM 单轮往返 1–5s | **纯模型闭环玩不了实时动作游戏** | 高频会话里跑**本地快速环**（合成策略/脚本），助手只设目标、看历史、纠偏 |
| 单指手势 API | 游戏常需多指 | 平台 `GestureDescription` 支持多 stroke；扩展 `/app/ui/touch` 支持多指会话 |
| 各 OEM 事件/限流差异 | 无法逐型号适配 | 能力探测 + 分级降级；不承诺全机型一致 |

## 2. 架构

```
DshaAccessibilityService ──events──▶ ScreenActivityLog（内存环形）
MediaProjection (可选)   ──frames──▶ FrameRing（缩略图 + 指纹）
                                        │
        ┌───────────────────────────────┼──────────────────────────┐
        ▼                               ▼                          ▼
  /app/ui/history               每次 /app/ui/* 回执           ScreenYieldHint（原生）
  （助手显式查询）               recentActivity（自动附带）    悬浮条 2s / 静默通知
```

### 2.1 事件模型

```
{t, kind, source, target{package,activity,windowId,display}, node?, gesture?}
kind: TARGET_CHANGED | USER_TAP | USER_SCROLL | USER_INPUT | CONTENT_CHANGED
    | ASSISTANT_ACTION | SESSION_START | SESSION_STOP
source: user | assistant        // 按派发窗口区分，自己的点击不记成用户
node: {text?|desc?, class, boundsCenter:{x,y}}   // 密码框只记 "已输入"
gesture: [{x,y,t}...]           // 仅原始触摸可用时，下采样 ≤ 12 点
```

### 2.2 存储

- **内存环形，不落盘**：120 秒 / 64 条事件；缩略图 60 帧（400×640 JPEG q60，约 50–80KB/帧，合计 3–5MB）。
- 任务结束（task-end）、会话停止、服务断开即清空；不进备份、不进诊断日志、不上传。
- 语义快照：关键事件（目标变化/用户点击/会话开始）附一份紧凑树摘要（≤2KB）。

### 2.3 原生提示

- 切走：「助手已让出：你切到了『桌面』」
- 触碰：「注意到你在『安装』点了确认」/「你在『设置』里滚动」
- 变化：「界面已更新，助手重新观察」
- 形式：非焦点悬浮条 2 秒自动消失；DSHA 不在前台时静默通知；同事件 2 秒内去重。
- 可关（设置 → 设备能力授权 → 屏幕活动提示）。

## 3. 工具与回执

### 3.1 自动附带（M1 核心）

每个 `/app/ui/*` 回执的 `result` 里加：

```json
"recentActivity": [
  {"t":17.2,"kind":"USER_TAP","target":"com.rosan.installer.x.revived","label":"安装","at":[800,1674]},
  {"t":19.8,"kind":"TARGET_CHANGED","target":"io.github.sunsetrne.dsha.test","activity":"MainActivity"}
]
```

- 只写**上一次回执之后**的事件，最多 3 条；单条约 40–80 token。
- 任务未开始时为空数组。

### 3.2 `android_history`

```
GET /app/ui/history?since=<ms|序号>&limit=20&shots=0
→ {events:[…], frames:[{id,t,fingerprint}], truncated:false}
```

MCP 工具 `android_history`（服务器 `dsh-computer-use-android`）：纯文本事件行；
`shots=1` 时附最近 1 张缩略图（图片内容，默认关，避免烧 token）。

## 4. 高频会话（高频操作模式）

进入：设置页或工具 `android_session?mode=high_frequency&package=…&minutes=10`。

| 项 | 规则 |
|---|---|
| 绑定 | 只对指定包名生效；前台换成别的应用 → 自动暂停 + 原生提示；切回 → 自动恢复 |
| 可见性 | 常驻悬浮条显示「高频会话 · <应用> · 剩余 8:32」，任何时刻可停止 |
| 时限 | 默认 10 分钟上限，到时自动结束 |
| 放宽 | ① 动作不再逐次确认（会话开始时确认一次）；② 目标变化不再逐次拒绝（会话内以"绑定应用"为准，离开即暂停）；③ 坐标动作可用 500ms 内的帧（指纹复核仍在派发前做） |
| 保留 | ① 会话可见指示；② 敏感应用排除（`SensitiveAppPolicy`）；③ 安装/系统设置/设备 shell 不纳入会话；④ 所有动作仍记入历史；⑤ 用户随时可停；⑥ 不隐藏、不后台静默操作 |
| 失败语义 | 仍区分 NOT_SENT / UNKNOWN；不重放 UNKNOWN |

本地快速环（高频会话的关键）：会话内可加载一个**本地控制器**（脚本/策略），按 10–30Hz 读帧、
发多指手势；助手通过历史与截图监督。模型不参与每帧决策。

## 5. 隐私与安全模型更新

- 这是对现有「无障碍服务不持续记录」承诺的**有意改变**：必须在 `docs/security-model.md`
  写明新增能力、内存边界、默认开关与排除项。
- 默认：随 phone task 开启记录（内存、任务结束清空）；设置里可完全关闭。
- 排除：`SensitiveAppPolicy` 命中的应用只记 `TARGET_CHANGED`；密码框不记文本；
  通知/输入法内容不记。

## 6. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | 事件记录 + `recentActivity` 自动附带 + `android_history`；加 `typeViewClicked/typeViewScrolled`；语义触碰 | **已实现**：单测覆盖事件分类、摘要截断、密码框/敏感应用排除、任务隔离与参数校验；真机验收待补 |
| M2 | 原生让出提示（切走/触碰/变化）+ 去重 + 开关 | 真机：切走/点击都能看到提示条；任务结束不再提示 |
| M3 | 真机试验 `setMotionEventSources`；可用则记录原始轨迹，不可用保留语义近似 | 试验报告 + 能力探测降级测试 |
| M4 | 内存缩略图环 + `history?shots=1` | 内存占用 ≤6MB；任务结束清空可验证 |
| M5 | 高频会话 + 本地快速环（MediaProjection、多指手势） | 真机连续操作 10 分钟，可见指示常驻，退出即停 |

## 7. 不砍的红线（即使“砍守卫”）

- 不隐藏操作：会话指示常驻可见，用户可一键停。
- 不越权：不把会话扩到安装、系统设置、设备 shell、敏感应用。
- 不伪造结果：NOT_SENT/UNKNOWN 语义不变，不自动重放。
- 不落盘、不外传：内存历史随任务/会话结束清空。
- 模型生成的动作仍过 `CapabilityBroker`；放宽的是策略，不是完整性校验。

## 8. M1 实现说明（本分支）

实现与规格的对应关系，以及刻意留到后续阶段的部分：

- 事件：`TARGET_CHANGED / USER_TAP / USER_SCROLL / CONTENT_CHANGED / ASSISTANT_ACTION`。
  无障碍事件按派发窗口（助手动作后 1.5 秒）区分 `source=user|assistant`；助手派发由动作执行器显式记录
  `ASSISTANT_ACTION`（`action` 字段），不依赖猜测事件来源。M1 不记录 `USER_INPUT`、`SESSION_START/STOP`
  和原始触摸轨迹。
- 存储：`util/ScreenActivityFeed` 内存环形（120 秒 / 64 条），只在 phone task 期间记录；task-end、暂停、
  无障碍服务断开或重连立即清空。不落盘、不进备份/诊断日志；`sensitive` 应用只留 `TARGET_CHANGED`，
  密码框只留「已输入」，输入法与自身浮层事件丢弃。
- 回执：`/app/ui/*` 的 JSON 回执统一附带 `recentActivity`（该任务上次回执之后最近的至多 3 条）；
  任务未开始时为空数组。游标是每个任务的内存序号，不是毫秒时间戳。
- 工具：`GET /app/ui/history?since=<seq>&limit=20` 返回 `{ok, kind, recording, events, frames:[], truncated,
  since, nextSince, limit}`；`since` 用 `nextSince` 续读，`limit` 默认 20、最大 100；非法参数返回
  `state=NOT_SENT` 的错误码，不执行任何动作。`android_history` 工具描述与 `/app/help` 同步。
- 未做（按里程碑留给后续）：M2 原生让出提示与设置开关、M3 原始触摸、M4 缩略图帧与 `shots=1`、
  M5 高频会话/本地快速环；M1 也没有放宽任何执行期护栏（目标比对、凭据 TTL、确认策略不变）。
