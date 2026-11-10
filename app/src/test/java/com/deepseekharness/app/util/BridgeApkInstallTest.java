package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class BridgeApkInstallTest {
  private static final String UUID = "0123abcd-4567-89ef-0123-456789abcdef";

  @Test
  public void acceptsOnlySingleApkNames() {
    assertTrue(BridgeApkInstall.apkName("app-release.apk"));
    assertTrue(BridgeApkInstall.apkName("APP.APK"));
    assertFalse(BridgeApkInstall.apkName(".apk"));
    assertFalse(BridgeApkInstall.apkName("bundle.apks"));
    assertFalse(BridgeApkInstall.apkName("bundle.xapk"));
    assertFalse(BridgeApkInstall.apkName("app.apk.zip"));
    assertFalse(BridgeApkInstall.apkName("dir/app.apk"));
    assertFalse(BridgeApkInstall.apkName(null));
  }

  @Test
  public void sizeMustBePositiveAndBounded() {
    assertFalse(BridgeApkInstall.sizeAllowed(0));
    assertTrue(BridgeApkInstall.sizeAllowed(1));
    assertTrue(BridgeApkInstall.sizeAllowed(BridgeApkInstall.SIZE_LIMIT));
    assertFalse(BridgeApkInstall.sizeAllowed(BridgeApkInstall.SIZE_LIMIT + 1));
  }

  private static final String ROOT = "/data/user/0/io.github.sunsetrne.dsha/files/linux/ubuntu";
  private static final String SHARED = "/storage/emulated/0";
  private static final String MEDIA = "/data/media/0";

  private static boolean source(String requested, String canonical) {
    return BridgeApkInstall.sourceAllowed(requested, canonical, ROOT, SHARED, MEDIA);
  }

  @Test
  public void sharedApkUsesCurrentUsersStorageWithoutRelaxingExportPolicy() {
    assertTrue(source("/sdcard/Download/app.apk", SHARED + "/Download/app.apk"));
    assertTrue(source(SHARED + "/Download/app.apk", SHARED + "/Download/app.apk"));
    assertTrue(source("/storage/self/primary/app.apk", SHARED + "/app.apk"));
    assertTrue(source("/data/media/0/app.apk", MEDIA + "/app.apk"));
    assertTrue(BridgePathPolicy.deniedGuestView(SHARED + "/Download/app.apk", ROOT));
  }

  @Test
  public void guestApkRemainsInsideTheSelectedRootfs() {
    assertTrue(source("/root/out/app.apk", ROOT + "/root/out/app.apk"));
    assertFalse(source("/root/out/app.apk", SHARED + "/app.apk"));
    assertFalse(
        source("/root/out/app.apk", "/data/user/0/io.github.sunsetrne.dsha/files/private.apk"));
    assertFalse(source("/root/out/app.apk", "/system/app/Settings/Settings.apk"));
    assertFalse(
        BridgeApkInstall.sourceAllowed(
            "/root/out/app.apk", ROOT + "/root/out/app.apk", null, SHARED, MEDIA));
  }

  @Test
  public void sharedAliasesCannotCrossToPrivateDataOtherUsersOrPrefixLookalikes() {
    assertFalse(source("/sdcard/Download/app.apk", ROOT + "/root/out/app.apk"));
    assertFalse(
        source(
            "/sdcard/Download/app.apk", "/data/user/0/io.github.sunsetrne.dsha/files/private.apk"));
    assertFalse(source("/sdcard/Download/app.apk", "/storage/emulated/10/app.apk"));
    assertFalse(source("/sdcard/Download/app.apk", "/storage/emulated/0-other/app.apk"));
    assertFalse(source("/sdcard/Download/app.apk", "/data/media/10/app.apk"));
    assertFalse(
        BridgeApkInstall.sourceAllowed("/sdcard/app.apk", SHARED + "/app.apk", ROOT, null, null));
  }

  @Test
  public void installCannotExposeCredentialTreesThroughRawNamesOrResolvedAliases() {
    assertFalse(source("/root/.dsh/app.apk", ROOT + "/root/out/app.apk"));
    assertFalse(source("/root/out/app.apk", ROOT + "/root/.dsh/app.apk"));
    assertFalse(source("/sdcard/Download/app.apk", SHARED + "/.ssh/app.apk"));
    assertFalse(source("/sdcard/.dsh/app.apk", SHARED + "/Download/app.apk"));
    assertFalse(
        source("/data/user/0/io.github.sunsetrne.dsha/files/app.apk", ROOT + "/root/out/app.apk"));
    assertFalse(source("relative.apk", ROOT + "/root/out/app.apk"));
  }

  @Test
  public void blocksOnlyExactOwnPackage() {
    assertTrue(
        BridgeApkInstall.selfPackage("io.github.sunsetrne.dsha", "io.github.sunsetrne.dsha"));
    assertFalse(
        BridgeApkInstall.selfPackage("io.github.sunsetrne.dsha.test", "io.github.sunsetrne.dsha"));
    assertFalse(BridgeApkInstall.selfPackage(null, "io.github.sunsetrne.dsha"));
  }

  @Test
  public void launchesDirectlyOnlyWithAnAospBackgroundExemption() {
    BridgeApkInstall.Launch direct = BridgeApkInstall.Launch.INSTALLER;
    BridgeApkInstall.Launch notify = BridgeApkInstall.Launch.NOTIFICATION;
    // 前台或无障碍服务已连接：任何版本都直接打开。
    assertEquals(direct, BridgeApkInstall.launch(true, false, false, 36));
    assertEquals(direct, BridgeApkInstall.launch(false, true, false, 36));
    // 悬浮窗权限在 Android 15 前单独即可豁免；15 起需可见窗口，无法确认就走通知。
    assertEquals(direct, BridgeApkInstall.launch(false, false, true, 34));
    assertEquals(notify, BridgeApkInstall.launch(false, false, true, 35));
    assertEquals(notify, BridgeApkInstall.launch(false, false, true, 36));
    assertEquals(notify, BridgeApkInstall.launch(false, false, false, 30));
  }

  @Test
  public void prunesOnlyExpiredWellFormedSlots() {
    long now = 100L * BridgeApkInstall.RETAIN_MS;
    String old = BridgeApkInstall.slotName(now - BridgeApkInstall.RETAIN_MS, UUID);
    String fresh = BridgeApkInstall.slotName(now - 1, UUID);
    String future = BridgeApkInstall.slotName(now + 1, UUID);
    List<String> names =
        Arrays.asList(old, fresh, future, "notes", "123-not-a-uuid", UUID, "../" + old, null);
    assertEquals(Collections.singletonList(old), BridgeApkInstall.expiredSlots(names, now));
    assertTrue(BridgeApkInstall.expiredSlots(null, now).isEmpty());
  }
}
