package com.deepseekharness.app.backup;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class MaintenanceSpaceTraceTest {
  @Test
  public void stageRecordsKeepThePlanAndObservedMinimumAfterSpaceIsReclaimed() {
    long[] free = {1000};
    List<MaintenanceSpaceTrace.Sample> samples = new ArrayList<>();
    var trace = new MaintenanceSpaceTrace(() -> free[0], samples::add);
    trace.sample(MaintenanceSpaceTrace.Stage.PREFLIGHT, 700);
    free[0] = 300;
    trace.sample(MaintenanceSpaceTrace.Stage.DATA_RESTORED, 0);
    free[0] = 800;
    var committed = trace.sample(MaintenanceSpaceTrace.Stage.COMMITTED, 0);
    assertEquals(3, samples.size());
    assertEquals(700, samples.get(0).required);
    assertEquals(700, committed.peakObservedUsed);
    assertEquals(200, committed.observedUsed);
    assertEquals(800, committed.available);
    assertTrue(committed.record().contains("stage=COMMITTED availableBytes=800"));
    assertTrue(committed.record().contains("peakObservedUsedBytes=700"));
  }

  @Test
  public void unknownSpaceFailsClosedAndEveryFailurePhaseCanStillBeRecorded() {
    List<MaintenanceSpaceTrace.Sample> samples = new ArrayList<>();
    var trace = new MaintenanceSpaceTrace(() -> -1, samples::add);
    assertEquals(0, trace.sample(MaintenanceSpaceTrace.Stage.PREFLIGHT, 1024).available);
    assertEquals(0, trace.sample(MaintenanceSpaceTrace.Stage.FAILED, 0).peakObservedUsed);
    trace.sample(MaintenanceSpaceTrace.Stage.ROLLED_BACK, 0);
    assertEquals(3, samples.size());
    assertThrows(
        IllegalArgumentException.class,
        () -> trace.sample(MaintenanceSpaceTrace.Stage.PREFLIGHT, -1));
    assertEquals(3, samples.size());
  }
}
