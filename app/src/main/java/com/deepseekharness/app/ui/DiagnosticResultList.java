package com.deepseekharness.app.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.util.DiagnosticReport;
import com.deepseekharness.app.util.DiagnosticSummary;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 自检结果卡渲染：需要处理与未核验的项按严重度置顶，正常项折叠为一行「N 项正常」，展开后是单行紧凑列表。
 *
 * <p>结果卡足够宽且字体未放大时，正常项排两列（{@link DiagnosticSummary#okColumns}）。列数随卡片实际宽度变化重新渲染，
 * 手机与平板布局共用同一份逻辑。健康度只看枚举，不从文案推断。
 */
final class DiagnosticResultList {
  private final Context context;
  private final LinearLayout card;
  private final View empty;
  private final Consumer<DiagnosticRepository.Result> onOpen;
  private List<DiagnosticRepository.Result> items = List.of();
  private boolean expanded;
  private boolean running;
  private int columns = 1;

  DiagnosticResultList(
      LinearLayout card, View empty, Consumer<DiagnosticRepository.Result> onOpen) {
    this.context = card.getContext();
    this.card = card;
    this.empty = empty;
    this.onOpen = onOpen;
    card.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (right - left == oldRight - oldLeft) return;
          int wanted = columnsFor(right - left);
          if (wanted != columns) card.post(this::render);
        });
  }

  boolean expanded() {
    return expanded;
  }

  void setExpanded(boolean value) {
    expanded = value;
  }

  void setRunning(boolean value) {
    if (running == value) return;
    running = value;
    if (items.isEmpty()) render();
  }

  /** 新一轮结果收起正常项；同一份结果（如旋转后重新观察）保持用户的展开状态。 */
  void show(List<DiagnosticRepository.Result> next) {
    List<DiagnosticRepository.Result> safe = next == null ? List.of() : next;
    if (safe != items && !items.isEmpty()) expanded = false;
    items = safe;
    render();
  }

  private int columnsFor(int widthPx) {
    float density = context.getResources().getDisplayMetrics().density;
    float fontScale = context.getResources().getConfiguration().fontScale;
    return DiagnosticSummary.okColumns(Math.round(widthPx / density), fontScale);
  }

  private void render() {
    card.removeAllViews();
    if (items.isEmpty()) {
      if (empty instanceof TextView text)
        text.setText(running ? UiText.text("正在收集结果…") : context.getString(R.string.diag248_empty));
      card.addView(empty);
      return;
    }
    List<DiagnosticReport.Health> healths = new ArrayList<>(items.size());
    for (DiagnosticRepository.Result item : items) healths.add(item.health);
    List<DiagnosticRepository.Result> ok = new ArrayList<>();
    for (int index : DiagnosticSummary.order(healths)) {
      DiagnosticRepository.Result item = items.get(index);
      DiagnosticSummary.Group group = DiagnosticSummary.group(item.health);
      if (group == DiagnosticSummary.Group.OK) ok.add(item);
      else problem(item, group);
    }
    if (ok.isEmpty()) return;
    okHeader(ok.size());
    if (!expanded) return;
    columns = card.getWidth() > 0 ? columnsFor(card.getWidth()) : 1;
    okGrid(ok);
  }

  // ---------- 需要处理 / 未核验：两行，彩色状态 ----------

  private void problem(DiagnosticRepository.Result item, DiagnosticSummary.Group group) {
    divider(dp(36));
    boolean attention = group == DiagnosticSummary.Group.ATTENTION;
    LinearLayout row = row(dp(56));
    row.addView(dot(attention ? R.drawable.bg_onb_dot_warn : R.drawable.bg_diag_dot_muted));
    LinearLayout words = column();
    words.addView(title(item.title, true));
    TextView status =
        text(item.status, 12, attention ? R.color.shell_warn : R.color.shell_text_muted);
    status.setPadding(0, dp(3), 0, 0);
    status.setMaxLines(2);
    status.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(status);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(12);
    p.rightMargin = dp(8);
    row.addView(words, p);
    row.addView(chevron(0));
    row.setContentDescription(item.title + ", " + item.status);
    row.setOnClickListener(v -> onOpen.accept(item));
    card.addView(row);
  }

  // ---------- 正常项折叠头 ----------

  private void okHeader(int count) {
    divider(dp(36));
    LinearLayout row = row(dp(56));
    row.addView(dot(R.drawable.bg_onb_dot_done));
    LinearLayout words = column();
    words.addView(title(UiText.format("%d 项正常", count), true));
    TextView hint =
        text(
            expanded
                ? UiText.choose("点按收起", "Tap to collapse")
                : UiText.choose("点按展开", "Tap to expand"),
            12,
            R.color.shell_text_muted);
    hint.setPadding(0, dp(3), 0, 0);
    words.addView(hint);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.leftMargin = dp(12);
    p.rightMargin = dp(8);
    row.addView(words, p);
    row.addView(chevron(expanded ? 90 : 0));
    row.setContentDescription(UiText.format("%d 项正常", count));
    if (android.os.Build.VERSION.SDK_INT >= 30)
      row.setStateDescription(
          expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
    row.setOnClickListener(
        v -> {
          expanded = !expanded;
          render();
        });
    card.addView(row);
  }

  // ---------- 正常项：单行紧凑，宽卡两列 ----------

  private void okGrid(List<DiagnosticRepository.Result> ok) {
    boolean large = context.getResources().getConfiguration().fontScale >= 1.15f;
    int inner = Math.max(0, card.getWidth() - card.getPaddingLeft() - card.getPaddingRight());
    int cellWidth = inner > 0 ? inner / columns : 0;
    LinearLayout grid = column();
    grid.setPadding(0, 0, 0, dp(6));
    View line = new View(context);
    line.setBackgroundColor(context.getColor(R.color.shell_line));
    grid.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    for (int start = 0; start < ok.size(); start += columns) {
      if (start > 0) {
        View between = new View(context);
        between.setBackgroundColor(context.getColor(R.color.shell_line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.leftMargin = dp(40);
        lp.rightMargin = dp(16);
        grid.addView(between, lp);
      }
      LinearLayout line2 = new LinearLayout(context);
      line2.setOrientation(LinearLayout.HORIZONTAL);
      line2.setBaselineAligned(false);
      for (int c = 0; c < columns; c++) {
        int index = start + c;
        View cell = index < ok.size() ? mini(ok.get(index), large, cellWidth) : new View(context);
        line2.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
      }
      grid.addView(line2, new LinearLayout.LayoutParams(-1, -2));
    }
    card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
  }

  private View mini(DiagnosticRepository.Result item, boolean large, int cellWidth) {
    LinearLayout cell = new LinearLayout(context);
    cell.setOrientation(LinearLayout.HORIZONTAL);
    cell.setGravity(Gravity.CENTER_VERTICAL);
    cell.setMinimumHeight(dp(44));
    cell.setPadding(dp(20), large ? dp(8) : 0, dp(16), large ? dp(8) : 0);
    cell.setBackgroundResource(R.drawable.bg_action_plain);
    cell.setFocusable(true);
    cell.setClickable(true);
    ImageView mark = new ImageView(context);
    mark.setImageResource(R.drawable.bg_onb_dot_done);
    mark.setAlpha(0.7f);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    cell.addView(mark, new LinearLayout.LayoutParams(dp(6), dp(6)));
    TextView name = text(item.title, 14, R.color.shell_text);
    TextView status = text(item.status, 12, R.color.shell_text_muted);
    if (large) {
      // 1.3 倍字体：标题与状态上下排、允许换行，不截断。
      LinearLayout words = column();
      words.addView(name);
      status.setPadding(0, dp(3), 0, 0);
      words.addView(status);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.leftMargin = dp(14);
      cell.addView(words, p);
    } else {
      name.setSingleLine(true);
      name.setEllipsize(TextUtils.TruncateAt.END);
      status.setSingleLine(true);
      status.setEllipsize(TextUtils.TruncateAt.END);
      status.setGravity(Gravity.END);
      if (cellWidth > 0) status.setMaxWidth(Math.round(cellWidth * 0.48f));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.leftMargin = dp(14);
      p.rightMargin = dp(10);
      cell.addView(name, p);
      cell.addView(status, new LinearLayout.LayoutParams(-2, -2));
    }
    cell.setContentDescription(item.title + ", " + item.status);
    cell.setOnClickListener(v -> onOpen.accept(item));
    return cell;
  }

  // ---------- 小工具 ----------

  private void divider(int inset) {
    if (card.getChildCount() == 0) return;
    View line = new View(context);
    line.setBackgroundColor(context.getColor(R.color.shell_line));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.leftMargin = inset;
    card.addView(line, p);
  }

  private LinearLayout row(int minHeight) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(minHeight);
    row.setPadding(dp(18), dp(10), dp(16), dp(10));
    row.setBackgroundResource(R.drawable.bg_action_plain);
    row.setFocusable(true);
    row.setClickable(true);
    return row;
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private ImageView dot(int drawable) {
    ImageView mark = new ImageView(context);
    mark.setImageResource(drawable);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(8), dp(8));
    mark.setLayoutParams(p);
    return mark;
  }

  private ImageView chevron(float rotation) {
    ImageView arrow = new ImageView(context);
    arrow.setImageResource(R.drawable.ic_ui2_chevron);
    arrow.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
    arrow.setRotation(rotation);
    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
    return arrow;
  }

  private TextView title(String value, boolean bold) {
    TextView view = text(value, 14, R.color.shell_text);
    if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
    view.setMaxLines(2);
    view.setEllipsize(TextUtils.TruncateAt.END);
    return view;
  }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(sp);
    view.setIncludeFontPadding(false);
    view.setTextColor(context.getColor(color));
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return view;
  }

  private int dp(int n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }
}
