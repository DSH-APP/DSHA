# rc2.1 备份/升级专项基线（2026-09-13）

HEAD: dca04aed7c1a1468827a953bfd6295fc3ca44170，main。开始时已有 #65、Bash 就绪检查、独立 Python 维护工具等未提交修改；完整差异保存在 app/build/backup-upgrade-baseline/preexisting.patch。本轮不回滚、不提交、不发布、不操作手机。

- 当前实际备份写入格式是 tar.gz / manifest formatVersion 4（脚本开头注释为 v3，不能据此推断）；支持旧 v1/v2/v3/v4，个人项目迁移另用 version 1 清单。
- 旧数据主要位于 files/linux/ubuntu/root/.dsh，sessions/storages/attachments 可能是到公开 dshdata 的链接。个人项目另由 environment-data.py 选择。原生设置和 Keystore 密文在旧 SharedPreferences / KeyVault。
- 锁顺序：EnvironmentTaskGate → 停止 Web → RuntimeTasks 维护屏障 → BackupManager.LOCK/dataOwner → 数据操作。DocumentsProvider 写入保留 RuntimeTasks。
- Python 恢复先写 root 内日志，再按目录切换；原生设置使用 SharedPreferences 的前态串，跨层日志尚未统一。
- RuntimeUpdateTransaction 在静态命令校验后 commit/cleanup，尚无候选网页健康确认；环境身份仍绑定 APK 版本码。
- 暂停前的 rc2.1 包不是此次专项完成品。新增宿主核心验证并接入之前，不把它们当最终交付。

## 后续实现及验收清单

宿主数据定位与严格文件访问；流式 v5 归档与 AEAD；预检和可中断事务；旧 tar 只读适配；SAF 导出/项目选择与原生救援入口；可迁移设置投影与系统备份规则；独立运行时描述与候选试运行/保留上一健康版；宿主配置修复；强杀恢复及恶意输入矩阵；双 flavor 单测/Lint/Release 与同证书验证。

真机验证按用户最新指令延期，禁止唤醒/操作手机。所有未执行的设备项目必须在 docs/backup-upgrade-acceptance.md 明示，不记作通过。
