package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 完整锚定的应用文案模板，只识别字符串、整数与字面量百分号。 */
final class UiMessageFormat {
  private final List<String> parts = new ArrayList<>();
  private final List<Character> conversions = new ArrayList<>();
  private final Pattern pattern;

  UiMessageFormat(String template) {
    StringBuilder literal = new StringBuilder();
    for (int i = 0; i < template.length(); i++) {
      char value = template.charAt(i);
      if (value != '%') {
        literal.append(value);
        continue;
      }
      if (++i >= template.length())
        throw new IllegalArgumentException("UI_FORMAT_TRAILING_PERCENT");
      char conversion = template.charAt(i);
      if (conversion == '%') {
        literal.append('%');
        continue;
      }
      if (conversion != 's' && conversion != 'd')
        throw new IllegalArgumentException("UI_FORMAT_UNSUPPORTED_CONVERSION");
      parts.add(literal.toString());
      literal.setLength(0);
      conversions.add(conversion);
    }
    parts.add(literal.toString());
    StringBuilder regex = new StringBuilder("\\A");
    for (int i = 0; i < conversions.size(); i++) {
      regex.append(Pattern.quote(parts.get(i)));
      regex.append(conversions.get(i) == 'd' ? "([+-]?[0-9]+)" : "(.*?)");
    }
    pattern =
        Pattern.compile(
            regex.append(Pattern.quote(parts.get(parts.size() - 1))).append("\\z").toString(),
            Pattern.DOTALL);
  }

  String reformat(String value, UiMessageFormat target) {
    if (!conversions.equals(target.conversions))
      throw new IllegalArgumentException("UI_FORMAT_ARGUMENT_MISMATCH");
    Matcher match = pattern.matcher(value);
    if (!match.matches()) return null;
    StringBuilder result = new StringBuilder(target.parts.get(0));
    for (int i = 1; i < target.parts.size(); i++)
      result.append(match.group(i)).append(target.parts.get(i));
    return result.toString();
  }

  int literalLength() {
    int length = 0;
    for (String part : parts) length += part.length();
    return length;
  }

  int argumentCount() {
    return conversions.size();
  }
}
