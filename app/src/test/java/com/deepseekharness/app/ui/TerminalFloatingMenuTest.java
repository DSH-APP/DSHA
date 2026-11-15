package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import com.deepseekharness.app.R;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TerminalNavigationTest.App.class)
public class TerminalFloatingMenuTest {
  private record Fixture(FrameLayout root, TerminalFloatingMenu ball, AtomicInteger clicks) {}

  private Fixture fixture() {
    android.view.ContextThemeWrapper context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    FrameLayout root = new FrameLayout(context);
    TerminalFloatingMenu ball = new TerminalFloatingMenu(context);
    AtomicInteger clicks = new AtomicInteger();
    ball.setOnClickListener(v -> clicks.incrementAndGet());
    root.addView(ball);
    root.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 640);
    ball.reserveBottom(100);
    return new Fixture(root, ball, clicks);
  }

  private void touch(TerminalFloatingMenu ball, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(0, 10, action, x, y, 0);
    try {
      ball.onTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  @Test
  public void compactAppearanceKeepsTheWholeTouchTargetClickable() {
    var f = fixture();
    float density = f.ball.getResources().getDisplayMetrics().density;
    assertEquals(Math.round(48 * density), f.ball.getWidth());
    assertEquals(Math.round(48 * density), f.ball.getHeight());
    assertTrue(f.ball.getBackground() instanceof android.graphics.drawable.InsetDrawable);
    f.ball.getBackground().setBounds(0, 0, f.ball.getWidth(), f.ball.getHeight());
    var visible = ((android.graphics.drawable.InsetDrawable) f.ball.getBackground()).getDrawable();
    assertEquals(Math.round(40 * density), visible.getBounds().width());
    assertEquals(Math.round(40 * density), visible.getBounds().height());
    touch(f.ball, MotionEvent.ACTION_DOWN, 1, 1);
    touch(f.ball, MotionEvent.ACTION_UP, 1, 1);
    assertEquals(1, f.clicks.get());
  }

  @Test
  public void tapOpensOnceAndDragDoesNotClick() {
    var f = fixture();
    touch(f.ball, MotionEvent.ACTION_DOWN, 20, 20);
    touch(f.ball, MotionEvent.ACTION_UP, 20, 20);
    assertEquals(1, f.clicks.get());
    touch(f.ball, MotionEvent.ACTION_DOWN, 20, 20);
    touch(f.ball, MotionEvent.ACTION_MOVE, -280, -120);
    touch(f.ball, MotionEvent.ACTION_UP, -280, -120);
    assertEquals(1, f.clicks.get());
    assertFalse(f.ball.dockRight());
    assertTrue(f.ball.getX() >= 0);
  }

  @Test
  public void cancelledDragDoesNotClickOrLeaveTheWindow() {
    var f = fixture();
    touch(f.ball, MotionEvent.ACTION_DOWN, 20, 20);
    touch(f.ball, MotionEvent.ACTION_MOVE, -500, 1000);
    touch(f.ball, MotionEvent.ACTION_CANCEL, -500, 1000);
    assertEquals(0, f.clicks.get());
    assertTrue(f.ball.getX() >= 0);
    assertTrue(f.ball.getY() + f.ball.getHeight() <= f.root.getHeight());
  }

  @Test
  public void keyboardAndAccessibilityCanRepositionWithoutTouch() {
    var f = fixture();
    f.ball.onKeyDown(
        KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
    assertFalse(f.ball.dockRight());
    assertEquals(0, f.clicks.get());
    assertTrue(f.ball.performAccessibilityAction(R.id.terminal_reset_ball, null));
    assertTrue(f.ball.dockRight());
    assertEquals(.6f, f.ball.dockFraction(), 0f);
  }

  @Test
  public void resizedWindowAndImeKeepWholeBallReachable() {
    var f = fixture();
    f.ball.restore(false, 1f);
    f.root.setPadding(20, 24, 20, 0);
    f.root.layout(0, 0, 320, 160);
    f.ball.reserveBottom(100);
    assertTrue(f.ball.getX() >= 20);
    assertTrue(f.ball.getY() >= 24);
    assertTrue(f.ball.getX() + f.ball.getWidth() <= 300);
    assertTrue(f.ball.getY() + f.ball.getHeight() <= 160);
  }
}
