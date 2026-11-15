package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import java.time.Duration;

/**
 * 底部抽屉：贴底整宽、返回键先收抽屉、遮罩只关闭不执行动作、手柄点击 / 下拖关闭、动作按钮才是执行入口。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = BottomSheetCardTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BottomSheetCardTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class FixtureHost extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
    }
  }

  private ActivityController<FixtureHost> controller;
  private FixtureHost activity;

  @Before
  public void setUp() {
    Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().getContentResolver(),
        Settings.Global.ANIMATOR_DURATION_SCALE,
        0f);
    Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().getContentResolver(),
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        0f);
    controller = Robolectric.buildActivity(FixtureHost.class).setup();
    activity = controller.get();
  }

  @After
  public void tearDown() {
    controller.pause().stop().destroy();
  }

  private CardPage page() {
    return new CardPage(activity, "备份详情", "08-08 10:00", true);
  }

  @Test
  public void drawerIsBottomFullWidthWithFocusableHandle() {
    CardPage page = page();
    page.kv(page.card(), "大小", "12.3 MB");
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    try {
      assertTrue(sheet.isShowing());
      AlertDialog dialog = sheet.dialog();
      assertEquals(
          Gravity.BOTTOM,
          dialog.getWindow().getAttributes().gravity & Gravity.VERTICAL_GRAVITY_MASK);
      assertEquals(-1, dialog.getWindow().getAttributes().width);
      assertNotNull(sheet.handle());
      assertTrue(sheet.handle().isFocusable());
      assertNotNull(sheet.handle().getContentDescription());
      assertSame(page, sheet.page());
    } finally {
      sheet.dismiss();
    }
  }

  @Test
  public void backClosesTheDrawerFirstWithoutRunningAnyAction() {
    CardPage page = page();
    int[] runs = {0};
    page.button(page.footer, "导出", true, () -> runs[0]++);
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    assertTrue(sheet.handleBack());
    assertFalse(sheet.isShowing());
    assertEquals("返回只收起抽屉，不执行动作", 0, runs[0]);
    assertFalse(activity.isFinishing());
  }

  @Test
  public void scrimTapOnlyClosesAndNeverExecutesTheButtonAction() {
    CardPage page = page();
    int[] runs = {0};
    Button export = page.button(page.footer, "导出", true, () -> runs[0]++);
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page);
    sheet.dialog().cancel();
    assertFalse(sheet.isShowing());
    assertEquals(0, runs[0]);
    assertFalse(export.isPressed());
  }

  @Test
  public void tappingTheHandleClosesTheDrawer() {
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page());
    sheet.handle().performClick();
    assertFalse(sheet.isShowing());
  }

  @Test
  public void draggingTheHandleDownPastThresholdClosesTheDrawer() {
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page());
    dragHandle(sheet.handle(), 10f, 180f);
    assertFalse("下拖超过 96dp 关闭抽屉", sheet.isShowing());
  }

  @Test
  public void smallDragReturnsTheDrawerToRest() {
    BottomSheetCard.Sheet sheet = BottomSheetCard.show(activity, page());
    dragHandle(sheet.handle(), 10f, 40f);
    assertTrue(sheet.isShowing());
    assertEquals(0f, sheet.container().getTranslationY(), 0.5f);
    sheet.dismiss();
  }

  private static void dragHandle(View handle, float fromY, float toY) {
    long now = SystemClock.uptimeMillis();
    send(handle, MotionEvent.ACTION_DOWN, now, now, fromY);
    send(handle, MotionEvent.ACTION_MOVE, now, now + 20, (fromY + toY) / 2f);
    send(handle, MotionEvent.ACTION_MOVE, now, now + 40, toY);
    send(handle, MotionEvent.ACTION_UP, now, now + 60, toY);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
  }

  private static void send(View target, int action, long down, long time, float y) {
    MotionEvent event = MotionEvent.obtain(down, time, action, 20f, y, 0);
    target.dispatchTouchEvent(event);
    event.recycle();
  }
}
