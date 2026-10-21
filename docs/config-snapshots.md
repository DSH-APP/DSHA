# 配置快照、配置重置与设置迁移

> 核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；按当前 build190 工作树的宿主配置链核验。本次是文档补齐，没有新增设备或中断恢复实测。原有[宿主配置恢复说明](configuration-recovery-host.md)保留其历史验证归属。

DSHA 有三类不同的配置保护：启动六文件快照保护 DSH 启动配置；“重置配置”处理共享设置和当前私有工作目录 `.env`；宿主设置迁移只导入定型白名单。三者范围不同，不能将任一称为完整数据备份。需要对话、附件或插件原件时使用[备份与恢复](backup-and-restore.md)。

## 启动六文件快照

[ConfigurationSnapshots](../app/src/main/java/com/deepseekharness/app/backup/ConfigurationSnapshots.java) 只处理当前 DSH 数据根下六个固定相对路径：

| 路径 | 作用范围 |
|---|---|
| `profiles/web/package.json` | Web profile bundle、依赖与 profile 设置 |
| `profiles/web/cordis.patch.yml` | Web profile 补丁 |
| `profiles/web/pnpm-workspace.yaml` | Web profile 工作区依赖设置 |
| `profiles/web/pnpm-lock.yaml` | 已有 Web profile 锁 |
| `settings.yaml` | DSH 共享设置 |
| `cordis.patch.yml` | DSH 共享补丁 |

快照不遍历会话、附件、工作区、插件实体或 Keystore，不导出原生偏好。路径为链接、不可读或超限时明确报错，不将错误读成缺失/空配置。快照会保存“原本缺失”这一事实；恢复时相应固定文件可能回到缺失状态，界面会列出。

新快照位于应用私有 `files/startup-config-snapshots/<UUID>/`。正文编号 0–5，`metadata.json` 记录存在状态、尺寸、摘要、创建时间与来源 DSH 版本；单文件上限 4 MiB。捕获后重新核对源内容和私有副本，再更新目录索引。目录保留三份 `healthy-*`、三份 `before-*` 和独立启动 `candidate` 引用；异常原件不作为正常轮换对象删除，恢复选中的快照会固定到本次操作结束。

### 何时成为健康快照

启动前先捕获 candidate，记录本次 startup ID。只有当前代次已报告浏览器就绪、未进入停止、且六个文件仍与启动前候选完全一致时，[StartupRepairs.healthy](../app/src/main/java/com/deepseekharness/app/core/StartupRepairs.java) 才保存健康引用。仅启动了进程、页面跳转或一次鉴权成功都不能替代这一条件；启动中用户改配置会使候选不再符合，不产生虚假的健康记录。

“健康”在这里说明该启动配置与对应就绪记录关联，不证明所有插件、模型提供商和会话数据正确。快照元数据中的 DSH 版本也不是数据格式兼容证明。

## 原生恢复操作和生效时机

[StartupRecoveryActivity](../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java) 不依赖 Web，可查看最近启动记录、选择配置快照、创建所选默认配置或处理未完成配置操作。界面通过 [BackupTask](../app/src/main/java/com/deepseekharness/app/core/BackupTask.java) 调用 `StartupRepairs`，写操作必须持有维护门。

1. 从启动失败/恢复入口查看列表，确认是健康或修复前快照、创建时间、缺失文件及来源 ID。
2. 查看并确认修改范围。操作会停止 Web、终端与写任务，并先保存修复前六文件快照。
3. 完成宿主事务后再重试启动。成功文案只表示配置事务完成，不自动宣称环境已就绪。

“新建 Web 启动配置”替换 `package.json` 和 Web `cordis.patch.yml` 为当前宿主默认内容，不凭空重新安装已卸载插件，也不将四个 profile 文件统称全部重建。“共享设置”只新建 `settings.yaml` 为 `{}`；“共享补丁”只新建顶层 `cordis.patch.yml` 为 `[]`。旧正文已进入修复前快照；会话、插件文件和原生 API Key 不在这组写目标内。

恢复绑定用户选择的 slot 与 ID，提交前重新加载并核对。快照损坏、slot 已轮换或源变更时，`RECOVERY_CHECKSUM`、`RECOVERY_CHANGED` 等错误阻止写入。快照原来缺失的文件通过明确 removal 计划处理，不以空文件替代。

## 中断和旧检查点

变更日志位于私有 `files/startup-config-operations/<UUID>/`，复用 [HostDataTransaction](../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java) 的候选、前后摘要、switching 意图与 finalized/rolled-back 终态。正文不通过 shell 命令行传递。未完成日志会阻止普通启动，应在统一恢复入口处理，不能删除日志绕过。

旧 v1 JSON/base64 检查点仍能读取，整体上限 36 MiB、单文件 4 MiB，界面标作 `legacy-healthy-*` / `legacy-before-*`。先固定和校验输入副本，再准备当前宿主事务，不执行 Python 或任意反序列化配置。已 finalized 的旧操作只做归档收尾，不能再次把旧快照覆盖后来用户修改。若只剩旧状态标记而无可回滚日志，保留标记并保持当前配置原样，界面明确显示这一区别。

## “重置配置”是另一条事务

[NativeConfigurationReset](../app/src/main/java/com/deepseekharness/app/backup/NativeConfigurationReset.java) 将 `settings.yaml` 和当前私有工作目录 `.env` 作为两根处理，使用检查后的原生凭据构建候选，并在原 [HostDataTransaction](../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java) 中保存和切换。其记录为 `host-backup-operations/<UUID>/config-reset.json`，中断由统一宿主恢复入口识别。

它不会重置六文件快照、清空插件/会话或格式化 Ubuntu。凭据暂时不可读时失败并保留原密文，不写空密钥；外部工作目录、配置链接或跨文件系统目标不强行重置。配置写入完成后仍需重新启动并验证运行结果。

## 宿主设置导入和合并

v5 手动备份通过 [ConfigStore.exportPortableSettings/prepareHostSettings](../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) 投影端口模式/端口、工作目录、工具权限模式、shell 确认、桌面 UA、更新检查、节能、PiP 布局/开关、主题及语言。只有归档中存在的白名单字段才合并到当前设置，未出现字段保留；非法端口和过长/NUL 路径被拒绝。原生 API Key 默认排除，用户明确包含/恢复时先经本机 Keystore 加密并读回验证，凭据不可用不能折叠为空串。

恢复设置不导入 ADB/Root/Shizuku 授权、屏幕许可、运行 PID、会话 token 或自动备份密码。端口、工作目录等运行参数在下一次启动使用；语言、主题和网页显示设置还受各自界面重建/网页加载链约束，导入完成不代表已经改变当前运行中的网页。模型和提供商的生产编辑入口在 Web，不由旧原生配置页重新接管。

系统迁移还有独立的 [PortableSettings](../app/src/main/java/com/deepseekharness/app/data/PortableSettings.java) / [PortablePreferenceProjection](../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java) 白名单投影：仅当现用偏好尚无可迁移字段且投影版本匹配时自动导入，现用设置优先；该投影不复制密钥、权限、自动备份偏好或运行状态。它与用户主动确认的 v5 恢复不是同一次合并过程。

## 修改和验证入口

[ConfigurationSnapshotsTest](../app/src/test/java/com/deepseekharness/app/backup/ConfigurationSnapshotsTest.java) 覆盖捕获、轮换、源变更、固定快照和事务；[LegacyConfigurationSnapshot](../app/src/main/java/com/deepseekharness/app/backup/LegacyConfigurationSnapshot.java) 约束历史读取；[ConfigStore](../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) 与凭据四态是设置导入入口。新增配置字段时，须分别判断是否属于快照正文、用户备份、系统迁移白名单和本机专有状态，不能一并导入。

本页只核对调用链和相对引用。历史强杀/临时目录测试不等于 Android 掉电持久性；未对本轮包做真实恢复、卸载迁移或所有历史数据矩阵复测。
