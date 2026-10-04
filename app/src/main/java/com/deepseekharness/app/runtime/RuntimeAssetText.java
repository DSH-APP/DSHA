package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 有界读取签名文本资产，交给 guest 脚本前统一换行。 */
final class RuntimeAssetText {
  static String read(Context context, String name) {
    try (InputStream input = context.getAssets().open(name);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (bytes.size() + count > 2 * 1024 * 1024)
          throw new IOException("RUNTIME_TEXT_ASSET_LIMIT");
        bytes.write(buffer, 0, count);
      }
      return bytes.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
    } catch (IOException error) {
      RuntimeHostPorts.fromOwner(context.getApplicationContext())
          .record("RUNTIME_ASSET_TEXT_UNAVAILABLE", name + ":" + error.getClass().getSimpleName());
      return "";
    }
  }

  private RuntimeAssetText() {}
}
