package com.deepseekharness.app.util;

/**
 * 虚拟屏预览的显示档位与可见性决策。
 *
 * <p>默认档位是宿主页顶部的蓝色提示条；完整预览恢复旧的页内小窗，关闭则不再占屏。收起是
 * 独立于档位的持久化意图：收起后跨虚拟屏代次与 App 重启保持，直到用户在设置里重新选择
 * 档位（选择档位即重新显示）。本类只有纯逻辑，不读取 Android 资源或偏好，持久化与绘制由
 * 调用方负责。
 */
public final class VirtualScreenPreviewPolicy {
  /** 持久化键：档位与收起意图。键名一经发布即固定，改动会让老用户的选择失效。 */
  public static final String KEY_MODE = "vscreen_preview_mode";

  public static final String KEY_COLLAPSED = "vscreen_preview_collapsed";

  public static final String STORED_STRIP = "strip";
  public static final String STORED_FULL = "full";
  public static final String STORED_OFF = "off";

  /** 顶部提示条文字最多保留的码点数；超出截断，避免挤压「收起」入口。 */
  public static final int STRIP_LABEL_MAX_CODE_POINTS = 32;

  /** 呼吸动画的透明端；不跑动画时提示条停在 {@link #BREATH_FULL_ALPHA}。 */
  public static final float BREATH_DIM_ALPHA = 0.35f;

  public static final float BREATH_FULL_ALPHA = 1f;

  /** 档位：顶部提示条（默认）/ 完整预览（旧行为）/ 关闭。 */
  public enum Mode {
    STRIP,
    FULL,
    OFF
  }

  private VirtualScreenPreviewPolicy() {}

  /** 未设置、空值或无法识别时回到默认提示条，绝不猜测用户意图。 */
  public static Mode parse(String stored) {
    if (STORED_FULL.equals(stored)) return Mode.FULL;
    if (STORED_OFF.equals(stored)) return Mode.OFF;
    return Mode.STRIP;
  }

  public static String stored(Mode mode) {
    if (mode == Mode.FULL) return STORED_FULL;
    if (mode == Mode.OFF) return STORED_OFF;
    return STORED_STRIP;
  }

  /**
   * 宿主页此刻是否应显示预览（提示条或完整预览）。
   *
   * <p>虚拟屏未运行、宿主页离开前台、当前页是虚拟识屏页本身、系统悬浮预览正在让位，或用户
   * 已收起时都不显示。
   */
  public static boolean visible(
      Mode mode,
      boolean collapsed,
      boolean running,
      boolean hostResumed,
      boolean virtualScreenPage,
      boolean systemOverlayShowing) {
    return mode != null
        && mode != Mode.OFF
        && !collapsed
        && running
        && hostResumed
        && !virtualScreenPage
        && !systemOverlayShowing;
  }

  /** 是否显示顶部提示条（只有当档位是提示条时才成立，调用方仍须先过 {@link #visible}）。 */
  public static boolean strip(Mode mode) {
    return mode == Mode.STRIP;
  }

  /** 是否显示旧的页内完整预览小窗。 */
  public static boolean full(Mode mode) {
    return mode == Mode.FULL;
  }

  /** 提示条文字：有目标时「前缀 + 应用名 · 包名」，拿不到目标时只显示运行中文案。 */
  public static String stripText(
      String appLabel, String packageName, String operating, String prefix, int maxCodePoints) {
    String target = targetLabel(appLabel, packageName, maxCodePoints);
    if (target.isEmpty()) return truncate(operating, maxCodePoints);
    return truncate((prefix == null ? "" : prefix) + target, maxCodePoints);
  }

  /**
   * 目标标签：应用名优先，包名其次；两者都有时「应用名 · 包名」。拿不到返回空串，由调用方
   * 回退到运行中文案。按 Unicode 码点截断，不拆开代理对。
   */
  public static String targetLabel(String appLabel, String packageName, int maxCodePoints) {
    String label = clean(appLabel), pkg = clean(packageName);
    if (label.isEmpty() && pkg.isEmpty()) return "";
    if (label.isEmpty()) return truncate(pkg, maxCodePoints);
    if (pkg.isEmpty() || label.equals(pkg)) return truncate(label, maxCodePoints);
    return truncate(label + " · " + pkg, maxCodePoints);
  }

  /** 呼吸动画只在系统动画打开时跑；关闭或无障碍减少动态时停在静态不透明状态。 */
  public static float breathAlpha(boolean animationsEnabled, boolean dimPhase) {
    return animationsEnabled && dimPhase ? BREATH_DIM_ALPHA : BREATH_FULL_ALPHA;
  }

  /** 按 Unicode 码点截断并补省略号；不拆分代理对，不返回 null。 */
  public static String truncate(String value, int maxCodePoints) {
    if (value == null || value.isEmpty() || maxCodePoints <= 0) return "";
    if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
    if (maxCodePoints == 1) return "…";
    int end = value.offsetByCodePoints(0, maxCodePoints - 1);
    return value.substring(0, end) + "…";
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
