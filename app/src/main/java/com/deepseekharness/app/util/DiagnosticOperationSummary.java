package com.deepseekharness.app.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognized parser exceptions omit quoted source. Other free-form errors retain redacted text. */
public final class DiagnosticOperationSummary {
  private DiagnosticOperationSummary() {}

  public static String safe(String text) {
    String original = text == null ? "" : text;
    String value = decodeMarkers(original);
    String kind =
        Pattern.compile(
                    "YAML(?:Exception|ParseError|SyntaxError)|org\\.yaml|snakeyaml|failed to parse (?:overlay|patches)",
                    Pattern.CASE_INSENSITIVE)
                .matcher(value)
                .find()
            ? "YAML"
            : Pattern.compile(
                        "JSONException|Json(?:Parse|Syntax|Processing|EOF)Exception|MalformedJsonException|JSON\\.parse|SyntaxError.*JSON",
                        Pattern.CASE_INSENSITIVE)
                    .matcher(value)
                    .find()
                ? "JSON"
                : Pattern.compile(
                            "SAXParseException|XmlPullParserException|XMLParseError|<parsererror",
                            Pattern.CASE_INSENSITIVE)
                        .matcher(value)
                        .find()
                    ? "XML"
                    : value.contains("SyntaxError")
                        ? "SYNTAX"
                        : Pattern.compile(
                                    "(?:Parser|Scanner)(?:Exception|Error)|failed to parse (?:configuration|config)",
                                    Pattern.CASE_INSENSITIVE)
                                .matcher(value)
                                .find()
                            ? "PARSER"
                            : "";
    if (kind.isEmpty()) return SensitiveData.redact(original);
    String target =
        value.contains("/profiles/web/cordis.patch.yml")
            ? "WEB_PROFILE_PATCH"
            : value.contains("/profiles/") && value.contains("cordis.patch.yml")
                ? "PROFILE_PATCH"
                : value.contains("cordis.patch.yml")
                    ? "GLOBAL_OR_BUNDLE_PATCH"
                    : value.contains("settings.yaml") ? "SETTINGS" : "UNSPECIFIED";
    String reason = "PARSER_REJECTED";
    String[][] known = {
      {"end of the stream or a document separator is expected", "YAML_DOCUMENT_BOUNDARY"},
      {"duplicated mapping key", "YAML_DUPLICATE_KEY"},
      {"bad indentation", "YAML_INDENTATION"},
      {"unknown tag", "YAML_UNKNOWN_TAG"},
      {"unidentified alias", "YAML_UNKNOWN_ALIAS"},
      {"unknown escape sequence", "INVALID_ESCAPE"},
      {"non-printable characters", "INVALID_CHARACTER"},
      {"Unexpected end", "UNEXPECTED_END"},
      {"Unexpected token", "UNEXPECTED_TOKEN"}
    };
    for (String[] item : known)
      if (value.contains(item[0])) {
        reason = item[1];
        break;
      }
    String position = "";
    Matcher mark = Pattern.compile("\\(([0-9]{1,7}):([0-9]{1,7})\\)").matcher(value);
    if (mark.find()) position = " line=" + mark.group(1) + " column=" + mark.group(2);
    else {
      Matcher offset =
          Pattern.compile("(?:at position|position[:=]?) ([0-9]{1,7})\\b").matcher(value);
      if (offset.find()) position = " position=" + offset.group(1);
    }
    return "PARSER_FAILURE type=" + kind + " target=" + target + " reason=" + reason + position;
  }

  /** Applies to existing stored records too; no rewrite of the private original is needed. */
  public static String history(String raw) {
    StringBuilder result = new StringBuilder();
    Pattern event = Pattern.compile("^([0-9-]+ [0-9:]+ \\[[^]\\r\\n]+] )(.*)$");
    String prefix = "";
    StringBuilder body = new StringBuilder();
    for (String line : (raw == null ? "" : raw).split("\\r?\\n")) {
      if (line.isEmpty()) continue;
      Matcher matched = event.matcher(line);
      if (matched.matches()) {
        append(result, prefix, body);
        prefix = matched.group(1);
        body.setLength(0);
        body.append(matched.group(2));
      } else {
        if (body.length() > 0) body.append('\n');
        body.append(line);
      }
    }
    append(result, prefix, body);
    return result.toString();
  }

  private static void append(StringBuilder out, String prefix, StringBuilder body) {
    if (body.length() == 0 || prefix.isEmpty()) return;
    String summary = safe(body.toString());
    // Historic continuation lines may contain config source or full stacks. Detect nested
    // parser errors using the entire record, then export only its safe one-line summary.
    int newline = summary.indexOf('\n');
    if (newline >= 0) summary = summary.substring(0, newline);
    out.append(prefix).append(summary).append('\n');
  }

  /** Decode only for recognizing parser diagnostics; no decoded source is ever returned. */
  private static String decodeMarkers(String value) {
    String decoded = value;
    Pattern escaped =
        Pattern.compile("&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));|\\\\u([0-9a-fA-F]{4})");
    for (int pass = 0; pass < 2; pass++) {
      decoded =
          decoded
              .replace("&amp;", "&")
              .replace("&lt;", "<")
              .replace("&gt;", ">")
              .replace("&quot;", "\"")
              .replace("&apos;", "'");
      Matcher match = escaped.matcher(decoded);
      StringBuffer result = new StringBuffer();
      while (match.find()) {
        String number =
            match.group(1) != null
                ? match.group(1)
                : match.group(3) != null ? match.group(3) : match.group(2);
        int codepoint = Integer.parseInt(number, match.group(2) != null ? 10 : 16);
        String replacement =
            Character.isValidCodePoint(codepoint) ? new String(Character.toChars(codepoint)) : "?";
        match.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
      match.appendTail(result);
      decoded = result.toString();
    }
    return decoded;
  }
}
