package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.InstallRepository;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

/**
 * 安装页只展示应用级任务快照；页面销毁不影响后台任务、取消信号或结果。
 *
 * <p>2026-11-01 压缩重构：状态卡（结论 + 版本标签 + 六段进度 + 主操作）→ 组件行内详情 → 维护与工具 → 可折叠日志。门禁规则与旧页一致：任务运行、其他环境任务或未完成维护时，检查与修复入口不可用。
 * 设备审计会反射 {@code repository} 与 {@code shownRevision}，并按 install_btn / install_state1..6 /
 * install_log / install_error 读取视图。
 */
public class InstallFragment extends Fragment {
  private static final int[] STEP_IDS = {
    R.id.install_step1,
    R.id.install_step2,
    R.id.install_step3,
    R.id.install_step4,
    R.id.install_step5,
    R.id.install_step6
  };
  private static final int[] STATE_IDS = {
    R.id.install_state1,
    R.id.install_state2,
    R.id.install_state3,
    R.id.install_state4,
    R.id.install_state5,
    R.id.install_state6
  };
  private static final int[] NUM_IDS = {
    R.id.install_num1,
    R.id.install_num2,
    R.id.install_num3,
    R.id.install_num4,
    R.id.install_num5,
    R.id.install_num6
  };
  private static final int[] SUB_IDS = {
    R.id.install_sub1,
    R.id.install_sub2,
    R.id.install_sub3,
    R.id.install_sub4,
    R.id.install_sub5,
    R.id.install_sub6
  };
  private static final int[] CHEV_IDS = {
    R.id.install_chev1,
    R.id.install_chev2,
    R.id.install_chev3,
    R.id.install_chev4,
    R.id.install_chev5,
    R.id.install_chev6
  };
  private static final String STATE_EXPANDED = "install_expanded_step";
  private static final String STATE_LOG = "install_log_override";

  private final Handler handler = new Handler(Looper.getMainLooper());
  private InstallRepository repository;
  private long shownRevision = -1;
  private String lastRenderKey = "";

  /** 行内展开的步骤序号（1–6），0 表示全部收起。 */
  private int expandedStep;

  /** 用户对日志面板的显式选择；null 表示按任务状态自动决定。 */
  private Boolean logOverride;

  private InstallTask.Outcome lastOutcome;
  private boolean environmentReady;

  /** 六段进度上一轮段位的 ordinal+1；0 表示尚未渲染，用于只给真正变化的段位做高亮。 */
  private final int[] segmentState = new int[6];

  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (getView() == null || !isResumed()) return;
          render();
          handler.postDelayed(this, 1_000);
        }
      };

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    View installRoot = inflater.inflate(R.layout.fragment_install, container, false);
    PressFeedback.attachButtons(installRoot);
    return installRoot;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    repository = InstallRepository.get(requireContext());
    shownRevision = -1;
    lastRenderKey = "";
    lastOutcome = null;
    if (state != null) {
      expandedStep = state.getInt(STATE_EXPANDED, 0);
      logOverride = state.containsKey(STATE_LOG) ? state.getBoolean(STATE_LOG) : null;
    }
    // 详情窗格或整页宽度 ≥ 680dp 时，状态卡与组件 / 维护与日志两列并排。
    AdaptiveColumns columns = view.findViewById(R.id.install_columns);
    columns.setSplitWidthDp(680);
    columns.setGutterDp(24);
    columns.setWeights(1f, 1f);
    var controller = com.deepseekharness.app.core.HarnessController.get(requireContext());
    environmentReady = controller.isEnvironmentReady();
    String base = "—", runtime = "—";
    try {
      var descriptor = controller.proot().installedRuntimeDescriptor();
      var identity = descriptor == null ? java.util.Map.of() : descriptor.json();
      base = String.valueOf(identity.getOrDefault("baseVersion", "—"));
      runtime = String.valueOf(identity.getOrDefault("dshVersion", "—"));
    } catch (java.io.IOException unknown) {
      // 描述读不到时只显示占位，不影响检查与修复入口。
    }
    bindChips(view, base, runtime, com.deepseekharness.app.BuildConfig.VERSION_NAME);

    view.findViewById(R.id.install_status)
        .setOnClickListener(v -> BackgroundTasksActivity.open(requireContext()));
    view.findViewById(R.id.install_managed_update)
        .setOnClickListener(
            v ->
                startActivity(
                    new Intent(requireContext(), ExtractActivity.class)
                        .putExtra("review_only", true)));
    view.findViewById(R.id.install_environment_recovery)
        .setOnClickListener(
            v -> startActivity(new Intent(requireContext(), StartupRecoveryActivity.class)));
    view.findViewById(R.id.install_storage_help)
        .setOnClickListener(
            v -> startActivity(new Intent(requireContext(), ColdSetupSpeedActivity.class)));
    ((TextView) view.findViewById(R.id.install_speed_title)).setText(UiText.text("首次安装测速"));
    ((TextView) view.findViewById(R.id.install_speed_sub)).setText(UiText.text("独立目录完整计时，不影响当前环境"));

    Button check = view.findViewById(R.id.install_btn);
    check.setText(UiText.choose("检查全部", "Check all"));
    check.setOnClickListener(v -> start(false, 0));
    Button repair = view.findViewById(R.id.install_repair);
    repair.setText(UiText.choose("按需修复", "Repair"));
    repair.setOnClickListener(v -> start(true, 0));
    Button recover = view.findViewById(R.id.install_recover);
    recover.setText(UiText.text("恢复中断维护"));
    recover.setOnClickListener(v -> confirmMaintenance());
    view.findViewById(R.id.install_cancel)
        .setOnClickListener(
            v -> {
              repository.cancel();
              render();
            });
    ((Button) view.findViewById(R.id.install_step_recheck)).setText(UiText.text("重新检查"));
    ((Button) view.findViewById(R.id.install_step_repair)).setText(UiText.choose("按需修复", "Repair"));
    view.findViewById(R.id.install_step_recheck)
        .setOnClickListener(v -> start(false, expandedStep));
    view.findViewById(R.id.install_step_repair).setOnClickListener(v -> start(true, expandedStep));
    view.findViewById(R.id.install_copy).setOnClickListener(v -> copyLog());
    view.findViewById(R.id.install_log_toggle)
        .setOnClickListener(
            v -> {
              logOverride = !logOpen(repository.snapshot());
              lastRenderKey = "";
              render();
            });
    view.findViewById(R.id.install_uninstall).setOnClickListener(v -> confirmMaintenance());
    for (int i = 0; i < STEP_IDS.length; i++) {
      final int step = i + 1;
      view.findViewById(STEP_IDS[i]).setOnClickListener(v -> toggleStep(step));
    }
    render();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt(STATE_EXPANDED, expandedStep);
    if (logOverride != null) state.putBoolean(STATE_LOG, logOverride);
  }

  @Override
  public void onViewStateRestored(@Nullable Bundle state) {
    super.onViewStateRestored(state);
    View view = getView();
    if (state == null && view != null)
      view.post(
          () -> {
            if (getView() == view)
              ((ScrollView) view.findViewById(R.id.install_scroll)).scrollTo(0, 0);
          });
  }

  @Override
  public void onResume() {
    super.onResume();
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }

  @Override
  public void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    handler.removeCallbacks(refresh);
    shownRevision = -1;
    lastRenderKey = "";
    super.onDestroyView();
  }

  private void start(boolean repair, int step) {
    if (!repository.start(repair, step))
      Toast.makeText(
              requireContext(),
              BackupTask.get(requireContext()).pendingMaintenance()
                  ? UiText.text("请先恢复中断维护，再检查或修复环境")
                  : UiText.text("无法开始安装，请稍后重试"),
              Toast.LENGTH_LONG)
          .show();
    render();
  }

  private void toggleStep(int step) {
    expandedStep = expandedStep == step ? 0 : step;
    lastRenderKey = "";
    render();
  }

  /** 日志面板：用户显式选择优先；否则运行中或失败时展开。 */
  private boolean logOpen(InstallTask.Snapshot state) {
    if (logOverride != null) return logOverride;
    return state.busy() || !state.failure.isEmpty();
  }

  private void render() {
    View view = getView();
    if (view == null || repository == null) return;
    InstallTask.Snapshot state = repository.snapshot();
    boolean environmentBusy = MaintenanceCoordinator.isExclusiveWorkBlocked();
    BackupTask maintenanceTask = BackupTask.get(requireContext());
    boolean pending = maintenanceTask.pendingMaintenanceForUi();
    if (lastOutcome != state.outcome) {
      // 新任务开始时交还自动展开；本页看着任务结束时保持日志可见，便于核对结果。
      if (state.outcome == InstallTask.Outcome.RUNNING) logOverride = null;
      else if (lastOutcome == InstallTask.Outcome.RUNNING) {
        logOverride = true;
        // 就绪检查会解析 Bash ELF，只在任务结束时重查，不随每秒刷新重复执行。
        environmentReady =
            com.deepseekharness.app.core.HarnessController.get(requireContext())
                .isEnvironmentReady();
      }
      lastOutcome = state.outcome;
    }
    String renderKey =
        state.revision
            + ":"
            + state.elapsedSeconds
            + ":"
            + state.stageSeconds
            + ":"
            + state.outcome
            + ":"
            + state.cancelRequested
            + ":"
            + environmentBusy
            + ":"
            + pending
            + ":"
            + expandedStep
            + ":"
            + logOverride
            + ":"
            + environmentReady;
    if (renderKey.equals(lastRenderKey)) return;
    lastRenderKey = renderKey;
    boolean gated = state.busy() || environmentBusy || pending;
    renderHero(view, state, environmentBusy, pending, gated);
    renderSteps(view, state, gated);
    renderMaintenance(view, state, environmentBusy, pending);
    renderLog(view, state);
  }

  private void renderHero(
      View view,
      InstallTask.Snapshot state,
      boolean environmentBusy,
      boolean pending,
      boolean gated) {
    int failed = 0, ok = 0;
    for (InstallTask.Step step : state.steps) {
      if (step == InstallTask.Step.FAILED) failed++;
      if (step == InstallTask.Step.OK) ok++;
    }
    String title;
    OnboardingKit.Tone tone;
    int icon;
    if (state.busy()) {
      title = state.repair ? UiText.text("正在检查并按需修复") : UiText.text("正在检查组件");
      tone = OnboardingKit.Tone.ACCENT;
      icon = R.drawable.ic_recovery_refresh;
    } else if (environmentBusy) {
      title = UiText.text("环境任务进行中");
      tone = OnboardingKit.Tone.ACCENT;
      icon = R.drawable.ic_recovery_refresh;
    } else if (pending) {
      title = UiText.text("维护未完成");
      tone = OnboardingKit.Tone.WARN;
      icon = R.drawable.ic_onb_alert;
    } else if (failed > 0) {
      title = UiText.format("%d 个组件需要处理", failed);
      tone = OnboardingKit.Tone.BAD;
      icon = R.drawable.ic_onb_x;
    } else if (!environmentReady) {
      title = UiText.text("等待检查");
      tone = OnboardingKit.Tone.WARN;
      icon = R.drawable.ic_onb_alert;
    } else if (ok == InstallTask.NAMES.length) {
      title = UiText.text("环境可用 · 全部组件正常");
      tone = OnboardingKit.Tone.GOOD;
      icon = R.drawable.ic_onb_check;
    } else {
      title = UiText.text("环境可用");
      tone = OnboardingKit.Tone.GOOD;
      icon = R.drawable.ic_onb_check;
    }
    UiMotion.crossFadeText((TextView) view.findViewById(R.id.install_environment), title);
    FrameLayout orb = view.findViewById(R.id.install_orb);
    orb.setBackgroundResource(orbBackground(tone));
    ImageView orbIcon = view.findViewById(R.id.install_orb_icon);
    orbIcon.setImageResource(icon);
    orbIcon.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext().getColor(OnboardingKit.toneColor(tone))));

    TextView status = view.findViewById(R.id.install_status);
    UiMotion.crossFadeText(
        status,
        state.busy()
            ? state.phase
            : environmentBusy
                ? "环境任务进行中：" + MaintenanceCoordinator.activeEnvironmentDescription()
                : pending
                    ? UiText.text("上次环境维护尚未完成，请先恢复原环境")
                    : state.outcome == InstallTask.Outcome.IDLE
                        ? UiText.text("检查环境，或按需修复缺项")
                        : state.phase);
    TextView progress = view.findViewById(R.id.install_progress);
    boolean showProgress = state.outcome != InstallTask.Outcome.IDLE;
    boolean progressWasHidden = progress.getVisibility() != View.VISIBLE;
    progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    if (showProgress) {
      String progressText =
          UiText.format(
              "%s · 总耗时 %s 秒%s%s",
              (state.repair ? UiText.text("检查与按需修复") : UiText.text("仅检查")),
              state.elapsedSeconds,
              (state.busy() ? UiText.format(" · 当前阶段 %s 秒", state.stageSeconds) : ""),
              (state.cancelRequested && state.busy()
                  ? (state.cancellable
                      ? UiText.text("\n正在取消检查…")
                      : UiText.text("\n等待当前修复到达安全点，随后停止"))
                  : ""));
      if (progressWasHidden) {
        progress.setText(progressText);
        UiMotion.reveal(progress);
      } else {
        // 秒表每秒刷新只改数字：直接落值；阶段/结论变化才 150ms 淡入。
        UiMotion.crossFadeTimerText(progress, progressText);
      }
    }

    // 六段进度：只覆盖本次选择的步骤，未执行的保持灰色；段位变化用 150ms 高亮。
    ViewGroup meter = view.findViewById(R.id.install_meter);
    boolean touched = state.outcome != InstallTask.Outcome.IDLE;
    meter.setVisibility(touched ? View.VISIBLE : View.GONE);
    for (int i = 0; i < meter.getChildCount() && i < state.steps.length; i++) {
      View segment = meter.getChildAt(i);
      boolean changed = segmentState[i] != state.steps[i].ordinal() + 1;
      segment.setBackgroundResource(segment(state.steps[i]));
      if (changed) {
        segmentState[i] = state.steps[i].ordinal() + 1;
        if (touched) UiMotion.flash(segment);
      }
    }

    view.findViewById(R.id.install_btn)
        .setVisibility(state.busy() || pending ? View.GONE : View.VISIBLE);
    view.findViewById(R.id.install_repair)
        .setVisibility(state.busy() || pending ? View.GONE : View.VISIBLE);
    for (int id : new int[] {R.id.install_btn, R.id.install_repair})
      view.findViewById(id).setEnabled(!gated);
    Button recover = view.findViewById(R.id.install_recover);
    recover.setVisibility(!state.busy() && pending ? View.VISIBLE : View.GONE);
    recover.setEnabled(!environmentBusy);
    Button cancel = view.findViewById(R.id.install_cancel);
    cancel.setVisibility(state.busy() ? View.VISIBLE : View.GONE);
    cancel.setEnabled(!state.cancelRequested);
    cancel.setText(
        state.cancelRequested
            ? UiText.text("等待停止…")
            : state.cancellable ? UiText.text("取消检查") : UiText.text("安全停止后续修复"));

    TextView error = view.findViewById(R.id.install_error);
    boolean showError = !state.failure.isEmpty();
    boolean errorWasHidden = error.getVisibility() != View.VISIBLE;
    error.setVisibility(showError ? View.VISIBLE : View.GONE);
    if (showError) {
      // UI 基准 ⑬：首屏只留短结论；发生了什么、影响/原因、下一步与错误码点开 ⓘ 看。
      InfoHint.bindOnSurface(
          error,
          UiText.text("失败"),
          UiText.text("失败"),
          com.deepseekharness.app.util.MaintenanceErrorText.render(state.failure));
      if (errorWasHidden) UiMotion.reveal(error);
    }
  }

  private void renderSteps(View view, InstallTask.Snapshot state, boolean gated) {
    int ok = 0;
    boolean any = false;
    for (int i = 0; i < state.steps.length; i++) {
      InstallTask.Step step = state.steps[i];
      if (step == InstallTask.Step.OK) ok++;
      if (step != InstallTask.Step.PENDING) any = true;
      String label;
      OnboardingKit.Tone tone;
      switch (step) {
        case RUNNING:
          label = UiText.text("进行中");
          tone = OnboardingKit.Tone.ACCENT;
          break;
        case OK:
          label = UiText.text("成功");
          tone = OnboardingKit.Tone.GOOD;
          break;
        case FAILED:
          label = UiText.text("失败");
          tone = OnboardingKit.Tone.BAD;
          break;
        case SKIPPED:
          label = UiText.text("未完成");
          tone = OnboardingKit.Tone.WARN;
          break;
        default:
          label = UiText.text("未检查");
          tone = OnboardingKit.Tone.NEUTRAL;
      }
      int ink =
          requireContext()
              .getColor(
                  tone == OnboardingKit.Tone.NEUTRAL
                      ? R.color.shell_text_muted
                      : OnboardingKit.toneColor(tone));
      TextView status = view.findViewById(STATE_IDS[i]);
      status.setText(label);
      status.setTextColor(ink);
      status.setBackgroundResource(OnboardingKit.pillBackground(tone));
      TextView number = view.findViewById(NUM_IDS[i]);
      number.setText(
          step == InstallTask.Step.OK
              ? "✓"
              : step == InstallTask.Step.FAILED ? "✕" : String.valueOf(i + 1));
      number.setTextColor(
          tone == OnboardingKit.Tone.NEUTRAL
              ? requireContext().getColor(R.color.shell_text_secondary)
              : ink);
      number.setBackgroundResource(OnboardingKit.pillBackground(tone));
      // 副标题只取详情首行；完整内容在行内展开区。
      TextView sub = view.findViewById(SUB_IDS[i]);
      String detail =
          step == InstallTask.Step.PENDING
              ? ""
              : firstLine(
                  com.deepseekharness.app.util.MaintenanceErrorText.render(state.details[i]));
      sub.setText(detail);
      sub.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
      boolean open = expandedStep == i + 1;
      view.findViewById(CHEV_IDS[i]).setRotation(open ? 90f : 0f);
      View row = view.findViewById(STEP_IDS[i]);
      row.setContentDescription(
          UiText.format("%s，%s，点击查看详情", UiText.text(InstallTask.NAMES[i]), label));
      androidx.core.view.ViewCompat.setStateDescription(
          row, open ? UiText.text("收起") : UiText.text("展开"));
    }
    ((TextView) view.findViewById(R.id.install_steps_count))
        .setText(any ? ok + "/" + state.steps.length : "");

    LinearLayout steps = view.findViewById(R.id.install_steps);
    View detail = view.findViewById(R.id.install_step_detail);
    if (expandedStep < 1 || expandedStep > STEP_IDS.length) {
      expandedStep = 0;
      detail.setVisibility(View.GONE);
      return;
    }
    // 详情区移到被展开行之后（行后紧跟的是分隔线或下一行）。
    View row = view.findViewById(STEP_IDS[expandedStep - 1]);
    int target = steps.indexOfChild(row) + 1;
    if (steps.indexOfChild(detail) != target) {
      steps.removeView(detail);
      steps.addView(detail, steps.indexOfChild(row) + 1);
    }
    detail.setVisibility(View.VISIBLE);
    ((TextView) view.findViewById(R.id.install_step_detail_text))
        .setText(
            com.deepseekharness.app.util.MaintenanceErrorText.render(
                state.details[expandedStep - 1]));
    // 与旧详情弹窗一致：任务运行、维护屏障或未完成维护时不提供单步操作。
    view.findViewById(R.id.install_step_detail_actions)
        .setVisibility(gated ? View.GONE : View.VISIBLE);
  }

  private void renderMaintenance(
      View view, InstallTask.Snapshot state, boolean environmentBusy, boolean pending) {
    View row = view.findViewById(R.id.install_uninstall);
    ((TextView) view.findViewById(R.id.install_uninstall_title))
        .setText(pending ? UiText.text("恢复中断维护") : UiText.text("备份并重建环境"));
    ((TextView) view.findViewById(R.id.install_uninstall_sub))
        .setText(pending ? UiText.text("回切旧环境，保留安全备份") : UiText.text("先完整备份再重建，失败自动回切"));
    boolean enabled = !state.busy() && !environmentBusy;
    row.setEnabled(enabled);
    row.setAlpha(enabled ? 1f : 0.45f);
  }

  private void renderLog(View view, InstallTask.Snapshot state) {
    boolean open = logOpen(state);
    LogScrollView scroll = view.findViewById(R.id.install_log_scroll);
    scroll.setVisibility(open ? View.VISIBLE : View.GONE);
    view.findViewById(R.id.install_log_chev).setRotation(open ? 90f : 0f);
    ((TextView) view.findViewById(R.id.install_log_title))
        .setText(open ? UiText.text("收起日志") : UiText.text("查看日志"));
    String last = lastLine(state.log);
    ((TextView) view.findViewById(R.id.install_log_last))
        .setText(last.isEmpty() ? UiText.text("执行后将在此逐行显示脱敏输出。") : last);
    View toggle = view.findViewById(R.id.install_log_toggle);
    androidx.core.view.ViewCompat.setStateDescription(
        toggle, open ? UiText.text("收起") : UiText.text("展开"));
    view.findViewById(R.id.install_copy)
        .setVisibility(state.log.isEmpty() ? View.GONE : View.VISIBLE);
    if (shownRevision != state.revision) {
      shownRevision = state.revision;
      TextView log = view.findViewById(R.id.install_log);
      boolean follow = scroll.shouldFollowEnd();
      log.setText(state.log.isEmpty() ? UiText.text("执行后将在此逐行显示脱敏输出。") : state.log);
      toggle.setContentDescription(
          UiText.format("%d 行", state.log.isEmpty() ? 0 : state.log.split("\n", -1).length));
      // fullScroll 会请求日志焦点并让外层页面滚到日志区域；这里只改变日志自身的滚动位置。
      if (follow && open) scroll.followEndAfterLayout();
    }
  }

  private static int segment(InstallTask.Step step) {
    switch (step) {
      case OK:
        return R.drawable.bg_onb_seg_ok;
      case FAILED:
        return R.drawable.bg_onb_seg_bad;
      case SKIPPED:
        return R.drawable.bg_onb_seg_warn;
      case RUNNING:
        return R.drawable.bg_onb_seg_done;
      default:
        return R.drawable.bg_onb_seg_todo;
    }
  }

  private static int orbBackground(OnboardingKit.Tone tone) {
    switch (tone) {
      case GOOD:
        return R.drawable.bg_onb_icon_good;
      case WARN:
        return R.drawable.bg_onb_hint_warn;
      case BAD:
        return R.drawable.bg_onb_hint_bad;
      default:
        return R.drawable.bg_onb_icon;
    }
  }

  private static String firstLine(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    int end = trimmed.indexOf('\n');
    return end < 0 ? trimmed : trimmed.substring(0, end).trim();
  }

  private static String lastLine(String log) {
    if (log == null || log.isEmpty()) return "";
    String[] lines = log.split("\n");
    for (int i = lines.length - 1; i >= 0; i--)
      if (!lines[i].trim().isEmpty()) return lines[i].trim();
    return "";
  }

  /** 版本标签：Ubuntu / dsh / App，点按复制完整值；长版本在中间省略，不再折行。 */
  private void bindChips(View view, String base, String runtime, String app) {
    FrameLayout host = view.findViewById(R.id.install_chips);
    host.removeAllViews();
    OnboardingKit kit = new OnboardingKit(requireContext());
    OnboardingKit.FlowRow row =
        new OnboardingKit.FlowRow(requireContext(), kit.dp(6), kit.dp(6), Gravity.START);
    row.addView(chip(kit, "Ubuntu", base, UiText.text("已复制")));
    row.addView(chip(kit, "dsh", runtime, UiText.text("已复制")));
    row.addView(chip(kit, "App", app, UiText.text("已复制应用版本")));
    host.addView(row, new FrameLayout.LayoutParams(-1, -2));
  }

  private TextView chip(OnboardingKit kit, String label, String value, String copied) {
    TextView view = new TextView(requireContext());
    SpannableStringBuilder text = new SpannableStringBuilder(label).append(' ');
    int start = text.length();
    text.append(value);
    text.setSpan(
        new ForegroundColorSpan(requireContext().getColor(R.color.shell_text)),
        start,
        text.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(
        new StyleSpan(android.graphics.Typeface.BOLD),
        start,
        text.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    view.setText(text);
    view.setTextSize(11.5f);
    view.setTextColor(requireContext().getColor(R.color.shell_text_secondary));
    view.setIncludeFontPadding(false);
    view.setSingleLine(true);
    view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    view.setBackgroundResource(R.drawable.bg_onb_pill_neutral);
    view.setPadding(kit.dp(10), kit.dp(5), kit.dp(10), kit.dp(5));
    view.setMinHeight(kit.dp(26));
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setContentDescription(label + " " + value);
    view.setOnClickListener(v -> copy(label, value, copied));
    return view;
  }

  private void copy(String label, String value, String done) {
    try {
      android.content.ClipboardManager clipboard =
          (android.content.ClipboardManager)
              requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      if (clipboard == null) return;
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
      Toast.makeText(requireContext(), done, Toast.LENGTH_SHORT).show();
    } catch (RuntimeException error) {
      Toast.makeText(requireContext(), UiText.text("复制失败，请稍后重试"), Toast.LENGTH_SHORT).show();
    }
  }

  private void copyLog() {
    try {
      android.content.ClipboardManager clipboard =
          (android.content.ClipboardManager)
              requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      if (clipboard != null) {
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                UiText.text("DSHA 安装日志"), repository.snapshot().log));
        Toast.makeText(requireContext(), UiText.text("已复制脱敏日志"), Toast.LENGTH_SHORT).show();
      }
    } catch (RuntimeException error) {
      Toast.makeText(requireContext(), UiText.text("复制失败，请稍后重试"), Toast.LENGTH_SHORT).show();
    }
  }

  private void confirmMaintenance() {
    BackupTask maintenance = BackupTask.get(requireContext());
    if (repository.snapshot().busy() || maintenance.busy()) {
      Toast.makeText(requireContext(), UiText.text("已有环境任务进行中，请等待完成"), Toast.LENGTH_LONG).show();
      return;
    }
    boolean recovery = maintenance.pendingMaintenance();
    // 重建需要勾选知情后才能确认；恢复中断维护只回切旧环境，保持一步确认。
    CheckBox acknowledge = null;
    if (!recovery) {
      acknowledge = new CheckBox(requireContext());
      acknowledge.setText(UiText.text("我了解会停止 Web 并占用额外空间"));
      acknowledge.setTextSize(13);
      acknowledge.setTextColor(requireContext().getColor(R.color.shell_text));
      acknowledge.setMinHeight(new OnboardingKit(requireContext()).dp(48));
    }
    var builder =
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
            .setTitle(recovery ? UiText.text("恢复中断维护？") : UiText.text("备份并重建环境？"))
            .setMessage(
                recovery
                    ? UiText.text("先停止 Web，再回切旧环境；安全备份和失败的新环境均保留。")
                    : UiText.text(
                        "会停止 Web 并中断正在执行的任务，完整备份并校验配置、会话和本地插件，再重建环境并恢复数据。\n\n备份失败不切换环境，后续失败回切旧环境；安全备份和旧环境会保留并占用额外空间。额外安装的系统软件留在旧环境中。"));
    if (acknowledge != null) {
      FrameLayout wrap = new FrameLayout(requireContext());
      int pad = new OnboardingKit(requireContext()).dp(20);
      wrap.setPadding(pad, 0, pad, 0);
      wrap.addView(acknowledge);
      builder.setView(wrap);
    }
    builder
        .setPositiveButton(
            recovery ? UiText.text("恢复原环境") : UiText.text("备份并重建"),
            (dialog, which) -> {
              if (!isAdded() || getView() == null) return;
              try {
                // BackupTask 在发布任务前原子取得同一全局锁，旧弹窗也不能绕过互斥。
                if (!(recovery ? maintenance.recoverMaintenance() : maintenance.rebuild())) {
                  Toast.makeText(requireContext(), UiText.text("稍后重试"), Toast.LENGTH_LONG).show();
                  return;
                }
                startActivity(
                    new Intent(requireContext(), ExtractActivity.class)
                        .putExtra("data_task_id", maintenance.snapshot().id));
              } catch (Throwable error) {
                Toast.makeText(
                        requireContext(),
                        UiText.format(
                            "无法打开维护页，可到数据与备份页查看任务：%s", SensitiveData.redact(String.valueOf(error))),
                        Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(UiText.text("取消"), null);
    androidx.appcompat.app.AlertDialog dialog = builder.show();
    // 全局确认策略可能已直接执行（未显示对话框），此时不再挂勾选门槛。
    if (acknowledge != null && dialog.isShowing()) {
      Button positive = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
      if (positive != null) {
        positive.setEnabled(false);
        acknowledge.setOnCheckedChangeListener((box, checked) -> positive.setEnabled(checked));
      }
    }
  }
}
