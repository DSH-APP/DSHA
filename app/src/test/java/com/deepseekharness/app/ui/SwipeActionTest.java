package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import java.time.Duration;

/**
 * 左滑动作组件：阈值内回弹、超过阈值只露出动作、滑动本身不执行任何动作；破坏性动作只走
 * 二次确认；RTL 手势镜像；横向滚动列表不挂滑动；同屏只开一行；读屏自定义动作可打开动作条。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = SwipeActionTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SwipeActionTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;
  private LinearLayout parent;

  @Before
  public void setUp() {
    ContentResolver resolver = RuntimeEnvironment.getApplication().getContentResolver();
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0f);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    parent = new LinearLayout(context);
    parent.setOrientation(LinearLayout.VERTICAL);
  }

  private View row() {
    TextView row = new TextView(context);
    row.setText("备份记录");
    row.setMinHeight(56);
    row.setPadding(16, 16, 16, 16);
    row.setBackgroundColor(context.getColor(R.color.shell_surface));
    row.setClickable(true);
    row.setOnClickListener(view -> {});
    parent.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2));
    return row;
  }

  private void layout(View root) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
    root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
  }

  private static void drag(View host, float fromX, float toX, float y) {
    long now = SystemClock.uptimeMillis();
    View target = (View) host;
    dispatch(target, MotionEvent.ACTION_DOWN, now, now, fromX, y);
    dispatch(target, MotionEvent.ACTION_MOVE, now, now + 20, (fromX + toX) / 2f, y);
    dispatch(target, MotionEvent.ACTION_MOVE, now, now + 40, toX, y);
    dispatch(target, MotionEvent.ACTION_UP, now, now + 60, toX, y);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
  }

  private static void dispatch(View target, int action, long down, long time, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
    target.dispatchTouchEvent(event);
    event.recycle();
  }

  private SwipeAction.Model twoActions(int[] runs, int[] confirms) {
    SwipeAction.Model model = new SwipeAction.Model();
    model.add(SwipeAction.Action.of("导出", R.drawable.ic_ui_export, () -> runs[0]++));
    model.add(
        SwipeAction.Action.destructive("删除", "再次确认后删除", R.drawable.ic_onb_x, () -> confirms[0]++));
    return model;
  }

  @Test
  public void swipePastThresholdOnlyRevealsActionsAndNeverRunsThem() {
    int[] runs = {0}, confirms = {0};
    View host = SwipeAction.attach(row(), twoActions(runs, confirms));
    assertTrue(host instanceof SwipeAction.Host);
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    int width = swipe.strip().getMeasuredWidth();
    assertTrue("动作条必须有宽度", width > 0);

    drag(host, 300f, 300f - width * 0.9f, 20f);

    assertTrue("超过阈值应停在打开位置", swipe.isOpen());
    assertEquals(-width, swipe.translation(), 1.5f);
    assertEquals("滑动本身不执行动作", 0, runs[0]);
    assertEquals("破坏性动作只在确认后才执行", 0, confirms[0]);
  }

  @Test
  public void swipeBelowThresholdSpringsClosed() {
    int[] runs = {0}, confirms = {0};
    View host = SwipeAction.attach(row(), twoActions(runs, confirms));
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    int width = swipe.strip().getMeasuredWidth();

    drag(host, 300f, 300f - width * 0.3f, 20f);

    assertFalse(swipe.isOpen());
    assertEquals(0f, swipe.translation(), 0.5f);
    assertEquals(0, runs[0]);
    assertEquals(0, confirms[0]);
  }

  @Test
  public void tappingRevealedActionRunsNonDestructiveAndOpensConfirmForDestructive() {
    int[] runs = {0}, confirms = {0};
    View host = SwipeAction.attach(row(), twoActions(runs, confirms));
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    int width = swipe.strip().getMeasuredWidth();

    drag(host, 300f, 300f - width, 20f);
    TextView export = (TextView) ((LinearLayout) swipe.strip()).getChildAt(0);
    export.performClick();
    assertEquals(1, runs[0]);
    assertFalse("点动作后收起", swipe.isOpen());

    drag(host, 300f, 300f - width, 20f);
    TextView delete = (TextView) ((LinearLayout) swipe.strip()).getChildAt(1);
    delete.performClick();
    assertEquals("删除不直接执行，只弹确认", 1, runs[0]);
    assertEquals(1, confirms[0]);
  }

  @Test
  public void destructiveActionWithoutConfirmationIsNotSelectable() {
    int[] confirms = {0};
    SwipeAction.Model model = new SwipeAction.Model();
    model.add(SwipeAction.Action.destructive("删除", "", 0, null));
    View host = SwipeAction.attach(row(), model);
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    int width = swipe.strip().getMeasuredWidth();
    drag(host, 300f, 300f - width, 20f);
    TextView delete = (TextView) ((LinearLayout) swipe.strip()).getChildAt(0);
    assertFalse("没有确认回调的破坏性动作不可点", delete.isEnabled());
    delete.performClick();
    assertEquals(0, confirms[0]);
  }

  @Test
  public void rtlSwipesTowardTheRightAndMirrorsTranslation() {
    int[] runs = {0}, confirms = {0};
    parent.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    View host = SwipeAction.attach(row(), twoActions(runs, confirms));
    ((SwipeAction.Host) host).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    int width = swipe.strip().getMeasuredWidth();

    drag(host, 40f, 40f + width * 0.9f, 20f);

    assertTrue("RTL 向右滑打开", swipe.isOpen());
    assertEquals("RTL 位移镜像到正方向", width, swipe.translation(), 1.5f);

    // 反方向（向左）在 RTL 下不露出动作。
    drag(host, 300f, 100f, 20f);
    assertFalse(swipe.isOpen());
  }

  @Test
  public void horizontalScrollableListIsNotSwiped() {
    HorizontalScrollView scroller = new HorizontalScrollView(context);
    FrameLayout holder = new FrameLayout(context);
    scroller.addView(holder);
    TextView row = new TextView(context);
    row.setText("横向内容");
    row.setClickable(true);
    holder.addView(row, new FrameLayout.LayoutParams(600, 80));
    int[] runs = {0}, confirms = {0};
    View result = SwipeAction.attach(row, twoActions(runs, confirms));
    assertSame("横向滚动列表不挂左滑", row, result);
  }

  @Test
  public void onlyOneRowStaysOpenInTheSameRoot() {
    int[] runs = {0}, confirms = {0};
    View first = SwipeAction.attach(row(), twoActions(runs, confirms));
    View second = SwipeAction.attach(row(), twoActions(runs, confirms));
    layout(parent);
    int width = ((SwipeAction.Host) first).strip().getMeasuredWidth();
    drag(first, 300f, 300f - width, 20f);
    assertTrue(((SwipeAction.Host) first).isOpen());
    drag(second, 300f, 300f - width, 20f);
    assertFalse("打开新行会收起旧行", ((SwipeAction.Host) first).isOpen());
    assertTrue(((SwipeAction.Host) second).isOpen());
  }

  @Test
  public void accessibilityActionOpensTheActionsForScreenReaderUsers() {
    int[] runs = {0}, confirms = {0};
    View row = row();
    View host = SwipeAction.attach(row, twoActions(runs, confirms));
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    assertTrue(row.performAccessibilityAction(SwipeAction.OPEN_ACTION, null));
    assertTrue(swipe.isOpen());
    assertEquals(0, runs[0]);
  }

  @Test
  public void disabledModelNeverOpens() {
    int[] runs = {0}, confirms = {0};
    SwipeAction.Model model = twoActions(runs, confirms);
    model.enabled = false;
    View row = row();
    View host = SwipeAction.attach(row, model);
    layout(parent);
    SwipeAction.Host swipe = (SwipeAction.Host) host;
    drag(host, 300f, 120f, 20f);
    assertFalse(swipe.isOpen());
    assertFalse(swipe.enabled());
    assertFalse(row.performAccessibilityAction(SwipeAction.OPEN_ACTION, null));
  }
}
