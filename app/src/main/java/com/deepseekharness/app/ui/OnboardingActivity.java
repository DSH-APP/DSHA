package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.DeviceSupportReport;
import com.deepseekharness.app.util.MaintenanceFooterPolicy;
import com.deepseekharness.app.util.OnboardingMotionPlan;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 首次引导：欢迎（含部署前检查）→ 用途 → 授权 → 完成。单 Activity，用代码构建视图并切换步骤。
 *
 * <p>启动契约：welcomed == false 只能进入本页；“开始部署”写入 welcomed 并启动应用级维护任务，
 * 部署在后台进行，进度由顶栏胶囊（手机）或左栏进度卡（宽 ≥ 600dp）显示。受限入口只登记本 APK
 * 的受限选择，绝不写就绪标记。旋转与分屏走正常重建：步骤与用途选择保存在 onSaveInstanceState 与偏好中。
 */
public final class OnboardingActivity extends AppCompatActivity {
  static final String PREFS = PermissionPanel.PREFS;
  static final String KEY_GOALS = "goals";
  private static final String KEY_STEP = "step";

  /**
   * 已在本引导里点过“开始部署”（或确认环境已就绪）。只用于引导内步骤恢复：welcomed 要等用户离开引导
   * 进入主界面时才写入，否则部署途中被清后台，下次启动会直接进主界面而未完成部署。
   */
  static final String KEY_DEPLOY_STARTED = "deploy_started";

  private static final String STATE_STEP = "onboarding_step";
  private static final String STATE_GOALS = "onboarding_goals";
  static final int WELCOME = 0, GOALS = 1, PERMS = 2, FINISH = 3, STEPS = 4;

  OnboardingKit kit;
  DeployMonitor monitor;
  PermissionPanel panel;
  final Set<String> goals = new LinkedHashSet<>();
  int step = WELCOME;
  DeployStages.View deploy = DeployStages.idle();
  Capped content, footer;
  private ScrollView scroll;
  private LinearLayout stepsBar, stepList;
  private TextView counter, sideCounter;
  private View back, brand;
  private DeployCapsule capsule;
  private DeployCard card;
  private String renderedKey = "";

  /** 本实例是否已显示过任一步骤，以及是否来自旋转 / 分屏重建：决定是否播放入场动效。 */
  private boolean shownOnce, recreated;

  private final OnBackPressedCallback backStep =
      new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
          show(step - 1);
        }
      };

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    ConfigStore config = new ConfigStore(this);
    // 已完成首次设置且环境可直接使用：转入主界面，避免旧任务栈回到引导。未完成设置时不做磁盘检查。
    if (config.isWelcomed() && OnboardingEnvironment.readyForMain(this)) {
      openMain(FirstRunWebStart.mainIntent(this, false));
      return;
    }
    kit = new OnboardingKit(this);
    ArrayList<String> restored = saved == null ? null : saved.getStringArrayList(STATE_GOALS);
    if (restored != null) {
      goals.add(PermissionGoals.BASE);
      for (String goal : PermissionGoals.goals()) if (restored.contains(goal)) goals.add(goal);
    } else goals.addAll(savedGoals(this));
    recreated = saved != null;
    if (saved != null) step = clamp(saved.getInt(STATE_STEP, WELCOME));
    else if (config.isWelcomed() || deployStarted())
      step = clamp(prefs().getInt(KEY_STEP, config.isWelcomed() ? FINISH : GOALS));
    // 没有在引导里开始过部署时不能越过第 1 步（进程被清后由偏好恢复到原步骤）。
    if (!config.isWelcomed() && !deployStarted()) step = WELCOME;
    getOnBackPressedDispatcher().addCallback(this, backStep);
    setContentView(buildShell());
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    panel = new PermissionPanel(this, goals, false);
    monitor = new DeployMonitor(this, this, view -> runOnUiThread(() -> onDeploy(view)));
    deploy = monitor.current();
    if (capsule != null) capsule.render(deploy);
    if (card != null) card.render(deploy);
    show(step, false);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (panel != null) panel.refresh();
    // 从系统设置返回后授权数可能变化：授权页底栏与完成页芯片重新读取。
    if (content != null && (step == PERMS || step == FINISH)) render(false);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putInt(STATE_STEP, step);
    state.putStringArrayList(STATE_GOALS, new ArrayList<>(goals));
    super.onSaveInstanceState(state);
  }

  // ---------------------------------------------------------------- 骨架

  private View buildShell() {
    boolean wide = kit.wide(), compact = kit.shortScreen();
    int pad = kit.dp(wide ? 28 : 20);
    LinearLayout main = kit.column();
    main.setBackgroundColor(kit.color(R.color.shell_bg));
    LinearLayout head = kit.row();
    head.setMinimumHeight(kit.dp(compact ? 40 : 52));
    head.setPadding(
        kit.dp(wide ? 12 : 8), kit.dp(compact ? 2 : 4), kit.dp(wide ? 22 : 16), kit.dp(2));
    FrameLayout left = new FrameLayout(this);
    left.setMinimumWidth(kit.dp(wide ? 48 : 64));
    back = backButton();
    left.addView(
        back, new FrameLayout.LayoutParams(kit.dp(48), kit.dp(48), Gravity.CENTER_VERTICAL));
    TextView name = kit.bold("DSHA", 18, R.color.shell_text);
    name.setPadding(kit.dp(10), 0, 0, 0);
    brand = name;
    if (!wide) left.addView(brand, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
    head.addView(left, new LinearLayout.LayoutParams(-2, -2));
    FrameLayout middle = new FrameLayout(this);
    if (!wide) {
      capsule = new DeployCapsule(this);
      capsule.setMaxWidthFraction(0.62f);
      capsule.setOnOpen(this::openTaskSheet);
      middle.addView(capsule, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }
    head.addView(middle, new LinearLayout.LayoutParams(0, -2, 1));
    counter = kit.text("", 11.5f, R.color.shell_text_muted);
    counter.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    counter.setGravity(Gravity.END);
    counter.setMinWidth(kit.dp(40));
    head.addView(counter);
    main.addView(head, OnboardingKit.matchWrap());
    if (!wide) {
      stepsBar = kit.row();
      stepsBar.setPadding(kit.dp(20), kit.dp(2), kit.dp(20), kit.dp(4));
      stepsBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      main.addView(stepsBar, OnboardingKit.matchWrap());
    }
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    FrameLayout holder = new FrameLayout(this);
    holder.setPadding(pad, kit.dp(compact ? 2 : wide ? 8 : 10), pad, kit.dp(wide ? 24 : 22));
    content = new Capped(this);
    holder.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
    scroll.addView(holder, new FrameLayout.LayoutParams(-1, -2));
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    FrameLayout foot = new FrameLayout(this);
    foot.setBackgroundResource(R.drawable.bg_onb_footer);
    foot.setPadding(
        pad, kit.dp(compact ? 7 : wide ? 12 : 10), pad, kit.dp(compact ? 7 : wide ? 14 : 12));
    footer = new Capped(this);
    foot.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
    main.addView(foot, OnboardingKit.matchWrap());
    if (!wide) return main;
    LinearLayout root = kit.row();
    root.setBackgroundColor(kit.color(R.color.shell_bg));
    root.addView(buildSide(), new LinearLayout.LayoutParams(sideWidth(), -1));
    View rule = new View(this);
    rule.setBackgroundColor(kit.color(R.color.shell_line));
    root.addView(rule, new LinearLayout.LayoutParams(kit.dp(1), -1));
    root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    return root;
  }

  private View backButton() {
    var button = new androidx.appcompat.widget.AppCompatImageButton(this);
    button.setImageResource(R.drawable.ic_ui_back);
    button.setImageTintList(ColorStateList.valueOf(kit.color(R.color.shell_text)));
    button.setBackgroundResource(R.drawable.bg_onb_ghost);
    button.setScaleType(ImageView.ScaleType.FIT_CENTER);
    button.setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12));
    button.setContentDescription(UiText.choose("返回上一步", "Back to the previous step"));
    button.setOnClickListener(v -> show(step - 1));
    return button;
  }

  /** 宽屏左栏：品牌、纵向步骤、部署进度卡、底部说明；内容过高时可滚动。 */
  private View buildSide() {
    boolean compact = kit.shortScreen();
    ScrollView holder = new ScrollView(this);
    holder.setFillViewport(true);
    holder.setBackgroundColor(kit.color(R.color.shell_surface));
    LinearLayout side = kit.column();
    side.setPadding(kit.dp(18), kit.dp(compact ? 14 : 22), kit.dp(18), kit.dp(18));
    int gap = compact ? 12 : 18;
    side.addView(kit.bold("DSHA", 22, R.color.shell_text));
    sideCounter = kit.text("", 12.5f, R.color.shell_text_muted);
    kit.add(side, sideCounter, 4);
    stepList = kit.column();
    kit.add(side, stepList, gap);
    card = new DeployCard(this);
    card.setOnOpen(this::openTaskSheet);
    kit.add(side, card, gap);
    View spacer = new View(this);
    side.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView note =
        kit.fine(
            UiText.choose(
                "授权全部可选，可随时在 设置 → 设备能力授权 中修改。",
                "All permissions are optional and can be changed anytime in Settings → Device permissions."));
    kit.add(side, note, gap);
    holder.addView(side, new FrameLayout.LayoutParams(-1, -2));
    return holder;
  }

  private int sideWidth() {
    int total = kit.dp(getResources().getConfiguration().screenWidthDp);
    return Math.max(kit.dp(240), Math.round(total * 0.3f));
  }

  /** 正文实际可用宽度（dp）：用于决定功能点与用途是否并排。 */
  int contentWidthDp(int maxDp) {
    int screen = getResources().getConfiguration().screenWidthDp;
    int pane =
        kit.wide() ? screen - Math.max(240, Math.round(screen * 0.3f)) - 1 - 56 : screen - 40;
    return Math.min(maxDp, Math.max(0, pane));
  }

  static String stepName(int index) {
    switch (index) {
      case WELCOME:
        return UiText.choose("欢迎", "Welcome");
      case GOALS:
        return UiText.choose("用途", "Purpose");
      case PERMS:
        return UiText.choose("授权", "Permissions");
      default:
        return UiText.choose("完成", "Finish");
    }
  }

  private View stepRow(int index) {
    LinearLayout row = kit.row();
    row.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
    boolean done = index < step, current = index == step;
    if (current) row.setBackgroundResource(R.drawable.bg_onb_step_row_cur);
    FrameLayout mark = new FrameLayout(this);
    mark.setBackgroundResource(
        done
            ? R.drawable.bg_onb_step_done
            : current ? R.drawable.bg_onb_step_cur : R.drawable.bg_onb_step_todo);
    if (done)
      mark.addView(
          kit.icon(R.drawable.ic_onb_check, R.color.shell_on_accent, 13),
          new FrameLayout.LayoutParams(kit.dp(13), kit.dp(13), Gravity.CENTER));
    else {
      TextView number =
          kit.bold(
              String.valueOf(index + 1),
              11,
              current ? R.color.shell_accent : R.color.shell_text_muted);
      number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
      mark.addView(number, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }
    row.addView(mark, new LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)));
    TextView label =
        current
            ? kit.bold(stepName(index), 14, R.color.shell_text)
            : kit.text(stepName(index), 14, R.color.shell_text_muted);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.leftMargin = kit.dp(10);
    row.addView(label, lp);
    String state =
        done
            ? UiText.choose("已完成", "Done")
            : current ? UiText.choose("当前步骤", "Current step") : UiText.choose("未开始", "Not started");
    row.setContentDescription(
        String.format(
            Locale.ROOT,
            UiText.choose("第 %1$d 步：%2$s，%3$s", "Step %1$d: %2$s, %3$s"),
            index + 1,
            stepName(index),
            state));
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return row;
  }

  /** 顶栏（返回 / 品牌 / 计数）、手机步骤条与宽屏左栏随步骤更新。 */
  private void updateChrome(OnboardingMotionPlan.Kind motion, int from) {
    boolean wide = kit.wide();
    back.setVisibility(step > WELCOME ? View.VISIBLE : wide ? View.INVISIBLE : View.GONE);
    if (!wide) brand.setVisibility(step == WELCOME ? View.VISIBLE : View.GONE);
    counter.setText((step + 1) + " / " + STEPS);
    counter.setContentDescription(
        String.format(
            Locale.ROOT, UiText.choose("第 %1$d 步，共 %2$d 步", "Step %1$d of %2$d"), step + 1, STEPS));
    if (stepsBar != null) {
      stepsBar.removeAllViews();
      for (int i = 0; i < STEPS; i++) {
        View segment = new View(this);
        segment.setBackgroundResource(
            i <= step ? R.drawable.bg_onb_seg_done : R.drawable.bg_onb_seg_todo);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, kit.dp(3), 1);
        if (i > 0) p.leftMargin = kit.dp(4);
        stepsBar.addView(segment, p);
      }
      OnboardingMotion.segments(stepsBar, motion, from, step);
    }
    if (stepList != null) {
      stepList.removeAllViews();
      for (int i = 0; i < STEPS; i++) kit.add(stepList, stepRow(i), i == 0 ? 0 : 3);
      if (stepList.getChildAt(step) instanceof android.view.ViewGroup row)
        OnboardingMotion.pop(row.getChildAt(0), motion);
      sideCounter.setText(
          String.format(
              Locale.ROOT,
              UiText.choose("首次引导 · %1$d / %2$d", "Setup · %1$d / %2$d"),
              step + 1,
              STEPS));
    }
  }

  // ---------------------------------------------------------------- 步骤切换与渲染

  void show(int target) {
    show(target, true);
  }

  private void show(int target, boolean announce) {
    if (content == null || isFinishing() || isDestroyed()) return;
    target = clamp(target);
    // 尚未点“开始部署”时不能越过第 1 步。
    if (target > WELCOME && !new ConfigStore(this).isWelcomed() && !deployStarted())
      target = WELCOME;
    boolean changed = target != step;
    int from = step;
    OnboardingMotionPlan.Kind motion =
        OnboardingMotionPlan.choose(
            UiMotion.animationsEnabled(this), recreated, !shownOnce, from, target);
    shownOnce = true;
    step = target;
    try {
      prefs().edit().putInt(KEY_STEP, step).apply();
    } catch (RuntimeException ignored) {
      // 只影响下次恢复到哪一步。
    }
    backStep.setEnabled(step > WELCOME);
    if (step == WELCOME) measureSpace();
    updateChrome(motion, from);
    renderedKey = "";
    render(true);
    if (changed || !announce) scroll.scrollTo(0, 0);
    // 只在真正的步骤切换（或首次入场）时播放；同一步原位刷新走 render(false)，不触发动效。
    OnboardingMotion.content(content, motion);
    OnboardingMotion.footer(footer, motion);
    if (announce)
      content.announceForAccessibility(
          String.format(
              Locale.ROOT,
              UiText.choose("第 %1$d 步，共 %2$d 步：%3$s", "Step %1$d of %2$d: %3$s"),
              step + 1,
              STEPS,
              stepName(step)));
  }

  /** force=false 时只在本步可见状态变化后重建，避免轮询打断滚动、焦点与 TalkBack。 */
  void render(boolean force) {
    if (content == null || isFinishing() || isDestroyed()) return;
    String key = renderKey();
    if (!force && key.equals(renderedKey)) return;
    renderedKey = key;
    content.setMax(kit.dp(step == PERMS ? 980 : 680));
    footer.setMax(kit.dp(step == PERMS ? 980 : 680));
    content.removeAllViews();
    footer.removeAllViews();
    switch (step) {
      case WELCOME:
        buildWelcome();
        break;
      case GOALS:
        buildGoals();
        break;
      case PERMS:
        buildPerms();
        break;
      default:
        buildFinish();
    }
  }

  /** 每步真正影响显示的状态；用途勾选在原位更新，只影响页脚，由 renderFooter 单独处理。 */
  private String renderKey() {
    StringBuilder key = new StringBuilder().append(step).append('|').append(UiText.language());
    switch (step) {
      case WELCOME:
        key.append(lowSpace()).append(started()).append(environmentReady());
        break;
      case GOALS:
        key.append(deploy.phase == DeployStages.Phase.RUNNING)
            .append(PermissionGoals.recommendedCount(goals));
        break;
      case PERMS:
        key.append(granted() > 0)
            .append(panel == null ? 0 : panel.recommendedDone())
            .append(new java.util.TreeSet<>(goals));
        break;
      default:
        key.append(finishState())
            .append(deploy.current)
            .append(deploy.detail)
            .append(deploy.lastStage)
            .append(grantedKey());
    }
    return key.toString();
  }

  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (isFinishing() || isDestroyed()) return;
          if (monitor != null) onDeploy(monitor.current());
          ui.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onStart() {
    super.onStart();
    ui.removeCallbacks(poll);
    ui.postDelayed(poll, 1000);
  }

  @Override
  protected void onStop() {
    ui.removeCallbacks(poll);
    super.onStop();
  }

  /** 部署快照回调（监视器只在内容变化时通知；本页另有 1 秒兜底，用于就绪检查与授权数变化）。 */
  void onDeploy(DeployStages.View view) {
    if (content == null || isFinishing() || isDestroyed()) return;
    DeployStages.Phase before = deploy.phase;
    // 监视器在内容未变时返回同一快照对象：兜底轮询不重复重绘胶囊与进度卡（避免打断动效与朗读）。
    boolean same = view != null && view == deploy;
    deploy = view == null ? DeployStages.idle() : view;
    if (capsule != null && !same) capsule.render(deploy);
    if (card != null && !same) card.render(deploy);
    // 部署期间保持屏幕常亮（原 ExtractActivity 行为）；结束、失败或未开始时清除。
    if (deploy.phase == DeployStages.Phase.RUNNING)
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (panel != null) panel.setEnvironmentReady(environmentReady());
    if (before != deploy.phase && deploy.visible())
      content.announceForAccessibility(deploy.label());
    render(false);
  }

  /** 是否已在本引导中开始过部署（进程被清后仍保留，用于恢复到原步骤）。 */
  boolean deployStarted() {
    try {
      return prefs().getBoolean(KEY_DEPLOY_STARTED, false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void markDeployStarted() {
    try {
      prefs().edit().putBoolean(KEY_DEPLOY_STARTED, true).commit();
    } catch (RuntimeException failure) {
      record("DEPLOY_STARTED", failure);
    }
  }

  boolean started() {
    return deploy.phase != DeployStages.Phase.IDLE;
  }

  /** 最近一次后台就绪检查（监视器缓存）；部署进行中一律按未就绪处理。 */
  boolean environmentReady() {
    return monitor != null
        && monitor.environmentReady()
        && deploy.phase != DeployStages.Phase.RUNNING
        && deploy.phase != DeployStages.Phase.PENDING;
  }

  int granted() {
    try {
      return panel == null ? 0 : panel.grantedCount();
    } catch (RuntimeException unknown) {
      return 0;
    }
  }

  private String grantedKey() {
    StringBuilder key = new StringBuilder();
    try {
      if (panel != null) for (String[] item : panel.summary()) key.append(item[1]);
    } catch (RuntimeException ignored) {
      // 授权状态读取失败时按未开启显示。
    }
    return key.toString();
  }

  void openTaskSheet() {
    if (monitor == null || isFinishing() || isDestroyed()) return;
    try {
      DeployTaskSheet.show(this, monitor, true, this::enterLimited);
    } catch (RuntimeException failure) {
      record("TASK_SHEET", failure);
    }
  }

  /** 环境已就绪：进入主界面，并由 FirstRunWebStart 在主界面就绪后自动启动一次 Web。 */
  void enterMain() {
    new ConfigStore(this).setWelcomed(true);
    openMain(FirstRunWebStart.mainIntent(this, true));
  }

  /**
   * 受限主界面：沿用 ExtractActivity.proceed() 的受限分支，只登记本 APK 的受限选择，
   * 绝不写就绪标记；终端、Web 与插件仍须通过环境门禁。
   */
  void enterLimited() {
    new ConfigStore(this).setWelcomed(true);
    boolean limited = true;
    try {
      HarnessController controller = HarnessController.get(this);
      var task = com.deepseekharness.app.core.BackupTask.get(this);
      limited =
          task.maintenanceBusy() || task.pendingMaintenance() || !controller.isEnvironmentReady();
      if (limited)
        controller.config().allowLimitedEntry(EnvironmentAccess.limitedEntryKey(controller));
    } catch (RuntimeException failure) {
      record("LIMITED_ENTRY", failure);
    }
    Intent intent = FirstRunWebStart.mainIntent(this, false);
    intent.putExtra("limited_entry", limited);
    openMain(intent);
  }

  private void openMain(Intent intent) {
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    try {
      startActivity(intent);
      finish();
    } catch (RuntimeException failure) {
      record("OPEN_MAIN", failure);
      toast(UiText.choose("无法打开主界面，请重试。", "Could not open the home screen. Please try again."));
    }
  }

  void toast(CharSequence text) {
    try {
      Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    } catch (RuntimeException ignored) {
      // 无法显示提示时不影响页面状态。
    }
  }

  void record(String stage, Throwable failure) {
    try {
      com.deepseekharness.app.core.DiagnosticLog.record(
          this, "ONBOARDING_" + stage, failure.getClass().getSimpleName());
    } catch (RuntimeException ignored) {
      // 诊断记录失败不影响引导。
    }
  }

  SharedPreferences prefs() {
    return getSharedPreferences(PREFS, MODE_PRIVATE);
  }

  /** 第 2 步保存的用途（始终包含 base）；主界面的授权页也可复用。 */
  static Set<String> savedGoals(Context context) {
    Set<String> result = new LinkedHashSet<>();
    result.add(PermissionGoals.BASE);
    try {
      Set<String> stored =
          context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_GOALS, null);
      if (stored != null)
        for (String goal : PermissionGoals.goals()) if (stored.contains(goal)) result.add(goal);
    } catch (RuntimeException ignored) {
      // 读取失败时只保留基础用途。
    }
    return result;
  }

  void saveGoals() {
    try {
      prefs().edit().putStringSet(KEY_GOALS, new LinkedHashSet<>(goals)).apply();
    } catch (RuntimeException ignored) {
      // 只影响推荐排序。
    }
  }

  static int clamp(int value) {
    return Math.max(WELCOME, Math.min(FINISH, value));
  }

  /** 内容最大宽度（680dp / 授权页 980dp）并水平居中的纵向容器。 */
  static final class Capped extends LinearLayout {
    private int max = Integer.MAX_VALUE;

    Capped(Context context) {
      super(context);
      setOrientation(VERTICAL);
    }

    void setMax(int px) {
      if (max == px) return;
      max = px;
      requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int size = MeasureSpec.getSize(widthSpec);
      if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && size > max)
        widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
      super.onMeasure(widthSpec, heightSpec);
    }
  }

  // ---------------------------------------------------------------- 第 1 步：欢迎

  private long spaceRequired, spaceUsable = -1;
  private boolean spaceLow;
  private DeviceSupportReport support;

  /** 真实空间预检：只在尚未部署、环境未安装时拦截；部署开始后可用空间会下降，不能再据此拦截。 */
  private void measureSpace() {
    spaceRequired = OnboardingEnvironment.requiredBytes(this);
    spaceUsable = OnboardingEnvironment.usableBytes(this);
    boolean cold = !OnboardingEnvironment.installed(this);
    spaceLow =
        cold
            && !started()
            && !environmentReady()
            && spaceRequired > 0
            && spaceUsable >= 0
            && spaceUsable < spaceRequired;
  }

  private boolean lowSpace() {
    return spaceLow;
  }

  private String size(long bytes) {
    return bytes < 0
        ? UiText.choose("未知", "Unknown")
        : android.text.format.Formatter.formatFileSize(this, bytes);
  }

  private void buildWelcome() {
    content.addView(kit.eyebrow(getString(R.string.welcome_eyebrow0)));
    kit.add(
        content,
        kit.heading(getString(R.string.ui2_welcome_title0), true),
        kit.shortScreen() ? 6 : 12);
    kit.add(content, kit.lead(getString(R.string.ui2_welcome_lead0)), 10);
    TextView badge =
        kit.bold(
            getString(
                R.string.ui2_offline_badge, com.deepseekharness.app.util.Constants.DSH_VERSION),
            12,
            R.color.shell_accent);
    badge.setBackgroundResource(R.drawable.bg_onb_pill_accent);
    badge.setPadding(kit.dp(11), kit.dp(6), kit.dp(11), kit.dp(6));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
    bp.topMargin = kit.dp(14);
    content.addView(badge, bp);
    if (!kit.wide()) kit.add(content, features(), 14);
    else kit.add(content, features(), 22);
    if (support == null) support = DeviceSupportProbe.sample(this);
    boolean blocked = support.blocksDeploy();
    boolean warn = support.gate == DeviceSupportReport.Gate.RISK;
    boolean advise = support.gate == DeviceSupportReport.Gate.ADVISE;
    kit.add(
        content, kit.section(UiText.choose("部署前检查", "Pre-deployment check")), kit.wide() ? 22 : 16);
    if (kit.wide()) kit.add(content, checkGrid(blocked, warn, advise), 8);
    else kit.add(content, checkSummary(blocked, warn, advise), 8);
    if (blocked) kit.add(content, kit.hint(support.hint(), OnboardingKit.Tone.BAD), 12);
    else if (warn || advise)
      kit.add(content, kit.hint(support.hint(), OnboardingKit.Tone.WARN), 12);
    if (spaceLow)
      kit.add(
          content,
          kit.hint(
              String.format(
                  Locale.ROOT,
                  UiText.choose(
                      "可用空间不足：部署约需 %1$s，当前可用 %2$s。清理空间后点「重新检查」。",
                      "Not enough free space: deployment needs about %1$s and %2$s is available. Free up space, then tap Check again."),
                  size(spaceRequired),
                  size(spaceUsable)),
              OnboardingKit.Tone.BAD),
          12);
    else if (!blocked && kit.wide())
      kit.add(
          content,
          kit.hint(
              UiText.choose(
                  "内置 Ubuntu 与运行组件将部署到应用私有目录。部署在后台进行，期间可以继续完成引导。",
                  "Ubuntu and runtime components are deployed to the app’s private storage in the background, so you can keep going."),
              OnboardingKit.Tone.ACCENT),
          14);
    if (kit.wide()) kit.add(content, dataRow(), 14);
    if (kit.wide()) kit.add(content, kit.fine(getString(R.string.ui2_online_notice)), 14);
    android.widget.Button primary;
    android.widget.Button extra = null;
    if (blocked) {
      primary = kit.primary(UiText.choose("无法部署", "Cannot deploy"), v -> {});
      primary.setEnabled(false);
    } else if (spaceLow)
      primary = kit.primary(UiText.choose("重新检查", "Check again"), v -> recheckSpace());
    else if (started() || environmentReady())
      primary =
          kit.primary(
              UiText.choose("继续", "Continue"),
              v -> {
                markDeployStarted();
                show(GOALS);
              });
    else primary = kit.primary(UiText.choose("开始部署", "Start deployment"), v -> startDeploy());
    if (support.openWebViewSettings)
      extra =
          kit.secondary(
              UiText.choose("打开 WebView 设置", "Open WebView settings"),
              v -> {
                if (!DeviceSupportProbe.openWebViewSettings(this, support))
                  toast(UiText.choose("打不开系统 WebView 设置。", "Could not open WebView settings."));
              });
    android.widget.Button copy =
        kit.ghost(
            UiText.choose("复制给支持", "Copy for support"),
            v -> {
              DeviceSupportProbe.copy(this, support);
              toast(UiText.choose("已复制本机环境摘要。", "Copied the device summary."));
            });
    footer.addView(kit.buttonBar(primary, extra, copy), OnboardingKit.matchWrap());
  }

  /** 三个功能点：正文足够宽时三列并排（图标在上），否则纵向列表。 */
  private View features() {
    boolean columns = contentWidthDp(680) >= 620;
    LinearLayout list = columns ? kit.row() : kit.column();
    if (columns) list.setGravity(Gravity.TOP);
    int[] icons = {R.drawable.ic_onb_chat, R.drawable.ic_terminal, R.drawable.ic_onb_grid};
    int[] titles = {R.string.ui2_w1_0, R.string.ui2_w1_1, R.string.ui2_w1_2};
    int[] subs = {R.string.ui2_ws1_0, R.string.ui2_ws1_1, R.string.ui2_ws1_2};
    for (int i = 0; i < icons.length; i++) {
      LinearLayout item = columns ? kit.column() : kit.row();
      item.addView(kit.iconBox(icons[i], false, 38, 19));
      LinearLayout words = kit.column();
      words.addView(kit.bold(getString(titles[i]), 15, R.color.shell_text));
      kit.add(words, kit.text(getString(subs[i]), 12.5f, R.color.shell_text_muted), 3);
      LinearLayout.LayoutParams wp =
          columns ? OnboardingKit.matchWrap() : new LinearLayout.LayoutParams(0, -2, 1);
      if (columns) wp.topMargin = kit.dp(8);
      else wp.leftMargin = kit.dp(12);
      item.addView(words, wp);
      item.setScreenReaderFocusable(true);
      LinearLayout.LayoutParams ip =
          columns ? new LinearLayout.LayoutParams(0, -2, 1) : OnboardingKit.matchWrap();
      if (i > 0) {
        if (columns) ip.leftMargin = kit.dp(12);
        else ip.topMargin = kit.dp(12);
      }
      list.addView(item, ip);
    }
    return list;
  }

  private View checkGrid(boolean blocked, boolean warn, boolean advise) {
    OnboardingKit.Grid checks = new OnboardingKit.Grid(this, kit.dp(8), kit.dp(120));
    checks.addView(
        tile(
            UiText.choose("设备架构", "Architecture"),
            android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0]
                : "unknown",
            blocked && android.os.Build.VERSION.SDK_INT >= 30));
    checks.addView(
        tile(
            UiText.choose("系统与版本", "System"),
            String.format(
                Locale.ROOT,
                UiText.choose("Android %s", "Android %s"),
                android.os.Build.VERSION.RELEASE),
            blocked && android.os.Build.VERSION.SDK_INT < 30));
    checks.addView(tile(UiText.choose("可用空间", "Free space"), size(spaceUsable), spaceLow));
    checks.addView(
        tile(
            UiText.choose("安装方式", "Installation"),
            UiText.choose("随包离线部署", "Bundled offline runtime"),
            false));
    checks.addView(
        tile(
            UiText.choose("网页内核", "Browser engine"),
            support.webViewLabel,
            blocked || warn || advise));
    checks.addView(tile(UiText.choose("支持结论", "Support"), support.conclusion(), blocked || warn));
    return checks;
  }

  /** 手机：一行摘要 + 点开看六项详情，避免欢迎页被检查区撑出一屏外。 */
  private View checkSummary(boolean blocked, boolean warn, boolean advise) {
    LinearLayout row = kit.row();
    row.setBackgroundResource(blocked ? R.drawable.bg_onb_tile_bad : R.drawable.bg_onb_tile);
    row.setPadding(kit.dp(14), kit.dp(12), kit.dp(12), kit.dp(12));
    row.setMinimumHeight(kit.dp(56));
    row.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout words = kit.column();
    words.addView(
        kit.bold(
            support.conclusion()
                + " · "
                + String.format(
                    Locale.ROOT,
                    UiText.choose("Android %s", "Android %s"),
                    android.os.Build.VERSION.RELEASE)
                + " · "
                + support.webViewLabel,
            14.5f,
            blocked
                ? R.color.shell_bad
                : warn || advise ? R.color.shell_warn : R.color.shell_text));
    kit.add(
        words,
        kit.text(
            spaceLow
                ? UiText.choose("可用空间不足，点此查看详情", "Not enough space. Tap for details.")
                : UiText.choose("点此查看架构、空间与网页内核", "Tap for architecture, space and WebView."),
            12.5f,
            R.color.shell_text_muted),
        3);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(
        kit.icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted, 18),
        new LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setAccessibilityDelegate(OnboardingRoles.BUTTON);
    row.setOnClickListener(v -> showCheckDialog(blocked, warn, advise));
    return row;
  }

  private void showCheckDialog(boolean blocked, boolean warn, boolean advise) {
    LinearLayout body = kit.column();
    int pad = kit.dp(20);
    body.setPadding(pad, kit.dp(4), pad, kit.dp(8));
    body.addView(checkGrid(blocked, warn, advise));
    if (blocked) kit.add(body, kit.hint(support.hint(), OnboardingKit.Tone.BAD), 12);
    else if (warn || advise) kit.add(body, kit.hint(support.hint(), OnboardingKit.Tone.WARN), 12);
    if (spaceLow)
      kit.add(
          body,
          kit.hint(
              String.format(
                  Locale.ROOT,
                  UiText.choose(
                      "可用空间不足：部署约需 %1$s，当前可用 %2$s。",
                      "Not enough free space: deployment needs about %1$s and %2$s is available."),
                  size(spaceRequired),
                  size(spaceUsable)),
              OnboardingKit.Tone.BAD),
          12);
    else
      kit.add(
          body,
          kit.hint(
              UiText.choose(
                  "内置 Ubuntu 与运行组件将部署到应用私有目录。部署在后台进行。",
                  "Ubuntu and runtime components are deployed to the app’s private storage in the background."),
              OnboardingKit.Tone.ACCENT),
          12);
    kit.add(body, kit.fine(getString(R.string.ui2_online_notice)), 10);
    ScrollView scroller = new ScrollView(this);
    scroller.addView(body);
    try {
      new DshaDialogBuilder(this)
          .setTitle(UiText.choose("部署前检查", "Pre-deployment check"))
          .setView(scroller)
          .setPositiveButton(UiText.choose("知道了", "Got it"), null)
          .show();
    } catch (RuntimeException failure) {
      record("SUPPORT_DIALOG", failure);
    }
  }

  private View tile(String label, String value, boolean bad) {
    LinearLayout tile = kit.column();
    tile.setBackgroundResource(bad ? R.drawable.bg_onb_tile_bad : R.drawable.bg_onb_tile);
    tile.setPadding(kit.dp(13), kit.dp(11), kit.dp(13), kit.dp(11));
    tile.addView(kit.text(label, 11, R.color.shell_text_muted));
    TextView body = kit.bold(value, 14.5f, bad ? R.color.shell_bad : R.color.shell_text);
    body.setMaxLines(3);
    body.setEllipsize(android.text.TextUtils.TruncateAt.END);
    kit.add(tile, body, 4);
    tile.setScreenReaderFocusable(true);
    return tile;
  }

  /** 可点说明行：“权限由你决定，数据有处可寻”。 */
  private View dataRow() {
    LinearLayout row = kit.row();
    row.setBackgroundResource(R.drawable.bg_onb_linkrow);
    row.setPadding(kit.dp(14), kit.dp(12), kit.dp(12), kit.dp(12));
    row.setMinimumHeight(kit.dp(56));
    FrameLayout slot = new FrameLayout(this);
    slot.addView(
        kit.icon(R.drawable.ic_ui_shield, R.color.shell_accent, 22),
        new FrameLayout.LayoutParams(kit.dp(22), kit.dp(22), Gravity.CENTER));
    row.addView(slot, new LinearLayout.LayoutParams(kit.dp(38), kit.dp(38)));
    LinearLayout words = kit.column();
    words.addView(
        kit.bold(
            UiText.choose("权限由你决定，数据有处可寻", "Your permissions, your data"),
            14.5f,
            R.color.shell_text));
    kit.add(
        words, kit.text(getString(R.string.ui2_welcome_lead2), 12.5f, R.color.shell_text_muted), 3);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = kit.dp(12);
    wp.rightMargin = kit.dp(8);
    row.addView(words, wp);
    row.addView(
        kit.icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted, 18),
        new LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setAccessibilityDelegate(OnboardingRoles.BUTTON);
    row.setOnClickListener(v -> showDataDialog());
    return row;
  }

  private void showDataDialog() {
    LinearLayout body = kit.column();
    int pad = kit.dp(24);
    body.setPadding(pad, kit.dp(4), pad, kit.dp(8));
    body.addView(kit.lead(getString(R.string.ui2_welcome_lead2)));
    int[][] rows = {
      {R.string.ui2_w2_0, R.string.ui2_ws2_0},
      {R.string.ui2_w2_1, R.string.ui2_ws2_1},
      {R.string.ui2_w2_2, R.string.ui2_ws2_2}
    };
    for (int[] r : rows) {
      LinearLayout line = kit.row();
      line.setGravity(Gravity.TOP);
      line.setPadding(0, kit.dp(10), 0, kit.dp(10));
      line.addView(
          kit.bold(getString(r[0]), 13.5f, R.color.shell_text),
          new LinearLayout.LayoutParams(kit.dp(96), -2));
      LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1);
      vp.leftMargin = kit.dp(10);
      line.addView(kit.text(getString(r[1]), 13.5f, R.color.shell_text_secondary), vp);
      line.setScreenReaderFocusable(true);
      kit.add(body, line, 8);
      View rule = new View(this);
      rule.setBackgroundColor(kit.color(R.color.shell_line));
      body.addView(rule, new LinearLayout.LayoutParams(-1, kit.dp(1)));
    }
    kit.add(body, kit.fine(getString(R.string.ui2_online_notice)), 14);
    ScrollView scroller = new ScrollView(this);
    scroller.addView(body);
    try {
      new DshaDialogBuilder(this)
          .setTitle(UiText.choose("权限由你决定，数据有处可寻", "Your permissions, your data"))
          .setView(scroller)
          .setPositiveButton(UiText.choose("知道了", "Got it"), null)
          .show();
    } catch (RuntimeException failure) {
      record("DATA_DIALOG", failure);
    }
  }

  private void recheckSpace() {
    measureSpace();
    renderedKey = "";
    render(true);
    content.announceForAccessibility(
        spaceLow
            ? UiText.choose("可用空间仍不足。", "There is still not enough free space.")
            : UiText.choose("空间检查已通过。", "The space check passed."));
  }

  /** “开始部署”：记下引导进度，启动一次应用级维护任务，再进入第 2 步；不重复启动、不写 welcomed。 */
  private void startDeploy() {
    measureSpace();
    if (support == null) support = DeviceSupportProbe.sample(this);
    if (support.blocksDeploy()) {
      renderedKey = "";
      render(true);
      content.announceForAccessibility(support.conclusion());
      return;
    }
    if (spaceLow) {
      recheckSpace();
      return;
    }
    markDeployStarted();
    boolean accepted = false;
    try {
      accepted = RuntimeMaintenanceStarter.startInitialDeploy(this);
    } catch (RuntimeException failure) {
      record("START_DEPLOY", failure);
    }
    if (monitor != null) monitor.refreshNow();
    if (accepted)
      content.announceForAccessibility(UiText.choose("运行环境部署已开始", "Runtime deployment started"));
    else
      // 已在进行、已就绪或本候选已尝试过：不重复启动，进度与结果以胶囊和第 4 步为准。
      toast(
          UiText.choose(
              "部署没有重新启动：可能已在进行或已尝试过。可在第 4 步查看结果。",
              "Deployment was not restarted: it may already be running or was attempted. Check step 4 for the result."));
    show(GOALS);
  }

  // ---------------------------------------------------------------- 第 2 步：用途

  private void buildGoals() {
    content.addView(kit.eyebrow(UiText.choose("第 2 步 · 用途", "Step 2 · Purpose")));
    kit.add(
        content,
        kit.heading(UiText.choose("你想用 DSHA 做什么？", "What will you use DSHA for?"), false),
        10);
    kit.add(
        content,
        kit.lead(
            UiText.choose(
                "可多选。只用来推荐下一步的授权，不会自动申请任何权限。",
                "Choose any. This only recommends permissions on the next step; nothing is requested automatically.")),
        8);
    OnboardingKit.Grid list = new OnboardingKit.Grid(this, kit.dp(10), kit.dp(290));
    list.setContentDescription(UiText.choose("用途（可多选）", "Purposes (multiple choice)"));
    String[][] items = {
      {
        PermissionGoals.BASE,
        UiText.choose("对话、写代码、用终端", "Chat, code and use the terminal"),
        UiText.choose("无需任何设备授权", "No device permission needed")
      },
      {
        PermissionGoals.FILES,
        UiText.choose("读写手机里的文件", "Work with files on the phone"),
        UiText.choose("推荐：存储读写", "Recommended: storage access")
      },
      {
        PermissionGoals.SCREEN,
        UiText.choose("让 AI 操作手机屏幕", "Let AI operate the screen"),
        UiText.choose("推荐：无障碍 · 悬浮窗 · 通知", "Recommended: accessibility · overlay · notifications")
      },
      {
        PermissionGoals.SHELL,
        UiText.choose("执行设备命令", "Run device commands"),
        UiText.choose(
            "推荐：Stellar（Shizuku 兼容层）", "Recommended: Stellar (Shizuku compatibility layer)")
      },
      {
        PermissionGoals.BACKGROUND,
        UiText.choose("长时间在后台运行", "Keep running in the background"),
        UiText.choose("推荐：电池优化 · 通知", "Recommended: battery optimization · notifications")
      }
    };
    int[] icons = {
      R.drawable.ic_onb_chat,
      R.drawable.ic_onb_folder,
      R.drawable.ic_onb_hand,
      R.drawable.ic_terminal,
      R.drawable.ic_onb_battery
    };
    for (int i = 0; i < items.length; i++)
      list.addView(goal(items[i][0], items[i][1], items[i][2], icons[i]));
    kit.add(content, list, 18);
    if (deploy.phase == DeployStages.Phase.RUNNING)
      kit.add(
          content,
          kit.hint(
              kit.wide()
                  ? UiText.choose(
                      "运行环境正在后台部署，左侧可查看进度。",
                      "The runtime is deploying in the background; see progress on the left.")
                  : UiText.choose(
                      "运行环境正在后台部署，点顶部胶囊可查看进度。",
                      "The runtime is deploying in the background; tap the top capsule for progress."),
              OnboardingKit.Tone.ACCENT),
          14);
    renderGoalsFooter();
  }

  private void renderGoalsFooter() {
    footer.removeAllViews();
    int n = PermissionGoals.recommendedCount(goals);
    if (n == 0) {
      // 只选了“对话、写代码、用终端”：不需要任何设备授权，直接到完成页；授权页仍可选择进入。
      footer.addView(
          kit.buttonBar(
              kit.primary(
                  UiText.choose("无需授权，下一步", "No permissions needed · Next"), v -> show(FINISH)),
              kit.secondary(
                  UiText.choose("仍然查看可选授权", "Review optional permissions"), v -> show(PERMS))),
          OnboardingKit.matchWrap());
      return;
    }
    String next =
        String.format(
            Locale.ROOT,
            UiText.choose("下一步：开启 %d 项推荐授权", "Next: enable %d recommended permissions"),
            n);
    footer.addView(
        kit.buttonBar(
            kit.primary(next, v -> show(PERMS)),
            kit.secondary(
                UiText.choose("跳过授权，稍后在设置中开启", "Skip permissions for now"), v -> show(FINISH))),
        OnboardingKit.matchWrap());
  }

  /** 用途项：整行可点的复选框；base 锁定选中。勾选只原位更新，不重建页面，保持焦点与滚动。 */
  private View goal(String id, String title, String sub, int icon) {
    boolean locked = PermissionGoals.BASE.equals(id);
    LinearLayout row = kit.row();
    row.setBackgroundResource(R.drawable.bg_onb_goal);
    row.setPadding(kit.dp(14), kit.dp(13), kit.dp(14), kit.dp(13));
    row.setMinimumHeight(kit.dp(64));
    FrameLayout box = new FrameLayout(this);
    ImageView check = kit.icon(R.drawable.ic_onb_check, R.color.shell_on_accent, 14);
    box.addView(check, new FrameLayout.LayoutParams(kit.dp(14), kit.dp(14), Gravity.CENTER));
    row.addView(box, new LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)));
    LinearLayout words = kit.column();
    words.addView(kit.bold(title, 15, R.color.shell_text));
    kit.add(words, kit.text(sub, 12.5f, R.color.shell_text_muted), 2);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = kit.dp(12);
    wp.rightMargin = kit.dp(12);
    row.addView(words, wp);
    View badge = kit.iconBox(icon, false, 34, 18);
    row.addView(badge);
    Runnable paint =
        () -> {
          boolean on = locked || goals.contains(id);
          row.setSelected(on);
          box.setBackground(checkBox(on));
          check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        };
    paint.run();
    row.setAlpha(locked ? 0.78f : 1f);
    row.setContentDescription(
        String.format(Locale.ROOT, UiText.choose("%1$s，%2$s", "%1$s, %2$s"), title, sub));
    row.setAccessibilityDelegate(OnboardingRoles.checkbox(locked));
    row.setFocusable(true);
    if (!locked) {
      row.setClickable(true);
      row.setOnClickListener(
          v -> {
            if (!goals.remove(id)) goals.add(id);
            saveGoals();
            paint.run();
            if (goals.contains(id)) OnboardingMotion.popIfEnabled(box);
            v.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
            renderGoalsFooter();
          });
    }
    return row;
  }

  // ---------------------------------------------------------------- 第 3 步：授权

  private void buildPerms() {
    content.addView(kit.eyebrow(UiText.choose("第 3 步 · 授权", "Step 3 · Permissions")));
    kit.add(content, kit.heading(UiText.choose("按需授权", "Grant what you need"), false), 10);
    TextView lead =
        kit.lead(UiText.choose("全部可选，之后可在设置中修改。", "All optional. Change later in Settings."));
    lead.setMaxWidth(kit.dp(680));
    kit.add(content, lead, 8);
    View view = null;
    try {
      if (panel != null) view = panel.view();
    } catch (RuntimeException failure) {
      record("PERMISSION_PANEL", failure);
    }
    if (view != null) {
      if (view.getParent() instanceof android.view.ViewGroup parent) parent.removeView(view);
      kit.add(content, view, 16);
    } else
      kit.add(
          content,
          kit.hint(
              UiText.choose(
                  "授权状态暂时无法读取，可稍后在 设置 → 设备能力授权 中开启。",
                  "Permission status is unavailable. You can enable permissions later in Settings → Device permissions."),
              OnboardingKit.Tone.WARN),
          16);
    int total = panel == null ? 0 : panel.recommendedTotal();
    int done = panel == null ? 0 : panel.recommendedDone();
    String primary =
        total > 0 && done >= total
            ? UiText.choose("下一步", "Next")
            : total > 0
                ? String.format(
                    Locale.ROOT,
                    UiText.choose("下一步 · 已开启 %1$d / %2$d", "Next · %1$d of %2$d on"),
                    done,
                    total)
                : granted() > 0
                    ? UiText.choose("完成授权", "Done")
                    : UiText.choose("暂不授权，继续", "Skip for now");
    footer.addView(
        kit.buttonBar(kit.primary(primary, v -> show(FINISH))), OnboardingKit.matchWrap());
  }

  // ---------------------------------------------------------------- 第 4 步：完成

  private static final int F_IDLE = 0,
      F_RUNNING = 1,
      F_READY = 2,
      F_FAILED = 3,
      F_PENDING = 4,
      F_UNREADY = 5;

  /** 完成页状态：成功文案不是就绪证明，只有就绪检查通过才允许“进入主界面”。 */
  private int finishState() {
    switch (deploy.phase) {
      case RUNNING:
        return F_RUNNING;
      case SUCCEEDED:
        return environmentReady() ? F_READY : F_UNREADY;
      case FAILED:
        return F_FAILED;
      case PENDING:
        return F_PENDING;
      case UNREADY:
        return F_UNREADY;
      default:
        return environmentReady() ? F_READY : F_IDLE;
    }
  }

  private void buildFinish() {
    int state = finishState();
    String title, lead, detail;
    switch (state) {
      case F_RUNNING:
        title = UiText.choose("马上就好", "Almost there");
        lead = UiText.text("部署中");
        detail =
            UiText.choose(
                "运行环境还在部署，完成后即可进入主界面并自动启动 Web。也可以先进入受限主界面查看配置和日志。",
                "The runtime is still deploying. When it finishes you can open the home screen and Web starts automatically. You can also enter the limited interface to view settings and logs.");
        break;
      case F_READY:
        title = UiText.choose("准备就绪", "All set");
        lead = UiText.text("已完成");
        detail =
            UiText.choose(
                "运行环境已部署并通过检查。进入主界面后会自动启动 Web。",
                "The runtime is deployed and passed its checks. Web starts automatically on the home screen.");
        break;
      case F_FAILED:
      case F_PENDING:
        title =
            state == F_PENDING ? deploy.label() : UiText.choose("部署未完成", "Deployment incomplete");
        lead = UiText.text("部署失败");
        detail =
            UiText.choose(
                "部署没有完成，本页不会自动覆盖环境。请按下方原因处理后重试。",
                "Deployment did not finish. This page will not overwrite the environment automatically. Fix the cause below, then retry.");
        break;
      case F_UNREADY:
        title = UiText.choose("需要处理环境", "The environment needs attention");
        lead = UiText.text("待修复");
        detail =
            UiText.choose(
                "维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。",
                "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface.");
        break;
      default:
        title = UiText.choose("还未开始部署", "Deployment has not started");
        lead = UiText.choose("回到第 1 步开始部署。", "Go back to step 1 to start deployment.");
        detail = "";
    }
    content.addView(kit.eyebrow(UiText.choose("第 4 步 · 完成", "Step 4 · Finish")));
    kit.add(content, kit.heading(title, false), 10);
    // 首屏只留 ≤5 字状态词，原因/影响/下一步点开 ⓘ（审计 A-06 + 基准 ⑩⑬）。
    TextView leadView = kit.lead(lead);
    if (!detail.isEmpty()) InfoHint.bind(leadView, title, lead, detail);
    kit.add(content, leadView, 8);
    kit.add(content, runtimeCard(state), 18);
    kit.add(content, grantsCard(), 12);
    buildFinishFooter(state);
  }

  /**
   * 第 4 步底栏与运行环境维护页共用同一套紧凑两行：主操作行（进度面板 / 主按钮 + 受限入口）与一行三个
   * 描边入口。状态映射沿用 {@link MaintenanceFooterPolicy}，点击后的门禁判断仍在本页。
   */
  private void buildFinishFooter(int state) {
    boolean running = state == F_RUNNING,
        pending = state == F_PENDING,
        failed = state == F_FAILED,
        incomplete = state == F_UNREADY,
        ready = state == F_READY;
    MaintenanceFooterPolicy.Model model =
        MaintenanceFooterPolicy.decide(running, pending, failed, incomplete, ready);
    String label = state == F_IDLE ? UiText.choose("回到第 1 步", "Back to step 1") : retryLabel();
    View bar = getLayoutInflater().inflate(R.layout.view_extract_footer, footer, false);
    // 外层 foot 已铺背景与内边距；内嵌视图只保留操作区本身，避免两套 padding 叠成 40dp。
    bar.setBackground(null);
    bar.setPadding(0, 0, 0, 0);
    ExtractFooter compact = new ExtractFooter(this, kit, bar);
    // 就绪后只有“进入主界面”，不再显示降级的主操作。
    compact.setAllowRetry(state != F_READY);
    compact
        .retry()
        .setOnClickListener(
            v -> {
              if (state == F_IDLE) show(WELCOME);
              else retry();
            });
    compact
        .enter()
        .setOnClickListener(
            v -> {
              if (model.entry == MaintenanceFooterPolicy.Entry.PRIMARY) enterMain();
              else enterLimited();
            });
    compact.logs().setOnClickListener(v -> openLogs());
    compact.copy().setOnClickListener(v -> copySupport());
    compact.emergency().setOnClickListener(v -> openRecovery());
    compact.render(model, deploy, label, true);
    footer.addView(bar, OnboardingKit.matchWrap());
  }

  private View runtimeCard(int state) {
    LinearLayout card = kit.card();
    String pill;
    OnboardingKit.Tone tone;
    if (deploy.phase == DeployStages.Phase.IDLE) {
      pill = state == F_READY ? UiText.choose("已就绪", "Ready") : UiText.choose("未开始", "Not started");
      tone = state == F_READY ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL;
    } else {
      pill = DeployCard.pillText(deploy);
      tone = OnboardingKit.tone(deploy.phase);
    }
    card.addView(kit.cardHeader(UiText.choose("运行环境", "Runtime"), kit.pill(pill, tone)));
    if (deploy.phase != DeployStages.Phase.IDLE && deploy.total() > 0) {
      kit.add(card, kit.segmentBar(deploy), 10);
      kit.add(card, kit.stageList(deploy), 10);
    }
    if (state == F_FAILED || state == F_PENDING)
      kit.add(card, kit.messageBox(failureText(), OnboardingKit.Tone.BAD), 12);
    else if (state == F_UNREADY)
      kit.add(
          card,
          kit.messageBox(
              UiText.choose(
                  "维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。",
                  "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface."),
              OnboardingKit.Tone.WARN),
          12);
    return card;
  }

  /** 失败说明：沿用 ExtractActivity 失败分支的三段式（操作 / 最后阶段 / 原因）。 */
  private String failureText() {
    String stage;
    if (!deploy.lastStage.isEmpty())
      stage =
          com.deepseekharness.app.util.LogCompactor.compactReason(
              com.deepseekharness.app.util.UiStateText.render(deploy.lastStage), 240);
    else if (deploy.current >= 0) stage = deploy.stages.get(deploy.current).title();
    else
      stage =
          UiText.choose(
              "旧记录未提供；请查看维护日志", "Not available in the old record; inspect maintenance logs");
    String reason = com.deepseekharness.app.util.MaintenanceErrorText.render(deploy.detail);
    return String.format(
        Locale.ROOT,
        UiText.choose(
            "未完成的操作：%1$s\n最后记录阶段：%2$s\n\n%3$s",
            "Unfinished operation: %1$s\nLast recorded stage: %2$s\n\n%3$s"),
        deploy.title(),
        stage,
        reason == null ? "" : reason);
  }

  private View grantsCard() {
    LinearLayout card = kit.card();
    java.util.List<String[]> summary = new ArrayList<>();
    try {
      if (panel != null) summary = panel.summary();
    } catch (RuntimeException failure) {
      record("PERMISSION_SUMMARY", failure);
    }
    int n = 0;
    for (String[] item : summary) if ("1".equals(item[1])) n++;
    String count =
        String.format(
            Locale.ROOT,
            UiText.choose("已开启 %1$d / %2$d", "%1$d / %2$d enabled"),
            n,
            summary.isEmpty() ? PermissionPanel.displayTotal() : summary.size());
    card.addView(
        kit.cardHeader(
            UiText.choose("授权", "Permissions"),
            kit.pill(count, n > 0 ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL)));
    OnboardingKit.FlowRow chips =
        new OnboardingKit.FlowRow(this, kit.dp(6), kit.dp(6), Gravity.START);
    for (String[] item : summary) {
      boolean on = "1".equals(item[1]);
      TextView chip =
          kit.pill(
              String.format(Locale.ROOT, on ? "✓ %s" : "– %s", item[0]),
              on ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL);
      chip.setContentDescription(
          String.format(
              Locale.ROOT,
              on
                  ? UiText.choose("%s：已开启", "%s: enabled")
                  : UiText.choose("%s：未开启", "%s: not enabled"),
              item[0]));
      chips.addView(chip, new android.view.ViewGroup.LayoutParams(-2, -2));
    }
    kit.add(card, chips, 10);
    // 按第 2 步用途列出尚未开启的推荐授权：用途选择在完成页也有可见结果。
    java.util.List<String> missing =
        panel == null ? java.util.List.of() : panel.recommendedMissingNames();
    if (!missing.isEmpty())
      kit.add(
          card,
          kit.hint(
              String.format(
                  Locale.ROOT,
                  UiText.choose(
                      "按你选的用途，还建议开启：%s。不开也能先进入主界面。",
                      "For the uses you picked, consider enabling: %s. You can enter the home screen without them."),
                  String.join(UiText.choose("、", ", "), missing)),
              OnboardingKit.Tone.ACCENT),
          10);
    LinearLayout note = kit.row();
    note.addView(
        kit.fine(
            UiText.choose(
                "授权可随时在 设置 → 设备能力授权 中修改或撤销。",
                "Permissions can be changed or revoked anytime in Settings → Device permissions.")),
        new LinearLayout.LayoutParams(0, -2, 1));
    android.widget.Button back =
        kit.ghost(UiText.choose("返回授权", "Back to permissions"), v -> show(PERMS));
    back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
    note.addView(back);
    kit.add(card, note, 8);
    return card;
  }

  private String retryLabel() {
    try {
      boolean rebuild =
          com.deepseekharness.app.util.BackupTaskKinds.is(
              com.deepseekharness.app.util.BackupTaskKinds.REBUILD,
              com.deepseekharness.app.core.BackupTask.get(this).snapshot().kind);
      return RuntimeMaintenanceStarter.retryLabel(
          RuntimeMaintenanceStarter.retryKind(this, rebuild), true);
    } catch (RuntimeException unknown) {
      return UiText.choose("重试部署", "Retry deployment");
    }
  }

  /** 重试由用户在确认对话框中确认后才执行（恢复中断维护 / 重试部署 / 更新运行时）。 */
  private void retry() {
    try {
      RuntimeMaintenanceStarter.confirmRetry(
          this,
          () -> {
            if (monitor != null) monitor.refreshNow();
          });
    } catch (RuntimeException failure) {
      record("RETRY", failure);
      toast(
          UiText.choose(
              "无法开始重试，请查看维护记录。", "Could not start the retry. Check the maintenance record."));
    }
  }

  private void openLogs() {
    try {
      startActivity(DiagnosticActivity.downloadLogs(this));
    } catch (RuntimeException failure) {
      record("LOGS", failure);
    }
  }

  /** 完成页的“复制给支持”：与欢迎页共用 DeviceSupportProbe 的设备摘要。 */
  private void copySupport() {
    try {
      if (support == null) support = DeviceSupportProbe.sample(this);
      DeviceSupportProbe.copy(this, support);
      toast(UiText.choose("已复制本机环境摘要。", "Copied the device summary."));
    } catch (RuntimeException failure) {
      record("COPY_SUPPORT", failure);
    }
  }

  private void openRecovery() {
    try {
      startActivity(new Intent(this, RecoveryActivity.class));
    } catch (RuntimeException failure) {
      record("RECOVERY", failure);
    }
  }

  /** 用途复选框的方框：选中为强调色实心，未选中为细描边；颜色全部取 shell_* token。 */
  private android.graphics.drawable.GradientDrawable checkBox(boolean on) {
    var shape = new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(kit.dp(7));
    if (on) shape.setColor(kit.color(R.color.shell_accent));
    else {
      shape.setColor(android.graphics.Color.TRANSPARENT);
      shape.setStroke(kit.dp(2), kit.color(R.color.shell_line_strong));
    }
    return shape;
  }
}
