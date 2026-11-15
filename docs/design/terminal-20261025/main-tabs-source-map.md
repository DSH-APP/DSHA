# 启动 / 插件 / 设置：当前源码到 HTML 原型的内容映射

> **审阅范围：源码存在 ≠ 手机实测。** 本文由 `tabs-review` 执行 shared task `task-2`，先 get/claim，再只读核查。仅新增本文；不改生产代码、其他文档或 HTML，不构建、不操作手机、不访问私有远端、不提交。

## 0. 依据、标记与给 Lead 的结论

- 核查日期：2026-10-25（UTC）。本地分支 `Sunset-zhongdua-G`，HEAD `02f80abeee37f3b107544714c8544ada5a1c40b9`；核查开始时工作树只有未跟踪的设计目录。本文不把该目录中的并行改动归为本任务修改。
- 已先读 [AGENTS.md][agents]、[README.md][readme]，另读 [安全模型][security]；页面结论优先采用当前 Java + XML + 字符串实际接线，不能从文档里的历史条目倒推当前入口。
- 构建脚本声明 `dsh.android.client` / `versionCode 197` / `0.2.1-rc.3-20261025.2000-dsh0.2.1-rc.3`，仅是**检出源码身份**，不是当前手机安装版本；见 [build.gradle:55–85][build].
- **[现有]**：当前检出源码有布局、文案及事件接线；未主张手机上已授权、已安装、已启动或验证成功。
- **[模拟]**：HTML 可仿真该现有功能的界面反馈，但没有 Android、进程、文件、插件仓库或服务端副作用。
- **[建议]**：为预览完整性或体验新增的组织/控件，不得描述成 Android 已有实现。

**优先填充顺序**：启动状态与操作 → 插件市场/管理实页 → 设置根目录 → 运行配置与数据备份子页。保留现有终端演示，不再让“插件”“设置”只有导航占位。首页“主终端”捷径可继续作为设计建议保留，但不能冒充原生启动页既有按钮。

**最重要的名称纠正**：

1. 原生有四个底部入口：启动 / 插件 / 设置 / 终端；“三个主 Tab”是本次核查的三个非终端页，不是删除终端。
2. 插件顶部第二段叫 **插件管理**，其内容标题是 **已安装插件**，不是两个名称混用。
3. 设置前两项实际显示 **安装与环境**、**运行配置**，虽然 `TAB_OPTIONS` 的内部标题还是“安装”“配置”。
4. `WorkspaceFragment` 是 **数据与备份**；首页 **电脑工作区** 进入的是 remote 包的 `WorkspaceActivity`，不是同一页。
5. 原生运行配置**不编辑模型 / API Key**；应提示到 Web UI 配置。不能因为 `ConfigStore` 仍保留凭据 API 就恢复旧原生表单。

## 1. MainActivity：外壳、层级与门禁

| 标记 | 真实内容与行为 | 证据 / HTML 对应 |
|---|---|---|
| 现有 | 四入口固定顺序为启动、插件、设置、终端。 | [bottom_nav_menu.xml:5–21][nav-xml]；[MainActivity:350–411][main-nav]。HTML 保留四入口，终端仍转入既有演示。 |
| 现有 | 顶栏有 DSHA、主题切换、关于；下载记录按钮由 Java 动态插入；有隐藏的环境恢复横幅。 | [activity_main.xml:3–18][main-xml]；[MainActivity:278–311][main-actions]、[344–348][main-about]、[579–605][main-theme]。不能只查 XML 就漏掉“下载记录”。 |
| 现有 | 重复点当前根 Tab 不重建页面，保留输入/滚动；切根 Tab 先清子页 back stack，然后 replace Fragment。 | [MainActivity:357–411][main-nav]。建议 HTML 根页与子页独立记录，避免返回后丢过滤条件。 |
| 现有 | 设置子页通过 back stack 返回；子页显示返回箭头与具体标题，根页标题仍为 DSHA。 | [SettingsFragment:249–257][settings-navigation]；[MainActivity:674–697][main-subpages]。 |
| 现有 | 系统返回：有子页则 pop；根页退出 UI，但不由这次返回停止已有 Web/运行环境。 | [MainActivity:96–114][main-back]。HTML 返回主界面不应把终端演示会话关闭。 |
| 现有 | 未准备本机环境也能进入 Main 首页和电脑工作区；只有请求本机动作才进 Welcome/准备流程。其他底部入口在 `localSetupRequired` 时导向本机准备。 | [MainActivity:90–145][main-entry]、[240–267][main-gate]、[353–355][main-nav]。此处应以当前代码为准，不沿用旧文档“未 welcomed 一律先 Welcome”的绝对说法。 |
| 现有 | 环境需恢复时，插件/终端可由 EnvironmentRecoveryFragment 替代，不能用原型绿色状态掩盖门禁。 | [MainActivity:384–406][main-nav]。 |
| 建议 | 增加统一“源码映射 · 仅本页模拟”标识，以及预览态选择器。 | 这是 HTML 专用辅助，不是 Android 原生控件。状态选项应显式标“示例”，不可叫“检测结果”。 |

## 2. 启动 Tab：LaunchFragment

布局是纵向滚动页，顺序为状态操作卡 → 电脑工作区卡 → 两个指标 → 访问地址 → 日志与恢复操作；见 [fragment_launch.xml:3–62][launch-xml]。

| 标记 | 当前真实页面内容 | 推荐预览交互 / 边界 | 准确证据 |
|---|---|---|---|
| 现有 + 模拟 | 状态卡：`DSH 未运行`、启动 DSHA、重启、停止；就绪时主按钮变为“进入工作台”。 | HTML 点击启动只能演示“启动中→模拟就绪”；“进入工作台”不能误接成系统终端。若无 Web 演示页，应给明确说明而非假称已进入实际 DSH。 | [LaunchFragment:160–208][launch-actions]、[426–514][launch-state]；[ui_iteration.xml:3–12][launch-labels]。 |
| 现有 + 模拟 | 首装态“手机环境尚未准备 / 准备手机环境”；隐藏状态明细、重启/停止、指标、快捷入口、日志面板。 | 可做切换示例；保留电脑工作区入口，不要默认显示本机 READY。 | [LaunchFragment:92–127][launch-setup]。 |
| 现有 + 模拟 | 电脑工作区：配对电脑、使用电脑上的 DSH，不需先安装手机环境。 | 打开原型的电脑工作区介绍/列表；默认“未读取已配对电脑”，不可虚构主机名、ID、延迟或连接成功。 | [LaunchFragment:92–101][launch-setup]；[WorkspaceActivity:48–62][remote-entry]。 |
| 现有 + 模拟 | 实际 Web 端口、运行环境；未就绪显示 `—`，环境就绪才 `READY`。副标题就绪时显示实际 runtime 与 DSH 版本，兼容回退可显示 proot。 | 端口用 `—` 或明确“示例端口”，不把固定 3080 当实际端口，不把源码版本当手机版本。 | [LaunchFragment:455–467][launch-state]。 |
| 现有 + 模拟 | 状态不只有开/关：启动、停止、恢复原 Web、原实例未确认、自动重启暂停、安全模式、环境需恢复。 | 可展示状态样本。未知/未确认必须保持未知；“正在打开 Web”不是网页核心功能已验证。 | [LaunchFragment:401–454][launch-state]、[515–530][launch-state]、[308–311][launch-web-open]。 |
| 现有 + 模拟 | 访问地址弹窗：本机地址 + LAN 状态；本机地址为空时复制按钮不可用。 | HTML 展示不含凭据的样例，不读取真实剪贴板，不生成或复制真实 token。 | [LaunchFragment:657–701][launch-address]。 |
| 现有 + 模拟 | LAN 有未开启、系统权限缺失、等待鉴权、监听不可用、地址发现失败、仅 IPv6、无可分享 IPv4、就绪等状态；打开地址面板本身不启用 LAN。 | “开启局域网访问”只显示模拟确认/风险说明；不能真实开监听或申请系统权限。需保留 HTTP 明文、可信网络、链接含凭据及重启/关闭后旧链接失效的提醒。 | [LaunchFragment:647–655][launch-lan-refresh]、[704–790][launch-lan-states]、[824–844][launch-lan-token]。 |
| 现有 + 模拟 | 启动日志可选文本，日志底部跟随但不抢操作区焦点；“下载日志”进 DiagnosticActivity。 | HTML 可展示短模拟日志与滚动，不下载真实诊断、不暴露实际鉴权 URL。原生日志有鉴权 URL 显示不等于原型应收集它。 | [LaunchFragment:140–141][launch-bindings]、[346–353][launch-log-follow]、[415–424][launch-state]；[fragment_launch.xml:47–60][launch-xml]。 |
| 现有 + 模拟 | 安全启动先确认，只加载官方基础界面，保留原插件开关/会话/模型/配置；恢复选项进入 StartupRecoveryActivity，环境维护时改为维护入口。 | 安全启动、重启、停止均只演示；普通停止失败时还有受核验的强制结束分支，不能在 HTML 无条件跳“已停止”。 | [LaunchFragment:146–158][launch-bindings]、[515–548][launch-recovery]、[551–624][launch-force-stop]。 |

**不应混淆**：当前 HTML 首页的终端计数/会话概览是现有原型逻辑，不是 `LaunchFragment` 的生产卡片；添加“主终端”快捷入口属于 **[建议]**，可以保留但需与本机 Web 启动卡分开。

## 3. 插件 Tab：市场不是已联网商店数据

### 3.1 结构与操作

原生使用**一个 RecyclerView**，header/footer 也是条目；市场态没有插件 item 列表，管理态才渲染 `visibleItems`。见 [PluginFragment:174–203][plugins-bind]、[992–994][plugins-count] 和 [fragment_plugins.xml:2–5][plugins-xml]。

| 标记 | 真实内容 | HTML 应做的交互 / 不应夸大的地方 | 证据 |
|---|---|---|---|
| 现有 + 模拟 | 顶部分段“插件市场 / 插件管理”。市场默认打开；外部 `show_installed` 可直达管理。 | 切换真实分区，不只是占位文字。 | [plugin_list_header.xml:3–6][plugins-header]；[ui_strings.xml:58–62][plugin-strings]；[PluginFragment:185–196][plugins-bind]、[408–419][plugins-tab]。 |
| 现有 + 模拟 | 市场：社区插件生态入口、从链接安装、输入框、识别结果、粘贴、解析安装。官网入口实际打开 CommunityActivity。 | 可做离线格式识别演示；按钮应写“预览解析/模拟安装”，不联网、不装插件、不自动访问社区。不存在本次已抓取的推荐商店卡片。 | [plugin_list_header.xml:7–28][plugins-header]；[PluginFragment:391–405][plugins-link]、[422–441][plugins-install]。 |
| 现有 + 模拟 | 安装来源支持 npm 包名（含 scoped/版本）、GitHub、HTTPS 压缩包；多链接报错。 | 不只支持 URL；空输入应不可解析。HTML 简化校验需标“演示校验，非生产安装器”。 | [PluginSource:27–65][plugin-source]。 |
| 现有 + 模拟 | “本地插件”卡有导入插件包与批量导出；导入提示 ZIP / TAR.GZ。 | 这一卡在**市场态**显示，管理态隐藏；不要为忠实复刻把批量导出误放管理头部。若为了体验搬位置，标建议。HTML 不调用系统文件选择器/写真实归档。 | [plugin_list_footer.xml:4–23][plugins-footer]；[PluginFragment:492–498][plugins-render]、[447–475][plugins-import]、[602–645][plugins-export]。 |
| 现有 + 模拟 | 下载源：自动选择（推荐）/ npm 官方源 / npmmirror 国内镜像；提示 GitHub 官方直连。 | 源选择只变本页状态，不是联网测速或已选出最快节点。 | [PluginFragment:209–213][plugins-wiring]、[335–369][plugins-sources]。 |
| 现有 + 模拟 | 管理：已安装插件标题、检测插件、名称/描述搜索、隐藏内置、数量、排序、检查更新。 | 搜索/过滤/排序可以对静态示例真的生效；数量必须称“示例条目”，不得称手机已装数量。 | [plugin_list_header.xml:29–40][plugins-header]；[PluginFragment:526–571][plugins-render]。 |
| 现有 + 模拟 | 四种排序：名称 A–Z、名称 Z–A、已启用优先、可更新优先。隐藏内置同时过滤 builtin / official / runtimeProvided。 | 原型不需要伪造“存在更新”；可更新优先仍可作为样例排序选项。 | [plugin_sort_strings.xml:2–6][plugin-sort]；[PluginFragment:535–546][plugins-render]。 |
| 现有 + 模拟 | 条目：真实包名、描述、状态/版本、开关、更多、展开详情；详情有版本/位置/来源。动态临时插件不显示普通开关。 | 展开/收起真实可用；启停只改样例。应分开“已启用”和“已确认加载”，不可一键启用就宣称加载成功。 | [item_plugin.xml:2–12][plugin-item-xml]；[PluginFragment:851–916][plugin-item-state]、[922–978][plugin-item-detail]。 |
| 现有 + 模拟 | 更多菜单按属性出现：查看详情、复制名称/来源、导出、检查更新、更新、回退、删除；详情还有能力声明。 | 对随包示例隐藏删除，不能假设每个插件都可更新/回退。禁用官方核心有风险确认，删除也有确认与原件保留说明。 | [PluginFragment:652–680][plugin-item-actions]、[687–794][plugin-action-details]、[798–814][plugin-delete]。 |
| 现有 + 模拟 | 忙进度、可取消状态、操作结果完整弹窗；可有重试安装/清理临时包。安全模式有恢复此前启用插件按钮。未知列表不是“0 插件”。 | 展示“插件列表尚未确认/未读取设备列表”与样例切换；不要把未加载等同为空列表或成功。 | [PluginFragment:214–227][plugins-wiring]、[372–388][plugins-status]、[505–571][plugins-render]。 |

### 3.2 可用的精确源码样例（不是手机安装清单）

以下名称、版本来自随包源码 manifest，可用于一组明确标注 **“随包源码示例 · 未读取本机已安装插件”** 的卡片。功能短述为 manifest 的简要释义；不代表插件当前已启用或系统能力已授权。

| 包名 | 源码版本 | 可展示的简介 | 证据 |
|---|---|---|---|
| `dsh-web-mobile` | `3.2.1` | 移动端/竖屏适配 | [dsh-web-mobile/package.json:2–4][pkg-mobile] |
| `dsh-status-overlay` | `0.1.4` | 将输出与工具调用状态显示到浮条 | [dsh-status-overlay/package.json:2–4][pkg-overlay] |
| `dsh-task-notifier` | `0.2.0` | 通过认证原生桥提供任务进度与单次取消 | [dsh-task-notifier/package.json:2–4][pkg-notifier] |
| `dsh-device-shell-guide` | `0.1.19` | Root / Shizuku / ADB 受保护设备命令引导 | [dsh-device-shell-guide/package.json:2–4][pkg-guide] |

卡片上的开启、加载、更新、安装时间、安装路径是**运行时字段**；没有读取设备，必须留“未读取”或另标“模拟值”。源码提供包名不能推出本机安装事实。

## 4. 设置根页：SettingsFragment

布局分组是 **工作台 / 使用体验 / 支持**，不是把所有功能挤成“终端显示选项”。见 [fragment_settings.xml:4–60][settings-xml]、[ui_iteration.xml:16–29][settings-labels]。

| 分组 / 标记 | 显示内容与去向 | 证据 / 预览边界 |
|---|---|---|
| 工作台 · 现有 | 安装与环境 → InstallFragment；运行配置 → ConfigFragment；数据与备份 → WorkspaceFragment；设备能力授权 → DeviceGrantsFragment。 | [SettingsFragment:28–34][settings-options]、[211–216][settings-titles]、[249–257][settings-navigation]。HTML 应有可返回的内容页，不只 toast。 |
| 使用体验 · 现有 + 模拟 | 省电模式；开=熄屏空闲 1 分钟后减少保活，有任务继续；关=持续保持运行。真实开关立即保存并刷新 HarnessService 电源模式。 | [SettingsFragment:50–90][settings-eco]。HTML 只能标“模拟开关”，不宣称节电已生效。 |
| 使用体验 · 现有 + 模拟 | 语言单选：跟随系统 + 十种语言；摘要显示实际生效语言及是否跟随系统。 | [SettingsFragment:116–169][settings-language]；[UiLanguagePreference:22][languages]、[80–90][language-names]。HTML 如果只改变摘要而未翻译整页，应如实说“选择演示”，不能宣称语言已完整切换。 |
| 使用体验 · 现有 + 模拟 | 悬浮条 → OverlayFragment。 | [SettingsFragment:170–176][settings-overlay]。这不是终端浮动菜单球；不得拿原型终端悬浮球替代系统悬浮条设置。 |
| 支持 · 现有 | 版本更新 → UpdateActivity；自检与诊断 → DiagnosticActivity；关于 DSHA → AboutFragment。 | [SettingsFragment:106–114][settings-support]、[180–186][settings-support-methods]；[fragment_settings.xml:30–60][settings-xml]。 |
| 页脚 · 现有 + 模拟 | `DSHA v<versionName> · MIT 许可` 从 PackageManager 取安装版本；另一个更新副标题控件在 XML 是 gone。 | [SettingsFragment:92–104][settings-version]；[fragment_settings.xml:59–60][settings-xml]。HTML 可标“源码版本 build197”，不能称已读取手机版本。 |

十种语言原文：简体中文、繁體中文、English、Русский、日本語、한국어、العربية、Português、Latina、Français；加“跟随系统”共 11 项，见 [UiLanguagePreference:80–90][language-names]。

## 5. 运行配置子页：ConfigFragment

真实布局为可滚动内容 + **固定底部保存按钮**；分组为运行与网络、显示与启动、悬浮条、运行时兼容选项；见 [fragment_config.xml:3–67][config-xml]。原生页说明模型和 API Key 去 Web UI 配置，端口和运行方式下次启动生效，见 [ui_iteration.xml:112–121][config-labels] 与 [ConfigFragment:101–148][config-choice]。

| 标记 | 真实控件 / 逻辑 | 证据与 HTML 建议 |
|---|---|---|
| 现有 + 模拟 | 运行方式 select：proot（默认，稳定）/ proroot（可选，实验）；原来的 `config_proroot` Switch 是隐藏绑定控件。 | [ConfigFragment:115–125][config-choice]；[fragment_config.xml:10–11][config-xml]。不要同时显示 select 与旧 Switch。 |
| 现有 + 模拟 | 自动端口开关；自动时禁用“首选 Web 端口”；另有恢复旧端口草稿入口。 | [ConfigFragment:66–69][config-start]、[102–104][config-choice]、[155–194][config-validation]；[fragment_config.xml:12–16][config-xml]。 |
| 现有 + 模拟 | 手动端口要求 1–65535，拒绝 3081、3082、3090–3099；无效值禁保存。 | [ConfigInput:7–17][config-port-validation]。原型可完整模拟验证，不要仅做“是数字”检查。 |
| 现有 + 模拟 | DNS select：自动（推荐）/ IPv4 / 原生；背后 RadioGroup 隐藏。 | [ConfigFragment:126–137][config-choice]；[fragment_config.xml:17–23][config-xml]。 |
| 现有 + 模拟 | LAN 开关、HTTP 未加密提示。真正保存会开/关监听，并可能申请系统本地网络权限。 | [ConfigFragment:222–243][config-save]、[326–345][config-lan]；[fragment_config.xml:24–25][config-xml]。原型严禁接这些副作用。 |
| 现有 + 模拟 | 桌面模式、网页内核；Standard 只有 System WebView，Low 才有自动/Gecko。 | [ConfigFragment:105–148][config-choice]；[fragment_config.xml:29–32][config-xml]。不能在 Standard 默认演示可任意切 Gecko。 |
| 现有 + 模拟 | PiP 用户开关**立即保存**；另有系统 PiP 设置入口，区分不支持/未启用/系统允许/系统禁止。 | [ConfigFragment:72–83][config-start]、[273–312][config-pip]。HTML 的“系统设置”只能说明，不开实际设置；不能画成已经取得许可。 |
| 现有 + 模拟 | 启动时检查更新；悬浮显示 AI 输出；悬浮条外观与行为→OverlayFragment。 | [ConfigFragment:70–91][config-start]、[150–151][config-overlay-entry]、[356–360][config-overlay]；[fragment_config.xml:41–53][config-xml]。 |
| 现有 + 模拟 | 使用 proroot 静态加载器、禁用 proot seccomp 加速；直接显示在兼容选项卡。 | [ConfigFragment:84–87][config-start]、[215–217][config-save]；[fragment_config.xml:55–58][config-xml]。这是运行器兼容配置，不等于操作系统 SELinux/系统安全开关。 |
| 现有 + 模拟 | 打开数据与备份；仓库链接。 | [ConfigFragment:63–64][config-start]、[349–353][config-navigation]、[363–370][config-repo]；[fragment_config.xml:60–61][config-xml]。 |
| 现有 + 模拟 | 保存主要配置受环境任务锁保护；成功提示“网页显示选项重新进入对话生效，端口与运行时需重启 Web”。 | [ConfigFragment:182–253][config-save]。HTML 可以有脏状态/保存反馈，但应说“仅保存本次预览”；不能说生产配置已保存。 |
| 非可见生产入口 | Java 仍接 `config_adv_header` 的折叠逻辑，但 XML 的 header/body 都为空且 gone。 | [ConfigFragment:93–99][config-fold]；[fragment_config.xml:62–63][config-xml]。不得因此把一个“高级配置折叠区”宣称为当前可见功能；重新组织为折叠是建议。 |

**源码默认值，仅供样例初始化**：DNS auto；proot；检查更新 true；桌面 false；PiP 仅明确本机 opt-in 才 true；LAN false；省电 false；静态 loader true；禁用 seccomp false。证据：[ConfigStore:111–112][defaults-dns]、[251–338][defaults-runtime]、[424–425][defaults-lan]、[500–501][defaults-eco]。这些不是用户手机当前值，不能标“当前已关闭/已开启”。

## 6. 数据与备份子页：WorkspaceFragment

布局是纵向 ScrollView，标题“数据与备份”；第一组含备份、存储、作品文件、储存指南、Skills，第二组是恢复更多与重置配置，底部有状态与条件取消按钮。见 [fragment_workspace.xml:4–59][workspace-xml]。

| 标记 | 真实内容与实际去向 | 证据 / HTML 推荐 |
|---|---|---|
| 现有 + 模拟 | 一键备份：Android 29+ 直接保存到 Download/DSHA、无需密码；更早系统先选目录后复用。备份安全停止 DSH 和运行任务；包含对话/配置/插件/当前应用内工作区，外置工作区另备。 | [WorkspaceFragment:48–59][workspace-backup-text]、[92–105][workspace-actions]。点击只能显示模拟确认/进度，不真实停止或写归档，不显示虚构“上次成功备份”。 |
| 现有 + 模拟 | 存储与文件 → StorageActivity；作品与文件导出 → WorkspaceFilesActivity（浏览工作区/保存手机/分享）。 | [WorkspaceFragment:67–83][workspace-files]、[106–109][workspace-actions]；[fragment_workspace.xml:17–33][workspace-xml]。 |
| 现有 + 模拟 | 储存指南 → StorageHelpActivity；技能（Skills）→ SkillsActivity，文字为查看技能/导入 SKILL.md。 | [WorkspaceFragment:71–91][workspace-files]。HTML 不伪造已有技能名、文件数或系统目录内容。 |
| 现有 + 模拟 | 恢复与更多备份选项 → NativeDataActivity。虽然 XML 初始文案叫“环境修复与数据救援”，Java 会覆盖为前者。 | [WorkspaceFragment:60–63][workspace-backup-text]、[101–105][workspace-actions]。采用最终运行文案，不只复制 XML。 |
| 现有 + 模拟 | 重置配置先确认：停止 Web/终端/写任务，在隔离服务读回后事务提交；保留插件构成、对话、凭据和原配置。 | [WorkspaceFragment:122–142][workspace-reset]。这不是“清除全部数据”或“恢复出厂设置”，不要新增一键格式化按钮。 |
| 现有 + 模拟 | 备份与维护记录包含手动/自动副本、上次失败、当前任务、维护待恢复；忙或待恢复时禁用备份/恢复更多/重置；有恢复预览确认。 | [WorkspaceFragment:111–119][workspace-history-entry]、[181–237][workspace-status]。可用“未读取记录”/显式样例，不能说本机还没有备份（那也是未经读取的事实）。 |
| 现有 + 模拟 | 取消备份仅在 oneClick busy 时可见。 | [WorkspaceFragment:64–66][workspace-cancel]、[217–220][workspace-status]。HTML 取消只影响示例流程。 |

## 7. 可展开的真实相邻入口（不必一次实现所有业务）

这些是设置/首页已经存在的去向；HTML 至少应显示对应内容提纲与明确边界，而不是统一“功能暂未实现”。若只做说明面板，请标“原生能力说明 / 本原型未连接”。

| 入口 | 真实内容证据 | 推荐原型呈现 |
|---|---|---|
| 安装与环境 | 环境状态、应用版本、已安装描述符版本；受管运行时更新、环境恢复、首次安装测速、全组件检查/按需修复/取消、步骤详情与日志。[InstallFragment:67–118][install-controls]、[168–183][install-step]；六个显示标题为 Ubuntu 环境、离线基础工具、Node.js 24、pnpm、DeepSeek Harness、启动与环境检查，[ui_iteration.xml:90–111][install-labels]。 | 六项行列 + “未检测”状态 + 可开详情；检测/修复只模拟，不用六个绿色勾冒充已通过。 |
| 设备能力授权 | Root/Shizuku/ADB 通道状态；屏幕操作设置、暂停并撤销预授权、独立虚拟屏入口；位置/传感器、所有文件/电池系统入口、短信独立确认。[DeviceGrantsFragment:53–143][grants-screen]、[193–267][grants-sensitive]。屏幕确认三模式为全部手动/按应用自定义/全部允许，明确“不授予系统权限”，[273–345][grants-policy]。 | 显示权限类别与“未读取 / 原型不申请权限”；如演示开关，只提示模拟，不出现系统授权成功 toast。不要把“全部允许”画成已经获 Android/Root 授权。 |
| 悬浮条 | 显示开关、样式预览、不透明度、最大 1–6 行、停留时间、背景样式、文字大小、思考/命令/悬浮确认及保存；真实系统预览可能打开悬浮窗权限。[OverlayFragment:30–169][overlay-content]。 | 可在页面卡内做纯 CSS 预览；明确不创建 Android 系统悬浮窗，与终端演示浮球分开。 |
| 电脑工作区 | 本机卡 + 已信任主机列表；连接状态、路径/延迟、连接/进入电脑、撤销信任、配对电脑、条件断开。[WorkspaceActivity:48–62][remote-entry]、[118–158][remote-state]、[159–192][remote-actions]；[build195_remote_workspaces.xml:8–21][remote-xml]。 | 默认“尚未读取配对列表”；可打开配对说明，不扫真实二维码、不建立连接、不伪造在线电脑。该页不是数据备份页。 |

## 8. 已有 HTML 基线与本轮建议的界线

本节是本任务读取时的 **基线快照**，不是 Lead 随后编辑完成的验收结论。证据为当时的 [preview.html:312–369][preview-baseline]；并行修改后行号可能移动，应由 Lead 复核最终 HTML。

| 基线已有 HTML | 当前源码对照 | 本轮填充建议 |
|---|---|---|
| 首页“工作区 / 运行概览 / 主终端 / 演示计数” | 启动页本身实际以 DSH Web 状态为主，没有这组终端统计卡。 | 保留演示统计为建议区，增加真实启动卡、电脑工作区、端口/环境、地址、日志和恢复。 |
| 插件页“导航占位”，只有“移动界面/设备工具”泛称 | 原生已有完整市场/管理结构、真实包名和丰富状态。 | 换成两段实页；样例卡使用上文精确包名，标源码示例而非已装事实。 |
| 设置页只有终端显示选项和原型说明 | 原生设置是模块路由中心，不是终端显示页。 | 建立三个真实分组和 Config/Workspace 子页；终端字号选项留在原型工具区域。 |
| 四入口导航与终端全屏演示 | 四根入口确实存在，但 HTML 全屏/浮球交互设计不能据此主张已进 Android。 | 延续终端模拟，补真实内容层级；不得接设备桥或伪装实际 shell。 |

### 推荐交互（均为 HTML 设计，不是授权执行原生操作）

1. **可真的操作的本页交互**：根 Tab/子页返回、展开详情、搜索、排序、隐藏示例内置项、单选菜单、端口本地校验、文本输入、CSS 样式预览。无需网络、手机、文件权限。
2. **明确模拟的业务动作**：启动/重启/停止、安装/更新/启停插件、保存配置、备份/恢复/重置、检测环境。点击前或反馈中持续出现“仅本页模拟”，不能只有一处难发现的总免责声明。
3. **仅说明的敏感入口**：真实系统授权、Root/Shizuku/ADB、短信、定位、屏幕控制、系统悬浮窗、PiP 系统设置、LAN 监听、电脑配对。不得调用 Android bridge、发 intent、申请浏览器权限或网络请求。
4. **建议但非生产既有**：全页状态样例选择器、统一演示重置、未保存提示、在启动页新增终端快捷卡。标“设计建议”，不要写入“现有能力”清单。
5. **数据诚实性**：运行时信息用“未读取”与 `—`；带具体数字的状态只能“模拟”。不展示实际密钥/token，不填随机主机/IP/插件数/备份时间后冒充检测结果。
6. **层级与可用性**：保持四根入口；子页返回到所属设置；所有表单都有 label，操作目标保留约 44–48px，弹窗关闭后还原焦点；短屏滚动内容，保存操作不盖住字段。源布局的 48dp/64dp 触控与固定保存可作为映射依据，但响应式排版属于 HTML 建议。

## 9. 本任务交付核对

- 已向 Lead 提前三次发送简明内容清单、实际名称/布局修正、精确插件样例与默认值边界。
- 本文核查的是**当前本地源码**，不是 Release 可得性、安装包内容验证、手机当前状态或用户授权。
- 只新增本文；未运行构建/测试/手机工具/私有远端请求，未改真实设置与权限，未提交。
- 静态引用核验：106 个引用定义、153 处引用均能解析，目标文件存在且行号在界内；这是文档结构核验，不是运行功能测试。生产源码与构建脚本的只读 `git diff --exit-code` 核对无差异。
- HTML 的最终实现与浏览器验证归 Lead；本文不宣称建议交互已全部落入最终原型。

## 证据链接

[agents]: <<workspace>/DSHA-Sunset-mod-3090-Q/AGENTS.md>
[readme]: <<workspace>/DSHA-Sunset-mod-3090-Q/README.md>
[security]: <<workspace>/DSHA-Sunset-mod-3090-Q/docs/security-model.md#L9-L48>
[build]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/build.gradle#L55-L85>
[nav-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/menu/bottom_nav_menu.xml#L5-L21>
[main-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/activity_main.xml#L3-L18>
[main-entry]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L90-L145>
[main-back]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L96-L114>
[main-gate]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L240-L267>
[main-actions]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L278-L311>
[main-about]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L344-L348>
[main-nav]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L350-L411>
[main-theme]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L579-L605>
[main-subpages]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L674-L697>
[launch-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/fragment_launch.xml#L3-L62>
[launch-labels]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/ui_iteration.xml#L3-L12>
[launch-setup]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L92-L127>
[launch-bindings]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L140-L158>
[launch-actions]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L160-L208>
[launch-web-open]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L308-L311>
[launch-log-follow]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L346-L353>
[launch-state]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L401-L530>
[launch-recovery]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L515-L548>
[launch-force-stop]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L551-L624>
[launch-lan-refresh]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L647-L655>
[launch-address]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L657-L701>
[launch-lan-states]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L704-L790>
[launch-lan-token]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java#L824-L844>
[plugins-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/fragment_plugins.xml#L2-L5>
[plugins-header]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/plugin_list_header.xml#L3-L46>
[plugins-footer]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/plugin_list_footer.xml#L4-L24>
[plugin-item-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/item_plugin.xml#L2-L12>
[plugin-strings]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/ui_strings.xml#L58-L62>
[plugin-sort]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/plugin_sort_strings.xml#L2-L6>
[plugins-bind]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L174-L203>
[plugins-wiring]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L209-L227>
[plugins-sources]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L335-L369>
[plugins-status]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L372-L388>
[plugins-link]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L391-L405>
[plugins-tab]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L408-L419>
[plugins-install]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L422-L441>
[plugins-import]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L447-L475>
[plugins-render]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L490-L573>
[plugins-export]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L602-L645>
[plugin-item-actions]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L652-L680>
[plugin-action-details]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L687-L794>
[plugin-delete]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L798-L814>
[plugin-item-state]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L851-L916>
[plugin-item-detail]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L922-L978>
[plugins-count]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java#L992-L994>
[plugin-source]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/util/PluginSource.java#L27-L65>
[pkg-mobile]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json#L2-L4>
[pkg-overlay]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/assets/builtin-plugins/dsh-status-overlay/package.json#L2-L4>
[pkg-notifier]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/assets/builtin-plugins/dsh-task-notifier/package.json#L2-L4>
[pkg-guide]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/assets/builtin-plugins/dsh-device-shell-guide/package.json#L2-L4>
[settings-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/fragment_settings.xml#L4-L60>
[settings-labels]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/ui_iteration.xml#L16-L29>
[settings-options]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L28-L34>
[settings-eco]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L50-L90>
[settings-version]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L92-L104>
[settings-support]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L106-L114>
[settings-language]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L116-L169>
[settings-overlay]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L170-L176>
[settings-support-methods]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L180-L186>
[settings-titles]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L211-L216>
[settings-navigation]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java#L249-L257>
[languages]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/util/UiLanguagePreference.java#L22>
[language-names]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/util/UiLanguagePreference.java#L80-L90>
[config-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/fragment_config.xml#L3-L67>
[config-labels]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/ui_iteration.xml#L112-L121>
[config-start]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L63-L91>
[config-fold]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L93-L99>
[config-choice]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L101-L148>
[config-overlay-entry]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L150-L151>
[config-validation]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L155-L194>
[config-save]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L182-L253>
[config-pip]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L273-L312>
[config-lan]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L326-L345>
[config-navigation]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L349-L353>
[config-overlay]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L356-L360>
[config-repo]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java#L363-L370>
[config-port-validation]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/util/ConfigInput.java#L7-L17>
[defaults-dns]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L111-L112>
[defaults-runtime]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L251-L338>
[defaults-lan]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L424-L425>
[defaults-eco]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/core/ConfigStore.java#L500-L501>
[workspace-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/fragment_workspace.xml#L4-L59>
[workspace-backup-text]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L48-L63>
[workspace-cancel]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L64-L66>
[workspace-files]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L67-L91>
[workspace-actions]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L92-L109>
[workspace-history-entry]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L111-L119>
[workspace-reset]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L122-L142>
[workspace-status]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java#L181-L237>
[install-controls]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/InstallFragment.java#L67-L118>
[install-step]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/InstallFragment.java#L168-L183>
[install-labels]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/values/ui_iteration.xml#L90-L111>
[grants-screen]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L53-L143>
[grants-sensitive]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L193-L267>
[grants-policy]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L273-L345>
[overlay-content]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/ui/OverlayFragment.java#L30-L169>
[remote-entry]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/remote/ui/WorkspaceActivity.java#L48-L62>
[remote-state]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/remote/ui/WorkspaceActivity.java#L118-L158>
[remote-actions]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/java/com/deepseekharness/app/remote/ui/WorkspaceActivity.java#L159-L192>
[remote-xml]: <<workspace>/DSHA-Sunset-mod-3090-Q/app/src/main/res/layout/build195_remote_workspaces.xml#L8-L21>
[preview-baseline]: <<workspace>/DSHA-Sunset-mod-3090-Q/docs/design/terminal-20261025/preview.html#L312-L369>
