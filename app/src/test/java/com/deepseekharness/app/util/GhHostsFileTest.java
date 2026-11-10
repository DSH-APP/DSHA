package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class GhHostsFileTest {
  private static final String TOKEN = "gho_SECRET_TOKEN_VALUE_0123456789";

  @Test
  public void parsesUsersLayoutWithTopLevelFallback() {
    String text =
        "github.com:\n"
            + "    users:\n"
            + "        SunsetRNE:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n"
            + "    user: SunsetRNE\n"
            + "    oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertEquals("SunsetRNE", r.login);
    assertTrue(r.credentialStored);
    assertTrue(r.usersLayout);
    assertFalse(r.legacyLayout);
    assertEquals(List.of("SunsetRNE"), r.accounts);
    assertEquals(2, r.tokenFields);
  }

  @Test
  public void parsesLegacySingleAccountLayout() {
    String text =
        "github.com:\n"
            + "    user: monalisa\n"
            + "    oauth_token: "
            + TOKEN
            + "\n"
            + "    git_protocol: ssh\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertEquals("monalisa", r.login);
    assertEquals("ssh", r.gitProtocol);
    assertTrue(r.credentialStored);
    assertFalse(r.usersLayout);
    assertTrue(r.legacyLayout);
  }

  @Test
  public void adoptsOnlyAccountWhenUserKeyAbsent() {
    String text =
        "github.com:\n"
            + "    users:\n"
            + "        octocat:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals("octocat", r.login);
    assertTrue(r.credentialStored);
  }

  @Test
  public void keepsEveryAccountAndActiveUser() {
    String text =
        "github.com:\n"
            + "    users:\n"
            + "        alpha:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n"
            + "        beta: {}\n"
            + "    user: beta\n";
    // 流式空映射不是 gh 的写法，必须拒绝而不是猜。
    assertEquals(GhHostsFile.Status.MALFORMED, GhHostsFile.parse(text).status);

    String valid =
        "github.com:\n"
            + "    users:\n"
            + "        alpha:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n"
            + "        beta:\n"
            + "    user: alpha\n";
    GhHostsFile.Result r = GhHostsFile.parse(valid);
    assertEquals(List.of("alpha", "beta"), r.accounts);
    assertEquals("alpha", r.login);
    assertTrue(r.credentialStored);
  }

  @Test
  public void tokenOnAnotherAccountStillCountsAsStoredCredential() {
    String text =
        "github.com:\n"
            + "    users:\n"
            + "        alpha:\n"
            + "        beta:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n"
            + "    user: alpha\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals("alpha", r.login);
    assertTrue(r.credentialStored);
  }

  @Test
  public void emptyTextIsNotLoggedIn() {
    GhHostsFile.Result r = GhHostsFile.parse("");
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertNull(r.login);
    assertFalse(r.credentialStored);
    assertTrue(r.accounts.isEmpty());
    assertTrue(r.otherHosts.isEmpty());
  }

  @Test
  public void keepsOtherHostsButDoesNotParseThem() {
    String text =
        "enterprise.example.com:\n"
            + "    user: someone\n"
            + "    oauth_token: "
            + TOKEN
            + "\n"
            + "  strange: nesting\n"
            + "github.com:\n"
            + "    user: octocat\n"
            + "    oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertEquals("octocat", r.login);
    assertEquals(List.of("enterprise.example.com"), r.otherHosts);
  }

  @Test
  public void parsesRecordedScopesDefensively() {
    String text =
        "github.com:\n"
            + "    user: octocat\n"
            + "    scopes: repo, read:org,gist\n"
            + "    oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(List.of("repo", "read:org", "gist"), r.scopes);
  }

  @Test
  public void acceptsQuotedScalarsAndCrlf() {
    String text =
        "github.com:\r\n"
            + "    user: \"octocat\"\r\n"
            + "    git_protocol: 'https'\r\n"
            + "    oauth_token: '"
            + TOKEN
            + "'\r\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertEquals("octocat", r.login);
    assertEquals("https", r.gitProtocol);
  }

  @Test
  public void rejectsStructuralDrift() {
    String[] bad = {
      "github.com:\n\tuser: octocat\n",
      "github.com:\n    user: octocat\ngithub.com:\n    user: other\n",
      "github.com:\n    user: octocat with space\n",
      "github.com:\n    users: filled\n",
      "github.com:\n    users:\n        bad login:\n",
      "github.com:\n    users:\n        octocat: value\n",
      "github.com:\n    user: octocat\n        oauth_token: " + TOKEN + "\n",
      "github.com\n",
      "github.com:\n    user: octocat\n        nested: x\n",
      "github.com:\n    oauth_token:\n",
      "=:\n",
      "github.com:\n    user:\n  - item\n"
    };
    for (String text : bad) {
      assertEquals(
          "should reject: " + text, GhHostsFile.Status.MALFORMED, GhHostsFile.parse(text).status);
    }
  }

  @Test
  public void rejectsOversizeInput() {
    StringBuilder text = new StringBuilder("github.com:\n");
    for (int i = 0; i < GhHostsFile.MAX_LINES + 1; i++) text.append("# line\n");
    assertEquals(GhHostsFile.Status.MALFORMED, GhHostsFile.parse(text.toString()).status);

    StringBuilder longLine = new StringBuilder("github.com:\n    user: ");
    while (longLine.length() <= GhHostsFile.MAX_LINE) longLine.append('a');
    assertEquals(GhHostsFile.Status.MALFORMED, GhHostsFile.parse(longLine.toString()).status);
  }

  @Test
  public void neverRetainsTokenValue() {
    String text =
        "github.com:\n"
            + "    user: octocat\n"
            + "    oauth_token: "
            + TOKEN
            + "\n"
            + "    users:\n"
            + "        octocat:\n"
            + "            oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertFalse(r.toString().contains(TOKEN));
    assertFalse(r.toString().contains("SECRET"));
    assertFalse(r.accounts.toString().contains(TOKEN));
    assertFalse(r.scopes.toString().contains(TOKEN));
    assertFalse(r.otherHosts.toString().contains(TOKEN));
  }

  @Test
  public void unknownScalarKeysAreIgnoredForwardCompatibly() {
    String text =
        "github.com:\n"
            + "    user: octocat\n"
            + "    future_field: some value\n"
            + "    oauth_token: "
            + TOKEN
            + "\n";
    GhHostsFile.Result r = GhHostsFile.parse(text);
    assertEquals(GhHostsFile.Status.OK, r.status);
    assertEquals("octocat", r.login);
  }
}
