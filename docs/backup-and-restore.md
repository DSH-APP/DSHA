# 备份、恢复与保留数据

> 核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；以下入口与边界按当前 build190 工作树源码核验。本次补文档，没有新增构建、设备、ADB、恢复实操或长期验收。具体包的执行结果仍看对应发布记录与摘要。

本页说明用户操作和失败边界；二进制格式、密码学参数与资源上限见 [v5 格式](backup-format-v5.md)，整体数据归属见[环境、数据与插件](runtime-data-plugin-architecture.md)。DSHA 宿主备份不同于 DSH 自身的会话文件，也不是 Ubuntu 全盘镜像。

## 选择入口和范围

| 入口 | 内容与目标 | 何时生效 |
|---|---|---|
| 数据与备份 → 一键备份 | 固定 `application` 范围，包含对话、附件、设置、用户插件及定位检查通过的当前应用内工作区；默认不导出原生 API Key | 接纳一次作业后安全停止 DSH 与运行任务，再采集；完成后手动启动 |
| 恢复与更多备份选项 → 手动导出 | 可选应用数据、对话、设置、插件或项目；可另选 SAF 项目目录并明确选择是否包含原生 API Key | 用户选择目标并提交后执行，取消选择不采集或重放 |
| 自动备份 | 默认个人应用数据，不复制 Ubuntu；个人项目使用手动备份 | 到期且 DSH、终端和其他写任务均已停止时采集，不强停业务 |
| 应用数据与救援 / 保留数据 | 检查、导出当前可读数据或所选历史树；救援结果可为部分或尽力采集 | 以显示的来源、范围和结果等级为准，不能当作完整健康副本 |

一键入口由 [WorkspaceFragment](../app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java) → [OneClickBackup](../app/src/main/java/com/deepseekharness/app/backup/OneClickBackup.java) → [NativeBackupJobs](../app/src/main/java/com/deepseekharness/app/backup/NativeBackupJobs.java) 进入既有宿主链。重复点击、旋转和页面重建不会重新提交同一准备请求；进程在选择保存位置期间重启时需重新发起，不自动执行旧选择。

应用内工作区、其他私有项目和外置工作区不是同一个集合。一键备份不会遍历所有项目，也不沿外置工作区引用扫描外部内容；外部文件请单独选择手动备份或使用文件所在应用备份。来源如何定位、排除和投影由 [NativeDataLocations](../app/src/main/java/com/deepseekharness/app/backup/NativeDataLocations.java) 决定，界面有一个路径不等于文件已经入包。

## 保存和确认完成

API29+ 一键保存到系统 `Download/DSHA`，使用 MediaStore 创建新文件，无需所有文件访问权限。Low 在 API23–28 首次选择 SAF 目录并保存授权，后续复用；授权失效时重新选择。Provider 取消和流读写在后台处理。

新导出名为 `DSHA-data-v5-<UUID>.tar.gz`，无密码，包含标准 tar/gzip 外壳、`portable.dshbak` 和说明。每次使用新 UUID，不覆盖已有导出。无密码归档只提供完整性校验，不提供保密性；对话、项目、`.env` 和插件配置可能包含秘密，即使未勾原生 API Key 也须由用户选择可信保存位置。

作业先在私有目录生成并验证载荷，再写目标并读回比较完整摘要。Downloads 的 pending 状态在关闭和完整读回通过后才清除；失败仅清理本任务确实新建且拥有的目标。SAF 若不能证明新对象归属，则保留对象并报告失败；不会按相同文件名删除其他文件。应用被终止后也不会扫描公共目录清历史 pending。

“已写入，目标读回未验证”不等于“已验证备份”，不会更新成功备份记录。保留的私有验证产物可重新导出，但私有副本和本机密钥不保证卸载后存续。新手动导出没有密码选项；历史加密输入仍需要原密码，内部自动副本仍用宿主本机随机密码加密。

| 载荷等级 | 实际含义 | 后续处理 |
|---|---|---|
| `QUIESCENT` | 应用写任务停止并复核来源 | 仍检查归档实际范围 |
| `EXTERNAL_CHECKED` | 对可能由外部应用修改的源重复核对 | 不代表取得外部应用的写锁 |
| `BEST_EFFORT` | 没有应用级静止保证的救援采集 | 保留原件并逐项检查 |
| `PARTIAL` | 存在缺失、不可读或变化项 | 查看清单，不能提升为完整副本 |

插件依赖警告独立保留。`DATA_SAVED_PLUGIN_WARNINGS` 可表示数据已保存但恢复前需检查插件，不能改称所有依赖正常。

## 自动备份设置

[AutomaticBackupActivity](../app/src/main/java/com/deepseekharness/app/ui/AutomaticBackupActivity.java) 中的开关和计划保存后由 [AutomaticBackups](../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackups.java) 重新安排本机 JobScheduler。默认开启，默认每日本地时间 03:00；另可选每隔 1–168 小时或停止 DSH 后。每日计划使用当前本地时区；更改时间不是精准闹钟保证，系统调度、省电和繁忙状态都可能延后。

到期检查要求首启完成、没有其他备份、没有启动/停止过程、没有活动 Web 鉴权会话、没有运行任务、没有待恢复维护日志，并确认 Web 已停止。界面“现在创建自动备份”仍须先开启且满足闲时条件，不能借此强停 DSH。[AutomaticBackupService](../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupService.java) 只取消自己持有 ID 的作业，旧回调不能取消后来手动导出。关闭开关取消未来调度、保留已有副本；已被该自动服务持有的作业在后续轮询看到关闭时取消。

[AutomaticBackupPruner](../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupPruner.java) 只轮换明确标记为自动、已验证、结果为 `COMPLETE` 或 `DATA_SAVED_PLUGIN_WARNINGS` 的副本，保留最新三份。它还重新验证内容和已完成作业记录；有未知额外文件或仍 busy 的目录不删除。手动、未知、部分结果以及公共导出不参加这轮清理。已有加密自动副本但本机密钥缺失时不会生成替代密钥伪装正常。

## 从文件或私有副本恢复

1. 在“恢复与更多备份选项”进入原生恢复入口，选择备份或已验证私有副本。原生入口不等待 Ubuntu/Web 可用。
2. 旧加密输入填写原密码；无密码 v5 输入按内容识别，不因扩展名相同假定加密或全量。
3. 等待完整认证、外壳与载荷摘要检查。读取只写本次私有编号槽位，归档内路径不直接成为当前写入目标。
4. 检查预检中的实际范围、冲突、警告、项目目标和凭据选择，确认停止并恢复。取消预检不会提交候选。
5. 提交完成后检查结果提示，再手动启动正式环境。数据恢复完成不证明模型凭据、插件加载或 Web 就绪。

[NativeRestorePlan](../app/src/main/java/com/deepseekharness/app/backup/NativeRestorePlan.java) 与 [NativeRestoreTargets](../app/src/main/java/com/deepseekharness/app/backup/NativeRestoreTargets.java) 对范围、逻辑根和目标白名单重新映射。项目目标支持私有恢复区或 guest home 中的恢复目录，不按归档提供的绝对路径写任意外置位置。确认后在 [MaintenanceCoordinator](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) 停止屏障内再次核对输入摘要和当前目标，再由 [HostDataTransaction](../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java) 保存前态并提交。

桥 token、PID、设备授权和旧事务所有权不能从归档生效；恢复后重新生成桥 token。本机原生设置只接纳白名单；原生 Key 只有明确选择时才投影并经本机加密。系统插件字节由当前签名 APK 重建，归档保留启停意图；用户插件、可执行配置和共享补丁按恢复规则隔离，需另行检查启用。

`DATA_RESTORED_PLUGINS_QUARANTINED`、`DATA_RESTORED_SETTINGS_REVIEW`、`API_KEY_OMITTED`、`API_KEY_MISSING` 各有含义，不能统一改写为无条件成功。恢复历史 v1–v4 先由 [LegacyBackupImporter](../app/src/main/java/com/deepseekharness/app/backup/LegacyBackupImporter.java) 私有转换后走同一预检；历史兼容以实际样本和绑定版本记录为准。

## 维护、旧树和失败处理

完整格式化与彻底重装的生产擦除能力已退休。维护不要求卸载、清数据或删日志；外置工作区只能断开应用私有引用，不遍历删除、覆盖或改名其内容，也不能重放旧日志中的外置写删。受管环境更新/重建由各自事务处理，普通 APK 更新不是重建用户数据的理由。

[RetainedCatalogue](../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java) 和 [RetainedDataActivity](../app/src/main/java/com/deepseekharness/app/ui/RetainedDataActivity.java) 提供只读清单、逐项检查、导出和预检恢复；旧树仅使用自己的映射，不沿旧链接读当前数据。未知和损坏项单列，同名冲突保留当前内容，不能用损坏一条记录阻断其他可读副本。健康前代清理由受管证明和逐根摘要约束，不由备份“三份保留”规则推导。

遇到停止退出未确认、输入摘要变化、Provider 不可读或 pending 维护时，保留当前状态并从原生记录/恢复入口处理。不能删除日志绕过屏障、用裸 PID 强杀，也不能自动重新运行未知写任务。

## 源码与验证入口

- 一键与目标：[OneClickBackupTest](../app/src/test/java/com/deepseekharness/app/backup/OneClickBackupTest.java)、[OneClickBackupLifecycleTest](../app/src/test/java/com/deepseekharness/app/backup/OneClickBackupLifecycleTest.java)、[BackupDirectoryTargetTest](../app/src/test/java/com/deepseekharness/app/backup/BackupDirectoryTargetTest.java)、[BackupDownloadsPublicationTest](../app/src/test/java/com/deepseekharness/app/backup/BackupDownloadsPublicationTest.java)。
- 计划和保留：[AutomaticBackupPolicyTest](../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyTest.java)、[AutomaticBackupPrunerTest](../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerTest.java)。
- 格式和恢复：[BackupArchiveTest](../app/src/test/java/com/deepseekharness/app/backup/BackupArchiveTest.java)、[HostDataTransactionTest](../app/src/test/java/com/deepseekharness/app/backup/HostDataTransactionTest.java)、[RetainedCatalogueTest](../app/src/test/java/com/deepseekharness/app/backup/RetainedCatalogueTest.java)。

这些链接用于选择关联回归，不表示本页重新执行了测试。F10 的软件/实际 APK 结果见[F10 发布记录](releases/v0.2.1-rc.2-build190-prerelease-20261020.md)；本轮未重做手机备份、Provider 矩阵或全历史数据恢复验证。
