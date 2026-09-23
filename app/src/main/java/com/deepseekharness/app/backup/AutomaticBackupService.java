package com.deepseekharness.app.backup;
import android.app.job.*;
import android.os.*;
import java.util.Arrays;

/** JobScheduler 持有任务寿命，不从后台强行启动前台服务或停止用户会话。 */
public final class AutomaticBackupService extends JobService {
    private final Handler main=new Handler(Looper.getMainLooper());
    private JobParameters active;
    private volatile boolean cancelled;
    private String owned="";
    private final Runnable poll=new Runnable(){public void run(){
        if(active==null)return;
        NativeBackupJobs jobs=NativeBackupJobs.get(AutomaticBackupService.this);var state=jobs.state();
        if(!AutomaticBackups.enabled(AutomaticBackupService.this)&&state.id.equals(owned))jobs.cancel();
        if(!state.id.equals(owned)||!state.busy){if(state.id.equals(owned)&&!state.error.isEmpty())AutomaticBackups.recordError(AutomaticBackupService.this,state.error);JobParameters done=active;active=null;jobFinished(done,false);return;}
        main.postDelayed(this,300);
    }};
    @Override public boolean onStartJob(JobParameters params){
        if(active!=null)return false;
        active=params;cancelled=false;owned="";
        WorkerQuiescence.Lease producer=AutomaticBackups.beginProducer();
        if(producer==null){active=null;return false;}
        Thread worker=new Thread(()->{try(WorkerQuiescence.Lease ignored=producer){
            char[] secret=null;
            try{
                boolean manual=params.getExtras().getBoolean("manual");
                if(cancelled||!AutomaticBackups.enabled(this)||(!manual&&!AutomaticBackups.due(this))){finishJob(params);return;}
                if(!AutomaticBackups.idle(this)){finishJob(params,manual);return;}
                secret=AutomaticBackups.password(this);
                if(cancelled||!AutomaticBackups.enabled(this)||!NativeBackupJobs.get(this).exportAutomatic(secret)){finishJob(params);return;}
                owned=NativeBackupJobs.get(this).state().id;if(cancelled){NativeBackupJobs.get(this).cancel();return;}main.post(poll);
            }catch(Exception error){AutomaticBackups.recordError(this,NativeBackupJobs.code(error));finishJob(params);}
            finally{if(secret!=null)Arrays.fill(secret,'\0');}
        }},"automatic-backup-schedule");
        try{worker.start();return true;}
        catch(RuntimeException|Error unavailable){producer.close();active=null;AutomaticBackups.recordError(this,"WORKER_UNAVAILABLE");return false;}
    }
    private void finishJob(JobParameters params){finishJob(params,false);}
    private void finishJob(JobParameters params,boolean retry){main.post(()->{if(active==params){active=null;jobFinished(params,retry);}});}
    @Override public boolean onStopJob(JobParameters params){cancelled=true;active=null;main.removeCallbacks(poll);var jobs=NativeBackupJobs.get(this);if(jobs.state().id.equals(owned)&&jobs.state().busy)jobs.cancel();return false;}
}
