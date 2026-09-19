package com.deepseekharness.app.backup;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** 真实 Repository/维护屏障/Cordis 加载故障闭环；仅合成插件、独立非调试安装。 */
public final class PluginWorkflowDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();private PluginRepository repository;private boolean inspectOnly;
    private void check(boolean value,String code)throws IOException{if(!value)throw new IOException(code);}
    private interface Checked {void run()throws Exception;}
    private void ui(Checked action)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new IOException("PLUGIN_UI",failure[0]);}
    private void waitFor(BooleanSupplier ready,long millis,String code)throws Exception{long until=android.os.SystemClock.elapsedRealtime()+millis,last=0;while(!ready.getAsBoolean()){
        if(android.os.SystemClock.elapsedRealtime()>until)throw new IOException(code+" · "+(repository==null?"":repository.state().getValue().message));
        if(android.os.SystemClock.elapsedRealtime()-last>5000){Bundle update=new Bundle();update.putString("progress",code);sendStatus(1,update);last=android.os.SystemClock.elapsedRealtime();}Thread.sleep(100);
    }}
    private void pass(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle result=new Bundle();result.putString("case",name);result.putString("status","PASS");sendStatus(1,result);}
    private PluginRepository.Item item(String name){var state=repository.state().getValue();if(state!=null)for(var item:state.items)if(item.name.equals(name))return item;return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);inspectOnly=args!=null&&args.getString("inspect_only","false").equals("true");start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;HarnessController controller=null;SharedPreferences prefs=null;String credential=null;boolean had=false;String name="dsha-audit-failure-"+UUID.randomUUID().toString().substring(0,8);
        try{
            Context context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);controller=HarnessController.get(context);
            if(inspectOnly){result.putString("lastFailure",StartupDiagnostics.lastFailure(context));StringBuilder history=new StringBuilder();for(var entry:controller.startupDiagnostics().history())history.append(entry.status).append(' ').append(entry.stage).append('\n').append(entry.log).append('\n');result.putString("history",SensitiveData.redact(history.toString()));
                File ledger=new File(new UserDataLayout(new AndroidBackupFileSystem(),context.getFilesDir().getCanonicalFile()).current(),"plugin-activations.json");if(ledger.isFile())result.putString("activationLedger",SensitiveData.redact(Compat.readAll(ledger)));return;}
            check(controller.isEnvironmentReady()&&EnvironmentAccess.runtimeLatest(controller),"RUN_CURRENT_RUNTIME_AUDIT_FIRST");
            prefs=context.getSharedPreferences(Constants.PREFS,Context.MODE_PRIVATE);had=prefs.contains(Constants.KEY_API_KEY);credential=prefs.getString(Constants.KEY_API_KEY,"");check(prefs.edit().remove(Constants.KEY_API_KEY).commit(),"CREDENTIAL_FIXTURE");
            HarnessController active=controller;File root=controller.proot().getRootfsDir(),executed=new File(root,"root/"+name+".executed");
            File workspace=new File(context.getFilesDir(),"device-workflow-"+UUID.randomUUID());check(workspace.mkdir(),"FIXTURE_DIRECTORY");Uri uri=AuditDocumentProvider.create(context,workspace,"normal");
            JSONObject pkg=new JSONObject().put("name",name).put("version","1.0.0").put("type","module").put("exports",new JSONObject().put(".","./index.js"))
                    .put("dsh",new JSONObject().put("bundle",new JSONObject().put("patch","cordis.patch.yml")));
            Map<String,String> files=Map.of("package.json",pkg.toString(),"cordis.patch.yml","- insert:\n    - id: owned-failing-plugin\n      name: '"+name+"'\n",
                    "index.js","import fs from 'node:fs';fs.appendFileSync('/root/"+name+".executed','x');export const inject=[];export function apply(){throw Error('DSHA_OWNED_PLUGIN_FAILURE');}\n");
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(AuditDocumentProvider.document(uri).file))){for(var entry:files.entrySet()){out.putNextEntry(new ZipEntry("package/"+entry.getKey()));out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry();}}
            AuditDocumentProvider.document(uri).written.countDown();ui(()->repository=new PluginRepository((Application)context.getApplicationContext()));
            ui(()->repository.importArchive(uri));waitFor(()->!repository.isBusy()&&repository.preview().getValue()!=null,180000,"WAIT_STATIC_PREVIEW");
            check(!executed.exists(),"PLUGIN_EXECUTED_DURING_PREVIEW");pass("real_repository_static_preview_does_not_execute_plugin");
            ui(repository::confirmPreview);waitFor(()->!repository.isBusy(),180000,"WAIT_INSTALL_TRANSACTION");
            check(repository.installationSucceeded()&&item(name)!=null&&!item(name).enabled,"INSTALL_DID_NOT_REMAIN_DISABLED");check(!executed.exists(),"PLUGIN_EXECUTED_BEFORE_ENABLE");
            check(!BackupManager.hasPendingMaintenance(controller),"INSTALL_JOURNAL_NOT_FINALIZED");pass("confirmed_install_uses_maintenance_and_remains_disabled");
            ui(()->repository.setEnabled(item(name),true));waitFor(()->!repository.isBusy()&&repository.preview().getValue()!=null,180000,"WAIT_ENABLE_REVIEW");check(!executed.exists(),"PLUGIN_EXECUTED_DURING_ENABLE_REVIEW");
            ui(repository::confirmPreview);waitFor(()->!repository.isBusy(),180000,"WAIT_APPROVED_ACTIVATION");check(item(name)!=null&&item(name).enabled,"APPROVED_PLUGIN_NOT_ENABLED");
            check(!executed.exists(),"PLUGIN_EXECUTED_WITHOUT_WEB_START");pass("activation_requires_second_explicit_review_and_keeps_code_idle");
            check(controller.startWeb(message->{}),"WEB_START_REFUSED");waitFor(executed::exists,180000,"WAIT_REAL_CORDIS_LOAD");
            File ledger=new File(new UserDataLayout(new AndroidBackupFileSystem(),context.getFilesDir().getCanonicalFile()).current(),"plugin-activations.json");
            waitFor(()->{try{JSONObject entries=new JSONObject(Compat.readAll(ledger)).getJSONObject("entries");String state=entries.getJSONObject(name).optString("status");return state.equals("failed")||state.equals("unconfirmed");}catch(Exception error){return false;}},90000,"WAIT_FAILED_ACTIVATION_RECOVERY");
            check(executed.length()==1,"FAILED_CANDIDATE_EXECUTED_REPEATEDLY");
            waitFor(()->!BackupManager.isEnvironmentTaskBusy()&&!active.isStarting()&&!active.isStopping(),30000,"WAIT_RECOVERY_DRAIN");
            String output=controller.proot().runPluginManager("list");JSONObject list=new JSONObject(PluginOutput.resultJson(output));boolean disabled=false;
            for(int i=0;i<list.getJSONArray("items").length();i++){JSONObject row=list.getJSONArray("items").getJSONObject(i);if(name.equals(row.optString("name")))disabled=!row.optBoolean("enabled");}
            check(disabled,"FAILED_PLUGIN_STILL_ACTIVE");check(new File(root,"root/.dsh/plugin-src/"+name+"/index.js").isFile(),"FAILED_PLUGIN_SOURCE_REMOVED");pass("actual_cordis_failure_disables_candidate_and_retains_source");
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));if(controller!=null)result.putString("startupTrace",SensitiveData.redact(controller.startupDiagnostics().snapshot().log));}
        finally{
            if(controller!=null&&!inspectOnly)try{HarnessController active=controller;BackupManager.runDataTask(controller,()->{active.proot().execAndReadWithProot("python3 /root/.dsh/plugin-manager.py delete "+ShellQuote.arg(name),60000);return null;});}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            if(prefs!=null){var edit=prefs.edit();if(had)edit.putString(Constants.KEY_API_KEY,credential);else edit.remove(Constants.KEY_API_KEY);if(!edit.commit())result.putString("cleanupFailure","CREDENTIAL_RESTORE");}
            if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"flavor",BuildConfig.FLAVOR),65536),StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish(result.containsKey("failure")||result.containsKey("cleanupFailure")?1:0,result);
        }
    }
}
