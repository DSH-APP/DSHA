# DSHA 原生 UI 改版规范（对齐 DSH 网页）

实现时对照 `/root/工作区/ui-mockups/v2/*.svg`。底部导航顺序保持 **启动 → 插件 → 设置 → 终端**。

## 色板（已写入 `colors.xml` / `values-night/colors.xml`）

来自 DSH `--dsw-*`：

| token | 日间 | 夜间 |
|---|---|---|
| surface 页面底 | `#F9FAFB` | `#151517` |
| card 卡片 | `#FFFFFF` | `#1B1B1C` |
| raised 浅底/输入 | `#F1F3F5` | `#2C2C2E` |
| text | `#0F1115` | `#F5F6F7` |
| text_secondary | `#61666B` | `#ADB2B8` |
| text_muted | `#81858C` | `#979DA6` |
| line | `#E1E5EE` | `#353638` |
| primary 业务蓝 | `#4176E6` | `#7AAAFF` |
| logo_top / 图标浅底 | `#EDF3FE` | `#283142` |
| ok 成功文字（对比度达标） | `#15803D` | `#4ED17E` |
| warn | `#B45309` | `#F7AD31` |
| err | `#C81E1E` | `#F25A5A` |

卡片：白底、圆角 16、**无描边**，靠底色差分层。禁止渐变、禁止硬编码色。同类框必须继续共用 `bg_card` / `bg_btn_primary`（LayoutAudit 像素比对）。

## 层级

- 一页一个主焦点（一个实心主按钮）。
- 次要操作用描边/浅底或文字按钮。
- 破坏性操作用 `err` 文字，不要做成大红实心块。
- 状态用语义色，不要只用灰点。
- 列表行高 ≥ 60dp，触控 ≥ 44dp，对比度 ≥ 4.5。
- 顶栏：当前页标题（22sp 粗体）+ 右侧图标；根 Tab 不显示 logo。
- 子页保留返回箭头，标题用功能名。

## 硬约束

- 不改 pinned launcher 文件（`core/HarnessController.java`、`util/TerminalTabs.java` 等 descriptor 列表）。
- 保留现有 view id，删除会让 debug/androidTest 崩溃。
- 中文文案：布局用 `@string`；Java 用 `UiText.text` / `UiText.choose`，禁止 `setText("中文")`。
- 新 XML 字符串同时写 `values/` 与 `values-en/`。
- 不伪造后端数据：没有的状态就不显示。
- 提交信息：中文 + `type:` 前缀。
- 只改自己范围内的文件。
