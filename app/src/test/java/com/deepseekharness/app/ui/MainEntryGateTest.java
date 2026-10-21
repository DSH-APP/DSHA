package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class MainEntryGateTest {
  @Test
  public void slowInspectionNeverRunsOnUiAndDeliveryIsSeparate() {
    MainEntryGate gate = new MainEntryGate();
    List<Runnable> io = new ArrayList<>(), ui = new ArrayList<>();
    List<Boolean> results = new ArrayList<>();
    gate.submit(io::add, ui::add, () -> true, () -> true, (m, e) -> results.add(m));
    assertEquals(1, io.size());
    assertTrue(ui.isEmpty());
    assertTrue(results.isEmpty());
    io.remove(0).run();
    assertTrue(results.isEmpty());
    ui.remove(0).run();
    assertEquals(List.of(true), results);
  }

  @Test
  public void pauseDestroyOrNewIntentRevokesQueuedNavigation() {
    for (int phase = 0; phase < 2; phase++) {
      MainEntryGate gate = new MainEntryGate();
      List<Runnable> io = new ArrayList<>(), ui = new ArrayList<>();
      gate.submit(io::add, ui::add, () -> true, () -> true, (m, e) -> fail("Stale navigation"));
      if (phase == 0) gate.cancel();
      io.remove(0).run();
      if (phase == 1) gate.cancel();
      ui.remove(0).run();
    }
  }

  @Test
  public void onlyLatestRequestCanDeliverAndUnknownDoesNotBecomeMaintenance() {
    MainEntryGate gate = new MainEntryGate();
    List<Runnable> io = new ArrayList<>(), ui = new ArrayList<>();
    List<String> results = new ArrayList<>();
    gate.submit(io::add, ui::add, () -> true, () -> true, (m, e) -> fail("Old request"));
    gate.submit(
        io::add,
        ui::add,
        () -> {
          throw new java.io.IOException("UNKNOWN");
        },
        () -> true,
        (m, e) -> results.add(m + ":" + e));
    io.forEach(Runnable::run);
    ui.forEach(Runnable::run);
    assertEquals(List.of("false:true"), results);
  }

  @Test
  public void changedLifecycleRejectsEvenUncancelledDelivery() {
    MainEntryGate gate = new MainEntryGate();
    List<Runnable> io = new ArrayList<>(), ui = new ArrayList<>();
    AtomicBoolean alive = new AtomicBoolean(true);
    gate.submit(io::add, ui::add, () -> true, alive::get, (m, e) -> fail("Destroyed owner"));
    io.remove(0).run();
    alive.set(false);
    ui.remove(0).run();
  }
}
