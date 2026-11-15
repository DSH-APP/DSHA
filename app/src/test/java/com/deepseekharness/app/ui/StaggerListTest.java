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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** F1 错峰：前 8 项、只第一次、容器重绘与滚动回来都不重放。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = StaggerListTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class StaggerListTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;
  private ContextThemeWrapper context;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animations(true);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    StaggerList.reset("rows");
    StaggerList.reset("recycled");
  }

  @After
  public void tearDown() {
    StaggerList.reset("rows");
    StaggerList.reset("recycled");
  }

  private void animations(boolean enabled) {
    float scale = enabled ? 1f : 0f;
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
  }

  @Test
  public void onlyTheFirstEightRowsAreRevealedOnce() {
    LinearLayout rows = rows(10);
    StaggerList.reveal(rows, "rows");
    for (int index = 0; index < 8; index++)
      assertTrue(StaggerList.revealed("rows", "position:" + index));
    assertFalse(StaggerList.revealed("rows", "position:8"));
    // 首播时前 8 项从 0 开始淡入，第 9 项不受影响。
    assertEquals(0f, rows.getChildAt(0).getAlpha(), 0.001f);
    assertEquals(1f, rows.getChildAt(8).getAlpha(), 0.001f);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(600));
    assertEquals(1f, rows.getChildAt(0).getAlpha(), 0.001f);
    // 重绘 / 滚动后回来不再播放：alpha 不被重置为 0。
    StaggerList.reveal(rows, "rows");
    assertEquals(1f, rows.getChildAt(0).getAlpha(), 0.001f);
  }

  @Test
  public void recycledRowsAreKeyedByItemNotByPosition() {
    View row = new View(context);
    StaggerList.revealRow(row, "recycled", "plugin-a");
    assertEquals(0f, row.getAlpha(), 0.001f);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(400));
    // 同一行再次绑定（复用）不重播。
    StaggerList.revealRow(row, "recycled", "plugin-a");
    assertEquals(1f, row.getAlpha(), 0.001f);
    assertTrue(StaggerList.revealed("recycled", "plugin-a"));
    assertFalse(StaggerList.revealed("recycled", "plugin-b"));
  }

  @Test
  public void animationsOffShowsRowsImmediately() {
    animations(false);
    LinearLayout rows = rows(3);
    StaggerList.reveal(rows, "rows");
    for (int index = 0; index < 3; index++)
      assertEquals(1f, rows.getChildAt(index).getAlpha(), 0.001f);
    assertFalse(StaggerList.revealed("rows", "position:0"));
  }

  private LinearLayout rows(int count) {
    LinearLayout rows = new LinearLayout(context);
    rows.setOrientation(LinearLayout.VERTICAL);
    for (int index = 0; index < count; index++) {
      View row = new View(context);
      row.setLayoutParams(new LinearLayout.LayoutParams(-1, 40));
      rows.addView(row);
    }
    return rows;
  }
}
