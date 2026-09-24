# 宿主配置检查点与恢复

“重置配置”入口另外使用 `NativeConfigurationReset`，将 settings.yaml 与当前私有工作目录的 .env 作为两根事务保存和切换。记录在 `host-backup-operations/<UUID>/config-reset.json`，中断由统一宿主恢复入口处理；不影响下述六文件检查点的固定范围。凭据先检查解密，失败时不生成空配置覆盖原件。外部目录、配置软链接或跨文件系统目标不强行重置。

`StartupRepairs` 的 list、prepare、healthy、before、new、restore、recover 已由 Android 宿主文件系统执行。插件管理的安装/删除继续使用独立的既有管理器；它失败不会阻止原生配置记录显示。

只处理六个固定文件：`profiles/web/package.json`、`profiles/web/cordis.patch.yml`、`profiles/web/pnpm-workspace.yaml`、`profiles/web/pnpm-lock.yaml`、`settings.yaml`、`cordis.patch.yml`。不遍历会话、附件、工作区或 KeyVault。符号链接与不可读路径明确报错，不作为空数据处理。

新检查点位于 `files/startup-config-snapshots/<UUID>/`。0–5 为独立正文文件，`metadata.json` 记录存在状态、大小和 SHA-256；单文件最多 4 MiB。生成后重新读取来源和私有副本，校验通过才更新小型目录索引。保留三次健康检查点和三次修复前检查点；启动前 candidate 也单独引用。恢复期间选中的检查点会固定，避免被“修复前”轮换抢先删除。

健康检查点必须同时满足启动 ID 和六文件内容与启动前捕获值一致。界面变化、源文件变化或检查点损坏不能产生虚假的健康记录。已取消的“按启动次数全量备份”没有恢复。

旧版 v1 JSON/base64 检查点仍可读取，限制整份 36 MiB、单文件 4 MiB，并逐个解码/校验。它们在界面中以 `legacy-healthy-*` / `legacy-before-*` 标识，不执行 Python，也不任意反序列化配置内容。

变更写入 `files/startup-config-operations/<UUID>/`，复用 `HostDataTransaction`：先保留修复前检查点，准备候选和逐文件前后摘要，再写 switching 意图，切换文件并确认，最后 finalized。配置正文不经过命令行。原生偏好及 Keystore 不属于这一六文件事务的写目标。

处理中断的旧 pending.json 时先固定私有输入副本。Native 事务已经 finalized 而旧日志尚未搬走的情况，只收尾旧日志；不会再次套用旧配置覆盖后来的用户改动。仅剩旧状态标记而没有日志时，将标记保留到私有记录，当前配置不变。

原生界面会列出检查点中原本缺失、恢复时将回到缺失状态的固定文件。部分配置修复只作用于所选固定文件；Rootfs 损坏、配置检查点损坏、缺少日志和恢复成功分别记录，不以“新建空目录”伪造环境就绪。

验证见 `ConfigurationSnapshotsTest` 和 `ConfigurationCrashProcess`。进程强杀测试使用真实临时目录；Windows 下没有进行目录掉电持久性测试，Android 设备验证仍按用户要求延期。
