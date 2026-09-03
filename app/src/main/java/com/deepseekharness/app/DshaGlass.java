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
        a = Math.max(0, Math.min(255, a));
        return android.graphics.Color.argb(a,
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base));
    }

    /** 给一棵 View 树应用当前外观参数（卡片透明度 + 圆角）。
     *
     *  <p>圆角和透明度分开判断：圆角是纯视觉偏好，玻璃关着也该生效；透明度只在玻璃开着时动。 */
    static void apply(View root) {
        if (root == null) return;
        try {
            boolean glass = enabled(root.getContext());
            int corner = cornerPx(root.getContext());
            if (!glass && corner < 0) return;      // 两样都不用改，省一次全树遍历
            // 用 overlayPct 而不是另一个"卡片透明度"：这两个分开之后，用户拖玻璃浓度时
            // 只有能装 BlurView 的顶栏底栏和卡片在变，终端页、设置页、插件页那些普通容器
            // 仍是 100% 不透明 —— 表现就是"大部分组件吃不到玻璃效果"。
            // 通透度对用户来说是一个概念，就该由一个参数管。
            int a255 = glass ? (int) (overlayPct(root.getContext()) / 100f * 255f) : 255;
            walk(root, a255, corner);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "卡片透明度应用失败（不影响功能）: " + t);
        }
    }

    /** 拖滑块时用：立即生效，不写 prefs（松手才写，避免每帧一次 IO）。 */
    static void preview(View root, int pct) {
        if (root == null) return;
        try {
            walk(root, (int) (clamp(pct, 20, 100) / 100f * 255f), cornerPx(root.getContext()));
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
            walk(root, glass ? (int) (alpha(root.getContext()) / 100f * 255f) : 255, px);
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
            walk(root, a, cornerPx(root.getContext()));
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

    private static void walk(View v, int alpha255, int cornerPx) {
        // 三类东西不能透，遇到就整棵子树跳过：
        // · 终端 —— Termux 的 ANSI 前景是白色，背景一透就成了浅底白字；
        // · WebView / GeckoView —— 里面是网页自己的排版，透了会让背景图串在正文后面；
        // · 显式标了 no-glass 的（留给以后不想被透的地方，不用改这里的判断）。
        if ("no-glass".equals(v.getTag())) return;
        String cls = v.getClass().getName();
        if (cls.contains("TerminalView") || cls.contains("WebView") || cls.contains("GeckoView")) {
            return;
        }
        Drawable bg = v.getBackground();
        // GlassCard 自己就是 BlurView，透明度由它的 overlayColor 决定；
        // 再给背景 setAlpha 会把圆角描边一起弄淡。只跳过它的 alpha，圆角照样要改。
        if (bg != null && isShapeLike(bg)) {
            Drawable m = bg.mutate();
            if (!(v instanceof GlassCard)) m.setAlpha(alpha255);
            applyCorner(m, cornerPx);
            v.setBackground(m);
            if (v instanceof GlassCard && cornerPx >= 0) {
                v.invalidateOutline();       // clipToOutline 用的是背景的 outline，得重算
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), alpha255, cornerPx);
            }
        }
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
