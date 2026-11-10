package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
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

/** 真Main导航/保存页面测试；仅隔离磁盘/guest/网络端口，不启动真实shell。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = TerminalNavigationTest.App.class,
    shadows = {
      TerminalNavigationTest.Controller.class,
      TerminalNavigationTest.Environment.class,
      TerminalNavigationTest.TerminalGate.class,
      TerminalNavigationTest.PtyShutdown.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalNavigationTest {
  public static class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static class Controller {
    static HarnessController value;

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {}
  }

  @Implements(value = com.deepseekharness.app.core.EnvironmentAccess.class, isInAndroidSdk = false)
  public static class Environment {
    static boolean recovery;

    @Implementation
    protected static boolean needsRecovery(HarnessController controller) {
      return recovery;
    }
  }

  @Implements(value = TerminalFragment.class, isInAndroidSdk = false)
  public static class TerminalGate {
    static int shutdown;

    @Implementation
    protected static String terminalBlockMessage(ProotBootstrap proot) {
      return "";
    }

    @Implementation
    protected static void shutdownShell() {
      shutdown++;
    }
  }

  @Implements(value = PtyTerminalFragment.class, isInAndroidSdk = false)
  public static class PtyShutdown {
    static int shutdown;

    @Implementation
    protected static void shutdown() {
      shutdown++;
    }
  }

  public static class SavedPage extends Fragment {
    public SavedPage() {
      super(android.R.layout.simple_list_item_1);
    }
  }

  @Before
  public void prepare() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setWelcomed(true);
    config.setCheckUpdate(false);
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Environment.recovery = false;
    TerminalGate.shutdown = PtyShutdown.shutdown = 0;
  }

  @Test
  public void repeatedTerminalOpenKeepsSourceFragmentAndUsesSameTask() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    MainActivity main = host.get();
    ready(main);
    SavedPage page = new SavedPage();
    main.getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, page)
        .addToBackStack("draft")
        .commit();
    main.getSupportFragmentManager().executePendingTransactions();
    var view = page.requireView();
    main.openTerminal();
    main.openTerminal();
    var shadow = activityShadow(main);
    Intent terminal = shadow.getNextStartedActivity();
    assertNotNull(terminal);
    assertEquals(TerminalActivity.class.getName(), terminal.getComponent().getClassName());
    assertEquals(
        0,
        terminal.getFlags()
            & (Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
    assertNull(shadow.getNextStartedActivity());
    assertSame(page, main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
    assertSame(view, page.requireView());
    assertEquals(1, main.getSupportFragmentManager().getBackStackEntryCount());
    host.pause().stop().destroy();
    assertEquals(0, TerminalGate.shutdown + PtyShutdown.shutdown);
  }

  @Test
  public void terminalNotificationIntentIsConsumedOnlyOnce() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    MainActivity main = host.get();
    ready(main);
    Intent intent = main.getIntent().putExtra("open_terminal", true);
    var consume = MainActivity.class.getDeclaredMethod("consumeTaskTarget", Intent.class);
    consume.setAccessible(true);
    consume.invoke(main, intent);
    consume.invoke(main, intent);
    assertFalse(intent.hasExtra("open_terminal"));
    assertNotNull(activityShadow(main).getNextStartedActivity());
    assertNull(activityShadow(main).getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void entryRecoveryDoesNotStartTerminalOrClaimPageTicket() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    MainActivity main = host.get();
    ready(main);
    Environment.recovery = true;
    main.openTerminal();
    Intent destination = activityShadow(main).getNextStartedActivity();
    assertEquals(
        StartupRecoveryActivity.class.getName(), destination.getComponent().getClassName());
    assertFalse(field("terminalPageInFlight").getBoolean(main));
    host.pause().stop().destroy();
  }

  @Test
  public void ordinaryMainDestructionNeverEndsSessions() {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    host.get().finish();
    host.pause().stop().destroy();
    assertEquals(0, TerminalGate.shutdown);
    assertEquals(0, PtyShutdown.shutdown);
  }

  private static org.robolectric.shadows.ShadowActivity activityShadow(MainActivity main) {
    return org.robolectric.shadow.api.Shadow.extract(main);
  }

  private static java.lang.reflect.Field field(String name) throws Exception {
    var field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void ready(MainActivity main) throws Exception {
    field("mainReady").setBoolean(main, true);
  }
}
