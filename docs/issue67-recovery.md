# Issue #67：维护循环、插件目录误报和旧预设

来源：[DSHA #67](https://github.com/DSH-APP/DSHA/issues/67)，2026-09-13。报告设备是小米 25091RP04G / Android 16，版本 0.1.5-rc2 / 129；目前连接的验收设备为 Redmi M2012K10C / Android 13，不能把它称作报告设备的直接复现。

## 已确认的原因与处理

`startup-observer.cjs` 原先只探测四个写死目录，遗漏了 Node 从 dsh 安装位置向父目录查找的全局依赖。`tools/test-issue67-startup.mjs` 使用锁定的 `dsh-app-boot.resolveBundleDir` 建立对照：17 个全局插件可被真实加载器定位，原观察器却报不存在。修复后观察器遵循相同两个 anchor 和 Node 顺序；不执行插件代码，也不要求 package.json 暴露在 exports 中。这证明可以产生同类误报的缺陷，不能据此断言反馈者所有插件实体一定仍在。

[梁神发布预设](https://raw.githubusercontent.com/zhu1090093659/dsh-web-ui/main/packages/dsh-liangshen/presets/liangshen/agent.cordis.yml) 使用 `persona.config.text`，而锁定的 dsh 0.1.5-rc.2 persona schema 要求 `prefix`。`persona-compat-patch.json` 在受管模块中兼容这个已知旧字段：仅当新字段未提供、旧 text 为字符串时映射，提示词原文、suffix 和其他配置保留。现代 prefix 优先；缺失两者或无效新字段仍报错。不会覆盖用户 `.agent-presets` 副本，也不会用空提示词掩盖错误。

`ExtractActivity` 将通用执行锁与数据维护状态分开显示，插件查询不再冒充持续更新。成功文案不作为就绪证明：就绪失败停留原生页，不自动跳回主界面触发循环。同一受管候选只自动尝试一次，自动启动被拒绝后也保留尝试记录；手动重试仍可用。完整、兼容、最新分开，兼容旧运行时在失败后可以继续使用，新资产候选只尝试一次。

维护事务在真实健康确认后再次核对环境就绪。已有 committed/rolled-back 的历史目录不构成未完成维护，不能通过删除安全副本来解除门禁。

非调试 PTY 回归还复现了关闭启动中的终端时的不完整进程信息：原实现把解析失败当作进程已消失，跳过停止后又一直等待 Java 退出通知。修复后区分“信息暂不可读”与内核确认的退出/会话变化，短暂重读并保持原出生身份核验。proot 使用原 SIGQUIT 清理 tracee，proroot 按独立会话回收；退出不明仍保留工作锁。

## 验证

- Node：`node tools/test-issue67-startup.mjs`；17 个全局插件解析与锁定加载器一致，未执行插件；persona 旧 text 原文、现代 prefix 优先及无效配置拒绝均通过。
- 原诊断回归：`node tools/test-startup-diagnostics.mjs` 通过。
- JVM：`EnvironmentIdentityTest` 验证成功但未就绪不跳转、同候选重开不反复自动更新；原事务测试保留“已提交历史目录不阻止启动”的断言。
- 标准版非调试真机：`app/build/backup-device-validation/1b45a83c-2459-426c-bebc-af60581ca148/`，五项通过，包含真实 proot、维护导航、终端执行命令及维护退出。
- 兼容版非调试真机：`app/build/backup-device-validation/5ba43bc5-9fd4-4767-8a7b-94cca3d764e2/`，相同五项通过。
- 显式传统 proot 终端分支：`app/build/backup-device-validation/816590b6-d547-40e5-bd90-ee0b71e97411/`，五项通过；此前设备所选 proroot 分支的进程状态与回收证据也已保留。
- 兼容版显式传统 proot 分支：`app/build/backup-device-validation/2a1e7aee-222a-411c-8320-ef9d752eb740/`，相同五项通过。
- 两版完整回归：`issue67-final-source-checks.log`，各 488 项，487 通过、1 原有跳过、0 失败/错误；两版 Release Lint 通过。

最终收尾：两版各 491 项单测（490 通过、1 原有跳过）、Release Lint 和打包通过。同最终生产代码的非调试真机五项回归再次通过，标准版报告为 `38182f6e-0977-484f-b4d1-08e1024e58c7`，兼容版为 `58e4d546-b4db-4ac2-96b3-abed18a9e4c0`。

已纳入 [rc2.1 本地交付](releases/v0.1.5-rc2.1-build130.md)。仍不能代表反馈中的 Android 16 设备已经直接验证；没有关闭或评论远程 issue。
