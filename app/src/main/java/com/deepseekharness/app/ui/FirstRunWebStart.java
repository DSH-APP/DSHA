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
    var activity = ForegroundActivity.current();
    if (!ForegroundActivity.isResumed(activity)) return;
    Context app = context.getApplicationContext();
    HarnessController controller = HarnessController.get(app);
    com.deepseekharness.app.util.WebStartProtection.Ticket ticket;
    try {
      ticket = HarnessService.prepareVisibleStart(activity);
    } catch (java.io.IOException unavailable) {
      protectionFailure(app, null, unavailable);
      return;
    }
    try {
      enqueue(
          app,
          intent,
          request ->
              IO.execute(
                  () -> {
                    try {
                      ticket.awaitPromotion();
                      request.run();
                    } catch (java.io.IOException | RuntimeException failure) {
                      protectionFailure(app, null, failure);
                    } finally {
                      HarnessService.finishVisibleStart(app, ticket);
                    }
                  }),
          () -> {
            // Installation itself stops the old runtime. This is the user's one setup request;
            // the generation fence still rejects it if a later explicit Stop wins.
            controller.startProtectedWeb(ticket, false, false, null);
          });
    } catch (RuntimeException rejected) {
      HarnessService.finishVisibleStart(app, ticket);
      protectionFailure(app, null, rejected);
    }
  }

  static boolean enqueueVisible(
      androidx.fragment.app.FragmentActivity activity,
      Executor executor,
      boolean safe,
      boolean recover,
      java.util.function.Consumer<String> status,
      Runnable completed) {
    Context app = activity.getApplicationContext();
    com.deepseekharness.app.util.WebStartProtection.Ticket ticket;
    try {
      // This call is synchronous in the visible click's main-thread frame.
      ticket = HarnessService.prepareVisibleStart(activity);
    } catch (java.io.IOException unavailable) {
      protectionFailure(app, status, unavailable);
      return false;
    }
    try {
      executor.execute(
          () -> {
            boolean accepted = false;
            try {
              ticket.awaitPromotion();
              accepted =
                  HarnessController.get(app).startProtectedWeb(ticket, safe, recover, status);
            } catch (java.io.IOException | RuntimeException unavailable) {
              protectionFailure(app, status, unavailable);
            } finally {
              // Recovery retains this ticket across its own Stop; its controller continuation
              // owns cleanup. An ordinary accepted start has already handed off atomically.
              if (!accepted || !recover) HarnessService.finishVisibleStart(app, ticket);
              if (completed != null) MAIN.post(completed);
            }
          });
      return true;
    } catch (RuntimeException rejected) {
      HarnessService.finishVisibleStart(app, ticket);
      protectionFailure(app, status, rejected);
      return false;
    }
  }

  static boolean enqueueVisible(
      androidx.fragment.app.FragmentActivity activity,
      boolean safe,
      boolean recover,
      java.util.function.Consumer<String> status) {
    return enqueueVisible(activity, IO, safe, recover, status, null);
  }

  private static void protectionFailure(
      Context context, java.util.function.Consumer<String> status, Exception failure) {
    com.deepseekharness.app.core.DiagnosticLog.record(
        context,
        "WEB_START_PROTECTION",
        failure.getClass().getSimpleName() + ":" + failure.getMessage());
    if (status != null)
      status.accept(
          com.deepseekharness.app.util.UiText.choose(
              "后台启动保护未能建立，请保持应用在前台后重试。",
              "Background startup protection could not be established. Keep the app visible and retry."));
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
