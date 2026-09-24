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
        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class).putExtra("data_mode","backup")));
        v.findViewById(R.id.workspace_location).setOnClickListener(x -> startActivity(new android.content.Intent(requireContext(),NativeDataActivity.class)));
        v.findViewById(R.id.workspace_storage).setOnClickListener(x ->
                startActivity(new android.content.Intent(requireContext(), StorageActivity.class)));
        refreshBackupStatus(v);
        v.findViewById(R.id.workspace_backup_status).setOnClickListener(x->CardSheet.show(requireContext(),com.deepseekharness.app.util.UiText.choose("备份与维护记录","Backup and maintenance history"),((TextView)v.findViewById(R.id.workspace_backup_status)).getText().toString()));


        v.findViewById(R.id.workspace_reset).setOnClickListener(x -> {
            if (task.busy() || task.pendingMaintenance()) { taskRejected(); return; }
            new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("重置配置？"))
                    .setMessage(com.deepseekharness.app.util.UiText.choose("先停止 Web、终端和写任务，在隔离服务中重置当前 web profile 的普通设置并读回，再事务提交与工作目录 .env。插件构成、对话和凭据保留；原配置可恢复。无法确认时保留原件并显示原因。", "Stop Web, terminals and writers. Reset ordinary settings of the web profile in an isolated service, read them back, then commit together with the workspace .env. Plugin composition, conversations and credentials are retained. Failures preserve originals and report the reason."))
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
            main.postDelayed(this, 1_000);
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
        EnvironmentUiStatus.Snapshot environment = EnvironmentUiStatus.get(requireContext());
        boolean busy = task.busy(), pending = environment.recovery;
        ((TextView) view.findViewById(R.id.workspace_backup_status)).setText(success
                + (failure.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n上次未完成：") + failure)
                + (s.id == 0 ? "" : "\n\n" + s.kind + "\n" + com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail))
                + (pending ? com.deepseekharness.app.util.UiText.text("\n\n有未完成的环境维护，请恢复原环境后再继续。") : ""));
        for (int id : new int[]{R.id.workspace_backup, R.id.workspace_location, R.id.workspace_reset})
            view.findViewById(id).setEnabled(!busy && !pending);
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
