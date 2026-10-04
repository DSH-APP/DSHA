export const site = {
  origin: 'https://dsha.cc', // version / versionCode 由实际 APK 清单注入。
  // dsh与应用身份由最终APK清单注入，不另存一个当前版本。
  checkedAt: '2026-10-03',
  group: '975836806'
};

export const entries = [
{
  "id": "dsh-peak-chip",
  "name": "峰谷指示灯",
  "packageName": "dsh-peak-chip",
  "kind": "plugin",
  "category": "workflow",
  "icon": "scan",
  "version": "4.1.8",
  "summary": "在会话中查看 DeepSeek 余额、本日消耗估算与误差提示。",
  "description": "会话顶部提供峰谷指示灯与余额面板，结合官方余额变化和本地 token 统计估算本日消耗，并显示充值识别、无法审计差额与误差提醒。包内附源码、测试和维修手册；一键维修只把提示填入输入框，由用户决定发送及后续修改。",
  "author": "sunsun320",
  "source": "https://github.com/sunsun320/dsh-peak-chip",
  "issue": "https://github.com/DSH-APP/DSHA/issues/78",
  "license": "MIT",
  "installSource": "https://github.com/sunsun320/dsh-peak-chip/releases/download/v4.1.8/dsh-peak-chip-4.1.8.tgz",
  "testedDsha": "0.1.5-rc2（作者记录）",
  "testedDsh": "0.1.5-rc.2",
  "testedByAuthor": true,
  "checkedAt": "2026-09-28",
  "packageCheckedAt": "2026-09-28",
  "download": {
    "url": "https://dsha.cc/downloads/plugins/dsh-peak-chip-4.1.8.tgz",
    "upstreamUrl": "https://github.com/sunsun320/dsh-peak-chip/releases/download/v4.1.8/dsh-peak-chip-4.1.8.tgz",
    "sha256": "437d649065fd1f47d334981ad6587425c21607c49f0d1620a5c57be05875d406",
    "bytes": 121530,
    "format": "tgz"
  },
  "requirements": [
    "先在 DSH 配置可用的 DeepSeek API Key，并核对插件的 peer 依赖。",
    "作者记录基于 DSHA / DSH 0.1.5-rc2；DSH 0.1.7-rc.2 尚未进行本轮实际加载验证。",
    "安装后保持停用，完成原生静态审阅与确认后再启用。"
  ],
  "permissions": [
    "通过 DSH 凭据服务读取已配置的 DeepSeek API Key，向 api.deepseek.com 查询余额。",
    "在 dsh-peak-chip 设置命名空间保存余额、统计、调试窗口与自身路径。",
    "充值跳转使用本机 127.0.0.1:3090 桥及 .bridge_token；缺少桥时只降级该入口，不自动充值。"
  ],
  "steps": [
    "下载本站固定包导入，或用原始 Release 链接在 DSHA 中预览并核对名称、版本与摘要。",
    "完成原生审阅后启用并重启 Web，检查会话顶部指示灯与余额面板。",
    "维修按钮只生成待发送提示；如需修改插件，应先看诊断与方案，再决定是否确认。"
  ],
  "example": "先确认余额查询可用，再查看本日消耗与误差；费用以服务商实际账单为准。",
  "limitations": [
    "消耗来自本地统计与余额推算，不等同于官方计费明细；其他客户端消费和结算延迟会影响差额。",
    "作者在 DSHA 0.1.5-rc2 的手机 WebView 上报告加载和轮询通过；本轮未复测模型、余额、充值或自动维修功能。",
    "客户端整体无法加载时，维修按钮也不可用；可自行查阅包内维修手册。"
  ],
  "verification": "已下载作者 v4.1.8 固定 Release 包并与 GitHub 公布的 SHA-256 一致；核对 package.json、宿主/客户端入口、依赖、MIT 许可及无安装生命周期脚本。这里只确认包与声明，未执行第三方代码或连接用户模型服务。",
  "tags": [
    "社区插件",
    "余额与用量",
    "作者测试记录"
  ]
},
{
  "id": "dsh-batch-tool-calls",
  "issue": "https://github.com/DSH-APP/DSHA/issues/71",
  "name": "批量工具调用提示",
  "packageName": "dsh-batch-tool-calls",
  "kind": "plugin",
  "category": "workflow",
  "icon": "layers",
  "version": "1.0.0",
  "summary": "提示 Agent 在同一步中发起互不依赖的工具调用。",
  "description": "向 DSH 的 systemPrompt 注册静态提示段，建议批量执行独立读取，并让有依赖的编辑和确认操作保持串行。它不修改工具列表、宿主并发上限或模型缓存策略。",
  "author": "liancha22",
  "source": "https://github.com/liancha22/dsh-batch-tool-calls",
  "license": "MIT（包声明）",
  "installSource": "dsh-batch-tool-calls@1.0.0",
  "testedDsha": "0.1.6-alpha1（独立宿主检查）",
  "testedDsh": "0.1.6-alpha.1",
  "checkedAt": "2026-09-17",
  "packageCheckedAt": "2026-09-28",
  "download": {
    "url": "https://dsha.cc/downloads/plugins/dsh-batch-tool-calls-1.0.0.tgz",
    "upstreamUrl": "https://registry.npmjs.org/dsh-batch-tool-calls/-/dsh-batch-tool-calls-1.0.0.tgz",
    "sha256": "da52f28308abed7faae3d4ba69f2bf44cf58f5595a1e5167681d45d2cbccadc4",
    "bytes": 14050,
    "format": "tgz"
  },
  "requirements": [
    "安装前核对当前 DSH 版本与插件依赖。",
    "社区插件需要联网取得依赖，安装后先审阅再启用。"
  ],
  "permissions": [
    "消费 systemPrompt 服务并注册提示段；不注册设备工具。",
    "静态提示会增加每次请求的提示长度。实际耗时与费用取决于任务和模型行为。"
  ],
  "steps": [
    "在 DSHA 中确认固定版本并安装，完成静态审阅后启用。",
    "重启 DSH，在预设中检查批量调用提示段；可从插件配置修改语言与建议数量。",
    "需要撤销时，在插件管理中停用并重启 DSH。"
  ],
  "example": "在插件设置中检查是否已生效，按需启用。",
  "limitations": [
    "2026-09-28 已重新核对固定包字节；尚未将历史宿主测试扩写为 DSH 0.1.7-rc.2 真机兼容验证。",
    "本轮未付费调用模型，未复核作者给出的节省费用比例。",
    "适配检查使用独立 PC 宿主；不代表每个设备和预设都已验证。"
  ],
  "verification": "固定 npm 发布包已下载核对 SHA-256、入口、依赖和安装脚本；Windows x64 / Node 24 的隔离 DSH 0.1.6-alpha.1 实际启动、鉴权及 Web 页面加载通过，未访问用户正式数据。",
  "tags": [
    "社区插件",
    "固定版本",
    "宿主加载已检查"
  ]
},
{
  "id": "dsh-any-background",
  "issue": "https://github.com/DSH-APP/DSHA/issues/72",
  "name": "自定义主题与壁纸",
  "packageName": "dsh-any-background",
  "kind": "plugin",
  "category": "workflow",
  "icon": "layout",
  "version": "0.2.8",
  "summary": "为 Web UI 设置图片或视频壁纸、主题色、透明度与分区模糊。",
  "description": "提供壁纸、主色、分区透明度与模糊设置，支持主题配置导入导出。服务端在 DSH 数据目录保存主题和上传的图片、视频；通过宿主鉴权后的本机接口提供媒体。",
  "author": "Tkingxiao",
  "source": "https://github.com/Tkingxiao/dsh-any-background",
  "license": "MIT（包声明）",
  "installSource": "dsh-any-background@0.2.8",
  "testedDsha": "0.1.6-alpha1（独立宿主检查）",
  "testedDsh": "0.1.6-alpha.1",
  "checkedAt": "2026-09-17",
  "packageCheckedAt": "2026-09-28",
  "download": {
    "url": "https://dsha.cc/downloads/plugins/dsh-any-background-0.2.8.tgz",
    "upstreamUrl": "https://registry.npmjs.org/dsh-any-background/-/dsh-any-background-0.2.8.tgz",
    "sha256": "a90f02cbebfdae0c6fff428038df9096f0d6ad3bc94538c4266eb7bd700d7a7e",
    "bytes": 213209,
    "format": "tgz"
  },
  "requirements": [
    "安装前核对当前 DSH 版本与插件依赖。",
    "社区插件需要联网取得依赖，安装后先审阅再启用。"
  ],
  "permissions": [
    "读取用户主动选择或上传的媒体，写入 .dsh-any-background-data。",
    "使用外部图片或视频地址时，会访问该地址；大视频会增加存储与流量。",
    "不调用 DSHA 的设备操作接口，不自动申请读屏或定位权限。"
  ],
  "steps": [
    "在 DSHA 中确认固定版本，安装并审阅启用。",
    "重启 DSH，在 Web UI 的设置中打开主题插件。",
    "选择体积适中的图片或视频，调整主题；需要恢复时停用插件并重启。"
  ],
  "example": "在插件设置中检查是否已生效，按需启用。",
  "limitations": [
    "2026-09-28 已重新核对固定包字节；尚未将历史宿主测试扩写为 DSH 0.1.7-rc.2 真机兼容验证。",
    "作者在 issue #72 说明 0.2.8 的图片位置拖动仍依赖鼠标事件，触屏拖动存在限制。",
    "视频、模糊和动画会增加渲染负担，旧设备建议降低模糊并使用静态图片。",
    "本轮验证了 DSH 0.1.6-alpha.1 的独立宿主加载和页面无脚本错误；未覆盖所有主题选项及手机媒体格式。"
  ],
  "verification": "固定 npm 发布包已下载核对 SHA-256、入口、依赖和安装脚本；Windows x64 / Node 24 的隔离 DSH 0.1.6-alpha.1 实际启动、鉴权及 Web 页面加载通过，未访问用户正式数据。",
  "tags": [
    "社区插件",
    "固定版本",
    "宿主加载已检查"
  ]
},
  {
    id: 'dsh-session-health', name: '会话健康检查', packageName: 'dsh-session-health',
    kind: 'plugin', category: 'workflow', icon: 'scan', version: '0.6.0',
    summary: '查看会话上下文用量、健康状态，以及继续当前会话或新开会话的参考提示。',
    description: '提供会话状态徽标、/health 命令与 session_health 工具，也可查看会话概览。统计与估算来自插件能读取的本机会话数据，实际模型费用以服务商账单为准。',
    author: 'NinjaSln-labs（源码仓库）', source: 'https://github.com/NinjaSln-labs/dsh-plugins', license: 'MIT（包声明）',
    installSource: 'dsh-session-health@0.6.0', testedDsha: '1.2.0-rc1.3', checkedAt: '2026-09-07',
    screenshot: {file:'dsh-session-health.png',caption:'Android 13 安装确认页：实际包信息、兼容声明和摘要。'},
    download: {url:'https://registry.npmjs.org/dsh-session-health/-/dsh-session-health-0.6.0.tgz',sha256:'a9d0162513ae5c1f8db1509feb28f40ebb84d1025260c2def0c0631d275e19c1',bytes:63206,format:'tgz'},
    requirements: ['已验证 DSHA rc1.3 / dsh 0.1.2-rc.1 的安装与 Web 加载。', '联网安装所需依赖；包未声明整体 dsh 版本范围，使用前核对组件要求。'],
    permissions: ['读取本机会话的用量和状态；会话概览接口仅供本机访问。', '默认从 jsDelivr / GitHub 获取公开价格表；费用显示属于估算。', '部分健康检查通过 dsh 已有的子进程通道读取 Git 工作区状态。'],
    steps: ['点击“在 DSHA 中安装”，核对实际包名、版本、作者声明和摘要。', '确认后安装，完成后回到启动页重启 Web。', '在对话中使用 /health，或打开会话健康概览，检查当前会话状态。'],
    example: '打开会话健康概览，先查看上下文用量和健康提示，再决定是否新开会话。',
    limitations: ['npm 包未填写 author 字段，App 会如实显示未声明；源码来源见本页链接。', '未逐项验证有历史消息、压缩会话、费用换算等全部情形。'],
    verification: 'Android 13 / arm64 / Node 24.19.0：固定包安装、dsh Web 启动和独立空会话 profile 的健康概览接口返回成功。',
    tags: ['社区插件', '上下文用量', '会话概览']
  },
  {
    id: 'dsh-subagent-model-picker', name: '子代理模型选择', packageName: 'dsh-subagent-model-picker',
    kind: 'plugin', category: 'workflow', icon: 'layers', version: '0.1.1',
    summary: '为子代理任务选择 provider、模型和输出上限，提供可用模型目录工具。',
    description: '增加 subagent_model 和 subagent_models 工具，在 dsh 的现有子代理通道上指定模型路由。实际任务仍由用户配置的模型服务执行。',
    author: 'NinjaSln-labs（源码仓库）', source: 'https://github.com/NinjaSln-labs/dsh-plugins', license: 'MIT（包声明）',
    installSource: 'dsh-subagent-model-picker@0.1.1', testedDsha: '1.2.0-rc1.3', checkedAt: '2026-09-07',
    screenshot: {file:'dsh-subagent-model-picker.png',caption:'Android 13 安装管理实测：当前 0.1.1，上一版 0.1.0 可回退。'},
    download: {url:'https://registry.npmjs.org/dsh-subagent-model-picker/-/dsh-subagent-model-picker-0.1.1.tgz',sha256:'c6ee20802d30fee2307ad2f1cf0b96eca2e29649e1d54060473dec70023369ce',bytes:14855,format:'tgz'},
    requirements: ['已验证 DSHA rc1.3 / dsh 0.1.2-rc.1 的安装与 Web 加载。', '使用前配置可用模型路由及 dsh 的 spawn 子代理 provider。'],
    permissions: ['读取本机已配置的模型目录，通过 dsh 创建子代理任务。', '任务内容会按选择的模型路由发给对应服务；模型调用可能产生费用。'],
    steps: ['点击“在 DSHA 中安装”，核对实际包信息并确认。', '重启 Web，先使用 subagent_models 查看可用模型。', '明确选择 provider 和模型后，再执行一个简短子代理任务并核对结果。'],
    example: '“使用 subagent_models 只列出可用模型，暂不创建任务。”',
    limitations: ['npm 包未填写 author 字段，App 会显示未声明。', '本次未调用模型服务或验证实际子任务输出；需要可用路由和支持深度限制的 provider。'],
    verification: 'Android 13 / arm64 / Node 24.19.0：固定发布包安装成功，dsh Web 加载通过，未运行模型任务。',
    tags: ['社区插件', '模型路由', '子代理']
  },
  {
    id: 'dsh-web-mobile', name: '移动端界面', packageName: 'dsh-web-mobile',
    kind: 'builtin', category: 'workflow', icon: 'layout',
    summary: '让对话、目录和设置适应手机竖屏，减少来回缩放。',
    description: '为 dsh 的 Web 界面提供窄屏布局、目录抽屉、设置面板和安全区适配。内置版本由 APK 清单和对应受管包核对，支持手机快捷键搜索，并改进触摸手势与聊天区域渲染。',
    author: 'mexiaosh', source: 'https://github.com/mexiaosqwq/dsh-web-mobile', license: 'MIT',
    requirements: ['当前同签名 DSHA', '标准版使用系统 WebView；兼容版可使用内置 Gecko'],
    permissions: ['无需额外 Android 系统授权', '插件在 dsh Web 环境中运行，参与界面渲染'],
    steps: ['打开 DSHA → 插件管理，搜索 dsh-web-mobile。', '按需启用或禁用，然后到启动页重启 Web。', '重新打开对话页，检查窄屏布局和目录抽屉。'],
    example: '在竖屏中展开项目目录，再打开设置；内容应保持在手机可阅读的布局内。',
    limitations: ['随 APK 内置，无需再次下载导入。', '页面布局还会受到系统字体大小和浏览器版本影响。'],
    verification: '构建时核对实际签名 APK 身份与对应受管包版本；不以历史 Android 13 记录代替本轮设备验收。',
    tags: ['手机竖屏', 'Web UI', '无额外系统授权']
  },
  {
    id: 'dsh-device-shell-guide', name: '设备操作引导', packageName: 'dsh-device-shell-guide',
    kind: 'builtin', category: 'device', icon: 'terminal',
    summary: '让 Agent 了解 DSHA 的设备命令通道，以及使用前需要的授权。',
    description: '随 DSHA 内置的提示引导插件，将设备 Shell 能力说明加入新对话。它帮助 Agent 选择现有通道，实际权限仍由 Android 授权和通道状态决定。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['当前同签名 DSHA', '需要操作设备时，先在设备能力授权中启用并授权 ADB、Shizuku 或 Root 通道'],
    permissions: ['设备操作通过已授权通道执行', '当前设备命令使用白名单，保护系统目录和关键进程；授权不会解除这些限制'],
    steps: ['在 DSHA 的设备能力授权中配置 ADB，或使用已授权的 Shizuku / Root 通道。', '在插件管理中确认 dsh-device-shell-guide 已启用；变更后重启 Web。', '新建对话，先让 Agent 执行只读设备信息查询并核对结果。'],
    example: '“读取这台手机的 Android 版本和设备型号，先不要修改设置。”',
    limitations: ['Android 11+ 可使用系统无线调试配对码；旧系统需要适合该系统的其他已授权通道。', '启用引导插件不会自动授予 ADB 或 Shizuku 权限。'],
    verification: '构建时核对实际签名 APK 身份与对应受管包版本；不以历史 Android 13 记录代替本轮设备验收。',
    tags: ['ADB', 'Shizuku', '设备命令']
  },
  {
    id: 'dsh-task-notifier', name: '任务完成通知', packageName: 'dsh-task-notifier',
    kind: 'builtin', category: 'workflow', icon: 'bell', version: '0.1.3',
    summary: 'Agent 完成一轮任务后，通过 DSHA 本机桥发送系统通知。',
    description: '监听 Agent 回合完成事件，并通过 DSHA 的本机桥接服务通知用户。适合把手机放在一旁等待较长任务完成。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['当前同签名 DSHA', 'DSHA 正常运行，且系统允许 DSHA 显示通知'],
    permissions: ['使用 Android 系统通知', '通过本机 DSHA 桥通信；本插件不要求额外模型 API Key'],
    steps: ['在 Android 应用设置中允许 DSHA 通知。', '在插件管理中启用 dsh-task-notifier，变更后重启 Web。', '发起一个简短任务，完成后检查系统通知。'],
    example: '“列出当前工作目录中的一级文件名，完成后告知我。”',
    limitations: ['系统通知权限、免打扰和后台管理可能影响通知显示。', '任务通知不意味着应用能绕过 Android 的后台限制。'],
    verification: '构建时核对实际签名 APK 身份与对应受管包版本；不以历史 Android 13 记录代替本轮设备验收。',
    tags: ['通知', '任务完成', '本机桥']
  },
  {
    id: 'dsh-status-overlay', name: '实时悬浮状态', packageName: 'dsh-status-overlay',
    kind: 'builtin', category: 'workflow', icon: 'layers',
    summary: '把 Agent 输出与工具状态显示在手机悬浮条中。',
    description: '将 Agent 输出和工具调用状态发送到 DSHA 悬浮条。切换到其他应用时，仍可查看任务进展；显示样式在 DSHA 中调整。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['当前同签名 DSHA', '开启 DSHA 悬浮条，并授予显示在其他应用上层的权限'],
    permissions: ['需要 Android 悬浮窗授权', '任务文字可能显示在其他应用上方，注意屏幕共享时的可见内容'],
    steps: ['在 DSHA 中开启悬浮条，完成系统悬浮窗授权。', '确认 dsh-status-overlay 已启用；变更后重启 Web。', '发起一个任务并切换应用，检查悬浮条；可在 DSHA 中关闭。'],
    example: '任务运行时切换到文件管理器，观察悬浮条中的当前状态。',
    limitations: ['悬浮条是应用绘制的覆盖层，显示效果取决于系统限制。', '锁屏及部分受保护页面可能不显示悬浮内容。'],
    verification: '构建时核对实际签名 APK 身份与对应受管包版本；不以历史 Android 13 记录代替本轮设备验收。',
    tags: ['悬浮窗', '实时输出', '任务状态']
  },
  {
    id: 'device-shell', name: '手机命令操作', packageName: 'device-shell',
    kind: 'skill', category: 'device', icon: 'terminal', version: 'current-interface-doc',
    testedDsha: '当前设备未复验', testedDsh: 'unknown', checkedAt: '2026-10-03',
    historicalVerification: {testedDsha: '1.2.0-rc1.1', testedDsh: '0.1.2-rc.1', checkedAt: '2026-09-06'},
    summary: '先核对设备通道和实际身份，再执行授权命令并验证结果。',
    description: '使用 DSHA 受管设备入口的 Agent Skill；Root、Shizuku 或 ADB 在原生层发送前选择。它是可读、可复用的工作流文档，不通过插件包导入器安装。',
    author: 'DSHA 项目', source: 'agent-skills/device-shell', license: 'MIT',
    requirements: ['支持 Agent Skills 的当前环境', '设备能力授权中 Root、Shizuku 或 ADB 至少一个已就绪', '先用 id 核验实际身份和目标，不假设 uid=2000'],
    permissions: ['使用用户已授权的设备通道', '设备策略与短信/屏幕等独立能力授权继续生效；技能不自行授予权限'],
    steps: ['下载技能并保留 device-shell/SKILL.md 目录结构。', '将目录放入当前 Agent 的技能搜索目录。', '先查询设备身份、型号和 Android 版本；结果未知时不换通道重放。'],
    example: '“核对这台手机的身份、型号和 Android 版本，只读取信息。”',
    limitations: ['不是 dsh bundle，不能从“导入插件包”安装。', '同 UID 的 guest 和插件不是独立恶意代码沙箱。', '当前文档只做源码/内容检查；历史设备记录不适用于修改后的字节。'],
    verification: '技能源与网站分发字节一致；现行说明按受管接口校对。旧 rc1.1 测试记录单独保留，未执行本轮手机验证。',
    tags: ['Agent Skill', '设备通道', '可下载']
  },
  {
    id: 'screen-ocr-operator', name: '屏幕识别与操作', packageName: 'screen-ocr-operator',
    kind: 'skill', category: 'device', icon: 'scan', version: 'current-interface-doc',
    testedDsha: '当前设备未复验', testedDsh: 'unknown', checkedAt: '2026-10-03',
    historicalVerification: {testedDsha: '1.2.0-rc1.1', testedDsh: '0.1.2-rc.1', checkedAt: '2026-09-06'},
    summary: '用当前授权的读屏与截图结果核对界面、方向和坐标，再小步操作。',
    description: '使用实际原生截图/读屏接口；是否可用取决于系统、服务和本次授权。核对目标、画面与遮挡后操作，每个关键步骤重新验证。',
    author: 'DSHA 项目', source: 'agent-skills/screen-ocr-operator', license: 'MIT',
    requirements: ['当前 DSHA 设备通道与屏幕能力已授权', '需要视觉模型时使用用户选择且支持图像的服务', '发送截图前核对其中的私人内容'],
    permissions: ['可接触当前授权的屏幕与控件树', '普通文件截图可保存到应用私有目录；只清理本次创建的临时文件', '上传内容依用户选择的模型服务协议处理'],
    steps: ['下载技能并放入 Agent 技能搜索目录。', '先核对方向、目标应用、PiP/键盘遮挡及本轮屏幕授权。', '小步操作后重新核对；目标已改变或无法确认时停止。'],
    example: '“描述当前页面有哪些按钮，先不要点击或提交。”',
    limitations: ['不以旧 XML 坐标或裸 adb 绕过受管入口。', '活动配对期间不使用 uiautomator dump，以免抑制其他服务。', '当前设备、Agent 与模型组合尚未逐一复验。'],
    verification: '技能源与网站分发字节一致；文档按现行接口校对。旧 rc1.1 测试记录单独保留，不冒充当前设备结果。',
    tags: ['Agent Skill', '屏幕授权', '截图']
  }
];
