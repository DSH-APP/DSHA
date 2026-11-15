package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.SheetGeometry.Mode;
import com.deepseekharness.app.util.SheetGeometry.Size;
import org.junit.Test;

public class SheetGeometryTest {
  // 基准档位的窗口尺寸（screenWidthDp × screenHeightDp，已扣系统栏），见 ui-template-baseline README §1。
  private static final int[] PHONE = {360, 736};
  private static final int[] PHONE_LAND = {780, 316};
  private static final int[] TABLET_PORT = {800, 1236};
  private static final int[] TABLET_LAND = {1280, 756};

  private static Mode mode(int[] s, Size size) {
    return SheetGeometry.mode(s[0], s[1], size);
  }

  @Test
  public void phonePortraitIsBottomSheetForEverySize() {
    for (Size size : Size.values()) assertEquals(Mode.BOTTOM, mode(PHONE, size));
    assertEquals(360, SheetGeometry.width(Mode.BOTTOM, Size.M, 360));
  }

  @Test
  public void tabletsCenterWithContentSizedWidth() {
    for (int[] s : new int[][] {TABLET_PORT, TABLET_LAND})
      for (Size size : Size.values()) assertEquals(Mode.CENTER, mode(s, size));
    assertEquals(400, SheetGeometry.width(Mode.CENTER, Size.S, 800));
    assertEquals(480, SheetGeometry.width(Mode.CENTER, Size.M, 800));
    assertEquals(640, SheetGeometry.width(Mode.CENTER, Size.L, 800));
    assertEquals(720, SheetGeometry.width(Mode.CENTER, Size.L, 1280));
  }

  @Test
  public void phoneLandscapeNoLongerGetsWideFlatCard() {
    // 旧实现：宽 ≥ 600 → 540 宽居中、高 ≤ 0.84 整屏，比例约 2:1。
    assertEquals(Mode.COMPACT, mode(PHONE_LAND, Size.S));
    assertEquals(Mode.COMPACT, mode(PHONE_LAND, Size.M));
    assertEquals(Mode.SIDE, mode(PHONE_LAND, Size.L));
    assertEquals(400, SheetGeometry.width(Mode.COMPACT, Size.S, 780));
    assertEquals(480, SheetGeometry.width(Mode.COMPACT, Size.M, 780));
    assertEquals(680, SheetGeometry.width(Mode.SIDE, Size.L, 780));
    assertEquals(292, SheetGeometry.maxHeight(Mode.COMPACT, 316, 0));
  }

  @Test
  public void shortNarrowWindowsStayCompactEvenForLongForms() {
    // 小屏横屏分屏等：宽不足 600 时没有空间分栏。
    assertEquals(Mode.COMPACT, SheetGeometry.mode(560, 320, Size.L));
    assertEquals(400, SheetGeometry.width(Mode.COMPACT, Size.L, 560));
    assertEquals(288, SheetGeometry.width(Mode.COMPACT, Size.M, 320));
  }

  @Test
  public void boundariesMatchInfoSurfaceAt600And480() {
    assertEquals(Mode.BOTTOM, SheetGeometry.mode(599, 900, Size.M));
    assertEquals(Mode.CENTER, SheetGeometry.mode(600, 900, Size.M));
    assertEquals(Mode.COMPACT, SheetGeometry.mode(900, 479, Size.M));
    assertEquals(Mode.CENTER, SheetGeometry.mode(900, 480, Size.M));
    assertEquals(InfoSurfacePolicy.TALL_MIN_HEIGHT, SheetGeometry.TALL_MIN_HEIGHT);
  }

  @Test
  public void unknownHeightKeepsWidthOnlyBehaviour() {
    assertEquals(Mode.BOTTOM, SheetGeometry.mode(360, 0, Size.L));
    assertEquals(Mode.CENTER, SheetGeometry.mode(800, 0, Size.L));
  }

  @Test
  public void bottomSheetHeightSubtractsLiftOnlyWhenGiven() {
    assertEquals(662, SheetGeometry.maxHeight(Mode.BOTTOM, 736, 0));
    assertEquals(596, SheetGeometry.maxHeight(Mode.BOTTOM, 736, 74));
    assertEquals(0, SheetGeometry.maxHeight(Mode.BOTTOM, 40, 74));
  }

  @Test
  public void actionDrawerCapsFirstScreenAt72PercentOfAvailableHeight() {
    // 动作抽屉（BottomSheetCard）单独用 0.72，不改变 CardSheet 贴底卡片的 0.9。
    assertEquals(530, SheetGeometry.bottomDrawerMaxHeight(736, 0));
    assertEquals(477, SheetGeometry.bottomDrawerMaxHeight(736, 74));
    assertEquals(0, SheetGeometry.bottomDrawerMaxHeight(40, 74));
    assertTrue(
        SheetGeometry.bottomDrawerMaxHeight(736, 0) < SheetGeometry.maxHeight(Mode.BOTTOM, 736, 0));
    assertEquals(0.72f, SheetGeometry.BOTTOM_DRAWER_HEIGHT_RATIO, 0f);
  }

  @Test
  public void centeredHeightIsCappedAt800() {
    assertEquals(676, SheetGeometry.maxHeight(Mode.CENTER, 756, 0));
    assertEquals(800, SheetGeometry.maxHeight(Mode.CENTER, 1236, 0));
  }

  @Test
  public void sideColumnTakesAboutAThird() {
    assertEquals(245, SheetGeometry.sideColumnWidth(680));
    assertEquals(0, SheetGeometry.sideColumnWidth(-5));
  }

  @Test
  public void onlyTwoButtonFootersGoInARowAndNeverInSideMode() {
    assertTrue(SheetGeometry.footerInRow(Mode.BOTTOM, 2));
    assertTrue(SheetGeometry.footerInRow(Mode.COMPACT, 2));
    assertTrue(SheetGeometry.footerInRow(Mode.CENTER, 2));
    assertFalse(SheetGeometry.footerInRow(Mode.SIDE, 2));
    assertFalse(SheetGeometry.footerInRow(Mode.CENTER, 1));
    assertFalse(SheetGeometry.footerInRow(Mode.CENTER, 3));
  }

  @Test
  public void plainDialogsNarrowButCustomViewsKeep560() {
    assertEquals(448, SheetGeometry.dialogWindowWidth(1280, false));
    assertEquals(560, SheetGeometry.dialogWindowWidth(1280, true));
    assertEquals(360, SheetGeometry.dialogWindowWidth(360, false));
    assertEquals(0, SheetGeometry.dialogWindowWidth(-1, true));
  }

  @Test
  public void widthsNeverNegativeOnTinyWindows() {
    for (Mode m : Mode.values())
      for (Size s : Size.values()) assertTrue(SheetGeometry.width(m, s, 10) >= 0);
    for (Mode m : Mode.values()) assertTrue(SheetGeometry.maxHeight(m, 10, 0) >= 0);
  }
}
