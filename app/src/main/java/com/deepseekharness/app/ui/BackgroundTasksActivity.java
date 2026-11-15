package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.BackgroundTaskModel;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 后台任务观察页：只读列出正在执行的原生任务，点按可去向其所属页面；取消仍交给原业务页面的安全提交边界。
 *
 * <p>布局：标题 + 一行概况；“进行中”任务卡（图标方块 + 名称 + 已运行时长 / 当前步骤 + 转圈）；
 * 空闲时显示空状态；“相关页面”入口列表；页脚说明取消入口。平板内容限宽居中，相关页面改两列。
 */
public final class BackgroundTasksActivity extends AppCompatActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          handler.postDelayed(this, 1000);
        }
      };

  private TextView summary;
  private LinearLayout taskList;
  private View emptyState;
  private String renderedKey;

  public static void open(Context context) {
    context.startActivity(new Intent(context, BackgroundTasksActivity.class));
  }

  public static boolean busy(Context context) {
    return RuntimeTasks.isBusy() || NativeBackupJobs.get(context).state().busy;
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  private int dp(float value) {
    return TransferUi.dp(this, value);
  }

  /** 一行正在执行的任务；key 决定是否需要重建行视图。 */
  private record Task(
      BackgroundTaskModel.Category category, String title, String meta, String key) {}

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    String pageTitle = t("后台任务", "Background tasks");
    LinearLayout root = TransferUi.vertical(this);
    root.setBackgroundColor(getColor(R.color.shell_bg));
    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(root, pageTitle);
    setContentView(root);

    LinearLayout header = TransferUi.horizontal(this);
    header.setMinimumHeight(dp(60));
    header.setPadding(dp(4), dp(2), dp(16), dp(2));
    ImageButton back = UiNavigation.back(this, () -> getOnBackPressedDispatcher().onBackPressed());
    back.setImageTintList(ColorStateList.valueOf(getColor(R.color.shell_text)));
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout titles = TransferUi.vertical(this);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
    titleParams.setMarginStart(dp(2));
    header.addView(titles, titleParams);
    TextView title = TransferUi.text(this, pageTitle, 19, R.color.shell_text, true);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setAccessibilityHeading(true);
    titles.addView(title);
    summary = TransferUi.text(this, "", 12.5f, R.color.shell_text_secondary, false);
    summary.setSingleLine(true);
    summary.setEllipsize(TextUtils.TruncateAt.END);
    summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    titles.addView(summary, TransferUi.match(this, 3));
    root.addView(header, new LinearLayout.LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout content = TransferUi.vertical(this);
    int width = getResources().getConfiguration().screenWidthDp;
    boolean phone = width < 600;
    int pad = dp(Math.max(phone ? 16 : 32, (width - 720) / 2f));
    content.setPadding(pad, dp(4), pad, dp(32));
    scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

    content.addView(TransferUi.sectionLabel(this, t("进行中", "Running")), sectionParams(4));
    taskList = TransferUi.card(this, 0);
    content.addView(taskList, TransferUi.match(this, 8));
    emptyState = emptyState();
    content.addView(emptyState, TransferUi.match(this, 8));

    content.addView(TransferUi.sectionLabel(this, t("相关页面", "Related pages")), sectionParams(24));
    content.addView(destinations(!phone), TransferUi.match(this, 8));

    TextView note =
        TransferUi.text(
            this,
            t(
                "仅显示进度；操作请进对应页面",
                "This page only shows progress. Open the related page to cancel or resolve a task."),
            12.5f,
            R.color.shell_text_muted,
            false);
    note.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams noteParams = TransferUi.match(this, 16);
    noteParams.setMarginStart(dp(4));
    noteParams.setMarginEnd(dp(4));
    content.addView(note, noteParams);

    render();
  }

  private LinearLayout.LayoutParams sectionParams(int topDp) {
    LinearLayout.LayoutParams params = TransferUi.match(this, topDp);
    params.setMarginStart(dp(4));
    return params;
  }

  // ---------------------------------------------------------------- 任务

  private List<Task> tasks() {
    List<Task> rows = new ArrayList<>();
    var maintenance = com.deepseekharness.app.core.BackupTask.get(this).snapshot();
    if (maintenance.busy()) {
      String kind = UiStateText.render(maintenance.kind);
      String detail = UiStateText.render(maintenance.detail);
      rows.add(
          new Task(
              BackgroundTaskModel.Category.MAINTENANCE,
              kind.isEmpty() ? t("环境维护", "Environment maintenance") : kind,
              detail.isEmpty() ? t("正在执行", "Running") : detail,
              "maintenance:" + maintenance.id));
    }
    var backup = NativeBackupJobs.get(this).state();
    if (backup.busy)
      rows.add(
          new Task(
              BackgroundTaskModel.Category.BACKUP,
              t("数据与备份", "Data and backups"),
              AutomaticBackupActivity.stage(backup.stage)
                  + " · "
                  + String.format(java.util.Locale.ROOT, t("%d 项", "%d entries"), backup.entries),
              "backup:" + backup.id));
    String language = UiText.language();
    for (var row : RuntimeTasks.snapshot()) {
      String detail = row.detail.isEmpty() ? t("正在执行", "Running") : UiStateText.render(row.detail);
      rows.add(
          new Task(
              BackgroundTaskModel.classify(row.kind),
              UiStateText.render(row.kind),
              BackgroundTaskModel.elapsed(row.elapsedMillis, language) + " · " + detail,
              "runtime:" + row.id));
    }
    return rows;
  }

  private void render() {
    if (taskList == null) return;
    List<Task> rows = tasks();
    String count =
        rows.isEmpty()
            ? t("当前空闲，没有正在执行的原生任务", "Idle; no native tasks are running")
            : String.format(
                java.util.Locale.ROOT, t("%d 项任务正在执行", "%d tasks running"), rows.size());
    // 计数行：数字变化直接落值（不闪），空闲/运行文案切换才淡入（审计 E-10）。
    UiMotion.crossFadeTimerText(summary, count);
    taskList.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    emptyState.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

    StringBuilder keys = new StringBuilder();
    for (Task row : rows) keys.append(row.key).append('|');
    String key = keys.toString();
    if (!key.equals(renderedKey)) {
      renderedKey = key;
      taskList.removeAllViews();
      for (int i = 0; i < rows.size(); i++) {
        if (i > 0) taskList.addView(TransferUi.divider(this, 68));
        taskList.addView(taskRow(rows.get(i)));
      }
      // 新增/消失的行用既有列表首屏同一节奏淡入；不重排已有行。
      UiMotion.staggerIn(taskList, 8);
      return;
    }
    // 同一批任务：只刷新时长与步骤文字，不重建行，保留焦点与读屏位置。
    int index = 0;
    for (int i = 0; i < taskList.getChildCount(); i++) {
      View child = taskList.getChildAt(i);
      if (!(child.getTag() instanceof TextView[] views)) continue;
      Task row = rows.get(index++);
      UiMotion.crossFadeText(views[0], row.title);
      UiMotion.crossFadeTimerText(views[1], row.meta);
      child.setContentDescription(row.title + ", " + row.meta);
    }
  }

  private View taskRow(Task row) {
    BackgroundTaskModel.Destination destination = BackgroundTaskModel.destination(row.category);
    boolean opens = destination != BackgroundTaskModel.Destination.NONE;
    LinearLayout line = TransferUi.horizontal(this);
    line.setMinimumHeight(dp(68));
    line.setPadding(dp(14), dp(12), dp(14), dp(12));
    if (opens) {
      line.setBackgroundResource(R.drawable.bg_transfer_row);
      line.setClickable(true);
      line.setFocusable(true);
      line.setOnClickListener(v -> open(destination));
    } else line.setFocusable(true);
    line.addView(tile(icon(row.category), R.color.shell_accent, R.color.shell_accent_tonal));

    LinearLayout texts = TransferUi.vertical(this);
    texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    TextView name = TransferUi.text(this, row.title, 15, R.color.shell_text, true);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(name);
    TextView meta = TransferUi.text(this, row.meta, 12.5f, R.color.shell_text_secondary, false);
    meta.setMaxLines(2);
    meta.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(meta, TransferUi.match(this, 4));
    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
    textParams.setMarginStart(dp(14));
    textParams.setMarginEnd(dp(8));
    line.addView(texts, textParams);

    ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
    spinner.setIndeterminate(true);
    spinner.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.shell_accent)));
    spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    line.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
    if (opens) line.addView(chevron());

    line.setTag(new TextView[] {name, meta});
    line.setContentDescription(row.title + ", " + row.meta);
    if (opens)
      androidx.core.view.ViewCompat.replaceAccessibilityAction(
          line,
          androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
              .ACTION_CLICK,
          t("打开所属页面", "Open its page"),
          null);
    return line;
  }

  private static int icon(BackgroundTaskModel.Category category) {
    return switch (category) {
      case MAINTENANCE -> R.drawable.ic_section_maintenance;
      case BACKUP -> R.drawable.ic_recovery_database;
      case TERMINAL -> R.drawable.ic_terminal;
      case PLUGIN -> R.drawable.ic_plugins;
      case CONTAINER -> R.drawable.ic_ui2_box;
      case SKILL -> R.drawable.ic_entry_book;
      case WORKS -> R.drawable.ic_ui2_folder;
      case TOOLS -> R.drawable.ic_recovery_wrench;
      case OTHER -> R.drawable.ic_ui_tasks;
    };
  }

  private View emptyState() {
    LinearLayout box = TransferUi.card(this, 0);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setPadding(dp(24), dp(28), dp(24), dp(28));
    ImageView ring = TransferUi.icon(this, R.drawable.ic_launch_log_ok, R.color.shell_good, 52);
    ring.setBackgroundResource(R.drawable.bg_transfer_ring);
    ring.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.shell_good_soft)));
    int ringPad = dp(14);
    ring.setPadding(ringPad, ringPad, ringPad, ringPad);
    box.addView(ring);
    TextView heading =
        TransferUi.text(this, t("没有正在执行的任务", "Nothing is running"), 15, R.color.shell_text, true);
    heading.setGravity(Gravity.CENTER);
    box.addView(heading, TransferUi.match(this, 14));
    TextView body =
        TransferUi.text(
            this,
            t(
                "任务开始后在此显示",
                "Terminals, plugins, backups and maintenance appear here while they run."),
            13,
            R.color.shell_text_secondary,
            false);
    body.setGravity(Gravity.CENTER);
    box.addView(body, TransferUi.match(this, 6));
    return box;
  }

  // ---------------------------------------------------------------- 相关页面

  private record Link(int icon, String title, String subtitle, Runnable action) {}

  private View destinations(boolean twoColumns) {
    List<Link> links =
        List.of(
            new Link(
                R.drawable.ic_ui_chat,
                t("Agent 任务", "Agent tasks"),
                t("正在运行的对话回合", "Conversation turns in progress"),
                () -> startActivity(new Intent(this, AgentTasksActivity.class))),
            new Link(
                R.drawable.ic_plugins,
                t("插件管理", "Plugin management"),
                t("安装、更新与检测", "Install, update and scan"),
                () -> main("open_plugins")),
            new Link(
                R.drawable.ic_terminal,
                t("终端", "Terminals"),
                t("会话与运行中的命令", "Sessions and running commands"),
                () -> main("open_terminal")),
            new Link(
                R.drawable.ic_recovery_database,
                t("数据与备份", "Data and backups"),
                t("导出、恢复与副本", "Export, restore and copies"),
                () -> startActivity(new Intent(this, NativeDataActivity.class))),
            new Link(
                R.drawable.ic_recovery_clock,
                t("自动备份", "Automatic backups"),
                t("计划与最近结果", "Schedule and recent results"),
                () -> startActivity(new Intent(this, AutomaticBackupActivity.class))),
            new Link(
                R.drawable.ic_section_maintenance,
                t("安装与环境", "Installation and environment"),
                t("运行环境状态与维护", "Runtime status and maintenance"),
                () -> main("open_install")));
    if (!twoColumns) {
      LinearLayout card = TransferUi.card(this, 0);
      for (int i = 0; i < links.size(); i++) {
        if (i > 0) card.addView(TransferUi.divider(this, 62));
        card.addView(linkRow(links.get(i)));
      }
      return card;
    }
    // 平板：两列等宽卡片，每格一个入口。
    LinearLayout grid = TransferUi.vertical(this);
    for (int i = 0; i < links.size(); i += 2) {
      LinearLayout pair = TransferUi.horizontal(this);
      pair.setGravity(Gravity.TOP);
      pair.setBaselineAligned(false);
      for (int j = i; j < Math.min(i + 2, links.size()); j++) {
        LinearLayout cell = TransferUi.card(this, 0);
        cell.addView(linkRow(links.get(j)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (j > i) params.setMarginStart(dp(12));
        pair.addView(cell, params);
      }
      grid.addView(pair, TransferUi.match(this, i == 0 ? 0 : 12));
    }
    return grid;
  }

  private View linkRow(Link link) {
    LinearLayout line = TransferUi.horizontal(this);
    line.setMinimumHeight(dp(60));
    line.setPadding(dp(14), dp(10), dp(10), dp(10));
    line.setBackgroundResource(R.drawable.bg_transfer_row);
    line.setClickable(true);
    line.setFocusable(true);
    line.setOnClickListener(v -> link.action.run());
    line.addView(tile(link.icon, R.color.shell_text_secondary, R.color.shell_raised));
    LinearLayout texts = TransferUi.vertical(this);
    texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    TextView name = TransferUi.text(this, link.title, 14.5f, R.color.shell_text, false);
    name.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(name);
    TextView sub = TransferUi.text(this, link.subtitle, 12, R.color.shell_text_muted, false);
    sub.setMaxLines(2);
    sub.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(sub, TransferUi.match(this, 3));
    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
    textParams.setMarginStart(dp(12));
    line.addView(texts, textParams);
    line.addView(chevron());
    line.setContentDescription(link.title + ", " + link.subtitle);
    return line;
  }

  // ---------------------------------------------------------------- 公共小件

  /** 36dp 圆角方块：浅底 + 着色图标。 */
  private View tile(int drawable, int iconColor, int backgroundColor) {
    ImageView view = TransferUi.icon(this, drawable, iconColor, 36);
    view.setBackgroundResource(R.drawable.bg_transfer_tile);
    view.setBackgroundTintList(ColorStateList.valueOf(getColor(backgroundColor)));
    int pad = dp(8);
    view.setPadding(pad, pad, pad, pad);
    return view;
  }

  private View chevron() {
    ImageView view =
        TransferUi.icon(this, R.drawable.ic_transfer_chevron, R.color.shell_text_muted, 18);
    view.setRotation(-90f);
    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
    params.setMarginStart(dp(8));
    return view;
  }

  private void open(BackgroundTaskModel.Destination destination) {
    switch (Objects.requireNonNull(destination)) {
      case MAINTENANCE_DETAIL ->
          startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true));
      case DATA -> startActivity(new Intent(this, NativeDataActivity.class));
      case TERMINAL -> main("open_terminal");
      case PLUGINS -> main("open_plugins");
      case INSTALL -> main("open_install");
      case NONE -> {}
    }
  }

  private void main(String extra) {
    startActivity(MainActivity.navigationIntent(this).putExtra(extra, true));
    finish();
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(refresh);
    super.onDestroy();
  }
}
