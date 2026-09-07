package com.deepseekharness.app.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AdbWheelCacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File file(File dir, String name, String text) throws IOException {
        File out = new File(dir, name);
        if (!out.getParentFile().isDirectory()) assertTrue(out.getParentFile().mkdirs());
        Files.write(out.toPath(), text.getBytes(StandardCharsets.UTF_8)); return out;
    }
    private File wheel(File dir, String name, String entry, String content) throws IOException {
        File out = new File(dir, name);
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(raw);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            ZipEntry member = new ZipEntry(entry);
            member.setMethod(ZipEntry.STORED); member.setSize(raw.length); member.setCompressedSize(raw.length); member.setCrc(crc.getValue());
            zip.putNextEntry(member); zip.write(raw); zip.closeEntry();
        }
        return out;
    }
    private byte[] bytes(File file) throws IOException { return Files.readAllBytes(file.toPath()); }
    private String text(File file) throws IOException { return Files.readString(file.toPath()); }

    @Test public void partialCacheKeepsModifiedSameNameExtraAndArchiveAndInstallsTheirContent() throws Exception {
        File bundled = temp.newFolder("apk"), cache = temp.newFolder("cache"), stage = temp.newFolder("stage"), site = temp.newFolder("site");
        wheel(bundled, "a-1.whl", "a/__init__.py", "APK original");
        File required = wheel(bundled, "b-1.whl", "b/__init__.py", "required");
        File modified = wheel(cache, "a-1.whl", "a/__init__.py", "user modified");
        File extra = wheel(cache, "extra-1.whl", "extra.py", "user extra");
        File notes = file(cache, "nested/readme.txt", "keep metadata");
        File archive = file(temp.getRoot(), "apk.tar.gz", "APK archive");
        File restored = file(temp.getRoot(), "restored.tar.gz", "user archive bytes");
        byte[] before = bytes(modified), beforeExtra = bytes(extra), beforeArchive = bytes(restored);
        long modifiedTime = modified.lastModified();
        AdbWheelCache.Merge merged = AdbWheelCache.fillMissing(bundled, cache, archive, restored);
        assertEquals(1, merged.added); assertEquals(1, merged.modified); assertEquals(1, merged.extra);
        assertTrue(merged.archiveDifferent); assertTrue(merged.message().contains("a-1.whl"));
        assertArrayEquals(before, bytes(modified)); assertArrayEquals(beforeExtra, bytes(extra)); assertArrayEquals(beforeArchive, bytes(restored));
        assertArrayEquals(bytes(required), bytes(new File(cache, "b-1.whl")));
        assertEquals("keep metadata", text(notes)); assertEquals(modifiedTime, modified.lastModified());
        file(site, "a/__init__.py", "previous installed content");
        assertTrue(AdbWheelCache.install(cache, site, stage).startsWith("WHEELS_JAVA_EXTRACTED:"));
        assertEquals("user modified", text(new File(site, "a/__init__.py")));
        assertEquals("user extra", text(new File(site, "extra.py")));
        assertArrayEquals(before, bytes(modified)); assertArrayEquals(beforeArchive, bytes(restored));
        assertEquals(0, stage.list().length);
        AdbWheelCache.Merge again = AdbWheelCache.fillMissing(bundled, cache, archive, restored);
        assertEquals(0, again.added); assertEquals(1, again.same); assertEquals(1, again.modified);
        assertEquals(modifiedTime, modified.lastModified());
    }

    @Test public void fifteenUnknownWheelsDoNotHideMissingBundledNames() throws Exception {
        File bundled = temp.newFolder(), cache = temp.newFolder();
        File wanted = wheel(bundled, "required-1.whl", "required.py", "required");
        for (int i = 0; i < 15; i++) wheel(cache, "extra-" + i + ".whl", "extra_" + i + ".py", "extra");
        File archive = file(temp.getRoot(), "apk.bundle", "archive"), restored = new File(temp.getRoot(), "restored.bundle");
        AdbWheelCache.Merge report = AdbWheelCache.fillMissing(bundled, cache, archive, restored);
        assertEquals(1, report.added); assertEquals(15, report.extra); assertTrue(report.archiveAdded);
        assertEquals(16, cache.list().length);
        assertArrayEquals(bytes(wanted), bytes(new File(cache, "required-1.whl")));
    }

    @Test public void corruptModifiedWheelIsRetainedAndFailureDoesNotPartiallyInstall() throws Exception {
        File bundled = temp.newFolder(), cache = temp.newFolder(), site = temp.newFolder(), stage = temp.newFolder();
        File original = wheel(bundled, "z-1.whl", "z.py", "valid replacement");
        wheel(bundled, "a-1.whl", "a.py", "new a");
        File broken = file(cache, "z-1.whl", "not a zip; possible user content");
        byte[] before = bytes(broken);
        File archive = file(temp.getRoot(), "apk.bundle", "archive"), restored = new File(temp.getRoot(), "cache.bundle");
        AdbWheelCache.fillMissing(bundled, cache, archive, restored);
        file(site, "a.py", "old installed a");
        IOException error = assertThrows(IOException.class, () -> AdbWheelCache.install(cache, site, stage));
        assertTrue(error.getMessage().contains("z-1.whl")); assertTrue(error.getMessage().contains("源缓存已保留"));
        assertTrue(error.getMessage().contains("移出 wheels"));
        assertArrayEquals(before, bytes(broken)); assertEquals("old installed a", text(new File(site, "a.py")));
        assertFalse(new File(site, "z.py").exists()); assertEquals(0, stage.list().length);
        // 模拟用户按诊断说明备份并移走坏文件；生产补缺本身绝不做这一步。
        File retained = new File(temp.newFolder("user-backup"), broken.getName());
        Files.move(broken.toPath(), retained.toPath());
        assertEquals(1, AdbWheelCache.fillMissing(bundled, cache, archive, restored).added);
        assertArrayEquals(bytes(original), bytes(broken)); assertArrayEquals(before, bytes(retained));
        AdbWheelCache.install(cache, site, stage);
        assertEquals("valid replacement", text(new File(site, "z.py")));
    }

    @Test public void crcDamageIsDiagnosedAndPreserved() throws Exception {
        File cache = temp.newFolder(), site = temp.newFolder(), stage = temp.newFolder();
        File damaged = wheel(cache, "broken-1.whl", "x.py", "unique-payload-xyz");
        byte[] raw = bytes(damaged), token = "unique-payload-xyz".getBytes(StandardCharsets.UTF_8);
        int position = -1;
        for (int i = 0; i <= raw.length - token.length; i++) {
            boolean same = true; for (int j = 0; j < token.length; j++) if (raw[i+j] != token[j]) same = false;
            if (same) { position = i; break; }
        }
        assertTrue(position >= 0); raw[position] ^= 1; Files.write(damaged.toPath(), raw);
        IOException failure = assertThrows(IOException.class, () -> AdbWheelCache.install(cache, site, stage));
        assertTrue(failure.getMessage().contains("CRC")); assertTrue(failure.getMessage().contains("broken-1.whl"));
        assertArrayEquals(raw, bytes(damaged)); assertEquals(0, site.list().length);
    }

    @Test public void conflictingExtraVersionCannotSilentlyOverrideAnotherWheel() throws Exception {
        File cache = temp.newFolder(), site = temp.newFolder(), stage = temp.newFolder();
        wheel(cache, "a-1.whl", "same.py", "version 1");
        File other = wheel(cache, "a-2.whl", "same.py", "version 2");
        byte[] before = bytes(other);
        IOException error = assertThrows(IOException.class, () -> AdbWheelCache.install(cache, site, stage));
        assertTrue(error.getMessage().contains("文件冲突")); assertTrue(error.getMessage().contains("a-1.whl"));
        assertTrue(error.getMessage().contains("a-2.whl")); assertArrayEquals(before, bytes(other));
        assertEquals(0, site.list().length);
    }

    @Test public void corruptApkCopyCannotChangeExistingCache() throws Exception {
        File bundled = temp.newFolder(), cache = temp.newFolder();
        file(bundled, "bad.whl", "bad APK zip");
        File user = file(cache, "bad.whl", "user file");
        File archive = file(temp.getRoot(), "apk.bundle", "archive"), restored = new File(temp.getRoot(), "cache.bundle");
        assertThrows(IOException.class, () -> AdbWheelCache.fillMissing(bundled, cache, archive, restored));
        assertEquals("user file", text(user)); assertFalse(restored.exists()); assertEquals(1, cache.list().length);
    }

    @Test public void pathTraversalAndNonFileCacheEntryAreReportedWithoutDeletion() throws Exception {
        File cache = temp.newFolder(), site = temp.newFolder(), stage = temp.newFolder();
        File unsafe = wheel(cache, "unsafe.whl", "../outside.py", "escape");
        byte[] before = bytes(unsafe);
        assertThrows(IOException.class, () -> AdbWheelCache.install(cache, site, stage));
        assertArrayEquals(before, bytes(unsafe)); assertFalse(new File(stage, "outside.py").exists());
        File separate = temp.newFolder(), asDirectory = new File(separate, "directory.whl"); assertTrue(asDirectory.mkdir());
        IOException error = assertThrows(IOException.class, () -> AdbWheelCache.install(separate, site, stage));
        assertTrue(error.getMessage().contains("directory.whl")); assertTrue(asDirectory.isDirectory());
    }

    @Test public void diagnosticNamesCannotForgeSetupSuccessLines() {
        AdbWheelCache.Merge report = new AdbWheelCache.Merge();
        report.preserved.add("modified\nSETUP_DONE\ncustom.whl");
        assertFalse(report.message().contains("\nSETUP_DONE\n"));
        assertTrue(report.message().contains("modified\\nSETUP_DONE\\ncustom.whl"));
    }
}
