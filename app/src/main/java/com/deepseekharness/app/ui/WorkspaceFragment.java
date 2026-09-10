package com.deepseekharness.app.ui;

import android.app.AlertDialog;
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
import com.deepseekharness.app.ShizukuShell;
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

        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> chooseScopeAndBackup());
        v.findViewById(R.id.workspace_restore).setOnClickListener(x -> confirmRestore());
        v.findViewById(R.id.workspace_location).setOnClickListener(x ->
                Toast.makeText(requireContext(), "备份保存在 Download/DSHA/", Toast.LENGTH_LONG).show());
        v.findViewById(R.id.workspace_restore_latest).setOnClickListener(x -> {
            String uri = controller.config().getLastBackupUri();
            if (!uri.isEmpty()) doRestore(Uri.parse(uri));
        });
        refreshBackupStatus(v);

        // 文件共享（DocumentsProvider，MT 管理器可发现）
        TextView shareStatus = v.findViewById(R.id.workspace_share_status);
        if (shareStatus != null) {
            shareStatus.setText("在 MT 管理器中添加本地存储，选择 DocumentsProvider → DSHA。\n\n"
                    + "容器目录：files/linux/ubuntu/root\n"
                    + "配置目录：容器中的 .dsh\n\n"
                    + "找不到 DSHA 时，先打开本 App 后重试。");
        }

        // Shizuku 授权（备用 shell 通道）
        v.findViewById(R.id.workspace_shizuku_auth).setOnClickListener(x -> {
            if (!ShizukuShell.isAvailable()) {
                Toast.makeText(requireContext(), "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            ShizukuShell.requestPermission((code, grantResult) -> refreshShizukuStatus());
            refreshShizukuStatus();
        });

        // 清理损坏会话：1.2-alpha 的会话是 packed/zstd，对 DSHA 不透明，照原版隐藏该控制
        View cleanSessions = v.findViewById(R.id.workspace_clean_sessions);
        if (cleanSessions != null) cleanSessions.setVisibility(View.GONE);

        v.findViewById(R.id.workspace_reset).setOnClickListener(x -> {
            if (task.busy() || task.pendingMaintenance()) { taskRejected(); return; }
            new AlertDialog.Builder(requireContext()).setTitle("重置配置？")
                    .setMessage("先停止 Web（会中断正在执行的任务）并创建校验安全备份，再重置 settings.yaml 和 .env；保留对话。")
                    .setPositiveButton("备份并重置", (d, w) -> { if (!task.resetConfig()) taskRejected(); })
                    .setNegativeButton("取消", null).show();
        });
        v.findViewById(R.id.workspace_clear).setOnClickListener(x -> {
            if (task.busy()) { taskRejected(); return; }
            boolean recovery = task.pendingMaintenance();
            new AlertDialog.Builder(requireContext()).setTitle(recovery ? "恢复中断维护" : "备份并重建环境？")
                    .setMessage(recovery ? "停止 Web 后回切原环境；安全备份及失败的新环境都会保留。"
                            : "先停止 Web（会中断正在执行的任务），完整备份并校验配置、会话和本地插件，再重建环境并恢复数据。\n\n"
                            + "旧环境和安全备份留在 App 私有目录，占用额外空间；任何失败均停止并尝试回切。额外安装的系统软件留在旧环境中。")
                    .setPositiveButton(recovery ? "恢复原环境" : "备份并重建", (d, w) -> {
                        if (!(recovery ? task.recoverMaintenance() : task.rebuild())) taskRejected();
                    }).setNegativeButton("取消", null).show();
        });
        refreshShizukuStatus();
        return v;
    }

    private void refreshShizukuStatus() {
        try {
            TextView status = getView() == null ? null
                    : getView().findViewById(R.id.workspace_shizuku_status);
            if (status == null) return;
            if (!ShizukuShell.isAvailable()) {
                status.setText("Shizuku 未安装/未运行");
            } else if (ShizukuShell.hasPermission() && ShizukuShell.isReady()) {
                status.setText("Shizuku 已授权，设备 shell 通道可用");
            } else if (ShizukuShell.hasPermission()) {
                status.setText("Shizuku 已授权，等待绑定服务…");
            } else {
                status.setText("Shizuku 未授权，点下方授权");
            }
        } catch (Throwable ignored) {
        }
    }

    private void chooseScopeAndBackup() {
        final CharSequence[] choices = new CharSequence[BackupScope.ALL.length];
        for (int i = 0; i < BackupScope.ALL.length; i++) {
            choices[i] = BackupScope.label(BackupScope.ALL[i]) + "\n" + BackupScope.describe(BackupScope.ALL[i]);
        }
        final int[] selected = {0};
        new AlertDialog.Builder(requireContext())
                .setTitle("选择备份范围")
                .setSingleChoiceItems(choices, 0, (d, which) -> selected[0] = which)
                .setPositiveButton("下一步", (d, which) -> confirmBackup(BackupScope.ALL[selected[0]]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBackup(final int scope) {
        String summary = "即将备份：" + BackupScope.label(scope)
                + "\n" + BackupScope.describe(scope)
                + "\n\n保存到 Download/DSHA，每次使用独立文件名。"
                + "\n快照期间若数据变化，备份会失败，可在任务完成后重试。"
                + (scope == BackupScope.FULL || scope == BackupScope.SETTINGS
                ? "\n原生 API Key：" + (controller.config().isBackupKey() ? "按当前设置包含" : "按当前设置不包含") : "")
                + "\n配置文件和插件自身保存的凭据会随原文件一起备份。";
        new AlertDialog.Builder(requireContext())
                .setTitle("确认备份")
                .setMessage(summary)
                .setPositiveButton("开始备份", (d, w) -> doBackup(scope))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doBackup(final int scope) {
        if (!task.backup(scope)) taskRejected();
    }
    private final androidx.activity.result.ActivityResultLauncher<String[]> restorePicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) doRestore(uri);
                    });

    private void confirmRestore() {
        if (task.busy() || task.pendingMaintenance()) { taskRejected(); return; }
        new AlertDialog.Builder(requireContext())
                .setTitle("恢复备份")
                .setMessage("选择 .tar.gz 备份文件。预检会显示范围和版本，不停止 Web；确认恢复时才停止 Web，并中断正在执行的任务。")
                .setPositiveButton("选择文件", (d, w) -> {
                    android.util.Log.i("DSHA-restore", "选择文件按钮点击，准备 launch");
                    try {
                        restorePicker.launch(new String[]{"application/gzip", "*/*"});
                        android.util.Log.i("DSHA-restore", "launch 已调用");
                    } catch (Throwable t) {
                        android.util.Log.e("DSHA-restore", "launch 异常: " + t, t);
                        Toast.makeText(requireContext(), "打开选择器失败：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRestore(Uri backupUri) {
        if (!task.prepareRestore(backupUri)) taskRejected();
    }

    private void taskRejected() {
        toast(task.pendingMaintenance() ? "请先点「恢复中断维护」，原环境尚未确认。" : "已有数据任务进行中，请等待或处理恢复预览。");
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
        String success = time == 0 ? "还没有成功备份" : "最近成功："
                + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(time))
                + "\n" + controller.config().getLastBackupName();
        String failure = controller.config().getLastBackupError();
        com.deepseekharness.app.util.BackupTaskState.Snapshot s = task.snapshot();
        boolean busy = task.busy(), pending = task.pendingMaintenance();
        ((TextView) view.findViewById(R.id.workspace_backup_status)).setText(success
                + (failure.isEmpty() ? "" : "\n上次未完成：" + failure)
                + (s.id == 0 ? "" : "\n\n" + s.kind + "\n" + s.detail)
                + (pending ? "\n\n有未完成的环境维护，请恢复原环境后再继续。" : ""));
        for (int id : new int[]{R.id.workspace_backup, R.id.workspace_restore, R.id.workspace_reset, R.id.workspace_apply})
            view.findViewById(id).setEnabled(!busy && !pending);
        view.findViewById(R.id.workspace_restore_latest).setEnabled(!busy && !pending && !controller.config().getLastBackupUri().isEmpty());
        android.widget.Button clear = view.findViewById(R.id.workspace_clear);
        clear.setEnabled(!busy);
        clear.setText(pending ? "恢复中断维护" : "备份并重建环境");
        if (s.status == com.deepseekharness.app.util.BackupTaskState.Status.PREVIEW && previewDialog == null && isResumed()) {
            previewDialog = new AlertDialog.Builder(requireContext()).setTitle("恢复预览")
                    .setMessage(s.detail)
                    .setPositiveButton("恢复此备份", (d, w) -> task.decide(s.id, true))
                    .setNegativeButton("取消", (d, w) -> task.decide(s.id, false))
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
