package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.ProgressState;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** E1 确定性进度：真实百分比只增不减、取消只发一次、完成态停动画并收起取消。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = DeterminateProgressTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeterminateProgressTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;
  private ContextThemeWrapper context;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    animations(false);
  }

  private void animations(boolean enabled) {
    float scale = enabled ? 1f : 0f;
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
  }

  @Test
  public void percentOnlyMovesForwardAndNeverReachesOneHundredWhileRunning() {
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setLabel("正在恢复");
    progress.setPercent(30);
    assertEquals(30, progress.percent());
    assertEquals("30%", progress.valueText().toString());
    progress.setPercent(12);
    assertEquals(30, progress.percent());
    progress.setPercent(120);
    assertEquals(99, progress.percent());
    assertTrue(progress.running());
    assertEquals(ProgressState.Phase.RUNNING, progress.phase());
  }

  @Test
  public void indeterminateModeShowsNoFakePercentage() {
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setIndeterminate();
    assertTrue(progress.running());
    assertEquals("", progress.valueText().toString());
    // 拿到真实总量后切回确定态，显示真实百分比。
    progress.setPercent(40);
    assertEquals("40%", progress.valueText().toString());
  }

  @Test
  public void cancelRunsOnceAndWaitsForTheTaskToStop() {
    final int[] invoked = {0};
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setPercent(10);
    progress.setCancelAction("取消当前作业", () -> invoked[0]++);
    assertTrue(progress.cancelVisible());
    View cancel = cancelView(progress);
    cancel.performClick();
    cancel.performClick();
    assertEquals(1, invoked[0]);
    assertFalse(cancel.isEnabled());
    // 后端还没确认结束时仍是运行中；收到取消收尾才进入取消终态。
    assertTrue(progress.running());
    progress.cancelled("已取消");
    assertEquals(ProgressState.Phase.CANCELLED, progress.phase());
    assertFalse(progress.cancelVisible());
    assertTrue(progress.terminalVisible());
    assertFalse(progress.running());
  }

  @Test
  public void completeStopsAtOneHundredAndHidesCancel() {
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setPercent(80);
    progress.setCancelAction("取消", () -> {});
    progress.complete("恢复完成");
    assertEquals(ProgressState.Phase.COMPLETED, progress.phase());
    assertEquals(100, progress.percent());
    assertFalse(progress.cancelVisible());
    assertTrue(progress.terminalVisible());
    assertEquals("恢复完成", labelText(progress));
  }

  @Test
  public void failureKeepsTheRealPercentAndIsTerminal() {
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setPercent(55);
    progress.fail("恢复未完成");
    assertEquals(ProgressState.Phase.FAILED, progress.phase());
    assertEquals(55, progress.percent());
    assertTrue(progress.terminalVisible());
    assertFalse(progress.running());
  }

  @Test
  public void ringModeShowsPercentInsideTheRing() {
    DeterminateProgress progress = new DeterminateProgress(context, DeterminateProgress.Mode.RING);
    progress.setPercent(45);
    assertEquals(DeterminateProgress.Mode.RING, progress.mode());
    assertEquals("45%", progress.valueText().toString());
  }

  @Test
  public void resetClearsTerminalStateForTheNextTask() {
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setPercent(70);
    progress.complete("完成");
    progress.reset();
    assertEquals(ProgressState.Phase.IDLE, progress.phase());
    assertEquals(-1, progress.percent());
    assertFalse(progress.terminalVisible());
    progress.setPercent(5);
    assertEquals("5%", progress.valueText().toString());
  }

  @Test
  public void animationsEnabledStillEndsInTheSameState() {
    animations(true);
    DeterminateProgress progress = new DeterminateProgress(context);
    progress.setPercent(10);
    progress.setPercent(60);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(400));
    assertEquals(60, progress.percent());
    progress.complete("完成");
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(400));
    assertEquals(100, progress.percent());
    assertFalse(progress.cancelVisible());
  }

  private static View cancelView(DeterminateProgress progress) {
    for (int index = 0; index < progress.getChildCount(); index++) {
      View child = progress.getChildAt(index);
      if (child instanceof android.widget.LinearLayout row) {
        for (int cell = 0; cell < row.getChildCount(); cell++)
          if (row.getChildAt(cell).isClickable()) return row.getChildAt(cell);
      }
    }
    throw new AssertionError("cancel view not found");
  }

  private static CharSequence labelText(DeterminateProgress progress) {
    for (int index = 0; index < progress.getChildCount(); index++) {
      View child = progress.getChildAt(index);
      if (child instanceof android.widget.LinearLayout row) {
        for (int cell = 0; cell < row.getChildCount(); cell++)
          if (row.getChildAt(cell) instanceof TextView text
              && !text.isClickable()
              && text.getText().length() > 0) {
            CharSequence value = text.getText();
            if (!value.toString().endsWith("%")) return value;
          }
      }
    }
    throw new AssertionError("label view not found");
  }
}
