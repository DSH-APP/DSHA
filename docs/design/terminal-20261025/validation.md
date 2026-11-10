# 主 Tab 与终端设计原型验证记录

日期：2026-10-25（Asia/Shanghai）。第二轮最终主 Tab 检查结束于 **20:24:58**，终端回归结束于 **20:28:27**。  
源码基线：`Sunset-zhongdua-G` / `02f80abeee37f3b107544714c8544ada5a1c40b9`。  
对象：[交互预览](<preview.html>)、[整合报告](<report.md>)、[实施指引](<implementation-guide.md>)。上一轮 133 条悬浮球验证及原型保存在 [旧验证记录](<before-main-tabs/validation.md>)和[旧原型](<before-main-tabs/preview.html>)，不与本轮结果混用。

## 1. 最终结果

| 检查 | 通过数 | 结果文件 / 复现入口 |
|---|---:|---|
| 三个主 Tab、设置子页、Web 与插件模拟状态、响应式 | **133** | [主页面结果](<validation-main-tabs.json>) / [主页面验证脚本](<check-main-tabs.cjs>) |
| 原有全屏终端、多会话、悬浮球、输入白名单、布局 | **133** | [终端结果](<validation-results.json>) / [终端验证脚本](<check-preview.cjs>) |
| 合计 | **266** | 两套结果都绑定同一最终 HTML SHA，不是两个版本的累加 |

- Chromium `141.0.7390.37`；Playwright `1.56.1`；Ubuntu arm64 容器。
- 两套检查均无页面脚本异常、无外部网络请求。
- 两套均覆盖 6 组 CSS 视口：`320×568`、`390×844`、`740×360`、`840×900`、`1024×768`、`1280×800`。
- 检查是浏览器实际解析 HTML、点击/输入/选择/滚动，不是只匹配源码字符串。
- **仍使用 `page.setContent` 加载文件原文**；本轮没有再验证 `file://` 导航或全部用户文件预览器。没有启动替代 Web 服务，也没有改当前 DSH GUI。
- **未改 Android 业务源码/依赖/运行资产；未运行 Android 单测、Lint、构建、安装、设备控制、真实 PTY、提交或发布。**

最终原型 SHA-256：

```text
b0d23d2d2657835a8e27f72029b48cf54ef7ec58cc48fadf02895cea16e471bb
```

## 2. 本轮新增主页面实际断言

| 范围 | 验证内容 |
|---|---|
| 三个主页面 | 启动/插件/设置不再是占位；六视口内容无页面级横向溢出；四入口持续可达 |
| 子页与终端 | 配置有独立返回；六视口都能保存；子页→终端→悬浮球返回仍在原子页，字段和会话不变 |
| Web 状态 | 准备→鉴权→就绪；启动期间禁重复；停止有取消/确认；停止不关终端、不停计数 |
| 启动失败与门禁 | 显式注入失败时不报就绪；未准备时终端拒绝进入且不新建会话；电脑工作区仍可进入 |
| 安全启动 | 正在运行时先模拟停止再安全启动；普通重启回普通模式，不错误继承安全模式 |
| 运行配置 | 自动端口禁用手填；拒绝桥接保留端口；未保存草稿跨子页保留；下次启动使用已保存端口；运行中改配置不改旧实例端口 |
| 插件数据 | 四个随包源码示例+一个虚构用户条目；不是手机安装清单；搜索、隐藏内置、名称倒序、无匹配空态生效 |
| 插件操作 | 启停 pending 禁用重复操作；详情展开；删除可取消，确认后仅删样例；禁用随包组件有风险确认 |
| 安装来源 | npm、GitHub、HTTPS 压缩包离线识别；拒绝不支持/危险格式；只添加固定模拟样例，不获取远端元数据 |
| 更新与数据诚实性 | 插件/应用更新仍标未检查；组件检测标示例未实测；电脑配对只生成虚构未连接条目；备份流程完成明确没生成归档 |
| 设置 | 省电切换只改预览；11 个语言选项，选择后明确整页仍中文；安装/备份/授权/悬浮条/更新/诊断/关于有对应结构与内容 |
| 大字与焦点 | 320px 两倍根字号三主 Tab 不横溢出；含 select 的对话框焦点不逃出；DOM ID 无重复；新文档重置模拟状态 |

主 Tab 保留滚动位置由代码维护，本轮自动化主要验证导航、草稿和筛选；未做全部滚动高度组合的精确断言。端口原型已按当前 `ConfigInput` 范围与保留端口规则实现，但本轮自动断言主要覆盖保留端口与有效端口，不声称遍历全部整数。

## 3. 原有 133 条终端回归

原有脚本未改业务断言，最终对新 HTML 重新执行通过：

- 默认极简与工作台两模式、全视口覆盖、主壳隐藏、840px 侧栏/抽屉断点。
- PTY/simple 新建切换、取消与确认关闭、组内 ID 递增和展示号复用、全部关闭后空态不偷偷新建。
- 页面返回保留输出/草稿/计数，未知命令不执行，HTML 字符串只作文字。
- 52px 悬浮球、鼠标/浏览器触摸拖动、左右吸边、取消、不误开菜单、不注入输入、方向键/Enter、闲置透明度、焦点回球、模拟键盘避让与窗口变化。
- 窄屏两倍根字号下对话框可达；字号/换行、模拟键盘占位、重新载入归零。

这只是 HTML 模型的状态保留，不证明 Android 后台常驻。浏览器触摸事件不是实体手机操作；模拟键盘不是 Android IME。

## 4. 实际截图与视觉复核

### 本轮三个主页面

| 页面 | 手机 | 平板 |
|---|---|---|
| 启动 | [手机启动](<phone-launch-v2.png>) | [平板启动](<tablet-launch-v2.png>) |
| 插件市场 | [手机市场](<phone-plugins-v2.png>) | [平板市场](<tablet-plugins-v2.png>) |
| 设置 | [手机设置](<phone-settings-v2.png>) | [平板设置](<tablet-settings-v2.png>) |
| 补充 | [手机插件管理](<phone-plugin-management-v2.png>) | [平板运行配置](<tablet-config-v2.png>) |

手机为 390×844、平板为 1024×768。已查看主页面、插件管理和配置截图，检查分组、文字、固定导航和单/双列关系。截图是当前视口，不是完整长页；下方内容可滚动，不应将视口截图误解为子功能被删除。

### 终端原有截图（本轮重新生成）

[手机悬浮球](<phone-floating-ball.png>) · [展开菜单](<phone-floating-menu.png>) · [手机极简](<phone-minimal-preview.png>) · [平板极简](<tablet-minimal-preview.png>) · [手机工作台](<phone-preview.png>) · [平板工作台](<tablet-preview.png>) · [模拟键盘](<phone-keyboard-preview.png>) · [启动页回归截图](<home-preview.png>)。

这些均是 HTML 浏览器截图，不是 APK 安装效果；没有伪造手机系统栏，也没有复制用户原始 Termux 截图。

## 5. 实际失败、评审修正和补跑

### 本轮

1. 编写新增脚本时，首两次静态语法检查暴露了对话框监听器缺少闭合括号；修正后才开始浏览器回归。终端回归先通过 133 条，但该中间结果不替代最终同 SHA 结果。
2. 新主 Tab 首次浏览器检查通过 **121 条**后，失败于“诊断页文案必须超过 80 字”的任意阈值：[首次原始结果](<validation-main-tabs-attempt1.json>)。当时页面无脚本异常，也无外部请求；结构与按钮存在，但诊断页文字较短。测试改为检查对应可见子页结构、非空内容与后续实际操作，不靠凑文案过门槛。
3. 独立只读源码复核发现两处语义偏差，并在最终补跑前修正：普通 Web 重启错误保留安全模式；范围选择错误混入一键备份。最终普通重启恢复普通模式，一键备份固定范围，更多选项才有五种范围。
4. 进一步修正下载源实际选项、端口范围/保留端口规则、随包插件禁用确认、主弹层 select 焦点处理，以及运行配置/运行实例快照分离。
5. 最终主页面 133 条、原终端 133 条全通过；两结果已重新核对 SHA 与当前 HTML 完全一致。保留失败，不称首轮全绿。

### 上一轮的环境限制仍适用

上一轮曾安装独立工具目录的 Playwright/Chromium 和容器运行依赖；`file://` 与 route 履约导航超时，改为 `page.setContent` 后完成验证。原始 [首次导航失败](<validation-first-attempt.json>)、[第二次导航失败](<validation-navigation-attempt.json>)、[悬浮球前结果](<validation-before-floating-ball.json>)及 [旧最终结果](<before-main-tabs/validation-results.json>)仍保留。本轮复用已有工具，没有新增依赖或服务器；导航超时根因仍未充分定位。

## 6. Termux 获取与参考包验证

- 公共 HTTPS 匿名浅 clone 一次成功，退出码 0；固定 `8629e632fcb95da272221be327db653fb24befe9`，不是依赖升级。
- `git fsck --strict --no-reflogs` 通过，上游工作树 pristine；25 个关键文件哈希、引用行号和完整 tracked 索引已核对，未验证作者签名。
- [源码参考包](<<workspace>/termux-ui-reference-8629e632.zip>)包含全部 346 个原始 tracked 文件及原许可，不含 `.git`；ZIP 完整性测试通过，解包字节逐文件与固定检出 SHA-256 相同。
- 参考包大小：1,185,634 bytes；SHA-256：`0d6f19b823c395546c78486c3e4eec1c65976e33d6ad309a01e338eed1d9d673`。
- [来源清单](<termux-source-manifest.json>)记录 clone、临时保留路径及25文件摘要。压缩包是 Lead 在评审后独立封装，清单中的临时路径/原始获取记录未改写为新的获取过程。
- 未执行上游脚本/构建/测试、未下载 APK；上游源只作参考，没有复制到 DSHA 生产目录或编译进 HTML。

## 7. 没有验证或没有实现的部分

- Android TerminalActivity、Main task 栈、预测返回、FGS/通知、真实 PTY/JNI/guest 退出与 UNKNOWN 清理状态。
- 真实中文组合输入、系统键盘/候选栏、TUI resize、剪贴板、TalkBack、全部十语/RTL、OEM 后台限制。
- 插件真实安装/加载/更新、实际 npm 包解析、备份归档生成/恢复、系统授权、真实电脑配对/远程连接。
- 本轮 HTML 不含终端重命名、双输入模式、关闭失败/未知模拟；这些继续留在后续终端改造评估中。
- 所有用户浏览器的文件打开路径；文件卡片预览可能禁用 JavaScript。应下载单文件后使用支持 JavaScript 和原生 dialog 的现代浏览器打开。

## 8. 复现

在已准备 Playwright/Chromium 的独立工具环境、仓库根目录执行：

```bash
PLAYWRIGHT_HOME=/path/to/tooling/node_modules \
  node docs/design/terminal-20261025/check-main-tabs.cjs

PLAYWRIGHT_HOME=/path/to/tooling/node_modules \
  node docs/design/terminal-20261025/check-preview.cjs
```

两脚本只读取本地 HTML 原文，在浏览器中交互并生成截图/JSON，不操作手机或真实终端。会覆盖各自结果与截图；若需保留历史证据，先另存。运行结束均关闭浏览器实例。

## 9. 2026-10-26 三向态与图标化侧栏复验

第三轮只改 HTML 原型与验证脚本，未改 Android 业务代码。终端回归脚本 [check-preview.cjs](<check-preview.cjs>) 在原有 133 条基础上新增 18 条三向态/图标化断言，最终 **151 条通过，0 页面脚本异常、0 外部网络请求**。

```text
finishedAt: 2026-10-26T05:43:48.491Z
previewSha256: 842ffdbb042e026fcddcc56d6f0b2f5504cb8cd1cfe6b139ec2b86fbd6ebc6db
checks: 151
```

### 9.1 新增断言

| 范围 | 实际检查 |
|---|---|
| 三向态 | 默认 `system`；系统浅色/深色分别解析；侧栏 3 个选项与选中态同步；设置面板与侧栏共用同一选择 |
| 颜色合同 | 白天侧栏为白底、强调色蓝通道最高；黑夜侧栏为黑底、强调色蓝通道最高；两套外观下终端输出区都保持 `rgb(0,0,0)` |
| 回归 | 普通点击不会把外观重置为 `system`；重新载入不保留选择；切换外观不新建或更换会话 |
| 侧栏图标化 | 两个分组各有图标；会话行类型图标；底部 4 个动作都有非透明底色与 `svg.icon`；2×2 几何排列 |
| 源码 | 旧绿色 token `#b2dcb9 / #17271c / #44604c` 不再出现 |

主流程截图仍固定模拟系统深色（贴近用户手机），另显式截取白天/黑夜的抽屉、工作台和平板内联侧栏：

| 场景 | 截图 |
|---|---|
| 手机抽屉·白天 | [phone-sidebar-light.png](<phone-sidebar-light.png>) |
| 手机抽屉·黑夜 | [phone-sidebar-dark.png](<phone-sidebar-dark.png>) |
| 手机工作台·白天 | [phone-workbench-light.png](<phone-workbench-light.png>) |
| 平板内联侧栏·白天 | [tablet-sidebar-light.png](<tablet-sidebar-light.png>) |
| 平板内联侧栏·黑夜 | [tablet-sidebar-dark.png](<tablet-sidebar-dark.png>) |
| 主流程（深色） | [phone-minimal-preview.png](<phone-minimal-preview.png>) · [phone-preview.png](<phone-preview.png>) · [tablet-preview.png](<tablet-preview.png>) |

### 9.2 本轮界面变化

- 侧栏头部增加 `>_` 身份块、独立副标题与图标关闭；会话行改为**单行**：类型图标 + `PTY 1` + 状态胶囊（空闲/运行中 N/已暂停 N），不再同时显示 `pty-1` 造成重复；会话 ID 保留在无障碍名称与长按提示中。
- 分组标题增加 PTY/simple 图标和计数胶囊；空态改为虚线框。
- 底部动作改为 2×2 有底色、有图标的按钮；第一个为软主色，其余为表面色；新增三向态分段开关、状态摘要和“返回只关页面”脚注。
- 终端工作台顶栏、对话框、主壳跟随三向态；终端输出区固定黑底。抽屉宽度调整为 344px，宽屏内联侧栏 272–320px。

### 9.3 边界

- 仍是离线 HTML、`page.setContent` 加载原文、浏览器模拟 `prefers-color-scheme`；不是 Android 主题实现、真机对比度或 TalkBack 验收。
- 主 Tab 脚本已重跑：**133 条通过**，`validation-main-tabs.json` 更新；其截图在默认浅色下重新生成，覆盖旧的 `*-v2.png`。
- 悬浮球、触摸、IME 仍是页面内模拟；系统主题切换由浏览器媒体查询驱动，不代表 AppCompat 重建行为。
- 主流程截图固定模拟系统深色；主 Tab 截图（`phone-launch-v2.png` 等）为默认浅色。两者都是同一原型的浏览器渲染，不是手机截图。

## 10. 2026-10-26 原生实现与 CI 验证

第四轮把三向态、图标化与 2×2 底栏落到 Android 原生，不再只是 HTML。实现 commit `1be3f6ab91f25e50ae844cf1a1ca01e775d20df3`，格式清单修订 `fb95eeafd18cbbb2ade15887fc796295970309eb`；[CI run 40294484304](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40294484304) 两个 job 均成功。

### 10.1 自动化结果（CI）

| 检查 | 结果 |
|---|---|
| Standard 全量单测 | **2550 项 / 0 失败 / 0 错误 / 1 允许跳过** |
| 新增 `TerminalThemeTest` | **5 项通过**：日夜 token 与黑底、宽/短窗六动作、主题段选中与结束中禁用、图标描述与矢量 |
| 终端回归类 | `TerminalActivityLifecycleTest` 14、`TerminalFloatingMenuTest` 4、`TerminalInputContractTest` 9、`TerminalNavigationTest` 4 等 13 类通过 |
| 完整夹具 | 7 项实际通过 |
| Lint `lintStandardRelease` | **0 error / 0 fatal / 350 warning**；4 条新 warning 已在发布报告列明，不称零警告 |
| source contracts | 全量格式清单、源码 hygiene、UI i18n、文档链接与 CI 输入回归通过 |

`TerminalThemeTest` 的 XML 结果为 `tests=5 failures=0 errors=0`；收件后独立解压 445 个 XML 重新计数 2550/0/0/1，与 `full-unit-tests.json` 一致。第一次运行 [40294086317](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40294086317) 因新测试未加入 `tools/java-format-files.json` 在 source job 失败；`fb95eea` 补清单后通过，失败原件保留。

### 10.2 收件独立核验

- APK `dsha-terminal-fb95eeaf.apk`，307,898,702 bytes，SHA-256 `8815e6517c52297039156be21c66290a90629e238b6dda07213d55a6ca976d24`，与 Actions artifact digest 一致。
- `apksigner` 本地复验 v3 通过；证书 SHA-256 `8bd5e1e985a5fa443f047c20f9de1fee11f229505dbb2187202233e4a91d4124`（CI 临时测试证书，非 E7E3）。
- `apkanalyzer`/`aapt2` 复核 `dsh.android.client.SunsetRNE2` / 198 / minSdk 30 / targetSdk 37 / arm64-v8a / `debuggable=false`。
- APK 内确认 11 个新 `ic_terminal_*` 图标、12 个新 `bg_terminal_*` 背景（含 active 态）与颜色 token；`terminal_panel` 白天 `#FFFFFF`、夜间 `#0B0F16`，`terminal_panel_text` 白天 `#152033`、夜间 `#E4E8F0`。
- 证据与回执：[delivery.json](<<workspace>/deliveries/native-terminal-theme-20261026/delivery.json>)、[本地签核](<<workspace>/deliveries/native-terminal-theme-20261026/local-apksigner.txt>)、[颜色抽查](<<workspace>/deliveries/native-terminal-theme-20261026/local-color-values.txt>)。

### 10.3 没有验证的部分

- 未安装、未操作手机；真机实际配色、AppCompat 重建、系统栏、IME、TalkBack、RTL、1.3 倍字体与 OEM ROM 未验收。
- 仅 Robolectric 视图合同 + CI Lint + APK 资源抽查，不等于 Android 真机 UI 验收。
- `LayoutAuditInstrumentation` 的终端 scene 扩展、十语种补全、对话框内重复主题开关、差量渲染与图标缓存仍未做。

### 10.4 横屏压缩后续（commit 8d7e4b6）

用户平板截图确认横屏底栏过高后，本批新增 `shortLandscape` 策略、横屏三列两行动作、44dp/10.5sp 紧凑按钮、短标签+完整无障碍名、单行摘要，并隐藏横屏副标题/脚注。CI [run 40306054023](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40306054023) 成功：

- Standard 全量 **2551 项 / 0 失败 / 0 错误 / 1 允许跳过**；`TerminalThemeTest` 6 项（新增横屏用例）、`TerminalWorkspacePolicyTest` 7 项通过。
- Lint `lintStandardRelease` **0 error / 0 fatal / 350 warning**，与上一批 warning 数一致。
- APK `dsha-terminal-8d7e4b62.apk`，307,898,702 bytes，SHA-256 `af91812eae766d889b134d60d0525d701884f55c3406153dc7675dd3d68485a2`；v3 签名证书 SHA-256 `5c58107033ce4ccb4edcfb04389b3a593b9ab48520244726f2d68147771ecc7a`（临时测试证书）。
- 收件后独立解压 445 个 XML，重新计数 2551/0/0/1；`TerminalThemeTest` 为 `tests=6 failures=0 errors=0`。
- 用户随后反馈已覆盖更新并提供新截图（LENOVO TB321FU / Android 16，横屏 2560×1600、竖屏 1600×2560）。横屏截图中副标题/脚注已隐藏、摘要单行、动作区为三列两行短标签，底栏约从侧栏高度 1/2 降到 1/3；竖屏仍保留完整标签和脚注。设备上只有正式包 `dsh.android.client`，测试包 `SunsetRNE2` 未安装，因此这只是用户正式包的截图复核。
- **测试 APK 仍未由我方安装，也未在设备上重新截图**；横屏点击、字体缩放、旋转重建、横竖屏来回切换与 TalkBack 未验收。详见[横屏压缩报告](../../releases/v0.2.1-rc.3-build198-terminal-landscape-20261026.md)。
