package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Actual Android intents/preferences and a controlled executor; no runtime or device launch. */
@RunWith(FirstRunWebStartTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FirstRunWebStartTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private Context context;
  private List<Runnable> queue;
  private AtomicInteger starts;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    context
        .getSharedPreferences(FirstRunWebStart.PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    queue = new ArrayList<>();
    starts = new AtomicInteger();
  }

  private void enqueue(Intent intent) {
    FirstRunWebStart.enqueue(context, intent, queue::add, starts::incrementAndGet);
  }

  @Test
  public void completionTargetsMainWithoutStartingWorkOnTheUiCaller() {
    Intent intent = FirstRunWebStart.mainIntent(context, true);
    assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
    assertTrue(intent.getBooleanExtra("open_launch", false));
    enqueue(intent);
    assertFalse(intent.hasExtra(FirstRunWebStart.EXTRA));
    assertEquals(0, starts.get());
    assertEquals(1, queue.size());
    queue.remove(0).run();
    assertEquals(1, starts.get());
  }

  @Test
  public void rotationAndRepeatedCompletionSubmitOnlyOnce() {
    for (int rotation = 0; rotation < 4; rotation++)
      enqueue(FirstRunWebStart.mainIntent(context, true));
    for (Runnable request : queue) request.run();
    assertEquals(1, starts.get());
    assertFalse(FirstRunWebStart.mainIntent(context, true).hasExtra(FirstRunWebStart.EXTRA));
  }

  @Test
  public void ordinaryOrLimitedMainEntryDoesNotArmTheInstallationStart() {
    enqueue(FirstRunWebStart.mainIntent(context, false));
    enqueue(new Intent(context, MainActivity.class));
    assertTrue(queue.isEmpty());
    assertEquals(0, starts.get());
  }

  @Test
  public void exportedIntentExtraAloneCannotCreateAnInstallationRequest() {
    enqueue(new Intent(context, MainActivity.class).putExtra(FirstRunWebStart.EXTRA, true));
    queue.remove(0).run();
    assertEquals(0, starts.get());
    enqueue(FirstRunWebStart.mainIntent(context, true));
    queue.remove(0).run();
    assertEquals(1, starts.get());
  }

  @Test
  public void explicitStopOrManualStartCancelsTheQueuedInstallationRequest() {
    enqueue(FirstRunWebStart.mainIntent(context, true));
    FirstRunWebStart.cancel(context);
    queue.remove(0).run();
    assertEquals(0, starts.get());
    assertFalse(FirstRunWebStart.mainIntent(context, true).hasExtra(FirstRunWebStart.EXTRA));
  }

  @Test
  public void sameIntentCannotEnqueueAgainWhenMainResumes() {
    Intent intent = FirstRunWebStart.mainIntent(context, true);
    enqueue(intent);
    enqueue(intent);
    assertEquals(1, queue.size());
  }

  @Test
  public void failedOrRejectedStartIsNotReplayedByReentry() {
    Intent intent = FirstRunWebStart.mainIntent(context, true);
    FirstRunWebStart.enqueue(
        context,
        intent,
        queue::add,
        () -> {
          throw new IllegalStateException("test rejection");
        });
    assertThrows(IllegalStateException.class, () -> queue.remove(0).run());
    enqueue(FirstRunWebStart.mainIntent(context, true));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void legacySettingsAndOpaqueCredentialRecordsAreUntouched() {
    var legacy =
        context.getSharedPreferences(
            com.deepseekharness.app.util.Constants.PREFS, Context.MODE_PRIVATE);
    legacy
        .edit()
        .putString(com.deepseekharness.app.util.Constants.KEY_API_KEY, "opaque-existing-record")
        .putString("model", "existing-model")
        .commit();
    var before = legacy.getAll();
    enqueue(FirstRunWebStart.mainIntent(context, true));
    queue.remove(0).run();
    assertEquals(before, legacy.getAll());
  }
}
