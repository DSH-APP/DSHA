package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.SensitiveScreenPolicy;
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

/**
 * 敏感界面设置入口（T119）：默认询问、类别可改交接、取消不落盘、说明可在短屏滚动。
 * 只验证本地策略与文案，不替代真机 LayoutAudit。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DeviceGrantsSensitiveScreenTest.App.class,
    shadows = DeviceGrantsSensitiveScreenTest.NoStorageManager.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeviceGrantsSensitiveScreenTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

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

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("dsha_device_grants", android.content.Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
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

  private TextView sensitiveRow() {
    return findText(page(), "敏感界面默认询问");
  }

  private static AlertDialog latestDialog() {
    var dialog = ShadowDialog.getLatestDialog();
    assertNotNull("expected a dialog", dialog);
    return (AlertDialog) dialog;
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

  @Test
  public void settingsEntryShowsDefaultAskAndHardBlock() {
    start();
    TextView row = sensitiveRow();
    assertNotNull("sensitive-screen entry must exist by default", row);
    assertTrue(row.getText().toString().contains("密码文本读写阻断"));
  }

  @Test
  public void dialogListsEveryCategoryAndExplainsHandoff() {
    start();
    sensitiveRow().performClick();
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog dialog = latestDialog();
    StringBuilder text = new StringBuilder();
    collect(dialog.getWindow().getDecorView(), text);
    String content = text.toString();
    for (String label : new String[] {"密码", "验证码", "支付", "系统安全", "无法识别"})
      assertTrue(label, content.contains(label));
    assertTrue(content, content.contains("暂停助手"));
    assertTrue("off must be explained separately", content.contains("不启用"));
    assertTrue(content, content.contains("不会重放"));
    assertNotNull(
        "policy content must scroll on short screens",
        findScrollable(dialog.getWindow().getDecorView()));
  }

  @Test
  public void choosingHandoffPersistsAndCancellingDoesNot() {
    start();
    sensitiveRow().performClick();
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog policyDialog = latestDialog();
    TextView payment = findText(policyDialog.getWindow().getDecorView(), "支付");
    assertNotNull(payment);
    payment.performClick();
    ShadowLooper.shadowMainLooper().idle();

    AlertDialog modeDialog = latestDialog();
    android.widget.ListView list = modeDialog.getListView();
    assertNotNull("single-choice list expected", list);
    var listener = list.getOnItemClickListener();
    assertNotNull("single-choice listener expected", listener);
    // 0 = 询问，1 = 交接
    listener.onItemClick(list, null, 1, 1);
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(
        SensitiveScreenPolicy.Mode.HANDOFF,
        new DeviceGrants(RuntimeEnvironment.getApplication())
            .sensitiveScreenPolicy()
            .mode(SensitiveScreenPolicy.Category.PAYMENT));

    // 再打开一次并选择「询问」，随后取消取消按钮不应落盘（按钮本身不提交）。
    TextView updated = findText(page(), "支付 交接");
    assertNotNull("row summary must reflect handoff", updated);
    updated.performClick();
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog reopened = latestDialog();
    TextView otp = findText(reopened.getWindow().getDecorView(), "验证码");
    assertNotNull(otp);
    otp.performClick();
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog otpDialog = latestDialog();
    otpDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(
        SensitiveScreenPolicy.Mode.ASK,
        new DeviceGrants(RuntimeEnvironment.getApplication())
            .sensitiveScreenPolicy()
            .mode(SensitiveScreenPolicy.Category.OTP));

    // 第三档「不启用」只关闭整屏策略；密码文本硬阻断仍由输入层独立执行。
    TextView paymentRow = findText(page(), "支付 交接");
    assertNotNull(paymentRow);
    paymentRow.performClick();
    ShadowLooper.shadowMainLooper().idle();
    TextView paymentAgain = findText(latestDialog().getWindow().getDecorView(), "支付");
    assertNotNull(paymentAgain);
    paymentAgain.performClick();
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog offDialog = latestDialog();
    android.widget.ListView offList = offDialog.getListView();
    var offListener = offList.getOnItemClickListener();
    assertNotNull(offListener);
    // 显示顺序固定为 询问 / 交接 / 不启用。
    offListener.onItemClick(offList, null, 2, 2);
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(
        SensitiveScreenPolicy.Mode.OFF,
        new DeviceGrants(RuntimeEnvironment.getApplication())
            .sensitiveScreenPolicy()
            .mode(SensitiveScreenPolicy.Category.PAYMENT));
    assertNotNull("row summary must reflect off", findText(page(), "支付 不启用"));
  }

  private static void collect(View view, StringBuilder out) {
    if (view instanceof TextView text) out.append(text.getText()).append('\n');
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
  }

  private static View findScrollable(View view) {
    if (view.getClass().getSimpleName().contains("ScrollView")) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findScrollable(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }
}
