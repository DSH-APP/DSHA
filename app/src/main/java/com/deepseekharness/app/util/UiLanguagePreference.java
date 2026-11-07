package com.deepseekharness.app.util;

/**
 * 界面语言偏好：<b>跟随系统</b> / 中文 / 英文 / 阿拉伯语。
 *
 * <p>构建契约见 AGENTS.md「中英文界面默认中文」：仅当用户显式选择语言时才偏离系统，
 * 其余情况都跟随系统 —— 系统是阿拉伯语落阿拉伯语；系统是中文（或无法识别）落中文，
 * 系统的首选语言是英文落英文，其他语言也落英文（英语是唯一可用的国际语言，比中文更可能被看懂）。
 *
 * <p>偏好值只有四个：{@code system}（默认）、{@code zh}、{@code en}、{@code ar}。旧版本只写过
 * {@code zh}/{@code en}，所以老用户升级后保持原语言；从未设置过的用户视为 {@code system}。
 */
public final class UiLanguagePreference {

  /** 跟随系统语言（默认值）。 */
  public static final String SYSTEM = "system";

  /** 简体中文。 */
  public static final String ZH = "zh";

  /** 英文。 */
  public static final String EN = "en";

  /** 阿拉伯语。 */
  public static final String AR = "ar";

  private UiLanguagePreference() {}

  /** 规范化偏好值：只认显式选择的 en/zh/ar，其余（含 null、旧脏值）一律跟随系统。 */
  public static String normalize(String value) {
    if (EN.equals(value)) return EN;
    if (ZH.equals(value)) return ZH;
    if (AR.equals(value)) return AR;
    return SYSTEM;
  }

  /** 这个值是否是可保存的偏好（四个之一）。 */
  public static boolean supported(String value) {
    return SYSTEM.equals(value) || ZH.equals(value) || EN.equals(value) || AR.equals(value);
  }

  /** 偏好是否表示「跟随系统」。 */
  public static boolean followsSystem(String value) {
    return SYSTEM.equals(normalize(value));
  }

  /**
   * 把系统首选语言映射到应用实际语言（只有 {@code zh} 与 {@code en}）。
   *
   * <p>规则：首选语言是中文（{@code zh}、{@code zh-CN}、{@code zh-Hant}…）→ 中文；
   * 无法识别 → 中文；其他语言（含 {@code en}、{@code fr}）→ 英文。
   *
   * @param languageTag 系统 Locale 的 BCP-47 标签，可以为 null
   */
  public static String resolveLanguage(String languageTag) {
    if (languageTag == null
        || languageTag.trim().isEmpty()
        || "und".equalsIgnoreCase(languageTag.trim())
        || !languageTag.trim().matches("(?i)[a-z]{2,8}([_-][a-z0-9]{1,8})*")) return ZH;
    if (isArabic(languageTag)) return AR;
    return isChinese(languageTag) ? ZH : EN;
  }

  /** 由偏好 + 系统语言算出实际生效语言。 */
  public static String resolve(String preference, String languageTag) {
    String chosen = normalize(preference);
    return SYSTEM.equals(chosen) ? resolveLanguage(languageTag) : chosen;
  }

  /** 语言标签的主语言子标签是否为 {@code zh}（大小写不敏感）。 */
  public static boolean isChinese(String languageTag) {
    return "zh".equalsIgnoreCase(primarySubtag(languageTag));
  }

  /** 语言标签的主语言子标签是否为 {@code ar}（大小写不敏感）。 */
  public static boolean isArabic(String languageTag) {
    return "ar".equalsIgnoreCase(primarySubtag(languageTag));
  }

  /** 该生效语言是否从右到左书写。 */
  public static boolean isRtl(String language) {
    return AR.equals(language);
  }

  /** 语言标签的主语言子标签（{@code zh-CN} → {@code zh}），无法解析时为 {@code null}。 */
  private static String primarySubtag(String languageTag) {
    if (languageTag == null) return null;
    String value = languageTag.trim();
    if (value.isEmpty()) return null;
    int dash = value.indexOf('-');
    if (dash < 0) dash = value.indexOf('_');
    return dash < 0 ? value : value.substring(0, dash);
  }
}
