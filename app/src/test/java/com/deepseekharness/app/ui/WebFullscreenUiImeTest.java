package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/** 受控焦点下的真实 AndroidX 监听与平台派发，不代表真机输入法时序。 */
@RunWith(WebFullscreenUiImeTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebFullscreenUiImeTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class Host extends Activity implements WebFullscreenUi.Host {
    final Object identity = new Object();
    final List<org.json.JSONObject> frames = new ArrayList<>();
    boolean autoAck = true, supported = true;

    @Override
    public Object imeBridgeIdentity() {
      return identity;
    }

    @Override
    public boolean sendImeFrame(Object expected, org.json.JSONObject frame) {
      if (expected != identity) return false;
      frames.add(frame);
      if (autoAck && !"cancel".equals(frame.optString("phase")))
        WebFullscreenUi.acknowledgeIme(this, identity, frame.optLong("frameId"), supported);
      return true;
    }

    boolean picture;

    @Override
    public boolean isInPictureInPictureMode() {
      return picture;
    }
  }

  private static final class Content extends FrameLayout {
    boolean focused = true;
    final List<Integer> bottoms = new ArrayList<>();

    Content(Context context) {
      super(context);
    }

    @Override
    public boolean hasWindowFocus() {
      return focused;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
      super.setPadding(left, top, right, bottom);
      if (bottoms != null) bottoms.add(bottom);
    }
  }

  private ActivityController<Host> host;
  private Content content;
  private WebFullscreenUi.ContentInsets callback;

  @Before
  public void setUp() {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    content = new Content(host.get());
    host.get().setContentView(content);
    content.layout(0, 0, 400, 800);
    callback = new WebFullscreenUi.ContentInsets(host.get(), content);
  }

  @After
  public void tearDown() {
    host.pause().stop().destroy();
  }

  private WindowInsetsCompat insets(int keyboard) {
    return new WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 14))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(3, 28, 5, 0))
        .setInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.displayCutout(), Insets.of(3, 28, 5, 0))
        .setInsets(WindowInsetsCompat.Type.captionBar(), Insets.NONE)
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 32, 0, 0))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
        .build();
  }

  private void apply(int keyboard) {
    assertTrue(ViewCompat.dispatchApplyWindowInsets(content, insets(keyboard)).isConsumed());
  }

  private WindowInsetsAnimationCompat prepare() {
    WindowInsetsAnimationCompat animation =
        new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 250);
    callback.onPrepare(animation);
    return animation;
  }

  private void progress(WindowInsetsAnimationCompat animation, int keyboard) {
    assertTrue(callback.onProgress(insets(keyboard), List.of(animation)).isConsumed());
  }

  private void focus(boolean focused) {
    content.focused = focused;
    ReflectionHelpers.callInstanceMethod(
        content.getViewTreeObserver(),
        "dispatchOnWindowFocusChange",
        ReflectionHelpers.ClassParameter.from(boolean.class, focused));
  }

  @Test
  public void showEndInsetsBeforeAnimationCannotJumpToEnd() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    assertEquals(14, content.getPaddingBottom());
    for (int frame : new int[] {0, 60, 130, 220, 300}) {
      progress(animation, frame);
      assertEquals(14, content.getPaddingBottom());
      assertEquals(
          800 - 32 - Math.max(14, frame),
          host.get().frames.get(host.get().frames.size() - 1).optInt("height"));
    }
    callback.onEnd(animation);
    apply(300);
    assertEquals(300, content.getPaddingBottom());
  }

  @Test
  public void hideEndInsetsCannotShrinkBeforeTheFirstProgressFrame() {
    apply(300);
    WindowInsetsAnimationCompat animation = prepare();
    apply(0);
    assertEquals(14, content.getPaddingBottom());
    for (int frame : new int[] {300, 240, 150, 50, 0}) {
      progress(animation, frame);
      assertEquals(14, content.getPaddingBottom());
      assertEquals(
          800 - 32 - Math.max(14, frame),
          host.get().frames.get(host.get().frames.size() - 1).optInt("height"));
    }
  }

  @Test
  public void actualPlatformPrepareApplyProgressDispatchUsesOneOwnerAndStopsChildInsets() {
    int[] childInsets = {0}, childAnimation = {0};
    View child = new View(host.get());
    content.addView(child);
    ViewCompat.setOnApplyWindowInsetsListener(
        child,
        (view, incoming) -> {
          childInsets[0]++;
          return incoming;
        });
    ViewCompat.setWindowInsetsAnimationCallback(
        child,
        new WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
          @Override
          public WindowInsetsCompat onProgress(
              WindowInsetsCompat incoming, List<WindowInsetsAnimationCompat> running) {
            childAnimation[0]++;
            return incoming;
          }
        });
    apply(0);
    WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), null, 250);
    content.dispatchWindowInsetsAnimationPrepare(animation);
    apply(300);
    assertEquals(14, content.getPaddingBottom());
    content.dispatchWindowInsetsAnimationProgress(insets(75).toWindowInsets(), List.of(animation));
    assertEquals(14, content.getPaddingBottom());
    assertEquals(693, host.get().frames.get(host.get().frames.size() - 1).optInt("height"));
    assertEquals(0, childInsets[0]);
    assertEquals(0, childAnimation[0]);
    content.dispatchWindowInsetsAnimationEnd(animation);
  }

  @Test
  public void staticOrDisabledAnimationsStillApplyTheAuthoritativeHeight() {
    apply(180);
    assertEquals(180, content.getPaddingBottom());
    apply(0);
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void stableStatusCutoutCaptionAndSideInsetsSurviveEveryImeFrame() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    progress(animation, 100);
    assertEquals(3, content.getPaddingLeft());
    assertEquals(32, content.getPaddingTop());
    assertEquals(5, content.getPaddingRight());
    focus(false);
    assertEquals(32, content.getPaddingTop());
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void otherWindowsImeCannotResizeAnUnfocusedContainer() {
    focus(false);
    apply(300);
    WindowInsetsAnimationCompat animation = prepare();
    progress(animation, 250);
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void focusLossClearsOwnedAnimationAndLateFramesCannotReviveIt() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    progress(animation, 100);
    focus(false);
    assertEquals(14, content.getPaddingBottom());
    focus(true);
    progress(animation, 250);
    callback.onEnd(animation);
    assertEquals(14, content.getPaddingBottom());
    apply(160);
    assertEquals(160, content.getPaddingBottom());
  }

  @Test
  public void canceledAnimationDoesNotSnapToItsStoredFinalTarget() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    progress(animation, 95);
    callback.onEnd(animation);
    assertEquals(95, content.getPaddingBottom());
    apply(0);
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void overlappingImeAnimationsDoNotReleaseTheNewerAnimationEarly() {
    apply(0);
    WindowInsetsAnimationCompat first = prepare();
    apply(300);
    progress(first, 120);
    WindowInsetsAnimationCompat second = prepare();
    callback.onEnd(first);
    apply(0);
    assertEquals(14, content.getPaddingBottom());
    progress(second, 80);
    assertEquals(14, content.getPaddingBottom());
    callback.onEnd(second);
    apply(0);
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void unrelatedAnimationsDoNotClaimOrFreezeImeInsets() {
    apply(0);
    WindowInsetsAnimationCompat bars =
        new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.statusBars(), null, 200);
    callback.onPrepare(bars);
    apply(120);
    assertEquals(120, content.getPaddingBottom());
    callback.onProgress(insets(300), List.of(bars));
    assertEquals(120, content.getPaddingBottom());
    callback.onEnd(bars);
  }

  @Test
  public void pictureInPictureUsesZeroInsetsAndDiscardsPendingIme() {
    apply(300);
    WindowInsetsAnimationCompat animation = prepare();
    host.get().picture = true;
    apply(300);
    progress(animation, 260);
    assertEquals(0, content.getPaddingLeft());
    assertEquals(0, content.getPaddingTop());
    assertEquals(0, content.getPaddingRight());
    assertEquals(0, content.getPaddingBottom());
    host.get().picture = false;
    callback.onEnd(animation);
    apply(0);
    assertEquals(32, content.getPaddingTop());
    assertEquals(14, content.getPaddingBottom());
  }

  @Test
  public void detachClearsAnimationAndReattachUsesFreshInsets() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    progress(animation, 100);
    ViewGroup parent = (ViewGroup) content.getParent();
    parent.removeView(content);
    assertEquals(14, content.getPaddingBottom());
    progress(animation, 280);
    assertEquals(14, content.getPaddingBottom());
    parent.addView(content);
    progress(animation, 290);
    assertEquals(14, content.getPaddingBottom());
    apply(200);
    assertEquals(200, content.getPaddingBottom());
  }

  @Test
  public void equalFramesDoNotRepeatContainerRelayout() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    progress(animation, 100);
    int before = content.bottoms.size();
    progress(animation, 100);
    assertEquals(before, content.bottoms.size());
  }

  @Test
  @Config(sdk = 29)
  public void api29SameViewCompatAdapterAnimatesDespiteConsumingChildInsets() {
    // 先完成真实窗口的首次布局；避免手工 400px 夹具在动画中被系统改为实际窗口宽度，
    // 被生产旋转撤销门正确识别成另一轮几何。
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    ReflectionHelpers.callStaticMethod(
        android.animation.ValueAnimator.class,
        "setDurationScale",
        ReflectionHelpers.ClassParameter.from(float.class, 1f));
    WindowInsets before =
        new WindowInsets.Builder()
            .setSystemWindowInsets(android.graphics.Insets.of(0, 24, 0, 0))
            .build();
    WindowInsets after =
        new WindowInsets.Builder()
            .setSystemWindowInsets(android.graphics.Insets.of(0, 24, 0, 300))
            .build();
    content.dispatchApplyWindowInsets(before);
    content.bottoms.clear();
    assertTrue(content.dispatchApplyWindowInsets(after).isConsumed());
    assertEquals(0, content.getPaddingBottom());
    content.getViewTreeObserver().dispatchOnPreDraw();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    assertFalse(
        content.bottoms.toString(),
        content.bottoms.stream().anyMatch(value -> value > 0 && value < 300));
    assertTrue(
        host.get().frames.toString(),
        host.get().frames.stream()
                .filter(value -> "frame".equals(value.optString("phase")))
                .map(value -> value.optInt("height"))
                .distinct()
                .count()
            > 2);
    assertEquals(300, content.getPaddingBottom());
  }

  @Test
  @Config(sdk = 23)
  public void api23StaticFallbackKeepsSafeNavigationAndKeyboardBounds() {
    host.get().supported = false;
    WindowInsetsCompat value =
        new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 220))
            .build();
    callback.onApplyWindowInsets(content, value);
    assertEquals(24, content.getPaddingTop());
    assertEquals(220, content.getPaddingBottom());
    focus(false);
    assertEquals(48, content.getPaddingBottom());
  }

  @Test
  public void busyRendererKeepsOneInFlightAndOnlyTheLatestEndFrame() {
    host.get().autoAck = false;
    apply(0);
    long first = host.get().frames.get(0).optLong("frameId");
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    for (int height = 20; height <= 300; height += 20) progress(animation, height);
    callback.onEnd(animation);
    assertEquals(1, host.get().frames.size());
    WebFullscreenUi.acknowledgeIme(host.get(), host.get().identity, first, true);
    assertEquals(2, host.get().frames.size());
    var last = host.get().frames.get(1);
    assertEquals("end", last.optString("phase"));
    assertEquals(468, last.optInt("height"));
    WebFullscreenUi.acknowledgeIme(host.get(), host.get().identity, first, true);
    assertEquals(2, host.get().frames.size());
    focus(false);
    assertEquals("cancel", host.get().frames.get(2).optString("phase"));
    WebFullscreenUi.acknowledgeIme(host.get(), host.get().identity, last.optLong("frameId"), true);
    assertEquals(3, host.get().frames.size());
  }

  @Test
  public void manyActualFramesDoNotChangeNativeSizeOrTransformTheWholePage() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    int count = content.bottoms.size();
    for (int frame = 1; frame <= 300; frame++) progress(animation, frame);
    assertEquals(count, content.bottoms.size());
    assertEquals(0f, content.getTranslationY(), 0f);
    assertEquals(1f, content.getScaleY(), 0f);
    callback.onEnd(animation);
    assertEquals(count + 1, content.bottoms.size());
  }

  @Test
  public void widthChangeRevokesOldAnimationBeforeUsingTheNewGeometry() {
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    progress(animation, 100);
    content.layout(0, 0, 500, 800);
    progress(animation, 200);
    assertEquals("cancel", host.get().frames.get(host.get().frames.size() - 1).optString("phase"));
    assertEquals(14, content.getPaddingBottom());
    apply(180);
    assertEquals("end", host.get().frames.get(host.get().frames.size() - 1).optString("phase"));
    assertEquals(492, host.get().frames.get(host.get().frames.size() - 1).optInt("width"));
    assertEquals(180, content.getPaddingBottom());
  }

  @Test
  public void missingComposerUsesOnlyStableBoundaryNativeResize() {
    host.get().supported = false;
    apply(0);
    WindowInsetsAnimationCompat animation = prepare();
    apply(300);
    int count = content.bottoms.size();
    for (int frame = 1; frame <= 300; frame++) progress(animation, frame);
    assertEquals(count, content.bottoms.size());
    callback.onEnd(animation);
    assertEquals(300, content.getPaddingBottom());
  }
}
