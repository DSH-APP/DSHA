# dsh 0.1.5-alpha.2 适配与验收

工作开始于 2026-09-09，交付日期 2026-09-10。范围包含启动性能、两张维护报错、取消启动计数备份、dsh 与内置插件升级，保留此前的设备权限、输入法、鉴权和日志修复。

## 依赖与功能保留

- npm `alpha` 指向 `0.1.5-alpha.2`；主 `latest` 标签仍为较早版本，因此按 alpha 精确版本锁定。运行时由 `tools/dsh-runtime/package-lock.json` 和生成摘要约束，Linux arm64 安装禁用宿主安装脚本。
- dsh 覆盖层 SHA-256：`d829714983447443743095485986ecea6b0decf2a99152193343b4a320547cf3`。
- 移动 UI 来自 `dsh-web-mobile@2.4.0` 的官方 npm 包，随包保留 MIT 许可；DSHA 版本为 `2.4.0-dsha.1`。升级新增的 `lib/delete-session.js` 一并交付。设备指南更新为 `0.1.16`，其余内置通知和悬浮状态插件核对现有事件接口。
- 官方文档预览、文件交付、反馈模块按字节摘要与锁定 npm 包比较。只对已核对的原子文件发布、旧会话迁移、输入键位与 Tooltip 做原有适配；不覆盖新版整套前端。
- 保留上游新增主面板、右栏及默认模式行为，不自动启用实验性配置。上游范围见 [alpha.2 发布说明](https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v0.1.5-alpha.2)。

## 启动和更新

`ProotBootstrap.ensureDshRuntimePatches()` 原先每次执行旧 `.l2s` 全目录扫描，默认工作区 `/root` 会包含项目依赖。这项工作已移出启动路径。`RuntimeTools` 使用 APK 身份、根目录身份和受管文件状态判断是否重用，普通命令不再反复读取大段前端 JS；显式工具修复强制重放资产。

版本码 115、基础环境版本 10。`ManagedRuntimeLayout` 限定局部更新路径，`RuntimeUpdateTransaction` 在磁盘记录切换意图，再逐项保留旧树并改名切换。会话、设置、项目、第三方插件不属于更新清单。`EnvironmentMaintenance.update()` 验证新 Node、Python、原生模块和 dsh 后提交并清理旧受管树；失败及进程中断可回切。不同基础环境仍走原有完整备份、个人目录保护和重建。

自动备份删除范围包括启动调用、计数、原生配置控件、输入验证、备份设置导出与恢复；历史键仅用于移除。

## 两类维护错误

1. `Integer cannot be cast to String`：配置读取兼容历史整数、长整数、字符串和布尔值；损坏值按原默认值降级，事务回滚保留数字原类型。
2. `dsh-file-upload → mammoth`：备份记录插件原有缺失依赖，保留实际存在的源码和依赖，继续保护其余数据。导出结果和恢复预览显示缺失项；恢复不会假装补装了原本不存在的模块。依赖图仍校验包名、引用和逐文件摘要。

依赖恢复同时改用相对链接，避免 guest 绝对路径在 proroot 下无法解析。生产备份/恢复固定使用 proot；测试也覆盖嵌套版本、循环依赖、源码历史和中断回滚。

## PDF 与 UI

真实系统 WebView 打开 PDF 时发现 `Map.getOrInsertComputed` 缺失。共享兼容脚本补齐 Map / WeakMap 插入接口及 `Response.bytes()`，保留浏览器已有实现。语义参照 [TC39 Upsert 规范](https://tc39.es/proposal-upsert/)，测试已有 `undefined`、回调重入、键校验、不可枚举属性和原生函数身份。

系统 WebView 在文档开始注入；Gecko 扩展由同一资产生成页面世界注入脚本。移动 UI 的文件动作同时声明 npm 模块依赖和 Cordis `sidebarRight` 服务。新增删除会话接口先调用 `connection.requestRejection()`，再读取有限请求体和执行删除。

## 实际检查记录

设备：Xiaomi 2410DPN6CC / Android 16 / arm64 / 4 KB 页，历史发布签名覆盖安装，未清除应用数据。

| 检查 | 结果 |
|---|---|
| alpha.1 → alpha.2 局部更新 | 7,638 ms，生产事务验证通过 |
| 升级数据保护 | 8 类会话、设置、索引、附件、插件及工作区摘要一致 |
| 连续六次手动启动 | 8,584 / 8,526 / 8,485 / 8,530 / 8,532 / 8,434 ms，均未自动备份 |
| 20 次运行工具准备 | 合计 78 ms |
| 独立冷环境 | 解压与离线工具安装 17,297 ms；完整验证 20,759 ms |
| 旧数字配置 | 真机隔离偏好：读取、导出、恢复及回滚通过 |
| V3 / 旧 V0 会话、内置指南、移动删除、原子发布 | 16 项在手机 Linux arm64 通过 |
| 备份与恢复 | proot 上 31 项通过；另 1 项 APK 源资产指纹在宿主验证 |
| 新版文件面板 | 系统 WebView 中 Markdown 标题/代码块、HTML sandbox iframe、PDF 画布、PNG 原图解码通过 |
| low / Gecko 143 | 实际触摸进入已有会话、文件面板、展开目录并打开 PDF，画布正常显示测试文本 |
| 删除入口鉴权 | 未鉴权返回 401；已鉴权的不存在会话返回 404；来源拒绝另有单测 |

Android 6/7 实机和真实 16 KB 页设备未在本次新增验证范围内。Python 在 proroot 上直接打开多层嵌套链接仍存在运行时差异；生产 proot 备份路径及 Node 依赖加载通过，不把该差异归为已修复。

Gecko 文件树的 Android 无障碍点击反馈不稳定，自动标签定位未完成该用例；最终使用实际屏幕触摸和截图核对 PDF 绘制，未把失败的自动定位算作通过。测试文件均位于明确创建的独立目录。

## 首次交付校验（停止检查修订前）

以下初版文件已存入 `release/history/20260910-before-pid-stop-fix/`。同名最新修复包和当前摘要见[停止检查修订记录](pid-stop-fix-2026-09-10.md)。

- 两版各 316 项 JUnit：315 通过，1 项平台条件跳过，0 失败。包含旧配置类型及运行时切换/回滚的新测试。
- 32 项宿主 JavaScript 回归全部通过；另 16 项 Linux arm64 会话与文件发布测试全部通过。
- 两版 Release 和 Lint 完成，0 错误；已有警告标准版 427 条、low 383 条。
- `verify-dsh-upgrade-apk.py` 从真实 APK 检查 dsh、原子发布/会话迁移适配、4 个内置插件、486 个共享依赖别名、28 个 Ubuntu 离线包和原始功能模块摘要，均通过。
- 两版 `zipalign -c -P 16 4` 通过；11 / 25 个 arm64 原生库与此前 alpha.1 逐字节一致。low 的 API 23 二进制和发布签名保留。
- 历史签名证书 SHA-256：`e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `dsha-1.5-alpha.2.apk` | 184531921 | `0d09051781869a4461a7a54213e60ac3cefbb9f09f1be4f6fea02cee6581b871` |
| `dsha-1.5-alpha.2low.apk` | 265047519 | `53acad21a9fb40bd5d333757f62dae57aa8dbbf4a469d97931549864a078d873` |

交付目录 `F:\DSHA_RESTART\release`，每包旁有同名 `.apk.sha256`。仅本地交付，未上传远程仓库或发布站点。

收尾已按摘要校验并移除预览夹具，清理独立冷环境与测试源码。最终安装的是交付目录中的标准版 Release（非 debuggable），版本码 115，启动主页面成功。历史 alpha.1 两个 APK 的 SHA-256 与任务开始前一致。
