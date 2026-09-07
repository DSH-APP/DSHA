package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.*;
import android.widget.TextView;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.UUID;

/** 诊断真实按钮与独立 PTY 子进程回归；不使用用户的终端实例、命令或剪贴板。 */
public final class DiagnosticPtyAudit extends Instrumentation {
    private int checks;
    private DiagnosticActivity page;
    private PtySession pty;
    private int child = -1, shellPid = -1;
    private File directory;
    private void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void ui(Runnable action) {
        Throwable[] error = {null}; runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error[0] = t; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void await(java.util.function.BooleanSupplier ready, long millis, String why) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + millis;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40);
        check(ready.getAsBoolean(), why);
    }
    private boolean alive(int pid) {
        if (pid <= 1) return false;
        try { Os.kill(pid, 0); }
        catch (ErrnoException error) {
            if (error.errno == OsConstants.ESRCH) return false;
            throw new IllegalStateException("无法确认本次 PID 存活状态", error);
        }
        try {
            // 已退出待回收的 zombie 没有执行能力，和仍可读写环境的进程区分开。
            String stat = Compat.readAll(new File("/proc/" + pid + "/stat"));
            int close = stat.lastIndexOf(')'); return close < 0 || !stat.substring(close + 2).startsWith("Z ");
        } catch (IOException e) { return true; }
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); Context app = getTargetContext();
        try {
            check(!RuntimeTasks.isBusy() && !EnvironmentTaskGate.isBusy(), "有其他环境工作，请稍后测试");
            for (String cmd : new String[]{"input keyevent 224", "wm dismiss-keyguard", "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity"}) {
                try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(cmd);
                     InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) { while (in.read() != -1) { } }
            }
            ActivityMonitor monitor = addMonitor(DiagnosticActivity.class.getName(), null, false);
            ui(() -> app.startActivity(new Intent(app, DiagnosticActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page = (DiagnosticActivity) waitForMonitorWithTimeout(monitor, 15000); removeMonitor(monitor);
            check(page != null, "诊断页面未打开");
            java.lang.reflect.Field model = DiagnosticActivity.class.getDeclaredField("repository"); model.setAccessible(true);
            DiagnosticRepository repository = (DiagnosticRepository) model.get(page);
            await(() -> !Boolean.TRUE.equals(repository.busy.getValue()), 40000, "诊断检查未返回"); waitForIdleSync();
            check(String.valueOf(repository.report.getValue()).contains("Node: v24"), "报告未包含真实 Node 运行结果");
            ui(() -> check(((TextView) page.findViewById(R.id.diagnostic_report)).getText().toString().contains("Python:"), "报告未显示在原生页面"));
            ui(() -> page.findViewById(R.id.diagnostic_repair).performClick());
            await(() -> !Boolean.TRUE.equals(repository.busy.getValue()), 70000, "工具修复没有返回"); waitForIdleSync();
            check(String.valueOf(repository.report.getValue()).startsWith("证书、Python、npm 与 pnpm 已修复"), "实际工具修复未成功：" + repository.report.getValue());
            check(!EnvironmentTaskGate.isBusy() && !RuntimeTasks.isBusy(), "诊断收尾未释放任务");
            ui(page::finish); page = null;

            com.deepseekharness.app.runtime.ProotBootstrap proot = HarnessController.get(app).proot();
            directory = new File(proot.getRootfsDir(), "root/.dsha-pty-audit-" + UUID.randomUUID());
            check(directory.mkdir(), "独立 PTY 目录未创建");
            String guest = "/root/" + directory.getName();
            try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("独立 PTY 验收")) {
                check(lease != null, "PTY 验收无法取得任务锁");
                ui(() -> pty = PtySession.start(proot, 80, 24, null));
                check(RuntimeTasks.isBusy(), "PTY 启动没有登记异步寿命");
                ui(() -> pty.write("unset HISTFILE; echo $$ > " + guest + "/shell.pid; sleep 30 & echo $! > " + guest + "/child.pid; wait\n"));
                File pid = new File(directory, "child.pid");
                await(pid::isFile, 7000, "PTY 输入未执行");
                child = WebProcSel.parsePid(Compat.readAll(pid));
                shellPid = WebProcSel.parsePid(Compat.readAll(new File(directory, "shell.pid")));
                check(child > 1 && shellPid > 1 && alive(child), "PTY 的真实 sleep 未启动");
                ui(pty::finish);
                await(() -> !pty.isRunning(), 7000, "PTY launcher 未退出");
                await(() -> !alive(child) && !alive(shellPid), 4000, "PTY 结束后仍遗留本次 shell/sleep");
                await(() -> !RuntimeTasks.isBusy(), 3000, "PTY 退出后运行任务未释放");
            }
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("failure", android.util.Log.getStackTraceString(error)); }
        finally {
            if (page != null) ui(page::finish);
            if (pty != null && pty.isRunning()) ui(pty::finish);
            // 两个 PID 只来自本次随机目录里的明确命令；失败时仅清理自己的测试子进程。
            for (int pid : new int[]{child, shellPid}) if (alive(pid)) try { Os.kill(pid, OsConstants.SIGKILL); } catch (ErrnoException ignored) { }
            if (directory != null) {
                new File(directory, "child.pid").delete(); new File(directory, "shell.pid").delete(); directory.delete();
            }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
