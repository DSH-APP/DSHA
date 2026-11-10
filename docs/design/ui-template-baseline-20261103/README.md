# DSHA UI 模板基准（build280 绘制，build296 核对）

基线：origin/main `466e16ae`（build279）绘制，`f55e3b3c`（build280）复核，两者界面相同。2026-11-03 扩展了手机横屏档、§8 适配度审计、§9 弹层自适应；随后分支快进到 `0766364c`（build296），弹层与外壳源码核对见下方差距说明。本目录是离线 HTML 预览，不是 APK 截图。没有运行 Gradle，也没有安装或操作手机。

> **与当前 main 的差距**：build281–296 没有改外壳、导航和 token，所以基准档位、断点与 kit 仍然适用。下列页面的原生实现在此之后有改动，预览仍是 build280 的样子，后续需要按新代码重画：
> - `system-vscreen`：虚拟识屏页在 build294–296 按状态重做，加入识屏模式。
> - `system-webpreview`：新增 WebCrashPanel。
> - `settings-grants`：设备能力授权页加入识屏模式入口。
> - `settings-island`：「支持的设备」避开悬浮胶囊。
> - `maintenance-diagnostics`：新增网络健康检测 sheet。
> - `onboarding-*`：部署进度改为 StageSegmentBar。
>
> 另有 `ConfigFragment` 和 `PluginFragment` 的小改动，以及 InfoHint 改为 InfoSurface 弹层。InfoHint 只在弹出时可见，不影响导出图。
>
> §9 依据的 `CardSheet`、`CardPage`、`shell_ui.xml`、`shell_page.xml` 和 `activity_main.xml` 在 build281–296 之间没有改动。`DshaDialogBuilder` 只新增了 `keepWindowSize()`，让 InfoSurface 自己决定窗口尺寸；默认的 560dp 居中宽度不变。

## 1. 基准档位

尺寸单位为 dp，指整窗尺寸，含系统栏。导出用 `deviceScaleFactor=2`，所以 PNG 为 @2x。

| 档位 id | 尺寸 | 用途 | 命中的原生判据 |
|---|---|---|---|
| `phone` | 360×780 | **手机竖屏主基准** | sw<600：悬浮胶囊导航、终端附加键 7+7；w<600 |
| `phone-landscape` | 780×360 | **手机横屏主基准**（2026-11-03 扩展） | sw<600 仍是手机外壳（胶囊）；w≥600 命中 `values-w600dp`；screenH=316≤480 命中短屏 |
| `tablet-portrait` | 800×1280 | **平板竖屏主基准** | sw≥600：左栏、附加键 8+8；600≤w<840：设置单栏 |
| `tablet-landscape` | 1280×800 | **平板横屏主基准** | w≥840：设置双栏、终端侧栏内嵌、`layout-w840dp`；h<1000：终端横屏紧凑 |
| `phone-narrow` | 320×640 | 边界：窄屏 | w<340、w<370 |
| `phone-large` | 412×892 | 边界：大屏手机 | 与 `phone` 同档 |
| `y700-portrait` | 640×1024 | 边界：Lenovo TB321FU（2560×1600 / 400dpi） | sw≥600 但 w<720/760：并排全部退回单栏 |
| `y700-landscape` | 1024×640 | 边界：同上横屏 | w≥840，高度只有 596 |
| `phone-landscape-small` | 640×320 | 边界：小手机横屏 | w≥600、screenH=276 |

系统栏按统一示意画：状态栏 24dp、手势条 20dp。所以 `screenHeightDp = 高 − 44`。真机系统栏高度随 OEM 不同，任务栏模式也不同，需在真机上另行核对。

## 2. 断点（只能用这些）

页面只通过 `env` 判据切换布局，阈值与代码一一对应：

| env 字段 | 条件 | 代码来源 |
|---|---|---|
| `tablet` | smallestScreenWidthDp ≥ 600 | `layout-sw600dp`、`values-sw600dp`、`TerminalExtraKeys.profileFor`、`BootScreen`、`SkillsFragment` |
| `w600` | screenWidthDp ≥ 600 | `values-w600dp`、`LaunchFragment`、`MainActivity`、`CardSheet`、`OnboardingKit`、`FileTransfersActivity`、`BackgroundTasksActivity`、`WorkspaceFragment`、`LanguagePicker` |
| `w840` | screenWidthDp ≥ 840 | `SettingsPanes.TWO_PANE_DP`、`layout-w840dp`、`TerminalWorkspacePolicy`、`ExtractActivity`、`FileTransfersActivity` |
| 组件自身宽度 | 680 / 700 / 720 / 760 | `InstallFragment` / `island_two_column_dp` / `config_two_column_dp` 与 `ExtractActivity` / `LaunchFragment` |
| `short480` | screenHeightDp ≤ 480 | `OnboardingKit`、`MaintenanceFooterPolicy` |
| `narrow370` / `narrow340` | w < 370 / w < 340 | `DeployCapsule` / `MaintenanceFooterPolicy` |

主外壳内容宽 `mainContentW`：平板要减去 81dp（80dp 左栏加 1dp 竖线）。独立 Activity 的内容宽就是 `screenW`。

## 3. 外壳与 token

- 颜色取标准调色板 `pal_standard_*` 与 `shell_ui.xml`，深浅各一套，见 [kit/tokens.css](kit/tokens.css)。
- 尺寸取 `shell_ui.xml`、`shell_page.xml`、`shell_launch.xml`、`dimens.xml`，以及 w600 / sw600 覆盖。
- 主外壳为 `appbar`（60dp）加左栏（平板）或悬浮胶囊（手机）；手机页底留 90dp，平板留 30dp。
- 内页使用返回行（52dp）加 CardPage 骨架：页边距 20dp、标题 26sp、卡片圆角 18dp、行最小高 56dp。
- 主按钮 52dp，次按钮 46dp，触控下限 44dp；控件圆角 13dp。

## 4. 文件

| 路径 | 内容 |
|---|---|
| [index.html](index.html) | 设计台：实时渲染，可切主题、档位、缩放；`?page=&profile=&theme=&state=` 为单屏 |
| [gallery.html](gallery.html) | 导出图浏览：按分组列出三个主档位 PNG |
| [kit/](kit/) | token、外壳、通用组件、`icons.js`（由 `tools/icons-from-drawables.py` 从 `res/drawable/ic_*.xml` 生成，含 layer-list 别名） |
| [pages/](pages/) | 按组拆分的页面模块；[pages/launch.js](pages/launch.js) 是参考写法；源码没有直接给出的取值在注释里标「推断」 |
| [tools/render.cjs](tools/render.cjs) | 导出 PNG 并自检：脚本错误、外部请求、缺图标、横向溢出、触控 <44dp、右缘截断 |
| [tools/build-manifest.cjs](tools/build-manifest.cjs) | 核对缺图和多余文件，写 `screens/manifest.json` / `manifest.js` |
| [tools/audit-fit.cjs](tools/audit-fit.cjs) | 适配度审计（§8 的数据来源）：固定区占比、滚动屏数、主按钮是否在首屏、截断、按钮折行、宽屏行长与卡片宽 |
| `screens/` | 导出图：`<page>/<state>/<profile>-<theme>.png`，只含四个主档位 |
| `screens-proposal/` | 打磨提案层导出图（`?variant=proposal`），浅色：全部页面的手机横屏与平板横屏，另加弹层相关页（`system-popups`、`terminal`、`terminal-workbench`）的四个主档位；不进 manifest |

```bash
cd docs/design/ui-template-baseline-20261103
export PLAYWRIGHT_HOME=/usr/local/lib/node_modules
node tools/render.cjs --profiles phone            # 逐档导出更稳（tablet-portrait / tablet-landscape 同理）
node tools/render.cjs --profiles edge --check-only  # 边界档只自检，不出图
node tools/build-manifest.cjs
node tools/audit-fit.cjs --out /tmp/fit.json                       # 原样基准适配度
node tools/audit-fit.cjs --variant proposal --out /tmp/fit-p.json  # 提案层对比
node tools/render.cjs --variant proposal --profiles phone-landscape,tablet-landscape --themes light
```

本机 proroot 环境下，Chromium 多进程模式导航会卡死，所以脚本默认加 `--single-process --no-zygote --disable-gpu`，并且全程只用一个 context。在其他机器上用 `PW_ARGS=''` 可恢复默认模式。

## 5. 范围与数量

- 43 个页面、96 个状态，覆盖 4 个主标签、8 个设置页（含 7 个子页）、全部独立 Activity，以及弹层模板页 `system-popups`（2026-11-03 新增）。
- 导出 768 张图（96 状态 × 4 主档位 × 深浅），全部通过自检；手机横屏与弹层模板为 2026-11-03 扩展。
- 5 个边界档的截屏自检全部通过（含小手机横屏）。
- 提案层浅色图同样通过自检，边界档也已核对。

## 6. 已知偏差与原生 UI 问题

预览保持原生尺寸。下列控件加了 `data-dense`，豁免触控检查，并在页面源码旁注明来源：

- 低于 44dp 触控下限的原生控件：
  - 启动日志图标钮 40dp（`shell_launch_log_ibtn`）
  - 引导部署胶囊 34dp、引导文字按钮 40dp、授权卡行内主操作 34dp、ADB 配对「查看连接详情」行 40dp
  - 运行配置 chip / 分段 36dp
  - 启动恢复「全部 N 条」36dp
  - 下载记录分段 40dp 与「了解下载记录」40dp
  - 数据页筛选 chip 40dp
  - 虚拟屏旧式按钮 40dp（12sp、无间距）
  - 终端窄侧栏会话行点击区约 32dp
- 插件卡操作行：内容宽 < 304dp（如 320dp 窄屏）时，原生「更新」按钮会被挤出卡片右缘；预览改为让胶囊收缩省略。
- 终端里的 CardSheet 仍按主界面胶囊抬高 74dp，底部会露出一条终端。
- 断点空档：在 TB321FU 竖屏（640dp）上，720 / 760 的并排全部不触发，平板竖屏实际是单栏；800dp 主基准上则会触发。
- 预览字体使用文泉驿或系统 CJK 字体，不是设备的 Roboto / 思源，字宽有差异。系统栏高度为统一示意。

## 7. 页面清单
**主标签页**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `launch` | 启动 | 未运行 / 已就绪 | fragment_launch.xml · LaunchFragment · LaunchLogPanel |
| `plugins` | 插件 | 插件管理（已安装） / 插件市场 / 安装进行中 | fragment_plugins.xml · plugin_list_header.xml · item_plugin.xml · plugin_detail_pane.xml · plugin_list_footer.xml · PluginFragment |
| `plugins-install` | 插件安装（链接唤起） | 正在下载解析 / 安装完成 / 环境未初始化 | activity_plugin_install.xml · PluginInstallActivity · PluginNavigation |
| `skills` | 技能 | 已安装（刚导入） / 导入进行中 / 空状态 | SkillsFragment · CardPage · InfoHint · WorkspaceFragment 入口 · SettingsFragment 双栏 |

**设置**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `settings` | 设置 | 默认 | fragment_settings.xml · SettingsFragment · SettingsPanes |
| `settings-config` | 运行配置 | 已保存 / 有未保存改动 | fragment_config.xml · ConfigFragment · config_page.xml |
| `settings-workspace` | 数据与备份 | 数据已保护 / 从未备份 | fragment_workspace.xml · WorkspaceFragment · item_data_entry.xml |
| `settings-install` | 安装与环境 | 未检查 / 检查失败 | fragment_install.xml · InstallFragment · AdaptiveColumns |
| `settings-grants` | 设备能力授权 | 默认（未授权） | fragment_device_grants.xml · DeviceGrantsFragment · FullPermissionControls · GrantSummary |
| `settings-overlay` | 悬浮条 | 已授权 / 缺悬浮窗权限 | OverlayFragment · CardPage · OverlayController |
| `settings-island` | 任务状态上岛 | 任务在岛上 / 无任务 | fragment_island.xml · IslandFragment · item_island_level.xml · island_page.xml |
| `settings-about` | 关于 DSHA | 默认 | AboutFragment · CardPage |

**终端**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `terminal` | 全屏终端 · 极简模式（默认） | 会话运行中 / 会话侧栏 / 抽屉展开 / 快捷按键弹层 | nav_terminal → TerminalActivity · PtyTerminalFragment · TerminalWorkspacePolicy · TerminalExtraKeys · TerminalFloatingMenu · ExtraKeysDialog |
| `terminal-workbench` | 全屏终端 · 工作台模式 | 会话运行中（宽屏侧栏常驻） / 终端操作弹层 | TerminalActivity（workbench=true）· TerminalWorkspacePolicy · TerminalActionsDialog · CardSheet |

**引导与授权**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `onboarding-welcome` | 引导 1 · 欢迎 | 首次 · 未部署 / 可用空间不足 / 已开始部署（返回第 1 步） | OnboardingActivity.buildWelcome · features / checkGrid / checkSummary / dataRow · DeployCapsule · DeployCard |
| `onboarding-goals` | 引导 2 · 用途 | 仅基础用途 / 已选文件与屏幕 | OnboardingActivity.buildGoals / goal / renderGoalsFooter · PermissionGoals · OnboardingRoles.checkbox |
| `onboarding-perms` | 引导 3 · 授权 | 推荐 0 / 3（用途：屏幕） / 已开 1 项 · 展开无障碍与更多 | OnboardingActivity.buildPerms · PermissionPanel · PermissionCard · PermissionCards · PermissionChannelCard |
| `onboarding-finish` | 引导 4 · 完成 | 部署进行中 / 准备就绪 / 部署未完成 | OnboardingActivity.buildFinish / runtimeCard / grantsCard / failureText · OnboardingKit.segmentBar / stageList · DeployCard.pillText |
| `onboarding-boot` | 开机加载页 | 检查运行环境 / 8 秒后出现诊断 / 直启 Web（可回主界面） | BootScreen · BootLogoView · BootSpinnerView · MainActivity（正在检查运行环境…）· DirectWebLaunch |
| `onboarding-a11y` | 屏幕操作设置 | 尚未开启 / 已连接 | AccessibilitySetupActivity · CardPage（kv / text / button / entry）· UiNavigation.addHeader |
| `onboarding-adb` | ADB 无线配对 | 等待配对码 / 配对进行中 | activity_adb_pair.xml · AdbPairActivity（buildUi / 状态观察 / 查看连接详情） |

**维护与恢复**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `maintenance` | 运行环境维护 | 进行中 / 失败 | activity_extract.xml · ExtractActivity · ExtractFooter · MaintenanceFooterPolicy · AdaptiveColumns |
| `maintenance-startup` | 启动恢复 | 插件加载失败 / 正常运行 | StartupRecoveryActivity · StartupRecoveryLayout · StartupRecoveryState |
| `maintenance-emergency` | 应急 DSH | 未启动 / 已就绪 | RecoveryActivity · CardPage · RecoveryStatusText（RecoveryWebActivity 为 WebView 外壳，未画） |
| `maintenance-update` | 版本更新 | 空闲 / 下载中 / 可安装 | activity_update.xml · layout-w840dp/activity_update.xml · update_hero/candidate/manage · UpdateUi · UpdateProgressButton |
| `maintenance-diagnostics` | 自检与诊断 | 尚未检查 / 需要处理 / 展开正常项 | activity_diagnostics.xml · layout-sw600dp/activity_diagnostics.xml · diagnostic_hero/feedback/tools · DiagnosticResultList |

**任务与系统**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `system-bgtasks` | 后台任务 | 有任务 / 空闲 | BackgroundTasksActivity · TransferUi · BackgroundTaskModel |
| `system-agent-tasks` | Agent 任务 | 有任务 / 无记录 | AgentTasksActivity · CardPage · AgentTaskNotifications.phase |
| `system-transfers` | 下载记录 | 记录列表 / 记录详情 / 无记录 | FileTransfersActivity · TransferDetailView · TransferStepsView · TransferListModel |
| `system-community` | 社区插件生态 | 连接中 / 连接失败 | CommunityActivity（WebView，无本机桥） |
| `system-theme` | 主题与外观 | 经典蓝 · 跟随系统 / 樱花配色 | ThemeActivity · CardPage · UiThemePalette · theme_palettes.xml |
| `system-vscreen` | 虚拟屏 | 未创建 / 运行中 · 应用面板 | vscreen/VirtualScreenActivity · VirtualScreenPreviewView |
| `system-assistant` | 悬浮助手 | 选择会话 / 已绑定会话 / 悬浮窗权限 | assistant/AssistantActivity · AssistantWindow.label/detail · AssistantService.taskGuidance |
| `system-webpreview` | Web 内嵌预览 | 加载中 / 连接失败 | WebPreviewActivity · activity_web_preview.xml · ui_strings.xml |
| `system-popups` | 弹层模板 | 选择列表 / 说明文字 / 输入对话框 / 副本操作 | CardSheet（create / show / choices）· DshaDialogBuilder.sizeOnce · AutomaticBackupActivity · DeviceGrantsFragment |

**数据与存储**

| id | 页面 | 状态 | 原生来源 |
|---|---|---|---|
| `data` | 应用数据与救援 | 救援工具（默认） / 从备份恢复 · 范围与选项 | NativeDataActivity（actionsPage / scopeGroup / optionsCard / renderStepper / optionsStep）· CardPage |
| `data-workspace-files` | 作品文件导出 | 工作目录列表 / 正在导出 | WorkspaceFilesActivity（onCreate / render / FileRows） |
| `data-storage` | 存储与文件 | 已统计 / 正在统计 | StorageActivity（renderUsage）· StorageMaintenance.inspect · StorageUsage · StorageCategoryLabels |
| `data-storage-help` | 存储、备份与重建说明 | 默认（首组展开） / 展开「先导出」 | StorageHelpActivity（section / paragraph / setExpanded）· CardPage |
| `data-retained` | 保留副本与旧环境 | 清单（已勾选 2 条） / 检查完成 | RetainedDataActivity（renderChips / label / render / inspect）· CardPage · 参考 docs/design/retained-data-20261102 |
| `data-auto-backup` | 自动备份 | 每天 · 有 3 份副本 / 按间隔 · 暂无副本 | AutomaticBackupActivity（render / refresh）· CardPage · CardSheet.choices |
| `data-cold-setup` | 首次安装测速 | 未测速 / 测速完成 | ColdSetupSpeedActivity · CardPage · core/ColdSetupTiming.report |
| `data-origin-restore` | 恢复旧端口草稿 | 读取旧端口候选 / 恢复结果 | BrowserOriginRestoreActivity（onCreate 状态文字）· BrowserOriginRestoreFactory |

## 8. 适配度审计与打磨清单（2026-11-03 扩展）

数据来自 [tools/audit-fit.cjs](tools/audit-fit.cjs)，量的是本目录的 HTML 预览，不是真机。一共 368 屏：92 个状态 × 4 个主档位，浅色，不含后来加的弹层模板页（见 §9）。单位为 dp。

### 8.1 总览

| 档位 | 固定区占比（中位） | 滚动屏数（中位 / 最大） | 主按钮不在首屏 | 卡片宽 >760dp 的屏 |
|---|---|---|---|---|
| 手机竖屏 360×780 | 18% | 1.27 / 3.15 | 3 / 37 | 0 |
| **手机横屏 780×360** | **38%** | **3.61 / 18.45** | **13 / 37** | 0 |
| 平板竖屏 800×1280 | 5% | 1.00 / 1.17 | 0 / 37 | 0 |
| 平板横屏 1280×800 | 8% | 1.00 / 2.29 | 1 / 38 | **21** |
| 手机横屏 · 提案层 | 15% | 2.96 / 5.48 | 8 / 37 | 0 |
| 平板横屏 · 提案层 | 8% | 1.00 / 1.88 | 1 / 38 | 4 |

整屏自绘的页面不计入固定区占比，它们的固定区为 100%：终端、开机加载、WebView 外壳、作品文件导出。

结论：
- 平板竖屏基本不用动。
- 手机竖屏只有个别长页。
- 压缩工作集中在手机横屏（竖向空间）和平板横屏（单栏拉伸）。

### 8.2 共享外壳提案（已画进 `?variant=proposal`）

只改 kit 共享层，落地时改共享资源或 `CardPage`，不需要逐页补丁。

| 编号 | 改什么 | 原生落点 | 适用 | 效果（手机横屏） |
|---|---|---|---|---|
| P1 | 悬浮胶囊换成 56dp 紧凑侧栏 | `activity_main.xml` 增加 `layout-land`（或按 `screenHeightDp≤480` 运行时切换），复用 sw600 左栏，去掉 LOCAL FIRST | 手机 + 短屏 | 胶囊加留白共 74dp 还给内容 |
| P2 | 顶栏 60→48dp，隐藏上下文副标题；内页返回行 52→44dp | `shell_header_min_height`、`UiNavigation.addHeader` 加 `values-h480dp` 反向覆盖（或运行时判断） | 短屏 | 再省 20dp |
| P3 | 页标题 26→20sp，说明间距 8/20→4/12 | `CardPage` 在短屏下套用已有的 `compact` 数值 | 短屏 | 标题区约省 30dp |
| P4 | 独立 Activity 底部留白 90→24dp | `CardPage` 不再读 `shell_page_bottom`（它是为主界面胶囊预留的），改用独立 dimen | 所有手机独立页 | 每页末尾省 66dp |
| P5 | 页脚按钮从竖排整宽改为横排一行，主按钮在右 | `CardPage.footer` 在 `short480 \|\| w840` 时改 `HORIZONTAL`，按钮权重 1 | 短屏、宽屏 | 「保留副本」页固定区 250→约 110dp |
| P6 | `CardPage` 内容和页脚限宽 760dp、居中 | `CardPage.content` 外包一层 `max-width` 容器 | w≥840 | 21 屏宽卡降到 4 屏 |
| P7 | 启动页短屏：隐藏插图，标题 34→24sp，去掉副标题 | `LaunchFragment.applySchemeALayout` 增加短屏分支 | 手机横屏 | 「启动 DSHA」从首屏外回到首屏 |

提案层对照图在 `screens-proposal/<page>/<state>/{phone-landscape,tablet-landscape}-light.png`。

### 8.3 仍需逐页处理（提案层也没解决）

**手机横屏**（780×360，可用高度 316）：
- **设备能力授权**：四格摘要头（全局 / Root / Shizuku / 屏幕）固定在顶部，加上顶栏共 193dp，正文只剩约 120dp，内容约 6.4 屏。建议短屏时让摘要头随内容滚动，或折成一行图标加数字。
- **引导 1–4**：左栏宽 480dp，右侧内容和底部操作栏挤在 300dp 里。底栏 119dp 固定，「欢迎」页约 4 屏。建议短屏时左栏收窄到 240dp，只保留步骤号，并把「运行环境」卡移到右侧内容区；底栏压到 56dp。
- **引导 4 · 部署未完成**：底栏 167dp，约 5.5 屏，是最差的一屏。失败说明和「复制给支持」应进入内容区，底栏只留主按钮。
- **悬浮助手 · 已绑定**：底部 7 个操作按钮横向滚动，最后一个看不到。「打开完整 DSH 会话 / 模型设置」最长，应改成「完整会话」，并把「暂停 / 继续」合成一个切换钮。
- **终端 · 快捷按键弹层**：原样正文只露出 114dp，约为内容的 19%，「布局」单选被切掉一半。弹层几何问题统一见 §9，提案层已经按 §9 改画。
- **数据与备份**：「立即备份」在第 304dp 处，刚好落在首屏外。建议把它放到状态卡同一行。
- **安装与环境 · 检查失败**：「检查全部」落在首屏外，失败说明在它上面占了 3 行。
- **应急 DSH**：「启动应急 DSH」在 256dp，位于说明卡之后。应移到页首状态行。
- **从备份恢复**：步骤条加范围列表之后，「开始预检」在第 692dp（平板横屏 731dp 也在首屏外）。建议给步骤向导加固定的底部主按钮。
- **维护页 · 失败**：底栏 115dp，约 4 屏。

**平板横屏**（1280×800）：
- 插件页（1160dp）、悬浮条和关于（839dp）是主外壳单栏，P6 不覆盖，需要各自限宽或改双栏。
- 「应用数据与救援」「保留副本」「存储与文件」的说明段落一行约 95 字，P6 限宽后降到约 55 字。
- 下载记录：详情栏里「复制路径」「重试」两个按钮的文字折成两行，按钮需要最小宽度。

**手机竖屏**（360×780）：
- 首屏外的主按钮：设备能力授权「设置屏幕操作」在 776dp，从备份恢复「开始预检」在 750dp，存储说明「去导出重要数据」在 894dp。
- 长页：「保留副本」3.15 屏、设备能力授权 2.57 屏。

**平板竖屏**：没有需要压缩的项。800dp 主基准以外，TB321FU 竖屏（640dp）的单栏退化见 §6。

### 8.4 需要压缩的文案

手机竖屏截断或折行（预览字体比 Roboto / 思源略宽，真机可能少 1–2 字的余量）：

| 页面 | 文案 | 现象 | 建议 |
|---|---|---|---|
| 运行配置 | 「2 项未保存 · 端口与运行时需重启 Web」 | 保存条单行截断 | 「2 项未保存 · 需重启 Web」 |
| 插件 · 安装中 | 「正在下载插件及依赖：@example/dsh-plugin-weather 1.…」 | 截断，看不到版本 | 包名单独一行，状态写「下载中」 |
| 插件 · 市场 | 「下载源：自动选择（推荐）」 | 截断 | 「下载源：自动」 |
| 插件 · 市场 | 链接输入框的占位 URL | 截断 | 改为「粘贴 GitHub 或 npm 链接」 |
| 插件 · 详情 | 安装路径 `/root/.dsh/plugins/node_modules/@…` | 两行截断（平板横屏同样） | 改成可复制的单行中间省略 |
| 启动 | 指标格「Ubuntu 24.04」 | 三格平分 320dp 时截断 | 「Ubuntu 24」或改为两格 |
| 应用数据与救援 | 两个救援选项的说明（各约 60 字） | 多行截断 | 压到 30 字以内，细节进 ⓘ |
| 版本更新 | 更新说明摘要 | 多行截断 | 已有「查看完整记录」，保持截断即可 |
| 应急 DSH | 「repair-runtime · managed-runtime 待确认」 | 所有档位都折行 | 只显示工具名，状态做成 tag |

宽屏说明段过长（单行超过 46 字，不限宽时约 95 字）：「应用数据与救援」「保留副本与旧环境」「存储与文件」的页首说明。这三段也是手机横屏首屏最占高度的文字，建议各压到一句话（≤40 字），其余内容进 ⓘ 或帮助页。

## 9. 弹层自适应（CardSheet / DshaDialogBuilder）

### 9.1 现状问题

原生弹层只按宽度切换形态，不看高度。`CardSheet.wide()` 只判断 `screenWidthDp ≥ 600`，`DshaDialogBuilder.sizeOnce` 只限制宽度 ≤560。于是：

- **手机横屏**（780×316）：被当成宽屏，拿到 540 宽的居中卡片，宽高比约 2.0。页脚按钮仍是竖排整宽（46 + 52 + 间距 ≈ 120dp），正文只剩 114–176dp。最差的「快捷按键」正文只能看到 19%。
- **平板**：所有 CardSheet 一律 540 宽，不分内容长短。三项选择列表是 540×324，一段说明是 540×262（宽高比 2.06）。短内容被拉得很扁，长表单（快捷按键）又没有多给宽度，显得竖长。
- **手机竖屏**：贴底弹层固定抬高 74dp 让出胶囊，终端这类没有胶囊的页面，底部会露出一条背景。
- **对话框**：AlertDialog 用 512 宽（平板与手机横屏都是），里面只有一个输入框和两个文字按钮，显得空。

### 9.2 规则（已实现为 `kit.js` 的 `popupGeom`，`?variant=proposal` 可看）

形态由宽、高、内容档三者一起决定。硬约束：正文可视高 ≥120dp，整体宽高比 ≤2.2（分栏模式按正文列计）。

| 形态 | 条件 | 宽 | 高上限 | 页脚 |
|---|---|---|---|---|
| A 贴底 | W<600 且 H>480 | 整宽 | 0.9·(H−lift)；动作抽屉 0.72·(H−lift) | ≤2 个按钮横排，≥3 竖排 |
| B 居中 | W≥600 且 H>480 | 按内容档：s/对话框 400、m 480、l 640（W≥840 时 720） | min(H−80, 800) | 横排，主按钮在右 |
| C 紧凑 | H≤480，s/m 档或对话框 | min(W−32, 400 / 480) | H−24 | 横排；标题 18sp |
| D 分栏 | H≤480，l 档且 W≥600 | min(W−48, 680) | H−24 | 左栏（36%）放标题、说明和竖排操作，右栏正文滚动 |

内容档由调用方声明，不靠运行时猜：
- s：选择列表 ≤4 项，例如 `CardSheet.choices`「备份时机」。
- m：说明文字或短列表，例如 `CardSheet.show`「设备通道状态」、副本操作。
- l：多段表单，例如快捷按键、显示设置。

贴底动作抽屉（`BottomSheetCard`，例如插件「更多操作」的入口列表）单独把首屏上限收紧到 **0.72×可用高度**（可用高度 = H−lift，lift 是主界面悬浮胶囊的抬高量）：6 行左右的入口列表按 0.9 会被内容撑到屏幕上半区；收紧后超出部分在抽屉内滚动、页脚常驻。`CardSheet` 的 A 形态仍保持 0.9（说明 / 选择 / 长表单调用面广，长表单也需要更多正文高度），两者由 `SheetGeometry` 的两个方法区分，短屏（H≤480）行为不变（整屏可滚动）。

### 9.3 审计对比（弹层相关 6 屏 × 4 档）

| 档位 | 原样 | 提案 |
|---|---|---|
| 手机竖屏 | 360 整宽，终端底部露缝 74dp | 360 整宽，正文高 +64dp（快捷按键 509→573） |
| 手机横屏 | 540×268，比 2.0，正文 114–176dp；快捷按键可见 19% | 选择 / 说明 / 对话框 400–480 宽，比 ≤1.71；快捷按键分栏 680×292，正文 266dp，可见 47% |
| 平板竖屏 / 横屏 | 一律 540；说明 540×262 | 选择 400×326、说明 480×331、快捷按键 640×634 / 720×634（不再需要滚动） |

### 9.4 落地到原生

- `CardSheet.create(context, page)` 增加一个尺寸档参数（`SheetSize.S/M/L`，默认 M）。`wide()` 改为 `Mode mode(Configuration, size)`，规则抽到 `util/SheetGeometry`，纯逻辑配单测，按 AGENTS.md 放在 util 下。
- `CardSheet.onMeasure` 的 `limit` 改用 `screenHeightDp` 计算，替换现在的 `heightPixels × .84/.86`。后者含系统栏，在横屏上偏大。
- 页脚横排：`CardPage.footer` 在 A（≤2 个按钮）/ B / C 下改为 `HORIZONTAL`、权重 1、主按钮在后。D 模式由 `CardSheet` 组装两列，`CardPage` 本身不用改。
- 贴底弹层的 `lp.y`：只有宿主是带胶囊的 `MainActivity` 才抬高；终端和独立 Activity 设为 0。
- 贴底动作抽屉（`BottomSheetCard`）的首屏上限用 `SheetGeometry.bottomDrawerMaxHeight`（0.72）；`CardSheet` 的 BOTTOM 继续用 `SheetGeometry.maxHeight`（0.9），不合并成一个常量。
- `DshaDialogBuilder.sizeOnce` 的宽度上限 560 改成 400，短屏 maxHeight 继续用 `H−24`。build296 的 InfoSurface 已经通过 `keepWindowSize()` 按形态自定尺寸（平板气泡、手机横屏紧凑弹窗、竖屏底部面板），可以作为 `SheetGeometry` 的先例，两者的规则应合并到一处。
- 落地后用 `LayoutAuditInstrumentation` 补手机横屏档（例如 780×360）和平板档（800×1280、1280×800）。现在审计里只有手机竖屏和短屏，所以这类问题没被发现。
