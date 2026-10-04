package com.deepseekharness.app.core;

import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** Process-wide owner of both terminal tab sets and their verified closing lifecycle. */
public final class TerminalSessionOwner {
  private static final TerminalSessionOwner SHARED = new TerminalSessionOwner();
  private final TerminalTabs<PtySession> pty = new TerminalTabs<>();
  private final TerminalTabs<SimpleTab> simple = new TerminalTabs<>();
  private final Object closing = new Object();
  private volatile PtyObserver ptyObserver;
  private volatile SimpleObserver simpleObserver;

  TerminalSessionOwner() {}

  public static TerminalSessionOwner shared() {
    return SHARED;
  }

  public interface PtyObserver {
    void onTabsChanged();
  }

  public interface SimpleObserver {
    void onOutput(SimpleTab tab);

    void onState(SimpleTab tab);

    void onTabsChanged();
  }

  public void attachPty(PtyObserver observer) {
    ptyObserver = observer;
  }

  public void detachPty(PtyObserver observer) {
    if (ptyObserver == observer) ptyObserver = null;
  }

  public void attachSimple(SimpleObserver observer) {
    simpleObserver = observer;
  }

  public void detachSimple(SimpleObserver observer) {
    if (simpleObserver == observer) simpleObserver = null;
  }

  public TerminalTabs.ReadOnly<PtySession> ptyTabs() {
    return pty.readOnly();
  }

  public TerminalTabs.ReadOnly<SimpleTab> simpleTabs() {
    return simple.readOnly();
  }

  public TerminalTabs.Tab<PtySession> currentPty() {
    return pty.current();
  }

  public TerminalTabs.Tab<SimpleTab> currentSimple() {
    return simple.current();
  }

  public TerminalTabs.Tab<PtySession> addPty(PtySession session) {
    TerminalTabs.Tab<PtySession> tab = pty.add(session);
    notifyPty();
    return tab;
  }

  public TerminalTabs.Tab<SimpleTab> addSimple(TerminalSession.Backend backend) {
    SimpleTab value = new SimpleTab(backend);
    TerminalTabs.Tab<SimpleTab> tab = simple.add(value);
    notifySimpleTabs();
    return tab;
  }

  public boolean selectPty(long id) {
    boolean selected = pty.select(id);
    if (selected) notifyPty();
    return selected;
  }

  public boolean selectSimple(long id) {
    boolean selected = simple.select(id);
    if (selected) notifySimpleTabs();
    return selected;
  }

  public boolean beginClosePty(long id) {
    boolean accepted = pty.beginClose(id);
    if (accepted) notifyPty();
    return accepted;
  }

  public boolean beginCloseSimple(long id) {
    boolean accepted = simple.beginClose(id);
    if (accepted) notifySimpleTabs();
    return accepted;
  }

  public void closePty(long id, long timeoutMs) throws IOException, InterruptedException {
    synchronized (closing) {
      TerminalTabs.Tab<PtySession> tab = pty.find(id);
      if (tab == null) return;
      if (!tab.isClosing()) pty.beginClose(id);
      try {
        tab.value.finishAndWait(timeoutMs);
        pty.remove(id);
      } catch (IOException | InterruptedException | RuntimeException error) {
        pty.closeFailed(id);
        throw error;
      } finally {
        notifyPty();
      }
    }
  }

  public void closeSimple(long id, long timeoutMs) throws IOException, InterruptedException {
    closeSimple(id, timeoutMs, (tab, wait) -> tab.session.disposeAndWait(wait));
  }

  interface SimpleStop {
    boolean stop(SimpleTab tab, long timeoutMs) throws InterruptedException;
  }

  void closeSimple(long id, long timeoutMs, SimpleStop stop)
      throws IOException, InterruptedException {
    synchronized (closing) {
      TerminalTabs.Tab<SimpleTab> tab = simple.find(id);
      if (tab == null) return;
      if (!tab.isClosing()) simple.beginClose(id);
      try {
        if (!stop.stop(tab.value, timeoutMs)) throw new IOException(UiText.text("尚未确认终端退出"));
        simple.remove(id);
      } catch (IOException | InterruptedException | RuntimeException error) {
        simple.closeFailed(id);
        throw error;
      } finally {
        notifySimpleTabs();
      }
    }
  }

  public void closeAllAndConfirm(long timeoutMs) throws IOException, InterruptedException {
    synchronized (closing) {
      closeAllPtyAndConfirm(timeoutMs);
      closeAllSimpleAndConfirm(timeoutMs);
    }
  }

  public void closeAllPtyAndConfirm(long timeoutMs) throws IOException, InterruptedException {
    synchronized (closing) {
      // This low-level set also covers a PTY forked before its tab was published.
      PtySession.closeAllForMaintenance(timeoutMs);
      for (TerminalTabs.Tab<PtySession> tab : pty.snapshot()) closePty(tab.id, timeoutMs);
    }
  }

  public void closeAllSimpleAndConfirm(long timeoutMs) throws IOException, InterruptedException {
    synchronized (closing) {
      for (TerminalTabs.Tab<SimpleTab> tab : simple.snapshot()) closeSimple(tab.id, timeoutMs);
    }
  }

  public void requestPtyShutdown() {
    for (TerminalTabs.Tab<PtySession> tab : pty.snapshot()) {
      try {
        tab.value.finish();
      } catch (Throwable error) {
        android.util.Log.w(
            "DSHA",
            UiText.format("终端尚未停止，保留会话与环境保护：%s", SensitiveData.redact(String.valueOf(error))));
      }
    }
  }

  public void requestSimpleShutdown() {
    for (TerminalTabs.Tab<SimpleTab> tab : simple.snapshot()) tab.value.session.shutdown();
  }

  public final class SimpleTab {
    public final TerminalSession session;
    public final StringBuilder buffer = new StringBuilder();
    public String draft = "";
    public int cursorStart, cursorEnd;

    private SimpleTab(TerminalSession.Backend backend) {
      session =
          new TerminalSession(
              backend, text -> appendSimple(this, text), ignored -> notifySimpleState(this));
    }

    public synchronized void clearOutput() {
      buffer.setLength(0);
    }

    public synchronized String output() {
      return buffer.toString();
    }
  }

  public void appendSimple(SimpleTab tab, String text) {
    if (tab == null || text == null || text.isEmpty()) return;
    String safe = SensitiveData.redact(stripAnsi(text));
    synchronized (tab) {
      tab.buffer.append(safe);
      if (tab.buffer.length() > 300000) tab.buffer.delete(0, tab.buffer.length() - 100000);
    }
    SimpleObserver observer = simpleObserver;
    if (observer != null) observer.onOutput(tab);
  }

  private static String stripAnsi(String text) {
    return text.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "")
        .replaceAll("\\x1B\\][^\\x07]*\\x07", "")
        .replaceAll("\\x1B[()][0-9A-B]", "");
  }

  private void notifySimpleState(SimpleTab tab) {
    SimpleObserver observer = simpleObserver;
    if (observer != null) observer.onState(tab);
  }

  private void notifyPty() {
    PtyObserver observer = ptyObserver;
    if (observer != null) observer.onTabsChanged();
  }

  private void notifySimpleTabs() {
    SimpleObserver observer = simpleObserver;
    if (observer != null) observer.onTabsChanged();
  }
}
