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
    private Button retry, enter, format;
    private long taskId;
    private boolean automaticEntry;
    private boolean automaticDeclined;
    private boolean rebuildRequested;
    private boolean freshStartScheduled;
    private String lastRender = "";
    private Boolean cachedReady;
    private long cachedReadyTaskId = -1;
    private BackupTaskState.Status cachedReadyStatus;
    private boolean cachedReadyBusy, cachedReadyPending;
    private long cachedReadyAt;

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_extract);
        controller = HarnessController.get(this); task = BackupTask.get(this);
        rebuildRequested = saved != null ? saved.getBoolean("rebuild_requested", false)
                : getIntent().getBooleanExtra("force_extract", false) || com.deepseekharness.app.util.UiText.text("重建环境").equals(com.deepseekharness.app.util.UiStateText.render(task.snapshot().kind));
        status = findViewById(R.id.extract_status); detail = findViewById(R.id.extract_detail);
        error = findViewById(R.id.extract_error); spinner = findViewById(R.id.extract_bar);
        progress = findViewById(R.id.extract_progress);
        ((TextView) findViewById(R.id.extract_title)).setText(com.deepseekharness.app.util.UiText.text("运行环境维护"));
        LinearLayout content = findViewById(R.id.extract_actions);
        retry = new androidx.appcompat.widget.AppCompatButton(this); enter = new androidx.appcompat.widget.AppCompatButton(this);
        format = new androidx.appcompat.widget.AppCompatButton(this);
        retry.setBackgroundResource(R.drawable.bg_btn_primary);retry.setTextColor(getColorStateList(R.color.button_primary_text));retry.setTextSize(14);retry.setAllCaps(false);
        enter.setBackgroundResource(R.drawable.bg_action_plain);enter.setTextColor(getColor(R.color.primary));enter.setTextSize(13);enter.setAllCaps(false);
        format.setBackgroundResource(R.drawable.bg_action_plain);format.setTextColor(getColor(R.color.err));format.setTextSize(13);format.setAllCaps(false);
        content.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        content.addView(enter, new LinearLayout.LayoutParams(-1, -2));
        Button logs = new androidx.appcompat.widget.AppCompatButton(this);
        logs.setText(com.deepseekharness.app.util.UiText.choose("查看本次维护记录", "View maintenance record"));
        logs.setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
        content.addView(logs, new LinearLayout.LayoutParams(-1, -2));
        content.addView(format, new LinearLayout.LayoutParams(-1, -2));
        retry.setOnClickListener(v -> {
            boolean recovery = task.pendingMaintenance();
            boolean update = !rebuildRequested && controller.proot().canUpdateManagedRuntime();
            new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(recovery ? com.deepseekharness.app.util.UiText.text("恢复原环境？") : update ? com.deepseekharness.app.util.UiText.text("更新运行时？") : com.deepseekharness.app.util.UiText.text("保护个人数据并重建？"))
                    .setMessage(recovery ? com.deepseekharness.app.util.UiText.text("先停止 Web，再回切旧环境，保留所有安全副本。")
                            : update ? com.deepseekharness.app.util.UiText.text("会停止 Web 和终端任务，再更新 dsh 和内置插件，个人目录、会话和配置保持原位。验证失败回切原运行时。")
                            : com.deepseekharness.app.util.UiText.text("将停止 Web 和终端，只备份对话、附件、配置、插件与个人项目，不打包 Ubuntu / Node 系统。数据保护失败不切换环境，重建失败保留原环境。"))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("继续"), (d, w) -> {
                        if (recovery ? task.recoverMaintenance() : rebuildRequested ? task.rebuild() : task.updateEnvironment()) { taskId = task.snapshot().id;automaticDeclined=false; }
                        render();
                    }).setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).show();
        });
        enter.setText(com.deepseekharness.app.util.UiText.text("进入主界面")); enter.setOnClickListener(v -> proceed());
        format.setText(com.deepseekharness.app.util.UiText.choose("格式化并全新开始", "Format and start fresh"));
        format.setOnClickListener(v -> confirmFormat());
        taskId = saved == null ? getIntent().getLongExtra("data_task_id", 0) : saved.getLong("data_task_id", 0);
        automaticEntry = saved != null && saved.getBoolean("automatic_entry", false);
        automaticDeclined = saved != null && saved.getBoolean("automatic_declined", false);
        if (task.maintenanceBusy()) taskId = task.snapshot().id;
        else if (saved == null && taskId == 0 && !task.pendingMaintenance() && !getIntent().getBooleanExtra("review_only", false)) {
            boolean force = getIntent().getBooleanExtra("force_extract", false);
            boolean latest=com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(controller);
            if (!force && controller.isEnvironmentReady() && !com.deepseekharness.app.core.EnvironmentAccess.shouldAttemptRuntimeUpdate(controller)) { proceed(); return; }
            android.content.SharedPreferences attempts = getSharedPreferences("dsha_environment_upgrade", MODE_PRIVATE);
            String attemptKey="";
            try{attemptKey="runtime:"+controller.proot().expectedRuntimeDescriptor().id();}catch(java.io.IOException invalid){ }
            if (!force && com.deepseekharness.app.util.EnvironmentIdentity.shouldAutoStart(
                    controller.isEnvironmentReady()&&latest, task.busy(), task.pendingMaintenance(), attemptKey,
                    attempts.getString("attempted_identity", ""))) {
                // 先记一次尝试再启动应用级任务；旋转、失败和进程重建都不会重复覆盖旧环境。
                if (attempts.edit().putString("attempted_identity", attemptKey).commit()) {
                    if (task.updateEnvironment()) { taskId = task.snapshot().id; automaticEntry = true; }
                    else automaticDeclined = true;
                }
            }
        }
        // 从受限主界面重新查看时也展示上次失败；自动重试判定已完成，不能因此阻止修复包升级。
        BackupTaskState.Snapshot previous=task.snapshot();
        if(taskId==0 && !controller.isEnvironmentReady()
                && (previous.status==BackupTaskState.Status.FAILED || previous.status==BackupTaskState.Status.INTERRUPTED))
            taskId=previous.id;
        render();
        if (saved == null && getIntent().getBooleanExtra("request_factory_reset", false))
            main.post(this::confirmFormat);
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); if (!isFinishing() && !isDestroyed()) main.postDelayed(this, 1_000); }
    };
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        BackupTaskState.Snapshot s = task.snapshot();
        boolean busy = task.maintenanceBusy(), pending = task.pendingMaintenanceForUi(), executionBusy=task.busy();
        boolean ready = environmentReady(s, busy, pending);
        boolean formatTask=task.isFactoryReset(taskId), formatComplete=task.isCompletedFactoryReset(taskId);
        String renderKey = s.id + ":" + s.status + ":" + s.detail + ":" + busy + ":" + executionBusy + ":" + pending + ":" + ready + ":" + taskId + ":" + formatComplete;
        if (renderKey.equals(lastRender)) return;
        lastRender = renderKey;
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); progress.setIndeterminate(true);
        ((TextView)findViewById(R.id.extract_title)).setText(formatTask?com.deepseekharness.app.util.UiText.choose("格式化 DSHA","Format DSHA"):com.deepseekharness.app.util.UiText.text("运行环境维护"));
        status.setText(formatComplete?com.deepseekharness.app.util.UiText.choose("格式化完成","Formatting complete"):
                formatTask&&busy?com.deepseekharness.app.util.UiText.choose("正在格式化 DSHA","Formatting DSHA"):
                busy ? com.deepseekharness.app.util.UiStateText.render(s.kind) : pending ? com.deepseekharness.app.util.UiText.text("上次维护未完成，请先恢复原环境") : com.deepseekharness.app.util.UiText.text("环境维护"));
        boolean mine = taskId != 0 && taskId == s.id;
        detail.setVisibility(View.VISIBLE);
        detail.setText(mine ? com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail) : com.deepseekharness.app.util.UiText.text("相同基础环境只更新 dsh 与内置插件，个人数据保持原位；基础环境变更时先保护数据再重建。验证失败可恢复原环境。"));
        boolean failed = mine && (s.status == BackupTaskState.Status.FAILED || s.status == BackupTaskState.Status.INTERRUPTED);
        if (failed) detail.setText(com.deepseekharness.app.util.UiText.choose("未完成的操作：", "Incomplete operation: ")
                + com.deepseekharness.app.util.UiStateText.render(s.kind)
                + "\n" + com.deepseekharness.app.util.UiText.choose("最后记录阶段：", "Last recorded stage: ")
                + (s.lastStage.isEmpty() ? com.deepseekharness.app.util.UiText.choose("旧记录未提供；请查看维护日志", "Not available in the old record; inspect maintenance logs") : com.deepseekharness.app.util.UiStateText.render(s.lastStage))
                + "\n\n" + com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail)
                + "\n\n" + com.deepseekharness.app.util.UiText.choose("原件状态：尚未确认恢复完成。请查看记录后使用“恢复中断维护”；也可进入受限主界面导出可读副本。", "Original state: recovery has not been confirmed. Inspect the record and use Recover interrupted maintenance, or enter the limited interface to export readable copies."));
        boolean incomplete=mine&&s.status==BackupTaskState.Status.SUCCEEDED&&!ready&&!formatTask;
        error.setVisibility(failed||incomplete||automaticDeclined ? View.VISIBLE : View.GONE);
        error.setText(incomplete?com.deepseekharness.app.util.UiText.choose("维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。", "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface."):
                automaticDeclined?com.deepseekharness.app.util.UiText.choose("本次自动维护未能启动。已有任务结束后可手动重试，不会反复启动维护。", "Automatic maintenance could not start. Retry manually after the current task ends; maintenance will not start repeatedly."):
                failed ? com.deepseekharness.app.util.UiText.text("任务未完成。请按上方原因处理后重试；本页不会自动覆盖环境。") : "");
        retry.setText(pending ? com.deepseekharness.app.util.UiText.text("恢复中断维护") : !rebuildRequested && controller.proot().canUpdateManagedRuntime() ? com.deepseekharness.app.util.UiText.text("更新运行时") : com.deepseekharness.app.util.UiText.text("保护数据并重建")); retry.setEnabled(!executionBusy);
        retry.setVisibility(formatTask?View.GONE:View.VISIBLE);
        enter.setVisibility(formatTask&&(busy||formatComplete)?View.GONE:View.VISIBLE);
        enter.setText(!busy && !pending && ready ? com.deepseekharness.app.util.UiText.text("进入主界面") : com.deepseekharness.app.util.UiText.text("进入受限主界面 · 查看日志与配置"));
        format.setVisibility(formatTask&&(busy||formatComplete)?View.GONE:View.VISIBLE);format.setEnabled(!executionBusy);
        if(formatComplete&&!freshStartScheduled){
            freshStartScheduled=true;
            main.postDelayed(()->{
                if(isFinishing()||isDestroyed())return;
                if(task.completeFactoryReset(taskId)||!controller.config().isWelcomed()){
                    // 系统清除数据过去靠进程重启恢复默认语言/主题；应用内格式化需显式重载。
                    LanguageController.apply(this);ThemeController.apply(this);
                    Intent welcome=new Intent(this,WelcomeActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(welcome);finish();
                }else{
                    freshStartScheduled=false;lastRender="";render();
                }
            },900);
        }
        if (com.deepseekharness.app.util.EnvironmentIdentity.mayAdvanceAfterMaintenance(automaticEntry,mine,s.status==BackupTaskState.Status.SUCCEEDED,busy,pending,ready)) { automaticEntry=false;proceed(); }
    }
    /**
     * 就绪检查会读取并解析运行时描述、健康回执和 Bash ELF。维护页每 500 ms 刷新
     * 进度，但这些文件只会在任务代次/状态变化时改变；逐帧重复检查会让失败页在
     * 静止时仍持续分配对象并触发 GC。状态变化立即重查，闲置时每 15 秒兜底重查。
     */
    private boolean environmentReady(BackupTaskState.Snapshot snapshot, boolean busy, boolean pending) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedReady == null || cachedReadyTaskId != snapshot.id || cachedReadyStatus != snapshot.status
                || cachedReadyBusy != busy || cachedReadyPending != pending || now - cachedReadyAt >= 15_000) {
            cachedReady = controller.isEnvironmentReady();
            cachedReadyTaskId = snapshot.id;
            cachedReadyStatus = snapshot.status;
            cachedReadyBusy = busy;
            cachedReadyPending = pending;
            cachedReadyAt = now;
        }
        return cachedReady;
    }
    private void confirmFormat() {
        new DshaDialogBuilder(this)
                .setTitle(com.deepseekharness.app.util.UiText.choose("格式化并全新开始？", "Format and start fresh?"))
                .setMessage(com.deepseekharness.app.util.UiText.choose(
                        "这会永久删除 DSHA 的运行环境、会话、配置、API Key、插件和本机自动备份，并尝试清理旧版 Documents/dshdata。公共或外置目录不可访问时会记录提示，不会阻止私有数据完成格式化；手动导出的备份与其他个人目录会保留。",
                        "This permanently deletes the DSHA runtime, conversations, configuration, API key, plugins, and local automatic backups, and attempts to remove legacy Documents/dshdata. Unavailable public or external storage is reported without blocking private-data formatting. Exported backups and other personal folders stay untouched."))
                .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null)
                .setPositiveButton(com.deepseekharness.app.util.UiText.choose("继续", "Continue"), (dialog, which) -> confirmFormatFinal())
                .show();
    }
    private void confirmFormatFinal() {
        new DshaDialogBuilder(this)
                .setTitle(com.deepseekharness.app.util.UiText.choose("最后确认", "Final confirmation"))
                .setMessage(com.deepseekharness.app.util.UiText.choose(
                        "格式化不可撤销。确认后会展示清理过程，完成时直接返回欢迎页并重新准备环境。",
                        "Formatting cannot be undone. Progress will be shown, then DSHA will return directly to welcome and prepare a fresh environment."))
                .setNegativeButton(com.deepseekharness.app.util.UiText.choose("返回", "Back"), null)
                .setPositiveButton(com.deepseekharness.app.util.UiText.choose("删除全部并格式化", "Delete all and format"), (dialog, which) -> {
                    if (task.factoryReset()) { taskId = task.snapshot().id; automaticEntry = false; automaticDeclined = false; }
                    else new DshaDialogBuilder(this)
                            .setTitle(com.deepseekharness.app.util.UiText.choose("暂时无法格式化", "Cannot format yet"))
                            .setMessage(com.deepseekharness.app.util.UiText.choose("另一个任务正在运行，请结束后重试。", "Another task is running. Try again after it finishes."))
                            .setPositiveButton(com.deepseekharness.app.util.UiText.choose("知道了", "OK"), null).show();
                    render();
                }).show();
    }
    private void proceed() {
        boolean limited = task.maintenanceBusy() || task.pendingMaintenance() || !controller.isEnvironmentReady();
        if (limited) controller.config().allowLimitedEntry(controller.proot().environmentIdentity());
        Intent intent = new Intent(this,getIntent().getBooleanExtra("first_setup",false)&&!limited?ModelSetupActivity.class:MainActivity.class);
        intent.putExtra("first_run",getIntent().getBooleanExtra("first_setup",false));
        intent.putExtra("limited_entry", limited);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent); finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("data_task_id", taskId); state.putBoolean("automatic_entry", automaticEntry);
        state.putBoolean("automatic_declined", automaticDeclined);
        state.putBoolean("rebuild_requested", rebuildRequested); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); cachedReady=null; main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
