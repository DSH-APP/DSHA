package com.deepseekharness.app.backup;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;

/** 独立验收进程在真实持久化边界自杀，绝不接收用户数据路径。 */
public class BackupCrashService extends Service {
    // 每个故障点独立进程；不修改厂商对短时间反复死亡进程的限制。
    public static final class Old extends BackupCrashService {}
    public static final class New extends BackupCrashService {}
    public static final class Rollback extends BackupCrashService {}
    public static final class SettingsWrite extends BackupCrashService {}
    public static final class SettingsCommitted extends BackupCrashService {}
    public IBinder onBind(Intent intent){return new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
        if(code!=1)return false;String path=data.readString(),phase=data.readString();boolean rollback=data.readInt()!=0;
        new Thread(()->{try{
            File root=new File(path).getCanonicalFile(),files=getFilesDir().getCanonicalFile();String relative=root.getPath().substring(files.getPath().length()+1);
            if(!getPackageName().equals("com.dsh.client.rc21audit")||!root.getPath().startsWith(files.getPath()+"/")||!relative.matches("device-audit-[a-f0-9-]{36}/kill-[a-z-]+"))throw new IOException("UNSAFE_FIXTURE");
            var tx=BackupDeviceAudit.transaction(root,at->{if(at.equals(phase)){BackupDeviceAudit.put(root,"reached-"+phase,phase);android.os.Process.killProcess(android.os.Process.myPid());throw new IOException("KILL_RETURNED");}});
            if(rollback)tx.recover();else tx.commit(new BackupControl(null));
        }catch(Throwable error){android.util.Log.e("DSHA-DeviceAudit","故障进程测试失败",error);}},"backup-crash-fixture").start();return true;
    }};}
}
