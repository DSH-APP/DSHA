package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.*;
import org.json.*;
import java.io.*;

/** 原生恢复入口通过离线 Python 管理声明文件，不启动故障 Host 或执行插件代码。 */
public final class StartupRepairs {
    private StartupRepairs() { }
    public static boolean pending(Context context) {
        return new File(context.getFilesDir(),"startup-config-repair.pending").exists()
                || new File(context.getFilesDir(),"linux/ubuntu/root/.dsh/dsha-startup-checkpoints/pending.json").exists();
    }
    private static JSONObject result(String text,String prefix) throws IOException {
        for(String line:text.split("\n"))if(line.startsWith(prefix))try {
            JSONObject result=new JSONObject(line.substring(prefix.length()));
            if(!"ok".equals(result.optString("status")))throw new IOException(result.optString("message"));
            return result;
        } catch(JSONException error) { throw new IOException("RECOVERY_FORMAT",error); }
        throw new IOException(SensitiveData.redact(text));
    }
    public static JSONObject checkpoint(HarnessController c,JSONObject request) throws IOException {
        return result(c.proot().execAndReadWithProot("python3 /root/.dsh/startup-checkpoints.py " + ShellQuote.arg(request.toString()),30_000),"STARTUP_RECOVERY_RESULT=");
    }
    public static JSONObject list(HarnessController c) throws Exception {
        return checkpoint(c,new JSONObject().put("command","list"));
    }
    public static JSONObject plugins(HarnessController c) throws IOException {
        return result(c.proot().runPluginManager("list"),"PLUGIN_RESULT: ");
    }
    public static String change(Context context,HarnessController c,JSONObject request) throws Exception {
        File marker=new File(context.getFilesDir(),"startup-config-repair.pending");
        if(!marker.exists() && !marker.createNewFile())throw new IOException("RECOVERY_PENDING");
        checkpoint(c,request);
        if(list(c).optBoolean("pending"))throw new IOException("RECOVERY_PENDING");
        if(!marker.delete() && marker.exists())throw new IOException("RECOVERY_PENDING");
        return UiText.choose("配置修复已完成，原配置快照已保留。可重试启动；已卸载的插件需要重新安装。",
                "Configuration repaired; the previous configuration was saved. Retry startup. Removed plugins must be reinstalled if needed.");
    }
    public static String deletePlugin(HarnessController c,String name) throws Exception {
        if(!name.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*") || BuiltinPlugins.internal(name))throw new IOException("RECOVERY_PLUGIN");
        JSONArray items=plugins(c).getJSONArray("items");boolean found=false;
        for(int i=0;i<items.length();i++) { JSONObject item=items.getJSONObject(i);if(name.equals(item.optString("name"))&&item.optBoolean("deletable"))found=true; }
        if(!found)throw new IOException("RECOVERY_PLUGIN");
        checkpoint(c,new JSONObject().put("command","before"));
        result(c.proot().runPluginManager("delete "+ShellQuote.arg(name)),"PLUGIN_RESULT: ");
        return UiText.choose("插件已卸载，可重试启动：", "Plugin removed. You can retry startup: ")+name;
    }
    public static void healthy(Context context,HarnessController c,long generation) {
        if(pending(context)||c.getWebGeneration()!=generation||!c.startupDiagnostics().snapshot().browserReady)return;
        EnvironmentTaskGate.Lease lease=EnvironmentTaskGate.tryAcquire("启动配置快照");
        if(lease==null)return;
        try(lease) {
            lease.run(()->{
                if(c.getWebGeneration()==generation&&!c.isStopping())checkpoint(c,new JSONObject().put("command","healthy").put("startupId",c.startupDiagnostics().recordId()));
                return null;
            });
        } catch(Exception error) { DiagnosticLog.record(context,"STARTUP_CHECKPOINT",SensitiveData.redact(String.valueOf(error))); }
    }
}
