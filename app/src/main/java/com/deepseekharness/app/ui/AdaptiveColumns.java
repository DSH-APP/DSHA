package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import com.deepseekharness.app.R;

/**
 * UI v5 方案 A 的「容器查询」：按自身实际宽度在单列与并排之间切换，而不是按屏幕或横竖屏。
 *
 * <p>同一套子视图在窄宽度下保持原来的竖向顺序与上边距；达到 {@link #setSplitWidthDp} 后改为
 * 横向并排，每列按 {@link #setWeights} 分宽，列间用 {@link #setGutterDp}，并去掉第一行以外的上边距
 * 让各列顶端对齐。分屏、自由窗口和设置详情窗格都按真实可用宽度生效。
 */
public final class AdaptiveColumns extends LinearLayout {
  private int splitWidthPx;
  private int gutterPx;
  private float[] weights = {1f, 1f};
  private boolean split;

  public AdaptiveColumns(Context context) {
    this(context, null);
  }

  public AdaptiveColumns(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    setOrientation(VERTICAL);
    splitWidthPx = dp(600);
    gutterPx = dp(24);
  }

  public void setSplitWidthDp(int widthDp) {
    splitWidthPx = dp(widthDp);
    requestLayout();
  }

  public void setGutterDp(int gutterDp) {
    gutterPx = dp(gutterDp);
    requestLayout();
  }

  public void setWeights(float... columnWeights) {
    weights = columnWeights.clone();
    requestLayout();
  }

  /** 当前是否处于并排形态；供测试与无障碍提示使用。 */
  public boolean isSplit() {
    return split;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingStart() - getPaddingEnd();
    boolean wantSplit =
        MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width >= splitWidthPx;
    if (wantSplit != split) {
      split = wantSplit;
      setOrientation(split ? HORIZONTAL : VERTICAL);
      setBaselineAligned(false);
    }
    // 每次测量都按当前可见性重排列宽与列距：子视图之后显示/隐藏时，首个可见列不留空列距。
    arrange(split);
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  /** 只改 LayoutParams 字段（测量前），不调用 setLayoutParams，避免测量中再次请求布局。 */
  private void arrange(boolean columns) {
    int column = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      LayoutParams params = (LayoutParams) child.getLayoutParams();
      int[] original = (int[]) child.getTag(R.id.adaptive_columns_margins);
      if (original == null) {
        original = new int[] {params.topMargin, params.getMarginStart()};
        child.setTag(R.id.adaptive_columns_margins, original);
      }
      if (columns) {
        params.width = 0;
        params.weight = column < weights.length ? weights[column] : 1f;
        params.topMargin = 0;
        params.setMarginStart(column == 0 ? original[1] : gutterPx);
      } else {
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.weight = 0f;
        params.topMargin = original[0];
        params.setMarginStart(original[1]);
      }
      if (child.getVisibility() != GONE) column++;
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
