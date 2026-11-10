package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * F4 设置页布局接入：真实 fragment_settings 里三组都有稳定 id，标题可折叠、分组正文随之收起；
 * 短屏 + 1.3 倍字体下标题与页签不横向溢出。只验证布局与本地状态，不启动 Harness。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = SettingsGroupsLayoutTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsGroupsLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  @After
  public void tearDown() {
    RuntimeEnvironment.setFontScale(1f);
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void threeGroupsCollapseIndependently() {
    ScrollView scroll = inflateSettings();
    GroupedList list = install(scroll, new GroupedList.Memory());
    assertNotNull(list);
    assertEquals(List.of("environment", "experience", "support"), list.keys());

    TextView supportHeader = scroll.findViewById(R.id.settings_section_support);
    View supportGroup = scroll.findViewById(R.id.settings_group_support);
    supportHeader.performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(View.GONE, supportGroup.getVisibility());
    assertEquals("已收起", supportHeader.getStateDescription().toString());
    assertEquals(View.VISIBLE, scroll.findViewById(R.id.settings_group_experience).getVisibility());

    supportHeader.performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(View.VISIBLE, supportGroup.getVisibility());
  }

  @Test
  public void collapsedStateIsRestoredFromMemory() {
    ScrollView scroll = inflateSettings();
    GroupedList.Memory memory = new GroupedList.Memory();
    install(scroll, memory).toggle("experience");
    android.os.Bundle saved = new android.os.Bundle();
    memory.save(saved, "groups");
    GroupedList.Memory restoredMemory = GroupedList.Memory.restore(saved, "groups");
    ScrollView second = inflateSettings();
    GroupedList restored = install(second, restoredMemory);
    assertTrue(restored.collapsed("experience"));
    assertEquals(View.GONE, second.findViewById(R.id.settings_group_experience).getVisibility());
  }

  @Test
  public void compactWidthWithLargeFontHasNoHorizontalOverflow() {
    RuntimeEnvironment.setFontScale(1.3f);
    ScrollView scroll = inflateSettings();
    install(scroll, new GroupedList.Memory());
    int width = 320;
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
    scroll.layout(0, 0, width, scroll.getMeasuredHeight());
    ShadowLooper.shadowMainLooper().idle();
    java.util.List<String> overflow = new java.util.ArrayList<>();
    collectOverflow(scroll, 0, width, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
    for (int id :
        new int[] {
          R.id.settings_section_environment,
          R.id.settings_section_experience,
          R.id.settings_section_support
        }) {
      TextView header = scroll.findViewById(id);
      assertNotNull(header);
      assertTrue(header.getMeasuredWidth() > 0);
    }
  }

  private ScrollView inflateSettings() {
    View view = LayoutInflater.from(context).inflate(R.layout.fragment_settings, null, false);
    return (ScrollView) view;
  }

  private GroupedList install(ScrollView scroll, GroupedList.Memory memory) {
    return GroupedList.install(
        scroll,
        List.of(
            new GroupedList.Group(
                "environment",
                scroll.findViewById(R.id.settings_section_environment),
                scroll.findViewById(R.id.settings_tabs)),
            new GroupedList.Group(
                "experience",
                scroll.findViewById(R.id.settings_section_experience),
                scroll.findViewById(R.id.settings_group_experience)),
            new GroupedList.Group(
                "support",
                scroll.findViewById(R.id.settings_section_support),
                scroll.findViewById(R.id.settings_group_support))),
        memory,
        null);
  }

  private static void collectOverflow(View view, int left, int width, List<String> out) {
    if (view.getVisibility() != View.VISIBLE) return;
    int start = left + view.getLeft();
    int end = start + view.getWidth();
    if (start < -1 || end > width + 1) {
      String label =
          view instanceof TextView ? String.valueOf(((TextView) view).getText()) : view.toString();
      out.add(label + " [" + start + "," + end + "]");
      return;
    }
    if (view instanceof android.view.ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++)
        collectOverflow(group.getChildAt(index), start - view.getScrollX(), width, out);
  }
}
