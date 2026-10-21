package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import java.util.ArrayList;
import java.util.List;
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

/** 实际Main/FragmentManager旋转恢复，后台核对被刻意延后；不运行guest或模型请求。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = MainEntryLayoutLifecycleTest.App.class,
    shadows = MainEntryLayoutLifecycleTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainEntryLayoutLifecycleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static final List<Runnable> checks = new ArrayList<>();

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) checks.add(completed);
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return false;
    }
  }

  public static final class SavedPage extends Fragment {
    public SavedPage() {
      super(android.R.layout.simple_list_item_1);
    }
  }

  @Before
  public void prepare() {
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.checks.clear();
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
  }

  @Test
  public void pendingCheckKeepsRealContainerAndRotatedFragmentAttached() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var first = host.get();
    assertNotNull(first.findViewById(R.id.fragment_container));
    assertFalse(Controller.checks.isEmpty());
    first
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SavedPage(), "saved-page")
        .commitNow();
    assertSame(
        first.findViewById(R.id.fragment_container),
        first
            .getSupportFragmentManager()
            .findFragmentByTag("saved-page")
            .requireView()
            .getParent());
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume();
    var restored = restoredHost.get();
    Fragment page = restored.getSupportFragmentManager().findFragmentByTag("saved-page");
    assertNotNull(page);
    assertNull("核对未完成不能重新创建旧终端等页面", page.getView());
    var release = MainActivity.class.getDeclaredMethod("releaseGatedPages", boolean.class);
    release.setAccessible(true);
    release.invoke(restored, true);
    assertNotNull(page.getView());
    assertSame(restored.findViewById(R.id.fragment_container), page.requireView().getParent());
    assertNotNull(restored.findViewById(R.id.bottom_nav));
    restoredHost.pause().stop().destroy();
  }
}
