# rc1.2 全屏对话与插件网站入口

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


## 交付

两版 APK 和对应 `.apk.sha256` 均位于 `F:\DSHA_RESTART\release`，历史版本保留。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| dsha-1.2.0-rc1.2.apk | 222706812 | 4a33cef0f718ceddb249478d5402aab39a33d9fb4175cfd35831d687b1734796 |
| dsha-1.2.0-rc1.2low.apk | 303522074 | dcc64d925ea1697e930753378e95724125cd4688dd58335348bd0c3887928403 |

两版 versionCode 均为 111，applicationId 为 `com.dsh.client`，标准版最低 API 30，兼容版最低 API 23，目标 API 37；仅 arm64-v8a。
发布证书 SHA-256 为 `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`，与 rc1.1 一致。
环境版本仍为 9，不触发离线环境重新解压。

## 改动

- 移除原生网页顶部的返回、标题和浏览器按钮，WebView / Gecko 共用全屏布局。
- 隐藏系统状态栏和导航栏，边缘滑动可临时唤出；保留系统返回、挖孔与输入法避让，加载进度覆盖显示，不占网页高度。
- 全屏窗口单独处理边距，避免全局 Activity 边距再次占用网页空间；错误页仍可重试或打开外部浏览器。
- 插件市场顶部新增网站卡片，点击使用浏览器打开 `https://dsha.cc/`；插件管理页隐藏该卡片。网站浏览后继续使用原有链接安装或文件导入。

全屏系统栏的实现参考 [Android 官方沉浸模式文档](https://developer.android.com/develop/ui/views/layout/immersive)。

## 验证范围

- 两版 Release 构建成功，Android Lint 均为 0 错误；发布 APK 的证书、版本、最低 API 和架构已核验，兼容版 V1/V2/V3 签名验证通过。
- Android 13 / Redmi M2012K10C 真机用对应调试包覆盖安装，保留现有数据，验证两种网页内核都能显示 dsh 首页并隐藏原生顶部栏、通过系统返回退出。
- 标准版使用系统 WebView 116.0.5845.92；兼容版在该旧 WebView 上自动使用 Gecko 143。两种内核的原生视图在键盘弹出后均由 2316 px 缩至 1342 px，键盘关闭后恢复。
- 网站按钮成功发出指向 `https://dsha.cc/` 的 ACTION_VIEW 并唤起小米浏览器；浏览器停在首次使用协议页后退出，未代用户接受协议，未验证网站内容加载或网站下载。
- 本轮没有发送模型请求，也未重复插件/npm 回归；本次修改不涉及安装运行时。Android 6—12、Android 17 和 16 KB 真机不在本轮验收范围内。
