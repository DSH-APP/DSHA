package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** 阅读和表单区域：平板上限制行长，保留原来的上下边距。 */
public final class PageColumn extends LinearLayout {
  public PageColumn(Context context) {
    this(context, null);
  }

  public PageColumn(Context context, AttributeSet attrs) {
    super(context, attrs);
    setOrientation(VERTICAL);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
      float density = getResources().getDisplayMetrics().density;
      int width = MeasureSpec.getSize(widthSpec);
      int gutter = Math.max(Math.round(20 * density), (width - Math.round(720 * density)) / 2);
      setPaddingRelative(gutter, getPaddingTop(), gutter, getPaddingBottom());
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}
