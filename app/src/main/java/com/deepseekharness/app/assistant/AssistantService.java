package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.control.ControlTaskAuthority;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Keeps local conversation status, native confirmations, and Stop available during phone tools. */
public final class AssistantService extends Service {
  private record Pending(
      AssistantBinding binding,
      AssistantBackend backend,
      Runnable ready,
      String verifiedTurn,
      long expiresAt,
      ControlTaskAuthority.Binding nativeTask) {}

  public static final String CONTROL_PERMISSION =
      "com.deepseekharness.app.assistant.CONTROL_PERMISSION";
  static final String STOP_CHANNEL = "floating-assistant";

  public record TaskUiResult(boolean ready, String code) {
    public String failureReply() {
      try {
        JSONObject reply =
            com.deepseekharness.app.screen.ScreenReply.refused(code)
                .put("guidance", taskGuidance(code))
                .put("automaticRetry", false);
        if ("ASSISTANT_STOP_NOTIFICATION_REQUIRED".equals(code))
          reply
              .put("nextAction", "OPEN_NOTIFICATION_SETTINGS")
              .put("settingsAction", "android.settings.APP_NOTIFICATION_SETTINGS")
              .put("settingsChannel", STOP_CHANNEL);
        return reply.toString();
      } catch (org.json.JSONException impossible) {
        return "[ERR] " + code;
      }
    }
  }

  private static final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private AssistantSessionController controller;
  private volatile AssistantWindow window;
  private AssistantConfirmationWindow confirmations;
  static final String REPLY_KEY = "dsha_assistant_reply";
  private static volatile AssistantService live;
  private PendingIntent stopAction;
  private Notification taskNotification;
  private volatile boolean stopAccessArmed;
  private final Runnable stopAccessChanged =
      () -> {
        var task = PhoneControlCoordinator.get(this).snapshot().binding();
        if (controller != null && task != null && controller.ownsTask(task))
          stopChannelCurrent(this, task);
      };

  static void nativeEditorVisible(boolean visible) {
    AssistantService service = live;
    if (service != null && service.window != null) service.window.setEditorVisible(visible);
  }

  /** Called only by an authenticated physical-phone tool request, never a text listener. */
  public static TaskUiResult ensureLocalTask(Context context, ControlTaskAuthority.Binding task) {
    if (task == null || task.owner() != ControlTaskAuthority.Owner.LOCAL_DSH)
      return refused("ASSISTANT_TASK_CHANGED");
    if (!stopPrerequisites(context, task)) return refused("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
    try {
      PhoneControlCoordinator.get(context).checkBegin(task);
    } catch (IllegalStateException unavailable) {
      return refused(unavailable.getMessage());
    }
    try {
      LocalAssistantBackend backend = new LocalAssistantBackend(context);
      if (!backend.ownsControlBinding(task)) return refused("ASSISTANT_TASK_CHANGED");
      // Discover this exact running Host, not a last-active/global conversation.
      String host = backend.call("sessions.list", new JSONObject()).getString("hostInstanceId");
      AssistantBinding binding =
          new AssistantBinding(
              host,
              task.taskSessionId(),
              "local-" + backend.generation + "-" + host,
              backend.generation);
      return ensureTask(context, binding, task.turnId(), backend, task);
    } catch (Exception unavailable) {
      return refused("ASSISTANT_TASK_UNAVAILABLE");
    }
  }

  static TaskUiResult ensureTask(
      Context context, AssistantBinding binding, String turnId, AssistantBackend backend) {
    return ensureTask(context, binding, turnId, backend, null);
  }

  private static TaskUiResult ensureTask(
      Context context,
      AssistantBinding binding,
      String turnId,
      AssistantBackend backend,
      ControlTaskAuthority.Binding nativeTask) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
      return refused("ASSISTANT_UI_WORKER_REQUIRED");
    try {
      if (!backend.current()) return refused("ASSISTANT_TASK_CHANGED");
      JSONObject status =
          backend.call(
              "sessions.status",
              new JSONObject()
                  .put("hostInstanceId", binding.hostInstanceId())
                  .put("sessionId", binding.sessionId()));
      AssistantTaskAttachment.verify(binding, turnId, status);
      if (!backend.current()) return refused("ASSISTANT_TASK_CHANGED");
    } catch (Exception unavailable) {
      return refused("ASSISTANT_TASK_CHANGED");
    }
    if (!stopPrerequisites(context, nativeTask))
      return refused("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
    if (!android.provider.Settings.canDrawOverlays(context)) {
      offerOverlayPermission(context);
      return refused("ASSISTANT_OVERLAY_PERMISSION_REQUIRED");
    }
    var screen = PhoneControlCoordinator.get(context).snapshot();
    if (screen.paused()) return refused("ASSISTANT_LOCAL_RESUME_REQUIRED");
    if (screen.binding() != null
        && (!backend.ownsControlBinding(screen.binding())
            || !binding.sessionId().equals(screen.binding().taskSessionId())
            || !turnId.equals(screen.binding().turnId()))) return refused("PHYSICAL_SCREEN_BUSY");
    var current = AssistantSessionController.active();
    if (!AssistantTaskAttachment.mayReplace(current == null ? null : current.snapshot(), binding))
      return refused("ASSISTANT_CONVERSATION_BUSY");

    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<TaskUiResult> outcome =
        new AtomicReference<>(refused("ASSISTANT_UI_UNAVAILABLE"));
    long expiresAt = SystemClock.elapsedRealtime() + 3000;
    String key = UUID.randomUUID().toString();
    pending.put(
        key,
        new Pending(
            binding,
            backend,
            () -> {
              if (matchesTask(AssistantSessionController.active(), binding, turnId)
                  && live != null
                  && live.window != null
                  && stopNotificationAvailable(context)
                  && live.window.taskUiAvailable()) outcome.set(new TaskUiResult(true, "READY"));
              ready.countDown();
            },
            turnId,
            expiresAt,
            nativeTask));
    try {
      startBinding(context, key);
      if (ready.await(3, TimeUnit.SECONDS)) {
        if (outcome.get().ready()) {
          // FGS notifications may be posted asynchronously. This only waits
          // for setup evidence; no phone action or prompt is retried.
          long postedBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
          while (stopNotificationAvailable(context)
              && !postedStopNotification(context)
              && System.nanoTime() < postedBy) Thread.sleep(10);
          AssistantService service = live;
          if (service != null) service.stopAccessArmed = true;
        }
        if (!stopChannelCurrent(context, nativeTask)
            || (outcome.get().ready() && !postedStopNotification(context)))
          return refused("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
        return outcome.get();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException unavailable) {
      return refused("ASSISTANT_UI_UNAVAILABLE");
    } finally {
      pending.remove(key);
    }
    return refused("ASSISTANT_UI_UNAVAILABLE");
  }

  private static TaskUiResult refused(String code) {
    return new TaskUiResult(false, code);
  }

  /** The task's native stop action must really be available in the notification shade. */
  public static boolean stopNotificationAvailable(Context context) {
    try {
      if (Build.VERSION.SDK_INT >= 33
          && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
              != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
      if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        return false;
      if (Build.VERSION.SDK_INT >= 26) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(STOP_CHANNEL);
        if (channel == null) {
          manager.createNotificationChannel(
              new NotificationChannel(
                  STOP_CHANNEL,
                  UiText.choose("手机任务状态", "Phone task status"),
                  NotificationManager.IMPORTANCE_LOW));
          channel = manager.getNotificationChannel(STOP_CHANNEL);
        }
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
          return false;
        if (Build.VERSION.SDK_INT >= 28 && channel.getGroup() != null) {
          var group = manager.getNotificationChannelGroup(channel.getGroup());
          if (group == null || group.isBlocked()) return false;
        }
      }
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static boolean postedStopNotification(Context context) {
    AssistantService service = live;
    if (service == null || service.stopAction == null) return false;
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return false;
      for (var posted : manager.getActiveNotifications()) {
        Notification notification = posted.getNotification();
        if (posted.getId() != com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT
            || !context.getPackageName().equals(posted.getPackageName())
            || (Build.VERSION.SDK_INT >= 26 && !STOP_CHANNEL.equals(notification.getChannelId()))
            || notification.actions == null) continue;
        for (Notification.Action action : notification.actions)
          if (service.stopAction.equals(action.actionIntent)) return true;
      }
    } catch (RuntimeException unavailable) {
      return false;
    }
    return false;
  }

  private static boolean stopPrerequisites(Context context, ControlTaskAuthority.Binding task) {
    if (task != null && task.equals(PhoneControlCoordinator.get(context).snapshot().binding()))
      return stopChannelCurrent(context, task);
    return stopNotificationAvailable(context);
  }

  /** Also used in final input validity checks, so revocation while confirming cannot admit input. */
  public static boolean stopChannelCurrent(Context context, ControlTaskAuthority.Binding task) {
    AssistantService current = live;
    boolean postedRequired =
        current != null
            && current.stopAccessArmed
            && task != null
            && current.controller != null
            && current.controller.ownsTask(task);
    if (stopNotificationAvailable(context) && (!postedRequired || postedStopNotification(context)))
      return true;
    if (task != null) {
      var coordinator = PhoneControlCoordinator.get(context);
      var state = coordinator.snapshot();
      if (task.equals(state.binding())
          && (!state.paused() || !"ASSISTANT_STOP_NOTIFICATION_REQUIRED".equals(state.reason())))
        coordinator.pause("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
      Runnable restore =
          () -> {
            AssistantService service = live;
            if (service != null
                && service.controller != null
                && service.window != null
                && service.controller.ownsTask(task)) service.window.restoreStopControls();
          };
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) restore.run();
      else new android.os.Handler(android.os.Looper.getMainLooper()).post(restore);
    }
    return false;
  }

  public static Intent notificationSettingsIntent(Context context) {
    if (Build.VERSION.SDK_INT >= 26)
      return new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.getPackageName())
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return new Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + context.getPackageName()))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  /** Hide and fence before capture. An input never silently retries after a visibility change. */
  public static TaskUiResult prepareScreenOperation(
      Context context, ControlTaskAuthority.Binding task, boolean observing) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
      return refused("ASSISTANT_UI_WORKER_REQUIRED");
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<TaskUiResult> outcome =
        new AtomicReference<>(refused("ASSISTANT_UI_UNAVAILABLE"));
    long expiresAt = SystemClock.elapsedRealtime() + 2000;
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () -> {
              try {
                if (SystemClock.elapsedRealtime() >= expiresAt) return;
                AssistantService service = live;
                if (service == null
                    || service.window == null
                    || service.controller == null
                    || !service.controller.ownsTask(task)) return;
                if (!stopChannelCurrent(context, task)) {
                  outcome.set(refused("ASSISTANT_STOP_NOTIFICATION_REQUIRED"));
                  return;
                }
                var coordinator = PhoneControlCoordinator.get(context);
                if (coordinator.snapshot().paused()) {
                  outcome.set(refused("ASSISTANT_LOCAL_RESUME_REQUIRED"));
                  return;
                }
                // 用户正在看展开的面板：不收走它。面板是不可聚焦的自有窗口，读屏时整窗排除，
                // 坐标动作也不会落在它上面；几何变化已在展开/收起时要求重新观察。
                if (service.window.keptVisibleForUser()) {
                  outcome.set(new TaskUiResult(true, "READY"));
                  return;
                }
                boolean changed = service.window.visible();
                if (changed) coordinator.requireObservation(task);
                service.window.hideForScreen();
                outcome.set(
                    changed && !observing
                        ? refused("FRESH_SCREEN_OBSERVATION_REQUIRED")
                        : new TaskUiResult(true, "READY"));
              } catch (RuntimeException unavailable) {
                outcome.set(refused("ASSISTANT_UI_UNAVAILABLE"));
              } finally {
                ready.countDown();
              }
            });
    try {
      if (ready.await(2, TimeUnit.SECONDS)) return outcome.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return refused("ASSISTANT_UI_UNAVAILABLE");
  }

  /**
   * A canceled turn may still have a user-visible local pause. Keep that exact task attached so
   * the user can press Continue; a normal completed turn remains eligible for retirement.
   */
  private static boolean retainLocalTaskAfterTurnEnd(ControlTaskAuthority.Binding task) {
    AssistantService service = live;
    if (service == null || service.controller == null || !service.controller.ownsTask(task))
      return false;
    var state = service.controller.snapshot();
    return state.paused()
        && state.phase() != AssistantSessionState.Phase.STOPPING
        && state.phase() != AssistantSessionState.Phase.STOPPED;
  }

  /** Called only after the exact local task completion has fenced the native authority. */
  public static void finishLocalTask(Context context, ControlTaskAuthority.Binding task) {
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () -> {
              AssistantService service = live;
              if (service == null
                  || service.controller == null
                  || !service.controller.ownsTask(task)) return;
              // Recheck on the UI thread: expansion may race the turn-end callback after the
              // worker-side decision. A user-visible pause must never be closed by stale cleanup.
              if (retainLocalTaskAfterTurnEnd(task)) return;
              if (service.controller.snapshot().expanded()) {
                // 面板展开时本轮结束：等用户收起后再关窗，窗口不在手指下消失。
                service.controller.deferFinishUntilCollapsed(() -> finishLocalTask(context, task));
                return;
              }
              if (service.window != null) service.window.close();
              service.controller.detachFinishedTask();
              service.stopSelf();
            });
  }

  public static String taskGuidance(String code) {
    return switch (code) {
      case "USER_MESSAGE_ARRIVED" ->
          UiText.choose(
              "用户刚给你发来新消息，这次手机动作没有执行。先阅读这条消息，再重新观察屏幕，按用户的新要求决定下一步；不要重放刚才的动作。",
              "The user just sent you a new message, so this phone action was not executed. Read the message first, observe the screen again, then decide the next step from the user's new request. Do not replay the previous action.");
      case "USER_COMPOSING" ->
          UiText.choose(
              "用户正在给你输入消息，这次手机动作没有执行。不要继续操作手机，等待用户的消息；本轮对话没有被取消。",
              "The user is typing a message to you, so this phone action was not executed. Do not operate the phone; wait for the user's message. This turn has not been cancelled.");
      case "ASSISTANT_STOP_NOTIFICATION_REQUIRED" ->
          UiText.choose(
              "手机操作尚未执行或已暂停。请在系统设置 → 应用 → DSHA → 通知中允许通知，并开启「手机任务状态」通知类别；该通知提供停止入口。允许后回原对话明确继续，不会自动重放。",
              "Phone actions have not started or are paused. In Android Settings → Apps → DSHA → Notifications, allow notifications and enable the Phone task status category, which provides Stop. Then explicitly continue in the original conversation; nothing is replayed automatically.");
      case "ASSISTANT_OVERLAY_PERMISSION_REQUIRED" ->
          UiText.choose(
              "本次手机工具尚未执行。请打开 DSHA 设置中的悬浮窗权限，或系统设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → DSHA。允许后回到原对话明确继续；不会自动重放。",
              "This phone tool has not executed. Open overlay permission in DSHA settings, or Android Settings → Apps → Special app access → Display over other apps → DSHA. After allowing it, return to this conversation and explicitly continue. Nothing is replayed automatically.");
      case "ASSISTANT_LOCAL_RESUME_REQUIRED", "SCREEN_CONTROL_PAUSED" ->
          UiText.choose(
              "手机控制已在本机暂停。请在当前任务悬浮界面或通知中继续；不要重复操作工具。",
              "Phone control is paused locally. Continue from the current task overlay or notification; do not repeat the phone action.");
      case "SCREEN_TURN_ENDED" ->
          UiText.choose(
              "本轮手机任务已结束，不能重新获取控制。请在原对话中明确发起下一轮；不会重放已完成或未知的操作。",
              "This phone task has ended and cannot reacquire control. Explicitly start a new turn in the original conversation; completed or unknown actions are not replayed.");
      case "FRESH_SCREEN_OBSERVATION_REQUIRED" ->
          UiText.choose(
              "状态窗已隐藏，旧屏幕观察已失效，本次输入未执行。请先重新读取屏幕，再决定下一步；不要自动重复原动作。",
              "The status window is hidden and the previous screen observation is invalid. This input was not executed. Observe the screen again before deciding the next action; do not automatically repeat it.");
      case "ASSISTANT_CONVERSATION_BUSY", "PHYSICAL_SCREEN_BUSY" ->
          UiText.choose(
              "另一会话仍在执行手机任务。请先在手机上结束或检查该任务，再回到原对话继续。",
              "Another conversation still owns the phone task. End or inspect it on the phone before continuing this conversation.");
      default ->
          UiText.choose(
              "手机任务状态界面尚未就绪，工具未开始执行。请检查当前 DSH 会话与连接，并从手机前台打开原对话后再明确继续。",
              "The phone task status interface is unavailable; this tool has not started. Check the current DSH conversation and connection, open the original conversation in the foreground on the phone, then explicitly continue.");
    };
  }

  public static Intent permissionIntent(Context context) {
    return new Intent(context, AssistantActivity.class)
        .setAction(CONTROL_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }

  public static Intent notificationPermissionIntent(Context context) {
    return permissionIntent(context).putExtra("notification_permission", true);
  }

  private static void offerOverlayPermission(Context context) {
    // Revoking overlay permission must not replace an active task's stop notification.
    if (live != null) return;
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (Build.VERSION.SDK_INT >= 26)
        manager.createNotificationChannel(
            new NotificationChannel(
                STOP_CHANNEL,
                UiText.choose("手机任务状态", "Phone task status"),
                NotificationManager.IMPORTANCE_LOW));
      Notification.Builder note =
          Build.VERSION.SDK_INT >= 26
              ? new Notification.Builder(context, STOP_CHANNEL)
              : new Notification.Builder(context);
      note.setSmallIcon(android.R.drawable.ic_dialog_info)
          .setContentTitle(UiText.choose("允许显示手机任务状态", "Allow phone task status"))
          .setContentText(
              UiText.choose(
                  "点按设置悬浮窗权限，然后返回原对话继续",
                  "Tap to allow overlays, then return to the original conversation"))
          .setContentIntent(
              PendingIntent.getActivity(
                  context,
                  1971,
                  permissionIntent(context),
                  PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
          .setAutoCancel(true);
      manager.notify(com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT, note.build());
    } catch (RuntimeException unavailable) {
      // The tool response also contains the full settings path, even with notifications denied.
    }
  }

  private static boolean matchesTask(
      AssistantSessionController current, AssistantBinding binding, String turnId) {
    if (current == null) return false;
    var status = current.snapshot();
    return status.binding().equals(binding)
        && turnId != null
        && !turnId.isBlank()
        && turnId.equals(status.turnId())
        && status.phase() == AssistantSessionState.Phase.RUNNING;
  }

  public static void bind(Context context, AssistantBinding binding, AssistantBackend backend) {
    bind(context, binding, backend, () -> {});
  }

  public static void bind(
      Context context, AssistantBinding binding, AssistantBackend backend, Runnable ready) {
    String key = UUID.randomUUID().toString();
    pending.put(key, new Pending(binding, backend, ready, null, 0, null));
    startBinding(context, key);
  }

  private static void startBinding(Context context, String key) {
    Intent intent = new Intent(context, AssistantService.class).putExtra("binding", key);
    try {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
      else context.startService(intent);
    } catch (RuntimeException failure) {
      pending.remove(key);
      throw failure;
    }
  }

  public static void startLocal(Context context, String sessionId) {
    context.startActivity(
        new Intent(context, AssistantActivity.class)
            .putExtra("sessionId", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    live = this;
    var manager = getSystemService(NotificationManager.class);
    if (Build.VERSION.SDK_INT >= 26)
      manager.createNotificationChannel(
          new NotificationChannel(
              STOP_CHANNEL,
              UiText.choose("手机任务状态", "Phone task status"),
              NotificationManager.IMPORTANCE_LOW));
    Intent open = new Intent(this, AssistantActivity.class).putExtra("bound", true);
    Intent stop = new Intent(this, AssistantService.class).setAction("STOP_TASK");
    stopAction =
        PendingIntent.getService(
            this, 1952, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // 直接回复：悬浮窗隐藏时也能从通知栏（或系统支持时的实时活动/岛）回复当前会话。
    Intent reply = new Intent(this, AssistantService.class).setAction("REPLY");
    PendingIntent replyAction =
        PendingIntent.getService(
            this,
            1953,
            reply,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
    Intent showOverlay = new Intent(this, AssistantService.class).setAction("SHOW_OVERLAY");
    PendingIntent showAction =
        PendingIntent.getService(
            this,
            1954,
            showOverlay,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder note =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, STOP_CHANNEL)
            : new Notification.Builder(this);
    note.setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(UiText.choose("DSHA 悬浮助手", "DSHA floating assistant"))
        .setContentText(
            UiText.choose("查看绑定会话；暂停或停止当前任务", "View conversation; pause or stop the current task"))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 1951, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .addAction(
            new Notification.Action.Builder(null, UiText.choose("回复", "Reply"), replyAction)
                .addRemoteInput(
                    new android.app.RemoteInput.Builder(REPLY_KEY)
                        .setLabel(UiText.choose("回复当前会话", "Reply to this conversation"))
                        .build())
                .setAllowGeneratedReplies(false)
                .build())
        .addAction(
            new Notification.Action.Builder(
                    null, UiText.choose("显示悬浮窗", "Show floating window"), showAction)
                .build())
        .addAction(
            new Notification.Action.Builder(null, UiText.choose("停止任务", "Stop task"), stopAction)
                .build())
        .setOngoing(true);
    if (Build.VERSION.SDK_INT >= 31)
      note.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    taskNotification = note.build();
    if (new com.deepseekharness.app.core.ConfigStore(this).isTaskIslandEnabled())
      // 系统支持实时活动时把同一条通知提升为岛；同一 id、频道与停止入口，不支持时原样返回。
      taskNotification =
          com.deepseekharness.app.IslandSupport.enhance(
              this, taskNotification, UiText.choose("DSH 执行中", "DSH running"), true);
    startForeground(
        com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT, taskNotification);
    confirmations = new AssistantConfirmationWindow(this);
    PhoneControlCoordinator.get(this).setConfirmationHandler(confirmations::show);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("STOP_TASK".equals(intent.getAction())) {
      if (controller != null) controller.stop();
      return START_NOT_STICKY;
    }
    if ("SHOW_OVERLAY".equals(intent.getAction())) {
      if (window != null) window.showByUser();
      return START_NOT_STICKY;
    }
    if ("REPLY".equals(intent.getAction())) {
      var results = android.app.RemoteInput.getResultsFromIntent(intent);
      CharSequence text = results == null ? null : results.getCharSequence(REPLY_KEY);
      String message = text == null ? "" : text.toString().strip();
      if (controller != null && !message.isEmpty() && message.length() <= 32768)
        controller.send(message);
      // 重新发出同一条通知，结束系统的“发送中”状态。
      var manager = getSystemService(NotificationManager.class);
      if (manager != null && taskNotification != null)
        manager.notify(
            com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT, taskNotification);
      return START_NOT_STICKY;
    }
    String key = intent.getStringExtra("binding");
    Pending selection = key == null ? null : pending.remove(key);
    if (selection == null) {
      if (controller == null) stopSelf();
      return START_NOT_STICKY;
    }
    if (selection.verifiedTurn != null) {
      boolean nativeTaskCurrent = true;
      if (selection.nativeTask != null)
        try {
          PhoneControlCoordinator.get(this).checkBegin(selection.nativeTask);
        } catch (IllegalStateException ended) {
          nativeTaskCurrent = false;
        }
      // The service may be scheduled after a timeout or after a native pause.
      // Such a request must not later replace a view or resume screen ownership.
      if (SystemClock.elapsedRealtime() >= selection.expiresAt
          || !nativeTaskCurrent
          || !stopChannelCurrent(this, selection.nativeTask)
          || !selection.backend.current()
          || PhoneControlCoordinator.get(this).snapshot().paused()
          || !AssistantTaskAttachment.mayReplace(
              controller == null ? null : controller.snapshot(), selection.binding)) {
        selection.ready.run();
        if (controller == null) stopSelf();
        return START_NOT_STICKY;
      }
      if (controller != null && controller.snapshot().binding().equals(selection.binding)) {
        if (taskNotification != null
            && PhoneControlCoordinator.get(this).snapshot().binding() == null)
          getSystemService(NotificationManager.class)
              .notify(
                  com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT,
                  taskNotification);
        if (controller.followVerifiedTask(selection.verifiedTurn)
            && (window == null || !window.taskUiAvailable())) {
          if (window != null) window.close();
          window = new AssistantWindow(this, controller);
          window.show();
        }
        selection.ready.run();
        return START_NOT_STICKY;
      }
    }
    if (window != null) window.close();
    if (controller != null) controller.close();
    controller =
        new AssistantSessionController(
            this, selection.binding, selection.backend, selection.verifiedTurn);
    controller.addListener(stopAccessChanged);
    if (selection.verifiedTurn != null && taskNotification != null)
      getSystemService(NotificationManager.class)
          .notify(
              com.deepseekharness.app.util.NotificationIds.FLOATING_ASSISTANT, taskNotification);
    com.deepseekharness.app.OverlayController.teardown(this);
    window = new AssistantWindow(this, controller);
    window.show();
    selection.ready.run();
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    if (live == this) live = null;
    if (window != null) window.close();
    if (controller != null) controller.close();
    if (confirmations != null) confirmations.close();
    PhoneControlCoordinator.get(this).setConfirmationHandler(null);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
