package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 可识别原件的只读管理；批量检查逐项报告，不提供自动或批量删除。 */
public final class RetainedDataActivity extends AppCompatActivity {
    public static final class Model extends ViewModel {
        final Set<String> selected=new HashSet<>();final MutableLiveData<List<RetainedCatalogue.Entry>> entries=new MutableLiveData<>();
        final MutableLiveData<String> report=new MutableLiveData<>("");final AtomicBoolean working=new AtomicBoolean();BackupControl control;int page;boolean pluginAction;
        @Override protected void onCleared(){if(control!=null)control.cancel();}
    }
    private Model model;private LinearLayout rows;private TextView report;
    private com.deepseekharness.app.core.PluginRepository plugins;private androidx.appcompat.app.AlertDialog pluginDialog;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    private RetainedCatalogue catalogue()throws IOException{var fs=new AndroidBackupFileSystem();File files=getFilesDir().getCanonicalFile();return new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);model=new ViewModelProvider(this).get(Model.class);ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(getColor(R.color.surface));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*getResources().getDisplayMetrics().density);body.setPadding(p,p,p,p);scroll.addView(body);setContentView(scroll);
        plugins=new ViewModelProvider(this).get(com.deepseekharness.app.core.PluginRepository.class);
        button(body,t("返回","Back"),this::finish);TextView title=new TextView(this);title.setText(t("保留副本与旧树","Retained copies and old trees"));title.setTextSize(22);title.setTextColor(getColor(R.color.text));title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(0,p,0,p/2);body.addView(title);
        TextView note=new TextView(this);note.setText(t("原件保持只读。记录中的成功状态不是本次重新验证；检查、导出与恢复分别处理。未知或受损原件继续保留。",
                "Originals remain read-only. A recorded success is not a fresh verification. Inspect, export and restore are separate operations. Unknown or damaged originals remain retained."));note.setTextSize(14);note.setTextColor(getColor(R.color.text_secondary));note.setPadding(0,0,0,p/2);body.addView(note);
        button(body,t("刷新清单","Refresh list"),this::refresh);button(body,t("检查所选记录","Inspect selected records"),this::inspect);
        button(body,t("取消检查","Cancel inspection"),()->{if(model.control!=null)model.control.cancel();});report=new TextView(this);body.addView(report);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);body.addView(rows);
        model.report.observe(this,value->{StringJoiner lines=new StringJoiner("\n");for(String line:value.split("\n",-1))lines.add(UiStateText.render(line));report.setText(lines.toString());});model.entries.observe(this,this::render);if(model.entries.getValue()==null)refresh();
        plugins.state().observe(this,state->{if(model.pluginAction&&state!=null&&state.message!=null&&!state.message.isEmpty())model.report.setValue(UiStateText.render(state.message));});
        plugins.preview().observe(this,preview->{if(preview==null||pluginDialog!=null)return;
            pluginDialog=new DshaDialogBuilder(this).setTitle(t("审阅隔离插件","Review quarantined plugin")).setMessage(preview.description())
                    .setPositiveButton(t("确认启用","Confirm enable"),(d,w)->plugins.confirmPreview())
                    .setNegativeButton(t("取消","Cancel"),(d,w)->plugins.discardPreview()).create();pluginDialog.setOnDismissListener(d->pluginDialog=null);pluginDialog.show();if(preview.blocked())pluginDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        });
    }
    private void button(LinearLayout parent,String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setIncludeFontPadding(false);button.setGravity(android.view.Gravity.CENTER);button.setMinHeight((int)(48*getResources().getDisplayMetrics().density));button.setBackgroundResource(R.drawable.bg_btn);button.setTextColor(getColor(R.color.text));LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=(int)(8*getResources().getDisplayMetrics().density);parent.addView(button,layout);button.setOnClickListener(v->action.run());}
    private void refresh(){if(!model.working.compareAndSet(false,true))return;new Thread(()->{try{model.entries.postValue(catalogue().list());}catch(Exception error){model.report.postValue(UiText.text("保留记录无法读取，原件未改动。")+"\n"+BackupErrorCode.from(error));}finally{model.working.set(false);}},"retained-list").start();}
    private String label(RetainedCatalogue.Entry entry){
        String kind=switch(entry.kind){case BACKUP->t("加密副本","Encrypted copy");case ENVIRONMENT->t("旧环境数据","Old environment data");case RUNTIME->t("运行时原件","Runtime original");case RESTORE->t("恢复原件","Restore original");case PLUGIN->t("插件原件","Plugin original");case QUARANTINE->t("隔离插件","Quarantined plugin");case SETTINGS->t("待恢复 profile 设置","Profile settings to restore");case PRESET->t("旧 Agent 预设候选","Legacy Agent preset candidate");case MIGRATION->t("rc1 迁移记录","rc1 migration records");};
        String state=switch(entry.status){case "COMMITTED"->t("原操作已提交","Original operation committed");case "PENDING_RETRY"->t("迁移待重试或待处理","Migration pending retry or review");case "ROLLED_BACK"->t("原操作已回切","Original operation rolled back");case "RECOVERY_REQUIRED"->t("需要恢复中断操作","Interrupted operation needs recovery");case "RECORDED_VERIFIED_COPY"->t("已记录验证，操作前复核","Previously verified; recheck before use");case "UNREADABLE", "UNRECOGNIZED"->t("来源或记录未确认","Source or record unconfirmed");default->t("原件保留","Original retained");};
        return kind+" · "+entry.id.substring(0,Math.min(8,entry.id.length()))+" · "+entry.displayName+"\n"+state+" · "+t("不自动删除","No automatic deletion");
    }
    private void render(List<RetainedCatalogue.Entry> entries){rows.removeAllViews();if(entries.isEmpty()){TextView empty=new TextView(this);empty.setText(t("暂无保留副本。","No retained copies."));rows.addView(empty);return;}
        model.page=Math.min(model.page,(entries.size()-1)/50);int first=model.page*50,last=Math.min(entries.size(),first+50);
        for(var entry:entries.subList(first,last)){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackgroundResource(R.drawable.bg_polished_card);int pad=(int)(12*getResources().getDisplayMetrics().density);card.setPadding(pad,pad,pad,pad);CheckBox selected=new CheckBox(this);selected.setText(label(entry));selected.setIncludeFontPadding(false);selected.setTextSize(16);selected.setMinHeight((int)(48*getResources().getDisplayMetrics().density));selected.setChecked(model.selected.contains(entry.key()));selected.setOnCheckedChangeListener((v,on)->{if(on)model.selected.add(entry.key());else model.selected.remove(entry.key());});card.addView(selected);button(card,t("查看与操作","Details and actions"),()->details(entry));LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.bottomMargin=pad;rows.addView(card,layout);}
        if(first>0)button(rows,t("上一页","Previous page"),()->{model.page--;render(entries);});
        if(last<entries.size())button(rows,t("下一页","Next page"),()->{model.page++;render(entries);});
    }
    private void details(RetainedCatalogue.Entry entry){
        String scope=switch(entry.scope){case "application"->t("应用数据","Application data");case "sessions"->t("对话与附件","Conversations and attachments");case "settings"->t("设置","Settings");case "plugins"->t("插件","Plugins");case "projects"->t("项目文件","Project files");default->t("范围未确认","Scope unconfirmed");};
        String protection=switch(entry.protection){case "VERIFY_BEFORE_EXPORT_OR_RESTORE"->t("操作前重新核对加密文件摘要","Recheck the encrypted file checksum before use");case "REVIEW_REQUIRED"->t("启用前需要审阅","Review required before activation");case "READ_ONLY_RESCUE_NO_AUTOMATIC_DELETION"->t("只读救援来源","Read-only rescue source");default->t("原件或记录保持保留","Originals or records remain retained");};
        String detail=label(entry)+"\n\n"+t("范围：","Scope: ")+scope+"\n"+protection+"\n\n"+entry.directory.getAbsolutePath()+"\n\n"+t("不会执行保留目录中的程序，也不会自动删除原件。","Programs in the retained directory will not run, and originals will not be deleted automatically.");
        var dialog=new DshaDialogBuilder(this).setTitle(t("保留记录","Retained record")).setMessage(detail).setNegativeButton(t("关闭","Close"),null);
        if(entry.source!=null){dialog.setNeutralButton(t("导出","Export"),(d,w)->open(entry,entry.part.equals("encrypted")?"reexport":"export-tree"));
            if(entry.kind==RetainedCatalogue.Kind.SETTINGS)dialog.setPositiveButton(t("检查设置差异","Review settings differences"),(d,w)->reviewSettings(entry));
            else if(entry.kind==RetainedCatalogue.Kind.PRESET)dialog.setPositiveButton(t("检查并审阅预设","Inspect and review preset"),(d,w)->{model.pluginAction=true;plugins.reviewLegacyPreset(entry.key());});
            else if(entry.kind==RetainedCatalogue.Kind.MIGRATION) { /* migration records are read-only/export-only */ }
            else if(entry.kind==RetainedCatalogue.Kind.QUARANTINE)dialog.setPositiveButton(t("审阅启用","Review activation"),(d,w)->{model.pluginAction=true;plugins.reviewRestored(entry.id,entry.part);});
            else dialog.setPositiveButton(t("预检恢复","Inspect restore"),(d,w)->open(entry,entry.part.equals("encrypted")?"restore-copy":"restore-tree"));}dialog.show();
    }
    private void open(RetainedCatalogue.Entry entry,String action){startActivity(new Intent(this,NativeDataActivity.class).putExtra("retained_key",entry.key()).putExtra("retained_action",action));}
    private void reviewSettings(RetainedCatalogue.Entry entry){
        new DshaDialogBuilder(this).setTitle(t("检查 profile 设置","Inspect profile settings"))
                .setMessage(t("将停止 Web、终端和写任务，在独立服务中读取当前 schema 并验证候选；活动设置在确认差异前保持原位。可执行配置和插件构成继续隔离。", "Web, terminals and writers will stop. An isolated service will validate the candidate against the current schema. Active settings remain unchanged until you confirm the differences; executable configuration and plugin composition stay quarantined."))
                .setPositiveButton(t("开始检查","Inspect"),(d,w)->settingsWork(()->ProfileSettingsTransaction.preview(this,entry.key(),model.control),true))
                .setNegativeButton(t("取消","Cancel"),null).show();
    }
    private interface SettingsWork {Map<String,Object> run()throws Exception;}
    private void settingsWork(SettingsWork work,boolean preview){
        if(!model.working.compareAndSet(false,true))return;model.control=new BackupControl(null);
        model.report.setValue(t("正在隔离验证设置；可使用取消检查。","Validating settings in isolation; Cancel inspection is available."));
        new Thread(()->{try{Map<String,Object> result=work.run();runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            if(preview)settingsDifferences(result);else model.report.setValue(t("所选设置已事务提交并由实际设置服务读回确认。其它配置和原件保留；可重新进入 Web。","Selected settings were committed and read back by the actual settings service. Other configuration and originals remain; you can reopen Web."));
        });}catch(Exception error){model.report.postValue(t("设置操作未完成，原件保留。可重新检查后继续。\n","Settings operation incomplete; originals retained. Inspect again to continue.\n")+BackupErrorCode.from(error));}finally{model.working.set(false);}},"profile-settings-review").start();
    }
    @SuppressWarnings("unchecked") private void settingsDifferences(Map<String,Object> result){
        List<Map<String,Object>> items=(List<Map<String,Object>>)result.get("items");
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);List<CheckBox> selected=new ArrayList<>();
        TextView note=new TextView(this);note.setText(t("Profile：","Profile: ")+result.get("profile")+"\n"+t("请选择需要恢复的字段；未选字段保持当前值。","Select fields to restore. Unselected fields retain their current values.")+"\n"+String.valueOf(result.get("warnings")));content.addView(note);
        for(var item:items){CheckBox box=new CheckBox(this);box.setChecked(false);box.setText(item.get("id")+"\n"+item.get("before")+"\n→ "+item.get("after"));content.addView(box);selected.add(box);}
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        var dialog=new DshaDialogBuilder(this).setTitle(t("已验证的设置差异","Validated settings differences")).setView(scroll).setNegativeButton(t("保留待处理","Keep pending"),null)
                .setPositiveButton(t("应用所选并读回","Apply selection and read back"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            Set<String> keys=new LinkedHashSet<>();for(int i=0;i<items.size();i++)if(selected.get(i).isChecked())keys.add((String)items.get(i).get("id"));
            if(keys.isEmpty())return;dialog.dismiss();settingsWork(()->ProfileSettingsTransaction.apply(this,(String)result.get("operation"),keys,model.control),false);
        }));dialog.show();
    }
    private void inspect(){
        Set<String> selected=new LinkedHashSet<>(model.selected);if(selected.isEmpty()||!model.working.compareAndSet(false,true))return;model.control=new BackupControl(null);BackupControl control=model.control;model.report.setValue(t("正在检查所选原件…","Inspecting selected originals…"));
        new Thread(()->{StringBuilder results=new StringBuilder();int passed=0,failed=0;try(com.deepseekharness.app.core.RuntimeTasks lease=com.deepseekharness.app.core.RuntimeTasks.begin()){
            RetainedCatalogue catalog=catalogue();var fs=new AndroidBackupFileSystem();
            for(String key:selected){
                if(control.isCancelled()){results.append(UiText.text("检查已取消，尚未检查的条目保持原状。"));break;}
                try{var entry=catalog.resolve(key);
                    if(entry.part.equals("encrypted"))VerifiedBackupCopy.inspect(fs,entry.directory.getParentFile(),entry.id).verify(fs,control);
                    else if(entry.source!=null){for(BackupSource source:catalog.sources(entry))source.walk(item->{
                        if(item.kind.equals("MISSING")||item.kind.equals("UNREADABLE"))throw new IOException("RETAINED_SOURCE_PARTIAL");
                        if(item.kind.equals("FILE")){String hash;try(InputStream input=source.open(item)){hash=BackupArchive.digest(input,control);}source.verify(item,hash,control);}
                    },control);}
                    else throw new IOException("RECORD_ONLY_NOT_A_VERIFIED_COPY");
                    passed++;results.append(key).append(" · ").append(entry.part.equals("encrypted")?t("摘要与验证记录一致","Checksum matches the verification record"):t("本次读取一致；没有据此确认历史格式或完整性","Reads are consistent; historical format/integrity remains unconfirmed")).append('\n');
                }catch(Exception error){failed++;results.append(key).append(" · ").append(BackupErrorCode.from(error)).append('\n');}
                model.report.postValue(results.toString());
            }
            if(results.length()>0&&results.charAt(results.length()-1)!='\n')results.append('\n');
            results.append(UiStateText.render("检查通过："+passed+"；未通过或未完整读取："+failed));
        }catch(Exception error){results.append(BackupErrorCode.from(error));}finally{model.report.postValue(results.toString());model.working.set(false);}},"retained-inspection").start();
    }
}
