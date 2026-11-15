package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TerminalWorkspacePolicyTest {
  @Test
  public void pageBackNeverRepresentsProcessEnd() {
    assertEquals(
        TerminalWorkspacePolicy.Back.LEAVE_PAGE, TerminalWorkspacePolicy.back(false, false, false));
    assertEquals(
        TerminalWorkspacePolicy.Back.HIDE_IME, TerminalWorkspacePolicy.back(false, false, true));
    assertEquals(
        TerminalWorkspacePolicy.Back.LEAVE_SELECTION,
        TerminalWorkspacePolicy.back(false, true, true));
    assertEquals(
        TerminalWorkspacePolicy.Back.DISMISS_PANEL, TerminalWorkspacePolicy.back(true, true, true));
  }

  @Test
  public void inlineNeedsWideWindowAndKeepsTerminalWidth() {
    assertFalse(TerminalWorkspacePolicy.canInline(839));
    assertTrue(TerminalWorkspacePolicy.canInline(840));
    assertTrue(TerminalWorkspacePolicy.canInline(1024));
    assertEquals(240, TerminalWorkspacePolicy.inlineWidth(840));
    assertEquals(256, TerminalWorkspacePolicy.inlineWidth(1024));
    assertEquals(296, TerminalWorkspacePolicy.inlineWidth(1280));
    assertEquals(296, TerminalWorkspacePolicy.inlineWidth(1600));
    assertTrue(840 - TerminalWorkspacePolicy.inlineWidth(840) >= 480);
  }

  @Test
  public void drawerWidthUsesPhoneCapAndKeepsTabletSplitScreenWide() {
    // 手机类（smallestScreenWidthDp < 600）：竖屏/窄窗 224dp，宽窗回到 336dp。
    for (int widthDp : new int[] {360, 393, 411, 599}) {
      assertEquals(224, TerminalWorkspacePolicy.drawerMaxWidth(widthDp, 393));
    }
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(800, 393));
    // 平板类（smallestScreenWidthDp >= 600）：分屏窄窗与宽窗都保持 336dp。
    for (int widthDp : new int[] {500, 800, 840, 1000}) {
      assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(widthDp, 800));
    }
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(1000, 1000));
    // 393dp 手机窗口：224dp 抽屉仍让出 169dp，远大于 32dp 遮罩/返回手势下限。
    int phoneDrawer =
        TerminalWorkspacePolicy.drawerWidth(
            393, TerminalWorkspacePolicy.drawerMaxWidth(393, 393), 32);
    assertEquals(224, phoneDrawer);
    assertTrue(phoneDrawer <= 393 - 32);
    // 平板/宽窗的既有上限与遮罩边距语义保持不变。
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(1000, 336, 32));
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(368, 336, 32));
    assertEquals(288, TerminalWorkspacePolicy.drawerWidth(320, 336, 32));
    assertEquals(208, TerminalWorkspacePolicy.drawerWidth(240, 336, 32));
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(0, 336, 32));
    assertEquals(12, TerminalWorkspacePolicy.drawerWidth(16, 336, 32));
    // 更窄窗口仍由 32dp 遮罩边距继续收缩，不会因 224dp 上限溢出。
    assertEquals(192, TerminalWorkspacePolicy.drawerWidth(224, 224, 32));
    for (int width = 1; width <= 400; width++) {
      int drawer = TerminalWorkspacePolicy.drawerWidth(width, 336, 32);
      assertTrue(drawer > 0 && drawer <= width);
    }
  }

  @Test
  public void drawerCapBoundariesFollowDeviceClassAndWindowWidth() {
    // 手机类在 599dp 窗口仍收窄；窗口一到 600dp 即回到 336dp 上限。
    assertEquals(224, TerminalWorkspacePolicy.drawerMaxWidth(599, 599));
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(600, 599));
    // smallestScreenWidthDp 达到 600 即按平板类处理，即使当前窗口更窄。
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(599, 600));
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth(600, 600));
  }

  @Test
  public void narrowDrawerKeepsTwoFooterColumnsAndStackedRows() {
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(224, 1f, false));
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(224, 1f, true));
    assertTrue(TerminalWorkspacePolicy.stackSessionDetails(224, 1f));
  }

  @Test
  public void narrowActionsStartBelow240dpAndMatchDrawerAndFooterColumns() {
    // 239/240 边界：内联侧栏最小就是 240dp，回到 240dp 即恢复全标签。
    assertTrue(TerminalWorkspacePolicy.narrowActions(0));
    assertTrue(TerminalWorkspacePolicy.narrowActions(239));
    assertFalse(TerminalWorkspacePolicy.narrowActions(240));
    assertTrue(TerminalWorkspacePolicy.narrowActions(224));
    assertFalse(TerminalWorkspacePolicy.narrowActions(320));
    assertFalse(TerminalWorkspacePolicy.narrowActions(336));
    // 手机类 224dp 抽屉上限落在窄分支；599dp 窗口仍 224dp，600dp 起回到 336dp 全标签。
    assertTrue(
        TerminalWorkspacePolicy.narrowActions(TerminalWorkspacePolicy.drawerMaxWidth(393, 393)));
    assertTrue(
        TerminalWorkspacePolicy.narrowActions(TerminalWorkspacePolicy.drawerMaxWidth(599, 599)));
    assertFalse(
        TerminalWorkspacePolicy.narrowActions(TerminalWorkspacePolicy.drawerMaxWidth(600, 599)));
    assertFalse(
        TerminalWorkspacePolicy.narrowActions(TerminalWorkspacePolicy.drawerMaxWidth(800, 393)));
    int phoneDrawer =
        TerminalWorkspacePolicy.drawerWidth(
            393, TerminalWorkspacePolicy.drawerMaxWidth(393, 393), 32);
    assertEquals(224, phoneDrawer);
    assertTrue(TerminalWorkspacePolicy.narrowActions(phoneDrawer));
    // 窄分支在默认字体下仍是两列；大字体继续按实际宽度减列。
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(224, 1f, true));
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(239, 1f, true));
    assertEquals(1, TerminalWorkspacePolicy.footerColumns(224, 2f, true));
    // 与内联侧栏下限一致：240dp 内联侧栏不属于窄分支。
    assertEquals(240, TerminalWorkspacePolicy.inlineWidth(840));
    assertFalse(TerminalWorkspacePolicy.narrowActions(TerminalWorkspacePolicy.inlineWidth(840)));
    // 性质：判定只由宽度 < 240 决定，不受抽屉上限或字体缩放影响。
    for (int width = 0; width <= 500; width++) {
      assertEquals(width < 240, TerminalWorkspacePolicy.narrowActions(width));
    }
  }

  @Test
  public void shortWindowsSwitchToCompactAndDenseChrome() {
    assertTrue(TerminalWorkspacePolicy.compactHeight(559));
    assertFalse(TerminalWorkspacePolicy.compactHeight(560));
    assertTrue(TerminalWorkspacePolicy.denseFooter(479));
    assertFalse(TerminalWorkspacePolicy.denseFooter(480));
    assertTrue(TerminalWorkspacePolicy.shortLandscape(1422, 889));
    assertTrue(TerminalWorkspacePolicy.shortLandscape(800, 400));
    assertFalse(TerminalWorkspacePolicy.shortLandscape(800, 889));
    assertFalse(TerminalWorkspacePolicy.shortLandscape(1600, 1200));
  }

  @Test
  public void actionColumnsFollowPanelWidthAndFontScale() {
    assertEquals(3, TerminalWorkspacePolicy.footerColumns(336, 1f, true));
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(240, 1f, true));
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(336, 1.3f, true));
    assertEquals(1, TerminalWorkspacePolicy.footerColumns(336, 2f, true));
    assertEquals(1, TerminalWorkspacePolicy.footerColumns(208, 1f, false));
    assertEquals(2, TerminalWorkspacePolicy.footerColumns(336, Float.NaN, false));
    assertTrue(TerminalWorkspacePolicy.stackSessionDetails(240, 1f));
    assertTrue(TerminalWorkspacePolicy.stackSessionDetails(336, 1.3f));
    assertFalse(TerminalWorkspacePolicy.stackSessionDetails(336, 1f));
    assertTrue(TerminalWorkspacePolicy.compactHeight(700, 1.5f));
    assertTrue(TerminalWorkspacePolicy.denseFooter(700, 1.5f));
  }

  @Test
  public void measuredChromeCannotConsumeTheSessionViewport() {
    int minimum = TerminalWorkspacePolicy.minimumSessionHeight(1f);
    assertFalse(TerminalWorkspacePolicy.scrollWholeSidebar(640, 320, minimum));
    assertFalse(TerminalWorkspacePolicy.scrollWholeSidebar(432, 320, minimum));
    assertTrue(TerminalWorkspacePolicy.scrollWholeSidebar(431, 320, minimum));
    assertTrue(TerminalWorkspacePolicy.scrollWholeSidebar(160, 320, minimum));
    assertFalse(TerminalWorkspacePolicy.scrollWholeSidebar(0, 320, minimum));
    assertTrue(
        TerminalWorkspacePolicy.scrollWholeSidebar(
            640, 420, TerminalWorkspacePolicy.minimumSessionHeight(2f)));
    assertTrue(TerminalWorkspacePolicy.scrollWholeSidebar(100, Integer.MAX_VALUE, minimum));
  }

  @Test
  public void processEpochPreventsOldIdReclaimingNewSession() {
    assertFalse(TerminalWorkspacePolicy.restoreSelection("old", "new", 1, true));
    assertFalse(TerminalWorkspacePolicy.restoreSelection(null, "new", 1, true));
    assertFalse(TerminalWorkspacePolicy.restoreSelection("new", "new", 1, false));
    assertFalse(TerminalWorkspacePolicy.restoreSelection("new", "new", 0, true));
    assertTrue(TerminalWorkspacePolicy.restoreSelection("new", "new", 1, true));
  }

  @Test
  public void floatingMenuNeverLeavesShortKeyboardWindow() {
    for (int height : new int[] {80, 160, 320, 640}) {
      for (float fraction : new float[] {-1, 0, .6f, 1, 4, Float.NaN}) {
        int y = TerminalWorkspacePolicy.ballY(height, 48, 12, 100, fraction);
        assertTrue(y >= 0);
        assertTrue(y + 48 <= height);
      }
    }
  }

  @Test
  public void ballUsesWholeTouchTargetAndClampsInvalidSavedState() {
    assertEquals(12, TerminalWorkspacePolicy.ballX(360, 48, 12, false));
    assertEquals(300, TerminalWorkspacePolicy.ballX(360, 48, 12, true));
    assertEquals(.6f, TerminalWorkspacePolicy.fraction(Float.NaN), 0f);
    assertEquals(0f, TerminalWorkspacePolicy.fraction(-3), 0f);
    assertEquals(1f, TerminalWorkspacePolicy.fraction(3), 0f);
  }
}
