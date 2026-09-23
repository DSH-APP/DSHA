package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 使用真实启动/网页结果收敛本轮已批准插件；文件和聊天不随加载失败回退。 */
public final class PluginActivationHooks {
    private PluginActivationHooks() { }
    private static final ScheduledThreadPoolExecutor IO=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"plugin-activation");t.setDaemon(true);return t;});
    private static java.util.concurrent.ScheduledFuture<?> waiting;
    private static long epoch;
    static { IO.setRemoveOnCancelPolicy(true); }
    private static boolean present(HarnessController controller,String startup)throws IOException{
        var fs=new AndroidBackupFileSystem();File files=controller.context().getFilesDir().getCanonicalFile();File ledger=new File(new UserDataLayout(fs,files).current(),"plugin-activations.json");
        if(fs.stat(ledger).type.equals("MISSING"))return false;
        var value=BackupJson.read(fs.small(ledger,1024*1024),1024*1024);
        if(!Long.valueOf(1).equals(value.get("format"))||!(value.get("entries") instanceof java.util.Map))throw new IOException("PLUGIN_ACTIVATION_RECORD");
        var entries=(java.util.Map<?,?>)value.get("entries");if(entries.size()>256)throw new IOException("PLUGIN_ACTIVATION_RECORD");
        for(Object entry:entries.values()){
            if(!(entry instanceof java.util.Map))throw new IOException("PLUGIN_ACTIVATION_RECORD");var row=(java.util.Map<?,?>)entry;Object state=row.get("status");
            if(!(state instanceof String))throw new IOException("PLUGIN_ACTIVATION_RECORD");
            if(startup==null?("queued".equals(state)||"attempted".equals(state)):("attempted".equals(state)&&startup.equals(row.get("startup"))))return true;
        }
        return false;
    }
    private static void command(HarnessController controller,String command,String id)throws Exception{
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("PLUGIN_STARTUP_ID");
        String text=controller.proot().runPluginManager(command+" "+ShellQuote.arg(id));String json=PluginOutput.resultJson(text);
        JSONObject result=new JSONObject(json);if(!"ok".equals(result.optString("status")))throw new IOException(UiStateText.render(result.optString("message","PLUGIN_ACTIVATION_FAILED")));
    }
    public static void beforeLaunch(HarnessController controller,String id)throws Exception{if(present(controller,null))command(controller,"begin-load",id);}
    public static synchronized void healthy(HarnessController controller,long generation,String id){schedule(controller,generation,id,false,++epoch,0);}
    public static synchronized void failed(HarnessController controller,long generation,String id){schedule(controller,generation,id,true,++epoch,0);}
    private static synchronized void schedule(HarnessController controller,long generation,String id,boolean failed,long request,int attempt){
        if(waiting!=null)waiting.cancel(false);
        waiting=IO.schedule(()->finish(controller,generation,id,failed,request,attempt),attempt==0?0:250,TimeUnit.MILLISECONDS);
    }
    private static void finish(HarnessController controller,long generation,String id,boolean failed,long request,int attempt){
        synchronized(PluginActivationHooks.class){if(request!=epoch)return;}
        if(controller.getWebGeneration()!=generation&&(!failed||!id.equals(controller.startupDiagnostics().recordId())))return;
        EnvironmentTaskGate.Lease lease=EnvironmentTaskGate.tryAcquire("插件加载确认");
        if(lease==null){if(attempt<40)schedule(controller,generation,id,failed,request,attempt+1);return;}
        try(lease;RuntimeTasks work=RuntimeTasks.begin()){
            lease.run(()->{
                if((controller.getWebGeneration()!=generation&&(!failed||!id.equals(controller.startupDiagnostics().recordId())))||!present(controller,id))return null;
                if(failed)com.deepseekharness.app.BackupManager.runDataTask(controller,()->{command(controller,"failed-load",id);return null;});
                else if(controller.startupDiagnostics().snapshot().browserReady&&controller.startupDiagnostics().snapshot().issues.isEmpty())command(controller,"complete-load",id);
                return null;
            });
        }catch(Exception error){DiagnosticLog.record(controller.context(),"PLUGIN_ACTIVATION",SensitiveData.redact(String.valueOf(error)));}
    }
}
