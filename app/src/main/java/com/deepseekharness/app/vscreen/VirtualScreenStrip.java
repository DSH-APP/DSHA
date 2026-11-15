package com.deepseekharness.app.vscreen;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.UiMotion;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VirtualScreenPreviewPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * 宿主页顶部的虚拟屏提示条：一条蓝色呼吸线加一行紧凑文字，显示当前正在操作的应用。
 *
 * <p>只显示，不向虚拟屏转发任何触摸；点按整条打开虚拟识屏页，点「×」收起（收起意图持久化，
 * 跨虚拟屏代次与 App 重启保持，直到在设置里重新选择档位）。目标应用只从 Manager 的只读
 * {@code targetStatus} 取得；拿不到时显示「虚拟屏操作中」，不按最近一次的启动记录猜测。
 */
final class VirtualScreenStrip {
  /** 只读 status 的刷新间隔：足够跟住助手换应用，又不至于频繁占用设备通道。 */
  static final long TARGET_REFRESH_MS = 4000;

  /** 视觉高度：3dp 呼吸线 + 24dp 文字行；热区另加到 40dp。 */
  private static final int VISUAL_HEIGHT_DP = 27;

  private static final int TOUCH_HEIGHT_DP = 40;

  private final Activity activity;
  private final VirtualScreenManager session;
  private final FrameLayout root;
  private final View line;
  private final TextView label;
  private final Handler main = new Handler(Looper.getMainLooper());

  /** 只由目标刷新线程访问：包名 → 应用名。 */
  private final Map<String, String> appLabels = new HashMap<>();

  private ScheduledExecutorService worker;
  private ObjectAnimator breathing;
  private volatile boolean closed;

  VirtualScreenStrip(Activity activity, VirtualScreenManager session) {
    this.activity = activity;
    this.session = session;
    root = new FrameLayout(activity);
    root.setId(R.id.vscreen_preview_strip);
    root.setClickable(true);
    root.setFocusable(true);
    root.setOnClickListener(v -> open());
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          // 宿主页未走统一 Insets 时（全屏网页 / 终端）自己让开状态栏与挖孔，不盖住系统图标。
          int top =
              insets.getInsets(
                      android.view.WindowInsets.Type.systemBars()
                          | android.view.WindowInsets.Type.displayCutout())
                  .top;
          FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
          if (params != null && params.topMargin != top) {
            params.topMargin = top;
            view.setLayoutParams(params);
          }
          return insets;
        });

    LinearLayout visual = new LinearLayout(activity);
    visual.setOrientation(LinearLayout.VERTICAL);
    visual.setElevation(dp(6));
    line = new View(activity);
    line.setBackgroundColor(activity.getColor(R.color.shell_accent));
    visual.addView(line, new LinearLayout.LayoutParams(-1, dp(3)));
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), 0, dp(44), 0);
    row.setBackgroundColor(activity.getColor(R.color.card));
    label = new TextView(activity);
    label.setSingleLine(true);
    label.setTextSize(11);
    label.setEllipsize(TextUtils.TruncateAt.END);
    label.setTextColor(activity.getColor(R.color.text));
    // 提示条整体是一个可点目标；文字不再单独进入读屏焦点，避免重复朗读。
    label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
    visual.addView(row, new LinearLayout.LayoutParams(-1, dp(VISUAL_HEIGHT_DP - 3)));
    root.addView(visual, new FrameLayout.LayoutParams(-1, dp(VISUAL_HEIGHT_DP), Gravity.TOP));

    TextView close = new TextView(activity);
    close.setText("×");
    close.setTextSize(18);
    close.setGravity(Gravity.CENTER);
    close.setPadding(0, 0, 0, dp(TOUCH_HEIGHT_DP - VISUAL_HEIGHT_DP));
    close.setTextColor(activity.getColor(R.color.text_secondary));
    close.setBackgroundResource(R.drawable.bg_action_plain);
    close.setContentDescription(UiText.choose("收起虚拟屏提示条", "Hide the virtual screen strip"));
    close.setOnClickListener(
        v -> {
          if (closed) return;
          VirtualScreenPreviewSettings.collapse(activity);
          session.foreground().refresh();
        });
    root.addView(
        close,
        new FrameLayout.LayoutParams(dp(44), dp(TOUCH_HEIGHT_DP), Gravity.TOP | Gravity.END));
    setLabel("", "");
  }

  View view() {
    return root;
  }

  /** 挂到宿主页后启动：先给回退文案，再异步刷新目标应用与呼吸动画。 */
  void start() {
    if (closed) return;
    root.requestApplyInsets();
    if (UiMotion.animationsEnabled(activity)) startBreathing();
    else line.setAlpha(VirtualScreenPreviewPolicy.BREATH_FULL_ALPHA);
    worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "vscreen-strip-target");
              thread.setDaemon(true);
              return thread;
            });
    worker.scheduleWithFixedDelay(this::refreshTarget, 0, TARGET_REFRESH_MS, TimeUnit.MILLISECONDS);
  }

  /** 幂等收起：停动画、停刷新线程并从宿主页移除。必须主线程调用。 */
  void dismiss() {
    if (closed) return;
    closed = true;
    if (breathing != null) {
      breathing.cancel();
      breathing = null;
    }
    if (worker != null) {
      worker.shutdownNow();
      worker = null;
    }
    main.removeCallbacksAndMessages(null);
    label.setText("");
    if (root.getParent() instanceof android.view.ViewGroup parent) parent.removeView(root);
  }

  private void startBreathing() {
    line.setAlpha(VirtualScreenPreviewPolicy.BREATH_DIM_ALPHA);
    breathing =
        ObjectAnimator.ofFloat(
            line,
            "alpha",
            VirtualScreenPreviewPolicy.BREATH_DIM_ALPHA,
            VirtualScreenPreviewPolicy.BREATH_FULL_ALPHA);
    // 800ms 单程 = 1.6s 往返；与设置页其它动效同节奏，不引入新曲线。
    breathing.setDuration(800);
    breathing.setRepeatMode(ValueAnimator.REVERSE);
    breathing.setRepeatCount(ValueAnimator.INFINITE);
    breathing.setInterpolator(new LinearInterpolator());
    breathing.start();
  }

  /** 后台线程：只读 status 取目标包名；失败或没有可验证目标时回退到运行中文案。 */
  private void refreshTarget() {
    if (closed) return;
    String appLabel = "", packageName = "";
    try {
      JSONObject status = session.targetStatus();
      if (status != null && status.optBoolean("ok") && status.optBoolean("packageVerified"))
        packageName = status.optString("package", "");
      if (!packageName.isEmpty()) appLabel = appLabel(packageName);
    } catch (RuntimeException unavailable) {
      // 查询失败不猜目标：按「拿不到」处理。
      packageName = "";
      appLabel = "";
    }
    if (closed) return;
    final String resolvedLabel = appLabel, resolvedPackage = packageName;
    main.post(
        () -> {
          if (closed) return;
          setLabel(resolvedLabel, resolvedPackage);
        });
  }

  private void setLabel(String appLabel, String packageName) {
    String text =
        VirtualScreenPreviewPolicy.stripText(
            appLabel,
            packageName,
            UiText.choose("虚拟屏操作中", "Operating the virtual screen"),
            UiText.choose("虚拟屏 · ", "Virtual screen · "),
            VirtualScreenPreviewPolicy.STRIP_LABEL_MAX_CODE_POINTS);
    label.setText(text);
    boolean known =
        !VirtualScreenPreviewPolicy.targetLabel(
                appLabel, packageName, VirtualScreenPreviewPolicy.STRIP_LABEL_MAX_CODE_POINTS)
            .isEmpty();
    String description =
        known
            ? String.format(
                UiText.choose(
                    "虚拟屏操作中：%s，点按打开控制", "Operating the virtual screen: %s. Tap to open controls."),
                text)
            : UiText.choose("虚拟屏操作中，点按打开控制", "Operating the virtual screen. Tap to open controls.");
    root.setContentDescription(description);
  }

  private String appLabel(String packageName) {
    String cached = appLabels.get(packageName);
    if (cached != null) return cached;
    String label = "";
    try {
      PackageManager manager = activity.getPackageManager();
      ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
      CharSequence name = manager.getApplicationLabel(info);
      if (name != null) label = name.toString().trim();
    } catch (Exception unavailable) {
      // 包名仍可显示；解析不到应用名不是错误（对方可能刚被卸载）。
    }
    appLabels.put(packageName, label);
    return label;
  }

  private void open() {
    try {
      activity.startActivity(
          new Intent(activity, VirtualScreenActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    } catch (RuntimeException ignored) {
      // 宿主页正在销毁时不再启动控制页；条本身随后由生命周期移除。
    }
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
