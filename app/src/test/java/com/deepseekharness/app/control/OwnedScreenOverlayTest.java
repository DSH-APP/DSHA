package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.MainScreenWindowPolicy;
import com.deepseekharness.app.util.ScreenWindowRegion;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public final class OwnedScreenOverlayTest {
  private final ScreenWindowRegion.Bounds full = new ScreenWindowRegion.Bounds(0, 0, 100, 200);
  private final ScreenWindowRegion.Bounds compact = new ScreenWindowRegion.Bounds(70, 80, 95, 120);

  @After
  public void clear() {
    OwnedScreenOverlay.clear("test");
  }

  @Test
  public void onlyExactNativeWindowPackageAndGeometryCanBeExcluded() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    assertNotNull(OwnedScreenOverlay.match(7, compact, true));
    assertNull(OwnedScreenOverlay.match(8, compact, true));
    assertNull(OwnedScreenOverlay.match(7, compact, false));
    assertNull(OwnedScreenOverlay.match(7, new ScreenWindowRegion.Bounds(71, 80, 96, 120), true));
    var decor = new ScreenWindowRegion.Bounds(68, 78, 97, 122);
    assertEquals(decor, OwnedScreenOverlay.match(7, decor, true).bounds());
    OwnedScreenOverlay.publish("test", 7, compact, true);
    assertNull(OwnedScreenOverlay.match(7, compact, true));
    assertTrue(OwnedScreenOverlay.expanded());
  }

  @Test
  public void paddedWindowIsEntirelyMaskedAndNoPackageWideExemptionExists() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    var decor = new ScreenWindowRegion.Bounds(68, 78, 97, 122);
    var own = OwnedScreenOverlay.match(7, decor, true);
    assertNotNull(own);
    assertNull(OwnedScreenOverlay.match(8, decor, true));
    assertNull(OwnedScreenOverlay.match(7, decor, false));
    var region = new ScreenWindowRegion(full, full, false, List.of(own));
    assertFalse(region.containsTrajectory(68, 80, 68, 80, 0));
    assertTrue(region.hasUnexcludedArea(full));
    assertFalse(region.hasUnexcludedArea(compact));
    assertFalse(region.hasUnexcludedArea(decor));
    var covered =
        new ScreenWindowRegion(
            full, full, false, List.of(new ScreenWindowRegion.Exclusion(7, own.epoch(), full)));
    assertFalse(covered.hasUnexcludedArea(full));
    OwnedScreenOverlay.clear("test");
    assertNull(OwnedScreenOverlay.match(7, decor, true));
  }

  @Test
  public void selfOverlayPermitsOtherPixelsButNoTouchPathThroughIt() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    var own = OwnedScreenOverlay.match(7, compact, true);
    var app =
        new MainScreenWindowPolicy.Window(
            0, 2, 1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, full);
    var overlay =
        new MainScreenWindowPolicy.Window(
            0, 7, 4, MainScreenWindowPolicy.Kind.UNSUPPORTED, false, false, compact, own);
    var region = MainScreenWindowPolicy.region(0, List.of(app, overlay));
    assertFalse(region.obscured());
    assertEquals(1, region.exclusions().size());
    assertTrue(region.containsTrajectory(20, 20, 20, 160, 1));
    assertFalse(region.containsTrajectory(80, 90, 80, 90, 1));
    assertFalse(region.containsTrajectory(40, 100, 99, 100, 1));
    assertFalse(region.containsTrajectory(69, 90, 69, 90, 1));
    var foreign =
        new MainScreenWindowPolicy.Window(
            0, 8, 5, MainScreenWindowPolicy.Kind.UNSUPPORTED, false, false, compact);
    assertTrue(MainScreenWindowPolicy.region(0, List.of(app, overlay, foreign)).obscured());
  }

  @Test
  public void overlayMoveAndRecreationInvalidateOldObservationEvenAtSameBounds() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    var first = OwnedScreenOverlay.match(7, compact, true);
    OwnedScreenOverlay.clear("test");
    OwnedScreenOverlay.publish("test", 7, compact, false);
    assertNotEquals(first, OwnedScreenOverlay.match(7, compact, true));
  }

  @Test
  public void semanticScrollVisibilitySubtractsUnionOfOwnedInputAreas() {
    var left = new ScreenWindowRegion.Exclusion(7, 1, new ScreenWindowRegion.Bounds(0, 0, 50, 200));
    var right =
        new ScreenWindowRegion.Exclusion(8, 2, new ScreenWindowRegion.Bounds(50, 0, 100, 200));
    var covered = new ScreenWindowRegion(full, full, false, List.of(left, right));
    assertFalse(covered.hasUnexcludedArea(full));
    var gap =
        new ScreenWindowRegion(
            full,
            full,
            false,
            List.of(
                left,
                new ScreenWindowRegion.Exclusion(
                    8, 2, new ScreenWindowRegion.Bounds(51, 0, 100, 200))));
    assertTrue(gap.hasUnexcludedArea(full));
    assertFalse(gap.hasUnexcludedArea(new ScreenWindowRegion.Bounds(0, 0, 40, 200)));
    assertFalse(gap.hasUnexcludedArea(new ScreenWindowRegion.Bounds(-20, 0, 0, 200)));
    assertFalse(new ScreenWindowRegion(full, full, true).hasUnexcludedArea(full));
  }
}
