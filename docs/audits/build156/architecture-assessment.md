# build156 架构评估与剩余设计边界

当前项目仍是单 Android 模块中的宿主编排架构：界面与平台服务适配 Android 生命周期，proot/proroot 承载 Linux/Node 运行域，宿主事务负责原件、候选、认证与提交。判断重构是否有效，应看执行职责、状态权威与失败边界是否真正改变；文件数量、行数和原审计条目关闭率都不能证明技术债已经清零或低于某个分数。

本评估逐项回应 [build155 未完成事项清单](../../releases/build155-remaining-work-20261003.md)中的五项结构工作。此前状态由摘要已核对的 build155 完整源码 ZIP 读取，当前事实见 [机器事实 JSON](architecture-assessment.json)。这些指纹只绑定本次架构核对所读字节；最终正式 source SHA、双 flavor 完整验证、Release Lint、APK/ELF/证书及真实设备验收由根交付报告另行绑定。

## 五项降债的前后状态

| 项目 | build155 已有 / 尚欠 | build156 实际变化 | 继续可做的设计工作与保留边界 |
|---|---|---|---|
| ProotBootstrap | 已拆冷安装、解压和公共输出；插件、维护、PTY 实现仍在门面内 | GuestPluginScripts / GuestScriptQueue、RuntimeExecution / RuntimeLauncher、TerminalRuntime、RecoveryToolchain、RuntimeDataTransfers、RootfsReadiness 等实际承担执行；命令输入与环境继续使用 LaunchSpec 和显式宿主端口 | 门面仍装配平台协作者。后续按输入、执行与退出责任收敛，不能为消除旧接口另建运行方式、工作锁或停止权威 |
| HttpShellService | 有路由表与处理器映射；端点平台动作仍集中在服务中 | BridgeRoutes 与 handler registry 明确分发；应用启动和剪贴板的真实平台动作移到 AppDeviceActions；特权文件执行由 AndroidDeviceFiles / DeviceFileCore、DeviceFileOperations 配合承担 | 服务内仍有文件读取、导出、打开、分享、问询、虚拟屏等协调动作，可继续按端点组拆分。鉴权、目标代次、确认与结果未知边界必须统一。原 D-ARCH-06 的无路由表缺口已解决，不代表所有端点已拆完 |
| 全局状态与依赖 | Web 会话已归应用；宿主端口、虚拟屏与部分监听器仍使用可变静态状态 | Web、宿主端口、仓库与前台服务引用由应用拥有；最后追加修订也把 VirtualScreenManager 及预览/前台/悬浮/节点状态、PortableSettings 的监听/错误/写队列归到同一应用所有者。LAN、配置、进程租约与停止事实有实际协作者；五条不必要 backup/core 边删除 | 应用拥有者必须唯一，不能再有默认另一 manager 或 App.current。WebLifecycleController 仍可按窄端口收敛；独立 app_process 与全 UID 工作记录仍有各自进程合同。硬件唯一性不要求应用状态使用可变静态字段，二十条已分类协调边也不等于全面依赖反转 |
| rc1 迁移 | prepare / finalize 每启动仍进入 guest 链；完成缓存缺少严格退出条件 | Rc1MigrationReuse / Rc1MigrationCache 核对同代 v2 记录、实际根 dev/inode、已提交/保留源/已导入标志、小配置及恢复标记摘要；真实 GuestPluginScripts 可复用本次证明，减少重复 guest 调用 | 保留接口和未知输入时的真实检查。不能按 APK 版本或归档自称已完成跳过；旧数据、共存配置、链接与读证失败仍需保护。这里是有证据的重复工作退出，不是删除历史读者 |
| 移动插件 HTTP 压缩 | 大响应/多次 write 已流式；≤1 MiB 完整响应同步压缩，全局钩子长期影响未测 | 4 KiB–1 MiB 合格完整 JSON 的一次 end(data) 使用异步 gzip / brotli，最多两个待完成压缩；不合格、饱和、大响应、流式正文等走 Node 原生路径，按实际公共 WebServer carrier 限定 | 仍包装 ServerResponse.prototype；第三方再次包装、长期装卸、Android 后台/息屏时序仍需证据。宿主预算断言只能证明执行范围，不是手机收益百分比 |

当前所读物理行数为 HarnessController **276**、ProotBootstrap **496**、HttpShellService **2112**、WebLifecycleController **939**。build155 归档对应前三者分别为 **1198 / 1417 / 2322**。HarnessController 263 行、HttpShellService 2113 行分别属于受控停止门面及最后虚拟屏调用收敛前的中间状态，不作为最终字节指标。这里列行数只帮助定位职责集中处：控制器变短是实际职责分离的结果，而 WebLifecycleController 与 HttpShellService 的剩余集中程度仍需结合具体行为评估，不能换类名后宣布全部解耦。

## 状态与依赖的准确边界

DshaApp 与 ApplicationOwner 持有完成构造后发布的应用实例；HarnessSessionState 仍是唯一生产 Web 会话、代次、队列和停止意图。启动、输出、鉴权、兼容重试与停止协作者共享该会话，不保存第二套生产状态。停止与核验产出稳定 WebStopDiagnostic 代码及原始事实，Core 展示适配按当前语言渲染，多来源事实不因显示字符串变化而丢失。

VirtualScreenManager 的 Context、token、channel、generation、epoch、heartbeat、动作/状态锁、worker 以及 UI/预览/节点协作者均归到应用唯一实例。Context 入口只解析既有应用所有者；硬件会话唯一、撤销代次与旧回调失效仍是同一组合同。PortableSettings 同样使用应用实例，维持每份偏好的一次监听和顺序写入；重置先解除监听并撤销旧 binding，排空已接受写入，再清理与重建。真实 Executor/SharedPreferences 接口夹具已核对双实例隔离、失败来源保留及旧回调不能回写；这不等于实际 Android prefs 持久化或虚拟硬件验收。

backup→core 的命名边从 **25 降为 20**。删除的是 ExternalBackupScanner 对控制器的无必要参数、PluginInstallJournals 的三条 guest 执行/维护权威依赖，以及 QuarantinedPluginReview 对 Web 控制器的定位。前者现在只收所选 rootfs File；插件恢复编排由 PluginJournalRecovery 承担；预设转换使用明确运行时适配器。这是实际减少耦合，不是仅更改包名或扩大白名单。

二十条剩余边属于九个明确的 Android 协调或 native Settings/Targets 适配者：

| 适配者 | 边数 | 继续调用唯一权威的原因 |
|---|---:|---|
| AutomaticBackups | 2 | 独立本机计划仍需现有 Web/配置状态和全 UID 工作记录，不能另造“空闲”或停止权威 |
| DataProtectionService | 1 | 前台通知观察/取消真正备份任务；通知页面由应用 MaintenanceUiPorts 提供 |
| FactoryReset | 3 | 删除私有根前需现有维护所有权，并排空 Web/历史/更新写者 |
| NativeBackupJobs | 5 | Android 导入导出作业适配 native 配置与凭据选择、维护/退出证据、未完成维护和前台工作租约 |
| NativeDataLocations | 1 | Android 存储与 native 设置字节由 ConfigStore 唯一投影，通过 ValueBackupSource 交给归档 |
| NativeRestoreTargets | 1 | 实现 HostDataTransaction.Settings / Targets；纯事务与恢复计划只见这些端口 |
| PostUpgradeCleanupService | 1 | 后台清理需既有生命周期/维护权威、活进程和实际字节证明 |
| ProfileSettingsTransaction | 4 | Android 设置导入适配 native 配置及真正停止、工作租约和运行核验 |
| StorageMaintenance | 2 | Android 清理入口取得真实应用目录与维护租约；文件统计和删除策略不拥有 Web 状态 |

逐边理由和处置见 [25 条原边的处置记录](architecture-backup-core-dispositions.json)。冻结名单防止增加或复活删除的五条边，不能证明全面依赖反转。纯文件、格式、归档、认证、恢复计划和事务算法的端口边界与 Android 作业/服务的协调责任必须分开评价。

## 必须继续保留的合同与证据范围

历史归档读者、旧加密原件完整认证、无密码用户 tar.gz 导出、内部自动副本保护、未知退出停止屏障、修改过/不可读原件保留、受管与用户插件分层、独立应急根及原生写入确认，都有维护成本，也都有实际功能和数据保护理由。常驻/前台服务及全 UID 工作租约不能为降低静态字段或依赖计数而移除；开放正式插件与终端也不取消这些合同。

本次评估引出的最后全局归属缺口由主代理明确重新授权修订，随后执行了对应窄 SDK/JVM 与结构检查；不是把尚存静态状态写成必要合同。当前补充回执为 348 个当前源码文件、37 个测试源码文件编译，126 项运行中 125 通过、1 项 Windows 真实符号链接创建能力假定；另有 PortableSettings 三项生命周期行为和九项源边界检查。桥代理另对最终 main/standard Release 源、当前 R/SDK37/目录执行了编译，25 项 JVM 和 6 项真实回环 HTTP 场景通过，最终源/token 证据见 [虚拟屏归属回执](bridge-vscreen-ownership.json)；夹具明确旁路 Android 框架构造，并不渲染真实系统 UI。没有在本评估中操作手机或发布；设备端同签名非破坏性验收若由主代理继续执行，其结果须绑定实际正式 APK 与记录。源码/JVM/宿主内核夹具不能补齐 Android 6/7、OEM /proc 与隐藏 API、FUSE、16 KiB、长期 Doze、掉电或全部外部服务矩阵，来源和旧历史摘要缺证也不是代码拆分可以补齐的事实。

五项工作已有实际降债，同时仍保留明确设计后续。原审计软件缺口的闭环与架构零债务是不同结论；本评估不提供技术债分数、不宣称低于 20，也不提供未经测量的性能改善百分比。
