package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

/** 维护前停止顺序：先普通停止；只有用户已确认维护时才按身份核验升级一次强制停止。 */
public final class MaintenancePreStopTest {
  private static final class Fixture implements MaintenancePreStop.Io<String> {
    int stops, prepares, forces;
    boolean forceAccepted = true;
    boolean prepareFails;
    boolean forceConfirms;
    boolean confirmed;
    boolean interruptPause;
    String lastError = "已请求停止，Web 尚未退出";
    long clock;

    public void requestStop() {
      stops++;
    }

    public boolean settled() {
      return true;
    }

    public boolean confirmed() {
      return confirmed || (forceConfirms && forces > 0);
    }

    public String lastStopError() {
      return forces > 0 ? "" : lastError;
    }

    public String prepareForce() throws IOException {
      prepares++;
      if (prepareFails) throw new IOException("WEB_RECORDED_STOP_UNCONFIRMED");
      return "identity-verified-candidate";
    }

    public boolean requestForce(String candidate) {
      forces++;
      assertEquals("identity-verified-candidate", candidate);
      return forceAccepted;
    }

    public long now() {
      return clock;
    }

    public void pause(long millis) throws InterruptedException {
      if (interruptPause) throw new InterruptedException("test interruption");
      clock += millis;
    }
  }

  @Test
  public void confirmedOrdinaryStopNeverPreparesForce() throws Exception {
    Fixture fixture = new Fixture();
    fixture.confirmed = true;
    MaintenancePreStop.stop(fixture);
    assertEquals(1, fixture.stops);
    assertEquals(0, fixture.prepares);
    assertEquals(0, fixture.forces);
  }

  @Test
  public void unconfirmedOrdinaryStopEscalatesThroughIdentityVerifiedCandidate() throws Exception {
    Fixture fixture = new Fixture();
    fixture.forceConfirms = true;
    MaintenancePreStop.stop(fixture);
    assertEquals(1, fixture.stops);
    assertEquals(1, fixture.prepares);
    assertEquals(1, fixture.forces);
  }

  @Test
  public void unavailableForceCandidateKeepsEnvironmentAndAsksForRetry() {
    Fixture fixture = new Fixture();
    fixture.prepareFails = true;
    IOException failure = assertThrows(IOException.class, () -> MaintenancePreStop.stop(fixture));
    assertTrue(failure.getMessage().contains("重试更新运行时"));
    assertTrue(failure.getMessage().contains("原运行环境未改动"));
    assertEquals(0, fixture.forces);
  }

  @Test
  public void forceAcceptedButUnconfirmedReportsExplicitFailure() {
    Fixture fixture = new Fixture();
    IOException failure = assertThrows(IOException.class, () -> MaintenancePreStop.stop(fixture));
    assertTrue(failure.getMessage().contains("强制"));
    assertTrue(failure.getMessage().contains("重试更新运行时"));
    assertEquals(1, fixture.prepares);
    assertEquals(1, fixture.forces);
  }

  @Test
  public void rejectedForceRequestIsReportedOnceWithoutRepeating() {
    Fixture fixture = new Fixture();
    fixture.forceAccepted = false;
    IOException failure = assertThrows(IOException.class, () -> MaintenancePreStop.stop(fixture));
    assertTrue(failure.getMessage().contains("未被接受"));
    assertEquals(1, fixture.forces);
  }

  @Test
  public void interruptedWaitIsNotReportedAsStopped() {
    Fixture fixture = new Fixture();
    fixture.interruptPause = true;
    assertThrows(InterruptedException.class, () -> MaintenancePreStop.stop(fixture));
    assertEquals(0, fixture.prepares);
  }
}
