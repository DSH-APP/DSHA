package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class GhAuthProbeTest {
  private static GhAuthProbe.Result classify(int exitCode, boolean timedOut, String output) {
    return GhAuthProbe.classify(exitCode, timedOut, output);
  }

  @Test
  public void validResponseCarriesScopesAndNoDetail() {
    String output =
        "HTTP/2.0 200 OK\n"
            + "X-Oauth-Scopes: gist, read:org, repo\n"
            + "Content-Type: application/json\n"
            + "\n"
            + "{\"login\":\"octocat\",\"id\":1}\n";
    GhAuthProbe.Result result = classify(0, false, output);
    assertEquals(GhAuthProbe.State.VALID, result.state);
    assertEquals(List.of("gist", "read:org", "repo"), result.scopes);
    assertEquals("", result.detail);
  }

  @Test
  public void validResponseWithoutScopeHeaderStillPasses() {
    GhAuthProbe.Result result = classify(0, false, "HTTP/1.1 200 OK\n\n{}");
    assertEquals(GhAuthProbe.State.VALID, result.state);
    assertTrue(result.scopes.isEmpty());
  }

  @Test
  public void tlsFailureIsDistinguishedFromInvalidToken() {
    String output =
        "gh: requests use the SunsetRNE GitHub identity configured in this DSHA environment\n"
            + "Get \"https://api.github.com/user\": tls: failed to verify certificate: "
            + "x509: certificate signed by unknown authority\n";
    GhAuthProbe.Result result = classify(1, false, output);
    assertEquals(GhAuthProbe.State.TLS_CA_FAILURE, result.state);
    assertTrue(result.detail.contains("x509"));
    assertFalse(result.detail.startsWith("gh: requests use"));
  }

  @Test
  public void invalidTokenFromUnauthorizedResponse() {
    GhAuthProbe.Result result = classify(1, false, "gh: Bad credentials (HTTP 401)\n");
    assertEquals(GhAuthProbe.State.TOKEN_INVALID, result.state);
    assertTrue(result.detail.contains("401") || result.detail.contains("Bad credentials"));
  }

  @Test
  public void invalidTokenFromAuthStatusWording() {
    String output =
        "github.com\n"
            + "  X Failed to log in to github.com account octocat (/root/.config/gh/hosts.yml)\n"
            + "  - The token in /root/.config/gh/hosts.yml is invalid.\n";
    assertEquals(GhAuthProbe.State.TOKEN_INVALID, classify(1, false, output).state);
  }

  @Test
  public void notLoggedInIsExplicit() {
    String output =
        "To get started with GitHub CLI, please run:  gh auth login\n"
            + "Alternatively, populate the GH_TOKEN environment variable with a GitHub API "
            + "authentication token.\n";
    assertEquals(GhAuthProbe.State.NOT_LOGGED_IN, classify(1, false, output).state);
    assertEquals(
        GhAuthProbe.State.NOT_LOGGED_IN,
        classify(1, false, "gh: not logged in to any hosts\n").state);
  }

  @Test
  public void missingManagedBinaryIsReportedWithoutParsingNetworkText() {
    String output =
        "gh: managed GitHub CLI is missing or damaged: /opt/gh/bin/gh\n"
            + "gh: nothing was executed and nothing was installed.\n";
    assertEquals(GhAuthProbe.State.BINARY_UNAVAILABLE, classify(127, false, output).state);
  }

  @Test
  public void timeoutNeverLooksLikeSuccess() {
    GhAuthProbe.Result result = classify(0, true, "HTTP/2.0 200 OK\nX-Oauth-Scopes: repo\n");
    assertEquals(GhAuthProbe.State.TIMEOUT, result.state);
    assertTrue(result.scopes.isEmpty());
  }

  @Test
  public void networkErrorsAreSeparateFromTls() {
    assertEquals(
        GhAuthProbe.State.NETWORK_ERROR,
        classify(1, false, "Get \"https://api.github.com/user\": dial tcp: looku p: no such host\n")
            .state);
    assertEquals(
        GhAuthProbe.State.NETWORK_ERROR,
        classify(1, false, "proxyconnect tcp: dial tcp 127.0.0.1:7891: connection refused\n")
            .state);
  }

  @Test
  public void unexpectedFailureKeepsAUsefulLine() {
    GhAuthProbe.Result result = classify(2, false, "something odd happened\ntry again later\n");
    assertEquals(GhAuthProbe.State.ERROR, result.state);
    assertEquals("try again later", result.detail);
  }

  @Test
  public void exitZeroWithoutHttpStatusIsNotTreatedAsValid() {
    assertEquals(GhAuthProbe.State.ERROR, classify(0, false, "{\"login\":\"octocat\"}\n").state);
    assertEquals(GhAuthProbe.State.ERROR, classify(0, false, "").state);
  }

  @Test
  public void detailRedactsTokenShapesAndQueryStrings() {
    String output =
        "Get \"https://api.github.com/user?access_token=gho_SECRETSECRETSECRETSECRETSECRET\": "
            + "Bad credentials (HTTP 401) for gho_ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";
    GhAuthProbe.Result result = classify(1, false, output);
    assertEquals(GhAuthProbe.State.TOKEN_INVALID, result.state);
    assertFalse(result.detail.contains("SECRETSECRET"));
    assertFalse(result.detail.contains("gho_ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
  }

  @Test
  public void detailIsBounded() {
    StringBuilder output = new StringBuilder("x509: ");
    while (output.length() < 1000) output.append('a');
    GhAuthProbe.Result result = classify(1, false, output.toString());
    assertEquals(GhAuthProbe.State.TLS_CA_FAILURE, result.state);
    assertTrue(result.detail.length() <= GhAuthProbe.MAX_DETAIL + 1);
  }

  @Test
  public void factoriesReportSkippedStatesWithoutDetail() {
    assertEquals(GhAuthProbe.State.SKIPPED_ENV, GhAuthProbe.Result.skippedEnvironment().state);
    assertEquals(
        GhAuthProbe.State.BINARY_UNAVAILABLE, GhAuthProbe.Result.binaryUnavailable().state);
    assertEquals(GhAuthProbe.State.ERROR, GhAuthProbe.Result.error("boom").state);
    assertEquals("boom", GhAuthProbe.Result.error("boom").detail);
  }
}
