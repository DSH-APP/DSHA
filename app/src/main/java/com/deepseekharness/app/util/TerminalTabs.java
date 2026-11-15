package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 会话身份永久递增；显示编号复用空缺，关闭失败或关闭中仍占用编号。 */
public final class TerminalTabs<T> {
  public enum CleanupState {
    NOT_REQUESTED,
    CLOSING,
    VERIFIED,
    UNCONFIRMED
  }

  /** 关闭投影用单一不可变值发布，UI 不会读到状态/错误来自不同代次的组合。 */
  public static final class CloseSnapshot {
    public final CleanupState state;
    public final long generation;
    public final String error;

    private CloseSnapshot(CleanupState state, long generation, String error) {
      this.state = state;
      this.generation = generation;
      this.error = error;
    }
  }

  /** Presentation-only access. The implementing object is not a TerminalTabs instance. */
  public interface ReadOnly<T> {
    List<Tab<T>> snapshot();

    Tab<T> current();

    Tab<T> find(long id);

    boolean wasInitialized();
  }

  private static final class View<T> implements ReadOnly<T> {
    private final TerminalTabs<T> owner;

    private View(TerminalTabs<T> owner) {
      this.owner = owner;
    }

    @Override
    public List<Tab<T>> snapshot() {
      return owner.snapshot();
    }

    @Override
    public Tab<T> current() {
      return owner.current();
    }

    @Override
    public Tab<T> find(long id) {
      return owner.find(id);
    }

    @Override
    public boolean wasInitialized() {
      return owner.wasInitialized();
    }
  }

  public static final class Tab<T> {
    public final long id;
    public final int number;
    public final T value;
    private volatile CloseSnapshot close = new CloseSnapshot(CleanupState.NOT_REQUESTED, 0, "");
    private volatile String title = "";

    private Tab(long id, int number, T value) {
      this.id = id;
      this.number = number;
      this.value = value;
    }

    public boolean isClosing() {
      return close.state == CleanupState.CLOSING;
    }

    public boolean isUnconfirmed() {
      return close.state == CleanupState.UNCONFIRMED;
    }

    public boolean isCloseUnconfirmed() {
      return isUnconfirmed();
    }

    public CleanupState cleanupState() {
      return close.state;
    }

    public CloseSnapshot closeSnapshot() {
      return close;
    }

    public String error() {
      return close.error;
    }

    public String closeError() {
      return error();
    }

    public String customTitle() {
      return title;
    }
  }

  private final List<Tab<T>> tabs = new ArrayList<>();
  private final ReadOnly<T> readOnly = new View<>(this);
  private long next = 1, selected;
  private boolean initialized;

  public ReadOnly<T> readOnly() {
    return readOnly;
  }

  public synchronized Tab<T> add(T value) {
    if (value == null)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("终端会话不能为空"));
    int number = 1;
    java.util.HashSet<Integer> occupied = new java.util.HashSet<>();
    for (Tab<T> item : tabs) occupied.add(item.number);
    while (occupied.contains(number)) number++;
    Tab<T> tab = new Tab<>(next++, number, value);
    tabs.add(tab);
    selected = tab.id;
    initialized = true;
    return tab;
  }

  public synchronized List<Tab<T>> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(tabs));
  }

  public synchronized Tab<T> current() {
    return find(selected);
  }

  public synchronized Tab<T> find(long id) {
    for (Tab<T> tab : tabs) if (tab.id == id) return tab;
    return null;
  }

  public synchronized boolean select(long id) {
    if (find(id) == null) return false;
    selected = id;
    return true;
  }

  public synchronized boolean beginClose(long id) {
    Tab<T> tab = find(id);
    if (tab == null || tab.isClosing()) return false;
    tab.close = new CloseSnapshot(CleanupState.CLOSING, tab.close.generation + 1, "");
    return true;
  }

  public synchronized void closeFailed(long id) {
    closeFailed(id, "");
  }

  public synchronized void closeFailed(long id, String error) {
    Tab<T> tab = find(id);
    if (tab != null)
      tab.close =
          new CloseSnapshot(CleanupState.UNCONFIRMED, tab.close.generation, safeLabel(error, 240));
  }

  public synchronized boolean closeFailed(long id, long generation, String error) {
    Tab<T> tab = find(id);
    if (tab == null || tab.close.generation != generation || !tab.isClosing()) return false;
    closeFailed(id, error);
    return true;
  }

  public synchronized boolean rename(long id, String title) {
    Tab<T> tab = find(id);
    if (tab == null) return false;
    tab.title = safeLabel(title, 80);
    return true;
  }

  private static String safeLabel(String value, int maxCodePoints) {
    String safe =
        SensitiveData.redact(value == null ? "" : value)
            .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ")
            .trim();
    int count = safe.codePointCount(0, safe.length());
    return count <= maxCodePoints
        ? safe
        : safe.substring(0, safe.offsetByCodePoints(0, maxCodePoints));
  }

  public synchronized void remove(long id) {
    Tab<T> tab = find(id);
    if (tab == null) return;
    int index = tabs.indexOf(tab);
    tab.close = new CloseSnapshot(CleanupState.VERIFIED, tab.close.generation, "");
    tabs.remove(index);
    if (selected == id)
      selected = tabs.isEmpty() ? 0 : tabs.get(Math.min(index, tabs.size() - 1)).id;
  }

  public synchronized boolean wasInitialized() {
    return initialized;
  }
}
