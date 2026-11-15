package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟屏显示规格白名单（尺寸 + dpi + 逻辑 dp）；不依赖 Android。
 *
 * <p>默认规格保持 {@code 1008×1792 @320}（16:9，逻辑 504×896 dp），与既有
 * {@link VirtualScreenPolicy#phoneSize(int, int)} 的归一化结果一致，所以默认行为、既有测试与真机基线
 * 都不变。{@code 720×1600 @320}（20:9，逻辑 360×800 dp）作为 360 dp 兼容基线候选，只由用户显式选择。
 *
 * <p>白名单之外（包括未被允许的 1440×2560）一律回退默认：创建参数不接受任意尺寸/dpi，避免把虚拟屏
 * 创建变成新的资源与稳定性入口。{@code phoneSize()} 仍保留给不带预设的旧请求；白名单预设按原尺寸创建，
 * 不再经过 16:9 + 144 倍数的 legacy 归一化。
 *
 * <p>观察预算与 {@link com.deepseekharness.app.vscreen.VirtualScreenObservedPixels} 共用同一常量来源：
 * 助手保留的原图最多 {@link #OBSERVED_COUNT_LIMIT} 份、合计 {@link #OBSERVED_BYTE_LIMIT} 字节。
 * 1440×2560 RGBA_8888 两份约 28.1 MiB，超过 24 MiB，因此不进入白名单；若将来要启用，必须先核算并
 * 调整观察预算，而不是只加一行预设。
 */
public final class VirtualScreenDisplayPreset {
  private VirtualScreenDisplayPreset() {}

  /** 偏好键：只保存白名单 id；未知值、空值与旧版本键按默认预设处理。 */
  public static final String KEY_PRESET = "vscreen_display_preset";

  public static final String DEFAULT_ID = "default";
  public static final String COMPAT_720_ID = "compat-720";

  /** Android 逻辑 dp 的基准密度：逻辑尺寸 = 像素 × 160 / dpi。 */
  public static final int LOGICAL_DENSITY = 160;

  /** RGBA_8888 每像素字节数。 */
  public static final int BYTES_PER_PIXEL = 4;

  /** 观察原图预算：最多 2 份、合计 24 MiB（两处共用这一常量来源）。 */
  public static final long OBSERVED_BYTE_LIMIT = 24L * 1024 * 1024;

  public static final int OBSERVED_COUNT_LIMIT = 2;

  /** 一个白名单预设；尺寸以竖屏记录，横屏时交换宽高。 */
  public static final class Preset {
    public final String id;
    public final int width;
    public final int height;
    public final int density;

    private Preset(String id, int width, int height, int density) {
      this.id = id;
      this.width = width;
      this.height = height;
      this.density = density;
    }

    /** 按方向取像素尺寸：长边为 width，横屏交换。 */
    public int[] size(boolean landscape) {
      return landscape ? new int[] {height, width} : new int[] {width, height};
    }

    public int logicalWidth() {
      return width * LOGICAL_DENSITY / density;
    }

    public int logicalHeight() {
      return height * LOGICAL_DENSITY / density;
    }

    public long rawBytes() {
      return VirtualScreenDisplayPreset.rawBytes(width, height);
    }

    public long observationBytes() {
      return VirtualScreenDisplayPreset.observationBytes(width, height);
    }

    @Override
    public String toString() {
      return id + " " + width + "×" + height + "@" + density;
    }
  }

  private static final Preset DEFAULT = new Preset(DEFAULT_ID, 1008, 1792, 320);
  private static final Preset COMPAT_720 = new Preset(COMPAT_720_ID, 720, 1600, 320);

  private static final Map<String, Preset> WHITELIST;

  static {
    Map<String, Preset> values = new LinkedHashMap<>();
    values.put(DEFAULT.id, DEFAULT);
    values.put(COMPAT_720.id, COMPAT_720);
    WHITELIST = Collections.unmodifiableMap(values);
  }

  public static Preset defaultPreset() {
    return DEFAULT;
  }

  /** 白名单顺序即选择入口顺序：默认在前。 */
  public static List<Preset> whitelist() {
    return List.copyOf(WHITELIST.values());
  }

  /** 严格解析：null / 空白 / 未知 id 返回 null，由调用方决定回退。 */
  public static Preset parse(String id) {
    return id == null ? null : WHITELIST.get(id.trim());
  }

  /** 解析并回退默认：设置损坏、旧版本未知 id、空值都回落到 1008×1792@320。 */
  public static Preset resolve(String id) {
    Preset preset = parse(id);
    return preset == null ? DEFAULT : preset;
  }

  /**
   * 按像素与 dpi 反查白名单预设（先按短边/长边归一化方向）；非白名单组合返回 null。
   *
   * <p>用于状态回读与“默认不变”核对：不带预设的旧请求只要尺寸仍是 1008×1792@320，也识别为
   * {@code default}；其它旧尺寸如实返回 null，不猜成某个预设。
   */
  public static Preset matching(int width, int height, int density) {
    int shortEdge = Math.min(width, height);
    int longEdge = Math.max(width, height);
    for (Preset preset : WHITELIST.values())
      if (preset.width == shortEdge && preset.height == longEdge && preset.density == density)
        return preset;
    return null;
  }

  public static long rawBytes(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    return (long) width * height * BYTES_PER_PIXEL;
  }

  /** 观察原图按上限份数计的总字节；预算核算只看白名单预设的竖屏尺寸。 */
  public static long observationBytes(int width, int height) {
    return rawBytes(width, height) * OBSERVED_COUNT_LIMIT;
  }

  public static boolean fitsObservationBudget(int width, int height) {
    long bytes = observationBytes(width, height);
    return bytes > 0 && bytes <= OBSERVED_BYTE_LIMIT;
  }

  public static boolean fitsObservationBudget(Preset preset) {
    return preset != null && fitsObservationBudget(preset.width, preset.height);
  }

  /** 白名单内每一项都必须装得进当前观察预算；单测守卫，防止以后误加 1440p。 */
  public static boolean whitelistFitsObservationBudget() {
    for (Preset preset : WHITELIST.values()) if (!fitsObservationBudget(preset)) return false;
    return true;
  }
}
