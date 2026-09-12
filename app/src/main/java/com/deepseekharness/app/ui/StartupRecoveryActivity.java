package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import org.json.*;
import java.util.*;

/** Web 无法加载时仍可使用的原生恢复工作台。 */
public final class StartupRecoveryActivity extends AppCompatActivity {
    private HarnessController controller;
    private StartupRecoveryModel model;
    private LinearLayout content,attempts,snapshots,plugins;
    private TextView progress;
    private StartupRecoveryLayout layout;
    private final List<View> actions=new ArrayList<>();
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private String previousTask="";
    private boolean wasBusy;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);controller=HarnessController.get(this);
        if(!controller.config().isWelcomed()){startActivity(new Intent(this,WelcomeActivity.class));finish();return;}
        String reason=controller.config().getWebFailureReason();
        if(reason.isEmpty())reason=controller.startupDiagnostics().snapshot().stage;
        layout=new StartupRecoveryLayout(this,StartupText.render(reason),()->finish());setContentView(layout.root);
        content=layout.content;attempts=layout.attempts;snapshots=layout.snapshots;plugins=layout.plugins;progress=layout.progress;
        action(layout.recovery,t("重试启动","Retry startup"),R.drawable.ic_recovery_refresh,()->retry(false),true);
        action(layout.recovery,t("安全启动基础界面","Start the basic interface in safe mode"),R.drawable.ic_recovery_shield,()->retry(true),true);
        action(layout.recovery,t("新建配置文件","Create configuration file"),R.drawable.ic_recovery_new_file,this::newConfiguration,true);
        action(layout.recovery,t("恢复中断的配置修复","Recover interrupted configuration repair"),R.drawable.ic_recovery_wrench,()->confirm(
                t("恢复修复前配置","Restore the configuration from before the repair"),
                t("将使用修复前的快照回退中断的配置写入，然后才能重新启动。","Roll back the interrupted configuration changes using the saved pre-repair copy before restarting."),
                ()->repair(request("recover"))),true);
        action(layout.tools,t("环境安装与修复","Environment installation and repair"),R.drawable.ic_recovery_gear,()->startActivity(new Intent(this,ExtractActivity.class).putExtra("review_only",true)),false);
        action(layout.tools,t("查看并下载日志","View and download logs"),R.drawable.ic_recovery_document,()->startActivity(DiagnosticActivity.downloadLogs(this)),false);
        action(layout.tools,t("刷新恢复记录","Refresh recovery records"),R.drawable.ic_recovery_refresh,()->model.load(),false);
        model=new ViewModelProvider(this).get(StartupRecoveryModel.class);
        BackupTaskState.Snapshot initial=BackupTask.get(this).snapshot();
        previousTask=initial.id+":"+initial.status+":"+initial.detail;
        model.contents.observe(this,value->{if(value!=null)render(value);});
        model.error.observe(this,value->{if(value!=null&&!value.isEmpty())status(readable(value));renderAttempts();});
        if(model.contents.getValue()==null)model.load();
    }
    private TextView label(LinearLayout parent,String text){return layout.label(parent,text,13,R.color.text_muted);}
    private void action(LinearLayout parent,String text,int icon,Runnable click,boolean protectedAction){
        View row=layout.action(parent,text,icon,click,true);if(protectedAction)actions.add(row);
    }
    private void button(LinearLayout parent,String text,Runnable click,boolean protectedAction){action(parent,text,0,click,protectedAction);}
    private void status(String text){progress.setText(text);progress.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static JSONObject request(String command){JSONObject value=new JSONObject();try{value.put("command",command);}catch(JSONException ignored){}return value;}
    private void confirm(String title,String detail,Runnable work){new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(title)
            .setMessage(detail+t("\n\n操作会停止 Web 和终端，并保留修复前配置。","\n\nThis stops Web and terminal sessions and saves the configuration before making changes."))
            .setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("继续","Continue"),(d,w)->work.run()).show();}
    private void repair(JSONObject request){
        if(!BackupTask.get(this).repairStartup(request))Toast.makeText(this,t("已有任务进行中，请完成后重试。","Another task is in progress. Retry when it finishes."),Toast.LENGTH_LONG).show();
        refresh.run();
    }
    private void newConfiguration(){
        String[] names={t("Web 启动配置","Web startup configuration"),t("共享设置（settings.yaml）","Shared settings (settings.yaml)"),t("共享补丁（cordis.patch.yml）","Shared patch (cordis.patch.yml)")};
        String[] targets={"web","settings.yaml","cordis.patch.yml"};
        new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(t("选择新建的配置文件","Choose the configuration to create")).setItems(names,(d,index)->{
            JSONObject request=request("new");try{request.put("target",targets[index]);}catch(JSONException ignored){}
            confirm(names[index],t("将以默认内容替换所选配置。原文件保存到修复前快照，插件文件、会话和原生 API Key 保留。",
                    "Replace the selected configuration with defaults. Save the original files in a pre-repair snapshot and retain plugin files, sessions, and the native API key."),()->repair(request));
        }).setNegativeButton(t("取消","Cancel"),null).show();
    }
    private void retry(boolean safe){
        if(StartupRepairs.pending(this)){status(readable("RECOVERY_PENDING"));return;}
        controller.recoverWeb(safe,null,message->{});
        startActivity(new Intent(this,MainActivity.class).putExtra("open_launch",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();
    }
    private void renderAttempts(){
        if(attempts==null)return;attempts.removeAllViews();
        for(StartupHistoryStore.Entry entry:model.attempts){
            String status="ready".equals(entry.status)?t("就绪","Ready"):"failed".equals(entry.status)?t("失败","Failed"):
                    "stopped".equals(entry.status)?t("已停止","Stopped"):t("启动中／已中断","Starting / interrupted");
            button(attempts,date(entry.started)+" · "+status+" · "+entry.elapsed/1000+"s",()->{
                ScrollView scroll=new ScrollView(this);TextView text=new TextView(this);text.setPadding(dp(16),dp(12),dp(16),dp(12));text.setTextIsSelectable(true);
                text.setText(entry.stage+"\n\n"+StartupText.render(entry.reason)+"\n\n"+entry.log);scroll.addView(text);
                new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(date(entry.started)).setView(scroll).setPositiveButton(t("关闭","Close"),null).show();
            },false);
        }
        if(model.attempts.isEmpty()){TextView empty=label(attempts,controller.startupDiagnostics().historyError().isEmpty()
                ?t("还没有启动记录。下一次启动会自动记录。","No startup records yet. The next start will be recorded automatically.")
                :t("启动记录暂不可用：","Startup records are currently unavailable: ")+controller.startupDiagnostics().historyError());empty.setPadding(dp(36),dp(2),0,dp(2));}
    }
    private void render(JSONObject value){
        renderAttempts();snapshots.removeAllViews();plugins.removeAllViews();actions.removeIf(view->view.getParent()==null);
        JSONArray records=value.optJSONArray("snapshots");
        if(records!=null)for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);if(record==null)continue;
            if(record.optBoolean("invalid")){label(snapshots,t("快照损坏，已禁止恢复：","Snapshot is damaged and cannot be restored: ")+record.optString("slot"));continue;}
            String label=(record.optString("slot").startsWith("healthy")?t("健康启动","Healthy start"):t("修复之前","Before repair"))+" · "+date(record.optLong("created"));
            button(snapshots,label,()->{
                JSONObject request=request("restore");try{request.put("slot",record.getString("slot")).put("id",record.getString("id"));}catch(JSONException ignored){}
                confirm(t("恢复所选配置快照","Restore selected configuration snapshot"),label+"\n"+record.optJSONArray("files")+t("\n只恢复这些配置文件；已卸载的插件不会自动重新安装。","\nRestore only these configuration files. Removed plugins are not automatically reinstalled."),()->repair(request));
            },true);
        }

        JSONArray items=value.optJSONArray("items");int count=0;
        if(items!=null)for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null||!item.optBoolean("deletable"))continue;
            String name=item.optString("name");if(BuiltinPlugins.internal(name))continue;count++;
            button(plugins,name+" · "+t("卸载","Remove"),()->confirm(t("卸载插件","Remove plugin"),name+t("\n仅卸载所选插件，使用现有插件管理器核验结果。","\nRemove only the selected plugin and verify the result with the plugin manager."),()->{
                if(!BackupTask.get(this).removeStartupPlugin(name))status(readable("RECOVERY_BUSY"));
            }),true);
        }
        layout.pluginCard.setVisibility(count>0||!value.optString("pluginError").isEmpty()?View.VISIBLE:View.GONE);
        if(!value.optString("pluginError").isEmpty())label(plugins,t("插件清单暂不可读；可先恢复或新建配置。\n","Cannot read the plugin list. Restore or create configuration first.\n")+readable(value.optString("pluginError")));
    }
    private String date(long time){return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(time));}
    private String readable(String value){
        if(value.contains("RECOVERY_PENDING"))return t("配置修复未完成，请使用“恢复中断的配置修复”。","Configuration repair is incomplete. Select Recover interrupted configuration repair.");
        if(value.contains("RECOVERY_BUSY"))return t("环境任务正在执行，完成后刷新即可。","An environment task is running. Refresh when it finishes.");
        if(value.contains("RECOVERY_LINK"))return t("配置文件使用软链接，已保留原文件。请手动检查链接目标。","A configuration file uses a symbolic link. The original was retained. Check the link target manually.");
        if(value.contains("RECOVERY_CHECKSUM")||value.contains("RECOVERY_FORMAT"))return t("配置快照校验失败，未应用修复。","Configuration snapshot validation failed. The repair was not applied.");
        if(value.contains("RECOVERY_CHANGED"))return t("配置或快照已变化，请刷新后重新选择。","The configuration or snapshot changed. Refresh and select it again.");
        if(value.contains("RECOVERY_SIZE"))return t("配置文件超过快照大小限制或不是普通文件，原文件保留。","A configuration file exceeds the snapshot size limit or is not a regular file. The original was retained.");
        return StartupText.render(value);
    }
    private final Runnable refresh=new Runnable(){@Override public void run(){
        if(isFinishing())return;
        BackupTask task=BackupTask.get(StartupRecoveryActivity.this);BackupTaskState.Snapshot state=task.snapshot();boolean busy=task.busy();
        String key=state.id+":"+state.status+":"+state.detail;
        if(!key.equals(previousTask)){previousTask=key;if(state.id>0)status(readable(state.detail));}
        boolean maintenance=task.maintenanceBusy();
        if(wasBusy&&!maintenance)model.load();wasBusy=maintenance;
        for(View action:actions)StartupRecoveryLayout.enabled(action,!busy&&controller.isEnvironmentReady());
        ui.removeCallbacks(this);ui.postDelayed(this,700);
    }};
    @Override protected void onResume(){super.onResume();ui.post(refresh);}
    @Override protected void onPause(){ui.removeCallbacks(refresh);super.onPause();}
}
