package com.deepseekharness.app.util;

/** 虚拟屏的纯尺寸、坐标和过期帧规则；不依赖 Android。 */
public final class VirtualScreenPolicy {
  private VirtualScreenPolicy() {}

  public static final int MIN_API = 30;

  /** 观察窗口：超过它就必须重新 see，避免用很久以前的画面决策。 */
  public static final long OBSERVATION_WINDOW_MS = 30_000;

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

  public static boolean fresh(long observed, long current) {
    return observed > 0 && observed == current;
  }

  /** 权限探测结果；{@code UNKNOWN} 表示查不出来，一律按"不持有"处理。 */
  public enum TrustedDisplayPermission {
    HELD,
    NOT_HELD,
    UNKNOWN
  }

  /**
   * 是否请求 {@code VIRTUAL_DISPLAY_FLAG_TRUSTED}。
   *
   * <p>该 flag 在 API 31+ 需要签名级 {@code ADD_TRUSTED_DISPLAY}，不持有却请求会被
   * DisplayManagerService 直接抛 SecurityException（issue #123：Android 12 的 shell 未申请该权限，
   * ADB/Shizuku 通道因此恒失败）。不请求它的代价是：该显示不再是受信任显示 —— 输入法策略回落到
   * 默认显示、不参与 display area organizer 分配（DSHA 本来就不请求系统装饰，输入走事件注入与
   * 无障碍），因此按调用方实际持有的权限决定请求与否。探测不出来时宁可不要 TRUSTED：不请求它
   * 的创建仍然可以成功。
   *
   * @param uid 实际发起创建的进程 uid
   * @param permission 该 uid 对 {@code ADD_TRUSTED_DISPLAY} 的探测结果
   */
  public static boolean requestTrustedDisplay(int uid, TrustedDisplayPermission permission) {
    return uid == 0 || permission == TrustedDisplayPermission.HELD;
  }

  public static final String FRAME_NOT_OBSERVED = "FRAME_NOT_OBSERVED";
  public static final String FRAME_EXPIRED = "FRAME_EXPIRED";
  public static final String OBSERVATION_TIMEOUT = "OBSERVATION_TIMEOUT";

  /** 帧号是否仍是最新帧；返回 null 表示放行，否则返回稳定的拒绝码。 */
  public static String frameRejection(long requested, long current) {
    if (requested <= 0) return FRAME_NOT_OBSERVED;
    return fresh(requested, current) ? null : FRAME_EXPIRED;
  }

  /**
   * 坐标与文本输入还要求调用方确实看过这一帧（观察由 see/preview 写入，输入后复位）。
   * 返回 null 表示放行，否则返回稳定的拒绝码。
   */
  public static String observationRejection(long requested, long observed, long ageMs) {
    if (requested <= 0 || observed != requested) return FRAME_NOT_OBSERVED;
    return ageMs > OBSERVATION_WINDOW_MS ? OBSERVATION_TIMEOUT : null;
  }

  /**
   * 一次响应携带的帧号比已知状态更旧。只有查询类响应（status / preview）才据此拒绝 ——
   * 输入可能已经真的执行过，它的结果必须保留，绝不能因为随后到来的旧预览被丢掉。
   */
  public static boolean frameRegressed(long previous, long returned, boolean queryRoute) {
    return queryRoute && returned >= 0 && returned < previous;
  }

  /** 帧号只前进：不因为一次旧响应把观察元数据退回去。 */
  public static long advanceFrameSequence(long previous, long returned) {
    return returned >= 0 ? Math.max(previous, returned) : previous;
  }

  /**
   * 输入帧门的完整判定（核心用的就是这个组合）：先问"这一帧是不是你刚看过的那一帧"，
   * 再问"它还是不是最新帧"。返回 null 表示放行，否则返回稳定的拒绝码。
   */
  public static String inputRejection(long requested, long current, long observed, long ageMs) {
    String rejected = observationRejection(requested, observed, ageMs);
    return rejected != null ? rejected : frameRejection(requested, current);
  }
}
