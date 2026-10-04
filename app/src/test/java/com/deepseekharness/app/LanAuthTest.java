package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * LAN 代理凭据逻辑的单测：token 校验、cookie 校验、请求行/URL 的 token 剥离。
 * 重构时不能改坏的不变式：
 *  - token 只从 Cookie 头或请求行 query 参数取，Referer 等其他头一律不算凭据；
 *  - 参数名精确匹配（Query.raw 的逐参数语义，后缀劫持在 util/Query 已修）；
 *  - strip 只删 token，不碰其它 query 参数、fragment 与 HTTP 版本号。
 */
public class LanAuthTest {

  private static final String TOKEN =
      "A7yZ0x9w8v7u6t5s4r3q2p1onm0lk9j8i7h6g5f4e3d2c1b0a9z8y7x6w5v4u";

  @Test
  public void cookieTokenAuthorizes() {
    int r =
        LanAuth.tokenOk(
            "GET / HTTP/1.1\r\nCookie: " + LanAuth.COOKIE_NAME + "=" + TOKEN + "\r\n\r\n", TOKEN);
    assertEquals(LanAuth.AUTH_OK, r);
  }

  @Test
  public void queryTokenAuthorizesAndRequestsSetCookie() {
    int r = LanAuth.tokenOk("GET /?token=" + TOKEN + " HTTP/1.1\r\n\r\n", TOKEN);
    assertEquals(LanAuth.AUTH_OK_SET_COOKIE, r);
  }

  @Test
  public void wrongTokenDenied() {
    assertEquals(
        LanAuth.AUTH_DENY,
        LanAuth.tokenOk(
            "GET /?token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA HTTP/1.1\r\n\r\n", TOKEN));
  }

  @Test
  public void missingTokenDenied() {
    assertEquals(LanAuth.AUTH_DENY, LanAuth.tokenOk("GET / HTTP/1.1\r\n\r\n", TOKEN));
  }

  @Test
  public void emptyTokenDenied() {
    // 「参数存在但值为空」走 fail-closed 拒绝路径，不能当没带凭据放行
    assertEquals(LanAuth.AUTH_DENY, LanAuth.tokenOk("GET /?token= HTTP/1.1\r\n\r\n", TOKEN));
  }

  @Test
  public void tokenInRefererIsNotACredential() {
    // 凭据只认 Cookie 头与请求行 query，Referer 里的 token 不能算数
    int r =
        LanAuth.tokenOk("GET / HTTP/1.1\r\nReferer: http://x/?token=" + TOKEN + "\r\n\r\n", TOKEN);
    assertEquals(LanAuth.AUTH_DENY, r);
  }

  @Test
  public void suffixParameterDoesNotHijackToken() {
    // 后缀劫持回归：?token2= 或 ?x_token= 不能当 token 用（util/Query 的逐参数比较）
    String head = "GET /?token2=" + TOKEN + "&x_token=" + TOKEN + " HTTP/1.1\r\n\r\n";
    assertEquals(LanAuth.AUTH_DENY, LanAuth.tokenOk(head, TOKEN));
  }

  @Test
  public void stripTokenFromRequestLineKeepsOtherParams() {
    String line = "GET /chat?token=" + TOKEN + "&model=x&a=1 HTTP/1.1";
    String out = LanAuth.stripTokenFromRequestLine(line);
    assertEquals("GET /chat?model=x&a=1 HTTP/1.1", out);
  }

  @Test
  public void stripTokenFromRequestLinePreservesFragmentAndVersion() {
    String line = "GET /p?a=1&token=" + TOKEN + "#frag HTTP/1.1";
    assertEquals("GET /p?a=1#frag HTTP/1.1", LanAuth.stripTokenFromRequestLine(line));
  }

  @Test
  public void stripTokenFromUrl() {
    String out = LanAuth.stripTokenFromUrl("http://192.168.1.5:3081/?token=" + TOKEN + "&x=1");
    assertEquals("http://192.168.1.5:3081/?x=1", out);
  }

  @Test
  public void stripTokenOnlyRemovesToken() {
    String url = "http://h:3081/?token=" + TOKEN;
    assertFalse(LanAuth.stripTokenFromUrl(url).contains("token="));
    // 不误删其它同名相近参数
    String url2 = "http://h:3081/?token2=keep&tokens=keep2";
    assertNotNull(LanAuth.stripTokenFromUrl(url2));
    assertTrue(LanAuth.stripTokenFromUrl(url2).contains("token2=keep"));
    assertTrue(LanAuth.stripTokenFromUrl(url2).contains("tokens=keep2"));
  }

  @Test
  public void constantTimeEqualsIsLengthAware() {
    assertTrue(LanAuth.constantTimeEquals("abc", "abc"));
    assertFalse(LanAuth.constantTimeEquals("abc", "abcd"));
    assertFalse(LanAuth.constantTimeEquals("abc", "abx"));
    assertFalse(LanAuth.constantTimeEquals(null, "abc"));
  }

  @Test
  public void closingLanRevokesOldCookieAndAddress() {
    var state = new com.deepseekharness.app.util.LanTokenState();
    String old = state.ensure(() -> "a".repeat(43));
    assertEquals(old, state.ensure(() -> "b".repeat(43)));
    String request = "GET / HTTP/1.1\r\nCookie: dsha_lan=" + old + "\r\n\r\n";
    assertEquals(LanAuth.AUTH_OK, LanAuth.tokenOk(request, state.current()));
    state.revoke();
    assertEquals(LanAuth.AUTH_DENY, LanAuth.tokenOk(request, state.current()));
    String current = state.ensure(() -> "b".repeat(43));
    assertEquals(LanAuth.AUTH_DENY, LanAuth.tokenOk(request, current));
    assertEquals(
        LanAuth.AUTH_DENY, LanAuth.tokenOk("GET /?token=" + old + " HTTP/1.1\r\n\r\n", current));
    assertFalse(LanAuth.stripTokenFromUrl("/?token=" + current).contains(current));
  }
}
