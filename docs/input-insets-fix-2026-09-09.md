# 1.5-alpha.1 输入与系统栏修订

本次按反馈修复 low 的状态栏/键盘遮挡，并调整标准版与 low 共用的 dsh 对话输入逻辑。交付仍为本地 `1.5-alpha.1` / `1.5-alpha.1low`，版本码 114、环境版本 10。

## 修改

- `WebFullscreenUi` 同时避让状态栏、导航栏、刘海和输入法；顶部保留稳定安全边距，取消会影响旧 Android `adjustResize` 的全屏窗口标志。显示状态栏，底部导航保留手势唤出。两个内核调用同一套处理。
- `app-integration/client.js` 为实际 dsh 页面设置 `interactive-widget=resizes-content`，保留原有宽度、缩放和刘海设置。真机确认仅调整原生容器仍不足以让 Gecko 的整屏网页随键盘重排；补充此设置后，输入框和发送箭头完整显示在键盘上方。
- `composer-enter-patch.json` 针对锁定的 dsh `0.1.5-alpha.1` 编辑器应用精确补丁。普通 Enter、Shift+Enter 换行，点击箭头或 Ctrl / ⌘ + Enter 发送；中文组合输入、候选菜单、重复按键和禁止发送状态继续使用上游保护逻辑。软键盘声明为换行键。
- `RuntimeTools` 在启动前幂等应用输入补丁，先完成全部文本匹配，再写入模块。既有环境覆盖安装后可直接生效；未知上游结构保留原文件并报告错误。

反馈文档中的零尺寸无障碍节点不能单独证明画面重叠，本次同时使用实际截图、窗口 Insets、网页边界与编辑内容验证。

Gecko 的布局差异与 [Firefox Android 132 的视口行为变更](https://www.firefox.com/en-US/firefox/android/132.0/releasenotes/)一致；键盘与原生安全区处理参考 [Android 输入法布局说明](https://developer.android.com/develop/ui/views/layout/sw-keyboard)。

## 验证

- 双版 Debug / Release 构建成功，Release Lint 均为零错误，保留既有警告。
- 双版 JUnit 各 285 项：284 通过、1 项 Windows 宿主条件跳过，零失败。
- 编辑器真实上游事件处理与图片草稿回归共 10 项通过。
- 实际浏览器选择工作区后，连续 Enter / Shift+Enter 保留三行文字；拦截统计发送请求为 0，中文组合输入未误发。原有视口参数保留；文字和图片刷新恢复、文件面板仍通过，页面脚本错误为 0。
- Android 13 真机运行实际 dsh 页面，两种内核均通过输入、键盘收起和再次弹出检查，测试草稿已清理。状态栏安全区为 84 像素；键盘出现时，网页原生边界为 `(0,84)-(1080,1387)`，与键盘顶部相接。Gecko 补充视口设置后，页面内部也随之重新布局，两行草稿和发送箭头完整可见。其无障碍树会把换行归一为空格，额外用截图确认真实分行。

证据保存在 `app/build/input-insets/`：`build-final.log`、`node-tests-final.log`、`host-browser-final.log`、`host-composer-final/composer-result.json`、`device-webview-final.log`、`device-gecko.log` 及对应截图和 JSON。测试使用调试包的独立验收入口；正式 APK 不包含验收入口。

## 交付与覆盖安装

| 安装包 | 大小 | SHA-256 |
|---|---:|---|
| `release/dsha-1.5-alpha.1.apk` | 175.58 MiB | `78d0e5093914b54ba2aab07eb4ec102c32f4c6efb6f12c252c6c015b3ec6b1a2` |
| `release/dsha-1.5-alpha.1low.apk` | 252.39 MiB | `431ae8d5611493b666894a803c5a097d0c5ef7bf0afaf00b36c1e4d07d0c1e28` |

新旧两版 APK 的发布证书 SHA-256 均为：

`e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`

包名、签名和版本码保持一致，可以手动覆盖安装。同一候选版本不改变环境身份，因此无需重建 Ubuntu。由 rc1.4 等旧环境升级时，仍沿用 1.5-alpha.1 原有的迁移机制。

每个 APK 的 121 项源资产逐项匹配；原生库与离线运行时包和替换前候选包逐项一致，沿用前轮 ELF 与冷安装验收。测试机采用独立测试证书，正式包另生成仅改签名的测试副本；两个副本的非签名 ZIP 条目分别 1035 / 1123 项与发布包完全一致。发布目录中交付的始终是历史发布证书签名的正式 APK。

原候选包及校验文件保留在 `release/history/20260909-before-input-insets-fix/`，当前同名 APK 和 `.apk.sha256` 更新为本次修订。

## 范围

本轮真机为 Android 13、ARM64、4 KB 页；Android 6/7 和其他厂商输入法仍需额外设备覆盖。本次未改变 Ubuntu、Node、Python 和签名，也未重跑前轮已经完成的模型往返、完整迁移及备份恢复全套验收；前轮结果见原真机验收记录。
