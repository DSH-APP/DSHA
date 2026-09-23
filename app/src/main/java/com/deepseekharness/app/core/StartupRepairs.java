package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.*;
import org.json.*;
import java.io.*;

/** 六文件检查点与配置恢复由宿主执行；插件管理保留独立的既有运行时入口。 */
public final class StartupRepairs {
    private StartupRepairs() { }
    public static String prepareSafeProfile(HarnessController controller)throws IOException{
        var fs=new com.deepseekharness.app.backup.AndroidBackupFileSystem();File root=controller.context().getFilesDir().getCanonicalFile();
        // 声明根若被替换为链接则保留原物，交给原生数据救援处理，不能按空目录新建。
        File dsh=new com.deepseekharness.app.backup.UserDataLayout(fs,root).current();
        return com.deepseekharness.app.backup.NativeSafeProfile.create(fs,dsh,java.util.UUID.randomUUID().toString().replace("-","").substring(0,16));
    }
    public static boolean pending(Context context) {
        return com.deepseekharness.app.backup.ConfigurationSnapshots.blocked(context.getFilesDir());
    }
    public static com.deepseekharness.app.backup.ConfigurationSnapshots snapshots(Context context)throws IOException{
        File files=context.getFilesDir().getCanonicalFile();var fs=new com.deepseekharness.app.backup.AndroidBackupFileSystem();return new com.deepseekharness.app.backup.ConfigurationSnapshots(fs,files,new com.deepseekharness.app.backup.UserDataLayout(fs,files).current());
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
        var engine=snapshots(c.context());var control=BackupTask.currentControl(null);String command=request.optString("command");
        if(java.util.List.of("new","restore","recover").contains(command)&&!com.deepseekharness.app.BackupManager.isDataTaskOwner())throw new IOException("RECOVERY_REQUIRES_MAINTENANCE");
        try{
            switch(command){
                case "list":return new JSONObject(engine.list(control));
                case "before":return new JSONObject().put("status","ok").put("slot",engine.before(control));
                case "prepare":engine.prepare(request.getString("startupId"),control);break;
                case "healthy":return new JSONObject(engine.healthy(request.getString("startupId"),control));
                case "new":engine.create(request.getString("target"),control);break;
                case "restore":engine.restore(request.getString("slot"),request.getString("id"),control);break;
                case "recover":return new JSONObject().put("status","ok").put("message",engine.recover(control));
                default:throw new IOException("RECOVERY_FORMAT");
            }return new JSONObject().put("status","ok");
        }catch(JSONException error){throw new IOException("RECOVERY_FORMAT",error);}
    }
    public static JSONObject list(HarnessController c) throws Exception {
        return checkpoint(c,new JSONObject().put("command","list"));
    }
    public static JSONObject plugins(HarnessController c) throws IOException {
        return result(c.proot().runPluginManager("list"),"PLUGIN_RESULT: ");
    }
    public static String change(Context context,HarnessController c,JSONObject request) throws Exception {
        if("recover".equals(request.optString("command")))EnvironmentMaintenance.ensureSingleRecovery(c);
        JSONObject result=checkpoint(c,request);
        if(pending(context))throw new IOException("RECOVERY_PENDING");
        if("RECOVERY_MARKER_RETAINED".equals(result.optString("message")))return UiText.choose(
                "旧状态标记已保留到私有记录中。没有配置日志可回滚，当前配置保持原样；可继续检查或修复运行环境。",
                "The old state marker was retained privately. No configuration journal was available to roll back, so current configuration stayed unchanged. Continue inspecting or repairing the runtime.");
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
        PluginActivationHooks.healthy(c,generation,c.startupDiagnostics().recordId());
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
