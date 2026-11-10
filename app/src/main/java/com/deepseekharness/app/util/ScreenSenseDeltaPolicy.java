package com.deepseekharness.app.util;

/**
 * 连续紧凑识屏的增量判定。稳定编号只是“阅读顺序里的位置编号”，仅用于预览与差异展示，不是动作凭据；动作仍必须
 * 使用 {@code /app/ui/tree} 或 {@code /app/ui/dump} 新签发的 treeId/nodeId 租约。
 *
 * <p>只有同一任务 owner、同一无障碍代次、同一窗口、同一帧几何（尺寸 / 焦点 / 输入法 / 来源 / 截断状态）才允许
 * 增量；变化超过三分之一直接回全量，避免顺序漂移让调用方把旧位置当成新节点。{@code full=1} 永远回全量。
 */
public final class ScreenSenseDeltaPolicy {
  /** 变化比例闸：{@code changed * 3 > total} 视为超过三分之一，回全量。 */
  private static final int THRESHOLD_DENOMINATOR = 3;

  private ScreenSenseDeltaPolicy() {}

  public enum Mode {
    FULL,
    DELTA,
    SAME
  }

  public record Decision(Mode mode, String reason, ScreenSenseCodec.Delta delta) {}

  public static Decision decide(
      ScreenSenseState previous, ScreenSenseState current, boolean fullRequested) {
    if (current == null) throw new IllegalArgumentException("SCREEN_SENSE_STATE_MISSING");
    if (fullRequested) return full("FULL_REQUESTED");
    if (previous == null) return full("NO_PREVIOUS");
    if (!previous.treeId.equals(current.treeId)) return full("SCOPE_CHANGED");
    if (!previous.target.matches(current.target)) return full("TARGET_CHANGED");
    if (current.sid <= previous.sid) return full("SID_NOT_MONOTONIC");
    if (previous.width != current.width
        || previous.height != current.height
        || previous.focused != current.focused
        || previous.ime != current.ime
        || !previous.source.equals(current.source)
        || previous.truncated != current.truncated
        || !previous.truncationReason.equals(current.truncationReason))
      return full("FRAME_CHANGED");
    ScreenSenseCodec.Delta delta;
    try {
      delta = ScreenSenseCodec.diff(previous, current);
    } catch (IllegalArgumentException refused) {
      return full("DIFF_UNAVAILABLE");
    }
    if (delta.changedCount == 0) return new Decision(Mode.SAME, "UNCHANGED", delta);
    int total = Math.max(previous.elements.size(), current.elements.size());
    if ((long) delta.changedCount * THRESHOLD_DENOMINATOR > total)
      return full("CHANGED_OVER_THIRD");
    return new Decision(Mode.DELTA, "WITHIN_THRESHOLD", delta);
  }

  private static Decision full(String reason) {
    return new Decision(Mode.FULL, reason, null);
  }
}
