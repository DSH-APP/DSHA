# DSHA 0.2.1-rc.3-20261026.1555-dsh0.2.1-rc.3 / build200

- 修复原生插件页把 `@deepseek-ai/dsh-experimental-terminal-bundle` 显示为“加载状态未确认”：该 bundle 的组内可选子项（pwsh）用 `!!js` 条件禁用，在 Android/Linux 上不进入 init，采样器此前把它当成 unknown 并拖累整组。现在条件禁用的可选子项不参与整组结论，采样也不重新执行 `!!js` 表达式；terminal-bundle 应显示为已加载，列表达到 20/20。
- 保留 build199 的 `/data/user/0` → `/data/data` 快照路径规范化修复；build199 已在设备上确认从 20/20 未确认降到 19/20，本轮补齐最后一项。
- 新增真实 Cordis Loader 回归：组内 `!!js` 条件禁用子项不把整组拖成 unknown/unloaded，且采样期间表达式计数不增加。
- 边界：DSHA 自启动到独立虚拟屏的 WebView 原生崩溃仍不在本包范围；本轮由 CI 编译、JS 证据回归与单测验证，设备安装验收在构建完成后执行。

版本码提升到 200；包名、历史签名、常规文件名及内置 DSH rc.3 保持。本分支只跑 Standard。
