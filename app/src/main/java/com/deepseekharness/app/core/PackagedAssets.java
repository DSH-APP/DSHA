package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 读取 APK 内打包的文本资产；只统一换行，不解释业务，读取失败降级为空串。 */
public final class PackagedAssets {
  private PackagedAssets() {}

  public static String read(Context context, String name) {
    try {
      return readText(context.getAssets().open(name));
    } catch (Exception e) {
      String reason =
          com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e.getMessage()));
      if (reason.length() > 200) reason = reason.substring(0, 200);
      Log.w(
          "DSHA",
          "Packaged asset read failed: "
              + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(name))
              + " ("
              + e.getClass().getSimpleName()
              + ": "
              + reason
              + ")");
      return "";
    }
  }

  static String readText(InputStream source) throws IOException {
    try (InputStream in = source;
        ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      // 资产在 Windows 检出时可能是 CRLF，注入容器后脚本认不了 \r → 统一转 LF
      return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
    }
  }
}
