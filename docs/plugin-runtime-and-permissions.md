# 内置插件、协议、权限与变更生效

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`，并按当前 build190 / F12 工作树核对注册表、生成器、插件入口与宿主读取链。F11是未上传的本地检查点，不是新增已发布版本；最终身份与实际执行范围见[README](../README.md)和[F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)。本页不新增软件测试或设备验收结论。上游运行时的热加载结论需要绑定随包输入及实际运行证据，不能用本页或旧审计推断Android已经验证。

## 7个可选项、8个签名项与策略源

[builtin-plugins.json](../app/src/main/assets/builtin-plugins.json)是注册事实源，[BuiltinPluginRegistry](../app/src/main/java/com/deepseekharness/app/util/BuiltinPluginRegistry.java)由[生成器](../tools/generate-builtin-plugins.py)派生：`DEFAULT`为7个用户可见项，`SIGNED`含8个APK提供项，`OFFICIAL`为2个官方核心bundle，`INTERNAL`为内部app-integration加这两个官方bundle。目录数量不等于用户功能数量。

| 层 | 包与当前源码版本 | 作用与入口 |
|---|---|---|
| 可见 | `dsh-device-shell-guide` 0.1.19 | 设备通道/桥使用说明，`lib/index.js` |
| 可见 | `dsh-task-notifier` 0.2.0 | `/app/tasks/*`任务状态与通知 |
| 可见 | `dsh-status-overlay` 0.1.4 | `/app/overlay`流式状态 |
| 可见 | `dsh-web-mobile` 3.2.1 | Web移动界面；上游锁和本地差异独立记录 |
| 可见 | `dsh-computer-use-android` 0.1.1 | MCP stdio `dsha-android`，180秒调用预算 |
| 可见 | `dsh-tool-vscreen` 0.1.1 | MCP stdio `dsha-vscreen`，60秒调用预算 |
| 可见兼容包装 | `dsh-auto-review` 0.1.0 | 包装/alias；实际功能来自锁定官方实验Auto包，不能把空包装当完整实现 |
| 内部 | `dsh-app-integration` 1.2.3 | 活动心跳、临时插件摘要、原生返回与浏览器origin恢复 |
| 策略源 | `dsh-destructive-guard` 0.1.0 | 不在8项注册表中；构建时生成`destructive-policy`，不是可随意卸载的第9个普通插件 |

可见插件源码位于[内置插件目录](../app/src/main/assets/builtin-plugins/)，内部集成位于[app-integration](../app/src/main/assets/app-integration/)。用户可选组件可停用；官方核心也可被明确禁用并可能令Web不可用，应由原生页恢复启用。核心/内部组件不因此变成第三方卸载对象。系统字节由当前APK受管证明重建，备份仅保存其启停意图。用户插件源码、实际依赖和包装另行归档。升级不能把用户主动禁用、空禁用marker或安全模式意图解除。

F11不再根据npm scope、`dsh-`前缀、安装树位置或可写`installation.dependencies`判定受管来源。宿主从APK设置proof摘要，guest先核proof原字节，再按每包FILE/mode/LINK完整内容核对。签名注册身份、DSH受管内容、用户/未验证分别显示；位于DSH安装树但未证明的用户包允许开关/导出，原生不自动在该位置更新或删除。普通profile用户插件仍走既有管理链。该来源检查不是对同UID恶意代码的安全认证。

发现阶段先用小manifest和真实patch bundle筛选，单次命令/请求只认证一次proof表，并按canonical目录去重；目录成员、FILE内容与mode、LINK文本及扫描前后身份/竞态仍完整核验。不将跨请求stat、mtime或旧摘要缓存当作内容证明，下次请求和提交仍重新核当前字节。

Node真实Host的bundle列表与完整内容proof使用[受管Plugin Manager补丁](../app/src/main/assets/plugin-manager-policy-patch.json)中的异步文件操作，全局最多2个内容扫描并发，每次文件读取缓冲最多64 KiB，分块间让出事件循环；这不是跳过大包内容核验。只读list不占profile writer锁。Node删除先在writer/HMR锁外做完整预检，进入提交后只针对实际删除目标再次完整内容核验；不能拿列表里的旧分类或其它目标的proof代替提交证据。原生Python删除仍按自己的NOFOLLOW对象计划和前后摘要核验，不将Node实现的异步预算套用给所有脚本。

## 作者面、内部集成与上游能力

| 接缝 | 调用方与内容 | 稳定性/失败判断 |
|---|---|---|
| 插件 `package.json` | 合法npm名称/版本、必需`dsh.bundle.patch`；`main/cordis.entry`若声明则校验存在 | 缺失补丁或已声明入口、声明无效是包失败，普通库不会自动成为插件 |
| profile/bundle | profile `dsh.profile.bundles`和`link:`依赖；bundle补丁组合行 | 由注册器与DSH loader共同消费；profile不在插件清单内，不能混写 |
| Cordis与client | `inject`、`apply`、effect/dispose；`dsh.client.platform: web`与inject | API由锁定上游提供；声明web平台不代表原生WebView桥必存在 |
| MCP stdio | computer-use/vscreen独立server命令与超时 | `failOnStartupError:true`，`reconnect.enabled:false`；启动失败不隐式换通道 |
| 3090桥 | `X-Token`、协议3、当前能力与端点 | 本机集成协议，认证和设备能力继续独立校验，见[桥和文件](bridge-and-files.md) |
| 活动摘要文件 | `/root/.dsha-web-activity.json` | 内部只读状态，不是安装清单或公开控制API |
| 破坏性策略 | schema1及本机consent身份 | 严格校验，失败为`DSHA_DESTRUCTIVE_BLOCKED`；不提供任意代码沙箱 |
| 动态Cordis/HMR/Plugin Manager RPC | 锁定上游内部事件和服务 | 需要匹配运行时输入再验证；不把内部事件全部发布成稳定DSHA协议 |

注册器只负责自己的注册字段和受管链接，不代替loader对所有 `dsh`字段的语义检查。本文不承诺未知字段一定被忽略或拒绝；作者应以锁定上游实际声明和加载错误核对，不凭字段名称推断能力。

## 安装、启停、加载与回切分别看

[插件操作](plugins.md)覆盖GitHub/npm/本地导入和依赖策略。现行正式候选通过格式、路径、摘要与事务检查后自动提交并启用，不增加原生审阅步骤。pnpm依赖解析、生命周期脚本及pnpmfile开放；下载阶段的 `--ignore-scripts`不能作为最终“不执行脚本”的证明。

每次变更至少区分五个状态：源码/依赖已安装、profile已选择、禁用意图、当前Web实际已加载、需要重启。安装成功和卡片显示不证明apply成功。[PluginRepository](../app/src/main/java/com/deepseekharness/app/core/PluginRepository.java)驱动原生列表，启动观察器报告实际插件名和脱敏失败；加载错误不能一律归为网络错误。

原生安装/启停/更新完成后按当前提示**正常重启Web后生效**。保存profile本身不提供当前会话已热替换的保证。关闭/取消后如果执行结果未知，不能自动重放prepare或依赖脚本。

原生`enable-state`/`disable-state`只写启停意图并实际读回；Java严格检查包名、Boolean状态与`intentVerified:true`。先向用户呈现已核验的意图回执，再独立合并调度refresh；完整列表慢或刷新失败不抹去已经确认的开关事实，也不把该事实提升为组件ready、活loader加载/卸载或热更新成功。list/refresh可取消且不取得profile writer锁；并发配置改写或已有writer锁时报错并保留旧列表，不把缓存卡片说成本次新核验。

原生只读查询和短意图命令使用30秒协作总预算，覆盖排队、准备边界和执行；[GuestScriptBudget](../app/src/main/java/com/deepseekharness/app/runtime/GuestScriptBudget.java)在查询提交/队列交接、准备步骤及流读取前后检查剩余时间和取消，[GuestPluginScripts](../app/src/main/java/com/deepseekharness/app/runtime/GuestPluginScripts.java)只把剩余预算交给runner。它不是硬实时抢占：单次系统I/O、等待进入monitor或Binder调用本身仍须返回后才能继续检查，因此不能承诺点击后严格30秒内结束所有平台调用。未知进程或管道退出继续保留原停止/维护屏障，不为超时清日志、释放所有权或自动重试写操作。

[plugin-transactions.py](../app/src/main/assets/plugin-transactions.py)按固定目标和前后摘要保存候选、原件与日志；宿主pending门在退出证明不足时保留现场。回切必须经对应事务/恢复入口核对，不用手动改名、删除日志或重解析历史依赖冒充原字节恢复。已提交旧日志不能回滚后来用户数据。坏插件删除使用专用NOFOLLOW对象证明并移入保留区，不跟随链接删共享store或外部源。

普通临时准备目录在成功或明确失败后可清理；只有包管理退出/管道未知、事务pending或明确保留原件等对应现场继续保留，不能泛称所有失败候选永远不删除。[NativePluginGraph](../app/src/main/java/com/deepseekharness/app/backup/NativePluginGraph.java)从APK读受管proof并重新核对实际包，发现没有alias但真实存在的运行时bundle。目录扫描后及最终备份前再次核proof，避免可变manifest或同scope包导致漏备；历史Python备份也不按scope/前缀排除用户原件。相关检查只说明本次实际内容，不提升为任意插件已加载或手机恢复通过。

## 活动文件和临时插件

[内部集成](../app/src/main/assets/app-integration/index.js)在有 `DSHA_WEB_GENERATION`时每2秒原子写 `generation/at/idle/plugins`；采样失败时不伪造idle，原生把过期状态视为未知并保活。PluginRepository只读当前代次、10秒内、最大256 KiB的摘要，最多取256项。

[runtime-plugins.js](../app/src/main/assets/app-integration/runtime-plugins.js)从动态runner公开列表提取名称、会话、active、state和package ID等截断摘要，不提取源码。列表中的“临时插件·已运行/未运行”与持久npm安装不同；该摘要不成为导出包、依赖锁或恢复来源。进程/会话内的定义不应描述为重启后自动重建的安装包；是否重新定义按上游实际运行链处理。

## HMR及 `patchReload` 的证据边界

[register-builtin-plugins.py](../app/src/main/assets/register-builtin-plugins.py)写 `patchReload: startup`，注释表达避免live/HMR依赖的意图；[NativeSafeProfileTest](../app/src/test/java/com/deepseekharness/app/backup/NativeSafeProfileTest.java)等可核对写入。当前主Web启动命令与应急启动的Node参数不同，不能把某一入口的标志迁用到另一个入口。

字段被写入不等于其消费和控制效果已经验证；旧报告开发容器的 `--dump-config`或未与随包锁绑定的全局Node树也不能证明Android上的HMR成功或失败。本页保留待确认项：匹配输入中的字段消费者、HMR服务实际状态、profile重组是否触发link包替换、替换失败后的真实状态与持久数据影响。缺少专用reload入口不自动等于产品有意排除或实现缺陷。本轮不为文档补齐改变该字段或新增热加载权限。

## 谁批准什么

DSH会话/Creator/Auto审阅的工具批准、上游动态runner的页面批准、宿主本机“全部允许”和Android系统能力是不同域。Creator或 `danger-full-access`的上游机制不能直接视为所有DSHA入口的运行结果；每个入口仍需匹配版本和调用证据。

[destructive-policy](../app/src/main/assets/destructive-policy/index.js)校验schema1、`globalAllow/revision/installationId/bridgeRunId/webGeneration/runtimeInstanceId`，本机不可用或身份无效时拒绝。全局策略改变使排队请求失效，许可不从备份导入。屏幕帧/节点/目标身份和系统能力仍检查。“全部允许”不是取消同UID风险，也不是把任意插件代码变成受监管的沙箱程序。

测试入口：[test-plugin-discovery.py](../tools/test-plugin-discovery.py)、[test-signed-builtin-web-intent.mjs](../tools/test-signed-builtin-web-intent.mjs)、[test-auto-review-alias.mjs](../tools/test-auto-review-alias.mjs)、[test-native-consent-transport.mjs](../tools/test-native-consent-transport.mjs)、[test-plugin-transactions.py](../tools/test-plugin-transactions.py)。这些入口分别验证注册、意图、传输或事务，不能互相替代HMR端到端、持久会话逐项比对或设备攻击验证。

已有桌面[真实Host并发夹具](../tools/test-plugin-provenance-concurrency.mjs)中，两次list约0.65秒；独立Worker发起的HTTP ping约14毫秒，且响应时内容扫描仍pending。该观察只覆盖对应Node/桌面夹具和输入，不是手机实测、APK性能承诺、全部插件规模上界或最终端到端延迟保证；本页未重新运行该夹具，也不据此新增F12正式测试总数。
