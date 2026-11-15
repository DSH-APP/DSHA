package com.deepseekharness.app.util;

import org.junit.Test;
import java.net.*;
import java.io.*;
import static org.junit.Assert.*;

public class WebPortPolicyTest {
  @Test
  public void automaticPolicyUsesKernelAllocationAndOnlyItsAvailablePriorPort() throws Exception {
    assertEquals(
        0,
        WebPortPolicy.choose(
                0,
                0,
                port -> {
                  throw new AssertionError();
                })
            .listen);
    assertEquals(45678, WebPortPolicy.choose(0, 45678, port -> true).listen);
    assertEquals(0, WebPortPolicy.choose(0, 45678, port -> false).listen);
    assertEquals(
        0,
        WebPortPolicy.choose(
                0,
                3090,
                port -> {
                  throw new AssertionError();
                })
            .listen);
  }

  @Test
  public void bindRaceNeedsExactPortExitAndAnUnusedRetryBeforeAuthentication() {
    assertTrue(WebPortPolicy.retryBindRace(45678, false, false, 45678, true));
    assertFalse(WebPortPolicy.retryBindRace(45678, true, false, 45678, true));
    assertFalse(WebPortPolicy.retryBindRace(45678, false, true, 45678, true));
    assertFalse(WebPortPolicy.retryBindRace(45678, false, false, 45679, true));
    assertFalse(WebPortPolicy.retryBindRace(45678, false, false, 45678, false));
    String conflict = "@deepseek-ai/dsh-host-webserver: listen EADDRINUSE 127.0.0.1:45678";
    assertTrue(WebPortPolicy.onlyBindConflict(java.util.Map.of("", conflict), 45678));
    assertFalse(
        WebPortPolicy.onlyBindConflict(java.util.Map.of("", conflict, "plugin", "broken"), 45678));
    assertFalse(WebPortPolicy.onlyBindConflict(java.util.Map.of("", conflict), 45679));
    assertFalse(WebPortPolicy.onlyBindConflict(java.util.Map.of(), 45678));
  }

  @Test
  public void retainsPreferredAndReusesOnlyAvailableCachedPort() throws Exception {
    assertEquals(3080, WebPortPolicy.choose(3080, 40000, port -> true).listen);
    assertEquals(40000, WebPortPolicy.choose(3080, 40000, port -> port == 40000).listen);
    assertEquals(0, WebPortPolicy.choose(3080, 40000, port -> false).listen);
    assertEquals(
        0,
        WebPortPolicy.choose(
                3090,
                3081,
                port -> {
                  throw new AssertionError("reserved port probed");
                })
            .listen);
  }

  @Test
  public void everyNativeAndLanPortIsExcludedWithoutProbingOrAuthenticating() throws Exception {
    String token = "a".repeat(43);
    for (int port :
        new int[] {3081, 3082, 3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099}) {
      assertTrue(WebPortPolicy.reserved(port));
      assertEquals(
          0,
          WebPortPolicy.choose(
                  port,
                  port,
                  candidate -> {
                    throw new AssertionError("reserved port probed");
                  })
              .listen);
      assertNull(
          WebPortPolicy.authentication(
              "dsh web: http://127.0.0.1:" + port + "/?token=" + token, 0));
    }
    assertFalse(WebPortPolicy.reserved(3080));
    assertFalse(WebPortPolicy.reserved(3083));
    assertFalse(WebPortPolicy.reserved(3100));
  }

  @Test
  public void bindsWithoutConnectingToOrDisturbingOccupiedListener() throws Exception {
    try (ServerSocket owner = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      assertFalse(WebPortPolicy.available(owner.getLocalPort()));
      assertEquals(
          0, WebPortPolicy.choose(owner.getLocalPort(), 0, WebPortPolicy::available).listen);
      assertFalse(owner.isClosed());
    }
  }

  @Test
  public void probeFailuresDoNotPretendToBePortCollisions() {
    try {
      WebPortPolicy.choose(
          3080,
          0,
          port -> {
            throw new IOException("permission denied");
          });
      fail();
    } catch (IOException expected) {
      assertEquals("permission denied", expected.getMessage());
    }
  }

  @Test
  public void dynamicPortRequiresUnambiguousOfficialLoopbackStartupLine() {
    String token = "a".repeat(43), url = "http://127.0.0.1:45678/?token=" + token;
    assertEquals(url, WebPortPolicy.authentication("dsh web: " + url + "\n", 0));
    assertNull(WebPortPolicy.authentication("plugin example " + url, 0));
    assertNull(
        WebPortPolicy.authentication(
            "dsh web: " + url + "\ndsh web: http://127.0.0.1:45679/?token=" + token, 0));
    assertNull(WebPortPolicy.authentication("dsh web: http://localhost:45678/?token=" + token, 0));
    assertNull(WebPortPolicy.authentication("dsh web: http://127.0.0.1:3090/?token=" + token, 0));
    assertNull(WebPortPolicy.authentication("dsh web: " + url, 3080));
    assertNull(WebPortPolicy.authentication("plugin example " + url, 45678));
    assertEquals(url, WebPortPolicy.authentication("dsh web: " + url, 45678));
    assertNull(WebPortPolicy.authentication("dsh web: " + url, 45679));
  }

  @Test
  public void identifiesTheReportedWebBindFailureWithoutMisclassifyingOtherPluginErrors() {
    String error =
        "@deepseek-ai/dsh-host-webserver: listen EADDRINUSE: address already in use 127.0.0.1:3080";
    assertEquals(3080, WebPortPolicy.conflictPort(error));
    assertEquals(-1, WebPortPolicy.conflictPort("a-plugin: listen EADDRINUSE 127.0.0.1:3090"));
    assertEquals(
        -1,
        WebPortPolicy.conflictPort("@deepseek-ai/dsh-host-webserver: Cannot find module example"));
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.issue(1, 10, "@deepseek-ai/dsh-web-app", error);
    assertTrue(trace.snapshot(20, "en").log.contains("Web port in use: 3080"));
    assertFalse(trace.snapshot(20, "en").issues.containsKey("@deepseek-ai/dsh-web-app"));
  }
}
