package com.deepseekharness.app.backup;

import android.content.Context;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.HarnessController;
import java.io.*;
import java.util.*;

/** 只在审计 APK 中轮换合成作业命名空间；历史与本轮文件均原样保留，不改生产保留上限。 */
final class AuditOperationsScope implements AutoCloseable {
    private final Context context;private final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();
    private final File files,owned;private final Map<String,String> hashes=new LinkedHashMap<>();
    AuditOperationsScope(Context context)throws Exception{
        DeviceAuditSupport.requireIsolated(context);this.context=context;files=context.getFilesDir().getCanonicalFile();
        owned=new File(files,"device-operation-scope-"+UUID.randomUUID());fs.directory(owned);
        BackupManager.runDataTask(HarnessController.get(context),()->{
            if(BackupManager.hasPendingMaintenance(files)||NativeBackupJobs.get(context).state().busy)throw new IOException("AUDIT_EXISTING_OPERATION_PENDING");
            for(String name:List.of("host-backup-operations","host-backup-catalogue")){
                File old=new File(files,name);if(fs.stat(old).type.equals("MISSING"))continue;
                if(!fs.stat(old).type.equals("DIRECTORY"))throw new IOException("AUDIT_ORIGINAL_TYPE");
                hashes.put(name,BackupTree.digest(fs,old,new BackupControl(null)));fs.move(old,new File(owned,"before-"+name));
            }
            fs.atomic(owned,"scope.json",BackupJson.write(Map.of("format",1,"originals",hashes),16384));return null;
        });
    }
    @Override public void close()throws Exception{
        BackupManager.runDataTask(HarnessController.get(context),()->{
            if(BackupManager.hasPendingMaintenance(files)||NativeBackupJobs.get(context).state().busy)throw new IOException("AUDIT_OPERATION_STILL_PENDING");
            for(String name:List.of("host-backup-operations","host-backup-catalogue")){
                File saved=new File(owned,"before-"+name),current=new File(files,name);
                if(hashes.containsKey(name)&&!hashes.get(name).equals(BackupTree.digest(fs,saved,new BackupControl(null))))throw new IOException("AUDIT_ORIGINAL_CHANGED");
                if(!fs.stat(current).type.equals("MISSING"))fs.move(current,new File(owned,"after-"+name));
                if(hashes.containsKey(name))fs.move(saved,current);
            }
            fs.atomic(owned,"restored.json",BackupJson.write(Map.of("originals",hashes,"testResultsRetained",true),16384));return null;
        });
    }
}
