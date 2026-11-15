# DSHA 安全模型

本页描述现行源码策略；历史说明保留在 tools/history，不能作为当前授权边界。当前终端与第三方插件按用户要求开放；独立应急的五工具和原生确认仍保留。具体资产、主体与控制适用范围见[威胁模型 ADR 0002](adr/0002-current-threat-model.md)。

## 资产与主体

需要保护的是对话、附件、个人项目、插件源和依赖、配置、API Key、设备授权、备份以及受管运行资产。参与者包括用户、原生应用、同 UID 的 Ubuntu/Node/插件、内嵌网页、独立应急进程、局域网客户端、外部模型和下载服务器。用户选择外部模型时，请求内容与附件按该服务实际协议发送。

## 信任边界

| 边界 | 实际保证 | 不能推导的保证 |
|---|---|---|
| Android 应用 UID | 系统隔离其他普通应用的私有数据 | proot 的 root 不是 Android root |
| Ubuntu 与插件 | 包、路径、实际摘要和事务核验 | 同 UID 任意代码不是独立沙箱 |
| loopback 桥 | 当前 token、路由与能力约束，执行未知时不重放 | 本机 TCP 不等于只有本应用能连接 |
| 内嵌页面 | 当前来源、实际端口、页面和运行代次 | 不能批准任意网页、旧回调或摄像头 |
| LAN | 独立启用和鉴权、当前 Web 端口与有界连接 | LAN HTTP 是明文；不提供 TLS、受信配对或不受 Android 后台策略影响的承诺 |
| 用户归档 | 完整认证/摘要、范围与双向记录核验，再预检 | 归档自述的健康或格式不成为本机证明 |

## 跨应用文件访问（SAF）

用户在「设置 → 设备能力授权 → 跨应用文件访问」通过系统文档选择器逐个目录授权，助手经 `/app/saf/*` 读写。授权记录只包含用户选中的树 URI、应用标签、读/写位与「本次新拿到的位」；撤销时只释放本次新增位，不误伤备份等其它功能已持有的授权。桥不接受直接 URI/authority，只接受 `/app/saf/trees` 里的短 id。

- **Provider 是信任边界**：目录名、大小、子项与写入结果都来自目标 Provider；DSHA 只做路径/大小上限、URI 归属和本地策略校验，不能替代 Provider 自身的权限判断。Provider 挂起、忽略取消或返回异常时按 `TIMEOUT`/`PROVIDER_ERROR`/`SCAN_LIMIT` 失败关闭，不用本地猜测继续。
- **容器侧固定身份**：容器路径使用 `O_NOFOLLOW|O_NONBLOCK`、`lstat/fstat` 身份与 `readlink /proc/self/fd/<fd>` 真实路径复核；目录逐级 `O_PATH|O_NOFOLLOW` 锚定，pull 经父目录 fd 原子发布，`AnchoredDirectory` 持有 dup 的 PFD 并在 close 时释放。拒绝 `nlink>1` 硬链接与 proot `.l2s` 实体；祖先目录被换链时失败关闭。
- **流拥有 fd**：`openRead`/`openWrite`/`createInDirectory` 返回的流在 `close()` 中负责关闭底层 fd；写入先校验身份/真实路径再 `ftruncate`，避免预置硬链接或非预期文件被截断。
- **写与删**：写/删要求该目录在本地开关里打开「允许写入」；只给写权限的树在添加时拒绝（`WRITE_ONLY`），旧只写记录读端点不可用，需用户移除后重新授权。`rm` 是两段式 + 一次性 token：首次只返回 `NEEDS_CONFIRM`（目标 + 子项数 + 60 秒 token，绑定 tree/path/目标身份），第二次必须带 `confirm=<token>` 且消费即删；`confirm=1` 一律返回 `CONFIRM_REQUIRED`，未知 token 同样拒绝，单条请求不能绕过确认。目录内容递归删除前先逐层只读预检，任何一层超过 500 子项直接 `SCAN_LIMIT`、不先删任何项（分页递归删除留待后续）。不再用「先列目录判空/非空」分支，避免检查与删除之间的竞态。
- **上限与并发**：单次读 256 KiB、单次写/push/pull ≤32 MiB；同时最多 2 个 SAF 传输；每次公开操作一个独立的 15 秒截止时间（`CancelledSignal` + 看门狗），不可中断的 Provider 调用返回后再复核截止并映射 `TIMEOUT`。
- **已知限制**：`cp/mv` 会新建 inode，DSHA 无法按 inode 识别「复制凭据内容」；本功能的边界是防误用/防直接读取，不是内容级防复制。`push` 覆盖已有文件时先 `"wt"` 截断，Provider 超时/取消/长度不符可能留下半截目标；写后长度的 `COLUMN_SIZE` 未知时只能验证写调用成功。`saf/` 目录已纳入 `managed-runtime-inputs.json` 的 `launcherTrees`（launchers 465→469）；它属于桥/安全层，必须被 `runtime-descriptor.json` pin 住。

## 终端与插件

正式终端允许修复 shell；链接、本地包和更新通过包格式、路径、摘要与事务检查后自动安装启用，不再要求插件审阅。依赖允许未锁定重解析、生命周期脚本和 pnpmfile，并记录实际锁与目录摘要以支持回切。用户主动禁用及安全模式意图仍保持。

插件可以在应用 UID 下读写可访问文件并执行代码。自动安装不是独立安全认证。受管系统组件与用户插件分层；系统组件从当前签名 APK 重建，用户修改和个人依赖不得被当作缓存清掉。

模型技能工具对 `disable-model-invocation: true` 的 user-only 技能保留模型目录可见性，但目录条目标记 `[approval required]`。模型加载正文前必须经过 `dsh-user-approval`，只有精确的 `allowed-once` 放行本次调用；拒绝、取消、审批服务缺失、审批异常或加载前后策略变成不可调用都会 fail-closed。`modelInvocable` 与 `userInvocable` 均为 false 的技能不进入模型目录，也不能通过审批绕过。用户消息中的显式 `/name` 仍走 user-invocable 注入路径，不代替模型审批，也不授予模型跨调用权限。

### AI 工具破坏性策略（DSH 0.2.2）

DSH 0.2.2 把工具执行移到 `dsh-tools/lib/pipeline.js` 的 `ToolExecutionPipeline`，原生执行器与 PTC（`run_code` 及其嵌套调用）共用它。签名补丁 `guard-tools-patch.json`（`attachPolicy`）与 `guard-tools-pipeline-patch.json`（`captureExecution` / `prepareOperation` / `verifyGlobalConsent` / `verifyDispatch`）共同生效：bash 删除/不透明命令与 write/edit/str_replace_editor 覆盖已有文件，在两种执行器下都须原生全局允许、会话 danger-full-access 档位或一次性网页审批。任一挂点缺失时 bash 与文件改写一律失败，不放行。

上游新增的 `apply_patch`（dsh-tool-fs，可新建、改写、删除、移动文件）**不在**此策略内：`prepareOperation` 对它不要求同意，guard-fs 也未包装它的 `fs/write|edit|delete-intent`。上游默认关闭（`Config.applyPatch=false`；dsh-base 与 web 的 standard / ptc / cordis / minimal 预设都不开启，DSHA 不改这些配置），按用户决定不加 DSHA 拒绝补丁。用户若在 profile `cordis.patch.yml` 中覆盖某预设的 `tool-fs` 行为 `config.applyPatch: true`（或由插件自行注册同名工具），该工具即可在无审批、无先读校验（0.2.2 默认停用 `fs-observation-policy`）、danger-full-access 下无路径边界的情况下删除或覆盖文件；DSHA 仅保留执行身份冻结、工具身份与取消核验。

DSH 0.2.2 的默认安全取舍还包括：Web Fetch 沿用上游实现，默认不拒绝内网地址；`fs-observation-policy` 默认关闭，不能把读取前置检查写成已启用的安全边界；`apply_patch` 默认关闭，但 profile 或插件可显式打开，因此上面的未受保护删除/移动风险仍适用。

## 设备与浏览器能力

Root、Shizuku、ADB 在发送前选择实际通道；设备命令由原生与特权执行侧策略检查。Root 通道另有用户可选的 strict / relaxed / full 三档拦截：strict 为默认白名单；relaxed 放行任意命令但拦截格式化、刷机、写块设备、重启、清应用数据及递归删除整块用户数据等不可逆操作；full 不按命令内容拦截。三档都要求 root shell 开关与真实 su 身份，短信读取闸门在三个档位都保留；Shizuku、ADB 通用执行器与普通设备命令继续使用 strict。短信、虚拟屏、截图/读屏、录音和存储仍依赖对应原生授权与 Android 实际权限；未知命令的确认不能替代设备身份核验。结果未知时不得换通道重放。实体屏特权执行（T130 Phase A，实验）是独立原生能力：默认关闭、可撤销、仅当前手机任务期间有效，复用同一受管 `app_process` 入口并逐项核验启动参数；它不需要无障碍，但已知敏感应用、未允许的目标应用与目标不明的窗口一律 fail-closed 拒绝，帧与动作凭据一次性、30 秒、绑定显示代次，UNKNOWN 不重放。详见[实体屏特权执行核心](privileged-screen-core.md)。

root 原始命令仅在 uid 0 的特权子进程内执行，保留 65 秒结果收集上限、256 KiB 输出截断和执行身份校验；严格档的受管 argv 路径与文件适配器不变。放宽档的不可逆操作判定是文本级黑名单，不能宣称覆盖所有等价写法；用户选择 full 即接受该边界。

设备写操作仅允许策略认可的共享存储子路径及 `/data/local/tmp/`，阻止共享根、DCIM、Pictures、Android/data、Android/obb 及其祖先写入；这只是受管设备命令的边界，不是同 UID 终端/插件的文件防火墙。短信查询默认关闭，要求独立可撤销授权，仅接受当前 Android 用户的严格 `content query`，不允许provider写入；文件读取侧拒绝已识别的telephony路径及canonical目标。

**已知覆盖缺口**：2026-10-22对 `140c7ba` 的核查发现，递归READ只核验顶层命令参数和canonical路径，未核验遍历过程中子链接指向的目标。`grep -R`等跟随链接的读取不能据此获得“整个telephony树不可达”的保证；这仍是待修实现问题。隔离策略检查证明派发前缺少该项核验，未读取真实手机短信，也未证明某ROM能被实际利用。FILE适配器的NOFOLLOW不替代READ守卫，具体入口与修复要求见[权限架构](device-permissions-architecture.md)第6.2节。

su的metadata发现、管理器授权和UID 0执行是不同事实；DSHA未在APK内实现Root管理器。上游固定源码与兼容性限制见[来源依据](references-and-provenance.md)第5.1节。Root管理器的身份授权不替代DSHA自身命令、短信或虚拟屏策略，也不把同UID任意插件代码变成强沙箱。

无障碍可接收其他应用窗口事件；配对事件扫描只在用户开启的短暂窗口处理设置页。屏幕活动历史（M1）只在当前手机任务活动期间把实体主屏活动保留在应用内存（约 120 秒 / 64 条：目标切换、用户点按/滚动、内容变化、助手动作），任务结束、暂停或服务断开立即清空，不落盘、不进备份或诊断日志、不上传；敏感应用只记目标切换，密码框只留「已输入」，输入法窗口与自身浮层事件丢弃，没有手机任务时不记录。会话经 `/app/ui/history` 读取本任务历史，`/app/ui/*` 回执自动附带最近至多 3 条摘要。主动读屏和截图是独立能力，截图可保存到私有 Pictures/DSHA 并通过内容 URI 返回，不能声称完全不落盘。录音只接受当前本机页面的纯音频请求，摄像头和屏幕音频拒绝。

## 凭据、网络与日志

原生 API Key 使用 Keystore 密文；暂时不可读不等于未配置，不能自动清密文或换解密密钥。桥使用 X-Token 请求头；兼容 shim 仅迁移已知本机 fetch 调用，不开放 CORS 或宣称兼容任意 HTTP 库。外部文件访问和默认备份排除机器令牌；用户选择的项目可能主动包含 .env 等内容，不静默删掉。

`/app/net/resolve` 同样受 X-Token 门控，只把域名解析为数字地址：不接受 IP 字面量、localhost 或单标签名，不建立连接、不记录主机名，并发与时限有上限。guest 本就能经同一默认网络发出 DNS 查询，此端点不新增出网能力，但会让查询走 Android 私有 DNS／VPN 路径；`/app/net` 只读，不输出本机 IP、SSID 或运营商名。

更新地址按 HTTPS、重定向和 APK 身份检查。Android 23 与已有用户 HTTP/LAN 功能仍需明文兼容配置，不能称应用拥有全局禁止明文的网络防火墙。日志对已知秘密和常见凭据格式脱敏；任意第三方输出不保证识别所有秘密。

## 备份与恢复

新导出固定为无需密码的 DSHA-data-v5-UUID.tar.gz，没有可选加密。内部自动副本仍用本机随机密钥加密；卸载可能失去本机密钥，重要副本应导出。历史加密原件继续完整 AEAD 认证，并需要原密码。

恢复先完成认证/摘要与预检，只写私有编号槽位。受管脚本、可执行配置和未知插件隔离；设备授权和机器状态不会由用户归档生效。未知、修改过和未完整提交的原件保持，不自动删除。

ADB 配对密钥目录 `.dsh/adbkeys` 按本机状态处理：备份与导出不再包含，恢复旧归档时其中的密钥不落地，本机现有配对保持不变；本机环境重建仍原样迁移该目录。已知弱点：build336 及以前生成的完整备份与用户导出含 ADB 私钥，拿到文件的人在配对未撤销且无线调试开启时可连接这台手机。建议删除这些旧导出，或在系统「开发者选项 → 无线调试 → 已配对设备」撤销 DSHA 的配对后重新配对。密钥目前仍以明文存在 rootfs 中，同 UID 代码可直接读取，原生化见 T42 设计稿。

## 应急与进程事务

应急 HOME 和运行根独立，不借正式环境或原生安全 Profile 伪装独立救援。五项受控修复工具不能任意安装包或执行 shell；写入通过原生确认、候选/源摘要/数据代次绑定和停止屏障。用户可另用正式终端修复。

停止以出生身份、会话及退出证据为准，禁止按端口、名称或裸 PID 强杀。安装、迁移和回切由宿主事务记录；结果未知时保留现场及运行任务屏障，不让用户清数据脱困。

## 交付与验证限度

正式包使用 `io.github.sunsetrne.dsha` 与 `ci/release-identity.json` 记录的 Mod 发布证书（v1/v2/v3）。构建不缺省发布口令，不替换为其它证书。build356 的 CI 只证明该 commit 在托管 runner 上的编译、门禁、单测、签名与发布流程；软件测试、APK/ELF/签名核验和手机覆盖安装是不同证据。发布准备的本地范围为本机宿主 contracts 整组、全树格式检查、全量单测（含 10 例 aarch64 环境基线）、lint、T145 的 `QuickActionsTest` / `QuickActionsBubbleTest` / 启动入口布局回归与 T146 的 `DeviceGrantsLayoutTest` / `GrantsAccordionTest` / 识屏与敏感界面测试 / 布局资源编译，以及 `UpdateVersionPageTest`（见 [build356 更新说明](releases/build356-changes.zh.md)）；真机结论待用户按当次记录核对后补写，未预写通过，不能继承旧报告的通过范围。
