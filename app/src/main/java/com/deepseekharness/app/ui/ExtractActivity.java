package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskState;

/** 解压门禁只展示应用级维护任务；旋转、返回或进程重建均不自动重复覆盖环境。 */
public class ExtractActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BackupTask task;
    private HarnessController controller;
    private TextView status, detail, error;
    private ProgressBar spinner, progress;
    private Button retry, enter;
    private long taskId;
    private boolean automaticEntry;
    private boolean rebuildRequested;
    private String lastRender = "";

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_extract);
        controller = HarnessController.get(this); task = BackupTask.get(this);
        rebuildRequested = saved != null ? saved.getBoolean("rebuild_requested", false)
                : getIntent().getBooleanExtra("force_extract", false) || com.deepseekharness.app.util.UiText.text("重建环境").equals(task.snapshot().kind);
        status = findViewById(R.id.extract_status); detail = findViewById(R.id.extract_detail);
        error = findViewById(R.id.extract_error); spinner = findViewById(R.id.extract_bar);
        progress = findViewById(R.id.extract_progress);
        ((TextView) findViewById(R.id.extract_title)).setText(com.deepseekharness.app.util.UiText.text("运行环境维护"));
        LinearLayout content = (LinearLayout) status.getParent();
        retry = new Button(this); enter = new Button(this);
        content.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        content.addView(enter, new LinearLayout.LayoutParams(-1, -2));
        retry.setOnClickListener(v -> {
            boolean recovery = task.pendingMaintenance();
            boolean update = !rebuildRequested && controller.proot().canUpdateManagedRuntime();
            new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(recovery ? com.deepseekharness.app.util.UiText.text("恢复原环境？") : update ? com.deepseekharness.app.util.UiText.text("更新运行时？") : com.deepseekharness.app.util.UiText.text("备份并重建环境？"))
                    .setMessage(recovery ? com.deepseekharness.app.util.UiText.text("先停止 Web，再回切旧环境，保留所有安全副本。")
                            : update ? com.deepseekharness.app.util.UiText.text("会停止 Web 和终端任务，再更新 dsh 和内置插件，个人目录、会话和配置保持原位。验证失败回切原运行时。")
                            : com.deepseekharness.app.util.UiText.text("会停止 Web 并中断正在执行的任务，创建并校验安全备份后重建环境。备份失败不切换环境，后续失败回切旧环境。"))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("继续"), (d, w) -> {
                        if (recovery ? task.recoverMaintenance() : rebuildRequested ? task.rebuild() : task.updateEnvironment()) taskId = task.snapshot().id;
                        render();
                    }).setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).show();
        });
        enter.setText(com.deepseekharness.app.util.UiText.text("进入主界面")); enter.setOnClickListener(v -> proceed());
        taskId = saved == null ? getIntent().getLongExtra("data_task_id", 0) : saved.getLong("data_task_id", 0);
        automaticEntry = saved != null && saved.getBoolean("automatic_entry", false);
        if (task.busy()) taskId = task.snapshot().id;
        else if (saved == null && taskId == 0 && !task.pendingMaintenance() && !getIntent().getBooleanExtra("review_only", false)) {
            boolean force = getIntent().getBooleanExtra("force_extract", false);
            if (!force && controller.isEnvironmentReady()) { proceed(); return; }
            android.content.SharedPreferences attempts = getSharedPreferences("dsha_environment_upgrade", MODE_PRIVATE);
            String identity = controller.proot().environmentIdentity();
            long updatedAt = 0;
            try { updatedAt = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            String attemptKey = com.deepseekharness.app.util.EnvironmentIdentity.attemptKey(identity, updatedAt);
            if (!force && com.deepseekharness.app.util.EnvironmentIdentity.shouldAutoStart(
                    controller.isEnvironmentReady(), task.busy(), task.pendingMaintenance(), attemptKey,
                    attempts.getString("attempted_identity", ""))) {
                // 先记一次尝试再启动应用级任务；旋转、失败和进程重建都不会重复覆盖旧环境。
                if (attempts.edit().putString("attempted_identity", attemptKey).commit()) {
                    if (task.updateEnvironment()) { taskId = task.snapshot().id; automaticEntry = true; }
                    else attempts.edit().remove("attempted_identity").commit();
                }
            }
        }
        // 从受限主界面重新查看时也展示上次失败；自动重试判定已完成，不能因此阻止修复包升级。
        BackupTaskState.Snapshot previous=task.snapshot();
        if(taskId==0 && !controller.isEnvironmentReady()
                && (previous.status==BackupTaskState.Status.FAILED || previous.status==BackupTaskState.Status.INTERRUPTED))
            taskId=previous.id;
        render();
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); if (!isFinishing() && !isDestroyed()) main.postDelayed(this, 500); }
    };
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        BackupTaskState.Snapshot s = task.snapshot();
        boolean busy = task.busy(), pending = task.pendingMaintenance();
        String renderKey = s.id + ":" + s.status + ":" + s.detail + ":" + busy + ":" + pending + ":" + taskId;
        if (renderKey.equals(lastRender)) return;
        lastRender = renderKey;
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); progress.setIndeterminate(true);
        status.setText(busy ? com.deepseekharness.app.util.UiStateText.render(s.kind) : pending ? com.deepseekharness.app.util.UiText.text("上次维护未完成，请先恢复原环境") : com.deepseekharness.app.util.UiText.text("环境维护"));
        boolean mine = taskId != 0 && taskId == s.id;
        detail.setVisibility(View.VISIBLE);
        detail.setText(mine ? com.deepseekharness.app.util.UiStateText.render(s.detail) : com.deepseekharness.app.util.UiText.text("相同基础环境只更新 dsh 与内置插件，个人数据保持原位；基础环境变更时先保护数据再重建。验证失败可恢复原环境。"));
        boolean failed = mine && (s.status == BackupTaskState.Status.FAILED || s.status == BackupTaskState.Status.INTERRUPTED);
        error.setVisibility(failed ? View.VISIBLE : View.GONE);
        error.setText(failed ? com.deepseekharness.app.util.UiText.text("任务未完成。请按上方原因处理后重试；本页不会自动覆盖环境。") : "");
        retry.setText(pending ? com.deepseekharness.app.util.UiText.text("恢复中断维护") : !rebuildRequested && controller.proot().canUpdateManagedRuntime() ? com.deepseekharness.app.util.UiText.text("更新运行时") : com.deepseekharness.app.util.UiText.text("备份并重建环境")); retry.setEnabled(!busy);
        enter.setVisibility(View.VISIBLE);
        enter.setText(!busy && !pending && controller.isEnvironmentReady() ? com.deepseekharness.app.util.UiText.text("进入主界面") : com.deepseekharness.app.util.UiText.text("进入受限主界面 · 查看日志与配置"));
        if (automaticEntry && mine && s.status == BackupTaskState.Status.SUCCEEDED && !busy && !pending) proceed();
    }
    private void proceed() {
        boolean limited = task.busy() || task.pendingMaintenance() || !controller.isEnvironmentReady();
        if (limited) controller.config().allowLimitedEntry(controller.proot().environmentIdentity());
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("limited_entry", limited);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent); finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("data_task_id", taskId); state.putBoolean("automatic_entry", automaticEntry);
        state.putBoolean("rebuild_requested", rebuildRequested); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
