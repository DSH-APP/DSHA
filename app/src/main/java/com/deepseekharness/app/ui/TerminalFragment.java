package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.TerminalRuntimeService;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.TerminalRuntimeProtection;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalInput;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** 简易终端只编辑/提交命令；中止是明确重启 shell，不冒充 PTY 的 Ctrl+C。 */
public class TerminalFragment extends Fragment implements TerminalPane {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final TerminalSessionOwner OWNER = TerminalSessionOwner.shared();
  private final TerminalSessionOwner.SimpleObserver observer =
      new TerminalSessionOwner.SimpleObserver() {
        @Override
        public void onOutput(TerminalSessionOwner.SimpleTab tab) {
          MAIN.post(
              () -> {
                if (getView() != null && active == tab && !outputScheduled) {
                  outputScheduled = true;
                  MAIN.postDelayed(renderOutput, 32);
                }
              });
        }

        @Override
        public void onState(TerminalSessionOwner.SimpleTab tab) {
          MAIN.post(
              () -> {
                if (getView() != null && active == tab) {
                  renderState(tab.session.state());
                  notifyHost();
                }
              });
        }

        @Override
        public void onTabsChanged() {
          MAIN.post(
              () -> {
                if (getView() != null) attachSelected();
              });
        }
      };
  private CommandInput inputEdit;
  private TextView outputText, cancelButton, statusText;
  private ScrollView scrollView;
  private TerminalSessionOwner.SimpleTab active;
  private TerminalRuntimeProtection.Ticket creating;
  private boolean outputScheduled, creationPending;
  private long viewGeneration, operationGeneration;
  private String startError = "";
  private AlertDialog pasteDialog, restartDialog;
  private ActionMode selectionMode;
  private final Runnable renderOutput = this::renderOutput;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    return inflater.inflate(R.layout.fragment_terminal, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View root, @Nullable Bundle state) {
    viewGeneration++;
    inputEdit = root.findViewById(R.id.term_input);
    outputText = root.findViewById(R.id.term_output);
    scrollView = root.findViewById(R.id.term_scroll);
    cancelButton = root.findViewById(R.id.term_ctrlc);
    statusText = root.findViewById(R.id.term_status);
    inputEdit.clipboardPaste = this::paste;
    TextView sendButton = root.findViewById(R.id.term_send);
    sendButton.setText(UiText.text("发送"));
    sendButton.setOnClickListener(v -> sendCommand());
    cancelButton.setOnClickListener(v -> confirmRestart());
    root.findViewById(R.id.term_clear)
        .setOnClickListener(
            v -> {
              if (active != null) active.clearOutput();
              renderOutput();
            });
    root.findViewById(R.id.term_pty)
        .setOnClickListener(
            v -> {
              if (getActivity() instanceof TerminalPane.Host)
                ((TerminalPane.Host) getActivity()).switchTerminalMode(true);
            });
    root.findViewById(R.id.terminal_new).setOnClickListener(v -> newSession());
    inputEdit.setOnEditorActionListener(
        (v, action, event) -> {
          if (event != null) {
            if (event.getKeyCode() != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
              sendCommand();
            return true;
          }
          if (action == EditorInfo.IME_ACTION_SEND
              || action == EditorInfo.IME_ACTION_GO
              || action == EditorInfo.IME_ACTION_DONE) {
            sendCommand();
            return true;
          }
          return false;
        });
    ActionMode.Callback selection =
        new ActionMode.Callback() {
          public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            selectionMode = mode;
            return true;
          }

          public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
          }

          public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == android.R.id.paste
                || item.getItemId() == android.R.id.pasteAsPlainText) {
              mode.finish();
              paste();
              return true;
            }
            return false;
          }

          public void onDestroyActionMode(ActionMode mode) {
            if (selectionMode == mode) selectionMode = null;
          }
        };
    inputEdit.setCustomSelectionActionModeCallback(selection);
    inputEdit.setCustomInsertionActionModeCallback(selection);
    outputText.setCustomSelectionActionModeCallback(selection);
    changeFont(0);
    attachSelected();
  }

  @Override
  public void newSession() {
    if (!isResumed() || inputEdit == null || creationPending || refuseDuringMaintenance()) return;
    if (getActivity() instanceof TerminalActivity
        && !((TerminalActivity) getActivity()).allowTerminalCreation()) return;
    saveDraft();
    creationPending = true;
    startError = "";
    final long generation = ++operationGeneration, targetView = viewGeneration;
    final var backend = terminalBackend(HarnessController.get(requireContext()).proot());
    try {
      creating =
          TerminalRuntimeService.requestFromVisible(
              requireActivity(),
              TerminalRuntimeProtection.Kind.SIMPLE,
              new TerminalRuntimeProtection.Admission() {
                @Override
                public void onReady(TerminalRuntimeProtection.Ticket ticket) {
                  if (!isResumed()
                      || inputEdit == null
                      || targetView != viewGeneration
                      || generation != operationGeneration) {
                    ticket.cancel();
                    return;
                  }
                  new Thread(
                          () -> {
                            String error = "";
                            try {
                              OWNER.createSimple(ticket, backend);
                            } catch (Exception problem) {
                              error = SensitiveData.redact(String.valueOf(problem));
                            }
                            final String result = error;
                            MAIN.post(
                                () -> {
                                  if (generation != operationGeneration) return;
                                  creationPending = false;
                                  creating = null;
                                  startError = result;
                                  if (inputEdit != null && viewGeneration == targetView)
                                    attachSelected();
                                });
                          },
                          "dsha-create-simple")
                      .start();
                }

                @Override
                public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {
                  if (generation != operationGeneration) return;
                  creationPending = false;
                  creating = null;
                  startError = SensitiveData.redact(reason);
                  if (inputEdit != null && viewGeneration == targetView) attachSelected();
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
    saveDraft();
    boolean selected = OWNER.selectSimple(id);
    if (selected) attachSelected();
    return selected;
  }

  @Override
  public void closeSession(long id) {
    final long targetView = viewGeneration;
    OWNER.closeSimpleAsync(
        id,
        5000,
        (epoch, kind, closed, error) ->
            MAIN.post(
                () -> {
                  if (!OWNER.ownerEpoch().equals(epoch)
                      || inputEdit == null
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

  private void saveDraft() {
    if (active != null && inputEdit != null) {
      active.draft = inputEdit.getText().toString();
      active.cursorStart = inputEdit.getSelectionStart();
      active.cursorEnd = inputEdit.getSelectionEnd();
    }
  }

  private void attachSelected() {
    if (getView() == null || inputEdit == null) return;
    saveDraft();
    var tab = OWNER.currentSimple();
    TerminalSessionOwner.SimpleTab selected = tab == null ? null : tab.value;
    boolean changed = active != selected;
    if (changed) {
      dismissDialogs();
      selectionBack();
    }
    active = selected;
    if (changed) {
      inputEdit.setText(active == null ? "" : active.draft);
      if (active != null)
        inputEdit.setSelection(
            Math.max(0, Math.min(active.cursorStart, active.draft.length())),
            Math.max(0, Math.min(active.cursorEnd, active.draft.length())));
    }
    renderState(active == null ? TerminalSession.State.STOPPED : active.session.state());
    renderOutput();
    TerminalTabBar.render(
        getView(),
        OWNER.simpleTabs(),
        new TerminalTabBar.Actions() {
          public void select(long id) {
            selectSession(id);
          }

          public void close(long id) {
            if (getActivity() instanceof TerminalPane.Host)
              ((TerminalPane.Host) getActivity()).requestCloseSession(false, id);
          }
        });
    notifyHost();
  }

  private void notifyHost() {
    if (getActivity() instanceof TerminalPane.Host)
      ((TerminalPane.Host) getActivity()).onTerminalPaneChanged();
  }

  /** Debug fixtures keep the production backend without owning user tabs. */
  static TerminalSession.Backend terminalBackend(ProotBootstrap proot) {
    return com.deepseekharness.app.core.SimpleTerminalBackend.create(proot);
  }

  static String terminalBlockMessage(ProotBootstrap proot) {
    return com.deepseekharness.app.core.SimpleTerminalBackend.blockMessage(proot);
  }

  private boolean refuseDuringMaintenance() {
    if (!isAdded()) return true;
    String blocked = terminalBlockMessage(HarnessController.get(requireContext()).proot());
    if (blocked.isEmpty()) return false;
    startError = blocked;
    Toast.makeText(requireContext(), blocked, Toast.LENGTH_LONG).show();
    if (inputEdit != null) inputEdit.setHint(blocked);
    return true;
  }

  private boolean inputAllowed() {
    var selected = OWNER.currentSimple();
    return isResumed()
        && inputEdit != null
        && active != null
        && selected != null
        && active == selected.value
        && OWNER.isSimpleInputAllowed(selected.id);
  }

  private void sendCommand() {
    if (!inputAllowed()) return;
    String command = inputEdit.getText().toString();
    if (command.trim().isEmpty() || refuseDuringMaintenance()) return;
    if (active.session.submit(command)) {
      inputEdit.setText("");
      saveDraft();
    } else Toast.makeText(requireContext(), UiText.text("未发送：命令过长或含空字符"), Toast.LENGTH_LONG).show();
  }

  private void confirmRestart() {
    if (!inputAllowed() || refuseDuringMaintenance() || restartDialog != null) return;
    final var target = OWNER.currentSimple();
    final String epoch = OWNER.ownerEpoch();
    final long generation = viewGeneration;
    restartDialog =
        new DshaDialogBuilder(requireContext())
            .setTitle(UiText.text("中止并重启"))
            .setMessage(
                UiText.choose(
                    "这会结束当前简易 shell 并启动新 shell，工作目录和变量会丢失；不是向当前命令发送 Ctrl+C。",
                    "This stops the simple shell and starts a new one. Working directory and variables are lost; this is not Ctrl+C to the current command."))
            .setNegativeButton(UiText.text("取消"), null)
            .setPositiveButton(
                UiText.text("中止并重启"),
                (dialog, which) -> {
                  var current = OWNER.currentSimple();
                  if (TerminalInput.sameTarget(
                          epoch,
                          target.id,
                          generation,
                          OWNER.ownerEpoch(),
                          current == null ? 0 : current.id,
                          viewGeneration,
                          inputAllowed())
                      && !refuseDuringMaintenance()) target.value.session.cancelAndRestart();
                })
            .create();
    restartDialog.setOnDismissListener(
        dialog -> {
          restartDialog = null;
          restoreInputFocus();
        });
    restartDialog.show();
  }

  private void renderState(TerminalSession.State state) {
    if (inputEdit == null || getView() == null) return;
    getView().findViewById(R.id.terminal_new).setEnabled(!creationPending);
    boolean allowed = inputAllowed();
    inputEdit.setEnabled(allowed);
    getView().findViewById(R.id.term_send).setEnabled(allowed);
    cancelButton.setEnabled(allowed && state != TerminalSession.State.STOPPING);
    cancelButton.setText(
        state == TerminalSession.State.STOPPING ? UiText.text("中止中…") : UiText.text("中止并重启"));
    String status = "";
    var selected = OWNER.currentSimple();
    if (!startError.isEmpty()) status = startError;
    else if (creationPending) status = UiText.choose("正在准备终端保护…", "Preparing terminal protection…");
    else if (selected != null && selected.isClosing()) status = UiText.text("正在关闭此终端…");
    else if (selected != null && selected.isUnconfirmed())
      status =
          UiText.format(
              UiText.choose("关闭未确认，输入已停用：%s", "Close unconfirmed; input disabled: %s"),
              SensitiveData.redact(selected.error()));
    else if (active != null && selected != null && OWNER.isSimpleProtectionLost(selected.id))
      status =
          UiText.choose(
              "终端后台保护已丢失，输入已停用；请从菜单关闭并核验会话。",
              "Terminal background protection was lost; input is disabled. Close and verify the session from the menu.");
    else if (active != null && state == TerminalSession.State.FAILED)
      status = UiText.text("会话失败，待发命令保留；点重试重启");
    statusText.setText(status);
    statusText.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
    if (active == null) inputEdit.setHint(UiText.text("点击「新建」打开终端"));
    else if (!allowed) inputEdit.setHint(status);
    else
      switch (state) {
        case STARTING:
          inputEdit.setHint(UiText.text("会话启动中，输入命令可排队"));
          break;
        case BUSY:
          inputEdit.setHint(UiText.text("命令执行中，新命令将排队"));
          break;
        case STOPPING:
          inputEdit.setHint(UiText.text("中止并重启中，新命令将排队"));
          break;
        case STOPPED:
          inputEdit.setHint(UiText.text("会话已退出，输入命令自动启动"));
          break;
        case FAILED:
          inputEdit.setHint(UiText.text("会话失败，待发命令保留；点重试重启"));
          break;
        default:
          inputEdit.setHint(UiText.text("输入命令，回车执行"));
      }
  }

  private void renderOutput() {
    outputScheduled = false;
    if (outputText == null || scrollView == null) return;
    if (active == null) {
      outputText.setText(UiText.text("暂无终端\n点击「新建」打开一个终端"));
      return;
    }
    // 用户回看历史时不抢回底部；本来就在末尾才随输出滚动。
    boolean atBottom = !scrollView.canScrollVertically(1);
    String buffer = active.output();
    String show =
        buffer.length() > 100000
            ? UiText.format("…（输出过长已截断）\n%s", buffer.substring(buffer.length() - 100000))
            : buffer;
    outputText.setText(SensitiveData.redact(show));
    ScrollView target = scrollView;
    if (atBottom)
      target.post(
          () -> {
            if (scrollView == target) target.fullScroll(View.FOCUS_DOWN);
          });
  }

  @Override
  public void changeFont(int deltaSp) {
    if (outputText == null) return;
    var prefs = requireContext().getSharedPreferences("deepseekharness", 0);
    int size = Math.max(8, Math.min(24, prefs.getInt("term_font_px", 13) + deltaSp));
    if (deltaSp != 0) prefs.edit().putInt("term_font_px", size).apply();
    outputText.setTextSize(size);
    inputEdit.setTextSize(size);
  }

  @Override
  public void showKeyboard() {
    if (!inputAllowed()) return;
    restoreInputFocus();
    InputMethodManager im =
        (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (im != null) im.showSoftInput(inputEdit, 0);
  }

  @Override
  public void restoreInputFocus() {
    if (inputAllowed()) inputEdit.requestFocus();
  }

  @Override
  public boolean selectionBack() {
    if (selectionMode == null) return false;
    selectionMode.finish();
    selectionMode = null;
    return true;
  }

  @Override
  public boolean isPty() {
    return false;
  }

  @Override
  public void paste() {
    if (!inputAllowed() || pasteDialog != null) return;
    try {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0) return;
      CharSequence content = clip.getItemAt(0).coerceToText(requireContext());
      TerminalInput.Paste prepared =
          TerminalInput.preparePaste(content == null ? "" : content.toString());
      if (!prepared.allowed()) {
        Toast.makeText(
                requireContext(),
                UiText.choose(
                    "没有可粘贴的文本，或超过 16K 字符", "Nothing to paste, or text exceeds 16K characters"),
                Toast.LENGTH_LONG)
            .show();
        return;
      }
      final long generation = viewGeneration, id = OWNER.currentSimple().id;
      final String epoch = OWNER.ownerEpoch();
      pasteDialog =
          new DshaDialogBuilder(requireContext())
              .setTitle(UiText.choose("确认粘贴", "Confirm paste"))
              .setMessage(
                  UiText.format(
                      "%s\n\n%s",
                      UiText.choose(
                          "来源：剪贴板。仅填入命令草稿，不会执行；末尾换行与危险控制字符会移除。",
                          "Source: Clipboard. Inserts into the command draft only; does not execute. Trailing newlines and unsafe control characters are removed."),
                      SensitiveData.redact(prepared.text)))
              .setNegativeButton(UiText.text("取消"), null)
              .setPositiveButton(
                  UiText.choose("粘贴文本", "Paste text"),
                  (dialog, which) -> {
                    var current = OWNER.currentSimple();
                    if (!TerminalInput.sameTarget(
                        epoch,
                        id,
                        generation,
                        OWNER.ownerEpoch(),
                        current == null ? 0 : current.id,
                        viewGeneration,
                        inputAllowed())) return;
                    int start = Math.max(0, inputEdit.getSelectionStart()),
                        end = Math.max(0, inputEdit.getSelectionEnd());
                    inputEdit
                        .getText()
                        .replace(Math.min(start, end), Math.max(start, end), prepared.text);
                    saveDraft();
                  })
              .create();
      pasteDialog.setOnDismissListener(
          dialog -> {
            pasteDialog = null;
            restoreInputFocus();
          });
      pasteDialog.show();
    } catch (RuntimeException error) {
      Toast.makeText(
              requireContext(),
              UiText.choose("无法读取剪贴板", "Cannot read clipboard"),
              Toast.LENGTH_SHORT)
          .show();
    }
  }

  private void dismissDialogs() {
    if (pasteDialog != null) {
      AlertDialog d = pasteDialog;
      pasteDialog = null;
      d.dismiss();
    }
    if (restartDialog != null) {
      AlertDialog d = restartDialog;
      restartDialog = null;
      d.dismiss();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    OWNER.attachSimple(observer);
    attachSelected();
    MAIN.post(
        () -> {
          if (isResumed()
              && inputEdit != null
              && !creationPending
              && startError.isEmpty()
              && com.deepseekharness.app.ForegroundActivity.isResumed(getActivity())
              && (getArguments() == null
                  || !getArguments().getBoolean("terminal_skip_initial_create"))
              && OWNER.claimSimpleInitialCreation()) newSession();
        });
  }

  @Override
  public void onPause() {
    saveDraft();
    dismissDialogs();
    if (creating != null && creating.cancel()) {
      creationPending = false;
      creating = null;
      operationGeneration++;
    }
    OWNER.detachSimple(observer);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    saveDraft();
    dismissDialogs();
    selectionBack();
    viewGeneration++;
    active = null;
    OWNER.detachSimple(observer);
    MAIN.removeCallbacks(renderOutput);
    outputScheduled = false;
    if (inputEdit != null) inputEdit.clipboardPaste = null;
    inputEdit = null;
    outputText = null;
    statusText = null;
    cancelButton = null;
    scrollView = null;
    super.onDestroyView();
  }

  public static void shutdownShell() {
    OWNER.requestSimpleShutdown();
  }

  public static void shutdownShellAndWait(long timeoutMs) throws IOException, InterruptedException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IOException(UiText.text("请在维护任务线程等待终端退出"));
    OWNER.closeAllSimpleAndConfirm(timeoutMs);
  }

  public static void inject(String text) {
    if (text != null && !text.isEmpty())
      MAIN.post(
          () -> {
            var tab = OWNER.currentSimple();
            if (tab != null)
              OWNER.appendSimple(tab.value, text + (text.endsWith("\n") ? "" : "\n"));
          });
  }

  /** 系统工具栏和硬键粘贴也走同一确认入口；普通 IME 编辑仍由 EditText 处理。 */
  public static final class CommandInput extends AppCompatEditText {
    Runnable clipboardPaste;

    public CommandInput(Context context, AttributeSet attributes) {
      super(context, attributes);
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo attributes) {
      android.view.inputmethod.InputConnection connection =
          super.onCreateInputConnection(attributes);
      // 多行草稿仍显示发送键；不把输入法粘贴中的换行当作执行。
      attributes.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
      attributes.imeOptions =
          (attributes.imeOptions & ~EditorInfo.IME_MASK_ACTION) | EditorInfo.IME_ACTION_SEND;
      return connection;
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
      if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
        if (clipboardPaste != null) clipboardPaste.run();
        return true;
      }
      return super.onTextContextMenuItem(id);
    }
  }
}
