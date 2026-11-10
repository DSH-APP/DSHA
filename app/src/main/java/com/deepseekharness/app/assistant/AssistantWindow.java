package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.control.OwnedScreenOverlay;
import com.deepseekharness.app.util.OverlayPlacement;
import com.deepseekharness.app.util.OverlayTouchGuard;
import com.deepseekharness.app.util.ScreenWindowRegion;

/**
 * 悬浮助手：收起时是可拖动的状态胶囊，展开是对话卡片（记录 + 窗内输入）。
 *
 * <p>展开、收起、拖动、隐藏只改界面，不暂停、不取消；几何变化只要求 AI 下一步重新观察。窗口平时不可聚焦；
 * 只有用户点窗内输入框时才临时可聚焦并弹出键盘，此时标记“用户正在输入”，AI 的手机动作原地等待。
 * 用户点 ✕ 隐藏后，任务照跑，通知栏保留回复、显示悬浮窗与停止。
 */
final class AssistantWindow implements AutoCloseable {
  private static final String SURFACE = "overlay";

  private final Context context;
  private final AssistantSessionController controller;
  private final WindowManager manager;
  private Context themed;
  private LinearLayout root, header, panel, actions;
  private View dot, divider;
  private TextView status, messages, composingChip;
  private ScrollView scroll;
  private EditText input;
  private ImageButton sendButton, voiceButton;
  private Button moreButton, minimizeButton, closeButton;
  private Button pauseButton, resumeButton, chatButton, editorButton, stopButton;
  private Button notificationSettingsButton;
  private WindowManager.LayoutParams params;
  private volatile boolean added;
  private boolean renderedExpanded;
  private long draftRevision = -1;
  private boolean editorVisible;
  private boolean hiddenForScreen;
  private boolean userHidden;
  private boolean stopControlsRequired;
  private boolean menuOpen;
  private boolean inlineInput;
  private String renderedText = "";
  private final Runnable changed = this::render;
  private final OverlayTouchGuard guard =
      new OverlayTouchGuard(android.os.SystemClock::elapsedRealtime);
  private final Runnable stopExpired = this::renderStopButton;
  private int renderedWidth = -1;
  // 用户拖到的位置在进程内保留，下次出现时沿用；-1 表示默认右下角。
  private static int savedX = -1, savedY = -1;
  private float downRawX, downRawY;
  private int downX, downY;
  private boolean dragging;

  /** 展开的面板正被用户查看或输入：读屏前不收走它（自有窗口整窗排除）。 */
  boolean keptVisibleForUser() {
    return added
        && !hiddenForScreen
        && !userHidden
        && !editorVisible
        && controller.snapshot().expanded();
  }

  boolean visible() {
    return added;
  }

  /** 用户点 ✕ 隐藏的状态：任务照常可用，停止与回复在通知栏。 */
  boolean userHidden() {
    return userHidden;
  }

  boolean taskUiAvailable() {
    return added || hiddenForScreen || userHidden;
  }

  /** Remove the actual input window before observing pixels; polling never recreates it. */
  boolean hideForScreen() {
    if (controller.snapshot().expanded() || editorVisible)
      throw new IllegalStateException("ASSISTANT_LOCAL_RESUME_REQUIRED");
    if (hiddenForScreen || userHidden) return false;
    if (!added) throw new IllegalStateException("ASSISTANT_UI_UNAVAILABLE");
    manager.removeViewImmediate(root);
    added = false;
    hiddenForScreen = true;
    stopControlsRequired = false;
    OwnedScreenOverlay.clear("assistant");
    return true;
  }

  /** Losing the stop notification restores explicit native controls, even after a user hide. */
  void restoreStopControls() {
    stopControlsRequired = true;
    if (!added && (hiddenForScreen || userHidden) && !editorVisible && canAdd()) {
      try {
        manager.addView(root, params);
        added = true;
        hiddenForScreen = false;
        userHidden = false;
        guard.layoutChanged();
      } catch (RuntimeException unavailable) {
        return;
      }
    }
    render();
  }

  /** ✕：隐藏悬浮窗，任务继续；通知栏仍有回复、显示悬浮窗与停止。 */
  void hideByUser() {
    stopInlineInput(true);
    menuOpen = false;
    controller.collapse();
    if (added)
      try {
        manager.removeViewImmediate(root);
      } catch (RuntimeException ignored) {
      }
    added = false;
    hiddenForScreen = false;
    userHidden = true;
    OwnedScreenOverlay.clear("assistant");
  }

  /** 通知栏「显示悬浮窗」：以展开卡片出现，方便直接回复。 */
  void showByUser() {
    if (!added && root != null && canAdd()) {
      try {
        manager.addView(root, params);
        added = true;
        hiddenForScreen = false;
        userHidden = false;
        guard.layoutChanged();
      } catch (RuntimeException unavailable) {
        return;
      }
    }
    if (added) controller.expand();
    render();
  }

  private boolean canAdd() {
    return Settings.canDrawOverlays(context);
  }

  AssistantWindow(Context context, AssistantSessionController controller) {
    this.context = context;
    this.controller = controller;
    manager = context.getSystemService(WindowManager.class);
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  /** 按 App 自己的日夜偏好取色；Service 的资源不跟随 AppCompat 夜间模式。 */
  private Context themedContext() {
    Configuration config = new Configuration(context.getResources().getConfiguration());
    String theme = new com.deepseekharness.app.core.ConfigStore(context).getUiTheme();
    int night =
        com.deepseekharness.app.util.UiThemePreference.DARK.equals(theme)
            ? Configuration.UI_MODE_NIGHT_YES
            : com.deepseekharness.app.util.UiThemePreference.LIGHT.equals(theme)
                ? Configuration.UI_MODE_NIGHT_NO
                : config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
    config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
    // 配置上下文没有应用主题；调色板色经主题属性引用，必须再套上应用主题与当前调色板。
    return com.deepseekharness.app.ui.ThemeController.themed(
        context.createConfigurationContext(config));
  }

  private int color(int id) {
    return themed.getColor(id);
  }

  private GradientDrawable shape(int fill, int radiusDp, int stroke) {
    GradientDrawable value = new GradientDrawable();
    value.setColor(fill);
    value.setCornerRadius(dp(radiusDp));
    if (stroke != 0) value.setStroke(Math.max(1, dp(1) / 2 + 1), stroke);
    return value;
  }

  void show() {
    if (!canAdd()) return;
    hiddenForScreen = false;
    userHidden = false;
    themed = themedContext();
    root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(4), dp(2), dp(4), dp(4));
    root.setBackground(shape(color(R.color.card), 22, color(R.color.line)));
    root.setElevation(dp(8));

    // 头部：状态点 + 状态（同时是拖动把手）+ ⋯ / — / ✕
    header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(10), 0, 0, 0);
    root.addView(header, new LinearLayout.LayoutParams(-1, dp(46)));
    dot = new View(context);
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(9), dp(9));
    dotParams.setMarginEnd(dp(8));
    header.addView(dot, dotParams);
    status = new TextView(context);
    status.setTextColor(color(R.color.text));
    status.setTextSize(13.5f);
    status.setMaxLines(1);
    status.setSingleLine(true);
    status.setEllipsize(TextUtils.TruncateAt.END);
    status.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
    status.setOnClickListener(
        v -> {
          if (!controller.snapshot().expanded() && guard.accept()) controller.expand();
        });
    // 状态行同时是拖动把手：移动不超过触摸阈值仍算点按（展开），超过则移动整个悬浮窗。
    status.setOnTouchListener(this::dragTouch);
    moreButton = headerButton("⋯", UiText.choose("更多操作", "More actions"));
    moreButton.setOnClickListener(
        v -> {
          if (guard.accept()) {
            menuOpen = !menuOpen;
            render();
          }
        });
    minimizeButton = headerButton("—", UiText.choose("收起为胶囊", "Collapse to capsule"));
    minimizeButton.setOnClickListener(
        v -> {
          if (guard.accept()) collapse();
        });
    closeButton = headerButton("✕", UiText.choose("隐藏悬浮窗", "Hide floating window"));
    closeButton.setOnClickListener(
        v -> {
          if (guard.accept()) hideByUser();
        });

    // 卡片正文：对话记录 + 输入状态 + 输入行
    panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    root.addView(panel);
    divider = new View(context);
    divider.setBackgroundColor(color(R.color.line));
    panel.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1)));
    scroll = new ScrollView(context);
    messages = new TextView(context);
    messages.setTextColor(color(R.color.text));
    messages.setTextSize(14);
    messages.setLineSpacing(dp(2), 1f);
    messages.setPadding(dp(12), dp(8), dp(12), dp(8));
    messages.setTextIsSelectable(false);
    scroll.addView(messages);
    int transcript =
        Math.max(
            dp(140),
            Math.min(dp(260), context.getResources().getDisplayMetrics().heightPixels / 3));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, transcript));
    composingChip = new TextView(context);
    composingChip.setTextColor(color(R.color.text_secondary));
    composingChip.setTextSize(12);
    composingChip.setPadding(dp(12), 0, dp(12), dp(4));
    composingChip.setText(
        UiText.choose(
            "你正在输入：AI 的手机操作会停在原地，发送后接着做",
            "You are typing: the AI's phone actions wait here and continue after you send"));
    panel.addView(composingChip);
    LinearLayout inputRow = new LinearLayout(context);
    inputRow.setOrientation(LinearLayout.HORIZONTAL);
    inputRow.setGravity(Gravity.CENTER_VERTICAL);
    inputRow.setPadding(dp(6), dp(4), dp(4), dp(4));
    panel.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));
    input = new EditText(context);
    input.setTextColor(color(R.color.text));
    input.setHintTextColor(color(R.color.text_muted));
    input.setHint(UiText.choose("回复或补充要求…", "Reply or add instructions…"));
    input.setTextSize(14);
    input.setMaxLines(4);
    input.setMinHeight(dp(42));
    input.setPadding(dp(12), dp(8), dp(12), dp(8));
    input.setBackground(shape(color(R.color.raised), 14, 0));
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    // 窗口平时不可聚焦：点输入框时才切换为可聚焦，避免抢走其它应用的键盘。
    input.setOnTouchListener(
        (v, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_UP && !inlineInput && guard.accept())
            startInlineInput();
          return false;
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (inlineInput) controller.setDraft(s.toString());
          }

          public void afterTextChanged(Editable value) {}
        });
    input.setOnKeyListener(
        (v, keyCode, event) -> {
          if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            stopInlineInput(true);
            return true;
          }
          return false;
        });
    voiceButton = iconButton(R.drawable.ic_assistant_mic, UiText.choose("语音输入", "Voice input"));
    voiceButton.setOnClickListener(
        v -> {
          if (!guard.accept()) return;
          stopInlineInput(false);
          controller.beginComposing("voice");
          context.startActivity(
              new Intent(context, AssistantVoiceActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    inputRow.addView(voiceButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
    sendButton = iconButton(R.drawable.ic_assistant_send, UiText.choose("发送", "Send"));
    sendButton.setOnClickListener(
        v -> {
          if (guard.accept()) sendTyped();
        });
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    sendParams.setMarginStart(dp(2));
    inputRow.addView(sendButton, sendParams);

    // ⋯ 菜单：暂停 / 继续 / 打开完整会话 / 全屏输入；停止单独放在最下并需二次确认。
    actions = new LinearLayout(context);
    actions.setOrientation(LinearLayout.VERTICAL);
    actions.setPadding(dp(6), dp(4), dp(6), dp(4));
    actions.setBackground(shape(color(R.color.raised), 16, 0));
    LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
    actionsParams.setMargins(dp(4), dp(2), dp(4), dp(2));
    root.addView(actions, actionsParams);
    pauseButton = menuItem(UiText.choose("暂停", "Pause"), () -> controller.pause("USER_PAUSED"));
    resumeButton = menuItem(UiText.choose("继续", "Continue"), controller::resume);
    chatButton =
        menuItem(UiText.choose("打开完整会话", "Open full conversation"), controller::returnConversation);
    editorButton = menuItem(UiText.choose("全屏输入", "Full-screen input"), this::openEditor);
    View menuDivider = new View(context);
    menuDivider.setBackgroundColor(color(R.color.line));
    LinearLayout.LayoutParams menuDividerParams =
        new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
    menuDividerParams.setMargins(dp(6), dp(4), dp(6), dp(4));
    actions.addView(menuDivider, menuDividerParams);
    stopButton = menuItem("", null);
    stopButton.setTextColor(color(R.color.err));
    stopButton.setOnClickListener(v -> stopTapped());
    renderStopButton();
    notificationSettingsButton =
        menuItem(
            UiText.choose("打开系统通知设置", "Open Android notification settings"),
            () -> context.startActivity(AssistantService.notificationSettingsIntent(context)));
    actions.removeView(notificationSettingsButton);
    root.addView(notificationSettingsButton, new LinearLayout.LayoutParams(-1, dp(44)));

    params = new WindowManager.LayoutParams();
    params.type =
        Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    params.format = PixelFormat.TRANSLUCENT;
    params.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    params.gravity = Gravity.BOTTOM | Gravity.END;
    params.y = savedY >= 0 ? savedY : dp(24);
    params.x = savedX >= 0 ? savedX : dp(12);
    params.width = pillWidth();
    params.height = -2;
    // 平时不是输入法目标（ADJUST_RESIZE 会被换算为随其它应用的输入法上弹），只避让系统栏；
    // 窗内输入时才额外避让自己的输入法。
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    params.setFitInsetsTypes(WindowInsets.Type.systemBars());
    root.setOnTouchListener(
        (v, event) -> {
          // 窗内输入时点到窗外：交还焦点与键盘，未发送的草稿保留。
          if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE && inlineInput)
            stopInlineInput(true);
          return false;
        });
    root.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          // 展开变宽变高、转屏或键盘弹出后仍要整窗留在屏幕内。
          keepOnScreen();
          publish();
        });
    try {
      manager.addView(root, params);
      added = true;
      guard.layoutChanged();
      controller.addListener(changed);
      render();
    } catch (RuntimeException unavailable) {
      controller.pause("OVERLAY_UNAVAILABLE");
      OwnedScreenOverlay.clear("assistant");
    }
  }

  private int pillWidth() {
    return Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(236));
  }

  private int cardWidth() {
    return Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(24), dp(420));
  }

  private Button headerButton(String glyph, String description) {
    Button button = new Button(context);
    button.setText(glyph);
    button.setTextSize(16);
    button.setTextColor(color(R.color.text_secondary));
    button.setAllCaps(false);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setPadding(0, 0, 0, 0);
    button.setBackground(shape(0x00000000, 12, 0));
    button.setContentDescription(description);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(42), dp(42));
    p.setMarginStart(dp(2));
    header.addView(button, p);
    return button;
  }

  private ImageButton iconButton(int drawable, String description) {
    ImageButton button = new ImageButton(context);
    button.setImageResource(drawable);
    button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
    button.setContentDescription(description);
    button.setBackground(shape(0x00000000, 21, 0));
    button.setImageTintList(ColorStateList.valueOf(color(R.color.text_secondary)));
    return button;
  }

  private Button menuItem(String text, Runnable action) {
    Button button = new Button(context);
    button.setText(text);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setTextColor(color(R.color.text));
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    button.setPadding(dp(12), 0, dp(12), 0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setBackground(shape(0x00000000, 10, 0));
    actions.addView(button, new LinearLayout.LayoutParams(-1, dp(44)));
    if (action != null)
      button.setOnClickListener(
          v -> {
            if (!guard.accept()) return;
            menuOpen = false;
            action.run();
            render();
          });
    return button;
  }

  private void collapse() {
    stopInlineInput(true);
    menuOpen = false;
    controller.collapse();
  }

  /** 用户点窗内输入框：窗口临时可聚焦并弹出键盘；AI 的手机动作原地等待。 */
  private void startInlineInput() {
    if (!added || inlineInput) return;
    inlineInput = true;
    menuOpen = false;
    controller.beginComposing(SURFACE);
    params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    params.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
    try {
      manager.updateViewLayout(root, params);
    } catch (RuntimeException removed) {
      inlineInput = false;
      controller.endComposing(SURFACE);
      return;
    }
    guard.layoutChanged();
    input.setFocusable(true);
    input.setFocusableInTouchMode(true);
    input.requestFocus();
    var ime = context.getSystemService(InputMethodManager.class);
    if (ime != null) ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    // 个别 ROM 不给悬浮窗输入焦点：退回全屏编辑页，草稿保留。
    input.postDelayed(
        () -> {
          if (inlineInput && !input.hasFocus()) {
            controller.beginComposing("editor");
            stopInlineInput(false);
            openEditor();
          }
        },
        700);
  }

  /** 交还焦点与键盘；{@code release} 为 true 时同时放开等待中的手机动作。 */
  private void stopInlineInput(boolean release) {
    if (!inlineInput) return;
    inlineInput = false;
    var ime = context.getSystemService(InputMethodManager.class);
    if (ime != null && input != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
    if (input != null) input.clearFocus();
    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    params.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    params.setFitInsetsTypes(WindowInsets.Type.systemBars());
    if (added)
      try {
        manager.updateViewLayout(root, params);
      } catch (RuntimeException removed) {
        // 已被移除：下一次 show 重新建立。
      }
    guard.layoutChanged();
    if (release) controller.endComposing(SURFACE);
  }

  private void sendTyped() {
    String text = input.getText().toString();
    if (text.isBlank()) text = controller.snapshot().draft();
    if (text.isBlank()) return;
    controller.setDraft(text);
    // 发送成功后控制器清空草稿并唤醒等待中的手机动作；失败时草稿保留、仍在输入。
    if (controller.send(text)) stopInlineInput(false);
  }

  private boolean dragTouch(View view, MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        downX = params.x;
        downY = params.y;
        dragging = false;
        return false;
      }
      case MotionEvent.ACTION_MOVE -> {
        float dx = event.getRawX() - downRawX, dy = event.getRawY() - downRawY;
        if (!dragging
            && OverlayPlacement.isDrag(
                dx, dy, android.view.ViewConfiguration.get(context).getScaledTouchSlop())) {
          dragging = true;
          // 交给状态行一次 CANCEL，清掉按下/长按/点按回调，这次手势不会再触发展开。
          MotionEvent cancel = MotionEvent.obtain(event);
          cancel.setAction(MotionEvent.ACTION_CANCEL);
          view.onTouchEvent(cancel);
          cancel.recycle();
        }
        if (!dragging) return false;
        moveTo(
            OverlayPlacement.offset(downX, downRawX, event.getRawX()),
            OverlayPlacement.offset(downY, downRawY, event.getRawY()));
        return true;
      }
      case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (!dragging) return false;
        dragging = false;
        savedX = params.x;
        savedY = params.y;
        // 松手后新位置附近的按钮短暂不响应；旧观察作废，下一次手机操作先重新观察。
        guard.layoutChanged();
        controller.panelMoved();
        publish();
        return true;
      }
      default -> {
        return dragging;
      }
    }
  }

  private void moveTo(int x, int y) {
    var metrics = context.getResources().getDisplayMetrics();
    int width = root.getWidth() > 0 ? root.getWidth() : params.width;
    int height = root.getHeight() > 0 ? root.getHeight() : dp(52);
    int nextX = OverlayPlacement.clamp(x, width, metrics.widthPixels, dp(8));
    int nextY = OverlayPlacement.clamp(y, height, metrics.heightPixels, dp(8));
    if (nextX == params.x && nextY == params.y) return;
    params.x = nextX;
    params.y = nextY;
    try {
      manager.updateViewLayout(root, params);
    } catch (RuntimeException removed) {
      // 窗口已被移除：下一次 show 重新建立。
    }
  }

  private void keepOnScreen() {
    if (!added || dragging) return;
    int beforeX = params.x, beforeY = params.y;
    moveTo(params.x, params.y);
    if ((params.x != beforeX || params.y != beforeY) && savedX >= 0) {
      savedX = params.x;
      savedY = params.y;
    }
  }

  private void stopTapped() {
    switch (guard.stop()) {
      case IGNORED -> {}
      case ARMED -> {
        renderStopButton();
        stopButton.removeCallbacks(stopExpired);
        stopButton.postDelayed(stopExpired, OverlayTouchGuard.CONFIRM_MILLIS + 50);
      }
      case CONFIRMED -> {
        stopButton.removeCallbacks(stopExpired);
        menuOpen = false;
        renderStopButton();
        controller.stop();
      }
    }
  }

  private void renderStopButton() {
    if (stopButton == null) return;
    boolean armed = guard.stopArmed();
    stopButton.setText(armed ? UiText.choose("再点停止", "Tap again") : UiText.choose("停止", "Stop"));
    stopButton.setBackground(shape(armed ? color(R.color.err_bg) : 0x00000000, 10, 0));
    stopButton.setContentDescription(
        armed
            ? UiText.choose("再点一次停止当前任务", "Tap again to stop the current task")
            : UiText.choose("停止当前任务，需要点两次", "Stop the current task; requires two taps"));
  }

  private int phaseColor(AssistantSessionState.Phase phase) {
    return switch (phase) {
      case RUNNING, SENDING -> color(R.color.terminal_state_running);
      case PAUSING, PAUSED, UNKNOWN -> color(R.color.warn);
      case DISCONNECTED -> color(R.color.err);
      default -> color(R.color.text_muted);
    };
  }

  private void render() {
    if (!added) return;
    var state = controller.snapshot();
    boolean expanded = state.expanded();
    String target = UiText.choose("本机 DSH", "Local DSH");
    String progress = state.text().replaceAll("\\s+", " ").strip();
    if (progress.length() > 60) progress = progress.substring(progress.length() - 60);
    status.setText(
        expanded || progress.isEmpty()
            ? target + " · " + label(state.phase())
            : label(state.phase()) + "  " + progress);
    status.setContentDescription(
        String.format(
            UiText.choose(
                "%s，会话 %s，%s。点按展开聊天，按住拖动可移动",
                "%s, conversation %s, %s. Tap to expand chat; drag to move"),
            target,
            state.binding().sessionId(),
            label(state.phase())));
    GradientDrawable dotShape = new GradientDrawable();
    dotShape.setShape(GradientDrawable.OVAL);
    dotShape.setColor(phaseColor(state.phase()));
    dot.setBackground(dotShape);
    String text = state.text() + (detail(state).isEmpty() ? "" : "\n\n" + detail(state));
    if (!text.equals(renderedText)) {
      renderedText = text;
      messages.setText(text);
      scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    boolean composing = controller.composing();
    composingChip.setVisibility(composing ? View.VISIBLE : View.GONE);
    String draft = inlineInput ? input.getText().toString() : state.draft();
    sendButton.setEnabled(!draft.isBlank());
    sendButton.setAlpha(draft.isBlank() ? 0.4f : 1f);
    sendButton.setImageTintList(
        ColorStateList.valueOf(
            draft.isBlank() ? color(R.color.text_muted) : color(R.color.primary)));
    if (!inlineInput && draftRevision != state.draftRevision()) {
      draftRevision = state.draftRevision();
      if (!input.getText().toString().equals(state.draft())) input.setText(state.draft());
    }
    resumeButton.setEnabled(
        state.terminalKnown()
            && state.paused()
            && state.phase() != AssistantSessionState.Phase.STOPPED);
    resumeButton.setAlpha(resumeButton.isEnabled() ? 1f : 0.45f);
    panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
    int headerButtons = expanded ? View.VISIBLE : View.GONE;
    moreButton.setVisibility(headerButtons);
    minimizeButton.setVisibility(headerButtons);
    closeButton.setVisibility(headerButtons);
    moreButton.setTextColor(menuOpen ? color(R.color.primary) : color(R.color.text_secondary));
    if (!expanded) menuOpen = false;
    actions.setVisibility(
        (expanded && menuOpen) || stopControlsRequired ? View.VISIBLE : View.GONE);
    renderStopButton();
    notificationSettingsButton.setVisibility(stopControlsRequired ? View.VISIBLE : View.GONE);
    root.setBackground(
        shape(
            color(R.color.card), expanded || stopControlsRequired ? 20 : 23, color(R.color.line)));
    if (renderedExpanded != expanded || stopControlsRequired) {
      boolean expansionChanged = renderedExpanded != expanded;
      renderedExpanded = expanded;
      params.width = expanded || stopControlsRequired ? cardWidth() : pillWidth();
      // 只有真实的展开/收起或改宽才进入静置期；轮询重绘不会吞掉点按。
      if (expansionChanged || params.width != renderedWidth) guard.layoutChanged();
      renderedWidth = params.width;
      // 平时从不是输入法目标；只有窗内输入期间才可聚焦。
      if (!inlineInput) params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      try {
        manager.updateViewLayout(root, params);
      } catch (RuntimeException failure) {
        controller.pause("OVERLAY_REMOVED");
      }
    }
    publish();
  }

  static Intent editorIntent(Context context) {
    return new Intent(context, AssistantActivity.class)
        .putExtra("bound", true)
        .putExtra("focus_input", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }

  private void openEditor() {
    stopInlineInput(false);
    controller.beginComposing("editor");
    controller.endComposing(SURFACE);
    controller.collapse();
    setEditorVisible(true);
    context.startActivity(editorIntent(context));
  }

  void setEditorVisible(boolean visible) {
    editorVisible = visible;
    if (!added) return;
    root.clearFocus();
    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    manager.updateViewLayout(root, params);
    root.setVisibility(visible ? View.GONE : View.VISIBLE);
    if (!visible) guard.layoutChanged();
    publish();
  }

  static String label(AssistantSessionState.Phase phase) {
    return switch (phase) {
      case CONNECTING -> UiText.choose("连接中", "Connecting");
      case IDLE -> UiText.choose("就绪", "Ready");
      case SENDING -> UiText.choose("发送中", "Sending");
      case RUNNING -> UiText.choose("执行中", "Running");
      case PAUSING -> UiText.choose("正在暂停", "Pausing");
      case PAUSED -> UiText.choose("已暂停", "Paused");
      case STOPPING -> UiText.choose("正在停止", "Stopping");
      case STOPPED -> UiText.choose("已停止", "Stopped");
      case UNKNOWN -> UiText.choose("状态待核对", "Status unknown");
      case DISCONNECTED -> UiText.choose("连接已断开", "Disconnected");
    };
  }

  static String detail(AssistantSessionController.Snapshot state) {
    return switch (state.detail()) {
      case "ASSISTANT_STOP_NOTIFICATION_REQUIRED" ->
          AssistantService.taskGuidance("ASSISTANT_STOP_NOTIFICATION_REQUIRED");
      case "USER_PAUSED" ->
          UiText.choose(
              "任务已暂停。确认停止后，点继续会先重新观察屏幕。",
              "Task paused. Continue after cancellation is confirmed to observe the screen again.");
      case "OBSERVE_REQUIRED" ->
          UiText.choose(
              "下一次手机操作前会重新观察屏幕。",
              "The screen must be observed again before the next phone action.");
      case "CONNECTION_CHANGED" ->
          UiText.choose(
              "连接已改变。返回悬浮助手重新选择会话。",
              "The connection changed. Choose a conversation again in the assistant.");
      case "" ->
          state.phase() == AssistantSessionState.Phase.STOPPED
              ? UiText.choose(
                  "当前任务已停止。输入新消息可在此会话开始新任务。",
                  "Task stopped. Send a new message to begin another task in this conversation.")
              : "";
      default ->
          state.detail().matches("[A-Z][A-Z0-9_]+")
              ? UiText.choose(
                  "手机操作已暂停；确认会话状态后继续。",
                  "Phone actions paused; check the conversation before continuing.")
              : state.detail();
    };
  }

  private void publish() {
    if (editorVisible) {
      OwnedScreenOverlay.clear("assistant");
      return;
    }
    // 助手面板展开时也按自有窗口整窗排除，不作为“展开即暂停”的信号。
    publish(root, "assistant", false);
  }

  static void publish(View view, String owner, boolean expanded) {
    if (view == null || !view.isAttachedToWindow() || view.getVisibility() != View.VISIBLE) {
      OwnedScreenOverlay.clear(owner);
      return;
    }
    int[] position = new int[2];
    view.getLocationOnScreen(position);
    var node = view.createAccessibilityNodeInfo();
    try {
      OwnedScreenOverlay.publish(
          owner,
          node.getWindowId(),
          new ScreenWindowRegion.Bounds(
              position[0],
              position[1],
              position[0] + view.getWidth(),
              position[1] + view.getHeight()),
          expanded);
    } finally {
      node.recycle();
    }
  }

  @Override
  public void close() {
    stopInlineInput(true);
    controller.removeListener(changed);
    OwnedScreenOverlay.clear("assistant");
    if (added)
      try {
        manager.removeViewImmediate(root);
      } catch (RuntimeException ignored) {
      }
    added = false;
    hiddenForScreen = false;
    userHidden = false;
  }
}
