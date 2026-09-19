package com.deepseekharness.app;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 在非调试 Android/proot 运行真实 Python、pnpm 和软链/强杀夹具。 */
public final class PluginScriptsDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();
    private final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();
    private void copy(String asset,File base,String relative)throws IOException{
        String[] children=getTargetContext().getAssets().list(asset);
        if(children!=null&&children.length>0){fs.parents(base,relative+"/placeholder");for(String name:children)copy(asset+"/"+name,base,relative+"/"+name);return;}
        fs.parents(base,relative);try(InputStream in=getTargetContext().getAssets().open(asset);OutputStream out=fs.create(new File(base,relative))){byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){if((total+=n)>2*1024*1024)throw new IOException("TEST_ASSET_LIMIT");out.write(buffer,0,n);}}
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;
        try{
            DeviceAuditSupport.requireIsolated(getTargetContext());screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);HarnessController controller=HarnessController.get(getTargetContext());
            if(!controller.proot().hasBash())throw new IOException("TEST_REQUIRES_INSTALLED_RUNTIME");
            File root=controller.proot().getRootfsDir().getCanonicalFile();String name="plugin-audit-"+UUID.randomUUID();File repo=new File(root,"root/"+name);fs.directory(repo);
            copy("device-checks",repo,"repo");String guest="/root/"+name+"/repo";
            BackupManager.runDataTask(controller,()->{
                for(String suite:new String[]{"test-plugin-discovery.py","test-plugin-dependencies.py","test-plugin-transactions.py","test-plugin-review.py"}){
                    String command="set -e; export DSHA_TEST_PNPM_CLI=/usr/local/lib/dsha-pnpm/dist/pnpm.cjs; export DSHA_TEST_NODE=node; python3 "+ShellQuote.arg(guest+"/tools/"+suite)+"; printf '\\nDSHA_PLUGIN_SUITE_PASS\\n'";
                    String output=controller.proot().execAndReadWithProot(command,600000);
                    if(!output.contains("DSHA_PLUGIN_SUITE_PASS"))throw new IOException("PLUGIN_SUITE_FAILED "+suite+":\n"+output);
                    if(output.contains("skipped="))throw new IOException("ANDROID_SUITE_SKIPPED "+suite+":\n"+output);
                    tests.add(Map.of("name",suite,"status","PASS","output",SensitiveData.redact(output)));
                    Bundle update=new Bundle();update.putString("case",suite);update.putString("status","PASS");sendStatus(1,update);
                }return null;
            });
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));}
        finally{if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")?"FAIL":"PASS","tests",tests,"flavor",BuildConfig.FLAVOR,"scope","isolated Android/proot fixtures, actual pnpm and process termination"),256*1024),StandardCharsets.UTF_8));}catch(IOException error){result.putString("failure",error.toString());}
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
