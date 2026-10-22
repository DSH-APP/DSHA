package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Real View measure/layout flags and the production container; no WebView compositor claim. */
@RunWith(PictureInPictureFrameLayoutTest.NoApkRunner.class)
@Config(
    sdk = {29, 34},
    manifest = Config.NONE,
    application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PictureInPictureFrameLayoutTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private static final class Page extends View {
    int measures, layouts, transforms;

    Page(Context context) {
      super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      measures++;
      setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      layouts++;
    }

    @Override
    public void setPivotX(float value) {
      transforms++;
      super.setPivotX(value);
    }

    @Override
    public void setPivotY(float value) {
      transforms++;
      super.setPivotY(value);
    }

    @Override
    public void setScaleX(float value) {
      transforms++;
      super.setScaleX(value);
    }

    @Override
    public void setScaleY(float value) {
      transforms++;
      super.setScaleY(value);
    }

    @Override
    public void setTranslationX(float value) {
      transforms++;
      super.setTranslationX(value);
    }

    @Override
    public void setTranslationY(float value) {
      transforms++;
      super.setTranslationY(value);
    }
  }

  private static final class Subtree extends FrameLayout {
    int measures, layouts;

    Subtree(Context context) {
      super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      measures++;
      super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      layouts++;
      super.onLayout(changed, left, top, right, bottom);
    }
  }

  private static int exact(int size) {
    return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
  }

  private static PictureInPictureFrame compact(View page) {
    var frame = new PictureInPictureFrame(RuntimeEnvironment.getApplication());
    frame.restoreViewport(1080, 1920);
    frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
    frame.setCompact(true);
    traverse(frame, 432, 905);
    return frame;
  }

  private static void traverse(PictureInPictureFrame frame, int width, int height) {
    // Force only the container's real View traversal, not any child's measure/layout flags.
    frame.forceLayout();
    frame.measure(exact(width), exact(height));
    frame.layout(0, 0, width, height);
  }

  @Test
  public void sameSizeRequestCompletesRealMeasureAndLayoutWithoutNewTransforms() {
    var page = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(page);
    assertEquals(1, page.measures);
    assertEquals(1, page.layouts);
    assertFalse(page.isLayoutRequested());
    int transforms = page.transforms;

    page.requestLayout();
    assertTrue(page.isLayoutRequested());
    assertTrue(frame.isLayoutRequested());
    frame.measure(exact(432), exact(905));
    assertEquals("same-size subtree request must reach real onMeasure", 2, page.measures);
    // Android may retain FORCE_LAYOUT until layout; do not clear or shadow that flag here.
    System.out.println("PiP same-size after measure: childRequested=" + page.isLayoutRequested());
    frame.layout(0, 0, 432, 905);
    assertEquals("unchanged child bounds must still complete onLayout", 2, page.layouts);
    assertFalse("the child's actual request must be satisfied", page.isLayoutRequested());
    assertEquals("child work must not rewrite unchanged transforms", transforms, page.transforms);
    assertEquals(1080, page.getMeasuredWidth());
    assertEquals(1920, page.getMeasuredHeight());
  }

  @Test
  public void idleContainerTraversalsDoNotRemeasureRelayoutOrRewriteTransforms() {
    var page = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(page);
    int transforms = page.transforms;
    for (int round = 0; round < 5; round++) traverse(frame, 432, 905);
    assertEquals(1, page.measures);
    assertEquals(1, page.layouts);
    assertEquals(transforms, page.transforms);
    assertFalse(page.isLayoutRequested());
  }

  @Test
  public void windowResizeOnlyChangesTransformsAndKeepsTheFrozenLogicalViewport() {
    var page = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(page);
    int transforms = page.transforms;
    for (int[] window : new int[][] {{500, 900}, {270, 360}, {600, 360}}) {
      traverse(frame, window[0], window[1]);
      float scale = Math.min(window[0] / 1080f, window[1] / 1920f);
      assertEquals(scale, page.getScaleX(), 0.00001f);
      assertEquals(scale, page.getScaleY(), 0.00001f);
      assertEquals((window[0] - 1080 * scale) / 2f, page.getTranslationX(), 0.00001f);
      assertEquals((window[1] - 1920 * scale) / 2f, page.getTranslationY(), 0.00001f);
    }
    assertEquals(1, page.measures);
    assertEquals(1, page.layouts);
    assertEquals(transforms + 18, page.transforms);
    assertEquals(1080, page.getWidth());
    assertEquals(1920, page.getHeight());
  }

  @Test
  public void explicitLogicalOrientationChangeDispatchesOnceAndRetainsThePageInstance() {
    var page = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(page);
    frame.setPictureLayout("landscape");
    traverse(frame, 432, 905);
    assertSame(page, frame.getChildAt(0));
    assertEquals(2, page.measures);
    assertEquals(2, page.layouts);
    assertEquals(1920, page.getWidth());
    assertEquals(1080, page.getHeight());
    int transforms = page.transforms;
    traverse(frame, 432, 905);
    assertEquals(2, page.measures);
    assertEquals(2, page.layouts);
    assertEquals(transforms, page.transforms);
  }

  @Test
  public void nestedDescendantRequestPropagatesThroughTheRealDirectChild() {
    var subtree = new Subtree(RuntimeEnvironment.getApplication());
    var leaf = new Page(RuntimeEnvironment.getApplication());
    subtree.addView(leaf, new FrameLayout.LayoutParams(-1, -1));
    var frame = compact(subtree);
    leaf.requestLayout();
    assertTrue(leaf.isLayoutRequested());
    assertTrue(subtree.isLayoutRequested());
    traverse(frame, 432, 905);
    assertEquals(2, subtree.measures);
    assertEquals(2, subtree.layouts);
    assertEquals(2, leaf.measures);
    assertEquals(2, leaf.layouts);
    assertFalse(leaf.isLayoutRequested());
    assertFalse(subtree.isLayoutRequested());
  }

  @Test
  public void newChildInAnAlreadyCachedCompactViewportIsMeasuredAndLaidOut() {
    var first = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(first);
    var second = new Page(RuntimeEnvironment.getApplication());
    frame.addView(second, new FrameLayout.LayoutParams(-1, -1));
    traverse(frame, 432, 905);
    assertEquals(1, second.measures);
    assertEquals(1, second.layouts);
    assertEquals(1080, second.getWidth());
    assertEquals(1920, second.getHeight());
    assertFalse(second.isLayoutRequested());
    assertEquals(1, first.measures);
    assertEquals(1, first.layouts);
  }

  @Test
  public void returningFullscreenClearsTransformsAndResumesNormalViewportMeasurement() {
    var page = new Page(RuntimeEnvironment.getApplication());
    var frame = compact(page);
    assertTrue(page.getScaleX() < 1f);
    frame.setCompact(false);
    traverse(frame, 720, 1280);
    assertSame(page, frame.getChildAt(0));
    assertEquals(1f, page.getScaleX(), 0f);
    assertEquals(1f, page.getScaleY(), 0f);
    assertEquals(0f, page.getTranslationX(), 0f);
    assertEquals(0f, page.getTranslationY(), 0f);
    assertEquals(720, page.getWidth());
    assertEquals(1280, page.getHeight());
    assertEquals(720, frame.viewportWidth());
    assertEquals(1280, frame.viewportHeight());
    assertFalse(frame.isFrozen());
  }
}
