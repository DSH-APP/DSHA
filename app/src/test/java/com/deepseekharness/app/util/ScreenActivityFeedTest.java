package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** 内存环形、回执游标、history 分页与任务隔离；不依赖 Android 或真实等待。 */
public final class ScreenActivityFeedTest {
  private final AtomicLong now = new AtomicLong(1_000L);
  private final ScreenActivityFeed feed = new ScreenActivityFeed(now::get);

  private ScreenActivityEvent event(String packageName) {
    return new ScreenActivityEvent(
        0L,
        0L,
        ScreenActivityEvent.Kind.USER_TAP,
        ScreenActivityEvent.Source.USER,
        packageName,
        "",
        7L,
        "标签",
        "android.widget.Button",
        10,
        20,
        false,
        false,
        "");
  }

  @Test
  public void recordsOnlyWhileATaskIsActive() {
    assertNull(feed.record(event("a")));
    assertEquals(0, feed.size());

    assertTrue(feed.begin("task-a"));
    assertEquals(1L, feed.record(event("a")).sequence());
    assertEquals(1, feed.size());

    feed.clear();
    assertFalse(feed.recording());
    assertNull(feed.record(event("a")));
    assertEquals(0, feed.size());
  }

  @Test
  public void ringIsBoundedByCapacityAndWindow() {
    ScreenActivityFeed bounded = new ScreenActivityFeed(now::get, 4, 1_000L);
    bounded.begin("task-a");
    for (int index = 0; index < 6; index++) {
      now.set(1_000L + index);
      bounded.record(event("p" + index));
    }
    assertEquals(4, bounded.size());

    now.set(3_000L);
    assertEquals(0, bounded.size());
  }

  @Test
  public void recentReturnsTheNewestEventsOnceAndAdvancesTheCursor() {
    feed.begin("task-a");
    for (int index = 0; index < 5; index++) feed.record(event("p" + index));

    List<ScreenActivityEvent> first = feed.recent(3, "task-a");
    assertEquals(3, first.size());
    assertEquals(3L, first.get(0).sequence());
    assertEquals(5L, first.get(2).sequence());
    assertTrue(feed.recent(3, "task-a").isEmpty());

    feed.record(event("p5"));
    List<ScreenActivityEvent> second = feed.recent(3, "task-a");
    assertEquals(1, second.size());
    assertEquals(6L, second.get(0).sequence());
  }

  @Test
  public void aDifferentTaskNeverSeesOrClearsCurrentEvents() {
    feed.begin("task-a");
    feed.record(event("a"));
    assertTrue(feed.recent(3, "task-b").isEmpty());
    assertTrue(feed.history(0L, 20, "task-b").events().isEmpty());
    assertFalse(feed.history(0L, 20, "task-b").recording());
    assertEquals(1, feed.size());

    assertTrue(feed.begin("task-b"));
    assertEquals(0, feed.size());
    assertTrue(feed.recent(3, "task-a").isEmpty());
    assertTrue(feed.history(0L, 20, "task-a").events().isEmpty());
  }

  @Test
  public void historyPagesBySequenceWithTruncation() {
    feed.begin("task-a");
    for (int index = 0; index < 5; index++) feed.record(event("p" + index));

    ScreenActivityFeed.History first = feed.history(0L, 2, "task-a");
    assertTrue(first.recording());
    assertEquals(2, first.events().size());
    assertEquals(2L, first.nextSince());
    assertTrue(first.truncated());

    ScreenActivityFeed.History second = feed.history(first.nextSince(), 2, "task-a");
    assertEquals(2, second.events().size());
    assertEquals(4L, second.nextSince());
    assertTrue(second.truncated());

    ScreenActivityFeed.History last = feed.history(second.nextSince(), 2, "task-a");
    assertEquals(1, last.events().size());
    assertEquals(5L, last.nextSince());
    assertFalse(last.truncated());

    ScreenActivityFeed.History empty = feed.history(5L, 2, "task-a");
    assertTrue(empty.events().isEmpty());
    assertEquals(5L, empty.nextSince());
    assertFalse(empty.truncated());
  }

  @Test
  public void taskEndClearDropsEverythingAndStopsRecording() {
    feed.begin("task-a");
    feed.record(event("a"));
    feed.clear();

    assertFalse(feed.recording());
    assertEquals(0, feed.size());
    ScreenActivityFeed.History history = feed.history(0L, 20, "task-a");
    assertFalse(history.recording());
    assertTrue(history.events().isEmpty());
  }

  @Test
  public void repeatedBeginForTheSameTaskKeepsEventsAndStartTime() {
    feed.begin("task-a");
    feed.record(event("a"));
    long started = feed.startedAtMillis();
    now.set(5_000L);

    assertFalse(feed.begin("task-a"));
    assertEquals(1, feed.size());
    assertEquals(started, feed.startedAtMillis());
  }

  @Test
  public void constructorRejectsInvalidBounds() {
    try {
      new ScreenActivityFeed(now::get, 0, 1_000L);
      fail("zero capacity must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_ACTIVITY_CAPACITY_INVALID", expected.getMessage());
    }
    try {
      new ScreenActivityFeed(now::get, 1, 0L);
      fail("zero window must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_ACTIVITY_WINDOW_INVALID", expected.getMessage());
    }
  }
}
