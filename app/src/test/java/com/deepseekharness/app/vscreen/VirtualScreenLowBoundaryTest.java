package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import java.io.File;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Unsupported managers still serve lifecycle callbacks without loading an NIO/display owner. */
@RunWith(VirtualScreenLowBoundaryTest.NoApkRunner.class)
@Config(manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenLowBoundaryTest {
  /** Pure code boundary fixture: do not install Standard's minSdk30 merged test APK on SDK23. */
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> testClass) throws org.junit.runners.model.InitializationError {
      super(testClass);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Test
  @Config(sdk = 23)
  public void api23StartupStopRevocationAndInformationNeverOpenTheVscreenJournal()
      throws Exception {
    assertUnsupportedBoundary();
  }

  @Test
  @Config(sdk = 34)
  public void lowFlavorRemainsUnsupportedEvenOnAModernAndroidVersion() throws Exception {
    assumeTrue(BuildConfig.LOW_ANDROID);
    assertUnsupportedBoundary();
  }

  private void assertUnsupportedBoundary() throws Exception {
    Context context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public File getFilesDir() {
            throw new AssertionError(
                "Unsupported virtual screen must not open an installation or journal file");
          }
        };
    ScheduledThreadPoolExecutor worker =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public void execute(Runnable command) {
            command.run();
          }
        };
    try {
      VirtualScreenManager manager =
          new VirtualScreenManager(
              context,
              () -> 100L,
              null,
              new VirtualScreenManager.Channels() {
                public VirtualScreenChannel choose(Context ignored) {
                  throw new AssertionError("No channel discovery");
                }

                public VirtualScreenChannel find(String ignored) {
                  throw new AssertionError("No cleanup channel");
                }
              },
              () -> {
                throw new AssertionError("No journal restore boot read");
              },
              worker);
      assertNull(field(manager, "journal"));
      assertEquals(VirtualScreenLifecycleState.STOPPED, manager.lifecycleState());
      assertFalse(manager.status().optBoolean("ok"));
      assertFalse(manager.targetStatus().optBoolean("ok"));
      manager.discardObservations();
      manager.stop();
      manager.revoke();
      manager.stop();
      assertEquals(VirtualScreenLifecycleState.STOPPED, manager.lifecycleState());
      assertNull(field(manager, "journal"));
      assertNull(field(manager, "core"));
      assertNull(field(manager, "pendingCleanup"));
      VirtualScreenAccessibility accessibility = new VirtualScreenAccessibility();
      JSONObject denied =
          accessibility.observedControl(null, 1, "see", new JSONObject(), null, null);
      assertFalse(denied.optBoolean("ok"));
      assertTrue(denied.optString("error").startsWith("VSCREEN_"));
      accessibility.invalidateObserved();
      accessibility.clearObserved();
      accessibility.clear();
      assertNull(field(accessibility, "observed"));
    } finally {
      worker.shutdownNow();
    }
  }

  private static Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }
}
