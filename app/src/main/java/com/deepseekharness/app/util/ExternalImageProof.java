package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable proof for an image copied into the App-private import directory. */
public final class ExternalImageProof {
  public final File file;
  public final String name;
  public final long size;
  public final String sha256;
  public final String type;

  public ExternalImageProof(File file, String name, long size, String sha256, String type) {
    this.file = file;
    this.name = name;
    this.size = size;
    this.sha256 = sha256;
    this.type = type;
  }

  public static ExternalImageProof verify(File file, File importRoot) throws IOException {
    if (file == null || importRoot == null || !file.isFile())
      throw new IOException("IMAGE_MISSING");
    File root = importRoot.getCanonicalFile();
    File current = file.getCanonicalFile();
    if (!current.getPath().startsWith(root.getPath() + File.separator))
      throw new IOException("IMAGE_OUTSIDE_IMPORT_ROOT");
    if (current.length() <= 0) throw new IOException("IMAGE_EMPTY");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (Exception error) {
      throw new IOException("SHA256_UNAVAILABLE", error);
    }
    byte[] head = new byte[4096];
    int headCount;
    try (InputStream input =
        new java.io.BufferedInputStream(new java.io.FileInputStream(current))) {
      headCount = input.read(head);
      byte[] buffer = new byte[128 * 1024];
      if (headCount > 0) digest.update(head, 0, headCount);
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    byte[] tail = new byte[(int) Math.min(4096L, current.length())];
    try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(current, "r")) {
      input.seek(Math.max(0L, current.length() - tail.length));
      input.readFully(tail);
    }
    String sha = hex(digest.digest());
    String type = ExternalImageMetadata.classify(headCount < 0 ? new byte[0] : head, tail);
    return new ExternalImageProof(current, current.getName(), current.length(), sha, type);
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
    return value.toString();
  }
}
