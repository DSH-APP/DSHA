package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import androidx.appcompat.widget.AppCompatTextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TerminalWorkspacePolicy;
import com.deepseekharness.app.util.UiText;

/** 页面内菜单球：只监听自身手势，不申请悬浮窗权限、不拦截终端选字。 */
final class TerminalFloatingMenu extends AppCompatTextView {
  private boolean right = true, dragging;
  private float fraction = .6f, downX, downY, startX, startY;
  private final int slop;
  private final Runnable dim =
      () -> {
        if (!hasFocus() && !isPressed()) setAlpha(.45f);
      };
  private int reserveBottom;

  TerminalFloatingMenu(android.content.Context context) {
    super(context);
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    setId(R.id.terminal_menu_ball);
    setText(">_");
    setTextDirection(View.TEXT_DIRECTION_LTR);
    // >_ 是图标字形，保持 40dp 外观；可点击和拖动区域仍为完整的 48dp。
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
    setIncludeFontPadding(false);
    setTypeface(android.graphics.Typeface.MONOSPACE);
    setGravity(android.view.Gravity.CENTER);
    setTextColor(context.getColor(R.color.terminal_panel_text));
    setBackground(
        new android.graphics.drawable.InsetDrawable(
            context.getDrawable(R.drawable.bg_terminal_menu), dp(4)));
    setPadding(0, 0, 0, 0);
    setContentDescription(UiText.choose("打开会话侧栏", "Open sessions sidebar"));
    setSessionsExpanded(false);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setClickable(true);
    setElevation(dp(6));
    setMinimumWidth(dp(48));
    setMinimumHeight(dp(48));
    setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48)));
    setOnFocusChangeListener(
        (v, focus) -> {
          if (focus) wake();
          else scheduleDim();
        });
  }

  void setSessionsExpanded(boolean expanded) {
    androidx.core.view.ViewCompat.setStateDescription(
        this,
        expanded
            ? UiText.choose("会话侧栏已展开", "Sessions sidebar expanded")
            : UiText.choose("打开会话侧栏", "Open sessions sidebar"));
  }

  boolean dockRight() {
    return right;
  }

  float dockFraction() {
    return fraction;
  }

  void restore(boolean side, float position) {
    right = side;
    fraction = TerminalWorkspacePolicy.fraction(position);
    constrain();
  }

  void reserveBottom(int pixels) {
    reserveBottom = pixels;
    constrain();
  }

  void constrain() {
    if (!(getParent() instanceof View parent)) return;
    if (parent.getWidth() == 0 || getWidth() == 0) return;
    int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
    int height = parent.getHeight() - parent.getPaddingTop();
    setX(parent.getPaddingLeft() + TerminalWorkspacePolicy.ballX(width, getWidth(), dp(12), right));
    setY(
        parent.getPaddingTop()
            + TerminalWorkspacePolicy.ballY(height, getHeight(), dp(12), reserveBottom, fraction));
  }

  void resetPosition() {
    restore(true, .6f);
    wake();
    announceForAccessibility(UiText.choose("菜单位置已重置", "Menu position reset"));
  }

  private void wake() {
    removeCallbacks(dim);
    setAlpha(1f);
  }

  private void scheduleDim() {
    removeCallbacks(dim);
    postDelayed(dim, 2500);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!(getParent() instanceof View parent)) return super.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        wake();
        dragging = false;
        downX = event.getRawX();
        downY = event.getRawY();
        startX = getX();
        startY = getY();
        setPressed(true);
        return true;
      case MotionEvent.ACTION_MOVE:
        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
        if (Math.hypot(dx, dy) > slop) dragging = true;
        if (dragging) {
          getParent().requestDisallowInterceptTouchEvent(true);
          setX(
              Math.max(
                  parent.getPaddingLeft(),
                  Math.min(
                      parent.getWidth() - parent.getPaddingRight() - getWidth(), startX + dx)));
          setY(
              Math.max(
                  parent.getPaddingTop(),
                  Math.min(parent.getHeight() - getHeight() - reserveBottom, startY + dy)));
        }
        return true;
      case MotionEvent.ACTION_UP:
        setPressed(false);
        getParent().requestDisallowInterceptTouchEvent(false);
        if (dragging) {
          right = getX() + getWidth() / 2f >= parent.getWidth() / 2f;
          int low =
              parent.getPaddingTop()
                  + Math.min(
                      dp(12),
                      Math.max(0, parent.getHeight() - parent.getPaddingTop() - getHeight()));
          int range = Math.max(0, parent.getHeight() - getHeight() - dp(12) - reserveBottom - low);
          fraction = range == 0 ? .6f : TerminalWorkspacePolicy.fraction((getY() - low) / range);
          constrain();
        } else performClick();
        dragging = false;
        scheduleDim();
        return true;
      case MotionEvent.ACTION_CANCEL:
        setPressed(false);
        dragging = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        constrain();
        scheduleDim();
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
      right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
    } else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
      fraction =
          TerminalWorkspacePolicy.fraction(
              fraction + (code == KeyEvent.KEYCODE_DPAD_UP ? -.1f : .1f));
    } else return super.onKeyDown(code, event);
    wake();
    constrain();
    return true;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName(android.widget.Button.class.getName());
    info.addAction(
        new AccessibilityNodeInfo.AccessibilityAction(
            R.id.terminal_reset_ball, UiText.choose("重置菜单位置", "Reset menu position")));
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle arguments) {
    if (action == R.id.terminal_reset_ball) {
      resetPosition();
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    post(this::constrain);
    scheduleDim();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(dim);
    super.onDetachedFromWindow();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
