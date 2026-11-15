package com.deepseekharness.app.util;

/** 配置输入先整体校验，再写入，避免一半保存或静默改成另一个值。 */
public final class ConfigInput {
  private ConfigInput() {}

  public static int port(String value) {
    int port = number(value, com.deepseekharness.app.util.UiText.text("端口请输入 1—65535 的整数"));
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException(
          com.deepseekharness.app.util.UiText.text("端口请输入 1—65535 的整数"));
    if (WebPortPolicy.reserved(port))
      throw new IllegalArgumentException(
          com.deepseekharness.app.util.UiText.choose(
              "3081、3082 和 3090–3099 已用于 App 桥接，请换一个端口",
              "3081, 3082 and 3090–3099 are reserved for app bridges. Choose another port."));
    return port;
  }

  private static int number(String value, String error) {
    try {
      return Integer.parseInt(value == null ? "" : value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(error);
    }
  }
}
