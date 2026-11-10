package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** B2 单开手风琴：同一屏只开一段；仍沿用 DisclosureCard 的后果行与折叠语义。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = AccordionTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AccordionTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;
  private LinearLayout host;
  private TextView first;
  private TextView second;
  private DisclosureCard.Model firstModel;
  private DisclosureCard.Model secondModel;
  private DisclosureCard.Memory memory;
  private Accordion accordion;

  @Before
  public void setUp() {
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    host = new LinearLayout(context);
    host.setOrientation(LinearLayout.VERTICAL);
    first = summary("设备执行通道");
    second = summary("全部文件访问");
    host.addView(first);
    host.addView(second);
    memory = new DisclosureCard.Memory();
    accordion = new Accordion(memory);
    firstModel = model("channels", "Root / Shizuku / ADB 的拦截范围");
    firstModel.consequence = "高风险：root 可执行白名单外命令";
    secondModel = model("all-files", "通过系统选择器访问共享目录");
    accordion.register("channels", this::bindFirst);
    accordion.register("all-files", this::bindSecond);
    bindFirst();
    bindSecond();
  }

  private void bindFirst() {
    DisclosureCard.bind(first, firstModel, accordion.state(), () -> accordion.onToggle("channels"));
  }

  private void bindSecond() {
    DisclosureCard.bind(
        second, secondModel, accordion.state(), () -> accordion.onToggle("all-files"));
  }

  @Test
  public void openingOneSectionClosesTheOther() {
    first.performClick();
    assertTrue(DisclosureCard.expanded(first));
    assertFalse(DisclosureCard.expanded(second));
    assertEquals(Boolean.TRUE, memory.expanded("channels"));
    assertEquals(Boolean.FALSE, memory.expanded("all-files"));

    second.performClick();
    assertFalse("另一段必须自动收起", DisclosureCard.expanded(first));
    assertTrue(DisclosureCard.expanded(second));
    assertEquals(Boolean.FALSE, memory.expanded("channels"));
    assertEquals(Boolean.TRUE, memory.expanded("all-files"));
  }

  @Test
  public void consequenceLineStaysVisibleWhenCollapsed() {
    first.performClick();
    second.performClick();
    // 收起后正文不可见，但后果行仍在（安全后果不藏）。
    String visible = mergedText(host);
    assertTrue(visible.contains("高风险：root 可执行白名单外命令"));
    assertFalse(visible.contains("Root / Shizuku / ADB 的拦截范围"));
  }

  @Test
  public void programmaticOpenAlsoClosesOthers() {
    second.performClick();
    assertTrue(DisclosureCard.expanded(second));
    accordion.open("channels");
    assertTrue(DisclosureCard.expanded(first));
    assertFalse(DisclosureCard.expanded(second));
  }

  @Test
  public void collapsingOneSectionDoesNotOpenAnother() {
    first.performClick();
    first.performClick();
    assertFalse(DisclosureCard.expanded(first));
    assertFalse(DisclosureCard.expanded(second));
  }

  private TextView summary(String text) {
    TextView view = new TextView(context);
    view.setText(text);
    return view;
  }

  private DisclosureCard.Model model(String key, String detail) {
    DisclosureCard.Model model = new DisclosureCard.Model();
    model.key = key;
    model.detail = detail;
    return model;
  }

  /** 拼接可见文字：GONE 的详情容器不算。 */
  private static String mergedText(android.view.ViewGroup summaryHost) {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < summaryHost.getChildCount(); index++) {
      android.view.View child = summaryHost.getChildAt(index);
      if (child.getVisibility() != android.view.View.VISIBLE) continue;
      if (child instanceof TextView label) text.append(label.getText());
      else if (child instanceof android.view.ViewGroup group) text.append(merger(group));
    }
    return text.toString();
  }

  private static String merger(android.view.ViewGroup group) {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < group.getChildCount(); index++) {
      android.view.View child = group.getChildAt(index);
      if (child.getVisibility() != android.view.View.VISIBLE) continue;
      if (child instanceof TextView label) text.append(label.getText());
      else if (child instanceof android.view.ViewGroup nested) text.append(merger(nested));
    }
    return text.toString();
  }
}
