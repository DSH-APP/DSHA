package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class DshVersionPolicyTest {
  private static final String RC3 = "dsh-0.2.1-rc.3";
  private static final String RC5 = "dsh-0.2.1-rc.5";
  private static final String RC1 = "dsh-0.2.2-rc.1";

  @Test
  public void currentAndOlderReadableDataAreNotDowngrades() {
    assertEquals(
        DshVersionPolicy.Status.CURRENT,
        DshVersionPolicy.assess("0.2.1-rc.5", RC5, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_OLDER,
        DshVersionPolicy.assess("0.2.1-rc.3", RC3, "0.2.1-rc.5", List.of(RC3, RC5)));
  }

  @Test
  public void newerDataAlwaysRequiresExplicitUserChoice() {
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_NEWER,
        DshVersionPolicy.assess("0.2.2-rc.1", RC1, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_NEWER,
        DshVersionPolicy.assess("0.2.2-rc.1", RC1, "0.2.1-rc.5", List.of(RC5, RC1)));
  }

  @Test
  public void dataWriteEvidenceWinsOverStaleDshLabel() {
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_NEWER,
        DshVersionPolicy.assess("0.2.1-rc.5", RC1, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.INSTALLED_OLDER,
        DshVersionPolicy.assess("0.2.2-rc.1", RC3, "0.2.1-rc.5", List.of(RC3, RC5)));
  }

  @Test
  public void unknownAndIncompleteEvidenceIsNotTreatedAsNewerData() {
    assertEquals(
        DshVersionPolicy.Status.MISSING,
        DshVersionPolicy.assess(null, RC5, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.MISSING,
        DshVersionPolicy.assess("", RC5, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.UNKNOWN,
        DshVersionPolicy.assess("0.2.1-rc.5", null, "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.UNKNOWN,
        DshVersionPolicy.assess("0.2.9", "dsh-0.2.9", "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.UNKNOWN,
        DshVersionPolicy.assess("0.2.1-rc.5", "not-a-dsh-version", "0.2.1-rc.5", List.of(RC5)));
    assertEquals(
        DshVersionPolicy.Status.UNKNOWN,
        DshVersionPolicy.assess("0.2.1-rc.5", RC5, "0.2.1-rc.5", null));
  }
}
