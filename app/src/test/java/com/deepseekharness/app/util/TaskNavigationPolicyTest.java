package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TaskNavigationPolicyTest {
  @Test
  public void nestedPageBackReturnsToMainButMainBackLeavesTheUi() {
    assertEquals(TaskNavigationPolicy.Back.POP_PAGE, TaskNavigationPolicy.back(1, false));
    assertEquals(TaskNavigationPolicy.Back.BACKGROUND, TaskNavigationPolicy.back(0, false));
    assertEquals(TaskNavigationPolicy.Back.BACKGROUND, TaskNavigationPolicy.back(1, true));
  }

  @Test
  public void onlySelectedMainAppearsInRecentsAndLegacyMainWinsOverBrowserRoot() {
    assertEquals(
        TaskNavigationPolicy.Recents.SHOW, TaskNavigationPolicy.recents(5, true, false, 5));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(4, true, false, 5));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(8, false, true, 5));
    assertEquals(TaskNavigationPolicy.Recents.SHOW, TaskNavigationPolicy.recents(5, true, true, 5));
  }

  @Test
  public void unclassifiedOrInvalidTasksAndTheLastUnknownMainAreNeverHidden() {
    assertEquals(
        TaskNavigationPolicy.Recents.KEEP, TaskNavigationPolicy.recents(7, false, false, 5));
    assertEquals(
        TaskNavigationPolicy.Recents.KEEP, TaskNavigationPolicy.recents(-1, true, false, 5));
    assertEquals(
        TaskNavigationPolicy.Recents.KEEP, TaskNavigationPolicy.recents(5, true, false, -1));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(8, false, true, -1));
  }

  @Test
  public void hideAllHidesEveryValidTaskButNeverAnInvalidOne() {
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(5, true, false, 5, true));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(4, true, false, 5, true));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(7, false, false, 5, true));
    assertEquals(
        TaskNavigationPolicy.Recents.HIDE, TaskNavigationPolicy.recents(8, false, true, -1, true));
    assertEquals(
        TaskNavigationPolicy.Recents.KEEP, TaskNavigationPolicy.recents(-1, true, false, 5, true));
  }

  @Test
  public void legacyOverloadKeepsTheSingleMainCardClassification() {
    assertEquals(
        TaskNavigationPolicy.recents(5, true, false, 5),
        TaskNavigationPolicy.recents(5, true, false, 5, false));
    assertEquals(
        TaskNavigationPolicy.recents(4, true, false, 5),
        TaskNavigationPolicy.recents(4, true, false, 5, false));
    assertEquals(
        TaskNavigationPolicy.recents(8, false, true, 5),
        TaskNavigationPolicy.recents(8, false, true, 5, false));
    assertEquals(
        TaskNavigationPolicy.recents(7, false, false, 5),
        TaskNavigationPolicy.recents(7, false, false, 5, false));
  }
}
