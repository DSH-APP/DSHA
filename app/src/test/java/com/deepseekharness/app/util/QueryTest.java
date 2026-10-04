package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Query 的参数解析边界：逐参数名匹配，且区分「不存在」与「存在但为空」。 */
public class QueryTest {

  @Test
  public void ofCutsAtQuestionMark() {
    assertEquals("a=1&b=2", Query.of("/exec?a=1&b=2"));
    assertEquals("", Query.of("/exec"));
    assertEquals("", Query.of(null));
  }

  @Test
  public void rawFindsExactParamName() {
    assertEquals("200", Query.raw("key=abc&y=200", "y"));
  }

  @Test
  public void rawIsNotHijackedBySuffix() {
    // 回归：indexOf(key+"=") 会把 key=abc 里的 "y=" 误命中
    assertEquals("200", Query.raw("key=abc&y=200", "y"));
    assertEquals("real", Query.raw("xtext=junk&text=real", "text"));
  }

  @Test
  public void rawReturnsNullWhenMissing() {
    assertNull(Query.raw("a=1", "nope"));
    assertNull(Query.raw(null, "a"));
    assertNull(Query.raw("a=1", null));
  }

  @Test
  public void emptyValueDistinctFromMissing() {
    // ?token= 是「存在但为空」，token 校验要据此 fail-closed，而不是当没带凭据
    assertEquals("", Query.raw("token=", "token"));
    assertNull(Query.raw("other=1", "token"));
  }

  @Test
  public void fragmentIsIgnored() {
    assertEquals("200", Query.raw("y=200#frag", "y"));
  }

  @Test
  public void paramDecodesUtf8() {
    assertEquals("a b", Query.param("q=a+b", "q", ""));
    assertEquals("你", Query.param("q=%E4%BD%A0", "q", ""));
  }

  @Test
  public void paramFallsBackOnMalformed() {
    assertEquals("def", Query.param("q=%ZZ", "q", "def"));
    assertEquals("def", Query.param("q=1", "missing", "def"));
  }
}
