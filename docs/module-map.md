# 当前模块与状态归属

此图说明现行源码的实际职责与状态归属，不把 package import 图等同完整运行架构。交付版本与已执行范围以 README 指向的本轮报告为准。

使用顺序为[接手指南](接手指南.md) → [工程规范](engineering-standard.md) / [ADR 0001](adr/0001-build154-boundaries.md) → [CONTRIBUTING](../CONTRIBUTING.md)。历史审计副本通过[保存清单](audits/build154/history-document-sources.json)保留，其旧“当前”标题不覆盖这里的生产归属。

| 边界 | 生产入口 / 协作者 | 负责的状态 |
|---|---|---|
| 组合根与应用状态 | DshaApp、ApplicationOwner、HarnessSessionState、RuntimeHostPorts | 应用持有唯一 Web 代次/队列/停止意图与服务槽位；平台端口由本 App 实例显式注入，不用全局 shared 注册表 |
| Web 兼容门面与生命周期 | HarnessController、WebLifecycleController | 门面保留既有 API；实际启动/停止和状态转移在生命周期执行器，复用同一个 HarnessSessionState |
| 启动与输出 | StartupPipeline、WebOutputSession | 有序准备、当前进程输出、鉴权和 EOF；通过窄端口通知编排者 |
| 启动平台动作与配置 | StartupEnvironmentActions、HostConfigService、WebReadyTasks | 宿主恢复、插件和配置快照的窄平台适配；原生配置事务与就绪后任务，不持第二套 Web 状态 |
| LAN 鉴权与进程租约 | LanAuthBridge、WebProcessSession | 捕获代次、URL 与实际端口；锁外 HTTP 后复核当前性；精确 launcher 句柄与桥 lease |
| 命令与环境 | ProotBootstrap、RuntimeLauncher、LaunchSpec、RuntimeTools | 不可变命令输入、统一环境、受管资产及补丁链 |
| 退出 | WebStopCoordinator、WebProcessManager、TerminalSessionOwner | 出生身份、会话和实际退出证据；未知结果保留屏障 |
| 冷安装 / 重建 | ColdInstallTransaction、ColdBundleTransaction、ColdBundleExtractor、ColdToolsInstaller、EnvironmentRebuildTransaction | 候选环境、受管解压及工具安装；冷安装选择和 root 发布一起回切；重建个人数据由宿主层保护 |
| 数据与备份 | backup 宿主文件层、HostDataTransaction、NativeBackupJobs、MaintenanceDataSnapshot.capture/restore | 范围、认证/摘要、编号槽位、数据代次和读写事务；基础重建不借损坏 guest 的 Python 备份作隐式回退 |
| 维护界面适配 | MaintenanceUiPorts、DataProtectionService、DshaApp | 备份层发出维护状态与通知请求，组合根装配真实界面目标；备份/运行时不直接引用 ui 类 |
| 插件 | PluginInstallJournals、plugin-transactions.py、插件归档图 | 原件 / 候选切换、实际依赖、用户启停意图 |
| 浏览器与桥 | WebPageScripts、GeckoPreviewActivity、HttpShellService | 当前页面/来源/代次、鉴权、能力授权；不拥有第二套运行状态 |
| 独立应急 | RecoveryController、RecoveryRuntime、RecoveryStoragePlan、RecoveryRepairBroker | 独立根与 HOME，五项受控工具；安全文件层原子记录、明确发布与保留原件；原生确认的修复 lease |
| 部署与历史隔离 | asset-deployment.json、prepare-standard-assets.py、prepare-recovery-assets.py | 许可/插件/扩展完整成员、明确源与物理映射；退休源不进入APK |

亮点是受管运行组件与用户数据分离、可回切的宿主事务、独立应急根以及进程身份保护。复杂度主要来自 Android 文件别名、proot/proroot 两通道、历史格式与浏览器生命周期。Java、Python、Node 之间仍存在跨语言契约，必须以锁、实际内容证明和行为测试约束，不能靠复制常量维持一致。

本轮继续拆出实际执行协作者和实例化平台端口；这不等于所有历史耦合已经消失。backup 与 core 仍有 Android 协调适配边，实际冻结边清单见 `tools/architecture-backup-core-baseline.json`，边界检查仅允许减少，不能据此宣称所有依赖都已反转。宿主测试、平台编译、手机生命周期、隐藏 API、持久盘掉电与外部服务是不同证据。当前软件工作与缺失证据从本轮逐项回执重建，不能沿用旧台账缓存或以类的行数宣称全部完成。
