package com.deepseekharness.app.ui;

import androidx.appcompat.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupScope;


/**
 * 数据与备份子页：备份（按范围 + 验证）/ 恢复（合并 + 验证）。
 */
public class WorkspaceFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private HarnessController controller;
    private com.deepseekharness.app.core.BackupTask task;
    private AlertDialog previewDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_workspace, container, false);
        controller = HarnessController.get(requireContext());
        task = com.deepseekharness.app.core.BackupTask.get(requireContext());
        ((TextView)v.findViewById(R.id.workspace_path)).setText(controller.config().getWorkdir());
        v.findViewById(R.id.workspace_retained).setOnClickListener(x->startActivity(new android.content.Intent(requireContext(),RetainedDataActivity.class)));
        ((TextView)v.findViewById(R.id.workspace_apply)).setText(com.deepseekharness.app.util.UiText.choose("查看目录与共享方式","Directory and sharing details"));
        v.findViewById(R.id.workspace_apply).setOnClickListener(x->CardSheet.show(requireContext(),com.deepseekharness.app.util.UiText.choose("数据位置与共享","Data location and sharing"),controller.config().getWorkdir()+"\n\n"+((TextView)v.findViewById(R.id.workspace_share_status)).getText()));
        android.widget.CheckBox backupKey = v.findViewById(R.id.config_backup_key);
        backupKey.setVisibility(View.GONE);
        backupKey.setChecked(controller.config().isBackupKey());
        backupKey.setOnClickListener(button -> {
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease saving =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("保存备份设置"));
            if (saving == null) {
                backupKey.setChecked(controller.config().isBackupKey());
                toast(com.deepseekharness.app.util.UiText.text("数据任务进行中，完成后再修改备份设置")); return;
            }
            try { saving.run(() -> { controller.config().setBackupKey(backupKey.isChecked()); return null; }); }
            catch (Exception error) { backupKey.setChecked(controller.config().isBackupKey()); toast(com.deepseekharness.app.util.UiText.text("备份设置保存失败，请重试")); }
            finally { saving.close(); }
        });

        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class).putExtra("data_mode","export")));
        v.findViewById(R.id.workspace_restore).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class).putExtra("data_mode","restore")));
        v.findViewById(R.id.workspace_location).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class)));
        v.findViewById(R.id.workspace_restore_latest).setOnClickListener(x -> {
            String uri = controller.config().getLastBackupUri();
            if (!uri.isEmpty()) doRestore(Uri.parse(uri));
        });
        android.widget.LinearLayout content=(android.widget.LinearLayout)((android.widget.ScrollView)v).getChildAt(0);
        android.widget.LinearLayout extra=new android.widget.LinearLayout(requireContext());extra.setOrientation(android.widget.LinearLayout.VERTICAL);extra.setBackgroundResource(R.drawable.bg_card);
        CardPage helper=new CardPage(requireContext(),"","");
        helper.entry(extra,com.deepseekharness.app.util.UiText.choose("自动备份","Automatic backups"),com.deepseekharness.app.util.UiText.choose("开关、备份时机与最近副本","Switch, schedule and recent copies"),R.drawable.ic_recovery_document,()->startActivity(new android.content.Intent(requireContext(),AutomaticBackupActivity.class)));
        helper.entry(extra,com.deepseekharness.app.util.UiText.choose("存储空间","Storage"),com.deepseekharness.app.util.UiText.choose("查看占用与清理缓存","Review usage and clean caches"),R.drawable.ic_ui2_box,()->startActivity(new android.content.Intent(requireContext(),StorageActivity.class)));
        content.addView(extra,Math.min(2,content.getChildCount()),new android.widget.LinearLayout.LayoutParams(-1,-2));
        refreshBackupStatus(v);
        v.findViewById(R.id.workspace_backup_status).setOnClickListener(x->CardSheet.show(requireContext(),com.deepseekharness.app.util.UiText.choose("备份与维护记录","Backup and maintenance history"),((TextView)v.findViewById(R.id.workspace_backup_status)).getText().toString()));


        // 文件共享（DocumentsProvider，MT 管理器可发现）
        TextView shareStatus = v.findViewById(R.id.workspace_share_status);
        if (shareStatus != null) {
            shareStatus.setText(com.deepseekharness.app.util.UiText.text("在 MT 管理器中添加本地存储，选择 DocumentsProvider → DSHA。\n\n")
                    + com.deepseekharness.app.util.UiText.text("容器目录：files/linux/ubuntu/root\n")
                    + com.deepseekharness.app.util.UiText.text("配置目录：容器中的 .dsh\n\n")
                    + com.deepseekharness.app.util.UiText.text("找不到 DSHA 时，先打开本 App 后重试。"));
        }

        // 清理损坏会话：1.2-alpha 的会话是 packed/zstd，对 DSHA 不透明，照原版隐藏该控制
        View cleanSessions = v.findViewById(R.id.workspace_clean_sessions);
        if (cleanSessions != null) cleanSessions.setVisibility(View.GONE);

        v.findViewById(R.id.workspace_reset).setOnClickListener(x -> {
            if (task.busy() || task.pendingMaintenance()) { taskRejected(); return; }
            new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("重置配置？"))
                    .setMessage(com.deepseekharness.app.util.UiText.choose("先停止 Web、终端和写任务，再由 Android 保存原配置并重置 settings.yaml 与工作目录的 .env。对话和原生凭据保留；无需旧 Python。外部目录或链接需先单独确认，不会强行覆盖。", "Stop Web, terminals and writers, then let Android retain the original configuration and reset settings.yaml and the workspace .env. Conversations and native credentials stay intact. The old Python is not required. External folders and links require separate review and will not be overwritten."))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.choose("保留原件并重置", "Retain originals and reset"), (d, w) -> { if (!task.resetConfig()) taskRejected(); })
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).show();
        });
        return v;
    }

    private void doRestore(Uri backupUri) {
        startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class).putExtra("restore_uri",backupUri.toString()));
    }

    private void taskRejected() {
        toast(task.pendingMaintenance() ? com.deepseekharness.app.util.UiText.text("请先点「恢复中断维护」，原环境尚未确认。") : com.deepseekharness.app.util.UiText.text("已有数据任务进行中，请等待或处理恢复预览。"));
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (getView() == null || task == null) return;
            refreshBackupStatus(getView());
            main.postDelayed(this, 500);
        }
    };
    private void refreshBackupStatus(View view) {
        long time = controller.config().getLastBackupSuccess();
        String success = time == 0 ? com.deepseekharness.app.util.UiText.choose("还没有手动导出备份", "No manually exported backup yet") : com.deepseekharness.app.util.UiText.choose("最近手动备份：", "Latest manual backup: ")
                + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(time))
                + "\n" + controller.config().getLastBackupName();
        long automatic=com.deepseekharness.app.backup.AutomaticBackups.prefs(requireContext()).getLong("last",0);
        if(automatic>0)success+="\n"+com.deepseekharness.app.util.UiText.choose("最近自动副本：", "Latest automatic copy: ")+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(automatic));
        String failure = controller.config().getLastBackupError();
        com.deepseekharness.app.util.BackupTaskState.Snapshot s = task.snapshot();
        boolean busy = task.busy(), pending = EnvironmentUiStatus.get(requireContext()).recovery;
        android.widget.CheckBox backupKey = view.findViewById(R.id.config_backup_key);
        backupKey.setChecked(controller.config().isBackupKey());
        backupKey.setEnabled(!busy && !com.deepseekharness.app.util.EnvironmentTaskGate.isBusy());
        ((TextView) view.findViewById(R.id.workspace_backup_status)).setText(success
                + (failure.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n上次未完成：") + failure)
                + (s.id == 0 ? "" : "\n\n" + s.kind + "\n" + s.detail)
                + (pending ? com.deepseekharness.app.util.UiText.text("\n\n有未完成的环境维护，请恢复原环境后再继续。") : ""));
        for (int id : new int[]{R.id.workspace_backup, R.id.workspace_restore, R.id.workspace_reset, R.id.workspace_apply})
            view.findViewById(id).setEnabled(!busy && !pending);
        view.findViewById(R.id.workspace_restore_latest).setEnabled(!busy && !pending && !controller.config().getLastBackupUri().isEmpty());
        if (s.status == com.deepseekharness.app.util.BackupTaskState.Status.PREVIEW && previewDialog == null && isResumed()) {
            previewDialog = new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("恢复预览"))
                    .setMessage(com.deepseekharness.app.util.UiStateText.render(s.detail))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("恢复此备份"), (d, w) -> task.decide(s.id, true))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), (d, w) -> task.decide(s.id, false))
                    .setOnCancelListener(d -> task.decide(s.id, false)).create();
            previewDialog.setOnDismissListener(d -> previewDialog = null);
            previewDialog.show();
        }
    }

    @Override public void onResume() {
        super.onResume();
        main.removeCallbacks(refreshTask); main.post(refreshTask);
    }
    @Override public void onPause() {
        main.removeCallbacks(refreshTask);
        if (previewDialog != null) { previewDialog.dismiss(); previewDialog = null; }
        super.onPause();
    }
    @Override public void onDestroyView() {
        main.removeCallbacks(refreshTask);
        super.onDestroyView();
    }
    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }
}
