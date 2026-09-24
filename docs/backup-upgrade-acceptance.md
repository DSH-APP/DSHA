# build 131 后续验收说明

2026-09-14 的稳定性修订补齐保留副本管理、旧树只读救援来源、隔离插件审阅/失败恢复及独立数据格式证据模型。当前实现与真机范围以 [稳定性逐项验收](stability-acceptance.md) 和 [build 131 交付](releases/v0.1.5-rc2.1-build131.md) 为准；以下 build 130 的历史结果和当时未完成项原样保留，不能误读为 build 131 当前状态。未知历史格式仍未被证明可降级，原件保护与不自动删除继续适用。

# rc2.1 数据保护改造验收记录

状态：**按用户收尾要求，rc2.1 已完成本轮验收并本地打包交付**。最终产物及验收边界见 [build 130 交付记录](releases/v0.1.5-rc2.1-build130.md)。这不代表完整专项所有扩展矩阵已完成；早期 `app/build` APK 与以下分阶段旧记录不能代替最终证据。

交付文件为 `release/dsha-0.1.5-rc2.1.apk`、`release/dsha-0.1.5-rc2.1low.apk` 及各自 SHA-256；版本码 130，包名 `com.dsh.client`，继续使用历史 E7E3… 发布证书。用户恢复真机验证后，又明确要求“快速验收完收尾，然后把 apk 打包好”；本轮未上传 GitHub，未覆盖原手机安装。

## 最终收尾证据与范围

- `rc21-final-release-build.log`：两版各 **491 项，490 通过、1 原有跳过、无失败/错误**；两版 Release Lint、完整离线 Release 构建成功。最终增加三项旧副本清理保护测试，未知/修改过的原件不删除，也不充当健康保留数量。
- `rc21-final-apk-assets.json`、`rc21-standard-elf.json`、`rc21-low-elf.json`：实际 APK 的离线包、内置插件、锁定软件包摘要和 arm64 / 16 KB ELF 静态检查通过。
- `rc21-final-delivery.json`：两份实际 Release 为非调试、原包名、版本码 130，证书与已有 rc2 相同；low 的 V1 验证通过，标准版按其最低 API 30 验证 V3。四个交付文件已复制到 `release`。
- 最后 #67 真机回归：标准版 `38182f6e-0977-484f-b4d1-08e1024e58c7`、兼容版 `58e4d546-b4db-4ac2-96b3-abed18a9e4c0`，各五项通过。失败样本 `b63fad03-7ded-4a35-be5c-0ca14db93364` 保留：前台检查失败后观察到设备超时息屏；测试补充交互状态及夹具就绪断言，唤醒后复验通过，生产导航逻辑未因该失败修改。

本次未完成的扩展为批量保留副本管理、旧树救援来源、隔离插件完整审阅启用流程，以及跨运行时的数据格式代次关联。未接入的 `RetainedOperations` 原型保存在 `app/build/backup-upgrade-baseline/retention-prototype`，不编入发布 APK。现有容量上限仍采取保留原件并明确停止的策略，不以未验证清理来绕过上限。历史布局、低空间和系统迁移组合及未连接设备的覆盖限制继续记录，不宣称已通过。

下方保留分阶段原始记录；其中当时的“未接入”“未打包”状态以本节和交付记录为准。

## 2026-09-13 真机验证

设备：Redmi M2012K10C，Android 13 / API 33，arm64，4 KiB 页，系统 WebView 116.0.5845.92。验收 APK 是与 Release 共用生产代码的 **非调试构建**，独立包名 `com.dsh.client.rc21audit`，沿用历史发布证书。原安装 `com.dsh.client` 的 rc2 / 129 未被覆盖；所有故障数据、临时 Bash 隔离和强杀进程均属于验收安装。

构建使用 `tools/device-backup-audit.init.gradle`，运行 `tools/run-backup-device-audit.py --serial <serial> --apk <deviceAudit.apk> --mode core|runtime|workflow|ui`。脚本安装前核对包名、非调试标志和证书，只接受独立验收包；报告保存在本轮新建的 `app/build/backup-device-validation/<UUID>/`。测试代码和 ContentProvider 不进入常规 Release。

| 实际检查 | 报告目录 UUID | 结果 |
|---|---|---|
| 标准版宿主文件、加密、链接与事务 | `447d20ed-9994-4ff7-b552-b92ef7779f2a` | 13 项通过；包括 5 次真实独立服务进程死亡，覆盖文件、设置和回滚边界 |
| 兼容版同一宿主测试 | `2b7dc2ae-57a3-451d-867e-13a1e45fdf1a` | 13 项通过；最低 API 设备仍未测 |
| 标准版冷重建、测试数据保留与系统 WebView 握手 | `caddf489-dc32-417d-85d4-b310d763d1f1` | 通过；首次从 APK 解压，实际端口、鉴权、JSON 后端及 Session 写后重开、浏览内核与进程退出确认 |
| 兼容版受管更新与 Gecko 握手 | `ef9e6d24-d884-4149-8fae-cca6a698bcd3` | 通过；此轮实际网页试运行仍由 proot 执行，不能算 proroot 网页验证。两种方式的稳定数据根绑定均已验证 |
| 兼容版无 Bash 的应用级备份/恢复 | `f565d37e-0abc-4eef-93c3-01d732dbbf0d` | 7 项通过：原生救援页、无 seek 的管道目标、加密导出、密码错误保护、项目预检/事务恢复、读回与写入失权、取消。项目中的隐藏文件及自定义依赖逐字节校验；失败不替换 latest |
| 真实 proroot 失败后的兼容试运行 | `3171f2d6-782b-4e5b-93a2-060d877c09a1` | 通过：该设备 proroot 触发 glibc 线程栈断言，以 139 退出；核验 guest 退出后只重试一次 proot，Gecko 与后端健康确认后提交。回执明确 `runtimeMode=proot`、`fallbackFrom=proroot`、`fallbackExitCode=139` |
| 标准版最终运行逻辑、受管更新和 WebView 116 | `02b61da6-a4de-42e0-a1df-6e8e14717475` | 通过：实际 proot、鉴权、数据写后重开及网页握手；并验证两种运行方式的稳定数据根绑定 |
| 标准版无 Bash 的应用级备份/恢复 | `15c0b38e-5c86-41b8-bdb3-7f632f911f1e` | 同上 7 项通过；真实 Android ContentResolver 和无 seek 管道，并非 JVM 模拟 |
| 全应用 `style` 中文 | `7dd42e6f-8d9e-45e4-bbc7-88708410be80` | 3036 文字状态、618 居中、90 颜色像素检查；日夜、320dp 短屏、1.3 字体及弹窗通过 |
| 全应用 `style` 英文 | `4cfa813a-9b73-47c1-9e90-9306ab7045f5` | 同上通过，最低测得文字对比度 4.78；抽查权限页和中英文关于弹窗图片 |
| 兼容版救援页与真实旋转 | `342acc4c-3f02-4d73-b99e-1f6da17b2d47` | 8 组语言/主题/短视口，加真实横竖屏，共 10 项、190 文字检查通过；重建保留范围和 API Key 选择，不新建作业。该轮截图因未抵消 ScrollView 滚动坐标不能用来证明底部画面，后续已修正捕获并复验 |
| 标准版救援页与真实旋转 | `62cffe4f-694b-4f2c-82d3-f66ede9df5f1` | 10 项通过，确认短视口可以滚动到最底部；修正后的截图已检查，包含大字体英文底部按钮及真实横屏 |
| 两版原生配置重置与真实 Keystore | `d2a5dbab-76e8-4fd2-a1ba-1d4a2bec333f` / `a2adfe80-6c4b-490c-b9d7-7bfea2af2b59` | 各 2 项通过：无 Bash 时通过真实 BackupTask 重置正确的 .env/settings.yaml，保留原件和凭据；损坏密文导致安全失败，不清空文件或凭据 |
| 两版 Linux 完全不可用时重导出 | `f87bd520-4411-4ddf-9d97-c00f7a4d8fa9` / `dea1480f-cc9f-4831-bb80-b7ecf41b8da3` | 各 3 项通过：整体移开测试 Linux 后重导出原加密字节，不需原密码；副本被篡改时不写目标/不改 latest；损坏记录不挡住其他副本。测试 Linux 在 finally 中恢复 |
| 含重导出入口的最终救援页复验 | `11958e28-f6e5-4229-b924-c6b5a98a1fff` | 10 项通过，覆盖两种语言、日夜、320dp 视口、1.3 字体、底部滚动、Activity 重建和真实横竖屏；截图已保存 |
| 两版真实文件服务阻塞取消 | `10a0a40b-52c7-4974-a1f5-d4c8b1827cc8` / `a3102059-ea5c-4908-a4d4-1dcd02b3e338` | 各 6 项通过：管道阻塞读写、Provider 打开/查询取消、AssetFileDescriptor 子范围和恢复输入取消；主线程不等待 Provider 的取消响应 |
| #67 两版插件、预设、维护与终端 | `1b45a83c-2459-426c-bebc-af60581ca148` / `5ba43bc5-9fd4-4767-8a7b-94cca3d764e2` | 各 5 项通过，详见 [#67 修复记录](issue67-recovery.md)。实际全局插件解析、旧 text 预设、维护防循环、真实 PTY 命令与进程回收 |

真机发现并修复：

- Android 对 `FileInputStream(FileDescriptor)` / `FileOutputStream(FileDescriptor)` 不转移描述符所有权，导致归档大量文件后句柄泄漏。改为 `ParcelFileDescriptor.AutoCloseInputStream/OutputStream`；修复前 250 次读取使句柄从 298 增至 548，修复后的两版均通过读/列目录/写/删除循环验收。
- 冷环境直接校验内置插件时，依赖链接尚未建立，出现 `@deepseek-ai/dsh-llm` 无法解析。冷解压和受管候选共用 `RuntimeTools.prepareBuiltinDependencies`，不以注册用户 profile 作为前置条件。
- 局域网设置补丁原来只进入冷安装路径，受管更新会漏掉并被第 6 步检查拒绝。补丁现与其他受管补丁一起在候选阶段应用，计算摘要和切换之前即完成。
- 隔离试运行此前固定走 proot，不能证明所选 proroot 模式可用。现按所选模式启动并把实际方式写入健康回执；新的双模式结果继续记录在下方，不沿用旧报告冒充通过。

`device-fallback-full-recheck.log`：兼容回退和失败日志修复后的两版各 477 项单测，476 通过、1 原有跳过、0 失败/错误；两版 Release Lint 通过，仍有已有警告。之后仅修改独立验收入口和截图捕获，不影响生产运行逻辑。

后续 `native-reexport-full-recheck.log` 为当前完整回归：两版各 **487 项，486 通过、1 原有跳过、0 失败/错误**，两版 Release Lint 通过。新增 `NativeConfigurationResetTest` 的 5 项包括重置与回滚中真正强杀子 JVM；`VerifiedBackupCopyTest` 的 4 项覆盖同尺寸篡改、旧私有记录范围、救援等级和空插件图。`UiStateTextTest` 验证缓存重置结果换语言时仍保留原路径。

`NativeConfigurationReset` 已替换重置入口的旧容器备份：只保留并事务切换两个明确配置文件，沿用原停止屏障和 `host-backup-operations` 中断恢复；修复 `/root/root/.../.env` 拼接问题。外部工作目录、配置链接和跨文件系统目标会明确拒绝且保留原件，这不是对所有旧外部布局的重置支持。`NativeBackupJobs.reexport` 已接入原生页面，只复用原来加密过且私有摘要再次通过的字节，不重新归档故障环境、不提升部分救援等级。

`DocumentStreams` 将查询、打开、读取、写入接入取消；原生导出/恢复/重导出和 SAF 项目源共用。读取保留 AssetFileDescriptor 的 offset/length，取消通知通过测试证明不会在 UI 线程等待阻塞服务。Provider 若拒绝响应所有关闭/取消手段，作业仍保留而不能假装成功或释放未完成的保护。

`issue67-final-source-checks.log` 更新了上述完整回归：两版各 **488 项，487 通过、1 原有跳过、0 失败/错误**，两版 Release Lint 通过。#67 的场景、已确认原因和覆盖边界另见 [修复记录](issue67-recovery.md)。没有删除原断言，也没有将诊断失败、维护未就绪或终端身份未知当作成功。

失败样本也保留：`be677082-58aa-4329-b137-c4df25fc657e`（冷插件依赖）、`7df74b64-eb53-442e-9f43-b13d7d9dc7f9`（候选遗漏局域网补丁）、`46419669-faa1-49dc-b4b0-9b06afc8f472`（proroot 原始断言与 139 退出）。部分 UI 尝试因 MIUI 前台切换、测试入口组件解析或测试字体断言失败，均保留 FAIL，不计入通过次数。最后改用与既有安装验收相同的 ActivityMonitor 和临时 instrumentation 后台启动权限；窗口打开后立即释放，不修改应用或系统持久权限。这个测试入口不属于正常 Release。

截至本轮只读复核，原安装仍为 rc2 / 129，更新时间为 **2026-09-12 17:44:24**。本轮没有发布 APK 覆盖或 GitHub 操作。

以下是此前分阶段记录，注明“未接入”的旧状态以本节及后续实际证据为准；尚未覆盖的故障矩阵与最终交付要求仍然保留。

## 基线和证据边界

- 修改前 HEAD 为 `dca04aed7c1a1468827a953bfd6295fc3ca44170`；基线与已有改动见 [基线记录](backup-upgrade-baseline.md) 以及 `app/build/backup-upgrade-baseline/`。
- 没有重置工作区、清理用户文件、提交、推送、打标签或变更签名。
- JUnit 文件测试使用新建临时目录及真实字节读写、移动、文件同步。Windows 不支持这里使用的目录 fsync，因此这些测试证明进程强杀后的协议收敛，**不证明断电持久性**。
- v1–v4 备份测试为按历史格式构造的合成样本，不是实际用户升级验证。`.l2s` 测试模拟 Android 链接元数据，载荷和归档读写使用真实文件。
- `tools/test-runtime-trial.mjs` 使用锁定版本的真实 JSON 存储后端与 Session 头部 API，但网页事件部分为合成事件；报告明确将 `androidRendererVerified` 设为 false。

## 当前已执行的检查

专项脚本：配置 BUILD.md 中的 JDK/SDK/Python 环境后运行 `python tools/verify-backup-upgrade.py`。脚本为每轮创建独立目录，强制重跑两 flavor 的宿主测试，将 JUnit 临时目录和 XML 报告限制在本轮目录；另运行桌面协议测试。`result.json` 逐测试记录结果，明确标注合成夹具与延期设备验证。脚本不调用 ADB，也不删除既有报告。

以下日志保存在 `app/build/backup-upgrade-baseline/`，后续代码改动仍需重跑对应检查，不能用旧日志证明最终工作树。

| 命令/范围 | 最近证据 | 结果和限度 |
|---|---|---|
| 标准版 backup 包单测 + 两版 Java 编译 | `runtime-integration.log` | 通过；早于后续回退与 L2S 改动 |
| 宿主运行时事务与真实 JVM 强杀恢复 | `runtime-crash-tests.log` | 通过；`HostRuntimeCrashProcess` 在健康验证后的提交边界被真实终止，并在回滚中再次终止 |
| 两版 backup 包单测 | `rollback-l2s-tests.log` | 通过；包含兼容回退、保留新版对话、旧 L2S 附件载荷等；早于随后新增的原生安全配置与 tar 清理检查 |
| `node tools/test-runtime-trial.mjs` | `runtime-trial-protocol.json` | 5 项通过，Node 24.18.0 / Windows；没有 Android 网页内核证明 |
| `git diff --check` | 本地命令输出 | 无空白错误；已有换行格式提示保留原状 |

本阶段最新证据：

- `runtime-checkpoint-recheck.log`：标准版与兼容版各 **455 项**，各 **454 通过、1 原有跳过、0 失败/错误**；两版 Release Java 编译及 Lint 通过，Lint 仍有已有和需人工核对的警告，不称“零警告”。
- `api23-full-recheck.log` 修复并复查了两个实际最低 API 问题：`O_CLOEXEC` Java 字段到 API 27 才公开，现使用 NDK UAPI 常量；SAF 树判断使用 AndroidX 的兼容接口，未提高 minSdk。
- 专项脚本首次运行因 AGP 覆盖 XML 输出目录而报告 `INCOMPLETE_OR_FAILED`，没有误用旧报告宣称通过。修正配置时机后，`app/build/backup-acceptance/e2980ba2-6d33-49aa-b5ab-1e67c0b4bd51/result.json` 为 PASS，夹具和 XML 均位于该轮目录。
- `runtime-trial-protocol.json` 现有 **7 项**通过，新增错误帧、无关消息和错误隔离目录不能充当握手的断言。监听锁定的 `/api/remote.mux` 的 `item / ready / clientId / host.home` 帧，不再将任意收到的消息当作后端就绪。
- 原生安全 profile 创建、密码不可用时不创建替代 Keystore 密钥、定型偏好投影、脚本/共享补丁隔离和本机状态不生效均已接入。宿主文件路径规范化与 proot 历史绑定路径保持分离，以免破坏旧 L2S 映射。

此前这一阶段尚未接入的宿主重建、配置检查点、稳定数据根和插件依赖图，已按后续记录接入。其中设备与历史矩阵仍未全部覆盖。当前归档可能声明 `UNINSPECTED` 数据格式；尚需把恢复后的数据代次与运行时可读关系关联，不能据描述文件单独宣称全部历史数据兼容。最终专项 APK 尚未打包或交付。

后续接入进展：

- 六文件配置检查点已由 `ConfigurationSnapshots` / `LegacyConfigurationSnapshot` 接入 `StartupRepairs`、`StartupRecoveryModel` 和维护门禁，不再要求 Python 可执行。`native-config-tests.log` 的 9 项测试通过，包含旧大于单条元数据限额的 base64 检查点、选中的旧快照在轮换期间保留、提交后不重放旧日志覆盖新配置，以及提交/回滚时真实强杀。
- `NativePluginGraph` 版本 2 增加 profile/shared 绑定和未声明依赖关系，并核对生成关系时读取的 package.json 未变化。`PluginRestoreGraph` 已接入恢复候选构建，重建依赖、命令及 profile 引用；恢复声明原件独立保留，插件不自动执行。`native-plugin-graph-restore.log` 的 8 项图相关测试通过，兼容版编译通过。
- 不同类型的未完成维护同时存在时，统一恢复入口会拒绝按任意顺序覆盖；原生配置按钮在运行环境损坏时仍可使用。没有日志的旧配置状态标记不会触发猜测性数据回滚。
- 当前仍待补齐：完整插件恢复后的用户审阅流程、数据格式代次关联、有界保留副本管理和旧树救援来源、历史布局扩展、SAF 阻塞 I/O 取消及最终签名交付。重导出、宿主重建、稳定根绑定、范围预览及项目恢复位置选择已接入；仍需扩展故障组合，不因此缩减其他要求。
- `config-graph-full-checks.log`：上述接入后的两版完整回归均为 **470 项，469 通过、1 原有跳过、0 失败/错误**；标准与兼容 Release 编译及两版 Lint 通过。尚未进行设备运行或最终 APK 打包。

## 要求与实现逐项核对

| 提示词范围 | 当前实现 | 仍需完成或补强的证据 |
|---|---|---|
| 1. 基线、定向阅读、不覆盖原改动 | 基线文件、README/AGENTS/BUILD 与真实调用链已检查 | 最终差异审核与未跟踪文件清单 |
| 2. Java、两 flavor、包名签名、停止屏障 | 保留 `BackupManager`、`EnvironmentTaskGate`、`RuntimeTasks`；试运行仅允许维护所有者进入 | 最终两版全单测、Lint、Release 和证书检查；进程行为真机延期 |
| A/B. 坏 Ubuntu 下的宿主救援及缺失/失权区别 | `NativeDataActivity`、`NativeDataLocations`、`AndroidBackupFileSystem`、`UnavailableBackupSource` | 完整数据位置登记、系统迁移后激活；基础重建仍有旧 Python 调用，必须接完宿主路径 |
| C. 新产物验证前不覆盖 latest | `NativeBackupJobs` 私有生成、重新解密校验、目标读回后更新目录 | 增加作业目录管理、重导出、ENOSPC/Provider 边界及强杀目录指针测试 |
| D. 输入验证前不写活跃数据 | `PortableBackupCrypto` → 私有隔离 → `BackupArchive` → `NativeRestorePlan` | 扩展恶意样本、计划/载荷篡改和部分范围测试 |
| E/H. 文件/配置事务及新版数据不回退 | `HostDataTransaction`，保留 previous/failed，finalized 后不自动回滚；真实 JVM 强杀测试 | 配置检查点和基础环境重建的宿主事务接入、旧日志恢复桥 |
| F. 运行时健康与保留 | `ManagedRuntimeTransaction` 接入 `EnvironmentMaintenance.update`；真实试运行回执才提交；前代文件摘要与健康回执绑定 | 旧 1.1.10/后续历史身份的一次性可信接入；真实 arm64 旧运行时回退延期 |
| G. 部分、不可读、未读回和隔离状态 | v5 清单完整性等级、`NativeBackupJobs.State` 独立结果码 | 完整中英文错误映射、部分插件恢复状态与所有旧入口一致 |
| 4. 应用/项目/受管/本机数据分类 | `NativeDataLocations`、`ManagedPackageProof`、`NativePluginGraph`；不按 node_modules 名字丢弃修改过的依赖 | 准确大小与排除预览、项目恢复位置选择、稳定布局激活和本机字段全面审计 |
| 5. 宿主流式、链接、限额、生命周期 | 原生 NOFOLLOW/fstat 与父目录描述符；逐文件暂存/二次读取；已接内层 `.l2s` 文件实际载荷；`DataProtectionService` 与共享取消信号 | 维护快照/重建接入、所有 SAF 阻塞 I/O 取消、保留树救援来源、旧挂载占位覆盖 |
| 6. 加密、格式、认证 | v5 `.dshbak`，PBKDF2-HMAC-SHA256 / AES-GCM，密码仅内存，原生 Key 默认不导出；v1–v4 原生只读适配 | 固定互操作向量、许可证与依赖校验记录、剩余历史布局/敏感范围用例；最低 API 真机延期 |
| 7. 预检/确认/提交、插件与部分恢复 | 私有固定输入、预览后才停止写入；可信本机目标映射；未知插件先隔离 | 插件依赖图重建与可运行状态判定、旧工作区特殊布局、冲突/目的地选择 |
| 8.1 完整/兼容/最新分离 | 构建生成 `runtime-descriptor.json`，独立资产与原生契约；普通启动不覆盖已登记受管文件；重试键使用候选 ID | 可信旧身份接入和完整纯 UI 更新验收，描述生成器固定向量 |
| 8.2 候选健康 | `RuntimeTrial`：隔离 profile、实际端口鉴权、真实 KV/Session 写后关闭重开、网页 WebSocket 握手、退出核验；rootfs 外日志 | 移动端真实完整流程延期；前台/旋转/Gecko 注入验证、失败候选提示与记录管理 |
| 8.3 原位数据保护、兼容回退 | 同基础只换受管树；原生回退入口不恢复旧对话快照；新旧运行时原件保留 | 基础重建还需宿主快照/迁移与峰值空间预算；未知文件和旧树保留管理 |
| 9. 原生界面、配置和系统备份 | 原生救援入口、回退入口、通知取消；安全 profile 创建已改宿主；迁移偏好投影和 XML 白名单已有初版 | 配置快照/修复仍需彻底脱离 Python；默认入口收敛；短屏/大字体/双语；系统迁移投影测试和合并清单审计 |
| 10. 故障验收矩阵 | JVM 真实文件/进程强杀、合成历史归档、真实桌面 JSON 后端 | 下表未完成项、统一机器可读专项脚本、完整历史边界样本 |
| 11. 构建与交付 | 两 flavor 约束、版本码/名称已准备 | 完整格式/映射/清理文档、AGENTS/用户说明/变更记录、最终全单测/Lint/签名 APK 与摘要 |

## 故障矩阵跟踪

| 场景 | 现有测试/证据 | 状态 |
|---|---|---|
| 无旧 Python/Node/proot | HostSnapshot、NativeSafeProfile 的纯 JVM 调用 | 宿主数据核心独立；完整原生 UI/重建闭环待接完 |
| rootfs 缺失、外部数据仍有内容 | NativeDataLocations 公开目录探测 | 待增加真实文件/权限组合测试 |
| 旧布局、L2S 实际载荷及循环越界 | GuestDataResolverTest、L2sSnapshotTest | 局部通过；挂载占位与全旧布局待扩展 |
| 自身嵌套、隐藏文件和自定义依赖 | HostSnapshotTest、NativePluginGraphTest | 已有测试；归档/历史产物排除待扩展 |
| 取消、强杀、空间满且 latest 不变 | HostSnapshotTest；作业目录逻辑 | 取消已有测试；作业层强杀/ENOSPC 待补 |
| 错误密码、篡改、截断、恶意 KDF | PortableBackupCryptoTest、BackupArchiveTest | 已有测试；固定向量和最大限额样本待补 |
| v1/v2/v3/v4、仅链接缺载荷 | LegacyBackupImporterTest、LegacyTarReaderTest | 合成样本通过；历史工作区/依赖图扩展待补 |
| 各部分恢复不动其他范围 | NativeRestorePlanTest | 需扩展分组热目录、配置与项目组合 |
| 源在 size/mtime 不变时被改写 | HostSnapshotTest | 二次内容摘要能发现变化 |
| 文件/设置边界强杀，回滚中再强杀 | HostDataTransactionTest、HostTransactionCrashProcess | 真实子 JVM 强杀通过；非断电验证 |
| 日志损坏、多未完成事务 | HostDataTransactionTest、HostPendingTransactions | 需补全多事务、旧日志和指针恢复组合 |
| 原生密钥处理失败 | KeyVault.decryptChecked、ConfigStore 预加密事务值 | 需专项故障注入与系统迁移测试 |
| UI-only APK 更新 | RuntimeDescriptorTest | 元数据单测；构建资产/真实旧安装验证待补 |
| 静态 Node 检查通过但网页失败 | ManagedRuntimeTransactionTest | 拒绝只凭静态证明提交；移动网页实测延期 |
| 旧运行时回退保留新版对话 | ManagedRuntimeTransactionTest | 真实文件回切与字节保留通过；实际旧 dsh 启动延期 |
| 停止 guest 不明 | ManagedRuntimeTransactionTest、RuntimeTasks/WebProcessManager 既有保护 | 不回切活跃树；非调试设备运行延期 |
| SAF 失权、无 seek/rename/读回 | NativeBackupJobs、SafBackupSource | 软件 Provider 场景与设备矩阵待补 |
| 双语、旋转、重复点击、前台服务 | 应用级作业状态、RuntimeBrowserProbe 重挂、DataProtectionService | UI/平台场景待验收，不宣称已通过 |

## 尚未覆盖的设备矩阵

用户已恢复真机验收，本轮已覆盖上述 API 33 / 4 KiB 非调试安装。没有连接标准版最低 API 30、兼容版 API 23/24、较新 Android 或真实 16 KiB 页设备；这些结果仍不可由当前手机、JVM 或桌面 Node 替代。进程强杀不等于断电测试，管道测试 Provider 不等于全部厂商云盘兼容验证。低空间、系统迁移和完整历史升级矩阵尚需继续补齐。
