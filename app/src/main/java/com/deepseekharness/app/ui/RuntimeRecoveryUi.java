package com.deepseekharness.app.ui;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.UiText;
/** 回退页面只呈现兼容前代；实际切换仍由原维护事务核验和试运行。 */
final class RuntimeRecoveryUi {
    static void show(AppCompatActivity activity){
        var controller=HarnessController.get(activity);
        new Thread(()->{try{
            var options=ManagedRuntimeTransaction.rollbackOptions(new AndroidBackupFileSystem(),activity.getFilesDir().getCanonicalFile(),controller.proot().expectedRuntimeDescriptor(),controller.proot().installedRuntimeDescriptor());
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                CardPage page=new CardPage(activity,t("兼容运行时回退","Compatible runtime rollback"),t("先核对兼容性，通过隔离试运行后再切换。","Compatibility and an isolated trial are required before switching."));
                var dialog=CardSheet.create(activity,page);
                if(options.isEmpty())page.card().addView(page.text(t("没有可确认兼容的直接前代。未知或受损原件仍保留；可先导出数据，再向前修复环境。","No direct predecessor is confirmed compatible. Unknown or damaged originals remain retained. Export readable data, then repair forward."),13,R.color.text_secondary));
                for(var item:options)page.entry(page.content,"DSH "+item.dshVersion,item.runtimeId.substring(0,12),R.drawable.ic_ui2_box,()->{
                    CardPage confirm=new CardPage(activity,t("回退确认","Review rollback"),"");var details=confirm.card();confirm.kv(details,"DSH",item.dshVersion);confirm.kv(details,t("个人数据","Personal data"),t("保持原位","Kept in place"));
                    details.addView(confirm.text(t("将停止 Web、终端和写入任务，复核保留文件，随后在当前 APK 下重新进行隔离试运行。不会恢复旧对话快照。","Stop Web, terminals and writers, recheck retained files, then run an isolated trial under this APK. Old conversation snapshots are not restored."),13,R.color.text_secondary));
                    var check=CardSheet.create(activity,confirm);confirm.button(confirm.footer,t("验证并回退","Validate and roll back"),true,()->{
                        var task=BackupTask.get(activity);if(task.rollbackRuntime(item.operationId)){check.dismiss();dialog.dismiss();activity.startActivity(new Intent(activity,ExtractActivity.class).putExtra("review_only",true).putExtra("data_task_id",task.snapshot().id));}
                        else android.widget.Toast.makeText(activity,t("当前有任务或维护记录需要处理，请稍后重试。","A task or maintenance record needs attention. Retry later."),android.widget.Toast.LENGTH_LONG).show();
                    });confirm.button(confirm.footer,t("取消","Cancel"),false,check::dismiss);CardSheet.show(check,activity);
                });
                page.button(page.footer,t("关闭","Close"),false,dialog::dismiss);CardSheet.show(dialog,activity);
            });
        }catch(Exception error){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())CardSheet.show(activity,t("运行时记录待检查","Runtime records need review"),t("存在未完成或不可读取的维护记录。请先恢复中断维护，原件不会被覆盖。","Maintenance records are incomplete or unreadable. Recover interrupted maintenance first; originals will not be overwritten."));});}},"runtime-retention-review").start();
    }
    private static String t(String zh,String en){return UiText.choose(zh,en);}
}