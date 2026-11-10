package com.deepseekharness.app.extdev;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** 进入 root 边界前核对 adb / fastboot / 加载器与锁定 Ubuntu 包的字节完全一致。 */
final class ExternalToolProof {
  private ExternalToolProof() {}

  static String verify(Context context, File root) {
    try {
      JSONObject proof;
      try (InputStream input = context.getAssets().open("external-tool-proof.json")) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 64 * 1024) throw new IOException("EXTERNAL_PROOF_LIMIT");
          bytes.write(buffer, 0, count);
        }
        // ByteArrayOutputStream#toString(Charset) 需 API 33；先取字节再解码以兼容 minSdk 30。
        proof =
            new JSONObject(
                new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
      }
      // schema 2 由 tools/prepare-external-tools.py 从 tools/external-tools/lock.json 生成；不再按 rootfs
      // 版本放行。
      if (proof.optInt("schema", -1) != 2) return "EXTERNAL_PROOF_SCHEMA";
      JSONObject files = proof.getJSONObject("files");
      JSONObject targets = proof.getJSONObject("targets");
      String install = proof.optString("install", "");
      String rootPath = root.getCanonicalPath() + File.separator;
      for (String path :
          new String[] {"usr/bin/adb", "usr/bin/fastboot", "usr/lib/ld-linux-aarch64.so.1"}) {
        String expected = files.optString(path, "");
        String target = targets.optString(path, "");
        if (!expected.matches("[0-9a-f]{64}")
            || !target.matches("[A-Za-z0-9_./+-]{1,200}")
            || target.contains("..")) return "EXTERNAL_PROOF_DIGEST";
        File file = new File(root, path).getCanonicalFile();
        File pinned = new File(root, target).getCanonicalFile();
        // 入口（含软链接）必须落到锁定包内的同一文件，且字节一致。
        if (!file.getPath().startsWith(rootPath)
            || !file.equals(pinned)
            || !file.isFile()
            || !expected.equals(sha256(file)))
          return "EXTERNAL_TOOL_UNVERIFIED:" + path + "；请在终端执行 " + install;
      }
      return null;
    } catch (Throwable error) {
      return "EXTERNAL_TOOL_PROOF_UNAVAILABLE";
    }
  }

  private static String sha256(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
      byte[] buffer = new byte[128 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    StringBuilder output = new StringBuilder(64);
    for (byte value : digest.digest())
      output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return output.toString();
  }
}
