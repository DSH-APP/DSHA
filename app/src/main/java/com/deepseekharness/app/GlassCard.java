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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // **尺寸从 0 变成有效值时重装一次。**
        // RecyclerView 在 ViewPager2 的离屏页里预建 item 时，卡片 attach 了但还没量过，
        // 宽高是 0；wire() 那一刻 BlurView 算不出有效的缩放尺寸，它的 controller 会停在
        // 「没准备好」的状态，而且之后不会自己恢复。症状是切回这一页时卡片整块空白 ——
        // 连里面的文字都不见，因为 BlurView 的 draw 是「先画模糊、再 super.draw() 画
        // background 和子 View」，前半段短路就把子 View 一起跳过了。
        // （反馈：从其他页返回插件管理，列表里的卡片全不见，搜索框和 tab 还在。）
        if (wired && w > 0 && h > 0 && (oldw <= 0 || oldh <= 0)) {
            wired = false;
            wire();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // 变可见时踢一帧。上面那条管的是「装的时候没尺寸」，这条管「装好了但快照是空的」——
        // 离屏时取样区域还没绘制，快照就是一片空白，而 BlurView 不会因为自己变可见就重算。
        // sBlurAllowed 那一道必须在这儿判：不判的话滑动过程中任何一次可见性变化
        // 都会把这张卡片的刷新偷偷打开，等于绕过了 setBlurUpdating(false)。
        if (isVisible && facade != null && sBlurAllowed) {
            try {
                facade.setBlurAutoUpdate(true);
            } catch (Throwable ignored) {
            }
            invalidate();
        }
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
            facade = setupWith(target, 8f, DshaGlass.noise(getContext()))
                    .setBlurRadius(DshaGlass.radius(getContext()) * 0.85f)
                    .setOverlayColor(DshaGlass.overlayColor(getContext(), factor));
            // 正在滑动时建出来的卡片也得跟着停 —— 否则它一出生就在每帧重算模糊，
            // 而滑动恰好是新卡片最多的时刻（相邻页在建 item）。
            if (!sBlurAllowed) facade.setBlurAutoUpdate(false);
            // 登记自己，好让页面横向滑动时统一停掉模糊刷新（见 setBlurUpdating）。
            synchronized (sCards) {
                sCards.add(new java.lang.ref.WeakReference<>(this));
            }
            wired = true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "卡片玻璃装配失败（退化成普通卡片）: " + t);
        }
    }

    private eightbitlab.com.blurview.BlurViewFacade facade;

    /** 所有装配过玻璃的卡片。弱引用 —— 卡片跟着 Fragment 反复创建销毁，
     *  强引用会把整棵 View 树连 Activity 一起钉在内存里。 */
    private static final java.util.List<java.lang.ref.WeakReference<GlassCard>> sCards =
            new java.util.ArrayList<>();

    /** 批量开关卡片的模糊刷新。
     *
     *  <p>给页面横向滑动用：一屏十几张卡，每张都在逐帧重算模糊，在 120Hz 的 8.3ms 帧预算里
     *  是主要超支来源。滑动过程中画面本身在飞，糊的是哪一块根本看不出来，
     *  停掉刷新只会保留进入滑动那一刻的快照 —— 停下来再打开就立刻对上。
     *
     *  <p><b>不能永久关掉。</b>卡片跟着列表上下滚时，它背后对应的背景图区域是在变的，
     *  快照不更新就成了一张贴纸，滚动时能明显看出错位。 */
    /**
     * 当前是否允许卡片自己刷新模糊。
     *
     * <p><b>为什么要记这个状态。</b>setBlurUpdating 只是把命令广播给当时在册的卡片，
     * 之后新登记的卡片、或者刚变可见的卡片都不知道「现在正在滑动、别刷」——
     * 它们会各自把 autoUpdate 打开，把「滑动期间停卡片模糊」这个优化一点点蚀空。
     * 横向滑动时恰好就是新卡片最多的时刻（相邻页在建 item），所以这不是边缘情况。
     */
    private static volatile boolean sBlurAllowed = true;

    static void setBlurUpdating(boolean on) {
        sBlurAllowed = on;
        synchronized (sCards) {
            java.util.Iterator<java.lang.ref.WeakReference<GlassCard>> it = sCards.iterator();
            while (it.hasNext()) {
                GlassCard c = it.next().get();
                if (c == null) {
                    it.remove();               // 顺手清掉已回收的
                    continue;
                }
                try {
                    if (c.facade != null) c.facade.setBlurAutoUpdate(on);
                    if (on) c.invalidate();    // 恢复时立刻重算一帧，别等下一次触发
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
