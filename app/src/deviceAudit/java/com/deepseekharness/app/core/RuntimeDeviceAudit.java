package com.deepseekharness.app.core;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.Compat;
import java.io.*;
import java.util.*;

/** 仅在独立验收安装中准备冷环境和试运行，不能以用户安装作为故障夹具。 */
public final class RuntimeDeviceAudit extends Instrumentation {
    private Bundle args;private long last;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args==null?new Bundle():args;start();}
    private void report(String value){long now=android.os.SystemClock.elapsedRealtime();if(now-last<2000)return;last=now;Bundle b=new Bundle();b.putString("progress",value);sendStatus(1,b);}
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;
        try{
            Context context=getTargetContext();if(!context.getPackageName().equals("com.dsh.client.rc21audit"))throw new IOException("REFUSE_USER_INSTALLATION");
            if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)throw new IOException("REQUIRES_NON_DEBUGGABLE");
            HarnessController controller=HarnessController.get(context);boolean requested=Boolean.parseBoolean(args.getString("proroot","false"));controller.config().setProroot(requested);
            activity=com.deepseekharness.app.DeviceAuditSupport.open(this,com.deepseekharness.app.ui.NativeDataActivity.class);
            Activity auditScreen=activity;runOnMainSync(()->auditScreen.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            if(!controller.proot().isEnvironmentInstalled()){
                File root=controller.proot().getRootfsDir(),data=new File(root,"root/.dsh/sessions/device-fixture.txt");data.getParentFile().mkdirs();Compat.write(data,"owned conversation fixture");
                File project=new File(root,"root/device-project/.env");project.getParentFile().mkdirs();Compat.write(project,"owned project fixture");controller.config().setWorkdir("/root/device-project");
                BackupManager.runDataTask(controller,()->EnvironmentMaintenance.rebuild(controller,this::report));
                if(!Compat.readAll(data).equals("owned conversation fixture")||!Compat.readAll(project).equals("owned project fixture"))throw new IOException("REBUILD_DATA_LOSS");
            }else BackupManager.runDataTask(controller,()->EnvironmentMaintenance.update(controller,this::report));
            if(!controller.isEnvironmentReady())throw new IOException("RUNTIME_NOT_READY");
            var health=controller.proot().runtimeHealth();
            boolean fallback=requested&&"proot".equals(health.get("runtimeMode"))&&"proroot".equals(health.get("fallbackFrom"))&&health.get("fallbackExitCode") instanceof Number;
            if(!(requested?"proroot":"proot").equals(health.get("runtimeMode"))&&!fallback)throw new IOException("TRIAL_WRONG_RUNTIME_MODE");
            if(fallback){result.putString("fallbackFrom","proroot");result.putString("fallbackExitCode",String.valueOf(health.get("fallbackExitCode")));}
            result.putString("runtimeMode",String.valueOf(controller.proot().runtimeHealth().get("runtimeMode")));
            result.putString("health",BackupJson.write(controller.proot().runtimeHealth(),1024*1024).length+" bytes; renderer and backend confirmed");
            File files=context.getFilesDir().getCanonicalFile();var fs=new AndroidBackupFileSystem();var layout=new UserDataLayout(fs,files);var previous=layout.selected();
            File stable=layout.root(UserDataLayout.Home.STABLE);if(!stable.exists())stable.mkdirs();File probe=new File(stable,"audit-stable.txt");Compat.write(probe,"stable data bytes");
            try{BackupManager.runDataTask(controller,()->{
                layout.choose(UserDataLayout.Home.STABLE);
                String command="set -e; test \"$(cat /root/.dsh/audit-stable.txt)\" = 'stable data bytes'; test -s /root/.dsh/register-builtin-plugins.py; printf '\\nDSHA_STABLE_BOUND\\n'";
                for(boolean proroot:new boolean[]{false,true}){controller.config().setProroot(proroot);String output=controller.proot().execAndRead(command,30000);if(!output.contains("DSHA_STABLE_BOUND"))throw new IOException("STABLE_BIND_FAILED_"+proroot+":"+output);}
                return null;
            });}finally{BackupManager.runDataTask(controller,()->{layout.choose(previous);controller.config().setProroot(requested);return null;});}
            result.putString("result","PASS");result.putString("runtime",controller.proot().installedRuntimeDescriptor().id());
        }catch(Throwable e){result.putString("failure",com.deepseekharness.app.util.SensitiveData.redact(android.util.Log.getStackTraceString(e)));}
        finally{if(activity!=null){Activity opened=activity;runOnMainSync(opened::finish);}finish(result.containsKey("failure")?1:0,result);}
    }
}
