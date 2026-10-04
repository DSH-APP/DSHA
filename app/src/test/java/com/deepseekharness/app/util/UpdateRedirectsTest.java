package com.deepseekharness.app.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class UpdateRedirectsTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  static class Response extends HttpURLConnection {
    final int code;
    final String location;
    boolean closed;
    final byte[] bytes;

    Response(String address, int code, String location, byte[] bytes) throws Exception {
      super(new URL(address));
      this.code = code;
      this.location = location;
      this.bytes = bytes;
    }

    public int getResponseCode() {
      return code;
    }

    public String getHeaderField(String name) {
      return name.equals("Location")
          ? location
          : name.equals("Content-Length")
              ? String.valueOf(bytes.length)
              : name.equals("Content-Range") ? "bytes 3-5/6" : null;
    }

    public InputStream getInputStream() {
      return new ByteArrayInputStream(bytes);
    }

    public void disconnect() {
      closed = true;
    }

    public boolean usingProxy() {
      return false;
    }

    public void connect() {}
  }

  @Test
  public void maliciousRedirectIsRejectedBeforeAnyExternalOpen() throws Exception {
    List<String> opened = new ArrayList<>();
    Response first =
        new Response(
            "https://dsha.cc/downloads/a.apk", 302, "https://evil.example/a.apk", new byte[0]);
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                UpdateRedirects.open(
                    first.getURL().toString(),
                    0,
                    "DSHA/test",
                    url -> {
                      opened.add(url);
                      return first;
                    },
                    () -> {}));
    assertEquals("UPDATE_REDIRECT_NOT_ALLOWED", failure.getMessage());
    assertEquals(1, opened.size());
    assertTrue(first.closed);
  }

  @Test
  public void officialCdnPreservesRangeThenRealTransferVerifiesWholeFile() throws Exception {
    String start = "https://github.com/DSH-APP/DSHA/releases/download/v1/a.apk",
        cdn =
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/a?signature=synthetic";
    Response first = new Response(start, 302, cdn, new byte[0]),
        last = new Response(cdn, 206, null, "def".getBytes());
    File partial = temp.newFile();
    Files.writeString(partial.toPath(), "abc");
    String hash = FileIntegrity.copy(new ByteArrayInputStream("abcdef".getBytes()), null, 6).sha256;
    ResumableDownload.transfer(
        partial,
        6,
        hash,
        offset ->
            UpdateRedirects.open(
                start, offset, "DSHA/test", url -> url.equals(start) ? first : last, () -> {}),
        new ResumableDownload.Progress() {
          public void check() {}

          public void changed(long n, long total) {}
        });
    assertEquals("abcdef", Files.readString(partial.toPath()));
    assertEquals("bytes=3-", first.getRequestProperty("Range"));
    assertEquals("bytes=3-", last.getRequestProperty("Range"));
    assertTrue(first.closed);
    assertTrue(last.closed);
  }

  @Test
  public void cancellationAndUnknownOpenFailureDoNotSwitchSource() throws Exception {
    int[] opens = {0};
    assertThrows(
        IOException.class,
        () ->
            UpdateRedirects.open(
                "https://dsha.cc/downloads/a.apk",
                0,
                "DSHA/test",
                url -> {
                  opens[0]++;
                  throw new IOException("UNKNOWN");
                },
                () -> {}));
    assertEquals(1, opens[0]);
    assertThrows(
        IOException.class,
        () ->
            UpdateRedirects.open(
                "https://dsha.cc/downloads/a.apk",
                0,
                "DSHA/test",
                url -> {
                  opens[0]++;
                  throw new AssertionError();
                },
                () -> {
                  throw new IOException("CANCELLED");
                }));
    assertEquals(1, opens[0]);
  }

  @Test
  public void redirectLoopIsBoundedAndEveryConnectionIsClosed() throws Exception {
    List<Response> opened = new ArrayList<>();
    assertThrows(
        IOException.class,
        () ->
            UpdateRedirects.open(
                "https://dsha.cc/downloads/a.apk",
                0,
                "DSHA/test",
                url -> {
                  Response r = new Response(url, 302, "a.apk", new byte[0]);
                  opened.add(r);
                  return r;
                },
                () -> {}));
    assertEquals(6, opened.size());
    for (Response r : opened) assertTrue(r.closed);
  }
}
