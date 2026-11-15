package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅在应用状态的显示边界重译。匹配完整文案或登记的完整模板，参数保持原文。 */
public final class UiStateText {
  private UiStateText() {}

  private static final Map<String, String> SOURCE = new LinkedHashMap<>();
  private static final Map<String, UiMessageFormat[]> FORMATS = new LinkedHashMap<>();

  static {
    for (String language : UiLanguagePreference.LANGUAGES)
      UiMessages.forLanguage(language)
          .forEach((zh, translated) -> SOURCE.putIfAbsent(translated, zh));
  }

  private static synchronized UiMessageFormat[] formats(String language) {
    UiMessageFormat[] compiled = FORMATS.get(language);
    if (compiled != null) return compiled;
    String[] templates = UiMessages.formatsForLanguage(language);
    compiled = new UiMessageFormat[templates.length];
    for (int i = 0; i < templates.length; i++) compiled[i] = new UiMessageFormat(templates[i]);
    FORMATS.put(language, compiled);
    return compiled;
  }

  /** Exact application text only; inserted user/plugin payloads never enter this lookup. */
  static String exact(String value, String language) {
    String source = SOURCE.get(value);
    return source == null ? value : UiMessages.forLanguage(language).getOrDefault(source, value);
  }

  public static String render(String value) {
    if (value == null) return "";
    String language = UiText.language();
    String task = BackupTaskKinds.display(value, false);
    if (task != null) return UiText.text(task);
    String direct = exact(value, language);
    if (!direct.equals(value) || SOURCE.containsKey(value)) return direct;
    UiMessageFormat[] target = formats(language);
    String chosen = null;
    int literalLength = -1, arguments = Integer.MAX_VALUE;
    for (String sourceLanguage : UiLanguagePreference.LANGUAGES) {
      UiMessageFormat[] source = formats(sourceLanguage);
      for (int i = 0; i < source.length; i++) {
        String result = source[i].reformat(value, target[i]);
        if (result == null) continue;
        UiMessageFormat matched = source[i];
        // 按实际匹配语言的固定文本排序，不能让更长的英文译句抢走具体中文状态；
        // 固定文本相同则优先参数更少的完整模板，防止空的首参数吞掉另一种状态。
        if (matched.literalLength() > literalLength
            || matched.literalLength() == literalLength && matched.argumentCount() < arguments) {
          chosen = result;
          literalLength = matched.literalLength();
          arguments = matched.argumentCount();
        }
      }
    }
    return chosen == null ? value : chosen;
  }
}
