# 首次引导重构方案 · 应用内交互预览

日期：2026-10-31 · 基于 build216（HEAD `fece7a8`）源码 · **仅设计预览，未改 Android 代码，未在真机验证**

| 文件 | 内容 |
|---|---|
| [preview.html](preview.html) | 应用内页面的交互原型。单文件、离线、无外部请求，可以直接发给别人用浏览器打开 |
| [external-authorizations.md](external-authorizations.md) | 应用外授权步骤（系统设置、管理器、Root 弹窗）。7 类授权 + ADB，含源码入口、返回判定、撤销方式和未验证点 |
| `screens/*.png` | 关键屏截图（手机 / 平板 / 深色 1.3× 字体），方便没法打开 HTML 的人看 |

## 1. 现状（源码）

| 环节 | 现在 | 问题 |
|---|---|---|
| 介绍 | `WelcomeActivity`：3 页 ViewPager2（`welcome_page1–3`） | 只介绍，不做事；第 3 页的权限说明只是文字 |
| 准备 | `OnboardingPrepareActivity`：“4 / 4”，只读检查 4 项 | 与介绍页割裂；点「安装内置环境」才开始部署 |
| 部署 | `ExtractActivity`“运行环境维护”：整屏等待 | 首次部署时用户只能干等 |
| 覆盖更新 | 同一个 `ExtractActivity` 自动启动 `UPDATE` 任务（`EnvironmentIdentity.shouldAutoStart`） | 每次升级后都被整屏挡住 |
| 授权 | 只在 设置 → 设备能力授权（`DeviceGrantsFragment`，914 行长表单） | 引导里不管授权；通知没有入口（只在 `UpdateActivity` 申请）；电池优化只跳转、不检测 |

## 2. 新流程

```
全新安装：  欢迎（含部署前检查）─开始部署→ 用途 → 授权 → 完成 ─→ 主界面（自动启动 Web）
                    └──────── 部署任务在后台跑，进度显示在「胶囊」里 ────────┘
覆盖更新：  直接进受限主界面，顶栏胶囊显示“更新中”；完成后变绿、约 5 秒后收起
```

1. **欢迎**：原 3 页介绍合成 1 页，加上原准备页的 4 项检查。空间不足时在这一页拦住（对应 `COLD_INSTALL_SPACE_REQUIRED`）。「开始部署」= 现在的 `setWelcomed(true)` + 启动应用级维护任务。
2. **用途**（新增）：多选 5 项，只用来给下一页标推荐和排序，不申请任何权限。
3. **授权**：7 类授权分成 4 组卡片（基础 / 文件 / 屏幕操作 / 设备命令通道），全部默认关闭、可以跳过。卡片只负责“去授权”和回到 DSHA 后的复查（`onResume`）。
4. **完成**：汇总部署和授权状态。成功时「进入主界面」，沿用 `FirstRunWebStart` 自动启动 Web；失败或检查未通过时沿用 `ExtractActivity` 的三个出口（维护记录 / 应急 DSH / 受限主界面），不自动跳转。

### 部署胶囊（“首次更新运行时”的新位置）

- **手机**：引导页和主界面顶栏正中。显示状态、当前阶段和“第几步 / 共几步”，窄屏只显示状态。
- **平板 / 横屏（≥ 600dp）**：引导左栏下方是一张进度卡片；主界面放在顶栏右侧。
- **点开**：手机是底部面板，平板是居中对话框。内容是阶段列表、当前阶段、失败原因，以及「重试 / 查看本次维护记录 / 启动应急 DSH」。
- **按阶段分段显示，不显示百分比**：现有任务只上报阶段文案，没有可信的百分比。阶段名取自 `ColdBundleExtractor`、`EnvironmentMaintenance`、`ManagedRuntimeAssets`；“隔离试运行”一项是示意命名。
- 颜色：蓝 = 进行中，绿 = 完成，红 = 失败，黄 = 结束但环境检查未通过。

### 授权卡片 → 7 类授权

| 分组 | 卡片 | 返回后判定（源码 / 新增） |
|---|---|---|
| 基础 | 通知 | `POST_NOTIFICATIONS` + `areNotificationsEnabled`（**新增入口**）。拒绝两次后改为“去系统通知设置” |
| 基础 | 电池优化 | **新增** `isIgnoringBatteryOptimizations` 只读检测；现在只跳转设置、无法判定 |
| 文件 | 存储读写 | `Environment.isExternalStorageManager()`，文案用 `refreshAllFilesStatus` 原文 |
| 屏幕操作 | 无障碍 | `enabledState` + `connected()`，包括“已开启但未连接”状态，文案用 `refreshA11yStatus` 原文 |
| 屏幕操作 | 悬浮窗 | `Settings.canDrawOverlays` |
| 设备命令通道 | Shizuku / Stellar · Root · ADB（三选一，用分页切换） | `ShizukuShell.userStatus`、`RootShell.status` 原文；Root 在引导里固定为“严格（默认）”档；ADB 要等部署完成才能配对 |

“屏幕操作”组会显示手机任务需要的三项（无障碍 · 悬浮窗 · 通知），对应 `AssistantService` 的实际前置检查（停止通知 + 悬浮窗）。

### 原型里的“离开 DSHA”

点「去开启…」不会模拟系统页，而是弹出黄色虚线框的**原型标注**：写明要去哪里、对应文档第几节，并让评审选择“回到 DSHA 时的结果”（例如无障碍有“已开启并连接 / 已开启但未连接 / 未开启”三种）。选完后卡片按源码文案更新。

## 3. 自适应

- 应用界面用容器查询，按“设备宽度”切换布局，不按浏览器窗口：
  - 宽度 < 600dp：单栏，底部按钮纵向排列。
  - 宽度 ≥ 600dp：两栏（左栏是步骤和胶囊卡片），底部按钮靠右。
  - 内容区宽度 ≥ 700dp：授权卡片排成两列，单独一张的卡片占满整行。
  - 高度 ≤ 480dp（手机横屏）：压缩页头、页脚和按钮高度。
- 主界面在 ≥ 600dp 时，底部导航变成左侧导航栏，与 UI v5 的平板左栏一致。
- 预览控制里可以切换：手机竖屏 / 横屏、平板竖屏 / 横屏、跟随窗口；浅色 / 深色；1.0× / 1.3× 字体；减少动效。

## 4. 落地时要改的地方（建议）

| 改动 | 涉及 |
|---|---|
| 新的引导 Activity（4 步，单 Activity 多 Fragment），替换 `WelcomeActivity` 与 `OnboardingPrepareActivity` | `ui/`；启动契约不变：`welcomed == false` 仍然只进引导 |
| 部署从“开始部署”起在后台进行，引导页订阅 `BackupTask.snapshot()`；部署期间保持屏幕常亮 | 复用 `BackupTask` / `DataProtectionService` 前台通知；`FLAG_KEEP_SCREEN_ON` 从 `ExtractActivity` 移过来 |
| 胶囊组件：手机放顶栏，平板放左栏卡片 | 新控件 + `shell_*` 颜色 token |
| 覆盖更新改为受限主界面 + 胶囊，不再整屏挡住 | `ExtractActivity` 的自动更新入口；受限入口仍然**不能伪造就绪标记**，Web / 终端 / 插件继续走环境门禁 |
| 授权卡片组件，引导和设备能力授权页共用 | `DeviceGrantsFragment` 可以逐步迁移 |
| 新增：通知申请入口、电池优化只读检测 | 只在用户点击后申请；不加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 文案统一：无障碍步骤里的“DSHA 配对助手”改成系统实际显示的「DSH-A-Su 屏幕控制」 | `AccessibilitySetupActivity` |
| 首次安装失败时，重试按钮显示“重试部署”（不是“保护数据并重建”） | `ExtractActivity` 的 retry 文案分支 |

## 5. 未验证 / 待讨论

- Stellar 能否被 `ShizukuManagerCompat` 识别（它没有声明 `API_V23`），见文档 §2，需要真机确认。
- 电池优化“不受限制”状态、通知申请入口都是新增能力，原型里的文案是新写的。
- 部署期间允许授权跳转到系统设置：DSHA 进入后台时维护任务由前台服务保护，但不同 ROM 会不会结束进程需要真机验证。存储授权可能要重启 Web / 终端才生效（文档 §1）。
- 原型里的数值（空间 3.1 GB / 23.4 GB、Android 14、端口 43827、su 路径）都是示意值。
- 只做了浏览器渲染检查（Chromium 无头：控制台无报错，6 种尺寸、深色、1.3× 字体下无横向溢出），没有做 TalkBack 和真机验收。
