package com.deepseekharness.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * 卡片透明度：让界面元素按用户设定的强度透出背景。
 *
 * <p>为什么要遍历 View 而不是改主题：颜色槽（{@code ?attr/dshaCard} 那些）是**主题属性**，
 * 主题在 Activity 创建时解析一次就定了，运行时拖滑块改不动它。而这个值恰恰是最需要
 * 实时反馈的 —— 透明度合不合适只能看着调，让用户每拖一次就重建一次界面是不可接受的。
 *
 * <p>所以走 Drawable 层：卡片、按钮、输入框、芯片的背景都是 shape drawable，
 * {@code setAlpha()} 直接生效，{@code invalidate()} 就能看见。
 *
 * <p>{@link #mutate()} 是必须的：drawable 资源默认在所有使用者之间共享同一个
 * ConstantState，不 mutate 就会把 alpha 改到别的 View 上去（表现是「调了一个卡片，
 * 整个 App 包括没显示的页面都变了，且退出重进后残留」）。
 *
 * <p>刻意不碰 {@code View.setAlpha()}：那个会把文字和图标一起变淡，读不清；
 * 我们只要背景变透，前景照旧。
 */
final class DshaGlass {

    /** 0~100。100 = 完全不透明（默认，与旧外观一致）。 */
    static final String KEY_ALPHA = "ui_card_alpha";
    static final int ALPHA_DEFAULT = 100;

    /** 玻璃效果总开关。
     *
     *  <p>玻璃是**叠加**在配色之上的，不是一套配色 —— 这样「樱花 + 玻璃」「蓝灰 + 玻璃」
     *  都表达得出来，而配色又能各自跟随系统深浅。早先把它做成第三套主题，
     *  结果是玻璃被钉死在深色上、樱花永远没有玻璃。
     *
     *  <p>关掉时：卡片与栏都是不透明的，BlurView 不装配（连快照都不记）。 */
    static final String KEY_ENABLED = "ui_glass";

    static boolean enabled(Context ctx) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
            if (sp.contains(KEY_ENABLED)) return sp.getBoolean(KEY_ENABLED, false);
            // 没有这个键 = 从「玻璃是一套主题」的旧版升上来。之前设过背景图、或者把卡片
            // 透明度调低过的人，本来就在用玻璃 —— 默认给开，别让一次升级把效果悄悄关掉。
            boolean had = DshaBackground.exists(ctx) || sp.getInt(KEY_ALPHA, ALPHA_DEFAULT) < 100;
            sp.edit().putBoolean(KEY_ENABLED, had).apply();
            return had;
        } catch (Throwable t) {
            return false;
        }
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
    }

    // ── 玻璃的四个可调项 ──────────────────────────────────────────
    // 都各自独立成键，不复用 KEY_ALPHA：那个管的是"没开玻璃时卡片透多少"，
    // 和"玻璃着色浓度"是两回事，混用会导致关掉玻璃后卡片跟着变淡。

    /** 玻璃浓度 20~100：BlurView overlay 的不透明度。低=通透但字难读，高=接近实色。 */
    static final String KEY_OVERLAY = "ui_glass_overlay";
    static final int OVERLAY_DEFAULT = 72;

    /** 模糊强度 4~40（对应 BlurView 的 blurRadius）。 */
    static final String KEY_RADIUS = "ui_glass_radius";
    static final int RADIUS_DEFAULT = 24;

    /** 细噪点。BlurView 自带的蓝噪声纹理，压掉纯模糊那种"塑料感"。改了要重建界面。 */
    static final String KEY_NOISE = "ui_glass_noise";

    /** 卡片圆角 0~32dp。不是玻璃专属，但归在同一个面板里调更顺手。 */
    static final String KEY_CORNER = "ui_corner";
    static final int CORNER_DEFAULT = -1;          // -1 = 用布局里的 radius_card，不干预

    static int overlayPct(Context ctx) {
        return clamp(pref(ctx).getInt(KEY_OVERLAY, OVERLAY_DEFAULT), 20, 100);
    }

    static void setOverlayPct(Context ctx, int v) {
        pref(ctx).edit().putInt(KEY_OVERLAY, clamp(v, 20, 100)).apply();
    }

    static int radius(Context ctx) {
        return clamp(pref(ctx).getInt(KEY_RADIUS, RADIUS_DEFAULT), 4, 40);
    }

    static void setRadius(Context ctx, int v) {
        pref(ctx).edit().putInt(KEY_RADIUS, clamp(v, 4, 40)).apply();
    }

    static boolean noise(Context ctx) {
        return pref(ctx).getBoolean(KEY_NOISE, true);
    }

    static void setNoise(Context ctx, boolean on) {
        pref(ctx).edit().putBoolean(KEY_NOISE, on).apply();
    }

    /** 玻璃元素要不要描边。默认要 —— 没有边界的半透明块会和背景糊在一起、
     *  分不清哪里是可点的区域；但有人就喜欢那种干净，所以给个开关。 */
    static final String KEY_STROKE = "ui_glass_stroke";

    static boolean stroke(Context ctx) {
        return pref(ctx).getBoolean(KEY_STROKE, true);
    }

    static void setStroke(Context ctx, boolean on) {
        pref(ctx).edit().putBoolean(KEY_STROKE, on).apply();
    }

    /** @return 圆角像素；负数表示"别改，用布局原值"。 */
    static int cornerPx(Context ctx) {
        int dp = pref(ctx).getInt(KEY_CORNER, CORNER_DEFAULT);
        if (dp < 0) return -1;
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }

    static int cornerDp(Context ctx) {
        return pref(ctx).getInt(KEY_CORNER, CORNER_DEFAULT);
    }

    static void setCornerDp(Context ctx, int dp) {
        pref(ctx).edit().putInt(KEY_CORNER, clamp(dp, 0, 32)).apply();
    }

    private static android.content.SharedPreferences pref(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private DshaGlass() {
    }

    static int alpha(Context ctx) {
        int v = ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getInt(KEY_ALPHA, ALPHA_DEFAULT);
        return Math.max(20, Math.min(100, v));   // 低于 20% 就完全看不清字了，挡住
    }

    static void setAlpha(Context ctx, int v) {
        ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putInt(KEY_ALPHA, Math.max(20, Math.min(100, v))).apply();
    }

    /** 玻璃层的着色：主题卡片色 + 用户设定的不透明度。
     *
     *  <p>完全不透明就看不见模糊，完全透明则文字压在花纹上读不清 —— 中间那一档才叫玻璃。
     *
     *  @param factor 再乘一档，用来让常驻的顶栏底栏比内容卡片更透（压太实等于没做）。 */
    static int overlayColor(Context ctx, float factor) {
        int base = 0xFF161B24;
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(R.attr.dshaCard, tv, true)) {
                base = tv.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
                        : tv.data;
            }
        } catch (Throwable ignored) {
        }
        int a = (int) (overlayPct(ctx) / 100f * 255f * factor);
        // 浅色配色 + 深色背景图是天然冲突：文字是深色、需要浅底衬，而图本身是深的。
        // 白色 overlay 得更实一档才压得住，不然整屏灰蒙蒙 —— 反馈里的「浅色模式异常昏暗」
        // 就是这么来的（同一个 76% 浓度，在深色配色下够用，浅色配色下远远不够）。
        if (isLightTheme(ctx)) {
            // 加成从 1.3 降到 1.12，并且封顶 216（≈85%）。
            // 1.3 倍是上一轮为了治「浅色模式异常昏暗」加的，但没算清后果：
            // 默认浓度 76% × 1.3 = 98.8% —— 卡片直接变成不透明，玻璃全废
            // （反馈：「浅色模式下几乎所有卡片都是不透明的」）。
            // 浅色配色配深色背景图确实需要实一点才压得住，但必须留出能看见背景的余量。
            a = Math.min((int) (a * 1.12f), 216);
        }
        // **统一封顶 235（≈92%）。**上面两个乘数（调用方给的 factor、浅色配色的加成）
        // 都是直接乘在 alpha 上的，任何一个偏大就会把卡片顶成不透明 —— 玻璃当场作废，
        // 而且症状看起来像「这个控件没做玻璃」，很难往回追到一个乘数上。
        // 插件卡片的 glassFactor=1.3 和浅色加成 1.3 都是这么翻的车，所以在出口处兜一道。
        a = Math.max(0, Math.min(235, a));
        return android.graphics.Color.argb(a,
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base));
    }

    /** 当前是不是浅色配色。按 dshaCard 的亮度判断，比读系统深浅色模式准 ——
     *  用户可能在浅色系统里选了樱花深色那套，或者反过来。 */
    private static boolean isLightTheme(Context ctx) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(R.attr.dshaCard, tv, true)) {
                int c = tv.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
                        : tv.data;
                // 简单亮度：浅色卡片的三通道都接近 255
                int lum = (android.graphics.Color.red(c) * 30
                        + android.graphics.Color.green(c) * 59
                        + android.graphics.Color.blue(c) * 11) / 100;
                return lum > 140;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ── 元素级玻璃（GlassDrawable）───────────────────────────────
    // BlurView 只能覆盖少数容器，容器里的按钮、状态行、输入框全是干的 —— 这是观感上最扎眼的
    // 问题。但那些元素背后基本只有静态背景图，没必要每帧重算：糊好一份全屏底图共用，
    // 每个元素按自己的窗口坐标取对应区域，纯 drawable 绘制，开销和普通色块一样。

    private static android.graphics.Bitmap sBackdrop;
    private static int sBackdropW;
    private static int sBackdropH;
    /** 已经铺了玻璃背景的 View -> 它的 drawable，用来在 preDraw 时刷新位置。弱引用避免泄漏。 */
    private static final java.util.WeakHashMap<View, GlassDrawable> LIVE = new java.util.WeakHashMap<>();
    /** apply 过的根视图。底图异步完成时要重新套到尚未出现在 decor 树里的页面。 */
    private static final java.util.WeakHashMap<View, Boolean> ROOTS = new java.util.WeakHashMap<>();
    /** 尚未 attach 的根视图只挂一次回调，避免 ViewPager2 建页时重复登记。 */
    private static final java.util.WeakHashMap<View, Boolean> ATTACH_HOOKS = new java.util.WeakHashMap<>();
    /** 每个 View 的原始背景与当前玻璃实例。避免重复 apply 时反复替换 RippleDrawable 内容层。 */
    private static final java.util.WeakHashMap<View, GlassState> MANAGED =
            new java.util.WeakHashMap<>();

    private static final class GlassState {
        final Drawable source;
        final Drawable sourceContent;
        final float sourceCorner;
        final float sourceOriginalCorner;
        final int sourceAlpha;
        final boolean ripple;
        GlassDrawable glass;
        boolean installed;

        GlassState(Drawable source, View view) {
            this.source = source;
            this.ripple = source instanceof android.graphics.drawable.RippleDrawable;
            if (ripple) {
                android.graphics.drawable.RippleDrawable rd =
                        (android.graphics.drawable.RippleDrawable) source;
                sourceContent = rd.getNumberOfLayers() > 0 ? rd.getDrawable(0) : null;
            } else {
                sourceContent = null;
            }
            sourceCorner = readCorner(source, view);
            sourceOriginalCorner = readOriginalCorner(source);
            sourceAlpha = source.getAlpha();
        }
    }

    /** 记住一个 apply 入口；底图在后台生成后，尚未进入 decor 树的页面也要被重新应用。 */
    private static void rememberRoot(View root) {
        ROOTS.put(root, Boolean.TRUE);
        if (root.getWindowToken() != null || ATTACH_HOOKS.containsKey(root)) return;
        ATTACH_HOOKS.put(root, Boolean.TRUE);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                ATTACH_HOOKS.remove(v);
                apply(v);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
    }

    /** 在主线程统一刷新所有登记过的根视图。 */
    private static void applyKnownRoots() {
        java.util.ArrayList<View> roots = new java.util.ArrayList<>(ROOTS.size() + 4);
        try {
            roots.addAll(ROOTS.keySet());
        } catch (Throwable ignored) {
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            View root = roots.get(i);
            if (root != null && root.getWindowToken() != null && !hasKnownAncestor(root)) apply(root);
        }
        roots.clear();
    }

    /** decor、Fragment 根和 RecyclerView item 可能同时登记；有祖先已登记时跳过子树，
     *  但 detached 的页面没有登记祖先，仍会被单独刷新。 */
    private static boolean hasKnownAncestor(View view) {
        android.view.ViewParent p = view.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 64) {
            if (ROOTS.containsKey((View) p)) return true;
            p = p.getParent();
        }
        return false;
    }

    /** preDraw 回调里复用的两个缓冲。只在主线程用，不需要同步。 */
    private static final java.util.ArrayList<View> sPreDrawBuf = new java.util.ArrayList<>(32);
    private static final int[] sPreDrawXY = new int[2];
    /** 挂过 preDraw 的 decorView。**弱引用** —— 静态字段强持有 decorView 就是泄漏整个 Activity。 */
    private static java.lang.ref.WeakReference<View> sHooked;

    /** 准备共用底图。没有自定义背景图时返回 false —— 那种情况下退回原来的 setAlpha 路子，
     *  因为主题底衬本身就是纯色或渐变，糊它没有意义。 */
    private static volatile boolean sBackdropLoading = false;
    private static String sBackdropKey = "";
    private static String sBackdropFailedKey = "";

    private static int backdropWidth(Context ctx) {
        return Math.max(1, ctx.getResources().getDisplayMetrics().widthPixels);
    }

    private static int backdropHeight(Context ctx) {
        return Math.max(1, ctx.getResources().getDisplayMetrics().heightPixels);
    }

    private static String backdropKey(Context ctx, int w, int h) {
        try {
            java.io.File f = DshaBackground.file(ctx);
            if (!f.isFile() || f.length() <= 0) return "";
            return f.length() + ":" + f.lastModified() + ":" + w + ":" + h
                    + ":" + radius(ctx);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isBackdropReady(View root) {
        if (root == null || sBackdrop == null || sBackdrop.isRecycled()) return false;
        int w = backdropWidth(root.getContext());
        int h = backdropHeight(root.getContext());
        return sBackdropW == w && sBackdropH == h
                && !sBackdropKey.isEmpty()
                && sBackdropKey.equals(backdropKey(root.getContext(), w, h));
    }

    private static boolean ensureBackdrop(View root) {
        try {
            if (!DshaBackground.exists(root.getContext())) return false;
            final int w = backdropWidth(root.getContext());
            final int h = backdropHeight(root.getContext());
            final String key = backdropKey(root.getContext(), w, h);
            if (isBackdropReady(root)) return true;
            // 玻璃图是按屏幕尺寸生成的；如果只是参数或背景发生变化，先继续使用旧图，
            // 直到新图完成，避免页面在「GlassDrawable / 普通 alpha」之间闪回。
            boolean usableOld = sBackdrop != null && !sBackdrop.isRecycled()
                    && sBackdropW == w && sBackdropH == h;
            if (key.equals(sBackdropFailedKey)) return usableOld;
            // **底图在后台线程生成。**即便降采样之后，解码 + 缩放 + 三次 box blur
            // 也是几十到上百毫秒的活，放在主线程就是一个必然掉的长帧。
            // 生成期间保留旧页面的当前状态；生成完成后统一刷新已登记的根视图，
            // 不让先创建的页面停在半透明、后创建的页面已经是玻璃。
            if (!sBackdropLoading) {
                sBackdropLoading = true;
                final String loadingKey = key;
                final Context appCtx = root.getContext().getApplicationContext();
                Thread t = new Thread(() -> {
                    android.graphics.Bitmap bm = null;
                    try {
                        bm = DshaBackground.loadBackdrop(appCtx, w, h);
                    } catch (Throwable ignored) {
                    }
                    final android.graphics.Bitmap result = bm;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        sBackdropLoading = false;
                        // 用户可能在后台生成期间换图、清图或旋转屏幕；不能把旧结果
                        // 绑定到新状态。下一次 apply 会按新的 key 重新生成。
                        if (result == null) {
                            sBackdropFailedKey = loadingKey;
                            applyKnownRoots();
                            return;
                        }
                        if (!loadingKey.equals(backdropKey(appCtx, w, h))) {
                            applyKnownRoots();
                            return;
                        }
                        sBackdropFailedKey = "";
                        // 换掉旧底图之前先解引用。不能直接 recycle —— 已有的 GlassDrawable
                        // 还拿着它画，recycle 掉会当场 "Canvas: trying to use a recycled bitmap"。
                        android.graphics.Bitmap old = sBackdrop;
                        sBackdrop = result;
                        sBackdropW = w;
                        sBackdropH = h;
                        sBackdropKey = loadingKey;
                        if (old != null && old != result) {
                            for (GlassDrawable gd : snapshotLive()) {
                                if (gd != null) gd.setBackdrop(result);
                            }
                        }
                        // 关键：FragmentStateAdapter 的新页面可能尚未挂进 decorView，
                        // 因此不能只 apply 最初触发加载的 root。
                        applyKnownRoots();
                    });
                }, "dsha-backdrop");
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
            return usableOld;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "玻璃底图准备失败（退回半透明）: " + t);
            return false;
        }
    }

    /** LIVE 的快照。WeakHashMap 在遍历过程中会清理已被回收的 key，直接迭代 entrySet
     *  可能撞上 ConcurrentModificationException —— 每帧都跑的代码不能有这种概率崩。 */
    private static java.util.List<GlassDrawable> snapshotLive() {
        java.util.List<GlassDrawable> out = new java.util.ArrayList<>(LIVE.size() + 4);
        try {
            out.addAll(LIVE.values());
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 每帧刷新一次所有玻璃元素的窗口坐标。
     *
     *  <p>Drawable 拿不到宿主 View 的位置，硬件加速下 canvas.getMatrix() 也不可靠，所以从
     *  外面喂。挂在 decorView 的 preDraw 上：滚动、展开、键盘弹出都会触发，位置始终对得上。
     *  遍历的是几十个元素的 getLocationInWindow，一次几十微秒。 */
    private static void hookPreDraw(View root) {
        final View decor = root.getRootView();
        if (decor == null) return;
        if (sHooked != null && sHooked.get() == decor) return;
        sHooked = new java.lang.ref.WeakReference<>(decor);
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (LIVE.isEmpty()) return true;
            // **这个回调每帧都跑**，一屏二十来个元素，任何分配都会被乘以帧率。
            // 原来每帧 new 一个 ArrayList 装快照、再 new 一个 int[2]，
            // 在 120Hz 下就是每秒 240 个短命对象 + 相应的 GC 压力；
            // gfxinfo 里 CPU 50 分位 10~17ms（帧预算只有 8.3ms）有一部分出在这里。
            // 缓冲改成复用的静态字段 —— OnPreDrawListener 一定在主线程，不需要同步。
            sPreDrawBuf.clear();
            try {
                // 仍然要拷一份再遍历：WeakHashMap 在迭代过程中会顺手清理被回收的 key，
                // 直接迭代 keySet 可能撞上 ConcurrentModificationException。
                sPreDrawBuf.addAll(LIVE.keySet());
            } catch (Throwable t) {
                sPreDrawBuf.clear();
                return true;
            }
            for (int i = 0; i < sPreDrawBuf.size(); i++) {
                View v = sPreDrawBuf.get(i);
                // isShown 只是沿父链查几个 flag，很便宜；getLocationInWindow 要沿树累加
                // 每一层的偏移与矩阵，贵得多。先筛一遍，能挡掉已经 detach 或 GONE 的。
                if (v == null || !v.isShown()) continue;
                GlassDrawable gd = LIVE.get(v);
                if (gd == null) continue;
                v.getLocationInWindow(sPreDrawXY);
                // setWindowOffset 自己会判「值没变就不 invalidate」，这里不用再比一次。
                gd.setWindowOffset(sPreDrawXY[0], sPreDrawXY[1]);
            }
            // 必须清空：留着强引用会让 WeakHashMap 里的 key 永远回收不掉。
            sPreDrawBuf.clear();
            return true;
        });
    }

    /** 当前背景是否仍由我们管理。外部重新 setBackground 后要丢弃旧状态，避免把新背景误当成旧 Ripple。 */
    private static boolean owns(GlassState state, Drawable current) {
        if (current == state.source) {
            if (!state.installed) return true;
            if (!state.ripple) return false;
            android.graphics.drawable.RippleDrawable rd =
                    (android.graphics.drawable.RippleDrawable) state.source;
            return rd.getNumberOfLayers() > 0 && rd.getDrawable(0) == state.glass;
        }
        return state.installed && current == state.glass;
    }

    /** 取得或创建一个 View 的背景状态。玻璃 Drawable 自己不是 shape，不能再拿它当原背景。 */
    private static GlassState stateFor(View v, Drawable current) {
        GlassState state = MANAGED.get(v);
        if (state != null) {
            if (owns(state, current)) return state;
            // View 被外部换了背景时不能把外部背景覆盖回来，但仍要把我们曾经
            // 改过的原 Ripple 内容、alpha 和圆角恢复，避免污染下一次使用。
            restoreSource(state);
            MANAGED.remove(v);
            LIVE.remove(v);
        }
        if (current == null || current instanceof GlassDrawable || !isShapeLike(current)) return null;
        state = new GlassState(current, v);
        MANAGED.put(v, state);
        return state;
    }

    /** 恢复原背景对象内部被 fallback 改过的属性，但不改变 View 当前指向的对象。 */
    private static void restoreSource(GlassState state) {
        if (state == null) return;
        if (state.ripple) {
            android.graphics.drawable.RippleDrawable rd =
                    (android.graphics.drawable.RippleDrawable) state.source.mutate();
            if (rd.getNumberOfLayers() > 0) rd.setDrawable(0, state.sourceContent);
        }
        state.source.setAlpha(state.sourceAlpha);
        if (state.sourceOriginalCorner >= 0) {
            applyCorner(state.source, Math.round(state.sourceOriginalCorner));
        }
    }

    /** 从 View 中卸下玻璃，恢复最初的背景对象和 Ripple 内容层。 */
    private static void restoreGlass(View v, GlassState state) {
        if (state == null) return;
        restoreSource(state);
        if (state.installed && v.getBackground() != state.source) {
            v.setBackground(state.source);
        }
        state.installed = false;
        LIVE.remove(v);
    }

    /** 把一个 shape 背景换成玻璃背景；已安装时只更新同一个实例的参数。 */
    private static boolean applyGlassBg(View v, GlassState state, int alpha255, int cornerPx) {
        if (state == null || sBackdrop == null || sBackdrop.isRecycled()) return false;
        // 纯色底衬不参与元素级玻璃，保持为普通背景并由调用方控制 alpha。
        if (state.source instanceof android.graphics.drawable.ColorDrawable) return false;
        try {
            // 生成期间 fallback 可能改过 source 的 alpha；安装同一份玻璃前先恢复原层，
            // 否则 Ripple/Gradient 的 alpha 会与 GlassDrawable 的 tint 叠乘。
            restoreSource(state);
            float corner = cornerPx >= 0 ? cornerPx : state.sourceCorner;
            int base = overlayColor(v.getContext(), 1f);
            int tint = android.graphics.Color.argb(alpha255,
                    android.graphics.Color.red(base),
                    android.graphics.Color.green(base),
                    android.graphics.Color.blue(base));
            int line = stroke(v.getContext())
                    ? resolveColor(v.getContext(), R.attr.dshaLine, 0x33FFFFFF) : 0;
            float sw = v.getResources().getDimension(R.dimen.stroke);
            if (state.glass == null) {
                state.glass = new GlassDrawable(sBackdrop, tint, line, corner, sw);
            } else {
                // 这些 setter 都是幂等的；重复 apply 不会创建 Drawable 或替换 Ripple 层。
                state.glass.setBackdrop(sBackdrop);
                state.glass.setGlassTint(tint);
                state.glass.setGlassStroke(line, sw);
                state.glass.setCorner(corner);
            }

            if (state.ripple) {
                android.graphics.drawable.RippleDrawable rd =
                        (android.graphics.drawable.RippleDrawable) state.source.mutate();
                if (rd.getNumberOfLayers() > 0) {
                    if (rd.getDrawable(0) != state.glass) rd.setDrawable(0, state.glass);
                    if (v.getBackground() != rd) v.setBackground(rd);
                } else if (v.getBackground() != state.glass) {
                    v.setBackground(state.glass);
                }
            } else if (v.getBackground() != state.glass) {
                v.setBackground(state.glass);
            }
            state.installed = true;
            LIVE.put(v, state.glass);
            v.getLocationInWindow(sPreDrawXY);
            state.glass.setWindowOffset(sPreDrawXY[0], sPreDrawXY[1]);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 沿用原背景的圆角，这样按钮、输入框、卡片各自的形状差异不会被抹平。 */
    private static float readCorner(Drawable d, View v) {
        try {
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                float r = ((android.graphics.drawable.GradientDrawable) d).getCornerRadius();
                if (r > 0) return r;
            }
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld =
                        (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    float r = readCorner(ld.getDrawable(i), v);
                    if (r > 0) return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return v.getResources().getDimension(R.dimen.radius_card);
    }

    /** 读取原 Drawable 的真实圆角；没有明确圆角时返回 -1，不能用默认卡片圆角代替。 */
    private static float readOriginalCorner(Drawable d) {
        try {
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable g =
                        (android.graphics.drawable.GradientDrawable) d;
                float r = g.getCornerRadius();
                if (r > 0) return r;
                float[] rs = g.getCornerRadii();
                if (rs != null) {
                    float max = 0;
                    for (int i = 0; i < rs.length; i++) max = Math.max(max, rs[i]);
                    if (max > 0) return max;
                }
                return -1;
            }
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld =
                        (android.graphics.drawable.LayerDrawable) d;
                float max = -1;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    max = Math.max(max, readOriginalCorner(ld.getDrawable(i)));
                }
                return max;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static int resolveColor(Context ctx, int attr, int fallback) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
                return tv.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
                        : tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 这个 View 在玻璃卡片（BlurView / GlassCard）内部吗。
     *
     *  <p>卡片内的元素**不该**再去取样背景图：它背后已经是一块糊过的卡片，再糊一次等于把
     *  两种材质叠在一起 —— 而且两者的模糊来源还不同（BlurView 糊实时内容、GlassDrawable
     *  糊静态底图），位置和虚化程度都对不上。屏幕上就会出现「外层是毛玻璃、里面的行却透出
     *  清晰的背景纹理」这种割裂感。
     *
     *  <p>卡片内的元素应该表现为「卡片表面上的一层」——Material 里就是 surface 加一档
     *  elevation tint 的做法，层次靠明度差表达，而不是靠再透一层。 */
    private static boolean insideGlass(View v) {
        android.view.ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 32) {
            if (p instanceof eightbitlab.com.blurview.BlurView) return true;
            p = p.getParent();
        }
        return false;
    }

    /** 底下有没有东西可透 —— 自定义背景图或填充背景。
     *  两者都没有的时候，纯色底衬得留着（透了就是一片全黑，什么层次都没有）。 */
    private static boolean hasBackdropSource(Context ctx) {
        try {
            return DshaBackground.exists(ctx) || DshaBackground.fillIndex(ctx) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给一棵 View 树应用当前外观参数（卡片透明度 + 圆角）。
     *
     *  <p>圆角和透明度分开判断：圆角是纯视觉偏好，玻璃关着也该生效；透明度只在玻璃开着时动。 */
    static void apply(View root) {
        if (root == null) return;
        rememberRoot(root);
        try {
            boolean glass = enabled(root.getContext());
            int corner = cornerPx(root.getContext());
            // 即使两个设置都恢复默认，也要走一遍：这一步负责卸下此前安装的玻璃背景。
            // 不能为了省遍历直接 return，否则用户关闭玻璃后旧 Drawable 会继续留着。
            int a255 = glass ? (int) (overlayPct(root.getContext()) / 100f * 255f) : 255;
            boolean useBackdrop = glass && ensureBackdrop(root);
            // 生成期间统一走普通背景，避免同一屏一部分是旧玻璃、一部分是半透明。
            if (!useBackdrop) {
                sBackdrop = null;
                sBackdropKey = "";
            } else {
                hookPreDraw(root);
            }
            walk(root, a255, corner, useBackdrop);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "卡片透明度应用失败（不影响功能）: " + t);
        }
    }

    /** 拖滑块时用：立即生效，不写 prefs（松手才写，避免每帧一次 IO）。 */
    static void preview(View root, int pct) {
        if (root == null) return;
        try {
            boolean useBackdrop = enabled(root.getContext())
                    && sBackdrop != null && !sBackdrop.isRecycled();
            walk(root, (int) (clamp(pct, 20, 100) / 100f * 255f),
                    cornerPx(root.getContext()), useBackdrop);
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 圆角滑块的实时预览。 */
    static void previewCorner(View root, int dp) {
        if (root == null) return;
        try {
            int px = (int) (clamp(dp, 0, 32) * root.getResources().getDisplayMetrics().density);
            boolean glass = enabled(root.getContext());
            boolean useBackdrop = glass && sBackdrop != null && !sBackdrop.isRecycled();
            walk(root, useBackdrop ? (int) (overlayPct(root.getContext()) / 100f * 255f) : 255,
                    px, useBackdrop);
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 玻璃浓度 / 模糊强度的实时预览。
     *
     *  <p>同一个动作要改两处：能装 BlurView 的（顶栏底栏、GlassCard）改它的 radius 与
     *  overlay；其余普通容器改背景 alpha。分开调的话用户拖一次只有一半界面在变。 */
    static void previewBlur(View root, int radius, int overlayPct) {
        if (root == null) return;
        try {
            int a = (int) (clamp(overlayPct, 20, 100) / 100f * 255f);
            walkBlur(root, clamp(radius, 4, 40), a);
            boolean useBackdrop = enabled(root.getContext())
                    && sBackdrop != null && !sBackdrop.isRecycled();
            walk(root, a, cornerPx(root.getContext()), useBackdrop);
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    private static void walkBlur(View v, int radius, int alpha255) {
        if (v instanceof eightbitlab.com.blurview.BlurView) {
            eightbitlab.com.blurview.BlurView bv = (eightbitlab.com.blurview.BlurView) v;
            int base = overlayColor(v.getContext(), 1f);
            int c = android.graphics.Color.argb(alpha255,
                    android.graphics.Color.red(base),
                    android.graphics.Color.green(base),
                    android.graphics.Color.blue(base));
            bv.setBlurRadius(radius);
            bv.setOverlayColor(c);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkBlur(g.getChildAt(i), radius, alpha255);
            }
        }
    }

    /**
     * 图层契约（**这是唯一定义，改任何一层前先读完**）。
     *
     * <p>之所以写死在这里：这块逻辑被反复改过四次，每次都只按当下看到的现象打补丁 ——
     * 底衬在「透明 / 半透明 / 铺糊底图」之间来回翻，结果是修好一个现象、冒出另一个。
     * 从下到上：
     *
     * <pre>
     * ① 背景图 ImageView（app_background）
     *      「背景淡化」「背景模糊」**只**作用于这一层。原图默认完全可见。
     * ② 页面底衬（ColorDrawable，各 Fragment 根 ScrollView 的 ?attr/dshaSurface）
     *      有背景图时 **alpha = 0，完全让路**。可读性由 ③ 负责，不准在这一层盖任何东西
     *      —— 半透明会遮住背景图（表现为「滑块没作用」），铺糊底图会让人以为背景被糊了
     *      （表现为「模糊 0% 却还生效」）。没有背景图时保持不透明，否则一片全黑。
     * ③ 卡片（GlassCard / BlurView）
     *      真模糊，取样 bg_blur_target（那里只有背景图）。文字下面必须有卡片 ——
     *      每个页面都得有，workspace 与 install 就是为此补的。
     * ④ 卡片内的元素
     *      纯色叠加（比卡片实一档），**不透**背景图。否则同一张卡片里会同时出现
     *      毛玻璃和清晰纹理，材质割裂。
     * ⑤ 不在卡片内的 shape 元素
     *      GlassDrawable：取样预糊底图的对应区域。
     * ⑥ 顶栏 / 底栏（BlurView）
     *      真模糊，取样 blur_target（含内容区），所以滚动时纹样会跟着动。
     * </pre>
     */
    /** 把一个 shape 背景应用为玻璃，或恢复为普通半透明背景。 */
    private static void walk(View v, int alpha255, int cornerPx, boolean useBackdrop) {
        // 三类内容不允许背景图透进去：终端、网页以及显式 no-glass 子树。
        if ("no-glass".equals(v.getTag())) return;
        String cls = v.getClass().getName();
        if (cls.contains("TerminalView") || cls.contains("WebView") || cls.contains("GeckoView")) {
            return;
        }

        Drawable bg = v.getBackground();
        boolean bar = v.getId() == R.id.top_glass || v.getId() == R.id.bottom_glass;
        if (bar) {
            // 顶栏和底栏由 setupGlass 自己管理，不能让静态 GlassDrawable 盖住实时 BlurView。
            GlassState old = MANAGED.get(v);
            if (old != null) {
                if (owns(old, bg)) restoreGlass(v, old);
                else {
                    restoreSource(old);
                    MANAGED.remove(v);
                    LIVE.remove(v);
                }
            }
        } else {
            GlassState state = stateFor(v, bg);
            boolean inGlass = insideGlass(v);
            boolean done = useBackdrop && !(v instanceof GlassCard) && !inGlass
                    && applyGlassBg(v, state, alpha255, cornerPx);
            if (!done) {
                // 底图未就绪、玻璃关闭、或 View 已经在 GlassCard 内时，先恢复原背景。
                // 这一步也是幂等的：没有安装玻璃时不会触碰 View 的背景对象。
                if (state != null) restoreGlass(v, state);
                bg = v.getBackground();
                if (bg != null && isShapeLike(bg)) {
                    int fallbackCorner = cornerPx >= 0
                            ? cornerPx
                            : state == null ? -1 : Math.round(state.sourceCorner);
                    applyFallback(v, bg, alpha255, fallbackCorner, inGlass);
                }
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), alpha255, cornerPx, useBackdrop);
            }
        }
    }

    /** 普通半透明路径，只改背景 Drawable，不改 View 本身的 alpha。 */
    private static void applyFallback(View v, Drawable bg, int alpha255,
                                      int cornerPx, boolean inGlass) {
        Drawable m = bg.mutate();
        if (!(v instanceof GlassCard)) {
            if (inGlass) {
                m.setAlpha(Math.min(255, alpha255 + 55));
            } else if (m instanceof android.graphics.drawable.ColorDrawable
                    && hasBackdropSource(v.getContext())) {
                // 页面底衬有背景源时完全让路，否则背景图会被整屏幕布挡住。
                m.setAlpha(0);
            } else {
                m.setAlpha(alpha255);
            }
        }
        applyCorner(m, cornerPx);
        if (v.getBackground() != m) v.setBackground(m);
        if (v instanceof GlassCard && cornerPx >= 0) v.invalidateOutline();
    }

    /** 统一圆角。
     *
     *  <p>只有 shape drawable 认这个 —— 圆点（oval）不受影响，图标与图片也不碰。
     *  Ripple 继承 LayerDrawable，所以枚举子层这一条把两种情况都覆盖了。 */
    private static void applyCorner(Drawable d, int px) {
        if (px < 0 || d == null) return;
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) d).setCornerRadius(px);
        } else if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld =
                    (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                applyCorner(ld.getDrawable(i), px);
            }
        }
    }

    /** 只动我们自己画的形状背景。
     *
     *  <p>把 ColorDrawable 也算进来是刻意的 —— 页面根节点的 {@code ?attr/dshaSurface}
     *  就是纯色，不透它的话背景图只能从卡片缝隙里露出来，看着像贴纸而不是玻璃。
     *
     *  <p>不碰 BitmapDrawable / VectorDrawable：那是图标和图片，透了就是掉色。 */
    private static boolean isShapeLike(Drawable d) {
        return d instanceof android.graphics.drawable.GradientDrawable
                || d instanceof android.graphics.drawable.ColorDrawable
                || d instanceof android.graphics.drawable.RippleDrawable
                || d instanceof android.graphics.drawable.LayerDrawable;
    }
}
