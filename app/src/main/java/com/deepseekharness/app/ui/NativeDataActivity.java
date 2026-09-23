package com.deepseekharness.app.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.*;

/** 原生数据入口不等待 Ubuntu 或网页；界面只传递参数并观察应用级作业。 */
public final class NativeDataActivity extends AppCompatActivity {
    public static final class Pending extends ViewModel {
        char[] password;NativeDataLocations.Selection selection;String filename;boolean rescue;
        int scopeIndex;boolean includeKey;String reexportId;
        final List<BackupSource> projects=new ArrayList<>();
        final androidx.lifecycle.MutableLiveData<BackupPreview.Report> preview=new androidx.lifecycle.MutableLiveData<>();
        final androidx.lifecycle.MutableLiveData<String> previewError=new androidx.lifecycle.MutableLiveData<>();
        volatile boolean previewBusy;BackupControl previewControl;
        void clear(){if(password!=null)Arrays.fill(password,'\0');password=null;}
        @Override protected void onCleared(){clear();if(previewControl!=null)previewControl.cancel();}
    }
    private Pending pending;
    private NativeBackupJobs jobs;
    private LinearLayout page,body;
    private TextView status,project;
    private Spinner scope;
    private Button cancelOperation;
    private CheckBox key;
    private List<BackupSource> projects;
    private androidx.appcompat.app.AlertDialog previewDialog;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    private final ActivityResultLauncher<String> destination=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),uri->{
        if(pending.reexportId!=null){String source=pending.reexportId;pending.reexportId=null;if(uri!=null&&!jobs.reexport(source,uri,pending.filename))status.setText(t("副本无法重新导出，请检查记录或当前作业。", "The copy cannot be exported. Review its record or the current operation."));return;}
        if(uri==null){pending.clear();return;}
        if(pending.password==null){status.setText(t("密码没有保存在设备中，请重新输入后导出。","The password was not saved. Enter it again to export."));return;}
        if(!jobs.export(pending.selection,pending.password,uri,pending.filename,pending.rescue))status.setText(t("已有任务或无法创建私有作业，请检查状态。","A task is already running or private storage is unavailable."));
        pending.clear();
    });
    private final ActivityResultLauncher<Uri> projectPicker=registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),uri->{
        if(uri==null)return;try{
            getContentResolver().takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            projects.add(new SafBackupSource(this,uri,"project-"+NativeDataLocations.hash(uri.toString()),"projects",""));
            project.setText(t("已选择项目目录：","Selected project folders: ")+projects.size());
        }catch(Exception error){status.setText(t("无法读取所选目录，请重新授权。","Cannot read the folder. Grant access again."));}
    });
    private final ActivityResultLauncher<String[]> restorePicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)restorePassword(uri);});
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);pending=new ViewModelProvider(this).get(Pending.class);projects=pending.projects;jobs=NativeBackupJobs.get(this);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(getColor(R.color.surface));page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);int pad=dp(20);page.setPadding(pad,dp(12),pad,pad);body=page;scroll.addView(page);setContentView(scroll);
        String mode=getIntent().getStringExtra("data_mode");boolean exportOnly="export".equals(mode),restoreOnly="restore".equals(mode),backupMode="backup".equals(mode);
        LinearLayout navigation=new LinearLayout(this);navigation.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var back=UiNavigation.back(this,this::finish);navigation.addView(back,new LinearLayout.LayoutParams(dp(44),dp(48)));
        TextView brand=new TextView(this);brand.setText("DSHA");brand.setTextSize(18);brand.setTextColor(getColor(R.color.text));navigation.addView(brand);page.addView(navigation);
        TextView title=text(backupMode?t("备份与恢复","Backup and restore"):exportOnly?t("加密导出","Encrypted export"):restoreOnly?t("从备份恢复","Restore a backup"):t("应用数据与救援","Application data and recovery"),24);title.setTypeface(null,android.graphics.Typeface.BOLD);
        text(backupMode?t("导出、导入和自动备份集中在这里。","Export, import and automatic backups in one place."):exportOnly?t("选择范围和项目后，设置密码保存加密副本。","Choose data and projects, then protect the export with a password."):restoreOnly?t("先验证备份密码与内容，再确认恢复范围。","Verify the password and contents before confirming what to restore."):t("运行环境不可用时，也能检查并保护可读取的数据。","Inspect and protect readable data even when the runtime is unavailable."),13);
        status=text("",14);status.setTextIsSelectable(true);status.setBackgroundResource(R.drawable.bg_card);status.setPadding(dp(16),dp(12),dp(16),dp(12));
        section(t("选择数据范围","Choose data scope"));
        if(restoreOnly)body.setVisibility(View.GONE);
        scope=choice(new String[]{t("应用数据","Application data"),t("对话与附件","Conversations and attachments"),t("设置","Settings"),t("插件","Plugins"),t("所选项目文件","Selected project files")});body.addView(scope,new LinearLayout.LayoutParams(-1,-2));
        scope.setPrompt(t("数据范围","Data scope"));scope.setSelection(pending.scopeIndex);scope.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int index,long id){pending.scopeIndex=index;}});
        key=new CheckBox(this);key.setText(t("包含原生 API Key（默认不包含）","Include native API key (excluded by default)"));key.setIncludeFontPadding(false);key.setGravity(android.view.Gravity.CENTER_VERTICAL);key.setMinimumHeight(dp(48));key.setChecked(false);body.addView(key);
        key.setChecked(pending.includeKey);key.setOnCheckedChangeListener((button,checked)->pending.includeKey=checked);
        project=text(t("项目文件需单独选择目录；不会扫描整部手机。","Choose project folders explicitly; the app does not scan the whole device."),14);
        button(t("选择项目目录","Choose project folder"),()->projectPicker.launch(null));
        button(t("查看数据范围与位置","Review data scope and locations"),this::locations);
        section(exportOnly?t("设置备份密码","Protect your backup"):restoreOnly?t("选择并验证","Select and verify"):t("导出与恢复","Export and restore"));
        if(!restoreOnly){Button export=button(t("导出应用数据","Export application data"),()->password(false));export.setBackgroundResource(R.drawable.bg_btn_primary);export.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this,R.color.button_primary_text));}
        if(!exportOnly&&!restoreOnly&&!backupMode)button(t("只读救援导出","Read-only rescue export"),()->password(true));
        if(!exportOnly){Button restore=button(t("导入备份","Import backup"),()->restorePicker.launch(new String[]{"application/octet-stream","application/gzip","*/*"}));if(restoreOnly){restore.setBackgroundResource(R.drawable.bg_btn_primary);restore.setTextColor(getColorStateList(R.color.button_primary_text));}}
        if(!exportOnly&&!restoreOnly&&!backupMode)button(t("重新导出已验证副本","Export a verified copy again"),this::verifiedCopies);
        if(backupMode)button(t("自动备份与记录","Automatic backups and history"),()->startActivity(new android.content.Intent(this,AutomaticBackupActivity.class)));
        cancelOperation=button(t("取消当前作业","Cancel current operation"),()->jobs.cancel());cancelOperation.setVisibility(View.GONE);
        section(t("恢复与维护","Recovery and maintenance"));if(exportOnly||restoreOnly||backupMode)body.setVisibility(View.GONE);
        button(t("选择已有数据目录","Choose existing data directory"),this::chooseDataHome);
        button(t("恢复中断的数据提交","Recover interrupted data commit"),()->{
            new DshaDialogBuilder(this).setTitle(t("恢复中断的数据提交","Recover interrupted data commit"))
                    .setMessage(t("将先停止写任务，再根据宿主日志恢复一致状态。无需旧 Python 或备份密码，原件将保留。",
                            "Stop writers first, then restore consistency from the host journal. The old Python and backup password are not required. Originals are retained."))
                    .setPositiveButton(t("继续","Continue"),(d,w)->{if(!jobs.recoverPending())status.setText(t("没有可自动处理的提交，或需要先检查损坏/冲突的日志。","No automatically recoverable commit, or damaged/conflicting records need review."));})
                    .setNegativeButton(t("取消","Cancel"),null).show();
        });
        button(t("回退兼容运行时","Roll back compatible runtime"),()->RuntimeRecoveryUi.show(this));
        jobs.changes().observe(this,this::render);
        pending.preview.observe(this,value->{if(value!=null)showScope(value);});pending.previewError.observe(this,value->{if(value!=null&&!value.isEmpty())status.setText(errorText(value));});
        if(saved==null&&(getIntent().hasExtra("auto_restore_id")||getIntent().hasExtra("auto_export_id")))page.post(this::automaticCopy);
        if(saved==null&&getIntent().hasExtra("retained_key"))page.post(this::retainedAction);
        if(saved==null&&getIntent().hasExtra("restore_uri")){
            Uri input=Uri.parse(getIntent().getStringExtra("restore_uri"));if("content".equals(input.getScheme()))new android.os.Handler(android.os.Looper.getMainLooper()).post(()->restorePassword(input));
        }
    }
    private void automaticCopy(){
        boolean restore=getIntent().hasExtra("auto_restore_id");String id=getIntent().getStringExtra(restore?"auto_restore_id":"auto_export_id");
        new Thread(()->{char[] secret=null;try{
            var copy=VerifiedBackupCopy.inspect(new AndroidBackupFileSystem(),new java.io.File(getFilesDir().getCanonicalFile(),"host-backup-operations"),id);
            if(!Boolean.TRUE.equals(copy.metadata.get("automatic")))throw new java.io.IOException("NOT_AUTOMATIC_COPY");
            secret=AutomaticBackups.password(this);
            if(restore){if(!jobs.prepareRestoreCopy(id,secret,Set.of("application"),false,"PRIVATE"))throw new java.io.IOException("RESTORE_BUSY");}
            else{String password=new String(secret);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;
                CardPage note=new CardPage(this,t("导出自动备份","Export automatic backup"),t("这是此备份的解密密码。请另行保存，换机或卸载后仍需它恢复。","Save this decryption password separately. It is required after reinstalling or moving to another device."));TextView value=note.text(password,13,R.color.text);value.setTextIsSelectable(true);note.content.addView(value);var dialog=CardSheet.create(this,note);
                note.button(note.footer,t("复制密码并选择保存位置","Copy password and choose destination"),true,()->{
                    android.content.ClipData clip=android.content.ClipData.newPlainText("DSHA backup password",password);
                    if(android.os.Build.VERSION.SDK_INT>=24){android.os.PersistableBundle sensitive=new android.os.PersistableBundle();sensitive.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(sensitive);}
                    ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
                    pending.reexportId=id;pending.filename="DSHA-data-v5-"+UUID.randomUUID()+".dshbak";dialog.dismiss();destination.launch(pending.filename);
                });CardSheet.show(dialog,this);
            });}
        }catch(Exception error){runOnUiThread(()->status.setText(errorText(BackupErrorCode.from(error))));}finally{if(secret!=null)Arrays.fill(secret,'\0');}},"automatic-backup-copy").start();
    }
    private void chooseDataHome(){
        String[] names={t("原容器数据目录","Original container data directory"),t("宿主持久数据目录","Persistent host data directory")};
        new DshaDialogBuilder(this).setTitle(t("选择要使用的数据目录","Choose the data directory to use")).setItems(names,(d,index)->{
            new DshaDialogBuilder(this).setTitle(names[index]).setMessage(t("会先停止 Web 和终端，再选择已有目录。两份数据都保留，不合并或清空。所选目录必须存在且可读取。", "Stop Web and terminals, then select an existing directory. Both data sets remain; neither is merged or cleared. The chosen directory must exist and be readable."))
                    .setPositiveButton(t("继续","Continue"),(dialog,which)->com.deepseekharness.app.core.BackupTask.get(this).selectDataHome(index==0?UserDataLayout.Home.LEGACY:UserDataLayout.Home.STABLE))
                    .setNegativeButton(t("取消","Cancel"),null).show();
        }).setNegativeButton(t("取消","Cancel"),null).show();
    }
    private void verifiedCopies(){
        status.setText(t("正在读取私有副本记录…", "Reading private copy records…"));
        new Thread(()->{try{
            NativeBackupJobs.Copies found=jobs.verifiedCopies();List<VerifiedBackupCopy> copies=found.valid;runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                String unavailable=found.unreadable.isEmpty()?"":found.unreadable.size()+t(" 份记录暂不可用，原件已保留；其余副本仍可选择。", " records are unavailable and retained; other copies can still be selected.");
                if(copies.isEmpty()){status.setText(t("还没有可重新导出的私有验证副本。", "No private verified copies are available yet.")+"\n"+unavailable);return;}
                if(!unavailable.isEmpty())status.setText(unavailable);
                String[] labels=new String[copies.size()];for(int i=0;i<labels.length;i++){var copy=copies.get(i);labels[i]=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(copy.created))+" · "+com.deepseekharness.app.util.Fmt.bytes(copy.bytes)+" · "+copy.id.substring(0,8);}
                new DshaDialogBuilder(this).setTitle(t("选择已验证的加密副本", "Choose a verified encrypted copy")).setItems(labels,(d,index)->{
                    new DshaDialogBuilder(this).setTitle(t("重新导出副本", "Export the copy again"))
                            .setMessage(t("将先重新核对私有文件的摘要，再复制到新建目标。仍使用该副本原来的备份密码；不会重新读取工作目录，也不会覆盖已有备份。", "Recheck the private file checksum, then copy it to a newly created destination. It keeps its original backup password. The workspace is not read again and existing backups are preserved."))
                            .setPositiveButton(t("选择保存位置", "Choose destination"),(dialog,which)->{pending.reexportId=copies.get(index).id;pending.filename="DSHA-data-v5-"+UUID.randomUUID()+".dshbak";destination.launch(pending.filename);})
                            .setNegativeButton(t("取消","Cancel"),null).show();
                }).setNegativeButton(t("取消","Cancel"),null).show();
            });
        }catch(Exception error){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())status.setText(errorText(BackupErrorCode.from(error)));});}},"verified-backup-records").start();
    }
    private void retainedAction(){
        try{
            var fs=new AndroidBackupFileSystem();java.io.File files=getFilesDir().getCanonicalFile();var catalogue=new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());
            var entry=catalogue.resolve(getIntent().getStringExtra("retained_key"));String action=getIntent().getStringExtra("retained_action");
            int index=List.of("application","sessions","settings","plugins","projects").indexOf(entry.scope);if(index>=0)scope.setSelection(index);
            if("reexport".equals(action)&&entry.part.equals("encrypted")){pending.reexportId=entry.id;pending.filename="DSHA-data-v5-"+UUID.randomUUID()+".dshbak";destination.launch(pending.filename);}
            else if("restore-copy".equals(action)&&entry.part.equals("encrypted"))restorePassword(null,entry.id);
            else if("export-tree".equals(action)){
                NativeDataLocations.Selection selected=new NativeDataLocations.Selection();selected.scope=entry.scope;selected.retainedKey=entry.key();password(true,selected);
            }else if("restore-tree".equals(action)){
                new DshaDialogBuilder(this).setTitle(t("预检保留原件","Inspect retained original"))
                        .setMessage(t("先只读扫描所选原件并建立私有验证副本，不执行其中的程序。预检完成后还需确认目标；项目原件会恢复到新的独立目录。",
                                "Read the selected original into private verified staging without running its programs. Confirm the targets after inspection. Project originals restore into a new separate directory."))
                        .setPositiveButton(t("开始预检","Inspect"),(dialog,which)->{if(!jobs.prepareRestoreTree(entry.key(),Set.of(entry.scope),"PRIVATE"))status.setText(t("无法开始预检，请检查记录或当前任务。","Inspection could not start. Review the record or current operation."));})
                        .setNegativeButton(t("取消","Cancel"),null).show();
            }
        }catch(Exception error){status.setText(errorText(BackupErrorCode.from(error)));}
    }
    private NativeDataLocations.Selection selection(){NativeDataLocations.Selection selected=new NativeDataLocations.Selection();selected.scope=new String[]{"application","sessions","settings","plugins","projects"}[scope.getSelectedItemPosition()];selected.includeApiKey=key.isChecked();selected.documentProjects.addAll(projects);return selected;}
    private void locations(){
        if(pending.previewBusy)return;pending.previewBusy=true;pending.previewControl=new BackupControl(null);var control=pending.previewControl;
        NativeDataLocations.Selection selected=selection();var owner=pending;android.content.Context app=getApplicationContext();status.setText(t("正在统计所选范围、大小与排除项…","Reviewing selected scope, sizes and exclusions…"));
        new Thread(()->{try{var locations=new NativeDataLocations(app);var value=locations.locate(selected,control);owner.preview.postValue(BackupPreview.inspect(value.sources,app.getFilesDir().getUsableSpace(),control));}
            catch(Exception error){owner.previewError.postValue(BackupErrorCode.from(error));}finally{owner.previewBusy=false;}},"data-scope-preview").start();
    }
    private void showScope(BackupPreview.Report value){
        StringBuilder text=new StringBuilder();text.append(value.files).append(t(" 个文件 · 已知大小 "," files · known size ")).append(com.deepseekharness.app.util.Fmt.bytes(value.bytes));
        text.append(t("\n可用空间：","\nFree space: ")).append(com.deepseekharness.app.util.Fmt.bytes(value.freeBytes));
        if(value.unknown>0)text.append(t("\n尺寸未知的文件：","\nFiles with unknown size: ")).append(value.unknown);
        if(value.unreadable>0)text.append(t("\n缺失或不可读取项：","\nMissing or unreadable entries: ")).append(value.unreadable);
        text.append(t("\n这是读取时的预估；实际导出仍会检查变化和空间。\n", "\nThese are read-time estimates; export checks changes and space again.\n"));
        for(var root:value.roots){text.append('\n').append(root.id.equals("native-settings")?t("原生设置","Native settings"):root.name).append(" · ").append(scopeText(root.scope));
            text.append('\n').append(root.files).append(t(" 项 · "," items · ")).append(com.deepseekharness.app.util.Fmt.bytes(root.bytes));if(!root.location.isEmpty())text.append('\n').append(root.location);text.append('\n');}
        if(!value.exclusions.isEmpty()){text.append(t("\n排除项：","\nExclusions: "));for(var entry:value.exclusions.entrySet())text.append('\n').append(entry.getKey()).append(" · ").append(entry.getValue());}
        new DshaDialogBuilder(this).setTitle(t("数据范围与大小","Data scope and size")).setMessage(text.toString()).setPositiveButton(t("关闭","Close"),null).show();
    }
    private String scopeText(String scope){switch(scope){case "application":return t("应用数据","Application data");case "sessions":return t("对话与附件","Conversations and attachments");case "settings":return t("设置","Settings");case "plugins":return t("插件","Plugins");case "projects":return t("项目文件","Project files");default:return t("待确认范围","Scope needs review");}}
    private void password(boolean rescue){password(rescue,null);}
    private void password(boolean rescue,NativeDataLocations.Selection retained){
        if(jobs.state().busy)return;
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),0,dp(20),0);
        TextView explanation=new TextView(this);explanation.setText(rescue?t("救援不会停止当前写入，结果将标明尽力救援或部分数据。不能据此删除原件。",
                "Rescue does not stop current writers. The result is best-effort or partial and cannot justify deleting originals."):
                t("将停止 Web、终端和写入任务后建立快照。项目、配置与聊天都可能含敏感内容；请保存至少 12 个字符的密码。",
                "Web, terminals and write tasks will stop before the snapshot. Projects, configuration and conversations may contain secrets. Keep a password of at least 12 characters."));fields.addView(explanation);
        EditText first=new EditText(this),second=new EditText(this);for(EditText input:new EditText[]{first,second}){input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);fields.addView(input);}
        first.setHint(t("备份密码","Backup password"));second.setHint(t("再次输入","Repeat password"));
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        var dialog=new DshaDialogBuilder(this).setTitle(rescue?t("救援导出","Rescue export"):t("加密导出","Encrypted export")).setView(scroll).setPositiveButton(t("选择保存位置","Choose destination"),null).setNegativeButton(t("取消","Cancel"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            String a=first.getText().toString(),b=second.getText().toString();if(a.length()<12||a.length()>1024||!a.equals(b)){first.setError(t("至少 12 个字符，且两次输入一致","Use at least 12 characters and matching passwords"));return;}
            pending.clear();pending.password=a.toCharArray();pending.selection=retained==null?selection():retained;pending.rescue=rescue;pending.filename="DSHA-data-v5-"+UUID.randomUUID()+".dshbak";
            first.getText().clear();second.getText().clear();dialog.dismiss();destination.launch(pending.filename);
        }));dialog.show();
    }
    private void restorePassword(Uri uri){restorePassword(uri,null);}
    private void restorePassword(Uri uri,String copy){
        if(jobs.state().busy)return;LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),0,dp(20),0);
        TextView description=new TextView(this);description.setText(t("加密备份请输入密码；旧版明文 tar.gz 可留空。先在私有目录验证，确认前不会覆盖当前数据。",
                "Enter the encrypted backup password, or leave it empty for legacy plain tar.gz. Verification uses private staging and does not overwrite current data."));content.addView(description);
        EditText password=new EditText(this);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);content.addView(password);
        CheckBox include=new CheckBox(this);include.setText(t("恢复原生 API Key（默认保留本机凭据）","Restore native API key (keep local credentials by default)"));content.addView(include);
        TextView destinationLabel=new TextView(this);destinationLabel.setText(t("项目恢复位置（创建新的独立目录）","Project destination (creates a new separate directory)"));content.addView(destinationLabel);
        Spinner projectDestination=choice(new String[]{t("应用私有项目区","App-private projects"),t("容器主目录 /root","Container home /root")});content.addView(projectDestination);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new DshaDialogBuilder(this).setTitle(t("备份预检","Inspect backup")).setView(scroll).setPositiveButton(t("开始预检","Inspect"),(d,w)->{
            char[] chars=password.getText().toString().toCharArray();password.getText().clear();try{
                String location=projectDestination.getSelectedItemPosition()==0?"PRIVATE":"GUEST_HOME";
                boolean started=copy==null?jobs.prepareRestore(uri,chars.length==0?null:chars,Set.of(selection().scope),include.isChecked(),location):jobs.prepareRestoreCopy(copy,chars.length==0?null:chars,Set.of(selection().scope),include.isChecked(),location);
                if(!started)status.setText(t("已有任务，完成后再试。","Another task is in progress."));
            }finally{Arrays.fill(chars,'\0');}
        }).setNegativeButton(t("取消","Cancel"),null).show();
    }
    private void restorePreview(NativeBackupJobs.State value){
        if(previewDialog!=null)return;
        try{Map<String,Object> preview=jobs.preview(value.id);String info=t("归档已通过完整预检。\n文件/目录：","Archive inspection passed.\nFiles/directories: ")+preview.get("entries")+t("\n同名文件冲突：","\nExisting-file conflicts: ")+preview.get("conflicts")
                +t("\n确认后将停止 Web、终端和写任务，合并所选范围并替换归档中同名文件。未提及的文件保留。插件在独立目录隔离导入，项目恢复到新的独立目录。",
                "\nConfirmation stops Web, terminals and writers, merges selected roots and replaces matching files from the archive. Unmentioned files remain. Plugins are quarantined and projects use new folders.");
            if(Boolean.TRUE.equals(preview.get("legacyConfirmationRequired")))info+=t("\n这是历史格式备份；摘要验证不代表来源身份认证。","\nThis is a legacy backup; checksum validation does not authenticate its sender.");
            if(preview.get("warnings") instanceof List&&!((List<?>)preview.get("warnings")).isEmpty())info+=t("\n该来源含未确认的格式或恢复提示；不承诺可用于所有历史运行时。原件继续保留。","\nThis source has unconfirmed format or restore warnings. Compatibility with every historical runtime is not established. Originals remain retained.");
            info+=t("\n项目位置：","\nProject destination: ")+("GUEST_HOME".equals(preview.get("projectDestination"))?t("容器主目录的新目录","A new directory in container home"):t("应用私有项目区的新目录","A new directory in app-private projects"));
            previewDialog=new DshaDialogBuilder(this).setTitle(t("确认恢复范围","Confirm restore scope")).setMessage(info)
                    .setPositiveButton(t("确认并恢复","Confirm and restore"),(d,w)->jobs.decide(value.id,true))
                    .setNegativeButton(t("取消","Cancel"),(d,w)->jobs.decide(value.id,false)).setOnCancelListener(d->jobs.decide(value.id,false)).create();
            previewDialog.setOnDismissListener(d->previewDialog=null);previewDialog.show();
        }catch(Exception error){status.setText(t("预检记录不可读取，请重新选择备份。","The inspection record is unavailable. Select the backup again."));}
    }
    private void render(NativeBackupJobs.State value){if(value==null)return;
        if(cancelOperation!=null)cancelOperation.setVisibility(value.busy?View.VISIBLE:View.GONE);
        if(value.stage.equals("PREVIEW")){restorePreview(value);return;}
        if(previewDialog!=null){previewDialog.dismiss();previewDialog=null;}
        String result=value.result.equals("COMPLETE")?t("导出完成，私有产物与目标均已校验。","Export complete; private artifact and destination verified."):
                value.result.equals("WRITTEN_UNVERIFIED")?t("已写入，未完成目标读回校验；私有验证副本保留。","Written, but destination readback was not verified. The private verified copy is retained."):
                value.result.equals("PARTIAL_RESCUE")?t("部分救援：缺失或变化的内容已记录，不能视为完整备份。","Partial rescue: missing or changed content was recorded; this is not a complete backup."):
                value.result.equals("BEST_EFFORT_RESCUE")?t("尽力救援完成，未承诺应用级一致性。","Best-effort rescue completed without an application-consistency guarantee."):
                value.result.equals("DATA_RESTORED_API_KEY_MISSING")?t("数据已恢复，但备份中的 API Key 无法读取；请到模型配置重新填写。","Data restored, but the selected API key could not be read. Enter it again in Model configuration."):
                value.result.equals("DATA_RESTORED_API_KEY_OMITTED")?t("数据已恢复；本次备份未包含 API Key，请到模型配置填写。","Data restored; this backup did not include an API key. Enter it in Model configuration."):
                value.result.equals("DATA_RESTORED_PLUGINS_QUARANTINED")?t("数据已恢复，插件、可执行配置与待确认的自定义数据已隔离保存，尚未启用。","Data restored; plugins, executable configuration and unclassified custom data are quarantined and not enabled."):
                value.result.startsWith("DATA_RESTORED")?t("数据已恢复；运行兼容性仍需检查。","Data restored; runtime compatibility still needs verification."):
                value.result.equals("DATA_SAVED_PLUGIN_WARNINGS")?t("数据已保存，插件依赖有缺失或未确认项。","Data saved with missing or unverified plugin dependencies."):
                value.result.equals("RECOVERED_INTERRUPTED_COMMIT")?t("中断的提交已恢复一致状态，原件保留。", "The interrupted commit is consistent again; originals were retained."):stage(value.stage);
        String projection=com.deepseekharness.app.data.PortableSettings.errorCode();
        status.setText(result+"\n"+value.entries+t(" 项 · "," items · ")+com.deepseekharness.app.util.Fmt.bytes(value.bytes)+(value.error.isEmpty()?"":"\n"+errorText(value.error))+(projection.isEmpty()?"":"\n"+errorText(projection)));
    }
    private String errorText(String code){
        String message;
        switch(code){
            case "AUTHENTICATION_FAILED":message=t("密码不匹配，或备份已损坏、被修改。当前数据保持原位。","The password does not match, or the backup is damaged or modified. Current data stayed in place.");break;
            case "NO_SPACE":message=t("可用空间不足。请释放空间后重试，原件与已验证副本保留。","Not enough free space. Free up space and retry; originals and verified copies are retained.");break;
            case "CREDENTIAL_TEMPORARILY_UNAVAILABLE":message=com.deepseekharness.app.core.ConfigStore.credentialMessage(com.deepseekharness.app.util.CredentialRead.failed(com.deepseekharness.app.util.CredentialRead.Reason.TRANSIENT_STORE));break;
            case "CREDENTIAL_NEEDS_ATTENTION":message=com.deepseekharness.app.core.ConfigStore.credentialMessage(com.deepseekharness.app.util.CredentialRead.failed(com.deepseekharness.app.util.CredentialRead.Reason.UNREADABLE));break;
            case "CREDENTIAL_UNAVAILABLE":message=t("原设备的密钥不可用。可先不包含原生 API Key 导出其他数据，再重新填写凭据。","The original device key is unavailable. Export other data without the native API key, then re-enter the credential.");break;
            case "PERMISSION_DENIED":case "SAF_PERMISSION_REVOKED":message=t("数据访问权限不可用。请重新选择目录或授予访问权限。","Data access is unavailable. Select the folder again or restore its permission.");break;
            case "CRYPTO_HEADER":case "ARCHIVE_VERSION":case "UNSUPPORTED_LEGACY_VERSION":message=t("此备份格式尚不支持，当前数据未覆盖。","This backup format is unsupported. Current data was not overwritten.");break;
            case "SOURCE_CHANGED":case "TARGET_CHANGED":case "INPUT_CHANGED":message=t("数据在核验期间发生变化，已停止操作并保留原件。","Data changed during verification. The operation stopped and originals were retained.");break;
            case "DESTINATION_CHECKSUM":message=t("目标读回校验失败，私有验证副本保留，可另选位置导出。","Destination readback failed verification. The private verified copy is retained for export elsewhere.");break;
            case "VERIFIED_COPY_CHANGED":case "VERIFIED_COPY_FORMAT":case "VERIFIED_COPY_RECORD":case "VERIFIED_COPY_SCOPE":message=t("所选私有副本或记录未通过复核，已停止复制并保留原件。可选择另一份副本。", "The private copy or its record failed verification. Copying stopped and originals were retained. Choose another copy.");break;
            case "CANCELLED":message=t("操作已取消；若已进入提交阶段，会先恢复一致状态。","Operation cancelled; a commit already in progress converges before releasing protection.");break;
            default:message=t("数据操作未完成。请查看错误代码和保留记录后重试。","The data operation did not complete. Review its error code and retained records before retrying.");
        }return message+"\n"+t("错误代码：","Error code: ")+code;
    }
    private String stage(String stage){switch(stage){case "IDLE":return t("等待操作","Ready");case "PREPARING":case "COPYING_INPUT":return t("准备与读取数据","Preparing and reading data");case "CAPTURING":case "ARCHIVING":return t("生成私有快照","Creating private snapshot");case "ENCRYPTING":return t("加密备份","Encrypting backup");case "AUTHENTICATING":case "VERIFYING":return t("验证完整性与认证","Verifying integrity and authentication");case "EXPORTING":return t("写入所选位置","Writing to destination");case "COMMITTING":return t("提交数据，正在保持一致性","Committing data and maintaining consistency");case "STOPPING_WRITERS":case "PREPARING_RESTORE":return t("准备恢复并停止写入","Preparing restore and stopping writers");case "INTERRUPTED":return t("上次作业中断，原件与私有副本保留","Previous operation interrupted; originals and private copies retained");case "CANCELLED":return t("已取消","Cancelled");case "FAILED":case "FAILED_RETAINED":return t("作业未完成，原件保留","Operation incomplete; originals retained");default:return t("处理数据中","Processing data");}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(getColor(R.color.text));text.setPadding(0,dp(10),0,dp(10));body.addView(text);return text;}
    private Button button(String label,Runnable click){Button button=new androidx.appcompat.widget.AppCompatButton(this);button.setTextSize(13);button.setBackgroundResource(R.drawable.bg_action_plain);button.setTextColor(getColor(R.color.primary));button.setText(label);button.setAllCaps(false);button.setIncludeFontPadding(false);button.setGravity(android.view.Gravity.CENTER);button.setMinHeight(dp(48));button.setOnClickListener(v->click.run());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);body.addView(button,params);return button;}
    private void section(String title){body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(10),dp(14),dp(14));body.setBackgroundResource(R.drawable.bg_card);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(16);page.addView(body,params);text(title,16).setTypeface(null,android.graphics.Typeface.BOLD);}
    private Spinner choice(String[] items){Spinner choice=new DshaSelectView(this);choice.setMinimumHeight(dp(48));choice.setBackgroundResource(R.drawable.bg_btn);choice.setPadding(dp(8),0,dp(8),0);choice.setAdapter(new ArrayAdapter<>(this,R.layout.item_data_choice,items));return choice;}
}
