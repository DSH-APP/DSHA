package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.BackupTaskState.Status;
import com.deepseekharness.app.util.MaintenanceTransaction;
import java.io.IOException;

/** 应用级任务；Activity/Fragment 只读快照，不保存输入副本或后台线程。 */
public final class BackupTask {
    private static BackupTask instance;
    private final Context app;
    private final HarnessController controller;
    private final SharedPreferences saved;
    private final BackupTaskState state = new BackupTaskState();
    private final Object decision = new Object();
    private Boolean accepted;

    public static synchronized BackupTask get(Context context) {
        if (instance == null) instance = new BackupTask(context.getApplicationContext());
        return instance;
    }
    private BackupTask(Context context) {
        this(context, HarnessController.get(context));
    }
    /** 包内 debug fixture 使用独立 Context/Controller，不改写应用单例。 */
    BackupTask(Context context, HarnessController harness) {
        app = context; controller = harness;
        saved = context.getSharedPreferences("dsha-data-task", Context.MODE_PRIVATE);
        try {
            state.restore(saved.getLong("id", 0), saved.getString("kind", ""),
                    Status.valueOf(saved.getString("status", "IDLE")), saved.getString("detail", ""));
        } catch (RuntimeException e) {
            state.restore(0, "任务记录", Status.INTERRUPTED, "上次任务记录无法读取，请检查数据状态。");
        }
    }
    public BackupTaskState.Snapshot snapshot() { return state.snapshot(); }
    public boolean pendingMaintenance() { return BackupManager.hasPendingMaintenance(controller); }
    public boolean busy() { return state.busy() || BackupManager.isEnvironmentTaskBusy(); }
    private synchronized void persist() throws IOException {
        BackupTaskState.Snapshot s = state.snapshot();
        if (!saved.edit().putLong("id", s.id).putString("kind", s.kind).putString("status", s.status.name())
                .putString("detail", s.detail).commit()) throw new IOException("无法保存任务状态，已停止操作");
    }
    private void progress(long id, String detail) {
        state.update(id, Status.RUNNING, detail);
        try { persist(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private interface Work { String run(long id) throws Exception; }
    private synchronized boolean start(String kind, boolean recovery, boolean stopWeb, Work work) {
        if (busy() || (!recovery && pendingMaintenance())) return false;
        com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(kind);
        if (lease == null) return false;
        // 在持有凭据后重查磁盘门禁，不能使用竞争窗口之前的 pending 快照。
        if (!recovery && pendingMaintenance()) { lease.close(); return false; }
        long id = state.start(kind);
        if (id < 0) { lease.close(); return false; }
        try { persist(); }
        catch (IOException e) { state.update(id, Status.FAILED, e.getMessage()); lease.close(); return false; }
        synchronized (decision) { accepted = null; }
        Thread worker = new Thread(() -> {
            try (lease) {
            try {
                String result = lease.run(() -> {
                    progress(id, stopWeb ? "正在停止 Web，等待运行队列完成…" : "正在准备数据快照…");
                    return stopWeb ? BackupManager.runDataTask(controller, () -> work.run(id)) : work.run(id);
                });
                state.update(id, Status.SUCCEEDED, result);
            } catch (Cancelled e) { state.update(id, Status.CANCELLED, "已取消恢复，当前数据未覆盖。"); }
            catch (Exception e) { state.update(id, Status.FAILED, BackupManager.safeError(e)); }
            finally {
                try { persist(); } catch (IOException e) { state.update(id, Status.FAILED, e.getMessage()); }
            }
            }
        }, "dsha-data-task");
        try { worker.start(); }
        catch (RuntimeException e) {
            state.update(id, Status.FAILED, BackupManager.safeError(e)); lease.close();
            try { persist(); } catch (IOException ignored) { }
            return false;
        }
        return true;
    }
    public boolean backup(int scope) {
        return start("创建备份", false, false, id -> {
            progress(id, "正在打包并校验备份…");
            String result = BackupManager.runSnapshotTask(controller, () -> BackupManager.backupToExternal(app, controller, scope));
            if (result == null) throw new IOException(BackupManager.lastError());
            return "备份成功，归档与导出文件已校验。\n" + result;
        });
    }
    public boolean prepareRestore(Uri uri) {
        return start("恢复备份", false, false, id -> {
            progress(id, "正在读取独立副本并检查归档…");
            try (BackupManager.PreparedRestore prepared = BackupManager.runSnapshotTask(controller,
                    () -> BackupManager.prepareRestore(app, controller, uri))) {
                // 预检结束即释放归档锁/CPU 工作锁；等待确认时仅持任务 Lease。
                state.update(id, Status.PREVIEW, "确认恢复将停止 Web，并中断正在执行的任务。\n\n" + prepared.summary); persist();
                synchronized (decision) {
                    while (accepted == null) decision.wait();
                    if (!accepted) throw new Cancelled();
                }
                progress(id, "已确认恢复，正在停止 Web 并等待退出…");
                return BackupManager.runDataTask(controller, () -> BackupManager.restoreWithinDataTask(controller, prepared));
            }
        });
    }
    public boolean decide(long id, boolean restore) {
        synchronized (decision) {
            if (!state.confirm(id) || accepted != null) return false;
            accepted = restore; decision.notifyAll(); return true;
        }
    }
    public boolean rebuild() {
        return start("重建环境", false, true, id -> EnvironmentMaintenance.rebuild(controller, detail -> progress(id, detail)));
    }
    public boolean recoverMaintenance() {
        return start("恢复中断维护", true, true, id -> EnvironmentMaintenance.recover(controller));
    }
    public boolean resetConfig() {
        return start("重置配置", false, true, id -> {
            progress(id, "正在备份重置前的数据…");
            MaintenanceTransaction safety = MaintenanceTransaction.create(app.getFilesDir());
            safety.verify(BackupManager.createMaintenanceBackup(controller, safety.archive()));
            progress(id, "安全备份校验通过，正在重置配置…");
            String result = controller.resetConfig();
            if (result.startsWith("重置失败")) throw new IOException(result + "\n安全备份：" + safety.archive());
            return result + "\n重置前安全备份：" + safety.archive();
        });
    }
    private static final class Cancelled extends Exception { }
}
