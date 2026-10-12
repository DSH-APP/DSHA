package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import com.deepseekharness.app.util.PictureInPicturePolicy;

/** 小窗继续绘制同一份网页；保留逻辑宽度并按实际窗口比例重排，不重新加载网页。 */
final class PictureInPictureFrame extends FrameLayout {
  private boolean compact, frozen;
  private int viewportWidth, viewportHeight;
  private String pictureLayout = "auto";
  /*
   * A WebView must not be re-laid out every time the system changes the PiP
   * surface bounds.  Some Android 12/13 window managers send a burst of
   * layout callbacks while the surface is being handed to SystemUI.  The
   * old implementation recomputed a different logical height for every one
   * of those callbacks; Chromium then repainted the lower half of the page
   * repeatedly (the high-frequency PiP flicker reported on the tablet).
   * Keep one logical viewport for the whole PiP session and only scale the
   * already laid-out child into the current bounds.
   *
   * The cache below deduplicates geometry, not child work: when the webpage
   * updates, its native subtree calls requestLayout(), and the logical viewport
   * does not move.  Skipping the child in that case would swallow the request
   * forever (stale measure, no onLayout), which shows up as the content area
   * flashing blank while the frozen PiP window keeps its bounds.  So both
   * onMeasure and onLayout must still dispatch a pending subtree request, and
   * only the scale/translation writes stay value-deduplicated.
   */
  private int compactViewportWidth = -1, compactViewportHeight = -1;
  private int measuredChildWidth = -1, measuredChildHeight = -1;
  private int laidOutViewportWidth = -1, laidOutViewportHeight = -1;
  private int laidOutWidth = -1, laidOutHeight = -1;
  private float laidOutScale = Float.NaN,
      laidOutTranslationX = Float.NaN,
      laidOutTranslationY = Float.NaN;
  private boolean childTransformApplied;

  PictureInPictureFrame(Context context) {
    super(context);
  }

  void restoreViewport(int width, int height) {
    if (width > 0 && height > 0) {
      viewportWidth = width;
      viewportHeight = height;
      resetGeometryCache();
    }
  }

  void setPictureLayout(String value) {
    if (!pictureLayout.equals(value)) {
      pictureLayout = value;
      if (compact) captureCompactViewport();
      resetGeometryCache();
      requestLayout();
    }
  }

  void freezeViewport() {
    frozen = true;
  }

  boolean isFrozen() {
    return frozen;
  }

  void resumeViewport() {
    if (!compact) {
      frozen = false;
      requestLayout();
    }
  }

  void setCompact(boolean value) {
    if (compact == value) return;
    compact = value;
    if (value) captureCompactViewport();
    else {
      frozen = false;
      compactViewportWidth = compactViewportHeight = -1;
      clearChildTransform();
    }
    resetGeometryCache();
    requestLayout();
  }

  int viewportWidth() {
    return viewportWidth > 0 ? viewportWidth : getWidth();
  }

  int viewportHeight() {
    return viewportHeight > 0 ? viewportHeight : getHeight();
  }

  int[] pictureViewport() {
    return PictureInPicturePolicy.viewport(viewportWidth(), viewportHeight(), pictureLayout);
  }

  private int[] drawingViewport(int width, int height) {
    if (compact && compactViewportWidth > 0 && compactViewportHeight > 0)
      return new int[] {compactViewportWidth, compactViewportHeight};
    return PictureInPicturePolicy.fittedViewport(
        viewportWidth(), viewportHeight(), width, height, pictureLayout);
  }

  private void captureCompactViewport() {
    int width = viewportWidth();
    int height = viewportHeight();
    int[] preferred = PictureInPicturePolicy.viewport(width, height, pictureLayout);
    compactViewportWidth = preferred[0];
    compactViewportHeight = preferred[1];
  }

  private void resetGeometryCache() {
    measuredChildWidth = measuredChildHeight = -1;
    laidOutViewportWidth = laidOutViewportHeight = -1;
    laidOutWidth = laidOutHeight = -1;
    laidOutScale = laidOutTranslationX = laidOutTranslationY = Float.NaN;
    childTransformApplied = false;
  }

  /** 子页面自己发起的布局请求；几何缓存不能把它当成「没有变化」丢掉。 */
  private boolean childRequestedLayout() {
    for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).isLayoutRequested()) return true;
    return false;
  }

  private void clearChildTransform() {
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      child.setScaleX(1f);
      child.setScaleY(1f);
      child.setTranslationX(0f);
      child.setTranslationY(0f);
    }
    childTransformApplied = false;
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    if (!compact || viewportWidth <= 0 || viewportHeight <= 0) {
      super.onMeasure(widthSpec, heightSpec);
      if (!frozen && !compact) {
        androidx.core.view.WindowInsetsCompat insets =
            androidx.core.view.ViewCompat.getRootWindowInsets(this);
        if (insets == null || !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) {
          viewportWidth = getMeasuredWidth();
          viewportHeight = getMeasuredHeight();
        }
      }
      return;
    }
    int[] viewport =
        drawingViewport(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    if (PictureInPicturePolicy.mustDispatchChildLayout(
        viewport[0],
        viewport[1],
        measuredChildWidth,
        measuredChildHeight,
        childRequestedLayout())) {
      for (int i = 0; i < getChildCount(); i++)
        getChildAt(i)
            .measure(
                MeasureSpec.makeMeasureSpec(viewport[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(viewport[1], MeasureSpec.EXACTLY));
      measuredChildWidth = viewport[0];
      measuredChildHeight = viewport[1];
    }
    setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (!compact) {
      if (childTransformApplied) clearChildTransform();
      super.onLayout(changed, left, top, right, bottom);
      return;
    }
    int[] viewport = drawingViewport(getWidth(), getHeight());
    float scale = PictureInPicturePolicy.scale(getWidth(), getHeight(), viewport[0], viewport[1]);
    float tx = (getWidth() - viewport[0] * scale) / 2f;
    float ty = (getHeight() - viewport[1] * scale) / 2f;
    // 逻辑视口冻结时窗口变化只需重写变换；只有子页面真的要求重排（或视口本身变了）才动子树。
    boolean layoutChild =
        PictureInPicturePolicy.mustDispatchChildLayout(
            viewport[0],
            viewport[1],
            laidOutViewportWidth,
            laidOutViewportHeight,
            childRequestedLayout());
    boolean transformChanged =
        !childTransformApplied
            || getWidth() != laidOutWidth
            || getHeight() != laidOutHeight
            || Float.compare(scale, laidOutScale) != 0
            || Float.compare(tx, laidOutTranslationX) != 0
            || Float.compare(ty, laidOutTranslationY) != 0;
    if (!layoutChild && !transformChanged) return;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (layoutChild) child.layout(0, 0, viewport[0], viewport[1]);
      if (transformChanged) {
        child.setPivotX(0);
        child.setPivotY(0);
        child.setScaleX(scale);
        child.setScaleY(scale);
        child.setTranslationX(tx);
        child.setTranslationY(ty);
      }
    }
    if (layoutChild) {
      laidOutViewportWidth = viewport[0];
      laidOutViewportHeight = viewport[1];
    }
    if (transformChanged) {
      laidOutWidth = getWidth();
      laidOutHeight = getHeight();
      laidOutScale = scale;
      laidOutTranslationX = tx;
      laidOutTranslationY = ty;
      childTransformApplied = true;
    }
  }
}
