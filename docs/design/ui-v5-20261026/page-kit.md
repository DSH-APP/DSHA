# UI v5 原生页面组件（2026-10-27 替换批次）

分支：`Sunset-3090-Q`  
范围：把 build202 的「外壳 + 启动页」试点继续推进到设置、插件、运行配置、安装与环境、设备授权、数据与备份、更新、诊断、欢迎与内页。**不新增原型模拟数据，不改变任何真实功能。**

## 1. 为什么需要页面组件层

build202 只替换了顶栏、手机胶囊导航、平板左栏和启动主页；其余页面仍用旧的白色画布 / 灰色卡片 / 22sp 标题。继续逐页复制样式会让同一个「设置行」在 8 个页面出现 8 套尺寸。

本批把 v5 的视觉规则收进一层可复用组件：

| 层 | 文件 | 作用 |
|---|---|---|
| 颜色 / 外壳 token | `values/shell_ui.xml`、`values-night/shell_ui.xml` | 画布、surface、描边、文字、强调色、状态色；新增 `shell_*_soft` 状态底 |
| 组件样式与尺寸 | `values/shell_page.xml` | 页面标题 / 说明、分组标签、卡片、行、输入、开关、主次/危险按钮、分段控件 |
| drawable | `bg_shell_card` / `bg_shell_input` / `bg_shell_danger` / `bg_shell_segment` | 18dp 卡片、13dp 输入与控件、软危险底、分段选中态 |
| 程序化页面骨架 | `ui/CardPage.java` | 统一标题、卡片、行、键值行、页脚按钮；对话框用 compact 模式 |
| 内页返回条 | `ui/UiNavigation.java` | 只保留返回键，避免「顶栏标题 + 页面大标题」重复 |

## 2. 通用 token 与旧页面

`values/colors.xml` 和 `values-night/colors.xml` 的通用层级色已对齐 `shell_*`：

| 通用资源 | 新值（浅色 / 夜间） | 对应 v5 |
|---|---|---|
| `surface` | `#F3F6FB` / `#0B0F16` | 画布 `shell_bg` |
| `card` | `#FCFDFF` / `#131923` | 卡片 `shell_surface` |
| `raised` | `#E7EDF6` / `#1B2432` | 凹槽 `shell_raised` |
| `text` | `#18273F` / `#EDF1F7` | `shell_text` |
| `text_secondary` | `#50617A` / `#B0BBCC` | `shell_text_secondary` |
| `text_muted` | `#61728D` / `#8895AA` | `shell_text_muted` |
| `line` | `#131D3A60` / `#12CBDCFF` | `shell_line` |

这样，尚未逐页重排的旧布局（文件、备份、技能、任务、恢复等）也会跟随 v5 画布、文字与 18dp 卡片，不再出现白色底 + 灰色卡片两套视觉。终端继续使用 `terminal_*`，不受这批通用色影响。

## 3. 本批替换的页面

| 页面 | 落点 | 处理 |
|---|---|---|
| 设置 | `fragment_settings.xml` + `SettingsFragment` | 页面标题 / 说明；运行环境、使用体验、支持三组卡片；语言、悬浮条、省电、更新、自检、关于 |
| 插件 | `fragment_plugins.xml`、`plugin_list_header/footer.xml`、`item_plugin.xml` | 页面标题；市场/已安装分段；社区入口与链接安装卡片；筛选、排序、本地导入导出与插件卡片全部 v5 |
| 运行配置 | `fragment_config.xml` | 26sp 标题、13sp 说明、12sp 分组标签、14sp 开关文字、v5 保存按钮 |
| 安装与环境 | `fragment_install.xml` | 状态卡、六步组件行、维护入口、检查按钮与危险卸载按钮 |
| 设备能力授权 | `fragment_device_grants.xml` | 通道卡、系统权限卡、敏感能力卡与 v5 排版 |
| 数据与备份 | `fragment_workspace.xml` | 分组卡片、行、维护操作按钮 |
| 更新 / 诊断 | `activity_update.xml`、`activity_diagnostics.xml` | 标题、说明、分组标签、行与按钮 |
| 欢迎 / 首启 | `activity_welcome.xml`、`welcome_page1..3.xml`、`OnboardingUi` | 画布、标题、说明、行卡与主次按钮 |
| 关于 | `dialog_about.xml`、`AboutFragment` | 对话框卡片与按钮 |
| 未完成环境 | `EnvironmentRecoveryFragment` | 改用 CardPage，不再手写 TextView/Button |
| 其它 CardPage 内页 | 存储、技能、任务、自动备份、数据文件、应急等 | 自动获得 v5 骨架与卡片 |

## 4. 边界

- 本批只替换视觉与组件，不接入原型里的模拟数据、模拟终端、模拟备份或模拟权限。
- 文件编辑、备份逐文档比较、选择性恢复等原型子能力仍沿用现有实现。
- 未在真机验收 TalkBack、1.3× / 2× 字体、横竖屏与深色切换；以 CI 资源编译与全量 Standard 单测为软件边界。
