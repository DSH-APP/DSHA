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
            bm = blur(bm, blurPct(a));
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
