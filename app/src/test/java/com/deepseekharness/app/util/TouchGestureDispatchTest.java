package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TouchGestureDispatchTest {
  static class Trace implements TouchGestureDispatch.Sink {
    final List<String> events = new ArrayList<>();
    int fail = -1;

    public void stream(int action, float x, float y) {
      events.add("stream:" + action);
      if (action == fail) throw new IllegalStateException("UNKNOWN");
    }

    public void input(float x1, float y1, float x2, float y2, int duration, boolean tap) {
      events.add(tap ? "tap" : "swipe");
    }
  }

  final float[] from = {10, 20}, to = {40, 50};

  @Test
  public void completedStreamNeverEmitsDiscreteTapOrSwipe() {
    for (boolean tap : new boolean[] {true, false}) {
      var flow = new TouchGestureDispatch();
      var trace = new Trace();
      flow.down(trace, true, true, from);
      flow.move(to);
      assertTrue(flow.up(to, 100, tap));
      assertEquals(List.of("stream:0", "stream:2", "stream:1"), trace.events);
      assertFalse(flow.up(to, 100, tap));
      assertEquals(3, trace.events.size());
    }
  }

  @Test
  public void explicitUnavailableStreamChoosesOneDiscreteFallbackBeforeAnySend() {
    var flow = new TouchGestureDispatch();
    var trace = new Trace();
    flow.down(trace, false, true, from);
    flow.move(to);
    assertTrue(flow.up(to, 500, false));
    assertEquals(List.of("swipe"), trace.events);
  }

  @Test
  public void unknownDownMoveOrUpResultNeverReplaysGesture() {
    for (int fail : new int[] {0, 2, 1}) {
      var flow = new TouchGestureDispatch();
      var trace = new Trace();
      trace.fail = fail;
      flow.down(trace, true, true, from);
      flow.move(to);
      flow.up(to, 100, true);
      flow.up(to, 100, true);
      assertFalse(trace.events.contains("tap"));
      assertFalse(trace.events.contains("swipe"));
      assertEquals(1, Collections.frequency(trace.events, "stream:0"));
      assertTrue(trace.events.contains("stream:3"));
    }
  }

  @Test
  public void cancelOrPinchConsumesOldGestureEvenWhenFingerEndsValidly() {
    var flow = new TouchGestureDispatch();
    var trace = new Trace();
    flow.down(trace, true, true, from);
    flow.cancel();
    flow.cancel();
    assertFalse(flow.up(to, 100, true));
    assertEquals(List.of("stream:0", "stream:3"), trace.events);
  }

  @Test
  public void invalidBlackBorderPointsDoNotSendUnmatchedUpAndCancelActiveStroke() {
    var flow = new TouchGestureDispatch();
    var trace = new Trace();
    flow.down(trace, true, true, null);
    assertFalse(flow.up(to, 100, true));
    assertTrue(trace.events.isEmpty());
    flow.down(trace, true, true, from);
    flow.move(null);
    assertFalse(flow.up(to, 100, false));
    assertEquals(List.of("stream:0", "stream:3"), trace.events);
  }

  @Test
  public void panDisabledInputAndChangedListenerDoNotRetargetTheGesture() {
    var flow = new TouchGestureDispatch();
    Trace old = new Trace(), newListener = new Trace();
    flow.down(old, true, false, from);
    assertFalse(flow.up(to, 100, true));
    assertTrue(old.events.isEmpty());
    flow.down(old, true, true, from);
    flow.down(newListener, false, true, from);
    assertTrue(flow.up(to, 100, true));
    assertEquals(List.of("stream:0", "stream:3"), old.events);
    assertEquals(List.of("tap"), newListener.events);
  }
}
