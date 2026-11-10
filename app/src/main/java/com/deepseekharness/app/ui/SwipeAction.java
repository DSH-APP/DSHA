package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SwipeGesture;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 左滑动作（F2）：行尾一侧滑出动作条，滑动本身只负责露出动作，从不执行动作。
 *
 * <p>规则（与 Wave D 验收一致）：
 *
 * <ul>
 *   <li>滑动超过阈值只停在打开位置；点动作条上的按钮才算一次明确的动作意图。
 *   <li>破坏性动作必须带 {@link Action#confirm}：按钮只调用确认回调（宿主的既有确认弹窗），
 *       真正的执行回调 {@link Action#run} 不会由本组件触发；没有确认回调的破坏性动作不可点。
 *   <li>RTL（阿拉伯语）时动作条仍在行尾一侧，手势方向镜像为向右滑；判定走
 *       {@link SwipeGesture} 的纯逻辑。
 *   <li>动作条固定在末侧：LTR 向左、RTL 向右；与横向滚动内容冲突的列表由
 *       {@link #supported(View)} 判定后不挂接滑动。
 *   <li>无障碍：行进读屏时提供「显示操作」自定义动作；动作条按钮可聚焦，关闭时整条对读屏隐藏。
 * </ul>
 *
 * <p>同一个根视图里同时只保留一行打开，打开新行会先收起旧行。动效统一走 {@link UiMotion}，
 * 系统关闭动画时直接落到终态。
 */
final class SwipeAction {
  /** 行进读屏的「显示操作」自定义动作 id；见 res/values/ids.xml。 */
  static final int OPEN_ACTION = R.id.swipe_action_open;

  private static final Map<View, Host> OPEN_HOSTS = new WeakHashMap<>();

  private SwipeAction() {}

  /** 动作条上的一个动作。 */
  static final class Action {
    final CharSequence label;
    final CharSequence description;
    final int icon;
    final boolean destructive;

    /** 非破坏性动作直接执行；破坏性动作不使用它。 */
    final Runnable run;

    /** 破坏性动作只调用确认回调（宿主既有确认弹窗），由用户在那里再次确认。 */
    final Runnable confirm;

    private Action(
        CharSequence label,
        CharSequence description,
        int icon,
        boolean destructive,
        Runnable run,
        Runnable confirm) {
      this.label = label;
      this.description = description;
      this.icon = icon;
      this.destructive = destructive;
      this.run = run;
      this.confirm = confirm;
    }

    /** 非破坏性动作：点按即执行。 */
    static Action of(CharSequence label, int icon, Runnable run) {
      return new Action(label, null, icon, false, run, null);
    }

    /** 非破坏性动作，带一行说明（读屏与视觉都可读）。 */
    static Action of(CharSequence label, CharSequence description, int icon, Runnable run) {
      return new Action(label, description, icon, false, run, null);
    }

    /** 破坏性动作：只允许走确认回调；本组件从不直接执行删除 / 覆盖等操作。 */
    static Action destructive(
        CharSequence label, CharSequence description, int icon, Runnable confirm) {
      return new Action(label, description, icon, true, null, confirm);
    }

    boolean selectable() {
      return destructive ? confirm != null : run != null;
    }

    /** 动作条按钮的点击：破坏性动作只弹确认，不执行。 */
    void select() {
      if (!selectable()) return;
      if (destructive) confirm.run();
      else run.run();
    }
  }

  /** 一行的动作配置。 */
  static final class Model {
    final List<Action> actions = new ArrayList<>();
    boolean enabled = true;

    Model add(Action action) {
      if (action != null) actions.add(action);
      return this;
    }

    static Model of(Action... values) {
      Model model = new Model();
      if (values != null) for (Action action : values) model.add(action);
      return model;
    }
  }

  /**
   * 把行放进可滑动的容器。行必须已经在父容器里；父容器是横向滚动列表或行本身与横向内容冲突时
   * 直接原样返回，不挂接滑动。
   */
  static View attach(View row, Model model) {
    if (row == null || model == null || model.actions.isEmpty()) return row;
    if (row.getParent() instanceof Host host) {
      host.apply(model);
      return host;
    }
    if (!(row.getParent() instanceof ViewGroup parent)) return row;
    if (parent instanceof androidx.recyclerview.widget.RecyclerView) return row;
    if (!supported(row)) return row;
    int index = parent.indexOfChild(row);
    ViewGroup.LayoutParams params = row.getLayoutParams();
    parent.removeView(row);
    Host host = new Host(row.getContext());
    host.setRow(row);
    parent.addView(host, index, params);
    host.apply(model);
    return host;
  }

  /** RecyclerView 适配器用：先建容器，再把自己的行视图放进去。 */
  static Host host(Context context) {
    return new Host(context);
  }

  /** 与横向内容冲突时禁用滑动（横向滚动容器 / 横向 RecyclerView / 横向 ViewPager 等）。 */
  static boolean supported(View row) {
    ViewParent parent = row == null ? null : row.getParent();
    while (parent instanceof View ancestor) {
      if (ancestor instanceof Host) {
        parent = ancestor.getParent();
        continue;
      }
      if (ancestor instanceof android.widget.HorizontalScrollView) return false;
      if (ancestor.canScrollHorizontally(-1) || ancestor.canScrollHorizontally(1)) return false;
      parent = ancestor.getParent();
    }
    return true;
  }

  /** 行 + 行尾动作条。行内容包在一层不透明底里整体平移，避免拖动时透出后面的动作条。 */
  static final class Host extends FrameLayout {
    private final FrameLayout content;
    private final LinearLayout strip;
    private final int touchSlop;
    private final List<TextView> cells = new ArrayList<>();
    private View row;
    private Model model = new Model();
    private boolean enabled;
    private boolean open;
    private boolean openAtDown;
    private boolean intercepting;
    private boolean dragging;
    private float downX;
    private float downY;
    private int measuredStripWidth;

    Host(Context context) {
      super(context);
      touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
      strip = new LinearLayout(context);
      strip.setOrientation(LinearLayout.HORIZONTAL);
      strip.setGravity(Gravity.CENTER_VERTICAL);
      strip.setVisibility(INVISIBLE);
      strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      addView(
          strip,
          new FrameLayout.LayoutParams(
              LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END));
      content = new FrameLayout(context);
      content.setBackgroundColor(context.getColor(R.color.shell_surface));
      addView(
          content,
          new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** 放入要滑动的行（RecyclerView 适配器在建视图时调用；静态列表由 {@link #attach} 代劳）。 */
    void setRow(View row) {
      if (row == null) return;
      if (this.row != null) content.removeView(this.row);
      this.row = row;
      content.addView(
          row, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
      installAccessibility(row);
    }

    /** 当前承载的行；列表 / 详情绑定与错峰动画都应以它为准。 */
    View row() {
      return row;
    }

    /** 动作条容器（测试与读屏用）。 */
    View strip() {
      return strip;
    }

    boolean isOpen() {
      return open;
    }

    boolean enabled() {
      return enabled;
    }

    /** 行内容的水平位移（负数 = LTR 打开）。 */
    float translation() {
      return content.getTranslationX();
    }

    void apply(Model value) {
      model = value == null ? new Model() : value;
      enabled = model.enabled && !model.actions.isEmpty() && SwipeAction.supported(this);
      close(false);
      rebuildStrip();
      updateAccessibility();
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      // 适配器在建视图时还看不到真正的父容器：挂上窗口后再按横向冲突复核一次。
      enabled = model.enabled && !model.actions.isEmpty() && SwipeAction.supported(this);
      updateAccessibility();
    }

    @Override
    protected void onDetachedFromWindow() {
      unregister();
      super.onDetachedFromWindow();
    }

    private void rebuildStrip() {
      strip.removeAllViews();
      cells.clear();
      for (Action action : model.actions) {
        TextView cell = actionCell(action);
        cells.add(cell);
        strip.addView(
            cell,
            new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
      }
      strip.setVisibility(INVISIBLE);
      strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      measuredStripWidth = 0;
    }

    private TextView actionCell(Action action) {
      Context context = getContext();
      TextView cell = new TextView(context);
      cell.setText(action.label);
      cell.setTextSize(12.5f);
      cell.setTypeface(null, Typeface.BOLD);
      cell.setIncludeFontPadding(false);
      cell.setGravity(Gravity.CENTER);
      cell.setPadding(dp(12), dp(8), dp(12), dp(8));
      cell.setMinWidth(dp(84));
      cell.setMaxLines(2);
      cell.setEllipsize(TextUtils.TruncateAt.END);
      cell.setSingleLine(false);
      cell.setTextColor(
          context.getColor(action.destructive ? R.color.shell_on_accent : R.color.shell_text));
      cell.setBackgroundColor(
          context.getColor(action.destructive ? R.color.shell_bad : R.color.shell_raised));
      if (action.icon != 0) {
        Drawable icon = context.getDrawable(action.icon);
        if (icon != null) {
          icon = icon.mutate();
          icon.setTint(
              context.getColor(action.destructive ? R.color.shell_on_accent : R.color.shell_text));
          icon.setBounds(0, 0, dp(16), dp(16));
          cell.setCompoundDrawables(null, icon, null, null);
          cell.setCompoundDrawablePadding(dp(4));
          cell.setPadding(dp(12), dp(6), dp(12), dp(6));
        }
      }
      cell.setContentDescription(
          TextUtils.isEmpty(action.description)
              ? action.label
              : action.label + " · " + action.description);
      cell.setFocusable(true);
      cell.setClickable(true);
      boolean selectable = action.selectable();
      PressFeedback.disabled(
          cell,
          !selectable,
          selectable ? null : UiText.choose("需要确认后才能执行", "Confirmation required first"));
      cell.setOnClickListener(
          view -> {
            if (!action.selectable()) return;
            action.select();
            close(true);
          });
      PressFeedback.attach(cell, PressFeedback.Variant.BUTTON);
      return cell;
    }

    private void installAccessibility(View row) {
      ViewCompat.replaceAccessibilityAction(
          row,
          new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
              OPEN_ACTION, UiText.choose("显示操作", "Show actions")),
          null,
          (view, arguments) -> {
            if (!enabled) return false;
            open(true);
            return true;
          });
    }

    private void updateAccessibility() {
      if (row == null) return;
      if (enabled) {
        installAccessibility(row);
      } else {
        ViewCompat.replaceAccessibilityAction(
            row,
            new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                OPEN_ACTION, UiText.choose("显示操作", "Show actions")),
            null,
            null);
      }
    }

    /** 程序化 / 读屏打开动作条；{@code focusFirst=true} 时把焦点交给第一个动作。 */
    void open(boolean focusFirst) {
      if (!enabled) return;
      open = true;
      registerOpen();
      strip.setVisibility(VISIBLE);
      strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
      for (TextView cell : cells) cell.setFocusable(true);
      float target = SwipeGesture.settleOffset(true, stripWidth(), rtl());
      animateContent(target);
      if (row != null) row.announceForAccessibility(UiText.choose("已显示操作", "Actions shown"));
      if (focusFirst && !cells.isEmpty()) cells.get(0).requestFocus();
    }

    /** 程序化收起；{@code animate=false} 时立即回到原位（重建 / 解绑用）。 */
    void close(boolean animate) {
      open = false;
      unregister();
      if (!animate || !UiMotion.animationsEnabled(getContext())) {
        content.animate().cancel();
        content.setTranslationX(0f);
        strip.setVisibility(INVISIBLE);
        strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return;
      }
      animateContent(0f);
    }

    private void animateContent(float target) {
      content.animate().cancel();
      if (!UiMotion.animationsEnabled(getContext())) {
        content.setTranslationX(target);
        afterSettle(target);
        return;
      }
      content
          .animate()
          .translationX(target)
          .setDuration(200L)
          .setInterpolator(UiMotion.standard())
          .withEndAction(() -> afterSettle(target))
          .start();
    }

    private void afterSettle(float target) {
      if (target != 0f) return;
      strip.setVisibility(INVISIBLE);
      strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void registerOpen() {
      View root = getRootView();
      Host previous = OPEN_HOSTS.get(root);
      if (previous != null && previous != this) previous.close(true);
      OPEN_HOSTS.put(root, this);
    }

    private void unregister() {
      View root = getRootView();
      if (OPEN_HOSTS.get(root) == this) OPEN_HOSTS.remove(root);
    }

    private int stripWidth() {
      int width = strip.getMeasuredWidth();
      if (width > 0) measuredStripWidth = width;
      if (measuredStripWidth > 0) return measuredStripWidth;
      return dp(84) * Math.max(1, model.actions.size());
    }

    private boolean rtl() {
      return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    private boolean insideStrip(MotionEvent event) {
      if (strip.getVisibility() != VISIBLE) return false;
      return event.getX() >= strip.getLeft()
          && event.getX() <= strip.getRight()
          && event.getY() >= strip.getTop()
          && event.getY() <= strip.getBottom();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      if (!enabled) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          dragging = false;
          openAtDown = open;
          intercepting = open && !insideStrip(event);
          return intercepting;
        case MotionEvent.ACTION_MOVE:
          if (intercepting) return true;
          float dx = event.getX() - downX;
          float dy = event.getY() - downY;
          if (SwipeGesture.horizontal(dx, dy, touchSlop)
              && SwipeGesture.revealDirection(dx, rtl())) {
            intercepting = true;
            dragging = true;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            return true;
          }
          return false;
        default:
          return intercepting;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (!enabled) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_MOVE:
          float delta = event.getX() - downX;
          float offset;
          if (openAtDown) {
            int edge = stripWidth();
            offset = Math.max(-edge, Math.min(edge, openTarget() + delta));
          } else {
            offset = SwipeGesture.dragOffset(delta, stripWidth(), rtl());
          }
          content.setTranslationX(offset);
          strip.setVisibility(offset == 0f ? INVISIBLE : VISIBLE);
          return true;
        case MotionEvent.ACTION_UP:
          boolean tappedOpenRow = !dragging && openAtDown;
          settle(!tappedOpenRow && SwipeGesture.opens(content.getTranslationX(), stripWidth()));
          return true;
        case MotionEvent.ACTION_CANCEL:
          settle(false);
          return true;
        default:
          return true;
      }
    }

    private float openTarget() {
      return SwipeGesture.settleOffset(true, stripWidth(), rtl());
    }

    private void settle(boolean shouldOpen) {
      boolean next = shouldOpen && enabled;
      open = next;
      if (next) {
        registerOpen();
        strip.setVisibility(VISIBLE);
        strip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
      } else {
        unregister();
      }
      animateContent(SwipeGesture.settleOffset(next, stripWidth(), rtl()));
    }

    private int dp(int value) {
      return Math.round(value * getResources().getDisplayMetrics().density);
    }
  }
}
