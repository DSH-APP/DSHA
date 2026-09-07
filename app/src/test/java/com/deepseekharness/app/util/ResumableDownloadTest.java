package com.deepseekharness.app.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ResumableDownloadTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final byte[] data = "完整安装包：断点也必须校验整个文件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final ResumableDownload.Progress progress = new ResumableDownload.Progress() {
        public void check() { }
        public void changed(long n, long total) { }
    };
    private String hash() throws IOException { return FileIntegrity.copy(new ByteArrayInputStream(data), null, 1024).sha256; }
    private HttpURLConnection response(int code, byte[] body, String range) throws Exception {
        return new HttpURLConnection(new URL("https://example.org/update.apk")) {
            public int getResponseCode() { return code; }
            public String getHeaderField(String key) { return key.equals("Content-Range") ? range : key.equals("Content-Length") ? "" + body.length : null; }
            public InputStream getInputStream() { return new ByteArrayInputStream(body); }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public void connect() { }
        };
    }
    @Test public void resumesAndVerifiesExistingPrefix() throws Exception {
        File partial = temp.newFile(); Files.write(partial.toPath(), Arrays.copyOf(data, 7));
        ResumableDownload.transfer(partial, data.length, hash(), offset -> {
            assertEquals(7, offset);
            return response(206, Arrays.copyOfRange(data, 7, data.length), "bytes 7-" + (data.length - 1) + "/" + data.length);
        }, progress);
        assertArrayEquals(data, Files.readAllBytes(partial.toPath()));
    }
    @Test public void ignoredRangeRestartsWithoutDuplicatingPrefix() throws Exception {
        File partial = temp.newFile(); Files.write(partial.toPath(), Arrays.copyOf(data, 7));
        ResumableDownload.transfer(partial, data.length, hash(), offset -> response(200, data, null), progress);
        assertArrayEquals(data, Files.readAllBytes(partial.toPath()));
    }
    @Test public void mismatchedRangePreservesExistingPartial() throws Exception {
        File partial = temp.newFile(); byte[] before = Arrays.copyOf(data, 7); Files.write(partial.toPath(), before);
        try { ResumableDownload.transfer(partial, data.length, hash(), offset -> response(206, data, "bytes 0-1/2"), progress); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("范围")); }
        assertArrayEquals(before, Files.readAllBytes(partial.toPath()));
    }
    @Test public void corruptedExistingPrefixIsRejectedAfterResume() throws Exception {
        File partial = temp.newFile(); Files.write(partial.toPath(), new byte[7]);
        try { ResumableDownload.transfer(partial, data.length, hash(), offset -> response(206,
                Arrays.copyOfRange(data, 7, data.length), "bytes 7-" + (data.length - 1) + "/" + data.length), progress); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
        assertFalse(partial.exists());
    }
    @Test public void completedPartialVerifiesWithoutNetwork() throws Exception {
        File partial = temp.newFile(); Files.write(partial.toPath(), data);
        ResumableDownload.transfer(partial, data.length, hash(), offset -> { throw new AssertionError("不应重复下载"); }, progress);
    }
    @Test public void cancelKeepsPrefixAndRetryFinishes() throws Exception {
        File partial = temp.newFile(); final boolean[] cancelled = {false};
        ResumableDownload.Progress cancel = new ResumableDownload.Progress() {
            public void check() throws IOException { if (cancelled[0]) throw new IOException("已取消"); }
            public void changed(long n, long total) { cancelled[0] = true; }
        };
        try { ResumableDownload.transfer(partial, data.length, hash(), offset -> response(200, data, null), cancel); fail(); }
        catch (IOException expected) { assertTrue(partial.length() > 0); }
        ResumableDownload.transfer(partial, data.length, hash(), offset -> response(200, data, null), progress);
        assertArrayEquals(data, Files.readAllBytes(partial.toPath()));
    }
}
