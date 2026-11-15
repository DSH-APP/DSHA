package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.StartupRecoveryState.Tone;
import com.deepseekharness.app.util.UiText;

/**
 * 恢复页的原生布局；静态展示可独立验收，不执行 Web 或修复操作。
 *
 * <p>2026-11-02 方案 A（docs/design/startup-recovery-20261102）：顶部状态卡只给一个结论和对症主操作，
 * 替代旧「进入原因」；颜色只表达结论（good/info/idle/warn/bad）。其后为插件、其它恢复操作（每行只有动作；
 * 效果说明收进行尾 ⓘ，行内只留「不可用：原因」这类状态）、最近启动（手机默认 3 条可展开）、配置快照、数据与诊断。
 * 可用宽度 ≥700dp 时分左右两栏。
 */
final class StartupRecoveryLayout {
  final ScrollView root;
  final LinearLayout content, status, recovery, tools, attempts, snapshots, plugins;
  final View pluginCard;
  final TextView progress, attemptsSummary, attemptsToggle;
  final boolean wide;
  private final Context context;
  private final LinearLayout left, right;

  /** 行尾 ⓘ 的查找标记；禁用整行后用它把只读说明按钮重新启用。 */
  private static final Object INFO = new Object();

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  StartupRecoveryLayout(Context context, Runnable back, Runnable refresh) {
    this.context = context;
    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    wide = metrics.widthPixels / metrics.density >= 700;
    root = new ScrollView(context);
    root.setFillViewport(true);
    root.setBackgroundColor(context.getColor(R.color.shell_bg));
    content = column();
    content.setPadding(dp(wide ? 24 : 16), dp(8), dp(wide ? 24 : 16), dp(24));
    root.addView(content);

    LinearLayout bar = new LinearLayout(context);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    content.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    bar.addView(
        iconButton(R.drawable.ic_ui_back, t("返回", "Back"), back),
        new LinearLayout.LayoutParams(dp(44), dp(44)));
    TextView title = label(bar, t("启动恢复", "Startup recovery"), 20, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setAccessibilityHeading(true);
    LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) title.getLayoutParams();
    titleParams.width = 0;
    titleParams.weight = 1;
    titleParams.leftMargin = dp(4);
    bar.addView(
        iconButton(
            R.drawable.ic_recovery_refresh, t("刷新恢复记录", "Refresh recovery records"), refresh),
        new LinearLayout.LayoutParams(dp(44), dp(44)));

    LinearLayout body = new LinearLayout(context);
    body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    content.addView(body, new LinearLayout.LayoutParams(-1, -2));
    left = column();
    right = wide ? column() : left;
    if (wide) {
      body.addView(left, new LinearLayout.LayoutParams(0, -2, 1.1f));
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
      rp.setMarginStart(dp(24));
      body.addView(right, rp);
    } else body.addView(left, new LinearLayout.LayoutParams(-1, -2));

    status = column();
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
    sp.topMargin = dp(8);
    left.addView(status, sp);
    progress = label(left, "", 13, R.color.shell_text);
    progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    progress.setPadding(dp(12), dp(10), dp(12), dp(10));
    progress.setTextIsSelectable(true);
    ((LinearLayout.LayoutParams) progress.getLayoutParams()).topMargin = dp(10);
    progress.setVisibility(View.GONE);

    plugins = group(left, t("插件", "Plugins"), "");
    pluginCard = (View) plugins.getParent();
    pluginCard.setVisibility(View.GONE);
    recovery = group(left, t("其它恢复操作", "Other recovery actions"), "");
    attempts = group(right, t("最近启动", "Recent starts"), "");
    LinearLayout attemptsHeader = (LinearLayout) ((ViewGroup) attempts.getParent()).getChildAt(0);
    attemptsSummary = label(attemptsHeader, "", 12, R.color.shell_text_muted);
    LinearLayout.LayoutParams summaryParams =
        (LinearLayout.LayoutParams) attemptsSummary.getLayoutParams();
    summaryParams.width = 0;
    summaryParams.weight = 1;
    attemptsSummary.setPadding(dp(8), 0, 0, 0);
    attemptsToggle = label(attemptsHeader, "", 13, R.color.shell_accent);
    ((LinearLayout.LayoutParams) attemptsToggle.getLayoutParams()).width = -2;
    attemptsToggle.setTypeface(null, android.graphics.Typeface.BOLD);
    attemptsToggle.setMinHeight(dp(36));
    attemptsToggle.setGravity(Gravity.CENTER);
    attemptsToggle.setPadding(dp(10), 0, dp(10), 0);
    attemptsToggle.setBackgroundResource(R.drawable.bg_action_plain);
    attemptsToggle.setVisibility(View.GONE);
    snapshots =
        group(
            right,
            t("配置快照", "Configuration snapshots"),
            t(
                "保留三次健康启动与三次修复前快照；仅保存配置，不复制会话、工作区或依赖目录。",
                "Keeps three healthy-start and three pre-repair snapshots. Saves configuration only, without sessions, workspaces, or dependency directories."));
    tools = group(left, t("数据与诊断", "Data & diagnostics"), "");
  }

  private ImageButton iconButton(int resource, String description, Runnable click) {
    ImageButton button = new ImageButton(context);
    button.setImageResource(resource);
    button.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_text)));
    button.setBackgroundResource(R.drawable.bg_action_plain);
    button.setContentDescription(description);
    button.setOnClickListener(v -> click.run());
    return button;
  }

  /** 行尾 / 分组标题旁的 ⓘ：打开完整说明；说明文案沿用原有效果句，不新增翻译键。 */
  private ImageButton infoButton(String title, String full) {
    ImageButton info = new ImageButton(context);
    info.setImageResource(R.drawable.ic_onb_info);
    info.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    info.setBackgroundResource(R.drawable.bg_action_plain);
    info.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int pad = dp(7);
    info.setPadding(pad, pad, pad, pad);
    info.setContentDescription(t("查看说明：", "View details: ") + title);
    info.setOnClickListener(v -> InfoHint.show(context, v, title, full));
    return info;
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private int dp(int n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  static int color(Tone tone) {
    switch (tone) {
      case GOOD:
        return R.color.shell_good;
      case INFO:
        return R.color.shell_accent;
      case WARN:
        return R.color.shell_warn;
      case BAD:
        return R.color.shell_bad;
      default:
        return R.color.shell_text_muted;
    }
  }

  private static int soft(Tone tone) {
    switch (tone) {
      case GOOD:
        return R.color.shell_good_soft;
      case INFO:
        return R.color.shell_accent_soft;
      case WARN:
        return R.color.shell_warn_soft;
      case BAD:
        return R.color.shell_bad_soft;
      default:
        return R.color.shell_raised;
    }
  }

  private static int pill(Tone tone) {
    switch (tone) {
      case GOOD:
        return R.drawable.bg_onb_pill_good;
      case INFO:
        return R.drawable.bg_onb_pill_accent;
      case WARN:
        return R.drawable.bg_onb_pill_warn;
      case BAD:
        return R.drawable.bg_onb_pill_bad;
      default:
        return R.drawable.bg_onb_pill_neutral;
    }
  }

  private static int dot(Tone tone) {
    switch (tone) {
      case GOOD:
        return R.drawable.bg_onb_dot_done;
      case INFO:
        return R.drawable.bg_onb_dot_run;
      case WARN:
        return R.drawable.bg_onb_dot_warn;
      case BAD:
        return R.drawable.bg_onb_dot_bad;
      default:
        return R.drawable.bg_plugin_dot;
    }
  }

  private ImageView icon(int resource, int color) {
    androidx.appcompat.widget.AppCompatImageView icon =
        new androidx.appcompat.widget.AppCompatImageView(context);
    icon.setImageResource(resource);
    icon.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(color)));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return icon;
  }

  /** 状态卡：圆点 + 结论标题（平板带错误码）+ 一句说明 + 元信息；返回主操作容器。 */
  LinearLayout statusCard(Tone tone, String title, String code, String detail, String meta) {
    status.removeAllViews();
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(dp(18));
    shape.setColor(context.getColor(soft(tone)));
    shape.setStroke(dp(1), context.getColor(R.color.shell_line));
    status.setBackground(shape);
    status.setPadding(dp(16), dp(14), dp(16), dp(14));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    LinearLayout head = new LinearLayout(context);
    head.setGravity(Gravity.CENTER_VERTICAL);
    status.addView(head, new LinearLayout.LayoutParams(-1, -2));
    View mark = new View(context);
    mark.setBackgroundResource(dot(tone));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(10), dp(10));
    mp.setMarginEnd(dp(10));
    head.addView(mark, mp);
    TextView heading = label(head, title, 16, color(tone));
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setAccessibilityHeading(true);
    LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) heading.getLayoutParams();
    hp.width = 0;
    hp.weight = 1;
    if (!code.isEmpty() && wide) {
      TextView badge = label(head, code, 11, color(tone));
      badge.setTypeface(android.graphics.Typeface.MONOSPACE);
      ((LinearLayout.LayoutParams) badge.getLayoutParams()).width = -2;
      badge.setPadding(dp(8), 0, 0, 0);
    }
    TextView text = label(status, detail, 13, R.color.shell_text_secondary);
    ((LinearLayout.LayoutParams) text.getLayoutParams()).topMargin = dp(4);
    String fullMeta =
        !code.isEmpty() && !wide ? (meta.isEmpty() ? code : meta + " · " + code) : meta;
    if (!fullMeta.isEmpty()) {
      TextView extra = label(status, fullMeta, 12, R.color.shell_text_muted);
      extra.setTextIsSelectable(true);
      ((LinearLayout.LayoutParams) extra.getLayoutParams()).topMargin = dp(4);
    }
    LinearLayout buttons = new LinearLayout(context);
    buttons.setVisibility(View.GONE);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
    bp.topMargin = dp(12);
    status.addView(buttons, bp);
    return buttons;
  }

  /** 状态卡主操作；第一个为主按钮。 */
  Button statusButton(LinearLayout buttons, String text, boolean primary, Runnable click) {
    Button button =
        new Button(
            context, null, 0, primary ? R.style.ShellPrimaryButton : R.style.ShellSecondaryButton);
    button.setText(text);
    button.setOnClickListener(v -> click.run());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    if (buttons.getChildCount() > 0) lp.setMarginStart(dp(8));
    buttons.addView(button, lp);
    buttons.setVisibility(View.VISIBLE);
    return button;
  }

  /** 操作结果横幅：成功 / 失败 / 进行中各用语义色；首次出现淡入，换文 150ms 淡入。 */
  void banner(Tone tone, String text) {
    String next = text == null ? "" : text;
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(dp(12));
    shape.setColor(context.getColor(soft(tone)));
    progress.setBackground(shape);
    if (next.isEmpty()) {
      progress.animate().cancel();
      progress.setVisibility(View.GONE);
      progress.setAlpha(1f);
      progress.setTranslationY(0f);
      return;
    }
    boolean wasHidden = progress.getVisibility() != View.VISIBLE;
    progress.setVisibility(View.VISIBLE);
    if (wasHidden) {
      progress.setText(next);
      UiMotion.reveal(progress);
    } else {
      UiMotion.crossFadeText(progress, next);
    }
  }

  /** 分组：标题行（可带 ⓘ 说明与右侧切换）+ 细边框行容器。返回行容器；其父视图是整组外壳。 */
  private LinearLayout group(LinearLayout column, String title, String hint) {
    LinearLayout section = column();
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(16);
    column.addView(section, params);
    LinearLayout header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(4), 0, 0, dp(6));
    section.addView(header, new LinearLayout.LayoutParams(-1, -2));
    TextView label = label(header, title, 12, R.color.shell_text_secondary);
    ((LinearLayout.LayoutParams) label.getLayoutParams()).width = -2;
    label.setTypeface(null, android.graphics.Typeface.BOLD);
    label.setLetterSpacing(0.03f);
    label.setAccessibilityHeading(true);
    // 分组说明默认不铺在页面上：标题旁一个 ⓘ，完整说明点开再看（审计 B-06）。
    if (!hint.isEmpty()) {
      ImageButton info = infoButton(title, hint);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(28), dp(28));
      ip.setMarginStart(dp(2));
      header.addView(info, ip);
    }
    LinearLayout entries = column();
    entries.setBackgroundResource(R.drawable.bg_settings_group);
    entries.setClipToOutline(true);
    section.addView(entries, new LinearLayout.LayoutParams(-1, -2));
    return entries;
  }

  TextView label(LinearLayout parent, String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(context.getColor(color));
    view.setLineSpacing(dp(2), 1);
    view.setPadding(0, dp(2), 0, dp(2));
    parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    return view;
  }

  /** 组内空状态 / 错误说明：与行文字左对齐。 */
  TextView note(LinearLayout parent, String value) {
    TextView view = label(parent, value, 13, R.color.shell_text_muted);
    view.setPadding(dp(12), dp(12), dp(12), dp(12));
    return view;
  }

  View action(LinearLayout parent, String label, int resource, Runnable click, boolean chevron) {
    return action(parent, label, "", "", false, resource, click, chevron);
  }

  /**
   * 操作行：只放动作标题（UI 基准 ①）；效果说明收进行尾 ⓘ，行内只保留状态
   * （「不可用：原因」或「本次启动报错」）。blocked 行置灰，但 ⓘ 仍可点读说明。
   */
  View action(
      LinearLayout parent,
      String label,
      String effect,
      String note,
      boolean blocked,
      int resource,
      Runnable click,
      boolean chevron) {
    View mark = null;
    if (resource != 0) {
      FrameLayout box = new FrameLayout(context);
      box.setBackgroundResource(R.drawable.bg_onb_icon);
      box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      box.addView(
          icon(resource, R.color.shell_accent),
          new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
      mark = box;
    }
    boolean hasEffect = effect != null && !effect.isEmpty();
    boolean hasNote = note != null && !note.isEmpty();
    LinearLayout text = column();
    TextView main = rowText(text, label, 14, 0);
    if (hasEffect || hasNote) main.setTypeface(null, android.graphics.Typeface.BOLD);
    String second = "";
    if (hasNote) {
      second = blocked ? t("不可用：", "Unavailable: ") + note : note;
      TextView view =
          rowText(text, second, 12, blocked ? R.color.shell_warn : R.color.shell_text_muted);
      ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = dp(3);
    }
    String description = second.isEmpty() ? label : label + UiText.text("，") + second;
    View row =
        row(
            parent,
            description,
            mark,
            mark == null ? 0 : 30,
            text,
            click,
            chevron,
            hasEffect ? label : "",
            hasEffect ? effect : "");
    if (blocked) {
      enabled(row, false);
      // 效果说明是只读信息，不随动作一起禁用；否则禁用行就再也看不到「这行本来做什么」。
      View info = row.findViewWithTag(INFO);
      if (info != null) info.setEnabled(true);
    }
    return row;
  }

  /** 记录 / 快照行：圆点 + 结论胶囊 + 时间 + 用时说明。 */
  View statusAction(
      LinearLayout parent, Tone tone, String outcome, String time, String elapsed, Runnable click) {
    View mark = new View(context);
    mark.setBackgroundResource(dot(tone));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout line = new LinearLayout(context);
    line.setGravity(Gravity.CENTER_VERTICAL);
    TextView tag = new TextView(context);
    tag.setText(outcome);
    tag.setTextSize(12);
    tag.setTypeface(null, android.graphics.Typeface.BOLD);
    tag.setTextColor(context.getColor(color(tone)));
    tag.setBackgroundResource(pill(tone));
    tag.setPadding(dp(8), dp(2), dp(8), dp(2));
    LinearLayout tagBox = new LinearLayout(context);
    tagBox.addView(tag, new LinearLayout.LayoutParams(-2, -2));
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
    tp.setMarginEnd(dp(8));
    line.addView(tagBox, tp);
    TextView when = rowText(line, time, 13, R.color.shell_text_secondary);
    when.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    if (!elapsed.isEmpty()) {
      TextView took = rowText(line, elapsed, 12, R.color.shell_text_muted);
      LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
      ep.setMarginStart(dp(8));
      took.setLayoutParams(ep);
    }
    String comma = UiText.text("，");
    String description = outcome + comma + time + (elapsed.isEmpty() ? "" : comma + elapsed);
    return row(parent, description, mark, 8, line, click, true);
  }

  private TextView rowText(LinearLayout parent, String value, int size, int color) {
    TextView text = new TextView(context);
    text.setText(value);
    text.setTextSize(size);
    text.setIncludeFontPadding(false);
    text.setLineSpacing(dp(2), 1);
    if (color == 0)
      text.setTextColor(
          androidx.core.content.ContextCompat.getColorStateList(context, R.color.button_text));
    else text.setTextColor(context.getColor(color));
    parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
    return text;
  }

  private View row(
      LinearLayout parent,
      String description,
      View mark,
      int markDp,
      View body,
      Runnable click,
      boolean chevron) {
    return row(parent, description, mark, markDp, body, click, chevron, "", "");
  }

  private View row(
      LinearLayout parent,
      String description,
      View mark,
      int markDp,
      View body,
      Runnable click,
      boolean chevron,
      String infoLabel,
      String infoEffect) {
    if (parent.getChildCount() > 0) {
      View line = new View(context);
      line.setBackgroundColor(context.getColor(R.color.shell_line));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
      lp.setMarginStart(dp(mark == null ? 12 : markDp > 10 ? 54 : 32));
      parent.addView(line, lp);
    }
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(48));
    row.setPadding(dp(12), dp(8), dp(10), dp(8));
    android.util.TypedValue ripple = new android.util.TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);
    row.setFocusable(true);
    row.setClickable(true);
    row.setContentDescription(description);
    row.setOnClickListener(v -> click.run());
    if (mark != null) {
      LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(markDp), dp(markDp));
      mp.setMarginEnd(dp(12));
      row.addView(mark, mp);
    }
    row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
    if (infoEffect != null && !infoEffect.isEmpty()) {
      ImageButton info = infoButton(infoLabel, infoEffect);
      info.setTag(INFO);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(32), dp(32));
      ip.setMarginStart(dp(2));
      row.addView(info, ip);
    }
    if (chevron) {
      ImageView next = icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted);
      LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(16), dp(16));
      nextParams.setMarginStart(dp(8));
      row.addView(next, nextParams);
    }
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    androidx.core.view.ViewCompat.setAccessibilityDelegate(
        row,
        new androidx.core.view.AccessibilityDelegateCompat() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
          }
        });
    return row;
  }

  /** 不可用时整行置灰，但「不可用：原因」仍需可读，故透明度高于旧版 0.45。 */
  static void enabled(View action, boolean enabled) {
    action.setAlpha(enabled ? 1f : 0.6f);
    enabledTree(action, enabled);
  }

  private static void enabledTree(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        enabledTree(((ViewGroup) view).getChildAt(i), enabled);
  }
}
