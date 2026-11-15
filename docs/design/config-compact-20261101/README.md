# 运行配置页压缩重构

状态：v2 已实现。分支 `ui/config-compact`，日期 2026-11-01；版本号由发布时统一分配。

v2 已落到 Android 源码（`fragment_config.xml`、`ConfigFragment.java`、`util/ConfigDraft` + 单测、`values/config_page.xml`、25 条新文案 × 8 语）。**未装机，未在真机验证**。

| 文件 | 内容 |
|---|---|
| [preview.html](preview.html) | 单文件离线原型：手机 360/412dp、平板竖屏/横屏、双栏，深浅色、1.3× 字体、短屏；可与现状对比并实时测量 |

## 现状问题（`fragment_config.xml` / `ConfigFragment.java`）

- 页面大标题 + 图标块与顶栏“运行配置”重复。
- 运行方式、DNS、网页内核都是整宽下拉；内核只有 System WebView 一个选项。
- 自动端口开启时，禁用的端口输入框、说明和“恢复旧端口草稿”仍占四行。
- 局域网开关、密码按钮、密码状态、HTTP 提示分四行，关闭时也全部显示。
- 两个 proroot/proot 兼容开关很少改动，却单独占一组。
- 保存按钮整宽常驻，不显示是否有未保存改动；平板横屏下也是整宽单列。

## 方案

见原型右栏“压缩了什么 / 不变的约束 / 待确认”。要点：

- 保留全部既有 id、`ConfigStore` 键、保存时机和 `EnvironmentTaskGate`；画中画与局域网密码仍即时生效，不计入未保存数。
- 端口校验仍走 `ConfigInput.port`。
- 详情区宽度 ≥720dp 时两列（按窗格宽度，不按设备类型）。

## v2（当前推荐）

在 v1 基础上继续压低高度：行高 56→48dp；五组卡片合成两张（高级并入运行与网络，悬浮条并入显示与启动）；页面说明并入首个分组标签行；网页内核改为副文案；数据与备份 / 恢复旧端口草稿 / 项目主页改为卡片下方链接按钮；保存条单行。

离屏测量（默认状态，内容高度 / 首屏可见项）：手机 360 现状 1948dp·4 → v1 1057·10 → v2 611·14，一屏放下；平板横屏 1905·4 → 685·14 → 629·14。非 Android 实测。

## Android 实现要点

- 带副文案或行尾按钮的开关行 = 标题列 + 行尾按钮 + 无文字 `MaterialSwitch`；标题列点按切换开关，开关的无障碍描述同步为“标题，副文案”。
- DNS 用原 `config_dns_mode` RadioGroup 做可见分段，`config_dns_choice` 下拉删除；`config_core_choice` 改为副文案 TextView。
- 运行方式仍是 `DshaSelectView`（同一底部选择面板），只去掉输入框底，整行点按打开。
- 未保存数由 `ConfigDraft.changesFrom` 计算；撤销恢复到上次保存快照。画中画开关、局域网密码不进草稿。
- 两列由 `config_columns` 的实际宽度决定（≥720dp），超过 1080dp 两侧留白居中。
- 新文案在 `messages-extra.json`（`cfg249a01–27`，其中 a17/a18 与 `diag248a17/18` 同源文，改为复用后者，实为 25 条），8 语译文已补，`check-source-hygiene.py` 的字节钉已更新。DNS 分段的 `IPv4` 用不翻译资源串 `cfg_dns_ipv4`。

## 待确认

- 静态加载器是否只对 proroot 生效未确认，不按运行方式隐藏。
- 手机上悬浮底部导航是否遮挡保存条：保存条位置与改动前一致（页面底部），未在真机核对。
- `LayoutAuditInstrumentation` 的中英文 / 日夜 / 短屏 / 1.3× 布局验收需装 debug 包在设备上跑，本轮未执行。
