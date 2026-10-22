# 终端：PTY、简易模式与会话关闭

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`，并对照本轮工作树源码。本文说明当前合同，不是本轮设备或命令执行报告；旧审计 APK 不作为操作入口。

## 入口与选择

Main 的“终端”提供 PTY 与简易终端切换。选择保存在本机 `term_pty`；切换页面保留另一种模式的既有会话，并非把同一 shell 转换成另一种协议。两组标签由应用级 [TerminalSessionOwner](../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) 持有，Fragment 只附着观察者；切页、旋转或语言重建不会主动重启终端。应用进程被系统结束后不承诺恢复原 shell 进程。

| 模式 | 适合的操作 | 实际行为与限制 |
|---|---|---|
| PTY | vim、htop、tmux、交互提示、逐键输入 | [PtySession](../app/src/main/java/com/deepseekharness/app/PtySession.java) 接 Termux terminal-emulator/JNI；扩展键提供 Ctrl、Alt、Esc、Tab、方向键，支持字号调整与缩放 |
| 简易 | 按行提交命令、查看文字结果 | 管道 shell，命令排队并串行发送；不模拟完整终端，需交互输入/TUI 时切 PTY |

入口实现为 [PtyTerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java) 与 [TerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java)。新标签使用当前环境准备与运行方式；已有标签继续持有出生时的进程身份。运行方式的选择与兼容边界见[运行引擎](runtime-engines.md)。

## 会话、编号与命令状态

[TerminalTabs](../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) 的内部 ID 永久递增，显示编号复用已成功关闭的空缺。关闭中或关闭失败的标签继续占号；不能根据显示编号向新会话投递旧回调。PTY 与简易模式各自保存标签与当前选择。

简易终端的 [TerminalSession](../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java) 状态为 `STOPPED → STARTING → READY/BUSY → STOPPING`，失败进入 `FAILED`。启动握手先核验固定 bootstrap 的 PID、会话与出生身份，才允许发送用户命令。尚未发送的命令可等待就绪；初始化超时不表示执行过用户命令。

“中止”会终止本次简易 shell 的进程组，确认退出后创建新 shell；`cd`、`export` 等 shell 内状态随之清除。尚未发送的命令保留，已经发送的命令不会自动重放。部分写入失败属于结果未知，界面提示先核查结果，不能以“重试”自动重复可能有副作用的操作。关闭标签会取消未发送队列。

简易输出经过 ANSI 清理和敏感文本脱敏；缓冲超过 300000 字符时保留后 100000 字符。这只是界面输出上限，不是完整命令审计归档。单条简易命令按 Java 字符串长度限制为 16384，空值及 NUL 被拒绝。

## 关闭、维护与失败

关闭标签先标记 closing，再在工作线程等待退出。只有 [TerminalSessionOwner](../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) 确认成功才移除标签；失败解除 closing 并保留原会话，允许重试关闭。环境维护会同时处理 PTY 与简易模式，也覆盖已 fork 但尚未发布标签的 PTY。

[TerminalProcessCloser](../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) 使用保存的进程身份与会话关系检查退出。不能把 `/proc` 暂时不可读、超时或启动器退出等同于整个 guest 已退出；核验失败继续保留环境占用，不按裸 PID、端口或名称强杀。现用环境不能在该屏障未解除时移动或重建。

终端、插件和 DSH 处于应用的 Android UID 运行域。原生终端允许执行 shell；设备桥的命令白名单不构成终端的强沙箱。输入命令前应按真实工作目录和权限判断影响，详见[安全模型](security-model.md)。

## 核验入口

修改后至少选择与变更相关的 [TerminalTabsTest](../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java)、[TerminalSessionTest](../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java)、[TerminalSessionOwnerTest](../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)、[TerminalRuntimeTest](../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java)。它们分别覆盖编号/关闭、队列与未知结果、所有权和启动输入；测试源码存在不表示本轮已重新执行。真实 PTY、IME、ROM `/proc` 行为仍须精确正式 APK 的设备记录。
