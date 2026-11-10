package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GhLoginStateTest {
  private static GhHostsFile.Result hosts(String text) {
    return GhHostsFile.parse(text);
  }

  @Test
  public void absentFileIsNotLoggedIn() {
    assertTrue(GhLoginState.classify(false, null) == GhLoginState.Login.NOT_LOGGED_IN);
  }

  @Test
  public void accountAndTokenIsLoggedIn() {
    GhLoginState.Login login =
        GhLoginState.classify(
            true, hosts("github.com:\n" + "    user: octocat\n" + "    oauth_token: gho_value\n"));
    assertTrue(login == GhLoginState.Login.LOGGED_IN);
    assertTrue(GhLoginState.canLogout(login));
  }

  @Test
  public void userWithoutTokenNeedsCredentialButLoginIsAllowed() {
    GhLoginState.Login login =
        GhLoginState.classify(true, hosts("github.com:\n    user: octocat\n"));
    assertTrue(login == GhLoginState.Login.CREDENTIAL_MISSING);
    assertFalse(GhLoginState.canLogout(login));
  }

  @Test
  public void tokenWithoutUserIsAccountUnknown() {
    GhLoginState.Login login =
        GhLoginState.classify(true, hosts("github.com:\n    oauth_token: gho_value\n"));
    assertTrue(login == GhLoginState.Login.ACCOUNT_UNKNOWN);
    assertTrue(GhLoginState.canLogout(login));
  }

  @Test
  public void malformedFileIsReportedNotTreatedAsLoggedOut() {
    GhLoginState.Login login = GhLoginState.classify(true, hosts("github.com:\n\tuser: octocat\n"));
    assertTrue(login == GhLoginState.Login.MALFORMED);
    assertFalse(GhLoginState.canLogout(login));
  }

  @Test
  public void otherHostsOnlyIsNotGitHubComLogin() {
    GhLoginState.Login login =
        GhLoginState.classify(
            true,
            hosts(
                "enterprise.example.com:\n"
                    + "    user: someone\n"
                    + "    oauth_token: gho_value\n"));
    assertTrue(login == GhLoginState.Login.OTHER_HOSTS_ONLY);
    assertFalse(GhLoginState.canLogout(login));
  }

  @Test
  public void emptyFileIsNotLoggedIn() {
    GhLoginState.Login login = GhLoginState.classify(true, hosts(""));
    assertTrue(login == GhLoginState.Login.NOT_LOGGED_IN);
  }
}
