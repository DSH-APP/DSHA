package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class UpdateManifestTest {
  private static final String SIGNER =
      "a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d";
  private static final String APK =
      "{\"flavor\":\"standard\",\"minSdk\":30,\"abi\":\"arm64-v8a\","
          + "\"url\":\"https://dsha.cc/downloads/new.apk\",\"sha256\":\""
          + "a".repeat(64)
          + "\",\"bytes\":100}";
  private static final String RELEASE =
      "{\"versionCode\":200,\"version\":\"0.2.1\","
          + "\"channel\":\"preview\",\"artifacts\":["
          + APK
          + "]}";

  private String feed(String releases) {
    return "{\"schemaVersion\":1,\"packageName\":\"io.github.sunsetrne.dsha\",\"certificateSha256\":\""
        + SIGNER
        + "\",\"releases\":"
        + releases
        + "}";
  }

  private List<UpdatePolicy.Release> parse(String json) throws Exception {
    return UpdateManifest.parse(
        json.getBytes(StandardCharsets.UTF_8),
        "io.github.sunsetrne.dsha",
        Collections.singletonList(SIGNER));
  }

  @Test
  public void exactIdentityPublishesOnlyApplicableChannelAndVersion() throws Exception {
    List<UpdatePolicy.Release> releases = parse(feed("[" + RELEASE + "]"));
    assertEquals(1, releases.size());
    assertNotNull(UpdatePolicy.select(releases, 194, "standard", 33, "preview"));
    assertNull(UpdatePolicy.select(releases, 194, "standard", 33, "stable"));
    assertNull(UpdatePolicy.select(releases, 200, "standard", 33, "preview"));
    assertNull(UpdatePolicy.select(releases, 194, "low", 33, "preview"));
  }

  @Test
  public void legacyPackageIsANormalEmptyResultEvenWithAHigherCode() throws Exception {
    assertTrue(
        parse(feed("[" + RELEASE + "]").replace("io.github.sunsetrne.dsha", "com.dsh.client"))
            .isEmpty());
  }

  @Test
  public void wrongOrMissingCertificateNeverOffersAnUpdate() throws Exception {
    assertTrue(parse(feed("[" + RELEASE + "]").replace(SIGNER, "b".repeat(64))).isEmpty());
    assertTrue(
        parse(feed("[" + RELEASE + "]").replace("\"certificateSha256\"", "\"oldField\""))
            .isEmpty());
    assertTrue(
        UpdateManifest.parse(
                feed("[" + RELEASE + "]").getBytes(StandardCharsets.UTF_8),
                "io.github.sunsetrne.dsha",
                Collections.emptyList())
            .isEmpty());
  }

  @Test
  public void emptyOrUnpublishedReleaseAndAbsentAssetsAreNormalResults() throws Exception {
    for (String releases : new String[] {"[]", "null", "[{}]", "[{\"artifacts\":[]}]"})
      assertTrue(parse(feed(releases)).isEmpty());
    assertTrue(parse(feed("[]").replace(",\"releases\":[]", "")).isEmpty());
  }

  @Test
  public void brokenOrInapplicableArtifactDoesNotHideAValidSibling() throws Exception {
    String release = RELEASE.replace("[" + APK + "]", "[null,{}," + APK + "]");
    assertEquals(1, parse(feed("[null,{}," + release + "]")).size());
    assertTrue(parse(feed("[" + RELEASE.replace("standard", "unavailable") + "]")).isEmpty());
    assertTrue(
        parse(feed("[" + RELEASE.replace("https://dsha.cc", "http://dsha.cc") + "]")).isEmpty());
  }

  @Test
  public void malformedManifestDoesNotBecomeNoUpdates() throws Exception {
    for (String json :
        new String[] {
          "",
          "<html>proxy</html>",
          "null",
          "[]",
          "{}",
          feed("[]") + " {}",
          feed("[]").replace("schemaVersion\":1", "schemaVersion\":2"),
          feed("{}")
        }) {
      assertThrows(json, UpdateFailures.InvalidManifest.class, () -> parse(json));
    }
  }

  @Test
  public void numbersCannotWrapOrTruncateIntoAnInstallableCandidate() throws Exception {
    for (String bad : new String[] {"4294967496", "200.1", "2e2", "\"200\""})
      assertTrue(
          parse(feed("[" + RELEASE.replace("\"versionCode\":200", "\"versionCode\":" + bad) + "]"))
              .isEmpty());
  }

  @Test
  public void oversizeMetadataIsAnExplicitFailure() {
    assertThrows(
        UpdateFailures.InvalidManifest.class,
        () ->
            UpdateManifest.parse(
                new byte[1024 * 1024 + 1], "io.github.sunsetrne.dsha", List.of(SIGNER)));
  }
}
