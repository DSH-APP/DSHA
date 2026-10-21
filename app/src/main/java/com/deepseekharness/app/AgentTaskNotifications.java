package com.deepseekharness.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AgentTaskState;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.ui.AgentTasksActivity;
import com.deepseekharness.app.ui.BrowserConversationRoute;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordinary task notifications, independent of the runtime's foreground service notification. */
public final class AgentTaskNotifications {
  private static final String CHANNEL = "dsh_task_progress";
  private static final int ID = 2010;
  private static AgentTaskNotifications instance;
  private final Context context;
  private final AgentTaskState state = new AgentTaskState();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<String> posted = new HashSet<>();
  private final Set<String> terminalShown = new HashSet<>();
  private final Runnable tick =
      new Runnable() {
        public void run() {
          render();
          main.postDelayed(this, 1000);
        }
      };

  private AgentTaskNotifications(Context ctx) {
    context = ctx.getApplicationContext();
    var notifications =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (notifications != null)
      for (var previous : notifications.getActiveNotifications())
        if (previous.getId() == ID
            && previous.getTag() != null
            && previous.getTag().startsWith("dsha-task:"))
          notifications.cancel(previous.getTag(), ID);
    HarnessController.get(context).addReadyWebPageListener(() -> main.post(this::render));
    main.post(tick);
  }

  public static synchronized AgentTaskNotifications get(Context ctx) {
    if (instance == null) instance = new AgentTaskNotifications(ctx);
    return instance;
  }

  /** 只撤销已存在通知所有者的同代票据，不创建实例，也不反向获取生命周期锁。 */
  public static synchronized void invalidateRuntime(long generation, String identity) {
    if (instance != null) instance.invalidate(generation, identity);
  }

  private synchronized void invalidate(long generation, String identity) {
    state.invalidate(generation, identity);
    main.post(this::render);
  }

  private PreviewPageSession.Identity sync() {
    var current = HarnessController.get(context).getReadyWebPageIdentity();
    state.identity(
        current == null ? -1 : current.generation(), current == null ? "" : current.instanceId());
    return current;
  }

  public synchronized String bridge(String operation, String payload) throws Exception {
    if (payload.length() > 4096) return "[TASK_PAYLOAD_LIMIT]";
    JSONObject value = new JSONObject(payload);
    // One ready snapshot owns state, listener port and the context reply together.
    // Reading readiness again after bind can pin the producer to a different runtime.
    var registration = HarnessController.get(context).getReadyTaskRegistration();
    if (registration == null) {
      state.identity(-1, "");
      return "[TASK_NOT_READY]";
    }
    var identity = registration.identity();
    state.identity(identity.generation(), identity.instanceId());
    int actualPort = registration.port();
    if (actualPort < 1 || value.optInt("port", -1) != actualPort) return "[TASK_STALE_PORT]";
    String client = value.optString("client");
    if (operation.equals("context")) {
      if (!registration.register(
          state,
          client,
          value.optLong("generation", -1),
          value.optString("instance"),
          value.optString("nonce"),
          actualPort,
          SystemClock.elapsedRealtime())) return "[TASK_REGISTRATION_REJECTED]";
      return new JSONObject()
          .put("generation", identity.generation())
          .put("instance", identity.instanceId())
          .put("port", actualPort)
          .toString();
    }
    return registration.whileActive(
        () -> applyBridge(operation, value, client, identity), "[TASK_NOT_READY]");
  }

  private String applyBridge(
      String operation, JSONObject value, String client, PreviewPageSession.Identity identity) {
    var key = key(value);
    if (!state.matches(key.generation(), key.instance(), client)) return "[TASK_STALE]";
    if (operation.equals("claim")) {
      var command = state.claim(client, key.generation(), key.instance());
      if (command == null) return "{}";
      try {
        return json(command.key()).put("action", command.action()).toString();
      } catch (Exception malformed) {
        return "[TASK_REJECTED]";
      }
    }
    if (!key.owner().matches("[a-f0-9]{32}")) return "[TASK_OWNER_REJECTED]";
    boolean accepted =
        operation.equals("event")
            ? state.event(
                client,
                key,
                value.optLong("sequence"),
                value.optString("phase"),
                value.optString("reason"),
                SystemClock.elapsedRealtime())
            : operation.equals("ack")
                && state.ack(
                    client,
                    key,
                    value.optString("action"),
                    value.optString("outcome"),
                    SystemClock.elapsedRealtime());
    // All frequent progress is rendered by the 1 Hz clock; terminal facts bypass it.
    if (accepted && value.optString("phase").equals("ended")) main.post(this::render);
    return accepted ? "OK" : "[TASK_REJECTED]";
  }

  public synchronized List<AgentTaskState.View> snapshot() {
    sync();
    return state.snapshot(SystemClock.elapsedRealtime());
  }

  public synchronized boolean stop(Intent intent) {
    sync();
    var key =
        new AgentTaskState.Key(
            intent.getLongExtra("generation", -1),
            intent.getStringExtra("instance"),
            intent.getStringExtra("session"),
            intent.getLongExtra("turn", -1),
            intent.getStringExtra("owner"));
    boolean result =
        state.requestStop(key, intent.getStringExtra("action"), SystemClock.elapsedRealtime());
    main.post(this::render);
    return result;
  }

  public synchronized void dismiss(Intent intent) {
    state.dismiss(
        new AgentTaskState.Key(
            intent.getLongExtra("generation", -1),
            intent.getStringExtra("instance"),
            intent.getStringExtra("session"),
            intent.getLongExtra("turn", -1),
            intent.getStringExtra("owner")),
        intent.getStringExtra("action"));
  }

  public static Intent extras(Intent intent, AgentTaskState.View row) {
    return intent
        .putExtra("generation", row.key().generation())
        .putExtra("instance", row.key().instance())
        .putExtra("session", row.key().session())
        .putExtra("turn", row.key().turn())
        .putExtra("owner", row.key().owner())
        .putExtra("action", row.action());
  }

  public static JSONObject json(AgentTaskState.Key key) throws Exception {
    return new JSONObject()
        .put("generation", key.generation())
        .put("instance", key.instance())
        .put("session", key.session())
        .put("turn", key.turn())
        .put("owner", key.owner());
  }

  private static AgentTaskState.Key key(JSONObject value) {
    return new AgentTaskState.Key(
        value.optLong("generation", -1),
        value.optString("instance"),
        value.optString("session"),
        value.optLong("turn", -1),
        value.optString("owner"));
  }

  public static String phase(AgentTaskState.View row) {
    return switch (row.phase()) {
      case "reply" -> UiText.choose("正在生成回复", "Generating reply");
      case "tool" -> UiText.choose("正在执行工具", "Running tool");
      case "waiting" -> UiText.choose("等待处理", "Waiting for input");
      case "stop-requested" -> UiText.choose("已请求停止，等待结束", "Stop requested; waiting for end");
      case "unknown" -> UiText.choose("状态暂不可确认", "Status cannot be confirmed");
      case "ended" ->
          switch (row.reason()) {
            case "completed" -> UiText.choose("任务完成", "Task completed");
            case "aborted", "interrupted" -> UiText.choose("任务已停止", "Task stopped");
            case "max-tokens" -> UiText.choose("已达到输出上限", "Output limit reached");
            default -> UiText.choose("任务需要处理", "Task needs attention");
          };
      default -> UiText.choose("任务运行中", "Task running");
    };
  }

  private synchronized void render() {
    sync();
    var manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    if (Build.VERSION.SDK_INT >= 26)
      manager.createNotificationChannel(
          new NotificationChannel(
              CHANNEL,
              UiText.choose("任务进度", "Task progress"),
              NotificationManager.IMPORTANCE_DEFAULT));
    boolean foreground = ForegroundActivity.current() != null;
    Set<String> keep = new HashSet<>();
    for (var row : state.snapshot(SystemClock.elapsedRealtime())) {
      String tag = "dsha-task:" + row.key();
      keep.add(tag);
      if (foreground || row.dismissed() || !row.active() && terminalShown.contains(tag)) {
        if (foreground || row.dismissed()) manager.cancel(tag, ID);
        continue;
      }
      Intent details =
          extras(
              new Intent(context, AgentTasksActivity.class)
                  .setData(Uri.parse("dsha-task://details/" + row.action())),
              row);
      var detailPi =
          PendingIntent.getActivity(
              context,
              0,
              details,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      var publicNotice =
          new NotificationCompat.Builder(context, CHANNEL)
              .setSmallIcon(R.mipmap.ic_launcher_v137)
              .setContentTitle(UiText.choose("DSHA 任务", "DSHA task"))
              .setContentText(phase(row))
              .build();
      var notice =
          new NotificationCompat.Builder(context, CHANNEL)
              .setSmallIcon(R.mipmap.ic_launcher_v137)
              .setContentTitle(UiText.choose("DSHA 任务", "DSHA task"))
              .setContentText(
                  phase(row)
                      + " · "
                      + Math.max(
                          0,
                          ((row.active() ? SystemClock.elapsedRealtime() : row.seen())
                                  - row.started())
                              / 1000)
                      + "s")
              .setContentIntent(detailPi)
              .setOnlyAlertOnce(true)
              .setOngoing(row.active())
              .setAutoCancel(!row.active())
              .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
              .setPublicVersion(publicNotice);
      if (row.active() && !row.phase().equals("unknown")) notice.setProgress(0, 0, true);
      Intent dismissal =
          extras(
              new Intent(context, AgentTaskStopReceiver.class)
                  .setAction("dsha-task-dismiss")
                  .setData(Uri.parse("dsha-task://dismiss/" + row.action())),
              row);
      notice.setDeleteIntent(
          PendingIntent.getBroadcast(
              context,
              0,
              dismissal,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      if (row.active() && !row.stopping() && !row.phase().equals("unknown")) {
        Intent stop =
            extras(
                new Intent(context, AgentTaskStopReceiver.class)
                    .setData(Uri.parse("dsha-task://stop/" + row.action())),
                row);
        notice.addAction(
            0,
            UiText.choose("停止", "Stop"),
            PendingIntent.getBroadcast(
                context,
                0,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      }
      Intent conversation =
          BrowserConversationRoute.conversation(
                  context, row.key().generation(), row.key().instance(), row.key().session())
              .setData(Uri.parse("dsha-task://conversation/" + row.action()));
      notice.addAction(
          0,
          UiText.choose("回聊天", "Return to chat"),
          PendingIntent.getActivity(
              context,
              0,
              conversation,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      try {
        manager.notify(tag, ID, notice.build());
        posted.add(tag);
        if (!row.active()) terminalShown.add(tag);
      } catch (SecurityException denied) {
        /* The native task page remains available without notification permission. */
      }
    }
    for (String tag : new HashSet<>(posted))
      if (!keep.contains(tag)) {
        manager.cancel(tag, ID);
        posted.remove(tag);
        terminalShown.remove(tag);
      }
  }
}
