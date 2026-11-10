package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 首次引导第 3 步的授权列表：6 项授权（不分类），按第 2 步用途把推荐项排在前、其余收进“更多授权”。
 *
 * <p>全部默认关闭、可跳过；外跳只由点击触发；宿主 onResume 调 refresh() 重读系统状态。
 * Root / Shizuku 状态在异步回调后可能稍晚变化，面板在可见期间每秒复查一次，宿主暂停即停止。
 */
final class PermissionPanel {
  static final String PREFS = "dsha-onboarding";
  private static final String NOTIFY_REQUESTS = "notify_requests";
  private static final String NOTIFY_DENIALS = "notify_denials";

  private final AppCompatActivity activity;
  private final OnboardingKit kit;
  private final Set<String> goals;
  private final LinearLayout root;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private boolean environmentReady;
  private String channelTab = PermissionChannelCard.SHIZUKU;

  /** 各授权行的展开选择；状态变化后自动回到折叠，轮询重绘不丢。 */
  private final com.deepseekharness.app.util.PermissionExpansion expansion =
      new com.deepseekharness.app.util.PermissionExpansion();

  private String lastKey = "";
  private boolean heldA11yConnected;
  private long a11yConnectedUntil;
  private androidx.activity.result.ActivityResultLauncher<String> notificationRequest;

  PermissionPanel(AppCompatActivity activity, Set<String> goals, boolean environmentReady) {
    this.activity = activity;
    this.kit = new OnboardingKit(activity);
    this.goals = goals == null ? Set.of() : goals;
    this.environmentReady = environmentReady;
    root = kit.column();
    registerNotificationRequest();
    activity
        .getLifecycle()
        .addObserver(
            new androidx.lifecycle.DefaultLifecycleObserver() {
              @Override
              public void onResume(androidx.lifecycle.LifecycleOwner owner) {
                refresh();
                main.removeCallbacks(poll);
                main.postDelayed(poll, 1000);
              }

              @Override
              public void onPause(androidx.lifecycle.LifecycleOwner owner) {
                main.removeCallbacks(poll);
              }

              @Override
              public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                main.removeCallbacks(poll);
                if (notificationRequest != null) notificationRequest.unregister();
              }
            });
    render(true);
  }

  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (activity.isFinishing() || activity.isDestroyed()) return;
          render(false);
          main.postDelayed(this, 1000);
        }
      };

  private void registerNotificationRequest() {
    if (Build.VERSION.SDK_INT < 33) return;
    notificationRequest =
        activity
            .getActivityResultRegistry()
            .register(
                "dsha-onboarding-notifications-" + System.identityHashCode(this),
                new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                this::onNotificationResult);
  }

  /** 返回面板根视图；每次调用都按 goals 的当前内容（宿主可能就地增删）重排推荐与分组。 */
  View view() {
    render(true);
    return root;
  }

  void refresh() {
    render(true);
  }

  boolean accessibilityConnectedForDisplay() {
    return stableAccessibilityConnected(activity);
  }

  void setEnvironmentReady(boolean ready) {
    if (environmentReady == ready) return;
    environmentReady = ready;
    render(true);
  }

  /**
   * 7 项状态（PermissionGoals 按此下标判定）：存储、Shizuku / Stellar、Root、无障碍（已连接）、通知、悬浮窗、
   * 电池优化。展示层用 summary() / displayGranted() 去掉 Root。
   */
  private boolean[] states() {
    Context c = activity;
    return new boolean[] {
      PermissionActions.storage(),
      PermissionChannelCard.shizukuReady(),
      PermissionChannelCard.rootReady(c),
      stableAccessibilityConnected(c),
      PermissionActions.notifications(c),
      PermissionActions.overlay(c),
      PermissionActions.batteryUnrestricted(c)
    };
  }

  /**
   * 系统从设置返回时，无障碍服务可能短暂 onServiceDisconnected。1.5 秒内保持「已连接」，
   * 避免每秒轮询在「已连接 / 等待连接」之间拆掉整页。
   */
  private boolean stableAccessibilityConnected(Context context) {
    boolean now = PermissionActions.accessibilityConnected(context);
    long t = android.os.SystemClock.elapsedRealtime();
    if (now) {
      heldA11yConnected = true;
      a11yConnectedUntil = t + 1500;
      return true;
    }
    return heldA11yConnected && t < a11yConnectedUntil;
  }

  private static String[] names() {
    return new String[] {
      UiText.choose("存储读写", "Storage"),
      UiText.choose("Shizuku / Stellar", "Shizuku / Stellar"),
      "Root",
      UiText.choose("无障碍", "Accessibility"),
      UiText.choose("通知", "Notifications"),
      UiText.choose("悬浮窗", "Overlay"),
      UiText.choose("电池优化", "Battery optimization")
    };
  }

  /** states() 中 Root 的下标；引导只推荐 Shizuku / Stellar，计数与完成页都不展示 Root。 */
  private static final int ROOT_INDEX = 2;

  /** 引导展示的项数（不含 Root）。 */
  static int displayTotal() {
    return names().length - 1;
  }

  private static int displayGranted(boolean[] s) {
    int n = 0;
    for (int i = 0; i < s.length; i++) if (i != ROOT_INDEX && s[i]) n++;
    return n;
  }

  int grantedCount() {
    return displayGranted(states());
  }

  /** 引导展示用的 6 项（不含 Root）；Root 仍可在设置页开启，并照常满足设备命令通道。 */
  List<String[]> summary() {
    boolean[] s = states();
    String[] n = names();
    List<String[]> out = new ArrayList<>();
    for (int i = 0; i < s.length; i++)
      if (i != ROOT_INDEX) out.add(new String[] {n[i], s[i] ? "1" : "0"});
    return out;
  }

  /** force=false 时只在状态变化后重绘，避免轮询打断滚动与焦点。 */
  private void render(boolean force) {
    boolean[] s = states();
    String a11y = PermissionActions.accessibilityState(activity);
    String key =
        java.util.Arrays.toString(s)
            + a11y
            + PermissionChannelCard.shizukuReady()
            + PermissionChannelCard.rootReady(activity)
            + channelTab
            + environmentReady
            + UiText.language()
            + notifyRequests()
            + prefs().getInt(NOTIFY_DENIALS, 0)
            + new java.util.TreeSet<>(goals);
    if (!force && key.equals(lastKey)) return;
    lastKey = key;
    root.removeAllViews();
    root.addView(summaryBar(s));
    // 只列授权项，不分类：推荐项在前；其余收进“更多授权”，需要时再展开。
    java.util.List<String> rec = PermissionGoals.recommendedCards(goals);
    java.util.List<String> others = PermissionGoals.otherCards(goals);
    if (rec.isEmpty()) {
      root.addView(
          kit.text(
              UiText.choose("你选的用途不需要授权，可直接继续。", "Nothing is required for your choices."),
              12.5f,
              R.color.shell_text_muted),
          kit.topMargin(14));
      root.addView(list(others, s), kit.topMargin(8));
      return;
    }
    root.addView(list(rec, s), kit.topMargin(14));
    if (others.isEmpty()) return;
    root.addView(othersToggle(others.size(), othersExpanded), kit.topMargin(6));
    if (othersExpanded) root.addView(list(others, s), kit.topMargin(2));
  }

  /** 是否展开“更多授权”；只影响本次显示，不记忆、不申请任何权限。 */
  private boolean othersExpanded;

  private View othersToggle(int count, boolean expanded) {
    android.widget.Button toggle =
        kit.ghost(
            expanded
                ? UiText.choose("收起", "Hide")
                : String.format(
                    java.util.Locale.ROOT, UiText.choose("更多授权 · %d 项", "More · %d"), count),
            v -> {
              othersExpanded = !othersExpanded;
              render(true);
            });
    toggle.setContentDescription(
        expanded
            ? UiText.choose("收起更多授权", "Hide more permissions")
            : String.format(
                java.util.Locale.ROOT,
                UiText.choose("展开更多授权，共 %d 项", "Show %d more permissions"),
                count));
    return toggle;
  }

  /** 第 3 / 4 步用：推荐授权中已开启 / 总数，以及尚未开启的推荐卡片名。 */
  int recommendedDone() {
    return PermissionGoals.recommendedDone(goals, states());
  }

  int recommendedTotal() {
    return PermissionGoals.recommendedCount(goals);
  }

  java.util.List<String> recommendedMissingNames() {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String card : PermissionGoals.recommendedMissing(goals, states()))
      names.add(cardName(card));
    return names;
  }

  private static String cardName(String card) {
    switch (card) {
      case PermissionGoals.STORAGE:
        return UiText.choose("存储读写", "Storage");
      case PermissionGoals.CHANNEL:
        return UiText.choose("设备执行通道", "Device command channel");
      case PermissionGoals.A11Y:
        return UiText.choose("无障碍", "Accessibility");
      case PermissionGoals.NOTIFY:
        return UiText.choose("通知", "Notifications");
      case PermissionGoals.OVERLAY:
        return UiText.choose("悬浮窗", "Overlay");
      default:
        return UiText.choose("电池优化", "Battery optimization");
    }
  }

  /** 进度行：“推荐 x / y”（无用途时“已开启 x / 6”）+ 细进度条。 */
  private View summaryBar(boolean[] s) {
    LinearLayout bar = kit.column();
    int n = displayGranted(s);
    int total = PermissionGoals.recommendedCount(goals);
    int done = PermissionGoals.recommendedDone(goals, s);
    int max = total > 0 ? total : displayTotal();
    int value = total > 0 ? done : n;
    TextView label =
        kit.bold(
            total > 0
                ? String.format(
                    java.util.Locale.ROOT,
                    UiText.choose("推荐 %1$d / %2$d", "Recommended %1$d / %2$d"),
                    done,
                    total)
                : String.format(
                    java.util.Locale.ROOT,
                    UiText.choose("已开启 %1$d / %2$d", "%1$d of %2$d enabled"),
                    n,
                    displayTotal()),
            13,
            value >= max ? R.color.shell_good : R.color.shell_text_secondary);
    bar.addView(label);
    android.widget.ProgressBar meter =
        new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    meter.setIndeterminate(false);
    meter.setMax(Math.max(1, max));
    meter.setProgress(value);
    meter.setProgressTintList(
        android.content.res.ColorStateList.valueOf(kit.color(R.color.shell_good)));
    meter.setProgressBackgroundTintList(
        android.content.res.ColorStateList.valueOf(kit.color(R.color.shell_line)));
    meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    bar.addView(
        meter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(4)));
    ((LinearLayout.LayoutParams) meter.getLayoutParams()).topMargin = kit.dp(6);
    bar.setContentDescription(label.getText());
    return bar;
  }

  /** 一组授权行：一个圆角列表，行间分隔线与文字左缘对齐。 */
  private View list(java.util.List<String> cards, boolean[] s) {
    Set<String> rec = PermissionGoals.recommended(goals);
    LinearLayout list = kit.column();
    list.setBackgroundResource(R.drawable.bg_onb_perm);
    list.setClipToOutline(true);
    for (String card : cards) {
      if (list.getChildCount() > 0) {
        View divider = new View(activity);
        divider.setBackgroundColor(kit.color(R.color.shell_line));
        LinearLayout.LayoutParams dp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dp.setMarginStart(kit.dp(54));
        list.addView(divider, dp);
      }
      list.addView(card(card, rec.contains(card), s), OnboardingKit.matchWrap());
    }
    return list;
  }

  private int notifyRequests() {
    return prefs().getInt(NOTIFY_REQUESTS, 0);
  }

  private SharedPreferences prefs() {
    return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private View card(String id, boolean recommended, boolean[] s) {
    PermissionCard.Model m;
    if (PermissionGoals.CHANNEL.equals(id))
      m =
          PermissionChannelCard.model(
              activity,
              kit,
              channelTab,
              environmentReady,
              recommended,
              tab -> {
                channelTab = tab;
                render(true);
              },
              this::refresh);
    else {
      m = PermissionCards.model(this, id, s);
      m.recommended = recommended;
    }
    boolean ok = PermissionGoals.satisfied(id, s);
    LinearLayout item =
        PermissionCard.build(
            kit,
            m,
            expansion.expanded(id, ok),
            () -> {
              expansion.toggle(id, ok);
              focusAfterRender = id;
              render(true);
            });
    // 整页重建会丢失读屏焦点：把焦点还给刚切换的那一行。
    if (id.equals(focusAfterRender)) {
      focusAfterRender = null;
      View row = item.getChildAt(0);
      row.post(
          () ->
              row.performAccessibilityAction(
                  android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                  null));
    }
    return item;
  }

  private String focusAfterRender;

  // ---------------------------------------------------------------- 卡片回调（PermissionCards 使用）

  AppCompatActivity activity() {
    return activity;
  }

  OnboardingKit kit() {
    return kit;
  }

  /** Android 13 以下没有运行时通知申请框：只能在系统通知设置中打开总开关。 */
  static boolean notificationPromptAvailable() {
    return Build.VERSION.SDK_INT >= 33;
  }

  /**
   * 系统已不再弹出申请框：至少有一次申请明确返回“拒绝”，且系统不再建议说明理由（再次拒绝或“不再询问”）。
   * 只取消、未作选择的申请不计入，避免把仍会弹框的情况误判成已拒绝。
   */
  boolean notificationRequestExhausted() {
    if (!notificationPromptAvailable()) return false;
    return prefs().getInt(NOTIFY_DENIALS, 0) >= 1
        && !activity.shouldShowRequestPermissionRationale(
            android.Manifest.permission.POST_NOTIFICATIONS);
  }

  /** 申请框回调：只有明确拒绝才计数（取消对话框时系统同样回传 false，但随后仍可再弹）。 */
  private void onNotificationResult(boolean granted) {
    if (!granted && notificationPromptAvailable() && !PermissionActions.notifications(activity)) {
      boolean rationale =
          activity.shouldShowRequestPermissionRationale(
              android.Manifest.permission.POST_NOTIFICATIONS);
      // 第一次拒绝后系统建议说明理由（rationale=true）；第二次拒绝或“不再询问”后为 false。
      // 二者都属于明确拒绝；首次请求被取消时 rationale 仍为 false 且系统下次还会弹框，
      // 因此只有在之前已请求过至少一次的情况下，rationale=false 才计为拒绝。
      if (rationale || notifyRequests() >= 2)
        prefs().edit().putInt(NOTIFY_DENIALS, prefs().getInt(NOTIFY_DENIALS, 0) + 1).apply();
    }
    refresh();
  }

  void requestNotifications() {
    if (!notificationPromptAvailable()
        || notificationRequest == null
        || notificationRequestExhausted()) {
      PermissionActions.openNotificationSettings(activity);
      return;
    }
    prefs().edit().putInt(NOTIFY_REQUESTS, notifyRequests() + 1).apply();
    try {
      notificationRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    } catch (RuntimeException unavailable) {
      PermissionActions.openNotificationSettings(activity);
    }
  }
}
