package com.deepseekharness.app.util;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The current signed APK has one rootfs asset; historical backups have separate readers. */
public final class BundledRootfsAsset {
  public static final String NAME = "offline-rootfs.bin";

  private BundledRootfsAsset() {}

  public static ZipEntry find(ZipFile apk) {
    ZipEntry entry = apk.getEntry("assets/" + NAME);
    return entry != null && !entry.isDirectory() ? entry : null;
  }
}
