package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.InstallRepository;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.SensitiveData;

/** 安装页只展示应用级任务快照；页面销毁不影响后台任务、取消信号或结果。 */
public class InstallFragment extends Fragment {
    private static final int[] STEP_IDS = {R.id.install_step1, R.id.install_step2, R.id.install_step3,
            R.id.install_step4, R.id.install_step5, R.id.install_step6};
    private static final int[] STATE_IDS = {R.id.install_state1, R.id.install_state2, R.id.install_state3,
            R.id.install_state4, R.id.install_state5, R.id.install_state6};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private InstallRepository repository;
    private long shownRevision = -1;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (getView() == null || !isResumed()) return;
            render(); handler.postDelayed(this, 500);
        }
    };

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_install, container, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        repository = InstallRepository.get(requireContext()); shownRevision = -1;
        view.findViewById(R.id.install_status).setOnClickListener(v->BackgroundTasksActivity.open(requireContext()));
        var controller=com.deepseekharness.app.core.HarnessController.get(requireContext());
        ((TextView)view.findViewById(R.id.install_environment)).setText(controller.isEnvironmentReady()?com.deepseekharness.app.util.UiText.choose("可用","Available"):com.deepseekharness.app.util.UiText.choose("等待检查","Needs checking"));
        ((TextView)view.findViewById(R.id.install_app_version)).setText(com.deepseekharness.app.BuildConfig.VERSION_NAME);
        try{var descriptor=controller.proot().installedRuntimeDescriptor();var identity=descriptor==null?java.util.Map.of():descriptor.json();
            ((TextView)view.findViewById(R.id.install_base)).setText(String.valueOf(identity.getOrDefault("baseVersion","—")));
            ((TextView)view.findViewById(R.id.install_runtime)).setText(String.valueOf(identity.getOrDefault("dshVersion","—")));
        }catch(java.io.IOException unknown){((TextView)view.findViewById(R.id.install_base)).setText("—");((TextView)view.findViewById(R.id.install_runtime)).setText("—");}
        view.findViewById(R.id.install_managed_update).setOnClickListener(v->startActivity(new Intent(requireContext(),ExtractActivity.class).putExtra("review_only",true)));
        view.findViewById(R.id.install_environment_recovery).setOnClickListener(v->startActivity(new Intent(requireContext(),StartupRecoveryActivity.class)));

        view.findViewById(R.id.install_btn).setOnClickListener(v -> start(false, 0));
        view.findViewById(R.id.install_repair).setOnClickListener(v -> start(true, 0));
        view.findViewById(R.id.install_cancel).setOnClickListener(v -> { repository.cancel(); render(); });
        view.findViewById(R.id.install_copy).setOnClickListener(v -> copyLog());
        view.findViewById(R.id.install_uninstall).setOnClickListener(v -> confirmMaintenance());
        view.findViewById(R.id.install_factory_reset).setOnClickListener(v -> startActivity(
                new Intent(requireContext(), ExtractActivity.class)
                        .putExtra("review_only", true)
                        .putExtra("request_factory_reset", true)));
        for (int i = 0; i < STEP_IDS.length; i++) {
            final int step = i + 1;
            view.findViewById(STEP_IDS[i]).setOnClickListener(v -> showStep(step));
        }
        render();
    }
    @Override public void onViewStateRestored(@Nullable Bundle state) {
        super.onViewStateRestored(state);
        View view = getView();
        if (state == null && view != null) view.post(() -> {
            if (getView() == view) ((ScrollView) view.findViewById(R.id.install_scroll)).scrollTo(0, 0);
        });
    }
    @Override public void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroyView() {
        handler.removeCallbacks(refresh); shownRevision = -1; super.onDestroyView();
    }
    private void start(boolean repair, int step) {
        if (!repository.start(repair, step)) Toast.makeText(requireContext(),
                BackupTask.get(requireContext()).pendingMaintenance() ? com.deepseekharness.app.util.UiText.text("请先恢复中断维护，再检查或修复环境")
                        : com.deepseekharness.app.util.UiText.text("无法开始安装任务，请稍后重试或先完成正在进行的环境任务"), Toast.LENGTH_LONG).show();
        render();
    }
    private void showStep(int step) {
        InstallTask.Snapshot state = repository.snapshot();
        androidx.appcompat.app.AlertDialog.Builder dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.text(InstallTask.NAMES[step - 1])).setMessage(com.deepseekharness.app.util.UiText.text(state.details[step - 1]))
                .setNeutralButton(com.deepseekharness.app.util.UiText.text("关闭"), null);
        if (!state.busy() && !BackupManager.isEnvironmentTaskBusy() && !BackupTask.get(requireContext()).pendingMaintenance()) {
            dialog.setPositiveButton(com.deepseekharness.app.util.UiText.text("按需修复"), (d, which) -> start(true, step))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("重新检查"), (d, which) -> start(false, step));
        }
        dialog.show();
    }

    private void render() {
        View view = getView(); if (view == null || repository == null) return;
        InstallTask.Snapshot state = repository.snapshot();
        boolean environmentBusy = BackupManager.isEnvironmentTaskBusy();
        boolean pending = BackupTask.get(requireContext()).pendingMaintenance();
        ((TextView) view.findViewById(R.id.install_status)).setText(state.busy() ? state.phase
                : environmentBusy ? "环境任务进行中：" + EnvironmentTaskGate.activeKind()
                : pending ? com.deepseekharness.app.util.UiText.text("上次环境维护尚未完成，请先恢复原环境")
                : state.outcome == InstallTask.Outcome.IDLE ? com.deepseekharness.app.util.UiText.text("检查环境，或按需修复缺项") : state.phase);
        TextView progress = view.findViewById(R.id.install_progress);
        progress.setVisibility(state.outcome == InstallTask.Outcome.IDLE ? View.GONE : View.VISIBLE);
        progress.setText((state.repair ? com.deepseekharness.app.util.UiText.text("检查与按需修复") : com.deepseekharness.app.util.UiText.text("仅检查")) + com.deepseekharness.app.util.UiText.text(" · 总耗时 ") + state.elapsedSeconds + com.deepseekharness.app.util.UiText.text(" 秒")
                + (state.busy() ? com.deepseekharness.app.util.UiText.text(" · 当前阶段 ") + state.stageSeconds + com.deepseekharness.app.util.UiText.text(" 秒") : "")
                + (state.cancelRequested && state.busy() ? (state.cancellable ? com.deepseekharness.app.util.UiText.text("\n正在取消检查…") : com.deepseekharness.app.util.UiText.text("\n等待当前修复到达安全点，随后停止")) : ""));
        int completed = 0, total = state.selected == 0 ? 6 : 1;
        for (int i = 0; i < state.steps.length; i++) {
            if (state.selected == 0 || state.selected == i + 1) {
                if (state.steps[i] == InstallTask.Step.OK || state.steps[i] == InstallTask.Step.FAILED || state.steps[i] == InstallTask.Step.SKIPPED) completed++;
            }
            String label;
            switch (state.steps[i]) {
                case RUNNING: label = com.deepseekharness.app.util.UiText.text("进行中"); break;
                case OK: label = com.deepseekharness.app.util.UiText.text("成功"); break;
                case FAILED: label = com.deepseekharness.app.util.UiText.text("失败"); break;
                case SKIPPED: label = com.deepseekharness.app.util.UiText.text("未完成"); break;
                default: label = com.deepseekharness.app.util.UiText.text("未检查");
            }
            TextView status = view.findViewById(STATE_IDS[i]); status.setText(com.deepseekharness.app.util.UiText.text(label));
            int color = state.steps[i] == InstallTask.Step.OK ? R.color.ok : state.steps[i] == InstallTask.Step.FAILED ? R.color.err
                    : state.steps[i] == InstallTask.Step.RUNNING ? R.color.primary : R.color.text_muted;
            status.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), color));
            view.findViewById(STEP_IDS[i]).setContentDescription(com.deepseekharness.app.util.UiText.text(InstallTask.NAMES[i] + com.deepseekharness.app.util.UiText.text("，") + label + "，点击查看详情"));
        }
        ProgressBar bar = view.findViewById(R.id.install_progressbar);
        bar.setVisibility(state.busy() ? View.VISIBLE : View.GONE); bar.setIndeterminate(false); bar.setProgress(completed * 100 / total);
        for (int id : new int[]{R.id.install_btn, R.id.install_repair})
            view.findViewById(id).setEnabled(!state.busy() && !environmentBusy && !pending);
        Button maintenance = view.findViewById(R.id.install_uninstall);
        maintenance.setText(pending ? com.deepseekharness.app.util.UiText.text("恢复中断维护") : com.deepseekharness.app.util.UiText.text("备份并重建环境"));
        maintenance.setEnabled(!state.busy() && !environmentBusy);
        view.findViewById(R.id.install_factory_reset).setEnabled(!state.busy() && !environmentBusy);
        Button cancel = view.findViewById(R.id.install_cancel);
        cancel.setVisibility(state.busy() ? View.VISIBLE : View.GONE); cancel.setEnabled(!state.cancelRequested);
        cancel.setText(state.cancelRequested ? com.deepseekharness.app.util.UiText.text("等待停止…") : state.cancellable ? com.deepseekharness.app.util.UiText.text("取消检查") : com.deepseekharness.app.util.UiText.text("安全停止后续修复"));
        TextView error = view.findViewById(R.id.install_error);
        error.setVisibility(state.failure.isEmpty() ? View.GONE : View.VISIBLE); error.setText(com.deepseekharness.app.util.UiText.text(state.failure));
        view.findViewById(R.id.install_copy).setVisibility(state.log.isEmpty() ? View.GONE : View.VISIBLE);
        if (shownRevision != state.revision) {
            shownRevision = state.revision;
            TextView log = view.findViewById(R.id.install_log);
            LogScrollView scroll = view.findViewById(R.id.install_log_scroll);
            boolean follow = scroll.shouldFollowEnd();
            log.setText(state.log.isEmpty() ? com.deepseekharness.app.util.UiText.text("执行后将在此逐行显示脱敏输出。") : state.log);
            // fullScroll 会请求日志焦点并让外层页面滚到日志区域；这里只改变日志自身的滚动位置。
            if (follow) scroll.followEndAfterLayout();
        }
    }
    private void copyLog() {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(com.deepseekharness.app.util.UiText.text("DSHA 安装日志"), repository.snapshot().log));
                Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("已复制脱敏日志"), Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException error) { Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("复制失败，请稍后重试"), Toast.LENGTH_SHORT).show(); }
    }
    private void confirmMaintenance() {
        BackupTask maintenance = BackupTask.get(requireContext());
        if (repository.snapshot().busy() || maintenance.busy()) {
            Toast.makeText(requireContext(), "已有环境任务进行中，请等待完成", Toast.LENGTH_LONG).show(); return;
        }
        boolean recovery = maintenance.pendingMaintenance();
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(recovery ? com.deepseekharness.app.util.UiText.text("恢复中断维护？") : com.deepseekharness.app.util.UiText.text("备份并重建环境？"))
                .setMessage(recovery ? com.deepseekharness.app.util.UiText.text("先停止 Web，再回切旧环境；安全备份和失败的新环境均保留。")
                        : com.deepseekharness.app.util.UiText.text("会停止 Web 并中断正在执行的任务，完整备份并校验配置、会话和本地插件，再重建环境并恢复数据。\n\n")
                        + com.deepseekharness.app.util.UiText.text("备份失败不切换环境，后续失败回切旧环境；安全备份和旧环境会保留并占用额外空间。额外安装的系统软件留在旧环境中。"))
                .setPositiveButton(recovery ? com.deepseekharness.app.util.UiText.text("恢复原环境") : com.deepseekharness.app.util.UiText.text("备份并重建"), (dialog, which) -> {
                    if (!isAdded() || getView() == null) return;
                    try {
                        // BackupTask 在发布任务前原子取得同一全局锁，旧弹窗也不能绕过互斥。
                        if (!(recovery ? maintenance.recoverMaintenance() : maintenance.rebuild())) {
                            Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("已有环境任务或未完成维护，请稍后重试"), Toast.LENGTH_LONG).show(); return;
                        }
                        startActivity(new Intent(requireContext(), ExtractActivity.class)
                                .putExtra("data_task_id", maintenance.snapshot().id));
                    } catch (Throwable error) {
                        Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("无法打开维护页，可到数据与备份页查看任务：")
                                + SensitiveData.redact(String.valueOf(error)), Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).show();
    }
}
