package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变的紧凑识屏快照。它只包含已经脱离 AccessibilityNodeInfo 的数据，不能当作动作授权。
 */
public final class ScreenSenseState {
  public final long sid;
  public final String treeId;
  public final ScreenTarget target;
  public final int width, height;
  public final boolean focused;

  /** -1 means the IME state is not observable from the accessibility tree. */
  public final int ime;

  public final String source;
  public final int visited;
  public final boolean truncated;
  public final String truncationReason;
  public final List<Element> elements;

  public ScreenSenseState(
      long sid,
      String treeId,
      ScreenTarget target,
      int width,
      int height,
      boolean focused,
      int ime,
      String source,
      int visited,
      boolean truncated,
      String truncationReason,
      List<Element> elements) {
    if (sid < 0
        || treeId == null
        || treeId.isEmpty()
        || target == null
        || target.windowId < 0
        || !target.packageName.matches("[A-Za-z0-9._-]+")
        || width < 0
        || height < 0
        || (ime != -1 && ime != 0 && ime != 1)
        || source == null
        || !source.matches("[a-z]+")
        || visited < 0
        || elements == null) throw new IllegalArgumentException("SCREEN_SENSE_STATE_INVALID");
    String reason = truncationReason == null ? "" : truncationReason;
    if (!reason.matches("[a-z]*")) throw new IllegalArgumentException("SCREEN_SENSE_STATE_INVALID");
    Set<String> ids = new HashSet<>();
    for (Element element : elements) {
      if (element == null) throw new IllegalArgumentException("SCREEN_SENSE_ELEMENT_INVALID");
      if (!ids.add(element.id)) throw new IllegalArgumentException("SCREEN_SENSE_DUPLICATE_ID");
    }
    this.sid = sid;
    this.treeId = treeId;
    this.target = target;
    this.width = width;
    this.height = height;
    this.focused = focused;
    this.ime = ime;
    this.source = source;
    this.visited = visited;
    this.truncated = truncated;
    this.truncationReason = reason;
    this.elements = Collections.unmodifiableList(List.copyOf(elements));
  }

  /** Compatibility constructor for early callers; new snapshots should provide display metadata. */
  public ScreenSenseState(
      long sid,
      String treeId,
      ScreenTarget target,
      int visited,
      boolean truncated,
      String truncationReason,
      List<Element> elements) {
    this(
        sid,
        treeId,
        target,
        0,
        0,
        false,
        -1,
        "tree",
        visited,
        truncated,
        truncationReason,
        elements);
  }

  /**
   * 读取质量：{@code partial} 表示达到节点/时间/数量上限，结果不完整；{@code empty} 表示树可读但没有可报告元素
   * （空树或低语义界面，日后 OCR 决策门按它抽样）；其余为 {@code ok}。截断优先于空。
   */
  public String quality() {
    if (truncated) return "partial";
    return elements.isEmpty() ? "empty" : "ok";
  }

  public ScreenSenseState withSid(long value) {
    return new ScreenSenseState(
        value,
        treeId,
        target,
        width,
        height,
        focused,
        ime,
        source,
        visited,
        truncated,
        truncationReason,
        elements);
  }

  /** A compact element id is only unique inside this snapshot's tree scope. */
  public static final class Element {
    public final String id;
    public final String role;
    public final String label;
    public final String flags;
    public final String actions;
    public final boolean hasBounds;
    public final int left, top, right, bottom;

    public Element(
        String id,
        String role,
        String label,
        String flags,
        String actions,
        boolean hasBounds,
        int left,
        int top,
        int right,
        int bottom) {
      if (id == null
          || !id.matches("[a-z][A-Za-z0-9]+")
          || role == null
          || !role.matches("[A-Z]")
          || label == null
          || flags == null
          || !flags.matches("[!*%^~]*")
          || actions == null
          || !actions.matches("[clfbot]*")
          || (hasBounds && (left >= right || top >= bottom)))
        throw new IllegalArgumentException("SCREEN_SENSE_ELEMENT_INVALID");
      this.id = id;
      this.role = role;
      this.label = label;
      this.flags = flags;
      this.actions = actions;
      this.hasBounds = hasBounds;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Element value)) return false;
      return id.equals(value.id)
          && role.equals(value.role)
          && label.equals(value.label)
          && flags.equals(value.flags)
          && actions.equals(value.actions)
          && hasBounds == value.hasBounds
          && (!hasBounds
              || (left == value.left
                  && top == value.top
                  && right == value.right
                  && bottom == value.bottom));
    }

    @Override
    public int hashCode() {
      return hasBounds
          ? Objects.hash(id, role, label, flags, actions, true, left, top, right, bottom)
          : Objects.hash(id, role, label, flags, actions, false);
    }
  }
}
