package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.os.Looper;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/**
 * 折叠披露：收起不创建详情、展开才出现说明与动作、默认展开只对未记录状态生效；
 * 系统关闭动画时直接落静态，不残留半透明。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = DisclosureCardTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisclosureCardTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;
  private ContextThemeWrapper context;
  private LinearLayout host;
  private TextView summary;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animationScales(1f);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    host = new LinearLayout(context);
    host.setOrientation(LinearLayout.VERTICAL);
    summary = new TextView(context);
    summary.setText("摘要");
    host.addView(summary, new LinearLayout.LayoutParams(-1, -2));
  }

  private void animationScales(float value) {
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, value);
  }

  private static DisclosureCard.Model model(String detail) {
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = "test";
    model.detail = detail;
    return model;
  }

  private static String findText(View view, String target) {
    if (view instanceof TextView text && target.contentEquals(text.getText())) {
      return text.getText().toString();
    }
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        String found = findText(group.getChildAt(i), target);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void collapsedByDefaultCreatesNoDetailView() {
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null);
    assertFalse(DisclosureCard.expanded(summary));
    assertTrue(summary.isClickable());
    assertNotNull(summary.getCompoundDrawablesRelative()[2]);
    assertNull("折叠时不创建隐藏详情", findText(host, "完整说明"));
  }

  @Test
  public void clickExpandsDetailAndSecondClickRemovesIt() {
    animationScales(0f);
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null);
    summary.performClick();
    assertTrue(DisclosureCard.expanded(summary));
    assertNotNull(findText(host, "完整说明"));
    summary.performClick();
    assertFalse(DisclosureCard.expanded(summary));
    assertNull(findText(host, "完整说明"));
  }

  /**
   * 展开→收起→再展开：动作视图由宿主复用，重新挂载前必须先脱离旧容器。真机「点徽章」第二次展开抛
   * IllegalStateException 的回归用例。
   */
  @Test
  public void actionsCanBeAttachedAgainAfterCollapse() {
    animationScales(0f);
    DisclosureCard.Model model = model("完整说明");
    TextView action = new TextView(context);
    action.setText("去系统设置");
    model.actions = new View[] {action};
    DisclosureCard.bind(summary, model, new DisclosureCard.Memory(), null);
    summary.performClick();
    assertNotNull(findText(host, "去系统设置"));
    summary.performClick();
    assertNull(findText(host, "去系统设置"));
    summary.performClick();
    assertNotNull("再次展开仍要挂上动作", findText(host, "去系统设置"));
    assertTrue(action.getParent() instanceof ViewGroup);
  }

  /** 展开状态下说明变化（例如从系统设置返回、状态翻转）重新渲染，动作同样不能重复挂父级。 */
  @Test
  public void changedDetailWhileExpandedRerendersActions() {
    animationScales(0f);
    DisclosureCard.Model model = model("说明一");
    TextView action = new TextView(context);
    action.setText("去系统设置");
    model.actions = new View[] {action};
    DisclosureCard.Memory state = new DisclosureCard.Memory();
    DisclosureCard.bind(summary, model, state, null);
    summary.performClick();
    assertNotNull(findText(host, "去系统设置"));
    model.detail = "说明二";
    DisclosureCard.bind(summary, model, state, null);
    assertNotNull(findText(host, "说明二"));
    assertNotNull("改说明后动作仍在", findText(host, "去系统设置"));
  }

  @Test
  public void collapseWithAnimationsStillRemovesDetail() {
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null);
    summary.performClick();
    summary.performClick();
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertNull(findText(host, "完整说明"));
    assertEquals(1f, summary.getAlpha(), 0.001f);
  }

  @Test
  public void defaultExpandedAppliesOnlyWithoutSavedState() {
    DisclosureCard.Model model = model("完整说明");
    model.defaultExpanded = true;
    DisclosureCard.Memory state = new DisclosureCard.Memory();
    DisclosureCard.bind(summary, model, state, null);
    assertTrue(DisclosureCard.expanded(summary));
    assertNotNull(findText(host, "完整说明"));
    assertEquals(Boolean.TRUE, state.expanded("test"));

    TextView other = new TextView(context);
    other.setText("另一摘要");
    host.addView(other, new LinearLayout.LayoutParams(-1, -2));
    state.setExpanded("test", false);
    DisclosureCard.bind(other, model, state, null);
    assertFalse("用户折叠后刷新不再自动展开", DisclosureCard.expanded(other));
  }

  @Test
  public void savedStateSurvivesViewRebuild() {
    animationScales(0f);
    DisclosureCard.Memory state = new DisclosureCard.Memory();
    DisclosureCard.bind(summary, model("完整说明"), state, null);
    summary.performClick();
    Bundle out = new Bundle();
    state.save(out, "disclosure");
    DisclosureCard.Memory restored = DisclosureCard.Memory.restore(out, "disclosure");
    assertEquals(Boolean.TRUE, restored.expanded("test"));

    TextView rebuilt = new TextView(context);
    rebuilt.setText("摘要");
    host.removeAllViews();
    host.addView(rebuilt, new LinearLayout.LayoutParams(-1, -2));
    DisclosureCard.bind(rebuilt, model("完整说明"), restored, null);
    assertTrue(DisclosureCard.expanded(rebuilt));
  }

  @Test
  public void consequenceStaysVisibleWhenCollapsed() {
    DisclosureCard.Model model = model("完整说明");
    model.consequence = "仅能访问应用私有目录";
    DisclosureCard.bind(summary, model, new DisclosureCard.Memory(), null);
    assertNotNull("安全后果收起态也可见", findText(host, "仅能访问应用私有目录"));
  }

  @Test
  public void actionsAreCreatedOnlyAfterExpanding() {
    animationScales(0f);
    TextView action = new TextView(context);
    action.setText("去设置");
    DisclosureCard.Model model = model("完整说明");
    model.actions = new View[] {action};
    DisclosureCard.bind(summary, model, new DisclosureCard.Memory(), null);
    assertNull(findText(host, "去设置"));
    summary.performClick();
    assertNotNull(findText(host, "去设置"));
  }

  @Test
  public void emptyDetailRestoresPlainText() {
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null);
    assertNotNull(summary.getCompoundDrawablesRelative()[2]);
    DisclosureCard.bind(summary, model(""), new DisclosureCard.Memory(), null);
    assertNull(summary.getCompoundDrawablesRelative()[2]);
    assertFalse(summary.isClickable());
    assertNull(findText(host, "完整说明"));
  }

  @Test
  public void horizontalSummaryInsertsDetailsIntoTheColumnAfterTheRow() {
    animationScales(0f);
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    TextView label = new TextView(context);
    label.setText("运行与网络");
    row.addView(label);
    TextView summary = new TextView(context);
    summary.setText("摘要");
    row.addView(summary);
    column.addView(row);
    host = column;
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null, row);
    assertTrue(summary.isClickable());
    assertEquals(2, host.getChildCount());
    assertTrue(host.getChildAt(1) instanceof LinearLayout);
    summary.performClick();
    assertNotNull(findText(host, "完整说明"));
  }

  @Test
  public void insertAfterKeepsSiblingOrder() {
    animationScales(0f);
    View track = new View(context);
    host.addView(track, 1, new LinearLayout.LayoutParams(-1, -2));
    DisclosureCard.bind(summary, model("完整说明"), new DisclosureCard.Memory(), null, track);
    summary.performClick();
    assertEquals(summary, host.getChildAt(0));
    assertEquals("活动轨道必须紧跟摘要", track, host.getChildAt(1));
    assertTrue("详情容器插在活动轨道之后", host.getChildAt(2) instanceof android.widget.LinearLayout);
  }
}
