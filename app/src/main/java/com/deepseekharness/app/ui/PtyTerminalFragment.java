package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.R;
import com.deepseekharness.app.TerminalRuntimeService;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.core.TerminalRuntimeProtection;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalExtraKeys;
import com.deepseekharness.app.util.TerminalInput;
import com.deepseekharness.app.util.TerminalOutputSnapshot;
import com.deepseekharness.app.util.UiText;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/** 原生 PTY 内容面板；宿主只管导航，进程始终属于进程级 owner。 */
public final class PtyTerminalFragment extends Fragment
    implements TerminalPane, TerminalViewClient, PtySession.Listener {
  public static final String KEY_PTY = "term_pty";

  /** 打开终端时显式指定的初始 guest 命令；只消费一次，不随旋转或进程重建重放。 */
  public static final String ARG_GUEST_COMMAND = "terminal_guest_command";

  public static final String ARG_GUEST_TITLE = "terminal_guest_title";

  private static final String KEY_FONT_PX = "term_font_px";
  private static final int FONT_MIN_SP = 8, FONT_MAX_SP = 24, FONT_DEF_SP = 13;
  private static final TerminalSessionOwner OWNER = TerminalSessionOwner.shared();
  private static final java.util.WeakHashMap<PtySession, Integer> SCROLL_ROWS =
      new java.util.WeakHashMap<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final TerminalInput.Modifiers modifiers = new TerminalInput.Modifiers();
  private final TerminalSessionOwner.PtyObserver tabsObserver =
      () ->
          main.post(
              () -> {
                if (getView() != null) attachSelected();
              });
  private HarnessController c;

  /** 输入 / 切换热路径只读缓存结论（T12 ANR），门禁本身不放宽。 */
  private TerminalReadiness readiness;

  private TerminalView view;
  private InputFrame inputFrame;
  private TextView title, ctrlBtn, altBtn;
  private LinearLayout extraKeysBox;
  private PtySession attachedSession;
  private PtySession.Listener sessionListener;
  private TerminalRuntimeProtection.Ticket creating;
  private boolean creationPending;
  private long viewGeneration, operationGeneration;
  private AlertDialog pasteDialog;
  private AlertDialog inputProblemDialog;
  private AlertDialog actionsDialog;
  private String startError = "";
  private final java.util.concurrent.atomic.AtomicBoolean redrawPending =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  public static boolean preferred(Context ctx) {
    return prefs(ctx).getBoolean(KEY_PTY, true);
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(TerminalExtraKeys.PREFS, Context.MODE_PRIVATE);
  }

  /** 仅兼容显式关闭调用；页面解绑不得调用。 */
  public static void shutdown() {
    OWNER.requestPtyShutdown();
  }

  public static void shutdownAndWait(long timeoutMs)
      throws java.io.IOException, InterruptedException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new java.io.IOException(UiText.text("请在维护线程等待终端退出"));
    OWNER.closeAllPtyAndConfirm(timeoutMs);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    return inflater.inflate(R.layout.fragment_pty_terminal, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View root, @Nullable Bundle state) {
    viewGeneration++;
    c = HarnessController.get(requireContext());
    readiness = new TerminalReadiness(requireContext(), c.proot());
    inputFrame = root.findViewById(R.id.pty_input_frame);
    inputFrame.allowed = this::inputAllowed;
    inputFrame.clipboardPaste = this::paste;
    inputFrame.lostWindowFocus = this::clearModifiers;
    view = root.findViewById(R.id.pty_view);
    title = root.findViewById(R.id.pty_title);
    configureNativeView(view);
    applyFontSize(fontSp());
    buildExtraKeys(root.findViewById(R.id.pty_keys));
    root.findViewById(R.id.pty_font_dec).setOnClickListener(v -> changeFont(-1));
    root.findViewById(R.id.pty_font_inc).setOnClickListener(v -> changeFont(1));
    root.findViewById(R.id.pty_simple)
        .setOnClickListener(
            v -> {
              if (getActivity() instanceof TerminalPane.Host)
                ((TerminalPane.Host) getActivity()).switchTerminalMode(false);
            });
    root.findViewById(R.id.terminal_new).setOnClickListener(v -> newSession());
    attachSelected();
  }

  @Override
  public void newSession() {
    newSessionWithCommand(null, null);
  }

  /**
   * 消费打开终端时传入的固定命令并创建新会话；命令只执行一次，创建失败不自动重放。
   *
   * <p>参数在调用前移除，旋转重建的 Fragment 不会再次拿到同一条命令；真正的进程生命周期与
   * 关闭核验仍由 PTY owner 负责。
   */
  private boolean startInitialGuestCommand() {
    Bundle args = getArguments();
    if (args == null) return false;
    String[] command = args.getStringArray(ARG_GUEST_COMMAND);
    if (command == null || command.length == 0) {
      args.remove(ARG_GUEST_COMMAND);
      args.remove(ARG_GUEST_TITLE);
      return false;
    }
    String guestTitle = args.getString(ARG_GUEST_TITLE, "");
    args.remove(ARG_GUEST_COMMAND);
    args.remove(ARG_GUEST_TITLE);
    newSessionWithCommand(command, guestTitle == null || guestTitle.isEmpty() ? null : guestTitle);
    return true;
  }

  /** 固定 guest command 只影响新会话 argv；保护票与默认新建共用同一条创建时序。 */
  public void newSessionWithCommand(String[] guestCommand, String sessionTitle) {
    if (!isResumed() || view == null || creationPending || c == null) return;
    if (getActivity() instanceof TerminalActivity
        && !((TerminalActivity) getActivity()).allowTerminalCreation()) return;
    String blocked = TerminalFragment.terminalBlockMessage(c.proot());
    if (!blocked.isEmpty()) {
      startError = blocked;
      attachSelected();
      return;
    }
    creationPending = true;
    startError = "";
    final long generation = ++operationGeneration;
    final long targetView = viewGeneration;
    final var proot = c.proot();
    final String[] command = guestCommand == null ? null : guestCommand.clone();
    final var created =
        new java.util.concurrent.atomic.AtomicReference<
            com.deepseekharness.app.util.TerminalTabs.Tab<PtySession>>();
    try {
      creating =
          TerminalRuntimeService.requestFromVisible(
              requireActivity(),
              TerminalRuntimeProtection.Kind.PTY,
              new TerminalRuntimeProtection.Admission() {
                @Override
                public void onReady(TerminalRuntimeProtection.Ticket ticket) {
                  if (!isResumed()
                      || view == null
                      || viewGeneration != targetView
                      || generation != operationGeneration) {
                    ticket.cancel();
                    return;
                  }
                  new Thread(
                          () -> {
                            String problem = "";
                            try {
                              created.set(OWNER.createPty(ticket, proot, 80, 24, null, command));
                            } catch (Exception error) {
                              problem = SensitiveData.redact(String.valueOf(error));
                            }
                            final String result = problem;
                            main.post(
                                () -> {
                                  var tab = created.get();
                                  if (tab != null && sessionTitle != null)
                                    OWNER.renamePty(tab.id, sessionTitle);
                                  if (generation != operationGeneration) return;
                                  creationPending = false;
                                  creating = null;
                                  startError = result;
                                  if (view != null && viewGeneration == targetView)
                                    attachSelected();
                                });
                          },
                          "dsha-create-pty")
                      .start();
                }

                @Override
                public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {
                  if (generation != operationGeneration) return;
                  creationPending = false;
                  creating = null;
                  startError = SensitiveData.redact(reason);
                  if (view != null && viewGeneration == targetView) attachSelected();
                }
              });
    } catch (Exception error) {
      creationPending = false;
      creating = null;
      startError = SensitiveData.redact(String.valueOf(error));
    }
    attachSelected();
  }

  @Override
  public boolean selectSession(long id) {
    boolean selected = OWNER.selectPty(id);
    if (selected) attachSelected();
    return selected;
  }

  @Override
  public void closeSession(long id) {
    final long targetView = viewGeneration;
    OWNER.closePtyAsync(
        id,
        5000,
        (epoch, kind, closed, error) ->
            main.post(
                () -> {
                  if (!OWNER.ownerEpoch().equals(epoch)
                      || view == null
                      || targetView != viewGeneration) return;
                  attachSelected();
                  if (!error.isEmpty())
                    Toast.makeText(
                            requireContext(),
                            UiText.format("关闭失败，会话仍保留：%s", SensitiveData.redact(error)),
                            Toast.LENGTH_LONG)
                        .show();
                }));
    attachSelected();
  }

  private void attachSelected() {
    if (view == null || getView() == null) return;
    var tab = OWNER.currentPty();
    PtySession selected = tab == null ? null : tab.value;
    if (selected != attachedSession) {
      saveScroll();
      if (attachedSession != null) attachedSession.detachListener(sessionListener);
      dismissActions();
      dismissPaste();
      if (inputProblemDialog != null) inputProblemDialog.dismiss();
      clearModifiers();
      replaceNativeView();
      attachedSession = selected;
      sessionListener = selected == null ? null : listenerFor(selected);
      if (selected != null && selected.session() != null) {
        view.attachSession(selected.session());
        view.onScreenUpdated();
        restoreScroll();
      }
      InputMethodManager im =
          (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      if (im != null && view.hasFocus()) im.restartInput(view);
    }
    if (selected != null && isResumed()) selected.attachListener(sessionListener);
    boolean allowed = inputAllowed();
    view.setVisibility(selected == null ? View.INVISIBLE : View.VISIBLE);
    view.setEnabled(selected != null); // 输入另经门禁，已退出的历史仍可选字/回看。
    getView()
        .findViewById(R.id.pty_empty)
        .setVisibility(selected == null ? View.VISIBLE : View.GONE);
    getView().findViewById(R.id.terminal_new).setEnabled(!creationPending);
    String status = "";
    if (readiness != null && !readiness.ready()) status = UiText.text("环境未就绪，请先到「安装」页完成安装");
    else if (!startError.isEmpty()) status = UiText.format("终端启动失败：%s", startError);
    else if (creationPending) status = UiText.choose("正在准备终端保护…", "Preparing terminal protection…");
    else if (tab != null && tab.isClosing()) status = UiText.text("正在关闭此终端…");
    else if (tab != null && tab.isUnconfirmed())
      status =
          UiText.format(
              UiText.choose("关闭未确认，输入已停用：%s", "Close unconfirmed; input disabled: %s"),
              SensitiveData.redact(tab.error()));
    else if (selected != null && !selected.cleanupError().isEmpty())
      status = SensitiveData.redact(selected.cleanupError());
    else if (selected != null && tab != null && OWNER.isPtyProtectionLost(tab.id))
      status =
          UiText.choose(
              "终端后台保护已丢失，输入已停用；请从菜单关闭并核验会话。",
              "Terminal background protection was lost; input is disabled. Close and verify the session from the menu.");
    else if (selected != null && !selected.isRunning()) status = UiText.text("会话已结束");
    title.setText(status);
    title.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
    TerminalTabBar.render(
        getView(),
        OWNER.ptyTabs(),
        new TerminalTabBar.Actions() {
          public void select(long id) {
            selectSession(id);
          }

          public void close(long id) {
            if (getActivity() instanceof TerminalPane.Host)
              ((TerminalPane.Host) getActivity()).requestCloseSession(true, id);
          }
        });
    notifyHost();
  }

  private PtySession.Listener listenerFor(PtySession selected) {
    return new PtySession.Listener() {
      private boolean active() {
        return attachedSession == selected;
      }

      public void onOutput() {
        if (active()) PtyTerminalFragment.this.onOutput();
      }

      public void onTitle(String value) {
        if (active()) PtyTerminalFragment.this.onTitle(value);
      }

      public void onExit(int status) {
        if (active()) PtyTerminalFragment.this.onExit(status);
      }

      public void onCopy(String text) {
        if (active()) PtyTerminalFragment.this.onCopy(text);
      }

      public void onPasteRequest() {
        if (active()) PtyTerminalFragment.this.onPasteRequest();
      }

      public void onBell() {
        if (active()) PtyTerminalFragment.this.onBell();
      }

      public void onInputProblem(int reason) {
        if (active()) PtyTerminalFragment.this.onInputProblem(reason);
      }
    };
  }

  private void notifyHost() {
    if (getActivity() instanceof TerminalPane.Host)
      ((TerminalPane.Host) getActivity()).onTerminalPaneChanged();
  }

  private void buildExtraKeys(LinearLayout box) {
    if (box == null) return;
    extraKeysBox = box;
    box.removeAllViews();
    ctrlBtn = null;
    altBtn = null;
    var context = requireContext();
    TerminalExtraKeys.Profile profile =
        TerminalExtraKeys.profileFor(getResources().getConfiguration().smallestScreenWidthDp);
    var rows =
        TerminalExtraKeys.decode(
            prefs(context).getString(TerminalExtraKeys.prefKey(profile), ""), profile);
    boolean anyRow = false;
    for (var actions : rows) {
      if (actions.isEmpty()) continue;
      anyRow = true;
      HorizontalScrollView scroll = new HorizontalScrollView(context);
      scroll.setHorizontalScrollBarEnabled(false);
      scroll.setFillViewport(true);
      LinearLayout row = new LinearLayout(context);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setMinimumWidth(dp(44 * actions.size()));
      row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
      row.setPadding(dp(2), 0, dp(2), 0);
      for (String action : actions) {
        TextView button = new TextView(context);
        button.setText(TerminalExtraKeys.label(action));
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(48));
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setIncludeFontPadding(false);
        // 可见键帽在 48dp 触控格内各缩 2dp，文字内边距同步留出键帽圆角。
        button.setPadding(dp(4), 0, dp(4), dp(1));
        styleExtraKey(button, TerminalExtraKeys.style(action));
        button.setFocusable(false); // 触摸附加键不抢走 IME 组合焦点。
        button.setContentDescription(TerminalExtraKeys.description(action));
        if ("CTRL".equals(action)) ctrlBtn = button;
        if ("ALT".equals(action)) altBtn = button;
        button.setOnClickListener(v -> extraKey(action));
        // 单键也支持长按进入自定义；短按仍只发送按键，不改变输入语义。
        button.setOnLongClickListener(
            v -> {
              ExtraKeysDialog.show(requireContext(), this::refreshExtraKeys);
              return true;
            });
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1f));
      }
      scroll.addView(row, new HorizontalScrollView.LayoutParams(-1, dp(48)));
      box.addView(scroll, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    // 托盘底板与纯黑输出区分层；没有任何键时不留空条。
    if (anyRow) {
      box.setBackgroundResource(R.drawable.bg_terminal_key_tray);
      box.setPadding(0, dp(4), 0, dp(2));
    } else {
      box.setBackground(null);
      box.setPadding(0, 0, 0, 0);
    }
    box.setContentDescription(
        UiText.choose("终端快捷按键，长按可自定义", "Terminal extra keys, long-press to customize"));
    box.setOnLongClickListener(
        v -> {
          ExtraKeysDialog.show(requireContext(), this::refreshExtraKeys);
          return true;
        });
    paintModifiers();
  }

  /**
   * 按键分类决定键帽底色与字形：功能键深一档、方向键放大、控制字符等宽强调色、符号等宽。
   * 自动缩字上限随分类变化，大字体或窄屏时标签仍不折行、不撑破 48dp 行高。
   */
  private void styleExtraKey(TextView button, TerminalExtraKeys.KeyStyle style) {
    var context = button.getContext();
    int background = R.drawable.bg_terminal_key;
    int color = R.color.terminal_key_text;
    android.graphics.Typeface typeface =
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
    int maxSp = 15;
    switch (style) {
      case MODIFIER:
        background = R.drawable.bg_terminal_key_mod;
        typeface = android.graphics.Typeface.DEFAULT_BOLD;
        break;
      case FUNCTION:
        background = R.drawable.bg_terminal_key_fn;
        break;
      case ARROW:
        typeface = android.graphics.Typeface.DEFAULT;
        maxSp = 18;
        break;
      case CONTROL:
        color = R.color.terminal_key_control;
        typeface = android.graphics.Typeface.MONOSPACE;
        break;
      case SYMBOL:
      default:
        color = R.color.terminal_key_symbol;
        typeface = android.graphics.Typeface.MONOSPACE;
        maxSp = 17;
        break;
    }
    button.setBackgroundResource(background);
    button.setTextColor(context.getColor(color));
    button.setTypeface(typeface);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp);
    button.setAutoSizeTextTypeUniformWithConfiguration(8, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
  }

  @Override
  public void refreshExtraKeys() {
    if (extraKeysBox != null && isAdded()) buildExtraKeys(extraKeysBox);
  }

  private void extraKey(String action) {
    if (!inputAllowed()) return;
    if (TerminalExtraKeys.isModifier(action)) {
      if ("CTRL".equals(action)) modifiers.toggleControl();
      else modifiers.toggleAlt();
      paintModifiers();
      showKeyboard();
      return;
    }
    int code =
        switch (action) {
          case "ESC" -> KeyEvent.KEYCODE_ESCAPE;
          case "TAB" -> KeyEvent.KEYCODE_TAB;
          case "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT;
          case "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT;
          case "UP" -> KeyEvent.KEYCODE_DPAD_UP;
          case "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN;
          case "HOME" -> KeyEvent.KEYCODE_MOVE_HOME;
          case "END" -> KeyEvent.KEYCODE_MOVE_END;
          case "PGUP" -> KeyEvent.KEYCODE_PAGE_UP;
          case "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN;
          default -> 0;
        };
    restoreInputFocus();
    if (code != 0) {
      var emulator = attachedSession.session().getEmulator();
      if (emulator == null) return;
      String sequence =
          KeyHandler.getCode(
              code,
              keyModifiers(null),
              emulator.isCursorKeysApplicationMode(),
              emulator.isKeypadApplicationMode());
      if (sequence != null) {
        attachedSession.write(sequence);
        clearModifiers();
      }
    } else {
      String label = TerminalExtraKeys.label(action);
      boolean control = TerminalExtraKeys.isControl(action);
      int cp = control ? Character.toLowerCase(label.charAt(1)) : label.codePointAt(0);
      view.inputCodePoint(cp, control, false);
    }
    restoreInputFocus();
  }

  private int keyModifiers(KeyEvent e) {
    int result = 0;
    if (modifiers.control() || (e != null && e.isCtrlPressed())) result |= KeyHandler.KEYMOD_CTRL;
    if (modifiers.alt() || (e != null && e.isAltPressed())) result |= KeyHandler.KEYMOD_ALT;
    if (e != null && e.isShiftPressed()) result |= KeyHandler.KEYMOD_SHIFT;
    if (e != null && e.isNumLockOn()) result |= KeyHandler.KEYMOD_NUM_LOCK;
    return result;
  }

  private void clearModifiers() {
    modifiers.clear();
    paintModifiers();
  }

  private void paintModifiers() {
    if (!isAdded()) return;
    if (ctrlBtn != null) {
      ctrlBtn.setSelected(modifiers.control());
      ctrlBtn.setTextColor(
          requireContext()
              .getColor(
                  modifiers.control() ? R.color.terminal_key_led_on : R.color.terminal_key_text));
      ctrlBtn.setContentDescription(
          modifiers.control()
              ? UiText.choose("Ctrl，一次性修饰键，已启用", "Ctrl, one-shot modifier, enabled")
              : UiText.choose("Ctrl，一次性修饰键，未启用", "Ctrl, one-shot modifier, disabled"));
    }
    if (altBtn != null) {
      altBtn.setSelected(modifiers.alt());
      altBtn.setTextColor(
          requireContext()
              .getColor(modifiers.alt() ? R.color.terminal_key_led_on : R.color.terminal_key_text));
      altBtn.setContentDescription(
          modifiers.alt()
              ? UiText.choose("Alt，一次性修饰键，已启用", "Alt, one-shot modifier, enabled")
              : UiText.choose("Alt，一次性修饰键，未启用", "Alt, one-shot modifier, disabled"));
    }
  }

  private int fontSp() {
    return Math.max(
        FONT_MIN_SP,
        Math.min(FONT_MAX_SP, prefs(requireContext()).getInt(KEY_FONT_PX, FONT_DEF_SP)));
  }

  @Override
  public void changeFont(int deltaSp) {
    if (!isAdded()) return;
    int next = Math.max(FONT_MIN_SP, Math.min(FONT_MAX_SP, fontSp() + deltaSp));
    prefs(requireContext()).edit().putInt(KEY_FONT_PX, next).apply();
    applyFontSize(next);
  }

  private void applyFontSize(int sp) {
    if (view != null)
      view.setTextSize(
          (int)
              TypedValue.applyDimension(
                  TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
  }

  @Override
  public void showKeyboard() {
    if (!inputAllowed()) return;
    restoreInputFocus();
    InputMethodManager im =
        (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (im != null) im.showSoftInput(view, 0);
  }

  @Override
  public void restoreInputFocus() {
    if (view != null && inputAllowed()) view.requestFocus();
  }

  @Override
  public boolean selectionBack() {
    if (view == null || !view.isSelectingText()) return false;
    view.stopTextSelectionMode();
    return true;
  }

  @Override
  public boolean isPty() {
    return true;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void paste() {
    if (!inputAllowed()) return;
    try {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0) return;
      CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
      if (text != null) confirmPaste(text.toString(), UiText.choose("剪贴板", "Clipboard"));
    } catch (RuntimeException error) {
      Toast.makeText(
              requireContext(),
              UiText.choose("无法读取剪贴板", "Cannot read clipboard"),
              Toast.LENGTH_SHORT)
          .show();
    }
  }

  private void confirmPaste(String raw, String source) {
    if (!inputAllowed() || pasteDialog != null) return;
    TerminalInput.Paste prepared = TerminalInput.preparePaste(raw);
    if (!prepared.allowed()) {
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "没有可粘贴的文本，或超过 16K 字符", "Nothing to paste, or text exceeds 16K characters"),
              Toast.LENGTH_LONG)
          .show();
      return;
    }
    final var tab = OWNER.currentPty();
    final long id = tab.id, generation = viewGeneration;
    final String epoch = OWNER.ownerEpoch();
    final PtySession session = tab.value;
    clearModifiers();
    String warning =
        UiText.format(
            UiText.choose(
                "来源：%s\n不会附加回车；末尾换行与危险控制字符会移除。",
                "Source: %s\nNo Enter is appended; trailing newlines and unsafe control characters are removed."),
            source);
    if (prepared.multiline)
      warning =
          UiText.format(
              "%s\n%s",
              warning,
              UiText.choose(
                  "包含多行。若程序未启用括号粘贴，内部换行可能立即执行前面的命令。仅在信任内容时粘贴。",
                  "Contains multiple lines. Without bracketed paste, internal newlines may execute earlier commands immediately. Paste only trusted text."));
    pasteDialog =
        new DshaDialogBuilder(requireContext())
            .setTitle(UiText.choose("确认粘贴", "Confirm paste"))
            .setView(pasteContent(warning, SensitiveData.redact(prepared.text)))
            .setNegativeButton(UiText.text("取消"), null)
            .setPositiveButton(
                UiText.choose("粘贴文本", "Paste text"),
                (dialog, which) -> {
                  var current = OWNER.currentPty();
                  if (!TerminalInput.sameTarget(
                          epoch,
                          id,
                          generation,
                          OWNER.ownerEpoch(),
                          current == null ? 0 : current.id,
                          viewGeneration,
                          inputAllowed())
                      || attachedSession != session) return;
                  var emulator = session.session().getEmulator();
                  if (emulator != null) emulator.paste(prepared.text);
                  restoreInputFocus();
                })
            .create();
    pasteDialog.setOnDismissListener(
        dialog -> {
          pasteDialog = null;
          restoreInputFocus();
        });
    pasteDialog.show();
  }

  private View pasteContent(String warning, String text) {
    android.widget.ScrollView scroll =
        new android.widget.ScrollView(requireContext()) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = dp(Math.max(80, getResources().getConfiguration().screenHeightDp / 2));
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
          }
        };
    LinearLayout content = new LinearLayout(requireContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), dp(8), dp(24), dp(16));
    TextView explanation = new TextView(requireContext());
    explanation.setText(warning);
    explanation.setTextSize(13);
    explanation.setTextColor(requireContext().getColor(R.color.text_secondary));
    content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
    TextView heading = new TextView(requireContext());
    heading.setText(UiText.choose("粘贴预览", "Paste preview"));
    heading.setTextColor(requireContext().getColor(R.color.shell_text));
    heading.setTextSize(14);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setPadding(0, dp(16), 0, dp(8));
    content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    TextView preview = new TextView(requireContext());
    preview.setText(text);
    preview.setTextColor(requireContext().getColor(R.color.shell_text));
    preview.setTextSize(12);
    preview.setTypeface(android.graphics.Typeface.MONOSPACE);
    preview.setTextDirection(View.TEXT_DIRECTION_LTR);
    preview.setTextIsSelectable(true);
    content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    scroll.addView(content);
    return scroll;
  }

  private void showTerminalActions(TerminalView target) {
    var tab = OWNER.currentPty();
    if (!isResumed()
        || view != target
        || tab == null
        || attachedSession != tab.value
        || actionsDialog != null
        || !(getActivity() instanceof TerminalActivity host)) return;
    final long id = tab.id, generation = viewGeneration;
    final String epoch = OWNER.ownerEpoch();
    final PtySession session = tab.value;
    final String name = tab.customTitle().isEmpty() ? "PTY " + tab.number : tab.customTitle();
    java.util.function.BooleanSupplier current =
        () -> {
          var selected = OWNER.currentPty();
          return TerminalInput.sameTarget(
              epoch,
              id,
              generation,
              OWNER.ownerEpoch(),
              selected == null ? 0 : selected.id,
              viewGeneration,
              isResumed() && view == target && attachedSession == session);
        };
    clearModifiers();
    actionsDialog =
        TerminalActionsDialog.create(
            requireContext(),
            name,
            () -> {
              if (current.getAsBoolean()) paste();
            },
            () -> {
              if (!current.getAsBoolean() || target.mEmulator == null) return;
              TerminalOutputDialog.show(
                  getParentFragmentManager(),
                  TerminalOutputSnapshot.capture(name, target.mEmulator, target.getTopRow()));
            },
            () -> {
              if (current.getAsBoolean()) host.showDisplaySettings();
            },
            () -> {
              if (current.getAsBoolean())
                ExtraKeysDialog.show(requireContext(), this::refreshExtraKeys);
            },
            () -> {
              if (current.getAsBoolean()) host.openSessions();
            });
    actionsDialog.setOnDismissListener(dialog -> actionsDialog = null);
    CardSheet.show(actionsDialog, requireContext());
  }

  private void dismissActions() {
    if (actionsDialog == null) return;
    AlertDialog dialog = actionsDialog;
    actionsDialog = null;
    dialog.dismiss();
  }

  private void dismissPaste() {
    if (pasteDialog != null) {
      AlertDialog dialog = pasteDialog;
      pasteDialog = null;
      dialog.dismiss();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    OWNER.attachPty(tabsObserver);
    if (view != null && nativeClient != null && !nativeClient.active) {
      replaceNativeView();
      if (attachedSession != null && attachedSession.session() != null) {
        view.attachSession(attachedSession.session());
        restoreScroll();
      }
    }
    attachSelected();
    main.post(
        () -> {
          if (!isResumed()
              || view == null
              || creationPending
              || !startError.isEmpty()
              || !com.deepseekharness.app.ForegroundActivity.isResumed(getActivity())) return;
          if (startInitialGuestCommand()) return;
          if ((getArguments() == null || !getArguments().getBoolean("terminal_skip_initial_create"))
              && OWNER.claimPtyInitialCreation()) newSession();
        });
  }

  @Override
  public void onPause() {
    saveScroll();
    clearModifiers();
    dismissActions();
    dismissPaste();
    if (inputProblemDialog != null) inputProblemDialog.dismiss();
    if (nativeClient != null) nativeClient.active = false;
    if (creating != null && creating.cancel()) { // 已开始的创建票不取消真实进程。
      creationPending = false;
      creating = null;
      operationGeneration++;
    }
    OWNER.detachPty(tabsObserver);
    if (attachedSession != null) attachedSession.detachListener(sessionListener);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    saveScroll();
    OWNER.detachPty(tabsObserver);
    if (attachedSession != null) attachedSession.detachListener(sessionListener);
    dismissActions();
    dismissPaste();
    clearModifiers();
    viewGeneration++;
    attachedSession = null;
    sessionListener = null;
    redrawPending.set(false);
    if (nativeClient != null) nativeClient.active = false;
    nativeClient = null;
    if (inputFrame != null) {
      inputFrame.allowed = () -> false;
      inputFrame.clipboardPaste = null;
      inputFrame.lostWindowFocus = null;
    }
    inputFrame = null;
    view = null;
    title = null;
    ctrlBtn = null;
    altBtn = null;
    c = null;
    readiness = null;
    super.onDestroyView();
  }

  @Override
  public void onOutput() {
    if (!redrawPending.compareAndSet(false, true)) return;
    final TerminalView target = view;
    if (target == null) {
      redrawPending.set(false);
      return;
    }
    target.postOnAnimation(
        () -> {
          redrawPending.set(false);
          if (isAdded() && view == target && target.getWindowToken() != null) {
            int top = target.getTopRow();
            int shift = target.mEmulator == null ? 0 : target.mEmulator.getScrollCounter();
            boolean selecting = target.isSelectingText();
            target.onScreenUpdated();
            if (top < 0 && !selecting && target.mEmulator != null) {
              target.setTopRow(
                  Math.max(-target.mEmulator.getScreen().getActiveTranscriptRows(), top - shift));
              target.invalidate();
            }
          }
        });
  }

  @Override
  public void onTitle(String value) {
    main.post(this::notifyHost);
  }

  @Override
  public void onExit(int status) {
    final PtySession exited = attachedSession;
    main.post(
        () -> {
          if (attachedSession == exited && view != null) attachSelected();
        });
  }

  @Override
  public void onCopy(String text) {
    final PtySession source = attachedSession;
    main.post(
        () -> {
          if (!isAdded() || view == null || attachedSession != source || text == null) return;
          android.content.ClipboardManager cm =
              (android.content.ClipboardManager)
                  requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
          if (cm != null) {
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("term", SensitiveData.redact(text)));
            Toast.makeText(requireContext(), UiText.text("已复制"), Toast.LENGTH_SHORT).show();
          }
        });
  }

  @Override
  public void onPasteRequest() {
    final PtySession source = attachedSession;
    main.post(
        () -> {
          if (attachedSession == source) paste();
        });
  }

  @Override
  public void onBell() {
    final TerminalView target = view;
    main.post(
        () -> {
          if (!isAdded() || view != target || target == null) return;
          try {
            android.os.Vibrator vibrator =
                (android.os.Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.vibrate(30);
          } catch (RuntimeException ignored) {
          }
        });
  }

  @Override
  public void onInputProblem(int reason) {
    final PtySession source = attachedSession;
    main.post(
        () -> {
          if (!isAdded()
              || !isResumed()
              || source == null
              || attachedSession != source
              || inputProblemDialog != null) return;
          final int pending = source.inputProblem();
          if (pending == 0) return;
          boolean overflow = pending == TerminalSession.INPUT_QUEUE_FULL;
          String message =
              overflow
                  ? UiText.choose(
                      "终端输入队列已满，部分输入未发送。后续输入已暂停。先检查终端和命令状态；不会自动重试。此前排队的输入仍可能送达。",
                      "The terminal input queue filled and some input was not sent. Further input is paused. Check the terminal and command state; nothing will be retried automatically. Previously queued input may still arrive.")
                  : UiText.choose(
                      "终端输入未发送或可能未完整送达。请检查终端状态；不会自动重试。",
                      "Terminal input was not sent or may be incomplete. Check the terminal state; it will not be retried automatically.");
          DshaDialogBuilder builder = new DshaDialogBuilder(requireContext());
          builder
              .setTitle(UiText.choose("终端输入中断", "Terminal input interrupted"))
              .setMessage(message)
              .setNegativeButton(
                  UiText.choose("关闭提示", "Close notice"),
                  (dialog, which) -> {
                    // Overflow remains latched; another input attempt presents the choice again.
                    if (!overflow) source.acknowledgeInputProblem(pending);
                  });
          if (overflow)
            builder.setPositiveButton(
                UiText.choose("恢复输入", "Resume input"),
                (dialog, which) -> {
                  TerminalSession terminal = source.session();
                  if (attachedSession != source
                      || terminal == null
                      || !inputAllowed()
                      || !terminal.resumeInputAfterOverflow()) {
                    Toast.makeText(
                            requireContext(),
                            UiText.choose(
                                "输入仍在发送或终端已关闭",
                                "Earlier input is still being sent or the terminal is closed. Input remains paused."),
                            Toast.LENGTH_LONG)
                        .show();
                  } else source.acknowledgeInputProblem(pending);
                });
          inputProblemDialog = builder.create();
          inputProblemDialog.setOnDismissListener(
              dialog -> {
                inputProblemDialog = null;
                PtySession current = attachedSession;
                if (isResumed()
                    && current != null
                    && current.inputProblem() != 0
                    && (current != source || current.inputProblem() != pending))
                  main.post(() -> onInputProblem(current.inputProblem()));
              });
          inputProblemDialog.show();
        });
  }

  private boolean inputAllowed() {
    var tab = OWNER.currentPty();
    return isResumed()
        && view != null
        && tab != null
        && attachedSession == tab.value
        && tab.value.isRunning()
        && OWNER.isPtyInputAllowed(tab.id)
        && readiness != null
        && readiness.ready();
  }

  @Override
  public float onScale(float scale) {
    if (scale < 0.9f || scale > 1.1f) {
      changeFont(scale > 1f ? 1 : -1);
      return 1f;
    }
    return scale;
  }

  @Override
  public void onSingleTapUp(MotionEvent event) {
    showKeyboard();
  }

  @Override
  public boolean shouldBackButtonBeMappedToEscape() {
    return false;
  }

  @Override
  public boolean shouldEnforceCharBasedInput() {
    return true;
  }

  @Override
  public boolean shouldUseCtrlSpaceWorkaround() {
    return false;
  }

  @Override
  public boolean isTerminalViewSelected() {
    return inputAllowed() && view.hasFocus();
  }

  @Override
  public void copyModeChanged(boolean selecting) {
    if (selecting) clearModifiers();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
    if (!inputAllowed() || attachedSession.session() != session) return true;
    if (keyCode == KeyEvent.KEYCODE_PASTE
        || (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed() && event.isShiftPressed())) {
      if (event.getRepeatCount() == 0) paste();
      return true;
    }
    if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
      confirmPaste(event.getCharacters(), UiText.choose("输入法", "Input method"));
      return true;
    }
    // 特殊键不会回调 onCodePoint，必须在同次发送消费软修饰键。
    if (!event.isSystem() && !event.isFunctionPressed() && session.getEmulator() != null) {
      var emulator = session.getEmulator();
      String sequence =
          KeyHandler.getCode(
              keyCode,
              keyModifiers(event),
              emulator.isCursorKeysApplicationMode(),
              emulator.isKeypadApplicationMode());
      if (sequence != null) {
        session.write(sequence);
        clearModifiers();
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    return !inputAllowed();
  }

  @Override
  public boolean onLongPress(MotionEvent event) {
    return false;
  }

  @Override
  public boolean readControlKey() {
    return modifiers.control();
  }

  @Override
  public boolean readAltKey() {
    return modifiers.alt();
  }

  @Override
  public boolean readShiftKey() {
    return false;
  }

  @Override
  public boolean readFnKey() {
    return false;
  }

  @Override
  public boolean onCodePoint(int cp, boolean ctrl, TerminalSession session) {
    boolean allowed = inputAllowed() && attachedSession.session() == session;
    // TerminalView 已快照 Ctrl/Alt；不在 read* 时消费，避免硬键/IME重复读取丢失。
    clearModifiers();
    return !allowed;
  }

  @Override
  public void onEmulatorSet() {
    onOutput();
  }

  @Override
  public void logError(String tag, String message) {
    android.util.Log.e("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
  }

  @Override
  public void logWarn(String tag, String message) {
    android.util.Log.w("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
  }

  @Override
  public void logInfo(String tag, String message) {
    android.util.Log.i("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
  }

  @Override
  public void logDebug(String tag, String message) {
    android.util.Log.d("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
  }

  @Override
  public void logVerbose(String tag, String message) {}

  @Override
  public void logStackTraceWithMessage(String tag, String message, Exception e) {
    logWarn(tag, message + " " + e);
  }

  @Override
  public void logStackTrace(String tag, Exception e) {
    logWarn(tag, String.valueOf(e));
  }

  private void saveScroll() {
    if (view != null && attachedSession != null) SCROLL_ROWS.put(attachedSession, view.getTopRow());
  }

  private void restoreScroll() {
    if (view == null || attachedSession == null || view.mEmulator == null) return;
    int top = SCROLL_ROWS.getOrDefault(attachedSession, 0);
    view.setTopRow(
        Math.max(-view.mEmulator.getScreen().getActiveTranscriptRows(), Math.min(0, top)));
    view.invalidate();
  }

  private NativeClient nativeClient;

  private void configureNativeView(TerminalView target) {
    nativeClient = new NativeClient(target);
    target.setTerminalViewClient(nativeClient);
    final NativeClient client = nativeClient;
    target.setOnCreateContextMenuListener(
        (menu, source, info) -> {
          if (client.active && view == target) showTerminalActions(target);
        });
    target.setFocusable(true);
    target.setFocusableInTouchMode(true);
    target.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    target.setBackgroundColor(requireContext().getColor(R.color.terminal_surface));
    target.setOnFocusChangeListener(
        (v, focused) -> {
          if (!focused) clearModifiers();
        });
    inputFrame.terminal = target;
  }

  /** final TerminalView 不可包装 InputConnection；新选择建立新视图，旧IME回调留在旧门禁。 */
  private void replaceNativeView() {
    if (nativeClient != null) nativeClient.active = false;
    TerminalView previous = view;
    boolean focused = previous != null && previous.hasFocus();
    if (previous != null) inputFrame.removeView(previous);
    view = new TerminalView(requireContext(), null);
    view.setId(R.id.pty_view);
    configureNativeView(view);
    inputFrame.addView(view, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
    applyFontSize(fontSp());
    if (focused) view.requestFocus();
  }

  /** 每个原生视图独立绑定；失效视图永不把旧IME字符交给新会话。 */
  private final class NativeClient implements TerminalViewClient {
    final TerminalView target;
    boolean active = true;

    NativeClient(TerminalView target) {
      this.target = target;
    }

    private boolean current() {
      return active && view == target && inputAllowed();
    }

    public float onScale(float scale) {
      return active ? PtyTerminalFragment.this.onScale(scale) : 1f;
    }

    public void onSingleTapUp(MotionEvent e) {
      if (current()) showKeyboard();
    }

    public boolean shouldBackButtonBeMappedToEscape() {
      return false;
    }

    public boolean shouldEnforceCharBasedInput() {
      return true;
    }

    public boolean shouldUseCtrlSpaceWorkaround() {
      return false;
    }

    public boolean isTerminalViewSelected() {
      return current() && target.hasFocus();
    }

    public void copyModeChanged(boolean selecting) {
      if (active) PtyTerminalFragment.this.copyModeChanged(selecting);
    }

    public boolean onKeyDown(int code, KeyEvent e, TerminalSession session) {
      return !current() || PtyTerminalFragment.this.onKeyDown(code, e, session);
    }

    public boolean onKeyUp(int code, KeyEvent e) {
      return !current() || PtyTerminalFragment.this.onKeyUp(code, e);
    }

    public boolean onLongPress(MotionEvent e) {
      return false;
    }

    public boolean readControlKey() {
      return current() && modifiers.control();
    }

    public boolean readAltKey() {
      return current() && modifiers.alt();
    }

    public boolean readShiftKey() {
      return false;
    }

    public boolean readFnKey() {
      return false;
    }

    public boolean onCodePoint(int cp, boolean ctrl, TerminalSession session) {
      return !current() || PtyTerminalFragment.this.onCodePoint(cp, ctrl, session);
    }

    public void onEmulatorSet() {
      if (active) onOutput();
    }

    public void logError(String tag, String message) {
      PtyTerminalFragment.this.logError(tag, message);
    }

    public void logWarn(String tag, String message) {
      PtyTerminalFragment.this.logWarn(tag, message);
    }

    public void logInfo(String tag, String message) {
      PtyTerminalFragment.this.logInfo(tag, message);
    }

    public void logDebug(String tag, String message) {
      PtyTerminalFragment.this.logDebug(tag, message);
    }

    public void logVerbose(String tag, String message) {}

    public void logStackTraceWithMessage(String tag, String message, Exception error) {
      PtyTerminalFragment.this.logStackTraceWithMessage(tag, message, error);
    }

    public void logStackTrace(String tag, Exception error) {
      PtyTerminalFragment.this.logStackTrace(tag, error);
    }
  }

  /** 只拦终端库会直接写入的鼠标路径，不覆写原生字符、选字、滚动和IME实现。 */
  public static final class InputFrame extends android.widget.FrameLayout {
    java.util.function.BooleanSupplier allowed = () -> false;
    Runnable clipboardPaste, lostWindowFocus;
    TerminalView terminal;
    private boolean middleGesture;

    public InputFrame(Context context, AttributeSet attributes) {
      super(context, attributes);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
      super.onWindowFocusChanged(focused);
      if (!focused && lostWindowFocus != null) lostWindowFocus.run();
    }

    private boolean readOnlyCanReportInput() {
      return terminal != null
          && terminal.mEmulator != null
          && (terminal.mEmulator.isMouseTrackingActive()
              || terminal.mEmulator.isAlternateBufferActive());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
        middleGesture =
            event.isFromSource(InputDevice.SOURCE_MOUSE)
                && event.isButtonPressed(MotionEvent.BUTTON_TERTIARY);
      if (middleGesture) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
            && allowed.getAsBoolean()
            && clipboardPaste != null) clipboardPaste.run();
        if (event.getActionMasked() == MotionEvent.ACTION_UP
            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) middleGesture = false;
        return true;
      }
      return (!allowed.getAsBoolean() && readOnlyCanReportInput())
          || super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
      return (!allowed.getAsBoolean() && readOnlyCanReportInput())
          || super.dispatchGenericMotionEvent(event);
    }
  }
}
