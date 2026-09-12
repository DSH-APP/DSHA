package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

/** 日志框自己处理上下滑动，到边缘再让外层页面滚动；保留文字长按选择。 */
public final class LogScrollView extends ScrollView {
    private float lastY;
    private boolean interacting;
    private long gestureRevision;

    public LogScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // 短屏保留页面操作空间；大屏的长日志也不无限撑开页面。
        int heightDp=Math.max(144,Math.min(220,getResources().getConfiguration().screenHeightDp/3));
        int available=Math.round(heightDp*getResources().getDisplayMetrics().density);
        if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)
            available=Math.min(available,View.MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.EXACTLY));
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        ViewParent parent=getParent();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY=event.getY();interacting=true;gestureRevision++;
                if(parent!=null)parent.requestDisallowInterceptTouchEvent(canScrollVertically(-1)||canScrollVertically(1));
                break;
            case MotionEvent.ACTION_MOVE:
                float delta=event.getY()-lastY;
                if(parent!=null && Math.abs(delta)>0.5f)
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(delta>0?-1:1));
                lastY=event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                interacting=false;
                if(parent!=null)parent.requestDisallowInterceptTouchEvent(false);
                break;
            default: break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** 只有原本正在看末尾且未触摸时，追加输出才跟随到末尾。 */
    public boolean shouldFollowEnd() {
        return !interacting && getChildCount()>0 && endPosition()-getScrollY()
                <=Math.round(24*getResources().getDisplayMetrics().density);
    }
    public void followEndAfterLayout() {
        long revision=gestureRevision;
        post(()->{
            if(isAttachedToWindow() && !interacting && gestureRevision==revision)scrollTo(0,endPosition());
        });
    }
    private int endPosition() {
        return getChildCount()==0?0:Math.max(0,getChildAt(0).getHeight()-getHeight()+getPaddingTop()+getPaddingBottom());
    }
}
