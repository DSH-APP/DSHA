package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 锁定主屏窗口选择/遮挡判据。
 *
 * <p>#210：DSHA 自有的非焦点悬浮窗（浮条、助手、确认窗）不得把目标应用判成
 * TARGET_WINDOW_OCCLUDED。P1 遮挡误判修复：系统装饰/IME 的贴边覆盖按边裁切，判定与窗口
 * 列表顺序/层级无关；内部覆盖仍整窗拒绝坐标；零面积/无效与 1×1 平台窗跳过。
 */
public class MainScreenWindowPolicyTest {

  private static final ScreenWindowRegion.Bounds TARGET =
      new ScreenWindowRegion.Bounds(0, 100, 1260, 2800);
  private static final ScreenWindowRegion.Bounds OVERLAY =
      new ScreenWindowRegion.Bounds(0, 119, 1184, 316);

  /** 审查报告使用的 vivo 逻辑尺寸。 */
  private static final ScreenWindowRegion.Bounds PHONE =
      new ScreenWindowRegion.Bounds(0, 0, 1080, 2408);

  /** build203 真机记录的 ZUI 屏幕尺寸。 */
  private static final ScreenWindowRegion.Bounds ZUI =
      new ScreenWindowRegion.Bounds(0, 0, 1600, 2560);

  private static MainScreenWindowPolicy.Window window(
      int id,
      MainScreenWindowPolicy.Kind kind,
      boolean focused,
      boolean active,
      int layer,
      ScreenWindowRegion.Bounds bounds,
      ScreenWindowRegion.Exclusion owned) {
    return new MainScreenWindowPolicy.Window(0, id, layer, kind, focused, active, bounds, owned);
  }

  /** 非交互的系统装饰/输入法窗口。 */
  private static MainScreenWindowPolicy.Window chrome(
      int id, int layer, MainScreenWindowPolicy.Kind kind, ScreenWindowRegion.Bounds bounds) {
    return window(id, kind, false, false, layer, bounds, null);
  }

  /** 目标应用（focused/active、最低层）与其上层 cover 组成的窗口列表。 */
  private static ScreenWindowRegion regionOf(
      ScreenWindowRegion.Bounds target, MainScreenWindowPolicy.Window... covers) {
    var windows = new ArrayList<MainScreenWindowPolicy.Window>();
    windows.add(
        new MainScreenWindowPolicy.Window(
            0, 1, 1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, target));
    windows.addAll(List.of(covers));
    return MainScreenWindowPolicy.region(0, windows);
  }

  /** #210 回归：未登记的自有非焦点悬浮窗按包名身份整窗排除，不构成遮挡。 */
  @Test
  public void ownUnregisteredOverlayBecomesAnExclusionNotAnOccluder() {
    ScreenWindowRegion.Exclusion fallback =
        MainScreenWindowPolicy.ownFallbackExclusion(true, false, 42, OVERLAY, null);
    assertNotNull("同包非焦点悬浮窗必须被整窗排除", fallback);
    assertEquals(42, fallback.windowId());
    assertEquals(OVERLAY, fallback.bounds());

    var windows =
        java.util.List.of(
            window(1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, 10, TARGET, null),
            window(
                42, MainScreenWindowPolicy.Kind.APPLICATION, false, false, 20, OVERLAY, fallback));
    MainScreenWindowPolicy.Selection selection = MainScreenWindowPolicy.selection(windows);
    assertEquals("排除后的浮窗不参与目标选择", 0, selection.index());
    assertEquals("READABLE", selection.diagnostic());

    ScreenWindowRegion region = MainScreenWindowPolicy.region(0, windows);
    assertNotNull(region);
    assertFalse("自有浮窗不得把目标判成遮挡", region.obscured());
    assertEquals(1, region.exclusions().size());
    assertEquals(OVERLAY, region.exclusions().get(0).bounds());
  }

  /** 登记路径优先：match 已命中的排除原样返回，不重复构造。 */
  @Test
  public void registeredExclusionIsReturnedAsIs() {
    var registered = new ScreenWindowRegion.Exclusion(7, 9, OVERLAY);
    assertSame(
        registered,
        MainScreenWindowPolicy.ownFallbackExclusion(true, false, 7, OVERLAY, registered));
  }

  /** 可聚焦的自有窗口可能是观察目标本身，不适用整窗排除。 */
  @Test
  public void focusedOwnWindowIsNeverFallbackExcluded() {
    assertNull(MainScreenWindowPolicy.ownFallbackExclusion(true, true, 7, TARGET, null));
  }

  /** 其他应用的窗口绝不因本规则被排除。 */
  @Test
  public void foreignPackageIsNeverFallbackExcluded() {
    assertNull(MainScreenWindowPolicy.ownFallbackExclusion(false, false, 7, OVERLAY, null));
  }

  /** 无效边界的窗口拿不到 fallback 排除；region 里按 P2-1 直接跳过。 */
  @Test
  public void invalidBoundsNeverBecomeExclusions() {
    var invalid = new ScreenWindowRegion.Bounds(10, 10, 10, 10);
    assertNull(MainScreenWindowPolicy.ownFallbackExclusion(true, false, 7, invalid, null));
  }

  /** 排除区内的坐标轨迹仍被拒绝；完全盖满时依旧无可点面积。 */
  @Test
  public void excludedRegionStillRefusesTrajectoriesAndFullCoverStillUnusable() {
    ScreenWindowRegion.Exclusion fallback =
        MainScreenWindowPolicy.ownFallbackExclusion(true, false, 42, OVERLAY, null);
    var windows =
        java.util.List.of(
            window(1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, 10, TARGET, null),
            window(
                42, MainScreenWindowPolicy.Kind.APPLICATION, false, false, 20, OVERLAY, fallback));
    ScreenWindowRegion region = MainScreenWindowPolicy.region(0, windows);
    assertFalse("穿过浮条的滑动不得放行", region.containsTrajectory(600, 150, 600, 2700, 8));
    assertTrue("避开浮条的滑动照常可用", region.containsTrajectory(600, 400, 600, 2700, 8));
    assertTrue("扣除浮窗后仍有可点面积", region.hasUnexcludedArea(region.content()));

    var fullCover = new ScreenWindowRegion.Bounds(0, 0, 1260, 2800);
    var full = MainScreenWindowPolicy.ownFallbackExclusion(true, false, 43, fullCover, null);
    var covered =
        java.util.List.of(
            window(1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, 10, TARGET, null),
            window(43, MainScreenWindowPolicy.Kind.APPLICATION, false, false, 20, fullCover, full));
    ScreenWindowRegion coveredRegion = MainScreenWindowPolicy.region(0, covered);
    assertFalse("浮窗盖满目标时依然没有可点面积", coveredRegion.hasUnexcludedArea(coveredRegion.content()));
  }

  /** build203 真机形状：ZUI 居中 taskbar + 边缘游戏浮层都必须裁切，而不是整窗拒绝。 */
  @Test
  public void zuiCenteredTaskbarAndEdgeGameOverlayAreClippedNotObscured() {
    var region =
        regionOf(
            ZUI,
            chrome(
                9,
                20,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(125, 2530, 1475, 2560)),
            chrome(
                10,
                20,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 1771, 75, 1931)));
    assertFalse("ZUI taskbar/游戏浮层不得再整窗拒绝坐标", region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(75, 0, 1600, 2530), region.content());
    assertTrue("裁切后仍有可点面积", region.hasUnexcludedArea(region.content()));
    assertTrue("被裁切的装饰窗不是遮挡者", region.occluders().isEmpty());
  }

  /** 居中 taskbar 单独出现时也只裁底边（覆盖 84% 宽，≥80% 阈值）。 */
  @Test
  public void centeredTaskbarClipsOnlyTheBottomEdge() {
    var region =
        regionOf(
            ZUI,
            chrome(
                9,
                20,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(125, 2530, 1475, 2560)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(0, 0, 1600, 2530), region.content());
  }

  /** 非整高侧滑条（84..2338）按左/右边裁切，覆盖率 93.6% 走完整边规则。 */
  @Test
  public void nonFullHeightSideBarsClipTheirOwnEdges() {
    var region =
        regionOf(
            PHONE,
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                3,
                13,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 2338, 1080, 2408)),
            chrome(
                4,
                14,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 84, 72, 2338)),
            chrome(
                5,
                14,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(1008, 84, 1080, 2338)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(72, 84, 1008, 2338), region.content());
  }

  /** P1-2：同一几何形状只改 layer/列表顺序，结果必须完全一致。 */
  @Test
  public void sameShapeDifferentLayerOrListOrderKeepsTheSameRegion() {
    var sidesAlert =
        regionOf(
            PHONE,
            chrome(
                4,
                14,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 84, 72, 2338)),
            chrome(
                5,
                14,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(1008, 84, 1080, 2338)),
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                3,
                13,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 2338, 1080, 2408)));
    var sidesBelow =
        regionOf(
            PHONE,
            chrome(
                2,
                14,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                3,
                13,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 2338, 1080, 2408)),
            chrome(
                4,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 84, 72, 2338)),
            chrome(
                5,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(1008, 84, 1080, 2338)));
    var expected = new ScreenWindowRegion.Bounds(72, 84, 1008, 2338);
    assertFalse(sidesAlert.obscured());
    assertFalse(sidesBelow.obscured());
    assertEquals("侧滑条层级高于系统栏时不得翻转成遮挡", expected, sidesAlert.content());
    assertEquals("侧滑条层级低于系统栏时结果相同", expected, sidesBelow.content());
    assertEquals(sidesAlert.occluders(), sidesBelow.occluders());
  }

  /** 低于状态栏高度的短侧滑条走“贴边窄条”规则，仍只裁自己那条边。 */
  @Test
  public void shortEdgeOverlayClipsOnlyItsOwnEdge() {
    var region =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 600, 40, 1800)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(40, 0, 1080, 2408), region.content());
  }

  /** 全宽状态栏/导航栏（标准 AOSP 形状）继续正确裁切。 */
  @Test
  public void fullWidthStatusAndNavigationBarsStillClip() {
    var region =
        regionOf(
            PHONE,
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                3,
                13,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 2338, 1080, 2408)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(0, 84, 1080, 2338), region.content());
  }

  /** 1–2px 取整内缩仍算贴同一条边（容差规则）。 */
  @Test
  public void onePixelInsetSystemBarsStillCountAsAttached() {
    var region =
        regionOf(
            PHONE,
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 1, 1080, 85)),
            chrome(
                3,
                13,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 2337, 1080, 2407)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(0, 85, 1080, 2337), region.content());
  }

  /** 停靠底边但非全宽的浮动输入法按底边裁切。 */
  @Test
  public void dockedFloatingImeClipsTheBottomEdge() {
    var region =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.INPUT_METHOD,
                new ScreenWindowRegion.Bounds(100, 1800, 980, 2408)));
    assertFalse(region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(0, 0, 1080, 1800), region.content());
  }

  /** 完全浮在中间、不贴任何边的输入法仍是内部覆盖：整窗拒绝坐标。 */
  @Test
  public void interiorFloatingImeStillRefusesCoordinates() {
    var region =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.INPUT_METHOD,
                new ScreenWindowRegion.Bounds(100, 800, 980, 1800)));
    assertTrue("内部覆盖保持整窗拒绝", region.obscured());
    assertEquals(1, region.occluders().size());
    assertEquals("INPUT_METHOD", region.occluders().get(0).kind());
    assertFalse(region.hasUnexcludedArea(region.content()));
  }

  /** 第三方无障碍浮层（UNSUPPORTED）内部覆盖仍整窗拒绝；部分遮挡降级需用户拍板。 */
  @Test
  public void thirdPartyAccessibilityOverlayStillRefusesTheWholeWindow() {
    var interior =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.UNSUPPORTED,
                new ScreenWindowRegion.Bounds(800, 300, 1000, 500)));
    assertTrue("内部第三方浮层不得授权坐标", interior.obscured());
    assertEquals("UNSUPPORTED", interior.occluders().get(0).kind());

    var edge =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.UNSUPPORTED,
                new ScreenWindowRegion.Bounds(0, 300, 200, 500)));
    assertTrue("第三方浮层不享受系统装饰的贴边裁切", edge.obscured());
  }

  /** 全屏系统窗把可用区域吃光时仍整窗拒绝，并带上可定位的遮挡者。 */
  @Test
  public void fullScreenSystemChromeStillRefusesTheWholeWindow() {
    var region =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 2408)));
    assertTrue(region.obscured());
    assertEquals(PHONE, region.content());
    assertEquals(1, region.occluders().size());
    assertEquals(9, region.occluders().get(0).windowId());
  }

  /** 已被同侧裁掉的区域里的内部窗口不算遮挡（判定对最终可用区域，而不是原始目标）。 */
  @Test
  public void interiorCoverInsideAnAlreadyClippedEdgeIsNotAnOccluder() {
    var region =
        regionOf(
            PHONE,
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.UNSUPPORTED,
                new ScreenWindowRegion.Bounds(100, 20, 200, 60)));
    assertFalse("位于裁掉的顶部条内的窗口不再覆盖可用区域", region.obscured());
    assertEquals(new ScreenWindowRegion.Bounds(0, 84, 1080, 2408), region.content());
  }

  /** 裁切后仍在可用区域里的内部窗口继续整窗拒绝。 */
  @Test
  public void interiorCoverInsideTheRemainingContentStillObscures() {
    var region =
        regionOf(
            PHONE,
            chrome(
                2,
                12,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(0, 0, 1080, 84)),
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.UNSUPPORTED,
                new ScreenWindowRegion.Bounds(500, 1000, 520, 1020)));
    assertTrue(region.obscured());
    assertEquals(1, region.occluders().size());
  }

  /** P2-1/#212：零面积、反向、null 与 1×1 平台窗不覆盖像素，任何 kind 都不触发遮挡。 */
  @Test
  public void tinyAndDegenerateWindowsAreSkipped() {
    for (var kind :
        new MainScreenWindowPolicy.Kind[] {
          MainScreenWindowPolicy.Kind.APPLICATION,
          MainScreenWindowPolicy.Kind.SYSTEM,
          MainScreenWindowPolicy.Kind.INPUT_METHOD,
          MainScreenWindowPolicy.Kind.UNSUPPORTED
        }) {
      for (var bounds :
          new ScreenWindowRegion.Bounds[] {
            new ScreenWindowRegion.Bounds(0, 0, 0, 0),
            new ScreenWindowRegion.Bounds(4, 4, 2, 2),
            new ScreenWindowRegion.Bounds(500, 500, 501, 501),
            new ScreenWindowRegion.Bounds(0, 0, 1, 1),
            null
          }) {
        var region = regionOf(PHONE, window(9, kind, false, false, 30, bounds, null));
        assertFalse("退化窗口不得整窗拒绝：" + kind + " " + bounds, region.obscured());
        assertEquals(PHONE, region.content());
        assertTrue("退化窗口不进遮挡诊断：" + kind + " " + bounds, region.occluders().isEmpty());
      }
    }
  }

  /** 2×2 内部覆盖仍必须拒绝（#212 的 1×1 豁免没有放宽到 2×2）。 */
  @Test
  public void twoByTwoInteriorCoverStillRefusesCoordinates() {
    var region =
        regionOf(
            PHONE,
            chrome(
                9,
                30,
                MainScreenWindowPolicy.Kind.SYSTEM,
                new ScreenWindowRegion.Bounds(500, 800, 502, 802)));
    assertTrue(region.obscured());
  }
}
