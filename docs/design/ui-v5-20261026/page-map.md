# v5 页面结构与本机原生落点

原型：`interaction-v5`（离线 HTML，`catalogEntries 29`、`mainViews 16`）。  
原生基线：当前 `Sunset-3090-Q` 工作区源码 + [build202 变更说明](../../releases/build202-changes.zh.md)。  
性质：**对照与计划，不是已完成的原生重做。** build202 明确写着“插件/设置子页与部分原型页面（文件、备份、诊断）仍沿用现有实现，未按原型重做”。

## 1. 计数口径

- **16 个主视图**：`src/base.html` 的 `renderPageContent` 映射 13 个（`maintenance`、`home`、`onboarding`、`settings`、`plugins`、`terminal`、`workspace`、`files`、`backups`、`tasks`、`components`、`diagnostics`、`guide`），`src/refinement.js` 再追加 3 个（`storage`、`virtualscreen`、`skills`）→ 13 + 3 = **16**。
- **29 个目录条目**：`studioCatalog` 原始 30 条；`polish-settings.js` 删除“键盘操作指南（shortcuts）”后为 **29**，与 `delivery.json` 的 `catalogEntries: 29` 一致。
- **11 个设置落点**：设置列表渲染 **10 行**（`psetListHtml` 过滤掉 `connection` 与已删除的 `shortcuts`），另有 **1 个目录直达**的“手机连接方式（setting:connection）”，合计 11。
- 四根导航：`[['home','启动'],['plugins','插件'],['settings','设置'],['terminal','终端']]`（`src/base.html` 的导航定义）。

## 2. 四根导航

| v5 根导航 | 原型现状 | 现有原生落点 | 本试点处理 |
|---|---|---|---|
| 启动 / 主页 `home` | 启动主页：访问地址、完整日志、维护/启动/停止记录 | [LaunchFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L29)（`nav_launch`） | **本试点已声明**：启动主页信息层级、活动条、日志复制/跟随/导出（build202） |
| 插件 `plugins` | 模拟市场/管理/安装/导出 | [PluginFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L41)（`nav_plugins`） | **沿用现有真实功能**，不接入原型模拟数据 |
| 设置 `settings` | 10 行列表 + 详情子页 | [SettingsFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L26)（`nav_settings`） | **沿用现有真实功能**，不按原型重排 |
| 终端 `terminal` | 原型内模拟终端页 | [TerminalActivity.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L41)（`nav_terminal` 是动作） | **本试点已合入**；见第 5 节 |

原生底部菜单仍是 4 项：`nav_launch` / `nav_plugins` / `nav_settings` / `nav_terminal`（[bottom_nav_menu.xml](../../../app/src/main/res/menu/bottom_nav_menu.xml)）。点击 `nav_terminal` 时 [MainActivity.openTerminal()](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L640-L663) 拉起 `TerminalActivity`，并 `return false` 保留原来的“启动/插件/设置”选中项与草稿（[MainActivity.java#L358-L364](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L358-L364)）。

## 3. 16 个主视图

标记说明：**已声明** = build202 变更说明或当前源码已明确；**沿用** = 现有原生页继续用；**暂缓** = 原型有、原生未按原型重做。

| # | v5 主视图 | 原型现状 | 现有原生落点 | 本试点处理 |
|---:|---|---|---|---|
| 1 | 启动主页 `home` | 就绪/维护门禁、地址、完整日志 | `LaunchFragment` | **已声明**（外壳与日志工具） |
| 2 | 四步首启 `onboarding` | 4 步引导 + 进度点 | [WelcomeActivity](../../../app/src/main/java/com/deepseekharness/app/ui/WelcomeActivity.java#L28)（3 页）+ [OnboardingPrepareActivity](../../../app/src/main/java/com/deepseekharness/app/ui/OnboardingPrepareActivity.java#L13) | **沿用**；原型 4 步结构暂不重做 |
| 3 | 运行环境维护 `maintenance` | 阶段列表、波浪活动条、日志 | [EnvironmentRecoveryFragment](../../../app/src/main/java/com/deepseekharness/app/ui/EnvironmentRecoveryFragment.java)、`EnvironmentMaintenance` | **沿用**；活动条/阶段布局暂缓 |
| 4 | 工作台衔接 `workspace` | “进入工作台”演示页 | `LaunchFragment` 的就绪入口 + Web 宿主 | **沿用**；原型演示输入不接入 |
| 5 | 工作区文件 `files` | 浏览、预览、编辑、收藏、示例下载 | [WorkspaceFilesActivity](../../../app/src/main/java/com/deepseekharness/app/ui/WorkspaceFilesActivity.java#L42)（只读浏览器 + 另存/分享） | **沿用**；编辑/预演/收藏暂缓 |
| 6 | 备份与恢复 `backups` | 列表、校验、内容比较、选择性恢复 | [WorkspaceFragment](../../../app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L24) + [AutomaticBackupActivity](../../../app/src/main/java/com/deepseekharness/app/ui/AutomaticBackupActivity.java) | **沿用**；逐文档比较/选择性恢复暂缓 |
| 7 | 任务与记录 `tasks` | 当前任务 + 操作历史 | [AgentTasksActivity](../../../app/src/main/java/com/deepseekharness/app/ui/AgentTasksActivity.java#L14) + [BackgroundTasksActivity](../../../app/src/main/java/com/deepseekharness/app/ui/BackgroundTasksActivity.java#L17) | **沿用** |
| 8 | 环境组成 `components` | 组件清单、就绪条件、维护记录 | [InstallFragment](../../../app/src/main/java/com/deepseekharness/app/ui/InstallFragment.java#L27) + `EnvironmentMaintenance` | **沿用**；无独立“组件”页 |
| 9 | 自检与诊断 `diagnostics` | 检查项、日志、导出 | [DiagnosticActivity](../../../app/src/main/java/com/deepseekharness/app/ui/DiagnosticActivity.java) | **沿用** |
| 10 | 使用指南 `guide` | 分组指南 + 搜索 | 无独立原生指南页；`StorageHelpActivity`/`AboutFragment` 分散承载 | **暂缓** |
| 11 | 存储 `storage` | 存储占用/清理说明 | [StorageActivity](../../../app/src/main/java/com/deepseekharness/app/ui/StorageActivity.java) | **沿用** |
| 12 | 技能 `skills` | 技能目录 | [SkillsActivity](../../../app/src/main/java/com/deepseekharness/app/ui/SkillsActivity.java) | **沿用** |
| 13 | 虚拟屏 `virtualscreen` | 原型无实际设备桥接 | 只有 `util/VirtualScreen*` 策略/桥，无 UI 页面 | **暂缓**；不新增原型页 |
| 14 | 插件 `plugins` | 市场/管理/安装/核对/导出 | `PluginFragment` | **沿用**（build202：真实功能，模拟数据不接入） |
| 15 | 设置 `settings` | 分组列表 + 详情 | `SettingsFragment` + 子页 | **沿用** |
| 16 | 终端 `terminal` | 黑底模拟终端、侧栏、附加键 | `TerminalActivity` | **已合入**；见第 5 节与 [terminal-preservation.md](terminal-preservation.md) |

## 4. 设置子项（10 个列表行 + 1 个目录直达）

`settingGroups` 原始 4 组 8 项；`atelier.js` 追加 `connection`、`overlay`、`shortcuts`、`guide`，`polish-settings.js` 删除 `shortcuts`。最终 11 个落点，其中 `connection` 不出现在列表，只能从“体验总览”目录进入。

| v5 设置子项 | 原型内容 | 现有原生落点 | 本试点处理 |
|---|---|---|---|
| 运行环境 `environment` | 六项组件检查、维护、修复 | `InstallFragment`（安装/修复）+ `EnvironmentRecoveryFragment` | **沿用** |
| 运行配置 `runtime` | 引擎、端口、DNS、局域网、画中画 | [ConfigFragment](../../../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L39) | **沿用** |
| 数据与备份 `data` | 备份计划、恢复、导出、技能入口 | `WorkspaceFragment` + `AutomaticBackupActivity` | **沿用** |
| 设备能力 `permissions` | Root/Shizuku/ADB/无障碍/短信/位置/屏幕 | [DeviceGrantsFragment](../../../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L30)；配对/无障碍分别走 `AdbPairActivity`、`AccessibilitySetupActivity` | **沿用** |
| 外观与体验 `appearance` | 主题、玻璃、字号、密度、动效、省电 | `SettingsFragment` 的外观/语言/省电入口 + `ThemeController` | **沿用**；原型“字号滑杆/悬浮预览”暂缓 |
| 悬浮条 `overlay` | 悬浮条样式/内容/位置 | [OverlayFragment](../../../app/src/main/java/com/deepseekharness/app/ui/OverlayFragment.java#L18) | **沿用** |
| 语言 `language` | 10 语言弹窗，文案仍简体中文 | `SettingsFragment` 语言弹窗 + [LanguageController](../../../app/src/main/java/com/deepseekharness/app/ui/LanguageController.java#L22) | **沿用**；语言内容与 RTL 暂缓 |
| 版本更新 `updates` | 通道、检查、变更记录、回退条件 | [UpdateActivity](../../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java) | **沿用** |
| 自检与诊断 `diagnostics` | 自检、维护日志、启动状态、任务 | [DiagnosticActivity](../../../app/src/main/java/com/deepseekharness/app/ui/DiagnosticActivity.java) | **沿用** |
| 使用指南 `guide` | 按当前需要找入口 | 无独立页 | **暂缓** |
| 手机连接方式 `connection` | Shizuku/无线 ADB/Root 指引 | `DeviceGrantsFragment` + `AdbPairActivity` | **沿用**；原型为目录直达 |

## 5. 终端是“动作”，不是页内终端

这一点在文档和实现里都要写死：

1. 底部导航 `nav_terminal` 不是“切换到第 4 个页面”，而是调用 `openTerminal()`（[MainActivity.java#L358-L364](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L358-L364)）。
2. `openTerminal()` 先做就绪/环境/保险门禁，并用 `terminalPageInFlight` 去重，再 `terminalPage.launch(new Intent(this, TerminalActivity.class))`（[MainActivity.java#L640-L663](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L640-L663)）。
3. `TerminalActivity` 是**独立、非导出、同 task、singleTop、adjustResize** 的全屏宿主（[AndroidManifest.xml#L100-L107](../../../app/src/main/AndroidManifest.xml#L100-L107)）。
4. 返回只 `finish()` 宿主；不 `shutdown`、不 `exit`、不 `Ctrl+D`、不 `stopWeb`（[TerminalActivity.java#L96-L119](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L96-L119)、[#L1746-L1753](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1746-L1753)）。
5. 因此原型里的“终端页”在原生侧没有对应的“页内 Fragment”，不要按普通主视图去接导航；详见 [terminal-preservation.md](terminal-preservation.md)。

## 6. 状态汇总

| 处理 | 数量 | 页面/落点 |
|---|---:|---|
| 已声明 / 已合入（表内） | 2 | 启动主页、终端；另有不在 16 视图内的原生外壳（四根导航容器） |
| 沿用现有真实功能 | 22 | 16 个主视图中的 12 个 + 11 个设置落点中的 10 个 |
| 暂缓（整页） | 3 个表内落点 / 2 个独立页面 | 主视图“使用指南”、设置“使用指南”（同一页面重复入口）、主视图“虚拟屏” |
| 暂缓（子能力） | 4 类 | 文件编辑/预览/收藏、备份逐文档比较/选择性恢复、语言全文与 RTL、外观字号滑杆/悬浮预览；这些页面的主状态仍是“沿用” |

> 计数说明：表内共 16 + 11 = 27 个落点，行级分布为 2 已声明 + 22 沿用 + 3 暂缓；原生外壳不计入表内行。子能力暂缓不与整页重复计数，也不是 `delivery.json` 的计数，不能当验收数字。

## 7. 风险与矛盾

1. **“四根导航”在原生是底部菜单，不是原型侧栏**；手机悬浮胶囊/平板左栏是 build202 声明的新外壳，不能拿原型布局直接推断原生尺寸。
2. **终端不是页内内容**：任何“把 terminal 当第 4 个 Fragment 塞进 container”的实现都会和现有 `TerminalActivity` 的会话保护/返回语义冲突。
3. **设置子项数量对不上**：原型设置列表 10 行，但目录里还有 `setting:connection` 第 11 个入口；评审时不要用“10”去核对目录的 29。
4. **文件页能力差距最大**：原型可编辑/预览/收藏，原生 `WorkspaceFilesActivity` 是只读浏览器（注释原文 “Read-only work-file browser”）；build202 也明说文件页沿用现有实现。文档不能写成“文件页已按原型落地”。
5. **指南/虚拟屏没有原生页**：虚拟屏只有 `util/VirtualScreen*` 策略与桥，`vscreen/` 是独立虚拟屏实现，不是设置里的“虚拟屏”页面；不要为了凑 16 个主视图新增空壳页。
6. **构建身份别混**：build202 测试包是 `dsh.android.client.SunsetRNE`，build198 历史测试包是 `dsh.android.client.SunsetRNE2`；两者不是同一个包名。详见 [terminal-preservation.md](terminal-preservation.md)。

## 8. build203 替换二批（2026-10-27）实际状态

本节回填 2026-10-27 的替换二批，读前面「沿用」行时以本节为准。

| 页面/落点 | 二批处理 | 现状 |
|---|---|---|
| 设置、插件、启动主页 | 已替换 | 使用 `shell_page.xml` 组件样式（`ShellPageTitle` 等） |
| 运行配置、安装与环境、设备能力授权、数据与备份、更新、自检与诊断、欢迎/首启、关于、环境恢复、CardPage 内页 | 已替换外观 | 标题、分组、卡片按 26sp / 13sp / 12sp 与 v5 卡片**手写对齐**；没有使用 `ShellPageTitle` / `ShellCard` 等样式，下次改字阶仍要逐页同步 |
| ADB 配对 | 二批收口 | 删掉顶栏重复标题；页面标题/说明改用 `ShellPageTitle` / `ShellPageDescription` |
| 插件安装确认 | 二批收口 | 标题 22sp → `ShellPageTitle`；按钮换 `ShellPrimaryButton` / `ShellTextButton` |
| 运行环境维护（解包，`ExtractActivity`） | 二批收口 | 去掉 18sp 应用名行与 27sp 标题，改 `ShellPageTitle` + `ShellCard` + v5 按钮；按钮改在 XML 声明 |
| 文件浏览器、远程配对/扫码/工作区、Web 预览、首启装饰页 | 未替换 | 仍是旧结构；远程配对/工作区只有手写 26sp 标题 |
| 终端 | 已合入 | 新建会话/结束会话/回到输入首击修复，侧栏主题分段删除；仍是独立 `TerminalActivity` |

仍未接入组件层的布局（截至 build203）：`fragment_config`、`fragment_device_grants`、`fragment_install`、`fragment_workspace`、`activity_update`、`activity_diagnostics`、`build195_remote_pair`、`build195_remote_workspaces`、`welcome_page2`、`welcome_page3`。下一步统一改用 `ShellPageTitle` / `ShellCard` / `ShellSectionLabel`，否则「统一页面组件」只是视觉近似。

状态补充：第 6 节统计的是 build202 试点口径；替换二批新增 13 个页面/落点（设置、插件、运行配置、安装、授权、数据、更新、诊断、欢迎、关于、环境恢复、启动主页与 CardPage 内页），其中多数为手写对齐而非样式复用。

