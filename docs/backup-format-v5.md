# 宿主数据归档 v5

build 131 增加向后兼容的 `dataCompatibility` 元数据：记录模型 schema 与所观察的运行时/读写实现摘要；没有可靠格式声明时 `formatId`、`formatEpoch` 为空，状态为 `unknown`。APK 版本、运行时版本和数据格式不是同一代次。发件人的声明不构成本机验证；预检继续显示格式未确认，不据此盲目降级或自动删除原件。已有明确范围的宿主恢复仍适用。保留副本和旧树入口见 [稳定性验收](stability-acceptance.md)。

实现位于 `backup/BackupArchive.java` 和 `backup/PortableBackupCrypto.java`。集成与验证状态见 [专项验收记录](backup-upgrade-acceptance.md)。v5 不修改历史 v1–v4 的版本含义；现有 Python 引擎实际写入格式为 4。

## 便携文件

便携文件使用 `DSHA-data-v5-<UUID>.dshbak`。它不是 tar/gzip，也不能使用会被旧版判为全量恢复的 `DSHA-backup-*.tar.gz` 名称。当前导出只提供密码加密，没有自动明文降级。

所有多字节整数按大端存储。加密头共 48 字节：

| 偏移 | 长度 | 含义 |
|---:|---:|---|
| 0 | 8 | ASCII `DSHABAK5` |
| 8 | 4 | 格式版本，值为 5 |
| 12 | 4 | PBKDF2 迭代次数，新文件为 600000 |
| 16 | 4 | 算法套件，值为 1 |
| 20 | 16 | 随机 salt |
| 36 | 12 | 随机 GCM nonce |

套件 1 使用 PBKDF2-HMAC-SHA256，输出 32 字节 AES 密钥，再用 AES-256-GCM 加密。完整头部作为 AAD；头后为密文，最后 16 字节是 GCM 认证标签。每次导出独立生成 salt 和 nonce。读取只接受 600000–1200000 次迭代；不可信参数在派生前拒绝。

密码直接编码为 UTF-8，不执行 Unicode 归一化。当前接口接收 12–1024 个 Java UTF-16 单元。密码、派生密钥与中间缓冲不写入任务记录或偏好；任务结束清理可清理的内存数组，但不承诺消除虚拟机或闪存中的所有历史副本。

解密流在认证标签核验前可能产生明文，因此只能写本任务的私有隔离文件。只有 `doFinal` 成功且外层读完后，才调用归档解析与恢复计划。SHA-256 用于内容完整性，不证明发送者身份。排除原生 API Key 不等于所有内容已脱敏：聊天、项目、`.env`、插件配置也可能含秘密，仍须加密。

派生参数依据 [OWASP 的 PBKDF2-HMAC-SHA256 建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。密码学实现使用锁定的 Bouncy Castle 轻量 API，不替换系统 Provider。库版本、摘要和许可见 `tools/backup-dependencies.lock.json` 与 `THIRD_PARTY_NOTICES.md`。

## 明文容器

容器为流式、未压缩的记录序列：

1. 8 字节 ASCII `DSHADATA`，随后 4 字节版本号 5。
2. 每条记录为：4 字节 JSON 元数据长度、UTF-8 JSON、8 字节载荷长度、载荷。
3. 4 字节 `-1` 表示记录结束，随后为 4 字节清单长度和 UTF-8 清单。
4. 32 字节 SHA-256 覆盖此前所有容器字节，包括结束标记、清单长度和清单内容。摘要之后必须立即 EOF。

记录必需字段为 `root`、`path`、`kind`、`scope`、`size`、`sha256`。`mode` 为可选的 0–0777 权限值。`FILE` 带逐文件 SHA-256 和实际载荷；其他种类必须为零载荷、空摘要。种类包含 `DIRECTORY`、`LINK`、`MISSING`、`UNREADABLE`、`EXCLUDED`；因此明确的空目录、缺失目录和读取失败不会混同。链接带 `target`，失败/排除记录可带 `reason`。

范围为 `application`、`sessions`、`settings`、`plugins`、`projects`。`application` 逻辑根可包含不同分类的记录；声明为某个具体范围的根必须只含该范围。Android 导出按数据子根拆分，恢复目标由本机映射决定。未知逻辑根或范围不会自动升级成应用全量恢复。

清单必需包含格式、条目数、载荷字节数、创建时间、应用版本、运行时来源、操作类型、数据格式声明、敏感内容策略、插件依赖信息、逻辑根描述及完整性等级。根描述与实际载荷的根集合必须一致；重复成员、冲突路径、错误尺寸、摘要、截断或尾随字节均拒绝。

完整性等级：

- `QUIESCENT`：已停止应用写任务，并对来源二次核验。
- `EXTERNAL_CHECKED`：应用写任务已停，对仍可能被外部应用修改的源重复读取校验；不宣称冻结了其他应用。
- `BEST_EFFORT`：只读救援，没有应用级静止保证。
- `PARTIAL`：存在缺失、不可读或变化项，具体条目/来源在清单中记录。

## 上限和路径

载荷总量最多 16 GiB，记录最多 100000，逻辑根最多 512。单条元数据最多 16 KiB、尾清单最多 2 MiB、累计记录元数据最多 32 MiB，路径长度最多 2048 字符、层数最多 64、链接深度最多 40。容器/加密流另保留最多 256 MiB 结构开销。计数依据实际读取/写入量。

拒绝绝对写入路径、盘符、反斜杠、NUL、空路径段、`.`、`..` 和换行。根内空相对路径代表逻辑根本身。冲突键采用 NFC 加大小写折叠，以保守拒绝跨文件系统可能碰撞的名称；遇到不支持的命名组合会报错，不悄悄丢掉条目。

解析器将载荷写到 `payload/<序号>` 私有槽位，归档路径不直接作为活跃写入位置。Android 目标映射对白名单角色/名称重新校验：原生设置经白名单导入；插件、可执行脚本、共享 Cordis 补丁进入隔离区；PID、环境标记、桥凭据和旧事务状态保留在输入副本中，不应用到当前设备。

## 历史读取

`LegacyTarReader` 校验 tar 头、PAX/GNU 长名限额、成员类型与外层 gzip CRC/ISIZE，并读到完整 EOF。它拒绝特殊文件、稀疏扩展、路径逃逸、重复成员、拼接 gzip 和尾随数据。旧软/硬链接只能引用包内已验证载荷，不能读取归档声称的宿主路径。

`LegacyBackupImporter` 将识别的 v1–v4 私有转换为 v5，再走同一预检流程。v3/v4 必须核对清单 inventory 与载荷双向一致。旧包缺 scope 只有在识别出明确历史命名时才进入需要确认的导入流程；未知 scope 拒绝。当前历史覆盖仍以合成样本为主，不能据此宣称所有旧用户路径已验证。

## 独立固定向量

`tools/generate-backup-vector.mjs` 使用 Node/OpenSSL 生成 `app/src/test/resources/backups/v5-aesgcm-vector.json`。其中的密码、salt、nonce、明文和密钥全是公开合成测试数据，不用于生产。`PortableBackupCryptoTest.independentOpenSslFixedVectorAndJceInteroperate` 验证 Bouncy Castle 解密和 JCE 派生/解密均得到同样结果。

## 导出完成边界

私有产物经过整份读取和重新解密验证后才写入新建 SAF 目标。目标能读回时比较整个密文摘要；无法读回时显示“已写入，目标读回未验证”，保留私有验证产物，不更新成功备份 latest。取消或失败不会覆盖前一份成功目录记录。

原生页面可以重新导出保留的私有加密副本。`VerifiedBackupCopy` 读取原 `verified.json`，检查类型、大小、格式标记及整个密文 SHA-256 后才允许复制；没有重新加密，因此仍使用原密码。此复核依赖本机先前完整验证的记录，不声称是发送者身份认证。新记录保存 `requestedScope`，早期记录从已声明的逻辑根推导范围；缺失或未知范围拒绝。重新导出不提高 PARTIAL、BEST_EFFORT 或插件依赖警告的完成等级，无法读回新目标时仍不更新 latest。
