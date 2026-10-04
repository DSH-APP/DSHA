# G3 备份入口与兼容证据

普通界面在本轮之前已经使用宿主实现：`WorkspaceFragment` 的备份和数据位置按钮打开 `NativeDataActivity`，导入 URI 也交给这个页面。没有新增“普通恢复执行 rootfs Python”的路线。旧 `BackupTask` / `BackupManager` API 仍用于明确过渡维护和历史事务，不以未复核的最低版本号删除它们。

宿主读取路线：`NativeBackupJobs` 先将输入复制进私有编号槽位，按内容识别 `PortableBackupEnvelope`、`PortableBackupCrypto` 或直接 `BackupArchive`；其它输入交给 `LegacyBackupImporter`。密文先完成 AEAD 认证，之后 `NativeRestorePlan` 按选择的范围预检，在原生确认与停止屏障内建立候选、复核输入摘要并执行 `HostDataTransaction`。插件依赖图在隔离 store 重建，系统源码由当前受管证明提供。成功提交后才轮换当前桥 token。

旧过渡导出现在不复制整个工作目录 `.env` 或未经脱敏的 `dsh-web.log`，原始文件保留。原生 API Key 仍只按明确选择的 native config 注入。`credential-paths.json` 给 Java 和 Python 提供同一机器凭据名单；归档里的机器 token / 安全模式记录不覆盖本机当前状态。回收站只在 sessions 根排除，不影响项目中的同名目录或用户明确选择的项目 `.env`。

实际宿主回归：最新 46 项 JUnit 全通过，涵盖历史无 manifest 与 v1–v4 最小合成 tar/gzip 样本、`.dshbak` 后缀的内容识别、sessions 局部范围不扩张、坏 inventory / 范围拒绝、未知记录保留、v4 用户插件图及可执行脚本字节保留；另覆盖 v5 密文往返、错误密码、截断和篡改拒绝、独立 OpenSSL 固定向量、无密码 tar.gz 封装与归档完整性。样本输入摘要与原件字节未改变，转换不执行归档脚本。

这证明所测格式的读取和预检契约，不证明全部历史真实用户数据、所有旧加密格式或设备矩阵。未识别或不受支持输入继续拒绝或保留到预检区，不静默丢掉未知记录。未操作手机；正式 Android 两 flavor 编译、Lint 和交付门禁由主控统一执行。
