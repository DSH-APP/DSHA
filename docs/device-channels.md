# ADB、Root 与 Shizuku 操作及失败语义

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；本文从当前 build190 工作树的设备通道调用链整理。完整架构、权限与状态机见[权限架构](device-permissions-architecture.md)；本文不声称本轮已连接设备。

## 选择需要的能力

读屏、文字点按和普通物理屏观察使用无障碍服务，不要求先配置ADB。设备shell、安装/停止受支持应用和创建独立虚拟屏等能力才需要可用的特权通道；容器内显示为root不代表Android已经root。仅开启无障碍不会创建虚拟屏。

在“设置 → 设备能力授权”分别配置和撤销Root、Shizuku、ADB。三条通道不是同一个总开关，DSHA授权、Android系统许可、真实执行身份和单次命令批准也分开校验。

| 通道 | 用户准备 | 确认成功 | 失败和撤销 |
|---|---|---|---|
| ADB无线直连 | 系统开启无线调试；在DSHA输入系统提供的配对端口/码，再使用连接端口 | 配对成功与连接成功分开；本机 `id` 的实际身份和连接状态才是执行依据 | 系统重启/关闭调试后可能需重新开启；撤销DSHA能力不等于系统已撤销配对 |
| Shizuku | 先让Shizuku运行，再在管理器允许DSHA，并等待执行服务Binder连接 | 管理器已授权不代表执行服务已经ready | Binder不可用/服务未就绪返回失败；执行已派发后失联可能是UNKNOWN |
| Root | 已有su/管理器并明确允许DSHA；探测不应静默索权 | 找到su、用户允许、真实命令成功是三步 | 无su为不可用；用户拒绝不改用解释器绕过 |

ADB配对与连接端口均由Android提供，不能填写Web端口或3090。依赖由应用离线准备；`PAIR_OK`、`CONNECT_OK`、`CONNECT_WARN`、`DEPS_MISSING`含义不同。不要因已经配对而把连接警告写成成功。

## 一次设备命令如何派发

[HttpShellService](../app/src/main/java/com/deepseekharness/app/HttpShellService.java)的自动设备执行按Root → Shizuku → ADB选择**一次**可用通道；`--su`只用于明确需要root且已授权的操作。命令先由[DeviceShellPolicy](../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java)完整识别和限制，再进入[DeviceShellExecutor](../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java)。同一策略适用于三通道，root也不能放行已拒绝命令。

只接受可完整识别的单条argv，不执行调用方提供的shell程序、管道、重定向或未知命令。块设备、分区、挂载、SELinux、刷机、系统属性等写入被拒绝；`[POLICY_BLOCKED]`没有“仍然允许”。终止应用还会核对完整包名、应用清单和用户/系统属性，不依裸PID或模糊名称强杀。

| 结果 | 含义 | 后续动作 |
|---|---|---|
| 124 / `*_UNAVAILABLE` / `*_NOT_READY` | 通道在派发前明确不可用或未就绪 | 修复对应授权/服务；不要把它当作业务命令的退出码解释 |
| 125 / `[EXECUTION_UNKNOWN]` | 命令可能已发送或执行，结果无法确定 | 观察真实目标状态并保留现场，不能切通道或自动重放 |
| 126 / `[POLICY_BLOCKED]` | 执行策略拒绝 | 报告原因；用户普通确认不能覆盖这条策略 |
| 工具动作接受 | Android接受了动作/提交 | 不等于目标应用已经达成效果，重新观察 |

超时不自动等于“未执行”。部分调用有独立等待预算；Shizuku服务等待、ADB连接、设备命令和虚拟屏启动的预算不混用，错误后不以换通道隐藏未知结果。

## 屏幕、短信与工具审批独立

屏幕许可绑定当前运行代次、实际目标、最新截图或节点身份。“全部允许”只免DSHA确认，不能补齐系统能力，也不免新鲜帧/节点检查。停止、撤销和规则revision变化会使旧排队请求失效，见[安全模型](security-model.md)及[虚拟屏架构](virtual-screen-architecture.md)。

短信读取是独立敏感能力：默认每条确认，只允许当前Android用户的只读 `content query --uri content://sms`。Shizuku不执行该敏感查询，不允许发送、修改或删除；即使DSHA允许，系统仍可能拒绝。该授权不随备份迁移。

原生终端和第三方插件在应用UID下开放运行，设备工具策略不构成对任意代码的强沙箱。DSH工具审批、Android能力许可和通道身份不能互相代替。

## 维护与验证入口

实际通道实现：[RootShell](../app/src/main/java/com/deepseekharness/app/RootShell.java)、[ShizukuShell](../app/src/main/java/com/deepseekharness/app/ShizukuShell.java)、[AdbBridge](../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java)、[DeviceBridgeService](../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java)。关联测试和故障定位按[权限回归地图](device-permissions-architecture.md)选择。代码和宿主测试不证明所有ROM、Root管理器、Shizuku版本或无线网络可用，本轮不通过ADB操作补证。
