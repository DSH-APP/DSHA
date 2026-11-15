package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CurrentReleaseNotesTest {
  private static final String VERSION = "0.2.1-rc.3-20261024.0345-dsh0.2.1-rc.3";
  private static final String NOTES = "# DSHA " + VERSION + " / build194\n\nCurrent fixes";

  @Test
  public void subtitleUsesTheInstalledReleaseVersionWithoutTheDiagnosticTimestamp() {
    assertEquals("0.2.1-rc.3", CurrentReleaseNotes.versionLabel(VERSION));
    assertEquals("1.2.0", CurrentReleaseNotes.versionLabel("1.2.0"));
    // build308 起 DSHA 修订号与内置 DSH 同为 rc.5；标签只取修订号前缀。
    assertEquals(
        "0.2.1-rc.5", CurrentReleaseNotes.versionLabel("0.2.1-rc.5-20261104.1830-dsh0.2.1-rc.5"));
  }

  @Test
  public void actualCurrentBodyAndInstalledIdentityAppearTogether() {
    assertEquals(
        "DSHA " + VERSION + " / build194\n\nCurrent fixes",
        CurrentReleaseNotes.render(VERSION, 194, NOTES, false));
  }

  @Test
  public void anotherCodeOrDiagnosticVersionCannotReuseTheseNotes() {
    for (String stale :
        new String[] {NOTES.replace("build194", "build157"), NOTES.replace(".0345-", ".0120-")}) {
      String rendered = CurrentReleaseNotes.render(VERSION, 194, stale, false);
      assertTrue(rendered.contains("未附当前版本"));
      assertFalse(rendered.contains("Current fixes"));
    }
  }

  @Test
  public void missingResourceHasALocalizedExplicitFallback() {
    assertTrue(CurrentReleaseNotes.render(VERSION, 194, null, false).contains("未附当前版本"));
    assertTrue(
        CurrentReleaseNotes.render(VERSION, 194, null, true)
            .contains("does not include release notes"));
  }

  @Test
  public void everySupportedLanguageUsesTheRequestedFallbackWithoutChangingInstalledIdentity() {
    for (String language : UiLanguagePreference.LANGUAGES) {
      String fallback =
          UiText.choose(
              language,
              "本安装包未附当前版本的更新说明。",
              "This installation does not include release notes for the current build.");
      assertEquals(
          "DSHA " + VERSION + " / build194\n\n" + fallback,
          CurrentReleaseNotes.render(VERSION, 194, null, language));
      assertTrue(
          CurrentReleaseNotes.render(VERSION, 194, NOTES, language).endsWith("Current fixes"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> CurrentReleaseNotes.render(VERSION, 194, null, "system"));
  }

  @Test
  public void testSuffixSharesNotesButKeepsItsActualInstalledTitle() {
    for (String suffix : new String[] {"", "-test"}) {
      String installed = VERSION + suffix;
      assertTrue(CurrentReleaseNotes.matches(installed, 194, NOTES));
      assertTrue(
          CurrentReleaseNotes.render(installed, 194, NOTES, true).startsWith("DSHA " + installed));
    }
  }
}
