package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WebSessionProofTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final String INSTANCE = "23456789-1234-1234-1234-123456789abc";
  private static final String NONCE = "ab".repeat(32);
  private static final String WEB =
      String.join(
          "\0",
          "node",
          "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
          "web",
          "--no-open",
          "--host",
          "127.0.0.1",
          "--port",
          "3080",
          "");
  private static final String LAUNCHER =
      "/data/app/lib/arm64/libproot.so\0--rootfs=/data/user/0/com.dsh.client/files/linux/ubuntu\0";

  private WebSessionProof.Saved saved() {
    return new WebSessionProof.Saved(BOOT, 7, INSTANCE, NONCE, 101, 1234, WEB, 100, 1233, LAUNCHER);
  }

  private WebSessionProof.Process web() {
    return new WebSessionProof.Process(101, 1234, 10001, WEB, "7", INSTANCE, NONCE, true);
  }

  private WebSessionProof.Process launcher() {
    return new WebSessionProof.Process(100, 1233, 10001, LAUNCHER, "7", INSTANCE, NONCE, true);
  }

  private boolean accepts(WebSessionProof.Process web, WebSessionProof.Process parent) {
    return WebSessionProof.matches(saved(), BOOT, 10001, web, parent);
  }

  @Test
  public void sameLiveGuestAndLauncherAreRecognized() {
    assertTrue(accepts(web(), launcher()));
  }

  @Test
  public void exitedLauncherStillRequiresTheSameLiveGuest() {
    var gone = new WebSessionProof.Process(100, 0, -1, "", "", "", "", false);
    assertTrue(accepts(web(), gone));
    assertFalse(accepts(new WebSessionProof.Process(101, 0, -1, "", "", "", "", false), gone));
  }

  @Test
  public void crossBootAndDifferentUidNeverRecognized() {
    assertFalse(WebSessionProof.matches(saved(), INSTANCE, 10001, web(), launcher()));
    assertFalse(WebSessionProof.matches(saved(), BOOT, 10002, web(), launcher()));
  }

  @Test
  public void pidReuseAndMissingParentEvidenceNeverRecognized() {
    var reused = new WebSessionProof.Process(101, 1235, 10001, WEB, "7", INSTANCE, NONCE, true);
    assertFalse(accepts(reused, launcher()));
    assertFalse(accepts(web(), null));
    assertFalse(
        accepts(
            web(),
            new WebSessionProof.Process(100, 9999, 10001, LAUNCHER, "7", INSTANCE, NONCE, true)));
  }

  @Test
  public void staleNonceGenerationInstanceOrCommandNeverRecognized() {
    for (var p :
        java.util.List.of(
            new WebSessionProof.Process(101, 1234, 10001, WEB, "6", INSTANCE, NONCE, true),
            new WebSessionProof.Process(101, 1234, 10001, WEB, "7", BOOT, NONCE, true),
            new WebSessionProof.Process(
                101, 1234, 10001, WEB, "7", INSTANCE, "cd".repeat(32), true),
            new WebSessionProof.Process(
                101, 1234, 10001, "node\0user.js\0", "7", INSTANCE, NONCE, true)))
      assertFalse(accepts(p, launcher()));
  }

  @Test
  public void stopAndNewStartFenceAdoption() {
    WebLifecycle gate = new WebLifecycle();
    assertTrue(gate.adoptRunning(7));
    assertFalse(gate.isStarting());
    assertEquals(8, gate.beginStop());
    assertFalse(gate.adoptRunning(7));
    WebLifecycle stopped = new WebLifecycle();
    stopped.beginStop();
    assertFalse(stopped.adoptRunning(7));
    WebLifecycle started = new WebLifecycle();
    started.beginStart(false, false);
    assertFalse(started.adoptRunning(7));
  }
}
