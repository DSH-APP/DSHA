package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.AgentTaskNotifications;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** Actual Agent turn facts; all cancellation goes through the authenticated one-shot queue. */
public final class AgentTasksActivity extends AppCompatActivity {
  private CardPage page;
  private LinearLayout tasks;
  private String signature = "";
  private final java.util.Map<
          com.deepseekharness.app.util.AgentTaskState.Key, android.widget.TextView>
      timings = new java.util.HashMap<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          render();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    page =
        new CardPage(
            this,
            UiText.choose("Agent 任务", "Agent tasks"),
            UiText.choose(
                "查看实际对话进度。停止只作用于对应任务。显示最多 32 个活动任务及最近 5 个结束状态。",
                "View actual conversation progress. Stop affects only the selected task. Shows up to 32 active tasks and the 5 most recent ended states."));
    UiNavigation.addHeader(this, page.root, UiText.choose("Agent 任务", "Agent tasks"), this::finish);
    page.content.removeViewAt(0);
    tasks = page.column();
    page.content.addView(tasks);
    setContentView(page.root);
  }

  @Override
  protected void onStart() {
    super.onStart();
    main.post(refresh);
  }

  @Override
  protected void onStop() {
    main.removeCallbacks(refresh);
    super.onStop();
  }

  private void render() {
    var source = AgentTaskNotifications.get(this);
    var rows = source.snapshot();
    String next =
        rows.stream()
            .map(
                row ->
                    row.key()
                        + ":"
                        + row.phase()
                        + ":"
                        + row.reason()
                        + ":"
                        + row.active()
                        + ":"
                        + row.stopping())
            .collect(java.util.stream.Collectors.joining("|"));
    if (next.equals(signature) && !timings.isEmpty()) {
      for (var row : rows) timings.get(row.key()).setText(timing(row));
      return;
    }
    signature = next;
    int scroll = page.scroll.getScrollY();
    tasks.removeAllViews();
    timings.clear();
    if (rows.isEmpty()) {
      tasks.addView(page.text(UiText.choose("暂无任务记录", "No task records"), 16, R.color.text));
      return;
    }
    for (var row : rows) {
      var card = page.card(tasks);
      card.addView(page.text(AgentTaskNotifications.phase(row), 16, R.color.text));
      var elapsed = page.text(timing(row), 12, R.color.text);
      timings.put(row.key(), elapsed);
      card.addView(elapsed);
      if (row.active() && !row.stopping() && !row.phase().equals("unknown"))
        page.button(
            card,
            UiText.choose("停止", "Stop"),
            false,
            () -> {
              source.stop(AgentTaskNotifications.extras(new android.content.Intent(), row));
              render();
            });
      page.button(
          card,
          UiText.choose("回聊天", "Return to chat"),
          false,
          () ->
              startActivity(
                  BrowserConversationRoute.conversation(
                      this, row.key().generation(), row.key().instance(), row.key().session())));
    }
    page.scroll.post(() -> page.scroll.scrollTo(0, scroll));
  }

  private static String timing(com.deepseekharness.app.util.AgentTaskState.View row) {
    return row.key().session()
        + " · "
        + row.key().turn()
        + " · "
        + Math.max(
            0, ((row.active() ? SystemClock.elapsedRealtime() : row.seen()) - row.started()) / 1000)
        + "s";
  }
}
