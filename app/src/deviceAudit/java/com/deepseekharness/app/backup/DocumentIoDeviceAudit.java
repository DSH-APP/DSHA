package com.deepseekharness.app.backup;

import android.app.*;
import android.os.Bundle;
import android.net.Uri;
import com.deepseekharness.app.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 使用实际 Linux 管道阻塞读写及真实 Provider 取消信号，不用抛异常代替阻塞 I/O。 */
public final class DocumentIoDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();private File fixtures;private NativeBackupJobs jobs;
    private void check(boolean value,String error)throws IOException{if(!value)throw new IOException(error);}
    private void passed(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void blocked(String behavior)throws Exception{
        var context=getTargetContext();Uri uri=AuditDocumentProvider.create(context,fixtures,behavior);var document=AuditDocumentProvider.document(uri);
        BackupControl control=new BackupControl(null);AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        Thread worker=new Thread(()->{try{
            if(behavior.equals("blocked-write")){try(OutputStream out=DocumentStreams.output(context.getContentResolver(),uri,control)){byte[] data=new byte[65536];for(int i=0;i<32;i++)out.write(data);}}
            else if(behavior.equals("blocked-query")){try(var cursor=DocumentStreams.query(context.getContentResolver(),uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},control)){cursor.moveToFirst();}}
            else try(InputStream input=DocumentStreams.input(context.getContentResolver(),uri,control)){input.read();}
        }catch(Throwable error){failure.set(error);}finally{done.countDown();}},"document-io-fixture");worker.start();
        try{
            check(document.opened.await(5,TimeUnit.SECONDS),"PROVIDER_NOT_OPENED:"+behavior);Thread.sleep(200);check(done.getCount()==1,"IO_DID_NOT_BLOCK:"+behavior);
            long start=android.os.SystemClock.elapsedRealtime();runOnMainSync(control::cancel);long elapsed=android.os.SystemClock.elapsedRealtime()-start;
            check(elapsed<1000,"CANCEL_BLOCKED_UI:"+behavior);check(done.await(5,TimeUnit.SECONDS),"IO_NOT_CANCELLED:"+behavior);
            check(failure.get() instanceof InterruptedIOException,"IO_WRONG_CANCELLATION:"+behavior+":"+failure.get());passed(behavior+"_cancels_without_blocking_ui");
        }finally{control.cancel();if(document.peer!=null)document.peer.close();worker.join(10000);check(!worker.isAlive(),"TEST_IO_THREAD_RETAINED");}
    }
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;AuditOperationsScope scope=null;
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            fixtures=new File(context.getFilesDir().getCanonicalFile(),"device-workflow-"+UUID.randomUUID());check(fixtures.mkdir(),"TEST_DIRECTORY");
            for(String behavior:List.of("blocked-read","blocked-write","blocked-open","blocked-query"))blocked(behavior);
            Uri slice=AuditDocumentProvider.create(context,fixtures,"slice");try(OutputStream out=new FileOutputStream(AuditDocumentProvider.document(slice).file)){out.write("PREFIXPAYLOADSUFFIX".getBytes());}
            try(InputStream input=DocumentStreams.input(context.getContentResolver(),slice,new BackupControl(null))){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[16];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);check(bytes.toString("UTF-8").equals("PAYLOAD"),"ASSET_SLICE_NOT_RESPECTED");}
            passed("asset_descriptor_offset_and_length_are_respected");
            runOnMainSync(()->jobs=NativeBackupJobs.get(context));scope=new AuditOperationsScope(context);var fs=new AndroidBackupFileSystem();File latest=new File(context.getFilesDir().getCanonicalFile(),"host-backup-catalogue/latest.json");fs.directory(latest.getParentFile());fs.atomic(latest.getParentFile(),"latest.json",BackupJson.write(Map.of("auditPreviousSuccess","must remain unchanged"),4096));String before=BackupTree.digest(fs,latest,new BackupControl(null));
            Uri blocked=AuditDocumentProvider.create(context,fixtures,"blocked-read");var document=AuditDocumentProvider.document(blocked);
            try{
                check(jobs.prepareRestore(blocked,null,Set.of("projects"),false),"RESTORE_NOT_STARTED:"+jobs.state().error);check(document.opened.await(5,TimeUnit.SECONDS),"RESTORE_DID_NOT_OPEN");Thread.sleep(200);
                runOnMainSync(jobs::cancel);long until=android.os.SystemClock.elapsedRealtime()+5000;while(jobs.state().busy&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);
                check(!jobs.state().busy&&jobs.state().stage.equals("CANCELLED"),"RESTORE_CANCEL_NOT_FINISHED:"+jobs.state().stage+":"+jobs.state().error);
                check(before.equals(BackupTree.digest(fs,latest,new BackupControl(null))),"CANCEL_REPLACED_LATEST");passed("restore_input_cancel_keeps_previous_success");
            }finally{jobs.cancel();if(document.peer!=null)document.peer.close();}
        }catch(Throwable error){result.putString("failure",com.deepseekharness.app.util.SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(screen!=null){Activity closed=screen;runOnMainSync(closed::finish);}
            if(scope!=null)try{scope.close();}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            Map<String,Object> report=Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"nonDebuggable",true,"flavor",BuildConfig.FLAVOR);
            try{result.putString("report",new String(BackupJson.write(report,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
