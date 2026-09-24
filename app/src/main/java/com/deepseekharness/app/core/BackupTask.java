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
    private static final String FACTORY_RESET_KIND="DSHA_FACTORY_RESET_V1";
    private static BackupTask instance;
    private final Context app;
    private final HarnessController controller;
    private final SharedPreferences saved;
    private final BackupTaskState state = new BackupTaskState();
    private final Object decision = new Object();
    private Boolean accepted;
    private volatile com.deepseekharness.app.backup.BackupControl activeCancellation;
    private volatile long persistedCompletion;
    /*
     * 维护页只是展示状态，不需要每一帧重新遍历所有事务目录。真正开始任务时
     * 仍调用 pendingMaintenance() 做同步复核；这个缓存只供 UI 轮询使用。
     */
    private static final long UI_PENDING_CACHE_MILLIS = 2_000L;
    private final Object pendingCacheLock = new Object();
    private volatile long pendingCacheAt;
    private volatile boolean pendingCacheValue;
    private volatile boolean pendingCacheValid;
    private static final ThreadLocal<com.deepseekharness.app.backup.BackupControl> OWNER_CONTROL=new ThreadLocal<>();
    public static com.deepseekharness.app.backup.BackupControl currentControl(com.deepseekharness.app.backup.BackupControl.Progress progress){
        var current=OWNER_CONTROL.get();return current==null?new com.deepseekharness.app.backup.BackupControl(progress):current.withProgress(progress);
    }
    public void cancel(){if(!cancellable())return;var current=activeCancellation;if(current!=null)current.cancel();synchronized(decision){if(accepted==null)accepted=false;decision.notifyAll();}}
    public boolean cancellable(){var snapshot=state.snapshot();return !(snapshot.busy()&&isFactoryReset(snapshot));}

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
            state.restoreStage(saved.getString("last_stage", ""));
            if (!state.busy()) persistedCompletion = state.snapshot().id;
        } catch (RuntimeException e) {
            state.restore(0, com.deepseekharness.app.util.UiText.text("任务记录"), Status.INTERRUPTED, com.deepseekharness.app.util.UiText.text("上次任务记录无法读取，请检查数据状态。"));
        }
    }
    public BackupTaskState.Snapshot snapshot() { return state.snapshot(); }
    /** 执行入口使用的同步、不可缓存维护门禁。 */
    public boolean pendingMaintenance() {
        boolean value = BackupManager.hasPendingMaintenance(controller);
        synchronized (pendingCacheLock) {
            pendingCacheValue = value;
            pendingCacheAt = android.os.SystemClock.elapsedRealtime();
            pendingCacheValid = true;
        }
        return value;
    }
    /**
     * 仅供维护/安装页面的显示轮询。磁盘事务的真正门禁仍在执行入口重新核验，
     * 因而短暂的 UI 缓存不会放行任何写操作。
     */
    public boolean pendingMaintenanceForUi() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (pendingCacheValid && now - pendingCacheAt < UI_PENDING_CACHE_MILLIS) return pendingCacheValue;
        synchronized (pendingCacheLock) {
            now = android.os.SystemClock.elapsedRealtime();
            if (!pendingCacheValid || now - pendingCacheAt >= UI_PENDING_CACHE_MILLIS) {
                pendingCacheValue = BackupManager.hasPendingMaintenance(controller);
                pendingCacheAt = now;
                pendingCacheValid = true;
            }
            return pendingCacheValue;
        }
    }
    private void invalidatePendingCache() {
        synchronized (pendingCacheLock) { pendingCacheValid = false; }
    }
    public boolean busy() { return state.busy() || BackupManager.isEnvironmentTaskBusy(); }
    /** 顶部维护提示只反映数据任务；启动、终端和插件查询也使用执行锁，不能冒充维护。 */
    public boolean maintenanceBusy() { return state.busy() || BackupManager.isRestoring(); }
    private synchronized void persist() throws IOException {
        BackupTaskState.Snapshot s = state.snapshot();
        if (!saved.edit().putLong("id", s.id).putString("kind", s.kind).putString("status", s.status.name())
                .putString("detail", s.detail).putString("last_stage", s.lastStage).commit()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法保存任务状态，已停止操作"));
    }
    private void progress(long id, String detail) {
        state.update(id, Status.RUNNING, detail);
        try { persist(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private interface Work { String run(long id) throws Exception; }
    private synchronized boolean start(String kind, boolean recovery, boolean stopWeb, Work work) {
        invalidatePendingCache();
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
        var cancellation=new com.deepseekharness.app.backup.BackupControl(null);activeCancellation=cancellation;
        try{com.deepseekharness.app.backup.DataProtectionService.start(app);}
        catch(IOException error){state.update(id,Status.FAILED,error.getMessage());lease.close();activeCancellation=null;try{persist();}catch(IOException ignored){}return false;}
        Thread worker = new Thread(() -> {
            OWNER_CONTROL.set(cancellation);
            try (lease) {
            try {
                String result = lease.run(() -> {
                    progress(id, stopWeb ? com.deepseekharness.app.util.UiText.text("正在停止 Web，等待运行队列完成…") : com.deepseekharness.app.util.UiText.text("正在准备数据快照…"));
                    return stopWeb ? BackupManager.runDataTask(controller, () -> work.run(id)) : work.run(id);
                });
                state.update(id, Status.SUCCEEDED, result);
            } catch (Cancelled e) { state.update(id, Status.CANCELLED, com.deepseekharness.app.util.UiText.text("已取消恢复，当前数据未覆盖。")); }
            catch (Exception e) { state.update(id, e instanceof java.io.InterruptedIOException&&!pendingMaintenance()?Status.CANCELLED:Status.FAILED, BackupManager.safeError(e)); }
            finally {
                OWNER_CONTROL.remove();if(activeCancellation==cancellation)activeCancellation=null;
                invalidatePendingCache();
                try { persist(); persistedCompletion=id; } catch (IOException e) { state.update(id, Status.FAILED, e.getMessage()); }
            }
            }
        }, "dsha-data-task");
        try { worker.start(); }
        catch (RuntimeException e) {
            activeCancellation=null;
            state.update(id, Status.FAILED, BackupManager.safeError(e)); lease.close();
            try { persist(); } catch (IOException ignored) { }
            return false;
        }
        return true;
    }
    public boolean backup(int scope) {
        return start(com.deepseekharness.app.util.UiText.text("创建备份"), false, false, id -> {
            progress(id, com.deepseekharness.app.util.UiText.text("正在打包并校验备份…"));
            String result = BackupManager.runSnapshotTask(controller, () -> BackupManager.backupToExternal(app, controller, scope));
            if (result == null) throw new IOException(BackupManager.lastError());
            return com.deepseekharness.app.util.UiText.text("备份成功，归档与导出文件已校验。\n") + result;
        });
    }
    public boolean prepareRestore(Uri uri) {
        return start(com.deepseekharness.app.util.UiText.text("恢复备份"), false, false, id -> {
            progress(id, com.deepseekharness.app.util.UiText.text("正在读取独立副本并检查归档…"));
            try (BackupManager.PreparedRestore prepared = BackupManager.runSnapshotTask(controller,
                    () -> BackupManager.prepareRestore(app, controller, uri))) {
                // 预检结束即释放归档锁/CPU 工作锁；等待确认时仅持任务 Lease。
                state.update(id, Status.PREVIEW, com.deepseekharness.app.util.UiText.text("确认恢复将停止 Web，并中断正在执行的任务。\n\n") + prepared.summary); persist();
                synchronized (decision) {
                    while (accepted == null) decision.wait();
                    if (!accepted) throw new Cancelled();
                }
                progress(id, com.deepseekharness.app.util.UiText.text("已确认恢复，正在停止 Web 并等待退出…"));
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
        return start(com.deepseekharness.app.util.UiText.text("重建环境"), false, true, id -> EnvironmentMaintenance.rebuild(controller, detail -> progress(id, detail)));
    }
    public boolean updateEnvironment() {
        return start(com.deepseekharness.app.util.UiText.text("更新运行环境"), false, true, id -> EnvironmentMaintenance.update(controller, detail -> progress(id, detail)));
    }
    public boolean recoverMaintenance() {
        return start(com.deepseekharness.app.util.UiText.text("恢复中断维护"), true, true, id -> EnvironmentMaintenance.recover(controller));
    }
    public boolean factoryReset() {
        // 格式化是用户主动选择的中断恢复出口，允许存在未完成维护记录。应用内
        // 清理保留当前界面进程，完成后由 ExtractActivity 直接切到欢迎页。
        return start(FACTORY_RESET_KIND, true, false, id -> {
            var control=currentControl((stage,entries,bytes)->progress(id,formatProgress(stage,entries,bytes)));
            com.deepseekharness.app.backup.AutomaticBackups.suspendForFactoryReset(app,control);
            java.util.concurrent.atomic.AtomicBoolean destructive=new java.util.concurrent.atomic.AtomicBoolean();
            try{
                com.deepseekharness.app.backup.FactoryReset.Result result=BackupManager.runDataTask(controller,()->{
                    destructive.set(true);
                    return com.deepseekharness.app.backup.FactoryReset.eraseApplicationData(app,control);
                });
                return result.hasWarnings()?com.deepseekharness.app.util.UiText.choose(
                        "格式化完成；部分旧公共、外置目录或系统可再生缓存暂时无法移除，详情已保存在本机提示记录。正在打开欢迎页…",
                        "Formatting is complete. Some legacy public, external, or system-regenerable cache items could not be removed; details were retained locally. Opening the welcome screen…"):
                        com.deepseekharness.app.util.UiText.choose(
                                "格式化完成，正在打开欢迎页…",
                                "Formatting is complete. Opening the welcome screen…");
            }catch(Exception failure){
                // 尚未开始删除时恢复正常调度；一旦进入删除阶段则继续封住写者，
                // 允许用户在同一页面重试并避免后台任务写入部分清理的目录。
                if(!destructive.get())com.deepseekharness.app.backup.AutomaticBackups.abortFactoryReset(app);
                throw failure;
            }
        });
    }
    private static String formatProgress(String stage,long entries,long bytes){
        String count=entries<=0?"":com.deepseekharness.app.util.UiText.choose(
                "\n已安全清理 "+entries+" 项 · ","\nSafely removed "+entries+" items · ")
                +com.deepseekharness.app.util.Fmt.bytes(bytes);
        if(com.deepseekharness.app.backup.FactoryReset.STAGE_PUBLIC.equals(stage))return com.deepseekharness.app.util.UiText.choose(
                "正在尝试清理旧版 Documents/dshdata；手动导出的备份与其他个人目录会保留…",
                "Attempting to remove legacy Documents/dshdata; exported backups and other personal folders stay untouched…")+count;
        if(com.deepseekharness.app.backup.FactoryReset.STAGE_RUNTIME.equals(stage))return com.deepseekharness.app.util.UiText.choose(
                "正在清除运行环境、会话和插件…","Removing the runtime, conversations, and plugins…")+count;
        if(com.deepseekharness.app.backup.FactoryReset.STAGE_PRIVATE.equals(stage))return com.deepseekharness.app.util.UiText.choose(
                "正在清除本机备份和应用私有文件…","Removing local backups and private app files…")+count;
        if(com.deepseekharness.app.backup.FactoryReset.STAGE_CACHE.equals(stage))return com.deepseekharness.app.util.UiText.choose(
                "正在清理缓存和临时文件…","Clearing caches and temporary files…")+count;
        if(com.deepseekharness.app.backup.FactoryReset.STAGE_SETTINGS.equals(stage))return com.deepseekharness.app.util.UiText.choose(
                "正在重置设置、设备授权和 API Key…","Resetting settings, device grants, and the API key…")+count;
        return com.deepseekharness.app.util.UiText.choose("格式化完成，正在返回欢迎页…","Formatting is complete. Returning to welcome…")+count;
    }
    private static boolean isFactoryReset(BackupTaskState.Snapshot snapshot){
        return FACTORY_RESET_KIND.equals(snapshot.kind)||"格式化 DSHA".equals(snapshot.kind)||"Format DSHA".equals(snapshot.kind);
    }
    public boolean isFactoryReset(long id){
        BackupTaskState.Snapshot snapshot=state.snapshot();return id!=0&&snapshot.id==id&&isFactoryReset(snapshot);
    }
    public boolean isCompletedFactoryReset(long id){
        BackupTaskState.Snapshot snapshot=state.snapshot();
        return id!=0&&id==persistedCompletion&&snapshot.id==id&&snapshot.status==Status.SUCCEEDED&&isFactoryReset(snapshot);
    }
    /** 只在格式化成功并已持久化后消费结果；随后新安装看到的是干净任务状态。 */
    public synchronized boolean completeFactoryReset(long id){
        if(!isCompletedFactoryReset(id))return false;
        boolean cleared=saved.edit().clear().commit();
        if(!cleared)return false;
        try{com.deepseekharness.app.backup.AutomaticBackups.completeFactoryReset(app);}
        catch(RuntimeException incomplete){return false;}
        controller.startupDiagnostics().completeFactoryResetDrain();
        state.reset();persistedCompletion=0;
        com.deepseekharness.app.backup.AutomaticBackups.schedule(app);
        com.deepseekharness.app.backup.PostUpgradeCleanupService.schedule(app);
        return true;
    }
    public boolean rollbackRuntime(String operation){
        return start(com.deepseekharness.app.util.UiText.choose("回退兼容运行时", "Roll back compatible runtime"),false,true,
                id->EnvironmentMaintenance.rollbackRuntime(controller,operation,detail->progress(id,detail)));
    }
    public boolean selectDataHome(com.deepseekharness.app.backup.UserDataLayout.Home home){
        return start(com.deepseekharness.app.util.UiText.choose("选择数据目录", "Select data directory"),true,true,id->{
            // 此动作只选择已有目录，不复制、清空或修复其中的数据。
            var fs=new com.deepseekharness.app.backup.AndroidBackupFileSystem();java.io.File files=app.getFilesDir().getCanonicalFile();
            if(!com.deepseekharness.app.backup.HostPendingTransactions.pending(fs,files).isEmpty()
                    ||!com.deepseekharness.app.backup.ManagedRuntimeTransaction.pending(fs,files).isEmpty()
                    ||com.deepseekharness.app.util.MaintenanceTransaction.pending(files)!=null||com.deepseekharness.app.util.RuntimeUpdateTransaction.pending(files)!=null
                    ||com.deepseekharness.app.backup.EnvironmentRebuildTransaction.pending(fs,files)!=null
                    ||!new com.deepseekharness.app.backup.ConfigurationSnapshots(fs,files,new java.io.File(files,com.deepseekharness.app.backup.UserDataLayout.LEGACY)).pendingNative().isEmpty())throw new IOException("RECOVERY_PENDING");
            var layout=new com.deepseekharness.app.backup.UserDataLayout(fs,files);
            layout.choose(home);return com.deepseekharness.app.util.UiText.choose("已选择数据目录，另一份原件仍保留。", "Data directory selected; the other original remains retained.");
        });
    }
    public boolean resetConfig() {
        return start(com.deepseekharness.app.util.UiText.text("重置配置"), false, true, id -> {
            progress(id, com.deepseekharness.app.util.UiText.choose("正在由 Android 校验并保留重置前配置…", "Android is verifying and retaining the original configuration…"));
            return controller.resetConfig();
        });
    }
    public boolean repairStartup(org.json.JSONObject request) {
        return start(com.deepseekharness.app.util.UiText.choose("修复启动配置", "Repair startup configuration"), "recover".equals(request.optString("command")), true,
                id -> StartupRepairs.change(app,controller,request));
    }
    public boolean removeStartupPlugin(String name) {
        return start(com.deepseekharness.app.util.UiText.choose("卸载故障插件", "Remove faulty plugin"), false, true,
                id -> StartupRepairs.deletePlugin(controller,name));
    }
    private static final class Cancelled extends Exception { }
}
