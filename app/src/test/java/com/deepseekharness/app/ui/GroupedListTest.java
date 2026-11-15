package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** F4 分组：标题吸顶、到组底被顶走、折叠正文连同读屏一起收起、状态可持久化。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = GroupedListTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GroupedListTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;
  private ScrollView scroll;
  private TextView headerEnvironment;
  private TextView headerSupport;
  private View bodyEnvironment;
  private View bodySupport;

  @Before
  public void setUp() {
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    scroll = new ScrollView(context);
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    headerEnvironment = label("环境");
    bodyEnvironment = block(300);
    headerSupport = label("支持");
    bodySupport = block(300);
    content.addView(headerEnvironment, new LinearLayout.LayoutParams(-1, 24));
    content.addView(bodyEnvironment, new LinearLayout.LayoutParams(-1, 300));
    content.addView(headerSupport, new LinearLayout.LayoutParams(-1, 24));
    content.addView(bodySupport, new LinearLayout.LayoutParams(-1, 300));
    scroll.addView(content);
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
    scroll.layout(0, 0, 360, 200);
  }

  @Test
  public void headersStickAndArePushedOutAtTheEndOfTheirGroup() {
    GroupedList list =
        GroupedList.install(
            scroll,
            List.of(
                new GroupedList.Group("environment", headerEnvironment, bodyEnvironment),
                new GroupedList.Group("support", headerSupport, bodySupport)),
            null,
            null);
    assertNotNull(list);
    assertEquals(0f, headerEnvironment.getTranslationY(), 0.001f);

    scroll.scrollTo(0, 150);
    list.refresh();
    assertEquals(150f, headerEnvironment.getTranslationY(), 0.001f);
    assertEquals(0f, headerSupport.getTranslationY(), 0.001f);

    scroll.scrollTo(0, 400);
    list.refresh();
    // 第一组到组底被顶走，第二组贴顶。
    assertEquals(300f, headerEnvironment.getTranslationY(), 0.001f);
    assertEquals(76f, headerSupport.getTranslationY(), 0.001f);
  }

  @Test
  public void collapsingHidesTheBodyAndIsRemembered() {
    GroupedList.Memory memory = new GroupedList.Memory();
    GroupedList list =
        GroupedList.install(
            scroll,
            List.of(
                new GroupedList.Group("environment", headerEnvironment, bodyEnvironment),
                new GroupedList.Group("support", headerSupport, bodySupport)),
            memory,
            null);
    assertFalse(list.collapsed("environment"));
    list.toggle("environment");
    assertTrue(list.collapsed("environment"));
    assertEquals(View.GONE, bodyEnvironment.getVisibility());
    assertEquals("已收起", headerEnvironment.getStateDescription().toString());
    assertEquals(View.VISIBLE, bodySupport.getVisibility());

    Bundle saved = new Bundle();
    memory.save(saved, "groups");
    GroupedList.Memory restored = GroupedList.Memory.restore(saved, "groups");
    assertTrue(Boolean.TRUE.equals(restored.collapsed("environment")));
    assertNull(restored.collapsed("support"));
  }

  @Test
  public void headerClickTogglesAndStateDescriptionFollows() {
    GroupedList list =
        GroupedList.install(
            scroll,
            List.of(new GroupedList.Group("environment", headerEnvironment, bodyEnvironment)),
            null,
            null);
    headerEnvironment.performClick();
    assertTrue(list.collapsed("environment"));
    headerEnvironment.performClick();
    assertFalse(list.collapsed("environment"));
    assertEquals(View.VISIBLE, bodyEnvironment.getVisibility());
    assertEquals("已展开", headerEnvironment.getStateDescription().toString());
  }

  @Test
  public void duplicateOrUnknownGroupsAreIgnored() {
    assertNull(GroupedList.install(null, List.of(), null, null));
    assertNull(GroupedList.install(scroll, null, null, null));
    GroupedList list =
        GroupedList.install(
            scroll,
            List.of(new GroupedList.Group("only", headerEnvironment, bodyEnvironment)),
            null,
            null);
    list.toggle("missing");
    assertFalse(list.collapsed("missing"));
  }

  private TextView label(String text) {
    TextView view = new TextView(context);
    view.setText(text);
    return view;
  }

  private View block(int height) {
    View view = new View(context);
    view.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
    return view;
  }
}
