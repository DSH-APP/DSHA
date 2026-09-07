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
        try (InputStream in = new FileInputStream(file)) { return FileIntegrity.copy(in, null, 128L * 1024 * 1024).sha256; }
    }
    @Test public void realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel() throws Exception {
        File assets = new File("app/src/main/assets");
        if (!assets.isDirectory()) assets = new File("src/main/assets");
        File archive = new File(assets, "adb-wheels.tar.gz");
        assertTrue("测试需在源码根目录或 app 目录运行", archive.isFile());
        String engine = Files.readString(new File(assets, "backup-engine.py").toPath());
        Matcher archiveHash = Pattern.compile("ADB_ARCHIVE_SHA256 = '([a-f0-9]{64})'").matcher(engine);
        assertTrue(archiveHash.find()); assertEquals(archiveHash.group(1), hash(archive));
        Map<String, String> expected = new HashMap<>();
        Matcher wheels = Pattern.compile("\"([^\"]+\\.whl)\"\\s*:\\s*\"([a-f0-9]{64})\"").matcher(engine);
        while (wheels.find()) expected.put(wheels.group(1), wheels.group(2));
        assertFalse(expected.isEmpty());
        File bundled = temp.newFolder("apk"), restored = temp.newFolder("restored");
        TarGzipExtractor.extract(archive, bundled);
        assertEquals(expected.size(), bundled.listFiles((d, n) -> n.endsWith(".whl")).length);
        for (Map.Entry<String, String> item : expected.entrySet()) assertEquals(item.getKey(), item.getValue(), hash(new File(bundled, item.getKey())));
        String modifiedName = "adb_shell_wifi-0.5.0-py3-none-any.whl";
        File modified = new File(restored, modifiedName);
        try (ZipFile source = new ZipFile(new File(bundled, modifiedName));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(modified))) {
            Enumeration<? extends ZipEntry> all = source.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                if (entry.getName().equals("adb_shell_wifi/__init__.py")) out.write("\n# restored custom wheel fixture\n".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        String originalModifiedHash = hash(modified);
        assertNotEquals(expected.get(modifiedName), originalModifiedHash);
        File cachedArchive = new File(temp.getRoot(), "user-archive.tar.gz");
        Files.writeString(cachedArchive.toPath(), "restored modified archive bytes");
        AdbWheelCache.Merge report = AdbWheelCache.fillMissing(bundled, restored, archive, cachedArchive);
        assertEquals(expected.size() - 1, report.added);
        assertEquals(1, report.modified);
        assertEquals(originalModifiedHash, hash(modified));
        assertEquals("restored modified archive bytes", Files.readString(cachedArchive.toPath()));
        for (Map.Entry<String, String> item : expected.entrySet())
            if (!item.getKey().equals(modifiedName)) assertEquals(item.getValue(), hash(new File(restored, item.getKey())));
        AdbWheelCache.validate(restored);
    }
}
