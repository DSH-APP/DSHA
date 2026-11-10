# DSHA 终端界面改造：可行性与整合报告

日期：2026-10-25（Asia/Shanghai）  
审查分支：`Sunset-zhongdua-G`  
源码基线：`02f80abeee37f3b107544714c8544ada5a1c40b9`（build197）  
性质：**源码调研 + 设计提案 + 离线交互原型；不是已完成的 Android 功能改造。**

## 2026-10-25 第二轮更新：Termux 官方源码与三个主 Tab

**本节为当前交付状态；下文第 0–9 节保留第一轮终端架构评估。** 当前分支/生产 HEAD 未变，Android 业务代码、依赖、运行资产均未修改；现有 HTML 已原位更新，而不是另建互不一致的预览。

### 本轮完成

- [交互预览](<preview.html>)：补齐 **启动 / 插件 / 设置** 的真实功能层级与页面内模拟交互；保留原来的全屏终端、悬浮球和多会话。适配手机单列、平板双列，主壳改用系统字体，路径/日志/终端保留等宽字体。
- [三个主 Tab 源码映射](<main-tabs-source-map.md>)：逐项核对 Java/XML 的最终事件接线，区分现有功能、模拟行为和新增设计。
- [Termux 源码评审](<termux-source-review.md>)及[来源清单](<termux-source-manifest.json>)：匿名拉取成功，固定 commit `8629e632fcb95da272221be327db653fb24befe9`；25 个证据文件摘要，346 个 tracked 文件完整索引。不是 DSHA 所用 0.118.0 的同版本依赖，本轮不升级依赖。
- [Termux 原始源码参考包](<<workspace>/termux-ui-reference-8629e632.zip>)：346 个未修改的上游 tracked 文件，保留原目录和许可，位于 DSHA 仓库外；1,185,634 bytes，SHA-256 `0d6f19b823c395546c78486c3e4eec1c65976e33d6ad309a01e338eed1d9d673`。这是供阅读的源码，不是 APK 或直接可用的 HTML 主题。
- [验证记录](<validation.md>)：新主页面 **133 条**检查 + 原终端/悬浮球 **133 条**回归，共 **266 条**；两套结果绑定同一个最终 HTML SHA。不是 Android 真机验收。

### 三个页面现在可以体验什么

| 主 Tab | 已填充内容 | 可操作的离线交互 |
|---|---|---|
| 启动 | 本机 Web 状态、端口/环境、电脑工作区、访问地址、日志、安全启动/恢复 | 准备→鉴权→就绪；取消停止、停止、重启；失败注入、未准备/维护门禁；普通重启退出安全模式；Web 停止不关闭终端 |
| 插件 | 插件市场 / 插件管理、链接与本地包入口、下载源；四个精确随包源码样例与虚构用户样例 | npm/GitHub/HTTPS 压缩包的简化文本识别；模拟添加；搜索、名称/启用排序、隐藏内置、详情、启停等待、随包禁用确认、用户样例删除确认与空态 |
| 设置 | 工作台 / 使用体验 / 支持；运行配置、数据备份、安装、授权、悬浮条、更新、诊断、关于 | 配置草稿保留与内存保存；端口 1–65535 且拒桥接保留端口；省电开关；十语+跟随系统选择摘要；子页返回；备份/恢复/授权等仅流程演示或说明 |

**事实边界：** 随包插件名称/版本来自源码，不是本机已装清单。语言只演示选择，不宣称整页翻译。一键备份固定为应用数据+应用内工作区，可选范围位于“恢复与更多”。没有下载/上传/剪贴板/文件读取/浏览器权限/设备调用/持久存储。

**有意的设计差异：** 首页终端概览与快捷入口、测试台、设置内终端显示快捷入口、跨根 Tab 保留草稿/筛选/滚动属于本次提案；不是当前 Android 全部已实现。测试台未准备/维护场景允许浏览静态根页以便评审，但本机执行入口受限；生产首次准备门禁更严格，实施不得照抄该放宽。运行配置保存按钮在原型中随内容滚动、在生产是固定底部；本轮保证可达，不声称像素级复刻。

### Termux 值得借鉴什么，不应直接搬什么

官方界面是 **Android 原生 XML/Java，不是 HTML 模板**。关键参考为 [主布局](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml#L19-L103>)、[输入工具条与焦点](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java#L44-L112>)及 [Activity 销毁解绑](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L325-L375>)。

| 借鉴 / 优化点 | 本轮处理 | 下一阶段 |
|---|---|---|
| 把大部分空间留给任务内容，低频设置按需展开 | 三个主 Tab 用稳定分组和独立子页；终端继续极简，无常驻主壳 | 真实 TerminalView 外壳重构时保留此原则 |
| 页面销毁与会话生死分离 | HTML 跨 Tab/子页/终端保留状态；Web 与终端模型分开 | 修复 Main.onDestroy 与终端关闭耦合、terminal-only FGS；仍为 P0 |
| 会话身份不依赖展示标题，返回与关闭分开 | 原有复合 ID、多会话、取消关闭与空态继续回归 | 真实 closing / exited / unconfirmed 投影，显式重命名 |
| 明确键盘输入、文本插入与 Enter 的区别；焦点是状态 | 本轮不重写终端，保留既有模拟边界；主壳弹层补 select 焦点环绕 | 原生 IME、候选栏、组合提交、粘贴与修饰键消费单独验证 |
| 未知/未连接不是成功 | 插件更新保持“未检查”；端口不伪造；备份不报已生成；授权不报已授予 | 接真实回执后才显示结果，不以 UI 动画授 ready |
| 不照搬机型/固定高度 workaround | 响应式按当前窗口，短屏可滚动；安全区保留 | 真机 IME/TUI/横竖屏验证，不复制固定延时补偿 |

许可特别注意：根 [LICENSE](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md>) 是 **GPL-3.0-only**；terminal-view/emulator 的 Apache-2.0 例外不覆盖 App。`termux-shared` 的 [Termux 子树](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L5-L9>)包括额外键，不能一概视为 MIT。本 HTML 独立编写，只借鉴交互原则；没有翻译或复制这些实现。

### 推荐体验顺序

1. 启动 → 模拟启动 → 看鉴权/日志 → 停止时取消一次，再确认。
2. 插件 → 插件管理 → 搜索/隐藏内置/展开详情 → 回市场填示例来源并模拟添加。
3. 设置 → 运行配置 → 改运行方式与端口 → 不保存先进入终端 → 悬浮球返回，检查草稿仍在 → 保存。
4. 终端输入 `demo start` → 返回启动 → 停止模拟 Web，检查终端计数仍继续。
5. 设置 → 数据与备份 / 设备能力授权，确认流程预览没有假装操作了真实设备。

**尚未落实的终端优化**：重命名、关闭异步失败/UNKNOWN、两种输入模式、长按重复与真实 IME 仍只在评估/后续指引中。本轮遵守“终端留待下一步改造”，未把报告建议统统冒称为已实现。

---

## 2026-10-26 第三轮更新：三向态侧栏、图标化与单行会话

### 为什么改

用户提供的原生终端截图里，侧栏是固定的深绿色，与应用现有“系统 / 白天 / 黑夜”三向态和浅蓝主色脱节；会话行同时显示 `PTY 1` 与 `pty-1 · 空闲`，信息重复；底部文字按钮没有图标和底色。本轮先在 HTML 原型上大改，供确认后再原生化，避免直接改 Java 后反复返工。

### 颜色合同

- **浅色 = 浅蓝 + 白**：页面底 `#EEF2F8`、面板白 `#FFFFFF`、主色 `#1E50D0`、选中底 `#E8EFFF`。
- **深色 = 浅蓝 + 黑**：页面底 `#05070B`、面板 `#0B0F16`、主色 `#8AB4FF`、选中底 `#13233C`。
- **终端输出区在两套外观下都固定黑底**（`#000`），与原生 `terminal_surface` 的既有合同一致；侧栏、顶栏、对话框、菜单和主壳随外观切换。
- 所有颜色集中在 `:root` 与 `:root[data-theme="dark"]` 的 CSS 变量；不再在组件里硬编码绿色。旧绿色 token `#b2dcb9 / #17271c / #44604c` 已从原型移除。

### 侧栏信息结构

| 区域 | 新结构 |
|---|---|
| 头部 | `>_` 身份块 + “会话” + “独立上下文 · 返回不结束进程” + 图标关闭 |
| 新建 | 主色填充按钮，`+` 图标，右侧 `PTY / 简易` 提示 |
| 分组 | PTY / simple 各带类型图标、标题、计数胶囊；空态为虚线框 |
| 会话行 | **单行**：类型图标 + `PTY 1` + 状态胶囊（空闲 / 运行中 N / 已暂停 N）；ID 只保留在无障碍名与长按提示，不再重复显示 |
| 底部 | 会话摘要 + “系统 / 白天 / 黑夜”分段开关 + 2×2 填充动作（返回主界面 / 显示模式 / 模拟键盘 / 说明）+ 边界脚注 |

### 原生落地

- 颜色 token、三向态机制、图标清单、2×2 与现有 6 个动作的取舍、Robolectric/Lint/真机验收清单见 [native-terminal-theme-plan.md](<native-terminal-theme-plan.md>)。
- 原生底部动作**不删能力**：保留现有 6 个入口；HTML 只展示其中 4 个，是因为原型没有真实剪贴板/输入焦点能力。
- 底部“快捷”按键的自定义是独立原生改造，见同轮实现记录（手机/平板两套布局、可增删替换排序、保存后即时刷新）。

### 底部附加键（原生实现，CI 已通过）

- 手机默认 7+7（Termux 风格：Esc / 斜杠 / 连字符 / Home / 上 / End / PgUp；Tab / Ctrl / Alt / 左 / 下 / 右 / PgDn），平板沿用 8+8；两套布局分别保存在 `term_extra_keys_phone` / `term_extra_keys_tablet`。
- 显示设置新增“快捷按键布局”，或长按附加键区域进入编辑面板；支持增删、替换、左移/右移、恢复默认；每行最多 10 个，空行隐藏，两个布局各自至少保留一个键。
- 按键改为单行 + 8–13sp 自动缩字 + 固定 48dp 行高；窄屏/大字体不再折行，超出宽度时横向滚动。`CTRL/ALT` 仍为一次性修饰键，选中态有背景和描边。
- 保存只重建当前 PTY 视图，不新建/切换会话；简易模式无附加键。模型单测 19 项本地通过；[CI run 40282161255](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40282161255) 已通过（2545 项、0 失败/0 错误/1 允许跳过，Lint 成功）。真机渲染、触摸、IME 与 TalkBack 未验。
- 相关文件：[TerminalExtraKeys.java](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java>) · [ExtraKeysDialog.java](<../../../app/src/main/java/com/deepseekharness/app/ui/ExtraKeysDialog.java>) · [PtyTerminalFragment.java](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java>) · [bg_terminal_key.xml](<../../../app/src/main/res/drawable/bg_terminal_key.xml>)

### 验证

- [check-preview.cjs](<check-preview.cjs>) 在原有 133 条上新增 18 条三向态/图标化断言，最终 **151 条通过**；原型 SHA-256 `803b55a72e049a40781bf66e63250af0ec1dcc13d2e605a4065e9123792b5024`。
- 新增白天/黑夜抽屉、白天工作台、平板内联侧栏明暗截图；详见[验证记录](<validation.md>)第 9 节。
- 仍是离线 HTML 验证，不是 Android 主题、对比度或真机验收。

## 2026-10-26 第四轮更新：三向态侧栏已原生落地

第三轮 HTML 稿已按用户要求落到原生，不是继续改原型。实现 commit `1be3f6a`，格式清单修订 `fb95eea`；[CI run 40294484304](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40294484304) 成功：2550 项单测 / 0 失败 / 0 错误 / 1 允许跳过，Lint 0 error / 350 warning，已产出并下载核验测试 APK。

### 实际落地

- **颜色 token**：`values/colors.xml` + `values-night/colors.xml` 新增/重定义 20 个 `terminal_*` 外壳 token；浅色白/浅蓝、夜间近黑/浅蓝；`terminal_surface` 与 `terminal_foreground` 仍固定用于黑底输出区，终端内核、PTY/JNI、运行资产未改。
- **图标化**：11 个新矢量 + 复用图标；工具栏返回/设置/说明/菜单、侧栏关闭、会话重命名/结束、主题三段和六个底栏动作都有图标；纯图标按钮均有无障碍描述、48dp 触摸；不复制 Termux GPL 资源。
- **侧栏结构**：身份块 + 副标题 + 关闭；新建按钮带 `+` 与 `PTY / 简易`；分组类型图标 + 计数胶囊；空态虚线框；会话行改为单行（3dp 强调条 + 30dp 类型块 + 名称 + 短状态胶囊 + 重命名/结束），完整状态留在无障碍描述。
- **底栏**：三向态分段开关 + 2×2 主操作 + 粘贴/回到输入一行；短窗 3 列×2 行，六个动作全保留；结束全部为软危险底色，进行中禁用主题段。
- **系统栏/悬浮球**：极简黑底浅色图标、工作台浅色深色图标、工作台夜间浅色图标；导航栏始终黑底；悬浮球改为面板色 + 强描边 + 主题涟漪 + 外壳文字色。
- **回归**：新增 [TerminalThemeTest.java](<../../../app/src/test/java/com/deepseekharness/app/ui/TerminalThemeTest.java>) 5 项，覆盖日夜 token、黑底输出、宽/短窗六动作、主题段选中/禁用、图标描述与矢量；`TerminalActivityLifecycleTest` 14 项、`TerminalFloatingMenuTest` 4 项、`TerminalInputContractTest` 9 项均通过。

### 与设计稿的有意差异

- 原生保留六个底部动作；HTML 只画四个是原型没有真实剪贴板/焦点能力。
- 原生仍叫「显示设置」而非「显示模式」，因为该入口还管理字号、极简/工作台、球位置和附加键布局。
- 摘要使用真实会话数；没有采用「仅本页内存、刷新即丢失」的模拟文案。状态胶囊用短词，完整状态保留在无障碍描述与确认框。
- 抽屉宽度仍按既有 336dp 上限策略，不照搬 HTML 的 344px。

### 边界

- 未安装、未操作手机；真机配色、切主题重建、状态栏、IME、TalkBack、RTL、1.3 倍字体与 OEM ROM 仍未验收。
- `LayoutAuditInstrumentation` 终端 scene 扩展、十语种补全、对话框内重复主题开关、差量渲染与图标缓存未在本批实现。
- 详见[本批发布报告](../../releases/v0.2.1-rc.3-build198-native-terminal-theme-20261026.md)、[验证记录](<validation.md>)第 10 节与[原生化方案](<native-terminal-theme-plan.md>)。

### 横屏压缩后续（同日）

用户提供平板竖屏/横屏截图后确认：横屏内联侧栏的底栏约占高度一半，摘要被挤成两行。后续 commit `8d7e4b6` 新增 `shortLandscape(widthDp,heightDp)`（宽度 > 高度且高度 < 1000dp），横屏底栏改三列两行、44dp/10.5sp、短标签+完整无障碍名、单行摘要，隐藏副标题/脚注并收紧主题与头部；竖屏和超高窗口恢复两列三行。CI [run 40306054023](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40306054023) 成功：2551 项单测 / 0 失败 / 0 错误 / 1 允许跳过，Lint 0 error / 350 warning；新增横屏 Robolectric 用例。仍未安装到平板再次截图。详见[横屏压缩报告](../../releases/v0.2.1-rc.3-build198-terminal-landscape-20261026.md)与[验证记录](<validation.md>)10.4。

## 0. 结论先行（第一轮架构评估）

1. **可行，而且不需要更换终端内核。** 当前真 PTY 页面已经使用 Termux 的 `terminal-view:0.118.0` 和 `TerminalView`。最合理的“完全换 UI”是替换周边工具条、标签布局、背景与页面导航，保留终端视图、终端模拟器、PTY/JNI、运行环境及退出核验链。
2. **一级“终端”入口直接打开全屏工作台可行。** 推荐同一 Android task、同一应用进程的非导出 `TerminalActivity`，内嵌现有终端 Fragment 的重构版本；不是新的浏览器、独立最近任务卡片或独立进程。返回仅 `finish()` 这个宿主页，不终止会话。
3. **现状尚不能直接宣称“任何退出页面都不断终端”。** Fragment 已经解绑而不关闭会话，但 `MainActivity.onDestroy()` 在非配置重建且非重复入口的路径仍调用两类终端 shutdown。必须移走这一 UI 销毁副作用，并建立明确的会话关闭责任及终端独立运行保护。
4. **“后台运行”不是“永远存活”。** 进程内单例持有与 CPU 锁不等于系统保证；现有前台服务主要围绕 Web 生命周期。终端独立运行时的可见启动准入、常驻通知和停止边界需要单独设计。强行停止、重启、系统回收后的原 PTY 恢复不属于 UI-only 范围。
5. **HTML 只用来讨论布局和交互。** [交互预览](<preview.html>) 不连接桥、终端、设备、文件或网络；不得直接将它嵌入 WebView 当生产终端替代品。具体落地见[实施指引](<implementation-guide.md>)。

**建议决策：保留 Termux 原生 TerminalView + 重做终端工作台外壳 + 同 task 全屏宿主；分阶段交付，而非先删除所有现有终端代码。**

---

## 1. 范围、事实与术语

### 1.1 本轮实际检查的是什么

工作区根目录不是 Git 仓库，实际仓库位于其子目录 `DSHA-Sunset-mod-3090-Q`。调研开始时该仓库工作区干净，当前分支和 HEAD 如上；未切分支、拉取远端、提交、推送、安装 APK 或操控实体手机。

“Top 页”按用户的一级主导航理解。**当前源码是底部导航，不是顶部 Tab**：启动 / 插件 / 设置 / 终端。结论来自当前提交的 Java/XML，而非对手机截图的推断。也不是正在承载本次对话的 DeepSeek Harness Web GUI 的终端功能。

当前版本身份由[构建配置](<../../../app/build.gradle#L60-L62>)确认。历史发布说明中的测试数量、设备结果不作为本轮验收证据。

### 1.2 “内核不变”的可执行定义

| 层次 | 本方案处理 | 原因 |
|---|---|---|
| 主导航、页面宿主、工具栏、会话选择器、附加键 | 重设计 | 属于用户可见外壳 |
| TerminalView 的布局、字号、颜色配置、无障碍接入 | 保留组件并调整配置 | 保留成熟的输入、选择、ANSI/TUI 绘制 |
| Fragment 绑定逻辑、生命周期职责、通知呈现 | 必要重构 | 不改 shell/PTY，但不是只改 XML 的“换皮” |
| PTY 创建、JNI 出生身份握手、终端模拟器、行列 resize | 保留合同与实现 | 保护 vim、tmux、htop 等行为与退出安全 |
| proot/proroot、Ubuntu、DSH 包、受管运行资产与用户数据 | 不更换、不重建 | UI-only APK 不应触发环境迁移 |
| 简易 shell 与 PTY 的差异 | 保留并明确标识 | 简易“中止”实际会重启 shell，不等于 PTY Ctrl+C |

若“删除现有渲染 UI”还要求删除 `TerminalView` 本身，就不再是低风险的纯外壳改造：需要重做终端绘制、输入法、选择、控制序列与 TUI 兼容层。**本报告不推荐这一路线。**

---

## 2. 当前源码调用链与证据

### 2.1 主链

```text
MainActivity / BottomNavigationView
  └─ nav_terminal
      ├─ 本地环境未准备 → 原有准备/恢复路径
      └─ PTY 偏好 term_pty
          ├─ PtyTerminalFragment
          │   ├─ TerminalView（Termux）
          │   ├─ TerminalSessionOwner.shared().ptyTabs()
          │   └─ PtySession → Termux TerminalSession → JNI / proot/proroot
          └─ TerminalFragment（简易）
              └─ TerminalSessionOwner.shared().simpleTabs()
                  → util.TerminalSession → SimpleTerminalBackend
```

### 2.2 重点证据索引

以下行号绑定本报告 HEAD；改造后应重新核验，不把注释当执行结果。

| 编号 | 证据 | 当前事实及设计意义 |
|---|---|---|
| E01 | [底部菜单:5–21](<../../../app/src/main/res/menu/bottom_nav_menu.xml#L5-L21>) | 四个一级入口；“终端”是 `nav_terminal` |
| E02 | [MainActivity:350–413](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L350-L413>) | 环境门禁后在 `fragment_container` replace PTY 或简易 Fragment；当前没有独立终端全屏宿主 |
| E03 | [主布局:3–18](<../../../app/src/main/res/layout/activity_main.xml#L3-L18>) | 顶部 app_bar、恢复横幅、内容容器、底部导航共同占屏 |
| E04 | [PTY 布局:2–16](<../../../app/src/main/res/layout/fragment_pty_terminal.xml#L2-L16>) | 标题/字号/简易入口、横向标签、边框内 TerminalView、横滚扩展键；可替换的是外壳 |
| E05 | [构建依赖:454–457](<../../../app/build.gradle#L454-L457>)、[PTY 导入:24–40](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L24-L40>) | 已采用 Termux 原生组件，不是需要从零引入 Termux |
| E06 | [PTY 绑定:174–249](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L174-L249>)、[解绑:421–448](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L421-L448>) | 页面接回 owner 的当前会话；pause/destroyView 仅解绑、清 UI 回调，不主动关 shell |
| E07 | [会话 owner:10–51](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L10-L51>) | 进程级单例，PTY/simple 两组独立集合；每组当前仅一个 observer，不能直接支持多宿主同时渲染 |
| E08 | [关闭核验:106–166](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L106-L166>) | 核验实际退出才移除标签；失败保留，不允许 UI 先假删 |
| E09 | [标签身份:65–129](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java#L65-L129>) | 每组实例内 ID 递增，显示编号复用空缺；全部关完仍记得 initialized，不应重进自动再开 |
| E10 | [Main 销毁:956–969](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L956-L969>) | 非配置销毁仍 shutdown 两种终端，是本改造的关键生命周期缺口 |
| E11 | [Main Back:96–114](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L96-L114>)、[退出主界面:213–231](<../../../app/src/main/java/com/deepseekharness/app/ui/BrowserTasks.java#L213-L231>) | 根页 Back 当前把整个 task 放后台，不是返回上一一级页；新终端页需自己的返回语义 |
| E12 | [外部终端意图:625–640](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L625-L640>) | `open_terminal` 走底部选中；改造必须一起迁移，不能只改手指点击 |
| E13 | [PTY 创建:89–134](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L89-L134>)、[退出:168–203](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L168-L203>) | 创建先取工作保护，再 native 握手；退出须核验启动器和 guest，不可重写为裸 PID kill |
| E14 | [工作保护:61–80](<../../../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java#L61-L80>)、[CPU 锁:170–202](<../../../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java#L170-L202>) | 工作锁/CPU锁不构成 Android 进程不死承诺 |
| E15 | [前台服务:74–98](<../../../app/src/main/java/com/deepseekharness/app/HarnessService.java#L74-L98>)、[运行恢复:120–154](<../../../app/src/main/java/com/deepseekharness/app/HarnessService.java#L120-L154>) | 现有可见启动票据和恢复主要面向 Web，不能假设新建终端已经拥有独立 FGS 准入 |
| E16 | [清单 Main:87–97](<../../../app/src/main/AndroidManifest.xml#L87-L97>)、[Web 宿主:170–180](<../../../app/src/main/AndroidManifest.xml#L170-L180>) | Main 是 singleTop/adjustResize；Web 有自己的 taskAffinity/PiP，不应把该模式照搬给终端 |
| E17 | [简易终端:75–151](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java#L75-L151>) | 简易中止 `cancelAndRestart()`；草稿/光标在 owner 会话记录中维护 |
| E18 | [PTY 输入和复制粘贴:497–541](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L497-L541>)、[输入门禁:574–644](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L574-L644>) | 原生输入、脱敏复制、粘贴和状态检查需要保留；不能只迁移外观 |

### 2.3 现状已经具备的能力

- 多终端、PTY/简易独立标签、当前会话切换、关闭中禁用输入、失败保留。
- 页面离开后由 owner 持有；PTY 历史在 emulator 中，简易输出和草稿在 owner 中。
- 原生 TerminalView 的长按选字、软键盘、硬键盘、字号和 resize 接入。
- 当前 PTY 回滚缓冲为 2000 行；简易输出达到 300000 字符后裁到后 100000 字符，不能把全历史无限存储当现有能力。
- 中文输入策略、返回键不映射 ESC、回调绑定会话身份、输出合并重绘等行为；UI 重构必须做对等回归。

### 2.4 当前缺口与待验证事项

**确定的代码级缺口：** Main 销毁会触发终端 shutdown；页面 owner 没有跨进程持久化；FGS 保护未形成独立终端启动/退休链；owner observer 单槽，不适合同时开启两个可见终端宿主。

**关闭状态还缺一层持久展示：** 标签目前只有 `closing` 布尔值，关闭失败后复位并弹 Toast，未保存统一的关闭错误快照。PTY 标题按 `isRunning()` 显示“会话已结束”，但启动器已退而 guest 尚未核验为空时，底层仍可能持有工作保护。因此新界面要区分“进程已退出”与“清理已确认”，不能用一颗状态点或标签数量判断可安全维护/退休服务。证据见 [owner 关闭结果](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L106-L146>) 与 [PTY guest 退出核验](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L185-L200>)。

**需要实施时验证而非先断言的事项：** 各 IME 对组合输入/候选框/硬件 Ctrl/Alt 的实际行为；原生库对 bracketed paste、宽字符、缩放后重排的边界；不同 Android/OEM 的后台限制；真实滑动返回和多窗口恢复。源码注释不代替设备证据。

---

## 3. 方案一：Termux 风格 UI 替换的可能性

### 3.1 路线比较

| 路线 | 内核与输入风险 | 工作范围 | 评价 |
|---|---|---|---|
| A. 原生外壳重构 + 保留 TerminalView | 低至中：仍须测 IME、resize、绑定 | XML/原生 View、宿主导航、状态呈现 | **推荐**，最符合用户限定 |
| B. 全屏 Activity + 原生终端工作台 | 中：主要是任务栈和生命周期 | 在 A 上增加宿主与运行保护协调 | **推荐呈现方式**，A 与 B 可组合 |
| C. WebView + xterm.js 等替代原生绘制 | 高：桥、输入法、流控、编码、焦点、性能 | 新浏览器渲染与输入传输层 | 不是必要条件，本轮不推荐 |
| D. 直接复制整个 Termux 应用页面 | 高：服务、设置和生命周期耦合，许可另核 | 大量迁移和适配 | 不符合“内核不变”的最小风险目标 |

A/B 并非互斥选项：A 讲渲染外壳，B 讲页面承载方式。

### 3.2 视觉与操作提案（已根据用户追加实图修订）

用户追加的 Termux 横屏实图可直接观察到：系统状态栏和底部手势条仍可见；终端纯黑底、浅色等宽文本接近边缘；没有常驻应用标题栏、卡片或左侧会话栏；底部两排附加键。它是**视觉参考，不是 DSHA 当前界面或功能验收证据**。未将带系统状态信息的用户原图拷入仓库，也未从截图读取或执行命令。

据此将默认方案从“带工具栏的工作台”收敛为“极简终端”，同时保留工作台模式供比较：

- **默认极简模式：** 黑底浅色等宽文本，删除常驻应用顶栏、卡片和侧栏；系统栏保留。按后续讨论将小菜单改为**页面内悬浮球**：默认右侧，可拖动吸附左右边缘，闲置降低透明度，点击打开返回主界面、会话、新建和显示菜单。球只在终端极简页面出现，不是跨应用系统悬浮窗，不需要额外悬浮窗权限。原型另保留小字“DSHA · 仅模拟”防止把示例当真实 shell。
- **可选工作台模式：** 顶栏提供返回、会话标题和菜单；手机会话抽屉，平板宽度足够时常驻侧栏。不是默认让平板牺牲终端列数。
- 结束会话位于会话目录中并确认，不和页面返回共享“×”。切换视觉模式/宽度只更换布局，不创建或复制会话。
- 底部按实图两排布局：`ESC / − HOME ↑ END PGUP` 与 `TAB CTRL ALT ← ↓ → PGDN`。窄屏若不足以维持 48dp 触控目标，允许行区横滚；不压成难点的小键。生产发送仍须考虑终端模式。
- 少量状态强调不影响 emulator 的 ANSI 颜色；不把全部真实输出强行染绿。
- PTY 使用原生输入面，不新增会抢焦点的独立聊天式输入框。原型为模拟命令输入，会在说明中明确这一差别。
- 简易模式保留为兼容入口，与 PTY 一致外观但明确标注“非 PTY / 中止会重启 shell”。切模式不迁移进程，也不关闭另一组。
- 设置入口提供字号、配色和附加键选择；本期先不引入多面板、自动命令模板、远程 SSH 管理或文件管理器。

### 3.3 许可和品牌

只学习终端操作习惯与黑底等宽排版，不使用 Termux 品牌冒充官方应用，不搬其图标、整套应用代码。当前 `terminal-view`/`terminal-emulator` 的 Apache-2.0 例外不能扩展到整个 Termux 应用。参见[第三方声明:27–42](<../../../THIRD_PARTY_NOTICES.md#L27-L42>)；新增上游代码前应逐文件核对来源、许可和 NOTICE。

---

## 4. 方案二：一级入口打开全屏页面

### 4.1 推荐架构

新增 `TerminalActivity` 作为**普通、非导出、同进程、同 task** 宿主，默认无独立 affinity、无 `NEW_TASK/MULTIPLE_TASK/NEW_DOCUMENT`、无新 launcher、无默认 PiP；它承载一个终端工作台及 PTY/简易内容视图。

```text
Main 主导航（保留原选中项与原页面状态）
  └─ openTerminal()，统一门禁与在途去重
       └─ TerminalActivity / TerminalWorkspace
            ├─ 会话目录 / 工具条 / 附加键
            └─ PTY 或简易视图 ↔ 同一个 TerminalSessionOwner

返回主界面 → 只解绑观察、保存 UI 状态、finish 宿主
结束会话 → 按明确 ID/类型调用 owner → 核验真实退出 → 移除条目
```

底部“终端”从持久选中页改为**启动动作**：处理入口后返回 `false`，保留“启动/插件/设置”的当前选中。为避免对话框、恢复页面或异步门禁重复拉起，统一 `openTerminal()` 和 launch-in-flight 状态；恢复/拒绝时不偷偷新建终端。

`open_terminal` 通知/其他原生入口也必须走同一路径。将其消费为一次导航意图，不保留一个返回 Main 后会再次自动打开的 stale extra。

### 4.2 为什么不只隐藏 Main 顶底栏

同 Activity 的全屏 Fragment 也能做到，若追求最小文件数可选。但当前主布局是纵向 LinearLayout，除了 app_bar/bottom_nav 还有分隔线、环境恢复横幅和锚定底栏的 Snackbar；`updateToolbar()`、回退栈、环境检查恢复也会写壳层状态。必须完整抽出 `MainChromeController`，不能两行 `GONE` 了事。

| 项目 | 独立 TerminalActivity（推荐） | Main 内全屏 Fragment（备选） |
|---|---|---|
| 与主界面顶底栏隔离 | 清晰 | 需统一管理所有壳层可见性 |
| 返回原一级页和滚动 | Main 原实例可保持 | 需显式返回路径/状态栈 |
| IME/系统栏处理 | 终端宿主单独配置 | 容易和 Main 其他页竞争 |
| 旋转/语言切换 | 恢复宿主+绑定同 owner | 恢复 Fragment+壳层状态 |
| 最近任务行为 | 同 task 可保持一张卡片 | 天然同 task |
| 修改规模 | 多一个宿主和清单项 | 少一个 Activity，多一些主壳协调 |

两条路线都需要修复 Main.onDestroy 的终端关闭耦合，且都不能承诺进程死亡后接回原 PTY。

### 4.3 “全屏”分两层，避免误会

- **默认：应用内容全屏。** 不显示 DSHA 主 app_bar/底部导航/内容卡片；终端填满应用窗口，尊重状态栏、导航手势区、挖孔和 IME insets。
- **可选：沉浸模式。** 用户主动隐藏系统栏，边缘手势可临时唤回；不能锁死退出入口，不能假设网页 `requestFullscreen()` 等于 Android 原生沉浸体验。
- 在 Android 分屏/自由窗口中，“全屏”指占满当前窗口，不越过用户选择的窗口边界。

### 4.4 明确的返回优先级

1. 对话框/会话抽屉打开：先关闭临时层。
2. 原生文字选择态：先退出选择，不杀进程。
3. IME 显示：系统返回先收键盘，适配预测返回，不把返回重复转成 ESC。
4. 工作台页面：回到来处 Main；若从通知恢复且缺少来处，走唯一 Main 入口，不堆新 task。
5. Main 根页：沿用当前将 task 放后台的行为。

键盘 Esc 是终端输入，Android 返回是导航；菜单应有显式“返回主界面”。任何 UI 返回都不得注入 `exit`、Ctrl+D、HUP 或 `cancelAndRestart()`。

---

## 5. 生命周期：可以承诺什么，不可以承诺什么

| 用户动作/事件 | 本方案目标 | 边界 |
|---|---|---|
| 离开终端页、进入其他主页面 | 同会话继续；重进绑定原 ID、缓冲和草稿 | 不创建替代会话冒充恢复 |
| 旋转、主题或语言切换 | 视图重建、会话不变 | 不缓存旧语言文案、不重启 shell |
| 关闭抽屉/收键盘/返回 | 只改变 UI | 没有终止信号 |
| 按 Ctrl+C | 发给当前 PTY 前台程序 | 不等于关会话；简易模式是另外的“中止并重启 shell” |
| 点击结束单会话 | 展示影响并确认；核验退出后移除 | timeout/UNKNOWN 只显示未确认，保留 ID 与保护 |
| PTY shell 自然 exit | 标记进程退出，保留末屏、单独显示清理状态 | 不自动替换为新会话，避免误以为原程序仍在 |
| 简易 shell 自然 exit | 保留当前后端的队列合同：旧 shell 确认退出后，未发送队列或后续显式新输入可启动新 shell | 已发送命令绝不重放；不能将该行为悄悄改成 PTY 策略，见 [队列输入](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java#L127-L141>) 与 [自然退出](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java#L482-L491>) |
| 最后会话被关闭 | 留空状态 + 新建按钮 | 重开页面不自动新建 |
| 停止 Web 服务 | 保持其现有授权范围 | 不擅自扩展为关闭所有终端 |
| 显式“结束全部终端” | 逐会话核验，失败留条目 | 不等于清用户文件或停止所有容器进程 |
| 环境维护/更新 | 先走现有终端关闭确认和维护屏障 | 退出未知不得继续覆盖环境 |
| Home/息屏/切应用 | 会话应继续，使用可见通知与合理运行保护 | OEM/Doze/系统资源仍需实机测试，不保证无限运行 |
| 系统杀进程/强行停止/手机重启 | 清楚提示旧会话不可直接恢复 | 不自动回放命令，不以同显示编号伪装旧会话 |

### 前台服务与停止职责

新增或扩展独立的 `TerminalRuntimeProtection` 协作者，以会话状态/工作保护为依据，从可见用户动作申请所需运行保护；若复用 `HarnessService`，需要在它的准入、活跃判断、通知和退休条件中明确纳入终端，而不是把终端伪装为正在运行的 Web。

“最后一个页面解绑”不能释放进程运行保护；“最后一个已核验会话退出”才可释放对应租约。关闭失败或未知必须保留租约，也必须覆盖已经 fork 但尚未发布成标签的 PTY。通知点击回原会话；“停止 Web”与“结束终端”分开命名和授权。

当前 [Web 停止链](<../../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L1479-L1549>) 不调用终端 owner 全关；[维护链](<../../../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java#L79-L118>) 则经 [终端关闭确认](<../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L118-L132>) 后再停 Web、取得环境屏障。owner 的全部关闭遇异常会中止；如果新增“结束全部终端”，必须显示部分成功和未确认，不能笼统提示全部已关闭。

这部分是**宿主运行生命周期调整**，不是 PTY 内核替换；但也意味着不能把整个项目量级称作“只改几处样式”。需要产品先确认后台运行保障的范围。

---

## 6. 容易遗漏的设计与指令性要求

| 优先级 | 遗漏点 | 必须采取的动作 |
|---|---|---|
| P0 | 页面关闭和会话关闭混淆 | 用不同入口、不同图标和文案；只有结束操作调用 owner close |
| P0 | Main 非配置销毁仍全关 | 先确定显式停止归属，再移除 UI 销毁 shutdown；增加回归，不机械删除两行后交付 |
| P0 | 终端独立运行没有完整 FGS 保护 | 建立可见启动、通知、租约、退休和失败反馈；不要因 Web 已停就误退终端保护 |
| P0 | 环境未就绪/维护 | 入口和实际新建/发送均核验；保留受限恢复页，不以全屏绕门禁 |
| P0 | 关闭异步结果失配 | 命令绑定 `(类型, 会话ID)`，不能按显示编号或当前标签关闭；等待核验，不先删 |
| P0 | 进程死亡“恢复”假象 | 元数据与可用进程分开；没有真实实例就显示已丢失/不可恢复，不自动重发历史命令 |
| P1 | owner 每组只有一个 observer | 单一可见终端宿主；去重连点/通知；首版不支持双窗口同组并排 |
| P1 | 简易与 PTY ID 可能相同 | 两组各自递增，统一目录使用复合键；不要误称跨进程持久全局 ID |
| P1 | IME 候选、组合输入与硬键盘 | 覆盖中文拼音、日文、Emoji、Ctrl/Alt、组合键释放、粘贴与 Enter 不重复 |
| P1 | 附加键状态 | 明确一次性/锁定态，切会话、失焦、切模式时清理；验证修饰键作用于实际输入而非只变色 |
| P1 | 方向键和 bracketed paste | 使用既有库能力/模式感知，不把所有终端模式硬编码成一种序列；多行粘贴有预览和执行风险提醒 |
| P1 | 文本复制/终端标题 | 保留脱敏边界；显示标题不作为身份；不得把终端输出当 HTML/URL 指令执行 |
| P1 | 自动滚动 | 用户回看历史时不要强拉到底；新增输出提示“回到底部”；不无限保存大日志 |
| P1 | 平板/折叠/分屏 | 按当前窗口 dp 宽度，不按机型标签；侧栏出现不复制会话；窗口变小自动收起 |
| P1 | 无障碍和国际化 | 48dp 触控、TalkBack 明确标签、非颜色状态、1.3/2倍字体；十语目录，RTL 壳与 LTR 终端分离 |
| P1 | 导航、恢复和最近任务 | 测 Main→终端→返回、通知连点、旋转重建、后台重进；一张主最近任务卡片 |
| P1 | 不可见页仍重绘 | 解绑 UI observer、清 Handler/动画；保留 backend 接收，不销毁会话来“省资源” |
| P2 | 搜索、重命名、配色、附加键定制 | 后续独立小版本；重命名只改展示，不改进程身份 |
| P2 | 分屏终端、多 pane、SSH 管理 | 暂不混入本期；需多 observer/焦点与 resize 权威设计 |

**粘贴设计须单独评审：** 当前 PTY 回调把剪贴板字符串交给 `send()`。不能在未验证库行为前声称“已完整支持所有 bracketed paste”；多行文本、末尾换行、不可见控制字符都可能直接触发命令，应显式确认而非自动粘贴执行。

---

## 7. 交付范围、阶段与风险

### 7.1 推荐 MVP

- 全屏原生终端宿主；返回主界面不断会话；原来主页面状态保持。
- 一套黑底等宽终端：默认极简（手机和平板都按需展开会话）；可选工作台（手机抽屉、平板侧栏）；PTY/简易分组、两排附加键、字号与新建/结束确认。
- 会话状态、启动/关闭失败、无会话、环境未就绪呈现。
- 移除 UI 销毁的终端关闭职责，补明确停止和独立终端运行保护。
- 不替换 emulator/JNI、不升级 Termux 依赖、不改运行资产、不改用户数据格式。

### 7.2 工作量粗估（不是承诺工期）

在已有 Android 工程和可用标准版构建环境前提下：

| 阶段 | 参考人日 | 完成标准 |
|---|---:|---|
| 交互与合同冻结 | 1–2 | 返回/结束/停止/空态语义达成一致 |
| 原生外壳与导航 | 3–5 | 两类终端接入、手机平板布局、统一入口 |
| 生命周期与运行保护 | 2–4 | Main销毁解耦、独立终端FGS、失败保留 |
| 测试、国际化与缺陷修正 | 3–5 | 定向单测、Standard构建、真实IME/TUI/导航矩阵 |
| 合计 | 9–16 | 约2–3周单人有效开发量，ROM/设备问题可能延长 |

以上为设计拆分估算；未运行 Android 构建，不能当排期保证。仅换外观会更小，但不完整满足“退出不关终端”的产品承诺。

### 7.3 灰度与回滚

使用 UI 实验开关选择旧/新外壳；开关只改宿主，不改 PTY/backend；两种外壳不能同时占用同组观察者。记录脱敏导航和会话状态，不采集命令正文、密钥或完整输出。

验收稳定后再删除不再引用的旧布局/渲染辅助。**不要第一步就删旧页面类**：它们还承载生命周期绑定、输入门禁和维护入口。回滚应恢复旧外壳但保持运行资产与用户数据不变，不能靠重置环境“回滚 UI”。

---

## 8. HTML 预览的定位与验证边界

打开[离线交互预览](<preview.html>)：点击底部“终端”进入全视口极简终端；点击 `>_` 悬浮球打开菜单，可新建/切换会话、返回主界面、修改显示模式；拖动球后吸边，返回再进保留本页停靠位置。输入 `demo start` 启动模拟计数，返回后重进，输入 `jobs` 核对；选择“显示模式”并关闭“极简模式”可对比带顶栏/平板侧栏的工作台。命令/任务输出全部为有限本地演示，页面刷新会丢失模拟状态。

预览宽度自动适配：极简模式在手机和平板都按需打开目录；可选工作台模式中窄窗口用抽屉，宽窗口常驻侧栏。浏览器 CSS px 只作为视觉原型尺度，不等于 Android 物理像素或最终 dp。浏览器地址栏、系统 IME、原生 TerminalView 和 Android 生命周期不在 HTML 能证明的范围内。

原型使用独立命令输入控件只为演示交互；生产 PTY 仍应直接使用 TerminalView 的 InputConnection，不照搬为第二输入层。这里的“任务继续”证明的是同一 HTML 文档内模拟状态未被返回操作销毁，不证明 Android 后台常驻。

验证方法及实际结果另记于[验证记录](<validation.md>)。本轮不把源码阅读、HTML检查或历史测试结果写成新的 Android 真机验收。

---

## 9. 需要与你讨论的四个选择

1. **全屏的默认程度：** 推荐隐藏 DSHA 主壳，保留系统状态/导航栏；另给沉浸开关。你是否希望默认连系统栏一起隐藏？
2. **会话入口（已按后续讨论修订）：** 小入口改为页面内悬浮球，默认52dp左右触控区域、左右吸边、闲置淡出但不完全隐藏；可以继续讨论更小的视觉球体配48dp触控区域。可切换工作台启用平板侧栏；不引入跨应用悬浮权限。
3. **简易终端：** 推荐保留在会话目录或更多菜单内，明确“中止会重启 shell”；是否愿意将其降为兼容入口而非与 PTY 同级？
4. **后台承诺：** 推荐本期做到页面退出/旋转/普通切后台不断，配持续运行通知；系统强停/重启后不自动恢复。若要更强会话恢复，应单独立项，不把它隐含进 UI 改造。

在这些选择确认之前，本轮停在报告和原型层，不修改现有 Android 业务实现。
