package com.deepseekharness.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.deepseekharness.app.util.UiText;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通知/岛上的回答动作：选项按钮、多选切换与“自己写”都从这里回到网页侧。
 *
 * <p>独立于通知渲染与前台 Activity，用户不必先打开 App；端点、令牌与 callId 都来自
 * 本应用自己发布的 PendingIntent。令牌只用于本机回环请求，答案是否有效仍由网页侧按
 * 当前真正待答的问题再次校验。
 *
 * <p>动作在后台执行，失败时只写日志会表现为“点了没反应”。因此失败、空提交与失效入口
 * 都要给一条可见提示，并引导用户改从通知正文进入对话回答。
 */
public final class AgentTaskAnswerReceiver extends BroadcastReceiver {
  private static final String TAG = "DSHA";
  private static final int TIMEOUT_MS = 4000;

  @Override
  public void onReceive(Context context, Intent intent) {
    if ("dsha-task-toggle".equals(intent.getAction())) {
      String rowAction = intent.getStringExtra("rowAction");
      String label = intent.getStringExtra("label");
      if (rowAction != null && label != null)
        AgentTaskNotifications.toggleSelection(context, rowAction, label);
      return;
    }
    int port = intent.getIntExtra("port", -1);
    String path = intent.getStringExtra("path");
    String token = intent.getStringExtra("token");
    String callId = intent.getStringExtra("callId");
    String questionId = intent.getStringExtra("questionId");
    String rowAction = intent.getStringExtra("rowAction");
    boolean multi = intent.getBooleanExtra("multi", false);
    String custom = "";
    if (intent.getBooleanExtra("custom", false)) {
      var results = RemoteInput.getResultsFromIntent(intent);
      CharSequence typed =
          results == null ? null : results.getCharSequence(AgentTaskNotifications.REPLY_KEY);
      custom = typed == null ? "" : typed.toString().trim();
      if (custom.codePointCount(0, custom.length()) > 2000) {
        note(context, UiText.choose("回答过长，未提交", "Answer too long, not submitted"));
        return;
      }
    }
    List<String> selected = new ArrayList<>();
    if (multi && rowAction != null)
      selected.addAll(AgentTaskNotifications.selection(context, rowAction));
    else {
      String label = intent.getStringExtra("label");
      if (label != null && !label.isEmpty()) selected.add(label);
    }
    if (selected.size() > 8) {
      note(context, UiText.choose("选择过多，未提交", "Too many selections, not submitted"));
      return;
    }
    for (String label : selected)
      if (label == null || label.isEmpty() || label.codePointCount(0, label.length()) > 80) {
        note(
            context, UiText.choose("选项已失效，请点通知进入对话回答", "Options expired; open the chat to answer"));
        return;
      }
    if (selected.isEmpty() && custom.isEmpty()) {
      note(
          context,
          multi
              ? UiText.choose("还没有选择，请勾选后点“提交”", "Nothing selected yet")
              : UiText.choose(
                  "回答入口已失效，请点通知进入对话回答", "Answer link expired; open the chat to answer"));
      return;
    }
    if (port < 1
        || port > 65535
        || path == null
        || !path.startsWith("/")
        || path.length() > 64
        || token == null
        || !token.matches("[a-f0-9]{64}")
        || callId == null
        || callId.isEmpty()
        || callId.length() > 128
        || questionId == null
        || questionId.isEmpty()
        || questionId.length() > 128) {
      note(
          context,
          UiText.choose("回答入口已失效，请点通知进入对话回答", "Answer link expired; open the chat to answer"));
      return;
    }
    final String answerText = custom;
    final List<String> answerSelection = selected;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                String failure =
                    answer(port, path, token, callId, questionId, answerSelection, answerText);
                if (!failure.isEmpty())
                  note(
                      context,
                      UiText.format(
                          UiText.choose(
                              "回答未提交（%s），请点通知进入对话回答",
                              "Answer not accepted (%s); tap the notification to answer in the chat"),
                          failure));
              } catch (Throwable failed) {
                Log.w(TAG, "TASK_ANSWER_FAILED", failed);
                note(
                    context,
                    UiText.choose(
                        "回答提交失败，请点通知进入对话回答",
                        "Answer failed; tap the notification to answer in the chat"));
              } finally {
                pending.finish();
              }
            },
            "dsha-task-answer")
        .start();
  }

  /** 后台线程不能直接弹 Toast；统一派回主线程。 */
  private static void note(Context context, String text) {
    new Handler(Looper.getMainLooper())
        .post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
  }

  /**
   * POST 一次答案。
   *
   * @return 空串表示已送达；否则是可直接展示的失败原因（HTTP 状态 + 服务端错误码）。
   */
  private static String answer(
      int port,
      String path,
      String token,
      String callId,
      String questionId,
      List<String> selected,
      String custom)
      throws Exception {
    var answer = new JSONObject().put("id", questionId).put("selected", new JSONArray(selected));
    if (!custom.isEmpty()) answer.put("custom", custom);
    var body =
        new JSONObject()
            .put("token", token)
            .put("callId", callId)
            .put("answers", new JSONArray().put(answer))
            .toString();
    var connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(payload.length);
      try (var stream = connection.getOutputStream()) {
        stream.write(payload);
      }
      int status = connection.getResponseCode();
      if (status == HttpURLConnection.HTTP_OK) return "";
      String detail = errorBody(connection);
      String code = errorCode(detail);
      Log.w(TAG, "TASK_ANSWER_REJECTED " + status + (detail.isEmpty() ? "" : " " + detail));
      return "HTTP " + status + (code.isEmpty() ? "" : " " + code);
    } finally {
      connection.disconnect();
    }
  }

  /** 读取端点返回的短错误体；只在失败分支调用。 */
  private static String errorBody(HttpURLConnection connection) {
    try (var stream = connection.getErrorStream()) {
      if (stream == null) return "";
      byte[] bytes = new byte[256];
      int read = stream.read(bytes);
      if (read <= 0) return "";
      return new String(bytes, 0, read, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    } catch (Exception ignored) {
      return "";
    }
  }

  /** 从 {@code {"ok":false,"error":"NO_WAITING"}} 里取错误码；取不到返回空串。 */
  private static String errorCode(String body) {
    int key = body.indexOf("\"error\"");
    if (key < 0) return "";
    int colon = body.indexOf(':', key);
    int open = colon < 0 ? -1 : body.indexOf('"', colon);
    int close = open < 0 ? -1 : body.indexOf('"', open + 1);
    if (close <= open) return "";
    String code = body.substring(open + 1, close);
    return code.length() > 40 ? "" : code;
  }
}
