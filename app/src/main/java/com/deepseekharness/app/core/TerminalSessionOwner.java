package com.deepseekharness.app.core;

import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.deepseekharness.app.runtime.ProotBootstrap;

/** Process-wide owner of both terminal tab sets and their verified closing lifecycle. */
public final class TerminalSessionOwner {
  private static final TerminalSessionOwner SHARED =
      new TerminalSessionOwner(TerminalRuntimeProtection.shared());
  private final String ownerEpoch = UUID.randomUUID().toString();
  private final TerminalRuntimeProtection protection;
  private final Map<Long, TerminalRuntimeProtection.Ticket> ptyProtection =
      new ConcurrentHashMap<>();
  private final Map<Long, TerminalRuntimeProtection.Ticket> simpleProtection =
      new ConcurrentHashMap<>();
  private final ExecutorService closeWorker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "dsha-terminal-close");
            thread.setDaemon(true);
            return thread;
          });
  private final TerminalTabs<PtySession> pty = new TerminalTabs<>();
  private final TerminalTabs<SimpleTab> simple = new TerminalTabs<>();
  private final Object closing = new Object();
  private final CopyOnWriteArrayList<Runnable> simpleStateObservers = new CopyOnWriteArrayList<>();
  private volatile PtyObserver ptyObserver;
  private volatile SimpleObserver simpleObserver;
  private boolean ptyInitialClaimed, simpleInitialClaimed;

  TerminalSessionOwner() {
    this(null);
  }

  TerminalSessionOwner(TerminalRuntimeProtection protection) {
    this.protection = protection;
    if (protection != null)
      protection.addObserver(
          () -> {
            notifyPty();
            notifySimpleTabs();
          });
  }

  public TerminalRuntimeProtection protection() {
    return protection;
  }

  public String ownerEpoch() {
    return ownerEpoch;
  }

  public String epoch() {
    return ownerEpoch;
  }

  public TerminalRuntimeProtection.Snapshot protectionSnapshot() {
    return protection == null
        ? new TerminalRuntimeProtection.Snapshot(
            TerminalRuntimeProtection.State.RELEASED, 0, 0, 0, "")
        : protection.snapshot();
  }

  public static TerminalSessionOwner shared() {
    return SHARED;
  }

  /** 默认首建意图只认领一次；取消/失败后须显式新建，不在旋转或回页时自动重放。 */
  public synchronized boolean claimPtyInitialCreation() {
    if (ptyInitialClaimed || pty.wasInitialized()) return false;
    ptyInitialClaimed = true;
    return true;
  }

  public synchronized boolean claimSimpleInitialCreation() {
    if (simpleInitialClaimed || simple.wasInitialized()) return false;
    simpleInitialClaimed = true;
    return true;
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

  /** The host's session list also needs updates while the simple pane is detached. */
  public void addSimpleStateObserver(Runnable observer) {
    if (observer != null) simpleStateObservers.addIfAbsent(observer);
  }

  public void removeSimpleStateObserver(Runnable observer) {
    simpleStateObservers.remove(observer);
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

  /** 仅保留给同包纯策略测试；生产 shared owner 必须经精确创建票。 */
  TerminalTabs.Tab<PtySession> addPty(PtySession session) {
    if (protection != null) throw new IllegalStateException("TERMINAL_CREATE_TICKET_REQUIRED");
    TerminalTabs.Tab<PtySession> tab = pty.add(session);
    notifyPty();
    return tab;
  }

  TerminalTabs.Tab<SimpleTab> addSimple(TerminalSession.Backend backend) {
    if (protection != null) throw new IllegalStateException("TERMINAL_CREATE_TICKET_REQUIRED");
    SimpleTab value = new SimpleTab(backend);
    TerminalTabs.Tab<SimpleTab> tab = simple.add(value);
    notifySimpleTabs();
    return tab;
  }

  /** 在工作线程调用；prepare 覆盖 fork 到发布之间，失败 pending 由 PtySession 继续持有。 */
  public TerminalTabs.Tab<PtySession> createPty(
      TerminalRuntimeProtection.Ticket ticket,
      ProotBootstrap proot,
      int cols,
      int rows,
      PtySession.Listener listener)
      throws IOException {
    return createPty(ticket, proot, cols, rows, listener, null);
  }

  /** 固定 guest command 只影响 PTY argv；创建票、发布与失败保留时序完全复用默认路径。 */
  public TerminalTabs.Tab<PtySession> createPty(
      TerminalRuntimeProtection.Ticket ticket,
      ProotBootstrap proot,
      int cols,
      int rows,
      PtySession.Listener listener,
      String[] guestCommand)
      throws IOException {
    return createPty(
        ticket,
        guestCommand,
        (verified, changed, beforeFork, allocated, command) ->
            PtySession.start(
                proot, cols, rows, listener, verified, changed, beforeFork, allocated, command));
  }

  interface PtyStart {
    PtySession start(
        Runnable verified,
        Runnable changed,
        Runnable beforeFork,
        java.util.function.Consumer<PtySession> allocated,
        String[] guestCommand);
  }

  TerminalTabs.Tab<PtySession> createPty(TerminalRuntimeProtection.Ticket ticket, PtyStart start)
      throws IOException {
    return createPty(ticket, null, start);
  }

  TerminalTabs.Tab<PtySession> createPty(
      TerminalRuntimeProtection.Ticket ticket, String[] guestCommand, PtyStart start)
      throws IOException {
    requireProtection();
    protection.beginCreate(ticket, ownerEpoch, TerminalRuntimeProtection.Kind.PTY);
    PtySession[] allocated = new PtySession[1];
    try {
      protection.retain(ticket);
      PtySession session =
          start.start(
              () -> protection.releaseVerified(ticket),
              this::notifyPty,
              () -> {
                try {
                  protection.requireHeld(ticket);
                } catch (IOException lost) {
                  throw new IllegalStateException(lost.getMessage(), lost);
                }
              },
              value -> allocated[0] = value,
              guestCommand);
      TerminalTabs.Tab<PtySession> tab = pty.add(session);
      ptyProtection.put(tab.id, ticket);
      notifyPty();
      return tab;
    } catch (RuntimeException | Error failure) {
      // 已 fork 的失败对象保持原出生身份，发布“待核验”而不是留下无法显式关闭的隐藏租约。
      if (allocated[0] != null && allocated[0].isCleanupPending()) {
        TerminalTabs.Tab<PtySession> tab = pty.add(allocated[0]);
        ptyProtection.put(tab.id, ticket);
        pty.closeFailed(tab.id, safeError(failure));
        notifyPty();
      } else protection.releaseVerified(ticket);
      throw failure;
    } finally {
      protection.finishCreate(ticket);
    }
  }

  /** 创建后端对象并开启其原有异步启动；票在首次 fork 前即转为逻辑 hold。 */
  public TerminalTabs.Tab<SimpleTab> createSimple(
      TerminalRuntimeProtection.Ticket ticket, TerminalSession.Backend backend) throws IOException {
    requireProtection();
    protection.beginCreate(ticket, ownerEpoch, TerminalRuntimeProtection.Kind.SIMPLE);
    boolean published = false;
    try {
      if (backend == null) throw new IllegalArgumentException("TERMINAL_BACKEND_REQUIRED");
      SimpleTab value = new SimpleTab(protectedBackend(ticket, backend));
      protection.retain(ticket);
      TerminalTabs.Tab<SimpleTab> tab = simple.add(value);
      simpleProtection.put(tab.id, ticket);
      published = true;
      value.session.ensureStarted();
      notifySimpleTabs();
      return tab;
    } finally {
      if (!published) protection.releaseVerified(ticket);
      protection.finishCreate(ticket);
    }
  }

  private void requireProtection() throws IOException {
    if (protection == null) throw new IOException("TERMINAL_PROTECTION_REQUIRED");
  }

  private TerminalSession.Backend protectedBackend(
      TerminalRuntimeProtection.Ticket ticket, TerminalSession.Backend delegate) {
    return new TerminalSession.Backend() {
      @Override
      public AutoCloseable beginLifetime() throws Exception {
        protection.requireHeld(ticket);
        return delegate.beginLifetime();
      }

      @Override
      public Process open() throws Exception {
        protection.requireHeld(ticket);
        return delegate.open();
      }

      @Override
      public void onReady(Process process, com.deepseekharness.app.util.TerminalReady identity)
          throws Exception {
        delegate.onReady(process, identity);
      }

      @Override
      public void terminate(Process process, long group) throws Exception {
        delegate.terminate(process, group);
      }

      @Override
      public void write(Process process, String text) throws IOException {
        // 已接受输入仍沿原算法执行一次；LOST 在 owner 禁止新输入，不能借写入异常暗停进程。
        delegate.write(process, text);
      }
    };
  }

  public boolean renamePty(long id, String title) {
    boolean changed = pty.rename(id, title);
    if (changed) notifyPty();
    return changed;
  }

  public boolean renameSimple(long id, String title) {
    boolean changed = simple.rename(id, title);
    if (changed) notifySimpleTabs();
    return changed;
  }

  public boolean isPtyInputAllowed(long id) {
    TerminalTabs.Tab<PtySession> tab = pty.find(id);
    return tab != null
        && !tab.isClosing()
        && !tab.isUnconfirmed()
        && tab.value.cleanupError().isEmpty()
        && tab.value.isRunning()
        && (protection == null || protection.isProtected(ptyProtection.get(id)));
  }

  public boolean isSimpleInputAllowed(long id) {
    TerminalTabs.Tab<SimpleTab> tab = simple.find(id);
    return tab != null
        && !tab.isClosing()
        && !tab.isUnconfirmed()
        && (protection == null || protection.isProtected(simpleProtection.get(id)));
  }

  public boolean isPtyProtectionLost(long id) {
    return protectionLost(ptyProtection.get(id));
  }

  public boolean isSimpleProtectionLost(long id) {
    return protectionLost(simpleProtection.get(id));
  }

  private boolean protectionLost(TerminalRuntimeProtection.Ticket ticket) {
    if (protection == null || ticket == null) return false;
    synchronized (protection) {
      TerminalRuntimeProtection.State state = ticket.state();
      return state == TerminalRuntimeProtection.State.LOST
          || (state == TerminalRuntimeProtection.State.ACTIVE && !protection.isProtected(ticket));
    }
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
        TerminalRuntimeProtection.Ticket ticket = ptyProtection.remove(id);
        if (protection != null) protection.releaseVerified(ticket);
      } catch (IOException | InterruptedException | RuntimeException error) {
        pty.closeFailed(id, safeError(error));
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
        TerminalRuntimeProtection.Ticket ticket = simpleProtection.remove(id);
        if (protection != null) protection.releaseVerified(ticket);
      } catch (IOException | InterruptedException | RuntimeException error) {
        simple.closeFailed(id, safeError(error));
        throw error;
      } finally {
        notifySimpleTabs();
      }
    }
  }

  public interface CloseCallback {
    void onClosed(String ownerEpoch, TerminalRuntimeProtection.Kind kind, long id, String error);
  }

  public boolean closePtyAsync(long id, long timeoutMs, CloseCallback callback) {
    if (!beginClosePty(id)) return false;
    return dispatchClose(TerminalRuntimeProtection.Kind.PTY, id, timeoutMs, callback);
  }

  public boolean closeSimpleAsync(long id, long timeoutMs, CloseCallback callback) {
    if (!beginCloseSimple(id)) return false;
    return dispatchClose(TerminalRuntimeProtection.Kind.SIMPLE, id, timeoutMs, callback);
  }

  private boolean dispatchClose(
      TerminalRuntimeProtection.Kind kind, long id, long timeoutMs, CloseCallback callback) {
    try {
      closeWorker.execute(
          () -> {
            String error = "";
            try {
              if (kind == TerminalRuntimeProtection.Kind.PTY) closePty(id, timeoutMs);
              else closeSimple(id, timeoutMs);
            } catch (IOException | InterruptedException | RuntimeException failure) {
              if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
              error = safeError(failure);
            }
            if (callback != null) {
              try {
                callback.onClosed(ownerEpoch, kind, id, error);
              } catch (RuntimeException ignored) {
              }
            }
          });
      return true;
    } catch (RuntimeException unavailable) {
      if (kind == TerminalRuntimeProtection.Kind.PTY) {
        pty.closeFailed(id, safeError(unavailable));
        notifyPty();
      } else {
        simple.closeFailed(id, safeError(unavailable));
        notifySimpleTabs();
      }
      return false;
    }
  }

  private static String safeError(Throwable error) {
    String message = error == null ? "" : error.getMessage();
    String value =
        SensitiveData.redact(
                message == null || message.trim().isEmpty()
                    ? UiText.choose("尚未确认终端退出", "Terminal exit is not yet verified")
                    : message)
            .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ")
            .trim();
    return value.length() <= 240 ? value : value.substring(0, 240);
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
    if (observer != null) {
      try {
        observer.onOutput(tab);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private static String stripAnsi(String text) {
    return text.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "")
        .replaceAll("\\x1B\\][^\\x07]*\\x07", "")
        .replaceAll("\\x1B[()][0-9A-B]", "");
  }

  private void notifySimpleState(SimpleTab tab) {
    SimpleObserver observer = simpleObserver;
    if (observer != null) {
      try {
        observer.onState(tab);
      } catch (RuntimeException ignored) {
      }
    }
    for (Runnable stateObserver : simpleStateObservers) {
      try {
        stateObserver.run();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void notifyPty() {
    for (TerminalTabs.Tab<PtySession> tab : pty.snapshot()) {
      String error = tab.value.cleanupError();
      if (!error.isEmpty() && !tab.isClosing()) pty.closeFailed(tab.id, error);
    }
    PtyObserver observer = ptyObserver;
    if (observer != null) {
      try {
        observer.onTabsChanged();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void notifySimpleTabs() {
    SimpleObserver observer = simpleObserver;
    if (observer != null) {
      try {
        observer.onTabsChanged();
      } catch (RuntimeException ignored) {
      }
    }
  }
}
