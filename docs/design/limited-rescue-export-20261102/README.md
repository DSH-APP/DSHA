# 受限状态「导出可读副本」入口原型

状态：待用户确认方案；原生实现进行中（分支 `fix/limited-rescue-export`，基于 build271 `e8920660`）。

- `preview.html`：离线交互原型。顶栏切换设备（手机竖 / 横、平板竖 / 横）、页面（维护页 / 受限主界面·数据与备份 / 救援确认）、维护状态、日夜、中英文、1.3× 字体，以及改前 / 改后对比。
- `check-preview.cjs`：Playwright 回归（`PLAYWRIGHT_HOME=/usr/local/lib/node_modules node check-preview.cjs`），检查脚本错误、外部请求、横向溢出、改后触控目标 ≥ 44dp，截图写入 `screens/`。

## 已知限制

- 手机横屏（高 360dp）首屏只露出阶段卡顶部，入口需要滚动才看到；与现有说明文字的可见性相同，本次不调整。
- 平板横屏设置页左栏为示意，以 `SettingsPanes`（≥ 840dp 双栏）实际渲染为准。
- 最终以真机 `LayoutAuditInstrumentation`（日夜、短屏、1.3 倍字体、中英文）为准。
