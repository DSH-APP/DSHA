package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import org.junit.Test;

/** SafPaths 纯逻辑单测：路径规范化、MIME 猜测、文本判定。 */
public final class SafPathsTest {

  @Test
  public void normalizeKeepsRelativePathsAndStripsSlashes() {
    assertEquals("", SafPaths.normalize(""));
    assertEquals("", SafPaths.normalize("/"));
    assertEquals("files/a.txt", SafPaths.normalize("/files/a.txt"));
    assertEquals("files/a.txt", SafPaths.normalize(" files/a.txt "));
    assertEquals("files/a.txt", SafPaths.normalize("files/a.txt/"));
  }

  @Test
  public void normalizeRejectsTraversalBackslashAndControl() {
    for (String path :
        new String[] {"../etc", "a/../b", "a//b", "/.", "a\\b", "a\nb", "a\tb", "a/./b"}) {
      try {
        SafPaths.normalize(path);
        fail("should reject: " + path);
      } catch (IllegalArgumentException expected) {
        // 预期拒绝
      }
    }
  }

  @Test
  public void normalizeRejectsExcessiveDepth() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < SafPaths.MAX_SEGMENTS + 1; i++) deep.append("d").append("/");
    try {
      SafPaths.normalize(deep.toString());
      fail("should reject deep path");
    } catch (IllegalArgumentException expected) {
      assertTrue(String.valueOf(expected.getMessage()).startsWith("PATH_TOO_DEEP"));
    }
  }

  @Test
  public void mimeOfGuessesCommonTypes() {
    assertEquals("text/plain", SafPaths.mimeOf("a.txt"));
    assertEquals("application/json", SafPaths.mimeOf("x.JSON"));
    assertEquals("application/x-sqlite3", SafPaths.mimeOf("data.sqlite3"));
    assertEquals("application/octet-stream", SafPaths.mimeOf("unknown.xyz"));
    assertEquals("application/octet-stream", SafPaths.mimeOf("noext"));
  }

  @Test
  public void looksTextAcceptsCleanUtf8AndRejectsBinary() {
    assertTrue(SafPaths.looksText("你好 hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertFalse(SafPaths.looksText(null));
    assertTrue("空文件是合法文本", SafPaths.looksText(new byte[0]));
    assertFalse(SafPaths.looksText(new byte[] {1, 2, 3, 0, 4})); // NUL
    // GBK 序列在 UTF-8 下必产生替换符 → 判二进制（调用方走 base64）
    byte[] gbk = "中文内容".getBytes(java.nio.charset.Charset.forName("GBK"));
    assertFalse(SafPaths.looksText(gbk));
    // 长文本只要合法就是文本；非法片段埋在中部也会被抓住（旧实现只看前 4 KB）
    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < 30000; i++) longText.append("a");
    assertTrue(
        SafPaths.looksText(longText.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    longText.setCharAt(20000, '\uFFFD');
    assertFalse(
        SafPaths.looksText(longText.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
