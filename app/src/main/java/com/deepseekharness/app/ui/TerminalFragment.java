package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalSession;
import java.io.IOException;

/** 简易终端页面；持久 shell 的发送、退出和取消由无视图引用的 TerminalSession 负责。 */
public class TerminalFragment extends Fragment {
  private static final String HEADER = "Ubuntu 24.04 · 回车执行 · 中止会重启 shell · 交互输入请用 PTY\n";
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
                if (getView() != null && active == tab) renderState(tab.session.state());
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
  private EditText inputEdit;
  private TextView outputText, cancelButton;
  private ScrollView scrollView;
  private TerminalSessionOwner.SimpleTab active;
  private boolean outputScheduled;
  private final Runnable renderOutput = this::renderOutput;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    return inflater.inflate(R.layout.fragment_terminal, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
    inputEdit = view.findViewById(R.id.term_input);
    outputText = view.findViewById(R.id.term_output);
    scrollView = view.findViewById(R.id.term_scroll);
    cancelButton = view.findViewById(R.id.term_ctrlc);
    if (isResumed()) OWNER.attachSimple(observer);
    cancelButton.setOnClickListener(
        v -> {
          if (active != null && !refuseDuringMaintenance()) active.session.cancelAndRestart();
        });
    view.findViewById(R.id.term_clear)
        .setOnClickListener(
            v -> {
              if (active != null) active.clearOutput();
              renderOutput();
            });
    View pty = view.findViewById(R.id.term_pty);
    if (pty != null) pty.setOnClickListener(v -> switchToPty());
    view.findViewById(R.id.terminal_new).setOnClickListener(v -> newTerminal());
    inputEdit.setOnEditorActionListener(
        (v, action, event) -> {
          if (event != null) {
            if (event.getKeyCode() != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
              sendCommand();
            return true; // 消费抬起/长按重复，不能把一次实体回车发送两遍。
          }
          if (action == EditorInfo.IME_ACTION_SEND
              || action == EditorInfo.IME_ACTION_GO
              || action == EditorInfo.IME_ACTION_DONE) {
            sendCommand();
            return true;
          }
          return false;
        });
    if (!OWNER.simpleTabs().wasInitialized()) newTerminal();
    else attachSelected();
  }

  private void newTerminal() {
    if (refuseDuringMaintenance()) return;
    saveDraft();
    var tab = OWNER.addSimple(terminalBackend(HarnessController.get(requireContext()).proot()));
    attachSelected();
    requireView().findViewById(R.id.terminal_new).setEnabled(false);
    tab.value.session.ensureStarted();
  }

  private void saveDraft() {
    if (active != null && inputEdit != null) {
      active.draft = inputEdit.getText().toString();
      active.cursorStart = inputEdit.getSelectionStart();
      active.cursorEnd = inputEdit.getSelectionEnd();
    }
  }

  private void attachSelected() {
    if (getView() == null) return;
    saveDraft();
    var tab = OWNER.currentSimple();
    TerminalSessionOwner.SimpleTab selected = tab == null ? null : tab.value;
    boolean changed = active != selected;
    active = selected;
    if (changed) {
      inputEdit.setText(active == null ? "" : active.draft);
      if (active != null)
        inputEdit.setSelection(
            Math.max(0, Math.min(active.cursorStart, active.draft.length())),
            Math.max(0, Math.min(active.cursorEnd, active.draft.length())));
    }
    inputEdit.setEnabled(tab != null && !tab.isClosing());
    cancelButton.setEnabled(tab != null && !tab.isClosing());
    if (active != null) renderState(active.session.state());
    else {
      inputEdit.setHint(com.deepseekharness.app.util.UiText.text("点击「新建」打开终端"));
      getView().findViewById(R.id.terminal_new).setEnabled(true);
    }
    renderOutput();
    renderTabs();
  }

  private void renderTabs() {
    if (getView() == null) return;
    TerminalTabBar.render(
        getView(),
        OWNER.simpleTabs(),
        new TerminalTabBar.Actions() {
          public void select(long id) {
            saveDraft();
            if (OWNER.selectSimple(id)) attachSelected();
          }

          public void close(long id) {
            closeTerminal(id);
          }
        });
  }

  private void closeTerminal(long id) {
    var tab = OWNER.simpleTabs().find(id);
    if (tab == null || !OWNER.beginCloseSimple(id)) return;
    attachSelected();
    android.content.Context app = requireContext().getApplicationContext();
    new Thread(
            () -> {
              String failure = null;
              try {
                OWNER.closeSimple(id, 5000);
              } catch (Exception error) {
                failure = SensitiveData.redact(String.valueOf(error.getMessage()));
              }
              final String problem = failure;
              MAIN.post(
                  () -> {
                    if (problem != null)
                      Toast.makeText(
                              app,
                              com.deepseekharness.app.util.UiText.format("关闭失败，会话仍保留：%s", problem),
                              Toast.LENGTH_LONG)
                          .show();
                    if (getView() != null) attachSelected();
                  });
            },
            "dsha-close-simple-terminal")
        .start();
  }

  /** Debug fixtures keep the production backend without owning user tabs. */
  static TerminalSession.Backend terminalBackend(ProotBootstrap proot) {
    return com.deepseekharness.app.core.SimpleTerminalBackend.create(proot);
  }

  static String terminalBlockMessage(ProotBootstrap proot) {
    return com.deepseekharness.app.core.SimpleTerminalBackend.blockMessage(proot);
  }

  private boolean refuseDuringMaintenance() {
    String blocked = terminalBlockMessage(HarnessController.get(requireContext()).proot());
    if (blocked.isEmpty()) return false;
    Toast.makeText(requireContext(), blocked, Toast.LENGTH_LONG).show();
    if (inputEdit != null) inputEdit.setHint(blocked);
    return true;
  }

  private void sendCommand() {
    if (inputEdit == null) return;
    String command = inputEdit.getText().toString();
    if (command.trim().isEmpty()) return;
    if (refuseDuringMaintenance()) return;
    var selected = OWNER.currentSimple();
    if (active != null
        && selected != null
        && !selected.isClosing()
        && active.session.submit(command)) inputEdit.setText("");
    else
      Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.text("命令未发送：请检查长度（最多 16K 字符）及空字符，输入已保留"),
              Toast.LENGTH_LONG)
          .show();
  }

  private void renderState(TerminalSession.State state) {
    if (inputEdit == null || cancelButton == null) return;
    if (getView() != null)
      getView().findViewById(R.id.terminal_new).setEnabled(state != TerminalSession.State.STARTING);
    cancelButton.setText(
        state == TerminalSession.State.STOPPING
            ? com.deepseekharness.app.util.UiText.text("中止中…")
            : state == TerminalSession.State.FAILED
                ? com.deepseekharness.app.util.UiText.text("重试重启")
                : com.deepseekharness.app.util.UiText.text("中止并重启"));
    cancelButton.setEnabled(state != TerminalSession.State.STOPPING);
    var selected = OWNER.currentSimple();
    if (selected != null && selected.isClosing()) {
      inputEdit.setEnabled(false);
      cancelButton.setEnabled(false);
      inputEdit.setHint(com.deepseekharness.app.util.UiText.text("正在关闭此终端…"));
      return;
    }
    switch (state) {
      case STARTING:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话启动中，输入命令可排队"));
        break;
      case BUSY:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("命令执行中，新命令将排队"));
        break;
      case STOPPING:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("中止并重启中，新命令将排队"));
        break;
      case STOPPED:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话已退出，输入命令自动启动"));
        break;
      case FAILED:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话失败，待发命令保留；点重试重启"));
        break;
      default:
        inputEdit.setHint(com.deepseekharness.app.util.UiText.text("输入命令，回车执行"));
    }
  }

  private void switchToPty() {
    if (refuseDuringMaintenance()) return;
    requireContext()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("term_pty", true)
        .apply();
    try {
      int containerId = ((ViewGroup) requireView().getParent()).getId();
      UiMotion.page(requireContext(), getParentFragmentManager().beginTransaction())
          .replace(containerId, new PtyTerminalFragment())
          .commit();
    } catch (RuntimeException error) {
      Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.text("请退出终端页再进来"),
              Toast.LENGTH_SHORT)
          .show();
    }
  }

  private void renderOutput() {
    outputScheduled = false;
    if (outputText == null || scrollView == null) return;
    if (active == null) {
      outputText.setText(com.deepseekharness.app.util.UiText.text("暂无终端\n点击「新建」打开一个终端"));
      return;
    }
    String buffer = active.output();
    String show =
        buffer.length() > 100000
            ? com.deepseekharness.app.util.UiText.format(
                "…（输出过长已截断）\n%s", buffer.substring(buffer.length() - 100000))
            : buffer.length() == 0 ? HEADER : buffer;
    outputText.setText(SensitiveData.redact(show));
    ScrollView scroll = scrollView;
    scroll.post(
        () -> {
          if (scrollView == scroll) scroll.fullScroll(View.FOCUS_DOWN);
        });
  }

  @Override
  public void onResume() {
    super.onResume();
    OWNER.attachSimple(observer);
    if (inputEdit != null) attachSelected();
  }

  @Override
  public void onPause() {
    saveDraft();
    OWNER.detachSimple(observer);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    saveDraft();
    active = null;
    OWNER.detachSimple(observer);
    MAIN.removeCallbacks(renderOutput);
    outputScheduled = false;
    inputEdit = null;
    outputText = null;
    cancelButton = null;
    scrollView = null;
    super.onDestroyView();
  }

  public static void shutdownShell() {
    OWNER.requestSimpleShutdown();
  }

  /** 数据维护已取得全局 Lease 后，在替换目录前调用；不能从主线程等待。 */
  public static void shutdownShellAndWait(long timeoutMs) throws IOException, InterruptedException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IOException(com.deepseekharness.app.util.UiText.text("请在维护任务线程等待终端退出"));
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
}
