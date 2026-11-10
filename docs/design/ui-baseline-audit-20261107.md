# UI 设计基准线全仓库审计（2026-11-07）

> 审计会话：`session-a56dec0f-8e8b-4028-a58e-b0199f981645`（T112，接手 `e83c001a` 的现场）
> 工作树 / 分支：`.agents/worktrees/docs/ui-audit` / `docs/ui-audit`，基于 `origin/main` `5474032f`
> 基准来源：[外部 PR 审查流程](../external-pr-review.md) 的「UI 设计基准线」（2026-11-07，`5474032f`）
> 性质：**只读静态审计**。未修改 `app/src`、`tools/`、插件目录；未在真机运行；未跑 `LayoutAuditInstrumentation`；未 push、未合并。本文件是唯一提交物。

## 0. 结论摘要

1. **存量 UI 在「按钮只保留动作」上已基本收敛**：全仓 143 个 `ui/*.java`、44 个布局中，只找到 **1 组**明确违反——启动恢复页的动作行仍为每行带一句「效果说明」（7 处定义，最多约 5–6 行同屏，`StartupRecoveryLayout.java:323-350`）。其余长标签多是导航行副标题（设置/数据页的 `entry`），本报告不按按钮注释计。
2. **真正的大缺口是基准 ④ 与 ②③**：
   - 一级页面仍有长文本，且状态文本/整块内容刷新普遍**瞬时替换**；
   - 二级页有大量「一句话说明」默认铺在页面上，没有走已有的 `InfoHint` / `CardSheet` / 折叠设施；
   - 29 个文件、51 处 `removeAllViews` 整块重建；68 个有动态文本/重建的文件里 **42 个完全没有动画设施**（62%）。
3. **主会话初扫的「长文案只有 2 条」只对 `values/ui_strings.xml` 成立**：该文件只有 85 条「布局直接引用的标签」，而运行时文案在 Java 字面量里——含中文的 UI 字面量 2,986 条，**≥30 码点 278 处**、≥40 码点 155 处、≥60 码点 67 处。完整口径见 §2.3。
4. **最严重的 3 条**：
   - **B-01** `ExtractActivity`：全屏维护页常驻 47 字 lead，且阶段/分段每次 `removeAllViews` 重建——覆盖安装与首次部署的必经页；
   - **B-05 + B-04** `StartupRecoveryActivity` / `StartupRecoveryLayout`：动作行带功能注释（唯一明确的基准 ① 违反），同时整页动作/记录/快照全量重建（④）；
   - **A-01 + A-02** `LaunchFragment`：一级「启动」页副标题在恢复核对时写入整句说明，启动状态条为多行 + 每秒刷新，均无过渡。
5. **P0：0 项**（没有安全、数据或启动阻断类问题）。本报告不做整改，只给出可执行的最小修法与改动面估计，便于主会话按页面分批派工。

### 汇总计数

| 类别 | 数量 | 其中 P1 | 其中 P2 | 口径 |
|---|---:|---:|---:|---|
| ① 首屏长文（一级页面默认可见长文本） | **7 处 / 6 页** | 5 | 2 | 一级 Tab / 全屏入口页，默认可见、未折叠、非弹窗，≥30 码点 |
| ② 状态/数据跳变（无动画/过渡） | **30 处** | 12 | 18 | 动态文本、可见性、列表/页面整块替换；逐条见 §3–§8 |
| ③ 长文本 | **278 处 ≥30 码点**（≥40 155、≥60 67、≥80 33） | 12 | 13 | 另：`ui_strings.xml` 2 条，手命名标签 9 条；默认可见未折叠 25 处（含首屏 7 处），明细见 §2.3 |
| ④ 按钮功能注释 | **9 处** | 7 | 2 | 动作行效果说明 7 + XML 回退标签 1 + 忙碌标签 1 |
| **合计（去重后的 finding 行）** | **51 行** | **22** | **29** | ①与③有 7 处重叠，按 finding 行去重 |

> 分类计数按各自口径（同一 finding 可能在不同类别以不同严重度出现）：例如 C-02 的**长文本**部分按 P2 计入 ③，它的**警告出现无过渡**按 P1 计入 ②。finding 行的总 P1 为 22、P2 为 29，以 §3–§9 的级别列合计为准。

补充基线数据（2026-11-07，`5474032f`）：

- `app/src/main/java/com/deepseekharness/app/ui/`：143 个 `.java`；`res/layout*`：44 个 XML（`layout` 40 + `layout-land` 1 + `layout-sw600dp` 2 + `layout-w840dp` 1）。
- `setText(` 共 915 次 / 73 文件；`setVisibility(` 285 次 / 46 文件；`removeAllViews` 51 次 / 29 文件。
- 动画设施：`UiMotion` 14 文件 / 42 次；`InfoHint` 7 文件 / 10 次；`InfoSurface` 1 文件；`CardSheet` 20 文件 / 51 次；`DshaDialogBuilder` 39 文件 / 114 次；`ObjectAnimator` 2 文件 / 5 次，`ValueAnimator` 7 文件 / 17 次。
- 有动态文本或重建的文件 68 个，其中 42 个没有任何动画调用（无 `UiMotion`、`animate()`、`ValueAnimator`、`alpha/translation` 等）。

## 1. 基线、口径与分级

### 1.1 被审基线

[外部 PR 审查流程](../external-pr-review.md) 第 39、41–49 行（2026-11-07 用户明确要求，全仓库适用）：

1. 按钮只保留动作本身，不带功能注释或解释性副标题；
2. 首 Top 页不放长文本描述，必要说明移到二级页、折叠区或弹窗；
3. 二级页面能折叠的折叠，适合弹窗的用弹窗，尽量减少默认可见内容；
4. UI 信息/数据/状态变化必须用动画或过渡呈现（淡入淡出、位移、尺寸与数值过渡等），禁止长文本堆叠或整段刷新跳变；动画不适合时用默认折叠收起或弹窗过渡；
5. 新增/修改 UI 违反任一条按 P1 打回；存量整改见 T112。

对照组：[UI 模板基准（build280）](ui-template-baseline-20261103/README.md) 定义尺寸、断点与外壳 token；本审计只审「行为与信息密度」，不重新审尺寸/配色。

### 1.2 判定口径（重要）

- **长文本**：按 Unicode 码点计，含标点与拉丁字符；阈值取 **≥30 码点**（约 320–360dp 宽下 1.5–2 行正文）。英文串不计。
- **默认可见**：`setText`/布局 `android:text` 后在页面默认状态直接展示；经 `DshaDialogBuilder`、`CardSheet`、`InfoSurface`/`InfoHint` 弹层、或默认收起的折叠区展示的**不计**。
- **一级页面**：主 Tab（Launch / Plugin / Settings）、主壳（MainActivity + 顶部环境横幅）、引导（Onboarding）、全屏维护（Extract）、启动恢复（StartupRecovery）、Web 预览（WebPreview）、启动加载（BootScreen）。
- **按钮**：可点击的**动作控件**（Button/带 `role=button` 的行）。设置页、数据页的导航行（标题 + 副标题的 `entry`）是「去哪儿」而非「做什么」，不计入按钮注释；边界说明见 §9。
- **状态/数据跳变**：动态内容在已显示状态下被替换，且没有 animation/transition；折叠/弹窗本身是基准允许的降级路径，不计。
- **分级**：
  - **P1**：明确违反基准、用户默认可见、且修复必要性高；
  - **P2**：边界（一句话状态说明、短副标题、已有部分折叠/信息入口）、或修复成本低但影响小的系统性问题；
  - **P0**：本轮无。
- **证据等级**：A = 逐行读了源码与布局；B = 机械扫描（正则/长度统计）+ 抽样核对。finding 表内均为 A；汇总数字来自 B 并已在 §2 复算。
- **已知字数修正**：`ui_m0084` 实际 62 字、`ui_m0118` 57 字（主会话初扫的 64/59 计入了 XML 包裹引号；Android 解析时会去掉引号）。结论不变。

## 2. 四类汇总（可复算）

### 2.1 ① 首屏长文：7 处 / 6 页

| # | 页面 | 位置 | 默认可见的长文本 | 级别 |
|---|---|---|---|---:|
| 1 | 启动（一级 Tab） | `LaunchFragment.java:569-580` | 恢复核对时副标题写入 `restore.notice()` 整句（30–90 字，随 reason 变化） | P1 |
| 2 | 启动（一级 Tab） | `LaunchFragment.java:592-616` | 启动中状态条两行：`阶段 · 本阶段 N 秒 · 总计 N 秒` + 「下方实时显示启动输出…」 | P1 |
| 3 | 引导第 4 步 | `OnboardingActivity.java:1269-1276` | 部署中 lead 41 字（「运行环境还在部署…」）；同文件 `1290-1300` 的未就绪状态句同类 | P2 |
| 4 | 运行环境维护（全屏） | `ExtractActivity.java:313-317` | 无模式说明时 lead 常驻 47 字（「相同基础环境只更新 dsh…」） | P1 |
| 5 | 应急 DSH（全屏） | `RecoveryActivity.java:41-48` | 首屏 63 字说明（「正式环境维护失败时…」） | P1 |
| 6 | 环境恢复（全屏） | `EnvironmentRecoveryFragment.java:14-20` | 页面副标题 50 字（「可以在设置中修改配置…」） | P1 |
| 7 | 主壳顶部横幅 | `EnvironmentNoticeBanner.java:94-107` + `MainActivity.java:1278-1328` | 标题 + 最多 2–3 行 detail（「…· 点按查看进度」） | P2 |

> 说明：WebPreview 的错误面板字数也偏长，但它是错误覆盖层而非页面默认内容，归入 ②（F-01），不重复计。

### 2.2 ② 状态/数据跳变：30 处

按「是否影响主流程」分档，逐条位置见 §3–§8 的 finding 表。计数口径：同一组件同一渲染函数算 1 处；同一 finding 里「可见性 + 文本 + 列表」合并为 1。

- **P1（12）**：A-01、A-02、B-01、B-02、B-04、C-02、E-01、E-02、E-04、E-08、F-01、F-02。
- **P2（18）**：A-03、A-04、A-05、B-03、B-07、B-08、B-09、C-03、C-08、C-09、D-03、E-05、E-06、E-09、E-10、F-03、F-04、F-07。

### 2.3 ③ 长文本：总量、来源与默认可见分布

| 来源 | 条数 / 计数 | 说明 |
|---|---:|---|
| `app/src/main/java/com/deepseekharness/app/ui/*.java` 含中文字面量 | **2,986** | 扫描 143 文件；含 `UiText.choose` 的中文参数、错误文案、contentDescription 等 |
| 其中 ≥30 码点 | **278** | 长文本主体；≥40：155；≥60：67；≥80：33 |
| `app/src/main/res/values/ui_strings.xml`（zh） | 85 条，**≥30/≥40 均 2 条** | `ui_m0084` 62 字、`ui_m0118` 57 字；该文件只有「布局直接引用的标签」，不是全部文案 |
| `app/src/main/res/values/*.xml` 手命名标签（zh，不含 `ui_strings.xml`） | 236 条，15 条 ≥30，**9 条 ≥40** | 如 `ui2_config_sub` 43、`ui_lan_unencrypted` 41、`ui134_root_hint` 80、`accessibility_desc` 85 |
| **默认可见、未折叠、非弹窗的长文本** | **25 处**（P1 12 / P2 13） | 含首屏 7 处；分布见下表 |

默认可见 25 处的分布（首屏 7 处见 §2.1，此处列二级页 18 处）：

| 页面 | 位置 | 字数 | 级别 |
|---|---|---:|---:|
| 设备能力授权 | `fragment_device_grants.xml:144`（`ui_m0084`） | 62 | P1 |
| 设备能力授权 | `DeviceGrantsFragment.java:1356-1370`（root 放宽警告，已收成一句） | 43 | P2（文本）/P1（跳变） |
| 屏幕操作设置 | `AccessibilitySetupActivity.java:48-53` | 89 | P1 |
| 跨应用文件访问 | `SafAccessFragment.java:64-71` | 86 | P1 |
| 引导授权卡（root 通道） | `PermissionChannelCard.java:270-282` | 74 | P1 |
| 运行配置 | `fragment_config.xml:19`（`ui2_config_sub`） | 43 | P2 |
| 运行配置 | `ConfigFragment.java:627-637`（启动目标说明） | 96 | P1 |
| GitHub 登录 | `GitHubLoginFragment.java:103-113` | 45 | P1 |
| 主题 | `ThemeActivity.java:295-303` | 33 | P2 |
| 作品文件导出 | `WorkspaceFilesActivity.java:185-191` | 45 | P2 |
| Agent 任务 | `AgentTasksActivity.java:36-40` | 35 | P2 |
| 存储与文件 | `StorageActivity.java:33-38` | 52 | P2 |
| 保留副本与旧环境 | `RetainedDataActivity.java:60-66` | 44 | P2 |
| 自动备份 | `AutomaticBackupActivity.java:57` | 31 | P2 |
| 自动备份 | `AutomaticBackupActivity.java:153-164` | 59 + 37 | P1 |
| 恢复向导步骤 | `NativeDataActivity.java:586-596` / `1181-1188` | 各约 40 | P2 |
| 存储帮助（首组默认展开） | `StorageHelpActivity.java:33-44` | 66 + 53 | P2 |
| 应急 Web | `RecoveryWebActivity.java:109-115` | 44 | P2 |

> 结论：主会话初扫「只有 2 条长文案」不足以代表存量；但长文本并非都要删——**绝大多数（278 条中的约 253 条）已在弹窗、InfoHint、折叠区或日志里**，真正需要整改的是上表 25 处默认可见项。

### 2.4 ④ 按钮功能注释：9 处

| # | 位置 | 注释形态 | 级别 |
|---|---|---|---:|
| 1–7 | `StartupRecoveryLayout.java:323-350`（数据源 `StartupRecoveryActivity.java:306-312, 408-472`） | 动作行「标题 + 一行效果说明」：启动 DSH / 重试启动 / 安全启动 / 新建配置文件 / 恢复最近健康快照 / 回滚未完成的修复 / 启动独立应急 DSH；最多 5–6 行同屏 | P1 |
| 8 | `plugin_list_header.xml:24-27`（`ui_m0104`） | XML 默认 `android:text="安全模式：恢复此前启用的第三方插件"`；运行时被 `PluginFragment.java:262-263` 改成「恢复」，但布局回退值与读屏描述仍是注释句 | P2 |
| 9 | `AdbPairActivity.java:54-58` | 忙碌时把动作标签换成「正在处理，请等待结果…」 | P2 |

> 边界：设置页 6 个入口行（`SettingsFragment.java:31-39`）、数据页 `CardPage.entry` 的「标题 + 副标题」、授权页各行 hint 属于导航/列表项，不是动作按钮，本报告不计；如果用户要求把「按钮」扩大到所有可点行，这批会成为第二批整改对象（约 60+ 行），建议先与用户确认口径。

## 3. 一级页面 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| A-01 | `LaunchFragment.java:569-580` | `launch_subtitle` 在未就绪/未确认时直接 `setText(restore.notice())`；`WebSessionRestoreText.unresolved` 的单句可达 90 字；此前是 12 字短提示，整句替换无过渡 | ②④ | 副标题固定为「运行时 · dsh 版本」或 2–4 字状态词；完整 notice 收进 `InfoHint`/可展开的核对详情；文本切换用 120–160ms 淡入 | P1 | 1 文件，约 30 行；复用现有 `InfoHint`，不新增翻译键 |
| A-02 | `LaunchFragment.java:592-616` | 启动中 `launch_status` 显示两行（阶段 + 两个计时 + 固定说明），`setVisibility` 与 `setText` 每轮轮询执行；高度变化无过渡 | ②④ | 状态条只留一行（阶段 + 计时），固定说明移入已存在的启动日志面板；用固定最小高度 + 文本淡入避免跳动 | P1 | 1 文件，约 25 行 |
| A-03 | `EnvironmentNoticeBanner.java:94-107`、`MainActivity.java:1278-1328` | 横幅 `setVisibility(GONE/VISIBLE)` + 标题/detail 直接 `setText`；detail 最多 3 行 | ②④（④ 为主） | `show()` 增加 fade/expand（height/alpha 150ms）；detail 压到 1 行，剩余内容点按进详情 | P2 | 1–2 文件，约 40 行 |
| A-04 | `PluginFragment.java:876-930` | 插件状态条的可见性、计数、状态文本、图标瞬时切换；条高已固定，但文本仍跳 | ④ | 状态文本做 100–150ms 淡入；计数变化用 `PluginTaskProgress` 相同节奏的数值过渡 | P2 | 1 文件，约 20 行 |
| A-05 | `SettingsFragment.java:104-118` | 省电模式副标题随开关在「熄屏空闲 1 分钟后…」与「持续保持运行…」之间直接换句 | ④ | 用 120ms 淡入；或固定一句短摘要 + ⓘ | P2 | 1 文件，约 10 行 |
| A-06 | `OnboardingActivity.java:1269-1276`、`1290-1300` | 引导第 4 步 lead 为 41 字状态说明；部署完成/失败时整句替换 | ②④（边界） | lead 收成 1 行结论；原因/细节复用下方 runtimeCard 与 `CardSheet`；文本淡入 | P2 | 1 文件，约 15 行 |

## 4. 维护、部署与启动恢复 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| B-01 | `ExtractActivity.java:291-325` | 全屏维护页：`status`、`pill`、`lead` 直接被赋值；`segments.removeAllViews()` + `stages.removeAllViews()` 每次 render 重建；lead 常驻 47 字 | ②④ | lead 只留一句当前结论（约 15 字），完整说明用 `InfoHint`/`CardSheet`；阶段列表改为复用行或整体淡入；保留滚动位置 | P1 | 1 文件，约 40 行；与 `DeployCard` 已有阶段模型对齐 |
| B-02 | `InstallFragment.java:352-410` | 环境结论标题（「环境可用 · 全部组件正常」等）、状态、进度（含 `elapsedSeconds`/`stageSeconds`）、六段 meter 每轮刷新直接赋值 | ④ | 标题/状态淡入；计时数字用 200ms 数值过渡；meter 段位用颜色/缩放过渡；整体保持固定高度 | P1 | 1 文件，约 35 行 |
| B-03 | `InstallFragment.java:589-608` | 版本 chips 每次 `host.removeAllViews()` 后重建（Ubuntu/dsh/App 三枚），刷新时闪动 | ④ | 三枚 chip 固定创建一次，后续只更新文本（保留点按复制）；或整体淡入 | P2 | 1 文件，约 20 行 |
| B-04 | `StartupRecoveryActivity.java:289-327, 689-704, 760-761` | 恢复页 `recovery`/`tools`/`attempts`/`snapshots`/`plugins` 五个容器全量 `removeAllViews` 重建；每次状态刷新（含 1s 轮询）整段替换 | ④ | 行级复用（按 action id 更新），或至少对新增/消失容器做 fade/expand；保留滚动位置 | P1 | 1 文件，约 60 行；建议与 B-05 同批 |
| B-05 | `StartupRecoveryLayout.java:323-350`（数据源 `StartupRecoveryActivity.java:306-312, 408-472`） | 动作行 = 图标 + 标题 + 一行效果说明；`Act.effect` 在 7 个动作上非空（如「正常启动，加载全部已启用插件。」「只加载官方基础界面…」） | ① | 可见行只留动作标题；效果说明移到行尾 ⓘ（`InfoHint`）或点开后的 `CardSheet`；「不可用：原因」作为禁用状态保留（不是注释） | P1 | 2 文件，约 40 行；`InfoHint` 已在本包可用 |
| B-06 | `StartupRecoveryLayout.java:104-110` | 「配置快照」分组说明 34 字默认可见（「保留三次健康启动与三次修复前快照…」） | ③ | 用 `InfoHint` 挂到分组标题；默认只显示标题 | P2 | 1 文件，约 5 行 |
| B-07 | `StartupRecoveryActivity.java:108-118` | 操作结果横幅 `progress` 直接 `setText` + `setVisibility` | ④ | 复用 `EnvironmentNoticeBanner` 的淡入或 `InfoSurface` 的 160ms fade | P2 | 1 文件，约 15 行 |
| B-08 | `BootScreen.java:298-304` | 启动加载页的 `hint` 在已显示状态下直接换文本（同页已有 fadeUp/exit 动画） | ④（边界） | `hint` 文本切换加 120ms 淡入；避免与开场动画叠加 | P2 | 1 文件，约 8 行 |
| B-09 | `DeployCapsule.java:99-127` | 部署胶囊 `label`/`sub`/`counter` 直接赋值，胶囊本身 `setVisibility` 直接显示；只有状态点有 pulse | ④ | 标签/counter 淡入；胶囊用展开/淡入 150ms；状态点 pulse 已合规 | P2 | 1 文件，约 20 行 |

## 5. 设备授权与权限 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| C-01 | `fragment_device_grants.xml:144` | 电池优化行副标题直接引用 `ui_m0084`（62 字），`maxLines=3`，默认可见；页面其他长说明已用 ⓘ | ③ | 行内改 1 行摘要（「后台保活与电池限制」），完整 62 字绑定 `InfoHint`；或并入「电池优化」设置弹窗 | P1 | 1 布局 + 1 Java（`DeviceGrantsFragment`）约 15 行 |
| C-02 | `DeviceGrantsFragment.java:1348-1370` | root 放宽警告 `setVisibility` 直接显示/隐藏，文本整句替换（43 字）；完整原文在 tag/CardSheet | ④ | 警告出现/消失用 expand+fade（已在同页的 `InfoSurface` 有动画基线）；文本淡入 | P1 | 1 文件，约 20 行 |
| C-03 | `DeviceGrantsFragment.java:1546-1556` | 概览 pill 的 `pillValues` 文本与色点颜色瞬时刷新，每秒连接轮询都会走 | ④ | 文本淡入 + 色点 150ms 颜色过渡；只在实际值变化时更新 | P2 | 1 文件，约 15 行 |
| C-04 | `AccessibilitySetupActivity.java:45-53` | 「怎么使用」卡内 89 字段落默认可见；「找不到开关」已走 `CardSheet` | ③ | 段落收成 1 行摘要，全文挂 `InfoHint`；或在「怎么使用」卡内折叠 | P1 | 1 文件，约 10 行 |
| C-05 | `SafAccessFragment.java:64-71` | `saf_intro` 86 字段落直接 `setText` 在二级页顶部 | ③ | 用 `InfoHint.bind(saf_intro, …)`；保留 1–2 行摘要 | P1 | 1 文件，约 5 行 |
| C-06 | `PermissionChannelCard.java:270-282` | root 通道的 74 字说明以 `kit.text` 直接加在授权卡内（引导授权页） | ③ | 同一卡已有 `statusBox`/ⓘ 模式；改用 `InfoHint` 或折叠区 | P1 | 1 文件，约 5 行 |
| C-07 | `AdbPairActivity.java:54-58` | 忙碌时按钮标签变成「正在处理，请等待结果…」，动作标签被状态句替代 | ①（边界） | 按钮保留「开始配对」+ spinner/禁用；状态放到 `statusText` | P2 | 1 文件，约 5 行 |
| C-08 | `AdbPairActivity.java:43-58` | 状态区（maxLines=3）与 5 个控件 enabled 状态在同一 observe 内直接切换 | ④ | 状态文本淡入；enabled 变化可保持即时（可用性反馈） | P2 | 1 文件，约 10 行 |
| C-09 | `DeviceGrantsFragment.java:600-610, 646-650` | 识屏模式状态在「切换中…」与结果之间直接换文本；无成功反馈动画（T113 已记 P2） | ④ | 切换中显示 spinner + 结果淡入；与 T113 合并处理 | P2 | 1 文件，约 15 行；与 T113 同文件 |

## 6. 配置、设置、主题与账号 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| D-01 | `fragment_config.xml:19`（`ui2_config_sub`） | 运行配置页顶部 43 字说明常驻 | ③（边界） | 精简为「端口与运行方式」或挂 ⓘ；与 D-02 同批 | P2 | 1 布局 + 少量 Java |
| D-02 | `ConfigFragment.java:627-637` | 「启动目标」开关下 96 字说明常驻（含「在 Web 里…」「禁用 dsh-launch-target 插件…」） | ③ | 行内 1 行摘要 + `InfoHint` 全文；该页已有 `InfoHint` 依赖 | P1 | 1 文件，约 10 行 |
| D-03 | `ConfigFragment.java:537-552` | 保存条在「端口无效」/「N 项未保存…」/「配置已是最新」之间直接替换文本与颜色 | ④ | 文本/颜色 120ms 过渡；错误态可用轻抖或色条（不新增长文案） | P2 | 1 文件，约 15 行 |
| D-04 | `GitHubLoginFragment.java:103-113` | 45 字 token 明文策略段落默认可见 | ③ | 挂 `InfoHint`；登录页保留「token 仅存容器内」一句短提示 | P1 | 1 文件，约 5 行 |
| D-05 | `ThemeActivity.java:295-303` | 「Web 界面跟随」行副标题 33 字 | ③（边界） | 可保持（行副标题），建议压到 1 行或挂 ⓘ | P2 | 1 文件，约 5 行 |
| D-06 | `WorkspaceFilesActivity.java:185-191`、`AgentTasksActivity.java:36-40`、`StorageActivity.java:33-38`、`RetainedDataActivity.java:60-66` | 四个二级页的 35–52 字页面引导句常驻 | ③（边界） | 统一压到 1 行（≤20 字），全文挂 ⓘ；与 D-01/D-02 同一改法 | P2 | 4 文件，各约 5 行 |
| D-07 | `AutomaticBackupActivity.java:57, 153, 160` | 页面说明 31 字 + 设置区 59 字 + 本机副本 37 字，默认可见 | ③ | 59/37 字两条挂 `InfoHint`；页面说明压到 1 行 | P1 | 1 文件，约 10 行 |

## 7. 数据与备份 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| E-01 | `NativeDataActivity.java:507-555` | 恢复向导 `renderStepper()` + `renderWizard()` 每步 `removeAllViews()` 后重建整步内容；步进切换是整段替换 | ④ | 保留 4 个步骤容器，只切可见性；或用淡入/横向 120ms；保留已输入内容（选项步已做） | P1 | 1 文件，约 50 行 |
| E-02 | `NativeDataActivity.java:1372-1390` | 状态卡用 `format("%s\n%s 项 · %s%s%s")` 一次写入结果 + 计数 + 字节 + 错误 + 投影错误；多行整段刷新 | ④ | 拆成 3 个字段（结论/计数/错误），各自淡入；错误行单独展开 | P1 | 1 文件，约 25 行 |
| E-03 | `NativeDataActivity.java:586-596, 1181-1188` | 步骤说明约 40 字默认可见（选择文件/旧加密备份说明） | ③（边界） | 步骤内说明可保留；若再压，挂 ⓘ；与 E-01 一起做 | P2 | 1 文件，约 5 行 |
| E-04 | `WorkspaceFragment.java:396-403` | 数据页状态卡 `title`/`detail` 直接替换；`detail` 可再拼 `nativeStatus`（多行，含保存路径/错误） | ④ | 标题短结论 + 详情 1 行 + ⓘ/展开；切片结果用淡入；保持卡片高度稳定 | P1 | 1 文件，约 30 行 |
| E-05 | `RetainedDataActivity.java:289-299` | `rows.removeAllViews()` + `pageStatus` 文本直接更新 | ④ | 分页列表 diff 或整体淡入；状态行淡入 | P2 | 1 文件，约 20 行 |
| E-06 | `StorageActivity.java:136-142` | 总量文本 + 占用条/图例全量重建 | ④ | 占用条用已有 `StorageUsage` 数据做过渡；图例行复用 | P2 | 1 文件，约 25 行 |
| E-07 | `StorageHelpActivity.java:33-44, 113-115` | 页面默认只展开第一组；第一组含 66 + 53 字两段 | ③（边界） | 默认全部收起，或第一组只显示 1 行摘要 + 「展开」；折叠交互已存在 | P2 | 1 文件，约 5 行 |
| E-08 | `FileTransfersActivity.java:303-310, 780-815` | 手机档用两个 `ScrollView` 叠放，通过 `setVisibility` 在列表/详情间瞬时切换；`detailContent.removeAllViews()` 重建 | ④ | 页面切换用 `UiMotion` 风格滑入/淡入（保留列表滚动位置）；详情内容保留视图只更新字段 | P1 | 1 文件，约 60 行 |
| E-09 | `FileTransfersActivity.java:1227-1231` | 操作结果 notice 条 `setVisibility(VISIBLE)` 无过渡，可能覆盖列表顶部 | ④ | 顶部 notice 用 expand+fade；自动消失可用 slide | P2 | 1 文件，约 15 行 |
| E-10 | `BackgroundTasksActivity.java:190-216` | 采用 diff 更新（内容相同不写），是正确方向；但计数/标题/列表变化仍无过渡 | ④（边界） | 计数淡入；列表新增项用 `UiMotion.staggerIn`；作为「保留视图」的参考实现 | P2 | 1 文件，约 15 行 |

## 8. 终端、Web、诊断、应急 findings

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| F-01 | `WebPreviewActivity.java:577-578, 1113-1115, 1481-1514`；`WebCrashPanel.java:131-135` | 进度条 `setVisibility` 直接切换；错误面板 `root.setVisibility(VISIBLE)` 直接覆盖 WebView，标题/详情/主次按钮整组替换 | ④ | 进度条淡入淡出；错误面板用 180ms fade+上移（`WebCrashPanel` 已有 chevron 动画可复用曲线）；面板内容分块淡入 | P1 | 2 文件，约 40 行；**与 T111 同文件，建议排 T111 之后** |
| F-02 | `DiagnosticActivity.java:172-212` | 自检结论的 headline/status/icon/色块整组切换；结果从「正在检查」到「全部通过/需要处理」是瞬时替换 | ④ | headline/status 交叉淡入；icon/色块 150ms 过渡；结果列表用 `staggerIn`（`DiagnosticResultList` 已支持折叠） | P1 | 1 文件，约 30 行 |
| F-03 | `DiagnosticActivity.java:243-252` | 日志导出状态行 `setVisibility` + 文本直接更新，分隔线同步显隐 | ④ | 状态行淡入/高度过渡 | P2 | 1 文件，约 10 行 |
| F-04 | `TerminalActivity.java:1345, 1363-1365` | 终端标题与警告状态行直接换文本；侧栏/抽屉已有动画，只有文字无过渡 | ④ | 标题/警告淡入；警告条出现用展开 | P2 | 1 文件，约 15 行 |
| F-05 | `RecoveryActivity.java:41-48` | 应急 DSH 首屏 63 字说明常驻 | ②③ | 首屏只留 1 行结论 + 「为什么需要应急」`InfoHint`/`CardSheet` | P1 | 1 文件，约 10 行 |
| F-06 | `EnvironmentRecoveryFragment.java:14-20` | 环境恢复全屏页副标题 50 字常驻 | ②③ | 压到 1 行；详情挂 ⓘ；与 F-05 同批 | P1 | 1 文件，约 5 行 |
| F-07 | `SkillsFragment.java:1409-1450` | 结果/错误条 `statusRow` 直接 `VISIBLE`，错误文案 30–50 字（成功短、失败长） | ④（边界） | 结果条 fade/expand；长错误正文放 `CardSheet`，条上只留一行摘要 | P2 | 1 文件，约 20 行 |
| F-08 | `PluginInstallActivity.java:101-110` | 「正在安装/安装完成/解析链接 / 重试」按钮标签直接切换；进程重建后的 31 字状态直接显示 | ④（边界） | 按钮标签切换淡入；重建提示用 notice 条淡入 | P2 | 1 文件，约 10 行 |
| F-09 | `RecoveryWebActivity.java:106-116` | 44 字「未取得模型密钥」提示直接加入页面顶部 | ③④（边界） | 用 notice 条（expand+fade）+ 1 行摘要 | P2 | 1 文件，约 10 行 |

## 9. 按钮注释的边界与剩余 finding

| 编号 | 位置 | 现状（证据） | 违反 | 建议修法 | 级别 | 改动面 |
|---|---|---|---|---|---:|---|
| G-01 | `plugin_list_header.xml:24-27`、`PluginFragment.java:262-263` | 恢复按钮的 XML 默认文本是 17 字注释句「安全模式：恢复此前启用的第三方插件」；运行时被改成「恢复」，屏幕阅读器描述仍是完整注解。默认值只在绑定前短暂存在，但属于错误回退文案 | ①（边界） | XML `android:text` 改「恢复」（与运行时一致）；`contentDescription` 保留完整说明即可 | P2 | 2 文件，各 1–2 行 |

以下形态**不计入**基准 ①，理由是它们是导航/状态说明而非动作按钮；如果用户要扩大口径，再单独排期：

- `SettingsFragment.java:31-39`：6 个设置入口行的副标题（「安装与修复运行环境」「接口、显示与运行」等），导航行。
- `CardPage.entry(...)`：数据页/作品页的「标题 + 副标题 + 箭头」入口，约 60+ 处；`CardPage.java:121` 的副标题参数就是导航说明。
- `DeviceGrantsFragment` 各行 `hint`：开关/通道的状态与风险说明，不是按钮标签。
- `plugin_list_header.xml:24-27` 的 `contentDescription`：读屏描述允许解释性文本；问题只在 XML 可见回退文案（已按 P2 记入 ④-8）。
- `StartupRecoveryLayout` 的「不可用：原因」：禁用状态的原因，保留；只有 `effect`（可用时的功能说明）计入 ①。

## 10. 系统性根因与统一修法

### S1. 文字/状态切换没有统一过渡（最大类）

- 事实：915 次 `setText` / 73 文件；68 个动态文件里 42 个无任何动画设施。
- 建议：在 `UiMotion` 增加两个公共方法，优先覆盖 12 个 P1 点：
  - `crossFadeText(TextView, CharSequence)`：文本不变直接返回；动画关闭时直接 set；否则 90ms 淡出 + set + 150ms 淡入（或交换两个 TextView 做真交叉淡化）。
  - `animateCounter(TextView, int/float)`：给计时/计数（`InstallFragment` 秒表、`DeviceGrants` pill、`BackgroundTasks` 计数）。
- 改动面：`UiMotion.java` 约 40 行 + 12 个调用点；无新资源、无翻译键。
- 验证：系统「动画时长 0」时直接落到静态值（现有 `animationsEnabled` 已覆盖）。

### S2. `removeAllViews` 整块重建（29 文件 / 51 处）

- 事实：`ExtractActivity`、`StartupRecoveryActivity`、`NativeDataActivity`、`RetainedDataActivity`、`StorageActivity`、`InstallFragment` 等在状态刷新时全量重建；滚动位置、焦点和输入都可能被重置（`NativeDataActivity` 已为密码步做例外）。
- 建议：优先「保留视图 + 更新字段」；必须替换时，用 `UiMotion.staggerIn` 或 120ms fade；列表用 diff/复用。`BackgroundTasksActivity.java:190-216` 已有 diff 写法，可抽成公共小工具。
- 改动面：按 finding 分批，先做 B-01/B-04/E-01/E-08 四个全屏/主流程页。

### S3. 默认可见长文本未用 `InfoHint`（设施已存在）

- 事实：`InfoHint` 只在 7 个文件使用；但 `ConfigFragment` 96 字、`GitHubLogin` 45 字、`AutomaticBackup` 59 字、`AccessibilitySetup` 89 字、`SafAccess` 86 字等仍直接铺在页面上。
- 建议：对 §2.3 的 12 个 P1 点逐一 `InfoHint.bind(...)`（`InfoHint` 会自动拆成摘要 + ⓘ，并复用 `InfoSurface` 的弹层；不新增翻译键）。
- 改动面：12 个文件、每处 3–10 行，风险低，可作为第一批。

### S4. 动作行带效果说明（基准 ①）

- 事实：`StartupRecoveryLayout.action()` 的 `sub` 参数既承担「不可用原因」又承担「可用时的效果」。
- 建议：`sub` 只保留 `blocked` 时的「不可用：原因」；`effect` 移到 `InfoHint`（行标题旁 ⓘ）或点开后的 `CardSheet`。`Act` 数据模型不变，只改渲染与交互。
- 改动面：2 文件、约 40 行；与 B-04 同批做最划算。

### 建议排期（不与其它会话冲突）

1. **第一批（低风险、高覆盖）**：S3 的 12 个 P1 长文本点（C-01/C-04/C-05/C-06/D-02/D-04/D-07 等）+ S1 的 `UiMotion` 帮助方法。
2. **第二批（主流程状态）**：A-01/A-02（启动页）、B-01/B-02（维护/安装）、F-02（诊断）。
3. **第三批（整块重建）**：B-04/B-05（恢复页）、E-01/E-02（恢复向导）、E-08（传输页）。
4. **F-01 WebPreview**：等 T111（同文件）合并后再做，避免冲突。
5. 每批都跑 `LayoutAuditInstrumentation` 的中英/日夜/短屏/1.3 倍字体验收；本报告没有跑。

## 11. 已符合基准、可复用的样例

- 页面/元素转场：`UiMotion.page/tab`（14 文件）、`OnboardingMotion`（入场/步骤标记）、`BootScreen`（开场/退场）、`ThemeReveal`（主题切换遮罩）。
- 进度：`ShellActivityTrack`（波浪→压平）、`PluginTaskProgress`（呼吸/流光/240ms 填充）、`UpdateProgressButton`（下载填充动画）。
- 文字/说明：`InfoHint`（摘要 + ⓘ 弹层）、`InfoSurface`（锚定气泡/底部面板/居中弹窗，内容 fade-in 160ms）、`CardSheet`（20 文件）。
- 折叠：`StorageHelpActivity`（默认展开第一组）、`DiagnosticResultList`（正常项折叠）、`LaunchLogPanel`（阶段折叠 + 展开日志）、`PermissionCard`（展开后才显示用途）、`SkillsFragment`（「展开全文」）。
- 增量更新：`BackgroundTasksActivity` 的 diff 更新；`LaunchLogPanel` 的问题行上浮；`PluginFragment` 的 `UiMotion.staggerIn` 首屏列表。
- 这些设施足以覆盖本报告 80% 的 P1，不需要引入新库或新组件体系。

## 12. 未覆盖范围与验证限制

**未做的验证**

- 未在真机/模拟器运行，未截图比对；所有「默认可见」判断来自代码与布局，不是实测。短屏、横屏、1.3 倍字体、RTL/阿语下的实际折行未验证。
- 未跑 `LayoutAuditInstrumentation`（中英/日夜/短屏/大字体）与任何 UI 单测；未验证 TalkBack 朗读顺序、焦点与 `contentDescription` 实际效果。
- 未测动画时长/掉帧/内存；未验证系统「关闭动画」时所有新建议都能静态降级（现有 `UiMotion.animationsEnabled` 已按此设计，未实跑）。
- 未审计其它 9 种语言的译文长度（属于 `docs/i18n-length-policy.md` 范围）；未审计 Web 端 UI（`app/src/main/assets`、内置 `dsh-web-mobile` 插件）与插件自带界面。
- 未审计 `vscreen/` 包内的虚拟屏预览/控制 UI、`recovery/` 包、`tools/`、以及正在被其它会话修改的插件目录。

**未逐行读的类（机械扫描 + 抽样，B 级）**

`TerminalActivity`（仅读动画/状态相关段）、`PtyTerminalFragment`、`TerminalFragment`、`TerminalSessionViews`、`TerminalPane`/`TerminalTabBar`/`TerminalFloatingMenu`/`TerminalActionsDialog`/`TerminalOutputDialog`/`ExtraKeysDialog`、`WebPreviewActivity` 的 WebView 管线部分（除状态/错误面板外）、`WebDownloads`/`WebUploads`/`WebBlobDownload`/`WebFileActions`/`WebFullscreenUi`/`WebFrameRate`/`PreviewAuth`、`SafBrowseFragment`、`CommunityActivity`、`ColdSetupSpeedActivity`、`BrowserOriginRestore*`、`RecoveryBrowserSurface`/`RecoveryWebSurface`、`PictureInPictureFrame`、`TransferStepsView`/`TransferUi`/`TransferText`/`TransferDetailView`、`ScreenAppPicker`、`NetworkHealthSheet`、`PluginFilePicker`/`PluginFlowRow`/`PluginListPresenter`/`PluginNavigation`、`DshaSelectView`/`CardPage`（部分）/`DshaDialogBuilder`（部分）。

这些类的机械扫描结果（长文本 ≥30 码点、`setText` 密度、`removeAllViews`）已纳入汇总，但没有逐条给出 finding；如需要，可在第二批整改时逐类补审。

**方法声明**

- 只读命令：`grep`/`sed`/`python3` 统计与源码阅读；未写仓库文件（本报告除外），未启动构建，未操作设备。
- 计数可复算：`ui/*.java` 中含中文字面量 2,986 条、≥30 码点 278 条（脚本按 Java 字符串字面量去转义后计码点）；`setText` 915、`removeAllViews` 51 等为 `grep -r` 计数（同一行多次出现只计 1 次）。

## 13. English summary

This is a read-only, static audit of the Android native UI against the 2026-11-07 UI baseline (buttons carry actions only; no long prose on top-level pages; secondary pages should fold or use dialogs; state/data changes must animate or transition instead of replacing blocks of text).

Scope: 143 `ui/*.java` files, 44 layout XMLs, `values*/ui_strings.xml`, and Chinese UI literals (2,986 strings containing Chinese; 278 are ≥30 code points). 51 findings (22 P1, 29 P2, 0 P0) were confirmed line-by-line; the remaining ~91 classes were only mechanically scanned and are listed as not fully covered.

The largest gaps are abrupt text/visibility/list replacement (30 sites; 42 of 68 dynamically updated files use no animation at all) and long default-visible prose on secondary pages that should use the existing `InfoHint`/`CardSheet`/fold facilities. The only clear violation of the “action-only button” rule is the startup-recovery action rows, which still show a one-line effect description.

Existing facilities (`UiMotion`, `InfoHint`, `InfoSurface`, `CardSheet`, `LaunchLogPanel`, `DiagnosticResultList`, `StorageHelpActivity`, `PluginTaskProgress`, `ShellActivityTrack`) are sufficient to fix most P1 items without new dependencies. No device, screenshot, instrumentation, or TalkBack verification was performed; translations and Web/plugin UI are out of scope. The audit did not modify product code, did not run a build, and did not push or merge.

## 附录 A. 覆盖与证据清单

- **逐行深读（A 级）**：MainActivity、LaunchFragment、PluginFragment、SettingsFragment、ConfigFragment、DeviceGrantsFragment、InstallFragment、ExtractActivity、StartupRecoveryActivity、StartupRecoveryLayout、OnboardingActivity、OnboardingKit、PermissionPanel/PermissionCard/PermissionChannelCard/FullPermissionControls、NativeDataActivity、RetainedDataActivity、AutomaticBackupActivity、StorageActivity、StorageHelpActivity、WorkspaceFragment、FileTransfersActivity、SkillsFragment、WebPreviewActivity、WebCrashPanel、DiagnosticActivity、DiagnosticResultList、AdbPairActivity、AccessibilitySetupActivity、GitHubLoginFragment、IslandFragment、OverlayFragment、RecoveryActivity、RecoveryWebActivity、UpdateActivity/UpdateProgressButton、ThemeActivity、EnvironmentRecoveryFragment、SafAccessFragment、AboutDialog、BootScreen、UiMotion/InfoHint/InfoSurface/CardPage、DeployCapsule/DeployCard/DeployTaskSheet、LaunchLogPanel、PluginTaskProgress、ShellActivityTrack、TerminalActivity（部分）。
- **机械扫描（B 级）**：其余 `ui/*.java`（逐文件统计长文本、`setText`、`setVisibility`、`removeAllViews`、动画关键词），以及全部 44 个布局的按钮/文本节点抽取。
- **布局覆盖**：`activity_main`、`fragment_launch`、`fragment_plugins`、`fragment_settings`、`fragment_config`、`fragment_device_grants`、`fragment_install`、`activity_extract`、`activity_adb_pair`、`dialog_about`、`plugin_list_header` 等已读；`layout-land`（虚拟屏）、`layout-sw600dp`（主界面/诊断）、`layout-w840dp`（更新）未逐一核对断点差异。
- **行号基准**：`origin/main` `5474032f`（本工作树 `docs/ui-audit` 与 main 同基线，无产品代码改动）。
