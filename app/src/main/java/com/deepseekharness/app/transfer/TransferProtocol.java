package com.deepseekharness.app.transfer;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure checks shared by the persistent downloader and its hostile-response fixtures. */
public final class TransferProtocol {
  public static final int CONNECT_TIMEOUT_MS = 15_000;
  public static final int SOURCE_VERIFY_READ_TIMEOUT_MS = 15 * 60 * 1000;
  public static final int CONTENT_READ_TIMEOUT_MS = 30_000;
  public static final int RELEASE_TIMEOUT_MS = 5_000;
  private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

  private TransferProtocol() {}

  /** Returns the actual offset; a server ignoring Range must replace the whole partial. */
  public static long responseOffset(
      int code, long requested, long size, String range, String length, String etag, String sha256)
      throws IOException {
    if (requested < 0 || requested > size || size < 0 || size > TransferArtifact.MAX_BYTES)
      throw new IOException("FILE_RANGE_INVALID");
    if (code != 200 && code != 206) throw new IOException("FILE_HTTP_" + code);
    if (!('"' + sha256 + '"').equals(etag)) throw new IOException("FILE_SOURCE_CHANGED");
    long offset;
    if (code == 206) {
      Matcher match = RANGE.matcher(range == null ? "" : range);
      try {
        if (!match.matches()
            || Long.parseLong(match.group(1)) != requested
            || Long.parseLong(match.group(2)) != size - 1
            || Long.parseLong(match.group(3)) != size) throw new IOException("FILE_RANGE_INVALID");
      } catch (NumberFormatException overflow) {
        throw new IOException("FILE_RANGE_INVALID");
      }
      offset = requested;
    } else if (code == 200) {
      offset = 0;
    } else {
      throw new IOException("FILE_HTTP_" + code);
    }
    try {
      if (length == null || Long.parseLong(length) != size - offset)
        throw new IOException("FILE_LENGTH_INVALID");
    } catch (NumberFormatException invalid) {
      throw new IOException("FILE_LENGTH_INVALID");
    }
    return offset;
  }

  public static long requiredSpace(long size, long partial, boolean includesFinalCopy)
      throws IOException {
    if (size < 0 || size > TransferArtifact.MAX_BYTES || partial < 0 || partial > size)
      throw new IOException("FILE_SIZE_INVALID");
    return size - partial + (includesFinalCopy ? size : 0) + 16L * 1024 * 1024;
  }
}
