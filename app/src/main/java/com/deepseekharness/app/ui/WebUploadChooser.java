package com.deepseekharness.app.ui;

import android.content.Intent;
import java.util.LinkedHashSet;
import java.util.Locale;

/** 网页附件直接进入系统文档选择器；图片专用请求保留内核提供的选择流程。 */
public final class WebUploadChooser {
  private WebUploadChooser() {}

  public static Intent intent(Intent primary, boolean multiple, String[] acceptTypes) {
    if (primary != null && onlyImages(acceptTypes)) {
      primary.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
      return primary;
    }
    // 部分 ROM 将 */* 的 GET_CONTENT 交给照片入口，再转到文档选择器。
    // 附件直接使用 SAF；不指定包名，也不插入会误导 MIME 分发的图片候选。
    String[] types = normalized(acceptTypes);
    Intent document =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType(types))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (types.length > 1) document.putExtra(Intent.EXTRA_MIME_TYPES, types);
    return document;
  }

  static boolean onlyImages(String[] acceptTypes) {
    String[] types = normalized(acceptTypes);
    if (types.length == 0) return false;
    for (String type : types) if (!type.startsWith("image/")) return false;
    return true;
  }

  static String mimeType(String[] acceptTypes) {
    String[] types = normalized(acceptTypes);
    return types.length == 1 ? types[0] : "*/*";
  }

  static String[] normalized(String[] acceptTypes) {
    LinkedHashSet<String> types = new LinkedHashSet<>();
    if (acceptTypes != null)
      for (String field : acceptTypes) {
        if (field == null) continue;
        for (String token : field.split(",")) {
          String type = token.trim().toLowerCase(Locale.ROOT);
          if ("*".equals(type) || "*/*".equals(type)) return new String[] {"*/*"};
          if (!type.isEmpty()) types.add(type);
        }
      }
    return types.toArray(new String[0]);
  }
}
