package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.R;

/**
 * 卡片层级与状态徽标（H1/H2）：
 *
 * <p>H1 三级只用主题 token——主卡 {@link Tier#ELEVATED}（surface + 细描边 + 1dp 抬升）、
 * 分组 {@link Tier#FILLED}（raised 底、无描边）、次级 {@link Tier#OUTLINED}（surface + 细描边）。
 * 不写死颜色/阴影；调色板与日夜由资源覆盖。
 *
 * <p>H2：{@link #dot} 只给状态（≤5 字状态词旁边的小点），{@link #badge} 只给数量/待办；
 * 徽标 >99 显示 99+，加载/未知仍由页面用状态点表达。
 */
final class CardTokens {
  private CardTokens() {}

  enum Tier {
    ELEVATED,
    FILLED,
    OUTLINED
  }

  enum Tone {
    ACCENT,
    GOOD,
    WARN,
    BAD,
    NEUTRAL
  }

  /** 应用卡片层级；保留调用方原有内边距与圆角。 */
  static void tier(View view, Tier tier) {
    if (view == null || tier == null) return;
    switch (tier) {
      case ELEVATED -> {
        view.setBackgroundResource(R.drawable.bg_shell_card);
        view.setElevation(dp(view.getContext(), 1));
      }
      case FILLED -> {
        view.setBackgroundResource(R.drawable.bg_shell_card_filled);
        view.setElevation(0f);
      }
      default -> {
        view.setBackgroundResource(R.drawable.bg_shell_card);
        view.setElevation(0f);
      }
    }
  }

  /** 数量/待办徽标；count < 0 表示未知，仍显示「?」。 */
  static TextView badge(Context context, int count, Tone tone) {
    TextView badge = new TextView(context);
    String text = count < 0 ? "?" : count > 99 ? "99+" : String.valueOf(Math.max(0, count));
    badge.setText(text);
    badge.setTextSize(11f);
    badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
    badge.setIncludeFontPadding(false);
    badge.setGravity(Gravity.CENTER);
    badge.setTextColor(context.getColor(toneColor(tone)));
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(dp(context, 9));
    shape.setColor(context.getColor(softColor(tone)));
    badge.setBackground(shape);
    int padH = dp(context, 6), padV = dp(context, 1);
    badge.setPadding(padH, padV, padH, padV);
    badge.setMinWidth(dp(context, 18));
    badge.setMinHeight(dp(context, 18));
    badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return badge;
  }

  /** 状态点：只表达状态，不带数字；读屏由相邻文字承担。 */
  static View dot(Context context, Tone tone) {
    View dot = new View(context);
    GradientDrawable shape = new GradientDrawable();
    shape.setShape(GradientDrawable.OVAL);
    shape.setColor(context.getColor(toneColor(tone)));
    dot.setBackground(shape);
    dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return dot;
  }

  /** 状态点换色（同一 View 复用，不重建）。 */
  static void setDotTone(View dot, Tone tone) {
    if (dot != null && dot.getBackground() instanceof GradientDrawable shape) {
      shape.setColor(dot.getContext().getColor(toneColor(tone)));
    }
  }

  static int toneColor(Tone tone) {
    if (tone == null) return R.color.shell_text_muted;
    return switch (tone) {
      case GOOD -> R.color.shell_good;
      case WARN -> R.color.shell_warn;
      case BAD -> R.color.shell_bad;
      case ACCENT -> R.color.shell_accent;
      default -> R.color.shell_text_muted;
    };
  }

  private static int softColor(Tone tone) {
    if (tone == null) return R.color.shell_raised;
    return switch (tone) {
      case GOOD -> R.color.shell_good_soft;
      case WARN -> R.color.shell_warn_soft;
      case BAD -> R.color.shell_bad_soft;
      case ACCENT -> R.color.shell_accent_soft;
      default -> R.color.shell_raised;
    };
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
