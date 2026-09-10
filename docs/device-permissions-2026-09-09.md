# 2026-09-09 设备命令权限修订

本次对 1.5-alpha.1 增加设备命令白名单，已同签名覆盖安装到 Android 16 手机，保留环境与用户数据。
版本码仍为 114、环境版本仍为 10、dsh 仍为 0.1.5-alpha.1。仅本地交付，未上传发布。

## 规则

| 操作 | 结果 |
|---|---|
| 查询或读取设备任意目录 | 策略层允许；实际读取仍由 Android / ADB / Root 的权限决定 |
| 在根目录、系统目录及 DCIM、Pictures、Android/data、Android/obb 内新建、移动、重命名、删除或写入 | 拦截，包含子目录、共享存储别名及其它用户的对应目录 |
| 复制只读目录中的文件到普通目录 | 允许读取源文件；目标必须通过写入检查 |
| 普通共享存储子目录（如 Download）及 `/data/local/tmp` 子目录内的明确文件操作 | 允许；不允许删除存储根目录或使用 `rmdir -p` 向父目录扩散 |
| dd、mkfs、fdisk/parted、块设备写入 | 拦截 |
| setenforce、chcon、restorecon 等 SELinux 修改 | 拦截 |
| settings put/delete、setprop 等系统写入 | 拦截；settings get/list 和 getprop 可查询 |
| mount/remount/umount | 拦截 |
| fastboot、flash、format、erase、wipe 等刷机操作 | 拦截 |
| 结束普通用户应用 | 先刷新全部应用、用户/系统分组及 UID，再执行；无需确认 |
| 系统应用、关键 UID、DSHA/Shizuku 自身、未知进程或应用 | 拦截；批次中任何目标不允许时整批拒绝 |
| 未识别命令、解释器、脚本、管道、重定向、变量/命令展开 | 拦截 |

文件操作必须使用明确绝对路径；执行前检查实际规范路径与符号链接，无法核对元数据时拒绝。
复制到已有目录时检查最终文件落点。支持完整包名或正数 PID；PID 通过 UID 定位唯一普通应用后，
转换成按包名停止，避免 PID 复用误伤。支持应用双开/多用户的逗号分隔 UID；任一 UID 涉及系统或受保护应用时禁止停止。

## 生效入口与边界

原生 `DeviceShellPolicy` 是命令解析入口。3090 `/device/plan` 输出结构化参数，ADB 使用同一份目录规则；
Shizuku `ShellService` 在 Binder 执行侧再次检查。STOP 计划只是待检查目标，不能直接执行；
实际 ADB/Shizuku 执行身份必须在本次连接上获取完整应用清单与 UID，不能沿用 App 层的部分清单。

`/app/apps` 尝试显示完整分组。Android 未提供完整清单时返回 `[APP_LIST_UNAVAILABLE]` 并给出设备查询方式，
不会用部分清单批准停止操作。旧 `/confirm` 只对已知只读查询返回 YES；文件与停止操作必须进入现场校验的执行器。
Root、旧确认开关和 `DSH_INTERNAL` 不能跳过随包入口的策略。配置页显示“设备命令保护始终开启”，保留原设置键名。

**这不是整个 Ubuntu 的文件系统沙箱。** 保护覆盖随包设备命令入口。任意容器 Python/Node 代码、插件、
直接共享存储访问、自写 ADB 客户端和其它 UI 操作仍受 Android 沙箱与已有授权约束；提示词要求不得绕过，
但不提供内核隔离。不能据此声称恶意插件无法直接写入共享存储。

## 验证

测试机：小米 2410DPN6CC、Android 16、ARM64、4 KB 页。使用真实原生桥与 USB 设备 shell 传输；
本轮未启用无线调试、未进行新的无线配对，也未实际启动 Shizuku 服务。

| 检查 | 结果 |
|---|---|
| 标准版 / low JUnit | 每版 301 项，300 通过，1 项宿主条件跳过，0 失败 |
| 双版 Release 构建与 Lint | 通过，Lint 0 错误；仍有既有警告 |
| ADB 流程回归 | 24 项通过 |
| Python 设备执行器回归 | 16 项通过，含路径逃逸、符号链接、双开 UID、系统共享 UID 与整批拒绝 |
| 设备引导插件回归 | 3 项通过 |
| 原生/Binder 入口与受控执行记录 | 30 项断言通过 |
| 真机策略与文件操作 | 96 项检查通过，完整设备清单共 507 个应用 |
| 原生命令期限与输出上限 | 16 项断言通过；四路 FIFO 读取约 769 ms 超时，后续请求可执行，持续输出有界 |
| Web 启动与鉴权 | 约 5.81 秒启动；换到临时端口再返回 3080，均重新鉴权并显示页面 |
| 实际 APK 与环境 | 新脚本与源码一致；标准版 17 项、low 31 项原生/离线环境条目与前一包摘要一致 |

文件操作仅使用本轮随机临时目录，覆盖 mkdir、touch、cp、rename/mv 和 rm。
指向 DCIM 的测试符号链接在后续写入的元数据检查阶段被拦截，未向相册写入文件；危险命令样例没有发送实际变更指令。

手机拒绝安装临时测试 APK，因此普通应用停止使用真实完整清单加执行记录验证，未结束日常应用，
不记作真实 force-stop 实测。系统进程保持运行。App UID 无法读取 `/proc/version`、ADB 可以读取，
测试确认系统拒绝不会伪装成成功。App 层未取得系统应用时明确报清单不可用；实际设备连接可获取完整分组。

覆盖前后核对了 proroot、端口、桌面模式、自动备份间隔和计数、首次进入标志、确认/Root/ADB 开关及 API key 状态，均一致。
现有环境中的设备脚本已更新，测试目录与测试配置已清理。手机最终安装的标准版 APK 回读 SHA-256 与实际交付文件一致，主页面启动成功。

## 本地交付

目录：`F:\DSHA_RESTART\release`。覆盖前的 APK 与校验文件保存在 `release/history/before-device-policy-20260909/`。

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `dsha-1.5-alpha.1.apk` | 175.61 MiB | `d7310d8415836fe40f9d32e459f5b0d763635aad676f7726d3e1df7674476ff8` |
| `dsha-1.5-alpha.1low.apk` | 252.40 MiB | `00b7b4b2877b62eae1baefda6b8c156df125ce99c6ee274156b8f87350041bc9` |

发布证书 SHA-256：`e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`。
两版均生成 `.apk.sha256`。Android 6/7 与真实 16 KB 内核的本次策略运行仍未实测。

原始结果位于 `app/build/device-policy-20260909/`：`delivery-proof.json`、`phone-policy-report.json`、
`native-audit-final.log`、`binder-bounds.log`、`web-runtime.log`、`native-before.json`、`native-after.json`、`installed-release-proof.json`。
