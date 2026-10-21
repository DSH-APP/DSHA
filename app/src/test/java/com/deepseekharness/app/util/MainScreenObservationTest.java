package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Deterministic policy/pixel evidence only; not Android window, screenshot or gesture execution. */
public final class MainScreenObservationTest {
  private static final ScreenWindowRegion.Bounds FULL = new ScreenWindowRegion.Bounds(0, 0, 6, 8);

  private static MainScreenWindowPolicy.Window window(
      int id,
      int layer,
      MainScreenWindowPolicy.Kind kind,
      boolean focused,
      boolean active,
      ScreenWindowRegion.Bounds bounds) {
    return new MainScreenWindowPolicy.Window(0, id, layer, kind, focused, active, bounds);
  }

  private static MainScreenWindowPolicy.Window app() {
    return window(7, 1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, FULL);
  }

  @Test
  public void noBackgroundFallbackAndRealForegroundSystemIsDistinct() {
    assertEquals(
        -1,
        MainScreenWindowPolicy.select(
            List.of(window(7, 1, MainScreenWindowPolicy.Kind.APPLICATION, false, false, FULL))));
    var system = window(8, 5, MainScreenWindowPolicy.Kind.SYSTEM, true, true, FULL);
    assertEquals(1, MainScreenWindowPolicy.select(List.of(app(), system)));
    var unknownGeometry =
        List.of(
            window(
                8,
                5,
                MainScreenWindowPolicy.Kind.SYSTEM,
                true,
                true,
                new ScreenWindowRegion.Bounds(0, 0, 0, 0)));
    assertEquals(0, MainScreenWindowPolicy.select(unknownGeometry));
    assertNull(MainScreenWindowPolicy.region(0, unknownGeometry));
    assertEquals(
        -1,
        MainScreenWindowPolicy.select(
            List.of(
                app(), window(8, 5, MainScreenWindowPolicy.Kind.UNSUPPORTED, true, true, FULL))));
  }

  @Test
  public void ambiguityAndOtherDisplaysCannotElectAPhysicalTarget() {
    assertEquals(
        -1,
        MainScreenWindowPolicy.select(
            List.of(
                app(), window(8, 1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, FULL))));
    assertEquals(
        -1,
        MainScreenWindowPolicy.select(
            List.of(
                new MainScreenWindowPolicy.Window(
                    3, 8, 4, MainScreenWindowPolicy.Kind.APPLICATION, true, true, FULL))));
  }

  @Test
  public void chromeAndImeClipOnlyCompleteEdgesOfTheSameObservedWindow() {
    var windows =
        List.of(
            app(),
            window(
                8,
                8,
                MainScreenWindowPolicy.Kind.SYSTEM,
                false,
                false,
                new ScreenWindowRegion.Bounds(0, 0, 6, 1)),
            window(
                9,
                8,
                MainScreenWindowPolicy.Kind.SYSTEM,
                false,
                false,
                new ScreenWindowRegion.Bounds(0, 7, 6, 8)),
            window(
                10,
                9,
                MainScreenWindowPolicy.Kind.INPUT_METHOD,
                false,
                true,
                new ScreenWindowRegion.Bounds(0, 5, 6, 7)));
    assertEquals(0, MainScreenWindowPolicy.select(windows));
    var region = MainScreenWindowPolicy.region(0, windows);
    assertEquals(FULL, region.window());
    assertEquals(new ScreenWindowRegion.Bounds(0, 1, 6, 5), region.content());
    assertFalse(region.obscured());
  }

  @Test
  public void pipAndInteriorOverlayNeverAuthorizeCoordinatesEvenWithIdenticalPixels() {
    for (var kind :
        new MainScreenWindowPolicy.Kind[] {
          MainScreenWindowPolicy.Kind.APPLICATION,
          MainScreenWindowPolicy.Kind.SYSTEM,
          MainScreenWindowPolicy.Kind.UNSUPPORTED
        }) {
      var region =
          MainScreenWindowPolicy.region(
              0,
              List.of(
                  app(),
                  window(8, 9, kind, false, false, new ScreenWindowRegion.Bounds(2, 2, 4, 4))));
      assertTrue(region.obscured());
      assertFalse(region.containsTrajectory(1, 1, 4, 6, 1));
      for (var unknownBounds :
          new ScreenWindowRegion.Bounds[] {
            new ScreenWindowRegion.Bounds(0, 0, 0, 0),
            new ScreenWindowRegion.Bounds(4, 4, 2, 2),
            null
          }) {
        var unknown =
            MainScreenWindowPolicy.region(
                0, List.of(app(), window(8, 9, kind, false, false, unknownBounds)));
        assertTrue(unknown.obscured());
        assertFalse(unknown.containsTrajectory(1, 1, 4, 6, 1));
      }
    }
  }

  @Test
  public void externalStatusPixelsDoNotChangeOldRegionButAnyInternalArgbBitDoes() throws Exception {
    int[] pixels = new int[6 * 8];
    var region = new ScreenWindowRegion.Bounds(0, 1, 6, 7);
    String observed = hash(pixels, region);
    pixels[0] = 0xff123456;
    pixels[47] = 0xffabcdef;
    assertEquals(observed, hash(pixels, region));
    pixels[6] = 1;
    assertNotEquals(observed, hash(pixels, region));
    pixels[6] = 0x01000000;
    assertNotEquals(observed, hash(pixels, region));
  }

  @Test
  public void everyReadUsesOnlyTheOldExactRectangleNotTheLaterFrameSize() throws Exception {
    var region = new ScreenWindowRegion.Bounds(1, 2, 5, 6);
    java.util.ArrayList<Integer> rows = new java.util.ArrayList<>();
    ScreenPixelFingerprint.hash(
        6,
        8,
        region,
        (pixels, left, y, width) -> {
          assertEquals(1, left);
          assertEquals(4, width);
          assertEquals(4, pixels.length);
          rows.add(y);
        });
    assertEquals(List.of(2, 3, 4, 5), rows);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScreenPixelFingerprint.hash(
                4, 5, region, (pixels, left, y, width) -> fail("outside geometry was read")));
  }

  @Test
  public void oldWindowAndContentGeometryRotationAndImageSizeRemainIndependentChecks() {
    AtomicLong clock = new AtomicLong(100);
    var ledger = new ScreenObservationLedger(clock::get);
    var target = new ScreenTarget(0, 7, "example.app");
    var region = new ScreenWindowRegion(FULL, new ScreenWindowRegion.Bounds(0, 1, 6, 7), false);
    var observed = ledger.publish(target, 4, "run+consent", 6, 8, 0, "exact", region);
    clock.addAndGet(2_000);
    var consumed = ledger.consume(observed.id, target, 4, "run+consent");
    assertNotNull(consumed);
    assertTrue(consumed.matches(6, 8, 0, "exact", region));
    assertFalse(consumed.matches(6, 8, 1, "exact", region));
    assertFalse(consumed.matches(8, 6, 0, "exact", region));
    assertFalse(
        consumed.matches(
            6,
            8,
            0,
            "exact",
            new ScreenWindowRegion(FULL, new ScreenWindowRegion.Bounds(0, 2, 6, 7), false)));
    assertNull(ledger.consume(observed.id, target, 4, "run+consent"));
    var other = ledger.publish(target, 4, "run+consent", 6, 8, 0, "exact", region);
    assertNull(ledger.consume(other.id, target, 4, "another-consent"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.publish(target, 4, "run+consent", 6, 8, 0, "exact", null));
  }

  @Test
  public void straightTrajectoryAndOnePixelBoundaryReserveCannotEscapeObservedRegion() {
    var region = new ScreenWindowRegion(FULL, new ScreenWindowRegion.Bounds(0, 1, 6, 7), false);
    assertTrue(region.containsTrajectory(1, 2, 4, 5, 1));
    for (int[] path :
        new int[][] {{0, 2, 4, 5}, {1, 1, 4, 5}, {1, 2, 5, 5}, {1, 2, 4, 6}, {1, 2, 9, 5}})
      assertFalse(region.containsTrajectory(path[0], path[1], path[2], path[3], 1));
  }

  @Test
  public void enabledServiceIdentityIsExactAndShortClassesFollowAndroidExpansion() {
    String own = "com.dsh.client.experimental/com.deepseekharness.app.DshaAccessibilityService";
    assertTrue(AccessibilityEnableList.containsOwn(own, own));
    assertFalse(
        AccessibilityEnableList.containsOwn(
            "com.dsh.client.experimental/AccessibilityService", own));
    assertFalse(AccessibilityEnableList.containsOwn(own.toUpperCase(java.util.Locale.ROOT), own));
    assertFalse(
        AccessibilityEnableList.containsOwn(
            "com.other/com.deepseekharness.app.DshaAccessibilityService", own));
    assertTrue(
        AccessibilityEnableList.containsOwn(
            "com.example/.Service", "com.example/com.example.Service"));
  }

  private static String hash(int[] pixels, ScreenWindowRegion.Bounds region) throws Exception {
    return ScreenPixelFingerprint.hash(
        6,
        8,
        region,
        (row, left, y, width) -> System.arraycopy(pixels, y * 6 + left, row, 0, width));
  }
}
