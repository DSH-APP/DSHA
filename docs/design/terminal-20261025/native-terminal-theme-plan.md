# 原生终端侧栏三向态与图标化落地方案

**状态：2026-10-26 已原生落地并产出 CI 测试包。** 实现 commit `1be3f6ab91f25e50ae844cf1a1ca01e775d20df3`，格式清单修订 `fb95eeafd18cbbb2ade15887fc796295970309eb`；[CI run 40294484304](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40294484304) 两个 job 成功，2550 项单测 / 0 失败 / 0 错误 / 1 允许跳过，Lint 0 error / 350 warning。落地范围、与设计的差异和未验证项见[本批报告](../../releases/v0.2.1-rc.3-build198-native-terminal-theme-20261026.md)。随后根据平板截图完成横屏压缩：commit `8d7e4b6`、[run 40306054023](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40306054023)、2551 项单测全绿，见[横屏压缩报告](../../releases/v0.2.1-rc.3-build198-terminal-landscape-20261026.md)。本文其余部分保留为实施前审计记录；第 8 节的设备验收清单仍未执行，不能把本文件或 APK 当作真机验收。

**下文是落地前的审计与方案原文。** 审计基线：`Sunset-zhongdua-G`，文档提交 `711eb1c`，终端源码末次修订 `f8117b6`（build198 平板侧栏）。视觉合同来自本轮 [HTML 交互预览](<preview.html>) 的三向态修订：浅色 = 浅蓝 + 白，深色 = 浅蓝 + 黑，终端输出区在两套外观下都保持黑底；预览底部的 2×2 动作网格、三向态开关和图标表也以该文件为准。

> 阅读顺序：先看第 1 节结论；实施者重点看第 4 节（token）、第 5 节（图标）、第 6 节（底栏）和第 9 节（Lead 统一修改范围）；验收者重点看第 8 节。

---

## 1. 结论（最重要的五条）

1. **绿色不是主题问题，是终端外壳被单独写死成深绿。** `values/colors.xml` 第 26–35 行把 `terminal_panel/line/muted/accent/accent_bg/on_accent/warning/danger/ripple` 全部定义为绿色/暖色深色值，`values-night/colors.xml` 完全没有终端 token 覆盖，所以白天/黑夜/跟随系统三种模式下终端外壳都是同一套深绿。输出区 `terminal_surface` 固定 `#000000` 是独立合同，和外壳主题无关，必须保留。
2. **不能直接重定义 `terminal_foreground` 来兼容浅色外壳。** 这个白色 token 同时被黑底输出区（`fragment_terminal.xml`、`fragment_pty_terminal.xml`、PTY 附加键）和外壳文字（工具栏、分组标题、会话名）使用；外壳改成浅色后必须拆出 `terminal_panel_text`，否则会出现白底白字。第 4 节给出拆分后的完整 token 表。
3. **`getColor()` 每次调用都按当前 `uiMode` 取色，但已经 `setTextColor/setBackgroundColor/setBackgroundResource` 到视图上的颜色不会自动刷新。** 终端外壳在 `onCreate()` 一次构建，切主题必须让 Activity 重建；现有 AppCompat 机制会重建，`TerminalSessionOwner.shared()` 和 `onSaveInstanceState()` 能保住会话、侧栏展开状态和悬浮球位置。但“结束全部”进行中的瞬态 UI 会在重建中丢失，终端内新增主题开关时必须显式禁用/保护。
4. **图标化不需要新增布局 XML。** `TerminalActivity` 的外壳完全由代码构建，现有 glyph `× / ＋ / ✎ / ✕ / ⟳ / >_` 都可以用 24dp vector + 运行时 tint + 48dp 触摸容器原地替换；保留 `terminal_sessions_actions`、`terminal_sessions_actions_dense` 等既有容器 ID 和可见文案，新增的按行动作 ID 是追加，不破坏测试。图标路径采用本仓库 `preview.html` 自绘 symbol 或本次新绘路径，**不复制 GPL Termux App/extra-keys 资源**。
5. **底部动作 2×2 和现有 6 个动作存在冲突，需要 Lead 拍板。** 预览的 2×2 是 4 个动作（返回主界面 / 显示模式 / 显示键盘 / 说明）；原生现有 6 个（另有 粘贴文本、回到输入）。本文默认“2×2 主动作 + 2 个输入辅助动作”全保留，短窗再按 `denseFooter` 退回 3 列×2 行；如果产品要严格 4 个，需单独确认移除两个输入辅助入口，不能静默删除。

---

## 2. 现状盘点：终端外壳的绿色与文字 glyph

### 2.1 颜色 token 现状

当前值全部在浅色资源里，夜间没有覆盖：

| Token | 当前值 | 语义 | 位置 |
|---|---|---|---|
| `terminal_surface` | `#000000` | 输出区/终端画布固定黑底 | [colors.xml:22](<../../../app/src/main/res/values/colors.xml#L22>) |
| `terminal_foreground` | `#E4E8F0` | 黑底上的前景文字 | [colors.xml:23](<../../../app/src/main/res/values/colors.xml#L23>) |
| `terminal_outline` | `#263041` | 悬浮球填充/描边（现仅 `bg_terminal`、`bg_terminal_menu` 使用） | [colors.xml:24](<../../../app/src/main/res/values/colors.xml#L24>) |
| `terminal_scrim` | `#99000000` | 抽屉遮罩 | [colors.xml:25](<../../../app/src/main/res/values/colors.xml#L25>) |
| `terminal_panel` | `#0C100E` | 工具栏、会话侧栏、抽屉面板底 | [colors.xml:27](<../../../app/src/main/res/values/colors.xml#L27>) |
| `terminal_line` | `#28332C` | 分隔线/描边 | [colors.xml:28](<../../../app/src/main/res/values/colors.xml#L28>) |
| `terminal_muted` | `#99A99D` | 次级文字 | [colors.xml:29](<../../../app/src/main/res/values/colors.xml#L29>) |
| `terminal_accent` | `#B2DCB9` | 运行点、当前会话条、当前会话名 | [colors.xml:30](<../../../app/src/main/res/values/colors.xml#L30>) |
| `terminal_accent_bg` | `#17271C` | 当前会话行底色 | [colors.xml:31](<../../../app/src/main/res/values/colors.xml#L31>) |
| `terminal_on_accent` | `#101A13` | 新建会话按钮文字 | [colors.xml:32](<../../../app/src/main/res/values/colors.xml#L32>) |
| `terminal_warning` | `#DFC58D` | 侧栏警示文案 | [colors.xml:33](<../../../app/src/main/res/values/colors.xml#L33>) |
| `terminal_danger` | `#EDAAA6` | 结束全部/关闭错误 | [colors.xml:34](<../../../app/src/main/res/values/colors.xml#L34>) |
| `terminal_ripple` | `#33B2DCB9` | 外壳按压涟漪 | [colors.xml:35](<../../../app/src/main/res/values/colors.xml#L35>) |

`values-night/colors.xml` 全文只有 28 行（[values-night/colors.xml](<../../../app/src/main/res/values-night/colors.xml>)），没有任何 `terminal_*`，这是“三向态下终端始终深绿”的直接原因。

**绿色语义注释也有几处**，改色时一并更新：

- [colors.xml:20–21](<../../../app/src/main/res/values/colors.xml#L20-L21>)：“终端背景必须固定为深色”的注释仍然正确，保留。
- [colors.xml:26](<../../../app/src/main/res/values/colors.xml#L26>)：“终端外壳沿用设计稿的深色语义色”已过期，应改为“外壳跟随应用三向态，输出区固定黑底”。
- [bg_terminal_primary.xml:2](<../../../app/src/main/res/drawable/bg_terminal_primary.xml#L2>) 注释“浅绿实心 + 深色文字”已过期。
- [bg_terminal_touch.xml:2](<../../../app/src/main/res/drawable/bg_terminal_touch.xml#L2>) 注释“深色终端外壳”已过期。

### 2.2 TerminalActivity 中逐行使用

按用途分成三类，改造时按类别替换，而不是全局换名：

**A. 外壳容器/分隔线（必须换成主题 token）**

| 行 | 用法 |
|---|---|
| [127](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L127>) | `root.setBackgroundColor(terminal_surface)`：根背景黑，保留（输出平面） |
| [135](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L135>) | `toolbar.setBackgroundColor(terminal_panel)` |
| [152](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L152>) 与 [494](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L494>) | `divider(terminal_line)`：工具栏分隔线、内联侧栏分隔线 |
| [174](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L174>) | `sessionScrim` 背景 `terminal_scrim` |
| [223](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L223>) | 会话面板 `terminal_panel` |
| [272](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L272>) | 面板底部与 footer 的分隔线 `terminal_line` |
| [188](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L188>) | 导航栏 `terminal_surface`（黑，保留） |

**B. 外壳文字/强调（必须换成主题 token，并拆分 foreground）**

| 行 | 用法 | 处理 |
|---|---|---|
| [156](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L156>) | 状态条文字 `terminal_foreground` | 状态条位于 toolbar 与 body 之间，背景是根黑底；保留固定浅色，见 4.6 |
| [232](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L232>) | 侧栏标题“会话” `terminal_foreground` | → `terminal_panel_text_strong` |
| [237](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L237>) | 侧栏副标题 `terminal_muted` | 重定义后自动成为浅色/深色主题值 |
| [254](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L254>) | 新建按钮文字 `terminal_on_accent` | 改用 `button_primary_text` / 新 token |
| [279](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L279>)、[307](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L307>)、[858](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L858>)、[859](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L859>)、[868](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L868>)、[911](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L911>) | 摘要/空态/分组头/状态 `terminal_muted` | 重定义即可 |
| [287](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L287>)、[949](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L949>) | `terminal_danger`：结束全部、关闭错误 | 重定义即可 |
| [296](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L296>) | `terminal_warning`：侧栏 notice | 重定义即可 |
| [882](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L882>) | 当前会话左侧 2dp accent 条 | `terminal_accent` |
| [904](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L904>) | 会话名：当前 `terminal_accent`，非当前 `terminal_foreground` | 非当前改 `terminal_panel_text` |
| [1301](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1301>) | 工具栏标题 `terminal_foreground` | → `terminal_panel_text`（标题建议 strong） |
| [1313](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1313>) | 工具栏按钮 `terminal_foreground` | 图标化后改 tint：`terminal_panel_text` |
| [1351](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1351>) | footer 文字 `terminal_foreground` | → `terminal_panel_text` |
| [1359](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1359>) | `panelIcon` glyph 颜色 `terminal_foreground` | 图标化后按语义 tint（accent/muted/danger） |
| [920](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L920>) 附近 | 会话行 contentDescription（已有 `UiText.format`） | 保留，图标化不改语义 |
| [1375](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1375>) | `label()` 设 `R.color.text` | 仅 status 使用且随后被 156 行覆盖；建议直接删掉误导性赋值或改为 `terminal_foreground` |

**C. 外壳 drawable（背景资源）**

| 行 | drawable |
|---|---|
| [259](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L259>) | 新建会话 `bg_terminal_primary`（绿色实心）→ 换 `bg_btn_primary` 或新的软主色背景 |
| [880](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L880>) | 当前会话行 `bg_terminal_session` → 换新 token 后的资源 |
| [892](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L892>)、[1322](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1322>)、[1345](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1345>)、[1366](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1366>) | `bg_terminal_touch`（透明涟漪） |
| [897](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L897>) | 运行点 `bg_terminal_dot` |

### 2.3 输出区布局（保持黑底，不在本轮改色）

| 文件:行 | 内容 | 结论 |
|---|---|---|
| [fragment_terminal.xml:4](<../../../app/src/main/res/layout/fragment_terminal.xml#L4>) | 简易终端根背景 `terminal_surface` | 保留黑 |
| [fragment_terminal.xml:7,21,29,36,41,51,56](<../../../app/src/main/res/layout/fragment_terminal.xml#L7>) | 状态/输出/提示符/输入/发送/中止/清理文字 `terminal_foreground` | 保留固定浅色 |
| [fragment_pty_terminal.xml:4,9,30,34](<../../../app/src/main/res/layout/fragment_pty_terminal.xml#L4>) | PTY 根、标题、`TerminalView`、空态 | 保留黑 |
| [verification] `PtyTerminalFragment.java:347,425,434,909` | 附加键/命令文字与 TerminalView 背景 | 属于输出平面，保留固定 `terminal_foreground` / `terminal_surface` |
| [ModernAndroidUi.java:35](<../../../app/src/main/java/com/deepseekharness/app/ui/ModernAndroidUi.java#L35>)、[WebFullscreenUi.java:349](<../../../app/src/main/java/com/deepseekharness/app/ui/WebFullscreenUi.java#L349>) | 全屏/画中画系统栏取 `terminal_surface` | 仍应是黑；不改 |

### 2.4 view_terminal_tabs 与旧标签条

- [view_terminal_tabs.xml:6](<../../../app/src/main/res/layout/view_terminal_tabs.xml#L6>)：`Button@terminal_new`，`text=@string/ui_m0218`（含 `＋` glyph），`contentDescription=@string/ui_m0142`。这个布局被 [fragment_terminal.xml:9–16](<../../../app/src/main/res/layout/fragment_terminal.xml#L9-L16>) 和 [fragment_pty_terminal.xml:18–24](<../../../app/src/main/res/layout/fragment_pty_terminal.xml#L18-L24>) 放在 `visibility="gone"` 的容器里，**生产不可见，只保留给迁移夹具定位**。本轮不把它当图标化范围；如重新启用，见第 9 节字符串范围。
- [TerminalTabBar.java:48](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalTabBar.java#L48>) 仍有 `×` glyph，且颜色已用应用主题 token（`primary/text_secondary/bg_chip/bg_selection`），同样在 GONE 容器内，保持原样。

### 2.5 文字 glyph 清单（全部要替换或明确保留）

| 文件:行 | Glyph | 语义/位置 | 处理 |
|---|---|---|---|
| [TerminalActivity.java:245](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L245>) | `×` | 收起会话侧栏 | → vector `ic_terminal_close` |
| [TerminalActivity.java:253](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L253>) | `＋` | 新建会话文字前缀 | → `ic_terminal_new`，文案去掉 `＋` |
| [TerminalActivity.java:925](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L925>) | `✎` | 会话重命名 | → `ic_terminal_rename` |
| [TerminalActivity.java:933](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L933>) | `⟳` / `✕` | 退出待核验 / 结束会话 | → `ic_recovery_refresh` / `ic_terminal_close` |
| [TerminalFloatingMenu.java:30](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L30>) | `>_` | 悬浮球品牌 glyph | **保留**（预览同样保留），只换背景/文字主题 token |
| [TerminalActivity.java:247](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L247>) | 22sp | 旧 `×` 字号 | 图标化后改为 24dp icon，不再设 22sp |
| [view_terminal_tabs.xml:6](<../../../app/src/main/res/layout/view_terminal_tabs.xml#L6>) | `＋`（资源 `ui_m0218`） | 旧标签条新建 | 本轮不动 |
| [TerminalTabBar.java:48](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalTabBar.java#L48>) | `×` | 旧标签条关闭 | 本轮不动 |
| [fragment_terminal.xml:28](<../../../app/src/main/res/layout/fragment_terminal.xml#L28>) | `@string/ui_terminal_prompt`（`$` 提示符） | 输出区提示符 | 属于终端输出，保留 |

### 2.6 其余终端 drawable

| 文件:行 | 现状 | 处理 |
|---|---|---|
| [bg_terminal_primary.xml:13](<../../../app/src/main/res/drawable/bg_terminal_primary.xml#L13>) | 绿色实心 + `terminal_ripple` | 废弃，改用 `bg_btn_primary` 或软主色 |
| [bg_terminal_session.xml:3](<../../../app/src/main/res/drawable/bg_terminal_session.xml#L3>) | `terminal_accent_bg` + 6dp 圆角 | 保留结构，token 重定义；圆角按预览降到 4dp（可选） |
| [bg_terminal_touch.xml:4](<../../../app/src/main/res/drawable/bg_terminal_touch.xml#L4>) | `terminal_ripple` | 保留，token 改为主题涟漪 |
| [bg_terminal_dot.xml:3](<../../../app/src/main/res/drawable/bg_terminal_dot.xml#L3>) | `terminal_accent` | 保留，token 重定义 |
| [bg_terminal_menu.xml:2–5](<../../../app/src/main/res/drawable/bg_terminal_menu.xml#L2-L5>) | `primary_ripple` + `terminal_outline` + `terminal_foreground` | 改为面板底/强描边/主题文字，见 4.2 |
| [bg_terminal.xml:3–5](<../../../app/src/main/res/drawable/bg_terminal.xml#L3-L5>) | `terminal_surface` + `terminal_outline`，全仓库无引用（仅可能留给旧夹具） | 本轮不动，标记为待清理 |
| [ic_terminal.xml](<../../../app/src/main/res/drawable/ic_terminal.xml>) | 24dp 终端图标，stroke 写死 `#FF000000`，现有页面用 `app:tint="@color/primary"` | 可复用为侧栏标识（tint accent） |

### 2.7 themes.xml 与系统栏

- [values/themes.xml:3–5](<../../../app/src/main/res/values/themes.xml#L3-L5>)：`Theme.DeepseekHarness.windowLightStatusBar=true`（浅色）；[values-night/themes.xml:4–6](<../../../app/src/main/res/values-night/themes.xml#L4-L6>)：`false`。**两份主题都没有终端专属属性**，`TerminalActivity` 使用应用主题；系统栏图标实际由代码覆盖。
- [TerminalActivity.java:186–191](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L186-L191>)：状态栏透明、导航栏黑、两种图标都强制“浅色图标”（`setAppearanceLightStatusBars(false)`）。在浅色工作台里工具栏会变成白色，但状态栏图标仍是白色 → 会看不清。这是本次必须修的细节，规则见 4.8。

---

## 3. 三向态机制审计

### 3.1 现有链路

| 环节 | 位置 | 事实 |
|---|---|---|
| 偏好值 | [UiThemePreference.java:5–30](<../../../app/src/main/java/com/deepseekharness/app/util/UiThemePreference.java#L5-L30>) | `system / light / dark`；未知旧值回 `system`；`next()` 循环三态 |
| 读写 | [ConfigStore.java:47–49,84–89](<../../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L47-L49>) | SharedPreferences 键 `ui_theme`，写入时 normalize |
| 应用 | [ThemeController.java:13–27](<../../../app/src/main/java/com/deepseekharness/app/ui/ThemeController.java#L13-L27>) | `apply()` → `AppCompatDelegate.setDefaultNightMode(...)`；`select()` 先存再 apply；注释明确“Activity 重建由 AppCompat 处理” |
| 启动时应用 | [DshaApp.java:160](<../../../app/src/main/java/com/deepseekharness/app/DshaApp.java#L160>) | Application 最早阶段 apply，早于任何 Activity |
| 用户入口 | [MainActivity.java:594–623](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L594-L623>) | `btn_theme` 单击循环、长按跟随系统；图标 `ic_ui2_auto/sun/moon`；`renderThemeButton()` 重绘 |
| 判定深浅 | [ThemeController.java:29–32](<../../../app/src/main/java/com/deepseekharness/app/ui/ThemeController.java#L29-L32>) | 用当前 `Configuration.uiMode & UI_MODE_NIGHT_MASK`，不走 SharedPreferences 猜测 |

### 3.2 `getColor()` 是否随 `uiMode` 刷新？

- **取值本身是实时的。** `Activity.getColor()` 走当前 `Resources` 的 Configuration，`uiMode` 已经切换时会返回新主题下的颜色。所以新增的 `values-night/colors.xml` 只要 Activity 重建，`getColor(R.color.terminal_panel)` 就会拿到新值。
- **已缓存到视图上的颜色不会自动刷新。** `TerminalActivity` 在 [onCreate:74](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L74>) → [buildWorkspace:124](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L124>) 和 [buildSessionsPanel:219](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L219>) 里把颜色 `setTextColor/setBackgroundColor/setBackgroundResource` 到具体 View；drawable 的颜色在 `setBackgroundResource` 加载时解析并缓存。之后只改 `Configuration` 不会重新执行这些赋值，因此**不要设计“onConfigurationChanged 里再 getColor 一次”的补丁**，除非把整套外壳改成可重放的颜色绑定，成本和风险都更大。
- **重建路径已经存在。** `TerminalActivity` 在 [AndroidManifest.xml:99–102](<../../../app/src/main/AndroidManifest.xml#L99-L102>) 没有声明 `configChanges="uiMode"`，系统深浅色变化会走默认重建；`AppCompatDelegate.setDefaultNightMode` 也会让正在运行的 Activity 应用新 night mode 并重建。
- **重建不会结束会话。** `TerminalSessionOwner.shared()` 是进程级单例（`owner` 字段 [TerminalActivity.java:39](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L39>)）；FragmentManager 恢复终端 Fragment；`onSaveInstanceState()` 保存 `SESSIONS_OPEN`、悬浮球停靠边与比例（[1287–1296](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1287-L1296>)）和 `terminal_owner`。现有测试 `sameProcessRecreateKeepsOwnerAndDoesNotCreateCommands`（[TerminalActivityLifecycleTest.java:233](<../../../app/src/test/java/com/deepseekharness/app/ui/TerminalActivityLifecycleTest.java#L233>)）已经覆盖“重建不新建/不关闭会话”。
- **必须补的验证：** “主题在 Main 切换后，再进入一个之前已停止的 TerminalActivity”是否一定重建。若实测出现停留旧 delegate、颜色不更新，建议在 `TerminalActivity.onResume()` 里比较“建视图时的 `uiMode`”与当前 `uiMode`，不一致才调用一次 `recreate()`（不要在 `onCreate` 里无条件 recreate，避免循环）。这是兜底，不替代 AppCompat 的默认重建。

### 3.3 终端内新增三向态开关（与 HTML 预览对齐）

预览在会话侧栏 footer 里放了 `系统 / 白天 / 黑夜` 三段开关（`theme-switch`），并在“终端显示”对话框里重复一份。原生建议只做侧栏 footer 一个入口，避免和 Main 的 `btn_theme` 形成两套状态：

- 控件：`LinearLayout`（横向 3 段，新建 IDs `terminal_theme_system/light/dark`，容器 `terminal_sessions_theme`），每段 `48dp` 触摸高度、图标 + 文案；`setSelected(true)` 表示当前偏好，`ViewCompat.setStateDescription` 补充“当前选中”。
- 文案：`跟随系统 / 白天 / 黑夜`，用 `UiText.choose(...)` 内联中英文；其他语言按现有机制回退英文（是否补 10 语种见第 9 节）。
- 点击：先判断是否与 `ThemeController.preference(this)` 相同（相同直接 return），否则 `ThemeController.select(this, mode)`。`select()` 会触发 AppCompat 重建；重建后从 `ThemeController.preference(this)` 恢复选中态，侧栏展开状态由 `SESSIONS_OPEN` 恢复。
- **瞬态保护（必须）：** `closeAllRunning` 为 true 时禁用三段开关（或至少忽略点击并提示“正在结束会话”）。原因：`closeAllBestEffort()` 的进度、`closeAllTotal/closeAllRun/closeAllWatchdog` 全是 Activity 字段，重建后旧回调因 `isDestroyed()` 被丢弃，新 Activity 会丢失“结束全部”的结果摘要；进程本身仍由 owner 异步核验，不会误判成功，但 UI 不能假装结果还在。
- 对话框里的三向态（预览有）可作为 P2 增强；`onPause()` 已经会 `dismissPanel()`（[1277](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1277>)），重建前对话框本来就会消失，不建议把主题开关只放在对话框里。

### 3.4 系统栏与极简/工作台

`workbench` 在 [onCreate:78](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L78>) 从偏好读出，`buildWorkspace()` 时已知，因此可以按模式决定状态栏：

| 模式 | 状态栏背景 | 状态栏图标 | 导航栏 |
|---|---|---|---|
| 极简（toolbar GONE，顶部是黑色输出） | 透明（根黑） | 浅色图标（`setAppearanceLightStatusBars(false)`） | 黑，浅色图标 |
| 工作台浅色（toolbar 可见，面板白） | 面板色 `terminal_panel`（不透明） | 深色图标（`true`） | 黑，浅色图标 |
| 工作台深色（toolbar 可见，面板近黑） | 面板色 `terminal_panel` | 浅色图标（`false`） | 黑，浅色图标 |

实现位置：[buildWorkspace:186–191](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L186-L191>)。**不要**只根据 `ThemeController.isDark()` 一维判断，极简模式下即使浅色主题也必须用浅色状态栏图标，因为背景是黑输出。

---

## 4. 终端 token 设计

### 4.1 原则

1. **外壳跟随应用三向态**：工具栏、侧栏、抽屉、footer、按钮、对话框入口全部用主题 token；浅色=白/浅蓝灰，深色=近黑/浅蓝。
2. **输出区固定**：`terminal_surface=#000000`、`terminal_foreground=#E4E8F0` 保持两套主题一致；不在输出区叠加主题色。
3. **语义拆分**：外壳文字用 `terminal_panel_text*`，不再复用 `terminal_foreground`；强调色统一 `terminal_accent`（浅色用应用 primary `#1E50D0`，夜间用 `#6AA4FF` 一系的 `#8AB4FF`）。
4. **不新增硬编码颜色**：Java 里不写 `Color.parseColor("#...")`；所有值经 `getColor()` / drawable token 读取；复用应用已有的 `bg_btn`、`bg_btn_primary`，减少一套平行按钮体系。
5. **与预览对齐**：本表采用 `preview.html` 根变量的色值；如果 Lead 定稿改了预览，只改本表与 `colors.xml`，不改结构。

### 4.2 建议 token 表（浅色 / 深色）

“批次”列：P0 = 去掉绿色必须；P1 = 视觉/交互完整度；P2 = 与预览逐像素对齐可选。

| Token | 浅色 | 深色 | 用途 | 现状/批次 |
|---|---|---|---|---|
| `terminal_panel` | `#FFFFFF` | `#0B0F16` | 工具栏、会话面板、抽屉 | 重定义；P0 |
| `terminal_panel_text` | `#152033` | `#E4E8F0` | 外壳主文字（标题/按钮/会话名） | **新增**；P0 |
| `terminal_panel_text_strong` | `#0D1727` | `#F2F5FA` | “会话”标题、强调 | 新增；P1 |
| `terminal_muted` | `#59657A` | `#9DA8BB` | 副标题/状态/摘要 | 重定义；P0 |
| `terminal_faint` | `#7A879C` | `#7C8AA0` | 分组图标、脚注、空态 | 新增；P1 |
| `terminal_line` | `#DFE6F0` | `#1E2735` | 分隔线/描边 | 重定义；P0 |
| `terminal_line_strong` | `#C6D2E4` | `#2E3B4F` | 悬浮球描边、主题轨道描边 | 新增；P1 |
| `terminal_accent` | `#1E50D0` | `#8AB4FF` | 运行点、当前行条、图标强调 | 重定义；P0 |
| `terminal_accent_bg` | `#E8EFFF` | `#13233C` | 当前会话行底色 | 重定义；P0 |
| `terminal_accent_soft_2` | `#DBE7FF` | `#1B3050` | 当前会话图标块/按下 | 新增；P2 |
| `terminal_accent_line` | `#B9CCF4` | `#2C4C7C` | 当前行/主软按钮描边 | 新增；P1 |
| `terminal_on_accent` | `#FFFFFF` | `#081428` | 实心主按钮文字 | 重定义；P0 |
| `terminal_raised` | `#EAEFF7` | `#151C27` | 主题轨道底、计数胶囊、图标块 | 新增；P1 |
| `terminal_warning` | `#7A5300` | `#E5C07B` | 侧栏警示文字 | 重定义；P0 |
| `terminal_warning_soft` | `#FCF3DE` | `#2A2417` | notice 底色（P1 可选） | 新增；P2 |
| `terminal_danger` | `#A93B43` | `#E08A8A` | 结束全部/关闭错误 | 重定义；P0 |
| `terminal_danger_soft` | `#FBEBEC` | `#2C1A1C` | 软危险按钮底 | 新增；P1 |
| `terminal_ripple` | `#1F1E50D0` | `#338AB4FF` | 外壳按压涟漪（约 12%/20% 不透明） | 重定义；P0 |
| `terminal_scrim` | `#8C09101E` | `#B3000000` | 抽屉遮罩（55%/70%） | 重定义；P1 |
| `terminal_panel_card` | `#F7F9FD` | `#10151E` | footer 底（与面板区分） | 新增；P2 |

> 如果 Lead 更愿意直接 alias 到应用 token（如 `<color name="terminal_panel">@color/surface</color>`），可以少维护一份色值；但本表的色彩合同（尤其 `terminal_panel_text`、`terminal_accent_line`、`terminal_danger_soft`）必须保留，因为应用现有 token 里没有一一对应项，直接混用会让终端外壳和预览再次漂移。

**固定、两套主题都相同的 token（不要放进 values-night 覆盖列表）：**

| Token | 值 | 说明 |
|---|---|---|
| `terminal_surface` | `#000000` | 终端输出区/画布；用户合同 |
| `terminal_foreground` | `#E4E8F0` | 黑底前景；保留现有值。预览为 `#E6ECF5`，差异很小，本轮不建议为对齐预览去动终端渲染合同 |
| `terminal_outline` | `#263041` | 仅被待清理的 `bg_terminal.xml` 使用；本轮保留，等 `bg_terminal` 删除时一起清理 |
| `terminal_warning_on_surface`（可选） | `#E5C07B` | 如未来要把“黑底状态条”的 warning 文案染成暖色，用固定值；当前不需要 |

### 4.3 `values/colors.xml` 建议片段

```xml
<!-- 终端外壳跟随应用三向态；输出区仍固定黑底。
     浅色：浅蓝 + 白；深色：浅蓝 + 近黑。 -->
<color name="terminal_surface">#000000</color>
<color name="terminal_foreground">#E4E8F0</color>
<color name="terminal_outline">#263041</color>

<color name="terminal_panel">#FFFFFF</color>
<color name="terminal_panel_text">#152033</color>
<color name="terminal_panel_text_strong">#0D1727</color>
<color name="terminal_muted">#59657A</color>
<color name="terminal_faint">#7A879C</color>
<color name="terminal_line">#DFE6F0</color>
<color name="terminal_line_strong">#C6D2E4</color>
<color name="terminal_accent">#1E50D0</color>
<color name="terminal_accent_bg">#E8EFFF</color>
<color name="terminal_accent_soft_2">#DBE7FF</color>
<color name="terminal_accent_line">#B9CCF4</color>
<color name="terminal_on_accent">#FFFFFF</color>
<color name="terminal_raised">#EAEFF7</color>
<color name="terminal_warning">#7A5300</color>
<color name="terminal_warning_soft">#FCF3DE</color>
<color name="terminal_danger">#A93B43</color>
<color name="terminal_danger_soft">#FBEBEC</color>
<color name="terminal_ripple">#1F1E50D0</color>
<color name="terminal_scrim">#8C09101E</color>
<color name="terminal_panel_card">#F7F9FD</color>
```

### 4.4 `values-night/colors.xml` 追加片段

```xml
<!-- 终端外壳夜间：浅蓝 + 近黑；输出区固定 token 不在此覆盖。 -->
<color name="terminal_panel">#0B0F16</color>
<color name="terminal_panel_text">#E4E8F0</color>
<color name="terminal_panel_text_strong">#F2F5FA</color>
<color name="terminal_muted">#9DA8BB</color>
<color name="terminal_faint">#7C8AA0</color>
<color name="terminal_line">#1E2735</color>
<color name="terminal_line_strong">#2E3B4F</color>
<color name="terminal_accent">#8AB4FF</color>
<color name="terminal_accent_bg">#13233C</color>
<color name="terminal_accent_soft_2">#1B3050</color>
<color name="terminal_accent_line">#2C4C7C</color>
<color name="terminal_on_accent">#081428</color>
<color name="terminal_raised">#151C27</color>
<color name="terminal_warning">#E5C07B</color>
<color name="terminal_warning_soft">#2A2417</color>
<color name="terminal_danger">#E08A8A</color>
<color name="terminal_danger_soft">#2C1A1C</color>
<color name="terminal_ripple">#338AB4FF</color>
<color name="terminal_scrim">#B3000000</color>
<color name="terminal_panel_card">#10151E</color>
```

### 4.5 对比度（WCAG 2.1，实算）

| 组合 | 对比度 | 判定 |
|---|---|---|
| `terminal_panel_text #152033` on `#FFFFFF` | 16.33 | 通过 |
| `terminal_muted #59657A` on `#FFFFFF` | 5.89 | 通过 |
| `terminal_accent #1E50D0` on `#FFFFFF` | 6.74 | 通过 |
| `terminal_on_accent #FFFFFF` on `#1E50D0` | 6.74 | 通过 |
| `terminal_danger #A93B43` on `#FFFFFF` | 6.19 | 通过 |
| `terminal_warning #7A5300` on `#FFFFFF` | 6.85 | 通过 |
| `terminal_muted #59657A` on `terminal_accent_bg #E8EFFF` | 5.11 | 通过 |
| `terminal_panel_text #E4E8F0` on `#0B0F16` | 16.16 | 通过 |
| `terminal_muted #9DA8BB` on `#0B0F16` | 8.00 | 通过 |
| `terminal_accent #8AB4FF` on `#0B0F16` | 9.19 | 通过 |
| `terminal_on_accent #081428` on `#8AB4FF` | 8.81 | 通过 |
| `terminal_danger #E08A8A` on `#0B0F16` | 7.47 | 通过 |
| `terminal_foreground #E4E8F0` on `#000000` | 17.10 | 通过（输出区固定） |

图标也按非文本 3:1 目标设计；上表 accent/muted 均远超。

### 4.6 与预览的已知差异（需 Lead 知晓）

1. 输出区前景：预览 `--term-text #E6ECF5`，原生现为 `#E4E8F0`。本文保留原生值，避免为 2 个色阶去改终端输出合同；若要求严格对齐，可在 `PtyTerminalFragment`/布局中单点替换并重跑终端回归。
2. 状态条：`terminal_workspace_status`（[156](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L156>)）位于 toolbar 与终端 body 之间，父背景是根黑底，预览没有对应元素。本文建议保留固定 `terminal_foreground`，并显式 `setBackgroundColor(terminal_surface)`，避免被浅色主题误伤；不要把它改成 `terminal_panel_text`。
3. 手势/系统栏：预览是浏览器视口，不证明原生状态栏/导航栏行为；以第 3.4 节原生规则为准。

### 4.7 取色与主题重建的代码约束

- 所有新颜色只通过 `getColor(...)` 或 drawable token 使用；禁止 Java 硬编码 `#RRGGBB`。
- `terminal_panel_text` 等要替换的旧行见 2.2 B 表；`terminal_foreground` 保留给输出布局与状态条。
- 重建后 `buildWorkspace()` 会重新 `getColor`，自然拿到新值；不需要监听 `onConfigurationChanged`。
- `root.setForceDarkAllowed(false)`（[128](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L128>)）保留，防止系统强制加深黑底输出。
- `terminal_scrim` 只用于 `sessionScrim`；不要把抽屉遮罩换成语义外的 `ripple`。

---

## 5. 图标化落地方案

### 5.1 实现方式（不改布局 XML）

`TerminalActivity` 的工具栏、侧栏全部代码构建，图标化不需要新增 layout；用三类控件替换：

1. **工具栏/侧栏图标按钮**：`TextView`（现有 `panelIcon/toolbarButton` 路径）或 `AppCompatImageButton`。推荐继续用 `TextView`/`AppCompatImageButton` 混合：
   - 纯图标按钮（返回、菜单、收起侧栏）：`AppCompatImageButton` + `bg_terminal_touch` + `setImageResource` + `setImageTintList` + `setContentDescription`，`minWidth/minHeight=48dp`、`padding=12dp`。
   - 图标+文字按钮（footer、新建）：`TextView` + `setCompoundDrawablesRelativeWithIntrinsicBounds(icon,0,0,0)` + `setCompoundDrawablePadding(7dp)` + `setGravity(CENTER_VERTICAL|START)`；保留可见文字作为无障碍名称，必要时再补 `contentDescription`。
2. **会话行的 30dp 图标块**：`AppCompatImageView`（或 TextView 背景 + 图标）叠在现有名次左侧；`bg` 用新的 `terminal_raised` / 当前行 `terminal_accent_soft_2`，图标 16dp，tint `terminal_faint` / `terminal_accent`。
3. **分组头/计数**：`ImageView` 15dp + 文本 + 计数胶囊（`terminal_raised` 底、`terminal_line` 描边、`terminal_muted` 字，最小 22×20dp）。

Tint 统一用 `ColorStateList`：
- 普通图标：`terminal_panel_text`（工具栏）或 `terminal_muted`（会话行）；
- 强调/当前：`terminal_accent`；
- 危险：`terminal_danger`；
- 主按钮内图标：`terminal_on_accent`；
- 选中态：`terminal_accent`（主题三段）。

### 5.2 图标清单（对应 task 的 12 项 + 原生额外项）

“来源”列：**新** = 本次新增 vector；**复用** = 仓库已有；所有路径取自本仓库 `preview.html` 自绘 symbol 或本次新绘，不复制 Termux GPL 资源。

| 语义 | 资源 | 来源 | 尺寸 | Tint / 位置 | contentDescription |
|---|---|---|---|---|---|
| 会话（侧栏标识/入口） | `ic_terminal` | 复用 | 20dp | accent；侧栏标题左侧 | 装饰性，`importantForAccessibility=no` |
| 会话菜单 | `ic_terminal_menu` | 新（i-menu） | 24dp | panel_text；工作台窄屏 toolbar | 现有“打开会话侧栏” |
| PTY | `ic_terminal_pty` | 新（i-pty） | 15dp 分组 / 16dp 会话块 | faint；active 时 accent | 装饰性；会话行整体已有描述 |
| simple | `ic_terminal_simple` | 新（i-simple） | 15dp / 16dp | 同上 | 装饰性 |
| 新建 | `ic_terminal_new` | 新（i-plus） | 20dp | accent；新建按钮 | 按钮文字“新建会话”已可读 |
| 关闭 | `ic_terminal_close` | 新（i-close） | 24dp 侧栏 / 18dp 会话行 | muted；会话行 hover 不可依赖 | 现有“收起会话侧栏”/“结束 %s 的进程” |
| 重命名 | `ic_terminal_rename` | 新（铅笔） | 18dp | muted；会话行 | 现有“重命名 %s” |
| 返回 | `ic_ui_back` | 复用 | 24dp | panel_text；toolbar 左侧 | 新增“返回”/“Back” |
| 返回主界面 | `ic_terminal_home` | 新（i-home） | 17dp | accent；footer 主动作 | 可见文字“返回主界面” |
| 显示设置 | `ic_terminal_display`（或复用 `ic_settings`） | 新/复用 | 17dp footer / 24dp toolbar | accent/ panel_text | 现有“终端显示设置” |
| 键盘 | `ic_terminal_keyboard` | 新（i-keyboard） | 17dp | accent；footer | 可见文字“显示键盘” |
| 说明 | `ic_about`（或新 `ic_terminal_help`） | 复用 | 17dp / 24dp | accent / panel_text | 现有“终端与会话说明” |
| 主题-系统 | `ic_ui2_auto` | 复用 | 15dp | muted/accent（选中） | “跟随系统外观” |
| 主题-白天 | `ic_ui2_sun` | 复用 | 15dp | 同上 | “白天模式” |
| 主题-黑夜 | `ic_ui2_moon` | 复用 | 15dp | 同上 | “黑夜模式” |
| 结束全部 | `ic_terminal_end_all` | 新（圆+方） | 16dp | danger；summary 行 | 现有“结束全部终端会话并核验” |
| 退出待核验 | `ic_recovery_refresh` | 复用 | 18dp | warning/danger；会话行 | 现有“核验并结束 %s” |
| 粘贴（保留动作） | `ic_ui_copy` | 复用 | 17dp | accent；输入辅助行 | 可见文字“粘贴文本” |
| 回到输入（保留动作） | `ic_ui2_prompt` | 复用 | 17dp | accent；输入辅助行 | 可见文字“回到输入” |

**12 项任务的落点映射：** 会话 → `ic_terminal`/`ic_terminal_menu`；PTY → `ic_terminal_pty`；simple → `ic_terminal_simple`；新建 → `ic_terminal_new`；关闭 → `ic_terminal_close`；重命名 → `ic_terminal_rename`；返回 → `ic_ui_back`；显示 → `ic_terminal_display`/`ic_settings`；键盘 → `ic_terminal_keyboard`；说明 → `ic_about`；主题 → `ic_ui2_auto/sun/moon`；结束全部 → `ic_terminal_end_all`。

### 5.3 vector 规范

- 统一 `24dp`、`viewportWidth/Height=24`、`fillColor=@android:color/transparent`、`strokeColor="#FF000000"`（运行时 tint 覆盖）、`strokeWidth=1.6～1.7`、`strokeLineCap/Join=round`。
- 不复用 PNG，不新增 density 目录；vector 由 Resources 缓存，符号成本可忽略。
- tint 前如多个视图共享同一 drawable 实例，先 `mutate()` 或使用 `DrawableCompat.setTintList`，避免一个按钮的 tint 污染另一个；更简单的是每个按钮 `getResources().getDrawable(id, theme)` 后直接 tint。
- 路径清单见[附录 C](#附录-c建议-vector-pathdata)。

### 5.4 glyph → icon 替换映射

| 位置 | 旧 | 新 |
|---|---|---|
| [TerminalActivity.java:245–249](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L245-L249>) | `panelIcon("×", ..., 48dp)` + `setTextSize(22)` | `AppCompatImageButton(ic_terminal_close)`，48dp，contentDescription 不变，删掉 22sp |
| [TerminalActivity.java:251–262](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L251-L262>) | `"＋ 新建会话"` + 绿色 `bg_terminal_primary` + `terminal_on_accent` | `"新建会话"` + `ic_terminal_new` compoundDrawable + `bg_btn_primary`/软主色 + `button_primary_text`，ID `terminal_sessions_create` 不变 |
| [TerminalActivity.java:923–930](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L923-L930>) | `panelIcon("✎", ...)` | `panelIcon(ic_terminal_rename)`，tint muted，48dp，描述不变 |
| [TerminalActivity.java:931–942](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L931-L942>) | `panelIcon("⟳"/"✕")` | 分别换 `ic_recovery_refresh` / `ic_terminal_close`；tint danger/warning；描述不变 |
| [TerminalActivity.java:154–158](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L154-L158>) | status 用 `terminal_foreground` | 保留；显式加黑底，见 4.6 |
| [TerminalFloatingMenu.java:30–35](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L30-L35>) | `>_` + `terminal_foreground` + `bg_terminal_menu` | 文本保留；文字改 `terminal_panel_text`，背景改面板色/强描边/主题涟漪（浅色白球、夜间近黑球） |

### 5.5 尺寸、无障碍与状态

- 所有图标按钮 48dp 触摸（现有 `minWidth/minHeight=48dp` 已满足；`LayoutAuditInstrumentation` 的 43.5dp 下限也要求不缩）。
- 图标尺寸：工具栏 24dp、footer 17–18dp、分组/会话 15–16dp、主题段 15dp；不要用 22sp 文本冒充图标。
- **可见文字优先**：footer/新建/说明等内容保留文字标签，图标只是增强；只有工具栏返回/菜单/侧栏收起这类无文字按钮必须 `contentDescription`。
- 所有描述继续走 `UiText.choose/text/format`（不要直接 `setContentDescription("中文")`，会被 `tools/check-ui-i18n.py` 记为新增 i18n 债）。
- 主题三段用 `setSelected` + `stateDescription`；不要只用颜色区分选中状态。
- 禁用态：沿用 `bg_btn` 的 disabled 外观 + `button_text` 状态色；图标用 `setAlpha(.45f)` 或 tint 的 disabled 状态。
- 动画：不加逐帧动画；图标化本身不应引入新 `onDraw`/requestLayout。

### 5.6 悬浮球与旧标签条

- 悬浮球保留 `>_`（预览也保留），仅改主题：背景 `terminal_panel`、描边 `terminal_line_strong`、文字 `terminal_panel_text`、涟漪 `terminal_ripple`；拖动态描边 `terminal_accent`（可选）。本轮不换成图标，避免改变用户对入口的识别。
- `view_terminal_tabs` / `TerminalTabBar` 在 GONE 容器内，旧 `＋/×` 留着不影响生产；如未来恢复标签条，再按 5.4 的方式替换，并同步第 9 节的 10 语种字符串。

---

## 6. 底部动作：带底色填充 + 2×2

### 6.1 现状

- [addFooterRow(2 列):383–394](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L383-L394>) 和 [addFooterRow(3 列):396–411](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L396-L411>) 只创建可点击 `TextView`，没有背景、没有图标、没有按按钮 ID。
- 宽窗 3 行×2 列：[315–344](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L315-L344>)（主页返回 / 显示设置 / 显示键盘 / 粘贴文本 / 回到输入 / 说明）。
- 短窗 2 行×3 列：[349–376](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L349-L376>)。
- 容器 ID：`terminal_sessions_actions` [314](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L314>)、`terminal_sessions_actions_dense` [348](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L348>)。测试 [TerminalActivityLifecycleTest.java:362–383](<../../../app/src/test/java/com/deepseekharness/app/ui/TerminalActivityLifecycleTest.java#L362-L383>) 断言这两个容器的可见性切换，**必须保留**。

### 6.2 目标结构（与预览对齐，同时保留 6 个动作）

侧栏 footer 从上到下：

1. `sessionSummary`（现有“%d 个会话 · PTY %d / 简易 %d”）+ `closeAllButton`（“结束全部”，图标化 + 软危险底）。
2. **主题三段开关**（3.3）。
3. **2×2 动作网格**（预览四动作）：返回主界面 / 显示模式 / 显示键盘 / 说明。
4. **输入辅助行**（原生保留的两个动作）：粘贴文本 / 回到输入 —— 同款填充按钮，2 列 1 行。
5. **脚注**：复用现有 `panelSecondary`（“返回只关闭页面 · 结束进程单独核验”），不再新增文案；`compactHeight` 时按现有规则隐藏。

> **决策点（Lead 必须确认）：** 预览只有 4 个动作，原生有 6 个。本文默认 2×2 主动作 + 2 个输入辅助全保留；如果产品要严格 2×2，只能把“粘贴文本/回到输入”移到终端输入条或接受能力减少，必须单独确认，不能在本轮静默删除。

### 6.3 按钮资源与状态

| 用途 | 背景 | 文本色 | 图标 tint | 备注 |
|---|---|---|---|---|
| 普通动作（显示/键盘/说明/粘贴/回到输入） | 复用 `@drawable/bg_btn`（card 底 + line 描边 + `@color/ripple` + disabled） | `@color/button_text`（状态色） | `terminal_accent` | 预览是 surface 底 + line 描边；`bg_btn` 已有等价语义，减少新资源 |
| 主动作（返回主界面） | 新增 `bg_terminal_action_soft.xml`：`terminal_accent_bg` 底 + `terminal_accent_line` 描边 + `terminal_ripple`；disabled 时 `disabled` 底 | `terminal_accent`（bold） | `terminal_accent` | 对应预览 `.is-primary` |
| 危险动作（结束全部） | 新增 `bg_terminal_action_danger.xml`：`terminal_danger_soft` 底 + `terminal_danger` 描边 + 涟漪；或复用 `bg_btn_danger`（实心 err，视觉更重） | `terminal_danger` | `terminal_danger` | 预览 close-all 是软危险；推荐新增软底 |
| 主题轨道 | 新增 `bg_terminal_theme_track.xml`：`terminal_raised` 底 + `terminal_line` 描边 + 10dp 圆角 | — | — | 预览 `.theme-switch` |
| 主题分段 | 新增 `bg_terminal_theme_segment.xml`：`<selector>`，`state_selected=true` → `terminal_panel` 底 + `terminal_accent_line` 描边 + `terminal_accent` 字；否则透明；外套 ripple | `terminal_muted` / 选中 `terminal_accent` | `terminal_muted` / 选中 `terminal_accent` | 必须保留 selected 状态 |
| 新建会话 | 复用 `@drawable/bg_btn_primary`（或同款软主色） | `@color/button_primary_text` | `terminal_on_accent` | 替换绿色 `bg_terminal_primary` |
| 会话行 | 更新 `bg_terminal_session`：`terminal_accent_bg` + 4dp 圆角（可选）+ 左侧 2dp accent 条 | — | — | 当前行选中态 |

尺寸：保持 48dp 触摸高度（预览 42px 是 CSS 视觉值，不照搬）；文字 12sp（短窗 11sp）；图标 17–18dp；圆角用现有 `@dimen/radius_small`（10dp）或 `radius_control`（13dp），不要引入第三套圆角。`bg_btn` 的 13dp 与预览 9px 略有差异，**一致性优先**；若 Lead 要求逐像素，再加 9dp 专用 `bg_terminal_action` 覆盖。

### 6.4 布局适配（宽窗 / 短窗 / 大字体）

- **宽窗（正常）**：2 列网格；主动作 2×2（2 行），输入辅助 1 行 × 2 列；每行 `LinearLayout.HORIZONTAL`，子项 `layout_width=0, weight=1`、`minHeight=48dp`。
- **短窗 `denseFooter`（`height<480dp`，[TerminalWorkspacePolicy.java:86–88](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java#L86-L88>)）**：2×2 在 470dp 高度仍可用；若 1.3 倍字体/320dp 宽导致 3 行 footer 挤掉会话列表，则退回预览外的 **3 列×2 行**（现有 dense 合同），保证 6 个动作全在；不删动作。
- **`compactHeight`（`<560dp`，[TerminalWorkspacePolicy.java:78–80](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java#L78-L80>)）**：沿用现有规则隐藏副标题（[applyCompactChrome:462–471](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L462-L471>)）；新增隐藏 footer 脚注、压缩 summary/notice 内边距。
- **大字体**：按钮 `setSingleLine(false)` + `setMaxLines(2)`；图标与文字仍用 compound drawable，不手算宽度；`footerButton` 现有 `singleLine(true)` 在 1.3 倍字体下会截断，短窗应允许两行或缩到 11sp。
- **主题三段**：短窗可只显示图标 + 短标签；`max-height 440` 时（预览规则）隐藏标签只留图标，但保留 contentDescription。
- **测量验证**：400×470、320×400、640×900、900×700 四个窗口 + `fontScale=1.3` 都必须能点到全部动作，且不与会话列表重叠。

### 6.5 实现建议（伪代码，非可编译补丁）

```java
// 替换 addFooterRow：保留容器 ID，动作带 icon + 背景 + 稳定 ID。
private void addFooterGrid(LinearLayout footer, Action... actions) {
  for (int i = 0; i < actions.length; i += 2) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(HORIZONTAL);
    for (int j = i; j < Math.min(i + 2, actions.length); j++)
      row.addView(actionButton(actions[j]), new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }
}

private TextView actionButton(Action a) {
  TextView v = panelText(a.label, 12, getColor(a.textToken), a.primary);
  v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
  v.setMinHeight(dp(48));
  v.setPadding(dp(10), 0, dp(10), 0);
  v.setBackgroundResource(a.background);
  v.setCompoundDrawablePadding(dp(7));
  v.setCompoundDrawablesRelativeWithIntrinsicBounds(a.icon, 0, 0, 0);
  v.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(a.iconToken)));
  v.setId(a.id);
  v.setOnClickListener(x -> a.action.run());
  return v;
}
```

建议新增 ID（`values/terminal_workspace.xml`，全部为追加）：

```
terminal_workspace_back
terminal_sessions_theme
terminal_theme_system / terminal_theme_light / terminal_theme_dark
terminal_sessions_action_home / display / keyboard / paste / focus / help
terminal_sessions_action_home_dense / display_dense / keyboard_dense / paste_dense / focus_dense / help_dense
```

既有容器 ID、`terminal_sessions_create`、`terminal_sessions_close_all`、`terminal_sessions_close` 保持不变；会话行的重命名/关闭没有既有 ID（每行重复），继续用 contentDescription 定位，不新增重复 ID。

### 6.6 无障碍

- 每个可见按钮保留文字；纯图标按钮始终 `contentDescription`；主题三段 `stateDescription`。
- TalkBack 顺序：主题三段 → 主动作网格（左→右、上→下）→ 输入辅助 → 脚注；`LinearLayout` 自然顺序即可，不加 `focusable` 容器。
- 破坏性动作（结束全部、关闭会话）不能只靠红色区分：保留文字 + 现有确认对话框。
- 禁用/进行中：`setEnabled(false)` + `setAlpha(.45f)`；`closeAllRunning` 时同时禁用主题开关，避免重建丢失进度。
- 所有 48dp 触摸目标在 `LayoutAuditInstrumentation` 的 43.5dp 阈值之上。

---

## 7. 渲染优化（与图标化同批，不改变行为）

1. **缓存 token 颜色**：`buildWorkspace()/buildSessionsPanel()` 把 `panelText/muted/accent/line` 等 `getColor` 结果存到字段，供 `renderSessions()` 复用，减少每行多次 Resources 查询；主题重建时字段随 Activity 重建自然刷新。
2. **差量渲染会话列表**：`renderSessions()` 目前每次 `removeAllViews()` 重建（[827–852](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L827-L852>)），而 [onTerminalPaneChanged:700–728](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L700-L728>) 在每次状态变化都会调用。建议计算签名（PTY/简易的 id、标题、状态、active、closing、closeError、运行点、计数），签名不变直接 return；侧栏关闭时不渲染（已有 `visibility` 判断）。
3. **图标 Drawable 复用**：每类图标按 tint 缓存一个 `Drawable.ConstantState` 或 `ColorStateList`；避免每行 `getDrawable + mutate + tint` 重复分配。实测不明显则保留简单实现，不为此引入复杂缓存。
4. **避免布局抖动**：会话行最小高度固定 56dp、图标固定 15/16/18dp；不要用 `wrap_content` 的图标 + 动态字号互相触发测量；`setSingleLine/ellipsize` 已有。
5. **不加 elevation/阴影**：会话行平铺；面板 `elevation=0`（内联已设置），悬浮球保留 6dp（[TerminalFloatingMenu.java:41](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L41>)）。
6. **vector 取代 PNG/文字排版**：图标不参与文本测量，减少 glyph 字体排版成本；9 个新 vector 合计很小。
7. **主题重建代价**：重建只发生一次，不在动画/滚动中触发；切换后不重放命令、不结束进程（已有 owner 合同）。
8. **不做的优化**：不把 `sessionList` 换成 RecyclerView（会扩大测试面）；不改 `TerminalView` 绘制；不缓存终端输出。

---

## 8. 风险、测试与验收

### 8.1 风险清单

| 风险 | 影响 | 对策 |
|---|---|---|
| 浅色外壳误用 `terminal_foreground` | 白底白字 | 按 2.2 B 表拆分 `terminal_panel_text`；新增对比度测试/审查 |
| 主题切换时 Activity 未重建 | 外壳停留旧色 | 先在 Main 切主题再进终端实测；必要时 `onResume` 比较 `uiMode` 后一次 `recreate()`（3.2） |
| 重建丢“结束全部”进度/对话框 | UI 显示结果丢失，进程仍正确关闭 | `closeAllRunning` 时禁用主题开关；重建后从 owner 重渲染 closing 状态，不宣称结果 |
| 极简模式状态栏图标 | 白底白图标（或黑底黑图标） | 3.4 三模式表；按 `workbench + isDark` 决定 |
| Lint 硬编码色/文本 | CI Lint 出现新 error | Java 只用 `getColor`；vector 用 `#FF000000` + tint（与现有 `ic_terminal` 一致）；不新增 XML 中文字面量 |
| `check-ui-i18n.py` 新债 | UI i18n 基线失败 | 内容描述走 `UiText.choose`；新增字符串不直接写 `setContentDescription("中文")` |
| 短窗/1.3 倍字体 | 动作被挤出、文字截断 | 6.4 适配 + 验收矩阵；必要时 dense 3 列×2 行 |
| Tablet 内联侧栏 | 面板底色/分隔线在宽屏错位 | 内联与抽屉复用同一 `sessionPanel`，颜色 token 一处改；900×700 验收 |
| RTL | 图标/文字方向错 | 侧栏文字用 `setPaddingRelative`/`layoutDirection`；终端输出保持 LTR；900×700 RTL 验收 |
| 旧标签条回归 | GONE 内 glyph 仍在，测试夹具可能启用 | 本轮不动；记录为已知遗留 |
| 预览定稿与本文色值漂移 | 实现与用户确认不一致 | token 表集中在一节；预览变更只同步第 4 节 |

### 8.2 Robolectric 测试（新增，Lead 执行）

建议新增 `app/src/test/java/com/deepseekharness/app/ui/TerminalThemeTest.java`，复用 `TerminalActivityLifecycleTest.Host` 的 fake pane 模式：

1. `lightShellUsesLightTokensAndBlackOutput`：`AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO)` + 构建 Host；断言 `terminal_sessions_panel` 背景色 = `getColor(terminal_panel)` 浅色值；`terminal_error`/标题文字 = `terminal_panel_text`；`terminal_content`/根背景 = `terminal_surface` 黑。
2. `nightShellUsesDarkTokensAndBlackOutput`：`MODE_NIGHT_YES`；断言面板 = `#0B0F16`、文字 `#E4E8F0`、输出仍 `#000000`；不得等于旧绿色值。
3. `themeRecreateKeepsSessionsAndSidebarState`：打开抽屉 → `host.recreate()` 或 `setDefaultNightMode` 后重建 → `FakePane.creates/closes == 0`、`TerminalSessionOwner.shared()` 相同、`terminal_sessions_panel` 仍可见、悬浮球位置保留。
4. `themeSwitchSelectsAndSurvivesRecreate`：点击 `terminal_theme_dark` → 偏好变 `dark`；重建后 `terminal_theme_dark.isSelected()`；三段只有一个 selected。
5. `themeSwitchDisabledWhileEndingAll`：把 `closeAllRunning` 置 true（反射，参考现有测试风格）→ 点击主题段不改变偏好、不触发 recreate。
6. `footerActionsKeepAllSixAndContainerIds`：断言 `terminal_sessions_actions`/`_dense` 存在；6 个动作的可见文本/contentDescription 均可找到（含粘贴/回到输入）。
7. `iconButtonsHaveContentDescriptions`：工具栏返回/菜单/设置/说明、侧栏收起、会话行 rename/close、主题段均有非空 contentDescription 或可见文字；不得为空。
8. `terminalSurfaceStaysBlackInBothThemes`：直接 inflate `fragment_terminal`/`fragment_pty_terminal`（参考 `LayoutAuditInstrumentation` 的 scene 方式）断言背景 `#000000`、输出文字 `terminal_foreground`。
9. `legacyTabsRemainHidden`：确认 `view_terminal_tabs` 仍由 GONE 容器包裹，不因图标化回归可见。

注意：`TerminalNavigationTest.App.onCreate()` 是空实现（测试不应用主题），所以主题测试要先 `AppCompatDelegate.setDefaultNightMode`；测试之间要 `@After` 复位 `MODE_NIGHT_FOLLOW_SYSTEM`，避免静态 night mode 串测。

### 8.3 布局/样式审计（扩展 `LayoutAuditInstrumentation`）

现有审计只覆盖 `fragment_terminal`/`fragment_pty_terminal`，不覆盖 `TerminalActivity` 侧栏。建议 Lead 在 debug 包增加 scene `activity_terminal_workspace`（工作台）与 `activity_terminal_minimal`（极简），在 `style` 模式下至少跑：

- 宽度 320 / 360 / 392 / 400 / 640 / 900dp，高度 400 / 470 / 700 / 900dp，`fontScale=1.0` 与 `1.3`；
- 浅色 + 深色两轮；
- 断言：文字对比度 ≥ 4.5（现有实现）、可点区域 ≥ 43.5dp、同行操作不重叠、文字不裁切/溢出；
- 额外断言：`terminal_surface` 场景的背景为 `#000000`，外壳背景随主题变化。

### 8.4 Lint / 静态检查

- `bash build.sh :app:lintStandardRelease`（或项目现用 lint 任务）：不得新增 `HardcodedText`/`HardcodedColor` error；目标仍是 0 error。
- `python3 tools/check-ui-i18n.py`（如果本次改动触及 i18n 源）：不得新增未基线化的中文显示字面量。
- 源码合同测试：[TerminalInputContractTest.java](<../../../app/src/test/java/com/deepseekharness/app/ui/TerminalInputContractTest.java>) 读取 `TerminalFragment`/`PtyTerminalFragment` 源码做接线断言；本方案不改这两个类的输入接线，理论上不受影响；不要为了图标化去动 `term_send`/`term_ctrlc` 的接线。
- `git diff` 自审：只出现第 9 节列出的文件；不出现 `docs/` 以外未授权改动，不出现 `app/libs/`。

### 8.5 真机/模拟器验收清单（用户确认预览后执行）

- [ ] 极简模式：进入终端 → 默认黑底输出；悬浮球为浅色/夜间两套外观；打开抽屉底部主题三段可见。
- [ ] 工作台模式：手机抽屉、平板 ≥840dp 内联侧栏；浅色 = 白/浅蓝，夜间 = 近黑/浅蓝；输出区始终黑底。
- [ ] 三向态：跟随系统时切系统深浅色；白天固定；黑夜固定；三种切换后终端会话不重启、不新建、不结束。
- [ ] 主题开关：终端内切三段 → 立即重建 → 侧栏保持展开、悬浮球位置保持、会话列表/运行状态正确。
- [ ] “结束全部”进行中主题段禁用；结束后主题可切。
- [ ] 底部 6 个动作全部可点（宽窗与 400×470 短窗），都有图标+底色；48dp 触摸。
- [ ] 1.3 倍字体、320dp 宽：无文字裁切、无动作重叠、侧栏可滚动。
- [ ] TalkBack：工具栏、会话行、主题段、footer 动作的朗读名称正确；主题段能读出当前选中；纯图标按钮不读空。
- [ ] RTL：侧栏图文方向正确，终端输出仍 LTR。
- [ ] 状态栏/导航栏图标：极简浅色、工作台浅色、工作台夜间三种都清晰。
- [ ] Lint 0 error；所选 Robolectric 测试 0 fail；不声称未执行的矩阵通过。

---

## 9. 需要 Lead 统一修改的范围

**本轮子任务不碰这些文件/资源；以下清单用于 Lead 合并实施。**

### 9.1 Manifest

- **不需要改** [AndroidManifest.xml](<../../../app/src/main/AndroidManifest.xml>)；`TerminalActivity` 保持 `exported=false`、`singleTop`、`adjustResize`（[100–102](<../../../app/src/main/AndroidManifest.xml#L100-L102>)）。
- **明确不要**给 `TerminalActivity` 加 `android:configChanges="uiMode"`；加了会阻止系统深浅色重建，外壳颜色不会刷新（除非另做完整的颜色重绑，不在本方案）。
- 不加权限、不加进程、不加 `android:theme`（继续用应用主题）。

### 9.2 颜色资源

- [values/colors.xml](<../../../app/src/main/res/values/colors.xml>)：按 4.3 重定义 10 个旧 token、新增 10 个 token；保留 `terminal_surface`、`terminal_foreground`、`terminal_outline`。
- [values-night/colors.xml](<../../../app/src/main/res/values-night/colors.xml>)：按 4.4 追加 20 个覆盖；**不要**覆盖固定的输出 token。
- 可选：`@color/disabled`、`@color/card` 等应用 token 已有，不重复造。

### 9.3 Drawable

新增：

- `ic_terminal_menu.xml`、`ic_terminal_pty.xml`、`ic_terminal_simple.xml`、`ic_terminal_new.xml`、`ic_terminal_close.xml`、`ic_terminal_rename.xml`、`ic_terminal_home.xml`、`ic_terminal_keyboard.xml`、`ic_terminal_display.xml`、`ic_terminal_end_all.xml`（共 10 个；路径见附录 C）。
- `bg_terminal_action_soft.xml`（软主色填充/描边/涟漪/禁用）。
- `bg_terminal_action_danger.xml`（软危险填充/描边/涟漪/禁用）。
- `bg_terminal_theme_track.xml`（主题轨道）。
- `bg_terminal_theme_segment.xml`（选中态 selector + ripple）。
- 可选：`bg_terminal_count_pill.xml`（计数胶囊）。

修改：

- `bg_terminal_session.xml`：token 重定义后沿用；圆角 6dp→4dp 可选。
- `bg_terminal_menu.xml`：改为 `terminal_panel` 填充 + `terminal_line_strong` 描边 + 主题 ripple。
- `bg_terminal_touch.xml`：保留结构，`terminal_ripple` 改为主题值（或直接用 `@color/ripple`）。
- `bg_terminal_primary.xml`：不再使用，建议删除或标 deprecated；新按钮走 `bg_btn_primary`。
- `bg_terminal.xml`：全仓库无引用，建议单独清理（不与本轮混在一起，避免扩大 diff）。

复用（不必新增）：

`ic_ui_back`、`ic_settings`（或 `ic_terminal_display`）、`ic_about`、`ic_ui2_auto`、`ic_ui2_sun`、`ic_ui2_moon`、`ic_recovery_refresh`、`ic_ui_copy`、`ic_ui2_prompt`、`ic_terminal`。

### 9.4 IDs

在 [values/terminal_workspace.xml](<../../../app/src/main/res/values/terminal_workspace.xml>) 追加（不重命名任何旧 ID）：

- `terminal_workspace_back`
- `terminal_sessions_theme`
- `terminal_theme_system` / `terminal_theme_light` / `terminal_theme_dark`
- `terminal_sessions_action_home` / `_display` / `_keyboard` / `_paste` / `_focus` / `_help`
- `terminal_sessions_action_home_dense` / `_display_dense` / `_keyboard_dense` / `_paste_dense` / `_focus_dense` / `_help_dense`

保留：`terminal_workspace`、`terminal_workspace_toolbar`、`terminal_workspace_settings/help/menu`、`terminal_sessions_panel/host/scrim/close/create/close_all/summary/scroll/subtitle/secondary/notice/actions/actions_dense`、`terminal_content`、`terminal_menu_ball`、`terminal_reset_ball`。

### 9.5 字符串

- **不需要新增 `values/strings.xml` 条目**；按钮文字/contentDescription 继续用 `UiText.choose/text/format` 内联中英文。
- [TerminalActivity.java:253](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L253>) 的 `"＋ 新建会话"` 改为 `"新建会话"`（英文 `"+ New session"` → `"New session"`），只改代码字面量，不涉及 `ui_strings.xml`。若要求 10 语种完整，则需在 `tools/i18n/messages-extra.json` + `tools/i18n/translations/*.json` 增补该 key；**最小批次不做**，回退英文是现有机制。
- 新增主题三段/返回按钮的 contentDescription（`跟随系统外观/白天模式/黑夜模式/返回`）同样走 `UiText.choose`；10 语种补全为可选项。
- [view_terminal_tabs.xml:6](<../../../app/src/main/res/layout/view_terminal_tabs.xml#L6>) 的 `ui_m0218`（含 `＋`，10 个 `values-*/ui_strings.xml`）本轮不动；因为容器 GONE。若恢复标签条，再统一改 10 份 xml + `tools/i18n/messages.json`（`m0218`）与翻译。
- [ui_strings.xml:86](<../../../app/src/main/res/values/ui_strings.xml#L86>) 等 10 份不要单独改，避免“同一文案散落两处”的历史问题（AGENTS.md 已明确提醒）。

### 9.6 Java 文件（由 Lead 实施，本轮不碰）

- [TerminalActivity.java](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java>)：外壳 token 拆分（B 表行）、图标化（5.4）、2×2 footer（6.5）、主题三段（3.3）、系统栏规则（3.4）、`closeAllRunning` 保护、可选差量渲染（第 7 节）。
- [TerminalFloatingMenu.java:30–35](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L30-L35>)：仅改文字色/背景 drawable。
- [PtyTerminalFragment.java](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java>)、[fragment_terminal.xml](<../../../app/src/main/res/layout/fragment_terminal.xml>)、[fragment_pty_terminal.xml](<../../../app/src/main/res/layout/fragment_pty_terminal.xml>)：**不改**（输出区固定黑底）。
- 不修改 `TerminalSessionOwner`、`PtySession`、`TerminalRuntimeProtection` 或任何运行/关闭逻辑。

### 9.7 测试/审查文件

- 新增 `TerminalThemeTest.java`（8.2）。
- 扩展 [LayoutAuditInstrumentation.java](<../../../app/src/debug/java/com/deepseekharness/app/ui/LayoutAuditInstrumentation.java>) / [LayoutPreviewActivity.java](<../../../app/src/debug/java/com/deepseekharness/app/ui/LayoutPreviewActivity.java>) 的终端 scene（8.3）。
- 如果新增了直接中文 `setContentDescription`，同步 `tools/i18n/ui-source-baseline.json`；推荐用 `UiText` 避免改基线。
- `docs/` 只新增本文件；`preview.html` 由 Lead 定稿，本轮不提交。

---

## 10. 建议最小实施批次

每个批次都能独立回滚；前一批通过再进下一批，不要一次性改完全部。

| 批次 | 内容 | 主要文件 | 验证 | 预估 |
|---|---|---|---|---|
| **P0-a 去绿（最小可见收益）** | 4.3/4.4 颜色 token；`TerminalActivity` 外壳文字拆分 `terminal_panel_text`；工具栏/面板/分隔线/会话行/摘要/notice/危险色；悬浮球 theme；状态栏三模式；输出区不动 | `colors.xml`、`values-night/colors.xml`、`TerminalActivity.java`、`TerminalFloatingMenu.java` | 浅/深两套颜色断言；四窗口 1.0/1.3 字体；Lint 0 error | 0.5 天 |
| **P0-b 图标化** | 10 个新 vector + 复用图标；替换 5 处 glyph；会话行图标块/分组图标/计数胶囊；contentDescription；48dp | drawable、`terminal_workspace.xml`、`TerminalActivity.java` | TalkBack 名称；图标 tint 状态；Lint/对比度 | 0.5～1 天 |
| **P1 底栏 2×2** | 主题三段开关；2×2 主动作 + 输入辅助；`bg_terminal_action_soft/danger`、theme track/segment；短窗 3 列退回；ID 保持 | drawable、ids、`TerminalActivity.java` | 6 动作可达；400×470 + 1.3 字体；主题切换重建不丢状态 | 0.5～1 天 |
| **P2 渲染与验收** | 颜色字段缓存、`renderSessions` 差量、图标缓存；`TerminalThemeTest`；`LayoutAuditInstrumentation` 终端 scene；真机矩阵 | 测试、debug 审计、`TerminalActivity.java` | 8.2/8.3/8.5 全通过；不新增 i18n 债 | 0.5～1 天 |

**不建议的批次拆分：**

- 先改 footer 再改颜色：会在浅色主题下出现“白底白字 footer”的中间态。
- 先加主题开关再拆 `terminal_panel_text`：切换即重建，但浅色仍是绿/白字，无法验收。
- 把 `view_terminal_tabs`/`TerminalTabBar` 一起重做：GONE 遗留不会影响用户，扩大 diff 且触发测试夹具风险。

**回滚：** 每批只涉及颜色/drawable/一个 Activity；回滚按批次 revert 即可。不得回滚 `terminal_surface`/`terminal_foreground` 的输出合同；不得为“快速恢复绿色”去动运行/关闭逻辑。

---

## 附录 A：颜色与 glyph 快速索引

| 对象 | 位置 |
|---|---|
| 绿色 token 定义 | [colors.xml:26–35](<../../../app/src/main/res/values/colors.xml#L26-L35>) |
| 夜间缺失终端 token | [values-night/colors.xml:1–28](<../../../app/src/main/res/values-night/colors.xml#L1-L28>) |
| 外壳容器取色 | [TerminalActivity.java:127,135,152,174,188,223,272,494](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L127>) |
| 外壳文字取色 | [TerminalActivity.java:156,232,237,254,279,287,296,307,858–868,904,911,949,1301–1375](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1301>) |
| 绿色 drawable | [bg_terminal_primary.xml](<../../../app/src/main/res/drawable/bg_terminal_primary.xml>)、[bg_terminal_primary.xml:13](<../../../app/src/main/res/drawable/bg_terminal_primary.xml#L13>) |
| 会话行 drawable | [bg_terminal_session.xml](<../../../app/src/main/res/drawable/bg_terminal_session.xml>)、[bg_terminal_dot.xml](<../../../app/src/main/res/drawable/bg_terminal_dot.xml>)、[bg_terminal_touch.xml](<../../../app/src/main/res/drawable/bg_terminal_touch.xml>) |
| 悬浮球 | [TerminalFloatingMenu.java:30–41](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L30-L41>)、[bg_terminal_menu.xml](<../../../app/src/main/res/drawable/bg_terminal_menu.xml>) |
| glyph | [TerminalActivity.java:245,253,925,933](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L245>)、[TerminalFloatingMenu.java:30](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java#L30>)、[TerminalTabBar.java:48](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalTabBar.java#L48>) |
| footer 行构建 | [TerminalActivity.java:383–411](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L383-L411>) |
| 宽/短 footer 动作 | [TerminalActivity.java:315–344,349–376](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L315-L344>) |
| 主题控制器 | [ThemeController.java:13–50](<../../../app/src/main/java/com/deepseekharness/app/ui/ThemeController.java#L13-L50>) |
| 主题偏好 | [UiThemePreference.java:5–30](<../../../app/src/main/java/com/deepseekharness/app/util/UiThemePreference.java#L5-L30>) |
| 启动应用主题 | [DshaApp.java:160](<../../../app/src/main/java/com/deepseekharness/app/DshaApp.java#L160>) |
| Main 主题入口 | [MainActivity.java:594–623](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L594-L623>) |
| 系统栏 | [TerminalActivity.java:186–191](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L186-L191>) |
| Activity 注册 | [AndroidManifest.xml:99–102](<../../../app/src/main/AndroidManifest.xml#L99-L102>) |
| 保存状态 | [TerminalActivity.java:1287–1296](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1287-L1296>) |
| 短窗策略 | [TerminalWorkspacePolicy.java:78–88](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java#L78-L88>) |

## 附录 B：建议的 vector pathData

以下仅为路径数据草案（`viewBox 0 0 24 24`，`fillColor=@android:color/transparent`，`strokeColor="#FF000000"`，`strokeWidth=1.6–1.7`，round cap/join）；实施时按仓库既有 `ic_ui_back` 格式落盘。

| 文件 | pathData |
|---|---|
| `ic_terminal_menu.xml` | `M4 6h16M4 12h16M4 18h16` |
| `ic_terminal_pty.xml` | `M3 7a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z` + `M7 9l3 3-3 3M12.5 15H17` |
| `ic_terminal_simple.xml` | `M5 7l4 5-4 5M13 17h6` |
| `ic_terminal_new.xml` | `M12 4v16M4 12h16` |
| `ic_terminal_close.xml` | `M6 6l12 12M6 18L18 6` |
| `ic_terminal_rename.xml` | `M4 20h4L19 9l-4-4L4 16zM14 6l4 4` |
| `ic_terminal_home.xml` | `M3 10l9-7 9 7M5 9v11h14V9M9 20v-7h6v7` |
| `ic_terminal_keyboard.xml` | `M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z` + `M6 9h.01M10 9h.01M14 9h.01M18 9h.01M6 12h.01M10 12h.01M14 12h.01M18 12h.01M7 16h10` |
| `ic_terminal_display.xml` | `M4 6h16M4 12h16M4 18h16` + `M8 3v6M16 9v6M9 15v6` |
| `ic_terminal_help.xml`（如不复用 `ic_about`） | `M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M12 11v6M12 7h.01` |
| `ic_terminal_end_all.xml` | `M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M9 9h6v6H9z` |

复用图标的路径已在仓库中，不需要复制：

- `ic_ui_back`、`ic_settings`、`ic_about`、`ic_terminal`、`ic_recovery_refresh`、`ic_ui_copy`、`ic_ui2_prompt`、`ic_ui2_auto`、`ic_ui2_sun`、`ic_ui2_moon`。

所有新图标均为本仓库预览/本次自绘路径；Termux 固定源码仅作为行为参考（会话/输入/生命周期），未复制其 GPL App 或 `extra-keys` 资源。

## 附录 C：验收勾选表（可直接贴到实施 PR）

- [ ] 浅色：工具栏/侧栏/抽屉/底栏为白/浅蓝灰；输出区纯黑；无任何绿色 token 残留（`grep terminal_` 审查）。
- [ ] 深色：面板近黑、accent 浅蓝；输出区纯黑；与浅色对比度表一致。
- [ ] 三向态：跟随系统/白天/黑夜切换后终端外壳刷新，会话不重建、不关闭、不重放命令。
- [ ] 终端内主题开关：三段可用，选中态正确；`closeAllRunning` 时禁用；重建后侧栏展开/球位置保留。
- [ ] 系统栏：极简浅色、工作台浅色、工作台夜间三套图标都清晰；导航栏始终黑。
- [ ] 图标：12 项语义全部有图标；glyph 只剩 `>_` 品牌和 GONE 旧标签条；所有图标按钮 48dp、有 contentDescription/可见文字。
- [ ] 底栏：6 个动作全在；2×2 主动作 + 输入辅助；短窗退回 3 列×2 行仍可达；无重叠/裁切。
- [ ] 大字体 1.3×、320dp、400×470、900×700、RTL、TalkBack 通过。
- [ ] Robolectric 新增用例通过；LayoutAudit 终端 scene 通过；Lint 0 error；无新增 i18n 基线债。
- [ ] 明确声明未验证范围（Low/旧系统/真机特定 ROM），不以本方案或 HTML 预览代替设备验收。
