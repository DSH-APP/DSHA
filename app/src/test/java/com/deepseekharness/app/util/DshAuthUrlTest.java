package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** DshAuthUrl 的解析边界：只认固定 loopback 端点 + 43 字符 base64url token。 */
public class DshAuthUrlTest {

    private static final String TOKEN_43 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 个 'A'

    @Test
    public void parsesExactUrl() {
        DshAuthUrl.Parsed p = DshAuthUrl.parse(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43);
        assertNotNull(p);
        assertEquals(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43, p.authUrl);
        assertEquals(TOKEN_43, p.token);
    }

    @Test
    public void rejectsWrongAuthority() {
        assertNull(DshAuthUrl.parse("http://localhost:3080/?token=" + TOKEN_43));
        assertNull(DshAuthUrl.parse("https://127.0.0.1:3080/?token=" + TOKEN_43));
        assertNull(DshAuthUrl.parse("http://192.168.1.5:3080/?token=" + TOKEN_43));
    }

    @Test
    public void acceptsCustomPort() {
        DshAuthUrl.Parsed p = DshAuthUrl.parse("http://127.0.0.1:8080/?token=" + TOKEN_43);
        assertNotNull(p);
        assertEquals("http://127.0.0.1:8080/", p.loopbackBaseUrl);
        assertEquals("http://127.0.0.1:8080/?token=" + TOKEN_43, p.authUrl);
    }

    @Test public void rejectsIncompleteOverlongTokensAndInvalidPorts() {
        assertNull(DshAuthUrl.findAny(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43.substring(0, 25)));
        assertNull(DshAuthUrl.findAny(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43 + "Z"));
        assertNull(DshAuthUrl.findAny(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43 + "&token=other"));
        assertNull(DshAuthUrl.parse("http://127.0.0.1:0/?token=" + TOKEN_43));
        assertNull(DshAuthUrl.parse("http://127.0.0.1:999999/?token=" + TOKEN_43));
        assertNull(DshAuthUrl.findAny(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43, 9999));
        assertNotNull(DshAuthUrl.findAny(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43, 3080));
    }

    @Test
    public void rejectsMalformedToken() {
        assertNull(DshAuthUrl.parse(DshAuthUrl.AUTH_URL_PREFIX + "short"));
        assertNull(DshAuthUrl.parse(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43 + "extra"));
        // 42 个字符（少一位）
        assertNull(DshAuthUrl.parse(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43.substring(0, 42)));
        // 含非法字符
        assertNull(DshAuthUrl.parse(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43.substring(0, 42) + "!"));
    }

    @Test
    public void rejectsNullAndEmpty() {
        assertNull(DshAuthUrl.parse(null));
        assertNull(DshAuthUrl.parse(""));
    }

    @Test
    public void extractsFromStartupLine() {
        String line = "dsh web: " + DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43 + " (some note)\n";
        DshAuthUrl.Parsed p = DshAuthUrl.fromStartupOutput(line);
        assertNotNull(p);
        assertEquals(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43, p.authUrl);
    }

    @Test
    public void rejectsGarbageOutput() {
        assertNull(DshAuthUrl.fromStartupOutput(""));
        assertNull(DshAuthUrl.fromStartupOutput(null));
        assertNull(DshAuthUrl.fromStartupOutput("random output without marker"));
    }

    @Test
    public void findAnyMatchesLooseFormat() {
        // 宽松兜底：dsh 输出里 URL 前不是严格「dsh web: 」前缀也能找到
        String loose = "some log line ready at " + DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43 + "\n";
        assertEquals(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43, DshAuthUrl.findAny(loose));
    }

    @Test
    public void findAnyPrefersStrictButFallsBack() {
        assertEquals(DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43,
                DshAuthUrl.findAny("dsh web: " + DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43));
        assertNull(DshAuthUrl.findAny("no url here"));
        assertNull(DshAuthUrl.findAny(null));
    }

    @Test
    public void redactHidesToken() {
        String out = "dsh web: " + DshAuthUrl.AUTH_URL_PREFIX + TOKEN_43;
        String redacted = DshAuthUrl.redact(out);
        assertFalse(redacted.contains(TOKEN_43));
        assertTrue(redacted.contains("?token=***"));
        assertNull(DshAuthUrl.redact(null));
    }

    @Test
    public void extractCookieParsesRealisticSetCookie() {
        // 与 dsh sessionCookie() 同款：Expires 带逗号、HttpOnly、SameSite=Strict
        String setCookie = "dsh-auth-" + TOKEN_43 + "=v1.abc123.xyz789"
                + "; Max-Age=2592000; Path=/; Expires=Mon, 01 Sep 2026 12:34:56 GMT"
                + "; HttpOnly; SameSite=Strict";
        assertEquals("dsh-auth-" + TOKEN_43 + "=v1.abc123.xyz789",
                DshAuthUrl.extractCookieValue(setCookie));
    }

    @Test
    public void extractCookieIgnoresNonDshCookies() {
        assertNull(DshAuthUrl.extractCookieValue("session=abc; Path=/; HttpOnly"));
        assertNull(DshAuthUrl.extractCookieValue(null));
        assertNull(DshAuthUrl.extractCookieValue("not-a-cookie"));
    }

    @Test
    public void extractCookieFromHeadersFindsAmongMany() {
        java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        headers.put("Content-Type", java.util.Arrays.asList("text/plain"));
        headers.put("Set-Cookie", java.util.Arrays.asList(
                "session=abc; Path=/; HttpOnly",
                "dsh-auth-" + TOKEN_43 + "=v1.abc123.xyz789; Path=/; HttpOnly; SameSite=Strict"));
        assertEquals("dsh-auth-" + TOKEN_43 + "=v1.abc123.xyz789",
                DshAuthUrl.extractCookie(headers));
    }
}
