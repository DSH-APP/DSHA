package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 插件页头部的单行优先流式容器：放得下时同一行、末项贴行尾；放不下时换行，不压缩子项、不竖排。
 *
 * <p>用于「标题 + 分段」与「筛选 / 排序 / 下载源」两行：中文 1.0 倍字体时一行，英文或 1.3 倍字体时自然换行。 {@code stretchWrapped}
 * 打开后，独占一行的末项拉满整行（分段换到标题下方时铺满宽度）。子项在行内垂直居中； RTL 镜像排列。
 */
public final class PluginFlowRow extends ViewGroup {
  private final int gapHorizontal;
  private final int gapVertical;
  private final boolean stretchWrapped;

  public PluginFlowRow(Context context) {
    this(context, null);
  }

  public PluginFlowRow(Context context, AttributeSet attrs) {
    super(context, attrs);
    float density = context.getResources().getDisplayMetrics().density;
    TypedArray values =
        context.obtainStyledAttributes(attrs, com.deepseekharness.app.R.styleable.PluginFlowRow);
    try {
      gapHorizontal =
          values.getDimensionPixelSize(
              com.deepseekharness.app.R.styleable.PluginFlowRow_flowGapHorizontal,
              Math.round(8 * density));
      gapVertical =
          values.getDimensionPixelSize(
              com.deepseekharness.app.R.styleable.PluginFlowRow_flowGapVertical,
              Math.round(8 * density));
      stretchWrapped =
          values.getBoolean(
              com.deepseekharness.app.R.styleable.PluginFlowRow_flowStretchWrapped, false);
    } finally {
      values.recycle();
    }
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int mode = MeasureSpec.getMode(widthSpec);
    int padding = getPaddingLeft() + getPaddingRight();
    int available =
        mode == MeasureSpec.UNSPECIFIED
            ? Integer.MAX_VALUE
            : Math.max(0, MeasureSpec.getSize(widthSpec) - padding);
    int childWidthSpec =
        available == Integer.MAX_VALUE
            ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            : MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
    int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    int line = 0, lineWidth = 0, widest = 0;
    View last = null;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      LayoutParams params = (LayoutParams) child.getLayoutParams();
      int margins = params.leftMargin + params.rightMargin;
      child.measure(
          getChildMeasureSpec(childWidthSpec, margins, params.width),
          getChildMeasureSpec(
              childHeightSpec, params.topMargin + params.bottomMargin, params.height));
      int width = child.getMeasuredWidth() + margins;
      if (lineWidth > 0 && (long) lineWidth + gapHorizontal + width > available) {
        line++;
        lineWidth = 0;
      }
      lineWidth += (lineWidth > 0 ? gapHorizontal : 0) + width;
      widest = Math.max(widest, lineWidth);
      params.line = line;
      params.stretched = false;
      last = child;
    }
    if (stretchWrapped && last != null && available != Integer.MAX_VALUE) {
      LayoutParams params = (LayoutParams) last.getLayoutParams();
      if (params.line > 0 && aloneOnLine(last)) {
        int margins = params.leftMargin + params.rightMargin;
        last.measure(
            MeasureSpec.makeMeasureSpec(Math.max(0, available - margins), MeasureSpec.EXACTLY),
            getChildMeasureSpec(
                childHeightSpec, params.topMargin + params.bottomMargin, params.height));
        params.stretched = true;
        widest = Math.max(widest, available);
      }
    }
    int height = getPaddingTop() + getPaddingBottom();
    for (int index = 0; index <= line && last != null; index++)
      height += lineHeight(index) + (index > 0 ? gapVertical : 0);
    int width =
        mode == MeasureSpec.EXACTLY
            ? MeasureSpec.getSize(widthSpec)
            : Math.min(available == Integer.MAX_VALUE ? widest : available, widest) + padding;
    setMeasuredDimension(
        resolveSize(width, widthSpec),
        resolveSize(Math.max(height, getSuggestedMinimumHeight()), heightSpec));
  }

  private boolean aloneOnLine(View target) {
    int line = ((LayoutParams) target.getLayoutParams()).line;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child != target
          && child.getVisibility() != GONE
          && ((LayoutParams) child.getLayoutParams()).line == line) return false;
    }
    return true;
  }

  private int lineHeight(int line) {
    int height = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      LayoutParams params = (LayoutParams) child.getLayoutParams();
      if (params.line == line)
        height =
            Math.max(height, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
    }
    return height;
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    int inner = r - l - getPaddingLeft() - getPaddingRight();
    int top = getPaddingTop();
    int lastIndex = -1;
    for (int i = getChildCount() - 1; i >= 0; i--)
      if (getChildAt(i).getVisibility() != GONE) {
        lastIndex = i;
        break;
      }
    int line = 0;
    int i = 0;
    while (i <= lastIndex) {
      int height = lineHeight(line);
      int x = 0;
      for (; i <= lastIndex; i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        if (params.line != line) break;
        int width = child.getMeasuredWidth();
        int start = x + params.leftMargin;
        // 末项与其他项同行时贴行尾，例如分段在标题右侧、下载源在筛选右侧。
        if (i == lastIndex && x > 0) start = inner - width - params.rightMargin;
        int y =
            top
                + params.topMargin
                + (height - params.topMargin - params.bottomMargin - child.getMeasuredHeight()) / 2;
        int left = rtl ? inner - start - width : start;
        left += getPaddingLeft();
        child.layout(left, y, left + width, y + child.getMeasuredHeight());
        x = start + width + params.rightMargin + gapHorizontal;
      }
      top += height + gapVertical;
      line++;
    }
  }

  @Override
  public LayoutParams generateLayoutParams(AttributeSet attrs) {
    return new LayoutParams(getContext(), attrs);
  }

  @Override
  protected LayoutParams generateDefaultLayoutParams() {
    return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
  }

  @Override
  protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
    return params instanceof MarginLayoutParams
        ? new LayoutParams((MarginLayoutParams) params)
        : new LayoutParams(params);
  }

  @Override
  protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
    return params instanceof LayoutParams;
  }

  /** 第几行由测量写入；布局只读取，不在布局阶段重新折行。 */
  public static final class LayoutParams extends MarginLayoutParams {
    int line;
    boolean stretched;

    public LayoutParams(Context context, AttributeSet attrs) {
      super(context, attrs);
    }

    public LayoutParams(int width, int height) {
      super(width, height);
    }

    public LayoutParams(MarginLayoutParams source) {
      super(source);
    }

    public LayoutParams(ViewGroup.LayoutParams source) {
      super(source);
    }
  }
}
