# AGENTS.md

> Miuix 独立体验版：`standardPersonal` 使用包名 `com.dsh.client.miuix`、名称 DSHA Miuix、本机签名和 `debuggable=false`，可与原应用并存且数据独立。该构建类型不替换正式版 E7E3 签名规则，也不进入正式 release 目录。构建方式与验证边界见 `docs/ui-miuix.md`。

> 2026-10-17 UI 更新：按用户指定接入 `compose-miuix-ui/miuix`，覆盖历史“无 Kotlin / 仅 Material3”界面限制。Kotlin 限于 `ui/miuix` 的 Compose 展示层，业务保持 Java 17、单模块 `:app`。固定 Miuix 0.8.8 / Compose Android 1.10.5 / Kotlin Compose 编译器 2.3.20；保留 Standard API 30、Low API 23。当前迁移范围、定向检查与未验边界见 `docs/ui-miuix.md`；不将源码验证写成正式 APK 或真机交付。

> 2026-10-15 本轮新增授权：仅同步当前应用源码、构建脚本、必要文档与 Star History 到 GitHub；不上传 APK、离线归档、设备取证、密钥或本机交付资料，不发布 Release 或网站。下方历史“未上传”描述对应各自当时的本地交付状态，本轮源码同步范围见 docs/source-updates/build173.md。

> build173 PiP 补充交付（2026-10-15）：C17 标准包修复“小窗显示时停止 → 重启 → 再进入”残留 Task 0.275 缩放；失效空宿主退休，新代次使用新 Task，同代网页实例继续保留。62 项定向 Activity 宿主回归、76 项开机边界恢复关联测试及最终 37 项软件门禁通过；两版 JUnit 各1700项、40 skip，Release Lint/完整资产/历史签名通过。正式同签名 Android13 原应用覆盖后，真实重启恢复、受管更新、PiP 停止及导出停止后的新窗口5/30秒、同代恢复/返回/旋转均有实际记录。1269条数据无增删，个人配置、会话、附件字节保留；历史4条未知记录与1条追加消息缺口继续保留。有限日志有2次 renderer 报告和2次 SAF 系统捕获 NPE，实际导出成功，不作零崩溃结论。标准 APK SHA c11abc4197f747b690897ea2198c990514eedd5d6ca10b9111ce7da90ed02f57 / 288099627 bytes，已替换 release 同名 rc2.1 标准包；C14 原件及封存材料保留，Low172 原字节不动。回执 artifacts/deliveries/build173-standard-pip-fix.json，源码 artifacts/source/build173-pip-fix/，补充报告 docs/releases/v0.2.24-build173-pip-20261015.md；不发布 GitHub、网站或 Release。

> build173 标准版本地交付：10 个 gpt-6.1-sol / xhigh 代理处理当前开放条目及真实覆盖升级故障，撤回 QuickChat。最终 C14 标准包 0.2.24-20261014.1308-dsh0.2.0-rc.2 / 173，E7E3 / 非调试 / com.dsh.client，288,084,856 bytes；已同签名覆盖 Android13 原应用，启动、画中画停止重启后全屏恢复、旋转及最终无密码导出有实际记录。145 个宿主入口、两版 JUnit（各1673项，39 skip）/ Release Lint / 完整资产签名门禁通过；四小时仅继承9项输入逐字节相同的 build172 桌面证据。旧1094条归档记录全在，明确变更已分类，4条新增和1条追加消息保留未归因缺口。只替换标准版 APK 与校验；Low 按用户要求暂缓设备验收、交付仍保留 build172 原字节。回执 artifacts/deliveries/build173-standard.json，源码 artifacts/source/build173/，范围与缺口 docs/releases/v0.2.24-build173-20261014.md；不发布 GitHub、网站或 Release。

> build172 本地软件交付：10 个 gpt-6.1-sol / xhigh 代理完成 bxroot 迁移、全部 #1–150 处置、沉浸式系统栏、移动 UI 3.1.0、导出与外置文件保护。144个软件入口、两版完整 JUnit/Release Lint/完整资产与历史签名门禁、完整四小时 Chromium/Firefox 桌面持续回归通过；同名 rc2.1 APK已覆盖，build171原件保存在版本history。当前源码 0.2.23-20261014.0112-dsh0.2.0-rc.2 / 172；未做设备验收，不发布 GitHub、网站或 Release。最终回执 artifacts/deliveries/build172.json，源材料 artifacts/source/build172/，范围及剩余缺口 docs/releases/v0.2.23-build172-20261014.md。

## 本轮用户决定（覆盖下方与历史文档中的旧实现规则）

- 本轮新增修复只看仍开放的 issue / PR，已关闭条目不追做，插件收录跳过。QuickChat 已按用户要求撤回，不再恢复其生产入口。标准版优先，Low 不安装、不做设备验收，既有 Low172 交付文件和校验保持原字节；不能把软件双版通过写成设备双版通过。
- 引擎只保留 bxroot / proot。首次升级优先 bxroot；升级后的显式 proot 选择保留。启动阶段明确引擎失败、无插件故障和用户业务提交，且本轮受管后代树与 launcher 都已确认退出，才允许一次自动 proot 重试。慢启动、超时、未知执行结果和已开始的写任务不能借此重放。proroot 只保留历史记录读取，不保留生产执行。
- 彻底重装/完整格式化功能移除，包括生产入口、Intent 触发和擦除后端。外置工作区维护只能断开私有引用，不遍历清理、覆盖或改名其内容；旧恢复日志不能自动重放外置写删。冷测速等合法私有临时清理必须使用受控目标及 NOFOLLOW，不能继承完整格式化能力。
- 原生外壳的业务继续 Java 17，导航流程保持；界面允许按本轮 Miuix 迁移说明使用 Kotlin / Compose。透明状态栏、背景跟色、图标明暗与 Insets 继续共用原生处理。状态栏保持可见，键盘、挖孔和 PiP 的安全区规则保留；双网页内核使用同一个网页实例切换全屏 / PiP / 隐藏。恢复全屏时按系统当前 PiP 状态同步并幂等清除自有缩放与位移，不能依赖旧几何缓存宣称变换已清除。
- 内置移动插件固定 3.1.0 / ab3a85bead1c53b723e8627f5b44e3c306d85604；DSH 仍 0.2.0-rc.2，Ubuntu base10 不变。上游已吸收的本地差异退休，安全服务端替换、快捷键搜索、可见区域 CSS 和视口合并仍须实际回归。
- 屏幕确认策略为全部手动 / 按应用自定义 / 全部允许。全部允许覆盖敏感页面，仅免 DSHA 确认；系统能力、实际目标、运行代次、最新帧与节点身份仍校验。持久规则只在本机，停止使本轮许可失效，规则修改或撤销使排队请求失效，不从备份导入授权。
- 不实现整合包/市场及插件收录。任务通知采用标准进度通知，不请求系统 promoted 胶囊；原生终端、插件安装及生命周期脚本继续开放，工具审批不能宣称强沙箱。
- 当前源码版本码173，诊断版本冻结为 0.2.24-20261014.1308-dsh0.2.0-rc.2。本轮标准版交付文件为 dsha-0.2.0-rc2.1.apk；Low172 原交付文件保留。com.dsh.client / E7E3 / 非调试正式包不变。生成总资产及描述符、全量最终验证与交付由单一负责人在源码冻结后完成。

> build171 本地整合版：执行 PR102–150 中20个开放 PR 的固定源码处置，并修复 issue121/122/148/149 的已证实路径。默认 Web 端口由内核动态分配并复用上次可用端口；ADB 配对/连接端口继续由 Android 提供。保留最新帧输入门、未知执行结果不重放和退出屏障。文件名为 dsha-0.2.0-rc2.1[low].apk，包名及 E7E3 历史签名不变。本轮按用户要求仅软件验证与本地打包，不操作设备或发布 GitHub。最终摘要、检查及未验范围见 docs/releases/v0.2.22-build171-20261013.md。

> build170 上一已发布正式包：报告的 ADB 路径/摘要与导入回退、附件直接 SAF、草稿心跳/旧引用恢复已打包。真机发现冷 Python 多级父目录未建立造成 PARENT_LINK_OR_MISSING，已允许安全缺失后缀交原 NOFOLLOW 发布器创建；现有链接仍拒绝。两版各45项关联检查与 Lint 通过，Android13 原生补齐/校验安装15个wheel并SETUP_DONE，WebView/Gecko附件和草稿行为、最终两APK覆盖启动通过。手机最终Standard170/READY，APK已按后续授权正式发布 GitHub v0.2.0-rc2 / Latest，完整无线配对及原vivo16未复验，详见 docs/releases/v0.2.21-build170-20261009.md。

DSHA 重构骨架。本文让你不扫全库就能上手 —— 读它之前先读 [README.md](README.md)。

## 一句话

APK 用 bxroot/proot 把完整 Ubuntu rootfs 搬进 app 私有目录，在里面跑 Node 24 +
pnpm + `@deepseek-ai/dsh`（**0.2.0-rc.2**）的 Web UI（默认动态端口，手动模式可指定 `3080`）。原生业务层是 Java 17，界面使用 Miuix / Compose 与既有 Material3 控件，单 Gradle 模块 `:app`。

## 技术约束（围绕这些设计）

- **build171 整合约束**：自动端口先 `--port 0`，仅复用本机保存且仍可用的实际端口；旧自定义端口继续手动，旧默认3080转自动。完整官方启动行才可鉴权，只有单一明确 bind 冲突且本轮 guest 核验退出后可重试一次；LAN 绑定同代次实际端口。旧端口网页数据通过原生恢复入口保留源与候选，先关闭预览并核验再写空白目标，不转移凭据/lease。内部破坏性工具 guard 不构成系统沙箱，原生终端和插件安装保持开放；已知绕过与设备限制须如实声明。ColdSetupTiming 归 core 编排，冷安装仍做完整内容摘要，不用 stat 缓存作为提交证明。

- **网页文件与虚拟屏（build164）**：WebView 的 `window.DSHA.shareFile/saveFile` 接收网页已有的 File/Blob，通过精确同源、顶层帧、当前运行实例的 WebMessageListener 转为受限流式传输，不接收本机路径。分享缓存的实际文件名与显示名一致，FileProvider仅临时授权读取；返回chooser-opened只代表选择器打开。下载绑定原download文件名并反馈保存/取消/失败。原生分享在Android13通过，Android SAF最终落盘及原Android16报告设备未复测。`VirtualScreenCore --launch` 在握手/清理完成后显式System.exit(0)，避免ROM非守护线程阻止父进程结束；不能因此放宽通道鉴权或成功退出判定。

- **build171 历史本地源码版本为 0.2.22-20261013.1248-dsh0.2.0-rc.2 / 171**：本轮网页附件选择修复范围见 `docs/releases/v0.2.18-build167-20261008.md`，移动UI同步继承166，文件分享与虚拟屏修复继承164，shell临时目录修复继承163，issue95/97/98与PR94继承161、说明继承162；完整冷安装继承160，236项历史处置见build156报告，本轮验收以实际新包记录为准。继承153的PARENT_LINK与重建回切修复；用户导出真实无密码tar.gz，保留历史加密原件读取。APK文件名为dsha-0.2.0-rc2.1[low].apk，升级受管DSH运行时并保留历史数据读取兼容；链接、本地包与更新解析后自动提交并启用。插件维护与终端按用户要求开放未锁定依赖安装、生命周期脚本和修复shell。上游已将Agent预设会话头标签设为只读，并接管模型设置入口；不要把rc1的自定义标签/模型导航补丁重新套回新版本。
- **独立应急 DSH**：`recovery/RecoveryController` 与 `RecoveryRuntime` 不经过正式环境的迁移、数据绑定及全局启动锁。归档由 `tools/recovery-runtime/lock.json` 单独锁定；应急 Profile 仅有五个受控修复工具，在自身 HOME/workspace 初始化真实工作区。不能用“原生安全 Profile”替代独立运行根。`READY_READ_ONLY` 只允许已鉴权且工具检查通过、但进程身份被系统拒读的本次进程；不得登记正式停止屏障豁免或允许确认写入。
- **应急请求扩展**：`RecoveryRuntime.prepareProfile` 写入的 `dsha-recovery-agent/package.json` 必须有有效 `name` 与 `version`；锁定 rc2 的官方 `dsh_plugin_packages` 在模型 HTTP 前枚举活动插件。缺版本会抛 `REQUEST_EXTENSION`。宿主夹具必须调用真实 `deepseekLlmApiExtensions.prepare()` 并核对该包；启动没有模型凭据时，原生/网页入口明确提示可先诊断、对话需填写临时密钥。
- **旧 Web PID 复用**：主 Web 有有效旧 PID+出生身份记录、内核 `kill(pid,0)` 明确 `EPERM` 且二次核验一致时，可以只隔离旧记录，不给该 PID 发信号；这是应用启动 Web 仍在同 UID/运行域的受限判据。单纯 `/proc` 拒读、旧记录缺失或损坏、同 UID 不可核验及隔离试运行继续保留停止屏障，不让用户清数据来摆脱死循环。不得按裸 PID、端口或名称强杀。
- **应急资产去重**：`recovery-asset-locations.json` 仅映射签名 APK 中的物理位置，不进入应急内容 runtimeId。仅当正式归档 SHA 与独立应急锁完全相同才共用包内字节；不同时必须打包固定应急副本。解压运行根仍独立，不读取正式 rootfs 作为救援依赖。Gradle 追踪实际共享资产及 pinned 副本；增量打包清除已省略的生成归档。当前 APK 门禁必须要求映射存在，历史 ELF 审计才允许无映射格式。
- **应急修复确认**：`RecoveryRepairBroker` 的 HTTP 接口不能确认写入。原生确认绑定候选、源摘要及数据代次，再经过既有停止屏障和宿主事务；停止应急聊天后，已经确认的原生修复 lease 仍由前台服务保护到结束。`recovery-active` 仅追踪未关闭实例，历史数量不能成为永久启动上限。应急运行结束和合法私有维护仍须确认 guest 与 launcher 均已退出；完整格式化生产能力已退休。
- **build171 移动插件源码修订（历史；当前固定3.1.0见上方）**：内置 `dsh-web-mobile` 为 `3.0.5`，固定到3.0.5标签之后的上游main提交 `9b16223e6c5ee8209c25034b24fb790990967b3f`。`tools/apply-mobile-client-patches.mjs` 管理本地差异；保留手机快捷键搜索、首次自动聚焦的定向限制、手势取消及聊天区域观察器优化，不恢复上游已撤回的整批改动。更新后执行真实 rc2 Modal/ShortcutReference 行为测试。
- **麦克风**：清单同时声明 `RECORD_AUDIO` 与普通权限 `MODIFY_AUDIO_SETTINGS`，Chromium 的录音设备选择需要两者；不能只验证录音权限。`RECORD_AUDIO` 由 `BrowserMicrophone` 按网页请求申请，不能在普通启动时预授权。WebView 与 Gecko 只放行当前本机页面的纯音频请求，严格核对主机名、实际端口、页面和运行代次；摄像头/屏幕音频拒绝。页面取消后等待旧系统结果排空，各系统请求使用独立注册身份，旧回调不能批准新页。应急 localhost 不与正式 127.0.0.1 混用来源。
- **备份新要求（2026-09-17）覆盖旧的禁用自动备份规则**：`AutomaticBackups` 默认开启，独立本机计划支持每日、1–168 小时间隔和停止后。使用既有宿主 v5 加密与预检，后台作业不强停 Web/终端。正常自动副本保留 3 份，手动、未知、部分记录不自动删除；插件依赖提示不能改称完全通过。本机密钥和副本不提供卸载存续保证，界面必须引导导出无需密码的副本；历史加密原件的原密码仍需保存。
- **旧环境清理新要求（2026-09-17）**：覆盖更新在闲时清理可再生缓存及多余健康受管副本；旧运行时的读写不兼容只阻止回退，不能令全部旧组件永久堆积。保留至少两份健康且字节仍符合计划的副本，修改过或含额外文件的原件不删除。新重建通过数据和运行核验后建立 `retired-proof.json`，核对旧树 inode、树摘要、数据归档及映射，再清理 `previous-linux`；无证明的历史重建原件继续保留。
- **设备验收经验**：`uiautomator dump` 会抑制其他无障碍服务，活动配对/持续读屏验收期间使用普通截图和实际本机桥；屏幕授权绑定 DSH generation，停止/断连/手动撤销后失效。截图使用应用私有 Pictures/DSHA，无需所有文件访问。PiP 可能盖住底部控件；自动化必须核对实际可见区域与屏幕方向，不能只按底层 XML 坐标点击。截图/读屏不可将敏感值写入公开取证文件。
- **交付边界**：E7E3 发布证书继续用于正式 APK；A3 仅用于明确的手机验收场景。本机验证不得扩写成 Android 6/7、16 KiB 或全部外部模型服务矩阵完成。DSH 0.2.0-rc.2、Ubuntu 10；Standard/Low 和虚拟屏能力仍按实际设备证据声明。正式 APK/签名与实际资产、两 flavor 受影响单测、Release Lint 和本轮必要的非破坏性覆盖检查完成后写入版本目录；未改动的 native/归档验证可在逐字节一致核对后引用已有证据，不冒充重跑全量矩阵。


- **用户最新验收与交付要求（2026-09-14，覆盖下文和历史文档中的旧审计包流程）**：不再生成、安装额外测试/调试/审计 APK；直接使用原包名 `com.dsh.client`、历史同签名、非调试正式 APK 覆盖安装，在实际应用中做非破坏性操作检查。已获得覆盖安装授权，不反复询问。保留用户对话、配置、插件与文件，不在正式数据上运行强杀、删 Bash、坏插件等故障注入。确认对应正式版本正常后，替换 `release` 里同版本同 flavor 的既有 APK 与 `.apk.sha256`，使用常规文件名；不要再新增 `-buildNNN` 并列副本。不同历史版本继续保留。测试源码/单测与历史报告可保留，历史审计工具仅作参考，不因旧脚本默认值再次生成测试 APK。
- **签名规则（最新验收要求）**：`release` 和手机验收统一沿用 E7E3 历史发布证书（完整指纹见 build 131 记录）；A3F4 仅保留为历史手机验收规则，不得用于本轮正式验收或放入 `release`。覆盖安装前核对包名、版本码、证书和 `debuggable=false`，不得卸载后用错误证书替代。

- **发布交付目录为 `F:\DSHA_RESTART\release\<APK文件名版本>\`**：只放两版 APK 和对应 `.apk.sha256`，同组历史 APK 放该版本的 `history/`。源码放 `artifacts/source/`，回执放 `artifacts/deliveries/`，其他历史原件移至 `artifacts/release-history/`；迁移记录保留原始 SHA，旧封档正文不改。
- **业务 Java 17，Kotlin 仅用于 Miuix 界面**，单模块 `:app`。
- `applicationId com.dsh.client`；Java 包 `com.deepseekharness.app`；标准版 `minSdk 30`、兼容版 `minSdk 23`，
  `compileSdk/targetSdk 37`（SDK 平台包 `android-37.0`）、AGP 9.1.1、Gradle 9.3.1、NDK 26、**arm64-v8a only**。
- 离线 rootfs（`assets/offline-rootfs.bin`）等大型生成归档**不提交**；正式本地/CI 包必须含经过核对的完整 Ubuntu 与 DSH 分包，不能把源码检出缺少归档当作正式精简交付。
- 新 dsh 依赖由 `tools/dsh-runtime/package-lock.json` 锁定，运行 `tools/prepare-dsh-runtime.py` 生成覆盖层；构建会校验补丁与覆盖层摘要。完整、兼容、最新分别判断：`tools/prepare-runtime-descriptor.py` 生成独立受管资产/启动器/桥接/数据契约描述，APK 版本仅作诊断来源。相同基础版本更新受管树，个人数据保持原位；不因 UI-only APK 更新替换环境，不因普通 dsh 更新递增 Ubuntu 基础环境版本。
- rc2.1 本地交付以 `docs/releases/v0.1.5-rc2.1-build130.md` 的产物摘要为准，专项逐项证据见 `docs/backup-upgrade-acceptance.md`。不得把早期 `app/build` APK 当作交付包，也不能把当前设备验收称为全部历史数据及设备矩阵完成。
- build 131 稳定性修订沿用 `0.1.5-rc2.1` 名称；之前的 `-build131` 文件名和不覆盖 build 130 的方式为历史记录，现按上方用户最新要求使用同版本常规文件名替换。验收记录见 `docs/stability-acceptance.md`；未经另行要求不得上传。
- `HttpProtocol` 在读取期间限制头部实际字节和基于单调时钟的总截止时间；桥的命令查询串预算不得缩到低于既有 8192 字符命令的编码需求。正文/鉴权后的执行与头预算分离。LAN 的 `SocketDispatch` 在登记前限制连接，短请求、长流及反向 pump 独立有界，停止必须关闭本轮所有 socket。
- LAN 正文由方法、状态码和明确的传输边界决定；零长度不是缺失长度，只有真正验证过的 101 WebSocket 握手可切隧道。分块需消费并检查 trailer，正文保持字节流；不得把 hop-by-hop 头移除后继续发送不匹配的编码体。
- 插件依赖使用随包 pnpm 10.34.5，记录实际解析所得锁及目录摘要。现行正式策略允许未锁定重解析、生命周期脚本及 pnpmfile；事务提交仍绑定实际候选，不能预览后偷偷改成另一组内容。历史完整副本读取不重新解析依赖，未知信息如实标记。
- 插件安装的原件与目录切换由 `plugin-transactions.py` 记录，原生 `PluginInstallJournals` 加入既有维护门禁。未提交日志不得被普通启动或其它恢复覆盖；终止进程后按固定目标与前后摘要恢复，已提交日志不回滚后来数据。工作目录中的旧历史和失败候选保留，不由临时目录清理隐式删除。
- **0.2.0-rc2 插件策略覆盖旧审阅规则**：链接、本地包和更新经包格式、路径、摘要与事务检查后自动提交并启用；依赖允许未锁定重解析、生命周期脚本和 pnpmfile，仍记录实际锁与目录摘要并可事务回滚。网页端可以直接安装、启用第三方插件；用户主动禁用及安全模式意图仍保留。升级只解除旧版 `DSHA_REVIEW_REQUIRED` 自动标记，不解除空标记或 `DSHA_SAFE_MODE`。浏览器握手前重启 Web 时，复核候选摘要并再次尝试加载，不能仅凭上次 `attempted` 状态停用；真实加载失败仍可诊断，不回退聊天或其它业务数据。
- 签名系统插件与用户插件必须分层：系统插件源码、实体和依赖不进入用户备份，只保存每个 profile 的启停意图，并由当前 APK 的精确受管证明重建；用户插件源码、实际依赖组和包装脚本照常归档。旧 v4 图可读，但其中系统插件字节必须丢弃。`.disabled` 与旧 bundle/依赖/实体冲突时，禁用意图优先，旧实体移出加载路径并保留到系统插件隔离区，不能静默覆盖或重新加载。
- DeepSeek Messages 的受管补丁只把用户消息或 `tool-result` 内用于展示的嵌套 `tool-call` 投影为稳定文本；assistant 的真实工具调用、调用 ID、顺序、错误语义与图片结果必须保持协议结构。补丁锁定 `@deepseek-ai/dsh-llm-deepseek` alpha.2 源码锚点，不修改旧会话文件；Agent Team、compact、普通双工具、错误结果和图片结果必须一起回归。
- `CredentialRead` 区分未配置、成功、暂时不可用与需处理状态；调用方不得重新折叠为空字符串。失败不自动清空旧密文或创建替代解密密钥；保存新值须先加密并读回验证，配置页保存其它选项不能清掉不可读凭据。
- `RetainedCatalogue` / `RetainedDataActivity` 仅提供只读清单、逐项检查、导出及预检恢复，不新增自动删除。旧树解析只用所选旧 rootfs 的映射，不借旧链接读取当前数据；未知记录单独保留。隔离插件的原始依赖组不修改，复制候选并重新检查后经用户选择恢复启用；同名冲突保留当前版本。
- 数据格式不能由 APK 或 dsh 版本号推导。`DataFormatEvidence` 对未经完整检查的数据保留 unknown；运行时回退要求读写包与相关持久化配置补丁指纹一致，并继续执行实际试运行。发件人声称的已验证格式不能成为本机兼容或删除原件的依据。
- 新受管更新使用 `ManagedRuntimeTransaction` / `HostDataTransaction`，旧 `RuntimeUpdateTransaction` 仅保留历史日志恢复。真实隔离 `RuntimeTrial` 核验静态资产/原生模块、实际端口/鉴权、存储和会话写后重开、网页内核与后端握手及 guest 退出后才能提交。回执保存在 rootfs 外，并绑定受管文件摘要。至少保留直接健康前代；只在维护屏障内按已验证策略清理更旧版本，未知或受损原件不自动删除。
- 旧运行时自动轮换必须重新核对 plan 中的逐根摘要与目录成员，不能仅凭缓存健康回执计数或删除。修改过、不可读或含清单外文件的副本原位保留，也不能将其计入“仍有两份健康前代”的数量。
- **历史 proroot 试运行记录规则**：`RuntimeTrial` 必须记录实际 `runtimeMode`。所选 proroot 在鉴权前明确退出、没有插件故障且 guest 已核验退出时，可按 `WebRuntimeFallback` 仅重试一次 proot；保留脱敏失败输出与退出码，回执标明 `fallbackFrom`，不能把 proot 成功报告成 proroot 原生成功。静态检查继续先选 proot，慢启动不触发重试。
- `RuntimeTrialRecords` 只轮换已确认关闭的现场：保留最近 3 条成功和 5 条失败，未关闭或标记异常的目录不得自动删除并继续要求恢复。不能再以累计 32 次为永久上限；连续 33 次成功/失败交错试运行必须仍可开始下一次。
- 冷解压和受管候选必须共用内置插件依赖链接与局域网设置补丁准备，先完整准备再计算摘要和切换。不能依赖注册用户 profile 或后续第一次 Web 启动补齐候选。
- Android 的 `FileInputStream(FileDescriptor)` / `FileOutputStream(FileDescriptor)` 不接管传入描述符；宿主备份通过 `ParcelFileDescriptor` 自动关闭流明确转移所有权。真机验收必须使用非调试构建并检查大量文件读写后的描述符数量。
- 兼容运行时回退仍使用当前 APK 的启动器，在隔离 profile 下重新试运行；不恢复旧对话快照。前代必须可读取当前运行时写入的数据格式，且其受管清单覆盖直接前代关系。未确认进程退出时保留运行任务锁和所有现场。
- 宿主归档 v5 外部导出使用 `DSHA-data-v5-<UUID>.tar.gz`（标准 tar/gzip 封装无需密码的 DSHADATA 完整性载荷与说明，内部自动副本格式保持），用户导出无需密码且无加密选项，统一保存 DSHADATA 完整性容器的 tar.gz；旧加密备份读取仍核验完整 AEAD 认证，自动副本仍由宿主内部随机密码加密；原生 API Key 默认排除。先完整认证/摘要核验再预检，只写私有编号槽位。范围、逻辑根和实际记录双向核验。本机状态不能从用户归档生效；受管脚本、可执行配置和未知插件先隔离。依赖版本/JAR 摘要由 `tools/backup-dependencies.lock.json` 与 `verifyBackupDependencies` 核验。
- 私有验证副本通过 `VerifiedBackupCopy` 重新核对加密文件摘要后，可由 `NativeBackupJobs.reexport` 复制到新目标；沿用原密码和真实范围，不提升 PARTIAL/BEST_EFFORT 或插件警告等级。坏记录保留并单独报告，不能挡住其他可读副本。
- SAF 查询、打开和流读写共用 `DocumentStreams`；取消不能在主线程等待远端 Provider。读取必须保留 AssetFileDescriptor 的 offset/length，不将部分区间误读成整个底层文件。
- 启动观察器沿用锁定 `dsh-app-boot.resolveBundleDir` 的安装/配置两个 anchor 与 Node 查找顺序，不能只查固定四目录误报全局插件丢失。`persona-compat-patch.json` 仅兼容已知旧 text 字段，保留用户提示词和现有 prefix，不能以默认空内容掩盖无效配置。
- 维护成功文案不能代替就绪检查；未就绪时留在原生页，不自动跳回循环。维护页的忙状态不使用插件查询/终端等通用锁冒充数据维护。终端出生身份不变；exec 窗口内无效 stat 必须按内核会话号复核，不能直接当作已退出。
- 配置重置由 `NativeConfigurationReset` 与原 `HostDataTransaction` 执行，只切换已确认的 settings.yaml 和私有工作目录 .env；使用检查后的原生凭据。重置日志仍归 `host-backup-operations`，统一恢复入口按专用记录识别；不再先运行旧 Python 备份，也不接受外部/链接目标作为可安全覆盖的私有配置。
- **完整格式化已退休**：不得恢复生产 UI、Intent、任务或擦除后端。历史日志只读解析并保留；合法维护的私有暂存清理继续限定本机编号根、NOFOLLOW 与退出屏障，取消继续抛出，个人数据根的错误不得借可再生缓存规则降级。
- 同基础版本的旧 dsh 缺失或被修改仍走受管更新，不因 package.json 检查失败转成 Ubuntu 重建。覆盖更新不归档系统；确需重建时只保护对话、附件、配置、插件与个人项目，旧系统目录仅通过同盘改名暂存作回切。
- 就绪检查必须解析实际 `/bin/bash` 的 ELF 和加载器路径，不能以 `/usr/bin/bash` 或旧标记存在代替；可执行位使用 lstat，不能用受 Android W^X 影响的 canExecute。损坏系统不得反复执行配置安全启动，应进入环境修复。
- 基础重建的数据保护由宿主 `MaintenanceDataSnapshot` / `MaintenanceDataRestore` 与 `EnvironmentRebuildTransaction` 执行；无生产调用者的 `EnvironmentDataBackup` 和 `environment-data.py` 已保留原字节后退休。旧日志/归档读者继续保留；独立旧工具只用于仍有明确消费者的维护职责，不能作为新宿主归档的隐式回退。未完成安装且无法确认旧数据位置时不能宣称全量对话备份。
- 附件模块与文件/会话模块共用 dsha-runtime-fs 原子发布：附件别名必须保留源对象并保留 EEXIST 语义；老内核仅在协作发布者之间使用加锁降级。验证真实图片、文件、流式写入、同摘要别名、冲突和取消，并覆盖私有目录与 attachments 软链到 Android FUSE 的路径。
- 旧的按启动次数自动备份保持删除。按上方用户新要求使用独立 v5 自动备份计划，不复活历史计数偏好；普通启动不得遍历整个工作区或会话目录做旧链接修复。
- APK 使用 `offline-rootfs.layout=split-runtime-v1` 标记 Ubuntu 与 `dsh-runtime.bin` 分包；冷安装两份都解压，局部更新仅读 dsh 包。两份内容各保留一份，不改基础环境身份，构建核验两份摘要及运行时完整性。
- 数据维护先关闭 PTY 与简易终端，再取得 `RuntimeTasks` 屏障；引擎终端按本次独立会话及受管后代树核验并回收 guest，不能只结束启动器就释放工作锁。无法确认时保留原环境。
- PTY 出生身份在原生 fork/exec 握手中登记：子进程自读 stat，父进程确认后才 exec。不得退回 Java 启动后单次读取，或停止时按裸 PID 临时认领；终端维护必须补测非调试 APK，调试版不能覆盖发布版的进程信息访问时序。
- `client-combo-patch.json` 与 `client-combo-cache` 适配锁定的网页拼接器；只复用上一轮未变化的脚本/映射，HMR、插件移除和顺序变化必须失效，保留上游脚本、源码映射、URL 与修订号语义。
- 网页 ES 兼容依赖锁在 `tools/web-compat/`，运行 `node tools/prepare-web-compat.mjs` 生成随包脚本、许可和输入摘要；构建核验摘要。官方 PDF 模块与独立 Worker 都需要兼容代码，旧 Chromium 的非特殊 URL 文件协议需单独适配；不能只在桌面新浏览器或预热环境验证。
- Agent 预设切换必须调用锁定 Host 的 `agentPresets.select` 并核对返回值。已有内容的会话保留，通过同工作区新会话应用所选预设；禁止只改标签伪装切换成功。
- 预设按钮自身提供基础样式，不能仅依赖窄屏移动插件消除浏览器默认边框。触屏宽屏的预设与文件入口靠右，文件入口调用真实 `sidebarRight.openTab('files')`；验证窄屏/平板横屏及菜单、文件面板行为，保留键盘焦点提示。
- 中英文界面默认跟随系统：偏好值为 `system`（默认）/ `zh` / `en`，系统语言是中文（或无法识别）落中文，其余落英文（英语是唯一可用的国际语言，比中文更可能被看懂）；旧版本只写过 zh/en，老用户升级后保持原语言。**系统语言必须在进程最早时刻锁存**（`SystemLanguage.initialize()`，早于任何 `Locale.setDefault`），否则 `Resources.getSystem()` 会被界面语言污染，「跟随系统」切一次就自我锁死。文案目录为 `tools/i18n/messages.json`，构建生成 Java 文案字典；布局使用对应中文/英文资源。语言切换只重建界面，不能停止终端或 Web；用户输入、聊天、文件名及命令原文不做自动替换。系统 WebView 与 Gecko 通过同源桥同步到实际 locale 服务。
- 应用状态在显示边界通过 `UiStateText` 重新渲染，不能缓存某种语言后一直显示；动态文案只匹配目录中声明的完整模板，参数与第三方插件描述保持原文。应用弹窗统一使用 `DshaDialogBuilder`，自定义内容必须能在短屏和大字体下滚动。
- DNS 默认 `auto`：Node 双栈 `lookup` 仅在 `EAI_AGAIN` / `EAI_FAIL` 时重试一次 IPv4，不重放 HTTP 请求，不降级显式 IPv6。配置页另有 `ipv4`（glibc `no-aaaa`）与 `native`；只增删受管解析选项，保留用户 nameserver/search/options。Web、插件、普通 shell 与 PTY 共用 `RuntimeTools` 的预加载环境，Ubuntu 基础环境版本不因该修复变化。
- 原生按钮和选项统一居中与字体边距；普通卡片、主按钮及文字状态共用主题资源，不能再用独立渐变或硬编码颜色制造同类框色差。布局修订运行 `LayoutAuditInstrumentation` 的 `style` 中英文验收，覆盖日夜、短屏和 1.3 倍字体；滚动内容按标题与底部按钮的实际高度分配，不写死英文标题所需高度。
- PTY 与简易终端使用独立标签和会话，跨切页、旋转及语言切换保留进程与各自状态。关闭单个标签必须核验该会话退出后再移除；失败保留标签和工作锁。维护入口仍要关闭全部终端并取得原有屏障，禁止把标签移除当作进程已退出。
- 终端会话 ID 永久递增，显示编号独立复用最小空缺；全部关闭后新建显示 1。关闭中或关闭失败仍占用编号，过期回调不能按显示编号关闭新会话。
- Web 启动等待鉴权不设强制终止时限，60 秒仅提示；保持 `isStarting`，避免看门狗把慢启动当故障。停止与维护必须共用 `WebProcessManager` 的 PID 身份/进程核验，不能重复使用裸 `kill(pid, 0)` 阻塞过期编号。
- Web 首选端口冲突时保留用户配置，先尝试本机成功备用端口，否则由锁定 dsh 的 `--port 0` 分配。动态鉴权只接收本轮官方启动行；保活、鉴权和 LAN 必须使用实际 Web 端口，不能继续探测已被占用的首选端口，也不能按端口猜 PID 终止进程。
- 旧 WebView 的 AbortSignal.any/timeout 与随机 UUID 补齐沿用 1.1.10 的提前注入思路：共同兼容脚本须进入受管 HTML 的应用脚本之前，并覆盖文档起始与 Worker；不能只在加载结束后隐藏能力错误。保留原生实现、取消原因与监听清理，验证接口缺失时的真实页面及重载。
- 启动诊断通过 `StartupDiagnostics` / `StartupTrace` 保存阶段、实际插件错误和退出码；失败输出另存，后续启动不可覆盖。兼容重试按本轮 bxroot 启动规则执行：合格只读探测失败或可信监督器证明引擎未执行，没有插件故障或用户业务，本轮后代树与 launcher 均已确认退出；只重试一次，不能由超时触发。
- 启动日志区分应用事件与原始进程输出：应用事件在显示时按语言渲染，不能全文替换命令、插件异常或用户内容。维护横幅只读取数据维护状态，不能把启动、插件查询或终端使用的通用执行锁当成维护。
- 明确的启动配置/插件故障或启动进程退出后自动进入原生 `StartupRecoveryActivity`；等待鉴权本身不是故障。最近五次脱敏启动记录独立保存在私有目录。六文件检查点现由宿主 `ConfigurationSnapshots` 执行，原生读取旧 `startup-checkpoints.py` v1 数据；正文逐文件存于 rootfs 外，保留三次健康与三次修复前快照。健康快照须匹配启动前捕获的内容及本轮启动 ID。配置恢复/新建仍通过 `BackupTask` 停止屏障；旧日志已提交后不得覆盖后来修改的配置。缺日志的旧状态标记只保留并解除，不猜测要回滚哪份数据。
- 原生插件归档记录 package、profile 与 shared 的实际绑定，包含未声明但确实存在的依赖。`PluginRestoreGraph` 在同一隔离目录重建相对依赖链接及命令入口，保留改写前声明，不运行安装钩子。缺少可验证的当前受管依赖时，保留源码并报告关系未完整，不能伪称插件已启用或可运行。
- 安全启动使用宿主 `NativeSafeProfile` 创建 `dsha-recovery-<16位十六进制>` 独立 profile，不依赖 Python；只加载官方基础组件并保留原 web profile。`WebProcSel` 必须能正确停止此类 Node 进程。启动观察器适配锁定 Cordis 的真实 Entry.init，不拦截全部 Node 模块解析，不把可选依赖探测误报为故障。
- 个人文件迁移、安全配置与配置快照的 Python 维护脚本和插件管理一样，预先选择 proot 执行；目录存在探针不能代表完整遍历/归档可用。不能在写入结果未知后自动改通道重放。Web 本身继续使用所选运行方式。
- 离线 curl/git/证书及依赖由 `tools/ubuntu-tools/packages.lock.json` 锁定，运行 `tools/prepare-ubuntu-tools.py` 生成 `ubuntu-tools.bin`；新环境通过 dpkg 离线安装后删除安装包。生成文件不提交，不省略冷环境的完整安装检查。
- `standard` / `low` 两个 flavor 共用功能代码与 Ubuntu Python。标准版使用系统 WebView，兼容版额外带 Gecko 143，在 Android 6/7 或旧 WebView 时使用；构建任务为 `assembleStandardRelease` / `assembleLowRelease`。
- Shizuku 必须注册 `rikka.shizuku.ShizukuProvider`，由 Application 监听 Binder，不依赖 ADB 开关。标准版 API/provider 为 13.1.5；兼容版为支持 API 23 的 12.2.0，不用 overrideLibrary 掩盖新版库的 minSdk 24。Root、Shizuku、ADB 在发送前选择通道；结果未知时不能自动切换通道重放。
- Shizuku 管理器按 API_V23 权限所属包识别，用户点击时可通过标准 REQUEST_BINDER 入口恢复连接；回调严格核对管理器 UID、Binder 描述符、单次请求与超时，再交给本应用受保护的 Provider。不得放松 Provider 权限、代替管理器授予权限或执行回调附带的 APK 路径。
- WebView 与 Gecko 的文件选择结果统一通过 WebUploads 解析 ClipData，多选优先、单选回退，保持顺序并去重；只接收 content URI，复制与数量/大小限制保持。回归需验证真实 Activity 文件回调返回两份文件及其字节。
- 设备通道与权限集中在「设备能力授权」。启用且经管理器授权的 Root 直接走 su，同一设备命令策略在特权进程内再次验证；无须先配对 ADB。
- 兼容版 proot / loader 从 `src/low/jniLibs` 选择 API 23 构建；重编脚本 `tools/build-low-proot.py`。终端 JNI 同样以 API 23 构建，并保留 16 KB 对齐。不要把标准版 proot 当作 Android 6 可执行文件。
- 构建通过 `tools/prepare-standard-assets.py` 生成 `app/build/generated/standardAssets`，需要 Python 3.9+（可用 `DSHA_PYTHON` 指定）。不直接修改原始 rootfs；仅重新压缩和清理预装缓存时不要递增环境版本，避免触发旧用户清空重装。
- `RuntimeTools` 负责随包 CA、npm/npx 与 dsha-plugin 入口；插件、普通 shell、PTY 必须共用其环境，不能依赖用户先跑 apt 才有证书。终端 JNI 保持 max-page-size=16384 / common-page-size=4096，并核验 RELRO 在 4 KB 与 16 KB 页映射内。
- `NARB_DISABLE_NATIVE_CACHE=1` 让原生扩展直接从随包目录加载；其默认 link+unlink 缓存会在 `--link2symlink` 下首次悬空。冷安装必须验证 Cordis 内部加载器第一次即可初始化，不能只检查 .node 文件或预热后复测。

## 分层与归属（改代码前先看这里）

| 层 | 类 | 职责 |
|---|---|---|
| `ui/` | MainActivity / WelcomeActivity / Launch·Settings·TerminalFragment | 界面与启动门禁 |
| `core/` | HarnessController / WebLifecycleController | 兼容门面及唯一 Web 编排；应用状态归 HarnessSessionState |
| `core/` | ConfigStore | 配置唯一读写入口（端口/模型/workdir/API key） |
| `runtime/` | ProotBootstrap / RuntimeExecution / GuestPluginScripts / TerminalRuntime | 运行门面、执行租约、插件脚本与 PTY 启动职责 |
| `runtime/` | ContainerRuntime | 运行时选择 + BINDS 挂载清单 |
| `runtime/` | WebProcessManager | 停止 Web（写哨兵 + 按 pid 文件杀） |
| `data/` | KeyVault | Keystore AES/GCM 加密 API key |
| `bridge/` | HttpShellService / BridgeRoutes / AppDeviceActions / DeviceFileCore | 3090 鉴权/授权/路由与具体设备动作；特权文件描述符边界 |
| `util/` | Constants / ShellQuote / Query / WebProcSel / BackupScope | 纯逻辑，无 Android 依赖，**必须配单测** |

## 启动契约（不可破坏）

- `welcomed == false` → `WelcomeActivity` → 点开始 → `MainActivity`。
- `MainActivity` 进入前校验 `welcomed`，否则永远回 Welcome。
- 正常运行仍检查 `.offline-extracted` 与完整环境身份。维护页允许用户进入受限主界面查看配置和日志；此入口不能伪造就绪标记，终端、Web 与插件执行仍须通过环境门禁。
- 个人文件迁移读取独立映射下的实际 rootfs，不能遍历 guest 的外部挂载；宿主绝对路径镜像中的 `.l2s` 挂载占位要在遍历前精确排除，不能粗略跳过整个 `/data` 或忽略真实个人文件的权限错误。旧 L2S 文件链接按 guest 根与宿主别名映射读取内容，拒绝循环、越界和外部挂载。写归档时同步计算源文件摘要，保留归档与恢复后逐文件核验，并向原生界面持续反馈进度；重新打开维护页仍显示上次失败原因。
- 短信查询属于独立敏感能力，只允许当前 Android 用户的严格 `content query`。原生预授权默认关闭、可撤销，不从系统备份恢复；不能借此放开内容提供者写入或系统危险命令。
- DocumentsProvider 保留 `root` 和 `linux/ubuntu/...` 的既有不透明文档 ID；树授权依据逻辑子路径与实际解析路径共同核验，不靠字符串前缀改命名空间。支持创建、读写、重命名和删除；guest 绝对软链接按 rootfs 解析，删除链接不递归目标。写文件先验证已打开的文件描述符，再截断；描述符关闭前保留运行任务锁。

## 安全网：纯逻辑必须能单测

`util/` 下的类**不得** import Android API。任何新增纯逻辑都要在
`app/src/test/java/com/deepseekharness/app/util/` 下配 JUnit 断言，跑：

```bash
./build.sh :app:testStandardDebugUnitTest
```

下面四类基础逻辑的不变式仍须保持；当前完整单测和宿主入口数量以本轮真实回执为准：

- `ShellQuote`：POSIX 单引号转义，恶意值不能逃逸。
- `Query`：逐参数名匹配（`indexOf(key+"=")` 会被后缀劫持，已修）；「参数为空」≠「参数不存在」。
- `BackupScope`：部分备份绝不叫 `DSHA-backup-*`（否则老版本当全量恢复会清掉配置与插件）；
  `dshPaths` 与 `mergeSubdirs` 一一对应。
- `WebProcSel`：认得出 dsh 进程、**绝不把 bxroot/proot 或历史 proroot 误认成 DSH 进程**（杀到容器启动器 = 环境连 App 一起带走）。

## 已知 trap（搬自原版，骨架已按此设计）

- **停止靠 pid 文件，不靠端口反查**：`/proc/net/tcp` 非 root 读不到（静默空），`/proc` 有 hidepid。
  启动时 `echo $$ > /root/.dsha-web.pid` 再 `exec node`（exec 不换 pid）。
- **停止先写哨兵 `/root/.dsha-stopped`**：看门狗/重启脚本见到就退出，否则「秒复活」。
- **app 私有目录禁 `link(2)`**（SELinux），proot 必须带 `--link2symlink`。
- `PROOT_L2S_DIR` 在 rootfs 内的 `.l2s`，必须把该目录绑定到相同的宿主绝对路径；否则 dpkg 安装时对硬链接执行 chown/stat 会报文件不存在。
- **两把签名钥匙各管一件事**：线上 APK 用 debug keystore（历史原因），增量更新清单用
  `DSHA-release.keystore`，绝不混用。

## 当前执行入口

Web 生命周期、LAN 鉴权、配置维护与运行环境准备由明确协作者承担，`HarnessController` 保留既有调用门面。冷安装、插件命令、普通执行与 PTY 启动各有职责边界；完整模块归属见 `docs/module-map.md`，不要按早期骨架的回填清单重建第二套实现。

备份/恢复、基础重建数据保护与新用户导出使用宿主核心，历史读者仅承担明确的兼容读取。桥、设备权限、终端、局域网和插件已经是生产功能，其软件与设备证据分别记录。

### dsh 启动契约（已实现，勿破坏）

- 入口：`exec dsh web --no-open --host 127.0.0.1 --port 3080`（`dsh` 在容器 PATH 的 `/usr/local/bin`）。
- env：`DSH_HOME=/root/.dsh`、`DEEPSEEK_API_KEY`（非空才加入进程环境，不放 argv）、`DSH_PERMISSION_MODE`、`DSH_CONFIRM`（由 `ConfigStore.isConfirmShell()` 决定）、`BROWSER=true`、`cd /root`。
- 写 pid 文件要在 `exec` 之前（`exec` 不换 pid）。
- proot 二进制从 `nativeLibraryDir/libproot.so` 执行（**不能放 filesDir**，Android 10+ W^X）；依赖 `libprootloader.so`/`libtalloc.so` 靠 `PROOT_LOADER`/`LD_LIBRARY_PATH` 引导。

## 编码约定

- 注释与 UI 串用中文；提交信息用中文 + `type:` 前缀说明原因。
- 每个协作者单一职责；纯逻辑抽到 `util/` 并配测试；不改历史 SharedPreferences 键名。
- 匹配现有风格：try/catch 包住有风险操作、优雅降级、失败 toast 给用户。
