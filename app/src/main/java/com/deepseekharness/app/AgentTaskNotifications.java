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
import androidx.core.app.RemoteInput;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AgentTaskState;
import com.deepseekharness.app.util.IslandText;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.ui.AgentTasksActivity;
import com.deepseekharness.app.ui.BrowserConversationRoute;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private final Set<String> finished = new HashSet<>();
  private final Set<String> activeTags = new HashSet<>();
  private final Map<String, Long> terminalAt = new HashMap<>();
  private final Map<String, String> signatures = new HashMap<>();
  private final Map<String, LinkedHashSet<String>> selections = new HashMap<>();
  static final String REPLY_KEY = "dsha_answer_text";
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
    var controller = HarnessController.get(context);
    var current = controller.getReadyWebPageIdentity();
    if (current != null) state.identity(current.generation(), current.instanceId());
    // 页面暂不可用不等于运行代次改变：不能把仍在运行的任务判死。
    // 只有确认 Web 已经停止时才终结旧票，避免状态莫名变成“暂不可确认”。
    else if (!controller.isWebRunning()) state.identity(-1, "");
    return current;
  }

  public synchronized String bridge(String operation, String payload) throws Exception {
    if (payload.length() > 4096) return "[TASK_PAYLOAD_LIMIT]";
    JSONObject value = new JSONObject(payload);
    // One ready snapshot owns state, listener port and the context reply together.
    // Reading readiness again after bind can pin the producer to a different runtime.
    var registration = HarnessController.get(context).getReadyTaskRegistration();
    // 暂不可登记时保留已有任务事实；上报端会重试，等同一运行代次恢复后继续更新。
    if (registration == null) return "[TASK_NOT_READY]";
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
                value.optString("detail"),
                value.optString("title"),
                value.optString("question"),
                SystemClock.elapsedRealtime())
            : operation.equals("ack")
                && state.ack(
                    client,
                    key,
                    value.optString("action"),
                    value.optString("outcome"),
                    SystemClock.elapsedRealtime());
    // 阶段变化立即重绘；心跳也会走这里，但内容未变时 render 不会重复 notify。
    if (accepted) main.post(this::render);
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
      case "waiting" -> UiText.choose("等你确认", "Waiting for you");
      case "stop-requested" -> UiText.choose("已请求停止，等待结束", "Stop requested; waiting for end");
      case "unknown" -> UiText.choose("状态暂不可确认", "Status cannot be confirmed");
      case "ended" ->
          switch (row.reason()) {
            case "completed" -> UiText.choose("任务完成", "Task completed");
            case "aborted", "interrupted" -> UiText.choose("任务已停止", "Task stopped");
            case "max-tokens" -> UiText.choose("已达到输出上限", "Output limit reached");
            case "connection-lost" -> UiText.choose("连接中断，任务已结束", "Connection lost; task ended");
            case "new-turn-observed" -> UiText.choose("已被新任务接管", "Superseded by a new task");
            case "reporter-changed" -> UiText.choose("任务上报已切换", "Task reporter changed");
            default -> UiText.choose("任务需要处理", "Task needs attention");
          };
      default -> UiText.choose("任务运行中", "Task running");
    };
  }

  /**
   * 胶囊短文本：中文 ≤7 字、英文 ≤9 字符（超长会被系统截断或退化成纯图标）。
   * 工具阶段带工具名，终态显示完成结果；秒数与详情交给 contentText 与展开态。
   */
  public static String islandShortText(AgentTaskState.View row) {
    return IslandText.shortText(row, UiText.language().startsWith("zh"));
  }

  /** 原生快速回答：单问题；可带 1–3 个选项按钮，多选走“切换 + 提交”。 */
  private static final class QuickAnswer {
    final int port;
    final String path, token, callId, questionId;
    final String[] labels;
    final boolean multi;

    QuickAnswer(
        int port,
        String path,
        String token,
        String callId,
        String questionId,
        String[] labels,
        boolean multi) {
      this.port = port;
      this.path = path;
      this.token = token;
      this.callId = callId;
      this.questionId = questionId;
      this.labels = labels;
      this.multi = multi;
    }
  }

  private static QuickAnswer quickAnswer(AgentTaskState.View row) {
    if (!"waiting".equals(row.phase()) || row.question().isEmpty()) return null;
    try {
      var value = new JSONObject(row.question());
      int port = value.optInt("port", -1);
      String path = value.optString("path");
      String token = value.optString("token");
      String callId = value.optString("callId");
      var questions = value.optJSONArray("questions");
      if (port < 1
          || port > 65535
          || !path.startsWith("/")
          || path.length() > 64
          || !token.matches("[a-f0-9]{64}")
          || callId.isEmpty()
          || callId.length() > 128
          || questions == null
          || questions.length() != 1) return null;
      var question = questions.getJSONObject(0);
      String id = question.optString("id");
      if (id.isEmpty()) return null;
      boolean multi = question.optBoolean("multiSelect");
      var options = question.optJSONArray("options");
      String[] labels = new String[0];
      if (options != null && options.length() >= 1 && options.length() <= 3) {
        labels = new String[options.length()];
        for (int i = 0; i < options.length(); i++) {
          String label = options.getJSONObject(i).optString("label");
          if (label.isEmpty() || label.codePointCount(0, label.length()) > 24) return null;
          labels[i] = label;
        }
      }
      return new QuickAnswer(port, path, token, callId, id, labels, multi);
    } catch (Exception malformed) {
      return null;
    }
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
    boolean chinese = UiText.language().startsWith("zh");
    boolean foreground = ForegroundActivity.current() != null;
    long now = SystemClock.elapsedRealtime();
    // 岛位选举：系统按包名只显示一个胶囊，多个活跃任务同时提升会互相顶掉。
    // 没有活跃任务时，最近 8 秒内结束的任务短暂占用岛位，显示“完成/已停止”。
    var rows = state.snapshot(now);
    String islandTag =
        IslandText.tagOf(IslandText.pickForDisplay(rows, now, IslandText.TERMINAL_GRACE_MS));
    var config = HarnessController.get(context).config();
    boolean islandEnabled = config.isTaskIslandEnabled();
    boolean islandChronometer = islandEnabled && config.isTaskIslandChronometer();
    Set<String> keep = new HashSet<>();
    for (var row : rows) {
      String tag = "dsha-task:" + row.key();
      keep.add(tag);
      boolean wasActive = activeTags.contains(tag);
      if (row.active()) {
        activeTags.add(tag);
        terminalAt.remove(tag);
        finished.remove(tag);
      } else if (row.dismissed()) {
        activeTags.remove(tag);
        cancel(manager, tag);
        finished.add(tag);
        continue;
      } else {
        if (finished.contains(tag)) continue;
        // 刚结束的这一帧开始计时：断连 60 秒后收敛的行也要给用户看到「已断开」。
        if (wasActive) {
          terminalAt.put(tag, now);
          finished.remove(tag);
        }
        Long endedAt = terminalAt.get(tag);
        if (endedAt == null) {
          endedAt = row.seen();
          terminalAt.put(tag, endedAt);
        }
        if (now - endedAt > IslandText.TERMINAL_GRACE_MS) {
          cancel(manager, tag);
          terminalAt.remove(tag);
          finished.add(tag);
          continue;
        }
      }
      if (foreground) {
        // 前台有原生页面可看，不占用通知与岛位；切回后台会重新发布。
        cancel(manager, tag);
        continue;
      }
      boolean waiting = "waiting".equals(row.phase());
      QuickAnswer quick = quickAnswer(row);
      LinkedHashSet<String> chosen = null;
      if (!waiting) selections.remove(row.action());
      else if (quick != null && quick.multi) {
        chosen = selections.computeIfAbsent(row.action(), key -> new LinkedHashSet<>());
        chosen.retainAll(Arrays.asList(quick.labels));
      }
      String selectionText = chosen == null || chosen.isEmpty() ? "" : String.join("、", chosen);
      Intent details =
          extras(
              new Intent(context, AgentTasksActivity.class)
                  .setData(Uri.parse("dsha-task://details/" + row.action())),
              row);
      var detailPi =
          PendingIntent.getActivity(
              context,
              1,
              details,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Intent conversation =
          (waiting
                  ? BrowserConversationRoute.question(
                      context, row.key().generation(), row.key().instance(), row.key().session())
                  : BrowserConversationRoute.conversation(
                      context, row.key().generation(), row.key().instance(), row.key().session()))
              .setData(Uri.parse("dsha-task://conversation/" + row.action()));
      var conversationPi =
          PendingIntent.getActivity(
              context,
              2,
              conversation,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      long elapsed = Math.max(0, (row.active() ? now : row.seen()) - row.started());
      String phaseText = phase(row);
      String detail = row.detail();
      String title = row.title();
      String total = IslandText.duration(elapsed, chinese);
      String contentTitle = title.isEmpty() ? UiText.choose("DSHA 任务", "DSHA task") : title;
      String body = detail.isEmpty() ? phaseText : phaseText + " · " + detail;
      // 运行中不把秒数写进文本：心跳每秒重绘会替换通知并吞掉点击，耗时交给系统计时器。
      String collapsed = row.active() ? body : body + " · " + total;
      // 总耗时只出现在 subText；展开态补充“当前阶段”耗时，避免同一信息写两遍。
      String subText = UiText.format(UiText.choose("第 %d 轮", "Turn %d"), row.key().turn());
      String answerHint =
          UiText.choose(
              "可直接点通知按钮回答，或点按进入对话",
              "Answer from the notification buttons, or tap to open the chat");
      boolean optionsClipped =
          waiting && quick != null && quick.labels.length > IslandText.QUICK_OPTION_LIMIT;
      String moreHint =
          optionsClipped
              ? UiText.choose("更多选项请点按通知进入对话回答", "More options: tap to answer in the chat")
              : "";
      String bigText =
          phaseText
              + (detail.isEmpty() ? "" : "\n" + detail)
              + (waiting ? "\n" + answerHint : "")
              + (moreHint.isEmpty() ? "" : "\n" + moreHint)
              + (selectionText.isEmpty()
                  ? ""
                  : "\n"
                      + UiText.format(
                          UiText.choose("已选 %d 项：%s", "Selected %d: %s"),
                          chosen.size(),
                          selectionText))
              // 轮次已由 subText 展示，展开态不再重复；运行中不写秒数，避免每秒重绘替换
              // PendingIntent 吞掉按钮点击（内容签名由 collapsed/subText/chronometer 保持稳定）。
              + (row.active()
                  ? ""
                  : "\n" + UiText.format(UiText.choose("总耗时 %s", "Total %s"), total));
      boolean promoted = islandEnabled && tag.equals(islandTag);
      String shortText = promoted ? islandShortText(row) : "";
      String signature =
          contentTitle
              + '\u0000'
              + body
              + '\u0000'
              + collapsed
              + '\u0000'
              + subText
              + '\u0000'
              + bigText
              + '\u0000'
              + promoted
              + '\u0000'
              + shortText
              + '\u0000'
              + row.active()
              + '\u0000'
              + row.dismissed()
              + '\u0000'
              + row.question()
              + '\u0000'
              + selectionText
              + '\u0000'
              + islandChronometer;
      if (!posted.contains(tag) || !signature.equals(signatures.get(tag))) {
        var publicNotice =
            new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_v137)
                .setContentTitle(UiText.choose("DSHA 任务", "DSHA task"))
                .setContentText(phaseText)
                .build();
        var notice =
            new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_v137)
                .setContentTitle(contentTitle)
                .setContentText(collapsed)
                .setSubText(subText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(conversationPi)
                .setOnlyAlertOnce(true)
                // 完成态在保留期内也必须 ongoing，否则系统会立即撤掉提升、胶囊直接消失。
                .setOngoing(row.active() || promoted)
                .setAutoCancel(!row.active())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotice)
                // 运行中给系统一个固定 when：开计时器显示已用时间，关计时器显示开始时间。
                // 两种情况通知内容都不再每秒变化，避免重绘吞点击。
                .setShowWhen(row.active());
        if (row.active())
          notice
              .setUsesChronometer(islandChronometer)
              .setWhen(System.currentTimeMillis() - elapsed);
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
        if (waiting && quick != null) {
          // 系统通知最多显示 3 个动作：只放 2 个选项，最后一个槽位留给提交/自己写。
          // 选项被裁剪时在展开态提示，用户仍可点通知进对话回答。
          var slots =
              IslandText.quickSlots(
                  quick.labels.length, quick.multi, chosen != null && !chosen.isEmpty());
          int shown = slots.options();
          for (int i = 0; i < shown; i++) {
            String label = actionLabel(quick.labels[i]);
            if (quick.multi && chosen.contains(quick.labels[i])) label = "✓ " + label;
            notice.addAction(
                0,
                label,
                quick.multi
                    ? togglePendingIntent(row, quick.labels[i], i)
                    : answerPendingIntent(row, quick, i));
          }
          // 多选已选时“提交”优先；2 个选项时槽位已满，“自己写”让位（槽位规则见 IslandText.quickSlots）。
          if (slots.submit())
            notice.addAction(0, UiText.choose("提交", "Submit"), submitPendingIntent(row, quick));
          if (slots.custom()) notice.addAction(customAnswerAction(row, quick));
          if (slots.openApp())
            notice.addAction(0, UiText.choose("打开回答", "Answer in app"), conversationPi);
        } else {
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
          notice.addAction(
              0,
              waiting
                  ? UiText.choose("打开回答", "Answer in app")
                  : UiText.choose("任务详情", "Task details"),
              waiting ? conversationPi : detailPi);
        }
        android.app.Notification built = notice.build();
        if (promoted) built = IslandSupport.enhance(context, built, shortText, true);
        try {
          manager.notify(tag, ID, built);
          posted.add(tag);
          signatures.put(tag, signature);
        } catch (SecurityException denied) {
          /* The native task page remains available without notification permission. */
        }
      }
    }
    for (String tag : new HashSet<>(posted)) if (!keep.contains(tag)) cancel(manager, tag);
    for (String tag : new HashSet<>(finished)) if (!keep.contains(tag)) finished.remove(tag);
    for (String tag : new HashSet<>(activeTags)) if (!keep.contains(tag)) activeTags.remove(tag);
    // 任务行被 prune 后不再出现在快照里，多选集合也要随之释放，避免长期运行残留。
    Set<String> waitingActions = new HashSet<>();
    for (var row : rows) if ("waiting".equals(row.phase())) waitingActions.add(row.action());
    selections.keySet().retainAll(waitingActions);
  }

  /** 多选切换：更新本地选择集合并重绘通知；不改动任务或会话。 */
  public static void toggleSelection(Context context, String rowAction, String label) {
    AgentTaskNotifications.get(context).toggle(rowAction, label);
  }

  /** 当前多选集合的只读副本；接收器 POST 时读取。 */
  public static List<String> selection(Context context, String rowAction) {
    return AgentTaskNotifications.get(context).selectedFor(rowAction);
  }

  private synchronized void toggle(String rowAction, String label) {
    var selected = selections.computeIfAbsent(rowAction, key -> new LinkedHashSet<>());
    if (!selected.remove(label)) selected.add(label);
    main.post(this::render);
  }

  private synchronized List<String> selectedFor(String rowAction) {
    var selected = selections.get(rowAction);
    return selected == null ? List.of() : new ArrayList<>(selected);
  }

  private static int mutableFlags() {
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
    return flags;
  }

  /** 通知按钮文字比胶囊宽松：12 个 code point，超出才截断。 */
  private static String actionLabel(String label) {
    if (label.codePointCount(0, label.length()) <= 12) return label;
    return label.substring(0, label.offsetByCodePoints(0, 12));
  }

  private PendingIntent togglePendingIntent(AgentTaskState.View row, String label, int option) {
    Intent intent =
        new Intent(context, AgentTaskAnswerReceiver.class)
            .setAction("dsha-task-toggle")
            .setData(Uri.parse("dsha-task://toggle/" + row.action() + "/" + option))
            .putExtra("rowAction", row.action())
            .putExtra("label", label);
    return PendingIntent.getBroadcast(
        context,
        (row.action().hashCode() & 0x0fffffff) * 8 + 5,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private PendingIntent submitPendingIntent(AgentTaskState.View row, QuickAnswer quick) {
    Intent intent =
        answerBase(row, quick)
            .setData(Uri.parse("dsha-task://submit/" + row.action()))
            .putExtra("multi", true);
    return PendingIntent.getBroadcast(
        context,
        (row.action().hashCode() & 0x0fffffff) * 8 + 6,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private NotificationCompat.Action customAnswerAction(AgentTaskState.View row, QuickAnswer quick) {
    var reply =
        new RemoteInput.Builder(REPLY_KEY)
            .setLabel(UiText.choose("输入答案", "Type an answer"))
            .setAllowFreeFormInput(true)
            .build();
    Intent intent =
        answerBase(row, quick)
            .setData(Uri.parse("dsha-task://custom/" + row.action()))
            .putExtra("custom", true)
            .putExtra("multi", quick.multi)
            .putExtra("rowAction", row.action());
    return new NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher_v137,
            UiText.choose("自己写", "Write"),
            PendingIntent.getBroadcast(
                context, (row.action().hashCode() & 0x0fffffff) * 8 + 7, intent, mutableFlags()))
        .addRemoteInput(reply)
        .build();
  }

  private Intent answerBase(AgentTaskState.View row, QuickAnswer quick) {
    return new Intent(context, AgentTaskAnswerReceiver.class)
        .setAction("dsha-task-answer")
        .putExtra("rowAction", row.action())
        .putExtra("port", quick.port)
        .putExtra("path", quick.path)
        .putExtra("token", quick.token)
        .putExtra("callId", quick.callId)
        .putExtra("questionId", quick.questionId);
  }

  private PendingIntent answerPendingIntent(
      AgentTaskState.View row, QuickAnswer quick, int option) {
    Intent intent =
        new Intent(context, AgentTaskAnswerReceiver.class)
            .setAction("dsha-task-answer")
            .setData(Uri.parse("dsha-task://answer/" + row.action() + "/" + option))
            .putExtra("port", quick.port)
            .putExtra("path", quick.path)
            .putExtra("token", quick.token)
            .putExtra("callId", quick.callId)
            .putExtra("questionId", quick.questionId)
            .putExtra("label", quick.labels[option]);
    return PendingIntent.getBroadcast(
        context,
        (row.action().hashCode() & 0x0fffffff) * 8 + option,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** 取消一条任务通知并清掉去重记录；重复调用是安全的。 */
  private void cancel(NotificationManager manager, String tag) {
    if (posted.remove(tag)) manager.cancel(tag, ID);
    signatures.remove(tag);
  }
}
