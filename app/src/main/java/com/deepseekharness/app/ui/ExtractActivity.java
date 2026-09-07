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

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_extract);
        controller = HarnessController.get(this); task = BackupTask.get(this);
        status = findViewById(R.id.extract_status); detail = findViewById(R.id.extract_detail);
        error = findViewById(R.id.extract_error); spinner = findViewById(R.id.extract_bar);
        progress = findViewById(R.id.extract_progress);
        ((TextView) findViewById(R.id.extract_title)).setText("运行环境维护");
        LinearLayout content = (LinearLayout) status.getParent();
        retry = new Button(this); enter = new Button(this);
        content.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        content.addView(enter, new LinearLayout.LayoutParams(-1, -2));
        retry.setOnClickListener(v -> {
            boolean recovery = task.pendingMaintenance();
            new android.app.AlertDialog.Builder(this).setTitle(recovery ? "恢复原环境？" : "备份并重建环境？")
                    .setMessage(recovery ? "先停止 Web，再回切旧环境，保留所有安全副本。"
                            : "会停止 Web 并中断正在执行的任务，创建并校验安全备份后重建环境。备份失败不切换环境，后续失败回切旧环境。")
                    .setPositiveButton("继续", (d, w) -> {
                        if (recovery ? task.recoverMaintenance() : task.rebuild()) taskId = task.snapshot().id;
                        render();
                    }).setNegativeButton("取消", null).show();
        });
        enter.setText("进入主界面"); enter.setOnClickListener(v -> proceed());
        taskId = saved == null ? getIntent().getLongExtra("data_task_id", 0) : saved.getLong("data_task_id", 0);
        if (task.busy()) taskId = task.snapshot().id;
        else if (saved == null && taskId == 0 && !task.pendingMaintenance()) {
            boolean force = getIntent().getBooleanExtra("force_extract", false);
            if (!force && controller.isEnvironmentReady()) { proceed(); return; }
            // 只有真正首次安装才自动开始；已有环境/旧 force intent 仍经过安全确认。
            if (!controller.proot().getRootfsDir().getParentFile().exists() && task.rebuild()) taskId = task.snapshot().id;
        }
        render();
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 500); }
    };
    private void render() {
        BackupTaskState.Snapshot s = task.snapshot();
        boolean busy = task.busy(), pending = task.pendingMaintenance();
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); progress.setIndeterminate(true);
        status.setText(busy ? s.kind : pending ? "上次维护未完成，请先恢复原环境" : "环境维护");
        boolean mine = taskId != 0 && taskId == s.id;
        detail.setVisibility(View.VISIBLE);
        detail.setText(mine ? s.detail : "重建前将停止 Web 并校验完整安全备份；失败时保留原环境。旧环境和备份会占用额外空间。");
        boolean failed = mine && (s.status == BackupTaskState.Status.FAILED || s.status == BackupTaskState.Status.INTERRUPTED);
        error.setVisibility(failed ? View.VISIBLE : View.GONE);
        error.setText(failed ? "任务未完成。请按上方原因处理后重试；本页不会自动覆盖环境。" : "");
        retry.setText(pending ? "恢复中断维护" : "备份并重建环境"); retry.setEnabled(!busy);
        enter.setVisibility(!busy && !pending && controller.isEnvironmentReady() ? View.VISIBLE : View.GONE);
    }
    private void proceed() {
        if (task.busy() || task.pendingMaintenance() || !controller.isEnvironmentReady()) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent); finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("data_task_id", taskId); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
