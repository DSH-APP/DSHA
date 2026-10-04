# 真机验收记录 · 2026-09-05

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


最新两版 versionCode 110 的下载、导入和 npm 验收与产物见 [后续修复记录](plugin-download-npm-fix-2026-09-05.md)。

同日后续的插件布局与安装第 2 步修复及最新 APK 信息，见 [后续修复记录](plugin-layout-install-fix-2026-09-05.md)。

本轮结论：Android 16 上核心使用流程通过；验收发现的运行时缺项已修复，并已覆盖安装到同一手机复验。
这不代表 Android 11、Android 17、16 KB 内存页或所有厂商机型都已验证。

## 设备与产物

- 设备型号：2410DPN6CC（haotian），arm64，Android 16 / API 36，内存页 4096 字节。
- 系统 WebView：com.google.android.webview 143.0.7499.192。
- APK：1.2.0-rc2-standard，versionCode 108，minSdk 30 / targetSdk 37，本地 debug 签名。
- 最终大小：224,511,219 字节（214.11 MiB）。
- SHA-256：`4619e802b9b6060660e62f128c6e47721a574bcda947ecf3b102cae8d2ee1695`。
  已核对手机安装的 base.apk 与本地产物一致。

## 实际操作结果

| 功能 | 结果与证据 |
|---|---|
| 安装、引导、离线解压 | 初始减重包从未安装状态正常进入三页引导，解压后进入主界面，无崩溃；首次 dsh 启动约 6 秒 |
| 真实模型回复 | 用户在手机自行配置 API；发送只要求回复 DSHA_OK、禁止工具/文件修改的消息，DeepSeek-V4-Flash 实际返回 DSHA_OK |
| 网页布局与输入 | 系统 WebView 正常加载；对话与键盘区域可见，返回并重新进入成功 |
| PTY 终端 | 在真实终端输入并执行 `printf DSHA_PTY_OK`，界面实际显示 DSHA_PTY_OK |
| 插件导入 | 通过手机系统文件选择器导入 ZIP，作用域插件 @dsha-check/device-demo 正常登记并显示版本 |
| 导出与重新导入 | 系统保存对话框写出 TAR.GZ；取回核对实体、package.json 和 patch，删除插件后可重新导入该导出包 |
| 禁用与同名更新 | 禁用 1.0.0 后，更新到含 npm 依赖 is-number 7.0.0 的 1.0.1 成功，禁用状态保留 |
| 删除 | 长按 → 删除 → 确认后列表移除该插件，安装目录清空；仅删除本轮创建的测试插件 |
| 链接识别 | 输入项目 GitHub URL，界面正确识别仓库与默认分支，安装按钮启用；未把项目仓库作为插件安装 |
| 覆盖安装保留 | 多次相同签名覆盖安装，未重新解压；环境标记时间保持 17:18，API 配置、插件状态和测试会话保留 |
| 停止、重开与后台 | 停止按钮成功终止 dsh 进程；后续可重新启动。短时返回桌面后前台服务仍在，重新进入可读原会话 |
| 最终错误检查 | 最终应用进程的 DSHA 警告和 AndroidRuntime 错误日志未见新错误 |

## 发现并修复的问题

1. **Python 动态库不完整**：sqlite3 缺 libsqlite3.so.0，readline 缺 libreadline.so.8。
   增加来自 Ubuntu 24.04 官方 arm64 包的补充资产，按需写入容器，不清空已有环境。
   修复后 ssl、sqlite3、ctypes、zlib、bz2、lzma、tarfile、zipfile、venv、readline、curses 均成功导入。
2. **缺少可直接运行的 pnpm**：原环境只有 Corepack 相关文件，没有就绪的 pnpm 命令。
   补充 pnpm 10.34.5 的离线发布包和命令入口；修复首次创建命令目录的遗漏。
   pnpm 异常不再阻断无需依赖安装的插件列表、开关和删除。
3. **带依赖的插件更新失败**：pnpm 默认文件导入方式遇到 proot 硬链接模拟时，Python 复制报 Invalid argument。
   改为 `package-import-method=copy`，继续禁用安装脚本；同一包重新导入后更新成功。
   失败发生时原插件及禁用状态仍被保留。

Node 24.19.0、dsh 0.1.2-rc.1，以及 koffi、sharp、node-pty 的原生模块加载通过。
新增两份动态库的 16 KB ELF 对齐通过；最终运行环境共 819 个 arm64 ELF 静态检查通过。
7 个已有插件脚本回归用例通过，没有清空数据或进行全量备份。

## 未覆盖范围

- 手机 Wi-Fi 已开启但未连接；无线 ADB 配对和跨设备 LAN 访问没有做联机验收。
- Android 11、Android 17 专属局域网授权、16 KB 设备和长时间锁屏/厂商省电回收仍待验证。
- GitHub 分支、Release 等各种远程下载端点没有逐一联网安装；本轮实际联网覆盖的是 npm 依赖下载。
- 多选导出、第三方作者的复杂原生插件、WebView 附件上传和外部浏览器兜底没有全部逐项操作。
- 观察到重新切回启动页时，页面内的启动日志显示会重置；运行状态和对话服务正常。该显示问题本轮未修改。

手机上保留了最终修复包、用户配置和 DSHA_OK 测试会话。测试插件及三个临时下载压缩包已清理。
截图、导出包检查副本和探测记录位于本地 `app/build/device-acceptance/`，不进入版本控制。
