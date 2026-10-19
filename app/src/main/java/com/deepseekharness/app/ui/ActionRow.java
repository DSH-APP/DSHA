package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** 等宽操作在文字放不下时改为纵排，避免固定两列截掉按钮文案。 */
public final class ActionRow extends LinearLayout {
  public ActionRow(Context context) {
    this(context, null);
  }

  public ActionRow(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int needed = getPaddingLeft() + getPaddingRight();
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      child.measure(
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      LayoutParams p = (LayoutParams) child.getLayoutParams();
      needed += child.getMeasuredWidth() + p.leftMargin + p.rightMargin;
    }
    boolean stack =
        MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
            && (needed > MeasureSpec.getSize(widthSpec)
                || getResources().getConfiguration().fontScale > 1.3f);
    setOrientation(stack ? VERTICAL : HORIZONTAL);
    for (int i = 0; i < getChildCount(); i++) {
      LayoutParams p = (LayoutParams) getChildAt(i).getLayoutParams();
      p.width = stack ? LayoutParams.MATCH_PARENT : 0;
      p.height = LayoutParams.WRAP_CONTENT;
      p.weight = stack ? 0 : 1;
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}
