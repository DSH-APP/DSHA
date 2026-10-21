# 端口与网络维护

本文对应 build190 的实际源码，供使用、排查和修改端口逻辑时查阅；F10 基线与 F12 新增行为分别标注。
当前应用运行 DSH 0.2.1-rc.2；运行引擎另见[运行引擎](runtime-engines.md)。
DNS 策略另见 [DNS 配置](dns-configuration.md)。
端口、鉴权凭据、Web 运行代次是三个不同字段，不能用其中一个推断另外两个。

## 先辨认是哪一种端口

| 用途 | 当前地址或来源 | 管理位置 |
|---|---|---|
| DSH Web 后端 | `127.0.0.1:<本次实际端口>` | 配置页的自动／手动 Web 端口 |
| DeepSeek OAuth 回调 | 发起登录时页面 origin 的 `/oauth/callback`；由当前 DSH WebServer 临时注册路由，LAN 访问经代理转发 | 官方账号登录状态机 |
| Android 能力桥 | `127.0.0.1:3090`，另尝试 `::1:3090` | `HttpShellService` |
| LAN 网页代理 | `0.0.0.0:3081` | 配置页的局域网访问开关 |
| 无线 ADB 配对 | Android 配对弹窗给出的临时端口 | 设备能力授权／ADB 配对页 |
| 无线 ADB 连接 | Android 无线调试页及发现结果 | ADB 连接管理 |
| 外部电脑的 ADB server | 默认本地 TCP 5037，属于电脑端 ADB 链路 | 不属于 DSH Web 服务 |

3081、3090 是 Web 配置的保留端口，不能选作手动 Web 端口。
`5555` 仅是历史 ADB 连接兜底值，不是现代 Android 无线调试的固定端口。
电脑端 ADB server 与手机上的 adbd 配对／连接服务也是不同端点。
5037 的职责见 [Android 官方 ADB 架构说明](https://developer.android.com/tools/adb#howadbworks)。
DSHA 的设备脚本使用 `adb_shell_wifi` TLS 客户端，不把 Web 端口交给 ADB。
更改 Web 端口不会完成 ADB 配对，也不会把配对端口变成网页地址。

OAuth 不另开一个独立的临时监听端口。锁定的 `@deepseek-ai/dsh-deepseek-account-platform` 使用当前 `webServer.register()` 注册回调，并把登录开始时的 origin 写入本次 `redirect_uri`。如果登录期间停止、重启或换了实际 origin，旧回调地址可能已经失效，应从当前页面重新发起登录；不能改写旧回调的端口、重放旧 code 或绕过 state/PKCE。实现依据为[依赖锁](../tools/dsh-runtime/package-lock.json)及[真实动态端口回调测试](../tools/test-deepseek-native-oauth.mjs)。插件列表阻塞与旧回调失效是不同判断条件，不能仅凭“连接超时”截图确定是哪一种。

相关常量见 [Constants.java](../app/src/main/java/com/deepseekharness/app/util/Constants.java)。
其中保留的 `DSH_WEB_PORT=3080` 是兼容旧配置的数值，不代表当前默认固定监听 3080。

## 自动模式的实际选择顺序

“配置”中的 Web 端口有 `auto` 与 `fixed` 两种策略。
自动模式传给端口选择器的请求值是 `0`。
选择器先看本机针对该请求保存过的成功实际端口：

1. 旧实际端口合法、不是保留端口且本机可绑定时，尝试复用它。
2. 否则向 DSH 传 `--port 0`。
3. 由 DSH 在真正监听时让操作系统分配端口。
4. 读到合格的本次官方鉴权启动行后，才登记实际端口。

因此“自动”不保证每次端口不同，也不保证每次端口相同。
这里没有先随机挑一个号码、关掉探测 socket、再假定该号码仍归自己。
对旧端口的可用性探测也不是监听成功证明，探测之后仍可能发生竞争。
选择器只尝试本机绑定，不查询占用进程，也不向端口占用者发送凭据。

源码入口：

- [WebPortSettings.java](../app/src/main/java/com/deepseekharness/app/util/WebPortSettings.java)：配置策略与迁移。
- [WebPortPolicy.java](../app/src/main/java/com/deepseekharness/app/util/WebPortPolicy.java)：可用性与鉴权端口校验。
- [WebLifecycleController.java](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)：选择、启动和发布本轮结果。

## 手动端口、旧 3080 与备用端口

手动模式接受 1–65535，排除 3081 与 3090。
它保存用户的首选值，不承诺该值一定能监听成功。
启动前若首选端口被占用，先尝试匹配该首选值的本机成功备用端口。
备用也不可用时，选择器返回 `0`，交给 DSH 动态监听。
成功使用备用端口不会覆盖用户保存的首选值。

没有显式 `web_port_mode` 的旧配置按以下规则解释：

- 旧值为 3080、缺失、无效或保留端口：转自动模式。
- 旧值为其他合法端口：保留为手动模式。
- 已有合法的 `auto`／`fixed`：尊重显式选择。

用户现在明确选择 `fixed` + 3080 时仍可使用 3080。
“旧默认 3080 转自动”不能写成“3080 永远禁止”。
保存后正常停止并重新启动 Web，端口策略才用于新的监听。
当前已启动的页面不会因为输入框改了数字就换端口。

## 鉴权必须绑定本次实际端口

正式启动命令始终包含 `--host 127.0.0.1 --port <选择值>`。
`0` 是监听请求，不是可打开的网页端口。
实际地址取自本轮进程完整的官方 `dsh web: ...` 启动行。
它必须解析成精确的 `http://127.0.0.1:<端口>/?token=...` 格式。
若本轮使用固定监听值，返回的实际端口还必须与该值相符。
保留端口、插件日志内伪造 URL、半行 token 或互相冲突的官方行不能发布为就绪地址。

不能在未知端口逐个发送旧 token，寻找“看起来像 DSH”的服务。
不能仅凭端口打开、HTTP 200 或旧页面仍可见，就认为新 Web 已就绪。
启动期间等待鉴权本身不等于失败；慢启动不能触发业务重放。
查看 Main 当前状态，区分后端鉴权就绪与网页实际载入就绪。

相关实现：

- [WebLaunchCommand.java](../app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java)。
- [DshAuthUrl.java](../app/src/main/java/com/deepseekharness/app/util/DshAuthUrl.java)。
- [WebOutputSession.java](../app/src/main/java/com/deepseekharness/app/core/WebOutputSession.java)。
- [LanAuthBridge.java](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)。

## 监听竞争与“一次重试”的现状

需要区分已经接入的启动前备用选择与监听失败后的重新派发。
`WebPortPolicy.retryBindRace` 定义的允许条件是：

- 本次监听请求大于 0，且尚未重试。
- 尚未鉴权；冲突必须精确对应本次监听端口。
- 必须有本轮 guest 已退出的确认。
- `onlyBindConflict` 还要求诊断中只有该明确的官方 bind 冲突，没有插件故障。

**当前 F10 的生产 Java 没有调用这两个重试判据。**
它们有单测，但不能据此宣称实际监听竞争已自动重试一次。
当前确定存在的是 `choose` 的启动前探测和备用／动态选择。
遇到实际 `EADDRINUSE`，保留失败诊断，按原恢复与停止流程处理，再由用户正常发起启动。
如果要接入自动重试，应在生命周期控制器内补齐生产调用、退出证明和实际回归。
不能因超时、未知退出结果或插件错误而重放启动，更不能重放已提交业务。
不能按端口、进程名或裸 PID 强杀占用者。

## F12插件卡顿调查与自动端口的边界

原生插件助手执行guest命令，不使用Web HTTP或WebRPC的当前Connection；网页Plugin Manager则使用当前Connection。受控真实Host中，官方voice插件实际启用/禁用后仍为同一个HTTP对象与端口。已有证据定位到耗时同步内容hash阻塞Node主循环，不能据此把所有手机卡顿归因于同一个原因，也没有证据证明自动端口就是插件卡死根因。

因此保留自动 `--port 0` 与仍可用的本机成功端口复用，不为这次现象取消自动模式。只有真实端口变化才形成新的网页origin；草稿/图片恢复、OAuth本次临时回调路由、LAN代理与当前generation各按自己的身份规则处理，不能因开关插件就假定它们已换端口或获得新许可。F12的异步内容证明和原生独立刷新边界见[插件运行与权限](plugin-runtime-and-permissions.md)，正式结果见[F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)，不以主机测时替代手机性能验收。

## 本机记忆与备份边界

`ConfigStore` 用 `web_fallback_for` + `web_fallback_port` 记住匹配策略的成功备用值。
它还记录 `web_origin_ports`，供显式旧端口草稿恢复选择。
自动模式或实际采用备用时，在发布合格鉴权后才保存这些记录。
这不是所有历史启动端口的完整审计清单。

可迁移配置中的 `port`／`portMode` 可以表达用户的端口偏好。
本机备用记忆、旧 origin 清单、当前运行代次及鉴权凭据不因此获得跨设备授权。
在另一台设备恢复“手动 45678”只代表偏好，不代表该端口可用或已鉴权。
LAN、能力桥与 ADB 授权必须按各自本机规则重新核验。
不要把带 token 的网页地址写进公开报告、命令参数或同步配置。

## LAN 使用 3081，后端仍是本轮实际端口

配置页开启 LAN 后，代理在具备当前代次有效 BrowserAuth 的情况下启动。
代理监听 `0.0.0.0:3081`，向 `127.0.0.1:<本轮实际 Web 端口>` 转发。
后端端口和 generation 由一个监听实例捕获，不能随异步旧回调混用。
开关允许访问不等于监听成功；必须检查当前代理的实际绑定状态。
手机换网络、系统局域网权限或路由隔离仍可能使其他设备无法连接。

LAN 有独立 token／cookie，不向远端客户端暴露 DSH 启动 token 或后端 BrowserAuth。
代理用内存中的本轮 BrowserAuth 向后端鉴权，停止／换代次使旧快照失效。
浏览器提供的 Host、Origin、Cookie 等字段按后端 loopback 语义重写。
首次 token 链接经跳转只清掉LAN token，保留原路径和其他查询参数；仍应把完整 LAN 链接视为敏感信息。
LAN 当前是 HTTP 代理，没有在此实现 TLS 终结；使用范围应符合所连网络的信任边界。

HTTP 与 WebSocket 共同经过鉴权和当前代次检查。
只有验证通过的 101 WebSocket 握手才能升级成双向通道。
普通零长度正文、分块与 trailer、跳转、长流不能靠“读到连接关闭”为统一分界。
连接登记、短请求、长流和反向 pump 有各自边界，停止须关闭本轮 socket。
LAN 网页访问不是把 3090 Android 能力桥直接暴露给局域网。

F11保持未知后端端口0为未就绪，不回退3080；同代次的新鉴权实际端口会替换旧代理目标。业务401/403仅影响该请求，不自动销毁整轮BrowserAuth。接口/地址变化会回收旧连接但保留本轮token和监听；相同接口/地址的快速网络替换不保证可观察。具体操作、地址候选和主机验证边界见[LAN专题](lan.md)。

严格恢复接入同一个活跃Web时，F12在第二次内核身份核验与adopt后复用该次已验证cookie；发布再次绑定当前generation、精确URL和实际端口，不重新exchange或执行普通新启动的 `afterAuthenticated`。LAN关闭不监听，绑定失败不撤销本机已成功的接入。详见[LAN严格恢复](lan.md)。

维护代码见 [LanProxyService.java](../app/src/main/java/com/deepseekharness/app/LanProxyService.java)、
[LanAuth.java](../app/src/main/java/com/deepseekharness/app/LanAuth.java)、
[HttpProtocol.java](../app/src/main/java/com/deepseekharness/app/util/HttpProtocol.java)。

## 旧端口网页数据与运行代次

浏览器按协议、主机、端口区分 origin；端口变了，旧草稿可能仍在旧 origin。
这不等于后端会话或附件文件已被删除。
“配置”中的“恢复旧端口草稿”先要求当前 DSH 就绪，再选择本机已记录的旧端口及内核。
恢复会先关闭现有预览并等待资源释放，避免活跃页面与恢复同时写入。
旧端口被占用时拒绝接管，保留源草稿和候选，不抢占服务。
只恢复受支持且校验通过的草稿／图片记录，不能作为任意 localStorage 搬运器。
新目标已有不同内容时保留冲突；只在允许的空白目标发布并核对读回。
不迁移 cookie、鉴权 token 或其他页面的图片 lease，也不删除源数据。

实现位于 [BrowserOriginRestore.java](../app/src/main/java/com/deepseekharness/app/ui/BrowserOriginRestore.java)、
[browser-origin-state.js](../app/src/main/assets/app-integration/browser-origin-state.js)。
同一个端口可被后来的新运行复用；generation 与页面身份仍须单独验证。
WebView／Gecko 的本地存储也不能仅凭端口相同互相替代。

## 用户排查顺序

1. Main 看当前启动阶段、实际端口及错误，不使用书签中的旧鉴权链接。
2. 若显示等待鉴权，先查本轮启动输出；不要把等待时间直接当成端口冲突。
3. 若手动端口被占用，确认是否已成功选用备用端口；首选值仍会保留。
4. 本机网页正常但 LAN 失败：查 LAN 开关、局域网权限、3081 实际绑定和当前链接。
5. ADB 失败：回到无线调试的当前配对／连接信息，不修改 Web 端口碰碰运气。
6. 网页草稿不见：先区分后端数据与旧 origin，再使用显式恢复入口。
7. 域名解析失败另查 [DNS 配置](dns-configuration.md)，端口修改不会修复外网 DNS。

## 修改与回归定位

修改端口选择时一起检查配置迁移、实际鉴权、LAN 捕获与备份投影。
至少覆盖自动 0、旧成功端口不可用、手动冲突、保留端口和假启动行。
网络代理改动需覆盖正文边界、WebSocket 握手、旧 generation 和停止后的 socket。
旧 origin 恢复改动需覆盖冲突保留、预览关闭、凭据排除和读回失败。
下面是已有测试入口，本文没有重新执行这些测试：

- `WebPortSettingsTest`、`WebPortPolicyTest`、`DshAuthUrlTest`、`WebOutputSessionTest`。
- `LanAuthBridgeTest`、`LanAuthTest`、`LanTokenStateTest`、`HttpProtocolTest`、`SocketDispatchTest`。
- `tools/test-lan-settings-host.mjs`。
- `tools/test-browser-origin-restore.mjs`、`tools/test-browser-origin-server.py` 与 `tools/fixtures/browser-origin/BrowserOriginRestoreServerTest.java`。

测试类位于 `app/src/test/java/com/deepseekharness/app/` 的对应包。
主启动环境与 shell 继承另见 `tools/test-build190-command-environment.py`。
软件测试不替代正式 APK 的实际端口、LAN 权限和机型网络验证。
