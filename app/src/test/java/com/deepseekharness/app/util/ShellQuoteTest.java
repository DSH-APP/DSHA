package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** ShellQuote 的安全边界断言：恶意/畸形值必须被字面量化，不能逃逸出单引号。 */
public class ShellQuoteTest {

  @Test
  public void plainValueIsSingleQuoted() {
    assertEquals("'hello'", ShellQuote.arg("hello"));
  }

  @Test
  public void nullBecomesEmptyQuotedArg() {
    assertEquals("''", ShellQuote.arg(null));
  }

  @Test
  public void singleQuoteIsEscapedPosixStyle() {
    // POSIX：单引号内嵌单引号 → ' + '\'' + '
    assertEquals("'a'\\''b'", ShellQuote.arg("a'b"));
  }

  @Test
  public void shellMetacharactersAreLiteral() {
    // 这些字符如果在单引号外会被 shell 解释，这里必须原样封住
    assertEquals("'a;b$(rm -rf /)`c`d${x}e'", ShellQuote.arg("a;b$(rm -rf /)`c`d${x}e"));
  }

  @Test
  public void roundTripOnPluginLikeValue() {
    String pluginName = "@deepseek-ai/dsh-foo@1.0.0 ' OR 1=1 --";
    String quoted = ShellQuote.arg(pluginName);
    assertEquals(pluginName, parseLiteralWord(quoted));
    for (String value :
        new String[] {"", "'", "two '' quotes", "line\nnext", "$(touch x);`id`\\end", "中文 é"})
      assertEquals(value, parseLiteralWord(ShellQuote.arg(value)));
  }

  /** Independent POSIX literal-word scanner: quote scopes/backslash escapes,
   * rejecting unquoted whitespace, expansion or command syntax. */
  private static String parseLiteralWord(String word) {
    StringBuilder value = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      if (quote == '\'') {
        if (c == '\'') quote = 0;
        else value.append(c);
        continue;
      }
      if (c == '\\') {
        if (++i == word.length()) throw new AssertionError("unfinished escape");
        value.append(word.charAt(i));
        continue;
      }
      if (c == '\'' && quote == 0) {
        quote = c;
        continue;
      }
      if (c == '"') {
        quote = quote == '"' ? 0 : '"';
        continue;
      }
      if (c == '$'
          || c == '`'
          || quote == 0 && (Character.isWhitespace(c) || ";&|<>()".indexOf(c) >= 0))
        throw new AssertionError("non-literal shell syntax");
      value.append(c);
    }
    if (quote != 0) throw new AssertionError("unfinished quote");
    return value.toString();
  }
}
