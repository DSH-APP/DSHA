package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.transfer.DownloadTask;
import com.deepseekharness.app.util.TransferListModel;
import com.deepseekharness.app.util.TransferListModel.Action;
import com.deepseekharness.app.util.TransferListModel.Failure;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.List;

/**
 * 记录详情：文件头 → 状态卡（三步进度）→ 主操作 → 保存位置 + 文件信息 → 管理区。
 *
 * <p>手机与平板竖屏作为二级页，平板横屏放在右侧面板；进度变化只刷新数字与进度条，不重建视图。
 */
final class TransferDetailView {
  interface Host {
    void act(Action action, DownloadTask task, View source);

    void systemDownloads();

    void copy(String label, String value);

    boolean techExpanded();

    void setTechExpanded(boolean expanded);
  }

  private final Context context;
  private final Host host;
  final LinearLayout root;
  private TextView bigPercent, bigBytes, speed, eta;
  private LinearProgressIndicator bar;

  TransferDetailView(Context context, Host host, DownloadTask task, boolean wide) {
    this.context = context;
    this.host = host;
    root = TransferUi.vertical(context);
    root.addView(hero(task));
    root.addView(stateCard(task), TransferUi.match(context, 14));
    View primary = primaryActions(task);
    if (primary != null) root.addView(primary, TransferUi.match(context, 12));
    View location = locationCard(task), file = fileCard(task);
    if (wide) {
      LinearLayout columns = TransferUi.horizontal(context);
      columns.setGravity(Gravity.TOP);
      LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
      LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
      right.setMarginStart(TransferUi.dp(context, 12));
      columns.addView(location, left);
      columns.addView(file, right);
      root.addView(columns, TransferUi.match(context, 12));
    } else {
      root.addView(location, TransferUi.match(context, 12));
      root.addView(file, TransferUi.match(context, 12));
    }
    root.addView(manageCard(task), TransferUi.match(context, 12));
  }

  /** 进度、速度与剩余时间的就地刷新；状态变化由调用方重建。 */
  void refresh(DownloadTask task) {
    int percent = TransferListModel.percent(task.downloaded, task.total);
    if (bigPercent != null) bigPercent.setText(percent < 0 ? "—" : percent + "%");
    if (bigBytes != null)
      bigBytes.setText(
          TransferText.pair(
              TransferText.size(context, Math.max(0, task.downloaded)),
              TransferText.size(context, task.total)));
    if (speed != null)
      speed.setText(
          task.speedBytesPerSecond > 0
              ? TransferText.f("%s/秒", "%s/s", TransferText.size(context, task.speedBytesPerSecond))
              : "");
    if (eta != null)
      eta.setText(
          TransferText.eta(
              TransferListModel.etaSeconds(task.downloaded, task.total, task.speedBytesPerSecond)));
    if (bar != null && !bar.isIndeterminate() && percent >= 0) bar.setProgressCompat(percent, true);
  }

  private View hero(DownloadTask task) {
    LinearLayout row = TransferUi.horizontal(context);
    row.setPadding(TransferUi.dp(context, 4), TransferUi.dp(context, 4), 0, 0);
    ImageView tile = TransferUi.tile(context, TransferText.name(task), 60);
    row.addView(tile);
    LinearLayout texts = TransferUi.vertical(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(TransferUi.dp(context, 14));
    row.addView(texts, params);
    TextView name = TransferUi.text(context, TransferText.name(task), 19, R.color.shell_text, true);
    name.setTextIsSelectable(true);
    name.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
    name.setAccessibilityHeading(true);
    texts.addView(name);
    LinearLayout sub = TransferUi.horizontal(context);
    sub.addView(chip(task.status));
    String ext = TransferListModel.extension(TransferText.name(task));
    String size = task.total >= 0 ? TransferText.size(context, task.total) : "";
    String meta = ext.isEmpty() ? size : size.isEmpty() ? ext : TransferText.join(ext, size);
    if (!meta.isEmpty()) {
      TextView info = TransferUi.text(context, meta, 13, R.color.shell_text_secondary, false);
      info.setSingleLine(true);
      info.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1);
      infoParams.setMarginStart(TransferUi.dp(context, 8));
      sub.addView(info, infoParams);
    }
    texts.addView(sub, TransferUi.match(context, 8));
    return row;
  }

  private TextView chip(DownloadTask.Status status) {
    TransferText.Tone tone = TransferText.tone(status);
    TextView chip =
        TransferUi.text(context, TransferText.status(status), 12, R.color.shell_text, true);
    chip.setTextColor(TransferUi.toneColor(context, tone));
    chip.setBackgroundResource(R.drawable.bg_transfer_chip);
    chip.setBackgroundTintList(ColorStateList.valueOf(TransferUi.toneSoft(context, tone)));
    chip.setPadding(
        TransferUi.dp(context, 10),
        TransferUi.dp(context, 5),
        TransferUi.dp(context, 10),
        TransferUi.dp(context, 5));
    chip.setSingleLine(true);
    return chip;
  }

  private View stateHead(TransferText.Tone tone, int icon, String title) {
    LinearLayout row = TransferUi.horizontal(context);
    ImageView ring = new ImageView(context);
    ring.setImageResource(icon);
    ring.setImageTintList(ColorStateList.valueOf(TransferUi.toneColor(context, tone)));
    ring.setBackgroundResource(R.drawable.bg_transfer_ring);
    ring.setBackgroundTintList(ColorStateList.valueOf(TransferUi.toneSoft(context, tone)));
    int pad = TransferUi.dp(context, 7);
    ring.setPadding(pad, pad, pad, pad);
    ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(
        ring,
        new LinearLayout.LayoutParams(TransferUi.dp(context, 32), TransferUi.dp(context, 32)));
    TextView label = TransferUi.text(context, title, 15.5f, R.color.shell_text, true);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(TransferUi.dp(context, 10));
    row.addView(label, params);
    return row;
  }

  private TextView body(String value) {
    TextView text = TransferUi.text(context, value, 13.5f, R.color.shell_text_secondary, false);
    text.setLineSpacing(TransferUi.dp(context, 4), 1f);
    return text;
  }

  private View stateCard(DownloadTask task) {
    LinearLayout card = TransferUi.card(context, 16);
    Failure failure =
        task.status == DownloadTask.Status.FAILED ? TransferListModel.failure(task.message) : null;
    switch (task.status) {
      case COMPLETE -> {
        card.addView(
            stateHead(
                TransferText.Tone.GOOD,
                R.drawable.ic_onb_check,
                TransferText.t("已保存并通过校验", "Saved and verified")));
        String where = TransferText.location(task);
        card.addView(
            body(
                TransferListModel.renamed(task.displayName, task.artifact.name)
                    ? TransferText.f(
                        "已保存到 %s（同名文件已改名）",
                        "Saved to %s. A file with the same name existed, so it was renamed.", where)
                    : TransferText.f("已保存到 %s。", "Saved to %s.", where)),
            TransferUi.match(context, 8));
      }
      case DOWNLOADING, PAUSED -> {
        LinearLayout big = TransferUi.horizontal(context);
        big.setGravity(Gravity.BOTTOM);
        bigPercent = TransferUi.text(context, "", 34, R.color.shell_text, true);
        bigPercent.setLetterSpacing(-0.02f);
        bigPercent.setTextColor(
            context.getColor(
                task.status == DownloadTask.Status.PAUSED
                    ? R.color.shell_warn
                    : R.color.shell_text));
        big.addView(bigPercent);
        bigBytes = TransferUi.text(context, "", 13.5f, R.color.shell_text_secondary, false);
        LinearLayout.LayoutParams bytes = new LinearLayout.LayoutParams(0, -2, 1);
        bytes.setMarginStart(TransferUi.dp(context, 10));
        bytes.bottomMargin = TransferUi.dp(context, 4);
        big.addView(bigBytes, bytes);
        card.addView(big);
        card.addView(progress(task), TransferUi.match(context, 12));
        if (task.status == DownloadTask.Status.DOWNLOADING) {
          LinearLayout nums = TransferUi.horizontal(context);
          speed = TransferUi.text(context, "", 12.5f, R.color.shell_text_secondary, false);
          eta = TransferUi.text(context, "", 12.5f, R.color.shell_text_secondary, false);
          eta.setGravity(Gravity.END);
          nums.addView(speed, new LinearLayout.LayoutParams(0, -2, 1));
          nums.addView(eta, new LinearLayout.LayoutParams(0, -2, 1));
          card.addView(nums, TransferUi.match(context, 8));
        } else {
          card.addView(body(pausedText(task)), TransferUi.match(context, 10));
        }
      }
      case QUEUED -> {
        card.addView(
            stateHead(
                TransferText.Tone.ACCENT,
                R.drawable.ic_transfer_download,
                TransferText.t("排队中", "Queued")));
        card.addView(
            body(
                TransferText.f(
                    "前一个任务完成后自动开始，共 %s。",
                    "Starts automatically after the current task. Size: %s.",
                    TransferText.size(context, task.total))),
            TransferUi.match(context, 8));
        card.addView(progress(task), TransferUi.match(context, 12));
      }
      case VERIFYING -> {
        card.addView(
            stateHead(
                TransferText.Tone.ACCENT,
                R.drawable.ic_onb_check,
                TransferText.t("正在校验完整性…", "Verifying integrity…")));
        card.addView(progress(task), TransferUi.match(context, 12));
        card.addView(
            body(
                TransferText.t(
                    "校验通过后才会写入保存位置。", "The file is written only after verification passes.")),
            TransferUi.match(context, 10));
      }
      case PUBLISHING -> {
        card.addView(
            stateHead(
                TransferText.Tone.ACCENT,
                R.drawable.ic_onb_folder,
                TransferText.t("正在保存…", "Saving…")));
        card.addView(progress(task), TransferUi.match(context, 12));
        card.addView(
            body(TransferText.f("正在写入 %s。", "Writing to %s.", TransferText.location(task))),
            TransferUi.match(context, 10));
      }
      case WAITING_DESTINATION -> {
        card.addView(
            stateHead(
                TransferText.Tone.ACCENT,
                R.drawable.ic_onb_folder,
                TransferText.t("已通过校验，请选择保存位置", "Verified. Choose a destination")));
        card.addView(
            body(
                TransferText.t(
                    "选好位置才写入，取消不丢记录",
                    "Nothing is written until you choose. Cancelling the picker keeps this record so you can choose later.")),
            TransferUi.match(context, 8));
      }
      case FAILED -> {
        String title = TransferText.failureTitle(failure);
        String body = TransferText.failureBody(failure, task.message);
        if (body.startsWith(title + "。")) body = body.substring(title.length() + 1);
        else if (body.startsWith(title + ". ")) body = body.substring(title.length() + 2);
        card.addView(stateHead(TransferText.Tone.BAD, R.drawable.ic_onb_alert, title));
        card.addView(body(body), TransferUi.match(context, 8));
        if (task.downloaded > 0 && failure != Failure.INTEGRITY && failure != Failure.LENGTH)
          card.addView(
              body(
                  TransferText.f(
                      "已下载 %s，重试时从断点继续。",
                      "%s downloaded. Retry resumes from there.",
                      TransferText.size(context, task.downloaded))),
              TransferUi.match(context, 6));
      }
      case CANCELLED -> {
        card.addView(
            stateHead(
                TransferText.Tone.MUTED, R.drawable.ic_onb_x, TransferText.t("已取消", "Cancelled")));
        card.addView(
            body(
                TransferText.t(
                    "需要再次下载请从网页重新发起。",
                    "To download again, start a new download from the web app.")),
            TransferUi.match(context, 8));
      }
    }
    TransferListModel.Step[] steps = TransferListModel.steps(task.status.name(), failure);
    if (steps.length > 0) {
      View line = TransferUi.divider(context, 0);
      LinearLayout.LayoutParams lineParams = (LinearLayout.LayoutParams) line.getLayoutParams();
      lineParams.topMargin = TransferUi.dp(context, 16);
      card.addView(line, lineParams);
      TransferStepsView view = new TransferStepsView(context);
      String[] labels = {
        TransferText.t("下载文件", "Download"),
        TransferText.t("校验", "Verify"),
        TransferText.t("保存", "Save")
      };
      view.setSteps(steps, labels, stepsDescription(steps, labels));
      card.addView(view, TransferUi.match(context, 14));
    }
    refresh(task);
    return card;
  }

  private String pausedText(DownloadTask task) {
    String reason = task.message == null ? "" : task.message.trim();
    if (reason.isEmpty() || reason.startsWith("已暂停"))
      return TransferText.t(
          "已暂停。已下载的进度会保留，继续后从断点续传。",
          "Paused. Downloaded progress is kept and resumes from where it stopped.");
    return com.deepseekharness.app.util.UiText.text(
        com.deepseekharness.app.util.SensitiveData.redact(reason));
  }

  private String stepsDescription(TransferListModel.Step[] steps, String[] labels) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < steps.length; i++) {
      String state =
          switch (steps[i]) {
            case DONE -> TransferText.f("%s：已完成", "%s: done", labels[i]);
            case CURRENT -> TransferText.f("%s：进行中", "%s: in progress", labels[i]);
            case PAUSED -> TransferText.f("%s：已暂停", "%s: paused", labels[i]);
            case FAILED -> TransferText.f("%s：失败", "%s: failed", labels[i]);
            case TODO -> TransferText.f("%s：未开始", "%s: not started", labels[i]);
          };
      if (result.length() > 0) result.append('\n');
      result.append(state);
    }
    return result.toString();
  }

  private LinearProgressIndicator progress(DownloadTask task) {
    LinearProgressIndicator indicator = new LinearProgressIndicator(context);
    indicator.setTrackThickness(TransferUi.dp(context, 6));
    indicator.setTrackCornerRadius(TransferUi.dp(context, 3));
    indicator.setTrackColor(context.getColor(R.color.shell_raised));
    boolean indeterminate =
        task.status == DownloadTask.Status.QUEUED && task.downloaded <= 0
            || task.status == DownloadTask.Status.VERIFYING
            || task.status == DownloadTask.Status.PUBLISHING
            || task.total <= 0;
    indicator.setIndicatorColor(
        context.getColor(
            task.status == DownloadTask.Status.PAUSED ? R.color.shell_warn : R.color.shell_accent));
    indicator.setIndeterminate(indeterminate);
    indicator.setMax(100);
    if (!indeterminate)
      indicator.setProgressCompat(
          Math.max(0, TransferListModel.percent(task.downloaded, task.total)), false);
    indicator.setContentDescription(TransferText.status(task.status));
    bar = indicator;
    return indicator;
  }

  private View primaryActions(DownloadTask task) {
    List<Action> actions = TransferListModel.primary(task.status.name());
    if (actions.isEmpty()) return null;
    LinearLayout row = TransferUi.horizontal(context);
    for (int i = 0; i < actions.size(); i++) {
      Action action = actions.get(i);
      MaterialButton button =
          TransferUi.button(
              context,
              i == 0 ? TransferUi.ButtonStyle.FILLED : TransferUi.ButtonStyle.TONAL,
              TransferText.action(action, task.status),
              TransferUi.iconFor(action),
              null,
              52);
      button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
      button.setIconSize(TransferUi.dp(context, 20));
      button.setOnClickListener(v -> host.act(action, task, v));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) params.setMarginStart(TransferUi.dp(context, 10));
      row.addView(button, params);
    }
    return row;
  }

  private View locationCard(DownloadTask task) {
    LinearLayout card = TransferUi.card(context, 16);
    card.addView(TransferUi.sectionLabel(context, TransferText.t("保存位置", "Saved location")));
    LinearLayout row = TransferUi.horizontal(context);
    ImageView tile =
        TransferUi.icon(context, R.drawable.ic_onb_folder, R.color.shell_text_secondary, 44);
    tile.setBackgroundResource(R.drawable.bg_transfer_tile);
    tile.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.shell_raised)));
    int pad = TransferUi.dp(context, 11);
    tile.setPadding(pad, pad, pad, pad);
    row.addView(tile);
    LinearLayout texts = TransferUi.vertical(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(TransferUi.dp(context, 12));
    row.addView(texts, params);
    boolean pending = TransferText.destinationPending(task);
    String where = pending ? TransferText.t("等待选择", "Not chosen") : TransferText.location(task);
    TextView path = TransferUi.text(context, where, 15, R.color.shell_text, true);
    path.setTextIsSelectable(!pending);
    texts.addView(path);
    texts.addView(
        TransferUi.text(
            context, TransferText.locationNote(task), 12.5f, R.color.shell_text_secondary, false),
        TransferUi.match(context, 4));
    card.addView(row, TransferUi.match(context, 12));
    PluginFlowRow actions = new PluginFlowRow(context);
    boolean any = false;
    if (task.status == DownloadTask.Status.COMPLETE && "downloads".equals(task.destination)) {
      MaterialButton list =
          TransferUi.button(
              context,
              TransferUi.ButtonStyle.OUTLINE,
              TransferText.t("系统下载列表", "System Downloads"),
              R.drawable.ic_transfer_download,
              null,
              40);
      list.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      list.setOnClickListener(v -> host.systemDownloads());
      actions.addView(list, new android.view.ViewGroup.LayoutParams(-2, -2));
      any = true;
    }
    if (!pending && !"share".equals(task.destination)) {
      MaterialButton copy =
          TransferUi.button(
              context,
              TransferUi.ButtonStyle.OUTLINE,
              TransferText.t("复制路径", "Copy path"),
              R.drawable.ic_ui_copy,
              null,
              40);
      copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      copy.setOnClickListener(v -> host.copy(TransferText.t("保存位置", "Saved location"), where));
      actions.addView(copy, new android.view.ViewGroup.LayoutParams(-2, -2));
      any = true;
    }
    if (any) card.addView(actions, TransferUi.match(context, 14));
    if (task.uri != null && !task.uri.isEmpty())
      card.addView(tech(task), TransferUi.match(context, 14));
    return card;
  }

  private View tech(DownloadTask task) {
    LinearLayout box = TransferUi.vertical(context);
    box.addView(TransferUi.divider(context, 0));
    LinearLayout header = TransferUi.horizontal(context);
    header.setMinimumHeight(TransferUi.dp(context, 48));
    header.setBackgroundResource(R.drawable.bg_plugin_icon_button);
    header.setClickable(true);
    header.setFocusable(true);
    TextView label =
        TransferUi.text(
            context,
            TransferText.t("技术信息", "Technical details"),
            13,
            R.color.shell_text_secondary,
            true);
    header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView chevron =
        TransferUi.icon(context, R.drawable.ic_transfer_chevron, R.color.shell_text_secondary, 18);
    header.addView(chevron);
    box.addView(header);
    LinearLayout values = TransferUi.vertical(context);
    values.addView(techRow(TransferText.t("文档 URI", "Document URI"), task.uri));
    if (task.artifact.sha256 != null && !task.artifact.sha256.isEmpty())
      values.addView(techRow("SHA-256", task.artifact.sha256), TransferUi.match(context, 10));
    values.addView(
        TransferUi.text(
            context,
            TransferText.t(
                "部分文件可能不在系统下载列表",
                "Android does not guarantee exact folder navigation from a document URI. Gallery files and files saved by other providers may not appear in the system Downloads list."),
            12,
            R.color.shell_text_muted,
            false),
        TransferUi.match(context, 10));
    box.addView(values);
    Runnable apply =
        () -> {
          boolean open = host.techExpanded();
          values.setVisibility(open ? View.VISIBLE : View.GONE);
          chevron.setRotation(open ? 180 : 0);
          header.setStateDescription(
              open ? TransferText.t("已展开", "Expanded") : TransferText.t("已收起", "Collapsed"));
        };
    header.setOnClickListener(
        v -> {
          host.setTechExpanded(!host.techExpanded());
          apply.run();
        });
    apply.run();
    return box;
  }

  private View techRow(String key, String value) {
    LinearLayout row = TransferUi.vertical(context);
    row.addView(TransferUi.text(context, key, 12, R.color.shell_text_muted, false));
    TextView text = TransferUi.text(context, value, 11.5f, R.color.shell_text_secondary, false);
    text.setTypeface(Typeface.MONOSPACE);
    text.setTextIsSelectable(true);
    text.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
    row.addView(text, TransferUi.match(context, 3));
    return row;
  }

  private View fileCard(DownloadTask task) {
    LinearLayout card = TransferUi.card(context, 16);
    card.addView(TransferUi.sectionLabel(context, TransferText.t("文件信息", "File info")));
    String mime =
        task.mime != null && task.mime.contains("/")
            ? task.mime
            : task.artifact.mime != null && task.artifact.mime.contains("/")
                ? task.artifact.mime
                : "application/octet-stream";
    card.addView(kv(TransferText.t("类型", "Type"), mime, null, true), TransferUi.match(context, 6));
    card.addView(
        kv(TransferText.t("大小", "Size"), TransferText.size(context, task.total), null, false));
    card.addView(
        kv(
            TransferText.t("创建时间", "Created"),
            TransferText.fullTime(context, task.createdAt),
            null,
            false));
    if (TransferListModel.renamed(task.displayName, task.artifact.name))
      card.addView(
          kv(
              TransferText.t("原文件名", "Original name"),
              task.artifact.name,
              TransferText.t("同名文件已存在，已自动改名", "Renamed because the name was taken"),
              false));
    return card;
  }

  private View kv(String key, String value, String note, boolean first) {
    LinearLayout box = TransferUi.vertical(context);
    if (!first) box.addView(TransferUi.divider(context, 0));
    LinearLayout row = TransferUi.horizontal(context);
    row.setGravity(Gravity.TOP);
    row.setPadding(0, TransferUi.dp(context, 11), 0, TransferUi.dp(context, 11));
    TextView label = TransferUi.text(context, key, 14, R.color.shell_text_secondary, false);
    row.addView(label, new LinearLayout.LayoutParams(-2, -2));
    LinearLayout right = TransferUi.vertical(context);
    right.setGravity(Gravity.END);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(TransferUi.dp(context, 16));
    row.addView(right, params);
    TextView text = TransferUi.text(context, value, 14, R.color.shell_text, true);
    text.setGravity(Gravity.END);
    text.setTextIsSelectable(true);
    text.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
    right.addView(text, new LinearLayout.LayoutParams(-1, -2));
    if (note != null) {
      TextView sub = TransferUi.text(context, note, 12, R.color.shell_text_muted, false);
      sub.setGravity(Gravity.END);
      right.addView(sub, TransferUi.match(context, 3));
    }
    box.addView(row);
    return box;
  }

  private View manageCard(DownloadTask task) {
    LinearLayout card = TransferUi.card(context, 0);
    card.setPadding(0, TransferUi.dp(context, 14), 0, TransferUi.dp(context, 6));
    TextView title = TransferUi.sectionLabel(context, TransferText.t("管理", "Manage"));
    title.setPadding(
        TransferUi.dp(context, 16), 0, TransferUi.dp(context, 16), TransferUi.dp(context, 4));
    card.addView(title);
    Action action = TransferListModel.manage(task.status.name());
    LinearLayout row = TransferUi.horizontal(context);
    row.setMinimumHeight(TransferUi.dp(context, 60));
    row.setPadding(
        TransferUi.dp(context, 16),
        TransferUi.dp(context, 10),
        TransferUi.dp(context, 16),
        TransferUi.dp(context, 10));
    row.setBackgroundResource(R.drawable.bg_transfer_danger_row);
    row.setClickable(true);
    row.setFocusable(true);
    row.addView(TransferUi.icon(context, TransferUi.iconFor(action), R.color.shell_bad, 22));
    LinearLayout texts = TransferUi.vertical(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(TransferUi.dp(context, 14));
    row.addView(texts, params);
    TextView label =
        TransferUi.text(
            context, TransferText.action(action, task.status), 14.5f, R.color.shell_bad, true);
    texts.addView(label);
    texts.addView(
        TransferUi.text(
            context, manageNote(action, task), 12.5f, R.color.shell_text_secondary, false),
        TransferUi.match(context, 3));
    row.setOnClickListener(v -> host.act(action, task, v));
    card.addView(row);
    return card;
  }

  private String manageNote(Action action, DownloadTask task) {
    if (action == Action.CANCEL)
      return TransferListModel.cancelDiscards(task.status.name(), task.downloaded)
          ? TransferText.f(
              "丢弃已下载的 %s，之后需从网页重新发起。",
              "Discards %s downloaded. Start again from the web app.",
              TransferText.size(context, task.downloaded))
          : TransferText.t(
              "停止这次文件操作，之后需从网页重新发起。", "Stops this file operation. Start again from the web app.");
    return task.status == DownloadTask.Status.COMPLETE
        ? TransferText.f(
            "只删除这条记录，文件仍保留在 %s。",
            "Removes only this record. The file stays in %s.", TransferText.location(task))
        : TransferText.t(
            "只删除这条记录，不影响其他文件。", "Removes only this record. Other files are not affected.");
  }
}
