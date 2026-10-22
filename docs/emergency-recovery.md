# 独立应急 DSH

> 核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；按当前 build190 工作树的应急运行时、工具和原生确认链核验。本次文档补齐没有新增设备、模型对话、启动/关闭或正式数据修复实测。

应急 DSH 用于正式环境不能正常安装、维护或启动时的独立诊断。它拥有自己的固定 rootfs、HOME、空白 profile、工作区和会话；不借正式 rootfs 启动，也不先执行正式迁移、数据绑定或全局启动锁。正式环境的“安全启动”仍使用正式环境，两者不同。整体数据/运行边界见[环境、数据与插件](runtime-data-plugin-architecture.md)。

## 进入和使用

原生[RecoveryActivity](../app/src/main/java/com/deepseekharness/app/ui/RecoveryActivity.java)提供“启动应急 DSH”“进入空白应急 Web”“停止应急 DSH”、原生诊断、无需模型的修复选项、日志和备份入口。可以从环境恢复页进入；原生页面不要求正式 Web 就绪。

1. 可填写仅本次使用的临时 API Key；留空会尝试读取已有可用凭据。输入框不保存实例状态或参与自动填充。
2. 启动并等待包内资产、工具自检和本轮 Web 鉴权。缺模型凭据仍可先看原生诊断，模型对话须配置可用临时密钥。
3. 进入应急 Web，让五个受控工具读取相关诊断、提出候选。诊断原文可能发送给所配置的模型服务；宿主会隐藏已知凭据字段，但不保证用户文件所有隐私都被识别。
4. 回到原生应急页检查具体候选和差异，逐次确认或拒绝。工具生成提案不等于已经修复。
5. 查看候选结果，再重试正式环境启动确认实际可用；完成后正常停止应急 DSH。更换临时密钥须先停止再启动。

页面退出或返回本身不是停止证明。正在准备/启动时可以请求停止；慢启动 60 秒只更新提示，不将其当作强杀时限。UI 是否可进入应以 [RecoveryController](../app/src/main/java/com/deepseekharness/app/recovery/RecoveryController.java) 的当前状态、代次和实例身份为准。

## 五个工具与提案边界

[recovery-agent.js](../app/src/main/assets/recovery-agent.js)只注册以下工具；不开放任意 shell、路径读写或模型直接覆盖正式插件/会话。

| 工具 | 输入/输出边界 | 是否写正式数据 |
|---|---|---|
| `dsha_diagnostics` | 读取脱敏启动与维护状态，不运行正式环境 | 否 |
| `dsha_targets` | 列出宿主批准的目标 ID 和允许动作，不接受任意路径 | 否 |
| `dsha_read` | 按目标 ID 读相关内容、源摘要与数据代次，隐藏凭据 | 否 |
| `dsha_propose` | 绑定目标 ID、已读取源 SHA-256、数据代次生成候选 | 否，等待原生确认 |
| `dsha_result` | 查询待审阅、拒绝、完成或失败状态 | 否，不能确认 |

普通设置候选须通过当前 schema；受管运行时和 CLI 依赖修复只能调用当前签名 APK 的固定维护动作并走既有试运行，不能让模型任意编辑 `node_modules` 或安装未锁定包。目标还可包含恢复中断维护、新建基础 Web profile 或全局补丁等宿主动作，实际可用集合由 `dsha_targets` 返回，不把表内工具数当作可执行动作数。

[RecoveryRepairBroker](../app/src/main/java/com/deepseekharness/app/recovery/RecoveryRepairBroker.java) 的本机 HTTP 协议只有 diagnostics、targets、read、propose、result 与 consent；没有 confirm 路由。请求必须是固定 POST、精确 `127.0.0.1:<port>` Host、单一 Bearer token、无 Origin，正文有上限；认证只允许该会话访问受控接口，不能替代原生用户确认。[RecoveryBrokerProtocol](../app/src/main/java/com/deepseekharness/app/util/RecoveryBrokerProtocol.java)是协议验证入口。

## 原生确认、事务和关闭

原生确认绑定应急实例、generation、nonce、候选、当前源摘要和正式数据代次。确认时重新核对源状态；源变了报 `REPAIR_SOURCE_CHANGED`，不能沿旧候选写入。存在待恢复维护时，先处理明确的恢复动作，不能跳过日志直接更新。

确认后经 [MaintenanceCoordinator](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) 正常停止正式 Web、终端和写任务，再进入对应宿主事务。正式 guest 与 launcher 退出未确认时保留屏障，不执行写入。候选设置走 [ProfileSettingsTransaction](../app/src/main/java/com/deepseekharness/app/backup/ProfileSettingsTransaction.java)；基础配置走 [StartupRepairs](../app/src/main/java/com/deepseekharness/app/core/StartupRepairs.java)，受管运行时走 [EnvironmentMaintenance](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)。

已原生确认的修复 lease 由应急前台服务继续保护。停止应急聊天会撤销后续提案/确认和关闭 broker，但不会提前释放正在执行的原生修复 lease；活动修复完成后服务再收尾。`APPLIED` 只说明该原生事务完成，正式 Web 的实际就绪还需重试验证。失败保留候选和原件供原生记录检查，不自动重放业务任务。

应急停止由 [RecoveryRuntime.stop](../app/src/main/java/com/deepseekharness/app/recovery/RecoveryRuntime.java)写停止哨兵，仅向本次记录且二次出生身份一致的 guest 发送 SIGTERM；不按裸 PID/端口/名称强杀，不先杀 proot 启动器。guest 和 launcher 均确认退出才写 closed。退出未知显示停止未确认并保留记录，可重试正常停止；不能删 `recovery-active` 伪造已关。

## `READY` 与 `READY_READ_ONLY`

就绪至少要求固定资产验证、鉴权交换、五工具自检、本次启动器未退出。进程身份验证成功才登记完整的 recovery 实例。若系统拒绝读取身份，而其他就绪证据成立，可显示 `READY_READ_ONLY`：应急聊天和只读诊断可用，broker 阻止正式数据写入。

`READY_READ_ONLY` 不是允许忽略停止证明的豁免，不将该进程注册为正式停止屏障可跳过对象，也不能通过“全部允许”提升写权限。单纯 `/proc` 不可读、token 可访问或工具存在都不能推导任意应急进程可信。

## 独立资产、会话和空间

[tools/recovery-runtime/lock.json](../tools/recovery-runtime/lock.json)与正式 DSH 锁独立。APK 中的 `recovery-asset-locations.json`只描述固定归档物理位置；只有正式归档 SHA 与独立应急锁完全相同才共享包内字节。共享压缩字节不共享解压运行根，也不令新正式 DSH 自动成为应急版本。构建维护见[应急资产说明](../tools/recovery-runtime/README.md)。

`files/recovery-capsules/<runtimeId>/`保存独立运行胶囊，`files/recovery-sessions/<instanceId>/`保存每次独立 HOME 和现场。创建时校验归档和受管文件；重新准备失败的原件被保留，不自动删除会话。`recovery-active`只跟踪未关闭实例，不能把历史会话总数当永久启动上限。准备复用只接受尚未 launched、inode/摘要与 prepare-proof 一致的候选。

`RecoveryRuntime.prepareProfile`生成有合法 `name` / `version` 的 `dsha-recovery-agent/package.json`。锁定 rc2 的模型请求扩展会读取活动插件版本；缺 version 可在模型 HTTP 前抛 `REQUEST_EXTENSION`，因此“Web 已开”不足以证明能发起应急请求。

[RecoveryStoragePlan](../app/src/main/java/com/deepseekharness/app/recovery/RecoveryStoragePlan.java)的空间说明使用不跟随链接的表观文件字节测量，异常为 unknown；它不自动删除应急原件。磁盘不足时查看原生错误、先保护可读数据，不通过清正式 Ubuntu 或用户会话腾空间。

## 修改与验证入口

- 固定资产、发布与空间：[RecoveryPublicationTest](../app/src/test/java/com/deepseekharness/app/recovery/RecoveryPublicationTest.java)、[RecoveryStoragePlanTest](../app/src/test/java/com/deepseekharness/app/recovery/RecoveryStoragePlanTest.java)、[RecoveryArchiveSourceTest](../app/src/test/java/com/deepseekharness/app/util/RecoveryArchiveSourceTest.java)。
- 协议、确认与状态：[RecoveryBrokerProtocolTest](../app/src/test/java/com/deepseekharness/app/util/RecoveryBrokerProtocolTest.java)、[RecoveryRepairPlanTest](../app/src/test/java/com/deepseekharness/app/util/RecoveryRepairPlanTest.java)、[RecoveryStatusTextTest](../app/src/test/java/com/deepseekharness/app/util/RecoveryStatusTextTest.java)。
- 模型扩展与工具实际准备：回归必须调用锁定 DSH 的真实 `deepseekLlmApiExtensions.prepare()` 核对应急包，而非只搜索 `toolNames` 字符串；停止回归必须分别覆盖 guest 与 launcher。

测试名称是后续关联检查入口。本页没有宣称全部模型服务、Android FD/身份限制、后台服务或历史修复路径已经在本轮手机上验证；旧发布记录只对对应版本和现场生效。
