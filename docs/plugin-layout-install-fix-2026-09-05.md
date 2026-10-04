# 插件布局与安装第 2 步修复

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


同日后续修复，已覆盖安装到 Android 16 测试手机，配置和会话保留。

## 界面

- 插件页使用统一滚动区域；管理页隐藏链接安装区，避免固定区域挤压列表。
- 卡片名称独占一行区域并允许换行，说明、状态、开关和「更多」按钮分别排布。
- 完整错误通过点按状态弹窗查看；搜索、键盘弹出后滚动及「更多」入口已在真机验证。
- 安装模块按 1—6 编号排列，第 2 步明确显示 curl / git / Python；成功报告仅列必要版本信息。

## 基础工具修复

此前第 2 步只查命令名，缺失时不会安装，空输出还有可能误报成功。
新增 BasicToolsInstaller：先离线修复 Python，再按需从 APT 源安装 curl/git/证书；
使用实际执行结果确认成功，失败保留输出，安装期间禁止重复点击。
第 4 步直接修复内置 pnpm，不再依赖 Corepack 首次联网激活。

真机安装时还发现 proot 的 L2S 临时目录没有按宿主绝对路径映射到容器，
dpkg 因此在处理新文件时报告 ownership / ENOENT。已补齐该目录映射，
APT 完成下载、解包与配置；118 个包的状态均为 install ok installed。
定位参考：[同类嵌入式 Ubuntu 的 L2S 挂载说明](https://github.com/Bentlybro/kern/blob/main/docs/11-embedded-linux.md)。

## 验证与产物

真机一键检查返回「全部 6 步检查通过」：

- curl 8.5.0、git 2.43.0、Python 3.12.3。
- Node 24.19.0、pnpm 10.34.5、dsh 0.1.2-rc.1。
- 界面搜索 web 正确显示两个匹配插件；键盘弹出时页面仍可滚动，控件没有重叠。
- Gradle assembleDebug 与 git diff --check 通过；未做全量备份。

APK 为 224,509,460 字节（214.11 MiB），手机安装包与本地产物 SHA-256 一致：
`0e3b325bf4aabcb12e9176545a09739293a619235c2107e610624ed797e68ed1`。

第 2 步缺少 curl/git 时需要联网下载，原始离线 rootfs 和环境版本 9 未修改。
截图保存在 app/build/device-acceptance/：plugin-management-final.png、
plugin-market-final.png、plugin-filter-final.png、install-results-final.png。
