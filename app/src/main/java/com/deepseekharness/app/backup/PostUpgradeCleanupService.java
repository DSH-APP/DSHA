package com.deepseekharness.app.backup;
import android.app.job.*;
import android.content.*;
import com.deepseekharness.app.BuildConfig;

/** 覆盖更新后的单次清理；运行中延后，绝不为清理终止用户任务。 */
public final class PostUpgradeCleanupService extends JobService {
    private static final int JOB=9139, POLICY=3;
    private static final WorkerQuiescence WORKERS=new WorkerQuiescence();
    private static final java.util.concurrent.atomic.AtomicBoolean FACTORY_RESET=new java.util.concurrent.atomic.AtomicBoolean();
    public static void schedule(Context context){
        if(AutomaticBackups.factoryResetPending(context)){
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);return;
        }
        var prefs=context.getSharedPreferences("dsha_storage_cleanup",MODE_PRIVATE);
        if(prefs.getInt("completedBuild",0)>=BuildConfig.VERSION_CODE&&prefs.getInt("policy",0)>=POLICY)return;
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        if(scheduler.getAllPendingJobs().stream().noneMatch(job->job.getId()==JOB))scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(context,PostUpgradeCleanupService.class))
                .setMinimumLatency(30_000).setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build());
    }
    static void suspendForFactoryReset(BackupControl control)throws java.io.IOException{
        FACTORY_RESET.set(true);WORKERS.closeGate();
        try{WORKERS.awaitIdle(control,15_000);}
        catch(java.io.IOException failure){FACTORY_RESET.set(false);WORKERS.reopen();throw failure;}
    }
    static void resumeAfterFactoryReset(){FACTORY_RESET.set(false);WORKERS.reopen();}
    private void save(boolean success,String value){
        synchronized(PostUpgradeCleanupService.class){
            if(FACTORY_RESET.get()||AutomaticBackups.factoryResetPending(this))return;
            var edit=getSharedPreferences("dsha_storage_cleanup",MODE_PRIVATE).edit();
            if(success)edit.putInt("completedBuild",BuildConfig.VERSION_CODE).putInt("policy",POLICY).putLong("freedBytes",Long.parseLong(value)).putString("error","");
            else edit.putString("error",value);
            edit.commit();
        }
    }
    @Override public boolean onStartJob(JobParameters params){
        if(FACTORY_RESET.get()||AutomaticBackups.factoryResetPending(this))return false;
        WorkerQuiescence.Lease lease=WORKERS.tryBegin();if(lease==null)return false;
        Thread worker=new Thread(()->{try(WorkerQuiescence.Lease ignored=lease){boolean retry=false;
            try{
                var controller=com.deepseekharness.app.core.HarnessController.get(this);
                if(!AutomaticBackups.idle(this)||!controller.isEnvironmentReady()){retry=true;return;}
                var descriptor=controller.proot().installedRuntimeDescriptor();
                if(!RuntimeDescriptor.healthy(controller.proot().runtimeHealth(),descriptor.id())){retry=true;return;}
                long bytes=StorageMaintenance.clean(this);
                save(true,String.valueOf(bytes));
            }catch(Exception error){save(false,NativeBackupJobs.code(error));}
            finally{jobFinished(params,retry);}
        }},"post-upgrade-cleanup");
        try{worker.start();return true;}catch(RuntimeException|Error unavailable){lease.close();save(false,"WORKER_UNAVAILABLE");return false;}
    }
    @Override public boolean onStopJob(JobParameters params){return !FACTORY_RESET.get()&&!AutomaticBackups.factoryResetPending(this);}
}
