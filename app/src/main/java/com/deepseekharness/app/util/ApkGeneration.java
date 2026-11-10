package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;

/**
 * 覆盖安装代次标记：guest 侧「内置插件默认禁用」策略用它判断用户是否在当前安装里显式启用。
 *
 * <p>每次 APK 覆盖更新（versionCode 变化）都必须让默认禁用的内置插件重新收敛为禁用；只重建受管资产、不换 APK 的维护不改变本代次。宿主在插件注册前把标记写入 guest 的
 * /usr/local/share/dsha/apk-generation，注册器读取后与 node_modules/&lt;name&gt;.enabled 里的记录比对。
 */
public final class ApkGeneration {
  private static final String PREFIX = "DSHA_APK_GENERATION_V1\n";

  private ApkGeneration() {}

  public static boolean valid(int versionCode) {
    return versionCode > 0;
  }

  public static String marker(int versionCode) {
    if (!valid(versionCode)) throw new IllegalArgumentException("versionCode");
    return PREFIX + versionCode + "\n";
  }

  public static byte[] bytes(int versionCode) {
    return marker(versionCode).getBytes(StandardCharsets.US_ASCII);
  }
}
