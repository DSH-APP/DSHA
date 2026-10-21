# 局域网访问、鉴权与故障判断

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；核验当前 build190 工作树的 LAN 配置、监听和鉴权代码。本文不记录本轮手机或网络实测。

F12补充：同代进程身份后来转为UNKNOWN时，最终发布门禁会拒绝晚到的LAN cookie；已有该代鉴权、监听和连接也会按generation撤销，保活检查不能凭旧cookie重开。旧代的晚到核验不能撤销新代连接。该收敛不发送Web进程信号、不解除未知退出屏障，也不清理用户数据。

## 开启和撤销

“配置 → 允许局域网访问”默认关闭。保存开启后，当前 Web 必须已完成本次实际端口的鉴权；应用才使用该后端建立 LAN 代理并显示带 token 的访问链接。Android API37起还需本地网络权限，较低系统不申请该新权限。系统许可失败不会被 DSHA 开关覆盖。

只将应用当前显示的链接交给需要访问的可信浏览器。代理监听 `0.0.0.0:3081`，后端为本轮 `127.0.0.1:<实际Web端口>`；后端不是固定3080，3090设备桥也不随之对外开放。详见[端口与网络](ports-and-networking.md)。

关闭开关会撤销本轮 token、关闭外部监听与对应连接；本机 DSH cookie不因撤销 LAN 被清除。重新开启或服务新一轮启动应使用新链接，不能复用截图或书签中的旧 token。开关、token存储与当前服务内存身份并非同一状态；旧持久值不能替代本轮有效 token。

## 严格恢复后复用已经验证的鉴权

F12的宿主重建恢复先核私有记录、APK/descriptor/root及本轮进程身份，执行真实HTTP鉴权，再进行第二次内核进程核验；成功只接入同一个仍活动的Web。LAN发布复用这次严格恢复中已经验证的cookie，不重新执行token交换，不调用普通新启动的 `afterAuthenticated`，也不重放启动、迁移或业务操作。

发布前仍须核对当前generation、原始鉴权URL和实际端口；`WebLifecycleController.publishRecoveredLan`与`LanAuthBridge.publishVerified`再次核对精确快照，迟到或不匹配的结果不能绑定代理。LAN关闭时不启动监听；LAN绑定失败只记LAN诊断，不撤销已经成功的本机Web接入。cookie是该次真实鉴权结果，不是从旧存储值推断出来的新许可。严格接入未通过时仍保留UNKNOWN与原停止屏障，不借LAN可达性推断可恢复。

实现见[WebSessionRecovery](../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java)、[WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)及[LanAuthBridge](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)。本批已知修复范围见[F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)；最终软件/APK数量和发布状态由负责人冻结，本文不预先宣布。

## 明文与认证边界

[LanProxyService](../app/src/main/java/com/deepseekharness/app/LanProxyService.java)使用普通 HTTP，没有 TLS。流量、对话及 token可能被同网观察者看到，只应在可信网络使用；这不是一个用于公网暴露的安全网关。平台背景与本项目边界见[安全模型](security-model.md)。

每轮生成32字节安全随机 token，链接中的精确 `token=` 或 `dsha_lan` cookie可认证。[LanAuth](../app/src/main/java/com/deepseekharness/app/LanAuth.java)做常数时间比较，不把任意自定义头作为旁路。首次有效链接返回303，设置 `HttpOnly; SameSite=Strict; Max-Age=43200` cookie；重定向保留原路径和其他查询参数，只移除LAN `token`，例如OAuth callback的code/state不应被改成根路径。12小时cookie寿命不能延长服务端本轮token寿命；停止/撤销后旧cookie仍无效。

后端 DSH token/cookie由代理在已核验的本机目标使用，不直接把3090 `X-Token`或后端凭据当作浏览器可转发凭据。请求/响应处理、WebSocket升级、分块和连接额度仍由受限HTTP处理链控制。

F11将后端实际端口未知的0保留为未就绪，不能偷偷回退3080；同代次鉴权更新到新实际端口时替换对应代理目标。普通业务401/403透传该请求，不把任意业务拒绝提升成整轮BrowserAuth失效。读头空闲连接与短请求分别限额，慢头连接不占满业务派发池。

接口/地址集合变化约每秒检查一次：回收旧连接及对应companion，保留wildcard监听和本轮LAN token。快速在相同接口、相同地址间替换网络不保证被观察到。显示地址由[LanAddressPolicy](../app/src/main/java/com/deepseekharness/app/util/LanAddressPolicy.java)选取up、非loopback/非P2P的RFC1918候选，优先wlan/wifi，再eth；没有合适候选就不使用其他私网兜底。显示地址只是保守候选，不是网络可达证明。

## 失败时按这一顺序看

| 现象 | 含义与处理 |
|---|---|
| 没有可分享的LAN链接 | 先确认开关、系统本地网络权限、Web本轮是否真正就绪和已鉴权 |
| 401，`X-Dsha-Lan-Reason` | 缺失、旧或错误token；响应会清旧cookie，重新使用应用当前链接 |
| 503 | 后端尚未完成认证/就绪或目标不可用；不能靠重复输入旧token解决 |
| 浏览器停留在旧页面 | 旧cookie或旧服务链接不代表新一轮身份；重新打开当前链接 |
| 同网仍无法连接 | 核查手机IP、Wi-Fi隔离/VPN/防火墙和系统许可，不把网络故障当认证失败 |
| 本机Web可用、LAN不可用 | 两条入口独立，保留本机可用状态，再查代理与网络 |

LAN日志由服务脱敏后写入专用日志和logcat；不要将完整带token链接放进公开日志或缺陷截图。对外监听的关闭、连接停止和后端代次绑定应分别验证，不能仅凭UI开关变色声明撤销已生效。

## 源码与验证

配置入口：[ConfigFragment](../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java)、[LocalNetworkAccess](../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java)；认证状态：[LanAuthBridge](../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java)、[LanTokenState](../app/src/main/java/com/deepseekharness/app/util/LanTokenState.java)；失败响应：[LanFailureResponse](../app/src/main/java/com/deepseekharness/app/util/LanFailureResponse.java)。关联测试为 [LanAuthTest](../app/src/test/java/com/deepseekharness/app/LanAuthTest.java)、[LanAuthBridgeTest](../app/src/test/java/com/deepseekharness/app/core/LanAuthBridgeTest.java)、[LanTokenStateTest](../app/src/test/java/com/deepseekharness/app/util/LanTokenStateTest.java)。源码单测不能替代真实Wi-Fi、系统权限、网络隔离或长流设备验证。

F11的[test-lan-proxy.py](../tools/test-lan-proxy.py)在JDK17以实际生产代理和主机loopback socket加逻辑回归共49项通过；保留修复前44项中5失败对照。Android Context/许可/日志为宿主stub，未操作设备、ADB、真实Wi-Fi切换或运行APK；未知OEM/热点/VPN兼容仍未验证。精确最终执行范围由本轮交付回执绑定，不能把本段当手机LAN验收。
