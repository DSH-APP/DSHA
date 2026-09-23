package com.deepseekharness.app.backup;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 合成旧树在无 Bash 情况下导出/恢复，并验证旧树只读及私有副本密码错误保护。 */
public final class RetainedWorkflowDeviceAudit extends Instrumentation {
    private final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();private final List<Map<String,Object>> tests=new ArrayList<>();
    private void check(boolean value,String code)throws IOException{if(!value)throw new IOException(code);}
    private void put(File file,String value)throws IOException{File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("TEST_PARENT");try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private void waitFor(NativeBackupJobs jobs,String stage)throws Exception{long until=android.os.SystemClock.elapsedRealtime()+120000;while(!jobs.state().stage.equals(stage)){
        if(!jobs.state().busy||android.os.SystemClock.elapsedRealtime()>until)throw new IOException("WAIT_"+stage+":"+jobs.state().stage+":"+jobs.state().error);Thread.sleep(100);}}
    private void pass(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    private void quarantineCopy(android.content.Context context,File files)throws Exception{
        String id=UUID.randomUUID().toString(),a="package-"+"a".repeat(20),b="package-"+"b".repeat(20);
        File source=new File(files,"plugin-imports/"+id);
        put(new File(source,"packages/"+a+"/package.json"),"{\"name\":\"retained-audit-plugin\",\"version\":\"1.0.0\",\"dependencies\":{\"retained-helper\":\"1.0.0\"},\"dsh\":{\"bundle\":{\"patch\":\"cordis.patch.yml\"}},\"scripts\":{\"postinstall\":\"node never-run.js\"}}");
        put(new File(source,"packages/"+a+"/never-run.js"),"throw new Error('MUST_NOT_EXECUTE');");
        put(new File(source,"packages/"+a+"/cordis.patch.yml"),"[]\n");
        put(new File(source,"packages/"+b+"/package.json"),"{\"name\":\"retained-helper\",\"version\":\"1.0.0\"}");
        put(new File(source,"profiles/web/package.json"),"{\"dependencies\":{\"retained-audit-plugin\":\"link:/old/source\"}}");
        Map<String,Object> graph=Map.of("version",2,"nodes",Map.of(a,Map.of("name","retained-audit-plugin","managed",false,"sourceRoot",a,"edges",Map.of("retained-helper",b)),b,Map.of("name","retained-helper","managed",false,"sourceRoot",b,"edges",Map.of())),"profiles",Map.of("web",Map.of("retained-audit-plugin",a)),"shared",Map.of(),"complete",true);
        new PluginRestoreGraph(fs,source,(name,proof)->null).rebuild(graph,new BackupControl(null));String before=BackupTree.digest(fs,source,new BackupControl(null));
        String candidate=QuarantinedPluginReview.prepare(context,id,a,new BackupControl(null));File copied=new File(files,"linux/ubuntu/root/dsha-native-plugin-reviews/"+candidate);
        check(before.equals(BackupTree.digest(fs,source,new BackupControl(null))),"QUARANTINE_ORIGINAL_CHANGED");
        File dependency=new File(copied,"packages/"+a+"/node_modules/retained-helper");
        check(fs.stat(dependency).type.equals("LINK")&&dependency.getCanonicalFile().equals(new File(copied,"packages/"+b).getCanonicalFile()),"QUARANTINE_DEPENDENCY_LINK");
        check(new File(copied,"original-declarations/profiles/web/package.json").isFile(),"QUARANTINE_DECLARATION_LOST");
        Map<String,Object> receipt=BackupJson.read(fs.small(new File(copied,"source-receipt.json"),4096),4096);check(Boolean.FALSE.equals(receipt.get("executed")),"QUARANTINE_EXECUTION_STATE");
        pass("quarantine_native_copy_rebuilds_dependencies_preserves_original_and_runs_no_code");
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;File bash=null,parked=null;HarnessController controller=null;AuditOperationsScope scope=null;
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,com.deepseekharness.app.ui.NativeDataActivity.class);controller=HarnessController.get(context);scope=new AuditOperationsScope(context);
            File files=context.getFilesDir().getCanonicalFile();String operation=UUID.randomUUID().toString(),name="retained-fixture-"+UUID.randomUUID();
            File old=new File(files,EnvironmentRebuildTransaction.HOME+"/"+operation),oldHome=new File(old,"previous-linux/ubuntu/root/.dsh");
            File source=new File(oldHome,"sessions/"+name);put(source,"original retained conversation");put(new File(old,"committed"),operation+"\ncommitted\n");
            File current=new File(new UserDataLayout(fs,files).current(),"sessions/"+name);put(current,"new current conversation");
            String originalHash=BackupTree.digest(fs,old,new BackupControl(null));
            File directory=new File(files,"device-workflow-"+UUID.randomUUID());check(directory.mkdir(),"TEST_DIRECTORY");var target=AuditDocumentProvider.create(context,directory,"normal");
            bash=new File(controller.proot().getRootfsDir().getCanonicalFile(),"usr/bin/bash");parked=new File(directory,"owned-bash");File from=bash,to=parked;
            BackupManager.runDataTask(controller,()->{fs.move(from,to);return null;});check(!controller.proot().hasBash(),"BASH_FIXTURE_ACTIVE");
            var jobs=NativeBackupJobs.get(context);var selected=new NativeDataLocations.Selection();selected.scope="application";selected.retainedKey="ENVIRONMENT:"+operation+":previous-linux-data";
            char[] password="owned-retained-password".toCharArray();String filename=AuditDocumentProvider.document(target).name;
            check(jobs.export(selected,password,target,filename,true),"RETAINED_EXPORT_REFUSED:"+jobs.state().error);waitFor(jobs,"FINISHED");String copy=jobs.state().id;
            check(jobs.state().result.equals("BEST_EFFORT_RESCUE"),"RESCUE_UPGRADED_TO_COMPLETE");check(originalHash.equals(BackupTree.digest(fs,old,new BackupControl(null))),"OLD_TREE_WRITTEN");pass("old_tree_exports_without_bash_and_remains_read_only");
            check(jobs.prepareRestoreCopy(copy,"wrong password".toCharArray(),Set.of("sessions"),false,"PRIVATE"),"COPY_PREFLIGHT_REFUSED");
            long until=android.os.SystemClock.elapsedRealtime()+60000;while(jobs.state().busy&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            check(!jobs.state().busy&&jobs.state().stage.equals("FAILED_RETAINED")&&jobs.state().error.equals("AUTHENTICATION_FAILED"),"WRONG_PASSWORD_STATE:"+jobs.state().stage+":"+jobs.state().error);check(Compat.readAll(current).equals("new current conversation"),"PASSWORD_ERROR_CHANGED_DATA");pass("retained_encrypted_copy_wrong_password_preserves_current_data");
            check(jobs.prepareRestoreTree(selected.retainedKey,Set.of("sessions"),"PRIVATE"),"OLD_TREE_PREFLIGHT_REFUSED");waitFor(jobs,"PREVIEW");check(Compat.readAll(current).equals("new current conversation"),"PREFLIGHT_CHANGED_DATA");
            check(jobs.decide(jobs.state().id,true),"CONFIRMATION_REFUSED");waitFor(jobs,"FINISHED");
            check(Compat.readAll(current).equals("original retained conversation"),"OLD_TREE_RESTORE_BYTES");check(originalHash.equals(BackupTree.digest(fs,old,new BackupControl(null))),"RESTORE_CHANGED_ORIGINAL");pass("old_tree_restore_requires_preview_and_preserves_retained_source");
            put(current,"current replacement fixture");check(jobs.prepareRestoreCopy(copy,password,Set.of("sessions"),false,"PRIVATE"),"PRIVATE_COPY_RESTORE_REFUSED:"+jobs.state().error);waitFor(jobs,"PREVIEW");check(jobs.decide(jobs.state().id,true),"PRIVATE_COPY_CONFIRMATION");waitFor(jobs,"FINISHED");
            check(Compat.readAll(current).equals("original retained conversation"),"PRIVATE_COPY_RESTORE_BYTES");Arrays.fill(password,'\0');pass("verified_private_copy_uses_existing_restore_pipeline");
            quarantineCopy(context,files);
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));}
        finally{
            if(parked!=null&&bash!=null&&controller!=null)try{File from=parked,to=bash;BackupManager.runDataTask(controller,()->{if(fs.stat(from).type.equals("FILE"))fs.move(from,to);return null;});}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            if(scope!=null)try{scope.close();}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"flavor",BuildConfig.FLAVOR),65536),StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish(result.containsKey("failure")||result.containsKey("cleanupFailure")?1:0,result);
        }
    }
}
