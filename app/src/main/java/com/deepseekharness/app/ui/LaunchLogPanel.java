package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.StartupLogDigest;
import com.deepseekharness.app.util.StartupTrace;
import com.deepseekharness.app.util.UiText;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动页「启动日志」区的渲染协作者（docs/design/launch-log-20261101）。
 *
 * <ul>
 *   <li>概览：按 {@link StartupLogDigest} 折叠阶段，计时行降级为明细，问题行上浮并自动展开。
 *   <li>原始：时间槽 + 正文，按行分类着色；筛选只影响显示，复制 / 下载始终是完整原文。
 *   <li>跟随：沿用 {@link LogScrollView#shouldFollowEnd()}，暂停时框内浮标显示新日志数。
 * </ul>
 *
 * <p>只做展示，不改变启动任务、日志内容或诊断导出。
 */
final class LaunchLogPanel {
  private static final int MAX_STAGE_ITEMS = 40;

  private enum Filter {
    ALL,
    NO_TIMING,
    ISSUES
  }

  private final Context context;
  private final float density;
  private final LogScrollView scroll;
  private final LinearLayout overview;
  private final View rawPane;
  private final TextView raw;
  private final View rawBar, rawBarLine;
  private final TextView tabOverview, tabRaw;
  private final TextView chipAll, chipNoTiming, chipIssues, chipWrap;
  private final TextView meta, digestText, latest;
  private final View dot;
  private final ImageButton expand;

  private boolean rawMode, wrap = true, tall, follow = true, programmatic;
  private Filter filter = Filter.ALL;

  // 最近一次渲染的输入；同一输入不重建视图，只刷新运行中阶段的耗时。
  private StartupTrace.Snapshot snapshot;
  private StartupLogDigest.Outcome outcome = StartupLogDigest.Outcome.IDLE;
  private StartupLogDigest.Result digest;
  private String plain = "", note = "", language = "";
  private long overviewKey = Long.MIN_VALUE, rawKey = Long.MIN_VALUE, generation = -1;
  private int layoutVersion;
  private TextView runningDuration;
  private long runningStart;

  // 用户手动展开 / 收起的阶段（按阶段序号，换代清空）；未手动时运行中 / 有问题的阶段自动展开。
  private final Set<Integer> userToggled = new HashSet<>(), userOpen = new HashSet<>();

  // 暂停跟随时记下当时的 revision；之后的增量作为「新日志」数。
  private long pausedRevision = -1, pausedEntries;
  private int[] rawOffsets = new int[0];
  private int flashEntry = -1;

  LaunchLogPanel(View root) {
    context = root.getContext();
    density = context.getResources().getDisplayMetrics().density;
    scroll = root.findViewById(R.id.launch_log_scroll);
    overview = root.findViewById(R.id.launch_log_overview);
    rawPane = root.findViewById(R.id.launch_log_raw);
    raw = root.findViewById(R.id.launch_log);
    rawBar = root.findViewById(R.id.launch_log_rawbar);
    rawBarLine = root.findViewById(R.id.launch_log_rawbar_line);
    tabOverview = root.findViewById(R.id.launch_log_tab_overview);
    tabRaw = root.findViewById(R.id.launch_log_tab_raw);
    chipAll = root.findViewById(R.id.launch_log_filter_all);
    chipNoTiming = root.findViewById(R.id.launch_log_filter_notiming);
    chipIssues = root.findViewById(R.id.launch_log_filter_issues);
    chipWrap = root.findViewById(R.id.launch_log_wrap);
    meta = root.findViewById(R.id.launch_log_meta);
    digestText = root.findViewById(R.id.launch_log_digest);
    latest = root.findViewById(R.id.launch_latest_button);
    dot = root.findViewById(R.id.launch_log_dot);
    expand = root.findViewById(R.id.launch_log_expand);

    tabOverview.setOnClickListener(v -> setRawMode(false));
    tabRaw.setOnClickListener(v -> setRawMode(true));
    chipAll.setText(UiText.choose("全部", "All"));
    chipNoTiming.setText(UiText.choose("隐藏计时", "Hide timing"));
    chipIssues.setText(UiText.choose("仅问题", "Issues only"));
    chipWrap.setText(UiText.choose("换行", "Wrap"));
    chipAll.setOnClickListener(v -> setFilter(Filter.ALL));
    chipNoTiming.setOnClickListener(v -> setFilter(Filter.NO_TIMING));
    chipIssues.setOnClickListener(v -> setFilter(Filter.ISSUES));
    chipWrap.setOnClickListener(
        v -> {
          wrap = !wrap;
          rawKey = Long.MIN_VALUE;
          renderChips();
          renderRaw();
        });
    latest.setOnClickListener(v -> resumeFollow());
    expand.setOnClickListener(v -> setTall(!tall));
    // 原始视图「不换行」按实际宽度截断，宽度变化后重新截断。
    raw.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (!wrap && rawMode && r - l != or - ol) {
            rawKey = Long.MIN_VALUE;
            v.post(this::renderRaw);
          }
        });
    // 用户滚回末尾恢复跟随；离开末尾暂停，避免下一秒刷新把阅读位置拉走。
    scroll.setOnScrollChangeListener(
        (view, x, y, ox, oy) -> {
          if (programmatic) return;
          boolean atEnd = scroll.shouldFollowEnd();
          if (atEnd != follow) setFollow(atEnd);
        });
    renderTabs();
    renderChips();
    renderExpand();
    renderFollow();
  }

  /** 每次启动页刷新调用；输入未变化时只更新运行中阶段的耗时，不重建视图、不打断焦点。 */
  void render(
      StartupTrace.Snapshot trace,
      StartupLogDigest.Outcome result,
      String plainText,
      String rawNote) {
    if (trace == null) return;
    String lang = UiText.language();
    if (trace.generation != generation) {
      generation = trace.generation;
      userToggled.clear();
      userOpen.clear();
      flashEntry = -1;
    }
    boolean changed =
        snapshot == null
            || trace.revision != snapshot.revision
            || result != outcome
            || !lang.equals(language)
            || !note.equals(rawNote == null ? "" : rawNote);
    snapshot = trace;
    outcome = result == null ? StartupLogDigest.Outcome.IDLE : result;
    plain = plainText == null ? "" : plainText;
    note = rawNote == null ? "" : rawNote;
    language = lang;
    if (changed || digest == null)
      digest =
          StartupLogDigest.digest(trace.entries, outcome, StartupLogDigest.endMs(trace, outcome));
    boolean followNow = follow && scroll.shouldFollowEnd();
    if (!follow) renderFollow();
    renderMeta();
    renderTabs();
    renderDigestLine();
    if (rawMode) renderRaw();
    else renderOverview();
    if (followNow && changed) scroll.followEndAfterLayout();
  }

  /** 只有纯文本时（布局验收）直接显示原始视图；不改变用户当前选择以外的状态。 */
  void renderPlain(String text) {
    boolean followNow = follow && scroll.shouldFollowEnd();
    plain = text == null ? "" : text;
    snapshot = null;
    digest = null;
    rawMode = true;
    rawOffsets = new int[0];
    rawKey = Long.MIN_VALUE;
    raw.setText(plain);
    applyMode();
    renderTabs();
    if (followNow) scroll.followEndAfterLayout();
    else renderFollow();
  }

  /** 复制用的完整原文（含本机地址说明），与显示筛选无关。 */
  String plainText() {
    return plain;
  }

  void resumeFollow() {
    setFollow(true);
    programmatic = true;
    scroll.fullScroll(View.FOCUS_DOWN);
    programmatic = false;
    scroll.followEndAfterLayout();
  }

  // ---------------------------------------------------------------- 标题行

  private void renderMeta() {
    StartupTrace.Snapshot s = snapshot;
    int tone;
    String text;
    // 已结束态与概览占比条同口径：已就绪取浏览器 / 后端就绪时刻，不随面板刷新增长（T22）。
    long end = StartupLogDigest.endMs(s, outcome);
    switch (outcome) {
      case RUNNING:
        tone = R.color.shell_accent;
        text = (s.stage == null ? "" : s.stage) + " · " + seconds(s.elapsedMs);
        break;
      case READY:
        tone = digest.issueCount > 0 ? R.color.shell_warn : R.color.shell_good;
        text =
            digest.issueCount > 0
                ? UiText.format(
                    UiText.choose("已就绪 · 用时 %s · %s 项提示", "Ready · %s · %s notices"),
                    seconds(end),
                    digest.issueCount)
                : UiText.format(UiText.choose("已就绪 · 用时 %s", "Ready · %s"), seconds(end));
        break;
      case FAILED:
        tone = R.color.shell_bad;
        text = UiText.format(UiText.choose("启动失败 · %s", "Startup failed · %s"), seconds(end));
        break;
      case STOPPED:
        tone = R.color.shell_text_muted;
        text = UiText.choose("已停止", "Stopped");
        break;
      default:
        tone = R.color.shell_text_muted;
        text = UiText.choose("尚未启动", "Not started");
        break;
    }
    if (!text.contentEquals(meta.getText())) meta.setText(text);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(color(tone));
    dot.setBackground(circle);
  }

  private void renderTabs() {
    boolean hasDigest = digest != null;
    int stages = hasDigest ? digest.stages.size() : 0;
    int lines = hasDigest ? digest.lineCount : 0;
    int issues = hasDigest ? digest.issueCount : 0;
    int issueTone =
        hasDigest && digest.worst == StartupLogDigest.Severity.BAD
            ? R.color.shell_bad
            : R.color.shell_warn;
    tabOverview.setText(tabLabel(UiText.choose("概览", "Overview"), String.valueOf(stages), 0));
    tabRaw.setText(
        tabLabel(
            UiText.choose("原始", "Raw"),
            issues > 0
                ? UiText.format(UiText.choose("%s 问题", "%s issues"), issues)
                : String.valueOf(lines),
            issues > 0 ? issueTone : 0));
    tabOverview.setContentDescription(
        UiText.format(UiText.choose("概览，%s 个阶段", "Overview, %s stages"), stages));
    tabRaw.setContentDescription(
        issues > 0
            ? UiText.format(
                UiText.choose("原始日志，%s 行，%s 个问题", "Raw log, %s lines, %s issues"), lines, issues)
            : UiText.format(UiText.choose("原始日志，%s 行", "Raw log, %s lines"), lines));
    tabOverview.setSelected(!rawMode);
    tabRaw.setSelected(rawMode);
    tabOverview.setTextColor(color(rawMode ? R.color.shell_text_secondary : R.color.shell_text));
    tabRaw.setTextColor(color(rawMode ? R.color.shell_text : R.color.shell_text_secondary));
  }

  private CharSequence tabLabel(String name, String count, int tone) {
    SpannableStringBuilder label = new SpannableStringBuilder(name).append("  ");
    int start = label.length();
    label.append(count);
    int fg = tone == 0 ? R.color.shell_text_secondary : tone;
    label.setSpan(
        new BadgeSpan(color(fg), withAlpha(color(fg), tone == 0 ? 0x22 : 0x1F), density),
        start,
        label.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return label;
  }

  private void renderDigestLine() {
    if (digest == null) {
      digestText.setText("");
      return;
    }
    String text;
    if (rawMode) {
      int shown = shownCount();
      text =
          filter == Filter.ALL
              ? UiText.format(UiText.choose("%s 行原文", "%s raw lines"), digest.lineCount)
              : UiText.format(
                  UiText.choose("显示 %s / %s 行", "Showing %s of %s lines"), shown, digest.lineCount);
    } else {
      text =
          UiText.format(
              UiText.choose(
                  "%s 行原文 → %s 个阶段 · 计时 %s 条归入明细", "%s lines → %s stages · %s timings in details"),
              digest.lineCount,
              digest.stages.size(),
              digest.timingCount);
    }
    if (!text.contentEquals(digestText.getText())) digestText.setText(text);
  }

  // ---------------------------------------------------------------- 模式与跟随

  private void setRawMode(boolean value) {
    if (snapshot == null && !value) return;
    rawMode = value;
    follow = true;
    pausedRevision = -1;
    applyMode();
    renderTabs();
    renderDigestLine();
    if (rawMode) renderRaw();
    else renderOverview();
    renderFollow();
    scroll.followEndAfterLayout();
  }

  private void applyMode() {
    overview.setVisibility(rawMode ? View.GONE : View.VISIBLE);
    rawPane.setVisibility(rawMode ? View.VISIBLE : View.GONE);
    rawBar.setVisibility(rawMode && snapshot != null ? View.VISIBLE : View.GONE);
    rawBarLine.setVisibility(rawBar.getVisibility());
  }

  private void setFilter(Filter value) {
    filter = value;
    rawKey = Long.MIN_VALUE;
    renderChips();
    renderRaw();
    renderDigestLine();
  }

  private void renderChips() {
    chip(chipAll, filter == Filter.ALL);
    chip(chipNoTiming, filter == Filter.NO_TIMING);
    chip(chipIssues, filter == Filter.ISSUES);
    chip(chipWrap, wrap);
  }

  private void chip(TextView chip, boolean on) {
    chip.setActivated(on);
    chip.setSelected(on);
    chip.setTextColor(color(on ? R.color.shell_accent : R.color.shell_text_secondary));
    chip.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
  }

  private void setTall(boolean value) {
    tall = value;
    scroll.setTall(value);
    renderExpand();
  }

  private void renderExpand() {
    expand.setImageResource(
        tall ? R.drawable.ic_launch_log_collapse : R.drawable.ic_launch_log_expand);
    expand.setContentDescription(
        tall
            ? UiText.choose("收起日志区域", "Collapse log area")
            : UiText.choose("展开日志区域", "Expand log area"));
  }

  private void setFollow(boolean value) {
    follow = value;
    if (value) pausedRevision = -1;
    else if (pausedRevision < 0) {
      pausedRevision = snapshot == null ? 0 : snapshot.revision;
      pausedEntries = snapshot == null ? 0 : snapshot.entries.size();
    }
    renderFollow();
  }

  private void renderFollow() {
    if (follow) {
      latest.setVisibility(View.GONE);
      return;
    }
    long fresh = 0;
    if (snapshot != null && pausedRevision >= 0 && snapshot.revision != pausedRevision)
      fresh = Math.max(0, snapshot.entries.size() - pausedEntries);
    latest.setText(
        fresh > 0
            ? UiText.format(UiText.choose("%s 条新日志", "%s new lines"), fresh)
            : UiText.choose("回到最新", "Back to latest"));
    latest.setContentDescription(UiText.choose("跳到最新并恢复跟随", "Jump to latest and resume following"));
    android.graphics.drawable.Drawable arrow =
        context.getDrawable(R.drawable.ic_launch_log_latest).mutate();
    int size = Math.round(13 * density);
    arrow.setBounds(0, 0, size, size);
    arrow.setTint(color(R.color.shell_accent));
    latest.setCompoundDrawablesRelative(arrow, null, null, null);
    latest.setVisibility(View.VISIBLE);
  }

  // ---------------------------------------------------------------- 概览

  private void renderOverview() {
    applyMode();
    if (snapshot == null) return;
    long key =
        snapshot.revision * 31
            + outcome.ordinal() * 7L
            + language.hashCode()
            + layoutVersion * 131L;
    if (key == overviewKey) {
      tickRunning();
      return;
    }
    overviewKey = key;
    overview.removeAllViews();
    runningDuration = null;
    if (digest.stages.isEmpty()) {
      overview.addView(
          empty(
              UiText.choose(
                  "还没有日志。点按「启动」后这里按阶段显示。", "No log yet. Stages appear here after you start.")));
      return;
    }
    long total = Math.max(1, StartupLogDigest.endMs(snapshot, outcome));
    for (int i = 0; i < digest.stages.size(); i++)
      overview.addView(stageRow(i, digest.stages.get(i), total, i == digest.stages.size() - 1));
  }

  private void tickRunning() {
    if (runningDuration == null || outcome != StartupLogDigest.Outcome.RUNNING) return;
    String value = duration(Math.max(0, snapshot.elapsedMs - runningStart));
    if (!value.contentEquals(runningDuration.getText())) runningDuration.setText(value);
  }

  private View stageRow(int index, StartupLogDigest.Stage stage, long total, boolean last) {
    LinearLayout block = new LinearLayout(context);
    block.setOrientation(LinearLayout.VERTICAL);
    boolean auto =
        stage.state == StartupLogDigest.State.RUNNING
            || stage.state == StartupLogDigest.State.WARN
            || stage.state == StartupLogDigest.State.BAD;
    boolean open = userToggled.contains(index) ? userOpen.contains(index) : auto;
    boolean expandable = !stage.items.isEmpty();

    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(12), dp(9), dp(12), dp(7));
    header.setMinimumHeight(dp(48));
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setBackgroundResource(selectable());
    LinearLayout line = new LinearLayout(context);
    line.setOrientation(LinearLayout.HORIZONTAL);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.addView(stateIcon(stage.state), new LinearLayout.LayoutParams(dp(18), dp(18)));
    TextView name = text(stageName(stage), 13, R.color.shell_text);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
    nameLp.setMarginStart(dp(10));
    line.addView(name, nameLp);
    TextView dur = mono(duration(stage.durationMs), 11.5f, durationTone(stage.state));
    LinearLayout.LayoutParams durLp = new LinearLayout.LayoutParams(-2, -2);
    durLp.setMarginStart(dp(10));
    line.addView(dur, durLp);
    if (stage.state == StartupLogDigest.State.RUNNING && last) {
      runningDuration = dur;
      runningStart = stage.startMs;
    }
    ImageView chevron = new ImageView(context);
    chevron.setImageResource(R.drawable.ic_launch_log_chevron);
    chevron.setImageTintList(ColorStateList.valueOf(color(R.color.shell_text_muted)));
    chevron.setRotation(open ? 90 : 0);
    chevron.setVisibility(expandable ? View.VISIBLE : View.INVISIBLE);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(14), dp(14));
    chevLp.setMarginStart(dp(10));
    line.addView(chevron, chevLp);
    header.addView(line);
    // 占比条：与阶段名对齐，宽度按本阶段占总用时比例。
    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(3));
    barLp.topMargin = dp(5);
    barLp.setMarginStart(dp(28));
    barLp.setMarginEnd(dp(24));
    header.addView(
        bar(
            stage.durationMs < 0 ? 0f : Math.min(1f, stage.durationMs / (float) total),
            barTone(stage.state),
            stage.state == StartupLogDigest.State.WARN || stage.state == StartupLogDigest.State.BAD
                ? 0xCC
                : 0x8C),
        barLp);
    String word = stateWord(stage.state);
    String dSpoken = duration(stage.durationMs);
    header.setContentDescription(
        dSpoken.isEmpty()
            ? UiText.format(UiText.choose("%s，%s", "%s, %s"), stageName(stage), word)
            : UiText.format(
                UiText.choose("%s，%s，%s", "%s, %s, %s"), stageName(stage), word, dSpoken));
    block.addView(header);

    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(40), 0, dp(12), dp(10));
    body.setVisibility(open && expandable ? View.VISIBLE : View.GONE);
    if (expandable) fillStageBody(body, stage);
    block.addView(body);
    if (expandable) {
      header.setStateDescription(
          open ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
      header.setOnClickListener(
          v -> {
            boolean next = body.getVisibility() != View.VISIBLE;
            userToggled.add(index);
            if (next) userOpen.add(index);
            else userOpen.remove(index);
            body.setVisibility(next ? View.VISIBLE : View.GONE);
            chevron.setRotation(next ? 90 : 0);
            header.setStateDescription(
                next ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
            if (follow) scroll.followEndAfterLayout();
          });
    } else {
      header.setClickable(false);
      header.setBackground(null);
    }
    if (!last) {
      View divider = new View(context);
      divider.setBackgroundColor(color(R.color.shell_line));
      block.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    return block;
  }

  private void fillStageBody(LinearLayout body, StartupLogDigest.Stage stage) {
    List<StartupLogDigest.Item> items = stage.items;
    int from = Math.max(0, items.size() - MAX_STAGE_ITEMS);
    if (from > 0)
      body.addView(
          text(
              UiText.format(
                  UiText.choose(
                      "较早的 %s 行已折叠，可在原始日志中查看", "%s earlier lines folded; see the raw log"),
                  from),
              11.5f,
              R.color.shell_text_muted));
    long maxTiming = Math.max(1, stage.durationMs);
    for (StartupLogDigest.Item item : items)
      if (item.kind == StartupLogDigest.ItemKind.TIMING)
        maxTiming = Math.max(maxTiming, item.valueMs);
    for (int i = from; i < items.size(); i++) body.addView(itemRow(items.get(i), maxTiming));
    TextView link =
        text(
            UiText.format(
                UiText.choose("在原始日志中查看 %s 行 ›", "View %s lines in raw log ›"), items.size()),
            11.5f,
            R.color.shell_accent);
    link.setPadding(0, dp(10), 0, dp(10));
    link.setMinHeight(dp(40));
    link.setGravity(Gravity.CENTER_VERTICAL);
    link.setBackgroundResource(selectable());
    int target = stage.items.get(0).entryIndex;
    link.setOnClickListener(v -> jumpToEntry(target));
    body.addView(link);
  }

  private View itemRow(StartupLogDigest.Item item, long maxTiming) {
    if (item.kind == StartupLogDigest.ItemKind.ISSUE) {
      boolean bad = item.severity == StartupLogDigest.Severity.BAD;
      TextView issue = text(item.label, 12, bad ? R.color.shell_bad : R.color.shell_warn);
      issue.setPadding(dp(9), dp(7), dp(9), dp(7));
      issue.setLineSpacing(0, 1.15f);
      GradientDrawable bg = new GradientDrawable();
      bg.setCornerRadius(dp(10));
      bg.setColor(color(bad ? R.color.shell_bad_soft : R.color.shell_warn_soft));
      issue.setBackground(bg);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.topMargin = dp(4);
      lp.bottomMargin = dp(2);
      issue.setLayoutParams(lp);
      return issue;
    }
    boolean timing = item.kind == StartupLogDigest.ItemKind.TIMING;
    int tone =
        item.severity == StartupLogDigest.Severity.BAD
            ? R.color.shell_bad
            : item.severity == StartupLogDigest.Severity.WARN
                ? R.color.shell_warn
                : R.color.shell_text_secondary;
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(0, dp(3), 0, dp(3));
    LinearLayout top = new LinearLayout(context);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);
    String label =
        timing && item.count >= 0
            ? item.label + UiText.format(UiText.choose(" · %s 项", " · %s entries"), item.count)
            : item.label;
    TextView k = timing ? mono(label, 11.5f, tone) : text(label, 12, tone);
    k.setSingleLine(true);
    k.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView v =
        mono(
            timing ? duration(item.valueMs) : "+" + seconds(item.valueMs),
            11,
            item.severity == StartupLogDigest.Severity.NONE ? R.color.shell_text_muted : tone);
    if (!timing)
      v.setContentDescription(
          UiText.format(UiText.choose("阶段开始后 %s", "%s after stage start"), seconds(item.valueMs)));
    LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-2, -2);
    vLp.setMarginStart(dp(10));
    top.addView(v, vLp);
    row.addView(top);
    if (timing) {
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(2));
      barLp.topMargin = dp(3);
      row.addView(
          bar(
              Math.max(0.03f, Math.min(1f, item.valueMs / (float) maxTiming)),
              item.severity == StartupLogDigest.Severity.NONE ? R.color.shell_text_muted : tone,
              0x73),
          barLp);
    }
    return row;
  }

  // ---------------------------------------------------------------- 原始

  private void renderRaw() {
    applyMode();
    if (snapshot == null) return;
    long key =
        snapshot.revision * 31
            + language.hashCode()
            + note.hashCode() * 17L
            + filter.ordinal() * 101L
            + (wrap ? 7 : 0)
            + flashEntry * 13L
            + (wrap ? 0 : raw.getWidth() * 3L);
    if (key == rawKey) return;
    rawKey = key;
    List<StartupTrace.Entry> entries = snapshot.entries;
    SpannableStringBuilder text = new SpannableStringBuilder();
    rawOffsets = new int[entries.size()];
    java.util.Arrays.fill(rawOffsets, -1);
    Paint paint = raw.getPaint();
    String widest = slot(entries.isEmpty() ? 0 : entries.get(entries.size() - 1).atMs);
    int slotChars = Math.max(4, widest.length());
    int indent = Math.round(paint.measureText(repeat(' ', slotChars + 1)));
    int available = raw.getWidth() - raw.getPaddingLeft() - raw.getPaddingRight() - indent;
    int shown = 0;
    for (int i = 0; i < entries.size(); i++) {
      StartupLogDigest.LineClass cls = digest.lineClasses.get(i);
      if (filter == Filter.NO_TIMING && cls == StartupLogDigest.LineClass.TIMING) continue;
      if (filter == Filter.ISSUES
          && cls != StartupLogDigest.LineClass.WARN
          && cls != StartupLogDigest.LineClass.BAD) continue;
      StartupTrace.Entry entry = entries.get(i);
      if (text.length() > 0) text.append('\n');
      int start = text.length();
      rawOffsets[i] = start;
      String s = slot(entry.atMs);
      text.append(repeat(' ', Math.max(0, slotChars - s.length()))).append(s).append(' ');
      int bodyStart = text.length();
      text.setSpan(
          new ForegroundColorSpan(withAlpha(color(R.color.shell_text_muted), 0xBF)),
          start,
          bodyStart,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      String body = rawBody(entry);
      CharSequence shownBody =
          !wrap && available > 0
              ? TextUtils.ellipsize(
                  body.replace('\n', ' '), raw.getPaint(), available, TextUtils.TruncateAt.END)
              : body;
      text.append(shownBody);
      int end = text.length();
      highlight(text, bodyStart, end, cls);
      text.setSpan(
          new LeadingMarginSpan.Standard(0, indent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (cls == StartupLogDigest.LineClass.WARN
          || cls == StartupLogDigest.LineClass.BAD
          || i == flashEntry)
        text.setSpan(
            new BackgroundColorSpan(
                color(
                    i == flashEntry
                        ? R.color.shell_accent_soft
                        : cls == StartupLogDigest.LineClass.BAD
                            ? R.color.shell_bad_soft
                            : R.color.shell_warn_soft)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      shown++;
    }
    if (shown == 0)
      text.append(
          filter == Filter.ISSUES
              ? UiText.choose("没有失败或跳过的行。", "No failed or skipped lines.")
              : UiText.choose("还没有日志。", "No log yet."));
    if (!note.isEmpty()) text.append("\n\n").append(note);
    raw.setText(text);
    raw.setHorizontallyScrolling(false);
  }

  /** 原始正文：阶段行用与原文一致的完整句子，其余去掉时间前缀。 */
  private static String rawBody(StartupTrace.Entry entry) {
    if (entry.kind == StartupTrace.Entry.OUTPUT || entry.kind == StartupTrace.Entry.OWNED)
      return entry.body;
    int close = entry.line.indexOf("] ");
    return close >= 0 ? entry.line.substring(close + 2) : entry.line;
  }

  private void highlight(
      SpannableStringBuilder text, int start, int end, StartupLogDigest.LineClass cls) {
    int tone;
    switch (cls) {
      case STAGE:
        tone = R.color.shell_text;
        break;
      case TIMING:
        tone = R.color.shell_text_muted;
        break;
      case WARN:
        tone = R.color.shell_warn;
        break;
      case BAD:
        tone = R.color.shell_bad;
        break;
      default:
        tone = R.color.shell_text_secondary;
        break;
    }
    text.setSpan(
        new ForegroundColorSpan(color(tone)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (cls == StartupLogDigest.LineClass.WARN || cls == StartupLogDigest.LineClass.BAD) return;
    // 大写标签（CAS_TIMING: / RUNTIME_STAGE: 等）用强调色，便于扫读。
    int i = start;
    while (i < end
        && (Character.isUpperCase(text.charAt(i))
            || Character.isDigit(text.charAt(i))
            || text.charAt(i) == '_')) i++;
    if (i - start >= 4 && i < end && text.charAt(i) == ':')
      text.setSpan(
          new ForegroundColorSpan(color(R.color.shell_accent)),
          start,
          i,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (cls == StartupLogDigest.LineClass.STAGE)
      text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private int shownCount() {
    if (digest == null) return 0;
    int n = 0;
    for (StartupLogDigest.LineClass cls : digest.lineClasses) {
      if (filter == Filter.NO_TIMING && cls == StartupLogDigest.LineClass.TIMING) continue;
      if (filter == Filter.ISSUES
          && cls != StartupLogDigest.LineClass.WARN
          && cls != StartupLogDigest.LineClass.BAD) continue;
      n++;
    }
    return n;
  }

  /** 概览「在原始日志中查看」：切到原始全部、暂停跟随并滚到该阶段首行，短暂高亮。 */
  private void jumpToEntry(int entry) {
    rawMode = true;
    filter = Filter.ALL;
    flashEntry = entry;
    rawKey = Long.MIN_VALUE;
    renderChips();
    renderTabs();
    renderDigestLine();
    renderRaw();
    setFollow(false);
    raw.post(
        () -> {
          Layout layout = raw.getLayout();
          if (layout == null || entry < 0 || entry >= rawOffsets.length || rawOffsets[entry] < 0)
            return;
          int top = layout.getLineTop(layout.getLineForOffset(rawOffsets[entry]));
          programmatic = true;
          scroll.scrollTo(0, Math.max(0, rawPane.getTop() + raw.getTop() + top - dp(8)));
          programmatic = false;
        });
    raw.postDelayed(
        () -> {
          if (flashEntry != entry) return;
          flashEntry = -1;
          rawKey = Long.MIN_VALUE;
          if (rawMode) renderRaw();
        },
        1200);
  }

  // ---------------------------------------------------------------- 工具

  private View stateIcon(StartupLogDigest.State state) {
    if (state == StartupLogDigest.State.RUNNING) {
      ProgressBar spin = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
      spin.setIndeterminate(true);
      spin.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.shell_accent)));
      spin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      return spin;
    }
    int icon, fg, bg;
    switch (state) {
      case WARN:
        icon = R.drawable.ic_launch_log_warn;
        fg = R.color.shell_warn;
        bg = R.color.shell_warn_soft;
        break;
      case BAD:
        icon = R.drawable.ic_launch_log_bad;
        fg = R.color.shell_bad;
        bg = R.color.shell_bad_soft;
        break;
      case MUTED:
        icon = R.drawable.ic_launch_log_idle;
        fg = R.color.shell_text_muted;
        bg = R.color.shell_line_strong;
        break;
      default:
        icon = R.drawable.ic_launch_log_ok;
        fg = R.color.shell_good;
        bg = R.color.shell_good_soft;
        break;
    }
    ImageView view = new ImageView(context);
    view.setImageResource(icon);
    view.setImageTintList(ColorStateList.valueOf(color(fg)));
    view.setPadding(dp(3), dp(3), dp(3), dp(3));
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(color(bg));
    view.setBackground(circle);
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return view;
  }

  private View bar(float fraction, int tone, int alpha) {
    LinearLayout track = new LinearLayout(context);
    track.setOrientation(LinearLayout.HORIZONTAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(3));
    bg.setColor(color(R.color.shell_line));
    track.setBackground(bg);
    track.setClipToOutline(true);
    View fill = new View(context);
    GradientDrawable fg = new GradientDrawable();
    fg.setCornerRadius(dp(3));
    fg.setColor(withAlpha(color(tone), alpha));
    fill.setBackground(fg);
    float f = Math.max(fraction <= 0 ? 0f : 0.02f, Math.min(1f, fraction));
    track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f));
    View rest = new View(context);
    track.addView(
        rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f));
    track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return track;
  }

  private TextView empty(String message) {
    TextView view = text(message, 12, R.color.shell_text_muted);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(12), dp(28), dp(12), dp(28));
    return view;
  }

  private TextView text(String value, float sp, int tone) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color(tone));
    view.setIncludeFontPadding(false);
    view.setLineSpacing(dp(2), 1f);
    return view;
  }

  private TextView mono(String value, float sp, int tone) {
    TextView view = text(value, sp, tone);
    view.setTypeface(Typeface.MONOSPACE);
    view.setFontFeatureSettings("tnum");
    return view;
  }

  private String stageName(StartupLogDigest.Stage stage) {
    return stage.name == null || stage.name.isEmpty()
        ? UiText.choose("准备", "Preparing")
        : stage.name;
  }

  private static String stateWord(StartupLogDigest.State state) {
    switch (state) {
      case RUNNING:
        return UiText.choose("进行中", "In progress");
      case WARN:
        return UiText.choose("有提示", "Has notices");
      case BAD:
        return UiText.choose("失败", "Failed");
      case MUTED:
        return UiText.choose("已停止", "Stopped");
      default:
        return UiText.choose("完成", "Done");
    }
  }

  private static int durationTone(StartupLogDigest.State state) {
    switch (state) {
      case RUNNING:
        return R.color.shell_accent;
      case WARN:
        return R.color.shell_warn;
      case BAD:
        return R.color.shell_bad;
      default:
        return R.color.shell_text_muted;
    }
  }

  private static int barTone(StartupLogDigest.State state) {
    switch (state) {
      case WARN:
        return R.color.shell_warn;
      case BAD:
        return R.color.shell_bad;
      case GOOD:
        return R.color.shell_good;
      case MUTED:
        return R.color.shell_text_muted;
      default:
        return R.color.shell_accent;
    }
  }

  /** 阶段 / 计时耗时：≥1s 显示一位小数秒，否则毫秒；没有可度量停留时为空。 */
  static String duration(long ms) {
    if (ms < 0) return "";
    return ms >= 1000
        ? String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
        : String.format(Locale.ROOT, "%dms", ms);
  }

  private static String seconds(long ms) {
    return String.format(Locale.ROOT, "%.1fs", Math.max(0, ms) / 1000.0);
  }

  /** 原始视图时间槽：自启动开始的秒数，不带单位，等宽右对齐。 */
  private static String slot(long ms) {
    return String.format(Locale.ROOT, "%.1f", Math.max(0, ms) / 1000.0);
  }

  private static String repeat(char c, int n) {
    StringBuilder b = new StringBuilder(Math.max(0, n));
    for (int i = 0; i < n; i++) b.append(c);
    return b.toString();
  }

  private int selectable() {
    TypedValue value = new TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
    return value.resourceId;
  }

  private int color(int res) {
    return context.getColor(res);
  }

  private static int withAlpha(int color, int alpha) {
    return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
  }

  private int dp(float value) {
    return Math.round(value * density);
  }

  /** 分段标签里的计数徽标：小号粗体、胶囊底。 */
  private static final class BadgeSpan extends ReplacementSpan {
    private final int fg, bg;
    private final float density;

    BadgeSpan(int fg, int bg, float density) {
      this.fg = fg;
      this.bg = bg;
      this.density = density;
    }

    private Paint badgePaint(Paint base) {
      Paint p = new Paint(base);
      p.setTextSize(base.getTextSize() * 0.84f);
      p.setFakeBoldText(true);
      return p;
    }

    @Override
    public int getSize(
        Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
      Paint p = badgePaint(paint);
      return Math.round(p.measureText(text, start, end) + 12 * density);
    }

    @Override
    public void draw(
        Canvas canvas,
        CharSequence text,
        int start,
        int end,
        float x,
        int top,
        int y,
        int bottom,
        Paint paint) {
      Paint p = badgePaint(paint);
      float width = p.measureText(text, start, end) + 12 * density;
      Paint.FontMetrics m = p.getFontMetrics();
      float h = (m.descent - m.ascent) + 2 * density;
      float cy = y + (m.ascent + m.descent) / 2f;
      RectF r = new RectF(x, cy - h / 2f, x + width, cy + h / 2f);
      Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
      fill.setColor(bg);
      canvas.drawRoundRect(r, h / 2f, h / 2f, fill);
      p.setColor(fg);
      canvas.drawText(text, start, end, x + 6 * density, y, p);
    }
  }

  /** 每次重建前调用：语言或主题变化时让下一次渲染重建视图。 */
  void invalidate() {
    layoutVersion++;
    overviewKey = rawKey = Long.MIN_VALUE;
  }

  /** 宿主测试用：当前是否为原始视图。 */
  boolean isRawMode() {
    return rawMode;
  }
}
