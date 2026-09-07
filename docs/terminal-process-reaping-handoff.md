# 简易终端与 Install 进程回收接线

## 最终集成结果 · 2026-09-08

主流程已完成 `FragmentSessionInstrumentation all`：真实 sleep 清理、重启、首条输入、排队一次及用户状态隔离全部通过。`DiagnosticPtyAudit` 在标准/兼容两版各 **16 条通过**，实际诊断修复及 PTY 的 shell/sleep 均回收；两版 proot 超时/中断各 **12 条通过**。PTY fork 后异常路径已改为保留任务保护，直到真实退出；结束使用身份校验后的 proot 清理信号。无法启动退出监听的罕见异常保守保留环境保护，避免在未知进程状态下移动文件。完整证据见[功能验收](functional-audit-rc1.4.md)。

以下为当时交接记录，设备独占、未实测及待修复说明均已被以上最终结果更新。

交接对象：主线程 / Helmholtz。2026-09-08。

主线程已向安装 owner 转交本文件。后续只读复核时共享回收/fence 接口已有更新，见下方补充；这不代表终端已通过真机验收。

## 所有权与现状

- 手机及 Gradle 继续由 Helmholtz 独占；本任务只改自己持有的 TerminalFragment、TerminalSession 和对应测试，使用独立 javac/JUnit。
- 不修改 Compat、ProcessIdentity、ProcessTermination、InstallProcess、ProotBootstrap 或 PtySession；通用回收逻辑由对应 owner 维护。
- 简易终端的实际取消、退出和进程清理 **尚未通过真机验收**。主机测试/编译不能替代 `FragmentSessionInstrumentation only=all`。

## 已按共享接口接入

1. `TerminalFragment.terminalBackend().terminate()` 先向本会话握手确认的 setsid 进程组发送原生 SIGKILL，不在维护中另起 proot/prepare 脚本。
2. launcher 仍未退出时调用通用 `Compat.destroy(process)`；退出等待采用 `ProcessTermination.awaitExit(process, millis)`。保留“未确认退出则抛异常”的断言。
3. 当前读到的通用实现会核验 Android Process 的 PID、父进程与 starttime；proot 尝试 SIGQUIT 清理 tracee，之后才回退到同一进程的强杀。终端不复制这套 PID 识别/信号升级逻辑。
4. `TerminalSession` 已将阻塞式 `InputStreamReader.read` 改为只读取 `available()` 指示的字节，并增量解码 UTF-8。目的是避免静默管道读线程与 Process 回收/close 互等；汉字、emoji 和分包协议标记仍需保持完整。
5. backend 回收失败时，TerminalSession 保留旧 Run，显示失败，不创建新 shell。维护调用 `TerminalFragment.shutdownShellAndWait(10_000)` 失败时必须停止环境移动。

## 请 Helmholtz 确认后交回主线程

- 通用 `Compat.destroy(Process)` 与 `ProcessTermination.awaitExit` 的最终契约是否保持上述行为；终端无需另写 launcher 回收策略。
- 简易终端“先杀独立 shell 组、再回收 proot launcher”是否与此次 SIGQUIT/tracee 清理顺序兼容；若实测需要调整顺序，请只交回建议，由终端 owner 修改自己的 backend。
- 是否还有 Android Process 管道关闭或回收线程的额外限制。终端已移除空管道阻塞 read，但此改动没有替代真机验证。

## 交回后保持现有严格验收

主线程运行 `com.deepseekharness.app.ui.FragmentSessionInstrumentation`，`only=all`，至少核对：

- `terminal_sleep`：真实 sleep 子进程停止，旧命令没有继续写入标记，新 shell 的排队命令只执行一次。
- `terminal_exit`：立即排队和观察到退出后再输入两条路径，首命令均不丢失、不重放。
- `terminal_cleanup`：本次 launcher/子进程已回收，独立临时目录清除。
- `isolation`：用户插件状态与真实 Web 代次/鉴权地址未被修改。

不得用延长到 sleep 自然结束、忽略残留 launcher、吞掉 cleanup 异常或降低断言来换取 PASS。

## RuntimeTaskRegistry fence 交叉复核（2026-09-08）

本轮仅改自有 Terminal/Plugin 代码与对应测试；RuntimeTasks、RuntimeTaskRegistry、ProotBootstrap、PtySession 只读。

已修复自有层：

- TerminalSession 在 backend.open/prepare 之前取得独立 lifetime；生产 backend 使用 `RuntimeTasks.beginDetached()`。只有 backend 确認清理完成之后才释放，回收失败保持旧 Run 和 token。没有取得进程句柄的模糊启动失败采取保守阻塞，不允许维护 rename。
- PluginRepository 在任务入队之前登记 detached token，持有范围覆盖 worker、列表同步、主线程 PluginTask 清理。普通返回/报错不会提前关闭其他进程保留的 token；本层清理抛错也不交还环境所有权。
- 新增 fence 先于启动时不调用 backend.open、进程终止失败时不放行维护、启动前失败正常释放、启动结果不明确时保持阻塞等主机回归。

复核到的共享层最新版本已覆盖早期问题：

- `ProotBootstrap.execRootfsInteractive()` 已在准备任何文件前 `beginDetached()`，并使用 `retainUntilExit(process)`。
- `collectRootfs()` 在清理未确认退出时保留原 token；`RuntimeTasks.retainUntilExit()` 原子 detach，并使旧调用方 close 不会解除仍存活进程的保护。不能继续把旧版 execAndRead 的错误释放问题列作当前缺陷。
- Registry 的 `begin` 与 `tryEnterMaintenance` 共用同步锁；已有 detached 任务阻止 rename fence，fence 存在时同线程的 detached 启动也拒绝。

仍需 PTY owner 修复的明确问题：

- `app/src/main/java/com/deepseekharness/app/PtySession.java:100` 在 `initializeEmulator()` 抛 RuntimeException/Error 后无条件 `ps.work.close()`。
- 已用本机缓存的 terminal-emulator 0.118.0 `javap -c` 核对：`initializeEmulator()` 先在字节码偏移 46 调用 JNI.createSubprocess、56 保存 mShellPid，之后在 109/149/187 才启动输入、输出与 waiter 线程。后续线程启动失败属于已 fork 的异常路径。
- 该分支会令仍存活的 TTY 从 Registry 消失，使下一次维护获得 fence 并 rename。建议 fork 前失败才直接 close；fork 后必须保留 token、按本次 session/PID 清理并确认退出。waiter 可能没有启动，不能仅假设稍后一定收到 onSessionFinished。
- 此处仅报告，没有修改 PtySession。

本轮独立 Java 17 javac 编译通过；主机 JUnit 34 项中 33 项通过、1 项 POSIX 实进程用例因 Windows 跳过。仍需主线程在设备交回后运行 FragmentSessionInstrumentation all；没有操作设备或 Gradle。
