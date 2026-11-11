package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebCrashReport;
import java.util.function.IntFunction;

/**
 * 对话页错误面板的显示层（实验性，docs/design/web-crash-20261102）。
 *
 * <p>只负责把 {@link WebCrashReport} 的判定渲染成结论、按钮、当前状态与更多操作，不执行任何恢复动作； 动作经 {@link
 * Listener} 交还 WebPreviewActivity。所有子控件按需查找，缺失时跳过，单测可用最小视图树驱动。
 */
final class WebCrashPanel {
  interface Listener {
    void onCrashAction(WebCrashReport.Action action);
  }

  /** 宽窄三档：按面板实际宽度切换，不按设备类型（与原型的容器断点一致）。 */
  private enum Mode {
    COMPACT,
    WIDE,
    TWO_COLUMN
  }

  private static final int WIDE_DP = 600;
  private static final int TWO_COLUMN_DP = 900;

  final View root;
  private final TextView title, detail;
  private final IntFunction<View> find;
  private final Listener listener;
  private final Context context;
  private Mode mode;
  private WebCrashReport.Kind kind;
  private WebCrashReport.Spec spec;
  private String technical = "";
  private boolean detailsOpen;

  /** 上一次渲染的状态行内容键；相同就不重建列表（避免错误态反复 show 时闪动）。 */
  private String renderedRows = "";

  /** 上一次渲染的图标资源；只在档位变化时高亮一次。 */
  private int renderedIcon = -1;

  WebCrashPanel(
      View root, TextView title, TextView detail, IntFunction<View> find, Listener listener) {
    this.root = root;
    this.title = title;
    this.detail = detail;
    this.find = find;
    this.listener = listener;
    context = root.getContext();
    click(R.id.web_error_back, () -> listener.onCrashAction(null));
    click(R.id.web_error_secondary, () -> act(spec == null ? null : spec.secondary));
    click(R.id.web_error_details_row, this::toggleDetails);
    click(R.id.web_error_copy, this::copyDetails);
    TextView label = text(R.id.web_error_state_label);
    if (label != null) label.setText(UiText.choose("当前状态", "Current state"));
    TextView more = text(R.id.web_error_more_label);
    if (more != null) more.setText(UiText.text("更多操作"));
    TextView detailsTitle = text(R.id.web_error_details_title);
    if (detailsTitle != null) detailsTitle.setText(UiText.choose("技术详情", "Technical details"));
    TextView detailsSub = text(R.id.web_error_details_sub);
    if (detailsSub != null) {
      detailsSub.setText(UiText.choose("入口、退出码、发生时间与内核版本", "Source, exit code, time and engine"));
      detailsSub.setVisibility(View.VISIBLE);
    }
    TextView copy = text(R.id.web_error_copy);
    if (copy != null) copy.setText(UiText.choose("复制详情", "Copy details"));
    root.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (right - left != oldRight - oldLeft) applyWidth(right - left);
        });
  }

  /**
   * 显示一个结论。
   *
   * @param titleOverride / detailOverride 仅 GENERIC 与 INVALID_ADDRESS 使用调用方原文；其它结论用本类文案。
   * @param technicalText 技术详情原文（入口、错误码、内核信息等协议值），不翻译。
   */
  void show(
      WebCrashReport.Kind kind,
      String titleOverride,
      String detailOverride,
      WebCrashReport.Facts facts,
      String technicalText,
      boolean browserAvailable) {
    this.kind = kind;
    spec = WebCrashReport.spec(kind);
    technical = technicalText == null ? "" : technicalText;
    boolean diagnostics = kind == WebCrashReport.Kind.PAGE_BLANK;
    TextView copyLabel = text(R.id.web_error_copy);
    if (copyLabel != null)
      copyLabel.setText(
          diagnostics
              ? UiText.choose("复制诊断", "Copy diagnostics")
              : UiText.choose("复制详情", "Copy details"));
    TextView detailsHead = text(R.id.web_error_details_title);
    if (detailsHead != null)
      detailsHead.setText(
          diagnostics
              ? UiText.choose("诊断摘要", "Diagnostic summary")
              : UiText.choose("技术详情", "Technical details"));
    String[] copy = copy(kind);
    boolean useOverride =
        kind == WebCrashReport.Kind.GENERIC || kind == WebCrashReport.Kind.INVALID_ADDRESS;
    if (title != null)
      UiMotion.crossFadeText(title, useOverride && titleOverride != null ? titleOverride : copy[0]);
    if (detail != null)
      UiMotion.crossFadeText(
          detail, UiText.raw(useOverride && detailOverride != null ? detailOverride : copy[1]));
    tone(spec.tone, icon(kind));
    Button primary = (Button) find.apply(R.id.web_retry);
    if (primary != null) {
      UiMotion.crossFadeText(primary, actionLabel(spec.primary));
      primary.setEnabled(spec.primary != WebCrashReport.Action.WAIT);
    }
    Button secondary = (Button) find.apply(R.id.web_error_secondary);
    if (secondary != null) UiMotion.crossFadeText(secondary, actionLabel(spec.secondary));
    renderRows(WebCrashReport.rows(kind, facts == null ? new WebCrashReport.Facts() : facts));
    TextView tech = text(R.id.web_error_details_text);
    if (tech != null) UiMotion.crossFadeText(tech, UiText.raw(technical));
    // 局部空白现场需要把复制诊断放在一层可见位置：默认展开摘要，而不是藏在「技术详情」里。
    if (diagnostics) {
      detailsOpen = true;
      visible(R.id.web_error_details_box, true);
      View chevron = find.apply(R.id.web_error_details_chevron);
      if (chevron != null) chevron.setRotation(90f);
      View row = find.apply(R.id.web_error_details_row);
      if (row != null) row.setStateDescription(UiText.choose("已展开", "Expanded"));
    }
    boolean browserUsable = browserAvailable && !spec.serviceDown;
    View browser = find.apply(R.id.web_error_browser);
    if (browser != null) {
      browser.setEnabled(browserUsable);
      browser.setAlpha(browserUsable ? 1f : 0.5f);
      TextView sub = text(R.id.web_error_browser_sub);
      if (sub != null) {
        sub.setText(UiText.choose("服务未就绪，暂不可用", "Unavailable until the service is ready"));
        sub.setVisibility(browserUsable ? View.GONE : View.VISIBLE);
      }
    }
    visible(R.id.web_error_browser, !isAction(WebCrashReport.Action.BROWSER));
    visible(R.id.web_error_logs, !isAction(WebCrashReport.Action.LOGS));
    visible(R.id.web_error_recovery, !isAction(WebCrashReport.Action.RECOVERY));
    tidyDividers();
    // 已接回保留的页面在下方，覆盖层半透明以示「页面还在」；其它情况下方已无页面。
    boolean kept = kind == WebCrashReport.Kind.EXITED_KEPT || kind == WebCrashReport.Kind.VERIFY;
    int background = context.getColor(R.color.shell_bg);
    root.setBackgroundColor(kept ? (background & 0x00FFFFFF) | 0xE6000000 : background);
    if (root.getWidth() > 0) applyWidth(root.getWidth());
    // 覆盖 WebView 前给 180ms 淡入 + 轻微上移，避免错误面板硬切覆盖页面（审计 F-01）。
    boolean wasHidden = root.getVisibility() != View.VISIBLE;
    root.setVisibility(View.VISIBLE);
    if (wasHidden) UiMotion.reveal(root);
  }

  WebCrashReport.Kind kind() {
    return root.getVisibility() == View.VISIBLE ? kind : null;
  }

  /** 主按钮由 Activity 统一绑定（保留旧 ID 与 runtimeSuspended 分支），这里只给出当前动作。 */
  WebCrashReport.Action primaryAction() {
    return spec == null ? WebCrashReport.Action.RELOAD : spec.primary;
  }

  private boolean isAction(WebCrashReport.Action action) {
    return spec.primary == action || spec.secondary == action;
  }

  private void act(WebCrashReport.Action action) {
    if (action != null && action != WebCrashReport.Action.WAIT) listener.onCrashAction(action);
  }

  private void toggleDetails() {
    detailsOpen = !detailsOpen;
    visible(R.id.web_error_details_box, detailsOpen);
    View chevron = find.apply(R.id.web_error_details_chevron);
    if (chevron != null) chevron.animate().rotation(detailsOpen ? 90f : 0f).setDuration(160);
    View row = find.apply(R.id.web_error_details_row);
    if (row != null)
      row.setStateDescription(
          detailsOpen ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
  }

  private void copyDetails() {
    try {
      android.content.ClipboardManager clipboard =
          context.getSystemService(android.content.ClipboardManager.class);
      if (clipboard == null) return;
      clipboard.setPrimaryClip(
          android.content.ClipData.newPlainText(
              UiText.choose("技术详情", "Technical details"), technical));
      // Android 13 起系统自带复制提示，避免重复。
      if (android.os.Build.VERSION.SDK_INT < 33)
        android.widget.Toast.makeText(
                context,
                UiText.choose("已复制技术详情", "Details copied"),
                android.widget.Toast.LENGTH_SHORT)
            .show();
    } catch (RuntimeException ignored) {
      // 剪贴板服务不可用时不影响面板其它操作。
    }
  }

  private void tone(WebCrashReport.Tone tone, int iconRes) {
    int color, soft;
    switch (tone) {
      case BAD:
        color = R.color.shell_bad;
        soft = R.color.shell_bad_soft;
        break;
      case WARN:
        color = R.color.shell_warn;
        soft = R.color.shell_warn_soft;
        break;
      default:
        color = R.color.shell_accent;
        soft = R.color.shell_accent_soft;
    }
    View badge = find.apply(R.id.web_error_badge);
    if (badge != null) badge.setBackgroundTintList(ColorStateList.valueOf(context.getColor(soft)));
    ImageView icon = (ImageView) find.apply(R.id.web_error_icon);
    if (icon != null) {
      boolean changed = renderedIcon != iconRes;
      renderedIcon = iconRes;
      icon.setImageResource(iconRes);
      icon.setImageTintList(ColorStateList.valueOf(context.getColor(color)));
      if (changed) UiMotion.flash(icon);
    }
  }

  private void renderRows(java.util.List<WebCrashReport.Row> rows) {
    View host = find.apply(R.id.web_error_state);
    if (!(host instanceof LinearLayout)) return;
    LinearLayout list = (LinearLayout) host;
    // 行内容没变就保留原视图：错误态可能被连续 show（鉴权刷新重试），整表重建会闪。
    String rowsKey = rows.toString();
    if (rowsKey.equals(renderedRows)) return;
    renderedRows = rowsKey;
    list.removeAllViews();
    String[] names = new String[rows.size()];
    float keyWidth = 0;
    android.text.TextPaint paint = new android.text.TextPaint();
    paint.setTextSize(sp(13));
    for (int i = 0; i < rows.size(); i++) {
      names[i] = layer(rows.get(i).layer);
      keyWidth = Math.max(keyWidth, paint.measureText(names[i]));
    }
    // 层名列按最长层名定宽，上限避免长语言把值挤到很窄。
    int keyPx = Math.min(Math.round(keyWidth) + dp(4), dp(132));
    for (int i = 0; i < rows.size(); i++) {
      WebCrashReport.Row row = rows.get(i);
      if (i > 0) {
        View line = new View(context);
        line.setBackgroundColor(context.getColor(R.color.shell_line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMarginStart(dp(12));
        list.addView(line, lp);
      }
      LinearLayout item = new LinearLayout(context);
      item.setOrientation(LinearLayout.HORIZONTAL);
      item.setPadding(dp(12), dp(11), dp(12), dp(11));
      item.setScreenReaderFocusable(true);
      View dot = new View(context);
      dot.setBackgroundResource(R.drawable.bg_web_crash_dot);
      dot.setBackgroundTintList(ColorStateList.valueOf(context.getColor(level(row.level))));
      dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
      dotParams.topMargin = dp(6);
      dotParams.setMarginEnd(dp(10));
      item.addView(dot, dotParams);
      TextView key = new TextView(context);
      key.setText(names[i]);
      key.setTextSize(13);
      key.setTextColor(context.getColor(R.color.shell_text_muted));
      item.addView(key, new LinearLayout.LayoutParams(keyPx, -2));
      LinearLayout values = new LinearLayout(context);
      values.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1);
      vp.setMarginStart(dp(8));
      item.addView(values, vp);
      TextView value = new TextView(context);
      value.setText(value(row));
      value.setTextSize(14);
      value.setTextColor(context.getColor(R.color.shell_text));
      values.addView(value, new LinearLayout.LayoutParams(-1, -2));
      String note = note(row);
      if (!note.isEmpty()) {
        TextView small = new TextView(context);
        small.setText(note);
        small.setTextSize(12);
        small.setTextColor(context.getColor(R.color.shell_text_muted));
        values.addView(small, new LinearLayout.LayoutParams(-1, -2));
      }
      list.addView(item, new LinearLayout.LayoutParams(-1, -2));
    }
    UiMotion.reveal(list);
  }

  /** 宽窄三档：窄屏单列；≥600dp 单列限宽、按钮并排；≥900dp 左右两栏。 */
  private void applyWidth(int widthPx) {
    if (widthPx <= 0) return;
    float density = context.getResources().getDisplayMetrics().density;
    int widthDp = Math.round(widthPx / density);
    Mode next =
        widthDp >= TWO_COLUMN_DP ? Mode.TWO_COLUMN : widthDp >= WIDE_DP ? Mode.WIDE : Mode.COMPACT;
    if (next == mode) return;
    mode = next;
    View columnsView = find.apply(R.id.web_error_columns);
    View mainView = find.apply(R.id.web_error_main);
    View sideView = find.apply(R.id.web_error_side);
    if (!(columnsView instanceof LinearLayout) || mainView == null || sideView == null) return;
    LinearLayout columns = (LinearLayout) columnsView;
    boolean two = mode == Mode.TWO_COLUMN;
    boolean wide = mode != Mode.COMPACT;
    columns.setOrientation(two ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    int maxWidth = two ? dp(1040) : wide ? dp(520) : -1;
    ViewGroup.LayoutParams cp = columns.getLayoutParams();
    cp.width = maxWidth > 0 ? Math.min(maxWidth, widthPx - dp(wide ? 80 : 0)) : -1;
    columns.setLayoutParams(cp);
    int pad = dp(wide ? 0 : 16);
    columns.setPadding(pad, dp(4), pad, dp(24));
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(two ? 0 : -1, -2, two ? 1.25f : 0);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(two ? 0 : -1, -2, two ? 1f : 0);
    if (two) sp.setMarginStart(dp(48));
    mainView.setLayoutParams(mp);
    sideView.setLayoutParams(sp);
    // 两栏时右栏标题不再需要上间距，与左栏结论顶部对齐。
    View moreLabel = find.apply(R.id.web_error_more_label);
    if (moreLabel != null) {
      ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) moreLabel.getLayoutParams();
      lp.topMargin = dp(two ? 0 : 22);
      moreLabel.setLayoutParams(lp);
    }
    int align = two ? Gravity.START : Gravity.CENTER_HORIZONTAL;
    View hero = find.apply(R.id.web_error_hero);
    if (hero instanceof LinearLayout) ((LinearLayout) hero).setGravity(align);
    if (title != null) {
      title.setGravity(align);
      title.setTextSize(wide ? 24 : 20);
    }
    if (detail != null) detail.setGravity(align);
    View badge = find.apply(R.id.web_error_badge);
    if (badge != null) {
      ViewGroup.LayoutParams bp = badge.getLayoutParams();
      bp.width = bp.height = dp(wide ? 76 : 64);
      badge.setLayoutParams(bp);
    }
    View actionsView = find.apply(R.id.web_error_actions);
    View primary = find.apply(R.id.web_retry), secondary = find.apply(R.id.web_error_secondary);
    if (actionsView instanceof LinearLayout && primary != null && secondary != null) {
      LinearLayout actions = (LinearLayout) actionsView;
      actions.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
      LinearLayout.LayoutParams sp2 =
          new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
      if (wide) sp2.setMarginStart(dp(8));
      else sp2.topMargin = dp(8);
      primary.setLayoutParams(pp);
      secondary.setLayoutParams(sp2);
    }
  }

  /** 隐藏行后只保留夹在两个可见行之间的分隔线（每处最多一条）。 */
  private void tidyDividers() {
    View host = find.apply(R.id.web_error_more);
    if (!(host instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) host;
    boolean seenRow = false;
    View pending = null;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (divider(child)) {
        child.setVisibility(View.GONE);
        if (seenRow && pending == null) pending = child;
      } else if (child.getVisibility() == View.VISIBLE) {
        if (pending != null) pending.setVisibility(View.VISIBLE);
        pending = null;
        seenRow = true;
      }
    }
  }

  private static boolean divider(View view) {
    return view.getClass() == View.class;
  }

  private void click(int id, Runnable action) {
    View view = find.apply(id);
    if (view != null) view.setOnClickListener(v -> action.run());
  }

  private void visible(int id, boolean visible) {
    View view = find.apply(id);
    if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private TextView text(int id) {
    View view = find.apply(id);
    return view instanceof TextView ? (TextView) view : null;
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private float sp(int value) {
    return android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
  }

  private static int icon(WebCrashReport.Kind kind) {
    switch (kind) {
      case BLOCKED:
        return R.drawable.ic_web_crash_paused;
      case RESTARTING:
        return R.drawable.ic_web_crash_restart;
      case EXITED:
      case EXITED_KEPT:
        return R.drawable.ic_web_crash_exit;
      case VERIFY:
        return R.drawable.ic_web_crash_wait;
      case STARTING:
        return R.drawable.ic_web_crash_start;
      case HUNG:
        return R.drawable.ic_web_crash_hung;
      case NETWORK:
        return R.drawable.ic_web_crash_network;
      case AUTH:
      case AUTH_REFRESH:
        return R.drawable.ic_web_crash_lock;
      case RENDER_CRASH:
        return R.drawable.ic_web_crash_render;
      case RENDER_RECLAIMED:
        return R.drawable.ic_web_crash_memory;
      case WEBVIEW_OUTDATED:
        return R.drawable.ic_web_crash_update;
      case WEBVIEW_INIT:
        return R.drawable.ic_web_crash_engine;
      default:
        return R.drawable.ic_web_crash_page;
    }
  }

  private static int level(WebCrashReport.Level level) {
    switch (level) {
      case GOOD:
        return R.color.shell_good;
      case WARN:
        return R.color.shell_warn;
      case BAD:
        return R.color.shell_bad;
      case RUNNING:
        return R.color.shell_accent;
      default:
        return R.color.shell_text_muted;
    }
  }

  /** 结论文案：{标题, 说明}。 */
  static String[] copy(WebCrashReport.Kind kind) {
    switch (kind) {
      case BLOCKED:
        return new String[] {
          UiText.choose("DSH 反复崩溃，已暂停自动重启", "DSH keeps crashing; auto-restart paused"),
          UiText.choose(
              "连续几次启动后都没能稳定运行。先查看原因，再选择安全启动或修复。",
              "It failed to stay up after several starts. Review the cause, then start safely or repair.")
        };
      case RESTARTING:
        return new String[] {
          UiText.choose("服务正在重新启动", "The service is restarting"),
          UiText.choose(
              "后台服务刚刚结束，正在重新启动。就绪后自动回到对话。",
              "The service just ended and is starting again. The conversation reopens when it is ready.")
        };
      case EXITED:
        return new String[] {
          UiText.choose("DSH 服务意外退出", "DSH service exited unexpectedly"),
          UiText.choose(
              "进程已经结束，不会自动重启。回到启动页查看日志并重新启动。",
              "The process ended and will not restart on its own. Check logs and restart from the start page.")
        };
      case EXITED_KEPT:
        return new String[] {
          UiText.choose("DSH 服务已停止", "DSH service stopped"),
          UiText.choose(
              "后台进程已确认退出，当前页面已保留。回到启动页重新启动即可。",
              "The background process has exited. This page is kept; restart from the start page.")
        };
      case VERIFY:
        return new String[] {
          UiText.choose("正在确认服务状态", "Checking the service"),
          UiText.choose(
              "暂时无法确认后台服务身份。确认后自动恢复当前页面，不会重新发送消息。",
              "The service identity is not confirmed yet. This page resumes once verified; nothing is resent.")
        };
      case STARTING:
        return new String[] {
          UiText.choose("DSH 正在启动", "DSH is starting"),
          UiText.choose(
              "服务就绪后即可进入对话，可以回启动页查看进度。",
              "The conversation opens once the service is ready. Check progress on the start page.")
        };
      case HUNG:
        return new String[] {
          UiText.choose("服务暂时没有响应", "The service is not responding"),
          UiText.choose(
              "本机端口暂时连不上，服务可能卡住或已经退出。稍后重试，或回启动页查看状态。",
              "The local port refused the connection; the service may be stuck or gone. Retry shortly, or check the start page.")
        };
      case NETWORK:
        return new String[] {
          UiText.choose("连不上对话服务", "Cannot reach the service"),
          UiText.choose(
              "这次请求没有连上服务。稍后重试；持续失败请回启动页查看日志。",
              "This request did not reach the service. Retry shortly, or check logs on the start page.")
        };
      case AUTH:
        return new String[] {
          UiText.choose("对话登录已过期", "Session sign-in expired"),
          UiText.choose(
              "已自动重新登录一次仍未通过。回到启动页重新进入对话即可。",
              "An automatic sign-in retry failed. Re-enter the conversation from the start page.")
        };
      case HTTP:
        return new String[] {
          UiText.choose("对话页面没能打开", "The page did not load"),
          UiText.choose(
              "服务返回了错误。可以重试，或回启动页查看服务状态。",
              "The service returned an error. Retry, or check its status on the start page.")
        };
      case AUTH_REFRESH:
        return new String[] {
          UiText.text("暂时无法进入对话"),
          UiText.choose(
              "获取对话凭据失败。重试一次；持续失败请回启动页重新进入。",
              "Getting the session credential failed. Retry, or re-enter from the start page.")
        };
      case RENDER_CRASH:
        return new String[] {
          UiText.choose("网页渲染进程崩溃了", "The page renderer crashed"),
          UiText.choose(
              "系统 WebView 出现异常，后台服务不受影响。重新打开通常就能恢复。",
              "System WebView hit an error; the service is unaffected. Reopening usually fixes it.")
        };
      case RENDER_RECLAIMED:
        return new String[] {
          UiText.choose("网页被系统回收了", "The system closed the page"),
          UiText.choose(
              "手机内存紧张时，系统会关闭后台网页。重新打开即可继续。",
              "Android frees background pages when memory is low. Reopen to continue.")
        };
      case WEBVIEW_OUTDATED:
        return new String[] {
          UiText.choose("系统 WebView 需要更新", "System WebView needs an update"),
          UiText.choose(
              "当前内核缺少对话页需要的能力。更新后重试，或先用系统浏览器打开。",
              "The engine lacks features this page needs. Update it, or open in a browser for now.")
        };
      case WEBVIEW_INIT:
        return new String[] {UiText.text("系统 WebView 无法启动"), UiText.text("请更新系统 WebView 后重试")};
      case INVALID_ADDRESS:
        return new String[] {UiText.text("对话地址无效"), UiText.text("请返回启动页，重新进入对话。")};
      case PAGE_BLANK:
        return new String[] {
          UiText.choose("页面没有渲染出来", "The page did not render"),
          UiText.choose(
              "页面已经加载，但路由挂载点没有内容。可以重新打开、用安全模式重试，或复制诊断摘要。",
              "The page loaded but its route mount stayed empty. Reopen, retry in safe mode, or copy the diagnostic summary.")
        };
      default:
        return new String[] {
          UiText.choose("暂时无法显示对话", "The conversation cannot be shown"),
          UiText.choose("可以重试，或回启动页查看服务状态。", "Retry, or check the service on the start page.")
        };
    }
  }

  static String actionLabel(WebCrashReport.Action action) {
    switch (action) {
      case HOME:
        return UiText.choose("返回启动页", "Back to start page");
      case RECHECK:
        return UiText.choose("重新验证", "Check again");
      case RELOAD:
        return UiText.choose("重新打开", "Reopen");
      case STORE:
        return UiText.choose("更新 WebView", "Update WebView");
      case BROWSER:
        return UiText.text("在系统浏览器中打开");
      case RECOVERY:
        return UiText.choose("查看原因与修复", "Review and repair");
      case SAFE_START:
        return UiText.choose("安全模式重试", "Retry in safe mode");
      case LOGS:
        return UiText.text("下载错误日志");
      case WAIT:
      default:
        return UiText.choose("正在重新启动…", "Restarting…");
    }
  }

  static String layer(WebCrashReport.Layer layer) {
    switch (layer) {
      case SERVICE:
        return UiText.choose("后台服务", "Service");
      case AUTO_RECOVERY:
        return UiText.choose("自动恢复", "Auto-recovery");
      case ENGINE:
        return UiText.text("网页内核");
      case PAGE:
        return UiText.choose("对话页面", "Page");
      case CONNECTION:
      default:
        return UiText.choose("连接", "Connection");
    }
  }

  static String value(WebCrashReport.Row row) {
    switch (row.value) {
      case SERVICE_RUNNING:
        return UiText.text("运行中");
      case SERVICE_STARTING:
        return UiText.text("启动中");
      case SERVICE_EXITED:
        return row.number == WebCrashReport.UNKNOWN_EXIT
            ? UiText.text("已退出")
            : UiText.format(UiText.choose("已退出 · 退出码 %s", "Exited · code %s"), row.number);
      case SERVICE_NOT_RESPONDING:
        return UiText.choose("端口不通", "Port not reachable");
      case SERVICE_UNCONFIRMED:
        return UiText.choose("身份未确认", "Identity not confirmed");
      case SERVICE_NOT_READY:
        return UiText.choose("尚未就绪", "Not ready");
      case AUTO_PAUSED:
        return UiText.format(
            UiText.choose("已暂停 · 连续失败 %s 次", "Paused · %s failures in a row"), row.number);
      case AUTO_IN_PROGRESS:
        return UiText.choose("正在进行", "In progress");
      case AUTO_NONE:
        return UiText.choose("不会自动重启", "No automatic restart");
      case AUTO_WAITING_CHECKS:
        return UiText.choose("保活检查中", "Health checks running");
      case AUTO_VERIFYING:
        return UiText.choose("正在校验", "Verifying");
      case AUTO_WATCHING:
        return UiText.choose("保活中", "Watching");
      case AUTO_OFF:
        return UiText.choose("当前不会自动重启", "Not restarting automatically");
      case ENGINE_OK:
        return UiText.choose("正常", "OK");
      case ENGINE_CRASHED:
        return UiText.choose("渲染进程崩溃", "Renderer crashed");
      case ENGINE_RECLAIMED:
        return UiText.choose("被系统回收", "Closed by the system");
      case ENGINE_OUTDATED:
        return UiText.choose("版本过旧", "Outdated");
      case ENGINE_FAILED:
        return UiText.choose("无法启动", "Cannot start");
      case PAGE_KEPT:
        return UiText.choose("已保留，可接回", "Kept, can resume");
      case PAGE_CLOSED:
        return UiText.choose("已关闭，需重新打开", "Closed; reopen needed");
      case PAGE_LOAD_FAILED:
        return UiText.text("加载失败");
      case PAGE_ROUTE_BLANK:
        return UiText.choose("路由空白", "Route blank");
      case PAGE_NOT_OPENED:
        return UiText.choose("尚未打开", "Not opened yet");
      case CONNECTION_NOT_APPLICABLE:
        return UiText.choose("不适用（服务不在）", "N/A (service down)");
      case CONNECTION_REFUSED:
        return UiText.choose("连接被拒绝", "Connection refused");
      case CONNECTION_ERROR:
        return UiText.format(UiText.choose("连接失败 · 错误码 %s", "Failed · error %s"), row.number);
      case CONNECTION_AUTH:
        return UiText.format(
            UiText.choose("认证失效 · HTTP %s", "Sign-in expired · HTTP %s"), row.number);
      case CONNECTION_HTTP:
        return "HTTP " + row.number;
      case CONNECTION_CREDENTIAL:
        return UiText.choose("凭据刷新失败", "Credential refresh failed");
      case CONNECTION_UNKNOWN:
      default:
        return UiText.choose("未检查", "Not checked");
    }
  }

  static String note(WebCrashReport.Row row) {
    switch (row.value) {
      case SERVICE_EXITED:
        switch (row.number) {
          case 137:
            return UiText.choose("被强制结束，常见于内存不足", "Killed, often due to low memory");
          case 139:
            return UiText.choose("段错误，多为原生模块异常", "Segfault, usually a native module");
          case 143:
            return UiText.choose("收到终止信号", "Received a stop signal");
          default:
            return "";
        }
      case AUTO_NONE:
        return UiText.choose("需要手动启动", "Start it manually");
      case AUTO_WAITING_CHECKS:
        return UiText.choose("连续 3 次无响应才会自动重启", "Restarts after 3 failed checks in a row");
      case CONNECTION_AUTH:
        return UiText.choose("已自动重试 1 次", "Retried once automatically");
      default:
        return "";
    }
  }
}
