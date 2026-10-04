package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateLocationsTest {
  @Test
  public void onlyOfficialReleasePagesAndArtifactPathsAreAccepted() {
    assertTrue(UpdateLocations.page("https://dsha.cc/download/"));
    assertTrue(UpdateLocations.page("https://github.com/DSH-APP/DSHA/releases/tag/v0.2.0-rc2"));
    assertTrue(
        UpdateLocations.artifact(
            "https://dsha.cc/downloads/0.2.4-20261002.2238-dsh0.2.0-rc.2/dsha-0.2.0-rc2low.apk"));
    assertTrue(
        UpdateLocations.artifact(
            "https://github.com/DSH-APP/DSHA/releases/download/v0.1.7-rc2/dsha-0.1.7-rc2.apk"));
    for (String url :
        new String[] {
          "https://evil.example/a.apk",
          "https://dsha.cc.evil.example/downloads/a.apk",
          "https://github.com/other/DSHA/releases/download/v1/a.apk",
          "https://dsha.cc/a.apk",
          "http://dsha.cc/downloads/a.apk",
          "https://user@dsha.cc/downloads/a.apk",
          "https://dsha.cc:444/downloads/a.apk",
          "https://dsha.cc/downloads/%2e%2e/a.apk",
          "https://dsha.cc/downloads/%252e%252e/a.apk",
          "https://dsha.cc/downloads/a%2fb.apk"
        }) assertFalse(url, UpdateLocations.artifact(url));
    assertFalse(
        UpdateLocations.page("https://github.com/DSH-APP/DSHA/releases/tag/v1?redirect=evil"));
  }

  @Test
  public void feedCannotRedirectToAnArtifactOrAnotherMetadataPath() {
    assertTrue(
        UpdateLocations.redirect(UpdateLocations.FEED, "https://dsha.cc:443/api/updates.json"));
    assertFalse(UpdateLocations.redirect(UpdateLocations.FEED, "https://dsha.cc/api/other.json"));
    assertFalse(
        UpdateLocations.redirect(
            UpdateLocations.FEED, "https://github.com/DSH-APP/DSHA/releases/download/v1/a.apk"));
  }

  @Test
  public void cdnIsAcceptedOnlyAsAnArtifactRedirect() {
    String cdn =
        "https://release-assets.githubusercontent.com/github-production-release-asset/1/a?signature=synthetic";
    assertFalse(UpdateLocations.artifact(cdn));
    assertTrue(UpdateLocations.redirect("https://dsha.cc/downloads/a.apk", cdn));
    assertFalse(UpdateLocations.redirect(UpdateLocations.FEED, cdn));
    assertFalse(
        UpdateLocations.redirect(
            "https://dsha.cc/downloads/a.apk",
            "https://raw.githubusercontent.com/DSH-APP/DSHA/a.apk"));
  }

  @Test
  public void notesStayBoundedPlainTextWithoutStatusSpoofingControls() {
    assertEquals("<b>text</b>\nline", UpdateLocations.notes("<b>text</b>\r\nline\u0000\u202e"));
    assertEquals(8192, UpdateLocations.notes("x".repeat(20000)).length());
  }

  @Test
  public void unsafeAndMissingReleasePageFallsBackToTheOfficialPage() {
    for (String value :
        new String[] {
          null,
          "",
          "https://evil.example/",
          "https://github.com/other/repo/releases",
          "javascript:alert(1)"
        }) assertEquals(UpdateLocations.DEFAULT_PAGE, UpdateLocations.releasePage(value));
    String official = "https://github.com/DSH-APP/DSHA/releases/tag/v1";
    assertEquals(official, UpdateLocations.releasePage(official));
  }
}
