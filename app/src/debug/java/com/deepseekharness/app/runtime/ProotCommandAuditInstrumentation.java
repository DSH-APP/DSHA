package com.deepseekharness.app.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.ProcessTermination;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 通用同步执行的真机回归；只运行自己创建的 echo/sleep，不启动 Web、不改用户偏好。 */
public final class ProotCommandAuditInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); AuditProot proot = new AuditProot(getTargetContext());
        try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("通用命令验收")) {
            check(lease != null && !RuntimeTasks.isBusy(), "有其他环境工作，请稍后测试");
            check(proot.isEnvironmentReady(), "环境尚未准备好");
            lease.run(() -> {
                long started = SystemClock.elapsedRealtime();
                String timeout = proot.execAndReadWithProot("printf AUDIT_BEGIN; exec sleep 30", 600);
                check(timeout.contains("超时"), "静默命令没有明确超时结果");
                check(SystemClock.elapsedRealtime() - started < 7000, "超时没有及时回收本次 launcher");
                check(allExited(proot), "超时后本次进程仍活跃");
                check(!RuntimeTasks.isBusy(), "超时回收后没有释放运行任务");
                check("中文🙂".equals(proot.execAndReadWithProot("printf '中文🙂'", 5000)), "有界读取丢失末尾 UTF-8 输出");
                boolean failed = false;
                try { proot.execChecked("printf expected_failure; exit 7"); }
                catch (IOException expected) { failed = expected.getMessage().contains("退出码 7"); }
                check(failed, "同步执行未返回真实退出码");
                check(!RuntimeTasks.isBusy(), "非零退出未释放任务");
                return null;
            });
            // 中断发生在真实子进程已经启动后；测试线程拥有自己的同步任务。
            proot.started = new CountDownLatch(1);
            String[] answer = {null}; boolean[] interrupted = {false};
            Thread worker = new Thread(() -> {
                answer[0] = proot.execAndReadWithProot("exec sleep 30", 30_000);
                interrupted[0] = Thread.currentThread().isInterrupted();
            }, "audit-command-interrupt");
            worker.start();
            check(proot.started.await(5, TimeUnit.SECONDS), "中断检查的进程未启动");
            worker.interrupt(); worker.join(7000);
            check(!worker.isAlive() && answer[0] != null && answer[0].contains("中断") && interrupted[0], "等待中断未有界返回并保留中断状态");
            check(allExited(proot) && !RuntimeTasks.isBusy(), "中断后本次进程或运行任务残留");
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("failure", android.util.Log.getStackTraceString(error)); }
        finally {
            synchronized (proot.processes) {
                for (Process process : proot.processes) if (!ProcessTermination.exited(process)) {
                    try { Compat.destroy(process); } catch (RuntimeException ignored) { }
                }
            }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
    private boolean allExited(AuditProot proot) {
        synchronized (proot.processes) {
            for (Process process : proot.processes) if (!ProcessTermination.exited(process)) return false;
            return true;
        }
    }
    private static final class AuditProot extends ProotBootstrap {
        final List<Process> processes = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch started;
        AuditProot(Context app) { super(app); }
        private Process record(Process process) {
            processes.add(process); CountDownLatch signal = started; if (signal != null) signal.countDown(); return process;
        }
        @Override public Process execRootfsForInstall(String command) throws IOException { return record(super.execRootfsForInstall(command)); }
        @Override public Process execRootfs(String command) throws IOException { return record(super.execRootfs(command)); }
    }
}
