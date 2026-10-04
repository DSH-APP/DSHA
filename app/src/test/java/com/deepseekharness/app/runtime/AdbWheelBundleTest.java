package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.AdbWheelCache;
import com.deepseekharness.app.util.FileIntegrity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** 用真实 APK 源资产对照备份引擎摘要契约，只在独立临时目录补缓存。 */
public class AdbWheelBundleTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private String hash(File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      return FileIntegrity.copy(in, null, 128L * 1024 * 1024).sha256;
    }
  }

  @Test
  public void realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel() throws Exception {
    String directory = System.getProperty("dsha.assetsDir");
    assertNotNull("构建入口须传入 dsha.assetsDir", directory);
    File assets = new File(directory);
    File archive = new File(assets, "adb-wheels.tar.gz");
    assertTrue(archive.isFile());
    var lock =
        com.google.gson.JsonParser.parseString(
                Files.readString(new File(assets, "adb-wheels.lock.json").toPath()))
            .getAsJsonObject();
    assertEquals(1, lock.get("schema").getAsInt());
    assertEquals(lock.get("archiveSha256").getAsString(), hash(archive));
    Map<String, String> expected = new HashMap<>();
    for (var entry : lock.getAsJsonObject("wheels").entrySet())
      expected.put(entry.getKey(), entry.getValue().getAsString());
    assertFalse(expected.isEmpty());
    File bundled = temp.newFolder("apk"), restored = temp.newFolder("restored");
    try (InputStream in = new GZIPInputStream(new FileInputStream(archive))) {
      TarGzipExtractor.extractSelected(
          in, bundled, 0, name -> true, new com.deepseekharness.app.backup.JvmBackupFileSystem());
    }
    assertEquals(expected.size(), bundled.listFiles((d, n) -> n.endsWith(".whl")).length);
    for (Map.Entry<String, String> item : expected.entrySet())
      assertEquals(item.getKey(), item.getValue(), hash(new File(bundled, item.getKey())));
    String modifiedName = "adb_shell_wifi-0.5.0-py3-none-any.whl";
    File modified = new File(restored, modifiedName);
    try (ZipFile source = new ZipFile(new File(bundled, modifiedName));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(modified))) {
      Enumeration<? extends ZipEntry> all = source.entries();
      while (all.hasMoreElements()) {
        ZipEntry entry = all.nextElement();
        out.putNextEntry(new ZipEntry(entry.getName()));
        try (InputStream in = source.getInputStream(entry)) {
          byte[] buffer = new byte[65536];
          int count;
          while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (entry.getName().equals("adb_shell_wifi/__init__.py"))
          out.write("\n# restored custom wheel fixture\n".getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    String originalModifiedHash = hash(modified);
    assertNotEquals(expected.get(modifiedName), originalModifiedHash);
    File cachedArchive = new File(temp.getRoot(), "user-archive.tar.gz");
    Files.writeString(cachedArchive.toPath(), "restored modified archive bytes");
    AdbWheelCache.Merge report =
        AdbWheelCache.fillMissing(
            new com.deepseekharness.app.backup.JvmBackupFileSystem(),
            bundled,
            restored,
            archive,
            cachedArchive);
    assertEquals(expected.size() - 1, report.added);
    assertEquals(1, report.modified);
    assertEquals(originalModifiedHash, hash(modified));
    assertEquals("restored modified archive bytes", Files.readString(cachedArchive.toPath()));
    for (Map.Entry<String, String> item : expected.entrySet())
      if (!item.getKey().equals(modifiedName))
        assertEquals(item.getValue(), hash(new File(restored, item.getKey())));
    AdbWheelCache.validate(new com.deepseekharness.app.backup.JvmBackupFileSystem(), restored);
  }
}
