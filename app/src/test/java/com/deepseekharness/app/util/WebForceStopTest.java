package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebForceStopTest {
  private static WebForceStop.Evidence evidence(
      long birth, String generation, int uid, String record) {
    return new WebForceStop.Evidence(
        321,
        birth,
        uid,
        10123,
        record,
        "321 " + birth,
        generation,
        "node\0/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js\0web\0--no-open",
        true);
  }

  @Test
  public void exactConfirmedCandidateSignalsOnceOnlyItsCapturedPid() throws Exception {
    Object owner = new Object();
    var proof = evidence(10, "7", 10123, "321\n");
    var candidate = WebForceStop.prepare(owner, () -> proof);
    AtomicInteger signals = new AtomicInteger();
    WebForceStop.signal(
        owner,
        candidate,
        () -> proof,
        pid -> {
          assertEquals(321, pid);
          signals.incrementAndGet();
        });
    assertEquals(1, signals.get());
    assertThrows(
        IOException.class,
        () -> WebForceStop.signal(owner, candidate, () -> proof, pid -> signals.incrementAndGet()));
    assertEquals(1, signals.get());
  }

  @Test
  public void reusedPidChangedGenerationRecordAndUidNeverSignal() throws Exception {
    Object owner = new Object();
    var proof = evidence(10, "7", 10123, "321\n");
    for (var changed :
        new WebForceStop.Evidence[] {
          evidence(11, "7", 10123, "321\n"), evidence(10, "8", 10123, "321\n"),
          evidence(10, "7", 10124, "321\n"), evidence(10, "7", 10123, "321")
        }) {
      var candidate = WebForceStop.prepare(owner, () -> proof);
      assertThrows(
          IOException.class,
          () ->
              WebForceStop.signal(owner, candidate, () -> changed, pid -> fail("must not signal")));
    }
    assertThrows(
        IOException.class,
        () -> WebForceStop.prepare(owner, () -> evidence(10, null, 10123, "321\n")));
  }

  @Test
  public void secondReadAndOwnerAreIndependentConfirmationFences() throws Exception {
    Object owner = new Object();
    var proof = evidence(10, "7", 10123, "321\n");
    var candidate = WebForceStop.prepare(owner, () -> proof);
    AtomicInteger reads = new AtomicInteger();
    assertThrows(
        IOException.class,
        () ->
            WebForceStop.signal(
                owner,
                candidate,
                () -> reads.incrementAndGet() == 1 ? proof : evidence(11, "7", 10123, "321\n"),
                pid -> fail()));
    assertThrows(
        IOException.class,
        () -> WebForceStop.signal(new Object(), candidate, () -> proof, pid -> fail()));
  }
}
