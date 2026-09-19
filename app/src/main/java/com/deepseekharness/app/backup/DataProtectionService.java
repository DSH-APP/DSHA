package com.deepseekharness.app.backup;

import android.app.*;
import android.content.*;
import android.os.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** 数据导出、恢复与受控运行时验证共享 dataSync 前台通知；中断后不自动重放任务。 */
public final class DataProtectionService extends Service {
    private static final String CHANNEL="dsha_data_protection";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){if(show())main.postDelayed(this,600);}};
    public static void start(Context context)throws IOException{
        Intent intent=new Intent(context,DataProtectionService.class);
        try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);}
        catch(RuntimeException error){throw new IOException("FOREGROUND_SERVICE_UNAVAILABLE",error);}
    }
    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,UiText.choose("数据保护","Data protection"),NotificationManager.IMPORTANCE_LOW));
        // 即使任务在 Service 创建之前已结束，也先履行 startForegroundService 的时间契约。
        publish(UiText.choose("正在确认任务状态…","Checking task state…"),null,null);main.post(refresh);
    }
    private boolean show(){
        var nativeJob=NativeBackupJobs.get(this).state();BackupTask tasks=BackupTask.get(this);var maintenance=tasks.snapshot();
        if(!nativeJob.busy&&!tasks.maintenanceBusy()){stopForeground(true);stopSelf();return false;}
        String id=nativeJob.busy?"native:"+nativeJob.id:"maintenance:"+maintenance.id;
        Intent cancel=new Intent(this,DataProtectionService.class).setAction("cancel").setData(android.net.Uri.parse("dsha://data-operation/"+id)).putExtra("operation",id);
        PendingIntent action=nativeJob.busy||tasks.cancellable()?PendingIntent.getService(this,0,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE):null;
        Intent view=nativeJob.busy?new Intent(this,com.deepseekharness.app.ui.NativeDataActivity.class):
                new Intent(this,com.deepseekharness.app.ui.ExtractActivity.class).putExtra("review_only",true).putExtra("data_task_id",maintenance.id);
        view.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,9031,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=nativeJob.busy?nativeJob.entries+UiText.choose(" 项 · "," items · ")+com.deepseekharness.app.util.Fmt.bytes(nativeJob.bytes):
                UiText.choose("正在保护数据并验证运行环境","Protecting data and verifying the runtime");
        publish(text,action,open);return true;
    }
    private void publish(String text,PendingIntent cancel,PendingIntent open){
        Notification.Builder notification=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        notification.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(UiText.choose("DSHA 数据保护","DSHA data protection"))
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true);
        if(cancel!=null)notification.addAction(new Notification.Action.Builder(null,UiText.choose("取消","Cancel"),cancel).build());
        // startForegroundService 的第一帧可能还没有拿到任务快照；即使这时用户立刻
        // 点击通知，也必须能回到 DSHA，而不是出现一个没有响应的通知。任务快照就绪后
        // show() 会用 NativeData/Extract 的更具体页面替换这个兜底入口。
        if(open==null){
            Intent app=new Intent(this,com.deepseekharness.app.ui.MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("open_launch",true);
            open=PendingIntent.getActivity(this,9032,app,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        }
        notification.setContentIntent(open);
        if(Build.VERSION.SDK_INT>=29)startForeground(9031,notification.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(9031,notification.build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"cancel".equals(intent.getAction())){
            String id=intent.getStringExtra("operation");var jobs=NativeBackupJobs.get(this);var tasks=BackupTask.get(this);
            if(("native:"+jobs.state().id).equals(id))jobs.cancel();
            if(("maintenance:"+tasks.snapshot().id).equals(id))tasks.cancel();
        }
        main.removeCallbacks(refresh);main.post(refresh);return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId,int fgsType){NativeBackupJobs.get(this).cancel();BackupTask.get(this).cancel();stopSelf();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){main.removeCallbacks(refresh);super.onDestroy();}
}

