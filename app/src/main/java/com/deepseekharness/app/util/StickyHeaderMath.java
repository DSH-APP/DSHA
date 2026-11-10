package com.deepseekharness.app.util;

/**
 * 分组吸顶的取整数学（F4）：表头越过视口顶部后平移贴顶，到本组底部时被顶出。
 *
 * <p>只用滚动位置与组几何计算，不依赖 Android 视图；测试覆盖边界，组件层只负责把结果写进
 * translationY。
 */
public final class StickyHeaderMath {
  private StickyHeaderMath() {}

  /**
   * 表头相对布局位置的吸顶位移。
   *
   * @param scrollY 滚动容器已滚动距离
   * @param headerTop 表头在滚动内容中的顶部坐标
   * @param headerHeight 表头高度
   * @param groupBottom 本组（含表头与正文）在滚动内容中的底部坐标
   */
  public static int offset(int scrollY, int headerTop, int headerHeight, int groupBottom) {
    if (headerHeight <= 0) return 0;
    int covered = scrollY - headerTop;
    if (covered <= 0) return 0;
    int limit = groupBottom - headerTop - headerHeight;
    return Math.min(covered, Math.max(0, limit));
  }

  /** 当前应吸顶的组下标（表头已在视口顶部或更高）；没有任何组吸顶返回 -1。 */
  public static int activeGroup(int scrollY, int[] headerTops, int[] headerHeights) {
    if (headerTops == null || headerTops.length == 0) return -1;
    int active = -1;
    for (int index = 0; index < headerTops.length; index++) {
      int height = headerHeights != null && index < headerHeights.length ? headerHeights[index] : 0;
      if (scrollY + height >= headerTops[index]) active = index;
      else break;
    }
    if (active < 0) return -1;
    return scrollY >= headerTops[active] ? active : -1;
  }
}
