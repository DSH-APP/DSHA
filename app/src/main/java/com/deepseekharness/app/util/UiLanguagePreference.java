package com.deepseekharness.app.util;

import java.util.List;
import java.util.Locale;

/** Stable saved preferences and the supported BCP-47 application locales. */
public final class UiLanguagePreference {

  public static final String SYSTEM = "system";
  public static final String ZH = "zh";
  public static final String ZH_HANT = "zh-Hant";
  public static final String EN = "en";
  public static final String RU = "ru";
  public static final String JA = "ja";
  public static final String KO = "ko";
  public static final String AR = "ar";
  public static final String PT = "pt";
  public static final String LA = "la";
  public static final String FR = "fr";

  /** The menu and generated dictionaries share this order; callers cannot mutate it. */
  public static final List<String> LANGUAGES = List.of(ZH, ZH_HANT, EN, RU, JA, KO, AR, PT, LA, FR);

  private UiLanguagePreference() {}

  /** Preserve explicit historical zh/en choices; absent or invalid preferences follow the system. */
  public static String normalize(String value) {
    return isSupportedLanguage(value) ? value : SYSTEM;
  }

  public static boolean supported(String value) {
    return SYSTEM.equals(value) || isSupportedLanguage(value);
  }

  /** Only canonical, resolved languages may enter resource writers and script bridges. */
  public static boolean isSupportedLanguage(String value) {
    return value != null && LANGUAGES.contains(value);
  }

  public static boolean followsSystem(String value) {
    return SYSTEM.equals(normalize(value));
  }

  /** Map a valid locale tag to an available language, or return null when it is unavailable. */
  public static String canonicalLanguage(String languageTag) {
    if (!validTag(languageTag)) return null;
    Locale locale = Locale.forLanguageTag(languageTag.trim().replace('_', '-'));
    String primary = locale.getLanguage();
    if (ZH.equals(primary)) {
      String script = locale.getScript(), region = locale.getCountry();
      if ("Hant".equals(script)) return ZH_HANT;
      if ("Hans".equals(script)) return ZH;
      return "TW".equals(region) || "HK".equals(region) || "MO".equals(region) ? ZH_HANT : ZH;
    }
    return isSupportedLanguage(primary) ? primary : null;
  }

  /** Unknown valid system languages use English; an unavailable system source retains Chinese. */
  public static String resolveLanguage(String languageTag) {
    if (!validTag(languageTag)) return ZH;
    String available = canonicalLanguage(languageTag);
    return available == null ? EN : available;
  }

  public static String resolve(String preference, String languageTag) {
    String chosen = normalize(preference);
    return SYSTEM.equals(chosen) ? resolveLanguage(languageTag) : chosen;
  }

  public static boolean isChinese(String languageTag) {
    String canonical = canonicalLanguage(languageTag);
    return ZH.equals(canonical) || ZH_HANT.equals(canonical);
  }

  public static boolean isRtl(String language) {
    return AR.equals(canonicalLanguage(language));
  }

  /** Native names keep the selector discoverable in every interface language. */
  public static String nativeName(String language) {
    if (ZH.equals(language)) return "简体中文";
    if (ZH_HANT.equals(language)) return "繁體中文";
    if (EN.equals(language)) return "English";
    if (RU.equals(language)) return "Русский";
    if (JA.equals(language)) return "日本語";
    if (KO.equals(language)) return "한국어";
    if (AR.equals(language)) return "العربية";
    if (PT.equals(language)) return "Português";
    if (LA.equals(language)) return "Latina";
    if (FR.equals(language)) return "Français";
    throw new IllegalArgumentException("UI_LANGUAGE");
  }

  private static boolean validTag(String tag) {
    return tag != null
        && !"und".equalsIgnoreCase(tag.trim())
        && tag.trim().matches("(?i)[a-z]{2,8}([_-][a-z0-9]{1,8})*");
  }
}
