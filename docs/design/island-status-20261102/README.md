# 任务状态上岛页重绘

状态：用户选定方案 B，已实现到 Android 源码。分支 `design/island-status-20261102`，日期 2026-11-02。**未装机，未在真机验证。**

**实验性提交**：允许合并（基于 build267），合入后须做统一视觉验收——`LayoutAuditInstrumentation` 中英文 / 日夜 / 短屏 / 1.3× 字体，以及真机核对平板两列、36.0 旧接口状态行与示意胶囊。验收前不在版本说明里宣称已完成视觉核对。

对应页面：设置 › 任务状态上岛（`ui/IslandFragment.java`）。截图里的“无法确认系统状态。（API 36）”是 `IslandSupport.STATE_UNKNOWN`：Android 16 早期版本（36.0 旧接口）没有 `canPostPromotedNotifications` 查询，DSHA 仍会按 colorized 请求提升。

| 文件 | 内容 |
|---|---|
| [preview.html](preview.html) | 单文件离线原型：现状复刻 + A/B/C 三方案；手机 360 / 平板竖 800 / 平板横 1280，深浅色、1.3× 字体、短屏；右栏有系统状态模拟、优先级预览、回到桌面后的胶囊与通知示意、方案选择 |
| [check-preview.cjs](check-preview.cjs) | 原型自检（Playwright）：选举逻辑与 `IslandText` 对照、各方案 × 系统状态渲染、关键交互、无外部请求 |

## 优先级预览

右栏按 `IslandText.rank / pick / pickForDisplay / shortText` 与 `AgentTaskNotifications.phase` 移植：等你确认 5 › 正在停止 4 › 执行工具 3 › 生成回复 2 › 其他 1；状态不明、已划掉为 0；同级先开始的优先；没有活跃任务时最近结束的保留 8 秒。可加减任务、改阶段、点停止、划掉、结束，实时看谁占岛、胶囊文字、普通通知。DSHA 原生页面在前台时任务通知全部撤下（源码 `foreground → cancel`）。

## 方案

- A 紧凑行：状态行（状态点 + 标题 + 副文案 + 行尾“系统设置”）、两个开关行、“上岛顺序”进弹层。只改布局。
- B 实时预览：顶部示意胶囊与通知（读 `AgentTaskState` 快照），5 级顺序内联并高亮当前占岛级别；≥700dp 两列。
- C 状态向导：按系统状态给主操作；“查不到”时可发一条 8 秒测试胶囊自己确认。需新增测试通知路径。

三案共同：计时器开关在总开关关闭时置灰（源码 `islandChronometer = islandEnabled && …`）；说明长文收进“支持的设备”弹层；偏好键、默认值、`settingsIntent` 回退不变。

## 实现（方案 B）

| 文件 | 改动 |
|---|---|
| `ui/IslandFragment.java` | 改为加载 `fragment_island.xml`；示意预览每秒读任务快照（仅页面可见时）；状态每次 `onResume` 重读；标题列点按切换开关；≥700dp 两列，>880dp 居中 |
| `util/IslandPage.java` + `IslandPageTest` | 纯逻辑：状态映射、五级计数、当前级别、预览代表（复用 `IslandText.pickForDisplay`）、示例胶囊（复用 `IslandText.shortText`） |
| `res/layout/fragment_island.xml`、`item_island_level.xml`、`values/island_page.xml`、`drawable/bg_island_*` | 布局沿用 `Config*` 行 / 卡片 / 按钮样式，颜色只引用 `shell_*` |
| `ui/MainActivity.java` | 顶栏为本页显示“任务状态上岛”（原来落到 `app_name`，即截图里的 DSH-A-Su） |
| `tools/i18n/messages-extra.json` + 8 语 `translations-extra` | 新增 `isl264a01–42`；复用已有的“系统设置”“正在停止”“DSHA 任务”“，”；`check-source-hygiene.py` 字节钉同步 |

不变：`task_island` / `task_island_chronometer` 键与默认值、岛位选举与胶囊文案（通知侧代码未改）、`settingsIntent` 回退；顺序不可配置。原页面的“系统状态”文案全部替换，原未登记的中文串不再使用。

## 待确认

- 系统在本应用前台时是否也隐藏胶囊：未核实；页面只做示意。
- 36.0（ColorOS 16 等）colorized 提升实际效果：只能真机核对。
- `LayoutAuditInstrumentation` 的中英文 / 日夜 / 短屏 / 1.3× 验收需装 debug 包在设备上跑，本轮未执行。
- 每秒刷新会调用 `AgentTaskNotifications.snapshot()`（内含一次 `HarnessController` 就绪身份读取），未在真机上测耗电与卡顿。
