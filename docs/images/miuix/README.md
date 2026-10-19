# DSHA Miuix 页面预览

这些图片来自本 PR 的 UI 包测试：Robolectric 在本机 Android 模拟环境中绘制生产 Compose 组件与 XML 布局。它们用于评审外观、文字和布局，**不是手机实拍，也不代表页面后台功能已通过真机验收**。

- 首页与设置使用生产 Miuix 组件；配置表单 `config-bound` 使用真实 Fragment 完成绑定。
- 多数内页仅挂载静态布局，动态状态可能为空，选择器未必有运行数据。
- 插件长名称、路径、版本号和恢复信息使用测试样例；虚拟屏仅创建控件，没有连接设备。
- 每张图只展示当时可见的区域，部分适配测试已滚动到目标操作，不能据此推断整页内容缺失。
- 所有图片均从测试输出提取，不包含手机截图、用户会话、实际凭据、安装日志或设备标识。

共 46 张原始页面图、4 张概览。点击原图可查看完整分辨率；图片对应源码提交 `23fd869`，复现命令见 [UI 迁移与验证说明](../../ui-miuix.md#定向验证)。

## 主要页面概览

![首页、设置、配置、插件、数据与授权](overview.png)

## 浅色与功能页面

[查看本组概览](overview-light.png)

| 页面 | 页面 | 页面 |
| --- | --- | --- |
| <a href="launch-light.png"><img src="launch-light.png" alt="启动首页 · 浅色" width="240"></a><br>启动首页 · 浅色 | <a href="config-bound.png"><img src="config-bound.png" alt="配置 · 实际表单绑定" width="240"></a><br>配置 · 实际表单绑定 | <a href="plugin-long-card.png"><img src="plugin-long-card.png" alt="插件 · 长名称和状态样例" width="240"></a><br>插件 · 长名称和状态样例 |
| <a href="fragment_config.png"><img src="fragment_config.png" alt="配置 · 静态布局" width="240"></a><br>配置 · 静态布局 | <a href="fragment_device_grants.png"><img src="fragment_device_grants.png" alt="设备能力授权" width="240"></a><br>设备能力授权 | <a href="fragment_workspace.png"><img src="fragment_workspace.png" alt="数据与备份" width="240"></a><br>数据与备份 |
| <a href="fragment_install.png"><img src="fragment_install.png" alt="安装与环境" width="240"></a><br>安装与环境 | <a href="activity_update.png"><img src="activity_update.png" alt="应用更新" width="240"></a><br>应用更新 | <a href="activity_diagnostics.png"><img src="activity_diagnostics.png" alt="自检与诊断" width="240"></a><br>自检与诊断 |
| <a href="activity_adb_pair.png"><img src="activity_adb_pair.png" alt="ADB 配对" width="240"></a><br>ADB 配对 | <a href="activity_plugin_install.png"><img src="activity_plugin_install.png" alt="插件链接安装" width="240"></a><br>插件链接安装 | <a href="activity_extract.png"><img src="activity_extract.png" alt="环境提取" width="240"></a><br>环境提取 |
| <a href="fragment_terminal.png"><img src="fragment_terminal.png" alt="简易终端" width="240"></a><br>简易终端 | <a href="fragment_pty_terminal.png"><img src="fragment_pty_terminal.png" alt="PTY 终端" width="240"></a><br>PTY 终端 | <a href="activity_web_preview.png"><img src="activity_web_preview.png" alt="网页连接错误提示" width="240"></a><br>网页连接错误提示 |
| <a href="plugin_list_header.png"><img src="plugin_list_header.png" alt="插件列表 · 搜索与来源" width="240"></a><br>插件列表 · 搜索与来源 | <a href="plugin_list_footer.png"><img src="plugin_list_footer.png" alt="插件列表 · 底部操作" width="240"></a><br>插件列表 · 底部操作 | <a href="welcome_page1.png"><img src="welcome_page1.png" alt="欢迎 · 应用介绍" width="240"></a><br>欢迎 · 应用介绍 |
| <a href="welcome_page2.png"><img src="welcome_page2.png" alt="欢迎 · 能力介绍" width="240"></a><br>欢迎 · 能力介绍 | <a href="welcome_page3.png"><img src="welcome_page3.png" alt="欢迎 · 开始使用" width="240"></a><br>欢迎 · 开始使用 | <a href="onboarding-prepare.png"><img src="onboarding-prepare.png" alt="引导准备 · 测试样例" width="240"></a><br>引导准备 · 测试样例 |
| <a href="startup-recovery.png"><img src="startup-recovery.png" alt="启动恢复 · 测试样例" width="240"></a><br>启动恢复 · 测试样例 | <a href="virtual-screen-controls.png"><img src="virtual-screen-controls.png" alt="虚拟屏 · 控件创建" width="240"></a><br>虚拟屏 · 控件创建 |  |

## 深色主题

[查看本组概览](overview-dark.png)

| 页面 | 页面 | 页面 |
| --- | --- | --- |
| <a href="dark-fragment_config.png"><img src="dark-fragment_config.png" alt="配置 · 静态布局 · 深色" width="240"></a><br>配置 · 静态布局 · 深色 | <a href="dark-fragment_device_grants.png"><img src="dark-fragment_device_grants.png" alt="设备能力授权 · 深色" width="240"></a><br>设备能力授权 · 深色 | <a href="dark-fragment_workspace.png"><img src="dark-fragment_workspace.png" alt="数据与备份 · 深色" width="240"></a><br>数据与备份 · 深色 |
| <a href="dark-fragment_install.png"><img src="dark-fragment_install.png" alt="安装与环境 · 深色" width="240"></a><br>安装与环境 · 深色 | <a href="dark-activity_update.png"><img src="dark-activity_update.png" alt="应用更新 · 深色" width="240"></a><br>应用更新 · 深色 | <a href="dark-activity_diagnostics.png"><img src="dark-activity_diagnostics.png" alt="自检与诊断 · 深色" width="240"></a><br>自检与诊断 · 深色 |
| <a href="dark-activity_adb_pair.png"><img src="dark-activity_adb_pair.png" alt="ADB 配对 · 深色" width="240"></a><br>ADB 配对 · 深色 | <a href="dark-activity_plugin_install.png"><img src="dark-activity_plugin_install.png" alt="插件链接安装 · 深色" width="240"></a><br>插件链接安装 · 深色 | <a href="dark-activity_extract.png"><img src="dark-activity_extract.png" alt="环境提取 · 深色" width="240"></a><br>环境提取 · 深色 |
| <a href="dark-fragment_terminal.png"><img src="dark-fragment_terminal.png" alt="简易终端 · 深色" width="240"></a><br>简易终端 · 深色 | <a href="dark-fragment_pty_terminal.png"><img src="dark-fragment_pty_terminal.png" alt="PTY 终端 · 深色" width="240"></a><br>PTY 终端 · 深色 | <a href="dark-activity_web_preview.png"><img src="dark-activity_web_preview.png" alt="网页连接错误提示 · 深色" width="240"></a><br>网页连接错误提示 · 深色 |
| <a href="settings-dark.png"><img src="settings-dark.png" alt="设置 · 深色" width="240"></a><br>设置 · 深色 |  |  |

## 短屏、大字体与平板

[查看本组概览](overview-adaptive.png)

| 页面 | 页面 | 页面 |
| --- | --- | --- |
| <a href="launch-short-large.png"><img src="launch-short-large.png" alt="首页 · 英文短屏大字体" width="240"></a><br>首页 · 英文短屏大字体 | <a href="card-actions-short.png"><img src="card-actions-short.png" alt="管理页 · 短屏长操作" width="240"></a><br>管理页 · 短屏长操作 | <a href="tablet-long-values.png"><img src="tablet-long-values.png" alt="平板 · 长版本号样例" width="240"></a><br>平板 · 长版本号样例 |
| <a href="welcome-short-large.png"><img src="welcome-short-large.png" alt="欢迎 · 英文横屏大字体" width="240"></a><br>欢迎 · 英文横屏大字体 | <a href="large-fragment_config.png"><img src="large-fragment_config.png" alt="配置 · 静态布局 · 英文大字体" width="240"></a><br>配置 · 静态布局 · 英文大字体 | <a href="large-fragment_device_grants.png"><img src="large-fragment_device_grants.png" alt="设备能力授权 · 英文大字体" width="240"></a><br>设备能力授权 · 英文大字体 |
| <a href="large-fragment_install.png"><img src="large-fragment_install.png" alt="安装与环境 · 英文大字体" width="240"></a><br>安装与环境 · 英文大字体 | <a href="large-activity_update.png"><img src="large-activity_update.png" alt="应用更新 · 英文大字体" width="240"></a><br>应用更新 · 英文大字体 | <a href="large-activity_diagnostics.png"><img src="large-activity_diagnostics.png" alt="自检与诊断 · 英文大字体" width="240"></a><br>自检与诊断 · 英文大字体 |
| <a href="large-activity_adb_pair.png"><img src="large-activity_adb_pair.png" alt="ADB 配对 · 英文大字体" width="240"></a><br>ADB 配对 · 英文大字体 |  |  |

