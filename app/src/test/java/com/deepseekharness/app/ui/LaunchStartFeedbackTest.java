package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Exercises the real launch controls with a deterministic startup dispatcher. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = MainEntryLayoutLifecycleTest.App.class,
    shadows = {
      MainEntryLayoutLifecycleTest.Controller.class,
      MainEntryLayoutLifecycleTest.Environment.class,
      com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class LaunchStartFeedbackTest {
  public static final class Host extends FragmentActivity {
    @Override
    protected void onCreate(android.os.Bundle state) {
      super.onCreate(state);
      FrameLayout root = new FrameLayout(this);
      root.setId(12345);
      setContentView(root);
    }
  }

  public static final class Probe extends LaunchFragment {
    final List<Consumer<String>> statuses = new ArrayList<>();
    final List<Consumer<Boolean>> completions = new ArrayList<>();
    boolean synchronousFailure;

    @Override
    boolean enqueueStart(
        FragmentActivity activity,
        boolean safe,
        Consumer<String> status,
        Consumer<Boolean> completed) {
      statuses.add(status);
      completions.add(completed);
      if (synchronousFailure) {
        status.accept("foreground protection unavailable");
        return false;
      }
      return true;
    }
  }

  @Before
  public void prepare() {
    Context app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    MainEntryLayoutLifecycleTest.Controller.value = new HarnessController(app);
    MainEntryLayoutLifecycleTest.Controller.unresolved = false;
    MainEntryLayoutLifecycleTest.Controller.stopRequests = 0;
  }

  @Test
  public void rejectionRemainsVisibleAndCanBeRetried() {
    var host = Robolectric.buildActivity(Host.class).setup();
    Probe page = new Probe();
    host.get().getSupportFragmentManager().beginTransaction().add(12345, page).commitNow();
    page.synchronousFailure = true;
    page.requireView().findViewById(R.id.launch_start).performClick();
    assertStatus(page, "foreground protection unavailable");
    assertTrue(page.requireView().findViewById(R.id.launch_start).isEnabled());

    page.synchronousFailure = false;
    page.requireView().findViewById(R.id.launch_start).performClick();
    page.statuses.get(1).accept("environment task is busy");
    page.completions.get(1).accept(false);
    assertStatus(page, "environment task is busy");
    assertTrue(page.requireView().findViewById(R.id.launch_start).isEnabled());
    host.pause().stop().destroy();
  }

  @Test
  public void stoppedRequestCannotOverwriteNewAttemptAndSilentRejectHasFeedback() {
    var host = Robolectric.buildActivity(Host.class).setup();
    Probe page = new Probe();
    host.get().getSupportFragmentManager().beginTransaction().add(12345, page).commitNow();
    page.requireView().findViewById(R.id.launch_start).performClick();
    page.requireView().findViewById(R.id.launch_stop).performClick();
    page.requireView().findViewById(R.id.launch_start).performClick();
    page.statuses.get(0).accept("old failure");
    page.completions.get(0).accept(false);
    page.statuses.get(1).accept("current failure");
    page.completions.get(1).accept(false);
    assertStatus(page, "current failure");
    assertEquals(1, MainEntryLayoutLifecycleTest.Controller.stopRequests);

    page.requireView().findViewById(R.id.launch_start).performClick();
    page.completions.get(2).accept(false);
    TextView status = page.requireView().findViewById(R.id.launch_status);
    assertEquals(android.view.View.VISIBLE, status.getVisibility());
    assertFalse(status.getText().toString().isEmpty());
    assertNotEquals("current failure", status.getText().toString());
    host.pause().stop().destroy();
  }

  private static void assertStatus(Probe page, String expected) {
    TextView status = page.requireView().findViewById(R.id.launch_status);
    assertEquals(android.view.View.VISIBLE, status.getVisibility());
    assertEquals(expected, status.getText().toString());
  }
}
