package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** ⓘ 摘要设施：长文拆句、显式摘要、短句复位与无障碍描述。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = InfoHintTest.App.class)
public final class InfoHintTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static final String LONG = "后台断连时，可允许 DSHA 后台运行并减少电池限制。厂商仍可能暂停后台；恢复前先确认无线调试已开启。";

  private static TextView view() {
    TextView view = new TextView(RuntimeEnvironment.getApplication());
    view.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return view;
  }

  @Test
  public void longTextKeepsFirstSentenceAndAddsTapHint() {
    TextView view = view();
    view.setMaxLines(3);
    view.setText(LONG);
    InfoHint.bind(view, "电池优化", view.getText());
    assertEquals("后台断连时，可允许 DSHA 后台运行并减少电池限制。", view.getText().toString());
    assertEquals(2, view.getMaxLines());
    assertEquals(TextUtils.TruncateAt.END, view.getEllipsize());
    assertNotNull("长文要挂 ⓘ 图标", view.getCompoundDrawablesRelative()[2]);
    assertTrue("摘要可点开完整说明", view.isClickable());
    assertNotNull(view.getContentDescription());
    assertTrue(view.getContentDescription().toString().contains("后台断连时"));
    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.getLayoutParams().width);
  }

  @Test
  public void singleSentenceStaysPlainWithoutIcon() {
    TextView view = view();
    view.setText("已确认原 Web 实例退出，可以直接启动。");
    InfoHint.bind(view, null, view.getText());
    assertEquals("已确认原 Web 实例退出，可以直接启动。", view.getText().toString());
    assertNull(view.getCompoundDrawablesRelative()[2]);
    assertFalse(view.isClickable());
  }

  @Test
  public void explicitSummaryShowsSummaryWhileFullTextStaysReachable() {
    TextView view = view();
    view.setText("完整说明：只更新 dsh 与内置插件，个人数据保持原位；验证失败回切原运行时。");
    InfoHint.bind(view, "运行时更新", "只更新 dsh 与内置插件，个人数据保留", "完整说明：只更新 dsh 与内置插件，个人数据保持原位；验证失败回切原运行时。");
    assertEquals("只更新 dsh 与内置插件，个人数据保留", view.getText().toString());
    assertNotNull(view.getCompoundDrawablesRelative()[2]);
    assertTrue(view.isClickable());
    assertTrue(view.getContentDescription().toString().contains("只更新 dsh 与内置插件"));
  }

  @Test
  public void summaryEqualToFullTextDoesNotCreateATapTarget() {
    TextView view = view();
    InfoHint.bind(view, null, "环境维护在后台进行", "环境维护在后台进行");
    assertEquals("环境维护在后台进行", view.getText().toString());
    assertNull(view.getCompoundDrawablesRelative()[2]);
    assertFalse(view.isClickable());
  }

  @Test
  public void dynamicTextRestoresOriginalStyleWhenItShrinksBack() {
    TextView view = view();
    int gravity = view.getGravity();
    view.setMaxLines(3);
    view.setText(LONG);
    InfoHint.bind(view, "电池优化", view.getText());
    assertEquals(2, view.getMaxLines());
    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.getLayoutParams().width);

    InfoHint.bind(view, null, "短句。");
    assertEquals("短句。", view.getText().toString());
    assertEquals("回到短句要恢复布局折行数", 3, view.getMaxLines());
    assertNull(view.getEllipsize());
    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.getLayoutParams().width);
    assertEquals(gravity, view.getGravity());
    assertNull(view.getBackground());
    assertFalse(view.isClickable());
    assertNull(view.getContentDescription());
  }

  @Test
  public void nullArgumentsAreTolerated() {
    InfoHint.attach(null, "标题");
    InfoHint.bind(null, null, "文字");
    InfoHint.bind(null, null, "摘要", "全文");
    TextView view = view();
    InfoHint.bind(view, null, (CharSequence) null);
    assertEquals("", view.getText().toString());
  }

  @Test
  public void bindOnSurfaceKeepsCallerBackgroundAndWidth() {
    TextView view = view();
    view.setBackgroundResource(R.drawable.bg_onb_hint_bad);
    int padding = view.getPaddingLeft();
    android.graphics.drawable.Drawable background = view.getBackground();
    String full = "发生了什么：环境维护未完成。影响：原件已保留。下一步：恢复中断维护。";
    InfoHint.bindOnSurface(view, "失败", "失败", full);
    assertEquals("失败", view.getText().toString());
    assertNotNull("首屏短结论仍带 ⓘ", view.getCompoundDrawablesRelative()[2]);
    assertSame("错误条背景由调用方保留", background, view.getBackground());
    assertEquals("内边距不被 ⓘ 改写", padding, view.getPaddingLeft());
    assertEquals("错误条仍占满可用宽度", ViewGroup.LayoutParams.MATCH_PARENT, view.getLayoutParams().width);
    assertTrue(view.isClickable());
    assertTrue(view.getContentDescription().toString().contains("失败"));
  }

  @Test
  public void bindOnSurfaceWithoutExtraTextClearsTapTarget() {
    TextView view = view();
    view.setBackgroundResource(R.drawable.bg_onb_hint_warn);
    InfoHint.bindOnSurface(view, "失败", "失败", "失败");
    assertEquals("失败", view.getText().toString());
    assertNull(view.getCompoundDrawablesRelative()[2]);
    assertFalse(view.isClickable());
  }
}
