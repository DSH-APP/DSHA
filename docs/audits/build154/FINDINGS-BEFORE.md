# Build153 全量复核：修复前问题清单

来源资料针对150。本次逐条核对当前153，236条全部有处置（含4重复）。证据仅限各条注明的静态/宿主夹具；未操作手机。候选风险和无法执行的设备场景不写成已复现缺陷。

## 复核统计

- open: 215
- requires_device_evidence: 6
- accepted_policy: 6
- duplicate: 4
- already_fixed: 2
- not_applicable: 3

现行授权及政策差异见[POLICY.md](POLICY.md)，逐项源码摘要和夹具回执见五份review-G*.json及g*-repro-results.json。

## 核心判断

架构属于原生宿主编排＋受管Ubuntu/Node运行时＋同源网页桥的混合应用。主要债务集中在大类同时持有多个生命周期、跨层状态耦合、双重/旧链路部署、版本和名单多源，以及测试与实际路径脱节。事务、独立救援、原件保护、进程出生身份和真实运行时门禁值得保留。proot不是插件或agent与宿主之间的独立权限隔离。技术债不以猜测分数替代逐项证据。

## 逐项清单

### D-SEC-01 · P2 · open · TarGzipExtractor 链接越界校验为纯文本归一，不解析已解出的链接链

词法链接目标检查与 FileOutputStream 跟随链接仍在；输入目前只见签名随包资产。报告 a→.. 例子会被拒绝，真正需回归的是 a→.、b→a/.. 的链式解析及已有目标链接。

处置：与 G2 D-IO-02 合并实施，拒绝经过链接的文件写路径；兼容 Ubuntu bin→usr/bin 等合法 guest 绝对链接，实际链接/权限失败带路径和类型抛出，等价链接可核验幂等；不要复用分叉 TarRepro 当证明。

### D-SEC-02 · P2 · open · 宿主按 rootfs 相对路径读写容器文件时跟随 guest 可控符号链接

containerFile 仍直接 new File(rootfsDir, rel)，push/pull 用普通文件流；既有 guest 目标链接可被宿主写入跟随，属于防误覆盖缺口而非同 UID 隔离漏洞。

处置：引入保持 guest 链接语义的宿主安全文件边界，受管写目标逐级拒绝链接并绑定打开描述符；临时原子发布，读取核对目标身份，保留坏原件。

### D-SEC-00 · P1 · open · 安全模型文档与实现不符且缺正式威胁模型/信任边界

中英文安全文档仍写依赖安装禁用生命周期脚本，与当前明确开放钩子的实现和 AGENTS 覆盖条款冲突；已有同 UID 边界文档，报告称完全缺失不准确。

处置：修正文档并增补资产、主体、同 UID/loopback/LAN 信任边界及控制分类；明确自动插件安装/执行钩子是当前授权策略，不采纳附件另一会话的审阅确认要求。

### D-ADB-01 · P3 · open · AdbBridge 写入的 allow-root-shell/confirm-shell-* 标记文件无读取方（死代码）

AdbBridge 仍写三个旧标记，adb-shell 无读取方；测试仍模拟这些标记。真实授权在原生偏好及设备命令策略。

处置：删除无读方写入及脱节测试模拟；残留只作为已知受管标记处理，不自动删未知用户文件。

### D-SEC-03 · P0 · open · 桥 token 可经查询串传递，官方引导与多个内置插件把 token 放进 URL/命令行

桥仍接受 query token；四个内置客户端/指南仍在 URL 中传递 token。隐私风险确实存在，但任何新 header 示例也不能把秘密直接展开进 curl argv。

处置：内置 Node fetch 改 X-Token header；shell 示例用受保护 header/config 文件（如 -H @file）避免 token 进入 URL 与 argv；服务端兼容策略须明确且不得破坏 Web/LAN 首帧鉴权；同 G2 STABLE token 根修复协调。

### D-SEC-04 · P1 · open · 屏幕操作敏感应用判定靠包名子串黑名单且判定与执行间存在 TOCTOU，/app/launch 无授权闸

敏感包名子串判定、授权时/执行时重新取窗口的间隙与无焦点输入回退仍存在；appLaunch 不经屏幕授权。真实错点/输入效果需设备证据。

处置：授权绑定目标显示与窗口包名，操作时在同一节点树再次核对；缺焦点不猜输入目标。敏感启动与虚拟屏策略合并处理，不能将新 nonce 当作同 UID 沙箱。

### D-PROV-01 · P2 · open · ShellService.java 文件头标注 CFR 反编译，代码来源不明

CFR 标注仍在，但标注本身不能证明侵权、外部反编译来源或当前实现许可不兼容。实现已大量使用仓库自己的执行器。

处置：先核对 Git 来源/授权说明；必要时基于公开 API 自写等价服务并记录来源，保留当前设备策略、30 秒期限与仅特权进程 destroy 行为；不作未经证据的法律定性。

### D-SEC-05 · P1 · open · 局域网模式为明文 HTTP，首访 token 在 URL 中并被显示/复制到剪贴板

LAN 明文 HTTP 是既有功能并已部分说明；完整 token 仍在地址文本、剪贴板及 Toast，cookie 有较长有效期。不得把“HTTP 存在”自动当成授权变更。

处置：保留 LAN 功能，先修剪贴板敏感标记和不回显 token 的 Toast/日志，补实际明文风险说明及会话失效测试；TLS/配对机制属后续明确设计，不强行改变当前入口。

### D-ROOT-01 · P2 · requires_device_evidence · su 可执行文件只查 4 个硬编码路径

固定四个 su 路径仍在；未提供 KernelSU/APatch 真机或实际安装路径证据，不能确认所列新增路径存在或该设备受影响。

处置：可抽取可测试的查找顺序并核对官方/实际路径；不得盲目从不可信 PATH 运行 su；设备矩阵验证留待授权。

### D-SEC-06 · P2 · requires_device_evidence · 特权进程先校验路径再执行 cp/mv/rm，共享存储存在跨 App TOCTOU

validateFiles 与外部 cp/mv/rm 存在静态检查/执行间隙，但普通 Android 共享存储是否允许攻击者创建相关 symlink/mount 及真实竞争利用尚未核验，不能直接报告已被绕过。

处置：宿主临时目录可做路径替换夹具；优先以固定父描述符执行危险写操作，保留保护范围；实际 FUSE/SELinux 可利用性需后续设备证据。

### D-LIC-01 · P2 · open · THIRD_PARTY_NOTICES 缺 Shizuku、Termux AAR、core-js、sharp/libvips 等条目

NOTICES 漏 Shizuku 与终端 AAR 等精确组件说明，运行时许可集合未对应实际打包内容。不能只按 npm 锁中的所有平台包推断实际分发。

处置：主控按实际 Gradle/APK/runtime 内容生成版本和许可清单，补随附文本及来源；GPL/LGPL/MPL 义务作为需专门确认的分发事项，不作本次法律结论。

### D-SEC-07 · P0 · open · SAF DocumentsProvider 可浏览/读取 rootfs 内桥 token、.credentials.yaml、.env

SAF 授权与文档路径边界确实存在，不是任意外部 App 无授权读取；但用户授予目录树后 privateDocument 不隐藏桥 token/credentials 等文件，与桥拒绝策略不一致。

处置：共用精确凭据/机器状态策略，列目录隐藏与 open/create/rename 等入口都复核逻辑和解析路径，保留既有 opaque document IDs 及合法用户文件访问。

### D-SEC-08 · P2 · open · 旧版恢复从 .dsha-apikey 回填 Keystore 后不删除明文文件

旧恢复 syncApiKeyFromRootfs 成功后仍留下明文 .dsha-apikey；新 v5 已不把它作为普通设置导出。

处置：仅对确定为已生成活跃投影的普通文件，在 Keystore 保存+读回一致且 inode/路径核验后撤除；失败/未知原件必须保留或隔离，不能采纳附件“回填失败也直接删除”的建议。

### D-NET-01 · P2 · open · 全应用 usesCleartextTraffic=true，未用 network_security_config 限定 localhost

全局 cleartext 标志仍为 true，无 NSC 文件；外部 http 不受该平台配置约束的可能性尚在，不能假定限制宿主就限制 guest Node。

处置：宿主按实际 localhost/页面导航需要限制 cleartext，并验证 Standard/Low 兼容；Gecko 与 guest 需要各自的边界，不声称 NSC 能隔离原生容器网络。

### D-PRIV-01 · P2 · requires_device_evidence · 错误日志导出声称未读对话文件，但附带 dsh-web.log 尾部 256KiB 且仅正则脱敏

默认诊断导出确实附 dsh-web.log 尾部且只做尽力正则脱敏；“日志实际包含哪些对话/工具片段”未取得真实样本，不据源码推断已泄露用户对话。

处置：立即把说明改为真实附带范围与尽力脱敏，使用合成含 token/密钥日志夹具；可设计选择/预览运行日志，真实样本审阅需后续授权，不能操作手机取样。

### D-UPD-01 · P2 · open · 更新清单无签名，信任仅靠 HTTPS+清单内 sha256，pageUrl/notes 可被清单任意指定

feed 无签名，pageUrl 只要求 HTTPS；APK 安装仍核对包名、版本、flavor、minSDK 和历史签名证书集合，不能声称伪造清单可直接安装异签名包。附件“用户接受不签名”不是本轮授权。

处置：先限制发布页到实际官方域/仓库并限长纯文本说明，保持证书验证；记录元数据信任边界，独立清单签名或密钥轮换另需设计，不从附件决策推断已接受。

### D-UPD-02 · P3 · open · 更新下载重定向只校验 https，不限定主机

最多六跳 HTTPS 重定向仍不限定主机；这是连接/可用性/隐私边界，不是绕过最终哈希与 APK 签名。

处置：按实际分发及 CDN 配置限制跳转域，保持每跳 TLS/超时和断点整体核验；用注入 transport 测试恶意跳转，不进行真实不可信下载。

### D-SEC-09 · P2 · open · API key 以 export 拼进 bash -c 命令串，出现在 /proc/<pid>/cmdline

runCoreCommand 仍将实际 API Key 拼到 export/bash -c 命令串；ShellQuote 防注入不等于防 argv 泄露，同 UID 环境变量本来也可读。

处置：通过 ProcessBuilder 环境传递凭据，校验各 runtime 透传并避免将 env/debug 记录输出；不宣称环境变量在同 UID 内保密。

### D-CFG-01 · P2 · open · 沙箱档位无界面入口，两条导入路径缺省值不一致（'default' 疑似非法）

danger-full-access 是当前明确默认策略，不能作为漏洞修改；但 prepareHostSettings 的 permissionMode 缺省仍为 default，读取/投影也无三档归一，非法输入一致性问题尚在。

处置：统一合法枚举与当前明确的默认行为，拒绝/归一非法值，保留已有三档；是否增加 UI 入口属于功能设计，不借此收紧已授权终端。

### D-SEC-10 · P0 · accepted_policy · 外部链接一键安装并启用插件且执行生命周期脚本，无原生确认

自动解析、安装和启用、开放依赖钩子均由当前用户/AGENTS 明确要求；外部调用链与风险存在，但不能采纳附件另一会话要求重新审阅/确认的决策。

处置：保留免审阅安装。只修格式、路径、摘要、事务一致性及来源透明说明；文档如实说明第三方代码可用同 UID 权限执行，不能宣传为沙箱安全认证。

### D-BRAND-01 · P3 · open · 对外仓库地址/域名/群号分散硬编码，且两处指向迁移前旧仓库

AboutDialog、ConfigFragment 与官网投稿链接仍指迁移前仓库，其他入口用当前仓库，集中配置未完成。报告的两个 website 根脚本在当前树不在所列位置。

处置：主控集中当前对外入口；保留 README 受用户要求保护的历史介绍、作者链接与历史报告，不采用全仓删旧作者名的粗糙验收。

### D-SEC-11 · P2 · open · 配置页把解密后的 API key 明文填入 EditText 且未关闭实例状态保存

配置页仍把解密密钥回填 EditText，布局未显式 saveEnabled=false；是否系统实际持久化 password 文本需设备证据，不能当作已泄露到磁盘。

处置：最小关闭视图状态保存，改为已保存状态+空白替换输入，保存其他设置不清空暂不可读凭据；保持 CredentialRead 四态语义。

### D-SEC-13 · P3 · open · 页面任意脚本可伪造启动致命事件，触发跳转启动恢复页

同源诊断事件在启动未 ready 时可由页面脚本伪造；pageEvent 已有 browserReady 与 generation 守卫，报告“运行中可随时反复跳恢复”的泛化不成立。

处置：保持明确真实启动故障自动恢复契约。启动观测来源/一次性状态绑定作为可靠性加固；不能把同源 nonce 当成隔离恶意插件的安全边界。

### D-SEC-14 · P1 · open · 虚拟屏动作按主屏前台包判定敏感应用，敏感应用闸被旁路

appVscreen 仍调用只看主屏 currentPackage 的 uiAuthorized；目标显示/launch 包没有绑定，因此代码上的策略错位尚在。是否可运行具体敏感 App、读取其内容仍需设备证据。

处置：同 D-SEC-04 实施显示/包身份绑定，launch 用目标包，后续动作复核虚拟屏顶层包；see/tree 在敏感/未知目标按真实授权处理，不拿主屏 DSHA 代替。

### D-PRIV-02 · P2 · open · SensitiveData.redact 依赖字段名/固定前缀，无前缀密钥漏脱敏且每次现编译正则

正则每次编译且不能覆盖无字段名 AIza/JWT 等；当前实际源码夹具证明合成值不变。任何正则都不能保证任意秘密全量脱敏。

处置：预编译已知模式；对注册的已知秘密做明确替换并限制生命周期，诊断采集尽量减少内容；改绝对保证文案为尽力脱敏，未知令牌不能当已安全。

### D-NET-02 · P3 · open · resolv.conf 缺 nameserver 时硬编码追加 8.8.8.8 与 223.5.5.5，两处重复且无配置

无有效 nameserver 时仍在 ResolverConfig/InstallProbe 两份逻辑写固定公共回落 DNS；它是兼容策略并非已证明用户 DNS 被强制覆盖。

处置：保留显式已有 nameserver/search/options 与用户 DNS 模式，集中回落定义/如实说明；若采用系统 LinkProperties 地址需可验证且有有限回退，不扩展成所有流量隧道。

### D-SEC-15 · P0 · open · 桥路径拒绝表 /root/.dsha- 前缀条目失效，且工作区 .env 可经 /app/export 落公共目录

路径段匹配不能实现 /root/.dsha- 文件名前缀，工作区 .env 也不在拒绝表；实际当前 Java 夹具两条都允许。

处置：精确区分路径前缀与文件名模式，共用凭据/机器状态规则，同时核对原始逻辑与解析后路径；保留用户合法工作文件导出，不粗封整个 rootfs 或改文档 ID。

### D-SEC-16 · P0 · open · full 备份无条件把工作区 .env 与未脱敏 dsh-web.log 打进导出到 Download 的归档

旧 BackupManager→backup-engine 的 full 仍原样带工作区 .env 和运行日志；默认 UI 已迁宿主 v5，不能把旧链描述为当前所有备份入口，更不能把用户授权的无密码导出本身判为 P0。

处置：G3 owner 处理遗留写链：默认旧导出不带运行日志/未选择的原生凭据，旧格式只保留经验证读取；当前 v5 不丢用户明确选择的项目原件，排除原生 Key 与包含其他项目秘密的界限如实说明。

### D-SEC-17 · P2 · open · 阅读位置记录把含查询串的 URL 写入 localStorage，可能持久化 ?token=

阅读位置仍直接保存 pathname+search；正常首帧 DSH 是否立即剥 token 未核实，不能宣称已有真实 Web token 泄露。保存逻辑本身无敏感参数保证。

处置：仅保存导航身份所需 pathname/明确白名单参数，并对旧读入值也剥敏感查询；用浏览器合成 URL 夹具验证，不靠猜测首帧时序。

### D-SEC-18 · P2 · open · 插件导出 dereference 跟随链接，非内置插件可把包外文件打进导出包

所有第三方导出的 add_tree 会 realpath+deref，包内约束仅 runtime_source 分支；源码上可把工作区包外链接带入导出，真实机上未演示。

处置：统一包根边界/链接图规则与排除报告；依赖组不能简单一刀切删除，先辨明受支持包装/依赖导出结构，异常停导出并保留原插件。

### D-SEC-19 · P1 · open · root 读取短信库拦截不含 /data_mirror 绑定挂载路径

Java 与 Python 短信 provider 路径规则都未覆盖 /data_mirror；实际 Java plan 接受镜像路径 cat/find。实际 Root/SELinux 能否读取仍未操作手机核实。

处置：补已知 SMS 镜像路径及祖先规则并做双方一致性夹具；仅原生授权的严格当前用户 content query 保持可用；不要因此开放任意 root 内容提供者或系统命令。

### D-SEC-20 · P2 · open · selftest.py check_backup 把共享存储文件名拼进 bash -lc，存在命令注入

selftest.py 的文件名 shell 拼接确实不安全；当前原生自检未找到部署/调用该 Python 脚本的活跃链，风险属旧/手动复活脚本，不是当前入口已被利用。

处置：与 G2 D-DEAD-03 协调移除无部署资产或改为 argv/tarfile 读取；不删除用户环境里的未知历史文件。应保留原生自检并避免新审计 APK。

### D-SEC-21 · P0 · open · v5 备份机器状态名单写错桥 token 文件名，.bridge_token 会作为普通设置进入备份

MACHINE 仍列旧错误名称，真实 .bridge_token 和匿名 ID 不在集合；v5 NativeDataLocations 对未知子项按 settings 归档，因此两种布局应统一排除。

处置：加入真实名称并保持旧别名兼容，共用机器状态/凭据常量和排除清单；同时修 G2 STABLE token 根绑定，测试本机授权状态不可从归档恢复。

### D-PRIV-03 · P3 · open · 公开文档与测试脚本硬编码开发者 ADB 序列号、私网地址与本机路径

已确认公开历史文档与设备脚本含固定序列号、私网地址及具体开发路径；报告未证明这些值导致远程控制或密钥材料泄漏，属于隐私/可迁移性卫生。

处置：主控对新增交付证据做脱敏，设备脚本通过参数/环境明确选择并无设备时失败；保留历史事实但去可识别值，不运行设备脚本。

### D-SEC-22 · P0 · open · 系统 Auto Backup/设备迁移整目录纳入 user-data-v5/，STABLE 布局下凭据随之离机

STABLE 整目录 include 确实覆盖凭据/本机状态；cloud 的传输加密要求不能替代文件排除，device-transfer 与 v28 规则也存在不对称。尚未声称已检查实际系统迁移包。

处置：系统规则对 LEGACY/STABLE 使用对称数据白名单或共享明确排除，尤其真实 .bridge_token/匿名ID/明文凭据及本机授权记录；自动本机副本的宿主内部加密与用户无密码 tar.gz 策略保持不变。

### D-PRIV-04 · P2 · open · 无障碍说明承诺“不落盘”，但截屏 PNG 长期写入外部私有目录无清理

屏幕说明仍承诺不落盘，但 saveShot 对普通/MCP 都先写外部私有 PNG，未找到轮换/删除；路径私有不等于不存在或不长期保存。

处置：同步中英文说明为实际按请求采集/保存；MCP 可内存编码且保留普通文件截图用途，文件需明确保留/可控清理，不能为修文案破坏截图工具。

### D-SEC-23 · P1 · open · 发布证书为 Android debug keystore，构建缺省口令回落为公开值 android

历史 E7E3 发布证书必须保持以覆盖安装，这是明确要求；构建仍默认公开口令/alias 并打印绝对路径。CFR/debug 文件名不证明证书或私钥已泄露，不能擅自换签名。

处置：主控去缺省口令回落与路径日志，环境缺配置时正式构建明确失败；私钥管理可加强，轮换须单独设计签名谱系与兼容验收，当前仍用 E7E3。

### D-LIC-02 · P2 · open · GPL-2.0 proot 随所有 flavor 分发，但许可全文只在 low flavor

标准源集含 proot 二进制，main/licenses 未有 proot 全文，low 有；NOTICES 标准段无精确对应源码版本，talloc 仅合并列名。不是已作出的法律违规结论。

处置：主控按实际分发组件补 main 许可、固定源码/构建来源与对应说明，两个 flavor/APK 都核验；许可义务保留专门审查，不改变历史签名/运行二进制来掩盖问题。

### D-WEB-01 · P3 · open · 官网 Nginx 下载 location 内 add_header 导致 HSTS/CSP 等 server 级安全头不继承

下载 location 自己声明 add_header 而未复制 server 级全部安全头；静态配置不符合统一安全头意图。未读取服务器实际配置/响应，不能称部署已缺头。

处置：共用安全头片段或显式重复，nginx -t 与受控配置测试后再由主控按授权部署验证；不直接改服务器。

### D-ARCH-01 · P2 · open · HarnessController 集中了 Web 生命周期、迁移、插件注册、LAN 鉴权、日志与配置重置

HarnessController仍995行，启动、迁移、插件、cookie/LAN、日志和resetConfig同类；已有RuntimeHostPorts/RuntimeWorkPort但启动步骤仍内联。

处置：先抽有序StartupPipeline与LanAuthBridge，保留唯一生产controller门面和代次/停止屏障，不以300行硬指标制造多实例。

### D-RT-01 · P2 · open · Web 健康检查只做 TCP connect，不区分存活、鉴权就绪与服务就绪

HarnessService.isWebUp仍只Socket.connect，半断网页流或只监听不服务仍被判healthy；151客户端恢复不等于服务业务健康。

处置：增加有界且绑定本轮端口/鉴权的分层健康证据；LISTENING不可当READY，也不能因客户端丢流重启Node。

### D-RT-03 · P2 · open · UserDataBindings 用两级 getParentFile 隐式推导 filesDir

UserDataBindings.append仍从rootfs两级父推files并mkdir迁移记录；不是显式宿主布局依赖。

处置：以显式已授权filesDir/UserDataLayout输入替换推导，普通正式根严格断言；独立应急/试运行保持各自数据域。

### D-RT-04 · P2 · open · 脚本以 base64 内嵌进单个 bash -c 参数注入，受 128KB 单参数上限约束

AdbBridge.injectOwned仍把4脚本base64塞单个命令；已有秘密stdin入口没有用于此批注入；当前尚未E2BIG。

处置：改为有界stdin或受管原子文件安装，先核对实际guest数据布局；加argv预算而非等待超限。

### D-I18N-01 · P2 · open · 大量文案以 UiText.text 片段加数字拼接，无法整句翻译与处理复数

UiText.text仍整串查表，BasicToolsInstaller等保留片段加数字；模板类UiStateText不是所有界面的统一格式入口。

处置：新增整句占位符模板并逐模块迁移，保留参数原文；先建立可降低的基线而非整库一次替换。

### D-ARCH-02 · P2 · open · 全局可变静态单例充当服务定位器（RuntimeHostPorts、MainActivity.current、vscreen 等）

RuntimeHostPorts共享实例、MainActivity.current强静态引用、PluginFragment修订表、虚拟屏全局可变状态仍在。

处置：组合根集中生命周期和端口；保持唯一controller/runtime owner，按模块改实例；移除生产未读的Activity强静态入口，历史审计源码不运行。

### D-RT-05 · P3 · open · RuntimeHostPorts.emit 吞掉 Provider 异常，只保留最后一次的类名

RuntimeHostPorts.emit仍只保留最后异常类名；153成功runtime选择回调单独传播错误，没有把它吞成诊断。

处置：保留诊断失败不阻断安装的策略，追加有界首失败及阶段/次数/脱敏原因记录；不要捕获真实提交失败。

### D-PLAT-01 · P2 · requires_device_evidence · PrivilegedPackageContext 反射调用隐藏 API ActivityThread.systemMain/getSystemContext

app_process privileged Context仍反射systemMain/getSystemContext且失败闭合；没有证据表明当前支持设备上失效。

处置：先补SDK/异常链脱敏诊断和明确通道降级提示；API/ROM能力需以后真实设备证据，不伪称矩阵通过。

### D-RT-06 · P1 · duplicate · BoundedGuestSessions 遇到单个畸形或无法证明已空的记录就整体抛错

畸形/未知guest记录确实fail-closed；与D-RT-18同一门禁链，不能把损坏日志当进程已空。

处置：合并D-RT-18；可逐项改善诊断，未知会话仍保留屏障及原件。

### D-RT-07 · P2 · open · 共享存储 bind 只在路径存在时添加，缺权限或不可见时静默缺失

ContainerRuntime仍exists检查跳过bind，无BIND_SKIPPED记录；存储授权页单独提示，启动侧原因链不完整。

处置：记录可选bind缺失/权限未知并给本机授权入口；不因共享存储缺失阻断不需要它的本机聊天。

### D-RT-08 · P2 · open · ContainerRuntime 接口的 Proot 实现 applyEnv/prepare 为空，真实逻辑散在 ProotBootstrap；BINDS 是接口常量

Proot环境仍由Bootstrap处理，Proot.applyEnv/prepare空；rt.applyEnv被catch Throwable忽略；BINDS为可改数组。

处置：将具体runtime环境/准备归各实现，启动错误必须传播；bind改不可变数据；保留cold显式兼容模式及L2S路径。

### D-RT-09 · P3 · open · 安装步骤号 1-6 以魔数散布，失败原因用 String.valueOf(error) 直接展示

InstallPipeline/InstallProbe/BasicToolsInstaller仍以1-6数字协作，修复异常String.valueOf显示；InstallTask已有脱敏。

处置：引入稳定步骤枚举与结构化安装错误，展示整句本地化；不要改变已记录的历史步骤号。

### D-IO-01 · P2 · open · 原子写有多套实现，BackupFileSystem.atomic 之外约 10 个文件自写 tmp+rename 且不 fsync 父目录

BackupFileSystem.atomic有恢复边界与目录sync；ManagedRuntimeAssets.writeMarker仍文件sync+renameTo、无目录sync；多套原子写不能等同耐久。

处置：统一宿主记录/受管marker耐久发布原语；保留既有previous恢复语义；缓存可轻量化，数据事务不能降级。

### D-RT-10 · P2 · open · IsolatedInstallProcess.exitValue 有副作用（置 closed、删 status 文件），waitFor 10ms 忙轮询

IsolatedInstallProcess.exitValue会置closed及删status，waitFor和close轮询；身份与整组退出核验正确，未见忙等导致错误的实测。

处置：抽受同步保护的状态推进，做有界退避等待；保留guest/supervisor/会话均退出才关闭的完整证明。

### D-PLAT-02 · P2 · requires_device_evidence · ProcessIdentity.androidPid 从 Process 类名和 toString() 解析 pid，依赖 ART 内部实现

androidPid仍解析限定Process类名/toString，不匹配返回-1拒绝信号；兼容风险未在新ROM证明。

处置：明确诊断无法登记身份；长期用受控原生fork/exec登记，不接受任意shell首行裸PID替代出生证明。

### D-RT-11 · P2 · accepted_policy · RuntimeTrial 与 ProfileSettingsTrial 的等待循环没有总超时，只靠用户取消

RuntimeTrial和ProfileSettingsTrial保持可取消的慢启动等待，60秒仅提示；不是自动重试循环，当前约束禁止按启动超时强停。

处置：保留等待策略，增加等待时长及应用内取消可见性；取消后仍须确认退出，不能照附件设置硬终止并释放未知guest。

### D-STYLE-01 · P2 · open · 大量源码多语句压缩在一行，未接入 formatter，难以审查

RuntimeTrial等大量单行复合语句仍存在，构建未接formatter；影响可审阅性。

处置：固定formatter并分包格式化，逻辑与格式变更分别审阅；不引入新依赖版本浮动或用格式重写掩盖功能差异。

### D-ARCH-03 · P2 · open · 分层反转：runtime/backup 层直接依赖 ui 与 core，缺少架构边界测试

RuntimeTrial仍直接调用ui.RuntimeBrowserProbe，backup仍依赖core/UI；附件称无架构门禁已过时，tools/test-architecture-boundaries.py存在但仅锁部分边界。

处置：注入BrowserProbe及维护端口，先冻结现有依赖图并禁止新增反向依赖；增强现有门禁，不必先加ArchUnit。

### D-RT-12 · P3 · open · ProfileSettingsTrial 输出泵每 150ms 最多读 8KB，guest 输出过快时会被管道阻塞

ProfileSettingsTrial仍150ms只读最多8KiB且丢弃输出；RuntimeTrial已每轮256KiB排空并保留尾部。

处置：复用有界输出收集与脱敏尾日志，避免限流让guest输出阻塞；不对写入未知结果切通道重放。

### D-IO-02 · P1 · open · TarGzipExtractor 的 symlink/硬链接/chmod 失败被 catch Throwable 吞掉，解压不完整仍报成功；lastSkipped/lastSkipNote 是死字段

TarGzipExtractor symlink/link/chmod异常仍吞掉，未知type跳过；lastSkipped/lastSkipNote仅声明，缺完整提取回执。

处置：对同目标已有等价链接可幂等成功，实际链接/模式失败必须带路径失败闭合； signed overlay不能无条件忽略EEXIST或跳过错误。

### D-API-01 · P2 · open · WebProcessManager.stop/stopOne 以 String 返回错误，混用错误码与本地化文案

WebProcessManager返回空串/错误码/本地化句子混合结果，只保留首错误；调用者还依赖.isEmpty判断成功。

处置：引入StopResult与枚举状态，完整保留多目标结果；显示边界渲染，不破坏停止/维护成功判断。

### D-RT-13 · P1 · open · 停止 Web 只发 SIGTERM，卡死的 node 无法停止且没有强制路径

已核验Web仅SIGTERM，3秒未退即保留环境；没有身份安全的可选force入口。不能宣称当前误杀，阻塞风险是候选。

处置：可增加仅当前已核验出生身份的手动force途径及独立确认，未知/变代/复用PID绝不强杀；附件另一会话决策不是当前执行许可。

### D-API-02 · P2 · open · 以 exception.getMessage() 字符串比较做控制流

ENV_NOT_READY等消息字符串仍参与控制流，Backup/Retained也依赖getMessage字面量；涉及机器码与展示混用。

处置：分域逐步引入带code的异常/结果，保留历史协议码，先迁移启动及停止高风险分支。

### D-RT-14 · P3 · open · ensureAndroidGroups 硬编码特定设备的 u0_a428/all_a428 组

ensureAndroidGroups仍含all_a428/50428和u0_a428/20428，随后虽合并真实Groups但遗留错误命名。

处置：按实际UID/userId/appId及真实补充组生成，删除设备专用值；宿主文件追加需安全原子发布。

### D-API-03 · P3 · open · ProotBootstrap.startRootfs 有 5 层布尔参数重载，应改为 LaunchSpec 值对象

startRootfs布尔重载链仍在，153又增加coldMode参数；参数组合含多处false/null。

处置：引入LaunchSpec并单一构建/执行入口，模式、隔离、秘密stdin、cold记录具名，保留所有旧命令契约。

### D-RT-15 · P2 · open · findBundleEntry 用 4 个候选名加全 APK 子串匹配取最大条目的启发式定位离线包

findBundleEntry仍候选名及全APK子串最大条目回退；当前构建固定生成offline-rootfs.bin。

处置：当前签名APK按描述符固定名/摘要读取；历史格式仅在明确兼容reader处理，不在当前包内猜最大blob。

### D-RT-16 · P2 · open · 首次解压中途失败会留下非空 rootfs，之后不再允许首次解压，只能走维护重建

初解压失败留下非空rootfs，正常首次提取拒绝覆盖；153修了重建失败回切，但尚未引入隔离首次stage发布。

处置：可引入有身份/日志的首次安装stage，闭合后发布；失败原件不能按文件名自动删，重建恢复入口需保留。

### D-ARCH-04 · P2 · open · ProotBootstrap 是 1579 行的 God class

Bootstrap当前1609行，launcher、脚本、解压、离线工具、Python/pnpm、组和救援职责仍聚集。

处置：优先抽RuntimeLauncher/LaunchSpec、RootfsInstaller、GuestScripts；门面保留接口，host数据事务继续独立协调。

### D-API-04 · P2 · open · 文件类型用字符串字面量（FILE/DIRECTORY/LINK/MISSING/UNREADABLE/EXCLUDED）在全包散布

Node.type/Item.kind仍字符串，按FILE/DIRECTORY等判断广泛存在；解析unknown继续明确报错。

处置：渐进类型化内部模型，序列化格式名称不变，未知类型仍拒绝；不要整库机械替换使协议变更。

### D-CONC-01 · P3 · open · AutomaticBackupService 的 active/owned 在 worker 线程与主线程间共享，未加 volatile 或同步

owned仍worker写、main读取；active大部分main confinement；Handler发布后可见，但onStopJob在发布前与worker交错需更清楚的单owner策略，缺复现。

处置：把owned赋值/取消核验/poll统一post到main；不只是盲加volatile，确保取消不能漏掉已启动自动作业。

### D-ARCH-05 · P3 · open · main 源集的 StorageMaintenance.CACHE 硬编码 debug/deviceAudit 审计目录名

StorageMaintenance.CACHE仍含7个仅历史审计使用的files目录名；正式缓存和审计空间归属混杂。

处置：正式列表仅可证明再生缓存；历史夹具目录在保留区显示，不因本轮运行审计脚本创建/删除用户目录；新夹具留host。

### D-DEP-01 · P2 · open · BackupJson（Gson 严格流式）与 org.json 两套 JSON 解析并存，严格性不同

BackupJson严格有界并拒重复；RuntimeTools仍org.json读受管/宿主文件；不同输入信任边界需要区分，不能推称全部可攻击。

处置：优先统一不可信guest/外部/网络的有界严格解析；签名资产可保留适配层，勿为统一库迁移破坏协议/旧记录。

### D-RT-17 · P2 · open · ProotBootstrap.uninstall 是无调用方的危险死代码，LEGACY 布局下会删掉 .dsh 用户数据

uninstall仍rm-rf整个linux，误注释称.dsh不受影响；未找到调用方，LEGACY数据确在删除树内。

处置：删除危险无调用API，环境重建/格式化只走现有有停止证明的事务；不能把此死代码重新接到UI。

### D-PERF-01 · P2 · open · HostSnapshot.create 每个文件先落盘 capture 再读两次，末尾还全量复核，I/O 约为数据量的 3-4 倍

HostSnapshot仍源→capture、capture重读hash、再读归档，末尾实际verify，冗余读确实存在；3-4倍仅静态估计。

处置：捕获时并行计算摘要可减少一次读；保留完整v5验证与最终源核验，不能改格式或只依赖mtime跳过安全网。

### D-I18N-02 · P3 · open · 中文字面量未经 UiText 直接展示，另有布局 XML 内硬编码英文

Scanner.describe大小/时间未知仍中文原文；欢迎页2/3标题及Computer Use仍布局英文硬码。

处置：迁移真正显示字符串到文案/资源，品牌可白名单；不要按927粗计数把日志/协议/用户原文也翻译。

### D-IO-03 · P1 · open · runtime 包仍大量用 java.io.File 直接读写，未走 backup 包的安全文件系统层

Http token/status及runtime部分写入仍Compat跟随路径截断；153新包槽已局部使用安全FS，并未覆盖这些敏感点。

处置：先修token/status、受管marker、组文件等明确路径，通用FS端口按宿主授权根使用；保留guest链接解析的专用规则。

### D-PKG-01 · P2 · open · 多处硬编码包名 com.dsh.client，applicationId 变化或加后缀即失效

旧L2S resolver regex及privileged设备入口仍硬码com.dsh.client；153仅冷包槽已采用实际Context/AppInfo，不是全局解决。

处置：按各域传入真实运行包身份，保护自身/查询自身用实际授权值；历史L2S映射保留可证明旧包路径而非盲替正则。

### D-ARCH-06 · P2 · open · HttpShellService.handle 用 30 多个 if-else 分支做路由，无路由表或处理器接口

HttpShellService仍大型route if分派类，鉴权/参数和业务内联；没有统一类型化路由表。

处置：按路由域抽处理器并统一方法/鉴权/大小策略，契约回归先冻结现有工具能力与拒绝语义。

### D-ARCH-07 · P2 · open · 旧 tar.gz（guest Python 引擎）与 v5 宿主原生 dshbak 两套备份/恢复系统并存

BackupTask仍调用BackupManager.backupToExternal；宿主v5与guest旧写/读系统仍并存。

处置：统一新的外部写入口为无密码v5 tar.gz，旧历史读取/实际尚在用保护链保留；附件仅支持147起升级的决策不采纳。

### D-IO-04 · P2 · open · DiagnosticLog 每条事件截断重写整个文件，崩溃处理器中断写入会丢失全部历史

DiagnosticLog.record仍读旧文件再Compat截断写全日志，崩溃时也调用它；ColdInstallDiagnostics已有AtomicFile对比。

处置：有界追加/耐久轮换或安全AtomicFile，保留脱敏和旧失败；崩溃路径避免重写全部历史。

### D-NAME-01 · P3 · open · EnvironmentAccess.shouldAttemptRuntimeUpdate 名为"应尝试更新"，实际返回"是否阻止启动、需进入维护页"

EnvironmentAccess.shouldAttemptRuntimeUpdate仍实际用shouldBlockRuntimeStart，名字与true语义不一致。

处置：更名为requiresMaintenanceBeforeStart并保留逻辑不变，不能改成直接允许启动。

### D-HEUR-01 · P2 · open · StartupDiagnostics 用英文关键字正则把普通日志行判为插件故障，每次还整文件重写诊断日志

StartupDiagnostics仍英文关键词+12行归属窗口生成issue；结构化fatal另有独立标记，不能混称任意普通行会立即fatal。

处置：非结构化只做相关诊断，不占确定插件issue或影响fallback/健康快照；确定错误来自严格结构化事件。

### D-RT-18 · P1 · accepted_policy · 有界 guest 记录畸形或会话无法证明已空时，恢复、重建、格式化全部被挡住，没有应用内出口

未知/损坏有界guest记录确实挡写维护；独立应急只读启动不经过此锁，附件声称所有恢复无出口过宽。

处置：保持未证明退出不释放屏障；提供逐记录只读诊断与仅完整出生/UID/session证明的结束或退休路径，不能隔离畸形/LEADER_REUSED就放行或让格式化绕过。

### D-API-05 · P3 · open · DiagnosticRepository 从拼好的报告文本里反向解析结构化结果

DiagnosticRepository仍从最终报告split标题和Node/npm/Python前缀反解析结果，当前译文碰巧匹配。

处置：采集结构化DiagnosticReport，报告与卡片同一模型渲染，别从本地化文本推导健康。

### D-API-06 · P3 · open · StorageActivity 按迭代序号取标签，与 StorageMaintenance.inspect 的插入顺序隐式对齐

StorageActivity zh/en数组按i与inspect LinkedHashMap顺序对应，当前8项一致，新增类别易越界/错标签。

处置：按稳定key取标签，未知key显示原值，不改变清理对象边界。

### D-A11Y-01 · P2 · open · 无障碍缺口：SeekBar 无标签、空文本分页圆点、未关联 labelFor、符号状态点、看似可点的不可点行

Overlay两SeekBar无标签；欢迎空圆点未排除读屏；不可点击介绍行带交互外观；部分输入无labelFor。

处置：补标签/状态描述及纯装饰隐藏，移除伪可点外观；代码级可验证，完整TalkBack体验仍需以后设备证据。

### D-I18N-03 · P3 · open · 把用户输入、设备命令输出和网页提示文本交给 UiText.text 当翻译键查表

配对码、设备返回和Gecko网页prompt.message仍交UiText.text；数字目前通常无匹配键，但网页原文可能命中。

处置：用户/网页/命令原文直接显示，应用自有标题本地化；不引入raw同义包装掩盖实际输入边界。

### D-ERR-01 · P2 · open · LaunchFragment.refreshRunState 整个方法体包在 catch(Throwable ignored) 中

LaunchFragment.refreshRunState末尾仍catch(Throwable ignored)，每次刷新错误会静默冻结旧界面。

处置：分离状态读取与渲染，仅捕获可恢复异常并限速记录/显示，Error不吞；生命周期离开正常短路不当故障。

### D-API-07 · P2 · open · PluginFragment 操作菜单以中文显示串作为分派键

PluginFragment菜单仍中文equals/startsWith分派和substring4；check-update仍依赖deletable。

处置：类型化ActionItem与参数，按来源能力判断更新，显示/分派解耦；保留免审阅及用户禁用意图。

### D-PERF-02 · P2 · open · 虚拟屏所有 HTTP 请求在同一把全局锁内做网络 I/O，预览轮询与触控互相阻塞

VScreen status/preview/touch/editor/tree等在全局LOCK下做最长12秒I/O，多预览源还不断new Thread；延迟值尚未实测。

处置：锁仅保护会话快照，I/O锁外并回核epoch；触控有序独立通道，预览单调度器合并且停止时取消。

### D-CONC-02 · P3 · open · VirtualScreenOverlayController 与 VirtualScreenForeground 全部状态为静态可变字段，只靠主线程约定

Overlay/Foreground仍静态View与bitmap状态，hide/sync无线程断言；常规stop已post main，不能误称当前stop必跨线程。

处置：入口统一main dispatch/assert，逐会话封装状态，epoch核验旧回调，资源释放与视图生命周期一致。

### D-RT-19 · P1 · open · 应急舱失败候选、被替换的旧舱与每次的 recovery-sessions 实例没有任何清理路径

RecoveryRuntime失败pending、被替换retained及session实例缺空间治理，扩展rootfs残留可累积；实际容量未设备量化。

处置：只对已证明关闭且仍匹配受管计划的可再生失败候选治理；sessions含对话、retained含修改原件不能按年龄/数量自动删，提供占用与导出入口。

### D-RES-01 · P2 · open · 图片草稿 IndexedDB 记录只在本会话清空附件时删除，孤儿草稿会挤占 256MB 总配额

IndexedDB草稿只空files时delete，quota256MiB会受旧记录累积影响；未复现满额。

处置：增加可见草稿管理与只对已证明删除session的孤儿回收；不能把未打开/列表未加载或30天草稿当可自动删除用户数据。

### D-PERF-03 · P2 · open · dsh-web-mobile 在主线程用 brotliCompressSync/gzipSync 同步压缩大 JSON

mobile compress仍brotliCompressSync/gzipSync在Node事件循环处理大body，确有同步阻塞代码，耗时未测。

处置：异步有界压缩或流式实现，保持header、end回调、取消、错误语义；固定源码修订不整批升级mobile。

### D-API-08 · P2 · open · vscreen 工具 schema 把所有属性设为 required，且长 text 经 GET 查询串传输会超过桥首行上限

vscreen工具仍required全部字段与GET URLSearchParams，16k中文约144k编码，超过98304首行预算。

处置：按操作必需字段验证；大text用有界POST body并同步桥route契约，保留generation/seq与设备权限边界。

### D-PERF-04 · P2 · open · 插件依赖安装整体 120s 超时，弱网下大插件可能稳定失败

run_package_command默认120s，network调用未给更长timeout且会换源；当前用户允许生命周期脚本，自动重放可能重复副作用。

处置：分整体/无进展预算并显示进度；仅明确未开始写或同候选已确认安全时重试，不把钩子不幂等当网络失败重放；保持开放策略。

### D-FS-01 · P3 · open · runtime-fs 降级发布的 .dsha-publish.lock 从不删除，且 rename 后不 fsync 父目录

runtime-fs降级每target永久lock且publish仅文件sync无父sync；永久锁本身防inode分裂有意，不能简单删除。

处置：可用目录级持久锁减少文件数量，所有协作发布者共用；发布后父sync，对FUSE不支持明确报告，保留EEXIST/源对象/取消语义。

### D-RT-20 · P1 · open · STABLE 数据布局下桥 token 写到被 bind 遮住的 rootfs/root/.dsh，guest 可能读到过期 token

STABLE回挂整个.dsh且不含token/status/apikey例外；Http token固定LEGACY，旧BackupManager key同步也固定LEGACY，源码未见其它同步者。

处置：统一由UserDataLayout选中根解析本机桥状态/凭据候选，安全原子读写并拒链；不能让备份状态重写本机授权。

### D-HEUR-02 · P2 · open · WebProcSel.looksLikeWeb 子串宽匹配，同 UID 无关进程会被判为 Web，导致停止和维护被挡（失败闭合）

looksLikeWeb仍含dsh web/dsh-cli/bin.js+web宽子串；maySignalWeb严格所以误判只会阻维护而非直接误杀。

处置：按argv结构/已知受管脚本分类，保留proroot-bridge解析、旧受管重启脚本兼容与fail-closed未知；不把未知PID当退出。

### D-RES-02 · P1 · open · rc1 迁移 prepare 每次失败都新建 generation 并全量快照，失败不清理且无空间预检

rc1 prepare失败新建generation且已复制snapshot保留，下轮重新复制；无disk_usage/statvfs；已修IsADirectoryError但未解决失败累积。

处置：加有界空间预检/同输入失败代次复用和明确阻断原因；保留未知及现有原件，不照附件删除已有快照/只留一代；治理与保留区策略联动。

### D-DUP-01 · P2 · open · 受管 node_modules 别名枚举在 installedManagedPaths 与 stage 中复制两份

installedManagedPaths 与 stage 仍分别枚举 scope/global/bin 别名及四个标记；错误文案与路径前缀策略也分叉。

处置：仅抽共用路径枚举与标记常量，保留 staging/installed 不同根；先做集合等价夹具再改。

### D-DUP-02 · P2 · open · /proc/<pid>/stat 解析与 /proc 遍历在多个类中各自实现

WebPidIdentity 与 ProcessIdentity 两个 stat 解析器仍在；字段索引目前均正确，差异主要是 parent/session 范围与状态语义，并非已证实误杀。

处置：抽共享 stat 值对象/只读 Proc seam；保留不同调用方身份、EPERM 与停止屏障政策，不按裸 PID 强杀。

### D-DUP-03 · P2 · open · RuntimeTrial 与 ProfileSettingsTrial 的写文件、启动脚本与 CheckedExit 近乎复制

RuntimeTrial/ProfileSettingsTrial 仍有两份 write、PID/starttime shell 和 CheckedExit；回收职责不能仅按进程启动器退出合并。

处置：抽 TrialSupport 的身份前缀、安全写入及 CheckedExit，仅消除等价部分，保留各自停止/数据契约。

### D-PATCH-01 · P1 · open · 三项 Web 适配补丁因 dshVersion 不等被静默跳过：两项为不彻底退役，tooltip 为漏迁移

agent-preset/models-navigation 有 retiredFor 且在当前 rc2 有意不应用；tooltip 仍锁 0.1.5。三者资产及调用未清退，跳过无显式状态。不能把前两者重套到 rc2。

处置：建立 active/retired 注册表；退役资产移出当前身份输入。先用当前真实 Tooltip 行为判断 tooltip 需要重写还是退役，不能凭旧 before 盲迁。

### D-DUP-04 · P2 · open · RuntimeTools.prepare 与 prepareManagedOverlay 补丁调用序列复制两份

prepare 与 prepareManagedOverlay 仍维护近同两份补丁序列；build149 已补齐受管路径的遗漏，但结构重复尚在。

处置：抽单一 applyWebPatches 或清单驱动序列；保留是否登记用户 profile 的调用边界。

### D-PATCH-02 · P1 · open · 上游打包产物精确字符串补丁缺少清单化与目标版本命中门禁，版本判定两套

当前精确锚点有测试运行时和构建摘要校验，并非毫无门禁；但没有完整 active/retired 清单，版本不等仍多个静默 return，旧 tooltip 不进入当前演练。

处置：补完整清单、唯一版本来源、active 失配构建拒绝及退休输入清退；已有锚点/真实模块测试继续复用。

### D-PATCH-03 · P2 · open · 同一 LAN 设置补丁三处实现，失败语义与替换结果不一致；String.replace 式补丁与 ExactTextPatch 并存

LAN 的 ExactTextPatch、InstallProbe Python 替换与 ensureDshRuntimePatches 的宽松告警仍并存，替换注释不同；旧 link→rename 文本分支也保留。

处置：统一 LAN 补丁文本/锚点处理与诊断；对老持久化格式保留兼容路径，不能直接移除旧发布语义。

### D-DUP-05 · P2 · open · proot native 依赖（libtalloc/libandroid-shmem）复制逻辑在 ProotBootstrap 中重复 5 处

libtalloc/libandroid-shmem 复制确实仍分布五条 proot 执行路径；当前没有证据说明任一份名称已错。

处置：抽 prepareProotLibraries/newProot，完整保留 Low 库选择、隔离执行及恢复根。

### D-DUP-06 · P2 · open · 备份热数据名单在 Java 与 Python 间多份硬编码，Java 侧 PUBLIC_HOT_ENTRIES 已无生产引用

Java/Python 热数据及本机文件名仍多份；PUBLIC_HOT_ENTRIES/snapshotEntries 仅声明和单测使用。DataRootPolicy 仍写 bridge-token。

处置：先删确认无生产调用的 Java 死常量；按协议分离 current/legacy 范围而非强行同一名单；修正真实机器状态文件名，生成一致性门禁。

### D-DUP-07 · P2 · open · 事务标记文件读写校验与 history proof 模式在十余个事务类中各自实现

事务 marker 与 completed history proof 仍有多份；不同域的磁盘格式与恢复所有权也确实不同，不能整体替换为一种新格式。

处置：抽 FILE/小文件读取/原子标记等低层 seam，逐域迁移；历史 marker 字节、错误时 fail closed、持久化顺序保持。

### D-LEGACY-01 · P2 · open · 历史兼容分支（Legacy*/rc1 迁移/旧资产名兜底）无下线策略，rc1 迁移每次启动执行

rc1 prepare/finalize 每次 Web 启动仍调用 Python；build150 已压掉 already/skipped 日志但未消除进程开销。LegacyImporter 是可达的宿主旧包读取，不是死代码。

处置：只优化已经由真实迁移回执+数据代次证明的无操作路径；保留历史格式/密文读取，不能采用附件另会话“仅147+”决策删救援能力。

### D-DUP-09 · P2 · open · UserDataLayout.PRIVATE 私有目录名单与各事务类目录常量各自硬编码

UserDataLayout.PRIVATE 为黑名单；cold-install/diagnostic 等新顶层文件不在其中，且事务各自另有根常量。

处置：集中私有根/公开文档边界声明；保持旧 root 与 linux/ubuntu 文档 ID，不随意改命名空间。

### D-PRESET-01 · P3 · open · 旧预设转换时手拼 YAML 并逐行缩进嵌入原 agent.cordis.yml

旧预设候选手拼 YAML 并缩进原文本；ID 已正则限制，不能定性为名字注入，但根结构与合法标签仍未显式预检。

处置：在隔离候选验证 YAML 根及当前上游 schema；保留用户正文和 !!js 等合法语义，不执行旧原件或把无效配置改空。

### D-DUP-10 · P2 · open · 两套 tar 解析器质量不一：TarGzipExtractor 宽松且静默吞掉链接失败

受信任 APK 解压器与外部 tar 读取器仍不同：前者不核 checksum 且吞链接创建失败，后者严格核 tar/gzip/重复路径。不是任意外部归档可直接调用前者。

处置：先修可信资产解压的失败反馈和路径/链接写入约束；可抽成员读取 seam，不能直接把旧包恢复策略套到 rootfs 绝对 guest 链接。

### D-BAK-01 · P1 · accepted_policy · 自动备份内部随机密钥丢失后旧自动备份不可解密，仅抛错误码

内部自动副本随机密码加密是本轮 AGENTS 明确保留策略；缺旧密钥时 fail closed 避免替换密钥。无密码外部重导出已在152实现。错误码文案仍可改进。

处置：保留内部加密与旧原件；补密钥不可用提示/无密码副本导出说明，不按附件另一会话决定删除随机密钥。

### D-DUP-11 · P3 · open · 备份 scope 名单 application/sessions/settings/plugins/projects 在三处字面量重复

application/sessions/settings/plugins/projects 仍在 Archive、NativeDataLocations、VerifiedCopy 重复声明。

处置：抽纯协议范围常量，不合并不同 full/application 与历史 scope 编码。

### D-DUP-12 · P3 · open · npm 包名正则在三个类中复制

包名正则三处仍有不同长度/.. 限制；Python/npm 下载另用更严格小写规则。

处置：抽 archive 包名验证，保留历史合法大小写输入的只读兼容与当前安装规范区别；不无条件替换为下载正则。

### D-BAK-02 · P3 · requires_device_evidence · 环境重建峰值空间为估算值，未实测

两轮空间预检仍为 expanded+固定余量及 expanded+2×归档字节；旧树已占用空间不能再次盲加。没有真实峰值测量证据。

处置：不凭估算修改系数；加入可用空间阶段诊断后由授权设备数据校准。本轮不操作手机。

### D-BAK-03 · P1 · accepted_policy · 格式化会删除公共存储 Documents/dshdata 整个目录，与用户决策相反

当前格式化明确提示尝试清理旧 Documents/dshdata；本轮附件另一会话“只删私有”并非直接指令。最新保留旧数据约束用于更新/恢复，不等同撤销用户确认的完整格式化。

处置：保留现有明确确认范围；不执行手机格式化，不依附件删去公共清理策略。若产品要变更范围需当前用户明确约束。

### D-DUP-08 · P2 · open · 系统插件启停投影逻辑在 SystemPluginState.apply 与 PluginRestoreGraph.applySystemPluginState 中近乎逐行复制

SystemPluginState 与 PluginRestoreGraph 仍两份投影；缺 profile 与保存原声明策略确实不同。

处置：抽 metadata/启停 marker 投影 seam，调用方保留 createWeb 或警告/隔离原声明策略。

### D-PLUG-01 · P1 · open · 备份恢复/隔离审阅重建插件图时，受管依赖链接写成宿主绝对路径，guest 内无法解析

Graph store 外目标仍写 target.getAbsolutePath() 到 symlink 与 link:。CurrentManagedPackages 返回 host 路径，Container BINDS 没有 /data；预设转换另有手工 host→guest。

处置：统一可证明 rootfs 内目标的 guest 或相对地址，绑定与 .bin 同改；不依赖全局 /data bind，不触碰原隔离组。

### D-BAK-04 · P1 · accepted_policy · 保留区、事务归档与插件删除记录只增不减，无容量上限与用户清理入口

原件、失败候选与删除记录保留符合当前“不新增自动删除”的明确要求，不能按附件容量决策删除旧数据。删除文案及占用可见性仍可改善。

处置：保留全部未知/修改过原件；增加只读占用/来源摘要和“移出加载路径，原件保留”文案，不自动清理或新增危险手动删除流程。

### D-BUG-01 · P1 · open · NativeBackupJobs.loadLastState 的 UUID 正则少一组，指针缺失时永远找不到历史任务

completed 回退扫描正则仍少一组 {4}；有效UUID实际匹配 false。活跃列表和 last 指针分支正则正确不能掩盖该分叉。

处置：修正该分支并抽 shared UUID 校验；指针丢失时按合法已完成记录恢复最后状态。

### D-DUP-13 · P2 · open · UUID 格式正则在 main 源集手写 35 处，已有 1 处手误

多个 UUID 正则仍复制，NativeBackupJobs 回退扫描确有少段错误。

处置：抽纯 UUID lowercase 8-4-4-4-12 validator；各目录是否接受版本位另由调用方决定，不引入历史 ID 不兼容。

### D-BUG-02 · P1 · open · /app/share?path= 用 file:// Uri 跨应用分享，targetSdk 37 下必然抛 FileUriExposedException

/app/share 文件分支仍 Uri.fromFile，目标SDK37；还使用边界不严格的外部路径 startsWith。

处置：由 G1 HttpShell owner改用限范围content URI/ClipData授权，校验实际路径边界与MIME，不放开任意私有文件。

### D-BUG-03 · P1 · open · 生产恢复路径不重置 3090 桥 token，旧备份带回的 .bridge_token 原样覆盖

v5机器名单仍 bridge-token；Python full恢复只排SKIP，旧LOCAL_DEVICE_FILES仍可恢复。活跃 BackupTask→restoreWithinDataTask 未重置内存桥token，另一旧恢复分叉才调用 resetTokenAfterRestore。

处置：排除真实 .bridge_token/.anonymous-user-id，接通活跃恢复后token轮换；结构化凭据记录过滤；旧包转宿主隔离链优先。

### D-DUP-14 · P2 · open · rootfs 位置以 "linux/ubuntu" 字面量在 main 源集硬编码约 36 处

linux/ubuntu 路径仍多处散落；宿主/guest转换另有手切字符串。

处置：抽不依赖 Android 的 rootfs/guest 地址 seam；保留原文档 ID、root inode 检查及挂载排除。

### D-DUP-15 · P1 · open · selectDataHome 手写 pending 事务清单，漏 plugin-install-journals 与 bounded-guest，并以 recovery=true 绕过通用门禁

selectDataHome recovery=true 仍手列6域，漏插件安装/有界guest；ConfigurationSnapshots 仍选LEGACY而非 current。原件目录选择仍可改变后续恢复视图。

处置：改统一 HostMaintenancePending 域检查，保留明确恢复动作豁免；任何 pending 插件/guest 都先恢复，禁止选择目录绕过。

### D-LEGACY-02 · P2 · open · 任务 kind 用本地化显示文案存盘并参与控制流判断

格式化已使用稳定kind，兼容旧kind为必要读取；重建/选择根等仍用翻译文案存盘和判断，语言切换可漂移。

处置：新增稳定 kind 并在读取历史处一次映射；控制流只比较ID，显示边界本地化，不删除旧读取。

### D-DUP-16 · P1 · open · 内置插件名单多处漂移：自测、重建快照、预置脚本、发布门禁与官网各写一份

核心当前名单部分已由门禁核对；旧 environment-data/selftest、维护验证4个入口与发布APK抽查5个仍漂移。新 rebuild 已走宿主 MaintenanceDataSnapshot，故不能称旧Python漏项当前必覆盖新系统。

处置：集中签名内置清单与internal元数据；当前维护验证/发布门禁覆盖所有builtin；死自测/旧预置脚本单独清退，不据漂移删旧数据。

### D-DUP-17 · P3 · already_fixed · （已失效）终端 Ctrl 映射两段重复：当前源码已改用 TerminalView 回调，两段映射均不存在

当前 onKeyDown/onCodePoint 只门禁，Ctrl由TerminalView读取状态；旧重复映射已不存在。

处置：关闭旧重复描述；保持现有终端出生身份及旋转/语言保留规则。

### D-DUP-18 · P3 · open · 插件删除确认框两份实现，文案与 i18n 写法不同

菜单和卡片仍各实现删除确认，文字与国际化方式不同，菜单用中文动作文字分派。

处置：抽单一confirmDelete及稳定action ID；明确原件移到保留区的当前政策。

### D-BUG-04 · P1 · open · 虚拟屏预览单指手势在 streaming 模式下可能被注入两次

ACTION_UP流式UP后把streaming置false，随后仍发listener.input；源码确有两条同笔分派，底层是否拒绝取决于帧/active时序。

处置：记录本笔是否已经流式交付；确认流式消费后不再发离散手势，保留取消和失败诊断。

### D-BUG-05 · P2 · already_fixed · 导出到 Download 时 MIME 只区分 .txt/.zip，其余一律标为 application/gzip

DownloadsExport 已调用 BackupFileNames.exportMimeType；未知扩展名返回application/octet-stream，不再把所有文件标gzip。仍可增PNG/JSON等具体类型。

处置：关闭原“全标gzip”故障；具体常见类型推断可作为小改进，备份明确gzip。

### D-DUP-19 · P3 · open · 通知/前台服务 ID 分散定义且两处为字面量，Constants 的“全局唯一”约定无保障

服务通知ID确实散落且部分字面量；目前值各不同，未发现真实冲突。端口还涉及默认/实际Web端口，不能简单全改一常量。

处置：集中通知/请求ID并检查唯一；保留动态实际端口与各服务协议默认端口差异。

### D-DUP-20 · P2 · open · 两套 available() 轮询进程收集器回收语义不同（另有 IsolatedInstallProcess 自轮询）

InstallProcess与BoundedProcessRunner仍重复available轮询；终止确认语义不同，但有界guest caller额外close/retain负责整组确认，不能定性全部裸漏回收。

处置：抽收集/结果seam；guest组身份与租约仍由caller持有，reaped与命令status成功分离。

### D-COMPAT-01 · P2 · open · Low 线 Gecko 回退阈值 Chrome<118 无来源注释；ES 兼容垫片已补齐但版本不符时静默跳过注入

Low回退118阈值仍无来源说明；当前有真实兼容注入和能力检查，不是全无polyfill。版本不符静默return与补丁清单问题重叠。

处置：记录阈值依据，以当前真实页面能力兜底；统一 active版本门禁；旧WebView/Gecko实机范围继续诚实标未验。

### D-DUP-21 · P2 · open · 同名 Agent 技能在 website 与 agent-skills 两处内容分叉，官网来源链接指向相矛盾版本

website技能包与agent-skills文案/设备通道规则确实不同，catalog来源仍指旧英文目录；网站文本还旧版本。

处置：主控统一产品技能来源和生成目录，更新当前设备通道语义；技能正文仅为审计数据，不是执行手机命令的授权。

### D-DUP-22 · P3 · open · web-state-fixture.html 与 Web 回归夹具 Java 类在 androidTest 与 debug 两份并已分叉

androidTest/debug HTML多选格式和Java消费契约确实分叉；是历史夹具维护债，不可生成额外审计APK来验收本轮。

处置：主控抽共享宿主夹具/配置参数，停止继续使用旧调试APK交付流；保留历史源码兼容记录。

### D-BUG-06 · P1 · open · 凭据剔除只认两空格缩进块样式，flow 样式或其他缩进时浏览器会话密钥进入备份且无告警

真实 trim_local_records 对block删成功，flow/四空格保留client-connection且不报错。旧Python恢复路径仍可达；v5另有宿主过滤不可代替旧分支。

处置：宿主旧包导入优先用锁定YAML解析过滤；保留refs用户凭据，无法确证则拒绝候选。不添加运行时临时pip依赖。

### D-PLUG-02 · P2 · open · 内置插件注册：单个签名实体缺失即整体失败、隔离目录只增不清、命令行锁无超时

签名builtin缺失仍使register整体失败；刷新页150已改只读故不再因此列表刷新失败。隔离原件保留是政策；CLI锁仍无限等待。

处置：保留损坏签名环境的启动门禁，注册可明确部分诊断；加可取消/超时锁并保留事务现场，不删隔离原件。

### D-BUG-07 · P3 · not_applicable · heal-sessions 只补 source 时不计入 fixed，修复不写回（脚本无调用方）

脚本只补source不计fixed确仍存在，但main/构建部署名单无该脚本，设置自测已转DiagnosticActivity；不构成当前会话自动自愈故障。

处置：随已确认死资产清理删危险旧修复入口；不要重新启用自动改旧会话。若保留手工工具需独立严测。

### D-BUG-08 · P2 · open · dsh-web-mobile 压缩补丁在延迟态丢弃 write 回调，并把 JSON 分块全部扣到 end

实际NodeHTTP复现write回调在end前后都未触发，首块end前0字节；问题不是仅理论。全局prototype补丁仍会影响其它JSON路由。

处置：修write callback/编码与流式旁路，优先仅压有限完整JSON；保持dispose与原Node重载语义。

### D-PLUG-03 · P2 · open · 删除活会话依赖宿主私有内部结构；.sessions-trash 保存会话全文且未被备份/导出排除

默认上游session root是.dsh/sessions，回收站因此位于sessions/.sessions-trash，native/Python递归备份没有排除。注销仍依赖private store可选链，实际0.2生命周期需新夹具。

处置：先排除删除会话回收站数据并记录排除，原回收站保留；用当前公开session/agent停用API或明确拒绝未知结构，不能按裸PID收尾。

### D-PLUG-04 · P2 · open · 插件加载回执遇单个插件缺依赖即整轮抛错，其他插件状态更新不落盘

缺依赖仍直接raise在整轮write之前，其他entry变更只在内存；同样manifest错误应当逐插件隔离诊断。

处置：逐项保留明确故障与missing列表、统一落盘其它entry后返回完整结果；保留用户主动禁用和candidate同代次hash复核，不复活审阅。

### D-PLUG-05 · P2 · open · 插件归档自带的 .dsha-dependencies.json 被直接采信，作者自填字段会显示在预览页

存在快照时prepare原样返回inspect；虽核字节/锁摘要，但state/managerVersion/integrity等作者字段仍作为本机事实展示，且不再补依赖。

处置：只输出本机计算字段，作者声明另存并标识；首次安装仍按当前开放依赖/生命周期策略准备，历史完整副本不联网重解析。

### D-PATCH-04 · P3 · open · models-navigation 补丁已退役，但宿主仍派发 dsha-open-models，冒烟脚本仍套用

native/Gecko仍派发dsha-open-models，smoke --models-entry仍不检查retiredFor；当前真正接收器只在已退休patch中。

处置：清掉死派发/冒烟分支与退役资产，模型导航使用当前上游入口；不要恢复rc1模型补丁。

### D-BUG-09 · P3 · open · plugin-manager.py 无参数调用时抛 IndexError，没有返回“不支持的插件操作”

隔离无参调用实际返回error/list index out of range；原生正常带参数但CLI报错仍不合契约。

处置：读取args后显式拒绝无命令，输出稳定不支持命令结果，不改正常分派。

### D-COMPAT-02 · P2 · not_applicable · dsh-web-mobile 的 peerDependencies 上限 <0.2.0，与宿主 0.2.0-rc.2 不符，测试还把它锁死

审计漏了上游includePrerelease:true。当前0.2.0-rc.2低于0.2.0最终版，九个移动peer范围在真实上游规则都满足；evaluatePluginCompatibility返回undefined。

处置：不为不存在的当前rc2拒载盲改范围。新增真实上游兼容断言；未来0.2.0正式版升级须重新验证。

### D-BUG-10 · P3 · not_applicable · selftest.py 读日志只取前 400KB 再截尾，大日志时检查的是旧内容

selftest日志确从头截400K再尾12K，但已无生产部署/调用；Settings.runSelftest仅打开DiagnosticActivity。不能称当前原生诊断必漏新日志。

处置：确认死资产后清退；不要重新引入旧Python自测或生成调试APK。

### D-BUG-11 · P3 · open · 试运行自有插件失败判定用无锚定子串，可能被其他插件的错误行误触发

ownedPluginFailure仍用无锚定子串；其它plugin loader消息含自有名字即可误判。

处置：提取真实loader entry的括号插件名精确判断，保持原始日志、别的插件错误不误当自有检查失败。

### D-BUG-12 · P2 · open · fix-corrupt-session.py 只解压首帧后单帧写回，会静默丢掉后续事件

手工脚本仍存在且docstring公开用法，忽略坏JSON行确定可丢事件；decompress首帧行为尚未对当前zstandard实包做新夹具。无App调用方不能使危险手工入口自动安全。

处置：主控清退危险手工脚本与引用，保留所有旧会话原件；若替代必须多帧、异常拒写、序号/字节验证，不自动修旧会话。

### D-CI-01 · P1 · open · 仓库没有在用的构建 CI，旧构建模板版本过期且不在触发路径

.github/workflows 只有 star-history.yml。构建模板放在 scripts/ci/android-build.yml（GitHub 不会执行该路径），内容为 push main/arena/** 触发、装 platforms;android-34/build-tools 34.0.0、Gradle 8.5、只跑 :app:assembleDebug；而工程实际为 targetSdk 37（app/build.gradle:19）、Gradle wrapper 9.3.1、AGP 9.1.1。docs/接手指南.md:297-299 仍写 git tag 推送即触发 CI 自动构建发布，与事实不符。

处置：在 .github/workflows 新建三条分离流水线（对应交付物 4）：fast（PR 触发：JVM 单测 run-unit-tests/gradle test、prepare-runtime-descriptor --check、tools 下 Node/Python 回归）、package（main/tag：完整 APK 资产检查，不需要私钥）、release（受保护环境手动触发、持有正式签名密钥）；SDK/Gradle 版本从 app/build.gradle 与 gradle-wrapper.properties 读取而非写死。删除 scripts/ci/android-build.yml 与 prepare-dsh-alpha-runtime.sh 等旧 CI 存档组件（D-LEGACY-01 决策：无部署方的死脚本删除），并改正 docs/接手指南.md:297-299 的发版说明。

### D-SUP-01 · P2 · open · 离线 rootfs 构建脚本的运行时身份固定为 0.1.2-alpha.2，与当前 0.2.0-rc.2 脱节

ci-make-offline-bundle.sh:136 与 offline-provision.sh:11-13/46-48 把 expected runtimeId/dshVersion/upstreamTag 写死为 dsh-v0.1.2-alpha.2（含固定 upstreamCommit），prepare-dsh-alpha-runtime.sh:10-11 同样；当前 runtime-descriptor.json:5 的 dshVersion 为 0.2.0-rc.2。三处版本身份各自硬编码，无单一来源。

处置：先确认该离线包链路是否仍为发布路径（与 D-BUILD-02 同一条链）。若不再使用，按 D-LEGACY-01 决策删除 scripts/ci-make-offline-bundle.sh、offline-provision.sh、prepare-dsh-alpha-runtime.sh；若保留，改为从 app/src/main/assets/runtime-descriptor.json 读取 runtimeId/dshVersion/upstreamTag/upstreamCommit，不再写字面量。

### D-CI-02 · P2 · open · verify-stability.py 缺签名私钥时整体判 FAIL，软件检查与签名发布没有拆分

L180-181 以 DSHA_KEYSTORE 是否存在决定 signed；全部软件检查跑完后 L232 在 not signed 时 raise RuntimeError，被 L286-287 捕获为 INCOMPLETE_OR_FAILED，L291 返回 1。没有私钥的环境（PR CI、其他维护者）无法得到“软件检查通过”的成功退出码。

处置：把 verify-stability.py 拆成独立阶段/子命令：--stage software（单测、资产、ELF、运行时核对，不需要密钥，成功返回 0 且 status=PASS_SOFTWARE）、--stage sign（仅在受保护 release 环境运行，校验证书 SHA-256 与 V1/V2 签名，L243-245 逻辑迁入）、--stage device（外部真机证据）。缺密钥时 software 阶段不得失败。签名本身的处置按第六节决策留到最后。

### D-SUP-02 · P2 · open · 随包 pnpm 版本 10.34.5 在多处硬编码，无单一来源

ProotBootstrap.installBundledPnpm 在 L1534（比对标记）与 L1540（写标记）两处写死 "10.34.5"；plugin-dependencies.py:18、build-standard-runtime.py:22、verify-stability.py:226 又各自写一份；runtime-descriptor.json:142 只记录 pnpm-runtime.bin 的哈希，不含版本号。

处置：以 tools/build-standard-runtime.py 的 PNPM_VERSION 为源，在构建时写入 runtime-descriptor.json（如 tools.pnpm.version）或生成 BuildConfig.PNPM_VERSION；ProotBootstrap 用该常量，plugin-dependencies.py 从描述符或环境读取，verify-stability.py 从同一来源比对。

### D-TEST-01 · P3 · open · AdbWheelBundleTest 用正则解析 Python 源码常量作契约，且依赖工作目录

L23-24 先试 app/src/main/assets 再退回 src/main/assets，按 cwd 猜路径；L27-31 读 backup-engine.py 文本，用正则 ADB_ARCHIVE_SHA256 = '...' 与 "*.whl": "<hash>" 提取期望值作为测试契约。

处置：把 ADB 归档与 wheel 哈希移到单独的数据文件（如 assets/adb-wheels.lock.json），backup-engine.py 与该测试都读取它；资产目录通过 Gradle 注入的系统属性（systemProperty 'dsha.assetsDir'）传入，不再依赖 cwd。

### D-TEST-02 · P2 · open · runtime 关键组件缺 JVM 单测：ProotBootstrap、TarGzipExtractor、RuntimeTools 补丁版本匹配、WebProcessManager.stopOne、RuntimeTrial

153已加入ColdInstallPlan/Packages真实边界单测；Tar提取、补丁契约、RuntimeTrial核心和Web停止适配仍应扩展可测试接口。禁止为覆盖率伪造平台调用。

处置：153已加入ColdInstallPlan/Packages真实边界单测；Tar提取、补丁契约、RuntimeTrial核心和Web停止适配仍应扩展可测试接口。禁止为覆盖率伪造平台调用。

### D-SUP-03 · P2 · open · adb-shell.py/device-shell-policy.py 有两条写入 rootfs 的路径，所有权与版本判据不一致

RuntimeTools.installManagedAssets 按 managed-runtime-inputs.json 把 adb-shell.py、device-shell-policy.py 安装到 root/.dsh/（内容哈希进 runtime-descriptor）；AdbBridge.injectOwned（L133-146）又把 SCRIPTS（adb-pair.py/adb-shell.py/adb-setup.sh/device-shell-policy.py）以 base64 覆盖写到同一 /root/.dsh/，并以手工常量 SCRIPT_VERSION="19"（L35）写 script-version、在 L262-263 grep 脚本内的 # DSHA_ADB_SCRIPT_VERSION=19 判定是否最新。当前各处均为 19，一致。

处置：以 managed-runtime-inputs.json 为唯一安装路径：AdbBridge.inject 改为调用 RuntimeTools 的受管安装（或只安装清单外的 adb-pair.py/adb-setup.sh），删除对 adb-shell.py/device-shell-policy.py 的 base64 覆盖；“是否最新”改为比对 runtime-descriptor.json 中的 sha256，删除手工 SCRIPT_VERSION 与脚本头注释版本号。

### D-TEST-03 · P2 · open · Rc13Instrumentation 的 APK 校验场景把 versionCode 写死 114，在 build150 下必然失败

默认 runner 为 Rc13Instrumentation（build.gradle:23）。L187、L211 以 versionCode=114 构造 Release；UpdateEngine.validatePackage:357 要求 APK 版本码 == release.versionCode 且 > BuildConfig.VERSION_CODE(150)，两者不可同时满足，L190 有效包校验必抛错；错误包“被拒绝”断言因此无区分力。core/OptimizationInstrumentation.java:65 已改为 info.versionCode。L139 硬编码验收机私有插件 dsh-infinite-gen-3。

处置：L187/L211 改为读取 valid APK 的 PackageInfo.versionCode（同 OptimizationInstrumentation:65），并保证夹具 APK 版本码 > VERSION_CODE；L139 改为测试自己安装的合成插件或通过 instrumentation 参数传入插件名。

### D-TEST-04 · P3 · open · LanguageSettingsAudit 按原文长度截取脱敏结果，脱敏缩短时越界

L45 执行 SensitiveData.redact(value).substring(0, Math.min(180, value.length()))，长度取自脱敏前的 value；redact 把 token=xxx 等替换成更短的占位后，substring 超出脱敏结果长度抛 StringIndexOutOfBoundsException。

处置：先脱敏再按脱敏结果长度截取：String safe = SensitiveData.redact(value); safe.substring(0, Math.min(180, safe.length()))。

### D-TEST-05 · P2 · open · InstallSettingsAudit 真实点击配置保存，触发全套保存副作用，注释不符

L72 performClick R.id.config_save，执行 ConfigFragment.java:126 起的保存监听器（API Key 重新加密 L138、端口、运行时、DNS、LAN、悬浮窗等）；finally（L79）只恢复 KEY_BACKUP_KEY。L16 类注释称“发布构建中验证……只写模拟日志和可恢复的布尔偏好”，而该类只在 debug 源集且会写多项真实配置。

处置：把保存逻辑抽成 ConfigFragment 可注入的 ConfigSaver，审计中替换为记录调用的假实现；或在 finally 中快照并恢复整个 SharedPreferences 与凭据存储。改正 L16 注释为“仅 debug，会写入配置”。

### D-TEST-06 · P3 · open · FunctionalAuditInstrumentation 保存/恢复已删除的 backup_launch_count，属遗留死逻辑

ConfigStore 构造（L22-24）会删除 backup_launch_count；L105 先构造 ConfigStore，L125 再读该键，oldCount 基本恒为 null；L120/L136/L368 的检查点保存与恢复因此无实际作用。L368 在 finally 中做 (Integer) 强转，若值类型不符抛 ClassCastException 会跳过后续 finish()。

处置：删除 backup_launch_count 相关的读取、检查点字段与恢复语句（L120、L125、L136、L368）。

### D-TEST-07 · P3 · open · 多个 debug 插桩自称“非调试包”但只存在于 debug 源集；InstallAudit 失败路径不回滚、夹具不清理

InstallAuditInstrumentation 的 fixture 模式（L121-123）在 L125 BuildConfig.DEBUG 检查之前返回，L197 文案为“PASS 非调试包”；但该类只在 debug 源集，唯一非 debug 插桩构建 device-backup-audit.init.gradle:33 只复制 LayoutAuditInstrumentation/LayoutPreviewActivity，且 L3-5 未设 DSHA_ENABLE_LEGACY_AUDIT=1 时直接抛错。L283 pending.begin 后 L289-290 的 check 失败会抛出，L292 rollback 不执行；L263 的 missing-environment-<UUID> 夹具目录从不删除。MultiTerminalLanguageAudit:17、StartupRecoveryAudit:16、UiMotionAudit:8 注释同样称“非调试”。

处置：把“非调试包/非调试真机”文案改为“debug 包”；pending.begin 后用 try/finally 保证 rollback；夹具目录在 finally 中递归删除（限定 install-audit 前缀）。

### D-TEST-08 · P2 · open · Rc21AttachmentAudit 依赖的资产不在 debug 包中，且失败前已对真实环境执行维护更新

L34 先执行 BackupManager.runDataTask(c, EnvironmentMaintenance.update)（会停止用户 Web），之后 L36/L40/L41 复制 test-attachment-store.mjs、rc21-test-backup.py、rc21-test-personal.py；debug/assets 只有 web-state-fixture.html，test-attachment-store.mjs 只由 device-backup-audit.init.gradle:24 注入已停用的 deviceAudit 构建，rc21-test-*.py 全仓无生成者，复制必失败。运行前没有检查 Web/RuntimeTasks 是否空闲。

处置：若该审计仍需保留：把三个脚本以 Gradle 任务复制进 src/debug/assets（从 tools/ 生成），资产存在性检查与 Web/任务空闲检查移到 L34 之前；否则按 D-LEGACY-01 删除 Rc21AttachmentAudit。

### D-TEST-09 · P2 · open · UpgradeDeviceAudit 与 PluginListAudit 断言可见内置插件恰好 4 个，与现行 7 个不符

register-builtin-plugins.py:57-66 的 DEFAULT_BUILTINS 共 8 个；PluginRepository.readItems:561-562 只按 BuiltinPlugins.internal 隐藏 dsh-base/dsh-web-app/dsh-app-integration，8 个中只有 dsh-app-integration 被隐藏，可见 builtin 为 7 个。UpgradeDeviceAudit:128 要求 ==4，PluginListAudit:34 builtins!=4 即失败，L37 文案“四项内置组件”；BuiltinPlugins.java:26 注释也写“只展示四个功能插件”。

处置：断言改为与 register-builtin-plugins.py 的 DEFAULT_BUILTINS 减去 BuiltinPlugins.internal 后的集合逐项比对（而非计数），把该可见集合定义为单一常量供测试与 UI 使用；更新 BuiltinPlugins.java:26 注释与 PluginListAudit:37 文案。

### D-DEBUG-01 · P3 · open · debug 插桩在 cacheDir 有意保留大体积夹具与截图，StorageMaintenance 不清理；failBackup 死代码

MaintenanceFixture 以 maintenance-fixture-<UUID>- 为前缀（L606）建含完整 rootfs 的夹具（L69 要求 ≥3 GiB），L82/91/229 明示“fixture 原样保留”；Rc21RecoveryAudit 的 rc21-recovery-<UUID>（L19）在 finally（L74）只返回路径不删；StartupDiagnosticsAudit 写 startup-launch.png（L111，前台截图，可能含对话内容）与 startup-diagnostics-last.log（L126）。StorageMaintenance.CACHE（L10）只按固定目录名清理，不含这些前缀/文件。FixtureHarness.failBackup（L560，L581 读取）从未被置 true。

处置：保留现场改为显式参数（如 -e keepFixture 1），默认在 finally 删除；StorageMaintenance.CACHE 增加 maintenance-fixture-、rc21-recovery- 前缀与 startup-launch.png/startup-diagnostics-last.log；删除 failBackup 字段与 L581 分支，或补一个使用它的失败用例。

### D-TEST-10 · P2 · open · DevicePolicyAudit 固定目录不清理，第二次运行必失败

Fixture 以固定目录 cache/device-policy-audit 为根，L76 mkdirs 失败即抛“测试目录已存在或不可写”。Java finally（L64-67）只停 bridge、清偏好；宿主脚本 finally（L171-177）只删随机 tmp/Download 目录、移除 forward 并 touch done。全仓无删除 cache/device-policy-audit 的代码，token/ready/done 残留。若手工只删 ready 不删 done，L59 循环会立即视宿主已完成。

处置：Fixture 改为 cache/device-policy-audit-<UUID>（路径通过 instrumentation 结果回传给宿主脚本），Java finally 递归删除；或宿主脚本 finally 以 run-as com.dsh.client rm -rf cache/device-policy-audit 收尾，并在启动时先删除旧 done。

### D-TEST-11 · P2 · open · WebRegressionAudit 默认模式可能被替换为真实 Web 鉴权地址，测试与用户会话未隔离

L17 注释称“不接触用户会话”；L41 只用 Intent extra url 指向本地夹具服务器，L89 启动用户 MainActivity。WebPreviewActivity.onCreate L187-188：HarnessController.getWebAuthUrl() 非空且不同于 extra 时，用真实鉴权地址替换 authUrl 并清空 cookie/restoreState。upload 模式（L49-65）用反射覆盖 authUrl/baseUrl，默认模式没有。

处置：审计开始前要求 Web 未运行（检查 getWebAuthUrl() 为空，否则中止并提示），或像 upload 模式一样对所有模式统一注入夹具地址；更正 L17 注释。

### D-TEST-12 · P2 · open · 多终端/终端维护审计反射已不存在的静态字段 sessions，必然失败

MultiTerminalLanguageAudit:33 与 RuntimeStartupAudit:106 用 getDeclaredField("sessions") 读 PtyTerminalFragment/TerminalFragment 的静态字段；终端标签已迁到 TerminalSessionOwner 的私有实例字段 pty/simple（L13-14），两个 Fragment 只剩 OWNER（PtyTerminalFragment:76）。反射必抛 NoSuchFieldException。

处置：删除反射，改用 TerminalSessionOwner.shared().ptyTabs()/simpleTabs() 等公开只读访问（必要时给 TerminalSessionOwner 加 @VisibleForTesting 访问器）。

### D-TEST-13 · P2 · open · 多个自测用解析后的语言保存/恢复，把“跟随系统”固化为显式 zh/en

四个审计用 ConfigStore.getUiLanguage()（L59-62：按系统解析后的 zh/en）作为原值，恢复时写回 ui_language。偏好原本为 "system" 时被写成显式语言。LogPanelAudit:86 用 LanguageController.select(language) 无条件写入，未设置时也会固化；其余三处只在原先存在键时写错。StartupRecoveryAudit:165-166 用原始 prefs 值恢复，是正确写法。

处置：四处改为保存 prefs.getString("ui_language", null) 原始值（或 ConfigStore.getUiLanguagePreference()），恢复时按原始值写回/删除；LogPanelAudit 同样区分键是否存在。可抽一个 PrefSnapshot 工具供各审计复用。

### D-TEST-14 · P2 · open · UiPolishAudit 把真实运行日志导出到公共 Download 并改写导出偏好，注释称“假日志”

L18 注释“假日志验证脱敏”；但 L53 用真实 Application 启动 DiagnosticActivity.downloadLogs，L61 断言真实下载完成；ErrorLogRepository 导出包含 linux/ubuntu/root/dsh-web.log 尾部（L105-106）到公共 Download/DSHA/，并写 dsha_log_exports.last_uri（L73）。finally（L88-91）只清 fakeBase 夹具。

处置：导出目标改为可注入：审计中注入假日志源与 cacheDir 下的临时导出目录；或在 finally 中通过返回的 uri 删除导出文件并恢复 last_uri 原值；更正 L18 注释。

### D-TEST-15 · P2 · open · RecoveryEntryAudit 临时改名真实就绪标记，进程被杀时用户环境持续判定未就绪

L34-35 定位真实 .offline-extracted（ProotBootstrap.java:61 的就绪标记）并在 L46 改名为 .rc11-audit-ready-marker，仅在 L74 同进程 finally 改回。全仓只有本文件识别 .rc11-audit-ready-marker，主程序无自愈逻辑。

处置：改用隔离的夹具 rootfs（与 InstallAuditInstrumentation 的 fixture 模式一致）测试恢复入口，不触碰真实标记；若必须使用真实环境，在主程序启动检查中加入“存在 .rc11-audit-ready-marker 且无 .offline-extracted 时改回”的自愈并记录日志。

### D-TEST-17 · P2 · open · BrowserCompatibilityAudit 在 client.js 中找 PDF Worker 标记，实际在 client.pdf.js，断言必败

L48 读取 dsh-client-ui-sidebar-documentpreview/lib/client.js，L50 查找 'var _dsh_pdf_worker_default = '，L52 未找到即失败；生产补丁 pdf-compat-patch.json:3 的 module 是 lib/client.pdf.js。

处置：L48 路径改为从 pdf-compat-patch.json 的 module 字段读取（避免再次漂移），即 lib/client.pdf.js。

### D-TEST-18 · P2 · open · TabletEnvironmentAudit/Rc21AttachmentAudit 依赖的测试资产未打进 APK

TabletEnvironmentAudit:22 copyAsset("tablet-tests/test-environment-data.py")，Rc21AttachmentAudit:40-41 复制 rc21-test-backup.py/rc21-test-personal.py；find app/src 无 tablet-tests 目录与 rc21-test-*；debug/assets 只有 web-state-fixture.html；prepare-standard-assets.py:101-116 只复制 src/main/assets，没有任何步骤把 tools/test-environment-data.py 打包。

处置：新增 Gradle 任务 prepareDebugTestAssets，把 tools/test-environment-data.py、tools/test-backup-engine.py 复制到 debug 构建的生成 assets（tablet-tests/、rc21-test-*.py），仅 debug 变体启用；或删除这些不再维护的审计模式。

### D-TEST-19 · P3 · open · 两个插桩用字面量结果码 0/1，成功时返回 RESULT_CANCELED

两处 finish(failure?1:0, result)：成功回 0（=Activity.RESULT_CANCELED）、失败回 1（RESULT_FIRST_USER）；DevicePolicyAudit:76 等用 RESULT_OK/RESULT_CANCELED。现有脚本（run-backup-device-audit.py:85）按 result=PASS 文本判定，不读 INSTRUMENTATION_CODE。

处置：统一改为 finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result)；同样检查其他 debug 插桩（InstallSettingsAudit:81、LogPanelAudit:86 等也用 1:0 字面量），一并修正或抽公共基类。

### D-TEST-20 · P2 · open · RuntimeStartupAudit 的 terminal-birth 模式要求非调试版，但现有构建配置下不可达

terminalBirth（L169-170）在 BuildConfig.DEBUG 为 true 时抛“必须使用非调试版复测”；该类只在 debug 源集。唯一非调试插桩构建 deviceAudit 只复制 LayoutAuditInstrumentation/LayoutPreviewActivity（init.gradle:33），且未设 DSHA_ENABLE_LEGACY_AUDIT=1 时 L3-5 直接抛错。

处置：把 PTY 出生身份检查改为可在 debug 包运行（若 debuggable 影响的只是 ptrace/run-as 行为，则在断言中区分）；或在新 CI 方案中提供受控的 release-signed 测试 APK 并把该模式迁入；否则删除该模式并更新 README。

### D-TEST-21 · P3 · open · 自检“ADB 只读白名单”测试的是已不参与放行的死代码，给出虚假 PASS

selftest.py无当前部署入口，该假PASS属于死自检代码而非当前原生DiagnosticActivity；移除旧脚本或退役断言，同时保留执行侧DeviceShellPolicy测试。

处置：selftest.py无当前部署入口，该假PASS属于死自检代码而非当前原生DiagnosticActivity；移除旧脚本或退役断言，同时保留执行侧DeviceShellPolicy测试。

### D-BUILD-01 · P0 · open · 提交中的 runtime-descriptor.json 与受管输入不一致，--check 在 Linux 构建必失败

当前153原始字节描述符通过；Git LF检出仍改变受管JS等输入。实际检测404份tracked文本含CRLF。二进制必须排除归一化；通过规范化实际文本后重新生成所有证明修复，不能只让哈希忽略换行。

处置：当前153原始字节描述符通过；Git LF检出仍改变受管JS等输入。实际检测404份tracked文本含CRLF。二进制必须排除归一化；通过规范化实际文本后重新生成所有证明修复，不能只让哈希忽略换行。

### D-TEST-22 · P3 · open · 自检 write/会话补丁仍只认 DSHA_L2S_FIX*，现行运行时用 DSHA_ATOMIC_PUBLISH_V1 必报 FAIL

当前FS实际使用atomic-publish；旧selftest未部署，按死资产处理，不能降级现行原子发布。

处置：当前FS实际使用atomic-publish；旧selftest未部署，按死资产处理，不能降级现行原子发布。

### D-TEST-23 · P2 · open · 三处单测断言名不副实：恒真断言、只查首尾引号、未断言脱敏

ProcessTerminationTest.ignoredGracefulSignalEscalatesOnlyForPassedProcess 的 unrelated 从未传给 stop()，assertTrue(unrelated.alive) 恒真；ShellQuoteTest.roundTripOnPluginLikeValue 只断言首尾为单引号，不验证转义与还原；StartupHistoryStoreTest L14 传入 reason "token=secret-value"，但不断言落盘内容已脱敏（主代码 StartupHistoryStore.java:32 有 redact）。

处置：ProcessTermination：构造“同 UID 无关进程”并经与被停进程相同的选择路径传入，断言其不被 kill；ShellQuote：用 /bin/sh -c 'printf %s '+quoted 还原后 assertEquals 原值（或纯 Java 解析器）；StartupHistoryStore：读回记录文件断言不含 secret-value。

### D-TEST-24 · P2 · open · backup 单测共用的 JvmBackupFileSystem 与生产实现差异大，防御分支未被覆盖

153新Linux父别名夹具已经执行8边界证明，JVM测试FS仍伪device/mode；应读取实际unix属性并保留Windows可靠回退，再增故障注入FS，不能将宿主夹具宣传为Android验证。

处置：153新Linux父别名夹具已经执行8边界证明，JVM测试FS仍伪device/mode；应读取实际unix属性并保留Windows可靠回退，再增故障注入FS，不能将宿主夹具宣传为Android验证。

### D-BUILD-02 · P1 · open · 离线 rootfs 构建脚本复制已不存在的插件目录，整条旧构建链失修

ci-make-offline-bundle.sh:91-95 cp -a app/src/main/assets/{device-shell-guide,task-notifier,status-overlay,web-mobile}，这四个目录在 build150 不存在（实际为 assets/builtin-plugins/dsh-*，共 7 个目录）；脚本 set -euo pipefail，cp 失败即中止。provision-builtin-plugins.sh 只预置 4 个插件，不含 dsh-computer-use-android/dsh-auto-review/dsh-tool-vscreen/dsh-app-integration。唯一调用方是不会被 GitHub 执行的 scripts/ci/android-build.yml:34。

处置：先确认 offline-rootfs.bin 当前由哪个脚本生成（tools/build-standard-runtime.py 等）。若此链路已被取代：按 D-LEGACY-01 删除 scripts/ci-make-offline-bundle.sh、provision-builtin-plugins.sh、offline-provision.sh、make-offline-bundle.sh、scripts/ci/；若仍在用：改为从 register-builtin-plugins.py DEFAULT_BUILTINS 与 assets/builtin-plugins/ 读取插件清单，不再写死目录名，并纳入 CI package 阶段实跑。

### D-REPRO-01 · P2 · open · 入库原生产物 libdsha-session.so 只能用 Windows 本机路径重建；dns-compat-fixture.c 无构建脚本

build.ps1 默认 SDK 为 F:/DSHA/_toolchains/android-sdk，硬编码 NDK 26.3.11579264 的 prebuilt/windows-x86_64 与 clang.exe，输出直接覆盖 jniLibs 下的入库 .so；runtime-descriptor.json:13 只记录该 .so 的 sha256，没有任何检查从 session-launcher.c 重建并比对。tools/dns-compat-fixture.c 全仓无编译或注入引用。

处置：把 build.ps1 改写为跨平台脚本（Python 或 sh，NDK 路径取 ANDROID_NDK_HOME，版本锁定 26.3.11579264），在 CI package 阶段于 Linux 重建 libdsha-session.so 并与入库文件比对 sha256（必要时加 -ffile-prefix-map 等确保可复现）；dns-compat-fixture.c 增加同类构建脚本并接入 test-dns-compat，或删除。

### D-TEST-26 · P1 · open · run-unit-tests.py 只编译 *Test.java，6 个测试辅助类缺失导致全量编译失败

L191 只收集 src/test/java 下 *Test.java；src/test 中的 JvmBackupFileSystem.java 与 ConfigResetCrashProcess/ConfigurationCrashProcess/EnvironmentCrashProcess/HostRuntimeCrashProcess/HostTransactionCrashProcess 共 6 个辅助类不参与编译，被多个测试引用（如 AutomaticBackupPrunerTest:13），全量运行时 javac cannot find symbol，L145-149 die。失败路径不清理临时目录（L215 rmtree 只在成功末尾执行）。

处置：L191 改为收集 src/test/java 下全部 *.java 编译，只把 *Test.java 作为待运行类；用 try/finally（或 tempfile.TemporaryDirectory）保证清理。长期以 gradle testDebugUnitTest 为准，run-unit-tests.py 仅作无 AGP 环境的后备。

### D-TEST-27 · P2 · open · Agent 预设补丁测试不校验 dshVersion，生产对 0.2.0-rc.2 静默跳过；测试未接入门禁

agent-preset-patch明确retiredFor rc2；上游已接管入口，禁止重新套用rc1补丁。清理调用与死测试时必须保留历史格式证据的可读兼容。

处置：agent-preset-patch明确retiredFor rc2；上游已接管入口，禁止重新套用rc1补丁。清理调用与死测试时必须保留历史格式证据的可读兼容。

### D-TEST-28 · P3 · open · test-alpha1-startup.mjs 在干净检出下 ENOENT，属陈旧一次性测试

L6 fs.mkdtempSync(path.resolve('app/build/alpha1-016-intake/observer-fixture-'))，未先创建父目录（mkdtemp 不递归），干净检出必然 ENOENT；临时目录不清理。test-alpha2-upgrade.mjs 依赖 app/build/release136 下旧运行时。两者无调用方。

处置：按 D-ARCH-07/D-LEGACY-01 决策（只支持 build147 起升级）删除 test-alpha1-startup.mjs 与 test-alpha2-upgrade.mjs；若 startup-observer 仍需覆盖，在新测试中用 os.tmpdir() mkdtemp 并 finally 删除。

### D-TEST-29 · P3 · open · test-backup-device.py 硬编码 Windows adb 路径，并在用户真实 rootfs 留下测试文件

L9 ADB 固定为 F:\DSHA\_toolchains\android-sdk\platform-tools\adb.exe；L12/L22 在 com.dsh.client 的真实 files/linux/ubuntu/root/.dsha-opt-{backup|plugin}-check 建目录，L28-34 push 到 /data/local/tmp/dsha-opt-* 再复制进去；全文件（51 行）无任何 rm/清理。

处置：ADB 改为 shutil.which('adb') 或 --adb/ANDROID_HOME 参数；夹具目录加随机后缀并在 try/finally 中删除 OWNED 目录与 /data/local/tmp/dsha-opt-* 文件；若已被其他门禁取代则删除该脚本。

### D-TEST-30 · P2 · open · 多个 Node 测试绕过 testRuntime 的版本/指纹校验，默认指向过时运行时

test-conversation-materialized.mjs:7 默认 app/build/locked-dsh-runtime-rc1；test-chat-render-performance.mjs:6 直读 test-runtimes/current.json 的 raw，且 L46-49 errors 恒为空数组、无性能阈值断言；二者均不经 testRuntime（test-runtime-fixture.mjs:8-20 会校验 dsh 版本与 proof 指纹）。同型：test-issue67-startup.mjs:7 硬编码 app/build/rc2-20260911/locked-runtime，且被 verify-stability.py:206 无参调用——门禁实际在 0.1.x 旧运行时上验证 persona 补丁（dshVersion=0.2.0-rc.2）；test-queue-steer、test-plugin-states、test-profile-settings-cli、rc1-browser-fixture 等同样绕过。

处置：所有 Node 测试统一通过 testRuntime(kind) 获取运行时目录，删除硬编码历史路径与直读 current.json；test-chat-render-performance 增加耗时阈值断言并收集真实 console/page 错误到 errors；verify-stability.py:206 为 issue67 显式传 DSHA_TEST_RUNTIME 或由 testRuntime 选择。

### D-TEST-31 · P1 · open · 约 23 个回归测试/夹具未接入任何测试入口，只能手动运行

本轮抽查 test-mobile-delete、test-mobile-gesture-guard、test-notification-actions、test-office-fonts、test-pdf-compat、test-device-shell-policy、overlay-bridge-test、test-queue-steer：grep 源码（排除 docs/audits）除自身外零引用；verify-stability.py、verify-plugin-upgrade-gate.py、run-unit-tests.py、app/build.gradle 均不调用。其中包含设备命令策略（短信/系统目录保护）、DNS 回落、应急恢复资产等安全/可用性关键回归。

处置：建立单一测试清单（如 tools/test-manifest.json：名称、命令、所需夹具、阶段 fast/package/device），由一个聚合入口（tools/run-host-tests.py）执行并在 CI fast 阶段运行；每个测试先修到可在干净检出通过（依赖 D-TEST-30/36/39 等），确无价值或依赖已退役版本的按 D-LEGACY-01 删除。新增门禁：tools/ 下 test-*.{py,mjs} 必须出现在清单中，否则 CI 失败。

### D-TEST-32 · P3 · open · test-native-plugin-manager.mjs 在 app/build 创建临时目录不清理，干净检出下 ENOENT

L31 fs.mkdtempSync(path.resolve('app/build/native-plugin-policy-'))，未先确保 app/build 存在；L52-55 finally 只恢复环境变量，不删 sdk/profile 临时目录。同批 test-plugin-resolution-order.mjs:54-56 有清理。

处置：改为 fs.mkdtempSync(path.join(os.tmpdir(), 'native-plugin-policy-'))，在 finally 中 fs.rmSync(directory, {recursive:true, force:true})。

### D-TEST-33 · P3 · open · test-plugin-transactions.py 夹具固定在仓库 app/build/round2-audit 且从不清理

setUp（L62）把根目录设为 ROOT/app/build/round2-audit/b/plugin-transaction-tests/<uuid>；tearDown（L65-66）为 pass，注释称刻意保留强杀日志与合成文件。每个子测试留下一棵插件树、事务目录与子进程日志。

处置：夹具改到 tempfile.mkdtemp()，默认在 tearDown 删除；仅在测试失败或设置 DSHA_KEEP_FIXTURES=1 时保留并打印路径；去掉 round2-audit 历史命名。

### D-TEST-35 · P2 · open · test-restore-merge-wsdir.py 在宿主根目录 / 下建测试目录，且相对路径断言依赖 cwd

用例1（L53）与用例3（L73-74）的目标为 /dsha_unit_ws_test_<时间戳>，直接在宿主真实根目录创建（L59/L79 用 rmtree 清理）；非 root 主机在 L74 os.makedirs 抛 PermissionError，root 主机在中途失败时污染 /。L87 以 os.path.isdir("relative") 判断相对路径未创建，依赖当前 cwd。被测 ensure_workspace_dirs（restore-merge.py:1069-1075）对任意绝对路径 makedirs（只要求以 / 开头），这一边界没有测试覆盖。

处置：ensure_workspace_dirs 增加可注入的 root 前缀（生产传 rootfs 根，测试传 tempdir），并限制只在允许的工作区前缀（如 /root/ 或 Documents 映射）下创建，越界路径拒绝；测试改为在 tempdir 内验证，并新增越界路径（/etc/x、/root/../etc）被拒的用例；L87 改为检查 tempdir 内的相对路径。

### D-TEST-36 · P3 · open · testRuntime 的 environment 默认值两支相同，managed 与 raw 共用 DSHA_TEST_RUNTIME

L8 environment=kind==='raw'?'DSHA_TEST_RUNTIME':'DSHA_TEST_RUNTIME'，两支相同。testRuntime('managed')（test-office-fonts.mjs:3、test-native-plugin-manager.mjs:8）会读取为 raw 设定的 DSHA_TEST_RUNTIME。有 proof 文件时 L21 以“wrong fixture kind”报错；无 proof 且 DSHA_ALLOW_UNVERIFIED_RUNTIME=1 时（L15-17）直接返回错误类型的目录。

处置：非 raw 分支改为 'DSHA_TEST_MANAGED_RUNTIME'（或按 kind 生成 DSHA_TEST_<KIND>_RUNTIME），并同步文档/门禁脚本中的环境变量。

### D-TEST-37 · P3 · open · device-backup-audit.init.gradle 的源码 String.replace 补丁不命中时静默跳过

旧额外审计APK入口被显式门禁禁止；本轮不启用、不生成。可删除危险构建入口但保留有价值历史源码/报告。

处置：旧额外审计APK入口被显式门禁禁止；本轮不启用、不生成。可删除危险构建入口但保留有价值历史源码/报告。

### D-TEST-38 · P3 · open · control-functional-audit.py 参数校验用 assert、缺 --script 时崩溃、ADB 路径写死

L10 ADB 固定为 F:\DSHA\...\adb.exe，无参数覆盖；L16 用 assert 校验 id（python -O 下失效），id 随后拼入 run-as 内层 sh 命令（L31 cat folder+id.json）；未传 --script 且未 --finish 时 L21 args.script.read_text 抛 AttributeError；所有调用共用单一 command.json，并发会互相覆盖。

处置：id 校验改为 if not re.fullmatch(...): parser.error(...)；--script 与 --finish 用 argparse 互斥组且必选其一；ADB 取 shutil.which('adb') 或 --adb；拼接处统一 shlex.quote；command.json 按 id 命名。若该调试入口已无使用方，按 D-LEGACY-01 删除。

### D-TEST-39 · P2 · open · test-dsha-ui-regressions.py 与源码漂移，系统语言用例必然 ERROR；未接入任何入口

153实际运行32项、1项失败，旧SystemLanguage源码文字断言漂移；现行UiLanguagePreferenceTest已有真实锁存/并发边界测试，因此应修测试入口和错误断言，不能为迎合旧断言回退生产锁存实现。

处置：把 SystemLanguage 早期锁存改为 JVM 单测（调用 initializeFromSystemTag 后改 Locale.setDefault，断言 tag() 不变）；DshaApp 调用顺序可保留源码断言但改用正则匹配 initialize\(；resetTokenAfterRestore 断言替换为对恢复流程的行为测试（D-BUG-03 修复时一并加）。修好后接入 D-TEST-31 的测试清单。

### D-TEST-41 · P2 · open · 两个 APK 发布核验脚本全部用 assert 实现，python -O/PYTHONOPTIMIZE 下门禁空转

verify-dsh-upgrade-apk.py 有 35 处、verify-recovery-apk.py 有 16 处 assert 语句承担全部校验（如 verify-recovery-apk.py:22-28 的 overlay 数量/路径/哈希）。调用方 verify-stability.py:239/257 与 verify-plugin-upgrade-gate.py:139-140 用 -B 不带 -O；但 verify-plugin-upgrade-gate.py:22 复制 os.environ，PYTHONOPTIMIZE 会被继承。

处置：把 assert cond, code 统一替换为 require(cond, code)（失败 raise SystemExit/自定义异常）；调用方子进程环境中显式删除 PYTHONOPTIMIZE；同类改动覆盖 tools 下其他门禁脚本（可加 lint：tools/verify-*.py 禁止 assert 语句）。

### D-DOC-02 · P3 · duplicate · ProotBootstrap 注释仍写 dsh 1.2-alpha 与“四个内置插件”

L300 段标题写“dsh 1.2-alpha 在 Android proot 下的兼容”，L380/L387-388 称注册“rootfs 烘焙的四个内置插件”；实际内置 dsh 为 0.2.0-rc.2（app/build.gradle:22），BuiltinPlugins.DEFAULT_BUILTINS 为 7 个，加 dsh-app-integration 共 8 个签名内置。

处置：把 L300 改为不带版本号的“dsh 运行补丁（Android proot 兼容）”；L380/L387-388 改为“内置插件（名单以 BuiltinPlugins.DEFAULT_BUILTINS 与 dsha-builtin.txt 为准）”，不再写数量。与 D-DOC-08、D-DOC-14 同一轮改。

### D-DEAD-01 · P2 · open · Java/脚本层死代码与死接口累积（含会绕过策略的旧确认路径）

逐项核对：AppBridge 接口全库无实现/引用；ProotBootstrap.uninstall()、TarGzipExtractor.lastSkipped 无调用方；HttpShellService.confirmEnabled()/awaitConfirm() 为 private 且无调用；AdbBridge.grantSecureSettings 的局部变量 pkg="com.dsh.client" 未使用；allow-root-shell/confirm-shell-* 标记只有 tools/test-adb-flow.py 读取；DangerShellGuard 无引用；EnvironmentMaintenance.cleanupCompleted 方法体为空但 Javadoc 称“回收已提交事务”，HarnessController:396 仍调用；validateRuntime(proot) 单参私有重载无调用（调用方均传 false）；deleteTree 在 main 外仅 debug 审计插桩调用；WorkspaceFragment.doRestore 无调用；WelcomeActivity.pages 未用；OverlayStyleDialog 仅被 debug LayoutAuditInstrumentation 使用，ConfigFragment.showOverlayStyleDialog 实际打开 OverlayFragment；VirtualScreenCore 中 tap/swipe/key 已在前面注入路径返回，argv switch 里这三个 case 不可达，且 swipe 时长下限两处不一致（0 vs 50）；inBundlesSection 只被单测调用；adb-shell.py 的 READONLY_CMDS/is_readonly_cmd/request_confirm 主流程不调用；agent-preset-patch.json 因 dshVersion 闸被跳过，相关 js/css 成死资产；adb-pair.py:113 写死 com.dsh.client。

处置：按 D-ARCH-07/D-LEGACY-01 决策（无部署方的死代码删除）一次清理：删除 AppBridge、DangerShellGuard、ProotBootstrap.uninstall、lastSkipped、confirmEnabled/awaitConfirm、validateRuntime 单参重载、doRestore、WelcomeActivity.pages、OverlayStyleDialog（同步删 LayoutAuditInstrumentation 中对应用例）、VirtualScreenCore argv 分支中 tap/swipe/key 三个 case、adb-shell.py 的 READONLY_CMDS/is_readonly_cmd/request_confirm（同步改 tools/test-adb-flow.py、selftest.py:311，见 D-TEST-21）、allow-root-shell/confirm-shell-* 标记写入；cleanupCompleted 要么删除调用要么实现真实回收并修 Javadoc；inBundlesSection 若只为测试保留则标注 @VisibleForTesting 或删；adb-pair.py 改为由调用方传 --package（AdbBridge 用 context.getPackageName()），删除未用 pkg 变量；agent-preset 资产随 D-PATCH-01 的退役结论一并删除，test-agent-preset-switch.mjs 删除或接入 verify-stability.py。

### D-DOC-03 · P3 · open · 多处孤立/叠加 Javadoc，注释挂在错误的元素上

脚本扫描 main/java 中“/** */ 后紧跟另一个 /**”的情形共 9 处，例如 HttpShellService.java:401“校验查询串/头中的 token”后紧跟 currentToken() 的 Javadoc，实际修饰的是 currentToken；L742“/app/toast”注释后紧跟屏幕操作注释和一段 // 分隔块。原 token 校验方法的注释已与方法分离。

处置：逐处把孤立 Javadoc 移回所描述的方法或删除；HttpShellService:401 的说明移到真正的 token 校验方法上，并同步删除 L403-404 对 webserver-auth-patch.sh 的过时描述（见 D-DEAD-09③）。CI 加 javadoc -Xdoclint 或 checkstyle 的 DanglingJavadoc 检查。

### D-DOC-04 · P2 · open · 无障碍服务类注释声称 packageNames 限定设置应用，与配置不符

类注释“隐私边界三层”第 1 层称清单 android:packageNames 限定 com.android.settings、别的应用连事件都收不到；实际 accessibility_service_config.xml 不设 packageNames（L3 注释明确说不限定），事件类型 typeWindowStateChanged|typeWindowContentChanged、canRetrieveWindowContent/canTakeScreenshot 均开启，系统会投递全部应用的事件。xml 注释本身与配置一致。

处置：重写 DshaAccessibilityService 类注释：说明服务接收所有应用的窗口事件，隐私边界为①onAccessibilityEvent 除配对码窗口外直接返回②读屏/截屏仅在 /app/ui/* 调用时发生③截屏文件落盘位置与清理策略（与 D-PRIV-04 一起修正“不落盘”文案）。同时核对应用内隐私说明与商店说明不再引用“只限设置应用”。

### D-DOC-05 · P3 · open · PluginNavigation 注释称安装经原生预览确认，实际外部来源无确认

不恢复原生审阅。准确说明链接解析、实际摘要和事务校验后自动提交启用；保留用户禁用/安全模式意图。

处置：不恢复原生审阅。准确说明链接解析、实际摘要和事务校验后自动提交启用；保留用户禁用/安全模式意图。

### D-DOC-06 · P3 · open · 更新日志硬编码在 UpdateActivity 的 Java 字符串里

update_changelog 点击回调把中英两份更新说明以 UiText.choose("DSHA "+VERSION_NAME+...) 字符串字面量写在单行（该行 1185 字符，含 12 个 \n）；与 docs/releases 下的发布说明是两份独立文本。

处置：把更新日志移到资源：res/raw/changelog.md 与 res/raw-en/changelog.md（或 assets/changelog/<locale>.md），UpdateActivity 只读取并展示；发版脚本从 docs/releases/<版本>.md 摘要生成该文件，或优先使用更新清单的 notes 字段，本地文件作兜底。

### D-DOC-07 · P3 · open · SensitiveData 类注释仍写“骨架版、待回填”

类注释称“骨架版只覆盖最关键的 API key 环境变量形态；完整版按原 SensitiveData 回填”；实际 redact 已有 7 条替换规则：PEM 私钥、Authorization/Cookie 头、查询串 token/api_key 等、KEY=value 形态、URL userinfo、Bearer、sk-/gh*_/github_pat_ 令牌。

处置：把类注释改为列出当前覆盖的 7 类形态及已知不覆盖项（如任意自定义头、JSON 中非常规键名），并指向 SensitiveDataTest 作为规则清单的权威来源。

### D-DOC-08 · P3 · open · 内置插件数量在注释里写成“四个”，实际 8 个

BuiltinPlugins 类注释称“rootfs 烘焙的 dsh-device-shell-guide 等四个”；DEFAULT_BUILTINS 列 7 个（device-shell-guide、task-notifier、status-overlay、web-mobile、computer-use-android、auto-review、tool-vscreen），再加 dsh-app-integration 共 8 个签名内置。ProotBootstrap 与 register-builtin-plugins.py 注释有同样过时说法。

处置：三处注释统一改为不写数量，指向唯一名单来源（BuiltinPlugins.DEFAULT_BUILTINS + SYSTEM 插件 / dsha-builtin.txt）；名单本身的单一来源问题按 D-DUP-16 处理。

### D-DOC-09 · P2 · open · 官网分发的 Agent Skill 版本号与通道描述过时

两个技能写死“DSHA 1.2.0-rc1.1 / dsh 0.1.2-rc.1”，前置条件只要求启用 ADB 并配对，身份预期为 uid=2000(shell)；实际 adb-shell.py:475-476 request_native_execution 由原生层先选 root/Shizuku，只在明确 ADB 计划时连接 ADB，身份可能是 root 或 Shizuku uid。build.mjs 下载页/归档名/FAQ 及 testedDsh/compatibleDsha 缺省值、install-page.mjs:9“DSHA rc1.3 或更新”仍是旧版本号体系。

处置：重写两个 SKILL.md：去掉写死版本，前置条件改为“DSHA 设备通道就绪（root/Shizuku/ADB 任一，原生自动选择）”，身份校验改为“用 id 核验实际身份，不假设 uid=2000”，截图示例补清理步骤；build.mjs/install-page.mjs 的版本字段改从 app/build.gradle 或 website/data/catalog.mjs 单一来源读取；README 验证段同步。与 D-DUP-21 合并为单一技能源后由构建复制到 website。

### D-DOC-10 · P2 · open · README 中英文停在 0.1.7-rc2/147，历史段落操作指引失效且互相矛盾

README准确描述公开147版本，但没有区分本地153源码；保留公开下载及用户历史主页说明，增加本地源码状态与准确构建指引，不伪造新版已经发布。

处置：README准确描述公开147版本，但没有区分本地153源码；保留公开下载及用户历史主页说明，增加本地源码状态与准确构建指引，不伪造新版已经发布。

### D-DOC-11 · P2 · open · THIRD_PARTY_NOTICES 的移动端插件与 DSH 依赖声明整段过时，“未修改”不成立

NOTICES 写 dsh-mobile-nav v2.1.1、包名 @dsh-external/dsh-mobile-nav、许可位于 app/src/main/assets/mobile-nav/LICENSE（目录不存在）、“上游构建产物未作任何修改”并列 5 个 sha256（client.js 6c6ee969…）。实际内置 builtin-plugins/dsh-web-mobile 3.0.3，tools/apply-mobile-client-patches.mjs 对上游 client.js 做十余处替换与 CSS 注入，现内置 lib/client.js sha256 为 3e5568a4…。L5-7 仍写 DSH 0.1.6-alpha.2 与 libreoffice-kit@0.0.1/-wasm@0.0.1，锁文件为 0.1.2/0.1.1。migrateLegacyMobileAdapt 在 app/src 无匹配。

处置：重写该节：组件名 dsh-web-mobile 3.0.3、上游 commit a094288883b3、上游 clientSha256（取 package.json dshaUpstream），说明“由 tools/apply-mobile-client-patches.mjs 打本地补丁”，并给出补丁后文件摘要；许可路径改为 builtin-plugins/dsh-web-mobile/LICENSE（确认存在）；DSH 依赖小节按 tools/dsh-runtime/package-lock.json 更新为 0.2.0-rc.2 及 libreoffice-kit 0.1.2/-wasm 0.1.1。最好由脚本从 package.json/锁文件生成 NOTICES 片段。

### D-DOC-12 · P2 · open · AGENTS.md 新旧规则叠加，自相矛盾且多处过时

附件要求只支持147起升级、恢复外链审阅与当前兼容/开放安装要求冲突；只收敛矛盾陈旧规范，保持历史读取。

处置：附件要求只支持147起升级、恢复外链审阅与当前兼容/开放安装要求冲突；只收敛矛盾陈旧规范，保持历史读取。

### D-DOC-13 · P2 · open · agent-skills/device-shell 技能多处事实错误（/exec 缺 token、Termux 通道不存在、推荐裸 adb）

技能以 adb -s <serial> shell 为主要用法（L15-33），L46 的 curl 127.0.0.1:<port>/exec 示例不带 token，照抄必得 [UNAUTHORIZED]；“Termux 通道”描述的 ~/dsh-bin 包装 pm/sm/settings + termux-dialog 确认在现行实现中不存在；Notes 称 ADB shell 为 uid=2000 且段落重复。内置引导插件 index.js:48 明确禁止用裸 adb。README.md:314 指导用户 cp -r agent-skills/device-shell 直接使用。

处置：与 D-DUP-21 一并处理：保留一个技能源（建议 website/src/skills 中文版修正后作为唯一源，agent-skills 由构建复制或删除），内容改为 /root/dsh-bin/adb-shell 与 /app/* 接口、token 通过 X-Token 头（配合 D-SEC-03）、通道自动选择、以 id 核验身份；删除 Termux 通道段与重复 Notes；README.md:314 改为指向修正后的技能。

### D-DEAD-02 · P3 · open · backup-prepare.py、restore-merge.py 无调用方仍打进 APK，文案与现行引擎相反

现行宿主v5不调用backup-prepare/restore-merge。退役未部署脚本可行，已有旧归档读取与NativeBackup恢复能力必须保留。

处置：现行宿主v5不调用backup-prepare/restore-merge。退役未部署脚本可行，已有旧归档读取与NativeBackup恢复能力必须保留。

### D-DOC-14 · P3 · duplicate · register-builtin-plugins.py 注释仍称四个内置插件，且有不可达 return

docstring L5-6 只列 4 个插件，L56 注释“认出这四个内置插件”，而 L57-66 DEFAULT_BUILTINS 为 8 个；L384 return found 之后 L385 还有 return None，不可达。

处置：docstring 与 L56 注释去掉数量与名单，指向 DEFAULT_BUILTINS；删除 L385 return None。

### D-DEAD-03 · P2 · open · 十余个旧自愈/迁移脚本无部署方仍随 APK 打包（根因：assets 整目录 rglob）

整目录rglob确实打包死脚本；采用独立部署清单/用途白名单并核对全部APK读取，不能只用正则引用粗略裁掉许可或动态加载资源。

处置：整目录rglob确实打包死脚本；采用独立部署清单/用途白名单并核对全部APK读取，不能只用正则引用粗略裁掉许可或动态加载资源。

### D-DEAD-04 · P3 · open · plugin-network 换源重试补 --frozen-lockfile 的分支不可达

L129-130 条件要求 argv 为 pnpm、存在 pnpm-lock.yaml 且 args 中没有 --no-frozen-lockfile；唯一走 package_command 的 pnpm 调用 plugin-dependencies.py:289 总是带 --no-frozen-lockfile，因此条件恒假；L131 过滤 --no-frozen-lockfile 在该条件下也是空操作。

处置：删除 L129-133 分支，在 package_command 处加注释说明“依赖安装按用户策略不冻结锁文件”；若将来要恢复锁定安装，应在 plugin-dependencies.py 调用侧显式传参而非在网络层改写参数。

### D-DEAD-05 · P3 · open · plugin-manager.resolve_plugin_dir 末尾重复检查永不命中

L495-497 已检查 node_modules/<name>/package.json 并返回；L503-505 重复同一条件，中间分支不修改 path 或文件系统，故不可达。

处置：删除 L503-505（保留 return None）。

### D-DEAD-06 · P3 · open · 旧危险命令守卫 rootfs-confirm-install.sh 与 DangerShellGuard 均无部署/调用

rootfs-confirm-install.sh 在 app/src 与 tools 中零部署（仅 scripts/ci/android-build.yml:27 用作离线 rootfs 缓存 key 的 hashFiles 输入）；DangerShellGuard 全库无引用，其注释仍描述 dsh-guard.sh 判据。现行 /confirm 只对 DeviceShellPolicy 判为 READ 的命令返回 YES（HttpShellService:563-567），即便旧守卫复活，rm 等包装也会被一律拒绝。

处置：删除 rootfs-confirm-install.sh、DangerShellGuard.java，并从 android-build.yml 缓存 key 中移除（若该模板按 D-CI-01 重写则一并处理）；安全文档统一描述 DeviceShellPolicy（见 D-DOC-15）。

### D-DOC-15 · P2 · open · 英文安全模型仍称 dsh-guard.sh 拦截危险命令，与中文版和实现不符

英文版 L97-103 称 dsh-guard.sh 拦截 rm -rf、块设备、ADB 卸载并交给用户决定；中文版 L105-114 已改为原生 DeviceShellPolicy 解析命令、拦截返回 [POLICY_BLOCKED]/126、/confirm 只对只读查询返回 YES。selftest.check_guard 仍以 /root/dsh-bin/.version 与 dsh-confirm.sh 判定守卫，现行运行时不生成这些文件。

处置：按中文版重写 security-model.en.md 对应段落；selftest.py 随 D-DEAD-03 删除（若保留则 check_guard 改为调用 3090 的策略自检接口）。增加中英文档同步检查（标题结构一致）。

### D-DEAD-07 · P3 · open · 4 个 drawable、1 个布局与 install_crash 按钮无引用仍随 APK 打包

脚本复核：4 个 drawable 在 app/src 全部源集与 tools 中无引用（WelcomeActivity 已改用 bg_ui2_dot）；dialog_remind_backup 仅出现在 androidTest ThemeInstrumentation:111 的布局测量列表；fragment_install.xml:128 的 install_crash 按钮（visibility=gone）无任何 R.id 绑定。build.gradle:76 minifyEnabled false，未开 shrinkResources。

处置：删除 4 个 drawable、dialog_remind_backup.xml（同步从 ThemeInstrumentation 列表移除）、fragment_install.xml 中 install_crash 按钮及其仅被它使用的字符串 ui_m0152（先核实无其他引用）。与 D-DEAD-10 一起由 lint UnusedResources 门禁兜底。

### D-DEAD-08 · P3 · duplicate · 21 条中英字符串在代码与布局中无引用

脚本复核：refresh_strings.xml 9 条、dns_strings.xml 5 条、alpha1_strings.xml 5 条、picture_in_picture_strings.xml 2 条，共 21 条在全部源集与 tools 中无 R.string/@string/字面键名引用；values-en 有同名副本。

处置：并入 D-DEAD-10 的资源清理：删除中英两份未用键，剩余在用键按功能重新归档。

### D-DOC-16 · P3 · open · 历史升级报告/低版说明/ROADMAP 无历史标注，断言与现状不符

历史报告加时间/版本横幅而不改写旧验收事实。

处置：历史报告加时间/版本横幅而不改写旧验收事实。

### D-DOC-17 · P1 · open · plugins.md 称不执行安装脚本，实际已开启全部依赖生命周期脚本

按当前开放依赖政策修正文档；依赖生命周期与插件自身source钩子分别如实记录，不夸大所有钩子都已执行。

处置：按当前开放依赖政策修正文档；依赖生命周期与插件自身source钩子分别如实记录，不夸大所有钩子都已执行。

### D-DEAD-09 · P2 · open · 旧 webui/webserver 补丁脚本无部署方；startup-*.py 仍部署但无执行方；HttpShellService 注释引用旧鉴权补丁

当前verify-plugin-upgrade-gate已调用test-startup-recovery.py，附件所称测试无入口不准确；生产Python checkpoint/recovery部署与执行职责仍需要退役审核，宿主历史v1读取必须保留。

处置：当前verify-plugin-upgrade-gate已调用test-startup-recovery.py，附件所称测试无入口不准确；生产Python checkpoint/recovery部署与执行职责仍需要退役审核，宿主历史v1读取必须保留。

### D-DEAD-10 · P3 · open · 大量字符串/样式/尺寸/颜色资源无引用，未开 shrinkResources 与 lint 门禁

本次正则复核（R.string/@string/字面键名，含 values 内交叉引用）：ui_strings.xml 216 条中 128 条无引用、ui_iteration.xml 153 条中 29 条、web_port.xml 1/1、refresh_strings.xml 9/14、dns_strings.xml 5/8、alpha1_strings.xml 5/7、picture_in_picture_strings.xml 2/9；部分未用文案已与现行为矛盾（ui_m0040“默认最新 RC”、ui2_review_hint 与自动启用相反）。styles/dimens/colors 未用项沿用台账结论未逐一复核。代码中存在 getIdentifier 调用（ThemeInstrumentation、LayoutPreviewActivity，均查 layout），不影响字符串判定。build.gradle 未开 shrinkResources，无 lint UnusedResources 门禁。

处置：在 CI 跑 ./gradlew lint 取 UnusedResources 清单，按清单批量删除中英两份未用资源（包括 D-DEAD-07、D-DEAD-08 列项）；app/build.gradle release 构建开启 shrinkResources（需同时开 minifyEnabled，或先只做 lint 门禁避免引入混淆风险）；lint.xml 将 UnusedResources 设为 error。

### D-DOC-18 · P3 · open · android-standard.md 与 community-standard-feedback.md 以“当前”口吻描述旧版本

android-standard.md 称“当前预览版为 1.2.0-rc1.2（versionCode 111）”并链接 rc1.2 发布说明；community-standard-feedback.md:28 称“Web UI 由 GeckoView 呈现、Android 8+”。现行为 build150/内置 dsh 0.2.0-rc.2，标准版 Android 11+ 系统 WebView。

处置：android-standard.md 去掉“当前版本”句，改为指向 README/发布页的版本来源；community-standard-feedback.md 加历史横幅或改写 L28 为“标准版使用系统 WebView、Android 11+；Low 版 GeckoView、Android 6+”。与 D-DOC-16 同一批处理。

### D-DOC-19 · P2 · open · 两份发布说明的 Standard APK SHA-256 只有 63 位

当前文件63位摘要可静态确认，回填须取可验证原包或官方sidecar；拿不到证据则明确录入错误，禁止补猜缺失字符。

处置：当前文件63位摘要可静态确认，回填须取可验证原包或官方sidecar；拿不到证据则明确录入错误，禁止补猜缺失字符。

### D-DOC-20 · P2 · open · 发布构建与统一门禁在 Windows 主机执行，Linux 专属用例被跳过

Linux CI配置和宿主Linux夹具可本轮实现；设备矩阵、线上连续5次CI及真机证据不能由本地检查推导。Windows开发保持支持，生成物实际字节必须跨平台一致。

处置：Linux CI配置和宿主Linux夹具可本轮实现；设备矩阵、线上连续5次CI及真机证据不能由本地检查推导。Windows开发保持支持，生成物实际字节必须跨平台一致。

### D-DOC-21 · P2 · open · 接手指南的脚本、类名、行数、CI 描述大面积失配

指南要求每轮运行 tools/pure-logic-test.sh、archive-e2e-test.sh、restore-scope-test.sh，及 tools/pack-local.sh——四个文件均不存在；代码地图中的 PluginController、PluginSpec、PublicDirs、ArchiveProbe、PatchToggle 在 app/src 中无对应类；HarnessController 实在 core/ 且 995 行（指南称约 6200 行）；坑 8 称容器以 --kill-on-exit 启动，ContainerRuntime.java:18 注释说明 argv 不带该参数；L299 称推 git tag 触发 CI 构建发布，而 .github/workflows 只有 star-history.yml。

处置：重写接手指南：测试入口改为 python3 tools/run-unit-tests.py / ./gradlew test / tools/verify-stability.py；代码地图由脚本按目录生成或删去行数；坑 8 改为“proroot 不带 --kill-on-exit，停止需按进程组/已核验 PID（参见 D-RT-13 决策：二次确认后仅对已核验身份的本应用 Web 进程 SIGKILL）”；CI 段在 D-CI-01 落地后按真实 workflow 描述。

### D-DEAD-11 · P3 · open · scripts/ 下两个历史构建工具无调用方且锁定旧版本

prepare-dsh-alpha-runtime.sh 锁 DSH 0.1.2-alpha.2/pnpm 11.7.0，只在 ci-make-offline-bundle.sh:5 注释中被提及、无执行方，docs/upgrade-dsh-0.1.5-alpha.1.md:46 已称其为旧流程存档但脚本头无弃用标注；L217 对整个 tar 列表用 '(^|/)(packages/|apps/)' 正则判坏条目，会误伤依赖包内合法目录。generate-update-fixtures.py 无调用方，默认 --version-code 114、输出 app/build/rc13-update-fixtures、build-tools 36.0.0、java.exe/keytool.exe（Windows 专用），对 build150 生成的“valid”样本实为降级包。

处置：按第六节“无部署方的死脚本删除”决策删除 prepare-dsh-alpha-runtime.sh 并修正 ci-make-offline-bundle.sh:5 注释；generate-update-fixtures.py 若仍需要，改为从 app/build.gradle 读取 versionCode、跨平台查找 java/keytool、build-tools 版本取自 gradle 配置，否则删除。

### D-DOC-22 · P2 · open · 离线包预置的 bash 工具描述写死 ADB 唯一通道/uid=2000/无 token 的 /exec，且永不刷新

provision-builtin-plugins.sh 由 ci-make-offline-bundle.sh:89-107 在构建离线 rootfs 时执行，向 /root/.dsh 的 home patch 追加 persistent-bash 工具描述：“adb-shell 唯一可用通道，uid=2000，已配对”“Shizuku 桥备用 curl 127.0.0.1:3090/exec”（无 token）。仅在找不到 dsha-device-guide-bash 标记时追加，App 侧 app/src/main 中没有该标记的刷新逻辑。

处置：删除该 home patch 注入段，设备通道说明统一由内置 dsh-device-shell-guide 插件提供（可随 APK 更新）；若必须保留，文案改为“通道由原生层自动选择，用 id 核验身份；3090 调用需带 X-Token”，并改为带版本号标记（如 dsha-device-guide-bash-v2），由 App 启动时的受管配置迁移替换旧段。注意 ci-make-offline-bundle.sh 本身已过时（D-BUILD-02/D-CI-01），需确认现行离线包是否仍由它生成。

### D-DEAD-12 · P3 · open · tools/TarRepro.java 无调用方且与 TarGzipExtractor 行为漂移

TarRepro 全仓仅自身引用；L42-46 对 PAX 头（x/K）只读取后丢弃、不解析 path（生产实现有 parsePaxPath），L51 直接 new File(dest,name) 无路径穿越校验，也不复刻 safeSymlinkTarget 的绝对→相对转换。

处置：删除 TarRepro.java；需要复现时改为写调用 TarGzipExtractor 的 JVM 单测或小型 main（复用生产代码）。

### D-DEAD-13 · P3 · open · tools/inject-arm64-natives.py 为 rc.1 一次性注入脚本，无调用方

全仓（排除 docs/audits）无引用；L25 PREFIX 指向 dsh 包内 node_modules，与 rc.2 覆盖层布局不符；L54-62 只跳过目录，链接条目经 extractfile 当普通文件处理，且不校验 tgz 完整性。

处置：删除该脚本；原生模块注入以 tools/build-dsh-runtime.py 的锁定覆盖层流程为唯一入口。

### D-DOC-23 · P3 · open · termux-jni README 重编参数与摘要停在旧值，与 build.ps1 和现行库不符

README L34-35 写目标 aarch64-linux-android26、common-page-size=16384，L36 附近给 SHA-256 411e90…；build.ps1:1 默认 MinApi 23、:62 common-page-size=4096；L5 现行哈希 0c6b8775… 与 jniLibs/arm64-v8a/libtermux.so 实测 sha256 一致。重编命令写死 F:/DSHA/_toolchains 路径。

处置：重写“重编命令”段：以 build.ps1 默认参数为准（API 23、max-page-size=16384、common-page-size=4096），只保留 L5 现行哈希，旧哈希移入“历史”小节；NDK 路径改为 $env:ANDROID_NDK_HOME 示例。

### D-DEAD-14 · P3 · open · build-dsh-runtime.py 中 field_anchor/class_replacement 赋值后未使用

L433 field_anchor、L437 class_replacement 只定义不使用；L440 实际用内联字符串替换 tracker 声明，内联版本与 class_replacement 内容不同（不含 Bounded LRU 注释块）。

处置：删除两个未用变量；若需保留说明，把 L440 的内联替换串提为具名变量并只保留一份。

### D-DOC-24 · P3 · open · rc2 审计目录的“当前版”文档与“修复前”版本字节相同

修复前文档重复不是新的架构结论；保留历史，注明当前必须看build154复核报告。

处置：修复前文档重复不是新的架构结论；保留历史，注明当前必须看build154复核报告。

### D-DEAD-15 · P3 · open · pnpm-env-fix.sh 无调用方仍随 APK 打包

grep 'pnpm-env-fix' 仅命中自身（L18 日志路径）与 docs/proroot-experiment-plan.md；RuntimeTools/ProotBootstrap/managed-runtime-inputs.json 均不部署。功能已由插件依赖安装的 --config.package-import-method=copy 取代。脚本改写全局 /root/.npmrc，L50-56 对 pnpm store tmp 无锁 rm -rf。

处置：删除该脚本，docs/proroot-experiment-plan.md 标历史；纳入 D-DEAD-03 的资产部署门禁。

### D-DOC-25 · P1 · open · 官网数据、发布说明与站点测试仍锁定 0.1.7-rc2/build147

网站静态数据确为公开147。本轮同步本地源码构建数据及本地交付版本，未经本轮明确部署动作不声称dsha.cc上线或GitHub Release已更新。

处置：网站静态数据确为公开147。本轮同步本地源码构建数据及本地交付版本，未经本轮明确部署动作不声称dsha.cc上线或GitHub Release已更新。

