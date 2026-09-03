package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自定义背景图。
 *
 * <p>玻璃外观没有背景图就没什么意义 —— 半透明卡片压在一层自造的渐变上，透出来的还是我们
 * 自己画的东西。压在用户自己的照片上才叫玻璃。所以这两个功能是配套的：
 * 主题负责让 surface 与卡片带 alpha，这里负责底下有东西可透。
 *
 * <p>做法上的两个选择：
 * <ul>
 *   <li><b>复制进私有目录</b>而不是保存 URI + takePersistableUriPermission。后者在用户
 *       删掉原图、清理相册缓存、或者从某些云盘类 provider 选图之后会失效，而背景失效的
 *       表现是整个界面突然变回纯色，用户根本猜不到是相册那边的事。复制一份最省心，
 *       代价是一张缩放后的图（通常 &lt; 1MB）。
 *   <li><b>按屏幕尺寸缩放</b>。原图动辄 4000×3000，直接当窗口背景是几十 MB 的位图，
 *       低端机上就是 OOM。用 inSampleSize 解码到屏幕量级。
 * </ul>
 *
 * <p>暗化层不是装饰：亮色照片上放浅色文字会完全看不清，而用户选图时不会想到这件事。
 * 默认压 45%，可调。
 */
final class DshaBackground {

    static final String KEY_DIM = "ui_bg_dim";          // 0~80，压暗百分比
    static final int DIM_DEFAULT = 45;
    static final String KEY_BLUR = "ui_bg_blur";        // 0~100，模糊强度
    static final int BLUR_DEFAULT = 0;

    /** 背景图滤镜，存的是 {@link #FILTER_NAMES} 的下标。0 = 原图（不加任何处理）。 */
    static final String KEY_FILTER = "ui_bg_filter";

    static final String[] FILTER_NAMES = {
            "原图（不处理）",
            "黑白",
            "淡雅 · 低饱和",
            "鲜艳 · 高饱和",
            "复古 · 棕调",
            "冷调 · 偏蓝",
            "暖调 · 偏橙",
    };

    static int filterIndex(Context ctx) {
        try {
            int i = ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getInt(KEY_FILTER, 0);
            return (i >= 0 && i < FILTER_NAMES.length) ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static void setFilterIndex(Context ctx, int i) {
        ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putInt(KEY_FILTER, Math.max(0, Math.min(FILTER_NAMES.length - 1, i)))
                .apply();
    }

    /** 当前滤镜对应的 ColorFilter；原图返回 null。
     *
     *  <p>全部用 ColorMatrix 表达 —— 一次矩阵乘法，GPU 直接吃，比预处理位图省内存也省时间，
     *  而且换滤镜不用重新解码图片。 */
    static android.graphics.ColorFilter colorFilter(Context ctx) {
        int i = filterIndex(ctx);
        if (i == 0) return null;
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        switch (i) {
            case 1:
                m.setSaturation(0f);
                break;
            case 2:
                m.setSaturation(0.45f);
                break;
            case 3:
                m.setSaturation(1.7f);
                break;
            case 4:
                // 棕调：先去色再压进棕黄通道，比直接给 RGB 加权更接近旧照片
                m.setSaturation(0f);
                android.graphics.ColorMatrix sepia = new android.graphics.ColorMatrix(new float[]{
                        1.07f, 0, 0, 0, 12,
                        0, 0.96f, 0, 0, 4,
                        0, 0, 0.74f, 0, -6,
                        0, 0, 0, 1, 0});
                m.postConcat(sepia);
                break;
            case 5:
                m.postConcat(new android.graphics.ColorMatrix(new float[]{
                        0.88f, 0, 0, 0, 0,
                        0, 0.96f, 0, 0, 0,
                        0, 0, 1.18f, 0, 8,
                        0, 0, 0, 1, 0}));
                break;
            case 6:
                m.postConcat(new android.graphics.ColorMatrix(new float[]{
                        1.16f, 0, 0, 0, 8,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 0.82f, 0, -4,
                        0, 0, 0, 1, 0}));
                break;
            default:
                return null;
        }
        return new android.graphics.ColorMatrixColorFilter(m);
    }
    private static final String FILE = "ui-background.jpg";

    private DshaBackground() {
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    static boolean exists(Context ctx) {
        File f = file(ctx);
        return f.isFile() && f.length() > 0;
    }

    static int dim(Context ctx) {
        int v = ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getInt(KEY_DIM, DIM_DEFAULT);
        return Math.max(0, Math.min(80, v));
    }

    static void setDim(Context ctx, int v) {
        ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putInt(KEY_DIM, Math.max(0, Math.min(80, v))).apply();
    }

    static void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists() && !f.delete()) {
                android.util.Log.w("DSHA", "背景图删不掉：" + f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把用户选的图缩放后存进私有目录。
     *
     *  @return 失败原因，成功返回 null（这样调用方可以直接把原因显示给用户 ——
     *          「选了没反应」是最难排查的那种反馈）。 */
    static String saveFrom(Context ctx, Uri uri) {
        try {
            int screen = Math.max(
                    ctx.getResources().getDisplayMetrics().widthPixels,
                    ctx.getResources().getDisplayMetrics().heightPixels);
            if (screen <= 0) screen = 2400;

            // 第一遍只读尺寸
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return "读不到这张图（provider 没给流）";
                BitmapFactory.decodeStream(in, null, probe);
            }
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                return "这不像一张图片";
            }

            int longSide = Math.max(probe.outWidth, probe.outHeight);
            int sample = 1;
            while (longSide / (sample * 2) >= screen) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;   // 背景不需要 alpha，省一半内存
            Bitmap bm;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return "读不到这张图";
                bm = BitmapFactory.decodeStream(in, null, opt);
            }
            if (bm == null) return "解码失败（格式不支持？）";

            File out = file(ctx);
            try (OutputStream os = new java.io.FileOutputStream(out)) {
                if (!bm.compress(Bitmap.CompressFormat.JPEG, 88, os)) {
                    return "写文件失败";
                }
            } finally {
                bm.recycle();
            }
            return null;
        } catch (SecurityException e) {
            return "没有读这张图的权限（换个相册或文件管理器再选一次）";
        } catch (OutOfMemoryError e) {
            return "图太大，内存不够 —— 换张小一点的";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 有自定义背景就把它设成窗口背景。
     *
     *  <p>设的是 window 而不是某个 View：布局根节点的背景是 {@code ?attr/dshaSurface}，
     *  各 Fragment 也各有一份，逐个改成透明要碰十几个文件而且以后新增页面必漏。
     *  改成「玻璃外观下 dshaSurface 自带 alpha」，图片放在窗口层，就自动透上来了。
     *
     *  <p>必须在 setContentView 之前调用。 */
    static void apply(Activity a) {
        try {
            if (!exists(a)) return;
            Bitmap bm = BitmapFactory.decodeFile(file(a).getAbsolutePath());
            if (bm == null) return;
            BitmapDrawable pic = new BitmapDrawable(a.getResources(), bm);
            // 铺满并裁切，不拉伸变形
            pic.setGravity(android.view.Gravity.FILL);
            int alpha = (int) (dim(a) / 100f * 255);
            Drawable[] layers = {pic, new ColorDrawable(Color.argb(alpha, 0, 0, 0))};
            a.getWindow().setBackgroundDrawable(new LayerDrawable(layers));
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "自定义背景应用失败（回落主题底衬）: " + t);
        }
    }

    /** 把背景图挂到 View 层（给 MainActivity 用）。
     *
     *  <p>为什么不能只用 window 背景：BlurView 只模糊 {@code BlurTarget} 内部的内容，
     *  而 window 背景在 BlurTarget 外面 —— 挂在那儿的图，玻璃栏是糊不到的，
     *  于是顶栏底栏看着还是"淡色块压在清晰的图上"。所以主界面把图放进 BlurTarget 的
     *  第一个子 View。
     *
     *  <p>压暗用 ColorFilter 而不是再叠一个 View：少一层就少一次全屏合成。 */
    static void applyTo(android.widget.ImageView iv) {
        if (iv == null) return;
        try {
            if (!exists(iv.getContext())) {
                iv.setVisibility(android.view.View.GONE);
                iv.setImageDrawable(null);
                return;
            }
            Bitmap bm = BitmapFactory.decodeFile(file(iv.getContext()).getAbsolutePath());
            if (bm == null) {
                iv.setVisibility(android.view.View.GONE);
                return;
            }
            iv.setImageBitmap(bm);
            // 半透明，而不是叠一层黑：叠黑只是把图压暗，它仍然是一张「实」的图压在界面底下；
            // 降低不透明度会让它和主题底色相融，观感轻得多。
            iv.setColorFilter(colorFilter(iv.getContext()));   // null = 原图
            iv.setImageAlpha(255 - (int) (dim(iv.getContext()) / 100f * 255f));
            iv.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "背景图挂到 View 失败: " + t);
            iv.setVisibility(android.view.View.GONE);
        }
    }

    /** 元素级玻璃用的共用底图：按窗口尺寸解码、糊开、压暗。
     *
     *  <p>和 {@link #applyTo} 那份的区别是这里必须精确匹配窗口尺寸 —— 每个元素按自己的
     *  窗口坐标从这张图上取样，尺寸对不上就会错位。
     *
     *  <p>模糊强度沿用「背景模糊」那个滑块的一半再加固定量：元素级玻璃要的是糊到看不出
     *  形状（否则纹样会干扰文字），比整页底衬需要更狠。 */
    static Bitmap loadBackdrop(Context ctx, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        try {
            if (!exists(ctx)) return null;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = BitmapFactory.decodeFile(file(ctx).getAbsolutePath(), opt);
            if (raw == null) return null;

            // centerCrop 到窗口尺寸：先按比例缩到能盖满，再居中裁
            float scale = Math.max(w / (float) raw.getWidth(), h / (float) raw.getHeight());
            int sw = Math.max(1, Math.round(raw.getWidth() * scale));
            int sh = Math.max(1, Math.round(raw.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(raw, sw, sh, true);
            if (scaled != raw) raw.recycle();
            int dx = Math.max(0, (sw - w) / 2);
            int dy = Math.max(0, (sh - h) / 2);
            Bitmap cropped = Bitmap.createBitmap(scaled, dx, dy,
                    Math.min(w, sw - dx), Math.min(h, sh - dy));
            if (cropped != scaled) scaled.recycle();

            Bitmap out = blur(cropped, Math.min(100, DshaGlass.radius(ctx) * 5 / 2));
            // 滤镜和压暗一起画进去。滤镜必须也作用在玻璃底图上 —— 否则背景图换了色调，
            // 玻璃里透出来的还是原色，两边对不上。压暗则是玻璃内部专属（文字要好读）。
            android.graphics.ColorFilter cf = colorFilter(ctx);
            int dim = dim(ctx);
            if (cf == null && dim <= 0) return out;
            Bitmap dst = Bitmap.createBitmap(out.getWidth(), out.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(dst);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            if (cf != null) p.setColorFilter(cf);
            c.drawBitmap(out, 0, 0, p);
            if (dim > 0) {
                c.drawColor(Color.argb((int) (dim / 100f * 255), 0, 0, 0));
            }
            out.recycle();
            return dst;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "玻璃底图生成失败: " + t);
            return null;
        }
    }

    static int blurPct(Context ctx) {
        int v = ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getInt(KEY_BLUR, BLUR_DEFAULT);
        return Math.max(0, Math.min(100, v));
    }

    static void setBlur(Context ctx, int v) {
        ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putInt(KEY_BLUR, Math.max(0, Math.min(100, v))).apply();
    }

    /** 廉价的高斯近似：缩小再放大，让双线性插值替我们做平滑。
     *
     *  <p>不用 RenderScript（API 31 起废弃）也不用 RenderEffect
     *  （那个模糊的是 View 自己的内容，连文字一起糊，不是我们要的背景模糊）。
     *  真正的 CSS {@code backdrop-filter} 在 Android 上没有等价物 ——
     *  Window#setBackgroundBlurRadius 模糊的是**本窗口背后的其他应用**。
     *  所以只能自己把图糊好再当背景，好处是零运行时开销。
     *
     *  <p>刻意在每次 apply 时做而不是保存时烘进文件：那样强度就调不动了，
     *  而用户拖滑块时需要立刻看到差别。一次约几十毫秒。 */
    private static Bitmap blur(Bitmap src, int pct) {
        if (pct <= 0 || src == null) return src;
        try {
            int div = 2 + (int) (pct / 100f * 14);          // 2~16 倍降采样
            int w = Math.max(1, src.getWidth() / div);
            int h = Math.max(1, src.getHeight() / div);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            Bitmap out = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
            small.recycle();
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;                                     // 糊不了就用原图，别把背景弄丢
        }
    }
}
