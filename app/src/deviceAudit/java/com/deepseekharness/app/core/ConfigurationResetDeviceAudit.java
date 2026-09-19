package com.deepseekharness.app.core;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.*;

/** 在测试安装内走真实 BackupTask；Bash 缺失、Keystore 正常与损坏凭据分别验证。 */
public final class ConfigurationResetDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> checks=new ArrayList<>();
    private void require(boolean value,String failure)throws IOException{if(!value)throw new IOException(failure);}
    private void passed(String name){checks.add(Map.of("name",name,"status","PASS"));Bundle update=new Bundle();update.putString("case",name);update.putString("status","PASS");sendStatus(1,update);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void waitFor(BackupTask task)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+30000;while(task.busy()&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(80);require(!task.busy(),"RESET_TEST_STILL_RUNNING");}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;HarnessController controller=null;Map<String,Object> original=null;
        AndroidBackupFileSystem fs=new AndroidBackupFileSystem();File bash=null,disabled=null,settings=null;byte[] previous=null;boolean settingsCaptured=false;int previousMode=0600;
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            controller=HarnessController.get(context);HarnessController active=controller;original=controller.config().hostSettingsState();
            File files=context.getFilesDir().getCanonicalFile(),home=new UserDataLayout(fs,files).current();settings=new File(home,"settings.yaml");
            var prior=fs.stat(settings);if(prior.type.equals("FILE")){previous=fs.small(settings,1024*1024);previousMode=prior.mode;}
            else require(prior.type.equals("MISSING"),"TEST_SETTINGS_NOT_REGULAR");settingsCaptured=true;
            File project=new File(files,"linux/ubuntu/root/device-reset-"+UUID.randomUUID());fs.directory(project);File env=new File(project,".env"),conversation=new File(project,"conversation.txt");
            Compat.write(env,"ORIGINAL=value\n");Compat.write(conversation,"owned conversation bytes");
            fs.atomic(home,"settings.yaml","original: true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            controller.config().setWorkdir("/root/"+project.getName());require(controller.config().saveApiKey("fixture-native-key-for-reset"),"TEST_KEYSTORE_UNAVAILABLE");
            Map<String,Object> configured=controller.config().hostSettingsState();
            bash=new File(files,"linux/ubuntu/usr/bin/bash");disabled=new File(bash.getParentFile(),".device-audit-bash-"+UUID.randomUUID());File bashSource=bash,bashTarget=disabled;
            BackupManager.runDataTask(controller,()->{fs.move(bashSource,bashTarget);return null;});require(!controller.proot().hasBash(),"TEST_BASH_NOT_DISABLED");
            BackupTask task=BackupTask.get(context);require(task.resetConfig(),"RESET_TASK_REJECTED");waitFor(task);
            require(task.snapshot().status==BackupTaskState.Status.SUCCEEDED,"RESET_TASK_FAILED:"+task.snapshot().detail);
            require(Compat.readAll(env).equals("DEEPSEEK_API_KEY='fixture-native-key-for-reset'\n"),"RESET_WRONG_ENVIRONMENT");
            require(Compat.readAll(settings).equals("{}\n"),"RESET_WRONG_SETTINGS");
            require(configured.equals(controller.config().hostSettingsState()),"RESET_CHANGED_NATIVE_SETTINGS");
            require(Compat.readAll(conversation).equals("owned conversation bytes"),"RESET_CHANGED_CONVERSATION");
            File operations=new File(files,"host-backup-operations");File saved=null;
            for(String id:fs.list(operations)){File operation=new File(operations,id);if(NativeConfigurationReset.owns(fs,operation)&&fs.stat(new File(operation,"finalized")).type.equals("FILE")){
                var metadata=BackupJson.read(fs.small(new File(operation,"config-reset.json"),4096),4096);if(metadata.get("workspace").equals("/root/"+project.getName()))saved=operation;
            }}
            require(saved!=null&&Compat.readAll(new File(saved,"previous/environment")).equals("ORIGINAL=value\n")&&Compat.readAll(new File(saved,"previous/settings")).equals("original: true\n"),"RESET_ORIGINALS_MISSING");
            passed("native_reset_without_bash_preserves_originals_and_keystore");
            String originalEnv=Compat.readAll(env),originalSettings=Compat.readAll(settings);
            require(context.getSharedPreferences(Constants.PREFS,0).edit().putString(Constants.KEY_API_KEY,"fixture-invalid-ciphertext").commit(),"TEST_PREF_WRITE");
            require(task.resetConfig(),"INVALID_KEY_RESET_NOT_STARTED");waitFor(task);
            require(task.snapshot().status==BackupTaskState.Status.FAILED,"INVALID_KEY_RESET_ACCEPTED");
            require(Compat.readAll(env).equals(originalEnv)&&Compat.readAll(settings).equals(originalSettings),"INVALID_KEY_RESET_CHANGED_FILES");
            require(context.getSharedPreferences(Constants.PREFS,0).getString(Constants.KEY_API_KEY,"").equals("fixture-invalid-ciphertext"),"INVALID_KEY_WAS_CLEARED");
            passed("unavailable_native_key_does_not_clear_configuration");
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(controller!=null)try{
                HarnessController active=controller;File source=disabled,target=bash,configFile=settings;byte[] bytes=previous;int mode=previousMode;boolean captured=settingsCaptured;Map<String,Object> preferences=original;
                BackupManager.runDataTask(controller,()->{
                    if(source!=null&&fs.stat(source).type.equals("FILE"))fs.move(source,target);
                    if(captured){if(bytes==null){if(fs.stat(configFile).type.equals("FILE"))fs.delete(configFile);}else{fs.atomic(configFile.getParentFile(),configFile.getName(),bytes);fs.mode(configFile,mode);}}
                    if(preferences!=null)active.config().applyHostSettings(preferences);return null;
                });
            }catch(Exception error){result.putString("cleanupFailure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
            if(screen!=null){Activity closed=screen;runOnMainSync(closed::finish);}
            Map<String,Object> report=Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",checks,"nonDebuggable",true,"flavor",BuildConfig.FLAVOR);
            try{result.putString("report",new String(BackupJson.write(report,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
