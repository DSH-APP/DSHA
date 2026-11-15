package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TransferListModel.Step;

/** 详情页「下载 → 校验 → 保存」三步进度；只绘制，不承担点击。 */
final class TransferStepsView extends View {
  private Step[] steps = new Step[0];
  private String[] labels = new String[0];
  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final float radius, gap, strokeWidth;
  private final int accent, warn, bad, todo, surface, onAccent, textColor, muted;

  TransferStepsView(Context context) {
    super(context);
    float density = getResources().getDisplayMetrics().density;
    radius = 11 * density;
    gap = 8 * density;
    strokeWidth = 2 * density;
    accent = context.getColor(R.color.shell_accent);
    warn = context.getColor(R.color.shell_warn);
    bad = context.getColor(R.color.shell_bad);
    todo = context.getColor(R.color.shell_line_strong);
    surface = context.getColor(R.color.shell_surface);
    onAccent = context.getColor(R.color.shell_on_accent);
    textColor = context.getColor(R.color.shell_text);
    muted = context.getColor(R.color.shell_text_muted);
    line.setStrokeWidth(strokeWidth);
    line.setStrokeCap(Paint.Cap.ROUND);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(strokeWidth);
    mark.setStyle(Paint.Style.STROKE);
    mark.setStrokeWidth(2.4f * density);
    mark.setStrokeCap(Paint.Cap.ROUND);
    mark.setStrokeJoin(Paint.Join.ROUND);
    text.setTextSize(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
    text.setTextAlign(Paint.Align.CENTER);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  void setSteps(Step[] next, String[] nextLabels, CharSequence description) {
    steps = next;
    labels = nextLabels;
    setContentDescription(description);
    requestLayout();
    invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    Paint.FontMetrics metrics = text.getFontMetrics();
    int height = Math.round(radius * 2 + gap + (metrics.descent - metrics.ascent) + 2);
    setMeasuredDimension(
        getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
        resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int count = steps.length;
    if (count == 0) return;
    float width = getWidth() - getPaddingLeft() - getPaddingRight();
    float cell = width / count;
    float cy = getPaddingTop() + radius;
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    float[] cx = new float[count];
    for (int i = 0; i < count; i++) {
      int slot = rtl ? count - 1 - i : i;
      cx[i] = getPaddingLeft() + cell * slot + cell / 2;
    }
    for (int i = 1; i < count; i++) {
      boolean reached = steps[i] != Step.TODO;
      line.setColor(reached ? accent : todo);
      float from = cx[i - 1] + (rtl ? -radius - gap / 2 : radius + gap / 2);
      float to = cx[i] + (rtl ? radius + gap / 2 : -radius - gap / 2);
      canvas.drawLine(from, cy, to, cy, line);
    }
    Paint.FontMetrics metrics = text.getFontMetrics();
    float baseline = cy + radius + gap - metrics.ascent;
    for (int i = 0; i < count; i++) {
      drawNode(canvas, steps[i], cx[i], cy);
      boolean strong = steps[i] != Step.TODO;
      text.setColor(steps[i] == Step.FAILED ? bad : strong ? textColor : muted);
      text.setFakeBoldText(
          steps[i] == Step.CURRENT || steps[i] == Step.PAUSED || steps[i] == Step.FAILED);
      String label = i < labels.length ? labels[i] : "";
      CharSequence fitted = TextUtils.ellipsize(label, text, cell - 4, TextUtils.TruncateAt.END);
      canvas.drawText(fitted.toString(), cx[i], baseline, text);
    }
  }

  private void drawNode(Canvas canvas, Step step, float cx, float cy) {
    switch (step) {
      case DONE -> {
        fill.setColor(accent);
        canvas.drawCircle(cx, cy, radius, fill);
        mark.setColor(onAccent);
        path.reset();
        path.moveTo(cx - radius * 0.42f, cy + radius * 0.02f);
        path.lineTo(cx - radius * 0.1f, cy + radius * 0.34f);
        path.lineTo(cx + radius * 0.44f, cy - radius * 0.3f);
        canvas.drawPath(path, mark);
      }
      case FAILED -> {
        fill.setColor(bad);
        canvas.drawCircle(cx, cy, radius, fill);
        mark.setColor(surface);
        float d = radius * 0.34f;
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, mark);
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, mark);
      }
      case CURRENT, PAUSED -> {
        int tone = step == Step.PAUSED ? warn : accent;
        fill.setColor(surface);
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setColor(tone);
        canvas.drawCircle(cx, cy, radius - strokeWidth / 2, stroke);
        fill.setColor(tone);
        canvas.drawCircle(cx, cy, radius * 0.38f, fill);
      }
      case TODO -> {
        fill.setColor(surface);
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setColor(todo);
        canvas.drawCircle(cx, cy, radius - strokeWidth / 2, stroke);
      }
    }
  }
}
