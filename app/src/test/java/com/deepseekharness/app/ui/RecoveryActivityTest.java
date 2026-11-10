package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 应急启动页：首屏只留结论与主操作，临时密钥与工具默认收起，展开/收起不改确认边界。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = RecoveryActivityTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecoveryActivityTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private static final String DETAIL =
      "正式环境维护失败时，可在这里启动独立的空白 DSH。它可以读取相关故障文件并向当前模型服务发送内容，提出修复方案；每次正式数据写入都需在此确认。";

  private ActivityController<RecoveryActivity> controller;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage(UiLanguagePreference.ZH);
  }

  @After
  public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void startupPageShowsOneLineConclusionAndPrimaryActions() {
    RecoveryActivity page = open();
    View root = page.getWindow().getDecorView();
    assertNotNull(findText(root, "维护失败时，从这里启动独立的应急 DSH。"));
    assertNull("详细边界只在 ⓘ 里", findText(root, DETAIL));
    assertTrue(shown(findButton(root, "启动应急 DSH")));
    assertTrue(shown(findButton(root, "进入空白应急 Web")));
    assertTrue(shown(findButton(root, "停止应急 DSH")));
    assertNotNull(findText(root, "修复提案"));
    assertNotNull(findButton(root, "查看无需模型的修复选项"));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void temporaryKeyAndToolsStayCollapsedUntilTapped() {
    RecoveryActivity page = open();
    View root = page.getWindow().getDecorView();
    EditText key = findEditText(root);
    assertNotNull(key);
    assertFalse("临时 Key 默认收起", shown(key));
    assertFalse("诊断与工具默认收起", shown(findButton(root, "查看原生诊断")));

    findButton(root, "临时 API Key（仅本次会话）").performClick();
    assertTrue(shown(key));
    findButton(root, "收起").performClick();
    assertFalse(shown(key));

    findButton(root, "更多").performClick();
    assertTrue(shown(findButton(root, "查看原生诊断")));
    assertTrue(shown(findButton(root, "查看并下载错误日志")));
    findButton(root, "收起").performClick();
    assertFalse(shown(findButton(root, "查看原生诊断")));
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
  public void repairPickerWithoutServiceOnlyWritesStatusText() {
    RecoveryActivity page = open();
    View root = page.getWindow().getDecorView();
    findButton(root, "查看无需模型的修复选项").performClick();
    settle();
    assertTrue("没有应急服务时不弹空对话框", ShadowDialog.getShownDialogs().isEmpty());
    assertNotNull(findText(root, "请先启动应急服务；无需配置模型。"));
  }

  private RecoveryActivity open() {
    controller = Robolectric.buildActivity(RecoveryActivity.class).setup().visible();
    settle();
    return controller.get();
  }

  private void settle() {
    for (int index = 0; index < 3; index++) {
      ShadowLooper.idleMainLooper();
      View root = controller.get().getWindow().getDecorView();
      root.measure(
          View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
      root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
    }
  }

  private static boolean shown(View view) {
    if (view == null) return false;
    for (View current = view; current != null; current = parent(current)) {
      if (current.getVisibility() != View.VISIBLE) return false;
    }
    return true;
  }

  private static View parent(View view) {
    return view.getParent() instanceof View parent ? parent : null;
  }

  private static Button findButton(View view, String text) {
    if (view instanceof Button button && text.contentEquals(button.getText())) return button;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        Button found = findButton(group.getChildAt(index), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView shown && text.contentEquals(shown.getText())) return shown;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        TextView found = findText(group.getChildAt(index), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static EditText findEditText(View view) {
    if (view instanceof EditText field) return field;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        EditText found = findEditText(group.getChildAt(index));
        if (found != null) return found;
      }
    }
    return null;
  }
}
