package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 真实 ContentInsets 回调：浏览器与聊天一起缩放，任何帧都不等待 JS 确认。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {30, 34},
    application = Application.class)
public final class WebFullscreenImeResizeTest {
  public static class Page extends Activity implements WebFullscreenUi.Host {
    int packets;

    @Override
    public boolean nativeImeResize() {
      return true;
    }

    @Override
    public Object imeBridgeIdentity() {
      return this;
    }

    @Override
    public boolean sendImeFrame(Object identity, org.json.JSONObject frame) {
      packets++;
      return true; // 故意永不 ack：尺寸不能依赖此通道。
    }
  }

  private static WindowInsetsCompat insets(int keyboard) {
    return new WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 16))
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
        .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
        .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(7, 0, 9, 0))
        .build();
  }

  /** 模拟 Chromium 保留最近一次 insets；只有真正收到派发才退休旧的 IME 避让。 */
  private static final class CachedBrowser extends View {
    int keyboard = 320, bars = 24, gestures, deliveries;

    CachedBrowser(Activity activity) {
      super(activity);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets value) {
      var compat = WindowInsetsCompat.toWindowInsetsCompat(value, this);
      keyboard = compat.getInsets(WindowInsetsCompat.Type.ime()).bottom;
      bars = compat.getInsets(WindowInsetsCompat.Type.systemBars()).top;
      gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures()).left;
      deliveries++;
      return value;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final org.robolectric.android.controller.ActivityController<Page> controller =
        Robolectric.buildActivity(Page.class).setup();
    final Page page = controller.get();
    final FrameLayout content =
        new FrameLayout(page) {
          @Override
          public boolean hasWindowFocus() {
            return true;
          }
        };
    final CachedBrowser browser = new CachedBrowser(page);
    final PictureInPictureFrame picture = new PictureInPictureFrame(page);
    final WebFullscreenUi.ContentInsets owner;

    Fixture() {
      picture.addView(browser, new FrameLayout.LayoutParams(-1, -1));
      content.addView(picture, new FrameLayout.LayoutParams(-1, -1));
      owner = new WebFullscreenUi.ContentInsets(page, content);
      owner.onViewAttachedToWindow(content);
      owner.onApplyWindowInsets(content, insets(0));
    }

    void bounds(int keyboard) {
      int expected = Math.max(16, keyboard);
      assertEquals(expected, content.getPaddingBottom());
      content.measure(
          View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
      content.layout(0, 0, 360, 800);
      assertEquals(800 - 24 - expected, browser.getHeight());
      assertEquals(0, page.packets);
    }

    @Override
    public void close() {
      owner.onViewDetachedFromWindow(content);
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void showAndHideResizeTheWholeBrowserOnEveryActualFrameWithoutIpc() {
    try (Fixture f = new Fixture()) {
      f.bounds(0);
      var show = new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 250);
      f.owner.onPrepare(show);
      f.owner.onApplyWindowInsets(f.content, insets(320));
      f.bounds(0); // 提前到达的终态不能覆盖动画起点。
      for (int h : new int[] {0, 64, 128, 240, 320}) {
        f.owner.onProgress(insets(h), List.of(show));
        f.bounds(h);
      }
      f.owner.onEnd(show);
      f.bounds(320);
      var hide = new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 250);
      f.owner.onPrepare(hide);
      f.owner.onApplyWindowInsets(f.content, insets(0));
      f.bounds(320);
      for (int h : new int[] {320, 240, 128, 64, 0}) {
        f.owner.onProgress(insets(h), List.of(hide));
        f.bounds(h);
      }
      f.owner.onEnd(hide);
      f.bounds(0);
    }
  }

  @Test
  public void endCommitsFinalInsetsEvenIfTheLastProgressFrameWasNotDelivered() {
    try (Fixture f = new Fixture()) {
      f.owner.onApplyWindowInsets(f.content, insets(320));
      var hide = new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 250);
      f.owner.onPrepare(hide);
      f.owner.onApplyWindowInsets(f.content, insets(0));
      f.owner.onProgress(insets(80), List.of(hide));
      f.bounds(80);
      f.owner.onEnd(hide);
      f.bounds(0);
      f.owner.onApplyWindowInsets(f.content, insets(0));
      f.bounds(0);
    }
  }

  @Test
  public void focusLossRetiresKeyboardPaddingWithoutAnAcknowledgement() {
    try (Fixture f = new Fixture()) {
      f.owner.onApplyWindowInsets(f.content, insets(320));
      f.bounds(320);
      f.owner.onWindowFocusChanged(false);
      f.bounds(0);
    }
  }

  @Test
  public void childReceivesExplicitZeroAndRetiresCachedKeyboardInsetsAcrossNestedContainers() {
    try (Fixture f = new Fixture()) {
      for (int h : new int[] {320, 0, 320, 0}) {
        WindowInsetsCompat result = ViewCompat.dispatchApplyWindowInsets(f.content, insets(h));
        assertEquals("不能吞掉通知并让浏览器保留旧键盘高度", 0, f.browser.keyboard);
        assertEquals(0, f.browser.bars);
        assertEquals(7, f.browser.gestures);
        assertFalse(result.isConsumed());
        f.bounds(h);
      }
      assertEquals(4, f.browser.deliveries);
    }
  }

  @Test
  public void animationSubtreeReceivesZeroBoundsAndInsetsWhileParentFollowsRealKeyboard() {
    try (Fixture f = new Fixture()) {
      assertEquals(
          WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
          f.owner.getDispatchMode());
      var show = new WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 250);
      f.owner.onPrepare(show);
      var original =
          new WindowInsetsAnimationCompat.BoundsCompat(Insets.NONE, Insets.of(0, 0, 0, 320));
      var bounds = f.owner.onStart(show, original);
      assertEquals(Insets.NONE, bounds.getLowerBound());
      assertEquals(Insets.NONE, bounds.getUpperBound());
      f.owner.onApplyWindowInsets(f.content, insets(320));
      for (int h : new int[] {0, 64, 160, 320}) {
        var child = f.owner.onProgress(insets(h), List.of(show));
        assertFalse(child.isConsumed());
        assertEquals(Insets.NONE, child.getInsets(WindowInsetsCompat.Type.ime()));
        assertFalse(child.isVisible(WindowInsetsCompat.Type.ime()));
        assertEquals(7, child.getInsets(WindowInsetsCompat.Type.systemGestures()).left);
        f.bounds(h);
      }
      f.owner.onEnd(show);
      f.bounds(320);
    }
  }
}
