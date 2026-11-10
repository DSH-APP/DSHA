# build206 终端后续修复与本地验证

日期：2026-10-28。实际工作区为 `/root/Documents/deepseek-harness/default-workspace/DSHA-Sunset-mod-3090-Q`，工作分支 `Sunset-3090-Q`。本文记录本轮源码与实际运行的软件检查，不是 APK、CI 或设备验收回执。

## 源码身份与合并

开始时 HEAD 为 `c6415f130128b1284d958baf4e7a63d1e6214b26`、versionCode 205。现场查询和 fetch 确认私有默认主分支为 `codex/prerelease-190`，目标提交为 `490ba973a30486e0a76d599faf4d9657da61a355`，新增 8 个提交。已使用 `git merge --ff-only` 完成快进；最终 `HEAD...origin/codex/prerelease-190` 为 `0 0`。

沿用主分支已确定的 versionCode **206**、`0.2.1-rc.3-20261028.1200-dsh0.2.1-rc.3`。主分支完整合并范围另见[build206 整合记录](20261028-build206-integration.md)和[局域网密码修订](20261028-build206-lan-password.md)，这些文档的历史测试不转记为本轮结果。

三处重叠文件的暂存补丁恢复成功，无冲突。主分支的中断/取消防重放逻辑、已附着窗口焦点夹具与本地修复同时保留。开始时已有的 [EnvironmentIdentity.java](../../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java) 和[对应测试](../../app/src/test/java/com/deepseekharness/app/util/EnvironmentIdentityTest.java)仍以未提交改动保留，已纳入本轮主机测试。恢复备份为本地 stash `4873faa68cd2685b2732c3d1f9edbd2807840cb5` 及 [原有补丁](../../artifacts/terminal-more-20261028/preexisting-environment.patch)。

当前修复尚未创建新提交或推送；远端历史和未跟踪的旧证据目录未清理。

## 修复范围与调用链

- 输出快照：在 [TerminalOutputSnapshot](../../app/src/main/java/com/deepseekharness/app/util/TerminalOutputSnapshot.java) 中先读取当前保留缓冲区作为上下文，经 [SensitiveData](../../app/src/main/java/com/deepseekharness/app/util/SensitiveData.java)识别敏感范围，再投影到原始屏幕或历史范围。复制、分享和保存继续消费同一固定快照，脱敏替换变短不会带出未选择的正文。
- 长输出边界：保留 65,536 个 UTF-16 单元、10,000 行、软换行逻辑行及代理对边界。未闭合私钥块与引号凭据隐藏至输入末尾；已登记秘密使用 KMP 扫描并合并重叠命中。完整上下文超过 1,048,576 个 UTF-16 单元时，两种范围均保守停止预览，避免导出缺少上下文的尾部。[输出弹窗](../../app/src/main/java/com/deepseekharness/app/ui/TerminalOutputDialog.java)显示准确提示并禁用空正文的导出。
- 侧栏焦点：[TerminalActivity](../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java)在打开抽屉或由内联变为抽屉时接管已有输入焦点；刷新会话行时按会话与动作身份恢复；短窗重排先恢复列表位置，再确保当前聚焦控件可见。按钮保持首击直接执行。[生命周期回归](../../app/src/test/java/com/deepseekharness/app/ui/TerminalActivityLifecycleTest.java)新增四项，并沿用主分支实际附着窗口和 ViewRoot 输入模式的夹具；这些 Robolectric 用例本轮未执行。
- 版本与文案：[版本页测试](../../app/src/test/java/com/deepseekharness/app/ui/UpdateVersionPageTest.java)由旧 build205 正文断言改为 build206 的中英文内容；保留十语资源身份和完整正文检查。补齐主分支遗漏的“启动请求未执行，请检查运行状态后重试。”与“复制地址”十语条目，并更新预览提示及真实字节摘要。最终目录为 **4,911 条 × 10 语**。中英文 README、CHANGELOG 与 AGENTS 顶部统一当前源码身份，旧条目作为历史保留。

## 实际验证结果

| 检查 | 本轮结果 |
| --- | --- |
| JDK 17 主机 JUnit | **95 项通过**；覆盖真实 TerminalEmulator/TerminalBuffer 的快照与裁切、脱敏、输入、侧栏策略、快捷键、环境身份、任务状态、版本说明及输入接线合同。 |
| 终端与启动页十语检查 | **7/7 通过**，包括生成 Java 字典的实际查找与格式化。 |
| 通用 UI / 语言目录回归 | **9/9、9/9 通过**。 |
| 版本说明生成器回归 | **8/8 通过**；最终 build206 十语正文与 raw 资源逐字节检查通过。 |
| 全局语言门禁 | 新增问题 0、失效声明 0。 |
| Android 语言资源 | **84 + 180 个资源名 × 10 语**检查通过。 |
| 源码门禁 | 受跟踪文本、固定摘要、工作流 pin、工具链身份及历史字节策略检查通过。 |
| Java 格式与语法解析 | 固定 google-java-format 1.22.0 / JDK 17 处理 10 个所选 Java 文件，5 个需格式调整；精确 Java token 保持一致。此项不是 Android 类型检查。 |
| 补丁检查 | `git diff --check` 通过。 |

最终 JUnit 证据在 [host-junit-116cf2c5](../../artifacts/terminal-more-20261028/host-junit-116cf2c5/junit.log)，其余最终检查在 [checks-eccb8491](../../artifacts/terminal-more-20261028/checks-eccb8491/results.json)，格式证据在 [build206-terminal-followup.json](../../app/build/java-format/build206-terminal-followup.json)。主机入口使用真实终端模拟器和缓冲区，仅对 Android Base64、TerminalSession 类型和系统语言入口提供适配，不运行 Android UI、真实 PTY 或原生进程。

保留的前序结果与失败：

- 初次主机 JUnit 为 94 项通过；加入长重复秘密的线性匹配回归并完成最终格式处理后，重新编译运行上述 95 项。
- 首轮终端语言回归发现主分支新增的两条启动页文案缺失，日志保留在 [checks-5502034a](../../artifacts/terminal-more-20261028/checks-5502034a/terminal-i18n.log)。补齐十语和摘要后完整检查通过，未将首次失败写成通过。
- 已尝试限定 Standard 的 `testStandardDebugUnitTest`；构建入口报告缺少 Android 37 平台包，退出码 1，在 Android 编译和 Robolectric 测试前停止。因此不声称新侧栏用例或版本页用例已执行。
- Git 传输成功；`gh auth status --hostname github.com` 返回已有 token 无效、退出码 1。未绕过认证或触发工作流，无法确认目标提交的 CI 结果。
- 一次组合 Git 只读查询在宿主 proroot 中以 SIGSEGV/139 结束；独立有界查询随后成功获得祖先关系、变更清单和目标版本。该失败不涉及写入，也没有被算作测试成功。

## 验证边界

本轮未运行 Low，未构建、安装或发布 APK，未操作实体手机或虚拟屏，未推送或触发远端构建。Android 窗口、TalkBack、旋转、系统分享及保存选择器仍需完整工具链和独立设备验证；Android 主线程耗时也未测量。

脱敏规则不保证识别任意秘密。完整上下文仅指当前终端仍保留的缓冲区：如果 BEGIN 或凭据字段前缀在捕获前已被历史淘汰，且剩余文字无可识别模式，无法恢复该上下文。本轮宿主结果不替代全量 Standard、Android 编译、Lint、最终 APK 或设备验收。
