package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/** 「插件管理」分段上的计数徽标：已确认数量显示数字；检测中显示一个小点；未确认显示「?」；刷新失败但保留上次 列表时用提醒色。只投影目录快照，不推算数量。 */
final class PluginCountBadge extends ReplacementSpan {
  enum Kind {
    COUNT,
    LOADING,
    UNKNOWN,
    WARN
  }

  private final Kind kind;
  private final String text;
  private final int background, foreground;
  private final float density, textSize;
  private final RectF rect = new RectF();

  private PluginCountBadge(Context context, Kind kind, String text, float textSizePx) {
    this.kind = kind;
    this.text = text;
    density = context.getResources().getDisplayMetrics().density;
    textSize = textSizePx;
    int soft =
        kind == Kind.WARN
            ? com.deepseekharness.app.R.color.shell_warn_soft
            : kind == Kind.UNKNOWN
                ? com.deepseekharness.app.R.color.shell_raised
                : com.deepseekharness.app.R.color.shell_accent_soft;
    int tone =
        kind == Kind.WARN
            ? com.deepseekharness.app.R.color.shell_warn
            : kind == Kind.UNKNOWN
                ? com.deepseekharness.app.R.color.shell_text_muted
                : com.deepseekharness.app.R.color.shell_accent;
    background = context.getColor(soft);
    foreground = context.getColor(tone);
  }

  /** 在标签后追加徽标；label 原样保留，徽标只是视觉附加。 */
  static CharSequence append(
      Context context, CharSequence label, Kind kind, int count, float textSizePx) {
    String text =
        switch (kind) {
          case COUNT, WARN -> count > 99 ? "99+" : String.valueOf(Math.max(0, count));
          case UNKNOWN -> "?";
          case LOADING -> "";
        };
    SpannableStringBuilder builder = new SpannableStringBuilder(label).append(" \u2060");
    int start = builder.length() - 1;
    builder.setSpan(
        new PluginCountBadge(context, kind, text, textSizePx * 0.8f),
        start,
        builder.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return builder;
  }

  private float badgeWidth(Paint paint) {
    float height = badgeHeight();
    if (kind == Kind.LOADING) return height;
    Paint measure = new Paint(paint);
    measure.setTextSize(textSize);
    return Math.max(height, measure.measureText(text) + 10 * density);
  }

  private float badgeHeight() {
    return Math.max(16 * density, textSize + 6 * density);
  }

  @Override
  public int getSize(
      Paint paint, CharSequence source, int start, int end, Paint.FontMetricsInt metrics) {
    if (metrics != null) {
      Paint.FontMetricsInt base = paint.getFontMetricsInt();
      metrics.ascent = base.ascent;
      metrics.descent = base.descent;
      metrics.top = base.top;
      metrics.bottom = base.bottom;
    }
    return Math.round(badgeWidth(paint) + 2 * density);
  }

  @Override
  public void draw(
      Canvas canvas,
      CharSequence source,
      int start,
      int end,
      float x,
      int top,
      int y,
      int bottom,
      Paint paint) {
    Paint.FontMetrics base = paint.getFontMetrics();
    float height = badgeHeight();
    float width = badgeWidth(paint);
    // 与文字视觉中线对齐，而不是与行框对齐。
    float center = y + (base.ascent + base.descent) / 2f;
    float left = x + 2 * density;
    rect.set(left, center - height / 2f, left + width, center + height / 2f);
    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    fill.setColor(background);
    canvas.drawRoundRect(rect, height / 2f, height / 2f, fill);
    fill.setColor(foreground);
    if (kind == Kind.LOADING) {
      canvas.drawCircle(rect.centerX(), rect.centerY(), 3f * density, fill);
      return;
    }
    fill.setTextSize(textSize);
    fill.setFakeBoldText(true);
    fill.setTextAlign(Paint.Align.CENTER);
    Paint.FontMetrics own = fill.getFontMetrics();
    canvas.drawText(text, rect.centerX(), rect.centerY() - (own.ascent + own.descent) / 2f, fill);
  }
}
