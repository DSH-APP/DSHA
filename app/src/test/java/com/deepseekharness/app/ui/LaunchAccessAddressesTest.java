package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.LanAccessPresentation;
import com.deepseekharness.app.util.LanAddressPolicy;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 真实 Launch 布局、访问地址自定义弹窗与剪贴板；用合成 LAN 快照驱动，绝不真正开监听。
 *
 * <p>build209 合并后的弹窗是 {@code dialog_access_address} 自定义视图：正文在
 * {@code access_body}，三个横向按钮是 {@code access_lan_toggle / access_copy / access_close}；
 * 不再使用 AlertDialog 的 message/positive/neutral 接口。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = LaunchAccessAddressesTest.App.class,
    shadows = {
      LaunchAccessAddressesTest.Controller.class,
      LaunchAccessAddressesTest.Proxy.class,
      LaunchAccessAddressesTest.RuntimeProtection.class,
      LaunchAccessAddressesTest.NetworkAccess.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class LaunchAccessAddressesTest {
  private static final String LOCAL = "http://127.0.0.1:49123/?token=synthetic-local";
  private static final String LAN_TOKEN = "x".repeat(43);

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static ConfigStore config;
    static long generation;
    static String local;

    @Implementation
    protected static HarnessController get(Context ignored) {
      return value;
    }

    @Implementation
    protected ConfigStore config() {
      return config;
    }

    @Implementation
    protected long getWebGeneration() {
      return generation;
    }

    @Implementation
    protected String getWebAuthUrl() {
      return local;
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable ignored) {}

    @Implementation
    protected void removeReadyWebPageListener(Runnable ignored) {}
  }

  @Implements(value = LanProxyService.class, isInAndroidSdk = false)
  public static final class Proxy {
    static int starts;
    static String token;

    @Implementation
    protected static String boundLanToken(long generation) {
      return token;
    }

    @Implementation
    protected static void start(String root, Context c, int port, long generation) {
      starts++;
    }
  }

  @Implements(value = HarnessService.class, isInAndroidSdk = false)
  public static final class RuntimeProtection {
    @Implementation
    protected static void ensureLanForeground(Context ignored) {}
  }

  @Implements(value = LocalNetworkAccess.class, isInAndroidSdk = false)
  public static final class NetworkAccess {
    static int applications;

    @Implementation
    protected static void applyConfiguredFeatures(Context ignored) {
      applications++;
    }
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout root = new FrameLayout(this);
      root.setId(10002);
      setContentView(root);
    }
  }

  public static final class Entry extends LaunchFragment {
    LanAccessPresentation snapshot;

    @Override
    LanAccessPresentation lanAccess() {
      return snapshot;
    }
  }

  private org.robolectric.android.controller.ActivityController<Host> host;
  private Entry entry;

  @Before
  public void prepare() {
    UiText.setLanguage("en");
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.config = new ConfigStore(RuntimeEnvironment.getApplication());
    Controller.config.setLanMode(false);
    RuntimeEnvironment.getApplication()
        .getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(com.deepseekharness.app.util.LanPassword.PREF_KEY, "synthetic-verifier")
        .commit();
    Controller.generation = 7;
    Controller.local = LOCAL;
    Proxy.starts = 0;
    Proxy.token = LAN_TOKEN;
    NetworkAccess.applications = 0;
    host = Robolectric.buildActivity(Host.class).setup();
    entry = new Entry();
    entry.snapshot = snapshot(false, true, List.of("192.168.1.2"));
    host.get().getSupportFragmentManager().beginTransaction().add(10002, entry).commitNow();
  }

  @After
  public void close() {
    host.pause().stop().destroy();
    UiText.setLanguage("zh");
  }

  private LanAccessPresentation snapshot(
      boolean enabled, boolean permission, List<String> addresses) {
    return LanAccessPresentation.capture(
        enabled,
        permission,
        Controller.generation,
        true,
        49231,
        new LanAddressPolicy.Discovery(addresses, false, LanAddressPolicy.Failure.NONE));
  }

  private AlertDialog open() {
    // 访问地址入口已从启动页快捷卡片移到顶栏「+」气泡；气泡调用的是同一个 showAccessAddresses()。
    entry.showAccessAddresses();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull("the access dialog must be shown", dialog);
    return dialog;
  }

  private static String body(AlertDialog dialog) {
    TextView view = dialog.findViewById(R.id.access_body);
    assertNotNull("the access dialog must carry its custom body", view);
    return view.getText().toString();
  }

  private static Button lanButton(AlertDialog dialog) {
    Button button = dialog.findViewById(R.id.access_lan_toggle);
    assertNotNull(button);
    return button;
  }

  private static Button copyButton(AlertDialog dialog) {
    Button button = dialog.findViewById(R.id.access_copy);
    assertNotNull(button);
    return button;
  }

  private static void click(Button button) {
    assertTrue(button.performClick());
    idle();
  }

  private static void clickDialogButton(AlertDialog dialog, int button) {
    assertTrue("the tested dialog must be visible", dialog.isShowing());
    assertTrue(dialog.getButton(button).performClick());
    idle();
  }

  private static void idle() {
    ShadowLooper shadow = org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper());
    shadow.idle();
  }

  private static String message(AlertDialog dialog) {
    return ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
  }

  private ClipboardManager clipboard() {
    return (ClipboardManager) host.get().getSystemService(Context.CLIPBOARD_SERVICE);
  }

  @Test
  public void disabledLanIsShownWithExplicitEnableAndOpeningDoesNotExposeIt() {
    AlertDialog dialog = open();
    String shown = body(dialog);
    assertTrue(shown.contains(LOCAL));
    assertTrue(shown.contains("LAN access: off"));
    assertFalse("overview must not expose the LAN token", shown.contains(LAN_TOKEN));
    assertEquals("Enable LAN", lanButton(dialog).getText().toString());
    assertEquals("Copy local URL", copyButton(dialog).getText().toString());
    assertTrue(copyButton(dialog).isEnabled());
    assertFalse(Controller.config.isLanMode());
    assertEquals(0, Proxy.starts);
    click(lanButton(dialog));
    AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    assertTrue(message(confirmation).contains("unencrypted"));
    assertFalse("showing confirmation cannot enable LAN", Controller.config.isLanMode());
    clickDialogButton(confirmation, DialogInterface.BUTTON_NEGATIVE);
    assertFalse(Controller.config.isLanMode());
    assertEquals(0, Proxy.starts);
  }

  @Test
  public void readyLanUrlsAreListedAndCopyCarriesTheTokenOnlyInClipboard() {
    Controller.config.setLanMode(true);
    entry.snapshot = snapshot(true, true, List.of("192.168.1.2", "10.2.0.2"));
    AlertDialog dialog = open();
    String shown = body(dialog);
    assertTrue(shown.contains("http://192.168.1.2:49231/"));
    assertTrue(shown.contains("http://10.2.0.2:49231/"));
    assertFalse("overview must not expose the LAN token", shown.contains(LAN_TOKEN));
    assertEquals("Disable LAN", lanButton(dialog).getText().toString());
    assertEquals("Copy LAN link", copyButton(dialog).getText().toString());
    assertTrue(copyButton(dialog).isEnabled());
    click(copyButton(dialog));
    assertEquals(
        "http://192.168.1.2:49231/?token=" + LAN_TOKEN,
        clipboard().getPrimaryClip().getItemAt(0).getText().toString());
    assertEquals(0, Proxy.starts);
  }

  @Test
  public void explicitEnableConfirmationAppliesConfiguredNetworkFeaturesOnce() {
    AlertDialog dialog = open();
    assertEquals(0, NetworkAccess.applications);
    click(lanButton(dialog));
    AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    assertFalse(Controller.config.isLanMode());
    assertTrue("confirmation applies only to the visible resumed launch page", entry.isResumed());
    clickDialogButton(confirmation, DialogInterface.BUTTON_POSITIVE);
    assertTrue(Controller.config.isLanMode());
    assertEquals(1, NetworkAccess.applications);
  }

  @Test
  public void generationChangeBlocksTheLanCopy() {
    Controller.config.setLanMode(true);
    entry.snapshot = snapshot(true, true, List.of("192.168.1.2"));
    AlertDialog dialog = open();
    Controller.generation = 8;
    click(copyButton(dialog));
    assertFalse("changed connection must not be copied", clipboard().hasPrimaryClip());
  }

  @Test
  public void missingPasswordIsShownAndDoesNotExposeAnAddress() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(com.deepseekharness.app.util.LanPassword.PREF_KEY)
        .commit();
    entry.snapshot = snapshot(true, true, List.of("192.168.1.2"));
    AlertDialog dialog = open();
    String shown = body(dialog);
    assertTrue(shown.contains("No LAN password"));
    assertFalse(shown.contains("http://192.168.1.2"));
    assertEquals("Enable LAN", lanButton(dialog).getText().toString());
  }

  @Test
  public void permissionFailureIsShownAndMissingLocalUrlDisablesCopy() {
    Controller.local = "";
    entry.snapshot = snapshot(true, false, List.of("192.168.1.2"));
    AlertDialog dialog = open();
    String shown = body(dialog);
    assertTrue(shown.contains("permission is missing"));
    assertFalse(shown.contains("http://192.168"));
    assertFalse(copyButton(dialog).isEnabled());
    assertEquals("Enable LAN", lanButton(dialog).getText().toString());
  }

  @Test
  public void localCopyIsAvailableOnlyWhenALocalAddressExists() {
    AlertDialog dialog = open();
    assertEquals("Copy local URL", copyButton(dialog).getText().toString());
    click(copyButton(dialog));
    assertEquals(LOCAL, clipboard().getPrimaryClip().getItemAt(0).getText().toString());
    clipboard().clearPrimaryClip();
    Controller.local = "";
    entry.snapshot = snapshot(false, true, List.of());
    AlertDialog second = open();
    assertFalse(copyButton(second).isEnabled());
    click(copyButton(second));
    assertFalse(clipboard().hasPrimaryClip());
  }
}
