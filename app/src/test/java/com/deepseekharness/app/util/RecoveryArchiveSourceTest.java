package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryArchiveSourceTest {
  private static final String SHA = "0123456789abcdef".repeat(4);

  @Test
  public void identicalBytesMayShareSignedArchive() {
    assertEquals(
        "offline-rootfs.bin",
        RecoveryArchiveSource.validate("recovery-rootfs.bin", "offline-rootfs.bin", SHA, SHA));
    assertEquals(
        "dsh-runtime.bin",
        RecoveryArchiveSource.validate("recovery-dsh-runtime.bin", "dsh-runtime.bin", SHA, SHA));
  }

  @Test
  public void pinnedSeparateArchiveSurvivesMainUpgrade() {
    for (String name : new String[] {"recovery-rootfs.bin", "recovery-dsh-runtime.bin"})
      assertEquals(name, RecoveryArchiveSource.validate(name, name, SHA, SHA));
  }

  @Test
  public void pathsUnknownNamesAndCrossedArchivesAreRejected() {
    for (String name :
        new String[] {
          null,
          "",
          "../offline-rootfs.bin",
          "/offline-rootfs.bin",
          "assets/offline-rootfs.bin",
          "dsh-runtime.bin",
          "recovery-other.bin"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> RecoveryArchiveSource.validate("recovery-rootfs.bin", name, SHA, SHA));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryArchiveSource.validate("unknown.bin", "unknown.bin", SHA, SHA));
  }

  @Test
  public void mappingCannotChangePinnedDigest() {
    for (String hash : new String[] {null, "", SHA.toUpperCase(), "0".repeat(64)})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RecoveryArchiveSource.validate(
                  "recovery-rootfs.bin", "offline-rootfs.bin", SHA, hash));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryArchiveSource.validate(
                "recovery-rootfs.bin", "offline-rootfs.bin", "bad", "bad"));
  }
}
