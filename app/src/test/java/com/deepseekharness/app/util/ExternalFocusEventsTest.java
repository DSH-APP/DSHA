package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class ExternalFocusEventsTest {
  @Test
  public void parsesAndroid16WindowManagerResumeEvents() {
    String output =
        "         1794256619.583  3647  5316 I wm_resume_activity:"
            + " [0,142211930,2,com.android.launcher/.Launcher]\n"
            + "         1794256621.292  3647  5316 I wm_set_resumed_activity:"
            + " [0,io.github.sunsetrne.dsha/com.deepseekharness.app.ui.MainActivity,resumeTop]";
    List<ExternalFocusEvents.Event> events = ExternalFocusEvents.parse(output);
    assertEquals(2, events.size());
    ExternalFocusEvents.Snapshot latest = ExternalFocusEvents.latest(events);
    assertNotNull(latest);
    assertEquals(1794256621292L, latest.deviceTimeMs());
    assertEquals("io.github.sunsetrne.dsha", latest.packageName());
    assertEquals("com.deepseekharness.app.ui.MainActivity", latest.activity());
    assertEquals(
        "io.github.sunsetrne.dsha/com.deepseekharness.app.ui.MainActivity", latest.component());
  }

  @Test
  public void canonicalizesShortAndBareActivityNames() {
    List<ExternalFocusEvents.Event> events =
        ExternalFocusEvents.parse(
            "05-12 12:34:56.789  1234  1234 I am_focused_activity:"
                + " [0,com.android.settings/.Settings]\n"
                + "05-12 12:34:57.000  1234  1234 I am_resume_activity:"
                + " [0,com.android.launcher/Launcher]");
    assertEquals(2, events.size());
    assertEquals("com.android.settings.Settings", events.get(0).activity());
    assertEquals("com.android.launcher.Launcher", events.get(1).activity());
  }

  @Test
  public void acceptsBriefTimeFormatAndIgnoresUnrelatedTags() {
    String output =
        "05-12 12:34:56.789 I/am_focused_activity( 1234): [0,com.android.settings/.Settings]\n"
            + "         1794256619.583  3647  5316 I netstats_wifi_sample: [0,0,0]\n"
            + "         1794256619.583  3647  5316 I wm_focused_root_task: [0,0,20279,1,x]\n"
            + "05-12 12:34:57.100 I/ActivityManager( 1234): unrelated payload";
    List<ExternalFocusEvents.Event> events = ExternalFocusEvents.parse(output);
    assertEquals(1, events.size());
    assertEquals(-1L, events.get(0).deviceTimeMs());
    assertEquals("com.android.settings.Settings", events.get(0).activity());
  }

  @Test
  public void latestUsesDeviceTimeAndStaysDeterministic() {
    ExternalFocusEvents.Event older =
        new ExternalFocusEvents.Event(1000L, "wm_resume_activity", "a.b", "a.b.A");
    ExternalFocusEvents.Event newer =
        new ExternalFocusEvents.Event(2000L, "wm_resume_activity", "c.d", "c.d.C");
    assertEquals("c.d/c.d.C", ExternalFocusEvents.latest(List.of(newer, older)).component());
    assertEquals("c.d/c.d.C", ExternalFocusEvents.latest(List.of(older, newer)).component());
    assertNull(ExternalFocusEvents.latest(List.of()));
    assertNull(ExternalFocusEvents.latest(null));
  }

  @Test
  public void parsesCurrentFocusAndFocusedAppFromWindowDump() {
    String dump =
        "  mCurrentFocus=Window{3174fbc u0"
            + " io.github.sunsetrne.dsha/com.deepseekharness.app.ui.MainActivity}\n"
            + "  mFocusedApp=ActivityRecord{95928561 u0"
            + " io.github.sunsetrne.dsha/com.deepseekharness.app.ui.MainActivity t20279}";
    ExternalFocusEvents.Snapshot snapshot = ExternalFocusEvents.parseWindowDump(dump);
    assertNotNull(snapshot);
    assertEquals(
        "io.github.sunsetrne.dsha/com.deepseekharness.app.ui.MainActivity", snapshot.component());
    assertNull(ExternalFocusEvents.parseWindowDump("  mCurrentFocus=null\n"));
    assertNull(ExternalFocusEvents.parseWindowDump(""));
  }

  @Test
  public void fallsBackToFocusedAppWhenCurrentFocusHasNoComponent() {
    String dump =
        "  mCurrentFocus=Window{5a1 u0 NotificationShade}\n"
            + "  mFocusedApp=ActivityRecord{5a2 u0 com.android.launcher/.Launcher t2}";
    ExternalFocusEvents.Snapshot snapshot = ExternalFocusEvents.parseWindowDump(dump);
    assertNotNull(snapshot);
    assertEquals("com.android.launcher/com.android.launcher.Launcher", snapshot.component());
  }

  @Test
  public void digestIsStableTargetBoundAndCarriesNoScreenText() {
    String first = ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Page");
    assertEquals(first, ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Page"));
    assertTrue(first.matches("[0-9a-f]{32}"));
    assertFalse(first.contains("a.b"));
    assertFalse(first.equals(ExternalFocusEvents.digest(8L, "events", "a.b", "a.b.Page")));
    assertFalse(first.equals(ExternalFocusEvents.digest(7L, "window", "a.b", "a.b.Page")));
    assertFalse(first.equals(ExternalFocusEvents.digest(7L, "events", "a.b", "a.b.Other")));
  }
}
