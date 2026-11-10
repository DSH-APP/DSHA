# DSHA 0.2.1-rc.5-20261107.0330-dsh0.2.2-rc.1 / build336

- 内置 `dsh-web-mobile` 同步上游 3.3.1 并恢复默认启用（T101，`517078d2`）：按用户选定的 C 方案，以上游 3.3.1（`4e71eb67`，适配宿主 0.2.2）为基座、**完全追随上游**，只保留宿主集成必需的最小集；上游修整页白屏、顶栏开关被裁、文件键消失、页签叠字与文件面板标签条被顶栏盖住，固定 HEAD 而非 v3.3.0 / v3.4.0 标签（后两者 react 仍为必选 peer）。`lib/client.js` 摘要 `7f82fac2…`；默认启用后用户显式禁用仍然优先，内置注册表不再有 `defaultDisabled` 条目。
- C 方案只比纯上游多五类宿主集成：① 齿轮几何让位 `DSHA_GEAR_FIT_V1`（上游仍按连接指示灯存在性判断，0.2.2 首连可能误判，改为实测几何）；② 安全区变量包装 `--dsha-safe-area-inset-*`（Android WebView 不报告系统栏 inset，跳过上游已包好的一处，不嵌套）；③ 服务端三个模块替换（删除会话走宿主鉴权 `connection.requestRejection`、压缩只作用于当前 webServer 端口、live 会话经公开 AgentHandle 删除）；④ 原生文件传输（`lib/file-transfer.js` + `/api/dsha/files/{prepare,release}`，保存到 / 分享 / 下载记录 / 图片保存，32 MiB 分享限制与失败回退）；⑤ 多语言与 live-locale（8 种附加语言，移动壳标签不再回退英文）。
- 删除陈旧本地补丁（上游 3.3.1 已覆盖或不再采用）：`DSHA_SESSION_INPUT_V4`（会话行触摸单击 / 抽屉关闭改写）、手机快捷键弹层搜索行与可见带 CSS、`DSHA_FONT_FAMILY_ROW_V1` 字体族行换行、`DSHA_BACKDROP_PRESS_V1` 遮罩按压染色、宽屏触屏档收起规则（上游 PR #257 已接管）；`test-mobile-backdrop-press.mjs` 与 `test-mobile-modal-behavior.mjs` 随之退役，host-tests manifest 252→250 项。上游已接管的项不再重放。
- 已知风险与接受项（真机未验证前不写通过）：335→336 首次覆盖安装待验证，验收项为主界面不白屏、侧栏与 footer 只有一个设置入口、`dsh-web-mobile` 默认启用、文件面板原生保存/分享/下载记录可用、10 语言标签正确、个人数据完好。上游删除路由本身无宿主鉴权钩子、压缩是进程级全局、删除生命周期未捕获公开句柄，因此 C 方案保留宿主侧服务模块替换（已加鉴权与端口约束）；原生文件传输与 8 种附加语言属于宿主集成，不是上游能力。按上游行为接受的回归：手机快捷键弹层无搜索、设置字体族行可能被压窄、深色主题按压遮罩会亮闪；会话行单击与抽屉关闭回到上游实现。
- 版本与受管输入：版本码提升为 336（基于 build335）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。合并插件替换后按 `tools/prepare-runtime-descriptor.py --write` 重算 runtime-descriptor（runtimeId `5b502f71…`）；版本与文档改动不改变它；固定输入未重建，不新增受管运行时。
- 验证范围：`:app:testStandardDebugUnitTest`、`:app:lintStandardRelease`、宿主 contracts 整组 12 项、全树格式检查、`git diff --check`、文档链接、`check-ui-i18n`、`generate-changelog --check`、`test-terminal-home-i18n.py`、`test-ui-language-catalog.py`、`verify-runtime-descriptor-source`、`prepare-runtime-descriptor --check`、host-tests manifest、`generate-builtin-plugins --verify` 与 `test-mobile-*` 整组；结果以本机发布准备回执为准。未 push、未合并、未发布、未安装。

## 真机验收回执（2026-11-07）

- 设备：本次单台测试机（型号/Android/WebView 以现场为准；未扩写为矩阵）。
- 安装：从 build335 覆盖安装 build336（versionCode 336 / versionName `0.2.1-rc.5-20261107.0330-dsh0.2.2-rc.1`）。
- 结果：**335→336 首次覆盖通过**；主界面不白屏、侧栏与 footer 只有一个设置入口、`dsh-web-mobile` 默认启用、文件面板原生保存/分享/下载记录可用、10 语言标签正确、个人数据完好、状态 READY。
- **已知问题（非阻塞，功能验收通过）**：每次从网页返回原生主页面再回网页时，网页 UI 侧边栏连接状态重走「黄→绿→隐藏」，原生侧出现一次加载遮罩；根因为 WebView/页面未保活（非 build336 代码回归，默认启用移动插件后暴露）。已建 T110（K2+K5）修复，目标 build337；修复后本回执再更新状态。
- 发布侧证据：tag `dsha-a-su-build336-20261107-033000`，release run 42287214579 success，APK SHA-256 `c35e6205…858e`（完整值以 Release 资产为准）。
- 未验证范围：其他机型 / ROM / Android 版本矩阵；长时间运行、极端存储与非默认端口组合等未纳入本次验收。
