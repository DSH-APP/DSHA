package com.deepseekharness.app.backup;

import android.app.*;
import android.os.*;
import android.net.Uri;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Compat;
import java.io.*;
import java.util.*;

/** 历史隔离安装验收源码；本轮仅维护编译一致性，未生成或运行审计 APK。 */
public final class BackupWorkflowDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> results=new ArrayList<>();
    private final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();
    private NativeBackupJobs jobs;
    private File files,fixture,project,disabled,bash;
    private HarnessController controller;
    private NativeDataLocations.Selection selection;
    private interface Case { void run() throws Exception; }
    private void check(boolean value,String error) throws IOException { if(!value)throw new IOException(error); }
    private void test(String name,Case work) throws Exception {
        try { work.run();results.add(Map.of("name",name,"status","PASS")); }
        catch(Exception error) { results.add(Map.of("name",name,"status","FAIL","error",android.util.Log.getStackTraceString(error)));throw error; }
        Bundle status=new Bundle();status.putString("case",name);status.putString("status","PASS");sendStatus(1,status);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    private NativeBackupJobs.State await(boolean preview) throws Exception {
        long end=SystemClock.elapsedRealtime()+120000;
        while(jobs.state().busy && !(preview&&jobs.state().stage.equals("PREVIEW")) && SystemClock.elapsedRealtime()<end)Thread.sleep(80);
        var state=jobs.state();check(!state.busy||preview&&state.stage.equals("PREVIEW"),"TEST_OPERATION_STILL_RUNNING");return state;
    }
    private NativeBackupJobs.State export(Uri uri) throws Exception {
        check(jobs.export(selection,uri,AuditDocumentProvider.document(uri).name,false),"EXPORT_NOT_STARTED:"+jobs.state().error);
        return await(false);
    }
    private String latest() throws IOException { return BackupTree.digest(fs,new File(files,"host-backup-catalogue/latest.json"),new BackupControl(null)); }
    @Override public void onStart() {
        Bundle result=new Bundle();Activity screen=null;AuditOperationsScope scope=null;
        try {
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);files=context.getFilesDir().getCanonicalFile();
            controller=HarnessController.get(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            runOnMainSync(()->jobs=NativeBackupJobs.get(context));
            scope=new AuditOperationsScope(context);
            fixture=new File(files,"device-workflow-"+UUID.randomUUID());fs.directory(fixture);
            project=new File(files,"linux/ubuntu/root/"+fixture.getName());fs.directory(project);
            Compat.write(new File(project,".env"),"TEST_ONLY=keep-original\n");fs.directory(new File(project,"node_modules"));Compat.write(new File(project,"node_modules/local.js"),"owned dependency bytes\n");
            selection=new NativeDataLocations.Selection();selection.scope="projects";selection.guestProjects.add("/root/"+fixture.getName());
            String original=BackupTree.digest(fs,project,new BackupControl(null));
            bash=new File(files,"linux/ubuntu/usr/bin/bash");disabled=new File(bash.getParentFile(),".device-audit-bash-"+UUID.randomUUID());
            test("rescue_ui_without_bash",()->{
                BackupManager.runDataTask(controller,()->{fs.move(bash,disabled);return null;});
                check(!controller.proot().hasBash(),"BASH_STILL_AVAILABLE");
                Activity rescue=DeviceAuditSupport.open(this,com.deepseekharness.app.ui.NativeDataActivity.class);
                check(DeviceAuditSupport.foreground(this)==rescue,"RESCUE_NOT_FOREGROUND");runOnMainSync(rescue::finish);
            });
            Uri verified=AuditDocumentProvider.create(context,fixture,"pipe");
            test("native_job_export_without_guest_to_pipe",()->{var state=export(verified);check(state.result.equals("COMPLETE"),state.stage+":"+state.result+":"+state.error);});
            String successful=latest();
            test("password_free_transport_and_cancelled_preflight_keep_data_and_latest",()->{
                File payload=new File(fixture,"verified-payload");
                check(PortableBackupEnvelope.unwrapIfPresent(fs,AuditDocumentProvider.document(verified).file,payload,new BackupControl(null)),"EXPORT_NOT_TAR_ENVELOPE");
                try(InputStream input=fs.read(payload,fs.stat(payload))){check("UNENCRYPTED".equals(BackupArchive.read(input,null,new BackupControl(null)).get("sensitivePolicy")),"EXPORT_STILL_PASSWORD_PROTECTED");}
                check(jobs.prepareRestore(verified,null,Set.of("projects"),false),"RESTORE_NOT_STARTED");
                var preview=await(true);check(preview.stage.equals("PREVIEW"),preview.stage+":"+preview.error);
                check(jobs.decide(preview.id,false),"CANCEL_DECISION_REJECTED");var state=await(false);check(state.stage.equals("CANCELLED"),state.stage+":"+state.error);
                check(original.equals(BackupTree.digest(fs,project,new BackupControl(null)))&&successful.equals(latest()),"CANCEL_CHANGED_DATA");
            });
            test("project_preflight_and_native_commit_without_guest",()->{
                check(jobs.prepareRestore(verified,null,Set.of("projects"),false),"RESTORE_NOT_STARTED");
                var preview=await(true);check(preview.stage.equals("PREVIEW"),preview.stage+":"+preview.error);
                check(original.equals(BackupTree.digest(fs,project,new BackupControl(null))),"PREFLIGHT_CHANGED_SOURCE");
                check(jobs.decide(preview.id,true),"RESTORE_DECISION_REJECTED");var done=await(false);check(done.result.startsWith("DATA_RESTORED"),done.stage+":"+done.error);
                File restored=new File(files,"user-data-v5/restored-projects/"+preview.id+"/project-"+NativeDataLocations.hash("root/"+fixture.getName()));
                check(original.equals(BackupTree.digest(fs,restored,new BackupControl(null))),"RESTORE_BYTES_OR_MODES");
                check(successful.equals(latest()),"RESTORE_CHANGED_LATEST");
            });
            test("readback_revoked_retains_verified_copy",()->{
                var state=export(AuditDocumentProvider.create(context,fixture,"no-readback"));
                check(state.result.equals("WRITTEN_UNVERIFIED"),state.stage+":"+state.error+":"+state.result);
                check(fs.stat(new File(files,"host-backup-operations/"+state.id+"/portable.dshbak")).type.equals("FILE"),"PRIVATE_COPY_MISSING");
                check(successful.equals(latest()),"UNVERIFIED_REPLACED_LATEST");
            });
            test("write_permission_revoked_preserves_latest",()->{
                var state=export(AuditDocumentProvider.create(context,fixture,"deny-write"));check(state.stage.equals("FAILED")&&state.error.equals("SAF_PERMISSION_REVOKED"),state.stage+":"+state.error);
                check(fs.stat(new File(files,"host-backup-operations/"+state.id+"/portable.dshbak")).type.equals("FILE"),"FAILED_EXPORT_COPY_MISSING");
                check(successful.equals(latest()),"FAILED_EXPORT_REPLACED_LATEST");
            });
            test("cancel_during_target_write_preserves_latest",()->{
                var state=export(AuditDocumentProvider.create(context,fixture,"cancel"));check(state.stage.equals("CANCELLED"),state.stage+":"+state.error);
                check(successful.equals(latest())&&original.equals(BackupTree.digest(fs,project,new BackupControl(null))),"CANCEL_CHANGED_DATA");
            });
        } catch(Throwable error) { result.putString("failure",com.deepseekharness.app.util.SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            if(disabled!=null)try { if(fs.stat(disabled).type.equals("FILE"))BackupManager.runDataTask(controller,()->{fs.move(disabled,bash);return null;}); }
            catch(Exception error) { result.putString("cleanupFailure",android.util.Log.getStackTraceString(error)); }
            if(screen!=null) { Activity closed=screen;runOnMainSync(closed::finish); }
            if(scope!=null)try{scope.close();}catch(Exception error){result.putString("cleanupFailure",android.util.Log.getStackTraceString(error));}
            Map<String,Object> report=new LinkedHashMap<>();report.put("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS");report.put("tests",results);report.put("flavor",BuildConfig.FLAVOR);report.put("nonDebuggable",true);
            try { result.putString("report",new String(BackupJson.write(report,256*1024),java.nio.charset.StandardCharsets.UTF_8)); } catch(Exception error) { result.putString("failure",error.toString()); }
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
