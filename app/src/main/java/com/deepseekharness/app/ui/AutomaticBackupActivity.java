package com.deepseekharness.app.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.UiText;

/** 自动计划、本机副本与原有恢复预检共用同一条数据路径。 */
public final class AutomaticBackupActivity extends AppCompatActivity {
    private CardPage page;private TextView state;private LinearLayout copies;private boolean reading;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    static String stage(String value){return switch(value){
        case "PREPARING" -> t("准备数据", "Preparing data");
        case "CAPTURING" -> t("保存并核验文件", "Saving and verifying files");
        case "ENCRYPTING" -> t("加密并验证副本", "Encrypting and verifying the copy");
        case "EXPORTING" -> t("完成副本记录", "Finishing the copy record");
        default -> t("正在处理", "Working");
    };}
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);render();
        NativeBackupJobs.get(this).changes().observe(this,value->{if(state==null)return;
            if(value.busy)state.setText(t("备份任务进行中：","Backup in progress: ")+stage(value.stage)+" · "+value.entries+t(" 项"," entries"));
            else refresh();
        });
    }
    private void render(){
        page=new CardPage(this,t("自动备份","Automatic backups"),t("默认开启。备份对话、附件、配置和插件；不重复备份 Ubuntu。个人项目请使用手动备份。","On by default. Saves conversations, attachments, settings and plugins, without copying Ubuntu. Use manual backup for personal projects."));
        UiNavigation.addHeader(this,page.root,t("自动备份","Automatic backups"),this::finish);
        var prefs=AutomaticBackups.prefs(this);LinearLayout settings=page.card();
        var enabled=new com.google.android.material.materialswitch.MaterialSwitch(this);enabled.setText(t("自动备份","Automatic backups"));enabled.setChecked(AutomaticBackups.enabled(this));settings.addView(enabled);
        enabled.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("enabled",value).commit();AutomaticBackups.schedule(this);refresh();});
        String mode=prefs.getString("mode","daily");String[] names={t("每天指定时间","Daily at a chosen time"),t("每隔一段时间","At an interval"),t("停止 DSH 后","After stopping DSH")};
        page.entry(settings,t("备份时机","Backup timing"),names["interval".equals(mode)?1:"stop".equals(mode)?2:0],R.drawable.ic_ui2_box,()->CardSheet.choices(this,t("备份时机","Backup timing"),()->java.util.Arrays.asList(names),index->{prefs.edit().putString("mode",new String[]{"daily","interval","stop"}[index]).putLong("anchor",System.currentTimeMillis()).commit();render();}));
        if("daily".equals(mode)){
            int minute=prefs.getInt("minute",180);page.entry(settings,t("每天时间","Daily time"),String.format(java.util.Locale.ROOT,"%02d:%02d",minute/60,minute%60),R.drawable.ic_ui2_box,()->new TimePickerDialog(this,(picker,h,m)->{prefs.edit().putInt("minute",h*60+m).commit();render();},minute/60,minute%60,true).show());
        }else if("interval".equals(mode)){
            page.entry(settings,t("间隔小时","Interval in hours"),String.valueOf(prefs.getInt("hours",24)),R.drawable.ic_ui2_box,()->{
                EditText hours=new EditText(this);hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);hours.setText(String.valueOf(prefs.getInt("hours",24)));
                var dialog=new DshaDialogBuilder(this).setTitle(t("间隔 1–168 小时","Interval: 1–168 hours")).setView(hours).setPositiveButton(t("保存","Save"),null).setNegativeButton(t("取消","Cancel"),null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int value=Integer.parseInt(hours.getText().toString());if(value<1||value>168)throw new NumberFormatException();prefs.edit().putInt("hours",value).commit();dialog.dismiss();render();}catch(NumberFormatException invalid){hours.setError(t("请输入 1–168","Enter 1–168"));}}));dialog.show();
            });
        }
        settings.addView(page.text(t("到期时仍在运行则延后，不会停止对话或终端。时间由系统调度，可能略有延迟。保留最近 3 份已验证自动副本。关闭后取消后续任务，已有副本保留。","If DSH or a terminal is running, backup waits. Android scheduling may delay the selected time. Keeps the latest 3 verified automatic copies. Turning off cancels future jobs and preserves existing copies."),12,R.color.text_secondary));
        TextView localHint=page.text(t("自动副本保存在本机，卸载应用会清除。换机或卸载前，请导出副本并保存解密密码。", "Automatic copies are stored on this device and removed when the app is uninstalled. Export a copy and save its decryption password before moving or uninstalling."),11,R.color.text_muted);localHint.setPadding(0,page.dp(10),0,0);settings.addView(localHint);
        state=page.text("",12,R.color.text_secondary);page.content.addView(state);
        copies=page.card();page.button(page.footer,t("现在创建自动备份","Create automatic backup now"),true,()->{if(NativeBackupJobs.get(this).state().busy){state.setText(t("备份任务进行中，请等待完成。", "A backup task is running. Wait for it to finish."));return;}if(!AutomaticBackups.enabled(this)){state.setText(t("请先打开自动备份","Enable automatic backups first"));return;}AutomaticBackups.request(this,true);state.setText(t("已安排：DSH 和终端停止后可执行，稍后刷新查看结果。","Scheduled. DSH and terminals must be stopped. Refresh shortly to view the result."));});page.button(page.footer,t("刷新备份记录","Refresh backup records"),false,this::refresh);setContentView(page.root);refresh();
    }
    private void refresh(){
        if(reading)return;reading=true;state.setText(t("正在读取记录…","Reading records…"));
        new Thread(()->{try{
            var found=NativeBackupJobs.get(this).verifiedCopies();var prefs=AutomaticBackups.prefs(this);long last=prefs.getLong("last",0);String error=prefs.getString("error","");
            runOnUiThread(()->{reading=false;if(isFinishing()||isDestroyed())return;copies.removeAllViews();
                String status=AutomaticBackups.enabled(this)?t("已开启 · 等待到期且 DSH 停止","On · waiting for the scheduled time and DSH to stop"):t("已关闭","Off");
                if(last>0)status+="\n"+t("上次完成：","Last completed: ")+android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",last);
                if(!error.isEmpty())status+="\n"+t("待处理：","Needs attention: ")+error;var job=NativeBackupJobs.get(this).state();if(job.busy)status=t("备份任务进行中：","Backup in progress: ")+stage(job.stage)+" · "+job.entries+t(" 项"," entries");
                if("DATA_SAVED_PLUGIN_WARNINGS".equals(job.result))status+="\n"+t("数据已保存；部分插件依赖需在恢复预检中核对。","Data saved; some plugin dependencies need review before restore.");state.setText(status);
                for(var copy:found.valid)if(Boolean.TRUE.equals(copy.metadata.get("automatic"))){
                    String title=android.text.format.DateFormat.format("MM-dd HH:mm",copy.created).toString();
                    page.entry(copies,title,com.deepseekharness.app.util.Fmt.bytes(copy.bytes)+("DATA_SAVED_PLUGIN_WARNINGS".equals(copy.result(true))?t(" · 插件依赖待核对"," · review plugin dependencies"):""),R.drawable.ic_recovery_document,()->CardSheet.choices(this,title,()->java.util.List.of(t("预检恢复","Inspect for restore"),t("导出加密副本","Export encrypted copy")),action->startActivity(new Intent(this,NativeDataActivity.class).putExtra(action==0?"auto_restore_id":"auto_export_id",copy.id))));
                }
                if(copies.getChildCount()==0)copies.addView(page.text(t("还没有自动备份。到达所选时间后，在 DSH 停止且没有终端任务时创建第一份。","No automatic backup yet. The first copy is created when due, with DSH and terminals stopped."),12,R.color.text_muted));
            });
        }catch(Exception error){runOnUiThread(()->{reading=false;state.setText(NativeBackupJobs.code(error));});}},"auto-backup-records").start();
    }
    @Override protected void onResume(){super.onResume();if(state!=null)refresh();}
}
