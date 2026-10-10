package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenStrokeTest {
  private static final int DISPLAY = 7;

  @Test
  public void quickStillGestureIsATap() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 100f, 200f, 1000);
    VirtualScreenStroke.Release release = stroke.up("s1", 100f, 200f, 1100);
    assertTrue(release.replays());
    assertArrayEquals(
        new String[] {"/system/bin/input", "-d", "7", "tap", "100.0", "200.0"}, release.argv);
    assertFalse(stroke.active());
  }

  @Test
  public void longPressIsReplayedAsASamePointSwipe() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 100f, 200f, 1000);
    VirtualScreenStroke.Release release = stroke.up("s1", 100f, 200f, 2500);
    assertArrayEquals(
        new String[] {
          "/system/bin/input", "-d", "7", "swipe", "100.0", "200.0", "100.0", "200.0", "1500"
        },
        release.argv);
    assertEquals(1500, release.durationMs);
  }

  @Test
  public void smallJitterLongPressStaysAPress() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 100f, 200f, 0);
    // 1px 抖动 + 800ms 按住：仍算长按，不能退化成瞬时点击
    VirtualScreenStroke.Release release = stroke.up("s1", 101f, 200f, 800);
    assertArrayEquals(
        new String[] {
          "/system/bin/input", "-d", "7", "swipe", "100.0", "200.0", "101.0", "200.0", "800"
        },
        release.argv);
  }

  @Test
  public void dragReplaysFromTheStartPointToTheEndPoint() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 10f, 20f, 0);
    assertTrue(stroke.move("s1", 40f, 60f, 100));
    VirtualScreenStroke.Release release = stroke.up("s1", 90f, 120f, 300);
    assertArrayEquals(
        new String[] {
          "/system/bin/input", "-d", "7", "swipe", "10.0", "20.0", "90.0", "120.0", "300"
        },
        release.argv);
  }

  @Test
  public void cancelNeverReplaysAGesture() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 100f, 200f, 0);
    assertTrue(stroke.cancel("s1"));
    assertFalse(stroke.active());
    // 取消之后再来的抬起不能补出一条手势（取消不是抬起）
    assertFalse(stroke.up("s1", 0f, 0f, 50).replays());
    // 没有手势时取消不算「丢弃了一个手势」
    assertFalse(stroke.cancel("s1"));
  }

  @Test
  public void mismatchedStrokeIdIsIgnoredAndKeepsTheStrokeOpen() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 100f, 200f, 0);
    assertFalse(stroke.move("other", 5f, 5f, 10));
    assertFalse(stroke.up("other", 5f, 5f, 20).replays());
    assertTrue(stroke.active());
    assertEquals("s1", stroke.id());
    assertFalse(stroke.cancel("other"));
    assertTrue(stroke.active());
  }

  @Test
  public void duplicateDownStartsAFreshGesture() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 10f, 10f, 0);
    stroke.down("s2", 300f, 400f, 100);
    assertEquals("s2", stroke.id());
    VirtualScreenStroke.Release release = stroke.up("s2", 300f, 400f, 150);
    assertArrayEquals(
        new String[] {"/system/bin/input", "-d", "7", "tap", "300.0", "400.0"}, release.argv);
  }

  @Test
  public void staleStrokeIsDroppedByTheWatchdog() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 10f, 10f, 0);
    assertFalse(stroke.expire(VirtualScreenStroke.STALE_MS));
    assertTrue(stroke.active());
    assertTrue(stroke.expire(VirtualScreenStroke.STALE_MS + 1));
    assertFalse(stroke.active());
    // 悬空之后同样不允许补手势
    assertFalse(stroke.up("s1", 10f, 10f, 4000).replays());
  }

  @Test
  public void movementRefreshesTheStaleDeadline() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 10f, 10f, 0);
    assertTrue(stroke.move("s1", 20f, 20f, 2500));
    assertFalse(stroke.expire(3000));
    assertTrue(stroke.expire(5600));
  }

  @Test
  public void exposesLastKnownPositionForReflectionCancel() {
    VirtualScreenStroke stroke = new VirtualScreenStroke(DISPLAY);
    stroke.down("s1", 10f, 20f, 100);
    assertTrue(stroke.move("s1", 30f, 40f, 200));
    assertEquals(30f, stroke.lastX(), 0.001f);
    assertEquals(40f, stroke.lastY(), 0.001f);
    assertEquals(100, stroke.downAt());
    stroke.cancel("s1");
    assertEquals("", stroke.id());
  }
}
