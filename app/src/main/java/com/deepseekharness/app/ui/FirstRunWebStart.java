package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.core.HarnessController;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** First installation hands off to Main before requesting the normal fenced Web start. */
final class FirstRunWebStart {
  static final String EXTRA = "start_web_after_setup";
  static final String PREFS = "dsha-first-setup-web";
  private static final String PENDING = "pending";
  private static final String SUBMITTED = "submitted";
  private static final Executor IO = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private FirstRunWebStart() {}

  static Intent mainIntent(Context context, boolean firstSetup) {
    Intent intent = new Intent(context, MainActivity.class).putExtra("open_launch", true);
    if (firstSetup) {
      synchronized (FirstRunWebStart.class) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SUBMITTED, false)) {
          prefs.edit().putBoolean(PENDING, true).apply();
          intent.putExtra(EXTRA, true);
        }
      }
    }
    return intent;
  }

  static void schedule(Context context, Intent intent) {
    if (intent == null || !intent.getBooleanExtra(EXTRA, false)) return;
    Context app = context.getApplicationContext();
    HarnessController controller = HarnessController.get(app);
    long generation = controller.getWebGeneration();
    enqueue(
        app,
        intent,
        request ->
            IO.execute(
                () -> {
                  try {
                    request.run();
                  } catch (RuntimeException failure) {
                    com.deepseekharness.app.core.DiagnosticLog.record(
                        app, "FIRST_SETUP_START", failure.getClass().getSimpleName());
                  }
                }),
        () -> {
          // Installation itself stops the old runtime. This is the user's one setup request;
          // the generation fence still rejects it if a later explicit Stop wins.
          if (controller.startWebIfUnchanged(generation, true, null)) protectVisibleRuntime();
        });
  }

  static void protectVisibleRuntime() {
    MAIN.post(
        () -> {
          var activity = ForegroundActivity.current();
          if (activity != null
              && !activity.isFinishing()
              && !activity.isDestroyed()
              && activity
                  .getLifecycle()
                  .getCurrentState()
                  .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            HarnessService.ensureRuntimeForeground(activity);
        });
  }

  static void enqueue(Context context, Intent intent, Executor executor, Runnable request) {
    if (intent == null || !intent.getBooleanExtra(EXTRA, false)) return;
    intent.removeExtra(EXTRA);
    executor.execute(
        () -> {
          synchronized (FirstRunWebStart.class) {
            var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(PENDING, false) || prefs.getBoolean(SUBMITTED, false)) return;
            if (!prefs.edit().putBoolean(PENDING, false).putBoolean(SUBMITTED, true).commit())
              return;
          }
          request.run();
        });
  }

  /** An explicit manual start/stop supersedes an installation request still in the queue. */
  static void cancel(Context context) {
    var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    if (prefs.getBoolean(PENDING, false))
      prefs.edit().putBoolean(PENDING, false).putBoolean(SUBMITTED, true).apply();
  }
}
