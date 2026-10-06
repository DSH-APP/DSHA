package com.deepseekharness.app.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 屏幕上显示访问链接时遮住凭据参数；完整值只进剪贴板。纯逻辑，无 Android 依赖。 */
public final class AddressMask {
  private static final Pattern SECRET = Pattern.compile("([?&](?:token|auth|key)=)([^&#]*)");

  private AddressMask() {}

  /** 保留参数名和值的前 4 位，其余替换为圆点；短于等于 4 位的值整段遮住。 */
  public static String mask(String url) {
    if (url == null) return "";
    Matcher m = SECRET.matcher(url);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String value = m.group(2);
      String shown = value.length() <= 4 ? "••••" : value.substring(0, 4) + "••••";
      m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + shown));
    }
    m.appendTail(out);
    return out.toString();
  }
}
