package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GhAuthCommandsTest {
  @Test
  public void loginUsesWebFlowOnGitHubCom() {
    String command = GhAuthCommands.login();
    assertTrue(command.startsWith("gh auth login --web "));
    assertTrue(command.contains("--hostname github.com"));
    assertTrue(command.contains("--git-protocol https"));
    assertFalse(command.contains("token"));
  }

  @Test
  public void refreshAndStatusAreFixedHostCommands() {
    assertEquals("gh auth refresh --hostname github.com", GhAuthCommands.refresh());
    assertEquals("gh auth status --hostname github.com", GhAuthCommands.status());
  }

  @Test
  public void logoutQuotesValidatedAccount() {
    assertEquals("gh auth logout --hostname github.com", GhAuthCommands.logout(null));
    assertEquals("gh auth logout --hostname github.com", GhAuthCommands.logout(""));
    assertEquals(
        "gh auth logout --hostname github.com --user 'octocat'", GhAuthCommands.logout("octocat"));
  }

  @Test
  public void logoutRejectsInjectionAndInvalidAccounts() {
    String[] bad = {
      "octo cat", "octo;cat", "octo$(true)", "-octo", "octo-", "octo'cat", "octo\ncat"
    };
    for (String account : bad) {
      try {
        GhAuthCommands.logout(account);
        throw new AssertionError("should reject: " + account);
      } catch (IllegalArgumentException expected) {
        assertEquals("GH_ACCOUNT_INVALID", expected.getMessage());
      }
    }
  }

  @Test
  public void guestArgvRunsThroughLoginShell() {
    String[] argv = GhAuthCommands.loginGuestArgv();
    assertEquals(3, argv.length);
    assertEquals("/bin/bash", argv[0]);
    assertEquals("-lc", argv[1]);
    assertTrue(argv[2].startsWith(GhAuthCommands.login()));
    assertTrue(argv[2].trim().endsWith("exec /bin/bash -l"));
    assertFalse(argv[2].contains("oauth_token"));

    String[] refresh = GhAuthCommands.refreshGuestArgv();
    assertTrue(refresh[2].startsWith(GhAuthCommands.refresh()));
    String[] status = GhAuthCommands.statusGuestArgv();
    assertTrue(status[2].startsWith(GhAuthCommands.status()));
  }

  @Test
  public void accountValidationMatchesGitHubRules() {
    assertTrue(GhAuthCommands.validAccount("octocat"));
    assertTrue(GhAuthCommands.validAccount("SunsetRNE"));
    assertTrue(GhAuthCommands.validAccount("a-b-c"));
    assertTrue(GhAuthCommands.validAccount("A1"));
    assertFalse(GhAuthCommands.validAccount(null));
    assertFalse(GhAuthCommands.validAccount(""));
    assertFalse(GhAuthCommands.validAccount("-a"));
    assertFalse(GhAuthCommands.validAccount("a-"));
    assertFalse(GhAuthCommands.validAccount("a--"));
    assertFalse(GhAuthCommands.validAccount("a b"));
    assertFalse(GhAuthCommands.validAccount("a".repeat(40)));
  }

  @Test
  public void browserUrlIsFixed() {
    assertEquals("https://github.com/login/device", GhAuthCommands.BROWSER_URL);
  }

  @Test
  public void emptyCommandCannotBecomeGuestArgv() {
    try {
      GhAuthCommands.guestArgv("");
      throw new AssertionError("empty command must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("GH_COMMAND_EMPTY", expected.getMessage());
    }
    try {
      GhAuthCommands.guestArgv(null);
      throw new AssertionError("null command must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("GH_COMMAND_EMPTY", expected.getMessage());
    }
  }
}
