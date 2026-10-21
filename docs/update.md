# 应用更新与受管运行时更新

> 核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；按本工作树 build190 的实际更新代码核验。本文未检查实时远端清单，也未安装 APK 或执行设备验收。

应用 APK 更新和 Ubuntu 内受管组件更新是两条链。APK 先通过下载与包身份检查，再交系统安装器；受管组件先审阅计划、正常停止运行任务、准备候选并试运行，再事务切换。两者都保留具体失败状态，不能用版本文案替代实际结果。

## 启动时检查更新

“启动时检查更新”默认开启，保存在[ConfigStore](../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java)；Main 调用[UpdateEngine.checkOnStartup](../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java)。每个应用进程至多自动检查一次，旋转、主题重建和从 Web 返回不重复请求；关闭开关后，后续启动入口不发起这次自动检查，手动更新页仍可检查。开关不取消已经开始的请求，也不自动下载或安装 APK。

检查读取固定 HTTPS 清单 `https://dsha.cc/api/updates.json`，要求 `schemaVersion=1`，清单上限 1 MiB。当前来源策略在[UpdateLocations](../app/src/main/java/com/deepseekharness/app/util/UpdateLocations.java)中，不因 README 的私有预发布下载链接而自动改为私有仓库。

清单请求使用 `DSHA/<VERSION_NAME>` User-Agent；接收服务会看到正常网络请求信息。该检查链未附加对话、终端命令或 API Key。它不是自动诊断上报；实时服务隐私政策、可用性与当前清单内容不由本次源码核验确认。

## 通道与候选

| 选项/操作 | 何时生效 | 当前规则与失败 |
|---|---|---|
| 稳定/预览通道 | 空闲时切换并立即重新检查 | 稳定通道只收 stable；预览可收 stable/preview；按更大的 versionCode 选择，不能按 rc 文件名猜先后 |
| 默认通道 | 无已保存选择时初始化 | 版本名含 `-` 默认 preview，否则 stable |
| 重新检查 | 用户点击，或首次进入无任务的更新页 | 网络失败保留当前通道上次结果；来源不明的旧候选不能直接安装 |
| 下载/继续下载 | 用户选择候选后启动应用范围的前台下载任务 | 离开页面/旋转不重新开始；取消保留部分进度，可再次请求继续 |
| 安装更新 | 完整校验后由前台页面领取一次结果 | 校验期间通道或候选改变则拒绝；进程重建中断校验后要求用户重试 |

[UpdatePolicy](../app/src/main/java/com/deepseekharness/app/util/UpdatePolicy.java)还检查当前 flavor、minSdk、`arm64-v8a`、版本码、有效 HTTPS 来源、64 位十六进制 SHA-256 与大小（至多 1 GiB）。旧候选记录的“查询通道”与发布自身通道分开保存，不能从当前偏好推断来源。

同为 code190 的 F10/F11修订即使版本名更晚，也不会被当前应用内更新器当作更高版本选中；更新器要求候选 `versionCode > BuildConfig.VERSION_CODE`。当前私有 Release 是否发布、附件摘要与人工下载请从[F11记录](releases/v0.2.1-rc.2-build190-fix11-20261021.md)、[README](../README.md)和实际 Release 核查；F11附件状态按实际Release及独立外部回执核验，不能由“暂无更新”推断远端没有预发布附件。

## 下载、校验与系统安装

固定来源只允许 dsha.cc 的下载路径与 DSH-APP/DSHA 的 GitHub Release APK 路径；重定向仍逐跳检查，GitHub 资产 CDN 有受限例外。域名允许列表不等于任意同名包受信任。下载使用[ResumableDownload](../app/src/main/java/com/deepseekharness/app/util/ResumableDownload.java)核对实际字节和 SHA-256，安装前重新核对，随后检查：

- 与当前安装的包名相同，且 `debuggable=false`。
- 实际 versionCode 与清单相符且更大，Android 版本兼容。
- versionName 与当前 Standard/Low 变体对应。
- 候选完整当前签名集合与已安装应用完全一致，不能只匹配一个子集。

当前正式交付沿用 `dsh.android.client` 和 E7E3 发布证书；源码签名比较实际针对已安装证书集合，不凭文档中的指纹缩写放行。摘要证明字节一致，同签名证明安装身份延续，两者都不证明插件代码无害。手动 APK 核验见[下载校验](download-verification.md)。

Android 8+ 未允许此应用安装未知来源时，页面先打开系统授权设置；用户返回后再发起校验。Android 13+ 下载前会请求通知权限，回调后继续请求下载；通知权限不授予安装权限。系统安装器仍由用户确认，不做静默安装。

**从F10基座到当前F11，实际安装派发仍是 FileProvider URI + `ACTION_VIEW`。** [UpdateActivity.dispatchInstall](../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java)没有旧 build173 R09 的 PackageInstaller 会话围栏，也没有在这里调用维护停止链。因此覆盖更新前应在应用内正常停止 DSH 和终端，并确认停止完成；外部 ADB/文件管理器覆盖同样不能继承应用内受管组件的停止证明。不能把历史 build173 的自有安装器验收移植到本次 build170 基座代码。

若系统取消安装，原应用仍在；页面打开成功也不能证明覆盖完成。更新后应核对实际版本并检查启动状态。无需为签名不符卸载原应用；卸载/清数据可能丢失应用私有数据、本机密钥和副本。重要数据先按[备份与恢复](backup-and-restore.md)导出。

## Ubuntu 内受管更新

更新页的“检查更新计划”进入环境维护页，比较当前环境和包内组件，确认后走[EnvironmentMaintenance](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)。同一 Ubuntu base 下只更新受管组件；个人数据保持原位，UI-only APK 变化不应直接重建环境。基础不适合局部更新时，进入保数据重建计划。

受管提交先经过[MaintenanceCoordinator](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java)正常停止 Web/终端和退出屏障，再准备候选、记录摘要及前代、进行[RuntimeTrial](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java)，核验真实端口/鉴权、会话与存储、网页握手和进程退出后提交。失败保留原件，未知退出不能切换、清理或重放。兼容运行时回退用当前启动器重新试运行，不回退用户对话快照；详见[安装与维护](install-and-maintenance.md)。

## 修改与验证入口

选择/来源/签名规则的回归入口为[UpdatePolicyTest](../app/src/test/java/com/deepseekharness/app/util/UpdatePolicyTest.java)与[UpdateRedirectsTest](../app/src/test/java/com/deepseekharness/app/util/UpdateRedirectsTest.java)，停止和事务边界见[MaintenanceGateTest](../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java)。本文未重新运行这些测试，也不宣称完整应用内安装确认/取消、远端清单、外部覆盖或各 Android 版本已经复验。
