package com.deepseekharness.app.backup;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.HarnessController;
import java.io.*;
import java.util.*;

/** 使用前面工作流创建的私有副本；整体移开测试 Linux 后复验，不读取原用户安装。 */
public final class ReexportDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();private NativeBackupJobs jobs;
    private void check(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private void passed(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    private NativeBackupJobs.State waitFor()throws Exception{long end=android.os.SystemClock.elapsedRealtime()+30000;while(jobs.state().busy&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(80);check(!jobs.state().busy,"REEXPORT_TEST_STILL_RUNNING");return jobs.state();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;File linux=null,parked=null;HarnessController controller=null;
        var fs=new AndroidBackupFileSystem();
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            runOnMainSync(()->jobs=NativeBackupJobs.get(context));controller=HarnessController.get(context);File files=context.getFilesDir().getCanonicalFile(),operations=new File(files,"host-backup-operations");
            var copies=jobs.verifiedCopies();VerifiedBackupCopy source=null;
            for(var item:copies.valid)if(item.scope.equals("projects")&&item.result(true).equals("COMPLETE")){
                try{item.verify(fs,new BackupControl(null));source=item;break;}catch(IOException retained){ /* 上轮故障夹具保留；选择仍通过摘要的原件。 */ }
            }
            check(source!=null,"RUN_WORKFLOW_FIRST");source.verify(fs,new BackupControl(null));
            File targets=new File(files,"device-workflow-"+UUID.randomUUID());fs.directory(targets);
            linux=new File(files,"linux");parked=new File(files,"device-parked-linux-"+UUID.randomUUID());File origin=linux,retained=parked;
            BackupManager.runDataTask(controller,()->{fs.move(origin,retained);return null;});check(!linux.exists(),"TEST_LINUX_STILL_PRESENT");
            var destination=AuditDocumentProvider.create(context,targets,"pipe");check(jobs.reexport(source.id,destination,AuditDocumentProvider.document(destination).name),"REEXPORT_REJECTED:"+jobs.state().error);
            var state=waitFor();check(state.result.equals("COMPLETE"),state.stage+":"+state.error+":"+state.result);
            try(InputStream input=new FileInputStream(AuditDocumentProvider.document(destination).file)){check(source.sha256.equals(BackupArchive.digest(input,new BackupControl(null))),"REEXPORTED_BYTES_CHANGED");}
            check(new com.deepseekharness.app.core.ConfigStore(context).getLastBackupUri().equals(destination.toString()),"REEXPORT_CATALOG_NOT_UPDATED");
            passed("verified_copy_reexports_without_linux_or_original_password");
            String latest=BackupTree.digest(fs,new File(files,"host-backup-catalogue/latest.json"),new BackupControl(null));
            File bad=new File(operations,UUID.randomUUID().toString());fs.directory(bad);File badFile=new File(bad,"portable.dshbak");
            try(InputStream input=fs.read(source.artifact,fs.stat(source.artifact));OutputStream out=fs.create(badFile)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);}
            try(RandomAccessFile file=new RandomAccessFile(badFile,"rw")){file.seek(file.length()-1);int old=file.read();file.seek(file.length()-1);file.write(old^1);file.getFD().sync();}
            fs.atomic(bad,"verified.json",BackupJson.write(source.metadata,BackupLimits.MANIFEST));
            var untouched=AuditDocumentProvider.create(context,targets,"pipe");check(jobs.reexport(bad.getName(),untouched,AuditDocumentProvider.document(untouched).name),"CHANGED_COPY_NOT_CHECKED");
            state=waitFor();check(state.error.equals("VERIFIED_COPY_CHANGED"),state.stage+":"+state.error);
            check(!AuditDocumentProvider.document(untouched).file.exists(),"BAD_COPY_WROTE_TARGET");
            check(latest.equals(BackupTree.digest(fs,new File(files,"host-backup-catalogue/latest.json"),new BackupControl(null))),"BAD_COPY_REPLACED_LATEST");
            passed("corrupted_copy_cannot_write_target_or_replace_latest");
            File unreadable=new File(operations,UUID.randomUUID().toString());fs.directory(unreadable);fs.atomic(unreadable,"verified.json","{}".getBytes());
            var records=jobs.verifiedCopies();check(records.unreadable.containsKey(unreadable.getName()),"CORRUPT_RECORD_NOT_REPORTED");
            boolean available=false;for(var item:records.valid)if(item.id.equals(source.id))available=true;
            check(available,"CORRUPT_RECORD_BLOCKED_OTHER_COPIES");passed("corrupt_record_does_not_hide_other_private_copies");
        }catch(Throwable error){result.putString("failure",com.deepseekharness.app.util.SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(parked!=null&&controller!=null)try{File source=parked,target=linux;BackupManager.runDataTask(controller,()->{if(fs.stat(source).type.equals("DIRECTORY"))fs.move(source,target);return null;});}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            if(screen!=null){Activity closed=screen;runOnMainSync(closed::finish);}
            Map<String,Object> report=Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"nonDebuggable",true,"flavor",BuildConfig.FLAVOR);
            try{result.putString("report",new String(BackupJson.write(report,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
