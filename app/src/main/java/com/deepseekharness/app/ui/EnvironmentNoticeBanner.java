package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.RecoveryBannerState;

/**
 * 主界面顶部的环境提示横幅：语气色浅底 + 状态图标（维护进行中时为转圈）+ 标题 / 说明两行 + 右箭头。
 *
 * <p>只负责显示；显示哪一种由 {@link RecoveryBannerState} 决定，点按去向由主界面按真实状态路由。
 * 颜色与图形资源在挂到窗口后才应用，文字与可见性立即生效。
 */
public final class EnvironmentNoticeBanner extends LinearLayout {
  private final FrameLayout badge;
  private final ImageView icon;
  private final ProgressBar spinner;
  private final TextView title;
  private final TextView subtitle;
  private final ImageView chevron;
  private RecoveryBannerState.Kind kind = RecoveryBannerState.Kind.NONE;
  private RecoveryBannerState.Kind styled;

  public EnvironmentNoticeBanner(Context context) {
    this(context, null);
  }

  public EnvironmentNoticeBanner(Context context, AttributeSet attrs) {
    super(context, attrs);
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setClickable(true);
    setFocusable(true);
    setBaselineAligned(false);
    setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

    badge = new FrameLayout(context);
    badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    icon = new ImageView(context);
    icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    badge.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
    spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
    spinner.setIndeterminate(true);
    spinner.setVisibility(GONE);
    badge.addView(spinner, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
    addView(badge, new LayoutParams(dp(34), dp(34)));

    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(VERTICAL);
    texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    title = new TextView(context);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    title.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    title.setIncludeFontPadding(false);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(title, new LayoutParams(-1, -2));
    subtitle = new TextView(context);
    subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
    subtitle.setIncludeFontPadding(false);
    subtitle.setLineSpacing(dp(2), 1f);
    subtitle.setMaxLines(3);
    subtitle.setEllipsize(TextUtils.TruncateAt.END);
    LayoutParams subtitleParams = new LayoutParams(-1, -2);
    subtitleParams.topMargin = dp(4);
    texts.addView(subtitle, subtitleParams);
    LayoutParams textParams = new LayoutParams(0, -2, 1);
    textParams.setMarginStart(dp(12));
    textParams.setMarginEnd(dp(6));
    addView(texts, textParams);

    chevron = new ImageView(context);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    chevron.setRotation(-90f);
    addView(chevron, new LayoutParams(dp(18), dp(18)));

    setPadding(dp(12), dp(11), dp(12), dp(11));
    setMinimumHeight(dp(56));
    setVisibility(GONE);
  }

  /** 显示一种提示；kind 为 NONE 时收起。标题与说明已按当前语言渲染。 */
  public void show(RecoveryBannerState.Kind kind, CharSequence titleText, CharSequence detail) {
    this.kind = kind == null ? RecoveryBannerState.Kind.NONE : kind;
    boolean visible = this.kind != RecoveryBannerState.Kind.NONE;
    if (visible) {
      // 标题/说明变化用 150ms 淡入，出现时整条淡入（审计 A-03），不再硬切顶部横幅。
      UiMotion.crossFadeText(title, titleText);
      UiMotion.crossFadeText(subtitle, detail);
      subtitle.setVisibility(TextUtils.isEmpty(detail) ? GONE : VISIBLE);
      CharSequence description =
          TextUtils.isEmpty(detail) ? titleText : TextUtils.concat(titleText, ", ", detail);
      if (!TextUtils.equals(getContentDescription(), description))
        setContentDescription(description);
    }
    int visibility = visible ? VISIBLE : GONE;
    boolean wasHidden = getVisibility() != VISIBLE;
    if (getVisibility() != visibility) setVisibility(visibility);
    if (visible && wasHidden) UiMotion.reveal(this);
    if (isAttachedToWindow()) style();
  }

  public RecoveryBannerState.Kind kind() {
    return kind;
  }

  /** 标题与说明的完整文字，供诊断与测试读取。 */
  public CharSequence text() {
    return TextUtils.isEmpty(subtitle.getText())
        ? title.getText()
        : TextUtils.concat(title.getText(), " · ", subtitle.getText());
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    styled = null;
    style();
  }

  private void style() {
    if (kind == RecoveryBannerState.Kind.NONE || kind == styled) return;
    styled = kind;
    Context context = getContext();
    RecoveryBannerState.Tone tone = RecoveryBannerState.tone(kind);
    int strong =
        context.getColor(
            switch (tone) {
              case WARN -> R.color.shell_warn;
              case BAD -> R.color.shell_bad;
              case ACCENT -> R.color.shell_accent;
            });
    int soft =
        context.getColor(
            switch (tone) {
              case WARN -> R.color.shell_warn_soft;
              case BAD -> R.color.shell_bad_soft;
              case ACCENT -> R.color.shell_accent_tonal;
            });
    setBackgroundResource(R.drawable.bg_env_notice);
    setBackgroundTintList(ColorStateList.valueOf(soft));
    setForeground(context.getDrawable(R.drawable.fg_env_notice));
    badge.setBackgroundResource(R.drawable.bg_transfer_tile);
    badge.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.shell_surface)));
    title.setTextColor(context.getColor(R.color.shell_text));
    subtitle.setTextColor(context.getColor(R.color.shell_text_secondary));
    chevron.setImageResource(R.drawable.ic_transfer_chevron);
    chevron.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
    boolean spinning = RecoveryBannerState.spinning(kind);
    spinner.setVisibility(spinning ? VISIBLE : GONE);
    icon.setVisibility(spinning ? GONE : VISIBLE);
    if (spinning) spinner.setIndeterminateTintList(ColorStateList.valueOf(strong));
    else {
      icon.setImageResource(
          switch (kind) {
            case AWAITING_CONFIRMATION -> R.drawable.ic_recovery_clock;
            case LIMITED -> R.drawable.ic_recovery_shield;
            default -> R.drawable.ic_onb_alert;
          });
      icon.setImageTintList(ColorStateList.valueOf(strong));
    }
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
