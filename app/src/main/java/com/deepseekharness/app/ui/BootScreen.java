package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.BootPalette;
import com.deepseekharness.app.util.UiText;

/**
 * 应用开机加载页：复刻 DSH 网页启动画面（字标 HARNESS + 圆环 + 一行状态）。
 *
 * <ul>
 *   <li>主界面路径使用宿主 shell 主题色，随应用的日间 / 夜间 / 跟随系统切换；
 *   <li>直启 Web 路径使用最近一次 DSH 网页上报的背景色推导前景，衔接 DSH 主题与外部主题壳；
 *   <li>手机与平板（最短边 ≥ 600dp）分别取尺寸，平板放大字标与圆环，底部操作区限宽居中。
 * </ul>
 *
 * <p>只表达真实阶段文字，不推算百分比；操作按钮延迟出现，卡住时仍能看诊断或回到主界面。
 *
 * <p>嵌入模式（{@link #embedded}）用于对话页首次加载：挂进页面自己的容器，系统栏与安全区
 * 仍由宿主页面管理；只保留字标、圆环与状态一行，与随后接手的 DSH 网页启动画面一致。
 */
final class BootScreen {
  /** 诊断入口延迟出现：正常冷启动不打扰，真正卡住时仍可达。 */
  static final long DIAGNOSTICS_DELAY_MS = 8_000L;

  /** 光晕最终不透明度（参考稿 0.15）。 */
  private static final float GLOW_ALPHA = 0.15f;

  private final Activity activity;
  private final FrameLayout root;
  private final TextView hint;
  private final LinearLayout actions;
  private final TextView diagnostics;
  private final TextView openMain;
  private final BootSpinnerView spinner;
  private final BootLogoView logo;
  private final View glow;
  private final TextView wordmark;
  private final boolean ownsSystemBars;
  private final Integer savedStatusBar, savedNavigationBar;
  private final boolean savedLightStatus, savedLightNavigation;
  private final Runnable revealDiagnostics;
  private boolean mainWithDiagnostics;

  /** 本次是否播放开场（冷启动且系统动画开启）；决定最短可见时长。转为 Web 遮罩后清除。 */
  private boolean intro;

  private long shownAt;
  private boolean dismissing;

  /** 上滑收起：cubic-bezier(.85, 0, .15, 1)，慢起慢停。 */
  private static final android.view.animation.Interpolator EXIT =
      new android.view.animation.PathInterpolator(0.85f, 0f, 0.15f, 1f);

  BootScreen(Activity activity, BootPalette palette) {
    this(activity, palette, false);
  }

  /**
   * @param intro 冷启动首次显示时播放光晕、描线与逐项淡入；重建（换主题 / 语言）时直接显示静态完成态
   */
  BootScreen(Activity activity, BootPalette palette, boolean intro) {
    this(activity, palette, intro, true);
  }

  /** 对话页加载遮罩：不接管系统栏与窗口边距，不播放开场，外观对齐 DSH 网页启动画面。 */
  static BootScreen embedded(Activity activity, BootPalette palette) {
    return new BootScreen(activity, palette, false, false);
  }

  private BootScreen(
      Activity activity, BootPalette palette, boolean intro, boolean ownsSystemBars) {
    this.activity = activity;
    this.ownsSystemBars = ownsSystemBars;
    this.intro = intro && UiMotion.animationsEnabled(activity);
    float density = activity.getResources().getDisplayMetrics().density;
    Configuration configuration = activity.getResources().getConfiguration();
    boolean tablet = configuration.smallestScreenWidthDp >= 600;

    root = new FrameLayout(activity);
    root.setBackgroundColor(palette.background);
    root.setClickable(true);
    root.setFocusable(true);

    // 全屏光晕：强调色从中心向外淡到透明，开场时放大淡入到 15%。
    glow = new View(activity);
    android.graphics.drawable.GradientDrawable halo =
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {palette.brand, palette.brand & 0x00FFFFFF});
    halo.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
    halo.setGradientRadius(
        0.5f
            * Math.min(
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels));
    glow.setBackground(halo);
    glow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    glow.setAlpha(GLOW_ALPHA);
    root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
    if (!ownsSystemBars) glow.setVisibility(View.GONE);

    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);

    logo = new BootLogoView(activity);
    logo.setAccent(palette.brand);
    int logoSize = Math.round((tablet ? 112 : 88) * density);
    var logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
    logoParams.bottomMargin = Math.round((tablet ? 12 : 8) * density);
    card.addView(logo, logoParams);
    if (!ownsSystemBars) logo.setVisibility(View.GONE);

    wordmark = new TextView(activity);
    // DSH 字标是品牌名，不随语言翻译。
    wordmark.setText(UiText.raw("HARNESS"));
    wordmark.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 20 : 16);
    wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    // 嵌入模式与 DSH 网页启动画面同为 .08em，网页接手时字标不跳动。
    wordmark.setLetterSpacing(ownsSystemBars ? 0.3f : 0.08f);
    wordmark.setTextColor(palette.primary);
    wordmark.setIncludeFontPadding(false);
    wordmark.setGravity(Gravity.CENTER);
    wordmark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    card.addView(wordmark, new LinearLayout.LayoutParams(-2, -2));

    spinner = new BootSpinnerView(activity);
    spinner.setColors(palette.border, palette.brand);
    spinner.setStrokeWidth((tablet ? 2.5f : 2f) * density);
    int spinnerSize = Math.round((tablet ? 26 : 20) * density);
    var spinnerParams = new LinearLayout.LayoutParams(spinnerSize, spinnerSize);
    int gap = Math.round((tablet ? 20 : 16) * density);
    spinnerParams.topMargin = gap;
    card.addView(spinner, spinnerParams);

    hint = new TextView(activity);
    hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 14 : 12);
    hint.setTextColor(palette.tertiary);
    hint.setGravity(Gravity.CENTER);
    hint.setLineSpacing(0, 1.2f);
    hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    int hintWidth = Math.round((tablet ? 420 : 280) * density);
    var hintParams = new LinearLayout.LayoutParams(-2, -2);
    hintParams.topMargin = gap;
    hint.setMaxWidth(hintWidth);
    card.addView(hint, hintParams);

    root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

    actions = new LinearLayout(activity);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setGravity(Gravity.CENTER);
    actions.setVisibility(View.GONE);
    diagnostics = action(palette, tablet, UiText.choose("查看诊断", "View diagnostics"));
    openMain = action(palette, tablet, UiText.choose("进入主界面", "Open main screen"));
    openMain.setVisibility(View.GONE);
    actions.addView(diagnostics);
    actions.addView(openMain);
    var actionParams =
        new FrameLayout.LayoutParams(
            tablet ? Math.round(480 * density) : -1,
            -2,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    actionParams.bottomMargin = Math.round(16 * density);
    root.addView(actions, actionParams);

    if (ownsSystemBars) {
      // 遮罩盖住整窗：按系统栏与刘海避让内容，背景仍铺满。
      ViewCompat.setOnApplyWindowInsetsListener(
          root,
          (view, insets) -> {
            Insets bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });

      // 系统栏随加载页底色；收起时还原 Activity 原值。
      var window = activity.getWindow();
      var controller = WindowCompat.getInsetsController(window, window.getDecorView());
      savedStatusBar = window.getStatusBarColor();
      savedNavigationBar = window.getNavigationBarColor();
      savedLightStatus = controller.isAppearanceLightStatusBars();
      savedLightNavigation = controller.isAppearanceLightNavigationBars();
      boolean light = !BootPalette.dark(palette.background);
      window.setStatusBarColor(palette.background);
      window.setNavigationBarColor(palette.background);
      controller.setAppearanceLightStatusBars(light);
      controller.setAppearanceLightNavigationBars(light);
    } else {
      // 嵌入模式：系统栏由宿主页面（WebFullscreenUi）管理，这里不保存也不还原。
      savedStatusBar = null;
      savedNavigationBar = null;
      savedLightStatus = false;
      savedLightNavigation = false;
    }

    revealDiagnostics =
        () -> {
          if (root.getParent() == null) return;
          diagnostics.setVisibility(View.VISIBLE);
          if (mainWithDiagnostics) openMain.setVisibility(View.VISIBLE);
          actions.setVisibility(View.VISIBLE);
        };
    diagnostics.setOnClickListener(
        v -> activity.startActivity(DiagnosticActivity.downloadLogs(activity)));
    diagnostics.setVisibility(View.GONE);
  }

  /** 宿主 shell 主题色：主界面路径与“无网页色记录”的直启都用它。 */
  static BootPalette shellPalette(Activity activity) {
    return BootPalette.of(
        activity.getColor(R.color.shell_bg),
        activity.getColor(R.color.shell_text),
        activity.getColor(R.color.shell_text_muted),
        activity.getColor(R.color.shell_line_strong),
        // 品牌色走 shell 强调色：标志描线、光晕与圆环弧共用。
        activity.getColor(R.color.shell_accent));
  }

  private TextView action(BootPalette palette, boolean tablet, String label) {
    float density = activity.getResources().getDisplayMetrics().density;
    var button = new androidx.appcompat.widget.AppCompatButton(activity, null, 0);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 14 : 13);
    button.setTextColor(palette.primary);
    button.setIncludeFontPadding(false);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(Math.round(48 * density));
    button.setMinimumHeight(Math.round(48 * density));
    int pad = Math.round(16 * density);
    button.setPadding(pad, 0, pad, 0);
    TypedValue ripple = new TypedValue();
    activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    button.setBackgroundResource(ripple.resourceId);
    var params = new LinearLayout.LayoutParams(0, -2, 1);
    button.setLayoutParams(params);
    return button;
  }

  void attach() {
    activity.addContentView(root, new ViewGroup.LayoutParams(-1, -1));
    ViewCompat.requestApplyInsets(root);
    root.postDelayed(revealDiagnostics, DIAGNOSTICS_DELAY_MS);
    shownAt = android.os.SystemClock.uptimeMillis();
    logo.start(intro);
    if (!intro) return;
    // 开场：光晕放大淡入 → 标志描线 → 字标、圆环与状态依次上浮淡入。
    glow.setAlpha(0f);
    glow.setScaleX(0.8f);
    glow.setScaleY(0.8f);
    glow.animate()
        .alpha(GLOW_ALPHA)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(1_600L)
        .setInterpolator(UiMotion.standard())
        .start();
    float rise = 10f * activity.getResources().getDisplayMetrics().density;
    fadeUp(wordmark, 450L, 500L, rise);
    fadeUp(spinner, 650L, 400L, rise);
    fadeUp(hint, 650L, 400L, rise);
  }

  /** 嵌入模式：盖在指定容器最上层，尺寸与安全区随容器。 */
  void attachTo(ViewGroup parent) {
    parent.addView(root, new ViewGroup.LayoutParams(-1, -1));
    root.postDelayed(revealDiagnostics, DIAGNOSTICS_DELAY_MS);
    shownAt = android.os.SystemClock.uptimeMillis();
  }

  private static void fadeUp(View view, long delay, long duration, float rise) {
    view.setAlpha(0f);
    view.setTranslationY(rise);
    view.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(duration)
        .setInterpolator(UiMotion.standard())
        .start();
  }

  void setStatus(String text) {
    // 同屏只换提示文字：150ms 淡入，避免与开场/退场动画叠加成硬切（审计 B-08）。
    UiMotion.crossFadeText(hint, text == null ? "" : text);
  }

  /** 直启路径的退出口：始终可见，回到原生主界面。 */
  void offerMain(Runnable open) {
    openMain.setOnClickListener(v -> open.run());
    openMain.setVisibility(View.VISIBLE);
    actions.setVisibility(View.VISIBLE);
  }

  /** 与诊断入口一起延迟出现的退出口：正常加载不打扰，卡住时仍可离开。 */
  void offerMainWithDiagnostics(String label, Runnable open) {
    openMain.setText(label);
    openMain.setOnClickListener(v -> open.run());
    mainWithDiagnostics = true;
  }

  /**
   * 直启已打开 Web：加载页转为 Main 上的静态遮罩，不再显示状态与按钮。
   *
   * <p>Web 是独立任务，桌面图标却属于 Main 任务；系统切回 Main 时先显示它最后一帧的快照，
   * 再由“返回原网页”路由切到 Web。遮罩保证这一帧与加载页一致，而不是闪出主界面。
   * 之后收起只做短淡出，不再播放开场上滑。
   */
  void quiet() {
    intro = false;
    root.removeCallbacks(revealDiagnostics);
    actions.setVisibility(View.GONE);
    hint.setText("");
    hint.setVisibility(View.INVISIBLE);
  }

  boolean attached() {
    return root.getParent() != null;
  }

  /**
   * 收起加载页。播放过开场时至少保留 {@link com.deepseekharness.app.util.ThemeMotion#SPLASH_MIN_VISIBLE_MS}，让描线完整播放；
   * 之后上滑淡出。重复调用只生效一次。
   */
  void dismiss() {
    if (dismissing) return;
    dismissing = true;
    root.removeCallbacks(revealDiagnostics);
    long hold =
        com.deepseekharness.app.util.ThemeMotion.splashHoldMs(
            intro, shownAt, android.os.SystemClock.uptimeMillis());
    if (hold > 0 && root.isAttachedToWindow()) root.postDelayed(this::exit, hold);
    else exit();
  }

  private void exit() {
    if (ownsSystemBars) {
      var window = activity.getWindow();
      var controller = WindowCompat.getInsetsController(window, window.getDecorView());
      window.setStatusBarColor(savedStatusBar);
      window.setNavigationBarColor(savedNavigationBar);
      controller.setAppearanceLightStatusBars(savedLightStatus);
      controller.setAppearanceLightNavigationBars(savedLightNavigation);
    }
    ViewGroup parent = (ViewGroup) root.getParent();
    if (parent == null) return;
    if (!root.isAttachedToWindow() || !UiMotion.animationsEnabled(activity)) {
      parent.removeView(root);
      return;
    }
    if (!intro) {
      // 重建路径：底下界面已经就绪，只做短淡出，不做整屏上滑。
      root.animate()
          .alpha(0f)
          .setDuration(160L)
          .setInterpolator(UiMotion.standard())
          .withEndAction(
              () -> {
                if (root.getParent() == parent) parent.removeView(root);
              })
          .start();
      return;
    }
    // 交还触摸：上滑期间露出的主界面可以立即操作。
    root.setClickable(false);
    root.setFocusable(false);
    root.animate()
        .translationY(-root.getHeight())
        .alpha(0f)
        .setDuration(com.deepseekharness.app.util.ThemeMotion.SPLASH_EXIT_MS)
        .setInterpolator(EXIT)
        .withEndAction(
            () -> {
              if (root.getParent() == parent) parent.removeView(root);
            })
        .start();
  }
}
