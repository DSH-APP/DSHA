# rc1.4 全流程检查与修复 · 2026-09-08

本轮完成真实 Web 对话、App 无线 ADB、备份与维护，以及原生页面的完成、失败、取消和重建检查，修复发现的命令无返回、进程残留、配置保存、环境切换竞争与更新状态问题。版本仍为 **rc1.4 / rc1.4low，版本码 113，环境版本 9，dsh 0.1.2-rc.1**。这是当前发布包的功能验收记录，9 月 7 日的主题、排版及首次交付报告保留为历史。

## 用户要求与处理结果

| 要求 | 结果 |
|---|---|
| Web UI、模型往返 | 标准版实际输入并发送，官方 DeepSeek-V4-Flash 返回 `DSHA_AUDIT_OK`；实际导出会话 ZIP。兼容版另验 Gecko 文件、状态保持及真实 dsh 页面。 |
| ADB 连接 | 真实六位码 TLS 配对，App 通道执行 `id` 得到 `uid=2000(shell)`，断线后重新发现端口并连接；USB adb 仅用于操作测试设备。 |
| 备份、恢复 | 四种范围在手机容器中往返；真实用户环境自动备份和 Web 运行中备份校验通过；恢复、重建及故障回滚在隔离目录完成。 |
| 点击后接口无返回 | 验证 shell/ADB 静默超时、取消、后继请求、鉴权失败、保存失败、端口占用、旧页面回调、互斥及进程回收；相应结果、错误或忙碌状态明确反馈。 |
| “显示与运行”逐项说明 | 每项增加作用、适用条件与生效时机；尚未提供的翻译入口禁用；虚假脚本检查改为真实更新入口。 |
| 安装检查慢、没有输出 | 一次容器探测完成六步检查，本次 **1,886 ms**；实时脱敏日志、步骤结果、耗时、取消及重建保留通过，健康组件不强制重装。 |
| 隐藏问题与交付 | 修复终端残留、维护并发、备份缓存、ADB 同步及跨通道更新；完成两版构建、JUnit、Lint、签名和 APK 检查。 |

## 主要修复

- **安装与修复：**合并重复启动；检查和修复分开、按需补齐。持续显示脱敏输出，切页/旋转保留任务及结果。取消等待本次进程真正退出，静默命令到期返回。
- **配置：**先验证全部输入再保存，端口保留原有 1—65535 范围并拒绝 3081/3090，备份间隔拒绝溢出；密钥加密失败保留原值。修复配置保存未进入环境锁执行范围、导致 ADB 设置同步失败的问题；迟到状态不覆盖新状态。
- **终端：**简易终端取消结束本次命令并恢复会话，exit 后首条输入不丢失、不重放。PTY 结束让 proot 清理子进程，真实验证 shell/sleep 均退出。任务保护直到进程退出才解除，异常路径不提前放行环境维护。
- **备份/维护：**普通备份及恢复预检不停止 Web。最终恢复/重建前验证安全备份，等待 Web 的 Node 与 launcher 退出，并拒绝仍在运行的终端等任务。失败回切，进程中断保留磁盘日志；未完成维护阻止新启动或解压。首次安装的停止不再创建半成品环境；严格检查 PID 与链接，拒绝向无关进程发送停止信号。
- **备份缓存：**只省略与 APK 摘要一致、可补齐的 ADB wheel 及归档，保留配对密钥、修改文件、额外依赖和插件源码。缓存正在变化时失败并提示重试，避免静默遗漏。恢复后只补缺失文件，不覆盖用户修改。
- **ADB：**区分配对成功与连接已验证，关闭无线调试时及时提示；重新发现端口、总超时和真实退出码生效。派发后的不确定命令不自动重放，配对/后台准备/配置同步与维护共用互斥门控。
- **原生桥：**提问和前台判断使用实际前台页面，修复 Web/子页误判后台。超时关闭原弹窗，旧按钮不能回答新请求；取消、服务停止、页面销毁结束等待。端口绑定失败撤销假就绪并可恢复，剪贴板报告实际完成或超时。
- **更新：**启动检查遵守开关和所选通道，提示显示后才消费；候选、下载文件、安装结果绑定查询来源。检查/校验不显示缓存的 100% 下载进度。安装校验跨页面重建保留，结果仅领取一次；来源不明的旧任务需重新联网确认。
- **页面/诊断：**连点进入对话只派发一次，插件状态及时刷新。诊断先显示已取得的信息，修复受环境任务保护，复制/导出失败反馈明确。长说明、按钮、禁用态及窄屏日夜排版完成复验。

## “显示与运行”各项作用

说明已直接放在配置页面。

| 项目 | 作用与生效方式 |
|---|---|
| 启动时检查更新 | 进入 App 后后台检查保存的稳定/预览通道，有新版本提示，由用户决定下载。 |
| 使用桌面版网页布局 | 使用电脑布局，适合大屏，手机通常关闭；保存后重新进入对话。 |
| 使用兼容浏览内核 | 兼容版强制使用 Gecko，关闭后自动选择；适合系统网页不兼容，保存后重新进入对话。标准版隐藏此项。 |
| 使用 proroot 运行时 | 尝试加快容器运行；设备/命令不兼容时关闭并使用 proot，保存并重启 Web。 |
| 允许局域网访问 | 本机 Web 运行时，通过 3081 供同一 Wi-Fi 的设备访问，需对应网络权限。 |
| 悬浮显示 AI 输出 | 在其他 App 上方显示回复及工具状态；保存后需允许系统悬浮窗权限，输出对屏幕旁观者可见。 |
| 悬浮条设置 | 调整底色、透明度、行数、字号、停留时间，以及思考过程、命令原文和确认方式。 |
| 插件翻译 | 当前版本尚未提供，入口禁用并说明，不影响插件安装和使用。 |

“更新 App 与运行脚本”明确说明脚本随 APK 更新，打开实际更新页。Root Shell 说明区分手机 Root 与 Ubuntu 的 root；自动备份间隔 0 为关闭。保留固定底部保存按钮及右上角日夜切换。

## 实测与自动验证

设备为 Redmi M2012K10C / Android 13 / arm64 / 4 KB 页。使用同源码标准/兼容调试包覆盖安装自插桩；发布包另作签名和内容核验。未卸载主应用、未清空数据，真实环境未恢复或重建。

| 验证 | 结果与证据 |
|---|---|
| 真实模型 | 官方 V4 Flash 1 轮、1 步回复 `DSHA_AUDIT_OK`，模型计时 1.428 秒，未调用设备工具。[截图](evidence/rc1.4-functional/model-reply.png) |
| 真实会话导出 | 实际按钮与 SAF 保存，ZIP 14,980 字节、1 个成员，内容含测试回复且完整性通过；SHA-256 `60e83d289ba7d4a531ae5899f08701abe03a072174e54474d1532123bab3afa2`。本地副本：`app/build/functional-evidence/session-export.zip`。 |
| WebView / Gecko | 两者均通过真实文件选择回调、PNG 回读、普通/Blob 下载与 SAF 保存、旋转和新会话图片/阅读位置恢复、返回、失败取消和更换保存位置。[标准](evidence/rc1.4-functional/web-standard.log) / [Gecko](evidence/rc1.4-functional/web-low.log) |
| Gecko 实际 dsh | 实际对话入口、token 鉴权、首次凭据提示及移动端页面检查；不发送额外模型请求。[日志](evidence/rc1.4-functional/gecko-live-final.log) / [截图](evidence/rc1.4-functional/gecko-live.png) |
| 无线 ADB | 本轮完整 170 条断言含真实配对、连接、断线重连和页面恢复；最终改动后再次验证 10 条通过。[最终连接](evidence/rc1.4-functional/adb-final-verify.log) / [复现入口](adb-flow-audit.md) |
| ADB 错误 | 缺失路径返回真实退出码 1；不回应 socket 在 2.563 秒返回 124，一次连接且命令未发出。[超时](evidence/rc1.4-functional/adb-no-response.json)。23 项 Python 隔离场景另通过。 |
| 安装 | 42 条通过：六步 1,886 ms、健康修复不下载/改组件、真实退出码 23、脱敏、取消回收、切页/重建、缺环境失败与重试、维护拒绝。[日志](evidence/rc1.4-functional/install-full.log) |
| 配置 | 13 条通过，有效保存 22 ms；非法输入/忙碌不写其他配置，成功清理旧错误，同步正确。[日志](evidence/rc1.4-functional/configuration.log) |
| 简易终端/页面会话 | 实际 sleep 回收、重启 2.395 秒、排队一次、exit 后首次输入保留；连点及插件刷新夹具通过。[结果](evidence/rc1.4-functional/fragment-session.json) |
| PTY/诊断 | 两版各 16 条通过：实际 Node/Python 诊断、修复按钮、PTY 输入及本次 shell/sleep 完整回收。[标准](evidence/rc1.4-functional/diagnostic-pty-final.log) / [兼容](evidence/rc1.4-functional/diagnostic-pty-low.log) |
| proot | 两版各 12 条通过：静默超时、完整回收、UTF-8 输出、真实退出码及中断后的环境保护。[标准](evidence/rc1.4-functional/proot-command.log) / [兼容](evidence/rc1.4-functional/proot-command-low.log) |
| 原生桥 | 65 条通过：四路超时/后继请求、输出上限、3090 占用恢复、token/owner 隔离；真实鉴权 HTTP 提问、非 Main 前台宿主、超时/旧按钮/取消/回答/停止/销毁。[日志](evidence/rc1.4-functional/bridge-all.log) |
| 备份引擎 | 最终手机容器 21 项：20 通过、1 项主机资产比对跳过；该比对已在主机通过。四范围、Linux 软链、坏档案、空间不足、回滚、缓存差异及并发变化均覆盖。入口 `tools/test-backup-engine.py` / `tools/test-backup-device.py`；插件容器另 27 项通过。 |
| 环境维护 | 最终 91 条通过：真实 APK 解压/备份恢复、断链拒绝、失败回切、磁盘日志恢复、预览取消不停止、保留进程禁止移动。另 16 条运行登记、36 条停止边界通过。[全链](evidence/rc1.4-functional/maintenance-all-final.log) / [运行](evidence/rc1.4-functional/maintenance-runtime.log) / [停止](evidence/rc1.4-functional/maintenance-stop-final.log) |
| 实际用户备份 | 自动备份及 Web 运行中全量备份校验成功，Web 代次保持；实际 Node/launcher 停止屏障完成后才进入空维护回调。[日志](evidence/rc1.4-functional/runtime-backup-final.log) |
| 更新状态 | 135 条通过：启动开关、来源/通道、旧状态迁移、离线保留、重建、一次性领取及实际进度控件。[日志](evidence/rc1.4-functional/update-flow.log) / [范围](update-flow-rc1.4-verification.md) |
| 排版 | 320×640、320×400，字体 1.0/1.3，日夜；2,016 文字状态，最低对比度 5.254，布局/触摸/重叠及子页返回/输入保留通过。[日志](evidence/rc1.4-functional/layout-final.log) / [白天](evidence/rc1.4-functional/light-320x640-font1.0-fragment_config.png) / [黑夜](evidence/rc1.4-functional/dark-320x640-font1.0-fragment_config.png) |
| JUnit | 两版各 43 类、263 项：262 通过、1 个 POSIX 实进程用例在 Windows 跳过，0 失败/错误；对应真实进程场景已在 Android 通过。输出位于 `app/build/test-results/testStandardDebugUnitTest` 和 `testLowDebugUnitTest`。 |
| 构建/Lint | 两版 Release、Lint、LowDebug AndroidTest 编译通过；Lint 0 errors，标准 421 / 兼容 378 warnings，未清空全部历史警告。[构建](evidence/rc1.4-functional/release-build.log) / [汇总](evidence/rc1.4-functional/release-verification.json) |

最初的安装取消、PTY 子进程和 PID 链接用例曾失败，修复后按原回收和隔离要求复验通过。新增 Gecko 验收修正了首次凭据提示及手机折叠侧栏的测试假设，没有据此更改产品行为。

已有 HTTPS 更新下载/取消/进程重建续传、插件下载、后台运行结果见 [9 月 7 日记录](release-rc1.4-2026-09-07.md)。本轮新增更新状态测试使用可控清单及校验执行器，不等同一次真实系统安装器验收。

## 当前发布文件

目录固定为 `F:\DSHA_RESTART\release`。同名旧 APK/摘要在覆盖前保留到 `release/history/rc1.4-before-functional-audit-20260908`，其他历史文件保留。

| 文件 | 字节数 | MiB | SHA-256 |
|---|---:|---:|---|
| `dsha-1.2.0-rc1.4.apk` | 223,208,350 | 212.87 | `2431f5ced86d7de16c65d8b7ca48b3ab4ab7b5977fc2551cb794dfe72d84b450` |
| `dsha-1.2.0-rc1.4low.apk` | 303,760,199 | 289.69 | `bc28e4f893bc2cfa3c4adafa5a59e2e6452d9e9fcedfb28b3118efac9c187c06` |

对应 `.apk.sha256` 同目录。标准 minSdk 30、兼容 minSdk 23，target/compile 37，arm64-v8a。两版发布签名匹配历史证书 `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`；ZIP 完整性、16 KB zipalign、无 debug 自插桩类及 debuggable 标记通过。

标准 819、兼容 833 个 arm64 ELF 的 LOAD 16 KB 对齐和 RELRO 在 4/16 KB 映射内覆盖通过，无其他架构混入。[标准报告](evidence/rc1.4-functional/standard-elf.json) / [兼容报告](evidence/rc1.4-functional/low-elf.json)。离线 rootfs 与上一批 rc1.4 摘要一致，未因清理缓存提升环境版本。

GitHub 交付采用 **Pre-release**，由贡献者 [@ym2025szz](https://github.com/ym2025szz) 发布，感谢原作者及其他贡献者。[发布页](https://github.com/DSH-APP/DSHA/releases/tag/v1.2.0-rc1.4)包含同一批双版 APK 及各自 SHA-256；仓库主页仅新增本预览版内容，历史发布和稳定版入口保留。官网/App 更新清单仍按独立的网站发布流程更新。

## 数据保留与验证边界

- 测试 Key 仅用于官方接口检查；保留原加密配置，验证读回后删除私有输入文件，结束恢复原凭据与自动备份设置。源码、文档、验收文件及 APK 成员未发现该格式的明文凭据。两条专用会话已归档，专用工作区取消登记。
- 有效用户备份继续保留在手机 `Download/DSHA`。最终两份为 `DSHA-backup-20260908-015956-753102cf.tar.gz`（2,265,772 字节）和 `DSHA-backup-20260908-020015-2802976f.tar.gz`（2,266,293 字节），此前的用户备份也保留。体积缩小主要来自省略可重建缓存。
- 完整恢复/重建仅操作本次隔离文件，不覆盖真实会话和插件。保留安全副本和中断日志已实测，但不能据此保证任意损坏数据都可恢复。
- Android 6/7、11/12、17 及真实 16 KB 内核未补齐完整设备矩阵；兼容版在本次 Android 13 上通过，静态 API/ELF 不替代旧系统真机。
- 手机未取得 Root；厂商权限页、第三方文件提供方、长期后台耗电及全部网络故障组合没有逐一实测。权限不可用、取消、超时及降级由相应代码/隔离测试覆盖。
- 系统更新安装器、未知来源授权、后台系统行为和正式签名 App Link 受 ROM/系统设置影响；本轮没有安装第三方测试更新包来证明这些路径。
- 最终手机状态及测试文件清理见 [清理记录](evidence/rc1.4-functional/cleanup.json)。

相关模块记录：[ADB](adb-flow-audit.md)、[维护](maintenance-rc1.4-verification.md)、[终端](terminal-process-reaping-handoff.md)、[更新](update-flow-rc1.4-verification.md)。
