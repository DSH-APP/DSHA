# v5 设计 token 与本机 Android 映射

来源：`src/base.html` 内联样式、`src/atelier.css`、`src/refinement.css`、`src/polish-*.css`（v5 离线原型，`interaction-v5`）。  
方法：用 Python 按 `build.py` 的拼接顺序解析 9 段样式，合并 `.app` 与 `.app.light` 的自定义属性；再用脚本把 CSS 颜色换算成 Android 的 8 位 ARGB。  
性质：**设计落点与工作区现有资源的核对，不是当前 APK 的渲染结果。** 工作区已有 Lead 未提交的 `values/shell_ui.xml`、`values-night/shell_ui.xml`（`git status` 为 `??`），本文第 3 节按这两个实际文件对齐，不再另起一套提案名。

## 1. 先说级联事实：token 不在一个文件里

任务描述称从 `atelier.css` 提取 `--bg/--surface/...`，实算不是这样：

| 层 | 文件 | 贡献 |
|---|---|---|
| 第 1 层 | `src/base.html` 内联 `<style>` | 17 个基础 token：`--bg`、`--surface`、`--lift`、`--line`、`--strong-line`、`--text`、`--muted`、`--subtle`、`--blue`、`--blue-rgb`、`--ink`、`--glass`、`--sheet`、`--green`、`--amber`、`--red`、`--shadow` |
| 第 2 层 | `src/atelier.css` | 新增 7 个派生/辅助 token：`--blue-soft`、`--blue-mid`、`--tone-bg`、`--frost-edge`、`--font-copy:14px`、`--row-space:16px`、`--nav-glow` |
| 第 3 层 | `src/refinement.css` | 覆盖 10 个基础色（`--bg`、`--surface`、`--lift`、`--line`、`--strong-line`、`--text`、`--muted`、`--subtle`、`--glass`、`--sheet`）+ `--font-copy:15px`；新增 `--edge-light`、`--well`、`--control-radius:13px` |
| 第 4 层 | `src/polish-*.css` | 不重定义 `.app` token；`polish-select.css` 给选择弹层单独一套 7 个局部 token |

按 `build.py` 的拼接顺序（`base.html` 的 marker 处依次插入 atelier → refinement → polish-*），最终 `.app` 有 **27 个自定义属性**，`.app.light` 显式声明 **22 个**（继承 5 个），两者实际生效都是 27 个。  
所以：**只抄 `atelier.css` 会拿到 7 个 token；只抄 `base.html` 会拿到旧的一层配色；必须按级联合并。** 本文第 2 节是合并后的最终值。

## 2. 最终生效的深浅两套颜色

下表“原值”是 CSS 里的最终字面值；“ARGB”已按 Android `#AARRGGBB` 换算。注意 CSS 的 8 位十六进制是 `#RRGGBBAA`，与 Android 的 `#AARRGGBB` 字节序相反，不能直接照抄。

| CSS token | 深色原值 | 深色 ARGB | 浅色原值 | 浅色 ARGB |
|---|---|---|---|---|
| `--bg` | `#0b0f16` | `#FF0B0F16` | `#f3f6fb` | `#FFF3F6FB` |
| `--surface` | `#131923` | `#FF131923` | `#fcfdff` | `#FFFCFDFF` |
| `--lift` | `#1b2432` | `#FF1B2432` | `#e7edf6` | `#FFE7EDF6` |
| `--line` | `#cbdcff12` | `#12CBDCFF` | `#1d3a6013` | `#131D3A60` |
| `--strong-line` | `#d0e1ff20` | `#20D0E1FF` | `#1d3a6025` | `#251D3A60` |
| `--text` | `#edf1f7` | `#FFEDF1F7` | `#18273f` | `#FF18273F` |
| `--muted` | `#b0bbcc` | `#FFB0BBCC` | `#50617a` | `#FF50617A` |
| `--subtle` | `#8895aa` | `#FF8895AA` | `#61728d` | `#FF61728D` |
| `--blue` | `#6aa4ff` | `#FF6AA4FF` | `#1e50d0` | `#FF1E50D0` |
| `--ink` | `#091729` | `#FF091729` | `#fff` | `#FFFFFFFF` |
| `--glass` | `rgba(25,34,48,.86)` | `#DB192230` | `rgba(250,252,255,.88)` | `#E0FAFCFF` |
| `--sheet` | `rgba(17,24,35,.98)` | `#FA111823` | `rgba(252,253,255,.99)` | `#FCFCFDFF` |
| `--green` | `#93ccaf` | `#FF93CCAF` | `#287453` | `#FF287453` |
| `--amber` | `#e4be83` | `#FFE4BE83` | `#8c600d` | `#FF8C600D` |
| `--red` | `#eea3a6` | `#FFEEA3A6` | `#af404b` | `#FFAF404B` |
| `--shadow` | `#0007` | `#77000000` | `#182a4420` | `#20182A44` |
| `--well` | `#05090e` | `#FF05090E` | `#dfe7f2` | `#FFDFE7F2` |
| `--tone-bg` | `#0d121b` | `#FF0D121B` | `#edf1f8` | `#FFEDF1F8` |
| `--frost-edge` | `#ffffff13` | `#13FFFFFF` | `#ffffffbb` | `#BBFFFFFF` |
| `--edge-light` | `#e4edff23` | `#23E4EDFF` | `#fff` | `#FFFFFFFF` |

几个容易抄错的点：

- `--shadow` 深色是 4 位十六进制 `#0007`，按 CSS 规则展开为 `rgba(0,0,0,.467)`，即 `#77000000`；浅色是 8 位 `#182a4420`，变成 ARGB 是 `#20182A44`。这不是笔误，两套阴影强度本来不同。
- `--glass` / `--sheet` 是半透明层，ARGB 的 alpha 已经按 `round(alpha × 255)` 换算；如果 Android 侧用 `ColorUtils.setAlphaComponent`，要保持同样的 alpha 基准。
- 深色的 `--line`/`--strong-line` 是**白色低透明度**（`#12CBDCFF` / `#20D0E1FF`），浅色是**深蓝低透明度**（`#131D3A60` / `#251D3A60`）。不要只改 RGB、复用同一个 alpha。
- 选择弹层（`pselect-studio-layer`）另有 7 个局部 token（`--text #edf2fb`、`--subtle #9daabd`、`--blue #8bb9ff`、`--blue-soft rgba(106,164,255,.09)`、`--sheet rgba(19,26,38,.97)`、`--line rgba(184,207,241,.08)`、`--strong-line rgba(166,197,244,.19)`），只在弹层作用域生效，不属于全局 `.app`。

前表列出的是颜色与阴影；`.app` 的 27 个属性里另有 7 个未单列：`--blue-rgb`、`--blue-soft`、`--blue-mid`、`--nav-glow`（派生色/透明度），以及 `--font-copy`、`--row-space`、`--control-radius`（字号/间距/圆角，见后文）。

## 3. 到 Android `shell_*` 的映射（按工作区实际资源）

工作区实际状态（`git status --porcelain` 实查，均为未提交 `??`）：

- `app/src/main/res/values/shell_ui.xml`：**17 个 `shell_*` 颜色 + 20 个 `shell_*` 尺寸**。
- `app/src/main/res/values-night/shell_ui.xml`：与日间一一对应的 **17 色 + 20 尺寸**。
- `app/src/main/res/values/shell_launch.xml`：启动主页专用 **25 个 `shell_launch_*` 尺寸 + 16 个样式**。
- 另有 `color/shell_*_tint.xml` 与 `drawable/bg_shell_*.xml` 等未提交资源。
- `values/colors.xml` / `values-night/colors.xml` 仍只有 23 / 20 个 `terminal_*` 与通用 `primary/surface/...`，没有混入 `shell_*`。

`shell_ui.xml` 的文件注释也写明：“数值取自设计源 base.html 的 .app(light) 与 refinement.css 覆盖层；层级色走 refinement 的 bg/surface/lift，强调色走设计源的 blue。”这与第 1–2 节的级联分析一致。

| CSS token | 实际资源名 | 深色 ARGB | 浅色 ARGB | 对齐说明 |
|---|---|---|---|---|
| `--bg` | `shell_bg` | `#FF0B0F16` | `#FFF3F6FB` | 完全一致 |
| `--surface` | `shell_surface` | `#FF131923` | `#FFFCFDFF` | 完全一致 |
| `--lift` | `shell_raised` | `#FF1B2432` | `#FFE7EDF6` | 名字是 raised，不是 lift |
| `--line` | `shell_line` | `#12CBDCFF` | `#131D3A60` | 完全一致 |
| `--strong-line` | `shell_line_strong` | `#20D0E1FF` | `#251D3A60` | 完全一致 |
| `--text` | `shell_text` | `#FFEDF1F7` | `#FF18273F` | 完全一致 |
| `--muted` | `shell_text_secondary` | `#FFB0BBCC` | `#FF50617A` | **CSS muted → Android secondary**，最容易接错 |
| `--subtle` | `shell_text_muted` | `#FF8895AA` | `#FF61728D` | **CSS subtle → Android muted**，与上一行交叉 |
| `--blue` | `shell_accent` | `#FF6AA4FF` | `#FF1E50D0` | 完全一致 |
| `--ink` | `shell_on_accent` | `#FF091729` | `#FFFFFFFF` | 完全一致 |
| `--glass` | `shell_glass` | `#DB192230` | `#E0FAFCFF` | 完全一致（alpha 已换算） |
| `--sheet` | `shell_nav_bg` | `#F2131923` | `#FAFCFDFF` | **不是等值替换**：导航底用了自己的不透明度（深色 alpha F2 ≠ sheet 的 FA） |
| `--green` | `shell_good` | `#FF93CCAF` | `#FF287453` | 名字是 good，不是 success |
| `--amber` | `shell_warn` | `#FFE4BE83` | `#FF8C600D` | 名字是 warn，不是 warning |
| `--red` | `shell_bad` | `#FFEEA3A6` | `#FFAF404B` | 名字是 bad，不是 danger |

工作区还额外建了两个派生色（CSS 没有同名 token）：

| 资源名 | 深色 ARGB | 浅色 ARGB | 推导 |
|---|---|---|---|
| `shell_accent_soft` | `#146AA4FF` | `#141E50D0` | `shell_accent` + alpha `0x14`（约 8%） |
| `shell_nav_ripple` | `#1F6AA4FF` | `#1F1E50D0` | `shell_accent` + alpha `0x1F`（约 12%） |

CSS 里存在、但工作区**还没有**对应 `shell_*` 资源的 token：

| CSS token | 深色 ARGB | 浅色 ARGB | 建议 |
|---|---|---|---|
| `--shadow` | `#77000000` | `#20182A44` | 不新增颜色也可用 `elevation`；若新增，命名需与 `shell_*` 一致 |
| `--well` | `#FF05090E` | `#FFDFE7F2` | 终端/凹陷底；当前终端仍用 `terminal_*` |
| `--tone-bg` | `#FF0D121B` | `#FFEDF1F8` | 同色系底，可暂缓 |
| `--frost-edge` | `#13FFFFFF` | `#BBFFFFFF` | 顶部内高光，可由 drawable 实现 |
| `--edge-light` | `#23E4EDFF` | `#FFFFFFFF` | 边缘高光，可暂缓 |
| `--blue-soft` / `--blue-mid` / `--nav-glow` | 均以 `--blue-rgb` 派生 | 同左 | 用 `shell_accent` + alpha 在代码里算，不新增资源 |

落地注意：

1. `shell_ui.xml` 是当前工作区**未提交**文件；文档、代码评审和 CI 看到的状态可能不同，提交前先确认它属于 build202 还是后续批次。
2. `shell_*` 与 `terminal_*` 是两套命名：前者是应用外壳，后者是终端外壳。不要直接把 `terminal_*` 改成 `shell_*` 别名；如需统一，先跑 `TerminalThemeTest` 等主题回归。
3. 半透明色已经是 8 位 ARGB（如 `shell_glass #DB192230`），XML `<color>` 可直接使用；不要再用 `#RRGGBBAA` 的 CSS 顺序写。
4. CSS 的 `--muted`/`--subtle` 与 Android 的 `shell_text_secondary`/`shell_text_muted` 是交叉命名；接代码时以本表为准，不要按单词直译。
5. 尺寸不要另起一套：工作区已有 `shell_nav_*`、`shell_header_*`、`shell_rail_*`、`shell_launch_*`，见第 4–6 节。

## 4. 字号

原型以 CSS px 表达，Android 建议按 `sp` 近似（1 CSS px ≈ 1dp 的视觉基准，但 Android 字体缩放必须独立验证）。

| 角色 | CSS 规则/值 | 建议 Android |
|---|---|---:|
| 应用基准 | `.app { font-size:15px }` | 15sp |
| 正文变量 | `--font-copy:15px`（refinement 覆盖 atelier 的 14px） | 15sp |
| 段落 | `.app p { font-size:var(--font-copy)!important }` | 15sp |
| 维护描述 | `.maint-description` 另有 `14px!important` | 14sp |
| 页面 H1 | `.page-head h1 { 28px }` | 28sp |
| 主 H1 | `.app h1 { 32px }`；首页 42px；维护标题 34px | 32/34/42sp，按页面 |
| H2 | `23px` | 23sp |
| H3 | `16px` | 16sp |
| 小字 | `.small { 12px }` | 12sp |
| 眉题 | `.eyebrow { 10px }`（atelier 后 10px，基础 11px） | 10–11sp |
| 导航标签 | `.app-nav button { 10px }` | 10sp，配 44dp 触控 |
| 主按钮 | `.primary { 14px }` | 14sp |
| 次按钮 | `.secondary { 13px }` | 13sp |
| 终端输出 | `.terminal-screen { font-size:var(--terminal-font,13px) }` | 原生默认 13sp，范围 8–24sp（`PtyTerminalFragment`） |
| 终端附加键 | HTML `12px`；原生密集态 `10.5sp` | 10.5–12sp，行高 44dp |

移动端还有覆盖：`page-head h1 26px`、`home-hero h1 31px`、`maint-title 29px`、终端键 `12px`。做原生时不要把桌面值直接搬进手机。

## 5. 圆角

| 用途 | CSS 值 | 建议 dp |
|---|---:|---:|
| 设备演示外框 | 26px | 不用于应用内 |
| 导航项 | 15px | 16dp |
| 主按钮 | 14px | 14dp |
| 次按钮 / 提示 | 12px | 12dp |
| 控件（refinement） | `--control-radius: 13px` | 13dp |
| 输入框 | 10px | 10dp |
| 设置行 | 9px 9px 0 0 | 9dp |
| chip / 标签 | 7px | 7–8dp |
| 桌面抽屉 | 0（贴边） | 0 |
| 居中弹窗 | 23px | 24dp |
| 手机底部面板 | 24px 24px 0 0 | 24dp 顶部 |
| 开关 | 99px | 胶囊 |
| 会话选中条 | 2px 宽 | 2dp |

## 6. 间距与触控

| 项 | CSS 值 | 建议 |
|---|---:|---|
| 页面内边距（宽） | `36px 40px 38px` | 36/40dp |
| 页面内边距（≤959） | `28px` | 28dp |
| 页面内边距（手机） | `25px 23px` | 24/23dp |
| 设置行上下 | `--row-space:16px`（舒适）/ 11px（紧凑） | 16/11dp |
| 列表行最小高 | 64–78px | 64–78dp |
| 触控最小高 | 44px | 44dp（不要低于） |
| 主按钮最小高 | 48px | 48dp |
| 导航项宽 | 60px（宽）/ 65px（手机） | 60/64dp |
| 弹层头 | `23px 24px 16px` | 24dp |
| 弹层底 | `16px 24px 22px` | 16/24/22dp |
| 终端输入行 | `min-height:44px` | 44dp |
| 终端附加键 | 44px 高、7 列 | 44dp，手机 7 列 × 2 行 |
| 终端侧栏宽 | 158px（HTML） | 原生内联 240–296dp，抽屉上限 336dp |

### 6.1 工作区已建的实际尺寸（`shell_ui.xml` / `shell_launch.xml`）

`values/shell_ui.xml` 与 `values-night/shell_ui.xml` 各有 **20 个 `dimen`**，日间/夜间同值：

| 组 | 资源名 | 值 |
|---|---|---:|
| 手机胶囊导航 | `shell_nav_height` / `shell_nav_margin_horizontal` / `shell_nav_margin_bottom` | 64dp / 12dp / 10dp |
| 手机胶囊导航 | `shell_nav_icon_size` / `shell_nav_label` / `shell_nav_item_padding_top` / `shell_nav_item_padding_bottom` | 22dp / 10sp / 8dp / 6dp |
| 顶栏 | `shell_header_min_height` / `shell_brand_size` / `shell_brand_icon_size` | 60dp / 34dp / 18dp |
| 顶栏 | `shell_title_text` / `shell_context_text` | 16sp / 11sp |
| 平板左栏 | `shell_rail_width` / `shell_rail_padding_horizontal` | 80dp / 7dp |
| 平板左栏 | `shell_rail_item_width` / `shell_rail_item_height` / `shell_rail_item_gap` | 56dp / 64dp / 8dp |
| 平板左栏 | `shell_rail_icon_size` / `shell_rail_label` / `shell_rail_foot` | 22dp / 10sp / 8sp |

`values/shell_launch.xml` 另有 **25 个 `shell_launch_*` 尺寸 + 16 个样式**，关键值：

| 用途 | 资源名 | 值 |
|---|---|---:|
| 启动主页内边距 | `shell_launch_page_pad` / `_top` / `_bottom` | 20dp / 18dp / 30dp |
| 区块/控件间距 | `shell_launch_section_gap` / `_control_gap` / `_metric_gap` | 22dp / 10dp / 8dp |
| 活动条高 | `shell_launch_activity_height` | 2dp |
| 卡片/状态/胶囊圆角 | `shell_launch_radius_card` / `_status` / `_pill` | 18dp / 16dp / 999dp |
| 行/胶囊/主次按钮高 | `shell_launch_row_height` / `_pill_height` / `_primary_height` / `_secondary_height` | 54dp / 36dp / 52dp / 46dp |
| 日志区高 | `shell_launch_log_height` | 184dp |
| 字号阶梯 | `shell_launch_title` / `_body` / `_meta` / `_caption` | 28sp / 13sp / 12sp / 11sp |
| 其他字号 | `shell_launch_row_text` / `_value` / `_button_text` / `_log_text` | 14sp / 18sp / 14sp / 12sp |

这些值比第 4–6 节的通用建议更权威：**能引用 `shell_*` 尺寸就不要另写 dp/sp 字面量。**

## 7. 动效

| 名称 | 值 | 位置/含义 |
|---|---|---|
| 切页主过渡 | **220ms** `cubic-bezier(.2,.7,.2,1)`，位移 6px + 透明度 | `src/polish-shared.js` 的 `psharedAnimateScene`；`verify_iteration.py` 断言 `duration==220` |
| `pageIn` | **280ms** `cubic-bezier(.2,.8,.2,1)`，Y+7px | `.page` 首屏；手机底部面板复用 `.32s` |
| `drawerIn` | **320ms** `cubic-bezier(.2,.8,.2,1)`，X+35px | 右侧抽屉 |
| `dialogIn` | **250ms** `ease-out`，缩放 .98→1 | 居中弹窗 |
| `sweep` | **2600ms** `ease-in-out infinite`，X -100%→400% | 维护活动条 |
| `toastIn` | **200ms** | 轻提示 |
| `fadeIn` | **200ms** | 遮罩 |
| View Transition | 380ms（任务标记）/ 280ms（导航）/ 280ms（内容） | 页面级过渡 |
| 按钮/控件 | 180ms 背景、120ms 位移、200ms 开关 | 交互反馈 |

要点：

- **220ms 只在 JS 里出现一次**（`polish-shared.js` 第 8 行），CSS 里没有 `220ms` 字面量；CSS 的页面入场是 `pageIn .28s`。引用“220ms”时应注明是切页脚本动画，不是所有页面动画。
- 减少动态效果时：`state.settings.reduce`、`prefers-reduced-motion`、弹层打开时直接跳过 220ms 动画；CSS 另有 `.reduce-motion` 与 `@media(prefers-reduced-motion:reduce)` 全量关闭。
- Android 建议用 `ViewPropertyAnimator`/`Animator` 复刻 220ms 透明度+6dp 位移，不要用 `AnimationUtils` 的默认时长；同时尊重系统“移除动画”设置。

## 8. Android 落地清单

1. `shell_*` 颜色已经在工作区 `values/shell_ui.xml` / `values-night/shell_ui.xml`（未提交）；落地时先确认是否随本批提交，不要在 `colors.xml` 里再复制一组同名值。
2. 尺寸同理：优先引用 `shell_nav_*`、`shell_header_*`、`shell_rail_*`、`shell_launch_*`；页面边距、44dp 触控、终端侧栏等缺失项再补 `dimens.xml`。
3. 字号用 `sp`，并在 1.3× / 2× 字体下检查列表行高与底部动作区。
4. 半透明 `shell_glass`/`shell_nav_bg` 在 Android 上不要假设 `backdrop-filter: blur()` 可直接等价；低版本/低性能设备应回退为不透明 `shell_surface`（与原型 `@supports not (backdrop-filter: blur(1px))` 的回退一致）。
5. 不引入动态取色（Material You）覆盖品牌色；v5 的蓝色是固定品牌语义。
6. 修改后跑现有的 `TerminalThemeTest`/主题回归，避免 `terminal_*` 与 `shell_*` 两套主题互相污染。

## 9. 暂缓与风险

- **命名/提交状态**：`shell_*` 已在工作区存在，但是 `git status` 的 `??` 未提交资源（`values/shell_ui.xml`、`values-night/shell_ui.xml`、`shell_launch.xml` 及 `bg_shell_*`）。提交前必须确认归属批次；同时 `terminal_*` 已有 23/20 个且被终端主题测试绑定，两套命名并存是最大风险。
- **交叉命名**：CSS `--muted` 对应 `shell_text_secondary`，CSS `--subtle` 对应 `shell_text_muted`；按单词直译会接反。`--lift` 对应 `shell_raised`，`--green/amber/red` 对应 `shell_good/warn/bad`。
- **缺失 token**：`--shadow`、`--well`、`--tone-bg`、`--frost-edge`、`--edge-light` 没有对应 `shell_*` 资源；不要在代码里假设它们存在。
- **模糊/玻璃**：CSS 的 `backdrop-filter:blur(20–24px)` 在 Android 上没有等价的一行实现；本试点最多做半透明色 + elevation，不承诺同款磨砂。
- **阴影**：CSS 的 `box-shadow` 多层大扩散无法逐像素移植；工作区没有 `shell_shadow`，建议先用 `elevation` 近似，若要新增颜色再按 `shell_*` 命名并在真机确认深色对比度。
- **字体**：原型用 `Inter`，仓库 Android 侧使用系统字体；不要为了字形一致额外打包字体，除非单独评审许可与包体。
- **220ms 单一来源**：如果后续把切页动画改回 CSS，必须同步更新验证断言，否则 724 项中的 iteration-v5 断言会失效。
- **本文未验证**：未在 Android 上渲染、未做对比度测量、未跑 TalkBack/字体缩放/深色切换真机验收。
