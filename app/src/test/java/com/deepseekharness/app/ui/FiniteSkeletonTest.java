package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** E4 有限骨架：数据到即停、到上限必须超时、动画关闭时保持静态。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = FiniteSkeletonTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FiniteSkeletonTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;
  private ContextThemeWrapper context;
  private LinearLayout host;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animations(false);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    host = new LinearLayout(context);
  }

  private void animations(boolean enabled) {
    float scale = enabled ? 1f : 0f;
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
  }

  @Test
  public void dataArrivalStopsTheSkeletonImmediately() {
    FiniteSkeleton skeleton = FiniteSkeleton.attach(host, 3, 1500);
    skeleton.start();
    assertTrue(skeleton.visible());
    View firstRow = host.getChildAt(0);
    assertEquals(View.VISIBLE, host.getVisibility());
    skeleton.stop();
    assertFalse(skeleton.visible());
    assertFalse(skeleton.timedOut());
    assertEquals(View.GONE, host.getVisibility());
    // 停止后即使时间流逝也不会再触发超时回调。
    final int[] timeouts = {0};
    skeleton.setListener(() -> timeouts[0]++);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(2_000));
    assertEquals(0, timeouts[0]);
    assertNotNull(firstRow);
  }

  @Test
  public void skeletonExpiresAtTheHardCapAndStopsAnimating() {
    FiniteSkeleton skeleton = FiniteSkeleton.attach(host, 2, 60_000);
    assertEquals(com.deepseekharness.app.util.SkeletonTiming.MAX_MS, skeleton.durationMs());
    final int[] timeouts = {0};
    skeleton.setListener(() -> timeouts[0]++);
    skeleton.start();
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(1_500));
    assertEquals(0, timeouts[0]);
    assertTrue(skeleton.visible());
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(600));
    assertEquals(1, timeouts[0]);
    assertTrue(skeleton.timedOut());
    assertFalse(skeleton.visible());
    assertEquals(View.GONE, host.getVisibility());
  }

  @Test
  public void requestedDurationIsClampedToOneToTwoSeconds() {
    FiniteSkeleton shortOne = FiniteSkeleton.attach(new LinearLayout(context), 1, 10);
    assertEquals(com.deepseekharness.app.util.SkeletonTiming.MIN_MS, shortOne.durationMs());
  }

  @Test
  public void secondStartAfterStopIsIgnoredWhileAlreadyRunning() {
    FiniteSkeleton skeleton = FiniteSkeleton.attach(host, 1, 1200);
    skeleton.start();
    skeleton.start();
    assertEquals(View.VISIBLE, host.getVisibility());
    skeleton.stop();
    skeleton.start();
    assertTrue(skeleton.visible());
    skeleton.stop();
  }
}
