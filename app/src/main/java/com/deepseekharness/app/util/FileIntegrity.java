package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 有界流复制和摘要，供归档、URI 导出验证共用。 */
public final class FileIntegrity {
  private FileIntegrity() {}

  public interface Check {
    void unchanged() throws IOException;
  }

  public static final class Result {
    public final long size;
    public final String sha256;

    Result(long size, String sha256) {
      this.size = size;
      this.sha256 = sha256;
    }

    public boolean matches(Result other) {
      return other != null && size == other.size && sha256.equals(other.sha256);
    }
  }

  public static Result copy(InputStream in, OutputStream out, long limit) throws IOException {
    return copy(in, out, limit, () -> {});
  }

  public static Result copy(InputStream in, OutputStream out, long limit, Check check)
      throws IOException {
    if (in == null || limit < 0)
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取文件"));
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
    byte[] block = new byte[64 * 1024];
    long size = 0;
    int count;
    check.unchanged();
    while ((count = in.read(block)) != -1) {
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("CANCELLED");
      check.unchanged();
      if (count > limit - size)
        throw new IOException(com.deepseekharness.app.util.UiText.text("文件超出允许大小"));
      if (out != null) out.write(block, 0, count);
      digest.update(block, 0, count);
      size += count;
    }
    check.unchanged();
    if (out != null) out.flush();
    check.unchanged();
    StringBuilder hex = new StringBuilder(64);
    for (byte value : digest.digest())
      hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return new Result(size, hex.toString());
  }
}
