package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class BrowserTaskPolicyTest {
  private static PreviewPageSession.Identity identity(long generation, String instance) {
    return new PreviewPageSession.Identity(generation, instance, "http://127.0.0.1:38557/?token=x");
  }

  @Test
  public void backgroundReturnRequiresTheSameLiveRuntimeAndEngine() {
    var current = identity(7, "a");
    assertTrue(BrowserTaskPolicy.resumeBackground(current, current, true, false, false, true));
    assertFalse(
        BrowserTaskPolicy.resumeBackground(current, identity(8, "a"), true, false, false, true));
    assertFalse(
        BrowserTaskPolicy.resumeBackground(current, identity(7, "b"), true, false, false, true));
    assertFalse(BrowserTaskPolicy.resumeBackground(current, null, true, false, false, true));
    assertFalse(BrowserTaskPolicy.resumeBackground(current, current, false, false, false, true));
    assertFalse(BrowserTaskPolicy.resumeBackground(current, current, true, true, false, true));
    assertFalse(BrowserTaskPolicy.resumeBackground(current, current, true, false, true, true));
    assertFalse(BrowserTaskPolicy.resumeBackground(current, current, true, false, false, false));
  }

  @Test
  public void repeatedLauncherDiscardsOnlyTheNewNonRootLauncherWrapper() {
    assertTrue(BrowserTaskPolicy.duplicateLauncher(false, false, true, true));
    assertFalse(BrowserTaskPolicy.duplicateLauncher(true, false, true, true));
    assertFalse(BrowserTaskPolicy.duplicateLauncher(false, true, true, true));
    assertFalse(BrowserTaskPolicy.duplicateLauncher(false, false, false, true));
    assertFalse(BrowserTaskPolicy.duplicateLauncher(false, false, true, false));
  }

  @Test
  public void nativeHomeNeverSelectsTheLiveBrowserEvenWithALegacyMainRoot() {
    assertEquals(
        4,
        BrowserTaskPolicy.nativeTask(
            List.of(new BrowserTaskPolicy.Task(9, true), new BrowserTaskPolicy.Task(4, true)),
            9,
            9));
    assertEquals(
        -1, BrowserTaskPolicy.nativeTask(List.of(new BrowserTaskPolicy.Task(9, true)), 9, -1));
  }

  @Test
  public void existingNativeRootIsPreferredAndInvalidOrBrowserRootsAreExcluded() {
    var tasks =
        List.of(
            new BrowserTaskPolicy.Task(3, true),
            new BrowserTaskPolicy.Task(8, true),
            new BrowserTaskPolicy.Task(11, false),
            new BrowserTaskPolicy.Task(-1, true));
    assertEquals(3, BrowserTaskPolicy.nativeTask(tasks, 11, 3));
    assertEquals(8, BrowserTaskPolicy.nativeTask(tasks, 11, -1));
    assertEquals(
        -1, BrowserTaskPolicy.nativeTask(List.of(new BrowserTaskPolicy.Task(-1, true)), 99, -1));
  }

  @Test
  public void emptyHostRemovesOnlyAConfirmedSingletonTask() {
    assertTrue(BrowserTaskPolicy.removeEmptyTask(true, 1));
    for (int count : new int[] {-1, 0, 2, 4})
      assertFalse(BrowserTaskPolicy.removeEmptyTask(true, count));
    assertFalse(BrowserTaskPolicy.removeEmptyTask(false, 1));
  }

  @Test
  public void sameGenerationReentryAndTransientUnreadyDoNotRetireTheHost() {
    var previous = identity(7, "a");
    assertFalse(BrowserTaskPolicy.endedHost(previous, identity(7, "a"), false, false, false, 7));
    assertFalse(BrowserTaskPolicy.endedHost(previous, null, false, false, false, 7));
    assertFalse(BrowserTaskPolicy.endedHost(previous, null, true, false, false, 7));
    assertFalse(BrowserTaskPolicy.endedHost(null, null, false, false, false, 7));
  }

  @Test
  public void stopAndNewRuntimeRetireAnAlreadyEmptyHost() {
    var previous = identity(7, "a");
    assertTrue(BrowserTaskPolicy.endedHost(previous, null, false, true, false, 7));
    assertTrue(BrowserTaskPolicy.endedHost(previous, null, false, false, true, 7));
    assertTrue(BrowserTaskPolicy.endedHost(previous, null, false, false, false, 8));
    assertTrue(BrowserTaskPolicy.endedHost(previous, identity(8, "b"), false, false, false, 8));
    assertTrue(BrowserTaskPolicy.endedHost(previous, identity(7, "b"), false, false, false, 7));
    assertTrue(BrowserTaskPolicy.endedHost(null, identity(8, "b"), true, false, false, 8));
  }
}
