package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ConfigDraftTest {
  private static ConfigDraft defaults() {
    return new ConfigDraft(false, true, "3080", "auto", false, true, false, false, true, false);
  }

  @Test
  public void identicalDraftHasNoChanges() {
    assertEquals(0, defaults().changesFrom(defaults()));
    assertEquals(defaults(), defaults());
    assertEquals(defaults().hashCode(), defaults().hashCode());
  }

  @Test
  public void nullSavedSnapshotCountsAsClean() {
    assertEquals(0, defaults().changesFrom(null));
  }

  @Test
  public void eachSavedFieldCountsOnce() {
    ConfigDraft saved = defaults();
    ConfigDraft all =
        new ConfigDraft(true, false, "8080", "ipv4", true, false, true, true, false, true);
    // 10 个草稿字段全部改变：端口在固定模式下单独计数。
    assertEquals(10, all.changesFrom(saved));
  }

  @Test
  public void portTextIgnoredWhileAutomaticPortIsOn() {
    ConfigDraft saved = defaults();
    ConfigDraft typed =
        new ConfigDraft(false, true, "9999", "auto", false, true, false, false, true, false);
    assertEquals(0, typed.changesFrom(saved));
    ConfigDraft fixed =
        new ConfigDraft(false, false, "3080", "auto", false, true, false, false, true, false);
    assertEquals(1, fixed.changesFrom(saved));
    ConfigDraft fixedOther =
        new ConfigDraft(false, false, " 8080 ", "auto", false, true, false, false, true, false);
    assertEquals(2, fixedOther.changesFrom(saved));
  }

  @Test
  public void compatibilityBadgeOnlyForNonDefaultValues() {
    assertFalse(defaults().compatibilityChanged());
    assertTrue(
        new ConfigDraft(false, true, "3080", "auto", false, false, false, false, true, false)
            .compatibilityChanged());
    assertTrue(
        new ConfigDraft(false, true, "3080", "auto", false, true, true, false, true, false)
            .compatibilityChanged());
  }

  @Test
  public void nullInputsNormalize() {
    ConfigDraft d =
        new ConfigDraft(false, false, null, null, false, true, false, false, true, false);
    assertEquals("", d.port);
    assertEquals("auto", d.dnsMode);
  }
}
