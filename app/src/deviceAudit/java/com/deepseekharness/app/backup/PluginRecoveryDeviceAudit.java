package com.deepseekharness.app.backup;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原生恢复入口处理实际插件日志，覆盖 /data/data 别名与单次恢复授权文件。 */
public final class PluginRecoveryDeviceAudit extends Instrumentation {
    private void check(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);HarnessController controller=HarnessController.get(context);
            check(!BackupManager.hasPendingMaintenance(controller),"EXISTING_AUDIT_MAINTENANCE");
            String name="dsha-native-recovery-"+UUID.randomUUID().toString().substring(0,8);File root=controller.proot().getRootfsDir().getCanonicalFile();
            String code="import importlib.util,os,json\nspec=importlib.util.spec_from_file_location('audit_manager','/root/.dsh/plugin-manager.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)\n"
                    +"name="+new org.json.JSONArray().put(name).toString()+"[0]\nt=m.transactions()\nwith t.workspace() as work:\n"
                    +" p=os.path.join(work,'new');os.mkdir(p);m.write_json(os.path.join(p,'package.json'),{'name':name,'version':'1.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})\n"
                    +" open(os.path.join(p,'cordis.patch.yml'),'w').write('[]\\n')\n doc=m.builtin.read_manifest();sources=m.read_json(m.local(m.SOURCES),{});sources[name]='owned recovery fixture'\n"
                    +" doc.setdefault('dependencies',{})[name]='link:'+os.path.join(m.PLUGIN_SRC,name)\n plan=t.prepare(work,name,p,doc,sources,b'DSHA_REVIEW_REQUIRED\\n')\n"
                    +" t.apply_file(work,'marker',plan);os.makedirs(m.local(m.PLUGIN_SRC),exist_ok=True);os.replace(p,m.local(os.path.join(m.PLUGIN_SRC,name)));t.apply_file(work,'sources',plan);t.apply_file(work,'manifest',plan)\n"
                    +"print('DSHA_NATIVE_PLUGIN_RECOVERY_READY')\n";
            File script=new File(root,"root/"+name+".py");try(FileOutputStream out=new FileOutputStream(script)){out.write(code.getBytes(StandardCharsets.UTF_8));}
            BackupManager.runDataTask(controller,()->{String text=controller.proot().execAndReadWithProot("python3 "+ShellQuote.arg("/root/"+script.getName()),60000);check(text.contains("DSHA_NATIVE_PLUGIN_RECOVERY_READY"),"RECOVERY_FIXTURE_FAILED:"+text);return null;});
            check(BackupManager.hasPendingMaintenance(controller),"PLUGIN_JOURNAL_NOT_IN_NATIVE_GATE");
            BackupTask task=BackupTask.get(context);boolean[] began={false};runOnMainSync(()->began[0]=task.recoverMaintenance());check(began[0],"NATIVE_RECOVERY_NOT_STARTED");
            long until=android.os.SystemClock.elapsedRealtime()+120000;while(task.busy()&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            check(task.snapshot().status==BackupTaskState.Status.SUCCEEDED,"NATIVE_RECOVERY_FAILED:"+task.snapshot().detail);check(!BackupManager.hasPendingMaintenance(controller),"NATIVE_GATE_REMAINED_BLOCKED");
            check(!new File(root,"root/.dsh/plugin-src/"+name).exists(),"NEW_PLUGIN_NOT_RETAINED_OUTSIDE_ACTIVE_TREE");
            var fs=new AndroidBackupFileSystem();File home=new UserDataLayout(fs,context.getFilesDir().getCanonicalFile()).current();boolean retained=false;
            for(String id:fs.list(new File(home,PluginInstallJournals.DIRECTORY))){File failed=new File(home,PluginInstallJournals.DIRECTORY+"/"+id+"/failed/package.json");if(failed.isFile()&&Compat.readAll(failed).contains(name))retained=true;}
            check(retained,"FAILED_CANDIDATE_NOT_RETAINED");
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")?"FAIL":"PASS","flavor",BuildConfig.FLAVOR,"scope","native maintenance entry, retained synthetic plugin transaction"),65536),StandardCharsets.UTF_8));}catch(IOException error){result.putString("failure",error.toString());}finish(result.containsKey("failure")?1:0,result);}
    }
}
