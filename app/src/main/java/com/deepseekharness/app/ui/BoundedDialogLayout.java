package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** 自定义弹窗按实际标题和按钮测量，中间内容在短屏和大字体下滚动。 */
public final class BoundedDialogLayout extends LinearLayout {
    public BoundedDialogLayout(Context context, AttributeSet attrs) { super(context,attrs); }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int available=Math.round((getResources().getConfiguration().screenHeightDp-72)*getResources().getDisplayMetrics().density);
        if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)available=Math.min(available,View.MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(Math.max(0,available),View.MeasureSpec.AT_MOST));
    }
}
