package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class SelfPackageIdentityTest {
  private static final String APK = "/data/app/actual-clone/base.apk";

  @Test
  public void actualCloneCallerAndLoadedApkBindTogether() throws Exception {
    var identity =
        SelfPackageIdentity.caller(
            "com.dsh.clienu", 99912345, APK, APK, 99912345, new String[] {"com.dsh.clienu"});
    assertEquals("com.dsh.clienu", identity.name);
    assertEquals(99912345, identity.uid);
    assertEquals(APK, identity.source);
  }

  @Test
  public void guessedPackageUidOrOtherApkCannotSupplyIdentity() {
    assertThrows(
        IOException.class,
        () ->
            SelfPackageIdentity.caller(
                "com.dsh.client", 12345, APK, APK, 12345, new String[] {"com.dsh.clienu"}));
    assertThrows(
        IOException.class,
        () ->
            SelfPackageIdentity.caller(
                "com.dsh.clienu", 12345, APK, APK, 99912345, new String[] {"com.dsh.clienu"}));
    assertThrows(
        IOException.class,
        () -> SelfPackageIdentity.launch("com.dsh.clienu", 12345, APK, "/data/app/other/base.apk"));
  }

  @Test
  public void missingLegacyIdentityDoesNotFallBackToStandardPackage() {
    for (String name : new String[] {null, "", "com.dsh.client;evil"})
      assertThrows(IOException.class, () -> SelfPackageIdentity.launch(name, 12345, APK, APK));
    assertThrows(
        IOException.class, () -> SelfPackageIdentity.launch("com.dsh.clienu", 2000, APK, APK));
    assertThrows(
        IOException.class,
        () ->
            SelfPackageIdentity.launch(
                "com.dsh.clienu", 12345, "/data/app/../tmp/base.apk", "/data/app/../tmp/base.apk"));
  }
}
