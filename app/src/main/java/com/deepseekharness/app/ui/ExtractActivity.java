package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.DeviceSupportReport;
import com.deepseekharness.app.util.UiText;

/**
 * 运行环境维护详情页（与部署胶囊面板同一风格）：只展示应用级维护任务；旋转、返回或进程重建均不
 * 自动重复覆盖环境。自动开始与手动重试统一经 {@link RuntimeMaintenanceStarter}；维护成功文案不代替
 * 就绪检查，未就绪时留在本页、不自动跳转。设备审计会反射 render()、lastRender、automaticEntry 与
 * enter，并按历史 id 读取视图，名称不得改动。
 */
public class ExtractActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private BackupTask task;
  private HarnessController controller;
  private OnboardingKit kit;
  private TextView status, error, pill, lead, stageLine;
  private ViewGroup segments, stages;
  private View stageCard;
  private ProgressBar spinner, progress;
  private Button retry, enter, rescue;
  private ExtractFooter footer;
  private long taskId;
  private boolean automaticEntry;
  private boolean automaticDeclined;
  private boolean rebuildRequested;
  private String lastRender = "";

  /** 阶段条/阶段列表上一轮的形态键；形态没变就不重建，避免每秒刷新闪动（审计 B-01）。 */
  private String lastStageKey = "";

  private Boolean cachedReady;
  private long cachedReadyTaskId = -1;
  private BackupTaskState.Status cachedReadyStatus;
  private boolean cachedReadyBusy, cachedReadyPending;
  private long cachedReadyAt;
  private boolean cachedInstalled, cachedCanUpdate, cachedLatest;
  private DeviceSupportReport support;

  @Override
  protected void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_extract);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    controller = HarnessController.get(this);
    task = BackupTask.get(this);
    kit = new OnboardingKit(this);
    rebuildRequested =
        saved != null
            ? saved.getBoolean("rebuild_requested", false)
            : getIntent().getBooleanExtra("force_extract", false)
                || com.deepseekharness.app.util.BackupTaskKinds.is(
                    com.deepseekharness.app.util.BackupTaskKinds.REBUILD, task.snapshot().kind);
    bindViews();
    taskId =
        saved == null
            ? getIntent().getLongExtra("data_task_id", 0)
            : saved.getLong("data_task_id", 0);
    automaticEntry = saved != null && saved.getBoolean("automatic_entry", false);
    automaticDeclined = saved != null && saved.getBoolean("automatic_declined", false);
    if (task.maintenanceBusy()) taskId = task.snapshot().id;
    else if (saved == null
        && taskId == 0
        && !task.pendingMaintenance()
        && !getIntent().getBooleanExtra("review_only", false)) {
      boolean force = getIntent().getBooleanExtra("force_extract", false);
      if (!force
          && controller.isEnvironmentReady()
          && !com.deepseekharness.app.core.EnvironmentAccess.requiresMaintenanceBeforeStart(
              controller)) {
        proceed();
        return;
      }
      if (!force) {
        // 同一候选只自动尝试一次（attempted_identity 先记录再启动），逻辑与主界面共用。
        RuntimeMaintenanceStarter.Auto started = RuntimeMaintenanceStarter.autoStart(this);
        if (started == RuntimeMaintenanceStarter.Auto.STARTED) {
          taskId = task.snapshot().id;
          automaticEntry = true;
        } else if (started == RuntimeMaintenanceStarter.Auto.DECLINED) automaticDeclined = true;
      }
    }
    // 从受限主界面重新查看时也展示上次失败；自动重试判定已完成，不能因此阻止修复包升级。
    BackupTaskState.Snapshot previous = task.snapshot();
    if (taskId == 0
        && (!controller.isEnvironmentReady()
            || !com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(controller))
        && (previous.status == BackupTaskState.Status.FAILED
            || previous.status == BackupTaskState.Status.INTERRUPTED)) taskId = previous.id;
    render();
  }

  private void bindViews() {
    status = findViewById(R.id.extract_status);
    error = findViewById(R.id.extract_error);
    pill = findViewById(R.id.extract_pill);
    lead = findViewById(R.id.extract_lead);
    stageLine = findViewById(R.id.extract_stage_line);
    segments = findViewById(R.id.extract_segments);
    stages = findViewById(R.id.extract_stages);
    stageCard = findViewById(R.id.extract_stage_card);
    spinner = findViewById(R.id.extract_bar);
    progress = findViewById(R.id.extract_progress);
    ((TextView) findViewById(R.id.extract_title)).setText(UiText.text("运行环境维护"));
    // 底栏外观（主按钮 / 进度面板、受限入口、次要入口）由 ExtractFooter 渲染；点击行为仍在本页。
    footer = new ExtractFooter(this, kit);
    retry = footer.retry();
    enter = footer.enter();
    footer
        .emergency()
        .setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
    footer.logs().setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    footer
        .copy()
        .setOnClickListener(
            v -> {
              if (support == null) support = DeviceSupportProbe.sample(this);
              DeviceSupportProbe.copy(this, support);
              Toast.makeText(
                      this,
                      UiText.choose("已复制本机环境摘要。", "Copied the device summary."),
                      Toast.LENGTH_SHORT)
                  .show();
            });
    retry.setOnClickListener(
        v ->
            RuntimeMaintenanceStarter.confirmRetry(
                this,
                () -> {
                  taskId = task.snapshot().id;
                  automaticDeclined = false;
                  cachedReady = null;
                  render();
                },
                rebuildRequested));
    enter.setOnClickListener(v -> proceed());
    // 只读救援导出：不停止写入、不进入受限主界面，也不写就绪标记；后端在维护待恢复时仍允许 rescue。
    rescue = findViewById(R.id.extract_rescue);
    rescue.setText(UiText.text("导出可读副本"));
    rescue.setContentDescription(UiText.text("只读导出，不停止写入"));
    rescue.setOnClickListener(
        v ->
            startActivity(
                new Intent(this, NativeDataActivity.class)
                    .putExtra("data_mode", NativeDataActivity.MODE_RESCUE)));
    // 方案 A：内容宽度 ≥ 720dp 且有失败/未就绪说明时，阶段卡与说明两列并排（容器按自身宽度判定）。
    AdaptiveColumns columns = findViewById(R.id.extract_columns);
    columns.setSplitWidthDp(720);
    columns.setGutterDp(28);
    columns.setWeights(1f, 1.15f);
    // 宽屏（≥ 600dp）把正文与底栏收在居中列内：单列约 640dp，可并排时放宽到约 1040dp。
    if (kit.wide()) {
      int width = getResources().getDisplayMetrics().widthPixels;
      int column = getResources().getConfiguration().screenWidthDp >= 840 ? 1040 : 640;
      int side = Math.max(kit.dp(20), (width - kit.dp(column)) / 2);
      View content = findViewById(R.id.extract_content);
      content.setPadding(side, kit.dp(28), side, kit.dp(24));
      // 底栏按钮保持单列宽度，不随两列内容拉到 1040dp。
      int footerSide = Math.max(kit.dp(20), (width - kit.dp(640)) / 2);
      View footer = findViewById(R.id.extract_footer);
      footer.setPadding(footerSide, footer.getPaddingTop(), footerSide, footer.getPaddingBottom());
    }
  }

  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          if (!isFinishing() && !isDestroyed()) main.postDelayed(this, 1_000);
        }
      };

  private void render() {
    if (isFinishing() || isDestroyed()) return;
    BackupTaskState.Snapshot s = task.snapshot();
    boolean busy = task.maintenanceBusy(),
        // 未关闭的隔离试运行现场也须先“恢复中断维护”（再次更新会以 TRIAL_RECOVERY_REQUIRED 拒绝）。
        pending = task.pendingMaintenanceForUi() || RuntimeMaintenanceStarter.pendingTrial(this),
        executionBusy = task.busy();
    // 环境可继续运行（兼容 + 健康回执）且已装运行时就是当前候选，才算真正无事可做；
    // 只有前者的补丁级覆盖会把“进入主界面”错摆到主操作位。
    boolean runnable = environmentReady(s, busy, pending);
    boolean ready = runnable && cachedLatest;
    // 外部恢复成功后，旧失败快照不能继续当作本页当前任务；否则页面停在旧失败文案，
    // 用户必须再点一次重试才会看到“上一版运行时待更新”的标准状态。
    if (com.deepseekharness.app.util.RuntimeUpgradeState.clearStaleFailure(
        busy,
        pending,
        runnable,
        s.status == BackupTaskState.Status.FAILED || s.status == BackupTaskState.Status.INTERRUPTED,
        taskId,
        s.id)) {
      taskId = 0;
      automaticDeclined = false;
    }
    com.deepseekharness.app.util.DshVersionPolicy.Status versionStatus =
        com.deepseekharness.app.core.EnvironmentAccess.runtimeVersionStatus(controller);
    String renderKey =
        s.id
            + ":"
            + s.status
            + ":"
            + s.detail
            + ":"
            + busy
            + ":"
            + executionBusy
            + ":"
            + pending
            + ":"
            + ready
            + ":"
            + versionStatus
            + ":"
            + taskId
            + ":"
            + automaticDeclined
            + ":"
            + UiText.language();
    if (renderKey.equals(lastRender)) return;
    lastRender = renderKey;
    if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // 维护进行中才显示忙状态；插件查询、终端等通用锁不冒充数据维护。
    spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    progress.setVisibility(View.GONE);
    ((TextView) findViewById(R.id.extract_title)).setText(UiText.text("运行环境维护"));
    boolean mine = taskId != 0 && taskId == s.id;
    DeployStages.Phase phase =
        busy
            ? DeployStages.Phase.RUNNING
            : pending
                ? DeployStages.Phase.PENDING
                : mine
                    ? DeployStages.phase(s.status, false, false, ready)
                    : DeployStages.Phase.IDLE;
    DeployStages.View view = DeployMonitor.stagesView(s, phase, cachedInstalled);
    boolean failed = mine && view.phase == DeployStages.Phase.FAILED;
    boolean incomplete = mine && s.status == BackupTaskState.Status.SUCCEEDED && !ready && !busy;
    renderHeader(s, view, busy, pending);
    renderStages(view);
    renderMessages(
        s,
        view,
        failed,
        incomplete,
        pending,
        versionStatus,
        // 只有“已安装、兼容可用、但不是当前候选”才是补丁级陈旧；首次部署不走这段文案。
        !busy && !pending && !failed && !incomplete && cachedInstalled && !ready);
    RuntimeMaintenanceStarter.Retry kind =
        pending
            ? RuntimeMaintenanceStarter.Retry.RECOVER
            : !rebuildRequested && cachedCanUpdate
                ? RuntimeMaintenanceStarter.Retry.UPDATE
                : !cachedInstalled
                    ? RuntimeMaintenanceStarter.Retry.DEPLOY
                    : RuntimeMaintenanceStarter.Retry.REBUILD;
    com.deepseekharness.app.util.MaintenanceFooterPolicy.Model footerModel =
        com.deepseekharness.app.util.MaintenanceFooterPolicy.decide(
            busy, pending, failed, incomplete, !busy && !pending && ready);
    footer.render(
        footerModel, view, RuntimeMaintenanceStarter.retryLabel(kind, failed), !executionBusy);
    // 兑现说明里的“导出可读副本”：入口在说明上方；没有说明文字时也保留说明列显示入口。
    rescue.setVisibility(footerModel.rescue ? View.VISIBLE : View.GONE);
    rescue.setEnabled(!executionBusy);
    if (footerModel.rescue) findViewById(R.id.extract_focus).setVisibility(View.VISIBLE);
    if (com.deepseekharness.app.util.EnvironmentIdentity.mayAdvanceAfterMaintenance(
        automaticEntry, mine, s.status == BackupTaskState.Status.SUCCEEDED, busy, pending, ready)) {
      automaticEntry = false;
      proceed();
    }
  }

  private void renderHeader(
      BackupTaskState.Snapshot s, DeployStages.View view, boolean busy, boolean pending) {
    String statusText =
        busy
            ? com.deepseekharness.app.util.UiStateText.render(s.kind)
            : pending
                ? UiText.text("上次维护未完成，请先恢复原环境")
                : view.visible() ? view.label() : UiText.text("环境维护");
    FadeSwap.text(status, statusText);
    OnboardingKit.Tone tone = OnboardingKit.tone(view.phase);
    pill.setVisibility(view.visible() ? View.VISIBLE : View.GONE);
    if (view.visible()) {
      pill.setText(DeployCard.pillText(view));
      pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
      pill.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
      pill.setSingleLine(true);
      pill.setIncludeFontPadding(false);
      pill.setBackgroundResource(OnboardingKit.pillBackground(tone));
      pill.setTextColor(
          getColor(
              tone == OnboardingKit.Tone.NEUTRAL
                  ? R.color.shell_text_muted
                  : OnboardingKit.toneColor(tone)));
      pill.setPadding(kit.dp(9), kit.dp(3), kit.dp(9), kit.dp(3));
    }
    // 行内只留一句本次动作；完整说明走既有 InfoHint 弹层（不新增翻译键）。
    DeployStages.Mode mode =
        view.visible() && view.mode != DeployStages.Mode.GENERIC
            ? view.mode
            : DeployStages.Mode.GENERIC;
    InfoHint.bind(
        lead,
        DeployStages.title(mode),
        DeployTaskSheet.summary(mode),
        view.visible() && view.mode != DeployStages.Mode.GENERIC
            ? DeployTaskSheet.description(view.mode, false)
            : UiText.text("更新不移动个人数据；失败可恢复"));
  }

  private void renderStages(DeployStages.View view) {
    boolean show = view.visible() && view.total() > 0;
    stageCard.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show) return;
    stageLine.setText(DeployCard.line(view));
    // 阶段集合与当前步都没变时只更新那一行文字：不重建整块列表，也不丢滚动位置。
    String stageKey =
        view.mode
            + ":"
            + view.phase
            + ":"
            + view.current
            + ":"
            + view.total()
            + ":"
            + UiText.language();
    if (stageKey.equals(lastStageKey)) return;
    lastStageKey = stageKey;
    segments.removeAllViews();
    stages.removeAllViews();
    segments.addView(kit.segmentBar(view));
    stages.addView(kit.stageList(view));
    UiMotion.reveal(stageCard);
  }

  private void renderMessages(
      BackupTaskState.Snapshot s,
      DeployStages.View view,
      boolean failed,
      boolean incomplete,
      boolean pending,
      com.deepseekharness.app.util.DshVersionPolicy.Status versionStatus,
      boolean staleRuntime) {
    String message;
    boolean warn;
    if (failed) {
      message =
          UiText.format(
                  "未完成的操作：%s\n最后记录阶段：%s\n\n%s\n\n原件状态：尚未确认恢复完成。请查看记录后使用「恢复中断维护」；也可先点「导出可读副本」保存数据。",
                  com.deepseekharness.app.util.UiStateText.render(s.kind),
                  s.lastStage.isEmpty()
                      ? UiText.choose(
                          "旧记录未提供；请查看维护日志",
                          "Not available in the old record; inspect maintenance logs")
                      : com.deepseekharness.app.util.LogCompactor.compactReason(
                          com.deepseekharness.app.util.UiStateText.render(s.lastStage), 240),
                  com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail))
              + "\n\n"
              + UiText.text("任务未完成，请处理后重试");
      message += supportHint();
      warn = false;
    } else if (incomplete) {
      message =
          UiText.choose(
              "维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。",
              "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface.");
      warn = true;
    } else if (automaticDeclined) {
      message =
          UiText.choose(
              "本次自动维护未能启动。已有任务结束后可手动重试，不会反复启动维护。",
              "Automatic maintenance could not start. Retry manually after the current task ends; maintenance will not start repeatedly.");
      warn = true;
    } else if (pending) {
      message = DeployTaskSheet.message(view);
      warn = false;
    } else if (staleRuntime) {
      // 同一 dsh 版本、只有受管补丁与描述符变化时环境仍“兼容”，但旧树没有本 APK 的补丁。
      message =
          UiText.choose(
              "已安装的运行环境仍是上一版的受管运行时，本次内置补丁尚未落地。请先完成这次维护；也可以进入受限主界面查看日志与配置。",
              "The installed runtime still belongs to the previous build, so this build's managed patches are not in place yet. Finish this maintenance first, or enter the limited interface to review logs and settings.");
      warn = true;
    } else {
      message = "";
      warn = false;
    }
    String versionWarning =
        versionStatus == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER
            ? UiText.choose(
                "检测到个人数据由更高版本 DSH 写入。为避免旧版本误读，系统不会自动覆盖或修复当前环境；请先导出可读副本，或选择能够读取该数据的版本。",
                "Personal data was written by a newer DSH version. To avoid an unsafe downgrade, this version will not automatically overwrite or repair the environment. Export a readable copy or choose a compatible DSH version first.")
            : versionStatus == com.deepseekharness.app.util.DshVersionPolicy.Status.UNKNOWN
                ? UiText.choose(
                    "无法确认已安装运行时的数据代次。系统不会自动覆盖个人数据；请导出可读副本并确认兼容版本后再维护。",
                    "The installed runtime's data generation could not be verified. Personal data will not be overwritten automatically; export a readable copy and confirm a compatible version before maintenance.")
                : "";
    if (!versionWarning.isEmpty()) {
      message = message.isEmpty() ? versionWarning : message + "\n\n" + versionWarning;
      warn = true;
    }
    error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    // 页内不再内嵌维护记录：没有说明时整列隐藏，宽屏下阶段卡占满内容宽度。
    findViewById(R.id.extract_focus).setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    error.setText(message);
    error.setBackgroundResource(warn ? R.drawable.bg_onb_hint_warn : R.drawable.bg_onb_hint_bad);
    error.setTextColor(getColor(warn ? R.color.shell_warn : R.color.shell_bad));
  }

  private String supportHint() {
    if (support == null) support = DeviceSupportProbe.sample(this);
    if (!support.showHint())
      return "\n\n"
          + String.format(
              java.util.Locale.ROOT, UiText.choose("本机环境：%s", "Device: %s"), support.copyText);
    return "\n\n" + support.conclusion() + "\n" + support.hint() + "\n\n" + support.copyText;
  }

  /**
   * 就绪检查会读取并解析运行时描述、健康回执和 Bash ELF。维护页每秒刷新进度，但这些文件只会在
   * 任务代次/状态变化时改变；逐帧重复检查会让失败页在静止时仍持续分配对象并触发 GC。状态变化
   * 立即重查，闲置时每 15 秒兜底重查。安装状态与可否原位更新随同一缓存刷新（决定重试文案）。
   */
  private boolean environmentReady(
      BackupTaskState.Snapshot snapshot, boolean busy, boolean pending) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (cachedReady == null
        || cachedReadyTaskId != snapshot.id
        || cachedReadyStatus != snapshot.status
        || cachedReadyBusy != busy
        || cachedReadyPending != pending
        || now - cachedReadyAt >= 15_000) {
      cachedReady = controller.isEnvironmentReady();
      cachedInstalled = controller.proot().isEnvironmentInstalled();
      cachedCanUpdate = controller.proot().canUpdateManagedRuntime();
      // 兼容且健康只说明旧树还能跑；补丁级覆盖还要看已装描述符是否就是当前候选。
      cachedLatest = com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(controller);
      cachedReadyTaskId = snapshot.id;
      cachedReadyStatus = snapshot.status;
      cachedReadyBusy = busy;
      cachedReadyPending = pending;
      cachedReadyAt = now;
    }
    return cachedReady;
  }

  private void proceed() {
    boolean limited =
        com.deepseekharness.app.util.RuntimeUpgradeState.limitedEntry(
            controller.isEnvironmentReady(),
            com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(controller),
            task.maintenanceBusy(),
            task.pendingMaintenance());
    if (limited)
      controller
          .config()
          .allowLimitedEntry(
              com.deepseekharness.app.core.EnvironmentAccess.limitedEntryKey(controller));
    Intent intent =
        FirstRunWebStart.mainIntent(
            this, getIntent().getBooleanExtra("first_setup", false) && !limited);
    intent.putExtra("limited_entry", limited);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity(intent);
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putLong("data_task_id", taskId);
    state.putBoolean("automatic_entry", automaticEntry);
    state.putBoolean("automatic_declined", automaticDeclined);
    state.putBoolean("rebuild_requested", rebuildRequested);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onResume() {
    super.onResume();
    cachedReady = null;
    // 从应急 DSH 的「恢复中断维护」返回时，外部恢复不会经过本页的 BackupTask；立即丢弃
    // 维护记录的 UI 缓存，避免恢复完成后仍沿用恢复前的结果把页面停在“中断维护”。
    task.refreshPendingForUi();
    main.post(refresh);
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(refresh);
    super.onPause();
  }
}
