package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
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

/** B3 页尾技术说明：一行摘要 + 展开事实明细；沿用 DisclosureCard，空明细不折叠。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TechNoteTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TechNoteTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContextThemeWrapper context;
  private LinearLayout footer;

  @Before
  public void setUp() {
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    footer = new LinearLayout(context);
    footer.setOrientation(LinearLayout.VERTICAL);
  }

  @Test
  public void summaryIsVisibleAndFactsAreOnlyCreatedWhenExpanded() {
    DisclosureCard.Memory memory = new DisclosureCard.Memory();
    TechNote.Model model = new TechNote.Model();
    model.key = "config-tech";
    model.summary = "技术说明";
    model.detail = "配置保存在应用私有目录；端口与运行方式由上方设置决定。";
    TextView summary = TechNote.attach(footer, model, memory, null);
    assertNotNull(summary);
    assertEquals("技术说明", summary.getText().toString());
    // 收起态只有空的详情容器：明细与动作都不创建，读屏读不到隐藏内容。
    assertEquals(2, footer.getChildCount());
    LinearLayout holder = (LinearLayout) footer.getChildAt(1);
    assertEquals(android.view.View.GONE, holder.getChildAt(0).getVisibility());
    assertEquals(android.view.View.GONE, holder.getChildAt(1).getVisibility());
    summary.performClick();
    assertTrue(DisclosureCard.expanded(summary));
    assertEquals(Boolean.TRUE, memory.expanded("config-tech"));
    assertEquals(android.view.View.VISIBLE, holder.getChildAt(1).getVisibility());
    assertTrue(((LinearLayout) holder.getChildAt(1)).getChildCount() > 0);
  }

  @Test
  public void emptyDetailDoesNotCreateADisclosure() {
    TechNote.Model model = new TechNote.Model();
    model.summary = "技术说明";
    TextView summary = TechNote.attach(footer, model, null, null);
    assertNotNull(summary);
    assertFalse(TechNote.hasDetail(model));
    // 没有明细时不加箭头，也不可点。
    assertFalse(summary.isClickable());
  }

  @Test
  public void stateCanBeSavedAndRestored() {
    DisclosureCard.Memory memory = new DisclosureCard.Memory();
    TechNote.Model model = new TechNote.Model();
    model.key = "launch-tech";
    model.summary = "技术说明";
    model.detail = "proot · dsh 0.2.2-rc.1";
    TextView summary = TechNote.attach(footer, model, memory, null);
    summary.performClick();
    Bundle saved = new Bundle();
    memory.save(saved, "disclosure");
    DisclosureCard.Memory restored = DisclosureCard.Memory.restore(saved, "disclosure");
    assertEquals(Boolean.TRUE, restored.expanded("launch-tech"));
  }

  @Test
  public void stylesStayThemeDriven() {
    TechNote.Model model = new TechNote.Model();
    model.summary = "技术说明";
    model.detail = "包名 · Android 版本";
    TextView summary = TechNote.attach(footer, model, null, null);
    assertNotNull(summary);
    assertNotNull(summary.getTextColors());
    assertEquals(context.getColor(R.color.shell_text_muted), summary.getCurrentTextColor());
    ColorStateList unused = summary.getTextColors();
    assertNotNull(unused);
    assertEquals(View.VISIBLE, summary.getVisibility());
  }
}
