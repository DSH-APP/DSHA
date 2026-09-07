package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.runtime.InstallPipeline;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.InstallProcess;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** 应用进程内的安装任务所有者；不持有 Activity/View，切页与旋转不丢任务或结果。 */
public final class InstallRepository {
    private static InstallRepository instance;
    private final InstallTask task = new InstallTask();
    private boolean running;
    private final Executor worker;
    private final BooleanSupplier blocked;
    private final Installation installation;
    interface Installation { void run(InstallTask task, boolean repair, int selected) throws Exception; }
    private InstallRepository(Context context) {
        Context app = context.getApplicationContext();
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dsha-install"); thread.setDaemon(true); return thread;
        });
        blocked = () -> BackupManager.isRestoring() || BackupManager.hasPendingMaintenance(app.getFilesDir());
        installation = (task, repair, selected) -> {
            try (RuntimeTasks ignored = RuntimeTasks.begin()) { new InstallPipeline(app).run(task, repair, selected); }
        };
    }
    /** 独立测试直接使用可控队列和操作，验证实际仓库的锁与异常路径。 */
    InstallRepository(Executor worker, BooleanSupplier blocked, Installation installation) {
        this.worker = worker; this.blocked = blocked; this.installation = installation;
    }
    public static synchronized InstallRepository get(Context context) {
        if (instance == null) instance = new InstallRepository(context); return instance;
    }
    public InstallTask.Snapshot snapshot() { return task.snapshot(); }
    public boolean cancel() { return task.requestCancel(); }
    public synchronized boolean start(boolean repair, int selected) {
        if (running) return false;
        if (selected < 0 || selected > 6) throw new IllegalArgumentException("安装步骤必须为 0—6");
        EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(repair ? "安装与按需修复" : "安装检查");
        if (lease == null) return false;
        boolean submitted = false;
        try {
            // 拿到全局凭据后再读磁盘维护状态；任何拒绝都不能先发布 RUNNING。
            if (blocked.getAsBoolean() || !task.start(repair, selected)) return false;
            running = true;
            worker.execute(() -> run(lease, repair, selected));
            submitted = true;
            return true;
        } catch (Throwable error) {
            if (running) task.finish(InstallTask.Outcome.FAILED, "无法启动安装任务：" + error);
            running = false; return false;
        } finally {
            // 排队失败、维护未完成或校验拒绝时，凭据尚未移交 worker。
            if (!submitted) lease.close();
        }
    }
    private void run(EnvironmentTaskGate.Lease lease, boolean repair, int selected) {
        try (lease) {
            try {
                lease.run(() -> { installation.run(task, repair, selected); return null; });
            } catch (Throwable error) {
                InstallProcess.CleanupFailure cleanup = InstallProcess.cleanupFailure(error);
                if (cleanup != null) {
                    task.cleaningUp("本次进程尚未退出，正在继续回收；其他环境任务暂不可开始");
                    while (!cleanup.awaitExit(1000)) cleanup.retry();
                    task.finish(InstallTask.Outcome.FAILED, "本次进程已回收，检查未完成，请重新检查");
                } else if (InstallPipeline.isCancellation(error)) task.finish(InstallTask.Outcome.CANCELLED, "任务已取消，已完成的结果与修复保留");
                else { task.append("任务异常：" + error); task.finish(InstallTask.Outcome.FAILED, "任务失败：" + error); }
            } finally {
                if (task.snapshot().busy()) task.finish(InstallTask.Outcome.FAILED, "任务异常结束，请查看详细输出后重试");
            }
        } finally {
            // 全局凭据释放完才允许下一次安装；页面销毁不参与这一生命周期。
            synchronized (this) { running = false; }
        }
    }
}
