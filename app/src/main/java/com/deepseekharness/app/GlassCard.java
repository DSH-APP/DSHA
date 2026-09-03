package com.deepseekharness.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * 会模糊自己背后内容的卡片，用来替换 {@code style="@style/DshaCard"} 的 LinearLayout。
 *
 * <p>两处需要绕开的现实：
 * <ul>
 *   <li>BlurView 是 FrameLayout，而卡片里的内容是竖排的。所以内部塞一个竖向
 *       LinearLayout，并把 {@link #addView} 全部转发给它 —— XML 里的写法因此和
 *       原来的 DshaCard 完全一样，子元素照旧从上往下排。
 *   <li>模糊需要一个 {@code BlurTarget} 作为取样源，而卡片自己不知道它在哪。
 *       附窗口时向上找最近的一个；找不到（比如这个页面不在 MainActivity 里）就安静地
 *       退化成普通卡片，不报错也不留白。
 * </ul>
 */
public class GlassCard extends BlurView {

    private LinearLayout inner;
    private boolean wired;

    public GlassCard(Context context) {
        super(context);
        init();
    }

    public GlassCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlassCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundResource(R.drawable.bg_card);
        int pad = getResources().getDimensionPixelSize(R.dimen.card_pad);
        setPadding(pad, pad, pad, pad);
        // 圆角：BlurView 自己画模糊，不裁的话四个角会溢出方形的模糊块
        setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        setClipToOutline(true);
        inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        super.addView(inner, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (inner == null || child == inner) {
            super.addView(child, index, params);     // 构造期：inner 自己
        } else {
            inner.addView(child, index, params);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!wired) wire();
    }

    private void wire() {
        try {
            BlurTarget target = null;
            for (ViewGroup p = (ViewGroup) getParent(); p != null; ) {
                if (p instanceof BlurTarget) {
                    target = (BlurTarget) p;
                    break;
                }
                p = (p.getParent() instanceof ViewGroup) ? (ViewGroup) p.getParent() : null;
            }
            if (target == null) return;              // 不在可取样的层级里，就当普通卡片
            // 半径比顶栏小一档：卡片面积小，糊过头会变成一团色，看不出层次。
            setupWith(target)
                    .setBlurRadius(14f)
                    .setOverlayColor(DshaGlass.overlayColor(getContext(), 1f));
            wired = true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "卡片玻璃装配失败（退化成普通卡片）: " + t);
        }
    }
}
