package com.deepseekharness.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 元素级玻璃背景：直接把「预先糊好的整张背景图」中属于自己那一块画出来，再叠一层着色。
 *
 * <p><b>为什么不用 BlurView。</b>BlurView 解决的是「糊我背后正在动的内容」，代价是每个实例
 * 都要一个 RenderNode 快照。按钮、状态行、输入框、列表行加起来一屏几十个，那样铺开不现实，
 * 于是之前只有容器有玻璃、容器里的组件全是干的 —— 这是观感上最刺眼的问题。
 *
 * <p>但绝大多数元素背后其实只有一样东西：那张静态背景图。既然它不动，就没必要每帧重算 ——
 * 糊好一份共用，每个元素按自己在窗口里的位置取对应区域即可。这就是 CSS backdrop-filter
 * 对静态背景的等价实现，而且是纯 drawable 绘制，GPU 开销与普通色块无异。
 *
 * <p>位置靠 {@link #setWindowOffset} 从外部喂（{@link DshaGlass} 在 preDraw 时统一刷新）——
 * Drawable 自己拿不到宿主 View 的窗口坐标，硬件加速下 {@code canvas.getMatrix()} 也不可靠。
 *
 * <p>顶栏底栏仍然用 BlurView：那两条要糊的是从背后滚过去的页面内容，不是静态图。
 */
final class GlassDrawable extends Drawable {

    private final Bitmap blurred;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Rect src = new Rect();

    private float corner;
    private int offsetX;
    private int offsetY;

    /**
     * @param blurred 全屏尺寸的、已经模糊过的背景图（各实例共用同一份，不要 recycle）
     * @param tint    叠在图上的着色（带 alpha，决定「厚度」）
     * @param stroke  描边色，0 表示不描
     * @param corner  圆角半径（px）
     */
    GlassDrawable(Bitmap blurred, int tint, int stroke, float corner, float strokeWidth) {
        this.blurred = blurred;
        this.corner = corner;
        tintPaint.setColor(tint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(stroke);
        strokePaint.setStrokeWidth(strokeWidth);
    }

    /** 宿主 View 在窗口里的左上角坐标。 */
    void setWindowOffset(int x, int y) {
        if (x == offsetX && y == offsetY) return;
        offsetX = x;
        offsetY = y;
        invalidateSelf();
    }

    void setCorner(float px) {
        if (px < 0 || px == corner) return;
        corner = px;
        invalidateSelf();
    }

    /** 改着色。刻意不叫 setTint —— 那是 Drawable 的公开方法，签名撞上会编译不过，
     *  而且语义不同（Drawable#setTint 是给整个 drawable 上色）。 */
    void setGlassTint(int argb) {
        tintPaint.setColor(argb);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rf.set(b);

        int save = canvas.save();
        // 圆角裁切。用 clipPath 而不是给 bitmap 套 shader + rounded rect，是因为要同时裁
        // 图和着色两层，clip 一次比两次各自做圆角省事，也不会在边缘出现半像素的错位。
        android.graphics.Path p = new android.graphics.Path();
        p.addRoundRect(rf, corner, corner, android.graphics.Path.Direction.CW);
        canvas.clipPath(p);

        if (blurred != null && !blurred.isRecycled()) {
            // 从整张模糊图里取自己这块。offset 是窗口坐标，图也是按窗口尺寸糊的，
            // 所以直接对应 —— 越界时 drawBitmap 会自动裁，不用自己判边界。
            src.set(offsetX, offsetY, offsetX + b.width(), offsetY + b.height());
            canvas.drawBitmap(blurred, src, rf, bmpPaint);
        }
        canvas.drawRect(rf, tintPaint);
        canvas.restoreToCount(save);

        if (strokePaint.getColor() != 0 && strokePaint.getStrokeWidth() > 0) {
            float half = strokePaint.getStrokeWidth() / 2f;
            rf.inset(half, half);
            canvas.drawRoundRect(rf, corner, corner, strokePaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        bmpPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        bmpPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
