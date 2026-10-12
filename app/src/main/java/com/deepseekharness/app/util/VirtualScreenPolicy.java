package com.deepseekharness.app.util;

/** 虚拟屏的纯尺寸、坐标和过期帧规则；不依赖 Android。 */
public final class VirtualScreenPolicy {
  private VirtualScreenPolicy() {}

  public static final int MIN_API = 30;

  public static boolean supported(int api) {
    return api >= MIN_API;
  }

  public static int[] phoneSize(int requestedWidth, int requestedHeight) {
    boolean landscape = requestedWidth > requestedHeight;
    int source = Math.min(Math.max(requestedWidth, 1), Math.max(requestedHeight, 1));
    int edge = Math.max(144, Math.min(1440, Math.round(source / 144f) * 144));
    int longEdge = edge * 16 / 9;
    return landscape ? new int[] {longEdge, edge} : new int[] {edge, longEdge};
  }

  public static float[] map(
      float x, float y, int viewWidth, int viewHeight, int screenWidth, int screenHeight) {
    if (viewWidth <= 0 || viewHeight <= 0 || screenWidth <= 0 || screenHeight <= 0)
      return new float[] {0, 0};
    return new float[] {
      Math.max(0, Math.min(screenWidth, x * screenWidth / viewWidth)),
      Math.max(0, Math.min(screenHeight, y * screenHeight / viewHeight))
    };
  }

  /** 输入允许使用「最近一次观察到的帧」的时间窗（毫秒）。真机实测模型单步往返：中位 3.7s、P75 7.4s、P90 11.8s。 */
  public static final long OBSERVATION_WINDOW_MS = 10_000;

  /** 输入帧判定结果：接受 / 帧身份不符 / 观察已过期。 */
  public enum InputFrame {
    ACCEPTED,
    STALE,
    EXPIRED
  }

  /**
   * 判定一次输入是否可以使用调用方出示的帧。
   *
   * <p>旧规则要求 {@code presented == current}（输入帧必须等于此刻的最新帧）。页面上只要存在动画或
   * 滚动，帧号就会持续前进 —— 从「看到画面」到「注入输入」之间隔着一次模型往返，最新帧必然已经变化，
   * 于是输入被永久拒绝。新规则只要求两件事：调用方出示的就是上次登记观察的那一帧，且该次观察足够新。
   * 画面后续前进多少帧都不影响判定，因为注入的画面本来就是调用方看过的那一帧。
   */
  public static InputFrame inputFrame(
      long presented, long registered, long current, long observedAt, long now) {
    if (presented <= 0 || presented != registered || presented > current) return InputFrame.STALE;
    if (observedAt <= 0 || now < observedAt) return InputFrame.STALE;
    if (now - observedAt > OBSERVATION_WINDOW_MS) return InputFrame.EXPIRED;
    return InputFrame.ACCEPTED;
  }
}
