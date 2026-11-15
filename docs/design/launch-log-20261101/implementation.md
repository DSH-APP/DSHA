# 启动日志区原生实现（实验性）

按同目录 `preview.html` 原型落到启动页 `fragment_launch.xml` 的日志区。状态：实验性渲染更新，仅宿主验证，合入后需统一视觉验收。

## 实现映射

| 原型 | 原生 |
|---|---|
| 阶段分组 | `util/StartupLogDigest`：读 `StartupTrace.Entry.kind`（开始 / 完成 / 切离），不解析中文文案；计时行按 `STARTUP_PREP_TIMING:` / `CAS_TIMING:` 解析，失败按普通行保留 |
| 问题上浮 | `StartupTrace.issue()` 写入的行带 `problem` 标记；`outcome=threw` 计时与明确错误开头的行也计入 |
| 整体结果 | `LaunchFragment.logOutcome()` 只取 controller 真实状态（启动中 / 已就绪 / 停止 / 失败），不从日志推测 |
| 概览 / 原始 / 跟随浮标 / 展开 | `ui/LaunchLogPanel`；原始仍是 `launch_log` TextView（Spannable 时间槽 + 着色，可选可复制） |
| 复制 / 下载 | 始终是完整原文（`trace.log` + 本机地址说明），与筛选无关；下载入口不变 |

删除了 `launch_follow_logs`；`launch_latest_button` 改为框内浮标，仅暂停跟随时显示并带新日志数。`StartupTrace.Snapshot.log` 与诊断归档字节不变。

## 已验证（宿主）

- 定向单测 71 项通过：`StartupLogDigestTest`、`LaunchLogPanelTest`、`StartupTraceTest`、`StartupHistoryStoreTest`、`StartupDiagnosticsTest`、`StartupRecoveryLifecycleTest` 及启动页既有 Launch* / MainEntry 测试。
- `check-source-hygiene`、`check-ui-i18n`、`test-terminal-home-i18n`、`test-dsha-ui-regressions`、格式检查通过；新增 32 条十语文案。

## 未验证

- 未跑 Lint、全量单测、`LayoutAuditInstrumentation` / `LaunchLogAudit`，未上真机。
- androidTest 源集在 origin/main 上已有与本改动无关的编译错误（`RecoveryInstrumentation.refreshPowerMode`），本分支未处理。

## 统一视觉验收清单

- 日夜、中英文（另抽查阿拉伯语 RTL）、短屏、1.3 倍字体、手机竖横屏、平板 ≥760dp 双栏。
- 概览：运行中转圈与耗时每秒更新；插件跳过 / 鉴权前退出时阶段变色并自动展开；就绪里程碑不显示耗时。
- 原始：时间槽对齐、折行悬挂缩进；「仅问题」「隐藏计时」「换行」切换；「在原始日志中查看」跳转与高亮。
- 跟随：上翻后出现「n 条新日志」浮标，点按回到末尾；展开 / 收起日志区域；长日志不移动顶部操作区。
- TalkBack：分段、阶段行（展开 / 收起状态）、图标钮名称。
