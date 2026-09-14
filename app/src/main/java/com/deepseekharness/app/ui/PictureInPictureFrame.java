package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import com.deepseekharness.app.util.PictureInPicturePolicy;

/** 小窗继续绘制同一份网页；保持进入前的网页视口，避免窄窗重排成一列大字。 */
final class PictureInPictureFrame extends FrameLayout {
    private boolean compact, frozen;
    private int viewportWidth, viewportHeight;
    private String pictureLayout = "auto";

    PictureInPictureFrame(Context context) { super(context); }
    void restoreViewport(int width, int height) {
        if (width > 0 && height > 0) { viewportWidth = width; viewportHeight = height; }
    }
    void setPictureLayout(String value) {
        if (!pictureLayout.equals(value)) { pictureLayout = value; requestLayout(); }
    }
    void freezeViewport() { frozen = true; }
    void resumeViewport() { if (!compact) { frozen = false; requestLayout(); } }
    void setCompact(boolean value) {
        compact = value;
        if (!value) frozen = false;
        requestLayout();
    }
    int viewportWidth() { return viewportWidth > 0 ? viewportWidth : getWidth(); }
    int viewportHeight() { return viewportHeight > 0 ? viewportHeight : getHeight(); }
    int[] pictureViewport() { return PictureInPicturePolicy.viewport(viewportWidth(), viewportHeight(), pictureLayout); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (!compact || viewportWidth <= 0 || viewportHeight <= 0) {
            super.onMeasure(widthSpec, heightSpec);
            if (!frozen && !compact) {
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(this);
                if (insets == null || !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) {
                    viewportWidth = getMeasuredWidth(); viewportHeight = getMeasuredHeight();
                }
            }
            return;
        }
        int[] viewport = pictureViewport();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(viewport[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(viewport[1], MeasureSpec.EXACTLY));
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!compact) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.setScaleX(1); child.setScaleY(1); child.setTranslationX(0); child.setTranslationY(0);
            }
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        int[] viewport = pictureViewport();
        float scale = PictureInPicturePolicy.scale(getWidth(), getHeight(), viewport[0], viewport[1]);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, viewport[0], viewport[1]);
            child.setPivotX(0); child.setPivotY(0); child.setScaleX(scale); child.setScaleY(scale);
            child.setTranslationX((getWidth() - viewport[0] * scale) / 2);
            child.setTranslationY((getHeight() - viewport[1] * scale) / 2);
        }
    }
}
