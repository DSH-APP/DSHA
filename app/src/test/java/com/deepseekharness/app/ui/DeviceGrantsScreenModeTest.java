package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.DialogInterface;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.screen.ScreenMode;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.ScreenModePolicy.Mode;
import com.deepseekharness.app.util.ScreenModePolicy.Switch;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * 识屏模式切换的界面契约：切到实体 / 虚拟必须二次确认，取消不切换，确认后才切换；成功、失败与待清理都
 * 有行内状态和 toast。只验证本地逻辑与文案，不替代真机 LayoutAudit。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DeviceGrantsScreenModeTest.App.class,
    shadows = DeviceGrantsScreenModeTest.NoStorageManager.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeviceGrantsScreenModeTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** Robolectric 没有存储卷，原生 isExternalStorageManager 会越界；这里按「未授予」处理。 */
  @Implements(android.os.Environment.class)
  public static final class NoStorageManager {
    @Implementation
    protected static boolean isExternalStorageManager() {
      return false;
    }
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;
  private ScreenOperationFragment.ScreenModeSwitch previousSwitch;
  private java.util.concurrent.Executor previousExecutor;
  private ScreenOperationFragment.SwitchOutcome nextOutcome;
  private int switchCalls;
  private Mode requestedTarget;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    previousSwitch = ScreenOperationFragment.screenModeSwitch;
    previousExecutor = ScreenOperationFragment.screenModeExecutor;
    // 直接执行，避免测试依赖后台线程时序；请求本身仍由测试注入。
    ScreenOperationFragment.screenModeExecutor = Runnable::run;
    ScreenOperationFragment.screenModeSwitch =
        (app, target, orientation) -> {
          switchCalls++;
          requestedTarget = target;
          return nextOutcome;
        };
    prefs().edit().putString(ScreenMode.KEY, "off").putString(ScreenMode.REASON_KEY, "").commit();
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    ScreenOperationFragment.screenModeSwitch = previousSwitch;
    ScreenOperationFragment.screenModeExecutor = previousExecutor;
    UiText.setLanguage(previousLanguage);
  }

  private static android.content.SharedPreferences prefs() {
    return RuntimeEnvironment.getApplication()
        .getSharedPreferences(Constants.PREFS, android.content.Context.MODE_PRIVATE);
  }

  private void start() {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new ScreenOperationFragment())
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
  }

  private View page() {
    return host.get()
        .getSupportFragmentManager()
        .findFragmentById(R.id.fragment_container)
        .getView();
  }

  private TextView status() {
    return page().findViewById(R.id.screen_mode_status);
  }

  private static AlertDialog latestDialog() {
    var dialog = ShadowDialog.getLatestDialog();
    assertNotNull("expected a dialog", dialog);
    return (AlertDialog) dialog;
  }

  private void openPicker() {
    page().findViewById(R.id.screen_mode_row).performClick();
    ShadowLooper.shadowMainLooper().idle();
  }

  private void chooseMode(Mode mode) {
    AlertDialog picker = latestDialog();
    ListView list = picker.getListView();
    assertNotNull("single-choice list expected", list);
    var listener = list.getOnItemClickListener();
    assertNotNull("single-choice listener expected", listener);
    listener.onItemClick(list, null, mode.ordinal(), mode.ordinal());
    ShadowLooper.shadowMainLooper().idle();
  }

  private static void collectText(View view, StringBuilder out) {
    if (view instanceof TextView text) out.append(text.getText()).append('\n');
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
  }

  private static String dialogText(AlertDialog dialog) {
    StringBuilder out = new StringBuilder();
    collectText(dialog.getWindow().getDecorView(), out);
    return out.toString();
  }

  private static TextView textViewContaining(AlertDialog dialog, String needle) {
    TextView found = findText(dialog.getWindow().getDecorView(), needle);
    assertNotNull("missing text: " + needle, found);
    return found;
  }

  private static TextView findText(View view, String needle) {
    if (view instanceof TextView text
        && text.getText() != null
        && text.getText().toString().contains(needle)) return text;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), needle);
        if (found != null) return found;
      }
    return null;
  }

  /** 弹窗正文放在可滚动的 NestedScrollView / ScrollView 里，短屏和大字体下不会压住按钮。 */
  private static boolean hasScrollableAncestor(View view) {
    for (View parent = (View) view.getParent(); parent != null; parent = (View) parent.getParent())
      if (parent.getClass().getSimpleName().contains("ScrollView")) return true;
    return false;
  }

  @Test
  public void cancellingConfirmationKeepsModeOff() {
    start();
    openPicker();
    chooseMode(Mode.PHYSICAL);

    AlertDialog confirm = latestDialog();
    String text = dialogText(confirm);
    assertTrue(text, text.contains("开启实体识屏？"));
    assertTrue(text, text.contains("读取主屏当前显示的内容"));
    assertTrue(text, text.contains("切回「关闭」"));

    confirm.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();

    assertEquals("cancel must not apply the switch", 0, switchCalls);
    assertEquals("关闭", status().getText().toString());
    assertEquals("off", prefs().getString(ScreenMode.KEY, ""));
  }

  @Test
  public void confirmingPhysicalSwitchShowsSuccess() {
    nextOutcome = new ScreenOperationFragment.SwitchOutcome(Switch.ALLOWED, "");
    start();
    openPicker();
    chooseMode(Mode.PHYSICAL);

    latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();

    assertEquals(1, switchCalls);
    assertEquals(Mode.PHYSICAL, requestedTarget);
    assertEquals("实体识屏 · 已切换", status().getText().toString());
    String toast = ShadowToast.getTextOfLatestToast();
    assertTrue(toast, toast.contains("已切换到实体识屏"));
    assertTrue(toast, toast.contains("可在此页随时关闭"));
  }

  @Test
  public void failedSwitchShowsFailureInsteadOfSilence() {
    nextOutcome = new ScreenOperationFragment.SwitchOutcome(Switch.NOT_ADMITTED, "NOT_ADMITTED");
    start();
    openPicker();
    chooseMode(Mode.PHYSICAL);

    latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();

    assertEquals(1, switchCalls);
    assertEquals("关闭 · 条件未满足", status().getText().toString());
    String toast = ShadowToast.getTextOfLatestToast();
    assertTrue(toast, toast.contains("条件未满足"));
    assertTrue(toast, toast.contains("实体识屏"));
  }

  /** 离开虚拟识屏遇待清理：不弹确认（关闭不需要），但失败提示与虚拟识屏页共用「待清理 / 核验关闭」。 */
  @Test
  public void cleanupPendingExitSharesVirtualScreenWording() {
    prefs().edit().putString(ScreenMode.KEY, "virtual").commit();
    nextOutcome =
        new ScreenOperationFragment.SwitchOutcome(Switch.CLEANUP_PENDING, "VSCREEN_CLOSING");
    start();
    openPicker();
    chooseMode(Mode.OFF);

    assertEquals(1, switchCalls);
    assertEquals(Mode.OFF, requestedTarget);
    assertEquals("虚拟识屏 · 待清理", status().getText().toString());
    String toast = ShadowToast.getTextOfLatestToast();
    assertTrue(toast, toast.contains("待清理"));
    assertTrue(toast, toast.contains("核验关闭"));
  }

  @Test
  public void repeatedSelectionReportsCurrentMode() {
    prefs().edit().putString(ScreenMode.KEY, "physical").commit();
    nextOutcome = new ScreenOperationFragment.SwitchOutcome(Switch.UNCHANGED, "");
    start();
    openPicker();
    chooseMode(Mode.PHYSICAL);

    // 目标与当前相同，不再弹二次确认，但仍给出明确反馈。
    assertEquals(1, switchCalls);
    assertEquals("实体识屏", status().getText().toString());
    assertTrue(ShadowToast.getTextOfLatestToast().contains("当前已是实体识屏"));
  }

  /** 320dp 短屏 + 1.3 倍字体、中英文：确认文案完整、无省略号，且位于可滚动容器内。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h400dp-mdpi")
  public void confirmationFitsShortScreenAndLargeFontInBothLanguages() {
    org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
    try {
      start();
      openPicker();
      chooseMode(Mode.PHYSICAL);
      assertConfirmationFits("读取主屏当前显示的内容");

      latestDialog().dismiss();
      ShadowLooper.shadowMainLooper().idle();
      UiText.setLanguage("en");
      openPicker();
      chooseMode(Mode.PHYSICAL);
      assertConfirmationFits("switch back to Off");
    } finally {
      org.robolectric.RuntimeEnvironment.setFontScale(1f);
    }
  }

  private void assertConfirmationFits(String phrase) {
    AlertDialog dialog = latestDialog();
    TextView message = textViewContaining(dialog, phrase);
    assertNull("message must not ellipsize", message.getEllipsize());
    assertTrue("expected a long, wrapped message", message.getText().length() > 40);
    assertTrue("message must sit in a scrollable container", hasScrollableAncestor(message));
    assertNotNull(dialog.getButton(DialogInterface.BUTTON_POSITIVE));
    assertNotNull(dialog.getButton(DialogInterface.BUTTON_NEGATIVE));
  }

  @Test
  public void switchRowIsDiscoverableInLayout() {
    start();
    View row = page().findViewById(R.id.screen_mode_row);
    assertNotNull(row);
    assertTrue(row.hasOnClickListeners());
    Context context = row.getContext();
    assertNotNull(context);
  }
}
