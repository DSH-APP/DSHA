package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DeployStages;
import java.util.List;

/**
 * 首次引导、部署胶囊、维护详情与授权卡片共用的原生构件（2026-10-31 重构）。
 *
 * <p>只用 shell_* / onb_* token 与 bg_onb_* 资源；不自建颜色、不缓存语言。所有构件不持有业务状态，
 * 也不绑定任何会改变环境的动作 —— 监听器由调用页面传入。断点按当前 Configuration 判定：
 * 宽 ≥ 600dp 两栏，高 ≤ 480dp 压缩。
 */
final class OnboardingKit {
  /** 状态色调：与 bg_onb_pill_* / bg_onb_hint_* 资源一一对应。 */
  enum Tone {
    NEUTRAL,
    ACCENT,
    GOOD,
    WARN,
    BAD
  }

  final Context context;
  private final float density;

  OnboardingKit(Context context) {
    this.context = context;
    density = context.getResources().getDisplayMetrics().density;
  }

  int dp(float value) {
    return Math.round(value * density);
  }

  /** 宽度达到 600dp：两栏 / 平板 / 横屏形态。 */
  boolean wide() {
    return context.getResources().getConfiguration().screenWidthDp >= 600;
  }

  /** 高度不足 480dp（手机横屏等）：压缩页头、页脚与按钮。 */
  boolean shortScreen() {
    Configuration c = context.getResources().getConfiguration();
    return c.screenHeightDp > 0 && c.screenHeightDp <= 480;
  }

  int color(int res) {
    return context.getColor(res);
  }

  static int toneColor(Tone tone) {
    switch (tone) {
      case ACCENT:
        return R.color.shell_accent;
      case GOOD:
        return R.color.shell_good;
      case WARN:
        return R.color.shell_warn;
      case BAD:
        return R.color.shell_bad;
      default:
        return R.color.shell_text_muted;
    }
  }

  static Tone tone(DeployStages.Phase phase) {
    switch (phase == null ? DeployStages.Phase.IDLE : phase) {
      case RUNNING:
        return Tone.ACCENT;
      case SUCCEEDED:
        return Tone.GOOD;
      case FAILED:
      case PENDING:
        return Tone.BAD;
      case UNREADY:
        return Tone.WARN;
      default:
        return Tone.NEUTRAL;
    }
  }

  // ---------------------------------------------------------------- 基础排版

  LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  LinearLayout row() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.HORIZONTAL);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  TextView text(CharSequence value, float sp, int colorRes) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color(colorRes));
    view.setIncludeFontPadding(false);
    view.setLineSpacing(dp(3), 1f);
    return view;
  }

  TextView bold(CharSequence value, float sp, int colorRes) {
    TextView view = text(value, sp, colorRes);
    view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
  }

  /** 页眉小标题，例如“第 2 步 · 用途”。 */
  TextView eyebrow(CharSequence value) {
    TextView view = bold(value, 12, R.color.shell_accent);
    view.setLetterSpacing(0.12f);
    return view;
  }

  /** 页面主标题；欢迎页用 h1，其余用 h2。 */
  TextView heading(CharSequence value, boolean h1) {
    float size = h1 ? (shortScreen() ? 24 : 29) : 24;
    TextView view = bold(value, size, R.color.shell_text);
    view.setLineSpacing(dp(2), 1f);
    view.setAccessibilityHeading(true);
    return view;
  }

  TextView lead(CharSequence value) {
    TextView view = text(value, 15, R.color.shell_text_secondary);
    view.setLineSpacing(dp(4), 1f);
    return view;
  }

  TextView fine(CharSequence value) {
    return text(value, 12, R.color.shell_text_muted);
  }

  /** 分组标题（例如“部署前检查”）。 */
  TextView section(CharSequence value) {
    TextView view = bold(value, 12.5f, R.color.shell_text_muted);
    view.setLetterSpacing(0.04f);
    view.setAccessibilityHeading(true);
    return view;
  }

  static LinearLayout.LayoutParams matchWrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  LinearLayout.LayoutParams topMargin(int dp) {
    LinearLayout.LayoutParams p = matchWrap();
    p.topMargin = dp(dp);
    return p;
  }

  /** 以给定上边距把 child 加入 parent 并返回 child。 */
  <T extends View> T add(LinearLayout parent, T child, int topDp) {
    parent.addView(child, topMargin(topDp));
    return child;
  }

  // ---------------------------------------------------------------- 按钮

  /** 主按钮：实心强调色，52dp（矮屏 44dp）。 */
  Button primary(CharSequence title, View.OnClickListener action) {
    Button b = baseButton(title, action);
    b.setBackgroundResource(R.drawable.bg_btn_primary);
    b.setTextColor(context.getColorStateList(R.color.button_primary_text));
    int h = dp(shortScreen() ? 44 : 52);
    b.setMinHeight(h);
    b.setMinimumHeight(h);
    return b;
  }

  /** 次级按钮：描边浅底，46dp（矮屏 40dp）。 */
  Button secondary(CharSequence title, View.OnClickListener action) {
    Button b = baseButton(title, action);
    b.setBackgroundResource(R.drawable.bg_btn);
    b.setTextColor(context.getColorStateList(R.color.button_text));
    int h = dp(shortScreen() ? 40 : 46);
    b.setMinHeight(h);
    b.setMinimumHeight(h);
    return b;
  }

  /** 文字按钮：透明底、强调色文字，40dp。 */
  Button ghost(CharSequence title, View.OnClickListener action) {
    Button b = baseButton(title, action);
    b.setBackgroundResource(R.drawable.bg_onb_ghost);
    b.setTextColor(context.getColorStateList(R.color.action_text));
    b.setMinHeight(dp(40));
    b.setMinimumHeight(dp(40));
    b.setPadding(dp(10), 0, dp(10), 0);
    return b;
  }

  /** 卡片内小按钮：38dp，primary 决定实心或描边。 */
  Button small(CharSequence title, boolean primary, View.OnClickListener action) {
    Button b = primary ? primary(title, action) : secondary(title, action);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    b.setMinHeight(dp(38));
    b.setMinimumHeight(dp(38));
    b.setPadding(dp(12), 0, dp(12), 0);
    return b;
  }

  private Button baseButton(CharSequence title, View.OnClickListener action) {
    var b = new androidx.appcompat.widget.AppCompatButton(context);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    b.setIncludeFontPadding(false);
    b.setGravity(Gravity.CENTER);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(18), dp(6), dp(18), dp(6));
    b.setStateListAnimator(null);
    if (action != null) b.setOnClickListener(action);
    PressFeedback.attach(b, PressFeedback.Variant.BUTTON);
    return b;
  }

  /**
   * 页脚按钮组：手机纵向铺满；宽屏横排靠右（主操作在最右），每个按钮至少 168dp，
   * 放不下时自动换行。第一个参数视为主操作。
   */
  ViewGroup buttonBar(View... buttons) {
    if (!wide()) {
      LinearLayout bar = column();
      for (int i = 0; i < buttons.length; i++) {
        if (buttons[i] == null) continue;
        bar.addView(buttons[i], topMargin(bar.getChildCount() == 0 ? 0 : 8));
      }
      return bar;
    }
    FlowRow bar = new FlowRow(context, dp(8), dp(8), Gravity.END);
    for (int i = buttons.length - 1; i >= 0; i--) {
      View b = buttons[i];
      if (b == null) continue;
      if (b instanceof TextView) ((TextView) b).setMinWidth(dp(168));
      bar.addView(
          b,
          new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    return bar;
  }

  /** 卡片内的小按钮行：自动换行。 */
  ViewGroup actionRow(View... buttons) {
    FlowRow row = new FlowRow(context, dp(6), dp(6), Gravity.START);
    for (View b : buttons)
      if (b != null)
        row.addView(
            b,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return row;
  }

  // ---------------------------------------------------------------- 标签与提示

  /** 小状态标签。 */
  TextView pill(CharSequence value, Tone tone) {
    TextView view = bold(value, 11.5f, toneColor(tone == Tone.NEUTRAL ? Tone.NEUTRAL : tone));
    if (tone == Tone.NEUTRAL) view.setTextColor(color(R.color.shell_text_muted));
    view.setBackgroundResource(pillBackground(tone));
    view.setPadding(dp(9), dp(3), dp(9), dp(3));
    view.setSingleLine(true);
    view.setEllipsize(TextUtils.TruncateAt.END);
    view.setGravity(Gravity.CENTER);
    return view;
  }

  static int pillBackground(Tone tone) {
    switch (tone) {
      case ACCENT:
        return R.drawable.bg_onb_pill_accent;
      case GOOD:
        return R.drawable.bg_onb_pill_good;
      case WARN:
        return R.drawable.bg_onb_pill_warn;
      case BAD:
        return R.drawable.bg_onb_pill_bad;
      default:
        return R.drawable.bg_onb_pill_neutral;
    }
  }

  /** 提示条：图标 + 文字；tone 只接受 ACCENT（信息）/ WARN / BAD。 */
  LinearLayout hint(CharSequence value, Tone tone) {
    LinearLayout box = row();
    box.setGravity(Gravity.TOP);
    box.setBackgroundResource(
        tone == Tone.BAD
            ? R.drawable.bg_onb_hint_bad
            : tone == Tone.WARN ? R.drawable.bg_onb_hint_warn : R.drawable.bg_onb_hint_info);
    box.setPadding(dp(12), dp(10), dp(12), dp(10));
    int ink =
        tone == Tone.BAD
            ? R.color.shell_bad
            : tone == Tone.WARN ? R.color.shell_warn : R.color.shell_accent;
    ImageView mark =
        icon(
            tone == Tone.BAD || tone == Tone.WARN
                ? R.drawable.ic_onb_alert
                : R.drawable.ic_onb_info,
            ink,
            16);
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(16), dp(16));
    mp.topMargin = dp(1);
    box.addView(mark, mp);
    TextView words =
        text(
            value,
            13,
            tone == Tone.BAD
                ? R.color.shell_bad
                : tone == Tone.WARN ? R.color.shell_warn : R.color.shell_text_secondary);
    LinearLayout.LayoutParams wp =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    wp.leftMargin = dp(8);
    box.addView(words, wp);
    return box;
  }

  ImageView icon(int drawable, int tintRes, int sizeDp) {
    ImageView view = new ImageView(context);
    view.setImageResource(drawable);
    view.setImageTintList(ColorStateList.valueOf(color(tintRes)));
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    return view;
  }

  /** 圆角方块图标底（功能 / 授权图标）。 */
  FrameLayout iconBox(int drawable, boolean good, int boxDp, int iconDp) {
    FrameLayout box = new FrameLayout(context);
    box.setBackgroundResource(good ? R.drawable.bg_onb_icon_good : R.drawable.bg_onb_icon);
    ImageView mark = icon(drawable, good ? R.color.shell_good : R.color.shell_accent, iconDp);
    box.addView(mark, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
    box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
    box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return box;
  }

  /** 普通卡片容器（bg_shell_card 同色系）。 */
  LinearLayout card() {
    LinearLayout card = column();
    card.setBackgroundResource(R.drawable.bg_onb_perm);
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    return card;
  }

  /** 卡片标题行：粗体标题 + 右侧状态标签。 */
  LinearLayout cardHeader(CharSequence title, TextView pill) {
    LinearLayout head = row();
    TextView t = bold(title, 15.5f, R.color.shell_text);
    head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    if (pill != null) {
      LinearLayout.LayoutParams p =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      p.leftMargin = dp(10);
      head.addView(pill, p);
    }
    return head;
  }

  /** 错误 / 警告说明框（可选择复制）。 */
  TextView messageBox(CharSequence value, Tone tone) {
    TextView view = text(value, 13, tone == Tone.WARN ? R.color.shell_warn : R.color.shell_bad);
    view.setBackgroundResource(
        tone == Tone.WARN ? R.drawable.bg_onb_hint_warn : R.drawable.bg_onb_hint_bad);
    view.setPadding(dp(12), dp(12), dp(12), dp(12));
    view.setTextIsSelectable(true);
    return view;
  }

  // ---------------------------------------------------------------- 阶段条与阶段列表

  /** 分段条动画进度；页面每秒重建分段条，记录随本 kit（即所属页面）保留。 */
  private final com.deepseekharness.app.util.StageSegmentMotion.Tracker segmentMotion =
      new com.deepseekharness.app.util.StageSegmentMotion.Tracker();

  /**
   * 分段进度条：按阶段着色并带填充 / 流光动画，不显示百分比；带无障碍进度描述。
   *
   * <p>当前段是否叠流光由 {@link #running} 判定，与阶段列表的加载圈同一口径。
   */
  View segmentBar(DeployStages.View view) {
    StageSegmentBar bar = new StageSegmentBar(context, segmentMotion, view, density);
    bar.setContentDescription(
        String.format(
            java.util.Locale.ROOT,
            com.deepseekharness.app.util.UiText.choose(
                "已完成 %1$d / %2$d 个阶段", "%1$d of %2$d stages completed"),
            view.completed(),
            view.total()));
    return bar;
  }

  /** 第 index 个阶段是否正在执行（只有部署进行中的当前阶段播放加载动画）。 */
  static boolean running(DeployStages.View view, int index) {
    return view.phase == DeployStages.Phase.RUNNING
        && index >= 0
        && index < view.steps.length
        && view.steps[index] == DeployStages.Step.CURRENT;
  }

  /** 阶段列表：完成打勾、当前加粗、失败标红。 */
  LinearLayout stageList(DeployStages.View view) {
    LinearLayout list = column();
    List<DeployStages.Stage> stages = view.stages;
    for (int i = 0; i < stages.size(); i++) {
      DeployStages.Step step = view.steps[i];
      LinearLayout line = row();
      line.setGravity(Gravity.TOP);
      line.setPadding(0, dp(6), 0, dp(6));
      FrameLayout mark = new FrameLayout(context);
      int bg;
      int glyph = 0;
      switch (step) {
        case DONE:
          bg =
              view.phase == DeployStages.Phase.SUCCEEDED
                  ? R.drawable.bg_onb_step_good
                  : R.drawable.bg_onb_step_done;
          glyph = R.drawable.ic_onb_check;
          break;
        case CURRENT:
          bg = R.drawable.bg_onb_step_cur;
          break;
        case FAILED:
          bg = R.drawable.bg_onb_step_bad;
          glyph = R.drawable.ic_onb_x;
          break;
        default:
          bg = R.drawable.bg_onb_step_todo;
      }
      boolean spinning = running(view, i);
      // 运行中的当前阶段用转动的圆环代替静态描边圆，表明仍在工作；圆环自带底轨，不再叠描边。
      if (!spinning) mark.setBackgroundResource(bg);
      if (spinning) {
        BootSpinnerView spinner = new BootSpinnerView(context);
        int accent = color(R.color.shell_accent);
        spinner.setColors((0x40 << 24) | (accent & 0x00FFFFFF), accent);
        spinner.setStrokeWidth(dp(2));
        mark.addView(spinner, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
      } else if (glyph != 0)
        mark.addView(
            icon(glyph, R.color.shell_on_accent, 12),
            new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
      else if (step == DeployStages.Step.CURRENT) {
        View dot = new View(context);
        dot.setBackgroundResource(R.drawable.bg_onb_dot_run);
        mark.addView(dot, new FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER));
      }
      LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(18), dp(18));
      mp.topMargin = dp(1);
      line.addView(mark, mp);
      int ink =
          step == DeployStages.Step.FAILED
              ? R.color.shell_bad
              : step == DeployStages.Step.CURRENT
                  ? R.color.shell_text
                  : step == DeployStages.Step.DONE
                      ? R.color.shell_text_secondary
                      : R.color.shell_text_muted;
      TextView label = text(stages.get(i).title(), 13, ink);
      if (step == DeployStages.Step.CURRENT || step == DeployStages.Step.FAILED)
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      lp.leftMargin = dp(10);
      line.addView(label, lp);
      line.setContentDescription(
          String.format(
              java.util.Locale.ROOT,
              com.deepseekharness.app.util.UiText.choose("%1$s：%2$s", "%1$s: %2$s"),
              stages.get(i).title(),
              stepWord(step)));
      line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      list.addView(line);
    }
    return list;
  }

  static String stepWord(DeployStages.Step step) {
    switch (step) {
      case DONE:
        return com.deepseekharness.app.util.UiText.choose("已完成", "Done");
      case CURRENT:
        return com.deepseekharness.app.util.UiText.choose("进行中", "In progress");
      case FAILED:
        return com.deepseekharness.app.util.UiText.choose("未完成", "Not completed");
      default:
        return com.deepseekharness.app.util.UiText.choose("等待中", "Waiting");
    }
  }

  // ---------------------------------------------------------------- 自适应网格

  /**
   * 自适应网格：可用宽度 ≥ minColumnDp × 2（含间距）时两列，否则一列；
   * 奇数个时最后一项占满整行。setWide(true) 的子项总是占满整行。
   */
  static final class Grid extends ViewGroup {
    private final int gap, minColumn;
    private int columns = 1;

    Grid(Context context, int gapPx, int minColumnPx) {
      super(context);
      gap = gapPx;
      minColumn = minColumnPx;
    }

    static void setWide(View child, boolean wide) {
      child.setTag(R.id.onb_grid_wide, wide ? Boolean.TRUE : null);
    }

    private static boolean wide(View child) {
      return Boolean.TRUE.equals(child.getTag(R.id.onb_grid_wide));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      columns = width >= minColumn * 2 + gap ? 2 : 1;
      int column = columns == 2 ? (width - gap) / 2 : width;
      int height = 0, rowHeight = 0, slot = 0, visible = 0;
      for (int i = 0; i < getChildCount(); i++)
        if (getChildAt(i).getVisibility() != GONE) visible++;
      int seen = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        seen++;
        boolean full = columns == 1 || wide(child) || (slot == 0 && seen == visible);
        if (full && slot == 1) {
          height += rowHeight + gap;
          rowHeight = 0;
          slot = 0;
        }
        int w = full ? width : column;
        child.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        if (full || slot == 1) {
          height += rowHeight + gap;
          rowHeight = 0;
          slot = 0;
        } else slot = 1;
      }
      if (slot == 1) height += rowHeight + gap;
      if (height > 0) height -= gap;
      setMeasuredDimension(
          MeasureSpec.getSize(widthSpec), height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int width = r - l - getPaddingLeft() - getPaddingRight();
      int column = columns == 2 ? (width - gap) / 2 : width;
      int y = getPaddingTop(), visible = 0, seen = 0;
      for (int i = 0; i < getChildCount(); i++)
        if (getChildAt(i).getVisibility() != GONE) visible++;
      boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
      java.util.ArrayList<View> row = new java.util.ArrayList<>(2);
      int slot = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        seen++;
        boolean full = columns == 1 || wide(child) || (slot == 0 && seen == visible);
        if (full && slot == 1) {
          y += layoutRow(row, y, column, width, rtl);
          row.clear();
          slot = 0;
        }
        row.add(child);
        if (full || slot == 1) {
          y += layoutRow(row, y, column, width, rtl);
          row.clear();
          slot = 0;
        } else slot = 1;
      }
      if (!row.isEmpty()) layoutRow(row, y, column, width, rtl);
    }

    /** 同一行两张卡片都拉到该行最大高度，避免换行格子参差。 */
    private int layoutRow(java.util.List<View> row, int y, int column, int width, boolean rtl) {
      int h = 0;
      for (View child : row) h = Math.max(h, child.getMeasuredHeight());
      int gapAfter = gap;
      for (int i = 0; i < row.size(); i++) {
        View child = row.get(i);
        boolean full = row.size() == 1;
        int w = full ? width : column;
        int x = getPaddingLeft() + (full || i == 0 ? 0 : column + gap);
        if (rtl) x = getPaddingLeft() + width - (x - getPaddingLeft()) - w;
        child.layout(x, y, x + w, y + h);
      }
      return h + gapAfter;
    }
  }

  /** 自动换行的横向容器（按钮组、标签组）。gravity 只区分 START / END。 */
  static final class FlowRow extends ViewGroup {
    private final int hGap, vGap, gravity;

    FlowRow(Context context, int hGapPx, int vGapPx, int gravity) {
      super(context);
      hGap = hGapPx;
      vGap = vGapPx;
      this.gravity = gravity;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      int x = 0, y = 0, line = 0, widest = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        child.measure(
            MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        if (x > 0 && x + w > max) {
          y += line + vGap;
          x = 0;
          line = 0;
        }
        x += w + hGap;
        widest = Math.max(widest, x - hGap);
        line = Math.max(line, h);
      }
      int height = y + line + getPaddingTop() + getPaddingBottom();
      int mode = MeasureSpec.getMode(widthSpec);
      int width =
          mode == MeasureSpec.EXACTLY
              ? MeasureSpec.getSize(widthSpec)
              : Math.min(max, widest) + getPaddingLeft() + getPaddingRight();
      if (mode == MeasureSpec.AT_MOST) width = MeasureSpec.getSize(widthSpec);
      setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int max = r - l - getPaddingLeft() - getPaddingRight();
      boolean end =
          (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END
              || (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT;
      boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
      if (rtl) end = !end;
      java.util.List<View> current = new java.util.ArrayList<>();
      int x = 0, y = getPaddingTop(), line = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        int w = child.getMeasuredWidth();
        if (x > 0 && x + w > max) {
          place(current, x - hGap, y, max, end);
          y += line + vGap;
          x = 0;
          line = 0;
          current.clear();
        }
        current.add(child);
        x += w + hGap;
        line = Math.max(line, child.getMeasuredHeight());
      }
      place(current, x - hGap, y, max, end);
    }

    private void place(java.util.List<View> views, int used, int y, int max, boolean end) {
      int x = getPaddingLeft() + (end ? Math.max(0, max - used) : 0);
      for (View child : views) {
        child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        x += child.getMeasuredWidth() + hGap;
      }
    }
  }
}
