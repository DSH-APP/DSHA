package com.deepseekharness.app.ui;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.util.UiText;

public final class StorageActivity extends AppCompatActivity {
    private CardPage page;private LinearLayout rows;private TextView state;private Button clean,refresh;private boolean busy;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);
        page=new CardPage(this,t("存储与文件","Storage and files"),t("完整 Linux 环境约占 1 GB。历史环境和回退副本另行保留；它们不等于聊天数据。以下为文件大小估算。","The full Linux environment uses about 1 GB. Older environments and rollback copies are retained separately from chats. Sizes below are estimates."));UiNavigation.addHeader(this,page.root,t("存储与文件","Storage and files"),this::finish);
        var files=page.card();page.kv(files,t("工作目录","Workspace"),com.deepseekharness.app.core.HarnessController.get(this).config().getWorkdir());
        page.entry(files,t("文件共享","File sharing"),t("通过系统文件选择器或 MT 管理器访问 DSHA","Access DSHA from the system file picker or MT Manager"),R.drawable.ic_ui2_box,()->CardSheet.show(this,t("文件共享","File sharing"),t("在文件管理器中添加本地存储，选择 DocumentsProvider → DSHA。找不到 DSHA 时，先打开本 App 后重试。","Add local storage in your file manager and select DocumentsProvider → DSHA. Open this app first if DSHA is missing.")));
        rows=page.card();state=page.text("",12,R.color.text_secondary);page.content.addView(state);
        clean=page.button(page.footer,t("清理可再生缓存与多余运行时副本","Clean reproducible caches and excess runtime copies"),true,()->new DshaDialogBuilder(this).setTitle(t("清理存储空间","Clean storage"))
                .setMessage(t("清理前会安全停止 DSH 和终端，再清理历史验收缓存，以及超过保留数量且已核验的受管回退副本。对话、附件、设置、插件、手动备份和无法核验的原件保留。","DSH and terminals are stopped safely before cleanup. Removes old verification caches and verified excess managed rollback copies. Keeps chats, attachments, settings, plugins, manual backups and unverified originals."))
                .setPositiveButton(t("开始清理","Clean"),(d,w)->load(true)).setNegativeButton(t("取消","Cancel"),null).show());
        refresh=page.button(page.footer,t("重新统计","Refresh sizes"),false,()->load(false));setContentView(page.root);load(false);
    }
    private void load(boolean remove){if(busy)return;busy=true;clean.setEnabled(false);refresh.setEnabled(false);state.setText(t("正在处理…","Working…"));
        new Thread(()->{try{long reclaimed=remove?StorageMaintenance.clean(this):0;var sizes=StorageMaintenance.inspect(this);runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;rows.removeAllViews();
            String[] zh={"当前 Linux 与 DSH","运行时回退副本","环境重建保护副本","旧版更新记录","备份副本","兼容浏览器数据","宿主个人数据","应用缓存"};String[] en={"Current Linux and DSH","Runtime rollback copies","Environment protection copies","Legacy update records","Backup copies","Compatibility browser data","Persistent user data","App cache"};int i=0;
            for(var entry:sizes.entrySet()){page.kv(rows,t(zh[i],en[i]),com.deepseekharness.app.util.Fmt.bytes(entry.getValue().bytes)+(entry.getValue().unreadable>0?t(" + 部分不可读"," + unreadable entries"):""));i++;}
            state.setText(remove?t("已释放 ","Freed ")+com.deepseekharness.app.util.Fmt.bytes(reclaimed):t("已完成统计。清理只移除可再生缓存和已核验的多余运行时副本，个人数据与备份保留。","Sizes updated. Cleanup removes only reproducible caches and verified excess runtime copies. Personal data and backups are retained."));done();
        });}catch(Exception error){runOnUiThread(()->{state.setText(com.deepseekharness.app.util.MaintenanceErrorText.render(com.deepseekharness.app.backup.NativeBackupJobs.code(error)));done();});}},"storage-maintenance").start();
    }
    private void done(){busy=false;clean.setEnabled(true);refresh.setEnabled(true);}
}
