package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DshaCliPreset;
import com.deepseekharness.app.util.TerminalSessionDot;
import com.deepseekharness.app.util.UiText;

/**
 * 终端会话侧栏的两个弹窗与状态点。只负责视图，不持有会话或进程；点击结果交回 {@link
 * TerminalActivity} 走原有创建 / 确认路径。内容都包在 ScrollView 里，短屏与大字体可滚动。
 */
final class TerminalSessionViews {
  interface CreateChoice {
    void pty();

    void simple();

    void cli();
  }

  private TerminalSessionViews() {}

  /** 新建会话：三张类型卡片，点卡片直接新建；底部只留“取消”。 */
  static AlertDialog showCreateChooser(Context context, CreateChoice choice) {
    LinearLayout list = column(context);
    list.addView(
        subtitle(
            context,
            UiText.choose(
                "选择类型，会话在后台独立运行", "Pick a type; sessions keep running in the background")));
    AlertDialog[] dialog = new AlertDialog[1];
    list.addView(
        option(
            context,
            R.drawable.ic_terminal_pty,
            UiText.choose("PTY 终端", "PTY terminal"),
            UiText.choose(
                "完整交互终端，支持 vim、top、补全与颜色",
                "Full interactive terminal with vim, top, completion and colors"),
            R.id.terminal_create_pty,
            () -> {
              dismiss(dialog[0]);
              choice.pty();
            }));
    list.addView(
        option(
            context,
            R.drawable.ic_terminal_simple,
            UiText.choose("简易终端", "Simple terminal"),
            UiText.choose(
                "按行执行命令，适合读屏与粘贴长命令",
                "Runs one line at a time; suits screen readers and long pasted commands"),
            R.id.terminal_create_simple,
            () -> {
              dismiss(dialog[0]);
              choice.simple();
            }));
    list.addView(
        option(
            context,
            R.drawable.ic_terminal_spark,
            DshaCliPreset.TITLE,
            UiText.choose("在新 PTY 中直接启动 DSH 命令行", "Starts the DSH command line in a new PTY"),
            R.id.terminal_create_cli,
            () -> {
              dismiss(dialog[0]);
              choice.cli();
            }));
    dialog[0] =
        new DshaDialogBuilder(context)
            .setTitle(UiText.choose("新建终端会话", "New terminal session"))
            .setView(scroll(context, list))
            .setNegativeButton(UiText.text("取消"), null)
            .show();
    return dialog[0];
  }

  /** 说明：四条带图标的要点 + 状态点图例；侧栏删掉的统计与说明行都由这里兜底。 */
  static AlertDialog showHelp(Context context) {
    LinearLayout list = column(context);
    list.addView(
        helpItem(
            context,
            R.drawable.ic_terminal_home,
            UiText.choose("返回不结束进程", "Back keeps processes"),
            UiText.choose(
                "返回或回到主界面只关闭页面，会话继续在后台运行。",
                "Back or Home only closes the page; sessions keep running in the background.")));
    list.addView(
        helpItem(
            context,
            R.drawable.ic_terminal_end_all,
            UiText.choose("结束需确认", "Ending needs confirmation"),
            UiText.choose(
                "会话行的 × 结束单个进程，标题栏的停止图标结束全部；两者都会先确认，再逐个核验退出。",
                "The × on a row ends one process; the stop icon in the header ends all. Both ask first, then verify each exit.")));
    list.addView(
        helpItem(
            context,
            R.drawable.ic_terminal_rename,
            UiText.text("重命名"),
            UiText.choose(
                "铅笔图标修改会话名，不影响正在运行的命令。",
                "The pencil renames a session without touching the running command.")));
    list.addView(
        helpItem(
            context,
            R.drawable.ic_terminal_keyboard,
            UiText.choose("快捷键", "Extra keys"),
            UiText.choose(
                "长按底部任一快捷键可自定义；Ctrl / Alt 点一下锁定一次，指示条亮起即生效。",
                "Long-press any extra key to customize. Tap Ctrl / Alt to latch it once; the lit bar shows it is active.")));
    list.addView(legend(context));
    return new DshaDialogBuilder(context)
        .setTitle(UiText.choose("终端会话", "Terminal sessions"))
        .setView(scroll(context, list))
        .setPositiveButton(UiText.choose("知道了", "Got it"), null)
        .show();
  }

  /** 状态点：实心色点或空心描边圈，侧栏与说明图例共用同一画法。 */
  static GradientDrawable dot(Context context, TerminalSessionDot.Kind kind) {
    GradientDrawable dot = new GradientDrawable();
    dot.setShape(GradientDrawable.OVAL);
    switch (kind) {
      case RUNNING -> dot.setColor(context.getColor(R.color.terminal_state_running));
      case IDLE -> dot.setColor(context.getColor(R.color.terminal_state_idle));
      case PENDING -> dot.setColor(context.getColor(R.color.terminal_state_pending));
      default -> {
        dot.setColor(Color.TRANSPARENT);
        dot.setStroke(
            Math.max(1, Math.round(1.5f * context.getResources().getDisplayMetrics().density)),
            context.getColor(R.color.terminal_faint));
      }
    }
    return dot;
  }

  private static View option(
      Context context, int iconRes, String title, String summary, int id, Runnable action) {
    LinearLayout row = new LinearLayout(context);
    row.setId(id);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(context, 72));
    row.setPaddingRelative(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12));
    row.setBackgroundResource(R.drawable.bg_terminal_sidebar_tile);
    row.setFocusable(true);
    row.setClickable(true);
    row.setContentDescription(UiText.format("%s · %s", title, summary));
    row.setOnClickListener(v -> action.run());
    row.addView(iconTile(context, iconRes, 44, 22), square(context, 44, 0, 14));
    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    texts.addView(text(context, title, 15.5f, R.color.terminal_panel_text, true));
    TextView detail = text(context, summary, 12.5f, R.color.terminal_muted, false);
    detail.setPadding(0, dp(context, 3), 0, 0);
    detail.setLineSpacing(0, 1.12f);
    texts.addView(detail);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    AppCompatImageView chevron = icon(context, R.drawable.ic_ui2_chevron, R.color.terminal_faint);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams chevronParams =
        new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
    chevronParams.setMarginStart(dp(context, 8));
    row.addView(chevron, chevronParams);
    UiMotion.pressScale(row);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(context, 8);
    row.setLayoutParams(params);
    return row;
  }

  private static View helpItem(Context context, int iconRes, String title, String body) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setFocusable(true);
    row.setContentDescription(UiText.format("%s · %s", title, body));
    row.addView(iconTile(context, iconRes, 34, 18), square(context, 34, 1, 12));
    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    texts.addView(text(context, title, 14.5f, R.color.terminal_panel_text, true));
    TextView detail = text(context, body, 12.5f, R.color.terminal_muted, false);
    detail.setPadding(0, dp(context, 3), 0, 0);
    detail.setLineSpacing(0, 1.18f);
    texts.addView(detail);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(context, 14);
    row.setLayoutParams(params);
    return row;
  }

  /** 图例换行排布：大字体或窄窗下自动折到下一行，不截断。 */
  private static View legend(Context context) {
    FlowRow box = new FlowRow(context, dp(context, 18), dp(context, 8));
    box.setBackgroundResource(R.drawable.bg_terminal_sidebar_tile);
    box.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
    box.setFocusable(true);
    String running = UiText.choose("运行中", "Running");
    String idle = UiText.choose("空闲", "Idle");
    String pending = UiText.choose("待核验", "Unverified");
    String exited = UiText.choose("已退出", "Exited");
    box.setContentDescription(
        UiText.format(
            UiText.choose("状态点：%1$s、%2$s、%3$s、%4$s", "Status dots: %1$s, %2$s, %3$s, %4$s"),
            running,
            idle,
            pending,
            exited));
    box.addView(legendItem(context, TerminalSessionDot.Kind.RUNNING, running));
    box.addView(legendItem(context, TerminalSessionDot.Kind.IDLE, idle));
    box.addView(legendItem(context, TerminalSessionDot.Kind.PENDING, pending));
    box.addView(legendItem(context, TerminalSessionDot.Kind.EXITED, exited));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(context, 2);
    box.setLayoutParams(params);
    return box;
  }

  private static View legendItem(Context context, TerminalSessionDot.Kind kind, String label) {
    LinearLayout item = new LinearLayout(context);
    item.setOrientation(LinearLayout.HORIZONTAL);
    item.setGravity(Gravity.CENTER_VERTICAL);
    item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    View dot = new View(context);
    dot.setBackground(dot(context, kind));
    LinearLayout.LayoutParams dotParams =
        new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7));
    dotParams.setMarginEnd(dp(context, 7));
    item.addView(dot, dotParams);
    item.addView(text(context, label, 12.5f, R.color.terminal_muted, false));
    return item;
  }

  private static AppCompatImageView iconTile(
      Context context, int iconRes, int sizeDp, int glyphDp) {
    AppCompatImageView tile = icon(context, iconRes, R.color.terminal_accent);
    tile.setBackgroundResource(R.drawable.bg_terminal_identity_soft);
    int pad = dp(context, (sizeDp - glyphDp) / 2f);
    tile.setPadding(pad, pad, pad, pad);
    tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return tile;
  }

  private static AppCompatImageView icon(Context context, int iconRes, int tintRes) {
    AppCompatImageView view = new AppCompatImageView(context);
    view.setImageResource(iconRes);
    view.setImageTintList(ColorStateList.valueOf(context.getColor(tintRes)));
    view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    return view;
  }

  private static LinearLayout.LayoutParams square(
      Context context, int sizeDp, int topDp, int endDp) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp));
    params.topMargin = dp(context, topDp);
    params.setMarginEnd(dp(context, endDp));
    return params;
  }

  private static TextView subtitle(Context context, String value) {
    TextView view = text(context, value, 13, R.color.terminal_muted, false);
    view.setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 14));
    return view;
  }

  private static TextView text(
      Context context, String value, float sizeSp, int colorRes, boolean bold) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
    view.setTextColor(context.getColor(colorRes));
    view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
    return view;
  }

  private static LinearLayout column(Context context) {
    LinearLayout list = new LinearLayout(context);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 4));
    return list;
  }

  private static ScrollView scroll(Context context, View content) {
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    return scroll;
  }

  private static void dismiss(AlertDialog dialog) {
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
  }

  private static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  /** 极简换行容器：子项按宽度从起始侧排列，放不下就换行；支持 RTL。 */
  private static final class FlowRow extends android.view.ViewGroup {
    private final int gapX, gapY;

    FlowRow(Context context, int gapX, int gapY) {
      super(context);
      this.gapX = gapX;
      this.gapY = gapY;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      int x = 0, y = 0, line = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        child.measure(
            MeasureSpec.makeMeasureSpec(Math.max(0, available), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (x > 0 && x + child.getMeasuredWidth() > available) {
          x = 0;
          y += line + gapY;
          line = 0;
        }
        x += child.getMeasuredWidth() + gapX;
        line = Math.max(line, child.getMeasuredHeight());
      }
      setMeasuredDimension(
          MeasureSpec.getSize(widthSpec), y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
      int width = r - l;
      int available = width - getPaddingLeft() - getPaddingRight();
      int x = 0, y = getPaddingTop(), line = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        if (x > 0 && x + w > available) {
          x = 0;
          y += line + gapY;
          line = 0;
        }
        int left = rtl ? width - getPaddingRight() - x - w : getPaddingLeft() + x;
        child.layout(left, y, left + w, y + h);
        x += w + gapX;
        line = Math.max(line, h);
      }
    }
  }
}
