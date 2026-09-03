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
    /** 玻璃厚度倍率，见 attrs.xml 的 GlassCard_glassFactor。 */
    private float factor = 1f;

    public GlassCard(Context context) {
        super(context);
        init();
    }

    public GlassCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        readFactor(context, attrs);
        init();
    }

    public GlassCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        readFactor(context, attrs);
        init();
    }

    private void readFactor(Context context, AttributeSet attrs) {
        if (attrs == null) return;
        android.content.res.TypedArray a = null;
        try {
            a = context.obtainStyledAttributes(attrs, R.styleable.GlassCard);
            factor = a.getFloat(R.styleable.GlassCard_glassFactor, 1f);
        } catch (Throwable ignored) {
        } finally {
            if (a != null) a.recycle();
        }
    }

    private void init() {
        // XML 里给的背景决定这张卡片要不要涟漪 —— 列表项是可点的，换成不带 ripple 的
        // 背景会让点击毫无反馈。super(context, attrs) 已经把 XML 背景应用上了，
        // 所以这里能直接判断。
        boolean ripple = getBackground() instanceof android.graphics.drawable.RippleDrawable;
        // 玻璃开着时，背景只能留圆角和描边、填充必须透明 —— BlurView 的绘制顺序是
        // 「先画模糊 + overlay，再 super.draw() 画 background 和子 View」，不透明的
        // background 会把刚画好的模糊整块盖住（症状：卡片看着毫无效果）。
        // 玻璃关着时它就是一张普通卡片，照旧用不透明底。
        boolean glass = DshaGlass.enabled(getContext());
        setBackgroundResource(glass
                ? (ripple ? R.drawable.bg_card_glass_clickable : R.drawable.bg_card_glass)
                : (ripple ? R.drawable.bg_card_clickable : R.drawable.bg_card));
        if (glass && !DshaGlass.stroke(getContext())) {
            // 描边关掉时 GlassDrawable 那边也不描，这里得跟上 —— 否则卡片有边框、
            // 卡片里的按钮没有，比两边都有边更难看。代码建而不是再加一份 drawable：
            // 只差一个 stroke，为它多两个 xml 不值得。
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(android.graphics.Color.TRANSPARENT);
            g.setCornerRadius(getResources().getDimension(R.dimen.radius_card));
            if (ripple) {
                setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                                DshaGlass.overlayColor(getContext(), 0.45f)), g, null));
            } else {
                setBackground(g);
            }
        }
        // 只在 XML 没给 padding 时补默认值。init() 跑在 super(context, attrs) 之后，
        // 无脑 setPadding 会把布局里写的值覆盖掉 —— 设置页那个「模块」容器写的是 2dp，
        // 被改成 card_pad 的话三行内容会突然缩进一大截。
        if (getPaddingTop() == 0 && getPaddingLeft() == 0
                && getPaddingRight() == 0 && getPaddingBottom() == 0) {
            int pad = getResources().getDimensionPixelSize(R.dimen.card_pad);
            setPadding(pad, pad, pad, pad);
        }
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
            if (!DshaGlass.enabled(getContext())) return;   // 玻璃没开 → 就是一张普通卡片
            // 取样源必须**不包含自己**，否则渲染树自己套自己 —— 上一版就是向上找最近的
            // BlurTarget（那个含内容区，卡片就在里面），启动直接栈溢出闪退：
            // logcat 里 500+ 帧的 RenderNode::prepareTreeImpl ↔ prepareListAndChildren。
            // 库的 README 有这条约束，只是当时读过没当真。
            //
            // 所以按 id 找专门那一层：bg_blur_target 只装背景图，不含任何卡片。
            View root = getRootView();
            BlurTarget target = root == null ? null : root.findViewById(R.id.bg_blur_target);
            if (target == null) return;              // 不在主界面里，就当普通卡片
            // 卡片糊的只有背景图（静态内容），所以降采样可以开得比栏更大：
            // scaleFactor 8 出来的是一片干净的色雾，文字压在上面很好读。
            // 半径取用户设定的 85% —— 卡片面积小，和栏用同一个值会糊成一团。
            setupWith(target, 8f, DshaGlass.noise(getContext()))
                    .setBlurRadius(DshaGlass.radius(getContext()) * 0.85f)
                    .setOverlayColor(DshaGlass.overlayColor(getContext(), factor));
            wired = true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "卡片玻璃装配失败（退化成普通卡片）: " + t);
        }
    }
}
