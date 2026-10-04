package com.deepseekharness.app.util;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class BundledRootfsAssetTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File apk(String... names) throws Exception {
    File file = temporary.newFile();
    try (var out = new ZipOutputStream(new FileOutputStream(file))) {
      for (String name : names) {
        out.putNextEntry(new ZipEntry(name));
        if (!name.endsWith("/")) out.write(new byte[1024]);
        out.closeEntry();
      }
    }
    return file;
  }

  @Test
  public void onlySignedCurrentPathCanBeSelected() throws Exception {
    try (var zip =
        new ZipFile(
            apk(
                "root/offline_rootfs-large.bin",
                "offline-rootfs.tar.gz",
                "assets/offline-rootfs.bin"))) {
      assertEquals("assets/offline-rootfs.bin", BundledRootfsAsset.find(zip).getName());
    }
    try (var zip = new ZipFile(apk("offline-rootfs.bin", "assets/offline-rootfs.tar.gz"))) {
      assertNull(BundledRootfsAsset.find(zip));
    }
    try (var zip = new ZipFile(apk("assets/offline-rootfs.bin/"))) {
      assertNull(BundledRootfsAsset.find(zip));
    }
  }
}
