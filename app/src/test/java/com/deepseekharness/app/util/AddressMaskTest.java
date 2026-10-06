package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** 锁定访问链接在屏幕上的凭据遮挡。 */
public final class AddressMaskTest {
  @Test
  public void masksTokenKeepingPrefix() {
    assertEquals(
        "http://192.168.1.2:3081/?token=abcd••••",
        AddressMask.mask("http://192.168.1.2:3081/?token=abcdef0123456789"));
  }

  @Test
  public void masksEveryCredentialParameterAndKeepsOthers() {
    String shown = AddressMask.mask("http://127.0.0.1:3080/?auth=secretvalue&x=1#frag");
    assertEquals("http://127.0.0.1:3080/?auth=secr••••&x=1#frag", shown);
  }

  @Test
  public void shortValuesAreFullyHidden() {
    assertEquals("http://h/?token=••••", AddressMask.mask("http://h/?token=ab"));
  }

  @Test
  public void suffixLookalikeIsNotTreatedAsCredential() {
    // 只匹配参数名边界：xtoken= 不是 token=。
    assertEquals("http://h/?xtoken=plain", AddressMask.mask("http://h/?xtoken=plain"));
  }

  @Test
  public void nullAndPlainUrlsAreSafe() {
    assertEquals("", AddressMask.mask(null));
    assertEquals("http://127.0.0.1:3080/", AddressMask.mask("http://127.0.0.1:3080/"));
    assertFalse(AddressMask.mask("http://h/?token=abcdef").contains("ef"));
  }
}
