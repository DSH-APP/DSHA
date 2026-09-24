package com.deepseekharness.app.backup;

import android.app.job.*;
import android.content.*;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AutomaticBackupPolicy;
import java.io.*;
import java.util.*;

/** 自动备份只存个人数据，采用已有 v5 加密归档；本机密钥不参与系统迁移。 */
public final class AutomaticBackups {
    public static final int JOB=9137, NOW=9138;
    private static final java.util.concurrent.atomic.AtomicBoolean FACTORY_RESET=new java.util.concurrent.atomic.AtomicBoolean();
    private static final WorkerQuiescence PRODUCERS=new WorkerQuiescence();
    private AutomaticBackups() { }
    public static android.content.SharedPreferences prefs(Context context){return context.getSharedPreferences("dsha_automatic_backup",Context.MODE_PRIVATE);}
    public static boolean enabled(Context context){var saved=prefs(context);return !FACTORY_RESET.get()&&!saved.getBoolean("factoryResetPending",false)&&saved.getBoolean("enabled",true);}
    static boolean factoryResetSuspended(){return FACTORY_RESET.get();}
    static boolean factoryResetPending(Context context){return FACTORY_RESET.get()||prefs(context).getBoolean("factoryResetPending",false);}
    static WorkerQuiescence.Lease beginProducer(){return FACTORY_RESET.get()?null:PRODUCERS.tryBegin();}
    static void recordError(Context context,String error){
        synchronized(AutomaticBackups.class){
            if(!factoryResetPending(context))prefs(context).edit().putString("error",error==null?"":error).commit();
        }
    }
    public static void schedule(Context context){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        if(factoryResetPending(context)){scheduler.cancel(JOB);scheduler.cancel(NOW);return;}
        var settings=prefs(context);
        if(!settings.contains("anchor"))settings.edit().putLong("anchor",System.currentTimeMillis()).commit();
        if(!enabled(context)){scheduler.cancel(JOB);scheduler.cancel(NOW);return;}
        if(scheduler.getAllPendingJobs().stream().noneMatch(job->job.getId()==JOB))scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(context,AutomaticBackupService.class)).setPersisted(true).setPeriodic(15*60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE).build());
    }
    public static boolean due(Context context){var p=prefs(context);return enabled(context)&&AutomaticBackupPolicy.due(p.getString("mode","daily"),p.getInt("hours",24),p.getInt("minute",180),System.currentTimeMillis(),p.getLong("anchor",System.currentTimeMillis()),p.getLong("last",0),p.getLong("stopped",0),java.time.ZoneId.systemDefault());}
    public static void stopped(Context context){
        synchronized(AutomaticBackups.class){if(factoryResetPending(context))return;prefs(context).edit().putLong("stopped",System.currentTimeMillis()).commit();}
        schedule(context);if("stop".equals(prefs(context).getString("mode","daily")))request(context,false);
    }
    public static void request(Context context,boolean manual){
        if(!enabled(context))return;
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("manual",manual);
        scheduler.schedule(new JobInfo.Builder(NOW,new ComponentName(context,AutomaticBackupService.class)).setExtras(extras).setMinimumLatency(1500).build());
    }
    public static boolean idle(Context context)throws IOException{
        HarnessController c=HarnessController.get(context);
        return c.config().isWelcomed()&&!NativeBackupJobs.get(context).state().busy&&!c.isStarting()&&!c.isStopping()&&c.getWebAuthUrl().isEmpty()
                &&!com.deepseekharness.app.core.RuntimeTasks.isBusy()&&!com.deepseekharness.app.BackupManager.hasPendingMaintenance(context.getFilesDir())&&c.isWebStoppedForMaintenance();
    }
    static boolean idleForOwner(Context context)throws IOException{
        HarnessController c=HarnessController.get(context);
        return !c.isStarting()&&!c.isStopping()&&c.getWebAuthUrl().isEmpty()&&!com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks()&&c.isWebStoppedForMaintenance();
    }
    /**
     * 格式化在进入普通数据维护屏障前调用。先阻止 JobService/手动作业产生新任务，
     * 再取消并等待 NativeBackupJobs 真正退出，避免删除 host-backup-operations 时迟到写回。
     */
    public static void suspendForFactoryReset(Context context,BackupControl control)throws IOException{
        boolean first=FACTORY_RESET.compareAndSet(false,true);
        if(first&&!prefs(context).edit().putBoolean("factoryResetPending",true).commit()){
            FACTORY_RESET.set(false);throw new IOException("FACTORY_RESET_GATE_PERSISTENCE");
        }
        PRODUCERS.closeGate();
        boolean nativeClosed=false;
        try{
            PostUpgradeCleanupService.suspendForFactoryReset(control);
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);
            if(scheduler!=null){
                // 某些 ROM 在后台/通知策略变更后会拒绝 JobScheduler.cancel。
                // factoryResetPending 已先持久化，两个 Job 的 onStartJob 都会
                // 看到该门禁并立即返回；取消失败不能阻断私有数据格式化。
                try { scheduler.cancel(JOB); } catch (SecurityException ignored) { }
                try { scheduler.cancel(NOW); } catch (SecurityException ignored) { }
            }
            NativeBackupJobs jobs=NativeBackupJobs.get(context);
            jobs.beginFactoryResetQuiescence(control,30_000);nativeClosed=true;
            PRODUCERS.awaitIdle(control,15_000);
            // password() 也用类锁；这里形成屏障，确保已进入的密钥准备已经返回。
            synchronized(AutomaticBackups.class){ }
        }catch(IOException|RuntimeException failure){
            if(nativeClosed)NativeBackupJobs.get(context).abortFactoryResetQuiescence();
            PostUpgradeCleanupService.resumeAfterFactoryReset();
            if(first){prefs(context).edit().remove("factoryResetPending").commit();PRODUCERS.reopen();FACTORY_RESET.set(false);schedule(context);PostUpgradeCleanupService.schedule(context);}
            throw failure;
        }
    }
    public static void abortFactoryReset(Context context){
        NativeBackupJobs.get(context).abortFactoryResetQuiescence();
        PostUpgradeCleanupService.resumeAfterFactoryReset();
        prefs(context).edit().remove("factoryResetPending").commit();
        FACTORY_RESET.set(false);PRODUCERS.reopen();schedule(context);PostUpgradeCleanupService.schedule(context);
    }
    public static void completeFactoryReset(Context context){
        NativeBackupJobs.get(context).completeFactoryResetQuiescence();
        PostUpgradeCleanupService.resumeAfterFactoryReset();
        prefs(context).edit().remove("factoryResetPending").commit();
        FACTORY_RESET.set(false);PRODUCERS.reopen();
    }
    public static synchronized char[] password(Context context)throws IOException{
        if(factoryResetPending(context))throw new IOException("FACTORY_RESET_QUIESCING");
        var fs=new AndroidBackupFileSystem();File root=context.getNoBackupFilesDir().getCanonicalFile(),file=new File(root,"automatic-backup-key");
        var vault=new com.deepseekharness.app.data.KeyVault(context);
        if(fs.stat(file).type.equals("FILE"))return vault.decryptChecked(new String(fs.small(file,8192),java.nio.charset.StandardCharsets.UTF_8)).toCharArray();
        if(!fs.stat(file).type.equals("MISSING"))throw new IOException("AUTOMATIC_KEY_UNAVAILABLE");
        for(var copy:NativeBackupJobs.get(context).verifiedCopies().valid)if(Boolean.TRUE.equals(copy.metadata.get("automatic")))throw new IOException("AUTOMATIC_KEY_MISSING");
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String plain=android.util.Base64.encodeToString(random,android.util.Base64.NO_WRAP|android.util.Base64.URL_SAFE);
        String encrypted=vault.encrypt(plain);if(encrypted.isEmpty()||!plain.equals(vault.decryptChecked(encrypted)))throw new IOException("AUTOMATIC_KEY_UNAVAILABLE");
        fs.atomic(root,file.getName(),encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return vault.decryptChecked(new String(fs.small(file,8192),java.nio.charset.StandardCharsets.UTF_8)).toCharArray();
    }
    public static void completed(Context context,String id){synchronized(AutomaticBackups.class){if(!FACTORY_RESET.get())prefs(context).edit().putLong("last",System.currentTimeMillis()).putString("lastId",id).putString("error","").commit();}}
    /** 只轮换明确标记、已验证的自动产物，手动副本及未知记录不删除。 */
    public static void prune(Context context,BackupControl control)throws IOException{
        var fs=new AndroidBackupFileSystem();File parent=new File(context.getFilesDir().getCanonicalFile(),"host-backup-operations");
        List<VerifiedBackupCopy> automatic=new ArrayList<>();
        for(var copy:NativeBackupJobs.get(context).verifiedCopies().valid)if(Boolean.TRUE.equals(copy.metadata.get("automatic"))&&Set.of("COMPLETE","DATA_SAVED_PLUGIN_WARNINGS").contains(copy.result(true))){copy.verify(fs,control);automatic.add(copy);}
        for(int i=3;i<automatic.size();i++){
            var copy=automatic.get(i);File dir=fs.child(parent,copy.id);
            var record=BackupJson.read(fs.small(fs.child(dir,"operation.json"),16384),16384);
            if(Boolean.TRUE.equals(record.get("busy"))||!"FINISHED".equals(record.get("stage")))continue;
            Set<String> allowed=Set.of("portable.dshbak","verified.json","operation.json","source-checks");
            if(!allowed.containsAll(fs.list(dir)))continue;
            copy.verify(fs,control);fs.removeOwned(parent,copy.id);
        }
    }
}
