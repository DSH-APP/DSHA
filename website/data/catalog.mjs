export const site = {
  origin: 'https://dsha.cc', // version / versionCode 由实际 APK 清单注入。
  dsh: '0.1.6-alpha.2', checkedAt: '2026-09-18',
  repository: 'https://github.com/DSH-APP/DSHA',
  group: '975836806'
};

export const entries = [
{
  "id": "dsh-batch-tool-calls",
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
  "download": {
    "url": "https://registry.npmjs.org/dsh-batch-tool-calls/-/dsh-batch-tool-calls-1.0.0.tgz",
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
  "download": {
    "url": "https://registry.npmjs.org/dsh-any-background/-/dsh-any-background-0.2.8.tgz",
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
    kind: 'builtin', category: 'workflow', icon: 'layout', version: '2.4.1-dsha.5',
    testedDsha: '0.1.6-alpha2', testedDsh: '0.1.6-alpha.2', checkedAt: '2026-09-18',
    summary: '让对话、目录和设置适应手机竖屏，减少来回缩放。',
    description: '为 dsh 的 Web 界面提供窄屏布局、目录抽屉、设置面板和安全区适配。当前版本内置 2.4.1-dsha.5，适配 alpha.2 的右栏预览和输入框布局。',
    author: 'mexiaosh', source: 'https://github.com/mexiaosqwq/dsh-web-mobile', license: 'MIT',
    requirements: ['DSHA 0.1.6-alpha2，内置 dsh 0.1.6-alpha.2', '标准版使用系统 WebView；兼容版可使用内置 Gecko'],
    permissions: ['无需额外 Android 系统授权', '插件在 dsh Web 环境中运行，参与界面渲染'],
    steps: ['打开 DSHA → 插件管理，搜索 dsh-web-mobile。', '按需启用或禁用，然后到启动页重启 Web。', '重新打开对话页，检查窄屏布局和目录抽屉。'],
    example: '在竖屏中展开项目目录，再打开设置；内容应保持在手机可阅读的布局内。',
    limitations: ['随 APK 内置，无需再次下载导入。', '页面布局还会受到系统字体大小和浏览器版本影响。'],
    verification: '与最终两版 APK 的内置包版本核对；本轮共享功能已在 Android 13 标准版与兼容版检查。具体设备能力仍取决于授权和系统支持。',
    tags: ['手机竖屏', 'Web UI', '无额外系统授权']
  },
  {
    id: 'dsh-device-shell-guide', name: '设备操作引导', packageName: 'dsh-device-shell-guide',
    kind: 'builtin', category: 'device', icon: 'terminal', version: '0.1.19',
    testedDsha: '0.1.6-alpha2', testedDsh: '0.1.6-alpha.2', checkedAt: '2026-09-18',
    summary: '让 Agent 了解 DSHA 的设备命令通道，以及使用前需要的授权。',
    description: '随 DSHA 内置的提示引导插件，将设备 Shell 能力说明加入新对话。它帮助 Agent 选择现有通道，实际权限仍由 Android 授权和通道状态决定。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['DSHA 0.1.6-alpha2', '需要操作设备时，先建立已授权的 ADB 或 Shizuku 通道'],
    permissions: ['设备操作通过已授权通道执行', '当前设备命令使用白名单，保护系统目录和关键进程；授权不会解除这些限制'],
    steps: ['在 DSHA 的工作区中配置 ADB，或使用已授权的 Shizuku 通道。', '在插件管理中确认 dsh-device-shell-guide 已启用；变更后重启 Web。', '新建对话，先让 Agent 执行只读设备信息查询并核对结果。'],
    example: '“读取这台手机的 Android 版本和设备型号，先不要修改设置。”',
    limitations: ['Android 11+ 可使用系统无线调试配对码；旧系统需要适合该系统的其他已授权通道。', '启用引导插件不会自动授予 ADB 或 Shizuku 权限。'],
    verification: '与最终两版 APK 的内置包版本核对；本轮共享功能已在 Android 13 标准版与兼容版检查。具体设备能力仍取决于授权和系统支持。',
    tags: ['ADB', 'Shizuku', '设备命令']
  },
  {
    id: 'dsh-task-notifier', name: '任务完成通知', packageName: 'dsh-task-notifier',
    kind: 'builtin', category: 'workflow', icon: 'bell', version: '0.1.3',
    testedDsha: '0.1.6-alpha2', testedDsh: '0.1.6-alpha.2', checkedAt: '2026-09-18',
    summary: 'Agent 完成一轮任务后，通过 DSHA 本机桥发送系统通知。',
    description: '监听 Agent 回合完成事件，并通过 DSHA 的本机桥接服务通知用户。适合把手机放在一旁等待较长任务完成。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['DSHA 0.1.6-alpha2', 'DSHA 正常运行，且系统允许 DSHA 显示通知'],
    permissions: ['使用 Android 系统通知', '通过本机 DSHA 桥通信；本插件不要求额外模型 API Key'],
    steps: ['在 Android 应用设置中允许 DSHA 通知。', '在插件管理中启用 dsh-task-notifier，变更后重启 Web。', '发起一个简短任务，完成后检查系统通知。'],
    example: '“列出当前工作目录中的一级文件名，完成后告知我。”',
    limitations: ['系统通知权限、免打扰和后台管理可能影响通知显示。', '任务通知不意味着应用能绕过 Android 的后台限制。'],
    verification: '与最终两版 APK 的内置包版本核对；本轮共享功能已在 Android 13 标准版与兼容版检查。具体设备能力仍取决于授权和系统支持。',
    tags: ['通知', '任务完成', '本机桥']
  },
  {
    id: 'dsh-status-overlay', name: '实时悬浮状态', packageName: 'dsh-status-overlay',
    kind: 'builtin', category: 'workflow', icon: 'layers', version: '0.1.4',
    testedDsha: '0.1.6-alpha2', testedDsh: '0.1.6-alpha.2', checkedAt: '2026-09-18',
    summary: '把 Agent 输出与工具状态显示在手机悬浮条中。',
    description: '将 Agent 输出和工具调用状态发送到 DSHA 悬浮条。切换到其他应用时，仍可查看任务进展；显示样式在 DSHA 中调整。',
    author: 'DSHA 内置', source: site.repository, license: 'MIT',
    requirements: ['DSHA 0.1.6-alpha2', '开启 DSHA 悬浮条，并授予显示在其他应用上层的权限'],
    permissions: ['需要 Android 悬浮窗授权', '任务文字可能显示在其他应用上方，注意屏幕共享时的可见内容'],
    steps: ['在 DSHA 中开启悬浮条，完成系统悬浮窗授权。', '确认 dsh-status-overlay 已启用；变更后重启 Web。', '发起一个任务并切换应用，检查悬浮条；可在 DSHA 中关闭。'],
    example: '任务运行时切换到文件管理器，观察悬浮条中的当前状态。',
    limitations: ['悬浮条是应用绘制的覆盖层，显示效果取决于系统限制。', '锁屏及部分受保护页面可能不显示悬浮内容。'],
    verification: '与最终两版 APK 的内置包版本核对；本轮共享功能已在 Android 13 标准版与兼容版检查。具体设备能力仍取决于授权和系统支持。',
    tags: ['悬浮窗', '实时输出', '任务状态']
  },
  {
    id: 'device-shell', name: '手机命令操作', packageName: 'device-shell',
    kind: 'skill', category: 'device', icon: 'terminal', version: '2026-09-06',
    summary: '一份可读、可下载的设备操作技能：先确认通道，再执行并验证命令。',
    description: '面向 DSHA 1.2.0-rc1.1 的 Agent Skill，提供 ADB 设备信息查询与操作流程。此技能是提示与操作指南，下载后需要放入技能目录，不通过插件包导入器安装。',
    author: 'DSHA 项目', source: site.repository + '/tree/main/agent-skills/device-shell', license: 'MIT',
    requirements: ['DSHA 1.2.0-rc1.1 或支持 Agent Skills 的兼容环境', '已建立并授权的 ADB 通道；有多台设备时明确选择目标'],
    permissions: ['使用已授权的设备 Shell 通道', '修改设置、安装应用等操作可能改变手机状态；技能不会自行授予系统权限'],
    steps: ['下载技能包并解压，保留 device-shell/SKILL.md 目录结构。', '按下方技能安装指南将目录放入 Agent 的技能搜索目录。', '新建对话，要求 Agent 先查询设备型号和 Android 版本，核对目标设备。'],
    example: '“使用 device-shell 检查已连接设备的型号和 Android 版本，只读取信息。”',
    limitations: ['这是 Agent Skill，不是 dsh bundle，不能从“导入插件包”安装。', '已内置设备操作引导的用户，可先使用内置功能；此文件用于查看、复用和定制工作流。'],
    verification: '内容按 rc1.1 的 proot/Ubuntu 环境整理；执行效果取决于设备授权和 Agent。',
    tags: ['Agent Skill', 'ADB', '可下载']
  },
  {
    id: 'screen-ocr-operator', name: '屏幕识别与操作', packageName: 'screen-ocr-operator',
    kind: 'skill', category: 'device', icon: 'scan', version: '2026-09-06',
    summary: '结合 ADB 截图与视觉模型，让 Agent 看屏幕、执行操作并检查结果。',
    description: '组织“截图 → 视觉模型分析 → ADB 操作 → 结果验证”的技能工作流。需要用户自己的视觉模型服务，模型费用由相应服务计收。',
    author: 'DSHA 项目', source: site.repository + '/tree/main/agent-skills/screen-ocr-operator', license: 'MIT',
    requirements: ['已授权的 ADB 通道', '支持图像输入的 OpenAI 兼容视觉模型 API 与用户自己的 Key', '上传截图前确认其中不含不希望发送给模型服务的内容'],
    permissions: ['读取手机屏幕截图并通过 ADB 操作设备', '截图会发送到用户配置的视觉模型服务', '本技能不要求 Android 无障碍服务授权'],
    steps: ['下载技能包并放入 Agent 的技能目录，配置自己的视觉模型服务。', '先以无敏感内容的页面测试截图与识别，并确认坐标对应原图或缩放图。', '让 Agent 执行小步操作，每个关键步骤重新截图验证。'],
    example: '“查看当前页面有哪些按钮。只描述，不点击，也不提交任何内容。”',
    limitations: ['界面变化、缩放和识别误差都可能影响点击位置。', '密码、验证码、付款和对外提交等步骤应由用户接手确认。', '技能文件不包含任何 API Key，也不会由此网站接收或保存 Key。'],
    verification: '工作流文档已适配 rc1.1；不同视觉服务和设备组合尚未逐一实测。',
    tags: ['Agent Skill', '视觉模型', '截图']
  }
];
