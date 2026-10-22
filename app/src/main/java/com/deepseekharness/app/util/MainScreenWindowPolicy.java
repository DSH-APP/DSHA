package com.deepseekharness.app.util;

import java.util.List;

/** Physical display policy. Virtual displays retain their separate application-window policy. */
public final class MainScreenWindowPolicy {
  private MainScreenWindowPolicy() {}

  public enum Kind {
    APPLICATION,
    SYSTEM,
    INPUT_METHOD,
    UNSUPPORTED
  }

  public record Window(
      int display,
      int id,
      int layer,
      Kind kind,
      boolean focused,
      boolean active,
      ScreenWindowRegion.Bounds bounds) {}

  public record Selection(int index, String diagnostic) {}

  /** IME is an occluder of the focused editor, never an excuse to choose a background app. */
  public static int select(List<Window> windows) {
    return selection(windows).index;
  }

  public static Selection selection(List<Window> windows) {
    int selected = -1, layer = Integer.MIN_VALUE;
    boolean ambiguous = false;
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      if (w.display != 0 || w.id < 0 || !w.focused && !w.active || w.kind == Kind.INPUT_METHOD)
        continue;
      if (selected < 0 || w.layer > layer) {
        selected = i;
        layer = w.layer;
        ambiguous = false;
      } else if (w.layer == layer) ambiguous = true;
    }
    if (ambiguous) return new Selection(-1, "AMBIGUOUS_INTERACTIVE_WINDOWS");
    if (selected < 0) return new Selection(-1, "NO_FOCUSED_OR_ACTIVE_WINDOW");
    Window w = windows.get(selected);
    if (w.kind != Kind.APPLICATION && w.kind != Kind.SYSTEM)
      return new Selection(-1, "UNSUPPORTED_INTERACTIVE_WINDOW");
    return new Selection(selected, "READABLE");
  }

  /** Clip only non-interactive system chrome / IME covering a complete edge. Interior covers refuse coordinates. */
  public static ScreenWindowRegion region(int selected, List<Window> windows) {
    Window target = windows.get(selected);
    var original = target.bounds;
    if (original == null || !original.valid()) return null;
    var content = original;
    boolean obscured = false;
    for (Window other : windows) {
      if (other.display != 0 || other.id == target.id || other.layer <= target.layer) continue;
      var cover = other.bounds;
      if (cover == null || !cover.valid()) {
        obscured = true;
        continue;
      }
      if (!content.intersects(cover)) continue;
      boolean edgeChrome =
          other.kind == Kind.INPUT_METHOD
              || other.kind == Kind.SYSTEM && !other.focused && !other.active;
      if (edgeChrome
          && cover.left() <= content.left()
          && cover.right() >= content.right()
          && cover.top() <= content.top()
          && cover.bottom() < content.bottom())
        content =
            new ScreenWindowRegion.Bounds(
                content.left(), cover.bottom(), content.right(), content.bottom());
      else if (edgeChrome
          && cover.left() <= content.left()
          && cover.right() >= content.right()
          && cover.bottom() >= content.bottom()
          && cover.top() > content.top())
        content =
            new ScreenWindowRegion.Bounds(
                content.left(), content.top(), content.right(), cover.top());
      else if (edgeChrome
          && cover.top() <= content.top()
          && cover.bottom() >= content.bottom()
          && cover.left() <= content.left()
          && cover.right() < content.right())
        content =
            new ScreenWindowRegion.Bounds(
                cover.right(), content.top(), content.right(), content.bottom());
      else if (edgeChrome
          && cover.top() <= content.top()
          && cover.bottom() >= content.bottom()
          && cover.right() >= content.right()
          && cover.left() > content.left())
        content =
            new ScreenWindowRegion.Bounds(
                content.left(), content.top(), cover.left(), content.bottom());
      else obscured = true;
    }
    return new ScreenWindowRegion(original, content, obscured);
  }
}
