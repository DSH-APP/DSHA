package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.InfoSurfacePolicy;
import com.deepseekharness.app.util.InfoSurfacePolicy.Kind;
import com.deepseekharness.app.util.UiText;

/**
 * ⓘ 说明弹层：按 {@link InfoSurfacePolicy} 在锚定气泡、紧凑弹窗、底部面板与居中弹窗之间选择。
 *
 * <p>四种形态都走 {@link DshaDialogBuilder}，保留 Dialog 的返回键、点外部取消与 TalkBack 焦点行为；窗口按卡片自身大小设置，
 * 「点外部」仍由系统 Dialog 判定。正文全部可滚动，标题与关闭入口固定，短屏和 1.3 倍字体下都能读完。
 */
final class InfoSurface {
  private InfoSurface() {}

  /** 气泡窗口四周给阴影留的透明边，单位 dp。 */
  private static final int SHADOW_PAD = 12;

  private static final int CARET_W = 16, CARET_H = 8;

  static void show(Context context, @Nullable View anchor, CharSequence title, CharSequence full) {
    boolean anchored = anchor != null && anchor.isAttachedToWindow();
    android.content.res.Configuration config = context.getResources().getConfiguration();
    Kind kind = InfoSurfacePolicy.kind(config.screenWidthDp, config.screenHeightDp, anchored);
    if (kind == Kind.POPOVER) {
      try {
        popover(context, anchor, title, full);
        return;
      } catch (RuntimeException ignored) {
        // 锚点在测量期间离开窗口等异常：退回居中弹窗，不让说明打不开。
        kind = Kind.DIALOG;
      }
    }
    if (kind == Kind.SHEET) sheet(context, anchor, title, full);
    else centered(context, anchor, title, full, kind);
  }

  // ---------------------------------------------------------------- 锚定气泡

  private static void popover(Context context, View anchor, CharSequence title, CharSequence full) {
    float density = context.getResources().getDisplayMetrics().density;
    Rect container = container(anchor);
    Rect icon = iconOnScreen(anchor);
    int containerWdp = px2dp(container.width(), density);
    int containerHdp = px2dp(container.height(), density);
    int widthDp = InfoSurfacePolicy.width(Kind.POPOVER, containerWdp);
    int widthPx = dp(context, widthDp);

    BoundedColumn card = new BoundedColumn(context);
    card.setBackgroundResource(R.drawable.bg_shell_card);
    card.setPadding(dp(context, 18), dp(context, 4), dp(context, 4), dp(context, 16));
    card.setElevation(dp(context, 6));
    card.setClickable(true);
    LinearLayout head = new LinearLayout(context);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = title(context, title, 16);
    head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton close = closeButton(context);
    head.addView(close, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
    card.addView(head, new LinearLayout.LayoutParams(-1, -2));
    TextView body = body(context, full, 14.5f);
    body.setPadding(0, 0, dp(context, 14), 0);
    card.setBody(scroll(context, body));
    card.setMaxHeightPx(dp(context, InfoSurfacePolicy.maxHeight(Kind.POPOVER, containerHdp)));
    card.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(container.height(), View.MeasureSpec.AT_MOST));

    InfoSurfacePolicy.Placement placement =
        InfoSurfacePolicy.place(
            containerWdp,
            containerHdp,
            px2dp(icon.left - container.left, density),
            px2dp(icon.top - container.top, density),
            px2dp(icon.right - container.left, density),
            px2dp(icon.bottom - container.top, density),
            widthDp,
            px2dp(card.getMeasuredHeight(), density));
    card.setMaxHeightPx(dp(context, placement.maxHeight));

    int pad = dp(context, SHADOW_PAD), caretH = dp(context, CARET_H);
    FrameLayout root = new FrameLayout(context);
    root.setClipChildren(false);
    root.setClipToPadding(false);
    FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(widthPx, -2);
    cardParams.leftMargin = pad;
    cardParams.rightMargin = pad;
    cardParams.topMargin = pad;
    cardParams.bottomMargin = pad;
    root.addView(card, cardParams);
    Caret caret = new Caret(context, placement.below);
    FrameLayout.LayoutParams caretParams =
        new FrameLayout.LayoutParams(dp(context, CARET_W), caretH + dp(context, 2));
    caretParams.leftMargin = pad + dp(context, placement.caretX) - dp(context, CARET_W) / 2;
    caretParams.gravity = placement.below ? Gravity.TOP : Gravity.BOTTOM;
    if (placement.below) caretParams.topMargin = pad - caretH;
    else caretParams.bottomMargin = pad - caretH;
    caret.setTranslationZ(card.getElevation() + 0.5f);
    caret.setOutlineProvider(null);
    root.addView(caret, caretParams);
    AlertDialog dialog = create(context, root, title);
    outsideCancels(root, card, dialog);
    close.setOnClickListener(v -> dialog.dismiss());
    restoreFocusOnDismiss(context, dialog, anchor);
    Window window = dialog.getWindow();
    // 期望卡片左上角的屏幕坐标；窗口原点 = 卡片 − 阴影边。
    int cardLeft = container.left + dp(context, placement.left);
    int cardTop = container.top + dp(context, placement.top);
    int[] target = {cardLeft - pad, cardTop - pad};
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
      window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(0f);
      window.setWindowAnimations(0);
      window.setGravity(Gravity.TOP | Gravity.START);
      WindowManager.LayoutParams lp = window.getAttributes();
      lp.x = target[0];
      lp.y = target[1];
      lp.width = widthPx + 2 * pad;
      lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
      window.setAttributes(lp);
    }
    root.setAlpha(0f);
    dialog.show();
    if (window == null) {
      root.setAlpha(1f);
      return;
    }
    // 窗口坐标系可能以内容区而非屏幕为原点（状态栏、分屏）：首帧前按实测位置校正一次再显示。
    View decor = window.getDecorView();
    decor
        .getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              int passes;

              @Override
              public boolean onPreDraw() {
                int[] at = new int[2];
                root.getLocationOnScreen(at);
                int dx = target[0] - at[0], dy = target[1] - at[1];
                if ((dx != 0 || dy != 0) && passes++ < 2) {
                  WindowManager.LayoutParams lp = window.getAttributes();
                  lp.x += dx;
                  lp.y += dy;
                  window.setAttributes(lp);
                  return false;
                }
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                enter(root, card, dp(context, placement.caretX), placement.below);
                return true;
              }
            });
  }

  private static void enter(View root, View card, int pivotX, boolean below) {
    root.setAlpha(1f);
    if (!ValueAnimator.areAnimatorsEnabled()) return;
    card.setPivotX(pivotX);
    card.setPivotY(below ? 0 : card.getHeight());
    root.setAlpha(0f);
    card.setScaleX(0.92f);
    card.setScaleY(0.92f);
    root.animate().alpha(1f).setDuration(160).start();
    card.animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(160)
        .setInterpolator(
            android.view.animation.AnimationUtils.loadInterpolator(
                root.getContext(), R.interpolator.ui_standard))
        .start();
  }

  /** 阴影边与小三角处的点按也算「外部」，与系统窗口外取消一致；读屏用户用 ✕ 或返回键关闭。 */
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  private static void outsideCancels(View root, View card, AlertDialog dialog) {
    root.setOnTouchListener(
        (v, e) -> {
          if (hit(card, e)) return false;
          if (e.getActionMasked() == MotionEvent.ACTION_UP) dialog.cancel();
          return true;
        });
  }

  private static boolean hit(View card, MotionEvent e) {
    return e.getX() >= card.getLeft()
        && e.getX() < card.getRight()
        && e.getY() >= card.getTop()
        && e.getY() < card.getBottom();
  }

  /** 活动窗口扣除系统栏与刘海后的可用区域（屏幕坐标）。 */
  private static Rect container(View anchor) {
    View root = anchor.getRootView();
    int[] at = new int[2];
    root.getLocationOnScreen(at);
    Rect r = new Rect(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
    WindowInsets insets = root.getRootWindowInsets();
    if (insets != null) {
      android.graphics.Insets bars =
          insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      r.left += bars.left;
      r.top += bars.top;
      r.right -= bars.right;
      r.bottom -= bars.bottom;
    }
    if (r.isEmpty()) throw new IllegalStateException("anchor window has no usable area");
    return r;
  }

  /** ⓘ 图标的屏幕矩形；没有末尾图标时用整个触发视图。 */
  private static Rect iconOnScreen(View anchor) {
    int[] at = new int[2];
    anchor.getLocationOnScreen(at);
    Rect r = new Rect(at[0], at[1], at[0] + anchor.getWidth(), at[1] + anchor.getHeight());
    if (anchor instanceof TextView) {
      TextView text = (TextView) anchor;
      Drawable end = text.getCompoundDrawablesRelative()[2];
      if (end != null) {
        int w = end.getBounds().width(), h = end.getBounds().height();
        boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int left = rtl ? r.left + text.getPaddingLeft() : r.right - text.getPaddingRight() - w;
        int top = r.top + (anchor.getHeight() - h) / 2;
        r.set(left, top, left + w, top + h);
      }
    }
    return r;
  }

  /** 气泡指向 ⓘ 的小三角：只描两条斜边，底边压住卡片描边。 */
  private static final class Caret extends View {
    private final boolean up;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Caret(Context context, boolean up) {
      super(context);
      this.up = up;
      fill.setColor(context.getColor(R.color.shell_surface));
      stroke.setColor(context.getColor(R.color.shell_line));
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(context.getResources().getDisplayMetrics().density);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 视图比三角高出 overlap，伸进卡片内压住卡片描边；只描两条斜边。 */
    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight();
      float overlap = 2 * getResources().getDisplayMetrics().density;
      float tip = up ? 0 : h, base = up ? h - overlap : overlap, edge = up ? h : 0;
      path.reset();
      path.moveTo(0, base);
      path.lineTo(w / 2, tip);
      path.lineTo(w, base);
      path.lineTo(w, edge);
      path.lineTo(0, edge);
      path.close();
      canvas.drawPath(path, fill);
      path.reset();
      path.moveTo(0, base);
      path.lineTo(w / 2, tip);
      path.lineTo(w, base);
      canvas.drawPath(path, stroke);
    }
  }

  // ---------------------------------------------------------------- 底部说明面板

  private static void sheet(
      Context context, @Nullable View anchor, CharSequence title, CharSequence full) {
    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    int heightDp = context.getResources().getConfiguration().screenHeightDp;
    BoundedColumn root = new BoundedColumn(context);
    root.setBackgroundResource(R.drawable.bg_shell_sheet);
    int side = dp(context, 24), bottom = dp(context, 20);
    root.setPadding(side, 0, side, bottom);
    LinearLayout drag = new LinearLayout(context);
    drag.setOrientation(LinearLayout.VERTICAL);
    View grab = new View(context);
    GradientDrawable handle = new GradientDrawable();
    handle.setColor(context.getColor(R.color.shell_line_strong));
    handle.setCornerRadius(dp(context, 2));
    grab.setBackground(handle);
    grab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams grabParams =
        new LinearLayout.LayoutParams(dp(context, 36), dp(context, 4));
    grabParams.gravity = Gravity.CENTER_HORIZONTAL;
    grabParams.topMargin = dp(context, 10);
    grabParams.bottomMargin = dp(context, 10);
    drag.addView(grab, grabParams);
    drag.addView(title(context, title, 18), new LinearLayout.LayoutParams(-1, -2));
    root.addView(drag, new LinearLayout.LayoutParams(-1, -2));
    TextView body = body(context, full, 15);
    body.setPadding(0, dp(context, 8), 0, 0);
    root.setBody(scroll(context, body));
    Button ok = softButton(context, UiText.text("知道了"));
    LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(-1, -2);
    okParams.topMargin = dp(context, 18);
    root.addView(ok, okParams);
    root.setMaxHeightPx(
        Math.round(InfoSurfacePolicy.maxHeight(Kind.SHEET, heightDp) * metrics.density));
    // 系统手势条 / 导航栏可能叠在窗口底部：按实际 inset 抬高内容，不写死。
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
          v.setPadding(side, 0, side, bottom + nav);
          return insets;
        });

    AlertDialog dialog = create(context, root, title);
    ok.setOnClickListener(v -> dialog.dismiss());
    restoreFocusOnDismiss(context, dialog, anchor);
    dragToDismiss(drag, root, dialog);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      window.setWindowAnimations(R.style.Animation_DSHA_Sheet);
    }
    dialog.show();
  }

  /** 拖动把手与标题下滑关闭；读屏用户用「知道了」或返回键关闭，不依赖手势。 */
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  private static void dragToDismiss(View handle, View sheet, AlertDialog dialog) {
    float threshold = 80 * sheet.getResources().getDisplayMetrics().density;
    float[] start = new float[1];
    handle.setOnTouchListener(
        (v, e) -> {
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              start[0] = e.getRawY();
              sheet.animate().cancel();
              return true;
            case MotionEvent.ACTION_MOVE:
              sheet.setTranslationY(Math.max(0, e.getRawY() - start[0]));
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              if (e.getActionMasked() == MotionEvent.ACTION_UP
                  && sheet.getTranslationY() > threshold) dialog.cancel();
              else sheet.animate().translationY(0).setDuration(160).start();
              return true;
            default:
              return false;
          }
        });
  }

  // ---------------------------------------------------------------- 居中弹窗（紧凑 / 收紧）

  private static void centered(
      Context context, @Nullable View anchor, CharSequence title, CharSequence full, Kind kind) {
    android.content.res.Configuration config = context.getResources().getConfiguration();
    boolean compact = kind == Kind.COMPACT_DIALOG;
    BoundedColumn card = new BoundedColumn(context);
    card.setBackgroundResource(R.drawable.bg_shell_card);
    Button action;
    if (compact) {
      // 矮窗：按钮与标题同行，竖向只留正文滚动。
      card.setPadding(dp(context, 24), dp(context, 8), dp(context, 12), dp(context, 16));
      LinearLayout head = new LinearLayout(context);
      head.setGravity(Gravity.CENTER_VERTICAL);
      head.addView(title(context, title, 18), new LinearLayout.LayoutParams(0, -2, 1));
      action = textButton(context, UiText.text("知道了"));
      head.addView(action, new LinearLayout.LayoutParams(-2, -2));
      card.addView(head, new LinearLayout.LayoutParams(-1, -2));
      TextView body = body(context, full, 15);
      body.setPadding(0, dp(context, 2), dp(context, 12), 0);
      card.setBody(scroll(context, body));
    } else {
      card.setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 16));
      card.addView(title(context, title, 18), new LinearLayout.LayoutParams(-1, -2));
      TextView body = body(context, full, 15);
      body.setPadding(0, dp(context, 10), 0, 0);
      card.setBody(scroll(context, body));
      action = softButton(context, UiText.text("知道了"));
      LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
      actionParams.gravity = Gravity.END;
      actionParams.topMargin = dp(context, 16);
      card.addView(action, actionParams);
    }
    card.setMaxHeightPx(dp(context, InfoSurfacePolicy.maxHeight(kind, config.screenHeightDp)));

    AlertDialog dialog = create(context, card, title);
    action.setOnClickListener(v -> dialog.dismiss());
    restoreFocusOnDismiss(context, dialog, anchor);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
      window.setGravity(Gravity.CENTER);
      window.setLayout(
          dp(context, InfoSurfacePolicy.width(kind, config.screenWidthDp)),
          ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    dialog.show();
  }

  // ---------------------------------------------------------------- 共用

  private static AlertDialog create(Context context, View content, CharSequence title) {
    AlertDialog dialog = new DshaDialogBuilder(context).keepWindowSize().setView(content).create();
    dialog.setCanceledOnTouchOutside(true);
    Window window = dialog.getWindow();
    if (window != null) {
      // MDC 按原背景内边距判定「外部」点按；换成透明背景后旧边距会误伤卡片边缘，交给 Dialog 默认判定。
      window.getDecorView().setOnTouchListener(null);
      window.setTitle(title);
    }
    content.setAccessibilityPaneTitle(title);
    return dialog;
  }

  /** 读屏开启时，关闭后把焦点还给触发的 ⓘ 行，免得回到页首重新找。 */
  private static void restoreFocusOnDismiss(
      Context context, AlertDialog dialog, @Nullable View anchor) {
    if (anchor == null) return;
    dialog.setOnDismissListener(
        d -> {
          AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
          if (manager != null && manager.isTouchExplorationEnabled() && anchor.isAttachedToWindow())
            anchor.post(
                () ->
                    anchor.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        });
  }

  private static TextView title(Context context, CharSequence value, float sp) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(sp);
    view.setTextColor(context.getColor(R.color.shell_text));
    view.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    view.setIncludeFontPadding(false);
    view.setLineSpacing(dp(context, 2), 1f);
    view.setAccessibilityHeading(true);
    view.setPadding(0, dp(context, 6), 0, dp(context, 6));
    return view;
  }

  private static TextView body(Context context, CharSequence value, float sp) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(sp);
    view.setTextColor(context.getColor(R.color.shell_text_secondary));
    view.setLineSpacing(0f, 1.3f);
    view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
    view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
    return view;
  }

  private static ScrollView scroll(Context context, View body) {
    ScrollView scroll = new ScrollView(context);
    scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
    scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
    return scroll;
  }

  private static Button softButton(Context context, CharSequence label) {
    Button button = new Button(context);
    button.setText(label);
    button.setAllCaps(false);
    button.setStateListAnimator(null);
    button.setBackgroundResource(R.drawable.bg_dialog_button);
    button.setBackgroundTintList(null);
    button.setTextColor(context.getColorStateList(R.color.action_text));
    button.setTextSize(15);
    button.setGravity(Gravity.CENTER);
    button.setIncludeFontPadding(false);
    button.setMinHeight(dp(context, 48));
    button.setMinimumHeight(dp(context, 48));
    button.setPadding(dp(context, 22), 0, dp(context, 22), 0);
    return button;
  }

  private static Button textButton(Context context, CharSequence label) {
    Button button = softButton(context, label);
    button.setBackgroundResource(R.drawable.bg_action_plain);
    button.setTextColor(context.getColor(R.color.shell_accent));
    button.setTypeface(null, android.graphics.Typeface.BOLD);
    button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
    return button;
  }

  private static ImageButton closeButton(Context context) {
    ImageButton close = new ImageButton(context);
    close.setImageResource(R.drawable.ic_terminal_close);
    close.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.shell_text_muted)));
    close.setBackgroundResource(R.drawable.bg_action_plain);
    int inset = dp(context, 13);
    close.setPadding(inset, inset, inset, inset);
    close.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    close.setContentDescription(UiText.text("关闭"));
    return close;
  }

  /** 竖向卡片：内容不超过上限时按内容收紧，超过时正文 ScrollView 吃掉剩余高度，标题与按钮不被挤出。 */
  private static final class BoundedColumn extends LinearLayout {
    private ScrollView body;
    private int maxHeightPx = Integer.MAX_VALUE;

    BoundedColumn(Context context) {
      super(context);
      setOrientation(VERTICAL);
    }

    void setBody(ScrollView scroll) {
      body = scroll;
      addView(scroll, new LayoutParams(-1, -2));
    }

    void setMaxHeightPx(int value) {
      maxHeightPx = Math.max(0, value);
      requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int limit = maxHeightPx;
      if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
        limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
      LayoutParams params = body == null ? null : (LayoutParams) body.getLayoutParams();
      if (params != null) {
        params.height = -2;
        params.weight = 0;
      }
      super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
      if (params != null && getMeasuredHeight() >= limit) {
        params.height = 0;
        params.weight = 1;
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY));
      }
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static int px2dp(int px, float density) {
    return Math.round(px / density);
  }
}
