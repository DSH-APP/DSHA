package com.deepseekharness.app.util;

/** Select the platform locale writer before Activity delegates exist on Android 13+. */
public final class AppLocaleDispatch {
  private AppLocaleDispatch() {}

  public interface Writer {
    void platform(String language);

    void compat(String language);
  }

  public static void apply(int sdk, String language, Writer writer) {
    if (!UiLanguagePreference.isSupportedLanguage(language))
      throw new IllegalArgumentException("UI_LANGUAGE");
    if (sdk >= 33) writer.platform(language);
    else writer.compat(language);
  }
}
