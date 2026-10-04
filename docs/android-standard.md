# Android 11+ 标准版适配与减重

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


当前预览版为 `1.2.0-rc1.2`（versionCode 111），最低 Android 11 / API 30，
仅 arm64-v8a；Android 12 包含在支持范围内。编译和目标 SDK 为 Android 17 / API 37。
采用系统 WebView；旧系统另有 [low 兼容版](android-low.md)。最新 APK 与校验值见 [rc1.2 发布说明](releases/v1.2.0-rc1.2.md)。

## 前序减重记录（debug 构建）

同为本地 debug APK，未改变完整离线安装方式：

| 项目 | 调整前 | 调整后 |
|---|---:|---:|
| APK | 345.54 MiB | 214.11 MiB |
| APK 内 rootfs 压缩资产 | 289.95 MiB | 181.67 MiB |

真机验收修复后的 APK 减少约 131.43 MiB（38.0%），精确大小为 224,511,219 字节。
最初减重包为 209.81 MiB；真机发现缺少 Python 动态库和 pnpm，补齐后以当前体积为准。
主要来自删除预装 npm 缓存、Windows/macOS/x64 二进制，以及重复的 Termux Python。
统一使用容器内 Ubuntu Python 3.12；ADB 的 Python wheels 继续保留。
Python 和 wheels 的压缩文件改用 `.bin` 资产名，避免 aapt 提前展开 gzip 后膨胀。
Node、pnpm、dsh 和离线 Ubuntu 环境仍随 APK 分发。

原始 `app/src/main/assets/offline-rootfs.bin` 未修改；构建自动产生减重后的分发资产。
环境版本仍为 9，避免仅因减重触发旧用户 rootfs 清空重装。
老用户按需补齐 Ubuntu Python，已有配置、插件、会话和工作区继续保留；不会主动清理用户已经安装的运行时。

目前 rootfs 仍占 APK 约 85%。如果还要大幅减小，应另做首次联网下载环境的安装方式，
或把 ADB 依赖改为按需下载；这会改变离线可用范围，本次没有引入。
单纯继续提高最低 Android 版本，对这部分体积帮助很小。

## 新版 Android 的处理

- Android 17：开启 LAN 或无线 ADB 时申请局域网权限；拒绝授权不会阻断本机回环对话。
  首次升级后保留原有开关，并在回到主界面时为已启用的功能补请求。
- 后台运行：用户启动的本地开发服务器使用 `specialUse` 前台服务类型，无线 ADB 使用
  `connectedDevice`；不再把长期运行的服务器声明为有时限的数据同步任务。
  系统拒绝启动时退出服务或提示回到前台恢复，避免无通知继续运行。
- 全面屏：统一处理状态栏、挖孔、导航栏和键盘边距，防止目标 SDK 提高后页面被系统区域遮挡。
- 16 KB 页大小：保留已重编对齐的 Termux JNI，并检查 APK 内宿主与容器的 ELF。

## 已完成的检查与边界

2026-09-05 在 Windows 本地完成：

- Gradle 9.3.1 / AGP 9.1.1 / JDK 17 成功构建 APK；包内声明核验为 min 30、target 37、arm64。
- 7 个插件脚本用例通过，涵盖导入导出、删除指定作用域插件、核心保护及写配置失败后复原。
- 819 个 arm64 ELF 的 16 KB LOAD 段对齐通过：宿主 10、rootfs 737、Python 49、wheels 21、补充库 2；
  未发现其他架构 ELF。可通过 `tools/audit-standard-apk.py` 复现。
- 最终包不含旧 `runtime-python` 资产，环境版本没有递增。

已在型号 2410DPN6CC、Android 16 / API 36、4 KB 页大小的真机完成核心验收：真实模型回复、
PTY 命令、插件导入/导出/删除、带依赖更新、覆盖安装保留和短时后台返回。
发现并修复 Python SQLite/readline 缺库、离线 pnpm 缺失及 proot 硬链接造成依赖复制失败。
完整记录见 [真机验收报告](device-acceptance-2026-09-05.md)。

尚未完成 Android 11 / Android 17（含 16 KB 设备）的真机运行验收。
静态对齐不证明 proot、Node、原生模块和厂商后台策略在每台设备上都能正常运行。
发布前保留最少两端真机验收：启动离线环境并对话；打开终端；完成一次插件导入、导出和删除；
检查键盘/返回操作与授权后的无线 ADB、LAN。Android 17 还需覆盖拒绝局域网权限后本机对话可用。

当前产物是本机 debug 证书签名，不能直接覆盖使用历史发布证书的 APK。
正式覆盖包需按 `BUILD.md` 配置原有发布签名；不要为了安装调试包卸载含用户数据的正式版。

参考：[Android 17 发布](https://developer.android.com/blog/posts/android-17-is-here)、
[局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)、
[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、
[16 KB 页大小](https://developer.android.com/guide/practices/page-sizes)、
[AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。
