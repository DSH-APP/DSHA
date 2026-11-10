package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RuntimeModeLabelTest {
  @Test
  public void prootKeysDistinguishSeccompState() {
    assertEquals("proot-seccomp", RuntimeModeLabel.key("proot", false));
    assertEquals("proot-no-seccomp", RuntimeModeLabel.key("proot", true));
  }

  @Test
  public void prorootIgnoresSeccompSetting() {
    assertEquals("proroot", RuntimeModeLabel.key("proroot", true));
    assertEquals("proroot", RuntimeModeLabel.key("proroot", false));
    assertEquals("proroot", RuntimeModeLabel.display("proroot", true));
  }

  @Test
  public void displayNamesTheCompatibilityTier() {
    String saved = UiText.language();
    try {
      UiText.setLanguage(UiLanguagePreference.ZH);
      assertEquals("proot（seccomp 过滤）", RuntimeModeLabel.display("proot", false));
      assertEquals("proot（兼容模式：无 seccomp）", RuntimeModeLabel.display("proot", true));
      UiText.setLanguage(UiLanguagePreference.EN);
      assertEquals("proot (seccomp filter)", RuntimeModeLabel.display("proot", false));
      assertEquals(
          "proot (compatibility mode: no seccomp)", RuntimeModeLabel.display("proot", true));
    } finally {
      UiText.setLanguage(saved);
    }
  }
}
